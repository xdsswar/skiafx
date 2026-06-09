package com.sun.prism.skia;

import com.sun.javafx.geom.Path2D;
import com.sun.javafx.geom.PathIterator;
import com.sun.prism.BasicStroke;
import com.sun.prism.Graphics;
import com.sun.prism.RTTexture;
import com.sun.prism.Texture;
import com.sun.prism.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link SkiaGraphics#fill(com.sun.javafx.geom.Shape)} and
 * {@link SkiaGraphics#draw(com.sun.javafx.geom.Shape)} on real
 * {@link Path2D} instances. Drives the
 * {@code PathIterator → PathEncoder → surface_*_path → SkPath/SkCanvas}
 * code path end-to-end.
 */
@EnabledIfSystemProperty(named = "openjfx.skia.runNativeTests", matches = "true")
class SkiaGraphicsPathTest {

    private static final SkiaResourceFactory FACTORY = new SkiaResourceFactory(null);
    private static final Color RED   = new Color(1f, 0f, 0f, 1f);
    private static final Color BLACK = new Color(0f, 0f, 0f, 1f);

    private RTTexture makeRT(int w, int h) {
        return FACTORY.createRTTexture(w, h, Texture.WrapMode.CLAMP_TO_EDGE);
    }

    @Test
    void fillTriangle() {
        // Equilateral-ish triangle with vertices at the buffer corners.
        Path2D triangle = new Path2D(PathIterator.WIND_NON_ZERO);
        triangle.moveTo(20, 4);
        triangle.lineTo(36, 36);
        triangle.lineTo(4,  36);
        triangle.closePath();

        RTTexture rt = makeRT(40, 40);
        try {
            Graphics g = rt.createGraphics();
            g.clear(BLACK);
            g.setPaint(RED);
            g.fill(triangle);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Centroid is around (20, 25) — inside the triangle.
            assertThat(px[25 * 40 + 20] & 0xFF).isGreaterThan(200);
            // Top corner above the apex — outside.
            assertThat(px[1 * 40 + 1] & 0xFF).isLessThan(20);
        } finally { rt.dispose(); }
    }

    @Test
    void strokeTriangleHasHollowCenter() {
        Path2D triangle = new Path2D(PathIterator.WIND_NON_ZERO);
        triangle.moveTo(20, 4);
        triangle.lineTo(36, 36);
        triangle.lineTo(4,  36);
        triangle.closePath();

        RTTexture rt = makeRT(40, 40);
        try {
            Graphics g = rt.createGraphics();
            g.clear(BLACK);
            g.setPaint(RED);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f));
            g.draw(triangle);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Centroid (~20,25) is inside but stroke is hollow → black.
            assertThat(px[25 * 40 + 20] & 0xFF).isLessThan(20);
            // A point on the bottom edge near the middle should be on the stroke.
            assertThat(px[36 * 40 + 20] & 0xFF).isGreaterThan(150);
        } finally { rt.dispose(); }
    }

    @Test
    void fillCubicBezierCurve() {
        // S-curve "ribbon" — not a simple shape, but the curve+close
        // shouldn't crash and the start/end pixels should be reachable.
        Path2D ribbon = new Path2D(PathIterator.WIND_EVEN_ODD);
        ribbon.moveTo(2,  10);
        ribbon.curveTo(15, 0, 25, 30, 38, 10);  // top edge
        ribbon.lineTo(38, 30);
        ribbon.curveTo(25, 50, 15, 20, 2,  30); // bottom edge
        ribbon.closePath();

        RTTexture rt = makeRT(40, 40);
        try {
            Graphics g = rt.createGraphics();
            g.clear(BLACK);
            g.setPaint(RED);
            g.fill(ribbon);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Center band should have at least some red pixels.
            int redHits = 0;
            for (int y = 15; y < 25; y++) {
                for (int x = 5; x < 35; x++) {
                    if ((px[y * 40 + x] & 0xFF) > 200) redHits++;
                }
            }
            assertThat(redHits).isGreaterThan(50);
        } finally { rt.dispose(); }
    }

    @Test
    void evenOddFillRuleProducesHole() {
        // Outer rect with an inner rect, both clockwise.
        // Even-odd: inside outer & inside inner → exits twice → not filled (hole).
        // Non-zero: same direction → still filled.
        Path2D withHole = new Path2D(PathIterator.WIND_EVEN_ODD);
        withHole.moveTo(4,  4);
        withHole.lineTo(36, 4);
        withHole.lineTo(36, 36);
        withHole.lineTo(4,  36);
        withHole.closePath();
        withHole.moveTo(14, 14);
        withHole.lineTo(26, 14);
        withHole.lineTo(26, 26);
        withHole.lineTo(14, 26);
        withHole.closePath();

        RTTexture rt = makeRT(40, 40);
        try {
            Graphics g = rt.createGraphics();
            g.clear(BLACK);
            g.setPaint(RED);
            g.fill(withHole);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Between outer and inner: red.
            assertThat(px[8 * 40 + 8] & 0xFF).isGreaterThan(200);
            // Inside inner → hole → black.
            assertThat(px[20 * 40 + 20] & 0xFF).isLessThan(20);
        } finally { rt.dispose(); }
    }
}
