/*
 * skia-fx: CPU-only automatic subtree bitmap caching support.
 *
 * Process-global cap on the number of simultaneously auto-attached
 * CacheFilters (see NGNode.maybeAutoCache). Auto-caching only ever runs on
 * the software-raster path, so this class is only touched there; on the GPU
 * path NGNode.maybeAutoCache returns before reaching it. Keeping the count
 * here (rather than as NGNode statics) isolates the policy and keeps the
 * acquire/release contract obvious. New file for the Skia pipeline. See
 * CLAUDE.md.
 */
package com.sun.javafx.sg.prism;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounds how many subtree bitmap caches the pipeline may auto-attach at once.
 *
 * <p>Auto-caching trades RAM/VRAM (one RTTexture per cached subtree) for CPU
 * rasterization time. Without a ceiling, a scene full of large animating
 * groups could attach an unbounded number of cache textures. When the cap is
 * reached {@link #tryAcquire()} returns {@code false} and the candidate node
 * simply renders uncached — the safe, full-fidelity default — until a slot
 * frees up.</p>
 */
final class AutoCacheRegistry {

    /** Max simultaneous auto-caches; override with {@code -Djavafx.autocache.max}. */
    private static final int MAX = Math.max(0,
            Integer.getInteger("javafx.autocache.max", 8));

    private static final AtomicInteger ACTIVE = new AtomicInteger(0);

    private AutoCacheRegistry() {}

    /**
     * Reserve a cache slot if one is available.
     *
     * @return {@code true} if a slot was acquired (caller must later call
     *         {@link #release()}); {@code false} if the cap is reached.
     */
    static boolean tryAcquire() {
        while (true) {
            int cur = ACTIVE.get();
            if (cur >= MAX) {
                return false;
            }
            if (ACTIVE.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    /** Release a slot previously reserved by {@link #tryAcquire()}. */
    static void release() {
        // Floor at 0 so a stray release can never make the counter negative.
        ACTIVE.updateAndGet(n -> n > 0 ? n - 1 : 0);
    }
}
