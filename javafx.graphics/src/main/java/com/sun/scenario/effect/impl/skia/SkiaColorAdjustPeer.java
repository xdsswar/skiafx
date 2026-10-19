/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import com.sun.scenario.effect.ColorAdjust;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.impl.Renderer;

/**
 * Skia peer for the {@code "ColorAdjust"} dispatch name. Builds a
 * single 4×5 colour matrix from JFX's hue / saturation / brightness /
 * contrast knobs and feeds it through Skia's
 * {@code SkImageFilters::ColorFilter(SkColorMatrix)}.
 *
 * <p>Upstream ({@code ColorAdjust.jsl}) works in HSB space on
 * unpremultiplied colour, in this order: contrast, hue, saturation,
 * brightness. Skia's colour matrix is also applied to unpremultiplied
 * colour, so the linear parts are reproduced exactly and the HSB parts are
 * approximated:</p>
 * <ul>
 *   <li><b>contrast</b> (exact): {@code rgb = (rgb - 0.5) * k + 0.5},
 *       {@code k = c > 0 ? 1 + 3c : 1 + c} (upstream getContrast).</li>
 *   <li><b>hue</b> (approx.): rotation about the grey axis by
 *       {@code hue * 180deg}; upstream shifts the HSB hue by
 *       {@code hue / 2} turns. Same angle, but a luminance-preserving
 *       matrix rotation rather than an HSB hexcone shift.</li>
 *   <li><b>saturation</b> (approx.): lerp to/away from BT.601 luma grey;
 *       upstream scales HSB S (i.e. lerps towards max(r,g,b)).</li>
 *   <li><b>brightness</b>: {@code b <= 0} scales rgb by {@code 1 + b},
 *       which is exactly upstream's {@code V *= 1 + b}. {@code b > 0}
 *       lerps rgb towards white by {@code b}; upstream does
 *       {@code S *= 1 - b; V += (1 - V) * b}, which this matches exactly
 *       for greys and for fully bright ({@code V = 1}) colours.</li>
 * </ul>
 * <p>Intermediate results are not clamped between stages (upstream clamps
 * S and B before converting back); only the final result is clamped.</p>
 */
public final class SkiaColorAdjustPeer extends SkiaColorMatrixPeerBase {

    public SkiaColorAdjustPeer(FilterContext fctx, Renderer r, String name) {
        super(fctx, r, name);
    }

    @Override
    protected long stateKey(Effect effect) {
        ColorAdjust c = (ColorAdjust) effect;
        // Quantise each knob to /256 for a stable cache key.
        long h = clamp(c.getHue());
        long s = clamp(c.getSaturation());
        long b = clamp(c.getBrightness());
        long k = clamp(c.getContrast());
        return (h << 48) | (s << 32) | (b << 16) | k;
    }

    private static long clamp(float v) {
        // Map [-1,1] → [0, 65535]
        int q = (int) ((v + 1f) * 32767.5f);
        return q < 0 ? 0 : (q > 65535 ? 65535 : q);
    }

    @Override
    protected float[] buildMatrix(Effect effect) {
        ColorAdjust c = (ColorAdjust) effect;
        float hue        = c.getHue();
        float saturation = c.getSaturation();
        float brightness = c.getBrightness();
        float contrast   = c.getContrast();

        // Start with identity.
        float[] m = identity();

        // Contrast (upstream ColorAdjust getContrast):
        //   k = c > 0 ? 1 + 3c : 1 + c;  rgb' = (rgb - 0.5) * k + 0.5
        float cScale  = (contrast > 0f) ? 1f + 3f * contrast : 1f + contrast;
        float cOffset = 0.5f * (1f - cScale);
        m = postMul(m, new float[] {
            cScale, 0,      0,      0, cOffset,
            0,      cScale, 0,      0, cOffset,
            0,      0,      cScale, 0, cOffset,
            0,      0,      0,      1, 0,
        });

        // Hue rotation around the (1,1,1) axis by hue*180deg (upstream adds
        // hue/2 turns to the HSB hue).
        if (hue != 0f) {
            double angle = hue * Math.PI;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            float lumR = 0.213f, lumG = 0.715f, lumB = 0.072f;
            // Rotation matrix per Filter Effects specification (hueRotate).
            float r00 = lumR + cos*(1f-lumR) - sin*lumR;
            float r01 = lumG - cos*lumG       - sin*lumG;
            float r02 = lumB - cos*lumB       + sin*(1f-lumB);
            float r10 = lumR - cos*lumR       + sin*0.143f;
            float r11 = lumG + cos*(1f-lumG) + sin*0.140f;
            float r12 = lumB - cos*lumB       - sin*0.283f;
            float r20 = lumR - cos*lumR       - sin*(1f-lumR);
            float r21 = lumG - cos*lumG       + sin*lumG;
            float r22 = lumB + cos*(1f-lumB) + sin*lumB;
            m = postMul(m, new float[] {
                r00, r01, r02, 0, 0,
                r10, r11, r12, 0, 0,
                r20, r21, r22, 0, 0,
                0,   0,   0,   1, 0,
            });
        }

        // Saturation. Lerp toward luminance grey at sat=-1, away from
        // it at sat=+1. Use ITU-R BT.601 luminance coefficients.
        // s in [0..2]: 0 = full grey, 1 = identity, 2 = double-saturated.
        float satFactor = 1f + saturation;
        float lr = 0.299f, lg = 0.587f, lb = 0.114f;
        float sInv = 1f - satFactor;
        m = postMul(m, new float[] {
            lr + satFactor*(1-lr), lg*sInv,               lb*sInv,               0, 0,
            lr*sInv,               lg + satFactor*(1-lg), lb*sInv,               0, 0,
            lr*sInv,               lg*sInv,               lb + satFactor*(1-lb), 0, 0,
            0,                     0,                     0,                     1, 0,
        });

        // Brightness: b <= 0 -> V *= 1 + b (exact: scales rgb uniformly);
        // b > 0 -> lerp towards white (exact for greys and V = 1 colours).
        float bScale  = (brightness > 0f) ? 1f - brightness : 1f + brightness;
        float bOffset = (brightness > 0f) ? brightness : 0f;
        m = postMul(m, new float[] {
            bScale, 0,      0,      0, bOffset,
            0,      bScale, 0,      0, bOffset,
            0,      0,      bScale, 0, bOffset,
            0,      0,      0,      1, 0,
        });
        return m;
    }

    // 4x5 × 4x5 matrix multiplication. Treats the affine column
    // (col 4) consistently with how Skia's SkColorMatrix interprets
    // row-major matrices: r' = m·(r,g,b,a,1).
    private static float[] postMul(float[] a, float[] b) {
        float[] out = new float[20];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 5; col++) {
                float v = 0f;
                for (int k = 0; k < 4; k++) {
                    v += b[row * 5 + k] * a[k * 5 + col];
                }
                if (col == 4) {
                    v += b[row * 5 + 4];
                }
                out[row * 5 + col] = v;
            }
        }
        return out;
    }

    private static float[] identity() {
        return new float[] {
            1, 0, 0, 0, 0,
            0, 1, 0, 0, 0,
            0, 0, 1, 0, 0,
            0, 0, 0, 1, 0,
        };
    }
}
