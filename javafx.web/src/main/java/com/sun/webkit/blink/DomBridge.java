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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.w3c.dom.Node;
import org.w3c.dom.events.Event;
import org.w3c.dom.events.EventListener;

import com.sun.webkit.blink.dom.DomNodes;

/**
 * Java-side mirror of the live Blink DOM and the single point that talks to the
 * engine for DOM work. Backs the {@code org.w3c.dom.*} wrappers in
 * {@code com.sun.webkit.dom} without any synchronous round-trips on the FX
 * thread: getters read a local cache, setters write the cache through and emit a
 * fire-and-forget DOM command.
 *
 * <h2>Threading</h2>
 * Every field is guarded by this object's monitor. Tree-sync and mutation
 * intake run on the {@link EventPump} thread; getters and command writers run on
 * the FX thread; interaction-event dispatch is marshalled to the FX thread by
 * {@link BlinkPage}. All of those paths enter through {@code synchronized}
 * methods here, so the cache is always consistent and DOM access never touches
 * the render hot path.
 *
 * <h2>Node ids</h2>
 * The engine's renderer assigns ids to existing nodes during its tree walk
 * (starting at {@code 1}); Java allocates ids for nodes it creates from
 * {@link #nextNodeId}, seeded high so the two id spaces never collide. Id
 * {@code 0} is reserved as "none". The {@code org.w3c.dom.Document} is a
 * Java-only concept (the engine has no node for it) and is held separately.
 *
 * <p>Public only so the package-private {@code com.sun.webkit.dom} wrappers can
 * reach it; the package is not exported, so this type never escapes the module
 * and never appears in any {@code javafx.scene.web} or {@code org.w3c.dom}
 * method signature.
 */
public final class DomBridge {

    /** Reserved "no node" id; matches the engine's {@code node_id == 0} sentinel. */
    public static final int NO_NODE = 0;

    private final CommandRingBuffer commands;
    private final int windowId;

    /** All mutable state below is guarded by this monitor. */
    private final Map<Integer, DomNodeData> cache = new LinkedHashMap<>();
    private final Map<Integer, Node> wrappers = new HashMap<>();
    private final Map<Integer, Map<String, List<ListenerReg>>> listeners = new HashMap<>();
    /** Java-created {@code DocumentFragment} ids (children flush on append). */
    private final Set<Integer> fragments = new HashSet<>();
    /**
     * Ids the engine removed from their parent but that we haven't evicted yet —
     * deferred so a DOM <em>move</em> (remove from old parent + add to new parent,
     * possibly in separate mutation callbacks) doesn't drop a node that's about to
     * be re-attached. Flushed past {@link #EVICT_THRESHOLD}: any id still genuinely
     * orphaned is evicted (with its subtree); re-attached ids are kept.
     */
    private final Set<Integer> pendingEvict = new HashSet<>();
    private static final int EVICT_THRESHOLD = 256;

    /** Java-allocated ids start high to avoid colliding with renderer-walk ids. */
    private final AtomicInteger nextNodeId = new AtomicInteger(0x4000_0000);

    /** The {@code <html>} element id (parent {@code 0}), or {@link #NO_NODE}. */
    private int rootElementId = NO_NODE;
    /** The current document wrapper (one per load), created lazily. */
    private Object document; // a com.sun.webkit.dom.DocumentImpl (kept as Object to avoid a hard type here)

    private record ListenerReg(EventListener listener, boolean useCapture) { }

    DomBridge(CommandRingBuffer commands, int windowId) {
        this.commands = commands;
        this.windowId = windowId;
    }

    // ====================================================================
    // Tree-sync + mutation intake (EventPump thread)
    // ====================================================================

    /** A walked element node. Stitches it under its parent in document order. */
    synchronized void onDomElement(int nodeId, int parentId, String tag,
                                   String idAttr, String className) {
        if (nodeId == NO_NODE) {
            return;
        }
        DomNodeData d = cache.get(nodeId);
        if (d == null) {
            d = new DomNodeData(nodeId, false, parentId);
            cache.put(nodeId, d);
        } else {
            d.parentId = parentId;
        }
        d.tag = tag == null ? "" : tag;
        if (idAttr != null && !idAttr.isEmpty()) {
            d.attrs().put("id", idAttr);
        }
        if (className != null && !className.isEmpty()) {
            d.attrs().put("class", className);
        }
        linkChild(parentId, nodeId);
        if (parentId == NO_NODE && rootElementId == NO_NODE) {
            rootElementId = nodeId;
        }
    }

