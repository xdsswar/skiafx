/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import java.lang.foreign.MemorySegment;

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.scenario.effect.Color4f;
import com.sun.scenario.effect.DropShadow;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.EffectPeer;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.state.RenderState;

/**
 * Skia-native peer for {@link DropShadow}.
 *
 * <p>Uses {@code SkImageFilters::DropShadow} via the
 * {@code openjfx_skia_filter_create_drop_shadow} bridge. The input
 * surface is drawn into a {@code saveLayerWithFilter} on the output
 * surface; Skia/Ganesh runs the blur + offset + color tint as one or
 * two GPU passes and composites the result into the output. No
 * GPU→CPU readback.</p>
 *
 * <p>JFX parameters that don't map 1:1 to Skia's drop-shadow filter:</p>
 * <ul>
 *   <li><b>spread</b> — JFX expands the alpha mask before blurring.
 *       Skia has no direct knob; we ignore it for v1. CSS dropshadows
 *       in Modena don't use spread.</li>
 *   <li><b>blurType</b> — JFX supports GAUSSIAN /
 *       {ONE,TWO,THREE}_PASS_BOX. Skia's drop-shadow filter is
 *       Gaussian-only. We always render Gaussian; box variants get
 *       the same look (close enough visually).</li>
 * </ul>
 */
public final class SkiaDropShadowPeer extends EffectPeer<RenderState> {

    public SkiaDropShadowPeer(FilterContext fctx, Renderer r, String name) {
        super(fctx, r, name);
    }

    @Override
    public ImageData filter(Effect effect,
                            RenderState rstate,
                            BaseTransform transform,
                            Rectangle outputClip,
                            ImageData... inputs) {
        final DropShadow ds = (DropShadow) effect;
        final ImageData srcData = inputs[0];
        final FilterContext fctx = getFilterContext();
        final Rectangle srcBounds = srcData.getTransformedBounds(null);

        // Skia's drop-shadow filter expands the rendered region by
        // the blur sigma and the offset on each side. Account for
        // both so the shadow doesn't get cropped.
        final float sigma = (float) (ds.getRadius() / 3.0);  // JFX radius → Skia sigma
        final int padX = (int) Math.ceil(sigma * 3.0 + Math.abs(ds.getOffsetX()));
        final int padY = (int) Math.ceil(sigma * 3.0 + Math.abs(ds.getOffsetY()));
        Rectangle dstBounds = new Rectangle(
            srcBounds.x - padX,
            srcBounds.y - padY,
            srcBounds.width  + 2 * padX,
            srcBounds.height + 2 * padY);
        dstBounds.intersectWith(outputClip);
        if (dstBounds.width <= 0 || dstBounds.height <= 0) {
            return new ImageData(fctx, null, srcBounds);
        }

        SkiaPrDrawable dst = (SkiaPrDrawable)
            getRenderer().getCompatibleImage(dstBounds.width, dstBounds.height);
        if (dst == null || !srcData.validate(fctx)) {
            return new ImageData(fctx, dst, dstBounds);
        }

        // Drop-shadow filter: input is implicit (the layer source).
        final Color4f c = ds.getColor();
        final int r = (int) (c.getRed()   * 255f + 0.5f);
        final int g = (int) (c.getGreen() * 255f + 0.5f);
        final int b = (int) (c.getBlue()  * 255f + 0.5f);
        final int a = (int) (c.getAlpha() * 255f + 0.5f);
        MemorySegment filter = NativeBridge.filterDropShadow(
            (float) ds.getOffsetX(), (float) ds.getOffsetY(),
            sigma, sigma,
            r, g, b, a);
        if (filter == null || filter.equals(MemorySegment.NULL)) {
            return new ImageData(fctx, dst, dstBounds);
        }

        try {
            // Render: create a Graphics on the destination, position the
            // source so that (srcBounds.x, srcBounds.y) maps to the
            // destination origin offset by the bounds delta, then issue
            // a save-layer-with-filter, draw the input image, restore.
            // Skia composites the drop-shadow + the source into the dst.
            com.sun.prism.Graphics gdst = dst.createGraphics();
            if (gdst == null) {
                return new ImageData(fctx, dst, dstBounds);
            }
            final long dstHandle = dst.getSurfaceHandle();
            if (dstHandle == 0L) {
                return new ImageData(fctx, dst, dstBounds);
            }
            MemorySegment dstSeg = MemorySegment.ofAddress(dstHandle);
            NativeBridge.surfaceSaveLayerWithFilter(dstSeg, filter);
            try {
                // Draw the input through the layer. Use the existing
                // PrEffectHelper.renderImageData which honours the
                // input's transform via the destination Graphics.
                com.sun.scenario.effect.impl.prism.PrEffectHelper
                    .renderImageData(gdst, srcData, dstBounds);
            } finally {
                NativeBridge.surfaceRestore(dstSeg);
            }
        } finally {
            NativeBridge.filterDestroy(filter);
        }
        return new ImageData(fctx, dst, dstBounds);
    }
}
