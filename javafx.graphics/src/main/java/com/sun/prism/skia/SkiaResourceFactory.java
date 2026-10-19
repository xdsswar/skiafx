package com.sun.prism.skia;

import com.sun.glass.ui.Screen;
import com.sun.prism.Image;
import com.sun.prism.MediaFrame;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.prism.skia.impl.NativeBridge3D;
import com.sun.prism.skia.impl.NativeHandles;
import com.sun.prism.skia.impl.SkiaGpu;
import com.sun.prism.Mesh;
import com.sun.prism.MeshView;
import com.sun.prism.PhongMaterial;
import com.sun.prism.PixelFormat;
import com.sun.prism.Presentable;
import com.sun.prism.PresentableState;
import com.sun.prism.RTTexture;
import com.sun.prism.ResourceFactory;
import com.sun.prism.ResourceFactoryListener;
import com.sun.prism.Texture;
import com.sun.prism.Texture.WrapMode;
import com.sun.prism.impl.TextureResourcePool;
import com.sun.prism.shape.ShapeRep;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Skia-backed {@link ResourceFactory}.
 *
 * <p><b>Phase 1 status:</b> the methods that Prism touches during
 * pipeline initialization (lifecycle, capability queries, listener
 * registration) work; resource-creation methods throw
 * {@code UnsupportedOperationException} until their Skia-backed
 * implementations land. Each unsupported call points at the next
 * concrete piece of work.</p>
 *
 * <p><b>Allocation discipline:</b> resource-creation methods will be
 * pooled (CLAUDE.md "Heap allocations on the render thread"). The
 * stub implementations don't allocate; their replacements must keep
 * the discipline.</p>
 */
public final class SkiaResourceFactory implements ResourceFactory {

    private final Screen screen;
    private final CopyOnWriteArrayList<ResourceFactoryListener> listeners =
        new CopyOnWriteArrayList<>();
    private boolean disposed;

    private Texture regionTexture;
    private Texture glyphTexture;

    // ---- Image texture cache ----------------------------------------------
    //
    // One SkImage per Prism Image, re-uploaded only when the image's content
    // serial changes. Without this, getCachedTexture() rebuilt a fresh SkImage
    // every frame: heavy CPU→native copy churn for tile-dense scenes (maps),
    // and a per-frame, in-paint upload-failure window that showed up as blank
    // tiles. Keyed weakly so an entry disappears once the scene graph drops the
    // Image; SkiaImageTexture holds no Image reference, so there is no leak.
    //
    // Accessed only on the render thread (matching BaseResourceFactory, which
    // also uses an unsynchronized WeakHashMap) — snapshot/pulse rendering is
    // serialized onto the render thread by the toolkit.
    private final Map<Image, Texture> clampTexCache  = new WeakHashMap<>();
    private final Map<Image, Texture> repeatTexCache = new WeakHashMap<>();
    // REPEAT + mipmap (3D material maps), as upstream BaseResourceFactory.
    private final Map<Image, Texture> mipmapTexCache = new WeakHashMap<>();

    // Fail-soft placeholders (1×1 transparent), one per cacheable wrap mode.
    // Lazily created, permanent, shared. Returned (locked) when a real upload
    // fails, so a node degrades to a blank frame + retry rather than crashing.
    private Texture placeholderClamp;
    private Texture placeholderRepeat;

    SkiaResourceFactory(Screen screen) {
        this.screen = screen;
    }

    Screen getScreen() {
        return screen;
    }

    // ---- Lifecycle ---------------------------------------------------------

    @Override public boolean isDisposed()    { return disposed; }
    // Report not-ready while the shared D3D12 device is lost (cross-DPI monitor-move
    // TDR / adapter change). PresentingPainter's existing device-not-ready path then
    // skips ALL render + present and disposes the presentable, so neither Skia 2D nor
    // bgfx 3D hammers the dead device (which spammed "Failed Direct3D call" and hung
    // the driver). Normal path is unchanged — isDeviceLost() is false unless the
    // device is genuinely gone. Recovery is attempted in attemptDeviceRecovery().
    @Override public boolean isDeviceReady() {
        if (disposed) return false;
        if (NativeBridge.isDeviceLost()) {
            attemptDeviceRecovery();
            return false; // skip this pulse; ready again once the device is rebuilt
        }
        return true;
    }

