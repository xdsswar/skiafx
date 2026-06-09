// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxWebContentsDelegate — handles page-level events for a WebContents.
//
// Implements both WebContentsDelegate (UI decisions like close, fullscreen)
// and WebContentsObserver (lifecycle events like navigation, title, load).
//
// When events fire, this delegate calls the registered C callback functions
// (set via JuxSetCallbacks). The callbacks are invoked on the browser thread
// — the EventWriter is thread-safe.

#ifndef JUX_WEB_CONTENTS_DELEGATE_H_
#define JUX_WEB_CONTENTS_DELEGATE_H_

#include <optional>
#include <string>

#include "base/memory/raw_ptr.h"
#include "base/memory/scoped_refptr.h"
#include "base/memory/weak_ptr.h"
#include "base/time/time.h"
#include "base/timer/timer.h"
#include "content/public/browser/file_select_listener.h"
#include "content/public/browser/web_contents_delegate.h"
#include "content/public/browser/web_contents_observer.h"
#include "jux/jux_engine_api.h"
#include "third_party/blink/public/mojom/choosers/file_chooser.mojom.h"
#include "third_party/blink/public/mojom/devtools/console_message.mojom.h"
#include "ui/base/window_open_disposition.h"
#include "ui/shell_dialogs/select_file_dialog.h"

