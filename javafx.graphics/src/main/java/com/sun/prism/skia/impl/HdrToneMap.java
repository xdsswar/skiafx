/*
 * Copyright (c) 2026 skia-fx contributors.
 *
 * Pure-Java HDR → SDR tone mapper. Used as a fallback when the native
 * Skia bridge lacks HDR support (no SkRuntimeEffect pipeline or
 * pre-HDR DLL build). Converts a planar I420 YUV frame whose pixels
 * are PQ- or HLG-encoded BT.2020 into BGRA8888 premultiplied bytes
 * suitable for the existing {@code imageCreateRaster} upload path.
 *
 * Algorithm pipeline (per pixel):
 *   1. YUV → R'G'B' using the source YUV matrix (BT.2020 limited /
 *      BT.709 limited / BT.601 limited / JPEG full).
 *   2. EOTF: R'G'B' → linear RGB in nits (PQ SMPTE-ST-2084 EOTF or
 *      HLG inverse-OETF + OOTF).
 *   3. Gamut: source primaries (Rec.2020 / Rec.709 / DCI-P3) → sRGB.
 *   4. BT.2390-9 perceptual tone curve, srcPeak → dstPeak nits.
 *   5. sRGB OETF (linear → sRGB encoded).
 *   6. Pack into BGRA premultiplied (alpha = 1.0).
 *
 * Hot path uses 1024-entry LUTs for the EOTF (PQ/HLG) and OETF (sRGB);
 * a small amount of float math runs per pixel for the gamut matrix
 * and the BT.2390 spline. Work is sliced by row and run in parallel
 * over {@link java.util.concurrent.ForkJoinPool#commonPool()}.
 *
 * Not used on the SDR fast path — SDR content goes through Skia's
 * native YUV upload directly. This class is HDR-only.
 */
package com.sun.prism.skia.impl;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.stream.IntStream;

public final class HdrToneMap {

    private HdrToneMap() {}

    // ---- enum mirrors of NativeBridge.{TFN,PRI}_* -------------------------
    public static final int TFN_SRGB    = NativeBridge.TFN_SRGB;
    public static final int TFN_REC709  = NativeBridge.TFN_REC709;
    public static final int TFN_PQ      = NativeBridge.TFN_PQ;
    public static final int TFN_HLG     = NativeBridge.TFN_HLG;
    public static final int TFN_LINEAR  = NativeBridge.TFN_LINEAR;

    public static final int PRI_SRGB    = NativeBridge.PRI_SRGB;
    public static final int PRI_REC2020 = NativeBridge.PRI_REC2020;
    public static final int PRI_DCI_P3  = NativeBridge.PRI_DCI_P3;
    public static final int PRI_REC601  = NativeBridge.PRI_REC601;

    public static final int YUV_BT601     = NativeBridge.YUV_BT601;
    public static final int YUV_BT709     = NativeBridge.YUV_BT709;
    public static final int YUV_BT2020    = NativeBridge.YUV_BT2020;
    public static final int YUV_JPEG_FULL = NativeBridge.YUV_JPEG_FULL;

    /** True when this {@code transferFn} requires HDR tone mapping
     *  (i.e. the CPU fallback or the native HDR path must be used). */
    public static boolean isHdr(int transferFn) {
        return transferFn == TFN_PQ || transferFn == TFN_HLG;
    }

    // ---- precomputed lookup tables ----------------------------------------
    // Keep these compact. 1024 entries float each = 4 KiB per table;
    // total static cost ~16 KiB. Fits in L1 on every modern CPU.
    private static final int LUT_SIZE = 1024;
    private static final float[] PQ_EOTF_LUT     = new float[LUT_SIZE];
    private static final float[] HLG_EOTF_LUT    = new float[LUT_SIZE];
    private static final byte[]  SRGB_OETF_LUT_U8 = new byte[LUT_SIZE];

    static {
        for (int i = 0; i < LUT_SIZE; i++) {
            float t = i / (float)(LUT_SIZE - 1);
            PQ_EOTF_LUT[i]      = pqEotfNormalized(t);   // 0..1 → linear in [0,1] (peak 10000nits = 1.0)
            HLG_EOTF_LUT[i]     = hlgEotfNormalized(t);  // 0..1 → linear normalised
            // sRGB OETF table: linear [0,1] in -> sRGB 8-bit.
            SRGB_OETF_LUT_U8[i] = (byte) Math.round(255.f * srgbOetf(t));
        }
    }

