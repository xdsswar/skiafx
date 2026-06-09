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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.w3c.dom.Attr;
import org.w3c.dom.CDATASection;
import org.w3c.dom.Comment;
import org.w3c.dom.DOMConfiguration;
import org.w3c.dom.DOMException;
import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.EntityReference;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ProcessingInstruction;
import org.w3c.dom.Text;
import org.w3c.dom.html.HTMLCollection;
import org.w3c.dom.html.HTMLDocument;
import org.w3c.dom.html.HTMLElement;

import com.sun.webkit.blink.DomBridge;

/**
 * {@code org.w3c.dom.html.HTMLDocument} root over the Blink DOM cache. Returned
 * by {@code WebEngine.getDocument()}. Implements {@code HTMLDocument} (not just
 * {@code Document}) so {@code HTMLEditorSkin}'s cast keeps working.
 *
 * <p>The document itself has no engine node; its single child is the document
 * element ({@code <html>}). XML-only factory methods that the DOM spec requires
 * {@code HTMLDocument} to reject throw {@code NOT_SUPPORTED_ERR}, matching a
 * standard HTML document.
 */
final class DocumentImpl extends NodeImpl implements HTMLDocument {

    /** Sentinel id for the document node (never assigned to a real Blink node). */
    static final int DOCUMENT_ID = -1;

    DocumentImpl(DomBridge bridge) {
        super(bridge, DOCUMENT_ID);
    }

    // ---- Node basics ---------------------------------------------------

    @Override public short getNodeType() {
        return DOCUMENT_NODE;
    }

    @Override public String getNodeName() {
        return "#document";
    }

    @Override public Node getParentNode() {
        return null;
    }

    @Override public Document getOwnerDocument() {
        return null; // a document has no owner document
    }

    @Override public Node getFirstChild() {
        return getDocumentElement();
    }

    @Override public Node getLastChild() {
        return getDocumentElement();
    }

    @Override public NodeList getChildNodes() {
        int root = bridge.rootElementId();
        return new NodeListImpl(bridge, root == DomBridge.NO_NODE
            ? new int[0] : new int[] { root });
    }

    @Override public boolean hasChildNodes() {
        return bridge.rootElementId() != DomBridge.NO_NODE;
    }

    @Override public String getTextContent() {
        Element de = getDocumentElement();
        return de == null ? "" : de.getTextContent();
    }

    // ---- Document tree -------------------------------------------------

    @Override public Element getDocumentElement() {
        int root = bridge.rootElementId();
        return root == DomBridge.NO_NODE ? null : (Element) bridge.getOrCreateWrapper(root);
    }

    @Override public Element getElementById(String elementId) {
        int found = bridge.getElementById(elementId);
        return found == DomBridge.NO_NODE ? null : (Element) bridge.getOrCreateWrapper(found);
    }

    @Override public NodeList getElementsByTagName(String tagname) {
        return new NodeListImpl(bridge, elementsByTag(tagname));
    }

    @Override public NodeList getElementsByTagNameNS(String namespaceURI, String localName) {
        return getElementsByTagName(localName);
    }

    /** Document-scoped element search, inclusive of the document element. */
    private int[] elementsByTag(String tag) {
        int root = bridge.rootElementId();
        if (root == DomBridge.NO_NODE) {
            return new int[0];
        }
        boolean all = tag == null || "*".equals(tag);
        String wantUpper = all ? null : tag.toUpperCase(Locale.ROOT);
        List<Integer> out = new ArrayList<>();
        if (wantUpper == null || wantUpper.equals(bridge.tagName(root))) {
            out.add(root);
        }
        for (int id : bridge.getElementsByTagName(root, tag)) {
            out.add(id);
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = out.get(i);
        }
        return r;
    }

    // ---- Factories -----------------------------------------------------

    @Override public Element createElement(String tagName) throws DOMException {
        int newId = bridge.createElement(tagName);
        return (Element) bridge.getOrCreateWrapper(newId);
    }

    @Override public Element createElementNS(String namespaceURI, String qualifiedName)
            throws DOMException {
        return createElement(localName(qualifiedName));
    }

    @Override public Text createTextNode(String data) {
        int newId = bridge.createTextNode(data);
        return (Text) bridge.getOrCreateWrapper(newId);
    }

    @Override public DocumentFragment createDocumentFragment() {
        int newId = bridge.createFragment();
        return (DocumentFragment) bridge.getOrCreateWrapper(newId);
    }

    @Override public Attr createAttribute(String name) throws DOMException {
        return new AttrImpl(bridge, DomBridge.NO_NODE, name);
    }

    @Override public Attr createAttributeNS(String namespaceURI, String qualifiedName)
            throws DOMException {
        return createAttribute(localName(qualifiedName));
    }

    @Override public Comment createComment(String data) {
        throw notSupported("createComment");
    }

    @Override public CDATASection createCDATASection(String data) throws DOMException {
        throw notSupported("createCDATASection"); // HTML documents reject CDATA
    }

    @Override public ProcessingInstruction createProcessingInstruction(String target, String data)
            throws DOMException {
        throw notSupported("createProcessingInstruction"); // HTML documents reject PIs
    }

