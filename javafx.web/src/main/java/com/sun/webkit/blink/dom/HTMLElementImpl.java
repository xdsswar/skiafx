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

import org.w3c.dom.html.HTMLElement;

import com.sun.webkit.blink.DomBridge;

/**
 * {@code org.w3c.dom.html.HTMLElement} — an {@link ElementImpl} with the core
 * HTML reflected attributes ({@code id}, {@code title}, {@code lang},
 * {@code dir}, {@code class}). This is the type {@code document.getBody()} and
 * {@code getElementById} hand back for HTML elements, so casts to
 * {@code HTMLElement} succeed as they did under the WebKit backend.
 */
final class HTMLElementImpl extends ElementImpl implements HTMLElement {

    HTMLElementImpl(DomBridge bridge, int id) {
        super(bridge, id);
    }

    @Override public String getId() {
        return getAttribute("id");
    }

    @Override public void setId(String id) {
        setAttribute("id", id);
    }

    @Override public String getTitle() {
        return getAttribute("title");
    }

    @Override public void setTitle(String title) {
        setAttribute("title", title);
    }

    @Override public String getLang() {
        return getAttribute("lang");
    }

    @Override public void setLang(String lang) {
        setAttribute("lang", lang);
    }

    @Override public String getDir() {
        return getAttribute("dir");
    }

    @Override public void setDir(String dir) {
        setAttribute("dir", dir);
    }

    @Override public String getClassName() {
        return getAttribute("class");
    }

    @Override public void setClassName(String className) {
        setAttribute("class", className);
    }
}
