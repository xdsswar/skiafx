package com.sun.prism.skia.impl;

import com.sun.javafx.geom.Path2D;
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
        if (shape instanceof Path2D path) {
            return encodePath2D(path, dx, dy);
        }
        // Pass 1: count.
        PathIterator pass1 = shape.getPathIterator(null);
        int fillRule = pass1.getWindingRule();
        int verbCount = 0;
        int coordCount = 0;
        float[] scratch = SCRATCH.get();
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
        if (shape instanceof Path2D path) {
            return encodePath2D(path, 0f, 0f);
        }
        // Pass 1: count verbs + coords.
        PathIterator pass1 = shape.getPathIterator(null);
        int fillRule = pass1.getWindingRule();
        int verbCount = 0;
        int coordCount = 0;
        float[] scratch = SCRATCH.get();
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

    /**
     * Fast path for {@link Path2D} (glyph outlines, SVGPath, Path, stroked
     * outlines): its command bytes are already PathIterator SEG_* values and
     * its coords are a flat float array, so copy them straight into the arena
     * instead of walking two PathIterators.
     */
    private static Encoded encodePath2D(Path2D path, float dx, float dy) {
        int verbCount = path.getNumCommands();
        byte[] cmds = path.getCommandsNoClone();
        float[] src = path.getFloatCoordsNoClone();
        int coordCount = 0;
        for (int i = 0; i < verbCount; i++) {
            coordCount += coordsForVerb(cmds[i]);
        }
        FrameArena.Lease lease = FrameArena.current().open();
        MemorySegment verbs  = lease.allocateBytes(verbCount);
        MemorySegment coords = lease.allocateFloats(coordCount);
        if (verbCount > 0) {
            MemorySegment.copy(cmds, 0, verbs, ValueLayout.JAVA_BYTE, 0L, verbCount);
        }
        if (coordCount > 0) {
            if (dx == 0f && dy == 0f) {
                MemorySegment.copy(src, 0, coords, ValueLayout.JAVA_FLOAT, 0L, coordCount);
            } else {
                for (int k = 0; k < coordCount; k += 2) {
                    coords.setAtIndex(ValueLayout.JAVA_FLOAT, k,     src[k]     + dx);
                    coords.setAtIndex(ValueLayout.JAVA_FLOAT, k + 1, src[k + 1] + dy);
                }
            }
        }
        return new Encoded(lease, verbs, coords, verbCount, coordCount, path.getWindingRule());
    }

    /** Per-thread segment scratch for the generic PathIterator path. */
    private static final ThreadLocal<float[]> SCRATCH =
        ThreadLocal.withInitial(() -> new float[6]);

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
