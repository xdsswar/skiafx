// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// jux-engine public C API — exported from jux-engine.dll.
//
// This is the API surface that the C++ engine process calls.
// All functions use C linkage and are safe to call from any thread —
// they marshal to the Chromium browser thread internally via PostTask.
//
// The engine owns the native window (Win32/Cocoa/X11) and the IPC layer
// (shared memory ring buffers). Chromium provides the content layer
// (Blink, V8, compositor, GPU, network) and renders into a child window.
//
// Thread safety:
//   - JuxInit() must be called from the main thread before any other call.
//   - All other functions marshal to the browser thread via PostTask.
//   - Callbacks fire on the browser thread — the EventWriter is
//     thread-safe (atomic load/store).
//
// Memory management:
//   - String parameters are borrowed (not copied) for the duration of the
//     call. The caller must keep them alive until the function returns.
//   - JuxWebContentsHandle values are opaque. Create via JuxCreateWebContents,
//     destroy via JuxDestroyWebContents. Using a stale handle is undefined.
//   - JuxShutdown() releases all resources. No calls are valid after it.

#ifndef JUX_ENGINE_API_H_
#define JUX_ENGINE_API_H_

#include "jux_engine_export.h"

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// =========================================================================
// Opaque handle for a WebContents instance (one per web view).
// =========================================================================
typedef uintptr_t JuxWebContentsHandle;

// =========================================================================
// Lifecycle
// =========================================================================

// Initializes the Chromium content layer. Spawns the browser thread and
// starts the message loop. Returns 0 on success, non-zero on failure.
//
// This function blocks until the browser thread is ready to accept API
// calls. After it returns, JuxCreateWebContents() etc. are safe to call.
//
// Must be called exactly once, before any other Jux* function.
// The subprocess_path is the absolute path to jux-engine-process.exe.
// The pak_path is the absolute path to jux-engine.pak.
//
// argc / argv: the process command line. On Windows, Chromium reads
// GetCommandLineW() internally so these are advisory (may be 0/nullptr).
// On POSIX, argv carries the switches that arrived from Java's
// Application.engineSwitches() via ProcessBuilder — passing them here
// is what makes those switches take effect. argv[0] is expected to be
// the program path; loose positional args are ignored by Chromium.
JUX_EXPORT int JuxInit(const char* subprocess_path, const char* pak_path,
                        int argc, const char* const* argv);

// Entry point for child processes (renderer, GPU, utility).
// Called by jux-engine-process.exe. This function blocks until the
// child process exits. Returns the process exit code.
//
// On Windows: argc=0, argv=nullptr (args read from GetCommandLineW).
// On POSIX: pass argc/argv from main().
JUX_EXPORT int JuxSubprocessMain(int argc, const char** argv);

// Shuts down the Chromium content layer. Stops the browser thread, tears
// down all WebContents, releases all resources. No Jux* calls are valid
// after this returns.
JUX_EXPORT void JuxShutdown(void);

// Sets the GPU rendering mode. Must be called BEFORE JuxRunBrowser.
// Modes:
//   0 = Default (ANGLE auto-detect with SwiftShader fallback)
//   1 = Force software rendering (SwiftShader via ANGLE)
//   2 = Disable GPU entirely (CPU compositing)
// If not called, mode 0 (default) is used.
JUX_EXPORT void JuxSetGpuMode(int mode);

// Runs the browser process for a window identified by its shared memory
// file. Opens the shared memory channel, initializes Chromium, starts
// the heartbeat and command dispatch, and enters the message loop.
//
// This function BLOCKS until shutdown (CMD_DESTROY_WINDOW or WM_QUIT).
// The mmap_path is the absolute path to the shared memory file created
// by the Java side, as a null-terminated UTF-8 string.
//
// Returns 0 on success, non-zero on failure.
//
// argc / argv: the full process command line from the exe wrapper's
// main/wWinMain (argv[0] = program path, argv[1] = mmap path, argv[2..]
// = switches from Application.engineSwitches()). Forwarded to JuxInit
// so Chromium's CommandLine sees them. On Windows this is redundant
// with GetCommandLineW(); on POSIX it is the only way switches reach
// Chromium, so callers must forward argv faithfully.
JUX_EXPORT int JuxRunBrowser(const char* mmap_path,
                              int argc, const char* const* argv);

