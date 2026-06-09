/*
 * openjfx_skia_bridge.cpp — bridge stub (no Skia C++ yet).
 *
 * When OPENJFX_WITH_SKIA is defined, the file pulls in Skia headers
 * and uses real SkSurface / SkCanvas. Otherwise it provides a
 * functionally-equivalent raster fallback so the FFM bridge can be
 * wired and tested without a Skia toolchain on the build machine.
 *
 * The fallback is NOT a long-term path; it exists so phase-0 work on
 * the JNI/FFM glue isn't blocked on a Skia binary distribution.
 */

#include "openjfx_skia_bridge.h"
#include "openjfx_skia_d3d11_interop.h"

#if defined(OPENJFX_WITH_SKIA) && defined(_WIN32)
  #include "openjfx_skia_d3d_win.h"
#endif

#include <cstring>
#include <vector>

#ifdef OPENJFX_WITH_SKIA
  #include "include/core/SkSurface.h"
  #include "include/core/SkBlendMode.h"
  #include "include/core/SkCanvas.h"
  #include "include/core/SkColor.h"
  #include "include/core/SkImageInfo.h"
  #include "include/core/SkMatrix.h"
  #include "include/core/SkPaint.h"
  #include "include/core/SkImage.h"
  #include "include/core/SkColorSpace.h"
  #include "include/core/SkYUVAPixmaps.h"
  #include "include/core/SkYUVAInfo.h"
  #include "include/gpu/ganesh/SkImageGanesh.h"
  #include "include/core/SkPath.h"
  #include "include/core/SkPathBuilder.h"
  #include "include/core/SkPathTypes.h"
  #include "include/core/SkSamplingOptions.h"
  #include "include/core/SkShader.h"
  #include "include/core/SkColorFilter.h"
  #include "include/core/SkMaskFilter.h"
  #include "include/core/SkBlurTypes.h"
  #include "include/core/SkImageFilter.h"
  #include "include/core/SkTileMode.h"
  #include "include/effects/SkGradient.h"
  #include "include/effects/SkImageFilters.h"
  #include "include/effects/SkColorMatrix.h"
  #include "include/effects/SkRuntimeEffect.h"
  #include "include/core/SkRect.h"
  #include "include/core/SkPicture.h"
  #include "include/core/SkPictureRecorder.h"
  #include "include/core/SkPixmap.h"
  #include "include/core/SkRefCnt.h"
  #include "include/core/SkData.h"
  #include "include/encode/SkPngEncoder.h"
  #include "include/encode/SkJpegEncoder.h"
  #include "include/encode/SkWebpEncoder.h"
  #include "include/core/SkFont.h"
  #include "include/core/SkFontMgr.h"
  #include "include/core/SkTextBlob.h"
  #include "include/core/SkTypeface.h"
  #include "include/ports/SkFontMgr_empty.h"
  // Skia SVG module (modules/svg). Parses an SVG document into an
  // SkSVGDOM and renders it through any SkCanvas — vector-exact at
  // whatever device size we set, which is what keeps SvgImageView
  // crystal-clear at every zoom level / DPI. Backed by the static
  // svg/skshaper/skunicode/skresources archives the build links in.
  // Gated on OPENJFX_WITH_SKIA_SVG (defined by CMake when those archives
  // were found) so a Skia build without the SVG module still compiles —
  // the svg_* entry points then degrade to no-ops.
  #ifdef OPENJFX_WITH_SKIA_SVG
    #include "include/core/SkStream.h"
    #include "modules/svg/include/SkSVGDOM.h"
    #include "modules/svg/include/SkSVGSVG.h"
    // Text in SVG: a shaping factory + a real font manager so <text> renders
    // with actual glyphs.
    #include "modules/skshaper/include/SkShaper_factory.h"
  #endif
  // Ganesh GPU backend (OpenGL).
  #include "include/gpu/GpuTypes.h"
  #include "include/gpu/ganesh/GrBackendSurface.h"
  #include "include/gpu/ganesh/GrDirectContext.h"
  #include "include/gpu/ganesh/GrTypes.h"
  #include "include/gpu/ganesh/SkSurfaceGanesh.h"
  #include "include/gpu/ganesh/gl/GrGLDirectContext.h"
  #include "include/gpu/ganesh/gl/GrGLInterface.h"
  #include "include/gpu/ganesh/gl/GrGLBackendSurface.h"
  #include "include/gpu/ganesh/gl/GrGLTypes.h"
#endif

#if defined(OPENJFX_WITH_SKIA) && defined(_WIN32)
  // For the off-screen WGL context that owns the Ganesh GL surface.
  // Lean + NOMINMAX to keep Windows.h from spraying macros into Skia.
  #define WIN32_LEAN_AND_MEAN
  #ifndef NOMINMAX
    #define NOMINMAX
  #endif
  #include <Windows.h>
  // Raw GL symbols for the resize-time window-clear path (glClear /
  // glClearColor / glViewport / GL_COLOR_BUFFER_BIT). wglCreateContext
  // already comes from <wingdi.h> via Windows.h.
  #include <GL/gl.h>
  // System (DirectWrite) font manager — real fonts for glyph/typeface
  // creation and for SVG <text>. Forward-declares its DWrite types, so it
  // needs no extra SDK include here.
  #include "include/ports/SkTypeface_win.h"
#endif

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <mutex>

#ifdef OPENJFX_WITH_SKIA
namespace {

/**
 * Surface ownership block. Held heap-allocated; uintptr_t handles
 * across the FFM boundary point at instances of this struct. The
 * sticky paint state (blend mode, extra alpha) is updated by the
 * surface_set_* entries and applied to every draw on this surface.
 */
struct OpenJfxSurface {
    // Identifies a live OpenJfxSurface. Zeroed by surface_destroy so a
    // stale handle — a SkiaGraphics that outlived its RTTexture — is
    // rejected by asState() instead of being dereferenced.
    static constexpr uint32_t kMagic = 0x534B5346u; // 'SKSF'
    uint32_t         magic = kMagic;
    sk_sp<SkSurface> surface;     // null for D3D-swap-chain surfaces;
                                  // the back-buffer surfaces live inside `d3d`.
    SkBlendMode      blendMode  = SkBlendMode::kSrcOver;
    float            extraAlpha = 1.0f;
    // skia-fx: tracks surface_save vs surface_restore from
    // WCGraphicsPrismContext.SkiaClipLayer (NOT begin_draw/end_draw —
    // those are always-balanced per-draw brackets). An imbalance would
    // underflow Skia's canvas save stack and segfault deep in canvas
    // state; the restore path refuses to pop below zero and logs the
    // first occurrence.
    int32_t          bridgeSaveCount = 0;
    // Set only when this struct is wrapping an SkPictureRecorder's
    // recording canvas (picture_recorder_begin → ...recording draws...
    // → picture_recorder_finish). The canvas is owned by the recorder;
    // the struct must not outlive picture_recorder_finish.
    SkCanvas*        recordingCanvas = nullptr;
#ifdef _WIN32
    // Set only for window-bound (direct-present) GPU surfaces. The HDC
    // is GetDC'd at create_window_gpu and ReleaseDC'd at destroy.
    HWND             windowHwnd      = nullptr;
    HDC              windowHdc       = nullptr;
    bool             isWindowSurface = false;
    // Offscreen-FBO present mode (Task #37 / preserving-buffer support).
    // When offscreenFbo != 0, Skia draws into this offscreen color
    // attachment and surface_present_window blits it onto FBO 0 before
    // SwapBuffers. This gives us BUFFER PRESERVATION across frames —
    // the offscreen content is fully owned by us, not subject to
    // SwapBuffers' undefined post-swap semantics. Required for
    // dirty-region painting + scene caching to work correctly.
    unsigned int     offscreenFbo    = 0;   // GL FBO ID (0 = direct FBO 0 path)
    unsigned int     offscreenTex    = 0;   // GL color-attachment texture
    unsigned int     offscreenRb     = 0;   // GL depth+stencil renderbuffer
    int              offscreenW      = 0;
    int              offscreenH      = 0;
    // Set only for D3D direct-present surfaces. The swap chain owns
    // multiple back-buffer SkSurfaces; asSurface() routes to whichever
    // one DXGI says is current.
    OpenJfxD3DSwapChain* d3d        = nullptr;
#endif
};

inline OpenJfxSurface* asState(uintptr_t handle) {
    auto* st = reinterpret_cast<OpenJfxSurface*>(handle);
    // A freed-but-still-mapped block fails the magic check; a genuinely
    // wild pointer is rare (handles only come from surface_create_*).
    if (!st || st->magic != OpenJfxSurface::kMagic) return nullptr;
    return st;
}
inline SkSurface* asSurface(uintptr_t handle) {
    auto* st = asState(handle);
    if (!st) return nullptr;
#ifdef _WIN32
    if (st->d3d) {
        // DXGI rotates back buffers; route every draw to whichever
        // buffer is currently the render target.
        return openjfxD3DCurrentSurface(st->d3d);
    }
#endif
    return st->surface.get();
}

/**
 * Returns the SkCanvas that draw ops should target for {@code handle}.
 *
 * <p>Routes to the right canvas regardless of surface kind:</p>
 * <ul>
 *   <li>Picture recorder (Task #31): returns the recorder's canvas
 *       directly — no SkSurface is involved.</li>
 *   <li>D3D swap chain: returns the canvas of the current DXGI back
 *       buffer (rotates per Present).</li>
 *   <li>Regular SkSurface (GL window, off-screen GPU, raster):
 *       returns surface->getCanvas().</li>
 * </ul>
 *
 * <p>Every draw op should use this helper instead of
 * {@code asSurface(handle)->getCanvas()}; the latter NULL-derefs on
 * recording targets.</p>
 */
inline SkCanvas* asCanvas(uintptr_t handle) {
    auto* st = asState(handle);
    if (!st) return nullptr;
    if (st->recordingCanvas) return st->recordingCanvas;
#ifdef _WIN32
    if (st->d3d) {
        SkSurface* s = openjfxD3DCurrentSurface(st->d3d);
        return s ? s->getCanvas() : nullptr;
    }
#endif
    return st->surface ? st->surface->getCanvas() : nullptr;
}

// Image / filter / shader handles. Each handle is a heap-allocated
// struct fronted by a distinct magic sentinel — mirroring OpenJfxSurface
// — so a stale-but-still-mapped handle is rejected by the accessor
// instead of being blindly reinterpret_cast and dereferenced (which was
// a SIGSEGV vector). The magic is zeroed at free time. All creation must
// go through makeImageHandle / makeFilterHandle / makeShaderHandle, and
// all release must go through freeImageHandle / freeFilterHandle /
// freeShaderHandle.
struct ImageHandle {
    static constexpr uint32_t kMagic = 0x534B494Du; // 'SKIM'
    uint32_t       magic = kMagic;
    sk_sp<SkImage> sp;
};
struct FilterHandle {
    static constexpr uint32_t kMagic = 0x534B4946u; // 'SKIF'
    uint32_t             magic = kMagic;
    sk_sp<SkImageFilter> sp;
};
struct ShaderHandle {
    static constexpr uint32_t kMagic = 0x534B5348u; // 'SKSH'
    uint32_t        magic = kMagic;
    sk_sp<SkShader> sp;
};

inline uintptr_t makeImageHandle(sk_sp<SkImage> img) {
    if (!img) return 0;
    return reinterpret_cast<uintptr_t>(new ImageHandle{ImageHandle::kMagic, std::move(img)});
}
inline uintptr_t makeFilterHandle(sk_sp<SkImageFilter> f) {
    if (!f) return 0;
    return reinterpret_cast<uintptr_t>(new FilterHandle{FilterHandle::kMagic, std::move(f)});
}
inline uintptr_t makeShaderHandle(sk_sp<SkShader> s) {
    if (!s) return 0;
    return reinterpret_cast<uintptr_t>(new ShaderHandle{ShaderHandle::kMagic, std::move(s)});
}

inline sk_sp<SkImage>* asImageSp(uintptr_t handle) {
    auto* h = reinterpret_cast<ImageHandle*>(handle);
    if (!h || h->magic != ImageHandle::kMagic) return nullptr;
    return &h->sp;
}
inline SkImage* asImage(uintptr_t handle) {
    auto* sp = asImageSp(handle);
    return sp ? sp->get() : nullptr;
}
inline void freeImageHandle(uintptr_t handle) {
    auto* h = reinterpret_cast<ImageHandle*>(handle);
    if (!h || h->magic != ImageHandle::kMagic) return;
    h->magic = 0; // poison so a double-free / stale use is rejected
    delete h;
}

// ---------------------------------------------------------------------------
// SVG document handle. Holds the parsed SkSVGDOM plus the intrinsic size we
// resolved at parse time (viewBox / width-height attrs). The DOM is parsed
// once and rendered many times at different device sizes — that re-rasterize
// on size change is exactly what keeps SvgImageView vector-sharp at any zoom.
// Same magic-guarded, poison-on-free discipline as the image/filter handles.
// Only present when the SVG module is linked (OPENJFX_WITH_SKIA_SVG).
// ---------------------------------------------------------------------------
#ifdef OPENJFX_WITH_SKIA_SVG
struct SvgHandle {
    static constexpr uint32_t kMagic = 0x534B5347u; // 'SKSG'
    uint32_t          magic = kMagic;
    sk_sp<SkSVGDOM>   dom;
    float             intrinsicW = 0.0f;
    float             intrinsicH = 0.0f;
};

inline uintptr_t makeSvgHandle(sk_sp<SkSVGDOM> dom, float w, float h) {
    if (!dom) return 0;
    return reinterpret_cast<uintptr_t>(
        new SvgHandle{SvgHandle::kMagic, std::move(dom), w, h});
}
inline SvgHandle* asSvg(uintptr_t handle) {
    auto* h = reinterpret_cast<SvgHandle*>(handle);
    if (!h || h->magic != SvgHandle::kMagic) return nullptr;
    return h;
}
inline void freeSvgHandle(uintptr_t handle) {
    auto* h = reinterpret_cast<SvgHandle*>(handle);
    if (!h || h->magic != SvgHandle::kMagic) return;
    h->magic = 0; // poison so a double-free / stale use is rejected
    delete h;
}
#endif // OPENJFX_WITH_SKIA_SVG

inline sk_sp<SkImageFilter>* asFilterSp(uintptr_t handle) {
    auto* h = reinterpret_cast<FilterHandle*>(handle);
    if (!h || h->magic != FilterHandle::kMagic) return nullptr;
    return &h->sp;
}
inline SkImageFilter* asFilter(uintptr_t handle) {
    auto* sp = asFilterSp(handle);
    return sp ? sp->get() : nullptr;
}
inline void freeFilterHandle(uintptr_t handle) {
    auto* h = reinterpret_cast<FilterHandle*>(handle);
    if (!h || h->magic != FilterHandle::kMagic) return;
    h->magic = 0;
    delete h;
}

inline sk_sp<SkShader>* asShaderSp(uintptr_t handle) {
    auto* h = reinterpret_cast<ShaderHandle*>(handle);
    if (!h || h->magic != ShaderHandle::kMagic) return nullptr;
    return &h->sp;
}
inline SkShader* asShader(uintptr_t handle) {
    auto* sp = asShaderSp(handle);
    return sp ? sp->get() : nullptr;
}
inline void freeShaderHandle(uintptr_t handle) {
    auto* h = reinterpret_cast<ShaderHandle*>(handle);
    if (!h || h->magic != ShaderHandle::kMagic) return;
    h->magic = 0;
    delete h;
}

inline sk_sp<SkTypeface>* asTypefaceSp(uintptr_t handle) {
    return reinterpret_cast<sk_sp<SkTypeface>*>(handle);
}

// FreeType-backed font manager with no system fonts — used solely to
// turn caller-supplied font-file bytes into SkTypeface instances.
// Built once on first use; SkFontMgr::makeFromData is const/thread-safe.
inline const sk_sp<SkFontMgr>& customFontMgr() {
    // Real system fonts on Windows (DirectWrite) so glyph/typeface creation and
    // SVG <text> use actual fonts; the empty custom manager elsewhere. Built
    // once; SkFontMgr is immutable and thread-safe to share.
#ifdef _WIN32
    static sk_sp<SkFontMgr> mgr = SkFontMgr_New_DirectWrite();
#else
    static sk_sp<SkFontMgr> mgr = SkFontMgr_New_Custom_Empty();
#endif
    return mgr;
}

#ifdef OPENJFX_WITH_SKIA_SVG
// Font manager used when rendering SVG <text>. Real system fonts (DirectWrite
// on Windows) so text shows actual glyphs; falls back to the empty custom mgr
// elsewhere. Built once and shared (SkFontMgr is immutable/thread-safe).
inline sk_sp<SkFontMgr> svgFontMgr() {
    return customFontMgr(); // real system fonts (DirectWrite on Windows)
}
// Text shaping factory for SVG <text>. Primitive shaping is dependency-light
// (no ICU/HarfBuzz wiring needed at call time) and covers the common case.
inline sk_sp<SkShapers::Factory> svgShaperFactory() {
    static sk_sp<SkShapers::Factory> f = SkShapers::Primitive::Factory();
    return f;
}
#endif // OPENJFX_WITH_SKIA_SVG

inline SkTileMode mapTileMode(int32_t v) {
    switch (v) {
        case 1:  return SkTileMode::kRepeat;
        case 2:  return SkTileMode::kMirror;
        case 3:  return SkTileMode::kDecal;
        default: return SkTileMode::kClamp;
    }
}

inline void applyState(SkPaint& paint, const OpenJfxSurface& st) {
    paint.setBlendMode(st.blendMode);
    if (st.extraAlpha < 1.0f) {
        paint.setAlphaf(paint.getAlphaf() * st.extraAlpha);
    }
}

// Decode RGBA-packed colors[] (low byte = R) into SkColor4f for
// gradient construction. Skia's SkColor4f is float per channel.
inline std::vector<SkColor4f> decodeColors(const uint32_t* colors, int n) {
    std::vector<SkColor4f> out(n);
    for (int i = 0; i < n; ++i) {
        uint32_t c = colors[i];
        float r = ((c       ) & 0xFF) / 255.0f;
        float g = ((c >>  8) & 0xFF) / 255.0f;
        float b = ((c >> 16) & 0xFF) / 255.0f;
        float a = ((c >> 24) & 0xFF) / 255.0f;
        out[i] = SkColor4f{r, g, b, a};
    }
    return out;
}

inline void configureFillShader(SkPaint& paint, const OpenJfxSurface& st,
                                SkShader* shader, uint8_t alpha) {
    paint.setStyle(SkPaint::kFill_Style);
    paint.setShader(sk_ref_sp(shader));
    paint.setAlpha(alpha);
    paint.setAntiAlias(true);
    applyState(paint, st);
}

inline void configureStrokeShader(SkPaint& paint, const OpenJfxSurface& st,
                                  SkShader* shader, uint8_t alpha,
                                  float width, int32_t cap, int32_t join, float miter) {
    paint.setStyle(SkPaint::kStroke_Style);
    paint.setShader(sk_ref_sp(shader));
    paint.setAlpha(alpha);
    paint.setAntiAlias(true);
    paint.setStrokeWidth(width);
    paint.setStrokeMiter(miter);
    paint.setStrokeCap(
        cap == 1 ? SkPaint::kRound_Cap
      : cap == 2 ? SkPaint::kSquare_Cap
      :            SkPaint::kButt_Cap);
    paint.setStrokeJoin(
        join == 1 ? SkPaint::kRound_Join
      : join == 2 ? SkPaint::kBevel_Join
      :             SkPaint::kMiter_Join);
    applyState(paint, st);
}

inline SkColorType mapColorType(int32_t v) {
    switch (v) {
        case 0:  return kRGBA_8888_SkColorType;
        case 1:  return kBGRA_8888_SkColorType;
        case 2:  return kGray_8_SkColorType;
        case 3:  return kAlpha_8_SkColorType;     // BYTE_ALPHA — coverage / mask textures
        case 4:  return kRGB_888x_SkColorType;    // BYTE_RGB packed in 4 bytes (X-pad)
        default: return kRGBA_8888_SkColorType;
    }
}

inline void configureFill(SkPaint& paint, const OpenJfxSurface& st,
                          uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    paint.setStyle(SkPaint::kFill_Style);
    paint.setColor(SkColorSetARGB(a, r, g, b));
    paint.setAntiAlias(true);
    applyState(paint, st);
}

// Largest coordinate magnitude the software rasterizer handles safely.
// Real surfaces are a few thousand pixels; anything past this is
// certainly garbage — uninitialized memory or a NaN-derived value.
inline constexpr float kCoordLimit = 1 << 23; // 8,388,608

inline bool coordFinite(float v) {
    return std::isfinite(v) && v >= -kCoordLimit && v <= kCoordLimit;
}

inline bool pathDebugEnabled() {
    static const bool enabled = [] {
        const char* v = std::getenv("OPENJFX_SKIA_DEBUG");
        return v && v[0] && v[0] != '0';
    }();
    return enabled;
}

// Env-gated, throttled report when a path is rejected for bad geometry.
// Set OPENJFX_SKIA_DEBUG=1 to trace the bad-coordinate source.
inline void reportBadPath(const char* where, int index, float value) {
    static int reported = 0;
    if (pathDebugEnabled() && reported < 16) {
        ++reported;
        std::fprintf(stderr,
            "[openjfx-skia] %s: rejected path, coord[%d]=%g is non-finite "
            "or out of range\n", where, index, value);
    }
}

inline bool buildPath(SkPath& path,
                      const uint8_t* verbs, int32_t verbCount,
                      const float*   coords, int32_t coordCount,
                      int32_t fillRule) {
    if (!verbs || !coords || verbCount < 0 || coordCount < 0) return false;
    // Reject pathological geometry before it reaches Skia. The software
    // scan-converter overflows its fixed-point coverage math on a
    // non-finite or astronomically large coordinate and corrupts the
    // heap; skipping the draw is the safe outcome.
    for (int i = 0; i < coordCount; ++i) {
        if (!coordFinite(coords[i])) {
            reportBadPath("buildPath", i, coords[i]);
            return false;
        }
    }
    SkPathBuilder builder;
    builder.setFillType(fillRule == 1 ? SkPathFillType::kWinding
                                      : SkPathFillType::kEvenOdd);
    int ci = 0;
    for (int i = 0; i < verbCount; ++i) {
        switch (verbs[i]) {
            case 0:                                                // MOVE
                if (ci + 2 > coordCount) return false;
                builder.moveTo(coords[ci], coords[ci + 1]);
                ci += 2;
                break;
            case 1:                                                // LINE
                if (ci + 2 > coordCount) return false;
                builder.lineTo(coords[ci], coords[ci + 1]);
                ci += 2;
                break;
            case 2:                                                // QUAD
                if (ci + 4 > coordCount) return false;
                builder.quadTo(coords[ci],     coords[ci + 1],
                               coords[ci + 2], coords[ci + 3]);
                ci += 4;
                break;
            case 3:                                                // CUBIC
                if (ci + 6 > coordCount) return false;
                builder.cubicTo(coords[ci],     coords[ci + 1],
                                coords[ci + 2], coords[ci + 3],
                                coords[ci + 4], coords[ci + 5]);
                ci += 6;
                break;
            case 4:                                                // CLOSE
                builder.close();
                break;
            default:
                return false;
        }
    }
    if (ci != coordCount) return false;
    path = builder.detach();
    return true;
}

inline void configureStroke(SkPaint& paint, const OpenJfxSurface& st,
                            uint8_t r, uint8_t g, uint8_t b, uint8_t a,
                            float width, int32_t cap, int32_t join, float miter) {
    paint.setStyle(SkPaint::kStroke_Style);
    paint.setColor(SkColorSetARGB(a, r, g, b));
    paint.setAntiAlias(true);
    paint.setStrokeWidth(width);
    paint.setStrokeMiter(miter);
    paint.setStrokeCap(
        cap == 1 ? SkPaint::kRound_Cap
      : cap == 2 ? SkPaint::kSquare_Cap
      :            SkPaint::kButt_Cap);
    paint.setStrokeJoin(
        join == 1 ? SkPaint::kRound_Join
      : join == 2 ? SkPaint::kBevel_Join
      :             SkPaint::kMiter_Join);
    applyState(paint, st);
}

// ---- Ganesh GL backend -----------------------------------------------------
//
// Phase-2 increment 1: rendering moves to the GPU. The present path
// stays on the existing readPixels + Glass upload (increment 2 makes
// the present direct-swapchain). One GrDirectContext per process; it
// binds to whichever thread calls first — Quantum's render thread in
// normal use.

#ifdef _WIN32
// Shared per-process WGL state used by both the off-screen GPU path
// (increment 1) and the direct-present window-bound path (increment 2).
// Populated by ensureWindowsGlContext; intentionally leaked on exit.
static HGLRC                 gGlHglrc       = nullptr;
static int                   gGlPixelFormat = 0;
static PIXELFORMATDESCRIPTOR gGlPfd         = {};

// Offscreen GL objects (FBO + color texture + depth/stencil RB) orphaned at
// surface_destroy when no GL context could be made current (the window's HDC
// was already torn down). They belong to the process-wide gGlHglrc, so rather
// than leaking we stash their names here and delete them the next time we DO
// make that context current (drainDeferredGlDeletes). Guarded by a mutex in
// case a destroy races a render-thread op.
struct OrphanedFbo { GLuint fbo; GLuint tex; GLuint rb; };
static std::vector<OrphanedFbo> gDeferredGlDeletes;
static std::mutex               gDeferredGlMutex;

// Sets up the WGL context against a hidden auxiliary HWND so Skia's
// Ganesh GL backend has a current OpenGL context on this thread. The
// chosen pixel format is captured in `gGlPixelFormat`/`gGlPfd` so that
// per-window direct-present surfaces can match it via SetPixelFormat.
// One-shot per process; leaks the HWND/HDC/HGLRC on exit by design.
inline bool ensureWindowsGlContext() {
    static bool initialized = false;
    static bool succeeded   = false;
    if (initialized) return succeeded;
    initialized = true;

    HINSTANCE hinst = GetModuleHandle(nullptr);
    static const TCHAR* const kClassName = TEXT("OpenJfxSkiaGL");
    WNDCLASSEX wc = {};
    wc.cbSize        = sizeof(wc);
    wc.lpfnWndProc   = DefWindowProc;
    wc.hInstance     = hinst;
    wc.lpszClassName = kClassName;
    RegisterClassEx(&wc); // ignore failure: re-registration is harmless

    HWND hwnd = CreateWindowEx(
        0, kClassName, TEXT(""),
        WS_POPUP | WS_DISABLED,
        0, 0, 1, 1,
        nullptr, nullptr, hinst, nullptr);
    if (!hwnd) return false;
    HDC hdc = GetDC(hwnd);
    if (!hdc) return false;

    gGlPfd = {};
    gGlPfd.nSize        = sizeof(gGlPfd);
    gGlPfd.nVersion     = 1;
    gGlPfd.dwFlags      = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
    gGlPfd.iPixelType   = PFD_TYPE_RGBA;
    gGlPfd.cColorBits   = 32;
    gGlPfd.cAlphaBits   = 8;
    gGlPfd.cStencilBits = 8;
    gGlPfd.iLayerType   = PFD_MAIN_PLANE;
    gGlPixelFormat = ChoosePixelFormat(hdc, &gGlPfd);
    if (!gGlPixelFormat || !SetPixelFormat(hdc, gGlPixelFormat, &gGlPfd)) return false;

    gGlHglrc = wglCreateContext(hdc);
    if (!gGlHglrc) return false;
    if (!wglMakeCurrent(hdc, gGlHglrc)) return false;

    // NOTE: We do NOT automatically initialize the D3D11 ⇄ GL interop
    // here. `wglDXOpenDeviceNV` puts the GL driver into an
    // interop-aware mode that serializes GL submissions more carefully
    // (perceptible FPS drop in pure-GL workloads like our dashboard).
    // The interop is opt-in via openjfx_skia_d3d11_interop_init(), which
    // SkiaMediaTexture's Phase-3 GPU-import path calls only when a
    // MediaView actually wants zero-copy video.

    succeeded = true;
    return true;
}

// =========================================================================
// GL function pointers for FBO + blit operations. Loaded lazily on first
// use via wglGetProcAddress. GL 1.1 (which <GL/gl.h> declares) doesn't
// cover these — we need GL 3.0 entry points.
// =========================================================================

typedef void (APIENTRY* PFN_glGenFramebuffers)(GLsizei, GLuint*);
typedef void (APIENTRY* PFN_glDeleteFramebuffers)(GLsizei, const GLuint*);
typedef void (APIENTRY* PFN_glBindFramebuffer)(GLenum, GLuint);
typedef void (APIENTRY* PFN_glFramebufferTexture2D)(GLenum, GLenum, GLenum, GLuint, GLint);
typedef void (APIENTRY* PFN_glFramebufferRenderbuffer)(GLenum, GLenum, GLenum, GLuint);
typedef GLenum (APIENTRY* PFN_glCheckFramebufferStatus)(GLenum);
typedef void (APIENTRY* PFN_glGenRenderbuffers)(GLsizei, GLuint*);
typedef void (APIENTRY* PFN_glDeleteRenderbuffers)(GLsizei, const GLuint*);
typedef void (APIENTRY* PFN_glBindRenderbuffer)(GLenum, GLuint);
typedef void (APIENTRY* PFN_glRenderbufferStorage)(GLenum, GLenum, GLsizei, GLsizei);
typedef void (APIENTRY* PFN_glBlitFramebuffer)(GLint, GLint, GLint, GLint,
                                                GLint, GLint, GLint, GLint,
                                                GLbitfield, GLenum);

#define GL_FRAMEBUFFER                          0x8D40
#define GL_READ_FRAMEBUFFER                     0x8CA8
#define GL_DRAW_FRAMEBUFFER                     0x8CA9
#define GL_COLOR_ATTACHMENT0                    0x8CE0
#define GL_DEPTH_STENCIL_ATTACHMENT             0x821A
#define GL_RENDERBUFFER                         0x8D41
#define GL_DEPTH24_STENCIL8                     0x88F0
#define GL_FRAMEBUFFER_COMPLETE                 0x8CD5
#define GL_RGBA8                                0x8058

struct GlFboFuncs {
    PFN_glGenFramebuffers       genFramebuffers       = nullptr;
    PFN_glDeleteFramebuffers    deleteFramebuffers    = nullptr;
    PFN_glBindFramebuffer       bindFramebuffer       = nullptr;
    PFN_glFramebufferTexture2D  framebufferTexture2D  = nullptr;
    PFN_glFramebufferRenderbuffer framebufferRenderbuffer = nullptr;
    PFN_glCheckFramebufferStatus checkFramebufferStatus = nullptr;
    PFN_glGenRenderbuffers      genRenderbuffers      = nullptr;
    PFN_glDeleteRenderbuffers   deleteRenderbuffers   = nullptr;
    PFN_glBindRenderbuffer      bindRenderbuffer      = nullptr;
    PFN_glRenderbufferStorage   renderbufferStorage   = nullptr;
    PFN_glBlitFramebuffer       blitFramebuffer       = nullptr;
    bool                        loaded                = false;
};

static GlFboFuncs gFboFuncs;

static bool loadFboFuncs() {
    if (gFboFuncs.loaded) return true;
    auto load = [](const char* name) -> void* {
        return reinterpret_cast<void*>(wglGetProcAddress(name));
    };
    gFboFuncs.genFramebuffers         = (PFN_glGenFramebuffers)         load("glGenFramebuffers");
    gFboFuncs.deleteFramebuffers      = (PFN_glDeleteFramebuffers)      load("glDeleteFramebuffers");
    gFboFuncs.bindFramebuffer         = (PFN_glBindFramebuffer)         load("glBindFramebuffer");
    gFboFuncs.framebufferTexture2D    = (PFN_glFramebufferTexture2D)    load("glFramebufferTexture2D");
    gFboFuncs.framebufferRenderbuffer = (PFN_glFramebufferRenderbuffer) load("glFramebufferRenderbuffer");
    gFboFuncs.checkFramebufferStatus  = (PFN_glCheckFramebufferStatus)  load("glCheckFramebufferStatus");
    gFboFuncs.genRenderbuffers        = (PFN_glGenRenderbuffers)        load("glGenRenderbuffers");
    gFboFuncs.deleteRenderbuffers     = (PFN_glDeleteRenderbuffers)     load("glDeleteRenderbuffers");
    gFboFuncs.bindRenderbuffer        = (PFN_glBindRenderbuffer)        load("glBindRenderbuffer");
    gFboFuncs.renderbufferStorage     = (PFN_glRenderbufferStorage)     load("glRenderbufferStorage");
    gFboFuncs.blitFramebuffer         = (PFN_glBlitFramebuffer)         load("glBlitFramebuffer");
    gFboFuncs.loaded =
        gFboFuncs.genFramebuffers && gFboFuncs.deleteFramebuffers &&
        gFboFuncs.bindFramebuffer && gFboFuncs.framebufferTexture2D &&
        gFboFuncs.framebufferRenderbuffer && gFboFuncs.checkFramebufferStatus &&
        gFboFuncs.genRenderbuffers && gFboFuncs.deleteRenderbuffers &&
        gFboFuncs.bindRenderbuffer && gFboFuncs.renderbufferStorage &&
        gFboFuncs.blitFramebuffer;
    return gFboFuncs.loaded;
}

/** Releases the offscreen FBO + texture + RB owned by {@code st}.
 *  Caller is responsible for making the GL context current. */
static void releaseOffscreenFbo(OpenJfxSurface* st) {
    if (!st) return;
    if (st->offscreenFbo) { gFboFuncs.deleteFramebuffers(1, &st->offscreenFbo); st->offscreenFbo = 0; }
    if (st->offscreenTex) { glDeleteTextures(1, &st->offscreenTex); st->offscreenTex = 0; }
    if (st->offscreenRb)  { gFboFuncs.deleteRenderbuffers(1, &st->offscreenRb); st->offscreenRb = 0; }
    st->offscreenW = st->offscreenH = 0;
}

/** Deletes any offscreen GL objects deferred by surface_destroy when it could
 *  not make a context current. The CALLER must have gGlHglrc current. Cheap
 *  no-op in the common case (empty list). */
static void drainDeferredGlDeletes() {
    std::vector<OrphanedFbo> pending;
    {
        std::lock_guard<std::mutex> lock(gDeferredGlMutex);
        if (gDeferredGlDeletes.empty()) return;
        pending.swap(gDeferredGlDeletes);
    }
    if (!loadFboFuncs()) return;
    for (OrphanedFbo& o : pending) {
        if (o.fbo) gFboFuncs.deleteFramebuffers(1, &o.fbo);
        if (o.tex) glDeleteTextures(1, &o.tex);
        if (o.rb)  gFboFuncs.deleteRenderbuffers(1, &o.rb);
    }
}

/** Allocates a fresh offscreen color texture + depth/stencil RB and
 *  binds them into a new FBO. Caller must have made the GL context
 *  current. Returns true on success. */
static bool allocateOffscreenFbo(OpenJfxSurface* st, int w, int h) {
    if (!loadFboFuncs()) return false;
    releaseOffscreenFbo(st);

    glGenTextures(1, &st->offscreenTex);
    glBindTexture(GL_TEXTURE_2D, st->offscreenTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA,
                 GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glBindTexture(GL_TEXTURE_2D, 0);

    gFboFuncs.genRenderbuffers(1, &st->offscreenRb);
    gFboFuncs.bindRenderbuffer(GL_RENDERBUFFER, st->offscreenRb);
    gFboFuncs.renderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, w, h);
    gFboFuncs.bindRenderbuffer(GL_RENDERBUFFER, 0);

    gFboFuncs.genFramebuffers(1, &st->offscreenFbo);
    gFboFuncs.bindFramebuffer(GL_FRAMEBUFFER, st->offscreenFbo);
    gFboFuncs.framebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                                   GL_TEXTURE_2D, st->offscreenTex, 0);
    gFboFuncs.framebufferRenderbuffer(GL_FRAMEBUFFER,
        GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, st->offscreenRb);
    GLenum status = gFboFuncs.checkFramebufferStatus(GL_FRAMEBUFFER);
    gFboFuncs.bindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        releaseOffscreenFbo(st);
        return false;
    }
    st->offscreenW = w;
    st->offscreenH = h;
    return true;
}

