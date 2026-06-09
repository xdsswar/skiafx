/*
 * openjfx_skia3d_bridge.cpp — bgfx-backed 3D pipeline, C ABI entry
 * points. See openjfx_skia3d_bridge.h and docs/3D.md.
 *
 * Increment 1 / Phases 2-4:
 *   - bring bgfx up on Skia's D3D12 device (shared device + queue,
 *     single-threaded);
 *   - render into a color texture WE allocate on Skia's device
 *     (currently a cycling clear — the geometry/shaders land next);
 *   - hand that SAME texture back to Skia ZERO-COPY via
 *     SkImages::BorrowTextureFrom and composite it into the window.
 *
 * The bgfx calls live here (no d3d12.h). All raw D3D12 / Skia-D3D work
 * (resource creation, GrBackendTexture wrap) is confined to
 * openjfx_skia3d_d3d.cpp and reached through opaque void* / uintptr_t.
 *
 * Hard rule (CLAUDE.md / [errors-never-kill-jvm]): validate inputs,
 * return failure codes, never abort/SIGSEGV, never throw across the ABI.
 */

#include "openjfx_skia3d_bridge.h"
#include "skia_fx_bridge.h"      // skia_fx::backend_is_d3d / d3d12_device / d3d12_queue

#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <unordered_map>
#include <unordered_set>

#ifdef OPENJFX_SKIA3D_HAVE_BGFX
#include <bgfx/bgfx.h>
#include <bgfx/platform.h>
#include <bx/math.h>
#ifdef OPENJFX_SKIA3D_HAVE_SHADERS
#include "dxbc/vs_cube.sc.bin.h"    // vs_cube_dxbc
#include "dxbc/fs_cube.sc.bin.h"    // fs_cube_dxbc
#include "dxbc/vs_phong.sc.bin.h"   // vs_phong_dxbc
#include "dxbc/fs_phong.sc.bin.h"   // fs_phong_dxbc
#include "dxbc/vs_shadow.sc.bin.h"  // vs_shadow_dxbc
#include "dxbc/fs_shadow.sc.bin.h"  // fs_shadow_dxbc
#endif
#include <cstring>
#include <vector>

// C ABI from openjfx_skia_shared (the 2D bridge) — composite + release.
extern "C" {
int32_t openjfx_skia_surface_draw_image_rect(
    uintptr_t surfaceHandle, uintptr_t imageHandle,
    float sx, float sy, float sw, float sh,
    float dx, float dy, float dw, float dh);
void openjfx_skia_image_destroy(uintptr_t imageHandle);
}
#endif

// Failure codes returned across the ABI (0 == success).
enum : int32_t {
    SKIA3D_OK            = 0,
    SKIA3D_BAD_ARGS      = 2,
    SKIA3D_WRONG_BACKEND = 3,
    SKIA3D_INIT_FAILED   = 4,
    SKIA3D_NO_BGFX       = 5,
};

namespace {

bool verbose() {
    static const int v = (std::getenv("OPENJFX_SKIA_3D_DIAG") != nullptr) ? 1 : 0;
    return v != 0;
}

#ifdef OPENJFX_SKIA3D_HAVE_BGFX

// Fixed offscreen size for the spike RT.
constexpr int kRtW = 256;
constexpr int kRtH = 256;

// All bgfx state below is touched only on the render thread.
std::atomic<bool> gInitTried{false};
std::atomic<bool> gInitOk{false};
// Written by the bgfx fatal callback (potentially a bgfx-owned thread) and read
// by the entry points — atomic so that cross-thread write/read isn't UB.
std::atomic<bool> gBroken{false};

struct RtState {
    void*                   res   = nullptr;  // ID3D12Resource* on the shared device
    bgfx::TextureHandle     tex   = BGFX_INVALID_HANDLE;
    bgfx::TextureHandle     depth = BGFX_INVALID_HANDLE;
    bgfx::FrameBufferHandle fb    = BGFX_INVALID_HANDLE;
    bool                    ready = false;
};
RtState gRt;

#ifdef OPENJFX_SKIA3D_HAVE_SHADERS
// ---- Cube geometry (position + normal), unit cube centered at origin ----
struct PosNormalVertex { float x, y, z, nx, ny, nz; };

const PosNormalVertex kCubeVerts[] = {
    // +X
    { 1,-1,-1,  1,0,0}, { 1, 1,-1,  1,0,0}, { 1, 1, 1,  1,0,0}, { 1,-1, 1,  1,0,0},
    // -X
    {-1,-1, 1, -1,0,0}, {-1, 1, 1, -1,0,0}, {-1, 1,-1, -1,0,0}, {-1,-1,-1, -1,0,0},
    // +Y
    {-1, 1,-1,  0,1,0}, {-1, 1, 1,  0,1,0}, { 1, 1, 1,  0,1,0}, { 1, 1,-1,  0,1,0},
    // -Y
    {-1,-1,-1,  0,-1,0},{ 1,-1,-1,  0,-1,0},{ 1,-1, 1,  0,-1,0},{-1,-1, 1,  0,-1,0},
    // +Z
    { 1,-1, 1,  0,0,1}, { 1, 1, 1,  0,0,1}, {-1, 1, 1,  0,0,1}, {-1,-1, 1,  0,0,1},
    // -Z
    {-1,-1,-1,  0,0,-1},{-1, 1,-1,  0,0,-1},{ 1, 1,-1,  0,0,-1},{ 1,-1,-1,  0,0,-1},
};

const uint16_t kCubeIndices[] = {
     0, 1, 2,  0, 2, 3,    4, 5, 6,  4, 6, 7,    8, 9,10,  8,10,11,
    12,13,14, 12,14,15,   16,17,18, 16,18,19,   20,21,22, 20,22,23,
};

struct CubeState {
    bgfx::VertexBufferHandle vbh       = BGFX_INVALID_HANDLE;
    bgfx::IndexBufferHandle  ibh       = BGFX_INVALID_HANDLE;
    bgfx::ProgramHandle      prog      = BGFX_INVALID_HANDLE;
    bgfx::UniformHandle      uLightDir = BGFX_INVALID_HANDLE;
    bgfx::UniformHandle      uColor    = BGFX_INVALID_HANDLE;
    bool                     ready     = false;
};
CubeState gCube;
#endif // OPENJFX_SKIA3D_HAVE_SHADERS

// bgfx must NEVER abort the process (CLAUDE.md). The default callback
// calls abort() on Fatal; ours logs and flags broken instead.
struct SkiaBgfxCallback final : public bgfx::CallbackI {
    ~SkiaBgfxCallback() override {}