// =========================================================================
// WebContents management
// =========================================================================

// Creates a new WebContents hosted inside the given native window handle.
// On Windows: parent_window is an HWND cast to uintptr_t.
// On macOS:   parent_window is an NSView* cast to uintptr_t.
// On Linux:   parent_window is an X11 Window or Wayland surface handle.
//
// Returns a valid handle on success, or 0 on failure.
// The WebContents renders into a child window that fills the parent.
JUX_EXPORT JuxWebContentsHandle JuxCreateWebContents(uintptr_t parent_window);

// Destroys a WebContents and releases all associated resources.
// The child window is removed from the parent. The handle becomes invalid.
// All pending Mojo pipes, DOM node maps, and event listeners are cleaned up.
JUX_EXPORT void JuxDestroyWebContents(JuxWebContentsHandle handle);

// Returns the native window handle for a WebContents.
// On Windows: HWND. On macOS: NSView*. On Linux: X11/Wayland handle.
// Returns 0 if the handle is invalid.
// Safe to call from any thread after JuxCreateWebContents() returns.
JUX_EXPORT uintptr_t JuxGetNativeWindow(JuxWebContentsHandle handle);

// Resizes the WebContents to the given rectangle within the parent window.
// x, y, width, height are in physical pixels.
JUX_EXPORT void JuxResizeWebContents(JuxWebContentsHandle handle,
                                      int x, int y, int width, int height);

// =========================================================================
// Navigation
// =========================================================================

// Navigates to a URL. The URL must be a valid, null-terminated UTF-8 string.
// Supports http://, https://, file://, and data: schemes.
JUX_EXPORT void JuxLoadURL(JuxWebContentsHandle handle, const char* url);

// Navigates the session history by a signed offset (-1 = back, +1 = forward)
// relative to the current entry. Validated against CanGoToOffset; a committed
// navigation echoes a fresh kHistoryState event. No-op if out of range.
JUX_EXPORT void JuxGoToOffset(JuxWebContentsHandle handle, int32_t offset);

// Restores a serialized session blob (from on_session_state, persisted by Java
// across a respawn) into a fresh WebContents — bringing back URL + scroll +
// form values + history via NavigationController::Restore. `data`/`len` is the
// opaque blob produced by SerializeSession.
JUX_EXPORT void JuxRestoreSession(JuxWebContentsHandle handle,
                                  const uint8_t* data, uint32_t len);

// Sets a per-WebView User-Agent override. An empty/null string clears it,
// reverting to the global default UA (JuxBrowserClient::GetUserAgent). Applies
// to subsequent navigations.
JUX_EXPORT void JuxSetUserAgent(JuxWebContentsHandle handle,
                                const char* user_agent);

// Loads raw HTML content. html is UTF-8, null-terminated.
// base_url is the origin for relative URLs (can be NULL for about:blank).
JUX_EXPORT void JuxLoadHTML(JuxWebContentsHandle handle,
                             const char* html, const char* base_url);

// Printing. JuxShowPrintPreview opens the interactive chrome://print preview.
// JuxPrintToPdf renders the page to a PDF: a non-empty `path` writes there
// directly; a NULL/empty `path` pops a native "Save As" dialog. No-ops when the
// build has print preview disabled.
JUX_EXPORT void JuxShowPrintPreview(JuxWebContentsHandle handle);
JUX_EXPORT void JuxPrintToPdf(JuxWebContentsHandle handle, const char* path);

// =========================================================================
// JavaScript execution
// =========================================================================

// Executes JavaScript in the main frame's V8 context.
// script is UTF-8, null-terminated.
// request_id is echoed in the on_js_result / on_js_error callback so the
// caller can match results to requests.
JUX_EXPORT void JuxExecuteJS(JuxWebContentsHandle handle,
                              const char* script, uint32_t request_id);

// JSObject operations on a live V8 object kept in the renderer's table
// (object_id 0 = the global window). Each runs in the renderer's main world and
// answers via on_js_value (typed result) or on_js_error (JS exception),
// correlated by request_id. Values/args are tagged bytes (jux_js_value.h).
JUX_EXPORT void JuxJsGetMember(JuxWebContentsHandle handle, uint32_t request_id,
                               int32_t object_id, const char* name);