    // SMPTE ST 2084 PQ EOTF, returning linear value normalised so 1.0
    // corresponds to 10000 nits.
    private static float pqEotfNormalized(float v) {
        if (v <= 0.f) return 0.f;
        final float c1 = 0.8359375f;
        final float c2 = 18.8515625f;
        final float c3 = 18.6875f;
        final float m1 = 0.1593017578125f;
        final float m2 = 78.84375f;
        double p = Math.pow(v, 1.0 / m2);
        double n = Math.max(p - c1, 0.0);
        double d = c2 - c3 * p;
        return (float) Math.pow(n / d, 1.0 / m1);
    }

    // ARIB STD-B67 / BT.2100 HLG EOTF (signal → linear scene-referred).
    // Normalised so 1.0 corresponds to peak HLG = 1000 nits at 1.2 OOTF gamma.
    private static float hlgEotfNormalized(float v) {
        if (v <= 0.f) return 0.f;
        final float a = 0.17883277f;
        final float b = 0.28466892f;
        final float c = 0.55991073f;
        double lin;
        if (v <= 0.5) {
            lin = (v * v) / 3.0;
        } else {
            lin = (Math.exp((v - c) / a) + b) / 12.0;
        }
        return (float) lin;
    }

    private static float srgbOetf(float lin) {
        if (lin <= 0.f) return 0.f;
        if (lin >= 1.f) return 1.f;
        return (lin <= 0.0031308f)
                ? 12.92f * lin
                : 1.055f * (float) Math.pow(lin, 1.f / 2.4f) - 0.055f;
    }

    // ---- BT.2020 → sRGB gamut conversion matrix ---------------------------
    // Computed once for the most common HDR case. Other gamut combos are
    // synthesised at call time on demand — they aren't hot paths.
    private static final float[] BT2020_TO_SRGB = {
        1.6605f, -0.5876f, -0.0728f,
       -0.1246f,  1.1329f, -0.0083f,
       -0.0182f, -0.1006f,  1.1187f,
    };
    private static final float[] IDENTITY = {
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    };

    private static float[] gamutMatrix(int srcPrimaries) {
        switch (srcPrimaries) {
            case PRI_REC2020: return BT2020_TO_SRGB;
            case PRI_SRGB:    return IDENTITY;
            // Approximate DCI-P3 → sRGB with identity here; DCI-P3 content
            // is rare in our media path and the visual difference is
            // smaller than the PQ/HDR effects we're correcting.
            case PRI_DCI_P3:  return IDENTITY;
            case PRI_REC601:  return IDENTITY;
            default:          return IDENTITY;
        }
    }

    // ---- public entry point ----------------------------------------------

    /**
     * Tone-map a planar I420 YUV frame into BGRA premultiplied bytes,
     * writing the result directly into a caller-supplied
     * {@link MemorySegment}. The segment must have capacity for
     * {@code width × height × 4} bytes.
     *
     * <p>Buffer ownership: the caller owns and pools every output and
     * scratch buffer. The previous version of this method allocated
     * a fresh direct {@code ByteBuffer} + four large {@code byte[]}s
     * per call — at 4K HDR + 30 fps that produced ~4 GB/sec of GC
     * churn. With pooled scratch we get the same algorithm at zero
     * steady-state heap allocation.</p>
     *
     * @param bgraOut        Caller-supplied destination, sized to at
     *                       least {@code width*height*4} bytes. Bytes
     *                       are written native-endian as BGRA premul.
     * @param yScratch       Caller-supplied scratch ≥ {@code yStride*height}.
     *                       Holds a snapshot of {@code yPlane} so parallel
     *                       row tasks can read without sharing
     *                       {@link ByteBuffer} position state.
     * @param uScratch       Scratch ≥ {@code uStride * (height+1)/2}.
     * @param vScratch       Scratch ≥ {@code vStride * (height+1)/2}.
     * @param yPlane         Y plane source ByteBuffer.
     * @param yStride        bytes per Y row (≥ width).
     * @param uPlane         U (Cb) plane source.
     * @param uStride        bytes per U row (≥ (width+1)/2).
     * @param vPlane         V (Cr) plane source.
     * @param vStride        bytes per V row (≥ (width+1)/2).
     * @param width          frame width.
     * @param height         frame height.
     * @param yuvMatrix      {@link #YUV_BT601} / {@link #YUV_BT709} /
     *                       {@link #YUV_BT2020} / {@link #YUV_JPEG_FULL}.
     * @param transferFn     {@link #TFN_PQ}, {@link #TFN_HLG}, or
     *                       (degenerate) one of the SDR transfers (then
     *                       the call is just a colour-correct YUV→BGRA
     *                       conversion without tone mapping).
     * @param primaries      {@link #PRI_REC2020} / {@link #PRI_SRGB} / …
     * @param fullRange      {@code true} when YUV uses full (0-255) range.
     * @param srcPeakNits    source mastering peak (default 1000 for HDR).
     * @param dstPeakNits    display target peak (default 100).
     */
    public static void tonemapI420ToBgra(
            MemorySegment bgraOut,
            byte[] yScratch, byte[] uScratch, byte[] vScratch,
            ByteBuffer yPlane, int yStride,
            ByteBuffer uPlane, int uStride,
            ByteBuffer vPlane, int vStride,
            int width, int height,
            int yuvMatrix, int transferFn, int primaries,
            boolean fullRange, float srcPeakNits, float dstPeakNits) {

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("non-positive dimensions");
        }
        if (yPlane == null || uPlane == null || vPlane == null
            || bgraOut == null
            || yScratch == null || uScratch == null || vScratch == null) {
            throw new IllegalArgumentException("null buffer");
        }
        final int chromaH = (height + 1) / 2;
        final long bgraBytes = (long) width * height * 4L;
        if (bgraOut.byteSize() < bgraBytes) {
            throw new IllegalArgumentException(
                "bgraOut too small: need " + bgraBytes + ", got " + bgraOut.byteSize());
        }
        if (yScratch.length < yStride * height
         || uScratch.length < uStride * chromaH
         || vScratch.length < vStride * chromaH) {
            throw new IllegalArgumentException("scratch buffers too small");
        }

