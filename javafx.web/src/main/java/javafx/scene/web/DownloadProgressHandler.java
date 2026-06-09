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
 * Handler invoked on the JavaFX Application Thread to report the progress and
 * completion of accepted downloads. Register it with
 * {@link WebEngine#setDownloadProgressHandler(DownloadProgressHandler)}.
 *
 * @since 25
 */
public interface DownloadProgressHandler {

    /**
     * Called repeatedly as bytes arrive.
     * @param download the current download snapshot
     */
    void onProgress(Download download);

    /**
     * Called once when the download reaches a terminal state (completed,
     * cancelled, or interrupted).
     * @param download the final download snapshot
     */
    void onFinished(Download download);
}