JUX_EXPORT void JuxJsSetMember(JuxWebContentsHandle handle, uint32_t request_id,
                               int32_t object_id, const char* name,
                               const uint8_t* value, uint32_t value_len);
JUX_EXPORT void JuxJsRemoveMember(JuxWebContentsHandle handle,
                                  uint32_t request_id, int32_t object_id,
                                  const char* name);
JUX_EXPORT void JuxJsGetSlot(JuxWebContentsHandle handle, uint32_t request_id,
                             int32_t object_id, int32_t index);
JUX_EXPORT void JuxJsSetSlot(JuxWebContentsHandle handle, uint32_t request_id,
                             int32_t object_id, int32_t index,
                             const uint8_t* value, uint32_t value_len);
JUX_EXPORT void JuxJsCall(JuxWebContentsHandle handle, uint32_t request_id,
                          int32_t object_id, const char* name, uint32_t argc,
                          const uint8_t* args, uint32_t args_len);
JUX_EXPORT void JuxJsEval(JuxWebContentsHandle handle, uint32_t request_id,
                          int32_t object_id, const char* script);
JUX_EXPORT void JuxJsRelease(JuxWebContentsHandle handle, int32_t object_id);

// Settles the JS Promise returned by a host-proxy call (slice B). `call_id`
// matches the OnJavaCall that opened it. ok=true → resolve with `value` (tagged
// bytes, len `value_len`); ok=false → reject with `error` (UTF-8, may be null).
// Fire-and-forget toward the renderer (no reply).
JUX_EXPORT void JuxResolveJavaCall(JuxWebContentsHandle handle, int32_t call_id,
                                   bool ok, const uint8_t* value,
                                   uint32_t value_len, const char* error);

// Answers a page JS dialog previously surfaced via on_dialog_requested. Runs
// the stashed continuation on the browser UI thread, resuming the page.
// accepted: confirm/prompt OK (ignored for alert); text/text_len: the prompt
// reply as length-counted UTF-8 (NOT null-terminated — a prompt value may
// legitimately contain embedded '\0', which a c_str() round-trip would truncate).
JUX_EXPORT void JuxRespondDialog(JuxWebContentsHandle handle,
                                  uint32_t dialog_id, bool accepted,
                                  const char* text, uint32_t text_len);

// Answers a fullscreen request. allowed=false on an entry kicks the page back
// out of fullscreen; allowed=true is a no-op (the page stays fullscreen).
JUX_EXPORT void JuxRespondFullscreen(JuxWebContentsHandle handle,
                                      uint32_t fs_id, bool allowed);

// Answers a permission request surfaced via the permission manager. Applies
// `granted` to every permission in the original request and resumes the page.
// (handle is accepted for symmetry but the manager is process-global.)
JUX_EXPORT void JuxRespondPermission(JuxWebContentsHandle handle,
                                      uint32_t perm_id, bool granted);

// Answers an HTTP/proxy auth challenge. supplied=true retries with the given
// credentials; supplied=false cancels (the load fails with 401/407).
JUX_EXPORT void JuxRespondAuth(JuxWebContentsHandle handle, uint32_t auth_id,
                                bool supplied, const char* user,
                                const char* pass);

// Answers a download surfaced via the download manager delegate. accepted=true
// saves to `path`; accepted=false (or empty path) cancels. (handle is accepted
// for symmetry but the delegate is process-global.)
JUX_EXPORT void JuxRespondDownload(JuxWebContentsHandle handle,
                                    uint32_t download_id, bool accepted,
                                    const char* path);

// Cancels an in-progress download.
JUX_EXPORT void JuxCancelDownload(JuxWebContentsHandle handle,
                                   uint32_t download_id);

// =========================================================================
// DevTools
// =========================================================================

// Opens the DevTools inspector for the WebContents.
// DevTools opens in a separate window.
JUX_EXPORT void JuxOpenDevTools(JuxWebContentsHandle handle);

// Closes the DevTools inspector.
JUX_EXPORT void JuxCloseDevTools(JuxWebContentsHandle handle);

// =========================================================================
// Focus and input
// =========================================================================

