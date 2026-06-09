package com.sun.prism.skia;

import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.Screen;
import com.sun.javafx.geom.Rectangle;
import com.sun.prism.Presentable;
import com.sun.prism.PresentableState;
import com.sun.prism.Texture.WrapMode;
import com.sun.prism.impl.QueuedPixelSource;
import com.sun.prism.skia.impl.Copies;
import com.sun.prism.skia.impl.Copies.Category;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.prism.skia.impl.NativeBridge3D;
import com.sun.prism.skia.impl.SkiaGpu;

import javafx.application.Application;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.IntBuffer;

/**
 * Skia-backed {@link Presentable} with a four-tier surface allocation:
 *
 * <ol start="0">
 *   <li><b>D3D12 + DXGI flip-model + ALLOW_TEARING</b> — preferred on
 *       Windows. Skia draws into a swap-chain back buffer; present is
 *       {@code IDXGISwapChain3::Present(0, ALLOW_TEARING)}. The only
 *       path that escapes Windows DWM's windowed-vsync cap.</li>
 *   <li><b>GL direct framebuffer + SwapBuffers</b> — fallback when
 *       D3D12 is unavailable. No readback, but subject to DWM's
 *       windowed-vsync cap at maximized.</li>
 *   <li><b>Off-screen GPU + readback</b> — Skia renders to an FBO; we
 *       read pixels back to CPU and hand them to Glass. Used for
 *       transparent/layered windows where direct present can't host.</li>
 *   <li><b>Software raster</b> — pure CPU Skia surface; same readback
 *       + Glass upload. Universal fallback.</li>
 * </ol>
 *
 * The constructor picks the highest tier that succeeds; the choice is
 * captured in {@link #presentMode} so {@code prepare()}/{@code present()}
 * branch accordingly. The direct-present tiers (D3D, GL) skip both the
 * readback in {@code prepare} and the {@code uploadPixels} in {@code present}.
 *
 * <p>For non-direct tiers, our SkSurface is {@code kRGBA_8888} (byte
 * order R, G, B, A); Glass Pixels are {@code INT_ARGB_PRE} which on
 * little-endian is byte order B, G, R, A — the per-pixel swap is done
 * inline during readback.</p>
 */
public final class SkiaPresentable extends SkiaRTTexture implements Presentable {

    private static final Logger LOG = System.getLogger(SkiaPresentable.class.getName());

    // SPIKE (docs/3D.md Increment 1): when -Dskia.3d.spike=true, composite
    // a bgfx-rendered cube onto the finished scene just before present, to
    // prove the zero-copy bgfx->Skia handback. Off by default; no-op when
    // the optional 3D native lib is absent.
    private static final boolean SPIKE_3D = Boolean.getBoolean("skia.3d.spike");

    private final PresentableState pState;
    /** Which present path this presentable uses (picked at construction). */
    private final PresentMode presentMode;
    /**
     * Render scale this presentable's surface was BUILT for, snapshotted at
     * construction. {@code pState} is a shared, mutable state object (the same
     * instance is handed to {@code lockResources} each frame), so its live
     * {@code getRenderScaleX()} cannot be used to detect a scale change — we
     * must compare against this frozen value. See {@link #lockResources}.
     */
    // NOT final: a DPI/scale change on a direct-present tier (D3D/GL) resizes the
    // existing swapchain/drawable IN PLACE (see lockResources) rather than
    // disposing+recreating the presentable, so the scale the live surface was
    // built for advances here when that happens.
    private float builtRenderScaleX;
    private float builtRenderScaleY;
    // Debounce state for the scale-change rebuild (Fix 2). During a DPI-boundary
    // drag the new scale and its (async) size land on different pulses, so the
    // reported (scale,size) disagrees with this presentable nearly every pulse —
    // ~300 dispose+recreate cycles. We only rebuild once the incoming (scale,size)
    // has been identical for two consecutive pulses (settled). These track the
    // previous pulse's values; seeded to the built state in the constructor.
    private float lastSeenScaleX, lastSeenScaleY;
    private int   lastSeenRenderW, lastSeenRenderH;
    private final QueuedPixelSource pixelSource = new QueuedPixelSource(false);
    private Pixels currentPixels;

