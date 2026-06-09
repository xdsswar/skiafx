/*
 * Copyright (c) 2018, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.javafx.embed.swing.interop;

import java.awt.AWTEvent;
import java.awt.Container;
import java.awt.Component;
import java.awt.event.WindowFocusListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.KeyEvent;
import sun.awt.UngrabEvent;
import sun.awt.AWTAccessor;
import sun.swing.JLightweightFrame;

/**
 * Thin wrapper over the JDK-internal {@link sun.swing.JLightweightFrame}.
 *
 * <h2>Why this class lives in {@code javafx.swing} instead of the JDK</h2>
 * This is a verbatim port (repackaged from {@code jdk.swing.interop}) of the
 * wrapper the JDK used to ship in the {@code jdk.unsupported.desktop} module.
 * That module is deprecated and slated for removal from the JDK, so
 * {@code javafx.swing} can no longer {@code requires} it. Instead we keep our
 * own copy here and reach the underlying {@code sun.*} classes directly via
 * {@code --add-exports} (see {@code javafx.swing/build.gradle} and
 * {@code docs/MODULE_SWING_DROP_UNSUPPORTED_DESKTOP.md}).
 *
 * <p>The public surface is intentionally identical to the old
 * {@code jdk.swing.interop.LightweightFrameWrapper} so the callers in
 * {@code com.sun.javafx.embed.swing.newimpl} only changed their imports.
 *
 * <p>{@code JLightweightFrame} is {@code final}, so we wrap it by composition
 * rather than subclassing it.
 */
public class LightweightFrameWrapper {

    /** The wrapped JDK lightweight frame; never null after construction. */
    JLightweightFrame lwFrame;

    public LightweightFrameWrapper() {
        lwFrame = new JLightweightFrame();
    }

    private JLightweightFrame getLightweightFrame() {
        return lwFrame;
    }

    public void notifyDisplayChanged(final int scaleFactor) {
        if (lwFrame != null) {
            lwFrame.notifyDisplayChanged(scaleFactor, scaleFactor);
        }
    }

    public void notifyDisplayChanged(final double scaleFactorX,
                                     final double scaleFactorY) {
        if (lwFrame != null) {
            lwFrame.notifyDisplayChanged(scaleFactorX, scaleFactorY);
        }
    }

    /**
     * Hands a foreign native window handle (an FX window handle) to AWT.
     *
     * <p>Kept package-private with this exact name and the
     * {@code (long, Runnable)} signature on purpose: the glass native code
     * (see {@code Application._overrideNativeWindowHandle}) looks this method
     * up reflectively via JNI on whatever {@code Class} it is handed, so the
     * relocation to this package is transparent to the native side as long as
     * the name and descriptor are preserved. It is invoked from JNI, not from
     * Java, which is why it is not public.
     */
    void overrideNativeWindowHandle(long handle, Runnable closeWindow) {
        if (lwFrame != null) {
            lwFrame.overrideNativeWindowHandle(handle, closeWindow);
        }
    }

    public void setHostBounds(int x, int y, int w, int h) {
        if (lwFrame != null) {
            lwFrame.setHostBounds(x, y, w, h);
        }
    }

    public void dispose() {
        if (lwFrame != null) {
            lwFrame.dispose();
        }
    }

    public void addWindowFocusListener(WindowFocusListener listener) {
        if (lwFrame != null) {
            lwFrame.addWindowFocusListener(listener);
        }
    }

    public void setVisible(boolean visible) {
        if (lwFrame != null) {
            lwFrame.setVisible(visible);
        }
    }

    public void setBounds(int x, int y, int w, int h) {
        if (lwFrame != null) {
            lwFrame.setBounds(x, y, w, h);
        }
    }

    public void setContent(final LightweightContentWrapper lwCntWrapper) {
        if (lwFrame != null) {
            // getContent() is package-private; reachable because the wrapper
            // classes all live together in this interop package.
            lwFrame.setContent(lwCntWrapper.getContent());
        }
    }

    public void emulateActivation(boolean activate) {
        if (lwFrame != null) {
            lwFrame.emulateActivation(activate);
        }
    }

    // The create*Event helpers build AWT events whose source is the wrapped
    // lightweight frame. They take the wrapper (rather than reading our own
    // field) to mirror the original interop API the callers expect.

    public MouseEvent createMouseEvent(LightweightFrameWrapper lwFrame,
                            int swingID, long swingWhen, int swingModifiers,
                            int relX, int relY, int absX, int absY,
                            int clickCount, boolean swingPopupTrigger,
                            int swingButton) {
        return new java.awt.event.MouseEvent(lwFrame.getLightweightFrame(),
                                             swingID, swingWhen,
                                             swingModifiers,
                                             relX, relY, absX, absY, clickCount,
                                             swingPopupTrigger, swingButton);
    }

    public MouseWheelEvent createMouseWheelEvent(LightweightFrameWrapper lwFrame,
                            int swingModifiers, int x, int y, int wheelRotation) {
        return  new MouseWheelEvent(lwFrame.getLightweightFrame(),
                                    java.awt.event.MouseEvent.MOUSE_WHEEL,
                                    System.currentTimeMillis(),
                                    swingModifiers, x, y, 0, 0, 0, false,
                                    MouseWheelEvent.WHEEL_UNIT_SCROLL, 1,
                                    wheelRotation);
    }

    public KeyEvent createKeyEvent(LightweightFrameWrapper lwFrame,
                                   int swingID, long swingWhen,
                                   int swingModifiers,
                                   int swingKeyCode, char swingChar) {
        return new java.awt.event.KeyEvent(lwFrame.getLightweightFrame(),
                       swingID, swingWhen, swingModifiers, swingKeyCode,
                       swingChar);
    }

    public AWTEvent createUngrabEvent(LightweightFrameWrapper lwFrame) {
        return new UngrabEvent(lwFrame.getLightweightFrame());
    }

    public Component findComponentAt(LightweightFrameWrapper cont, int x, int y, boolean ignoreEnabled) {
        Container lwframe = cont.getLightweightFrame();
        // No public Container API exposes deep hit-testing, so go through the
        // AWTAccessor back door (same as the original JDK wrapper).
        return AWTAccessor.getContainerAccessor().findComponentAt(lwframe, x, y, ignoreEnabled);
    }

    public boolean isCompEqual(Component c, LightweightFrameWrapper lwFrame) {
        // NB: despite the name, this returns true when the component is NOT
        // the frame itself (preserved from the original interop contract).
        return c != lwFrame.getLightweightFrame();
    }
}
