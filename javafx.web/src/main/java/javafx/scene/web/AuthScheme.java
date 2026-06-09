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
 * The HTTP authentication scheme of an {@link AuthRequest}. {@link #UNKNOWN} is
 * a forward-compatibility fallback for a scheme not yet named here.
 *
 * @since 25
 */
public enum AuthScheme {

    /** HTTP Basic authentication. */
    BASIC(0),
    /** HTTP Digest authentication. */
    DIGEST(1),
    /** Windows NTLM authentication. */
    NTLM(2),
    /** SPNEGO / Kerberos ("Negotiate") authentication. */
    NEGOTIATE(3),
    /** A proxy-server authentication challenge. */
    PROXY(4),
    /** A scheme not yet represented by this enum. */
    UNKNOWN(-1);

    private final int wireCode;

    AuthScheme(int wireCode) {
        this.wireCode = wireCode;
    }

    static AuthScheme fromWire(int code) {
        for (AuthScheme s : values()) {
            if (s.wireCode == code) {
                return s;
            }
        }
        return UNKNOWN;
    }
}