// Shows the Widget for a WebContents (makes the window visible).
// Must be called after JuxCreateWebContents. The widget is created
// hidden — this makes it visible on screen.
JUX_EXPORT void JuxShowWidget(JuxWebContentsHandle handle);

// Hides the Widget for a WebContents (makes the window invisible).
JUX_EXPORT void JuxHideWidget(JuxWebContentsHandle handle);

// Permits the widget close for a WebContents. The custom WidgetDelegate
// blocks close requests until this is called. Must be called before
// JuxDestroyWebContents to allow the widget to actually close.
JUX_EXPORT void JuxAllowClose(JuxWebContentsHandle handle);

// Notifies Chromium that the parent window gained or lost OS focus.
// When focused, Chromium activates the WebContents for keyboard input.
JUX_EXPORT void JuxNotifyFocus(JuxWebContentsHandle handle, int focused);

// skia-fx OSR: sizes the off-screen view to width x height LOGICAL px and sets
// the per-view device-scale (DSF) used to rasterize the page, so the captured
// frame is (width*scale) x (height*scale) device px — crisp at any HiDPI /
// multi-monitor scale. scale comes from the JavaFX scene's render scale and is
// re-sent whenever it changes (e.g. the window moves to a different-DPI
// monitor). Replaces the chrome-inset HWND resize for the windowless WebView.
JUX_EXPORT void JuxSetOffscreenSize(JuxWebContentsHandle handle,
                                     int width, int height, float scale);

// If a print preview is open over `initiator`, re-size the (centered, off-screen)
// preview to Chrome's GetDialogSize for the initiator's new w x h logical size,
// so the modal tracks the window on resize. No-op if no preview is open or if
// `initiator` IS the preview. Same OSR resize path as JuxSetOffscreenSize (bursts
// fast-capture so the preview re-fits responsively).
JUX_EXPORT void JuxAdaptPrintPreviewToInitiator(JuxWebContentsHandle initiator,
                                                int width, int height,
                                                float scale);

// =========================================================================
// Off-screen input injection
//
// The WebView has no OS window of its own (it is composited into the
// JavaFX scene), so JavaFX input events are forwarded to the off-screen
// RenderWidgetHost via these entries. Coordinates are device pixels
// relative to the page's top-left. All marshal to the UI thread.
// =========================================================================

// Mouse move/down/up. type: 0=move, 1=down, 2=up. button: 0=left, 1=middle,
// 2=right (ignored for move). modifiers is a blink WebInputEvent modifier mask.
JUX_EXPORT void JuxSendMouseEvent(JuxWebContentsHandle handle, int type,
                                   float x, float y, int button,
                                   int click_count, int modifiers);

// Forwards a synthetic mouse event to the open OSR popup (page-popup) instead of
// the main frame. (x, y) are popup-local DIP coords. No-op if no popup is open.
JUX_EXPORT void JuxSendPopupMouseEvent(JuxWebContentsHandle handle, int type,
                                        float x, float y, int button,
                                        int click_count, int modifiers);

// Forwards a synthetic wheel event to the open OSR popup (scroll a long
// <select>/datalist list). (x, y) are popup-local DIP coords. No-op if no
// popup is open.
JUX_EXPORT void JuxSendPopupWheelEvent(JuxWebContentsHandle handle,
                                        float x, float y,
                                        float delta_x, float delta_y,
                                        int modifiers);

// Forwards a synthetic key event to the open OSR popup (arrow/Enter/Esc/
// type-ahead). Same args as JuxSendKeyEvent. No-op if no popup is open.
JUX_EXPORT void JuxSendPopupKeyEvent(JuxWebContentsHandle handle, int type,
                                      int windows_key_code, int native_key_code,
                                      int modifiers, const char* text);

// Mouse wheel. delta_x/delta_y are pixel deltas (positive = content moves
// right/down, matching JavaFX ScrollEvent sign once negated by the caller).
JUX_EXPORT void JuxSendWheelEvent(JuxWebContentsHandle handle,
                                   float x, float y,
                                   float delta_x, float delta_y, int modifiers);

// Keyboard. type: 0=keydown(rawkeydown), 1=keyup, 2=char. text is UTF-8
// (null-terminated) carrying the typed character(s) for a char event; it may
// be NULL/empty for keydown/keyup.
JUX_EXPORT void JuxSendKeyEvent(JuxWebContentsHandle handle, int type,
                                 int windows_key_code, int native_key_code,
                                 int modifiers, const char* text);

