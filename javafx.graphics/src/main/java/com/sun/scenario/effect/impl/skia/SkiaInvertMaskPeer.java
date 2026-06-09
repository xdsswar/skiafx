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
 * Skia peer for the {@code "InvertMask"} dispatch name.
 *
 * <p>Used by {@link com.sun.scenario.effect.InnerShadow} as its first
 * step: invert the input's alpha so the shadow is built from the
 * inverted mask, then composited back via SRC_ATOP. Some apps also
 * use it directly as a mask flip.</p>
 *
 * <p>Implemented as a Skia color-matrix that produces {@code RGBA(0,0,0,1-A)}
 * for each pixel (drop colour, invert alpha).</p>
 */
public final class SkiaInvertMaskPeer extends EffectPeer<RenderState> {

    /** 4x5 row-major matrix: output channel = m·(R,G,B,A,1). */
    private static final float[] INVERT_ALPHA = {
        // r' = 0
        0f, 0f, 0f, 0f, 0f,
        // g' = 0
        0f, 0f, 0f, 0f, 0f,
        // b' = 0
        0f, 0f, 0f, 0f, 0f,
        // a' = 1 - a
        0f, 0f, 0f, -1f, 1f,
    };

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

        MemorySegment filter;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment matrix = a.allocate(ValueLayout.JAVA_FLOAT, INVERT_ALPHA.length);
            for (int i = 0; i < INVERT_ALPHA.length; i++) {
                matrix.setAtIndex(ValueLayout.JAVA_FLOAT, i, INVERT_ALPHA[i]);
            }
            filter = NativeBridge.filterColorMatrix(matrix);
        }
        if (filter == null || filter.equals(MemorySegment.NULL)) {
            return new ImageData(fctx, dst, dstBounds);
        }

        try {
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
        } finally {
            NativeBridge.filterDestroy(filter);
        }
        return new ImageData(fctx, dst, dstBounds);
    }
}
