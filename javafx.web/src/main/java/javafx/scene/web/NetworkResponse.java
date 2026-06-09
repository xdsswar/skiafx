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
 * An immutable view of an HTTP response's status and headers (the body is
 * delivered separately via {@link NetworkInterceptor#onBodyChunk}). Presented to
 * {@link NetworkInterceptor#onResponse}; modify via the owning
 * {@link NetworkExchange}.
 *
 * @since 25
 */
public final class NetworkResponse {

    private final int statusCode;
    private final String reasonPhrase;
    private final NetworkHeaders headers;
    private final String mimeType;
    private final long expectedContentLength;
    private final boolean fromCache;

    NetworkResponse(int statusCode, String reasonPhrase, NetworkHeaders headers,
                    String mimeType, long expectedContentLength, boolean fromCache) {
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase == null ? "" : reasonPhrase;
        this.headers = headers == null ? NetworkHeaders.empty() : headers;
        this.mimeType = mimeType == null ? "" : mimeType;
        this.expectedContentLength = expectedContentLength;
        this.fromCache = fromCache;
    }

    /**
     * Returns the HTTP status code (e.g. {@code 200}).
     * @return the status code
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Returns the HTTP reason phrase (e.g. {@code "OK"}), or an empty string.
     * @return the reason phrase
     */
    public String reasonPhrase() {
        return reasonPhrase;
    }

    /**
     * Returns the response headers.
     * @return the headers
     */
    public NetworkHeaders headers() {
        return headers;
    }

    /**
     * Returns the MIME type, or an empty string if unknown.
     * @return the MIME type
     */
    public String mimeType() {
        return mimeType;
    }

    /**
     * Returns the declared body length, or {@code -1} if unknown (e.g. chunked).
     * @return the expected content length, or {@code -1}
     */
    public long expectedContentLength() {
        return expectedContentLength;
    }

    /**
     * Returns whether the response was served from cache.
     * @return {@code true} if from cache
     */
    public boolean fromCache() {
        return fromCache;
    }
}
