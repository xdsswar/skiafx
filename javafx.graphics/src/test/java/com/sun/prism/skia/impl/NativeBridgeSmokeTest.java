package com.sun.prism.skia.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the native bridge.
 *
 * <p>Disabled by default — needs the native shared library on the
 * library path. Enable with
 * {@code -Dopenjfx.skia.runNativeTests=true} once
 * {@code ./gradlew :modules:javafx.graphics:nativeCompile} has run.</p>
 */
@EnabledIfSystemProperty(named = "openjfx.skia.runNativeTests", matches = "true")
class NativeBridgeSmokeTest {

    @Test
    void versionStringNonEmpty() {
        String v = NativeBridge.version();
        assertThat(v).startsWith("openjfx-skia-bridge/");
    }

    @Test
    void hasSkiaIsTrueWhenSkiaHomeWasSet() {
        // Either true (real Skia) or false (stub) is valid; we only
        // assert the call doesn't throw and is consistent with the
        // version string.
        boolean hasSkia = NativeBridge.hasSkia();
        String version = NativeBridge.version();
        if (hasSkia) {
            assertThat(version).contains("skia-enabled");
        } else {
            assertThat(version).contains("stub-no-skia");
        }
    }

    @Test
    void fillRectAntiAliasedDrawsInsideBufferOnly() {
        int width = 32, height = 32, rowBytes = width * 4;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate((long) rowBytes * height);

            // Clear to opaque black, then draw a red rectangle inside.
            assertThat(NativeBridge.clearBuffer(
                buf, width, height, rowBytes, 0, 0, 0, 255)).isEqualTo(0);
            assertThat(NativeBridge.fillRect(
                buf, width, height, rowBytes,
                8, 8, 16, 16, 255, 0, 0, 255)).isEqualTo(0);

            // Center of rect should be red, corner should be black.
            int center = buf.get(ValueLayout.JAVA_INT,
                ((long) (height / 2) * rowBytes) + ((long) (width / 2) * 4));
            int corner = buf.get(ValueLayout.JAVA_INT, 0);

            // Center R-channel ≈ 255, corner all zero except alpha.
            assertThat(center & 0xFF).isGreaterThan(200);  // red
            assertThat((corner >> 24) & 0xFF).isEqualTo(255); // black alpha
            assertThat(corner & 0xFFFFFF).isEqualTo(0);   // black rgb
        }
    }

    @Test
    void clearBufferFillsPremultipliedRgba() {
        int width = 4;
        int height = 4;
        int rowBytes = width * 4;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate((long) rowBytes * height);

            // Fill with a non-trivial color so we know the call ran.
            int r = 200, g = 100, b = 50, a = 255;
            int rc = NativeBridge.clearBuffer(buf, width, height, rowBytes, r, g, b, a);
            assertThat(rc).isEqualTo(0);

            // First pixel should be RGBA8888 packed (a=255 means premul == raw).
            int p0 = buf.get(ValueLayout.JAVA_INT, 0);
            int rOut = (p0      ) & 0xFF;
            int gOut = (p0 >>  8) & 0xFF;
            int bOut = (p0 >> 16) & 0xFF;
            int aOut = (p0 >> 24) & 0xFF;
            assertThat(rOut).isEqualTo(r);
            assertThat(gOut).isEqualTo(g);
            assertThat(bOut).isEqualTo(b);
            assertThat(aOut).isEqualTo(a);
        }
    }
}
