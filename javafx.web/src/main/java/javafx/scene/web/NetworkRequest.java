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
 * An immutable view of an outgoing HTTP request, as presented to a
 * {@link NetworkInterceptor}. To modify it, use the mutating methods on the
 * owning {@link NetworkExchange} (e.g. {@link NetworkExchange#setRequestHeader}).
 *
 * @since 25
 */
public final class NetworkRequest {

    private final String url;
    private final String method;
    private final NetworkHeaders headers;
    private final ResourceType resourceType;
    private final byte[] body;

    NetworkRequest(String url, String method, NetworkHeaders headers,
                   ResourceType resourceType, byte[] body) {
        this.url = url == null ? "" : url;
        this.method = method == null ? "GET" : method;
        this.headers = headers == null ? NetworkHeaders.empty() : headers;
        this.resourceType = resourceType == null ? ResourceType.OTHER : resourceType;
        this.body = body;
    }

    /**
     * Returns the request URL.
     * @return the URL
     */
    public String url() {
        return url;
    }

    /**
     * Returns the HTTP method (e.g. {@code "GET"}, {@code "POST"}).
     * @return the method
     */
    public String method() {
        return method;
    }

    /**
     * Returns the request headers.
     * @return the headers
     */
    public NetworkHeaders headers() {
        return headers;
    }

    /**
     * Returns the kind of resource being fetched.
     * @return the resource type
     */
    public ResourceType resourceType() {
        return resourceType;
    }

    /**
     * Returns whether the request carries an upload body.
     * @return {@code true} if a body is present
     */
    public boolean hasBody() {
        return body != null && body.length > 0;
    }

    /**
     * Returns a copy of the upload body, or {@code null} if none was captured.
     * @return the body bytes, or {@code null}
     */
    public byte[] body() {
        return body == null ? null : body.clone();
    }
}
