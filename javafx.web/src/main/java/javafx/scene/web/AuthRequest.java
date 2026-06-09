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
package javafx.scene.web;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An HTTP authentication challenge (server {@code 401} or proxy {@code 407}).
 * Surfaced to the application through
 * {@link WebEngine#authHandlerProperty() authHandler} so it can collect
 * credentials and answer with {@link #supply(String, String)} or {@link #cancel()}.
 *
 * <p>The network load is held until answered, so the answer may be given
 * asynchronously. The request is <b>single-shot</b>. If no handler is set, the
 * challenge is cancelled (the load fails as it would if the user pressed Cancel).
 *
 * @since 25
 */
public final class AuthRequest {

    private final WebEngine engine;
    private final int id;
    private final AuthScheme scheme;
    private final boolean proxy;
    private final String host;
    private final String realm;
    private final AtomicBoolean responded = new AtomicBoolean();

    AuthRequest(WebEngine engine, int id, AuthScheme scheme, boolean proxy,
                String host, String realm) {
        this.engine = engine;
        this.id = id;
        this.scheme = scheme == null ? AuthScheme.UNKNOWN : scheme;
        this.proxy = proxy;
        this.host = host == null ? "" : host;
        this.realm = realm == null ? "" : realm;
    }

    /**
     * Returns the {@code WebEngine} whose load raised this challenge.
     * @return the source engine
     */
    public WebEngine getSource() {
        return engine;
    }

    /**
     * Returns the authentication scheme.
     * @return the scheme
     */
    public AuthScheme getScheme() {
        return scheme;
    }

    /**
     * Returns whether this is a proxy-server challenge (vs. an origin server).
     * @return {@code true} for a proxy challenge
     */
    public boolean isProxy() {
        return proxy;
    }

    /**
     * Returns the host (and possibly port) requesting authentication.
     * @return the host
     */
    public String getHost() {
        return host;
    }

    /**
     * Returns the authentication realm, or an empty string if none.
     * @return the realm
     */
    public String getRealm() {
        return realm;
    }

    /**
     * Returns whether this challenge has already been answered.
     * @return {@code true} once {@link #supply} or {@link #cancel} ran
     */
    public boolean isResponded() {
        return responded.get();
    }

    /**
     * Supplies credentials and retries the load.
     * @param username the user name
     * @param password the password
     */
    public void supply(String username, String password) {
        if (!responded.compareAndSet(false, true)) {
            return;
        }
        engine.respondAuth(id, true, username == null ? "" : username,
            password == null ? "" : password);
    }

    /**
     * Cancels the challenge; the load fails with an authentication error.
     */
    public void cancel() {
        if (!responded.compareAndSet(false, true)) {
            return;
        }
        engine.respondAuth(id, false, "", "");
    }
}
