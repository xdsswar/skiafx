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
 * Handler invoked on the JavaFX Application Thread when a page requests a
 * capability (geolocation, notifications, camera/microphone, …). Register it
 * with {@link WebEngine#setPermissionHandler(PermissionHandler)}.
 *
 * <p>If no handler is set, the request is automatically denied — a permission is
 * never granted without an explicit {@link PermissionRequest#allow()}.
 *
 * @since 25
 */
@FunctionalInterface
public interface PermissionHandler {

    /**
     * Handles a permission request. Answer it with {@link PermissionRequest#allow}
     * or {@link PermissionRequest#deny} (now or later); the page's request stays
     * pending until then.
     *
     * @param request the pending permission request
     */
    void handle(PermissionRequest request);
}
