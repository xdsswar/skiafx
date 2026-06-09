package org.openjfx.samples.ensemble;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.SvgImage;
import javafx.scene.image.SvgImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/**
 * Live repaint test: confirms that changing a visual property (tint) WITHOUT a
 * geometry change triggers an automatic repaint of the node — i.e. the side
 * panel controls take effect live, not only on snapshot. Watches
 * {@link NGSvgImageView#RENDER_COUNT}.
 *
 * <p>Run: {@code ./gradlew :samples:ensemble:runSvgLiveTest}</p>
 */
public class SvgLiveTest extends Application {

    private static final String RED = "<svg xmlns='http://www.w3.org/2000/svg' "
            + "viewBox='0 0 100 100' width='100%' height='100%'>"
            + "<rect width='100' height='100' fill='#ff0000'/></svg>";

    @Override
    public void start(Stage stage) {
        SvgImageView view = new SvgImageView(SvgImage.ofContent(RED));
        view.setFitWidth(120);
        view.setFitHeight(120);
        stage.setScene(new Scene(new StackPane(view), 240, 240));
        stage.show();

        int[] frame = {0};
        int[] r0 = {0};
        new AnimationTimer() {
            @Override public void handle(long now) {
                frame[0]++;
                if (frame[0] == 20) {
                    r0[0] = renderCount();                        // baseline (steady state)
                } else if (frame[0] == 22) {
                    view.setTintMode(SvgImageView.TintMode.SRC_IN);
                    view.setTint(Color.web("#00ff00"));           // visual-only change
                } else if (frame[0] == 60) {
                    int r1 = renderCount();
                    System.out.println("[svg-live] beforeTint=" + r0[0] + " afterTint=" + r1
                            + " RESULT=" + (r1 > r0[0] ? "PASS" : "FAIL"));
                    stop();
                    Platform.exit();
                    System.exit(0);
                }
            }
        }.start();
    }

    /** Reads NGSvgImageView.RENDER_COUNT reflectively (internal package). */
    private static int renderCount() {
        try {
            return Class.forName("com.sun.javafx.sg.prism.NGSvgImageView")
                    .getField("RENDER_COUNT").getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static void main(String[] args) {
        Application.launch(args);
    }
}
