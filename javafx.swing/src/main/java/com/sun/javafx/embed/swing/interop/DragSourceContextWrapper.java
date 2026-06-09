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

import java.awt.Cursor;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragSourceContext;
import java.awt.dnd.peer.DragSourceContextPeer;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.DataFlavor;
import java.util.Map;
import sun.awt.dnd.SunDragSourceContextPeer;

/**
 * Abstract base for the FX drag-source peer. Subclasses implement the
 * AWT-facing callbacks; this class adapts them onto the JDK-internal
 * {@link sun.awt.dnd.SunDragSourceContextPeer} via the private
 * {@link DragSourceContextPeerProxy}.
 *
 * <p>Verbatim port of {@code jdk.swing.interop.DragSourceContextWrapper},
 * repackaged into {@code javafx.swing} to drop the deprecated
 * {@code jdk.unsupported.desktop} dependency. See
 * {@link LightweightFrameWrapper} for the rationale.
 */
public abstract class DragSourceContextWrapper {
    private DragSourceContextPeerProxy dsp;

    public DragSourceContextWrapper(DragGestureEvent e) {
        dsp = new DragSourceContextPeerProxy(e);
    }

    /** The real AWT peer, handed back to {@code LightweightContentProxy}. */
    DragSourceContextPeer getPeer() {
        return dsp;
    }

    public static int convertModifiersToDropAction(int modifiers,
                                                   int supportedActions) {
        return DragSourceContextPeerProxy.
            convertModifiersToDropAction(modifiers, supportedActions);
    }

    protected abstract void setNativeCursor(Cursor c, int cType);

    protected abstract void startDrag(Transferable trans, long[] formats,
                                      Map<Long, DataFlavor> formatMap);

    public abstract void startSecondaryEventLoop();

    public abstract void quitSecondaryEventLoop();

    public void dragDropFinished(final boolean success,
                                 final int operations,
                                 final int x, final int y) {
        dsp.dragDropFinishedCall(success, operations, x, y);
    }

    public DragSourceContext getDragSourceContext() {
        return dsp.getDragSourceContextCall();
    }

    /**
     * Concrete {@code SunDragSourceContextPeer} that bounces every AWT call
     * back to the enclosing wrapper. The {@code *Call} bridge methods exist
     * because the matching wrapper methods are {@code protected}/{@code public}
     * on this outer class, not on the peer.
     */
    private class DragSourceContextPeerProxy extends SunDragSourceContextPeer {

        public DragSourceContextPeerProxy(DragGestureEvent e) {
            super(e);
        }

        protected void startDrag(Transferable trans, long[] formats,
                                 Map<Long, DataFlavor> formatMap) {
            DragSourceContextWrapper.this.startDrag(trans, formats, formatMap);
        }

        protected void setNativeCursor(long nativeCtxt, Cursor c, int cType) {
            // The FX side has no native drag context, so the long handle is
            // dropped and only (cursor, type) are forwarded.
            DragSourceContextWrapper.this.setNativeCursor(c, cType);
        }

        public void startSecondaryEventLoop() {
            DragSourceContextWrapper.this.startSecondaryEventLoop();
        }

        public void quitSecondaryEventLoop() {
            DragSourceContextWrapper.this.quitSecondaryEventLoop();
        }

        protected void dragDropFinishedCall(final boolean success,
                                 final int operations,
                                 final int x, final int y) {
            dragDropFinished(success, operations, x, y);
        }

        protected DragSourceContext getDragSourceContextCall() {
            return getDragSourceContext();
        }
    }
}
