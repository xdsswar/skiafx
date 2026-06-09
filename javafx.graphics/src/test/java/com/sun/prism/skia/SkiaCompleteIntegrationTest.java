package com.sun.prism.skia;

import com.sun.prism.BasicStroke;
import com.sun.prism.Graphics;
import com.sun.prism.RTTexture;
import com.sun.prism.Texture;
import com.sun.prism.paint.Color;
import com.sun.prism.paint.Gradient;
import com.sun.prism.paint.LinearGradient;
import com.sun.prism.paint.Stop;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.prism.skia.impl.SkiaImageFilters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the "complete integration" features:
 *   - stroke-with-gradient,
 *   - HiDPI pixel scale,
 *   - blur / drop-shadow filters via save_layer_with_filter.
 */
@EnabledIfSystemProperty(named = "openjfx.skia.runNativeTests", matches = "true")
class SkiaCompleteIntegrationTest {

    private static final SkiaResourceFactory FACTORY = new SkiaResourceFactory(null);

    private RTTexture rt(int w, int h) {
        return FACTORY.createRTTexture(w, h, Texture.WrapMode.CLAMP_TO_EDGE);
    }

    @Test
    void strokeWithLinearGradient() {
        // Gradient from red to blue along the X axis, stroke a wide
        // horizontal rect — pixels along the stroke should vary.
        List<Stop> stops = List.of(
            new Stop(new Color(1f, 0f, 0f, 1f), 0f),
            new Stop(new Color(0f, 0f, 1f, 1f), 1f));
        LinearGradient lg = new LinearGradient(0, 0, 32, 0,
            null, false, Gradient.PAD, stops);

        RTTexture rt = rt(32, 16);
        try {
            Graphics g = rt.createGraphics();
            g.clear(new Color(0f, 0f, 0f, 1f));
            g.setPaint(lg);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f));
            g.drawRect(2, 4, 28, 8);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Top edge of stroke at y=4: x=4 (red side), x=28 (blue side).
            int leftR =  px[4 * 32 + 4]        & 0xFF;
            int rightB= (px[4 * 32 + 28] >>> 16) & 0xFF;
            assertThat(leftR).isGreaterThan(180);
            assertThat(rightB).isGreaterThan(180);
        } finally { rt.dispose(); }
    }

    @Test
    void hidpiScaleDoublesEffectiveResolution() {
        RTTexture rt = rt(32, 32);
        try {
            Graphics g = rt.createGraphics();
            g.setPixelScaleFactors(2f, 2f);
            g.clear(new Color(0f, 0f, 0f, 1f));
            g.setPaint(new Color(1f, 0f, 0f, 1f));
            // Logical 4x4 rect at (1,1) → physical 8x8 rect at (2,2)
            // because pixelScale × user_transform multiplies coords by 2.
            g.fillRect(1, 1, 4, 4);

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Center of the scaled rect: (2 + 4, 2 + 4) = (6, 6).
            assertThat(px[6 * 32 + 6] & 0xFF).isGreaterThan(200);
            // Outside the scaled rect: should be black at (12,12).
            assertThat(px[12 * 32 + 12] & 0xFF).isLessThan(20);
        } finally { rt.dispose(); }
    }

    @Test
    void blurFilterSpreadsPixelsBeyondSourceBounds() {
        RTTexture rt = rt(32, 32);
        try {
            Graphics g = rt.createGraphics();
            g.clear(new Color(0f, 0f, 0f, 1f));

            try (SkiaImageFilters.Handle blur = SkiaImageFilters.blur(4f, 4f)) {
                MemorySegment surface = MemorySegment.ofAddress(
                    ((SkiaRTTexture) rt).getNativeHandle());
                NativeBridge.surfaceSaveLayerWithFilter(surface, blur.filter);
                try {
                    g.setPaint(new Color(1f, 0f, 0f, 1f));
                    g.fillRect(12, 12, 8, 8);  // crisp 8x8 red square
                } finally {
                    NativeBridge.surfaceRestore(surface);
                }
            }

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // Center of the original square: still red (blurred so dimmer than 255).
            assertThat(px[16 * 32 + 16] & 0xFF).isGreaterThan(50);
            // Several pixels outside the original 8x8 should also be
            // partially red because of the blur spread.
            assertThat(px[16 * 32 + 8]  & 0xFF).isGreaterThan(20);
            assertThat(px[16 * 32 + 24] & 0xFF).isGreaterThan(20);
        } finally { rt.dispose(); }
    }

    @Test
    void dropShadowProducesShadowBeneathSource() {
        RTTexture rt = rt(40, 40);
        try {
            Graphics g = rt.createGraphics();
            g.clear(new Color(1f, 1f, 1f, 1f));   // white background

            try (SkiaImageFilters.Handle ds = SkiaImageFilters.dropShadow(
                    4f, 4f, 2f, 2f, 0, 0, 0, 200)) {
                MemorySegment surface = MemorySegment.ofAddress(
                    ((SkiaRTTexture) rt).getNativeHandle());
                NativeBridge.surfaceSaveLayerWithFilter(surface, ds.filter);
                try {
                    g.setPaint(new Color(1f, 0f, 0f, 1f));
                    g.fillRect(10, 10, 16, 16);
                } finally {
                    NativeBridge.surfaceRestore(surface);
                }
            }

            int[] px = ((SkiaRTTexture) rt).getRawPixels();
            // The shadow should darken pixels at +4,+4 from the rect:
            // (28, 28) was white, now should be darker (lower R-channel
            // because the shadow is overlaid).
            int shadowed = px[28 * 40 + 28];
            int undisturbed = px[2 * 40 + 2];
            int rShadow = shadowed   & 0xFF;
            int rClean  = undisturbed & 0xFF;
            assertThat(rShadow).isLessThan(rClean);
        } finally { rt.dispose(); }
    }

    @Test
    void filterComposeChainsTwoEffects() {
        try (SkiaImageFilters.Handle blur = SkiaImageFilters.blur(2f, 2f);
             SkiaImageFilters.Handle ds   = SkiaImageFilters.dropShadow(
                 2f, 2f, 1f, 1f, 0, 0, 0, 255);
             SkiaImageFilters.Handle composed = SkiaImageFilters.compose(blur, ds)) {

            assertThat(blur.isValid()).isTrue();
            assertThat(ds.isValid()).isTrue();
            assertThat(composed.isValid()).isTrue();
        }
    }
}
