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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A request to start a download (an {@code <a download>} click or a
 * {@code Content-Disposition: attachment} response). Surfaced to the application
 * through {@link WebEngine#downloadHandlerProperty() downloadHandler} so it can
 * choose a target file or refuse.
 *
 * <p>The download is held until answered. The request is <b>single-shot</b>. If
 * no handler is set, the download is denied. After {@link #accept(File)}, watch
 * progress and completion through
 * {@link WebEngine#downloadProgressHandlerProperty() downloadProgressHandler}.
 *
 * @since 25
 */
public final class DownloadRequest {

    private final WebEngine engine;
    private final int id;
    private final String url;
    private final String suggestedFileName;
    private final String mimeType;
    private final long totalBytes;
    private final AtomicBoolean responded = new AtomicBoolean();

    DownloadRequest(WebEngine engine, int id, String url, String suggestedFileName,
                    String mimeType, long totalBytes) {
        this.engine = engine;
        this.id = id;
        this.url = url == null ? "" : url;
        this.suggestedFileName = suggestedFileName == null ? "" : suggestedFileName;
        this.mimeType = mimeType == null ? "" : mimeType;
        this.totalBytes = totalBytes;
    }

    /**
     * Returns the {@code WebEngine} whose page initiated this download.
     * @return the source engine
     */
    public WebEngine getSource() {
        return engine;
    }

    /**
     * Returns the download URL.
     * @return the URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Returns the file name the page suggests for the download.
     * @return the suggested file name
     */
    public String getSuggestedFileName() {
        return suggestedFileName;
    }

    /**
     * Returns the MIME type, or an empty string if unknown.
     * @return the MIME type
     */
    public String getMimeType() {
        return mimeType;
    }

    /**
     * Returns the total size in bytes, or {@code -1} if not known in advance.
     * @return the total byte count, or {@code -1}
     */
    public long getTotalBytes() {
        return totalBytes;
    }

    /**
     * Returns whether this request has already been answered.
     * @return {@code true} once {@link #accept(File)} or {@link #deny()} ran
     */
    public boolean isResponded() {
        return responded.get();
    }

    /**
     * Accepts the download and writes it to {@code target}.
     * @param target the destination file
     */
    public void accept(File target) {
        if (!responded.compareAndSet(false, true)) {
            return;
        }
        engine.respondDownload(id, target != null, target == null ? "" : target.getAbsolutePath());
    }

    /**
     * Refuses the download.
     */
    public void deny() {
        if (!responded.compareAndSet(false, true)) {
            return;
        }
        engine.respondDownload(id, false, "");
    }
}