    /** Selected by {@link #allocate}; drives {@code prepare}/{@code present}. */
    private enum PresentMode {
        /** D3D12 DXGI swap chain. Present(0, ALLOW_TEARING) — no DWM cap. */
        D3D,
        /** GL direct framebuffer + SwapBuffers. Subject to DWM windowed vsync. */
        GL,
        /** Off-screen GPU FBO or software raster → readback → Glass upload. */
        READBACK
    }

    public SkiaPresentable(SkiaResourceFactory factory, PresentableState pState) {
        this(factory, pState, allocate(pState));
    }

    private SkiaPresentable(SkiaResourceFactory factory, PresentableState pState,
                            AllocResult alloc) {
        super(factory, /*screen*/ null,
              alloc.handle(),
              pState.getRenderWidth(),
              pState.getRenderHeight(),
              WrapMode.CLAMP_TO_EDGE,
              /*msaa*/ false);
        this.pState = pState;
        this.presentMode = alloc.mode();
        this.builtRenderScaleX = pState.getRenderScaleX();
        this.builtRenderScaleY = pState.getRenderScaleY();
        this.lastSeenScaleX = builtRenderScaleX;
        this.lastSeenScaleY = builtRenderScaleY;
        this.lastSeenRenderW = pState.getRenderWidth();
        this.lastSeenRenderH = pState.getRenderHeight();
        // DEBUG, not INFO: a DPI-boundary drag rebuilds the presentable hundreds
        // of times in a couple of seconds, and at INFO that floods the log with
        // identical "Presentable created" lines. Surface it only when one of the
        // diag flags is on.
        if (LOG.isLoggable(Level.DEBUG)
                || Boolean.getBoolean("skia.verbose")
                || Boolean.getBoolean("skia.resize.diag")) {
            LOG.log(Level.INFO,
                "Presentable created " + pState.getRenderWidth() + "x"
                + pState.getRenderHeight() + " (" + describe(presentMode) + ")");
        }
        // Surface this presentable's full dimension chain when the D3D
        // diag flag is set — lets us detect view vs render vs output
        // size mismatches that would otherwise be invisible.
        if (Boolean.getBoolean("skia.resize.diag")) {
            LOG.log(Level.INFO,
                String.format(
                  "[skia.dims] view=%dx%d  render=%dx%d  output=%dx%d  scales: render=(%.2f,%.2f) output=(%.2f,%.2f)",
                  pState.getWidth(), pState.getHeight(),
                  pState.getRenderWidth(), pState.getRenderHeight(),
                  pState.getOutputWidth(), pState.getOutputHeight(),
                  pState.getRenderScaleX(), pState.getRenderScaleY(),
                  pState.getOutputScaleX(), pState.getOutputScaleY()));
        }
    }

    private static String describe(PresentMode m) {
        return switch (m) {
            case D3D      -> "tier 0: D3D12 swap chain (Present + ALLOW_TEARING)";
            case GL       -> "tier 1: GL direct framebuffer (SwapBuffers)";
            case READBACK -> "tier 2/3: GPU FBO / raster + readback";
        };
    }

    /** Surface-allocation result. */
    private record AllocResult(long handle, PresentMode mode) {}

