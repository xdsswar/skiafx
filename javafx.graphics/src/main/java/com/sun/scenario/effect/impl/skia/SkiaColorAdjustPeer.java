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
 * <p>JFX semantics (each knob in [-1, +1]):</p>
 * <ul>
 *   <li><b>hue</b> — rotates RGB around the (1,1,1) luminance axis.
 *       hue=0 → identity; hue=1 → +180°.</li>
 *   <li><b>saturation</b> — blends toward grey at -1, away from grey
 *       at +1. saturation=0 → identity.</li>
 *   <li><b>brightness</b> — adds a constant to RGB.</li>
 *   <li><b>contrast</b> — scales RGB around 0.5.</li>
 * </ul>
 *
 * <p>Order of composition matters; the table below matches the JFX
 * reference behaviour: contrast → brightness → saturation → hue.</p>
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

        // Contrast: r' = (r - 0.5) * (1 + contrast) + 0.5
        //         = r * (1+contrast) + (0.5 - 0.5*(1+contrast))
        //         = r * (1+contrast) - 0.5*contrast
        float cScale  = 1f + contrast;
        float cOffset = -0.5f * contrast;
        m = postMul(m, new float[] {
            cScale, 0,      0,      0, cOffset,
            0,      cScale, 0,      0, cOffset,
            0,      0,      cScale, 0, cOffset,
            0,      0,      0,      1, 0,
        });

        // Brightness: r' = r + brightness
        m = postMul(m, new float[] {
            1, 0, 0, 0, brightness,
            0, 1, 0, 0, brightness,
            0, 0, 1, 0, brightness,
            0, 0, 0, 1, 0,
        });

        // Saturation. Lerp toward luminance gray at sat=-1, away from
        // it at sat=+1. Use ITU-R BT.601 luminance coefficients.
        // s∈[0..2]: 0 = full grey, 1 = identity, 2 = double-saturated.
        float satFactor = 1f + saturation;
        float lr = 0.299f, lg = 0.587f, lb = 0.114f;
        float sInv = 1f - satFactor;
        m = postMul(m, new float[] {
            lr + satFactor*(1-lr), lg*sInv,               lb*sInv,               0, 0,
            lr*sInv,               lg + satFactor*(1-lg), lb*sInv,               0, 0,
            lr*sInv,               lg*sInv,               lb + satFactor*(1-lb), 0, 0,
            0,                     0,                     0,                     1, 0,
        });

        // Hue rotation around the (1,1,1) axis by hue*180°.
        if (hue != 0f) {
            double angle = hue * Math.PI;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            float lumR = 0.213f, lumG = 0.715f, lumB = 0.072f;
            // Rotation matrix per Filter Effects specification (approx).
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
