/*
 * openjfx_skia_d3d_win.cpp — D3D12 / DXGI / Ganesh integration.
 *
 * Lives in its own translation unit so that d3d12.h's macro pollution
 * (`#define interface struct`, `near`, `far`, `small`, etc.) stays
 * confined here and does not leak into the main Skia bridge.
 *
 * Implements the contract declared in openjfx_skia_d3d_win.h:
 *   - one process-wide D3D12 device + command queue + DXGI factory
 *   - per-HWND flip-model swap chain wrapped as Skia SkSurfaces
 *   - present via DXGI Present(0, ALLOW_TEARING) — the path that
 *     bypasses DWM's windowed-vsync cap on Windows 10/11.
 */

#include "openjfx_skia_d3d_win.h"

#define WIN32_LEAN_AND_MEAN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <Windows.h>
#include <d3d12.h>
#include <dxgi1_5.h>

#include <cstdio>
#include <vector>

#include <atomic>
#include <utility>

#include "include/core/SkAlphaType.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkColorType.h"
#include "include/core/SkData.h"
#include "include/core/SkImage.h"
#include "include/core/SkRefCnt.h"
#include "include/encode/SkPngEncoder.h"
#include "include/core/SkSurface.h"
#include "include/core/SkSurfaceProps.h"
#include "include/gpu/ganesh/GrBackendSurface.h"
#include "include/gpu/ganesh/GrDirectContext.h"
#include "include/gpu/ganesh/GrTypes.h"
#include "include/gpu/ganesh/SkImageGanesh.h"
#include "include/gpu/ganesh/SkSurfaceGanesh.h"
#include "include/gpu/ganesh/d3d/GrD3DBackendContext.h"
#include "include/gpu/ganesh/d3d/GrD3DTypes.h"

#include "skia_fx_bridge.h" // skia_fx::d3d12_* (cross-DLL) + register_image / shared_gr_context

namespace {

// Process-wide D3D state. Populated by openjfxD3DInit; leaked at
// process exit by design (mirrors gpuDirectContext()'s leak-on-exit
// in the main bridge — destroying GrDirectContext after the OS has
// torn down GPU drivers / DWM crashes).
struct D3DGlobal {
    IDXGIFactory4*       factory     = nullptr;
    IDXGIAdapter1*       adapter     = nullptr;
    ID3D12Device*        device      = nullptr;
    ID3D12CommandQueue*  queue       = nullptr;
    bool                 allowTearing = false;
    bool                 initialized  = false;
    bool                 succeeded    = false;
};

static D3DGlobal& g() {
    static D3DGlobal s;
    return s;
}

// Three back buffers — the minimum for fully pipelined CPU/GPU
// throughput on a flip-model swap chain. With BufferCount=2 plus a
// SetMaximumFrameLatency(1) waitable, the CPU stalls every frame on
// the GPU; with BufferCount=3 it keeps a constant one-frame lead.
constexpr int kBufferCount = 3;
constexpr DXGI_FORMAT kSwapFormat = DXGI_FORMAT_R8G8B8A8_UNORM;

} // namespace

// Pulled into the namespace later; defined here so the impl struct
// can use the public name `OpenJfxD3DSwapChain` consistently.
struct OpenJfxD3DSwapChain {
    HWND                          hwnd = nullptr;
    IDXGISwapChain3*              swapChain = nullptr;
    std::vector<sk_sp<SkSurface>> backBuffers;
    int                           width = 0;
    int                           height = 0;
    bool                          allowTearing = false;

    // ---- Frame-pacing state ------------------------------------------------
    // SetMaximumFrameLatency(1) gives us this handle; WaitForSingleObject
    // on it blocks until DXGI is ready to accept another present.
    HANDLE                        waitableObject = nullptr;
    // Per-back-buffer fence values. fenceValues[i] is the value the
    // queue will be signalled to *after* the present that releases
    // buffer i; the next time we acquire buffer i we wait until the
    // fence reaches at least that value.
    ID3D12Fence*                  fence          = nullptr;
    UINT64                        fenceValues[kBufferCount] = {};
    UINT64                        nextFenceValue = 0;
    HANDLE                        fenceEvent     = nullptr;
    // AddRef'd ref to the process-wide command queue so the queue
    // out-lives the swap chain even if something else releases the
    // global early.
    ID3D12CommandQueue*           queue          = nullptr;
};

