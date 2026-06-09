/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import java.lang.foreign.MemorySegment;
import java.lang.ref.WeakReference;

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.scenario.effect.Color4f;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.EffectPeer;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.prism.PrEffectHelper;
import com.sun.scenario.effect.impl.state.LinearConvolveRenderState;
import com.sun.scenario.effect.impl.state.RenderState;

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
 *   <li><b>Pass 0:</b> apply {@code DropShadowOnly} with the full
 *       (sigmaX, sigmaY) derived from the state's kernel size, and the
 *       state's shadow colour. The output bounds expand by ~3σ on
 *       each side to give Skia's blur kernel room.</li>
 *   <li><b>Pass 1+:</b> pass through — the result from pass 0 is
 *       already the final filtered image.</li>
 * </ul>
 *
 * <p>The shadow's {@code dx,dy} offset is NOT applied here: the
 * {@code DropShadow} Effect class wraps this peer with an
 * {@code Offset} effect before merging, so the offset arrives at the
 * Merge intrinsic naturally. We pass {@code dx=dy=0} to
 * {@code DropShadowOnly}.</p>
 */
public class SkiaLinearConvolveShadowPeer extends EffectPeer<LinearConvolveRenderState> {

    // Filter-handle cache. The vast majority of shadow effect calls
    // come from Modena CSS at fixed params (e.g. scrollbar arrow
    // dropshadow: sigma≈0.167, black at 10% alpha) and stay identical
    // every frame. Creating + destroying a fresh sk_sp<SkImageFilter>
    // per call is pure CPU waste under continuous WM_SIZE → measurable
    // contributor to drag stutter even with a hit-rate-99% drawable pool.
    private long cachedKey   = -1L;
    private MemorySegment cachedFilter;

    // Phase B: SkImage output cache. The filter's rasterized output is
    // snapshot into an SkImage and replayed when called with the same
    // input identity + generation + state. On a cache hit we skip the
    // saveLayer + filter + restore Skia pipeline entirely and just
    // drawImage onto a fresh destination drawable. The cache key
    // includes the input drawable's generation (bumped in
    // SkiaPrDrawable.createGraphics) so any redraw of the input
    // invalidates correctly.
    private MemorySegment cachedOutputImage;
    private int           cachedOutputW, cachedOutputH;
    // WeakReference to the input drawable, compared by identity (==). A previous
    // version keyed on System.identityHashCode, which is RECYCLED after GC: a
    // different pooled drawable could land on the same hash and trigger a stale
    // shadow blit. A weak ref never pins the pooled drawable, and a GC'd/replaced
    // input fails the == check → cache miss → correct. (bugs.md M4)
    private WeakReference<Object> cachedInputRef;
    private long          cachedInputGen   = 0L;
    private long          cachedStateKey   = -1L;
    // Sampling offset (dstBounds - srcBounds) baked into the cached pixels.
    // renderImageData samples the source at this delta, so a clip that shifts
    // the output origin relative to the source changes the rasterized shadow
    // even when size/input/params match — must be part of the cache key.
    private int           cachedOffX = Integer.MIN_VALUE;
    private int           cachedOffY = Integer.MIN_VALUE;

    public SkiaLinearConvolveShadowPeer(FilterContext fctx, Renderer r, String name) {
        super(fctx, r, name);
    }

    /** Filter cache key: bits for sigma (rounded to /16th of pixel) +
     *  packed RGBA. Cheap, no allocation. */
    private static long key(float sigma, int r, int g, int b, int a) {
        int sigmaQ = (int) (sigma * 16f + 0.5f);
        return ((long) (sigmaQ & 0xFFFF) << 32)
             | ((r & 0xFF) << 24)
             | ((g & 0xFF) << 16)
             | ((b & 0xFF) <<  8)
             |  (a & 0xFF);
    }

