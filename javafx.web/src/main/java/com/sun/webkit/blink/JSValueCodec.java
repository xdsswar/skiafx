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

import java.nio.charset.StandardCharsets;

/**
 * Symmetric tagged-value codec for the Java↔JS bridge — the single encoding used
 * for every {@code executeScript}/{@code JSObject} result and argument. Mirrors
 * the engine's {@code jux_js_value.{h,cc}} (little-endian). Layout:
 *
 * <pre>
 *   [tag:1] then, by tag:
 *     0 null/undefined  — (no payload)            → Java {@code null}
 *     1 boolean         — [v:1]                    → {@code Boolean}
 *     2 int32           — [v:4]                     → {@code Integer}
 *     3 double          — [v:8]                     → {@code Double}
 *     4 string          — [len:4][utf8:N]           → {@code String}
 *     5 jsObject        — [id:4]                    → {@link JSObjectImpl}
 *     6 javaObject      — [id:4]                    → the original Java object
 * </pre>
 *
 * <p>Tag 5 wraps a live V8 object the renderer keeps alive (released on GC via
 * the wrapper's {@code Cleaner}); tag 6 carries a Java object exposed to JS via
 * {@link JSObjectImpl#setMember}. Internal; never exported from {@code javafx.web}.
 */
final class JSValueCodec {

    static final int TAG_NULL = 0;
    static final int TAG_BOOL = 1;
    static final int TAG_INT = 2;
    static final int TAG_DOUBLE = 3;
    static final int TAG_STRING = 4;
    static final int TAG_JS_OBJECT = 5;
    static final int TAG_JAVA_OBJECT = 6;

    private JSValueCodec() { }

    /**
     * Encodes a Java argument to tagged bytes for a command payload. Numbers
     * follow the JS contract ({@code Integer}→int32, other numbers→double);
     * a {@link JSObjectImpl} round-trips by id; any other Java object is
     * registered with {@code page} and exposed to JS as a javaObject.
     */
    static byte[] encode(Object v, BlinkPage page) {
        if (v == null) {
            return new byte[] { TAG_NULL };
        }
        if (v instanceof Boolean b) {
            return new byte[] { TAG_BOOL, (byte) (b ? 1 : 0) };
        }
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) {
            byte[] o = new byte[5];
            o[0] = TAG_INT;
            putInt(o, 1, ((Number) v).intValue());
            return o;
        }
        if (v instanceof Number n) { // Long, Float, Double, BigDecimal, …
            byte[] o = new byte[9];
            o[0] = TAG_DOUBLE;
            putLong(o, 1, Double.doubleToRawLongBits(n.doubleValue()));
            return o;
        }
        if (v instanceof String s) {
            byte[] u = s.getBytes(StandardCharsets.UTF_8);
            byte[] o = new byte[5 + u.length];
            o[0] = TAG_STRING;
            putInt(o, 1, u.length);
            System.arraycopy(u, 0, o, 5, u.length);
            return o;
        }
        if (v instanceof Character c) {
            return encode(c.toString(), page);
        }
        if (v instanceof JSObjectImpl j) {
            byte[] o = new byte[5];
            o[0] = TAG_JS_OBJECT;
            putInt(o, 1, j.objectId());
            return o;
        }
        // Arbitrary Java object → expose it to JS (the page owns the table).
        byte[] o = new byte[5];
        o[0] = TAG_JAVA_OBJECT;
        putInt(o, 1, page.registerJavaObject(v));
        return o;
    }

    /**
     * Decodes a single tagged value from an event slot at relative offset
     * {@code off}, resolving object ids through {@code page}. Bounds-checked by
     * {@link EventSlot}; an unknown/corrupt tag decodes to {@code null}.
     */
    static Object decode(EventSlot slot, int off, BlinkPage page) {
        return decode(slot, off, page, null);
    }

    /**
     * As {@link #decode(EventSlot, int, BlinkPage)} but, when {@code consumed}
     * is non-null, reports the number of bytes the value occupied in
     * {@code consumed[0]} — so a caller decoding a run of values (e.g. the
     * argument list of a JS→Java callback) can advance its cursor.
     */
    static Object decode(EventSlot slot, int off, BlinkPage page, int[] consumed) {
        int tag = slot.readByte(off) & 0xFF;
        int used;
        Object value;
        switch (tag) {
            case TAG_BOOL -> { used = 2; value = slot.readByte(off + 1) != 0; }
            case TAG_INT -> { used = 5; value = slot.readInt(off + 1); }
            case TAG_DOUBLE -> { used = 9; value = slot.readDouble(off + 1); }
            case TAG_STRING -> {
                // Guard the 4-byte length read ITSELF: on a slot truncated before
                // the length field, slot.readInt would throw IllegalStateException.
                // A slot too short to hold the length decodes to an empty string.
                // (bugs.md M16 — robustness for the unguarded callback-args path.)
                if (off + 5 > slot.length) {
                    used = Math.max(1, slot.length - off);
                    value = "";
                } else {
                    int len = slot.readInt(off + 1);
                    // Clamp the declared length to what the slot actually holds, so a
                    // corrupt/truncated length can't make readUtf8 read past the buffer.
                    int avail = slot.length - (off + 5);
                    if (len > avail) {
                        len = Math.max(0, avail);
                    }
                    used = 5 + Math.max(0, len);
                    value = len <= 0 ? "" : slot.readUtf8(off + 5, len);
                }
            }
            case TAG_JS_OBJECT -> { used = 5; value = page.wrapJsObject(slot.readInt(off + 1)); }
            case TAG_JAVA_OBJECT -> { used = 5; value = page.lookupJavaObject(slot.readInt(off + 1)); }
            case TAG_NULL -> { used = 1; value = null; }
            // Unknown/corrupt tag: consume only the tag byte and stop meaningfully.
            default -> { used = 1; value = null; }
        }
        if (consumed != null) {
            consumed[0] = used;
        }
        return value;
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void putLong(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) {
            b[off + i] = (byte) (v >> (8 * i));
        }
    }
}
