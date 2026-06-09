package com.sun.prism.skia;

import com.sun.glass.ui.Screen;
import com.sun.prism.GraphicsPipeline;
import com.sun.prism.ResourceFactory;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.prism.skia.impl.NativeBridge3D;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skia-backed {@link GraphicsPipeline}.
 *
 * <p>Discovered by {@link GraphicsPipeline#createPipeline()} when
 * {@code prism.order} contains {@code "skia"}. Prism's reflection
 * mechanism (which we honor without modification) requires the
 * fully-qualified name {@code com.sun.prism.skia.SKIAPipeline}; the
 * all-caps {@code SKIA} prefix in the class name is the price of that
 * convention. Internally we treat this as the "Skia pipeline" — you
 * will never have to type {@code SKIA} elsewhere in our code.</p>
 *
 * <p><b>Phase 1 status:</b> the pipeline reports as initialized when
 * the native Skia bridge loads, but downstream resource factories and
 * graphics surfaces are stubs that throw on every operation. The
 * surface is filled in incrementally as Skia-backed implementations
 * land.</p>
 */
public final class SKIAPipeline extends GraphicsPipeline {

    private static volatile SKIAPipeline INSTANCE;

    // ConcurrentHashMap: getResourceFactory() runs computeIfAbsent without the
    // class lock, while dispose() clears the map under it. A plain HashMap let
    // those race (lost-update / resize corruption). computeIfAbsent on CHM is
    // atomic per key, so a screen's factory is created exactly once.
    private final Map<Integer, SkiaResourceFactory> factories = new ConcurrentHashMap<>(1);
    private boolean nativeSkiaEnabled;

    private SKIAPipeline() {
        // Constructed once via reflection from Prism.
    }

    /** Reflection entry point invoked by {@link GraphicsPipeline#createPipeline()}. */
    public static SKIAPipeline getInstance() {
        SKIAPipeline local = INSTANCE;
        if (local == null) {
            synchronized (SKIAPipeline.class) {
                local = INSTANCE;
                if (local == null) {
                    INSTANCE = local = new SKIAPipeline();
                }
            }
        }
        return local;
    }

    @Override
    public boolean init() {
        try {
            String version = NativeBridge.version();
            nativeSkiaEnabled = NativeBridge.hasSkia();
            System.getLogger(SKIAPipeline.class.getName()).log(
                System.Logger.Level.INFO,
                "Skia pipeline up: " + version
                    + " (skia=" + nativeSkiaEnabled + ")");
        } catch (Throwable t) {
            System.getLogger(SKIAPipeline.class.getName()).log(
                System.Logger.Level.WARNING,
                "Skia native bridge failed to load; pipeline declines.", t);
            return false;
        }
        // Refuse the pipeline if the bridge is in stub mode unless the
        // caller explicitly opted in (phase-0 plumbing tests).
        if (!nativeSkiaEnabled
                && !Boolean.getBoolean("openjfx.skia.allowStubBridge")) {
            return false;
        }

        Map<String, Boolean> details = new HashMap<>();
        details.put("nativeSkia", nativeSkiaEnabled);
        setDeviceDetails(details);

        // Eagerly initialize the effect-rendering classes on the render thread
        // now, while the toolkit is quiescent. RenderState's static initializer
        // (RenderState.java:87) constructs anonymous inner classes; if its very
        // first <clinit> ever lands inside a resource-pressure storm (e.g. the
        // ~300 presentable rebuilds Windows triggers when a window is dragged
        // across a 150%/175% DPI boundary) a transient classload failure throws
        // NoClassDefFoundError: RenderState$1. The JVM then records that
        // initialization as permanently failed, so EVERY later effect render
        // throws "Could not initialize class RenderState" for the rest of the
        // session — effects die toolkit-wide. A class that has already
        // initialized successfully can never be re-poisoned, so forcing it here
        // (off the hot path, before any monitor-move can happen) makes the
        // failure mode impossible. See docs/DPI_MONITOR_MOVE_AND_BUG_FIX_PLAN.md.
        warmUpEffectClasses();
        return true;
    }

    /**
     * Force {@code <clinit>} of the effect classes the Skia pipeline drives, so a
     * transient classload failure during a later high-pressure repaint storm
     * cannot permanently poison the effect system. Each class is initialized in
     * its own try/catch: a warm-up miss is logged and ignored — it must never
     * cause {@link #init()} to decline the pipeline.
     */
    private static void warmUpEffectClasses() {
        // Only the RenderState family is genuinely fragile: its <clinit>
        // constructs the synthetic inner classes RenderState$1/$2/$3, and that
        // multi-class init is what a transient classload failure can poison. The
        // effect classes themselves (Merge/Blend/DropShadow/...) have trivial
        // static initializers and reach RenderState only at *runtime*, so they
        // don't need warming. Keep the list minimal.
        final String[] classes = {
            // The critical one — pulls in RenderState$1/$2/$3.
            "com.sun.scenario.effect.impl.state.RenderState",
            "com.sun.scenario.effect.impl.state.LinearConvolveRenderState",
            "com.sun.scenario.effect.impl.state.GaussianRenderState",
            "com.sun.scenario.effect.impl.state.BoxRenderState",
        };
        ClassLoader loader = SKIAPipeline.class.getClassLoader();
        int loaded = 0;
        for (String name : classes) {
            try {
                Class.forName(name, /*initialize*/ true, loader);
                loaded++;
            } catch (Throwable t) {
                // Class absent in this configuration, or a genuine init failure:
                // log and continue. The lazy path will retry on first render.
                System.getLogger(SKIAPipeline.class.getName()).log(
                    System.Logger.Level.DEBUG,
                    "Effect warm-up skipped " + name + ": " + t);
            }
        }
        System.getLogger(SKIAPipeline.class.getName()).log(
            System.Logger.Level.DEBUG,
            "Effect classes warmed up: " + loaded + "/" + classes.length);
    }

    @Override
    public void dispose() {
        // M18 observability: dump the GPU texture-budget state on shutdown when
        // -Djavafx.gpu.memDump=true, so VRAM usage/peak is never a mystery.
        if (SkiaTextureResourcePool.INSTANCE.isMemDumpEnabled()) {
            SkiaTextureResourcePool.INSTANCE.dumpMemory();
        }
        synchronized (SKIAPipeline.class) {
            // Deterministically tear down each factory (frees its cached textures +
            // placeholder drawables) instead of leaving them to Cleaner/GC at
            // shutdown (L2). dispose() is best-effort; one failure must not skip
            // the others or leak the map.
            for (SkiaResourceFactory f : factories.values()) {
                try {
                    f.dispose();
                } catch (Throwable t) {
                    System.getLogger(SKIAPipeline.class.getName()).log(
                        System.Logger.Level.DEBUG, "factory dispose failed", t);
                }
            }
            factories.clear();
            INSTANCE = null;
        }
        super.dispose();
    }

    @Override
    public int getAdapterOrdinal(Screen screen) {
        return Screen.getScreens().indexOf(screen);
    }

    @Override
    public ResourceFactory getResourceFactory(Screen screen) {
        Integer index = screen.getAdapterOrdinal();
        return factories.computeIfAbsent(index, i -> new SkiaResourceFactory(screen));
    }

    @Override
    public ResourceFactory getDefaultResourceFactory(List<Screen> screens) {
        return getResourceFactory(Screen.getMainScreen());
    }

    // Door 1: 3D is supported when the bgfx-backed native renderer is
    // present + usable on the active backend (Windows + D3D12). Cached;
    // the native check is a pure env-var probe (no GPU context init), so
    // it's safe to call from any thread. If bgfx init later fails on the
    // render thread, 3D draws degrade to no-ops rather than crashing.
    private static final boolean THREE_D_SUPPORTED =
        com.sun.prism.skia.impl.NativeBridge3D.available();

    @Override
    public boolean is3DSupported() {
        return THREE_D_SUPPORTED;
    }

    @Override
    public boolean isMSAASupported() {
        // 3D SubScenes are anti-aliased by rendering the bgfx target multisampled
        // and resolving into the Skia-wrapped single-sample color (see Target3D in
        // openjfx_skia3d_bridge.cpp). Reportable wherever 3D is available; the
        // native side degrades to single-sample if the device/format lacks MSAA.
        return THREE_D_SUPPORTED;
    }

    @Override
    public boolean isVsyncSupported() {
        // Software path can honor swap-chain vsync; GPU path will too.
        // We say yes; the actual presentation policy is per-window in
        // Phase 2.
        return true;
    }

    @Override
    public boolean supportsShaderType(ShaderType type) {
        // Skia uses SkSL internally and does not expose HLSL/GLSL/MSL
        // as user-facing shader types. Returning false instructs Prism
        // not to ship its own shader programs into our pipeline.
        return false;
    }

    @Override
    public boolean supportsShaderModel(ShaderModel model) {
        return false;
    }

    @Override
    public boolean isEffectSupported() {
        // ====================================================================
        // RESIZE INVARIANT #1 — Effect engine activation
        // ====================================================================
        // On. All effect rendering routes through Skia (no Prism code
        // path is ever taken — see /SKPICTURE_CACHING_DESIGN.md for
        // the full architecture). Known acceptable trade-off:
        // drag-resize on Modena content stutters because each frame
        // pays a saveLayer + filter + restore GPU cost per
        // effect-bearing node. SkPicture caching (Task #31) is the
        // long-term fix; until it lands, fast drags briefly skip
        // shadow rendering via the drag-time bypass in each peer (see
        // SkiaEffectRenderer.shouldBypassForDrag).
        return true;
    }

    @Override
    public boolean isUploading() {
        // ====================================================================
        // RESIZE INVARIANT #2 — Painter selection
        // ====================================================================
        // Returning *true* here makes ViewScene.setStage pick
        // UploadingPainter, which:
        //   1. Creates an offscreen RTTexture sized to the scene.
        //   2. Renders the scene into it via Prism Graphics.
        //   3. Reads pixels back GPU→CPU.
        //   4. Uploads via Glass into the window.
        // On every drag-resize WM_SIZE the offscreen RTTexture has to be
        // destroyed and rebuilt at the new size (allocation per frame),
        // and step 3 is a per-frame readback. Both visible as the
        // original "ScrollPane content shrinks and expands" / "content
        // overflows window" symptom we tracked down at the start of
        // the project.
        //
        // Hard rule: must return false on every opaque top-level stage.
        // (Transparent / layered windows are routed to UploadingPainter
        // via WindowStage.needsUpdateWindow() instead — that's a
        // separate, correct path for those window types.)
        return false;
    }

    /** True if the loaded native library is built with real Skia C++. */
    public boolean isNativeSkiaEnabled() {
        return nativeSkiaEnabled;
    }
}