    /** Earliest time (System.nanoTime) of the next recovery attempt. */
    private long nextRecoveryAttemptNs;
    /** Consecutive failed attempts for the current loss (drives the back-off). */
    private int recoveryFailures;
    /** Resources already dropped for the current loss. */
    private boolean resourcesDroppedForLoss;
    private static volatile boolean recoveryFailureLogged;

    /**
     * Recover from a lost shared D3D12 device (TDR, driver update/reset, adapter
     * change on a cross-DPI monitor move). Render thread; called from
     * {@link #isDeviceReady} while the painter has stopped all rendering.
     *
     * <ol>
     * <li>Drop every GPU resource the factory caches and notify listeners, once
     *     per loss, while the old context still exists (releasing refs is safe on
     *     a removed device).</li>
     * <li>Native recovery: the old context is abandoned, a new device/queue +
     *     GrDirectContext is built, the device generation is bumped.</li>
     * <li>On success, publish the new generation: render targets, presentables,
     *     media and 3D objects from the old device then report lost/invalid and
     *     the scene graph rebuilds them on the next pulse (Prism's own
     *     device-reset contract). Image textures hold raster pixels and survive.</li>
     * </ol>
     *
     * A failed attempt (the driver is often still resetting for a few hundred
     * ms) is retried with back-off — 250 ms doubling to 4 s — for as long as the
     * device stays lost, so a window never stays frozen after a transient reset.
     */
    private void attemptDeviceRecovery() {
        long now = System.nanoTime();
        if (now - nextRecoveryAttemptNs < 0) return;
        try {
            if (!resourcesDroppedForLoss) {
                resourcesDroppedForLoss = true;
                for (ResourceFactoryListener l :
                        listeners.toArray(new ResourceFactoryListener[0])) {
                    try { l.factoryReset(); } catch (Throwable ignore) { }
                }
                regionTexture = null;
                glyphTexture = null;
                disposeCache(clampTexCache);
                disposeCache(repeatTexCache);
                disposeCache(mipmapTexCache);
                // Run the frees deferred by Cleaners against the OLD context
                // before it is abandoned.
                NativeHandles.drainDeferred();
                // The 3D renderer shares the device; it must let go of it too
                // or no new device can be created on the same GPU.
                NativeBridge3D.releaseDevice();
            }
            if (NativeBridge.recoverDevice()) {
                SkiaGpu.refreshDeviceGeneration();
                recoveryFailures = 0;
                resourcesDroppedForLoss = false;
                nextRecoveryAttemptNs = now;
                return;
            }
        } catch (Throwable t) {
            // Recovery itself must never crash — stay latched and retry later.
        }
        recoveryFailures++;
        long delayMs = Math.min(4000L, 250L << Math.min(4, recoveryFailures - 1));
        nextRecoveryAttemptNs = now + delayMs * 1_000_000L;
        if (recoveryFailures == 8 && !recoveryFailureLogged) {
            recoveryFailureLogged = true;
            System.getLogger(SkiaResourceFactory.class.getName()).log(
                System.Logger.Level.WARNING,
                "GPU device lost and not recovered yet; retrying every 4 s");
        }
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        for (ResourceFactoryListener l : listeners) {
            // Same isolation as attemptDeviceRecovery: one throwing listener
            // must not skip the rest or abort the cache/placeholder release.
            try { l.factoryReleased(); } catch (Throwable ignore) { }
        }
        listeners.clear();
        regionTexture = null;
        glyphTexture = null;
        disposeCache(clampTexCache);
        disposeCache(repeatTexCache);
        disposeCache(mipmapTexCache);
        if (placeholderClamp != null)  { placeholderClamp.dispose();  placeholderClamp = null; }
        if (placeholderRepeat != null) { placeholderRepeat.dispose(); placeholderRepeat = null; }
    }

    private static void disposeCache(Map<Image, Texture> cache) {
        for (Texture t : cache.values()) {
            if (t != null) t.dispose();
        }
        cache.clear();
    }

