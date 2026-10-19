/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import java.lang.foreign.MemorySegment;
import java.lang.ref.WeakReference;

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.prism.Graphics;
import com.sun.scenario.effect.BoxShadow;
import com.sun.scenario.effect.Color4f;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.GaussianShadow;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.EffectPeer;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.prism.PrEffectHelper;
import com.sun.scenario.effect.impl.state.BoxRenderState;
import com.sun.scenario.effect.impl.state.LinearConvolveRenderState;

/**
 * Skia peer for the {@code "LinearConvolveShadow"} dispatch name.
 *
 * <p>JFX uses this peer to build the shadow alpha image for
 * {@code DropShadow}, {@code InnerShadow}, {@code GaussianShadow},
 * and (as fallback) {@code BoxShadow}. The Effect class wrapping it
 * then composites that shadow over the source via {@code PrMergePeer}
 * (intrinsic, unchanged).</p>
 *
 * <p>JFX runs LinearConvolveShadow as two 1-D convolution passes
 * (horizontal then vertical), accumulating each pass's output as the
 * next pass's input. Skia's {@code DropShadowOnly} filter is
 * separable internally — so we collapse the two passes into one:</p>
 *
 * <ul>
 *   <li><b>First pass that reaches the peer:</b> apply
 *       {@code DropShadowOnly} with the full (sigmaX, sigmaY) derived from
 *       each axis' kernel size, the state's shadow colour and the spread
 *       alpha gain (below). The output bounds expand by ~3 sigma on each
 *       side. Upstream skips a no-op pass 0 (horizontal radius ~0) but
 *       never pass 1 of a shadow, so this can be pass 1 -- it must still
 *       colourise, otherwise a radius-0 DropShadow/InnerShadow shows a
 *       full-colour copy instead of a silhouette.</li>
 *   <li><b>Following pass of the same render state:</b> pass through.</li>
 * </ul>
 *
 * <p><b>Spread:</b> upstream renormalises one pass' kernel so its weights
 * sum to more than 1 (Gaussian {@code total += (w0 - total) * spread},
 * Box {@code sum += (1 - sum) * spread}), i.e. an alpha gain clamped to 1.
 * Reproduced as a clamped colour-matrix alpha gain on the opaque blurred
 * shadow, then multiplied by the shadow colour's alpha. The gain is applied
 * after the full 2-D blur rather than between the two 1-D passes (exact
 * when upstream picks pass 1 for the spread, the common case).</p>
 *
 * <p>The shadow's {@code dx,dy} offset is NOT applied here: the
 * {@code DropShadow} Effect class wraps this peer with an
 * {@code Offset} effect before merging, so the offset arrives at the
 * Merge intrinsic naturally. We pass {@code dx=dy=0} to
 * {@code DropShadowOnly}.</p>
 */
public class SkiaLinearConvolveShadowPeer extends EffectPeer<LinearConvolveRenderState> {

    /** Upper bound for the spread alpha gain (spread == 1 is a step). */
    private static final float MAX_SPREAD_GAIN = 4096f;

    // Filter-handle cache. The vast majority of shadow effect calls
    // come from Modena CSS at fixed params (e.g. scrollbar arrow
    // dropshadow: sigma~0.167, black at 10% alpha) and stay identical
    // every frame. Creating + destroying a fresh sk_sp<SkImageFilter>
    // per call is pure CPU waste under continuous WM_SIZE.
    private MemorySegment cachedFilter;
    private float cachedSigX = Float.NaN, cachedSigY = Float.NaN, cachedGain = Float.NaN;
    private int   cachedRGBA;

    // Output cache: the filter's rasterized output is snapshot into an
    // SkImage and replayed when called with the same input identity +
    // generation + params. The input drawable's generation is bumped in
    // SkiaPrDrawable.createGraphics so any redraw of the input invalidates.
    //
    // A snapshot is itself a GPU copy, so it is only taken once the same
    // key MISSES twice in a row (i.e. a genuinely static input being
    // re-filtered). With several shadowed nodes interleaving, every call is
    // a miss against a different key; snapshotting on each miss thrashed the
    // single entry and added a copy per call for no hit.
    private MemorySegment cachedOutputImage;
    private final Key cachedKey  = new Key();
    private final Key pendingKey = new Key();
    private final Key currentKey = new Key();

    // Render state whose full shadow was already produced by an earlier
    // pass of the same LinearConvolveCoreEffect.filterImageDatas() call
    // (a fresh render state is created per call; both passes resolve to
    // this same cached peer instance).
    private LinearConvolveRenderState workDoneFor;

