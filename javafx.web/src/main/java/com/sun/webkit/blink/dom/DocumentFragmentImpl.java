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

import org.w3c.dom.DocumentFragment;

import com.sun.webkit.blink.DomBridge;

/**
 * {@code org.w3c.dom.DocumentFragment} — a lightweight container node. Children
 * appended here are realized into the live tree when the fragment is appended to
 * a connected node (the bridge flushes the queued children).
 */
final class DocumentFragmentImpl extends NodeImpl implements DocumentFragment {

    DocumentFragmentImpl(DomBridge bridge, int id) {
        super(bridge, id);
    }

    @Override public short getNodeType() {
        return DOCUMENT_FRAGMENT_NODE;
    }

    @Override public String getNodeName() {
        return "#document-fragment";
    }
}
