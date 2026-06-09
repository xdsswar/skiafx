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
 * The page context of the most recent right-click on a {@link WebView}: the
 * selected text, the link / image under the cursor, and whether the click landed
 * on an editable element. Surfaced so an application can build a contextual menu.
 *
 * <p>The {@code WebView} shows a single, reused context menu that it owns. Read
 * this context from {@link WebEngine#getContextMenuContext()} inside a
 * {@link WebEngine#setContextMenuCustomizer(java.util.function.Consumer) context-menu
 * customizer}, which is handed that menu (already filled with the default items) so
 * the application can add or remove {@link javafx.scene.control.MenuItem}s. Because
 * the page renders off-screen, the menu is shown only after the engine reports the
 * hit context, so this object is already populated when the customizer runs.
 *
 * <p>Example:
 * <pre>{@code
 * webEngine.setContextMenuCustomizer(menu -> {
 *     ContextMenuContext ctx = webEngine.getContextMenuContext();
 *     if (!ctx.getLinkUrl().isEmpty()) {
 *         MenuItem open = new MenuItem("Open in new window");
 *         open.setOnAction(a -> openExternally(ctx.getLinkUrl()));
 *         menu.getItems().add(open);
 *     }
 * });
 * }</pre>
 *
 * @since 25
 */
public final class ContextMenuContext {

    private final boolean editable;
    private final String selectedText;
    private final String linkUrl;
    private final String imageUrl;

    ContextMenuContext(boolean editable, String selectedText,
                       String linkUrl, String imageUrl) {
        this.editable = editable;
        this.selectedText = selectedText == null ? "" : selectedText;
        this.linkUrl = linkUrl == null ? "" : linkUrl;
        this.imageUrl = imageUrl == null ? "" : imageUrl;
    }

    /**
     * Returns the selected text at the click, or an empty string if none.
     * @return the selected text (never {@code null})
     */
    public String getSelectedText() {
        return selectedText;
    }

    /**
     * Returns the href of the link under the cursor, or an empty string if none.
     * @return the link URL (never {@code null})
     */
    public String getLinkUrl() {
        return linkUrl;
    }

    /**
     * Returns the source URL of the image / media under the cursor, or an empty
     * string if none.
     * @return the image URL (never {@code null})
     */
    public String getImageUrl() {
        return imageUrl;
    }

    /**
     * Returns whether the click was over an editable element (an input, textarea,
     * or {@code contenteditable} node).
     * @return {@code true} if the target is editable
     */
    public boolean isEditable() {
        return editable;
    }
}
