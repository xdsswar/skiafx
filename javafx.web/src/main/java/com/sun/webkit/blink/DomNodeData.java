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
package com.sun.webkit.blink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One node in the Java-side DOM cache mirrored from the live Blink document.
 *
 * <p>Populated by {@link DomBridge} from the engine's tree-sync events
 * ({@code DOM_ELEMENT}/{@code DOM_TEXT}) and kept current by mutation events
 * ({@code MUTATION_*}) and Java-side write-through. Every field is mutated only
 * while holding the owning {@link DomBridge}'s monitor, so the fields are plain
 * (no per-field synchronization needed).
 *
 * <p>Pure data — no engine handles, no behavior. Internal; never exported from
 * {@code javafx.web}.
 */
final class DomNodeData {

    /** Engine/Java node id (stable for the lifetime of the document). */
    final int nodeId;
    /** {@code true} for a Text node, {@code false} for an Element. */
    final boolean isText;

    /** Parent node id, or {@code 0} for a document-level root (the {@code <html>}). */
    int parentId;
    /** Upper-case tag name (e.g. {@code "DIV"}); empty for text nodes. */
    String tag = "";
    /** Text content for text nodes; empty for elements. */
    String text = "";

    /** Attribute name → value, in insertion order. Lazily created. */
    private Map<String, String> attrs;
    /** Inline style property → value, in insertion order. Lazily created. */
    private Map<String, String> styleProps;
    /** Child node ids in document order. Lazily created. */
    private List<Integer> childIds;

    DomNodeData(int nodeId, boolean isText, int parentId) {
        this.nodeId = nodeId;
        this.isText = isText;
        this.parentId = parentId;
    }

    Map<String, String> attrs() {
        if (attrs == null) {
            attrs = new LinkedHashMap<>(4);
        }
        return attrs;
    }

    boolean hasAttrs() {
        return attrs != null && !attrs.isEmpty();
    }

    Map<String, String> styleProps() {
        if (styleProps == null) {
            styleProps = new LinkedHashMap<>(4);
        }
        return styleProps;
    }

    List<Integer> childIds() {
        if (childIds == null) {
            childIds = new ArrayList<>(4);
        }
        return childIds;
    }

    boolean hasChildren() {
        return childIds != null && !childIds.isEmpty();
    }

    /** Convenience: the {@code id} attribute value, or empty. */
    String idAttr() {
        return attrs == null ? "" : attrs.getOrDefault("id", "");
    }

    /** Convenience: the {@code class} attribute value, or empty. */
    String className() {
        return attrs == null ? "" : attrs.getOrDefault("class", "");
    }
}
