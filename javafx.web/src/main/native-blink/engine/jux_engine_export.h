// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// jux-engine export macro — controls DLL symbol visibility.
//
// When building jux-engine.dll, JUX_ENGINE_IMPLEMENTATION is defined,
// so JUX_EXPORT marks symbols as dllexport (Windows) or visible (POSIX).
// When consuming the DLL, JUX_EXPORT marks symbols as dllimport (Windows)
// or does nothing (POSIX, symbols are resolved at link time).

#ifndef JUX_ENGINE_EXPORT_H_
#define JUX_ENGINE_EXPORT_H_

#if defined(WIN32)

#if defined(JUX_ENGINE_IMPLEMENTATION)
#define JUX_EXPORT __declspec(dllexport)
#else
#define JUX_EXPORT __declspec(dllimport)
#endif  // JUX_ENGINE_IMPLEMENTATION

#elif defined(__GNUC__) || defined(__clang__)

#if defined(JUX_ENGINE_IMPLEMENTATION)
#define JUX_EXPORT __attribute__((visibility("default")))
#else
#define JUX_EXPORT
#endif  // JUX_ENGINE_IMPLEMENTATION

#else

#define JUX_EXPORT

#endif

#endif  // JUX_ENGINE_EXPORT_H_
