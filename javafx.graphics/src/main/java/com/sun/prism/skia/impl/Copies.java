/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.prism.skia.impl;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-frame tally of every avoidable copy in the Skia rendering path.
 *
 * <p>Lightweight in-module fork of {@code tools/copy-counter} —
 * {@code javafx.graphics} is a named JPMS module and can't read an
 * unnamed module, so the counter lives here next to the code it
 * instruments.</p>
 *
 * <p>Allocation discipline:</p>
 * <ul>
 *   <li>{@link #add} must not allocate. {@link LongAdder#add(long)} is
 *       allocation-free in the fast path.</li>
 *   <li>{@link #snapshot()} allocates a small map — call it once per
 *       frame, off the hot path (we call it from the fps logger).</li>
 * </ul>
 *
 * <p>Thread-safe: any thread may add; snapshot is called by the render
 * thread between frames.</p>
 */
public final class Copies {

    /** Categories tracked. Each one represents a copy we want to keep
     *  near zero. Adding a category is a deliberate act. */
    public enum Category {
        /** Texture upload from CPU pixels to GPU. */
        TEXTURE_UPLOAD,
        /** Glyph atlas push (rasterized glyph copied into atlas page). */
        GLYPH_ATLAS_PUSH,
        /** FBO blit / surface-to-surface copy. */
        SURFACE_BLIT,
        /** Pulse-sync field write (FX thread -> NGNode peer). */
        PULSE_SYNC_FIELD,
        /** WritableImage / PixelReader readback. */
        SNAPSHOT_READBACK,
        /** Persistent staging buffer copy (CPU -> mapped GPU memory). */
        STAGING_BUFFER_COPY,
        /** SkPicture record / replay round-trip. */
        SK_PICTURE_RECORD,
        /** MemorySegment.copy invocation. */
        MEMORY_SEGMENT_COPY,
        /** Present-path pixel copy on the READBACK tier (surface readback
         *  into the Glass {@code Pixels} buffer). Kept separate from
         *  {@link #SNAPSHOT_READBACK} so app-driven snapshots don't hide
         *  the per-frame present cost (and vice versa). */
        PRESENT_COPY,
    }

    private static final EnumMap<Category, LongAdder> COUNTERS;
    static {
        COUNTERS = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            COUNTERS.put(c, new LongAdder());
        }
    }

    private Copies() { /* no instances */ }

    /** Record one copy in {@code category}. Allocation-free. */
    public static void inc(Category category) {
        COUNTERS.get(category).increment();
    }

    /** Record {@code count} copies in {@code category}. Allocation-free. */
    public static void add(Category category, long count) {
        COUNTERS.get(category).add(count);
    }

    /** Atomic read-and-reset of every counter. Call once per frame. */
    public static Map<Category, Long> snapshot() {
        EnumMap<Category, Long> out = new EnumMap<>(Category.class);
        for (Map.Entry<Category, LongAdder> e : COUNTERS.entrySet()) {
            out.put(e.getKey(), e.getValue().sumThenReset());
        }
        return out;
    }

    /** Single-line summary suitable for a per-frame log entry. */
    public static String formatLine(Map<Category, Long> snap) {
        StringBuilder sb = new StringBuilder(160);
        boolean first = true;
        for (Map.Entry<Category, Long> e : snap.entrySet()) {
            if (e.getValue() == 0L) continue;
            if (!first) sb.append(' ');
            sb.append(e.getKey().name()).append('=').append(e.getValue());
            first = false;
        }
        if (first) sb.append("copies=0");
        return sb.toString();
    }
}
