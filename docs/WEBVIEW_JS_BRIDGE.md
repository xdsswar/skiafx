# WebView Java↔JS bridge — `netscape.javascript.JSObject` on Blink/OSR

Status: **Slice A (Java→JS) + slice B (Java-from-JS) implemented; all Java
`:javafx.web:compileJava` green; native pending the Chromium engine rebuild**
(2026-06-02, branch `web-blink`). Java and engine must rebuild **in lockstep**
— the JS wire format is shared, so a mismatched pair breaks the bridge.

### Progress
- **Done + Java compiling:** `JSValueCodec.java` (incl. a length-reporting
  `decode` for arg runs), `JSObjectImpl.java` (all 7 `JSObject` methods →
  `BlinkPage` ops), `BlinkPage` typed result channel (`JS_VALUE`/`JS_ERROR`
  handlers, op senders, `Cleaner` `JS_RELEASE`, java-object table, **`JS_CALLBACK`
  handler + reflection dispatch**), `CommandRingBuffer.writeBytes`,
  `WebPage`/`WebEngine` typed `executeScript`, the `JS_*`/`JS_VALUE`/`JS_CALLBACK`
  constants (Java **and** native headers).
- **Done (native, needs the Chromium rebuild to compile):** `jux_js_value.{h,cc}`
  (tagged codec ↔ `v8::Value`), `jux_dom.mojom` `Js*` methods + `OnJavaCall`,
  renderer `Js*` op impls in `jux_dom_handler_impl` (V8 object table +
  `MainWorldScriptContext` get/set/call/eval), browser `JuxJs*` wrappers +
  `OnJsValue`/`OnJavaCall` routing, **`JavaProxy` callable host-proxy +
  `Promise` return path (`ResolveJavaCall`/`JuxResolveJavaCall`/
  `JS_CALLBACK_RESULT`)**, `BUILD.gn` entry.
- **Remaining:** the lockstep Chromium engine rebuild + the post-rebuild
  verification in §11 (V8 APIs in `JavaProxy` are version-sensitive — expect a
  short compile loop).

Goal: implement the public, documented Java↔JS API exactly —
`netscape.javascript.JSObject` (`getMember`/`setMember`/`removeMember`/
`getSlot`/`setSlot`/`call`/`eval`/`toString`), correctly-typed `executeScript`
results, and exposing a Java object to JS so the page can call Java. No new
public API: apps use `(JSObject) engine.executeScript("window")` etc., unchanged.

---

## 1. Why this is non-trivial on OSR Blink

A `JSObject` is a **live reference to a V8 object** living in the renderer
process. Every `getMember`/`call` must round-trip browser→renderer→V8. Stock
JavaFX did this with native JSC handles; we need a Blink equivalent over the
existing `jux_dom.mojom` pipe (browser↔renderer, already carrying DOM ops and
`OnScriptedPrint`). The renderer side (`jux_dom_handler_impl`) already has
`blink::WebDocument`/`WebLocalFrame` and therefore `MainWorldScriptContext()`.

`JSObject` calls are **synchronous** (they return a value). We reuse
`executeScript`'s existing pattern: the call blocks the caller on a request-id
latch (bounded timeout), serviced off the FX thread inside `BlinkPage`.

---

## 2. Object model

- **Renderer JS-object table**: `int32 jsObjectId → v8::Global<v8::Value>`. The
  `Global` keeps the JS object alive. Id `0` is reserved (null). Ids are
  per-page; cleared on navigation (the old context is gone).
- A non-primitive JS value crossing to Java is assigned an id and returned as
  `{tag=jsObject, id}`. Java wraps it in **`JSObjectImpl(page, id)`**.
- **Lifetime**: `JSObjectImpl` registers a `Cleaner` action that sends
  `JS_RELEASE(id)` (fire-and-forget) so the renderer erases the `Global`. No
  `finalize`. This is the leak boundary — every id handed to Java is released
  exactly once. (Page dispose / navigation also clears the whole table.)