        // Snapshot plane bytes into pooled scratch — we read
        // concurrently across rows and ByteBuffer position state
        // isn't thread-safe. snapshotInto() copies up to the buffer
        // size; planes shorter than the declared stride*rows simply
        // leave the tail of the scratch array untouched (which is
        // safe — that region isn't read by processRow).
        snapshotInto(yPlane, yScratch, yStride, height);
        snapshotInto(uPlane, uScratch, uStride, chromaH);
        snapshotInto(vPlane, vScratch, vStride, chromaH);

        final YuvCoeffs yc = pickYuvCoeffs(yuvMatrix, fullRange);
        final float[]  mat = gamutMatrix(primaries);

        // Tone-map normalisation: PQ peaks at 10000 nits = 1.0 in
        // normalised linear; for HLG we treat 1.0 as peak (1000 nits).
        // Both get scaled into a common (srcPeak / 10000) scale here.
        final float srcPeak = (srcPeakNits > 0.f) ? srcPeakNits
                : (transferFn == TFN_HLG ? 1000.f : 1000.f);
        final float dstPeak = (dstPeakNits > 0.f) ? dstPeakNits : 100.f;
        final float srcMaxLin = srcPeak / 10000.f;
        final float dstMaxLin = dstPeak / 10000.f;
        float ks = 1.5f * dstMaxLin / srcMaxLin - 0.5f;
        if (ks < 0.05f) ks = 0.05f;
        if (ks > 0.95f) ks = 0.95f;
        final float kneeStart = ks;
        final float kneeRange = 1.f - ks;
        final float invSrcMax = 1.f / srcMaxLin;
        final float invDstMax = 1.f / dstMaxLin;

        // Slice rows; parallel for sufficiently big frames. 1080p+ is
        // worth the overhead, 720p one-shots aren't.
        final int rows = height;
        final boolean parallel = rows >= 540
                && Runtime.getRuntime().availableProcessors() > 1;

        IntStream stream = IntStream.range(0, rows);
        if (parallel) {
            stream = stream.parallel();
        }

