// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxDomClientImpl implementation.

#ifdef UNSAFE_BUFFERS_BUILD
#pragma allow_unsafe_buffers
#endif

#include "jux/jux_dom_client_impl.h"

#include <cstring>
#include <utility>

#include "base/files/file_util.h"
#include "base/logging.h"
#include "jux/jux_command_dispatch.h"
#include "jux/jux_event_types.h"
#include "jux/jux_ipc.h"
#include "jux/jux_ring_buffer.h"
#include "printing/buildflags/buildflags.h"
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
#include "jux/jux_select_dropdown.h"
#endif
#if BUILDFLAG(IS_WIN)
#include "jux/jux_chrome_subclass.h"
#endif

namespace jux {

namespace {

// Maps the DOM event-type name (as fired by Blink) to the Jux event type
// constant written on the ring buffer. Unknown event types fall back to
// kDomClick (the caller should never register unknown types but the
// fallback guarantees we always emit *something* observable).
uint32_t EventTypeFromName(const std::string& name) {
  if (name == "click")          return events::kDomClick;
  if (name == "dblclick")       return events::kDomDblClick;
  if (name == "mousedown")      return events::kDomMouseDown;
  if (name == "mouseup")        return events::kDomMouseUp;
  if (name == "mousemove")      return events::kDomMouseMove;
  if (name == "mouseenter")     return events::kDomMouseEnter;
  if (name == "mouseleave")     return events::kDomMouseLeave;
  if (name == "mouseover")      return events::kDomMouseOver;
  if (name == "mouseout")       return events::kDomMouseOut;
  if (name == "contextmenu")    return events::kDomContextMenu;
  if (name == "keydown")        return events::kDomKeyDown;
  if (name == "keyup")          return events::kDomKeyUp;
  if (name == "keypress")       return events::kDomKeyPress;
  if (name == "focus")          return events::kDomFocus;
  if (name == "blur")           return events::kDomBlur;
  if (name == "focusin")        return events::kDomFocusIn;
  if (name == "focusout")       return events::kDomFocusOut;
  if (name == "scroll")         return events::kDomScroll;
  if (name == "input")          return events::kDomInput;
  LOG(WARNING) << "Unknown DOM event name: " << name;
  return events::kDomClick;
}

}  // namespace

JuxDomClientImpl::JuxDomClientImpl(EventWriter* writer,
                                     ipc::SharedMemoryChannel* channel)
    : writer_(writer), channel_(channel) {}

JuxDomClientImpl::~JuxDomClientImpl() = default;

void JuxDomClientImpl::Bind(
    mojo::PendingReceiver<mojom::JuxDomClient> receiver) {
  receiver_.reset();
  receiver_.Bind(std::move(receiver));
}

void JuxDomClientImpl::OnScriptedPrint(bool user_initiated) {
  (void)user_initiated;
  if (!writer_ || !channel_) return;

  // Fire kPrintRequested to Java so application code can react.
  writer_->WriteEvent(events::kPrintRequested, channel_->window_id());

#if defined(SFXWEB_ENABLE_PRINT_PREVIEW)
  // Print preview owns window.print()/Ctrl+P: PrintRenderFrameHelper routes the
  // scripted-print request through PrintViewManager → chrome://print, which
  // renders the in-app preview overlay. The legacy native OS dialog below must
  // NOT also fire here, or the user gets TWO dialogs (the OS one + the preview).
  // We still emit kPrintRequested above so app code can observe the intent.
#elif defined(SFXWEB_ENABLE_PRINTING)
  // Printing without preview: pop the native OS print dialog so window.print()
  // behaves like any other browser. Parent to the dispatcher's HWND for modality
  // (g_callback_hwnd may be null if torn down — the dialog still shows).
#if BUILDFLAG(IS_WIN)
  ShowNativePrintDialog(g_callback_hwnd, writer_, channel_->window_id());
#else
  ShowNativePrintDialog(writer_, channel_->window_id());
#endif
#else
  // skia-fx: printing dropped for size. window.print() is a no-op beyond the
  // kPrintRequested notification already fired above.
#endif
}

void JuxDomClientImpl::OnMutations(
    std::vector<mojom::MutationRecordPtr> records) {
  if (!writer_ || !channel_) return;
  uint32_t wid = channel_->window_id();

  // Encoders for the three mutation flavours.
  auto put_u32 = [](std::vector<uint8_t>& b, uint32_t v) {
    b.push_back(static_cast<uint8_t>(v));
    b.push_back(static_cast<uint8_t>(v >> 8));
    b.push_back(static_cast<uint8_t>(v >> 16));
    b.push_back(static_cast<uint8_t>(v >> 24));
  };
  auto put_u16 = [](std::vector<uint8_t>& b, uint16_t v) {
    b.push_back(static_cast<uint8_t>(v));
    b.push_back(static_cast<uint8_t>(v >> 8));
  };
  auto put_str16 = [&put_u16](std::vector<uint8_t>& b,
                               const std::string& s) {
    uint16_t len = static_cast<uint16_t>(
        s.size() > 0xFFFFu ? 0xFFFFu : s.size());
    put_u16(b, len);
    if (len > 0) {
      b.insert(b.end(), s.begin(), s.begin() + len);
    }
  };

  for (const auto& r : records) {
    if (!r) continue;
    uint32_t target = static_cast<uint32_t>(
        r->target_node_id & 0xFFFFFFFF);

    std::vector<uint8_t> p;
    switch (r->type) {
      case 0: {
        // Attribute: [nodeId:4][nameLen:2][name][oldLen:2][old][newLen:2][new]
        put_u32(p, target);
        put_str16(p, r->attribute_name);
        put_str16(p, r->old_value);
        put_str16(p, r->new_value);
        writer_->WriteEvent(events::kMutationAttribute, wid,
                             base::span<const uint8_t>(p));
        break;
      }
      case 1: {
        // ChildList: [parentId:4][addedCount:4]{[id:4]}...[removedCount:4]{[id:4]}...
        put_u32(p, target);
        put_u32(p, static_cast<uint32_t>(r->added_ids.size()));
        for (int64_t id : r->added_ids) {
          put_u32(p, static_cast<uint32_t>(id & 0xFFFFFFFF));
        }
        put_u32(p, static_cast<uint32_t>(r->removed_ids.size()));
        for (int64_t id : r->removed_ids) {
          put_u32(p, static_cast<uint32_t>(id & 0xFFFFFFFF));
        }
        writer_->WriteEvent(events::kMutationChildren, wid,
                             base::span<const uint8_t>(p));
        break;
      }
      case 2: {
        // Text: [nodeId:4][oldLen:2][old][newLen:2][new]
        put_u32(p, target);
        put_str16(p, r->old_text);
        put_str16(p, r->new_text);
        writer_->WriteEvent(events::kMutationText, wid,
                             base::span<const uint8_t>(p));
        break;
      }
      default: break;
    }
  }
}

void JuxDomClientImpl::OnDomEvent(int64_t node_id,
                                    const std::string& event_type,
                                    const std::vector<uint8_t>& payload) {
  VLOG(1) << "[jux-dom] browser OnDomEvent: type=" << event_type
            << " node_id=" << node_id
            << " payload_size=" << payload.size();
  if (!writer_ || !channel_) {
    LOG(WARNING) << "[jux-dom] OnDomEvent: writer/channel null, dropping";
    return;
  }

  // Java-facing payload format: [nodeId:4] + type-specific bytes
  // (matches EventDispatchLoop.java parsing). EventWriter prepends the
  // windowId automatically.
  uint32_t ev_type = EventTypeFromName(event_type);
  std::vector<uint8_t> out(4 + payload.size());
  uint32_t node32 = static_cast<uint32_t>(node_id & 0xFFFFFFFF);
  std::memcpy(out.data(), &node32, 4);
  if (!payload.empty()) {
    std::memcpy(out.data() + 4, payload.data(), payload.size());
  }

  writer_->WriteEvent(ev_type, channel_->window_id(),
                       base::span<const uint8_t>(out));

}

void JuxDomClientImpl::OnHitSpotRects(
    std::vector<mojom::HitSpotRectPtr> rects) {
#if BUILDFLAG(IS_WIN)
  if (!g_callback_hwnd) return;

  std::vector<jux::HitSpotRect> out;
  out.reserve(rects.size());
  for (const auto& r : rects) {
    if (!r) continue;
    jux::HitSpotRect s;
    s.code = r->code;
    s.x = r->x;
    s.y = r->y;
    s.w = r->w;
    s.h = r->h;
    out.push_back(s);
  }
  jux::SetChromeHitSpots(g_callback_hwnd, out.data(), out.size());
#endif
}

namespace {
void PutU32(std::vector<uint8_t>& b, uint32_t v) {
  b.push_back(static_cast<uint8_t>(v));
  b.push_back(static_cast<uint8_t>(v >> 8));
  b.push_back(static_cast<uint8_t>(v >> 16));
  b.push_back(static_cast<uint8_t>(v >> 24));
}
void PutF32(std::vector<uint8_t>& b, float f) {
  uint32_t bits;
  std::memcpy(&bits, &f, 4);
  PutU32(b, bits);
}
void PutLenStr(std::vector<uint8_t>& b, const std::string& s) {
  PutU32(b, static_cast<uint32_t>(s.size()));
  b.insert(b.end(), s.begin(), s.end());
}
void PutU16(std::vector<uint8_t>& b, uint16_t v) {
  b.push_back(static_cast<uint8_t>(v));
  b.push_back(static_cast<uint8_t>(v >> 8));
}
void PutStr16(std::vector<uint8_t>& b, const std::string& s) {
  uint16_t n = static_cast<uint16_t>(s.size() > 0xFFFFu ? 0xFFFFu : s.size());
  PutU16(b, n);
  b.insert(b.end(), s.begin(), s.begin() + n);
}
}  // namespace

void JuxDomClientImpl::OnContextMenu(double x, double y, uint32_t flags,
                                     const std::string& link,
                                     const std::string& src,
                                     const std::string& selection) {
  if (!writer_ || !channel_) {
    return;
  }
  // Payload (after windowId): [menuId:4][x:f32][y:f32][flags:4]
  // [linkLen:4][link][srcLen:4][src][selLen:4][sel]
  std::vector<uint8_t> p;
  PutU32(p, next_menu_id_++);
  PutF32(p, static_cast<float>(x));
  PutF32(p, static_cast<float>(y));
  PutU32(p, flags);
  PutLenStr(p, link);
  PutLenStr(p, src);
  PutLenStr(p, selection);
  writer_->WriteEvent(events::kContextMenuRequested, channel_->window_id(),
                      base::span<const uint8_t>(p));
}

void JuxDomClientImpl::OnTooltipChanged(const std::string& text) {
  if (!writer_ || !channel_) {
    return;
  }
  // Payload (after windowId): [textLen:4][text]
  std::vector<uint8_t> p;
  PutLenStr(p, text);
  writer_->WriteEvent(events::kTooltipChanged, channel_->window_id(),
                      base::span<const uint8_t>(p));
}

void JuxDomClientImpl::OnSelectPopup(uint32_t popup_id, uint32_t flags,
                                     int32_t selected_index, double x, double y,
                                     double w, double h,
                                     std::vector<mojom::JuxSelectItemPtr> items) {
  if (!writer_ || !channel_) {
    return;
  }
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  // If this is the off-screen print preview, the engine renders the drop-down
  // itself with Skia (Blink's native popup can't composite there) and composites
  // it over the modal. Hand the options off; if it takes over, we're done.
  {
    std::vector<DropdownOption> opts;
    opts.reserve(items.size());
    for (const auto& it : items) {
      if (!it) {
        continue;
      }
      opts.push_back(DropdownOption{it->label, it->group, it->enabled});
    }
    if (OpenPreviewSelectDropdown(channel_->window_id(), popup_id, flags,
                                  selected_index, x, y, w, h, std::move(opts))) {
      return;
    }
  }
#endif
  // The options list can exceed the ring slot, so it rides a temp file (Java's
  // readSelectItemsFile reads then deletes it). File format:
  //   [count:4]{[labelLen:2][label][valLen:2][val][enabled:1][groupLen:2][group]}
  std::vector<uint8_t> file;
  PutU32(file, static_cast<uint32_t>(items.size()));
  for (const auto& it : items) {
    if (!it) {
      continue;
    }
    PutStr16(file, it->label);
    PutStr16(file, it->value);
    file.push_back(it->enabled ? 1 : 0);
    PutStr16(file, it->group);
  }
  std::string path;
  base::FilePath tmp;
  if (base::CreateTemporaryFile(&tmp)) {
    base::WriteFile(tmp, file);
    path = tmp.AsUTF8Unsafe();
  }

  // Event payload (after windowId): [popupId:4][flags:4][selIndex:4]
  // [ax:f32][ay:f32][aw:f32][ah:f32][pathLen:4][path]
  std::vector<uint8_t> p;
  PutU32(p, popup_id);
  PutU32(p, flags);
  PutU32(p, static_cast<uint32_t>(selected_index));
  PutF32(p, static_cast<float>(x));
  PutF32(p, static_cast<float>(y));
  PutF32(p, static_cast<float>(w));
  PutF32(p, static_cast<float>(h));
  PutLenStr(p, path);
  writer_->WriteEvent(events::kSelectPopupOpen, channel_->window_id(),
                      base::span<const uint8_t>(p));
}

void JuxDomClientImpl::OnColorChooser(
    uint32_t chooser_id, uint32_t initial_rgba,
    const std::vector<uint32_t>& suggestions) {
  if (!writer_ || !channel_) {
    return;
  }
  // Payload (after windowId): [chooserId:4][initialRgba:4][suggCount:4]{[rgba:4]}
  std::vector<uint8_t> p;
  PutU32(p, chooser_id);
  PutU32(p, initial_rgba);
  PutU32(p, static_cast<uint32_t>(suggestions.size()));
  for (uint32_t rgba : suggestions) {
    PutU32(p, rgba);
  }
  writer_->WriteEvent(events::kColorChooserOpen, channel_->window_id(),
                      base::span<const uint8_t>(p));
}

void JuxDomClientImpl::OnJavaCall(int32_t java_id, int32_t call_id,
                                  const std::string& name, uint32_t argc,
                                  const std::vector<uint8_t>& args) {
  if (!writer_ || !channel_) {
    return;
  }
  // Payload (after windowId): [javaObjectId:4][callId:4][nameLen:4][name]
  // [argc:4]{args…}. Rides WriteEventLarge — call args can exceed one slot.
  // Mirrors NativeEventType.JS_CALLBACK / BlinkPage's java-object reflection;
  // callId correlates the JS_CALLBACK_RESULT that settles the JS promise.
  std::vector<uint8_t> p;
  PutU32(p, static_cast<uint32_t>(java_id));
  PutU32(p, static_cast<uint32_t>(call_id));
  PutU32(p, static_cast<uint32_t>(name.size()));
  p.insert(p.end(), name.begin(), name.end());
  PutU32(p, argc);
  p.insert(p.end(), args.begin(), args.end());
  writer_->WriteEventLarge(events::kJsCallback, channel_->window_id(),
                           base::span<const uint8_t>(p));
}

}  // namespace jux
