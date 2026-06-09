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

import org.w3c.dom.CharacterData;
import org.w3c.dom.DOMException;

import com.sun.webkit.blink.DomBridge;

/**
 * {@code org.w3c.dom.CharacterData} over a Blink text node. The data is read
 * from the bridge cache; edits recompute the string and write it back through
 * {@code setTextContent}.
 */
abstract class CharacterDataImpl extends NodeImpl implements CharacterData {

    CharacterDataImpl(DomBridge bridge, int id) {
        super(bridge, id);
    }

    @Override public String getData() throws DOMException {
        return bridge.textOf(id);
    }

    @Override public void setData(String data) throws DOMException {
        bridge.setTextContent(id, data == null ? "" : data);
    }

    @Override public String getNodeValue() throws DOMException {
        return getData();
    }

    @Override public void setNodeValue(String nodeValue) throws DOMException {
        setData(nodeValue);
    }

    @Override public int getLength() {
        return getData().length();
    }

    @Override public String substringData(int offset, int count) throws DOMException {
        String d = getData();
        if (offset < 0 || count < 0 || offset > d.length()) {
            throw new DOMException(DOMException.INDEX_SIZE_ERR, "bad range");
        }
        int end = Math.min(offset + count, d.length());
        return d.substring(offset, end);
    }

    @Override public void appendData(String arg) throws DOMException {
        setData(getData() + (arg == null ? "" : arg));
    }

    @Override public void insertData(int offset, String arg) throws DOMException {
        String d = getData();
        if (offset < 0 || offset > d.length()) {
            throw new DOMException(DOMException.INDEX_SIZE_ERR, "bad offset");
        }
        setData(d.substring(0, offset) + (arg == null ? "" : arg) + d.substring(offset));
    }

    @Override public void deleteData(int offset, int count) throws DOMException {
        String d = getData();
        if (offset < 0 || count < 0 || offset > d.length()) {
            throw new DOMException(DOMException.INDEX_SIZE_ERR, "bad range");
        }
        int end = Math.min(offset + count, d.length());
        setData(d.substring(0, offset) + d.substring(end));
    }

    @Override public void replaceData(int offset, int count, String arg) throws DOMException {
        deleteData(offset, count);
        insertData(offset, arg);
    }
}