    void fatal(const char* filePath, uint16_t line,
               bgfx::Fatal::Enum code, const char* str) override {
        std::fprintf(stderr, "[skia3d] bgfx FATAL code=%d %s:%u %s\n",
                     (int) code, filePath ? filePath : "?",
                     (unsigned) line, str ? str : "");
        gBroken = true;
    }
    void traceVargs(const char*, uint16_t, const char* format, va_list argList) override {
        if (verbose() && format) {
            std::vfprintf(stderr, format, argList);
        }
    }
    void profilerBegin(const char*, uint32_t, const char*, uint16_t) override {}
    void profilerBeginLiteral(const char*, uint32_t, const char*, uint16_t) override {}
    void profilerEnd() override {}
    uint32_t cacheReadSize(uint64_t) override { return 0; }
    bool cacheRead(uint64_t, void*, uint32_t) override { return false; }
    void cacheWrite(uint64_t, const void*, uint32_t) override {}
    void screenShot(const char*, uint32_t, uint32_t, uint32_t,
                    bgfx::TextureFormat::Enum, const void*, uint32_t, bool) override {}
    void captureBegin(uint32_t, uint32_t, uint32_t,
                      bgfx::TextureFormat::Enum, bool) override {}
    void captureEnd() override {}
    void captureFrame(const void*, uint32_t) override {}
};

SkiaBgfxCallback gCallback;

bool ensureBgfxInit() {
    if (gInitTried.load(std::memory_order_acquire)) {
        return gInitOk;
    }
    gInitTried.store(true, std::memory_order_release);

    if (!skia_fx::backend_is_d3d()) {
        std::fprintf(stderr,
            "[skia3d] active Skia backend is not D3D12 (set OPENJFX_SKIA_D3D=1); "
            "3D spike disabled.\n");
        return false;
    }
    void* device = skia_fx::d3d12_device();
    void* queue  = skia_fx::d3d12_queue();
    if (device == nullptr || queue == nullptr) {
        std::fprintf(stderr,
            "[skia3d] no D3D12 device/queue from the shared bridge; "
            "3D spike disabled.\n");
        return false;
    }

    // renderFrame() BEFORE init() => single-threaded on THIS (render)
    // thread, submitting to the shared command queue in order.
    bgfx::renderFrame();

    bgfx::Init init;
    init.type                      = bgfx::RendererType::Direct3D12;
    init.platformData.ndt          = nullptr;
    init.platformData.nwh          = nullptr;   // headless: we own the RT
    init.platformData.context      = device;    // share Skia's ID3D12Device
    init.platformData.queue        = queue;     // share Skia's ID3D12CommandQueue
    init.platformData.backBuffer   = nullptr;
    init.platformData.backBufferDS = nullptr;
    init.resolution.width          = kRtW;
    init.resolution.height         = kRtH;
    init.resolution.reset          = BGFX_RESET_NONE;
    init.callback                  = &gCallback;

    if (!bgfx::init(init)) {
        std::fprintf(stderr, "[skia3d] bgfx::init failed.\n");
        gBroken = true;
        return false;
    }
    if (verbose()) {
        std::fprintf(stderr,
            "[skia3d] bgfx Direct3D12 init OK on shared device (renderer=%s).\n",
            bgfx::getRendererName(bgfx::getRendererType()));
    }
    gInitOk = true;
    return true;
}

// Allocate the shared color RT (on Skia's device) once and bind it into
// a bgfx framebuffer via overrideInternal.
bool ensureRt() {
    if (gRt.ready) return true;

    // Create the color RT on Skia's device (in the shared bridge — one
    // Skia, one GrDirectContext).
    gRt.res = skia_fx::d3d12_create_rt_texture(kRtW, kRtH);
    if (gRt.res == nullptr) {
        std::fprintf(stderr, "[skia3d] failed to create shared D3D RT.\n");
        gBroken = true;
        return false;
    }
    gRt.tex = bgfx::createTexture2D(
        (uint16_t) kRtW, (uint16_t) kRtH, false, 1,
        bgfx::TextureFormat::RGBA8, BGFX_TEXTURE_RT);
    // Materialize bgfx's internal texture, then point it at OUR resource.
    bgfx::frame();
    const uintptr_t got = bgfx::overrideInternal(
        gRt.tex, reinterpret_cast<uintptr_t>(gRt.res));
    if (got == 0 && verbose()) {
        std::fprintf(stderr,
            "[skia3d] overrideInternal returned 0 (texture not yet created?).\n");
    }
    // Color (our shared resource) + a bgfx-owned depth buffer. destroy=
    // false so bgfx never frees our shared color resource.
    gRt.depth = bgfx::createTexture2D(
        (uint16_t) kRtW, (uint16_t) kRtH, false, 1,
        bgfx::TextureFormat::D32F, BGFX_TEXTURE_RT_WRITE_ONLY);
    bgfx::TextureHandle handles[2] = { gRt.tex, gRt.depth };
    gRt.fb = bgfx::createFrameBuffer(2, handles, false);
    gRt.ready = true;
    if (verbose()) {
        std::fprintf(stderr, "[skia3d] shared RT %dx%d (color+depth) bound into bgfx framebuffer.\n",
                     kRtW, kRtH);
    }
    return true;
}

#ifdef OPENJFX_SKIA3D_HAVE_SHADERS
// Create the cube VB/IB, the Phong-ish program, and uniforms once.
bool ensureCubeResources() {
    if (gCube.ready) return true;

    bgfx::VertexLayout layout;
    layout.begin()
        .add(bgfx::Attrib::Position, 3, bgfx::AttribType::Float)
        .add(bgfx::Attrib::Normal,   3, bgfx::AttribType::Float)
        .end();

    gCube.vbh = bgfx::createVertexBuffer(
        bgfx::copy(kCubeVerts, sizeof(kCubeVerts)), layout);
    gCube.ibh = bgfx::createIndexBuffer(
        bgfx::copy(kCubeIndices, sizeof(kCubeIndices)));

    bgfx::ShaderHandle vsh = bgfx::createShader(
        bgfx::makeRef(vs_cube_dxbc, sizeof(vs_cube_dxbc)));
    bgfx::ShaderHandle fsh = bgfx::createShader(
        bgfx::makeRef(fs_cube_dxbc, sizeof(fs_cube_dxbc)));
    gCube.prog = bgfx::createProgram(vsh, fsh, /*destroyShaders*/ true);

    gCube.uLightDir = bgfx::createUniform("u_lightDir", bgfx::UniformType::Vec4);
    gCube.uColor    = bgfx::createUniform("u_color",    bgfx::UniformType::Vec4);

    gCube.ready = bgfx::isValid(gCube.prog);
    if (!gCube.ready) {
        std::fprintf(stderr, "[skia3d] cube program invalid.\n");
    }
    return gCube.ready;
}
#endif // OPENJFX_SKIA3D_HAVE_SHADERS

// Render one frame into the shared RT: a lit, slowly rotating cube
// (depth-tested). Falls back to a cycling clear if shaders are absent.
void renderInto() {
    bgfx::setViewFrameBuffer(0, gRt.fb);
    bgfx::setViewRect(0, 0, 0, (uint16_t) kRtW, (uint16_t) kRtH);

#ifdef OPENJFX_SKIA3D_HAVE_SHADERS
    if (ensureCubeResources()) {
        bgfx::setViewClear(0, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH,
                           0x202830ff, 1.0f, 0);

        float view[16];
        float proj[16];
        float model[16];
        const bx::Vec3 at  = { 0.0f, 0.0f, 0.0f };
        const bx::Vec3 eye = { 0.0f, 0.0f, -4.5f };
        bx::mtxLookAt(view, eye, at);
        bx::mtxProj(proj, 60.0f, float(kRtW) / float(kRtH), 0.1f, 100.0f,
                    bgfx::getCaps()->homogeneousDepth);

        static float angle = 0.0f;
        angle += 0.02f;
        bx::mtxRotateXY(model, angle * 0.73f, angle);

        bgfx::setViewTransform(0, view, proj);
        bgfx::setTransform(model);
        bgfx::setVertexBuffer(0, gCube.vbh);
        bgfx::setIndexBuffer(gCube.ibh);

        const float lightDir[4] = { 0.4f, 0.7f, -0.6f, 0.0f };
        const float color[4]    = { 0.30f, 0.70f, 1.0f, 1.0f };
        bgfx::setUniform(gCube.uLightDir, lightDir);
        bgfx::setUniform(gCube.uColor, color);

        bgfx::setState(BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A
                       | BGFX_STATE_WRITE_Z | BGFX_STATE_DEPTH_TEST_LESS);
        bgfx::submit(0, gCube.prog);
        bgfx::frame();
        return;
    }
#endif

    // Fallback: cycling clear (no shaders compiled in).
    static uint32_t f = 0;
    ++f;
    const uint32_t r = (f) & 0xff;
    const uint32_t g = (f * 2u) & 0xff;
    const uint32_t b = (f * 3u) & 0xff;
    bgfx::setViewClear(0, BGFX_CLEAR_COLOR,
                       (r << 24) | (g << 16) | (b << 8) | 0xffu, 1.0f, 0);
    bgfx::touch(0);
    bgfx::frame();
}

// ===========================================================================
// Door 1: real scene-graph 3D — per-SubScene targets, meshes, materials,
// meshviews (with lights), and a Phong draw. All on the shared device,
// composited zero-copy. View id 1 (separate from the spike's view 0).
// ===========================================================================

constexpr uint16_t kView3D = 1;

// Device generation. The HOOK for a future device-loss recovery: a recovery would
// bump this so every resource built under the old GPU device fails the guard check
// (asMesh/asTarget/...) and is treated as stale. Device-loss RECOVERY is deferred
// (in-process bgfx re-init on the shared device is unsafe — see docs/3D.md), so this
// stays at 1 today and the generation check is inert but in place. Starts at 1 so a
// zeroed/garbage struct (generation 0) never validates. Render-thread-owned; atomic
// as defensive future-proofing for the eventual recovery path.
std::atomic<uint32_t> gDeviceGen{1};

// Common validity preamble for every native 3D resource struct. `magic` is a
// distinct per-type tag (set at construction, poisoned to 0 on destroy) so a
// null / wild / already-freed / wrong-type handle handed back from Java is
// rejected instead of dereferenced (no use-after-free) — this is the active
// protection. `deviceGen` records the device generation the resource was built
// under; the guards also compare it (the deferred device-loss-recovery hook above).
// See the as*() helpers.
#define SKIA3D_VALIDITY(MAGIC)                                               \
    static constexpr uint32_t kMagic = (MAGIC);                             \
    uint32_t magic     = kMagic;                                            \
    uint32_t deviceGen = gDeviceGen.load(std::memory_order_relaxed)

struct Mesh3D {
    SKIA3D_VALIDITY(0x33444D31u); // "3DM1"
    bgfx::VertexBufferHandle vbh        = BGFX_INVALID_HANDLE;
    bgfx::IndexBufferHandle  ibh        = BGFX_INVALID_HANDLE;
    uint32_t                 numIndices = 0;
    // For DrawMode.LINE wireframe: a CPU copy of the triangle indices, used to lazily
    // build an edge (line-list) index buffer on first wireframe draw.
    std::vector<uint32_t>    triIndices;
    bgfx::IndexBufferHandle  edgeIbh    = BGFX_INVALID_HANDLE;
};

// Release a mesh's GPU buffers (VB/IB + any lazily-built wireframe edge buffer).
// JavaFX TriangleMesh is mutable: editing points/faces rebuilds on the SAME
// Mesh3D*, so without this the previous VB/IB/edge buffer leaks on every edit
// (unbounded GPU growth) and a stale edge buffer renders last frame's topology.
inline void releaseMeshBuffers(Mesh3D* m) {
    if (bgfx::isValid(m->vbh))     { bgfx::destroy(m->vbh);     m->vbh = BGFX_INVALID_HANDLE; }
    if (bgfx::isValid(m->ibh))     { bgfx::destroy(m->ibh);     m->ibh = BGFX_INVALID_HANDLE; }
    if (bgfx::isValid(m->edgeIbh)) { bgfx::destroy(m->edgeIbh); m->edgeIbh = BGFX_INVALID_HANDLE; }
}

// Reject index buffers that reference vertices past the supplied vertex array.
// App-supplied mesh data is a danger vector: an out-of-range index makes bgfx
// read freed/garbage GPU memory. floatsPerVertex = 9 (pos3 + uv2 + quat4).
inline bool indicesInRange(const int32_t* ib, int32_t ilen, int32_t vlen) {
    const int32_t numVerts = vlen / 9;
    for (int32_t i = 0; i < ilen; ++i) {
        if (ib[i] < 0 || ib[i] >= numVerts) return false;
    }
    return true;
}

// Lazily build a line-list index buffer (triangle edges) for wireframe rendering.
inline void ensureEdgeIndexBuffer(Mesh3D* m) {
    if (!m || bgfx::isValid(m->edgeIbh) || m->triIndices.size() < 3) return;
    std::vector<uint32_t> edges;
    edges.reserve(m->triIndices.size() * 2);
    for (size_t i = 0; i + 2 < m->triIndices.size(); i += 3) {
        uint32_t a = m->triIndices[i], b = m->triIndices[i + 1], c = m->triIndices[i + 2];
        edges.push_back(a); edges.push_back(b);
        edges.push_back(b); edges.push_back(c);
        edges.push_back(c); edges.push_back(a);
    }
    m->edgeIbh = bgfx::createIndexBuffer(
        bgfx::copy(edges.data(), (uint32_t) (edges.size() * sizeof(uint32_t))),
        BGFX_BUFFER_INDEX32);
}

struct Material3D {
    SKIA3D_VALIDITY(0x33444131u); // "3DA1"
    float diffuse[4]  = { 1, 1, 1, 1 };
    float specular[4] = { 0, 0, 0, 0 }; // rgb + a = power (0 = no specular)
    // JavaFX PhongMaterial texture maps, indexed by MapType ordinal:
    // 0=DIFFUSE, 1=SPECULAR, 2=BUMP(normal), 3=SELF_ILLUM. Invalid handle = absent
    // (shader falls back to the solid colors above via u_mapFlags).
    bgfx::TextureHandle tex[4] = {
        BGFX_INVALID_HANDLE, BGFX_INVALID_HANDLE,
        BGFX_INVALID_HANDLE, BGFX_INVALID_HANDLE
    };
    // Source-image id backing each slot (0 = none). Multiple materials that use the
    // same JavaFX Image share ONE refcounted GPU texture (see gSharedTex), so the
    // 4K upload + mipmap chain is built once, not once per material. The id lets the
    // destroy/replace path release the right shared entry.
    uint64_t texImageId[4] = { 0, 0, 0, 0 };
};
// MapType ordinals (mirror com.sun.prism.PhongMaterial.MapType).
enum { MAP_DIFFUSE = 0, MAP_SPECULAR = 1, MAP_BUMP = 2, MAP_SELFILLUM = 3, MAP_COUNT = 4 };

// Max simultaneous lights per shape. Stock JavaFX/Prism caps at 3; we raise it to 8
// ("better than stock"). NGShape3D (our copy) sends the first MAX_LIGHTS lights.
enum { MAX_LIGHTS = 8 };

struct MeshView3D {
    SKIA3D_VALIDITY(0x33445631u); // "3DV1"
    Mesh3D* mesh = nullptr;
    float ambient[3]               = { 0, 0, 0 };
    // Unified light model (matches the stock JavaFX shader):
    float lightPos[MAX_LIGHTS][4]   = {}; // xyz world position
    float lightColor[MAX_LIGHTS][4] = {}; // rgb + w (w = light on/off, 1/0)
    float lightDir[MAX_LIGHTS][4]   = {}; // xyz spotlight direction
    float lightAttn[MAX_LIGHTS][4]  = {}; // ca, la, qa, isAttenuated (0=directional)
    float lightSpot[MAX_LIGHTS][4]  = {}; // cosOuter, denom(cosInner-cosOuter), falloff, range
    int   cull             = 0;
    bool  wireframe        = false;
};

struct Target3D {
    SKIA3D_VALIDITY(0x33445431u); // "3DT1"
    void*                   colorRes = nullptr; // ID3D12Resource* on Skia's device
    // colorTex is the single-sample RESOLVE DESTINATION bound to colorRes (what
    // Skia wraps zero-copy). When MSAA is active, the scene is rendered into the
    // multisampled msaaColor/msaaDepth framebuffer and resolved into colorTex via
    // bgfx::blit; when MSAA is off, colorTex itself is the framebuffer color.
    bgfx::TextureHandle     colorTex  = BGFX_INVALID_HANDLE;
    bgfx::TextureHandle     msaaColor = BGFX_INVALID_HANDLE; // MSAA color (resolvable)
    bgfx::TextureHandle     msaaDepth = BGFX_INVALID_HANDLE; // MSAA depth (write-only)
    bgfx::FrameBufferHandle fb        = BGFX_INVALID_HANDLE;
    int                     w = 0, h = 0;
    int                     samples = 1; // 1 = no MSAA; 2/4/8 = MSAA active
};
#undef SKIA3D_VALIDITY

// ---- Resource registry + handle-validity guards ------------------------------
// Render-thread-only (all 3D GPU work happens on QuantumRenderer), so no locking —
// mirrors the gSharedTex comment. These sets track every live resource so a
// device-loss recovery (Phase 1b) can iterate and invalidate them; they are mutated
// only on create/destroy, never per frame (no per-frame heap allocation).
std::unordered_set<Mesh3D*>     gMeshes;
std::unordered_set<Material3D*> gMaterials;
std::unordered_set<MeshView3D*> gMeshViews;
std::unordered_set<Target3D*>   gTargets;

inline void registerMesh(Mesh3D* m)         { if (m) gMeshes.insert(m); }
inline void unregisterMesh(Mesh3D* m)       { if (m) gMeshes.erase(m); }
inline void registerMaterial(Material3D* m) { if (m) gMaterials.insert(m); }
inline void unregisterMaterial(Material3D* m){ if (m) gMaterials.erase(m); }
inline void registerMeshView(MeshView3D* v) { if (v) gMeshViews.insert(v); }
inline void unregisterMeshView(MeshView3D* v){ if (v) gMeshViews.erase(v); }
inline void registerTarget(Target3D* t)     { if (t) gTargets.insert(t); }
inline void unregisterTarget(Target3D* t)   { if (t) gTargets.erase(t); }

// Validate a raw Java-supplied handle before any dereference / GPU call. Rejects
// (returns nullptr): a null/wild pointer, an already-destroyed one (poisoned magic),
// a wrong-type one (each struct has a distinct magic), and a stale one left over
// from a previous GPU device generation (rejected so it is lazily rebuilt rather
// than touched). This is the single choke point that makes "never use destroyed or
// unreferenced GPU resources" hold for every entry point.
// NOTE: each guard checks REGISTRY MEMBERSHIP first (a hash lookup on the pointer
// value — no dereference). Because every destroy unregisters BEFORE `delete`, this
// rejects a freed / already-destroyed / wild pointer WITHOUT touching its memory,
// so we never read freed heap (e.g. a MeshView's cached Mesh3D* whose mesh was
// destroyed) and never dereference a garbage handle. Only after membership is
// confirmed do we read magic/deviceGen. Render-thread-only, no locks.
inline Mesh3D* asMesh(uintptr_t h) {
    auto* p = reinterpret_cast<Mesh3D*>(h);
    if (!p || gMeshes.count(p) == 0) return nullptr;
    if (p->magic != Mesh3D::kMagic) return nullptr;
    if (p->deviceGen != gDeviceGen.load(std::memory_order_relaxed)) return nullptr;
    return p;
}
inline Material3D* asMaterial(uintptr_t h) {
    auto* p = reinterpret_cast<Material3D*>(h);
    if (!p || gMaterials.count(p) == 0) return nullptr;
    if (p->magic != Material3D::kMagic) return nullptr;
    if (p->deviceGen != gDeviceGen.load(std::memory_order_relaxed)) return nullptr;
    return p;
}
inline MeshView3D* asMeshView(uintptr_t h) {
    // MeshView3D owns NO GPU resource (only CPU light/cull/wireframe state + a Mesh3D*
    // that is revalidated separately at draw), so a device-generation change does not
    // invalidate it — validate by registry membership + magic only.
    auto* p = reinterpret_cast<MeshView3D*>(h);
    if (!p || gMeshViews.count(p) == 0) return nullptr;
    if (p->magic != MeshView3D::kMagic) return nullptr;
    return p;
}
inline Target3D* asTarget(uintptr_t h) {
    auto* p = reinterpret_cast<Target3D*>(h);
    if (!p || gTargets.count(p) == 0) return nullptr;
    if (p->magic != Target3D::kMagic) return nullptr;
    if (p->deviceGen != gDeviceGen.load(std::memory_order_relaxed)) return nullptr;
    return p;
}

struct PhongProgram {
    bgfx::ProgramHandle prog = BGFX_INVALID_HANDLE;
    bgfx::VertexLayout  layout;
    bgfx::UniformHandle uAmbient    = BGFX_INVALID_HANDLE;
    bgfx::UniformHandle uDiffuse    = BGFX_INVALID_HANDLE;
    bgfx::UniformHandle uSpecular   = BGFX_INVALID_HANDLE;
    bgfx::UniformHandle uCamPos     = BGFX_INVALID_HANDLE;
    bgfx::UniformHandle uLightPos   = BGFX_INVALID_HANDLE;
    bgfx::UniformHandle uLightColor = BGFX_INVALID_HANDLE;
    bgfx::UniformHandle uLightDir   = BGFX_INVALID_HANDLE;
    bgfx::UniformHandle uLightAttn  = BGFX_INVALID_HANDLE;
    bgfx::UniformHandle uLightSpot  = BGFX_INVALID_HANDLE;
    // Texture-map samplers (diffuse/specular/bump/self-illum) + a vec4 telling the
    // shader which maps are bound (x,y,z,w = diffuse,specular,bump,selfIllum; 1=present).
    bgfx::UniformHandle sDiffuse    = BGFX_INVALID_HANDLE;
    bgfx::UniformHandle sSpecular   = BGFX_INVALID_HANDLE;
    bgfx::UniformHandle sBump       = BGFX_INVALID_HANDLE;
    bgfx::UniformHandle sSelfIllum  = BGFX_INVALID_HANDLE;
    bgfx::UniformHandle uMapFlags   = BGFX_INVALID_HANDLE;
    // Shadow mapping: the world→light-clip matrix (for the shadow-map lookup), the
    // shadow map sampler, and (x=enabled, y=bias, z=strength) params.
    bgfx::UniformHandle uLightViewProj = BGFX_INVALID_HANDLE;
    bgfx::UniformHandle sShadow        = BGFX_INVALID_HANDLE;
    bgfx::UniformHandle uShadowParams  = BGFX_INVALID_HANDLE;
    bool ready = false;
};
PhongProgram gPhong;

// ---- Shadow mapping (single directional "sun", whole-scene) -------------------
// A depth-from-light pass (bgfx view kViewShadow, rendered BEFORE the main view so
// the phong pass can sample it the same frame) writes light-space depth into an R32F
// map; the phong shader compares each fragment's light-space depth against it. The
// sun direction + orthographic light frustum are fixed and cover the scene — a first
// increment; per-light / camera-fit (cascaded) shadows are a later refinement.
constexpr uint16_t kViewShadow = 0;       // < kView3D(1): renders first each frame
constexpr int      kShadowMapSize = 2048;
struct ShadowState {
    bgfx::TextureHandle     map   = BGFX_INVALID_HANDLE; // R32F light-space depth
    bgfx::TextureHandle     depth = BGFX_INVALID_HANDLE; // depth buffer for the pass
    bgfx::FrameBufferHandle fb    = BGFX_INVALID_HANDLE;
    bgfx::ProgramHandle     prog  = BGFX_INVALID_HANDLE; // vs_shadow + fs_shadow
    float lightViewProj[16] = {};
    bool  enabled = true;
    bool  ready   = false;
    bool  failed  = false;
};
ShadowState gShadow;
// Latched once the phong program fails to build (bad DXBC / device loss mid-init)
// so ensurePhongProgram() doesn't re-create + re-leak shaders/uniforms on every
// subsequent draw/mesh_build call (the build is deterministic — retrying spins).
bool gPhongFailed = false;

inline uint32_t packRGBA(float r, float g, float b, float a) {
    auto c = [](float v) -> uint32_t {
        int x = (int) (v * 255.0f + 0.5f);
        return (uint32_t) (x < 0 ? 0 : (x > 255 ? 255 : x));
    };
    return (c(r) << 24) | (c(g) << 16) | (c(b) << 8) | c(a);
}

bool ensurePhongProgram() {
#ifdef OPENJFX_SKIA3D_HAVE_SHADERS
    if (gPhong.ready) return true;
    if (gPhongFailed) return false;     // don't retry a deterministically-failing build
    if (!ensureBgfxInit()) return false;

    // Matches BaseMesh's 9-float interleaved vertex: pos(3) + uv(2) +
    // normal-as-quaternion(4). The quat rides in the Tangent slot.
    gPhong.layout.begin()
        .add(bgfx::Attrib::Position,  3, bgfx::AttribType::Float)
        .add(bgfx::Attrib::TexCoord0, 2, bgfx::AttribType::Float)
        .add(bgfx::Attrib::Tangent,   4, bgfx::AttribType::Float)
        .end();

    bgfx::ShaderHandle vsh = bgfx::createShader(
        bgfx::makeRef(vs_phong_dxbc, sizeof(vs_phong_dxbc)));
    bgfx::ShaderHandle fsh = bgfx::createShader(
        bgfx::makeRef(fs_phong_dxbc, sizeof(fs_phong_dxbc)));
    gPhong.prog = bgfx::createProgram(vsh, fsh, true);
    if (!bgfx::isValid(gPhong.prog)) {
        // createProgram only destroys the shaders on SUCCESS — free them here so a
        // retry doesn't leak two shader handles, and latch the failure so we don't
        // re-attempt (and leak the uniforms below) on every frame.
        if (bgfx::isValid(vsh)) bgfx::destroy(vsh);
        if (bgfx::isValid(fsh)) bgfx::destroy(fsh);
        gPhongFailed = true;
        std::fprintf(stderr, "[skia3d] phong program invalid.\n");
        return false;
    }

    gPhong.uAmbient    = bgfx::createUniform("u_ambient",    bgfx::UniformType::Vec4);
    gPhong.uDiffuse    = bgfx::createUniform("u_diffuse",    bgfx::UniformType::Vec4);
    gPhong.uSpecular   = bgfx::createUniform("u_specular",   bgfx::UniformType::Vec4);
    gPhong.uCamPos     = bgfx::createUniform("u_camPos",     bgfx::UniformType::Vec4);
    gPhong.uLightPos   = bgfx::createUniform("u_lightPos",   bgfx::UniformType::Vec4, MAX_LIGHTS);
    gPhong.uLightColor = bgfx::createUniform("u_lightColor", bgfx::UniformType::Vec4, MAX_LIGHTS);
    gPhong.uLightDir   = bgfx::createUniform("u_lightDir",   bgfx::UniformType::Vec4, MAX_LIGHTS);
    gPhong.uLightAttn  = bgfx::createUniform("u_lightAttn",  bgfx::UniformType::Vec4, MAX_LIGHTS);
    gPhong.uLightSpot  = bgfx::createUniform("u_lightSpot",  bgfx::UniformType::Vec4, MAX_LIGHTS);
    gPhong.sDiffuse    = bgfx::createUniform("s_diffuse",    bgfx::UniformType::Sampler);
    gPhong.sSpecular   = bgfx::createUniform("s_specular",   bgfx::UniformType::Sampler);
    gPhong.sBump       = bgfx::createUniform("s_bump",       bgfx::UniformType::Sampler);
    gPhong.sSelfIllum  = bgfx::createUniform("s_selfIllum",  bgfx::UniformType::Sampler);
    gPhong.uMapFlags   = bgfx::createUniform("u_mapFlags",   bgfx::UniformType::Vec4);
    gPhong.uLightViewProj = bgfx::createUniform("u_lightViewProj", bgfx::UniformType::Mat4);
    gPhong.sShadow        = bgfx::createUniform("s_shadow",        bgfx::UniformType::Sampler);
    gPhong.uShadowParams  = bgfx::createUniform("u_shadowParams",  bgfx::UniformType::Vec4);

    gPhong.ready = true;
    return true;
#else
    return false;
#endif
}

// Create the shadow map (R32F) + depth buffer + framebuffer + shadow program, and
// compute the fixed sun's world→light-clip matrix once. Returns false (and latches
// failed) if shadows can't be set up, so the phong pass simply renders unshadowed.
bool ensureShadowResources() {
#ifdef OPENJFX_SKIA3D_HAVE_SHADERS
    if (gShadow.ready)  return true;
    if (gShadow.failed) return false;
    if (skia_fx::d3d12_device_lost() || !ensureBgfxInit()) return false;

    gShadow.map = bgfx::createTexture2D((uint16_t) kShadowMapSize, (uint16_t) kShadowMapSize,
        false, 1, bgfx::TextureFormat::R32F,
        BGFX_TEXTURE_RT | BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP
        | BGFX_SAMPLER_MIN_POINT | BGFX_SAMPLER_MAG_POINT);
    gShadow.depth = bgfx::createTexture2D((uint16_t) kShadowMapSize, (uint16_t) kShadowMapSize,
        false, 1, bgfx::TextureFormat::D32F, BGFX_TEXTURE_RT_WRITE_ONLY);
    bgfx::TextureHandle atts[2] = { gShadow.map, gShadow.depth };
    gShadow.fb = bgfx::createFrameBuffer(2, atts, false);

    bgfx::ShaderHandle vsh = bgfx::createShader(bgfx::makeRef(vs_shadow_dxbc, sizeof(vs_shadow_dxbc)));
    bgfx::ShaderHandle fsh = bgfx::createShader(bgfx::makeRef(fs_shadow_dxbc, sizeof(fs_shadow_dxbc)));
    gShadow.prog = bgfx::createProgram(vsh, fsh, true);
    if (!bgfx::isValid(gShadow.fb) || !bgfx::isValid(gShadow.prog)) {
        if (bgfx::isValid(vsh))           bgfx::destroy(vsh);
        if (bgfx::isValid(fsh))           bgfx::destroy(fsh);
        if (bgfx::isValid(gShadow.fb))    bgfx::destroy(gShadow.fb);
        if (bgfx::isValid(gShadow.map))   bgfx::destroy(gShadow.map);
        if (bgfx::isValid(gShadow.depth)) bgfx::destroy(gShadow.depth);
        gShadow = ShadowState{};
        gShadow.failed = true;
        if (verbose()) {
            std::fprintf(stderr, "[skia3d] shadow resources invalid — rendering unshadowed.\n");
        }
        return false;
    }

    // Fixed directional "sun": +Y is DOWN in JavaFX, so a light shining down has
    // direction ~+Y; place the light camera above (−Y) looking down. Orthographic
    // frustum sized to cover the demo scene (floor 1500², shapes within ±900).
    const bx::Vec3 dir    = bx::normalize({ 0.35f, 1.0f, 0.25f });
    const bx::Vec3 center = { 0.0f, 150.0f, 0.0f };
    const bx::Vec3 eye    = { center.x - dir.x * 2600.0f,
                              center.y - dir.y * 2600.0f,
                              center.z - dir.z * 2600.0f };
    const bx::Vec3 up     = { 0.0f, 0.0f, 1.0f };
    float view[16], proj[16];
    bx::mtxLookAt(view, eye, center, up);
    bx::mtxOrtho(proj, -1300.0f, 1300.0f, -1300.0f, 1300.0f, 1.0f, 5200.0f,
                 0.0f, bgfx::getCaps()->homogeneousDepth);
    // Same combine bgfx's setViewTransform does internally, so the uniform matches
    // u_viewProj used in the shadow pass: mul(viewProj, pos) = proj·view·pos.
    bx::mtxMul(gShadow.lightViewProj, view, proj);

    gShadow.ready = true;
    return true;
#else
    return false;
#endif
}

// Begin the shadow depth pass for this pulse: bind the shadow framebuffer to the
// shadow view, clear it to "far" (1.0), and set the light's view-projection. Called
// once per SubScene before its draws; each draw then also submits to kViewShadow.
void shadowBeginPass() {
    bgfx::setViewFrameBuffer(kViewShadow, gShadow.fb);
    bgfx::setViewRect(kViewShadow, 0, 0, (uint16_t) kShadowMapSize, (uint16_t) kShadowMapSize);
    bgfx::setViewClear(kViewShadow, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, 0xffffffff, 1.0f, 0);
    bgfx::setViewTransform(kViewShadow, nullptr, gShadow.lightViewProj);
    bgfx::touch(kViewShadow);
}

// Whether shadows are active this run. OPT-IN (OPENJFX_SKIA_3D_SHADOWS=1): the fixed
// "sun" + whole-scene frustum is a first increment whose direction won't match an
// arbitrary app's lights, so it must not apply by default. A proper per-SubScene /
// light-driven shadow API is a later refinement.
bool shadowsEnabled() {
    static const bool on = [] {
        const char* v = std::getenv("OPENJFX_SKIA_3D_SHADOWS");
        return v && v[0] && v[0] != '0';
    }();
    return on && gShadow.enabled;
}

// ---- Deferred target destruction ---------------------------------------
// A SubScene resize makes NGSubScene dispose+recreate its RTT every size
// tick, so target_destroy fires every frame during a drag. Freeing the
// resources immediately needs a full GPU stall (use-after-free otherwise),
// which makes the resize crawl + show stale/overlapped frames. Instead we
// QUEUE the target and free it a few frames later, by which point the GPU
// has presented past the frame that last used it — safe, and no stall.

struct PendingFree3D {
    Target3D* t;
    uint64_t  frame;
};
std::vector<PendingFree3D> gPendingFree;
uint64_t gFrameNum3D = 0;                 // SubScene-pass counter (diagnostics only)
// Real swap-chain PRESENT counter — the deferred-free clock. target_end runs once
// per SubScene pass (N times per pulse for N SubScenes) and only SUBMITS to the GPU
// queue; it does not present or wait. Keying the free off it meant that with ≥3
// SubScenes the latency elapsed within a single pulse, freeing a target's raw
// colorRes while the GPU was still presenting it (UAF on strict drivers). This
// counter advances once per actual present (openjfx_skia3d_notify_present, driven
// from SkiaPresentable.present()), so "kFreeLatency presents past" is now true.
std::atomic<uint64_t> gPresentNum3D{0};
int gDumpCount = 0; // DEBUG (Phase A): colorRes PNG dump counter
constexpr uint64_t kFreeLatency = 3; // PRESENTS the GPU is guaranteed past (= kBufferCount)

// Resolve an MSAA sample count to what the device actually supports. Returns the
// usable sample count (1 = no MSAA) and sets *outFlag to the matching
// BGFX_TEXTURE_RT_MSAA_* flag (0 when samples == 1). Degrades to 1 (no AA) rather
// than failing when blit/MSAA isn't available.
//
// `requested` is the caller's desired count:
//   <= 0  → use the global default from OPENJFX_SKIA_3D_MSAA (or 4 if unset). This is
//           the per-SubScene "use the default" path.
//   1     → AA explicitly off.
//   2/4/8 → that count, snapped down to device support.
// Per-SubScene control (dev.skiafx.scene3d.Skia3D) passes an explicit count here.
int resolveMsaaSamples(int requested, uint64_t* outFlag) {
    *outFlag = 0;
    int want = requested;
    if (want <= 0) {
        want = 4; // default
        if (const char* env = std::getenv("OPENJFX_SKIA_3D_MSAA"); env && *env) {
            want = std::atoi(env);
        }
    }
    if (want <= 1) return 1;
    const bgfx::Caps* caps = bgfx::getCaps();
    // MSAA resolve into the single-sample colorRes goes through bgfx::blit.
    if ((caps->supported & BGFX_CAPS_TEXTURE_BLIT) == 0) return 1;
    const uint16_t fmt = caps->formats[bgfx::TextureFormat::RGBA8];
    if ((fmt & BGFX_CAPS_FORMAT_TEXTURE_MSAA) == 0) return 1;
    if (want >= 8) { *outFlag = BGFX_TEXTURE_RT_MSAA_X8; return 8; }
    if (want >= 4) { *outFlag = BGFX_TEXTURE_RT_MSAA_X4; return 4; }
    *outFlag = BGFX_TEXTURE_RT_MSAA_X2; return 2;
}

void freeTargetNow(Target3D* t) {
    if (!t) return;
    // Only destroy bgfx handles that belong to the LIVE device generation — a stale
    // target's handles died with the old bgfx instance (recovery dropped them already),
    // so destroying them on the new instance is UB. AND only when the device is not
    // lost: after a D3D12 device-removed, bgfx holds a dead instance, so calling
    // bgfx::destroy on this deferred-free path (reached from notify_present, which is
    // NOT otherwise device-lost-guarded) would touch a dead device — the same hazard
    // every other GPU entry point latches against. Releasing the raw D3D12 colorRes
    // COM ref is always safe (a plain refcount decrement, device-state-independent),
    // so we still do it (and the delete) to avoid leaking on device loss.
    if (t->deviceGen == gDeviceGen.load(std::memory_order_relaxed)
            && !skia_fx::d3d12_device_lost()) {
        if (bgfx::isValid(t->fb)) bgfx::destroy(t->fb);
        if (bgfx::isValid(t->colorTex)) bgfx::destroy(t->colorTex);
        if (bgfx::isValid(t->msaaColor)) bgfx::destroy(t->msaaColor);
        if (bgfx::isValid(t->msaaDepth)) bgfx::destroy(t->msaaDepth);
    }
    if (t->colorRes) skia_fx::d3d12_release(t->colorRes);
    delete t;
}

// Free queued targets the GPU is provably done with. Called each frame. Compacts
// survivors in place (swap-to-front + shrink) so it does NOT allocate on the render
// thread — gPendingFree is non-empty every frame during a resize drag, so a scratch
// vector here would be a per-frame heap allocation (forbidden by CLAUDE.md).
void drainPendingFrees(bool force) {
    if (gPendingFree.empty()) return;
    const uint64_t present = gPresentNum3D.load(std::memory_order_relaxed);
    size_t w = 0;
    for (size_t r = 0; r < gPendingFree.size(); ++r) {
        const PendingFree3D& pf = gPendingFree[r];
        if (force || (present - pf.frame) >= kFreeLatency) {
            freeTargetNow(pf.t);
        } else {
            if (w != r) gPendingFree[w] = pf;
            ++w;
        }
    }
    gPendingFree.resize(w); // retains capacity — no reallocation
}

#endif // OPENJFX_SKIA3D_HAVE_BGFX

} // namespace

