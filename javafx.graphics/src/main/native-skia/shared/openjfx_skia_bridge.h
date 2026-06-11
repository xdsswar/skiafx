/*
 * openjfx_skia_bridge.h — C ABI surface for Java FFM.
 *
 * Every entry point here is `extern "C"` and uses primitive / pointer
 * types only, so it round-trips cleanly through java.lang.foreign.
 * The bridge intentionally hides Skia C++ types behind opaque pointers
 * (uintptr_t handles) so the header compiles even when Skia is not
 * available, and so Java does not try to model C++ types.
 */

#ifndef OPENJFX_SKIA_BRIDGE_H
#define OPENJFX_SKIA_BRIDGE_H

#include <stddef.h>
#include <stdint.h>

#ifdef _WIN32
  #define OPENJFX_API __declspec(dllexport)
#else
  #define OPENJFX_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Returns a NUL-terminated string identifying the bridge build.
 * Lifetime: static (do not free).
 */
OPENJFX_API const char* openjfx_skia_version(void);

/**
 * Returns 1 if the bridge was compiled with real Skia integration,
 * 0 if it is the bridge-only stub. Java side uses this to decide
 * whether to enable Skia-backed paths or fall back.
 */
OPENJFX_API int32_t openjfx_skia_has_skia(void);

/* ===========================================================================
 * SkSurface lifecycle (handle-based).
 *
 * Surfaces are owned by the native side and exposed to Java as
 * uintptr_t-sized handles. A non-zero handle is valid; zero means the
 * native side could not create the surface. Java is responsible for
 * calling surface_destroy exactly once when the surface is no longer
 * needed (typically via a Cleaner action attached to its Java wrapper).
 * ===========================================================================
 */

/**
 * Creates a CPU-backed SkSurface (kRGBA_8888, premultiplied) with the
 * given dimensions. Returns 0 on failure or when Skia is not compiled
 * in.
 */
OPENJFX_API uintptr_t openjfx_skia_surface_create_raster(int32_t width, int32_t height);

/**
 * Creates a CPU-backed SkSurface in kBGRA_8888 (premultiplied). Used by
 * the READBACK present tier: BGRA matches the INT_ARGB_PRE layout Glass
 * uploads on little-endian hosts, so the per-frame
 * surface_read_pixels_argb becomes a straight row copy instead of a
 * full-frame channel swizzle. Returns 0 on failure or when Skia is not
 * compiled in.
 */
OPENJFX_API uintptr_t openjfx_skia_surface_create_raster_bgra(int32_t width, int32_t height);

/**
 * Creates a GPU-backed SkSurface (Ganesh, OpenGL) with the given
 * dimensions. The first call also brings up a per-process
 * GrDirectContext bound to whichever thread calls first (Quantum's
 * render thread in normal use). Returns 0 if GPU is unavailable on
 * this platform/build, in which case the caller should fall back to
 * surface_create_raster.
 */
OPENJFX_API uintptr_t openjfx_skia_surface_create_gpu(int32_t width, int32_t height);

/**
 * Creates a GPU-backed SkSurface that wraps the OpenGL default
 * framebuffer of `hwnd`. Subsequent Skia draws land directly in the
 * window's back buffer; `surface_present_window` flushes and
 * SwapBuffers to make them visible. No CPU readback, no Glass BitBlt.
 *
 * Returns 0 if GPU is unavailable, the HWND already has an
 * incompatible pixel format, or the wrap failed — caller falls back to
 * `surface_create_gpu` or `surface_create_raster`.
 *
 * Windows only at this revision; on other platforms returns 0.
 */
OPENJFX_API uintptr_t openjfx_skia_surface_create_window_gpu(
    uintptr_t hwnd, int32_t width, int32_t height);

/**
 * Flushes the GrDirectContext and SwapBuffers on the window-bound
 * surface. {@code vsync} (1 = on, 0 = uncapped) is applied via
 * wglSwapIntervalEXT when available (best-effort; the GL tier is
 * otherwise DWM-vsync'd). Returns 0 on success.
 */
OPENJFX_API int32_t openjfx_skia_surface_present_window(uintptr_t handle, int32_t vsync);

/**
 * Creates a D3D12 DXGI flip-model swap chain attached to `hwnd` and
 * wraps each back buffer as a Skia SkSurface. Drawing through the
 * returned handle lands in the current back buffer; present_window_d3d
 * issues Present(0, ALLOW_TEARING).
 *
 * Returns 0 if D3D12 isn't the active backend on this process, the
 * adapter doesn't support D3D12, or the swap-chain creation failed —
 * caller falls back to surface_create_window_gpu (GL direct-present),
 * surface_create_gpu (offscreen + readback), or surface_create_raster.
 *
 * Windows only.
 */
OPENJFX_API uintptr_t openjfx_skia_surface_create_swap_chain_d3d(
    uintptr_t hwnd, int32_t width, int32_t height);

/**
 * Flushes the GrDirectContext and presents the swap chain. With
 * {@code vsync == 0}, uses `Present(0, DXGI_PRESENT_ALLOW_TEARING)` when
 * supported (uncapped — bypasses DWM's windowed-vsync cap, may tear);
 * with {@code vsync != 0}, uses `Present(1, 0)` (blocks for the next
 * vblank, no tearing). The sync interval is a per-Present parameter, so
 * this can be toggled freely at runtime with no swap-chain rebuild.
 * Signals the per-back-buffer fence after the present so the next acquire
 * of that buffer can synchronise correctly.
 */
OPENJFX_API int32_t openjfx_skia_surface_present_window_d3d(uintptr_t handle, int32_t vsync);

/**
 * Called once per frame *before* the bridge starts drawing into a
 * D3D-swap-chain surface. Waits on DXGI's frame-latency waitable and
 * on the per-buffer fence — deterministic CPU↔GPU pacing with a
 * shallow (1-frame) CPU lead. Returns 0 on success, non-zero if the
 * surface isn't a D3D swap-chain surface.
 */
OPENJFX_API int32_t openjfx_skia_surface_begin_frame_d3d(uintptr_t handle);

/**
 * In-place resize of a D3D swap-chain surface via
 * {@code IDXGISwapChain::ResizeBuffers}. DWM honours this as a
 * semantic resize, eliminating the resize-drag stretch artifact that
 * GL windowed apps exhibit on Windows. Returns 0 on success.
 */
OPENJFX_API int32_t openjfx_skia_surface_resize_d3d(
    uintptr_t handle, int32_t width, int32_t height);

