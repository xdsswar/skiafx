package com.sun.prism.skia;

import com.sun.prism.Image;
import com.sun.prism.MediaFrame;
import com.sun.prism.PixelFormat;
import com.sun.prism.Texture;
import com.sun.prism.Texture.WrapMode;
import com.sun.prism.skia.impl.HdrToneMap;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.prism.skia.impl.NativeHandles;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Skia-backed {@link Texture} for media (video) frames.
 *
 * <p>Phase-2 (BGRA scaffolding) implementation. Each video frame is
 * uploaded as a fresh {@code SkImage}; the previous frame's handle is
 * destroyed on {@link #update(MediaFrame, boolean) update()}. This
 * keeps the integration plumbing simple while validating the JNI →
 * Skia path end-to-end. Phase 3 will replace this with a zero-copy
 * hardware-shared-texture import (D3D11VA → WGL_NV_DX_interop2 → Skia
 * GL, then IOSurface→Metal on Mac, DRM_PRIME→EGL on Linux).</p>
 *
 * <p>Format support today: any {@link MediaFrame} that can convert to
 * {@link PixelFormat#INT_ARGB_PRE} via
 * {@link MediaFrame#convertToFormat(PixelFormat)} — BGRA frames flow
 * through directly, YUV frames hit a CPU conversion path. Multi-plane
 * native upload (YUV → {@code SkImage::MakeFromYUVAPixmaps}) is the
 * next step inside Phase 2/3 once zero-copy is design-locked.</p>
 *
 * <p>This class deliberately does <b>not</b> extend
 * {@link SkiaTextureBase}: that class's native handle is owned by a
 * single {@code NativeHandles.Slot} that's set once at construction.
 * Media textures need to swap the underlying {@code SkImage} every
 * frame, so we manage the handle directly in an {@link AtomicLong}
 * and implement the {@link Texture} surface ourselves.</p>
 */
public final class SkiaMediaTexture implements Texture {

    /** Optional override of the YUV color-space tag passed to the
     *  native upload. -1 means "pick automatically from the source
     *  resolution". Override via {@code -Dskia.media.yuvColorSpace=N}
     *  with N = one of {@code NativeBridge.YUV_*} constants. */
    private static final int YUV_COLORSPACE_OVERRIDE =
        Integer.getInteger("skia.media.yuvColorSpace", -1);

    /** Pick the YUV→RGB matrix for the upload.
     *
     *  Decision order:
     *   1. {@code -Dskia.media.yuvColorSpace=N} explicit override (debug).
     *   2. {@link MediaFrame#getYuvColorSpace()} — the demuxer's
     *      caps-level metadata, plumbed through
     *      {@code com.sun.media.jfxmedia.control.VideoDataBuffer} →
     *      {@code com.sun.javafx.media.PrismMediaFrameHandler}. Any
     *      well-formed file (MP4 with a {@code colr} atom, MKV with a
     *      {@code Colour} element, WebM's {@code matroska} colorimetry
     *      etc.) carries this and we get the correct matrix without
     *      any guessing.
     *   3. Resolution-based default — matches libavutil's
     *      {@code ff_default_csp_from_dims} exactly: any frame whose
     *      luma is at least 1280×720 is assumed BT.709 (HDTV/4K
     *      standard); smaller frames are BT.601 (SDTV standard).
     *      ffplay, mpv, VLC and every other ffmpeg-based player use
     *      this same fallback. Files that go through Windows Media
     *      Foundation or DirectShow (mfwrapper / dshowwrapper) often
     *      arrive with no colorimetry on caps — for those, this
     *      heuristic is what gets us correct skin tones on 4K AV1,
     *      HD H.264, etc. content without any caps metadata. */
    private static int autoYuvColorSpace(MediaFrame frame, int width, int height) {
        if (YUV_COLORSPACE_OVERRIDE >= 0) return YUV_COLORSPACE_OVERRIDE;
        // 2. caps-level hint from the demuxer (proper auto-detect)
        int fromCaps = (frame != null) ? frame.getYuvColorSpace()
                                       : MediaFrame.YUV_COLORSPACE_AUTO;
        if (fromCaps != MediaFrame.YUV_COLORSPACE_AUTO) {
            switch (fromCaps) {
                case MediaFrame.YUV_COLORSPACE_BT601:  return NativeBridge.YUV_BT601;
                case MediaFrame.YUV_COLORSPACE_BT709:  return NativeBridge.YUV_BT709;
                case MediaFrame.YUV_COLORSPACE_BT2020: return NativeBridge.YUV_BT2020;
                case MediaFrame.YUV_COLORSPACE_JPEG:   return NativeBridge.YUV_JPEG_FULL;
            }
        }
        // 3. Resolution heuristic — libavutil's ff_default_csp_from_dims.
        return (width >= 1280 || height >= 720)
                ? NativeBridge.YUV_BT709
                : NativeBridge.YUV_BT601;
    }

    /** Pretty-print a {@link MediaFrame#YUV_COLORSPACE_*} value for the
     *  one-shot first-frame diagnostic. */
    private static String yuvNameForCaps(int v) {
        if (v == MediaFrame.YUV_COLORSPACE_BT601)  return "BT601";
        if (v == MediaFrame.YUV_COLORSPACE_BT709)  return "BT709";
        if (v == MediaFrame.YUV_COLORSPACE_BT2020) return "BT2020";
        if (v == MediaFrame.YUV_COLORSPACE_JPEG)   return "JPEG-full";
        return "AUTO(none)";
    }

    /** Pretty-print a {@link NativeBridge#YUV_*} value for the
     *  one-shot first-frame diagnostic. */
    private static String yuvNameForNative(int v) {
        if (v == NativeBridge.YUV_BT601)     return "BT601";
        if (v == NativeBridge.YUV_BT709)     return "BT709";
        if (v == NativeBridge.YUV_BT2020)    return "BT2020";
        if (v == NativeBridge.YUV_JPEG_FULL) return "JPEG-full";
        return "?(" + v + ")";
    }

    // ------------------------------------------------------------------
    // HDR pipeline configuration.
    // ------------------------------------------------------------------

    private enum HdrPath { AUTO, GPU, CPU, OFF }

    /** Aggregate debug-log gate. Surfaces verbose diagnostic output
     *  when {@code SKIA_MEDIA_DEBUG=1} is in the env or
     *  {@code -Dskia.media.debug=true} is on the command line.
     *  Quiet by default so a normal user's console isn't spammed
     *  with single-shot pipeline diagnostics. */
    private static boolean isMediaDebug() {
        if (Boolean.getBoolean("skia.media.debug")) return true;
        String env = System.getenv("SKIA_MEDIA_DEBUG");
        return env != null && !env.isEmpty() && !"0".equals(env)
                && !"false".equalsIgnoreCase(env);
    }

    /** Reads the public {@code skia.media.decode} property that
     *  {@link javafx.application.Application#setDecodeMethod} writes.
     *  We can't reference the {@code MediaDecoding} class directly
     *  (it lives in javafx.media, an upstream module from javafx.
     *  graphics), so we duplicate the property read — by-value
     *  coupling is fine, both sides agree on the same well-known
     *  string. */
    private static boolean decodeModeIsCpuOnly() {
        String v = System.getProperty("skia.media.decode", "AUTO");
        return v != null && "CPU".equalsIgnoreCase(v.trim());
    }

    /** Effective HDR path for this texture. Resolved on every frame
     *  so a runtime {@code Application.setDecodeMethod()} takes
     *  effect immediately.
     *
     *  <p>Decision order: CPU master-switch → per-feature
     *  {@code -Dskia.media.hdrPath} → AUTO default. CPU mode (set
     *  via {@code Application.setDecodeMethod(DecodeMethod.CPU)})
     *  forces {@link HdrPath#CPU} regardless — that's the whole
     *  point of "I am on a GPU-less machine".</p> */
    private static HdrPath currentHdrPath() {
        if (decodeModeIsCpuOnly()) return HdrPath.CPU;
        String v = System.getProperty("skia.media.hdrPath", "auto")
                         .toLowerCase(Locale.ROOT);
        switch (v) {
            case "gpu":  return HdrPath.GPU;
            case "cpu":  return HdrPath.CPU;
            case "off":  return HdrPath.OFF;
            default:     return HdrPath.AUTO;
        }
    }

    /** Source mastering peak in nits. 0 = autodetect from frame metadata
     *  (falls back to 1000 nits when neither file nor flag carries it). */
    private static final float HDR_SRC_PEAK_NITS =
        Float.parseFloat(System.getProperty("skia.media.hdrPeakNits", "0"));

    /** Display target peak in nits. Default 100 (sRGB). Set higher
     *  on actual HDR displays once the platform exposes that. */
    private static final float HDR_DST_PEAK_NITS =
        Float.parseFloat(System.getProperty("skia.media.hdrDisplayNits", "100"));

    /** {@code -Dskia.media.forceHdr=pq|hlg|sdr|auto} — pretend the
     *  source has this transfer, ignoring caps and heuristics.
     *  Useful for testing the HDR path on a file whose metadata
     *  doesn't reach the consumer. */
    private static final int HDR_TRANSFER_FORCE = parseHdrTransferForce();
    private static int parseHdrTransferForce() {
        String v = System.getProperty("skia.media.forceHdr", "auto")
                         .toLowerCase(Locale.ROOT);
        switch (v) {
            case "pq":   return MediaFrame.TRANSFER_PQ;
            case "hlg":  return MediaFrame.TRANSFER_HLG;
            case "sdr":  return MediaFrame.TRANSFER_SRGB;
            case "709":  return MediaFrame.TRANSFER_REC709;
            default:     return MediaFrame.TRANSFER_AUTO;
        }
    }

    /** Lazy probe result for {@link NativeBridge#hasHdrPipeline()} —
     *  the native call returns the same value forever after the first
     *  invocation, so cache it once. */
    private static final boolean NATIVE_HDR_AVAILABLE =
        NativeBridge.hasHdrPipeline();

    /** Resolved colour descriptor for one upload — collects matrix,
     *  transfer, primaries, range, peak. */
    private static final class ColorDesc {
        final int yuvMatrix;      // NativeBridge.YUV_*
        final int transferFn;     // NativeBridge.TFN_*
        final int primaries;      // NativeBridge.PRI_*
        final int range;          // 0 limited, 1 full
        final float srcPeakNits;  // 0 = let native pick
        final float dstPeakNits;
        final boolean isHdr;
        ColorDesc(int yuvMatrix, int transferFn, int primaries,
                  int range, float srcPeakNits, float dstPeakNits) {
            this.yuvMatrix   = yuvMatrix;
            this.transferFn  = transferFn;
            this.primaries   = primaries;
            this.range       = range;
            this.srcPeakNits = srcPeakNits;
            this.dstPeakNits = dstPeakNits;
            this.isHdr = transferFn == NativeBridge.TFN_PQ
                      || transferFn == NativeBridge.TFN_HLG;
        }
    }

    /** Resolve the full colour descriptor for the upload.
     *
     *  Three-stage resolution:
     *    1. Explicit user overrides (system properties) win.
     *    2. {@link MediaFrame} caps metadata when present (this is
     *       what well-formed MP4 / MKV / WebM streams carry, and what
     *       the ffmpegwrapper push event sets via {@code colorimetry}).
     *    3. Resolution heuristic last:
     *         ≥ 3840 wide  → BT.2020 PQ HDR (the overwhelming case for
     *                       4K content streamed via mfwrapper /
     *                       dshowwrapper, both of which strip caps);
     *         ≥ 1280 wide  → BT.709 SDR;
     *         else         → BT.601 SDR. */
    private static ColorDesc resolveColorDesc(MediaFrame frame,
                                              int width, int height) {
        int yuvMatrix;
        int transferFn;
        int primaries;
        int range;
        float srcPeak = HDR_SRC_PEAK_NITS;

        // --- YUV matrix (existing logic, kept for SDR fast path). ---
        if (YUV_COLORSPACE_OVERRIDE >= 0) {
            yuvMatrix = YUV_COLORSPACE_OVERRIDE;
        } else {
            int fromCaps = (frame != null) ? frame.getYuvColorSpace()
                                           : MediaFrame.YUV_COLORSPACE_AUTO;
            yuvMatrix = mapCapsMatrix(fromCaps);
            if (yuvMatrix < 0) {
                yuvMatrix = (width >= 1280 || height >= 720)
                              ? NativeBridge.YUV_BT709
                              : NativeBridge.YUV_BT601;
            }
        }

        // --- Transfer function. ---
        //
        // Decision order: explicit user override → caps metadata →
        // SDR default. We deliberately do NOT auto-assume HDR PQ for
        // 4K content lacking metadata: most 4K content is SDR, and
        // running PQ→sRGB tone-mapping on SDR pixels crushes contrast.
        // A user with a known HDR file lacking caps can opt in with
        // -PforceHdr=pq / -PforceHdr=hlg. Otherwise we follow the
        // file's metadata when present, and fall back to SDR.
        int capsTrf = (frame != null) ? frame.getColorTransfer()
                                      : MediaFrame.TRANSFER_AUTO;
        if (HDR_TRANSFER_FORCE != MediaFrame.TRANSFER_AUTO) {
            transferFn = mapCapsTransfer(HDR_TRANSFER_FORCE);
        } else if (capsTrf != MediaFrame.TRANSFER_AUTO) {
            transferFn = mapCapsTransfer(capsTrf);
        } else if (width >= 1280 || height >= 720) {
            // HD+ default: BT.709 OETF (modern HDTV / web content).
            transferFn = NativeBridge.TFN_REC709;
        } else {
            // SD content: sRGB transfer (close enough — SD content
            // doesn't have a meaningful "transfer" distinction at
            // these bit depths).
            transferFn = NativeBridge.TFN_SRGB;
        }

        // --- Primaries. ---
        //
        // Caps wins; otherwise pair primaries with whatever transfer
        // we just picked. PQ / HLG without caps is forced by the user
        // and implies BT.2020 (the only sane HDR gamut). Anything
        // else: BT.709 primaries for HD+, BT.601 for SD.
        int capsPri = (frame != null) ? frame.getColorPrimaries()
                                      : MediaFrame.PRIMARIES_AUTO;
        if (capsPri != MediaFrame.PRIMARIES_AUTO) {
            primaries = mapCapsPrimaries(capsPri);
        } else if (transferFn == NativeBridge.TFN_PQ
                || transferFn == NativeBridge.TFN_HLG) {
            primaries = NativeBridge.PRI_REC2020;
        } else if (width >= 1280 || height >= 720) {
            primaries = NativeBridge.PRI_SRGB;
        } else {
            primaries = NativeBridge.PRI_REC601;
        }

        // --- Range. ---
        int capsRange = (frame != null) ? frame.getColorRange()
                                        : MediaFrame.RANGE_AUTO;
        if (yuvMatrix == NativeBridge.YUV_JPEG_FULL
         || capsRange == MediaFrame.RANGE_FULL) {
            range = 1;
        } else {
            range = 0;
        }

        // --- Source peak. ---
        if (srcPeak <= 0.f && frame != null) {
            srcPeak = frame.getMasteringPeakNits();
        }

        return new ColorDesc(yuvMatrix, transferFn, primaries,
                             range, srcPeak, HDR_DST_PEAK_NITS);
    }

    private static int mapCapsMatrix(int caps) {
        switch (caps) {
            case MediaFrame.YUV_COLORSPACE_BT601:  return NativeBridge.YUV_BT601;
            case MediaFrame.YUV_COLORSPACE_BT709:  return NativeBridge.YUV_BT709;
            case MediaFrame.YUV_COLORSPACE_BT2020: return NativeBridge.YUV_BT2020;
            case MediaFrame.YUV_COLORSPACE_JPEG:   return NativeBridge.YUV_JPEG_FULL;
            default:                               return -1;
        }
    }

    private static int mapCapsTransfer(int caps) {
        switch (caps) {
            case MediaFrame.TRANSFER_SRGB:    return NativeBridge.TFN_SRGB;
            case MediaFrame.TRANSFER_REC709:  return NativeBridge.TFN_REC709;
            case MediaFrame.TRANSFER_PQ:      return NativeBridge.TFN_PQ;
            case MediaFrame.TRANSFER_HLG:     return NativeBridge.TFN_HLG;
            case MediaFrame.TRANSFER_LINEAR:  return NativeBridge.TFN_LINEAR;
            default:                          return NativeBridge.TFN_SRGB;
        }
    }

    private static int mapCapsPrimaries(int caps) {
        switch (caps) {
            case MediaFrame.PRIMARIES_SRGB:    return NativeBridge.PRI_SRGB;
            case MediaFrame.PRIMARIES_REC2020: return NativeBridge.PRI_REC2020;
            case MediaFrame.PRIMARIES_DCI_P3:  return NativeBridge.PRI_DCI_P3;
            case MediaFrame.PRIMARIES_REC601:  return NativeBridge.PRI_REC601;
            default:                           return NativeBridge.PRI_SRGB;
        }
    }

    /** Pretty-print transfer function for diagnostics. */
    private static String transferName(int v) {
        if (v == NativeBridge.TFN_SRGB)    return "sRGB";
        if (v == NativeBridge.TFN_REC709)  return "Rec.709";
        if (v == NativeBridge.TFN_PQ)      return "PQ";
        if (v == NativeBridge.TFN_HLG)     return "HLG";
        if (v == NativeBridge.TFN_LINEAR)  return "linear";
        return "?(" + v + ")";
    }

    /** Pretty-print primaries for diagnostics. */
    private static String primariesName(int v) {
        if (v == NativeBridge.PRI_SRGB)    return "sRGB";
        if (v == NativeBridge.PRI_REC2020) return "Rec.2020";
        if (v == NativeBridge.PRI_DCI_P3)  return "DCI-P3";
        if (v == NativeBridge.PRI_REC601)  return "Rec.601";
        return "?(" + v + ")";
    }

    // ------------------------------------------------------------------
    // Native-resource ownership block.
    //
    // Every off-heap or GPU-side resource the texture owns lives inside
    // the {@link Resources} object referenced by {@link #resources}.
    // Resources is a static class — it must NEVER hold a reference back
    // to SkiaMediaTexture, otherwise the Cleaner registration would
    // make the texture reachable from itself and the Cleaner action
    // would never fire. Cleaner uses a {@link java.lang.ref.PhantomReference}
    // to schedule {@link Resources#run} only after the texture object
    // becomes phantom-reachable.
    //
    // dispose() drains Resources explicitly (preferred path). The
    // Cleaner is the safety net for code paths that forget to dispose
    // — without it, missing a dispose() would leak the SkImage handle
    // + the off-heap HDR scratch arena until JVM exit, which is the
    // exact bug class CLAUDE.md warns about.
    // ------------------------------------------------------------------
    private static final Cleaner CLEANER = Cleaner.create();
    private final Resources resources = new Resources();
    private final Cleaner.Cleanable cleanable = CLEANER.register(this, resources);

    private static final class Resources implements Runnable {
        /** Current SkImage handle. Swapped on every update(). */
        final AtomicLong skImage = new AtomicLong(0L);

        /** Optional WGL_NV_DX_interop2 handle paired with the current
         *  skImage. Non-zero only on the D3D11 zero-copy path. */
        final AtomicLong interopHandle = new AtomicLong(0L);

        /** One-slot deferred destroy of the *previous* update's image:
         *  retired one frame late so Skia's command queue can't still
         *  be sampling it. */
        final AtomicLong pendingDestroyImage   = new AtomicLong(0L);
        final AtomicLong pendingDestroyInterop = new AtomicLong(0L);

        /** GPU bytes charged to {@link SkiaTextureResourcePool} for the matching
         *  image handle (H2). Tracked in lockstep with {@code skImage} /
         *  {@code pendingDestroyImage} so every recordAllocated is balanced by
         *  exactly one recordFree — at retire (update) or at teardown (drainGpu) —
         *  with no drift. 0 when the paired handle is 0 (meta-only / failed frame). */
        final AtomicLong skImageBytes            = new AtomicLong(0L);
        final AtomicLong pendingDestroyImageBytes = new AtomicLong(0L);

        /** Long-lived shared Arena for the pooled HDR BGRA segment. */
        volatile Arena hdrArena;
        volatile MemorySegment hdrBgraSeg;

        /** Long-lived shared Arena for the SDR upload pinning. Holds
         *  one segment per plane (YUV path) plus a single BGRA segment
         *  for the BGRA fallback. Allocated lazily on the first frame
         *  and only re-allocated when frame dimensions grow.
         *  Eliminates ~240 MB/s of GC churn at 1080p/30fps. */
        volatile Arena sdrArena;
        volatile MemorySegment sdrYSeg;
        volatile MemorySegment sdrUSeg;
        volatile MemorySegment sdrVSeg;
        volatile MemorySegment sdrBgraSeg;

        /** Pooled heap tmp used by {@link #copyIntoSegment} when the
         *  source ByteBuffer is direct (gstreamer always is) and we
         *  need a byte[] hop to drive {@link MemorySegment#copy} via
         *  the bulk array overload. Sized to the largest plane seen
         *  so far. */
        volatile byte[] sdrCopyTmp;

        /** Guards drain() against double-free when both dispose() and
         *  the Cleaner action fire. */
        private final AtomicBoolean drained = new AtomicBoolean(false);

        /** Cleaner entry point (safety net) — never called from app
         *  code. Equivalent to dispose() but runs on the Cleaner
         *  background thread after the SkiaMediaTexture becomes
         *  phantom-reachable. */
        @Override public void run() {
            // BUG-1 fix: split the teardown by resource affinity.
            //  - GPU-affine handles (Ganesh SkImage drop, D3D11 interop unlock/
            //    unregister) are render-thread-confined; freeing them on this
            //    Cleaner daemon would corrupt the GPU heap / steal the GL-D3D
            //    context, so defer them to the render thread (drained per pulse).
            //  - The pure off-heap CPU arenas (hdr/sdr) have NO thread affinity.
            //    The old code deferred *everything*; if the deferred queue ever
            //    stops draining (Cleaner firing during/after render-thread
            //    teardown) those arenas leaked. Free them inline here so they
            //    never leak, regardless of render-thread state.
            if (!drained.compareAndSet(false, true)) return;
            freeArenas();
            NativeHandles.deferOnRenderThread(this::drainGpu);
        }

        /** Idempotent: destroys every native handle this Resources
         *  block owns. Safe to call from any thread; safe to call
         *  multiple times (subsequent calls no-op). Runs both halves inline —
         *  use only when already on the render thread. */
        void drain() {
            if (!drained.compareAndSet(false, true)) return;
            drainGpu();
            freeArenas();
        }

        /** GPU-affine native handles — render-thread only. Idempotent
         *  (each handle is getAndSet to 0). */
        private void drainGpu() {
            long h = skImage.getAndSet(0L);
            if (h != 0L) {
                NativeBridge.imageDestroy(MemorySegment.ofAddress(h));
                long b = skImageBytes.getAndSet(0L);
                if (b > 0) SkiaTextureResourcePool.INSTANCE.recordFree(b);  // H2
            }
            long ih = interopHandle.getAndSet(0L);
            if (ih != 0L) {
                MemorySegment seg = MemorySegment.ofAddress(ih);
                NativeBridge.d3d11InteropUnlock(seg);
                NativeBridge.d3d11InteropUnregisterTexture(seg);
            }
            long pdi = pendingDestroyImage.getAndSet(0L);
            if (pdi != 0L) {
                NativeBridge.imageDestroy(MemorySegment.ofAddress(pdi));
                long b = pendingDestroyImageBytes.getAndSet(0L);
                if (b > 0) SkiaTextureResourcePool.INSTANCE.recordFree(b);  // H2
            }
            long pii = pendingDestroyInterop.getAndSet(0L);
            if (pii != 0L) {
                MemorySegment seg = MemorySegment.ofAddress(pii);
                NativeBridge.d3d11InteropUnlock(seg);
                NativeBridge.d3d11InteropUnregisterTexture(seg);
            }
        }

        /** Pure off-heap CPU arenas — safe to free on any thread.
         *  Idempotent (null-guarded). */
        private void freeArenas() {
            Arena arena = hdrArena;
            if (arena != null) {
                arena.close();
                hdrArena   = null;
                hdrBgraSeg = null;
            }
            Arena sa = sdrArena;
            if (sa != null) {
                sa.close();
                sdrArena    = null;
                sdrYSeg     = null;
                sdrUSeg     = null;
                sdrVSeg     = null;
                sdrBgraSeg  = null;
            }
            sdrCopyTmp = null;
        }
    }

    /** Fallback handle published when the SkImage handle is 0 (between
     *  uploads). Drawing while this is 0 throws — see
     *  {@link SkiaGraphics#imageHandleOf}. */
    private final WrapMode wrapMode;
    private int physicalWidth;
    private int physicalHeight;
    private int contentWidth;
    private int contentHeight;
    private boolean linearFiltering = true;
    private int lockCount;
    private boolean permanent;
    private int lastImageSerial;

    // ----- Pooled scratch for the CPU HDR tone-map path -------------------
    // Heap scratch arrays for the parallel-stream row workers. The
    // off-heap BGRA segment + its Arena live in {@link Resources} so
    // the Cleaner safety-net can release them; heap arrays GC
    // naturally and don't need that treatment.
    //
    // Allocated lazily on the first HDR frame and grown only when
    // frame dimensions go up (rare — streams keep a stable resolution
    // for their lifetime). For non-HDR videos these stay null.
    //
    // Memory cost when active, dominated by the BGRA scratch:
    //   1080p HDR ≈ 8 MB native + 3 MB heap (Y+U+V scratch)
    //   4K   HDR ≈ 32 MB native + 12 MB heap
    // For non-HDR videos the cost is zero.
    private byte[] yHdrScratch;
    private byte[] uHdrScratch;
    private byte[] vHdrScratch;

    public SkiaMediaTexture(MediaFrame initialFrame, WrapMode wrapMode) {
        this.wrapMode = wrapMode;
        // uploadFrame may return null on the D3D11-zero-copy failure
        // edge case (interop register/lock both succeeded once on
        // ensureInteropInit but failed on this specific frame, or the
        // GstBuffer's pixel bytes are uninitialised placeholder for
        // a meta-only HW frame — see uploadFrame). Defend against it
        // so construction always succeeds: a 0 SkImage handle reports
        // surfaceLost=true and drawing no-ops cleanly until the next
        // update() arrives with a real frame. Without this guard, the
        // very first frame failing interop would NPE here and tear
        // down the whole media stack before playback even started.
        UploadResult r = uploadFrame(initialFrame);
        long imgHandle    = (r != null) ? r.skImageHandle()  : 0L;
        long interopValue = (r != null) ? r.interopHandle()  : 0L;
        int tw = textureWidthOf(initialFrame);
        int th = textureHeightOf(initialFrame);
        // H2: charge the initial GPU image's bytes to the texture budget. ~w*h*4
        // (the audit's estimate); freed at retire/teardown. 0 for a failed/meta
        // frame (handle 0) so recordAllocated/skImageBytes stay balanced.
        long bytes = (imgHandle != 0L) ? gpuImageBytes(tw, th) : 0L;
        this.resources.skImage.set(imgHandle);
        this.resources.skImageBytes.set(bytes);
        this.resources.interopHandle.set(interopValue);
        if (bytes > 0) {
            SkiaTextureResourcePool.INSTANCE.recordAllocated(bytes);
        }
        this.physicalWidth  = tw;
        this.physicalHeight = th;
        this.contentWidth   = tw;
        this.contentHeight  = th;
    }

    /** GPU byte estimate for a media SkImage (BGRA-equivalent footprint). */
    private static long gpuImageBytes(int w, int h) {
        return (w > 0 && h > 0) ? (long) w * h * 4L : 0L;
    }

    /** Returns the texture's actual pixel width. For HW-decoded frames
     *  taking the zero-copy path this is the platform texture's width
     *  (which may be smaller than the source when the producer
     *  downscaled); for CPU paths it's the source width. */
    private static int textureWidthOf(MediaFrame f) {
        int tw = f.getPlatformTextureWidth();
        return tw > 0 ? tw : f.getWidth();
    }

    private static int textureHeightOf(MediaFrame f) {
        int th = f.getPlatformTextureHeight();
        return th > 0 ? th : f.getHeight();
    }

    /** Native SkImage handle (uintptr_t value). Returns 0 if disposed. */
    public long getNativeHandle() {
        return resources.skImage.get();
    }

    @Override public PixelFormat getPixelFormat() {
        // Skia internally treats every uploaded frame as BGRA8888-premul,
        // even when the source was YUV (we convert on the Java side).
        return PixelFormat.INT_ARGB_PRE;
    }

    @Override public int getPhysicalWidth()  { return physicalWidth; }
    @Override public int getPhysicalHeight() { return physicalHeight; }

    @Override public int getContentX()       { return 0; }
    @Override public int getContentY()       { return 0; }
    @Override public int getContentWidth()   { return contentWidth; }
    @Override public int getContentHeight()  { return contentHeight; }
    @Override public int getMaxContentWidth()  { return physicalWidth; }
    @Override public int getMaxContentHeight() { return physicalHeight; }
    @Override public void setContentWidth(int w)  { this.contentWidth = w; }
    @Override public void setContentHeight(int h) { this.contentHeight = h; }

    @Override public WrapMode getWrapMode()  { return wrapMode; }
    @Override public boolean getUseMipmap()  { return false; }

    @Override public boolean getLinearFiltering()      { return linearFiltering; }
    @Override public void setLinearFiltering(boolean l) { this.linearFiltering = l; }

    @Override public Texture getSharedTexture(WrapMode altMode) {
        if (altMode == WrapMode.CLAMP_NOT_NEEDED || altMode == wrapMode) {
            lock();
            return this;
        }
        return null;
    }

    @Override public void lock()             { lockCount++; }
    @Override public void unlock()           { if (lockCount > 0) lockCount--; }
    @Override public boolean isLocked()      { return lockCount > 0; }
    @Override public int getLockCount()      { return lockCount; }
    @Override public void assertLocked() {
        if (lockCount <= 0 && !permanent) {
            throw new IllegalStateException("Texture is not locked");
        }
    }

    @Override public void makePermanent()    { this.permanent = true; }
    @Override public void contentsUseful()    { /* no atlas yet */ }
    @Override public void contentsNotUseful() { /* no atlas yet */ }

    @Override public boolean isSurfaceLost() { return resources.skImage.get() == 0L; }

    @Override public int getLastImageSerial()        { return lastImageSerial; }
    @Override public void setLastImageSerial(int s)  { this.lastImageSerial = s; }

    // ---- Update overloads -------------------------------------------------

    /** Re-upload pixels for a new media frame. The old SkImage handle
     *  is destroyed atomically; the new one becomes visible to
     *  subsequent {@link SkiaGraphics#drawTexture} calls. If the
     *  previous frame held a WGL interop registration, that's torn down
     *  here too. */
    // Deferred-destruction queue. SkImages created with
    // BorrowTextureFrom don't bump a refcount on the underlying GL
    // texture — destroying an SkImage just drops Skia's tracking,
    // but if there are still pending Skia draws referencing it, the
    // GPU may sample the texture AFTER we've unregistered the WGL
    // alias (and after the producer has bound new pixel data into
    // the same D3D11 texture). Symptom: post-seek flicker.
    //
    @Override public void update(MediaFrame frame, boolean skipFlush) {
        UploadResult r = uploadFrame(frame);
        if (r == null) {
            // Upload failed and there's nothing to swap in. Keep showing
            // whatever the previous frame produced — the SkImage and
            // interop handles remain valid; just skip this update.
            return;
        }
        // One-slot deferred destroy: keep the previous frame's image
        // + interop handle alive for one full pulse so Skia's command
        // queue can't still be sampling them when we free. One slot is
        // enough because the render thread flushes Skia on present
        // each pulse — by the time we're called again, the previous
        // GPU work has retired.
        // Track the latest texture size. Uses the platform texture's
        // actual dimensions when present (HW path with downscale) and
        // falls back to source dims for the CPU paths.
        int tw = textureWidthOf(frame);
        int th = textureHeightOf(frame);
        // H2: charge the new image's bytes to the budget, then swap the byte
        // tracking in LOCKSTEP with the handle swap so the retired image (and the
        // two still-live ones at teardown) are freed exactly once. 0 for a
        // meta/failed handle keeps it balanced.
        long newBytes = (r.skImageHandle() != 0L) ? gpuImageBytes(tw, th) : 0L;
        if (newBytes > 0) {
            SkiaTextureResourcePool.INSTANCE.recordAllocated(newBytes);
        }
        long oldImage      = resources.skImage.getAndSet(r.skImageHandle());
        long oldBytes      = resources.skImageBytes.getAndSet(newBytes);
        long oldInterop    = resources.interopHandle.getAndSet(r.interopHandle());
        long retireImage   = resources.pendingDestroyImage.getAndSet(oldImage);
        long retireBytes   = resources.pendingDestroyImageBytes.getAndSet(oldBytes);
        long retireInterop = resources.pendingDestroyInterop.getAndSet(oldInterop);
        if (retireImage != 0L) {
            NativeBridge.imageDestroy(MemorySegment.ofAddress(retireImage));
            if (retireBytes > 0) {
                SkiaTextureResourcePool.INSTANCE.recordFree(retireBytes);  // H2
            }
        }
        if (retireInterop != 0L) {
            MemorySegment h = MemorySegment.ofAddress(retireInterop);
            NativeBridge.d3d11InteropUnlock(h);
            NativeBridge.d3d11InteropUnregisterTexture(h);
        }
        this.physicalWidth  = tw;
        this.physicalHeight = th;
        this.contentWidth   = tw;
        this.contentHeight  = th;
    }

    // The other Image-based update paths aren't used by NGMediaView and
    // would require us to know the source PixelFormat (BGRA only here).
    @Override public void update(Image img) { rejectImageUpdate(); }
    @Override public void update(Image img, int dstx, int dsty) { rejectImageUpdate(); }
    @Override public void update(Image img, int dstx, int dsty, int srcw, int srch) { rejectImageUpdate(); }
    @Override public void update(Image img, int dstx, int dsty, int srcw, int srch, boolean skipFlush) { rejectImageUpdate(); }
    @Override public void update(Buffer buffer, PixelFormat format,
                                 int dstx, int dsty, int srcx, int srcy, int srcw, int srch,
                                 int srcscan, boolean skipFlush) { rejectImageUpdate(); }

    private static void rejectImageUpdate() {
        throw new UnsupportedOperationException(
            "SkiaMediaTexture only accepts MediaFrame updates.");
    }

    @Override public void dispose() {
        // dispose() is the explicit / preferred teardown. It runs the
        // exact same drain logic the Cleaner safety net would run if
        // the texture became unreachable without dispose() being
        // called — so calling both (or either) is idempotent and safe.
        //
        // We invoke {@link Cleaner.Cleanable#clean} which both fires
        // the Cleaner action *and* deregisters it (so the Cleaner
        // doesn't run again later). Resources.drain() is itself
        // guarded by an AtomicBoolean so a second invocation no-ops.
        cleanable.clean();
        // Heap-side HDR scratch arrays — let GC reclaim them once
        // this texture goes out of scope. No native bytes here.
        yHdrScratch = null;
        uHdrScratch = null;
        vHdrScratch = null;
    }

    // ---- Upload helper ----------------------------------------------------

    /**
     * Uploads a {@link MediaFrame} to a fresh {@code SkImage}, picking
     * the fastest available path:
     *
     * <ol>
     *   <li><b>YUV-native</b> — for {@link PixelFormat#MULTI_YCbCr_420}
     *       frames when the native lib supports it. Uploads Y/U/V
     *       planes directly; Skia's GPU shader does YUV→RGB at sample
     *       time. Skips a CPU YUV→BGRA conversion per frame.</li>
     *   <li><b>BGRA raster fallback</b> — anything else (already-BGRA
     *       frames, or YUV when the native YUV path is unavailable)
     *       goes through {@link MediaFrame#convertToFormat(PixelFormat)}
     *       → single-plane BGRA → {@code SkImages::RasterFromPixmapCopy}.</li>
     * </ol>
     */
    /** YUV-native upload (default ON): the native bridge now routes
     *  through {@code SkImages::TextureFromYUVAPixmaps} with a proper
     *  {@code SkYUVAInfo} carrying the source's BT.601 / BT.709 /
     *  BT.2020 / JPEG-full color-space tag, so Skia's fragment shader
     *  performs YUV→RGB at sample time with the right matrix. The
     *  earlier amber-tint bug came from a hand-rolled CPU matrix that
     *  pinned every stream to BT.709-limited regardless of content;
     *  picking the right matrix per-source (see {@link
     *  #autoYuvColorSpace}) is what unblocked re-enabling this path.
     *
     *  Override to {@code false} via {@code -Dskia.media.yuvNative=false}
     *  to force the BGRA-via-GStreamer-converter fallback (CPU
     *  YUV→BGRA in {@code videoconvert}, then single-plane raster
     *  upload) for A/B comparison. */
    private static final boolean YUV_NATIVE_ENABLED =
        Boolean.parseBoolean(System.getProperty("skia.media.yuvNative", "true"));

    /** Pair of native handles produced by an upload: the SkImage we
     *  draw with, and (optionally) a WGL interop handle that must be
     *  torn down once the SkImage is destroyed. {@code interopHandle}
     *  is {@code 0L} for non-zero-copy paths. */
    private record UploadResult(long skImageHandle, long interopHandle) {
        static UploadResult image(long h) { return new UploadResult(h, 0L); }
    }

    /** Picks the best available upload path for this frame. Non-static
     *  because the HDR CPU path uses per-texture pooled scratch (see
     *  {@link #ensureHdrScratch}). All other paths are stateless and
     *  delegate to private helpers. */
    private UploadResult uploadFrame(MediaFrame frame) {
        int kind   = frame.getPlatformTextureKind();
        long handle = frame.getPlatformTextureHandle();
        boolean canInterop = NativeBridge.hasD3d11Interop()
                          && NativeBridge.hasGlTextureImage();
        if (!firstFrameDiagDone && isMediaDebug()) {
            firstFrameDiagDone = true;
            int fw = frame.getWidth();
            int fh = frame.getHeight();
            int caps = frame.getYuvColorSpace();
            ColorDesc desc = resolveColorDesc(frame, fw, fh);
            System.err.printf(
                "[skia.media] FIRST frame: pf=%s kind=%d handle=%#x "
                + "size=%dx%d capsYuv=%s yuv=%s transfer=%s primaries=%s "
                + "range=%s srcPeak=%.0fnit dstPeak=%.0fnit hdrPath=%s "
                + "nativeHdr=%s%n",
                frame.getPixelFormat(), kind, handle,
                fw, fh, yuvNameForCaps(caps),
                yuvNameForNative(desc.yuvMatrix),
                transferName(desc.transferFn),
                primariesName(desc.primaries),
                desc.range == 1 ? "full" : "limited",
                desc.srcPeakNits, desc.dstPeakNits,
                currentHdrPath().name(), NATIVE_HDR_AVAILABLE ? "yes" : "no");
            System.err.printf(
                "[skia.media] decodeMode=%s zeroCopyEnabled=%s hasInterop=%s hasGlTex=%s%n",
                System.getProperty("skia.media.decode", "AUTO"),
                isD3d11ZeroCopyEnabled(), NativeBridge.hasD3d11Interop(),
                NativeBridge.hasGlTextureImage());
        } else if (!firstFrameDiagDone) {
            firstFrameDiagDone = true;
        }
        // Frames carrying a D3D11 platform-texture handle ALWAYS have
        // garbage in their Y/U/V plane bytes — the GstBuffer's pixel
        // memory is a dimensionally-sized placeholder; the real pixels
        // live in the meta-attached D3D11 texture. Reading those bytes
        // as YUV produces a green frame (Y=0, U=0, V=0 → BT.709 RGB ≈
        // (0, 87, 0)) — exactly the symptom an app sees when CPU mode
        // disables the zero-copy consumer while the ffmpeg producer
        // still emits HW frames. Two cases:
        //
        //   (a) zero-copy ENABLED → try the GL alias upload. On
        //       success we render correctly; on failure we drop the
        //       frame (the previously-uploaded SkImage stays visible).
        //   (b) zero-copy DISABLED (CPU mode, or interop missing) →
        //       skip outright. NEVER fall through to the YUV upload —
        //       that would upload the placeholder bytes and the user
        //       gets a green/random flash per frame. The proper way to
        //       remove the green-frame symptom in CPU mode is to make
        //       the *producer* (ffmpegwrapper) do SW decode; see
        //       javafx.media's MediaFfmpegConfig which propagates the
        //       skia.media.decode property into the OPENJFX_MEDIA_USE_
        //       HWACCEL env var at media init time.
        if (kind == MediaFrame.PLATFORM_TEXTURE_KIND_D3D11 && handle != 0L) {
            if (isD3d11ZeroCopyEnabled() && canInterop) {
                UploadResult r = uploadD3d11Texture(frame);
                if (r != null) return r;
            } else if (!d3d11DropWarningPrinted) {
                // Footgun warning: producer is emitting HW frames but
                // the consumer can't sample them (zero-copy off, or
                // no WGL interop). Without this warning every frame
                // silently drops and the user sees frozen video.
                d3d11DropWarningPrinted = true;
                System.err.println(
                    "[skia.media] WARNING: receiving D3D11 platform-"
                    + "texture frames but the consumer cannot use them "
                    + "(zeroCopyEnabled=" + isD3d11ZeroCopyEnabled()
                    + " canInterop=" + canInterop + "). All HW frames "
                    + "will be dropped. To fix: call "
                    + "Media.setDecodeMethod(DecodeMethod.CPU) so the "
                    + "producer also goes to software, OR enable the "
                    + "zero-copy path (driver must support "
                    + "WGL_NV_DX_interop2).");
            }
            // Drop the frame — never try to use the placeholder YUV
            // bytes that ship alongside a HW-textured GstBuffer.
            return null;
        }

        // Fast path: native YUV (I420). Skips the per-frame CPU
        // YUV→BGRA conversion that the BGRA fallback path triggers
        // inside GStreamer's video converter. Skia handles YUV→RGB
        // in its shader at sample time (GPU when Ganesh is active).
        if (YUV_NATIVE_ENABLED
            && frame.getPixelFormat() == PixelFormat.MULTI_YCbCr_420
            && NativeBridge.hasYuvI420()) {
            long h = uploadYuvI420(frame);
            if (h != 0L) return UploadResult.image(h);
            // YUV upload failed (e.g. no GPU context) → fall through.
        }
        return UploadResult.image(uploadBgra(frame));
    }

    /** D3D11 zero-copy enable. Re-queried per upload so a runtime
     *  {@link javafx.application.Application#setDecodeMethod} switch
     *  to {@link javafx.application.Application.DecodeMethod#CPU}
     *  is honoured immediately. The per-feature
     *  {@code skia.media.d3d11ZeroCopy} property remains as the
     *  fine-grained override (defaults on). */
    private static boolean isD3d11ZeroCopyEnabled() {
        if (decodeModeIsCpuOnly()) return false;
        return Boolean.parseBoolean(
            System.getProperty("skia.media.d3d11ZeroCopy", "true"));
    }

    /** One-shot diagnostic flag — used to log the first MediaFrame's
     *  platform-texture state so we can confirm at runtime whether the
     *  M3-B zero-copy path is being entered. */
    private static volatile boolean firstFrameDiagDone = false;

    /** One-shot warning latch for the D3D11-frames-but-cannot-sample
     *  footgun. Without this the player just silently freezes on every
     *  HW frame; with it the user gets exactly one log line explaining
     *  the misconfiguration. */
    private static volatile boolean d3d11DropWarningPrinted = false;

    /** Lazy interop init guard. {@link NativeBridge#d3d11InteropInit()}
     *  is idempotent, but we still skip the call once we know it
     *  failed (e.g. no NV_DX_interop2 in the driver) so each frame
     *  doesn't pay the JNI crossing. */
    private static volatile boolean interopInitTried = false;
    private static volatile boolean interopInitOk = false;

    private static boolean ensureInteropInit() {
        if (interopInitTried) return interopInitOk;
        interopInitTried = true;
        interopInitOk = NativeBridge.d3d11InteropInit() != 0;
        return interopInitOk;
    }

    /** D3D11 zero-copy upload. Returns null on any failure so the
     *  caller falls through to a CPU path; never throws. */
    private static UploadResult uploadD3d11Texture(MediaFrame frame) {
        if (!ensureInteropInit()) return null;

        long d3dTexPtr = frame.getPlatformTextureHandle();
        // Use the platform texture's actual dimensions — when the
        // producer downscaled an 8K source into a 4K texture, the
        // frame's getWidth()/getHeight() still report the source
        // dimensions but the GL alias is the downscaled size, and
        // sampling outside that would produce garbage.
        int  w = frame.getPlatformTextureWidth();
        int  h = frame.getPlatformTextureHeight();
        if (w <= 0 || h <= 0) {
            w = frame.getWidth();
            h = frame.getHeight();
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment glTexOut = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment d3dTexSeg = MemorySegment.ofAddress(d3dTexPtr);

            MemorySegment interop = NativeBridge.d3d11InteropRegisterTexture(
                d3dTexSeg, glTexOut);
            if (interop == null || interop.equals(MemorySegment.NULL)) {
                return null;
            }
            // Lock for sampling. Pair with unlock when the SkImage that
            // borrows the GL alias gets destroyed (in update / dispose).
            if (NativeBridge.d3d11InteropLock(interop) != 1) {
                NativeBridge.d3d11InteropUnregisterTexture(interop);
                return null;
            }
            int glName = glTexOut.get(ValueLayout.JAVA_INT, 0L);
            MemorySegment img = NativeBridge.imageCreateFromGlTexture(glName, w, h);
            if (img == null || img.equals(MemorySegment.NULL)) {
                NativeBridge.d3d11InteropUnlock(interop);
                NativeBridge.d3d11InteropUnregisterTexture(interop);
                return null;
            }
            return new UploadResult(img.address(), interop.address());
        }
    }

    /** One-shot diagnostic dump so we can see exactly what JFX is
     *  delivering on the first frame (plane count, strides, byte
     *  ranges). Gated on -Dskia.media.yuvDiag=true so it's quiet by
     *  default. */
    private static boolean yuvDiagDone = false;
    private static void diagFrame(MediaFrame frame) {
        if (yuvDiagDone || !Boolean.getBoolean("skia.media.yuvDiag")) return;
        yuvDiagDone = true;
        int n = frame.planeCount();
        System.err.printf("[skia.media] frame %dx%d format=%s planes=%d%n",
            frame.getWidth(), frame.getHeight(), frame.getPixelFormat(), n);
        for (int i = 0; i < n; i++) {
            ByteBuffer bb = frame.getBufferForPlane(i);
            int stride = frame.strideForPlane(i);
            System.err.printf("[skia.media]   plane %d: stride=%d  buf.pos=%d  buf.limit=%d  buf.cap=%d%n",
                i, stride, bb.position(), bb.limit(), bb.capacity());
        }
    }

    /**
     * Native YUV-planar upload. Pins the Y / U / V plane ByteBuffers
     * into off-heap memory and hands them to Skia — for SDR sources
     * Skia does the chroma upsample + YUV→RGB matrix in its sampling
     * shader on the GPU. For HDR sources (PQ / HLG) we either:
     *   - call {@link NativeBridge#imageCreateYuvHdr} which adds a
     *     BT.2390 tone-map pass via SkRuntimeEffect (GPU path); or
     *   - fall back to {@link HdrToneMap#tonemapI420ToBgra}, a pure-
     *     Java LUT-based tone mapper, when the native bridge wasn't
     *     built with HDR support.
     */
    private long uploadYuvI420(MediaFrame frame) {
        diagFrame(frame);
        int w = frame.getWidth();
        int h = frame.getHeight();
        ColorDesc desc = resolveColorDesc(frame, w, h);

        // The MediaFrame protocol: 3 planes for YCbCr_420 → plane 0
        // (Y) is full-size, planes 1 (U/Cb) and 2 (V/Cr) are half-size
        // both dimensions.
        ByteBuffer yBuf = frame.getBufferForPlane(0);
        ByteBuffer uBuf = frame.getBufferForPlane(1);
        ByteBuffer vBuf = frame.getBufferForPlane(2);
        int yStride = frame.strideForPlane(0);
        int uStride = frame.strideForPlane(1);
        int vStride = frame.strideForPlane(2);

        // Path decision:
        //   HDR + (AUTO/GPU) + native available    → native HDR upload
        //   HDR + (CPU)                            → Java tone-map
        //   HDR + (AUTO/GPU) + no native           → Java tone-map
        //   HDR + (OFF)                            → SDR path (looks dim but not amber)
        //   SDR                                    → existing fast path
        // Per-call resolve so a runtime Application.setDecodeMethod()
        // takes effect on the next frame, not just on next stream.
        final HdrPath hdrPath = currentHdrPath();
        final boolean wantHdr = desc.isHdr && hdrPath != HdrPath.OFF;
        final boolean useGpuHdr = wantHdr
                && hdrPath != HdrPath.CPU
                && NATIVE_HDR_AVAILABLE;
        final boolean useCpuHdr = wantHdr && !useGpuHdr;

        if (useCpuHdr) {
            // CPU fallback: tone-map into a BGRA buffer, then push
            // through the existing raster upload. Slower than the
            // GPU path but produces correct colours on any setup.
            return uploadHdrViaCpu(frame, desc, w, h,
                                   yBuf, yStride, uBuf, uStride, vBuf, vStride);
        }

        // Pool the three plane segments so Skia gets a native pointer
        // it can copy from with zero per-frame Arena/allocate churn.
        // ensureSdrYuvScratch() grows the pool on the first frame and
        // on any later frame whose plane size increased — typically
        // never after frame #1 for a single stream.
        final int chromaH = (h + 1) / 2;
        ensureSdrYuvScratch(yStride * h, uStride * chromaH, vStride * chromaH);
        copyIntoSegment(yBuf, resources.sdrYSeg, yStride * h);
        copyIntoSegment(uBuf, resources.sdrUSeg, uStride * chromaH);
        copyIntoSegment(vBuf, resources.sdrVSeg, vStride * chromaH);

        MemorySegment imgHandle;
        if (useGpuHdr) {
            imgHandle = NativeBridge.imageCreateYuvHdr(
                resources.sdrYSeg, yStride,
                resources.sdrUSeg, uStride,
                resources.sdrVSeg, vStride,
                w, h,
                desc.yuvMatrix, desc.transferFn, desc.primaries,
                desc.range, desc.srcPeakNits, desc.dstPeakNits);
            if (imgHandle != null && !imgHandle.equals(MemorySegment.NULL)) {
                return imgHandle.address();
            }
            // Native HDR call returned NULL — try the SDR path as a
            // safety net so the user still sees a frame.
        }

        imgHandle = NativeBridge.imageCreateYuvI420(
            resources.sdrYSeg, yStride,
            resources.sdrUSeg, uStride,
            resources.sdrVSeg, vStride,
            w, h, desc.yuvMatrix);
        if (imgHandle == null || imgHandle.equals(MemorySegment.NULL)) {
            return 0L;
        }
        return imgHandle.address();
    }

    /** CPU-side HDR tone map → upload-as-BGRA path. Used when the
     *  native bridge lacks HDR support or the user forced
     *  {@code -Dskia.media.hdrPath=cpu}.
     *
     *  <p>Uses pooled scratch + a long-lived native segment so the per-
     *  frame allocation cost is ~0 in steady state. At 4K HDR the
     *  previous unpooled version produced ~140 MB of garbage per
     *  frame (3 plane snapshots + 1 direct ByteBuffer + 1 byte[]
     *  output + 1 arena segment + 1 tmp[]); that's eliminated. The
     *  scratch grows only when frame dimensions go up — for any
     *  single stream the buffers are reused for the lifetime of the
     *  texture.</p>
     */
    private long uploadHdrViaCpu(
            MediaFrame frame, ColorDesc desc, int w, int h,
            ByteBuffer yBuf, int yStride,
            ByteBuffer uBuf, int uStride,
            ByteBuffer vBuf, int vStride) {
        ensureHdrScratch(w, h, yStride, uStride, vStride);

        HdrToneMap.tonemapI420ToBgra(
            resources.hdrBgraSeg,
            yHdrScratch, uHdrScratch, vHdrScratch,
            yBuf, yStride, uBuf, uStride, vBuf, vStride,
            w, h,
            desc.yuvMatrix, desc.transferFn, desc.primaries,
            desc.range == 1,
            desc.srcPeakNits, desc.dstPeakNits);

        int rowBytes = w * 4;
        MemorySegment imgHandle = NativeBridge.imageCreateRaster(
            w, h, rowBytes, resources.hdrBgraSeg, NativeBridge.CT_BGRA_8888_PREMUL);
        if (imgHandle == null || imgHandle.equals(MemorySegment.NULL)) {
            return 0L;
        }
        return imgHandle.address();
    }

    /** Allocate-or-grow the pooled HDR scratch buffers + native BGRA
     *  segment so they're sized for the current frame. Idempotent
     *  when dimensions/strides haven't grown — typical case after
     *  the first HDR frame. When growth is needed, the previous
     *  arena is closed atomically (releasing the old native segment)
     *  before allocating the new one, so peak memory is bounded by
     *  the largest frame size ever seen.
     *
     *  <p>The {@code yScratch}/{@code uScratch}/{@code vScratch} are
     *  plain heap arrays, sized to (stride × rows) so the tone-mapper
     *  can stride-aware index them without re-copying. The BGRA
     *  segment lives in a shared {@link Arena} — shared because
     *  allocating it inside a confined arena every frame is exactly
     *  what we're trying to eliminate.</p> */
    private void ensureHdrScratch(int w, int h, int yStride, int uStride, int vStride) {
        final int chromaH = (h + 1) / 2;
        final int yBytes  = yStride * h;
        final int uBytes  = uStride * chromaH;
        final int vBytes  = vStride * chromaH;
        final long bgraBytes = (long) w * h * 4L;

        if (yHdrScratch == null || yHdrScratch.length < yBytes) {
            yHdrScratch = new byte[yBytes];
        }
        if (uHdrScratch == null || uHdrScratch.length < uBytes) {
            uHdrScratch = new byte[uBytes];
        }
        if (vHdrScratch == null || vHdrScratch.length < vBytes) {
            vHdrScratch = new byte[vBytes];
        }
        if (resources.hdrBgraSeg == null
         || resources.hdrBgraSeg.byteSize() < bgraBytes) {
            if (resources.hdrArena != null) {
                // Close the old arena before allocating the new one.
                // The previous segment becomes invalid the instant
                // close() returns — but at that point we're about to
                // overwrite hdrBgraSeg, so no use-after-free.
                resources.hdrArena.close();
            }
            resources.hdrArena   = Arena.ofShared();
            resources.hdrBgraSeg = resources.hdrArena.allocate(bgraBytes);
        }
    }

    /** Copy a plane's bytes from the source ByteBuffer into a
     *  caller-supplied (pooled) MemorySegment. Handles both heap and
     *  direct ByteBuffers; for direct buffers (the gstreamer case)
     *  this routes through a pooled byte[] tmp held on the texture
     *  so we don't allocate per frame.
     *
     *  <p>The byte order is preserved as-is — pixel data is byte-
     *  oriented so no endian conversion happens at this layer.</p> */
    private void copyIntoSegment(ByteBuffer src, MemorySegment dst, int planeBytes) {
        int pos = src.position();
        int lim = src.limit();
        int avail = lim - pos;
        int copyN = Math.min(avail, planeBytes);
        if (src.hasArray()) {
            // Heap buffer — direct byte[] → MemorySegment copy, no tmp.
            MemorySegment.copy(src.array(), src.arrayOffset() + pos,
                               dst, ValueLayout.JAVA_BYTE, 0L, copyN);
        } else {
            // Direct ByteBuffer (gstreamer's pinned GstBuffer): use a
            // pooled byte[] hop. The alternative is MemorySegment.copy
            // segment-to-segment via MemorySegment.ofBuffer(src), which
            // works but ties src's lifetime to the segment — we'd have
            // to be careful that the GstBuffer outlives the native
            // call. The tmp-hop is simpler and the byte[] is pooled
            // so it's still zero per-frame allocation.
            if (resources.sdrCopyTmp == null
             || resources.sdrCopyTmp.length < copyN) {
                resources.sdrCopyTmp = new byte[copyN];
            }
            src.duplicate().get(resources.sdrCopyTmp, 0, copyN);
            MemorySegment.copy(resources.sdrCopyTmp, 0,
                               dst, ValueLayout.JAVA_BYTE, 0L, copyN);
        }
        if (copyN < planeBytes) {
            // Short source (truncated GstBuffer): zero the unwritten tail so the
            // native YUV→RGB upload doesn't read last frame's pixels from this
            // pooled, reused segment (would show stale garbage at the frame bottom).
            dst.asSlice(copyN, (long) planeBytes - copyN).fill((byte) 0);
        }
    }

    /** Allocate-or-grow the SDR YUV plane segments. All three live in
     *  {@code resources.sdrArena}; growing any one closes the arena
     *  and re-allocates all of them at the new max sizes — same
     *  policy as {@link #ensureHdrScratch}. Stable-resolution playback
     *  hits this once on the first frame. */
    private void ensureSdrYuvScratch(int yBytes, int uBytes, int vBytes) {
        long curY = resources.sdrYSeg != null ? resources.sdrYSeg.byteSize() : 0L;
        long curU = resources.sdrUSeg != null ? resources.sdrUSeg.byteSize() : 0L;
        long curV = resources.sdrVSeg != null ? resources.sdrVSeg.byteSize() : 0L;
        if (curY >= yBytes && curU >= uBytes && curV >= vBytes) return;

        if (resources.sdrArena != null) {
            resources.sdrArena.close();   // invalidates all SDR segments
        }
        resources.sdrArena  = Arena.ofShared();
        // Keep the BGRA segment around if it was already sized big enough —
        // closing the arena invalidated it, so re-allocate.
        long bgraNeeded = resources.sdrBgraSeg != null
                          ? resources.sdrBgraSeg.byteSize() : 0L;
        resources.sdrYSeg = resources.sdrArena.allocate(Math.max(yBytes, curY));
        resources.sdrUSeg = resources.sdrArena.allocate(Math.max(uBytes, curU));
        resources.sdrVSeg = resources.sdrArena.allocate(Math.max(vBytes, curV));
        if (bgraNeeded > 0) {
            resources.sdrBgraSeg = resources.sdrArena.allocate(bgraNeeded);
        } else {
            resources.sdrBgraSeg = null;
        }
    }

    /** Same growth policy as {@link #ensureSdrYuvScratch} but for the
     *  single BGRA fallback segment. */
    private void ensureSdrBgraScratch(int bgraBytes) {
        if (resources.sdrBgraSeg != null
         && resources.sdrBgraSeg.byteSize() >= bgraBytes) return;

        if (resources.sdrArena != null) {
            resources.sdrArena.close();
        }
        resources.sdrArena = Arena.ofShared();
        // Re-allocate any YUV plane segments that were sized before —
        // closing the arena invalidated them too.
        long curY = resources.sdrYSeg != null ? resources.sdrYSeg.byteSize() : 0L;
        long curU = resources.sdrUSeg != null ? resources.sdrUSeg.byteSize() : 0L;
        long curV = resources.sdrVSeg != null ? resources.sdrVSeg.byteSize() : 0L;
        resources.sdrBgraSeg = resources.sdrArena.allocate(bgraBytes);
        if (curY > 0) resources.sdrYSeg = resources.sdrArena.allocate(curY);
        if (curU > 0) resources.sdrUSeg = resources.sdrArena.allocate(curU);
        if (curV > 0) resources.sdrVSeg = resources.sdrArena.allocate(curV);
    }

    /** Fallback path: force BGRA single-plane (CPU YUV→BGRA in the
     *  source decoder if needed), upload via the raster bridge.
     *  Uses the pooled SDR BGRA segment. */
    private long uploadBgra(MediaFrame frame) {
        MediaFrame bgraFrame = frame;
        if (frame.getPixelFormat() != PixelFormat.INT_ARGB_PRE) {
            MediaFrame converted = frame.convertToFormat(PixelFormat.INT_ARGB_PRE);
            if (converted == null) {
                throw new UnsupportedOperationException(
                    "SkiaMediaTexture: cannot convert "
                    + frame.getPixelFormat() + " → INT_ARGB_PRE");
            }
            bgraFrame = converted;
        }

        int w = bgraFrame.getWidth();
        int h = bgraFrame.getHeight();
        int rowBytes = bgraFrame.strideForPlane(0);
        ByteBuffer src = bgraFrame.getBufferForPlane(0);
        int planeBytes = rowBytes * h;

        ensureSdrBgraScratch(planeBytes);
        copyIntoSegment(src, resources.sdrBgraSeg, planeBytes);

        MemorySegment imgHandle = NativeBridge.imageCreateRaster(
            w, h, rowBytes, resources.sdrBgraSeg, NativeBridge.CT_BGRA_8888_PREMUL);
        if (imgHandle == null || imgHandle.equals(MemorySegment.NULL)) {
            throw new IllegalStateException(
                "Skia media frame upload failed (" + w + "x" + h + ")");
        }
        return imgHandle.address();
    }
}