- **Java-object table** (browser/Java side): `int32 javaObjectId → Object`
  (strong ref, held by the page). When a Java object is exposed to JS
  (`setMember(name, javaObj)` / passed as a call arg), it gets a `javaObjectId`
  and the renderer builds a JS **host proxy** carrying that id. The page owns
  the table; entries live until page dispose (v1 — no JS→Java GC feedback yet).

---

## 3. Value marshaling — the tagged wire value

A single `[tag:1]` + payload encoding, used for every arg and result:

| tag | meaning | payload |
|----|---------|---------|
| 0 | `undefined`/`null` | — (→ Java `null`) |
| 1 | boolean | `[v:1]` (→ `Boolean`) |
| 2 | int32 | `[v:4]` (→ `Integer`) |
| 3 | double | `[v:8]` (→ `Double`) |
| 4 | string | `[len:4][utf8]` (→ `String`) |
| 5 | jsObject | `[jsObjectId:4]` (→ `JSObjectImpl`) |
| 6 | javaObject | `[javaObjectId:4]` (a Java object exposed to JS) |

`Integer` vs `Double`: the renderer checks `v8::Value::IsInt32()`. Objects,
arrays, and functions all marshal as tag 5 (`JSObject` wraps them; arrays expose
length + numeric `getSlot`). `executeScript` returns the decoded `Object`,
finally honoring the documented type rules.

Codec lives in one place each side: Java `JSValueCodec`, native
`jux_js_value.{h,cc}` — symmetric, little-endian, used by all JS ops.

---

## 4. Wire protocol — commands (Java→engine)

New **JS interop block `0x0100–0x011F`** (existing commands are all `< 0x0100`).
All synchronous ops carry a 4-byte `requestId` (correlated like `EXECUTE_JS`)
and the target `objId` (0 = global `window`). Mirrored in `jux_command_types.h`.

| cmd | id | payload (after windowId) |
|-----|----|--------------------------|
| `JS_GET_MEMBER` | `0x0100` | `[reqId:4][objId:4][nameLen:4][name]` |
| `JS_SET_MEMBER` | `0x0101` | `[reqId:4][objId:4][nameLen:4][name][value]` |
| `JS_REMOVE_MEMBER` | `0x0102` | `[reqId:4][objId:4][nameLen:4][name]` |
| `JS_GET_SLOT` | `0x0103` | `[reqId:4][objId:4][index:4]` |
| `JS_SET_SLOT` | `0x0104` | `[reqId:4][objId:4][index:4][value]` |
| `JS_CALL` | `0x0105` | `[reqId:4][objId:4][nameLen:4][name][argc:4]{value}…` |
| `JS_EVAL` | `0x0106` | `[reqId:4][objId:4][scriptLen:4][utf8]` |
| `JS_RELEASE` | `0x0107` | `[objId:4]` (no reqId — fire-and-forget, from Cleaner) |
| `JS_CALLBACK_RESULT` | `0x0108` | `[callId:4][status:1][payload]` — status 0 = success (payload = tagged value), 1 = error (payload = `[len:4][utf8]`); settles the JS promise of a host-proxy call |

`EXECUTE_JS` is unchanged on the wire but its **result** now uses the typed
event below. Oversized payloads (big scripts, large string args) ride the
existing temp-file path for `EXECUTE_JS_FILE`; `JS_CALL`/`JS_EVAL` with large
data reuse the **multi-slot command** approach or temp-file staging (TBD per
op — scripts already stage to a file; call args are typically small).

## 5. Wire protocol — events (engine→Java)

| event | id | payload (after windowId) |
|-------|----|--------------------------|
| `JS_VALUE` | `0x0213` | `[reqId:4][value]` — typed result for any sync JS op (incl. executeScript) |
| `JS_ERROR` | `0x0211` (existing) | `[reqId:4][errLen:4][utf8]` — JS exception |
| `JS_CALLBACK` | `0x0212` (existing, now used) | `[javaObjectId:4][callId:4][nameLen:4][name][argc:4]{value}…` — JS called a Java method; `callId` correlates the `JS_CALLBACK_RESULT` that settles the returned promise |