    private MemorySegment getOrCreateFilter(float sigma, int r, int g, int b, int a) {
        long k = key(sigma, r, g, b, a);
        if (k == cachedKey && cachedFilter != null) {
            return cachedFilter;
        }
        // Params changed (or first call). Release old filter, create new.
        if (cachedFilter != null) {
            NativeBridge.filterDestroy(cachedFilter);
            cachedFilter = null;
        }
        MemorySegment f = NativeBridge.filterDropShadowOnly(
            0f, 0f, sigma, sigma, r, g, b, a);
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
        if (cachedOutputImage != null) {
            NativeBridge.imageDestroy(cachedOutputImage);
            cachedOutputImage = null;
            cachedInputRef = null;
            cachedInputGen = 0L;
            cachedStateKey = -1L;
        }
    }

    /** Compact identity + generation hash for an input ImageData.
     *  Identity uses the underlying drawable instance; generation is
     *  the drawable's draw-session counter. */
    private static long inputGenerationOf(ImageData img) {
        Object u = (img == null) ? null : img.getUntransformedImage();
        if (u instanceof SkiaPrDrawable d) return d.getGeneration();
        return 0L;
    }
    private static Object inputObjectOf(ImageData img) {
        return (img == null) ? null : img.getUntransformedImage();
    }

    /** State hash combining sigma quanta + packed RGBA. */
    private static long stateHash(float sigma, int r, int g, int b, int a) {
        int sigmaQ = (int) (sigma * 16f + 0.5f);
        return ((long) (sigmaQ & 0xFFFFL) << 32)
             | ((long) (r & 0xFF) << 24)
             | ((long) (g & 0xFF) << 16)
             | ((long) (b & 0xFF) <<  8)
             |  (long) (a & 0xFF);
    }

