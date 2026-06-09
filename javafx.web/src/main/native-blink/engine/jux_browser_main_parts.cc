// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxBrowserMainParts — browser process initialization and shutdown.
//
// Creates the BrowserContext, initializes the aura/views environment,
// and sets up the IPC layer (heartbeat, command dispatch, ring buffers)
// for communication with the Java side via shared memory.

#include "jux/jux_browser_main_parts.h"

#include <utility>

#include "base/logging.h"
#include "base/run_loop.h"
#include "build/build_config.h"
#include "base/files/file_path.h"
#include "base/functional/bind.h"
#include "base/location.h"
#include "base/path_service.h"
#include "base/threading/platform_thread.h"
#include "base/time/time.h"
#include "content/public/common/result_codes.h"
#include "jux/jux_browser_context.h"
#include "jux/jux_command_dispatch.h"
#include "jux/jux_event_types.h"

#include "jux/jux_heartbeat.h"
#include "jux/jux_ipc.h"
#include "jux/jux_ring_buffer.h"
#include "printing/buildflags/buildflags.h"
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
#include "chrome/browser/ui/webui/print_preview/print_preview_ui.h"
#include "chrome/browser/ui/webui/print_preview/print_preview_ui_untrusted.h"
#include "content/public/browser/webui_config_map.h"
#include "jux/print_preview/shim/jux_browser_process.h"
#include "jux/print_preview/shim/jux_print_preview_hook.h"
namespace content {
class WebContents;
}
namespace jux {
// Defined in jux_engine_api.cc (same jux_engine target) — creates/destroys the
// off-screen chrome://print WebContents reusing the OSR machinery.
content::WebContents* OpenPrintPreviewWebContents(content::WebContents* initiator);
void ClosePrintPreviewWebContents(content::WebContents* preview);
}  // namespace jux
#endif
#include "ui/aura/env.h"
#include "ui/display/screen.h"
#include "jux/jux_views_delegate.h"
#include "ui/views/widget/desktop_aura/desktop_screen.h"
#include "ui/wm/core/wm_state.h"

#if BUILDFLAG(IS_LINUX)
#include "ui/base/ime/init/input_method_initializer.h"
#endif

// Declared in jux_engine_api.cc — returns the shared memory channel
// pointer stored by JuxRunBrowser before calling ContentMain.
jux::ipc::SharedMemoryChannel* JuxGetRunBrowserChannel();