`JS_VALUE` rides `WriteEventLarge` (a returned string/object graph can exceed a
slot), so it reassembles transparently. `JS_CALLBACK` likewise.

`BlinkPage` already correlates `reqId` via a `ConcurrentHashMap` + latch; the
result decode swaps the raw-string path for `JSValueCodec.decode`.

---

## 6. Java→JS (read/call) — slice A

1. `engine.executeScript("window")` → `JS_EVAL`(objId=0) or `EXECUTE_JS` →
   renderer evaluates, result is the global object → tag 5, new id → Java wraps
   `JSObjectImpl`.
2. `win.getMember("location")` → `JS_GET_MEMBER` → renderer
   `obj->Get(ctx, "location")` → marshal → Java.
3. `win.call("alert", "hi")` → `JS_CALL` (args marshaled) → renderer resolves
   `obj.alert`, `Function::Call(obj, args)` → marshal result.
4. `win.eval("1+1")` → `JS_EVAL` with this object as the receiver scope.
5. `setMember`/`setSlot` with **primitive** values → renderer `obj->Set(...)`.

This delivers "call JS functions from Java" fully and typed results.

## 7. Java exposure + Java-from-JS — slice B (implemented)

1. `win.setMember("app", javaObj)` with a **Java object**: `JSValueCodec.encode`
   registers it (→ `javaObjectId`) and sends `JS_SET_MEMBER` with tag 6.
   `JsSetMember` decodes the value, `DecodeJsValue` → `JavaProxy(java_id)` builds
   a **host proxy** and sets it as `window.app`. Proxies are cached per id
   (`java_proxies_`, stable identity) and cleared on navigation.
2. **The proxy is a callable object** (`ObjectTemplate` with a
   `CallAsFunctionHandler` + a named-property getter):
   - `app.foo(1,'x')` → the named getter returns a self-contained bound function
     → on call, `DispatchJavaCall(java_id, "foo", args)`.
   - `app(arg)` → the call handler → `DispatchJavaCall(java_id, "", args)`. The
     **empty name** means "invoke the object's functional-interface SAM", so a
     Java lambda / `Consumer` / `Function` / `Runnable` exposed to JS is called
     directly as a function. This is the path for **"call a Java functional
     interface from JS"**.
   - The getter deliberately *doesn't* intercept `then`/`toString`/`valueOf`/
     symbols, so promises/`await`, `JSON.stringify`, and string coercion don't
     see a spurious function and break.
3. Args are marshaled with the **same tagged codec** (`EncodeJsValue`). A JS
   object arg is assigned an id (kept alive in `js_objects_`) and arrives in Java
   as a **live `JSObject`** — so JS can hand a JS object to a Java callback,
   which can then read it back (`getMember`, `call`, …). This is how
   `onData({…})` delivers the object to Java.