extern "C" OPENJFX3D_API int32_t openjfx_skia3d_available(void) {
#if defined(OPENJFX_SKIA3D_HAVE_BGFX) && defined(OPENJFX_SKIA3D_HAVE_SHADERS)
    // Pure env-var check — must NOT force GPU context init, because
    // is3DSupported() may be queried off the render thread (e.g. from the
    // FX thread via Platform.isSupported(SCENE3D)). The real bgfx init
    // happens lazily on the render thread; if it fails, 3D draws degrade
    // to no-ops rather than crashing.
    //
    // Opt-in: matches d3dOptedIn (3D needs the D3D12 backend). Enable
    // with OPENJFX_SKIA_D3D=1.
    const char* v = std::getenv("OPENJFX_SKIA_D3D");
    return (v && v[0] && v[0] != '0') ? 1 : 0;
#else
    return 0;
#endif
}

extern "C" OPENJFX3D_API int32_t openjfx_skia3d_spike_composite(uintptr_t surfaceHandle,
                                                                int32_t w, int32_t h) {
    if (surfaceHandle == 0 || w <= 0 || h <= 0) {
        return SKIA3D_BAD_ARGS;
    }
#ifdef OPENJFX_SKIA3D_HAVE_BGFX
    if (gBroken) {
        return SKIA3D_INIT_FAILED;
    }
    if (!skia_fx::backend_is_d3d()) {
        return SKIA3D_WRONG_BACKEND;
    }
    if (!ensureBgfxInit() || !ensureRt()) {
        return SKIA3D_INIT_FAILED;
    }

    // 1. bgfx renders into our shared color resource.
    renderInto();

    // 2. Wrap that SAME resource as an SkImage on the shared context —
    //    zero copy — and composite it onto the scene surface.
    const uintptr_t imgHandle = skia_fx::d3d12_wrap_texture_as_image(gRt.res, kRtW, kRtH);
    if (imgHandle == 0) {
        return SKIA3D_INIT_FAILED;
    }
    const float dw = static_cast<float>(w < kRtW ? w : kRtW);
    const float dh = static_cast<float>(h < kRtH ? h : kRtH);
    openjfx_skia_surface_draw_image_rect(
        surfaceHandle, imgHandle,
        0.0f, 0.0f, static_cast<float>(kRtW), static_cast<float>(kRtH),
        0.0f, 0.0f, dw, dh);

    // 3. Drop our SkImage view; Skia's recorded draw holds its own ref
    //    until flush, and the underlying resource is cached for reuse.
    openjfx_skia_image_destroy(imgHandle);
    return SKIA3D_OK;
#else
    (void) surfaceHandle; (void) w; (void) h;
    return SKIA3D_NO_BGFX;
#endif
}

