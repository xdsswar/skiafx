package com.sun.prism.skia;

import com.sun.prism.PixelFormat;
import com.sun.prism.Texture;
import com.sun.prism.skia.impl.NativeBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link SkiaRTTexture} end-to-end:
 *   - allocate native SkSurface via the factory,
 *   - drive Skia draw calls through the bridge,
 *   - read pixels back into Java and verify content.
 *
 * <p>Phase 1 surrogate for {@code SkiaGraphics}: we drive
 * {@link NativeBridge#surfaceClear} / {@link NativeBridge#surfaceFillRect}
 * directly against the surface handle, since the proper {@code Graphics}
 * implementation lands next.</p>
 */
@EnabledIfSystemProperty(named = "openjfx.skia.runNativeTests", matches = "true")
class SkiaRTTextureTest {

    @Test
    void allocatesAndDisposesCleanly() {
        SkiaRTTexture rt = new SkiaRTTexture(null, null, 32, 16, Texture.WrapMode.CLAMP_TO_EDGE, false);
        try {
            assertThat(rt.getNativeHandle()).isNotZero();
            assertThat(rt.getPhysicalWidth()).isEqualTo(32);
            assertThat(rt.getPhysicalHeight()).isEqualTo(16);
            assertThat(rt.getPixelFormat()).isEqualTo(PixelFormat.BYTE_BGRA_PRE);
            assertThat(rt.isLocked()).isFalse();

            rt.lock();
            rt.lock();
            assertThat(rt.getLockCount()).isEqualTo(2);
            rt.unlock();
            assertThat(rt.isLocked()).isTrue();
        } finally {
            rt.dispose();
        }
        assertThat(rt.isSurfaceLost()).isTrue();
        assertThat(rt.getNativeHandle()).isZero();
    }

    @Test
    void clearAndFillRectThenReadBack() {
        SkiaRTTexture rt = new SkiaRTTexture(null, null, 16, 16, Texture.WrapMode.CLAMP_TO_EDGE, false);
        try {
            MemorySegment handle = MemorySegment.ofAddress(rt.getNativeHandle());

            // Clear to opaque black, then draw a 4x4 red square at (6,6).
            assertThat(NativeBridge.surfaceClear(handle, 0, 0, 0, 255)).isEqualTo(0);
            assertThat(NativeBridge.surfaceFillRect(
                handle, 6, 6, 4, 4, 255, 0, 0, 255)).isEqualTo(0);

            int[] pixels = rt.getRawPixels();
            assertThat(pixels).hasSize(16 * 16);

            // Center pixel of the rect is (8,8) — should be red.
            int center = pixels[8 * 16 + 8];
            int corner = pixels[0];

            // Pixel encoding is RGBA8888; channel-0 (low byte) is R.
            assertThat(center & 0xFF).isGreaterThan(200);    // red dominant
            assertThat((corner >>> 24) & 0xFF).isEqualTo(255); // black alpha
            assertThat(corner & 0x00FFFFFF).isEqualTo(0);    // black rgb
        } finally {
            rt.dispose();
        }
    }

    @Test
    void factoryProducesRTTextures() {
        // Drive the real ResourceFactory path. We use a null Screen
        // because the factory only stores it; nothing here needs Glass.
        SkiaResourceFactory factory = new SkiaResourceFactory(null);
        var rt = factory.createRTTexture(8, 8, Texture.WrapMode.CLAMP_TO_EDGE);
        try {
            assertThat(rt).isInstanceOf(SkiaRTTexture.class);
            assertThat(factory.isCompatibleTexture(rt)).isTrue();
        } finally {
            rt.dispose();
        }
    }
}
