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
 * Handler invoked on the JavaFX Application Thread when a page requests a color
 * via an {@code <input type="color">} control. Register it with
 * {@link WebEngine#setColorChooserHandler(ColorChooserHandler)}.
 *
 * <p>If no handler is set, the request is automatically cancelled (the control
 * keeps its current value) — an off-screen {@link WebView} has no native color
 * picker to fall back to, so an application that wants a working color input
 * must provide a handler.
 *
 * @since 25
 */
@FunctionalInterface
public interface ColorChooserHandler {

    /**
     * Handles a color request. Answer it by calling {@link ColorChooser#choose}
     * or {@link ColorChooser#cancel} (now or later); the page stays suspended
     * until then.
     *
     * @param chooser the pending color request
     */
    void handle(ColorChooser chooser);
}
