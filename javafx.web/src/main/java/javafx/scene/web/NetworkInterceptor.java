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

/**
 * Intercepts the network traffic of a {@link WebEngine}: inspect, block,
 * redirect, or modify requests and responses, and optionally read/rewrite
 * response bodies. Register it (with a scoping {@link NetworkFilter}) via the
 * engine's {@link Network} facade, {@link WebEngine#getNetwork()}.
 * Several interceptors may be registered, each scoped to a different slice of
 * traffic; for each request the first one whose filter matches owns the decision.
 *
 * <p>All callbacks run on the JavaFX Application Thread. Each intercepted load
 * is <b>held</b> until the application calls a terminal method on the supplied
 * {@link NetworkExchange} (the default methods below answer with
 * {@code proceed()}), so an interceptor may answer asynchronously.
 *
 * <p>Example:
 * <pre>{@code
 * engine.getNetwork().add(
 *     NetworkFilter.builder()
 *         .includeUrlPattern("*://host/api/x")
 *         .phases(EnumSet.of(Phase.REQUEST, Phase.RESPONSE))
 *         .captureResponseBodies(true).build(),
 *     new NetworkInterceptor() {
 *         public void onRequest(NetworkExchange ex) {
 *             if (ex.request().url().contains("/ads/")) ex.block();
 *             else ex.setRequestHeader("X-Tap", "1").proceedModified();
 *         }
 *         public BodyEdit onBodyChunk(NetworkExchange ex, BodyChunk c) {
 *             return c.last() ? BodyEdit.replace(rewrite(c.copy())) : BodyEdit.passThrough();
 *         }
 *     });
 * }</pre>
 *
 * @since 25
 */
public interface NetworkInterceptor {

    /**
     * A request is about to be sent. The load is held until a terminal method on
     * {@code exchange} is invoked. Default: {@link NetworkExchange#proceed()}.
     * @param exchange the request exchange
     */
    default void onRequest(NetworkExchange exchange) {
        exchange.proceed();
    }

    /**
     * Response headers have arrived (only if the {@link NetworkFilter} armed the
     * response phase). The load is held until a terminal method is invoked.
     * Default: {@link NetworkExchange#proceed()}.
     * @param exchange the response exchange
     */
    default void onResponse(NetworkExchange exchange) {
        exchange.proceed();
    }

    /**
     * A captured response-body chunk (only if body capture was armed and
     * {@link NetworkExchange#captureBody()} was called). Return a {@link BodyEdit}
     * to forward, replace, or drop the chunk. Default: pass through.
     * @param exchange the owning exchange
     * @param chunk the body chunk
     * @return the edit to apply
     */
    default BodyEdit onBodyChunk(NetworkExchange exchange, BodyChunk chunk) {
        return BodyEdit.passThrough();
    }
}
