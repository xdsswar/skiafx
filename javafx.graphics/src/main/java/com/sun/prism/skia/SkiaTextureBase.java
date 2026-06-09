package com.sun.prism.skia;

import com.sun.prism.PixelFormat;
import com.sun.prism.Texture;
import com.sun.prism.Texture.WrapMode;
import com.sun.prism.skia.impl.NativeHandles;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Common bookkeeping for Skia-backed textures.
 *
 * <p>Holds dimensions, content rect, lock count, wrap-mode, mipmap
 * flag, and a {@link NativeHandles.Slot} owning the native SkSurface
 * or SkImage. Concrete subclasses ({@link SkiaRTTexture},
 * {@link SkiaImageTexture}) wire this into the Prism
 * {@link Texture} / {@link com.sun.prism.RTTexture} interfaces.</p>
 *
 * <p>Lifecycle: native handles are released either by an explicit
 * {@link #dispose()} call (the fast path), or — if Java code leaks
 * the wrapper — by a {@link java.lang.ref.Cleaner} registered against
 * this object. CLAUDE.md "Native resource lifecycle" mandates this
 * dual path; explicit dispose stays the norm in hot paths.</p>
 */
abstract class SkiaTextureBase implements Texture {

    private final NativeHandles.Slot slot;
    // GPU bytes currently charged to SkiaTextureResourcePool for this texture, or
    // 0 if it isn't accounted (a non-owning wrap). A mutable holder (not a final
    // local) so an in-place resize can re-account the delta (H3) and so the destroy
    // callback — which must NOT capture `this` — can read the CURRENT charge. The
    // destroy frees it exactly once via getAndSet(0).
    private final AtomicLong accountedBytes;
    // Non-final so a D3D-swap-chain-backed presentable can update them
    // through {@link #setPhysicalSize} after IDXGISwapChain::ResizeBuffers.
    // For non-resizable textures (images, off-screen RTTs) these are
    // written once in the ctor and never touched again.
    private int physicalWidth;
    private int physicalHeight;
    private final WrapMode wrapMode;
    private final PixelFormat pixelFormat;
    private final boolean useMipmap;

    private int contentX;
    private int contentY;
    private int contentWidth;
    private int contentHeight;
    private boolean linearFiltering = true;
    private int lockCount;
    private boolean permanent;
    private int lastImageSerial;
    /** Monotonic "last used" stamp for LRU eviction (M18 18b); bumped on lock(). */
    volatile long lastUseTick;

    /**
     * @param destroy callback that frees the native resource owning
     *                the given handle. Must be a static method handle
     *                or a lambda that does <b>not</b> capture
     *                {@code this} (the Cleaner needs the wrapper to
     *                become unreachable). Suggested: pass a method
     *                reference like {@code NativeBridge::imageDestroy}.
     */
    SkiaTextureBase(long nativeHandle,
                    LongConsumer destroy,
                    PixelFormat pixelFormat,
                    int physicalWidth, int physicalHeight,
                    int contentWidth, int contentHeight,
                    WrapMode wrapMode,
                    boolean useMipmap,
                    boolean countBytes) {
        if (nativeHandle == 0L) {
            throw new IllegalArgumentException("nativeHandle must be non-zero");
        }
        // GPU memory accounting (M18). countBytes == false for a non-owning wrap
        // (the native surface is owned elsewhere — it must NOT inflate the budget,
        // M1). The byte count lives in a heap AtomicLong (the `acct` local captured
        // by the destroy lambda — NEVER `this`, so the Cleaner can still collect
        // this wrapper), so recordFree frees the CURRENT charge once, in a finally
        // even if the native destroy throws (M2), and an in-place resize can
        // re-account the delta via setPhysicalSize (H3).
        final long bytes = countBytes ? SkiaTextureResourcePool.INSTANCE
            .estimateTextureSize(physicalWidth, physicalHeight, pixelFormat) : 0L;
        this.accountedBytes = new AtomicLong(bytes);
        if (bytes > 0) {
            SkiaTextureResourcePool.INSTANCE.recordAllocated(bytes);
        }
        final AtomicLong acct = this.accountedBytes;
        final LongConsumer accountedDestroy = h -> {
            try {
                destroy.accept(h);
            } finally {
                long b = acct.getAndSet(0L);
                if (b > 0) {
                    SkiaTextureResourcePool.INSTANCE.recordFree(b);
                }
            }
        };
        this.slot = NativeHandles.register(this, nativeHandle, accountedDestroy);
        this.pixelFormat = pixelFormat;
        this.physicalWidth = physicalWidth;
        this.physicalHeight = physicalHeight;
        this.contentWidth = contentWidth;
        this.contentHeight = contentHeight;
        this.wrapMode = wrapMode;
        this.useMipmap = useMipmap;
    }

    /** Native handle for use by the bridge. {@code 0} once disposed. */
    public long getNativeHandle() {
        return slot.get();
    }

    @Override public PixelFormat getPixelFormat() { return pixelFormat; }

    @Override public int getPhysicalWidth()  { return physicalWidth; }
    @Override public int getPhysicalHeight() { return physicalHeight; }

    @Override public int getContentX()       { return contentX; }
    @Override public int getContentY()       { return contentY; }
    @Override public int getContentWidth()   { return contentWidth; }
    @Override public int getContentHeight()  { return contentHeight; }
    @Override public int getMaxContentWidth()  { return physicalWidth; }
    @Override public int getMaxContentHeight() { return physicalHeight; }

    @Override public void setContentWidth(int w)  { this.contentWidth = w; }
    @Override public void setContentHeight(int h) { this.contentHeight = h; }

    /**
     * Updates the cached physical dimensions after the underlying
     * native surface has been resized in place (currently only the
     * D3D swap-chain path via {@code IDXGISwapChain::ResizeBuffers}).
     * Not exposed on the {@link Texture} interface; callers hold a
     * concrete {@link SkiaTextureBase} reference.
     */
    protected void setPhysicalSize(int w, int h) {
        this.physicalWidth  = w;
        this.physicalHeight = h;
        if (this.contentWidth  > w) this.contentWidth  = w;
        if (this.contentHeight > h) this.contentHeight = h;
        // Re-account on in-place resize (H3): the D3D/GL ResizeBuffers path changes
        // the live surface size, so charge the pool the delta. Without this the
        // pool stays at the old (e.g. 1080p) size and recordFree later subtracts
        // that stale amount → a permanent positive drift in managedBytes across
        // the app's resize history. old==0 means not accounted (non-owning) or
        // already freed — skip.
        long old = accountedBytes.get();
        if (old > 0) {
            long now = SkiaTextureResourcePool.INSTANCE
                .estimateTextureSize(w, h, pixelFormat);
            if (now > 0 && accountedBytes.compareAndSet(old, now)) {
                long delta = now - old;
                if (delta > 0) {
                    SkiaTextureResourcePool.INSTANCE.recordAllocated(delta);
                } else if (delta < 0) {
                    SkiaTextureResourcePool.INSTANCE.recordFree(-delta);
                }
            }
        }
    }

    @Override public WrapMode getWrapMode()  { return wrapMode; }
    @Override public boolean getUseMipmap()  { return useMipmap; }

    @Override public boolean getLinearFiltering()      { return linearFiltering; }
    @Override public void setLinearFiltering(boolean l) { this.linearFiltering = l; }

    @Override public Texture getSharedTexture(WrapMode altMode) {
        if (altMode == WrapMode.CLAMP_NOT_NEEDED || altMode == wrapMode) {
            lock();
            return this;
        }
        return null;
    }

    @Override public void lock()             { lockCount++; lastUseTick = SkiaTextureResourcePool.nextUseTick(); }
    @Override public void unlock()           { if (lockCount > 0) lockCount--; }
    @Override public boolean isLocked()      { return lockCount > 0; }
    @Override public int getLockCount()      { return lockCount; }
    @Override public void assertLocked() {
        if (lockCount <= 0 && !permanent) {
            throw new IllegalStateException("Texture is not locked");
        }
    }

    @Override public void makePermanent()    { this.permanent = true; }
    public boolean isPermanent()             { return permanent; }

    @Override public void contentsUseful()    { /* TODO atlas eviction policy */ }
    @Override public void contentsNotUseful() { /* TODO atlas eviction policy */ }

    @Override public boolean isSurfaceLost() { return slot.isClosed(); }

    @Override public int getLastImageSerial()        { return lastImageSerial; }
    @Override public void setLastImageSerial(int s)  { this.lastImageSerial = s; }

    @Override public void dispose() {
        slot.close();
    }
}
