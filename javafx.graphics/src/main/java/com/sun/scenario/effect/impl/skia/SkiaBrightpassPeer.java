/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import java.lang.foreign.MemorySegment;

import com.sun.prism.skia.impl.NativeBridge;
import com.sun.scenario.effect.Brightpass;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.impl.Renderer;

/**
 * Skia peer for the {@code "Brightpass"} dispatch name. Mirrors upstream
 * {@code Brightpass.jsl}, a binary luminance gate on premultiplied colour:
 *
 * <pre>
 *   lum   = max(0, dot((0.2125, 0.7154, 0.0721), rgb) - a * threshold)
 *   color = val * sign(lum)
 * </pre>
 *
 * <p>i.e. a pixel is kept unchanged when its UNpremultiplied luminance is
 * above {@code threshold} and dropped to transparent otherwise. A single
 * colour matrix cannot branch, so the gate is built as a filter graph:
 * a colour matrix (which Skia evaluates on unpremultiplied colour and
 * clamps to [0,1]) produces a mask whose alpha is a very steep ramp
 * {@code clamp(K * (lum - threshold))}, and {@code Blend(DST_IN, source,
 * mask)} multiplies the untouched source by that mask alpha. With
 * {@code K = 4096} the ramp is narrower than one 8-bit step, so it is a
 * step function in practice.</p>
 *
 * <p>Mostly consumed internally by {@code Bloom} (Bloom decomposes
 * into Brightpass + GaussianBlur + Crop + Blend.ADD).</p>
 */
public final class SkiaBrightpassPeer extends SkiaColorMatrixPeerBase {

    private static final float LUM_R = 0.2125f, LUM_G = 0.7154f, LUM_B = 0.0721f;
    private static final float GATE_SLOPE = 4096f;

    public SkiaBrightpassPeer(FilterContext fctx, Renderer r, String name) {
        super(fctx, r, name);
    }

    @Override
    protected long stateKey(Effect effect) {
        return Float.floatToIntBits(((Brightpass) effect).getThreshold());
    }

    /** Mask matrix: rgb = 0, alpha = K * (lum - threshold), clamped. */
    @Override
    protected float[] buildMatrix(Effect effect) {
        final float t = ((Brightpass) effect).getThreshold();
        final float k = GATE_SLOPE;
        return new float[] {
            0f,        0f,        0f,        0f, 0f,
            0f,        0f,        0f,        0f, 0f,
            0f,        0f,        0f,        0f, 0f,
            k * LUM_R, k * LUM_G, k * LUM_B, 0f, -k * t,
        };
    }

    @Override
    protected MemorySegment createFilter(Effect effect) {
        MemorySegment mask = createColorMatrixFilter(buildMatrix(effect));
        if (mask == null) {
            return null;
        }
        // Blend(mode, background, foreground): background = null = the
        // source, foreground = the mask. DST_IN keeps source * mask.alpha.
        MemorySegment gate = NativeBridge.filterBlend(
            NativeBridge.BLEND_DST_IN, null, mask);
        NativeBridge.filterDestroy(mask);
        return (gate == null || gate.equals(MemorySegment.NULL)) ? null : gate;
    }
}
