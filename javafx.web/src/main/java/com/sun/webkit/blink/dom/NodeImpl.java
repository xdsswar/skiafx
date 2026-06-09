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
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.UserDataHandler;
import org.w3c.dom.events.Event;
import org.w3c.dom.events.EventListener;
import org.w3c.dom.events.EventTarget;

import com.sun.webkit.blink.DomBridge;

/**
 * Base {@code org.w3c.dom.Node} + {@code EventTarget} implementation backed by
 * the out-of-process Blink DOM via {@link DomBridge}. Each wrapper holds only an
 * opaque node id and the bridge; all state lives in the bridge's local DOM cache
 * (so getters never round-trip the FX thread) and all mutations go through the
 * bridge (cache write-through + a fire-and-forget engine command).
 *
 * <p>Package-private; applications only ever see the {@code org.w3c.dom}
 * interface. The bridge reference is internal plumbing and never appears in any
 * exported signature.
 */
abstract class NodeImpl implements Node, EventTarget {

    final DomBridge bridge;
    final int id;

    NodeImpl(DomBridge bridge, int id) {
        this.bridge = bridge;
        this.id = id;
    }

    /** The engine/Java node id this wrapper stands for (package use only). */
    final int nodeId() {
        return id;
    }

    private Node wrap(int otherId) {
        return otherId == DomBridge.NO_NODE ? null : bridge.getOrCreateWrapper(otherId);
    }

    // ---- Identity ------------------------------------------------------

    @Override public abstract short getNodeType();
    @Override public abstract String getNodeName();

    @Override public boolean isSameNode(Node other) {
        return other instanceof NodeImpl n && n.bridge == bridge && n.id == id;
    }

    @Override public boolean equals(Object o) {
        return o instanceof NodeImpl n && n.bridge == bridge && n.id == id;
    }

    @Override public int hashCode() {
        return id;
    }

    // ---- Values --------------------------------------------------------

    @Override public String getNodeValue() throws DOMException {
        return null;
    }

    @Override public void setNodeValue(String nodeValue) throws DOMException {
        // Overridden by character-data nodes.
    }

    @Override public String getTextContent() throws DOMException {
        StringBuilder sb = new StringBuilder();
        collectText(id, sb);
        return sb.toString();
    }

    private void collectText(int nodeId, StringBuilder sb) {
        if (bridge.isText(nodeId)) {
            sb.append(bridge.textOf(nodeId));
            return;
        }
        for (int childId : bridge.childIds(nodeId)) {
            collectText(childId, sb);
        }
    }

    @Override public void setTextContent(String textContent) throws DOMException {
        bridge.setTextContent(id, textContent);
    }

    // ---- Tree navigation -----------------------------------------------

    @Override public Node getParentNode() {
        int pid = bridge.parentId(id);
        if (pid == DomBridge.NO_NODE) {
            // A document-level root's DOM parent is the Document itself.
            if (id == bridge.rootElementId()) {
                return getOwnerDocument();
            }
            return null;
        }
        return wrap(pid);
    }

    @Override public NodeList getChildNodes() {
        return new NodeListImpl(bridge, bridge.childIds(id));
    }

    @Override public Node getFirstChild() {
        return wrap(bridge.firstChild(id));
    }

    @Override public Node getLastChild() {
        return wrap(bridge.lastChild(id));
    }

    @Override public Node getNextSibling() {
        return wrap(bridge.nextSibling(id));
    }

    @Override public Node getPreviousSibling() {
        return wrap(bridge.previousSibling(id));
    }

    @Override public boolean hasChildNodes() {
        return bridge.firstChild(id) != DomBridge.NO_NODE;
    }

    @Override public Document getOwnerDocument() {
        Node doc = bridge.document();
        return (doc instanceof Document d) ? d : null;
    }

    // ---- Tree mutation -------------------------------------------------

    @Override public Node appendChild(Node newChild) throws DOMException {
        int childId = requireOwned(newChild);
        bridge.appendChild(id, childId);
        return newChild;
    }

    @Override public Node insertBefore(Node newChild, Node refChild) throws DOMException {
        int childId = requireOwned(newChild);
        int refId = refChild instanceof NodeImpl r ? r.id : DomBridge.NO_NODE;
        bridge.insertBefore(id, childId, refId);
        return newChild;
    }

    @Override public Node removeChild(Node oldChild) throws DOMException {
        int childId = requireOwned(oldChild);
        bridge.removeChild(id, childId);
        return oldChild;
    }

    @Override public Node replaceChild(Node newChild, Node oldChild) throws DOMException {
        int newId = requireOwned(newChild);
        int oldId = requireOwned(oldChild);
        bridge.insertBefore(id, newId, oldId);
        bridge.removeChild(id, oldId);
        return oldChild;
    }

    private int requireOwned(Node n) throws DOMException {
        if (!(n instanceof NodeImpl ni) || ni.bridge != bridge) {
            throw new DOMException(DOMException.WRONG_DOCUMENT_ERR,
                "node belongs to a different document");
        }
        return ni.id;
    }

    @Override public Node cloneNode(boolean deep) {
        // Shallow element clone (deep clone is a follow-up). Non-elements clone
        // to themselves' shape via the document factory where supported.
        return this;
    }

    @Override public void normalize() {
        // No-op: the live Blink document is already normalized for our purposes.
    }

    // ---- Attributes (overridden by ElementImpl) ------------------------

    @Override public NamedNodeMap getAttributes() {
        return null;
    }

    @Override public boolean hasAttributes() {
        return false;
    }

    // ---- Namespaces / misc (minimal) -----------------------------------

    @Override public String getNamespaceURI() {
        return null;
    }

    @Override public String getPrefix() {
        return null;
    }

    @Override public void setPrefix(String prefix) throws DOMException {
    }

    @Override public String getLocalName() {
        return null;
    }

    @Override public String getBaseURI() {
        return null;
    }

    @Override public boolean isSupported(String feature, String version) {
        return false;
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

    @Override public boolean isEqualNode(Node arg) {
        return isSameNode(arg);
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

    // ---- EventTarget ---------------------------------------------------

    @Override public void addEventListener(String type, EventListener listener,
                                           boolean useCapture) {
        bridge.addEventListener(id, type, listener, useCapture);
    }

    @Override public void removeEventListener(String type, EventListener listener,
                                              boolean useCapture) {
        bridge.removeEventListener(id, type, listener, useCapture);
    }

    @Override public boolean dispatchEvent(Event evt) {
        // Programmatic dispatch into Blink is limited to a synthesized click in
        // this slice (the most common app-driven case); other synthetic types
        // are a documented follow-up.
        if (evt != null && "click".equals(evt.getType())) {
            bridge.domClick(id);
            return true;
        }
        return false;
    }
}
