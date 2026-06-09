package com.sun.prism.skia.impl;

import java.lang.foreign.MemorySegment;

/**
 * Java handle wrappers around native {@link SkiaImageFilters} factories.
 *
 * <p>The bridge owns the underlying {@code SkImageFilter}; Java holds
 * a {@link Handle} that disposes the native filter when closed. Apply
 * a filter to subsequent draws by calling
 * {@link com.sun.prism.skia.impl.NativeBridge#surfaceSaveLayerWithFilter}
 * before drawing and {@code surfaceRestore} after.</p>
 */
public final class SkiaImageFilters {

    public static final class Handle implements AutoCloseable {
        public final MemorySegment filter;

        Handle(MemorySegment filter) { this.filter = filter; }

        public boolean isValid() {
            return filter != null && !filter.equals(MemorySegment.NULL);
        }

        @Override public void close() {
            if (isValid()) {
                NativeBridge.filterDestroy(filter);
            }
        }
    }

    private SkiaImageFilters() {}

    /** Gaussian blur with the given sigma (per axis). */
    public static Handle blur(float sigmaX, float sigmaY) {
        return new Handle(NativeBridge.filterBlur(
            sigmaX, sigmaY, NativeBridge.TILE_DECAL));
    }

    /**
     * Drop-shadow filter. The shadow is rendered offset by (dx, dy)
     * with a Gaussian-blurred silhouette, plus the original input
     * composited on top.
     */
    public static Handle dropShadow(float dx, float dy,
                                    float sigmaX, float sigmaY,
                                    int r, int g, int b, int a) {
        return new Handle(NativeBridge.filterDropShadow(
            dx, dy, sigmaX, sigmaY, r, g, b, a));
    }

    /**
     * Color-matrix filter. {@code matrix20} is a row-major 4x5 array
     * (R', G', B', A' rows; columns are R, G, B, A, bias).
     */
    public static Handle colorMatrix(float[] matrix20) {
        if (matrix20 == null || matrix20.length != 20) {
            throw new IllegalArgumentException("matrix20 must be a length-20 row-major float[]");
        }
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            MemorySegment seg = arena.allocate((long) 20 * 4, 4);
            for (int i = 0; i < 20; i++) {
                seg.setAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT, i, matrix20[i]);
            }
            return new Handle(NativeBridge.filterColorMatrix(seg));
        }
    }

    /** {@code outer(inner(input))}. Both inputs are referenced; close them as usual. */
    public static Handle compose(Handle outer, Handle inner) {
        if (!outer.isValid() || !inner.isValid()) {
            return new Handle(MemorySegment.NULL);
        }
        return new Handle(NativeBridge.filterCompose(outer.filter, inner.filter));
    }
}
