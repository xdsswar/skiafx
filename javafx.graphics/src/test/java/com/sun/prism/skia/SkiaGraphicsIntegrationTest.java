package com.sun.prism.skia;

import com.sun.javafx.geom.transform.Affine2D;
import com.sun.prism.CompositeMode;
import com.sun.prism.Graphics;
import com.sun.prism.RTTexture;
import com.sun.prism.Texture;
import com.sun.prism.paint.Color;
import com.sun.prism.paint.Gradient;
import com.sun.prism.paint.LinearGradient;
import com.sun.prism.paint.RadialGradient;
import com.sun.prism.paint.Stop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the "complete" {@link SkiaGraphics}:
 * gradients, transform sync, clip, composite mode, extra alpha.
 */
@EnabledIfSystemProperty(named = "openjfx.skia.runNativeTests", matches = "true")
class SkiaGraphicsIntegrationTest {

    private static final SkiaResourceFactory FACTORY = new SkiaResourceFactory(null);

    private RTTexture rt(int w, int h) {
        return FACTORY.createRTTexture(w, h, Texture.WrapMode.CLAMP_TO_EDGE);
    }

    @Test
    void linearGradientFillVariesAcrossExtent() {
        // Red→blue horizontal gradient across a 32×8 rect.
        List<Stop> stops = List.of(
            new Stop(new Color(1f, 0f, 0f, 1f), 0f),
            new Stop(new Color(0f, 0f, 1f, 1f), 1f));
        LinearGradient lg = new LinearGradient(0, 0, 32, 0,
            null, false, Gradient.PAD, stops);

        RTTexture rt = rt(32, 8);
        try {
            Graphics g = rt.createGraphics();
            g.clear(new Color(0f, 0f, 0f, 1f));
            g.setPaint(lg);
            g.fillRect(0, 0, 32, 8);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            int leftR  =  px[4 * 32 + 1]        & 0xFF;
            int leftB  = (px[4 * 32 + 1] >>> 16) & 0xFF;
            int rightR =  px[4 * 32 + 30]        & 0xFF;
            int rightB = (px[4 * 32 + 30] >>> 16) & 0xFF;

            assertThat(leftR).isGreaterThan(200);   // red on left
            assertThat(leftB).isLessThan(60);
            assertThat(rightB).isGreaterThan(200);  // blue on right
            assertThat(rightR).isLessThan(60);
        } finally { rt.dispose(); }
    }

    @Test
    void radialGradientFillIsBrightAtCenter() {
        List<Stop> stops = List.of(
            new Stop(new Color(1f, 1f, 1f, 1f), 0f),
            new Stop(new Color(0f, 0f, 0f, 1f), 1f));
        // RadialGradient(centerX, centerY, focusAngle, focusDistance, radius, ...)
        RadialGradient rg = new RadialGradient(16, 16, 0, 0, 16,
            null, false, Gradient.PAD, stops);

        RTTexture rt = rt(32, 32);
        try {
            Graphics g = rt.createGraphics();
            g.clear(new Color(0f, 0f, 0f, 1f));
            g.setPaint(rg);
            g.fillRect(0, 0, 32, 32);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Center should be bright (white-ish).
            int center = px[16 * 32 + 16];
            // Edge should be dark.
            int corner = px[0];
            int centerLuma = (center & 0xFF) + ((center >>> 8) & 0xFF) + ((center >>> 16) & 0xFF);
            int cornerLuma = (corner & 0xFF) + ((corner >>> 8) & 0xFF) + ((corner >>> 16) & 0xFF);
            assertThat(centerLuma).isGreaterThan(cornerLuma + 200);
        } finally { rt.dispose(); }
    }

    @Test
    void transformAppliesToFillRect() {
        RTTexture rt = rt(32, 32);
        try {
            Graphics g = rt.createGraphics();
            g.clear(new Color(0f, 0f, 0f, 1f));
            g.setPaint(new Color(1f, 0f, 0f, 1f));

            // Translate (10, 10) then fill a 4x4 rect at origin →
            // pixel rect ends up at (10..14, 10..14).
            Affine2D xform = new Affine2D();
            xform.setToTranslation(10, 10);
            g.setTransform(xform);
            g.fillRect(0, 0, 4, 4);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Inside translated rect.
            assertThat(px[12 * 32 + 12] & 0xFF).isGreaterThan(200);
            // Origin (0,0) was background.
            assertThat(px[0]            & 0xFF).isLessThan(20);
            // (5,5) — also outside.
            assertThat(px[5 * 32 + 5]   & 0xFF).isLessThan(20);
        } finally { rt.dispose(); }
    }

    @Test
    void extraAlphaScalesFillAlpha() {
        RTTexture rt = rt(8, 8);
        try {
            Graphics g = rt.createGraphics();
            g.clear(new Color(0f, 0f, 0f, 1f));
            g.setPaint(new Color(1f, 0f, 0f, 1f));   // full red
            g.setExtraAlpha(0.5f);                   // half opacity
            g.fillRect(0, 0, 8, 8);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Pixel is half red over black → ~127 red, ~127 alpha (premul).
            int p = px[4 * 8 + 4];
            int r =  p          & 0xFF;
            assertThat(r).isBetween(100, 160);
        } finally { rt.dispose(); }
    }

    @Test
    void compositeModeSrcOverridesDestination() {
        RTTexture rt = rt(8, 8);
        try {
            Graphics g = rt.createGraphics();
            // Initially white, then overwrite half with SRC blend.
            g.clear(new Color(1f, 1f, 1f, 1f));
            g.setPaint(new Color(1f, 0f, 0f, 0.5f));  // half-alpha red
            // Default blend mode is SRC_OVER → blend red with white → pink.
            g.fillRect(0, 0, 4, 8);
            // SRC: replace dst with paint as-is (premul).
            g.setCompositeMode(CompositeMode.SRC);
            g.fillRect(4, 0, 4, 8);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            int srcOver = px[4 * 8 + 1];   // SrcOver region
            int src     = px[4 * 8 + 5];   // Src region

            // SrcOver region: pink (high R, also some G + B).
            assertThat(srcOver & 0xFF).isGreaterThan(200);
            assertThat((srcOver >>> 8) & 0xFF).isBetween(80, 200);

            // Src region: half-alpha red replaces white → final pixel
            // is premultiplied (~127 R, alpha=127). So R≈127, alpha≈127.
            assertThat((src >>> 24) & 0xFF).isBetween(100, 160);
            assertThat(src & 0xFF).isBetween(100, 160);
        } finally { rt.dispose(); }
    }

    @Test
    void clipRestrictsDrawArea() {
        RTTexture rt = rt(16, 16);
        try {
            Graphics g = rt.createGraphics();
            g.clear(new Color(0f, 0f, 0f, 1f));
            g.setPaint(new Color(1f, 0f, 0f, 1f));
            // Clip to top-left 8×8 only.
            g.setClipRect(new com.sun.javafx.geom.Rectangle(0, 0, 8, 8));
            g.fillRect(0, 0, 16, 16); // would fill all without clip

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Inside clip: red.
            assertThat(px[4 * 16 + 4]   & 0xFF).isGreaterThan(200);
            // Outside clip: still black.
            assertThat(px[12 * 16 + 12] & 0xFF).isLessThan(20);
        } finally { rt.dispose(); }
    }
}