    @Override public EntityReference createEntityReference(String name) throws DOMException {
        throw notSupported("createEntityReference"); // HTML documents reject entity refs
    }

    @Override public Node importNode(Node importedNode, boolean deep) throws DOMException {
        if (importedNode instanceof NodeImpl ni && ni.bridge == bridge) {
            return importedNode; // already ours
        }
        if (importedNode instanceof Element e) {
            return createElement(e.getTagName());
        }
        if (importedNode instanceof Text t) {
            return createTextNode(t.getData());
        }
        throw notSupported("importNode for node type " + importedNode.getNodeType());
    }

    @Override public Node adoptNode(Node source) throws DOMException {
        return source;
    }

    @Override public Node renameNode(Node n, String namespaceURI, String qualifiedName) {
        return n;
    }

    // ---- HTMLDocument --------------------------------------------------

    @Override public String getTitle() {
        int[] titles = elementsByTag("title");
        if (titles.length == 0) {
            return "";
        }
        Node t = bridge.getOrCreateWrapper(titles[0]);
        return t == null ? "" : t.getTextContent();
    }

    @Override public void setTitle(String title) {
        int[] titles = elementsByTag("title");
        if (titles.length > 0) {
            bridge.setTextContent(titles[0], title);
        }
    }

    @Override public HTMLElement getBody() {
        int[] bodies = elementsByTag("body");
        return bodies.length == 0 ? null : (HTMLElement) bridge.getOrCreateWrapper(bodies[0]);
    }

    @Override public void setBody(HTMLElement body) {
        // Replacing the whole <body> is uncommon; not wired in this slice.
    }

    @Override public String getReferrer() {
        return "";
    }

    @Override public String getDomain() {
        return "";
    }

    @Override public String getURL() {
        return null;
    }

    @Override public String getCookie() {
        return "";
    }

    @Override public void setCookie(String cookie) throws DOMException {
    }

    @Override public HTMLCollection getImages() {
        return new HTMLCollectionImpl(bridge, elementsByTag("img"));
    }

    @Override public HTMLCollection getApplets() {
        return new HTMLCollectionImpl(bridge, elementsByTag("applet"));
    }

    @Override public HTMLCollection getLinks() {
        return new HTMLCollectionImpl(bridge, elementsByTag("a"));
    }

    @Override public HTMLCollection getForms() {
        return new HTMLCollectionImpl(bridge, elementsByTag("form"));
    }

    @Override public HTMLCollection getAnchors() {
        return new HTMLCollectionImpl(bridge, elementsByTag("a"));
    }

    @Override public NodeList getElementsByName(String elementName) {
        List<Integer> out = new ArrayList<>();
        if (elementName != null) {
            for (int id : elementsByTag("*")) {
                if (elementName.equals(bridge.attr(id, "name"))) {
                    out.add(id);
                }
            }
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = out.get(i);
        }
        return new NodeListImpl(bridge, r);
    }

    @Override public void open() {
    }

    @Override public void close() {
    }

    @Override public void write(String text) {
    }

    @Override public void writeln(String text) {
    }

    // ---- DOM metadata (mostly fixed for an HTML document) --------------

    @Override public DocumentType getDoctype() {
        return null;
    }

    @Override public DOMImplementation getImplementation() {
        return MINIMAL_IMPL;
    }

    @Override public String getInputEncoding() {
        return "UTF-8";
    }

    @Override public String getXmlEncoding() {
        return null;
    }

    @Override public boolean getXmlStandalone() {
        return false;
    }

    @Override public void setXmlStandalone(boolean xmlStandalone) throws DOMException {
    }

    @Override public String getXmlVersion() {
        return "1.0";
    }

    @Override public void setXmlVersion(String xmlVersion) throws DOMException {
    }

    @Override public boolean getStrictErrorChecking() {
        return true;
    }

    @Override public void setStrictErrorChecking(boolean strictErrorChecking) {
    }

    @Override public String getDocumentURI() {
        return null;
    }

    @Override public void setDocumentURI(String documentURI) {
    }

    @Override public DOMConfiguration getDomConfig() {
        return null;
    }

    @Override public void normalizeDocument() {
    }

    private static String localName(String qualifiedName) {
        if (qualifiedName == null) {
            return null;
        }
        int c = qualifiedName.indexOf(':');
        return c < 0 ? qualifiedName : qualifiedName.substring(c + 1);
    }

    private static DOMException notSupported(String what) {
        return new DOMException(DOMException.NOT_SUPPORTED_ERR, what);
    }

    /** Bare {@code DOMImplementation}: reports no optional features. */
    private static final DOMImplementation MINIMAL_IMPL = new DOMImplementation() {
        @Override public boolean hasFeature(String feature, String version) {
            return false;
        }
        @Override public DocumentType createDocumentType(String qualifiedName,
                String publicId, String systemId) throws DOMException {
            throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "createDocumentType");
        }
        @Override public Document createDocument(String namespaceURI,
                String qualifiedName, DocumentType doctype) throws DOMException {
            throw new DOMException(DOMException.NOT_SUPPORTED_ERR, "createDocument");
        }
        @Override public Object getFeature(String feature, String version) {
            return null;
        }
    };
}
