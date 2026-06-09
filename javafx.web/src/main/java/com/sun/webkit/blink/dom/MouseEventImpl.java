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
package com.sun.webkit.blink.dom;

import org.w3c.dom.events.EventTarget;
import org.w3c.dom.events.MouseEvent;
import org.w3c.dom.views.AbstractView;

/**
 * {@code org.w3c.dom.events.MouseEvent} carrying the client coordinates and
 * button index decoded from the engine's interaction payload
 * ({@code [x:f32][y:f32][button:4]}). Modifier-key state and screen coordinates
 * are not yet forwarded by the engine (a follow-up); they read as {@code 0}/
 * {@code false}.
 */
final class MouseEventImpl extends EventImpl implements MouseEvent {

    private final int clientX;
    private final int clientY;
    private final short button;

    MouseEventImpl(String type, EventTarget target, float x, float y, int button) {
        super(type, target);
        this.clientX = Math.round(x);
        this.clientY = Math.round(y);
        this.button = (short) button;
    }

    @Override public int getClientX() {
        return clientX;
    }

    @Override public int getClientY() {
        return clientY;
    }

    @Override public int getScreenX() {
        return clientX;
    }

    @Override public int getScreenY() {
        return clientY;
    }

    @Override public short getButton() {
        return button;
    }

    @Override public boolean getCtrlKey() {
        return false;
    }

    @Override public boolean getShiftKey() {
        return false;
    }

    @Override public boolean getAltKey() {
        return false;
    }

    @Override public boolean getMetaKey() {
        return false;
    }

    @Override public EventTarget getRelatedTarget() {
        return null;
    }

    // ---- UIEvent -------------------------------------------------------

    @Override public AbstractView getView() {
        return null;
    }

    @Override public int getDetail() {
        return 0;
    }

    @Override public void initUIEvent(String typeArg, boolean canBubbleArg,
            boolean cancelableArg, AbstractView viewArg, int detailArg) {
    }

    @Override public void initMouseEvent(String typeArg, boolean canBubbleArg,
            boolean cancelableArg, AbstractView viewArg, int detailArg,
            int screenXArg, int screenYArg, int clientXArg, int clientYArg,
            boolean ctrlKeyArg, boolean altKeyArg, boolean shiftKeyArg,
            boolean metaKeyArg, short buttonArg, EventTarget relatedTargetArg) {
    }
}
