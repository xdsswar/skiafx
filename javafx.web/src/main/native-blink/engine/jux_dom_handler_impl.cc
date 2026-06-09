// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxDomHandlerImpl — renderer-side DOM bridge using real Blink APIs.

#ifdef UNSAFE_BUFFERS_BUILD
#pragma allow_unsafe_buffers
#endif

#include "jux/jux_dom_handler_impl.h"

#include <tuple>
#include <utility>

#include "base/functional/bind.h"
#include "base/location.h"
#include "base/logging.h"
#include "base/strings/utf_string_conversions.h"
#include "base/task/single_thread_task_runner.h"
#include "base/time/time.h"
#include "content/public/renderer/render_frame.h"
#include "printing/buildflags/buildflags.h"
#include "third_party/blink/public/common/associated_interfaces/associated_interface_registry.h"
#include "third_party/blink/public/platform/web_string.h"
#include "third_party/blink/public/web/web_document.h"
#include "third_party/blink/public/web/web_element.h"
#include "third_party/blink/public/web/web_local_frame.h"
#include "third_party/blink/public/web/web_node.h"

// Blink renderer/core — real DOM APIs so we can manipulate the tree
// and register native event listeners without injecting JavaScript.
#include "third_party/blink/renderer/core/dom/document.h"
#include "third_party/blink/renderer/core/dom/element.h"
#include "third_party/blink/renderer/core/dom/events/event.h"
#include "third_party/blink/renderer/core/dom/events/event_path.h"
#include "third_party/blink/renderer/core/dom/events/event_target.h"
#include "third_party/blink/renderer/core/dom/events/node_event_context.h"
#include "third_party/blink/renderer/core/dom/events/native_event_listener.h"
#include "third_party/blink/renderer/core/dom/node.h"
#include "third_party/blink/renderer/core/dom/shadow_root.h"
#include "third_party/blink/renderer/core/dom/text.h"
#include "third_party/blink/renderer/core/editing/editing_utilities.h"
#include "third_party/blink/renderer/core/editing/frame_selection.h"
#include "third_party/blink/renderer/core/event_type_names.h"
#include "third_party/blink/renderer/core/events/keyboard_event.h"
#include "third_party/blink/renderer/core/events/mouse_event.h"
#include "third_party/blink/renderer/core/frame/local_frame.h"
#include "third_party/blink/renderer/core/css/css_style_declaration.h"
#include "third_party/blink/renderer/bindings/core/v8/v8_mutation_observer_init.h"
#include "third_party/blink/renderer/core/dom/mutation_observer.h"
#include "third_party/blink/renderer/core/dom/static_node_list.h"
#include "base/strings/stringprintf.h"
#include "third_party/blink/renderer/core/html/forms/html_input_element.h"
#include "third_party/blink/renderer/core/html/forms/html_opt_group_element.h"
#include "third_party/blink/renderer/core/html/forms/html_option_element.h"
#include "third_party/blink/renderer/core/html/forms/html_select_element.h"
#include "third_party/blink/renderer/core/html/forms/text_control_element.h"
#include "third_party/blink/renderer/core/html/html_element.h"
#include "third_party/blink/renderer/core/html/html_head_element.h"
#include "third_party/blink/renderer/core/html/html_style_element.h"
#include "third_party/blink/renderer/core/html_names.h"
#include "third_party/blink/renderer/core/execution_context/execution_context.h"
#include "third_party/blink/renderer/core/geometry/dom_rect.h"
#include "third_party/blink/public/platform/task_type.h"
#include "third_party/blink/renderer/platform/bindings/exception_state.h"
#include "third_party/blink/renderer/platform/heap/garbage_collected.h"
#include "third_party/blink/renderer/platform/wtf/text/atomic_string.h"
#include "third_party/blink/renderer/platform/wtf/text/wtf_string.h"

namespace jux {

namespace {
// "#rrggbb" → 0xRRGGBBAA (alpha forced opaque, matching the Java rgba packing
// in ColorChooser). Non-hex input falls back to opaque black.
uint32_t ParseHexColor(const blink::String& value) {
  std::string s = value.Utf8();
  auto hex = [](char c) -> int {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return 0;
  };
  int r = 0, g = 0, b = 0;
  if (s.size() >= 7 && s[0] == '#') {
    r = hex(s[1]) * 16 + hex(s[2]);
    g = hex(s[3]) * 16 + hex(s[4]);
    b = hex(s[5]) * 16 + hex(s[6]);
  }
  return (static_cast<uint32_t>(r) << 24) | (static_cast<uint32_t>(g) << 16) |
         (static_cast<uint32_t>(b) << 8) | 0xFFu;
}
}  // namespace

// ---------------------------------------------------------------------------
// JuxEventForwarder — GC-allocated EventListener that forwards each DOM
// event back to the JuxDomHandlerImpl, which in turn ships it over Mojo
// to the browser process.
// ---------------------------------------------------------------------------
class JuxEventForwarder : public blink::NativeEventListener {
 public:
  JuxEventForwarder(JuxDomHandlerImpl* handler,
                     int64_t node_id,
                     std::string event_type)
      : handler_(handler),
        node_id_(node_id),
        event_type_(std::move(event_type)) {}

  void Invoke(blink::ExecutionContext* /*context*/,
              blink::Event* event) override {
    VLOG(1) << "[jux-dom] Invoke: event_type=" << event_type_
              << " node_id=" << node_id_
              << " handler_=" << (handler_ ? "set" : "null")
              << " event=" << (event ? event->type().Utf8() : "null");
    if (!handler_ || !event) return;

    // Build a type-specific payload matching the layouts that the
    // browser-side JuxDomClientImpl (and ultimately EventDispatchLoop
    // on the Java side) already parse.
    std::vector<uint8_t> payload = EncodePayload(event);
    handler_->ForwardDomEvent(node_id_, event_type_, payload);
  }

  // Called when the handler is destroyed — breaks the back-reference
  // so a late-firing event doesn't touch freed memory.
  void Detach() { handler_ = nullptr; }

 private:
  // Builds the per-event-type payload. Mirrors the Java
  // EventDispatchLoop switch cases for DOM_* events — the Blink side
  // is the producer, the Java side is the consumer, they must stay
  // in sync.
  std::vector<uint8_t> EncodePayload(blink::Event* event) {
    // All DOM_* events on the Java side expect at minimum:
    //   [nodeId:4] — prepended here at the front of the payload.
    // The browser-side JuxDomClientImpl::OnDomEvent further prepends
    // the windowId before writing to the ring, so we do NOT duplicate
    // it here.
    std::vector<uint8_t> out;
    auto put_u32 = [&out](uint32_t v) {
      out.push_back(static_cast<uint8_t>(v & 0xFF));
      out.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
      out.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
      out.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
    };
    auto put_f32 = [&put_u32](float v) {
      uint32_t bits;
      std::memcpy(&bits, &v, sizeof(bits));
      put_u32(bits);
    };

    put_u32(static_cast<uint32_t>(node_id_ & 0xFFFFFFFF));  // [nodeId:4]

    // Mouse events: [x:4(f32)][y:4(f32)][button:4]
    if (auto* m = blink::DynamicTo<blink::MouseEvent>(event)) {
      put_f32(static_cast<float>(m->offsetX()));
      put_f32(static_cast<float>(m->offsetY()));
      // MouseEvent::button() returns 0=left, 1=middle, 2=right.
      // Jux engine convention (see jux_command_dispatch.cc): the
      // browser side uses 0=left, 1=right, 2=middle. Map here.
      uint32_t raw = static_cast<uint32_t>(m->button());
      uint32_t mapped = (raw == 1) ? 2u : (raw == 2 ? 1u : raw);
      put_u32(mapped);
      return out;
    }

    // Keyboard events: [keyCode:4][modifiers:4][repeat:1]
    if (auto* k = blink::DynamicTo<blink::KeyboardEvent>(event)) {
      put_u32(static_cast<uint32_t>(k->keyCode()));
      uint32_t mods = 0;
      if (k->shiftKey()) mods |= 0x01;
      if (k->ctrlKey())  mods |= 0x02;
      if (k->altKey())   mods |= 0x04;
      if (k->metaKey())  mods |= 0x08;
      put_u32(mods);
      out.push_back(k->repeat() ? uint8_t{1} : uint8_t{0});
      return out;
    }

    // Default: no extra payload beyond the nodeId.
    return out;
  }

  // raw_ptr — not GarbageCollected. The handler clears this via
  // Detach() when it is destroyed.
  raw_ptr<JuxDomHandlerImpl> handler_;
  int64_t node_id_;
  std::string event_type_;
};

// ---------------------------------------------------------------------------
// JuxDocListener — document-level listener for `contextmenu` (right-click) and
// `mouseover` (tooltip). Unlike JuxEventForwarder it carries rich, dedicated
// payloads (link / media src / selection / editable for the menu; the nearest
// title for the tooltip), so it routes through dedicated handler methods rather
// than the generic OnDomEvent.
// ---------------------------------------------------------------------------
class JuxDocListener : public blink::NativeEventListener {
 public:
  enum Mode { kContextMenu, kTooltip, kSelect };