// ---------------------------------------------------------------------------
// Init / device + queue + factory + tearing capability detection.
// ---------------------------------------------------------------------------

namespace {

// Brings up the process-wide D3D objects (idempotent). Returns true if
// the globals are populated and the device is usable.
bool ensureD3DInitialized() {
    D3DGlobal& s = g();
    if (s.initialized) return s.succeeded;
    s.initialized = true;

    HRESULT hr;
    UINT factoryFlags = 0;

    hr = CreateDXGIFactory2(factoryFlags, IID_PPV_ARGS(&s.factory));
    if (FAILED(hr) || !s.factory) return false;

    // Pick the first hardware adapter that creates a D3D12 device.
    for (UINT i = 0;; ++i) {
        IDXGIAdapter1* a = nullptr;
        if (s.factory->EnumAdapters1(i, &a) == DXGI_ERROR_NOT_FOUND) break;
        DXGI_ADAPTER_DESC1 desc;
        a->GetDesc1(&desc);
        if (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) {
            a->Release();
            continue;
        }
        ID3D12Device* dev = nullptr;
        hr = D3D12CreateDevice(a, D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&dev));
        if (SUCCEEDED(hr) && dev) {
            s.adapter = a;
            s.device  = dev;
            break;
        }
        a->Release();
    }
    if (!s.device) {
        s.factory->Release();
        s.factory = nullptr;
        return false;
    }

    D3D12_COMMAND_QUEUE_DESC qdesc = {};
    qdesc.Type     = D3D12_COMMAND_LIST_TYPE_DIRECT;
    qdesc.Priority = D3D12_COMMAND_QUEUE_PRIORITY_NORMAL;
    qdesc.Flags    = D3D12_COMMAND_QUEUE_FLAG_NONE;
    qdesc.NodeMask = 0;
    hr = s.device->CreateCommandQueue(&qdesc, IID_PPV_ARGS(&s.queue));
    if (FAILED(hr) || !s.queue) {
        s.device->Release();  s.device  = nullptr;
        s.adapter->Release(); s.adapter = nullptr;
        s.factory->Release(); s.factory = nullptr;
        return false;
    }

    // Probe ALLOW_TEARING — only available on Windows 10 1607+ with a
    // DXGI 1.5 factory. Required to bypass DWM's windowed vsync.
    IDXGIFactory5* f5 = nullptr;
    if (SUCCEEDED(s.factory->QueryInterface(IID_PPV_ARGS(&f5)))) {
        BOOL allow = FALSE;
        if (SUCCEEDED(f5->CheckFeatureSupport(
                DXGI_FEATURE_PRESENT_ALLOW_TEARING, &allow, sizeof(allow)))) {
            s.allowTearing = !!allow;
        }
        f5->Release();
    }
    s.succeeded = true;
    return true;
}

} // namespace

// Release everything the D3D init reserved. Called when the caller
// decides not to use the D3D backend (GrDirectContext::MakeDirect3D
// returned null), so the GL fallback doesn't compete with a held-but-
// idle D3D12 device for GPU resources / driver state.
static void releaseD3DGlobals() {
    D3DGlobal& s = g();
    if (s.queue)   { s.queue->Release();   s.queue   = nullptr; }
    if (s.device)  { s.device->Release();  s.device  = nullptr; }
    if (s.adapter) { s.adapter->Release(); s.adapter = nullptr; }
    if (s.factory) { s.factory->Release(); s.factory = nullptr; }
    s.allowTearing = false;
    s.succeeded    = false;
    // Leave `initialized` true so a second openjfxD3DMakeContext call
    // returns null fast without re-attempting.
}

