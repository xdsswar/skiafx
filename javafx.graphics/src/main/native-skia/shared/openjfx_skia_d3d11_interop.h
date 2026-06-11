// ---------------------------------------------------------------------------
// openjfx_skia_d3d11_interop — D3D11 ⇄ OpenGL interop (Windows, NV_DX_interop2)
//
// Phase-3 / M1 of the javafx.media zero-copy plan. This module owns the
// per-process ID3D11Device and the WGL_NV_DX_interop2 handle that lets
// us hand D3D11 textures to OpenGL (Skia's Ganesh GL backend) without
// copying pixels through CPU memory.
//
// The shared D3D11 device is reused by:
//   - mfwrapper (Phase-3 M2): MediaFoundation's H.264 decoder outputs
//     into THIS device's textures, so we don't have to cross-device-
//     share.
//   - Skia (Phase-3 M4): textures registered here become GL textures
//     that Skia can wrap as SkImage via GrBackendTexture.
//
// All entry points are `extern "C"` and use primitive / opaque-pointer
// types so they're callable from FFM without any C++ symbol mangling.
// ---------------------------------------------------------------------------
#ifndef OPENJFX_SKIA_D3D11_INTEROP_H
#define OPENJFX_SKIA_D3D11_INTEROP_H

#include <stdint.h>

#ifdef _WIN32
  #define OPENJFX_INTEROP_API __declspec(dllexport)
#else
  #define OPENJFX_INTEROP_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

/**
 * Creates the process-wide ID3D11Device and opens the WGL_NV_DX_interop2
 * device handle bound to it. Must be called AFTER the GL context is
 * current (wglMakeCurrent succeeded) — the interop extension uses the
 * calling thread's current GL context.
 *
 * Returns 1 on success, 0 on failure (no GPU, missing WGL extension,
 * D3D11 device creation failed). After failure the rest of this module
 * is a no-op and callers fall back to the CPU upload path.
 *
 * Idempotent: subsequent calls return the cached state.
 */
OPENJFX_INTEROP_API int32_t openjfx_skia_d3d11_interop_init(void);

/**
 * Returns the shared ID3D11Device pointer (as void*) so mfwrapper can
 * configure MediaFoundation to use it for hardware decode. Returns NULL
 * if init() hasn't succeeded.
 */
OPENJFX_INTEROP_API void* openjfx_skia_d3d11_interop_get_device(void);

/** Returns 1 if init() succeeded and the interop pipeline is usable. */
OPENJFX_INTEROP_API int32_t openjfx_skia_d3d11_interop_ready(void);

/**
 * Blocks new register/lock calls for the next `millis` milliseconds
 * (extends an active window, never shortens it). Called by the surface
 * resize / swap-chain rebuild paths: a concurrent GL<->D3D11 interop
 * sync during a rebuild can deadlock in the driver (observed desktop-
 * level freeze leaving fullscreen during 4K zero-copy playback). While
 * quiesced the media texture keeps showing its previous frame; unlock /
 * unregister are never blocked.
 */
OPENJFX_INTEROP_API void openjfx_skia_d3d11_interop_quiesce(int32_t millis);

/**
 * Tears down the interop device + D3D11 device. Called once at shutdown
 * (or from a Cleaner action). Safe to call when init never succeeded.
 */
OPENJFX_INTEROP_API void openjfx_skia_d3d11_interop_shutdown(void);

// ---------------------------------------------------------------------------
// Per-texture registration (used by mfwrapper at M2+ and by SkiaMediaTexture
// at M4). The interop "handle" is opaque; pair every register with one
// unregister, and bracket draws with lock/unlock.
// ---------------------------------------------------------------------------

/**
 * Registers a D3D11 texture with the WGL interop device, producing a
 * GL texture name (output param) that aliases the same VRAM. Returns
 * an opaque HANDLE used by lock/unlock/unregister, or NULL on failure.
 *
 * @param d3d11Texture  ID3D11Texture2D* (as void* for ABI).
 * @param glTextureOut  Receives the GL texture name. Caller doesn't
 *                      glGenTextures — this function does.
 */
OPENJFX_INTEROP_API void* openjfx_skia_d3d11_interop_register_texture(
    void* d3d11Texture,
    uint32_t* glTextureOut);

/**
 * Locks the registered texture for GL access. Must be called before
 * each Skia draw that samples the GL texture. The lock blocks D3D11
 * from modifying the underlying surface for the lock's duration.
 *
 * Returns 1 on success, 0 on failure.
 */
OPENJFX_INTEROP_API int32_t openjfx_skia_d3d11_interop_lock(void* handle);

/** Unlocks. Pair with every lock. Returns 1 on success. */
OPENJFX_INTEROP_API int32_t openjfx_skia_d3d11_interop_unlock(void* handle);

/**
 * Unregisters the texture + deletes the aliased GL texture name. The
 * underlying D3D11 texture is NOT released (the caller still owns it).
 */
OPENJFX_INTEROP_API void openjfx_skia_d3d11_interop_unregister_texture(void* handle);

// ---------------------------------------------------------------------------
// Smoke test — M1 acceptance criterion
// ---------------------------------------------------------------------------

/**
 * End-to-end smoke test:
 *   1. Create a 256×256 RGBA D3D11 texture.
 *   2. Fill it (via D3D11 staging upload) with a solid color.
 *   3. Register it with WGL interop.
 *   4. Lock it.
 *   5. Return the GL texture name so a test caller can draw it.
 *   6. Caller draws the GL texture via Skia.
 *   7. Caller calls smoke_test_release().
 *
 * @param red,green,blue  fill color (0–255).
 * @param glTextureOut    receives the aliased GL texture name.
 * @return                opaque handle (pass to smoke_test_release).
 *                        NULL on failure.
 */
OPENJFX_INTEROP_API void* openjfx_skia_d3d11_interop_smoke_test_make(
    uint8_t red, uint8_t green, uint8_t blue,
    uint32_t* glTextureOut);

/** Unlocks + unregisters + releases the smoke-test texture. */
OPENJFX_INTEROP_API void openjfx_skia_d3d11_interop_smoke_test_release(void* handle);

#ifdef __cplusplus
}
#endif

#endif // OPENJFX_SKIA_D3D11_INTEROP_H