    @Override public void addFactoryListener(ResourceFactoryListener l)    { listeners.addIfAbsent(l); }
    @Override public void removeFactoryListener(ResourceFactoryListener l) { listeners.remove(l); }

    // ---- Capability queries ------------------------------------------------

    @Override public boolean isFormatSupported(PixelFormat format) {
        // Skia's raster surfaces handle the RGBA8888-equivalent formats
        // and grayscale; tighten this once the texture path is implemented.
        return switch (format) {
            case INT_ARGB_PRE, BYTE_BGRA_PRE, BYTE_RGB, BYTE_GRAY, BYTE_ALPHA -> true;
            default -> false;
        };
    }

    @Override public boolean isWrapModeSupported(WrapMode mode) {
        return mode == WrapMode.CLAMP_TO_EDGE
            || mode == WrapMode.REPEAT
            || mode == WrapMode.CLAMP_NOT_NEEDED;
    }

    @Override public int getMaximumTextureSize() { return 16_384; }
    @Override public int getRTTWidth(int w, WrapMode wrapMode)  { return w; }
    @Override public int getRTTHeight(int h, WrapMode wrapMode) { return h; }
    @Override public boolean isCompatibleTexture(Texture tex)   {
        return tex instanceof SkiaTextureBase
            || tex instanceof SkiaMediaTexture;
    }
    @Override public boolean isSuperShaderAllowed()             { return false; }

    // ---- Region / glyph atlas slots ---------------------------------------

    @Override public void setRegionTexture(Texture texture) { this.regionTexture = texture; }
    @Override public Texture getRegionTexture()              { return regionTexture; }
    @Override public void setGlyphTexture(Texture texture)   { this.glyphTexture = texture; }
    @Override public Texture getGlyphTexture()               { return glyphTexture; }

    // ---- Texture & RT creation (TODO: Skia-backed) ------------------------

    @Override public TextureResourcePool getTextureResourcePool() {
        return SkiaTextureResourcePool.INSTANCE;
    }

    @Override public Texture createTexture(Image image, Texture.Usage usageHint, WrapMode wrapMode) {
        return createTexture(image, usageHint, wrapMode, false);
    }
    @Override public Texture createTexture(Image image, Texture.Usage usageHint, WrapMode wrapMode, boolean useMipmap) {
        // M18: budget pre-check (reclaim-on-pressure; degrades, never fails).
        SkiaTextureResourcePool.INSTANCE.prepareForAllocation(
            SkiaTextureResourcePool.INSTANCE.estimateTextureSize(
                image.getWidth(), image.getHeight(), image.getPixelFormat()));
        return new SkiaImageTexture(image, wrapMode, useMipmap);
    }
    @Override public Texture createTexture(PixelFormat formatHint, Texture.Usage usageHint, WrapMode wrapMode, int w, int h) {
        return createTexture(formatHint, usageHint, wrapMode, w, h, false);
    }
    @Override public Texture createTexture(PixelFormat formatHint, Texture.Usage usageHint, WrapMode wrapMode, int w, int h, boolean useMipmap) {
        // No mutable "blank texture, fill in via update(Buffer)" texture yet
        // (needed by SwingNode / NGExternalNode). Prism's contract for an
        // allocation the factory can't satisfy is null, which every caller
        // handles; throwing here failed the whole paint pass every pulse.
        return null;
    }
    @Override public Texture createTexture(MediaFrame frame) {
        // Phase-2 (BGRA scaffolding). Validates that MediaFrames flow
        // through Skia end-to-end. SkiaMediaTexture re-uploads every
        // frame as a fresh SkImage; Phase-3 will swap this for the
        // zero-copy hardware-shared-texture path.
        return new SkiaMediaTexture(frame, WrapMode.CLAMP_TO_EDGE);
    }
    @Override public Texture getCachedTexture(Image image, WrapMode wrapMode) {
        return getCachedTexture(image, wrapMode, false);
    }

