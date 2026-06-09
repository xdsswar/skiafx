package com.sun.prism.skia;

import com.sun.prism.PixelFormat;
import com.sun.prism.impl.Disposer;
import com.sun.prism.impl.ManagedResource;
import com.sun.prism.impl.TextureResourcePool;
import com.sun.prism.skia.impl.NativeHandles;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Skia GPU texture resource pool — real byte accounting + a soft/hard budget
 * (CLAUDE.md "Memory Management"). Replaces the Phase-1 no-op stub.
 *
 * <p><b>Phase 18a (this class):</b> accurate accounting of standalone textures
 * and render targets (everything that flows through {@link SkiaTextureBase}),
 * configurable budgets from {@code javafx.gpu.*} system properties, and
 * reclaim-on-pressure: when an allocation would cross the hard cap we first drain
 * already-disposed natives (Prism {@link Disposer} + the deferred-free queue),
 * and if still over we log a rate-limited warning and <b>allow</b> the allocation
 * — the hard cap degrades, it never crashes (CLAUDE.md). VRAM is now bounded and
 * observable ({@code -Djavafx.gpu.memDump=true} dumps on shutdown).</p>
 *
 * <p><b>Phase 18b (follow-up):</b> active LRU eviction of unlocked render targets
 * plugs into {@link #prepareForAllocation} after the reclaim step.</p>
 *
 * <p>Accounting is exact across both release paths: {@link SkiaTextureBase} calls
 * {@link #recordAllocated} at construction and wraps its native destroy callback
 * so {@link #recordFree} fires on the single native-free point (explicit
 * {@code dispose()} OR the leaked-wrapper Cleaner). Media textures
 * ({@code SkiaMediaTexture}, separate Cleaner) are not yet counted — a tracked
 * follow-up.</p>
 */
public final class SkiaTextureResourcePool implements TextureResourcePool<Object> {

    public static final SkiaTextureResourcePool INSTANCE = new SkiaTextureResourcePool();

    private static final Logger LOG =
        System.getLogger(SkiaTextureResourcePool.class.getName());

    /** Hard cap — allocations past it trigger reclaim/eviction, never a crash. */
    private final long maxBytes;
    /** Soft target — 18b starts evicting once {@code managed > target}. */
    private final long origTargetBytes;
    private volatile long targetBytes;

    private final AtomicLong managedBytes = new AtomicLong();
    private final AtomicLong peakBytes = new AtomicLong();
    private final AtomicLong overBudgetWarns = new AtomicLong();
    private final AtomicLong evictionCount = new AtomicLong();
    private final boolean memDump = Boolean.getBoolean("javafx.gpu.memDump");

    /** Monotonic clock for LRU ordering (18b); bumped on every texture lock(). */
    private static final AtomicLong USE_TICK = new AtomicLong();
    static long nextUseTick() { return USE_TICK.incrementAndGet(); }

    /**
     * Active eviction of unlocked render targets (18b). OFF by default: evicting a
     * live (but unlocked) RT forces its holder onto a re-render fallback, which is
     * correct only for holders that re-check {@code isSurfaceLost()} (CacheFilter,
     * the effect ImagePool do; not every path is audited). Opt in with
     * {@code -Djavafx.gpu.evictUnlockedRTs=true}. When off, the budget still
     * accounts + reclaims dead resources (18a) and degrades gracefully past the cap.
     */
    private final boolean evictRTs = Boolean.getBoolean("javafx.gpu.evictUnlockedRTs");
    /** Weak registry of evictable RTs — weak keys so tracking never pins a leaked
     *  RT (the Cleaner still reclaims it). Only populated when evictRTs is on. */
    private final Set<SkiaRTTexture> rtRegistry =
        Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private SkiaTextureResourcePool() {
        this.maxBytes = parseBytes("javafx.gpu.textureBudget", 1L << 30); // 1 GiB
        long t = parseBytes("javafx.gpu.textureTarget", -1L);
        this.origTargetBytes = (t > 0 ? Math.min(t, maxBytes)
                                      : (long) (maxBytes * 0.75));
        this.targetBytes = origTargetBytes;
    }

    /** Parses a byte size that may carry a {@code k}/{@code m}/{@code g} suffix. */
    private static long parseBytes(String prop, long defaultBytes) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) {
            return defaultBytes;
        }
        v = v.trim().toLowerCase();
        long mult = 1L;
        char last = v.charAt(v.length() - 1);
        if (last == 'k' || last == 'm' || last == 'g') {
            mult = (last == 'k') ? 1024L : (last == 'm') ? 1024L * 1024 : 1024L * 1024 * 1024;
            v = v.substring(0, v.length() - 1).trim();
        }
        try {
            long n = Long.parseLong(v);
            return (n > 0) ? n * mult : defaultBytes;
        } catch (NumberFormatException e) {
            return defaultBytes;
        }
    }

    // ---- Accounting --------------------------------------------------------

    @Override public long used()       { return managedBytes.get(); }
    @Override public long managed()    { return managedBytes.get(); }
    @Override public long max()        { return maxBytes; }
    @Override public long origTarget() { return origTargetBytes; }
    @Override public long target()     { return targetBytes; }

    @Override public void setTarget(long newTarget) {
        this.targetBytes = Math.min(Math.max(0L, newTarget), maxBytes);
    }

    /** Per-resource size is tracked by {@link SkiaTextureBase}, not here. */
    @Override public long size(Object resource) { return 0L; }

    @Override public void recordAllocated(long size) {
        if (size <= 0) return;
        long m = managedBytes.addAndGet(size);
        peakBytes.accumulateAndGet(m, Math::max);
    }

    @Override public void recordFree(long size) {
        if (size <= 0) return;
        managedBytes.addAndGet(-size);
    }

    // ---- Budget enforcement ------------------------------------------------

    @Override public boolean isManagerThread() {
        // Texture create/dispose are render-thread-confined in the Skia pipeline.
        return true;
    }

    @Override
    public void freeDisposalRequestedAndCheckResources(boolean forgiveStaleLocks) {
        // Drain native frees the Cleaner deferred to the render thread. Called on
        // the render thread (the manager thread).
        NativeHandles.drainDeferred();
    }

    @Override
    public boolean prepareForAllocation(long size) {
        if (managedBytes.get() + size <= maxBytes) {
            return true;
        }
        // Over the hard cap: reclaim already-dead resources first (cheap, common
        // case clears it — disposed-but-not-yet-freed textures).
        Disposer.cleanUp();
        NativeHandles.drainDeferred();
        if (managedBytes.get() + size <= maxBytes) {
            return true;
        }
        // 18b (opt-in): evict unlocked render targets LRU-first to get back under
        // the soft target, freeing headroom for this allocation.
        if (evictRTs) {
            evictUnlockedRTs(size);
            if (managedBytes.get() + size <= maxBytes) {
                return true;
            }
        }
        // Still over after reclaim/eviction: the hard cap DEGRADES, never crashes — allow
        // the allocation but warn (rate-limited: first few, then powers of two).
        long n = overBudgetWarns.incrementAndGet();
        if (n <= 4 || Long.bitCount(n) == 1) {
            LOG.log(Level.WARNING,
                "GPU texture budget exceeded (allowing, no crash): managed="
                + managedBytes.get() + " + " + size + " > max=" + maxBytes
                + "  [warn #" + n + "; -Djavafx.gpu.textureBudget to raise]");
        }
        return true;
    }

    /**
     * Registers a normal off-screen RT as an eviction candidate (18b). No-op
     * unless {@code -Djavafx.gpu.evictUnlockedRTs=true}. Called from
     * {@link SkiaRTTexture}'s public ctor only — NOT for presentables / non-owning
     * wraps, which must never be evicted.
     */
    void trackRT(SkiaRTTexture rt) {
        if (evictRTs && rt != null) {
            rtRegistry.add(rt);
        }
    }

    /**
     * Disposes unlocked, non-permanent, still-live RTs LRU-first until managed
     * bytes (plus the pending {@code size}) fit under the soft target, or no more
     * candidates remain. Render-thread only. Disposed RTs stay weakly registered
     * until GC; a later pass skips them via {@code isSurfaceLost()}.
     */
    private void evictUnlockedRTs(long size) {
        List<SkiaRTTexture> candidates = new ArrayList<>();
        synchronized (rtRegistry) {
            for (SkiaRTTexture rt : rtRegistry) {
                if (rt != null && !rt.isPermanent()
                        && rt.getLockCount() == 0 && !rt.isSurfaceLost()) {
                    candidates.add(rt);
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        candidates.sort(Comparator.comparingLong(rt -> rt.lastUseTick));
        for (SkiaRTTexture rt : candidates) {
            if (managedBytes.get() + size <= targetBytes) {
                break;
            }
            if (rt.getLockCount() != 0 || rt.isSurfaceLost()) {
                continue; // raced to locked/disposed since the snapshot
            }
            rt.dispose(); // frees native SkSurface → accountedDestroy → recordFree
            evictionCount.incrementAndGet();
        }
    }

    // ManagedResource integration is unused — Skia textures account directly via
    // recordAllocated/recordFree (see SkiaTextureBase), not the WeakLinkedList.
    @Override public void resourceManaged(ManagedResource<Object> resource) { }
    @Override public void resourceFreed(ManagedResource<Object> resource)   { }

    @Override public long estimateTextureSize(int width, int height, PixelFormat format) {
        return (long) width * height * format.getBytesPerPixelUnit();
    }
    @Override public long estimateRTTextureSize(int width, int height, boolean hasDepth) {
        return (long) width * height * 4 + (hasDepth ? (long) width * height * 4 : 0);
    }

    // ---- Observability -----------------------------------------------------

    /** True when {@code -Djavafx.gpu.memDump=true}. */
    public boolean isMemDumpEnabled() { return memDump; }

    /** Dumps the current/peak managed GPU bytes vs the budget. Called on shutdown
     *  when {@code -Djavafx.gpu.memDump=true}. */
    public void dumpMemory() {
        LOG.log(Level.INFO, String.format(
            "[gpu.mem] textures managed=%.1f MiB  peak=%.1f MiB  target=%.0f MiB  "
            + "max=%.0f MiB  evictions=%d  over-budget-warns=%d",
            managedBytes.get() / 1048576.0, peakBytes.get() / 1048576.0,
            targetBytes / 1048576.0, maxBytes / 1048576.0,
            evictionCount.get(), overBudgetWarns.get()));
    }
}