  JuxDocListener(JuxDomHandlerImpl* handler, Mode mode)
      : handler_(handler), mode_(mode) {}

  void Invoke(blink::ExecutionContext* /*context*/,
              blink::Event* event) override {
    if (!handler_ || !event) return;
    switch (mode_) {
      case kContextMenu: handler_->HandleContextMenuEvent(event); break;
      case kTooltip:     handler_->HandleTooltipEvent(event); break;
      case kSelect:      handler_->HandleSelectMouseDown(event); break;
    }
  }

  // Breaks the back-reference so a late-firing event can't touch freed state.
  void Detach() { handler_ = nullptr; }

 private:
  raw_ptr<JuxDomHandlerImpl> handler_;
  Mode mode_;
};

// ---------------------------------------------------------------------------
// JuxMutationDelegate — receives MutationObserver batches and hands them
// to JuxDomHandlerImpl for encoding + forwarding to the browser.
// ---------------------------------------------------------------------------
class JuxMutationDelegate : public blink::MutationObserver::Delegate {
 public:
  JuxMutationDelegate(JuxDomHandlerImpl* handler,
                       blink::ExecutionContext* context)
      : handler_(handler), context_(context) {}

  blink::ExecutionContext* GetExecutionContext() const override {
    return context_.Get();
  }

  void Deliver(const blink::MutationRecordVector& records,
               blink::MutationObserver& /*observer*/) override {
    if (!handler_) return;
    handler_->ForwardMutations(records);
  }

  void Trace(blink::Visitor* visitor) const override {
    visitor->Trace(context_);
    blink::MutationObserver::Delegate::Trace(visitor);
  }

  void Detach() { handler_ = nullptr; }

