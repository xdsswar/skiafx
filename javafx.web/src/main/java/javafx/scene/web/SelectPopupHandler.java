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
 * Handler invoked on the JavaFX Application Thread when a page opens an HTML
 * {@code <select>} drop-down. Register it with
 * {@link WebEngine#setSelectPopupHandler(SelectPopupHandler)}.
 *
 * <p>If no handler is set, the drop-down is automatically cancelled (the
 * selection is unchanged) — an off-screen {@link WebView} has no native popup,
 * so an application that wants working {@code <select>} controls must provide a
 * handler.
 *
 * @since 25
 */
@FunctionalInterface
public interface SelectPopupHandler {

    /**
     * Handles a drop-down request. Answer it with {@link SelectPopup#select} or
     * {@link SelectPopup#cancel} (now or later); the page stays suspended until
     * then.
     *
     * @param popup the pending drop-down request
     */
    void handle(SelectPopup popup);
}
