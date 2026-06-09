/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Constructor;

import com.sun.glass.ui.Screen;
import com.sun.javafx.tk.quantum.QuantumToolkit;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.Graphics;
import com.sun.prism.GraphicsPipeline;
import com.sun.prism.Image;
import com.sun.prism.RTTexture;
import com.sun.prism.ResourceFactory;
import com.sun.prism.Texture;
import com.sun.prism.Texture.WrapMode;
import com.sun.scenario.effect.Effect.AccelType;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.Filterable;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.LockableResource;
import com.sun.scenario.effect.impl.EffectPeer;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.prism.PrDrawable;
import com.sun.scenario.effect.impl.prism.PrEffectHelper;
import com.sun.scenario.effect.impl.prism.PrImage;
import com.sun.scenario.effect.impl.prism.PrRenderer;

/**
 * Effect renderer whose drawables are Skia {@code SkSurface}-backed and
 * whose per-effect peers apply {@code SkImageFilter} chains entirely on
 * the GPU. Replaces the Prism-era {@code PSWRenderer} / {@code PPSRenderer}
 * — the only Prism effect engine that was still doing GPU→CPU readbacks
 * per frame (see CLAUDE.md "Effects → SkImageFilter chains").
 *
 * <p>Peer lookup convention:
 * {@code com.sun.scenario.effect.impl.skia.Skia<Name>Peer}.
 * Intrinsic peers (Crop/Flood/Merge/Reflection) come from
 * {@code com.sun.scenario.effect.impl.prism.Pr<Name>Peer} — the
 * existing pipeline-agnostic implementations work unchanged on top of
 * a {@link SkiaPrDrawable}.</p>
 */
public final class SkiaEffectRenderer extends PrRenderer {

    private final Screen screen;
    private final ResourceFactory factory;
    private RendererState state = RendererState.OK;

    private SkiaEffectRenderer(Screen screen, ResourceFactory factory) {
        this.screen = screen;
        this.factory = factory;
    }

    /** Called via reflection by {@code PrRenderer.createRenderer}. */
    public static Renderer createRenderer(FilterContext fctx) {
        Object ref = fctx.getReferent();
        if (ref instanceof Screen s) {
            return new SkiaEffectRenderer(s, null);
        }
        if (ref instanceof ResourceFactory rf) {
            return new SkiaEffectRenderer(null, rf);
        }
        return null;
    }

    // ---- PrRenderer surface ------------------------------------------------

    @Override
    public PrDrawable createDrawable(RTTexture rtt) {
        return SkiaPrDrawable.wrap(rtt);
    }

    // ---- Renderer surface --------------------------------------------------

    @Override
    public AccelType getAccelType() {
        // Skia's filters run on Ganesh (GPU); when GPU init fails Skia
        // falls back to CPU SkSurface — the peer code is the same. We
        // report OPENGL as the closest matching JFX AccelType value
        // (it's used by JFX only as a hint for which peer prefix to
        // use, which is irrelevant since we override createPeer).
        return AccelType.OPENGL;
    }

    @Override
    public synchronized RendererState getRendererState() {
        return state;
    }

    @Override
    public boolean isImageDataCompatible(ImageData id) {
        // All ImageData we produce wraps a SkiaPrDrawable (or a
        // pipeline-shared PrImage source). Any of those round-trip
        // through our drawables fine.
        return getRendererState() == RendererState.OK
            && (id.getUntransformedImage() instanceof SkiaPrDrawable
             || id.getUntransformedImage() instanceof PrImage);
    }

    @Override
    protected synchronized Renderer getBackupRenderer() {
        // No software fallback — Skia handles its own raster fallback
        // inside SkSurface. If we get here something else is broken.
        return this;
    }

    // ====================================================================
    // RESIZE INVARIANT #1/4 fix — size-bucketed intermediate drawables.
    // ====================================================================
    // Every peer call goes through getCompatibleImage(w, h) which keys
    // JFX's ImagePool by exact (w,h). During a drag-resize, sigma-
    // rounding produces slightly-different bounds each frame
    // (576→574→577…) and the pool misses on every request —
    // createCompatibleImage allocates a fresh GPU texture per frame
    // per shadowed node = visible stutter.
    //
    // Bucketing here makes the pool key by (ceil(w/32)·32,
    // ceil(h/32)·32). All sigma variations within a 32-px band hit
    // the same bin → first checkout allocates, subsequent checkouts
    // return the same drawable. ImagePool will call setContentWidth/
    // Height back to the logical (w,h) on checkout, so peers see the
    // real bounds; the unused padding within the bucket is never
    // read. See RESIZE_INVARIANTS.md.
    // 128-px buckets — wide enough that small drag deltas don't
    // cross a boundary, narrow enough that we don't over-allocate
    // dramatically for small effect targets (e.g. a 17×500 scrollbar
    // shadow buckets to 128×512 = ~7× overhead, acceptable).
    // Increasing further is safe (bigger pool hit rate) at the cost
    // of more wasted texture memory per cached entry.
    private static final int BUCKET = 128;
    private static int bucket(int v) {
        if (v <= 0) return BUCKET;
        return ((v + BUCKET - 1) / BUCKET) * BUCKET;
    }

