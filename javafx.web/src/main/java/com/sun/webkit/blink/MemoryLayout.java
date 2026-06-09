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

/**
 * Binary layout of the shared-memory IPC region between the JVM and the
 * out-of-process {@code skia-fx-webview} (Blink) engine.
 *
 * <p>Derived from the {@code jux-framework-blink} engine's {@code jux_ipc.h}
 * (same author); every constant here <b>must</b> match that header exactly,
 * or the engine will read corrupt data. Java is the canonical source of truth
 * for the values.
 *
 * <pre>{@code
 * +--------+-----------+----------------+--------------+---------+--------+
 * | Header | Heartbeat | Command ring   | Event ring   | Journal | Data   |
 * | 256 B  | 128 B     | 4 MB           | 8 MB         | 1 MB    | rest   |
 * +--------+-----------+----------------+--------------+---------+--------+
 * }</pre>
 *
 * <p>Both ring buffers are SPSC. Their {@code writePos}/{@code readPos}
 * counters sit on separate cache lines (offsets 0 and {@value #CACHE_LINE})
 * to avoid cross-process false sharing, and — because the ring regions begin
 * at an 8-byte-aligned offset — those counters are naturally aligned, so the
 * Java side accesses them with acquire/release {@code VarHandle}s
 * (see {@link CommandRingBuffer} / {@link EventRingBuffer}).
 *
 * <p>Internal; never exported from {@code javafx.web}.
 */
final class MemoryLayout {

    private MemoryLayout() { }

    // --- Magic / version -------------------------------------------------
    /** ASCII "JUXF" — validated by both sides before any other access. */
    static final int MAGIC = 0x4A555846;
    /** Protocol version; engine refuses to attach on mismatch. */
    static final int VERSION = 1;

    // --- Region sizes ----------------------------------------------------
    static final long DEFAULT_SIZE = 16L * 1024 * 1024;
    static final int HEADER_SIZE = 256;
    static final int HEARTBEAT_SIZE = 128;
    static final int SLOT_SIZE = 256;
    static final int CACHE_LINE = 64;

    // --- Header field offsets -------------------------------------------
    static final int OFF_MAGIC = 0;
    static final int OFF_VERSION = 4;
    static final int OFF_WINDOW_ID = 8;
    static final int OFF_CMD_BUF_OFFSET = 12;
    static final int OFF_CMD_BUF_SIZE = 20;
    static final int OFF_EVT_BUF_OFFSET = 28;
    static final int OFF_EVT_BUF_SIZE = 36;
    static final int OFF_JOURNAL_OFFSET = 44;
    static final int OFF_JOURNAL_SIZE = 52;
    static final int OFF_DATA_OFFSET = 60;
    static final int OFF_DATA_SIZE = 68;
    // Frame-slot handshake (M13): the render thread publishes the main data-region
    // frame slot it is currently reading (-1 = none) so the engine skips that slot
    // instead of overwriting it mid-copy (tearing under render stall). Lives in the
    // otherwise-unused tail of the 256-byte header, cache-line aligned. Release/
    // acquire atomic int; mirrored as kOffFrameReadingSlot in jux_ipc.h.
    static final int OFF_FRAME_READING_SLOT = 128;

    // --- Heartbeat field offsets (relative to heartbeat region) ----------
    static final int OFF_JAVA_HEARTBEAT = 0;
    static final int OFF_ENGINE_HEARTBEAT = CACHE_LINE;       // 64
    static final int OFF_ENGINE_STATE = CACHE_LINE + 8;       // 72

    // --- Engine state values --------------------------------------------
    static final int ENGINE_STARTING = 0;
    static final int ENGINE_RUNNING = 1;
    static final int ENGINE_SHUTDOWN = 2;

    // --- Ring-buffer header offsets (relative to a ring region) ----------
    static final int RB_OFF_WRITE_POS = 0;
    static final int RB_OFF_READ_POS = CACHE_LINE;            // 64
    static final int RB_OFF_CAPACITY = 128;
    static final int RB_OFF_SLOT_SIZE = 136;
    static final int RB_SLOTS_START = 144;

    // --- Slot layout -----------------------------------------------------
    static final int SLOT_TYPE_OFFSET = 0;
    static final int SLOT_LEN_OFFSET = 4;
    static final int SLOT_PAYLOAD_OFFSET = 8;
    static final int MAX_PAYLOAD = SLOT_SIZE - 8;             // 248

    // --- Multi-slot event framing (engine→Java) --------------------------
    // A payload larger than one slot is split across consecutive slots (safe
    // because the event ring is single-producer, so the slots stay contiguous).
    // The FIRST slot's type carries EVT_CONT_FLAG and its payload is
    // [windowId:4][totalUserLen:4][chunk0]; each following EVT_CONTINUATION
    // slot's payload is [windowId:4][chunkK]. EventRingBuffer reassembles them
    // into a single logical event. Mirrored in the engine's jux_ring_buffer.h.
    static final int EVT_CONT_FLAG = 0x80000000;             // high bit of slot type
    static final int EVT_CONTINUATION = 0x0000FFFE;          // continuation slot type

