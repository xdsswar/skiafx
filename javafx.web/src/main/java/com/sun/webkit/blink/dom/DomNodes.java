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

import java.util.Set;

import org.w3c.dom.Node;
import org.w3c.dom.events.Event;
import org.w3c.dom.events.EventTarget;

import com.sun.webkit.blink.DomBridge;

/**
 * Factory bridging {@link DomBridge} (which knows only node ids and kinds) to the
 * concrete {@code org.w3c.dom} wrapper classes in this package. Kept here so the
 * blink package never needs to reference the individual impl types.
 *
 * <p>Public only so {@code com.sun.webkit.blink.DomBridge} can call it; the
 * package is not exported, so nothing here escapes the module.
 */
public final class DomNodes {

    /** Node-kind discriminators passed by the bridge. */
    public static final int KIND_ELEMENT = 0;
    public static final int KIND_TEXT = 1;
    public static final int KIND_FRAGMENT = 2;

    private static final Set<String> MOUSE_TYPES = Set.of(
        "click", "dblclick", "mousedown", "mouseup", "mousemove",
        "mouseenter", "mouseleave", "mouseover", "mouseout", "contextmenu");

    private DomNodes() {
    }

    /** Builds the wrapper for a node id of the given kind. */
    public static Node create(DomBridge bridge, int nodeId, int kind) {
        return switch (kind) {
            case KIND_TEXT -> new TextImpl(bridge, nodeId);
            case KIND_FRAGMENT -> new DocumentFragmentImpl(bridge, nodeId);
            default -> new HTMLElementImpl(bridge, nodeId);
        };
    }

    /** Builds the single {@code Document} wrapper for the current page. */
    public static Node createDocument(DomBridge bridge) {
        return new DocumentImpl(bridge);
    }

    /**
     * Builds the {@code Event} delivered to Java listeners for a fired engine
     * interaction event. Mouse-family types carry coordinates + button.
     */
    public static Event createEvent(String domType, Node target, float x, float y, int button) {
        EventTarget t = target instanceof EventTarget et ? et : null;
        if (MOUSE_TYPES.contains(domType)) {
            return new MouseEventImpl(domType, t, x, y, button);
        }
        return new EventImpl(domType, t);
    }
}
