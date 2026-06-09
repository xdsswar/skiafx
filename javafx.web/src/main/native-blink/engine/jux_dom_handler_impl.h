// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxDomHandlerImpl — renderer-side implementation of the jux DOM Mojo
// interface. Uses real Blink renderer/core APIs (no JS injection) to
// drive the DOM from the browser process.

#ifndef JUX_DOM_HANDLER_IMPL_H_
#define JUX_DOM_HANDLER_IMPL_H_

#include <cstdint>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "base/memory/weak_ptr.h"
#include "content/public/renderer/render_frame_observer.h"
#include "jux/jux_dom.mojom.h"
#include "jux/jux_js_value.h"
#include "v8/include/v8.h"
#include "mojo/public/cpp/bindings/associated_receiver.h"
#include "mojo/public/cpp/bindings/pending_receiver.h"
#include "mojo/public/cpp/bindings/remote.h"
#include "third_party/blink/renderer/core/dom/mutation_record.h"
#include "third_party/blink/renderer/platform/heap/member.h"
#include "third_party/blink/renderer/platform/heap/persistent.h"
#include "third_party/blink/renderer/platform/wtf/vector.h"

namespace blink {
class Document;
class Element;
class Event;
class EventTarget;
class MutationObserver;
class Node;
class WebDocument;
class WebElement;
class WebNode;
}

namespace content {
class RenderFrame;
}