/**
 * In-place resize of a GL direct-present surface created by
 * {@link openjfx_skia_surface_create_window_gpu}. Re-wraps the window's
 * default framebuffer (FBO 0) at the new size as a fresh
 * {@code SkSurface}; the prior surface is released. Keeps the
 * {@code GrDirectContext} and the cached HDC binding intact, so
 * subsequent draws/presents reuse the same context — much cheaper than
 * destroying and recreating the bridge-side surface for every
 * drag-resize tick. Returns 0 on success; non-zero falls back to
 * caller-driven recreate.
 *
 * Windows only.
 */
OPENJFX_API int32_t openjfx_skia_surface_resize_gl(
    uintptr_t handle, int32_t width, int32_t height);

/** Releases a surface. Safe to call with a 0 handle (no-op). */
OPENJFX_API void openjfx_skia_surface_destroy(uintptr_t handle);

/**
 * Synchronously clears the OpenGL default framebuffer of {@code hwnd}
 * to (r, g, b) and presents via SwapBuffers, using a secondary GL
 * context dedicated to this call (so it can be invoked from any
 * thread without stealing the render thread's main context).
 *
 * Designed for Glass's WM_SIZE handler: between the OS resizing the
 * HWND and our render thread's next paint, DWM otherwise composes the
 * window using whatever's in the GL framebuffer — typically a stretched
 * copy of the previous frame. Calling this inline within WM_SIZE
 * replaces those pixels with a flat color, so the visible artifact is
 * a clean color flash instead of a stretched/distorted image.
 *
 * Windows only; pixel format must already be set on the HWND's HDC
 * (the render thread does this on its first bind). Returns 0 on
 * success, non-zero if the clear couldn't be performed (no pixel
 * format yet, GL context creation failed, etc.); callers should
 * treat any non-zero return as "skip — render thread will catch up".
 */
OPENJFX_API int32_t openjfx_skia_window_clear(
    void* hwnd /* HWND */,
    int32_t width, int32_t height,
    uint8_t r, uint8_t g, uint8_t b);

/**
 * Returns the refresh rate (Hz) of the monitor the given window is on,
 * for use as the present-rate cap. Multi-monitor setups need this
 * per-window — a window dragged from a 60 Hz secondary onto a 144 Hz
 * primary should pick up the higher refresh.
 *
 * Returns 0 if the rate could not be determined (unknown platform,
 * driver hides it, hwnd not yet on any monitor); callers should fall
 * back to a sensible default (e.g. 60). Cheap enough to call per
 * present (Windows: one MonitorFromWindow + EnumDisplaySettings).
 */
OPENJFX_API int32_t openjfx_skia_window_get_refresh_hz(void* hwnd /* HWND */);

/** Returns the surface's pixel width / height, or -1 on failure. */
OPENJFX_API int32_t openjfx_skia_surface_width(uintptr_t handle);
OPENJFX_API int32_t openjfx_skia_surface_height(uintptr_t handle);

/* ===========================================================================
 * SkCanvas operations on an SkSurface handle.
 * ===========================================================================
 */

/** Clears the surface's canvas to the given (premultiplied) RGBA color. */
OPENJFX_API int32_t openjfx_skia_surface_clear(
    uintptr_t handle,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a);

/** Fills an axis-aligned rectangle with anti-aliased Skia drawing. */
OPENJFX_API int32_t openjfx_skia_surface_fill_rect(
    uintptr_t handle,
    int32_t   x,
    int32_t   y,
    int32_t   w,
    int32_t   h,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a);

/* ---- Filled primitives (anti-aliased) -------------------------------- */

OPENJFX_API int32_t openjfx_skia_surface_fill_round_rect(
    uintptr_t handle,
    float x, float y, float w, float h,
    float arcW, float arcH,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a);

OPENJFX_API int32_t openjfx_skia_surface_fill_oval(
    uintptr_t handle,
    float x, float y, float w, float h,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a);

/* ---- Stroked primitives -------------------------------------------------
 * Stroke parameters:
 *   width  = line width in pixels
 *   cap    = 0 BUTT, 1 ROUND, 2 SQUARE
 *   join   = 0 MITER, 1 ROUND, 2 BEVEL
 *   miter  = miter limit (used only when join == 0)
 */

OPENJFX_API int32_t openjfx_skia_surface_stroke_rect(
    uintptr_t handle,
    float x, float y, float w, float h,
    float width, int32_t cap, int32_t join, float miter,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a);

OPENJFX_API int32_t openjfx_skia_surface_stroke_round_rect(
    uintptr_t handle,
    float x, float y, float w, float h,
    float arcW, float arcH,
    float width, int32_t cap, int32_t join, float miter,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a);

OPENJFX_API int32_t openjfx_skia_surface_stroke_oval(
    uintptr_t handle,
    float x, float y, float w, float h,
    float width, int32_t cap, int32_t join, float miter,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a);

OPENJFX_API int32_t openjfx_skia_surface_stroke_line(
    uintptr_t handle,
    float x1, float y1, float x2, float y2,
    float width, int32_t cap, int32_t join, float miter,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a);

/* ---- Arbitrary path (fill + stroke) -------------------------------------
 * Verb encoding (matches JavaFX PathIterator):
 *   0 = MOVE_TO    (consumes 2 floats: x, y)
 *   1 = LINE_TO    (consumes 2 floats)
 *   2 = QUAD_TO    (consumes 4 floats: cx,cy, x,y)
 *   3 = CUBIC_TO   (consumes 6 floats: c1x,c1y, c2x,c2y, x,y)
 *   4 = CLOSE      (consumes 0 floats)
 * Fill rule:
 *   0 = EVEN_ODD
 *   1 = NON_ZERO (winding)
 */

OPENJFX_API int32_t openjfx_skia_surface_fill_path(
    uintptr_t handle,
    const uint8_t* verbs,  int32_t verbCount,
    const float*   coords, int32_t coordCount,
    int32_t fillRule,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a);

/*
 * Fill a path with a gaussian-blurred edge — the native primitive behind CSS
 * box-shadow. Caller passes the shadow shape already offset; `sigma` is the
 * SkMaskFilter blur sigma (≈ CSS blur radius / 2). Bypasses the broken Prism
 * DropShadow effect-composite path on Skia.
 */
OPENJFX_API int32_t openjfx_skia_surface_fill_path_blur(
    uintptr_t handle,
    const uint8_t* verbs,  int32_t verbCount,
    const float*   coords, int32_t coordCount,
    int32_t fillRule,
    float sigma,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a);

OPENJFX_API int32_t openjfx_skia_surface_stroke_path(
    uintptr_t handle,
    const uint8_t* verbs,  int32_t verbCount,
    const float*   coords, int32_t coordCount,
    int32_t fillRule,
    float width, int32_t cap, int32_t join, float miter,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a);

