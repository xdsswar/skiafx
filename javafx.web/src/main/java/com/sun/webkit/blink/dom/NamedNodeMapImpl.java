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

import org.w3c.dom.Attr;
import org.w3c.dom.DOMException;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import com.sun.webkit.blink.DomBridge;

/**
 * {@code org.w3c.dom.NamedNodeMap} over an element's attributes. Each entry is
 * an {@link AttrImpl}; the attribute name set is snapshotted at creation so
 * {@code item(int)} indexing is stable for a single iteration.
 */
final class NamedNodeMapImpl implements NamedNodeMap {

    private final DomBridge bridge;
    private final int ownerId;
    private final String[] names;

    NamedNodeMapImpl(DomBridge bridge, int ownerId) {
        this.bridge = bridge;
        this.ownerId = ownerId;
        this.names = bridge.attrNames(ownerId);
    }

    @Override public Node getNamedItem(String name) {
        return bridge.hasAttr(ownerId, name) ? new AttrImpl(bridge, ownerId, name) : null;
    }

    @Override public Node item(int index) {
        if (index < 0 || index >= names.length) {
            return null;
        }
        return new AttrImpl(bridge, ownerId, names[index]);
    }

    @Override public int getLength() {
        return names.length;
    }

    @Override public Node setNamedItem(Node arg) throws DOMException {
        if (arg instanceof Attr a) {
            bridge.setAttribute(ownerId, a.getName(), a.getValue());
        }
        return null;
    }

    @Override public Node removeNamedItem(String name) throws DOMException {
        Node prev = getNamedItem(name);
        if (prev == null) {
            throw new DOMException(DOMException.NOT_FOUND_ERR, name);
        }
        bridge.removeAttribute(ownerId, name);
        return prev;
    }

    // Namespace variants map onto the non-namespaced forms (HTML attributes).

    @Override public Node getNamedItemNS(String namespaceURI, String localName) {
        return getNamedItem(localName);
    }

    @Override public Node setNamedItemNS(Node arg) throws DOMException {
        return setNamedItem(arg);
    }

    @Override public Node removeNamedItemNS(String namespaceURI, String localName)
            throws DOMException {
        return removeNamedItem(localName);
    }
}