    /** Mutable cache key; compared field-wise, not allocated per call. */
    private static final class Key {
        // WeakReference to the input drawable, compared by identity (==).
        // identityHashCode is RECYCLED after GC; a weak ref never pins the
        // pooled drawable, and a GC'd/replaced input fails the == check.
        // (bugs.md M4)
        WeakReference<Object> inputRef;
        Object input;          // currentKey only; cleared after each call
        long  gen;
        float sigX, sigY, gain;
        int   rgba;
        int   w, h;
        // Sampling offset (dstBounds - srcBounds) baked into the pixels.
        int   offX, offY;
        boolean valid;

        boolean matches(Key k) {
            return valid
                && k.input != null
                && inputRef != null
                && inputRef.get() == k.input
                && gen == k.gen
                && Float.compare(sigX, k.sigX) == 0
                && Float.compare(sigY, k.sigY) == 0
                && Float.compare(gain, k.gain) == 0
                && rgba == k.rgba
                && w == k.w && h == k.h
                && offX == k.offX && offY == k.offY;
        }

        void copyFrom(Key k) {
            if (inputRef == null || inputRef.get() != k.input) {
                inputRef = (k.input == null) ? null : new WeakReference<>(k.input);
            }
            gen = k.gen;
            sigX = k.sigX; sigY = k.sigY; gain = k.gain;
            rgba = k.rgba;
            w = k.w; h = k.h;
            offX = k.offX; offY = k.offY;
            valid = true;
        }

        void clear() {
            inputRef = null;
            input = null;
            valid = false;
        }
    }

    public SkiaLinearConvolveShadowPeer(FilterContext fctx, Renderer r, String name) {
        super(fctx, r, name);
    }

    private MemorySegment getOrCreateFilter(float sigX, float sigY, float gain,
                                            int r, int g, int b, int a) {
        final int rgba = (r << 24) | (g << 16) | (b << 8) | a;
        if (cachedFilter != null
            && Float.compare(sigX, cachedSigX) == 0
            && Float.compare(sigY, cachedSigY) == 0
            && Float.compare(gain, cachedGain) == 0
            && rgba == cachedRGBA) {
            return cachedFilter;
        }
        // Params changed (or first call). Release old filter, create new.
        if (cachedFilter != null) {
            NativeBridge.filterDestroy(cachedFilter);
            cachedFilter = null;
        }
        MemorySegment f = (gain > 1f)
            ? createSpreadFilter(sigX, sigY, gain, r, g, b, a)
            : NativeBridge.filterDropShadowOnly(0f, 0f, sigX, sigY, r, g, b, a);
        if (f == null || f.equals(MemorySegment.NULL)) return null;
        cachedSigX = sigX;
        cachedSigY = sigY;
        cachedGain = gain;
        cachedRGBA = rgba;
        cachedFilter = f;
        return f;
    }

    /**
     * alpha' = clamp(gain * blurredAlpha) * colourAlpha, rgb = shadow rgb.
     * Built as ColorMatrix(alpha*colourA) o ColorMatrix(alpha*gain, clamped)
     * o DropShadowOnly(opaque colour). Intermediate handles are released
     * once composed (the native compose holds its own refs).
     */
    private static MemorySegment createSpreadFilter(float sigX, float sigY, float gain,
                                                    int r, int g, int b, int a) {
        MemorySegment shadow = NativeBridge.filterDropShadowOnly(
            0f, 0f, sigX, sigY, r, g, b, 255);
        if (shadow == null || shadow.equals(MemorySegment.NULL)) return null;
        MemorySegment gainCm = SkiaColorMatrixPeerBase.createColorMatrixFilter(alphaScale(gain));
        if (gainCm == null) {
            NativeBridge.filterDestroy(shadow);
            return null;
        }
        MemorySegment spread = NativeBridge.filterCompose(gainCm, shadow);
        NativeBridge.filterDestroy(gainCm);
        NativeBridge.filterDestroy(shadow);
        if (spread == null || spread.equals(MemorySegment.NULL)) return null;
        if (a >= 255) {
            return spread;
        }
        MemorySegment colorCm = SkiaColorMatrixPeerBase.createColorMatrixFilter(alphaScale(a / 255f));
        if (colorCm == null) {
            NativeBridge.filterDestroy(spread);
            return null;
        }
        MemorySegment out = NativeBridge.filterCompose(colorCm, spread);
        NativeBridge.filterDestroy(colorCm);
        NativeBridge.filterDestroy(spread);
        return (out == null || out.equals(MemorySegment.NULL)) ? null : out;
    }