namespace jux {

class JuxEventForwarder;
class JuxDocListener;
class JuxMutationDelegate;

// Implements the renderer side of the DOM Mojo pipe. Lives as a
// RenderFrameObserver so it is automatically destroyed when the frame
// is unloaded.
//
// Uses Blink's internal renderer/core APIs to:
//   - create / remove / mutate DOM elements
//   - register native event listeners that forward events back via
//     the JuxDomClient Mojo pipe (no JavaScript injection involved)
//
// Node IDs:
//   - Allocated by Java and passed through the Mojo interface. For
//     CreateElement the Java side asserts the caller-supplied id, so
//     subsequent commands can locate the node via the internal map.
//   - RequestDomTree walks the document and assigns ids to existing
//     nodes, returning them to Java in document order.
class JuxDomHandlerImpl : public content::RenderFrameObserver,
                           public mojom::JuxDomHandler,
                           public JsObjectTable {
 public:
  explicit JuxDomHandlerImpl(content::RenderFrame* render_frame);
  ~JuxDomHandlerImpl() override;

  JuxDomHandlerImpl(const JuxDomHandlerImpl&) = delete;
  JuxDomHandlerImpl& operator=(const JuxDomHandlerImpl&) = delete;

  // Binds a new pending receiver to this impl.
  void BindReceiver(
      mojo::PendingAssociatedReceiver<mojom::JuxDomHandler> receiver);

  // content::RenderFrameObserver:
  void OnDestruct() override;
  void ScriptedPrint(bool user_initiated) override;
  // Fires when a new document replaces the previous one (cross-document
  // navigation or same-tab reload). Used to tear down all per-document
  // state so stale listeners and node-id references don't leak across
  // navigations.
  void DidCreateNewDocument() override;

  // mojom::JuxDomHandler:
  void SetClient(
      mojo::PendingRemote<mojom::JuxDomClient> client) override;
  void RequestDomTree(RequestDomTreeCallback callback) override;
  void AddEventListener(int64_t node_id,
                         const std::string& event_type) override;
  void RemoveEventListener(int64_t node_id,
                            const std::string& event_type) override;
  void CreateElement(int64_t node_id, const std::string& tag) override;
  void RemoveElement(int64_t node_id) override;
  void SetAttribute(int64_t node_id, const std::string& name,
                     const std::string& value) override;
  void RemoveAttribute(int64_t node_id,
                        const std::string& name) override;
  void SetTextContent(int64_t node_id, const std::string& text) override;
  void SetInnerHtml(int64_t node_id, const std::string& html) override;
  void AppendChild(int64_t parent_id, int64_t child_id) override;
  void InsertBefore(int64_t parent_id, int64_t child_id,
                     int64_t ref_id) override;
  void SetStyleProperty(int64_t node_id, const std::string& prop,
                         const std::string& value) override;
  void AddClass(int64_t node_id,
                 const std::string& class_name) override;
  void RemoveClass(int64_t node_id,
                    const std::string& class_name) override;
  void AddStylesheet(uint32_t id, const std::string& css) override;
  void RemoveStylesheet(uint32_t id) override;
  void SetHitSpotNodes(
      std::vector<mojom::HitSpotNodePtr> nodes) override;
  void SetHitSpotHovered(uint32_t code, bool hovered) override;
  void SelectPopupResponse(uint32_t popup_id,
                           const std::vector<int32_t>& indices) override;
  void ColorChooserResponse(uint32_t chooser_id, bool chosen,
                            uint32_t rgba) override;
  void SetPopupOverrides(bool select_overridden,
                         bool color_overridden) override;

  // JavaScript object bridge (netscape.javascript.JSObject). Each op runs in the
  // frame's main-world V8 context on a live object kept alive in js_objects_
  // (object_id 0 = the global window). Tagged values per jux_js_value.h.
  void JsGetMember(int32_t object_id, const std::string& name,
                   JsGetMemberCallback callback) override;
  void JsSetMember(int32_t object_id, const std::string& name,
                   const std::vector<uint8_t>& value,
                   JsSetMemberCallback callback) override;
  void JsRemoveMember(int32_t object_id, const std::string& name,
                      JsRemoveMemberCallback callback) override;
  void JsGetSlot(int32_t object_id, int32_t index,
                 JsGetSlotCallback callback) override;
  void JsSetSlot(int32_t object_id, int32_t index,
                 const std::vector<uint8_t>& value,
                 JsSetSlotCallback callback) override;
  void JsCall(int32_t object_id, const std::string& name, uint32_t argc,
              const std::vector<uint8_t>& args,
              JsCallCallback callback) override;
  void JsEval(int32_t object_id, const std::string& script,
              JsEvalCallback callback) override;
  void JsRelease(int32_t object_id) override;
  // Slice B: settles the JS Promise returned by a host-proxy call with the Java
  // method's return value (ok=true) or exception (ok=false). call_id matches the
  // OnJavaCall that opened it.
  void ResolveJavaCall(int32_t call_id, bool ok,
                       const std::vector<uint8_t>& value,
                       const std::string& error) override;

  // JsObjectTable — the renderer's live V8 object table for the codec.
  int32_t AssignJsObject(v8::Isolate* isolate,
                         v8::Local<v8::Value> value) override;
  v8::Local<v8::Value> ResolveJsObject(v8::Isolate* isolate,
                                       int32_t id) override;
  v8::Local<v8::Value> JavaProxy(v8::Isolate* isolate,
                                 v8::Local<v8::Context> context,
                                 int32_t java_id) override;

  // Called by JuxEventForwarder when a registered event fires. Posts
  // an OnDomEvent back through the client pipe.
  void ForwardDomEvent(int64_t node_id, const std::string& event_type,
                        const std::vector<uint8_t>& payload);

  // Called by the document-level listeners (JuxDocListener). Extract the
  // context-menu / tooltip data from the Blink event and forward it through
  // the dedicated JuxDomClient methods.
  void HandleContextMenuEvent(blink::Event* event);
  void HandleTooltipEvent(blink::Event* event);
  void HandleSelectMouseDown(blink::Event* event);

  // Called by JuxMutationDelegate when the MutationObserver fires.
  // Packs each record into a mojom::MutationRecord and ships the batch
  // to the browser via the client pipe. Records targeting nodes not
  // already in node_map_ are dropped (the browser can't correlate
  // them with a Java-side element).
  void ForwardMutations(
      const blink::HeapVector<blink::Member<blink::MutationRecord>>&
          records);

  // Returns the engine node id for a given Blink node, or 0 if the
  // node hasn't been registered in the forward map. Used by the
  // mutation delegate to translate target pointers.
  int64_t LookupNodeId(const blink::Node* node) const;

 private:
  // Walks the current primary document and populates nodes_out +
  // node_map_. Clears any prior state first.
  void WalkDocument(const blink::WebDocument& document,
                     std::vector<mojom::DomNodePtr>* nodes_out);

  // Recursive helper for WalkDocument.
  void WalkNode(const blink::WebNode& node, int64_t parent_id,
                 std::vector<mojom::DomNodePtr>* nodes_out);

  // Returns the Blink Node for the given id, or nullptr.
  blink::Node* LookupNode(int64_t node_id);

  // Returns the Blink Element for the given id, or nullptr.
  blink::Element* LookupElement(int64_t node_id);

  // Returns the Blink Document for the observed frame.
  blink::Document* GetDocument();

  // Returns the frame's main-world V8 context (empty handle if unavailable).
  // The caller must already hold a v8::HandleScope on the calling isolate.
  v8::Local<v8::Context> MainWorldContext();
  // Resolves object_id to a V8 object (0 = the global). Returns false if the id
  // is unknown or no longer an object.
  bool ResolveJsTarget(v8::Isolate* isolate, v8::Local<v8::Context> context,
                       int32_t object_id, v8::Local<v8::Object>* out);

  // Slice B (Java-from-JS): marshals a host-proxy call's JS arguments with the
  // shared tagged codec and forwards them to Java via client_->OnJavaCall.
  // `name` empty = the functional-interface SAM. Runs inside the V8 callback on
  // the renderer main thread; the call is fire-and-forget (returns undefined).
  void DispatchJavaCall(v8::Isolate* isolate, v8::Local<v8::Context> context,
                        int32_t java_id, const std::string& name,
                        const v8::FunctionCallbackInfo<v8::Value>& info);

  // V8 callbacks backing a Java host proxy (built by JavaProxy). Static because
  // V8 gives them no C++ `this`; the handler pointer + java id (+ method name
  // for BoundMethodCall) ride in the callback data array. ProxyCall handles
  // `app(...)`, ProxyNamedGet returns a bound method for `app.foo`, and
  // BoundMethodCall is that bound method.
  static void ProxyCall(const v8::FunctionCallbackInfo<v8::Value>& info);
  static void BoundMethodCall(const v8::FunctionCallbackInfo<v8::Value>& info);
  static v8::Intercepted ProxyNamedGet(
      v8::Local<v8::Name> property,
      const v8::PropertyCallbackInfo<v8::Value>& info);

  // Tears down all per-document state: registered event listeners are
  // detached, the MutationObserver is disconnected, node maps and
  // stylesheet map are cleared. Called on navigation to a new document
  // so stale references can be garbage-collected promptly.
  void ResetPerDocumentState();

  // Returns a key combining node_id + event_type for the listener map.
  static std::string ListenerKey(int64_t node_id,
                                  const std::string& event_type);

  // Installs the document-level `contextmenu` + `mouseover` listeners once per
  // document (no-op if already installed, the client isn't bound, or there is
  // no document yet). Reset by ResetPerDocumentState on navigation.
  void EnsureDocListeners();

  // Monotonic allocator for ids assigned during tree walk.
  int64_t next_id_ = 1;

  // Live V8 objects handed to Java, keyed by id (object_id 0 is the global, not
  // stored here). The v8::Global keeps each alive until Java's JSObject is GC'd
  // (→ JsRelease) or navigation clears the table in ResetPerDocumentState.
  std::unordered_map<int32_t, v8::Global<v8::Value>> js_objects_;
  int32_t next_js_id_ = 1;

  // Host proxies for Java objects exposed to JS (slice B), keyed by
  // javaObjectId. Cached so `window.app === window.app` holds; cleared on
  // navigation in ResetPerDocumentState. Each proxy is callable (a
  // functional-interface SAM call → Java method name "") and intercepts
  // property reads to return bound methods. Built by JavaProxy.
  std::unordered_map<int32_t, v8::Global<v8::Object>> java_proxies_;

  // In-flight Java-call promises (slice B), keyed by call_id. Each host-proxy
  // call returns a Promise whose resolver lives here until ResolveJavaCall
  // settles it with the Java return value/exception. Cleared on navigation.
  std::unordered_map<int32_t, v8::Global<v8::Promise::Resolver>>
      pending_java_calls_;
  int32_t next_java_call_id_ = 1;

  // Map from allocated id to a Blink Node (Persistent keeps the GC
  // pointer alive across garbage-collection cycles).
  std::unordered_map<int64_t, blink::Persistent<blink::Node>> node_map_;

  // Map from (nodeId, eventType) → registered listener, so we can
  // unregister precisely. Persistent because EventListener is a GC type.
  std::unordered_map<std::string,
                      blink::Persistent<JuxEventForwarder>>
      listeners_;

  // Injected <style> elements keyed by id.
  std::unordered_map<uint32_t, blink::Persistent<blink::Element>>
      stylesheet_map_;

  // Receiver for the pipe used by the browser to call us.
  mojo::AssociatedReceiver<mojom::JuxDomHandler> receiver_{this};

  // Remote used to report DOM events back to the browser.
  mojo::Remote<mojom::JuxDomClient> client_;

  // MutationObserver + delegate. Installed on the document by
  // RequestDomTree so that post-walk DOM changes (JS-driven, parser,
  // etc.) flow back to Java.
  blink::Persistent<blink::MutationObserver> mutation_observer_;
  blink::Persistent<JuxMutationDelegate> mutation_delegate_;

  // Document-level context-menu + tooltip listeners (installed once per
  // document by EnsureDocListeners). last_tooltip_ debounces OnTooltipChanged
  // so it only fires on change.
  blink::Persistent<JuxDocListener> context_listener_;
  blink::Persistent<JuxDocListener> tooltip_listener_;
  bool doc_listeners_installed_ = false;
  std::string last_tooltip_;

  // Native <select> popups currently surfaced to Java, keyed by popupId so the
  // SelectPopupResponse can find the originating element. Persistent keeps the
  // GC element alive while the app's list is open.
  blink::Persistent<JuxDocListener> select_listener_;
  uint32_t next_popup_id_ = 1;
  std::unordered_map<uint32_t, blink::Persistent<blink::Element>> open_selects_;
  // Whether the app overrides the select / colour popup. When set we intercept
  // the control (preventDefault + surface to Java); otherwise Blink shows its
  // own native page-popup. Pushed from Java via SetPopupOverrides.
  bool select_overridden_ = false;
  bool color_overridden_ = false;
  // <input type=color> pickers currently surfaced to Java, keyed by chooserId.
  std::unordered_map<uint32_t, blink::Persistent<blink::Element>>
      open_color_inputs_;

  // Custom-chrome hit-spot node subscription. The browser pushes
  // (HT* code, node_id) pairs via SetHitSpotNodes; we keep a
  // Persistent<Element> for each so a pending rect poll doesn't race
  // with garbage collection. A self-reposting 16 ms tick reads
  // getBoundingClientRect on each entry, diffs against the last
  // value, and fires OnHitSpotRects on the client pipe when
  // something changed. The diff avoids hammering the browser with
  // identical rects every frame.
  std::vector<std::pair<uint32_t, blink::Persistent<blink::Element>>>
      hit_spot_nodes_;
  struct LastRect { double x, y, w, h; };
  std::unordered_map<uint32_t, LastRect> last_rects_;
  bool hit_spot_poll_scheduled_ = false;

  // Schedules the next hit-spot poll tick if any nodes are registered.
  void PostHitSpotPoll();
  // Fires from the frame task runner — calls PushHitSpotRects then
  // re-posts itself.
  void OnHitSpotPollTick();
  // Reads current rects, diffs, fires OnHitSpotRects if changed.
  void PushHitSpotRects();

  // WeakPtrs used as the completion token for the self-reposting
  // poll task. Declared last so it's destroyed first, invalidating
  // any in-flight task at destruction.
  base::WeakPtrFactory<JuxDomHandlerImpl> weak_factory_{this};
};

}  // namespace jux

#endif  // JUX_DOM_HANDLER_IMPL_H_
