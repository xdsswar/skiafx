/*
 * Copyright (c) 2013, 2024, Oracle and/or its affiliates. All rights reserved.
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

import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.Graphics;
import com.sun.prism.RTTexture;
import com.sun.prism.ResourceFactory;
import com.sun.prism.skia.SkiaRTTexture;
import com.sun.prism.Texture;
import com.sun.prism.paint.Color;
import com.sun.prism.paint.Paint;

/**
 * @author Thor Johannesson
 */
public class NGSubScene extends NGNode {

    // The Scene logical dimensions (pre-pixel scaling)
    private float slWidth, slHeight;
    // The scaled dimensions last used in the rtt
    private double lastScaledW, lastScaledH;
    private RTTexture rtt;
    // skia-fx: the upstream resolveRTT (a scratch RT used to resolve a multisampled
    // SubScene buffer) is gone — our MSAA resolve happens inside the bgfx 3D target
    // (Target3D), so the RTT we composite is already single-sample + anti-aliased.
    private NGNode root = null;
    private boolean renderSG = true;
    // Depth and msaa are immutable states
    private final boolean depthBuffer;
    private final boolean msaa;

    // skia-fx: runtime MSAA sample count for the bgfx 3D target, controllable
    // per-SubScene at runtime via dev.skiafx.scene3d.Skia3D (SceneAntialiasing is
    // immutable in the public API). Encoding: -1 = use the pipeline default (when the
    // SubScene was created anti-aliased), 1 = AA off, 2/4/8 = explicit count. Pushed
    // onto the SubScene's RTT each render (see renderContent) so a change rebuilds the
    // native target on the next pulse.
    // volatile: written on the FX thread (Scene3D.setSampleCount → setMsaaSamples)
    // and read on the render thread (renderContent). Without it the render thread
    // can observe a stale value indefinitely, so a runtime AA toggle silently no-ops.
    private volatile int msaaSamples;

    public NGSubScene(boolean depthBuffer, boolean msaa) {
        this.depthBuffer = depthBuffer;
        this.msaa = msaa;
        // Anti-aliased SubScene → pipeline default sample count; otherwise off.
        this.msaaSamples = msaa ? -1 : 1;
    }

    private NGSubScene() {
        this(false, false);
    }

    public void setRoot(NGNode root) {
        this.root = root;
    }

    private Paint fillPaint;
    public void setFillPaint(Object paint) {
        fillPaint = (Paint)paint;
    }

    private NGCamera camera;
    public void setCamera(NGCamera camera) {
        this.camera = camera == null ? NGCamera.INSTANCE : camera;
    }

    public void setWidth(float width) {
        if (this.slWidth != width) {
            this.slWidth = width;
            geometryChanged();
            invalidateRTT();
        }
    }

    public void setHeight(float height) {
        if (this.slHeight != height) {
            this.slHeight = height;
            geometryChanged();
            invalidateRTT();
        }
    }

    private NGLightBase[] lights;

    public NGLightBase[] getLights() { return lights; }

    public void setLights(NGLightBase[] lights) {
        this.lights = lights;
    }

    public void markContentDirty() {
        visualsChanged();
    }

    @Override
    public void clearDirty() {
        super.clearDirty();
        if (root != null) {
            root.clearDirty();
        }
    }

    @Override
    protected void visualsChanged() {
        renderSG = true;
        super.visualsChanged();
    }

    @Override
    protected void geometryChanged() {
        renderSG = true;
        super.geometryChanged();
    }

    private void invalidateRTT() {
        if (rtt != null) {
            // TODO as possibile optimization by keeping old rtt if SubScene
            // becomes smaller
            rtt.dispose();
            rtt = null;
        }
    }

    @Override
    protected boolean hasOverlappingContents() {
        //TODO verify correctness
        return false;
    }

    private boolean isOpaque = false;
    private void applyBackgroundFillPaint(Graphics g) {
        isOpaque = true;
        if (fillPaint != null) {
            if (fillPaint instanceof Color) {
                Color fillColor = (Color)fillPaint;
                isOpaque = (fillColor.getAlpha() >= 1.0);
                g.clear(fillColor);
            } else {
                if (!fillPaint.isOpaque()) {
                    g.clear();
                    isOpaque = false;
                }
                g.setPaint(fillPaint);
                g.fillRect(0, 0, rtt.getContentWidth(), rtt.getContentHeight());
            }
        } else {
            isOpaque = false;
            // Default is transparent
            g.clear();
        }
    }

    @Override
    public void renderForcedContent(Graphics gOptional) {
        root.renderForcedContent(gOptional);
    }