    private static AllocResult allocate(PresentableState pState) {
        int w = pState.getRenderWidth();
        int h = pState.getRenderHeight();
        long hwnd = pState.getNativeWindow();

        // Tier 0: D3D12 + DXGI flip-model + ALLOW_TEARING. The only
        // path that escapes Windows DWM's windowed-vsync cap.
        if (SkiaGpu.isEnabled() && hwnd != 0L) {
            MemorySegment seg = NativeBridge.surfaceCreateSwapChainD3d(
                MemorySegment.ofAddress(hwnd), w, h);
            if (seg != null && !seg.equals(MemorySegment.NULL)) {
                return new AllocResult(seg.address(), PresentMode.D3D);
            }
            // Returns NULL when the active backend is GL (D3D init
            // failed earlier) or swap-chain creation failed.
        }
        // Tier 1: GL window-bound GPU (direct-present via SwapBuffers).
        if (SkiaGpu.isEnabled() && hwnd != 0L) {
            MemorySegment seg = NativeBridge.surfaceCreateWindowGpu(
                MemorySegment.ofAddress(hwnd), w, h);
            if (seg != null && !seg.equals(MemorySegment.NULL)) {
                return new AllocResult(seg.address(), PresentMode.GL);
            }
            // Returns NULL for layered/transparent windows or pixel-
            // format clashes — fall through.
        }
        // Tier 2: off-screen GPU + readback.
        if (SkiaGpu.isEnabled()) {
            MemorySegment seg = NativeBridge.surfaceCreateGpu(w, h);
            if (seg != null && !seg.equals(MemorySegment.NULL)) {
                return new AllocResult(seg.address(), PresentMode.READBACK);
            }
        }
        // Tier 3: software raster.
        MemorySegment seg = NativeBridge.surfaceCreateRaster(w, h);
        if (seg == null || seg.equals(MemorySegment.NULL)) {
            throw new IllegalStateException(
                "Skia surface allocation failed (" + w + "x" + h + ")");
        }
        return new AllocResult(seg.address(), PresentMode.READBACK);
    }

