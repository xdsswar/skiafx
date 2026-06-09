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
package javafx.scene.web;

/**
 * The result an interceptor returns from {@link NetworkInterceptor#onBodyChunk}
 * to control a streaming response body: forward the chunk unchanged, replace its
 * bytes, or drop it.
 *
 * @since 25
 */
public final class BodyEdit {

    enum Kind { PASS_THROUGH, REPLACE, DROP }

    private static final BodyEdit PASS = new BodyEdit(Kind.PASS_THROUGH, null);
    private static final BodyEdit DROP_EDIT = new BodyEdit(Kind.DROP, null);

    private final Kind kind;
    private final byte[] replacement;

    private BodyEdit(Kind kind, byte[] replacement) {
        this.kind = kind;
        this.replacement = replacement;
    }

    /**
     * Forwards the chunk to the page unchanged.
     * @return a pass-through edit
     */
    public static BodyEdit passThrough() {
        return PASS;
    }

    /**
     * Replaces the chunk's bytes with {@code bytes}.
     * @param bytes the replacement bytes
     * @return a replace edit
     */
    public static BodyEdit replace(byte[] bytes) {
        return new BodyEdit(Kind.REPLACE, bytes == null ? new byte[0] : bytes.clone());
    }

    /**
     * Drops the chunk (the page never sees these bytes).
     * @return a drop edit
     */
    public static BodyEdit drop() {
        return DROP_EDIT;
    }

    Kind kind() {
        return kind;
    }

    byte[] replacement() {
        return replacement;
    }
}
