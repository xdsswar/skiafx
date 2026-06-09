// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// Implementation of the command dispatcher.
//
// Polls the Java command ring buffer, dispatches commands to native
// window APIs (Win32) and Chromium content APIs. Window events from
// WndProc are translated and written to the event ring buffer.

#ifdef UNSAFE_BUFFERS_BUILD
#pragma allow_unsafe_buffers
#endif

#include "jux/jux_command_dispatch.h"

#include <atomic>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <memory>
#include <sstream>
#include <utility>
#include <vector>

#include "base/bit_cast.h"
#include "base/files/file_path.h"
#include "base/files/file_util.h"
#include "base/functional/callback.h"
#include "base/logging.h"
#include "base/no_destructor.h"
#include "base/strings/utf_string_conversions.h"
#include "jux/jux_chrome_subclass.h"
#include "jux/jux_command_types.h"
#include "jux/print_preview/shim/jux_print_preview_hook.h"
#include "jux/jux_event_types.h"
#include "jux/jux_ipc.h"
#include "jux/jux_engine_api.h"
#include "jux/jux_network_interceptor.h"
// skia-fx: PDF + printing dropped for size (enable_pdf=false; on Windows the
// printing stack requires PDF). The print code below is guarded by
// SFXWEB_ENABLE_PRINTING (never defined). Re-enable by defining the macro,
// adding "//printing" back to the engine BUILD.gn deps, and building with
// -Pchromium.feature.pdf=true -Pchromium.feature.printing=true.
#if defined(SFXWEB_ENABLE_PRINTING)
#include "printing/buildflags/buildflags.h"
#include "printing/mojom/print.mojom.h"
#include "printing/printing_context.h"
#endif  // SFXWEB_ENABLE_PRINTING
#include "ui/shell_dialogs/select_file_dialog.h"
#include "ui/shell_dialogs/select_file_policy.h"
#include "ui/shell_dialogs/selected_file_info.h"

#if BUILDFLAG(IS_WIN)
#include <dwmapi.h>
#include <shellscalingapi.h>
#include <wincodec.h>
#include <wrl/client.h>
#include "ui/views/widget/desktop_aura/desktop_window_tree_host_win.h"
#pragma comment(lib, "dwmapi.lib")
#pragma comment(lib, "windowscodecs.lib")
#endif