// --- present diagnostics (gated by OPENJFX_SKIA_PRESENT_DIAG) ---------------
// Logs, per window create/present: which monitor the window is on (by rect),
// the window client size, whether the buffer-preservation offscreen FBO is in
// use (offscreenFbo != 0) or we fell back to direct FBO 0, the offscreen size,
// and the wglMakeCurrent / SwapBuffers results. Lets us see why a static
// window on a secondary monitor blanks/flickers while the continuously
// presenting main window is fine. Zero cost when the env var is unset.
static inline bool presentDiagOn() {
    static int v = -1;
    if (v < 0) {
        const char* e = std::getenv("OPENJFX_SKIA_PRESENT_DIAG");
        v = (e && e[0] && e[0] != '0') ? 1 : 0;
    }
    return v == 1;
}
static void logWindowDiag(const char* tag, HWND hwnd, OpenJfxSurface* st,
                          long mkCur, long swap, long lastErr) {
    HMONITOR mon = hwnd ? ::MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST)
                        : nullptr;
    MONITORINFOEX mi;
    ::ZeroMemory(&mi, sizeof(mi));
    mi.cbSize = sizeof(mi);
    long ml = 0, mt = 0, mr = 0, mb = 0;
    if (mon && ::GetMonitorInfo(mon, &mi)) {
        ml = mi.rcMonitor.left;  mt = mi.rcMonitor.top;
        mr = mi.rcMonitor.right; mb = mi.rcMonitor.bottom;
    }
    RECT cr{};
    if (hwnd) ::GetClientRect(hwnd, &cr);
    HWND owner   = hwnd ? ::GetWindow(hwnd, GW_OWNER) : nullptr;
    long exStyle = hwnd ? (long)::GetWindowLongPtr(hwnd, GWL_EXSTYLE) : 0;
    long style   = hwnd ? (long)::GetWindowLongPtr(hwnd, GWL_STYLE)   : 0;
    fprintf(stderr,
        "[skia.present.diag] %-7s hwnd=%p owner=%p mon=%p monRect=(%ld,%ld,%ld,%ld) "
        "client=%ldx%ld offscreenFbo=%u offscreen=%dx%d style=0x%lx exStyle=0x%lx "
        "mkCur=%ld swap=%ld lastErr=%ld\n",
        tag, (void*)hwnd, (void*)owner, (void*)mon, ml, mt, mr, mb,
        (long)(cr.right - cr.left), (long)(cr.bottom - cr.top),
        st ? st->offscreenFbo : 0u, st ? st->offscreenW : 0,
        st ? st->offscreenH : 0, style, exStyle, mkCur, swap, lastErr);
    fflush(stderr);
}

// Makes the shared GL context current against a target HWND's HDC, so
// Skia draws and SwapBuffers land in that window. Returns the HDC
// (caller stores it; later released via ReleaseDC) or nullptr on
// failure. SetPixelFormat is called once per HWND (Windows allows it
// only once; subsequent calls re-bind to the same context).
inline HDC bindWindowGlContext(HWND hwnd) {
    if (!ensureWindowsGlContext()) return nullptr;
    if (!hwnd) return nullptr;
    HDC hdc = GetDC(hwnd);
    if (!hdc) return nullptr;
    int existingPf = GetPixelFormat(hdc);
    if (existingPf == 0) {
        if (!SetPixelFormat(hdc, gGlPixelFormat, &gGlPfd)) {
            ReleaseDC(hwnd, hdc);
            return nullptr;
        }
    } else if (existingPf != gGlPixelFormat) {
        // Window's pixel format already locked to something incompatible.
        ReleaseDC(hwnd, hdc);
        return nullptr;
    }
    if (!wglMakeCurrent(hdc, gGlHglrc)) {
        ReleaseDC(hwnd, hdc);
        return nullptr;
    }
    // First successful bind: configure SwapBuffers vsync behavior.
    // Default is interval=1 (vsync ON), matching stock JavaFX so the
    // GPU paces at display refresh rate — no wasted frames on weak
    // hardware, no fan spin-up, no battery drain. Set
    // OPENJFX_SKIA_UNCAPPED=1 to force interval=0 for benchmarking.
    static bool vsyncConfigured = false;
    if (!vsyncConfigured) {
        vsyncConfigured = true;
        // Default uncapped: many windowed apps see DWM drop frames
        // anyway, but render headroom + lower input latency is the
        // headline. Set OPENJFX_SKIA_VSYNC=1 to lock to display refresh.
        const char* vsyncOn = std::getenv("OPENJFX_SKIA_VSYNC");
        int interval = (vsyncOn && vsyncOn[0] && vsyncOn[0] != '0') ? 1 : 0;
        using PFN_wglSwapIntervalEXT = BOOL (APIENTRY*)(int);
        auto pSwapInterval = reinterpret_cast<PFN_wglSwapIntervalEXT>(
            wglGetProcAddress("wglSwapIntervalEXT"));
        if (pSwapInterval) pSwapInterval(interval);
    }
    return hdc;
}
#endif // _WIN32

// Tracks which backend backs the singleton GrDirectContext. Read once
// after gpuDirectContext() returns; the create_swap_chain_d3d entry
// rejects (returns 0 for fall-through) when the active backend is GL.
enum class GpuBackend { None, GL, D3D };
static GpuBackend gGpuBackend = GpuBackend::None;

// Lazily-built per-process GrDirectContext. On Windows we try
// D3D12/Ganesh first (no DWM windowed-vsync cap when paired with the
// DXGI flip-model + ALLOW_TEARING swap chain); on D3D init failure we
// fall back to the GL backend from the previous increment. Both are
// kept alive intentionally — the sk_sp lives on the heap and is never
// freed, because at process exit the GL context / D3D device are
// torn down by the OS and the destructor would crash inside the
// driver. The OS reclaims everything on exit.
// Backend selection on Windows.
//
//   - default: GL Ganesh + direct-present via SwapBuffers. The
//     functional, validated production path. Known cost: the
//     documented invariant-#3 back-buffer-size lag during drag-
//     resize (Windows defers FBO 0 reallocation to next SwapBuffers
//     → 1-frame DWM stretch artifact during a fast drag).
//   - opt-in: D3D12 (set OPENJFX_SKIA_D3D=1). DXGI flip-model swap
//     chain + ALLOW_TEARING; would give artifact-free drag-resize
//     and bypass DWM's vsync cap. Parked as opt-in pending Task #34
//     (rendering issues observed on at least one host).
//
// Hardware-adaptive principle: the multi-tier probe in
// SkiaPresentable.allocate already covers the spectrum (D3D12 swap
// chain → GL direct-present → GPU FBO + readback → software raster).
// Each tier is opt-in at the C++ level; the Java side picks the
// highest-tier surface that succeeds at allocation. When D3D12's
// known issues are resolved, flipping the default below is the
// single-line change.
inline bool d3dOptedIn() {
    static const bool on = [] {
        // OPT-IN: GL is the default backend. D3D12 (which enables 3D via
        // bgfx — see docs/3D.md) turns on with OPENJFX_SKIA_D3D=1. Kept
        // opt-in until the 3D path is verified stable across GPUs
        // (an AMD multi-GPU driver crash showed up under default-on).
        const char* v = std::getenv("OPENJFX_SKIA_D3D");
        return v && v[0] && v[0] != '0';
    }();
    return on;
}

inline const sk_sp<GrDirectContext>& gpuDirectContext() {
    static const sk_sp<GrDirectContext>* ctx = [] {
        auto* p = new sk_sp<GrDirectContext>();
#ifdef _WIN32
        // D3D12 — only when opted in. The d3d12.h macro pollution
        // stays confined to openjfx_skia_d3d_win.cpp.
        if (d3dOptedIn()) {
            *p = openjfxD3DMakeContext();
            if (*p) {
                gGpuBackend = GpuBackend::D3D;
                return p;
            }
        }
        // Default: GL Ganesh + direct-present via SwapBuffers.
        if (ensureWindowsGlContext()) {
            sk_sp<const GrGLInterface> iface = GrGLMakeNativeInterface();
            if (iface) {
                *p = GrDirectContexts::MakeGL(iface);
                if (*p) gGpuBackend = GpuBackend::GL;
            }
        }
#endif
        return p;
    }();
    return *ctx;
}

} // namespace
#endif

namespace {
constexpr const char* kVersion =
    "openjfx-skia-bridge/0.1 ("
#ifdef OPENJFX_WITH_SKIA
    "skia-enabled"
#else
    "stub-no-skia"
#endif
    ")";
} // namespace

