package com.sun.prism.skia;

import com.sun.prism.RTTexture;
import com.sun.prism.Texture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.ref.WeakReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a leaked (un-disposed) texture eventually has its
 * native handle reaped by the Cleaner.
 *
 * <p>The test allocates a texture, drops the strong reference, and
 * loops on {@code System.gc()} + a {@code WeakReference} to confirm
 * the wrapper became unreachable. The Cleaner action runs on a
 * background thread, so we observe success indirectly: once the
 * wrapper is GC'd, the action <i>has been or will soon be</i> run.
 * For a stricter assertion we'd need to drain the Cleaner's
 * internal queue, which has no public API.</p>
 */
@EnabledIfSystemProperty(named = "openjfx.skia.runNativeTests", matches = "true")
class CleanerLifecycleTest {

    @Test
    void leakedTextureBecomesUnreachable() throws InterruptedException {
        SkiaResourceFactory factory = new SkiaResourceFactory(null);
        WeakReference<RTTexture> ref;
        {
            RTTexture rt = factory.createRTTexture(8, 8, Texture.WrapMode.CLAMP_TO_EDGE);
            ref = new WeakReference<>(rt);
            // Intentionally do not call rt.dispose(); let the Cleaner
            // path handle it once the wrapper is unreachable.
        }
        // Loop on GC until the weak ref clears or we time out.
        long deadline = System.nanoTime() + 5_000_000_000L; // 5s
        while (ref.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(50);
        }
        assertThat(ref.get())
            .as("texture wrapper should be reachable-only-via-Cleaner after GC")
            .isNull();
    }
}