    /** A walked text node. */
    synchronized void onDomText(int nodeId, int parentId, String text) {
        if (nodeId == NO_NODE) {
            return;
        }
        DomNodeData d = cache.get(nodeId);
        if (d == null) {
            d = new DomNodeData(nodeId, true, parentId);
            cache.put(nodeId, d);
        } else {
            d.parentId = parentId;
        }
        d.text = text == null ? "" : text;
        linkChild(parentId, nodeId);
    }

    private void linkChild(int parentId, int childId) {
        if (parentId == NO_NODE) {
            return;
        }
        DomNodeData parent = cache.get(parentId);
        if (parent != null && !parent.childIds().contains(childId)) {
            parent.childIds().add(childId);
        }
    }

    /** Attribute mutation echoed from the engine's MutationObserver (idempotent). */
    synchronized void onMutationAttribute(int nodeId, String name, String oldVal, String newVal) {
        DomNodeData d = cache.get(nodeId);
        if (d == null || name == null) {
            return;
        }
        if (newVal == null || newVal.isEmpty()) {
            // Attribute removed (or set empty). Mirror the live state.
            if (d.hasAttrs()) {
                d.attrs().remove(name);
            }
        } else {
            d.attrs().put(name, newVal);
        }
    }

    /** Child-list mutation echoed from the engine (idempotent compare-and-apply). */
    synchronized void onMutationChildren(int parentId, int[] addedIds, int[] removedIds) {
        DomNodeData parent = cache.get(parentId);
        if (parent == null) {
            return;
        }
        if (removedIds != null) {
            for (int id : removedIds) {
                parent.childIds().remove(Integer.valueOf(id));
                pendingEvict.add(id);   // evicted later unless re-attached (a move)
            }
        }
        if (addedIds != null) {
            for (int id : addedIds) {
                pendingEvict.remove(id);   // re-attached → not actually removed
                if (!parent.childIds().contains(id)) {
                    parent.childIds().add(id);
                }
                DomNodeData child = cache.get(id);
                if (child != null) {
                    child.parentId = parentId;
                }
            }
        }
        if (pendingEvict.size() > EVICT_THRESHOLD) {
            flushEvictions();
        }
    }

    /**
     * Evicts cached state ({@code cache}/{@code wrappers}/{@code listeners}) for
     * nodes the page removed and did not re-attach — otherwise these maps grow
     * without bound on churny pages (infinite scroll, SPA re-renders). A node that
     * was merely moved still has a parent that lists it, so it is kept.
     */
    private void flushEvictions() {
        if (pendingEvict.isEmpty()) {
            return;
        }
        for (int id : pendingEvict) {
            DomNodeData d = cache.get(id);
            if (d == null) {
                continue;   // already gone
            }
            DomNodeData parent = cache.get(d.parentId);
            if (parent != null && parent.childIds().contains(id)) {
                continue;   // re-attached elsewhere (moved) — keep it
            }
            evictSubtree(id);
        }
        pendingEvict.clear();
    }

    /** Recursively drops a removed node and its cached descendants. */
    private void evictSubtree(int id) {
        DomNodeData d = cache.remove(id);
        wrappers.remove(id);
        listeners.remove(id);
        if (d != null && d.hasChildren()) {
            for (int kid : new ArrayList<>(d.childIds())) {
                evictSubtree(kid);
            }
        }
    }

    /** Character-data mutation on a text node. */
    synchronized void onMutationText(int nodeId, String oldText, String newText) {
        DomNodeData d = cache.get(nodeId);
        if (d != null) {
            d.text = newText == null ? "" : newText;
        }
    }

    /** The tree walk finished; record the document element if not yet seen. */
    synchronized void onDomTreeReady() {
        if (rootElementId == NO_NODE) {
            for (DomNodeData d : cache.values()) {
                if (!d.isText && d.parentId == NO_NODE) {
                    rootElementId = d.nodeId;
                    break;
                }
            }
        }
    }