// ===========================================================================
// Door 1 entry points
// ===========================================================================

#ifdef OPENJFX_SKIA3D_HAVE_BGFX

extern "C" OPENJFX3D_API uintptr_t openjfx_skia3d_mesh_create(void) {
    auto* m = new Mesh3D();
    registerMesh(m);
    return reinterpret_cast<uintptr_t>(m);
}

extern "C" OPENJFX3D_API int32_t openjfx_skia3d_mesh_build_int(
        uintptr_t mesh, const float* vb, int32_t vlen, const int32_t* ib, int32_t ilen) {
    auto* m = asMesh(mesh);
    if (!m || !vb || !ib || vlen <= 0 || ilen <= 0) return 0;
    if (skia_fx::d3d12_device_lost()) return 0;   // no GPU work on a removed device
    if (!ensurePhongProgram()) return 0;
    if (!indicesInRange(ib, ilen, vlen)) return 0; // reject OOB app-supplied indices
    releaseMeshBuffers(m);                          // mutable-mesh rebuild: free old GPU buffers
    m->vbh = bgfx::createVertexBuffer(
        bgfx::copy(vb, (uint32_t) vlen * sizeof(float)), gPhong.layout);
    m->ibh = bgfx::createIndexBuffer(
        bgfx::copy(ib, (uint32_t) ilen * sizeof(int32_t)), BGFX_BUFFER_INDEX32);
    m->numIndices = (uint32_t) ilen;
    m->triIndices.assign(ib, ib + ilen); // for lazy wireframe edge buffer
    return (bgfx::isValid(m->vbh) && bgfx::isValid(m->ibh)) ? 1 : 0;
}