    @Override
    public ImageData filter(Effect effect,
                            LinearConvolveRenderState rstate,
                            BaseTransform transform,
                            Rectangle outputClip,
                            ImageData... inputs) {
        final FilterContext fctx = getFilterContext();
        final ImageData srcData = inputs[0];

        // Drag-resize bypass: while the user is mid-drag, skip the
        // per-frame saveLayer + filter + restore GPU pipeline cost
        // (the structural bottleneck that pooling can't fix). Effects
        // re-engage automatically the moment the drag ends. See
        // SKPICTURE_CACHING_DESIGN.md for the long-term fix.
        if (SkiaEffectRenderer.shouldBypassForDrag()) {
            SkiaEffectRenderer.BYPASS_COUNT.incrementAndGet();
            srcData.addref();
            return srcData;
        }

        // Subsequent passes pass through — pass 0 already produced
        // the full 2-D blurred + tinted result.
        if (getPass() > 0) {
            srcData.addref();
            return srcData;
        }

        final Rectangle srcBounds = srcData.getTransformedBounds(null);

        // JFX kernel size N → Skia Gaussian sigma (N - 1) / 6 (the
        // ~3σ rule of thumb that aligns with kernel half-width).
        final int kernelSize = rstate.getInputKernelSize(0);
        final float sigma = Math.max(0.001f, (kernelSize - 1) / 6.0f);

        // Expand bounds by ~3σ on each side to give the blur kernel
        // room (otherwise edges get clipped).
        final int pad = (int) Math.ceil(sigma * 3.0f) + 1;
        Rectangle dstBounds = new Rectangle(
            srcBounds.x - pad, srcBounds.y - pad,
            srcBounds.width  + 2 * pad,
            srcBounds.height + 2 * pad);
        dstBounds.intersectWith(outputClip);
        if (dstBounds.width <= 0 || dstBounds.height <= 0) {
            // Nothing to draw: the (possibly inverted/off-clip) source doesn't
            // intersect the output clip. PrEffectHelper/Merge skip this cleanly.
            return new ImageData(fctx, null, srcBounds);
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

        // ---- Phase B cache key ----------------------------------------
        // Hit when: same input drawable instance + same generation
        // (= input pixels unchanged since last call) + same shadow
        // params + same output dimensions.
        final Object inObj = inputObjectOf(srcData);
        final long inG  = inputGenerationOf(srcData);
        final long stK  = stateHash(sigma, r, g, b, a);
        final int  offX = dstBounds.x - srcBounds.x;
        final int  offY = dstBounds.y - srcBounds.y;
        final boolean cacheHit =
            cachedOutputImage != null
            && inObj != null
            && cachedInputRef != null
            && cachedInputRef.get() == inObj
            && inG == cachedInputGen
            && stK == cachedStateKey
            && cachedOutputW == dstBounds.width
            && cachedOutputH == dstBounds.height
            && cachedOffX == offX
            && cachedOffY == offY;

        MemorySegment dstSeg = MemorySegment.ofAddress(dst.getSurfaceHandle());
        if (dstSeg.equals(MemorySegment.NULL)) {
            return new ImageData(fctx, dst, dstBounds);
        }

        if (cacheHit) {
            // Blit cached rasterized shadow into a fresh dst. Skips the
            // saveLayer + filter + restore pipeline entirely → ~10×
            // faster on the hot drag-resize path. The drawable arrives
            // already cleared from SkiaEffectRenderer.getCompatibleImage,
            // and drawImage overwrites the whole content region.
            NativeBridge.surfaceDrawImage(dstSeg, cachedOutputImage,
                0f, 0f, dstBounds.width, dstBounds.height);
            return new ImageData(fctx, dst, dstBounds);
        }

        // ---- Cache miss: run the filter and snapshot the output ------
        // Shadow-only filter (no offset; the parent DropShadow effect
        // applies offset via a separate Offset effect in its chain).
        // Cached by (sigma, color) — identical-param consecutive calls
        // reuse the same SkImageFilter handle. No finally{destroy} —
        // dispose() releases on peer teardown.
        MemorySegment filter = getOrCreateFilter(sigma, r, g, b, a);
        if (filter == null) {
            return new ImageData(fctx, dst, dstBounds);
        }

        com.sun.prism.Graphics gdst = dst.createGraphics();
        if (gdst == null) {
            return new ImageData(fctx, dst, dstBounds);
        }
        NativeBridge.surfaceSaveLayerWithFilter(dstSeg, filter);
        try {
            // PrEffectHelper.renderImageData draws the source at the drawable
            // ORIGIN (0,0) sized to its 3rd arg, sampling the source texture at
            // (dstBounds - srcUntransformedBounds). It must therefore receive the
            // OUTPUT (dstBounds) and be given an UNtranslated Graphics — exactly
            // like PrMergePeer. The old code translated gdst by -dstBounds AND
            // passed srcBounds, which placed the source at device (-dstBounds.xy)
            // — almost entirely off the top-left of the output, leaving only a
            // tiny corner of shadow (the "ghost"). See the effect-dump captures.
            PrEffectHelper.renderImageData(gdst, srcData, dstBounds);
        } finally {
            NativeBridge.surfaceRestore(dstSeg);
        }

        // Snapshot the rasterized output for next time. The previous
        // cached image (if any) is released first.
        if (cachedOutputImage != null) {
            NativeBridge.imageDestroy(cachedOutputImage);
            cachedOutputImage = null;
        }
        MemorySegment snap = NativeBridge.surfaceSnapshotToImage(dstSeg);
        if (snap != null && !snap.equals(MemorySegment.NULL)) {
            cachedOutputImage = snap;
            cachedOutputW = dstBounds.width;
            cachedOutputH = dstBounds.height;
            cachedInputRef = (inObj == null) ? null : new WeakReference<>(inObj);
            cachedInputGen = inG;
            cachedStateKey = stK;
            cachedOffX = offX;
            cachedOffY = offY;
        }
        return new ImageData(fctx, dst, dstBounds);
    }
}