    /** Drops all per-document state before a new navigation's tree streams in. */
    synchronized void reset() {
        cache.clear();
        wrappers.clear();
        listeners.clear();
        fragments.clear();
        pendingEvict.clear();
        rootElementId = NO_NODE;
        document = null;
    }

    // ====================================================================
    // Interaction-event dispatch (marshalled to the FX thread by BlinkPage)
    // ====================================================================

    /**
     * Delivers a fired Blink event to the Java listeners registered on the node.
     * Runs on the FX thread. {@code domType} is the lowercase DOM event name.
     */
    public void fireDomEvent(int nodeId, String domType, float x, float y, int button) {
        List<ListenerReg> regs;
        Node target;
        synchronized (this) {
            Map<String, List<ListenerReg>> byType = listeners.get(nodeId);
            if (byType == null) {
                return;
            }
            List<ListenerReg> l = byType.get(domType);
            if (l == null || l.isEmpty()) {
                return;
            }
            regs = new ArrayList<>(l); // snapshot: a listener may add/remove during dispatch
            target = getOrCreateWrapperLocked(nodeId);
        }
        if (target == null) {
            return;
        }
        Event ev = DomNodes.createEvent(domType, target, x, y, button);
        for (ListenerReg r : regs) {
            try {
                r.listener().handleEvent(ev);
            } catch (RuntimeException ex) {
                // One misbehaving listener must not break the rest of the batch.
                System.getLogger("com.sun.webkit.blink.DomBridge")
                    .log(System.Logger.Level.DEBUG, "DOM listener threw", ex);
            }
        }
    }

    /** Maps an engine interaction-event id to its lowercase DOM event name. */
    public static String domEventName(int eventTypeId) {
        return switch (eventTypeId) {
            case NativeEventType.DOM_CLICK -> "click";
            case NativeEventType.DOM_DBLCLICK -> "dblclick";
            case NativeEventType.DOM_MOUSE_DOWN -> "mousedown";
            case NativeEventType.DOM_MOUSE_UP -> "mouseup";
            case NativeEventType.DOM_MOUSE_MOVE -> "mousemove";
            case NativeEventType.DOM_MOUSE_ENTER -> "mouseenter";
            case NativeEventType.DOM_MOUSE_LEAVE -> "mouseleave";
            case NativeEventType.DOM_MOUSE_OVER -> "mouseover";
            case NativeEventType.DOM_MOUSE_OUT -> "mouseout";
            case NativeEventType.DOM_CONTEXT_MENU -> "contextmenu";
            case NativeEventType.DOM_KEY_DOWN -> "keydown";
            case NativeEventType.DOM_KEY_UP -> "keyup";
            case NativeEventType.DOM_KEY_PRESS -> "keypress";
            case NativeEventType.DOM_FOCUS -> "focus";
            case NativeEventType.DOM_BLUR -> "blur";
            case NativeEventType.DOM_FOCUS_IN -> "focusin";
            case NativeEventType.DOM_FOCUS_OUT -> "focusout";
            case NativeEventType.DOM_SCROLL -> "scroll";
            case NativeEventType.DOM_INPUT -> "input";
            default -> "";
        };
    }

    /** True if the interaction-event tail carries {@code [x:f32][y:f32][button:4]}. */
    public static boolean isMouseInteraction(int eventTypeId) {
        return switch (eventTypeId) {
            case NativeEventType.DOM_CLICK, NativeEventType.DOM_DBLCLICK,
                 NativeEventType.DOM_MOUSE_DOWN, NativeEventType.DOM_MOUSE_UP,
                 NativeEventType.DOM_MOUSE_MOVE, NativeEventType.DOM_MOUSE_ENTER,
                 NativeEventType.DOM_MOUSE_LEAVE, NativeEventType.DOM_MOUSE_OVER,
                 NativeEventType.DOM_MOUSE_OUT, NativeEventType.DOM_CONTEXT_MENU -> true;
            default -> false;
        };
    }

    // ====================================================================
    // Wrapper identity + Document (FX thread)
    // ====================================================================