namespace jux {

// Global state for JuxCallbacks → event ring buffer bridge.
// Set by CommandDispatcher::OnCreateWindow before creating WebContents.
// Declared extern in jux_engine_api.cc for use by CreateWebContentsOnUI
// (JuxWidgetDelegate and JuxWidgetObserver need these pointers).
EventWriter* g_callback_evt_writer = nullptr;
ipc::SharedMemoryChannel* g_callback_channel = nullptr;

// Pending print-to-PDF "save location" requests. jux::ShowSavePdfDialog (called
// from PdfPrinterHandler / JuxPrintToPdf) emits kSavePdfRequested to Java and
// parks its completion callback here; OnSavePdfResponse fires it with the chosen
// path once Java's JavaFX FileChooser answers. Keyed by a monotonic request id.
base::NoDestructor<std::map<uint32_t, base::OnceCallback<void(base::FilePath)>>>
    g_pending_pdf_saves;
uint32_t g_next_pdf_save_id = 1;

#if BUILDFLAG(IS_WIN)
// Native HWND for the top-level window owned by the command dispatcher.
// Set by CommandDispatcher::OnCreateWindow once the Widget has been
// realized. Used by free helpers like the native print dialog so they
// can parent themselves to our window (for modality and icon
// inheritance).
HWND g_callback_hwnd = nullptr;

// WebContents handle for the primary window, in lockstep with
// g_callback_hwnd. Exposed so free helpers on the browser UI thread
// (chrome-subclass overlay WndProc) can reach the per-WebContents
// JuxDomHandler Mojo remote without plumbing the handle through every
// Win32 entry point.
JuxWebContentsHandle g_callback_web_contents = 0;
#endif

namespace {

// Writes a little-endian uint32 into a payload buffer at the given offset.
void PutU32(uint8_t* buf, size_t offset, uint32_t value) {
  memcpy(buf + offset, &value, sizeof(value));
}

// Writes a little-endian double into a payload buffer at the given offset.
void PutF64(uint8_t* buf, size_t offset, double value) {
  memcpy(buf + offset, &value, sizeof(value));
}

// Callback: web page requested close (window.close() in JavaScript).
// The X button close is now handled by JuxWidgetDelegate::OnCloseRequested.
// This callback fires via WebContentsDelegate::CloseContents for script-
// initiated close requests.
void OnCloseRequested(JuxWebContentsHandle handle) {
  if (g_callback_evt_writer && g_callback_channel) {
    g_callback_evt_writer->WriteEvent(
        events::kWindowCloseRequest, g_callback_channel->window_id());
  }
  // Do NOT quit the message loop here. Wait for Java to send
  // CMD_DESTROY_WINDOW, which triggers shutdown_requested_ in the
  // command dispatcher.
}

// Callback: document title changed (fires on browser thread).
void OnTitleChanged(JuxWebContentsHandle handle,
                    const char* title, uint32_t title_len) {
  if (g_callback_evt_writer && g_callback_channel) {
    uint32_t wid = g_callback_channel->window_id();
    // Build payload: [windowId:4][titleLen:4][utf8:N]
    std::vector<uint8_t> payload(4 + title_len);
    PutU32(payload.data(), 0, title_len);
    if (title_len > 0) {
      memcpy(payload.data() + 4, title, title_len);
    }
    g_callback_evt_writer->WriteEvent(
        events::kDocTitleChanged, wid,
        base::span<const uint8_t>(payload.data(), payload.size()));
  }
}

// Callback: page load status changed (fires on browser thread).
void OnLoadStatusChanged(JuxWebContentsHandle handle, uint32_t status) {
  if (g_callback_evt_writer && g_callback_channel) {
    uint32_t wid = g_callback_channel->window_id();
    // Map status:
    //   0=loading, 1=interactive, 2=content_loaded, 3=ready,
    //   4=ready_to_show (first of FirstVisuallyNonEmptyPaint /
    //                    DidStopLoading / DidFailLoad; one-shot).
    static constexpr uint32_t status_to_event[] = {
        events::kDocLoading,
        events::kDocInteractive,
        events::kDocContentLoaded,
        events::kDocReady,
        events::kDocReadyToShow,
    };
    if (status < 5) {
      // WriteEvent returns false (writes nothing) when the ring is full; a lost
      // kDocReady would leave the Java LoadWorker stuck in RUNNING. Warn ONCE
      // per process so the stall is diagnosable without spamming the console if
      // the ring stays full (the Java side also force-completes on
      // DidStopLoading, so this is a diagnostic, not the recovery path).
      if (!g_callback_evt_writer->WriteEvent(status_to_event[status], wid)) {
        static bool warned_once = false;
        if (!warned_once) {
          warned_once = true;
          LOG(WARNING) << "[jux] a load-status event was dropped (event ring "
                          "full) — Java load state may stall (logged once)";
        }
      }
    }
  }
}

// Callback: URL changed after navigation committed (fires on browser thread).
void OnURLChanged(JuxWebContentsHandle handle,
                  const char* url, uint32_t url_len) {
  if (g_callback_evt_writer && g_callback_channel) {
    uint32_t wid = g_callback_channel->window_id();
    // Build payload: [urlLen:4][utf8:N]
    std::vector<uint8_t> payload(4 + url_len);
    PutU32(payload.data(), 0, url_len);
    if (url_len > 0) {
      memcpy(payload.data() + 4, url, url_len);
    }
    g_callback_evt_writer->WriteEvent(
        events::kDocNavigation, wid,
        base::span<const uint8_t>(payload.data(), payload.size()));
  }
}

// Callback: session history changed after a committed navigation (browser
// thread). `data`/`len` is the already-serialized kHistoryState payload (after
// windowId): [currentIndex:4][count:4]{[urlLen:4][url][titleLen:4][title]}…. It
// can exceed one slot (long URLs), so it goes out via WriteEventLarge, which
// Java's EventRingBuffer reassembles.
void OnHistoryChanged(JuxWebContentsHandle handle, const uint8_t* data,
                      uint32_t len) {
  if (g_callback_evt_writer && g_callback_channel) {
    g_callback_evt_writer->WriteEventLarge(
        events::kHistoryState, g_callback_channel->window_id(),
        base::span<const uint8_t>(data, static_cast<size_t>(len)));
  }
}

// Callback: serialized session snapshot for crash recovery (browser thread).
// `data` is the opaque SerializeSession() blob; can exceed one slot → ships via
// WriteEventLarge as kSessionState. Java stores it and replays via kRestoreSession.
void OnSessionState(JuxWebContentsHandle handle, const uint8_t* data,
                    uint32_t len) {
  if (g_callback_evt_writer && g_callback_channel) {
    g_callback_evt_writer->WriteEventLarge(
        events::kSessionState, g_callback_channel->window_id(),
        base::span<const uint8_t>(data, static_cast<size_t>(len)));
  }
}

// Callback: typed result of executeScript / a JSObject op (browser thread).
// `value` is tagged bytes (jux_js_value.h). Emits kJsValue keyed by request_id;
// can exceed one slot (large strings/objects), so WriteEventLarge.
void OnJsValue(JuxWebContentsHandle handle, uint32_t request_id,
               const uint8_t* value, uint32_t value_len) {
  if (g_callback_evt_writer && g_callback_channel) {
    uint32_t wid = g_callback_channel->window_id();
    // Payload (after windowId): [requestId:4][value tagged bytes].
    std::vector<uint8_t> p(4 + value_len);
    PutU32(p.data(), 0, request_id);
    if (value_len > 0) {
      memcpy(p.data() + 4, value, value_len);
    }
    g_callback_evt_writer->WriteEventLarge(
        events::kJsValue, wid,
        base::span<const uint8_t>(p.data(), p.size()));
  }
}

// Callback: renderer process crashed or was killed (fires on browser thread).
void OnRenderProcessGone(JuxWebContentsHandle handle, int status) {
  if (g_callback_evt_writer && g_callback_channel) {
    uint32_t wid = g_callback_channel->window_id();
    // Build payload: [terminationStatus:4]
    uint8_t payload[4];
    PutU32(payload, 0, static_cast<uint32_t>(status));
    g_callback_evt_writer->WriteEvent(
        events::kRenderProcessGone, wid,
        base::span<const uint8_t>(payload, sizeof(payload)));
  }
}

// Callback: page JS opened a dialog. The engine has stashed Chromium's
// continuation (JuxJsDialogManager) and suspended the page; this just notifies
// Java, which answers via kDialogResponse. Fires on the browser thread.
void OnDialogRequested(JuxWebContentsHandle handle, uint32_t dialog_id,
                       uint32_t dialog_type, const char* message,
                       uint32_t message_len, const char* default_text,
                       uint32_t default_len) {
  if (!g_callback_evt_writer || !g_callback_channel) {
    return;
  }
  uint32_t wid = g_callback_channel->window_id();
  // Payload (after windowId): [dialogId:4][dialogType:4][msgLen:4][msg]
  //                           [defLen:4][def]
  size_t total = 4 + 4 + 4 + message_len + 4 + default_len;
  std::vector<uint8_t> p(total);
  size_t off = 0;
  PutU32(p.data(), off, dialog_id);                 off += 4;
  PutU32(p.data(), off, dialog_type);               off += 4;
  PutU32(p.data(), off, message_len);               off += 4;
  if (message_len > 0) {
    memcpy(p.data() + off, message, message_len);   off += message_len;
  }
  PutU32(p.data(), off, default_len);               off += 4;
  if (default_len > 0) {
    memcpy(p.data() + off, default_text, default_len);
  }
  g_callback_evt_writer->WriteEvent(
      events::kDialogRequested, wid,
      base::span<const uint8_t>(p.data(), p.size()));
}

// Engine implementation of jux::ShowSavePdfDialog (registered as the hook in
// OnCreateWindow). Emits kSavePdfRequested so Java shows a cross-platform
// FileChooser owned by the WebView's Stage — our WebContents are off-screen (OSR)
// and have no on-screen window for a native dialog to parent to.
void SavePdfDialogHookImpl(const std::u16string& default_name,
                           base::OnceCallback<void(base::FilePath)> on_done) {
  if (!g_callback_evt_writer || !g_callback_channel) {
    std::move(on_done).Run(base::FilePath());
    return;
  }
  const uint32_t id = g_next_pdf_save_id++;
  (*g_pending_pdf_saves)[id] = std::move(on_done);
  const std::string name = base::UTF16ToUTF8(default_name);
  const uint32_t wid = g_callback_channel->window_id();
  // Payload (after windowId): [requestId:4][nameLen:4][utf8Name:N]
  std::vector<uint8_t> p(4 + 4 + name.size());
  size_t off = 0;
  PutU32(p.data(), off, id);                                  off += 4;
  PutU32(p.data(), off, static_cast<uint32_t>(name.size()));  off += 4;
  if (!name.empty()) {
    memcpy(p.data() + off, name.data(), name.size());
  }
  g_callback_evt_writer->WriteEvent(
      events::kSavePdfRequested, wid,
      base::span<const uint8_t>(p.data(), p.size()));
}

// Callback: page entered/left fullscreen. Fires on the browser thread.
void OnFullscreenRequested(JuxWebContentsHandle handle, uint32_t fs_id,
                           uint8_t entering) {
  if (!g_callback_evt_writer || !g_callback_channel) {
    return;
  }
  uint32_t wid = g_callback_channel->window_id();
  // Payload (after windowId): [fsId:4][entering:1]
  uint8_t p[5];
  PutU32(p, 0, fs_id);
  p[4] = entering;
  g_callback_evt_writer->WriteEvent(
      events::kFullscreenRequested, wid,
      base::span<const uint8_t>(p, sizeof(p)));
}

// Callback: favicon URL changed. Fires on the browser thread.
void OnFaviconChanged(JuxWebContentsHandle handle, const char* url,
                      uint32_t url_len) {
  if (!g_callback_evt_writer || !g_callback_channel) {
    return;
  }
  uint32_t wid = g_callback_channel->window_id();
  // Payload (after windowId): [urlLen:4][url]
  std::vector<uint8_t> p(4 + url_len);
  PutU32(p.data(), 0, url_len);
  if (url_len > 0) {
    memcpy(p.data() + 4, url, url_len);
  }
  g_callback_evt_writer->WriteEvent(
      events::kFaviconChanged, wid,
      base::span<const uint8_t>(p.data(), p.size()));
}

// -------------------------------------------------------------------------
// DOM tree sync callbacks. Invoked from the browser side of the DOM Mojo
// pipe as the renderer reports each element/text node during a walk,
// followed by on_dom_tree_ready to signal completion.
// -------------------------------------------------------------------------

// Callback: a DOM element was walked (fires on browser thread).
//
// Payload written to the ring buffer:
//   [windowId:4]  <-- prepended by EventWriter
//   [nodeId:4][parentId:4]
//   [tagLen:1][tag:N]
//   [idLen:1][id:N]
//   [classLen:2][class:N]
void OnDomElement(JuxWebContentsHandle handle,
                  int64_t node_id, int64_t parent_id,
                  const char* tag, uint32_t tag_len,
                  const char* id_attr, uint32_t id_len,
                  const char* class_attr, uint32_t class_len) {
  if (!g_callback_evt_writer || !g_callback_channel) return;
  uint32_t wid = g_callback_channel->window_id();

  // Clamp length-prefix fields to fit in their sizes on the wire.
  uint8_t tag_len_u8 = tag_len > 255 ? 255 : static_cast<uint8_t>(tag_len);
  uint8_t id_len_u8 = id_len > 255 ? 255 : static_cast<uint8_t>(id_len);
  uint16_t class_len_u16 =
      class_len > 65535 ? 65535 : static_cast<uint16_t>(class_len);

  std::vector<uint8_t> payload;
  payload.reserve(4 + 4 + 1 + tag_len_u8 + 1 + id_len_u8 + 2 + class_len_u16);
  // nodeId, parentId (stored as little-endian 32-bit truncations).
  uint32_t node32 = static_cast<uint32_t>(node_id & 0xFFFFFFFF);
  uint32_t parent32 = static_cast<uint32_t>(parent_id & 0xFFFFFFFF);
  payload.resize(8);
  PutU32(payload.data(), 0, node32);
  PutU32(payload.data(), 4, parent32);

  // tag (u8 length prefix)
  payload.push_back(tag_len_u8);
  payload.insert(payload.end(), tag, tag + tag_len_u8);

  // id (u8 length prefix)
  payload.push_back(id_len_u8);
  payload.insert(payload.end(), id_attr, id_attr + id_len_u8);

  // class (u16 length prefix, little-endian)
  payload.push_back(static_cast<uint8_t>(class_len_u16 & 0xFF));
  payload.push_back(static_cast<uint8_t>((class_len_u16 >> 8) & 0xFF));
  payload.insert(payload.end(), class_attr, class_attr + class_len_u16);

  g_callback_evt_writer->WriteEvent(
      events::kDomElement, wid,
      base::span<const uint8_t>(payload));
}

// Callback: a DOM text node was walked. Payload (after windowId):
//   [nodeId:4][parentId:4][textLen:2][utf8Text:N]
void OnDomText(JuxWebContentsHandle handle,
               int64_t node_id, int64_t parent_id,
               const char* text, uint32_t text_len) {
  if (!g_callback_evt_writer || !g_callback_channel) return;
  uint32_t wid = g_callback_channel->window_id();

  uint16_t text_len_u16 =
      text_len > 65535 ? 65535 : static_cast<uint16_t>(text_len);
  std::vector<uint8_t> payload(4 + 4 + 2 + text_len_u16);
  uint32_t node32 = static_cast<uint32_t>(node_id & 0xFFFFFFFF);
  uint32_t parent32 = static_cast<uint32_t>(parent_id & 0xFFFFFFFF);
  PutU32(payload.data(), 0, node32);
  PutU32(payload.data(), 4, parent32);
  payload[8] = static_cast<uint8_t>(text_len_u16 & 0xFF);
  payload[9] = static_cast<uint8_t>((text_len_u16 >> 8) & 0xFF);
  if (text_len_u16 > 0) {
    memcpy(payload.data() + 10, text, text_len_u16);
  }

  g_callback_evt_writer->WriteEvent(
      events::kDomText, wid,
      base::span<const uint8_t>(payload));
}

// Callback: the renderer finished walking the DOM tree (no payload other
// than the windowId prepended by EventWriter).
void OnDomTreeReady(JuxWebContentsHandle handle) {
  VLOG(1) << "[jux-dom] OnDomTreeReady firing to Java (h=" << handle << ")";
  if (!g_callback_evt_writer || !g_callback_channel) {
    LOG(WARNING) << "[jux-dom] OnDomTreeReady: writer/channel null, DROPPED";
    return;
  }
  bool ok = g_callback_evt_writer->WriteEvent(
      events::kDomTreeReady, g_callback_channel->window_id());
  if (!ok) {
    LOG(WARNING) << "[jux-dom] OnDomTreeReady: ring write failed — Java "
                 << "will never get DOM_TREE_READY";
  }
}

// Callback: a DOM event fired on a node that Java registered a listener
// for. The payload is the raw bytes produced by the renderer's event
// serializer; EventDispatchLoop already understands the layout per
// event_type.
//
// We don't know the exact event-type constant at this layer, so the
// renderer has already encoded it. On this side we just write the raw
// bytes and let the receiver interpret. However to preserve the existing
// per-type dispatch on the Java side, we need the specific kDom* value.
// JuxDomClientImpl already writes these events itself via its Mojo
// receiver, so this global callback is a fallback for any non-Mojo
// path (currently unused — safe to leave as a stub).
void OnDomEvent(JuxWebContentsHandle handle,
                int64_t node_id,
                const char* /*event_type*/, uint32_t /*event_type_len*/,
                const uint8_t* /*payload*/, uint32_t /*payload_len*/) {
  // Intentionally empty: DOM events are delivered through
  // JuxDomClientImpl's Mojo receiver which writes directly to the event
  // ring. This global callback is kept for API completeness.
  (void)handle;
  (void)node_id;
}

// Callback: synchronous JS execution returned a result. Payload:
//   [requestId:4][resultLen:4][utf8Result:N]
void OnJSResult(JuxWebContentsHandle handle, uint32_t request_id,
                const char* result, uint32_t result_len) {
  if (!g_callback_evt_writer || !g_callback_channel) return;
  uint32_t wid = g_callback_channel->window_id();
  std::vector<uint8_t> payload(4 + 4 + result_len);
  PutU32(payload.data(), 0, request_id);
  PutU32(payload.data(), 4, result_len);
  if (result_len > 0) {
    memcpy(payload.data() + 8, result, result_len);
  }
  g_callback_evt_writer->WriteEvent(
      events::kJsResult, wid, base::span<const uint8_t>(payload));
}

// Callback: page load failed. Payload (after windowId prepended):
//   [errorCode:4][urlLen:4][utf8Url:N][descLen:4][utf8Desc:N]
void OnLoadError(JuxWebContentsHandle handle, int32_t error_code,
                 const char* url, uint32_t url_len,
                 const char* description, uint32_t description_len) {
  if (!g_callback_evt_writer || !g_callback_channel) return;
  uint32_t wid = g_callback_channel->window_id();

  std::vector<uint8_t> payload(4 + 4 + url_len + 4 + description_len);
  size_t off = 0;
  PutU32(payload.data(), off, static_cast<uint32_t>(error_code)); off += 4;
  PutU32(payload.data(), off, url_len); off += 4;
  if (url_len > 0) {
    memcpy(payload.data() + off, url, url_len);
    off += url_len;
  }
  PutU32(payload.data(), off, description_len); off += 4;
  if (description_len > 0) {
    memcpy(payload.data() + off, description, description_len);
  }

  g_callback_evt_writer->WriteEvent(
      events::kLoadError, wid,
      base::span<const uint8_t>(payload));
}

// Callback: console message or JS uncaught exception. Payload (after
// windowId): [level:4][lineNumber:4][msgLen:4][utf8Msg:N][srcLen:4][utf8Src:N]
void OnConsoleMessage(JuxWebContentsHandle handle, uint32_t level,
                      uint32_t line_number,
                      const char* message, uint32_t message_len,
                      const char* source_id, uint32_t source_id_len) {
  if (!g_callback_evt_writer || !g_callback_channel) return;
  uint32_t wid = g_callback_channel->window_id();

  std::vector<uint8_t> payload(4 + 4 + 4 + message_len + 4 + source_id_len);
  size_t off = 0;
  PutU32(payload.data(), off, level); off += 4;
  PutU32(payload.data(), off, line_number); off += 4;
  PutU32(payload.data(), off, message_len); off += 4;
  if (message_len > 0) {
    memcpy(payload.data() + off, message, message_len);
    off += message_len;
  }
  PutU32(payload.data(), off, source_id_len); off += 4;
  if (source_id_len > 0) {
    memcpy(payload.data() + off, source_id, source_id_len);
  }

  g_callback_evt_writer->WriteEvent(
      events::kConsoleMessage, wid,
      base::span<const uint8_t>(payload));
}

// Callback: synchronous JS execution threw. Payload:
//   [requestId:4][errorLen:4][utf8Error:N]
void OnJSError(JuxWebContentsHandle handle, uint32_t request_id,
               const char* error, uint32_t error_len) {
  if (!g_callback_evt_writer || !g_callback_channel) return;
  uint32_t wid = g_callback_channel->window_id();
  std::vector<uint8_t> payload(4 + 4 + error_len);
  PutU32(payload.data(), 0, request_id);
  PutU32(payload.data(), 4, error_len);
  if (error_len > 0) {
    memcpy(payload.data() + 8, error, error_len);
  }
  g_callback_evt_writer->WriteEvent(
      events::kJsError, wid, base::span<const uint8_t>(payload));
}

}  // namespace

// =========================================================================
// Constructor / Destructor
// =========================================================================

CommandDispatcher::CommandDispatcher(CommandReader cmd_reader,
                                     EventWriter* evt_writer,
                                     ipc::SharedMemoryChannel* channel)
    : cmd_reader_(std::move(cmd_reader)),
      evt_writer_(evt_writer),
      channel_(channel) {}

CommandDispatcher::~CommandDispatcher() {
  // Neutralize any still-open native file dialog so its eventual (or
  // never-arriving) listener callback no-ops and self-deletes instead of
  // writing through our now-dangling EventWriter.
  NeutralizeOpenFileDialogs();
  // Null the JuxCallbacks → event-ring globals BEFORE our owner frees
  // evt_writer_/channel_ (TeardownIPC does dispatcher_.reset() first, then
  // evt_writer_.reset()/channel_=nullptr). Other callback paths can still
  // fire after this dtor — a delayed EmitReadyToShow, the periodic capture
  // tick, the session timer, or a RequestDomTree Mojo reply — and they all
  // dereference these globals. They guard on (g_callback_evt_writer &&
  // g_callback_channel), so nulling here turns a late firing into a safe
  // no-op instead of a use-after-free on the freed EventWriter/channel.
  g_callback_evt_writer = nullptr;
  g_callback_channel = nullptr;
#if BUILDFLAG(IS_WIN)
  // WM_SETICON does not transfer HICON ownership and Windows does not destroy
  // these on window close; the replace paths free prior handles, so free the
  // final pair here to avoid a per-process GDI leak.
  if (big_icon_) {
    DestroyIcon(big_icon_);
    big_icon_ = nullptr;
  }
  if (small_icon_) {
    DestroyIcon(small_icon_);
    small_icon_ = nullptr;
  }
#endif
  // Don't destroy the HWND — Chromium owns the aura host window.
  hwnd_ = nullptr;
}

void CommandDispatcher::TrackFileDialog(void* request) {
  open_file_dialogs_.insert(request);
}

void CommandDispatcher::ForgetFileDialog(void* request) {
  open_file_dialogs_.erase(request);
}

uintptr_t CommandDispatcher::native_window() const {
#if BUILDFLAG(IS_WIN)
  return reinterpret_cast<uintptr_t>(hwnd_);
#else
  return 0;
#endif
}

// =========================================================================
// Command Processing
// =========================================================================

void CommandDispatcher::ProcessPendingCommands() {
  while (auto opt_cmd = cmd_reader_.Poll()) {
    const CommandSlot& cmd = *opt_cmd;

    switch (cmd.cmd_type) {
      // ── Window lifecycle ───────────────────────────────
      case commands::kCreateWindow:  OnCreateWindow(cmd); break;
      case commands::kDestroyWindow: OnDestroyWindow(cmd); break;
      case commands::kShow:          OnShow(cmd); break;
      case commands::kHide:          OnHide(cmd); break;

      // ── Window properties ──────────────────────────────
      case commands::kSetTitle:      OnSetTitle(cmd); break;
      case commands::kSetSize:       OnSetSize(cmd); break;
      case commands::kSetPosition:   OnSetPosition(cmd); break;
      case commands::kSetMinSize:    OnSetMinSize(cmd); break;
      case commands::kSetMaxSize:    OnSetMaxSize(cmd); break;
      case commands::kSetState:      OnSetState(cmd); break;
      case commands::kSetResizable:  OnSetResizable(cmd); break;
      case commands::kSetDecorated:  OnSetDecorated(cmd); break;
      case commands::kSetAlwaysOnTop: OnSetAlwaysOnTop(cmd); break;

      // ── Window appearance ──────────────────────────────
      case commands::kSetCursor:     OnSetCursor(cmd); break;

      // ── DOM / Content ──────────────────────────────────
      case commands::kLoadHtml:      OnLoadHtml(cmd); break;
      case commands::kLoadUrl:       OnLoadUrl(cmd); break;
      case commands::kGoToOffset:    OnGoToOffset(cmd); break;
      case commands::kRestoreSession: OnRestoreSession(cmd); break;
      case commands::kSetUserAgent:  OnSetUserAgent(cmd); break;
      case commands::kSetScreenOrigin:   OnSetScreenOrigin(cmd); break;
      case commands::kSetPopupOverrides: OnSetPopupOverrides(cmd); break;
      case commands::kExecEditingCommand: OnExecEditingCommand(cmd); break;
      case commands::kExecuteJs:     OnExecuteJs(cmd); break;
      case commands::kExecuteJsFile: OnExecuteJsFile(cmd); break;

      // ── JSObject operations ────────────────────────────
      case commands::kJsGetMember:    OnJsGetMember(cmd); break;
      case commands::kJsSetMember:    OnJsSetMember(cmd); break;
      case commands::kJsRemoveMember: OnJsRemoveMember(cmd); break;
      case commands::kJsGetSlot:      OnJsGetSlot(cmd); break;
      case commands::kJsSetSlot:      OnJsSetSlot(cmd); break;
      case commands::kJsCall:         OnJsCall(cmd); break;
      case commands::kJsEval:         OnJsEval(cmd); break;
      case commands::kJsRelease:      OnJsRelease(cmd); break;
      case commands::kJsCallbackResult: OnJsCallbackResult(cmd); break;
      case commands::kLoadResources: OnLoadResources(cmd); break;
      case commands::kOpenDevTools:  OnOpenDevTools(cmd); break;
      case commands::kCloseDevTools: OnCloseDevTools(cmd); break;
      case commands::kAddStylesheet: OnAddStylesheet(cmd); break;
      case commands::kRemoveStylesheet: OnRemoveStylesheet(cmd); break;

      // ── Window actions ─────────────────────────────────
      case commands::kRequestFocus:  OnRequestFocus(cmd); break;
      case commands::kCenter:        OnCenter(cmd); break;

      // ── DOM listener management ────────────────────────
      case commands::kAddEventListener:    OnAddEventListener(cmd); break;
      case commands::kRemoveEventListener: OnRemoveEventListener(cmd); break;

      // ── DOM manipulation ───────────────────────────────
      case commands::kCreateElement:       OnCreateElement(cmd); break;
      case commands::kRemoveElement:       OnRemoveElement(cmd); break;
      case commands::kSetAttribute:        OnSetAttribute(cmd); break;
      case commands::kRemoveAttribute:     OnRemoveAttributeCmd(cmd); break;
      case commands::kAppendChild:         OnAppendChild(cmd); break;
      case commands::kInsertBefore:        OnInsertBefore(cmd); break;
      case commands::kRemoveChild:         OnRemoveChild(cmd); break;
      case commands::kSetTextContent:      OnSetTextContentCmd(cmd); break;
      case commands::kSetInnerHtml:        OnSetInnerHtmlCmd(cmd); break;
      case commands::kSetStyleProperty:    OnSetStylePropertyCmd(cmd); break;
      case commands::kRemoveStyleProperty: OnRemoveStylePropertyCmd(cmd); break;
      case commands::kAddClass:            OnAddClassCmd(cmd); break;
      case commands::kRemoveClass:         OnRemoveClassCmd(cmd); break;
      case commands::kDomFocus:            OnDomFocusCmd(cmd); break;
      case commands::kDomBlur:             OnDomBlurCmd(cmd); break;
      case commands::kDomClick:            OnDomClickCmd(cmd); break;

      // ── Window appearance ──────────────────────────────
      case commands::kSetIcons:            OnSetIcons(cmd); break;

      // ── File / directory dialogs ───────────────────────
      case commands::kShowOpenDialog:      OnShowOpenDialog(cmd); break;
      case commands::kShowSaveDialog:      OnShowSaveDialog(cmd); break;
      case commands::kShowDirDialog:       OnShowDirDialog(cmd); break;

      // ── Dialog / chooser responses ─────────────────────
      case commands::kDialogResponse:      OnDialogResponse(cmd); break;
      case commands::kFullscreenResponse:  OnFullscreenResponse(cmd); break;
      case commands::kPermissionResponse:  OnPermissionResponse(cmd); break;
      case commands::kAuthResponse:        OnAuthResponse(cmd); break;
      case commands::kDownloadResponse:    OnDownloadResponse(cmd); break;
      case commands::kDownloadCancel:      OnDownloadCancel(cmd); break;
      case commands::kSelectPopupResponse: OnSelectPopupResponse(cmd); break;
      case commands::kColorChooserResponse: OnColorChooserResponse(cmd); break;
      case commands::kFileChooserResponse:  OnFileChooserResponse(cmd); break;

      // ── Network interception ───────────────────────────
      case commands::kArmInterception:        OnArmInterception(cmd); break;
      case commands::kArmInterceptionFile:    OnArmInterceptionFile(cmd); break;
      case commands::kDisarmInterception:     OnDisarmInterception(cmd); break;
      case commands::kInterceptDecision:      OnInterceptDecision(cmd); break;
      case commands::kInterceptDecisionFile:  OnInterceptDecisionFile(cmd); break;
      case commands::kInterceptBodyEdit:      OnInterceptBodyEdit(cmd); break;

      // ── Print ──────────────────────────────────────────
      case commands::kPrint:               OnPrint(cmd); break;
      case commands::kPrintToPdf:          OnPrintToPdf(cmd); break;
      case commands::kShowPrintPreview:    OnShowPrintPreview(cmd); break;
      case commands::kSavePdfResponse:     OnSavePdfResponse(cmd); break;

      // ── Off-screen input injection ─────────────────────
      case commands::kMouseEvent:          OnMouseEvent(cmd); break;
      case commands::kPopupMouseEvent:     OnPopupMouseEvent(cmd); break;
      case commands::kPopupWheelEvent:     OnPopupWheelEvent(cmd); break;
      case commands::kPopupKeyEvent:       OnPopupKeyEvent(cmd); break;
      case commands::kWheelEvent:          OnWheelEvent(cmd); break;
      case commands::kKeyEvent:            OnKeyEvent(cmd); break;
      case commands::kFocusEvent:          OnFocusEvent(cmd); break;

      // ── Not yet implemented ────────────────────────────
      case commands::kSetOpacity:
      case commands::kSetTransparent:
      case commands::kSetClosable:
      case commands::kSetMinimizable:
      case commands::kSetMaximizable:
      case commands::kSetHideFromTaskBar:
      case commands::kSetFocusable:
      case commands::kSetOwner:
      case commands::kSetModality:
      case commands::kSetTrayIcon:
      case commands::kCreateTray:
      case commands::kDestroyTray:
      case commands::kSetTrayTooltip:
      case commands::kReleaseFocus:
      case commands::kToFront:
      case commands::kToBack:
      case commands::kSetEnabled:
        // Accepted as no-ops. Without this break they fell through into
        // kSetBackground and silently reconfigured the chrome background brush.
        break;

      case commands::kSetBackground:      OnSetBackground(cmd); break;
      case commands::kSetCustomChrome:    OnSetCustomChrome(cmd); break;
      case commands::kSetTitleBarHeight:  OnSetTitleBarHeight(cmd); break;
      case commands::kSetHitSpotNodes:    OnSetHitSpotNodes(cmd); break;
      // Superseded commands kept as no-ops so older Java bridges still
      // linked against them don't trigger the unknown-command warning.
      case commands::kSetHitSpots:
      case commands::kSetDragRegion:
      case commands::kSetMinimizeControl:
      case commands::kSetMaximizeControl:
      case commands::kSetCloseControl:
      case commands::kSetFullscreenControl:
        break;

      default:
        LOG(WARNING) << "Unknown command type: 0x" << std::hex << cmd.cmd_type;
        break;
    }

    cmd_reader_.Advance();
  }
}

// =========================================================================
// Window Lifecycle Commands
// =========================================================================

void CommandDispatcher::OnCreateWindow(const CommandSlot& cmd) {
  VLOG(1) << "CMD: CREATE_WINDOW";

  // Register callbacks so WebContentsDelegate events (close, title, load)
  // flow to the event ring buffer for Java. Must be done BEFORE creating
  // the WebContents so the delegate sees the callbacks from the start.
  g_callback_evt_writer = evt_writer_.get();
  g_callback_channel = channel_.get();
  JuxCallbacks callbacks = {};
  callbacks.on_close_requested = &OnCloseRequested;
  callbacks.on_title_changed = &OnTitleChanged;
  callbacks.on_load_status_changed = &OnLoadStatusChanged;
  callbacks.on_url_changed = &OnURLChanged;
  callbacks.on_history_changed = &OnHistoryChanged;
  callbacks.on_session_state = &OnSessionState;
  callbacks.on_js_value = &OnJsValue;
  callbacks.on_render_process_gone = &OnRenderProcessGone;
  // DOM tree sync + event callbacks (Phase 3). These are invoked from
  // the browser-side of the DOM Mojo pipe as the renderer walks the
  // document and reports events.
  callbacks.on_dom_element = &OnDomElement;
  callbacks.on_dom_text = &OnDomText;
  callbacks.on_dom_tree_ready = &OnDomTreeReady;
  callbacks.on_dom_event = &OnDomEvent;
  callbacks.on_js_result = &OnJSResult;
  callbacks.on_js_error = &OnJSError;
  // Error / diagnostic callbacks so Java sees load failures and JS
  // exceptions instead of silent failures.
  callbacks.on_load_error = &OnLoadError;
  callbacks.on_console_message = &OnConsoleMessage;
  // JS dialogs (alert/confirm/prompt/beforeunload) → Java.
  callbacks.on_dialog_requested = &OnDialogRequested;
  // Fullscreen + favicon → Java.
  callbacks.on_fullscreen_requested = &OnFullscreenRequested;
  callbacks.on_favicon_changed = &OnFaviconChanged;
  JuxSetCallbacks(callbacks);

  // Route print-to-PDF "Save As" prompts through Java's JavaFX FileChooser.
  jux::SetSavePdfDialogHook(&SavePdfDialogHookImpl);

  // Create a standalone top-level WebContents (no parent HWND).
  // JuxCreateWebContents is synchronous — blocks until the aura host
  // and WebContents are fully created on the browser thread.
  web_contents_handle_ = JuxCreateWebContents(0);

  // Get the aura host's native HWND — this IS the window the user sees.
  // No separate HWND from CommandDispatcher, so only one window appears.
  //
  // We do NOT subclass the HWND — it was created on the browser thread
  // and its messages are dispatched there by Chromium's message pump.
  // Subclassing from the main thread causes null-pointer crashes from
  // re-entrant messages during SetWindowLongPtrW. Instead, we just use
  // Win32 APIs (ShowWindow, SetWindowText, SetWindowPos) directly on
  // the HWND — these are safe to call cross-thread.
#if BUILDFLAG(IS_WIN)
  hwnd_ = reinterpret_cast<HWND>(JuxGetNativeWindow(web_contents_handle_));
  if (!hwnd_) {
    LOG(ERROR) << "Failed to get native window from WebContents";
    return;
  }
  // Expose for free helpers that need to parent native dialogs (e.g.
  // the print dialog launched from JuxDomClientImpl::OnScriptedPrint)
  // or talk to the DomHandler Mojo remote (chrome-subclass overlay
  // WndProc calling SetHitSpotHovered).
  g_callback_hwnd = hwnd_;
  g_callback_web_contents = web_contents_handle_;

  VLOG(1) << "Window created (HWND=" << hwnd_
            << ", handle=" << web_contents_handle_ << ")";
#endif
}

void CommandDispatcher::OnDestroyWindow(const CommandSlot& cmd) {
  VLOG(1) << "CMD: DESTROY_WINDOW";
  uint32_t wid = channel_->window_id();
  evt_writer_->WriteEvent(events::kWindowClosing, wid);

  hwnd_ = nullptr;
#if BUILDFLAG(IS_WIN)
  g_callback_hwnd = nullptr;
  g_callback_web_contents = 0;
#endif

  if (web_contents_handle_) {
    // Permit the close on the JuxWidgetDelegate before destroying
    // the WebContents and widget.
    JuxAllowClose(web_contents_handle_);
    JuxDestroyWebContents(web_contents_handle_);
    web_contents_handle_ = 0;
  }

  evt_writer_->WriteEvent(events::kWindowClosed, wid);
  shutdown_requested_ = true;
}

void CommandDispatcher::OnShow(const CommandSlot& cmd) {
  VLOG(1) << "CMD: SHOW";
  uint32_t wid = channel_->window_id();
  evt_writer_->WriteEvent(events::kWindowShowing, wid);

  // Show the widget via the views::Widget API. This is the first time
  // the window becomes visible — CreateWebContentsOnUI defers Show()
  // to this handler so Java controls when the window appears.
  if (web_contents_handle_) {
    JuxShowWidget(web_contents_handle_);
  }

  evt_writer_->WriteEvent(events::kWindowShown, wid);
}

void CommandDispatcher::OnHide(const CommandSlot& cmd) {
  uint32_t wid = channel_->window_id();
  evt_writer_->WriteEvent(events::kWindowHiding, wid);

  if (web_contents_handle_) {
    JuxHideWidget(web_contents_handle_);
  }

  evt_writer_->WriteEvent(events::kWindowHidden, wid);
}

// =========================================================================
// Window Property Commands
// =========================================================================

void CommandDispatcher::OnSetTitle(const CommandSlot& cmd) {
#if BUILDFLAG(IS_WIN)
  if (hwnd_) {
    std::string title = cmd.ReadStringPayload();
    std::wstring wtitle = base::UTF8ToWide(title);
    SetWindowTextW(hwnd_, wtitle.c_str());
  }
#endif
}

void CommandDispatcher::OnSetSize(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  // Payload: [windowId:4][width:8(double)][height:8(double)][scale:8(double)].
  // skia-fx OSR: size the off-screen VIEW (not the decorated HWND) to exactly
  // width x height logical px and rasterize at the JavaFX render scale, so the
  // captured frame matches the node and is HiDPI-crisp. The old chrome-inset
  // HWND resize made GetViewBounds() != requested size (the vertical-scale bug).
  double w = cmd.ReadF64(4);
  double h = cmd.ReadF64(12);
  double scale = cmd.payload.size() >= 28 ? cmd.ReadF64(20) : 1.0;
  if (scale <= 0.0) scale = 1.0;
  JuxSetOffscreenSize(web_contents_handle_,
                      static_cast<int>(w), static_cast<int>(h),
                      static_cast<float>(scale));
  // If a print preview is open over this page, resize the centered modal to match
  // the new window size too (Chrome re-runs GetDialogSize on resize). No-op if no
  // preview is open. Keeps the off-screen preview tracking the window.
  JuxAdaptPrintPreviewToInitiator(web_contents_handle_,
                                  static_cast<int>(w), static_cast<int>(h),
                                  static_cast<float>(scale));
}

void CommandDispatcher::OnSetScreenOrigin(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  // Payload: [windowId:4][screenX:8(double)][screenY:8(double)][scale:8(double)]
  double sx = cmd.ReadF64(4);
  double sy = cmd.ReadF64(12);
  double scale = cmd.payload.size() >= 28 ? cmd.ReadF64(20) : 1.0;
  JuxSetScreenOrigin(web_contents_handle_, sx, sy, scale);
}

void CommandDispatcher::OnSetPopupOverrides(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  // Payload: [windowId:4][bits:4] (bit0=select, bit1=color, bit2=contextMenu).
  // The context-menu bit isn't needed here — the context menu is always rendered
  // as a JavaFX menu in the foreground process from the app-supplied items.
  uint32_t bits = cmd.ReadU32(4);
  JuxSetPopupOverrides(web_contents_handle_, (bits & 0x1) != 0,
                       (bits & 0x2) != 0);
}

void CommandDispatcher::OnExecEditingCommand(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  // Payload: [windowId:4][cmd:4].
  JuxExecEditingCommand(web_contents_handle_, cmd.ReadU32(4));
}

void CommandDispatcher::OnSetPosition(const CommandSlot& cmd) {
#if BUILDFLAG(IS_WIN)
  if (hwnd_) {
    auto [x, y] = cmd.ReadTwoDoublesPayload();
    UINT dpi = GetDpiForWindow(hwnd_);
    double scale = dpi / 96.0;
    SetWindowPos(hwnd_, nullptr,
                 static_cast<int>(x * scale),
                 static_cast<int>(y * scale),
                 0, 0, SWP_NOSIZE | SWP_NOZORDER);
  }
#endif
}

void CommandDispatcher::OnSetMinSize(const CommandSlot& cmd) {
  auto [w, h] = cmd.ReadTwoDoublesPayload();
  min_width_ = w;
  min_height_ = h;
  has_min_size_ = true;
}

void CommandDispatcher::OnSetMaxSize(const CommandSlot& cmd) {
  auto [w, h] = cmd.ReadTwoDoublesPayload();
  max_width_ = w;
  max_height_ = h;
  has_max_size_ = true;
}

void CommandDispatcher::OnSetState(const CommandSlot& cmd) {
#if BUILDFLAG(IS_WIN)
  if (!hwnd_) return;
  uint32_t state = cmd.ReadU32Payload();
  switch (state) {
    case commands::kStateNormal:
      ShowWindow(hwnd_, SW_RESTORE);
      break;
    case commands::kStateMinimized:
      ShowWindow(hwnd_, SW_MINIMIZE);
      break;
    case commands::kStateMaximized:
      ShowWindow(hwnd_, SW_MAXIMIZE);
      break;
    case commands::kStateFullscreen: {
      // Borderless fullscreen on current monitor.
      MONITORINFO mi = {sizeof(mi)};
      GetMonitorInfo(MonitorFromWindow(hwnd_, MONITOR_DEFAULTTONEAREST), &mi);
      DWORD style = GetWindowLongW(hwnd_, GWL_STYLE);
      SetWindowLongW(hwnd_, GWL_STYLE, style & ~WS_OVERLAPPEDWINDOW);
      SetWindowPos(hwnd_, HWND_TOP,
                   mi.rcMonitor.left, mi.rcMonitor.top,
                   mi.rcMonitor.right - mi.rcMonitor.left,
                   mi.rcMonitor.bottom - mi.rcMonitor.top,
                   SWP_FRAMECHANGED);
      break;
    }
    default:
      LOG(WARNING) << "Unknown window state: " << state;
      break;
  }
#endif
}

void CommandDispatcher::OnSetResizable(const CommandSlot& cmd) {
#if BUILDFLAG(IS_WIN)
  if (hwnd_) {
    bool resizable = cmd.ReadBoolPayload();
    DWORD style = GetWindowLongW(hwnd_, GWL_STYLE);
    if (resizable) {
      style |= WS_THICKFRAME | WS_MAXIMIZEBOX;
    } else {
      style &= ~(WS_THICKFRAME | WS_MAXIMIZEBOX);
    }
    SetWindowLongW(hwnd_, GWL_STYLE, style);
    SetWindowPos(hwnd_, nullptr, 0, 0, 0, 0,
                 SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_FRAMECHANGED);
    // Keep the chrome subclass in sync so the thin top resize band
    // appears/disappears with the property. No-op if the subclass
    // isn't installed (decorated=true).
    jux::SetChromeResizable(hwnd_, resizable);
  }
#endif
}

void CommandDispatcher::OnSetDecorated(const CommandSlot& cmd) {
#if BUILDFLAG(IS_WIN)
  if (hwnd_) {
    bool decorated = cmd.ReadBoolPayload();
    // Per hittest.md §0.1: do NOT strip WS_CAPTION | WS_THICKFRAME |
    // WS_MAXIMIZEBOX. Snap layouts, DWM shadow, and the show animation
    // all require those styles. The frameless look is produced by the
    // subclass's WM_NCCALCSIZE override (which eats the caption inset
    // visually), not by stripping the style flags.
    DWORD style = GetWindowLongW(hwnd_, GWL_STYLE);
    style |= WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX | WS_MAXIMIZEBOX;
    SetWindowLongW(hwnd_, GWL_STYLE, style);

    // Per hittest.md §0.2: install the WndProc subclass BEFORE firing
    // SWP_FRAMECHANGED so WM_NCCALCSIZE is handled by our override on
    // the very first frame recalculation — otherwise a default caption
    // flashes on first paint.
    if (decorated) {
      jux::UninstallChromeSubclass(hwnd_);
    } else {
      jux::InstallChromeSubclass(hwnd_);
    }

    SetWindowPos(hwnd_, nullptr, 0, 0, 0, 0,
                 SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_FRAMECHANGED);
  }
#endif
}

void CommandDispatcher::OnSetCustomChrome(const CommandSlot& cmd) {
#if BUILDFLAG(IS_WIN)
  if (hwnd_) {
    bool enable = cmd.ReadBoolPayload();
    if (enable) {
      jux::InstallChromeSubclass(hwnd_);
    } else {
      jux::UninstallChromeSubclass(hwnd_);
    }
  }
#endif
}

void CommandDispatcher::OnSetBackground(const CommandSlot& cmd) {
#if BUILDFLAG(IS_WIN)
  if (hwnd_) {
    // Payload (after the 4-byte window id): [rgb:u32] — packed
    // 0x00RRGGBB. The chrome subclass uses this brush to fill the
    // strip exposed during a fast resize before the WebView child
    // catches up; match it to the page body so the gap blends in.
    uint32_t rgb = cmd.ReadU32(4);
    jux::SetChromeBackgroundColor(hwnd_, rgb);
  }
#endif
}

void CommandDispatcher::OnSetTitleBarHeight(const CommandSlot& cmd) {
#if BUILDFLAG(IS_WIN)
  if (hwnd_) {
    // Payload (after the 4-byte window id): [height:f64][reserved:f64]
    // — matches writeDoubleCommand. Reserved slot is currently ignored.
    auto [h, _unused] = cmd.ReadTwoDoublesPayload();
    jux::SetChromeTitleBarHeight(hwnd_, h);
  }
#endif
}

void CommandDispatcher::OnSetHitSpots(const CommandSlot& cmd) {
#if BUILDFLAG(IS_WIN)
  if (!hwnd_) return;

  // Payload (after the 4-byte window id):
  //   [count:u32] × { code:u32, x:f64, y:f64, w:f64, h:f64 }
  // Every entry occupies 36 bytes. Validate length strictly so a
  // malformed Java-side serializer doesn't hand us garbage.
  uint32_t count = cmd.ReadU32(4);
  const size_t needed = 8u + static_cast<size_t>(count) * 36u;
  if (cmd.payload.size() < needed) {
    LOG(WARNING) << "kSetHitSpots: payload too short (have "
                 << cmd.payload.size() << " need " << needed << ")";
    return;
  }

  std::vector<jux::HitSpotRect> spots;
  spots.reserve(count);
  for (uint32_t i = 0; i < count; ++i) {
    size_t off = 8u + static_cast<size_t>(i) * 36u;
    jux::HitSpotRect s;
    s.code = cmd.ReadU32(off + 0);
    s.x    = cmd.ReadF64(off + 4);
    s.y    = cmd.ReadF64(off + 12);
    s.w    = cmd.ReadF64(off + 20);
    s.h    = cmd.ReadF64(off + 28);
    spots.push_back(s);
  }
  VLOG(1) << "CMD: SET_HIT_SPOTS count=" << count;
  for (const auto& s : spots) {
    VLOG(1) << "  spot code=" << s.code
              << " rect=(" << s.x << "," << s.y << "," << s.w << "," << s.h << ")";
  }
  jux::SetChromeHitSpots(hwnd_, spots.data(), spots.size());
#endif
}

void CommandDispatcher::OnSetHitSpotNodes(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;

  // Payload (after the 4-byte window id):
  //   [count:u32] × { code:u32, nodeId:i32 }
  // Each entry occupies 8 bytes. `nodeId` is stored as i32 on the wire
  // but the renderer API takes int64 — we sign-extend.
  uint32_t count = cmd.ReadU32(4);
  const size_t needed = 8u + static_cast<size_t>(count) * 8u;
  if (cmd.payload.size() < needed) {
    LOG(WARNING) << "kSetHitSpotNodes: payload too short (have "
                 << cmd.payload.size() << " need " << needed << ")";
    return;
  }

  std::vector<JuxHitSpotNode> nodes;
  nodes.reserve(count);
  for (uint32_t i = 0; i < count; ++i) {
    size_t off = 8u + static_cast<size_t>(i) * 8u;
    JuxHitSpotNode n = {};
    n.code = cmd.ReadU32(off);
    n.node_id = static_cast<int64_t>(
        static_cast<int32_t>(cmd.ReadU32(off + 4)));
    nodes.push_back(n);
  }
  VLOG(1) << "CMD: SET_HIT_SPOT_NODES count=" << count;
  JuxSetHitSpotNodes(web_contents_handle_, nodes.data(),
                     static_cast<uint32_t>(nodes.size()));
}

void CommandDispatcher::OnSetAlwaysOnTop(const CommandSlot& cmd) {
#if BUILDFLAG(IS_WIN)
  if (hwnd_) {
    bool on_top = cmd.ReadBoolPayload();
    SetWindowPos(hwnd_, on_top ? HWND_TOPMOST : HWND_NOTOPMOST,
                 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE);
  }
#endif
}

// =========================================================================
// Window Appearance Commands
// =========================================================================

void CommandDispatcher::OnSetCursor(const CommandSlot& cmd) {
#if BUILDFLAG(IS_WIN)
  uint32_t cursor_id = cmd.ReadU32Payload();
  LPCWSTR cursor = IDC_ARROW;
  switch (cursor_id) {
    case 0: cursor = IDC_ARROW; break;
    case 1: cursor = IDC_HAND; break;
    case 2: cursor = IDC_IBEAM; break;
    case 3: cursor = IDC_WAIT; break;
    case 4: cursor = IDC_CROSS; break;
    case 5: cursor = IDC_SIZEALL; break;
    case 6: cursor = IDC_NO; break;
    default: cursor = IDC_ARROW; break;
  }
  SetCursor(LoadCursor(nullptr, cursor));
#endif
}

// =========================================================================
// DOM / Content Commands
// =========================================================================

void CommandDispatcher::OnLoadHtml(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  std::string file_path = cmd.ReadStringPayload();
  VLOG(1) << "CMD: LOAD_HTML — " << file_path;

  // Java writes HTML to a temp file and sends the path, or sends inline HTML.
  // Try reading as file first; if it fails, treat as inline HTML.
  base::FilePath path =
#if BUILDFLAG(IS_WIN)
      base::FilePath(base::UTF8ToWide(file_path));
#else
      base::FilePath(file_path);
#endif

  // For now, try loading as file URL.
  // If the path starts with a drive letter or /, treat as file path.
  if (!file_path.empty() &&
      (file_path[0] == '/' || (file_path.size() > 1 && file_path[1] == ':'))) {
    std::string file_url = "file:///" + file_path;
    // Normalize backslashes to forward slashes for URL.
    for (char& c : file_url) {
      if (c == '\\') c = '/';
    }
    JuxLoadURL(web_contents_handle_, file_url.c_str());
  } else {
    JuxLoadHTML(web_contents_handle_, file_path.c_str(), nullptr);
  }
}

void CommandDispatcher::OnLoadUrl(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  std::string url = cmd.ReadStringPayload();
  VLOG(1) << "CMD: LOAD_URL — " << url;
  JuxLoadURL(web_contents_handle_, url.c_str());
}

void CommandDispatcher::OnGoToOffset(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  // Payload: [windowId:4][offset:4(int32)].
  int32_t offset = static_cast<int32_t>(cmd.ReadU32(4));
  VLOG(1) << "CMD: GO_TO_OFFSET " << offset;
  JuxGoToOffset(web_contents_handle_, offset);
}

void CommandDispatcher::OnRestoreSession(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  // Payload: [windowId:4][pathLen:4][utf8Path] — the session blob is staged to a
  // temp file (it can be large with form data). Read it, restore, then delete.
  std::string path = cmd.ReadStringPayload();
  base::FilePath fp = base::FilePath::FromUTF8Unsafe(path);
  std::string blob;
  if (!base::ReadFileToString(fp, &blob)) {
    LOG(ERROR) << "CMD: RESTORE_SESSION — cannot read " << path;
    return;
  }
  base::DeleteFile(fp);  // engine owns the temp file's lifetime
  VLOG(1) << "CMD: RESTORE_SESSION — " << blob.size() << " bytes";
  JuxRestoreSession(web_contents_handle_,
                    reinterpret_cast<const uint8_t*>(blob.data()),
                    static_cast<uint32_t>(blob.size()));
}

// JSObject ops. Payload prefix is always [windowId:4][reqId:4][objId:4]; the
// trailing tagged-value bytes are read straight from cmd.payload.

void CommandDispatcher::OnJsGetMember(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t reqid = cmd.ReadU32(4);
  int32_t obj = static_cast<int32_t>(cmd.ReadU32(8));
  uint32_t name_len = cmd.ReadU32(12);
  std::string name = cmd.ReadString(16, name_len);
  JuxJsGetMember(web_contents_handle_, reqid, obj, name.c_str());
}

void CommandDispatcher::OnJsSetMember(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t reqid = cmd.ReadU32(4);
  int32_t obj = static_cast<int32_t>(cmd.ReadU32(8));
  uint32_t name_len = cmd.ReadU32(12);
  std::string name = cmd.ReadString(16, name_len);
  size_t voff = static_cast<size_t>(16) + name_len;
  const uint8_t* vptr = cmd.payload.data() + voff;
  uint32_t vlen =
      cmd.payload.size() > voff
          ? static_cast<uint32_t>(cmd.payload.size() - voff)
          : 0;
  JuxJsSetMember(web_contents_handle_, reqid, obj, name.c_str(), vptr, vlen);
}

void CommandDispatcher::OnJsRemoveMember(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t reqid = cmd.ReadU32(4);
  int32_t obj = static_cast<int32_t>(cmd.ReadU32(8));
  uint32_t name_len = cmd.ReadU32(12);
  std::string name = cmd.ReadString(16, name_len);
  JuxJsRemoveMember(web_contents_handle_, reqid, obj, name.c_str());
}

void CommandDispatcher::OnJsGetSlot(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t reqid = cmd.ReadU32(4);
  int32_t obj = static_cast<int32_t>(cmd.ReadU32(8));
  int32_t index = static_cast<int32_t>(cmd.ReadU32(12));
  JuxJsGetSlot(web_contents_handle_, reqid, obj, index);
}

void CommandDispatcher::OnJsSetSlot(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t reqid = cmd.ReadU32(4);
  int32_t obj = static_cast<int32_t>(cmd.ReadU32(8));
  int32_t index = static_cast<int32_t>(cmd.ReadU32(12));
  size_t voff = 16;
  const uint8_t* vptr = cmd.payload.data() + voff;
  uint32_t vlen =
      cmd.payload.size() > voff
          ? static_cast<uint32_t>(cmd.payload.size() - voff)
          : 0;
  JuxJsSetSlot(web_contents_handle_, reqid, obj, index, vptr, vlen);
}

void CommandDispatcher::OnJsCall(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t reqid = cmd.ReadU32(4);
  int32_t obj = static_cast<int32_t>(cmd.ReadU32(8));
  uint32_t name_len = cmd.ReadU32(12);
  std::string name = cmd.ReadString(16, name_len);
  size_t argc_off = static_cast<size_t>(16) + name_len;
  uint32_t argc = cmd.ReadU32(argc_off);
  size_t aoff = argc_off + 4;
  const uint8_t* aptr = cmd.payload.data() + aoff;
  uint32_t alen =
      cmd.payload.size() > aoff
          ? static_cast<uint32_t>(cmd.payload.size() - aoff)
          : 0;
  JuxJsCall(web_contents_handle_, reqid, obj, name.c_str(), argc, aptr, alen);
}

void CommandDispatcher::OnJsEval(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t reqid = cmd.ReadU32(4);
  int32_t obj = static_cast<int32_t>(cmd.ReadU32(8));
  uint32_t script_len = cmd.ReadU32(12);
  std::string script = cmd.ReadString(16, script_len);
  JuxJsEval(web_contents_handle_, reqid, obj, script.c_str());
}

void CommandDispatcher::OnJsRelease(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  // Payload: [windowId:4][objId:4] — no requestId (fire-and-forget).
  int32_t obj = static_cast<int32_t>(cmd.ReadU32(4));
  JuxJsRelease(web_contents_handle_, obj);
}

void CommandDispatcher::OnJsCallbackResult(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  // Payload (after windowId): [callId:4][status:1][payload]. status 0 = success
  // (payload = tagged value bytes); 1 = error (payload = [len:4][utf8]).
  int32_t call_id = static_cast<int32_t>(cmd.ReadU32(4));
  uint8_t status = cmd.payload.size() > 8 ? cmd.payload[8] : 1;
  if (status == 0) {
    size_t voff = 9;
    const uint8_t* vptr = cmd.payload.data() + voff;
    uint32_t vlen = cmd.payload.size() > voff
                        ? static_cast<uint32_t>(cmd.payload.size() - voff)
                        : 0;
    JuxResolveJavaCall(web_contents_handle_, call_id, /*ok=*/true, vptr, vlen,
                       nullptr);
  } else {
    uint32_t err_len = cmd.payload.size() >= 13 ? cmd.ReadU32(9) : 0;
    std::string err = cmd.ReadString(13, err_len);
    JuxResolveJavaCall(web_contents_handle_, call_id, /*ok=*/false, nullptr, 0,
                       err.c_str());
  }
}

void CommandDispatcher::OnSetUserAgent(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  std::string ua = cmd.ReadStringPayload();
  VLOG(1) << "CMD: SET_USER_AGENT — len=" << ua.size();
  JuxSetUserAgent(web_contents_handle_, ua.c_str());
}

void CommandDispatcher::OnExecuteJs(const CommandSlot& cmd) {
  // Payload: [windowId:4][requestId:4][strLen:4][utf8]. The request id is
  // echoed back in JS_RESULT/JS_ERROR so the Java side correlates results to
  // the exact call (FIFO matching desynced on dropped/stray results).
  uint32_t request_id = cmd.ReadU32(4);
  uint32_t str_len = cmd.ReadU32(8);
  std::string script = cmd.ReadString(12, str_len);
  VLOG(1) << "CMD: EXECUTE_JS wc=" << web_contents_handle_
            << " req=" << request_id << " script=" << script;
  if (!web_contents_handle_) return;
  JuxExecuteJS(web_contents_handle_, script.c_str(), request_id);
}

void CommandDispatcher::OnDialogResponse(const CommandSlot& cmd) {
  // Payload: [windowId:4][dialogId:4][accepted:1][textLen:4][utf8Text]
  uint32_t dialog_id = cmd.ReadU32(4);
  bool accepted = cmd.ReadBool(8);
  uint32_t text_len = cmd.ReadU32(9);
  std::string text = cmd.ReadString(13, text_len);
  if (!web_contents_handle_) return;
  // Pass (ptr,len) so an embedded '\0' in the prompt reply isn't truncated.
  JuxRespondDialog(web_contents_handle_, dialog_id, accepted, text.data(),
                   static_cast<uint32_t>(text.size()));
}

void CommandDispatcher::OnFullscreenResponse(const CommandSlot& cmd) {
  // Payload: [windowId:4][fsId:4][allowed:1]
  uint32_t fs_id = cmd.ReadU32(4);
  bool allowed = cmd.ReadBool(8);
  if (!web_contents_handle_) return;
  JuxRespondFullscreen(web_contents_handle_, fs_id, allowed);
}

void CommandDispatcher::OnPermissionResponse(const CommandSlot& cmd) {
  // Payload: [windowId:4][permId:4][granted:1]
  uint32_t perm_id = cmd.ReadU32(4);
  bool granted = cmd.ReadBool(8);
  JuxRespondPermission(web_contents_handle_, perm_id, granted);
}

void CommandDispatcher::OnAuthResponse(const CommandSlot& cmd) {
  // Payload: [windowId:4][authId:4][supplied:1][userLen:4][user][passLen:4][pass]
  uint32_t auth_id = cmd.ReadU32(4);
  bool supplied = cmd.ReadBool(8);
  uint32_t user_len = cmd.ReadU32(9);
  std::string user = cmd.ReadString(13, user_len);
  size_t pass_off = 13u + user_len;
  uint32_t pass_len = cmd.ReadU32(pass_off);
  std::string pass = cmd.ReadString(pass_off + 4, pass_len);
  JuxRespondAuth(web_contents_handle_, auth_id, supplied, user.c_str(),
                 pass.c_str());
}

void CommandDispatcher::OnDownloadResponse(const CommandSlot& cmd) {
  // Payload: [windowId:4][downloadId:4][accepted:1][pathLen:4][utf8Path:N]
  uint32_t download_id = cmd.ReadU32(4);
  bool accepted = cmd.ReadBool(8);
  uint32_t path_len = cmd.ReadU32(9);
  std::string path = cmd.ReadString(13, path_len);
  JuxRespondDownload(web_contents_handle_, download_id, accepted, path.c_str());
}

void CommandDispatcher::OnDownloadCancel(const CommandSlot& cmd) {
  // Payload: [windowId:4][downloadId:4]
  uint32_t download_id = cmd.ReadU32(4);
  JuxCancelDownload(web_contents_handle_, download_id);
}

void CommandDispatcher::OnSelectPopupResponse(const CommandSlot& cmd) {
  // Payload: [windowId:4][popupId:4][accepted:1][count:4]{[index:4]}
  if (!web_contents_handle_) return;
  uint32_t popup_id = cmd.ReadU32(4);
  bool accepted = cmd.ReadBool(8);
  uint32_t count = accepted ? cmd.ReadU32(9) : 0;
  // Clamp the wire count to what the payload can actually hold (4 bytes per
  // index starting at offset 13). An unbounded count would drive a ~16 GB
  // reserve() -> bad_alloc abort of the engine on a malformed command.
  uint32_t max_count = cmd.payload.size() > 13
                           ? static_cast<uint32_t>((cmd.payload.size() - 13) / 4)
                           : 0;
  if (count > max_count) count = max_count;
  std::vector<int32_t> indices;
  indices.reserve(count);
  for (uint32_t i = 0; i < count; ++i) {
    indices.push_back(static_cast<int32_t>(cmd.ReadU32(13 + i * 4)));
  }
  JuxSelectPopupResponse(web_contents_handle_, popup_id,
                         indices.empty() ? nullptr : indices.data(), count);
}

void CommandDispatcher::OnColorChooserResponse(const CommandSlot& cmd) {
  // Payload: [windowId:4][chooserId:4][chosen:1][rgba:4]
  if (!web_contents_handle_) return;
  uint32_t chooser_id = cmd.ReadU32(4);
  bool chosen = cmd.ReadBool(8);
  uint32_t rgba = cmd.ReadU32(9);
  JuxColorChooserResponse(web_contents_handle_, chooser_id, chosen, rgba);
}

void CommandDispatcher::OnFileChooserResponse(const CommandSlot& cmd) {
  // Payload: [windowId:4][chooserId:4][count:4][tempLen:4][utf8 tempPath]
  // count==0 ⇒ cancel; otherwise tempPath names a UTF-8 file of `count`
  // newline-separated native paths that JuxFileChooserResponse reads + deletes.
  if (!web_contents_handle_) return;
  uint32_t chooser_id = cmd.ReadU32(4);
  uint32_t count = cmd.ReadU32(8);
  uint32_t temp_len = cmd.ReadU32(12);
  std::string temp_path =
      temp_len > 0 ? cmd.ReadString(16, temp_len) : std::string();
  JuxFileChooserResponse(web_contents_handle_, chooser_id, count,
                         temp_path.c_str());
}

void CommandDispatcher::OnArmInterception(const CommandSlot& cmd) {
  // Payload: [windowId:4][filterLen:4][filterBlob]
  JuxNetworkInterceptor* in = JuxNetworkInterceptor::GetInstance();
  if (!in) return;
  uint32_t len = cmd.ReadU32(4);
  if (8u + len > cmd.payload.size()) {
    len = cmd.payload.size() > 8u ? static_cast<uint32_t>(cmd.payload.size() - 8u) : 0u;
  }
  in->Arm(cmd.payload.data() + 8, len);
}

void CommandDispatcher::OnArmInterceptionFile(const CommandSlot& cmd) {
  // Payload: [windowId:4][pathLen:4][utf8Path]
  JuxNetworkInterceptor* in = JuxNetworkInterceptor::GetInstance();
  if (!in) return;
  uint32_t plen = cmd.ReadU32(4);
  std::string path = cmd.ReadString(8, plen);
  std::string data;
  base::FilePath fp = base::FilePath::FromUTF8Unsafe(path);
  if (base::ReadFileToString(fp, &data)) {
    in->Arm(reinterpret_cast<const uint8_t*>(data.data()), data.size());
  }
  base::DeleteFile(fp);
}

void CommandDispatcher::OnDisarmInterception(const CommandSlot& cmd) {
  JuxNetworkInterceptor* in = JuxNetworkInterceptor::GetInstance();
  if (in) in->Disarm();
}

void CommandDispatcher::OnInterceptDecision(const CommandSlot& cmd) {
  // Payload: [windowId:4][interceptId:4][phase:1][action:1][tailLen:4][tail]
  JuxNetworkInterceptor* in = JuxNetworkInterceptor::GetInstance();
  if (!in) return;
  uint32_t id = cmd.ReadU32(4);
  uint8_t phase = cmd.payload.size() > 8 ? cmd.payload[8] : 0;
  uint8_t action = cmd.payload.size() > 9 ? cmd.payload[9] : 0;
  uint32_t tlen = cmd.ReadU32(10);
  const uint8_t* tail = nullptr;
  if (14u + tlen <= cmd.payload.size()) {
    tail = cmd.payload.data() + 14;
  } else {
    tlen = 0;
  }
  in->Resolve(id, phase, action, tail, tlen);
}

void CommandDispatcher::OnInterceptDecisionFile(const CommandSlot& cmd) {
  // Payload: [windowId:4][interceptId:4][phase:1][action:1][pathLen:4][utf8Path]
  JuxNetworkInterceptor* in = JuxNetworkInterceptor::GetInstance();
  if (!in) return;
  uint32_t id = cmd.ReadU32(4);
  uint8_t phase = cmd.payload.size() > 8 ? cmd.payload[8] : 0;
  uint8_t action = cmd.payload.size() > 9 ? cmd.payload[9] : 0;
  uint32_t plen = cmd.ReadU32(10);
  std::string path = cmd.ReadString(14, plen);
  std::string data;
  base::FilePath fp = base::FilePath::FromUTF8Unsafe(path);
  if (base::ReadFileToString(fp, &data)) {
    in->Resolve(id, phase, action,
                reinterpret_cast<const uint8_t*>(data.data()), data.size());
  }
  base::DeleteFile(fp);
}

void CommandDispatcher::OnInterceptBodyEdit(const CommandSlot& cmd) {
  // Payload: [windowId:4][interceptId:4][chunkSeq:4][edit:1][pathLen:4][utf8Path]
  // path holds the REPLACE body (empty for pass/drop); read then delete it.
  JuxNetworkInterceptor* in = JuxNetworkInterceptor::GetInstance();
  if (!in) return;
  uint32_t id = cmd.ReadU32(4);
  uint32_t seq = cmd.ReadU32(8);
  uint8_t edit = cmd.payload.size() > 12 ? cmd.payload[12] : 0;
  uint32_t plen = cmd.ReadU32(13);
  std::string path = cmd.ReadString(17, plen);
  std::string data;
  if (!path.empty()) {
    base::FilePath fp = base::FilePath::FromUTF8Unsafe(path);
    base::ReadFileToString(fp, &data);
    base::DeleteFile(fp);
  }
  in->ResolveBodyEdit(id, seq, edit,
                      reinterpret_cast<const uint8_t*>(data.data()),
                      data.size());
}

void CommandDispatcher::OnExecuteJsFile(const CommandSlot& cmd) {
  // Payload: [windowId:4][requestId:4][pathLen:4][utf8 path]. The path is a
  // temp file (written by Java) whose contents are the script — used when the
  // script is larger than a ring slot. We read it, delete it, and execute the
  // contents, echoing requestId like OnExecuteJs.
  uint32_t request_id = cmd.ReadU32(4);
  uint32_t path_len = cmd.ReadU32(8);
  std::string path = cmd.ReadString(12, path_len);
  if (!web_contents_handle_ || path.empty()) return;
  // std::ifstream (not base::ReadFileToString) so we don't trip Chromium's
  // UI-thread blocking assertions for this small, local, one-shot read.
  std::string script;
  {
    std::ifstream in(path, std::ios::binary);
    if (in) {
      std::ostringstream ss;
      ss << in.rdbuf();
      script = ss.str();
    } else {
      LOG(ERROR) << "OnExecuteJsFile: cannot open " << path;
    }
  }
  std::remove(path.c_str());  // engine owns the temp file's lifetime
  if (script.empty()) return;
  VLOG(1) << "CMD: EXECUTE_JS_FILE wc=" << web_contents_handle_
            << " req=" << request_id << " bytes=" << script.size();
  JuxExecuteJS(web_contents_handle_, script.c_str(), request_id);
}

void CommandDispatcher::OnLoadResources(const CommandSlot& cmd) {
  VLOG(1) << "CMD: LOAD_RESOURCES";
  if (!web_contents_handle_) return;

  // Payload: [windowId:4][baseLen:4][basePath:N][entryLen:4][entryFile:N]
  uint32_t base_len = cmd.ReadU32(4);
  std::string base_path = cmd.ReadString(8, base_len);
  uint32_t entry_len = cmd.ReadU32(8 + base_len);
  std::string entry_file = cmd.ReadString(8 + base_len + 4, entry_len);

  // Build a file:// URL from the base path + entry file.
  std::string full_path = base_path;
  if (!full_path.empty() && full_path.back() != '/' && full_path.back() != '\\') {
    full_path += '/';
  }
  full_path += entry_file;

  // Convert to file:// URL.
  std::string file_url = "file:///";
  for (char c : full_path) {
    file_url += (c == '\\') ? '/' : c;
  }

  VLOG(1) << "LOAD_RESOURCES URL: " << file_url;
  JuxLoadURL(web_contents_handle_, file_url.c_str());
}

void CommandDispatcher::OnOpenDevTools(const CommandSlot& cmd) {
  if (web_contents_handle_) {
    JuxOpenDevTools(web_contents_handle_);
  }
}

void CommandDispatcher::OnCloseDevTools(const CommandSlot& cmd) {
  if (web_contents_handle_) {
    JuxCloseDevTools(web_contents_handle_);
  }
}

void CommandDispatcher::OnAddStylesheet(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  std::string css = cmd.ReadStringPayload();
  uint32_t id = next_stylesheet_id_++;
  JuxAddStylesheet(web_contents_handle_, id,
                   css.c_str(), static_cast<uint32_t>(css.size()));
}

void CommandDispatcher::OnRemoveStylesheet(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t id = cmd.ReadU32Payload();
  JuxRemoveStylesheet(web_contents_handle_, id);
}

// =========================================================================
// Off-screen input injection commands
//
// Java forwards JavaFX input to the windowless WebContents via these. Floats
// are carried as little-endian IEEE-754 bits in 4-byte fields (Java writes
// JAVA_FLOAT_UNALIGNED). Payload layouts match CommandType.java /
// jux_command_types.h. Each decodes and calls the matching JuxSend* C entry,
// which posts to the UI thread and forwards to the RenderWidgetHost.
// =========================================================================

namespace {
// Reinterpret a 4-byte LE field (read as u32) as a float.
float DecodeF32(uint32_t bits) {
  float f;
  memcpy(&f, &bits, sizeof(f));
  return f;
}
}  // namespace

void CommandDispatcher::OnMouseEvent(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  // [windowId:4][type:4][x:f32][y:f32][button:4][clickCount:4][modifiers:4]
  int type = static_cast<int>(cmd.ReadU32(4));
  float x = DecodeF32(cmd.ReadU32(8));
  float y = DecodeF32(cmd.ReadU32(12));
  int button = static_cast<int>(cmd.ReadU32(16));
  int click_count = static_cast<int>(cmd.ReadU32(20));
  int modifiers = static_cast<int>(cmd.ReadU32(24));
  JuxSendMouseEvent(web_contents_handle_, type, x, y, button, click_count,
                    modifiers);
}

void CommandDispatcher::OnPopupMouseEvent(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  // Same layout as kMouseEvent; (x,y) are popup-local DIP coords.
  int type = static_cast<int>(cmd.ReadU32(4));
  float x = DecodeF32(cmd.ReadU32(8));
  float y = DecodeF32(cmd.ReadU32(12));
  int button = static_cast<int>(cmd.ReadU32(16));
  int click_count = static_cast<int>(cmd.ReadU32(20));
  int modifiers = static_cast<int>(cmd.ReadU32(24));
  JuxSendPopupMouseEvent(web_contents_handle_, type, x, y, button, click_count,
                         modifiers);
}

void CommandDispatcher::OnPopupWheelEvent(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  // Same layout as kWheelEvent; (x,y) are popup-local DIP coords.
  float x = DecodeF32(cmd.ReadU32(4));
  float y = DecodeF32(cmd.ReadU32(8));
  float dx = DecodeF32(cmd.ReadU32(12));
  float dy = DecodeF32(cmd.ReadU32(16));
  int modifiers = static_cast<int>(cmd.ReadU32(20));
  JuxSendPopupWheelEvent(web_contents_handle_, x, y, dx, dy, modifiers);
}

void CommandDispatcher::OnPopupKeyEvent(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  // Same layout as kKeyEvent.
  int type = static_cast<int>(cmd.ReadU32(4));
  int wkc = static_cast<int>(cmd.ReadU32(8));
  int nkc = static_cast<int>(cmd.ReadU32(12));
  int modifiers = static_cast<int>(cmd.ReadU32(16));
  uint32_t text_len = cmd.ReadU32(20);
  std::string text = text_len > 0 ? cmd.ReadString(24, text_len) : std::string();
  JuxSendPopupKeyEvent(web_contents_handle_, type, wkc, nkc, modifiers,
                       text.c_str());
}

void CommandDispatcher::OnWheelEvent(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  // [windowId:4][x:f32][y:f32][deltaX:f32][deltaY:f32][modifiers:4]
  float x = DecodeF32(cmd.ReadU32(4));
  float y = DecodeF32(cmd.ReadU32(8));
  float dx = DecodeF32(cmd.ReadU32(12));
  float dy = DecodeF32(cmd.ReadU32(16));
  int modifiers = static_cast<int>(cmd.ReadU32(20));
  JuxSendWheelEvent(web_contents_handle_, x, y, dx, dy, modifiers);
}

void CommandDispatcher::OnKeyEvent(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  // [windowId:4][type:4][winKeyCode:4][nativeKeyCode:4][modifiers:4][textLen:4][utf8:N]
  int type = static_cast<int>(cmd.ReadU32(4));
  int wkc = static_cast<int>(cmd.ReadU32(8));
  int nkc = static_cast<int>(cmd.ReadU32(12));
  int modifiers = static_cast<int>(cmd.ReadU32(16));
  uint32_t text_len = cmd.ReadU32(20);
  std::string text = text_len > 0 ? cmd.ReadString(24, text_len) : std::string();
  JuxSendKeyEvent(web_contents_handle_, type, wkc, nkc, modifiers,
                  text.c_str());
}

void CommandDispatcher::OnFocusEvent(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  // [windowId:4][focused:4]
  int focused = static_cast<int>(cmd.ReadU32(4));
  JuxSendFocusEvent(web_contents_handle_, focused);
}

// =========================================================================
// Window Action Commands
// =========================================================================

void CommandDispatcher::OnRequestFocus(const CommandSlot& cmd) {
#if BUILDFLAG(IS_WIN)
  if (hwnd_) {
    SetForegroundWindow(hwnd_);
    SetFocus(hwnd_);
  }
#endif
  if (web_contents_handle_) {
    JuxNotifyFocus(web_contents_handle_, 1);
  }
}

void CommandDispatcher::OnCenter(const CommandSlot& cmd) {
#if BUILDFLAG(IS_WIN)
  if (!hwnd_) {
    LOG(WARNING) << "CMD: CENTER — hwnd is null, skipping";
    return;
  }

  HMONITOR monitor = MonitorFromWindow(hwnd_, MONITOR_DEFAULTTONEAREST);
  MONITORINFO mi = {sizeof(mi)};
  GetMonitorInfo(monitor, &mi);

  RECT win_rect;
  GetWindowRect(hwnd_, &win_rect);
  int win_w = win_rect.right - win_rect.left;
  int win_h = win_rect.bottom - win_rect.top;

  // Guard: if the window hasn't been sized yet (0x0), skip — centering
  // at this point would just put a zero-size window at the monitor
  // center and then show() would reveal an invisible window in a
  // weird spot. Callers should center AFTER setSize (and ideally
  // after show()).
  if (win_w <= 0 || win_h <= 0) {
    LOG(WARNING) << "CMD: CENTER — window has no size yet ("
                 << win_w << "x" << win_h << "), skipping";
    return;
  }

  int x = mi.rcWork.left + (mi.rcWork.right - mi.rcWork.left - win_w) / 2;
  int y = mi.rcWork.top + (mi.rcWork.bottom - mi.rcWork.top - win_h) / 2;

  VLOG(1) << "CMD: CENTER — monitor_work="
            << (mi.rcWork.right - mi.rcWork.left) << "x"
            << (mi.rcWork.bottom - mi.rcWork.top)
            << " window=" << win_w << "x" << win_h
            << " → pos=(" << x << "," << y << ")";

  SetWindowPos(hwnd_, nullptr, x, y, 0, 0,
               SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);

  // Fire WINDOW_MOVED event.
  uint32_t wid = channel_->window_id();
  UINT dpi = GetDpiForWindow(hwnd_);
  double scale = dpi / 96.0;
  double lx = x / scale;
  double ly = y / scale;

  // User payload: [x:8(double)][y:8(double)] — EventWriter prepends wid.
  uint8_t payload[16];
  PutF64(payload, 0, lx);
  PutF64(payload, 8, ly);
  evt_writer_->WriteEvent(events::kWindowMoved, wid,
                          base::span<const uint8_t>(payload, sizeof(payload)));
#endif
}

// =========================================================================
// DOM Listener Commands
// =========================================================================

void CommandDispatcher::OnAddEventListener(const CommandSlot& cmd) {
  if (!web_contents_handle_) {
    LOG(WARNING) << "[jux-dom] OnAddEventListener: no web_contents_handle_";
    return;
  }

  // Payload: [windowId:4][nodeId:4][evtTypeLen:2][evtType:N]
  uint32_t node_id = cmd.ReadU32(4);
  if (cmd.payload.size() < 10) {
    LOG(WARNING) << "[jux-dom] OnAddEventListener: payload too small ("
                 << cmd.payload.size() << ")";
    return;
  }
  uint16_t evt_len;
  memcpy(&evt_len, cmd.payload.data() + 8, sizeof(evt_len));
  if (evt_len == 0 || 10 + evt_len > cmd.payload.size()) {
    LOG(WARNING) << "[jux-dom] OnAddEventListener: bad evt_len=" << evt_len;
    return;
  }

  std::string evt_type = cmd.ReadString(10, evt_len);
  VLOG(1) << "[jux-dom] CMD ADD_EVENT_LISTENER: node_id=" << node_id
            << " event=" << evt_type;
  JuxAddEventListener(web_contents_handle_,
                      static_cast<int64_t>(node_id), evt_type.c_str());
}

void CommandDispatcher::OnRemoveEventListener(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;

  uint32_t node_id = cmd.ReadU32(4);
  if (cmd.payload.size() < 10) return;
  uint16_t evt_len;
  memcpy(&evt_len, cmd.payload.data() + 8, sizeof(evt_len));
  if (evt_len == 0 || 10 + evt_len > cmd.payload.size()) return;

  std::string evt_type = cmd.ReadString(10, evt_len);
  JuxRemoveEventListener(web_contents_handle_,
                         static_cast<int64_t>(node_id), evt_type.c_str());
}

// =========================================================================
// DOM manipulation commands
// =========================================================================
//
// Each handler parses the payload (layout documented in jux_command_types.h)
// and forwards to the matching JuxXxx C API, which posts to the UI thread
// and invokes the Mojo remote on JuxDomHandlerImpl (renderer).

namespace {

// Reads a u16 length-prefixed UTF-8 string at offset. Advances *offset
// past the string. Returns empty string on parse error.
std::string ReadLenStr16(const CommandSlot& cmd, size_t* offset) {
  if (*offset + 2 > cmd.payload.size()) return std::string();
  uint16_t len;
  memcpy(&len, cmd.payload.data() + *offset, sizeof(len));
  *offset += 2;
  if (*offset + len > cmd.payload.size()) return std::string();
  std::string s = cmd.ReadString(*offset, len);
  *offset += len;
  return s;
}

// Same but u32 length prefix (used for large payloads like innerHTML
// and textContent).
std::string ReadLenStr32(const CommandSlot& cmd, size_t* offset) {
  if (*offset + 4 > cmd.payload.size()) return std::string();
  uint32_t len;
  memcpy(&len, cmd.payload.data() + *offset, sizeof(len));
  *offset += 4;
  if (*offset + len > cmd.payload.size()) return std::string();
  std::string s = cmd.ReadString(*offset, len);
  *offset += len;
  return s;
}

}  // namespace

void CommandDispatcher::OnCreateElement(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t node_id = cmd.ReadU32(4);
  size_t off = 8;
  std::string tag = ReadLenStr16(cmd, &off);
  if (tag.empty()) return;
  JuxCreateElement(web_contents_handle_,
                   static_cast<int64_t>(node_id), tag.c_str());
}

void CommandDispatcher::OnRemoveElement(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t node_id = cmd.ReadU32(4);
  JuxRemoveElement(web_contents_handle_,
                   static_cast<int64_t>(node_id));
}

void CommandDispatcher::OnSetAttribute(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t node_id = cmd.ReadU32(4);
  size_t off = 8;
  std::string name = ReadLenStr16(cmd, &off);
  std::string value = ReadLenStr16(cmd, &off);
  if (name.empty()) return;
  JuxSetAttribute(web_contents_handle_,
                  static_cast<int64_t>(node_id),
                  name.c_str(), value.c_str());
}

void CommandDispatcher::OnRemoveAttributeCmd(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t node_id = cmd.ReadU32(4);
  size_t off = 8;
  std::string name = ReadLenStr16(cmd, &off);
  if (name.empty()) return;
  JuxRemoveAttribute(web_contents_handle_,
                     static_cast<int64_t>(node_id), name.c_str());
}

void CommandDispatcher::OnAppendChild(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t parent_id = cmd.ReadU32(4);
  uint32_t child_id = cmd.ReadU32(8);
  JuxAppendChild(web_contents_handle_,
                 static_cast<int64_t>(parent_id),
                 static_cast<int64_t>(child_id));
}

void CommandDispatcher::OnInsertBefore(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t parent_id = cmd.ReadU32(4);
  uint32_t child_id = cmd.ReadU32(8);
  uint32_t ref_id = cmd.ReadU32(12);
  JuxInsertBefore(web_contents_handle_,
                  static_cast<int64_t>(parent_id),
                  static_cast<int64_t>(child_id),
                  static_cast<int64_t>(ref_id));
}

void CommandDispatcher::OnRemoveChild(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  // RemoveChild is implemented as RemoveElement on the target; Blink
  // will detach from its current parent.
  uint32_t child_id = cmd.ReadU32(8);  // skip parent_id at offset 4
  JuxRemoveElement(web_contents_handle_,
                   static_cast<int64_t>(child_id));
}

void CommandDispatcher::OnSetTextContentCmd(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t node_id = cmd.ReadU32(4);
  size_t off = 8;
  std::string text = ReadLenStr32(cmd, &off);
  JuxSetTextContent(web_contents_handle_,
                    static_cast<int64_t>(node_id), text.c_str());
}

void CommandDispatcher::OnSetInnerHtmlCmd(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t node_id = cmd.ReadU32(4);
  size_t off = 8;
  std::string html = ReadLenStr32(cmd, &off);
  JuxSetInnerHTML(web_contents_handle_,
                  static_cast<int64_t>(node_id), html.c_str());
}

void CommandDispatcher::OnSetStylePropertyCmd(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t node_id = cmd.ReadU32(4);
  size_t off = 8;
  std::string prop = ReadLenStr16(cmd, &off);
  std::string value = ReadLenStr16(cmd, &off);
  if (prop.empty()) return;
  JuxSetStyleProperty(web_contents_handle_,
                      static_cast<int64_t>(node_id),
                      prop.c_str(), value.c_str());
}

void CommandDispatcher::OnRemoveStylePropertyCmd(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t node_id = cmd.ReadU32(4);
  size_t off = 8;
  std::string prop = ReadLenStr16(cmd, &off);
  if (prop.empty()) return;
  // Same transport as SetStyleProperty with empty value (removes the
  // property via Element::style()->setProperty(prop, "")).
  JuxSetStyleProperty(web_contents_handle_,
                      static_cast<int64_t>(node_id),
                      prop.c_str(), "");
}

void CommandDispatcher::OnAddClassCmd(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t node_id = cmd.ReadU32(4);
  size_t off = 8;
  std::string class_name = ReadLenStr16(cmd, &off);
  if (class_name.empty()) return;
  JuxAddClass(web_contents_handle_,
              static_cast<int64_t>(node_id), class_name.c_str());
}

void CommandDispatcher::OnRemoveClassCmd(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  uint32_t node_id = cmd.ReadU32(4);
  size_t off = 8;
  std::string class_name = ReadLenStr16(cmd, &off);
  if (class_name.empty()) return;
  JuxRemoveClass(web_contents_handle_,
                 static_cast<int64_t>(node_id), class_name.c_str());
}

void CommandDispatcher::OnDomFocusCmd(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  (void)cmd;
  // TODO: plumb JuxFocus. For now a no-op — the public DOM API can
  // still set focus via setAttribute or other side effects.
}

void CommandDispatcher::OnDomBlurCmd(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  (void)cmd;
  // TODO: plumb JuxBlur.
}

void CommandDispatcher::OnDomClickCmd(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  (void)cmd;
  // TODO: plumb JuxClick (synthesize a click event via Blink).
}

// =========================================================================
// File / directory dialog commands (0x0050–0x0052)
//
// Payload format (all three dialogs):
//   [windowId:4][requestId:4][flags:4]
//   [titleLen:2][utf8Title:N]
//   [initialDirLen:2][utf8InitialDir:N]
//   For Open/Save only: [filterCount:2]
//   For each filter:    [descLen:2][utf8Desc:N]
//                       [extListLen:2][utf8ExtList:N]    // ext1;ext2;ext3
//
// flags bits:
//   bit 0 = multi-select (Open dialog only)
//
// The result is written to the event ring buffer with event type
// kDialogOpenResult / kDialogSaveResult / kDialogDirResult. All three
// echo the request_id as the first 4 bytes of the payload (after the
// windowId that EventWriter prepends automatically).
// =========================================================================

namespace {

// Parses the shared header of all three dialog commands. Returns the offset
// to the start of any per-dialog trailing data (filters for Open/Save).
// On parse error, returns 0 and leaves outputs uninitialised.
size_t ParseDialogHeader(const CommandSlot& cmd,
                          uint32_t* request_id,
                          uint32_t* flags,
                          std::u16string* title,
                          base::FilePath* initial_dir) {
  if (cmd.payload.size() < 16) return 0;  // windowId + requestId + flags + 2×len

  *request_id = cmd.ReadU32(4);
  *flags = cmd.ReadU32(8);

  size_t offset = 12;

  // Title.
  uint16_t title_len;
  memcpy(&title_len, cmd.payload.data() + offset, sizeof(title_len));
  offset += 2;
  if (offset + title_len > cmd.payload.size()) return 0;
  if (title_len > 0) {
    std::string title_utf8 = cmd.ReadString(offset, title_len);
    *title = base::UTF8ToUTF16(title_utf8);
  }
  offset += title_len;

  // Initial directory.
  if (offset + 2 > cmd.payload.size()) return 0;
  uint16_t dir_len;
  memcpy(&dir_len, cmd.payload.data() + offset, sizeof(dir_len));
  offset += 2;
  if (offset + dir_len > cmd.payload.size()) return 0;
  if (dir_len > 0) {
    std::string dir_utf8 = cmd.ReadString(offset, dir_len);
#if BUILDFLAG(IS_WIN)
    *initial_dir = base::FilePath(base::UTF8ToWide(dir_utf8));
#else
    *initial_dir = base::FilePath(dir_utf8);
#endif
  }
  offset += dir_len;

  return offset;
}

// Parses the per-filter trailing data into a FileTypeInfo. Returns false on
// parse error.
bool ParseFilters(const CommandSlot& cmd, size_t offset,
                   ui::SelectFileDialog::FileTypeInfo* info) {
  info->include_all_files = true;
  info->allowed_paths = ui::SelectFileDialog::FileTypeInfo::NATIVE_PATH;

  if (offset + 2 > cmd.payload.size()) {
    // No filter count field — treat as zero filters.
    return true;
  }
  uint16_t filter_count;
  memcpy(&filter_count, cmd.payload.data() + offset, sizeof(filter_count));
  offset += 2;

  for (uint16_t i = 0; i < filter_count; ++i) {
    if (offset + 2 > cmd.payload.size()) return false;
    uint16_t desc_len;
    memcpy(&desc_len, cmd.payload.data() + offset, sizeof(desc_len));
    offset += 2;
    if (offset + desc_len > cmd.payload.size()) return false;
    std::u16string description;
    if (desc_len > 0) {
      description = base::UTF8ToUTF16(cmd.ReadString(offset, desc_len));
    }
    offset += desc_len;

    if (offset + 2 > cmd.payload.size()) return false;
    uint16_t ext_list_len;
    memcpy(&ext_list_len, cmd.payload.data() + offset, sizeof(ext_list_len));
    offset += 2;
    if (offset + ext_list_len > cmd.payload.size()) return false;
    std::string ext_list_utf8;
    if (ext_list_len > 0) {
      ext_list_utf8 = cmd.ReadString(offset, ext_list_len);
    }
    offset += ext_list_len;

    // Split ext_list by ';' and build an extension vector.
    std::vector<base::FilePath::StringType> exts;
    size_t pos = 0;
    while (pos < ext_list_utf8.size()) {
      size_t semi = ext_list_utf8.find(';', pos);
      if (semi == std::string::npos) semi = ext_list_utf8.size();
      std::string one = ext_list_utf8.substr(pos, semi - pos);
      // Strip leading '.' if present.
      if (!one.empty() && one[0] == '.') one.erase(0, 1);
      if (!one.empty()) {
#if BUILDFLAG(IS_WIN)
        exts.emplace_back(base::UTF8ToWide(one));
#else
        exts.emplace_back(one);
#endif
      }
      pos = semi + 1;
    }
    if (!exts.empty()) {
      info->extensions.push_back(std::move(exts));
      info->extension_description_overrides.push_back(std::move(description));
    }
  }
  return true;
}

// Heap-allocated listener that writes the dialog result back to the event
// ring buffer and self-destructs on completion. Holds a scoped_refptr to
// the SelectFileDialog to keep it alive, and releases it in Cleanup().
class JavaDialogRequest : public ui::SelectFileDialog::Listener {
 public:
  JavaDialogRequest(EventWriter* writer, uint32_t window_id,
                     uint32_t request_id, uint32_t event_type)
      : writer_(writer),
        window_id_(window_id),
        request_id_(request_id),
        event_type_(event_type) {}

