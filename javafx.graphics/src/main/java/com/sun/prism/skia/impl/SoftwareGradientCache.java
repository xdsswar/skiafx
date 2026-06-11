/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.prism.skia.impl;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.paint.Gradient;
import com.sun.prism.paint.LinearGradient;
import com.sun.prism.paint.RadialGradient;
import com.sun.prism.paint.Stop;

/**
 * Software-tier cache of large gradient fills, rendered once to an SkImage
 * and composited per frame as a 1:1 sprite blit.
 *
 * <p><b>Why:</b> Skia's CPU raster pipeline executes gradient shading in
 * scalar code under MSVC (the pipeline's SIMD kernels require clang vector
 * extensions), measured at ~120&nbsp;ns/px — a full-window gradient fill
 * costs ~80&nbsp;ms, which alone destroys the software tier's frame budget.
 * N32 sprite blits use Skia's dedicated blitters and stay fast (~0.4&nbsp;ns/px),
 * so rasterizing a repeated gradient once and blitting it each frame is a
 * ~200x win. Region/CSS backgrounds (Modena, AtlantaFX) re-issue identical
 * gradient fills every frame whenever anything animates above them, which is
 * exactly the shape this cache hits.</p>
 *
 * <p><b>Pixel parity:</b> the cached image is produced by the same native
 * shader fill the direct path would issue, rendered at device resolution
 * under the same pixel scale, and composited with the surface's current
 * blend/extra-alpha state. The cache only engages when the composite is a
 * pure integral device-space translation (no rotation/skew/scale beyond the
 * pixel scale, integral device position and size); every other case falls
 * back to the direct fill, so output is bit-identical by construction.</p>
 *
 * <p><b>Bounds:</b> entries are LRU-evicted above a byte budget
 * ({@code -Dskia.cpu.gradientCacheMB}, default 32). Eviction destroys the
 * native image immediately on the render thread. The whole cache is
 * render-thread-confined — no locking, no cross-thread native destroys.</p>
 *
 * <p>GPU tiers never reach this class: callers gate on
 * {@link SkiaGpu#isResolvedSoftware()} (a cached volatile read).</p>
 */
public final class SoftwareGradientCache {

    private static final boolean ENABLED =
        !"false".equals(System.getProperty("skia.cpu.gradientCache"));

    /** Byte budget for cached gradient images (MB). */
    private static final long BUDGET_BYTES =
        Math.max(1, Integer.getInteger("skia.cpu.gradientCacheMB", 32)) * 1024L * 1024L;

    /** Minimum fill size (device px) worth caching: below this the direct
     *  scalar fill is cheap enough (~120 ns/px → ~2 ms at 16k px). */
    private static final int MIN_AREA_PX =
        Integer.getInteger("skia.cpu.gradientCacheMinPx", 16_384);

    /** Max gradient stops a key encodes; wilder gradients go direct. */
    private static final int MAX_STOPS = 16;

    /** Device-space positions/sizes must be integral within this epsilon for
     *  the 1:1 blit to be bit-identical to the direct fill. */
    private static final float INT_EPS = 1f / 256f;

    private SoftwareGradientCache() {}

    // ---- cache state (render-thread-confined) -----------------------------

    private record Entry(MemorySegment image, long bytes) {}

    /** Access-ordered LRU; values own native SkImage handles. */
    private static final LinkedHashMap<Key, Entry> CACHE =
        new LinkedHashMap<>(32, 0.75f, true);
    private static long cacheBytes;

    private static boolean loggedActive;

    // One-shot per-reason bail diagnostics, -Dskia.verbose only. Tells us
    // WHY the cache never engages on a scene that should hit it.
    private static final boolean VERBOSE = Boolean.getBoolean("skia.verbose");
    private static int loggedBails;

    private static boolean bail(int reason, String what) {
        if (VERBOSE && (loggedBails & (1 << reason)) == 0) {
            loggedBails |= (1 << reason);
            System.err.println("[skia.gradcache] bail: " + what);
        }
        return false;
    }

