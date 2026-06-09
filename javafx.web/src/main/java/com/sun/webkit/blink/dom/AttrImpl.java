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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.TypeInfo;
import org.w3c.dom.UserDataHandler;

import com.sun.webkit.blink.DomBridge;

/**
 * {@code org.w3c.dom.Attr} backed by (owner element id, attribute name). An Attr
 * is not part of the node tree, so the inherited tree-navigation methods return
 * {@code null}/empty per the DOM spec. Value reads/writes go through the bridge.
 */
final class AttrImpl implements Attr {

    private final DomBridge bridge;
    private final int ownerId;
    private final String name;

    AttrImpl(DomBridge bridge, int ownerId, String name) {
        this.bridge = bridge;
        this.ownerId = ownerId;
        this.name = name;
    }

    // ---- Attr ----------------------------------------------------------

    @Override public String getName() {
        return name;
    }

    @Override public boolean getSpecified() {
        return bridge.hasAttr(ownerId, name);
    }

    @Override public String getValue() {
        String v = bridge.attr(ownerId, name);
        return v == null ? "" : v;
    }

    @Override public void setValue(String value) throws DOMException {
        bridge.setAttribute(ownerId, name, value);
    }

    @Override public Element getOwnerElement() {
        Node n = bridge.getOrCreateWrapper(ownerId);
        return n instanceof Element e ? e : null;
    }

    @Override public TypeInfo getSchemaTypeInfo() {
        return null;
    }

    @Override public boolean isId() {
        return "id".equalsIgnoreCase(name);
    }

    // ---- Node ----------------------------------------------------------

    @Override public String getNodeName() {
        return name;
    }

    @Override public short getNodeType() {
        return ATTRIBUTE_NODE;
    }

    @Override public String getNodeValue() {
        return getValue();
    }

    @Override public void setNodeValue(String nodeValue) throws DOMException {
        setValue(nodeValue);
    }

    @Override public String getTextContent() {
        return getValue();
    }

    @Override public void setTextContent(String textContent) throws DOMException {
        setValue(textContent);
    }

    @Override public Document getOwnerDocument() {
        Node doc = bridge.document();
        return doc instanceof Document d ? d : null;
    }

    @Override public boolean isSameNode(Node other) {
        return other instanceof AttrImpl a
            && a.bridge == bridge && a.ownerId == ownerId && a.name.equals(name);
    }

    @Override public boolean isEqualNode(Node arg) {
        return isSameNode(arg);
    }

    @Override public boolean equals(Object o) {
        return isSameNode(o instanceof Node n ? n : null);
    }

    @Override public int hashCode() {
        return ownerId * 31 + name.hashCode();
    }

    // ---- Node tree members: an Attr is not in the tree -----------------

    @Override public Node getParentNode() {
        return null;
    }

    @Override public NodeList getChildNodes() {
        return new NodeListImpl(bridge, new int[0]);
    }

    @Override public Node getFirstChild() {
        return null;
    }

    @Override public Node getLastChild() {
        return null;
    }

    @Override public Node getPreviousSibling() {
        return null;
    }

    @Override public Node getNextSibling() {
        return null;
    }

    @Override public NamedNodeMap getAttributes() {
        return null;
    }

    @Override public boolean hasChildNodes() {
        return false;
    }

    @Override public boolean hasAttributes() {
        return false;
    }

    @Override public Node insertBefore(Node n, Node r) throws DOMException {
        throw notSupported();
    }

    @Override public Node replaceChild(Node n, Node o) throws DOMException {
        throw notSupported();
    }

    @Override public Node removeChild(Node o) throws DOMException {
        throw notSupported();
    }

    @Override public Node appendChild(Node n) throws DOMException {
        throw notSupported();
    }

    @Override public Node cloneNode(boolean deep) {
        return new AttrImpl(bridge, ownerId, name);
    }

    @Override public void normalize() {
    }

    @Override public boolean isSupported(String feature, String version) {
        return false;
    }

    @Override public String getNamespaceURI() {
        return null;
    }

    @Override public String getPrefix() {
        return null;
    }

    @Override public void setPrefix(String prefix) throws DOMException {
    }

    @Override public String getLocalName() {
        return name;
    }

    @Override public String getBaseURI() {
        return null;
    }

    @Override public short compareDocumentPosition(Node other) throws DOMException {
        return 0;
    }

    @Override public String lookupPrefix(String namespaceURI) {
        return null;
    }

    @Override public boolean isDefaultNamespace(String namespaceURI) {
        return false;
    }

    @Override public String lookupNamespaceURI(String prefix) {
        return null;
    }

    @Override public Object getFeature(String feature, String version) {
        return null;
    }

    @Override public Object setUserData(String key, Object data, UserDataHandler handler) {
        return null;
    }

    @Override public Object getUserData(String key) {
        return null;
    }

    private static DOMException notSupported() {
        return new DOMException(DOMException.NO_MODIFICATION_ALLOWED_ERR,
            "attribute nodes have no children");
    }
}
