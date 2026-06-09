// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxWebContentsDelegate — fires C callbacks for page lifecycle events.

#include "jux/jux_web_contents_delegate.h"

#include <utility>
#include <vector>

#include "base/files/file_path.h"
#include "base/functional/bind.h"
#include "content/public/browser/web_contents.h"
#include "jux/jux_js_dialog_manager.h"
#include "jux/jux_event_types.h"
#include "jux/jux_ipc.h"
#include "jux/jux_ring_buffer.h"
#include "third_party/blink/public/mojom/favicon/favicon_url.mojom.h"
#include "base/bit_cast.h"
#include "base/logging.h"
#include "base/memory/ref_counted.h"
#include "base/strings/utf_string_conversions.h"
#include "base/task/sequenced_task_runner.h"
#include "base/time/time.h"
#include "content/public/browser/navigation_controller.h"
#include "content/public/browser/reload_type.h"
#include "content/public/browser/navigation_entry.h"
#include "third_party/blink/public/common/page_state/page_state.h"
#include "content/public/browser/context_menu_params.h"
#include "content/public/browser/navigation_handle.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/web_contents.h"
#include "third_party/blink/public/mojom/choosers/file_chooser.mojom.h"
#include "ui/base/page_transition_types.h"
#include "ui/shell_dialogs/select_file_dialog.h"
#include "ui/shell_dialogs/select_file_policy.h"
#include "ui/shell_dialogs/selected_file_info.h"

