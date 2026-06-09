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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.geometry.Rectangle2D;

/**
 * A request to show the drop-down of an HTML {@code <select>} element. Because
 * the {@link WebView} is rendered off-screen, the browser engine has no native
 * popup surface, so the request is surfaced to the application through
 * {@link WebEngine#selectPopupHandlerProperty() selectPopupHandler}. The
 * application presents its own list UI anchored at {@link #getAnchorBounds()} and
 * answers with {@link #select(int...)} or {@link #cancel()}.
 *
 * <p>The page is suspended until answered, so the application may respond
 * asynchronously. The request is <b>single-shot</b>.
 *
 * @since 25
 */
public final class SelectPopup {

    private final WebEngine engine;
    private final int id;
    private final List<SelectItem> items;
    private final int selectedIndex;
    private final boolean multiple;
    private final Rectangle2D anchorBounds;
    private final AtomicBoolean responded = new AtomicBoolean();

    SelectPopup(WebEngine engine, int id, List<SelectItem> items, int selectedIndex,
                boolean multiple, Rectangle2D anchorBounds) {
        this.engine = engine;
        this.id = id;
        this.items = items == null ? List.of() : Collections.unmodifiableList(items);
        this.selectedIndex = selectedIndex;
        this.multiple = multiple;
        this.anchorBounds = anchorBounds == null ? Rectangle2D.EMPTY : anchorBounds;
    }

    /**
     * Returns the {@code WebEngine} whose page raised this request.
     * @return the source engine
     */
    public WebEngine getSource() {
        return engine;
    }

    /**
     * Returns the options to display, in document order.
     * @return an unmodifiable list of options
     */
    public List<SelectItem> getItems() {
        return items;
    }

    /**
     * Returns the index of the currently selected option, or {@code -1} if none.
     * @return the selected index
     */
    public int getSelectedIndex() {
        return selectedIndex;
    }

    /**
     * Returns whether the {@code <select>} allows multiple selection.
     * @return {@code true} for a multi-select control
     */
    public boolean isMultiple() {
        return multiple;
    }

    /**
     * Returns the control's bounds in the {@link WebView}'s local coordinate
     * space, so the application can anchor its popup over the element.
     * @return the anchor bounds
     */
    public Rectangle2D getAnchorBounds() {
        return anchorBounds;
    }

    /**
     * Returns whether this request has already been answered.
     * @return {@code true} once {@link #select(int...)} or {@link #cancel()} ran
     */
    public boolean isResponded() {
        return responded.get();
    }

    /**
     * Commits the given option indices and resumes the page. For a single-select
     * control only the first index is used; an empty array is treated as a cancel.
     * @param indices the chosen option indices
     */
    public void select(int... indices) {
        if (!responded.compareAndSet(false, true)) {
            return;
        }
        if (indices == null || indices.length == 0) {
            engine.respondSelectPopup(id, false, new int[0]);
        } else {
            engine.respondSelectPopup(id, true, indices.clone());
        }
    }

    /**
     * Dismisses the drop-down without changing the selection and resumes the page.
     */
    public void cancel() {
        if (!responded.compareAndSet(false, true)) {
            return;
        }
        engine.respondSelectPopup(id, false, new int[0]);
    }
}