    private static double hypot(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    // Allow the scaled size in pixels to vary by a distance approximately
    // large enough to affect the sampling result in a LINEAR interpolation.
    // If we move by 1/256th of a pixel from one color to the opposite color
    // then in the worst case the sample value might change by +/- 1 bit.
    static final double THRESHOLD = 1.0 / 256.0;
    @Override
    protected void renderContent(Graphics g) {
        if (slWidth <= 0.0 || slHeight <= 0.0) { return; }
        BaseTransform txform = g.getTransformNoClone();
        // logScaleX/Y is the LOGICAL render scale (~1.0): the Skia pipeline keeps the
        // device pixel scale OUT of the render transform (it is applied per-draw in
        // SkiaGraphics). The 3D model/camera math uses this logical scale (no zoom).
        double logScaleX = hypot(txform.getMxx(), txform.getMyx(), txform.getMzx());
        double logScaleY = hypot(txform.getMxy(), txform.getMyy(), txform.getMzy());
        // scaleX/Y is the full DEVICE scale (logical × pixel scale). We size the
        // SubScene RT — and therefore the native bgfx 3D target (getContentWidth) and
        // its viewport — in DEVICE pixels, exactly like the 2D back buffer. The 3D
        // then renders at native device resolution (pixel-perfect, not logical-then-
        // upscaled), and because the DEVICE size genuinely changes on a DPI / monitor-
        // scale move (the OS holds the LOGICAL size constant), the size-driven rebuild
        // below fires and the 3D re-renders at the new scale — tracking DPI like 2D.
        double scaleX = logScaleX * g.getPixelScaleFactorX();
        double scaleY = logScaleY * g.getPixelScaleFactorY();
        double scaledW = slWidth * scaleX;   // device px
        double scaledH = slHeight * scaleY;
        int rtWidth = (int) Math.ceil(scaledW - THRESHOLD);
        int rtHeight = (int) Math.ceil(scaledH - THRESHOLD);
        if (Math.max(Math.abs(scaledW - lastScaledW), Math.abs(scaledH - lastScaledH)) > THRESHOLD) {
            if (rtt != null &&
                (rtWidth != rtt.getContentWidth() ||
                 rtHeight != rtt.getContentHeight()))
            {
                invalidateRTT();
            }
            renderSG = true;
            lastScaledW = scaledW;
            lastScaledH = scaledH;
        }
        if (rtt != null) {
            rtt.lock();
            if (rtt.isSurfaceLost()) {
                renderSG = true;
                rtt = null;
            }
        }

        if (renderSG || !root.isClean()) {
            if (rtt == null) {
                ResourceFactory factory = g.getResourceFactory();
                rtt = factory.createRTTexture(rtWidth, rtHeight,
                                              Texture.WrapMode.CLAMP_TO_ZERO,
                                              msaa);
            }
            Graphics rttGraphics = rtt.createGraphics();
            // The pixel scale factors must be copied to the rttGraphics, otherwise the position
            // of the lights will not be scaled correctly on HiDPI displays like MacBooks' retina
            // displays.
            // Device pixel scale stays as a per-draw factor (2D children render at
            // device resolution into the device-sized RT, and light positions scale
            // correctly). The render transform carries only the LOGICAL scale, so the
            // 3D model/camera are NOT zoomed by the device scale — the bgfx target is
            // already device-sized and the camera fills its device viewport.
            rttGraphics.setPixelScaleFactors(g.getPixelScaleFactorX(), g.getPixelScaleFactorY());
            rttGraphics.scale((float) logScaleX, (float) logScaleY);
            rttGraphics.setLights(lights);

            rttGraphics.setDepthBuffer(depthBuffer);
            if (camera != null) {
                rttGraphics.setCamera(camera);
            }
            applyBackgroundFillPaint(rttGraphics);

            // skia-fx: push the per-SubScene MSAA sample count onto the RTT before
            // the scene-graph render — SkiaMeshView.render lazily creates/ rebuilds
            // the bgfx 3D target during root.render, so the sample count must be set
            // first. A runtime change (Skia3D.setAntiAliasing) thus takes effect on
            // the next pulse by rebuilding the target at the new sample count.
            if (rtt instanceof SkiaRTTexture skiaRtt) {
                skiaRtt.set3DSamples(msaaSamples);
            }

            root.render(rttGraphics);
            renderSG = false;
        }
        // skia-fx: MSAA is resolved INSIDE the bgfx 3D target — it renders
        // multisampled and resolves into the single-sample, Skia-wrapped color
        // (see Target3D in openjfx_skia3d_bridge.cpp), so the SubScene RTT handed
        // to us is always single-sample and already anti-aliased. The upstream
        // MSAA blit/resolve path is therefore unnecessary, and for 3D it is harmful:
        // it copies via blit() instead of drawTexture(), bypassing
        // SkiaGraphics.composite3DRtt (the zero-copy 3D composite) and dropping the
        // 3D content. Route every SubScene (msaa or not) through drawTexture so the
        // 3D pass composites correctly.
        g.drawTexture(rtt, 0, 0, (float) (rtWidth / scaleX), (float) (rtHeight / scaleY),
                      0, 0, rtWidth, rtHeight);
        rtt.unlock();
    }

    public NGCamera getCamera() {
        return camera;
    }

    /**
     * skia-fx: set the MSAA sample count for this SubScene's bgfx 3D target at
     * runtime ({@code -1} = pipeline default, {@code 1} = AA off, {@code 2/4/8} =
     * explicit). Forces a re-render so the target rebuilds on the next pulse.
     * Called by {@code dev.skiafx.scene3d.Skia3D}; a pulse must be requested
     * separately for it to take effect promptly.
     */
    public void setMsaaSamples(int samples) {
        if (samples != msaaSamples) {
            msaaSamples = samples;
            renderSG = true; // force the scene-graph to re-render into a fresh target
        }
    }

    /** skia-fx: current MSAA sample count (see {@link #setMsaaSamples(int)}). */
    public int getMsaaSamples() {
        return msaaSamples;
    }
}