  void SetDialog(scoped_refptr<ui::SelectFileDialog> d) {
    dialog_ = std::move(d);
  }

  // Registers the owning dispatcher so this request is untracked on self-delete
  // and can be neutralized if the dispatcher tears down first.
  void SetOwner(CommandDispatcher* owner) { owner_ = owner; }

  // Called by the dispatcher dtor: drop the back-references so a late listener
  // callback writes nothing and doesn't touch the destroyed dispatcher. The
  // request still self-deletes when (if) the callback eventually fires.
  void Neutralize() {
    writer_ = nullptr;
    owner_ = nullptr;
  }

  void FileSelected(const ui::SelectedFileInfo& file, int index) override {
    std::vector<base::FilePath> paths;
    paths.emplace_back(file.file_path);
    WriteResult(paths);
    Untrack();
    delete this;
  }

  void MultiFilesSelected(
      const std::vector<ui::SelectedFileInfo>& files) override {
    std::vector<base::FilePath> paths;
    paths.reserve(files.size());
    for (const auto& f : files) paths.emplace_back(f.file_path);
    WriteResult(paths);
    Untrack();
    delete this;
  }

  void FileSelectionCanceled() override {
    WriteResult({});  // empty = cancelled
    Untrack();
    delete this;
  }

 private:
  // Must be public for std::unique_ptr's default_delete to compile. The
  // class self-destructs in its callbacks via `delete this`, so external
  // code should not construct unique_ptr's that survive past that
  // destruction point anyway.
 public:
  ~JavaDialogRequest() override = default;

