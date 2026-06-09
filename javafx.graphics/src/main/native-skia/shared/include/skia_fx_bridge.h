/*
 * skia_fx_bridge.h — typed C++ accessors for in-process native consumers.
 *
 * Sibling to openjfx_skia_bridge.h. The two serve different audiences:
 *
 *   openjfx_skia_bridge.h — C ABI (extern "C", uintptr_t handles, no
 *                           Skia types). Crosses java.lang.foreign.
 *   skia_fx_bridge.h      — C++ API in namespace skia_fx. Returns
 *                           SkSurface* / SkCanvas* / SkImage* /
 *                           GrDirectContext* directly. For other
 *                           native modules in the same process that
 *                           already link Skia (today: javafx.web's
 *                           WebKit port).
 *
 * Process invariant: there is one Skia, one GrDirectContext, one
 * handle namespace. javafx.web's native code uses these accessors to
 * resolve a handle issued by javafx.graphics (e.g. a SkiaGraphics's
 * SkSurface) to a real SkCanvas pointer and paint into the *same*
 * scene buffer the pipeline is rendering. That is the zero-copy
 * property described in CLAUDE.md.
 *
 * Consumer build requirements:
 *   - Add this header's directory to the include path.
 *   - Add ${SKIA_HOME}/include to the include path (needed for
 *     sk_sp<T> template + Skia public headers when consumers actually
 *     deref the returned pointers).
 *   - Link against the openjfx_skia_shared shared library.
 *
 * Threading: every call here is render-thread-only. Skia is single-
 * threaded per GrDirectContext, and our shared context is bound to
 * Quantum's render thread on first GPU surface allocation.
 */

#ifndef SKIA_FX_BRIDGE_H
#define SKIA_FX_BRIDGE_H

#include <cstdint>
#include "include/core/SkRefCnt.h"

class SkSurface;
class SkCanvas;
class SkImage;
class GrDirectContext;

#if defined(_WIN32)
  #if defined(OPENJFX_BUILDING_SKIA_BRIDGE)
    #define SKIA_FX_API __declspec(dllexport)
  #else
    #define SKIA_FX_API __declspec(dllimport)
  #endif
#else
  #define SKIA_FX_API __attribute__((visibility("default")))
#endif

