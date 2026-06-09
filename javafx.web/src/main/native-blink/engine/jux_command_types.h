// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// Command type constants for Java → C++ engine communication.
//
// These constants must exactly match CommandType.java on the Java side.
// Commands are written to the command ring buffer by Java and read by
// the engine's command dispatch loop.
//
// IMPORTANT: Java is the canonical source of truth for all constant values.

#ifndef JUX_COMMAND_TYPES_H_
#define JUX_COMMAND_TYPES_H_

#include <cstdint>

namespace jux {
namespace commands {

// ── Window lifecycle (0x0001–0x000F) ─────────────────────────────────

inline constexpr uint32_t kCreateWindow = 0x0001;
inline constexpr uint32_t kDestroyWindow = 0x0002;
inline constexpr uint32_t kShow = 0x0003;
inline constexpr uint32_t kHide = 0x0004;

// ── Window properties (0x0010–0x001F) ────────────────────────────────

inline constexpr uint32_t kSetTitle = 0x0010;
inline constexpr uint32_t kSetSize = 0x0011;
inline constexpr uint32_t kSetPosition = 0x0012;
inline constexpr uint32_t kSetMinSize = 0x0013;
inline constexpr uint32_t kSetMaxSize = 0x0014;
inline constexpr uint32_t kSetState = 0x0015;
inline constexpr uint32_t kSetResizable = 0x0016;
inline constexpr uint32_t kSetDecorated = 0x0017;
inline constexpr uint32_t kSetAlwaysOnTop = 0x0018;
inline constexpr uint32_t kSetOpacity = 0x0019;
inline constexpr uint32_t kSetTransparent = 0x001A;
inline constexpr uint32_t kSetClosable = 0x001B;
inline constexpr uint32_t kSetMinimizable = 0x001C;
inline constexpr uint32_t kSetMaximizable = 0x001D;
inline constexpr uint32_t kSetHideFromTaskBar = 0x001E;
inline constexpr uint32_t kSetFocusable = 0x001F;

// ── Window appearance (0x0020–0x002F) ────────────────────────────────

inline constexpr uint32_t kSetIcons = 0x0020;
inline constexpr uint32_t kSetCursor = 0x0021;
inline constexpr uint32_t kSetBackground = 0x0022;

// ── DOM / Content commands (0x0030–0x003F) ───────────────────────────

inline constexpr uint32_t kLoadHtml = 0x0030;
inline constexpr uint32_t kLoadUrl = 0x0031;
inline constexpr uint32_t kExecuteJs = 0x0032;
inline constexpr uint32_t kLoadResources = 0x0033;
inline constexpr uint32_t kOpenDevTools = 0x0034;
inline constexpr uint32_t kCloseDevTools = 0x0035;
inline constexpr uint32_t kAddStylesheet = 0x0036;
inline constexpr uint32_t kRemoveStylesheet = 0x0037;
inline constexpr uint32_t kSetOwner = 0x0038;
inline constexpr uint32_t kSetModality = 0x0039;
inline constexpr uint32_t kSetTrayIcon = 0x003A;
// Like kExecuteJs but the inline string is a temp-file PATH whose contents are
// the script — for scripts larger than a command-ring slot (script injection).
inline constexpr uint32_t kExecuteJsFile = 0x003B;
// Per-WebView User-Agent override. Payload: [windowId:4][len:4][utf8:N].
// An empty string clears the override (reverts to the global default UA).
inline constexpr uint32_t kSetUserAgent = 0x003C;
// Move the hidden engine window's origin to the WebView node's on-screen
// position so Blink's native page-popups (select/color/datalist) land over the
// control. Payload: [windowId:4][screenX:8(double)][screenY:8(double)][scale:8(double)].
inline constexpr uint32_t kSetScreenOrigin = 0x003D;
// Which form popups the app overrides. Payload: [windowId:4][bits:4]
// (bit0=select, bit1=color, bit2=contextMenu). Overridden ⇒ engine suppresses
// its native UI and surfaces the request to Java; otherwise native.
inline constexpr uint32_t kSetPopupOverrides = 0x003E;
// Run a Blink editor command on the focused frame. Payload: [windowId:4][cmd:4]
// (0=Copy,1=Cut,2=Paste,3=SelectAll,4=Undo,5=Redo,6=Delete) — drives the context
// menu's editing items and the Ctrl+C/X/V/A/Z/Y shortcuts.
inline constexpr uint32_t kExecEditingCommand = 0x003F;

// ── Tray commands (0x0040–0x004F) ────────────────────────────────────

inline constexpr uint32_t kCreateTray = 0x0040;
inline constexpr uint32_t kDestroyTray = 0x0041;
inline constexpr uint32_t kSetTrayTooltip = 0x0042;

// ── Dialog commands (0x0050–0x005F) ──────────────────────────────────

inline constexpr uint32_t kShowOpenDialog = 0x0050;
inline constexpr uint32_t kShowSaveDialog = 0x0051;
inline constexpr uint32_t kShowDirDialog = 0x0052;

// ── Window actions (0x0060–0x006F) ───────────────────────────────────

inline constexpr uint32_t kRequestFocus = 0x0060;
inline constexpr uint32_t kReleaseFocus = 0x0061;
inline constexpr uint32_t kToFront = 0x0063;
inline constexpr uint32_t kToBack = 0x0064;
inline constexpr uint32_t kCenter = 0x0065;
inline constexpr uint32_t kSetEnabled = 0x0066;

// ── Custom chrome (0x0070–0x007F) ────────────────────────────────────

inline constexpr uint32_t kSetCustomChrome = 0x0070;
inline constexpr uint32_t kSetTitleBarHeight = 0x0071;
inline constexpr uint32_t kSetDragRegion = 0x0072;
inline constexpr uint32_t kSetMinimizeControl = 0x0073;
inline constexpr uint32_t kSetMaximizeControl = 0x0074;
inline constexpr uint32_t kSetCloseControl = 0x0075;
inline constexpr uint32_t kSetFullscreenControl = 0x0076;
// Replaces the per-element SetMinimize/Maximize/Close/FullscreenControl
// commands with a single batched update. Payload (after the 4-byte
// window id prefix):
//   [count:u32] × { code:u32, x:f64, y:f64, w:f64, h:f64 }
// `code` is the Win32 HT* value (HTMINBUTTON=8, HTMAXBUTTON=9,
// HTCLOSE=20, HTCAPTION=2, ...). Rect is in device-independent pixels
// relative to the window's client area, exactly as Java reads them
// from Element.getBoundingClientRect().
inline constexpr uint32_t kSetHitSpots = 0x0077;

// Replaces the custom-chrome hit-spot cache by (code, nodeId) pairs —
// no more Java-side rect polling. The renderer subscribes to each
// node and pushes its getBoundingClientRect on every layout tick back
// to the browser, which updates the WndProc subclass cache. Payload
// (after the 4-byte window id prefix):
//   [count:u32] × { code:u32, nodeId:i32 }
// `code` is the Win32 HT* value (HTMINBUTTON=8, HTMAXBUTTON=9,
// HTCLOSE=20, HTCAPTION=2, ...). An empty array clears. HTCAPTION
// entries materialize as dedicated child-HWND overlays that forward
// caption clicks (drag, dblclk-max, rclick-sysmenu) to the parent.
inline constexpr uint32_t kSetHitSpotNodes = 0x0079;

// ── DOM listener management (0x0080–0x008F) ──────────────────────────

inline constexpr uint32_t kAddEventListener = 0x0080;
inline constexpr uint32_t kRemoveEventListener = 0x0081;

// ── DOM manipulation (0x00A0–0x00BF) ─────────────────────────────────
//
// All DOM mutation commands carry the target node id (engine-assigned,
// allocated by Java for new elements or assigned by RequestDomTree for
// existing ones). windowId prefix is prepended by the bridge.

// Create a new element with a pre-allocated node id.
// Payload: [windowId:4][nodeId:4][tagLen:2][utf8Tag:N]
inline constexpr uint32_t kCreateElement = 0x00A0;
// Remove an element (detaches from parent and destroys).
// Payload: [windowId:4][nodeId:4]
inline constexpr uint32_t kRemoveElement = 0x00A1;
// Set or replace an attribute.
// Payload: [windowId:4][nodeId:4][nameLen:2][utf8Name:N][valueLen:2][utf8Value:N]
inline constexpr uint32_t kSetAttribute = 0x00A2;
// Remove an attribute.
// Payload: [windowId:4][nodeId:4][nameLen:2][utf8Name:N]
inline constexpr uint32_t kRemoveAttribute = 0x00A3;
// Append a child node to a parent.
// Payload: [windowId:4][parentId:4][childId:4]
inline constexpr uint32_t kAppendChild = 0x00A4;
// Insert a child before a reference child.
// Payload: [windowId:4][parentId:4][childId:4][refId:4]
// (refId=0 means append to the end.)
inline constexpr uint32_t kInsertBefore = 0x00A5;
// Remove a child from its parent.
// Payload: [windowId:4][parentId:4][childId:4]
inline constexpr uint32_t kRemoveChild = 0x00A6;
// Replace the text content of a node.
// Payload: [windowId:4][nodeId:4][textLen:4][utf8Text:N]
inline constexpr uint32_t kSetTextContent = 0x00A7;
// Replace the inner HTML of an element.
// Payload: [windowId:4][nodeId:4][htmlLen:4][utf8Html:N]
inline constexpr uint32_t kSetInnerHtml = 0x00A8;
// Set or replace an inline style property.
// Payload: [windowId:4][nodeId:4][propLen:2][utf8Prop:N][valueLen:2][utf8Value:N]
inline constexpr uint32_t kSetStyleProperty = 0x00A9;
// Remove an inline style property.
// Payload: [windowId:4][nodeId:4][propLen:2][utf8Prop:N]
inline constexpr uint32_t kRemoveStyleProperty = 0x00AA;
// Add a class-name token.
// Payload: [windowId:4][nodeId:4][classLen:2][utf8Class:N]
inline constexpr uint32_t kAddClass = 0x00AB;
// Remove a class-name token.
// Payload: [windowId:4][nodeId:4][classLen:2][utf8Class:N]
inline constexpr uint32_t kRemoveClass = 0x00AC;
// Give keyboard focus to the element.
// Payload: [windowId:4][nodeId:4]
inline constexpr uint32_t kDomFocus = 0x00AD;
// Remove keyboard focus from the element.
// Payload: [windowId:4][nodeId:4]
inline constexpr uint32_t kDomBlur = 0x00AE;
// Simulate a click on the element.
// Payload: [windowId:4][nodeId:4]
inline constexpr uint32_t kDomClick = 0x00AF;

// ── Off-screen input injection (0x00C0–0x00CF) ───────────────────────
//
// skia-fx OSR: the WebView node has no OS window, so JavaFX input events
// are forwarded to the off-screen RenderWidgetHostView via these commands.
// Coordinates are device pixels relative to the page's top-left. windowId
// prefix is prepended by the bridge.

// Mouse move / button. Payload:
//   [windowId:4][type:4][x:4(f32)][y:4(f32)][button:4][clickCount:4][modifiers:4]
// type: 0=move, 1=down, 2=up. button: 0=left,1=middle,2=right.
inline constexpr uint32_t kMouseEvent = 0x00C0;
// Mouse wheel. Payload:
//   [windowId:4][x:4(f32)][y:4(f32)][deltaX:4(f32)][deltaY:4(f32)][modifiers:4]
inline constexpr uint32_t kWheelEvent = 0x00C1;
// Keyboard. Payload:
//   [windowId:4][type:4][windowsKeyCode:4][nativeKeyCode:4][modifiers:4][textLen:4][utf8Text:N]
// type: 0=keydown, 1=keyup, 2=char.
inline constexpr uint32_t kKeyEvent = 0x00C2;
// Focus gained/lost. Payload: [windowId:4][focused:4] (1=gained,0=lost).
inline constexpr uint32_t kFocusEvent = 0x00C3;
// Mouse event forwarded to the open OSR popup (page-popup) instead of the main
// frame. Same payload as kMouseEvent; (x,y) are popup-local DIP coords.
// [windowId:4][type:4][x:4(f32)][y:4(f32)][button:4][clickCount:4][modifiers:4]
inline constexpr uint32_t kPopupMouseEvent = 0x00C4;
// Wheel forwarded to the open OSR popup (scroll a long <select>/datalist list).
// Same payload as kWheelEvent; (x,y) are popup-local DIP coords.
inline constexpr uint32_t kPopupWheelEvent = 0x00C5;
// Key forwarded to the open OSR popup (arrow/Enter/Esc/type-ahead). Same payload
// as kKeyEvent.
inline constexpr uint32_t kPopupKeyEvent = 0x00C6;

// ── Dialog / chooser / permission responses (0x00D0–0x00DF) ──────────
//
// Sent by Java on the FX thread when the application answers a request the
// engine surfaced (see the 0x0430–0x0483 events). Each runs the Chromium
// continuation stashed under the matching id and resumes the suspended page.
// Mirrors CommandType.java (Java is canonical).

// [windowId:4][dialogId:4][accepted:1][textLen:4][utf8Text:N]
inline constexpr uint32_t kDialogResponse = 0x00D0;
// [windowId:4][chooserId:4][chosen:1][rgba:4]
inline constexpr uint32_t kColorChooserResponse = 0x00D1;
// [windowId:4][popupId:4][accepted:1][count:4]{[index:4]}...
inline constexpr uint32_t kSelectPopupResponse = 0x00D2;
// [windowId:4][chooserId:4][count:4]{[pathLen:4][utf8Path:N]}...  (count=0 = cancel)
inline constexpr uint32_t kFileChooserResponse = 0x00D3;
// [windowId:4][permId:4][granted:1]
inline constexpr uint32_t kPermissionResponse = 0x00D4;
// [windowId:4][authId:4][supplied:1][userLen:4][utf8User:N][passLen:4][utf8Pass:N]
inline constexpr uint32_t kAuthResponse = 0x00D5;
// [windowId:4][downloadId:4][accepted:1][pathLen:4][utf8Path:N]
inline constexpr uint32_t kDownloadResponse = 0x00D6;
// [windowId:4][downloadId:4]
inline constexpr uint32_t kDownloadCancel = 0x00D7;
// 0x00D8 (kContextMenuClosed) and 0x00DA (kContextMenuShow) retired: the context
// menu is now rendered as a JavaFX menu in the foreground process (the browser
// only fires the kContextMenuRequested signal); no engine round-trip.
// [windowId:4][fsId:4][allowed:1]
inline constexpr uint32_t kFullscreenResponse = 0x00D9;

// ── Network interception control (0x00E0–0x00EF) ─────────────────────
//
// Arm/disarm the interceptor and answer per-exchange decisions. The filter
// blob and oversize decision tails (synthetic / replacement bodies) use the
// temp-file variant. Mirrors CommandType.java (Java is canonical).

// [windowId:4][filterLen:4][filterBlob:N]
inline constexpr uint32_t kArmInterception = 0x00E0;
// [windowId:4][pathLen:4][utf8TempFilePath:N]
inline constexpr uint32_t kArmInterceptionFile = 0x00E1;
// [windowId:4]
inline constexpr uint32_t kDisarmInterception = 0x00E2;
// [windowId:4][interceptId:4][phase:1][action:1][tailLen:4][tail:N]
inline constexpr uint32_t kInterceptDecision = 0x00E3;
// [windowId:4][interceptId:4][phase:1][action:1][pathLen:4][utf8TempFilePath:N]
inline constexpr uint32_t kInterceptDecisionFile = 0x00E4;
// [windowId:4][interceptId:4][chunkSeq:4][edit:1][pathLen:4][utf8Path:N]
// edit: 0=pass, 1=replace, 2=drop. path holds the REPLACE body in a temp file
// (empty for pass/drop) — the body can exceed a ring slot.
inline constexpr uint32_t kInterceptBodyEdit = 0x00E5;

// ── Print commands (0x0090–0x009F) ───────────────────────────────────

// Requests a print of the current page. Payload: [windowId:4].
// The engine shows a native print dialog and streams the result via
// kPrintResult (or kPrintRequested for window.print() originated by
// JS — which fires first as a notification).
inline constexpr uint32_t kPrint = 0x0090;
// Requests print-to-PDF. Payload: [windowId:4][pathLen:4][utf8Path:N]. A
// non-empty path writes the PDF directly there (WebEngine.print(location)); an
// empty path pops a native "Save As" dialog first (WebEngine.print()).
inline constexpr uint32_t kPrintToPdf = 0x0091;
// Opens the interactive chrome://print preview (WebEngine.showPrintPreview()).
// Payload: [windowId:4].
inline constexpr uint32_t kShowPrintPreview = 0x0092;
// Java's answer to kSavePdfRequested: the chosen save path (empty = cancelled).
// Payload: [windowId:4][requestId:4][pathLen:4][utf8Path:N].
inline constexpr uint32_t kSavePdfResponse = 0x0093;

// ── Navigation / session history (0x00F0–0x00FF) ─────────────────────

// Navigate the session history by a signed offset relative to the current
// entry (-1 = back, +1 = forward). The engine validates (CanGoToOffset) and,
// on commit, echoes a fresh kHistoryState event. Payload:
// [windowId:4][offset:4(int32)]. Mirrors CommandType.GO_TO_OFFSET.
inline constexpr uint32_t kGoToOffset = 0x00F0;

// Restore a serialized session (URL+scroll+forms+history) into a respawned
// engine after a crash. Payload: [windowId:4][pathLen:4][utf8TempFilePath:N] —
// the blob (engine format, see kSessionState) is staged to a temp file the
// engine reads then deletes. Mirrors CommandType.RESTORE_SESSION.
inline constexpr uint32_t kRestoreSession = 0x00F1;

// ── JavaScript object interop (0x0100–0x011F) ─────────────────────────
//
// Each sync op carries a 4-byte requestId (correlated like kExecuteJs) and a
// target objId (0 = the global window). Values are tagged per jux_js_value.h.
// Results come back as kJsValue (or kJsError). Mirrors CommandType.java.
inline constexpr uint32_t kJsGetMember = 0x0100;
inline constexpr uint32_t kJsSetMember = 0x0101;
inline constexpr uint32_t kJsRemoveMember = 0x0102;
inline constexpr uint32_t kJsGetSlot = 0x0103;
inline constexpr uint32_t kJsSetSlot = 0x0104;
inline constexpr uint32_t kJsCall = 0x0105;
inline constexpr uint32_t kJsEval = 0x0106;
inline constexpr uint32_t kJsRelease = 0x0107;
// Java→engine: the Java method invoked by a host-proxy call has finished; settle
// the JS promise. Payload (after windowId): [callId:4][status:1][payload] where
// status 0 = success (payload = tagged value), 1 = error (payload = [len:4][utf8]).
inline constexpr uint32_t kJsCallbackResult = 0x0108;

// ── Window state constants (must match Java WindowState ordinals) ────

inline constexpr uint32_t kStateNormal = 0;
inline constexpr uint32_t kStateMinimized = 1;
inline constexpr uint32_t kStateMaximized = 2;
inline constexpr uint32_t kStateFullscreen = 3;

}  // namespace commands
}  // namespace jux

#endif  // JUX_COMMAND_TYPES_H_