namespace jux {

// Producer side of the event ring (set by CommandDispatcher::Start). Used to
// emit the context-menu request from HandleContextMenu (browser thread).
extern EventWriter* g_callback_evt_writer;
extern ipc::SharedMemoryChannel* g_callback_channel;

namespace {

// Maps Blink's FileChooserParams::Mode to ui::SelectFileDialog::Type.
// [[maybe_unused]]: retained for reference now that the file chooser is routed
// to JavaFX instead of a native ui::SelectFileDialog.
[[maybe_unused]] ui::SelectFileDialog::Type ModeToDialogType(
    blink::mojom::FileChooserParams::Mode mode) {
  switch (mode) {
    case blink::mojom::FileChooserParams::Mode::kOpen:
      return ui::SelectFileDialog::SELECT_OPEN_FILE;
    case blink::mojom::FileChooserParams::Mode::kOpenMultiple:
      return ui::SelectFileDialog::SELECT_OPEN_MULTI_FILE;
    case blink::mojom::FileChooserParams::Mode::kUploadFolder:
      return ui::SelectFileDialog::SELECT_UPLOAD_FOLDER;
    case blink::mojom::FileChooserParams::Mode::kOpenDirectory:
      return ui::SelectFileDialog::SELECT_EXISTING_FOLDER;
    case blink::mojom::FileChooserParams::Mode::kSave:
      return ui::SelectFileDialog::SELECT_SAVEAS_FILE;
  }
  return ui::SelectFileDialog::SELECT_OPEN_FILE;
}

// Builds a ui::SelectFileDialog::FileTypeInfo from the list of accept_types
// provided by the renderer (e.g. ".jpg", ".png", "image/*"). MIME types
// like "image/*" are dropped — only extensions are honored, since the
// native file dialog filters by extension.
[[maybe_unused]] ui::SelectFileDialog::FileTypeInfo BuildFileTypeInfo(
    const std::vector<std::u16string>& accept_types) {
  ui::SelectFileDialog::FileTypeInfo info;
  info.include_all_files = true;
  info.allowed_paths = ui::SelectFileDialog::FileTypeInfo::NATIVE_PATH;

  std::vector<base::FilePath::StringType> extensions;
  for (const auto& accept : accept_types) {
    // Skip MIME types (anything containing '/'). Only extension strings
    // like ".png" or "png" are usable in the native filter.
    if (accept.find(u'/') != std::u16string::npos) {
      continue;
    }
    // Strip leading '.' if present — FileTypeInfo expects bare extensions.
    std::u16string ext = (!accept.empty() && accept[0] == u'.')
                             ? accept.substr(1)
                             : accept;
    if (ext.empty()) {
      continue;
    }
#if BUILDFLAG(IS_WIN)
    extensions.emplace_back(ext.begin(), ext.end());
#else
    extensions.emplace_back(base::UTF16ToUTF8(ext));
#endif
  }

  if (!extensions.empty()) {
    info.extensions.push_back(std::move(extensions));
  }
  return info;
}

// Converts a ui::SelectedFileInfo into a Blink FileChooserFileInfoPtr.
blink::mojom::FileChooserFileInfoPtr ToFileChooserFileInfo(
    const ui::SelectedFileInfo& file) {
  return blink::mojom::FileChooserFileInfo::NewNativeFile(
      blink::mojom::NativeFileInfo::New(file.file_path, std::u16string(),
                                        std::vector<std::u16string>()));
}

}  // namespace

// How long to wait for a real paint / load-stop signal before emitting
// DOC_READY_TO_SHOW as a backstop. Tuned to:
//   - long enough that a cold-start navigation with server latency (up
//     to a few hundred ms of network + a few hundred ms of first paint)
//     will almost always fire the real signal first,
//   - short enough that a window created-then-shown with no content
//     ever loaded (unusual but valid API use) doesn't stay cloaked
//     long enough to feel broken to the user.
namespace {
constexpr int kReadyToShowBackstopMs = 3000;
}

JuxWebContentsDelegate::JuxWebContentsDelegate(
    content::WebContents* web_contents,
    JuxWebContentsHandle handle,
    const JuxCallbacks* callbacks)
    : content::WebContentsObserver(web_contents),
      handle_(handle),
      callbacks_(callbacks) {
  ArmReadyToShowBackstop();
  // Periodically snapshot the session for crash recovery (Mode 2 native
  // session-restore). 5s keeps it cheap; DidFinishNavigation also captures
  // immediately after each navigation. The timer is a member, so it can't
  // outlive `this`.
  session_timer_.Start(FROM_HERE, base::Seconds(5), this,
                       &JuxWebContentsDelegate::EmitSessionState);
}

JuxWebContentsDelegate::~JuxWebContentsDelegate() {
  // Release any still-pending file chooser as cancelled so the renderer is
  // never left suspended when this delegate is torn down.
  if (pending_file_listener_) {
    pending_file_listener_->FileSelectionCanceled();
    pending_file_listener_.reset();
  }
}

// --- WebContentsDelegate ---

void JuxWebContentsDelegate::CloseContents(content::WebContents* source) {
  // The web page requested to close (window.close() or user action).
  // Forward to the engine which fires WINDOW_CLOSE_REQUEST to Java.
  if (callbacks_ && callbacks_->on_close_requested) {
    callbacks_->on_close_requested(handle_);
  }
}

bool JuxWebContentsDelegate::ShouldSuppressDialogs(
    content::WebContents* source) {
  // Don't suppress JS dialogs — jux handles them via the Java UI layer.
  return false;
}

bool JuxWebContentsDelegate::HandleContextMenu(
    content::RenderFrameHost& render_frame_host,
    const content::ContextMenuParams& params) {
  // Chromium calls this ONLY when the page did not preventDefault() the
  // contextmenu event — i.e. exactly when we should show our menu. A page with
  // its own context menu (canvas apps, editors, games) preventDefaults, so this
  // is NOT called and the page's own menu renders in the frame instead. We emit
  // the hit context (from ContextMenuParams) to Java, which shows the single
  // JavaFX context menu. Returning true suppresses content's native (Views) menu.
  EventWriter* writer = g_callback_evt_writer;
  ipc::SharedMemoryChannel* channel = g_callback_channel;
  if (writer && channel) {
    std::vector<uint8_t> p;
    auto put_u32 = [&p](uint32_t v) {
      p.push_back(static_cast<uint8_t>(v & 0xFF));
      p.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
      p.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
      p.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
    };
    auto put_f32 = [&](float f) { put_u32(base::bit_cast<uint32_t>(f)); };
    auto put_str = [&](const std::string& s) {
      put_u32(static_cast<uint32_t>(s.size()));
      p.insert(p.end(), s.begin(), s.end());
    };
    // Payload (after windowId): [menuId:4][x:f32][y:f32][flags:4]
    //   [linkLen:4][link][srcLen:4][src][selLen:4][sel]. flags bit0 = editable.
    put_u32(0);  // menuId unused on the JavaFX path
    put_f32(static_cast<float>(params.x));
    put_f32(static_cast<float>(params.y));
    put_u32(params.is_editable ? 1u : 0u);
    put_str(params.link_url.spec());
    put_str(params.src_url.spec());
    put_str(base::UTF16ToUTF8(params.selection_text));
    writer->WriteEvent(events::kContextMenuRequested, channel->window_id(),
                       base::span<const uint8_t>(p));
  }
  return true;
}

content::JavaScriptDialogManager*
JuxWebContentsDelegate::GetJavaScriptDialogManager(
    content::WebContents* source) {
  if (!js_dialog_manager_) {
    js_dialog_manager_ =
        std::make_unique<JuxJsDialogManager>(handle_, callbacks_);
  }
  return js_dialog_manager_.get();
}

// ── Fullscreen ─────────────────────────────────────────────────────────

bool JuxWebContentsDelegate::CanEnterFullscreenModeForTab(
    content::RenderFrameHost* requesting_frame) {
  // Allow Chromium's internal :fullscreen state to change so page CSS reacts;
  // the JavaFX app decides whether to make its Stage fullscreen, and can deny.
  return true;
}

void JuxWebContentsDelegate::EnterFullscreenModeForTab(
    content::RenderFrameHost* requesting_frame,
    const blink::mojom::FullscreenOptions& options) {
  is_fullscreen_ = true;
  pending_fs_id_ = ++next_fs_id_;
  if (callbacks_ && callbacks_->on_fullscreen_requested) {
    callbacks_->on_fullscreen_requested(handle_, pending_fs_id_, /*entering=*/true);
  }
}

void JuxWebContentsDelegate::ExitFullscreenModeForTab(
    content::WebContents* web_contents) {
  is_fullscreen_ = false;
  // Keep pending_fs_id_ in lockstep with the id we actually fire (as enter
  // does). Bumping next_fs_id_ without storing it left pending_fs_id_ pinned
  // to a stale enter id, so a subsequent enter→deny round could mismatch in
  // RespondFullscreen's `fs_id == pending_fs_id_` check.
  pending_fs_id_ = ++next_fs_id_;
  if (callbacks_ && callbacks_->on_fullscreen_requested) {
    callbacks_->on_fullscreen_requested(handle_, pending_fs_id_, /*entering=*/false);
  }
}

bool JuxWebContentsDelegate::IsFullscreenForTabOrPending(
    const content::WebContents* web_contents) {
  return is_fullscreen_;
}

void JuxWebContentsDelegate::RespondFullscreen(uint32_t fs_id, bool allowed) {
  // Only a denial of the most recent *entry* needs action: kick the page back
  // out of fullscreen (which fires ExitFullscreenModeForTab → entering=false).
  if (!allowed && is_fullscreen_ && fs_id == pending_fs_id_) {
    content::WebContents* wc = web_contents();
    if (wc) {
      wc->ExitFullscreen(/*will_cause_resize=*/true);
    }
  }
}

// ── Favicon ────────────────────────────────────────────────────────────

void JuxWebContentsDelegate::DidUpdateFaviconURL(
    content::RenderFrameHost* render_frame_host,
    const std::vector<blink::mojom::FaviconURLPtr>& candidates) {
  if (!callbacks_ || !callbacks_->on_favicon_changed) {
    return;
  }
  for (const auto& c : candidates) {
    if (c && c->icon_url.is_valid()) {
      std::string url = c->icon_url.spec();
      callbacks_->on_favicon_changed(
          handle_, url.c_str(), static_cast<uint32_t>(url.size()));
      return;
    }
  }
}

content::WebContents* JuxWebContentsDelegate::AddNewContents(
    content::WebContents* source,
    std::unique_ptr<content::WebContents> new_contents,
    const GURL& target_url,
    WindowOpenDisposition disposition,
    const blink::mojom::WindowFeatures& window_features,
    bool user_gesture,
    bool* was_blocked) {
  // The jux framework manages windows from Java — the engine does not
  // create popup windows. Navigate the source WebContents to the target
  // URL instead. The new_contents unique_ptr is dropped (Chromium will
  // clean it up).
  if (was_blocked) {
    *was_blocked = true;
  }

  if (source && target_url.is_valid()) {
    content::NavigationController::LoadURLParams load_params(target_url);
    load_params.transition_type = ui::PAGE_TRANSITION_LINK;
    source->GetController().LoadURLWithParams(load_params);
  }

  return nullptr;
}

content::WebContents* JuxWebContentsDelegate::OpenURLFromTab(
    content::WebContents* source,
    const content::OpenURLParams& params,
    base::OnceCallback<void(content::NavigationHandle&)>
        navigation_handle_callback) {
  // Navigate the current WebContents to the requested URL. This handles
  // Ctrl+click, middle-click, and certain server redirects that route
  // through the delegate.
  if (!source) {
    return nullptr;
  }

  content::NavigationController::LoadURLParams load_params(params.url);
  load_params.transition_type = params.transition;
  load_params.referrer = params.referrer;
  source->GetController().LoadURLWithParams(load_params);

  return source;
}

// --- WebContentsObserver ---

void JuxWebContentsDelegate::TitleWasSet(content::NavigationEntry* entry) {
  if (!callbacks_ || !callbacks_->on_title_changed) {
    return;
  }
  std::u16string title16 = web_contents()->GetTitle();
  std::string title = base::UTF16ToUTF8(title16);
  callbacks_->on_title_changed(handle_, title.c_str(),
                                static_cast<uint32_t>(title.size()));
}

void JuxWebContentsDelegate::DidFinishNavigation(
    content::NavigationHandle* navigation_handle) {
  VLOG(1) << "DidFinishNavigation: url=" << navigation_handle->GetURL().spec()
            << " committed=" << navigation_handle->HasCommitted()
            << " error=" << navigation_handle->GetNetErrorCode()
            << " main_frame=" << navigation_handle->IsInPrimaryMainFrame();
  // Only care about main frame navigations that committed.
  if (!navigation_handle->IsInPrimaryMainFrame() ||
      !navigation_handle->HasCommitted()) {
    return;
  }

  // Notify URL changed.
  if (callbacks_ && callbacks_->on_url_changed) {
    std::string url = navigation_handle->GetURL().spec();
    callbacks_->on_url_changed(handle_, url.c_str(),
                                static_cast<uint32_t>(url.size()));
  }

  // Notify session-history changed. Serialize the whole NavigationController
  // entry list inline; the list can exceed one event slot (long URLs), so the
  // callback ships it via WriteEventLarge (Java reassembles). Payload (after
  // windowId, prepended by the writer):
  //   [currentIndex:4(int32)][count:4]{[urlLen:4][url][titleLen:4][title]}…
  if (callbacks_ && callbacks_->on_history_changed) {
    content::NavigationController& controller = web_contents()->GetController();
    const int count = controller.GetEntryCount();
    const int current = controller.GetLastCommittedEntryIndex();
    std::vector<uint8_t> blob;
    auto put_u32 = [&blob](uint32_t v) {
      blob.push_back(static_cast<uint8_t>(v));
      blob.push_back(static_cast<uint8_t>(v >> 8));
      blob.push_back(static_cast<uint8_t>(v >> 16));
      blob.push_back(static_cast<uint8_t>(v >> 24));
    };
    auto put_str = [&](const std::string& s) {
      put_u32(static_cast<uint32_t>(s.size()));
      blob.insert(blob.end(), s.begin(), s.end());
    };
    put_u32(static_cast<uint32_t>(current));  // -1 → 0xFFFFFFFF, read as int32
    put_u32(static_cast<uint32_t>(count));
    for (int i = 0; i < count; ++i) {
      content::NavigationEntry* entry = controller.GetEntryAtIndex(i);
      put_str(entry ? entry->GetURL().spec() : std::string());
      put_str(entry ? base::UTF16ToUTF8(entry->GetTitleForDisplay())
                    : std::string());
    }
    callbacks_->on_history_changed(handle_, blob.data(),
                                   static_cast<uint32_t>(blob.size()));
  }

  // Notify loading started.
  if (callbacks_ && callbacks_->on_load_status_changed) {
    callbacks_->on_load_status_changed(handle_, 0);  // 0 = DOC_LOADING
  }

  // Capture the session immediately after this navigation committed (the
  // periodic timer catches later scroll/form drift).
  EmitSessionState();
}

void JuxWebContentsDelegate::DidFinishLoad(
    content::RenderFrameHost* render_frame_host,
    const GURL& validated_url) {
  if (!render_frame_host->IsInPrimaryMainFrame()) {
    return;
  }
  VLOG(1) << "[jux-dom] DidFinishLoad fired for main frame, url="
            << validated_url.spec();
  // Fire DOC_READY here — this is the synchronous, reliable "main frame
  // finished loading" signal that drives the Java WebEngine LoadWorker to
  // SUCCEEDED. It MUST NOT be coupled to the asynchronous DOM-tree Mojo
  // reply: that reply is a posted UI-thread task and can stall when the
  // engine is idle (delayed tasks don't pump), leaving the LoadWorker stuck
  // in RUNNING forever. The DOM mirror is populated independently by the
  // on_dom_element / on_dom_tree_ready events (and JuxRequestDomTree still
  // fires its own DOC_READY, which the Java side treats idempotently).
  if (callbacks_ && callbacks_->on_load_status_changed) {
    callbacks_->on_load_status_changed(handle_, 3);  // 3 = DOC_READY
  }
}

void JuxWebContentsDelegate::DOMContentLoaded(
    content::RenderFrameHost* render_frame_host) {
  if (!render_frame_host->IsInPrimaryMainFrame()) {
    return;
  }
  VLOG(1) << "[jux-dom] DOMContentLoaded fired for main frame";
  if (callbacks_ && callbacks_->on_load_status_changed) {
    callbacks_->on_load_status_changed(handle_, 2);  // 2 = DOC_CONTENT_LOADED
  }
  // Also request the DOM tree here — DOMContentLoaded fires earlier than
  // DidFinishLoad and is reliable across navigation types including
  // file:// URLs from loadResources. JuxRequestDomTree is idempotent.
  // Guard on callbacks_: an off-screen WebContents (e.g. print-preview) has
  // had its Java callbacks detached (DetachJavaCallbacks) precisely so its
  // navigation never surfaces to the app. The RequestDomTree reply path fires
  // through the global g_callbacks regardless, which would leak the preview's
  // DOM elements AND a spurious DOC_READY to Java under the preview handle.
  // Skipping the request when detached honors that contract.
  if (callbacks_) {
    JuxRequestDomTree(handle_);
  }
}

void JuxWebContentsDelegate::DidFailLoad(
    content::RenderFrameHost* render_frame_host,
    const GURL& validated_url,
    int error_code) {
  LOG(ERROR) << "DidFailLoad: url=" << validated_url.spec()
             << " error=" << error_code;
  if (!render_frame_host->IsInPrimaryMainFrame()) {
    return;
  }
  // net::ERR_ABORTED (-3) is not a real failure — it fires when a load is
  // superseded (a new navigation started) or cancelled. Stock WebView
  // ignores it; surfacing it would spuriously drive the LoadWorker to
  // FAILED on every normal re-navigation.
  static constexpr int kErrAborted = -3;
  if (error_code == kErrAborted) {
    return;
  }
  // Do NOT fire DOC_READY (on_load_status_changed=3) here — that maps to
  // PAGE_FINISHED → SUCCEEDED on the Java side and would race the error
  // below, ending a failed load in SUCCEEDED. The error event is the
  // terminal state for a failed main-frame load (→ LOAD_FAILED → FAILED).

  // Release any deferred show so the window becomes visible for the error
  // page / retry UI instead of staying hidden forever.
  EmitReadyToShow();

  // Surface the load failure to Java as a structured kLoadError event
  // so applications can show an error page or log diagnostics.
  if (callbacks_ && callbacks_->on_load_error) {
    std::string url = validated_url.spec();
    // No free-form description from DidFailLoad — Chromium only gives
    // us the net::Error code. Pass an empty description; Java can map
    // the code to a message via net::ErrorToString if needed.
    std::string desc;
    callbacks_->on_load_error(handle_, static_cast<int32_t>(error_code),
                               url.c_str(),
                               static_cast<uint32_t>(url.size()),
                               desc.c_str(),
                               static_cast<uint32_t>(desc.size()));
  }
}

void JuxWebContentsDelegate::OnDidAddMessageToConsole(
    content::RenderFrameHost* source_frame,
    blink::mojom::ConsoleMessageLevel log_level,
    const std::u16string& message,
    int32_t line_no,
    const std::u16string& source_id,
    const std::optional<std::u16string>& untrusted_stack_trace) {
  // skia-fx debug: surface every console message (esp. JS errors) to debug.log,
  // independent of the Java callback (which is dropped in headless smoke runs).
  {
    std::string m = base::UTF16ToUTF8(message);
    std::string s = base::UTF16ToUTF8(source_id);
    if (untrusted_stack_trace.has_value() && !untrusted_stack_trace->empty()) {
      m += " | stack: " + base::UTF16ToUTF8(*untrusted_stack_trace);
    }
    LOG(ERROR) << "[skia-fx console L" << static_cast<int>(log_level) << "] " << m
               << "  (" << s << ":" << line_no << ")";
  }
  if (!callbacks_ || !callbacks_->on_console_message) return;

  // Flatten ConsoleMessageLevel to a small uint.
  uint32_t level = 0;
  switch (log_level) {
    case blink::mojom::ConsoleMessageLevel::kVerbose: level = 0; break;
    case blink::mojom::ConsoleMessageLevel::kInfo:    level = 1; break;
    case blink::mojom::ConsoleMessageLevel::kWarning: level = 2; break;
    case blink::mojom::ConsoleMessageLevel::kError:   level = 3; break;
  }

  std::string msg_utf8 = base::UTF16ToUTF8(message);
  // For Error-level messages include the stack trace (if any) so the
  // Java side sees it — makes debugging dramatically easier.
  if (untrusted_stack_trace.has_value() &&
      !untrusted_stack_trace->empty()) {
    msg_utf8 += "\n";
    msg_utf8 += base::UTF16ToUTF8(*untrusted_stack_trace);
  }
  std::string src_utf8 = base::UTF16ToUTF8(source_id);

  callbacks_->on_console_message(
      handle_, level,
      line_no < 0 ? 0u : static_cast<uint32_t>(line_no),
      msg_utf8.c_str(),
      static_cast<uint32_t>(msg_utf8.size()),
      src_utf8.c_str(),
      static_cast<uint32_t>(src_utf8.size()));
}

void JuxWebContentsDelegate::DidStopLoading() {
  VLOG(1) << "DidStopLoading";
  // All frames stopped loading. Fire DOC_INTERACTIVE.
  if (callbacks_ && callbacks_->on_load_status_changed) {
    callbacks_->on_load_status_changed(handle_, 1);  // 1 = DOC_INTERACTIVE
  }
  // Backstop for DOC_READY_TO_SHOW: if the page never produced a
  // visually non-empty paint (e.g. blank body, all-white content), at
  // least notify Java that loading finished so the window can still
  // become visible. EmitReadyToShow is a one-shot — no-op if the paint
  // callback already fired.
  EmitReadyToShow();
}

void JuxWebContentsDelegate::DidFirstVisuallyNonEmptyPaint() {
  VLOG(1) << "[jux-dom] DidFirstVisuallyNonEmptyPaint";
  // Fast path for DOC_READY_TO_SHOW: the compositor has pushed a frame
  // with visible pixels. This is the ideal moment to show the OS
  // window — no flash of an empty surface.
  EmitReadyToShow();
}

void JuxWebContentsDelegate::EmitReadyToShow() {
  if (ready_to_show_emitted_) return;
  ready_to_show_emitted_ = true;
  if (callbacks_ && callbacks_->on_load_status_changed) {
    // Status code 4 is mapped to kDocReadyToShow by
    // CommandDispatcher::OnLoadStatusChanged.
    callbacks_->on_load_status_changed(handle_, 4);  // 4 = DOC_READY_TO_SHOW
  }
}

void JuxWebContentsDelegate::ArmReadyToShowBackstop() {
  // Runs on the browser UI thread (ctor invoked from CreateWebContentsOnUI).
  // Post to the same sequence — the trigger callbacks all run here too, so
  // ready_to_show_emitted_ needs no synchronization.
  base::SequencedTaskRunner::GetCurrentDefault()->PostDelayedTask(
      FROM_HERE,
      base::BindOnce(&JuxWebContentsDelegate::EmitReadyToShow,
                     weak_factory_.GetWeakPtr()),
      base::Milliseconds(kReadyToShowBackstopMs));
}

std::string JuxWebContentsDelegate::SerializeSession() {
  // Opaque blob (little-endian), replayed verbatim by Java into a respawned
  // engine: [count:4][currentIndex:4]{ [urlLen:4][url][psLen:4][pageState] }.
  // PageState carries scroll position + form values; the entry list carries the
  // back/forward history. (NavigationController's getters aren't const.)
  content::WebContents* wc = web_contents();
  if (!wc) {
    return std::string();
  }
  content::NavigationController& c = wc->GetController();
  const int count = c.GetEntryCount();
  const int current = c.GetLastCommittedEntryIndex();
  if (count <= 0) {
    return std::string();
  }
  std::string out;
  auto put_u32 = [&out](uint32_t v) {
    out.push_back(static_cast<char>(v & 0xFF));
    out.push_back(static_cast<char>((v >> 8) & 0xFF));
    out.push_back(static_cast<char>((v >> 16) & 0xFF));
    out.push_back(static_cast<char>((v >> 24) & 0xFF));
  };
  auto put_str = [&](const std::string& s) {
    put_u32(static_cast<uint32_t>(s.size()));
    out.append(s);
  };
  put_u32(static_cast<uint32_t>(count));
  put_u32(static_cast<uint32_t>(current));
  for (int i = 0; i < count; ++i) {
    content::NavigationEntry* e = c.GetEntryAtIndex(i);
    put_str(e ? e->GetURL().spec() : std::string());
    put_str(e ? e->GetPageState().ToEncodedData() : std::string());
  }
  return out;
}

void JuxWebContentsDelegate::EmitSessionState() {
  if (!callbacks_ || !callbacks_->on_session_state || !web_contents()) {
    return;
  }
  std::string blob = SerializeSession();
  if (blob.empty()) {
    return;
  }
  callbacks_->on_session_state(
      handle_, reinterpret_cast<const uint8_t*>(blob.data()),
      static_cast<uint32_t>(blob.size()));
}

void JuxWebContentsDelegate::ReloadAfterRenderCrash() {
  if (web_contents()) {
    // Reload the current entry with a fresh renderer. Chromium restores the
    // entry's PageState (scroll position + form values), so the page comes back
    // where the user left it. The Java DOM mirror is rebuilt by the reload's
    // normal DOM-tree walk.
    web_contents()->GetController().Reload(content::ReloadType::NORMAL,
                                           /*check_for_repost=*/false);
  }
}

void JuxWebContentsDelegate::PrimaryMainFrameRenderProcessGone(
    base::TerminationStatus status) {
  LOG(ERROR) << "RenderProcessGone: status=" << static_cast<int>(status);

  // A normal termination (we navigated away / closed the tab) is not a crash.
  if (status == base::TERMINATION_STATUS_NORMAL_TERMINATION) {
    return;
  }

  // Mode 1 auto-recovery: the engine/browser process is alive — only the
  // renderer died — and Chromium has kept the NavigationController. Reload to
  // bring the page back with a fresh renderer (URL + scroll + form state
  // restored via PageState). The shared-memory channel, window and last
  // captured frame all survive, so the WebView keeps showing the last frame
  // until the reload repaints — the user barely notices.
  //
  // Crash-loop guard: if the page keeps crashing the renderer, stop after a few
  // rapid attempts and surface the failure for an error UI instead of looping.
  constexpr int kMaxRenderAutoReload = 3;
  const base::TimeTicks now = base::TimeTicks::Now();
  if (last_render_crash_.is_null() ||
      now - last_render_crash_ > base::Seconds(30)) {
    render_crash_count_ = 0;
  }
  last_render_crash_ = now;
  ++render_crash_count_;

  if (render_crash_count_ <= kMaxRenderAutoReload) {
    VLOG(1) << "RenderProcessGone: auto-reloading (attempt "
              << render_crash_count_ << "/" << kMaxRenderAutoReload << ")";
    // Post rather than reload re-entrantly from the crash observer.
    base::SequencedTaskRunner::GetCurrentDefault()->PostTask(
        FROM_HERE,
        base::BindOnce(&JuxWebContentsDelegate::ReloadAfterRenderCrash,
                       weak_factory_.GetWeakPtr()));
    // Tell Java we're loading (recovering) so the LoadWorker shows RUNNING — not
    // FAILED. The reload's normal DidFinishLoad drives it on to SUCCEEDED.
    if (callbacks_ && callbacks_->on_load_status_changed) {
      callbacks_->on_load_status_changed(handle_, 0);  // 0 = DOC_LOADING
    }
    return;
  }

  // Repeated crashes in a short window — give up auto-recovery and surface the
  // failure so the app can show an error / retry UI.
  LOG(ERROR) << "RenderProcessGone: giving up after " << render_crash_count_
             << " crashes in 30s";
  if (callbacks_ && callbacks_->on_render_process_gone) {
    callbacks_->on_render_process_gone(handle_, static_cast<int>(status));
  }
}

// ---------------------------------------------------------------------------
// File chooser (<input type="file"> and directory pickers from web content)
//
// Blink calls RunFileChooser() when a web page invokes a file picker. We
// show a native OS file dialog via ui::SelectFileDialog and relay the
// selection back to Blink through the supplied FileSelectListener.
// ---------------------------------------------------------------------------

void JuxWebContentsDelegate::RunFileChooser(
    content::RenderFrameHost* render_frame_host,
    scoped_refptr<content::FileSelectListener> listener,
    const blink::mojom::FileChooserParams& params) {
  // Only one chooser per WebContents at a time. If a previous chooser is
  // still pending (shouldn't normally happen), cancel it cleanly.
  if (pending_file_listener_) {
    LOG(WARNING) << "RunFileChooser: a prior chooser was still pending; "
                 << "cancelling it.";
    pending_file_listener_->FileSelectionCanceled();
    pending_file_listener_.reset();
  }

  pending_file_listener_ = std::move(listener);
  pending_file_mode_ = params.mode;
  pending_file_id_ = ++next_file_chooser_id_;

  // skia-fx: the WebView is off-screen, so we do NOT show a native
  // SelectFileDialog here (the headless engine has no dialog factory). Instead
  // surface the request to JavaFX (kFileChooserRequested); the app shows a
  // javafx.stage.FileChooser (or a custom handler) and answers via
  // RespondFileChooser, which hands Blink the chosen NATIVE paths. Only paths
  // cross IPC — Blink streams the file contents straight from disk, so large
  // uploads are unaffected. (Keep ModeToDialogType / BuildFileTypeInfo /
  // ToFileChooserFileInfo for reference, and the SelectFileDialog::Listener
  // overrides for ABI completeness; they are simply no longer driven.)
  EventWriter* writer = g_callback_evt_writer;
  ipc::SharedMemoryChannel* channel = g_callback_channel;
  if (!writer || !channel) {
    // No IPC channel to surface the request — cancel so the renderer is not
    // left suspended forever.
    FileSelectionCanceled();
    return;
  }

  uint32_t mode_int = 0;
  switch (params.mode) {
    case blink::mojom::FileChooserParams::Mode::kOpenMultiple:  mode_int = 1; break;
    case blink::mojom::FileChooserParams::Mode::kUploadFolder:  mode_int = 2; break;
    case blink::mojom::FileChooserParams::Mode::kOpenDirectory: mode_int = 3; break;
    case blink::mojom::FileChooserParams::Mode::kSave:          mode_int = 4; break;
    default:                                                    mode_int = 0; break;
  }
  std::string accept;
  for (const auto& a : params.accept_types) {
    if (!accept.empty()) {
      accept.push_back('\n');
    }
    accept += base::UTF16ToUTF8(a);
  }

  std::vector<uint8_t> p;
  auto put_u32 = [&p](uint32_t v) {
    p.push_back(static_cast<uint8_t>(v & 0xFF));
    p.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    p.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
    p.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
  };
  auto put_str = [&](const std::string& s) {
    put_u32(static_cast<uint32_t>(s.size()));
    p.insert(p.end(), s.begin(), s.end());
  };
  // Payload (after windowId): [chooserId:4][mode:4][titleLen][title][initLen]
  //                           [init][acceptLen][acceptCsv]
  put_u32(pending_file_id_);
  put_u32(mode_int);
  put_str(base::UTF16ToUTF8(params.title));
  put_str(params.default_file_name.AsUTF8Unsafe());
  put_str(accept);
  writer->WriteEvent(events::kFileChooserRequested, channel->window_id(),
                     base::span<const uint8_t>(p));
}

void JuxWebContentsDelegate::RespondFileChooser(
    uint32_t chooser_id, std::vector<base::FilePath> paths) {
  if (chooser_id != pending_file_id_ || !pending_file_listener_) {
    return;  // stale id or already answered — ignore
  }
  if (paths.empty()) {
    FileSelectionCanceled();  // cancels the pending listener + resets state
    return;
  }
  std::vector<blink::mojom::FileChooserFileInfoPtr> files;
  files.reserve(paths.size());
  for (const auto& path : paths) {
    // NATIVE file — Blink reads/streams it straight from disk. Never switch to
    // a bytes-based FileChooserFileInfo, or large uploads would be loaded into
    // memory / crammed through IPC.
    files.push_back(blink::mojom::FileChooserFileInfo::NewNativeFile(
        blink::mojom::NativeFileInfo::New(path, std::u16string(),
                                          std::vector<std::u16string>())));
  }
  DeliverFilesToBlink(std::move(files));  // listener->FileSelected(files, {}, mode)
  ResetPendingFileChooser();
}

void JuxWebContentsDelegate::FileSelected(const ui::SelectedFileInfo& file,
                                           int index) {
  std::vector<blink::mojom::FileChooserFileInfoPtr> files;
  files.emplace_back(ToFileChooserFileInfo(file));
  DeliverFilesToBlink(std::move(files));
  ResetPendingFileChooser();
}

void JuxWebContentsDelegate::MultiFilesSelected(
    const std::vector<ui::SelectedFileInfo>& files) {
  std::vector<blink::mojom::FileChooserFileInfoPtr> out;
  out.reserve(files.size());
  for (const auto& f : files) {
    out.emplace_back(ToFileChooserFileInfo(f));
  }
  DeliverFilesToBlink(std::move(out));
  ResetPendingFileChooser();
}

void JuxWebContentsDelegate::FileSelectionCanceled() {
  if (pending_file_listener_) {
    pending_file_listener_->FileSelectionCanceled();
  }
  ResetPendingFileChooser();
}

void JuxWebContentsDelegate::DeliverFilesToBlink(
    std::vector<blink::mojom::FileChooserFileInfoPtr> files) {
  if (!pending_file_listener_) {
    return;
  }
  pending_file_listener_->FileSelected(std::move(files), base::FilePath(),
                                        pending_file_mode_);
}

void JuxWebContentsDelegate::ResetPendingFileChooser() {
  pending_file_listener_.reset();
  file_chooser_dialog_.reset();
}

}  // namespace jux
