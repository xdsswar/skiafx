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
 * Producer side of the Java→engine SPSC command ring (one writer: the FX
 * thread). The engine is the sole consumer.
 *
 * <h2>Concurrency</h2>
 * The {@code writePos}/{@code readPos} counters live at 8-byte-aligned offsets
 * (see {@link MemoryLayout}), so they are accessed through a naturally-aligned
 * {@link VarHandle} with explicit ordering — <b>not</b> plain reads:
 * <ul>
 *   <li>publish: write the slot's type/len/payload, then
 *       {@code writePos.setRelease(writePos+1)} — the release makes the slot
 *       bytes visible to the consumer before it observes the new position;</li>
 *   <li>free-space check: {@code readPos.getAcquire()} — the acquire observes
 *       the consumer's released {@code readPos} so we never overwrite an
 *       unread slot.</li>
 * </ul>
 * This pairs exactly with the engine's {@code std::memory_order_acquire/release}
 * atomics (see {@code jux_ring_buffer.cc}) and is correct on every architecture
 * (unlike a plain-access ring, which is only correct on x86's strong model).
 *
 * <p>Monotonic positions (never reset) make full/empty unambiguous; the slot
 * index is {@code pos & (capacity-1)} with power-of-two capacity. Internal.
 */
final class CommandRingBuffer {

    /** Naturally-aligned (8-byte) long handle; supports acquire/release. */
    private static final VarHandle POS = ValueLayout.JAVA_LONG.varHandle();

    private final MemorySegment buf;
    private final long capacity;   // power of two
    private final long mask;       // capacity - 1

    CommandRingBuffer(MemorySegment buf) {
        this.buf = buf;
        long cap = buf.get(ValueLayout.JAVA_LONG_UNALIGNED, MemoryLayout.RB_OFF_CAPACITY);
        if (cap <= 0 || (cap & (cap - 1)) != 0) {
            throw new IllegalStateException(
                "command ring capacity is not a positive power of two: " + cap);
        }
        long need = MemoryLayout.RB_SLOTS_START + cap * (long) MemoryLayout.SLOT_SIZE;
        if (need > buf.byteSize()) {
            throw new IllegalStateException(
                "command ring region (" + buf.byteSize() + " B) too small for "
                    + cap + " slots");
        }
        this.capacity = cap;
        this.mask = cap - 1;
    }

    /**
     * Writes one command. Caller must be the single producer thread.
     *
     * @param type    command id (see {@link CommandType})
     * @param payload payload bytes (may be {@code null} when {@code len == 0})
     * @param len     payload length; must be in {@code [0, MAX_PAYLOAD]}
     * @return {@code true} if written, {@code false} if the ring is full
     */
    boolean write(int type, byte[] payload, int len) {
        if (len < 0 || len > MemoryLayout.MAX_PAYLOAD) {
            throw new IllegalArgumentException(
                "payload length " + len + " out of range [0, "
                    + MemoryLayout.MAX_PAYLOAD + "]");
        }
        // Sole producer: plain read of our own writePos is correct.
        long writePos = (long) POS.get(buf, (long) MemoryLayout.RB_OFF_WRITE_POS);
        // Acquire the consumer's readPos so we observe freed slots.
        long readPos = (long) POS.getAcquire(buf, (long) MemoryLayout.RB_OFF_READ_POS);
        if (writePos - readPos >= capacity) {
            return false; // full
        }
        long slot = MemoryLayout.RB_SLOTS_START + (writePos & mask) * (long) MemoryLayout.SLOT_SIZE;
        buf.set(ValueLayout.JAVA_INT_UNALIGNED, slot + MemoryLayout.SLOT_TYPE_OFFSET, type);
        buf.set(ValueLayout.JAVA_INT_UNALIGNED, slot + MemoryLayout.SLOT_LEN_OFFSET, len);
        if (len > 0) {
            MemorySegment.copy(payload, 0, buf, ValueLayout.JAVA_BYTE,
                slot + MemoryLayout.SLOT_PAYLOAD_OFFSET, len);
        }
        // Release: publishes the slot bytes, then the new position.
        POS.setRelease(buf, (long) MemoryLayout.RB_OFF_WRITE_POS, writePos + 1);
        return true;
    }

    // --- Typed encoders -------------------------------------------------
    // Every command payload begins with [windowId:4] (the engine's
    // ReadStringPayload/ReadTwoDoublesPayload/ReadBoolPayload read the actual
    // args at offset 4+). Ints/longs are written through a heap-segment view
    // with native byte order, matching the engine's memcpy decode exactly.

    /** {@code [windowId:4]} — e.g. CREATE_WINDOW / SHOW / HIDE / REQUEST_FOCUS. */
    boolean writeWindowOnly(int type, int windowId) {
        byte[] p = new byte[4];
        MemorySegment.ofArray(p).set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        return write(type, p, 4);
    }

    /**
     * Writes a fully pre-built payload (which must already begin with the
     * {@code [windowId:4]} prefix). Used by the JSObject ops, which assemble a
     * variable tagged-value payload in {@link BlinkPage}. Returns {@code false}
     * if it exceeds a slot (the caller surfaces that as a JSException — commands
     * use no overflow region).
     */
    boolean writeBytes(int type, byte[] payload) {
        if (payload.length > MemoryLayout.MAX_PAYLOAD) {
            return false;
        }
        return write(type, payload, payload.length);
    }

    /**
     * {@code [windowId:4][len:4][utf8:N]} — e.g. LOAD_URL / LOAD_HTML / EXECUTE_JS.
     * The UTF-8 form must fit in a slot ({@code 8 + bytes <= MAX_PAYLOAD}); larger
     * content must be written to a temp file and passed as a {@code file://}
     * path instead (no overflow region is used).
     */
    boolean writeString(int type, int windowId, String s) {
        byte[] u = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int total = 8 + u.length;
        if (total > MemoryLayout.MAX_PAYLOAD) {
            throw new IllegalArgumentException(
                "string command payload " + total + " B exceeds slot MAX_PAYLOAD "
                    + MemoryLayout.MAX_PAYLOAD + "; use a temp file + file:// URL");
        }
        byte[] p = new byte[total];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, u.length);
        MemorySegment.copy(u, 0, ps, ValueLayout.JAVA_BYTE, 8, u.length);
        return write(type, p, total);
    }

    /**
     * Like {@link #writeString} but with a 4-byte request id between the window
     * id and the string, so the engine can echo it back for exact request/result
     * correlation. Payload: {@code [windowId:4][requestId:4][strLen:4][utf8]}.
     */
    boolean writeStringWithId(int type, int windowId, int requestId, String s) {
        byte[] u = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int total = 12 + u.length;
        if (total > MemoryLayout.MAX_PAYLOAD) {
            throw new IllegalArgumentException(
                "string command payload " + total + " B exceeds slot MAX_PAYLOAD "
                    + MemoryLayout.MAX_PAYLOAD + "; use a temp file + file:// URL");
        }
        byte[] p = new byte[total];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, requestId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 8, u.length);
        MemorySegment.copy(u, 0, ps, ValueLayout.JAVA_BYTE, 12, u.length);
        return write(type, p, total);
    }

    /** {@code [windowId:4][a:8(double)][b:8(double)]} — e.g. SET_POSITION. */
    boolean writeTwoDoubles(int type, int windowId, double a, double b) {
        byte[] p = new byte[20];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_DOUBLE_UNALIGNED, 4, a);
        ps.set(ValueLayout.JAVA_DOUBLE_UNALIGNED, 12, b);
        return write(type, p, 20);
    }

    /**
     * {@code [windowId:4][a:8(double)][b:8(double)][c:8(double)]} — SET_SIZE with
     * width, height (logical px) and the device-pixel scale. The engine sizes the
     * off-screen view to {@code a×b} logical px and renders at DSF {@code c}, so
     * the captured frame is {@code (a*c)×(b*c)} device px (HiDPI-crisp).
     */
    boolean writeThreeDoubles(int type, int windowId, double a, double b, double c) {
        byte[] p = new byte[28];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_DOUBLE_UNALIGNED, 4, a);
        ps.set(ValueLayout.JAVA_DOUBLE_UNALIGNED, 12, b);
        ps.set(ValueLayout.JAVA_DOUBLE_UNALIGNED, 20, c);
        return write(type, p, 28);
    }

    /** {@code [windowId:4][value:1]} — boolean flag commands. */
    boolean writeBool(int type, int windowId, boolean value) {
        byte[] p = new byte[5];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_BYTE, 4, (byte) (value ? 1 : 0));
        return write(type, p, 5);
    }

    // --- Off-screen input encoders --------------------------------------

    /** {@code [windowId:4][type:4][x:f32][y:f32][button:4][clickCount:4][modifiers:4]}. */
    boolean writeMouse(int windowId, int eventType, float x, float y,
                       int button, int clickCount, int modifiers) {
        byte[] p = new byte[28];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, eventType);
        ps.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 8, x);
        ps.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 12, y);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 16, button);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 20, clickCount);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 24, modifiers);
        return write(CommandType.MOUSE_EVENT, p, 28);
    }

    /** As {@link #writeMouse} but routed to the open OSR popup ({@code POPUP_MOUSE_EVENT}). */
    boolean writePopupMouse(int windowId, int eventType, float x, float y,
                            int button, int clickCount, int modifiers) {
        byte[] p = new byte[28];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, eventType);
        ps.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 8, x);
        ps.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 12, y);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 16, button);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 20, clickCount);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 24, modifiers);
        return write(CommandType.POPUP_MOUSE_EVENT, p, 28);
    }

    /** {@code [windowId:4][x:f32][y:f32][deltaX:f32][deltaY:f32][modifiers:4]}. */
    boolean writeWheel(int windowId, float x, float y, float dx, float dy,
                       int modifiers) {
        byte[] p = new byte[24];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 4, x);
        ps.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 8, y);
        ps.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 12, dx);
        ps.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 16, dy);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 20, modifiers);
        return write(CommandType.WHEEL_EVENT, p, 24);
    }

    /** As {@link #writeWheel} but routed to the open OSR popup ({@code POPUP_WHEEL_EVENT}). */
    boolean writePopupWheel(int windowId, float x, float y, float dx, float dy,
                            int modifiers) {
        byte[] p = new byte[24];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 4, x);
        ps.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 8, y);
        ps.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 12, dx);
        ps.set(ValueLayout.JAVA_FLOAT_UNALIGNED, 16, dy);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 20, modifiers);
        return write(CommandType.POPUP_WHEEL_EVENT, p, 24);
    }

    /**
     * {@code [windowId:4][type:4][windowsKeyCode:4][nativeKeyCode:4][modifiers:4][textLen:4][utf8:N]}.
     * {@code text} may be {@code null}/empty for keydown/keyup; it carries the
     * typed character(s) for a char event.
     */
    boolean writeKey(int windowId, int eventType, int windowsKeyCode,
                     int nativeKeyCode, int modifiers, String text) {
        byte[] u = text == null ? new byte[0]
            : text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int total = 24 + u.length;
        if (total > MemoryLayout.MAX_PAYLOAD) {
            u = new byte[0]; // oversized text is never a single keystroke; drop it
            total = 24;
        }
        byte[] p = new byte[total];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, eventType);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 8, windowsKeyCode);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 12, nativeKeyCode);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 16, modifiers);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 20, u.length);
        if (u.length > 0) {
            MemorySegment.copy(u, 0, ps, ValueLayout.JAVA_BYTE, 24, u.length);
        }
        return write(CommandType.KEY_EVENT, p, total);
    }

    /** As {@link #writeKey} but routed to the open OSR popup ({@code POPUP_KEY_EVENT}). */
    boolean writePopupKey(int windowId, int eventType, int windowsKeyCode,
                          int nativeKeyCode, int modifiers, String text) {
        byte[] u = text == null ? new byte[0]
            : text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int total = 24 + u.length;
        if (total > MemoryLayout.MAX_PAYLOAD) {
            u = new byte[0];
            total = 24;
        }
        byte[] p = new byte[total];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, eventType);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 8, windowsKeyCode);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 12, nativeKeyCode);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 16, modifiers);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 20, u.length);
        if (u.length > 0) {
            MemorySegment.copy(u, 0, ps, ValueLayout.JAVA_BYTE, 24, u.length);
        }
        return write(CommandType.POPUP_KEY_EVENT, p, total);
    }

    /** {@code [windowId:4][focused:4]}. */
    boolean writeFocus(int windowId, boolean focused) {
        byte[] p = new byte[8];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, focused ? 1 : 0);
        return write(CommandType.FOCUS_EVENT, p, 8);
    }

    // --- DOM command encoders -------------------------------------------
    // The engine reads windowId at offset 0, the (first) node id at offset 4,
    // and any further args at offset 8 (see jux_command_dispatch.cc:
    // OnCreateElement/OnSetAttribute/OnAppendChild/…). String lengths are
    // little-endian u16 (ReadLenStr16) for names/tags/classes/styles/listener
    // types, and u32 (ReadLenStr32) for textContent/innerHTML. Native byte
    // order on every supported platform (x86/ARM) is little-endian, matching
    // these JAVA_*_UNALIGNED writes.

    /** {@code [windowId:4][nodeId:4]} — REMOVE_ELEMENT / DOM_FOCUS / DOM_BLUR / DOM_CLICK. */
    boolean writeNode(int type, int windowId, int nodeId) {
        byte[] p = new byte[8];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, nodeId);
        return write(type, p, 8);
    }

    /** {@code [windowId:4][a:4][b:4]} — APPEND_CHILD / REMOVE_CHILD. */
    boolean writeTwoNodes(int type, int windowId, int a, int b) {
        byte[] p = new byte[12];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, a);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 8, b);
        return write(type, p, 12);
    }

    /** {@code [windowId:4][parentId:4][childId:4][refId:4]} — INSERT_BEFORE (refId=0 ⇒ append). */
    boolean writeThreeNodes(int type, int windowId, int a, int b, int c) {
        byte[] p = new byte[16];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, a);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 8, b);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 12, c);
        return write(type, p, 16);
    }

    /**
     * {@code [windowId:4][nodeId:4][len:2(u16)][utf8:N]} — CREATE_ELEMENT (tag),
     * REMOVE_ATTRIBUTE / REMOVE_STYLE_PROPERTY (name), ADD_CLASS / REMOVE_CLASS,
     * ADD_EVENT_LISTENER / REMOVE_EVENT_LISTENER (type). The UTF-8 form must fit
     * in a slot; a token longer than ~240 B is never a valid tag/class/event name.
     */
    boolean writeNodeString16(int type, int windowId, int nodeId, String s) {
        byte[] u = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int total = 10 + u.length;
        if (u.length > 0xFFFF || total > MemoryLayout.MAX_PAYLOAD) {
            throw new IllegalArgumentException(
                "node-string command payload " + total + " B exceeds slot MAX_PAYLOAD "
                    + MemoryLayout.MAX_PAYLOAD);
        }
        byte[] p = new byte[total];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, nodeId);
        ps.set(ValueLayout.JAVA_SHORT_UNALIGNED, 8, (short) u.length);
        MemorySegment.copy(u, 0, ps, ValueLayout.JAVA_BYTE, 10, u.length);
        return write(type, p, total);
    }

    /**
     * {@code [windowId:4][nodeId:4][nameLen:2][utf8Name:N][valueLen:2][utf8Value:N]}
     * — SET_ATTRIBUTE / SET_STYLE_PROPERTY. Both length prefixes are u16.
     */
    boolean writeNodeNameValue16(int type, int windowId, int nodeId,
                                 String name, String value) {
        byte[] n = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] v = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int total = 4 + 4 + 2 + n.length + 2 + v.length;
        if (n.length > 0xFFFF || v.length > 0xFFFF
                || total > MemoryLayout.MAX_PAYLOAD) {
            throw new IllegalArgumentException(
                "attribute command payload " + total + " B exceeds slot MAX_PAYLOAD "
                    + MemoryLayout.MAX_PAYLOAD + " (name/value too large for a slot)");
        }
        byte[] p = new byte[total];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, nodeId);
        ps.set(ValueLayout.JAVA_SHORT_UNALIGNED, 8, (short) n.length);
        MemorySegment.copy(n, 0, ps, ValueLayout.JAVA_BYTE, 10, n.length);
        int vOff = 10 + n.length;
        ps.set(ValueLayout.JAVA_SHORT_UNALIGNED, vOff, (short) v.length);
        MemorySegment.copy(v, 0, ps, ValueLayout.JAVA_BYTE, vOff + 2, v.length);
        return write(type, p, total);
    }

    /**
     * {@code [windowId:4][nodeId:4][len:4(u32)][utf8:N]} — SET_TEXT_CONTENT /
     * SET_INNER_HTML. Returns {@code false} (rather than throwing) when the UTF-8
     * form does not fit in a single slot: the engine has no temp-file variant for
     * these yet (see plan risk #3), so an oversize body is reported to the caller
     * to handle (skip/log) instead of corrupting the ring.
     */
    boolean writeNodeString32(int type, int windowId, int nodeId, String s) {
        byte[] u = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int total = 12 + u.length;
        if (total > MemoryLayout.MAX_PAYLOAD) {
            return false; // too large for one slot; caller decides
        }
        byte[] p = new byte[total];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, nodeId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 8, u.length);
        MemorySegment.copy(u, 0, ps, ValueLayout.JAVA_BYTE, 12, u.length);
        return write(type, p, total);
    }

    // --- Dialog / chooser / permission response encoders ----------------
    // Layout: [windowId:4][id:4] then a response-specific tail. The engine runs
    // the continuation stashed under {@code id} and resumes the page.

    /** {@code [windowId:4][id:4][flag:1]} — PERMISSION_RESPONSE / FULLSCREEN_RESPONSE / cancels. */
    boolean writeIdFlag(int type, int windowId, int id, boolean flag) {
        byte[] p = new byte[9];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, id);
        ps.set(ValueLayout.JAVA_BYTE, 8, (byte) (flag ? 1 : 0));
        return write(type, p, 9);
    }

    /** {@code [windowId:4][id:4][flag:1][val:4]} — COLOR_CHOOSER_RESPONSE (val=RGBA). */
    /**
     * {@code [windowId:4][chooserId:4][count:4][tempLen:4][utf8 tempPath]} —
     * FILE_CHOOSER_RESPONSE. {@code count==0} ⇒ cancel (empty tempPath); otherwise
     * {@code tempPath} names a UTF-8 file of {@code count} newline-separated native
     * paths that the engine reads and deletes. A single temp path always fits a slot.
     */
    boolean writeFileChooserResponse(int windowId, int chooserId, int count, String tempPath) {
        byte[] u = tempPath == null ? new byte[0]
            : tempPath.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int total = 16 + u.length;
        if (total > MemoryLayout.MAX_PAYLOAD) {
            return false;
        }
        byte[] p = new byte[total];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, chooserId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 8, count);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 12, u.length);
        if (u.length > 0) {
            MemorySegment.copy(u, 0, ps, ValueLayout.JAVA_BYTE, 16, u.length);
        }
        return write(CommandType.FILE_CHOOSER_RESPONSE, p, total);
    }

    boolean writeIdFlagInt(int type, int windowId, int id, boolean flag, int val) {
        byte[] p = new byte[13];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, id);
        ps.set(ValueLayout.JAVA_BYTE, 8, (byte) (flag ? 1 : 0));
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 9, val);
        return write(type, p, 13);
    }

    /**
     * {@code [windowId:4][id:4][flag:1][textLen:4][utf8:N]} — DIALOG_RESPONSE /
     * DOWNLOAD_RESPONSE. The text must fit one slot (prompt replies and download
     * paths are well within 248 B); a larger value is rejected with {@code false}.
     */
    boolean writeIdFlagString(int type, int windowId, int id, boolean flag, String text) {
        byte[] u = text == null ? new byte[0]
            : text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int total = 13 + u.length;
        if (total > MemoryLayout.MAX_PAYLOAD) {
            return false;
        }
        byte[] p = new byte[total];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, id);
        ps.set(ValueLayout.JAVA_BYTE, 8, (byte) (flag ? 1 : 0));
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 9, u.length);
        if (u.length > 0) {
            MemorySegment.copy(u, 0, ps, ValueLayout.JAVA_BYTE, 13, u.length);
        }
        return write(type, p, total);
    }

    /** {@code [windowId:4][id:4][flag:1][count:4]{[v:4]}…} — SELECT_POPUP_RESPONSE. */
    boolean writeIdFlagIntArray(int type, int windowId, int id, boolean flag, int[] values) {
        int n = values == null ? 0 : values.length;
        int total = 13 + n * 4;
        if (total > MemoryLayout.MAX_PAYLOAD) {
            n = (MemoryLayout.MAX_PAYLOAD - 13) / 4; // clamp: a selection list never realistically overflows
            total = 13 + n * 4;
        }
        byte[] p = new byte[total];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, id);
        ps.set(ValueLayout.JAVA_BYTE, 8, (byte) (flag ? 1 : 0));
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 9, n);
        for (int i = 0; i < n; i++) {
            ps.set(ValueLayout.JAVA_INT_UNALIGNED, 13 + i * 4, values[i]);
        }
        return write(type, p, total);
    }

    /**
     * {@code [windowId:4][id:4][flag:1][userLen:4][utf8User:N][passLen:4][utf8Pass:N]}
     * — AUTH_RESPONSE.
     */
    boolean writeAuthResponse(int type, int windowId, int id, boolean supplied,
                              String user, String pass) {
        byte[] u = user == null ? new byte[0]
            : user.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] pw = pass == null ? new byte[0]
            : pass.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int total = 9 + 4 + u.length + 4 + pw.length;
        if (total > MemoryLayout.MAX_PAYLOAD) {
            return false;
        }
        byte[] p = new byte[total];
        MemorySegment ps = MemorySegment.ofArray(p);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 0, windowId);
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 4, id);
        ps.set(ValueLayout.JAVA_BYTE, 8, (byte) (supplied ? 1 : 0));
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, 9, u.length);
        MemorySegment.copy(u, 0, ps, ValueLayout.JAVA_BYTE, 13, u.length);
        int passOff = 13 + u.length;
        ps.set(ValueLayout.JAVA_INT_UNALIGNED, passOff, pw.length);
        MemorySegment.copy(pw, 0, ps, ValueLayout.JAVA_BYTE, passOff + 4, pw.length);
        return write(type, p, total);
    }

    long capacity() {
        return capacity;
    }
}

