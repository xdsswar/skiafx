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
package com.sun.javafx.webkit;

import com.sun.webkit.WebPage;

import javafx.beans.InvalidationListener;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Window;

/**
 * Keeps the off-screen engine window's screen origin aligned with the
 * {@link WebView} node's on-screen position, so Blink's native page-popups
 * (the {@code <select>} drop-down, colour picker, datalist) appear over the
 * control instead of at the engine window's (0,0) origin.
 *
 * <p>On the JavaFX Application Thread it recomputes {@code view.localToScreen(0,0)}
 * and pushes it (with the window's output scale) to {@link WebPage#setScreenOrigin}
 * whenever anything that moves the node on screen changes: the node's
 * scene-transform (layout/scroll within the scene), the owning window's position
 * or output scale, or the scene/window itself. Pushes are coalesced (only on an
 * actual change). All listeners are named and removed when the scene/window goes
 * away, so nothing leaks.
 *
 * <p>Internal; never exported from {@code javafx.web}.
 */
public final class WebViewScreenSync {

    private final WebView view;
    private final WebPage page;

    private final InvalidationListener relayout;   // transform / window geom / scale
    private final InvalidationListener sceneChanged;
    private final InvalidationListener windowChanged;

    private Scene boundScene;
    private Window boundWindow;

    private double lastX = Double.NaN, lastY = Double.NaN, lastScale = Double.NaN;

    public WebViewScreenSync(WebView view, WebPage page) {
        this.view = view;
        this.page = page;
        this.relayout = o -> push();
        this.sceneChanged = o -> { rebindScene(); push(); };
        this.windowChanged = o -> { rebindWindow(); push(); };
    }

    /** Installs the listeners and pushes the initial origin (if already shown). */
    public void install() {
        view.localToSceneTransformProperty().addListener(relayout);
        view.sceneProperty().addListener(sceneChanged);
        rebindScene();
        push();
    }

    private void rebindScene() {
        Scene scene = view.getScene();
        if (scene == boundScene) {
            rebindWindow();
            return;
        }
        if (boundScene != null) {
            boundScene.windowProperty().removeListener(windowChanged);
        }
        boundScene = scene;
        if (boundScene != null) {
            boundScene.windowProperty().addListener(windowChanged);
        }
        rebindWindow();
    }

    private void rebindWindow() {
        Window window = (boundScene == null) ? null : boundScene.getWindow();
        if (window == boundWindow) {
            return;
        }
        if (boundWindow != null) {
            boundWindow.xProperty().removeListener(relayout);
            boundWindow.yProperty().removeListener(relayout);
            boundWindow.outputScaleXProperty().removeListener(relayout);
        }
        boundWindow = window;
        if (boundWindow != null) {
            boundWindow.xProperty().addListener(relayout);
            boundWindow.yProperty().addListener(relayout);
            boundWindow.outputScaleXProperty().addListener(relayout);
        }
    }

    private void push() {
        Point2D origin = view.localToScreen(0, 0);
        if (origin == null) {
            return; // not in a shown scene yet
        }
        double scale = (boundWindow == null) ? 1.0 : boundWindow.getOutputScaleX();
        double sx = origin.getX();
        double sy = origin.getY();
        if (sx == lastX && sy == lastY && scale == lastScale) {
            return; // unchanged — don't spam the command ring
        }
        // A DPI / output-scale change (the window moved to a monitor with a
        // different scale) fires this listener on the FX thread BEFORE any paint.
        // Catch it here so we can re-push the viewport at the new render scale
        // immediately — see below.
        boolean scaleChanged = scale != lastScale;
        lastX = sx;
        lastY = sy;
        lastScale = scale;
        page.setScreenOrigin(sx, sy, scale);
        // Right-away resize: on a scale change, tell the engine to re-rasterize the
        // viewport at the new render scale NOW (which triggers its fast-capture
        // burst → fresh frames), rather than waiting for the next paint's
        // NGWebView.renderContent → setRenderScale. After a monitor move the pulse
        // loop can sit idle for ~seconds (the engine's capture even times out at
        // ~1s during the surface transition), so the paint-driven path is too slow
        // and the video freezes on a stale frame. setRenderScale no-ops when the
        // scale is unchanged, so origin-only pushes (scroll/drag) don't recapture.
        if (scaleChanged) {
            page.setRenderScale(scale);
        }
    }
}
