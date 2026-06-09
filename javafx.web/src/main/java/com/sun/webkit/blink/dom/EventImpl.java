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
package com.sun.webkit.blink.dom;

import org.w3c.dom.events.Event;
import org.w3c.dom.events.EventTarget;

/**
 * Minimal {@code org.w3c.dom.events.Event} delivered to Java listeners when a
 * registered Blink event fires. Populated from the engine's interaction-event
 * payload; phase is always {@code AT_TARGET} in this slice (capture/bubble
 * ancestor walking is a documented follow-up).
 */
class EventImpl implements Event {

    private final String type;
    private final EventTarget target;
    private short phase = AT_TARGET;
    private boolean propagationStopped;
    private boolean defaultPrevented;

    EventImpl(String type, EventTarget target) {
        this.type = type;
        this.target = target;
    }

    @Override public String getType() {
        return type;
    }

    @Override public EventTarget getTarget() {
        return target;
    }

    @Override public EventTarget getCurrentTarget() {
        return target;
    }

    @Override public short getEventPhase() {
        return phase;
    }

    @Override public boolean getBubbles() {
        return true;
    }

    @Override public boolean getCancelable() {
        return true;
    }

    @Override public long getTimeStamp() {
        return 0L; // wall-clock stamping is a follow-up (Date.now() is unavailable here)
    }

    @Override public void stopPropagation() {
        propagationStopped = true;
    }

    @Override public void preventDefault() {
        defaultPrevented = true;
    }

    boolean isPropagationStopped() {
        return propagationStopped;
    }

    boolean isDefaultPrevented() {
        return defaultPrevented;
    }

    @Override public void initEvent(String eventTypeArg, boolean canBubbleArg,
                                    boolean cancelableArg) {
        // Synthetic re-init is not used on the delivery path; no-op.
    }
}
