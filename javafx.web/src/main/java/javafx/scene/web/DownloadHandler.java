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
 * Handler invoked on the JavaFX Application Thread when a page starts a
 * download. Register it with {@link WebEngine#setDownloadHandler(DownloadHandler)}.
 *
 * <p>If no handler is set, the download is denied.
 *
 * @since 25
 */
@FunctionalInterface
public interface DownloadHandler {

    /**
     * Handles a download request. Answer it with {@link DownloadRequest#accept}
     * or {@link DownloadRequest#deny} (now or later).
     *
     * @param request the pending download request
     */
    void handle(DownloadRequest request);
}
