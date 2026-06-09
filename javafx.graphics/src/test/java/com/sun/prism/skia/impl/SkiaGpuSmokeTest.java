package com.sun.prism.skia.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the Phase-2 increment-1 GPU path: build a small
 * Ganesh-GL surface, clear it to a known color, read back via the
 * existing ARGB readback, and verify the round-trip.
 *
 * <p>Double-gated: needs the native bridge ({@code openjfx.skia.runNativeTests})
 * <b>and</b> GPU mode requested ({@code prism.skia.gpu=true}). Skipped
 * otherwise.</p>
 */
@EnabledIfSystemProperty(named = "openjfx.skia.runNativeTests", matches = "true")
@EnabledIfSystemProperty(named = "prism.skia.gpu",              matches = "true")
class SkiaGpuSmokeTest {

    @Test
    void gpuSurfaceClearAndReadBackRoundTrips() {
        MemorySegment surface = NativeBridge.surfaceCreateGpu(4, 4);
        assertThat(surface)
            .as("GPU surface creation; prism.skia.gpu=true assumes a working GL driver")
            .isNotNull()
            .isNotEqualTo(MemorySegment.NULL);
        try {
            // Clear to opaque red (R=255, G=0, B=0, A=255).
            int clearRc = NativeBridge.surfaceClear(surface, 255, 0, 0, 255);
            assertThat(clearRc).isZero();

            // Read back in INT_ARGB_PRE layout — i.e. 0xAARRGGBB int on LE.
            // SkSurface::readPixels on a GPU surface implicitly flushes
            // the GrDirectContext and stalls for the read.
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(4L * 4 * 4); // w * h * 4
                int readRc = NativeBridge.surfaceReadPixelsArgb(surface, buf, 0, 0, 4, 4);
                assertThat(readRc).isZero();

                int packed = buf.get(ValueLayout.JAVA_INT, 0);
                assertThat(packed)
                    .as("opaque red → INT_ARGB_PRE = 0xFFFF0000")
                    .isEqualTo(0xFFFF0000);
            }
        } finally {
            NativeBridge.surfaceDestroy(surface);
        }
    }
}
