/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package org.openjfx.samples.perf;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import com.sun.prism.skia.impl.NativeBridge;

/**
 * Headless microbenchmark of the native CPU raster fill paths, bypassing the
 * scene graph entirely. Isolates whether the software tier's slow frames come
 * from the bridge/Skia fill throughput or from scene-graph-side overhead.
 *
 * <p>Run via {@code :samples:perf:runRasterFillBench}. Prints ms/fill and
 * ns/px for a full-surface solid fill and a full-surface 2-stop linear
 * gradient fill on a 1100x611 kN32 raster surface — the exact shape of the
 * dominant draw in the CpuDemo software-path frame.</p>
 */
public final class RasterFillBench {

    private static final int W = 1100;
    private static final int H = 611;
    private static final int WARMUP = 30;
    private static final int ITERS = 100;

    private static MemorySegment surface;

    public static void main(String[] args) {
        surface = NativeBridge.surfaceCreateRaster(W, H);
        if (surface == null || surface.equals(MemorySegment.NULL)) {
            System.err.println("raster surface creation failed");
            System.exit(1);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment positions = arena.allocateFrom(
                ValueLayout.JAVA_FLOAT, 0f, 1f);
            MemorySegment colors = arena.allocateFrom(
                ValueLayout.JAVA_INT, 0xFF1E3A2F, 0xFF14532D);
            MemorySegment shader = NativeBridge.shaderLinearGradient(
                0, 0, W, 0, 2, positions, colors, 0);
            if (shader == null || shader.equals(MemorySegment.NULL)) {
                System.err.println("gradient shader creation failed");
                System.exit(1);
            }

            bench("solid        ", () -> NativeBridge.surfaceFillRect(
                surface, 0, 0, W, H, 30, 60, 90, 255));
            bench("gradient     ", () -> NativeBridge.surfaceFillRectShader(
                surface, 0, 0, W, H, shader, 0xFF));

            // Cached-gradient strategy: rasterize the gradient ONCE into an
            // offscreen, snapshot to an SkImage, then per-frame 1:1 image
            // blit. Validates that N32 sprite blits stay on the fast path
            // under this Skia build.
            MemorySegment scratch = NativeBridge.surfaceCreateRaster(W, H);
            NativeBridge.surfaceBeginDraw(scratch, 1, 0, 0, 0, 1, 0,
                0, 0, 0, 0, false, NativeBridge.BLEND_SRC_OVER, 1f);
            NativeBridge.surfaceFillRectShader(scratch, 0, 0, W, H, shader, 0xFF);
            NativeBridge.surfaceEndDraw(scratch);
            MemorySegment cachedImg = NativeBridge.surfaceSnapshotToImage(scratch);
            NativeBridge.surfaceDestroy(scratch);
            if (cachedImg == null || cachedImg.equals(MemorySegment.NULL)) {
                System.err.println("snapshot failed");
                System.exit(1);
            }
            bench("cached-blit  ", () -> NativeBridge.surfaceDrawImage(
                surface, cachedImg, 0, 0, W, H));

            NativeBridge.imageDestroy(cachedImg);
            NativeBridge.shaderDestroy(shader);
        }
    }

    private static void bench(String label, Runnable fill) {
        for (int i = 0; i < WARMUP; i++) draw(fill);
        long t0 = System.nanoTime();
        for (int i = 0; i < ITERS; i++) draw(fill);
        long elapsed = System.nanoTime() - t0;
        double msPerFill = elapsed / 1e6 / ITERS;
        double nsPerPx = (double) elapsed / ITERS / ((long) W * H);
        System.out.printf("%s  %dx%d: %.2f ms/fill  %.1f ns/px%n",
            label, W, H, msPerFill, nsPerPx);
    }

    private static void draw(Runnable fill) {
        NativeBridge.surfaceBeginDraw(surface, 1, 0, 0, 0, 1, 0,
            0, 0, 0, 0, false, NativeBridge.BLEND_SRC_OVER, 1f);
        try {
            fill.run();
        } finally {
            NativeBridge.surfaceEndDraw(surface);
        }
    }
}
