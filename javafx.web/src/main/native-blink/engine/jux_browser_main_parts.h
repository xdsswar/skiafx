// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxBrowserMainParts — browser process lifecycle management.
//
// Sets up the BrowserContext (storage, cookies, network), initializes
// aura/display on desktop platforms, stores the quit closure so
// JuxShutdown() can stop the message loop, and manages the IPC layer
// (heartbeat, command dispatch, ring buffers) for communication with
// the Java side.
//
// The IPC setup runs in PreMainMessageLoopRun, which executes on the
// main thread (the browser UI thread) BEFORE the message loop starts.
// A native Win32 timer (WM_TIMER) polls the command ring buffer every
// 1ms. This avoids Chromium's DisallowBlocking restriction that applies
// to posted tasks — widget creation (CreateWindowExW) is blocking and
// must not run from a Chromium-posted task.

#ifndef JUX_BROWSER_MAIN_PARTS_H_
#define JUX_BROWSER_MAIN_PARTS_H_

#include <atomic>
#include <memory>

#include "base/timer/timer.h"

#include "base/functional/callback.h"
#include "base/memory/raw_ptr.h"
#include "base/threading/thread.h"
#include "base/threading/thread_restrictions.h"
#include "build/build_config.h"
#include "content/public/browser/browser_context.h"
#include "content/public/browser/browser_main_parts.h"
#include "content/public/browser/gpu_data_manager.h"
#include "content/public/browser/gpu_data_manager_observer.h"

#if BUILDFLAG(IS_WIN)
#include <windows.h>
#endif

namespace aura {
class Env;
}

namespace display {
class Screen;
}

namespace views {
class ViewsDelegate;
}

namespace wm {
class WMState;
}

namespace jux {

class CommandDispatcher;
class EventWriter;

namespace ipc {
class SharedMemoryChannel;
}

class JuxBrowserMainParts : public content::BrowserMainParts,
                           public content::GpuDataManagerObserver {
 public:
  JuxBrowserMainParts();
  ~JuxBrowserMainParts() override;

  JuxBrowserMainParts(const JuxBrowserMainParts&) = delete;
  JuxBrowserMainParts& operator=(const JuxBrowserMainParts&) = delete;

  // Access the default BrowserContext for creating WebContents.
  content::BrowserContext* browser_context() const {
    return browser_context_.get();
  }

  // Returns the quit closure for the main message loop.
  // Called by JuxShutdown() to stop the browser thread.
  base::OnceClosure GetQuitClosure();

  // Returns the event writer for sending events to Java.
  EventWriter* evt_writer() const { return evt_writer_.get(); }

  // Runs a callback with blocking calls permitted. Used by the engine
  // API to allow CreateWindowExW during widget creation.
  // JuxBrowserMainParts is a friend of ScopedAllowBlocking.
  static void RunWithBlockingAllowed(base::OnceClosure task);

  // content::BrowserMainParts overrides:
  int PreEarlyInitialization() override;
  int PreMainMessageLoopRun() override;
  void WillRunMainMessageLoop(
      std::unique_ptr<base::RunLoop>& run_loop) override;
  void PostMainMessageLoopRun() override;

  // content::GpuDataManagerObserver override:
  void OnGpuProcessCrashed() override;

 private:
  // Sets up the IPC layer: heartbeat, command dispatch, ring buffers,
  // and the native timer for command polling.
  void SetupIPC();

  // Tears down the IPC layer in reverse order.
  void TeardownIPC();

  // Cross-platform command-drain step, driven by cmd_timer_ on the UI task
  // runner: drains the command ring and stops the loop on shutdown request.
  void PollCommands();

#if BUILDFLAG(IS_WIN)
  // Creates a message-only HWND with a 1ms timer for polling the
  // command ring buffer. WM_TIMER is dispatched by the native message
  // pump, bypassing Chromium's DisallowBlocking for posted tasks.
  void CreateCommandPollTimer();

  // Starts/stops the dedicated command-doorbell thread. It watches the command
  // ring and PostMessage(WM_JUX_DRAIN)s the instant a command arrives, so
  // interactive input (e.g. the Enter key — typically the last keystroke before
  // the engine goes idle) drains immediately instead of waiting up to one 8ms
  // cmd_timer_ tick. The actual dispatch still runs on the UI thread via the
  // WM_JUX_DRAIN WndProc path, where blocking commands stay legal.
  void StartCommandDoorbell();
  void StopCommandDoorbell();

  // Static WndProc for the timer window.
  static LRESULT CALLBACK TimerWndProc(HWND hwnd, UINT msg,
                                       WPARAM wparam, LPARAM lparam);
#endif

  // Default browser context (persistent storage).
  std::unique_ptr<content::BrowserContext> browser_context_;

  // Quit closure from the main RunLoop — stored so JuxShutdown() can
  // stop the message loop from any thread via PostTask.
  base::OnceClosure quit_closure_;

  // Aura/Views environment — required for windowed WebContents rendering.
  // Explicit aura::Env ownership — matches content_shell's pattern.
  // Without this, aura::Env is created implicitly, which is fragile
  // across Chromium versions and platforms.
  std::unique_ptr<aura::Env> env_;
  std::unique_ptr<wm::WMState> wm_state_;
  std::unique_ptr<display::Screen> screen_;
  std::unique_ptr<views::ViewsDelegate> views_delegate_;

  // ── IPC layer (set up by SetupIPC, torn down by TeardownIPC) ──────

  // Non-owning pointer to the shared memory channel. Lifetime is managed
  // by JuxRunBrowser (via std::unique_ptr on the call stack).
  raw_ptr<ipc::SharedMemoryChannel> channel_ = nullptr;

  // Event writer — heap-allocated, lifetime spans the dispatcher.
  std::unique_ptr<EventWriter> evt_writer_;

  // Command dispatcher — processes ring buffer commands and calls
  // the engine API (JuxCreateWebContents, JuxLoadURL, etc.).
  std::unique_ptr<CommandDispatcher> dispatcher_;

#if BUILDFLAG(IS_WIN)
  // Hidden message-only window used for the legacy command poll timer.
  HWND timer_hwnd_ = nullptr;

  // Dedicated background thread + run flag for the command doorbell (see
  // StartCommandDoorbell). Joined in TeardownIPC before dispatcher_/timer_hwnd_
  // are destroyed, so its loop never touches freed state.
  std::unique_ptr<base::Thread> cmd_doorbell_thread_;
  std::atomic<bool> cmd_doorbell_running_{false};
#endif

  // Reliable command-drain (+ later, frame-capture) timer. WM_TIMER only fires
  // while the UI thread's message pump is busy, which stops when the hidden
  // background window goes idle — so commands after page load never drain. A
  // base::RepeatingTimer posts delayed tasks to the UI task runner, which
  // Chromium wakes for even when idle, so it fires reliably in the background.
  // Window creation (the only blocking command) is wrapped in
  // RunWithBlockingAllowed, so running the drain from a posted task is safe.
  base::RepeatingTimer cmd_timer_;
};

}  // namespace jux

#endif  // JUX_BROWSER_MAIN_PARTS_H_