    private static float[] alphaScale(float s) {
        return new float[] {
            1, 0, 0, 0, 0,
            0, 1, 0, 0, 0,
            0, 0, 1, 0, 0,
            0, 0, 0, s, 0,
        };
    }

    private static float spreadOf(Effect effect, LinearConvolveRenderState rstate) {
        if (effect instanceof GaussianShadow gs) return gs.getSpread();
        if (effect instanceof BoxShadow bs) return bs.getSpread();
        if (rstate instanceof BoxRenderState box) return box.getSpread();
        return 0f;
    }

    /**
     * Alpha gain equivalent to upstream's spread renormalisation of the
     * spread pass' 1-D kernel (see class doc). Mirrors the spread-pass
     * choice of GaussianRenderState / BoxRenderState from the per-axis
     * kernel sizes.
     */
    static float spreadGain(LinearConvolveRenderState rstate, float spread) {
        if (!(spread > 0f)) return 1f;
        if (spread > 1f) spread = 1f;
        final int ks0 = rstate.getInputKernelSize(0);
        final int ks1 = rstate.getInputKernelSize(1);
        double total;
        double totalSpread;
        if (rstate instanceof BoxRenderState box) {
            final int ks = (ks1 > 1) ? ks1 : ks0;
            final int n = box.getBlurPasses();
            if (n <= 0 || ks <= 1) return 1f;
            final int k = (ks - 1) / n + 1;
            total = Math.pow(k, n);
            totalSpread = total + (1.0 - total) * spread;
        } else {
            final int ks = (ks1 > 3 || ks1 >= ks0) ? ks1 : ks0;
            final int pad = (ks - 1) / 2;
            if (pad <= 0) return 1f;
            final double sigma = pad / 3.0;
            final double sigma22 = 2.0 * sigma * sigma;
            total = 0.0;
            for (int i = -pad; i <= pad; i++) {
                total += Math.exp(-(i * (double) i) / sigma22);
            }
            final double w0 = Math.exp(-(pad * (double) pad) / sigma22);
            totalSpread = total + (w0 - total) * spread;
        }
        if (!(totalSpread > 0.0)) return MAX_SPREAD_GAIN;
        return (float) Math.min(MAX_SPREAD_GAIN, Math.max(1.0, total / totalSpread));
    }

    @Override
    public void dispose() {
        workDoneFor = null;
        if (cachedFilter != null) {
            NativeBridge.filterDestroy(cachedFilter);
            cachedFilter = null;
            cachedSigX = cachedSigY = cachedGain = Float.NaN;
        }
        if (cachedOutputImage != null) {
            NativeBridge.imageDestroy(cachedOutputImage);
            cachedOutputImage = null;
        }
        cachedKey.clear();
        pendingKey.clear();
        currentKey.clear();
    }

    /** Generation (draw-session counter) of an input's drawable. */
    private static long inputGenerationOf(ImageData img) {
        Object u = (img == null) ? null : img.getUntransformedImage();
        if (u instanceof SkiaPrDrawable d) return d.getGeneration();
        return 0L;
    }
    private static Object inputObjectOf(ImageData img) {
        return (img == null) ? null : img.getUntransformedImage();
    }

    @Override
    public ImageData filter(Effect effect,
                            LinearConvolveRenderState rstate,
                            BaseTransform transform,
                            Rectangle outputClip,
                            ImageData... inputs) {
        final FilterContext fctx = getFilterContext();
        final ImageData srcData = inputs[0];

        // Drag-resize bypass (opt-in, -Dskia.effects.gateOnResize).
        if (SkiaEffectRenderer.shouldBypassForDrag()) {
            SkiaEffectRenderer.BYPASS_COUNT.incrementAndGet();
            srcData.addref();
            return srcData;
        }

        // The earlier pass of this same render state already produced the
        // full 2-D blurred + tinted result: pass through. Otherwise (pass 0,
        // or pass 1 after upstream skipped a no-op pass 0 -- a shadow's
        // pass 1 is never skipped) do the work here.
        if (getPass() > 0 && workDoneFor == rstate) {
            workDoneFor = null;
            srcData.addref();
            return srcData;
        }
        workDoneFor = (getPass() == 0) ? rstate : null;

        final Rectangle srcBounds = srcData.getTransformedBounds(null);

        final float sigmaX = SkiaLinearConvolvePeer.sigmaFor(rstate, 0);
        final float sigmaY = SkiaLinearConvolvePeer.sigmaFor(rstate, 1);
        final float gain   = spreadGain(rstate, spreadOf(effect, rstate));

        // Expand bounds by ~3 sigma on each side to give the blur kernel
        // room (otherwise edges get clipped).
        final int padX = (int) Math.ceil(sigmaX * 3.0f) + 1;
        final int padY = (int) Math.ceil(sigmaY * 3.0f) + 1;
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
            // Nothing to draw: the (possibly inverted/off-clip) source doesn't
            // intersect the output clip.
            // Empty result: a real cleared drawable, like upstream (never image-less).
            return SkiaEffectRenderer.emptyResult(fctx, getRenderer(), dstBounds);
        }

