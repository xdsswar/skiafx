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

import java.io.File;

/**
 * A snapshot of an in-flight or finished download, delivered to
 * {@link DownloadProgressHandler}. Immutable; each progress/finished
 * notification carries a fresh instance. {@link #cancel()} aborts the underlying
 * transfer (a no-op once the download is in a terminal state).
 *
 * @since 25
 */
public final class Download {

    private final WebEngine engine;
    private final int id;
    private final DownloadState state;
    private final long receivedBytes;
    private final long totalBytes;
    private final File targetFile;

    Download(WebEngine engine, int id, DownloadState state,
             long receivedBytes, long totalBytes, File targetFile) {
        this.engine = engine;
        this.id = id;
        this.state = state == null ? DownloadState.UNKNOWN : state;
        this.receivedBytes = receivedBytes;
        this.totalBytes = totalBytes;
        this.targetFile = targetFile;
    }

    /**
     * Returns the {@code WebEngine} that owns this download.
     * @return the source engine
     */
    public WebEngine getSource() {
        return engine;
    }

    /**
     * Returns the current lifecycle state.
     * @return the state
     */
    public DownloadState getState() {
        return state;
    }

    /**
     * Returns the number of bytes received so far.
     * @return the received byte count
     */
    public long getReceivedBytes() {
        return receivedBytes;
    }

    /**
     * Returns the total size in bytes, or {@code -1} if unknown.
     * @return the total byte count, or {@code -1}
     */
    public long getTotalBytes() {
        return totalBytes;
    }

    /**
     * Returns the destination file, or {@code null} if not yet known.
     * @return the target file
     */
    public File getTargetFile() {
        return targetFile;
    }

    /**
     * Aborts the download. No effect once it has completed, cancelled, or been
     * interrupted.
     */
    public void cancel() {
        engine.cancelDownload(id);
    }
}
