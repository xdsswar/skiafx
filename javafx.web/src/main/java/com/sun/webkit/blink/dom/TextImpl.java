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

import org.w3c.dom.DOMException;
import org.w3c.dom.Text;

import com.sun.webkit.blink.DomBridge;

/**
 * {@code org.w3c.dom.Text} over a Blink text node. {@code splitText} and the
 * whole-text helpers are limited (the live document keeps text runs coalesced);
 * the common case — reading and replacing text data — is fully supported.
 */
final class TextImpl extends CharacterDataImpl implements Text {

    TextImpl(DomBridge bridge, int id) {
        super(bridge, id);
    }

    @Override public short getNodeType() {
        return TEXT_NODE;
    }

    @Override public String getNodeName() {
        return "#text";
    }

    @Override public Text splitText(int offset) throws DOMException {
        // The live Blink node is not actually split (a follow-up); we return a
        // detached text node carrying the tail so callers still get the data.
        String d = getData();
        if (offset < 0 || offset > d.length()) {
            throw new DOMException(DOMException.INDEX_SIZE_ERR, "bad offset");
        }
        String tail = d.substring(offset);
        setData(d.substring(0, offset));
        int tailId = bridge.createTextNode(tail);
        return (Text) bridge.getOrCreateWrapper(tailId);
    }

    @Override public boolean isElementContentWhitespace() {
        return getData().isBlank();
    }

    @Override public String getWholeText() {
        return getData();
    }

    @Override public Text replaceWholeText(String content) throws DOMException {
        setData(content);
        return this;
    }
}
