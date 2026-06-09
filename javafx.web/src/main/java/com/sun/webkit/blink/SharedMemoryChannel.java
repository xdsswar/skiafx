/*
 * Copyright (c) 2026, skia-fx. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The skia-fx project
 * designates this particular file as subject to the "Classpath" exception
 * as provided in the LICENSE file that accompanied this code.
 */
package com.sun.webkit.blink;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.lang.ref.Cleaner;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Memory-mapped shared-memory IPC channel between the JVM and one
 * {@code skia-fx-webview} engine process. One channel per page/window.
 *
 * <p>Owns a temp file, a {@link MemorySegment} mapping it, and the
 * {@link Arena} controlling that mapping's lifetime. The arena is
 * {@link Arena#ofShared()} so the segment is reachable from the FX writer
 * thread, the {@link EventPump} reader thread, and the {@link HeartbeatWriter}.
 *
 * <h2>Memory safety / lifecycle</h2>
 * Implements {@link AutoCloseable} <i>and</i> registers a {@link Cleaner}
 * backstop (static action — captures only the arena/path, never {@code this})
 * so a dropped channel still unmaps and deletes its file even if {@code close()}
 * is missed. {@code close()} is idempotent; after it, any segment access throws
 * {@link IllegalStateException}. The caller must quiesce ring readers/writers
 * before closing (see {@link BlinkPage} teardown order).
 *
 * <p>Derived from {@code jux-framework-blink} (same author). Internal.
 */
final class SharedMemoryChannel implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    // Release/acquire VarHandle for the M13 frame-slot handshake field in the
    // header (aligned int at OFF_FRAME_READING_SLOT). The release store pairs with
    // the engine's acquire load (jux_ipc.cc ReadFrameReadingSlot).
    private static final VarHandle FRAME_READING_SLOT =
        ValueLayout.JAVA_INT.varHandle();

    private final MemorySegment segment;
    private final Path path;
    private final Cleaner.Cleanable cleanable;
    private volatile boolean closed;

    private SharedMemoryChannel(Arena arena, MemorySegment segment, Path path, boolean ownsFile) {
        this.segment = segment;
        this.path = path;
        this.cleanable = CLEANER.register(this, cleanup(arena, path, ownsFile));
    }

    /** Static cleanup action — must not capture {@code this}. */
    private static Runnable cleanup(Arena arena, Path path, boolean ownsFile) {
        return () -> {
            try {
                arena.close();
            } catch (Throwable ignore) {
                // best-effort unmap
            }
            if (ownsFile) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignore) {
                    // best-effort; OS temp cleanup reclaims orphans
                }
            }
        };
    }

    /** Creates and initializes a channel of the default size. */
    static SharedMemoryChannel create(int windowId) throws IOException {
        return create(windowId, MemoryLayout.DEFAULT_SIZE);
    }

    /**
     * Creates a temp file of {@code totalSize} bytes, maps it, and writes the
     * header + ring-buffer headers. The OS zero-fills the file, so positions
     * start at 0 (empty) and engine state at {@code ENGINE_STARTING}.
     */
    static SharedMemoryChannel create(int windowId, long totalSize) throws IOException {
        long minimum = MemoryLayout.HEADER_SIZE + MemoryLayout.HEARTBEAT_SIZE
            + MemoryLayout.RB_SLOTS_START * 2L + MemoryLayout.SLOT_SIZE * 2L;
        if (totalSize < minimum) {
            throw new IllegalArgumentException(
                "totalSize (" + totalSize + ") too small; minimum " + minimum);
        }
        Path filePath = Files.createTempFile("skia-fx-webview-" + windowId + "-", ".mem");
        Arena arena = null;
        try {
            arena = Arena.ofShared();
            MemorySegment mapped;
            try (FileChannel fc = FileChannel.open(filePath,
                    StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                mapped = fc.map(FileChannel.MapMode.READ_WRITE, 0, totalSize, arena);
            }

            long offset = MemoryLayout.HEADER_SIZE + MemoryLayout.HEARTBEAT_SIZE;
            long cmdBufOffset = offset;
            long cmdBufSize = MemoryLayout.CMD_BUF_SIZE;
            offset += cmdBufSize;
            long evtBufOffset = offset;
            long evtBufSize = MemoryLayout.EVT_BUF_SIZE;
            offset += evtBufSize;
            long journalOffset = offset;
            long journalSize = MemoryLayout.JOURNAL_SIZE;
            offset += journalSize;
            long dataOffset = offset;
            long dataSize = totalSize - offset;
            if (dataSize < 0) {
                throw new IllegalArgumentException(
                    "totalSize (" + totalSize + ") too small for default regions ("
                        + offset + " B)");
            }

            // Header.
            mapped.set(ValueLayout.JAVA_INT_UNALIGNED, MemoryLayout.OFF_MAGIC, MemoryLayout.MAGIC);
            mapped.set(ValueLayout.JAVA_INT_UNALIGNED, MemoryLayout.OFF_VERSION, MemoryLayout.VERSION);
            mapped.set(ValueLayout.JAVA_INT_UNALIGNED, MemoryLayout.OFF_WINDOW_ID, windowId);
            mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, MemoryLayout.OFF_CMD_BUF_OFFSET, cmdBufOffset);
            mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, MemoryLayout.OFF_CMD_BUF_SIZE, cmdBufSize);
            mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, MemoryLayout.OFF_EVT_BUF_OFFSET, evtBufOffset);
            mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, MemoryLayout.OFF_EVT_BUF_SIZE, evtBufSize);
            mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, MemoryLayout.OFF_JOURNAL_OFFSET, journalOffset);
            mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, MemoryLayout.OFF_JOURNAL_SIZE, journalSize);
            mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, MemoryLayout.OFF_DATA_OFFSET, dataOffset);
            mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, MemoryLayout.OFF_DATA_SIZE, dataSize);
            // M13 frame-slot handshake: -1 = the render thread is reading no slot.
            mapped.set(ValueLayout.JAVA_INT_UNALIGNED, MemoryLayout.OFF_FRAME_READING_SLOT, -1);

            // Ring headers (positions are 0 from zero-fill).
            initRingHeader(mapped.asSlice(cmdBufOffset, cmdBufSize), cmdBufSize);
            initRingHeader(mapped.asSlice(evtBufOffset, evtBufSize), evtBufSize);

            return new SharedMemoryChannel(arena, mapped, filePath, true);
        } catch (Throwable t) {
            // Partial-init failure (a header set() / initRingHeader threw). Unmap
            // the arena deterministically here instead of leaving it to a deferred
            // GC of the never-registered arena (L1). On success the returned
            // channel owns the arena, so this only runs before that. close() is
            // idempotent-safe via the try/catch.
            if (arena != null) {
                try {
                    arena.close();
                } catch (Throwable suppressed) {
                    t.addSuppressed(suppressed);
                }
            }
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException suppressed) {
                t.addSuppressed(suppressed);
            }
            throw t;
        }
    }

    private static void initRingHeader(MemorySegment ring, long ringSize) {
        long slotSpace = ringSize - MemoryLayout.RB_SLOTS_START;
        long capacity = Long.highestOneBit(slotSpace / MemoryLayout.SLOT_SIZE);
        if (capacity <= 0) {
            throw new IllegalStateException("ring region too small for any slot");
        }
        ring.set(ValueLayout.JAVA_LONG_UNALIGNED, MemoryLayout.RB_OFF_CAPACITY, capacity);
        ring.set(ValueLayout.JAVA_LONG_UNALIGNED, MemoryLayout.RB_OFF_SLOT_SIZE,
            (long) MemoryLayout.SLOT_SIZE);
    }

    // --- Region accessors -----------------------------------------------

    MemorySegment commandBuffer() {
        return regionSlice(MemoryLayout.OFF_CMD_BUF_OFFSET, MemoryLayout.OFF_CMD_BUF_SIZE);
    }

    MemorySegment eventBuffer() {
        return regionSlice(MemoryLayout.OFF_EVT_BUF_OFFSET, MemoryLayout.OFF_EVT_BUF_SIZE);
    }

    /**
     * The data region — used by the OSR path to hold the double-buffered
     * captured frame pixels. The engine maps the same bytes (see
     * {@code SharedMemoryChannel::DataBufferMut} in {@code jux_ipc.cc}).
     */
    MemorySegment dataBuffer() {
        return regionSlice(MemoryLayout.OFF_DATA_OFFSET, MemoryLayout.OFF_DATA_SIZE);
    }

    /**
     * Publishes the main frame slot the render thread is currently reading
     * ({@code -1} = none), so the engine skips that slot instead of overwriting it
     * mid-copy (M13 anti-tearing handshake). Release store; pairs with the
     * engine's acquire load. No-op once closed.
     */
    void publishReadingSlot(int slot) {
        if (closed) {
            return;
        }
        try {
            FRAME_READING_SLOT.setRelease(segment,
                (long) MemoryLayout.OFF_FRAME_READING_SLOT, slot);
        } catch (IllegalStateException closing) {
            // The shared arena was closed between the `closed` check and this
            // store (a concurrent dispose). Best-effort handshake field — drop it
            // rather than throw on the render thread. The live call sites hold the
            // frame read-lock and re-check disposed, so this only guards a race
            // with teardown.
        }
    }

    private MemorySegment regionSlice(int offField, int sizeField) {
        long off = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offField);
        long size = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, sizeField);
        // Validate the region lies within the mapping (defends against a corrupt
        // header — never slice outside the mapped segment). Written overflow-safe:
        // `off + size` could wrap for huge corrupt values and slip past the guard,
        // so compare against the remaining space instead (size > byteSize - off).
        if (off < 0 || size < 0 || off > segment.byteSize()
                || size > segment.byteSize() - off) {
            throw new IllegalStateException("corrupt region header: off=" + off + " size=" + size);
        }
        return segment.asSlice(off, size);
    }

    private MemorySegment heartbeat() {
        return segment.asSlice(MemoryLayout.HEADER_SIZE, MemoryLayout.HEARTBEAT_SIZE);
    }

    // --- Heartbeat / state ----------------------------------------------

    void writeJavaHeartbeat() {
        heartbeat().set(ValueLayout.JAVA_LONG_UNALIGNED,
            MemoryLayout.OFF_JAVA_HEARTBEAT, System.nanoTime());
    }

    long readEngineHeartbeat() {
        return heartbeat().get(ValueLayout.JAVA_LONG_UNALIGNED, MemoryLayout.OFF_ENGINE_HEARTBEAT);
    }

    int readEngineState() {
        return heartbeat().get(ValueLayout.JAVA_INT_UNALIGNED, MemoryLayout.OFF_ENGINE_STATE);
    }

    void resetEngineState() {
        MemorySegment hb = heartbeat();
        hb.set(ValueLayout.JAVA_LONG_UNALIGNED, MemoryLayout.OFF_ENGINE_HEARTBEAT, 0L);
        hb.set(ValueLayout.JAVA_INT_UNALIGNED, MemoryLayout.OFF_ENGINE_STATE,
            MemoryLayout.ENGINE_STARTING);
    }

    Path getPath() {
        return path;
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        cleanable.clean(); // runs the static cleanup once (unmap + delete)
    }
}
