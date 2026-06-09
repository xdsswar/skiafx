package com.sun.prism.skia.impl;

import com.sun.javafx.geom.PathIterator;
import com.sun.javafx.geom.Shape;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Serializes a JavaFX {@link Shape} into the verb / coord arrays
 * that {@link NativeBridge#surfaceFillPath} /
 * {@link NativeBridge#surfaceStrokePath} consume.
 *
 * <p><b>Allocation:</b> backed by the per-thread {@link FrameArena}
 * to avoid per-call native allocation. Caller must close the
 * {@link Encoded} to release the bump pointer.</p>
 */
public final class PathEncoder {

    public static final class Encoded implements AutoCloseable {
        private final FrameArena.Lease lease;
        public final MemorySegment verbs;
        public final MemorySegment coords;
        public final int verbCount;
        public final int coordCount;
        public final int fillRule;

        Encoded(FrameArena.Lease lease,
                MemorySegment verbs, MemorySegment coords,
                int verbCount, int coordCount, int fillRule) {
            this.lease = lease;
            this.verbs = verbs;
            this.coords = coords;
            this.verbCount = verbCount;
            this.coordCount = coordCount;
            this.fillRule = fillRule;
        }

        @Override public void close() { lease.close(); }
    }

    private PathEncoder() {}

    /**
     * Encode {@code shape} translated by ({@code dx}, {@code dy}).
     * Avoids a per-glyph save/translate/restore round-trip when
     * rendering many glyphs at known positions.
     */
    public static Encoded encodeTranslated(Shape shape, float dx, float dy) {
        // Pass 1: count.
        PathIterator pass1 = shape.getPathIterator(null);
        int fillRule = pass1.getWindingRule();
        int verbCount = 0;
        int coordCount = 0;
        float[] scratch = new float[6];
        while (!pass1.isDone()) {
            int type = pass1.currentSegment(scratch);
            verbCount++;
            coordCount += coordsForVerb(type);
            pass1.next();
        }

        FrameArena.Lease lease = FrameArena.current().open();
        MemorySegment verbs  = lease.allocateBytes(verbCount);
        MemorySegment coords = lease.allocateFloats(coordCount);

        PathIterator pass2 = shape.getPathIterator(null);
        int vi = 0;
        long ci = 0;
        while (!pass2.isDone()) {
            int type = pass2.currentSegment(scratch);
            verbs.set(ValueLayout.JAVA_BYTE, vi++, (byte) type);
            int n = coordsForVerb(type);
            // Coords come in (x, y) pairs for every verb that has any.
            for (int k = 0; k < n; k += 2) {
                coords.setAtIndex(ValueLayout.JAVA_FLOAT, ci++, scratch[k]     + dx);
                coords.setAtIndex(ValueLayout.JAVA_FLOAT, ci++, scratch[k + 1] + dy);
            }
            pass2.next();
        }
        return new Encoded(lease, verbs, coords, verbCount, coordCount, fillRule);
    }

    public static Encoded encode(Shape shape) {
        // Pass 1: count verbs + coords.
        PathIterator pass1 = shape.getPathIterator(null);
        int fillRule = pass1.getWindingRule();
        int verbCount = 0;
        int coordCount = 0;
        float[] scratch = new float[6];
        while (!pass1.isDone()) {
            int type = pass1.currentSegment(scratch);
            verbCount++;
            coordCount += coordsForVerb(type);
            pass1.next();
        }

        // Pass 2: emit verbs + coords into the per-thread frame arena.
        FrameArena.Lease lease = FrameArena.current().open();
        MemorySegment verbs  = lease.allocateBytes(verbCount);
        MemorySegment coords = lease.allocateFloats(coordCount);

        PathIterator pass2 = shape.getPathIterator(null);
        int vi = 0;
        long ci = 0;
        while (!pass2.isDone()) {
            int type = pass2.currentSegment(scratch);
            verbs.set(ValueLayout.JAVA_BYTE, vi++, (byte) type);
            int n = coordsForVerb(type);
            for (int k = 0; k < n; k++) {
                coords.setAtIndex(ValueLayout.JAVA_FLOAT, ci++, scratch[k]);
            }
            pass2.next();
        }
        return new Encoded(lease, verbs, coords, verbCount, coordCount, fillRule);
    }

    private static int coordsForVerb(int type) {
        return switch (type) {
            case PathIterator.SEG_MOVETO  -> 2;
            case PathIterator.SEG_LINETO  -> 2;
            case PathIterator.SEG_QUADTO  -> 4;
            case PathIterator.SEG_CUBICTO -> 6;
            case PathIterator.SEG_CLOSE   -> 0;
            default -> throw new IllegalStateException("Unknown PathIterator segment: " + type);
        };
    }
}