        SkiaPrDrawable dst = (SkiaPrDrawable)
            getRenderer().getCompatibleImage(dstBounds.width, dstBounds.height);
        if (dst == null || !srcData.validate(fctx)) {
            return new ImageData(fctx, dst, dstBounds);
        }

        final Color4f c = rstate.getShadowColor();
        final int r = (int) (c.getRed()   * 255f + 0.5f);
        final int g = (int) (c.getGreen() * 255f + 0.5f);
        final int b = (int) (c.getBlue()  * 255f + 0.5f);
        final int a = (int) (c.getAlpha() * 255f + 0.5f);

        MemorySegment dstSeg = MemorySegment.ofAddress(dst.getSurfaceHandle());
        if (dstSeg.equals(MemorySegment.NULL)) {
            return new ImageData(fctx, dst, dstBounds);
        }

        final Key k = currentKey;
        k.input = inputObjectOf(srcData);
        try {
            k.gen  = inputGenerationOf(srcData);
            k.sigX = sigmaX;
            k.sigY = sigmaY;
            k.gain = gain;
            k.rgba = (r << 24) | (g << 16) | (b << 8) | a;
            k.w    = dstBounds.width;
            k.h    = dstBounds.height;
            k.offX = dstBounds.x - srcBounds.x;
            k.offY = dstBounds.y - srcBounds.y;

            if (cachedOutputImage != null && cachedKey.matches(k)) {
                // Blit the cached rasterized shadow into the fresh (already
                // cleared) dst; skips saveLayer + filter + restore entirely.
                NativeBridge.surfaceDrawImage(dstSeg, cachedOutputImage,
                    0f, 0f, dstBounds.width, dstBounds.height);
                return new ImageData(fctx, dst, dstBounds);
            }

            // ---- Cache miss: run the filter ---------------------------
            // Shadow-only filter (no offset; the parent DropShadow effect
            // applies offset via a separate Offset effect in its chain).
            MemorySegment filter = getOrCreateFilter(sigmaX, sigmaY, gain, r, g, b, a);
            if (filter == null) {
                return new ImageData(fctx, dst, dstBounds);
            }

            Graphics gdst = dst.createGraphics();
            if (gdst == null) {
                return new ImageData(fctx, dst, dstBounds);
            }
            NativeBridge.surfaceSaveLayerWithFilter(dstSeg, filter);
            try {
                // PrEffectHelper.renderImageData draws the source at the
                // drawable ORIGIN (0,0) sized to its 3rd arg, sampling the
                // source texture at (dstBounds - srcUntransformedBounds). It
                // must therefore receive the OUTPUT (dstBounds) and an
                // UNtranslated Graphics -- exactly like PrMergePeer.
                PrEffectHelper.renderImageData(gdst, srcData, dstBounds);
            } finally {
                NativeBridge.surfaceRestore(dstSeg);
            }

            // Snapshot only when this exact key already missed on the
            // previous call (static input re-filtered); else remember it.
            if (k.input != null) {
                if (pendingKey.matches(k)) {
                    MemorySegment snap = NativeBridge.surfaceSnapshotToImage(dstSeg);
                    if (snap != null && !snap.equals(MemorySegment.NULL)) {
                        if (cachedOutputImage != null) {
                            NativeBridge.imageDestroy(cachedOutputImage);
                        }
                        cachedOutputImage = snap;
                        cachedKey.copyFrom(k);
                    }
                    pendingKey.clear();
                } else {
                    pendingKey.copyFrom(k);
                }
            }
            return new ImageData(fctx, dst, dstBounds);
        } finally {
            k.input = null;
        }
    }
}
