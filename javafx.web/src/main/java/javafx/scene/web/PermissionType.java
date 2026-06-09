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
 * The kind of capability a web page is requesting via a {@link PermissionRequest}
 * (for example {@code navigator.geolocation}, {@code Notification.requestPermission},
 * or {@code getUserMedia}).
 *
 * <p>{@link #UNKNOWN} is a forward-compatibility fallback: a newer engine
 * permission that this API does not yet name surfaces as {@code UNKNOWN} rather
 * than failing, so application {@code switch} statements stay robust.
 *
 * @since 25
 */
public enum PermissionType {

    /** Access to the device's geographic location. */
    GEOLOCATION(0),
    /** Permission to display system notifications. */
    NOTIFICATIONS(1),
    /** Access to a camera (video capture). */
    CAMERA(2),
    /** Access to a microphone (audio capture). */
    MICROPHONE(3),
    /** Access to both camera and microphone. */
    CAMERA_AND_MICROPHONE(4),
    /** Read access to the system clipboard. */
    CLIPBOARD_READ(5),
    /** Access to MIDI devices. */
    MIDI(6),
    /** Access to MIDI devices including system-exclusive messages. */
    MIDI_SYSEX(7),
    /** Screen / window / tab capture. */
    SCREEN_CAPTURE(8),
    /** A request to persist site storage so it is not evicted under pressure. */
    PERSISTENT_STORAGE(9),
    /** Access to motion / orientation sensors. */
    SENSORS(10),
    /** Idle detection (whether the user is active). */
    IDLE_DETECTION(11),
    /** Multi-screen window placement. */
    WINDOW_MANAGEMENT(12),
    /** An engine permission not yet represented by this enum. */
    UNKNOWN(-1);

    private final int wireCode;

    PermissionType(int wireCode) {
        this.wireCode = wireCode;
    }

    // Internal: the code shared with the engine over the command/event ring.
    int wireCode() {
        return wireCode;
    }

    // Internal: maps an engine code back to a constant ({@link #UNKNOWN} fallback).
    static PermissionType fromWire(int code) {
        for (PermissionType t : values()) {
            if (t.wireCode == code) {
                return t;
            }
        }
        return UNKNOWN;
    }
}
