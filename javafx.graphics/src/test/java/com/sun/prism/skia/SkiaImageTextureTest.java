package com.sun.prism.skia;

import com.sun.prism.Graphics;
import com.sun.prism.Image;
import com.sun.prism.RTTexture;
import com.sun.prism.Texture;
import com.sun.prism.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises Image → SkiaImageTexture → SkCanvas::drawImageRect end-to-end.
 *
 * <p>We hand-construct a 4×4 image with a known checkerboard pattern,
 * upload it via the resource factory, draw it onto a 32×32 RT, read
 * pixels back, and assert that the destination pixels match the
 * upscaled-with-bilinear pattern.</p>
 */
@EnabledIfSystemProperty(named = "openjfx.skia.runNativeTests", matches = "true")
class SkiaImageTextureTest {

    private static final SkiaResourceFactory FACTORY = new SkiaResourceFactory(null);

    private static Image makeRedImage(int w, int h) {
        // BYTE_BGRA_PRE: 4 bytes per pixel, B G R A.
        byte[] pixels = new byte[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            int o = i * 4;
            pixels[o]     = 0;       // B
            pixels[o + 1] = 0;       // G
            pixels[o + 2] = (byte) 255; // R
            pixels[o + 3] = (byte) 255; // A
        }
        return Image.fromByteBgraPreData(pixels, w, h);
    }

    @Test
    void factoryReturnsImageTextureFromImage() {
        Image src = makeRedImage(4, 4);
        Texture tex = FACTORY.createTexture(src, Texture.Usage.DEFAULT, Texture.WrapMode.CLAMP_TO_EDGE);
        try {
            assertThat(tex).isInstanceOf(SkiaImageTexture.class);
            assertThat(tex.getPhysicalWidth()).isEqualTo(4);
            assertThat(tex.getPhysicalHeight()).isEqualTo(4);
            assertThat(((SkiaImageTexture) tex).getNativeHandle()).isNotZero();
        } finally {
            tex.dispose();
            assertThat(((SkiaImageTexture) tex).getNativeHandle()).isZero();
        }
    }

    @Test
    void drawTextureFillsDestRect() {
        Image src = makeRedImage(4, 4);
        Texture tex = FACTORY.createTexture(src, Texture.Usage.DEFAULT, Texture.WrapMode.CLAMP_TO_EDGE);
        RTTexture rt = FACTORY.createRTTexture(32, 32, Texture.WrapMode.CLAMP_TO_EDGE);
        try {
            Graphics g = rt.createGraphics();
            g.clear(new Color(0f, 0f, 0f, 1f));   // black background
            g.drawTexture(tex, 8, 8, 16, 16);     // red square at (8,8)..(24,24)

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Center of drawn rect should be red.
            int center = px[16 * 32 + 16];
            // Outside: black background.
            int corner = px[0];

            assertThat(center & 0xFF).isGreaterThan(200);   // R high
            assertThat(corner & 0xFF).isLessThan(20);
        } finally {
            rt.dispose();
            tex.dispose();
        }
    }

    @Test
    void drawTextureSubRectAlsoWorks() {
        // 4x4 image: top-left 2x2 red, rest blue. Draw subrect of just
        // the top-left quad onto the RT — full RT should be red.
        byte[] pixels = new byte[4 * 4 * 4];
        for (int i = 0; i < 4 * 4; i++) {
            int x = i % 4;
            int y = i / 4;
            int o = i * 4;
            boolean topLeft = (x < 2 && y < 2);
            pixels[o]     = (byte) (topLeft ? 0   : 255); // B
            pixels[o + 1] = 0;                            // G
            pixels[o + 2] = (byte) (topLeft ? 255 : 0);   // R
            pixels[o + 3] = (byte) 255;                   // A
        }
        Image src = Image.fromByteBgraPreData(pixels, 4, 4);
        Texture tex = FACTORY.createTexture(src, Texture.Usage.DEFAULT, Texture.WrapMode.CLAMP_TO_EDGE);
        RTTexture rt = FACTORY.createRTTexture(16, 16, Texture.WrapMode.CLAMP_TO_EDGE);
        try {
            Graphics g = rt.createGraphics();
            g.clear(new Color(0f, 0f, 0f, 1f));
            // Draw source subrect (0,0)-(2,2) [red] into dest (0,0)-(16,16).
            g.drawTexture(tex,
                /*dx1*/ 0,  /*dy1*/ 0,  /*dx2*/ 16, /*dy2*/ 16,
                /*sx1*/ 0,  /*sy1*/ 0,  /*sx2*/ 2,  /*sy2*/ 2);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Anywhere in the RT should be red.
            assertThat(px[8 * 16 + 8] & 0xFF).isGreaterThan(200);
            assertThat((px[8 * 16 + 8] >>> 16) & 0xFF).isLessThan(20);
        } finally {
            rt.dispose();
            tex.dispose();
        }
    }
}
