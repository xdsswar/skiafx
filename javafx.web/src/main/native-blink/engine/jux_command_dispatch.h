// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// Command processor — polls the Java command ring buffer and dispatches
// commands to the appropriate handlers.
//
// Uses native Win32 windowing (CreateWindowEx/WndProc) for top-level
// window management, with Chromium rendering into a child window inside
// the native parent.
//
// Command polling runs on the main thread via a manual poll-and-pump loop
// (ProcessPendingCommands + PeekMessage/DispatchMessage + Sleep).
// Window events from WndProc are translated and written to the event ring
// buffer for Java.

#ifndef JUX_COMMAND_DISPATCH_H_
#define JUX_COMMAND_DISPATCH_H_

#include <cstdint>
#include <memory>
#include <set>
#include <string>

#include "base/memory/raw_ptr.h"
#include "jux/jux_ring_buffer.h"

#if BUILDFLAG(IS_WIN)
#include <windows.h>
#endif

// Forward declarations for Chromium types used by the dispatcher.
typedef uintptr_t JuxWebContentsHandle;

namespace jux {

// Global HWND of the window owned by the sole CommandDispatcher in this
// process. Used by helpers that need to parent OS-native dialogs (file
// pickers, print dialog, etc.) to our top-level window. Set by
// CommandDispatcher::OnCreateWindow and cleared on OnDestroyWindow.
#if BUILDFLAG(IS_WIN)
extern HWND g_callback_hwnd;
#endif

// Shows the native OS print dialog parented to parent_hwnd (may be
// null) and writes a kPrintResult event to writer when the dialog
// closes. Defined in jux_command_dispatch.cc; callable from any
// component running on the browser thread (CommandDispatcher itself
// and JuxDomClientImpl::OnScriptedPrint both invoke this).
#if BUILDFLAG(IS_WIN)
void ShowNativePrintDialog(HWND parent_hwnd, EventWriter* writer,
                            uint32_t window_id);
#else
void ShowNativePrintDialog(EventWriter* writer, uint32_t window_id);
#endif

namespace ipc {
class SharedMemoryChannel;
}

// =========================================================================
// CommandDispatcher
// =========================================================================

// Polls the command ring buffer and dispatches commands to window
// management (native OS APIs) and content (Chromium APIs).
//
// Owns the native top-level window (HWND on Windows) and the Chromium
// WebContents handle. The dispatcher runs on the main thread's poll-and-
// pump loop. Chromium API calls (JuxCreateWebContents, JuxLoadURL, etc.)
// internally PostTask to the browser thread.
class CommandDispatcher {
 public:
  CommandDispatcher(CommandReader cmd_reader,
                    EventWriter* evt_writer,
                    ipc::SharedMemoryChannel* channel);
  ~CommandDispatcher();

  // Processes all pending commands from the ring buffer.
  // Called by the main thread's poll-and-pump loop.
  void ProcessPendingCommands();

  // Returns true if the command ring has unread commands. Thread-safe
  // (reads the ring's atomic positions only) — called from the background
  // command-poll thread to decide whether to wake the UI pump.
  bool HasPendingCommands() const { return cmd_reader_.Available() > 0; }

  // Returns true after CMD_DESTROY_WINDOW has been processed.
  bool shutdown_requested() const { return shutdown_requested_; }

  // Returns the native window handle (HWND on Windows).
  uintptr_t native_window() const;

  // Tracks an in-flight native file dialog (a heap JavaDialogRequest, passed as
  // void* because it's a .cc-private type) so an abandoned dialog can be
  // neutralized at teardown. ForgetFileDialog is called when the request
  // self-deletes after its listener callback fires. Both run on the UI thread.
  void TrackFileDialog(void* request);
  void ForgetFileDialog(void* request);

 private:
  // ── Command handlers ─────────────────────────────────────────────

  void OnCreateWindow(const CommandSlot& cmd);
  void OnDestroyWindow(const CommandSlot& cmd);
  void OnShow(const CommandSlot& cmd);
  void OnHide(const CommandSlot& cmd);

  void OnSetTitle(const CommandSlot& cmd);
  void OnSetSize(const CommandSlot& cmd);
  // Moves the hidden engine window's origin to the on-screen WebView position so
  // Blink's native page-popups (select/color) land over the control.
  void OnSetScreenOrigin(const CommandSlot& cmd);
  // Forwards the app's popup-override flags (select/color) to the renderer.
  void OnSetPopupOverrides(const CommandSlot& cmd);
  // Runs a Blink editor command (copy/cut/paste/select-all/undo/redo/delete).
  void OnExecEditingCommand(const CommandSlot& cmd);
  void OnSetPosition(const CommandSlot& cmd);
  void OnSetMinSize(const CommandSlot& cmd);
  void OnSetMaxSize(const CommandSlot& cmd);
  void OnSetState(const CommandSlot& cmd);
  void OnSetResizable(const CommandSlot& cmd);
  void OnSetDecorated(const CommandSlot& cmd);
  void OnSetAlwaysOnTop(const CommandSlot& cmd);
  void OnSetBackground(const CommandSlot& cmd);

