package com.sun.prism.skia;

import com.sun.javafx.font.CharToGlyphMapper;
import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.FontStrike;
import com.sun.javafx.font.Glyph;
import com.sun.javafx.font.Metrics;
import com.sun.javafx.geom.Path2D;
import com.sun.javafx.geom.PathIterator;
import com.sun.javafx.geom.Point2D;
import com.sun.javafx.geom.RectBounds;
import com.sun.javafx.geom.Shape;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.scene.text.GlyphList;
import com.sun.javafx.scene.text.TextSpan;
import com.sun.prism.Graphics;
import com.sun.prism.RTTexture;
import com.sun.prism.Texture;
import com.sun.prism.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the {@code drawString → glyph outline → fill_path} pipeline
 * using hand-built Glyph / GlyphList / FontStrike instances. Skia
 * doesn't need a real font — every glyph contributes its
 * {@link Glyph#getShape() outline} which we route through the existing
 * path-fill bridge entry.
 */
@EnabledIfSystemProperty(named = "openjfx.skia.runNativeTests", matches = "true")
class SkiaGraphicsTextTest {

    private static final SkiaResourceFactory FACTORY = new SkiaResourceFactory(null);

    /** A glyph whose outline is just a 6×8 filled rect — easy to hit-test. */
    private static final class BoxGlyph implements Glyph {
        @Override public int getGlyphCode() { return 1; }
        @Override public RectBounds getBBox() { return new RectBounds(0, -8, 6, 0); }
        @Override public float getAdvance() { return 6; }
        @Override public Shape getShape() {
            Path2D p = new Path2D(PathIterator.WIND_NON_ZERO);
            p.moveTo(0, -8);
            p.lineTo(6, -8);
            p.lineTo(6, 0);
            p.lineTo(0, 0);
            p.closePath();
            return p;
        }
        @Override public byte[] getPixelData() { return null; }
        @Override public byte[] getPixelData(int subPixel) { return null; }
        @Override public float getPixelXAdvance() { return 6; }
        @Override public float getPixelYAdvance() { return 0; }
        @Override public boolean isLCDGlyph() { return false; }
        @Override public int getWidth()  { return 6; }
        @Override public int getHeight() { return 8; }
        @Override public int getOriginX() { return 0; }
        @Override public int getOriginY() { return -8; }
    }

    private static final BoxGlyph BOX = new BoxGlyph();

    /** A FontStrike that returns BOX for every glyph code. */
    private static final FontStrike STRIKE = new FontStrike() {
        @Override public FontResource getFontResource() { return null; }
        @Override public float getSize() { return 12; }
        @Override public int getQuantizedPosition(Point2D point) { return 0; }
        @Override public Metrics getMetrics() { return null; }
        @Override public Glyph getGlyph(char symbol) { return BOX; }
        @Override public Glyph getGlyph(int glyphCode) { return BOX; }
        @Override public void clearDesc() {}
        @Override public int getAAMode() { return 0; }
        @Override public boolean drawAsShapes() { return true; }
        @Override public BaseTransform getTransform() { return BaseTransform.IDENTITY_TRANSFORM; }
        @Override public float getCharAdvance(char ch) { return 6; }
        @Override public Shape getOutline(GlyphList gl, BaseTransform tx) {
            return new Path2D(); // not exercised by drawString
        }
    };

    /** A 3-glyph GlyphList: positions (0,0), (10,0), (20,0). */
    private static GlyphList threeGlyphs() {
        return new GlyphList() {
            @Override public int getGlyphCount() { return 3; }
            @Override public int getGlyphCode(int i) { return 1; }
            @Override public float getPosX(int i) { return i * 10f; }
            @Override public float getPosY(int i) { return 0f; }
            @Override public float getWidth() { return 30; }
            @Override public float getHeight() { return 8; }
            @Override public RectBounds getLineBounds() { return new RectBounds(0, -8, 30, 0); }
            @Override public Point2D getLocation() { return new Point2D(0, 0); }
            @Override public int getCharOffset(int i) { return i; }
            @Override public boolean isComplex() { return false; }
            @Override public TextSpan getTextSpan() { return null; }
            @Override public boolean isLinebreak() { return false; }
            @Override public int getStart() { return 0; }
            @Override public int getOffsetAtX(float x, AtomicBoolean trailing) { return 0; }
        };
    }

    @Test
    void drawStringRendersThreeGlyphs() {
        RTTexture rt = FACTORY.createRTTexture(40, 16, Texture.WrapMode.CLAMP_TO_EDGE);
        try {
            Graphics g = rt.createGraphics();
            g.clear(new Color(0f, 0f, 0f, 1f));
            g.setPaint(new Color(1f, 0f, 0f, 1f));
            g.drawString(threeGlyphs(), STRIKE,
                /*x*/ 2, /*y*/ 12, /*selectColor*/ null, 0, 0);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Glyph 0 at (2..8) × (4..12) — center ≈ (5, 8).
            // Glyph 1 at (12..18) × (4..12) — center ≈ (15, 8).
            // Glyph 2 at (22..28) × (4..12) — center ≈ (25, 8).
            // Gaps at (9..11) and (19..21) — should be black.
            assertThat(px[8 * 40 + 5]  & 0xFF).isGreaterThan(200); // glyph 0
            assertThat(px[8 * 40 + 15] & 0xFF).isGreaterThan(200); // glyph 1
            assertThat(px[8 * 40 + 25] & 0xFF).isGreaterThan(200); // glyph 2
            assertThat(px[8 * 40 + 10] & 0xFF).isLessThan(20);     // gap
        } finally { rt.dispose(); }
    }

    @Test
    void emptyGlyphListIsNoOp() {
        RTTexture rt = FACTORY.createRTTexture(8, 8, Texture.WrapMode.CLAMP_TO_EDGE);
        try {
            Graphics g = rt.createGraphics();
            g.clear(new Color(0f, 0f, 0f, 1f));
            g.setPaint(new Color(1f, 0f, 0f, 1f));

            GlyphList empty = new GlyphList() {
                @Override public int getGlyphCount() { return 0; }
                @Override public int getGlyphCode(int i) { return 0; }
                @Override public float getPosX(int i) { return 0; }
                @Override public float getPosY(int i) { return 0; }
                @Override public float getWidth() { return 0; }
                @Override public float getHeight() { return 0; }
                @Override public RectBounds getLineBounds() { return new RectBounds(); }
                @Override public Point2D getLocation() { return new Point2D(0, 0); }
                @Override public int getCharOffset(int i) { return 0; }
                @Override public boolean isComplex() { return false; }
                @Override public TextSpan getTextSpan() { return null; }
                @Override public boolean isLinebreak() { return false; }
                @Override public int getStart() { return 0; }
                @Override public int getOffsetAtX(float x, AtomicBoolean t) { return 0; }
            };
            g.drawString(empty, STRIKE, 0, 0, null, 0, 0);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            assertThat(px[0]).isEqualTo(0xFF000000); // still black (alpha=255)
        } finally { rt.dispose(); }
    }

    @Test
    void selectionRangeUsesAlternateColor() {
        // Three glyphs at offsets 0, 1, 2. Select offset 1 only → green.
        RTTexture rt = FACTORY.createRTTexture(40, 16, Texture.WrapMode.CLAMP_TO_EDGE);
        try {
            Graphics g = rt.createGraphics();
            g.clear(new Color(0f, 0f, 0f, 1f));
            g.setPaint(new Color(1f, 0f, 0f, 1f));     // base = red
            Color green = new Color(0f, 1f, 0f, 1f);   // selection = green
            g.drawString(threeGlyphs(), STRIKE, 2, 12, green, 1, 2);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            int g0 = px[8 * 40 + 5];   // not selected
            int g1 = px[8 * 40 + 15];  // selected
            int g2 = px[8 * 40 + 25];  // not selected

            // g0/g2 red dominant.
            assertThat(g0 & 0xFF).isGreaterThan(200);
            assertThat(g2 & 0xFF).isGreaterThan(200);
            // g1 green dominant.
            assertThat((g1 >>> 8) & 0xFF).isGreaterThan(200);
            assertThat(g1 & 0xFF).isLessThan(20);
        } finally { rt.dispose(); }
    }
}
