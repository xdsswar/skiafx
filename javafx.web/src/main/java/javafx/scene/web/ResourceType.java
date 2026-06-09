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
 * The kind of resource a network request is fetching, as reported to a
 * {@link NetworkInterceptor} and usable in a {@link NetworkFilter}.
 * {@link #OTHER} covers anything not separately named.
 *
 * @since 25
 */
public enum ResourceType {

    /** The top-level document or a sub-frame document. */
    DOCUMENT(0),
    /** A CSS stylesheet. */
    STYLESHEET(1),
    /** A script resource. */
    SCRIPT(2),
    /** An image. */
    IMAGE(3),
    /** A font. */
    FONT(4),
    /** An {@code XMLHttpRequest}. */
    XHR(5),
    /** A {@code fetch()} request. */
    FETCH(6),
    /** Audio or video media. */
    MEDIA(7),
    /** A WebSocket handshake. */
    WEBSOCKET(8),
    /** Any other resource kind. */
    OTHER(9);

    private final int wireCode;

    ResourceType(int wireCode) {
        this.wireCode = wireCode;
    }

    int wireCode() {
        return wireCode;
    }

    static ResourceType fromWire(int code) {
        for (ResourceType t : values()) {
            if (t.wireCode == code) {
                return t;
            }
        }
        return OTHER;
    }
}
