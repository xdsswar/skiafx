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
 * The lifecycle state of a {@link Download}. {@link #UNKNOWN} is a
 * forward-compatibility fallback.
 *
 * @since 25
 */
public enum DownloadState {

    /** The download has been accepted and is starting. */
    STARTED(0),
    /** Bytes are being received. */
    IN_PROGRESS(1),
    /** All bytes received and written to the target file. */
    COMPLETED(2),
    /** Cancelled by the application or the page. */
    CANCELLED(3),
    /** Interrupted by an error (network failure, disk full, …). */
    INTERRUPTED(4),
    /** A state not yet represented by this enum. */
    UNKNOWN(-1);

    private final int wireCode;

    DownloadState(int wireCode) {
        this.wireCode = wireCode;
    }

    static DownloadState fromWire(int code) {
        for (DownloadState s : values()) {
            if (s.wireCode == code) {
                return s;
            }
        }
        return UNKNOWN;
    }
}