/* ===========================================================================
 * Text / glyph rendering.
 *
 * Typefaces are built from in-memory font-file bytes (TTF/OTF/TTC) and
 * exposed as opaque uintptr_t handles owned by the native side. Java
 * holds the handle and is responsible for typeface_destroy.
 *
 * surface_draw_glyphs renders a run of already-shaped glyphs: the
 * caller supplies physical glyph ids and an absolute baseline-origin
 * (x, y) per glyph in surface coordinates. One call builds one
 * SkTextBlob from one SkTypeface.
 * ===========================================================================
 */

/**
 * Builds an SkTypeface from a font file held entirely in memory
 * (`data` points at `length` bytes of TTF/OTF/TTC). Returns 0 on
 * failure or when Skia is not compiled in.
 */
OPENJFX_API uintptr_t openjfx_skia_typeface_create_from_data(
    const void* data, int32_t length);

/** Releases a typeface. Safe to call with a 0 handle (no-op). */
OPENJFX_API void openjfx_skia_typeface_destroy(uintptr_t handle);

/**
 * Draws `count` glyphs of `typefaceHandle` at `fontSize` onto the
 * surface. glyphIds[i]'s baseline origin is placed at (posX[i],
 * posY[i]) in surface coordinates. Color is RGBA. Returns 0 on
 * success, non-zero on failure (bad surface/typeface handle, bad
 * args, or a non-finite position).
 */
OPENJFX_API int32_t openjfx_skia_surface_draw_glyphs(
    uintptr_t surfaceHandle,
    uintptr_t typefaceHandle,
    float fontSize,
    const uint16_t* glyphIds,
    int32_t count,
    const float* posX,
    const float* posY,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a);

/*
 * surface_draw_glyphs_shader: as surface_draw_glyphs, but fills the glyph
 * coverage with an SkShader (gradient / image-pattern) instead of a solid
 * colour. Used for gradient/pattern-filled Text nodes.
 */
OPENJFX_API int32_t openjfx_skia_surface_draw_glyphs_shader(
    uintptr_t surfaceHandle,
    uintptr_t typefaceHandle,
    float fontSize,
    const uint16_t* glyphIds,
    int32_t count,
    const float* posX,
    const float* posY,
    uintptr_t shaderHandle, uint8_t alpha);

/* ===========================================================================
 * SkImageFilter lifecycle.
 *
 * Filters are SkImageFilter handles applied via save_layer. Pattern:
 *   filter = filter_create_blur(...)
 *   save_layer_with_filter(surface, filter)
 *   ... draw into surface ...
 *   surface_restore(surface)   // applies filter and composites
 *   filter_destroy(filter)
 * ===========================================================================
 */

OPENJFX_API uintptr_t openjfx_skia_filter_create_blur(
    float sigmaX, float sigmaY,
    int32_t tileMode);

OPENJFX_API uintptr_t openjfx_skia_filter_create_drop_shadow(
    float dx, float dy, float sigmaX, float sigmaY,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a);

OPENJFX_API uintptr_t openjfx_skia_filter_create_color_matrix(
    /* row-major 4x5 matrix: r' = m00*r + m01*g + m02*b + m03*a + m04, etc. */
    const float* matrix20);

/** Composes f1 and f2: result = f1(f2(input)). Refs both inputs. */
OPENJFX_API uintptr_t openjfx_skia_filter_compose(
    uintptr_t outerFilter, uintptr_t innerFilter);

/* ---- Additional SkImageFilter primitives ------------------------------ */

/** Produces *only* the drop-shadow (no source composition). Used by
 *  the JFX shadow chain (LinearConvolveShadow) — DropShadow's Effect
 *  class composites this back onto the source via Merge. */
OPENJFX_API uintptr_t openjfx_skia_filter_create_drop_shadow_only(
    float dx, float dy, float sigmaX, float sigmaY,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a);

/** SkImageFilters::Blend(mode, background, foreground). mode is the
 *  SkBlendMode int (same encoding as the bridge's BLEND_* constants).
 *  Either input may be 0 → means "use the source image". */
OPENJFX_API uintptr_t openjfx_skia_filter_create_blend(
    int32_t blendMode, uintptr_t background, uintptr_t foreground);

/** SkImageFilters::Merge(filters[]). Filter handles array; null entries
 *  ignored. */
OPENJFX_API uintptr_t openjfx_skia_filter_create_merge(
    const uintptr_t* filters, int32_t count);

/** SkImageFilters::Offset(dx, dy, input). input may be 0 → source. */
OPENJFX_API uintptr_t openjfx_skia_filter_create_offset(
    float dx, float dy, uintptr_t input);

/** SkImageFilters::Crop(rect, kClamp, input). input may be 0 → source. */
OPENJFX_API uintptr_t openjfx_skia_filter_create_crop(
    float x, float y, float w, float h, uintptr_t input);

/** SkImageFilters::Erode(rx, ry, input). Min-filter morphology. */
OPENJFX_API uintptr_t openjfx_skia_filter_create_erode(
    float rx, float ry, uintptr_t input);

/** SkImageFilters::Dilate(rx, ry, input). Max-filter morphology. */
OPENJFX_API uintptr_t openjfx_skia_filter_create_dilate(
    float rx, float ry, uintptr_t input);

/** SkImageFilters::MatrixTransform — applies a 3x3 affine/perspective
 *  matrix to the input. Row-major 9 floats. */
OPENJFX_API uintptr_t openjfx_skia_filter_create_matrix_transform(
    float m00, float m01, float m02,
    float m10, float m11, float m12,
    float m20, float m21, float m22,
    uintptr_t input);

/** SkImageFilters::DisplacementMap. channelX/Y: 0=R 1=G 2=B 3=A. */
OPENJFX_API uintptr_t openjfx_skia_filter_create_displacement_map(
    int32_t channelX, int32_t channelY, float scale,
    uintptr_t displacementFilter, uintptr_t input);

/** SkImageFilters::Image(image, samplingNearest=false). image handle
 *  is an SkImage handle (e.g. from image_create_raster). */
OPENJFX_API uintptr_t openjfx_skia_filter_create_image(uintptr_t imageHandle);

/** SkImageFilters::Shader(shader). shader handle is an SkShader handle
 *  (e.g. from shader_create_*). Used as a filter *source*. */
OPENJFX_API uintptr_t openjfx_skia_filter_create_shader(uintptr_t shaderHandle);

/* Lighting filters. lightX/Y/Z are the light position (or direction
 * for distant). targetX/Y/Z are the spot target (spot only).
 * colorRGBA is 0xRRGGBBAA. surfaceScale, kd/ks are material constants.
 * For specular variants, shininess controls the highlight exponent.
 * Spot adds falloffExponent and cutoffAngleDegrees. */