    @Override
    public int getCompatibleWidth(int w) {
        return bucket(w);
    }

    @Override
    public int getCompatibleHeight(int h) {
        return bucket(h);
    }

    /** DIAG: count drawable checkouts (= effect filter passes). */
    public static final java.util.concurrent.atomic.AtomicLong CHECKOUT_COUNT =
        new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong ALLOC_COUNT =
        new java.util.concurrent.atomic.AtomicLong();

    @Override
    public SkiaPrDrawable getCompatibleImage(int w, int h) {
        CHECKOUT_COUNT.incrementAndGet();
        SkiaPrDrawable im = (SkiaPrDrawable) super.getCompatibleImage(w, h);
        // Reset to transparent on checkout. The ImagePool reuses SkiaRTTextures
        // without re-zeroing, so a checked-out drawable still holds the PREVIOUS
        // effect's pixels. Every effect drawable flows through here — the node
        // content snapshot (NodeEffectInput → Effect.getCompatibleImage), each
        // peer's output, and intermediate passes — and they all composite SrcOver,
        // so stale pixels bleed through wherever the result is transparent. For a
        // DropShadow that means the node snapshot's transparent margins (and the
        // shadow halo) carry garbage that GaussianShadow blurs and Merge paints
        // onto the scene — the grey ghost that flickers as the pool cycles.
        // Clearing here once fixes input, output, and intermediates uniformly.
        // Skip a pooled drawable whose native surface was disposed/lost: its
        // handle can still be non-zero, so surfaceClear would scribble on freed
        // GPU memory (UAF). isLost() checks rtt == null || surface lost.
        if (im != null && (im.isLost() || im.getSurfaceHandle() == 0L)) {
            // The pool handed back a drawable whose native SkSurface was
            // disposed/lost (e.g. a presentable rebuild on resize/maximize/
            // monitor-move tore down the GPU surface this entry was created
            // against). Drawing an effect into a dead surface yields BLANK
            // output — the "effects vanish on resize" symptom. Discard it and
            // get a fresh, valid drawable instead of returning the corpse.
            //
            // It MUST stay pool-tracked: `im` is already in ImagePool.locked
            // (super.getCompatibleImage == checkOut adds it there). Releasing a
            // raw createCompatibleImage() substitute instead would return a
            // drawable that is NOT in `locked`, so the matching checkIn silently
            // drops it and its native SkSurface/SkiaRTTexture leaks on every
            // resize/monitor-move that trips this guard. So: check the corpse
            // back in (removes it from `locked`; a lost entry parked in unlocked
            // is evicted on the next checkOut at ImagePool.java:174-177), then
            // check out again — the replacement is itself pool-tracked and will
            // be flushed correctly on checkIn. (bugs.md NEW-H2)
            releaseCompatibleImage(im);
            im = (SkiaPrDrawable) super.getCompatibleImage(w, h);
            // Defensive: if even the fresh checkout is lost (genuine device
            // loss), fall back to a raw drawable rather than returning a corpse.
            // This last-ditch path is not pool-tracked, but a lost device is
            // already a degraded state and the next pulse retries from scratch.
            if (im != null && (im.isLost() || im.getSurfaceHandle() == 0L)) {
                im = createCompatibleImage(w, h);
            }
        }
        if (im != null && !im.isLost()) {
            long handle = im.getSurfaceHandle();
            if (handle != 0L) {
                NativeBridge.surfaceClear(MemorySegment.ofAddress(handle), 0, 0, 0, 0);
            }
        }
        // NOTE: do NOT transition state to LOST on a single null result.
        // A transient ImagePool allocation failure (e.g. a pulse where
        // surface_draw_surface fails on a cached RTT path) would otherwise
        // lock the renderer LOST permanently — isImageDataCompatible would
        // always return false, every DropShadow / Bloom / Blend validate()
        // call would fail, and PrEffectHelper would print
        //   "[prism.effect] giving up on <Effect> after 3 failed
        //    validate() retries"
        // every pulse for the rest of the session. Letting the caller see
        // a one-pulse miss as just a missed effect (and the next pulse
        // tries fresh from RendererState.OK) matches the original Prism
        // PrRenderer's transient-failure recovery. State is only set to
        // LOST through the explicit dispose path (line ~373).
        return im;
    }

