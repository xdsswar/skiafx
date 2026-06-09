/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import com.sun.glass.ui.Screen;
import com.sun.prism.Graphics;
import com.sun.prism.GraphicsPipeline;
import com.sun.prism.Image;
import com.sun.prism.RTTexture;
import com.sun.prism.ResourceFactory;
import com.sun.prism.Texture.WrapMode;
import com.sun.scenario.effect.impl.HeapImage;
import com.sun.scenario.effect.impl.prism.PrDrawable;

import java.nio.IntBuffer;

/**
 * {@code PrDrawable} backed by a Skia {@code SkSurface} (via the
 * existing {@code SkiaRTTexture}).
 *
 * <p>Critically, this class also implements {@link HeapImage} so the
 * shared {@link com.sun.scenario.effect.impl.prism.PrCropPeer}/
 * {@link com.sun.scenario.effect.impl.prism.PrFloodPeer}/
 * {@link com.sun.scenario.effect.impl.prism.PrMergePeer}/
 * {@link com.sun.scenario.effect.impl.prism.PrReflectionPeer}
 * intrinsic peers — which expect to fall back on heap pixel access —
 * still work. The {@code getPixelArray} path performs one GPU→CPU
 * readback per request, lazily. Native Skia effect peers
 * ({@code Skia*Peer}) <b>never</b> touch this path; they operate
 * directly on the underlying {@code SkiaRTTexture}'s Skia surface
 * via {@code openjfx_skia_surface_save_layer_with_filter} +
 * {@code surface_draw_surface}, keeping everything GPU-resident.</p>
 */
public final class SkiaPrDrawable extends PrDrawable implements HeapImage {

    private RTTexture rtt;
    private Image image;

    /** Generation counter — bumped each time a draw session on this
     *  drawable's surface completes (see {@link #bumpGeneration()}).
     *  Used as a cache-validity key by Skia effect peers (Phase B of
     *  Task #31): if {@code (identityHashCode + generation + state)}
     *  match a cached SkPicture, the peer replays instead of re-running
     *  its filter chain. */
    private volatile long generation = 0L;

    private SkiaPrDrawable(RTTexture rtt) {
        super(rtt);
        this.rtt = rtt;
    }

    /** Current generation. Effect peers read this before deciding to
     *  cache-hit on this drawable. */
    public long getGeneration() {
        return generation;
    }

    /** Bumps the generation. Called externally by the Graphics layer
     *  (or test harness) when a draw session on this drawable's
     *  surface has finished and its pixel content is now considered
     *  "fresh". Peers checking the cache key after this point will
     *  see a different generation than they recorded against, and
     *  will invalidate / re-record. */
    public void bumpGeneration() {
        generation++;
        invalidateHeapCache();
    }

    /** Drops the lazy CPU readback so intrinsic peers re-read fresh pixels
     *  after the surface is redrawn (otherwise getPixelArray returns stale
     *  pixels for Crop/Flood/Merge/Reflection). */
    private void invalidateHeapCache() {
        heapPixels = null;
        heapScan = 0;
    }

    public static SkiaPrDrawable wrap(RTTexture rtt) {
        return new SkiaPrDrawable(rtt);
    }

    public static SkiaPrDrawable create(Screen screen, int width, int height) {
        ResourceFactory factory = GraphicsPipeline.getPipeline().getResourceFactory(screen);
        // CLAMP_TO_ZERO is what Decora-era effects want at the edges.
        RTTexture rtt = factory.createRTTexture(width, height, WrapMode.CLAMP_TO_ZERO);
        return new SkiaPrDrawable(rtt);
    }

    public static SkiaPrDrawable create(ResourceFactory factory, int width, int height) {
        RTTexture rtt = factory.createRTTexture(width, height, WrapMode.CLAMP_TO_ZERO);
        return new SkiaPrDrawable(rtt);
    }

    /** Native handle of the underlying Skia surface — for direct
     *  Skia-side filter chains in {@code Skia*Peer.filter}. */
    public long getSurfaceHandle() {
        if (rtt instanceof com.sun.prism.skia.SkiaRTTexture sk) {
            return sk.getNativeHandle();
        }
        return 0L;
    }

    /** Underlying {@link RTTexture} (a {@code SkiaRTTexture}). */
    @Override
    public RTTexture getTextureObject() {
        return rtt;
    }

    // ---- PrDrawable surface ------------------------------------------------

    @Override
    public Graphics createGraphics() {
        if (rtt == null) return null;
        // Bump generation BEFORE returning the Graphics — signals to any
        // SkPicture cache (Phase B of Task #31) that the drawable's
        // pixel content is about to change. A peer that recorded its
        // output against a previous generation will see the new value
        // and invalidate. Conservative: bumps even if the caller ends
        // up drawing identical pixels; per-content-hash invalidation
        // would be more precise but requires a GPU readback.
        generation++;
        invalidateHeapCache();
        return rtt.createGraphics();
    }

    @Override
    public boolean isLost() {
        return rtt == null || rtt.isSurfaceLost();
    }

    @Override
    public void flush() {
        if (rtt != null) {
            rtt.dispose();
            rtt = null;
            image = null;
        }
    }

    @Override
    public Object getData() {
        return this;
    }

    @Override
    public int getContentWidth()  { return rtt.getContentWidth(); }
    @Override
    public int getContentHeight() { return rtt.getContentHeight(); }
    @Override
    public int getMaxContentWidth()  { return rtt.getMaxContentWidth(); }
    @Override
    public int getMaxContentHeight() { return rtt.getMaxContentHeight(); }
    @Override
    public int getPhysicalWidth()  { return rtt.getPhysicalWidth(); }
    @Override
    public int getPhysicalHeight() { return rtt.getPhysicalHeight(); }

    // ---- HeapImage surface (lazy GPU→CPU readback fallback) ----------------
    //
    // Used only by the intrinsic-peer fallback paths (Crop/Flood/Merge/
    // Reflection) on rare inputs. Skia-native peers don't touch these.

    private int[] heapPixels;
    private int   heapScan;

    @Override
    public int getScanlineStride() {
        if (heapScan == 0) heapScan = rtt.getContentWidth();
        return heapScan;
    }

    @Override
    public int[] getPixelArray() {
        if (heapPixels == null) {
            int w = rtt.getContentWidth();
            int h = rtt.getContentHeight();
            heapPixels = new int[w * h];
            // Readback in INT_ARGB_PRE layout (matches HeapImage contract).
            rtt.readPixels(IntBuffer.wrap(heapPixels), 0, 0, w, h);
            heapScan = w;
        }
        return heapPixels;
    }
}
