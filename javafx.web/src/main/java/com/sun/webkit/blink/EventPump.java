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
 * The single consumer of the event ring. Runs one daemon virtual thread that
 * polls events and invokes a {@link Handler} for each, then advances the ring.
 *
 * <p>This is the <b>only</b> thread that reads the event ring, which is what
 * makes the SPSC contract hold. The {@link EventSlot} passed to the handler is
 * valid only for the duration of the call (it points into the ring slot about
 * to be reclaimed), so the handler must copy out any data it needs and not
 * retain the slot. The handler runs on the pump thread; marshalling to the FX
 * thread is the handler's responsibility (see {@link BlinkPage}).
 *
 * <p>When the ring is empty the thread parks briefly (1 ms) rather than
 * busy-spinning. Internal.
 */
final class EventPump {

    /** Invoked on the pump thread for each event; the slot is valid only during the call. */
    @FunctionalInterface
    interface Handler {
        void handle(EventSlot slot);
    }

    private static final long IDLE_PARK_NANOS = 1_000_000L; // 1 ms

    private final EventRingBuffer ring;
    private final SharedMemoryChannel channel;
    private final Handler handler;
    private final EventSlot slot = new EventSlot();
    private volatile boolean running;
    private Thread thread;

    EventPump(EventRingBuffer ring, SharedMemoryChannel channel, Handler handler) {
        this.ring = ring;
        this.channel = channel;
        this.handler = handler;
    }

    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        // Platform (not virtual) thread: the pump does blocking native/shared-memory
        // reads and marshals frames to the FX thread; a virtual thread can be pinned
        // during those native calls and stall delivery (contributes to the
        // print/save modal-dialog hang). A dedicated daemon platform thread is steady.
        thread = Thread.ofPlatform()
                       .name("skia-fx-webview-event-pump")
                       .daemon(true)
                       .start(this::loop);
    }

    private void loop() {
        while (running) {
            boolean drained = false;
            try {
                if (channel.isClosed()) {
                    return;
                }
                while (running && ring.poll(slot)) {
                    try {
                        handler.handle(slot);
                    } catch (Throwable t) {
                        // A bad single event must not kill the pump.
                        System.getLogger(EventPump.class.getName())
                            .log(System.Logger.Level.WARNING,
                                "event handler failed for type 0x"
                                    + Integer.toHexString(slot.eventType), t);
                    }
                    ring.advance();
                    drained = true;
                }
            } catch (IllegalStateException closed) {
                return; // arena closed underneath us
            }
            if (!drained && running) {
                LockSupport.parkNanos(IDLE_PARK_NANOS);
            }
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
