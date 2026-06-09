// Copyright 2026 Xtreme Software Solutions. All rights reserved.

#ifdef UNSAFE_BUFFERS_BUILD
#pragma allow_unsafe_buffers
#endif

#include "jux/jux_js_value.h"

#include <cstring>

namespace jux {

std::vector<uint8_t> EncodeJsValue(v8::Isolate* isolate,
                                   v8::Local<v8::Context> context,
                                   v8::Local<v8::Value> value,
                                   JsObjectTable& table) {
  std::vector<uint8_t> out;
  auto put_u32 = [&out](uint32_t v) {
    out.push_back(static_cast<uint8_t>(v));
    out.push_back(static_cast<uint8_t>(v >> 8));
    out.push_back(static_cast<uint8_t>(v >> 16));
    out.push_back(static_cast<uint8_t>(v >> 24));
  };

  if (value.IsEmpty() || value->IsNullOrUndefined()) {
    out.push_back(kJsTagNull);
    return out;
  }
  if (value->IsBoolean()) {
    out.push_back(kJsTagBool);
    out.push_back(value->BooleanValue(isolate) ? 1 : 0);
    return out;
  }
  if (value->IsInt32()) {
    out.push_back(kJsTagInt);
    put_u32(static_cast<uint32_t>(value->Int32Value(context).FromMaybe(0)));
    return out;
  }
  if (value->IsNumber()) {
    out.push_back(kJsTagDouble);
    double d = value->NumberValue(context).FromMaybe(0.0);
    uint64_t bits;
    memcpy(&bits, &d, sizeof(bits));
    for (int i = 0; i < 8; ++i) {
      out.push_back(static_cast<uint8_t>(bits >> (8 * i)));
    }
    return out;
  }
  if (value->IsString()) {
    out.push_back(kJsTagString);
    v8::String::Utf8Value utf8(isolate, value);
    uint32_t len = (*utf8 && utf8.length() > 0)
                       ? static_cast<uint32_t>(utf8.length())
                       : 0;
    put_u32(len);
    if (len > 0) {
      const uint8_t* p = reinterpret_cast<const uint8_t*>(*utf8);
      out.insert(out.end(), p, p + len);
    }
    return out;
  }
  // Object / array / function / symbol → keep it alive and return an id.
  out.push_back(kJsTagJsObject);
  put_u32(static_cast<uint32_t>(table.AssignJsObject(isolate, value)));
  return out;
}

v8::Local<v8::Value> DecodeJsValue(v8::Isolate* isolate,
                                   v8::Local<v8::Context> context,
                                   const uint8_t* data, size_t len,
                                   size_t* consumed, JsObjectTable& table) {
  *consumed = 0;
  if (len < 1) {
    return v8::Local<v8::Value>();
  }
  uint8_t tag = data[0];
  size_t off = 1;
  auto have = [&](size_t n) { return off + n <= len; };
  auto get_u32 = [&]() -> uint32_t {
    uint32_t v = static_cast<uint32_t>(data[off]) |
                 (static_cast<uint32_t>(data[off + 1]) << 8) |
                 (static_cast<uint32_t>(data[off + 2]) << 16) |
                 (static_cast<uint32_t>(data[off + 3]) << 24);
    off += 4;
    return v;
  };

  switch (tag) {
    case kJsTagNull:
      *consumed = off;
      return v8::Null(isolate);
    case kJsTagBool: {
      if (!have(1)) return v8::Local<v8::Value>();
      bool b = data[off] != 0;
      off += 1;
      *consumed = off;
      return v8::Boolean::New(isolate, b);
    }
    case kJsTagInt: {
      if (!have(4)) return v8::Local<v8::Value>();
      int32_t v = static_cast<int32_t>(get_u32());
      *consumed = off;
      return v8::Integer::New(isolate, v);
    }
    case kJsTagDouble: {
      if (!have(8)) return v8::Local<v8::Value>();
      uint64_t bits = 0;
      for (int i = 0; i < 8; ++i) {
        bits |= static_cast<uint64_t>(data[off + i]) << (8 * i);
      }
      off += 8;
      double d;
      memcpy(&d, &bits, sizeof(d));
      *consumed = off;
      return v8::Number::New(isolate, d);
    }
    case kJsTagString: {
      if (!have(4)) return v8::Local<v8::Value>();
      uint32_t slen = get_u32();
      if (off + slen > len) return v8::Local<v8::Value>();
      // Guard a wire-controlled length: static_cast<int> goes negative past
      // INT_MAX, and ToLocalChecked() CHECK-fails (aborts the renderer) when V8
      // rejects an oversized/OOM string. Cap to kMaxLength and use ToLocal,
      // bailing to undefined — "errors never kill the JVM".
      if (slen > static_cast<uint32_t>(v8::String::kMaxLength)) {
        return v8::Local<v8::Value>();
      }
      v8::Local<v8::String> s;
      if (!v8::String::NewFromUtf8(isolate,
                                   reinterpret_cast<const char*>(data + off),
                                   v8::NewStringType::kNormal,
                                   static_cast<int>(slen))
               .ToLocal(&s)) {
        return v8::Local<v8::Value>();
      }
      off += slen;
      *consumed = off;
      return s;
    }
    case kJsTagJsObject: {
      if (!have(4)) return v8::Local<v8::Value>();
      int32_t id = static_cast<int32_t>(get_u32());
      *consumed = off;
      return table.ResolveJsObject(isolate, id);
    }
    case kJsTagJavaObject: {
      if (!have(4)) return v8::Local<v8::Value>();
      int32_t id = static_cast<int32_t>(get_u32());
      *consumed = off;
      return table.JavaProxy(isolate, context, id);
    }
    default:
      return v8::Local<v8::Value>();
  }
}

}  // namespace jux