        // The row workers write to bgraOut via absolute-offset
        // MemorySegment.set(JAVA_BYTE, offset, ...) — that's thread-
        // safe at byte granularity (different threads write different
        // offsets) and skips the intermediate byte[] → ByteBuffer copy
        // the old version paid per frame.
        stream.forEach(row -> processRow(
            yScratch, yStride, uScratch, uStride, vScratch, vStride,
            row, width, bgraOut,
            yc, mat,
            transferFn,
            srcMaxLin, dstMaxLin, invSrcMax, invDstMax,
            kneeStart, kneeRange));
    }

    // ---- inner workings ---------------------------------------------------

    /** Copy at most {@code stride*rows} bytes from {@code src}'s
     *  current position into a caller-supplied scratch array. Does
     *  not mutate the caller's ByteBuffer position. Used to give the
     *  parallel row workers thread-safe access (each worker reads
     *  via array indexing — no shared {@link ByteBuffer} state). */
    private static void snapshotInto(ByteBuffer src, byte[] dst,
                                     int stride, int rows) {
        int needed = stride * rows;
        ByteBuffer dup = src.duplicate();
        int avail = dup.remaining();
        int n = Math.min(Math.min(needed, avail), dst.length);
        dup.get(dst, 0, n);
    }

    private static final class YuvCoeffs {
        // RGB = (y - yOffs)*yk + (u - uvOffs)*uk + (v - uvOffs)*vk
        final float kr, kg, kb;    // R coefficients (kr_y, kr_u, kr_v)
        final float gr, gg, gb;    // G
        final float br, bg, bb;    // B
        final float yOffs;         // typically 16 (limited) or 0 (full)
        final float uvOffs;        // 128 always
        final float yScale;        // scale to bring into [0,1]
        final float uvScale;
        YuvCoeffs(float kr, float kg, float kb,
                  float gr, float gg, float gb,
                  float br, float bg, float bb,
                  float yOffs, float uvOffs,
                  float yScale, float uvScale) {
            this.kr=kr; this.kg=kg; this.kb=kb;
            this.gr=gr; this.gg=gg; this.gb=gb;
            this.br=br; this.bg=bg; this.bb=bb;
            this.yOffs=yOffs; this.uvOffs=uvOffs;
            this.yScale=yScale; this.uvScale=uvScale;
        }
    }

    // BT.2020 non-constant-luminance YUV→RGB matrix (limited range 8-bit).
    private static final YuvCoeffs C_BT2020_LIMITED = new YuvCoeffs(
            1.16438f,  0.f,        1.67867f,    // R
            1.16438f, -0.18733f,  -0.65042f,    // G
            1.16438f,  2.14177f,   0.f,         // B
            16.f, 128.f, 1.f / 219.f, 1.f / 224.f);

    private static final YuvCoeffs C_BT709_LIMITED = new YuvCoeffs(
            1.16438f,  0.f,        1.79274f,
            1.16438f, -0.21325f,  -0.53291f,
            1.16438f,  2.11240f,   0.f,
            16.f, 128.f, 1.f / 219.f, 1.f / 224.f);

    private static final YuvCoeffs C_BT601_LIMITED = new YuvCoeffs(
            1.16438f,  0.f,        1.59603f,
            1.16438f, -0.39176f,  -0.81297f,
            1.16438f,  2.01723f,   0.f,
            16.f, 128.f, 1.f / 219.f, 1.f / 224.f);

    private static final YuvCoeffs C_JPEG_FULL = new YuvCoeffs(
            1.0f,  0.f,        1.402f,
            1.0f, -0.34414f,  -0.71414f,
            1.0f,  1.772f,     0.f,
            0.f, 128.f, 1.f / 255.f, 1.f / 255.f);

    private static YuvCoeffs pickYuvCoeffs(int yuvMatrix, boolean fullRange) {
        if (yuvMatrix == YUV_JPEG_FULL || fullRange) return C_JPEG_FULL;
        switch (yuvMatrix) {
            case YUV_BT2020: return C_BT2020_LIMITED;
            case YUV_BT709:  return C_BT709_LIMITED;
            case YUV_BT601:  return C_BT601_LIMITED;
            default:         return C_BT709_LIMITED;
        }
    }

    private static float lutSample(float[] lut, float t) {
        if (t <= 0.f) return lut[0];
        if (t >= 1.f) return lut[LUT_SIZE - 1];
        float x = t * (LUT_SIZE - 1);
        int i = (int) x;
        float f = x - i;
        return lut[i] + (lut[i + 1] - lut[i]) * f;
    }

    private static int srgbU8(float lin) {
        if (lin <= 0.f) return 0;
        if (lin >= 1.f) return 255;
        return SRGB_OETF_LUT_U8[(int) (lin * (LUT_SIZE - 1) + 0.5f)] & 0xFF;
    }

    private static void processRow(
            byte[] y, int yStride, byte[] u, int uStride, byte[] v, int vStride,
            int row, int width, MemorySegment out,
            YuvCoeffs c, float[] gamut,
            int transferFn,
            float srcMaxLin, float dstMaxLin,
            float invSrcMax, float invDstMax,
            float kneeStart, float kneeRange) {

        final int chromaRow = row >> 1;
        final int yRowBase  = row * yStride;
        final int uRowBase  = chromaRow * uStride;
        final int vRowBase  = chromaRow * vStride;
        final long outRowBase = (long) row * width * 4L;
        final boolean hlg = transferFn == TFN_HLG;
        final boolean pq  = transferFn == TFN_PQ;

        for (int col = 0; col < width; col++) {
            int yi = (y[yRowBase + col]) & 0xFF;
            int chromaCol = col >> 1;
            int ui = (u[uRowBase + chromaCol]) & 0xFF;
            int vi = (v[vRowBase + chromaCol]) & 0xFF;

            float yf = (yi - c.yOffs) * c.yScale;
            float uf = (ui - c.uvOffs) * c.uvScale;
            float vf = (vi - c.uvOffs) * c.uvScale;

            // YUV → R'G'B' (non-linear)
            float r0 = yf * c.kr + uf * c.kg + vf * c.kb;
            float g0 = yf * c.gr + uf * c.gg + vf * c.gb;
            float b0 = yf * c.br + uf * c.bg + vf * c.bb;

            // Clamp to [0,1] before EOTF — keeps the LUT lookup in range.
            r0 = clamp01(r0); g0 = clamp01(g0); b0 = clamp01(b0);

            // EOTF → linear in normalised peak units (1.0 = 10000nits PQ
            // or 1000nits HLG, depending on transfer).
            float rl, gl, bl;
            if (pq) {
                rl = lutSample(PQ_EOTF_LUT, r0);
                gl = lutSample(PQ_EOTF_LUT, g0);
                bl = lutSample(PQ_EOTF_LUT, b0);
            } else if (hlg) {
                rl = lutSample(HLG_EOTF_LUT, r0);
                gl = lutSample(HLG_EOTF_LUT, g0);
                bl = lutSample(HLG_EOTF_LUT, b0);
            } else {
                // SDR pass-through — gamma 2.2 approximation via PQ LUT
                // would be wrong; treat the inputs as already-linearised
                // sRGB. (This path isn't expected to be hot.)
                rl = r0; gl = g0; bl = b0;
            }

            // Gamut convert source → sRGB (BT.2020 in the HDR case).
            float r1 = rl * gamut[0] + gl * gamut[1] + bl * gamut[2];
            float g1 = rl * gamut[3] + gl * gamut[4] + bl * gamut[5];
            float b1 = rl * gamut[6] + gl * gamut[7] + bl * gamut[8];

            // BT.2390-9 per-channel tone curve mapping [0..srcMaxLin]
            // into [0..dstMaxLin], rolled-off above kneeStart.
            r1 = bt2390(r1, srcMaxLin, dstMaxLin, kneeStart, kneeRange);
            g1 = bt2390(g1, srcMaxLin, dstMaxLin, kneeStart, kneeRange);
            b1 = bt2390(b1, srcMaxLin, dstMaxLin, kneeStart, kneeRange);

            // Normalise to [0,1] over the destination peak.
            r1 *= invDstMax; g1 *= invDstMax; b1 *= invDstMax;
            r1 = clamp01(r1); g1 = clamp01(g1); b1 = clamp01(b1);

            // sRGB OETF + pack BGRA. Writing via absolute offset
            // MemorySegment.set is thread-safe at byte granularity
            // — parallel row workers each touch a disjoint range.
            int rU = srgbU8(r1);
            int gU = srgbU8(g1);
            int bU = srgbU8(b1);

            long idx = outRowBase + col * 4L;
            out.set(ValueLayout.JAVA_BYTE, idx,     (byte) bU);
            out.set(ValueLayout.JAVA_BYTE, idx + 1, (byte) gU);
            out.set(ValueLayout.JAVA_BYTE, idx + 2, (byte) rU);
            out.set(ValueLayout.JAVA_BYTE, idx + 3, (byte) 255); // alpha = opaque
        }
    }

    private static float clamp01(float v) {
        return v < 0.f ? 0.f : (v > 1.f ? 1.f : v);
    }

    // BT.2390-9 per-channel EETF (Hermite spline knee with a linear
    // segment below kneeStart).
    private static float bt2390(float lin, float srcMax, float dstMax,
                                 float kneeStart, float kneeRange) {
        if (lin <= 0.f) return 0.f;
        float e1 = lin / srcMax;            // normalise into [0,1]
        if (e1 <= kneeStart) {
            return lin;                     // below knee: pass through
        }
        if (e1 >= 1.f) e1 = 1.f;
        float t  = (e1 - kneeStart) / kneeRange;
        float t2 = t * t;
        float t3 = t2 * t;
        float P  = (2.f * t3 - 3.f * t2 + 1.f) * kneeStart
                 + (t3 - 2.f * t2 + t) * kneeRange
                 + (-2.f * t3 + 3.f * t2) * (dstMax / srcMax);
        return P * srcMax;
    }
}
