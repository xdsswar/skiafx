package com.sun.prism.skia;

import com.sun.prism.BasicStroke;
import com.sun.prism.Graphics;
import com.sun.prism.RTTexture;
import com.sun.prism.Texture;
import com.sun.prism.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SkiaGraphics} shape primitives. Each test draws into
 * a small RTTexture, reads pixels back, and asserts on a hand-picked
 * sample point that the shape rendered with the right color in the
 * right place. The full anti-aliased pixel content isn't verified
 * exactly — that lives in the future pixel-diff suite.
 *
 * <p>Pixel encoding: RGBA8888 little-endian — channel 0 (low byte) is
 * R, then G, B, A in ascending byte positions.</p>
 */
@EnabledIfSystemProperty(named = "openjfx.skia.runNativeTests", matches = "true")
class SkiaGraphicsShapesTest {

    private static final SkiaResourceFactory FACTORY = new SkiaResourceFactory(null);
    private static final Color RED   = new Color(1f, 0f, 0f, 1f);
    private static final Color BLACK = new Color(0f, 0f, 0f, 1f);

    private RTTexture makeRT(int w, int h) {
        return FACTORY.createRTTexture(w, h, Texture.WrapMode.CLAMP_TO_EDGE);
    }

    @Test
    void fillRoundRectRendersInsideButNotOutside() {
        RTTexture rt = makeRT(40, 40);
        try {
            Graphics g = rt.createGraphics();
            g.clear(BLACK);
            g.setPaint(RED);
            g.fillRoundRect(5, 5, 30, 30, 10, 10);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Center should be solid red.
            int center = px[20 * 40 + 20];
            assertThat(center & 0xFF).isGreaterThan(200);
            // (1,1) is well outside the rect → black.
            int outside = px[1 * 40 + 1];
            assertThat(outside & 0xFF).isLessThan(20);
        } finally { rt.dispose(); }
    }

    @Test
    void fillEllipseLeavesCornersUntouched() {
        RTTexture rt = makeRT(40, 40);
        try {
            Graphics g = rt.createGraphics();
            g.clear(BLACK);
            g.setPaint(RED);
            g.fillEllipse(0, 0, 40, 40);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Center → red; (0,0) corner → outside ellipse → black.
            int center = px[20 * 40 + 20];
            int corner = px[0];
            assertThat(center & 0xFF).isGreaterThan(200);
            assertThat(corner & 0xFF).isLessThan(20);
        } finally { rt.dispose(); }
    }

    @Test
    void drawRectStrokeIsHollow() {
        RTTexture rt = makeRT(20, 20);
        try {
            Graphics g = rt.createGraphics();
            g.clear(BLACK);
            g.setPaint(RED);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f));
            g.drawRect(5, 5, 10, 10);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Top edge (10,5) should be red (on the stroke).
            int onEdge = px[5 * 20 + 10];
            // Center (10,10) is *inside* the rect — empty (black).
            int inside = px[10 * 20 + 10];
            assertThat(onEdge & 0xFF).isGreaterThan(150);
            assertThat(inside & 0xFF).isLessThan(20);
        } finally { rt.dispose(); }
    }

    @Test
    void drawLineHitsExpectedPixel() {
        RTTexture rt = makeRT(20, 20);
        try {
            Graphics g = rt.createGraphics();
            g.clear(BLACK);
            g.setPaint(RED);
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f));
            g.drawLine(2, 10, 18, 10);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // On the line.
            assertThat(px[10 * 20 + 10] & 0xFF).isGreaterThan(150);
            // Far above the line.
            assertThat(px[2 * 20 + 10] & 0xFF).isLessThan(20);
        } finally { rt.dispose(); }
    }

    @Test
    void drawRoundRectStrokesEdge() {
        RTTexture rt = makeRT(40, 40);
        try {
            Graphics g = rt.createGraphics();
            g.clear(BLACK);
            g.setPaint(RED);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f));
            g.drawRoundRect(5, 5, 30, 30, 10, 10);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Top edge midpoint, on the stroke.
            assertThat(px[5 * 40 + 20] & 0xFF).isGreaterThan(150);
            // Center, inside the round rect, not on stroke.
            assertThat(px[20 * 40 + 20] & 0xFF).isLessThan(20);
        } finally { rt.dispose(); }
    }

    @Test
    void drawEllipseStrokesEdge() {
        RTTexture rt = makeRT(40, 40);
        try {
            Graphics g = rt.createGraphics();
            g.clear(BLACK);
            g.setPaint(RED);
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f));
            g.drawEllipse(5, 5, 30, 30);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Top of the ellipse.
            assertThat(px[5 * 40 + 20] & 0xFF).isGreaterThan(150);
            // Far outside.
            assertThat(px[0] & 0xFF).isLessThan(20);
        } finally { rt.dispose(); }
    }
}
