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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * Consumer side of the engine→Java SPSC event ring (one reader: the
 * {@link EventPump} thread). The engine is the sole producer.
 *
 * <h2>Concurrency</h2>
 * Mirror of {@link CommandRingBuffer}: the position counters are accessed via
 * a naturally-aligned {@link VarHandle}. {@link #poll(EventSlot)} reads the
 * producer's {@code writePos} with <b>acquire</b> ordering, which pairs with
 * the engine's release store and guarantees the slot bytes written before that
 * release are visible before we read them. {@link #advance()} publishes our
 * consumed {@code readPos} with <b>release</b> ordering so the producer can
 * reclaim the slot.
 *
 * <h2>Memory safety</h2>
 * The engine-supplied slot length is untrusted: {@link #poll} clamps it to
 * {@code [0, MAX_PAYLOAD]} before exposing it, and {@link EventSlot} additionally
 * bounds-checks every field read. There is no out-of-band/overflow path — a
 * length can never address memory outside its own slot. Internal.
 */
final class EventRingBuffer {

    private static final VarHandle POS = ValueLayout.JAVA_LONG.varHandle();

    private final MemorySegment buf;
    private final long capacity;
    private final long mask;
    /** Ring slots consumed by the last {@link #poll} (>1 for reassembled events). */
    private int lastSlots = 1;

    EventRingBuffer(MemorySegment buf) {
        this.buf = buf;
        long cap = buf.get(ValueLayout.JAVA_LONG_UNALIGNED, MemoryLayout.RB_OFF_CAPACITY);
        if (cap <= 0 || (cap & (cap - 1)) != 0) {
            throw new IllegalStateException(
                "event ring capacity is not a positive power of two: " + cap);
        }
        long need = MemoryLayout.RB_SLOTS_START + cap * (long) MemoryLayout.SLOT_SIZE;
        if (need > buf.byteSize()) {
            throw new IllegalStateException(
                "event ring region (" + buf.byteSize() + " B) too small for "
                    + cap + " slots");
        }
        this.capacity = cap;
        this.mask = cap - 1;
    }

    /**
     * Reads the next event into {@code out} without consuming it. Caller must be
     * the single consumer thread, and must call {@link #advance()} after fully
     * processing the slot (before the next {@code poll}).
     *
     * @return {@code true} if an event was read, {@code false} if the ring is empty
     */
    boolean poll(EventSlot out) {
        // Sole consumer: plain read of our own readPos is correct.
        long readPos = (long) POS.get(buf, (long) MemoryLayout.RB_OFF_READ_POS);
        // Acquire the producer's writePos so the published slot bytes are visible.
        long writePos = (long) POS.getAcquire(buf, (long) MemoryLayout.RB_OFF_WRITE_POS);
        if (readPos >= writePos) {
            return false; // empty
        }
        long slot = MemoryLayout.RB_SLOTS_START + (readPos & mask) * (long) MemoryLayout.SLOT_SIZE;
        int type = buf.get(ValueLayout.JAVA_INT_UNALIGNED, slot + MemoryLayout.SLOT_TYPE_OFFSET);
        int len = clampLen(buf.get(ValueLayout.JAVA_INT_UNALIGNED, slot + MemoryLayout.SLOT_LEN_OFFSET));

        // Multi-slot event: the first slot's type carries EVT_CONT_FLAG and the
        // payload is reassembled from this slot plus its continuation slots.
        if ((type & MemoryLayout.EVT_CONT_FLAG) != 0) {
            return assembleLarge(out, readPos, writePos, slot, type, len);
        }

        lastSlots = 1;
        out.eventType = type;
        out.length = len;
        out.buffer = buf;
        out.payloadOffset = slot + MemoryLayout.SLOT_PAYLOAD_OFFSET;
        return true;
    }

    /**
     * Reassembles a multi-slot event into a heap-backed {@link EventSlot}. The
     * header slot holds {@code [windowId:4][totalUserLen:4][chunk0]}; each
     * continuation slot holds {@code [windowId:4][chunkK]}. Because the engine is
     * the sole producer and publishes all slots with one release of {@code
     * writePos}, the continuation slots are guaranteed present and contiguous
     * once the header is visible. Untrusted lengths are clamped so a corrupt
     * header can never read beyond the slots actually published.
     */
    private boolean assembleLarge(EventSlot out, long readPos, long writePos,
                                  long headerSlot, int headerType, int headerLen) {
        int originalType = headerType & ~MemoryLayout.EVT_CONT_FLAG;
        long hp = headerSlot + MemoryLayout.SLOT_PAYLOAD_OFFSET;
        int windowId = buf.get(ValueLayout.JAVA_INT_UNALIGNED, hp);
        int totalLen = buf.get(ValueLayout.JAVA_INT_UNALIGNED, hp + 4);
        if (totalLen < 0) {
            totalLen = 0;
        }
        // Cap to what the published slots could possibly hold (corruption guard).
        long maxBytes = (writePos - readPos) * (long) MemoryLayout.MAX_PAYLOAD;
        if (totalLen > maxBytes) {
            totalLen = (int) maxBytes;
        }

        byte[] assembled = new byte[4 + totalLen];
        assembled[0] = (byte) windowId;
        assembled[1] = (byte) (windowId >>> 8);
        assembled[2] = (byte) (windowId >>> 16);
        assembled[3] = (byte) (windowId >>> 24);

        int chunk0 = Math.min(Math.max(headerLen - 8, 0), totalLen); // user bytes in header
        MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, hp + 8, assembled, 4, chunk0);
        int got = chunk0;
        int slots = 1;
        // Walk ONLY this event's continuation slots. The producer tags every
        // continuation slot's type field with EVT_CONTINUATION; the slot after
        // this event's last chunk belongs to a different event and will NOT
        // carry that marker. Stopping at the first non-continuation slot bounds
        // reassembly (and advance()/lastSlots) to exactly this event, so a
        // corrupt totalLen header can no longer over-consume subsequent
        // unrelated events and permanently desync the ring.
        while (got < totalLen && (readPos + slots) < writePos) {
            long cs = MemoryLayout.RB_SLOTS_START
                + ((readPos + slots) & mask) * (long) MemoryLayout.SLOT_SIZE;
            int ctype = buf.get(ValueLayout.JAVA_INT_UNALIGNED,
                cs + MemoryLayout.SLOT_TYPE_OFFSET);
            if (ctype != MemoryLayout.EVT_CONTINUATION) {
                break; // next event's header — this event's slots end here
            }
            int clen = clampLen(buf.get(ValueLayout.JAVA_INT_UNALIGNED,
                cs + MemoryLayout.SLOT_LEN_OFFSET));
            int chunk = Math.min(Math.max(clen - 4, 0), totalLen - got); // skip windowId
            MemorySegment.copy(buf, ValueLayout.JAVA_BYTE,
                cs + MemoryLayout.SLOT_PAYLOAD_OFFSET + 4, assembled, 4 + got, chunk);
            got += chunk;
            slots++;
        }

        lastSlots = slots;
        out.eventType = originalType;
        out.length = 4 + got;
        out.buffer = MemorySegment.ofArray(assembled);
        out.payloadOffset = 0;
        return true;
    }

    /** Clamps an untrusted slot length into {@code [0, MAX_PAYLOAD]}. */
    private static int clampLen(int len) {
        if (len < 0) {
            return 0;
        }
        return Math.min(len, MemoryLayout.MAX_PAYLOAD);
    }

    /** Consumes the slot(s) read by the last {@link #poll}, releasing them to the producer. */
    void advance() {
        long readPos = (long) POS.get(buf, (long) MemoryLayout.RB_OFF_READ_POS);
        POS.setRelease(buf, (long) MemoryLayout.RB_OFF_READ_POS, readPos + lastSlots);
    }

    long capacity() {
        return capacity;
    }
}