sk_sp<GrDirectContext> openjfxD3DMakeContext() {
    if (!ensureD3DInitialized()) {
        std::fprintf(stderr,
            "[openjfx-skia] D3D12 init failed (CreateDXGIFactory2 / D3D12CreateDevice / CreateCommandQueue) — falling back to GL.\n");
        return nullptr;
    }
    D3DGlobal& s = g();

    GrD3DBackendContext bc;
    // gr_cp adopts without AddRef, so AddRef once so our globals keep
    // their own ref independent of Skia's.
    s.adapter->AddRef();
    s.device->AddRef();
    s.queue->AddRef();
    bc.fAdapter = gr_cp<IDXGIAdapter1>(s.adapter);
    bc.fDevice  = gr_cp<ID3D12Device>(s.device);
    bc.fQueue   = gr_cp<ID3D12CommandQueue>(s.queue);
    // fMemoryAllocator = nullptr → Skia uses its built-in allocator.

    sk_sp<GrDirectContext> ctx = GrDirectContext::MakeDirect3D(bc);
    if (!ctx) {
        std::fprintf(stderr,
            "[openjfx-skia] GrDirectContext::MakeDirect3D returned null — "
            "releasing D3D state and falling back to GL.\n");
        // bc's gr_cps will release the extra refs we AddRef'd above.
        // We also release our long-lived globals so the GL fallback
        // isn't competing with a stale D3D12 device.
        releaseD3DGlobals();
        return nullptr;
    }
    if (getenv("OPENJFX_SKIA_D3D_DIAG")) {
        std::fprintf(stderr,
            "[openjfx-skia] D3D12 GrDirectContext created (allowTearing=%d).\n",
            (int)s.allowTearing);
    }
    return ctx;
}

bool openjfxD3DAllowTearing() {
    return g().allowTearing;
}

