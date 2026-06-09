/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import com.sun.glass.ui.Screen;
import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.EffectPeer;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.state.RenderState;

/**
 * Fallback peer that returns the input image unchanged. Used by
 * {@link SkiaEffectRenderer} when a Skia-backed peer for a given
 * effect name doesn't exist yet — keeps the scene paint working
 * (an exception from {@code createPeer} would otherwise abort the
 * whole frame). The visual cost is that the unimplemented effect
 * becomes a no-op until its real peer ships; the structural win is
 * that adding effects is now a drop-in operation with zero risk of
 * regressing rendering.
 *
 * <p><b>Validity invariant:</b> every {@link ImageData} this peer
 * returns wraps a non-null, renderer-compatible image (either the
 * input's own image, or a fresh 1×1 {@link SkiaPrDrawable} when no
 * input is available). A null-image {@code ImageData} would fail
 * {@link ImageData#validate} downstream, and the upstream retry loop
 * in {@code PrEffectHelper.render} would spin until externally
 * killed (observed at 250 000+ surface_begin_draw calls/sec during a
 * maximize→restore window-state change). Returning a valid 1×1
 * drawable lets the retry loop exit on the first attempt with the
 * effect a visual no-op for this pulse, which is benign.</p>
 */
public final class SkiaPassthroughPeer extends EffectPeer<RenderState> {

    public SkiaPassthroughPeer(FilterContext fctx, Renderer r, String name) {
        super(fctx, r, name);
    }

    @Override
    public ImageData filter(Effect effect,
                            RenderState rstate,
                            BaseTransform transform,
                            Rectangle outputClip,
                            ImageData... inputs) {
        if (inputs == null || inputs.length == 0 || inputs[0] == null
            || inputs[0].getUntransformedImage() == null) {
            // No usable input — produce a valid 1×1 SkiaPrDrawable so
            // ImageData.validate() returns true. The Effect chain's
            // downstream peers treat this as a transparent no-op
            // contribution. See class-level Javadoc for the
            // PrEffectHelper retry-loop interaction.
            //
            // Allocate THROUGH the pool: downstream releaseCompatibleImage ->
            // ImagePool.checkIn only balances pooled (locked) drawables, so a
            // raw SkiaPrDrawable.create here is silently dropped on check-in and
            // its native SkSurface leaks. (bugs.md M6)
            SkiaPrDrawable empty = (SkiaPrDrawable) getRenderer().getCompatibleImage(1, 1);
            if (empty == null) {
                // Rare transient pool/OOM failure. Fall back to a direct 1×1 so we
                // never return a null-image ImageData (which would spin the
                // PrEffectHelper retry loop — see class Javadoc). Not pool-tracked,
                // but this path is rare and a one-pulse miss is benign.
                empty = SkiaPrDrawable.create(Screen.getMainScreen(), 1, 1);
            }
            return new ImageData(getFilterContext(), empty,
                new Rectangle(0, 0, 1, 1));
        }
        // Return the first input untouched. addref() so the caller's
        // ref-count bookkeeping works regardless of whether the
        // peer produced a fresh ImageData or reused one.
        inputs[0].addref();
        return inputs[0];
    }
}