    @Override
    public boolean lockResources(PresentableState newState) {
        int nw = newState.getRenderWidth();
        int nh = newState.getRenderHeight();
        boolean sizeChanged = getPhysicalWidth() != nw || getPhysicalHeight() != nh;

        // A render-SCALE change (dragging the window to a monitor with a
        // different DPI) must NOT take the in-place surface-resize path below.
        // For the GL/D3D direct tiers the in-place resize re-wraps the existing
        // drawable, which does not pick up the window's new physical size on a
        // DPI change — the surface stays at its old device size, so Skia renders
        // the (correctly device-scaled) scene into the old, smaller region and
        // the content fills only builtScale/newScale of the window (the "content
        // renders only a piece after a monitor move" bug). Force a full dispose+
        // recreate instead, so a fresh surface is built at the new size on the
        // now-resized window; PresentingPainter recreates the presentable when
        // lockResources returns true. Scale changes are rare (monitor moves), so
        // the rebuild cost is irrelevant.
        //
        // NB: compare against the snapshot taken at construction, NOT
        // pState.getRenderScaleX() — pState is the same mutable instance as
        // newState here (samePState), so that comparison is always equal.
        float nsx = newState.getRenderScaleX();
        float nsy = newState.getRenderScaleY();
        boolean scaleChanged = nsx != builtRenderScaleX || nsy != builtRenderScaleY;
        if (scaleChanged) {
            // Debounce (Fix 2): a monitor-straddle drag reports the new scale a
            // pulse before the (async WM_SIZE) new size, so the (scale,size) pair
            // disagrees with this presentable on nearly every pulse — disposing +
            // recreating ~300 times in a couple of seconds. Only act once the
            // incoming (scale,size) has SETTLED — i.e. is identical to the previous
            // pulse's. While it's still changing, keep the current presentable
            // (return false) for one more pulse. A real monitor move settles in a
            // pulse or two, so the change still applies (at the correct final
            // scale/size, skipping the wrong-size intermediate); the storm does not.
            boolean settled = nsx == lastSeenScaleX && nsy == lastSeenScaleY
                && nw == lastSeenRenderW && nh == lastSeenRenderH;
            lastSeenScaleX = nsx; lastSeenScaleY = nsy;
            lastSeenRenderW = nw; lastSeenRenderH = nh;
            if (!settled) {
                return false; // still oscillating — defer one pulse
            }
            // Settled at a new scale. For the D3D tier resize the live swapchain
            // IN PLACE instead of disposing+recreating the presentable. A
            // dispose+recreate races the swapchain lifecycle: DXGI permits only
            // ONE flip-model swapchain per HWND, and the old one is not always
            // released before createPresentable() calls CreateSwapChainForHwnd
            // again — which then fails with E_ACCESSDENIED (0x80070005). The
            // presentable falls back to the READBACK tier, which leaves the stale
            // (larger) surface on the window scaled DOWN by DWM to the new client
            // size — content renders at oldScale/newScale (e.g. 1/1.75 moving
            // 175%→100%) until a later manual resize finally recreates a D3D
            // swapchain. ResizeBuffers keeps the same HWND association and just
            // resizes to the new device size — no race, no tier downgrade, no
            // shrink. The GL tier has no per-HWND swapchain limit (recreate is
            // safe there) and its in-place re-wrap does not pick up the new
            // physical size, so it stays on the recreate path; READBACK/raster
            // wraps a reallocated pixel buffer and likewise must recreate.
            if (presentMode == PresentMode.D3D) {
                int rc = NativeBridge.surfaceResizeD3d(
                    MemorySegment.ofAddress(getNativeHandle()), nw, nh);
                if (rc == 0) {
                    builtRenderScaleX = nsx; builtRenderScaleY = nsy;
                    setPhysicalSize(nw, nh);
                    return false; // kept on D3D, swapchain resized — no downgrade
                }
                // ResizeBuffers failed (rare): fall through to full recreate.
            }
            return true; // GL / READBACK / raster (or in-place resize failed) — rebuild now
        }
        // Scale unchanged: keep the debounce snapshot current so a later scale
        // change is measured against the latest size.
        lastSeenScaleX = nsx; lastSeenScaleY = nsy;
        lastSeenRenderW = nw; lastSeenRenderH = nh;
        if (!sizeChanged) return false;

        // D3D path: in-place resize via IDXGISwapChain::ResizeBuffers.
        // DWM treats this as a semantic resize, not a stretch — so the
        // window doesn't show distorted contents during drag. We keep
        // the same presentable instance and just update its cached
        // dimensions, avoiding the swap-chain teardown that regressed
        // the previous attempt to ~55 fps small / 6 fps maximized.
        if (presentMode == PresentMode.D3D) {
            int rc = NativeBridge.surfaceResizeD3d(
                MemorySegment.ofAddress(getNativeHandle()), nw, nh);
            if (rc == 0) {
                setPhysicalSize(nw, nh);
                return false; // keep the presentable, do not dispose
            }
            // Resize failed — fall through to "true" so the painter
            // rebuilds the presentable from scratch.
        }
        // GL direct-present: re-wrap FBO 0 at the new size on the same
        // GrDirectContext / HDC binding. Cheap (one wgl + one wrap)
        // versus the prior destroy-then-recreate-presentable path which
        // ran for every WM_SIZE tick during a drag-resize and was the
        // primary cause of the ScrollPane shrink/expand shimmer on
        // non-maximized resize.
        if (presentMode == PresentMode.GL) {
            int rc = NativeBridge.surfaceResizeGl(
                MemorySegment.ofAddress(getNativeHandle()), nw, nh);
            if (rc == 0) {
                setPhysicalSize(nw, nh);
                return false; // keep the presentable, do not dispose
            }
            // Wrap failed (rare — typically context loss): fall through
            // to the recreate path.
        }
        // READBACK / raster: dispose + recreate is the only option
        // (raster wraps a Java pixel buffer that gets re-allocated at
        // the new size).
        return true;
    }