extern "C" OPENJFX3D_API int32_t openjfx_skia3d_mesh_build_short(
        uintptr_t mesh, const float* vb, int32_t vlen, const int16_t* ib, int32_t ilen) {
    auto* m = asMesh(mesh);
    if (!m || !vb || !ib || vlen <= 0 || ilen <= 0) return 0;
    if (skia_fx::d3d12_device_lost()) return 0;   // no GPU work on a removed device
    if (!ensurePhongProgram()) return 0;
    const int32_t numVerts = vlen / 9;            // pos3 + uv2 + quat4 = 9 floats/vertex
    for (int32_t i = 0; i < ilen; ++i) {          // reject OOB app-supplied indices
        if ((uint16_t) ib[i] >= (uint32_t) numVerts) return 0;
    }
    releaseMeshBuffers(m);                          // mutable-mesh rebuild: free old GPU buffers
    m->vbh = bgfx::createVertexBuffer(
        bgfx::copy(vb, (uint32_t) vlen * sizeof(float)), gPhong.layout);
    m->ibh = bgfx::createIndexBuffer(
        bgfx::copy(ib, (uint32_t) ilen * sizeof(int16_t)));
    m->numIndices = (uint32_t) ilen;
    m->triIndices.resize((size_t) ilen);
    for (int32_t i = 0; i < ilen; ++i) m->triIndices[i] = (uint32_t) (uint16_t) ib[i];
    return (bgfx::isValid(m->vbh) && bgfx::isValid(m->ibh)) ? 1 : 0;
}

extern "C" OPENJFX3D_API void openjfx_skia3d_mesh_destroy(uintptr_t mesh) {
    auto* m = reinterpret_cast<Mesh3D*>(mesh);
    if (!m || m->magic != Mesh3D::kMagic) return;   // null / wild / already-destroyed
    m->magic = 0;                                   // poison FIRST: double-destroy is a no-op
    unregisterMesh(m);
    // Only touch bgfx if these handles belong to the LIVE device generation; a stale
    // mesh's handles died with the old bgfx instance (destroying them now is UB).
    if (m->deviceGen == gDeviceGen.load(std::memory_order_relaxed)) {
        releaseMeshBuffers(m);
    }
    delete m;
}