// Focus gained/lost for the off-screen widget (equivalent to JuxNotifyFocus,
// named for symmetry with the input commands).
JUX_EXPORT void JuxSendFocusEvent(JuxWebContentsHandle handle, int focused);

// Notifies Chromium that the DPI scale factor changed (e.g. moved to
// a monitor with a different scale).
JUX_EXPORT void JuxNotifyScaleFactorChanged(JuxWebContentsHandle handle,
                                             float scale_factor);

// =========================================================================
// DOM manipulation (via Mojo IPC to renderer process)
//
// These functions manipulate Blink's DOM tree directly — no JavaScript.
// All node IDs are logical handles assigned by Java (monotonic int64).
// The renderer maintains a HashMap<int64, blink::Element*> for resolution.
//
// On navigation/page reload, the entire map is cleared and Java is
// notified via on_load_status_changed (DOC_LOADING).
// =========================================================================

// Creates a new DOM element with the given tag name.
// The element is not attached to the tree — use JuxAppendChild or
// JuxInsertBefore to insert it.
JUX_EXPORT void JuxCreateElement(JuxWebContentsHandle handle,
                                  int64_t node_id, const char* tag);

// Removes a DOM element from the tree and the node map.
// The element and all its descendants are destroyed.
JUX_EXPORT void JuxRemoveElement(JuxWebContentsHandle handle,
                                  int64_t node_id);

// Sets an attribute on a DOM element.
// name and value are UTF-8, null-terminated.
JUX_EXPORT void JuxSetAttribute(JuxWebContentsHandle handle,
                                 int64_t node_id,
                                 const char* name, const char* value);

// Removes an attribute from a DOM element.
JUX_EXPORT void JuxRemoveAttribute(JuxWebContentsHandle handle,
                                    int64_t node_id, const char* name);

// Answers a native <select> popup surfaced via OnSelectPopup. `indices` are
// the option indices to select (count=0 cancels / no change).
JUX_EXPORT void JuxSelectPopupResponse(JuxWebContentsHandle handle,
                                        uint32_t popup_id,
                                        const int32_t* indices, uint32_t count);

// Answers an <input type=color> picker surfaced via OnColorChooser. chosen=false
// cancels; rgba is packed 0xRRGGBBAA.
JUX_EXPORT void JuxColorChooserResponse(JuxWebContentsHandle handle,
                                         uint32_t chooser_id, bool chosen,
                                         uint32_t rgba);

// Answers a file chooser (id from kFileChooserRequested). count==0 ⇒ cancel;
// otherwise temp_path is a UTF-8 file of `count` newline-separated native paths
// that this reads and deletes, handing the paths to Blink as native files
// (the renderer streams large uploads straight from disk). Runs on the UI thread.
JUX_EXPORT void JuxFileChooserResponse(JuxWebContentsHandle handle,
                                        uint32_t chooser_id, uint32_t count,
                                        const char* temp_path);

// Moves the hidden engine window's origin to the WebView node's on-screen
// position (DIP) so Blink's native page-popups land over the control. Keeps the
// current size; never shows the window.
JUX_EXPORT void JuxSetScreenOrigin(JuxWebContentsHandle handle,
                                    double screen_x, double screen_y,
                                    double scale);

// Tells the renderer which form popups the app overrides (so it suppresses the
// native page-popup and surfaces the request to Java for those controls).
JUX_EXPORT void JuxSetPopupOverrides(JuxWebContentsHandle handle,
                                     bool select_overridden,
                                     bool color_overridden);

// Runs a Blink editor command on the focused frame: 0=Copy, 1=Cut, 2=Paste,
// 3=SelectAll, 4=Undo, 5=Redo, 6=Delete (clipboard via the browser process).
JUX_EXPORT void JuxExecEditingCommand(JuxWebContentsHandle handle, uint32_t cmd);

// (JuxShowContextMenu retired: the context menu is rendered as a JavaFX menu in
// the foreground process. The browser only fires the contextmenu request signal.)

// Sets the text content of a DOM element (replaces all children with
// a single text node).
JUX_EXPORT void JuxSetTextContent(JuxWebContentsHandle handle,
                                   int64_t node_id, const char* text);

