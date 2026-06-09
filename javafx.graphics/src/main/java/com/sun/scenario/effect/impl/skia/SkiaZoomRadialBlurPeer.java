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
import com.sun.scenario.effect.ZoomRadialBlur;
import com.sun.scenario.effect.impl.EffectPeer;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.prism.PrEffectHelper;
import com.sun.scenario.effect.impl.state.RenderState;

/**
 * Skia peer for the {@code "ZoomRadialBlur"} dispatch name.
 *
 * <p>Radial blur isn't a single Skia primitive. JFX's reference path
 * runs a multi-pass MatrixTransform (progressively scaled toward the
 * center) and accumulates via Blend.ADD. For v1, we approximate with
 * a Gaussian blur — visually softer than true radial blur but
 * structurally correct and one Skia pass.</p>
 *
 * <p>This is one of the rarer JFX effects (ZoomRadialBlur is used by
 * a handful of demos and very few real apps). The cleaner radial
 * implementation is tracked as a follow-up.</p>
 */
public final class SkiaZoomRadialBlurPeer extends EffectPeer<RenderState> {

    public SkiaZoomRadialBlurPeer(FilterContext fctx, Renderer r, String name) {
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

        // Gaussian approximation. The "radius" knob on ZoomRadialBlur is exposed
        // via getRadius() — read best-effort and fall back to a fixed sigma.
        // Computed up front because it sets the bounds padding below.
        float sigma = extractRadius(effect) * 0.5f;
        if (sigma <= 0) sigma = 4f;

        // Pad the destination by ~3σ before clipping so the blurred halo isn't
        // cropped to the source bounds (it spreads OUTSIDE the content). Same
        // convention as SkiaLinearConvolvePeer. Without this the blur is sharply
        // cut at the source edge instead of fading out. (bugs.md H2)
        final int pad = (int) Math.ceil(sigma * 3f) + 1;
        Rectangle srcBounds = srcData.getTransformedBounds(null);
        Rectangle dstBounds = new Rectangle(
            srcBounds.x - pad, srcBounds.y - pad,
            srcBounds.width  + 2 * pad,
            srcBounds.height + 2 * pad);
        dstBounds.intersectWith(outputClip);
        if (dstBounds.width <= 0 || dstBounds.height <= 0) {
            return new ImageData(fctx, null, srcBounds);
        }

        SkiaPrDrawable dst = (SkiaPrDrawable)
            getRenderer().getCompatibleImage(dstBounds.width, dstBounds.height);
        if (dst == null || !srcData.validate(fctx)) {
            return new ImageData(fctx, dst, dstBounds);
        }

        // TILE_DECAL so the kernel fades to transparent past the (now padded)
        // edge instead of smearing edge pixels outward (kClamp). With the 3σ pad
        // above, the decal edge sits beyond the real content, so the halo is
        // preserved and fades correctly. (bugs.md H2)
        MemorySegment filter = NativeBridge.filterBlur(sigma, sigma, NativeBridge.TILE_DECAL);
        if (filter == null || filter.equals(MemorySegment.NULL)) {
            srcData.addref();
            return srcData;
        }

        try {
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
        } finally {
            NativeBridge.filterDestroy(filter);
        }
        return new ImageData(fctx, dst, dstBounds);
    }

    private static float extractRadius(Effect effect) {
        // ZoomRadialBlur is the only effect dispatched to this peer, and it
        // exposes a public getRadius() — a direct cast beats reflection.
        return (effect instanceof ZoomRadialBlur z) ? z.getRadius() : 0f;
    }
}
