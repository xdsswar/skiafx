/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.impl.Renderer;

/**
 * Skia peer for the {@code "SepiaTone"} dispatch name. JFX's
 * {@link com.sun.scenario.effect.SepiaTone} applies a sepia-tone
 * recolouring with no per-instance parameters — the classic
 * filter-effects matrix.
 */
public final class SkiaSepiaTonePeer extends SkiaColorMatrixPeerBase {

    /** Standard sepia 4×5 colour matrix (W3C filter-effects spec). */
    private static final float[] SEPIA = {
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f,     0f,     0f,     1f, 0f,
    };

    public SkiaSepiaTonePeer(FilterContext fctx, Renderer r, String name) {
        super(fctx, r, name);
    }

    @Override protected long  stateKey(Effect effect)    { return 0L; /* singleton */ }
    @Override protected float[] buildMatrix(Effect effect) { return SEPIA;             }
}