OPENJFX_API uintptr_t openjfx_skia_filter_create_distant_lit_diffuse(
    float dirX, float dirY, float dirZ,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a,
    float surfaceScale, float kd,
    uintptr_t input);

OPENJFX_API uintptr_t openjfx_skia_filter_create_point_lit_diffuse(
    float lx, float ly, float lz,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a,
    float surfaceScale, float kd,
    uintptr_t input);

OPENJFX_API uintptr_t openjfx_skia_filter_create_spot_lit_diffuse(
    float lx, float ly, float lz,
    float tx, float ty, float tz,
    float falloffExponent, float cutoffAngleDegrees,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a,
    float surfaceScale, float kd,
    uintptr_t input);

OPENJFX_API uintptr_t openjfx_skia_filter_create_distant_lit_specular(
    float dirX, float dirY, float dirZ,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a,
    float surfaceScale, float ks, float shininess,
    uintptr_t input);

OPENJFX_API uintptr_t openjfx_skia_filter_create_point_lit_specular(
    float lx, float ly, float lz,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a,
    float surfaceScale, float ks, float shininess,
    uintptr_t input);

OPENJFX_API uintptr_t openjfx_skia_filter_create_spot_lit_specular(
    float lx, float ly, float lz,
    float tx, float ty, float tz,
    float falloffExponent, float cutoffAngleDegrees,
    uint8_t r, uint8_t g, uint8_t b, uint8_t a,
    float surfaceScale, float ks, float shininess,
    uintptr_t input);

/** Wrap the current surface contents as an SkImage handle (refs the
 *  current snapshot). Use {@link openjfx_skia_image_destroy} to release.
 *  Lets a peer feed its already-rendered input through subsequent
 *  filter chains via filter_create_image. */
OPENJFX_API uintptr_t openjfx_skia_surface_snapshot_to_image(uintptr_t surfaceHandle);

OPENJFX_API void openjfx_skia_filter_destroy(uintptr_t handle);

/* ===========================================================================
 * SVG documents (modules/svg). Parse an SVG once into an opaque handle, then
 * render it many times at whatever device-pixel size a node resolved — output
 * is vector-exact at any zoom / DPI, never an upscaled raster. Handles are
 * owned by the native side and exposed as uintptr_t; the owning Java wrapper
 * (SvgImage) calls svg_destroy exactly once via a Cleaner. Every entry point
 * is guarded: a stale / freed handle is rejected, not dereferenced.
 * ===========================================================================
 */

/**
 * Parses UTF-8 SVG markup into an SkSVGDOM. The bytes are copied internally,
 * so the caller may free its buffer immediately. Returns a non-zero handle on
 * success, or 0 if Skia is not compiled in or the markup is malformed.
 */
OPENJFX_API uintptr_t openjfx_skia_svg_parse(const void* utf8, int32_t length);

/**
 * Writes the document's intrinsic width/height (from its width/height attrs
 * or viewBox) into outWidthHeight[0]/[1]. Returns 0 on success, -1 on a bad
 * or freed handle.
 */
OPENJFX_API int32_t openjfx_skia_svg_get_size(uintptr_t svgHandle, float* outWidthHeight);

/**
 * Renders the SVG directly onto the surface's CURRENT canvas, under its
 * CURRENT matrix and clip (the live device transform the caller set up), into
 * the logical box (x,y,w,h). Drawn as vectors — pixel-perfect at any zoom/DPI,
 * no resampling, no texture-size cap — and clipped to the box so it never
 * overflows. Composites background -> grid -> SVG -> optional tint. Colors are
 * 0xAARRGGBB; tintMode 0/1/2 = none/SRC_IN/MULTIPLY; gridCell<=0 disables grid.
 * Returns 0 on success, -1 on a bad handle / non-positive size.
 */
OPENJFX_API int32_t openjfx_skia_svg_render_in_place(
    uintptr_t surfaceHandle, uintptr_t svgHandle,
    float x, float y, float w, float h,
    int32_t bgArgb, int32_t tintArgb, int32_t tintMode,
    int32_t gridArgb, float gridCell, float gridLineWidth);

/** Releases an SVG handle. Safe to call with 0 or a stale handle. */
OPENJFX_API void openjfx_skia_svg_destroy(uintptr_t svgHandle);

/**
 * Pushes a save layer that, when popped via {@code surface_restore},
 * applies the given filter to all drawing performed on the layer
 * before compositing onto the parent surface.
 */
OPENJFX_API int32_t openjfx_skia_surface_save_layer_with_filter(
    uintptr_t handle, uintptr_t filterHandle);

/* ===========================================================================
 * SkShader lifecycle.
 *
 * Tile mode encoding (matches SkTileMode int values):
 *   0 = CLAMP, 1 = REPEAT, 2 = MIRROR, 3 = DECAL
 *
 * Stops layout: `positions` is a float[] of length nStops with values
 * in [0..1]; `colorsRGBA` is a uint32_t[] of length nStops where each
 * entry is 0xRRGGBBAA in memory (i.e. R is the low byte of the int
 * when read as little-endian).
 * ===========================================================================
 */

OPENJFX_API uintptr_t openjfx_skia_shader_create_linear_gradient(
    float x0, float y0, float x1, float y1,
    int32_t nStops,
    const float* positions,
    const uint32_t* colorsRGBA,
    int32_t tileMode);

OPENJFX_API uintptr_t openjfx_skia_shader_create_radial_gradient(
    float cx, float cy, float radius,
    int32_t nStops,
    const float* positions,
    const uint32_t* colorsRGBA,
    int32_t tileMode);

/**
 * Wraps an SkImage as an SkShader with the given tile modes for sampling.
 * The image-shader keeps its own ref to the image; you may dispose
 * the image handle after this call.
 */
OPENJFX_API uintptr_t openjfx_skia_shader_create_image(
    uintptr_t imageHandle,
    int32_t tileModeX, int32_t tileModeY);

/* ---- Shader-create variants with an explicit local matrix ----------------
 * The (m00..m12) args are a 2x3 affine matrix in the same column convention
 * as openjfx_skia_surface_begin_draw — see openjfx_skia_bridge.cpp for the
 * setAll mapping. Passes the matrix directly to SkShaders::*Gradient /
 * SkImage::makeShader as the SkShader local matrix, exactly mirroring
 * upstream WebCore/platform/graphics/skia/GradientSkia.cpp Gradient::shader
 * and GraphicsContextSkia::drawPattern.
 *
 * Without a local matrix the caller has to pre-transform the gradient
 * points/image-pattern phase, which is only equivalent for uniform
 * similarity transforms — for SVG / Canvas API gradients with rotation or
 * non-uniform scale the math diverges. The _lm entries are the correct
 * primitives; the older non-_lm entries are kept for Java-side callers.
 */
