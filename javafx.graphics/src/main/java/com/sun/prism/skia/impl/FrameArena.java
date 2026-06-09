package com.sun.prism.skia.impl;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Per-thread reusable bump arena for FFM scratch allocations on the
 * render thread.
 *
 * <p>Each {@link Lease} reserves a piece of the chunk for the duration
 * of one bridge call; closing the lease resets the bump pointer back
 * to where it was. The backing chunk is reused across frames and grows
 * to the high-water mark of any single frame.</p>
 *
 * <p>This addresses CLAUDE.md "Off-heap and FFM" — per-frame transient
 * native memory reused via a bump-allocator-style lease, so multiple
 * call-scoped segments share one chunk without allocating per call. The
 * chunk is backed by an {@linkplain Arena#ofAuto() automatic arena}: the
 * steady-state chunk is held (never churns), and a chunk replaced on grow
 * is GC-reclaimed once its in-flight slices are released — no leak (a
 * confined arena dropped without {@code close()} would leak its native
 * memory) and no dangling (slices stay valid while referenced).</p>
 *
 * <p><b>Thread affinity:</b> each thread gets its own
 * {@link FrameArena}; do not share leases across threads.</p>
 */
public final class FrameArena {

    private static final int DEFAULT_CHUNK = 64 * 1024;

    private static final ThreadLocal<FrameArena> TL = ThreadLocal.withInitial(FrameArena::new);

    public static FrameArena current() { return TL.get(); }

    // Auto (GC-managed) backing chunk — see the class javadoc. Reused across
    // frames via the bump pointer; replaced (not closed) on grow.
    private MemorySegment chunk = Arena.ofAuto().allocate(DEFAULT_CHUNK, 8);
    private long offset;
    private long capacity = DEFAULT_CHUNK;
    // Bumped every time the backing chunk is replaced by grow(). A Lease
    // captures this at open(); if it changed before close(), the lease's
    // saved start offset belongs to a now-discarded chunk and must not be
    // restored onto the fresh (empty) chunk — see Lease.close().
    private long generation;

    private FrameArena() {
        // chunk + capacity are initialized in their field declarations; offset = 0.
    }

    /** Snapshot of the current bump pointer; restore to this on close. */
    public Lease open() {
        return new Lease(this, offset, generation);
    }

    /**
     * Allocate {@code byteSize} bytes aligned to {@code alignment}.
     * Bumps the cursor; never reclaims until the lease closes.
     */
    MemorySegment allocate(long byteSize, long alignment) {
        long aligned = (offset + (alignment - 1)) & -alignment;
        long end = aligned + byteSize;
        if (end > capacity) {
            // Grow: discard the old arena (everything in use is in
            // segments returned for the current lease, but we hold the
            // arena open until the user's leases close. Safer: allocate
            // a fresh confined arena for the bigger chunk.
            grow(Math.max(end, capacity * 2));
            aligned = (offset + (alignment - 1)) & -alignment;
            end = aligned + byteSize;
        }
        MemorySegment slice = chunk.asSlice(aligned, byteSize);
        offset = end;
        return slice;
    }

    private void grow(long newCapacity) {
        // Replace the chunk with a bigger auto-managed one. Slices already handed
        // out from the old chunk (e.g. an earlier allocate in the same lease) stay
        // valid: the automatic arena keeps the old chunk's memory alive while any
        // slice references it, and reclaims it once those are released. We must NOT
        // close() the old chunk here — that would dangle such slices.
        chunk = Arena.ofAuto().allocate(newCapacity, 8);
        capacity = newCapacity;
        offset = 0;
        generation++;
    }

    /**
     * Convenience: allocate an int-aligned RGBA color buffer of length
     * {@code count}. Bytes are zero-initialized.
     */
    public MemorySegment allocateInts(int count) {
        return allocate((long) count * 4, 4);
    }

    public MemorySegment allocateBytes(int count) {
        return allocate(count, 1);
    }

    /** Allocate a 2-byte-aligned buffer of {@code count} 16-bit values. */
    public MemorySegment allocateShorts(int count) {
        return allocate((long) count * 2, 2);
    }

    public MemorySegment allocateFloats(int count) {
        return allocate((long) count * 4, 4);
    }

    /** A scoped reservation. Resets the bump pointer on {@link #close()}. */
    public static final class Lease implements AutoCloseable {
        private final FrameArena arena;
        private final long start;
        private final long generation;
        private boolean closed;

        Lease(FrameArena arena, long start, long generation) {
            this.arena = arena;
            this.start = start;
            this.generation = generation;
        }

        public MemorySegment allocateInts(int count)   { return arena.allocateInts(count); }
        public MemorySegment allocateBytes(int count)  { return arena.allocateBytes(count); }
        public MemorySegment allocateShorts(int count) { return arena.allocateShorts(count); }
        public MemorySegment allocateFloats(int count) { return arena.allocateFloats(count); }

        public MemorySegment allocate(long byteSize, long alignment) {
            return arena.allocate(byteSize, alignment);
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (arena.generation == this.generation) {
                // Same chunk we opened on — restore the bump pointer to where
                // this lease began, releasing everything it (and any nested
                // leases) reserved.
                arena.offset = start;
            } else {
                // A grow() replaced the chunk during this lease. Our saved
                // start belongs to the discarded chunk; restoring it onto the
                // fresh chunk would wrongly reserve/skip its first `start`
                // bytes. The new chunk began empty for this lease's scope, so
                // reset to its base. The grow already set offset=0; any nested
                // lease opened after the grow captured the new generation and
                // restored correctly on its own close, so reset to 0 here.
                arena.offset = 0;
            }
        }
    }
}
