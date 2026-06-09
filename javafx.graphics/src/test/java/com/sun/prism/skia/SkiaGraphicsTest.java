package com.sun.prism.skia;

import com.sun.prism.Graphics;
import com.sun.prism.RTTexture;
import com.sun.prism.Texture;
import com.sun.prism.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end render test driven through the public Prism API surface:
 *
 *   factory → createRTTexture → createGraphics → setPaint → fillRect
 *           → readPixels → assert
 *
 * <p>This is the first test that exercises the real
 * {@link com.sun.prism.Graphics} interface, not the bridge directly.
 * Every Skia operation here flows through {@code SkiaGraphics →
 * NativeBridge → SkCanvas}.</p>
 */
@EnabledIfSystemProperty(named = "openjfx.skia.runNativeTests", matches = "true")
class SkiaGraphicsTest {

    @Test
    void clearAndFillRectViaGraphicsApi() {
        SkiaResourceFactory factory = new SkiaResourceFactory(null);
        RTTexture rt = factory.createRTTexture(32, 16, Texture.WrapMode.CLAMP_TO_EDGE);
        try {
            Graphics g = rt.createGraphics();
            assertThat(g).isInstanceOf(SkiaGraphics.class);
            assertThat(g.getRenderTarget()).isSameAs(rt);
            assertThat(g.getResourceFactory()).isSameAs(factory);

            // Clear to opaque blue.
            g.clear(new Color(0f, 0f, 1f, 1f));

            // Fill an 8×8 green square at (4,4).
            g.setPaint(new Color(0f, 1f, 0f, 1f));
            g.fillRect(4, 4, 8, 8);

            int[] pixels = ((SkiaRTTexture) rt).getRawPixels();
            assertThat(pixels).hasSize(32 * 16);

            // Center of the green square: (8,8) → row 8, col 8.
            int center = pixels[8 * 32 + 8];
            // Outside it (corner): (0,0) → blue.
            int corner = pixels[0];

            // Pixel encoding is RGBA8888. Blue dominant in corner; green dominant in center.
            assertThat(corner & 0xFF).isLessThan(20);             // R low
            assertThat((corner >>> 16) & 0xFF).isGreaterThan(200); // B high
            assertThat((center >>> 8) & 0xFF).isGreaterThan(200);  // G high
            assertThat(center & 0xFF).isLessThan(20);             // R low
        } finally {
            rt.dispose();
        }
    }

    @Test
    void clearWithoutPaintUsesTransparent() {
        SkiaResourceFactory factory = new SkiaResourceFactory(null);
        RTTexture rt = factory.createRTTexture(8, 8, Texture.WrapMode.CLAMP_TO_EDGE);
        try {
            Graphics g = rt.createGraphics();
            // RTTexture defaults to non-opaque → clear() should
            // produce fully-transparent pixels.
            g.clear();
            int[] pixels = ((SkiaRTTexture) rt).getRawPixels();
            assertThat(pixels[0]).isEqualTo(0); // RGBA8888 (0,0,0,0)
        } finally {
            rt.dispose();
        }
    }
}