    @Override public Texture getCachedTexture(Image image, WrapMode wrapMode, boolean mip) {
        Objects.requireNonNull(image, "image");

        // Contract: this returns a LOCKED texture; the caller unlocks once.
        // A new texture is created unlocked, then locked here; a cache hit is
        // re-locked here. Either way the resting (cached) lock count is 0 and
        // the WeakHashMap keeps the entry alive. Callers (NGImageView,
        // NGCanvas) never null-check, so we must always return a drawable
        // texture — never null, never an escaping exception.

        // CLAMP_NOT_NEEDED is satisfied by a CLAMP_TO_EDGE texture (as
        // getSharedTexture also treats it), so share the clamp cache instead of
        // uploading a fresh uncached texture on every call. Created textures in
        // that bucket are always CLAMP_TO_EDGE, so CLAMP_TO_EDGE callers never
        // get a texture with a different wrap mode.
        if (wrapMode == WrapMode.CLAMP_NOT_NEEDED && !mip) {
            wrapMode = WrapMode.CLAMP_TO_EDGE;
        }
        Map<Image, Texture> cache = cacheFor(wrapMode, mip);
        if (disposed || cache == null) {
            // Factory gone, or a combination upstream doesn't cache either
            // (it throws for CLAMP+mipmap / CLAMP_TO_ZERO): build fresh, fail-soft.
            Texture fresh = createImageTextureSafe(image, wrapMode, mip);
            return lockOrPlaceholder(fresh, wrapMode);
        }

        int serial = image.getSerial().getIdRect().getKey();
        Texture tex = cache.get(image);
        if (tex != null) {
            if (tex.isSurfaceLost()) {
                cache.remove(image);
                tex = null;
            } else if (tex.getLastImageSerial() != serial) {
                // Content changed (e.g. a WritableImage tile was repainted).
                // SkiaImageTexture is immutable, so rebuild rather than update.
                cache.remove(image);
                tex.dispose();
                tex = null;
            }
        }
        if (tex == null) {
            tex = createImageTextureSafe(image, wrapMode, mip);
            if (tex == null) {
                // Upload failed (unsupported format / transient native OOM).
                // Degrade to a transparent placeholder and DON'T cache it, so
                // the next frame retries the real upload: recovers from a
                // transient failure; an unsupported format merely stays
                // transparent — never blank garbage, never a crash.
                return lockPlaceholder(wrapMode);
            }
            tex.setLastImageSerial(serial);
            cache.put(image, tex);
        }
        tex.lock();
        return tex;
    }

    /** Cache bucket for a wrap/mipmap mode (upstream BaseResourceFactory
     *  layout), or {@code null} if that combination isn't cached. */
    private Map<Image, Texture> cacheFor(WrapMode wrapMode, boolean mip) {
        if (wrapMode == WrapMode.REPEAT)        return mip ? mipmapTexCache : repeatTexCache;
        if (wrapMode == WrapMode.CLAMP_TO_EDGE) return mip ? null : clampTexCache;
        return null; // CLAMP_TO_ZERO etc. — created fresh, not cached
    }

    private static volatile boolean imageUploadFailureLogged;

    /** Build an image texture, converting any upload failure into {@code null}. */
    private Texture createImageTextureSafe(Image image, WrapMode wrapMode, boolean mip) {
        try {
            // M18: same budget pre-check as createTexture / upstream
            // BaseResourceFactory.getCachedTexture (reclaim-on-pressure;
            // degrades, never fails).
            SkiaTextureResourcePool.INSTANCE.prepareForAllocation(
                SkiaTextureResourcePool.INSTANCE.estimateTextureSize(
                    image.getWidth(), image.getHeight(), image.getPixelFormat()));
            return new SkiaImageTexture(image, wrapMode, mip);
        } catch (Throwable t) {
            // Failures are not cached (the next frame retries), so a
            // persistent one would log every frame: report the first only.
            if (!imageUploadFailureLogged) {
                imageUploadFailureLogged = true;
                System.getLogger(SkiaResourceFactory.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "Skia image upload failed (" + image.getWidth() + "x" + image.getHeight()
                    + ", " + image.getPixelFormat() + "); drawing a transparent placeholder"
                    + " (further failures are not logged)", t);
            }
            return null;
        }
    }

