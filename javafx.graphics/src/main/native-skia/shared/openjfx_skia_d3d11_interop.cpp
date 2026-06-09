// ---------------------------------------------------------------------------
// openjfx_skia_d3d11_interop.cpp — see header for the contract.
//
// Implementation notes:
//   - We create a SINGLE process-wide ID3D11Device with the
//     D3D11_CREATE_DEVICE_BGRA_SUPPORT flag (required for D2D/GL interop)
//     and feature level 11_0 (covers ~all GPUs from 2010+).
//   - WGL_NV_DX_interop2 function pointers are resolved via
//     wglGetProcAddress; we don't link against a static .lib for them.
//   - All entry points are noexcept-by-construction (return-code based,
//     never throw across the FFM boundary).
// ---------------------------------------------------------------------------

#include "openjfx_skia_d3d11_interop.h"

#if defined(_WIN32)

#include <cstdio>
#include <cstdint>
#include <atomic>
#include <vector>
#include <unordered_map>
#include <mutex>

#define WIN32_LEAN_AND_MEAN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <Windows.h>
#include <d3d11.h>
#include <GL/gl.h>

// ---------------------------------------------------------------------------
// WGL_NV_DX_interop2 extension declarations.
// Function pointer types + access-flag values. These aren't in the
// stock gl.h shipped with the Windows SDK; we declare them inline so the
// module compiles without an extra glext.h header dependency.
// ---------------------------------------------------------------------------
#ifndef WGL_ACCESS_READ_ONLY_NV
#define WGL_ACCESS_READ_ONLY_NV     0x00000000
#define WGL_ACCESS_READ_WRITE_NV    0x00000001
#define WGL_ACCESS_WRITE_DISCARD_NV 0x00000002
#endif

typedef HANDLE (WINAPI * PFN_wglDXOpenDeviceNV)(void* dxDevice);
typedef BOOL   (WINAPI * PFN_wglDXCloseDeviceNV)(HANDLE hDevice);
typedef HANDLE (WINAPI * PFN_wglDXRegisterObjectNV)(HANDLE hDevice, void* dxObject,
                                                   GLuint name, GLenum type, GLenum access);
typedef BOOL   (WINAPI * PFN_wglDXUnregisterObjectNV)(HANDLE hDevice, HANDLE hObject);
typedef BOOL   (WINAPI * PFN_wglDXLockObjectsNV)(HANDLE hDevice, GLint count, HANDLE* hObjects);
typedef BOOL   (WINAPI * PFN_wglDXUnlockObjectsNV)(HANDLE hDevice, GLint count, HANDLE* hObjects);

namespace {

struct InteropFns {
    PFN_wglDXOpenDeviceNV       openDevice       = nullptr;
    PFN_wglDXCloseDeviceNV      closeDevice      = nullptr;
    PFN_wglDXRegisterObjectNV   registerObject   = nullptr;
    PFN_wglDXUnregisterObjectNV unregisterObject = nullptr;
    PFN_wglDXLockObjectsNV      lockObjects      = nullptr;
    PFN_wglDXUnlockObjectsNV    unlockObjects    = nullptr;

    bool valid() const {
        return openDevice && closeDevice && registerObject
            && unregisterObject && lockObjects && unlockObjects;
    }
};

// Single per-process state.
struct InteropState {
    std::atomic<bool>     initTried{false};
    std::atomic<bool>     ready{false};

    ID3D11Device*         d3dDevice = nullptr;
    ID3D11DeviceContext*  d3dContext = nullptr;
    HANDLE                wglInteropDevice = nullptr;
    InteropFns            fns;
};

InteropState g_state;

// Each registered texture keeps its WGL handle + GL texture name +
// (optionally) the source D3D11 texture for the smoke-test path which
// also owns the texture. mfwrapper-supplied textures keep ownerTex=nullptr.
struct TextureEntry {
    HANDLE             wglHandle = nullptr;
    GLuint             glTexture = 0;
    ID3D11Texture2D*   ownerTex  = nullptr;   // smoke test only: we own
    bool               locked    = false;
};

// Resolve WGL extension function pointers. Requires a current GL
// context on the calling thread.
bool loadExtensions(InteropFns& out) {
    out.openDevice       = (PFN_wglDXOpenDeviceNV)      wglGetProcAddress("wglDXOpenDeviceNV");
    out.closeDevice      = (PFN_wglDXCloseDeviceNV)     wglGetProcAddress("wglDXCloseDeviceNV");
    out.registerObject   = (PFN_wglDXRegisterObjectNV)  wglGetProcAddress("wglDXRegisterObjectNV");
    out.unregisterObject = (PFN_wglDXUnregisterObjectNV)wglGetProcAddress("wglDXUnregisterObjectNV");
    out.lockObjects      = (PFN_wglDXLockObjectsNV)     wglGetProcAddress("wglDXLockObjectsNV");
    out.unlockObjects    = (PFN_wglDXUnlockObjectsNV)   wglGetProcAddress("wglDXUnlockObjectsNV");
    return out.valid();
}

} // anonymous namespace