 private:

  // Writes the result event to the ring buffer. For kDialogOpenResult
  // the payload is [requestId:4][pathCount:4]{[pathLen:4][utf8Path:N]}...;
  // for Save/Dir results the payload is [requestId:4][pathLen:4][utf8Path:N]
  // (pathLen=0 indicates cancellation).
  void WriteResult(const std::vector<base::FilePath>& paths) {
    if (!writer_) return;

    std::vector<std::string> utf8_paths;
    utf8_paths.reserve(paths.size());
    size_t total_bytes = 0;
    for (const auto& p : paths) {
#if BUILDFLAG(IS_WIN)
      std::string u8 = base::WideToUTF8(p.value());
#else
      std::string u8 = p.value();
#endif
      total_bytes += u8.size();
      utf8_paths.push_back(std::move(u8));
    }

    if (event_type_ == events::kDialogOpenResult) {
      // [requestId:4][pathCount:4] + for each: [pathLen:4][utf8:N]
      size_t needed = 4 + 4 + 4 * utf8_paths.size() + total_bytes;
      std::vector<uint8_t> payload(needed);
      size_t off = 0;
      PutU32(payload.data(), off, request_id_); off += 4;
      PutU32(payload.data(), off,
             static_cast<uint32_t>(utf8_paths.size())); off += 4;
      for (const auto& u8 : utf8_paths) {
        PutU32(payload.data(), off,
               static_cast<uint32_t>(u8.size())); off += 4;
        if (!u8.empty()) {
          memcpy(payload.data() + off, u8.data(), u8.size());
        }
        off += u8.size();
      }
      writer_->WriteEvent(event_type_, window_id_,
                          base::span<const uint8_t>(payload));
    } else {
      // Save / Dir: [requestId:4][pathLen:4][utf8:N]
      const std::string& u8 = utf8_paths.empty() ? std::string() : utf8_paths[0];
      std::vector<uint8_t> payload(4 + 4 + u8.size());
      PutU32(payload.data(), 0, request_id_);
      PutU32(payload.data(), 4, static_cast<uint32_t>(u8.size()));
      if (!u8.empty()) {
        memcpy(payload.data() + 8, u8.data(), u8.size());
      }
      writer_->WriteEvent(event_type_, window_id_,
                          base::span<const uint8_t>(payload));
    }
  }

