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

import java.util.List;

import javafx.util.Subscription;

/**
 * The network layer of a {@link WebEngine}: register {@link NetworkInterceptor}s
 * to inspect, block, redirect, or modify the engine's HTTP requests and
 * responses. Obtain it from {@link WebEngine#getNetwork()}.
 *
 * <p>Several interceptors may be registered, each scoped to a different slice of
 * traffic by its {@link NetworkFilter}; for each request the first one whose
 * filter matches owns the decision. All interceptor callbacks run on the JavaFX
 * Application Thread.
 *
 * <p>Example:
 * <pre>{@code
 * Subscription sub = webEngine.getNetwork().add(
 *     NetworkFilter.builder()
 *         .includeUrlPattern("*://host/api/*")
 *         .build(),
 *     new NetworkInterceptor() {
 *         public void onRequest(NetworkExchange ex) {
 *             if (ex.request().url().contains("/ads/")) ex.block();
 *             else ex.proceed();
 *         }
 *     });
 *
 * // later, to stop intercepting:
 * sub.unsubscribe();
 * }</pre>
 *
 * @since 25
 */
public interface Network {

    /**
     * Registers an interceptor scoped to {@code filter}. Returns a
     * {@link Subscription} whose {@link Subscription#unsubscribe() unsubscribe()}
     * removes <b>this</b> registration (registering the same interceptor twice
     * yields two independent registrations).
     *
     * @param filter the filter scoping this interceptor, or {@code null} to match
     *        all requests
     * @param interceptor the interceptor to add (must not be {@code null})
     * @return a subscription that removes this registration
     */
    Subscription add(NetworkFilter filter, NetworkInterceptor interceptor);

    /**
     * Returns the registered interceptors, in registration order.
     * @return an unmodifiable snapshot of the registered interceptors
     */
    List<NetworkInterceptor> interceptors();

    /**
     * Removes every registered interceptor and stops interception.
     */
    void clear();
}
