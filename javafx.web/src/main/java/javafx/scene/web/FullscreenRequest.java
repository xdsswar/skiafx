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
 * A request from a page to enter (or a notification that it is leaving)
 * fullscreen, e.g. {@code element.requestFullscreen()}. Surfaced through
 * {@link WebEngine#fullscreenHandlerProperty() fullscreenHandler}; the
 * application decides whether to honour it (typically by toggling
 * {@code Stage.setFullScreen(true)}) and answers with {@link #allow()} or
 * {@link #deny()}.
 *
 * <p>Single-shot. If no handler is set, the request is denied.
 *
 * @since 25
 */
public final class FullscreenRequest {

    private final WebEngine engine;
    private final int id;
    private final boolean entering;
    private final AtomicBoolean responded = new AtomicBoolean();

    FullscreenRequest(WebEngine engine, int id, boolean entering) {
        this.engine = engine;
        this.id = id;
        this.entering = entering;
    }

    /**
     * Returns the {@code WebEngine} whose page made this request.
     * @return the source engine
     */
    public WebEngine getSource() {
        return engine;
    }

    /**
     * Returns whether the page is asking to enter fullscreen ({@code true}) or
     * leave it ({@code false}).
     * @return {@code true} when entering fullscreen
     */
    public boolean isEntering() {
        return entering;
    }

    /**
     * Returns whether this request has already been answered.
     * @return {@code true} once {@link #allow()} or {@link #deny()} ran
     */
    public boolean isResponded() {
        return responded.get();
    }

    /**
     * Allows the fullscreen transition.
     */
    public void allow() {
        if (!responded.compareAndSet(false, true)) {
            return;
        }
        engine.respondFullscreen(id, true);
    }

    /**
     * Denies the fullscreen transition.
     */
    public void deny() {
        if (!responded.compareAndSet(false, true)) {
            return;
        }
        engine.respondFullscreen(id, false);
    }
}