  // Removes this request from the owner's open-dialog set before self-delete,
  // so the set never holds a dangling pointer. No-op if already neutralized.
  void Untrack() {
    if (owner_) {
      owner_->ForgetFileDialog(this);
      owner_ = nullptr;
    }
  }

  raw_ptr<EventWriter> writer_;
  uint32_t window_id_;
  uint32_t request_id_;
  uint32_t event_type_;
  scoped_refptr<ui::SelectFileDialog> dialog_;  // keeps dialog alive
  raw_ptr<CommandDispatcher> owner_ = nullptr;  // not owned; for untrack/neutralize
};

// Shared helper: creates a dialog of the given type using the parsed
// header + filters, and shows it. Takes ownership of the request (which
// self-destructs on completion).
void ShowDialog(CommandDispatcher* owner,
                 HWND owning_hwnd,
                 std::unique_ptr<JavaDialogRequest> request,
                 ui::SelectFileDialog::Type dialog_type,
                 const std::u16string& title,
                 const base::FilePath& initial_dir,
                 const ui::SelectFileDialog::FileTypeInfo* file_types) {
  scoped_refptr<ui::SelectFileDialog> dialog =
      ui::SelectFileDialog::Create(request.get(), nullptr);
  if (!dialog) {
    LOG(ERROR) << "Failed to create SelectFileDialog — cancelling";
    request->FileSelectionCanceled();  // this deletes request
    (void)request.release();
    return;
  }
  // Hand dialog to request — it keeps it alive until the callback fires.
  JavaDialogRequest* raw = request.release();
  raw->SetDialog(dialog);
  // Track it so an abandoned dialog (window torn down while open) is reaped /
  // neutralized at dispatcher teardown instead of leaking or writing through a
  // dangling EventWriter.
  raw->SetOwner(owner);
  if (owner) {
    owner->TrackFileDialog(raw);
  }

  gfx::NativeWindow owning_window = nullptr;
#if BUILDFLAG(IS_WIN)
  // Convert the native HWND to the aura::Window that hosts it. This makes
  // the dialog modal to the owner window AND inherits the owner's title-
  // bar icon, which is what Win32 dialogs do by default when parented.
  if (owning_hwnd && IsWindow(owning_hwnd)) {
    owning_window =
        views::DesktopWindowTreeHostWin::GetContentWindowForHWND(owning_hwnd);
  }
#else
  (void)owning_hwnd;
#endif

  dialog->SelectFile(dialog_type, title, initial_dir,
                     file_types, /*file_type_index=*/0,
                     base::FilePath::StringType(), owning_window,
                     /*caller=*/nullptr);
}

}  // namespace

void CommandDispatcher::NeutralizeOpenFileDialogs() {
  // Defined here (after JavaDialogRequest) so the type is complete. Each request
  // self-deletes on its own callback; we only sever its links to us.
  for (void* r : open_file_dialogs_) {
    static_cast<JavaDialogRequest*>(r)->Neutralize();
  }
  open_file_dialogs_.clear();
}

void CommandDispatcher::OnShowOpenDialog(const CommandSlot& cmd) {
  uint32_t request_id = 0, flags = 0;
  std::u16string title;
  base::FilePath initial_dir;
  size_t offset = ParseDialogHeader(cmd, &request_id, &flags, &title,
                                     &initial_dir);
  if (offset == 0) {
    LOG(WARNING) << "OnShowOpenDialog: malformed payload";
    return;
  }
  ui::SelectFileDialog::FileTypeInfo file_types;
  if (!ParseFilters(cmd, offset, &file_types)) {
    LOG(WARNING) << "OnShowOpenDialog: malformed filter payload";
    return;
  }
  bool multi = (flags & 0x01) != 0;
  auto dialog_type = multi ? ui::SelectFileDialog::SELECT_OPEN_MULTI_FILE
                             : ui::SelectFileDialog::SELECT_OPEN_FILE;

  uint32_t wid = channel_ ? channel_->window_id() : 0;
  auto request = std::make_unique<JavaDialogRequest>(
      evt_writer_, wid, request_id, events::kDialogOpenResult);

#if BUILDFLAG(IS_WIN)
  HWND owning = hwnd_;
#else
  void* owning = nullptr;
#endif
  ShowDialog(this, owning, std::move(request), dialog_type, title,
             initial_dir,
             file_types.extensions.empty() ? nullptr : &file_types);
}

void CommandDispatcher::OnShowSaveDialog(const CommandSlot& cmd) {
  uint32_t request_id = 0, flags = 0;
  std::u16string title;
  base::FilePath initial_dir;
  size_t offset = ParseDialogHeader(cmd, &request_id, &flags, &title,
                                     &initial_dir);
  if (offset == 0) {
    LOG(WARNING) << "OnShowSaveDialog: malformed payload";
    return;
  }
  ui::SelectFileDialog::FileTypeInfo file_types;
  if (!ParseFilters(cmd, offset, &file_types)) {
    LOG(WARNING) << "OnShowSaveDialog: malformed filter payload";
    return;
  }

  uint32_t wid = channel_ ? channel_->window_id() : 0;
  auto request = std::make_unique<JavaDialogRequest>(
      evt_writer_, wid, request_id, events::kDialogSaveResult);

#if BUILDFLAG(IS_WIN)
  HWND owning = hwnd_;
#else
  void* owning = nullptr;
#endif
  ShowDialog(this, owning, std::move(request),
             ui::SelectFileDialog::SELECT_SAVEAS_FILE, title, initial_dir,
             file_types.extensions.empty() ? nullptr : &file_types);
}

void CommandDispatcher::OnShowDirDialog(const CommandSlot& cmd) {
  uint32_t request_id = 0, flags = 0;
  std::u16string title;
  base::FilePath initial_dir;
  size_t offset = ParseDialogHeader(cmd, &request_id, &flags, &title,
                                     &initial_dir);
  if (offset == 0) {
    LOG(WARNING) << "OnShowDirDialog: malformed payload";
    return;
  }

  uint32_t wid = channel_ ? channel_->window_id() : 0;
  auto request = std::make_unique<JavaDialogRequest>(
      evt_writer_, wid, request_id, events::kDialogDirResult);

#if BUILDFLAG(IS_WIN)
  HWND owning = hwnd_;
#else
  void* owning = nullptr;
#endif
  // Directory pickers don't take filters.
  ShowDialog(this, owning, std::move(request),
             ui::SelectFileDialog::SELECT_EXISTING_FOLDER, title,
             initial_dir, /*file_types=*/nullptr);
}

// =========================================================================
// Window icons (0x0020)
//
// Java sends a PNG file path as the string payload. We decode the PNG via
// WIC, produce an HICON, and apply it with WM_SETICON (big + small).
// Previous icons are destroyed to prevent GDI handle leaks.
// =========================================================================

#if BUILDFLAG(IS_WIN)
namespace {

// Creates an HICON from a PNG file on disk using WIC. Returns nullptr on
// failure. The caller owns the returned HICON and must DestroyIcon() it.
HICON CreateHIconFromPng(const base::FilePath& png_path, int width,
                          int height) {
  using Microsoft::WRL::ComPtr;

  // Ensure COM is initialised on this thread (one-shot, safe to call
  // repeatedly per thread).
  static thread_local bool com_initialised = false;
  if (!com_initialised) {
    HRESULT hr = CoInitializeEx(nullptr,
                                 COINIT_APARTMENTTHREADED |
                                     COINIT_DISABLE_OLE1DDE);
    if (SUCCEEDED(hr) || hr == RPC_E_CHANGED_MODE) {
      com_initialised = true;
    }
  }

  ComPtr<IWICImagingFactory> factory;
  if (FAILED(CoCreateInstance(CLSID_WICImagingFactory, nullptr,
                              CLSCTX_INPROC_SERVER,
                              IID_PPV_ARGS(&factory)))) {
    return nullptr;
  }

  ComPtr<IWICBitmapDecoder> decoder;
  if (FAILED(factory->CreateDecoderFromFilename(
          png_path.value().c_str(), nullptr, GENERIC_READ,
          WICDecodeMetadataCacheOnLoad, &decoder))) {
    return nullptr;
  }

  ComPtr<IWICBitmapFrameDecode> frame;
  if (FAILED(decoder->GetFrame(0, &frame))) return nullptr;

  ComPtr<IWICFormatConverter> converter;
  if (FAILED(factory->CreateFormatConverter(&converter))) return nullptr;
  if (FAILED(converter->Initialize(
          frame.Get(), GUID_WICPixelFormat32bppBGRA,
          WICBitmapDitherTypeNone, nullptr, 0.0,
          WICBitmapPaletteTypeCustom))) {
    return nullptr;
  }

  ComPtr<IWICBitmapScaler> scaler;
  if (FAILED(factory->CreateBitmapScaler(&scaler))) return nullptr;
  if (FAILED(scaler->Initialize(converter.Get(), width, height,
                                 WICBitmapInterpolationModeFant))) {
    return nullptr;
  }

  std::vector<uint8_t> pixels(width * height * 4);
  if (FAILED(scaler->CopyPixels(nullptr, width * 4,
                                 static_cast<UINT>(pixels.size()),
                                 pixels.data()))) {
    return nullptr;
  }

  // Create a 32bpp DIB from the pixel data. The pixels are BGRA which is
  // what CreateIconIndirect expects.
  BITMAPV5HEADER bi = {};
  bi.bV5Size = sizeof(bi);
  bi.bV5Width = width;
  bi.bV5Height = -height;  // top-down
  bi.bV5Planes = 1;
  bi.bV5BitCount = 32;
  bi.bV5Compression = BI_BITFIELDS;
  bi.bV5RedMask = 0x00FF0000;
  bi.bV5GreenMask = 0x0000FF00;
  bi.bV5BlueMask = 0x000000FF;
  bi.bV5AlphaMask = 0xFF000000;

  HDC hdc = GetDC(nullptr);
  void* color_bits = nullptr;
  HBITMAP color_bmp = CreateDIBSection(
      hdc, reinterpret_cast<BITMAPINFO*>(&bi), DIB_RGB_COLORS, &color_bits,
      nullptr, 0);
  ReleaseDC(nullptr, hdc);
  if (!color_bmp || !color_bits) {
    if (color_bmp) DeleteObject(color_bmp);
    return nullptr;
  }
  memcpy(color_bits, pixels.data(), pixels.size());

  // An AND mask is required for CreateIconIndirect — create an empty one.
  HBITMAP mask_bmp = CreateBitmap(width, height, 1, 1, nullptr);
  if (!mask_bmp) {
    DeleteObject(color_bmp);
    return nullptr;
  }

  ICONINFO ii = {};
  ii.fIcon = TRUE;
  ii.hbmColor = color_bmp;
  ii.hbmMask = mask_bmp;
  HICON icon = CreateIconIndirect(&ii);
  DeleteObject(color_bmp);
  DeleteObject(mask_bmp);
  return icon;
}

}  // namespace
#endif

// =========================================================================
// Print commands (0x0090 / 0x0091)
//
// The actual print pipeline (PrintViewManager, PrintRenderFrameHelper,
// PrintedDocument → native GDI spooler) is set up by JuxPrintManager
// which is attached per-WebContents in jux_engine_api.cc. The command
// handlers here just fire the print flow and emit kPrintRequested /
// kPrintResult events to Java so it can track the outcome.
// =========================================================================

// ---------------------------------------------------------------------------
// Minimal PrintingContext::Delegate — provides the parent window and the
// app locale to the print dialog.
// ---------------------------------------------------------------------------
#if defined(SFXWEB_ENABLE_PRINTING)
namespace {

class JuxPrintDelegate : public printing::PrintingContext::Delegate {
 public:
#if BUILDFLAG(IS_WIN)
  explicit JuxPrintDelegate(HWND parent_hwnd) : parent_hwnd_(parent_hwnd) {}
#else
  JuxPrintDelegate() = default;
#endif
  ~JuxPrintDelegate() override = default;