// Sets the inner HTML of a DOM element (parses HTML and replaces children).
JUX_EXPORT void JuxSetInnerHTML(JuxWebContentsHandle handle,
                                 int64_t node_id, const char* html);

// Appends a child element to a parent element.
// Both parent_id and child_id must refer to existing nodes.
JUX_EXPORT void JuxAppendChild(JuxWebContentsHandle handle,
                                int64_t parent_id, int64_t child_id);

// Inserts a child element before a reference element.
// ref_id must be a child of parent_id. If ref_id is 0, appends at the end.
JUX_EXPORT void JuxInsertBefore(JuxWebContentsHandle handle,
                                 int64_t parent_id,
                                 int64_t child_id, int64_t ref_id);

// Sets a CSS style property on a DOM element.
// prop is the CSS property name (e.g. "background-color").
// value is the CSS value (e.g. "red").
JUX_EXPORT void JuxSetStyleProperty(JuxWebContentsHandle handle,
                                     int64_t node_id,
                                     const char* prop, const char* value);

// Adds a CSS class to a DOM element's classList.
JUX_EXPORT void JuxAddClass(JuxWebContentsHandle handle,
                             int64_t node_id, const char* class_name);

// Removes a CSS class from a DOM element's classList.
JUX_EXPORT void JuxRemoveClass(JuxWebContentsHandle handle,
                                int64_t node_id, const char* class_name);

// =========================================================================
// Stylesheets
// =========================================================================

// Injects a <style> element with the given CSS text.
// id is a caller-assigned identifier for later removal.
JUX_EXPORT void JuxAddStylesheet(JuxWebContentsHandle handle,
                                  uint32_t id,
                                  const char* css, uint32_t css_len);

// Removes a previously injected stylesheet by its ID.
JUX_EXPORT void JuxRemoveStylesheet(JuxWebContentsHandle handle, uint32_t id);

// =========================================================================
// DOM event listeners
// =========================================================================

// Registers a DOM event listener on a node. The renderer will attach a
// C++ EventListener to the Blink element. When fired, the event is
// forwarded via Mojo to the browser process, which calls the on_dom_event
// callback.
//
// node_id: the target DOM element.
// event_type: lowercase DOM event name (e.g. "click", "input", "mousedown").
JUX_EXPORT void JuxAddEventListener(JuxWebContentsHandle handle,
                                     int64_t node_id,
                                     const char* event_type);

// Removes a previously registered DOM event listener.
JUX_EXPORT void JuxRemoveEventListener(JuxWebContentsHandle handle,
                                        int64_t node_id,
                                        const char* event_type);

// Registers a set of (HT* code, node_id) pairs with the renderer so it
// can push live getBoundingClientRect values back to the browser via
// the JuxDomClient pipe. The browser uses those rects to drive the
// Win32 custom-chrome subclass's hit-spot cache — clearing the array
// clears the subscription. Each entry is 12 bytes: uint32 code +
// uint32 (padding) + int64 node_id.
//
// The caller does not need to keep the buffer alive after the call
// returns — the implementation posts the data to the UI thread.
typedef struct {
  uint32_t code;
  uint32_t _pad;  // alignment
  int64_t  node_id;
} JuxHitSpotNode;

JUX_EXPORT void JuxSetHitSpotNodes(JuxWebContentsHandle handle,
                                    const JuxHitSpotNode* nodes,
                                    uint32_t count);

// =========================================================================
// DOM tree sync
// =========================================================================

// Requests the renderer to walk the current DOM tree and send all nodes
// to the browser process via Mojo. The browser process fires on_dom_element
// and on_dom_text callbacks for each node, followed by on_dom_tree_ready.
JUX_EXPORT void JuxRequestDomTree(JuxWebContentsHandle handle);

// =========================================================================
// Callbacks
//
// All callbacks are invoked on the Chromium browser thread.
// The engine writes events to the ring buffer from these callbacks.
// EventWriter is thread-safe (atomic operations).
//
// String parameters in callbacks are valid only for the duration of the
// callback invocation. Copy them if you need to retain them.
// =========================================================================

// Called when the document title changes.
// title is UTF-8, title_len is byte length (not null-terminated).
typedef void (*JuxOnTitleChanged)(JuxWebContentsHandle handle,
                                   const char* title, uint32_t title_len);

