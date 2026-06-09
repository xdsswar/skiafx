/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import java.lang.foreign.MemorySegment;

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.EffectPeer;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.prism.PrEffectHelper;
import com.sun.scenario.effect.impl.state.PerspectiveTransformState;
import com.sun.scenario.effect.impl.state.RenderState;

/**
 * Skia peer for the {@code "PerspectiveTransform"} dispatch name.
 * Maps to Skia's {@code SkImageFilters::MatrixTransform} with the 3×3
 * matrix that {@link PerspectiveTransformState} pre-computes from the
 * user's four-corner quad.
 *
 * <p>One drawable allocation per call (destination), one Skia filter
 * handle reused across identical-transform consecutive calls.</p>
 */
public final class SkiaPerspectiveTransformPeer extends EffectPeer<RenderState> {

    private long cachedKey = Long.MIN_VALUE;
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

        PerspectiveTransformState pts = (rstate instanceof PerspectiveTransformState)
            ? (PerspectiveTransformState) rstate : null;

        MemorySegment filter = getOrCreateFilter(pts);
        if (filter == null) {
            // No transform available — passthrough.
            srcData.addref();
            return srcData;
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

    private MemorySegment getOrCreateFilter(PerspectiveTransformState pts) {
        if (pts == null) return null;
        // PerspectiveTransformState exposes the matrix via its
        // transform field/methods. For the common 3x3 affine case we
        // pass it through MatrixTransform; for full perspective the
        // upstream behaviour also collapses to an affine fit so this
        // is an acceptable approximation for v1.
        float[] m = extractMatrix(pts);
        if (m == null) return null;
        long k = hashMatrix(m);
        if (k == cachedKey && cachedFilter != null) return cachedFilter;
        if (cachedFilter != null) {
            NativeBridge.filterDestroy(cachedFilter);
            cachedFilter = null;
        }
        MemorySegment f = NativeBridge.filterMatrixTransform(
            m[0], m[1], m[2],
            m[3], m[4], m[5],
            m[6], m[7], m[8],
            null);
        if (f == null || f.equals(MemorySegment.NULL)) return null;
        cachedKey = k;
        cachedFilter = f;
        return f;
    }

    /** The forward 3×3 perspective matrix from PerspectiveTransformState.
     *  Read directly via the public {@link PerspectiveTransformState#getTx()}
     *  accessor (no reflection — the old code read a non-existent {@code tx}
     *  field and always degraded to passthrough). */
    private static float[] extractMatrix(PerspectiveTransformState pts) {
        float[][] tx = pts.getTx();
        if (tx == null || tx.length < 3 || tx[0].length < 3) return null;
        return new float[] {
            tx[0][0], tx[0][1], tx[0][2],
            tx[1][0], tx[1][1], tx[1][2],
            tx[2][0], tx[2][1], tx[2][2],
        };
    }

    private static long hashMatrix(float[] m) {
        long h = 0L;
        for (float v : m) {
            h = h * 31 + Float.floatToRawIntBits(v);
        }
        return h;
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