// ---------------------------------------------------------------------------
// Cross-DLL accessors for in-process 3D consumers (openjfx_skia_3d).
//
// Hand the active D3D12 device/queue out as void* so a sibling DLL can
// initialize bgfx on the SAME device Skia built its GrDirectContext from
// (the zero-copy prerequisite — see docs/3D.md). The pointers are valid
// only after the D3D backend was successfully brought up; null otherwise.
// No AddRef: g() owns the process-lifetime refs (leaked at exit).
// ---------------------------------------------------------------------------
namespace skia_fx {

SKIA_FX_API void* d3d12_device() {
    return g().succeeded ? static_cast<void*>(g().device) : nullptr;
}

SKIA_FX_API void* d3d12_queue() {
    return g().succeeded ? static_cast<void*>(g().queue) : nullptr;
}

// Latches true once the D3D12 device is removed/reset (e.g. a TDR or an adapter
// change while dragging the window across monitors of different DPI). Both Skia and
// bgfx share this single device, so once it is gone EVERY further D3D call fails
// (DXGI_ERROR_DEVICE_REMOVED, 0x887a0005) and hammering it spams errors and can hang
// the driver. Callers (present, swap-chain resize, the bgfx 3D pass) check this and
// skip all GPU work once the device is lost — degrade, never crash (errors-never-
// kill-jvm). It stays latched until a future device-recreate path clears it.
static std::atomic<bool> gDeviceLost{ false };

void d3d12_mark_device_lost() { gDeviceLost.store(true, std::memory_order_relaxed); }

SKIA_FX_API bool d3d12_device_lost() {
    if (gDeviceLost.load(std::memory_order_relaxed)) return true;
    D3DGlobal& s = g();
    if (!s.succeeded || !s.device) return false;
    if (s.device->GetDeviceRemovedReason() != S_OK) {
        gDeviceLost.store(true, std::memory_order_relaxed);
        return true;
    }
    return false;
}

SKIA_FX_API void* d3d12_create_rt_texture(int32_t w, int32_t h) {
    if (w <= 0 || h <= 0) return nullptr;
    if (d3d12_device_lost()) return nullptr; // no GPU allocs on a removed device
    D3DGlobal& s = g();
    if (!s.succeeded || !s.device) return nullptr;

    D3D12_HEAP_PROPERTIES heap = {};
    heap.Type = D3D12_HEAP_TYPE_DEFAULT;

    D3D12_RESOURCE_DESC desc = {};
    desc.Dimension          = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    desc.Width              = static_cast<UINT64>(w);
    desc.Height             = static_cast<UINT>(h);
    desc.DepthOrArraySize   = 1;
    desc.MipLevels          = 1;
    desc.Format             = DXGI_FORMAT_R8G8B8A8_UNORM;
    desc.SampleDesc.Count   = 1;
    desc.SampleDesc.Quality = 0;
    desc.Layout             = D3D12_TEXTURE_LAYOUT_UNKNOWN;
    // RENDER_TARGET so bgfx can draw into it; sampleable as SRV by default
    // (no DENY_SHADER_RESOURCE) so Skia can read it.
    desc.Flags              = D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET;

    ID3D12Resource* res = nullptr;
    HRESULT hr = s.device->CreateCommittedResource(
        &heap, D3D12_HEAP_FLAG_NONE, &desc,
        D3D12_RESOURCE_STATE_RENDER_TARGET,
        /*pOptimizedClearValue*/ nullptr,
        IID_PPV_ARGS(&res));
    if (FAILED(hr) || !res) return nullptr;
    return static_cast<void*>(res);
}

SKIA_FX_API uintptr_t d3d12_wrap_texture_as_image(void* resource, int32_t w, int32_t h) {
    if (!resource || w <= 0 || h <= 0) return 0;
    GrDirectContext* grCtx = shared_gr_context();
    if (!grCtx) return 0;

    auto* res = static_cast<ID3D12Resource*>(resource);
    // gr_cp inside GrD3DTextureResourceInfo ADOPTS without AddRef, and the
    // per-frame SkImage releases that ref when it dies. AddRef once so the
    // caller's cached resource keeps its own ref across frames.
    res->AddRef();
    GrD3DTextureResourceInfo info(
        res,
        /*alloc*/ nullptr,
        D3D12_RESOURCE_STATE_RENDER_TARGET,
        DXGI_FORMAT_R8G8B8A8_UNORM,
        /*sampleCount*/ 1,
        /*levelCount*/ 1,
        /*sampleQualityPattern*/ DXGI_STANDARD_MULTISAMPLE_QUALITY_PATTERN,
        /*protected*/ skgpu::Protected::kNo);

    GrBackendTexture tex(w, h, info);
    sk_sp<SkImage> img = SkImages::BorrowTextureFrom(
        grCtx, tex,
        kTopLeft_GrSurfaceOrigin,
        kRGBA_8888_SkColorType,
        kPremul_SkAlphaType,
        /*colorSpace*/ nullptr);
    if (!img) return 0;
    return register_image(std::move(img));
}

SKIA_FX_API void d3d12_release(void* resource) {
    if (resource) {
        static_cast<ID3D12Resource*>(resource)->Release();
    }
}

// DEBUG (Phase A): read back an RT resource and write a PNG so we can SEE colorRes.
SKIA_FX_API int32_t debug_dump_rt(void* resource, int32_t w, int32_t h, const char* path) {
    if (!resource || w <= 0 || h <= 0 || !path) return -1;
    GrDirectContext* grCtx = shared_gr_context();
    if (!grCtx) return -2;
    auto* res = static_cast<ID3D12Resource*>(resource);
    res->AddRef();
    GrD3DTextureResourceInfo info(res, nullptr, D3D12_RESOURCE_STATE_RENDER_TARGET,
        DXGI_FORMAT_R8G8B8A8_UNORM, 1, 1, DXGI_STANDARD_MULTISAMPLE_QUALITY_PATTERN,
        skgpu::Protected::kNo);
    GrBackendTexture tex(w, h, info);
    sk_sp<SkImage> img = SkImages::BorrowTextureFrom(grCtx, tex, kTopLeft_GrSurfaceOrigin,
        kRGBA_8888_SkColorType, kPremul_SkAlphaType, nullptr);
    if (!img) return -3;
    sk_sp<SkData> data = SkPngEncoder::Encode(grCtx, img.get(), {});
    if (!data) return -6;
    std::FILE* fp = std::fopen(path, "wb");
    if (!fp) return -7;
    std::fwrite(data->data(), 1, data->size(), fp);
    std::fclose(fp);
    return 0;
}

} // namespace skia_fx

