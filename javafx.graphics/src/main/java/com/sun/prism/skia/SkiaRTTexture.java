package com.sun.prism.skia;

import com.sun.glass.ui.Screen;
import com.sun.prism.Graphics;
import com.sun.prism.Image;
import com.sun.prism.MediaFrame;
import com.sun.prism.PixelFormat;
import com.sun.prism.RTTexture;
import com.sun.prism.Texture.WrapMode;
import com.sun.prism.skia.impl.Copies;
import com.sun.prism.skia.impl.Copies.Category;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.prism.skia.impl.NativeBridge3D;
import com.sun.prism.skia.impl.NativeHandles;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.Buffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Skia-backed {@link RTTexture}. Wraps a CPU-backed {@code SkSurface}
 * obtained via the native bridge. The same handle backs the
 * {@link Graphics} created by {@link #createGraphics()}.
 *
 * <p><b>Phase 1 status:</b> raster only (no GPU, no MSAA). Screen
 * association is the main screen; {@link #isOpaque()} defaults to
 * {@code false} (the surface is initialized to fully-transparent
 * pixels by Skia).</p>
 */
public class SkiaRTTexture extends SkiaTextureBase implements RTTexture {

    private final SkiaResourceFactory factory;
    private final Screen screen;
    private final boolean msaa;
    private boolean opaque;

    // Door 1: lazily-created native bgfx 3D target (shared color+depth)
    // when this RTT hosts a 3D SubScene, plus the per-pass "begun" flag.
    private long native3dTarget = 0L;
    // Cleaner-backed owner of native3dTarget. The base class's Cleaner only owns the
    // SkSurface; without this slot a leaked/GC'd RTT (a 3D SubScene removed from the
    // scene, or the surface-lost path that nulls the RTT without dispose) would leak
    // the bgfx color+depth(+MSAA) framebuffer — the most expensive 3D resource. The
    // slot frees it on explicit dispose (inline, render thread) AND as a GC safety
    // net (deferred to the render thread by NativeHandles); double-free is impossible.
    private NativeHandles.Slot slot3d;
    private boolean pass3dBegun = false;
    // Content size the native 3D target was created at; rebuilt when it changes
    // (pooled RTT reused at a new size) so bgfx renders at the sampled size.
    private int target3dW = 0;
    private int target3dH = 0;
    // Desired MSAA sample count for the 3D target (<=0 = pipeline default, 1 = off,
    // 2/4/8 = that count). Driven per-SubScene by NGSubScene from the SceneAntialiasing
    // state / the dev.skiafx.scene3d.Skia3D runtime toggle. The target is rebuilt when
    // this changes, just like a content-size change, so AA can be toggled at runtime.
    private int desired3dSamples = -1;   // -1 = use pipeline default
    private int target3dSamples = -1;    // sample count the live target was built with
    // Last requested content size, for the rebuild debounce below.
    private int pending3dW = -1;
    private int pending3dH = -1;
    // Consecutive frames the requested size has differed from the live target
    // without ever matching the previous request ("settling"). A steady 2-value
    // pixel oscillation never settles, so we force a rebuild after this many
    // frames to keep the target from latching at a stale size indefinitely.
    private int unsettled3dFrames = 0;
    private static final int MAX_UNSETTLED_3D_FRAMES = 8;

    /**
     * Allocates a new raster SkSurface of size (width × height). Throws
     * if the native side could not allocate (for example when Skia is
     * not compiled into the bridge).
     */
    public SkiaRTTexture(SkiaResourceFactory factory, Screen screen,
                         int width, int height,
                         WrapMode wrapMode, boolean msaa) {
        super(allocSurface(width, height),
              SkiaRTTexture::destroyNative,
              PixelFormat.BYTE_BGRA_PRE,
              width, height,
              width, height,
              wrapMode,
              false /* useMipmap */,
              true /* countBytes — owns its native surface */);
        this.factory = factory;
        this.screen = screen;
        this.msaa = msaa;
        // M18 18b: only normal off-screen RTs are eviction candidates. The adopt
        // ctor below (window/presentable surfaces) and the non-owning wrap are NOT
        // tracked — a presentable must never be evicted. No-op unless
        // -Djavafx.gpu.evictUnlockedRTs=true.
        SkiaTextureResourcePool.INSTANCE.trackRT(this);
    }

    /**
     * Adopts an already-allocated native handle. Used by
     * {@link SkiaPresentable} to wrap window-bound or off-screen GPU
     * surfaces it allocated through alternate native paths.
     */
    protected SkiaRTTexture(SkiaResourceFactory factory, Screen screen,
                            long nativeHandle,
                            int width, int height,
                            WrapMode wrapMode, boolean msaa) {
        super(nativeHandle,
              SkiaRTTexture::destroyNative,
              PixelFormat.BYTE_BGRA_PRE,
              width, height,
              width, height,
              wrapMode,
              false /* useMipmap */,
              true /* countBytes — adopts + owns the native surface */);
        this.factory = factory;
        this.screen = screen;
        this.msaa = msaa;
    }

    private static void destroyNative(long handle) {
        NativeBridge.surfaceDestroy(MemorySegment.ofAddress(handle));
    }

    /**
     * Wraps a native surface handle without taking ownership of its
     * lifecycle — the caller is responsible for destroying the
     * underlying resource. Used by the Phase C scene-cache to wrap an
     * SkPictureRecorder's recording canvas (the recorder owns the
     * canvas; releasing it via {@code openjfx_skia_picture_recorder_finish}
     * is the caller's responsibility, NOT the texture's dispose).
     */
    public static SkiaRTTexture wrapNonOwning(SkiaResourceFactory factory,
                                              long nativeHandle,
                                              int width, int height) {
        // Use the main screen for filter-context lookups — the wrapped
        // canvas is owned by a recorder, not a real GPU surface, so any
        // screen will do (PrFilterContext just needs SOME screen).
        return new SkiaRTTexture(factory, Screen.getMainScreen(),
            nativeHandle, width, height,
            WrapMode.CLAMP_TO_EDGE, /*msaa*/ false,
            /*nonOwning*/ true);
    }

    /** Internal constructor for non-owning wraps. The {@code nonOwning}
     *  flag selects a no-op destroyer so the registered Cleaner doesn't
     *  free a handle the caller still manages. */
    private SkiaRTTexture(SkiaResourceFactory factory, Screen screen,
                          long nativeHandle,
                          int width, int height,
                          WrapMode wrapMode, boolean msaa,
                          boolean nonOwning) {
        super(nativeHandle,
              nonOwning ? SkiaRTTexture::noopDestroy
                        : SkiaRTTexture::destroyNative,
              PixelFormat.BYTE_BGRA_PRE,
              width, height,
              width, height,
              wrapMode,
              false /* useMipmap */,
              !nonOwning /* countBytes — a non-owning wrap must not inflate the budget (M1) */);
        this.factory = factory;
        this.screen = screen;
        this.msaa = msaa;
    }

    private static void noopDestroy(long handle) {
        // Non-owning wrap — caller manages the handle's lifecycle
        // (e.g., SkPictureRecorder owns its canvas).
    }

    private static long allocSurface(int width, int height) {
        // Try GPU first when Phase-2 GPU mode is enabled; fall through to
        // software raster on a null return so a per-size GPU allocation
        // failure (e.g. out of GPU memory) degrades gracefully.
        if (com.sun.prism.skia.impl.SkiaGpu.isEnabled()) {
            MemorySegment gpu = NativeBridge.surfaceCreateGpu(width, height);
            if (gpu != null && !gpu.equals(MemorySegment.NULL)) {
                return gpu.address();
            }
        }
        MemorySegment handle = NativeBridge.surfaceCreateRaster(width, height);
        if (handle == null || handle.equals(MemorySegment.NULL)) {
            throw new IllegalStateException(
                "Skia surface allocation failed (" + width + "x" + height
                + "). Bridge has no Skia integration?");
        }
        return handle.address();
    }

    private MemorySegment handleSegment() {
        long h = getNativeHandle();
        if (h == 0L) {
            throw new IllegalStateException("SkiaRTTexture is disposed");
        }
        return MemorySegment.ofAddress(h);
    }

    // ---- RenderTarget ------------------------------------------------------

    @Override public Screen getAssociatedScreen() { return screen; }

    @Override
    public Graphics createGraphics() {
        return new SkiaGraphics(factory, this);
    }

    @Override public boolean isOpaque()             { return opaque; }
    @Override public void setOpaque(boolean opaque) { this.opaque = opaque; }
    @Override public boolean isMSAA()               { return msaa; }
    @Override public boolean isVolatile()           { return false; }

    // ---- RTTexture --------------------------------------------------------

    /**
     * Returns {@code null} so that {@link com.sun.javafx.tk.quantum.UploadingPainter}
     * falls through to {@link #readPixels(java.nio.Buffer)} — the
     * read path that converts Skia's RGBA8888 layout to Glass's
     * expected INT_ARGB_PRE int packing. Returning a raw RGBA int[]
     * here would feed wrong-channel pixels to the window.
     *
     * <p>Tests that want the raw Skia-side pixel layout (RGBA bytes
     * → 0xAABBGGRR int on little-endian) call {@link #getRawPixels()}.</p>
     */
    @Override
    public int[] getPixels() {
        return null;
    }

    /**
     * Read the surface contents in Skia's native RGBA8888 layout.
     * Used by tests; production callers should rely on
     * {@link #readPixels(java.nio.Buffer)}.
     */
    public int[] getRawPixels() {
        int w = getContentWidth();
        int h = getContentHeight();
        if (w <= 0 || h <= 0) return new int[0];
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate((long) w * h * 4);
            int rc = NativeBridge.surfaceReadPixels(handleSegment(), dst, 0, 0, w, h);
            if (rc != 0) {
                throw new IllegalStateException("surface_read_pixels rc=" + rc);
            }
            int[] out = new int[w * h];
            for (int i = 0; i < out.length; i++) {
                out[i] = dst.get(ValueLayout.JAVA_INT, (long) i * 4);
            }
            return out;
        }
    }

    @Override
    public boolean readPixels(Buffer pixels) {
        return readPixels(pixels, 0, 0, getContentWidth(), getContentHeight());
    }

    // ---- FPS counter (logged whenever Quantum uploads our RT) ---------
    // Lock-free: this runs on the hot readback path from BOTH the render thread
    // (pulse) and the FX thread (snapshot/PixelReader). The old
    // synchronized(SkiaRTTexture.class) put needless class-monitor contention
    // there. Benign races (a frame counted in the next window) don't matter for
    // a best-effort FPS sample; a CAS on the window start ensures only one
    // thread rolls the window.
    private static final AtomicLong fpsWindowStart = new AtomicLong();
    private static final LongAdder fpsFrames = new LongAdder();

    private static final boolean VERBOSE = Boolean.getBoolean("skia.verbose");

    private static void tickFps() {
        long now = System.nanoTime();
        long start = fpsWindowStart.get();
        if (start == 0) {
            start = fpsWindowStart.compareAndSet(0, now) ? now : fpsWindowStart.get();
        }
        fpsFrames.increment();
        long elapsed = now - start;
        if (elapsed >= 1_000_000_000L && fpsWindowStart.compareAndSet(start, now)) {
            long frames = fpsFrames.sumThenReset();
            double fps = frames * 1_000_000_000.0 / elapsed;
            LAST_FPS = fps;
            if (VERBOSE) System.err.printf("[skia] %.1f fps%n", fps);
        }
    }

    /** Most recent FPS sample (rolling 1-second window). */
    public static volatile double LAST_FPS;

    private static boolean READ_PATH_LOGGED;

    // Pooled native scratch buffer for the heap-array readPixels path. Reused
    // across frames; grows monotonically. PER-THREAD: snapshot/PixelReader readback
    // runs on the FX thread while pulse-driven readback runs on the render thread,
    // so a single shared static (the old design) let one readback close() the
    // buffer another thread was mid-writing — a data race / use-after-free. A
    // ThreadLocal gives each thread its own buffer; Arena.ofAuto() means the old
    // buffer is GC-reclaimed when dropped on grow (and when the thread dies), so
    // there's no cross-thread free and no leaked confined arena.
    private static final class ReadBuf {
        MemorySegment seg;
        long cap;
    }
    private static final ThreadLocal<ReadBuf> READ_BUF =
        ThreadLocal.withInitial(ReadBuf::new);

    // Package-visible so SkiaPresentable's READBACK-tier prepare() reuses this
    // same pooled per-thread buffer instead of allocating a confined arena per
    // presented frame (BUG-5). Render-thread-confined in practice (each thread
    // gets its own ReadBuf via the ThreadLocal), so no cross-thread sharing.
    static MemorySegment ensureReadBuffer(long bytes) {
        ReadBuf rb = READ_BUF.get();
        if (rb.seg != null && rb.cap >= bytes) {
            return rb.seg;
        }
        rb.cap = Math.max(bytes, rb.cap * 2);
        rb.seg = Arena.ofAuto().allocate(rb.cap, 4);  // GC-reclaimed; old buffer freed when unreachable
        return rb.seg;
    }

    /** Generation counter, kept for SkiaGraphics state-cache invalidation. */
    volatile int backingGeneration;

    // DIAG: log up to N distinct call stacks that hit this readPixels.
    // Direct-present mode shouldn't need any GPU→CPU readback; whatever
    // is calling here is the next zero-copy target. We cap the log so
    // we get the catalog of callers without flooding the console.
    private static final java.util.Set<String> READ_CALLERS =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int READ_CALLERS_CAP = 6;

    @Override
    public boolean readPixels(Buffer pixels, int x, int y, int width, int height) {
        if (!(pixels instanceof IntBuffer ib)) {
            return false;
        }
        if (READ_CALLERS.size() < READ_CALLERS_CAP) {
            // One-line caller signature (skip Throwable.fillInStackTrace
            // + this method itself). Set is cheap on the hot path once
            // it's full because we only check size().
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            String key = (st.length >= 4)
                ? st[2].toString() + " <- " + st[3].toString()
                       + (st.length >= 5 ? " <- " + st[4] : "")
                : "<unknown>";
            if (READ_CALLERS.add(key) && VERBOSE) {
                System.err.println("[skia.readback] " + key
                    + "  region=" + width + "x" + height);
            }
        }
        tickFps();
        // Direct IntBuffer: Skia writes BGRA bytes (= INT_ARGB_PRE)
        // straight into the buffer's native memory in one Skia-side
        // memcpy. We honor the buffer's current position via
        // MemorySegment.ofBuffer.
        if (ib.isDirect()) {
            MemorySegment seg = MemorySegment.ofBuffer(ib);
            int rc = NativeBridge.surfaceReadPixelsArgb(
                handleSegment(), seg, x, y, width, height);
            if (rc == 0) {
                Copies.add(Category.SNAPSHOT_READBACK, 1);
            }
            return rc == 0;
        }
        // Heap-array IntBuffer. Use a pooled native scratch buffer
        // (allocated once, grown monotonically) so we don't pay an
        // Arena allocation each pulse.
        long n = (long) width * height;
        MemorySegment dst = ensureReadBuffer(n * 4);
        int rc = NativeBridge.surfaceReadPixelsArgb(
            handleSegment(), dst, x, y, width, height);
        if (rc != 0) return false;
        Copies.add(Category.SNAPSHOT_READBACK, 1);
        int[] arr = ib.array();
        int off = ib.arrayOffset() + ib.position();
        MemorySegment.copy(dst, ValueLayout.JAVA_INT, 0L,
                           arr, off, (int) Math.min(n, arr.length - off));
        Copies.add(Category.MEMORY_SEGMENT_COPY, 1);
        return true;
    }

    // ---- Texture update overloads (RTs aren't typically updated this way) -

    @Override public void update(Image img)                                                 { rejectUpdate(); }
    @Override public void update(Image img, int dstx, int dsty)                             { rejectUpdate(); }
    @Override public void update(Image img, int dstx, int dsty, int srcw, int srch)         { rejectUpdate(); }
    @Override public void update(Image img, int dstx, int dsty, int srcw, int srch,
                                 boolean skipFlush)                                          { rejectUpdate(); }
    @Override public void update(Buffer buffer, PixelFormat format,
                                 int dstx, int dsty, int srcx, int srcy, int srcw, int srch,
                                 int srcscan, boolean skipFlush)                             { rejectUpdate(); }
    @Override public void update(MediaFrame frame, boolean skipFlush)                        { rejectUpdate(); }

    private static void rejectUpdate() {
        throw new UnsupportedOperationException(
            "SkiaRTTexture is a render target — draw via createGraphics(), "
            + "do not call Texture.update() on it.");
    }

    // ---- Door 1: bgfx 3D target lifecycle (called by SkiaGraphics) --------

    /**
     * Lazily create — and keep sized to — the native bgfx 3D target for this
     * RTT. 0 if 3D unavailable.
     *
     * <p>SubScene render targets are pooled and reused: the same SkiaRTTexture
     * instance can be handed back at a different {@linkplain #getContentWidth()
     * content size} on a later pulse (window/layout settle on show, resize).
     * bgfx renders the scene into a framebuffer of exactly the target's size and
     * the compositor samples a {@code content}-sized rect from it, so if the
     * target is left at a stale (larger) size the compositor reads a sub-crop and
     * the 3D content appears zoomed in until a fresh pooled RTT is acquired.
     * Rebuild whenever the content size changes so the bgfx framebuffer always
     * matches what {@code composite3DRtt} samples.</p>
     */
    long ensureNative3DTarget() {
        int w = getContentWidth();
        int h = getContentHeight();
        if (w <= 0 || h <= 0) {
            // SubScene not laid out yet / collapsed to zero — never hand bgfx a
            // 0-size framebuffer. Keep whatever target exists (a later non-zero
            // frame rebuilds it via sizeChanged); create nothing now.
            return native3dTarget;
        }
        boolean sampleChanged = desired3dSamples != target3dSamples;
        boolean sizeChanged = w != target3dW || h != target3dH;
        if (!sizeChanged) {
            unsettled3dFrames = 0;
        }
        if (native3dTarget != 0L && (sizeChanged || sampleChanged)) {
            // DEBOUNCE the size-driven rebuild. During a monitor-move / DPI drag the
            // requested size jitters by a pixel almost every frame. Rebuilding the
            // bgfx framebuffer each time means creating + destroying a D3D12 colorRes
            // texture + framebuffer (and an extra bgfx::frame()) per frame — a storm
            // of GPU resource churn that, across a cross-monitor / cross-GPU
            // transition, can hang and reset the GPU driver (TDR). So only rebuild
            // once the requested size has SETTLED (identical to the previous request),
            // which collapses the storm to a single rebuild after the drag stops. A
            // sample-count change (the AA toggle) is rare and rebuilds immediately.
            // While unsettled we keep the existing target for this frame; the 3D
            // composite samples getContentWidth()/Height() from it (a ≤jitter-sized
            // crop) until the rebuild lands — far cheaper than a per-frame teardown.
            boolean settled = (w == pending3dW && h == pending3dH);
            pending3dW = w;
            pending3dH = h;
            // Escape valve: a sustained 2-value oscillation never "settles", so
            // force the rebuild after MAX_UNSETTLED_3D_FRAMES. This still collapses
            // a real DPI-drag storm (at most one rebuild per N frames, not per
            // frame) while guaranteeing the target can't stay stale forever.
            boolean forced = sizeChanged && (++unsettled3dFrames >= MAX_UNSETTLED_3D_FRAMES);
            if (sampleChanged || settled || forced) {
                closeNative3DTarget();
                pass3dBegun = false;
                unsettled3dFrames = 0;
            }
        }
        if (native3dTarget == 0L) {
            native3dTarget = NativeBridge3D.targetCreate(w, h, desired3dSamples);
            if (native3dTarget != 0L) {
                // Register with a Cleaner so a leaked/GC'd RTT still frees this
                // target (the base Cleaner only covers the SkSurface).
                slot3d = NativeHandles.register(this, native3dTarget, NativeBridge3D::targetDestroy);
            }
            target3dW = w;
            target3dH = h;
            target3dSamples = desired3dSamples;
            pending3dW = w;
            pending3dH = h;
            unsettled3dFrames = 0;
        }
        return native3dTarget;
    }

    /**
     * Set the desired MSAA sample count for this RTT's 3D target ({@code <=0} =
     * pipeline default, {@code 1} = off, {@code 2/4/8} = that count). Driven by
     * {@code NGSubScene} from the SubScene's anti-aliasing state. The native target
     * is rebuilt by {@link #ensureNative3DTarget()} on the next render if it changed.
     */
    public void set3DSamples(int samples) {
        this.desired3dSamples = samples;
    }

    /** Begin a 3D pass once per SubScene render (bind framebuffer + clear). */
    void begin3DPassIfNeeded() {
        if (native3dTarget != 0L && !pass3dBegun) {
            // Transparent clear so the 3D result composites over the parent (v1).
            NativeBridge3D.targetBegin(native3dTarget, 0f, 0f, 0f, 0f);
            pass3dBegun = true;
            // Reset the per-frame texture-upload budget so a scene that needs many
            // large maps spreads its (expensive) first-frame uploads over several
            // frames instead of stalling one frame — see SkiaPhongMaterial.
            SkiaPhongMaterial.beginUploadPass();
        }
    }

    boolean is3DPassBegun() { return pass3dBegun; }

    /** End the bgfx pass and wrap its color as a zero-copy SkImage handle (0 on failure). */
    long composite3DEnd() {
        if (native3dTarget == 0L || !pass3dBegun) {
            return 0L;
        }
        NativeBridge3D.targetEnd(native3dTarget);
        pass3dBegun = false;
        return NativeBridge3D.targetWrapImage(native3dTarget);
    }

    /**
     * Free the native bgfx 3D target via its Cleaner slot (idempotent; frees the
     * handle exactly once, inline on the render thread). Also resets the cached
     * handle so {@link #ensureNative3DTarget()} rebuilds it on the next render.
     */
    private void closeNative3DTarget() {
        if (slot3d != null) {
            slot3d.close();
            slot3d = null;
        }
        native3dTarget = 0L;
    }

    @Override
    public void dispose() {
        closeNative3DTarget();
        super.dispose();
    }

}