// ---------------------------------------------------------------------------
// init / shutdown
// ---------------------------------------------------------------------------
extern "C" OPENJFX_INTEROP_API int32_t openjfx_skia_d3d11_interop_init(void) {
    // initTried is set BEFORE any work — if init fails partway, callers
    // get cached "ready=false" instead of retrying every frame.
    bool expected = false;
    if (!g_state.initTried.compare_exchange_strong(expected, true)) {
        return g_state.ready.load() ? 1 : 0;
    }

    if (!wglGetCurrentContext()) {
        std::fprintf(stderr,
            "[openjfx-skia.d3d11] init failed: no current GL context.\n");
        return 0;
    }

    // Resolve WGL_NV_DX_interop2 entry points.
    if (!loadExtensions(g_state.fns)) {
        std::fprintf(stderr,
            "[openjfx-skia.d3d11] init failed: WGL_NV_DX_interop2 unavailable "
            "(driver too old). Falling back to CPU upload path.\n");
        return 0;
    }

    // Create the shared D3D11 device. BGRA_SUPPORT lets us interop
    // with D2D. VIDEO_SUPPORT enables the ID3D11VideoDevice /
    // ID3D11VideoContext interfaces — needed so producer plugins
    // (ffmpegwrapper / mfwrapper) can run their VideoProcessor on
    // *this* device instead of a separate one, which eliminates the
    // cross-device synchronisation race that WGL_NV_DX_interop2's
    // lock/unlock can't bridge (D3D11 hazard tracking is per-device,
    // so VP writes on a producer-private device weren't ordered
    // against GL reads on our device — manifested as occasional
    // flicker between current and one-frame-stale content).
    // Feature level 11_0 covers ~all Windows GPUs from 2010+.
    UINT createFlags = D3D11_CREATE_DEVICE_BGRA_SUPPORT
                     | D3D11_CREATE_DEVICE_VIDEO_SUPPORT;
#ifndef NDEBUG
    // D3D11 debug layer only when the SDK layers DLL is present —
    // silently fall back when it's not (release machines often miss it).
    UINT triedFlags = createFlags | D3D11_CREATE_DEVICE_DEBUG;
#else
    UINT triedFlags = createFlags;
#endif

    D3D_FEATURE_LEVEL featureLevels[] = {
        D3D_FEATURE_LEVEL_11_1,
        D3D_FEATURE_LEVEL_11_0,
        D3D_FEATURE_LEVEL_10_1,
        D3D_FEATURE_LEVEL_10_0,
    };
    D3D_FEATURE_LEVEL gotLevel = D3D_FEATURE_LEVEL_11_0;

    HRESULT hr = D3D11CreateDevice(
        /*adapter*/        nullptr,
        /*driverType*/     D3D_DRIVER_TYPE_HARDWARE,
        /*software*/       nullptr,
        /*flags*/          triedFlags,
        /*featureLevels*/  featureLevels,
        /*nLevels*/        ARRAYSIZE(featureLevels),
        /*sdkVersion*/     D3D11_SDK_VERSION,
        /*pDevice*/        &g_state.d3dDevice,
        /*pFeatureLevel*/  &gotLevel,
        /*pContext*/       &g_state.d3dContext);

    if (FAILED(hr) && triedFlags != createFlags) {
        // Retry without the debug flag (SDK layers DLL missing).
        hr = D3D11CreateDevice(
            nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
            createFlags,
            featureLevels, ARRAYSIZE(featureLevels),
            D3D11_SDK_VERSION,
            &g_state.d3dDevice, &gotLevel, &g_state.d3dContext);
    }

    if (FAILED(hr) || !g_state.d3dDevice) {
        std::fprintf(stderr,
            "[openjfx-skia.d3d11] init failed: D3D11CreateDevice 0x%08lx.\n",
            (unsigned long)hr);
        return 0;
    }

    // Open the WGL interop device on top of our D3D11 device.
    g_state.wglInteropDevice = g_state.fns.openDevice(g_state.d3dDevice);
    if (!g_state.wglInteropDevice) {
        std::fprintf(stderr,
            "[openjfx-skia.d3d11] init failed: wglDXOpenDeviceNV returned NULL "
            "(driver may not support DX interop with this device).\n");
        if (g_state.d3dContext) { g_state.d3dContext->Release(); g_state.d3dContext = nullptr; }
        if (g_state.d3dDevice)  { g_state.d3dDevice->Release();  g_state.d3dDevice  = nullptr; }
        return 0;
    }

    g_state.ready.store(true);
    std::fprintf(stderr,
        "[openjfx-skia.d3d11] init OK: D3D11 feature level 0x%04x, WGL interop ready.\n",
        (unsigned)gotLevel);
    return 1;
}

