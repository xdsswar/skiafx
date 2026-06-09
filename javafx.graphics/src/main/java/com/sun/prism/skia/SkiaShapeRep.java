package com.sun.prism.skia;

import com.sun.javafx.geom.BaseBounds;
import com.sun.javafx.geom.Shape;
import com.sun.prism.Graphics;
import com.sun.prism.shape.ShapeRep;

/**
 * Skia-backed {@link ShapeRep}. Phase-1 implementation is stateless —
 * each {@link #fill}/{@link #draw} hands the shape directly to
 * {@link Graphics#fill}/{@link Graphics#draw}, which routes through
 * Skia's {@code SkPath} draw path.
 *
 * <p>Prism's design lets a {@code ShapeRep} cache geometry for repeated
 * rendering of the same shape; we don't yet exercise that. If profiling
 * reveals path encoding as a hotspot we can add an SkPath handle here
 * keyed off the source {@link Shape}'s identity.</p>
 */
public final class SkiaShapeRep implements ShapeRep {

    /** Reusable singleton — the impl is stateless. */
    public static final SkiaShapeRep INSTANCE = new SkiaShapeRep();

    private SkiaShapeRep() {}

    @Override public boolean is3DCapable() { return false; }
    @Override public void invalidate(InvalidationType type) { /* no cache */ }

    @Override public void fill(Graphics g, Shape shape, BaseBounds bounds) {
        g.fill(shape);
    }

    @Override public void draw(Graphics g, Shape shape, BaseBounds bounds) {
        g.draw(shape);
    }

    @Override public void dispose() { /* nothing to release */ }
}
