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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The pause/resume handle for one intercepted resource. Passed to a
 * {@link NetworkInterceptor}; the load is held until exactly one terminal method
 * is called (now or later, from any thread). Terminal methods are
 * <b>single-shot</b> — a second terminal call throws {@link IllegalStateException}.
 *
 * <p>Request-phase decisions: {@link #proceed()}, {@link #block()},
 * {@link #redirect(String)}, header/method mutators + {@link #proceedModified()},
 * or {@link #respondWith(SyntheticResponse)}. Response-phase decisions:
 * header/status mutators + {@link #proceed()}, {@link #captureBody()},
 * {@link #replaceBody(byte[])}.
 *
 * @since 25
 */
public final class NetworkExchange {

    // Action codes shared with the engine seam.
    private static final int A_PROCEED = 0;
    private static final int A_PROCEED_MODIFIED = 1;
    private static final int A_BLOCK = 2;
    private static final int A_REDIRECT = 3;
    private static final int A_SYNTHETIC = 4;
    private static final int A_RESPONSE_PROCEED = 5;
    private static final int A_REPLACE_BODY = 6;

    private static final int PHASE_REQUEST = 0;
    private static final int PHASE_RESPONSE = 1;

    private final WebEngine engine;
    private final int interceptId;
    private final int phase;
    private final NetworkRequest request;
    private final NetworkResponse response;

    // Accumulated request/response mutations applied by *_MODIFIED / RESPONSE_PROCEED.
    private final List<String[]> setHeaders = new ArrayList<>();
    private final List<String> removeHeaders = new ArrayList<>();
    private String methodOverride;
    private int statusOverride = -1;
    private String reasonOverride;
    private boolean captureBody;
    private final AtomicBoolean answered = new AtomicBoolean();

    NetworkExchange(WebEngine engine, int interceptId, int phase,
                    NetworkRequest request, NetworkResponse response) {
        this.engine = engine;
        this.interceptId = interceptId;
        this.phase = phase;
        this.request = request;
        this.response = response;
    }

    /**
     * Returns the request being intercepted.
     * @return the request view
     */
    public NetworkRequest request() {
        return request;
    }

    /**
     * Returns the response, or {@code null} during the request phase.
     * @return the response view, or {@code null}
     */
    public NetworkResponse response() {
        return response;
    }

    /**
     * Returns whether a terminal decision has been made.
     * @return {@code true} once answered
     */
    public boolean isAnswered() {
        return answered.get();
    }

    // ---- Mutators (chainable; applied by proceedModified / proceed) -----

    /**
     * Sets (or replaces) a request header. Request phase only.
     * @param name the header name
     * @param value the header value
     * @return this exchange
     */
    public NetworkExchange setRequestHeader(String name, String value) {
        setHeaders.add(new String[] { name, value });
        return this;
    }

    /**
     * Removes a request header. Request phase only.
     * @param name the header name
     * @return this exchange
     */
    public NetworkExchange removeRequestHeader(String name) {
        removeHeaders.add(name);
        return this;
    }

    /**
     * Overrides the request method. Request phase only.
     * @param method the HTTP method
     * @return this exchange
     */
    public NetworkExchange setRequestMethod(String method) {
        this.methodOverride = method;
        return this;
    }

    /**
     * Sets (or replaces) a response header. Response phase only.
     * @param name the header name
     * @param value the header value
     * @return this exchange
     */
    public NetworkExchange setResponseHeader(String name, String value) {
        setHeaders.add(new String[] { name, value });
        return this;
    }

    /**
     * Removes a response header. Response phase only.
     * @param name the header name
     * @return this exchange
     */
    public NetworkExchange removeResponseHeader(String name) {
        removeHeaders.add(name);
        return this;
    }

    /**
     * Overrides the response status line. Response phase only.
     * @param code the status code
     * @param reason the reason phrase
     * @return this exchange
     */
    public NetworkExchange setResponseStatus(int code, String reason) {
        this.statusOverride = code;
        this.reasonOverride = reason;
        return this;
    }

    /**
     * Requests that this response's body be streamed to
     * {@link NetworkInterceptor#onBodyChunk}. Response phase; call before
     * {@link #proceed()}.
     */
    public void captureBody() {
        this.captureBody = true;
    }

    // ---- Terminal decisions --------------------------------------------

    /**
     * Continues the load. In the request phase this proceeds unmodified; in the
     * response phase it applies any status/header edits made on this exchange.
     */
    public void proceed() {
        if (phase == PHASE_RESPONSE) {
            resolve(A_RESPONSE_PROCEED, encodeEdits());
        } else if (setHeaders.isEmpty() && removeHeaders.isEmpty() && methodOverride == null) {
            resolve(A_PROCEED, new byte[0]);
        } else {
            resolve(A_PROCEED_MODIFIED, encodeEdits());
        }
    }

    /**
     * Continues the request with the accumulated header/method modifications.
     */
    public void proceedModified() {
        resolve(A_PROCEED_MODIFIED, encodeEdits());
    }

    /**
     * Blocks the load; the resource fails with a blocked-by-client error.
     */
    public void block() {
        resolve(A_BLOCK, new byte[0]);
    }

    /**
     * Redirects the request to {@code url}.
     * @param url the redirect target
     */
    public void redirect(String url) {
        resolve(A_REDIRECT, (url == null ? "" : url).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Serves a fully synthetic response, short-circuiting the network.
     * @param synthetic the response to serve
     */
    public void respondWith(SyntheticResponse synthetic) {
        resolve(A_SYNTHETIC, encodeSynthetic(synthetic));
    }

    /**
     * Replaces the entire response body with {@code body}. Response phase.
     * @param body the replacement body
     */
    public void replaceBody(byte[] body) {
        resolve(A_REPLACE_BODY, body == null ? new byte[0] : body.clone());
    }

    private void resolve(int action, byte[] tail) {
        if (!answered.compareAndSet(false, true)) {
            throw new IllegalStateException("NetworkExchange already answered");
        }
        // The capture flag rides the action byte's high bit on a response proceed.
        int effectiveAction = action;
        if (captureBody && action == A_RESPONSE_PROCEED) {
            effectiveAction |= 0x80;
        }
        engine.resolveNetwork(interceptId, phase, effectiveAction, tail);
    }

    /** Encodes header/method/status edits: see the engine seam for the layout. */
    private byte[] encodeEdits() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, setHeaders.size());
        for (String[] kv : setHeaders) {
            writeStr16(out, kv[0]);
            writeStr16(out, kv[1]);
        }
        writeU16(out, removeHeaders.size());
        for (String n : removeHeaders) {
            writeStr16(out, n);
        }
        writeStr16(out, methodOverride == null ? "" : methodOverride);
        writeU32(out, statusOverride);
        writeStr16(out, reasonOverride == null ? "" : reasonOverride);
        return out.toByteArray();
    }

    private static byte[] encodeSynthetic(SyntheticResponse r) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (r == null) {
            r = SyntheticResponse.builder().build();
        }
        writeU32(out, r.status());
        writeStr16(out, r.reason());
        String[] names = r.headerNames();
        String[] values = r.headerValues();
        writeU16(out, names.length);
        for (int i = 0; i < names.length; i++) {
            writeStr16(out, names[i]);
            writeStr16(out, values[i]);
        }
        byte[] body = r.body();
        writeU32(out, body.length);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static void writeStr16(ByteArrayOutputStream out, String s) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        writeU16(out, b.length);
        out.write(b, 0, b.length);
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, int v) {
        for (int i = 0; i < 4; i++) {
            out.write((v >>> (i * 8)) & 0xFF);
        }
    }
}