OPENJFX_API uintptr_t openjfx_skia_shader_create_linear_gradient_lm(
    float x0, float y0, float x1, float y1,
    int32_t nStops,
    const float* positions,
    const uint32_t* colorsRGBA,
    int32_t tileMode,
    float m00, float m01, float m02,
    float m10, float m11, float m12);

OPENJFX_API uintptr_t openjfx_skia_shader_create_radial_gradient_lm(
    float cx, float cy, float radius,
    int32_t nStops,
    const float* positions,
    const uint32_t* colorsRGBA,
    int32_t tileMode,
    float m00, float m01, float m02,
    float m10, float m11, float m12);

OPENJFX_API uintptr_t openjfx_skia_shader_create_image_lm(
    uintptr_t imageHandle,
    int32_t tileModeX, int32_t tileModeY,
    float m00, float m01, float m02,
    float m10, float m11, float m12);

OPENJFX_API void openjfx_skia_shader_destroy(uintptr_t shaderHandle);

/* ---- Drawing with a shader (fill style) ---------------------------------
 * The color args are still RGBA, but they only contribute to the
 * paint's alpha channel — Skia multiplies the shader output by the
 * paint's alpha. Pass a=255, r=g=b=0 (or anything; Skia ignores rgb)
 * for the unmodified shader.
 */

OPENJFX_API int32_t openjfx_skia_surface_fill_rect_shader(
    uintptr_t handle,
    float x, float y, float w, float h,
    uintptr_t shaderHandle, uint8_t alpha);

OPENJFX_API int32_t openjfx_skia_surface_fill_round_rect_shader(
    uintptr_t handle,
    float x, float y, float w, float h,
    float arcW, float arcH,
    uintptr_t shaderHandle, uint8_t alpha);

OPENJFX_API int32_t openjfx_skia_surface_fill_oval_shader(
    uintptr_t handle,
    float x, float y, float w, float h,
    uintptr_t shaderHandle, uint8_t alpha);

OPENJFX_API int32_t openjfx_skia_surface_fill_path_shader(
    uintptr_t handle,
    const uint8_t* verbs,  int32_t verbCount,
    const float*   coords, int32_t coordCount,
    int32_t fillRule,
    uintptr_t shaderHandle, uint8_t alpha);

/* ---- Stroke-with-shader operations ---------------------------------- */

OPENJFX_API int32_t openjfx_skia_surface_stroke_rect_shader(
    uintptr_t handle,
    float x, float y, float w, float h,
    float width, int32_t cap, int32_t join, float miter,
    uintptr_t shaderHandle, uint8_t alpha);

OPENJFX_API int32_t openjfx_skia_surface_stroke_round_rect_shader(
    uintptr_t handle,
    float x, float y, float w, float h,
    float arcW, float arcH,
    float width, int32_t cap, int32_t join, float miter,
    uintptr_t shaderHandle, uint8_t alpha);

OPENJFX_API int32_t openjfx_skia_surface_stroke_oval_shader(
    uintptr_t handle,
    float x, float y, float w, float h,
    float width, int32_t cap, int32_t join, float miter,
    uintptr_t shaderHandle, uint8_t alpha);

OPENJFX_API int32_t openjfx_skia_surface_stroke_line_shader(
    uintptr_t handle,
    float x1, float y1, float x2, float y2,
    float width, int32_t cap, int32_t join, float miter,
    uintptr_t shaderHandle, uint8_t alpha);

OPENJFX_API int32_t openjfx_skia_surface_stroke_path_shader(
    uintptr_t handle,
    const uint8_t* verbs,  int32_t verbCount,
    const float*   coords, int32_t coordCount,
    int32_t fillRule,
    float width, int32_t cap, int32_t join, float miter,
    uintptr_t shaderHandle, uint8_t alpha);

/* ===========================================================================
 * SkImage lifecycle (handle-based).
 * Like surfaces, images are owned by the native side; Java holds an
 * opaque uintptr_t and is responsible for destroy.
 *
 * ColorType encoding:
 *   0 = RGBA_8888_PREMUL
 *   1 = BGRA_8888_PREMUL    (matches Prism INT_ARGB_PRE & BYTE_BGRA_PRE on LE)
 *   2 = GRAY_8              (single-channel grayscale)
 * ===========================================================================
 */

OPENJFX_API uintptr_t openjfx_skia_image_create_raster(
    int32_t width, int32_t height,
    int32_t rowBytes,
    const void* pixels,
    int32_t colorType);

/**
 * Creates an SkImage from three planar YUV (I420 / YCbCr 4:2:0) planes.
 * The chroma planes are half-width and half-height of the Y plane.
 *
 * Implementation: native C++ converts YUV→BGRA in a fast straight-line
 * loop (BT.709 limited-range matrix coefficients with clamping) and
 * hands the resulting BGRA buffer to SkImages::RasterFromPixmapCopy.
 * Avoids both GStreamer's video converter AND Skia's GPU YUV→RGB
 * shader (the latter produced an inexplicable amber tint with our
 * particular plane layout). All Skia rendering downstream is unchanged.
 *
 * colorSpace: 0 = BT.601 (SDR, default for SD content), 1 = BT.709
 * (HD, default for 720p+), 2 = BT.2020 (HDR / wide gamut, 4K+).
 *
 * Returns 0 on failure.
 */
OPENJFX_API uintptr_t openjfx_skia_image_create_yuv_i420(
    const void* yPlane,  int32_t yStride,
    const void* uPlane,  int32_t uStride,
    const void* vPlane,  int32_t vStride,
    int32_t width, int32_t height,
    int32_t colorSpace);

