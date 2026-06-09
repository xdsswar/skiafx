package com.sun.prism.skia.impl;

import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Tracks native handle lifecycles for Skia-backed Java wrappers.
 *
 * <p>The handle (a {@code uintptr_t} value passed across FFM) is kept
 * in an {@link AtomicLong} owned by a {@link Slot}. The slot is
 * referenced both by the wrapper (via a field) and by a registered
 * {@link Cleaner} action — but the Cleaner action does <b>not</b>
 * reference the wrapper itself, so the wrapper can be collected once
 * Java code drops its references. When that happens the Cleaner runs
 * the destroy callback against the slot's handle.</p>
 *
 * <p>Explicit {@link Slot#close} (called from {@code dispose()}) is the
 * fast path; the Cleaner is a safety net for leaks. Both atomically
 * compare-and-set the handle to zero before invoking the destroy
 * callback, so double-frees are impossible.</p>
 *
 * <p>See CLAUDE.md "Native resource lifecycle".</p>
 */
public final class NativeHandles {

    private static final Cleaner CLEANER = Cleaner.create();

    /**
     * The owned native handle plus the destroy callback. Wrappers
     * keep this as a field; the Cleaner keeps a reference too. The
     * wrapper is GC-eligible without disturbing the slot.
     */
    public static final class Slot {
        private final AtomicLong handle;
        private final LongConsumer destroy;
        private volatile Cleaner.Cleanable cleanable;

        Slot(long handle, LongConsumer destroy) {
            this.handle = new AtomicLong(handle);
            this.destroy = destroy;
        }

        public long get() { return handle.get(); }
        public boolean isClosed() { return handle.get() == 0L; }

        /** Explicit release. Idempotent. Safe to call from any thread. */
        public void close() {
            long h = handle.getAndSet(0L);
            if (h != 0L) {
                destroy.accept(h);
            }
            // Detach the cleaner so it doesn't run later for nothing.
            Cleaner.Cleanable c = cleanable;
            if (c != null) {
                cleanable = null;
                c.clean(); // idempotent in our action; runs reap path
            }
        }

        // The Cleaner's runnable. Static so it cannot capture an
        // enclosing `this`. Closes over the slot via parameter binding.
        private static Runnable cleaningAction(Slot slot) {
            return () -> {
                long h = slot.handle.getAndSet(0L);
                if (h != 0L) {
                    // The Cleaner runs on a daemon thread, but the destroy
                    // callbacks free GPU-affine resources (Ganesh SkSurface /
                    // SkImage drop, wglMakeCurrent + FBO teardown, D3D swap-chain
                    // destroy) that are confined to the render thread — Skia's
                    // GrDirectContext and the GL/D3D context are single-threaded.
                    // Freeing them on the daemon thread corrupts the GPU heap /
                    // steals the GL context from the render thread. So DON'T free
                    // here: enqueue and let the render thread drain it
                    // (drainDeferred(), called each pulse). The explicit close()
                    // path already runs on the render thread, so it frees inline
                    // and this safety-net path only fires for a leaked+GC'd
                    // wrapper. (BUG-4)
                    final LongConsumer d = slot.destroy;
                    DEFERRED.add(() -> d.accept(h));
                }
            };
        }
    }

    private NativeHandles() {}

    /**
     * GPU-handle frees enqueued by the Cleaner's daemon thread, drained on the
     * render thread by {@link #drainDeferred()}. Non-blocking add from the
     * Cleaner; poll from the render thread.
     */
    private static final java.util.Queue<Runnable> DEFERRED =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * Runs any native frees that the Cleaner deferred because it fired on its
     * daemon thread. <b>Must be called on the render thread</b> — the destroy
     * callbacks touch the thread-confined {@code GrDirectContext} and the
     * GL/D3D context. Called once per pulse from the Skia painter, alongside
     * Prism's {@code Disposer.cleanUp()}. Cheap no-op when the queue is empty
     * (the common case: explicit dispose frees inline, so nothing is deferred).
     */
    /**
     * Enqueues {@code free} to run on the render thread at the next
     * {@link #drainDeferred()} (once per pulse). For any wrapper whose Cleaner
     * safety-net fires on the daemon thread but whose native frees touch the
     * thread-confined {@code GrDirectContext} / GL-D3D context — e.g.
     * {@code SkiaMediaTexture}'s GPU {@code SkImage} / D3D11 interop teardown.
     * The explicit {@code dispose()} path runs on the render thread and should
     * free inline; this is only for the leaked-and-GC'd safety net. (BUG-1)
     */
    public static void deferOnRenderThread(Runnable free) {
        if (free != null) {
            DEFERRED.add(free);
        }
    }

    public static void drainDeferred() {
        Runnable r;
        while ((r = DEFERRED.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                System.getLogger(NativeHandles.class.getName()).log(
                        System.Logger.Level.WARNING,
                        "Deferred native handle free failed", t);
            }
        }
    }

    /**
     * Register a {@link Slot} owning {@code handle} on behalf of
     * {@code owner}. The slot's destroy callback is invoked exactly
     * once — either from {@link Slot#close()} or from a Cleaner
     * triggered by {@code owner} becoming unreachable.
     *
     * @param owner    the wrapper instance whose lifecycle gates the
     *                 cleaner; must not be referenced from {@code destroy}
     * @param handle   non-zero native handle
     * @param destroy  invoked with the handle exactly once
     */
    public static Slot register(Object owner, long handle, LongConsumer destroy) {
        if (handle == 0L) {
            throw new IllegalArgumentException("handle must be non-zero");
        }
        Slot slot = new Slot(handle, destroy);
        slot.cleanable = CLEANER.register(owner, Slot.cleaningAction(slot));
        return slot;
    }
}