    @Override
    public SkiaPrDrawable createCompatibleImage(int w, int h) {
        // Caller is ImagePool.checkOut (cache miss). Counts as a real
        // GPU texture allocation. If this number stays high during a
        // drag, the pool's hit rate is poor and the bucket size needs
        // tuning.
        ALLOC_COUNT.incrementAndGet();
        ResourceFactory f = factory();
        return SkiaPrDrawable.create(f, w, h);
    }

    @Override
    public void clearImage(Filterable filterable) {
        PrDrawable drawable = (PrDrawable) filterable;
        drawable.clear();
    }

    @Override
    public ImageData createImageData(FilterContext fctx, Filterable src) {
        // Source is typically a PrImage wrapping a Prism Image; we
        // copy it onto a Skia drawable so subsequent filter passes
        // can treat it as a regular Skia surface.
        if (!(src instanceof PrImage prImage)) {
            throw new IllegalArgumentException(
                "SkiaEffectRenderer.createImageData: source must be a PrImage, got "
              + (src == null ? "null" : src.getClass().getName()));
        }
        Image img = prImage.getImage();
        int w = img.getWidth();
        int h = img.getHeight();
        // Route through the pool — bucketed so repeated identical-size
        // requests during a drag hit the cache instead of allocating.
        SkiaPrDrawable dst = (SkiaPrDrawable) getCompatibleImage(w, h);
        if (dst == null) {
            return new ImageData(fctx, null, new Rectangle(0, 0, w, h));
        }
        Graphics g = dst.createGraphics();
        if (g != null) {
            Texture tex = factory().createTexture(img,
                Texture.Usage.DEFAULT,
                WrapMode.CLAMP_TO_EDGE);
            g.drawTexture(tex, 0f, 0f, (float) w, (float) h);
            tex.dispose();
        }
        return new ImageData(fctx, dst, new Rectangle(0, 0, w, h));
    }

    @Override
    public Filterable transform(FilterContext fctx,
                                Filterable original,
                                BaseTransform transform,
                                Rectangle origBounds,
                                Rectangle xformBounds) {
        SkiaPrDrawable dst = (SkiaPrDrawable) getCompatibleImage(xformBounds.width, xformBounds.height);
        if (dst == null) return null;
        Graphics g = dst.createGraphics();
        if (g != null) {
            g.translate(-xformBounds.x, -xformBounds.y);
            g.transform(transform);
            PrEffectHelper.renderImageData(g,
                new ImageData(fctx, (SkiaPrDrawable) original, origBounds),
                origBounds);
        }
        return dst;
    }

    @Override
    public ImageData transform(FilterContext fctx, ImageData original,
                               BaseTransform transform,
                               Rectangle origBounds, Rectangle xformBounds) {
        SkiaPrDrawable dst = (SkiaPrDrawable) getCompatibleImage(xformBounds.width, xformBounds.height);
        if (dst == null) {
            return new ImageData(fctx, null, xformBounds);
        }
        Graphics g = dst.createGraphics();
        if (g != null) {
            g.translate(-xformBounds.x, -xformBounds.y);
            g.transform(transform);
            PrEffectHelper.renderImageData(g, original, origBounds);
        }
        return new ImageData(fctx, dst, xformBounds);
    }

    // No float-texture path; Lighting peer (when ported) will use the
    // Skia bridge's native lighting filters directly instead of float
    // textures.
    @Override
    public LockableResource createFloatTexture(int w, int h) {
        throw new UnsupportedOperationException(
            "SkiaEffectRenderer does not provide float-texture resources; "
          + "Lighting effects use SkImageFilters::*LitDiffuse/Specular.");
    }

    // ---- Peer dispatch -----------------------------------------------------

    /** DIAG: per-second dispatch counter for hot-path visibility. */
    public static final java.util.concurrent.atomic.AtomicLong PEER_DISPATCH_COUNT =
        new java.util.concurrent.atomic.AtomicLong();

    /** DIAG: count of bypassed (drag-resize) peer calls per second. */
    public static final java.util.concurrent.atomic.AtomicLong BYPASS_COUNT =
        new java.util.concurrent.atomic.AtomicLong();