extern "C" OPENJFX_INTEROP_API void* openjfx_skia_d3d11_interop_get_device(void) {
    return g_state.ready.load() ? g_state.d3dDevice : nullptr;
}

extern "C" OPENJFX_INTEROP_API int32_t openjfx_skia_d3d11_interop_ready(void) {
    return g_state.ready.load() ? 1 : 0;
}

extern "C" OPENJFX_INTEROP_API void openjfx_skia_d3d11_interop_shutdown(void) {
    if (!g_state.ready.exchange(false)) return;
    if (g_state.wglInteropDevice && g_state.fns.closeDevice) {
        g_state.fns.closeDevice(g_state.wglInteropDevice);
        g_state.wglInteropDevice = nullptr;
    }
    if (g_state.d3dContext) { g_state.d3dContext->Release(); g_state.d3dContext = nullptr; }
    if (g_state.d3dDevice)  { g_state.d3dDevice->Release();  g_state.d3dDevice  = nullptr; }
}

// ---------------------------------------------------------------------------
// Per-texture registration
// ---------------------------------------------------------------------------
extern "C" OPENJFX_INTEROP_API void*
openjfx_skia_d3d11_interop_register_texture(void* d3d11Texture,
                                             uint32_t* glTextureOut) {
    if (!g_state.ready.load() || !d3d11Texture) return nullptr;

    // No dedup: each register creates a fresh entry + WGL handle, even
    // when the same D3D11 texture pointer reappears (producer's pool
    // wrapped). Why: wglDXLockObjectsNV provides snapshot-at-lock
    // semantics; sharing a single entry across consecutive frames means
    // the second register's lock is idempotent (no-op) and the GL alias
    // keeps showing the OLD snapshot's content even after the producer
    // wrote new content. The "two locks on the same DX object" UB is
    // avoided structurally by the pool's refcount-aware reuse: with our
    // ownerTex AddRef below, the producer can't recycle a slot whose
    // interop entry is still alive, so concurrent live entries on the
    // same DX object don't actually happen.

    auto* entry = new TextureEntry();
    glGenTextures(1, &entry->glTexture);
    if (entry->glTexture == 0) {
        delete entry;
        return nullptr;
    }
    // AddRef the underlying D3D11Texture so the consumer's lifetime is
    // independent of the producer's GstBuffer queue. The pool's refcount-
    // aware recycle (probe_refcount == 1) sees rc>=2 (pool + interop)
    // until the last consumer reference is gone — guarantees the
    // producer can't overwrite a slot whose GL alias is still in flight.
    auto* tex = static_cast<ID3D11Texture2D*>(d3d11Texture);
    tex->AddRef();
    entry->ownerTex = tex;
    entry->wglHandle = g_state.fns.registerObject(
        g_state.wglInteropDevice,
        d3d11Texture,
        entry->glTexture,
        GL_TEXTURE_2D,
        WGL_ACCESS_READ_ONLY_NV);
    if (!entry->wglHandle) {
        std::fprintf(stderr,
            "[openjfx-skia.d3d11] register_texture: wglDXRegisterObjectNV "
            "returned NULL (last error %lu).\n",
            (unsigned long)::GetLastError());
        glDeleteTextures(1, &entry->glTexture);
        if (entry->ownerTex) entry->ownerTex->Release();
        delete entry;
        return nullptr;
    }
    if (glTextureOut) *glTextureOut = entry->glTexture;
    return entry;
}

extern "C" OPENJFX_INTEROP_API int32_t
openjfx_skia_d3d11_interop_lock(void* handle) {
    auto* entry = static_cast<TextureEntry*>(handle);
    if (!entry || !g_state.ready.load()) return 0;
    if (entry->locked) return 1; // idempotent
    BOOL ok = g_state.fns.lockObjects(g_state.wglInteropDevice, 1, &entry->wglHandle);
    entry->locked = (ok == TRUE);
    return entry->locked ? 1 : 0;
}

