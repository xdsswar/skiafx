/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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

import java.awt.Component;
import java.awt.dnd.DragGestureRecognizer;
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.InvalidDnDOperationException;
import java.awt.dnd.peer.DragSourceContextPeer;
import java.awt.dnd.DropTarget;
import javax.swing.JComponent;
import sun.swing.LightweightContent;

/**
 * Abstract base for the FX "content" that a {@link LightweightFrameWrapper}
 * renders. Subclasses (notably the {@code SwingNodeContent} inside
 * {@code SwingNodeInteropN}) implement the abstract callbacks; this class wires
 * them to the JDK-internal {@link sun.swing.LightweightContent} interface
 * through the private {@link LightweightContentProxy}.
 *
 * <p>Verbatim port of {@code jdk.swing.interop.LightweightContentWrapper},
 * repackaged into {@code javafx.swing} so we no longer depend on the
 * deprecated {@code jdk.unsupported.desktop} module. See
 * {@link LightweightFrameWrapper} for the full rationale.
 *
 * <p>The proxy indirection is kept deliberately: it preserves the exact
 * behaviour of the old interop layer (for example {@code LightweightContent}'s
 * default {@code setCursor(Cursor)} stays a no-op because the proxy does not
 * override it).
 */
public abstract class LightweightContentWrapper {
    private LightweightContentProxy lwCnt;

    public LightweightContentWrapper() {
        lwCnt = new LightweightContentProxy();
    }

    /** Package-private: handed to {@link LightweightFrameWrapper#setContent}. */
    LightweightContentProxy getContent() {
        return lwCnt;
    }

    public abstract void imageBufferReset(int[] data, int x, int y, int width,
                                          int height, int linestride);

    public abstract void imageBufferReset(int[] data, int x, int y, int width,
                                     int height,
                                     int linestride, double scaleX,
                                     double scaleY);

    public abstract JComponent getComponent();

    public abstract void paintLock();

    public abstract void paintUnlock();

    public abstract void imageReshaped(int x, int y, int width, int height);

    public abstract void imageUpdated(int dirtyX, int dirtyY,int dirtyWidth,
                                      int dirtyHeight);

    public abstract void focusGrabbed();

    public abstract void focusUngrabbed();

    public abstract void preferredSizeChanged(int width, int height);

    public abstract void maximumSizeChanged(int width, int height);

    public abstract void minimumSizeChanged(int width, int height);

    public abstract <T extends DragGestureRecognizer> T createDragGestureRecognizer(
            Class<T> abstractRecognizerClass,
            DragSource ds, Component c, int srcActions,
            DragGestureListener dgl);

    public abstract DragSourceContextWrapper createDragSourceContext(DragGestureEvent dge)
                                            throws InvalidDnDOperationException;

    public abstract void addDropTarget(DropTarget dt);

    public abstract void removeDropTarget(DropTarget dt);

    /**
     * Adapts this wrapper to the JDK-internal {@code LightweightContent}
     * interface by forwarding every callback to the enclosing instance's
     * abstract methods.
     */
    private class LightweightContentProxy implements LightweightContent {

        public JComponent getComponent() {
            return LightweightContentWrapper.this.getComponent();
        }

        public void paintLock() {
            LightweightContentWrapper.this.paintLock();
        }

        public void paintUnlock() {
            LightweightContentWrapper.this.paintUnlock();
        }

        public void imageBufferReset(int[] data, int x, int y, int width,
                                     int height, int linestride) {
            LightweightContentWrapper.this.imageBufferReset(data, x, y, width,
                                     height, linestride);
        }

        public void imageBufferReset(int[] data, int x, int y, int width,
                                     int height, int linestride, double scaleX,
                                     double scaleY) {
            LightweightContentWrapper.this.imageBufferReset(data, x, y, width,
                                     height, linestride, scaleX, scaleY);
        }

        public void imageReshaped(int x, int y, int width, int height) {
            LightweightContentWrapper.this.imageReshaped(x, y, width, height);
        }

        public void imageUpdated(int dirtyX, int dirtyY,int dirtyWidth, int dirtyHeight) {
            LightweightContentWrapper.this.imageUpdated(dirtyX, dirtyY, dirtyWidth, dirtyHeight);
        }

        public void focusGrabbed() {
            LightweightContentWrapper.this.focusGrabbed();
        }

        public void focusUngrabbed() {
            LightweightContentWrapper.this.focusUngrabbed();
        }

        public void preferredSizeChanged(int width, int height) {
            LightweightContentWrapper.this.preferredSizeChanged(width, height);
        }

        public void maximumSizeChanged(int width, int height) {
            LightweightContentWrapper.this.maximumSizeChanged(width, height);
        }

        public void minimumSizeChanged(int width, int height) {
            LightweightContentWrapper.this.minimumSizeChanged(width, height);
        }

        public <T extends DragGestureRecognizer> T createDragGestureRecognizer(
            Class<T> abstractRecognizerClass,
            DragSource ds, Component c, int srcActions,
            DragGestureListener dgl) {
            return LightweightContentWrapper.this.createDragGestureRecognizer(
                          abstractRecognizerClass, ds, c, srcActions, dgl);
        }

        public DragSourceContextPeer createDragSourceContextPeer(DragGestureEvent dge)
                        throws InvalidDnDOperationException {
            DragSourceContextWrapper peerWrapper =
                    LightweightContentWrapper.this.createDragSourceContext(dge);
            // Unwrap to the real sun.awt.dnd peer that AWT expects.
            return peerWrapper.getPeer();
        }

        public void addDropTarget(DropTarget dt) {
            LightweightContentWrapper.this.addDropTarget(dt);
        }

        public void removeDropTarget(DropTarget dt) {
            LightweightContentWrapper.this.removeDropTarget(dt);
        }
    }
}
