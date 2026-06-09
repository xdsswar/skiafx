/*
 * openjfx_skia3d_bridge.h — C ABI surface for the bgfx-backed 3D
 * pipeline, callable from Java via java.lang.foreign (FFM).
 *
 * Every entry point here is `extern "C"` and uses primitive / pointer
 * (uintptr_t handle) types only, mirroring openjfx_skia_bridge.h, so it
 * round-trips cleanly through FFM. No Skia or bgfx types cross this ABI.
 *
 * This is a SEPARATE shared library (openjfx_skia_3d) from the 2D Skia
 * bridge (openjfx_skia_shared): the bgfx dependency is isolated so the
 * production 2D bridge builds and ships even when bgfx is absent. The
 * 3D lib links openjfx_skia_shared and reaches the shared
 * GrDirectContext / D3D12 device via the skia_fx::* C++ accessors.
 *
 * See docs/3D.md for the architecture (Door 1 / Door 2, zero-copy
 * handback) and CLAUDE.md for the project-wide constraints.
 */

#ifndef OPENJFX_SKIA3D_BRIDGE_H
#define OPENJFX_SKIA3D_BRIDGE_H

#include <stdint.h>

#ifdef _WIN32
  #define OPENJFX3D_API __declspec(dllexport)
#else
  #define OPENJFX3D_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Reports whether the native 3D renderer is present AND usable on the
 * active GPU backend. Returns 1 when bgfx can share Skia's device for a
 * zero-copy handback (currently: Windows + D3D12), 0 otherwise.
 *
 * Java's SKIAPipeline.is3DSupported() will gate on this once Door 1
 * lands; until then it lets a caller probe availability without forcing
 * any GPU work. Never throws / aborts.
 */
OPENJFX3D_API int32_t openjfx_skia3d_available(void);

/**
 * SPIKE ONLY (Increment 1, see docs/3D.md): render one lit cube via
 * bgfx into a texture allocated on Skia's device and composite it,
 * zero-copy, onto the Skia surface identified by `surfaceHandle` at a
 * fixed destination rect of size w x h.
 *
 * Returns 0 on success, or a nonzero failure code (wrong backend,
 * null/invalid handle, init failure, device loss, ...). MUST degrade
 * cleanly — never abort the process or throw across the ABI. Callers
 * ignore the result so the spike can never break normal painting.
 *
 * This entry is replaced by the real ResourceFactory / SubScene path in
 * a later increment; it exists purely to prove the zero-copy keystone.
 */
OPENJFX3D_API int32_t openjfx_skia3d_spike_composite(uintptr_t surfaceHandle,
                                                     int32_t w, int32_t h);

/* ---- Door 1: real JavaFX 3D scene graph -------------------------------- *
 * Resource handles are opaque uintptr_t (pointers to native structs). All
 * entries validate inputs and never abort/throw. See docs/3D.md.          */

OPENJFX3D_API uintptr_t openjfx_skia3d_mesh_create(void);
OPENJFX3D_API int32_t   openjfx_skia3d_mesh_build_int(uintptr_t mesh,
        const float* vb, int32_t vlen, const int32_t* ib, int32_t ilen);
OPENJFX3D_API int32_t   openjfx_skia3d_mesh_build_short(uintptr_t mesh,
        const float* vb, int32_t vlen, const int16_t* ib, int32_t ilen);
OPENJFX3D_API void      openjfx_skia3d_mesh_destroy(uintptr_t mesh);

OPENJFX3D_API uintptr_t openjfx_skia3d_material_create(void);
OPENJFX3D_API void      openjfx_skia3d_material_set_diffuse(uintptr_t mat,
        float r, float g, float b, float a);
OPENJFX3D_API void      openjfx_skia3d_material_set_specular(uintptr_t mat,
        int32_t set, float r, float g, float b);
// Upload an RGBA8 texture map (typeOrdinal: 0=diffuse,1=specular,2=bump,3=self-illum).
// pixels == null clears the slot. imageId is a stable per-Image id (0 = un-shared) so
// the texture can be reused by other materials via openjfx_skia3d_material_bind_texture.
OPENJFX3D_API void      openjfx_skia3d_material_set_texture(uintptr_t mat,
        int32_t typeOrdinal, const void* pixels, int32_t w, int32_t h, uint64_t imageId);
// Bind an already-uploaded shared texture (by imageId) into the slot, taking a
// reference. Returns 1 if found+bound, 0 if not registered (caller uploads instead).
OPENJFX3D_API int32_t   openjfx_skia3d_material_bind_texture(uintptr_t mat,
        int32_t typeOrdinal, uint64_t imageId);
OPENJFX3D_API void      openjfx_skia3d_material_destroy(uintptr_t mat);

OPENJFX3D_API uintptr_t openjfx_skia3d_meshview_create(uintptr_t mesh);
OPENJFX3D_API void      openjfx_skia3d_meshview_set_material(uintptr_t mv, uintptr_t mat);
OPENJFX3D_API void      openjfx_skia3d_meshview_set_culling(uintptr_t mv, int32_t mode);
OPENJFX3D_API void      openjfx_skia3d_meshview_set_wireframe(uintptr_t mv, int32_t wf);
OPENJFX3D_API void      openjfx_skia3d_meshview_set_ambient(uintptr_t mv,
        float r, float g, float b);
OPENJFX3D_API void      openjfx_skia3d_meshview_set_light(uintptr_t mv, int32_t index,
        float x, float y, float z, float r, float g, float b, float w,
        float ca, float la, float qa, float isAttenuated, float maxRange,
        float dirX, float dirY, float dirZ,
        float innerAngle, float outerAngle, float falloff);
OPENJFX3D_API void      openjfx_skia3d_meshview_destroy(uintptr_t mv);

OPENJFX3D_API uintptr_t openjfx_skia3d_target_create(int32_t w, int32_t h, int32_t samples);
OPENJFX3D_API void      openjfx_skia3d_target_destroy(uintptr_t target);
OPENJFX3D_API int32_t   openjfx_skia3d_target_begin(uintptr_t target,
        float r, float g, float b, float a);
OPENJFX3D_API int32_t   openjfx_skia3d_target_end(uintptr_t target);
// Notify the 3D pipeline that one real swap-chain present completed. Drives the
// deferred target-free latency (see the .cpp). Safe to call when 3D is idle.
OPENJFX3D_API void      openjfx_skia3d_notify_present(void);
OPENJFX3D_API uintptr_t openjfx_skia3d_target_wrap_image(uintptr_t target);

OPENJFX3D_API int32_t   openjfx_skia3d_draw(uintptr_t target, uintptr_t meshview,
        uintptr_t material, const float* projView16, const float* model16,
        float camX, float camY, float camZ);

#ifdef __cplusplus
} // extern "C"
#endif

#endif /* OPENJFX_SKIA3D_BRIDGE_H */
