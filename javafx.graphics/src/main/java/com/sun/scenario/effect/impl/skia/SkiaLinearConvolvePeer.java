/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import java.lang.foreign.MemorySegment;

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.Graphics;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.MotionBlur;
import com.sun.scenario.effect.impl.EffectPeer;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.prism.PrEffectHelper;
import com.sun.scenario.effect.impl.state.LinearConvolveRenderState;

/**
 * Skia peer for the {@code "LinearConvolve"} dispatch name —
 * non-shadow linear convolution. Drives:
 *
 * <ul>
 *   <li>{@link com.sun.scenario.effect.GaussianBlur} — separable
 *       Gaussian kernel.</li>
 *   <li>{@link com.sun.scenario.effect.MotionBlur} — directional 1-D
 *       kernel (approximated via Gaussian in Skia; the kernel vector
 *       picks which axis gets blurred per pass).</li>
 * </ul>
 *
 * <p>Same pass-collapse pattern as {@link SkiaLinearConvolveShadowPeer}:
 * apply the full Skia Blur on pass 0, pass through on subsequent
 * passes. Skia's Blur is separable internally so a single call
 * covers both 1-D passes JFX would otherwise dispatch.</p>
 */
public class SkiaLinearConvolvePeer extends EffectPeer<LinearConvolveRenderState> {

    // Filter cache keyed by (sigmaX, sigmaY). Same rationale as
    // SkiaLinearConvolveShadowPeer — identical params per frame in the
    // common CSS case (e.g. a fixed-size GaussianBlur).
    private long cachedKey   = -1L;
    private MemorySegment cachedFilter;

    public SkiaLinearConvolvePeer(FilterContext fctx, Renderer r, String name) {
        super(fctx, r, name);
    }

    private static long key(float sigmaX, float sigmaY) {
        int qx = (int) (sigmaX * 16f + 0.5f);
        int qy = (int) (sigmaY * 16f + 0.5f);
        return ((long) (qx & 0xFFFFFFFFL) << 32) | (qy & 0xFFFFFFFFL);
    }

    private MemorySegment getOrCreateFilter(float sigmaX, float sigmaY) {
        long k = key(sigmaX, sigmaY);
        if (k == cachedKey && cachedFilter != null) {
            return cachedFilter;
        }
        if (cachedFilter != null) {
            NativeBridge.filterDestroy(cachedFilter);
            cachedFilter = null;
        }
        MemorySegment f = NativeBridge.filterBlur(sigmaX, sigmaY,
            NativeBridge.TILE_DECAL);
        if (f == null || f.equals(MemorySegment.NULL)) return null;
        cachedKey = k;
        cachedFilter = f;
        return f;
    }

    @Override
    public void dispose() {
        if (cachedFilter != null) {
            NativeBridge.filterDestroy(cachedFilter);
            cachedFilter = null;
            cachedKey = -1L;
        }
    }

    @Override
    public ImageData filter(Effect effect,
                            LinearConvolveRenderState rstate,
                            BaseTransform transform,
                            Rectangle outputClip,
                            ImageData... inputs) {
        final FilterContext fctx = getFilterContext();
        final ImageData srcData = inputs[0];

        // Drag-resize bypass — see SkiaLinearConvolveShadowPeer for rationale.
        if (SkiaEffectRenderer.shouldBypassForDrag()) {
            SkiaEffectRenderer.BYPASS_COUNT.incrementAndGet();
            srcData.addref();
            return srcData;
        }

        if (getPass() > 0) {
            srcData.addref();
            return srcData;
        }

        final Rectangle srcBounds = srcData.getTransformedBounds(null);

        float sigmaX;
        float sigmaY;
        if (effect instanceof MotionBlur) {
            // Directional 1-D motion kernel. Skia's Blur can't do a diagonal
            // kernel, so approximate it as an axis-aligned ellipse by splitting
            // the single sigma along the pass (direction) vector.
            final int kernelSize = rstate.getInputKernelSize(0);
            final float sigma = Math.max(0.001f, (kernelSize - 1) / 6.0f);
            sigmaX = sigma;
            sigmaY = sigma;
            float[] v = rstate.getPassVector();
            if (v != null && v.length >= 2) {
                float ax = Math.abs(v[0]);
                float ay = Math.abs(v[1]);
                float total = ax + ay;
                if (total > 0.001f) {
                    sigmaX = sigma * (ax / total);
                    sigmaY = sigma * (ay / total);
                    if (sigmaX < 0.001f && ax > 0f) sigmaX = 0.001f;
                    if (sigmaY < 0.001f && ay > 0f) sigmaY = 0.001f;
                }
            }
        } else {
            // Axis-aligned separable blur (GaussianBlur, BoxBlur). JFX runs it
            // as a horizontal pass (0) then a vertical pass (1); collapse both
            // into ONE Skia Blur using each axis's own kernel. Previously this
            // read only pass 0's kernel and split it by the pass-0 vector (1,0),
            // which zeroed sigmaY -> horizontal-only smear. Reading both passes'
            // kernels gives the correct isotropic 2-D blur (and honours an
            // anisotropic BoxBlur where hsize != vsize).
            final int ksX = rstate.getInputKernelSize(0);
            final int ksY = rstate.getInputKernelSize(1);
            sigmaX = Math.max(0.001f, (ksX - 1) / 6.0f);
            sigmaY = Math.max(0.001f, (ksY - 1) / 6.0f);
        }

        final int padX = (int) Math.ceil(sigmaX * 3f) + 1;
        final int padY = (int) Math.ceil(sigmaY * 3f) + 1;
        Rectangle dstBounds = new Rectangle(
            srcBounds.x - padX, srcBounds.y - padY,
            srcBounds.width  + 2 * padX,
            srcBounds.height + 2 * padY);
        dstBounds.intersectWith(outputClip);
        if (dstBounds.width <= 0 || dstBounds.height <= 0) {
            return new ImageData(fctx, null, srcBounds);
        }

        SkiaPrDrawable dst = (SkiaPrDrawable)
            getRenderer().getCompatibleImage(dstBounds.width, dstBounds.height);
        if (dst == null || !srcData.validate(fctx)) {
            return new ImageData(fctx, dst, dstBounds);
        }

        MemorySegment filter = getOrCreateFilter(sigmaX, sigmaY);
        if (filter == null) {
            return new ImageData(fctx, dst, dstBounds);
        }

        Graphics gdst = dst.createGraphics();
        if (gdst == null) {
            return new ImageData(fctx, dst, dstBounds);
        }
        MemorySegment dstSeg = MemorySegment.ofAddress(dst.getSurfaceHandle());
        if (dstSeg.equals(MemorySegment.NULL)) {
            return new ImageData(fctx, dst, dstBounds);
        }
        NativeBridge.surfaceSaveLayerWithFilter(dstSeg, filter);
        try {
            PrEffectHelper.renderImageData(gdst, srcData, dstBounds);
        } finally {
            NativeBridge.surfaceRestore(dstSeg);
        }
        return new ImageData(fctx, dst, dstBounds);
    }
}