// ---------------------------------------------------------------------------
// Per-window swap chain.
// ---------------------------------------------------------------------------

namespace {

// Wrap each back-buffer ID3D12Resource as one SkSurface. The Skia
// surfaces hold their own refs to the underlying D3D resources via
// GrD3DTextureResourceInfo's gr_cp<ID3D12Resource>.
bool wrapBackBuffers(OpenJfxD3DSwapChain* sc, GrDirectContext* grCtx) {
    sc->backBuffers.clear();
    sc->backBuffers.reserve(kBufferCount);
    for (int i = 0; i < kBufferCount; ++i) {
        ID3D12Resource* res = nullptr;
        HRESULT hr = sc->swapChain->GetBuffer(i, IID_PPV_ARGS(&res));
        if (FAILED(hr) || !res) return false;

        // gr_cp adopts without AddRef → we own the ref already.
        GrD3DTextureResourceInfo info(
            res,
            /* alloc */ nullptr,
            D3D12_RESOURCE_STATE_PRESENT,
            kSwapFormat,
            /* sampleCount         */ 1,
            /* levelCount          */ 1,
            /* sampleQualityPattern*/ DXGI_STANDARD_MULTISAMPLE_QUALITY_PATTERN,
            /* protected           */ skgpu::Protected::kNo);

        GrBackendRenderTarget brt(sc->width, sc->height, info);
        sk_sp<SkSurface> surface = SkSurfaces::WrapBackendRenderTarget(
            grCtx, brt,
            kTopLeft_GrSurfaceOrigin,
            kRGBA_8888_SkColorType,
            /* colorSpace    */ nullptr,
            /* surfaceProps  */ nullptr,
            /* releaseProc   */ nullptr,
            /* releaseContext*/ nullptr);
        if (!surface) {
            // GrBackendRenderTarget's gr_cp will Release `res` when it
            // dies; if the wrap failed, dropping `info` here releases.
            return false;
        }
        sc->backBuffers.push_back(std::move(surface));
    }
    return true;
}

} // namespace