    /** Lock {@code tex} if non-null, otherwise hand back a locked placeholder. */
    private Texture lockOrPlaceholder(Texture tex, WrapMode wrapMode) {
        if (tex != null) {
            tex.lock();
            return tex;
        }
        return lockPlaceholder(wrapMode);
    }

    private Texture lockPlaceholder(WrapMode wrapMode) {
        Texture p = placeholderFor(wrapMode);
        if (p != null) p.lock();
        return p;
    }

    private Texture placeholderFor(WrapMode wrapMode) {
        boolean repeat = (wrapMode == WrapMode.REPEAT);
        Texture existing = repeat ? placeholderRepeat : placeholderClamp;
        if (existing != null && !existing.isSurfaceLost()) {
            return existing;
        }
        WrapMode pm = repeat ? WrapMode.REPEAT : WrapMode.CLAMP_TO_EDGE;
        SkiaImageTexture created = SkiaImageTexture.newTransparentPlaceholder(pm);
        if (created != null) {
            created.makePermanent();
            if (repeat) placeholderRepeat = created; else placeholderClamp = created;
        }
        return created;
    }
    @Override public Texture createMaskTexture(int width, int height, WrapMode wrapMode)    { throw todo("createMaskTexture"); }
    @Override public Texture createFloatTexture(int width, int height)                      { throw todo("createFloatTexture"); }

    @Override public RTTexture createRTTexture(int width, int height, WrapMode wrapMode) {
        return createRTTexture(width, height, wrapMode, false);
    }
    @Override public RTTexture createRTTexture(int width, int height, WrapMode wrapMode, boolean msaa) {
        // Phase 1: raster only. msaa is recorded but ignored; CPU-side
        // SkSurface always anti-aliases via SkPaint.setAntiAlias(true)
        // in the draw calls.
        // M18: budget pre-check (reclaims dead resources under pressure; degrades,
        // never fails). The authoritative accounting is in SkiaTextureBase's ctor.
        SkiaTextureResourcePool.INSTANCE.prepareForAllocation(
            SkiaTextureResourcePool.INSTANCE.estimateRTTextureSize(width, height, false));
        try {
            return new SkiaRTTexture(this, screen, width, height, wrapMode, msaa);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // Native surface allocation failed (GPU and raster both refused, e.g.
            // out of memory / oversized). Match upstream D3D/ES2ResourceFactory:
            // return null — Prism callers (UploadingPainter, NGCanvas, CacheFilter,
            // snapshot) already treat null as "no RT this frame" and degrade.
            if (!rtAllocFailureLogged) {
                rtAllocFailureLogged = true;
                System.getLogger(SkiaResourceFactory.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "Skia RT allocation failed (" + width + "x" + height
                    + "); returning null (further failures not logged)", e);
            }
            return null;
        }
    }

    // One-shot: an RT allocation failure under memory pressure can repeat every
    // pulse; log the first only (no console spam).
    private static volatile boolean rtAllocFailureLogged;

    @Override public Presentable createPresentable(PresentableState pState) {
        return new SkiaPresentable(this, pState);
    }

    // ---- Shape representations (TODO: SkPath wrappers) --------------------

    @Override public ShapeRep createPathRep()      { return SkiaShapeRep.INSTANCE; }
    @Override public ShapeRep createRoundRectRep() { return SkiaShapeRep.INSTANCE; }
    @Override public ShapeRep createEllipseRep()   { return SkiaShapeRep.INSTANCE; }
    @Override public ShapeRep createArcRep()       { return SkiaShapeRep.INSTANCE; }

    // ---- 3D (Door 1: bgfx-backed) -----------------------------------------

    @Override public PhongMaterial createPhongMaterial() { return SkiaPhongMaterial.create(); }
    @Override public MeshView createMeshView(Mesh mesh)  { return SkiaMeshView.create((SkiaMesh) mesh); }
    @Override public Mesh createMesh()                   { return SkiaMesh.create(); }

    // ---- helpers ----------------------------------------------------------

    private static UnsupportedOperationException todo(String method) {
        return new UnsupportedOperationException(
            "SkiaResourceFactory." + method + " is not yet implemented. "
            + "See CLAUDE.md phase-1 plan.");
    }
}