extern "C" OPENJFX3D_API uintptr_t openjfx_skia3d_material_create(void) {
    auto* m = new Material3D();
    registerMaterial(m);
    return reinterpret_cast<uintptr_t>(m);
}
extern "C" OPENJFX3D_API void openjfx_skia3d_material_set_diffuse(
        uintptr_t mat, float r, float g, float b, float a) {
    auto* m = asMaterial(mat);  // CPU-only state; a stale/destroyed handle is ignored
    if (m) { m->diffuse[0]=r; m->diffuse[1]=g; m->diffuse[2]=b; m->diffuse[3]=a; }
}
extern "C" OPENJFX3D_API void openjfx_skia3d_material_set_specular(
        uintptr_t mat, int32_t set, float r, float g, float b) {
    auto* m = asMaterial(mat);
    if (m) {
        m->specular[0]=r; m->specular[1]=g; m->specular[2]=b;
        m->specular[3] = set ? 32.0f : 0.0f; // default Phong exponent
    }
}
// Build an RGBA8 texture with a full box-filtered mipmap chain from tightly-packed
// level-0 pixels (w*h*4 bytes). Mipmaps are essential: a large (e.g. 4K) texture
// minified onto a small/far surface without mips both looks like flat noise (each
// pixel grabs one random texel) AND thrashes the GPU texture cache (huge fps loss).
// Trilinear sampling over the chain fixes both. Returns an invalid handle on bad args.
static bgfx::TextureHandle createMipmappedTextureRGBA8(const void* pixels, int32_t w, int32_t h) {
    if (!pixels || w <= 0 || h <= 0) return BGFX_INVALID_HANDLE;
    // Clamp to the device texture limit up front: an app-supplied dimension past
    // the GPU max would be rejected by bgfx anyway AND overflow the 32-bit size
    // accumulation below, under-allocating the mip chain → heap corruption on the
    // box-filter writes. App-supplied texture dimensions are a danger vector.
    const uint32_t maxDim = bgfx::getCaps()->limits.maxTextureSize;
    if ((uint32_t) w > maxDim || (uint32_t) h > maxDim) return BGFX_INVALID_HANDLE;

    uint8_t numMips = 1;
    for (uint32_t d = (uint32_t) (w > h ? w : h); d > 1u; d >>= 1) numMips++;

    // Total size of the chain — accumulate in size_t, then verify it fits the
    // uint32 bgfx::alloc takes (the clamp above keeps it well under 4 GB, but be
    // explicit rather than rely on it).
    size_t total = 0;
    for (uint32_t lw = (uint32_t) w, lh = (uint32_t) h, l = 0; l < numMips; l++) {
        total += (size_t) lw * lh * 4u;
        lw = lw > 1 ? lw >> 1 : 1;
        lh = lh > 1 ? lh >> 1 : 1;
    }
    if (total > 0xFFFFFFFFull) return BGFX_INVALID_HANDLE;
    const bgfx::Memory* mem = bgfx::alloc((uint32_t) total);

    // Level 0 = the supplied pixels.
    std::memcpy(mem->data, pixels, (size_t) w * h * 4u);
    const uint8_t* prev = mem->data;
    uint32_t pw = (uint32_t) w, ph = (uint32_t) h;
    uint8_t* cur = mem->data + (size_t) w * h * 4u;
    for (uint8_t l = 1; l < numMips; l++) {
        uint32_t cw = pw > 1 ? pw >> 1 : 1;
        uint32_t ch = ph > 1 ? ph >> 1 : 1;
        for (uint32_t y = 0; y < ch; y++) {
            uint32_t y0 = y * 2, y1 = (y0 + 1 < ph) ? y0 + 1 : y0;
            for (uint32_t x = 0; x < cw; x++) {
                uint32_t x0 = x * 2, x1 = (x0 + 1 < pw) ? x0 + 1 : x0;
                const uint8_t* p00 = prev + (size_t) (y0 * pw + x0) * 4;
                const uint8_t* p01 = prev + (size_t) (y0 * pw + x1) * 4;
                const uint8_t* p10 = prev + (size_t) (y1 * pw + x0) * 4;
                const uint8_t* p11 = prev + (size_t) (y1 * pw + x1) * 4;
                uint8_t* o = cur + (size_t) (y * cw + x) * 4;
                for (int c = 0; c < 4; c++) {
                    o[c] = (uint8_t) ((p00[c] + p01[c] + p10[c] + p11[c]) >> 2);
                }
            }
        }
        prev = cur;
        cur += (size_t) cw * ch * 4u;
        pw = cw; ph = ch;
    }

    // Default sampler = trilinear + wrap (repeat) — good for surface/material maps.
    return bgfx::createTexture2D(
        (uint16_t) w, (uint16_t) h, /*hasMips*/ true, 1,
        bgfx::TextureFormat::RGBA8, BGFX_TEXTURE_NONE, mem);
}

// Shared-texture registry keyed by JavaFX Image id. Many materials reference the same
// Image (e.g. one tiling texture on several shapes); without sharing each material
// re-converts and re-uploads the same 4K image + mipmap chain (~70ms each on the
// render thread — a visible cold-start hitch) and holds a duplicate GPU copy. We
// build it once and refcount it across materials. Render-thread only, so no lock.
struct SharedTex { bgfx::TextureHandle handle; int32_t refs; uint32_t deviceGen; };
static std::unordered_map<uint64_t, SharedTex> gSharedTex;

// Release the shared texture currently bound to material slot `ordinal`, destroying
// the GPU texture when its last reference goes away. Leaves the slot empty.
static void releaseMaterialSlot(Material3D* m, int32_t ordinal) {
    uint64_t id = m->texImageId[ordinal];
    if (id != 0) {
        auto it = gSharedTex.find(id);
        if (it != gSharedTex.end() && --it->second.refs <= 0) {
            if (bgfx::isValid(it->second.handle)) bgfx::destroy(it->second.handle);
            gSharedTex.erase(it);
        }
        m->texImageId[ordinal] = 0;
    } else if (bgfx::isValid(m->tex[ordinal])) {
        // Legacy/un-shared texture (imageId 0) — destroy directly.
        bgfx::destroy(m->tex[ordinal]);
    }
    m->tex[ordinal] = BGFX_INVALID_HANDLE;
}

// Upload a JavaFX PhongMaterial texture map into the material. `typeOrdinal` is the
// MapType ordinal (0=diffuse,1=specular,2=bump,3=self-illum); `pixels` is tightly
// packed RGBA8 (w*h*4 bytes), already converted on the Java side. `imageId` is a
// stable per-Image id (0 = un-shared) so the texture is registered for reuse by other
// materials via material_bind_texture. A null/zero image clears the slot (the shader
// falls back to the solid color).
extern "C" OPENJFX3D_API void openjfx_skia3d_material_set_texture(
        uintptr_t mat, int32_t typeOrdinal, const void* pixels,
        int32_t w, int32_t h, uint64_t imageId) {
    auto* m = asMaterial(mat);
    if (!m || typeOrdinal < 0 || typeOrdinal >= MAP_COUNT) return;
    if (skia_fx::d3d12_device_lost()) return;       // no GPU upload on a removed device
    // Drop whatever this slot held (release shared ref / destroy un-shared).
    releaseMaterialSlot(m, typeOrdinal);
    if (!pixels || w <= 0 || h <= 0) return; // clear-only

    bgfx::TextureHandle h2 = createMipmappedTextureRGBA8(pixels, w, h);
    if (!bgfx::isValid(h2)) return;
    if (imageId != 0) {
        auto it = gSharedTex.find(imageId);
        // A registry entry minted under a previous device generation holds a handle
        // that died with the old bgfx instance; bgfx::isValid can't detect that. Drop
        // such a stale entry (no destroy — the GPU resource is already gone) and treat
        // it as absent so we register the freshly-built texture below.
        if (it != gSharedTex.end()
                && it->second.deviceGen != gDeviceGen.load(std::memory_order_relaxed)) {
            gSharedTex.erase(it);
            it = gSharedTex.end();
        }
        if (it != gSharedTex.end() && bgfx::isValid(it->second.handle)) {
            // id already registered (a concurrent/repeat upload that skipped the
            // material_bind_texture fast path): reuse the existing shared texture
            // and drop the duplicate we just built — overwriting the map entry
            // would orphan the old GPU texture and corrupt the refcount of every
            // other material still pointing at it (leak + later use-after-free).
            bgfx::destroy(h2);
            it->second.refs++;
            m->tex[typeOrdinal] = it->second.handle;
            m->texImageId[typeOrdinal] = imageId;
            return;
        }
        m->tex[typeOrdinal] = h2;
        m->texImageId[typeOrdinal] = imageId;
        gSharedTex[imageId] = SharedTex{ h2, 1, gDeviceGen.load(std::memory_order_relaxed) };
    } else {
        m->tex[typeOrdinal] = h2;
    }
}

// Bind an already-uploaded shared texture (by `imageId`) into material slot `ordinal`,
// taking a reference. Returns 1 if the id was found and bound, 0 if it is not in the
// registry (the caller must then fall back to material_set_texture with pixels). This
// is the fast path that skips the Java-side RGBA8 conversion and the native upload +
// mipmap build entirely for repeats of the same image.
extern "C" OPENJFX3D_API int32_t openjfx_skia3d_material_bind_texture(
        uintptr_t mat, int32_t typeOrdinal, uint64_t imageId) {
    auto* m = asMaterial(mat);
    if (!m || typeOrdinal < 0 || typeOrdinal >= MAP_COUNT || imageId == 0) return 0;
    releaseMaterialSlot(m, typeOrdinal);
    auto it = gSharedTex.find(imageId);
    // Reject (and drop) an entry from a dead device generation — its handle is a
    // dangling bgfx handle that isValid() can't catch. Caller then falls back to a
    // real upload via material_set_texture, re-registering under the live device.
    if (it != gSharedTex.end()
            && it->second.deviceGen != gDeviceGen.load(std::memory_order_relaxed)) {
        gSharedTex.erase(it);
        it = gSharedTex.end();
    }
    if (it == gSharedTex.end() || !bgfx::isValid(it->second.handle)) return 0;
    it->second.refs++;
    m->tex[typeOrdinal] = it->second.handle;
    m->texImageId[typeOrdinal] = imageId;
    return 1;
}
extern "C" OPENJFX3D_API void openjfx_skia3d_material_destroy(uintptr_t mat) {
    auto* m = reinterpret_cast<Material3D*>(mat);
    if (!m || m->magic != Material3D::kMagic) return; // null / wild / already-destroyed
    m->magic = 0;                                     // poison FIRST: double-destroy is a no-op
    unregisterMaterial(m);
    // Release each slot through the shared registry so a texture shared with other live
    // materials is kept and only the last reference frees the GPU resource — but ONLY if
    // these handles belong to the live device generation (a stale material's textures
    // died with the old bgfx instance; destroying them now is UB).
    if (m->deviceGen == gDeviceGen.load(std::memory_order_relaxed)) {
        for (int i = 0; i < MAP_COUNT; ++i) {
            releaseMaterialSlot(m, i);
        }
    }
    delete m;
}

extern "C" OPENJFX3D_API uintptr_t openjfx_skia3d_meshview_create(uintptr_t mesh) {
    auto* v = new MeshView3D();
    v->mesh = asMesh(mesh);  // store a validated Mesh3D* (null if the handle is bad)
    registerMeshView(v);
    return reinterpret_cast<uintptr_t>(v);
}
extern "C" OPENJFX3D_API void openjfx_skia3d_meshview_set_material(uintptr_t mv, uintptr_t mat) {
    (void) mv; (void) mat; // material handle is passed to draw() directly
}
extern "C" OPENJFX3D_API void openjfx_skia3d_meshview_set_culling(uintptr_t mv, int32_t mode) {
    auto* v = asMeshView(mv); if (v) v->cull = mode;
}
extern "C" OPENJFX3D_API void openjfx_skia3d_meshview_set_wireframe(uintptr_t mv, int32_t wf) {
    auto* v = asMeshView(mv); if (v) v->wireframe = (wf != 0);
}
extern "C" OPENJFX3D_API void openjfx_skia3d_meshview_set_ambient(uintptr_t mv, float r, float g, float b) {
    auto* v = asMeshView(mv);
    if (v) { v->ambient[0]=r; v->ambient[1]=g; v->ambient[2]=b; }
}
extern "C" OPENJFX3D_API void openjfx_skia3d_meshview_set_light(uintptr_t mv, int32_t index,
        float x, float y, float z, float r, float g, float b, float w,
        float ca, float la, float qa, float isAtt, float maxRange,
        float dirX, float dirY, float dirZ, float inner, float outer, float falloff) {
    auto* v = asMeshView(mv);
    if (!v || index < 0 || index >= MAX_LIGHTS) return;
    // Unified light model (mirrors stock JavaFX): w = light on/off; isAtt = positional
    // (1) vs directional (0); ca/la/qa + range = attenuation; spotlight cone derived
    // from inner/outer angles (degrees). Point lights arrive as a 180° cone (cosOuter
    // = -1, falloff = 0) so the spot factor collapses to 1.
    const float deg2rad = 3.14159265358979323846f / 180.0f;
    float cosOuter = std::cos(outer * deg2rad);
    float cosInner = std::cos(inner * deg2rad);
    v->lightPos[index][0]=x; v->lightPos[index][1]=y; v->lightPos[index][2]=z; v->lightPos[index][3]=0;
    v->lightColor[index][0]=r; v->lightColor[index][1]=g; v->lightColor[index][2]=b; v->lightColor[index][3]=w;
    v->lightDir[index][0]=dirX; v->lightDir[index][1]=dirY; v->lightDir[index][2]=dirZ; v->lightDir[index][3]=0;
    v->lightAttn[index][0]=ca; v->lightAttn[index][1]=la; v->lightAttn[index][2]=qa; v->lightAttn[index][3]=isAtt;
    v->lightSpot[index][0]=cosOuter; v->lightSpot[index][1]=cosInner-cosOuter; v->lightSpot[index][2]=falloff;
    v->lightSpot[index][3]=maxRange;
}
extern "C" OPENJFX3D_API void openjfx_skia3d_meshview_destroy(uintptr_t mv) {
    auto* v = reinterpret_cast<MeshView3D*>(mv);
    if (!v || v->magic != MeshView3D::kMagic) return; // null / wild / already-destroyed
    v->magic = 0;                                     // poison FIRST: double-destroy is a no-op
    unregisterMeshView(v);
    delete v;                                          // no GPU resources to release
}