  // Custom chrome commands. Install the HWND subclass (jux_chrome_subclass)
  // and feed it state from Java.
  void OnSetCustomChrome(const CommandSlot& cmd);
  void OnSetTitleBarHeight(const CommandSlot& cmd);
  void OnSetHitSpots(const CommandSlot& cmd);
  void OnSetHitSpotNodes(const CommandSlot& cmd);

  void OnLoadHtml(const CommandSlot& cmd);
  void OnLoadUrl(const CommandSlot& cmd);
  // Navigate session history by a signed offset (back/forward) → JuxGoToOffset.
  void OnGoToOffset(const CommandSlot& cmd);
  // Restore a serialized session (crash recovery) → JuxRestoreSession. Reads the
  // staged temp file named in the payload, then deletes it.
  void OnRestoreSession(const CommandSlot& cmd);
  void OnSetUserAgent(const CommandSlot& cmd);
  void OnExecuteJs(const CommandSlot& cmd);
  void OnExecuteJsFile(const CommandSlot& cmd);

  // JSObject operations (0x0100–0x0107). Each parses the command and forwards
  // to the matching JuxJs* C API, which drives the renderer's V8 object table.
  void OnJsGetMember(const CommandSlot& cmd);
  void OnJsSetMember(const CommandSlot& cmd);
  void OnJsRemoveMember(const CommandSlot& cmd);
  void OnJsGetSlot(const CommandSlot& cmd);
  void OnJsSetSlot(const CommandSlot& cmd);
  void OnJsCall(const CommandSlot& cmd);
  void OnJsEval(const CommandSlot& cmd);
  void OnJsRelease(const CommandSlot& cmd);
  void OnJsCallbackResult(const CommandSlot& cmd);
  void OnLoadResources(const CommandSlot& cmd);
  void OnOpenDevTools(const CommandSlot& cmd);
  void OnCloseDevTools(const CommandSlot& cmd);
  void OnAddStylesheet(const CommandSlot& cmd);
  void OnRemoveStylesheet(const CommandSlot& cmd);

  void OnRequestFocus(const CommandSlot& cmd);
  void OnCenter(const CommandSlot& cmd);

  void OnSetCursor(const CommandSlot& cmd);

  void OnAddEventListener(const CommandSlot& cmd);
  void OnRemoveEventListener(const CommandSlot& cmd);

  // DOM manipulation commands (0x00A0–0x00BF). Each parses the payload
  // and calls the matching JuxXxx C API, which funnels through Mojo to
  // JuxDomHandlerImpl on the renderer.
  void OnCreateElement(const CommandSlot& cmd);
  void OnRemoveElement(const CommandSlot& cmd);
  void OnSetAttribute(const CommandSlot& cmd);
  void OnRemoveAttributeCmd(const CommandSlot& cmd);
  void OnAppendChild(const CommandSlot& cmd);
  void OnInsertBefore(const CommandSlot& cmd);
  void OnRemoveChild(const CommandSlot& cmd);
  void OnSetTextContentCmd(const CommandSlot& cmd);
  void OnSetInnerHtmlCmd(const CommandSlot& cmd);
  void OnSetStylePropertyCmd(const CommandSlot& cmd);
  void OnRemoveStylePropertyCmd(const CommandSlot& cmd);
  void OnAddClassCmd(const CommandSlot& cmd);
  void OnRemoveClassCmd(const CommandSlot& cmd);
  void OnDomFocusCmd(const CommandSlot& cmd);
  void OnDomBlurCmd(const CommandSlot& cmd);
  void OnDomClickCmd(const CommandSlot& cmd);

  // Dialog commands (0x0050–0x0052). Each shows a native OS file dialog
  // and writes the result back as a DIALOG_*_RESULT event.
  void OnShowOpenDialog(const CommandSlot& cmd);
  void OnShowSaveDialog(const CommandSlot& cmd);
  void OnShowDirDialog(const CommandSlot& cmd);

  // JS dialog response (0x00D0). Resumes a suspended alert/confirm/prompt/
  // beforeunload by running the stashed JuxJsDialogManager continuation.
  void OnDialogResponse(const CommandSlot& cmd);

