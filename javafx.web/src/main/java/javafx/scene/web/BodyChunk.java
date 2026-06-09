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

import java.nio.ByteBuffer;

/**
 * One chunk of an HTTP response body, delivered to
 * {@link NetworkInterceptor#onBodyChunk} when body capture is armed. Chunks
 * arrive in order; {@link #last()} is {@code true} on the final one.
 *
 * <p>{@link #data()} is a read-only view valid only for the duration of the
 * callback — use {@link #copy()} to retain the bytes.
 *
 * @since 25
 */
public final class BodyChunk {

    private final byte[] bytes;
    private final long offset;
    private final boolean last;

    BodyChunk(byte[] bytes, long offset, boolean last) {
        this.bytes = bytes == null ? new byte[0] : bytes;
        this.offset = offset;
        this.last = last;
    }

    /**
     * Returns a read-only view over this chunk's bytes, valid only during the
     * callback.
     * @return a read-only {@code ByteBuffer}
     */
    public ByteBuffer data() {
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }

    /**
     * Returns this chunk's byte offset within the full body.
     * @return the offset
     */
    public long offset() {
        return offset;
    }

    /**
     * Returns whether this is the final chunk of the body.
     * @return {@code true} on the last chunk
     */
    public boolean last() {
        return last;
    }

    /**
     * Returns the chunk length in bytes.
     * @return the length
     */
    public int length() {
        return bytes.length;
    }

    /**
     * Returns a defensive copy of this chunk's bytes.
     * @return a fresh byte array
     */
    public byte[] copy() {
        return bytes.clone();
    }
}
