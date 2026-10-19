/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.Graphics;
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
 * <p>Reproduces upstream {@code DisplacementMap.jsl} with an
 * {@code SkRuntimeEffect} image filter ({@code openjfx_skia_effects_ext.cpp}):
 * for each output pixel at unit position {@code pos} over the source content,
 * {@code loc = pos + scale * (map(pos) + offset)} (in source-size units),
 * optionally wrapped, then the source is sampled at {@code loc}. The
 * {@link FloatMap} is uploaded once as a raw signed half-float RGBA image (no 8-bit
 * quantization) and stretched over the source bounds; anisotropic scale,
 * offset and wrap are all honoured.</p>
 *
 * <h2>Caching</h2>
 *
 * <p>The uploaded map image is cached per {@code (FloatMap identity,
 * FloatMap.getModCount())}; the filter is cached per (map image, source
 * size, offset, scale, wrap). Both native handles are released on miss and on
 * {@link #dispose()}.</p>
 *
 * <p>The effect uses {@code UnclippedUserSpaceRenderState}: any output pixel
 * may sample any source pixel, so the output covers the full source bounds
 * (not just the output clip).</p>
 */
public final class SkiaDisplacementMapPeer extends EffectPeer<RenderState> {

    private static final int PARAM_COUNT = 9;

    // Cached map upload.
    private FloatMap      cachedMap;
    private long          cachedModCount;
    private MemorySegment cachedImage;

    // Cached filter (built over cachedImage).
    private MemorySegment cachedFilter;
    private int     fSrcW, fSrcH;
    private float   fOffX, fOffY, fScaleX, fScaleY;
    private boolean fWrap;

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
        if (map == null || !SkiaEffectsNative.isDisplacementAvailable()) {
            srcData.addref();
            return srcData;
        }

        Rectangle srcBounds = srcData.getTransformedBounds(null);
        if (srcBounds.width <= 0 || srcBounds.height <= 0) {
            return SkiaEffectRenderer.emptyResult(fctx, getRenderer(), srcBounds);
        }
        if (outputClip != null) {
            Rectangle visible = new Rectangle(srcBounds);
            visible.intersectWith(outputClip);
            if (visible.width <= 0 || visible.height <= 0) {
                // Empty result: a real cleared drawable, like upstream (never image-less).
                return SkiaEffectRenderer.emptyResult(fctx, getRenderer(), visible);
            }
        }
        // Full source bounds: displaced lookups may reach outside the clip.
        final Rectangle dstBounds = new Rectangle(srcBounds);

        if (!srcData.validate(fctx)) {
            return SkiaEffectRenderer.emptyResult(fctx, getRenderer(), dstBounds);
        }

        // Build (or reuse) the filter BEFORE checking out the destination so a
        // native failure falls back to passthrough without leaking a pooled
        // drawable.
        MemorySegment displaceFilter = ensureDisplacementFilter(dm, map,
            dstBounds.width, dstBounds.height);
        if (displaceFilter == null) {
            srcData.addref();
            return srcData;
        }

        SkiaPrDrawable dst = (SkiaPrDrawable)
            getRenderer().getCompatibleImage(dstBounds.width, dstBounds.height);
        if (dst == null) {
            return new ImageData(fctx, null, dstBounds);
        }

        Graphics gdst = dst.createGraphics();
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
    }

    /**
     * Returns the cached displacement filter, re-uploading the map when its
     * identity / modification count changed and rebuilding the filter when
     * any parameter changed. Null on native failure (caller passes through).
     */
    private MemorySegment ensureDisplacementFilter(DisplacementMap dm, FloatMap map,
                                                   int srcW, int srcH) {
        long mod = map.getModCount();
        if (map != cachedMap || mod != cachedModCount || cachedImage == null) {
            releaseCached();
            MemorySegment image = SkiaEffectsNative.floatMapImage(
                map.getData(), map.getWidth(), map.getHeight());
            if (image == null) {
                return null;
            }
            cachedMap      = map;
            cachedModCount = mod;
            cachedImage    = image;
        }
        float offX = dm.getOffsetX();
        float offY = dm.getOffsetY();
        float scX  = dm.getScaleX();
        float scY  = dm.getScaleY();
        boolean wrap = dm.getWrap();
        if (cachedFilter != null && fSrcW == srcW && fSrcH == srcH
                && fOffX == offX && fOffY == offY
                && fScaleX == scX && fScaleY == scY && fWrap == wrap) {
            return cachedFilter;
        }
        releaseFilter();
        MemorySegment filter;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = arena.allocate(ValueLayout.JAVA_FLOAT, PARAM_COUNT);
            p.setAtIndex(ValueLayout.JAVA_FLOAT, 0, 0f);   // source rect in layer coords
            p.setAtIndex(ValueLayout.JAVA_FLOAT, 1, 0f);
            p.setAtIndex(ValueLayout.JAVA_FLOAT, 2, srcW);
            p.setAtIndex(ValueLayout.JAVA_FLOAT, 3, srcH);
            p.setAtIndex(ValueLayout.JAVA_FLOAT, 4, offX);
            p.setAtIndex(ValueLayout.JAVA_FLOAT, 5, offY);
            p.setAtIndex(ValueLayout.JAVA_FLOAT, 6, scX);
            p.setAtIndex(ValueLayout.JAVA_FLOAT, 7, scY);
            p.setAtIndex(ValueLayout.JAVA_FLOAT, 8, wrap ? 1f : 0f);
            filter = SkiaEffectsNative.displacementFilter(cachedImage, p, PARAM_COUNT);
        }
        if (filter == null) {
            return null;
        }
        cachedFilter = filter;
        fSrcW = srcW;
        fSrcH = srcH;
        fOffX = offX;
        fOffY = offY;
        fScaleX = scX;
        fScaleY = scY;
        fWrap = wrap;
        return filter;
    }

    private void releaseFilter() {
        if (cachedFilter != null) {
            NativeBridge.filterDestroy(cachedFilter);
            cachedFilter = null;
        }
    }

    private void releaseCached() {
        releaseFilter();
        if (cachedImage != null) {
            NativeBridge.imageDestroy(cachedImage);
            cachedImage = null;
        }
        cachedMap = null;
        cachedModCount = 0L;
    }

    @Override
    public void dispose() {
        releaseCached();
    }
}