namespace skia_fx {

/**
 * Resolves a uintptr_t handle (issued by openjfx_skia_surface_create_*)
 * to the live SkSurface*.
 *
 * Returns nullptr when:
 *   - the handle is 0 or stale (already destroyed);
 *   - the handle refers to a picture-recorder target (no SkSurface);
 *   - the handle refers to a D3D swap-chain wrapper (the per-back-
 *     buffer surface rotates; use resolve_canvas instead to get the
 *     canvas of the *current* back buffer).
 */
SKIA_FX_API SkSurface* resolve_surface(uintptr_t handle);

/**
 * Resolves a uintptr_t handle to the SkCanvas draw operations should
 * target. Handles all surface kinds correctly:
 *   - picture recorder targets return the recorder's canvas;
 *   - D3D swap-chain wrappers return the current back-buffer canvas;
 *   - regular GL / raster surfaces return surface->getCanvas().
 *
 * Returns nullptr on stale/invalid handle.
 *
 * This is the primary entry for in-process consumers. WebKit's
 * PlatformContextSkiaJava obtains the scene's canvas via this call
 * and draws directly into it for the duration of one NGWebView paint.
 */
SKIA_FX_API SkCanvas* resolve_canvas(uintptr_t handle);

/**
 * Resolves a uintptr_t image handle (issued by the bridge's
 * openjfx_skia_image_* entry points, or by register_image below)
 * to the live SkImage*. Returns nullptr on stale/invalid handle.
 */
SKIA_FX_API SkImage* resolve_image(uintptr_t handle);

/**
 * Returns the per-process GrDirectContext used by the entire pipeline.
 * Lazy-init on first GPU surface allocation; subsequent callers see
 * the same context. Returns nullptr if no GPU backend is available
 * (CPU-only profile) or if context creation has not yet been driven
 * by the Java side.
 *
 * Use this to allocate offscreen GPU SkSurfaces (e.g. WebKit's
 * ImageBuffer for HTML5 canvas) that share GPU memory with the
 * scene's surfaces — no cross-context copies.
 */
SKIA_FX_API GrDirectContext* shared_gr_context();

/**
 * Registers an externally-created SkSurface with the bridge and
 * returns a uintptr_t handle that can be:
 *   - passed back to any openjfx_skia_surface_* C ABI entry,
 *   - resolved via resolve_surface / resolve_canvas,
 *   - released via openjfx_skia_surface_destroy.
 *
 * Ownership: the bridge takes the sk_sp ref. Caller must not also
 * destroy the underlying SkSurface; release exactly once via
 * openjfx_skia_surface_destroy.
 *
 * Returns 0 on failure (null input).
 */
SKIA_FX_API uintptr_t register_surface(sk_sp<SkSurface> surface);

/**
 * Registers an externally-created SkImage with the bridge and returns
 * a uintptr_t handle interoperable with the C ABI image entries.
 * Ownership semantics mirror register_surface (release via
 * openjfx_skia_image_destroy).
 *
 * Returns 0 on failure (null input).
 */
SKIA_FX_API uintptr_t register_image(sk_sp<SkImage> image);

/**
 * True if the active GPU backend is Direct3D 12 (vs OpenGL or none).
 * Brings up the shared context if it has not been initialized yet.
 *
 * 3D consumers (openjfx_skia_3d) MUST check this before attempting to
 * wrap a D3D12 texture into the shared GrDirectContext — wrapping a
 * D3D backend texture into a GL-backed context is undefined.
 */
SKIA_FX_API bool backend_is_d3d();

/**
 * Flush all pending GPU work on the shared GrDirectContext and BLOCK on
 * the CPU until the GPU has finished it. Call before releasing a native
 * resource the GPU might still be reading (e.g. tearing down a 3D
 * SubScene target) — an in-flight release corrupts/crashes strict
 * drivers (observed: AMD multi-GPU). Expensive (full GPU stall); use
 * only on teardown, never per-frame.
 */
SKIA_FX_API void gpu_flush_and_wait();

#if defined(_WIN32)
/**
 * The active backend's ID3D12Device / direct ID3D12CommandQueue as a
 * void* (cast back to the real type inside the consumer's own
 * d3d12.h-confined translation unit), or nullptr if the D3D backend
 * was never brought up (GL fallback / CPU profile).
 *
 * No AddRef is performed: the bridge owns process-lifetime references
 * to these objects. Borrow them only for the duration of a single
 * render-thread call; never store past the lifetime of the device.
 */
SKIA_FX_API void* d3d12_device();
SKIA_FX_API void* d3d12_queue();

/**
 * True once the shared D3D12 device has been removed/reset (TDR, adapter change on
 * a cross-DPI monitor move, etc.). Skia and bgfx share this device, so every GPU
 * path must check this and skip all D3D work once it is lost — degrade, never crash
 * or spam the dead device. Latched until a future device-recreate path clears it.
 */
SKIA_FX_API bool d3d12_device_lost();

/**
 * Create a color render-target ID3D12Resource (RGBA8, width x height) on
 * the shared D3D12 device. Initial state RENDER_TARGET, sampleable as a
 * shader resource. Returns the resource as a void* (the caller owns one
 * reference — release it via {@link d3d12_release}), or nullptr.
 *
 * Lives in the shared bridge (not the 3D lib) so ALL Skia/D3D wrapping
 * uses the one process-wide Skia + GrDirectContext — never a second copy.
 */
SKIA_FX_API void* d3d12_create_rt_texture(int32_t width, int32_t height);

/**
 * Wrap an existing D3D12 color resource (e.g. one bgfx rendered into) as
 * an SkImage on the shared GrDirectContext — ZERO COPY — and register it,
 * returning a bridge image handle (0 on failure). The resource's current
 * state (RENDER_TARGET) is declared so Skia inserts the transition to
 * shader-resource itself. Release the handle via
 * {@link openjfx_skia_image_destroy} after the composite is recorded.
 */
SKIA_FX_API uintptr_t d3d12_wrap_texture_as_image(void* resource,
                                                  int32_t width, int32_t height);

/** Release one reference on a D3D12 resource from d3d12_create_rt_texture. */
SKIA_FX_API void d3d12_release(void* resource);

/** DEBUG (Phase A): read back an RT resource and write it to a PNG. 0 on success. */
SKIA_FX_API int32_t debug_dump_rt(void* resource, int32_t width, int32_t height,
                                  const char* path);
#endif

} // namespace skia_fx

#endif // SKIA_FX_BRIDGE_H