// Called when the page load status changes.
// status values:
//   0 = loading started (DOC_LOADING)
//   1 = DOM interactive (DOC_INTERACTIVE)
//   2 = content loaded (DOC_CONTENT_LOADED)
//   3 = fully loaded (DOC_READY)
typedef void (*JuxOnLoadStatusChanged)(JuxWebContentsHandle handle,
                                        uint32_t status);

// Called when the URL changes (navigation committed).
// url is UTF-8, url_len is byte length.
typedef void (*JuxOnURLChanged)(JuxWebContentsHandle handle,
                                 const char* url, uint32_t url_len);

// Called after a committed main-frame navigation with the full session-history
// snapshot, pre-serialized as the kHistoryState event payload (after windowId):
//   [currentIndex:4(int32)][count:4]{[urlLen:4][url][titleLen:4][title]}…
// `data`/`len` is that blob; the receiver writes it via WriteEventLarge. Kept
// opaque here so the variable-length list rides one callback.
typedef void (*JuxOnHistoryChanged)(JuxWebContentsHandle handle,
                                     const uint8_t* data, uint32_t len);

// Called periodically + after navigation with the serialized session snapshot
// (NavigationController + PageState: URL/scroll/forms/history) for crash
// recovery. `data`/`len` is an opaque engine blob; the receiver ships it as the
// kSessionState event, and Java replays it via JuxRestoreSession after a respawn.
typedef void (*JuxOnSessionState)(JuxWebContentsHandle handle,
                                  const uint8_t* data, uint32_t len);

// Called with the result of JuxExecuteJS.
// result is a JSON-encoded string. result_len is byte length.
typedef void (*JuxOnJSResult)(JuxWebContentsHandle handle,
                               uint32_t request_id,
                               const char* result, uint32_t result_len);

// Called when JuxExecuteJS encounters an error.
// error is a UTF-8 error message. error_len is byte length.
typedef void (*JuxOnJSError)(JuxWebContentsHandle handle,
                              uint32_t request_id,
                              const char* error, uint32_t error_len);

// Typed result of executeScript and every JSObject op. `value` is tagged bytes
// (jux_js_value.h); request_id correlates to the originating command. The
// receiver writes it as the kJsValue event (via WriteEventLarge).
typedef void (*JuxOnJsValue)(JuxWebContentsHandle handle, uint32_t request_id,
                             const uint8_t* value, uint32_t value_len);

// Called when a registered DOM event fires.
// node_id: the element that fired the event.
// event_type: the DOM event name (e.g. "click"). event_type_len is byte length.
// payload: event-specific binary data. payload_len is byte length.
//   Mouse events: [x:4(f32)][y:4(f32)][button:4(u32)]
//   Keyboard events: [keyCode:4(u32)][modifiers:4(u32)][repeat:1(u8)]
//   Scroll events: [scrollX:8(f64)][scrollY:8(f64)]
//   Input events: [valueLen:2(u16)][utf8Value:N]
//   Focus events: [relatedNodeId:4(u32)]
typedef void (*JuxOnDomEvent)(JuxWebContentsHandle handle,
                               int64_t node_id,
                               const char* event_type, uint32_t event_type_len,
                               const uint8_t* payload, uint32_t payload_len);

// Called during DOM tree sync for each element node.
// tag, id_attr, class_attr are UTF-8 with their respective byte lengths.
// id_attr and class_attr may be NULL if the element has no id/class.
typedef void (*JuxOnDomElement)(JuxWebContentsHandle handle,
                                 int64_t node_id, int64_t parent_id,
                                 const char* tag, uint32_t tag_len,
                                 const char* id_attr, uint32_t id_len,
                                 const char* class_attr, uint32_t class_len);

// Called during DOM tree sync for each text node.
// text is UTF-8, text_len is byte length.
typedef void (*JuxOnDomText)(JuxWebContentsHandle handle,
                              int64_t node_id, int64_t parent_id,
                              const char* text, uint32_t text_len);

// Called when DOM tree sync is complete.
typedef void (*JuxOnDomTreeReady)(JuxWebContentsHandle handle);

