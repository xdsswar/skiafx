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
import org.w3c.dom.NodeList;

import com.sun.webkit.blink.DomBridge;

/**
 * Snapshot {@code org.w3c.dom.NodeList} over an array of node ids resolved to
 * wrappers lazily. The id array is captured when the list is created (e.g. from
 * {@code getChildNodes()} / {@code getElementsByTagName()}); each {@code item()}
 * resolves to the single canonical wrapper for that id.
 */
final class NodeListImpl implements NodeList {

    private final DomBridge bridge;
    private final int[] ids;

    NodeListImpl(DomBridge bridge, int[] ids) {
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
}
