package com.sun.prism.skia.impl;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FFM bindings to the {@code openjfx_skia_shared} native library.
 *
 * <p>The native library exports a small C ABI defined in
 * {@code openjfx_skia_bridge.h}. The library may have been compiled
 * with real Skia integration (when SKIA_HOME was set at build time)
 * or as a stub fallback; {@link #hasSkia()} reflects which.</p>
 *
 * <p>Loaded once on first use; subsequent calls reuse the same
 * {@link Linker} / handles. The library handle is held by a
 * {@code shared} arena so it lives for the JVM lifetime.</p>
 */
public final class NativeBridge {

    private static final String LIB_BASENAME = "openjfx_skia_shared";

    private static final Linker LINKER = Linker.nativeLinker();

    private static final SymbolLookup LOOKUP = loadLibrary();

    private static final MethodHandle MH_VERSION = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_version"),
        FunctionDescriptor.of(ValueLayout.ADDRESS));

    private static final MethodHandle MH_HAS_SKIA = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_has_skia"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT));

    // Optional: present only on builds that ship the device-loss guard. find() (not
    // findOrThrow) so an older native lib doesn't break class init — absent => false.
    private static final MethodHandle MH_DEVICE_LOST = LOOKUP.find("openjfx_skia_device_lost")
        .map(s -> LINKER.downcallHandle(s, FunctionDescriptor.of(ValueLayout.JAVA_INT)))
        .orElse(null);

    private static final MethodHandle MH_DEVICE_RECOVER = LOOKUP.find("openjfx_skia_device_recover")
        .map(s -> LINKER.downcallHandle(s, FunctionDescriptor.of(ValueLayout.JAVA_INT)))
        .orElse(null);

    // Optional: GPU backend selection. find() so an older native lib without these
    // symbols still loads (the selector then degrades to env-var/AUTO behaviour).
    private static final MethodHandle MH_SET_GPU_BACKEND = LOOKUP.find("openjfx_skia_set_gpu_backend")
        .map(s -> LINKER.downcallHandle(s, FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT)))
        .orElse(null);

    private static final MethodHandle MH_GET_ACTIVE_BACKEND = LOOKUP.find("openjfx_skia_get_active_backend")
        .map(s -> LINKER.downcallHandle(s, FunctionDescriptor.of(ValueLayout.JAVA_INT)))
        .orElse(null);

    /**
     * Backend preference / active-backend codes for {@link #setGpuBackend(int)}
     * and {@link #activeBackend()}. METAL and VULKAN are reserved for the
     * per-platform backends on the roadmap; until their native paths land the
     * selector treats them as {@link #BACKEND_AUTO}.
     */
    public static final int BACKEND_AUTO   = 0;
    public static final int BACKEND_GL     = 1;
    public static final int BACKEND_D3D12  = 2;
    public static final int BACKEND_METAL  = 3;
    public static final int BACKEND_VULKAN = 4;

    private static final MethodHandle MH_CLEAR_BUFFER = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_clear_buffer"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,    // return: status
            ValueLayout.ADDRESS,     // pixels
            ValueLayout.JAVA_INT,    // width
            ValueLayout.JAVA_INT,    // height
            ValueLayout.JAVA_INT,    // rowBytes
            ValueLayout.JAVA_BYTE,   // r
            ValueLayout.JAVA_BYTE,   // g
            ValueLayout.JAVA_BYTE,   // b
            ValueLayout.JAVA_BYTE)); // a

    // ---- SkSurface lifecycle ------------------------------------------------

    private static final MethodHandle MH_SURFACE_CREATE_RASTER = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_create_raster"),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,    // return: uintptr_t handle (as pointer)
            ValueLayout.JAVA_INT,   // width
            ValueLayout.JAVA_INT)); // height

    // Optional: BGRA raster variant for the READBACK present tier (swizzle-free
    // readback). find() so an older native lib degrades to the RGBA path.
    private static final MethodHandle MH_SURFACE_CREATE_RASTER_BGRA =
        LOOKUP.find("openjfx_skia_surface_create_raster_bgra")
            .map(s -> LINKER.downcallHandle(s, FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT)))
            .orElse(null);

    private static final MethodHandle MH_SURFACE_CREATE_GPU = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_create_gpu"),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,    // return: uintptr_t handle (0 if GPU unavailable)
            ValueLayout.JAVA_INT,   // width
            ValueLayout.JAVA_INT)); // height

    private static final MethodHandle MH_SURFACE_CREATE_WINDOW_GPU = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_create_window_gpu"),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,    // return: uintptr_t handle (0 = fall back)
            ValueLayout.ADDRESS,    // HWND
            ValueLayout.JAVA_INT,   // width
            ValueLayout.JAVA_INT)); // height

    private static final MethodHandle MH_SURFACE_PRESENT_WINDOW = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_present_window"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    private static final MethodHandle MH_SURFACE_PRIME_WINDOW = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_prime_window"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    // Per-window monitor refresh-rate query for the multi-monitor
    // present-rate cap. Returns Hz, with 0 meaning "unknown".
    //
    // OPTIONAL symbol: uses LOOKUP.find (Optional<MemorySegment>) and
    // NOT findOrThrow. A NativeBridge instance built against an older
    // openjfx_skia_shared.dll (e.g. dev cycles where Java rebuilt
    // ahead of native) would fail this entire class's <clinit> if we
    // used findOrThrow — taking the whole Skia pipeline down. With
    // an optional handle, missing symbol just means "feature
    // unavailable, fallback to 0/unknown" which PresentingPainter
    // already routes to the default 144 Hz cap.
    private static final MethodHandle MH_WINDOW_GET_REFRESH_HZ =
        LOOKUP.find("openjfx_skia_window_get_refresh_hz")
              .map(seg -> LINKER.downcallHandle(seg,
                  FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)))
              .orElse(null);

    private static final MethodHandle MH_SURFACE_CREATE_SWAP_CHAIN_D3D = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_create_swap_chain_d3d"),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,    // return: uintptr_t handle (0 if D3D unavailable)
            ValueLayout.ADDRESS,    // HWND
            ValueLayout.JAVA_INT,   // width
            ValueLayout.JAVA_INT)); // height

    private static final MethodHandle MH_SURFACE_PRESENT_WINDOW_D3D = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_present_window_d3d"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    // Per-frame "wait for back buffer + waitable" entry, called before
    // any drawing into a D3D-swap-chain surface.
    private static final MethodHandle MH_SURFACE_BEGIN_FRAME_D3D = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_begin_frame_d3d"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    // In-place resize for a D3D swap chain via IDXGISwapChain::ResizeBuffers.
    private static final MethodHandle MH_SURFACE_RESIZE_D3D = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_resize_d3d"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,   // return: 0 on success
            ValueLayout.ADDRESS,    // handle
            ValueLayout.JAVA_INT,   // new width
            ValueLayout.JAVA_INT)); // new height

    // In-place re-wrap of FBO 0 for a GL direct-present surface.
    // Avoids the destroy-and-recreate path that previously fired on
    // every WM_SIZE tick during a drag-resize.
    private static final MethodHandle MH_SURFACE_RESIZE_GL = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_resize_gl"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,   // return: 0 on success
            ValueLayout.ADDRESS,    // handle
            ValueLayout.JAVA_INT,   // new width
            ValueLayout.JAVA_INT)); // new height

    private static final MethodHandle MH_SURFACE_DESTROY = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_destroy"),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private static final MethodHandle MH_SURFACE_WIDTH = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_width"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private static final MethodHandle MH_SURFACE_HEIGHT = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_height"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private static final MethodHandle MH_SURFACE_CLEAR = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_clear"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE));

    private static final MethodHandle MH_SURFACE_FILL_RECT = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_fill_rect"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE));

    // ---- Filled shape primitives -------------------------------------------

    private static final FunctionDescriptor FD_FILLED_RRECT = FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,                                       // handle
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,            // x, y
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,            // w, h
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,            // arcW, arcH
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE);             // RGBA

    private static final FunctionDescriptor FD_FILLED_OVAL = FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE);

    // Stroke params: width (float), cap (int), join (int), miter (float).
    private static final FunctionDescriptor FD_STROKE_RECT = FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,   ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE);

    private static final FunctionDescriptor FD_STROKE_RRECT = FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,   ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE);

    private static final FunctionDescriptor FD_STROKE_LINE = FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,   ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE);

    private static final MethodHandle MH_SURFACE_FILL_ROUND_RECT = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_fill_round_rect"), FD_FILLED_RRECT);

    private static final MethodHandle MH_SURFACE_FILL_OVAL = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_fill_oval"), FD_FILLED_OVAL);

    private static final MethodHandle MH_SURFACE_STROKE_RECT = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_stroke_rect"), FD_STROKE_RECT);

    private static final MethodHandle MH_SURFACE_STROKE_ROUND_RECT = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_stroke_round_rect"), FD_STROKE_RRECT);

    private static final MethodHandle MH_SURFACE_STROKE_OVAL = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_stroke_oval"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,   ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE));

    private static final MethodHandle MH_SURFACE_STROKE_LINE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_stroke_line"), FD_STROKE_LINE);

    // ---- Arbitrary path ----------------------------------------------------

    private static final MethodHandle MH_SURFACE_FILL_PATH = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_fill_path"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,                                   // surface handle
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,             // verbs ptr, count
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,             // coords ptr, count
            ValueLayout.JAVA_INT,                                  // fillRule
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE));        // RGBA

    private static final MethodHandle MH_SURFACE_FILL_PATH_BLUR = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_fill_path_blur"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,                                   // surface handle
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,             // verbs ptr, count
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,             // coords ptr, count
            ValueLayout.JAVA_INT,                                  // fillRule
            ValueLayout.JAVA_FLOAT,                                // blur sigma
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE));        // RGBA

    // ---- SkImage lifecycle ------------------------------------------------

    /** Color type constants matching the bridge enum (see header). */
    public static final int CT_RGBA_8888_PREMUL = 0;
    public static final int CT_BGRA_8888_PREMUL = 1;
    public static final int CT_GRAY_8           = 2;
    public static final int CT_ALPHA_8          = 3;
    public static final int CT_RGB_888x         = 4;   // BYTE_RGB → kRGB_888x_SkColorType

    private static final MethodHandle MH_IMAGE_CREATE_RASTER = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_image_create_raster"),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,                                   // returns image handle
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,            // w, h
            ValueLayout.JAVA_INT,                                  // rowBytes
            ValueLayout.ADDRESS,                                   // pixels
            ValueLayout.JAVA_INT));                                // colorType

    /** YUV planar (I420) upload — Skia does GPU YUV→RGB at sample time.
     *  Optional symbol: older native libs may not have it. */
    private static final MethodHandle MH_IMAGE_CREATE_YUV_I420 =
        LOOKUP.find("openjfx_skia_image_create_yuv_i420")
              .map(seg -> LINKER.downcallHandle(seg,
                  FunctionDescriptor.of(
                      ValueLayout.ADDRESS,                          // returns image handle
                      ValueLayout.ADDRESS, ValueLayout.JAVA_INT,    // yPlane, yStride
                      ValueLayout.ADDRESS, ValueLayout.JAVA_INT,    // uPlane, uStride
                      ValueLayout.ADDRESS, ValueLayout.JAVA_INT,    // vPlane, vStride
                      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,   // w, h
                      ValueLayout.JAVA_INT)))                       // colorSpace
              .orElse(null);

    /** YUV colorspace tag matching the native impl. JPEG/full range is
     *  the GStreamer software-decoder default; the BT.* variants are
     *  limited (TV) range. Mismatched range produces visible color
     *  shift (often a yellow/amber tint). */
    public static final int YUV_BT601      = 0;
    public static final int YUV_BT709      = 1;
    public static final int YUV_BT2020     = 2;
    public static final int YUV_JPEG_FULL  = 3;

    /** HDR-aware YUV upload with transfer/primaries/range descriptor and
     *  optional BT.2390 tone mapping. See {@code openjfx_skia_bridge.h}
     *  for the parameter ordering and the {@code OPENJFX_SKIA_TFN_*} /
     *  {@code OPENJFX_SKIA_PRI_*} enum values. Optional symbol — older
     *  native libs without HDR support won't have it; callers must
     *  check {@link #hasHdrPipeline()} and fall through to the legacy
     *  {@link #imageCreateYuvI420} or to {@link com.sun.prism.skia.impl.HdrToneMap}
     *  on the Java side. */
    private static final MethodHandle MH_IMAGE_CREATE_YUV_HDR =
        LOOKUP.find("openjfx_skia_image_create_yuv_hdr")
              .map(seg -> LINKER.downcallHandle(seg,
                  FunctionDescriptor.of(
                      ValueLayout.ADDRESS,                          // returns image handle
                      ValueLayout.ADDRESS, ValueLayout.JAVA_INT,    // yPlane, yStride
                      ValueLayout.ADDRESS, ValueLayout.JAVA_INT,    // uPlane, uStride
                      ValueLayout.ADDRESS, ValueLayout.JAVA_INT,    // vPlane, vStride
                      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,   // w, h
                      ValueLayout.JAVA_INT,                         // yuvMatrix
                      ValueLayout.JAVA_INT,                         // transferFn
                      ValueLayout.JAVA_INT,                         // primaries
                      ValueLayout.JAVA_INT,                         // fullRange
                      ValueLayout.JAVA_FLOAT,                       // srcPeakNits
                      ValueLayout.JAVA_FLOAT)))                     // dstPeakNits
              .orElse(null);

    /** Probe: does the native bridge implement HDR tone mapping? */
    private static final MethodHandle MH_HAS_HDR_PIPELINE =
        LOOKUP.find("openjfx_skia_has_hdr_pipeline")
              .map(seg -> LINKER.downcallHandle(seg,
                  FunctionDescriptor.of(ValueLayout.JAVA_INT)))
              .orElse(null);

    /** Transfer-function enum, matches {@code OPENJFX_SKIA_TFN_*}
     *  in {@code openjfx_skia_bridge.h}. */
    public static final int TFN_SRGB    = 0;
    public static final int TFN_REC709  = 1;
    public static final int TFN_PQ      = 2;   // SMPTE ST 2084, HDR10
    public static final int TFN_HLG     = 3;   // BT.2100 HLG
    public static final int TFN_LINEAR  = 4;

    /** Primaries enum, matches {@code OPENJFX_SKIA_PRI_*}. */
    public static final int PRI_SRGB    = 0;
    public static final int PRI_REC2020 = 1;
    public static final int PRI_DCI_P3  = 2;
    public static final int PRI_REC601  = 3;

    /** Wrap an externally-managed GL texture (RGBA8) as a borrowed
     *  SkImage. Used by the M3-B media zero-copy path; the texture name
     *  is produced by {@link #d3d11InteropRegisterTexture}. Optional
     *  symbol: stub builds without Skia GPU return null. */
    private static final MethodHandle MH_IMAGE_CREATE_FROM_GL_TEXTURE =
        LOOKUP.find("openjfx_skia_image_create_from_gl_texture")
              .map(seg -> LINKER.downcallHandle(seg,
                  FunctionDescriptor.of(
                      ValueLayout.ADDRESS,                          // returns image handle
                      ValueLayout.JAVA_INT,                         // glTextureName
                      ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))) // width, height
              .orElse(null);

    private static final MethodHandle MH_IMAGE_DESTROY = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_image_destroy"),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private static final MethodHandle MH_IMAGE_WIDTH = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_image_width"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private static final MethodHandle MH_IMAGE_HEIGHT = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_image_height"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    /** SkImage → compressed byte stream (PNG/JPEG/WebP). Caller frees the
     *  returned pointer via {@link #bufferFree}. See bridge header. */
    private static final MethodHandle MH_IMAGE_ENCODE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_image_encode"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,                                  // status (0 = ok)
            ValueLayout.ADDRESS,                                   // image handle
            ValueLayout.JAVA_INT,                                  // format (0=PNG, 1=JPEG, 2=WEBP)
            ValueLayout.JAVA_INT,                                  // quality 0..100
            ValueLayout.ADDRESS,                                   // out: ptr
            ValueLayout.ADDRESS));                                 // out: size

    private static final MethodHandle MH_BUFFER_FREE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_buffer_free"),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    /** Encoded image format codes for {@link #imageEncode}. */
    public static final int FMT_PNG  = 0;
    public static final int FMT_JPEG = 1;
    public static final int FMT_WEBP = 2;

    // ---- Text / glyph rendering --------------------------------------------

    private static final MethodHandle MH_TYPEFACE_CREATE_FROM_DATA = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_typeface_create_from_data"),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,                                   // returns typeface handle
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));           // data ptr, length

    private static final MethodHandle MH_TYPEFACE_DESTROY = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_typeface_destroy"),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private static final MethodHandle MH_SURFACE_DRAW_GLYPHS = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_draw_glyphs"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,                                   // surface handle
            ValueLayout.ADDRESS,                                   // typeface handle
            ValueLayout.JAVA_FLOAT,                                // fontSize
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,             // glyphIds ptr, count
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,              // posX ptr, posY ptr
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE));        // RGBA

    private static final MethodHandle MH_SURFACE_DRAW_GLYPHS_SHADER = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_draw_glyphs_shader"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,                                   // surface handle
            ValueLayout.ADDRESS,                                   // typeface handle
            ValueLayout.JAVA_FLOAT,                                // fontSize
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,             // glyphIds ptr, count
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,              // posX ptr, posY ptr
            ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));          // shader handle, alpha

    // ---- Canvas state (save / restore / translate) -------------------------

    private static final MethodHandle MH_SURFACE_SAVE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_save"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private static final MethodHandle MH_SURFACE_RESTORE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_restore"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private static final MethodHandle MH_SURFACE_CLIP_PATH = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_clip_path"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,                                  // handle
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,            // verbs, verbCount
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,            // coords, coordCount
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));         // fillRule, clipOp

    private static final MethodHandle MH_SURFACE_SAVE_LAYER_ALPHA = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_save_layer_alpha"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    // Batched per-draw setup. One FFM crossing replaces save +
    // optional clipRect + setMatrix + setBlendMode + setExtraAlpha.
    private static final MethodHandle MH_SURFACE_BEGIN_DRAW = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_begin_draw"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,                                  // handle
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT,                               // m00, m01, m02
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT,                               // m10, m11, m12
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,           // clipX,Y,W,H
            ValueLayout.JAVA_INT,                                 // hasClip
            ValueLayout.JAVA_INT,                                 // blendMode
            ValueLayout.JAVA_FLOAT));                             // extraAlpha

    private static final MethodHandle MH_SURFACE_END_DRAW = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_end_draw"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private static final MethodHandle MH_SURFACE_TRANSLATE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_translate"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));

    private static final MethodHandle MH_SURFACE_SET_MATRIX = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_set_matrix"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));

    private static final MethodHandle MH_SURFACE_CLIP_RECT = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_clip_rect"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));

    private static final MethodHandle MH_SURFACE_SET_BLEND_MODE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_set_blend_mode"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    private static final MethodHandle MH_SURFACE_SET_EXTRA_ALPHA = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_set_extra_alpha"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT));

    // ---- Shader lifecycle --------------------------------------------------

    /** SkBlendMode int values (subset). See SkBlendMode.h for the full list. */
    public static final int BLEND_CLEAR    = 0;
    public static final int BLEND_SRC      = 1;
    public static final int BLEND_DST      = 2;
    public static final int BLEND_SRC_OVER = 3;
    public static final int BLEND_DST_OVER = 4;
    public static final int BLEND_SRC_IN   = 5;
    public static final int BLEND_DST_IN   = 6;
    public static final int BLEND_SRC_OUT  = 7;
    public static final int BLEND_DST_OUT  = 8;
    public static final int BLEND_SRC_ATOP = 9;
    public static final int BLEND_DST_ATOP = 10;
    public static final int BLEND_XOR      = 11;
    public static final int BLEND_PLUS     = 12;
    public static final int BLEND_MULTIPLY = 13;
    public static final int BLEND_SCREEN   = 14;
    public static final int BLEND_OVERLAY  = 15;

    /** SkTileMode constants. */
    public static final int TILE_CLAMP  = 0;
    public static final int TILE_REPEAT = 1;
    public static final int TILE_MIRROR = 2;
    public static final int TILE_DECAL  = 3;

    private static final MethodHandle MH_SHADER_LINEAR = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_shader_create_linear_gradient"),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,    // x0,y0
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,    // x1,y1
            ValueLayout.JAVA_INT,                              // nStops
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,          // positions, colors
            ValueLayout.JAVA_INT));                            // tileMode

    private static final MethodHandle MH_SHADER_RADIAL = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_shader_create_radial_gradient"),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,    // cx,cy
            ValueLayout.JAVA_FLOAT,                            // radius
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT));

    // Local-matrix gradient variants. Bound optionally: a native library
    // predating these symbols must not break class init — when absent the
    // shader path falls back to the no-matrix call (correct for the common
    // proportional + identity-transform case; only a non-identity
    // gradientTransform / non-square elliptical radial is degraded).
    private static final MethodHandle MH_SHADER_LINEAR_LM =
        LOOKUP.find("openjfx_skia_shader_create_linear_gradient_lm")
            .map(sym -> LINKER.downcallHandle(sym, FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,    // x0,y0
                ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,    // x1,y1
                ValueLayout.JAVA_INT,                              // nStops
                ValueLayout.ADDRESS, ValueLayout.ADDRESS,          // positions, colors
                ValueLayout.JAVA_INT,                             // tileMode
                ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,  // m00,m01,m02
                ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT))) // m10,m11,m12
            .orElse(null);

    private static final MethodHandle MH_SHADER_RADIAL_LM =
        LOOKUP.find("openjfx_skia_shader_create_radial_gradient_lm")
            .map(sym -> LINKER.downcallHandle(sym, FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,    // cx,cy
                ValueLayout.JAVA_FLOAT,                            // radius
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT)))
            .orElse(null);

    private static final MethodHandle MH_SHADER_IMAGE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_shader_create_image"),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,                              // image handle
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));     // tileX, tileY

    private static final MethodHandle MH_SHADER_DESTROY = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_shader_destroy"),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private static final MethodHandle MH_SURFACE_FILL_RECT_SHADER = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_fill_rect_shader"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));

    private static final MethodHandle MH_SURFACE_FILL_ROUND_RECT_SHADER = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_fill_round_rect_shader"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));

    private static final MethodHandle MH_SURFACE_FILL_OVAL_SHADER = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_fill_oval_shader"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));

    private static final MethodHandle MH_SURFACE_FILL_PATH_SHADER = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_fill_path_shader"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,         // verbs, count
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,         // coords, count
            ValueLayout.JAVA_INT,                              // fillRule
            ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));      // shader, alpha

    // ---- Stroke-with-shader -----------------------------------------------

    private static final FunctionDescriptor FD_STROKE_RECT_SHADER = FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,   ValueLayout.JAVA_FLOAT,
        ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE);

    private static final MethodHandle MH_SURFACE_STROKE_RECT_SHADER = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_stroke_rect_shader"), FD_STROKE_RECT_SHADER);

    private static final MethodHandle MH_SURFACE_STROKE_ROUND_RECT_SHADER = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_stroke_round_rect_shader"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,   ValueLayout.JAVA_FLOAT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));

    private static final MethodHandle MH_SURFACE_STROKE_OVAL_SHADER = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_stroke_oval_shader"), FD_STROKE_RECT_SHADER);

    private static final MethodHandle MH_SURFACE_STROKE_LINE_SHADER = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_stroke_line_shader"), FD_STROKE_RECT_SHADER);

    private static final MethodHandle MH_SURFACE_STROKE_PATH_SHADER = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_stroke_path_shader"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,   ValueLayout.JAVA_FLOAT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));

    // ---- ImageFilter ------------------------------------------------------

    private static final MethodHandle MH_FILTER_BLUR = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_blur"),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_INT));

    private static final MethodHandle MH_FILTER_DROP_SHADOW = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_drop_shadow"),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE));

    private static final MethodHandle MH_FILTER_COLOR_MATRIX = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_color_matrix"),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static final MethodHandle MH_FILTER_COMPOSE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_compose"),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static final MethodHandle MH_FILTER_DESTROY = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_destroy"),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    // ---- Additional filter primitives (full effect-engine surface) -------

    private static final MethodHandle MH_FILTER_DROP_SHADOW_ONLY = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_drop_shadow_only"),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE));

    private static final MethodHandle MH_FILTER_BLEND = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_blend"),
        FunctionDescriptor.of(ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static final MethodHandle MH_FILTER_MERGE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_merge"),
        FunctionDescriptor.of(ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    private static final MethodHandle MH_FILTER_OFFSET = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_offset"),
        FunctionDescriptor.of(ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.ADDRESS));

    private static final MethodHandle MH_FILTER_CROP = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_crop"),
        FunctionDescriptor.of(ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.ADDRESS));

    private static final MethodHandle MH_FILTER_ERODE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_erode"),
        FunctionDescriptor.of(ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.ADDRESS));

    private static final MethodHandle MH_FILTER_DILATE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_dilate"),
        FunctionDescriptor.of(ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.ADDRESS));

    private static final MethodHandle MH_FILTER_MATRIX_TRANSFORM = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_matrix_transform"),
        FunctionDescriptor.of(ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.ADDRESS));

    private static final MethodHandle MH_FILTER_DISPLACEMENT_MAP = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_displacement_map"),
        FunctionDescriptor.of(ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static final MethodHandle MH_FILTER_IMAGE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_image"),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static final MethodHandle MH_FILTER_SHADER = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_shader"),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // Lighting: layout (dir/loc xyz, color rgba, surfaceScale, k, input)
    private static final FunctionDescriptor FD_LIT_DIFFUSE = FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.ADDRESS);
    private static final FunctionDescriptor FD_LIT_SPECULAR = FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.ADDRESS);
    private static final FunctionDescriptor FD_SPOT_DIFFUSE = FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,   // loc
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,   // target
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,                           // falloff, cutoff
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,                           // surfaceScale, kd
        ValueLayout.ADDRESS);
    private static final FunctionDescriptor FD_SPOT_SPECULAR = FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
        ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
        ValueLayout.ADDRESS);

    private static final MethodHandle MH_FILTER_DISTANT_LIT_DIFFUSE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_distant_lit_diffuse"), FD_LIT_DIFFUSE);
    private static final MethodHandle MH_FILTER_POINT_LIT_DIFFUSE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_point_lit_diffuse"), FD_LIT_DIFFUSE);
    private static final MethodHandle MH_FILTER_SPOT_LIT_DIFFUSE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_spot_lit_diffuse"), FD_SPOT_DIFFUSE);
    private static final MethodHandle MH_FILTER_DISTANT_LIT_SPECULAR = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_distant_lit_specular"), FD_LIT_SPECULAR);
    private static final MethodHandle MH_FILTER_POINT_LIT_SPECULAR = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_point_lit_specular"), FD_LIT_SPECULAR);
    private static final MethodHandle MH_FILTER_SPOT_LIT_SPECULAR = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_filter_create_spot_lit_specular"), FD_SPOT_SPECULAR);

    private static final MethodHandle MH_SURFACE_SNAPSHOT_TO_IMAGE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_snapshot_to_image"),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static final MethodHandle MH_SURFACE_SAVE_LAYER_WITH_FILTER = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_save_layer_with_filter"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // ---- SVG documents (modules/svg) ----------------------------------------
    // OPTIONAL symbols (find, not findOrThrow): a native lib built without the
    // SVG module — or an older one — simply leaves these null, and the Java
    // side falls back to "no SVG support" (SvgImage reports a load error)
    // rather than failing this class's <clinit> and taking the whole Skia
    // pipeline down.
    private static final MethodHandle MH_SVG_PARSE =
        LOOKUP.find("openjfx_skia_svg_parse")
              .map(s -> LINKER.downcallHandle(s, FunctionDescriptor.of(
                  ValueLayout.ADDRESS,    // return: uintptr_t svg handle (0 on failure)
                  ValueLayout.ADDRESS,    // const void* utf8
                  ValueLayout.JAVA_INT))) // length
              .orElse(null);

    private static final MethodHandle MH_SVG_GET_SIZE =
        LOOKUP.find("openjfx_skia_svg_get_size")
              .map(s -> LINKER.downcallHandle(s, FunctionDescriptor.of(
                  ValueLayout.JAVA_INT,   // return: status (0 ok, -1 bad handle)
                  ValueLayout.ADDRESS,    // svg handle
                  ValueLayout.ADDRESS)))  // float* out[2]
              .orElse(null);

    private static final MethodHandle MH_SVG_RENDER_IN_PLACE =
        LOOKUP.find("openjfx_skia_svg_render_in_place")
              .map(s -> LINKER.downcallHandle(s, FunctionDescriptor.of(
                  ValueLayout.JAVA_INT,   // return: status
                  ValueLayout.ADDRESS,    // surface handle
                  ValueLayout.ADDRESS,    // svg handle
                  ValueLayout.JAVA_FLOAT, // x
                  ValueLayout.JAVA_FLOAT, // y
                  ValueLayout.JAVA_FLOAT, // w
                  ValueLayout.JAVA_FLOAT, // h
                  ValueLayout.JAVA_INT,   // bgArgb
                  ValueLayout.JAVA_INT,   // tintArgb
                  ValueLayout.JAVA_INT,   // tintMode
                  ValueLayout.JAVA_INT,   // gridArgb
                  ValueLayout.JAVA_FLOAT, // gridCell
                  ValueLayout.JAVA_FLOAT)))// gridLineWidth
              .orElse(null);

    private static final MethodHandle MH_SVG_DESTROY =
        LOOKUP.find("openjfx_skia_svg_destroy")
              .map(s -> LINKER.downcallHandle(s, FunctionDescriptor.ofVoid(
                  ValueLayout.ADDRESS)))  // svg handle
              .orElse(null);

    private static final MethodHandle MH_SURFACE_DRAW_IMAGE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_draw_image"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,              // surface, image
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));      // dx, dy, dw, dh

    private static final MethodHandle MH_SURFACE_DRAW_SURFACE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_draw_surface"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));

    private static final MethodHandle MH_SURFACE_DRAW_SURFACE_VO = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_draw_surface_vo"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,        // dst quad
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,        // src quad
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));      // top/bot opacity

    private static final MethodHandle MH_SURFACE_DRAW_IMAGE_RECT = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_draw_image_rect"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,        // src
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));      // dst

    // Off-screen Blink WebView frame compositor: BGRA8888 bytes (native
    // address) → live scene canvas. surfaceHandle + pixels are uintptr_t
    // numbers, passed as JAVA_LONG.
    private static final MethodHandle MH_SURFACE_DRAW_BGRA = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_draw_bgra"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,  // surfaceHandle
            ValueLayout.JAVA_LONG,  // pixels
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,  // srcW,srcH,srcStride
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,                        // dstX,dstY
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));                      // dstW,dstH

    private static final MethodHandle MH_SURFACE_STROKE_PATH = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_stroke_path"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,                                  // fillRule (only used to set path's fill type, irrelevant for stroke)
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,   ValueLayout.JAVA_FLOAT,        // width, cap, join, miter
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE,
            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_BYTE));

    private static final MethodHandle MH_SURFACE_REPLACE_BACKING_ARGB = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_replace_backing_argb"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    private static final MethodHandle MH_SURFACE_READ_PIXELS_ARGB = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_read_pixels_argb"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    // Optional: dirty-rect readback with caller stride (partial present).
    // find() so an older native lib degrades to full-frame readback.
    private static final MethodHandle MH_SURFACE_READ_PIXELS_ARGB_STRIDE =
        LOOKUP.find("openjfx_skia_surface_read_pixels_argb_stride")
            .map(s -> LINKER.downcallHandle(s, FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)))
            .orElse(null);

    private static final MethodHandle MH_SURFACE_READ_PIXELS = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_read_pixels"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,    // surface handle
            ValueLayout.ADDRESS,    // dst pixel buffer
            ValueLayout.JAVA_INT,   // x
            ValueLayout.JAVA_INT,   // y
            ValueLayout.JAVA_INT,   // w
            ValueLayout.JAVA_INT)); // h

    // ---- Buffer-based smoke helpers ----------------------------------------

    private static final MethodHandle MH_FILL_RECT = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_fill_rect"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,    // return: status
            ValueLayout.ADDRESS,     // pixels
            ValueLayout.JAVA_INT,    // width
            ValueLayout.JAVA_INT,    // height
            ValueLayout.JAVA_INT,    // rowBytes
            ValueLayout.JAVA_INT,    // x
            ValueLayout.JAVA_INT,    // y
            ValueLayout.JAVA_INT,    // rectW
            ValueLayout.JAVA_INT,    // rectH
            ValueLayout.JAVA_BYTE,   // r
            ValueLayout.JAVA_BYTE,   // g
            ValueLayout.JAVA_BYTE,   // b
            ValueLayout.JAVA_BYTE)); // a

    // ---- SkPicture record / replay — Task #31 ------------------------------

    private static final MethodHandle MH_PICTURE_RECORDER_CREATE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_picture_recorder_create"),
        FunctionDescriptor.of(ValueLayout.ADDRESS));

    private static final MethodHandle MH_PICTURE_RECORDER_BEGIN = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_picture_recorder_begin"),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,   // returns: recording-canvas surface handle
            ValueLayout.ADDRESS,   // recorderHandle
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, // x, y
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT)); // w, h

    private static final MethodHandle MH_PICTURE_RECORDER_FINISH = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_picture_recorder_finish"),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static final MethodHandle MH_PICTURE_DESTROY = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_picture_destroy"),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private static final MethodHandle MH_PICTURE_RECORDER_DESTROY = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_picture_recorder_destroy"),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private static final MethodHandle MH_SURFACE_DRAW_PICTURE = LINKER.downcallHandle(
        LOOKUP.findOrThrow("openjfx_skia_surface_draw_picture"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,  // returns: status
            ValueLayout.ADDRESS,   // targetSurface
            ValueLayout.ADDRESS,   // pictureHandle
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT)); // dx, dy

    private NativeBridge() {
        // Static utility.
    }

    /**
     * Returns the version string baked into the native library.
     * Includes whether the library has Skia compiled in.
     */
    public static String version() {
        try {
            MemorySegment ptr = (MemorySegment) MH_VERSION.invokeExact();
            return ptr.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_version failed", t);
        }
    }

    /**
     * Returns {@code true} if the native library was compiled with
     * real Skia C++ integration. {@code false} indicates the stub
     * fallback (raster only, no Skia API surface available).
     */
    public static boolean hasSkia() {
        try {
            return ((int) MH_HAS_SKIA.invokeExact()) != 0;
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_has_skia failed", t);
        }
    }

    /**
     * True once the shared D3D12 device has been removed/lost (cross-DPI monitor
     * move TDR / adapter change). Skia 2D and bgfx 3D share the device, so callers
     * must stop ALL GPU work while this is true — degrade, never crash or spam the
     * dead device. Returns {@code false} on builds without the guard or on non-D3D
     * backends (GL/raster don't lose the device this way).
     */
    public static boolean isDeviceLost() {
        if (MH_DEVICE_LOST == null) return false;
        try {
            return ((int) MH_DEVICE_LOST.invokeExact()) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Recreate the shared D3D12 device + GrDirectContext + bgfx after a device loss,
     * clearing the lost flag on success. Returns {@code true} if the device is usable
     * again. No-op (returns {@code false}) on builds/backends without the recover path.
     */
    public static boolean recoverDevice() {
        if (MH_DEVICE_RECOVER == null) return false;
        try {
            return ((int) MH_DEVICE_RECOVER.invokeExact()) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Request a GPU backend BEFORE the GrDirectContext is first built (i.e. before
     * the first GPU surface is allocated). {@code pref} is one of {@link #BACKEND_AUTO},
     * {@link #BACKEND_GL}, {@link #BACKEND_D3D12}. No-op on builds without the selector
     * (the env var / platform default still applies).
     */
    public static void setGpuBackend(int pref) {
        if (MH_SET_GPU_BACKEND == null) return;
        try {
            MH_SET_GPU_BACKEND.invokeExact(pref);
        } catch (Throwable t) {
            // best-effort; selection falls back to AUTO/env
        }
    }

    /**
     * The backend actually backing the GrDirectContext: {@link #BACKEND_AUTO} (0) when
     * not yet created or unknown, {@link #BACKEND_GL} (1), or {@link #BACKEND_D3D12} (2).
     */
    public static int activeBackend() {
        if (MH_GET_ACTIVE_BACKEND == null) return BACKEND_AUTO;
        try {
            return (int) MH_GET_ACTIVE_BACKEND.invokeExact();
        } catch (Throwable t) {
            return BACKEND_AUTO;
        }
    }

    /**
     * Clears a raster pixel buffer to the given premultiplied RGBA color.
     * Smoke test for the FFM bridge. Returns 0 on success.
     */
    public static int clearBuffer(MemorySegment pixels,
                                  int width, int height, int rowBytes,
                                  int r, int g, int b, int a) {
        try {
            return (int) MH_CLEAR_BUFFER.invokeExact(
                pixels, width, height, rowBytes,
                (byte) r, (byte) g, (byte) b, (byte) a);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_clear_buffer failed", t);
        }
    }

    // ---- Surface lifecycle wrappers ----------------------------------------

    /**
     * Creates a CPU-backed SkSurface. Returns the opaque native handle
     * as a {@link MemorySegment} (zero-length address). Returns
     * {@link MemorySegment#NULL} if the surface could not be created
     * (typically because Skia is not compiled in).
     */
    public static MemorySegment surfaceCreateRaster(int width, int height) {
        try {
            return (MemorySegment) MH_SURFACE_CREATE_RASTER.invokeExact(width, height);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_create_raster failed", t);
        }
    }

    /**
     * Creates a CPU-backed SkSurface in BGRA byte order — the READBACK
     * present tier's format, where the per-frame readback is a straight
     * row copy instead of a channel swizzle. Falls back to the RGBA
     * variant when the native lib predates the symbol.
     */
    public static MemorySegment surfaceCreateRasterBgra(int width, int height) {
        if (MH_SURFACE_CREATE_RASTER_BGRA == null) {
            return surfaceCreateRaster(width, height);
        }
        try {
            return (MemorySegment) MH_SURFACE_CREATE_RASTER_BGRA.invokeExact(width, height);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_create_raster_bgra failed", t);
        }
    }

    /**
     * Creates a GPU-backed SkSurface (Ganesh OpenGL). Returns
     * {@link MemorySegment#NULL} when GPU is unavailable — callers fall
     * back to {@link #surfaceCreateRaster}.
     */
    public static MemorySegment surfaceCreateGpu(int width, int height) {
        try {
            return (MemorySegment) MH_SURFACE_CREATE_GPU.invokeExact(width, height);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_create_gpu failed", t);
        }
    }

    /**
     * Creates a GPU-backed SkSurface wrapping the OpenGL default
     * framebuffer of {@code hwnd}. Draws land directly in the window's
     * back buffer; {@link #surfacePresentWindow} flushes + SwapBuffers
     * to present without a CPU readback. Returns
     * {@link MemorySegment#NULL} if direct-present is unavailable for
     * this window — caller falls back to off-screen GPU or raster.
     */
    public static MemorySegment surfaceCreateWindowGpu(MemorySegment hwnd,
                                                       int width, int height) {
        try {
            return (MemorySegment) MH_SURFACE_CREATE_WINDOW_GPU.invokeExact(
                hwnd, width, height);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_create_window_gpu failed", t);
        }
    }

    /**
     * Flushes the GrDirectContext and SwapBuffers on a window-bound
     * GPU surface. Returns 0 on success, non-zero on failure (caller
     * should mark the surface stale and recreate).
     */
    public static int surfacePresentWindow(MemorySegment handle, int vsync) {
        try {
            return (int) MH_SURFACE_PRESENT_WINDOW.invokeExact(handle, vsync);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_present_window failed", t);
        }
    }

    /**
     * Paint-before-show: GDI-blits the window surface's just-rendered frame onto
     * the window's DWM redirection bitmap so the OS show animation reveals real
     * UI. Call once, before {@code surfacePresentWindow*}, while painting the
     * first frame of a not-yet-shown window. Returns 0 on success.
     */
    public static int surfacePrimeWindow(MemorySegment handle) {
        try {
            return (int) MH_SURFACE_PRIME_WINDOW.invokeExact(handle);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_prime_window failed", t);
        }
    }

    /**
     * Returns the refresh rate (Hz) of the monitor the given window
     * currently lives on, or 0 if it can't be determined (unsupported
     * platform, driver hides it, HWND invalid, or the native lib was
     * built without this symbol). PresentingPainter uses this to
     * per-window-derive the SwapBuffers rate cap so multi-monitor
     * setups with mixed refresh rates (e.g. 60 Hz secondary + 144 Hz
     * primary) match each window to its own display.
     *
     * <p>{@code hwndAddr} must be a raw native window-handle address
     * wrapped as a MemorySegment via {@code MemorySegment.ofAddress(h)}
     * — NOT a Skia surface handle.</p>
     */
    public static int windowGetRefreshHz(MemorySegment hwndAddr) {
        // Symbol may be absent in older native libs; treat as "unknown"
        // so callers fall back to a sensible default cap.
        if (MH_WINDOW_GET_REFRESH_HZ == null) {
            return 0;
        }
        try {
            return (int) MH_WINDOW_GET_REFRESH_HZ.invokeExact(hwndAddr);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_window_get_refresh_hz failed", t);
        }
    }

    /**
     * Creates a D3D12 DXGI flip-model swap chain attached to {@code hwnd},
     * with each back-buffer wrapped as a Skia SkSurface. Returns
     * {@link MemorySegment#NULL} when D3D12 isn't the active backend or
     * the swap-chain creation fails — caller falls back to
     * {@link #surfaceCreateWindowGpu} (GL), then offscreen GPU, then raster.
     */
    public static MemorySegment surfaceCreateSwapChainD3d(MemorySegment hwnd,
                                                          int width, int height) {
        try {
            return (MemorySegment) MH_SURFACE_CREATE_SWAP_CHAIN_D3D.invokeExact(
                hwnd, width, height);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_create_swap_chain_d3d failed", t);
        }
    }

    /**
     * Presents the D3D swap chain via {@code Present(0, ALLOW_TEARING)}
     * — bypasses DWM's windowed-vsync cap. Returns 0 on success.
     */
    public static int surfacePresentWindowD3d(MemorySegment handle, int vsync) {
        try {
            return (int) MH_SURFACE_PRESENT_WINDOW_D3D.invokeExact(handle, vsync);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_present_window_d3d failed", t);
        }
    }

    /**
     * Per-frame entry called before drawing into a D3D swap-chain
     * surface. Waits on DXGI's frame-latency waitable + the per-buffer
     * fence for deterministic CPU↔GPU pacing with a shallow 1-frame
     * lead. Returns 0 on success.
     */
    public static int surfaceBeginFrameD3d(MemorySegment handle) {
        try {
            return (int) MH_SURFACE_BEGIN_FRAME_D3D.invokeExact(handle);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_begin_frame_d3d failed", t);
        }
    }

    /**
     * In-place resize of a D3D swap-chain surface via DXGI's
     * {@code ResizeBuffers}. DWM treats this as a semantic resize
     * (no stretch artifact). Returns 0 on success.
     */
    public static int surfaceResizeD3d(MemorySegment handle, int width, int height) {
        try {
            return (int) MH_SURFACE_RESIZE_D3D.invokeExact(handle, width, height);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_resize_d3d failed", t);
        }
    }

    /**
     * In-place re-wrap of a GL direct-present surface at the new size.
     * Re-binds the shared WGL context, drops the old SkSurface, and
     * wraps FBO 0 again at (width, height) — keeps the
     * GrDirectContext + HDC binding intact, so the per-WM_SIZE
     * cost during a drag-resize collapses from "destroy + recreate
     * presentable" to a single wgl+wrap. Returns 0 on success;
     * non-zero leaves the caller to fall back to destroy+recreate.
     */
    public static int surfaceResizeGl(MemorySegment handle, int width, int height) {
        try {
            return (int) MH_SURFACE_RESIZE_GL.invokeExact(handle, width, height);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_resize_gl failed", t);
        }
    }

    /** Releases the SkSurface owning the given handle. Safe with NULL. */
    public static void surfaceDestroy(MemorySegment handle) {
        try {
            MH_SURFACE_DESTROY.invokeExact(handle);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_destroy failed", t);
        }
    }

    public static int surfaceWidth(MemorySegment handle) {
        try { return (int) MH_SURFACE_WIDTH.invokeExact(handle); }
        catch (Throwable t) { throw new RuntimeException("openjfx_skia_surface_width failed", t); }
    }

    public static int surfaceHeight(MemorySegment handle) {
        try { return (int) MH_SURFACE_HEIGHT.invokeExact(handle); }
        catch (Throwable t) { throw new RuntimeException("openjfx_skia_surface_height failed", t); }
    }

    /** Clears a surface to a premultiplied RGBA color via SkCanvas::clear. */
    public static int surfaceClear(MemorySegment handle, int r, int g, int b, int a) {
        try {
            return (int) MH_SURFACE_CLEAR.invokeExact(
                handle, (byte) r, (byte) g, (byte) b, (byte) a);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_clear failed", t);
        }
    }

    /** Fills a rect on the surface with anti-aliased SkCanvas::drawRect. */
    public static int surfaceFillRect(MemorySegment handle,
                                      int x, int y, int w, int h,
                                      int r, int g, int b, int a) {
        try {
            return (int) MH_SURFACE_FILL_RECT.invokeExact(
                handle, x, y, w, h,
                (byte) r, (byte) g, (byte) b, (byte) a);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_fill_rect failed", t);
        }
    }

    // ---- Filled / stroked primitives wrappers -----------------------------

    public static int surfaceFillRoundRect(MemorySegment handle,
                                           float x, float y, float w, float h,
                                           float arcW, float arcH,
                                           int r, int g, int b, int a) {
        try {
            return (int) MH_SURFACE_FILL_ROUND_RECT.invokeExact(
                handle, x, y, w, h, arcW, arcH,
                (byte) r, (byte) g, (byte) b, (byte) a);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_fill_round_rect failed", t);
        }
    }

    public static int surfaceFillOval(MemorySegment handle,
                                      float x, float y, float w, float h,
                                      int r, int g, int b, int a) {
        try {
            return (int) MH_SURFACE_FILL_OVAL.invokeExact(
                handle, x, y, w, h,
                (byte) r, (byte) g, (byte) b, (byte) a);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_fill_oval failed", t);
        }
    }

    public static int surfaceStrokeRect(MemorySegment handle,
                                        float x, float y, float w, float h,
                                        float width, int cap, int join, float miter,
                                        int r, int g, int b, int a) {
        try {
            return (int) MH_SURFACE_STROKE_RECT.invokeExact(
                handle, x, y, w, h, width, cap, join, miter,
                (byte) r, (byte) g, (byte) b, (byte) a);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_stroke_rect failed", t);
        }
    }

    public static int surfaceStrokeRoundRect(MemorySegment handle,
                                             float x, float y, float w, float h,
                                             float arcW, float arcH,
                                             float width, int cap, int join, float miter,
                                             int r, int g, int b, int a) {
        try {
            return (int) MH_SURFACE_STROKE_ROUND_RECT.invokeExact(
                handle, x, y, w, h, arcW, arcH,
                width, cap, join, miter,
                (byte) r, (byte) g, (byte) b, (byte) a);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_stroke_round_rect failed", t);
        }
    }

    public static int surfaceStrokeOval(MemorySegment handle,
                                        float x, float y, float w, float h,
                                        float width, int cap, int join, float miter,
                                        int r, int g, int b, int a) {
        try {
            return (int) MH_SURFACE_STROKE_OVAL.invokeExact(
                handle, x, y, w, h, width, cap, join, miter,
                (byte) r, (byte) g, (byte) b, (byte) a);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_stroke_oval failed", t);
        }
    }

    public static int surfaceStrokeLine(MemorySegment handle,
                                        float x1, float y1, float x2, float y2,
                                        float width, int cap, int join, float miter,
                                        int r, int g, int b, int a) {
        try {
            return (int) MH_SURFACE_STROKE_LINE.invokeExact(
                handle, x1, y1, x2, y2, width, cap, join, miter,
                (byte) r, (byte) g, (byte) b, (byte) a);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_stroke_line failed", t);
        }
    }

    // ---- Canvas state wrappers --------------------------------------------

    public static int surfaceSave(MemorySegment handle) {
        try { return (int) MH_SURFACE_SAVE.invokeExact(handle); }
        catch (Throwable t) { throw new RuntimeException("openjfx_skia_surface_save failed", t); }
    }

    public static int surfaceRestore(MemorySegment handle) {
        try { return (int) MH_SURFACE_RESTORE.invokeExact(handle); }
        catch (Throwable t) { throw new RuntimeException("openjfx_skia_surface_restore failed", t); }
    }

    public static int surfaceClipPath(MemorySegment handle,
                                      MemorySegment verbs, int verbCount,
                                      MemorySegment coords, int coordCount,
                                      int fillRule, int clipOp) {
        try {
            return (int) MH_SURFACE_CLIP_PATH.invokeExact(
                handle, verbs, verbCount, coords, coordCount, fillRule, clipOp);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_clip_path failed", t);
        }
    }

    public static int surfaceSaveLayerAlpha(MemorySegment handle, int alpha) {
        try {
            return (int) MH_SURFACE_SAVE_LAYER_ALPHA.invokeExact(handle, alpha);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_save_layer_alpha failed", t);
        }
    }

    /**
     * Single-crossing per-draw setup. Equivalent to:
     * {@code surfaceSave + (hasClip ? surfaceClipRect : nothing) +
     * surfaceSetMatrix + surfaceSetBlendMode + surfaceSetExtraAlpha}.
     * The clip is applied in device coordinates (the native side
     * applies it before the matrix) so per-node rect clips on TableView
     * / ScrollPane / etc. land correctly.
     */
    public static int surfaceBeginDraw(MemorySegment handle,
                                       float m00, float m01, float m02,
                                       float m10, float m11, float m12,
                                       int clipX, int clipY, int clipW, int clipH,
                                       boolean hasClip,
                                       int blendMode,
                                       float extraAlpha) {
        try {
            return (int) MH_SURFACE_BEGIN_DRAW.invokeExact(
                handle, m00, m01, m02, m10, m11, m12,
                clipX, clipY, clipW, clipH, hasClip ? 1 : 0,
                blendMode, extraAlpha);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_begin_draw failed", t);
        }
    }

    /** Pops the save level pushed by {@link #surfaceBeginDraw}. */
    public static int surfaceEndDraw(MemorySegment handle) {
        try { return (int) MH_SURFACE_END_DRAW.invokeExact(handle); }
        catch (Throwable t) { throw new RuntimeException("openjfx_skia_surface_end_draw failed", t); }
    }

    public static int surfaceTranslate(MemorySegment handle, float dx, float dy) {
        try { return (int) MH_SURFACE_TRANSLATE.invokeExact(handle, dx, dy); }
        catch (Throwable t) { throw new RuntimeException("openjfx_skia_surface_translate failed", t); }
    }

    public static int surfaceSetMatrix(MemorySegment handle,
                                       float m00, float m01, float m02,
                                       float m10, float m11, float m12) {
        try { return (int) MH_SURFACE_SET_MATRIX.invokeExact(handle, m00, m01, m02, m10, m11, m12); }
        catch (Throwable t) { throw new RuntimeException("openjfx_skia_surface_set_matrix failed", t); }
    }

    public static int surfaceClipRect(MemorySegment handle,
                                      float x, float y, float w, float h) {
        try { return (int) MH_SURFACE_CLIP_RECT.invokeExact(handle, x, y, w, h); }
        catch (Throwable t) { throw new RuntimeException("openjfx_skia_surface_clip_rect failed", t); }
    }

    public static int surfaceSetBlendMode(MemorySegment handle, int mode) {
        try { return (int) MH_SURFACE_SET_BLEND_MODE.invokeExact(handle, mode); }
        catch (Throwable t) { throw new RuntimeException("openjfx_skia_surface_set_blend_mode failed", t); }
    }

    public static int surfaceSetExtraAlpha(MemorySegment handle, float alpha) {
        try { return (int) MH_SURFACE_SET_EXTRA_ALPHA.invokeExact(handle, alpha); }
        catch (Throwable t) { throw new RuntimeException("openjfx_skia_surface_set_extra_alpha failed", t); }
    }

    // ---- Shader wrappers --------------------------------------------------

    public static MemorySegment shaderLinearGradient(float x0, float y0, float x1, float y1,
                                                     int nStops,
                                                     MemorySegment positions,
                                                     MemorySegment colorsRGBA,
                                                     int tileMode) {
        try {
            return (MemorySegment) MH_SHADER_LINEAR.invokeExact(
                x0, y0, x1, y1, nStops, positions, colorsRGBA, tileMode);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_shader_create_linear_gradient failed", t);
        }
    }

    public static MemorySegment shaderRadialGradient(float cx, float cy, float radius,
                                                     int nStops,
                                                     MemorySegment positions,
                                                     MemorySegment colorsRGBA,
                                                     int tileMode) {
        try {
            return (MemorySegment) MH_SHADER_RADIAL.invokeExact(
                cx, cy, radius, nStops, positions, colorsRGBA, tileMode);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_shader_create_radial_gradient failed", t);
        }
    }

    /** Whether the local-matrix gradient variants are present in the loaded
     *  native library (older libraries omit them). */
    public static boolean lmShadersAvailable() {
        return MH_SHADER_LINEAR_LM != null && MH_SHADER_RADIAL_LM != null;
    }

    public static MemorySegment shaderLinearGradientLm(float x0, float y0, float x1, float y1,
                                                       int nStops,
                                                       MemorySegment positions,
                                                       MemorySegment colorsRGBA,
                                                       int tileMode,
                                                       float m00, float m01, float m02,
                                                       float m10, float m11, float m12) {
        try {
            return (MemorySegment) MH_SHADER_LINEAR_LM.invokeExact(
                x0, y0, x1, y1, nStops, positions, colorsRGBA, tileMode,
                m00, m01, m02, m10, m11, m12);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_shader_create_linear_gradient_lm failed", t);
        }
    }

    public static MemorySegment shaderRadialGradientLm(float cx, float cy, float radius,
                                                       int nStops,
                                                       MemorySegment positions,
                                                       MemorySegment colorsRGBA,
                                                       int tileMode,
                                                       float m00, float m01, float m02,
                                                       float m10, float m11, float m12) {
        try {
            return (MemorySegment) MH_SHADER_RADIAL_LM.invokeExact(
                cx, cy, radius, nStops, positions, colorsRGBA, tileMode,
                m00, m01, m02, m10, m11, m12);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_shader_create_radial_gradient_lm failed", t);
        }
    }

    public static MemorySegment shaderImage(MemorySegment imageHandle,
                                            int tileModeX, int tileModeY) {
        try {
            return (MemorySegment) MH_SHADER_IMAGE.invokeExact(imageHandle, tileModeX, tileModeY);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_shader_create_image failed", t);
        }
    }

    public static void shaderDestroy(MemorySegment shader) {
        try { MH_SHADER_DESTROY.invokeExact(shader); }
        catch (Throwable t) { throw new RuntimeException("openjfx_skia_shader_destroy failed", t); }
    }

    public static int surfaceFillRectShader(MemorySegment handle,
                                            float x, float y, float w, float h,
                                            MemorySegment shader, int alpha) {
        try {
            return (int) MH_SURFACE_FILL_RECT_SHADER.invokeExact(
                handle, x, y, w, h, shader, (byte) alpha);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_fill_rect_shader failed", t);
        }
    }

    public static int surfaceFillRoundRectShader(MemorySegment handle,
                                                 float x, float y, float w, float h,
                                                 float arcW, float arcH,
                                                 MemorySegment shader, int alpha) {
        try {
            return (int) MH_SURFACE_FILL_ROUND_RECT_SHADER.invokeExact(
                handle, x, y, w, h, arcW, arcH, shader, (byte) alpha);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_fill_round_rect_shader failed", t);
        }
    }

    public static int surfaceFillOvalShader(MemorySegment handle,
                                            float x, float y, float w, float h,
                                            MemorySegment shader, int alpha) {
        try {
            return (int) MH_SURFACE_FILL_OVAL_SHADER.invokeExact(
                handle, x, y, w, h, shader, (byte) alpha);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_fill_oval_shader failed", t);
        }
    }

    public static int surfaceFillPathShader(MemorySegment handle,
                                            MemorySegment verbs, int verbCount,
                                            MemorySegment coords, int coordCount,
                                            int fillRule,
                                            MemorySegment shader, int alpha) {
        try {
            return (int) MH_SURFACE_FILL_PATH_SHADER.invokeExact(
                handle, verbs, verbCount, coords, coordCount, fillRule,
                shader, (byte) alpha);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_fill_path_shader failed", t);
        }
    }

    public static int surfaceStrokeRectShader(MemorySegment handle,
                                              float x, float y, float w, float h,
                                              float width, int cap, int join, float miter,
                                              MemorySegment shader, int alpha) {
        try {
            return (int) MH_SURFACE_STROKE_RECT_SHADER.invokeExact(
                handle, x, y, w, h, width, cap, join, miter, shader, (byte) alpha);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_stroke_rect_shader failed", t);
        }
    }

    public static int surfaceStrokeRoundRectShader(MemorySegment handle,
                                                   float x, float y, float w, float h,
                                                   float arcW, float arcH,
                                                   float width, int cap, int join, float miter,
                                                   MemorySegment shader, int alpha) {
        try {
            return (int) MH_SURFACE_STROKE_ROUND_RECT_SHADER.invokeExact(
                handle, x, y, w, h, arcW, arcH,
                width, cap, join, miter, shader, (byte) alpha);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_stroke_round_rect_shader failed", t);
        }
    }

    public static int surfaceStrokeOvalShader(MemorySegment handle,
                                              float x, float y, float w, float h,
                                              float width, int cap, int join, float miter,
                                              MemorySegment shader, int alpha) {
        try {
            return (int) MH_SURFACE_STROKE_OVAL_SHADER.invokeExact(
                handle, x, y, w, h, width, cap, join, miter, shader, (byte) alpha);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_stroke_oval_shader failed", t);
        }
    }

    public static int surfaceStrokeLineShader(MemorySegment handle,
                                              float x1, float y1, float x2, float y2,
                                              float width, int cap, int join, float miter,
                                              MemorySegment shader, int alpha) {
        try {
            return (int) MH_SURFACE_STROKE_LINE_SHADER.invokeExact(
                handle, x1, y1, x2, y2,
                width, cap, join, miter, shader, (byte) alpha);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_stroke_line_shader failed", t);
        }
    }

    public static int surfaceStrokePathShader(MemorySegment handle,
                                              MemorySegment verbs, int verbCount,
                                              MemorySegment coords, int coordCount,
                                              int fillRule,
                                              float width, int cap, int join, float miter,
                                              MemorySegment shader, int alpha) {
        try {
            return (int) MH_SURFACE_STROKE_PATH_SHADER.invokeExact(
                handle, verbs, verbCount, coords, coordCount, fillRule,
                width, cap, join, miter, shader, (byte) alpha);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_stroke_path_shader failed", t);
        }
    }

    // ---- Filter wrappers --------------------------------------------------

    public static MemorySegment filterBlur(float sigmaX, float sigmaY, int tileMode) {
        try {
            return (MemorySegment) MH_FILTER_BLUR.invokeExact(sigmaX, sigmaY, tileMode);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_blur failed", t);
        }
    }

    public static MemorySegment filterDropShadow(float dx, float dy,
                                                 float sigmaX, float sigmaY,
                                                 int r, int g, int b, int a) {
        try {
            return (MemorySegment) MH_FILTER_DROP_SHADOW.invokeExact(
                dx, dy, sigmaX, sigmaY,
                (byte) r, (byte) g, (byte) b, (byte) a);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_drop_shadow failed", t);
        }
    }

    public static MemorySegment filterColorMatrix(MemorySegment matrix20) {
        try {
            return (MemorySegment) MH_FILTER_COLOR_MATRIX.invokeExact(matrix20);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_color_matrix failed", t);
        }
    }

    public static MemorySegment filterCompose(MemorySegment outer, MemorySegment inner) {
        try {
            return (MemorySegment) MH_FILTER_COMPOSE.invokeExact(outer, inner);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_compose failed", t);
        }
    }

    public static void filterDestroy(MemorySegment handle) {
        try { MH_FILTER_DESTROY.invokeExact(handle); }
        catch (Throwable t) { throw new RuntimeException("openjfx_skia_filter_destroy failed", t); }
    }

    // ---- Additional filter primitives -------------------------------------

    public static MemorySegment filterDropShadowOnly(float dx, float dy,
                                                     float sigmaX, float sigmaY,
                                                     int r, int g, int b, int a) {
        try {
            return (MemorySegment) MH_FILTER_DROP_SHADOW_ONLY.invokeExact(
                dx, dy, sigmaX, sigmaY,
                (byte) r, (byte) g, (byte) b, (byte) a);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_drop_shadow_only failed", t);
        }
    }

    public static MemorySegment filterBlend(int blendMode,
                                            MemorySegment background,
                                            MemorySegment foreground) {
        try {
            // Hoist the null-checks into typed locals. A conditional expression
            // passed DIRECTLY to the signature-polymorphic invokeExact becomes a
            // poly expression with target type Object, so the call site's
            // descriptor would carry Object instead of MemorySegment and throw
            // WrongMethodTypeException. A plain local name has a fixed type.
            MemorySegment bg = (background == null) ? MemorySegment.NULL : background;
            MemorySegment fg = (foreground == null) ? MemorySegment.NULL : foreground;
            return (MemorySegment) MH_FILTER_BLEND.invokeExact(blendMode, bg, fg);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_blend failed", t);
        }
    }

    /** {@code filters} is a native MemorySegment holding an array of
     *  ADDRESS-sized handles (use Arena.allocate(JAVA_LONG, count) on
     *  64-bit). */
    public static MemorySegment filterMerge(MemorySegment filtersArray, int count) {
        try {
            return (MemorySegment) MH_FILTER_MERGE.invokeExact(filtersArray, count);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_merge failed", t);
        }
    }

    public static MemorySegment filterOffset(float dx, float dy, MemorySegment input) {
        try {
            MemorySegment in = (input == null) ? MemorySegment.NULL : input;
            return (MemorySegment) MH_FILTER_OFFSET.invokeExact(dx, dy, in);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_offset failed", t);
        }
    }

    public static MemorySegment filterCrop(float x, float y, float w, float h,
                                           MemorySegment input) {
        try {
            MemorySegment in = (input == null) ? MemorySegment.NULL : input;
            return (MemorySegment) MH_FILTER_CROP.invokeExact(x, y, w, h, in);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_crop failed", t);
        }
    }

    public static MemorySegment filterErode(float rx, float ry, MemorySegment input) {
        try {
            MemorySegment in = (input == null) ? MemorySegment.NULL : input;
            return (MemorySegment) MH_FILTER_ERODE.invokeExact(rx, ry, in);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_erode failed", t);
        }
    }

    public static MemorySegment filterDilate(float rx, float ry, MemorySegment input) {
        try {
            MemorySegment in = (input == null) ? MemorySegment.NULL : input;
            return (MemorySegment) MH_FILTER_DILATE.invokeExact(rx, ry, in);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_dilate failed", t);
        }
    }

    public static MemorySegment filterMatrixTransform(float m00, float m01, float m02,
                                                       float m10, float m11, float m12,
                                                       float m20, float m21, float m22,
                                                       MemorySegment input) {
        try {
            MemorySegment in = (input == null) ? MemorySegment.NULL : input;
            return (MemorySegment) MH_FILTER_MATRIX_TRANSFORM.invokeExact(
                m00, m01, m02, m10, m11, m12, m20, m21, m22, in);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_matrix_transform failed", t);
        }
    }

    public static MemorySegment filterDisplacementMap(int channelX, int channelY,
                                                      float scale,
                                                      MemorySegment displacement,
                                                      MemorySegment input) {
        try {
            MemorySegment disp = (displacement == null) ? MemorySegment.NULL : displacement;
            MemorySegment in = (input == null) ? MemorySegment.NULL : input;
            return (MemorySegment) MH_FILTER_DISPLACEMENT_MAP.invokeExact(
                channelX, channelY, scale, disp, in);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_displacement_map failed", t);
        }
    }

    public static MemorySegment filterImage(MemorySegment imageHandle) {
        try {
            return (MemorySegment) MH_FILTER_IMAGE.invokeExact(imageHandle);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_image failed", t);
        }
    }

    public static MemorySegment filterShader(MemorySegment shaderHandle) {
        try {
            return (MemorySegment) MH_FILTER_SHADER.invokeExact(shaderHandle);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_shader failed", t);
        }
    }

    public static MemorySegment filterDistantLitDiffuse(float dx, float dy, float dz,
                                                        int r, int g, int b, int a,
                                                        float surfaceScale, float kd,
                                                        MemorySegment input) {
        try {
            MemorySegment in = (input == null) ? MemorySegment.NULL : input;
            return (MemorySegment) MH_FILTER_DISTANT_LIT_DIFFUSE.invokeExact(
                dx, dy, dz,
                (byte) r, (byte) g, (byte) b, (byte) a,
                surfaceScale, kd, in);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_distant_lit_diffuse failed", t);
        }
    }

    public static MemorySegment filterPointLitDiffuse(float lx, float ly, float lz,
                                                      int r, int g, int b, int a,
                                                      float surfaceScale, float kd,
                                                      MemorySegment input) {
        try {
            MemorySegment in = (input == null) ? MemorySegment.NULL : input;
            return (MemorySegment) MH_FILTER_POINT_LIT_DIFFUSE.invokeExact(
                lx, ly, lz,
                (byte) r, (byte) g, (byte) b, (byte) a,
                surfaceScale, kd, in);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_point_lit_diffuse failed", t);
        }
    }

    public static MemorySegment filterSpotLitDiffuse(float lx, float ly, float lz,
                                                     float tx, float ty, float tz,
                                                     float falloffExp, float cutoffAngleDeg,
                                                     int r, int g, int b, int a,
                                                     float surfaceScale, float kd,
                                                     MemorySegment input) {
        try {
            MemorySegment in = (input == null) ? MemorySegment.NULL : input;
            return (MemorySegment) MH_FILTER_SPOT_LIT_DIFFUSE.invokeExact(
                lx, ly, lz, tx, ty, tz, falloffExp, cutoffAngleDeg,
                (byte) r, (byte) g, (byte) b, (byte) a,
                surfaceScale, kd, in);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_spot_lit_diffuse failed", t);
        }
    }

    public static MemorySegment filterDistantLitSpecular(float dx, float dy, float dz,
                                                         int r, int g, int b, int a,
                                                         float surfaceScale, float ks,
                                                         float shininess,
                                                         MemorySegment input) {
        try {
            MemorySegment in = (input == null) ? MemorySegment.NULL : input;
            return (MemorySegment) MH_FILTER_DISTANT_LIT_SPECULAR.invokeExact(
                dx, dy, dz,
                (byte) r, (byte) g, (byte) b, (byte) a,
                surfaceScale, ks, shininess, in);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_distant_lit_specular failed", t);
        }
    }

    public static MemorySegment filterPointLitSpecular(float lx, float ly, float lz,
                                                       int r, int g, int b, int a,
                                                       float surfaceScale, float ks,
                                                       float shininess,
                                                       MemorySegment input) {
        try {
            MemorySegment in = (input == null) ? MemorySegment.NULL : input;
            return (MemorySegment) MH_FILTER_POINT_LIT_SPECULAR.invokeExact(
                lx, ly, lz,
                (byte) r, (byte) g, (byte) b, (byte) a,
                surfaceScale, ks, shininess, in);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_point_lit_specular failed", t);
        }
    }

    public static MemorySegment filterSpotLitSpecular(float lx, float ly, float lz,
                                                      float tx, float ty, float tz,
                                                      float falloffExp, float cutoffAngleDeg,
                                                      int r, int g, int b, int a,
                                                      float surfaceScale, float ks,
                                                      float shininess,
                                                      MemorySegment input) {
        try {
            MemorySegment in = (input == null) ? MemorySegment.NULL : input;
            return (MemorySegment) MH_FILTER_SPOT_LIT_SPECULAR.invokeExact(
                lx, ly, lz, tx, ty, tz, falloffExp, cutoffAngleDeg,
                (byte) r, (byte) g, (byte) b, (byte) a,
                surfaceScale, ks, shininess, in);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_filter_create_spot_lit_specular failed", t);
        }
    }

    public static MemorySegment surfaceSnapshotToImage(MemorySegment surfaceHandle) {
        try {
            return (MemorySegment) MH_SURFACE_SNAPSHOT_TO_IMAGE.invokeExact(surfaceHandle);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_snapshot_to_image failed", t);
        }
    }

    public static int surfaceSaveLayerWithFilter(MemorySegment handle, MemorySegment filter) {
        try { return (int) MH_SURFACE_SAVE_LAYER_WITH_FILTER.invokeExact(handle, filter); }
        catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_save_layer_with_filter failed", t);
        }
    }

    // ---- SVG wrappers ------------------------------------------------------

    /**
     * True when the native library was compiled with the Skia SVG module
     * (OPENJFX_WITH_SKIA_SVG). When false, the {@code svg*} methods are no-ops
     * and {@link #svgParse} returns 0 so callers fall back to "no SVG".
     */
    public static boolean hasSvg() {
        return MH_SVG_PARSE != null;
    }

    /**
     * Parses UTF-8 SVG markup into a native SkSVGDOM. The bytes are copied on
     * the native side, so {@code utf8} may be reused or freed immediately.
     * Returns a non-zero handle on success, or 0 on parse failure / when the
     * SVG module is unavailable. The returned handle is owned by the caller
     * and must be released exactly once via {@link #svgDestroy}.
     */
    public static long svgParse(byte[] utf8) {
        if (MH_SVG_PARSE == null || utf8 == null || utf8.length == 0) return 0L;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(utf8.length);
            MemorySegment.copy(utf8, 0, seg, ValueLayout.JAVA_BYTE, 0, utf8.length);
            MemorySegment r = (MemorySegment) MH_SVG_PARSE.invokeExact(seg, utf8.length);
            return r.address();
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_svg_parse failed", t);
        }
    }

    /**
     * Writes the SVG's intrinsic width/height into {@code outWidthHeight}
     * (length &gt;= 2). Returns {@code true} on success, {@code false} on a
     * bad/freed handle or when SVG is unavailable.
     */
    public static boolean svgGetSize(long svgHandle, float[] outWidthHeight) {
        if (MH_SVG_GET_SIZE == null || svgHandle == 0L
                || outWidthHeight == null || outWidthHeight.length < 2) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.JAVA_FLOAT, 2);
            int rc = (int) MH_SVG_GET_SIZE.invokeExact(
                MemorySegment.ofAddress(svgHandle), out);
            if (rc != 0) return false;
            outWidthHeight[0] = out.getAtIndex(ValueLayout.JAVA_FLOAT, 0);
            outWidthHeight[1] = out.getAtIndex(ValueLayout.JAVA_FLOAT, 1);
            return true;
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_svg_get_size failed", t);
        }
    }

    /**
     * Renders {@code svgHandle} directly onto {@code surfaceHandle}'s current
     * canvas (under its current matrix + clip) into the logical box (x,y,w,h),
     * as vectors — pixel-perfect at any zoom/DPI, clipped to the box. Composites
     * background -&gt; grid -&gt; SVG -&gt; tint. Returns 0 on success.
     */
    public static int svgRenderInPlace(MemorySegment surfaceHandle, long svgHandle,
                                       float x, float y, float w, float h,
                                       int bgArgb, int tintArgb, int tintMode,
                                       int gridArgb, float gridCell, float gridLineWidth) {
        if (MH_SVG_RENDER_IN_PLACE == null || surfaceHandle == null
                || surfaceHandle.equals(MemorySegment.NULL) || svgHandle == 0L) return -1;
        try {
            return (int) MH_SVG_RENDER_IN_PLACE.invokeExact(
                surfaceHandle,
                MemorySegment.ofAddress(svgHandle),
                x, y, w, h, bgArgb, tintArgb, tintMode,
                gridArgb, gridCell, gridLineWidth);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_svg_render_in_place failed", t);
        }
    }

    /**
     * Releases an SVG handle. Idempotent and safe with 0 — the native side
     * poisons the handle so a later stale use is rejected, not dereferenced.
     */
    public static void svgDestroy(long svgHandle) {
        if (MH_SVG_DESTROY == null || svgHandle == 0L) return;
        try { MH_SVG_DESTROY.invokeExact(MemorySegment.ofAddress(svgHandle)); }
        catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_svg_destroy failed", t);
        }
    }

    // ---- SkImage wrappers --------------------------------------------------

    /**
     * Uploads {@code pixels} as a copy and returns a handle to a fresh
     * SkImage. Returns {@link MemorySegment#NULL} if creation failed.
     */
    public static MemorySegment imageCreateRaster(int width, int height,
                                                  int rowBytes,
                                                  MemorySegment pixels,
                                                  int colorType) {
        try {
            return (MemorySegment) MH_IMAGE_CREATE_RASTER.invokeExact(
                width, height, rowBytes, pixels, colorType);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_image_create_raster failed", t);
        }
    }

    public static void imageDestroy(MemorySegment handle) {
        try { MH_IMAGE_DESTROY.invokeExact(handle); }
        catch (Throwable t) { throw new RuntimeException("openjfx_skia_image_destroy failed", t); }
    }

    /**
     * Encodes an SkImage to a compressed byte stream (PNG / JPEG / WebP).
     * Allocates the result via the native side; caller MUST release with
     * {@link #bufferFree} once the bytes have been consumed. Returns
     * {@code null} on any failure (Skia not compiled in, unsupported
     * format, encode error).
     *
     * @param handle  image handle from {@link #imageCreateRaster} etc.
     * @param format  one of {@link #FMT_PNG}, {@link #FMT_JPEG}, {@link #FMT_WEBP}
     * @param quality 0..100 (PNG ignores; JPEG/WebP use lossy quality)
     */
    public static byte[] imageEncode(MemorySegment handle, int format, int quality) {
        if (handle == null || handle.equals(MemorySegment.NULL)) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outPtr  = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment outSize = arena.allocate(ValueLayout.JAVA_INT);
            int status;
            try {
                status = (int) MH_IMAGE_ENCODE.invokeExact(
                    handle, format, quality, outPtr, outSize);
            } catch (Throwable t) {
                throw new RuntimeException("openjfx_skia_image_encode failed", t);
            }
            if (status != 0) return null;

            MemorySegment ptrSeg = outPtr.get(ValueLayout.ADDRESS, 0L);
            int size = outSize.get(ValueLayout.JAVA_INT, 0L);
            if (ptrSeg == null || ptrSeg.equals(MemorySegment.NULL) || size <= 0) {
                return null;
            }
            long addr = ptrSeg.address();
            try {
                MemorySegment data = MemorySegment.ofAddress(addr).reinterpret(size);
                byte[] out = new byte[size];
                MemorySegment.copy(data, ValueLayout.JAVA_BYTE, 0L, out, 0, size);
                return out;
            } finally {
                bufferFree(addr);
            }
        }
    }

    /** Releases a native buffer returned by {@link #imageEncode}. */
    public static void bufferFree(long ptr) {
        if (ptr == 0L) return;
        try { MH_BUFFER_FREE.invokeExact(MemorySegment.ofAddress(ptr)); }
        catch (Throwable t) { throw new RuntimeException("openjfx_skia_buffer_free failed", t); }
    }

    /**
     * Uploads three planar YUV (I420) planes as a single SkImage. Skia
     * does the YUV→RGB conversion in its shader at sample time (GPU
     * when Ganesh is active) — far cheaper than CPU YUV→BGRA in the
     * raster path. Used by SkiaMediaTexture for video frames whose
     * source MediaFrame has pixel-format MULTI_YCbCr_420.
     *
     * @param colorSpace one of {@link #YUV_BT601}, {@link #YUV_BT709},
     *                   {@link #YUV_BT2020}.
     * @return image handle, or NULL on failure / symbol unavailable.
     */
    public static MemorySegment imageCreateYuvI420(
            MemorySegment yPlane, int yStride,
            MemorySegment uPlane, int uStride,
            MemorySegment vPlane, int vStride,
            int width, int height, int colorSpace) {
        if (MH_IMAGE_CREATE_YUV_I420 == null) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) MH_IMAGE_CREATE_YUV_I420.invokeExact(
                yPlane, yStride, uPlane, uStride, vPlane, vStride,
                width, height, colorSpace);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_image_create_yuv_i420 failed", t);
        }
    }

    /** True if the native lib was built with the YUV-native upload path. */
    public static boolean hasYuvI420() {
        return MH_IMAGE_CREATE_YUV_I420 != null;
    }

    /**
     * HDR-aware planar YUV upload. Lets the caller pass the full colour
     * descriptor (transfer function + primaries + range + peak nits)
     * instead of just a YUV-matrix tag, so a PQ / HLG source ends up
     * tagged correctly and is tone-mapped on the GPU before being
     * handed back as an sRGB-tagged SkImage.
     *
     * Returns {@link MemorySegment#NULL} when the native bridge was
     * built without HDR support, or when the GPU tone-map pipeline
     * couldn't be set up. Java callers fall back to
     * {@link com.sun.prism.skia.impl.HdrToneMap} on the CPU.
     *
     * @param yuvMatrix   {@link #YUV_BT601} / {@link #YUV_BT709} /
     *                    {@link #YUV_BT2020} / {@link #YUV_JPEG_FULL}
     * @param transferFn  {@link #TFN_SRGB} / {@link #TFN_REC709} /
     *                    {@link #TFN_PQ} / {@link #TFN_HLG} /
     *                    {@link #TFN_LINEAR}
     * @param primaries   {@link #PRI_SRGB} / {@link #PRI_REC2020} /
     *                    {@link #PRI_DCI_P3} / {@link #PRI_REC601}
     * @param fullRange   1 if YUV values are full (0-255), 0 if limited
     * @param srcPeakNits source content peak luminance, 0 = auto-pick
     *                    (1000 nits for HDR10/HLG, 100 for SDR)
     * @param dstPeakNits display target peak luminance, 0 = 100 nits
     */
    public static MemorySegment imageCreateYuvHdr(
            MemorySegment yPlane, int yStride,
            MemorySegment uPlane, int uStride,
            MemorySegment vPlane, int vStride,
            int width, int height,
            int yuvMatrix, int transferFn, int primaries,
            int fullRange, float srcPeakNits, float dstPeakNits) {
        if (MH_IMAGE_CREATE_YUV_HDR == null) return MemorySegment.NULL;
        try {
            return (MemorySegment) MH_IMAGE_CREATE_YUV_HDR.invokeExact(
                yPlane, yStride, uPlane, uStride, vPlane, vStride,
                width, height,
                yuvMatrix, transferFn, primaries,
                fullRange, srcPeakNits, dstPeakNits);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_image_create_yuv_hdr failed", t);
        }
    }

    /** True if the native bridge has HDR support (transfer-function
     *  aware YUV upload + BT.2390 GPU tone mapping). */
    public static boolean hasHdrPipeline() {
        if (MH_HAS_HDR_PIPELINE == null) return false;
        try {
            return ((int) MH_HAS_HDR_PIPELINE.invokeExact()) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Wrap an existing GL RGBA8 texture as a borrowed-reference
     *  SkImage. Returns {@code MemorySegment.NULL} when the native
     *  symbol is missing (stub builds) or the wrap fails. */
    public static MemorySegment imageCreateFromGlTexture(
            int glTextureName, int width, int height) {
        if (MH_IMAGE_CREATE_FROM_GL_TEXTURE == null) return MemorySegment.NULL;
        try {
            return (MemorySegment) MH_IMAGE_CREATE_FROM_GL_TEXTURE
                .invokeExact(glTextureName, width, height);
        } catch (Throwable t) {
            throw new RuntimeException("image_create_from_gl_texture failed", t);
        }
    }

    /** True when the GL-texture-borrow path is wired in this native lib. */
    public static boolean hasGlTextureImage() {
        return MH_IMAGE_CREATE_FROM_GL_TEXTURE != null;
    }

    public static int imageWidth(MemorySegment handle) {
        try { return (int) MH_IMAGE_WIDTH.invokeExact(handle); }
        catch (Throwable t) { throw new RuntimeException("openjfx_skia_image_width failed", t); }
    }

    public static int imageHeight(MemorySegment handle) {
        try { return (int) MH_IMAGE_HEIGHT.invokeExact(handle); }
        catch (Throwable t) { throw new RuntimeException("openjfx_skia_image_height failed", t); }
    }

    // ---- Text / glyph wrappers ---------------------------------------------

    /**
     * Builds an SkTypeface from in-memory font-file bytes (TTF/OTF/TTC).
     * Returns the native handle; the caller checks for
     * {@link MemorySegment#NULL} to detect failure.
     */
    public static MemorySegment typefaceCreateFromData(MemorySegment data, int length) {
        try {
            return (MemorySegment) MH_TYPEFACE_CREATE_FROM_DATA.invokeExact(data, length);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_typeface_create_from_data failed", t);
        }
    }

    public static void typefaceDestroy(MemorySegment handle) {
        try { MH_TYPEFACE_DESTROY.invokeExact(handle); }
        catch (Throwable t) { throw new RuntimeException("openjfx_skia_typeface_destroy failed", t); }
    }

    /**
     * Draws a run of {@code count} glyphs with {@code typeface} at
     * {@code fontSize}. {@code glyphIds} is a {@code uint16} buffer;
     * {@code posX}/{@code posY} are {@code float} buffers holding one
     * baseline-origin position per glyph, in surface coordinates.
     */
    public static int surfaceDrawGlyphs(MemorySegment surface, MemorySegment typeface,
                                        float fontSize,
                                        MemorySegment glyphIds, int count,
                                        MemorySegment posX, MemorySegment posY,
                                        int r, int g, int b, int a) {
        try {
            return (int) MH_SURFACE_DRAW_GLYPHS.invokeExact(
                surface, typeface, fontSize,
                glyphIds, count, posX, posY,
                (byte) r, (byte) g, (byte) b, (byte) a);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_draw_glyphs failed", t);
        }
    }

    /**
     * Shader-filled glyph run: fills the glyph coverage with {@code shader}
     * (a gradient / image-pattern SkShader handle) instead of a solid colour.
     */
    public static int surfaceDrawGlyphsShader(MemorySegment surface, MemorySegment typeface,
                                              float fontSize,
                                              MemorySegment glyphIds, int count,
                                              MemorySegment posX, MemorySegment posY,
                                              MemorySegment shader, int alpha) {
        try {
            return (int) MH_SURFACE_DRAW_GLYPHS_SHADER.invokeExact(
                surface, typeface, fontSize,
                glyphIds, count, posX, posY,
                shader, (byte) alpha);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_draw_glyphs_shader failed", t);
        }
    }

    public static int surfaceDrawImage(MemorySegment surface, MemorySegment image,
                                       float dx, float dy, float dw, float dh) {
        try {
            return (int) MH_SURFACE_DRAW_IMAGE.invokeExact(surface, image, dx, dy, dw, dh);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_draw_image failed", t);
        }
    }

    public static int surfaceDrawSurface(MemorySegment dstSurface, MemorySegment srcSurface,
                                         float sx, float sy, float sw, float sh,
                                         float dx, float dy, float dw, float dh) {
        try {
            return (int) MH_SURFACE_DRAW_SURFACE.invokeExact(
                dstSurface, srcSurface, sx, sy, sw, sh, dx, dy, dw, dh);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_draw_surface failed", t);
        }
    }

    public static int surfaceDrawSurfaceVO(MemorySegment dstSurface, MemorySegment srcSurface,
                                           float dx1, float dy1, float dx2, float dy2,
                                           float sx1, float sy1, float sx2, float sy2,
                                           float topOpacity, float botOpacity) {
        try {
            return (int) MH_SURFACE_DRAW_SURFACE_VO.invokeExact(
                dstSurface, srcSurface,
                dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2,
                topOpacity, botOpacity);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_draw_surface_vo failed", t);
        }
    }

    public static int surfaceDrawImageRect(MemorySegment surface, MemorySegment image,
                                           float sx, float sy, float sw, float sh,
                                           float dx, float dy, float dw, float dh) {
        try {
            return (int) MH_SURFACE_DRAW_IMAGE_RECT.invokeExact(
                surface, image, sx, sy, sw, sh, dx, dy, dw, dh);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_draw_image_rect failed", t);
        }
    }

    /**
     * Composites a BGRA8888 (premultiplied) buffer at native address
     * {@code pixels} onto the scene canvas {@code surfaceHandle}, scaling
     * src into the dst rect. Used by the Blink WebView's off-screen frame path.
     */
    public static int surfaceDrawBgra(long surfaceHandle, long pixels,
                                      int srcW, int srcH, int srcStride,
                                      int dstX, int dstY, int dstW, int dstH) {
        try {
            return (int) MH_SURFACE_DRAW_BGRA.invokeExact(
                surfaceHandle, pixels, srcW, srcH, srcStride,
                dstX, dstY, dstW, dstH);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_draw_bgra failed", t);
        }
    }

    // ---- Arbitrary path wrappers -------------------------------------------

    public static int surfaceFillPath(MemorySegment handle,
                                      MemorySegment verbs,  int verbCount,
                                      MemorySegment coords, int coordCount,
                                      int fillRule,
                                      int r, int g, int b, int a) {
        try {
            return (int) MH_SURFACE_FILL_PATH.invokeExact(
                handle, verbs, verbCount, coords, coordCount,
                fillRule,
                (byte) r, (byte) g, (byte) b, (byte) a);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_fill_path failed", t);
        }
    }

    public static int surfaceFillPathBlur(MemorySegment handle,
                                          MemorySegment verbs,  int verbCount,
                                          MemorySegment coords, int coordCount,
                                          int fillRule, float sigma,
                                          int r, int g, int b, int a) {
        try {
            return (int) MH_SURFACE_FILL_PATH_BLUR.invokeExact(
                handle, verbs, verbCount, coords, coordCount,
                fillRule, sigma,
                (byte) r, (byte) g, (byte) b, (byte) a);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_fill_path_blur failed", t);
        }
    }

    public static int surfaceStrokePath(MemorySegment handle,
                                        MemorySegment verbs,  int verbCount,
                                        MemorySegment coords, int coordCount,
                                        int fillRule,
                                        float width, int cap, int join, float miter,
                                        int r, int g, int b, int a) {
        try {
            return (int) MH_SURFACE_STROKE_PATH.invokeExact(
                handle, verbs, verbCount, coords, coordCount,
                fillRule,
                width, cap, join, miter,
                (byte) r, (byte) g, (byte) b, (byte) a);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_stroke_path failed", t);
        }
    }

    /**
     * Switches the SkSurface's backing memory to caller-provided
     * pixels (zero-copy: subsequent SkCanvas draws land directly in
     * {@code dst}). Pixel layout is BGRA8888 premultiplied =
     * INT_ARGB_PRE.
     */
    public static int surfaceReplaceBackingArgb(MemorySegment handle, MemorySegment dst,
                                                int width, int height, int rowBytes) {
        try {
            return (int) MH_SURFACE_REPLACE_BACKING_ARGB.invokeExact(
                handle, dst, width, height, rowBytes);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_replace_backing_argb failed", t);
        }
    }

    /**
     * Reads pixels in INT_ARGB_PRE layout (BGRA bytes on little-endian)
     * — the format Glass expects. Skia handles the channel conversion
     * inside its readPixels path; Java doesn't need to swap.
     */
    public static int surfaceReadPixelsArgb(MemorySegment handle, MemorySegment dst,
                                            int x, int y, int w, int h) {
        try {
            return (int) MH_SURFACE_READ_PIXELS_ARGB.invokeExact(handle, dst, x, y, w, h);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_read_pixels_argb failed", t);
        }
    }

    /** True when the native lib ships the dirty-rect readback symbol. */
    public static boolean hasReadPixelsArgbStride() {
        return MH_SURFACE_READ_PIXELS_ARGB_STRIDE != null;
    }

    /**
     * Dirty-rect readback in INT_ARGB_PRE layout: reads the (x,y,w,h)
     * sub-rect into the FULL-FRAME buffer {@code dst} (stride
     * {@code dstRowBytes}), landing it at its natural offset so the
     * buffer stays a coherent full frame. Callers must check
     * {@link #hasReadPixelsArgbStride()} first.
     */
    public static int surfaceReadPixelsArgbStride(MemorySegment handle, MemorySegment dst,
                                                  int dstRowBytes,
                                                  int x, int y, int w, int h) {
        try {
            return (int) MH_SURFACE_READ_PIXELS_ARGB_STRIDE.invokeExact(
                handle, dst, dstRowBytes, x, y, w, h);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_read_pixels_argb_stride failed", t);
        }
    }

    /** Reads an RGBA8888 (premul) rect from the surface into {@code dst}. */
    public static int surfaceReadPixels(MemorySegment handle, MemorySegment dst,
                                        int x, int y, int w, int h) {
        try {
            return (int) MH_SURFACE_READ_PIXELS.invokeExact(handle, dst, x, y, w, h);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_surface_read_pixels failed", t);
        }
    }

    /**
     * Draws an anti-aliased filled rectangle into the buffer using
     * SkPaint + SkCanvas::drawRect (Skia-enabled build) or a non-AA
     * pixel fill (stub build). Returns 0 on success.
     */
    public static int fillRect(MemorySegment pixels,
                               int width, int height, int rowBytes,
                               int x, int y, int rectW, int rectH,
                               int r, int g, int b, int a) {
        try {
            return (int) MH_FILL_RECT.invokeExact(
                pixels, width, height, rowBytes,
                x, y, rectW, rectH,
                (byte) r, (byte) g, (byte) b, (byte) a);
        } catch (Throwable t) {
            throw new RuntimeException("openjfx_skia_fill_rect failed", t);
        }
    }

    // ---- SkPicture record / replay (Task #31) ------------------------------

    /** Allocate a new picture recorder. Returned handle must be freed by
     *  {@link #pictureRecorderDestroy(MemorySegment)}. */
    public static MemorySegment pictureRecorderCreate() {
        try { return (MemorySegment) MH_PICTURE_RECORDER_CREATE.invokeExact(); }
        catch (Throwable t) { throw new RuntimeException("picture_recorder_create failed", t); }
    }

    /** Start a new recording on {@code recorder}, clipped to the given
     *  rectangle. Returns a SURFACE handle that {@code surface*} draw
     *  ops can target — Skia routes every draw into the recorder's
     *  internal canvas. The returned handle becomes invalid after
     *  {@link #pictureRecorderFinish}. */
    public static MemorySegment pictureRecorderBegin(MemorySegment recorder,
                                                     float x, float y,
                                                     float w, float h) {
        try {
            return (MemorySegment) MH_PICTURE_RECORDER_BEGIN.invokeExact(
                recorder, x, y, w, h);
        } catch (Throwable t) {
            throw new RuntimeException("picture_recorder_begin failed", t);
        }
    }

    /** Finalize the current recording and return an SkPicture handle.
     *  The recorder is reusable for the next recording. The picture
     *  handle must be freed by {@link #pictureDestroy(MemorySegment)}. */
    public static MemorySegment pictureRecorderFinish(MemorySegment recorder) {
        try { return (MemorySegment) MH_PICTURE_RECORDER_FINISH.invokeExact(recorder); }
        catch (Throwable t) { throw new RuntimeException("picture_recorder_finish failed", t); }
    }

    public static void pictureDestroy(MemorySegment picture) {
        try { MH_PICTURE_DESTROY.invokeExact(picture); }
        catch (Throwable t) { throw new RuntimeException("picture_destroy failed", t); }
    }

    public static void pictureRecorderDestroy(MemorySegment recorder) {
        try { MH_PICTURE_RECORDER_DESTROY.invokeExact(recorder); }
        catch (Throwable t) { throw new RuntimeException("picture_recorder_destroy failed", t); }
    }

    /** Replay {@code picture} into {@code targetSurface} translated by
     *  {@code (dx, dy)}. Returns 0 on success. */
    public static int surfaceDrawPicture(MemorySegment targetSurface,
                                         MemorySegment picture,
                                         float dx, float dy) {
        try {
            return (int) MH_SURFACE_DRAW_PICTURE.invokeExact(
                targetSurface, picture, dx, dy);
        } catch (Throwable t) {
            throw new RuntimeException("surface_draw_picture failed", t);
        }
    }

    // ---- D3D11 ⇄ GL interop (Windows / WGL_NV_DX_interop2) ------------------
    //
    // The interop module is built into openjfx_skia_shared on Windows but
    // stubbed out on macOS / Linux. All handles below use Optional lookup
    // so a stub build leaves them null and {@link #hasD3d11Interop()}
    // reports false — callers fall back to the CPU upload path without
    // crashing <clinit>.

    private static final MethodHandle MH_D3D11_INTEROP_INIT =
        LOOKUP.find("openjfx_skia_d3d11_interop_init")
              .map(seg -> LINKER.downcallHandle(seg,
                  FunctionDescriptor.of(ValueLayout.JAVA_INT)))
              .orElse(null);

    private static final MethodHandle MH_D3D11_INTEROP_READY =
        LOOKUP.find("openjfx_skia_d3d11_interop_ready")
              .map(seg -> LINKER.downcallHandle(seg,
                  FunctionDescriptor.of(ValueLayout.JAVA_INT)))
              .orElse(null);

    private static final MethodHandle MH_D3D11_INTEROP_REGISTER_TEXTURE =
        LOOKUP.find("openjfx_skia_d3d11_interop_register_texture")
              .map(seg -> LINKER.downcallHandle(seg,
                  FunctionDescriptor.of(
                      ValueLayout.ADDRESS,   // return: opaque handle (NULL on failure)
                      ValueLayout.ADDRESS,   // ID3D11Texture2D*
                      ValueLayout.ADDRESS))) // uint32_t* glTextureOut
              .orElse(null);

    private static final MethodHandle MH_D3D11_INTEROP_LOCK =
        LOOKUP.find("openjfx_skia_d3d11_interop_lock")
              .map(seg -> LINKER.downcallHandle(seg,
                  FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)))
              .orElse(null);

    private static final MethodHandle MH_D3D11_INTEROP_UNLOCK =
        LOOKUP.find("openjfx_skia_d3d11_interop_unlock")
              .map(seg -> LINKER.downcallHandle(seg,
                  FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)))
              .orElse(null);

    private static final MethodHandle MH_D3D11_INTEROP_UNREGISTER_TEXTURE =
        LOOKUP.find("openjfx_skia_d3d11_interop_unregister_texture")
              .map(seg -> LINKER.downcallHandle(seg,
                  FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)))
              .orElse(null);

    /** True when the interop module is present and all expected entry
     *  points were found. False on non-Windows or stub builds. */
    public static boolean hasD3d11Interop() {
        return MH_D3D11_INTEROP_INIT != null
            && MH_D3D11_INTEROP_READY != null
            && MH_D3D11_INTEROP_REGISTER_TEXTURE != null
            && MH_D3D11_INTEROP_LOCK != null
            && MH_D3D11_INTEROP_UNLOCK != null
            && MH_D3D11_INTEROP_UNREGISTER_TEXTURE != null;
    }

    /** Initialise the process-wide D3D11 device + WGL interop handle.
     *  Must be called with a GL context current on the calling thread.
     *  Returns 1 on success, 0 on failure or when {@link #hasD3d11Interop()}
     *  is false. Idempotent. */
    public static int d3d11InteropInit() {
        if (MH_D3D11_INTEROP_INIT == null) return 0;
        try { return (int) MH_D3D11_INTEROP_INIT.invokeExact(); }
        catch (Throwable t) { throw new RuntimeException("d3d11_interop_init failed", t); }
    }

    /** Returns 1 if init succeeded and the interop pipeline is usable. */
    public static int d3d11InteropReady() {
        if (MH_D3D11_INTEROP_READY == null) return 0;
        try { return (int) MH_D3D11_INTEROP_READY.invokeExact(); }
        catch (Throwable t) { throw new RuntimeException("d3d11_interop_ready failed", t); }
    }

    /** Register a D3D11 texture with the WGL interop device. Returns
     *  the opaque interop handle (zero on failure). The aliased GL
     *  texture name is written into {@code glTextureOut[0]}. */
    public static MemorySegment d3d11InteropRegisterTexture(
            MemorySegment d3d11Texture, MemorySegment glTextureOut) {
        if (MH_D3D11_INTEROP_REGISTER_TEXTURE == null) return MemorySegment.NULL;
        try {
            return (MemorySegment) MH_D3D11_INTEROP_REGISTER_TEXTURE
                .invokeExact(d3d11Texture, glTextureOut);
        } catch (Throwable t) {
            throw new RuntimeException("d3d11_interop_register_texture failed", t);
        }
    }

    /** Lock the registered texture for GL sampling. Pair with unlock
     *  around every draw. Returns 1 on success. */
    public static int d3d11InteropLock(MemorySegment handle) {
        if (MH_D3D11_INTEROP_LOCK == null) return 0;
        try { return (int) MH_D3D11_INTEROP_LOCK.invokeExact(handle); }
        catch (Throwable t) { throw new RuntimeException("d3d11_interop_lock failed", t); }
    }

    /** Unlock the texture. Returns 1 on success. */
    public static int d3d11InteropUnlock(MemorySegment handle) {
        if (MH_D3D11_INTEROP_UNLOCK == null) return 0;
        try { return (int) MH_D3D11_INTEROP_UNLOCK.invokeExact(handle); }
        catch (Throwable t) { throw new RuntimeException("d3d11_interop_unlock failed", t); }
    }

    /** Unregister + delete the aliased GL texture. The underlying D3D11
     *  texture is NOT released. */
    public static void d3d11InteropUnregisterTexture(MemorySegment handle) {
        if (MH_D3D11_INTEROP_UNREGISTER_TEXTURE == null) return;
        try { MH_D3D11_INTEROP_UNREGISTER_TEXTURE.invokeExact(handle); }
        catch (Throwable t) { throw new RuntimeException("d3d11_interop_unregister_texture failed", t); }
    }

    // ---- Library loading ----------------------------------------------------

    private static SymbolLookup loadLibrary() {
        // Search order:
        //   1. -Dopenjfx.skia.nativeLib=<absolute path>   (explicit override; debug + dev)
        //   2. dev tree: javafx.graphics/build/native/<host>/lib/<libname>
        //   3. Extract from jar resources via NativeLibLoader  ← end-user path,
        //      mirrors how stock OpenJFX auto-loads `glass`, `javafx_font`, etc.
        //      from per-module jars. Native libs ship at the jar root, the
        //      loader copies them into ~/.openjfx/cache/<version>/<arch>/
        //      on first call and System.load()s from there.
        //   4. java.library.path via System.loadLibrary       (last resort)
        String override = System.getProperty("openjfx.skia.nativeLib");
        if (override != null) {
            return SymbolLookup.libraryLookup(Path.of(override), Arena.ofAuto());
        }

        Path devPath = locateDevBuild();
        if (devPath != null && Files.exists(devPath)) {
            return SymbolLookup.libraryLookup(devPath, Arena.ofAuto());
        }

        // NativeLibLoader.loadLibrary first checks java.library.path /
        // System.loadLibrary, then falls back to the in-jar resource
        // extraction. For deployed apps that consume sdk/lib/*.jar on
        // the module path, this is the path that fires — no
        // -Djava.library.path required from the user.
        try {
            com.sun.glass.utils.NativeLibLoader.loadLibrary(LIB_BASENAME);
            return SymbolLookup.loaderLookup();
        } catch (UnsatisfiedLinkError ule) {
            throw new IllegalStateException(
                "Native library '" + LIB_BASENAME + "' not found. " +
                "Set -Dopenjfx.skia.nativeLib=<path> or run " +
                "`./gradlew :javafx.graphics:nativeCompile` first.", ule);
        }
    }

    private static Path locateDevBuild() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        String platform = os.contains("win") ? "win"
                        : os.contains("mac") ? "mac"
                        : "linux";
        String archDir = (arch.equals("amd64") || arch.equals("x86_64")) ? "x64" : "arm64";
        // Filename candidates: MSVC produces <name>.dll, MinGW produces
        // lib<name>.dll, others use lib<name>.{so,dylib}.
        String[] filenames = switch (platform) {
            case "win"   -> new String[] {
                LIB_BASENAME + ".dll",
                "lib" + LIB_BASENAME + ".dll",
            };
            case "mac"   -> new String[] { "lib" + LIB_BASENAME + ".dylib" };
            default      -> new String[] { "lib" + LIB_BASENAME + ".so" };
        };
        // Flat skia-fx layout (no `modules/` wrapper): the per-module
        // native build output sits at <repo>/javafx.graphics/build/native/<host>/lib/.
        Path libDir = Path.of("javafx.graphics/build/native")
            .resolve(platform + "-" + archDir)
            .resolve("lib");
        for (String filename : filenames) {
            Path candidate = libDir.resolve(filename);
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath();
            }
        }
        return null;
    }
}
