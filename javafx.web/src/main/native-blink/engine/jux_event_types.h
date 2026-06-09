// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// Event type constants for C++ engine → Java communication.
//
// These constants must exactly match NativeEventType.java on the Java side.
// Events are written to the event ring buffer by the engine and read by
// the Java EventDispatchLoop.
//
// IMPORTANT: Java is the canonical source of truth for all constant values.

#ifndef JUX_EVENT_TYPES_H_
#define JUX_EVENT_TYPES_H_

#include <cstdint>

namespace jux {
namespace events {

// ── System events (0x0001–0x000F) ────────────────────────────────────

// Engine has started and is ready to process commands.
inline constexpr uint32_t kEngineReady = 0x0001;
// Engine encountered a fatal error.
inline constexpr uint32_t kEngineError = 0x0002;
// Engine has completed a graceful shutdown.
inline constexpr uint32_t kEngineShutdown = 0x0003;
// Renderer process crashed or was killed.
// Payload: [windowId:4][terminationStatus:4]
inline constexpr uint32_t kRenderProcessGone = 0x0004;
// GPU process crashed — Chromium will auto-restart it, but Java may
// need to redraw or log the event.
// Payload: [windowId:4]
inline constexpr uint32_t kGpuProcessCrashed = 0x0005;

// ── Window lifecycle events (0x0010–0x001F) ──────────────────────────

inline constexpr uint32_t kWindowShowing = 0x0010;
inline constexpr uint32_t kWindowShown = 0x0011;
inline constexpr uint32_t kWindowHiding = 0x0012;
inline constexpr uint32_t kWindowHidden = 0x0013;
inline constexpr uint32_t kWindowCloseRequest = 0x0014;
inline constexpr uint32_t kWindowClosing = 0x0015;
inline constexpr uint32_t kWindowClosed = 0x0016;

// ── Window state change events (0x0020–0x002F) ──────────────────────

inline constexpr uint32_t kWindowFocused = 0x0020;
inline constexpr uint32_t kWindowUnfocused = 0x0021;
inline constexpr uint32_t kWindowMinimized = 0x0022;
inline constexpr uint32_t kWindowMaximized = 0x0023;
inline constexpr uint32_t kWindowRestored = 0x0024;
inline constexpr uint32_t kWindowFullscreenChanged = 0x0025;
// Payload: [windowId:4][x:8(double)][y:8(double)]
inline constexpr uint32_t kWindowMoved = 0x0026;
// Payload: [windowId:4][width:8(double)][height:8(double)]
inline constexpr uint32_t kWindowResized = 0x0027;
// Payload: [windowId:4][isDark:1(byte)]
inline constexpr uint32_t kWindowThemeChanged = 0x0028;
// Payload: [windowId:4][scaleFactor:8(double)]
inline constexpr uint32_t kWindowDpiChanged = 0x0029;

// ── Mouse input events (0x0100–0x010F) ───────────────────────────────

// Payload: [windowId:4][x:8][y:8][screenX:8][screenY:8][modifiers:4]
inline constexpr uint32_t kMouseMove = 0x0100;
// Payload: [windowId:4][x:8][y:8][screenX:8][screenY:8][button:4][modifiers:4]
inline constexpr uint32_t kMouseDown = 0x0101;
inline constexpr uint32_t kMouseUp = 0x0102;
// Payload: [windowId:4][x:8][y:8][screenX:8][screenY:8][button:4][clickCount:4][modifiers:4]
inline constexpr uint32_t kMouseClick = 0x0103;
inline constexpr uint32_t kMouseDblClick = 0x0104;
inline constexpr uint32_t kMouseEnter = 0x0105;
inline constexpr uint32_t kMouseLeave = 0x0106;
// Payload: [windowId:4][x:8][y:8][screenX:8][screenY:8][deltaX:8][deltaY:8][modifiers:4]
inline constexpr uint32_t kMouseWheel = 0x0107;
// The hovered element's cursor changed (off-screen rendering: the engine has no
// OS window to apply it to, so it forwards the type to Java which sets it on the
// JavaFX WebView node). Payload: [windowId:4][cursorType:4(int32)] where
// cursorType is a com.sun.webkit.CursorManager constant (POINTER=0, HAND=2, …).
inline constexpr uint32_t kCursorChanged = 0x0108;

// ── Keyboard input events (0x0110–0x011F) ────────────────────────────

// Payload: [windowId:4][keyCode:4][modifiers:4][repeat:1]
inline constexpr uint32_t kKeyDown = 0x0110;
inline constexpr uint32_t kKeyUp = 0x0111;
// Payload: [windowId:4][charLen:4][utf8Char:N][modifiers:4]
inline constexpr uint32_t kKeyPress = 0x0112;

// ── Document events (0x0200–0x020F) ──────────────────────────────────

inline constexpr uint32_t kDocLoading = 0x0200;
inline constexpr uint32_t kDocInteractive = 0x0201;
inline constexpr uint32_t kDocReady = 0x0202;
inline constexpr uint32_t kDocContentLoaded = 0x0203;
// Payload: [windowId:4][titleLen:4][utf8Title:N]
inline constexpr uint32_t kDocTitleChanged = 0x0204;
// Payload: [windowId:4][urlLen:4][utf8Url:N]
inline constexpr uint32_t kDocNavigation = 0x0205;
// One-shot "ready to show" signal emitted on first visually non-empty
// paint, or (as a backstop) when loading stops / fails — whichever
// arrives first. Payload: [windowId:4]. Used by Java's showWhenReady
// to defer the first window-show until content has actually painted,
// avoiding a flash of an empty OS window at startup.
inline constexpr uint32_t kDocReadyToShow = 0x0206;
// Session-history snapshot after a committed main-frame navigation. The full
// entry list rides inline via WriteEventLarge (URLs can exceed one slot), and
// Java's EventRingBuffer reassembles it. Payload (after windowId):
//   [currentIndex:4(int32)][count:4]{[urlLen:4][url][titleLen:4][title]}…
// Mirrors NativeEventType.HISTORY_STATE (Java canonical).
inline constexpr uint32_t kHistoryState = 0x0207;
// Serialized session snapshot for crash recovery (URL+scroll+forms+history),
// emitted periodically + after navigation via WriteEventLarge. Opaque blob Java
// stores and replays via kRestoreSession. Payload (after windowId): the blob.
// Mirrors NativeEventType.SESSION_STATE.
inline constexpr uint32_t kSessionState = 0x0208;

// ── JavaScript bridge events (0x0210–0x021F) ─────────────────────────

// Payload: [windowId:4][requestId:4][resultLen:4][utf8Result:N]
inline constexpr uint32_t kJsResult = 0x0210;
// Payload: [windowId:4][requestId:4][errorLen:4][utf8Error:N]
inline constexpr uint32_t kJsError = 0x0211;
// Payload: [windowId:4][javaObjectId:4][nameLen:4][utf8Name:N][argc:4]{value:tagged}…
// JS invoked a method on a Java object exposed via JSObject.setMember.
inline constexpr uint32_t kJsCallback = 0x0212;
// Payload: [windowId:4][requestId:4][value:tagged] — typed result of any sync JS
// op (executeScript + every JSObject op). Sent via WriteEventLarge. Tagged value
// per jux_js_value.h. Mirrors NativeEventType.JS_VALUE.
inline constexpr uint32_t kJsValue = 0x0213;

// ── Off-screen render events (0x0600–0x060F) ─────────────────────────
//
// skia-fx OSR: emitted after the engine copies a freshly-captured frame
// (whole page, all iframes folded in by viz) into the channel's data
// region. Java reads the published double-buffer slot and composites it
// into the WebView scene node — there is no Chromium OS window. Payload
// (after windowId prepended):
//   [bufIndex:4][width:4][height:4][stride:4]
// width/height are device pixels; stride is the row stride in bytes
// (width*4, BGRA8888 premultiplied); bufIndex selects the slot (0 or 1).
inline constexpr uint32_t kFrameReady = 0x0600;
// OSR popup frame (Blink page-popup: <select>/color/datalist). Pixels live in a
// double-buffered popup slot at the END of the data region. Payload (after windowId):
//   [bufIndex:4][w:4][h:4][stride:4][x:4(f32)][y:4(f32)][dipW:4(f32)][dipH:4(f32)]
// w/h/stride describe the device-px BGRA8888 bitmap in the slot; x/y/dipW/dipH are
// the popup's rect in the MAIN view's local DIP (= node-logical) space. Java
// stretches the bitmap into that logical rect as an overlay.
inline constexpr uint32_t kPopupFrame = 0x0601;
// The OSR popup closed (no payload) — Java clears the overlay.
inline constexpr uint32_t kPopupClosed = 0x0602;

// ── Print preview overlay (0x0610–0x061F; 0x0700+ is the network range) ──
// The browser created an off-screen chrome://print WebContents in response to
// window.print()/Ctrl+P (see jux::OpenPrintPreviewWebContents). It is a full
// second WebContents, not a popup, so M4 gives it its OWN capture surface region
// (mirroring the popup region) and Java composites it as a MODAL overlay over the
// initiator view, forwarding mouse/keyboard/wheel to it.
// Payload (after windowId): [previewHandle:4] — the JuxWebContentsHandle of the
// preview; Java drives JuxCaptureTick(previewHandle) + composites its frames.
inline constexpr uint32_t kPrintPreviewOpened = 0x0610;
// The preview was closed/cancelled (payload [previewHandle:4]) — Java removes the
// overlay and the engine tears down the preview WebContents.
inline constexpr uint32_t kPrintPreviewClosed = 0x0611;
// A print-preview MODAL frame: the preview WebContents' own view, captured into
// the dedicated PREVIEW region (separate from the popup region so the preview's
// own <select> dropdowns can use the popup region without colliding). Same
// payload as kPopupFrame; x/y are 0 (Java centers it over the page).
inline constexpr uint32_t kPreviewFrame = 0x0612;

// ── Tray icon events (0x0300–0x030F) ─────────────────────────────────

inline constexpr uint32_t kTrayClicked = 0x0300;
inline constexpr uint32_t kTrayDblClicked = 0x0301;
inline constexpr uint32_t kTrayRightClicked = 0x0302;
inline constexpr uint32_t kTrayMenuAction = 0x0303;

// ── Dialog result events (0x0400–0x040F) ─────────────────────────────

inline constexpr uint32_t kDialogOpenResult = 0x0400;
inline constexpr uint32_t kDialogSaveResult = 0x0401;
inline constexpr uint32_t kDialogDirResult = 0x0402;

// ── Print events (0x0410–0x041F) ─────────────────────────────────────

// Fired when the rendered page invokes window.print(). Payload:
// [windowId:4]. Java may use this to show a custom UI; otherwise the
// engine defaults to the native print dialog.
inline constexpr uint32_t kPrintRequested = 0x0410;
// Fired after the user completes or cancels the native print dialog.
// Payload: [windowId:4][success:1(byte)] — 1 for printed, 0 for
// cancelled/failed.
inline constexpr uint32_t kPrintResult = 0x0411;

// ── Error / diagnostic events (0x0420–0x042F) ────────────────────────
//
// These surface failure conditions and observability info so Java can
// raise exceptions or log diagnostics instead of silently ignoring
// engine-side failures.

// Page-level load error (e.g. DNS failure, TLS failure, net::ERR_*
// result from a navigation). Payload:
//   [windowId:4]
//   [errorCode:4(int32 little-endian, negative net::Error)]
//   [urlLen:4][utf8Url:N]
//   [descLen:4][utf8Description:N]
inline constexpr uint32_t kLoadError = 0x0420;

// Console message or JavaScript exception. Payload:
//   [windowId:4]
//   [level:4(uint32; 0=Verbose,1=Info,2=Warning,3=Error)]
//   [lineNumber:4(uint32; 0 if unknown)]
//   [messageLen:4][utf8Message:N]
//   [sourceIdLen:4][utf8SourceId:N]
// JavaScript uncaught exceptions arrive with level=3 (Error) and the
// stack trace embedded in the message.
inline constexpr uint32_t kConsoleMessage = 0x0421;

// Renderer-side IPC/pipe error (e.g. DOM Mojo pipe disconnected).
// Payload: [windowId:4][msgLen:4][utf8Message:N]
inline constexpr uint32_t kIpcError = 0x0422;

// ── DOM tree sync events (0x0500–0x050F) ─────────────────────────────

// Payload: [nodeId:4][parentId:4][tagLen:1][tag:N][idLen:1][id:N][classLen:2][class:N]
inline constexpr uint32_t kDomElement = 0x0500;
// Payload: [nodeId:4][parentId:4][textLen:2][text:N]
inline constexpr uint32_t kDomText = 0x0501;
inline constexpr uint32_t kDomTreeReady = 0x0502;

// ── DOM interaction events (0x0510–0x052F) ───────────────────────────

// Payload: [nodeId:4][x:4(f32)][y:4(f32)][button:4]
inline constexpr uint32_t kDomClick = 0x0510;
inline constexpr uint32_t kDomMouseEnter = 0x0511;
inline constexpr uint32_t kDomMouseLeave = 0x0512;
inline constexpr uint32_t kDomFocus = 0x0513;
inline constexpr uint32_t kDomBlur = 0x0514;
inline constexpr uint32_t kDomMouseDown = 0x0515;
inline constexpr uint32_t kDomMouseUp = 0x0516;
inline constexpr uint32_t kDomMouseMove = 0x0517;
inline constexpr uint32_t kDomKeyDown = 0x0518;
inline constexpr uint32_t kDomKeyUp = 0x0519;
inline constexpr uint32_t kDomKeyPress = 0x051A;
inline constexpr uint32_t kDomDblClick = 0x051B;
inline constexpr uint32_t kDomContextMenu = 0x051C;
inline constexpr uint32_t kDomMouseOver = 0x051D;
inline constexpr uint32_t kDomMouseOut = 0x051E;
inline constexpr uint32_t kDomFocusIn = 0x051F;
inline constexpr uint32_t kDomFocusOut = 0x0520;
inline constexpr uint32_t kDomScroll = 0x0521;
inline constexpr uint32_t kDomInput = 0x0522;

// ── DOM mutation events (0x0530–0x053F) ──────────────────────────────
//
// Fired when non-Java-initiated DOM changes (JS, parser, etc.) affect
// nodes the browser already tracks. Java uses these to keep its
// MutationBridge (and ultimately the Java DOM tree) in sync with the
// live Blink DOM.

// Attribute mutation. Payload (after windowId prepended):
//   [nodeId:4][nameLen:2][name:N][oldLen:2][old:N][newLen:2][new:N]
inline constexpr uint32_t kMutationAttribute = 0x0530;

// Child-list mutation. Payload:
//   [parentId:4][addedCount:4]{[addedId:4]}...
//                [removedCount:4]{[removedId:4]}...
inline constexpr uint32_t kMutationChildren = 0x0531;

// Character-data mutation on a text node. Payload:
//   [nodeId:4][oldLen:2][old:N][newLen:2][new:N]
inline constexpr uint32_t kMutationText = 0x0532;

// ── JS dialogs (0x0430–0x043F) ───────────────────────────────────────
//
// The engine has stashed Chromium's DialogClosedCallback and kept the page's
// JS suspended; it resumes only when Java sends kDialogResponse. Payload
// (after windowId): [dialogId:4][dialogType:4][msgLen:4][utf8Msg:N]
//                   [defLen:4][utf8Default:N]
// dialogType: 0=alert, 1=confirm, 2=prompt, 3=beforeunload.
inline constexpr uint32_t kDialogRequested = 0x0430;

// Engine needs a save location for a print-to-PDF (WebEngine.print() / the
// preview's Save button). Java shows a JavaFX FileChooser (owned by the WebView's
// Stage) and answers with kSavePdfResponse; the engine then writes the PDF to the
// chosen path. Payload (after windowId): [requestId:4][nameLen:4][utf8Name:N].
inline constexpr uint32_t kSavePdfRequested = 0x0431;

// ── Choosers (0x0440–0x044F) ─────────────────────────────────────────
// [chooserId:4][initialRgba:4][suggCount:4]{[rgba:4]}...
inline constexpr uint32_t kColorChooserOpen = 0x0440;
// [popupId:4][flags:4(bit0=multiple)][selIndex:4][anchorX:4(f32)][anchorY:4(f32)]
// [anchorW:4(f32)][anchorH:4(f32)][pathLen:4][utf8TempFilePath:N] — items JSON in temp file.
inline constexpr uint32_t kSelectPopupOpen = 0x0441;
// [chooserId:4][mode:4][initLen:4][utf8InitialName:N][filtLen:4][utf8MimeFilters:N]
inline constexpr uint32_t kFileChooserRequested = 0x0442;

// ── Permissions (0x0450–0x045F) ──────────────────────────────────────
// [permId:4][permType:4][originLen:4][utf8Origin:N]
inline constexpr uint32_t kPermissionRequested = 0x0450;

// ── Auth (0x0460–0x046F) ─────────────────────────────────────────────
// [authId:4][scheme:4][isProxy:1][hostLen:4][utf8Host:N][realmLen:4][utf8Realm:N]
inline constexpr uint32_t kAuthRequested = 0x0460;

// ── Downloads (0x0470–0x047F) ────────────────────────────────────────
// [downloadId:4][totalBytes:8][urlLen:4][utf8Url:N][nameLen:4][utf8Name:N][mimeLen:4][utf8Mime:N]
inline constexpr uint32_t kDownloadRequested = 0x0470;
// [downloadId:4][state:4][received:8][total:8]
inline constexpr uint32_t kDownloadProgress = 0x0471;
// [downloadId:4][state:4][pathLen:4][utf8Path:N]
inline constexpr uint32_t kDownloadFinished = 0x0472;

// ── Context menu / fullscreen / misc (0x0480–0x048F) ─────────────────
// [menuId:4][x:4(f32)][y:4(f32)][flags:4][linkLen:4][utf8Link:N][srcLen:4][utf8Src:N]
// [selLen:4][utf8Selection:N]; flags: bit0=editable,bit1=hasLink,bit2=hasImage,bit3=hasSelection.
inline constexpr uint32_t kContextMenuRequested = 0x0480;
// [fsId:4][entering:1]
inline constexpr uint32_t kFullscreenRequested = 0x0481;
// [urlLen:4][utf8IconUrl:N]
inline constexpr uint32_t kFaviconChanged = 0x0482;
// [textLen:4][utf8Text:N]
inline constexpr uint32_t kTooltipChanged = 0x0483;
// 0x0484 (kContextMenuCommand) retired: context-menu items are rendered and run
// in the foreground JavaFX process, so the engine never reports a chosen index.

// ── Network interception (0x0700–0x070F) ─────────────────────────────
//
// Surfaced only for requests the engine-side filter matched. Response body
// chunks ride a temp file (path in the event); the ring carries only chunk
// metadata. Correlated by interceptId.

// [interceptId:4][resourceType:4][methodLen:2][method][urlLen:4][url][hdrBlobLen:4][hdrBlob]
inline constexpr uint32_t kRequestWillBeSent = 0x0700;
// [interceptId:4][status:4][mimeLen:2][mime][contentLen:8][hdrBlobLen:4][hdrBlob][flags:4]
inline constexpr uint32_t kResponseReceived = 0x0701;
// [interceptId:4][chunkSeq:4][offset:8][last:1][len:4][pathLen:4][utf8Path:N]
// (temp-file path — written by the engine, read+deleted by Java)
inline constexpr uint32_t kResponseBodyChunk = 0x0702;
// [interceptId:4][netError:4(int32)] — load resumed/failed; frees the Java exchange.
inline constexpr uint32_t kInterceptComplete = 0x0703;

}  // namespace events
}  // namespace jux

#endif  // JUX_EVENT_TYPES_H_
