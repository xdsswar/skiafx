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

import org.w3c.dom.Node;
import org.w3c.dom.html.HTMLCollection;

import com.sun.webkit.blink.DomBridge;

/**
 * Snapshot {@code org.w3c.dom.html.HTMLCollection} over an array of element ids
 * (used by {@code HTMLDocument.getImages()}, {@code getForms()}, …).
 * {@code namedItem} matches by {@code id} first, then {@code name}.
 */
final class HTMLCollectionImpl implements HTMLCollection {

    private final DomBridge bridge;
    private final int[] ids;

    HTMLCollectionImpl(DomBridge bridge, int[] ids) {
        this.bridge = bridge;
        this.ids = ids;
    }

    @Override public Node item(int index) {
        if (index < 0 || index >= ids.length) {
            return null;
        }
        return bridge.getOrCreateWrapper(ids[index]);
    }

    @Override public int getLength() {
        return ids.length;
    }

    @Override public Node namedItem(String name) {
        if (name == null) {
            return null;
        }
        for (int id : ids) {
            if (name.equals(bridge.attr(id, "id"))) {
                return bridge.getOrCreateWrapper(id);
            }
        }
        for (int id : ids) {
            if (name.equals(bridge.attr(id, "name"))) {
                return bridge.getOrCreateWrapper(id);
            }
        }
        return null;
    }
}