namespace jux {

JuxBrowserMainParts::JuxBrowserMainParts() = default;
JuxBrowserMainParts::~JuxBrowserMainParts() = default;

// static
void JuxBrowserMainParts::RunWithBlockingAllowed(base::OnceClosure task) {
  // JuxBrowserMainParts is a friend of ScopedAllowBlocking, so
  // construction/destruction of the scoped guard is permitted here.
  // The guard lives on the stack — no unique_ptr needed.
  base::ScopedAllowBlocking allow_blocking;
  std::move(task).Run();
}

int JuxBrowserMainParts::PreEarlyInitialization() {
#if BUILDFLAG(IS_LINUX)
  // Required for IME support on Linux.
  ui::InitializeInputMethod();
#endif
  return content::RESULT_CODE_NORMAL_EXIT;
}

int JuxBrowserMainParts::PreMainMessageLoopRun() {
  // Explicitly create aura::Env if not already present. This singleton
  // manages event processing, input, and window tree host creation.
  // Matches the pattern used by content_shell.
  if (!aura::Env::HasInstance()) {
    env_ = aura::Env::CreateInstance();
  }

  // Initialize aura/views environment for windowed rendering.
  wm_state_ = std::make_unique<wm::WMState>();

  // Initialize the display::Screen singleton (desktop screen for all platforms).
  if (!display::Screen::HasScreen()) {
    screen_ = views::CreateDesktopScreen();
  }

  // Views delegate — provides default behavior for views::Widget creation.
  if (!views::ViewsDelegate::GetInstance()) {
    views_delegate_ = std::make_unique<JuxViewsDelegate>();
  }

  // Create the default browser context (persistent storage).
  // Data goes to <platform-app-data>/jux-framework/data/.
  base::FilePath user_data_dir;
#if BUILDFLAG(IS_WIN)
  base::PathService::Get(base::DIR_LOCAL_APP_DATA, &user_data_dir);
  user_data_dir = user_data_dir.Append(FILE_PATH_LITERAL("jux-framework"))
                               .Append(FILE_PATH_LITERAL("data"));
#elif BUILDFLAG(IS_MAC)
  base::PathService::Get(base::DIR_APP_DATA, &user_data_dir);
  user_data_dir = user_data_dir.Append(FILE_PATH_LITERAL("jux-framework"))
                               .Append(FILE_PATH_LITERAL("data"));
#else
  base::PathService::Get(base::DIR_HOME, &user_data_dir);
  user_data_dir = user_data_dir.Append(FILE_PATH_LITERAL(".jux-framework"))
                               .Append(FILE_PATH_LITERAL("data"));
#endif
  browser_context_ =
      std::make_unique<JuxBrowserContext>(user_data_dir);

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  // Install a minimal g_browser_process FIRST — PrintViewManager(Base), attached
  // to every WebContents, dereferences it in its constructor (print_job_manager),
  // so it must exist before any WebContents is created.
  jux::InstallPrintPreviewBrowserProcess();

  // Register chrome://print → PrintPreviewUI. WebUIConfigMap installs its own
  // WebUIControllerFactory, so adding the config is all that's needed for the
  // scheme to resolve; PrintPreviewUI's ctor sets up the WebUIDataSource that
  // serves the print_preview pak. The off-screen preview WebContents that
  // navigates here is created by the preview controller (next milestone).
  content::WebUIConfigMap::GetInstance().AddWebUIConfig(
      std::make_unique<printing::PrintPreviewUIConfig>());
  // Register chrome-untrusted://print → PrintPreviewUIUntrusted. This is the
  // WebUI that SERVES the generated preview PDF bytes (from PrintPreviewDataService)
  // for the PDF viewer's <embed src="chrome-untrusted://print/<id>/<page>/print.pdf">.
  // Without it the embed load fails → the in-process PDF plugin has no data source
  // and the renderer crashes (RenderProcessGone → reload loop). Untrusted
  // configs go through AddUntrustedWebUIConfig — AddWebUIConfig CHECK_EQs the
  // scheme against chrome:// and aborts for chrome-untrusted://.
  content::WebUIConfigMap::GetInstance().AddUntrustedWebUIConfig(
      std::make_unique<printing::PrintPreviewUIUntrustedConfig>());

  // Wire the controller→engine bridge: window.print()/Ctrl+P → PrintViewManager
  // → PrintPreviewDialogController::PrintPreview → jux::OpenPrintPreview → this
  // factory, which opens the off-screen chrome://print WebContents.
  jux::SetOpenPrintPreviewHook(&jux::OpenPrintPreviewWebContents);
  jux::SetClosePrintPreviewHook(&jux::ClosePrintPreviewWebContents);
#endif

  // Set up the IPC layer (heartbeat, command dispatch, ring buffers).
  SetupIPC();

  return content::RESULT_CODE_NORMAL_EXIT;
}

void JuxBrowserMainParts::WillRunMainMessageLoop(
    std::unique_ptr<base::RunLoop>& run_loop) {
  // Store the quit closure so JuxShutdown() can stop the message loop.
  quit_closure_ = run_loop->QuitClosure();
}

void JuxBrowserMainParts::PostMainMessageLoopRun() {
  // Tear down IPC before anything else.
  TeardownIPC();

  // Tear down in reverse order of creation.
  browser_context_.reset();
  views_delegate_.reset();
  screen_.reset();
  wm_state_.reset();
  env_.reset();
}

base::OnceClosure JuxBrowserMainParts::GetQuitClosure() {
  return std::move(quit_closure_);
}

void JuxBrowserMainParts::OnGpuProcessCrashed() {
  LOG(ERROR) << "GPU process crashed — Chromium will auto-restart it";

  // Notify Java via the event ring buffer so the application can log
  // the crash or trigger a redraw.
  if (evt_writer_ && channel_) {
    evt_writer_->WriteEvent(events::kGpuProcessCrashed,
                            channel_->window_id());
  }
}

// =========================================================================
// IPC Layer Setup / Teardown
// =========================================================================

void JuxBrowserMainParts::SetupIPC() {
  // Read the shared memory channel pointer stored by JuxRunBrowser.
  channel_ = JuxGetRunBrowserChannel();
  if (!channel_) {
    VLOG(1) << "No shared memory channel — IPC not configured "
              << "(standalone mode or child process)";
    return;
  }

  VLOG(1) << "Setting up IPC layer";

  // Start the heartbeat thread — writes engine heartbeat every 200ms.
  StartHeartbeat(channel_.get());

  // Create event writer (kept alive for the dispatcher and direct use).
  evt_writer_ = std::make_unique<EventWriter>(channel_->EventBuffer());

  // Create the command dispatcher with its own command reader.
  dispatcher_ = std::make_unique<CommandDispatcher>(
      CommandReader(channel_->CommandBuffer()),
      evt_writer_.get(), channel_.get());

  // Register for GPU process crash notifications.
  content::GpuDataManager::GetInstance()->AddObserver(this);

  // Signal ENGINE_RUNNING to the Java side.
  channel_->SetEngineState(ipc::kEngineRunning);
  VLOG(1) << "ENGINE_RUNNING set";

  // Write ENGINE_READY event so Java knows we're fully operational.
  evt_writer_->WriteEvent(events::kEngineReady, 0);

#if BUILDFLAG(IS_WIN)
  // Legacy Win32 WM_TIMER — only fires while the message pump is busy, so it's
  // just a fast-path on Windows. The real cross-platform driver is below.
  CreateCommandPollTimer();
  // Zero-latency wake for interactive input: a dedicated thread posts
  // WM_JUX_DRAIN the moment a command lands, so the last keystroke before the
  // engine goes idle (typically Enter) drains immediately instead of waiting up
  // to one 8ms cmd_timer_ tick.
  StartCommandDoorbell();
#endif

  // Cross-platform command drain: a UI-task-runner timer that fires even while
  // the hidden background window is idle (Win/macOS/Linux). Chromium wakes the
  // loop for delayed tasks, so commands sent after a static page loads still
  // drain promptly. (Replaces the Windows-only WM_TIMER dependency and the
  // POSIX "not implemented" gap.)
  cmd_timer_.Start(FROM_HERE, base::Milliseconds(8),
                   base::BindRepeating(&JuxBrowserMainParts::PollCommands,
                                       base::Unretained(this)));

  VLOG(1) << "IPC layer ready — command polling active";
}

void JuxBrowserMainParts::PollCommands() {
  if (!dispatcher_) {
    return;
  }
  dispatcher_->ProcessPendingCommands();
  if (dispatcher_->shutdown_requested()) {
    cmd_timer_.Stop();
    if (quit_closure_) {
      std::move(quit_closure_).Run();
    }
  }
}

void JuxBrowserMainParts::TeardownIPC() {
  if (!channel_) {
    return;
  }

  VLOG(1) << "Tearing down IPC layer";

  // Unregister GPU crash observer.
  content::GpuDataManager::GetInstance()->RemoveObserver(this);

  // Stop the cross-platform command-drain timer (runs on this UI thread).
  cmd_timer_.Stop();

#if BUILDFLAG(IS_WIN)
  // Stop the doorbell thread FIRST — its loop reads dispatcher_ and timer_hwnd_,
  // both torn down just below / in dispatcher_.reset().
  StopCommandDoorbell();
  // Kill the legacy WM_TIMER and destroy the hidden message-only window.
  if (timer_hwnd_) {
    KillTimer(timer_hwnd_, 1);
    DestroyWindow(timer_hwnd_);
    timer_hwnd_ = nullptr;
  }
#endif

  // Destroy the command dispatcher.
  dispatcher_.reset();

  // Stop the heartbeat thread.
  StopHeartbeat();

  // Release event writer.
  evt_writer_.reset();

  channel_ = nullptr;
}

// =========================================================================
// Win32 Command Poll Timer
// =========================================================================

#if BUILDFLAG(IS_WIN)

// Custom message posted by the background command-poll thread to wake the
// (possibly idle) UI message pump and drain commands in the native WndProc
// context (WM_USER range, private to this window class).
static constexpr UINT WM_JUX_DRAIN = WM_USER + 1;

void JuxBrowserMainParts::CreateCommandPollTimer() {
  // Register a window class for the hidden message-only window.
  WNDCLASSW wc = {};
  wc.lpfnWndProc = TimerWndProc;
  wc.hInstance = GetModuleHandle(nullptr);
  wc.lpszClassName = L"JuxCommandPoll";
  RegisterClassW(&wc);

  // Create a message-only window (HWND_MESSAGE parent — not visible).
  timer_hwnd_ = CreateWindowExW(
      0, L"JuxCommandPoll", nullptr, 0,
      0, 0, 0, 0, HWND_MESSAGE,
      nullptr, wc.hInstance, nullptr);

  if (!timer_hwnd_) {
    LOG(ERROR) << "Failed to create command poll timer window";
    return;
  }

  // Store the this pointer for the static WndProc.
  SetWindowLongPtrW(timer_hwnd_, GWLP_USERDATA,
                    reinterpret_cast<LONG_PTR>(this));

  // Start a 1ms timer. WM_TIMER is dispatched by the native Win32
  // message pump, NOT by Chromium's task runner, so the callback
  // context does NOT have DisallowBlocking set. This is critical —
  // widget creation (CreateWindowExW) is a blocking call and would
  // DCHECK if called from a Chromium-posted task.
  SetTimer(timer_hwnd_, 1, 1, nullptr);

  VLOG(1) << "Command poll timer started (1ms, HWND=" << timer_hwnd_ << ")";
}

void JuxBrowserMainParts::StartCommandDoorbell() {
  if (cmd_doorbell_thread_) {
    return;
  }
  cmd_doorbell_running_.store(true, std::memory_order_release);
  cmd_doorbell_thread_ = std::make_unique<base::Thread>("jux-cmd-doorbell");
  base::Thread::Options options;
  options.message_pump_type = base::MessagePumpType::DEFAULT;
  cmd_doorbell_thread_->StartWithOptions(std::move(options));

  // Capture the HWND by value; `this` outlives the thread (joined in
  // TeardownIPC before any member it touches is destroyed).
  HWND hwnd = timer_hwnd_;
  cmd_doorbell_thread_->task_runner()->PostTask(
      FROM_HERE,
      base::BindOnce(
          [](JuxBrowserMainParts* self, HWND hwnd) {
            while (self->cmd_doorbell_running_.load(std::memory_order_acquire)) {
              CommandDispatcher* d = self->dispatcher_.get();
              if (d && d->HasPendingCommands()) {
                // Wake the (possibly idle) UI message pump so it drains the ring
                // NOW. Dispatch runs in the WndProc WM_JUX_DRAIN context, where
                // blocking commands (window creation) are legal. One post drains
                // the whole batch (ProcessPendingCommands loops); the 1ms settle
                // keeps us from flooding the queue while the UI thread catches up.
                ::PostMessageW(hwnd, WM_JUX_DRAIN, 0, 0);
                base::PlatformThread::Sleep(base::Milliseconds(1));
              } else {
                // Idle: re-check at sub-frame latency (cheap atomic ring read)
                // without busy-spinning a core.
                base::PlatformThread::Sleep(base::Microseconds(500));
              }
            }
          },
          base::Unretained(this), hwnd));

  VLOG(1) << "Command doorbell thread started";
}

void JuxBrowserMainParts::StopCommandDoorbell() {
  cmd_doorbell_running_.store(false, std::memory_order_release);
  if (cmd_doorbell_thread_) {
    // Stop() flushes the queue and joins; the loop exits within one ~1ms/500us
    // sleep of the flag flip above.
    cmd_doorbell_thread_->Stop();
    cmd_doorbell_thread_.reset();
  }
}

// static
LRESULT CALLBACK JuxBrowserMainParts::TimerWndProc(
    HWND hwnd, UINT msg, WPARAM wparam, LPARAM lparam) {
  if (msg == WM_TIMER || msg == WM_JUX_DRAIN) {
    auto* self = reinterpret_cast<JuxBrowserMainParts*>(
        GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    if (self && self->dispatcher_) {
      // Poll the command ring buffer and dispatch commands.
      // This runs in a native Win32 message dispatch context, so
      // blocking calls (like CreateWindowExW) are allowed.
      self->dispatcher_->ProcessPendingCommands();

      // Check if shutdown was requested by CMD_DESTROY_WINDOW from Java.
      // The X button close is now intercepted by JuxWidgetDelegate,
      // which forwards to Java — the engine only shuts down when Java
      // responds with CMD_DESTROY_WINDOW.
      if (self->dispatcher_->shutdown_requested()) {
        KillTimer(hwnd, 1);
        // Quit the message loop — ContentMain will return.
        if (self->quit_closure_) {
          std::move(self->quit_closure_).Run();
        }
      }
    }
    return 0;
  }

  return DefWindowProcW(hwnd, msg, wparam, lparam);
}

#endif  // IS_WIN

}  // namespace jux
