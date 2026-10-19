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
/** Which D3D12 adapters openjfxD3DMakeContext may use. */
enum class D3DAdapterPolicy {
    HardwareOnly,      // AUTO, first attempt: real GPUs only
    HardwareThenWarp,  // D3D12 explicitly requested: GPU, else WARP
    WarpOnly           // AUTO, after hardware D3D12 and GL both failed
};

sk_sp<GrDirectContext> openjfxD3DMakeContext(
    D3DAdapterPolicy policy = D3DAdapterPolicy::HardwareThenWarp);

/**
 * Device-loss recovery: releases the removed device's globals, brings up a
 * NEW device/queue (any adapter class may be retried) and returns a fresh
 * GrDirectContext on it, bumping skia_fx::d3d12_device_generation(). Returns
 * nullptr if no usable device is available yet (driver still resetting) —
 * the caller retries later. Render thread only.
 */
sk_sp<GrDirectContext> openjfxD3DRecreateContext(D3DAdapterPolicy policy);

/** Clears the device-lost latch (recovery only, after everything is rebuilt). */
namespace skia_fx { void d3d12_clear_device_lost(); void d3d12_device_generation_bump(); }

/**
 * TEST ONLY: ID3D12Device5::RemoveDevice() on the shared device — a real
 * device removal, as a TDR would cause. 0 on success.
 */
int32_t openjfxD3DTestRemoveDevice();

/** True if DXGI_FEATURE_PRESENT_ALLOW_TEARING was detected at init. */
bool openjfxD3DAllowTearing();

/** True when the D3D12 device is the WARP software rasterizer (no usable GPU). */
bool openjfxD3DIsWarp();

/**
 * Creates a DXGI flip-model swap chain attached to `hwnd`, wraps each
 * back buffer as a Skia SkSurface using `grCtx`. Returns nullptr on
 * failure. Caller owns the returned object; release via
 * openjfxD3DDestroySwapChain.
 */
OpenJfxD3DSwapChain* openjfxD3DCreateSwapChain(
    void* hwnd /* HWND */,
    int32_t width, int32_t height,
    GrDirectContext* grCtx,
    bool stretch);

/**
 * Sets the colour DWM shows in the part of the client area the last
 * presented buffer does not cover (DXGI_SCALING_NONE). During a live
 * border-drag that is the strip the window has grown by before the next
 * frame lands, so it should match the scene fill rather than black.
 * Channels are 0..1, opaque. Returns 0 on success.
 */
int32_t openjfxD3DSetBackgroundColor(OpenJfxD3DSwapChain* sc,
                                     float r, float g, float b);

/** Content size the scene renders at; the back buffers may be larger
 *  (grow-only headroom), with the margin outside it unused. */
void openjfxD3DContentSize(OpenJfxD3DSwapChain* sc, int32_t* w, int32_t* h);

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
 * Sets the content size to {@code width x height}. The back buffers are
 * grow-only with headroom (256 px per axis, capped at the monitor size):
 * a size that fits only moves the content size (no GPU drain), and the
 * margin outside the content is filled with the background colour at
 * present, so a growing window shows the scene fill, not black, until the
 * next frame lands (DXGI_SCALING_NONE crops the buffer to the client area).
 * When the window outgrows the buffers, or is much smaller than them,
 * the swap chain is resized via {@code IDXGISwapChain::ResizeBuffers}: waits for the GPU to finish all in-flight
 * frames, drops the per-buffer SkSurfaces, calls ResizeBuffers, then
 * re-wraps each new back buffer as a fresh SkSurface.
 *
 * Returns 0 on success. On failure the swap chain is in an undefined
 * state and the caller should treat the presentable as broken.
 */
int32_t openjfxD3DResize(OpenJfxD3DSwapChain* sc,
                         int32_t width, int32_t height,
                         GrDirectContext* grCtx);

/**
 * Releases the swap chain + per-buffer surfaces. When {@code grCtx} is given,
 * Skia is flushed synchronously after the back-buffer SkSurfaces are dropped so
 * it releases its own refs on the buffers' ID3D12Resources BEFORE the swap
 * chain is released. Without that the DXGI swap chain outlives Release() and a
 * new CreateSwapChainForHwnd on the same HWND fails (one flip-model swap chain
 * per HWND) — e.g. on every Stage.setScene.
 */
void openjfxD3DDestroySwapChain(OpenJfxD3DSwapChain* sc,
                                GrDirectContext* grCtx = nullptr);

#endif /* OPENJFX_SKIA_D3D_WIN_H */