/* ---------------------------------------------------------------------------
 * HDR-aware YUV upload + present-time tone mapping (full GPU path).
 *
 * Takes a complete colour descriptor and:
 *   1. Tags the SkImage with the correct source SkColorSpace
 *      (transfer + gamut). When the image is later drawn into an
 *      sRGB SkSurface, Skia's colour engine applies the transfer
 *      EOTF and the gamut matrix automatically.
 *   2. For HDR sources (PQ / HLG), runs a one-shot SkRuntimeEffect
 *      pass that performs BT.2390 perceptual tone-mapping from the
 *      source peak luminance to the destination's SDR peak. The
 *      tone-map runs on the GPU when a GrDirectContext is available,
 *      on the CPU rasteriser otherwise — Skia picks transparently.
 *   3. Always returns an sRGB-tagged SkImage so the rest of the
 *      pipeline never has to know about HDR.
 *
 * Parameters:
 *   yuvMatrix    0 = BT.601  limited, 1 = BT.709  limited,
 *                2 = BT.2020 limited, 3 = JPEG full-range
 *   transferFn   {@link OPENJFX_SKIA_TFN_*} below
 *   primaries    {@link OPENJFX_SKIA_PRI_*} below
 *   fullRange    0 = limited (16-235 / 16-240), 1 = full (0-255)
 *   srcPeakNits  Source peak luminance in nits. 0 = autopick from
 *                transferFn (1000 for PQ, 1000 for HLG, 100 for SDR).
 *   dstPeakNits  Destination peak luminance in nits. 0 = 100 (sRGB).
 *
 * Returns 0 on failure (Skia not compiled in, plane args invalid,
 * runtime-effect compilation failed). Java callers fall back to the
 * pure-Java tone-map path on 0.
 */
#define OPENJFX_SKIA_TFN_SRGB    0   /* gamma ~2.2, SDR default                  */
#define OPENJFX_SKIA_TFN_REC709  1   /* BT.709 OETF                              */
#define OPENJFX_SKIA_TFN_PQ      2   /* SMPTE ST 2084 PQ HDR                     */
#define OPENJFX_SKIA_TFN_HLG     3   /* ARIB STD-B67 / BT.2100 HLG HDR           */
#define OPENJFX_SKIA_TFN_LINEAR  4   /* linear-light (debug / pass-through)      */

#define OPENJFX_SKIA_PRI_SRGB    0   /* sRGB / Rec.709 primaries                 */
#define OPENJFX_SKIA_PRI_REC2020 1   /* BT.2020 / Rec.2100 wide gamut            */
#define OPENJFX_SKIA_PRI_DCI_P3  2   /* DCI-P3 / Display P3                      */
#define OPENJFX_SKIA_PRI_REC601  3   /* BT.601 SD primaries (PAL / NTSC)         */

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
    float   dstPeakNits);

/**
 * Returns 1 if the bridge has a working HDR tone-mapping pipeline
 * (SkRuntimeEffect compiled successfully on first call), 0 if HDR
 * inputs must be handled by the Java CPU fallback. Compiled-once-
 * lazy-init: the first call may take a few hundred microseconds; all
 * subsequent calls return the cached result.
 */
OPENJFX_API int32_t openjfx_skia_has_hdr_pipeline(void);

/**
 * Wraps an existing OpenGL texture as a borrowed-reference SkImage.
 *
 * Used by the M3-B media zero-copy path: WGL_NV_DX_interop2 produces a
 * GL texture name that aliases the same VRAM as a decoder-output D3D11
 * texture. Skia draws this GL texture directly via Ganesh GL — no pixel
 * data ever traverses the CPU.
 *
 * The texture must be GL_TEXTURE_2D with format GL_RGBA8 (the WGL
 * interop spec only supports standard RGB/RGBA color formats; NV12 is
 * not interop-shareable, so a D3D11 VideoProcessor pass earlier in the
 * chain must convert decoder output to RGBA before registration).
 *
 * Ownership: Skia BORROWS the texture — it does not delete the GL name
 * when the SkImage is destroyed. The caller is responsible for
 * unregistering the WGL interop handle and (only via the interop module)
 * deleting the GL texture once Skia has finished sampling. Practically:
 * destroy the SkImage handle returned here BEFORE calling
 * openjfx_skia_d3d11_interop_unregister_texture.
 *
 * @param glTextureName the GL texture name from glGenTextures /
 *                      wglDXRegisterObjectNV.
 * @param width / height texture dimensions in pixels.
 * @return SkImage handle, or 0 on failure (no GPU context, invalid args).
 */
OPENJFX_API uintptr_t openjfx_skia_image_create_from_gl_texture(
    uint32_t glTextureName,
    int32_t width, int32_t height);

OPENJFX_API void openjfx_skia_image_destroy(uintptr_t handle);

OPENJFX_API int32_t openjfx_skia_image_width(uintptr_t handle);
OPENJFX_API int32_t openjfx_skia_image_height(uintptr_t handle);

/* ===========================================================================
 * Encoding SkImage handles to compressed byte streams (PNG / JPEG / WEBP).
 * The bridge mallocs an SkData buffer, returns its raw pointer + size to
 * the caller, and the caller must call openjfx_skia_buffer_free on the
 * pointer when done. No CPU-side AWT/ImageIO required.
 *
 * Format codes:
 *   0 = PNG  (lossless; quality is ignored, pass 100)
 *   1 = JPEG (lossy; quality in [0..100], 92 is a sane default)
 *   2 = WEBP (lossy; quality in [0..100], 80 is a sane default)
 *
 * On success: writes the malloc'd pointer into *outPtr, the size into
 * *outSize, and returns 0. On failure (unsupported format, encode error,
 * Skia not compiled in): writes 0 to both outputs and returns non-zero.
 * =========================================================================== */
OPENJFX_API int32_t openjfx_skia_image_encode(
    uintptr_t handle,
    int32_t   format,
    int32_t   quality,
    uintptr_t* outPtr,
    int32_t*   outSize);

/** Frees a buffer returned by openjfx_skia_image_encode. Safe on 0. */
OPENJFX_API void openjfx_skia_buffer_free(uintptr_t ptr);

/* ===========================================================================
 * Canvas state stack on a surface.
 * Used to apply per-draw transforms (e.g. positioning glyphs).
 * ===========================================================================
 */

/** Pushes a save level. Returns the new save count, or -1 on failure. */
OPENJFX_API int32_t openjfx_skia_surface_save(uintptr_t handle);

/** Pops one save level. */
OPENJFX_API int32_t openjfx_skia_surface_restore(uintptr_t handle);

/**
 * Push a Skia saveLayer with alpha-only composition. Subsequent draws
 * go into an internal layer that Skia allocates; matching surface_restore
 * composites the layer back with the given alpha. Used by
 * WCGraphicsPrismContext.SkiaTransparencyLayer for WebKit
 * beginTransparencyLayer / CSS opacity on Skia surfaces.
 *   alpha: 0..255 (clamped).
 */
OPENJFX_API int32_t openjfx_skia_surface_save_layer_alpha(
    uintptr_t handle, int32_t alpha);

/**
 * Apply an arbitrary path clip to the current canvas state. Must be
 * bracketed by surface_save / surface_restore on the caller's side so
 * the clip can be unwound — clipPath intersects with (or, for
 * clipOp == 1, subtracts from) the current clip and persists until the
 * matching restore. Used by WCGraphicsPrismContext.SkiaClipLayer to
 * honor CSS border-radius / clip-path on Skia surfaces.
 *   clipOp:   0 = intersect, 1 = difference
 *   fillRule: 0 = even-odd,  1 = winding
 */
