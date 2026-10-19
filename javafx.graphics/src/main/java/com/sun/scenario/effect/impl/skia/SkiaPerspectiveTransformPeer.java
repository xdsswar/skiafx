/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import java.lang.foreign.MemorySegment;

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.Graphics;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.EffectPeer;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.prism.PrEffectHelper;
import com.sun.scenario.effect.impl.state.AccessHelper;
import com.sun.scenario.effect.impl.state.PerspectiveTransformState;
import com.sun.scenario.effect.impl.state.RenderState;

/**
 * Skia peer for the {@code "PerspectiveTransform"} dispatch name.
 * Maps to Skia's {@code SkImageFilters::MatrixTransform} with a full 3x3
 * (projective) matrix.
 *
 * <p>The effect's {@link PerspectiveTransformState} (reached through
 * {@link AccessHelper#getState}, exactly like upstream
 * {@code PerspectiveTransform.jsl}; the {@code rstate} handed to the peer
 * is the stock {@code UnclippedUserSpaceRenderState}) holds {@code H}, the
 * mapping from the input's UNIT square to output device coordinates. The
 * input is rasterised into the layer with its bounds' origin at the layer
 * origin, so a layer pixel {@code l} is the input point
 * {@code src.xy + l}, whose unit coordinate is {@code l / src.wh}. The
 * filter matrix that lands it in the output drawable (whose origin is
 * {@code dst.xy} in device space) is therefore</p>
 *
 * <pre>  M = T(-dst.x, -dst.y) . H . S(1 / src.w, 1 / src.h)</pre>
 *
 * <p>and the output bounds are the effect's result bounds (the transformed
 * quad intersected with the clip), as upstream.</p>
 */
public final class SkiaPerspectiveTransformPeer extends EffectPeer<RenderState> {

    private final float[] matrix = new float[9];
    private final float[] cachedMatrix = new float[9];
    private MemorySegment cachedFilter;

    public SkiaPerspectiveTransformPeer(FilterContext fctx, Renderer r, String name) {
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

        final Rectangle srcBounds = srcData.getTransformedBounds(null);
        Rectangle dstBounds = new Rectangle(
            effect.getResultBounds(transform, outputClip, srcData));
        if (dstBounds.width <= 0 || dstBounds.height <= 0
                || srcBounds.width <= 0 || srcBounds.height <= 0) {
            // Empty result: a real cleared drawable, like upstream (never image-less).
            return SkiaEffectRenderer.emptyResult(fctx, getRenderer(), dstBounds);
        }

        Object st = AccessHelper.getState(effect);
        if (!(st instanceof PerspectiveTransformState pts)
                || !buildMatrix(pts, srcBounds, dstBounds)) {
            // No usable transform -- pass the input through unchanged.
            srcData.addref();
            return srcData;
        }
        MemorySegment filter = getOrCreateFilter();
        if (filter == null) {
            srcData.addref();
            return srcData;
        }

        SkiaPrDrawable dst = (SkiaPrDrawable)
            getRenderer().getCompatibleImage(dstBounds.width, dstBounds.height);
        if (dst == null || !srcData.validate(fctx)) {
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
            // Rasterise the whole input with its bounds' origin at the layer
            // origin (renderImageData samples the source at R - srcBounds,
            // so R = srcBounds is a 1:1 copy at (0,0)); M then maps it into
            // the output drawable.
            PrEffectHelper.renderImageData(gdst, srcData, srcBounds);
        } finally {
            NativeBridge.surfaceRestore(dstSeg);
        }
        return new ImageData(fctx, dst, dstBounds);
    }

    /** Fills {@link #matrix} with M (see class doc). False if degenerate. */
    private boolean buildMatrix(PerspectiveTransformState pts,
                                Rectangle src, Rectangle dst) {
        float[][] h = pts.getTx();
        if (h == null || h.length < 3 || h[0].length < 3
                || h[1].length < 3 || h[2].length < 3) {
            return false;
        }
        final float iw = 1f / src.width;
        final float ih = 1f / src.height;
        final float dx = dst.x;
        final float dy = dst.y;
        // HS = H . S(1/w, 1/h): scale the first two columns.
        final float r20 = h[2][0] * iw, r21 = h[2][1] * ih, r22 = h[2][2];
        // T(-dx,-dy) . HS: row0 -= dx*row2, row1 -= dy*row2.
        matrix[0] = h[0][0] * iw - dx * r20;
        matrix[1] = h[0][1] * ih - dx * r21;
        matrix[2] = h[0][2]      - dx * r22;
        matrix[3] = h[1][0] * iw - dy * r20;
        matrix[4] = h[1][1] * ih - dy * r21;
        matrix[5] = h[1][2]      - dy * r22;
        matrix[6] = r20;
        matrix[7] = r21;
        matrix[8] = r22;
        for (int i = 0; i < 9; i++) {
            if (!Float.isFinite(matrix[i])) return false;
        }
        return true;
    }

    private MemorySegment getOrCreateFilter() {
        if (cachedFilter != null) {
            boolean same = true;
            for (int i = 0; i < 9; i++) {
                if (Float.compare(matrix[i], cachedMatrix[i]) != 0) {
                    same = false;
                    break;
                }
            }
            if (same) return cachedFilter;
            NativeBridge.filterDestroy(cachedFilter);
            cachedFilter = null;
        }
        float[] m = matrix;
        MemorySegment f = NativeBridge.filterMatrixTransform(
            m[0], m[1], m[2],
            m[3], m[4], m[5],
            m[6], m[7], m[8],
            null);
        if (f == null || f.equals(MemorySegment.NULL)) return null;
        System.arraycopy(m, 0, cachedMatrix, 0, 9);
        cachedFilter = f;
        return f;
    }

    @Override
    public void dispose() {
        if (cachedFilter != null) {
            NativeBridge.filterDestroy(cachedFilter);
            cachedFilter = null;
        }
    }
}
