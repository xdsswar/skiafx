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
import com.sun.scenario.effect.impl.state.BoxRenderState;
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
 * apply the full 2-D Skia Blur on the FIRST pass that reaches this peer
 * and pass through on the following pass of the same render state.
 * Upstream skips a no-op pass entirely ({@code getPassPeer} returns null
 * when {@code isPassNop()}), so the first pass that reaches us is not
 * necessarily pass 0 — e.g. {@code BoxBlur(1, 10)} only dispatches
 * pass 1. Skia's Blur is separable internally so a single call covers
 * both 1-D passes JFX would otherwise dispatch.</p>
 */
public class SkiaLinearConvolvePeer extends EffectPeer<LinearConvolveRenderState> {

    // Filter cache keyed by (sigmaX, sigmaY). Same rationale as
    // SkiaLinearConvolveShadowPeer — identical params per frame in the
    // common CSS case (e.g. a fixed-size GaussianBlur).
    private long cachedKey   = -1L;
    private MemorySegment cachedFilter;

    // Render state whose full 2-D blur was already applied by an earlier
    // pass of the same LinearConvolveCoreEffect.filterImageDatas() call
    // (a fresh render state is created per call, and both passes resolve
    // to this same cached peer instance). The next pass for that state
    // passes through; any other pass does the work itself.
    private LinearConvolveRenderState workDoneFor;

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

    /**
     * Gaussian sigma for one axis of a linear-convolve render state.
     * Box kernels: {@code n} box passes of odd width {@code k} have
     * variance {@code n(k^2-1)/12}. Gaussian kernels: the upstream weights
     * use {@code sigma = radius / 3} with a kernel of {@code 1 + 2*ceil(r)}
     * taps, i.e. {@code (ksize - 1) / 6}.
     */
    static float sigmaFor(LinearConvolveRenderState rstate, int pass) {
        final int ks = rstate.getInputKernelSize(pass);
        if (ks <= 1) {
            return 0.001f;
        }
        if (rstate instanceof BoxRenderState box) {
            final int n = box.getBlurPasses();
            if (n <= 0) {
                return 0.001f;
            }
            final int k = (ks - 1) / n + 1;
            return Math.max(0.001f, (float) Math.sqrt(n * (k * (double) k - 1.0) / 12.0));
        }
        return Math.max(0.001f, (ks - 1) / 6.0f);
    }

    @Override
    public void dispose() {
        workDoneFor = null;
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

        // The 2-D blur was already applied by the earlier pass of this same
        // render state: pass through. Otherwise (pass 0, or pass 1 after a
        // skipped no-op pass 0) do the full blur here.
        if (getPass() > 0 && workDoneFor == rstate) {
            workDoneFor = null;
            srcData.addref();
            return srcData;
        }
        workDoneFor = (getPass() == 0) ? rstate : null;

        final Rectangle srcBounds = srcData.getTransformedBounds(null);

        float sigmaX;
        float sigmaY;
        if (effect instanceof MotionBlur) {
            // Directional 1-D motion kernel. Skia's Blur can't do a diagonal
            // kernel, so approximate it as an axis-aligned ellipse by splitting
            // the single sigma along the pass (direction) vector.
            final float sigma = sigmaFor(rstate, 0);
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
            sigmaX = sigmaFor(rstate, 0);
            sigmaY = sigmaFor(rstate, 1);
        }

        final int padX = (int) Math.ceil(sigmaX * 3f) + 1;
        final int padY = (int) Math.ceil(sigmaY * 3f) + 1;
        Rectangle dstBounds = new Rectangle(
            srcBounds.x - padX, srcBounds.y - padY,
            srcBounds.width  + 2 * padX,
            srcBounds.height + 2 * padY);
        if (outputClip != null) {
            // The kernel reads source pixels up to one pad OUTSIDE the clip,
            // and only pixels drawn into the layer reach it: cover the clip
            // grown by the pad (consumers clip the margin). Clipping to the
            // bare outputClip dropped that fringe -- e.g. InnerShadow's
            // opaque InvertMask pad never got blurred into the content.
            Rectangle reach = new Rectangle(outputClip);
            reach.grow(padX, padY);
            dstBounds.intersectWith(reach);
        }
        if (dstBounds.width <= 0 || dstBounds.height <= 0) {
            // Empty result: a real cleared drawable, like upstream (never image-less).
            return SkiaEffectRenderer.emptyResult(fctx, getRenderer(), dstBounds);
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
