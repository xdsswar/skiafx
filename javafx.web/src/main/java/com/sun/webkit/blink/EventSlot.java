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
import java.nio.charset.StandardCharsets;

/**
 * Reusable cursor over one event slot's payload, populated by
 * {@link EventRingBuffer#poll(EventSlot)}. A single instance is reused by the
 * {@link EventPump} reader thread across all events to avoid per-event garbage.
 *
 * <p>The payload always begins with {@code [windowId:4]} (the engine prepends
 * it — see {@code EventWriter::WriteEvent}), followed by the event-specific
 * fields documented in {@link NativeEventType}. All field reads are
 * <b>bounds-checked</b> against the slot's declared length: an out-of-range
 * offset throws rather than reading neighbouring slot memory, so a corrupt or
 * truncated length from the engine can never cause an over-read.
 *
 * <p>Not thread-safe; owned exclusively by the reader thread. Internal.
 */
final class EventSlot {

    /** Event type id (see {@link NativeEventType}). */
    int eventType;
    /** Valid payload length in bytes (already clamped to {@code [0, MAX_PAYLOAD]}). */
    int length;
    /** The event ring segment the payload lives in (valid until {@code advance()}). */
    MemorySegment buffer;
    /** Absolute byte offset of the payload start within {@link #buffer}. */
    long payloadOffset;

    /** windowId is always the first 4 payload bytes. */
    int windowId() {
        return readInt(0);
    }

    int readInt(int rel) {
        bounds(rel, 4);
        return buffer.get(ValueLayout.JAVA_INT_UNALIGNED, payloadOffset + rel);
    }

    long readLong(int rel) {
        bounds(rel, 8);
        return buffer.get(ValueLayout.JAVA_LONG_UNALIGNED, payloadOffset + rel);
    }

    double readDouble(int rel) {
        bounds(rel, 8);
        return buffer.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, payloadOffset + rel);
    }

    byte readByte(int rel) {
        bounds(rel, 1);
        return buffer.get(ValueLayout.JAVA_BYTE, payloadOffset + rel);
    }

    /** Reads a UTF-8 string of {@code len} bytes starting at relative offset {@code rel}. */
    String readUtf8(int rel, int len) {
        bounds(rel, len);
        byte[] b = new byte[len];
        MemorySegment.copy(buffer, ValueLayout.JAVA_BYTE, payloadOffset + rel, b, 0, len);
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Reads {@code len} raw bytes starting at relative offset {@code rel} (binary payloads). */
    byte[] readBytes(int rel, int len) {
        bounds(rel, len);
        byte[] b = new byte[len];
        MemorySegment.copy(buffer, ValueLayout.JAVA_BYTE, payloadOffset + rel, b, 0, len);
        return b;
    }

    /**
     * Reads a length-prefixed UTF-8 string: a 4-byte little-endian length at
     * {@code rel} followed by that many bytes. Returns {@code ""} for length 0.
     *
     * <p>Tolerant of a slot the engine truncated to fit the 248-byte payload:
     * if the declared length runs past the slot, the string is clamped to the
     * bytes actually present rather than throwing. A console message or source
     * id cut off mid-field is acceptable; an over-read is not. If even the
     * 4-byte length prefix doesn't fit, returns {@code ""}.</p>
     */
    String readLenString(int rel) {
        if (rel < 0 || (long) rel + 4 > length) {
            return "";
        }
        int len = readInt(rel);
        if (len <= 0) return "";
        int avail = length - (rel + 4);
        if (avail <= 0) return "";
        if (len > avail) {
            len = avail; // engine truncated this slot — read what's present
        }
        return readUtf8(rel + 4, len);
    }

    private void bounds(int rel, int n) {
        if (rel < 0 || n < 0 || (long) rel + n > length) {
            throw new IllegalStateException(
                "event payload out of bounds: offset=" + rel + " len=" + n
                    + " slotLen=" + length + " type=0x" + Integer.toHexString(eventType));
        }
    }
}
