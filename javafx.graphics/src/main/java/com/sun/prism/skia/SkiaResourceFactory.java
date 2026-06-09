package com.sun.prism.skia;

import com.sun.glass.ui.Screen;
import com.sun.prism.Image;
import com.sun.prism.MediaFrame;
import com.sun.prism.skia.impl.NativeBridge;
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
        recoveryAttempted = false; // device healthy — re-arm for a future loss
        return true;
    }

    private boolean recoveryInProgress;
    private boolean recoveryAttempted;

    /**
     * Recover from a lost shared D3D12 device (cross-DPI monitor-move TDR / adapter
     * change). The painter has already stopped all rendering via {@link #isDeviceReady}.
     * Order matters: drop every GPU resource tied to the OLD context FIRST (while it is
     * still alive — releasing refs is safe even on a removed device), THEN recreate the
     * device + GrDirectContext + bgfx. Resources rebuild lazily against the new context
     * on the next pulse. Best-effort: if the native recover is unavailable the guard
     * still prevents the crash (the window holds its last frame). Does NOT touch the
     * normal 2D path — only runs while the device is genuinely lost.
     */
    private void attemptDeviceRecovery() {
        if (recoveryInProgress || recoveryAttempted) return;
        recoveryAttempted = true; // one-shot per loss; re-armed in isDeviceReady
        recoveryInProgress = true;
        try {
            // 1. Notify every resource (textures, cached surfaces, 3D meshes/materials)
            //    to drop its GPU handle before the old context is destroyed.
            for (ResourceFactoryListener l :
                    listeners.toArray(new ResourceFactoryListener[0])) {
                try { l.factoryReset(); } catch (Throwable ignore) { }
            }
            regionTexture = null;
            glyphTexture = null;
            disposeCache(clampTexCache);
            disposeCache(repeatTexCache);
            // 2. Recreate device + GrDirectContext + bgfx and clear the lost flag.
            NativeBridge.recoverDevice();
        } catch (Throwable t) {
            // Recovery itself must never crash — leave the device lost; the guard
            // keeps the pipeline from touching it.
        } finally {
            recoveryInProgress = false;
        }
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        for (ResourceFactoryListener l : listeners) {
            l.factoryReleased();
        }
        listeners.clear();
        regionTexture = null;
        glyphTexture = null;
        disposeCache(clampTexCache);
        disposeCache(repeatTexCache);
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
        // For the "blank texture, fill in via update" path we'd need a
        // mutable bitmap-backed image. Phase-1 callers all go through
        // createTexture(Image, ...).
        throw todo("createTexture(PixelFormat, Usage, WrapMode, w, h)");
    }
    @Override public Texture createTexture(PixelFormat formatHint, Texture.Usage usageHint, WrapMode wrapMode, int w, int h, boolean useMipmap) {
        throw todo("createTexture(PixelFormat, Usage, WrapMode, w, h, mipmap)");
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

        Map<Image, Texture> cache = mip ? null : cacheFor(wrapMode);
        if (disposed || cache == null) {
            // Factory gone, or a wrap mode / mipmap mode we don't cache
            // (CLAMP_NOT_NEEDED, mipmapped 3D maps): build fresh, fail-soft.
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
            tex = createImageTextureSafe(image, wrapMode, false);
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

    /** Cache bucket for a wrap mode, or {@code null} if that mode isn't cached. */
    private Map<Image, Texture> cacheFor(WrapMode wrapMode) {
        if (wrapMode == WrapMode.CLAMP_TO_EDGE) return clampTexCache;
        if (wrapMode == WrapMode.REPEAT)        return repeatTexCache;
        return null; // CLAMP_NOT_NEEDED etc. — created fresh, not cached
    }

    /** Build an image texture, converting any upload failure into {@code null}. */
    private Texture createImageTextureSafe(Image image, WrapMode wrapMode, boolean mip) {
        try {
            return new SkiaImageTexture(image, wrapMode, mip);
        } catch (Throwable t) {
            System.getLogger(SkiaResourceFactory.class.getName()).log(
                System.Logger.Level.WARNING,
                "Skia image upload failed (" + image.getWidth() + "x" + image.getHeight()
                + ", " + image.getPixelFormat() + "); drawing a transparent placeholder", t);
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
        return new SkiaRTTexture(this, screen, width, height, wrapMode, msaa);
    }

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
