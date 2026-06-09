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
 * Handler invoked on the JavaFX Application Thread when a page activates an
 * {@code <input type="file">} control. Register it with
 * {@link WebEngine#setFileChooserHandler(FileChooserHandler)} to override the
 * default picker.
 *
 * <p>If no handler is set, a built-in JavaFX {@link javafx.stage.FileChooser} is
 * shown automatically, so file inputs work without any application code. Set a
 * handler to present a custom picker or supply files programmatically.
 *
 * @since 25
 */
@FunctionalInterface
public interface FileChooserHandler {

    /**
     * Handles a file request. Answer it by calling
     * {@link FileChooserRequest#choose} or {@link FileChooserRequest#cancel}
     * (now or later); the page stays suspended until then.
     *
     * @param request the pending file request
     */
    void handle(FileChooserRequest request);
}
