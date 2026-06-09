/*
 * Hint channel: NGMediaView publishes its current on-screen rect here;
 * the producer side (mfwrapper) reads it through the native export of
 * the same name to downscale the GPU output texture to what the view
 * actually needs. Cheap to call every render — two atomic stores.
 */
package com.sun.media.jfxmediaimpl;

public final class MediaTargetSize {
    private MediaTargetSize() {}

    private static native void nativeSet(int width, int height);

    private static int lastW = -1;
    private static int lastH = -1;
    private static long lastChangeNanos = 0L;
    // Wait this long after the last reported size change before
    // publishing to native code. During a window-resize drag the user
    // moves through many sizes a few ms apart; without debouncing the
    // producer would rebuild its VideoProcessor on every bucket cross
    // (each rebuild ~50-200ms) and stall the decoder thread. With
    // debouncing we only publish once the user has stopped dragging.
    private static final long QUIET_NANOS = 200_000_000L; // 200ms

    /** Publish the MediaView's current on-screen size to native code.
     *  Debounced — the actual native set happens once the size has
     *  been stable for {@link #QUIET_NANOS}. Cheap on every call. */
    public static void update(int width, int height) {
        // Floor the published hint at 144p. The native pick_output_size
        // also enforces a higher floor (720p) for quality reasons, but
        // we don't even want to publish tiny transient sizes — when
        // the window collapses to a few px during a fast drag, that
        // can interact badly with downstream allocations.
        final int MIN_W = 256, MIN_H = 144;
        if (width  < MIN_W) width  = MIN_W;
        if (height < MIN_H) height = MIN_H;
        long now = System.nanoTime();
        if (width != lastW || height != lastH) {
            // Size moved — reset the quiet timer; don't publish yet.
            lastW = width;
            lastH = height;
            lastChangeNanos = now;
            return;
        }
        if (lastChangeNanos == 0L) return; // already published
        if (now - lastChangeNanos < QUIET_NANOS) return;
        lastChangeNanos = 0L;
        try {
            nativeSet(width, height);
        } catch (UnsatisfiedLinkError ule) {
            // Native lib not loaded yet (very early startup) — fine.
        }
    }
}
