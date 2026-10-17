package com.sun.prism.skia;

import com.sun.javafx.geom.BaseBounds;
import com.sun.javafx.geom.Ellipse2D;
import com.sun.javafx.geom.RoundRectangle2D;
import com.sun.javafx.geom.Shape;
import com.sun.prism.Graphics;
import com.sun.prism.shape.ShapeRep;

/**
 * Skia-backed {@link ShapeRep}. Stateless; dispatches on the geometry type
 * the same way upstream's {@code BasicEllipseRep} / {@code BasicRoundRectRep}
 * do, so ellipses and (rounded) rectangles reach Skia's analytic
 * {@code drawOval} / {@code drawRRect} ops instead of the generic
 * {@code SkPath} renderer.
 *
 * <p>Why it matters: routing a {@code Circle} or rounded {@code Rectangle}
 * through {@link Graphics#fill(Shape)} encodes cubic curves into an
 * {@code SkPath}, which Skia cannot recognise as an oval/rrect and must
 * tessellate or mask on every frame. The analytic ops are a single cheap
 * coverage shader (and stay exact under rotation/scale). Everything else
 * (arcs, general paths) still goes through the path renderer.</p>
 */
public final class SkiaShapeRep implements ShapeRep {

    /** Reusable singleton — the impl is stateless. */
    public static final SkiaShapeRep INSTANCE = new SkiaShapeRep();

    private SkiaShapeRep() {}

    @Override public boolean is3DCapable() { return false; }
    @Override public void invalidate(InvalidationType type) { /* no cache */ }

    @Override public void fill(Graphics g, Shape shape, BaseBounds bounds) {
        if (shape instanceof Ellipse2D e) {
            g.fillEllipse(e.x, e.y, e.width, e.height);
        } else if (shape instanceof RoundRectangle2D r) {
            if (r.width <= 0 || r.height <= 0) return;
            // A zero arc is a plain rect; fillRoundRect keeps float
            // geometry (fillRect snaps to whole units) and Skia reduces
            // a zero-radius rrect to its rect fast path.
            g.fillRoundRect(r.x, r.y, r.width, r.height,
                Math.max(0f, r.arcWidth), Math.max(0f, r.arcHeight));
        } else {
            g.fill(shape);
        }
    }

    @Override public void draw(Graphics g, Shape shape, BaseBounds bounds) {
        if (shape instanceof Ellipse2D e) {
            g.drawEllipse(e.x, e.y, e.width, e.height);
        } else if (shape instanceof RoundRectangle2D r) {
            if (r.arcWidth > 0 && r.arcHeight > 0) {
                g.drawRoundRect(r.x, r.y, r.width, r.height, r.arcWidth, r.arcHeight);
            } else {
                g.drawRect(r.x, r.y, r.width, r.height);
            }
        } else {
            g.draw(shape);
        }
    }

    @Override public void dispose() { /* nothing to release */ }
}