OPENJFX_API int32_t openjfx_skia_surface_clip_path(
    uintptr_t handle,
    const uint8_t* verbs, int32_t verbCount,
    const float*   coords, int32_t coordCount,
    int32_t fillRule, int32_t clipOp);

/**
 * Batched per-draw setup. Single FFM crossing equivalent to:
 *   surface_save()
 *   if (hasClip) surface_clip_rect(...) under identity CTM
 *   surface_set_matrix(m)
 *   surface_set_blend_mode(blendMode)
 *   surface_set_extra_alpha(extraAlpha)
 * Pairs with surface_end_draw.
 */
OPENJFX_API int32_t openjfx_skia_surface_begin_draw(
    uintptr_t handle,
    float m00, float m01, float m02,
    float m10, float m11, float m12,
    int32_t clipX, int32_t clipY, int32_t clipW, int32_t clipH,
    int32_t hasClip,
    int32_t blendMode,
    float   extraAlpha);

/** Pops the save level pushed by begin_draw. Single FFM crossing. */
OPENJFX_API int32_t openjfx_skia_surface_end_draw(uintptr_t handle);

/** Applies a translation to the current matrix. */
OPENJFX_API int32_t openjfx_skia_surface_translate(uintptr_t handle, float dx, float dy);

/**
 * Replaces the current canvas matrix with the given 2D affine
 * transform [m00 m01 m02; m10 m11 m12]. Equivalent to
 * SkCanvas::setMatrix.
 */
OPENJFX_API int32_t openjfx_skia_surface_set_matrix(
    uintptr_t handle,
    float m00, float m01, float m02,
    float m10, float m11, float m12);

/**
 * Intersects the current clip with the given rect. Use save() before
 * and restore() after if you want this to be reversible.
 */
OPENJFX_API int32_t openjfx_skia_surface_clip_rect(
    uintptr_t handle,
    float x, float y, float w, float h);

/**
 * Selects the SkBlendMode used when subsequently drawn paints
 * compose with the surface. Encoded as the SkBlendMode int value
 * (0 = Clear, 1 = Src, 2 = Dst, 3 = SrcOver, ...).
 *
 * Note: Skia applies blend mode per SkPaint, not per canvas. This
 * call updates a sticky default that the bridge applies to every
 * subsequent fill/stroke operation until changed again.
 */
OPENJFX_API int32_t openjfx_skia_surface_set_blend_mode(
    uintptr_t handle,
    int32_t mode);

/**
 * Sets the extra alpha multiplier applied to every fill/stroke paint
 * on this surface (range [0..1]). Multiplies color/shader alpha at
 * draw time; sticky until changed. 1.0 means "no scaling".
 */
OPENJFX_API int32_t openjfx_skia_surface_set_extra_alpha(
    uintptr_t handle, float alpha);

/* ===========================================================================
 * Drawing images onto a surface.
 * ===========================================================================
 */

/** Draws the entire image into rect (dx,dy,dw,dh) on the surface. */
OPENJFX_API int32_t openjfx_skia_surface_draw_image(
    uintptr_t surfaceHandle,
    uintptr_t imageHandle,
    float dx, float dy, float dw, float dh);

/**
 * Draws another SkSurface onto this one (snapshot-and-blit).
 * Used when an off-screen RTTexture (e.g. a node-cache) is drawn
 * as a texture via SkiaGraphics.drawTexture().
 */
OPENJFX_API int32_t openjfx_skia_surface_draw_surface(
    uintptr_t dstSurfaceHandle,
    uintptr_t srcSurfaceHandle,
    float sx, float sy, float sw, float sh,
    float dx, float dy, float dw, float dh);

/**
 * Reflection's vertical-flip + opacity-gradient surface draw — the GPU peer
 * for SkiaGraphics.drawTextureVO() (only caller: PrReflectionPeer). Maps the
 * source quad to the vertically inverted destination quad (the mirror) and
 * modulates alpha with a vertical white gradient: topOpacity at dy1, botOpacity
 * at dy2. Matches the Prism reference (J2DPrismGraphics.drawTextureVO).
 */
OPENJFX_API int32_t openjfx_skia_surface_draw_surface_vo(
    uintptr_t dstSurfaceHandle,
    uintptr_t srcSurfaceHandle,
    float dx1, float dy1, float dx2, float dy2,
    float sx1, float sy1, float sx2, float sy2,
    float topOpacity, float botOpacity);

/**
 * Draws a sub-rect of the image into a destination rect on the surface.
 * Source rect is in image pixels (sx, sy, sw, sh); destination is in
 * surface pixels (dx, dy, dw, dh).
 */
OPENJFX_API int32_t openjfx_skia_surface_draw_image_rect(
    uintptr_t surfaceHandle,
    uintptr_t imageHandle,
    float sx, float sy, float sw, float sh,
    float dx, float dy, float dw, float dh);

/**
 * Composites a raw BGRA8888 (premultiplied, top-down) pixel buffer onto the
 * canvas resolved from surfaceHandle, scaling srcW x srcH into the dst rect.
 * The pixels are copied into a Skia-managed image, so the caller's buffer may
 * be reused immediately. In-process path for off-screen Blink WebView frames.
 * Returns 0 on success, non-0 on failure.
 */
OPENJFX_API int32_t openjfx_skia_surface_draw_bgra(
    uintptr_t surfaceHandle,
    uintptr_t pixels,
    int32_t srcW, int32_t srcH, int32_t srcStride,
    int32_t dstX, int32_t dstY, int32_t dstW, int32_t dstH);

/**
 * Reads a rectangle of pixels out of the surface into `dst`. Pixel
 * format is RGBA8888 premultiplied; rows are tightly packed
 * (rowBytes = w * 4). Returns 0 on success.
 */
OPENJFX_API int32_t openjfx_skia_surface_read_pixels(
    uintptr_t handle,
    void*     dst,
    int32_t   x,
    int32_t   y,
    int32_t   w,
    int32_t   h);

/**
 * Replaces the SkSurface's backing memory with a caller-provided
 * pixel buffer (no copy). Subsequent SkCanvas draws on this surface
 * land directly in `pixels`, so a follow-up readPixels is a no-op.
 *
 * The pixel layout is BGRA8888 premultiplied (matches Glass
 * INT_ARGB_PRE). Returns 0 on success.
 *
 * The previously-rendered contents are NOT carried over. Caller is
 * responsible for re-rendering or first reading prior contents
 * into `pixels`.
 */