  gfx::NativeView GetParentView() override {
#if BUILDFLAG(IS_WIN)
    if (parent_hwnd_ && IsWindow(parent_hwnd_)) {
      return views::DesktopWindowTreeHostWin::GetContentWindowForHWND(
          parent_hwnd_);
    }
    return nullptr;
#else
    return nullptr;
#endif
  }

  std::string GetAppLocale() override { return "en-US"; }

 private:
#if BUILDFLAG(IS_WIN)
  HWND parent_hwnd_;
#endif
};

}  // namespace

// Shows the native OS print dialog parented to the given HWND, and
// writes a kPrintResult event to `writer` when the dialog closes.
// Safe to call from any browser-thread context. The actual page
// rendering pipeline is a separate TODO — this only surfaces the
// dialog so the user can pick a printer.
void ShowNativePrintDialog(
#if BUILDFLAG(IS_WIN)
    HWND parent_hwnd,
#endif
    EventWriter* writer, uint32_t window_id) {
#if BUILDFLAG(IS_WIN)
  auto delegate = std::make_unique<JuxPrintDelegate>(parent_hwnd);
  auto* delegate_raw = delegate.get();
  auto context = printing::PrintingContext::Create(
      delegate_raw,
      printing::PrintingContext::OutOfProcessBehavior::kDisabled);
  if (!context) {
    LOG(ERROR) << "ShowNativePrintDialog: PrintingContext::Create failed";
    if (writer) {
      uint8_t payload[1] = {0};
      writer->WriteEvent(events::kPrintResult, window_id,
                         base::span<const uint8_t>(payload, size_t{1}));
    }
    return;
  }

  // Prime with default printer settings — required before
  // AskUserForSettings on Windows.
  context->UseDefaultSettings();

  auto* context_raw = context.get();
  context_raw->AskUserForSettings(
      /*max_pages=*/1,
      /*has_selection=*/false,
      /*is_scripted=*/false,
      base::BindOnce(
          [](std::unique_ptr<JuxPrintDelegate> /*d*/,
             std::unique_ptr<printing::PrintingContext> /*c*/,
             EventWriter* writer, uint32_t wid,
             printing::mojom::ResultCode result) {
            bool success =
                (result == printing::mojom::ResultCode::kSuccess);
            VLOG(1) << "Print dialog result: "
                      << static_cast<int>(result);
            if (writer) {
              uint8_t payload[1] = {success ? uint8_t{1} : uint8_t{0}};
              writer->WriteEvent(events::kPrintResult, wid,
                                 base::span<const uint8_t>(payload, size_t{1}));
            }
          },
          std::move(delegate), std::move(context), writer, window_id));
#else
  // Non-Windows: not implemented yet.
  if (writer) {
    uint8_t payload[1] = {0};
    writer->WriteEvent(events::kPrintResult, window_id,
                       base::span<const uint8_t>(payload, size_t{1}));
  }
#endif
}
#endif  // SFXWEB_ENABLE_PRINTING

void CommandDispatcher::OnPrint(const CommandSlot& cmd) {
  (void)cmd;
  if (!web_contents_handle_) return;
  VLOG(1) << "CMD: PRINT";

  // Notify Java that a print has been requested.
  if (evt_writer_ && channel_) {
    evt_writer_->WriteEvent(events::kPrintRequested, channel_->window_id());
  }

  // Pop the native OS print dialog via the shared helper.
  uint32_t wid = channel_ ? channel_->window_id() : 0;
#if defined(SFXWEB_ENABLE_PRINTING)
#if BUILDFLAG(IS_WIN)
  ShowNativePrintDialog(hwnd_, evt_writer_, wid);
#else
  ShowNativePrintDialog(evt_writer_, wid);
#endif
#else
  // Printing disabled in this build (PDF/printing dropped for size).
  (void)wid;
  if (evt_writer_ && channel_) {
    uint8_t payload[1] = {0};
    evt_writer_->WriteEvent(events::kPrintResult, channel_->window_id(),
                            base::span<const uint8_t>(payload, size_t{1}));
  }
#endif  // SFXWEB_ENABLE_PRINTING
}

void CommandDispatcher::OnPrintToPdf(const CommandSlot& cmd) {
  if (!web_contents_handle_) return;
  std::string path = cmd.ReadStringPayload();
  VLOG(1) << "CMD: PRINT_TO_PDF path=" << path;

  if (evt_writer_ && channel_) {
    evt_writer_->WriteEvent(events::kPrintRequested, channel_->window_id());
  }

  // Headless page→PDF. Empty path → "Save As" dialog; non-empty → write there.
  JuxPrintToPdf(web_contents_handle_, path.empty() ? nullptr : path.c_str());
}

void CommandDispatcher::OnShowPrintPreview(const CommandSlot& cmd) {
  (void)cmd;
  if (!web_contents_handle_) return;
  VLOG(1) << "CMD: SHOW_PRINT_PREVIEW";
  JuxShowPrintPreview(web_contents_handle_);
}

void CommandDispatcher::OnSavePdfResponse(const CommandSlot& cmd) {
  // Payload: [windowId:4][requestId:4][pathLen:4][utf8Path:N]
  uint32_t request_id = cmd.ReadU32(4);
  uint32_t path_len = cmd.ReadU32(8);
  std::string path = cmd.ReadString(12, path_len);
  VLOG(1) << "CMD: SAVE_PDF_RESPONSE id=" << request_id
            << " path=" << path;
  auto it = g_pending_pdf_saves->find(request_id);
  if (it == g_pending_pdf_saves->end()) {
    return;
  }
  base::OnceCallback<void(base::FilePath)> cb = std::move(it->second);
  g_pending_pdf_saves->erase(it);
  std::move(cb).Run(path.empty() ? base::FilePath()
                                  : base::FilePath::FromUTF8Unsafe(path));
}

void CommandDispatcher::OnSetIcons(const CommandSlot& cmd) {
#if BUILDFLAG(IS_WIN)
  if (!hwnd_) return;

  // Payload: [windowId:4][pathLen:4][utf8Path:N]
  std::string path_utf8 = cmd.ReadStringPayload();
  if (path_utf8.empty()) return;

  base::FilePath png_path(base::UTF8ToWide(path_utf8));
  HICON big = CreateHIconFromPng(png_path, 32, 32);
  HICON small_icon = CreateHIconFromPng(png_path, 16, 16);
  if (!big && !small_icon) {
    LOG(WARNING) << "OnSetIcons: failed to decode PNG: " << path_utf8;
    return;
  }

  // Apply to window — SendMessage returns the previous icon; we already
  // track the previous handles in big_icon_ / small_icon_ so we ignore it.
  if (big) {
    SendMessage(hwnd_, WM_SETICON, ICON_BIG,
                reinterpret_cast<LPARAM>(big));
    if (big_icon_) DestroyIcon(big_icon_);
    big_icon_ = big;
  }
  if (small_icon) {
    SendMessage(hwnd_, WM_SETICON, ICON_SMALL,
                reinterpret_cast<LPARAM>(small_icon));
    if (small_icon_) DestroyIcon(small_icon_);
    small_icon_ = small_icon;
  }
#else
  (void)cmd;
#endif
}

}  // namespace jux
