/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.Graphics;
import com.sun.prism.skia.SkiaGraphics;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.EffectPeer;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.prism.PrEffectHelper;
import com.sun.scenario.effect.impl.state.RenderState;

/**
 * Skia peer for every {@code "Blend_<MODE>"} dispatch name.
 *
 * <p>{@link com.sun.scenario.effect.Blend.Mode} has 19 variants;
 * Skia's {@link android.graphics.BlendMode}/{@code SkBlendMode}
 * covers the standard 15 directly and the rest via close equivalents
 * (or fall back to {@code SRC_OVER} for the JFX-only channel-isolation
 * modes that don't map cleanly).</p>
 *
 * <p>Implementation: draw bottom into the destination with normal
 * SRC_OVER, then flip the surface's sticky blend mode to the
 * target {@code SkBlendMode} for the top draw. Both draws go through
 * the existing {@link PrEffectHelper#renderImageData} so transforms
 * and bounds work correctly.</p>
 */
public final class SkiaBlendPeer extends EffectPeer<RenderState> {

    public SkiaBlendPeer(FilterContext fctx, Renderer r, String name) {
        super(fctx, r, name);
    }

    /** Map JFX Blend.Mode → SkBlendMode int. */
    private static int skBlendMode(String peerName) {
        // peerName: "Blend_SRC_OVER", "Blend_ADD", ...
        String mode = peerName.startsWith("Blend_")
            ? peerName.substring("Blend_".length()) : peerName;
        return switch (mode) {
            case "SRC_OVER"    -> 3;   // kSrcOver
            case "SRC_IN"      -> 5;   // kSrcIn
            case "SRC_OUT"     -> 7;   // kSrcOut
            case "SRC_ATOP"    -> 9;   // kSrcATop
            case "ADD"         -> 12;  // kPlus
            case "MULTIPLY"    -> 24;  // kMultiply (true alpha-respecting multiply)
            case "SCREEN"      -> 14;  // kScreen
            case "OVERLAY"     -> 15;  // kOverlay
            case "DARKEN"      -> 16;  // kDarken
            case "LIGHTEN"     -> 17;  // kLighten
            case "COLOR_DODGE" -> 18;  // kColorDodge
            case "COLOR_BURN"  -> 19;  // kColorBurn
            case "HARD_LIGHT"  -> 20;  // kHardLight
            case "SOFT_LIGHT"  -> 21;  // kSoftLight
            case "DIFFERENCE"  -> 22;  // kDifference
            case "EXCLUSION"   -> 23;  // kExclusion
            // JFX channel-isolation modes (RED/GREEN/BLUE) don't have
            // direct SkBlendMode equivalents; fall back to SRC_OVER
            // for visual correctness on the alpha and most cases. A
            // proper implementation would chain a ColorMatrix isolating
            // the channel + a SRC_OVER blend.
            default -> 3; // kSrcOver
        };
    }

    @Override
    public ImageData filter(Effect effect,
                            RenderState rstate,
                            BaseTransform transform,
                            Rectangle outputClip,
                            ImageData... inputs) {
        final FilterContext fctx = getFilterContext();

        // Drag-resize bypass — preserve bottom input (the more
        // commonly-visible layer); top is added back the moment the
        // drag ends.
        if (SkiaEffectRenderer.shouldBypassForDrag()
                && inputs != null && inputs.length > 0 && inputs[0] != null) {
            SkiaEffectRenderer.BYPASS_COUNT.incrementAndGet();
            inputs[0].addref();
            return inputs[0];
        }

        if (inputs == null || inputs.length < 2
                || inputs[0] == null || inputs[1] == null) {
            // Defensive: shouldn't normally happen — Blend always has two.
            if (inputs != null && inputs.length > 0 && inputs[0] != null) {
                inputs[0].addref();
                return inputs[0];
            }
            return new ImageData(fctx, null, new Rectangle());
        }

        ImageData bottom = inputs[0];
        ImageData top = inputs[1];

        Rectangle bottomBounds = bottom.getTransformedBounds(null);
        Rectangle topBounds = top.getTransformedBounds(null);
        Rectangle dstBounds = new Rectangle(bottomBounds);
        dstBounds.add(topBounds);
        dstBounds.intersectWith(outputClip);
        if (dstBounds.width <= 0 || dstBounds.height <= 0) {
            return new ImageData(fctx, null, dstBounds);
        }

        if (!bottom.validate(fctx) || !top.validate(fctx)) {
            return new ImageData(fctx, null, dstBounds);
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
        // NOTE: do NOT translate gdst. PrEffectHelper.renderImageData draws into
        // the compatible image at its local origin (0,0)->(w,h) and uses the
        // dstBounds arg only to compute the source-sampling offset. The
        // destination image origin already corresponds to dstBounds.x/y, so a
        // pre-translate would shift the composite off the top-left (the
        // documented "grey ghost" off-origin bug). Pass dstBounds — not the
        // per-input source bounds — to both draws, matching every other peer.

        if (dst.getSurfaceHandle() == 0L) {
            return new ImageData(fctx, dst, dstBounds);
        }

        // Draw bottom with the default SRC_OVER (override -1).
        PrEffectHelper.renderImageData(gdst, bottom, dstBounds);

        // Composite top with the target SkBlendMode. We must NOT set the
        // surface's sticky blend mode directly (surfaceSetBlendMode): the
        // very next draw routes through SkiaGraphics.syncBeforeDraw ->
        // surfaceBeginDraw, which always rewrites the blend arg and would
        // clobber it back to SRC_OVER before the top texture is drawn —
        // flooding the whole node (the InnerShadow "solid box"). Routing the
        // mode through the Graphics' per-draw blend override makes every
        // syncBeforeDraw of this top input use it.
        int mode = skBlendMode(getUniqueName());
        SkiaGraphics sg = (SkiaGraphics) gdst;
        sg.setPeerBlendOverride(mode);
        try {
            PrEffectHelper.renderImageData(gdst, top, dstBounds);
        } finally {
            sg.setPeerBlendOverride(-1);
        }

        return new ImageData(fctx, dst, dstBounds);
    }
}
