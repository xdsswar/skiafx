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
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.InvertMask;
import com.sun.scenario.effect.impl.EffectPeer;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.prism.PrEffectHelper;
import com.sun.scenario.effect.impl.state.RenderState;

/**
 * Skia peer for the {@code "InvertMask"} dispatch name.
 *
 * <p>Used by {@link com.sun.scenario.effect.InnerShadow} as its first step.
 * Matches upstream {@code InvertMask.jsl}: {@code color = float4(1 - src(pos -
 * offset).a)} over the source bounds grown by {@code pad} on every side, so
 * the padded fringe (where there is no source) is fully opaque and the
 * offset shifts the mask.</p>
 *
 * <p>Implemented as an image-filter chain over the layer source:
 * {@code Crop(dst) <- ColorMatrix(invert alpha) <- Offset(offset) <-
 * Crop(source rect)}. The inner crop limits the source to its real content
 * (the pooled texture may be larger), the color matrix affects transparent
 * black so it fills the padded fringe, and the outer crop bounds the result
 * to the destination.</p>
 */
public final class SkiaInvertMaskPeer extends EffectPeer<RenderState> {

    /** 4x5 row-major matrix: every channel = 1 - a (premultiplied white). */
    private static final float[] INVERT_ALPHA = {
        0f, 0f, 0f, -1f, 1f,
        0f, 0f, 0f, -1f, 1f,
        0f, 0f, 0f, -1f, 1f,
        0f, 0f, 0f, -1f, 1f,
    };

    // Cached chain, keyed by its geometry.
    private MemorySegment cachedFilter;
    private int cSrcX, cSrcY, cSrcW, cSrcH, cDstW, cDstH, cOffX, cOffY;

    public SkiaInvertMaskPeer(FilterContext fctx, Renderer r, String name) {
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

        // Drag-resize bypass.
        if (SkiaEffectRenderer.shouldBypassForDrag()) {
            SkiaEffectRenderer.BYPASS_COUNT.incrementAndGet();
            srcData.addref();
            return srcData;
        }

        int pad = 0, offX = 0, offY = 0;
        if (effect instanceof InvertMask im) {
            pad  = Math.max(0, im.getPad());
            offX = im.getOffsetX();
            offY = im.getOffsetY();
        }

        final Rectangle srcBounds = srcData.getTransformedBounds(null);
        // Upstream InvertMask.getResultBounds: (source ∩ clip) grown by pad.
        // The pad is applied AFTER clipping — the fringe outside the clip is
        // exactly the opaque border the following shadow blur pulls inward.
        // getTransformedBounds returns the ImageData's OWN bounds object for an
        // identity transform (and ignores the clip): copy before mutating.
        Rectangle dstBounds = new Rectangle(srcBounds);
        if (outputClip != null) {
            dstBounds.intersectWith(outputClip);
        }
        dstBounds.grow(pad, pad);
        if (dstBounds.width <= 0 || dstBounds.height <= 0) {
            // Empty result: a real cleared drawable, like upstream (never image-less).
            return SkiaEffectRenderer.emptyResult(fctx, getRenderer(), dstBounds);
        }

        if (!srcData.validate(fctx)) {
            return SkiaEffectRenderer.emptyResult(fctx, getRenderer(), dstBounds);
        }

        MemorySegment filter = ensureFilter(
            srcBounds.x - dstBounds.x, srcBounds.y - dstBounds.y,
            srcBounds.width, srcBounds.height,
            dstBounds.width, dstBounds.height, offX, offY);

        SkiaPrDrawable dst = (SkiaPrDrawable)
            getRenderer().getCompatibleImage(dstBounds.width, dstBounds.height);
        if (dst == null) {
            return new ImageData(fctx, null, dstBounds);
        }
        if (filter == null) {
            // Native failure: a cleared drawable is the closest safe result.
            return new ImageData(fctx, dst, dstBounds);
        }

        Graphics gdst = dst.createGraphics();
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

    private MemorySegment ensureFilter(int srcX, int srcY, int srcW, int srcH,
                                       int dstW, int dstH, int offX, int offY) {
        if (cachedFilter != null
                && cSrcX == srcX && cSrcY == srcY && cSrcW == srcW && cSrcH == srcH
                && cDstW == dstW && cDstH == dstH && cOffX == offX && cOffY == offY) {
            return cachedFilter;
        }
        releaseFilter();

        MemorySegment cropSrc = null, offset = null, matrix = null,
                      invert = null, result = null;
        try {
            cropSrc = NativeBridge.filterCrop(srcX, srcY, srcW, srcH, null);
            if (isNull(cropSrc)) return null;
            offset = NativeBridge.filterOffset(offX, offY, cropSrc);
            if (isNull(offset)) return null;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment m = a.allocate(ValueLayout.JAVA_FLOAT, INVERT_ALPHA.length);
                for (int i = 0; i < INVERT_ALPHA.length; i++) {
                    m.setAtIndex(ValueLayout.JAVA_FLOAT, i, INVERT_ALPHA[i]);
                }
                matrix = NativeBridge.filterColorMatrix(m);
            }
            if (isNull(matrix)) return null;
            invert = NativeBridge.filterCompose(matrix, offset);
            if (isNull(invert)) return null;
            result = NativeBridge.filterCrop(0, 0, dstW, dstH, invert);
            if (isNull(result)) return null;
        } finally {
            // The composed filters hold their own refs on the inner stages.
            destroy(cropSrc);
            destroy(offset);
            destroy(matrix);
            destroy(invert);
        }
        cachedFilter = result;
        cSrcX = srcX; cSrcY = srcY; cSrcW = srcW; cSrcH = srcH;
        cDstW = dstW; cDstH = dstH; cOffX = offX; cOffY = offY;
        return result;
    }

    private static boolean isNull(MemorySegment s) {
        return s == null || s.equals(MemorySegment.NULL);
    }

    private static void destroy(MemorySegment s) {
        if (!isNull(s)) {
            NativeBridge.filterDestroy(s);
        }
    }

    private void releaseFilter() {
        if (cachedFilter != null) {
            NativeBridge.filterDestroy(cachedFilter);
            cachedFilter = null;
        }
    }

    @Override
    public void dispose() {
        releaseFilter();
    }
}