OpenJfxD3DSwapChain* openjfxD3DCreateSwapChain(
    void* hwndVoid, int32_t width, int32_t height, GrDirectContext* grCtx) {
    if (!grCtx || !hwndVoid || width <= 0 || height <= 0) return nullptr;
    D3DGlobal& s = g();
    if (!s.succeeded) return nullptr;

    HWND hwnd = reinterpret_cast<HWND>(hwndVoid);

    DXGI_SWAP_CHAIN_DESC1 scd = {};
    scd.Width              = static_cast<UINT>(width);
    scd.Height             = static_cast<UINT>(height);
    scd.Format             = kSwapFormat;
    scd.Stereo             = FALSE;
    scd.SampleDesc.Count   = 1;
    scd.SampleDesc.Quality = 0;
    scd.BufferUsage        = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    scd.BufferCount        = kBufferCount;
    scd.Scaling            = DXGI_SCALING_STRETCH;
    scd.SwapEffect         = DXGI_SWAP_EFFECT_FLIP_DISCARD;
    scd.AlphaMode          = DXGI_ALPHA_MODE_IGNORE;
    // ALLOW_TEARING (when supported) lets Present(0, ALLOW_TEARING)
    // bypass DWM's windowed-vsync cap and uncap fps.
    // NOTE: we deliberately do NOT set FRAME_LATENCY_WAITABLE_OBJECT
    // or call SetMaximumFrameLatency. The waitable, combined with
    // a small MaxFrameLatency, signals at DXGI's swap-chain-ready
    // rate — effectively re-imposes DWM-like pacing, which is what
    // collapsed the previous attempt's fps. We use 3 back buffers
    // + a per-buffer fence to pipeline CPU/GPU instead.
    scd.Flags              = s.allowTearing ? DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING : 0;

    IDXGISwapChain1* sc1 = nullptr;
    HRESULT hr = s.factory->CreateSwapChainForHwnd(
        s.queue, hwnd, &scd, nullptr, nullptr, &sc1);
    if (FAILED(hr) || !sc1) return nullptr;

    // Disable DXGI's stock Alt-Enter handling — we don't want exclusive
    // fullscreen mode transitions for now.
    s.factory->MakeWindowAssociation(hwnd, DXGI_MWA_NO_ALT_ENTER);

    IDXGISwapChain3* sc3 = nullptr;
    hr = sc1->QueryInterface(IID_PPV_ARGS(&sc3));
    sc1->Release();
    if (FAILED(hr) || !sc3) return nullptr;

    auto* out = new OpenJfxD3DSwapChain();
    out->hwnd         = hwnd;
    out->swapChain    = sc3;
    out->width        = width;
    out->height       = height;
    out->allowTearing = s.allowTearing;

    // No SetMaximumFrameLatency — the default (3) is what we want.
    // No GetFrameLatencyWaitableObject — see swap-chain-flag note above.

    // Per-back-buffer fence — every successful Present signals the
    // queue with fenceValues[idx]; the next acquire of that index waits
    // until the GPU is actually done with that buffer. Without this we
    // either over-pipeline (DXGI blocks deep inside Present) or risk
    // overwriting a buffer the front-end hasn't released yet.
    hr = s.device->CreateFence(0, D3D12_FENCE_FLAG_NONE,
                               IID_PPV_ARGS(&out->fence));
    if (FAILED(hr) || !out->fence) {
        openjfxD3DDestroySwapChain(out);
        return nullptr;
    }
    out->fenceEvent = ::CreateEventEx(nullptr, nullptr, 0,
                                      EVENT_MODIFY_STATE | SYNCHRONIZE);
    if (!out->fenceEvent) {
        openjfxD3DDestroySwapChain(out);
        return nullptr;
    }
    // Keep our own queue ref so the swap chain stays self-contained.
    s.queue->AddRef();
    out->queue = s.queue;

    if (!wrapBackBuffers(out, grCtx)) {
        openjfxD3DDestroySwapChain(out);
        return nullptr;
    }

    // Sanity diag: DXGI may round the back-buffer dimensions for
    // certain format/scaling combinations. Compare against what we
    // asked for so any mismatch is visible at the source.
    if (getenv("OPENJFX_SKIA_D3D_DIAG")) {
        DXGI_SWAP_CHAIN_DESC1 actual = {};
        if (SUCCEEDED(out->swapChain->GetDesc1(&actual))) {
            std::fprintf(stderr,
                "[skia.d3d] swapchain created: req=%dx%d  actual=%ux%u  buffers=%u  scaling=%d  swap=%d\n",
                width, height, actual.Width, actual.Height,
                actual.BufferCount, (int)actual.Scaling, (int)actual.SwapEffect);
        }
        // Also: what does the client rect of the HWND we attached to
        // actually measure right now? If it differs from (width, height)
        // the caller is passing the wrong size and DXGI will stretch.
        RECT cr = {};
        if (::GetClientRect(hwnd, &cr)) {
            std::fprintf(stderr,
                "[skia.d3d] hwnd client rect: %ldx%ld  (vs request %dx%d)\n",
                cr.right - cr.left, cr.bottom - cr.top, width, height);
        }
    }
    return out;
}

SkSurface* openjfxD3DCurrentSurface(OpenJfxD3DSwapChain* sc) {
    if (!sc || sc->backBuffers.empty()) return nullptr;
    UINT idx = sc->swapChain->GetCurrentBackBufferIndex();
    if (idx >= sc->backBuffers.size()) return nullptr;
    return sc->backBuffers[idx].get();
}