4. Renderer → `client_->OnJavaCall` → `kJsCallback` event (`WriteEventLarge`) →
   `BlinkPage.JS_CALLBACK` decodes `[javaObjectId][name][argc]{value}`, looks up
   the object, picks the method (named, overload-by-arity; or the functional
   interface's single abstract method when `name==""`), coerces args, and invokes
   it on the **FX thread** via `Invoker`.
5. **Return values — a renderer-side `Promise`.** Each host-proxy call returns a
   JS `Promise`, not `undefined`. `DispatchJavaCall` mints a `Promise::Resolver`,
   stores it in `pending_java_calls_` keyed by a `call_id` (carried on the
   `OnJavaCall`), and returns the promise. Java invokes the method, then sends
   `JS_CALLBACK_RESULT(call_id, ok, value|error)` → `JuxResolveJavaCall` →
   `JuxDomHandler.ResolveJavaCall` → the renderer `resolve`s with the decoded
   return value (or `reject`s with the exception). So the page does
   `const r = await app.foo(1)` / `app.foo(1).then(…)` and gets the **real Java
   return value** (a returned Java object round-trips as a new host proxy; a
   returned `JSObject` resolves back to its live V8 object).
   - **Still deadlock-proof:** the promise is *async* — the renderer never
     *blocks* on Java. JS can't synchronously wait on a promise, so there's no
     reentrant FX-thread cycle; a Java callback may itself do a blocking Java→JS
     op (`obj.getMember(…)`) freely.
   - Return values ride one command slot (≤ ~239 B). An oversized return
     (huge string) settles the promise as a **reject** ("too large") rather than
     hanging — bounded, never a silent stall.

---

## 8. Re-entrancy & threading

- Sync JS ops block the **caller** thread on a latch (FX thread or any), like
  `executeScript`. The work happens on the pump thread → renderer.
- Java methods invoked from JS (`JS_CALLBACK`) run on the **FX thread** via
  `Invoker`. They must not themselves perform a blocking `JS_CALL` if the FX
  thread is the one blocked — v1 keeps these callbacks void to avoid the
  reentrant-deadlock class entirely. Documented limitation.
- Bounded timeouts everywhere (reuse `JS_TIMEOUT_MS`): a wedged renderer can't
  hang the FX thread forever.

---

## 9. Files

Java (`com.sun.webkit.blink`): `JSValueCodec` (new), `JSObjectImpl` (new,
extends `netscape.javascript.JSObject`), `CommandType`/`NativeEventType` (new
ids), `BlinkPage` (JS-op methods + `JS_VALUE`/`JS_CALLBACK` handling + java-obj
table + Cleaner release). `com.sun.webkit.WebPage.executeScript` returns the
decoded value. `WebEngine` doc already promises this.

Native: `jux_command_types.h`/`jux_event_types.h` (ids), `jux_js_value.{h,cc}`
(codec, new), `jux_dom.mojom` (+`JsGetMember`/`JsCall`/… and `OnJavaCall`),
`jux_dom_handler_impl.{cc,h}` (renderer V8 object table + ops + host proxy),
`jux_command_dispatch.{cc,h}` (route JS cmds + emit `JS_VALUE`/`JS_CALLBACK`),
`jux_engine_api.{h,cc}` (typed result for `JuxExecuteJS`).

## 10. Slices

- **A (Java→JS):** typed results + `JSObjectImpl`
  get/set(primitive)/call/eval/slot + object lifetime. **Implemented.**
- **B (Java-from-JS):** Java-object exposure + callable host proxy (named
  methods **and** functional-interface SAM) + `JS_CALLBACK` reflection +
  **renderer-side `Promise` return values** (`JS_CALLBACK_RESULT` →
  `ResolveJavaCall`). **Implemented.**

## 11. Verification (post-rebuild)
- `executeScript("1+1")` → `Integer 2`; `("true")` → `Boolean`; `("'x'")` →
  `String "x"`; `("({})")` → `JSObject`.
- `(JSObject) executeScript("window")`; `getMember`/`call`/`eval` round-trip.
- `win.setMember("app", obj); executeScript("app.foo(1,2)")` invokes Java method.
- **Functional interface:** `win.setMember("onData", (JsDataReceiver) v -> …);
  executeScript("onData({name:'hi', n:7})")` → Java's SAM runs on the FX thread
  with a live `JSObject` it can read (`obj.getMember("name")`). (WebViewDemo
  "Java functional interface ← JS object" button.)
- **Return value (promise):** `win.setMember("jfx", obj);
  executeScript("(async()=>{ const s = await jfx.addNumbers(3,4);
  onResult(s); })()")` → JS receives `7` from Java. (WebViewDemo "Await Java
  return value in JS" button.)
- **Leak soak**: create/drop thousands of `JSObject`s **and** fire many
  `JS_CALLBACK`s with object args; the renderer `js_objects_` + `java_proxies_`
  tables return to baseline (Cleaner `JS_RELEASE` fire; navigation clears
  proxies). Java↔C++ id parity holds.
