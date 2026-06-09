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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A request to choose one or more files (or a directory), raised when the page
 * activates an {@code <input type="file">} control. Because the {@link WebView}
 * is rendered off-screen, the browser engine has no native file picker.
 *
 * <p>By default a built-in JavaFX {@link javafx.stage.FileChooser} (single- or
 * multi-select per the control) is shown automatically, so file inputs work
 * with no application code. Set {@link WebEngine#setFileChooserHandler} to take
 * over: the handler receives this request and answers it by calling
 * {@link #choose} or {@link #cancel}.
 *
 * <p>The page's script is suspended until the request is answered, so the
 * application may respond asynchronously. The request is <b>single-shot</b>:
 * after the first answer, further calls have no effect.
 *
 * @since 25
 */
public final class FileChooserRequest {

    /**
     * The kind of selection the page asked for.
     * @since 25
     */
    public enum Mode {
        /** A single file ({@code <input type="file">}). */
        OPEN,
        /** Multiple files ({@code <input type="file" multiple>}). */
        OPEN_MULTIPLE,
        /** A directory ({@code <input type="file" webkitdirectory>}). */
        DIRECTORY,
        /** A save target. */
        SAVE
    }

    private final WebEngine engine;
    private final int id;
    private final Mode mode;
    private final String title;
    private final String initialFileName;
    private final List<String> acceptFilters;
    private final AtomicBoolean responded = new AtomicBoolean();

    // Constructed internally by the WebView engine bridge.
    FileChooserRequest(WebEngine engine, int id, Mode mode, String title,
                       String initialFileName, List<String> acceptFilters) {
        this.engine = engine;
        this.id = id;
        this.mode = mode == null ? Mode.OPEN : mode;
        this.title = title == null ? "" : title;
        this.initialFileName = initialFileName == null ? "" : initialFileName;
        this.acceptFilters = acceptFilters == null
            ? List.of() : Collections.unmodifiableList(acceptFilters);
    }

    /**
     * Returns the {@code WebEngine} whose page raised this request.
     * @return the source engine
     */
    public WebEngine getSource() {
        return engine;
    }

    /**
     * Returns what kind of selection the page asked for.
     * @return the chooser mode, never {@code null}
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * Returns {@code true} if the control accepts multiple files.
     * @return whether multiple selection was requested
     */
    public boolean isMultiple() {
        return mode == Mode.OPEN_MULTIPLE;
    }

    /**
     * Returns the dialog title the page suggested, or an empty string.
     * @return the suggested title, never {@code null}
     */
    public String getTitle() {
        return title;
    }

    /**
     * Returns the default file name the page suggested (for {@link Mode#SAVE}),
     * or an empty string.
     * @return the suggested file name, never {@code null}
     */
    public String getInitialFileName() {
        return initialFileName;
    }

    /**
     * Returns the page's {@code accept} filters (MIME types like
     * {@code "image/*"} and extensions like {@code ".pdf"}), or an empty list.
     * @return an unmodifiable list of accept filters
     */
    public List<String> getAcceptFilters() {
        return acceptFilters;
    }

    /**
     * Returns whether this request has already been answered.
     * @return {@code true} once {@link #choose} or {@link #cancel} ran
     */
    public boolean isResponded() {
        return responded.get();
    }

    /**
     * Accepts the request with the given files and resumes the page. Passing no
     * files (or {@code null}) is treated as a cancel. Files are handed to the
     * engine by native path, so the browser streams them directly from disk —
     * arbitrarily large uploads are fine.
     * @param files the chosen files
     */
    public void choose(File... files) {
        choose(files == null ? null : List.of(files));
    }

    /**
     * Accepts the request with the given files and resumes the page. An empty or
     * {@code null} list is treated as a cancel.
     * @param files the chosen files
     */
    public void choose(List<File> files) {
        if (!responded.compareAndSet(false, true)) {
            return;
        }
        if (files == null || files.isEmpty()) {
            engine.respondFileChooser(id, new String[0]);
            return;
        }
        String[] paths = new String[files.size()];
        for (int i = 0; i < paths.length; i++) {
            paths[i] = files.get(i).getAbsolutePath();
        }
        engine.respondFileChooser(id, paths);
    }

    /**
     * Cancels the request (the control keeps its current value) and resumes the page.
     */
    public void cancel() {
        if (!responded.compareAndSet(false, true)) {
            return;
        }
        engine.respondFileChooser(id, new String[0]);
    }
}
