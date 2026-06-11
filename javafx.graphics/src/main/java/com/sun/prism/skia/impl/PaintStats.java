/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.prism.skia.impl;

/**
 * Always-on, near-zero-cost paint telemetry published by the Quantum
 * painter ({@code PresentingPainter}), refreshed about once a second.
 *
 * <p>Lives in this package (rather than {@code com.sun.javafx.tk.quantum})
 * so benchmark harnesses that already {@code --add-exports} this package
 * for {@link Copies} can read paint cost without a second export. The
 * painter writes plain volatile doubles from the render thread; readers
 * (sample apps, benches) poll from any thread.</p>
 *
 * <p>All values are {@code 0} until the first one-second window with at
 * least one paint elapses, and go stale while the scene is idle (the
 * painter skips paints entirely when nothing changed).</p>
 */
public final class PaintStats {

    /** Scene paints per second over the last measured window. */
    public static volatile double LAST_PAINTS_PER_SEC;

    /** Average {@code paintImpl} duration in milliseconds over the last
     *  measured window. */
    public static volatile double LAST_PAINT_AVG_MS;

    private PaintStats() { /* no instances */ }
}
