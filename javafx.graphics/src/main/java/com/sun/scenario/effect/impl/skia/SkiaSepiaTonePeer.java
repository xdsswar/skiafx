/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.SepiaTone;
import com.sun.scenario.effect.impl.Renderer;

/**
 * Skia peer for the {@code "SepiaTone"} dispatch name. Mirrors upstream
 * {@code SepiaTone.jsl} exactly (it is linear, so a 4x5 colour matrix is
 * faithful):
 *
 * <pre>
 *   l   = dot(rgb, (0.30, 0.59, 0.11))
 *   sep = l * (1.60, 1.20, 0.90)
 *   rgb = mix(sep, rgb, 1 - level) = level * sep + (1 - level) * rgb
 *   a   = a
 * </pre>
 *
 * <p>The shader works on premultiplied colour and Skia's matrix on
 * unpremultiplied colour; with no constant term and alpha untouched the
 * two are equivalent.</p>
 */
public final class SkiaSepiaTonePeer extends SkiaColorMatrixPeerBase {

    private static final float BW_R = 0.30f, BW_G = 0.59f, BW_B = 0.11f;
    private static final float SEP_R = 1.60f, SEP_G = 1.20f, SEP_B = 0.90f;

    public SkiaSepiaTonePeer(FilterContext fctx, Renderer r, String name) {
        super(fctx, r, name);
    }

    private static float levelOf(Effect effect) {
        float level = ((SepiaTone) effect).getLevel();
        if (!(level >= 0f)) return 0f;   // also maps NaN to 0
        return Math.min(level, 1f);
    }

    @Override
    protected long stateKey(Effect effect) {
        return Float.floatToIntBits(levelOf(effect));
    }

    @Override
    protected float[] buildMatrix(Effect effect) {
        final float lv  = levelOf(effect);
        final float inv = 1f - lv;
        return new float[] {
            lv * SEP_R * BW_R + inv, lv * SEP_R * BW_G,       lv * SEP_R * BW_B,       0f, 0f,
            lv * SEP_G * BW_R,       lv * SEP_G * BW_G + inv, lv * SEP_G * BW_B,       0f, 0f,
            lv * SEP_B * BW_R,       lv * SEP_B * BW_G,       lv * SEP_B * BW_B + inv, 0f, 0f,
            0f,                      0f,                      0f,                      1f, 0f,
        };
    }
}
