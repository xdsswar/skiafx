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
 * Monitors the engine heartbeat on a daemon virtual thread. If the engine
 * timestamp fails to advance for {@link #STALE_THRESHOLD_NANOS} across
 * {@link #CONSECUTIVE_STALE_LIMIT} consecutive checks, it runs the
 * {@code onEngineDead} callback exactly once.
 *
 * <p>P1 policy: surface the death (the callback fires a load-failed to
 * listeners and tears the page down) rather than silently auto-restarting.
 * Internal.
 */
final class Watchdog {

    private static final long CHECK_INTERVAL_NANOS = 200_000_000L;  // 200 ms
    private static final long STALE_THRESHOLD_NANOS = 3_000_000_000L; // 3 s
    private static final int CONSECUTIVE_STALE_LIMIT = 5;

    private final SharedMemoryChannel channel;
    private final Runnable onEngineDead;
    private volatile boolean running;
    private boolean fired;
    private Thread thread;

    Watchdog(SharedMemoryChannel channel, Runnable onEngineDead) {
        this.channel = channel;
        this.onEngineDead = onEngineDead;
    }

    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = Thread.ofVirtual().name("skia-fx-webview-watchdog").start(this::loop);
    }

    private void loop() {
        long lastHeartbeat = 0L;
        long lastChangeAt = System.nanoTime();
        int staleStreak = 0;

        while (running) {
            LockSupport.parkNanos(CHECK_INTERVAL_NANOS);
            if (!running) {
                return;
            }
            long now = System.nanoTime();
            long hb;
            try {
                if (channel.isClosed()) {
                    return;
                }
                hb = channel.readEngineHeartbeat();
            } catch (IllegalStateException closed) {
                return;
            }

            if (hb != lastHeartbeat) {
                lastHeartbeat = hb;
                lastChangeAt = now;
                staleStreak = 0;
                continue;
            }
            // No advance: count it stale only once the threshold has elapsed.
            if (now - lastChangeAt >= STALE_THRESHOLD_NANOS) {
                if (++staleStreak >= CONSECUTIVE_STALE_LIMIT) {
                    fireOnce();
                    return;
                }
            }
        }
    }

    private void fireOnce() {
        synchronized (this) {
            if (fired) {
                return;
            }
            fired = true;
        }
        try {
            onEngineDead.run();
        } catch (Throwable ignore) {
            // recovery callback must not crash the watchdog thread
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
