/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.javafx.sg.prism;

import com.sun.prism.Graphics;
import com.sun.prism.skia.SkiaGraphics;

/**
 * Render peer for {@code javafx.scene.image.SvgImageView}.
 *
 * <p>The SVG is drawn <b>directly onto the scene surface as vectors</b>, under
 * the live device transform, via {@link SkiaGraphics#drawSvg}. There is no
 * intermediate raster texture: the SVG rasterizes through Skia at the exact
 * device resolution and sub-pixel position it occupies this frame, so it is
 * <b>pixel-perfect and crystal-clear at any zoom level or DPI</b> — no
 * resampling blur and no texture-size cap to lose sharpness past. The native
 * side clips the render to the node box, so a zoomed/oversized SVG never
 * overflows.</p>
 *
 * <p>The borrowed SVG handle is owned and freed by the {@code SvgImage}; the
 * native side rejects a stale handle, so a handle freed out from under the peer
 * degrades to drawing nothing rather than crashing.</p>
 */
public class NGSvgImageView extends NGNode {

    // --- synced from SvgImageView.doUpdatePeer (logical coordinates) ---
    private long svgHandle;             // borrowed; owned/freed by the SvgImage
    private float x, y;                 // node origin
    private float w, h;                 // logical display size (fit size × zoom)
    private int bgArgb;                 // 0 = transparent (skip)
    private int tintArgb;
    private int tintMode;               // 0 none, 1 SRC_IN, 2 MULTIPLY
    private int gridArgb;               // 0 = no grid
    private float gridCell;             // display-logical px between grid lines
    private float gridLineWidth;        // display-logical px line thickness

    // ---- peer state setters (called on the FX→render sync) -----------------

    public void setSvgHandle(long handle) {
        if (svgHandle != handle) {
            svgHandle = handle;
            visualsChanged();
        }
    }

    public void setPosition(float x, float y) {
        if (this.x != x || this.y != y) {
            this.x = x;
            this.y = y;
            geometryChanged();
        }
    }

    public void setDimensions(float w, float h) {
        if (this.w != w || this.h != h) {
            this.w = w;
            this.h = h;
            geometryChanged();
        }
    }

    public void setColors(int bgArgb, int tintArgb, int tintMode) {
        if (this.bgArgb != bgArgb || this.tintArgb != tintArgb || this.tintMode != tintMode) {
            this.bgArgb = bgArgb;
            this.tintArgb = tintArgb;
            this.tintMode = tintMode;
            visualsChanged();
        }
    }

    public void setGrid(int gridArgb, float gridCell, float gridLineWidth) {
        if (this.gridArgb != gridArgb || this.gridCell != gridCell
                || this.gridLineWidth != gridLineWidth) {
            this.gridArgb = gridArgb;
            this.gridCell = gridCell;
            this.gridLineWidth = gridLineWidth;
            visualsChanged();
        }
    }

    // ---- rendering ---------------------------------------------------------

    /** Counts actual SVG draws — used by tests to confirm a property change
     *  triggers a live repaint (no production reads). */
    public static volatile int RENDER_COUNT;

    @Override
    protected void renderContent(Graphics g) {
        // Note: !(w > 0) also rejects NaN (NaN comparisons are all false).
        if (svgHandle == 0L || !(w > 0) || !(h > 0)) {
            return;
        }
        // SVG rendering is Skia-specific (it draws an SkSVGDOM straight onto the
        // surface canvas). On any other pipeline there is nothing to draw.
        if (g instanceof SkiaGraphics sg) {
            sg.drawSvg(svgHandle, x, y, w, h,
                    bgArgb, tintArgb, tintMode, gridArgb, gridCell, gridLineWidth);
            RENDER_COUNT++;
        }
    }

    @Override
    protected boolean hasOverlappingContents() {
        // The SVG (with an optional grid) is composited in one pass; the native
        // side already clips it to the node box. Nothing self-overlaps in a way
        // that needs a layer.
        return false;
    }
}