OPENJFX_API int32_t openjfx_skia_surface_replace_backing_argb(
    uintptr_t handle,
    void*     pixels,
    int32_t   width,
    int32_t   height,
    int32_t   rowBytes);

/**
 * Reads pixels in BGRA8888 layout directly — bytes (B,G,R,A) on
 * little-endian, packed int 0xAARRGGBB. Matches Glass's
 * INT_ARGB_PRE layout exactly, so the Java side can memcpy without
 * any channel swap. Used by the Quantum upload path.
 */
OPENJFX_API int32_t openjfx_skia_surface_read_pixels_argb(
    uintptr_t handle,
    void*     dst,
    int32_t   x,
    int32_t   y,
    int32_t   w,
    int32_t   h);

/**
 * Dirty-rect variant of surface_read_pixels_argb for the partial-present
 * path: reads the (x,y,w,h) sub-rect into the FULL-FRAME buffer `dst`
 * (stride `dstRowBytes`), landing it at its natural offset so the buffer
 * stays a coherent full frame. Returns 0 on success.
 */
OPENJFX_API int32_t openjfx_skia_surface_read_pixels_argb_stride(
    uintptr_t handle,
    void*     dst,
    int32_t   dstRowBytes,
    int32_t   x,
    int32_t   y,
    int32_t   w,
    int32_t   h);

/**
 * Paint-before-show: GDI-blit the window surface's just-rendered frame onto the
 * window's DWM redirection bitmap (the surface the OS show animation reveals),
 * so a StageStyle.CUSTOM window appears already showing real UI. Call once, on
 * the render thread, AFTER the pre-show paint and BEFORE Present + ShowWindow.
 * Returns 0 on success, non-zero on failure (caller degrades to prior behavior);
 * -1 off-Windows / without Skia.
 */
OPENJFX_API int32_t openjfx_skia_surface_prime_window(uintptr_t handle);

/* ===========================================================================
 * Buffer-based smoke helpers (no surface handle). Useful for tests that
 * want to verify Skia draws into a Java-owned buffer.
 * ===========================================================================
 */

/**
 * Software SkSurface smoke test:
 *   - wraps `pixels` (RGBA8888, premultiplied, row-major) as a raster
 *     SkSurface of size (width × height) with stride `rowBytes`,
 *   - clears to (r,g,b,a),
 *   - returns 0 on success, non-zero on failure.
 *
 * Stub implementation (no Skia): writes the clear color to `pixels`
 * directly so Java can verify the bridge round-trips.
 */
OPENJFX_API int32_t openjfx_skia_clear_buffer(
    void*    pixels,
    int32_t  width,
    int32_t  height,
    int32_t  rowBytes,
    uint8_t  r,
    uint8_t  g,
    uint8_t  b,
    uint8_t  a);

/**
 * Draws an axis-aligned filled rectangle on a raster SkSurface wrapping
 * `pixels`. Coordinates are integer pixels (Skia internally uses float).
 * Returns 0 on success, non-zero on failure.
 *
 * Stub implementation: rasterizes the rectangle directly into `pixels`
 * without anti-aliasing. Useful for FFM smoke tests; not a substitute
 * for the real Skia path.
 */
OPENJFX_API int32_t openjfx_skia_fill_rect(
    void*    pixels,
    int32_t  width,
    int32_t  height,
    int32_t  rowBytes,
    int32_t  x,
    int32_t  y,
    int32_t  rectW,
    int32_t  rectH,
    uint8_t  r,
    uint8_t  g,
    uint8_t  b,
    uint8_t  a);

/* ===========================================================================
 * SkPicture record / replay (Task #31 — static-subtree caching).
 *
 * A picture is a recorded stream of canvas draw commands. Re-playing a
 * picture is roughly a single GPU pass with no per-draw CPU cost — so
 * mostly-static UI subtrees (TableView cells, sidebar, etc.) can pay
 * the full draw cost once and then run "free" until their inputs
 * change.
 *
 * Lifecycle:
 *
 *   1. recorder = picture_recorder_create();
 *   2. handle   = picture_recorder_begin(recorder, x, y, w, h);
 *                 // 'handle' is the recording-target SkSurface handle,
 *                 // interchangeable with the regular window surface for
 *                 // the surface_* draw entries.
 *   3. ... issue draw commands via the surface_* API ...
 *   4. picture  = picture_recorder_finish(recorder);
 *                 // recorder is reusable for the next recording.
 *   5. surface_draw_picture(targetSurface, picture, dx, dy);
 *                 // replay onto a real surface; can be called many times.
 *   6. picture_destroy(picture);
 *   7. picture_recorder_destroy(recorder);
 * ===========================================================================
 */

/** Creates a new SkPictureRecorder. Returns a handle, or 0 on failure. */
OPENJFX_API uintptr_t openjfx_skia_picture_recorder_create(void);

/** Begins recording on an existing recorder. The (x,y,w,h) tuple
 *  defines the recording-bounds rectangle that Skia uses as a clip
 *  for the recording.
 *
 *  Returns a SURFACE handle (wrapping the recorder's internal canvas)
 *  that callers feed to the regular surface_* draw entries. The
 *  returned handle is owned by the recorder and becomes invalid the
 *  moment {@link openjfx_skia_picture_recorder_finish} is called;
 *  callers must NOT destroy it via surface_destroy. Returns 0 on
 *  failure. */
OPENJFX_API uintptr_t openjfx_skia_picture_recorder_begin(
    uintptr_t recorderHandle,
    float x, float y, float w, float h);

/** Finalizes the current recording and returns an SkPicture handle.
 *  The recorder is reusable for the next recording.
 *  Returns 0 if the recorder is not currently recording or finalize
 *  failed. */
OPENJFX_API uintptr_t openjfx_skia_picture_recorder_finish(
    uintptr_t recorderHandle);

/** Releases an SkPicture handle. */
OPENJFX_API void openjfx_skia_picture_destroy(uintptr_t pictureHandle);

/** Releases an SkPictureRecorder handle. */
OPENJFX_API void openjfx_skia_picture_recorder_destroy(uintptr_t recorderHandle);

/** Replays a recorded picture onto `targetSurface`, translated by
 *  (dx, dy) in the target's coordinate system. The target's current
 *  clip and matrix apply. Returns 0 on success. */
OPENJFX_API int32_t openjfx_skia_surface_draw_picture(
    uintptr_t targetSurface,
    uintptr_t pictureHandle,
    float dx,
    float dy);

#ifdef __cplusplus
}
#endif

#endif /* OPENJFX_SKIA_BRIDGE_H */