    /** The current document wrapper, or {@code null} if no tree has been walked yet. */
    public synchronized Node document() {
        if (document == null && rootElementId != NO_NODE) {
            document = DomNodes.createDocument(this);
        }
        return (Node) document;
    }

    /** Returns the single canonical wrapper for {@code id} (creating it on first use). */
    public synchronized Node getOrCreateWrapper(int id) {
        return getOrCreateWrapperLocked(id);
    }

    private Node getOrCreateWrapperLocked(int id) {
        if (id == NO_NODE) {
            return null;
        }
        Node w = wrappers.get(id);
        if (w != null) {
            return w;
        }
        DomNodeData d = cache.get(id);
        if (d == null) {
            return null;
        }
        int kind = d.isText ? DomNodes.KIND_TEXT
            : fragments.contains(id) ? DomNodes.KIND_FRAGMENT
            : DomNodes.KIND_ELEMENT;
        w = DomNodes.create(this, d.nodeId, kind);
        wrappers.put(id, w);
        return w;
    }

    // ====================================================================
    // Cache reads for the org.w3c.dom wrappers (FX thread)
    // ====================================================================

    public synchronized boolean exists(int id) {
        return cache.containsKey(id);
    }

    public synchronized boolean isText(int id) {
        DomNodeData d = cache.get(id);
        return d != null && d.isText;
    }

    public synchronized String tagName(int id) {
        DomNodeData d = cache.get(id);
        return d == null ? "" : d.tag;
    }

    public synchronized int parentId(int id) {
        DomNodeData d = cache.get(id);
        return d == null ? NO_NODE : d.parentId;
    }

    public synchronized int rootElementId() {
        return rootElementId;
    }

    public synchronized String textOf(int id) {
        DomNodeData d = cache.get(id);
        return d == null ? "" : d.text;
    }

    public synchronized String attr(int id, String name) {
        DomNodeData d = cache.get(id);
        if (d == null || !d.hasAttrs()) {
            return null;
        }
        return d.attrs().get(name);
    }

    public synchronized boolean hasAttr(int id, String name) {
        DomNodeData d = cache.get(id);
        return d != null && d.hasAttrs() && d.attrs().containsKey(name);
    }

    public synchronized String[] attrNames(int id) {
        DomNodeData d = cache.get(id);
        if (d == null || !d.hasAttrs()) {
            return new String[0];
        }
        return d.attrs().keySet().toArray(new String[0]);
    }

    /** Child ids in document order (a defensive copy, safe to iterate unlocked). */
    public synchronized int[] childIds(int id) {
        DomNodeData d = cache.get(id);
        if (d == null || !d.hasChildren()) {
            return new int[0];
        }
        List<Integer> kids = d.childIds();
        int[] out = new int[kids.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = kids.get(i);
        }
        return out;
    }

    public synchronized int firstChild(int id) {
        DomNodeData d = cache.get(id);
        return (d == null || !d.hasChildren()) ? NO_NODE : d.childIds().get(0);
    }

    public synchronized int lastChild(int id) {
        DomNodeData d = cache.get(id);
        if (d == null || !d.hasChildren()) {
            return NO_NODE;
        }
        List<Integer> kids = d.childIds();
        return kids.get(kids.size() - 1);
    }

    public synchronized int nextSibling(int id) {
        DomNodeData d = cache.get(id);
        if (d == null) {
            return NO_NODE;
        }
        return siblingAt(d.parentId, id, +1);
    }

    public synchronized int previousSibling(int id) {
        DomNodeData d = cache.get(id);
        if (d == null) {
            return NO_NODE;
        }
        return siblingAt(d.parentId, id, -1);
    }

    private int siblingAt(int parentId, int id, int delta) {
        DomNodeData parent = cache.get(parentId);
        if (parent == null || !parent.hasChildren()) {
            return NO_NODE;
        }
        List<Integer> kids = parent.childIds();
        int idx = kids.indexOf(id);
        int j = idx + delta;
        return (idx < 0 || j < 0 || j >= kids.size()) ? NO_NODE : kids.get(j);
    }

