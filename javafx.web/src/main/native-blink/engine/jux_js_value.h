// Copyright 2026 Xtreme Software Solutions. All rights reserved.
//
// Tagged-value codec for the Java↔JS bridge (netscape.javascript.JSObject).
// Symmetric with JSValueCodec.java (little-endian). A single encoding carries
// every executeScript/JSObject result and argument across the shared-memory
// rings. Layout:
//
//   [tag:1] then, by tag:
//     0 null/undefined  — (no payload)
//     1 boolean         — [v:1]
//     2 int32           — [v:4]
//     3 double          — [v:8]
//     4 string          — [len:4][utf8:N]
//     5 jsObject        — [id:4]   (a live V8 object kept alive in the table)
//     6 javaObject      — [id:4]   (a Java object exposed to JS — slice B)
//
// Object marshaling needs renderer state (the V8 object table), so the codec
// takes a JsObjectTable the renderer (JuxDomHandlerImpl) implements.

#ifndef JUX_JS_VALUE_H_
#define JUX_JS_VALUE_H_

#include <cstdint>
#include <vector>

#include "v8/include/v8.h"

namespace jux {

inline constexpr uint8_t kJsTagNull = 0;
inline constexpr uint8_t kJsTagBool = 1;
inline constexpr uint8_t kJsTagInt = 2;
inline constexpr uint8_t kJsTagDouble = 3;
inline constexpr uint8_t kJsTagString = 4;
inline constexpr uint8_t kJsTagJsObject = 5;
inline constexpr uint8_t kJsTagJavaObject = 6;

// Bridges the codec to the renderer's live-object state.
class JsObjectTable {
 public:
  virtual ~JsObjectTable() = default;

  // Stores `value` as a v8::Global and returns a stable non-zero id (the
  // reverse, Resolve, must return the same object). Called when a non-primitive
  // JS value crosses to Java.
  virtual int32_t AssignJsObject(v8::Isolate* isolate,
                                 v8::Local<v8::Value> value) = 0;

  // Returns the V8 value previously stored under `id`, or an empty handle.
  virtual v8::Local<v8::Value> ResolveJsObject(v8::Isolate* isolate,
                                               int32_t id) = 0;

  // Slice B: builds (or returns a cached) JS host proxy for a Java object id,
  // whose method calls marshal back to Java via JuxDomClient::OnJavaCall. May
  // return an empty handle until slice B is wired.
  virtual v8::Local<v8::Value> JavaProxy(v8::Isolate* isolate,
                                         v8::Local<v8::Context> context,
                                         int32_t java_id) = 0;
};

// Encodes a V8 value to tagged bytes (assigning an object id via `table` for
// non-primitives). `context` must be entered by the caller.
std::vector<uint8_t> EncodeJsValue(v8::Isolate* isolate,
                                   v8::Local<v8::Context> context,
                                   v8::Local<v8::Value> value,
                                   JsObjectTable& table);

// Decodes a single tagged value at `data`; on success sets `*consumed` to the
// number of bytes read. Resolves jsObject/javaObject ids via `table`. Returns
// an empty handle (and *consumed=0) on a malformed/too-short buffer.
v8::Local<v8::Value> DecodeJsValue(v8::Isolate* isolate,
                                   v8::Local<v8::Context> context,
                                   const uint8_t* data, size_t len,
                                   size_t* consumed, JsObjectTable& table);

}  // namespace jux

#endif  // JUX_JS_VALUE_H_
