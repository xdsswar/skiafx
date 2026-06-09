/*
 * LoaderDemo — show a loader, then transition to styled content.
 *
 * Mirrors the Skeleton pattern from xbill: the root is an AnchorPane
 * with the loader anchored to fill, the content is added on top and
 * faded in after a short pause. Loader stops once content is in.
 */
package org.openjfx.samples.ensemble;

import javafx.animation.*;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

public final class LoaderDemo extends Application {

    private static final Duration LOADER_HOLD   = Duration.seconds(2.0);
    private static final Duration CONTENT_FADE  = Duration.millis(450);
    private static final Duration LOADER_FADE   = Duration.millis(300);

    @Override
    public void start(Stage stage) {
        AnchorPane root = new AnchorPane();
        root.getStyleClass().add("root-pane");

        DualLoader loader = new DualLoader();
        anchorFill(loader);
        root.getChildren().add(loader);
        loader.start();

        Region content = buildContent();
        anchorFill(content);
        content.setOpacity(0);
        root.getChildren().add(content);

        Label fpsBadge = buildFpsBadge();
        AnchorPane.setTopAnchor(fpsBadge, 12d);
        AnchorPane.setRightAnchor(fpsBadge, 14d);
        root.getChildren().add(fpsBadge);
        startFpsTimer(fpsBadge);

        Scene scene = new Scene(root, 960, 600);
        java.net.URL css = locateStylesheet("loader-demo.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        } else {
            System.err.println("[LoaderDemo] loader-demo.css NOT FOUND");
        }
        stage.setScene(scene);
        stage.setTitle("Loader Demo");
       // stage.setOpacity(0);
        stage.show();

        //var x = fadeIn(stage, 10);
        //x.play();


        // Hold the loader for LOADER_HOLD, then crossfade to content.
        PauseTransition hold = new PauseTransition(LOADER_HOLD);
        hold.setOnFinished(e -> {
            FadeTransition fadeIn = new FadeTransition(CONTENT_FADE, content);
            fadeIn.setFromValue(0);
            fadeIn.setToValue(1);

            FadeTransition fadeOut = new FadeTransition(LOADER_FADE, loader);
            fadeOut.setFromValue(1);
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(ev -> {
                loader.stop();
                root.getChildren().remove(loader);
            });

            fadeIn.play();
            fadeOut.play();
        });
        hold.play();
    }

    private Region buildContent() {
        Label title = new Label("Welcome back");
        title.getStyleClass().add("title");

        Label subtitle = new Label(
            "Your scene is ready. The loader handled the boot animation.");
        subtitle.getStyleClass().add("subtitle");

        HBox tiles = new HBox(16,
            tile("Renders",  "1,284", "+12.4%"),
            tile("Latency",  "8.3 ms", "-0.6 ms"),
            tile("Memory",   "412 MB", "stable"),
            tile("FPS",      "144",   "locked"));
        tiles.getStyleClass().add("tiles");
        tiles.setAlignment(Pos.CENTER);

        Button primary   = new Button("Get started");
        primary.getStyleClass().add("primary");

        Button secondary = new Button("View docs");
        secondary.getStyleClass().add("secondary");

        HBox actions = new HBox(12, primary, secondary);
        actions.setAlignment(Pos.CENTER);

        VBox card = new VBox(18, title, subtitle, tiles, actions);
        card.getStyleClass().add("card");
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(720);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setPadding(new Insets(36, 40, 36, 40));

        VBox wrap = new VBox(card);
        wrap.setAlignment(Pos.CENTER);
        wrap.setPadding(new Insets(24));
        VBox.setVgrow(card, Priority.NEVER);
        return wrap;
    }

    private VBox tile(String caption, String value, String delta) {
        Label vLabel = new Label(value);
        vLabel.getStyleClass().add("tile-value");

        Label cLabel = new Label(caption);
        cLabel.getStyleClass().add("tile-caption");

        Label dLabel = new Label(delta);
        dLabel.getStyleClass().add("tile-delta");

        VBox box = new VBox(4, cLabel, vLabel, dLabel);
        box.getStyleClass().add("tile");
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private static void anchorFill(javafx.scene.Node n) {
        AnchorPane.setTopAnchor(n, 0d);
        AnchorPane.setRightAnchor(n, 0d);
        AnchorPane.setBottomAnchor(n, 0d);
        AnchorPane.setLeftAnchor(n, 0d);
    }

    private Label buildFpsBadge() {
        Label badge = new Label("— fps");
        badge.getStyleClass().add("fps-badge");
        return badge;
    }

    private void startFpsTimer(Label badge) {
        new AnimationTimer() {
            long windowStart = 0;
            int  frames      = 0;

            @Override public void handle(long now) {
                frames++;
                if (windowStart == 0) {
                    windowStart = now;
                    return;
                }
                long elapsed = now - windowStart;
                if (elapsed >= 500_000_000L) {
                    double fps = frames * 1_000_000_000.0 / elapsed;
                    badge.setText(String.format("%.0f fps", fps));
                    badge.getStyleClass().removeAll(
                        "fps-good", "fps-okay", "fps-slow");
                    String tier = fps >= 100 ? "fps-good"
                                : fps >= 50  ? "fps-okay"
                                              : "fps-slow";
                    badge.getStyleClass().add(tier);
                    windowStart = now;
                    frames = 0;
                }
            }
        }.start();
    }

    /**
     * Find a stylesheet at the module-root resources directory.
     * Under JPMS, {@code Class.getResource("/foo.css")} doesn't see
     * resources at module root when classes and resources are in
     * separate module-path directories (the common Gradle layout).
     * The system class loader does — it walks every module-path
     * entry irrespective of which one carries module-info.class.
     */
    private static java.net.URL locateStylesheet(String name) {
        java.net.URL u = ClassLoader.getSystemResource(name);
        if (u != null) return u;
        // Fallbacks for fat-jar / packaged runs.
        u = LoaderDemo.class.getClassLoader().getResource(name);
        if (u != null) return u;
        u = LoaderDemo.class.getResource("/" + name);
        return u;
    }

    public static Timeline fadeIn(Stage stage, int delay) {
        return new Timeline(new KeyFrame(Duration.millis(delay), new KeyValue(stage.opacityProperty(), 1)));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