extern "C" {

OPENJFX_API const char* openjfx_skia_version(void) {
    return kVersion;
}

OPENJFX_API int32_t openjfx_skia_has_skia(void) {
#ifdef OPENJFX_WITH_SKIA
    return 1;
#else
    return 0;
#endif
}

// True (1) once the D3D12 device has been removed/lost (cross-DPI monitor move TDR /
// adapter change). Java's SkiaResourceFactory.isDeviceReady() checks this and reports
// not-ready so the painter skips ALL render+present work — no calls hammer the dead
// device (Skia 2D + bgfx 3D both share it). Windows/D3D only; GL/raster never lose
// the device this way, so they return 0. See d3d12_device_lost().
// openjfx_skia_device_lost() is defined near the bottom of this file, after
// skia_fx_bridge.h is included (it calls skia_fx::d3d12_device_lost()).

// ===========================================================================
// SkSurface lifecycle
// ===========================================================================

OPENJFX_API uintptr_t openjfx_skia_surface_create_raster(int32_t width, int32_t height) {
    if (width <= 0 || height <= 0) return 0;
#ifdef OPENJFX_WITH_SKIA
    SkImageInfo info = SkImageInfo::Make(
        width, height, kRGBA_8888_SkColorType, kPremul_SkAlphaType);
    sk_sp<SkSurface> surface = SkSurfaces::Raster(info);
    if (!surface) return 0;
    auto* st = new OpenJfxSurface();
    st->surface = std::move(surface);
    return reinterpret_cast<uintptr_t>(st);
#else
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_surface_create_gpu(int32_t width, int32_t height) {
    if (width <= 0 || height <= 0) return 0;
#ifdef OPENJFX_WITH_SKIA
    const sk_sp<GrDirectContext>& grCtx = gpuDirectContext();
    if (!grCtx) return 0; // GPU unavailable on this platform/build
    SkImageInfo info = SkImageInfo::Make(
        width, height, kRGBA_8888_SkColorType, kPremul_SkAlphaType);
    sk_sp<SkSurface> surface = SkSurfaces::RenderTarget(
        grCtx.get(), skgpu::Budgeted::kNo, info);
    if (!surface) return 0;
    // GPU render targets are NOT zeroed by the driver — the backing VRAM
    // starts as uninitialized garbage. Any region a later draw leaves
    // transparent (an effect or NGRegion-cache RTTexture's rounded-corner
    // margins / shadow halo on its FIRST use, before the pool begins handing
    // back already-rendered drawables) then shows that garbage as a grey
    // "ghost" that only appears on first render. clear() ignores the canvas
    // clip and fills the whole surface, so zero it once at creation.
    surface->getCanvas()->clear(SK_ColorTRANSPARENT);
    auto* st = new OpenJfxSurface();
    st->surface = std::move(surface);
    return reinterpret_cast<uintptr_t>(st);
#else
    (void)width; (void)height;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_surface_create_window_gpu(
    uintptr_t hwnd, int32_t width, int32_t height) {
    if (!hwnd || width <= 0 || height <= 0) return 0;
#if defined(OPENJFX_WITH_SKIA) && defined(_WIN32)
    HWND hWnd = reinterpret_cast<HWND>(hwnd);
    // Layered windows (transparent stages) composite via
    // UpdateLayeredWindow, not SwapBuffers — direct-present can't host
    // them. Glass's upload path handles those correctly; bail so the
    // caller falls back to off-screen GPU or raster.
    if (GetWindowLongPtr(hWnd, GWL_EXSTYLE) & WS_EX_LAYERED) {
        return 0;
    }
    // Owned windows (dialogs, alerts, menus, tooltips) bail to the readback
    // tier. On a multi-monitor / multi-GPU desktop, an owned window shown on
    // a secondary monitor presents BLANK through the GL direct path even
    // though every GL call reports success — captured live as mkCur=1,
    // swap=1 yet nothing composited (the offscreen→window blit + SwapBuffers
    // lands on a drawable the shared GL context can't reach when the window's
    // monitor is driven by the other adapter, or DWM declines to composite an
    // owned window's swap chain there). The un-owned main stage is created on
    // the GL context's adapter and keeps that drawable when dragged across
    // monitors, so it is never affected — which is why the WebView renders on
    // every screen. Returning 0 makes SkiaPresentable fall back to the
    // readback tier: we render on our GPU, read the pixels back, and Glass
    // uploads them through the OS, which is monitor/adapter agnostic and
    // always composites correctly. Owned windows are small and infrequent
    // (dialogs/menus), so the readback cost is negligible. The fast GL path
    // for the main stage and any other top-level stage is untouched.
    if (GetWindow(hWnd, GW_OWNER) != nullptr) {
        return 0;
    }
    HDC hdc = bindWindowGlContext(hWnd);
    if (!hdc) return 0;

    const sk_sp<GrDirectContext>& grCtx = gpuDirectContext();
    if (!grCtx) {
        ReleaseDC(hWnd, hdc);
        return 0;
    }

    constexpr GrGLenum kGlRgba8 = 0x8058;

    // OFFSCREEN-FBO present mode (preserves the back buffer across
    // frames — required for dirty regions, scene cache, skip-clean-
    // frame). Direct FBO 0 leaves post-swap content undefined, which
    // shows up as "previous-frame fight" flicker on resize/redraw.
    // Default ON; set OPENJFX_SKIA_GL_OFFSCREEN=0 to opt out for
    // debug. When the FBO/RB allocation fails we silently fall
    // through to the direct FBO 0 path.
    bool useOffscreen = true;
    {
        const char* env = std::getenv("OPENJFX_SKIA_GL_OFFSCREEN");
        if (env && env[0]) {
            useOffscreen = env[0] != '0';
        }
    }

    auto* st = new OpenJfxSurface();
    st->windowHwnd      = hWnd;
    st->windowHdc       = hdc;
    st->isWindowSurface = true;

    GLuint chosenFbo = 0;
    if (useOffscreen && allocateOffscreenFbo(st, width, height)) {
        chosenFbo = st->offscreenFbo;
    }
    // If allocation failed or wasn't requested, chosenFbo stays 0 →
    // we wrap FBO 0 (the window's default framebuffer) like before.

    GrGLFramebufferInfo fbInfo;
    fbInfo.fFBOID  = chosenFbo;
    fbInfo.fFormat = kGlRgba8;
    GrBackendRenderTarget rt = GrBackendRenderTargets::MakeGL(
        width, height,
        /* sampleCount */ 0,
        /* stencilBits */ 8,
        fbInfo);
    sk_sp<SkSurface> surface = SkSurfaces::WrapBackendRenderTarget(
        grCtx.get(), rt,
        kBottomLeft_GrSurfaceOrigin,
        kRGBA_8888_SkColorType,
        /* colorSpace */ nullptr,
        /* surfaceProps */ nullptr,
        /* releaseProc */ nullptr,
        /* releaseContext */ nullptr);
    if (!surface) {
        releaseOffscreenFbo(st);
        delete st;
        ReleaseDC(hWnd, hdc);
        return 0;
    }
    st->surface = std::move(surface);
    if (presentDiagOn()) logWindowDiag("create", hWnd, st, 1, -1, 0);
    return reinterpret_cast<uintptr_t>(st);
#else
    (void)hwnd; (void)width; (void)height;
    return 0;
#endif
}

#if defined(OPENJFX_WITH_SKIA) && defined(_WIN32)
// Apply the GL swap interval (1 = vsync, 0 = uncapped) via wglSwapIntervalEXT,
// best-effort: when the extension is unavailable the GL tier simply stays at the
// DWM-managed interval. The resolved fn + last value are cached (render-thread
// only) so we don't re-resolve or redundantly set every frame.
static void setGlSwapInterval(int interval) {
    typedef BOOL (WINAPI *PFNWGLSWAPINTERVALEXTPROC_)(int);
    static PFNWGLSWAPINTERVALEXTPROC_ fn =
        (PFNWGLSWAPINTERVALEXTPROC_) wglGetProcAddress("wglSwapIntervalEXT");
    static int last = -999;
    if (fn && interval != last) {
        fn(interval);
        last = interval;
    }
}
#endif

OPENJFX_API int32_t openjfx_skia_surface_present_window(uintptr_t handle, int32_t vsync) {
#if defined(OPENJFX_WITH_SKIA) && defined(_WIN32)
    OpenJfxSurface* st = asState(handle);
    if (!st || !st->isWindowSurface || !st->windowHdc) return 1;
    // Re-bind the shared GL context to this window's HDC. Cheap when
    // already current; critical when multiple windows alternate.
    if (!wglMakeCurrent(st->windowHdc, gGlHglrc)) {
        if (presentDiagOn())
            logWindowDiag("present", st->windowHwnd, st, 0, -1, (long)GetLastError());
        return 2;
    }
    const sk_sp<GrDirectContext>& grCtx = gpuDirectContext();
    if (grCtx) {
        // Every GL window shares ONE process-wide GrDirectContext, and
        // Skia caches GL state (bound FBO, viewport, scissor, …) inside
        // it. The draws for THIS window were only *recorded* during
        // paint; they render here, at flush time. Between record and
        // this flush, another window on the same shared context — e.g.
        // a continuously-rendering WebView main window painting every
        // pulse — can have rebound the framebuffer/viewport. If we flush
        // while Skia still believes the other window's binding is live,
        // it submits THIS window's draws against the WRONG render target
        // and nothing lands in our offscreen FBO → the window presents
        // blank. (Visible only when a sibling window renders constantly:
        // an idle sibling leaves the cache matching, which is why a
        // dialog over a static scene paints fine but the same dialog
        // over a live WebView comes up blank from the 2nd one on.)
        //
        // Tell Skia its cached GL state is stale BEFORE the flush so it
        // re-binds our render target. resetContext() with no args resets
        // all tracked state; it is always safe (worst case it re-sets
        // state that was already correct) and cheap.
        grCtx->resetContext();
        grCtx->flushAndSubmit();
    }
    // Offscreen-FBO present path: blit the offscreen color attachment
    // onto FBO 0 before SwapBuffers. This is the buffer-preservation
    // path — the offscreen content survives across frames so that
    // dirty-region painting / scene caching has a valid starting
    // point each pulse.
    if (st->offscreenFbo) {
        gFboFuncs.bindFramebuffer(GL_READ_FRAMEBUFFER, st->offscreenFbo);
        gFboFuncs.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        gFboFuncs.blitFramebuffer(
            0, 0, st->offscreenW, st->offscreenH,
            0, 0, st->offscreenW, st->offscreenH,
            GL_COLOR_BUFFER_BIT, GL_NEAREST);
        gFboFuncs.bindFramebuffer(GL_FRAMEBUFFER, 0);
        // Skia's GrDirectContext tracks bound FBO state — let it know
        // every backend bit may have changed under it so the next
        // draw re-binds correctly. (resetContext with no args = reset
        // all tracked GL state.)
        if (grCtx) grCtx->resetContext();
    }
    // Runtime vsync toggle on the GL tier (best-effort; see setGlSwapInterval).
    setGlSwapInterval(vsync ? 1 : 0);
    BOOL swapOk = SwapBuffers(st->windowHdc);
    if (presentDiagOn())
        logWindowDiag("present", st->windowHwnd, st, 1, swapOk ? 1 : 0,
                      swapOk ? 0 : (long)GetLastError());
    if (!swapOk) return 3;
    return 0;
#else
    (void)handle;
    (void)vsync;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_resize_gl(
    uintptr_t handle, int32_t width, int32_t height) {
#if defined(OPENJFX_WITH_SKIA) && defined(_WIN32)
    if (width <= 0 || height <= 0) return 1;
    OpenJfxSurface* st = asState(handle);
    if (!st || !st->isWindowSurface || !st->windowHdc) return 2;

    // Re-bind the shared GL context to this window's HDC before
    // touching any GL state. The HDC's pixel format is already set
    // from the original create_window_gpu call, so this is the same
    // cheap re-bind path that surface_present_window uses.
    if (!wglMakeCurrent(st->windowHdc, gGlHglrc)) return 3;
    // Context is current — reclaim any GL objects a prior failed-bind
    // surface_destroy had to defer (almost always a no-op).
    drainDeferredGlDeletes();

    const sk_sp<GrDirectContext>& grCtx = gpuDirectContext();
    if (!grCtx) return 4;

    // ================================================================
    // RESIZE INVARIANT #3 — No SwapBuffers nudge here.
    // ================================================================
    // We previously presented an extra frame inside surface_resize_gl
    // to force Windows to reallocate the default-framebuffer back
    // buffer up front, with a slate-900 clear so that frame wasn't
    // visible garbage. That added a second SwapBuffers per WM_SIZE
    // tick — at ~80 resize events/sec, the extra ~3-4 ms it cost
    // kept WndProc busy long enough that DWM couldn't repaint the
    // desktop area newly exposed by the moving window edge, so the
    // user saw ghost pixels from the previous window position
    // lingering on the desktop. The clear-color variant also
    // alternated content with slate-900 in DWM's compositor →
    // visible flicker. Letting the single SwapBuffers in
    // surface_present_window do all the buffer-resize work keeps
    // the WndProc tick lean.
    //
    // Hard rule: never add a SwapBuffers, glClear+SwapBuffers, or
    // an aux-HGLRC clear inside this function. The first frame
    // after a shrink may briefly show content drawn into a
    // still-larger back buffer that DWM then scales down to fit the
    // new client area, but that's a 1-frame soft-scale (~5%
    // imperceptible to most users) rather than a sustained artifact,
    // and it disappears the moment the drag stops.

    // If this presentable is using the offscreen-FBO present path,
    // recreate the offscreen attachment at the new size. The old one
    // is discarded — its content is lost, but the next pulse paints
    // the whole scene anyway (the painter marks the scene dirty on
    // resize, so no preserved-buffer expectation holds).
    constexpr GrGLenum kGlRgba8 = 0x8058;
    GLuint chosenFbo = 0;
    if (st->offscreenFbo) {
        if (!allocateOffscreenFbo(st, width, height)) {
            return 6; // offscreen path was active; failure leaves us inconsistent
        }
        chosenFbo = st->offscreenFbo;
    }

    GrGLFramebufferInfo fbInfo;
    fbInfo.fFBOID  = chosenFbo;
    fbInfo.fFormat = kGlRgba8;
    GrBackendRenderTarget rt = GrBackendRenderTargets::MakeGL(
        width, height,
        /* sampleCount */ 0,
        /* stencilBits */ 8,
        fbInfo);
    sk_sp<SkSurface> newSurface = SkSurfaces::WrapBackendRenderTarget(
        grCtx.get(), rt,
        kBottomLeft_GrSurfaceOrigin,
        kRGBA_8888_SkColorType,
        /* colorSpace */ nullptr,
        /* surfaceProps */ nullptr,
        /* releaseProc */ nullptr,
        /* releaseContext */ nullptr);
    if (!newSurface) return 5;

    // Replace; the old surface releases when the last sk_sp drops it.
    st->surface = std::move(newSurface);

    if (getenv("OPENJFX_SKIA_DRAW_DIAG")) {
        SkISize imgSize = st->surface->imageInfo().dimensions();
        // Query GL state too — viewport + window client rect — to
        // see if anything diverges from our requested logical size.
        GLint viewport[4] = {0, 0, 0, 0};
        glGetIntegerv(0x0BA2 /* GL_VIEWPORT */, viewport);
        RECT crect = {};
        if (::GetClientRect(::WindowFromDC(st->windowHdc), &crect)) {
            std::fprintf(stderr,
                "[skia.gl] resize: req=%dx%d  Skia surface=%dx%d  GL viewport=(%d,%d,%dx%d)  HWND client=%ldx%ld\n",
                width, height, imgSize.width(), imgSize.height(),
                viewport[0], viewport[1], viewport[2], viewport[3],
                crect.right - crect.left, crect.bottom - crect.top);
        } else {
            std::fprintf(stderr,
                "[skia.gl] resize: req=%dx%d  Skia surface=%dx%d  GL viewport=(%d,%d,%dx%d)\n",
                width, height, imgSize.width(), imgSize.height(),
                viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }
    return 0;
#else
    (void)handle; (void)width; (void)height;
    return -1;
#endif
}

// ---------------------------------------------------------------------------
// Resize-time window clear (Glass WM_SIZE hook target). See the
// declaration in openjfx_skia_bridge.h for the motivation.
//
// Uses a *secondary* HGLRC, distinct from the render thread's main
// context, so the FX thread can call this synchronously inside the
// WM_SIZE handler without stealing the render thread's context. Both
// contexts share the same HDC pixel format (set by the render thread
// on its first bind to this window); both write to the same window
// surface — the last SwapBuffers wins, which for our purposes is fine.
// ---------------------------------------------------------------------------

OPENJFX_API int32_t openjfx_skia_window_clear(
    void* hwndVoid, int32_t width, int32_t height,
    uint8_t r, uint8_t g, uint8_t b) {
#if defined(OPENJFX_WITH_SKIA) && defined(_WIN32)
    HWND hwnd = reinterpret_cast<HWND>(hwndVoid);
    if (!hwnd || width <= 0 || height <= 0) return 1;

    HDC hdc = ::GetDC(hwnd);
    if (!hdc) {
        static bool warnedDC = false;
        if (!warnedDC) {
            warnedDC = true;
            std::fprintf(stderr, "[openjfx-skia] window_clear: GetDC FAILED (err %lu)\n",
                         (unsigned long)::GetLastError());
        }
        return 2;
    }

    // The render thread sets the pixel format on its first bind to
    // this HDC. If it hasn't bound yet — e.g. WM_SIZE fires during
    // initial window creation — there's no compatible format and
    // wglCreateContext would fail.
    int pfIdx = ::GetPixelFormat(hdc);
    if (pfIdx == 0) {
        static bool warnedPF = false;
        if (!warnedPF) {
            warnedPF = true;
            std::fprintf(stderr,
                "[openjfx-skia] window_clear: GetPixelFormat=0 — render thread "
                "hasn't bound this HWND's HDC yet (err %lu). Clear is a no-op "
                "until render-thread bind. (Reported once.)\n",
                (unsigned long)::GetLastError());
        }
        ::ReleaseDC(hwnd, hdc);
        return 3;
    }

    // Lazy-create the secondary HGLRC the first time we're called.
    // No wglShareLists — we don't need any shared resources (just a
    // raw glClear + SwapBuffers). This is per-process: the same
    // secondary context handles all windows.
    static HGLRC s_auxHglrc = nullptr;
    if (!s_auxHglrc) {
        s_auxHglrc = ::wglCreateContext(hdc);
        if (!s_auxHglrc) {
            std::fprintf(stderr,
                "[openjfx-skia] window_clear: wglCreateContext FAILED (last error %lu)\n",
                ::GetLastError());
            ::ReleaseDC(hwnd, hdc);
            return 4;
        }
        std::fprintf(stderr,
            "[openjfx-skia] window_clear: secondary HGLRC created OK\n");
    }

    if (!::wglMakeCurrent(hdc, s_auxHglrc)) {
        std::fprintf(stderr,
            "[openjfx-skia] window_clear: wglMakeCurrent FAILED (last error %lu)\n",
            ::GetLastError());
        ::ReleaseDC(hwnd, hdc);
        return 5;
    }

    glViewport(0, 0, width, height);
    glClearColor(r / 255.0f, g / 255.0f, b / 255.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    ::SwapBuffers(hdc);

    // Release the context from this thread so the render thread (or
    // any other) can re-bind without conflict on its next operation.
    ::wglMakeCurrent(nullptr, nullptr);
    ::ReleaseDC(hwnd, hdc);
    return 0;
#else
    (void)hwndVoid; (void)width; (void)height; (void)r; (void)g; (void)b;
    return -1;
#endif
}

// ---------------------------------------------------------------------------
// openjfx_skia_window_get_refresh_hz — see header for the contract.
//
// Multi-monitor support: PresentingPainter calls this every pulse so the
// present-rate cap follows the window onto whichever monitor it lives on.
// The Win32 round-trip (MonitorFromWindow + GetMonitorInfoW +
// EnumDisplaySettingsW) is sub-microsecond, so we deliberately skip
// caching — moving a window between monitors needs zero special handling.
// Returns 0 on any failure; caller falls back to a sane default.
// ---------------------------------------------------------------------------
OPENJFX_API int32_t openjfx_skia_window_get_refresh_hz(void* hwndVoid) {
#if defined(_WIN32)
    // Use the typedef-agnostic Win32 names (MONITORINFOEX / DEVMODE /
    // GetMonitorInfo / EnumDisplaySettings) — they resolve via macros
    // to the A or W variant based on UNICODE so we don't depend on
    // either being declared explicitly. With WIN32_LEAN_AND_MEAN set
    // higher up in this file, the W-suffixed direct symbols aren't
    // reliably visible.
    HWND hwnd = reinterpret_cast<HWND>(hwndVoid);
    if (!hwnd) return 0;

    HMONITOR mon = ::MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
    if (!mon) return 0;

    MONITORINFOEX mi;
    ::ZeroMemory(&mi, sizeof(mi));
    mi.cbSize = sizeof(mi);
    if (!::GetMonitorInfo(mon, &mi)) return 0;

    DEVMODE dm;
    ::ZeroMemory(&dm, sizeof(dm));
    dm.dmSize = sizeof(dm);
    if (!::EnumDisplaySettings(mi.szDevice, ENUM_CURRENT_SETTINGS, &dm)) {
        return 0;
    }
    // dmDisplayFrequency is 0 or 1 for "default refresh, no specific
    // rate" (rare on modern hardware). Treat as unknown.
    const DWORD hz = dm.dmDisplayFrequency;
    if (hz <= 1) return 0;
    return static_cast<int32_t>(hz);
#else
    // TODO macOS: CGDisplayCopyDisplayMode + CGDisplayModeGetRefreshRate.
    // TODO Linux: XRandR XRRGetScreenInfo → XRRConfigCurrentRate, or
    //             Wayland zwp_output via wp_presentation feedback.
    (void)hwndVoid;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_surface_create_swap_chain_d3d(
    uintptr_t hwnd, int32_t width, int32_t height) {
    if (!hwnd || width <= 0 || height <= 0) return 0;
#if defined(OPENJFX_WITH_SKIA) && defined(_WIN32)
    // Force GrDirectContext init so we know which backend is active.
    const sk_sp<GrDirectContext>& grCtx = gpuDirectContext();
    if (!grCtx || gGpuBackend != GpuBackend::D3D) {
        // D3D init failed earlier (no D3D12 driver, etc.); caller falls
        // back to GL direct-present.
        return 0;
    }
    OpenJfxD3DSwapChain* sc = openjfxD3DCreateSwapChain(
        reinterpret_cast<void*>(hwnd), width, height, grCtx.get());
    if (!sc) return 0;
    auto* st = new OpenJfxSurface();
    st->d3d = sc;
    return reinterpret_cast<uintptr_t>(st);
#else
    (void)hwnd; (void)width; (void)height;
    return 0;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_present_window_d3d(uintptr_t handle, int32_t vsync) {
#if defined(OPENJFX_WITH_SKIA) && defined(_WIN32)
    OpenJfxSurface* st = asState(handle);
    if (!st || !st->d3d) return 1;
    const sk_sp<GrDirectContext>& grCtx = gpuDirectContext();
    return openjfxD3DPresent(st->d3d, grCtx.get(), vsync);
#else
    (void)handle;
    (void)vsync;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_begin_frame_d3d(uintptr_t handle) {
#if defined(OPENJFX_WITH_SKIA) && defined(_WIN32)
    OpenJfxSurface* st = asState(handle);
    if (!st || !st->d3d) return 1;
    return openjfxD3DAcquireNextBuffer(st->d3d);
#else
    (void)handle;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_resize_d3d(
    uintptr_t handle, int32_t width, int32_t height) {
#if defined(OPENJFX_WITH_SKIA) && defined(_WIN32)
    OpenJfxSurface* st = asState(handle);
    if (!st || !st->d3d) return 1;
    const sk_sp<GrDirectContext>& grCtx = gpuDirectContext();
    return openjfxD3DResize(st->d3d, width, height, grCtx.get());
#else
    (void)handle; (void)width; (void)height;
    return -1;
#endif
}

OPENJFX_API void openjfx_skia_surface_destroy(uintptr_t handle) {
    if (!handle) return;
#ifdef OPENJFX_WITH_SKIA
    // Use the raw cast (not asState) so a double-destroy is a no-op:
    // the second call sees the poisoned magic and skips.
    auto* st = reinterpret_cast<OpenJfxSurface*>(handle);
    if (st && st->magic == OpenJfxSurface::kMagic) {
        st->magic = 0; // poison: a later asState() on this handle fails
#ifdef _WIN32
        // Tear down the offscreen FBO/texture/RB before releasing the
        // HDC. Requires the GL context current; safe even if the
        // offscreen attachment wasn't allocated (releaseOffscreenFbo
        // checks each handle).
        if (st->isWindowSurface && st->offscreenFbo) {
            if (st->windowHdc && wglMakeCurrent(st->windowHdc, gGlHglrc)) {
                drainDeferredGlDeletes();   // free earlier orphans while current
                releaseOffscreenFbo(st);
            } else {
                // No context could be made current (HDC already torn down).
                // Don't orphan the GL objects in gGlHglrc — defer their delete
                // to the next op that makes the context current.
                {
                    std::lock_guard<std::mutex> lock(gDeferredGlMutex);
                    gDeferredGlDeletes.push_back(
                        { st->offscreenFbo, st->offscreenTex, st->offscreenRb });
                }
                st->offscreenFbo = st->offscreenTex = st->offscreenRb = 0;
            }
        }
        // Release the per-window HDC we obtained at create_window_gpu.
        // Window class is CS_OWNDC so this is mostly a courtesy, but
        // staying balanced keeps the GDI side happy under churn.
        if (st->isWindowSurface && st->windowHwnd && st->windowHdc) {
            ReleaseDC(st->windowHwnd, st->windowHdc);
        }
        // Tear down the D3D swap chain + per-buffer SkSurfaces.
        if (st->d3d) {
            openjfxD3DDestroySwapChain(st->d3d);
            st->d3d = nullptr;
        }
#endif
        delete st;
    }
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_width(uintptr_t handle) {
#ifdef OPENJFX_WITH_SKIA
    SkSurface* s = asSurface(handle);
    return s ? s->width() : -1;
#else
    (void)handle;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_height(uintptr_t handle) {
#ifdef OPENJFX_WITH_SKIA
    SkSurface* s = asSurface(handle);
    return s ? s->height() : -1;
#else
    (void)handle;
    return -1;
#endif
}

// ===========================================================================
// SkCanvas ops (handle-based)
// ===========================================================================

OPENJFX_API int32_t openjfx_skia_surface_clear(
    uintptr_t handle, uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return 1;
    // drawColor with kSrc honors the current canvas clip — required
    // by Prism's Graphics.clear() contract (dirty-region rendering
    // depends on it). SkCanvas::clear() ignores the clip and fills
    // the whole surface, which is wrong here.
    canvas->drawColor(SkColorSetARGB(a, r, g, b), SkBlendMode::kSrc);
    return 0;
#else
    (void)handle; (void)r; (void)g; (void)b; (void)a;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_fill_rect(
    uintptr_t handle,
    int32_t x, int32_t y, int32_t w, int32_t h,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return 1;
    // TEMP DIAG (env-gated, atomic counter, no unsafe deref).
    // OPENJFX_SKIA_DRAW_DIAG=1 to enable. Strip once gradient cards
    // are fixed.
    static const bool diagEnabled = []() {
        const char* e = std::getenv("OPENJFX_SKIA_DRAW_DIAG");
        return e && *e && *e != '0';
    }();
    if (diagEnabled) {
        static std::atomic<int> n { 0 };
        int v = n.fetch_add(1);
        if (v < 200) {
            SkRect clip = canvas->getLocalClipBounds();
            SkMatrix m = canvas->getTotalMatrix();
            std::fprintf(stderr,
                "[skia.fr]  #%d canv=%p rgba=#%02x%02x%02x%02x "
                "rect=(%d,%d %dx%d) clipLocal=(%.0f,%.0f %.0fx%.0f) "
                "m=[%.2f %.2f %.0f / %.2f %.2f %.0f]\n",
                v, (void*)canvas, r, g, b, a, x, y, w, h,
                clip.x(), clip.y(), clip.width(), clip.height(),
                m.getScaleX(), m.getSkewX(), m.getTranslateX(),
                m.getSkewY(), m.getScaleY(), m.getTranslateY());
        }
    }
    SkPaint paint;
    configureFill(paint, *asState(handle), r, g, b, a);
    SkRect rect = SkRect::MakeXYWH(
        static_cast<float>(x), static_cast<float>(y),
        static_cast<float>(w), static_cast<float>(h));
    canvas->drawRect(rect, paint);
    return 0;
#else
    (void)handle; (void)x; (void)y; (void)w; (void)h;
    (void)r; (void)g; (void)b; (void)a;
    return -1;
#endif
}

// ---- Filled primitives -----------------------------------------------------

OPENJFX_API int32_t openjfx_skia_surface_fill_round_rect(
    uintptr_t handle,
    float x, float y, float w, float h,
    float arcW, float arcH,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return 1;
    SkPaint paint;
    configureFill(paint, *asState(handle), r, g, b, a);
    canvas->drawRoundRect(
        SkRect::MakeXYWH(x, y, w, h), arcW * 0.5f, arcH * 0.5f, paint);
    return 0;
#else
    (void)handle; (void)x; (void)y; (void)w; (void)h;
    (void)arcW; (void)arcH; (void)r; (void)g; (void)b; (void)a;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_fill_oval(
    uintptr_t handle,
    float x, float y, float w, float h,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return 1;
    SkPaint paint;
    configureFill(paint, *asState(handle), r, g, b, a);
    canvas->drawOval(SkRect::MakeXYWH(x, y, w, h), paint);
    return 0;
#else
    (void)handle; (void)x; (void)y; (void)w; (void)h;
    (void)r; (void)g; (void)b; (void)a;
    return -1;
#endif
}

// ---- Stroked primitives ----------------------------------------------------

OPENJFX_API int32_t openjfx_skia_surface_stroke_rect(
    uintptr_t handle,
    float x, float y, float w, float h,
    float width, int32_t cap, int32_t join, float miter,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return 1;
    SkPaint paint;
    configureStroke(paint, *asState(handle), r, g, b, a, width, cap, join, miter);
    canvas->drawRect(SkRect::MakeXYWH(x, y, w, h), paint);
    return 0;
#else
    (void)handle; (void)x; (void)y; (void)w; (void)h;
    (void)width; (void)cap; (void)join; (void)miter;
    (void)r; (void)g; (void)b; (void)a;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_stroke_round_rect(
    uintptr_t handle,
    float x, float y, float w, float h,
    float arcW, float arcH,
    float width, int32_t cap, int32_t join, float miter,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return 1;
    SkPaint paint;
    configureStroke(paint, *asState(handle), r, g, b, a, width, cap, join, miter);
    canvas->drawRoundRect(
        SkRect::MakeXYWH(x, y, w, h), arcW * 0.5f, arcH * 0.5f, paint);
    return 0;
#else
    (void)handle; (void)x; (void)y; (void)w; (void)h;
    (void)arcW; (void)arcH;
    (void)width; (void)cap; (void)join; (void)miter;
    (void)r; (void)g; (void)b; (void)a;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_stroke_oval(
    uintptr_t handle,
    float x, float y, float w, float h,
    float width, int32_t cap, int32_t join, float miter,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return 1;
    SkPaint paint;
    configureStroke(paint, *asState(handle), r, g, b, a, width, cap, join, miter);
    canvas->drawOval(SkRect::MakeXYWH(x, y, w, h), paint);
    return 0;
#else
    (void)handle; (void)x; (void)y; (void)w; (void)h;
    (void)width; (void)cap; (void)join; (void)miter;
    (void)r; (void)g; (void)b; (void)a;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_stroke_line(
    uintptr_t handle,
    float x1, float y1, float x2, float y2,
    float width, int32_t cap, int32_t join, float miter,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return 1;
    SkPaint paint;
    configureStroke(paint, *asState(handle), r, g, b, a, width, cap, join, miter);
    canvas->drawLine(x1, y1, x2, y2, paint);
    return 0;
#else
    (void)handle; (void)x1; (void)y1; (void)x2; (void)y2;
    (void)width; (void)cap; (void)join; (void)miter;
    (void)r; (void)g; (void)b; (void)a;
    return -1;
#endif
}

// ===========================================================================
// Canvas state
// ===========================================================================

OPENJFX_API int32_t openjfx_skia_surface_save(uintptr_t handle) {
#ifdef OPENJFX_WITH_SKIA
    OpenJfxSurface* st = asState(handle);
    if (!st) return -1;
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return -1;
    st->bridgeSaveCount++;
    return canvas->save();
#else
    (void)handle;
    return -1;
#endif
}

// Batched per-draw setup: save + clip + matrix + sticky state in one
// FFM crossing. The clip is applied in DEVICE coordinates (CTM is
// identity at this moment, just after save) — this preserves the
// "clip before matrix" invariant that keeps TableView/ScrollPane
// rendering correct.
OPENJFX_API int32_t openjfx_skia_surface_begin_draw(
    uintptr_t handle,
    float m00, float m01, float m02,
    float m10, float m11, float m12,
    int32_t clipX, int32_t clipY, int32_t clipW, int32_t clipH,
    int32_t hasClip,
    int32_t blendMode,
    float   extraAlpha) {
#ifdef OPENJFX_WITH_SKIA
    OpenJfxSurface* st = asState(handle);
    if (!st) return -1;
    // Route through asCanvas so the D3D swap-chain / recording backends (where
    // st->surface is null) get their real canvas. Using st->surface->getCanvas()
    // and bailing on !st->surface dropped this bracket's save()/clip/setMatrix on
    // those backends while end_draw (which uses asCanvas) still restore()d the
    // real canvas — an unbalanced save stack + lost transform/clip. (BUG-2)
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return -1;

    // Sticky state used by every draw's configureFill / configureStroke.
    if (blendMode >= 0 && blendMode <= static_cast<int32_t>(SkBlendMode::kLastMode)) {
        st->blendMode = static_cast<SkBlendMode>(blendMode);
    }
    if (extraAlpha < 0.0f) extraAlpha = 0.0f;
    if (extraAlpha > 1.0f) extraAlpha = 1.0f;
    st->extraAlpha = extraAlpha;

    // Per-draw surface diag, retained as a regression hook. Off by
    // default; flip OPENJFX_SKIA_DRAW_DIAG=1 to enable. Prints
    // surface dimensions + isWindow flag so we can immediately tell
    // when scene rendering routes to an off-screen drawable instead
    // of the main window surface (the signature of RESIZE_INVARIANT
    // #8 if the contentWidth/Height callback ever regresses).
    static int matrixDiagCount = 0;
    if (matrixDiagCount < 20 && getenv("OPENJFX_SKIA_DRAW_DIAG")) {
        std::fprintf(stderr,
            "[skia.matrix] handle=%p surface=%dx%d (isWindow=%d isD3D=%d)\n",
            (void*) st,
            st->surface ? st->surface->width() : canvas->imageInfo().width(),
            st->surface ? st->surface->height() : canvas->imageInfo().height(),
            st->isWindowSurface ? 1 : 0, (st->d3d != nullptr) ? 1 : 0);
        matrixDiagCount++;
    }

    // TEMP DIAG (env-gated): log every begin_draw with its incoming
    // transform + clipBounds. Tells us exactly what GraphicsContextJava
    // pushed for each draw, including the cards' fillRect. Cap at 200.
    static const bool bdDiag = []() {
        const char* e = std::getenv("OPENJFX_SKIA_DRAW_DIAG");
        return e && *e && *e != '0';
    }();
    if (bdDiag) {
        static std::atomic<int> bn { 0 };
        int v = bn.fetch_add(1);
        if (v < 200) {
            std::fprintf(stderr,
                "[skia.bd]  #%d st=%p hasClip=%d "
                "clip=(%d,%d %dx%d) m=[%.2f %.2f %.0f / %.2f %.2f %.0f] "
                "blend=%d alpha=%.2f\n",
                v, (void*)st, hasClip, clipX, clipY, clipW, clipH,
                m00, m01, m02, m10, m11, m12,
                blendMode, extraAlpha);
        }
    }

    canvas->save();
    if (hasClip) {
        // Clamp clip extents: a negative/garbage width or height would build an
        // inverted SkRect and mis-scissor the GPU (or feed the raster scan
        // converter nonsense). Negative -> 0 yields an empty clip (nothing
        // draws), the safe degrade. This is the highest-traffic entry point, so
        // the guard matches the coordFinite discipline in buildPath/draw_glyphs.
        float cw = clipW > 0 ? static_cast<float>(clipW) : 0.0f;
        float ch = clipH > 0 ? static_cast<float>(clipH) : 0.0f;
        canvas->clipRect(SkRect::MakeXYWH(
            static_cast<float>(clipX), static_cast<float>(clipY), cw, ch));
    }
    // Validate the CTM before handing it to Skia. A non-finite term (NaN/Inf
    // from e.g. a large off-screen translate * HiDPI scale overflow) would
    // corrupt every subsequent draw on this bracket. Degrade to identity rather
    // than feed the rasterizer garbage. save/restore stays balanced (end_draw
    // restores). The Skia canvas's pre-existing matrix is verified identity (no
    // implicit DPI base — see the [skia.matrix] diag investigation).
    if (coordFinite(m00) && coordFinite(m01) && coordFinite(m02)
        && coordFinite(m10) && coordFinite(m11) && coordFinite(m12)) {
        SkMatrix m;
        m.setAll(m00, m01, m02, m10, m11, m12, 0, 0, 1);
        canvas->setMatrix(m);
    } else {
        reportBadPath("begin_draw(ctm)", 0, coordFinite(m00) ? m11 : m00);
        canvas->resetMatrix();
    }
    return 0;
#else
    (void)handle; (void)m00; (void)m01; (void)m02;
    (void)m10; (void)m11; (void)m12;
    (void)clipX; (void)clipY; (void)clipW; (void)clipH;
    (void)hasClip; (void)blendMode; (void)extraAlpha;
    return -1;
#endif
}

// Pair to begin_draw: pops the canvas save level. One FFM crossing.
OPENJFX_API int32_t openjfx_skia_surface_end_draw(uintptr_t handle) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return -1;
    canvas->restore();
    return 0;
#else
    (void)handle;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_restore(uintptr_t handle) {
#ifdef OPENJFX_WITH_SKIA
    OpenJfxSurface* st = asState(handle);
    if (!st) return -1;
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return -1;
    if (st->bridgeSaveCount <= 0) {
        static int reported = 0;
        if (reported < 8) {
            ++reported;
            std::fprintf(stderr,
                "[openjfx-skia] surface_restore underflow refused "
                "(handle=%p count=%d) — fix the WC layer pairing.\n",
                (void*)st, st->bridgeSaveCount);
        }
        return -2;
    }
    st->bridgeSaveCount--;
    canvas->restore();
    return 0;
#else
    (void)handle;
    return -1;
#endif
}

// Push a Skia saveLayer with alpha-only composition. Subsequent draws on
// this canvas go into an internal off-screen surface that Skia allocates
// and manages; on the matching surface_restore the layer is composited
// back onto the canvas with the given alpha. This is the Chrome-grade
// primitive for CSS opacity / WebKit beginTransparencyLayer — no second
// SkSurface allocation by us, no surface_draw_surface, no recursive
// GrDirectContext state hazard. Skia handles all the layer compositing
// correctly including overlapping semi-transparent draws within the
// layer.
//   alpha: 0..255 (clamped). Caller must pair with surface_restore.
OPENJFX_API int32_t openjfx_skia_surface_save_layer_alpha(
    uintptr_t handle, int32_t alpha) {
#ifdef OPENJFX_WITH_SKIA
    OpenJfxSurface* st = asState(handle);
    if (!st) return -1;
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return -1;
    if (alpha < 0) alpha = 0;
    if (alpha > 255) alpha = 255;
    st->bridgeSaveCount++;
    canvas->saveLayerAlpha(nullptr, static_cast<U8CPU>(alpha));
    return 0;
#else
    (void)handle; (void)alpha;
    return -1;
#endif
}

// Apply an arbitrary path clip to the current canvas state. Callers are
// responsible for bracketing in a surface_save / surface_restore pair so
// the clip can be undone — clipPath ANDs into the canvas's current clip,
// it doesn't replace. Used by WCGraphicsPrismContext.SkiaClipLayer to
// honor CSS border-radius / clip-path on Skia surfaces without the
// recursive RTT-readback path that the legacy ClipLayer takes.
//   clipOp: 0 = intersect (normal clipPath), 1 = difference (clip-out)
OPENJFX_API int32_t openjfx_skia_surface_clip_path(
    uintptr_t handle,
    const uint8_t* verbs, int32_t verbCount,
    const float*   coords, int32_t coordCount,
    int32_t fillRule, int32_t clipOp) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return -1;
    SkPath path;
    if (!buildPath(path, verbs, verbCount, coords, coordCount, fillRule)) {
        return -2;
    }
    canvas->clipPath(path,
                     clipOp == 1 ? SkClipOp::kDifference
                                 : SkClipOp::kIntersect,
                     /*doAntiAlias=*/true);
    return 0;
#else
    (void)handle; (void)verbs; (void)verbCount;
    (void)coords; (void)coordCount; (void)fillRule; (void)clipOp;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_translate(uintptr_t handle, float dx, float dy) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return -1;
    canvas->translate(dx, dy);
    return 0;
#else
    (void)handle; (void)dx; (void)dy;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_set_matrix(
    uintptr_t handle,
    float m00, float m01, float m02,
    float m10, float m11, float m12) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return -1;
    SkMatrix m;
    m.setAll(m00, m01, m02,
             m10, m11, m12,
             0, 0, 1);
    canvas->setMatrix(m);
    return 0;
#else
    (void)handle; (void)m00; (void)m01; (void)m02;
    (void)m10; (void)m11; (void)m12;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_clip_rect(
    uintptr_t handle, float x, float y, float w, float h) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return -1;
    canvas->clipRect(SkRect::MakeXYWH(x, y, w, h));
    return 0;
#else
    (void)handle; (void)x; (void)y; (void)w; (void)h;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_set_blend_mode(
    uintptr_t handle, int32_t mode) {
#ifdef OPENJFX_WITH_SKIA
    OpenJfxSurface* st = asState(handle);
    if (!st) return -1;
    // SkBlendMode values are 0..28; clamp to a known range.
    if (mode < 0 || mode > static_cast<int>(SkBlendMode::kLastMode)) return -2;
    st->blendMode = static_cast<SkBlendMode>(mode);
    return 0;
#else
    (void)handle; (void)mode;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_set_extra_alpha(
    uintptr_t handle, float alpha) {
#ifdef OPENJFX_WITH_SKIA
    OpenJfxSurface* st = asState(handle);
    if (!st) return -1;
    if (alpha < 0.0f) alpha = 0.0f;
    if (alpha > 1.0f) alpha = 1.0f;
    st->extraAlpha = alpha;
    return 0;
#else
    (void)handle; (void)alpha;
    return -1;
#endif
}

// ===========================================================================
// SkImageFilter lifecycle
// ===========================================================================

OPENJFX_API uintptr_t openjfx_skia_filter_create_blur(
    float sigmaX, float sigmaY, int32_t tileMode) {
#ifdef OPENJFX_WITH_SKIA
    sk_sp<SkImageFilter> f = SkImageFilters::Blur(
        sigmaX, sigmaY, mapTileMode(tileMode), nullptr);
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)sigmaX; (void)sigmaY; (void)tileMode;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_drop_shadow(
    float dx, float dy, float sigmaX, float sigmaY,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
#ifdef OPENJFX_WITH_SKIA
    sk_sp<SkImageFilter> f = SkImageFilters::DropShadow(
        dx, dy, sigmaX, sigmaY, SkColorSetARGB(a, r, g, b), nullptr);
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)dx; (void)dy; (void)sigmaX; (void)sigmaY;
    (void)r; (void)g; (void)b; (void)a;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_color_matrix(const float* m20) {
#ifdef OPENJFX_WITH_SKIA
    if (!m20) return 0;
    SkColorMatrix cm;
    cm.setRowMajor(m20);
    sk_sp<SkColorFilter> cf = SkColorFilters::Matrix(cm);
    if (!cf) return 0;
    sk_sp<SkImageFilter> f = SkImageFilters::ColorFilter(std::move(cf), nullptr);
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)m20;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_compose(
    uintptr_t outerHandle, uintptr_t innerHandle) {
#ifdef OPENJFX_WITH_SKIA
    SkImageFilter* outer = asFilter(outerHandle);
    SkImageFilter* inner = asFilter(innerHandle);
    if (!outer || !inner) return 0;
    sk_sp<SkImageFilter> f = SkImageFilters::Compose(
        sk_ref_sp(outer), sk_ref_sp(inner));
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)outerHandle; (void)innerHandle;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_drop_shadow_only(
    float dx, float dy, float sigmaX, float sigmaY,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
#ifdef OPENJFX_WITH_SKIA
    sk_sp<SkImageFilter> f = SkImageFilters::DropShadowOnly(
        dx, dy, sigmaX, sigmaY, SkColorSetARGB(a, r, g, b), nullptr);
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)dx; (void)dy; (void)sigmaX; (void)sigmaY;
    (void)r; (void)g; (void)b; (void)a;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_blend(
    int32_t blendMode, uintptr_t bgHandle, uintptr_t fgHandle) {
#ifdef OPENJFX_WITH_SKIA
    SkBlendMode mode = static_cast<SkBlendMode>(blendMode);
    sk_sp<SkImageFilter> bg = bgHandle
        ? sk_ref_sp(asFilter(bgHandle)) : sk_sp<SkImageFilter>(nullptr);
    sk_sp<SkImageFilter> fg = fgHandle
        ? sk_ref_sp(asFilter(fgHandle)) : sk_sp<SkImageFilter>(nullptr);
    sk_sp<SkImageFilter> f = SkImageFilters::Blend(mode, std::move(bg), std::move(fg));
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)blendMode; (void)bgHandle; (void)fgHandle;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_merge(
    const uintptr_t* filters, int32_t count) {
#ifdef OPENJFX_WITH_SKIA
    if (!filters || count <= 0) return 0;
    std::vector<sk_sp<SkImageFilter>> v;
    v.reserve(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        SkImageFilter* p = asFilter(filters[i]);
        v.emplace_back(p ? sk_ref_sp(p) : sk_sp<SkImageFilter>(nullptr));
    }
    sk_sp<SkImageFilter> f = SkImageFilters::Merge(v.data(),
        static_cast<int>(v.size()), nullptr);
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)filters; (void)count;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_offset(
    float dx, float dy, uintptr_t inputHandle) {
#ifdef OPENJFX_WITH_SKIA
    sk_sp<SkImageFilter> input = inputHandle
        ? sk_ref_sp(asFilter(inputHandle)) : sk_sp<SkImageFilter>(nullptr);
    sk_sp<SkImageFilter> f = SkImageFilters::Offset(dx, dy, std::move(input));
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)dx; (void)dy; (void)inputHandle;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_crop(
    float x, float y, float w, float h, uintptr_t inputHandle) {
#ifdef OPENJFX_WITH_SKIA
    sk_sp<SkImageFilter> input = inputHandle
        ? sk_ref_sp(asFilter(inputHandle)) : sk_sp<SkImageFilter>(nullptr);
    SkRect rect = SkRect::MakeXYWH(x, y, w, h);
    sk_sp<SkImageFilter> f = SkImageFilters::Crop(rect, std::move(input));
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)x; (void)y; (void)w; (void)h; (void)inputHandle;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_erode(
    float rx, float ry, uintptr_t inputHandle) {
#ifdef OPENJFX_WITH_SKIA
    sk_sp<SkImageFilter> input = inputHandle
        ? sk_ref_sp(asFilter(inputHandle)) : sk_sp<SkImageFilter>(nullptr);
    sk_sp<SkImageFilter> f = SkImageFilters::Erode(rx, ry, std::move(input));
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)rx; (void)ry; (void)inputHandle;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_dilate(
    float rx, float ry, uintptr_t inputHandle) {
#ifdef OPENJFX_WITH_SKIA
    sk_sp<SkImageFilter> input = inputHandle
        ? sk_ref_sp(asFilter(inputHandle)) : sk_sp<SkImageFilter>(nullptr);
    sk_sp<SkImageFilter> f = SkImageFilters::Dilate(rx, ry, std::move(input));
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)rx; (void)ry; (void)inputHandle;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_matrix_transform(
    float m00, float m01, float m02,
    float m10, float m11, float m12,
    float m20, float m21, float m22,
    uintptr_t inputHandle) {
#ifdef OPENJFX_WITH_SKIA
    sk_sp<SkImageFilter> input = inputHandle
        ? sk_ref_sp(asFilter(inputHandle)) : sk_sp<SkImageFilter>(nullptr);
    SkMatrix m;
    m.setAll(m00, m01, m02, m10, m11, m12, m20, m21, m22);
    sk_sp<SkImageFilter> f = SkImageFilters::MatrixTransform(
        m, SkSamplingOptions(SkFilterMode::kLinear), std::move(input));
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)m00;(void)m01;(void)m02;(void)m10;(void)m11;(void)m12;
    (void)m20;(void)m21;(void)m22;(void)inputHandle;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_displacement_map(
    int32_t channelX, int32_t channelY, float scale,
    uintptr_t dispHandle, uintptr_t inputHandle) {
#ifdef OPENJFX_WITH_SKIA
    if (!dispHandle) return 0;
    auto mapChannel = [](int c) {
        switch (c) {
            case 0:  return SkColorChannel::kR;
            case 1:  return SkColorChannel::kG;
            case 2:  return SkColorChannel::kB;
            default: return SkColorChannel::kA;
        }
    };
    sk_sp<SkImageFilter> disp = sk_ref_sp(asFilter(dispHandle));
    sk_sp<SkImageFilter> input = inputHandle
        ? sk_ref_sp(asFilter(inputHandle)) : sk_sp<SkImageFilter>(nullptr);
    sk_sp<SkImageFilter> f = SkImageFilters::DisplacementMap(
        mapChannel(channelX), mapChannel(channelY), scale,
        std::move(disp), std::move(input));
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)channelX; (void)channelY; (void)scale;
    (void)dispHandle; (void)inputHandle;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_image(uintptr_t imageHandle) {
#ifdef OPENJFX_WITH_SKIA
    SkImage* img = asImage(imageHandle);
    if (!img) return 0;
    sk_sp<SkImageFilter> f = SkImageFilters::Image(
        sk_ref_sp(img), SkSamplingOptions(SkFilterMode::kLinear));
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)imageHandle;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_shader(uintptr_t shaderHandle) {
#ifdef OPENJFX_WITH_SKIA
    SkShader* sh = asShader(shaderHandle);
    if (!sh) return 0;
    sk_sp<SkImageFilter> f = SkImageFilters::Shader(sk_ref_sp(sh));
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)shaderHandle;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_distant_lit_diffuse(
    float dirX, float dirY, float dirZ,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a,
    float surfaceScale, float kd, uintptr_t inputHandle) {
#ifdef OPENJFX_WITH_SKIA
    sk_sp<SkImageFilter> input = inputHandle
        ? sk_ref_sp(asFilter(inputHandle)) : sk_sp<SkImageFilter>(nullptr);
    SkPoint3 dir = SkPoint3::Make(dirX, dirY, dirZ);
    sk_sp<SkImageFilter> f = SkImageFilters::DistantLitDiffuse(
        dir, SkColorSetARGB(a, r, g, b), surfaceScale, kd, std::move(input));
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)dirX;(void)dirY;(void)dirZ;(void)r;(void)g;(void)b;(void)a;
    (void)surfaceScale;(void)kd;(void)inputHandle;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_point_lit_diffuse(
    float lx, float ly, float lz,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a,
    float surfaceScale, float kd, uintptr_t inputHandle) {
#ifdef OPENJFX_WITH_SKIA
    sk_sp<SkImageFilter> input = inputHandle
        ? sk_ref_sp(asFilter(inputHandle)) : sk_sp<SkImageFilter>(nullptr);
    SkPoint3 loc = SkPoint3::Make(lx, ly, lz);
    sk_sp<SkImageFilter> f = SkImageFilters::PointLitDiffuse(
        loc, SkColorSetARGB(a, r, g, b), surfaceScale, kd, std::move(input));
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)lx;(void)ly;(void)lz;(void)r;(void)g;(void)b;(void)a;
    (void)surfaceScale;(void)kd;(void)inputHandle;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_spot_lit_diffuse(
    float lx, float ly, float lz,
    float tx, float ty, float tz,
    float falloffExponent, float cutoffAngleDegrees,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a,
    float surfaceScale, float kd, uintptr_t inputHandle) {
#ifdef OPENJFX_WITH_SKIA
    sk_sp<SkImageFilter> input = inputHandle
        ? sk_ref_sp(asFilter(inputHandle)) : sk_sp<SkImageFilter>(nullptr);
    SkPoint3 loc = SkPoint3::Make(lx, ly, lz);
    SkPoint3 tgt = SkPoint3::Make(tx, ty, tz);
    sk_sp<SkImageFilter> f = SkImageFilters::SpotLitDiffuse(
        loc, tgt, falloffExponent, cutoffAngleDegrees,
        SkColorSetARGB(a, r, g, b), surfaceScale, kd, std::move(input));
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)lx;(void)ly;(void)lz;(void)tx;(void)ty;(void)tz;
    (void)falloffExponent;(void)cutoffAngleDegrees;
    (void)r;(void)g;(void)b;(void)a;(void)surfaceScale;(void)kd;(void)inputHandle;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_distant_lit_specular(
    float dirX, float dirY, float dirZ,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a,
    float surfaceScale, float ks, float shininess, uintptr_t inputHandle) {
#ifdef OPENJFX_WITH_SKIA
    sk_sp<SkImageFilter> input = inputHandle
        ? sk_ref_sp(asFilter(inputHandle)) : sk_sp<SkImageFilter>(nullptr);
    SkPoint3 dir = SkPoint3::Make(dirX, dirY, dirZ);
    sk_sp<SkImageFilter> f = SkImageFilters::DistantLitSpecular(
        dir, SkColorSetARGB(a, r, g, b),
        surfaceScale, ks, shininess, std::move(input));
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)dirX;(void)dirY;(void)dirZ;(void)r;(void)g;(void)b;(void)a;
    (void)surfaceScale;(void)ks;(void)shininess;(void)inputHandle;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_point_lit_specular(
    float lx, float ly, float lz,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a,
    float surfaceScale, float ks, float shininess, uintptr_t inputHandle) {
#ifdef OPENJFX_WITH_SKIA
    sk_sp<SkImageFilter> input = inputHandle
        ? sk_ref_sp(asFilter(inputHandle)) : sk_sp<SkImageFilter>(nullptr);
    SkPoint3 loc = SkPoint3::Make(lx, ly, lz);
    sk_sp<SkImageFilter> f = SkImageFilters::PointLitSpecular(
        loc, SkColorSetARGB(a, r, g, b),
        surfaceScale, ks, shininess, std::move(input));
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)lx;(void)ly;(void)lz;(void)r;(void)g;(void)b;(void)a;
    (void)surfaceScale;(void)ks;(void)shininess;(void)inputHandle;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_filter_create_spot_lit_specular(
    float lx, float ly, float lz,
    float tx, float ty, float tz,
    float falloffExponent, float cutoffAngleDegrees,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a,
    float surfaceScale, float ks, float shininess, uintptr_t inputHandle) {
#ifdef OPENJFX_WITH_SKIA
    sk_sp<SkImageFilter> input = inputHandle
        ? sk_ref_sp(asFilter(inputHandle)) : sk_sp<SkImageFilter>(nullptr);
    SkPoint3 loc = SkPoint3::Make(lx, ly, lz);
    SkPoint3 tgt = SkPoint3::Make(tx, ty, tz);
    sk_sp<SkImageFilter> f = SkImageFilters::SpotLitSpecular(
        loc, tgt, falloffExponent, cutoffAngleDegrees,
        SkColorSetARGB(a, r, g, b),
        surfaceScale, ks, shininess, std::move(input));
    if (!f) return 0;
    return makeFilterHandle(std::move(f));
#else
    (void)lx;(void)ly;(void)lz;(void)tx;(void)ty;(void)tz;
    (void)falloffExponent;(void)cutoffAngleDegrees;
    (void)r;(void)g;(void)b;(void)a;(void)surfaceScale;(void)ks;(void)shininess;
    (void)inputHandle;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_surface_snapshot_to_image(uintptr_t surfaceHandle) {
#ifdef OPENJFX_WITH_SKIA
    SkSurface* s = asSurface(surfaceHandle);
    if (!s) return 0;
    sk_sp<SkImage> img = s->makeImageSnapshot();
    if (!img) return 0;
    return makeImageHandle(std::move(img));
#else
    (void)surfaceHandle;
    return 0;
#endif
}

// ===========================================================================
// SVG documents (modules/svg). Parse-once / render-many. Each render targets
// the device-pixel size the node resolved, so output is vector-exact at any
// zoom or DPI — never an upscaled raster. The render pass composites in a
// fixed order: background -> grid -> SVG -> optional tint. The tint is a
// node-level recolor of the rasterized output; the parsed SVG paint is never
// edited. Every handle is magic-guarded and poisoned on free, so a stale or
// double-freed handle is rejected (returns an error / no-op) rather than
// dereferenced — there is no use-after-free path here.
// ===========================================================================

OPENJFX_API uintptr_t openjfx_skia_svg_parse(const void* utf8, int32_t length) {
#if defined(OPENJFX_WITH_SKIA) && defined(OPENJFX_WITH_SKIA_SVG)
    if (!utf8 || length <= 0) return 0;
    // MakeCopy duplicates the bytes, so the Java-side buffer / confined arena
    // can be released the instant this returns — no lifetime coupling across
    // the FFM boundary, no dangling pointer into Java memory.
    auto stream = SkMemoryStream::MakeCopy(utf8, static_cast<size_t>(length));
    if (!stream) return 0;
    SkSVGDOM::Builder builder;
    builder.setFontManager(svgFontMgr());              // real fonts for <text>
    builder.setTextShapingFactory(svgShaperFactory()); // shape <text> runs
    sk_sp<SkSVGDOM> dom = builder.make(*stream);
    if (!dom) return 0; // malformed SVG — caller surfaces this as a load error
    // Resolve intrinsic size from the document (width/height attrs or viewBox).
    // containerSize() returns that intrinsic size until we override it via
    // setContainerSize(). Fall back to a sane default so a sizeless SVG still
    // lays out instead of collapsing to 0x0.
    const SkSize& sz = dom->containerSize();
    float w = sz.width();
    float h = sz.height();
    // viewBox-only documents (no width/height attrs) report a 0 container size;
    // fall back to the viewBox extent so the intrinsic aspect ratio is correct.
    if (w <= 0.0f || h <= 0.0f) {
        if (SkSVGSVG* root = dom->getRoot()) {
            const std::optional<SkRect>& vb = root->getViewBox();
            if (vb.has_value() && vb->width() > 0 && vb->height() > 0) {
                w = vb->width();
                h = vb->height();
            }
        }
    }
    if (w <= 0.0f || h <= 0.0f) { w = 100.0f; h = 100.0f; }
    return makeSvgHandle(std::move(dom), w, h);
#else
    (void)utf8; (void)length;
    return 0;
#endif
}

OPENJFX_API int32_t openjfx_skia_svg_get_size(uintptr_t svgHandle, float* outWidthHeight) {
#if defined(OPENJFX_WITH_SKIA) && defined(OPENJFX_WITH_SKIA_SVG)
    SvgHandle* h = asSvg(svgHandle); // null on a stale/freed handle — guarded
    if (!h || !outWidthHeight) return -1;
    outWidthHeight[0] = h->intrinsicW;
    outWidthHeight[1] = h->intrinsicH;
    return 0;
#else
    (void)svgHandle; (void)outWidthHeight;
    return -1;
#endif
}

// In-place vector render: draws the SVG straight onto the target surface's
// CURRENT canvas (the scene surface), under the canvas's CURRENT matrix +
// clip — i.e. the live device transform the caller already applied. Because
// the SVG is drawn as vectors under that transform (rather than rasterized to
// an intermediate texture and blitted), it is pixel-perfect and crystal-clear
// at ANY zoom/DPI, with no resampling and no texture-size cap. The content is
// clipped to the node box (x,y,w,h) so a zoomed/oversized SVG never overflows.
// Composites in the fixed order background -> grid -> SVG -> optional tint.
OPENJFX_API int32_t openjfx_skia_svg_render_in_place(
        uintptr_t surfaceHandle, uintptr_t svgHandle,
        float x, float y, float w, float h,
        int32_t bgArgb, int32_t tintArgb, int32_t tintMode,
        int32_t gridArgb, float gridCell, float gridLineWidth) {
#if defined(OPENJFX_WITH_SKIA) && defined(OPENJFX_WITH_SKIA_SVG)
    SkCanvas* canvas = asCanvas(surfaceHandle);
    SvgHandle* hh = asSvg(svgHandle);
    // !(w > 0) also rejects NaN (all NaN comparisons are false).
    if (!canvas || !hh || !hh->dom || !(w > 0.0f) || !(h > 0.0f)) return -1;

    // Hold our own reference for the duration of the render so the document
    // can't be freed out from under us mid-render (defense in depth; the Java
    // side also defers all frees to this thread).
    sk_sp<SkSVGDOM> dom = hh->dom;
    float iw = hh->intrinsicW > 0.0f ? hh->intrinsicW : w;
    float ih = hh->intrinsicH > 0.0f ? hh->intrinsicH : h;

    canvas->save();
    canvas->translate(x, y);
    // Clip to the node's logical box so SVG content (or the grid) can never
    // bleed outside the node — fixes "zoom overflows the SVG". Hard (non-AA)
    // clip: the box is the node's device-aligned bounds, so an AA clip would
    // only leave a 1px translucent seam at the edges.
    canvas->clipRect(SkRect::MakeWH(w, h), /*doAntiAlias*/ false);

    SkColor bg = static_cast<SkColor>(bgArgb);
    if (SkColorGetA(bg) != 0) {
        SkPaint p;
        p.setColor(bg);
        canvas->drawRect(SkRect::MakeWH(w, h), p);
    }

    // Render the WHOLE document and scale it to fit the box. We lay the DOM out
    // at its intrinsic size and scale the canvas by box/intrinsic, rather than
    // relying on setContainerSize(box): Skia only honors the container size for
    // percentage-sized SVG roots — a root with absolute width/height (e.g.
    // width="960") ignores it and renders at its native size, which would then
    // be clipped to the box (the document appears cropped). Scaling the canvas
    // works for both, and stays vector-sharp at any zoom.
    dom->setContainerSize(SkSize::Make(iw, ih));
    canvas->save();
    canvas->scale(w / iw, h / ih);   // fit the full document into the node box

    if (tintMode == 0 || SkColorGetA(static_cast<SkColor>(tintArgb)) == 0) {
        dom->render(canvas);
    } else {
        SkPaint lp;
        SkBlendMode mode = (tintMode == 2) ? SkBlendMode::kMultiply
                                           : SkBlendMode::kSrcIn;
        lp.setColorFilter(SkColorFilters::Blend(static_cast<SkColor>(tintArgb), mode));
        canvas->saveLayer(nullptr, &lp);
        dom->render(canvas);
        canvas->restore();
    }

    canvas->restore();   // undo fit scale

    // Grid drawn ON TOP of the SVG (in node-box coords), so it's visible over
    // any artwork — opaque or not — like a measurement/inspector overlay.
    // gridCell/gridLineWidth are in the node's logical px (already scaled for
    // zoom by the caller); the canvas matrix maps them to device pixels.
    // Cap the line count so a pathologically small spacing (e.g. from CSS)
    // can't spin the render thread drawing millions of lines.
    static constexpr float kMaxGridLines = 4096.0f;
    if (SkColorGetA(static_cast<SkColor>(gridArgb)) != 0 && gridCell > 0.01f
            && w / gridCell <= kMaxGridLines && h / gridCell <= kMaxGridLines) {
        SkPaint gp;
        gp.setAntiAlias(true);
        gp.setStyle(SkPaint::kStroke_Style);
        gp.setStrokeWidth(gridLineWidth > 0.0f ? gridLineWidth : 1.0f);
        gp.setColor(static_cast<SkColor>(gridArgb));
        for (float gx = 0.0f; gx <= w; gx += gridCell) canvas->drawLine(gx, 0.0f, gx, h, gp);
        for (float gy = 0.0f; gy <= h; gy += gridCell) canvas->drawLine(0.0f, gy, w, gy, gp);
    }

    canvas->restore();   // undo translate/clip
    return 0;
#else
    (void)surfaceHandle; (void)svgHandle; (void)x; (void)y; (void)w; (void)h;
    (void)bgArgb; (void)tintArgb; (void)tintMode;
    (void)gridArgb; (void)gridCell; (void)gridLineWidth;
    return -1;
#endif
}

OPENJFX_API void openjfx_skia_svg_destroy(uintptr_t svgHandle) {
    if (!svgHandle) return;
#if defined(OPENJFX_WITH_SKIA) && defined(OPENJFX_WITH_SKIA_SVG)
    freeSvgHandle(svgHandle); // poisons the magic so any later use is rejected
#endif
}

OPENJFX_API void openjfx_skia_filter_destroy(uintptr_t handle) {
    if (!handle) return;
#ifdef OPENJFX_WITH_SKIA
    freeFilterHandle(handle);
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_save_layer_with_filter(
    uintptr_t handle, uintptr_t filterHandle) {
#ifdef OPENJFX_WITH_SKIA
    OpenJfxSurface* st = asState(handle);
    SkCanvas* canvas = asCanvas(handle);
    SkImageFilter* f = asFilter(filterHandle);
    if (!st || !canvas || !f) return -1;
    SkPaint paint;
    paint.setImageFilter(sk_ref_sp(f));
    SkCanvas::SaveLayerRec rec(nullptr, &paint, 0);
    // Must mirror surface_save / surface_save_layer_alpha and bump the bridge
    // save count: the effect peers (SkiaDropShadowPeer, SkiaColorMatrixPeerBase,
    // SkiaLinearConvolvePeer, …) pop this layer with surface_restore, which
    // decrements bridgeSaveCount and REFUSES at 0. Without the matching
    // increment every effect (CSS drop-shadow, blur, color-matrix, …) drove the
    // count negative — "surface_restore underflow refused" — and, because the
    // refused restore skips canvas->restore(), leaked the saveLayer, corrupting
    // the canvas state and growing GPU memory frame over frame.
    st->bridgeSaveCount++;
    canvas->saveLayer(rec);
    return 0;
#else
    (void)handle; (void)filterHandle;
    return -1;
#endif
}

// ===========================================================================
// SkShader lifecycle
// ===========================================================================

OPENJFX_API uintptr_t openjfx_skia_shader_create_linear_gradient(
    float x0, float y0, float x1, float y1,
    int32_t nStops,
    const float* positions,
    const uint32_t* colorsRGBA,
    int32_t tileMode) {
#ifdef OPENJFX_WITH_SKIA
    if (nStops < 2 || !positions || !colorsRGBA) return 0;
    SkPoint pts[2] = { {x0, y0}, {x1, y1} };
    auto colors = decodeColors(colorsRGBA, nStops);
    SkGradient::Colors gc(SkSpan<const SkColor4f>(colors.data(), nStops),
                          SkSpan<const float>(positions, nStops),
                          mapTileMode(tileMode));
    SkGradient grad(gc, SkGradient::Interpolation{});
    sk_sp<SkShader> shader = SkShaders::LinearGradient(pts, grad);
    if (!shader) return 0;
    return makeShaderHandle(std::move(shader));
#else
    (void)x0; (void)y0; (void)x1; (void)y1;
    (void)nStops; (void)positions; (void)colorsRGBA; (void)tileMode;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_shader_create_radial_gradient(
    float cx, float cy, float radius,
    int32_t nStops,
    const float* positions,
    const uint32_t* colorsRGBA,
    int32_t tileMode) {
#ifdef OPENJFX_WITH_SKIA
    if (nStops < 2 || !positions || !colorsRGBA || radius <= 0.0f) return 0;
    auto colors = decodeColors(colorsRGBA, nStops);
    SkGradient::Colors gc(SkSpan<const SkColor4f>(colors.data(), nStops),
                          SkSpan<const float>(positions, nStops),
                          mapTileMode(tileMode));
    SkGradient grad(gc, SkGradient::Interpolation{});
    sk_sp<SkShader> shader = SkShaders::RadialGradient(SkPoint{cx, cy}, radius, grad);
    if (!shader) return 0;
    return makeShaderHandle(std::move(shader));
#else
    (void)cx; (void)cy; (void)radius;
    (void)nStops; (void)positions; (void)colorsRGBA; (void)tileMode;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_shader_create_image(
    uintptr_t imageHandle,
    int32_t tileModeX, int32_t tileModeY) {
#ifdef OPENJFX_WITH_SKIA
    SkImage* img = asImage(imageHandle);
    if (!img) return 0;
    sk_sp<SkShader> shader = img->makeShader(
        mapTileMode(tileModeX), mapTileMode(tileModeY),
        SkSamplingOptions(SkFilterMode::kLinear));
    if (!shader) return 0;
    return makeShaderHandle(std::move(shader));
#else
    (void)imageHandle; (void)tileModeX; (void)tileModeY;
    return 0;
#endif
}

OPENJFX_API void openjfx_skia_shader_destroy(uintptr_t shaderHandle) {
    if (!shaderHandle) return;
#ifdef OPENJFX_WITH_SKIA
    freeShaderHandle(shaderHandle);
#endif
}

// ---- Shader-create variants with explicit SkShader local matrix ----------
// The (m00..m12) args use the same setAll convention as begin_draw, so
// callers can pass an AffineTransform straight through (a, c, e, b, d, f
// → m00, m01, m02, m10, m11, m12). When the caller has no transform they
// should pass identity (1,0,0, 0,1,0); using the non-_lm variants is
// equivalent in that case.

OPENJFX_API uintptr_t openjfx_skia_shader_create_linear_gradient_lm(
    float x0, float y0, float x1, float y1,
    int32_t nStops,
    const float* positions,
    const uint32_t* colorsRGBA,
    int32_t tileMode,
    float m00, float m01, float m02,
    float m10, float m11, float m12) {
#ifdef OPENJFX_WITH_SKIA
    if (nStops < 2 || !positions || !colorsRGBA) return 0;
    SkPoint pts[2] = { {x0, y0}, {x1, y1} };
    auto colors = decodeColors(colorsRGBA, nStops);
    SkGradient::Colors gc(SkSpan<const SkColor4f>(colors.data(), nStops),
                          SkSpan<const float>(positions, nStops),
                          mapTileMode(tileMode));
    SkGradient grad(gc, SkGradient::Interpolation{});
    SkMatrix lm;
    lm.setAll(m00, m01, m02, m10, m11, m12, 0, 0, 1);
    sk_sp<SkShader> shader = SkShaders::LinearGradient(pts, grad, &lm);
    uintptr_t result = shader
        ? makeShaderHandle(std::move(shader))
        : 0;
    // TEMP DIAG (env-gated): so we see whether the cards' shader is
    // built successfully or returns 0.
    static const bool diagEnabled = []() {
        const char* e = std::getenv("OPENJFX_SKIA_DRAW_DIAG");
        return e && *e && *e != '0';
    }();
    if (diagEnabled) {
        static std::atomic<int> n { 0 };
        int v = n.fetch_add(1);
        if (v < 200) {
            std::fprintf(stderr,
                "[skia.lg]  #%d p0=(%.1f,%.1f) p1=(%.1f,%.1f) "
                "nStops=%d c0=%08x c%d=%08x tile=%d "
                "lm=[%.2f %.2f %.0f / %.2f %.2f %.0f] -> %p\n",
                v, x0, y0, x1, y1, nStops, colorsRGBA[0],
                nStops - 1, colorsRGBA[nStops - 1], tileMode,
                m00, m01, m02, m10, m11, m12, (void*)result);
        }
    }
    return result;
#else
    (void)x0; (void)y0; (void)x1; (void)y1;
    (void)nStops; (void)positions; (void)colorsRGBA; (void)tileMode;
    (void)m00; (void)m01; (void)m02; (void)m10; (void)m11; (void)m12;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_shader_create_radial_gradient_lm(
    float cx, float cy, float radius,
    int32_t nStops,
    const float* positions,
    const uint32_t* colorsRGBA,
    int32_t tileMode,
    float m00, float m01, float m02,
    float m10, float m11, float m12) {
#ifdef OPENJFX_WITH_SKIA
    if (nStops < 2 || !positions || !colorsRGBA || radius <= 0.0f) return 0;
    auto colors = decodeColors(colorsRGBA, nStops);
    SkGradient::Colors gc(SkSpan<const SkColor4f>(colors.data(), nStops),
                          SkSpan<const float>(positions, nStops),
                          mapTileMode(tileMode));
    SkGradient grad(gc, SkGradient::Interpolation{});
    SkMatrix lm;
    lm.setAll(m00, m01, m02, m10, m11, m12, 0, 0, 1);
    sk_sp<SkShader> shader = SkShaders::RadialGradient(
        SkPoint{cx, cy}, radius, grad, &lm);
    if (!shader) return 0;
    return makeShaderHandle(std::move(shader));
#else
    (void)cx; (void)cy; (void)radius;
    (void)nStops; (void)positions; (void)colorsRGBA; (void)tileMode;
    (void)m00; (void)m01; (void)m02; (void)m10; (void)m11; (void)m12;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_shader_create_image_lm(
    uintptr_t imageHandle,
    int32_t tileModeX, int32_t tileModeY,
    float m00, float m01, float m02,
    float m10, float m11, float m12) {
#ifdef OPENJFX_WITH_SKIA
    SkImage* img = asImage(imageHandle);
    if (!img) return 0;
    SkMatrix lm;
    lm.setAll(m00, m01, m02, m10, m11, m12, 0, 0, 1);
    sk_sp<SkShader> shader = img->makeShader(
        mapTileMode(tileModeX), mapTileMode(tileModeY),
        SkSamplingOptions(SkFilterMode::kLinear),
        &lm);
    if (!shader) return 0;
    return makeShaderHandle(std::move(shader));
#else
    (void)imageHandle; (void)tileModeX; (void)tileModeY;
    (void)m00; (void)m01; (void)m02; (void)m10; (void)m11; (void)m12;
    return 0;
#endif
}

// ---- Fill-with-shader operations ------------------------------------------

OPENJFX_API int32_t openjfx_skia_surface_fill_rect_shader(
    uintptr_t handle,
    float x, float y, float w, float h,
    uintptr_t shaderHandle, uint8_t alpha) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    SkShader* shader = asShader(shaderHandle);
    // TEMP DIAG (env-gated). OPENJFX_SKIA_DRAW_DIAG=1 to enable.
    static const bool diagEnabled = []() {
        const char* e = std::getenv("OPENJFX_SKIA_DRAW_DIAG");
        return e && *e && *e != '0';
    }();
    if (diagEnabled && canvas) {
        static std::atomic<int> n { 0 };
        int v = n.fetch_add(1);
        if (v < 200) {
            SkRect clip = canvas->getLocalClipBounds();
            SkMatrix m = canvas->getTotalMatrix();
            std::fprintf(stderr,
                "[skia.frs] #%d canv=%p shdr=%p rect=(%.0f,%.0f %.0fx%.0f) "
                "alpha=%u clipLocal=(%.0f,%.0f %.0fx%.0f) "
                "m=[%.2f %.2f %.0f / %.2f %.2f %.0f]\n",
                v, (void*)canvas, (void*)shader, x, y, w, h, alpha,
                clip.x(), clip.y(), clip.width(), clip.height(),
                m.getScaleX(), m.getSkewX(), m.getTranslateX(),
                m.getSkewY(), m.getScaleY(), m.getTranslateY());
        }
    }
    if (!canvas || !shader) return 1;
    SkPaint paint;
    configureFillShader(paint, *asState(handle), shader, alpha);
    canvas->drawRect(SkRect::MakeXYWH(x, y, w, h), paint);
    return 0;
#else
    (void)handle; (void)x; (void)y; (void)w; (void)h;
    (void)shaderHandle; (void)alpha;
    return -1;
#endif
}


OPENJFX_API int32_t openjfx_skia_surface_fill_round_rect_shader(
    uintptr_t handle,
    float x, float y, float w, float h,
    float arcW, float arcH,
    uintptr_t shaderHandle, uint8_t alpha) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    SkShader* shader = asShader(shaderHandle);
    if (!canvas || !shader) return 1;
    SkPaint paint;
    configureFillShader(paint, *asState(handle), shader, alpha);
    canvas->drawRoundRect(
        SkRect::MakeXYWH(x, y, w, h), arcW * 0.5f, arcH * 0.5f, paint);
    return 0;
#else
    (void)handle; (void)x; (void)y; (void)w; (void)h;
    (void)arcW; (void)arcH; (void)shaderHandle; (void)alpha;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_fill_oval_shader(
    uintptr_t handle,
    float x, float y, float w, float h,
    uintptr_t shaderHandle, uint8_t alpha) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    SkShader* shader = asShader(shaderHandle);
    if (!canvas || !shader) return 1;
    SkPaint paint;
    configureFillShader(paint, *asState(handle), shader, alpha);
    canvas->drawOval(SkRect::MakeXYWH(x, y, w, h), paint);
    return 0;
#else
    (void)handle; (void)x; (void)y; (void)w; (void)h;
    (void)shaderHandle; (void)alpha;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_fill_path_shader(
    uintptr_t handle,
    const uint8_t* verbs,  int32_t verbCount,
    const float*   coords, int32_t coordCount,
    int32_t fillRule,
    uintptr_t shaderHandle, uint8_t alpha) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    SkShader* shader = asShader(shaderHandle);
    if (!canvas || !shader) return 1;
    SkPath path;
    if (!buildPath(path, verbs, verbCount, coords, coordCount, fillRule)) return 2;
    SkPaint paint;
    configureFillShader(paint, *asState(handle), shader, alpha);
    canvas->drawPath(path, paint);
    return 0;
#else
    (void)handle; (void)verbs; (void)verbCount; (void)coords; (void)coordCount;
    (void)fillRule; (void)shaderHandle; (void)alpha;
    return -1;
#endif
}

// ---- Stroke-with-shader operations ----------------------------------------

OPENJFX_API int32_t openjfx_skia_surface_stroke_rect_shader(
    uintptr_t handle,
    float x, float y, float w, float h,
    float width, int32_t cap, int32_t join, float miter,
    uintptr_t shaderHandle, uint8_t alpha) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    SkShader* sh = asShader(shaderHandle);
    if (!canvas || !sh) return 1;
    SkPaint paint;
    configureStrokeShader(paint, *asState(handle), sh, alpha, width, cap, join, miter);
    canvas->drawRect(SkRect::MakeXYWH(x, y, w, h), paint);
    return 0;
#else
    (void)handle; (void)x; (void)y; (void)w; (void)h;
    (void)width; (void)cap; (void)join; (void)miter;
    (void)shaderHandle; (void)alpha;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_stroke_round_rect_shader(
    uintptr_t handle,
    float x, float y, float w, float h,
    float arcW, float arcH,
    float width, int32_t cap, int32_t join, float miter,
    uintptr_t shaderHandle, uint8_t alpha) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    SkShader* sh = asShader(shaderHandle);
    if (!canvas || !sh) return 1;
    SkPaint paint;
    configureStrokeShader(paint, *asState(handle), sh, alpha, width, cap, join, miter);
    canvas->drawRoundRect(
        SkRect::MakeXYWH(x, y, w, h), arcW * 0.5f, arcH * 0.5f, paint);
    return 0;
#else
    (void)handle; (void)x; (void)y; (void)w; (void)h;
    (void)arcW; (void)arcH;
    (void)width; (void)cap; (void)join; (void)miter;
    (void)shaderHandle; (void)alpha;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_stroke_oval_shader(
    uintptr_t handle,
    float x, float y, float w, float h,
    float width, int32_t cap, int32_t join, float miter,
    uintptr_t shaderHandle, uint8_t alpha) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    SkShader* sh = asShader(shaderHandle);
    if (!canvas || !sh) return 1;
    SkPaint paint;
    configureStrokeShader(paint, *asState(handle), sh, alpha, width, cap, join, miter);
    canvas->drawOval(SkRect::MakeXYWH(x, y, w, h), paint);
    return 0;
#else
    (void)handle; (void)x; (void)y; (void)w; (void)h;
    (void)width; (void)cap; (void)join; (void)miter;
    (void)shaderHandle; (void)alpha;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_stroke_line_shader(
    uintptr_t handle,
    float x1, float y1, float x2, float y2,
    float width, int32_t cap, int32_t join, float miter,
    uintptr_t shaderHandle, uint8_t alpha) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    SkShader* sh = asShader(shaderHandle);
    if (!canvas || !sh) return 1;
    SkPaint paint;
    configureStrokeShader(paint, *asState(handle), sh, alpha, width, cap, join, miter);
    canvas->drawLine(x1, y1, x2, y2, paint);
    return 0;
#else
    (void)handle; (void)x1; (void)y1; (void)x2; (void)y2;
    (void)width; (void)cap; (void)join; (void)miter;
    (void)shaderHandle; (void)alpha;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_stroke_path_shader(
    uintptr_t handle,
    const uint8_t* verbs,  int32_t verbCount,
    const float*   coords, int32_t coordCount,
    int32_t fillRule,
    float width, int32_t cap, int32_t join, float miter,
    uintptr_t shaderHandle, uint8_t alpha) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    SkShader* sh = asShader(shaderHandle);
    if (!canvas || !sh) return 1;
    SkPath path;
    if (!buildPath(path, verbs, verbCount, coords, coordCount, fillRule)) return 2;
    SkPaint paint;
    configureStrokeShader(paint, *asState(handle), sh, alpha, width, cap, join, miter);
    canvas->drawPath(path, paint);
    return 0;
#else
    (void)handle; (void)verbs; (void)verbCount; (void)coords; (void)coordCount;
    (void)fillRule; (void)width; (void)cap; (void)join; (void)miter;
    (void)shaderHandle; (void)alpha;
    return -1;
#endif
}

// ===========================================================================
// SkImage lifecycle
// ===========================================================================

OPENJFX_API uintptr_t openjfx_skia_image_create_raster(
    int32_t width, int32_t height,
    int32_t rowBytes,
    const void* pixels,
    int32_t colorType) {
#ifdef OPENJFX_WITH_SKIA
    if (width <= 0 || height <= 0 || pixels == nullptr) return 0;
    SkColorType ct = mapColorType(colorType);
    // Alpha type depends on color type:
    //   gray + RGB-888x are opaque (no alpha channel),
    //   alpha-8 stores alpha values (premul/unpremul both fine; pick premul
    //   so masks compose correctly with srcOver/dstIn etc.),
    //   the 8888 variants are premultiplied.
    SkAlphaType at;
    switch (ct) {
        case kGray_8_SkColorType:
        case kRGB_888x_SkColorType:
            at = kOpaque_SkAlphaType;
            break;
        default:
            at = kPremul_SkAlphaType;
            break;
    }
    SkImageInfo info = SkImageInfo::Make(width, height, ct, at);
    SkPixmap pm(info, pixels, static_cast<size_t>(rowBytes));
    sk_sp<SkImage> img = SkImages::RasterFromPixmapCopy(pm);
    if (!img) return 0;
    return makeImageHandle(std::move(img));
#else
    (void)width; (void)height; (void)rowBytes; (void)pixels; (void)colorType;
    return 0;
#endif
}

// ---------------------------------------------------------------------------
// I420 / planar YUV upload — real Skia YUV path.
//
// Build an SkYUVAPixmaps from the caller's Y/U/V plane pointers, hand
// to SkImages::TextureFromYUVAPixmaps (GPU/Ganesh) or
// RasterFromYUVAPixmaps (CPU). Skia applies the YUV→RGB matrix at
// SAMPLE time using SkYUVAInfo::YUVColorSpace, so we don't compute
// the conversion ourselves — that's what produced the amber tint in
// the earlier hand-rolled CPU-matrix version: a BT.709-limited matrix
// applied to BT.2020 / full-range content shifts the chroma
// projection just enough to bias warm tones (skin, sky) toward
// orange while leaving primaries roughly right.
//
// `colorSpace` parameter mapping (matches NativeBridge.YUV_CS_* on
// the Java side):
//   0 = BT.601 limited (SDR / legacy DV / old DVD)
//   1 = BT.709 limited (HD broadcast — most common in HD video)
//   2 = BT.2020 limited (UHD / HDR content — most common in 4K AV1)
//   3 = JPEG / BT.601 full range (still images, web video, AV1
//        encoded with color_range=1)
// Anything else → BT.709 limited fallback.
//
// The plane pointers are non-owning. SkYUVAPixmaps wraps them in
// SkPixmaps without copying, and RasterFromYUVAPixmaps /
// TextureFromYUVAPixmaps internally copies the data into Skia-
// managed storage before returning, so the caller can release the
// source buffers as soon as this returns.
// ---------------------------------------------------------------------------
OPENJFX_API uintptr_t openjfx_skia_image_create_yuv_i420(
    const void* yPlane,  int32_t yStride,
    const void* uPlane,  int32_t uStride,
    const void* vPlane,  int32_t vStride,
    int32_t width, int32_t height,
    int32_t colorSpace) {
#ifdef OPENJFX_WITH_SKIA
    // Strides must cover the actual plane width, not merely be positive: a
    // demuxer that pads or lies about stride would make Skia sample rows the
    // Java copy never populated (OOB native read / stale prior-frame pixels).
    // Chroma is 4:2:0 → (width+1)/2 samples per row.
    const int32_t chromaW_i420 = (width + 1) / 2;
    if (width <= 0 || height <= 0
        || yPlane == nullptr || uPlane == nullptr || vPlane == nullptr
        || yStride < width || uStride < chromaW_i420 || vStride < chromaW_i420) {
        return 0;
    }

    SkYUVColorSpace yuvCs;
    switch (colorSpace) {
        case 0:  yuvCs = kRec601_Limited_SkYUVColorSpace;  break;
        case 1:  yuvCs = kRec709_Limited_SkYUVColorSpace;  break;
        case 2:  yuvCs = kBT2020_8bit_Limited_SkYUVColorSpace; break;
        case 3:  yuvCs = kJPEG_Full_SkYUVColorSpace;       break;
        default: yuvCs = kRec709_Limited_SkYUVColorSpace;  break;
    }

    // I420 plane layout: Y full size, then U at width/2 × height/2,
    // then V at width/2 × height/2. SkYUVAInfo::PlaneConfig::kY_U_V
    // describes exactly this order; Subsampling::k420 sets the 2×2
    // chroma decimation.
    SkYUVAInfo yuvaInfo(
        { width, height },
        SkYUVAInfo::PlaneConfig::kY_U_V,
        SkYUVAInfo::Subsampling::k420,
        yuvCs);

    // Build SkPixmaps that view the caller's buffers directly. No
    // copy here — the data is fetched on the SkImages::*FromYUVA
    // call below.
    SkImageInfo yInfo = SkImageInfo::Make(
        width, height, kAlpha_8_SkColorType, kPremul_SkAlphaType);
    SkImageInfo cInfo = SkImageInfo::Make(
        (width + 1) / 2, (height + 1) / 2,
        kAlpha_8_SkColorType, kPremul_SkAlphaType);

    SkPixmap planes[SkYUVAInfo::kMaxPlanes];
    planes[0] = SkPixmap(yInfo, yPlane, static_cast<size_t>(yStride));
    planes[1] = SkPixmap(cInfo, uPlane, static_cast<size_t>(uStride));
    planes[2] = SkPixmap(cInfo, vPlane, static_cast<size_t>(vStride));

    SkYUVAPixmaps yuvaPixmaps = SkYUVAPixmaps::FromExternalPixmaps(yuvaInfo, planes);
    if (!yuvaPixmaps.isValid()) return 0;

    // Tag the destination color space as sRGB so Skia treats the
    // YUV→RGB output as gamma-encoded sRGB — matches what every
    // standard video codec emits and what the window framebuffer
    // expects. Using nullptr (untagged) would make Skia pick the
    // destination canvas's color space at draw time, which is the
    // same thing for our pipeline today but explicit is clearer.
    sk_sp<SkColorSpace> dstCs = SkColorSpace::MakeSRGB();

    // Skia's public YUV image API in m126 is the Ganesh one —
    // SkImages::TextureFromYUVAPixmaps. There is no public
    // RasterFromYUVAPixmaps. When Ganesh isn't up (CPU-only profile),
    // we return 0 here and let the Java caller fall through to the
    // BGRA-via-GStreamer-videoconvert path. gpuDirectContext()
    // lazily builds the per-process GrDirectContext; it returns an
    // empty sk_sp when GPU init failed.
    const sk_sp<GrDirectContext>& grCtx = gpuDirectContext();
    if (!grCtx) return 0;

    sk_sp<SkImage> img = SkImages::TextureFromYUVAPixmaps(
        grCtx.get(),
        yuvaPixmaps,
        skgpu::Mipmapped::kNo,
        /*limitToMaxTextureSize=*/false,
        dstCs);
    if (!img) return 0;
    return makeImageHandle(std::move(img));
#else
    (void)yPlane; (void)yStride; (void)uPlane; (void)uStride;
    (void)vPlane; (void)vStride; (void)width; (void)height; (void)colorSpace;
    return 0;
#endif
}

// ---------------------------------------------------------------------------
// HDR-aware YUV upload + present-time tone mapping.
//
// Two stages, both on the GPU when Ganesh is up:
//
//   1. YUV → linear-light RGB in the *source* gamut.
//      Skia's SkImages::TextureFromYUVAPixmaps applies:
//        a) the chosen YUV matrix (BT.601/709/2020/JPEG-full) to go from
//           Y'CbCr to non-linear R'G'B';
//        b) the source SkColorSpace's transfer function (sRGB / Rec.709
//           OETF for SDR, PQ EOTF for HDR10, HLG OETF for HLG) to go from
//           non-linear R'G'B' to linear nits-encoded RGB;
//        c) the source gamut → destination gamut matrix conversion.
//      We tag the source SkColorSpace with the file's (transfer, gamut)
//      and the destination with sRGB. Skia's colour engine handles (a),
//      (b), (c) automatically.
//
//   2. For HDR sources only, run a one-shot BT.2390 perceptual tone-
//      map pass via SkRuntimeEffect, mapping (source-peak-nits) linear
//      values into the [0,1] SDR range. The result is then encoded with
//      the sRGB OETF when drawn to the framebuffer.
//
// For SDR inputs (transferFn == SRGB or REC709) the tone-map pass is
// skipped — the YUV-uploaded SkImage already represents linear sRGB and
// can be drawn directly. Same path as the legacy image_create_yuv_i420.
//
// The returned SkImage is always sRGB-tagged. Callers downstream never
// need to know whether the source was HDR.
//
// Returns 0 on failure (no Ganesh, plane args invalid, shader compile
// failure). Java falls back to the CPU tone-mapper on 0.
// ---------------------------------------------------------------------------
#ifdef OPENJFX_WITH_SKIA

// Pick the source SkColorSpace (transfer function + gamut) for a given
// (transferFn, primaries) pair. Returns nullptr if the combination is
// invalid (defensive — callers pass enum values, not raw ints from
// untrusted input).
static sk_sp<SkColorSpace> pickSourceColorSpace(int32_t transferFn,
                                                int32_t primaries) {
    skcms_TransferFunction tfn;
    switch (transferFn) {
        case OPENJFX_SKIA_TFN_SRGB:
            tfn = SkNamedTransferFn::kSRGB;
            break;
        case OPENJFX_SKIA_TFN_REC709:
            // BT.709 OETF — Skia exposes kRec2020 which uses the
            // identical curve as Rec.709 (the gamut differs but the
            // transfer is the same: linear segment + 0.45-gamma).
            tfn = SkNamedTransferFn::kRec2020;
            break;
        case OPENJFX_SKIA_TFN_PQ:
            tfn = SkNamedTransferFn::kPQ;
            break;
        case OPENJFX_SKIA_TFN_HLG:
            tfn = SkNamedTransferFn::kHLG;
            break;
        case OPENJFX_SKIA_TFN_LINEAR:
            tfn = SkNamedTransferFn::kLinear;
            break;
        default:
            return nullptr;
    }

    skcms_Matrix3x3 gamut;
    switch (primaries) {
        case OPENJFX_SKIA_PRI_SRGB:
            gamut = SkNamedGamut::kSRGB;
            break;
        case OPENJFX_SKIA_PRI_REC2020:
            gamut = SkNamedGamut::kRec2020;
            break;
        case OPENJFX_SKIA_PRI_DCI_P3:
            gamut = SkNamedGamut::kDisplayP3;
            break;
        case OPENJFX_SKIA_PRI_REC601:
            // No named Rec.601 gamut in skcms — Rec.709 is close enough
            // for the SD content this path serves (and SD content is
            // rarely tagged with primaries anyway).
            gamut = SkNamedGamut::kSRGB;
            break;
        default:
            return nullptr;
    }
    return SkColorSpace::MakeRGB(tfn, gamut);
}

// SkSL source for the BT.2390 perceptual tone curve. Input is linear
// extended-range RGB in the *destination* gamut (already converted from
// the source gamut by Skia's colour engine when sampling). Output is
// linear [0,1] RGB in the destination gamut, suitable for sRGB OETF.
//
// Algorithm: ITU-R BT.2390-9 Annex 2 EETF — preserves perceptual
// quality of mid-tones and rolls off highlights smoothly. Constants
// are computed on the CPU side and passed as uniforms (Hermite spline
// knee point + scale).
//
// The shader is per-channel rather than luminance-based; this avoids
// hue shifts that BT.2390 luma-based variant produces on saturated
// HDR highlights (a separate algorithmic choice — luma-based is
// optional in BT.2390-9).
static constexpr char kBt2390ToneMapSkSL[] = R"SKSL(
uniform shader  src;
uniform float   srcMaxLin;   // source peak in linear nits / 10000
uniform float   dstMaxLin;   // destination peak in linear nits / 10000
uniform float   kneeStart;   // pre-computed Hermite knee start (in [0,1])
uniform float   kneeRange;   // 1.0 - kneeStart

half3 bt2390(half3 x) {
    // x is in [0, srcMaxLin]; rescale to [0, 1] then apply Hermite EETF.
    half3 e1 = x / half(srcMaxLin);
    half3 e2 = e1;
    // Hermite spline rolling [kneeStart .. 1] into [kneeStart .. dstMaxLin].
    // BT.2390 uses:  T = (E1 - KS) / (1 - KS), then
    // P(T) = (2T^3 - 3T^2 + 1)*KS + (T^3 - 2T^2 + T)*(1 - KS)
    //        + (-2T^3 + 3T^2)*dstMaxLin / srcMaxLin
    half3 t  = max((e1 - half(kneeStart)) / half(kneeRange), half3(0.0));
    half3 t2 = t * t;
    half3 t3 = t2 * t;
    half3 p  = (half3(2.0) * t3 - half3(3.0) * t2 + half3(1.0)) * half(kneeStart)
             + (t3 - half3(2.0) * t2 + t) * half(kneeRange)
             + (half3(-2.0) * t3 + half3(3.0) * t2)
                 * (half(dstMaxLin) / half(srcMaxLin));
    // Below knee: linear pass-through. Above: Hermite blended value.
    half3 above = step(half(kneeStart), e1);
    e2 = mix(e1, p * half(srcMaxLin), above);
    // Scale to destination linear [0, 1].
    return e2 / half(dstMaxLin);
}

half4 main(float2 xy) {
    half4 c = src.eval(xy);
    half3 mapped = clamp(bt2390(max(c.rgb, half3(0.0))), half3(0.0), half3(1.0));
    return half4(mapped, c.a);
}
)SKSL";

// Lazy-compile + cache the BT.2390 SkRuntimeEffect. Compilation is
// cheap (microseconds on warm cache) but we still cache because we
// run this per frame on the render thread.
static sk_sp<SkRuntimeEffect> bt2390Effect() {
    static SkRuntimeEffect* sEffect = nullptr;
    static std::once_flag sOnce;
    std::call_once(sOnce, [] {
        SkRuntimeEffect::Options opts;
        auto [eff, err] = SkRuntimeEffect::MakeForShader(
            SkString(kBt2390ToneMapSkSL), opts);
        if (!eff) {
            const char* msg = err.c_str();
            fprintf(stderr,
                "[skia.hdr] BT.2390 SkRuntimeEffect compile failed: %s\n",
                msg ? msg : "(no message)");
            return;
        }
        sEffect = eff.release();
    });
    return sk_ref_sp(sEffect);
}

#endif // OPENJFX_WITH_SKIA

OPENJFX_API int32_t openjfx_skia_has_hdr_pipeline(void) {
#ifdef OPENJFX_WITH_SKIA
    return bt2390Effect() ? 1 : 0;
#else
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_image_create_yuv_hdr(
    const void* yPlane,  int32_t yStride,
    const void* uPlane,  int32_t uStride,
    const void* vPlane,  int32_t vStride,
    int32_t width, int32_t height,
    int32_t yuvMatrix,
    int32_t transferFn,
    int32_t primaries,
    int32_t fullRange,
    float   srcPeakNits,
    float   dstPeakNits) {
#ifdef OPENJFX_WITH_SKIA
    // Strides must cover the actual plane width (see image_create_yuv_i420).
    const int32_t chromaW_hdr = (width + 1) / 2;
    if (width <= 0 || height <= 0
        || yPlane == nullptr || uPlane == nullptr || vPlane == nullptr
        || yStride < width || uStride < chromaW_hdr || vStride < chromaW_hdr) {
        return 0;
    }
    (void)fullRange; // YUV matrix enums below already encode range.

    // ---- 1. YUV matrix selection (matches image_create_yuv_i420). ----
    SkYUVColorSpace yuvCs;
    switch (yuvMatrix) {
        case 0:  yuvCs = kRec601_Limited_SkYUVColorSpace;        break;
        case 1:  yuvCs = kRec709_Limited_SkYUVColorSpace;        break;
        case 2:  yuvCs = kBT2020_8bit_Limited_SkYUVColorSpace;   break;
        case 3:  yuvCs = kJPEG_Full_SkYUVColorSpace;             break;
        default: yuvCs = kRec709_Limited_SkYUVColorSpace;        break;
    }

    // ---- 2. Source colour-space tag for Skia's colour engine. ----
    sk_sp<SkColorSpace> srcCs = pickSourceColorSpace(transferFn, primaries);
    if (!srcCs) srcCs = SkColorSpace::MakeSRGB();

    sk_sp<SkColorSpace> dstCs = SkColorSpace::MakeSRGB();

    // ---- 3. Build the SkYUVAInfo / pixmap views. ----
    SkYUVAInfo yuvaInfo(
        { width, height },
        SkYUVAInfo::PlaneConfig::kY_U_V,
        SkYUVAInfo::Subsampling::k420,
        yuvCs);

    SkImageInfo yInfo = SkImageInfo::Make(
        width, height, kAlpha_8_SkColorType, kPremul_SkAlphaType);
    SkImageInfo cInfo = SkImageInfo::Make(
        (width + 1) / 2, (height + 1) / 2,
        kAlpha_8_SkColorType, kPremul_SkAlphaType);

    SkPixmap planes[SkYUVAInfo::kMaxPlanes];
    planes[0] = SkPixmap(yInfo, yPlane, static_cast<size_t>(yStride));
    planes[1] = SkPixmap(cInfo, uPlane, static_cast<size_t>(uStride));
    planes[2] = SkPixmap(cInfo, vPlane, static_cast<size_t>(vStride));

    SkYUVAPixmaps yuvaPixmaps = SkYUVAPixmaps::FromExternalPixmaps(yuvaInfo, planes);
    if (!yuvaPixmaps.isValid()) return 0;

    const sk_sp<GrDirectContext>& grCtx = gpuDirectContext();
    if (!grCtx) return 0;

    // YUV → linear-light RGB in *source* gamut, tagged with srcCs so
    // Skia later applies (transfer EOTF + gamut conversion) when
    // sampling. For HDR sources this image conceptually holds the full
    // 0..10000-nit linear-light extended-range content of the file; for
    // SDR it's just linear sRGB.
    sk_sp<SkImage> rawImg = SkImages::TextureFromYUVAPixmaps(
        grCtx.get(),
        yuvaPixmaps,
        skgpu::Mipmapped::kNo,
        /*limitToMaxTextureSize=*/false,
        srcCs);
    if (!rawImg) return 0;

    // ---- 4. SDR fast path: no tone-map needed. ----
    const bool isHdr = (transferFn == OPENJFX_SKIA_TFN_PQ
                     || transferFn == OPENJFX_SKIA_TFN_HLG);
    if (!isHdr) {
        // Re-tag as sRGB so downstream callers don't trigger a second
        // colour conversion. The pixels are already in the dstCs
        // representation (Skia made the conversion when it sampled).
        sk_sp<SkImage> finalImg = rawImg->reinterpretColorSpace(dstCs);
        if (!finalImg) finalImg = std::move(rawImg);
        return makeImageHandle(std::move(finalImg));
    }

    // ---- 5. HDR tone-map via SkRuntimeEffect (GPU). ----
    sk_sp<SkRuntimeEffect> eff = bt2390Effect();
    if (!eff) {
        // Shader compile failed (driver / Skia issue). Hand the raw
        // image back so the user sees *something* — bare PQ→sRGB
        // conversion looks dim but not amber. CPU fallback path in
        // Java handles the rest if the caller checks for HDR.
        sk_sp<SkImage> finalImg = rawImg->reinterpretColorSpace(dstCs);
        if (!finalImg) finalImg = std::move(rawImg);
        return makeImageHandle(std::move(finalImg));
    }

    // Pick peaks. Default 1000 nits for HDR10 / HLG content (the
    // overwhelming majority of HDR streams target this); 100 nits for
    // SDR sRGB output.
    float srcPeak = srcPeakNits > 0.f ? srcPeakNits
                    : (transferFn == OPENJFX_SKIA_TFN_HLG ? 1000.f : 1000.f);
    float dstPeak = dstPeakNits > 0.f ? dstPeakNits : 100.f;

    // PQ EOTF normalises 1.0 in non-linear space to 10000 nits in
    // linear. Skia produces values in [0, srcPeak/10000] after sampling
    // a PQ-tagged source. Pre-compute the knee parameters BT.2390-9
    // uses: kneeStart picks where the perceptual rolloff begins.
    const float srcMaxLin = srcPeak / 10000.f;
    const float dstMaxLin = dstPeak / 10000.f;
    // BT.2390-9 Annex 2: knee start = 1.5 * (dst/src) - 0.5
    // (clamped to keep the linear segment well-conditioned).
    float kneeStart = 1.5f * dstMaxLin / srcMaxLin - 0.5f;
    if (kneeStart < 0.05f) kneeStart = 0.05f;
    if (kneeStart > 0.95f) kneeStart = 0.95f;
    const float kneeRange = 1.0f - kneeStart;

    SkRuntimeShaderBuilder b(eff);
    SkSamplingOptions sampling(SkFilterMode::kLinear);
    b.child("src")        = rawImg->makeShader(SkTileMode::kClamp,
                                               SkTileMode::kClamp,
                                               sampling);
    b.uniform("srcMaxLin") = srcMaxLin;
    b.uniform("dstMaxLin") = dstMaxLin;
    b.uniform("kneeStart") = kneeStart;
    b.uniform("kneeRange") = kneeRange;
    sk_sp<SkShader> shader = b.makeShader();
    if (!shader) {
        sk_sp<SkImage> finalImg = rawImg->reinterpretColorSpace(dstCs);
        if (!finalImg) finalImg = std::move(rawImg);
        return makeImageHandle(std::move(finalImg));
    }

    // Intermediate sRGB surface — Skia will encode the tone-mapped
    // linear values to sRGB OETF for us when writing into this.
    SkImageInfo dstInfo = SkImageInfo::Make(
        width, height, kRGBA_8888_SkColorType, kPremul_SkAlphaType, dstCs);
    sk_sp<SkSurface> tmpSurface = SkSurfaces::RenderTarget(
        grCtx.get(), skgpu::Budgeted::kYes, dstInfo);
    if (!tmpSurface) return 0;

    SkPaint paint;
    paint.setShader(shader);
    paint.setBlendMode(SkBlendMode::kSrc);
    tmpSurface->getCanvas()->drawRect(
        SkRect::MakeWH(SkIntToScalar(width), SkIntToScalar(height)), paint);
    sk_sp<SkImage> finalImg = tmpSurface->makeImageSnapshot();
    if (!finalImg) return 0;

    return makeImageHandle(std::move(finalImg));
#else
    (void)yPlane; (void)yStride; (void)uPlane; (void)uStride;
    (void)vPlane; (void)vStride; (void)width; (void)height;
    (void)yuvMatrix; (void)transferFn; (void)primaries; (void)fullRange;
    (void)srcPeakNits; (void)dstPeakNits;
    return 0;
#endif
}

// ---------------------------------------------------------------------------
// Borrowed GL texture → SkImage (M3-B zero-copy media path).
//
// Wraps a caller-owned GL texture name (typically aliased from a D3D11
// texture via WGL_NV_DX_interop2) as an SkImage via Ganesh GL backend.
// The texture data isn't copied; Skia samples from it directly when
// drawing. No mips, kTopLeft origin (matches D3D-sourced content after
// the interop view), kRGBA_8888 color type.
// ---------------------------------------------------------------------------
OPENJFX_API uintptr_t openjfx_skia_image_create_from_gl_texture(
    uint32_t glTextureName,
    int32_t width, int32_t height) {
#ifdef OPENJFX_WITH_SKIA
    if (glTextureName == 0 || width <= 0 || height <= 0) return 0;

    const sk_sp<GrDirectContext>& grCtx = gpuDirectContext();
    if (!grCtx) return 0;

    GrGLTextureInfo glInfo;
    glInfo.fTarget = 0x0DE1;       // GL_TEXTURE_2D
    glInfo.fID     = glTextureName;
    glInfo.fFormat = 0x8058;       // GL_RGBA8 — only format supported
                                   // through WGL_NV_DX_interop2 in our
                                   // M3-B path. (NV12 isn't shareable.)

    GrBackendTexture backendTex = GrBackendTextures::MakeGL(
        width, height, skgpu::Mipmapped::kNo, glInfo);
    if (!backendTex.isValid()) return 0;

    sk_sp<SkImage> img = SkImages::BorrowTextureFrom(
        grCtx.get(),
        backendTex,
        kTopLeft_GrSurfaceOrigin,
        kRGBA_8888_SkColorType,
        kPremul_SkAlphaType,
        /*colorSpace*/ SkColorSpace::MakeSRGB(),
        /*releaseProc*/ nullptr,
        /*releaseContext*/ nullptr);
    if (!img) return 0;

    return makeImageHandle(std::move(img));
#else
    (void)glTextureName; (void)width; (void)height;
    return 0;
#endif
}

OPENJFX_API void openjfx_skia_image_destroy(uintptr_t handle) {
    if (!handle) return;
#ifdef OPENJFX_WITH_SKIA
    freeImageHandle(handle);
#endif
}

OPENJFX_API int32_t openjfx_skia_image_width(uintptr_t handle) {
#ifdef OPENJFX_WITH_SKIA
    SkImage* img = asImage(handle);
    return img ? img->width() : -1;
#else
    (void)handle;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_image_height(uintptr_t handle) {
#ifdef OPENJFX_WITH_SKIA
    SkImage* img = asImage(handle);
    return img ? img->height() : -1;
#else
    (void)handle;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_image_encode(
    uintptr_t handle,
    int32_t   format,
    int32_t   quality,
    uintptr_t* outPtr,
    int32_t*   outSize)
{
    if (outPtr)  *outPtr  = 0;
    if (outSize) *outSize = 0;
#ifdef OPENJFX_WITH_SKIA
    SkImage* img = asImage(handle);
    if (!img || !outPtr || !outSize) return -1;
    if (quality < 0)   quality = 0;
    if (quality > 100) quality = 100;

    sk_sp<SkData> data;
    switch (format) {
    case 0: { // PNG (lossless)
        SkPngEncoder::Options opts;
        data = SkPngEncoder::Encode(nullptr, img, opts);
        break;
    }
    case 1: { // JPEG
        SkJpegEncoder::Options opts;
        opts.fQuality = quality;
        data = SkJpegEncoder::Encode(nullptr, img, opts);
        break;
    }
    case 2: { // WebP
        SkWebpEncoder::Options opts;
        opts.fQuality = static_cast<float>(quality);
        opts.fCompression = SkWebpEncoder::Compression::kLossy;
        data = SkWebpEncoder::Encode(nullptr, img, opts);
        break;
    }
    default:
        return -2;
    }
    if (!data || data->size() == 0) return -3;

    const size_t n = data->size();
    void* mem = std::malloc(n);
    if (!mem) return -4;
    std::memcpy(mem, data->data(), n);
    *outPtr  = reinterpret_cast<uintptr_t>(mem);
    *outSize = static_cast<int32_t>(n);
    return 0;
#else
    (void)handle; (void)format; (void)quality;
    return -1;
#endif
}

OPENJFX_API void openjfx_skia_buffer_free(uintptr_t ptr) {
    if (!ptr) return;
    std::free(reinterpret_cast<void*>(ptr));
}

// ===========================================================================
// Drawing images
// ===========================================================================

OPENJFX_API int32_t openjfx_skia_surface_draw_image(
    uintptr_t surfaceHandle,
    uintptr_t imageHandle,
    float dx, float dy, float dw, float dh) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* cv = asCanvas(surfaceHandle);
    SkImage*  img = asImage(imageHandle);
    if (!cv || !img) return 1;
    SkRect dst = SkRect::MakeXYWH(dx, dy, dw, dh);
    SkSamplingOptions sampling(SkFilterMode::kLinear);
    const OpenJfxSurface* st = asState(surfaceHandle);
    SkPaint p;
    if (st) applyState(p, *st);
    p.setAntiAlias(false);
    cv->drawImageRect(
        img, dst, sampling,
        &p);
    return 0;
#else
    (void)surfaceHandle; (void)imageHandle;
    (void)dx; (void)dy; (void)dw; (void)dh;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_draw_surface(
    uintptr_t dstSurfaceHandle,
    uintptr_t srcSurfaceHandle,
    float sx, float sy, float sw, float sh,
    float dx, float dy, float dw, float dh) {
#ifdef OPENJFX_WITH_SKIA
    SkSurface* dst = asSurface(dstSurfaceHandle);
    SkSurface* src = asSurface(srcSurfaceHandle);
    if (!dst || !src) return 1;
    sk_sp<SkImage> img = src->makeImageSnapshot();
    if (!img) return 2;
    SkRect srcR = SkRect::MakeXYWH(sx, sy, sw, sh);
    SkRect dstR = SkRect::MakeXYWH(dx, dy, dw, dh);
    SkSamplingOptions sampling(SkFilterMode::kLinear);
    const OpenJfxSurface* st = asState(dstSurfaceHandle);
    SkPaint p;
    if (st) applyState(p, *st);
    p.setAntiAlias(false);
    dst->getCanvas()->drawImageRect(
        img, srcR, dstR, sampling, &p,
        SkCanvas::kStrict_SrcRectConstraint);
    return 0;
#else
    (void)dstSurfaceHandle; (void)srcSurfaceHandle;
    (void)sx; (void)sy; (void)sw; (void)sh;
    (void)dx; (void)dy; (void)dw; (void)dh;
    return -1;
#endif
}

// Reflection's vertical-flip + opacity-gradient surface draw — the GPU peer for
// Graphics.drawTextureVO(), used only by PrReflectionPeer. Reproduces the Prism
// reference algorithm (see J2DPrismGraphics.drawTextureVO): map the source quad
// to the (vertically inverted) destination quad to produce the mirror, and
// modulate alpha with a vertical white gradient — topOpacity at dy1, botOpacity
// at dy2. Done with a kSrc gradient fill followed by a kSrcIn flipped image so
// the result is the faded mirror; the reflection buffer is cleared transparent
// and its band never overlaps the original, exactly as the reference relies on.
OPENJFX_API int32_t openjfx_skia_surface_draw_surface_vo(
    uintptr_t dstSurfaceHandle,
    uintptr_t srcSurfaceHandle,
    float dx1, float dy1, float dx2, float dy2,
    float sx1, float sy1, float sx2, float sy2,
    float topOpacity, float botOpacity) {
#ifdef OPENJFX_WITH_SKIA
    SkSurface* dst = asSurface(dstSurfaceHandle);
    SkSurface* src = asSurface(srcSurfaceHandle);
    if (!dst || !src) return 1;
    sk_sp<SkImage> img = src->makeImageSnapshot();
    if (!img) return 2;
    SkCanvas* cv = dst->getCanvas();

    const float xL = std::min(dx1, dx2);
    const float xR = std::max(dx1, dx2);
    const float yT = std::min(dy1, dy2);
    const float yB = std::max(dy1, dy2);
    SkRect dstBounds = SkRect::MakeLTRB(xL, yT, xR, yB);
    if (dstBounds.isEmpty()) return 0;

    // 1) Fill the band with a vertical white gradient whose alpha runs
    //    topOpacity @ dy1 -> botOpacity @ dy2 (kSrc replaces the transparent
    //    reflection buffer with the gradient mask).
    SkPoint gpts[2] = { {0.0f, dy1}, {0.0f, dy2} };
    SkColor4f gcolors[2] = { {1.0f, 1.0f, 1.0f, topOpacity},
                             {1.0f, 1.0f, 1.0f, botOpacity} };
    float gpos[2] = { 0.0f, 1.0f };
    SkGradient::Colors gc(SkSpan<const SkColor4f>(gcolors, 2),
                          SkSpan<const float>(gpos, 2), SkTileMode::kClamp);
    SkGradient grad(gc, SkGradient::Interpolation{});
    sk_sp<SkShader> gshader = SkShaders::LinearGradient(gpts, grad);
    if (!gshader) return 3;
    SkPaint gp;
    gp.setShader(gshader);
    gp.setBlendMode(SkBlendMode::kSrc);
    gp.setAntiAlias(false);
    cv->drawRect(dstBounds, gp);

    // 2) Draw the source, vertically flipped about the band, masked by the
    //    gradient alpha (kSrcIn): result = source * gradientAlpha. The flip
    //    (translate + scale(1,-1)) puts the source's bottom edge adjacent to
    //    the original — a true mirror.
    SkRect srcR = SkRect::MakeLTRB(std::min(sx1, sx2), std::min(sy1, sy2),
                                   std::max(sx1, sx2), std::max(sy1, sy2));
    SkPaint ip;
    ip.setBlendMode(SkBlendMode::kSrcIn);
    ip.setAntiAlias(false);
    SkSamplingOptions sampling(SkFilterMode::kLinear);
    cv->save();
    cv->translate(0.0f, yT + yB);
    cv->scale(1.0f, -1.0f);
    cv->drawImageRect(img.get(), srcR, dstBounds, sampling, &ip,
                      SkCanvas::kStrict_SrcRectConstraint);
    cv->restore();
    return 0;
#else
    (void)dstSurfaceHandle; (void)srcSurfaceHandle;
    (void)dx1; (void)dy1; (void)dx2; (void)dy2;
    (void)sx1; (void)sy1; (void)sx2; (void)sy2;
    (void)topOpacity; (void)botOpacity;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_draw_image_rect(
    uintptr_t surfaceHandle,
    uintptr_t imageHandle,
    float sx, float sy, float sw, float sh,
    float dx, float dy, float dw, float dh) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* cv = asCanvas(surfaceHandle);
    SkImage*  img = asImage(imageHandle);
    if (!cv || !img) return 1;
    SkRect src = SkRect::MakeXYWH(sx, sy, sw, sh);
    SkRect dst = SkRect::MakeXYWH(dx, dy, dw, dh);
    SkSamplingOptions sampling(SkFilterMode::kLinear);
    const OpenJfxSurface* st = asState(surfaceHandle);
    SkPaint p;
    if (st) applyState(p, *st);
    p.setAntiAlias(false);
    cv->drawImageRect(
        img, src, dst, sampling, &p,
        SkCanvas::kStrict_SrcRectConstraint);
    return 0;
#else
    (void)surfaceHandle; (void)imageHandle;
    (void)sx; (void)sy; (void)sw; (void)sh;
    (void)dx; (void)dy; (void)dw; (void)dh;
    return -1;
#endif
}

// Composite a raw BGRA8888 (premultiplied, top-down) pixel buffer straight
// onto the canvas resolved from surfaceHandle, scaling the srcW x srcH source
// into the dst rect. The bytes are wrapped without an extra heap allocation
// and copied into a Skia-managed raster image (RasterFromPixmap), so the
// caller's buffer — e.g. a shared-memory frame slot — is free to be reused the
// instant this returns. This is the in-process path for javafx.web's
// off-screen Blink frames: same SkCanvas, same GrDirectContext, one copy.
OPENJFX_API int32_t openjfx_skia_surface_draw_bgra(
    uintptr_t surfaceHandle,
    uintptr_t pixels,
    int32_t srcW, int32_t srcH, int32_t srcStride,
    int32_t dstX, int32_t dstY, int32_t dstW, int32_t dstH) {
#ifdef OPENJFX_WITH_SKIA
    if (surfaceHandle == 0 || pixels == 0 ||
        srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
        return 1;
    }
    // Reject a stride that can't hold a full BGRA row — otherwise SkPixmap /
    // RasterFromPixmap would read past the caller's buffer (same guard the
    // sibling raw-buffer entries clear_buffer / fill_rect already apply).
    if (srcStride < srcW * 4) {
        return 1;
    }
    SkCanvas* cv = asCanvas(surfaceHandle);
    if (!cv) return 2;
    SkImageInfo info = SkImageInfo::Make(
        srcW, srcH, kBGRA_8888_SkColorType, kPremul_SkAlphaType);
    SkPixmap pixmap(info, reinterpret_cast<const void*>(pixels),
                    static_cast<size_t>(srcStride));
    sk_sp<SkImage> img = SkImages::RasterFromPixmap(pixmap, nullptr, nullptr);
    if (!img) return 3;
    SkRect src = SkRect::MakeIWH(srcW, srcH);
    SkRect dst = SkRect::MakeXYWH(static_cast<SkScalar>(dstX),
                                  static_cast<SkScalar>(dstY),
                                  static_cast<SkScalar>(dstW),
                                  static_cast<SkScalar>(dstH));
    SkSamplingOptions sampling(SkFilterMode::kLinear, SkMipmapMode::kNone);
    const OpenJfxSurface* st = asState(surfaceHandle);
    SkPaint p;
    if (st) applyState(p, *st);
    p.setAntiAlias(false);
    cv->drawImageRect(img.get(), src, dst, sampling, &p,
                      SkCanvas::kStrict_SrcRectConstraint);
    return 0;
#else
    (void)surfaceHandle; (void)pixels;
    (void)srcW; (void)srcH; (void)srcStride;
    (void)dstX; (void)dstY; (void)dstW; (void)dstH;
    return -1;
#endif
}

// ---- Arbitrary path --------------------------------------------------------

OPENJFX_API int32_t openjfx_skia_surface_fill_path(
    uintptr_t handle,
    const uint8_t* verbs,  int32_t verbCount,
    const float*   coords, int32_t coordCount,
    int32_t fillRule,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return 1;
    SkPath path;
    if (!buildPath(path, verbs, verbCount, coords, coordCount, fillRule)) return 2;
    SkPaint paint;
    configureFill(paint, *asState(handle), r, g, b, a);
    canvas->drawPath(path, paint);
    return 0;
#else
    (void)handle; (void)verbs; (void)verbCount; (void)coords; (void)coordCount;
    (void)fillRule; (void)r; (void)g; (void)b; (void)a;
    return -1;
#endif
}

// Fill a path with a gaussian-blurred edge — the native primitive behind CSS
// box-shadow. The caller passes the shadow shape already translated by the
// shadow offset; `sigma` is the SkMaskFilter blur sigma (≈ CSS blur radius / 2).
// Drawing the blurred shape straight on the live SkCanvas avoids the Prism
// DropShadow effect's intermediate-surface composite, which is broken on our
// Skia backend (hard / missing shadows). See memory
// project_webview_box_shadow_hard_no_blur.
OPENJFX_API int32_t openjfx_skia_surface_fill_path_blur(
    uintptr_t handle,
    const uint8_t* verbs,  int32_t verbCount,
    const float*   coords, int32_t coordCount,
    int32_t fillRule,
    float sigma,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return 1;
    SkPath path;
    if (!buildPath(path, verbs, verbCount, coords, coordCount, fillRule)) return 2;
    SkPaint paint;
    configureFill(paint, *asState(handle), r, g, b, a);
    if (sigma > 0.0f) {
        // Cache the last-used normal blur mask filter, keyed on the sigma
        // quantized to 0.25 steps. Box-shadow redraws on a given element
        // hit the same sigma every frame; MakeBlur otherwise allocates a
        // fresh SkMaskFilter per call. thread_local (not a plain static):
        // WebKit box-shadow blur can reach this from the record/event thread
        // concurrently with a scene shadow on the render thread, and a shared
        // sk_sp would tear its refcount (UAF/double-free). Per-thread caches
        // keep the perf win without the race.
        thread_local float               s_lastSigmaQuant = -1.0f;
        thread_local sk_sp<SkMaskFilter> s_lastMaskFilter;
        float quant = std::round(sigma * 4.0f) / 4.0f;
        if (quant <= 0.0f) quant = sigma; // never quantize a positive sigma to 0
        if (!s_lastMaskFilter || quant != s_lastSigmaQuant) {
            s_lastMaskFilter = SkMaskFilter::MakeBlur(kNormal_SkBlurStyle, quant);
            s_lastSigmaQuant = quant;
        }
        paint.setMaskFilter(s_lastMaskFilter);
    }
    canvas->drawPath(path, paint);
    return 0;
#else
    (void)handle; (void)verbs; (void)verbCount; (void)coords; (void)coordCount;
    (void)fillRule; (void)sigma; (void)r; (void)g; (void)b; (void)a;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_stroke_path(
    uintptr_t handle,
    const uint8_t* verbs,  int32_t verbCount,
    const float*   coords, int32_t coordCount,
    int32_t fillRule,
    float width, int32_t cap, int32_t join, float miter,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return 1;
    SkPath path;
    if (!buildPath(path, verbs, verbCount, coords, coordCount, fillRule)) return 2;
    SkPaint paint;
    configureStroke(paint, *asState(handle), r, g, b, a, width, cap, join, miter);
    canvas->drawPath(path, paint);
    return 0;
#else
    (void)handle; (void)verbs; (void)verbCount; (void)coords; (void)coordCount;
    (void)fillRule; (void)width; (void)cap; (void)join; (void)miter;
    (void)r; (void)g; (void)b; (void)a;
    return -1;
#endif
}

// ===========================================================================
// Text / glyph rendering
// ===========================================================================

OPENJFX_API uintptr_t openjfx_skia_typeface_create_from_data(
    const void* data, int32_t length) {
#ifdef OPENJFX_WITH_SKIA
    if (!data || length <= 0) return 0;
    sk_sp<SkData> bytes = SkData::MakeWithCopy(data, static_cast<size_t>(length));
    if (!bytes) return 0;
    const sk_sp<SkFontMgr>& mgr = customFontMgr();
    if (!mgr) return 0;
    sk_sp<SkTypeface> tf = mgr->makeFromData(std::move(bytes), 0);
    if (!tf) return 0;
    return reinterpret_cast<uintptr_t>(new sk_sp<SkTypeface>(std::move(tf)));
#else
    (void)data; (void)length;
    return 0;
#endif
}

OPENJFX_API void openjfx_skia_typeface_destroy(uintptr_t handle) {
    if (!handle) return;
#ifdef OPENJFX_WITH_SKIA
    delete asTypefaceSp(handle);
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_draw_glyphs(
    uintptr_t handle, uintptr_t typefaceHandle,
    float fontSize,
    const uint16_t* glyphIds, int32_t count,
    const float* posX, const float* posY,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return 1;
    if (!glyphIds || !posX || !posY || count <= 0) return 2;
    sk_sp<SkTypeface>* tfSp = asTypefaceSp(typefaceHandle);
    if (!tfSp || !*tfSp) return 3;
    // Same guard as buildPath: a non-finite baseline position would
    // overflow the software rasterizer.
    for (int i = 0; i < count; ++i) {
        if (!coordFinite(posX[i]) || !coordFinite(posY[i])) {
            reportBadPath("draw_glyphs", i,
                          coordFinite(posX[i]) ? posY[i] : posX[i]);
            return 4;
        }
    }
    SkFont font(*tfSp, fontSize);
    // Greyscale anti-alias only. kSubpixelAntiAlias (LCD ClearType) splits
    // the rasterized coverage across R / G / B sub-channels — correct only
    // when the destination is fully opaque AND the paint alpha is 1.0.
    // WebKit hits us with translucent paints (opacity, transparency layers
    // for box-shadow) where the sub-channel splay reads as horizontal
    // ghost stripes on top of the text. kAntiAlias renders symmetric
    // coverage and looks identical on every surface.
    //
    // setSubpixel(false): snap glyph baselines to integer pixel positions.
    // With subpixel positioning ON, glyphs land at fractional Y, so each
    // line's AA edges spill across two pixel rows; on a gradient or
    // translucent background that vertical splay reads as a faint stripe
    // through the text. Snapping to whole pixels removes that artifact at
    // the cost of ~half a pixel of horizontal placement precision —
    // imperceptible at body-text sizes.
    font.setEdging(SkFont::Edging::kAntiAlias);
    font.setSubpixel(false);
    font.setHinting(SkFontHinting::kSlight);
    SkTextBlobBuilder builder;
    const SkTextBlobBuilder::RunBuffer& run = builder.allocRunPos(font, count);
    std::memcpy(run.glyphs, glyphIds,
                static_cast<size_t>(count) * sizeof(uint16_t));
    SkPoint* pts = run.points();
    for (int i = 0; i < count; ++i) {
        pts[i].set(posX[i], posY[i]);
    }
    sk_sp<SkTextBlob> blob = builder.make();
    if (!blob) return 0; // empty run — nothing to draw
    SkPaint paint;
    configureFill(paint, *asState(handle), r, g, b, a);
    canvas->drawTextBlob(blob, 0, 0, paint);
    return 0;
#else
    (void)handle; (void)typefaceHandle; (void)fontSize;
    (void)glyphIds; (void)count; (void)posX; (void)posY;
    (void)r; (void)g; (void)b; (void)a;
    return -1;
#endif
}

// Shader-filled glyph run. Identical to openjfx_skia_surface_draw_glyphs
// except the paint carries an SkShader (gradient / image-pattern) instead
// of a solid colour, so the gradient is sampled across each glyph's coverage
// exactly like a shape fill. Used by SkiaGraphics.drawString when the
// current paint is a Gradient or ImagePattern. The shader is sampled in the
// surface's device space (the same space the glyph baseline positions are
// in), matching how JavaFX resolves gradient text fills.
OPENJFX_API int32_t openjfx_skia_surface_draw_glyphs_shader(
    uintptr_t handle, uintptr_t typefaceHandle,
    float fontSize,
    const uint16_t* glyphIds, int32_t count,
    const float* posX, const float* posY,
    uintptr_t shaderHandle, uint8_t alpha) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* canvas = asCanvas(handle);
    if (!canvas) return 1;
    if (!glyphIds || !posX || !posY || count <= 0) return 2;
    sk_sp<SkTypeface>* tfSp = asTypefaceSp(typefaceHandle);
    if (!tfSp || !*tfSp) return 3;
    SkShader* shader = asShader(shaderHandle);
    if (!shader) return 5;
    for (int i = 0; i < count; ++i) {
        if (!coordFinite(posX[i]) || !coordFinite(posY[i])) {
            reportBadPath("draw_glyphs_shader", i,
                          coordFinite(posX[i]) ? posY[i] : posX[i]);
            return 4;
        }
    }
    // Same font configuration as the solid path: greyscale AA, integer
    // baseline snap, slight hinting (see openjfx_skia_surface_draw_glyphs).
    SkFont font(*tfSp, fontSize);
    font.setEdging(SkFont::Edging::kAntiAlias);
    font.setSubpixel(false);
    font.setHinting(SkFontHinting::kSlight);
    SkTextBlobBuilder builder;
    const SkTextBlobBuilder::RunBuffer& run = builder.allocRunPos(font, count);
    std::memcpy(run.glyphs, glyphIds,
                static_cast<size_t>(count) * sizeof(uint16_t));
    SkPoint* pts = run.points();
    for (int i = 0; i < count; ++i) {
        pts[i].set(posX[i], posY[i]);
    }
    sk_sp<SkTextBlob> blob = builder.make();
    if (!blob) return 0; // empty run — nothing to draw
    SkPaint paint;
    configureFillShader(paint, *asState(handle), shader, alpha);
    canvas->drawTextBlob(blob, 0, 0, paint);
    return 0;
#else
    (void)handle; (void)typefaceHandle; (void)fontSize;
    (void)glyphIds; (void)count; (void)posX; (void)posY;
    (void)shaderHandle; (void)alpha;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_read_pixels(
    uintptr_t handle, void* dst,
    int32_t x, int32_t y, int32_t w, int32_t h) {
#ifdef OPENJFX_WITH_SKIA
    SkSurface* s = asSurface(handle);
    if (!s || !dst || w <= 0 || h <= 0) return 1;
    SkImageInfo info = SkImageInfo::Make(
        w, h, kRGBA_8888_SkColorType, kPremul_SkAlphaType);
    return s->readPixels(info, dst, static_cast<size_t>(w) * 4, x, y) ? 0 : 2;
#else
    (void)handle; (void)dst; (void)x; (void)y; (void)w; (void)h;
    return -1;
#endif
}

// Re-points a surface state at caller-supplied BGRA pixels via
// SkSurfaces::WrapPixels — which does NOT copy or take ownership of the memory.
// LIFETIME CONTRACT: `pixels` must outlive `st->surface` (i.e. until the next
// replace_backing call or the surface's destruction). The caller (Java side,
// holding the MemorySegment whose Arena keeps the memory mapped) owns it; the
// bridge never frees `pixels`. Passing a stack/temp buffer here is a UAF.
OPENJFX_API int32_t openjfx_skia_surface_replace_backing_argb(
    uintptr_t handle, void* pixels,
    int32_t width, int32_t height, int32_t rowBytes) {
#ifdef OPENJFX_WITH_SKIA
    OpenJfxSurface* st = asState(handle);
    if (!st || !pixels || width <= 0 || height <= 0) return 1;
    SkImageInfo info = SkImageInfo::Make(
        width, height, kBGRA_8888_SkColorType, kPremul_SkAlphaType);
    sk_sp<SkSurface> wrapped = SkSurfaces::WrapPixels(
        info, pixels, static_cast<size_t>(rowBytes));
    if (!wrapped) return 2;
    st->surface = std::move(wrapped);
    return 0;
#else
    (void)handle; (void)pixels; (void)width; (void)height; (void)rowBytes;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_read_pixels_argb(
    uintptr_t handle, void* dst,
    int32_t x, int32_t y, int32_t w, int32_t h) {
#ifdef OPENJFX_WITH_SKIA
    SkSurface* s = asSurface(handle);
    if (!s || !dst || w <= 0 || h <= 0) return 1;
    // BGRA byte order on little-endian = INT_ARGB_PRE int layout in
    // memory. Skia handles the conversion from its native RGBA
    // surface (vector / SIMD path).
    SkImageInfo info = SkImageInfo::Make(
        w, h, kBGRA_8888_SkColorType, kPremul_SkAlphaType);
    return s->readPixels(info, dst, static_cast<size_t>(w) * 4, x, y) ? 0 : 2;
#else
    (void)handle; (void)dst; (void)x; (void)y; (void)w; (void)h;
    return -1;
#endif
}

// skia-fx paint-before-show: copy the just-rendered frame onto the window's DWM
// redirection bitmap via GDI, so the OS show animation reveals the real UI
// instead of the default white/black bitmap. Called ONCE, on the render thread,
// AFTER the pre-show paint and BEFORE Present + the FX thread's ShowWindow. GDI
// reaches the same redirection surface CustomWinProc's WM_ERASEBKGND fills,
// which is what the show animation captures (a flip-model swap-chain Present
// does not). Returns non-zero on any failure so the caller degrades to the
// prior behavior. No-op (-1) off-Windows / without Skia.
OPENJFX_API int32_t openjfx_skia_surface_prime_window(uintptr_t handle) {
#if defined(OPENJFX_WITH_SKIA) && defined(_WIN32)
    OpenJfxSurface* st = asState(handle);
    if (!st) return 1;
    HWND hwnd = st->windowHwnd ? st->windowHwnd
              : (st->d3d ? (HWND) openjfxD3DHwnd(st->d3d) : nullptr);
    if (!hwnd) return 2;
    SkSurface* s = asSurface(handle);
    if (!s) return 3;
    const int w = s->width();
    const int h = s->height();
    if (w <= 0 || h <= 0) return 4;

    std::vector<uint8_t> buf(static_cast<size_t>(w) * static_cast<size_t>(h) * 4);
    SkImageInfo info = SkImageInfo::Make(
        w, h, kBGRA_8888_SkColorType, kPremul_SkAlphaType);
    if (!s->readPixels(info, buf.data(), static_cast<size_t>(w) * 4, 0, 0)) {
        return 5;
    }

    HDC hdc = ::GetDC(hwnd);
    if (!hdc) return 6;
    RECT cr{};
    ::GetClientRect(hwnd, &cr);
    int cw = cr.right - cr.left;
    int ch = cr.bottom - cr.top;
    if (cw <= 0) cw = w;
    if (ch <= 0) ch = h;

    BITMAPINFO bmi{};
    bmi.bmiHeader.biSize        = sizeof(BITMAPINFOHEADER);
    bmi.bmiHeader.biWidth       = w;
    bmi.bmiHeader.biHeight      = -h;          // negative = top-down DIB
    bmi.bmiHeader.biPlanes      = 1;
    bmi.bmiHeader.biBitCount    = 32;          // BGRA / BGRX, alpha ignored
    bmi.bmiHeader.biCompression = BI_RGB;
    int scan = ::StretchDIBits(hdc,
        0, 0, cw, ch,                          // dest client rect
        0, 0, w, h,                            // source surface px
        buf.data(), &bmi, DIB_RGB_COLORS, SRCCOPY);
    ::ReleaseDC(hwnd, hdc);
    return (scan == 0 || scan == GDI_ERROR) ? 7 : 0;
#else
    (void)handle;
    return -1;
#endif
}

OPENJFX_API int32_t openjfx_skia_clear_buffer(
    void* pixels,
    int32_t width,
    int32_t height,
    int32_t rowBytes,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a) {

    if (pixels == nullptr || width <= 0 || height <= 0) {
        return 1;
    }
    if (rowBytes < width * 4) {
        return 2;
    }

#ifdef OPENJFX_WITH_SKIA
    SkImageInfo info = SkImageInfo::Make(
        width, height, kRGBA_8888_SkColorType, kPremul_SkAlphaType);
    sk_sp<SkSurface> surface = SkSurfaces::WrapPixels(
        info, pixels, static_cast<size_t>(rowBytes));
    if (!surface) {
        return 3;
    }
    SkCanvas* canvas = surface->getCanvas();
    canvas->clear(SkColorSetARGB(a, r, g, b));
    return 0;
#else
    // Pre-multiply RGB by alpha so the fallback matches what Skia
    // would write for kPremul_SkAlphaType.
    auto premul = [](uint8_t c, uint8_t a) -> uint8_t {
        return static_cast<uint8_t>((static_cast<int>(c) * a + 127) / 255);
    };
    uint8_t pr = premul(r, a);
    uint8_t pg = premul(g, a);
    uint8_t pb = premul(b, a);
    uint32_t packed = (static_cast<uint32_t>(a)  << 24)
                    | (static_cast<uint32_t>(pb) << 16)
                    | (static_cast<uint32_t>(pg) << 8)
                    |  static_cast<uint32_t>(pr);
    auto* base = static_cast<uint8_t*>(pixels);
    for (int32_t y = 0; y < height; ++y) {
        auto* row = reinterpret_cast<uint32_t*>(base + y * rowBytes);
        for (int32_t x = 0; x < width; ++x) {
            row[x] = packed;
        }
    }
    return 0;
#endif
}

OPENJFX_API int32_t openjfx_skia_fill_rect(
    void* pixels,
    int32_t width, int32_t height, int32_t rowBytes,
    int32_t x, int32_t y, int32_t rectW, int32_t rectH,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a) {

    if (pixels == nullptr || width <= 0 || height <= 0) return 1;
    if (rowBytes < width * 4) return 2;

#ifdef OPENJFX_WITH_SKIA
    SkImageInfo info = SkImageInfo::Make(
        width, height, kRGBA_8888_SkColorType, kPremul_SkAlphaType);
    sk_sp<SkSurface> surface = SkSurfaces::WrapPixels(
        info, pixels, static_cast<size_t>(rowBytes));
    if (!surface) return 3;

    SkPaint paint;
    paint.setColor(SkColorSetARGB(a, r, g, b));
    paint.setAntiAlias(true);
    SkRect rect = SkRect::MakeXYWH(
        static_cast<float>(x), static_cast<float>(y),
        static_cast<float>(rectW), static_cast<float>(rectH));
    surface->getCanvas()->drawRect(rect, paint);
    return 0;
#else
    auto premul = [](uint8_t c, uint8_t a) -> uint8_t {
        return static_cast<uint8_t>((static_cast<int>(c) * a + 127) / 255);
    };
    uint32_t packed =
          (static_cast<uint32_t>(a)             << 24)
        | (static_cast<uint32_t>(premul(b, a)) << 16)
        | (static_cast<uint32_t>(premul(g, a)) << 8)
        |  static_cast<uint32_t>(premul(r, a));

    int32_t x0 = std::max(x, 0);
    int32_t y0 = std::max(y, 0);
    int32_t x1 = std::min(x + rectW, width);
    int32_t y1 = std::min(y + rectH, height);
    auto* base = static_cast<uint8_t*>(pixels);
    for (int32_t yi = y0; yi < y1; ++yi) {
        auto* row = reinterpret_cast<uint32_t*>(base + yi * rowBytes);
        for (int32_t xi = x0; xi < x1; ++xi) {
            row[xi] = packed;
        }
    }
    return 0;
#endif
}

// ===========================================================================
// SkPicture record / replay — Task #31
// ---------------------------------------------------------------------------
// Lets Java cache stable subtrees: record the draw stream once, replay each
// pulse until the subtree is marked dirty. See bridge.h for the full API
// contract and SKPICTURE_CACHING_DESIGN.md for the rationale.
// ===========================================================================

// Wrapper that owns both the Skia recorder AND the OpenJfxSurface that
// proxies the recorder's canvas. begin() may be called repeatedly on the
// same recorder; each call discards the previous canvas-wrapper.
struct OpenJfxPictureRecorder {
    SkPictureRecorder skRec;
    OpenJfxSurface*   canvasWrapper = nullptr; // non-null between begin/finish
};

static void releaseRecorderCanvas(OpenJfxPictureRecorder* r) {
    if (r && r->canvasWrapper) {
        delete r->canvasWrapper;
        r->canvasWrapper = nullptr;
    }
}

OPENJFX_API uintptr_t openjfx_skia_picture_recorder_create(void) {
#ifdef OPENJFX_WITH_SKIA
    return reinterpret_cast<uintptr_t>(new OpenJfxPictureRecorder());
#else
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_picture_recorder_begin(
    uintptr_t recorderHandle, float x, float y, float w, float h) {
#ifdef OPENJFX_WITH_SKIA
    auto* r = reinterpret_cast<OpenJfxPictureRecorder*>(recorderHandle);
    if (!r) return 0;
    releaseRecorderCanvas(r); // belt-and-suspenders: drop any prior wrapper
    SkCanvas* canvas = r->skRec.beginRecording(SkRect::MakeXYWH(x, y, w, h));
    if (!canvas) return 0;
    // Wrap the recording canvas so the regular surface_* draw entries
    // can target it transparently — asCanvas() picks recordingCanvas
    // over the (null) surface field.
    auto* st = new OpenJfxSurface();
    st->recordingCanvas = canvas;
    r->canvasWrapper = st;
    return reinterpret_cast<uintptr_t>(st);
#else
    (void)recorderHandle; (void)x; (void)y; (void)w; (void)h;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_picture_recorder_finish(
    uintptr_t recorderHandle) {
#ifdef OPENJFX_WITH_SKIA
    auto* r = reinterpret_cast<OpenJfxPictureRecorder*>(recorderHandle);
    if (!r) return 0;
    sk_sp<SkPicture> pic = r->skRec.finishRecordingAsPicture();
    // Whether finish succeeded or not, the canvas pointer is now stale.
    releaseRecorderCanvas(r);
    if (!pic) return 0;
    return reinterpret_cast<uintptr_t>(new sk_sp<SkPicture>(std::move(pic)));
#else
    (void)recorderHandle;
    return 0;
#endif
}

OPENJFX_API void openjfx_skia_picture_destroy(uintptr_t pictureHandle) {
#ifdef OPENJFX_WITH_SKIA
    if (!pictureHandle) return;
    auto* sp = reinterpret_cast<sk_sp<SkPicture>*>(pictureHandle);
    delete sp;
#else
    (void)pictureHandle;
#endif
}

OPENJFX_API void openjfx_skia_picture_recorder_destroy(uintptr_t recorderHandle) {
#ifdef OPENJFX_WITH_SKIA
    if (!recorderHandle) return;
    auto* r = reinterpret_cast<OpenJfxPictureRecorder*>(recorderHandle);
    releaseRecorderCanvas(r);
    delete r;
#else
    (void)recorderHandle;
#endif
}

OPENJFX_API int32_t openjfx_skia_surface_draw_picture(
    uintptr_t targetSurface, uintptr_t pictureHandle,
    float dx, float dy) {
#ifdef OPENJFX_WITH_SKIA
    SkCanvas* cv = asCanvas(targetSurface);
    if (!cv) return 1;
    auto* sp = reinterpret_cast<sk_sp<SkPicture>*>(pictureHandle);
    if (!sp || !*sp) return 2;
    if (dx != 0.0f || dy != 0.0f) {
        SkMatrix m = SkMatrix::Translate(dx, dy);
        cv->drawPicture(sp->get(), &m, /*paint=*/nullptr);
    } else {
        cv->drawPicture(sp->get());
    }
    return 0;
#else
    (void)targetSurface; (void)pictureHandle; (void)dx; (void)dy;
    return -1;
#endif
}

} // extern "C"

// ===========================================================================
// skia_fx — typed C++ accessors for in-process native consumers.
//
// Declared in shared/include/skia_fx_bridge.h. These exist so that other
// native modules (today: javafx.web's WebKit port) can resolve a handle
// issued by this bridge to a real SkSurface* / SkCanvas* / SkImage* /
// GrDirectContext* and draw into the same scene buffer the pipeline is
// rendering — the zero-copy property described in CLAUDE.md.
//
// Defined here, in the same translation unit as the anonymous-namespace
// helpers (asState, asSurface, asCanvas, asImage, gpuDirectContext),
// because anon-namespace members are TU-scoped and we need to reuse them
// without duplicating handle-validation logic.
// ===========================================================================

#ifdef OPENJFX_WITH_SKIA

#include "skia_fx_bridge.h"

namespace skia_fx {

SKIA_FX_API SkSurface* resolve_surface(uintptr_t handle) {
    return asSurface(handle);
}

SKIA_FX_API SkCanvas* resolve_canvas(uintptr_t handle) {
    return asCanvas(handle);
}

SKIA_FX_API SkImage* resolve_image(uintptr_t handle) {
    return asImage(handle);
}

SKIA_FX_API GrDirectContext* shared_gr_context() {
    return gpuDirectContext().get();
}

SKIA_FX_API uintptr_t register_surface(sk_sp<SkSurface> surface) {
    if (!surface) return 0;
    auto* st = new OpenJfxSurface();
    st->surface = std::move(surface);
    return reinterpret_cast<uintptr_t>(st);
}

SKIA_FX_API uintptr_t register_image(sk_sp<SkImage> image) {
    if (!image) return 0;
    return makeImageHandle(std::move(image));
}

SKIA_FX_API bool backend_is_d3d() {
    // Force lazy backend selection so the answer is meaningful even if
    // no GPU surface has been allocated yet.
    (void)gpuDirectContext();
    return gGpuBackend == GpuBackend::D3D;
}

SKIA_FX_API void gpu_flush_and_wait() {
    const sk_sp<GrDirectContext>& ctx = gpuDirectContext();
    if (ctx) {
        ctx->flushAndSubmit(GrSyncCpu::kYes);
    }
}

} // namespace skia_fx

// Device-loss query for Java (SkiaResourceFactory.isDeviceReady). Defined here,
// after skia_fx_bridge.h, so skia_fx::d3d12_device_lost() is declared. Windows/D3D
// only; GL/raster return 0 (they don't lose the device this way).
extern "C" OPENJFX_API int32_t openjfx_skia_device_lost(void) {
#if defined(_WIN32)
    return skia_fx::d3d12_device_lost() ? 1 : 0;
#else
    return 0;
#endif
}

#endif // OPENJFX_WITH_SKIA