void* openjfxD3DHwnd(OpenJfxD3DSwapChain* sc) {
    return sc ? (void*)sc->hwnd : nullptr;
}

int32_t openjfxD3DAcquireNextBuffer(OpenJfxD3DSwapChain* sc) {
    if (!sc || !sc->swapChain) return 1;

    // Wait for the back buffer DXGI is about to hand us to have
    // actually finished its previous GPU use. With 3 back buffers
    // pipelined this is almost always already signalled (CPU is at
    // most 2 frames ahead of GPU); when it isn't, we wait one frame.
    // This is the ONLY sync point — no DXGI waitable, no MaxFrameLatency
    // gating — so Present(0, ALLOW_TEARING) actually returns immediately.
    if (sc->fence) {
        UINT idx = sc->swapChain->GetCurrentBackBufferIndex();
        if (idx < kBufferCount) {
            UINT64 expected = sc->fenceValues[idx];
            if (expected != 0 && sc->fence->GetCompletedValue() < expected) {
                sc->fence->SetEventOnCompletion(expected, sc->fenceEvent);
                ::WaitForSingleObject(sc->fenceEvent, INFINITE);
            }
        }
    }
    return 0;
}

int32_t openjfxD3DPresent(OpenJfxD3DSwapChain* sc, GrDirectContext* grCtx, int32_t vsync) {
    if (!sc || !sc->swapChain) return 1;
    if (skia_fx::d3d12_device_lost()) return 3; // device gone — don't touch the GPU
    if (grCtx) grCtx->flushAndSubmit();
    UINT idx = sc->swapChain->GetCurrentBackBufferIndex();
    // VSync ON  -> Present(1, 0): block for the next vblank, no tearing.
    // VSync OFF -> Present(0, ALLOW_TEARING): uncapped, escapes the DWM cap.
    // (DXGI forbids combining a non-zero sync interval with ALLOW_TEARING.)
    UINT syncInterval = vsync ? 1u : 0u;
    UINT flags = vsync ? 0u : (sc->allowTearing ? DXGI_PRESENT_ALLOW_TEARING : 0u);
    HRESULT hr = sc->swapChain->Present(syncInterval, flags);
    if (FAILED(hr)) {
        // A device-removed/reset present is terminal for this device — latch it so
        // every other GPU path (Skia readback, the bgfx 3D pass) stops calling it.
        if (hr == DXGI_ERROR_DEVICE_REMOVED || hr == DXGI_ERROR_DEVICE_RESET) {
            skia_fx::d3d12_mark_device_lost();
        }
        return 2;
    }

    // Signal the fence on the queue *after* the present so the next
    // acquire of this buffer index can wait on this value. Skia's
    // flushAndSubmit above already pushed our work onto sc->queue;
    // Signal here is queued at the back of that work.
    if (sc->fence && sc->queue && idx < kBufferCount) {
        sc->nextFenceValue++;
        sc->fenceValues[idx] = sc->nextFenceValue;
        sc->queue->Signal(sc->fence, sc->nextFenceValue);
    }
    return 0;
}

void openjfxD3DDestroySwapChain(OpenJfxD3DSwapChain* sc) {
    if (!sc) return;
    // Wait for the GPU to drain its queue before tearing anything
    // down — releasing the back-buffer SkSurfaces while the GPU is
    // still reading them is a use-after-free inside the driver.
    if (sc->queue && sc->fence) {
        sc->nextFenceValue++;
        if (SUCCEEDED(sc->queue->Signal(sc->fence, sc->nextFenceValue))) {
            if (sc->fence->GetCompletedValue() < sc->nextFenceValue) {
                sc->fence->SetEventOnCompletion(sc->nextFenceValue, sc->fenceEvent);
                ::WaitForSingleObject(sc->fenceEvent, INFINITE);
            }
        }
    }
    sc->backBuffers.clear(); // releases the per-buffer SkSurfaces
                             // (and their adopted ID3D12Resource refs)
    if (sc->fenceEvent) { ::CloseHandle(sc->fenceEvent); sc->fenceEvent = nullptr; }
    if (sc->fence)      { sc->fence->Release();          sc->fence      = nullptr; }
    if (sc->queue)      { sc->queue->Release();          sc->queue      = nullptr; }
    // The waitable handle is owned by the swap chain and released
    // when the swap chain releases — don't CloseHandle it ourselves.
    sc->waitableObject = nullptr;
    if (sc->swapChain) {
        sc->swapChain->Release();
        sc->swapChain = nullptr;
    }
    delete sc;
}

