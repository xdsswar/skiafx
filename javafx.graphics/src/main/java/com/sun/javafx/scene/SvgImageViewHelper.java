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

package com.sun.javafx.scene;

import com.sun.javafx.geom.BaseBounds;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.sg.prism.NGNode;
import com.sun.javafx.util.Utils;
import javafx.scene.Node;
import javafx.scene.image.SvgImageView;

/**
 * Used to access internal methods of {@code SvgImageView}.
 *
 * <p>{@code SvgImageView extends ImageView}, but its rendering is completely
 * different (it rasterizes an SVG through Skia rather than sampling a raster),
 * so this helper extends {@link NodeHelper} <b>directly</b> rather than
 * {@code ImageViewHelper}. That deliberately bypasses {@code ImageView}'s
 * peer-update path (which assumes an {@code NGImageView} peer and a raster
 * {@code Image}) and routes to the SVG-specific {@code NGSvgImageView} peer
 * instead. The instance initializer in {@code SvgImageView} installs this
 * helper after {@code ImageView}'s constructor installed {@code ImageViewHelper},
 * so this one wins — the same override pattern {@code PerspectiveCamera} uses
 * over {@code Camera}.</p>
 */
public class SvgImageViewHelper extends NodeHelper {

    private static final SvgImageViewHelper theInstance;
    private static SvgImageViewAccessor svgImageViewAccessor;

    static {
        theInstance = new SvgImageViewHelper();
        Utils.forceInit(SvgImageView.class);
    }

    private static SvgImageViewHelper getInstance() {
        return theInstance;
    }

    public static void initHelper(SvgImageView svgImageView) {
        setHelper(svgImageView, getInstance());
    }

    @Override
    protected NGNode createPeerImpl(Node node) {
        return svgImageViewAccessor.doCreatePeer(node);
    }

    @Override
    protected void updatePeerImpl(Node node) {
        // Base Node sync (transforms, clip, effect, opacity, …) then the
        // SVG-specific peer state. Note: NOT ImageViewHelper.updatePeerImpl —
        // we must not push a raster image to an NGImageView peer.
        super.updatePeerImpl(node);
        svgImageViewAccessor.doUpdatePeer(node);
    }

    @Override
    protected BaseBounds computeGeomBoundsImpl(Node node, BaseBounds bounds,
            BaseTransform tx) {
        return svgImageViewAccessor.doComputeGeomBounds(node, bounds, tx);
    }

    @Override
    protected boolean computeContainsImpl(Node node, double localX, double localY) {
        return svgImageViewAccessor.doComputeContains(node, localX, localY);
    }

    public static void setSvgImageViewAccessor(final SvgImageViewAccessor newAccessor) {
        if (svgImageViewAccessor != null) {
            throw new IllegalStateException();
        }
        svgImageViewAccessor = newAccessor;
    }

    public interface SvgImageViewAccessor {
        NGNode doCreatePeer(Node node);
        void doUpdatePeer(Node node);
        BaseBounds doComputeGeomBounds(Node node, BaseBounds bounds, BaseTransform tx);
        boolean doComputeContains(Node node, double localX, double localY);
    }
}
