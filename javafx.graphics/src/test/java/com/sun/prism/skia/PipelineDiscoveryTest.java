package com.sun.prism.skia;

import com.sun.prism.GraphicsPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Prism's pipeline-discovery reflection finds and initializes
 * our {@link SKIAPipeline}.
 *
 * <p>Requires the native bridge to be loadable; runs only when
 * {@code -Dopenjfx.skia.runNativeTests=true}, same gate as the bridge
 * smoke test.</p>
 */
@EnabledIfSystemProperty(named = "openjfx.skia.runNativeTests", matches = "true")
class PipelineDiscoveryTest {

    @Test
    void prismCreatesSkiaPipelineWhenOrderRequestsIt() {
        // The test JVM is launched with -Dprism.order=skia; verify
        // that the static `tryOrder` includes us, then ask Prism to
        // build the pipeline and assert we got the Skia one.
        assertThat(System.getProperty("prism.order")).contains("skia");

        GraphicsPipeline pipeline = GraphicsPipeline.createPipeline();
        assertThat(pipeline)
            .as("Prism should select SKIAPipeline for prism.order=skia")
            .isInstanceOf(SKIAPipeline.class);

        SKIAPipeline skia = (SKIAPipeline) pipeline;
        assertThat(skia.is3DSupported()).isFalse();
        assertThat(skia.isVsyncSupported()).isTrue();
    }
}
