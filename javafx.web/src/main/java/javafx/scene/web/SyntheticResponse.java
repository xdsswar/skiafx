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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A fully synthetic HTTP response an interceptor can return for a request via
 * {@link NetworkExchange#respondWith(SyntheticResponse)}, short-circuiting the
 * network entirely. Build with {@link #builder()}.
 *
 * @since 25
 */
public final class SyntheticResponse {

    private final int status;
    private final String reason;
    private final String[] headerNames;
    private final String[] headerValues;
    private final byte[] body;

    private SyntheticResponse(int status, String reason, String[] headerNames,
                              String[] headerValues, byte[] body) {
        this.status = status;
        this.reason = reason;
        this.headerNames = headerNames;
        this.headerValues = headerValues;
        this.body = body;
    }

    /**
     * Creates a new builder (defaults: status {@code 200 OK}, empty body).
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    int status() {
        return status;
    }

    String reason() {
        return reason;
    }

    String[] headerNames() {
        return headerNames;
    }

    String[] headerValues() {
        return headerValues;
    }

    byte[] body() {
        return body;
    }

    /**
     * Builder for {@link SyntheticResponse}.
     *
     * @since 25
     */
    public static final class Builder {

        private int status = 200;
        private String reason = "OK";
        private final List<String> names = new ArrayList<>();
        private final List<String> values = new ArrayList<>();
        private byte[] body = new byte[0];

        private Builder() {
        }

        /**
         * Sets the status line.
         * @param code the status code
         * @param reasonPhrase the reason phrase
         * @return this builder
         */
        public Builder status(int code, String reasonPhrase) {
            this.status = code;
            this.reason = reasonPhrase == null ? "" : reasonPhrase;
            return this;
        }

        /**
         * Adds a response header.
         * @param name the header name
         * @param value the header value
         * @return this builder
         */
        public Builder header(String name, String value) {
            if (name != null) {
                names.add(name);
                values.add(value == null ? "" : value);
            }
            return this;
        }

        /**
         * Sets the {@code Content-Type} header.
         * @param mime the MIME type
         * @return this builder
         */
        public Builder contentType(String mime) {
            return header("Content-Type", mime);
        }

        /**
         * Sets the response body bytes.
         * @param bytes the body
         * @return this builder
         */
        public Builder body(byte[] bytes) {
            this.body = bytes == null ? new byte[0] : bytes.clone();
            return this;
        }

        /**
         * Sets the response body from a UTF-8 string.
         * @param text the body text
         * @return this builder
         */
        public Builder body(String text) {
            this.body = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        /**
         * Builds the synthetic response.
         * @return the response
         */
        public SyntheticResponse build() {
            return new SyntheticResponse(status, reason,
                names.toArray(new String[0]), values.toArray(new String[0]), body);
        }
    }
}