  // Fullscreen response (0x00D9). A denial of an entry exits fullscreen.
  void OnFullscreenResponse(const CommandSlot& cmd);

  // Permission response (0x00D4). Delivers grant/deny to the permission manager.
  void OnPermissionResponse(const CommandSlot& cmd);

  // Auth response (0x00D5). Supplies/cancels HTTP auth credentials.
  void OnAuthResponse(const CommandSlot& cmd);

  // Download response (0x00D6) / cancel (0x00D7) → JuxDownloadManagerDelegate.
  void OnDownloadResponse(const CommandSlot& cmd);
  void OnDownloadCancel(const CommandSlot& cmd);

  // Select-popup response (0x00D2) → renderer JuxDomHandler.SelectPopupResponse.
  void OnSelectPopupResponse(const CommandSlot& cmd);

  // Color-chooser response (0x00D1) → renderer JuxDomHandler.ColorChooserResponse.
  void OnColorChooserResponse(const CommandSlot& cmd);

  // File-chooser response (0x00D3) → JuxWebContentsDelegate::RespondFileChooser.
  void OnFileChooserResponse(const CommandSlot& cmd);

  // Network interception control (0x00E0–0x00E5) → JuxNetworkInterceptor.
  void OnArmInterception(const CommandSlot& cmd);
  void OnArmInterceptionFile(const CommandSlot& cmd);
  void OnDisarmInterception(const CommandSlot& cmd);
  void OnInterceptDecision(const CommandSlot& cmd);
  void OnInterceptDecisionFile(const CommandSlot& cmd);
  void OnInterceptBodyEdit(const CommandSlot& cmd);

  // Window icons (0x0020). Reads a PNG file from the path in the payload
  // and applies it as the window's icon via WM_SETICON on Windows.
  void OnSetIcons(const CommandSlot& cmd);

  // Print commands (0x0090–0x009F).
  void OnPrint(const CommandSlot& cmd);
  void OnPrintToPdf(const CommandSlot& cmd);
  void OnShowPrintPreview(const CommandSlot& cmd);
  void OnSavePdfResponse(const CommandSlot& cmd);

  // Off-screen input injection (forwarded to the RenderWidgetHost).
  void OnMouseEvent(const CommandSlot& cmd);
  // Forwards mouse/wheel/key to the open OSR popup instead of the main frame.
  void OnPopupMouseEvent(const CommandSlot& cmd);
  void OnPopupWheelEvent(const CommandSlot& cmd);
  void OnPopupKeyEvent(const CommandSlot& cmd);
  void OnWheelEvent(const CommandSlot& cmd);
  void OnKeyEvent(const CommandSlot& cmd);
  void OnFocusEvent(const CommandSlot& cmd);

  // ── State ────────────────────────────────────────────────────────

  // Neutralizes every still-open file dialog at teardown: clears each request's
  // writer + owner so its eventual (or never-arriving) listener callback no-ops
  // and self-deletes cleanly instead of touching this destroyed dispatcher.
  void NeutralizeOpenFileDialogs();

  CommandReader cmd_reader_;
  raw_ptr<EventWriter> evt_writer_;             // Not owned — lifetime managed by caller.
  raw_ptr<ipc::SharedMemoryChannel> channel_;   // Not owned.

  // In-flight native file dialogs (JavaDialogRequest*, kept as void* — the type
  // is private to the .cc). Inserted in ShowDialog, erased on self-delete,
  // neutralized in the dtor. Not owned (each request owns itself + its dialog).
  std::set<void*> open_file_dialogs_;

  // Native window handle (the aura host's HWND, not owned by us).
  // Used for Win32 window management (ShowWindow, SetWindowText, etc.).
#if BUILDFLAG(IS_WIN)
  HWND hwnd_ = nullptr;
  // Current window icons, destroyed when replaced. 32x32 for ICON_BIG and
  // 16x16 for ICON_SMALL — they can be the same resource scaled.
  HICON big_icon_ = nullptr;
  HICON small_icon_ = nullptr;
#endif

  // Chromium WebContents handle (0 if not yet created).
  JuxWebContentsHandle web_contents_handle_ = 0;

  // Window property tracking.
  double min_width_ = 0, min_height_ = 0;
  double max_width_ = 0, max_height_ = 0;
  bool has_min_size_ = false;
  bool has_max_size_ = false;

  // Whether shutdown has been requested (CMD_DESTROY_WINDOW received).
  bool shutdown_requested_ = false;

  // Next stylesheet ID counter.
  uint32_t next_stylesheet_id_ = 1;
};

}  // namespace jux

#endif  // JUX_COMMAND_DISPATCH_H_