int32_t openjfxD3DResize(OpenJfxD3DSwapChain* sc,
                         int32_t width, int32_t height,
                         GrDirectContext* grCtx) {
    if (!sc || !sc->swapChain || width <= 0 || height <= 0) return 1;
    if (sc->width == width && sc->height == height) return 0; // no-op
    // A removed device's queue never signals its fence, so the drain below would
    // block forever (and ResizeBuffers would fail anyway). Bail out.
    if (skia_fx::d3d12_device_lost()) return 1;

    // 1. Drain the GPU — IDXGISwapChain::ResizeBuffers requires that
    //    no references to the existing back buffers are outstanding
    //    on either side (CPU Skia refs OR queued GPU work).
    if (sc->queue && sc->fence) {
        sc->nextFenceValue++;
        if (SUCCEEDED(sc->queue->Signal(sc->fence, sc->nextFenceValue))) {
            if (sc->fence->GetCompletedValue() < sc->nextFenceValue) {
                sc->fence->SetEventOnCompletion(sc->nextFenceValue, sc->fenceEvent);
                ::WaitForSingleObject(sc->fenceEvent, INFINITE);
            }
        }
    }
    // Also drop any Skia-owned references the GrDirectContext might
    // still be holding to the soon-to-be-invalid back buffers.
    if (grCtx) {
        grCtx->flushAndSubmit(/* GrSyncCpu::kYes */ GrSyncCpu::kYes);
    }

    // 2. Drop the per-buffer SkSurfaces (releases the ID3D12Resource
    //    refs that Skia adopted in wrapBackBuffers).
    sc->backBuffers.clear();

    // 3. DXGI in-place resize — DWM treats this as a semantic resize,
    //    so it does not stretch the previous composition while the
    //    new buffers are being prepared. This is the artifact-free
    //    property the GL path can't provide.
    UINT flags = sc->allowTearing ? DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING : 0;
    HRESULT hr = sc->swapChain->ResizeBuffers(
        kBufferCount,
        static_cast<UINT>(width), static_cast<UINT>(height),
        kSwapFormat, flags);
    if (FAILED(hr)) return 2;

    sc->width  = width;
    sc->height = height;

    if (getenv("OPENJFX_SKIA_D3D_DIAG")) {
        DXGI_SWAP_CHAIN_DESC1 actual = {};
        if (SUCCEEDED(sc->swapChain->GetDesc1(&actual))) {
            std::fprintf(stderr,
                "[skia.d3d] resize: req=%dx%d  actual=%ux%u\n",
                width, height, actual.Width, actual.Height);
        }
        RECT cr = {};
        if (::GetClientRect(sc->hwnd, &cr)) {
            std::fprintf(stderr,
                "[skia.d3d]   hwnd client rect: %ldx%ld\n",
                cr.right - cr.left, cr.bottom - cr.top);
        }
    }

    // 4. Re-wrap the new back buffers as fresh SkSurfaces.
    if (!wrapBackBuffers(sc, grCtx)) return 3;

    // 5. The GPU is fully idle; all fence values are "done" from the
    //    GPU's perspective. Reset so acquires don't spuriously wait.
    for (int i = 0; i < kBufferCount; ++i) sc->fenceValues[i] = 0;
    return 0;
}
