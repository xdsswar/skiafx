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
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.EffectPeer;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.prism.PrEffectHelper;
import com.sun.scenario.effect.impl.state.RenderState;

/**
 * Shared base for the color-matrix peers (ColorAdjust, SepiaTone,
 * Brightpass, and the InnerShadow component peers). Subclasses
 * provide:
 *
 * <ol>
 *   <li>A row-major 4×5 matrix via {@link #buildMatrix(Effect)}, called
 *       on every {@code filter()}.</li>
 *   <li>A {@link #stateKey(Effect)} hash so the filter handle can be
 *       cached and reused on identical-param consecutive calls.</li>
 * </ol>
 *
 * <p>This base handles the standard plumbing: drag-resize bypass,
 * destination drawable checkout, filter creation + caching,
 * saveLayer + restore, source render via {@link PrEffectHelper}.</p>
 */
public abstract class SkiaColorMatrixPeerBase extends EffectPeer<RenderState> {

    private long cachedKey = Long.MIN_VALUE;
    private MemorySegment cachedFilter;

    protected SkiaColorMatrixPeerBase(FilterContext fctx, Renderer r, String name) {
        super(fctx, r, name);
    }

    /** Row-major 4x5 matrix [r' g' b' a'] × [r g b a 1] → result. */
    protected abstract float[] buildMatrix(Effect effect);

    /** Hash of params that fed buildMatrix. Identical hash → reuse the
     *  cached filter handle. */
    protected abstract long stateKey(Effect effect);

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

        final Rectangle srcBounds = srcData.getTransformedBounds(null);
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

        MemorySegment filter = getOrCreateFilter(effect);
        if (filter == null) {
            return new ImageData(fctx, dst, dstBounds);
        }

        com.sun.prism.Graphics gdst = dst.createGraphics();
        if (gdst == null) {
            return new ImageData(fctx, dst, dstBounds);
        }
        MemorySegment dstSeg = MemorySegment.ofAddress(dst.getSurfaceHandle());
        if (dstSeg.equals(MemorySegment.NULL)) {
            return new ImageData(fctx, dst, dstBounds);
        }
        NativeBridge.surfaceSaveLayerWithFilter(dstSeg, filter);
        try {
            PrEffectHelper.renderImageData(gdst, srcData, dstBounds);
        } finally {
            NativeBridge.surfaceRestore(dstSeg);
        }
        return new ImageData(fctx, dst, dstBounds);
    }

    private MemorySegment getOrCreateFilter(Effect effect) {
        long k = stateKey(effect);
        if (k == cachedKey && cachedFilter != null) {
            return cachedFilter;
        }
        if (cachedFilter != null) {
            NativeBridge.filterDestroy(cachedFilter);
            cachedFilter = null;
        }
        float[] m = buildMatrix(effect);
        if (m == null || m.length != 20) return null;
        MemorySegment f;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ms = a.allocate(ValueLayout.JAVA_FLOAT, 20);
            for (int i = 0; i < 20; i++) {
                ms.setAtIndex(ValueLayout.JAVA_FLOAT, i, m[i]);
            }
            f = NativeBridge.filterColorMatrix(ms);
        }
        if (f == null || f.equals(MemorySegment.NULL)) return null;
        cachedKey = k;
        cachedFilter = f;
        return f;
    }

    @Override
    public void dispose() {
        if (cachedFilter != null) {
            NativeBridge.filterDestroy(cachedFilter);
            cachedFilter = null;
            cachedKey = Long.MIN_VALUE;
        }
    }
}
