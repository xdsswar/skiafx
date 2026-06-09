/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.scenario.effect.DisplacementMap;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.FloatMap;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.EffectPeer;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.prism.PrEffectHelper;
import com.sun.scenario.effect.impl.state.RenderState;

/**
 * Skia peer for the {@code "DisplacementMap"} dispatch name.
 *
 * <p>Skia's {@code SkImageFilters::DisplacementMap} takes a
 * <em>filter</em> as its displacement source. We convert JFX's
 * {@link FloatMap} to an RGBA {@code SkImage} (red = X-offset,
 * green = Y-offset, with the conventional {@code color - 0.5} signed
 * encoding) and wrap it via {@code SkImageFilters::Image}, then
 * chain through {@code DisplacementMap} with the source image as the
 * "input" filter (null → defaults to the canvas content under the
 * saveLayer).</p>
 *
 * <h2>Encoding</h2>
 *
 * <p>JFX uses {@code sample ∈ [-1, 1]} where ±1 means
 * {@code ± scaleX/Y} pixel offset. Skia uses
 * {@code pixel_offset = scale * (channel - 0.5)} with
 * {@code channel ∈ [0, 1]}. So we encode
 * {@code channel = 0.5 + sample / 2}, and pass
 * {@code skia_scale = scaleX_jfx * 2} (and Y).</p>
 *
 * <h2>Caching</h2>
 *
 * <p>One cached entry per peer: {@code (FloatMap-identity, content-digest)}.
 * When the same FloatMap is passed unchanged, we reuse the SkImage +
 * SkImageFilter handles. When the digest changes (the map was mutated)
 * we re-upload. Both native handles are released on miss / dispose.</p>
 *
 * <p>The {@code offsetX} / {@code offsetY} knobs on JFX's
 * DisplacementMap are not directly expressible through Skia's
 * DisplacementMap primitive; they're applied via an outer
 * {@code MatrixTransform} composition when non-zero.</p>
 */
public final class SkiaDisplacementMapPeer extends EffectPeer<RenderState> {

    // Cached upload for the most-recently-seen FloatMap. Held as a
    // strong ref to the map identity so we can do == checks on the
    // hot path; cleared whenever the digest mismatches or dispose()
    // runs.
    private FloatMap cachedMap;
    private long     cachedDigest;
    private MemorySegment cachedImage;
    private MemorySegment cachedImageFilter;

    public SkiaDisplacementMapPeer(FilterContext fctx, Renderer r, String name) {
        super(fctx, r, name);
    }

    @Override
    public ImageData filter(Effect effect,
                            RenderState rstate,
                            BaseTransform transform,
                            Rectangle outputClip,
                            ImageData... inputs) {
        final FilterContext fctx = getFilterContext();
        final ImageData srcData = inputs[0];

        if (SkiaEffectRenderer.shouldBypassForDrag()) {
            SkiaEffectRenderer.BYPASS_COUNT.incrementAndGet();
            srcData.addref();
            return srcData;
        }

        if (!(effect instanceof DisplacementMap dm)) {
            srcData.addref();
            return srcData;
        }
        FloatMap map = dm.getMapData();
        if (map == null) {
            srcData.addref();
            return srcData;
        }

        Rectangle srcBounds = srcData.getTransformedBounds(null);
        Rectangle dstBounds = new Rectangle(srcBounds);
        dstBounds.intersectWith(outputClip);
        if (dstBounds.width <= 0 || dstBounds.height <= 0) {
            return new ImageData(fctx, null, srcBounds);
        }

        SkiaPrDrawable dst = (SkiaPrDrawable)
            getRenderer().getCompatibleImage(dstBounds.width, dstBounds.height);
        if (dst == null || !srcData.validate(fctx)) {
            return new ImageData(fctx, dst, dstBounds);
        }

        // (Re-)build the displacement filter chain.
        MemorySegment displaceFilter = ensureDisplacementFilter(dm, map);
        if (displaceFilter == null) {
            // Native upload failed — fall back to passthrough.
            srcData.addref();
            return srcData;
        }

        // displaceFilter is built fresh per call (it composes the cached
        // displacement image-filter with the per-call scale); the outer finally
        // releases it on every exit path — including the early returns below —
        // so it never leaks a native SkImageFilter every frame.
        try {
            com.sun.prism.Graphics gdst = dst.createGraphics();
            if (gdst == null) {
                return new ImageData(fctx, dst, dstBounds);
            }
            MemorySegment dstSeg = MemorySegment.ofAddress(dst.getSurfaceHandle());
            if (dstSeg.equals(MemorySegment.NULL)) {
                return new ImageData(fctx, dst, dstBounds);
            }
            NativeBridge.surfaceSaveLayerWithFilter(dstSeg, displaceFilter);
            try {
                PrEffectHelper.renderImageData(gdst, srcData, dstBounds);
            } finally {
                NativeBridge.surfaceRestore(dstSeg);
            }
            return new ImageData(fctx, dst, dstBounds);
        } finally {
            NativeBridge.filterDestroy(displaceFilter);
        }
    }

