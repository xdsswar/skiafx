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

import java.util.concurrent.locks.LockSupport;

/**
 * Writes the JVM-side heartbeat timestamp into the channel at a fixed interval
 * on a daemon virtual thread, so the engine can detect a frozen JVM. One per
 * channel. Stopping is idempotent and joins promptly. Internal.
 */
final class HeartbeatWriter {

    private static final long INTERVAL_NANOS = 200_000_000L; // 200 ms

    private final SharedMemoryChannel channel;
    private volatile boolean running;
    private Thread thread;

    HeartbeatWriter(SharedMemoryChannel channel) {
        this.channel = channel;
    }

    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = Thread.ofVirtual().name("skia-fx-webview-heartbeat").start(this::loop);
    }

    private void loop() {
        while (running) {
            try {
                if (channel.isClosed()) {
                    return;
                }
                channel.writeJavaHeartbeat();
            } catch (IllegalStateException closed) {
                return; // arena closed underneath us — stop quietly
            } catch (Throwable ignore) {
                // best-effort liveness; never let this thread die loudly
            }
            LockSupport.parkNanos(INTERVAL_NANOS);
        }
    }

    synchronized void stop() {
        running = false;
        if (thread != null) {
            LockSupport.unpark(thread);
            thread = null;
        }
    }
}
