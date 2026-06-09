/*
 * openjfx_skia_d3d_win.h — Windows-only D3D12 / Ganesh / DXGI plumbing.
 *
 * This header is intentionally minimal: it does NOT pull in d3d12.h
 * (which transitively defines `interface`, `small`, `near`, `far`
 * as macros and breaks the rest of the bridge). All those types are
 * confined to openjfx_skia_d3d_win.cpp.
 *
 * Consumers (openjfx_skia_bridge.cpp) see only opaque pointers and
 * the small C++ entry-points declared below.
 */

#ifndef OPENJFX_SKIA_D3D_WIN_H
#define OPENJFX_SKIA_D3D_WIN_H

#include <stdint.h>
#include "include/core/SkRefCnt.h" // sk_sp<GrDirectContext>

class SkSurface;
class GrDirectContext;

/** Per-window D3D swap chain + back-buffer SkSurfaces. Opaque. */
struct OpenJfxD3DSwapChain;

/**
 * One-shot per-process init. Brings up an ID3D12Device + Direct
 * command queue + IDXGIFactory, then calls
 * {@code GrDirectContext::MakeDirect3D} and returns the result.
 * Returns null sk_sp on any failure (caller falls back to GL or
 * raster). Safe to call multiple times; subsequent calls return the
 * cached context.
 *
 * Kept inside this TU so that the d3d12.h macro pollution (which is
 * needed to populate {@code GrD3DBackendContext}) does not leak into
 * the main Skia bridge.
 */
sk_sp<GrDirectContext> openjfxD3DMakeContext();

/** True if DXGI_FEATURE_PRESENT_ALLOW_TEARING was detected at init. */
bool openjfxD3DAllowTearing();

/**
 * Creates a DXGI flip-model swap chain attached to `hwnd`, wraps each
 * back buffer as a Skia SkSurface using `grCtx`. Returns nullptr on
 * failure. Caller owns the returned object; release via
 * openjfxD3DDestroySwapChain.
 */
OpenJfxD3DSwapChain* openjfxD3DCreateSwapChain(
    void* hwnd /* HWND */,
    int32_t width, int32_t height,
    GrDirectContext* grCtx);

/** Returns the SkSurface for the swap chain's current back buffer. */
SkSurface* openjfxD3DCurrentSurface(OpenJfxD3DSwapChain* sc);

/** Returns the HWND this swap chain is attached to (as void*), or null. */
void* openjfxD3DHwnd(OpenJfxD3DSwapChain* sc);

/**
 * Flush the GrDirectContext + DXGI Present. With {@code vsync == 0} uses
 * Present(0, `DXGI_PRESENT_ALLOW_TEARING`) when supported, so the call
 * returns without blocking on the desktop refresh — this is what bypasses
 * the DWM windowed-vsync cap (uncapped, may tear). With {@code vsync != 0}
 * uses Present(1, 0): blocks for the next vblank, no tearing. Signals the
 * per-buffer fence on the command queue *after* the present so the next
 * acquire can wait. Returns 0 on success.
 */
int32_t openjfxD3DPresent(OpenJfxD3DSwapChain* sc, GrDirectContext* grCtx, int32_t vsync);

/**
 * Called once per frame *before* the bridge starts drawing. Blocks
 * the calling thread until the frame-latency waitable signals (DXGI
 * is ready to accept another present) *and* the back buffer that
 * DXGI is about to hand us has finished its previous GPU use. With
 * SetMaximumFrameLatency(1), this gives deterministic, non-stalling
 * frame pacing. Returns 0 on success.
 */
int32_t openjfxD3DAcquireNextBuffer(OpenJfxD3DSwapChain* sc);

/**
 * Re-sizes the swap chain in place via {@code IDXGISwapChain::ResizeBuffers}.
 * DWM treats this as a semantic resize, not a stretch — the only path
 * on Windows that avoids the resize-drag artifact OpenGL windowed apps
 * exhibit. Internally: waits for the GPU to finish all in-flight
 * frames, drops the per-buffer SkSurfaces, calls ResizeBuffers, then
 * re-wraps each new back buffer as a fresh SkSurface.
 *
 * Returns 0 on success. On failure the swap chain is in an undefined
 * state and the caller should treat the presentable as broken.
 */
int32_t openjfxD3DResize(OpenJfxD3DSwapChain* sc,
                         int32_t width, int32_t height,
                         GrDirectContext* grCtx);

/** Releases the swap chain + per-buffer surfaces. */
void openjfxD3DDestroySwapChain(OpenJfxD3DSwapChain* sc);

#endif /* OPENJFX_SKIA_D3D_WIN_H */