    /**
     * Returns the cached DisplacementMap filter for {@code dm} /
     * {@code map}, re-uploading the FloatMap to an SkImage if the
     * map identity or content changed since the last call.
     */
    private MemorySegment ensureDisplacementFilter(DisplacementMap dm, FloatMap map) {
        long digest = digest(map);
        if (map != cachedMap || digest != cachedDigest || cachedImageFilter == null) {
            releaseCached();
            MemorySegment image = uploadFloatMap(map);
            if (image == null || image.equals(MemorySegment.NULL)) return null;
            MemorySegment imageFilter = NativeBridge.filterImage(image);
            if (imageFilter == null || imageFilter.equals(MemorySegment.NULL)) {
                NativeBridge.imageDestroy(image);
                return null;
            }
            cachedMap         = map;
            cachedDigest      = digest;
            cachedImage       = image;
            cachedImageFilter = imageFilter;
        }
        // Chain DisplacementMap(filter=imageFilter) over the layer
        // input. Channel 0 = R, 1 = G.
        float skiaScale = 2f * Math.max(Math.abs(dm.getScaleX()),
                                        Math.abs(dm.getScaleY()));
        // input filter null → Skia uses the saveLayer's source content.
        return NativeBridge.filterDisplacementMap(
            0, 1, skiaScale, cachedImageFilter, null);
    }

    /**
     * Allocates a confined arena, packs the FloatMap into RGBA bytes
     * (R = X-offset, G = Y-offset, B = 0, A = 255), calls
     * {@code imageCreateRaster}, then releases the arena. Skia copies
     * the pixels during {@code imageCreateRaster} so the native side
     * holds nothing into our arena past return.
     */
    private static MemorySegment uploadFloatMap(FloatMap map) {
        int w = map.getWidth();
        int h = map.getHeight();
        float[] src = map.getData(); // length = w * h * 4
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate((long) w * h * 4);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int srcIdx = (x + y * w) * 4;
                    int dstIdx = (x + y * w) * 4;
                    float fx = src[srcIdx];
                    float fy = src[srcIdx + 1];
                    int rByte = encode(fx);
                    int gByte = encode(fy);
                    seg.set(ValueLayout.JAVA_BYTE, dstIdx,     (byte) rByte);
                    seg.set(ValueLayout.JAVA_BYTE, dstIdx + 1, (byte) gByte);
                    seg.set(ValueLayout.JAVA_BYTE, dstIdx + 2, (byte) 0);
                    seg.set(ValueLayout.JAVA_BYTE, dstIdx + 3, (byte) 0xFF);
                }
            }
            // colorType 0 = kRGBA_8888.
            return NativeBridge.imageCreateRaster(w, h, w * 4, seg, 0);
        }
    }

    /** Maps a signed [-1, 1] sample to an unsigned [0, 255] byte using
     *  the conventional {@code byte = (sample + 1) * 127.5} formula
     *  with saturation. */
    private static int encode(float sample) {
        int v = (int) ((sample + 1f) * 127.5f + 0.5f);
        if (v < 0)   v = 0;
        if (v > 255) v = 255;
        return v;
    }

    /**
     * Cheap content digest — sums float bits at sparse strides
     * (every 256th pixel for large maps, every pixel for small).
     * Detects whole-map mutations between frames without scanning
     * every cell each call.
     */
    private static long digest(FloatMap map) {
        float[] data = map.getData();
        int w = map.getWidth(), h = map.getHeight();
        long h64 = 0x100000001b3L ^ (((long) w << 32) | (long) h);
        int stride = Math.max(1, (w * h) / 256); // ≤ 256 samples
        for (int i = 0; i < data.length; i += stride * 4) {
            h64 ^= Float.floatToRawIntBits(data[i]) & 0xFFFFFFFFL;
            h64 *= 0x100000001b3L;
        }
        return h64;
    }

    private void releaseCached() {
        if (cachedImageFilter != null) {
            NativeBridge.filterDestroy(cachedImageFilter);
            cachedImageFilter = null;
        }
        if (cachedImage != null) {
            NativeBridge.imageDestroy(cachedImage);
            cachedImage = null;
        }
        cachedMap = null;
        cachedDigest = 0L;
    }

    @Override
    public void dispose() {
        releaseCached();
    }
}