extern "C" OPENJFX_INTEROP_API int32_t
openjfx_skia_d3d11_interop_unlock(void* handle) {
    auto* entry = static_cast<TextureEntry*>(handle);
    if (!entry || !g_state.ready.load() || !entry->locked) return 1;
    BOOL ok = g_state.fns.unlockObjects(g_state.wglInteropDevice, 1, &entry->wglHandle);
    entry->locked = false;
    return ok ? 1 : 0;
}

extern "C" OPENJFX_INTEROP_API void
openjfx_skia_d3d11_interop_unregister_texture(void* handle) {
    auto* entry = static_cast<TextureEntry*>(handle);
    if (!entry) return;
    if (entry->locked && g_state.ready.load()) {
        g_state.fns.unlockObjects(g_state.wglInteropDevice, 1, &entry->wglHandle);
        entry->locked = false;
    }
    if (entry->wglHandle && g_state.ready.load()) {
        g_state.fns.unregisterObject(g_state.wglInteropDevice, entry->wglHandle);
    }
    if (entry->glTexture) {
        glDeleteTextures(1, &entry->glTexture);
    }
    if (entry->ownerTex) {
        entry->ownerTex->Release();
    }
    delete entry;
}

// ---------------------------------------------------------------------------
// Smoke test — proves the M1 path end-to-end
// ---------------------------------------------------------------------------
extern "C" OPENJFX_INTEROP_API void*
openjfx_skia_d3d11_interop_smoke_test_make(uint8_t r, uint8_t g, uint8_t b,
                                            uint32_t* glTextureOut) {
    if (!g_state.ready.load()) return nullptr;

    // 1) Make a 256×256 RGBA D3D11 texture with init data.
    D3D11_TEXTURE2D_DESC desc = {};
    desc.Width = 256;
    desc.Height = 256;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_SHADER_RESOURCE;
    desc.CPUAccessFlags = 0;
    desc.MiscFlags = D3D11_RESOURCE_MISC_SHARED;  // sharable for interop

    std::vector<uint8_t> pixels(256 * 256 * 4);
    for (size_t i = 0; i < pixels.size(); i += 4) {
        pixels[i + 0] = r;
        pixels[i + 1] = g;
        pixels[i + 2] = b;
        pixels[i + 3] = 255;
    }
    D3D11_SUBRESOURCE_DATA init = {};
    init.pSysMem = pixels.data();
    init.SysMemPitch = 256 * 4;

    ID3D11Texture2D* tex = nullptr;
    HRESULT hr = g_state.d3dDevice->CreateTexture2D(&desc, &init, &tex);
    if (FAILED(hr) || !tex) {
        std::fprintf(stderr,
            "[openjfx-skia.d3d11.smoke] CreateTexture2D failed 0x%08lx\n",
            (unsigned long)hr);
        return nullptr;
    }

    // 2) Register with WGL interop.
    auto* entry = static_cast<TextureEntry*>(
        openjfx_skia_d3d11_interop_register_texture(tex, glTextureOut));
    if (!entry) {
        tex->Release();
        return nullptr;
    }
    entry->ownerTex = tex; // we own it for the smoke test
    return entry;
}

extern "C" OPENJFX_INTEROP_API void
openjfx_skia_d3d11_interop_smoke_test_release(void* handle) {
    openjfx_skia_d3d11_interop_unregister_texture(handle);
}

#else  // !_WIN32

// Non-Windows: every entry point is a no-op returning failure.
extern "C" OPENJFX_INTEROP_API int32_t openjfx_skia_d3d11_interop_init(void) { return 0; }
extern "C" OPENJFX_INTEROP_API void*   openjfx_skia_d3d11_interop_get_device(void) { return nullptr; }
extern "C" OPENJFX_INTEROP_API int32_t openjfx_skia_d3d11_interop_ready(void) { return 0; }
extern "C" OPENJFX_INTEROP_API void    openjfx_skia_d3d11_interop_shutdown(void) {}
extern "C" OPENJFX_INTEROP_API void* openjfx_skia_d3d11_interop_register_texture(void*, uint32_t*) { return nullptr; }
extern "C" OPENJFX_INTEROP_API int32_t openjfx_skia_d3d11_interop_lock(void*) { return 0; }
extern "C" OPENJFX_INTEROP_API int32_t openjfx_skia_d3d11_interop_unlock(void*) { return 0; }
extern "C" OPENJFX_INTEROP_API void    openjfx_skia_d3d11_interop_unregister_texture(void*) {}
extern "C" OPENJFX_INTEROP_API void*   openjfx_skia_d3d11_interop_smoke_test_make(uint8_t,uint8_t,uint8_t,uint32_t*) { return nullptr; }
extern "C" OPENJFX_INTEROP_API void    openjfx_skia_d3d11_interop_smoke_test_release(void*) {}

#endif