// Called when the user clicks the window close button (via WebContentsDelegate).
// The engine should fire WINDOW_CLOSE_REQUEST to Java.
typedef void (*JuxOnCloseRequested)(JuxWebContentsHandle handle);

// Called when the renderer process crashes or is killed.
// status values match base::TerminationStatus:
//   0 = NORMAL_TERMINATION
//   1 = ABNORMAL_TERMINATION
//   2 = PROCESS_WAS_KILLED
//   3 = PROCESS_CRASHED
//   5 = LAUNCH_FAILED
//   6 = OOM
typedef void (*JuxOnRenderProcessGone)(JuxWebContentsHandle handle,
                                        int status);

// Called when a page navigation fails (net::Error, DNS failure, TLS
// error, 4xx/5xx response when configured to surface them, etc.).
// error_code is a signed net::Error value (negative). url/description
// are UTF-8 without null terminators; *_len is byte length.
typedef void (*JuxOnLoadError)(JuxWebContentsHandle handle,
                               int32_t error_code,
                               const char* url, uint32_t url_len,
                               const char* description,
                               uint32_t description_len);

// Called for every console message (console.log/warn/error) and every
// uncaught JavaScript exception. level: 0=Verbose, 1=Info, 2=Warning,
// 3=Error. JS uncaught exceptions arrive with level=3 and the stack
// trace embedded in the message. source_id is typically the URL or
// "about:blank".
typedef void (*JuxOnConsoleMessage)(JuxWebContentsHandle handle,
                                     uint32_t level,
                                     uint32_t line_number,
                                     const char* message,
                                     uint32_t message_len,
                                     const char* source_id,
                                     uint32_t source_id_len);

// Called when page JS opens a dialog (alert/confirm/prompt/beforeunload). The
// engine has stashed Chromium's continuation and suspended the page; Java must
// answer via JuxRespondDialog. dialog_type: 0=alert,1=confirm,2=prompt,
// 3=beforeunload. message/default_text are UTF-8 (no null terminator); *_len is
// byte length.
typedef void (*JuxOnDialogRequested)(JuxWebContentsHandle handle,
                                      uint32_t dialog_id, uint32_t dialog_type,
                                      const char* message, uint32_t message_len,
                                      const char* default_text,
                                      uint32_t default_len);

// Called when the page enters (entering=1) or leaves (entering=0) fullscreen.
// A notification; the app toggles its own Stage and may deny an entry via
// JuxRespondFullscreen(allowed=0), which kicks the page back out.
typedef void (*JuxOnFullscreenRequested)(JuxWebContentsHandle handle,
                                          uint32_t fs_id, uint8_t entering);

// Called when the page's favicon URL changes. url is UTF-8, no terminator.
typedef void (*JuxOnFaviconChanged)(JuxWebContentsHandle handle,
                                     const char* url, uint32_t url_len);

// Callback registration struct. Pass NULL for any callback you don't need.
typedef struct {
    JuxOnTitleChanged        on_title_changed;
    JuxOnLoadStatusChanged   on_load_status_changed;
    JuxOnURLChanged          on_url_changed;
    JuxOnHistoryChanged      on_history_changed;
    JuxOnSessionState        on_session_state;
    JuxOnJsValue             on_js_value;
    JuxOnJSResult            on_js_result;
    JuxOnJSError             on_js_error;
    JuxOnDomEvent            on_dom_event;
    JuxOnDomElement          on_dom_element;
    JuxOnDomText             on_dom_text;
    JuxOnDomTreeReady        on_dom_tree_ready;
    JuxOnCloseRequested      on_close_requested;
    JuxOnRenderProcessGone   on_render_process_gone;
    JuxOnLoadError           on_load_error;
    JuxOnConsoleMessage      on_console_message;
    JuxOnDialogRequested     on_dialog_requested;
    JuxOnFullscreenRequested on_fullscreen_requested;
    JuxOnFaviconChanged      on_favicon_changed;
} JuxCallbacks;

// Registers all callbacks at once. The callbacks struct is copied — the
// caller does not need to keep it alive after this call returns.
// Can be called before or after JuxCreateWebContents.
JUX_EXPORT void JuxSetCallbacks(JuxCallbacks callbacks);

#ifdef __cplusplus
}
#endif

#endif  // JUX_ENGINE_API_H_
