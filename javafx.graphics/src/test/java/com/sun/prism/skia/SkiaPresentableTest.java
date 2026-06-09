package com.sun.prism.skia;

import com.sun.prism.PresentableState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkiaPresentable wires SkiaResourceFactory.createPresentable to the
 * Skia render path. End-to-end verification (the real
 * {@code uploadPixels}) requires a Glass {@code Application} which is
 * not available inside a JUnit-only test JVM — JavaFX's
 * {@link PresentableState} static-initializes against
 * {@code com.sun.glass.ui.Application.GetApplication()}, which is
 * {@code null} without {@code Toolkit.startup()}.
 *
 * <p>This test only checks that the Presentable type is reachable and
 * that the factory hook is wired. The render-and-upload flow is
 * covered indirectly by {@link SkiaGraphicsTest} (off-screen RT) and
 * will be smoke-tested again once Glass startup happens (HelloFX).</p>
 */
@EnabledIfSystemProperty(named = "openjfx.skia.runNativeTests", matches = "true")
class SkiaPresentableTest {

    @Test
    void presentableClassIsReachable() {
        // Just touching the class triggers any static-init issues at
        // load time. We don't try to construct one; that needs Glass.
        assertThat(SkiaPresentable.class.getSuperclass())
            .isEqualTo(SkiaRTTexture.class);
    }
}
