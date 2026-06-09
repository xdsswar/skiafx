/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import com.sun.scenario.effect.Brightpass;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.impl.Renderer;

/**
 * Skia peer for the {@code "Brightpass"} dispatch name. The
 * {@link com.sun.scenario.effect.Brightpass} effect zeros out pixels
 * below {@code threshold} and linearly remaps the rest to [0,1].
 *
 * <p>JFX's reference brightpass is luminance-aware (test luminance,
 * keep RGB). A 4×5 colour matrix can't do luminance branching, but
 * Skia's matrix clamps negatives to 0 automatically, so the
 * per-channel formula
 * {@code r' = (r - t) / (1 - t)}, similarly for g/b, gets us a close
 * visual match and is what JFX's HW path uses anyway. Pixels with all
 * three channels below {@code threshold} become black; brighter
 * pixels are linearly stretched back toward white.</p>
 *
 * <p>Mostly consumed internally by {@code Bloom} (Bloom decomposes
 * into Brightpass + GaussianBlur + Crop + Blend.ADD).</p>
 */
public final class SkiaBrightpassPeer extends SkiaColorMatrixPeerBase {

    public SkiaBrightpassPeer(FilterContext fctx, Renderer r, String name) {
        super(fctx, r, name);
    }

    @Override
    protected long stateKey(Effect effect) {
        Brightpass b = (Brightpass) effect;
        // threshold in [0, 1] → quantise to /65535
        int q = (int) (b.getThreshold() * 65535f + 0.5f);
        if (q < 0) q = 0;
        if (q > 65535) q = 65535;
        return q;
    }

    @Override
    protected float[] buildMatrix(Effect effect) {
        Brightpass b = (Brightpass) effect;
        float t = b.getThreshold();
        if (t >= 0.9999f) {
            // Degenerate — everything below 1 vanishes. Return an
            // all-zero matrix.
            return new float[20];
        }
        float scale = 1f / (1f - t);
        float bias  = -t * scale;
        return new float[] {
            scale, 0,     0,     0, bias,
            0,     scale, 0,     0, bias,
            0,     0,     scale, 0, bias,
            0,     0,     0,     1, 0,
        };
    }
}