// `samples` is the desired MSAA sample count for this target: <=0 uses the global
// default (OPENJFX_SKIA_3D_MSAA), 1 disables AA, 2/4/8 request that count. The
// per-SubScene toggle (dev.skiafx.scene3d.Skia3D) passes an explicit value.
extern "C" OPENJFX3D_API uintptr_t openjfx_skia3d_target_create(int32_t w, int32_t h,
                                                                int32_t samples) {
    if (w <= 0 || h <= 0) return 0;
    if (skia_fx::d3d12_device_lost()) return 0; // shared device gone — no 3D GPU work
    if (!ensureBgfxInit()) return 0;
    if (std::getenv("OPENJFX_SKIA_3D_DIAG")) {
        std::fprintf(stderr, "[skia3d] target_create %dx%d samples=%d (frame %llu)\n",
                     w, h, samples, (unsigned long long) gFrameNum3D);
    }
    auto* t = new Target3D();
    t->w = w; t->h = h;
    t->colorRes = skia_fx::d3d12_create_rt_texture(w, h);
    if (!t->colorRes) { delete t; return 0; }

    uint64_t msaaFlag = 0;
    t->samples = resolveMsaaSamples(samples, &msaaFlag);

    // Single-sample color bound to colorRes (what Skia wraps). With MSAA it is the
    // resolve DESTINATION (needs BLIT_DST); without MSAA it is the render target.
    const uint64_t colorFlags = (t->samples > 1)
        ? (BGFX_TEXTURE_RT | BGFX_TEXTURE_BLIT_DST)
        : BGFX_TEXTURE_RT;
    t->colorTex = bgfx::createTexture2D((uint16_t) w, (uint16_t) h, false, 1,
        bgfx::TextureFormat::RGBA8, colorFlags);
    bgfx::frame(); // materialize before override
    uintptr_t ovr = bgfx::overrideInternal(t->colorTex, reinterpret_cast<uintptr_t>(t->colorRes));
    if (ovr == 0) {
        // The bgfx texture wasn't materialized yet on this driver/timing window.
        // colorTex would then keep pointing at bgfx's OWN internal resource while
        // target_wrap_image wraps colorRes (never rendered into) → black composite
        // with no failure signalled. Give it one more frame and retry once.
        // Don't hard-fail: ensureRt tolerates a transient 0 and recovers, so a
        // forced failure here could break 3D on drivers that return 0 benignly.
        // Surface a persistent failure only under diagnostics.
        bgfx::frame();
        ovr = bgfx::overrideInternal(t->colorTex, reinterpret_cast<uintptr_t>(t->colorRes));
        if (ovr == 0 && verbose()) {
            std::fprintf(stderr,
                "[skia3d] target_create overrideInternal returned 0 "
                "(colorRes not bound; 3D may render black).\n");
        }
    }

    if (t->samples > 1) {
        // Render into a multisampled color+depth framebuffer; resolve into
        // colorTex (=colorRes) each frame in target_end via bgfx::blit.
        t->msaaColor = bgfx::createTexture2D((uint16_t) w, (uint16_t) h, false, 1,
            bgfx::TextureFormat::RGBA8, BGFX_TEXTURE_RT | msaaFlag);
        t->msaaDepth = bgfx::createTexture2D((uint16_t) w, (uint16_t) h, false, 1,
            bgfx::TextureFormat::D32F, BGFX_TEXTURE_RT_WRITE_ONLY | msaaFlag);
        bgfx::TextureHandle atts[2] = { t->msaaColor, t->msaaDepth };
        t->fb = bgfx::createFrameBuffer(2, atts, false);
        if (!bgfx::isValid(t->fb)) {
            // MSAA framebuffer unsupported at this size/format: degrade to
            // single-sample (colorTex itself as the render target).
            if (bgfx::isValid(t->msaaColor)) bgfx::destroy(t->msaaColor);
            if (bgfx::isValid(t->msaaDepth)) bgfx::destroy(t->msaaDepth);
            t->msaaColor = BGFX_INVALID_HANDLE;
            t->msaaDepth = BGFX_INVALID_HANDLE;
            t->samples = 1;
        }
    }
    if (t->samples <= 1) {
        bgfx::TextureHandle depth = bgfx::createTexture2D((uint16_t) w, (uint16_t) h,
            false, 1, bgfx::TextureFormat::D32F, BGFX_TEXTURE_RT_WRITE_ONLY);
        t->msaaDepth = depth; // reuse the slot as the (single-sample) depth
        bgfx::TextureHandle atts[2] = { t->colorTex, depth };
        t->fb = bgfx::createFrameBuffer(2, atts, false);
    }
    // A half-built target (no valid framebuffer) would make target_begin bind an
    // invalid FB and render to the backbuffer/garbage with no error signalled to
    // Java. Free it and report failure so the SubScene degrades to no 3D, not a
    // corrupt composite.
    if (!bgfx::isValid(t->fb)) {
        freeTargetNow(t);
        return 0;
    }
    if (std::getenv("OPENJFX_SKIA_3D_DIAG")) {
        std::fprintf(stderr, "[skia3d] target MSAA samples=%d\n", t->samples);
    }
    registerTarget(t); // live now (the failure paths above freed t before this point)
    return reinterpret_cast<uintptr_t>(t);
}
extern "C" OPENJFX3D_API void openjfx_skia3d_target_destroy(uintptr_t target) {
    auto* t = reinterpret_cast<Target3D*>(target);
    if (!t || t->magic != Target3D::kMagic) return; // null / wild / already-destroyed
    t->magic = 0;                                   // poison FIRST: double-destroy is a no-op
    unregisterTarget(t);                            // no longer "live" — recovery drains the queue
    // Don't free now — the previous frame's bgfx draws + Skia composite may
    // still be reading colorRes on the GPU (use-after-free crashes strict
    // AMD drivers). Queue it; drainPendingFrees() frees it a few presented
    // frames later. No GPU stall → resize stays smooth (no overlap flicker).
    gPendingFree.push_back({ t, gPresentNum3D.load(std::memory_order_relaxed) });
}
extern "C" OPENJFX3D_API int32_t openjfx_skia3d_target_begin(uintptr_t target,
        float r, float g, float b, float a) {
    auto* t = asTarget(target);                  // null / wild / destroyed / stale-gen → skip
    if (!t) return -1;
    if (skia_fx::d3d12_device_lost()) return -1;
    // Shadow depth pass setup (view kViewShadow, renders before the main view this
    // frame). Best-effort: if it can't be set up the main pass renders unshadowed.
    if (shadowsEnabled() && ensureShadowResources()) {
        shadowBeginPass();
    }
    bgfx::setViewFrameBuffer(kView3D, t->fb);
    bgfx::setViewRect(kView3D, 0, 0, (uint16_t) t->w, (uint16_t) t->h);
    bgfx::setViewClear(kView3D, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH,
                       packRGBA(r, g, b, a), 1.0f, 0);
    bgfx::touch(kView3D);
    return 0;
}
extern "C" OPENJFX3D_API int32_t openjfx_skia3d_target_end(uintptr_t target) {
    auto* t = asTarget(target);                  // null / wild / destroyed / stale-gen → skip
    if (!t) return -1;                           // (don't submit a stray frame() for a dead target)
    if (skia_fx::d3d12_device_lost()) return -1; // don't submit to a removed device
    // First, execute this pulse's 3D draws (submitted to kView3D) into the
    // framebuffer — msaaColor for MSAA, or colorTex(=colorRes) directly otherwise.
    bgfx::frame();
    if (t->samples > 1 && bgfx::isValid(t->msaaColor)) {
        // Resolve the multisampled color into the single-sample colorTex(=colorRes)
        // Skia wraps (bgfx does a D3D12 ResolveSubresource, sample counts differ).
        // bgfx processes a blit at the START of the NEXT frame, so we must issue a
        // second frame() for it to run THIS pulse — otherwise the resolve lags a
        // frame and a freshly-created target (every frame during resize) reads an
        // empty msaaColor, blanking the 3D until the size settles.
        bgfx::blit(kView3D, t->colorTex, 0, 0, t->msaaColor);
        // bgfx runs a blit at the START of the next frame, so issue a second frame()
        // now to execute the resolve THIS pulse. (Deferring it 1 frame to save the
        // frame() makes stable content render 1-frame-late, which flickers — so we
        // always resolve in-pulse.)
        bgfx::frame();
    }
    // Advance the SubScene-pass clock (diagnostics) and opportunistically drain
    // (the actual free decision is gated on the PRESENT clock — see
    // openjfx_skia3d_notify_present). No stall — just bookkeeping.
    ++gFrameNum3D;
    drainPendingFrees(false);
    return 0;
}
// Called once per real swap-chain present (from SkiaPresentable.present()). This
// is the clock the deferred target-free latency is measured against, so a target
// queued by target_destroy is only freed once the GPU has presented kFreeLatency
// frames past the one that last used it — preventing the multi-SubScene UAF where
// target_end's per-pass counter elapsed within a single un-presented pulse.
extern "C" OPENJFX3D_API void openjfx_skia3d_notify_present(void) {
    gPresentNum3D.fetch_add(1, std::memory_order_relaxed);
    drainPendingFrees(false);
}
extern "C" OPENJFX3D_API uintptr_t openjfx_skia3d_target_wrap_image(uintptr_t target) {
    auto* t = asTarget(target);                  // null / wild / destroyed / stale-gen → skip
    if (!t || !t->colorRes) return 0;
    if (skia_fx::d3d12_device_lost()) return 0;
    if (std::getenv("OPENJFX_SKIA_3D_DIAG") && (gFrameNum3D % 30 == 0) && gDumpCount < 20) {
        char path[256];
        std::snprintf(path, sizeof(path), "F:/DEV/skia-fx/build/skia3d_dump_f%06llu.png",
                      (unsigned long long) gFrameNum3D);
        skia_fx::debug_dump_rt(t->colorRes, t->w, t->h, path);
        ++gDumpCount;
    }
    return skia_fx::d3d12_wrap_texture_as_image(t->colorRes, t->w, t->h);
}
extern "C" OPENJFX3D_API int32_t openjfx_skia3d_draw(uintptr_t target, uintptr_t meshview,
        uintptr_t material, const float* projView16, const float* model16,
        float camX, float camY, float camZ) {
    // Validate EVERY handle before any GPU use: a stale/destroyed/wrong-type target,
    // mesh-view, mesh or material cleanly skips the draw rather than dereferencing
    // freed or dead-device memory. Material is nullable (null/stale → solid-colour
    // defaults below, exactly as a material-less shape already renders).
    Target3D*   t = asTarget(target);
    MeshView3D* v = asMeshView(meshview);
    if (!t || !v || !projView16 || !model16) return -1;
    if (skia_fx::d3d12_device_lost()) return -1;
    if (!ensurePhongProgram()) return -1;
    Mesh3D* mesh = asMesh(reinterpret_cast<uintptr_t>(v->mesh)); // revalidate the stored mesh
    if (!mesh || !bgfx::isValid(mesh->vbh) || !bgfx::isValid(mesh->ibh)) return -1;
    Material3D* m = asMaterial(material);

    // JavaFX builds GL-convention projections (NDC depth in [-1, 1]). bgfx on
    // D3D/Metal clips to [0, 1], which silently culls the near half of the depth
    // range. That is invisible for a PerspectiveCamera (perspective compresses
    // nearly all depth toward the far plane, so every visible fragment already
    // lands in [0, 1]) but it badly clips an orthographic ParallelCamera, whose
    // z is linear — half the scene falls in [-1, 0] and disappears. When the
    // active renderer is non-homogeneous, remap z to [0, 1]: z' = (z + w) / 2,
    // i.e. row2' = 0.5*row2 + 0.5*row3. projView16 is column-major, so row r of
    // column c lives at [c*4 + r]. This is a no-op for already-[0,1] depth.
    float proj[16];
    std::memcpy(proj, projView16, sizeof(proj));
    if (!bgfx::getCaps()->homogeneousDepth) {
        for (int col = 0; col < 4; ++col) {
            proj[col * 4 + 2] = 0.5f * proj[col * 4 + 2] + 0.5f * proj[col * 4 + 3];
        }
    }
    bgfx::setViewTransform(kView3D, nullptr, proj);
    bgfx::setTransform(model16);
    bgfx::setVertexBuffer(0, mesh->vbh);
    bgfx::setIndexBuffer(mesh->ibh);

    const float ambient[4] = { v->ambient[0], v->ambient[1], v->ambient[2], 1.0f };
    bgfx::setUniform(gPhong.uAmbient, ambient);

    float diffuse[4]  = { 1, 1, 1, 1 };
    float specular[4] = { 0, 0, 0, 0 };
    if (m) {
        std::memcpy(diffuse, m->diffuse, sizeof(diffuse));
        std::memcpy(specular, m->specular, sizeof(specular));
    }
    bgfx::setUniform(gPhong.uDiffuse, diffuse);
    bgfx::setUniform(gPhong.uSpecular, specular);

    const float camPos[4] = { camX, camY, camZ, 1.0f };
    bgfx::setUniform(gPhong.uCamPos, camPos);
    bgfx::setUniform(gPhong.uLightPos, v->lightPos, MAX_LIGHTS);
    bgfx::setUniform(gPhong.uLightColor, v->lightColor, MAX_LIGHTS);
    bgfx::setUniform(gPhong.uLightDir, v->lightDir, MAX_LIGHTS);
    bgfx::setUniform(gPhong.uLightAttn, v->lightAttn, MAX_LIGHTS);
    bgfx::setUniform(gPhong.uLightSpot, v->lightSpot, MAX_LIGHTS);

    // Bind whatever texture maps the material has, and tell the shader which are
    // present (u_mapFlags). Absent maps leave the sampler unbound and the flag 0, so
    // the shader uses the solid diffuse/specular colors exactly as before.
    float mapFlags[4] = { 0, 0, 0, 0 };
    if (m) {
        const bgfx::UniformHandle samplers[MAP_COUNT] = {
            gPhong.sDiffuse, gPhong.sSpecular, gPhong.sBump, gPhong.sSelfIllum
        };
        for (int i = 0; i < MAP_COUNT; ++i) {
            if (bgfx::isValid(m->tex[i])) {
                bgfx::setTexture((uint8_t) i, samplers[i], m->tex[i]);
                mapFlags[i] = 1.0f;
            }
        }
    }
    bgfx::setUniform(gPhong.uMapFlags, mapFlags);

    // Shadow-map lookup params for the phong pass (x=enabled, y=bias, z=strength).
    // The light matrix + map match the depth written by the kViewShadow pass below.
    const bool shadowOn = shadowsEnabled() && gShadow.ready;
    float shadowParams[4] = { 0.0f, 0.0f, 0.0f, 0.0f };
    if (shadowOn) {
        bgfx::setUniform(gPhong.uLightViewProj, gShadow.lightViewProj);
        bgfx::setTexture(4, gPhong.sShadow, gShadow.map);
        shadowParams[0] = 1.0f; shadowParams[1] = 0.0025f; shadowParams[2] = 0.6f;
    }
    bgfx::setUniform(gPhong.uShadowParams, shadowParams);

    const uint64_t baseState = BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A
                             | BGFX_STATE_WRITE_Z | BGFX_STATE_DEPTH_TEST_LESS;
    if (v->wireframe) {
        // DrawMode.LINE: draw the triangle edges as a line list (no culling so every
        // edge shows). Lazily build the edge index buffer the first time.
        ensureEdgeIndexBuffer(mesh);
        if (bgfx::isValid(mesh->edgeIbh)) {
            bgfx::setIndexBuffer(mesh->edgeIbh); // override the triangle index buffer
            bgfx::setState(baseState | BGFX_STATE_PT_LINES);
        } else {
            bgfx::setState(baseState); // fallback: solid if the edge build failed
        }
    } else {
        // Back-face culling. v->cull is the JavaFX CullFace ordinal, already adjusted
        // for mirror transforms by NGShape3D: 0=NONE, 1=BACK, 2=FRONT. JavaFX defines
        // BACK as clockwise winding, FRONT as counter-clockwise, so cull BACK = cull CW.
        uint64_t cullState = 0;
        if (v->cull == 1)      cullState = BGFX_STATE_CULL_CW;   // CULL_BACK
        else if (v->cull == 2) cullState = BGFX_STATE_CULL_CCW;  // CULL_FRONT
        bgfx::setState(baseState | cullState);
    }
    bgfx::submit(kView3D, gPhong.prog);

    // Shadow depth pass: re-submit this geometry from the light's POV (depth only) so
    // it occludes in the shadow map. bgfx consumed the transform/buffers in the phong
    // submit above, so set them again. Wireframe shapes don't cast solid shadows.
    if (shadowOn && !v->wireframe) {
        bgfx::setTransform(model16);
        bgfx::setVertexBuffer(0, mesh->vbh);
        bgfx::setIndexBuffer(mesh->ibh);
        bgfx::setState(BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_Z | BGFX_STATE_DEPTH_TEST_LESS);
        bgfx::submit(kViewShadow, gShadow.prog);
    }
    return 0;
}

