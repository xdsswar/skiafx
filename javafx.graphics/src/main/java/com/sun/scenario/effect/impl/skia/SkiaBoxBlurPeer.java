/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.impl.Renderer;

/**
 * Skia peer for the {@code "BoxBlur"} dispatch name — the fast-path
 * variant JFX picks for {@link com.sun.scenario.effect.BoxBlur} when
 * the kernel is axis-aligned with no spread. Behaves identically to
 * {@link SkiaLinearConvolvePeer} as far as Skia is concerned: a box
 * blur in JFX-terms is "multi-pass Gaussian whose σ ≈ √(passes/3) ·
 * (size-1)/2", and {@code BoxRenderState.getInputKernelSize(pass)}
 * already returns the equivalent kernel half-width so the
 * {@code (N-1)/6} mapping in the parent peer produces the right σ.
 *
 * <p>The two peer classes exist solely so JFX's class-name dispatch
 * resolves both {@code "LinearConvolve"} and {@code "BoxBlur"} —
 * {@code BoxRenderState extends LinearConvolveRenderState} so the
 * filter implementation reads the same API.</p>
 */
public final class SkiaBoxBlurPeer extends SkiaLinearConvolvePeer {
    public SkiaBoxBlurPeer(FilterContext fctx, Renderer r, String name) {
        super(fctx, r, name);
    }
}
