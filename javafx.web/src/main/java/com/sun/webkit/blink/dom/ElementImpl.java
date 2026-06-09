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
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;
import org.w3c.dom.TypeInfo;

import com.sun.webkit.blink.DomBridge;

/**
 * {@code org.w3c.dom.Element} over the Blink DOM cache. Attribute getters read
 * the cache; setters write through and emit a DOM command. Namespace-aware
 * variants map onto their non-namespaced counterparts (sufficient for HTML;
 * full XML-namespace support is a follow-up).
 */
class ElementImpl extends NodeImpl implements Element {

    ElementImpl(DomBridge bridge, int id) {
        super(bridge, id);
    }

    @Override public short getNodeType() {
        return ELEMENT_NODE;
    }

    @Override public String getNodeName() {
        return getTagName();
    }

    @Override public String getTagName() {
        return bridge.tagName(id);
    }

    // ---- Attributes ----------------------------------------------------

    @Override public String getAttribute(String name) {
        String v = bridge.attr(id, name);
        return v == null ? "" : v; // W3C: missing attribute → empty string
    }

    @Override public void setAttribute(String name, String value) throws DOMException {
        bridge.setAttribute(id, name, value);
    }

    @Override public void removeAttribute(String name) throws DOMException {
        bridge.removeAttribute(id, name);
    }

    @Override public boolean hasAttribute(String name) {
        return bridge.hasAttr(id, name);
    }

    @Override public Attr getAttributeNode(String name) {
        return hasAttribute(name) ? new AttrImpl(bridge, id, name) : null;
    }

    @Override public NamedNodeMap getAttributes() {
        return new NamedNodeMapImpl(bridge, id);
    }

    @Override public boolean hasAttributes() {
        return bridge.attrNames(id).length > 0;
    }

    @Override public NodeList getElementsByTagName(String name) {
        return new NodeListImpl(bridge, bridge.getElementsByTagName(id, name));
    }

    // ---- Namespace variants (map onto non-namespaced) ------------------

    @Override public String getAttributeNS(String namespaceURI, String localName) {
        return getAttribute(localName);
    }

    @Override public void setAttributeNS(String namespaceURI, String qualifiedName,
                                         String value) throws DOMException {
        setAttribute(qualifiedName, value);
    }

    @Override public void removeAttributeNS(String namespaceURI, String localName)
            throws DOMException {
        removeAttribute(localName);
    }

    @Override public Attr getAttributeNodeNS(String namespaceURI, String localName) {
        return getAttributeNode(localName);
    }

    @Override public boolean hasAttributeNS(String namespaceURI, String localName) {
        return hasAttribute(localName);
    }

    @Override public NodeList getElementsByTagNameNS(String namespaceURI, String localName) {
        return getElementsByTagName(localName);
    }

    // ---- Attr nodes (limited) ------------------------------------------

    @Override public Attr setAttributeNode(Attr newAttr) throws DOMException {
        setAttribute(newAttr.getName(), newAttr.getValue());
        return null;
    }

    @Override public Attr removeAttributeNode(Attr oldAttr) throws DOMException {
        removeAttribute(oldAttr.getName());
        return oldAttr;
    }

    @Override public Attr setAttributeNodeNS(Attr newAttr) throws DOMException {
        return setAttributeNode(newAttr);
    }

    // ---- Type info / id attributes (not tracked) -----------------------

    @Override public TypeInfo getSchemaTypeInfo() {
        return null;
    }

    @Override public void setIdAttribute(String name, boolean isId) throws DOMException {
    }

    @Override public void setIdAttributeNS(String namespaceURI, String localName, boolean isId)
            throws DOMException {
    }

    @Override public void setIdAttributeNode(Attr idAttr, boolean isId) throws DOMException {
    }
}