#else // !OPENJFX_SKIA3D_HAVE_BGFX — stubs so the ABI always resolves

extern "C" OPENJFX3D_API uintptr_t openjfx_skia3d_mesh_create(void) { return 0; }
extern "C" OPENJFX3D_API int32_t openjfx_skia3d_mesh_build_int(uintptr_t, const float*, int32_t, const int32_t*, int32_t) { return 0; }
extern "C" OPENJFX3D_API int32_t openjfx_skia3d_mesh_build_short(uintptr_t, const float*, int32_t, const int16_t*, int32_t) { return 0; }
extern "C" OPENJFX3D_API void openjfx_skia3d_mesh_destroy(uintptr_t) {}
extern "C" OPENJFX3D_API uintptr_t openjfx_skia3d_material_create(void) { return 0; }
extern "C" OPENJFX3D_API void openjfx_skia3d_material_set_diffuse(uintptr_t, float, float, float, float) {}
extern "C" OPENJFX3D_API void openjfx_skia3d_material_set_specular(uintptr_t, int32_t, float, float, float) {}
extern "C" OPENJFX3D_API void openjfx_skia3d_material_set_texture(uintptr_t, int32_t, const void*, int32_t, int32_t, uint64_t) {}
extern "C" OPENJFX3D_API int32_t openjfx_skia3d_material_bind_texture(uintptr_t, int32_t, uint64_t) { return 0; }
extern "C" OPENJFX3D_API void openjfx_skia3d_material_destroy(uintptr_t) {}
extern "C" OPENJFX3D_API uintptr_t openjfx_skia3d_meshview_create(uintptr_t) { return 0; }
extern "C" OPENJFX3D_API void openjfx_skia3d_meshview_set_material(uintptr_t, uintptr_t) {}
extern "C" OPENJFX3D_API void openjfx_skia3d_meshview_set_culling(uintptr_t, int32_t) {}
extern "C" OPENJFX3D_API void openjfx_skia3d_meshview_set_wireframe(uintptr_t, int32_t) {}
extern "C" OPENJFX3D_API void openjfx_skia3d_meshview_set_ambient(uintptr_t, float, float, float) {}
extern "C" OPENJFX3D_API void openjfx_skia3d_meshview_set_light(uintptr_t, int32_t, float, float, float, float, float, float, float, float, float, float, float, float, float, float, float, float, float, float) {}
extern "C" OPENJFX3D_API void openjfx_skia3d_meshview_destroy(uintptr_t) {}
extern "C" OPENJFX3D_API uintptr_t openjfx_skia3d_target_create(int32_t, int32_t, int32_t) { return 0; }
extern "C" OPENJFX3D_API void openjfx_skia3d_target_destroy(uintptr_t) {}
extern "C" OPENJFX3D_API int32_t openjfx_skia3d_target_begin(uintptr_t, float, float, float, float) { return -1; }
extern "C" OPENJFX3D_API int32_t openjfx_skia3d_target_end(uintptr_t) { return -1; }
extern "C" OPENJFX3D_API void openjfx_skia3d_notify_present(void) {}
extern "C" OPENJFX3D_API uintptr_t openjfx_skia3d_target_wrap_image(uintptr_t) { return 0; }
extern "C" OPENJFX3D_API int32_t openjfx_skia3d_draw(uintptr_t, uintptr_t, uintptr_t, const float*, const float*, float, float, float) { return -1; }

#endif // OPENJFX_SKIA3D_HAVE_BGFX
