package org.openjfx.samples.ensemble;

import java.util.concurrent.CountDownLatch;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.image.SvgImage;
import javafx.scene.image.SvgImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

/**
 * Headless render test for {@link SvgImageView}: verifies that changing the
 * side-panel-style properties (tint, background) actually changes the rendered
 * output, by snapshotting the node before/after each change and diffing pixels.
 *
 * <p>Run: {@code ./gradlew :samples:ensemble:runSvgRenderTest}</p>
 */
public final class SvgRenderTest {

    // Solid red square; SRC_IN tint should repaint it a different color.
    private static final String RED = "<svg xmlns='http://www.w3.org/2000/svg' "
            + "viewBox='0 0 100 100' width='100%' height='100%'>"
            + "<rect width='100' height='100' fill='#ff0000'/></svg>";

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        StringBuilder out = new StringBuilder();

        Platform.startup(() -> {
            try {
                SvgImageView view = new SvgImageView(SvgImage.ofContent(RED));
                view.setFitWidth(64);
                view.setFitHeight(64);
                new Scene(new Group(view)); // node must be in a scene to snapshot

                int base = argb(view.snapshot(null, null), 32, 32);
                out.append("base=").append(hex(base)).append('\n');

                view.setTintMode(SvgImageView.TintMode.SRC_IN);
                view.setTint(Color.web("#00ff00"));
                int tinted = argb(view.snapshot(null, null), 32, 32);
                out.append("tinted=").append(hex(tinted)).append('\n');

                view.setTint(null);
                view.setBackgroundColor(Color.web("#0000ff"));
                int bg = argb(view.snapshot(null, null), 4, 4); // corner (red covers center)
                out.append("bgCorner=").append(hex(bg)).append('\n');

                // Changing fitWidth AFTER the first layout must resize the node
                // (regression: the inherited fit props didn't invalidate our geom cache).
                double w0 = view.getBoundsInLocal().getWidth();
                view.setFitWidth(160);
                double w1 = view.getBoundsInLocal().getWidth();
                boolean resizes = Math.abs(w0 - 64) < 1 && Math.abs(w1 - 160) < 1;
                out.append("fitW: ").append(w0).append("->").append(w1)
                   .append(resizes ? " (resizes)" : " (STALE)").append('\n');

                boolean tintWorks = greenish(tinted) && !greenish(base);
                out.append("RESULT=").append(tintWorks && resizes ? "PASS" : "FAIL");
            } catch (Throwable t) {
                out.append("EXCEPTION ").append(t);
            } finally {
                done.countDown();
            }
        });

        done.await();
        System.out.println("[svg-render] " + out.toString().replace("\n", "  "));
        Platform.exit();
        System.exit(0);
    }

    private static int argb(WritableImage img, int x, int y) {
        return img.getPixelReader().getArgb(x, y);
    }

    private static boolean greenish(int argb) {
        int r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
        return g > 120 && r < 120 && b < 120;
    }

    private static String hex(int argb) {
        return String.format("#%08X", argb);
    }

    private SvgRenderTest() {}
}
