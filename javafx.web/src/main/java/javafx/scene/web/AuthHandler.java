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
 * Handler invoked on the JavaFX Application Thread when a load hits an HTTP
 * authentication challenge. Register it with
 * {@link WebEngine#setAuthHandler(AuthHandler)}.
 *
 * <p>If no handler is set, the challenge is cancelled and the load fails.
 *
 * @since 25
 */
@FunctionalInterface
public interface AuthHandler {

    /**
     * Handles an authentication challenge. Answer it with
     * {@link AuthRequest#supply} or {@link AuthRequest#cancel} (now or later);
     * the load stays held until then.
     *
     * @param request the pending challenge
     */
    void handle(AuthRequest request);
}