    @Override
    public boolean prepare(Rectangle dirtyregion) {
        if (pState.isViewClosed()) return false;
        // D3D: wait on DXGI's frame-latency waitable + per-buffer
        // fence before any drawing this frame. Gives deterministic
        // CPU↔GPU pacing with a 1-frame lead.
        if (presentMode == PresentMode.D3D) {
            int rc = NativeBridge.surfaceBeginFrameD3d(
                MemorySegment.ofAddress(getNativeHandle()));
            if (rc != 0) return false;
            return true;
        }
        // Direct-present (GL): nothing to copy — the surface IS the
        // window's back buffer. Skia drew straight into it.
        if (presentMode != PresentMode.READBACK) {
            return true;
        }

        int w = getPhysicalWidth();
        int h = getPhysicalHeight();

        // Reuse a Pixels from the queue, or allocate one. w/h are the
        // surface's physical pixels (what we read back); the scale must be
        // the window's output scale so Glass composites the uploaded pixels
        // at the correct logical size on HiDPI displays — matching
        // UploadingPainter, which passes sceneState.getOutputScaleX/Y().
        // Passing 1.0 here double-sized the readback present on HiDPI.
        currentPixels = pixelSource.getUnusedPixels(
            w, h, pState.getOutputScaleX(), pState.getOutputScaleY());
        IntBuffer dst = (IntBuffer) currentPixels.getPixels();
        if (!dst.hasArray()) {
            // QueuedPixelSource is constructed with useDirectBuffers=false,
            // so this should always have an array. Defensive check.
            return false;
        }

        // Read directly into INT_ARGB_PRE layout — Skia does the
        // channel swap in C++ during the native readPixels call, so
        // Java doesn't need a second pass. One GPU→CPU readback into
        // the POOLED per-thread scratch buffer (shared with
        // SkiaRTTexture.readPixels — grown monotonically, never freed
        // per frame), then a single MemorySegment.copy into the
        // IntBuffer's heap array. prepare() runs on the render thread,
        // so it gets that thread's pooled buffer; this avoids the
        // ~4 MB-at-1080p confined-arena malloc/free every presented
        // frame on the READBACK tier (BUG-5).
        long bytes = (long) w * h * 4;
        MemorySegment src = ensureReadBuffer(bytes);
        int rc = NativeBridge.surfaceReadPixelsArgb(
            MemorySegment.ofAddress(getNativeHandle()),
            src, 0, 0, w, h);
        if (rc != 0) return false;
        Copies.add(Category.SNAPSHOT_READBACK, 1);

        int[] outArr = dst.array();
        int outOff = dst.arrayOffset() + dst.position();
        MemorySegment dstSeg = MemorySegment.ofArray(outArr)
            .asSlice((long) outOff * 4, bytes);
        MemorySegment.copy(src, 0L, dstSeg, 0L, bytes);
        Copies.add(Category.MEMORY_SEGMENT_COPY, 1);
        return true;
    }

    // ---- FPS counter (logged every second, gated by -Dskia.verbose) -----
    private static final boolean VERBOSE =
        Boolean.getBoolean("skia.verbose");
    private static long fpsWindowStart;
    private static int  fpsFrames;
    private static long fpsLastFrame;

    /**
     * Paint-before-show: GDI-blit the just-rendered frame onto the window's DWM
     * redirection bitmap so the OS show animation reveals real UI instead of a
     * blank/flash. Call once, right before {@link #present()}, while painting
     * the first frame of a not-yet-shown window. Native no-ops for surfaces with
     * no window swap chain (e.g. the READBACK present mode).
     */
    public void primeWindowForShow() {
        NativeBridge.surfacePrimeWindow(MemorySegment.ofAddress(getNativeHandle()));
    }