namespace jux {

class JuxJsDialogManager;

class JuxWebContentsDelegate : public content::WebContentsDelegate,
                               public content::WebContentsObserver,
                               public ui::SelectFileDialog::Listener {
 public:
  // handle: the opaque JuxWebContentsHandle returned by JuxCreateWebContents.
  // callbacks: pointer to the global callback struct (owned by jux_engine_api).
  JuxWebContentsDelegate(content::WebContents* web_contents,
                          JuxWebContentsHandle handle,
                          const JuxCallbacks* callbacks);
  ~JuxWebContentsDelegate() override;

  JuxWebContentsDelegate(const JuxWebContentsDelegate&) = delete;
  JuxWebContentsDelegate& operator=(const JuxWebContentsDelegate&) = delete;

  // Detach ALL Java event callbacks (URL/title/history/load/console/favicon).
  // Used for the internal off-screen print-preview WebContents so its
  // chrome://print navigation never leaks to the app as the page's URL/history/
  // title/load state. The preview's frames still flow via the capture tick and
  // its open/close via the dedicated kPrintPreview* events (neither uses these).
  void DetachJavaCallbacks() { callbacks_ = nullptr; }

  // content::WebContentsDelegate overrides:
  void CloseContents(content::WebContents* source) override;
  bool ShouldSuppressDialogs(content::WebContents* source) override;

  // Suppresses Chromium's own (native Views) context menu. The WebView renders
  // off-screen into a cloaked window, so a native menu would pop up detached at
  // the cloaked window's screen origin. Returning true tells content "the
  // embedder handled it" → no native menu. The right-click is still surfaced to
  // Java independently by the renderer's document `contextmenu` listener
  // (JuxDomHandlerImpl::HandleContextMenuEvent → OnContextMenu), so the app's
  // own ContextMenu still shows.
  bool HandleContextMenu(content::RenderFrameHost& render_frame_host,
                         const content::ContextMenuParams& params) override;

  // Returns our JS dialog manager (lazily created) so page alert/confirm/
  // prompt/beforeunload surface to Java instead of being auto-cancelled.
  content::JavaScriptDialogManager* GetJavaScriptDialogManager(
      content::WebContents* source) override;

  // Accessor used by JuxRespondDialog to resume a suspended dialog. May be
  // null if no dialog has been raised yet on this WebContents.
  JuxJsDialogManager* js_dialog_manager() { return js_dialog_manager_.get(); }

  // Fullscreen: we let Chromium update its internal :fullscreen state (so the
  // page's CSS reacts) and notify Java, which toggles its own Stage. CanEnter
  // returns true; Enter/Exit fire on_fullscreen_requested. If the app denies a
  // fullscreen *entry* (JuxRespondFullscreen with allowed=false) we kick the
  // page back out via WebContents::ExitFullscreen.
  bool CanEnterFullscreenModeForTab(
      content::RenderFrameHost* requesting_frame) override;
  void EnterFullscreenModeForTab(
      content::RenderFrameHost* requesting_frame,
      const blink::mojom::FullscreenOptions& options) override;
  void ExitFullscreenModeForTab(content::WebContents* web_contents) override;
  bool IsFullscreenForTabOrPending(
      const content::WebContents* web_contents) override;

  // Called by JuxRespondFullscreen (browser UI thread).
  void RespondFullscreen(uint32_t fs_id, bool allowed);

  // Handles window.open() and <a target="_blank"> — navigates the
  // current WebContents to the target URL instead of creating a popup
  // (Java manages all window lifecycle).
  content::WebContents* AddNewContents(
      content::WebContents* source,
      std::unique_ptr<content::WebContents> new_contents,
      const GURL& target_url,
      WindowOpenDisposition disposition,
      const blink::mojom::WindowFeatures& window_features,
      bool user_gesture,
      bool* was_blocked) override;

  // Handles Ctrl+click and other navigations that route through the
  // delegate instead of direct navigation.
  content::WebContents* OpenURLFromTab(
      content::WebContents* source,
      const content::OpenURLParams& params,
      base::OnceCallback<void(content::NavigationHandle&)>
          navigation_handle_callback) override;

  // Handles <input type="file"> and directory pickers triggered by the
  // rendered web page. Shows a native OS file dialog via
  // ui::SelectFileDialog and forwards the result back to Blink via the
  // content::FileSelectListener.
  void RunFileChooser(
      content::RenderFrameHost* render_frame_host,
      scoped_refptr<content::FileSelectListener> listener,
      const blink::mojom::FileChooserParams& params) override;

  // Answers the pending file chooser (id from the kFileChooserRequested event)
  // with the app-chosen NATIVE paths — empty ⇒ cancel. Hands the paths to Blink
  // as native files so the renderer streams large uploads straight from disk.
  // A stale id (the chooser was superseded / the page moved on) is ignored.
  void RespondFileChooser(uint32_t chooser_id,
                          std::vector<base::FilePath> paths);

  // ui::SelectFileDialog::Listener overrides:
  void FileSelected(const ui::SelectedFileInfo& file, int index) override;
  void MultiFilesSelected(
      const std::vector<ui::SelectedFileInfo>& files) override;
  void FileSelectionCanceled() override;

  // content::WebContentsObserver overrides:
  void TitleWasSet(content::NavigationEntry* entry) override;
  void DidFinishNavigation(
      content::NavigationHandle* navigation_handle) override;
  void DidStopLoading() override;
  void DidFinishLoad(content::RenderFrameHost* render_frame_host,
                     const GURL& validated_url) override;
  void DidFailLoad(content::RenderFrameHost* render_frame_host,
                   const GURL& validated_url,
                   int error_code) override;
  void DOMContentLoaded(content::RenderFrameHost* render_frame_host) override;
  void PrimaryMainFrameRenderProcessGone(
      base::TerminationStatus status) override;

  // Fires on_favicon_changed with the first valid candidate icon URL.
  void DidUpdateFaviconURL(
      content::RenderFrameHost* render_frame_host,
      const std::vector<blink::mojom::FaviconURLPtr>& candidates) override;

  // Fires once the compositor has pushed a frame with visible content.
  // This is the earliest reliable signal that the page has actually
  // painted something — used to drive Java's showWhenReady logic so the
  // first OS show happens with content already on screen.
  void DidFirstVisuallyNonEmptyPaint() override;

  // Fires for every console.log/warn/error and every uncaught JS
  // exception. Forwarded to Java via on_console_message so the app
  // can log / display diagnostics.
  void OnDidAddMessageToConsole(
      content::RenderFrameHost* source_frame,
      blink::mojom::ConsoleMessageLevel log_level,
      const std::u16string& message,
      int32_t line_no,
      const std::u16string& source_id,
      const std::optional<std::u16string>& untrusted_stack_trace) override;
 private:
  // Helpers for the file chooser flow. pending_file_listener_ is the Blink
  // callback to invoke once the dialog closes; pending_file_mode_ is the
  // original chooser mode (needed by FileSelectListener::FileSelected).
  void DeliverFilesToBlink(
      std::vector<blink::mojom::FileChooserFileInfoPtr> files);
  void ResetPendingFileChooser();

  // Posted from PrimaryMainFrameRenderProcessGone — reloads the current
  // NavigationEntry (restoring URL + PageState) with a fresh renderer.
  void ReloadAfterRenderCrash();

  // Crash-recovery session snapshot. SerializeSession() encodes the whole
  // NavigationController (each entry's URL + PageState: scroll/forms) into an
  // opaque blob; EmitSessionState() ships it to Java (kSessionState) so it can
  // be replayed into a respawned engine. Driven by a periodic timer +
  // DidFinishNavigation.
  std::string SerializeSession();
  void EmitSessionState();

  // JS dialog manager for this WebContents (alert/confirm/prompt/beforeunload).
  // Created lazily by GetJavaScriptDialogManager; the unique_ptr's destructor
  // (defined in the .cc where the type is complete) releases any pending dialog.
  std::unique_ptr<JuxJsDialogManager> js_dialog_manager_;

  // Fullscreen tracking (UI thread only). is_fullscreen_ backs
  // IsFullscreenForTabOrPending; pending_fs_id_ is the id of the most recent
  // entry request so a deny can be correlated.
  bool is_fullscreen_ = false;
  uint32_t next_fs_id_ = 0;
  uint32_t pending_fs_id_ = 0;

  // Renderer-crash auto-recovery (Mode 1). Chromium keeps the NavigationEntry
  // (URL + PageState: scroll position and form values) across a renderer crash,
  // so a Reload() restores the exact page with a fresh renderer. Guard against
  // a crash loop: auto-recover at most kMaxRenderAutoReload times within a short
  // window; beyond that, surface the failure to Java for an error UI.
  int render_crash_count_ = 0;
  base::TimeTicks last_render_crash_;

  // Periodically serializes the session to Java for crash recovery (Mode 2,
  // native session-restore). Started in the ctor; fires EmitSessionState.
  base::RepeatingTimer session_timer_;

  // The opaque handle passed back in callbacks so the engine can identify
  // which WebContents the event belongs to.
  JuxWebContentsHandle handle_;

  // Pointer to the global callback struct. Not owned — lives in the
  // API layer for the lifetime of the process.
  raw_ptr<const JuxCallbacks> callbacks_;

  // The currently active file chooser dialog, if any. Held to keep the
  // dialog alive for the duration of the user's interaction. Chromium
  // only allows one chooser per WebContents, so no concurrent state
  // tracking is needed.
  scoped_refptr<ui::SelectFileDialog> file_chooser_dialog_;

  // The Blink-side callback for the currently active chooser. Reset after
  // delivery or cancellation.
  scoped_refptr<content::FileSelectListener> pending_file_listener_;

  // The mode of the currently active chooser (echoed back to Blink).
  blink::mojom::FileChooserParams::Mode pending_file_mode_ =
      blink::mojom::FileChooserParams::Mode::kOpen;

  // Correlates the async app response with the request that is still pending.
  // next_file_chooser_id_ is bumped per request; pending_file_id_ is the id of
  // the open chooser. A response whose id != pending_file_id_ is stale (ignored).
  uint32_t next_file_chooser_id_ = 0;
  uint32_t pending_file_id_ = 0;

  // One-shot latch for the "ready to show" signal — emitted on the
  // first of DidFirstVisuallyNonEmptyPaint / DidStopLoading /
  // DidFailLoad. Used so Java only ever sees one DOC_READY_TO_SHOW per
  // WebContents (it's a startup-only concern — subsequent navigations
  // don't re-arm it).
  bool ready_to_show_emitted_ = false;

  // Emits DOC_READY_TO_SHOW exactly once. Safe to call from any of the
  // trigger callbacks; later calls are no-ops.
  void EmitReadyToShow();

  // Arms a delayed-task backstop that fires EmitReadyToShow after
  // kReadyToShowBackstopMs. Handles the edge case where Java calls
  // show() on a window that never loads any content: without a
  // navigation, none of the real paint/load signals ever fire and the
  // cloaked window would stay invisible forever. The backstop is a
  // no-op if any real signal latches first. Called from the ctor.
  void ArmReadyToShowBackstop();

  // WeakPtr guard for the delayed backstop task. If the delegate is
  // destroyed (e.g. window closed before content loaded) before the
  // delayed task fires, the posted bound task safely drops on WeakPtr
  // invalidation instead of use-after-freeing `this`.
  base::WeakPtrFactory<JuxWebContentsDelegate> weak_factory_{this};
};

}  // namespace jux

#endif  // JUX_WEB_CONTENTS_DELEGATE_H_
