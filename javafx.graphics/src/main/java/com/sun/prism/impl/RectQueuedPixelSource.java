/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.prism.impl;

import com.sun.glass.ui.Pixels;
import com.sun.javafx.geom.Rectangle;

/**
 * A {@link QueuedPixelSource} that carries a dirty rectangle alongside each
 * enqueued {@link Pixels}, for partial window uploads on the readback
 * present tier.
 *
 * <p>Correctness invariant: the rect attached to an upload must cover every
 * pixel that changed on screen since the <em>previously uploaded</em> frame
 * — not just since the previously <em>rendered</em> frame. The queue drops
 * frames in two ways (a new enqueue replacing a not-yet-consumed one, and
 * {@link #skipLatestPixels()} when the view went invalid), and a dropped
 * frame's dirty area was never shown. This class accumulates those areas in
 * {@code pending} and only resets it when a frame is actually handed to the
 * consumer, so the next real upload always covers the gap.</p>
 *
 * <p>All state is guarded by this object's monitor, same as the superclass
 * (producer = render thread, consumer = event thread).</p>
 */
public final class RectQueuedPixelSource extends QueuedPixelSource {

    /** Accumulated dirty area not yet handed to the consumer. */
    private final Rectangle pending = new Rectangle();
    private boolean pendingValid;   // false + !pendingFull => nothing pending
    private boolean pendingFull;    // a full-frame upload is owed

    /** Rect captured for the Pixels most recently returned by
     *  {@link #getLatestPixels()}; {@code null} means "upload everything". */
    private final Rectangle consumed = new Rectangle();
    private boolean consumedPartial;

    public RectQueuedPixelSource(boolean useDirectBuffers) {
        super(useDirectBuffers);
    }

    /**
     * Enqueues {@code pixels} whose changed area since the previous
     * <em>rendered</em> frame is {@code dirty} ({@code null} = the whole
     * frame changed / unknown). Any dirty area carried from dropped frames
     * is unioned in automatically.
     */
    public synchronized void enqueuePixels(Pixels pixels, Rectangle dirty) {
        if (dirty == null) {
            pendingFull = true;
        } else if (!pendingFull) {
            if (pendingValid) {
                pending.add(dirty);
            } else {
                pending.setBounds(dirty);
                pendingValid = true;
            }
        }
        super.enqueuePixels(pixels);
    }

    @Override
    public synchronized void enqueuePixels(Pixels pixels) {
        // Rect-less enqueue = caller didn't say what changed: owe a full upload.
        pendingFull = true;
        super.enqueuePixels(pixels);
    }

    @Override
    public synchronized Pixels getLatestPixels() {
        Pixels p = super.getLatestPixels();
        if (p != null) {
            consumedPartial = !pendingFull && pendingValid;
            if (consumedPartial) {
                consumed.setBounds(pending);
            }
            pendingValid = false;
            pendingFull = false;
        }
        return p;
    }

    /**
     * The upload rect for the {@code Pixels} just returned by
     * {@link #getLatestPixels()}, or {@code null} for a full upload. Valid
     * until the next {@code getLatestPixels()} call; do not retain.
     */
    public synchronized Rectangle getConsumedRect() {
        return consumedPartial ? consumed : null;
    }

    // skipLatestPixels() intentionally NOT overridden: the skipped frame's
    // dirty area stays in `pending` (it was added at enqueue time and is
    // only cleared on consumption), so the next upload covers it.
}