    // --- Default region sizes -------------------------------------------
    static final long CMD_BUF_SIZE = 4L * 1024 * 1024;
    static final long EVT_BUF_SIZE = 8L * 1024 * 1024;
    static final long JOURNAL_SIZE = 1L * 1024 * 1024;
    // (No DATA_SIZE constant: the data region is sized by channelSizeForFrames()
    // from the frame/popup slot counts — a fixed default would be far too small
    // for even one frame slot and silently drop frames if ever wired in.)

    // --- Off-screen frame buffer (data region) --------------------------
    // The channel's data region holds {@link #FRAME_BUFFER_COUNT}
    // viewport-sized BGRA8888 slots, double-buffered so the engine can fill
    // one slot while Java reads the other. The region is sized once at
    // channel creation for a generous device-pixel cap (overridable); pages
    // larger than a slot are downscaled by the engine to fit (see the
    // CopyFromSurface output-size clamp in jux_engine_api.cc), so resize
    // never needs to remap the channel.
    static final int FRAME_BUFFER_COUNT = 3;
    static final int MAX_FRAME_WIDTH =
        Integer.getInteger("skia.webview.maxFrameWidth", 2560);
    static final int MAX_FRAME_HEIGHT =
        Integer.getInteger("skia.webview.maxFrameHeight", 1440);
    /** Bytes per frame slot (BGRA8888 at the device-pixel cap). */
    static final long FRAME_SLOT_BYTES =
        (long) MAX_FRAME_WIDTH * MAX_FRAME_HEIGHT * 4L;

    // --- OSR popup frame slots (data region, after the main slots) ----------
    // Blink page-popups (<select>/colour/datalist) are captured into their own
    // double-buffered slots appended after the main slots. These caps are FIXED
    // (NOT property-overridable) so the engine computes the identical layout
    // from data.size() without any header field — see jux_engine_api.cc
    // kPopupFrameBufferCount / kPopupSlotBytes (must match these exactly).
    static final int POPUP_FRAME_BUFFER_COUNT = 2;
    static final int MAX_POPUP_WIDTH = 1280;
    static final int MAX_POPUP_HEIGHT = 1600;
    /** Bytes per popup slot (BGRA8888 at the popup cap). */
    static final long POPUP_FRAME_SLOT_BYTES =
        (long) MAX_POPUP_WIDTH * MAX_POPUP_HEIGHT * 4L;
    /** Total bytes of the popup region (all popup slots). */
    static final long POPUP_REGION_BYTES =
        POPUP_FRAME_SLOT_BYTES * POPUP_FRAME_BUFFER_COUNT;

    // --- Print-preview modal frame slots (data region, between main + popup) ----
    // The off-screen chrome://print preview WebContents is captured into its OWN
    // double-buffered region, SEPARATE from the popup region, so the preview's own
    // <select>/datalist dropdowns can use the popup region without colliding with
    // the modal frame. Physical data-region order is [main][preview][popup], so the
    // popup region stays at the very end (its offset is unchanged). FIXED caps (NOT
    // property-overridable) — the engine derives the identical layout from
    // data.size(); see jux_engine_api.cc kPreview* (must match these exactly).
    static final int PREVIEW_FRAME_BUFFER_COUNT = 2;
    static final int MAX_PREVIEW_WIDTH = 1280;
    static final int MAX_PREVIEW_HEIGHT = 1600;
    /** Bytes per preview slot (BGRA8888 at the preview cap). */
    static final long PREVIEW_FRAME_SLOT_BYTES =
        (long) MAX_PREVIEW_WIDTH * MAX_PREVIEW_HEIGHT * 4L;
    /** Total bytes of the preview region (all preview slots). */
    static final long PREVIEW_REGION_BYTES =
        PREVIEW_FRAME_SLOT_BYTES * PREVIEW_FRAME_BUFFER_COUNT;

    /**
     * Total channel size needed to hold the fixed base regions plus the
     * double-buffered main frame slots and the popup slots in the data region.
     * Must use the same region constants {@link SharedMemoryChannel#create} uses
     * to lay out the header, so the carved {@code dataSize} equals
     * {@code FRAME_SLOT_BYTES * FRAME_BUFFER_COUNT + POPUP_REGION_BYTES} exactly.
     */
    static long channelSizeForFrames() {
        return HEADER_SIZE + HEARTBEAT_SIZE + CMD_BUF_SIZE + EVT_BUF_SIZE
            + JOURNAL_SIZE + FRAME_SLOT_BYTES * FRAME_BUFFER_COUNT
            + PREVIEW_REGION_BYTES + POPUP_REGION_BYTES;
    }
}