    /**
     * Attempts to satisfy a gradient rect / round-rect fill from the cache.
     * Must be called between {@code surfaceBeginDraw} and
     * {@code surfaceEndDraw} on the render thread, with the surface's clip,
     * blend and extra-alpha state already applied.
     *
     * @return {@code true} if the fill was composited (caller is done);
     *         {@code false} to fall through to the direct shader fill.
     */
    public static boolean tryFill(MemorySegment targetSurface,
                                  BaseTransform t, float psx, float psy,
                                  Gradient g,
                                  float x, float y, float w, float h,
                                  float arcW, float arcH) {
        if (!ENABLED || w <= 0 || h <= 0) return false;
        try {
            // Pure (logical) translation only — anything else changes the
            // rasterization and must use the direct path.
            if (!t.isTranslateOrIdentity()) {
                return bail(0, "non-translate transform " + t);
            }

            float devXf = (float) ((t.getMxt() + x) * psx);
            float devYf = (float) ((t.getMyt() + y) * psy);
            float devWf = w * psx;
            float devHf = h * psy;
            int devX = Math.round(devXf);
            int devY = Math.round(devYf);
            int devW = Math.round(devWf);
            int devH = Math.round(devHf);
            if (Math.abs(devXf - devX) > INT_EPS
                    || Math.abs(devYf - devY) > INT_EPS
                    || Math.abs(devWf - devW) > INT_EPS
                    || Math.abs(devHf - devH) > INT_EPS) {
                return bail(1, "fractional device rect "
                    + devXf + "," + devYf + " " + devWf + "x" + devHf);
            }
            if (devW <= 0 || devH <= 0
                    || (long) devW * devH < MIN_AREA_PX) {
                return bail(2, "below min area " + devW + "x" + devH);
            }

            List<Stop> stops = g.getStops();
            int n = stops.size();
            if (n < 2 || n > MAX_STOPS) return bail(3, "stop count " + n);

            Key key = Key.of(g, stops, x, y, devW, devH,
                             arcW * psx, arcH * psy, psx, psy);
            if (key == null) return bail(4, "unsupported gradient type "
                + g.getClass().getSimpleName());

            Entry e = CACHE.get(key);
            if (e == null) {
                e = render(g, x, y, w, h, arcW, arcH, devW, devH, psx, psy);
                if (e == null) return bail(5, "native render failed");
                CACHE.put(key, e);
                cacheBytes += e.bytes();
                evictOverBudget();
                if (!loggedActive && Boolean.getBoolean("skia.verbose")) {
                    loggedActive = true;
                    System.err.println("[skia.gradcache] software gradient "
                        + "cache active (budget " + (BUDGET_BYTES >> 20) + " MB)");
                }
            }

            // Composite 1:1 in device space. The begin-draw save scope
            // restores the matrix afterwards; the clip (already applied in
            // device space) is unaffected by setMatrix.
            NativeBridge.surfaceSetMatrix(targetSurface, 1, 0, 0, 0, 1, 0);
            return NativeBridge.surfaceDrawImage(
                targetSurface, e.image(), devX, devY, devW, devH) == 0;
        } catch (Throwable th) {
            // Any failure degrades to the direct fill — never break paint.
            return bail(6, "exception " + th);
        }
    }

    /** Render the gradient once at device resolution; returns null on any
     *  native failure (caller falls back to the direct fill). */
    private static Entry render(Gradient g,
                                float x, float y, float w, float h,
                                float arcW, float arcH,
                                int devW, int devH, float psx, float psy) {
        MemorySegment scratch = NativeBridge.surfaceCreateRaster(devW, devH);
        if (scratch == null || scratch.equals(MemorySegment.NULL)) return null;
        MemorySegment img = null;
        try {
            // User point (x,y) → scratch (0,0), at the window's pixel scale,
            // so the shader (user-space geometry) rasterizes exactly as the
            // direct fill would. SkSurfaces::Raster zero-fills, so SRC_OVER
            // onto it yields the plain premultiplied gradient.
            NativeBridge.surfaceBeginDraw(scratch,
                psx, 0, -x * psx, 0, psy, -y * psy,
                0, 0, 0, 0, false,
                NativeBridge.BLEND_SRC_OVER, 1f);
            try (SkiaShaders.Handle s = SkiaShaders.forGradient(g)) {
                if (!s.isValid()) return null;
                int rc = (arcW > 0 || arcH > 0)
                    ? NativeBridge.surfaceFillRoundRectShader(
                        scratch, x, y, w, h, arcW, arcH, s.shader, 0xFF)
                    : NativeBridge.surfaceFillRectShader(
                        scratch, x, y, w, h, s.shader, 0xFF);
                if (rc != 0) return null;
            } finally {
                NativeBridge.surfaceEndDraw(scratch);
            }
            img = NativeBridge.surfaceSnapshotToImage(scratch);
            if (img == null || img.equals(MemorySegment.NULL)) return null;
            return new Entry(img, (long) devW * devH * 4);
        } finally {
            NativeBridge.surfaceDestroy(scratch);
        }
    }

