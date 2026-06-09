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

import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;

import netscape.javascript.JSException;
import netscape.javascript.JSObject;

/**
 * A {@link netscape.javascript.JSObject} backed by a live V8 object in the
 * out-of-process Blink engine. The renderer keeps the V8 value alive in a
 * per-page table keyed by {@link #objectId}; each operation round-trips through
 * {@link BlinkPage} (a bounded, request-id-correlated wait, exactly like
 * {@code executeScript}).
 *
 * <p><b>Lifetime:</b> on GC the registered {@link Cleaner} sends a
 * fire-and-forget {@code JS_RELEASE} so the renderer drops its {@code v8::Global}
 * — this is the leak boundary. {@link BlinkPage#wrapJsObject} caches one wrapper
 * per id (so one Cleaner per id), and the release is gated on the cache still
 * owning this wrapper's token (so a re-wrap after GC suppresses a stale release).
 * Navigation/page-dispose clears the whole renderer table as a backstop. The
 * cleanup action captures only the id, page, and the (weak) cache token — never
 * {@code this} — so the wrapper stays collectible.
 *
 * <p>Apps never reference this type directly — they cast {@code executeScript}
 * results to {@link netscape.javascript.JSObject}. Package-private; internal.
 */
final class JSObjectImpl extends JSObject {

    private static final Cleaner CLEANER = Cleaner.create();

    private final BlinkPage page;
    private final int objectId;
    /** Document generation this wrapper was minted under (see BlinkPage.checkLive). */
    private final int generation;
    @SuppressWarnings("unused") // retained so the Cleaner stays armed for our lifetime
    private Cleaner.Cleanable cleanable;

    JSObjectImpl(BlinkPage page, int objectId, int generation) {
        this.page = page;
        this.objectId = objectId;
        this.generation = generation;
        // NB: the Cleaner is armed later via arm(), only once this wrapper has
        // won the identity-cache slot — see BlinkPage.wrapJsObject. A wrapper that
        // loses the race is never armed and so releases nothing.
    }

    int objectId() {
        return objectId;
    }

    /**
     * Register the GC release against {@code token} (this wrapper's identity-cache
     * key). Called exactly once, by {@link BlinkPage#wrapJsObject}, after this
     * wrapper has been published to the cache. Static action so it never captures
     * the wrapper (which must stay collectible).
     */
    void arm(WeakReference<JSObjectImpl> token) {
        this.cleanable = CLEANER.register(this, releaseAction(page, objectId, token));
    }

    /** Static so it never captures the wrapper (which must stay collectible). */
    private static Runnable releaseAction(BlinkPage page, int objectId,
                                          WeakReference<JSObjectImpl> token) {
        return () -> page.jsReleaseIfOwner(objectId, token);
    }

    @Override
    public Object call(String methodName, Object... args) throws JSException {
        page.checkLive(generation);
        return page.jsCall(objectId, methodName, args);
    }

    @Override
    public Object eval(String s) throws JSException {
        page.checkLive(generation);
        return page.jsEval(objectId, s);
    }

    @Override
    public Object getMember(String name) throws JSException {
        page.checkLive(generation);
        return page.jsGetMember(objectId, name);
    }

    @Override
    public void setMember(String name, Object value) throws JSException {
        page.checkLive(generation);
        page.jsSetMember(objectId, name, value);
    }

    @Override
    public void removeMember(String name) throws JSException {
        page.checkLive(generation);
        page.jsRemoveMember(objectId, name);
    }

    @Override
    public Object getSlot(int index) throws JSException {
        page.checkLive(generation);
        return page.jsGetSlot(objectId, index);
    }

    @Override
    public void setSlot(int index, Object value) throws JSException {
        page.checkLive(generation);
        page.jsSetSlot(objectId, index, value);
    }

    /**
     * Cheap, non-blocking identity — deliberately does NOT round-trip to the
     * engine (toString is called from loggers/debuggers and must not block or
     * risk a timeout). For the JavaScript string value use
     * {@code String.valueOf(obj.call("toString"))}.
     */
    @Override
    public String toString() {
        return "[JSObject " + objectId + "]";
    }
}
