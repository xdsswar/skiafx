/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.impl.Renderer;

/**
 * Skia peer for the {@code "BoxShadow"} dispatch name — the fast-path
 * variant JFX picks for {@link com.sun.scenario.effect.BoxShadow}
 * when the kernel is axis-aligned with no spread.
 *
 * <p>See {@link SkiaBoxBlurPeer} for why this is just a name-dispatch
 * sub-class of {@link SkiaLinearConvolveShadowPeer}.</p>
 */
public final class SkiaBoxShadowPeer extends SkiaLinearConvolveShadowPeer {
    public SkiaBoxShadowPeer(FilterContext fctx, Renderer r, String name) {
        super(fctx, r, name);
    }
}
