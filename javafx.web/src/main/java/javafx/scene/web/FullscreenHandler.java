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
 * fullscreen transition. Register it with
 * {@link WebEngine#setFullscreenHandler(FullscreenHandler)}. If no handler is
 * set, the request is denied.
 *
 * @since 25
 */
@FunctionalInterface
public interface FullscreenHandler {

    /**
     * Handles a fullscreen request. Answer it with {@link FullscreenRequest#allow}
     * or {@link FullscreenRequest#deny}.
     * @param request the pending request
     */
    void handle(FullscreenRequest request);
}