 private:
  raw_ptr<JuxDomHandlerImpl> handler_;
  blink::WeakMember<blink::ExecutionContext> context_;
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

namespace {

// Wraps a C++ std::string into a Blink WTF String.
blink::String ToWtf(const std::string& s) {
  return blink::String::FromUTF8(s);
}

// Wraps a C++ std::string into an AtomicString (for attribute names,
// tag names, event type names).
blink::AtomicString ToAtomic(const std::string& s) {
  return blink::AtomicString(blink::String::FromUTF8(s));
}

}  // namespace

// ---------------------------------------------------------------------------
// JuxDomHandlerImpl lifecycle + binding
// ---------------------------------------------------------------------------

JuxDomHandlerImpl::JuxDomHandlerImpl(content::RenderFrame* render_frame)
    : content::RenderFrameObserver(render_frame) {
  VLOG(1) << "[jux-dom] JuxDomHandlerImpl created (renderer) for frame="
            << render_frame;
  render_frame->GetAssociatedInterfaceRegistry()
      ->AddInterface<mojom::JuxDomHandler>(base::BindRepeating(
          &JuxDomHandlerImpl::BindReceiver, base::Unretained(this)));
}

JuxDomHandlerImpl::~JuxDomHandlerImpl() {
  // Break back-references in all registered listeners so any event
  // that fires between destruction and GC reclaim does nothing.
  for (auto& [key, fwd] : listeners_) {
    if (fwd) fwd->Detach();
  }
}

void JuxDomHandlerImpl::BindReceiver(
    mojo::PendingAssociatedReceiver<mojom::JuxDomHandler> receiver) {
  VLOG(1) << "[jux-dom] BindReceiver called (renderer)";
  receiver_.reset();
  receiver_.Bind(std::move(receiver));
}

void JuxDomHandlerImpl::OnDestruct() {
  delete this;
}

void JuxDomHandlerImpl::ScriptedPrint(bool user_initiated) {
  if (client_.is_bound()) {
    client_->OnScriptedPrint(user_initiated);
  }
}

void JuxDomHandlerImpl::DidCreateNewDocument() {
  // Cross-document navigation (or initial about:blank → real page).
  // Drop every reference we hold to Blink nodes from the prior document
  // so they can be collected, and detach any live listeners + mutation
  // observer so they can't fire against dead nodes.
  VLOG(1) << "[jux-dom] DidCreateNewDocument — resetting per-document "
            << "state (" << listeners_.size() << " listeners, "
            << node_map_.size() << " nodes)";
  ResetPerDocumentState();
  // Re-install document-level context-menu/tooltip listeners on the new doc.
  EnsureDocListeners();
}

void JuxDomHandlerImpl::ResetPerDocumentState() {
  // Detach native event listeners so any late Blink dispatch becomes
  // a no-op instead of touching our freed/unbound state.
  for (auto& [key, fwd] : listeners_) {
    if (fwd) fwd->Detach();
  }
  listeners_.clear();

  // Drop live JS object refs — the old document's V8 context is gone, so the
  // ids Java holds are dead. (Java's JSObject wrappers still fire JsRelease on
  // GC, which then no-ops on the missing id.)
  js_objects_.clear();
  next_js_id_ = 1;

  // Drop the host proxies built for Java objects exposed to the old document —
  // their V8 context is gone. The Java-object table itself lives on BlinkPage
  // (browser side) and is unaffected; a re-expose rebuilds the proxy.
  java_proxies_.clear();

  // Abandon any in-flight Java-call promises: their resolvers belong to the old
  // context. A late ResolveJavaCall then no-ops on the missing call_id. (JS that
  // was awaiting them is gone with the document.)
  pending_java_calls_.clear();

  // Disconnect and drop the mutation observer. A fresh one will be
  // installed by the next RequestDomTree call.
  if (mutation_observer_) {
    mutation_observer_->disconnect();
  }
  mutation_observer_.Clear();
  if (mutation_delegate_) {
    mutation_delegate_->Detach();
  }
  mutation_delegate_.Clear();

  // Drop the document-level context-menu/tooltip listeners; a fresh set is
  // installed by EnsureDocListeners against the new document.
  if (context_listener_) {
    context_listener_->Detach();
  }
  context_listener_.Clear();
  if (tooltip_listener_) {
    tooltip_listener_->Detach();
  }
  tooltip_listener_.Clear();
  if (select_listener_) {
    select_listener_->Detach();
  }
  select_listener_.Clear();
  open_selects_.clear();
  open_color_inputs_.clear();
  doc_listeners_installed_ = false;
  last_tooltip_.clear();

  // Drop node / stylesheet maps — indices from the prior walk are no
  // longer valid in the new document.
  node_map_.clear();
  stylesheet_map_.clear();
  next_id_ = 1;

  // Hit-spot node subscriptions reference Persistent<Element> from the
  // prior document — drop them so GC can collect and a Java re-push
  // after the next RequestDomTree walks against fresh ids.
  hit_spot_nodes_.clear();
  last_rects_.clear();
}

void JuxDomHandlerImpl::SetClient(
    mojo::PendingRemote<mojom::JuxDomClient> client) {
  VLOG(1) << "[jux-dom] SetClient called (renderer) — binding reply remote";
  client_.reset();
  client_.Bind(std::move(client));
  // Now that the client pipe is bound, install the document-level listeners
  // (right-click / tooltip) on the current document.
  EnsureDocListeners();
}

void JuxDomHandlerImpl::ForwardDomEvent(
    int64_t node_id,
    const std::string& event_type,
    const std::vector<uint8_t>& payload) {
  if (!client_.is_bound()) {
    LOG(WARNING) << "[jux-dom] ForwardDomEvent: client_ not bound, "
                 << "dropping " << event_type
                 << " for node_id=" << node_id;
    return;
  }
  client_->OnDomEvent(node_id, event_type, payload);
}

void JuxDomHandlerImpl::EnsureDocListeners() {
  if (doc_listeners_installed_ || !client_.is_bound()) {
    return;
  }
  blink::Document* doc = GetDocument();
  if (!doc) {
    return;
  }
  tooltip_listener_ =
      blink::MakeGarbageCollected<JuxDocListener>(this,
                                                  JuxDocListener::kTooltip);
  select_listener_ =
      blink::MakeGarbageCollected<JuxDocListener>(this, JuxDocListener::kSelect);
  // No `contextmenu` DOM listener: the context menu is driven by the browser
  // delegate's HandleContextMenu, which Chromium calls ONLY when the page didn't
  // preventDefault() — so a page with its own context menu suppresses ours
  // correctly (this renderer listener would fire regardless and break that).
  doc->addEventListener(blink::event_type_names::kMouseover, tooltip_listener_,
                        /*use_capture=*/false);
  // Capture-phase mousedown: when the app overrides select/color we intercept the
  // <select>/<input type=color> click here (preventDefault + surface to Java);
  // otherwise we leave it alone so Blink shows its own native page-popup.
  doc->addEventListener(blink::event_type_names::kMousedown, select_listener_,
                        /*use_capture=*/true);
  doc_listeners_installed_ = true;
}

void JuxDomHandlerImpl::HandleContextMenuEvent(blink::Event* event) {
  if (!client_.is_bound() || !event) {
    return;
  }
  auto* mouse = blink::DynamicTo<blink::MouseEvent>(event);
  double x = mouse ? mouse->clientX() : 0;
  double y = mouse ? mouse->clientY() : 0;

  blink::Node* target = nullptr;
  if (event->target()) {
    target = event->target()->ToNode();
  }

  std::string link;
  std::string src;
  bool editable = false;
  for (blink::Node* n = target; n; n = n->parentNode()) {
    auto* el = blink::DynamicTo<blink::Element>(n);
    if (!el) {
      continue;
    }
    // Nearest enclosing link.
    if (link.empty() &&
        (el->HasTagName(blink::html_names::kATag) ||
         el->HasTagName(blink::html_names::kAreaTag)) &&
        el->FastHasAttribute(blink::html_names::kHrefAttr)) {
      link = el->GetDocument()
                 .CompleteURL(el->FastGetAttribute(blink::html_names::kHrefAttr))
                 .GetString()
                 .Utf8();
    }
    // Nearest enclosing image / media source.
    if (src.empty() &&
        (el->HasTagName(blink::html_names::kImgTag) ||
         el->HasTagName(blink::html_names::kVideoTag) ||
         el->HasTagName(blink::html_names::kAudioTag))) {
      const blink::AtomicString& s =
          el->FastGetAttribute(blink::html_names::kSrcAttr);
      if (!s.empty()) {
        src = el->GetDocument().CompleteURL(s).GetString().Utf8();
      }
    }
  }
  if (target) {
    editable = blink::IsEditable(*target);
  }

  std::string selection;
  if (blink::Document* doc = GetDocument()) {
    if (blink::LocalFrame* frame = doc->GetFrame()) {
      selection = frame->Selection().SelectedText().Utf8();
    }
  }

  uint32_t flags = editable ? 0x1u : 0x0u;
  client_->OnContextMenu(x, y, flags, link, src, selection);
}

void JuxDomHandlerImpl::HandleTooltipEvent(blink::Event* event) {
  if (!client_.is_bound() || !event) {
    return;
  }
  blink::Node* target = nullptr;
  if (event->target()) {
    target = event->target()->ToNode();
  }
  std::string title;
  for (blink::Node* n = target; n; n = n->parentNode()) {
    auto* el = blink::DynamicTo<blink::Element>(n);
    if (!el) {
      continue;
    }
    const blink::AtomicString& t =
        el->FastGetAttribute(blink::html_names::kTitleAttr);
    if (!t.empty()) {
      title = t.Utf8();
      break;
    }
  }
  if (title != last_tooltip_) {
    last_tooltip_ = title;
    client_->OnTooltipChanged(title);
  }
}

void JuxDomHandlerImpl::HandleSelectMouseDown(blink::Event* event) {
  if (!client_.is_bound() || !event) {
    return;
  }
  auto* mouse = blink::DynamicTo<blink::MouseEvent>(event);
  if (mouse && mouse->button() != 0) {
    return;  // only the primary (left) button opens a select/color control
  }
  blink::Node* target =
      event->target() ? event->target()->ToNode() : nullptr;

  // Find the enclosing <select> or <input type=color> (whichever is nearer).
  blink::HTMLSelectElement* select = nullptr;
  blink::HTMLInputElement* color_input = nullptr;
  for (blink::Node* n = target; n; n = n->parentNode()) {
    if (auto* s = blink::DynamicTo<blink::HTMLSelectElement>(n)) {
      select = s;
      break;
    }
    if (auto* in = blink::DynamicTo<blink::HTMLInputElement>(n)) {
      if (blink::EqualIgnoringAsciiCase(
              in->FastGetAttribute(blink::html_names::kTypeAttr), "color")) {
        color_input = in;
        break;
      }
    }
  }

  // Controls in the print-preview UI (and any custom element) live inside nested
  // shadow DOM, so the document-level listener sees `target` retargeted to the
  // outermost host (e.g. <PRINT-PREVIEW-APP>) and the ancestor walk above finds
  // nothing. Fall back to the composed event path, which includes shadow nodes.
  if (!select && !color_input && event->HasEventPath()) {
    const blink::EventPath& path = event->GetEventPath();
    for (blink::wtf_size_t i = 0; i < path.size(); ++i) {
      blink::Node& n = path[i].GetNode();
      if (auto* s = blink::DynamicTo<blink::HTMLSelectElement>(&n)) {
        select = s;
        break;
      }
      if (auto* in = blink::DynamicTo<blink::HTMLInputElement>(&n)) {
        if (blink::EqualIgnoringAsciiCase(
                in->FastGetAttribute(blink::html_names::kTypeAttr), "color")) {
          color_input = in;
          break;
        }
      }
    }
  }

  // skia-fx DIAGNOSTIC (temporary, capped): log EVERY mousedown that reaches this
  // renderer — does it arrive at all (input routing), what element is the target
  // (selects live in custom-element shadow DOM, so the retargeted target is the
  // HOST, e.g. PRINT-PREVIEW-SETTINGS-SELECT, not a <select> the ancestor walk
  // finds), and did we find an enclosing control.
  {
    static int dbg = 0;
    if (dbg < 40) {
      ++dbg;
      std::string tag = target ? target->nodeName().Utf8() : std::string("(null)");
      VLOG(1) << "[jux-dom] mousedown target=<" << tag << "> select="
                << (select != nullptr) << " color=" << (color_input != nullptr)
                << " select_overridden=" << select_overridden_;
    }
  }

  // Native by default: only intercept when the app has overridden the control.
  // Otherwise leave the event alone so Blink opens its own native page-popup
  // (positioned correctly via the window-origin sync). When overridden we
  // preventDefault (suppress the native popup) and surface the request to Java.

  // <input type=color>: surface a color chooser only if overridden.
  if (color_input && !color_input->IsDisabledFormControl()) {
    if (!color_overridden_) {
      return;  // let Blink show its native colour page-popup
    }
    event->preventDefault();
    uint32_t chooser_id = next_popup_id_++;
    open_color_inputs_[chooser_id] = color_input;
    client_->OnColorChooser(chooser_id, ParseHexColor(color_input->Value()),
                            /*suggestions=*/{});
    return;
  }

  if (!select || select->IsDisabledFormControl()) {
    return;
  }
  bool select_override = select_overridden_;
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  // The off-screen print preview always overrides <select>: its native Blink
  // page-popup can't composite (the preview WebContents is hidden + captured), so
  // the engine draws the drop-down itself with Skia. Detected by document URL so
  // it needs no browser-side timing handshake.
  if (!select_override) {
    blink::Document* d = GetDocument();
    if (d && d->Url().ProtocolIs("chrome") && d->Url().Host() == "print") {
      select_override = true;
    }
  }
#endif
  if (!select_override) {
    return;  // let Blink show its native <select> page-popup
  }

  // Overridden: suppress the engine's default popup; the app shows its own list.
  event->preventDefault();

  // Gather options (skipping <optgroup> headers but tracking their label).
  std::vector<mojom::JuxSelectItemPtr> items;
  blink::String group;
  for (const auto& member : select->GetListItems()) {
    blink::HTMLElement* el = member.Get();
    if (auto* og = blink::DynamicTo<blink::HTMLOptGroupElement>(el)) {
      group = og->GroupLabelText();
    } else if (auto* opt = blink::DynamicTo<blink::HTMLOptionElement>(el)) {
      auto item = mojom::JuxSelectItem::New();
      item->label = opt->DisplayLabel().Utf8();
      item->value = opt->value().Utf8();
      item->enabled = !opt->IsDisabledFormControl();
      item->group = group.Utf8();
      items.push_back(std::move(item));
    }
  }

  blink::DOMRect* rect = select->GetBoundingClientRect();
  double x = rect ? rect->x() : 0;
  double y = rect ? rect->y() : 0;
  double w = rect ? rect->width() : 0;
  double h = rect ? rect->height() : 0;

  uint32_t popup_id = next_popup_id_++;
  open_selects_[popup_id] = select;
  uint32_t flags = select->IsMultiple() ? 0x1u : 0x0u;
  client_->OnSelectPopup(popup_id, flags, select->selectedIndex(), x, y, w, h,
                         std::move(items));
}

void JuxDomHandlerImpl::SetPopupOverrides(bool select_overridden,
                                          bool color_overridden) {
  select_overridden_ = select_overridden;
  color_overridden_ = color_overridden;
}

void JuxDomHandlerImpl::SelectPopupResponse(
    uint32_t popup_id, const std::vector<int32_t>& indices) {
  auto it = open_selects_.find(popup_id);
  if (it == open_selects_.end()) {
    return;
  }
  auto* select = blink::DynamicTo<blink::HTMLSelectElement>(it->second.Get());
  open_selects_.erase(it);
  if (!select) {
    return;
  }
  // The app's indices are options-only; map them to GetListItems list indices
  // (which include <optgroup> entries) for the popup-result setters.
  blink::Vector<int> option_list_indices;
  const auto& list = select->GetListItems();
  for (blink::wtf_size_t li = 0; li < list.size(); ++li) {
    if (blink::IsA<blink::HTMLOptionElement>(list[li].Get())) {
      option_list_indices.push_back(static_cast<int>(li));
    }
  }
  if (select->IsMultiple()) {
    blink::Vector<int> sel;
    for (int32_t idx : indices) {
      if (idx >= 0 && idx < static_cast<int>(option_list_indices.size())) {
        sel.push_back(option_list_indices[idx]);
      }
    }
    select->SelectMultipleOptions(sel);
  } else if (!indices.empty()) {
    int32_t idx = indices[0];
    if (idx >= 0 && idx < static_cast<int>(option_list_indices.size())) {
      select->SelectOptionByPopup(option_list_indices[idx]);
    }
  }
}

void JuxDomHandlerImpl::ColorChooserResponse(uint32_t chooser_id, bool chosen,
                                             uint32_t rgba) {
  auto it = open_color_inputs_.find(chooser_id);
  if (it == open_color_inputs_.end()) {
    return;
  }
  auto* input = blink::DynamicTo<blink::HTMLInputElement>(it->second.Get());
  open_color_inputs_.erase(it);
  if (!input || !chosen) {
    return;  // cancelled → leave the value unchanged
  }
  int r = (rgba >> 24) & 0xFF;
  int g = (rgba >> 16) & 0xFF;
  int b = (rgba >> 8) & 0xFF;
  blink::String hex =
      blink::String::FromUTF8(base::StringPrintf("#%02x%02x%02x", r, g, b));
  input->SetValue(hex,
                  blink::TextFieldEventBehavior::kDispatchInputAndChangeEvent);
}

int64_t JuxDomHandlerImpl::LookupNodeId(const blink::Node* node) const {
  if (!node) return 0;
  // Linear scan of node_map_. A reverse map keyed by Node* isn't
  // allowed by the blink-gc plugin (raw pointers to GC types in a
  // non-heap collection), and HeapHashMap<WeakMember<Node>, int64_t>
  // requires GC-allocating this observer. The scan is O(n) but node
  // counts are bounded (documents rarely exceed a few thousand) and
  // mutation dispatch is sparse — measured cost is negligible.
  for (const auto& [id, persistent_node] : node_map_) {
    if (persistent_node.Get() == node) return id;
  }
  return 0;
}

void JuxDomHandlerImpl::ForwardMutations(
    const blink::MutationRecordVector& records) {
  if (!client_.is_bound() || records.empty()) return;

  std::vector<mojom::MutationRecordPtr> out;
  out.reserve(records.size());
  for (const auto& rec : records) {
    if (!rec) continue;
    blink::Node* target = rec->target();
    int64_t target_id = LookupNodeId(target);
    if (target_id == 0) {
      // Node not tracked — skip (Java doesn't know about it yet).
      continue;
    }

    auto m = mojom::MutationRecord::New();
    m->target_node_id = target_id;
    const blink::AtomicString& type = rec->type();

    if (type == "attributes") {
      m->type = 0;
      m->attribute_name = rec->attributeName().Utf8();
      m->old_value = rec->oldValue().Utf8();
      // Live value from the element (MutationRecord only carries old).
      if (auto* el = blink::DynamicTo<blink::Element>(target)) {
        m->new_value = el->getAttribute(rec->attributeName()).Utf8();
      }
    } else if (type == "childList") {
      m->type = 1;
      if (blink::StaticNodeList* added = rec->addedNodes()) {
        for (unsigned i = 0; i < added->length(); ++i) {
          int64_t aid = LookupNodeId(added->item(i));
          if (aid != 0) m->added_ids.push_back(aid);
        }
      }
      if (blink::StaticNodeList* removed = rec->removedNodes()) {
        for (unsigned i = 0; i < removed->length(); ++i) {
          int64_t rid = LookupNodeId(removed->item(i));
          if (rid != 0) m->removed_ids.push_back(rid);
        }
      }
      // If we resolved no ids at all, drop the record — nothing for
      // Java to do with it.
      if (m->added_ids.empty() && m->removed_ids.empty()) {
        continue;
      }
    } else if (type == "characterData") {
      m->type = 2;
      m->old_text = rec->oldValue().Utf8();
      m->new_text = target->nodeValue().Utf8();
    } else {
      continue;  // Unknown mutation type.
    }

    out.push_back(std::move(m));
  }

  if (!out.empty()) {
    client_->OnMutations(std::move(out));
  }
}

// ---------------------------------------------------------------------------
// Document helper
// ---------------------------------------------------------------------------

blink::Document* JuxDomHandlerImpl::GetDocument() {
  auto* frame = render_frame()->GetWebFrame();
  if (!frame) return nullptr;
  blink::WebDocument web_doc = frame->GetDocument();
  if (web_doc.IsNull()) return nullptr;
  return web_doc.Unwrap<blink::Document>();
}

blink::Node* JuxDomHandlerImpl::LookupNode(int64_t node_id) {
  auto it = node_map_.find(node_id);
  if (it == node_map_.end()) return nullptr;
  return it->second.Get();
}

blink::Element* JuxDomHandlerImpl::LookupElement(int64_t node_id) {
  blink::Node* node = LookupNode(node_id);
  if (!node) return nullptr;
  return blink::DynamicTo<blink::Element>(node);
}

std::string JuxDomHandlerImpl::ListenerKey(int64_t node_id,
                                            const std::string& event_type) {
  return std::to_string(node_id) + ":" + event_type;
}

// ---------------------------------------------------------------------------
// Tree walk (RequestDomTree)
// ---------------------------------------------------------------------------

void JuxDomHandlerImpl::RequestDomTree(RequestDomTreeCallback callback) {
  std::vector<mojom::DomNodePtr> nodes;
  auto* frame = render_frame()->GetWebFrame();
  if (!frame) {
    LOG(WARNING) << "[jux-dom] RequestDomTree: no WebFrame";
    std::move(callback).Run(std::move(nodes));
    return;
  }
  blink::WebDocument doc = frame->GetDocument();
  if (doc.IsNull()) {
    LOG(WARNING) << "[jux-dom] RequestDomTree: null WebDocument";
    std::move(callback).Run(std::move(nodes));
    return;
  }

  next_id_ = 1;
  node_map_.clear();
  WalkDocument(doc, &nodes);
  VLOG(1) << "[jux-dom] RequestDomTree: walked " << nodes.size()
            << " nodes (map size=" << node_map_.size() << ")";

  // Install the MutationObserver on the freshly-walked document so
  // post-walk changes (JS, parser, later mutations) flow back to Java.
  // We detach any previous observer to avoid duplicates on reload.
  blink::Document* blink_doc = doc.Unwrap<blink::Document>();
  if (blink_doc) {
    if (mutation_delegate_) mutation_delegate_->Detach();
    mutation_observer_.Clear();
    mutation_delegate_ = blink::MakeGarbageCollected<JuxMutationDelegate>(
        this, blink_doc->GetExecutionContext());
    mutation_observer_ = blink::MutationObserver::Create(
        mutation_delegate_.Get());
    auto* init = blink::MutationObserverInit::Create();
    init->setSubtree(true);
    init->setAttributes(true);
    init->setAttributeOldValue(true);
    init->setChildList(true);
    init->setCharacterData(true);
    init->setCharacterDataOldValue(true);
    mutation_observer_->observe(blink_doc, init, ASSERT_NO_EXCEPTION);
  }

  std::move(callback).Run(std::move(nodes));
}

void JuxDomHandlerImpl::WalkDocument(
    const blink::WebDocument& document,
    std::vector<mojom::DomNodePtr>* nodes_out) {
  blink::WebElement root = document.DocumentElement();
  if (root.IsNull()) return;
  WalkNode(root, /*parent_id=*/0, nodes_out);
}

void JuxDomHandlerImpl::WalkNode(
    const blink::WebNode& node,
    int64_t parent_id,
    std::vector<mojom::DomNodePtr>* nodes_out) {
  if (node.IsNull()) return;

  int64_t id = next_id_++;
  // Unwrap to the internal blink::Node and store it with a Persistent
  // so subsequent commands can look it up.
  blink::Node* blink_node = const_cast<blink::WebNode&>(node)
                                 .Unwrap<blink::Node>();
  if (!blink_node) return;
  node_map_[id] = blink::Persistent<blink::Node>(blink_node);

  auto dom = mojom::DomNode::New();
  dom->node_id = id;
  dom->parent_id = parent_id;
  dom->is_text = node.IsTextNode();

  if (node.IsElementNode()) {
    blink::WebElement elem = node.To<blink::WebElement>();
    dom->tag = elem.TagName().Utf8();
    dom->id_attr = elem.GetIdAttribute().Utf8();
    dom->class_attr = elem.GetAttribute("class").Utf8();
  } else if (node.IsTextNode()) {
    dom->text = node.NodeValue().Utf8();
  }

  nodes_out->push_back(std::move(dom));

  for (blink::WebNode child = node.FirstChild(); !child.IsNull();
       child = child.NextSibling()) {
    if (child.IsElementNode() || child.IsTextNode()) {
      WalkNode(child, id, nodes_out);
    }
  }
}

// ---------------------------------------------------------------------------
// Event listener registration (real Blink NativeEventListener)
// ---------------------------------------------------------------------------

void JuxDomHandlerImpl::AddEventListener(int64_t node_id,
                                           const std::string& event_type) {
  blink::Element* elem = LookupElement(node_id);
  if (!elem) {
    LOG(WARNING) << "[jux-dom] AddEventListener(" << event_type
                 << "): no element for id=" << node_id
                 << " (map size=" << node_map_.size() << ")";
    return;
  }
  std::string key = ListenerKey(node_id, event_type);
  if (listeners_.find(key) != listeners_.end()) {
    VLOG(1) << "[jux-dom] AddEventListener(" << event_type
              << "): already registered for id=" << node_id;
    return;
  }
  auto* forwarder = blink::MakeGarbageCollected<JuxEventForwarder>(
      this, node_id, event_type);
  elem->addEventListener(ToAtomic(event_type), forwarder,
                          /*use_capture=*/false);
  listeners_[key] = blink::Persistent<JuxEventForwarder>(forwarder);
  VLOG(1) << "[jux-dom] AddEventListener(" << event_type
            << ") registered on <" << elem->tagName().Utf8()
            << "> id=" << node_id;
}

void JuxDomHandlerImpl::RemoveEventListener(
    int64_t node_id,
    const std::string& event_type) {
  std::string key = ListenerKey(node_id, event_type);
  auto it = listeners_.find(key);
  if (it == listeners_.end()) return;
  blink::Element* elem = LookupElement(node_id);
  if (elem) {
    elem->removeEventListener(ToAtomic(event_type), it->second.Get(),
                               /*use_capture=*/false);
  }
  if (it->second) it->second->Detach();
  listeners_.erase(it);
}

// ---------------------------------------------------------------------------
// DOM manipulation (real Blink APIs)
// ---------------------------------------------------------------------------

void JuxDomHandlerImpl::CreateElement(int64_t node_id,
                                       const std::string& tag) {
  blink::Document* doc = GetDocument();
  if (!doc) return;
  // CreateRawElement with the XHTML namespace URI so the element factory
  // returns the proper HTMLElement subclass (HTMLDivElement,
  // HTMLButtonElement, HTMLInputElement, …) with default rendering and
  // behavior.
  //
  // The previous implementation passed an empty AtomicString as the
  // namespace, which dropped all elements onto the generic blink::Element
  // path. Generic elements live in the DOM but get no HTML default
  // styling, so JuxComponent-built subtrees were technically present
  // but rendered as nothing.
  blink::Element* elem = doc->CreateRawElement(
      blink::QualifiedName(blink::AtomicString(), ToAtomic(tag),
                            blink::html_names::xhtmlNamespaceURI),
      blink::CreateElementFlags::ByCreateElement());
  if (elem) {
    node_map_[node_id] = blink::Persistent<blink::Node>(elem);
  }
}

void JuxDomHandlerImpl::RemoveElement(int64_t node_id) {
  blink::Node* node = LookupNode(node_id);
  if (!node) return;
  if (blink::Node* parent = node->parentNode()) {
    parent->removeChild(node, ASSERT_NO_EXCEPTION);
  }
  node_map_.erase(node_id);
}

void JuxDomHandlerImpl::SetAttribute(int64_t node_id,
                                      const std::string& name,
                                      const std::string& value) {
  blink::Element* elem = LookupElement(node_id);
  if (!elem) return;
  elem->setAttribute(ToAtomic(name), ToWtf(value),
                     ASSERT_NO_EXCEPTION);
}

void JuxDomHandlerImpl::RemoveAttribute(int64_t node_id,
                                         const std::string& name) {
  blink::Element* elem = LookupElement(node_id);
  if (!elem) return;
  elem->removeAttribute(ToAtomic(name));
}

void JuxDomHandlerImpl::SetTextContent(int64_t node_id,
                                        const std::string& text) {
  blink::Node* node = LookupNode(node_id);
  if (!node) return;
  node->setTextContent(ToWtf(text));
}

void JuxDomHandlerImpl::SetInnerHtml(int64_t node_id,
                                      const std::string& html) {
  blink::Element* elem = LookupElement(node_id);
  if (!elem) return;
  // SetInnerHTMLWithoutTrustedTypes bypasses the Trusted-Types check
  // — safe here because the HTML comes from Java code, not untrusted
  // web input.
  elem->SetInnerHTMLWithoutTrustedTypes(ToWtf(html),
                                         ASSERT_NO_EXCEPTION);
}

void JuxDomHandlerImpl::AppendChild(int64_t parent_id, int64_t child_id) {
  blink::Node* parent = LookupNode(parent_id);
  blink::Node* child = LookupNode(child_id);
  if (!parent || !child) return;
  parent->appendChild(child, ASSERT_NO_EXCEPTION);
}

void JuxDomHandlerImpl::InsertBefore(int64_t parent_id, int64_t child_id,
                                      int64_t ref_id) {
  blink::Node* parent = LookupNode(parent_id);
  blink::Node* child = LookupNode(child_id);
  blink::Node* ref = (ref_id != 0) ? LookupNode(ref_id) : nullptr;
  if (!parent || !child) return;
  parent->insertBefore(child, ref, ASSERT_NO_EXCEPTION);
}

void JuxDomHandlerImpl::SetStyleProperty(int64_t node_id,
                                          const std::string& prop,
                                          const std::string& value) {
  blink::Element* elem = LookupElement(node_id);
  if (!elem) return;
  // Use the inline style declaration. Accessing `style` requires the
  // element to be an HTMLElement (or SVG); for most pages this holds.
  auto* html_elem = blink::DynamicTo<blink::HTMLElement>(elem);
  if (!html_elem) return;
  html_elem->style()->setProperty(elem->GetExecutionContext(),
                                   ToAtomic(prop), ToWtf(value),
                                   blink::String(),
                                   ASSERT_NO_EXCEPTION);
}

void JuxDomHandlerImpl::AddClass(int64_t node_id,
                                   const std::string& class_name) {
  blink::Element* elem = LookupElement(node_id);
  if (!elem) return;
  elem->classList().add({ToAtomic(class_name)},
                         ASSERT_NO_EXCEPTION);
}

void JuxDomHandlerImpl::RemoveClass(int64_t node_id,
                                     const std::string& class_name) {
  blink::Element* elem = LookupElement(node_id);
  if (!elem) return;
  elem->classList().remove({ToAtomic(class_name)},
                            ASSERT_NO_EXCEPTION);
}

// ---------------------------------------------------------------------------
// Stylesheets — a <style> element injected in <head> keyed by a numeric id.
// ---------------------------------------------------------------------------

void JuxDomHandlerImpl::AddStylesheet(uint32_t id, const std::string& css) {
  blink::Document* doc = GetDocument();
  if (!doc) return;
  blink::Element* existing = nullptr;
  auto it = stylesheet_map_.find(id);
  if (it != stylesheet_map_.end()) existing = it->second.Get();
  if (existing) {
    existing->setTextContent(ToWtf(css));
    return;
  }
  auto* style = blink::MakeGarbageCollected<blink::HTMLStyleElement>(*doc);
  style->setTextContent(ToWtf(css));
  if (blink::HTMLHeadElement* head = doc->head()) {
    head->appendChild(style, ASSERT_NO_EXCEPTION);
  }
  stylesheet_map_[id] = blink::Persistent<blink::Element>(style);
}

void JuxDomHandlerImpl::RemoveStylesheet(uint32_t id) {
  auto it = stylesheet_map_.find(id);
  if (it == stylesheet_map_.end()) return;
  if (blink::Element* style = it->second.Get()) {
    if (blink::Node* parent = style->parentNode()) {
      parent->removeChild(style, ASSERT_NO_EXCEPTION);
    }
  }
  stylesheet_map_.erase(it);
}

// ---------------------------------------------------------------------------
// Hit-spot node subscription (custom-chrome rect pipeline)
// ---------------------------------------------------------------------------

void JuxDomHandlerImpl::SetHitSpotNodes(
    std::vector<mojom::HitSpotNodePtr> nodes) {
  hit_spot_nodes_.clear();
  last_rects_.clear();
  VLOG(1) << "[jux-dom] SetHitSpotNodes count=" << nodes.size();
  for (const auto& n : nodes) {
    if (!n) continue;
    blink::Element* el = LookupElement(n->node_id);
    if (!el) {
      LOG(WARNING) << "[jux-dom] SetHitSpotNodes: node_id=" << n->node_id
                   << " not found (code=" << n->code << ")";
      continue;
    }
    hit_spot_nodes_.emplace_back(
        n->code, blink::Persistent<blink::Element>(el));
  }

  // Fire one immediate push so the browser has rects by the time the
  // user moves the mouse — the 16 ms tick is only for layout-reactive
  // updates.
  PushHitSpotRects();

  if (!hit_spot_poll_scheduled_ && !hit_spot_nodes_.empty()) {
    hit_spot_poll_scheduled_ = true;
    PostHitSpotPoll();
  }
}

void JuxDomHandlerImpl::PostHitSpotPoll() {
  if (hit_spot_nodes_.empty()) {
    hit_spot_poll_scheduled_ = false;
    return;
  }
  auto* rf = render_frame();
  if (!rf) {
    hit_spot_poll_scheduled_ = false;
    return;
  }
  scoped_refptr<base::SingleThreadTaskRunner> runner =
      rf->GetTaskRunner(blink::TaskType::kInternalDefault);
  if (!runner) {
    hit_spot_poll_scheduled_ = false;
    return;
  }
  runner->PostDelayedTask(
      FROM_HERE,
      base::BindOnce(&JuxDomHandlerImpl::OnHitSpotPollTick,
                     weak_factory_.GetWeakPtr()),
      base::Milliseconds(16));
}

void JuxDomHandlerImpl::OnHitSpotPollTick() {
  PushHitSpotRects();
  if (hit_spot_nodes_.empty()) {
    hit_spot_poll_scheduled_ = false;
    return;
  }
  PostHitSpotPoll();
}

// Drives Blink's :hover pseudo-class on the element registered at
// `code`. The browser calls this from its overlay-HWND WndProc when
// the cursor enters or leaves the overlay for HTMAXBUTTON (and
// potentially HTMINBUTTON / HTCLOSE when overlays for those codes
// are enabled). Node::SetHovered updates the real :hover state
// machine and triggers style recalc, so the page author's existing
// `.foo:hover {}` rules match without any JS, custom class, or
// attribute-selector workaround.
void JuxDomHandlerImpl::SetHitSpotHovered(uint32_t code, bool hovered) {
  for (const auto& entry : hit_spot_nodes_) {
    if (entry.first != code) continue;
    blink::Element* el = entry.second.Get();
    if (!el) return;
    el->SetHovered(hovered);
    return;
  }
}

void JuxDomHandlerImpl::PushHitSpotRects() {
  if (!client_.is_bound()) return;
  if (hit_spot_nodes_.empty()) return;

  std::vector<mojom::HitSpotRectPtr> out;
  out.reserve(hit_spot_nodes_.size());
  bool changed = false;

  for (const auto& entry : hit_spot_nodes_) {
    uint32_t code = entry.first;
    blink::Element* el = entry.second.Get();
    if (!el) continue;
    blink::DOMRect* rect = el->GetBoundingClientRect();
    if (!rect) continue;
    double x = rect->x();
    double y = rect->y();
    double w = rect->width();
    double h = rect->height();

    auto it = last_rects_.find(code);
    if (it == last_rects_.end() ||
        it->second.x != x || it->second.y != y ||
        it->second.w != w || it->second.h != h) {
      changed = true;
      last_rects_[code] = LastRect{x, y, w, h};
    }

    auto p = mojom::HitSpotRect::New();
    p->code = code;
    p->x = x;
    p->y = y;
    p->w = w;
    p->h = h;
    out.push_back(std::move(p));
  }

  if (changed) {
    client_->OnHitSpotRects(std::move(out));
  }
}

// ---------------------------------------------------------------------------
// JavaScript object bridge (netscape.javascript.JSObject).
//
// Each op runs synchronously in the frame's main-world V8 context and answers
// its Mojo reply with (tagged-value bytes, error). A non-empty error string is
// a JS exception → Java throws JSException. Live non-primitive results are kept
// alive in js_objects_ and released by Java's Cleaner (JsRelease).
// ---------------------------------------------------------------------------

namespace {
// Renders a caught V8 exception to a UTF-8 string for the Java JSException.
std::string DescribeJsException(v8::Isolate* isolate, v8::TryCatch* try_catch) {
  if (!try_catch->HasCaught()) {
    return "JavaScript error";
  }
  v8::Local<v8::Value> ex = try_catch->Exception();
  v8::String::Utf8Value text(isolate, ex);
  if (*text && text.length() > 0) {
    return std::string(*text, static_cast<size_t>(text.length()));
  }
  return "JavaScript exception";
}
}  // namespace

v8::Local<v8::Context> JuxDomHandlerImpl::MainWorldContext() {
  auto* frame = render_frame() ? render_frame()->GetWebFrame() : nullptr;
  if (!frame) {
    return v8::Local<v8::Context>();
  }
  return frame->MainWorldScriptContext();
}

bool JuxDomHandlerImpl::ResolveJsTarget(v8::Isolate* isolate,
                                        v8::Local<v8::Context> context,
                                        int32_t object_id,
                                        v8::Local<v8::Object>* out) {
  if (object_id == 0) {
    *out = context->Global();
    return true;
  }
  auto it = js_objects_.find(object_id);
  if (it == js_objects_.end()) {
    return false;
  }
  v8::Local<v8::Value> v = it->second.Get(isolate);
  if (v.IsEmpty() || !v->IsObject()) {
    return false;
  }
  *out = v.As<v8::Object>();
  return true;
}

int32_t JuxDomHandlerImpl::AssignJsObject(v8::Isolate* isolate,
                                          v8::Local<v8::Value> value) {
  int32_t id = next_js_id_++;
  js_objects_[id].Reset(isolate, value);
  return id;
}

v8::Local<v8::Value> JuxDomHandlerImpl::ResolveJsObject(v8::Isolate* isolate,
                                                        int32_t id) {
  auto it = js_objects_.find(id);
  if (it == js_objects_.end()) {
    return v8::Local<v8::Value>();
  }
  return it->second.Get(isolate);
}

namespace {
// Type tag for the handler pointer wrapped in v8::External inside proxy
// callback data. The embedder doesn't use distinct external-pointer tags.
constexpr v8::ExternalPointerTypeTag kJuxProxyTag =
    v8::kExternalPointerTypeTagDefault;

// Unpacks the [External(handler), Integer(java_id), …] binding array shared by
// every proxy callback. Returns false on any shape mismatch (never crashes on
// a tampered proxy).
bool UnpackProxyBinding(v8::Isolate* isolate, v8::Local<v8::Context> context,
                        v8::Local<v8::Value> data, JuxDomHandlerImpl** self_out,
                        int32_t* id_out) {
  if (data.IsEmpty() || !data->IsArray()) {
    return false;
  }
  v8::Local<v8::Array> arr = data.As<v8::Array>();
  v8::Local<v8::Value> ext;
  if (!arr->Get(context, 0).ToLocal(&ext) || !ext->IsExternal()) {
    return false;
  }
  v8::Local<v8::Value> idv;
  if (!arr->Get(context, 1).ToLocal(&idv) || !idv->IsInt32()) {
    return false;
  }
  auto* self = static_cast<JuxDomHandlerImpl*>(
      ext.As<v8::External>()->Value(kJuxProxyTag));
  if (!self) {
    return false;
  }
  *self_out = self;
  *id_out = idv.As<v8::Int32>()->Value();
  return true;
}
}  // namespace

void JuxDomHandlerImpl::DispatchJavaCall(
    v8::Isolate* isolate, v8::Local<v8::Context> context, int32_t java_id,
    const std::string& name,
    const v8::FunctionCallbackInfo<v8::Value>& info) {
  // Marshal each JS argument with the shared tagged codec. A JS object arg is
  // assigned an id (kept alive in js_objects_) and surfaces in Java as a live
  // JSObject — this is how a page hands a JS object to a Java callback.
  std::vector<uint8_t> args;
  const int argc = info.Length();
  for (int i = 0; i < argc; ++i) {
    std::vector<uint8_t> enc = EncodeJsValue(isolate, context, info[i], *this);
    args.insert(args.end(), enc.begin(), enc.end());
  }
  // Return a Promise settled later by ResolveJavaCall with the Java method's
  // return value (or its exception). `await app.foo()` therefore yields the
  // real Java result, yet the renderer never *blocks*: the promise is async, so
  // a JS→Java call can't deadlock against an in-flight Java→JS call.
  v8::Local<v8::Promise::Resolver> resolver;
  if (!client_ || !v8::Promise::Resolver::New(context).ToLocal(&resolver)) {
    // No browser pipe, or promise allocation failed: fall back to fire-and-
    // forget so JS never hangs (it just sees undefined).
    if (client_) {
      client_->OnJavaCall(java_id, 0, name, static_cast<uint32_t>(argc),
                          std::move(args));
    }
    info.GetReturnValue().SetUndefined();
    return;
  }
  const int32_t call_id = next_java_call_id_++;
  pending_java_calls_[call_id].Reset(isolate, resolver);
  client_->OnJavaCall(java_id, call_id, name, static_cast<uint32_t>(argc),
                      std::move(args));
  info.GetReturnValue().Set(resolver->GetPromise());
}

void JuxDomHandlerImpl::ResolveJavaCall(int32_t call_id, bool ok,
                                        const std::vector<uint8_t>& value,
                                        const std::string& error) {
  auto it = pending_java_calls_.find(call_id);
  if (it == pending_java_calls_.end()) {
    // Navigated away (table cleared) or a duplicate settle — nothing to do.
    return;
  }
  v8::Isolate* isolate = v8::Isolate::GetCurrent();
  v8::HandleScope handle_scope(isolate);
  v8::Local<v8::Promise::Resolver> resolver = it->second.Get(isolate);
  pending_java_calls_.erase(it);
  if (resolver.IsEmpty()) {
    return;
  }
  v8::Local<v8::Context> context = MainWorldContext();
  if (context.IsEmpty()) {
    return;
  }
  v8::Context::Scope context_scope(context);
  if (ok) {
    size_t consumed = 0;
    v8::Local<v8::Value> v = DecodeJsValue(isolate, context, value.data(),
                                           value.size(), &consumed, *this);
    if (v.IsEmpty()) {
      v = v8::Undefined(isolate);
    }
    std::ignore = resolver->Resolve(context, v);
  } else {
    v8::Local<v8::String> msg;
    if (!v8::String::NewFromUtf8(isolate, error.c_str(),
                                 v8::NewStringType::kNormal,
                                 static_cast<int>(error.size()))
             .ToLocal(&msg)) {
      msg = v8::String::Empty(isolate);
    }
    std::ignore = resolver->Reject(context, v8::Exception::Error(msg));
  }
  // The promise reactions run at the main thread's next microtask checkpoint,
  // which Blink performs at the end of this IPC task — no explicit checkpoint
  // (that could trip Blink's scoped-microtask policy).
}

// static
void JuxDomHandlerImpl::ProxyCall(
    const v8::FunctionCallbackInfo<v8::Value>& info) {
  v8::Isolate* isolate = info.GetIsolate();
  v8::HandleScope handle_scope(isolate);
  v8::Local<v8::Context> context = isolate->GetCurrentContext();
  JuxDomHandlerImpl* self = nullptr;
  int32_t java_id = 0;
  if (!UnpackProxyBinding(isolate, context, info.Data(), &self, &java_id)) {
    return;
  }
  // Empty name → invoke the Java object's functional-interface method.
  self->DispatchJavaCall(isolate, context, java_id, std::string(), info);
}

// static
void JuxDomHandlerImpl::BoundMethodCall(
    const v8::FunctionCallbackInfo<v8::Value>& info) {
  v8::Isolate* isolate = info.GetIsolate();
  v8::HandleScope handle_scope(isolate);
  v8::Local<v8::Context> context = isolate->GetCurrentContext();
  JuxDomHandlerImpl* self = nullptr;
  int32_t java_id = 0;
  if (!UnpackProxyBinding(isolate, context, info.Data(), &self, &java_id)) {
    return;
  }
  v8::Local<v8::Value> namev;
  if (!info.Data().As<v8::Array>()->Get(context, 2).ToLocal(&namev)) {
    return;
  }
  v8::String::Utf8Value utf8(isolate, namev);
  std::string name = (*utf8) ? std::string(*utf8, utf8.length()) : std::string();
  self->DispatchJavaCall(isolate, context, java_id, name, info);
}

// static
v8::Intercepted JuxDomHandlerImpl::ProxyNamedGet(
    v8::Local<v8::Name> property,
    const v8::PropertyCallbackInfo<v8::Value>& info) {
  // Only intercept plain string keys. Symbols (Symbol.toPrimitive, iterator,
  // toStringTag) and the JS-internal probes below must fall through to defaults
  // so promises (`then`), JSON.stringify, and string coercion behave normally
  // on the proxy instead of seeing a bound function for every name.
  if (!property->IsString()) {
    return v8::Intercepted::kNo;
  }
  v8::Isolate* isolate = info.GetIsolate();
  v8::Local<v8::Context> context = isolate->GetCurrentContext();
  v8::String::Utf8Value utf8(isolate, property);
  std::string name = (*utf8) ? std::string(*utf8, utf8.length()) : std::string();
  if (name.empty() || name == "then" || name == "toJSON" ||
      name == "toString" || name == "valueOf" || name == "constructor" ||
      name == "length" || name == "prototype" || name == "Symbol") {
    return v8::Intercepted::kNo;
  }
  JuxDomHandlerImpl* self = nullptr;
  int32_t java_id = 0;
  if (!UnpackProxyBinding(isolate, context, info.Data(), &self, &java_id)) {
    return v8::Intercepted::kNo;
  }
  // Self-contained binding for the bound method: handler + id + method name, so
  // a detached `var f = app.foo; f()` still reaches Java method "foo".
  v8::Local<v8::Array> data = v8::Array::New(isolate, 3);
  if (data->Set(context, 0,
                v8::External::New(isolate, self, kJuxProxyTag)).IsNothing() ||
      data->Set(context, 1, v8::Integer::New(isolate, java_id)).IsNothing() ||
      data->Set(context, 2, property).IsNothing()) {
    return v8::Intercepted::kNo;
  }
  v8::Local<v8::Function> fn;
  if (!v8::Function::New(context, &JuxDomHandlerImpl::BoundMethodCall, data)
           .ToLocal(&fn)) {
    return v8::Intercepted::kNo;
  }
  info.GetReturnValue().Set(fn);
  return v8::Intercepted::kYes;
}

v8::Local<v8::Value> JuxDomHandlerImpl::JavaProxy(v8::Isolate* isolate,
                                                  v8::Local<v8::Context> context,
                                                  int32_t java_id) {
  if (java_id == 0) {
    return v8::Null(isolate);
  }
  // Stable identity: window.app === window.app across reads.
  auto it = java_proxies_.find(java_id);
  if (it != java_proxies_.end()) {
    v8::Local<v8::Object> cached = it->second.Get(isolate);
    if (!cached.IsEmpty()) {
      return cached;
    }
  }
  // Binding shared by the call + named-get handlers (handler pointer + id).
  v8::Local<v8::Array> binding = v8::Array::New(isolate, 2);
  if (binding->Set(context, 0,
                   v8::External::New(isolate, this, kJuxProxyTag))
          .IsNothing() ||
      binding->Set(context, 1, v8::Integer::New(isolate, java_id))
          .IsNothing()) {
    return v8::Undefined(isolate);
  }
  v8::Local<v8::ObjectTemplate> tpl = v8::ObjectTemplate::New(isolate);
  // Callable: `app(arg)` invokes the Java object's functional-interface SAM
  // (method name ""), so apps can expose a Consumer/Function/Runnable lambda.
  tpl->SetCallAsFunctionHandler(&JuxDomHandlerImpl::ProxyCall, binding);
  // `app.foo` resolves to a bound function that calls Java method "foo".
  tpl->SetHandler(v8::NamedPropertyHandlerConfiguration(
      &JuxDomHandlerImpl::ProxyNamedGet, nullptr, nullptr, nullptr, nullptr,
      binding));
  v8::Local<v8::Object> proxy;
  if (!tpl->NewInstance(context).ToLocal(&proxy)) {
    return v8::Undefined(isolate);
  }
  java_proxies_[java_id].Reset(isolate, proxy);
  return proxy;
}

void JuxDomHandlerImpl::JsGetMember(int32_t object_id, const std::string& name,
                                    JsGetMemberCallback callback) {
  v8::Isolate* isolate = v8::Isolate::GetCurrent();
  v8::HandleScope handle_scope(isolate);
  v8::Local<v8::Context> context = MainWorldContext();
  if (context.IsEmpty()) {
    std::move(callback).Run({}, "no script context");
    return;
  }
  v8::Context::Scope context_scope(context);
  v8::Local<v8::Object> obj;
  if (!ResolveJsTarget(isolate, context, object_id, &obj)) {
    std::move(callback).Run({}, "invalid JS object");
    return;
  }
  v8::Local<v8::String> key;
  if (!v8::String::NewFromUtf8(isolate, name.c_str(),
                               v8::NewStringType::kNormal,
                               static_cast<int>(name.size()))
           .ToLocal(&key)) {
    std::move(callback).Run({}, "bad member name");
    return;
  }
  v8::TryCatch try_catch(isolate);
  v8::Local<v8::Value> result;
  if (!obj->Get(context, key).ToLocal(&result)) {
    std::move(callback).Run({}, DescribeJsException(isolate, &try_catch));
    return;
  }
  std::move(callback).Run(EncodeJsValue(isolate, context, result, *this),
                          std::string());
}

void JuxDomHandlerImpl::JsSetMember(int32_t object_id, const std::string& name,
                                    const std::vector<uint8_t>& value,
                                    JsSetMemberCallback callback) {
  v8::Isolate* isolate = v8::Isolate::GetCurrent();
  v8::HandleScope handle_scope(isolate);
  v8::Local<v8::Context> context = MainWorldContext();
  if (context.IsEmpty()) {
    std::move(callback).Run("no script context");
    return;
  }
  v8::Context::Scope context_scope(context);
  v8::Local<v8::Object> obj;
  if (!ResolveJsTarget(isolate, context, object_id, &obj)) {
    std::move(callback).Run("invalid JS object");
    return;
  }
  size_t consumed = 0;
  v8::Local<v8::Value> val =
      DecodeJsValue(isolate, context, value.data(), value.size(), &consumed,
                    *this);
  if (val.IsEmpty()) {
    val = v8::Undefined(isolate);
  }
  v8::Local<v8::String> key;
  if (!v8::String::NewFromUtf8(isolate, name.c_str(),
                               v8::NewStringType::kNormal,
                               static_cast<int>(name.size()))
           .ToLocal(&key)) {
    std::move(callback).Run("bad member name");
    return;
  }
  v8::TryCatch try_catch(isolate);
  if (obj->Set(context, key, val).IsNothing()) {
    std::move(callback).Run(DescribeJsException(isolate, &try_catch));
    return;
  }
  std::move(callback).Run(std::string());
}

void JuxDomHandlerImpl::JsRemoveMember(int32_t object_id,
                                       const std::string& name,
                                       JsRemoveMemberCallback callback) {
  v8::Isolate* isolate = v8::Isolate::GetCurrent();
  v8::HandleScope handle_scope(isolate);
  v8::Local<v8::Context> context = MainWorldContext();
  if (context.IsEmpty()) {
    std::move(callback).Run("no script context");
    return;
  }
  v8::Context::Scope context_scope(context);
  v8::Local<v8::Object> obj;
  if (!ResolveJsTarget(isolate, context, object_id, &obj)) {
    std::move(callback).Run("invalid JS object");
    return;
  }
  v8::Local<v8::String> key;
  if (!v8::String::NewFromUtf8(isolate, name.c_str(),
                               v8::NewStringType::kNormal,
                               static_cast<int>(name.size()))
           .ToLocal(&key)) {
    std::move(callback).Run("bad member name");
    return;
  }
  v8::TryCatch try_catch(isolate);
  if (obj->Delete(context, key).IsNothing()) {
    std::move(callback).Run(DescribeJsException(isolate, &try_catch));
    return;
  }
  std::move(callback).Run(std::string());
}

void JuxDomHandlerImpl::JsGetSlot(int32_t object_id, int32_t index,
                                  JsGetSlotCallback callback) {
  v8::Isolate* isolate = v8::Isolate::GetCurrent();
  v8::HandleScope handle_scope(isolate);
  v8::Local<v8::Context> context = MainWorldContext();
  if (context.IsEmpty()) {
    std::move(callback).Run({}, "no script context");
    return;
  }
  v8::Context::Scope context_scope(context);
  v8::Local<v8::Object> obj;
  if (!ResolveJsTarget(isolate, context, object_id, &obj)) {
    std::move(callback).Run({}, "invalid JS object");
    return;
  }
  v8::TryCatch try_catch(isolate);
  v8::Local<v8::Value> result;
  if (!obj->Get(context, static_cast<uint32_t>(index)).ToLocal(&result)) {
    std::move(callback).Run({}, DescribeJsException(isolate, &try_catch));
    return;
  }
  std::move(callback).Run(EncodeJsValue(isolate, context, result, *this),
                          std::string());
}

void JuxDomHandlerImpl::JsSetSlot(int32_t object_id, int32_t index,
                                  const std::vector<uint8_t>& value,
                                  JsSetSlotCallback callback) {
  v8::Isolate* isolate = v8::Isolate::GetCurrent();
  v8::HandleScope handle_scope(isolate);
  v8::Local<v8::Context> context = MainWorldContext();
  if (context.IsEmpty()) {
    std::move(callback).Run("no script context");
    return;
  }
  v8::Context::Scope context_scope(context);
  v8::Local<v8::Object> obj;
  if (!ResolveJsTarget(isolate, context, object_id, &obj)) {
    std::move(callback).Run("invalid JS object");
    return;
  }
  size_t consumed = 0;
  v8::Local<v8::Value> val =
      DecodeJsValue(isolate, context, value.data(), value.size(), &consumed,
                    *this);
  if (val.IsEmpty()) {
    val = v8::Undefined(isolate);
  }
  v8::TryCatch try_catch(isolate);
  if (obj->Set(context, static_cast<uint32_t>(index), val).IsNothing()) {
    std::move(callback).Run(DescribeJsException(isolate, &try_catch));
    return;
  }
  std::move(callback).Run(std::string());
}

void JuxDomHandlerImpl::JsCall(int32_t object_id, const std::string& name,
                               uint32_t argc,
                               const std::vector<uint8_t>& args,
                               JsCallCallback callback) {
  v8::Isolate* isolate = v8::Isolate::GetCurrent();
  v8::HandleScope handle_scope(isolate);
  v8::Local<v8::Context> context = MainWorldContext();
  if (context.IsEmpty()) {
    std::move(callback).Run({}, "no script context");
    return;
  }
  v8::Context::Scope context_scope(context);
  v8::Local<v8::Object> obj;
  if (!ResolveJsTarget(isolate, context, object_id, &obj)) {
    std::move(callback).Run({}, "invalid JS object");
    return;
  }
  v8::Local<v8::String> key;
  if (!v8::String::NewFromUtf8(isolate, name.c_str(),
                               v8::NewStringType::kNormal,
                               static_cast<int>(name.size()))
           .ToLocal(&key)) {
    std::move(callback).Run({}, "bad method name");
    return;
  }
  v8::TryCatch try_catch(isolate);
  v8::Local<v8::Value> fn_val;
  if (!obj->Get(context, key).ToLocal(&fn_val) || !fn_val->IsFunction()) {
    std::move(callback).Run({}, "not a function: " + name);
    return;
  }
  v8::Local<v8::Function> fn = fn_val.As<v8::Function>();

  std::vector<v8::Local<v8::Value>> argv;
  argv.reserve(argc);
  size_t off = 0;
  for (uint32_t i = 0; i < argc && off < args.size(); ++i) {
    size_t consumed = 0;
    v8::Local<v8::Value> a = DecodeJsValue(isolate, context, args.data() + off,
                                           args.size() - off, &consumed, *this);
    if (consumed == 0) {
      break;
    }
    argv.push_back(a.IsEmpty() ? v8::Local<v8::Value>(v8::Undefined(isolate))
                               : a);
    off += consumed;
  }

  v8::Local<v8::Value> result;
  if (!fn->Call(context, obj, static_cast<int>(argv.size()), argv.data())
           .ToLocal(&result)) {
    std::move(callback).Run({}, DescribeJsException(isolate, &try_catch));
    return;
  }
  std::move(callback).Run(EncodeJsValue(isolate, context, result, *this),
                          std::string());
}

void JuxDomHandlerImpl::JsEval(int32_t object_id, const std::string& script,
                               JsEvalCallback callback) {
  // object_id is the eval scope receiver. v1 evaluates in the global context
  // (executeScript uses object_id 0); per-object "this" scoping is a follow-up.
  v8::Isolate* isolate = v8::Isolate::GetCurrent();
  VLOG(1) << "[jux-js] JsEval obj=" << object_id << " len=" << script.size()
            << " isolate=" << static_cast<void*>(isolate);
  if (!isolate) {
    std::move(callback).Run({}, "no V8 isolate");
    return;
  }
  v8::HandleScope handle_scope(isolate);
  v8::Local<v8::Context> context = MainWorldContext();
  if (context.IsEmpty()) {
    std::move(callback).Run({}, "no script context");
    return;
  }
  v8::Context::Scope context_scope(context);
  v8::Local<v8::String> source;
  if (!v8::String::NewFromUtf8(isolate, script.c_str(),
                               v8::NewStringType::kNormal,
                               static_cast<int>(script.size()))
           .ToLocal(&source)) {
    std::move(callback).Run({}, "bad script");
    return;
  }
  v8::TryCatch try_catch(isolate);
  v8::Local<v8::Script> compiled;
  if (!v8::Script::Compile(context, source).ToLocal(&compiled)) {
    std::move(callback).Run({}, DescribeJsException(isolate, &try_catch));
    return;
  }
  v8::Local<v8::Value> result;
  if (!compiled->Run(context).ToLocal(&result)) {
    std::move(callback).Run({}, DescribeJsException(isolate, &try_catch));
    return;
  }
  std::move(callback).Run(EncodeJsValue(isolate, context, result, *this),
                          std::string());
}

void JuxDomHandlerImpl::JsRelease(int32_t object_id) {
  // Java's JSObject was GC'd — drop the Global so V8 can reclaim the object.
  js_objects_.erase(object_id);
}

}  // namespace jux