    /** First element whose {@code id} attribute equals {@code elementId}, or {@code NO_NODE}. */
    public synchronized int getElementById(String elementId) {
        if (elementId == null) {
            return NO_NODE;
        }
        for (DomNodeData d : cache.values()) {
            // Skip nodes the page removed but whose eviction is still deferred
            // (move-safety window): they linger in `cache` with their old idAttr
            // until flushEvictions(), so a plain scan would return a detached
            // element. getElementsByTagName is unaffected — it walks childIds,
            // from which removed nodes are unlinked immediately.
            if (!d.isText && !pendingEvict.contains(d.nodeId)
                    && elementId.equals(d.idAttr())) {
                return d.nodeId;
            }
        }
        return NO_NODE;
    }

    /** Descendant element ids of {@code scopeId} matching {@code tag} ("*" = all), document order. */
    public synchronized int[] getElementsByTagName(int scopeId, String tag) {
        List<Integer> out = new ArrayList<>();
        boolean all = tag == null || "*".equals(tag);
        String want = all ? null : tag.toUpperCase(Locale.ROOT);
        collectByTag(scopeId, want, out);
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = out.get(i);
        }
        return r;
    }

    private void collectByTag(int scopeId, String wantUpper, List<Integer> out) {
        DomNodeData scope = cache.get(scopeId);
        if (scope == null || !scope.hasChildren()) {
            return;
        }
        for (int childId : scope.childIds()) {
            DomNodeData c = cache.get(childId);
            if (c == null || c.isText) {
                continue;
            }
            if (wantUpper == null || wantUpper.equals(c.tag)) {
                out.add(childId);
            }
            collectByTag(childId, wantUpper, out);
        }
    }

    public synchronized String styleProperty(int id, String prop) {
        DomNodeData d = cache.get(id);
        if (d == null) {
            return "";
        }
        return d.styleProps().getOrDefault(prop, "");
    }

    // ====================================================================
    // Mutators: cache write-through + fire-and-forget command (FX thread)
    // ====================================================================

    public synchronized void setAttribute(int id, String name, String value) {
        DomNodeData d = cache.get(id);
        if (d == null || name == null) {
            return;
        }
        d.attrs().put(name, value == null ? "" : value);
        commands.writeNodeNameValue16(CommandType.SET_ATTRIBUTE, windowId, id,
            name, value == null ? "" : value);
    }

    public synchronized void removeAttribute(int id, String name) {
        DomNodeData d = cache.get(id);
        if (d == null || name == null) {
            return;
        }
        if (d.hasAttrs()) {
            d.attrs().remove(name);
        }
        commands.writeNodeString16(CommandType.REMOVE_ATTRIBUTE, windowId, id, name);
    }

    public synchronized void setTextContent(int id, String text) {
        DomNodeData d = cache.get(id);
        if (d == null) {
            return;
        }
        String t = text == null ? "" : text;
        if (d.isText) {
            d.text = t;
        }
        if (!commands.writeNodeString32(CommandType.SET_TEXT_CONTENT, windowId, id, t)) {
            logOversize("textContent", id, t.length());
        }
    }

    public synchronized void setInnerHtml(int id, String html) {
        String h = html == null ? "" : html;
        if (!commands.writeNodeString32(CommandType.SET_INNER_HTML, windowId, id, h)) {
            logOversize("innerHTML", id, h.length());
        }
    }

    public synchronized void setStyleProperty(int id, String prop, String value) {
        DomNodeData d = cache.get(id);
        if (d == null || prop == null) {
            return;
        }
        d.styleProps().put(prop, value == null ? "" : value);
        commands.writeNodeNameValue16(CommandType.SET_STYLE_PROPERTY, windowId, id,
            prop, value == null ? "" : value);
    }

    public synchronized void removeStyleProperty(int id, String prop) {
        DomNodeData d = cache.get(id);
        if (d == null || prop == null) {
            return;
        }
        d.styleProps().remove(prop);
        commands.writeNodeString16(CommandType.REMOVE_STYLE_PROPERTY, windowId, id, prop);
    }

    public synchronized void addClass(int id, String token) {
        DomNodeData d = cache.get(id);
        if (d == null || token == null || token.isEmpty()) {
            return;
        }
        String cls = d.className();
        if (!containsToken(cls, token)) {
            d.attrs().put("class", cls.isEmpty() ? token : cls + " " + token);
        }
        commands.writeNodeString16(CommandType.ADD_CLASS, windowId, id, token);
    }

    public synchronized void removeClass(int id, String token) {
        DomNodeData d = cache.get(id);
        if (d == null || token == null || token.isEmpty()) {
            return;
        }
        if (d.hasAttrs()) {
            String cls = d.className();
            if (!cls.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String t : cls.split("\\s+")) {
                    if (!t.isEmpty() && !t.equals(token)) {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        sb.append(t);
                    }
                }
                d.attrs().put("class", sb.toString());
            }
        }
        commands.writeNodeString16(CommandType.REMOVE_CLASS, windowId, id, token);
    }

    private static boolean containsToken(String classAttr, String token) {
        if (classAttr.isEmpty()) {
            return false;
        }
        for (String t : classAttr.split("\\s+")) {
            if (t.equals(token)) {
                return true;
            }
        }
        return false;
    }

    public synchronized int createElement(String tag) {
        int id = nextNodeId.getAndIncrement();
        DomNodeData d = new DomNodeData(id, false, NO_NODE);
        d.tag = tag == null ? "" : tag.toUpperCase(Locale.ROOT);
        cache.put(id, d);
        commands.writeNodeString16(CommandType.CREATE_ELEMENT, windowId, id,
            tag == null ? "" : tag);
        return id;
    }

    public synchronized int createTextNode(String text) {
        // Text nodes created Java-side live only in the cache until appended;
        // the engine materializes them through setTextContent on append. We give
        // them a Java id so wrappers have identity; the engine has no text-node
        // create command, so a detached text node is realized as the textContent
        // of its first element parent on append.
        int id = nextNodeId.getAndIncrement();
        DomNodeData d = new DomNodeData(id, true, NO_NODE);
        d.text = text == null ? "" : text;
        cache.put(id, d);
        return id;
    }

    /** A Java-only DocumentFragment; its children flush into the tree on append. */
    public synchronized int createFragment() {
        int id = nextNodeId.getAndIncrement();
        cache.put(id, new DomNodeData(id, false, NO_NODE));
        fragments.add(id);
        return id;
    }

    public synchronized void appendChild(int parentId, int childId) {
        if (fragments.contains(childId)) {
            flushFragment(parentId, childId, NO_NODE);
            return;
        }
        reparent(parentId, childId);
        commands.writeTwoNodes(CommandType.APPEND_CHILD, windowId, parentId, childId);
    }

    /** Moves a fragment's children into {@code parentId} (before {@code refId}, or appended). */
    private void flushFragment(int parentId, int fragId, int refId) {
        DomNodeData frag = cache.get(fragId);
        DomNodeData parent = cache.get(parentId);
        if (frag == null || parent == null) {
            return;
        }
        List<Integer> kids = new ArrayList<>(frag.childIds());
        frag.childIds().clear();
        for (int kid : kids) {
            DomNodeData kd = cache.get(kid);
            if (kd == null) {
                continue;
            }
            kd.parentId = parentId;
            if (refId == NO_NODE) {
                if (!parent.childIds().contains(kid)) {
                    parent.childIds().add(kid);
                }
                commands.writeTwoNodes(CommandType.APPEND_CHILD, windowId, parentId, kid);
            } else {
                int idx = parent.childIds().indexOf(refId);
                if (idx < 0) {
                    parent.childIds().add(kid);
                } else {
                    parent.childIds().add(idx, kid);
                }
                commands.writeThreeNodes(CommandType.INSERT_BEFORE, windowId, parentId, kid, refId);
            }
        }
    }

    public synchronized void insertBefore(int parentId, int childId, int refId) {
        if (fragments.contains(childId)) {
            flushFragment(parentId, childId, refId);
            return;
        }
        DomNodeData parent = cache.get(parentId);
        DomNodeData child = cache.get(childId);
        if (parent != null && child != null) {
            detachFromParent(childId);
            child.parentId = parentId;
            int idx = refId == NO_NODE ? -1 : parent.childIds().indexOf(refId);
            if (idx < 0) {
                parent.childIds().add(childId);
            } else {
                parent.childIds().add(idx, childId);
            }
        }
        commands.writeThreeNodes(CommandType.INSERT_BEFORE, windowId, parentId, childId, refId);
    }

    public synchronized void removeChild(int parentId, int childId) {
        detachFromParent(childId);
        // Queue the detached child for the SAME deferred, move-safe eviction the
        // engine's mutation echo uses (onMutationChildren). flushEvictions only
        // drops an id that isn't re-attached elsewhere, so a DOM move (removeChild
        // then appendChild) keeps the node's mirror while a true discard is
        // reclaimed. Previously a Java-initiated removeChild fed nothing, so a
        // removed-and-dropped node leaked its cache/wrappers/listeners entries
        // until navigation. A hard cache/wrappers/listeners.remove() here (à la
        // removeElement) would instead destroy a moved node's mirror — wrong.
        // (bugs.md M14)
        pendingEvict.add(childId);
        if (pendingEvict.size() > EVICT_THRESHOLD) {
            flushEvictions();
        }
        commands.writeTwoNodes(CommandType.REMOVE_CHILD, windowId, parentId, childId);
    }

    public synchronized void removeElement(int id) {
        detachFromParent(id);
        cache.remove(id);
        wrappers.remove(id);
        listeners.remove(id);
        commands.writeNode(CommandType.REMOVE_ELEMENT, windowId, id);
    }

    private void reparent(int parentId, int childId) {
        DomNodeData parent = cache.get(parentId);
        DomNodeData child = cache.get(childId);
        if (parent == null || child == null) {
            return;
        }
        detachFromParent(childId);
        child.parentId = parentId;
        if (!parent.childIds().contains(childId)) {
            parent.childIds().add(childId);
        }
    }

    private void detachFromParent(int childId) {
        DomNodeData child = cache.get(childId);
        if (child == null) {
            return;
        }
        DomNodeData parent = cache.get(child.parentId);
        if (parent != null && parent.hasChildren()) {
            parent.childIds().remove(Integer.valueOf(childId));
        }
        child.parentId = NO_NODE;
    }

    public synchronized void domFocus(int id) {
        commands.writeNode(CommandType.DOM_FOCUS, windowId, id);
    }

    public synchronized void domBlur(int id) {
        commands.writeNode(CommandType.DOM_BLUR, windowId, id);
    }

    public synchronized void domClick(int id) {
        commands.writeNode(CommandType.DOM_CLICK, windowId, id);
    }

    // ====================================================================
    // Event listener registration (FX thread)
    // ====================================================================

    public synchronized void addEventListener(int id, String type, EventListener listener,
                                              boolean useCapture) {
        if (type == null || listener == null) {
            return;
        }
        Map<String, List<ListenerReg>> byType =
            listeners.computeIfAbsent(id, k -> new LinkedHashMap<>());
        List<ListenerReg> l = byType.computeIfAbsent(type, k -> new ArrayList<>());
        boolean first = l.isEmpty();
        // Match the W3C contract: a duplicate (listener, type, capture) is ignored.
        for (ListenerReg r : l) {
            if (r.listener() == listener && r.useCapture() == useCapture) {
                return;
            }
        }
        l.add(new ListenerReg(listener, useCapture));
        if (first) {
            commands.writeNodeString16(CommandType.ADD_EVENT_LISTENER, windowId, id, type);
        }
    }

    public synchronized void removeEventListener(int id, String type, EventListener listener,
                                                 boolean useCapture) {
        if (type == null || listener == null) {
            return;
        }
        Map<String, List<ListenerReg>> byType = listeners.get(id);
        if (byType == null) {
            return;
        }
        List<ListenerReg> l = byType.get(type);
        if (l == null) {
            return;
        }
        l.removeIf(r -> r.listener() == listener && r.useCapture() == useCapture);
        if (l.isEmpty()) {
            byType.remove(type);
            commands.writeNodeString16(CommandType.REMOVE_EVENT_LISTENER, windowId, id, type);
            if (byType.isEmpty()) {
                listeners.remove(id);
            }
        }
    }

    private static void logOversize(String what, int id, int len) {
        System.getLogger("com.sun.webkit.blink.DomBridge").log(
            System.Logger.Level.WARNING,
            "DOM " + what + " for node " + id + " (" + len
                + " chars) exceeds one command slot and was dropped; "
                + "chunking is a follow-up (see plan risk #3).");
    }
}