    @Override
    public boolean present() {
        // SPIKE hook: draw the 3D cube LAST (after the scene is painted),
        // onto this on-screen presentable's current back buffer, so it is
        // visible. Render-thread only; result ignored — must never break
        // presentation. No-op unless -Dskia.3d.spike and the 3D lib is
        // present + on a D3D backend.
        if (SPIKE_3D) {
            NativeBridge3D.spikeComposite(getNativeHandle(),
                Math.min(256, getContentWidth()),
                Math.min(256, getContentHeight()));
        }
        // Runtime VSync (Application.vsyncEnabledProperty): 1 = present(1,0) /
        // vblank-synced, 0 = present(0, ALLOW_TEARING) / uncapped. A per-frame
        // read of a volatile flag (global — every window honors it); toggling
        // only changes the present sync interval, never rebuilds the swap chain,
        // so it is safe at any time.
        final int vsync = Application.isVsyncEnabled() ? 1 : 0;
        switch (presentMode) {
            case D3D -> {
                // grCtx->flushAndSubmit + DXGI Present (vsync ? (1,0) : (0, ALLOW_TEARING)).
                int rc = NativeBridge.surfacePresentWindowD3d(
                    MemorySegment.ofAddress(getNativeHandle()), vsync);
                if (rc != 0) return false;
                countFrame();
                // Drive the 3D deferred-target-free clock off real presents (no-op
                // when 3D isn't loaded). Must be a true present, hence after rc==0.
                NativeBridge3D.notifyPresent();
                return true;
            }
            case GL -> {
                // grCtx->flushAndSubmit + wglSwapBuffers (vsync via wglSwapIntervalEXT).
                int rc = NativeBridge.surfacePresentWindow(
                    MemorySegment.ofAddress(getNativeHandle()), vsync);
                if (rc != 0) return false;
                countFrame();
                // Drive the 3D deferred-target-free clock off real presents (no-op
                // when 3D isn't loaded). Must be a true present, hence after rc==0.
                NativeBridge3D.notifyPresent();
                return true;
            }
            case READBACK -> {
                if (currentPixels == null) return false;
                pixelSource.enqueuePixels(currentPixels);
                pState.uploadPixels(pixelSource);
                // One CPU → OS-window pixel upload via Glass per frame.
                Copies.add(Category.TEXTURE_UPLOAD, 1);
                currentPixels = null;
                countFrame();
                // Drive the 3D deferred-target-free clock off real presents (no-op
                // when 3D isn't loaded). Must be a true present, hence after rc==0.
                NativeBridge3D.notifyPresent();
                return true;
            }
        }
        return false;
    }

    /**
     * Most recent measured window-present frame rate (frames actually presented
     * to the swap chain per second), refreshed about once a second. Exposed for
     * on-screen telemetry in sample apps; reflects the runtime VSync state (it
     * caps near the display refresh when VSync is on, runs uncapped when off).
     * {@code 0} until the first second of presenting elapses.
     */
    public static volatile double LAST_PRESENT_FPS;

    private static void countFrame() {
        long now = System.nanoTime();
        synchronized (SkiaPresentable.class) {
            // A long gap since the previous present means the scene went idle
            // (adaptive cadence rendered nothing). Start a fresh window so the
            // first frame back doesn't report an fps averaged across the idle gap.
            if (fpsLastFrame != 0 && now - fpsLastFrame > 250_000_000L) {
                fpsWindowStart = now;
                fpsFrames = 0;
            }
            fpsLastFrame = now;
            if (fpsWindowStart == 0) fpsWindowStart = now;
            fpsFrames++;
            long elapsed = now - fpsWindowStart;
            if (elapsed >= 1_000_000_000L) {
                double fps = fpsFrames * 1_000_000_000.0 / elapsed;
                LAST_PRESENT_FPS = fps;
                if (!VERBOSE) {
                    fpsWindowStart = now;
                    fpsFrames = 0;
                    return;
                }
                java.util.Map<Category, Long> copies = Copies.snapshot();
                long checkouts = com.sun.scenario.effect.impl.skia
                    .SkiaEffectRenderer.CHECKOUT_COUNT.getAndSet(0);
                long allocs = com.sun.scenario.effect.impl.skia
                    .SkiaEffectRenderer.ALLOC_COUNT.getAndSet(0);
                long bypass = com.sun.scenario.effect.impl.skia
                    .SkiaEffectRenderer.BYPASS_COUNT.getAndSet(0);
                System.err.printf("[skia] %.1f fps  %s  effects: checkouts=%d allocs=%d bypass=%d%n",
                    fps, Copies.formatLine(copies), checkouts, allocs, bypass);
                fpsWindowStart = now;
                fpsFrames = 0;
            }
        }
    }

    @Override public float getPixelScaleFactorX() { return pState.getRenderScaleX(); }
    @Override public float getPixelScaleFactorY() { return pState.getRenderScaleY(); }

    @Override public int getContentWidth()  { return pState.getOutputWidth(); }
    @Override public int getContentHeight() { return pState.getOutputHeight(); }

    @Override
    public Screen getAssociatedScreen() {
        // Best-effort screen: PresentableState exposes screen dimensions
        // but not the Screen instance directly. Phase-2 wiring picks
        // the real one from Glass; for now return main.
        return Screen.getMainScreen();
    }
}