    private static void evictOverBudget() {
        if (cacheBytes <= BUDGET_BYTES) return;
        Iterator<Map.Entry<Key, Entry>> it = CACHE.entrySet().iterator();
        while (cacheBytes > BUDGET_BYTES && it.hasNext()) {
            Entry victim = it.next().getValue();
            it.remove();
            cacheBytes -= victim.bytes();
            NativeBridge.imageDestroy(victim.image());
        }
    }

    // ---- key ---------------------------------------------------------------

    /**
     * Value key for one cached fill: gradient geometry relative to the fill
     * rect's origin (so a node translating across the scene keeps hitting
     * the same entry), stop colors/offsets, spread, device size, rounded-arc
     * size and pixel scale.
     */
    private static final class Key {
        private final float[] f;
        private final int[] colors;
        private final int hash;

        private Key(float[] f, int[] colors) {
            this.f = f;
            this.colors = colors;
            int h = 1;
            for (float v : f) h = 31 * h + Float.floatToIntBits(v);
            for (int c : colors) h = 31 * h + c;
            this.hash = h;
        }

        static Key of(Gradient g, List<Stop> stops,
                      float x, float y, int devW, int devH,
                      float devArcW, float devArcH, float psx, float psy) {
            int n = stops.size();
            float[] f = new float[11 + n];
            int[] colors = new int[n];
            int i = 0;
            if (g instanceof LinearGradient lg) {
                f[i++] = 1f;
                f[i++] = lg.getX1() - x;
                f[i++] = lg.getY1() - y;
                f[i++] = lg.getX2() - x;
                f[i++] = lg.getY2() - y;
            } else if (g instanceof RadialGradient rg) {
                f[i++] = 2f;
                f[i++] = rg.getCenterX() - x;
                f[i++] = rg.getCenterY() - y;
                f[i++] = rg.getRadius();
                f[i++] = rg.getFocusAngle() * 4096f + rg.getFocusDistance();
            } else {
                return null;
            }
            f[i++] = g.getSpreadMethod();
            f[i++] = devW;
            f[i++] = devH;
            f[i++] = devArcW;
            f[i++] = devArcH;
            f[i++] = psx * 4096f + psy;
            for (int s = 0; s < n; s++) {
                Stop stop = stops.get(s);
                f[i++] = stop.getOffset();
                colors[s] = pack(stop.getColor());
            }
            return new Key(f, colors);
        }

        private static int pack(com.sun.prism.paint.Color c) {
            return (c8(c.getAlpha()) << 24) | (c8(c.getRed()) << 16)
                 | (c8(c.getGreen()) << 8) | c8(c.getBlue());
        }

        private static int c8(double v) {
            return v <= 0 ? 0 : v >= 1 ? 255 : (int) (v * 255 + 0.5);
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k) || k.hash != hash
                    || k.f.length != f.length
                    || k.colors.length != colors.length) return false;
            for (int i = 0; i < f.length; i++) {
                if (Float.floatToIntBits(f[i]) != Float.floatToIntBits(k.f[i])) {
                    return false;
                }
            }
            for (int i = 0; i < colors.length; i++) {
                if (colors[i] != k.colors[i]) return false;
            }
            return true;
        }
    }
}