    /**
     * Returns true if the current frame is inside a drag-resize tight
     * loop (a {@code liveRepaintRenderJob} fired within the last
     * 100 ms). Used by each Skia effect peer to short-circuit to
     * passthrough during a drag — the per-frame GPU cost of
     * {@code saveLayer + filter + restore} is what causes drag
     * stutter even with allocation churn eliminated. Outside of drag,
     * peers run the full filter so the visual result is correct.
     * Once SkPicture caching lands (see SKPICTURE_CACHING_DESIGN.md)
     * this short-circuit can be removed.
     */
    // Mirrors NGNode's opt-in resize gate (default OFF). When the NGNode-level
    // effect dispatch is NOT gated (the default), this peer-level passthrough
    // must also stay off, otherwise the effect would still flatten to its input
    // during/after a resize and get stuck plain. Enable both together with
    // -Dskia.effects.gateOnResize=true for the drag-smoothness optimization.
    private static final boolean GATE_EFFECTS_ON_RESIZE =
        Boolean.getBoolean("skia.effects.gateOnResize");

    public static boolean shouldBypassForDrag() {
        return GATE_EFFECTS_ON_RESIZE && QuantumToolkit.isInLiveRepaint();
    }

    @Override
    protected EffectPeer createPeer(FilterContext fctx, String name, int unrollCount) {
        if (PrRenderer.isIntrinsicPeer(name)) {
            // Crop / Flood / Merge / Reflection — share the existing
            // pipeline-agnostic intrinsic peers. They paint via PrDrawable.
            return createIntrinsicPeer(fctx, name);
        }
        return createSkiaPeer(fctx, name);
    }

    private EffectPeer createIntrinsicPeer(FilterContext fctx, String name) {
        String klassName = rootPkg + ".impl.prism.Pr" + name + "Peer";
        try {
            Class<?> klass = Class.forName(klassName);
            Constructor<?> ctor = klass.getConstructor(
                FilterContext.class, Renderer.class, String.class);
            return (EffectPeer) ctor.newInstance(fctx, this, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private EffectPeer createSkiaPeer(FilterContext fctx, String name) {
        // Try the per-effect Skia peer by name.
        String klassName = rootPkg + ".impl.skia.Skia" + name + "Peer";
        try {
            Class<?> klass = Class.forName(klassName);
            Constructor<?> ctor = klass.getConstructor(
                FilterContext.class, Renderer.class, String.class);
            return (EffectPeer) ctor.newInstance(fctx, this, name);
        } catch (ClassNotFoundException missing) {
            // Blend modes encode the mode in the suffix
            // ("Blend_ADD", "Blend_MULTIPLY", ...) — dispatch all to
            // the single SkiaBlendPeer which parses the suffix.
            if (name.startsWith("Blend_")) {
                return instantiate(fctx, "SkiaBlendPeer", name);
            }
            // Phong lighting dispatches the light type in the suffix
            // ("PhongLighting_DISTANT", ...) — single peer handles all.
            if (name.startsWith("PhongLighting_")) {
                return instantiate(fctx, "SkiaPhongLightingPeer", name);
            }
            // No real peer for this effect yet — fall back to a
            // passthrough peer so the scene paint doesn't abort.
            // (The alternative — return null — causes Renderer
            // .getPeerInstance to throw "Could not create peer",
            // which kills the frame.) Visual cost: the unimplemented
            // effect becomes a no-op until its real peer lands.
            logMissing(name);
            return new SkiaPassthroughPeer(fctx, this, name);
        } catch (Throwable t) {
            if (VERBOSE) {
                System.err.println("[skia.effects] failed to instantiate Skia"
                    + name + "Peer: " + t);
            }
            return new SkiaPassthroughPeer(fctx, this, name);
        }
    }

    private EffectPeer instantiate(FilterContext fctx, String simpleName,
                                   String dispatchName) {
        String klassName = rootPkg + ".impl.skia." + simpleName;
        try {
            Class<?> klass = Class.forName(klassName);
            Constructor<?> ctor = klass.getConstructor(
                FilterContext.class, Renderer.class, String.class);
            return (EffectPeer) ctor.newInstance(fctx, this, dispatchName);
        } catch (Throwable t) {
            logMissing(dispatchName);
            return new SkiaPassthroughPeer(fctx, this, dispatchName);
        }
    }

    // One-shot per name so a recurring effect doesn't spam the log.
    private static final java.util.Set<String> LOGGED_MISSING =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static void logMissing(String name) {
        if (VERBOSE && LOGGED_MISSING.add(name)) {
            System.err.println("[skia.effects] passthrough for '" + name
                + "' (Skia peer not yet ported — effect is a no-op).");
        }
    }

    private static final boolean VERBOSE = Boolean.getBoolean("skia.verbose");

    // ---- Helpers -----------------------------------------------------------

    private ResourceFactory factory() {
        if (factory != null) return factory;
        return GraphicsPipeline.getPipeline().getResourceFactory(screen);
    }

    /** No-op dispose; the singleton {@code SKIAPipeline} owns lifetime. */
    protected void dispose() {
        synchronized (this) { state = RendererState.DISPOSED; }
    }
}
