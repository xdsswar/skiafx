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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A request from a web page for a capability such as geolocation, notifications,
 * camera/microphone, clipboard read, MIDI, or screen capture. Surfaced to the
 * application through {@link WebEngine#permissionHandlerProperty() permissionHandler}.
 *
 * <p>The page's request promise is held pending until the application calls
 * {@link #allow()} or {@link #deny()}, so the answer may be given
 * asynchronously. The request is <b>single-shot</b>: after the first answer,
 * further calls have no effect. If no handler is registered, the engine denies
 * the request (a safe default — permissions are never granted silently).
 *
 * @since 25
 */
public final class PermissionRequest {

    private final WebEngine engine;
    private final int id;
    private final PermissionType type;
    private final String origin;
    private final AtomicBoolean responded = new AtomicBoolean();

    PermissionRequest(WebEngine engine, int id, PermissionType type, String origin) {
        this.engine = engine;
        this.id = id;
        this.type = type == null ? PermissionType.UNKNOWN : type;
        this.origin = origin == null ? "" : origin;
    }

    /**
     * Returns the {@code WebEngine} whose page made this request.
     * @return the source engine
     */
    public WebEngine getSource() {
        return engine;
    }

    /**
     * Returns the requested capability.
     * @return the permission type
     */
    public PermissionType getPermissionType() {
        return type;
    }

    /**
     * Returns the requesting origin (e.g. {@code "https://example.com"}).
     * @return the origin
     */
    public String getOrigin() {
        return origin;
    }

    /**
     * Returns whether this request has already been answered.
     * @return {@code true} once {@link #allow()} or {@link #deny()} ran
     */
    public boolean isResponded() {
        return responded.get();
    }

    /**
     * Grants the permission and resumes the page.
     */
    public void allow() {
        if (!responded.compareAndSet(false, true)) {
            return;
        }
        engine.respondPermission(id, true);
    }

    /**
     * Denies the permission and resumes the page.
     */
    public void deny() {
        if (!responded.compareAndSet(false, true)) {
            return;
        }
        engine.respondPermission(id, false);
    }
}
