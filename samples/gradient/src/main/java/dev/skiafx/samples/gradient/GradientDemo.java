package dev.skiafx.samples.gradient;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Stage;

/**
 * Manual test for proportional gradient rendering on the Skia pipeline, and a
 * demonstrator for the {@link Application#setGpuBackend} selector.
 *
 * <p>Reproduces the reported bug: a proportional {@link LinearGradient} background
 * (a coloured stop fading to {@link Color#TRANSPARENT}) used to render as a
 * near-solid bar one direction and nothing the other, because the Skia path
 * ignored the {@code proportional} flag and the fill bounds. Each row below
 * should show a SMOOTH fade; the transparent end must reveal the gradient
 * backdrop, not a black/solid block.</p>
 *
 * <p>Run from the Gradle panel: {@code :samples:gradient:run}. Pick a GPU backend
 * with {@code -Pdemo.backend=AUTO|OPENGL|DIRECT3D12|METAL|VULKAN} (an unsupported
 * one falls back to the most suitable; watch the "Skia GPU enabled: ..." log).</p>
 */
public class GradientDemo extends Application<Stage> {

    private static final Stop[] FADE = {
        new Stop(0, new Color(0, 0, 0, 0.8)),   // black, 80% alpha
        new Stop(1, Color.TRANSPARENT)          // rgba(0,0,0,0)
    };

    @Override
    public void start(Stage stage) {
        VBox root = new VBox(14);
        root.setPadding(new Insets(18));
        // A bright backdrop so any transparency is obvious (a real bug paints
        // these areas black instead of letting the backdrop through).
        root.setStyle("-fx-background-color: linear-gradient(to bottom right, #2bc0e4, #eaecc6);");

        root.getChildren().addAll(
            labeled("Linear, fade to the RIGHT (start=left)  — expect black→transparent L→R",
                    fadeRow(0, 0, 1, 0)),
            labeled("Linear, fade to the LEFT  (start=right) — expect black→transparent R→L",
                    fadeRow(1, 0, 0, 0)),
            labeled("Linear, fade DOWN (vertical)            — expect black→transparent top→bottom",
                    fadeRow(0, 0, 0, 1)),
            labeled("Linear DIAGONAL                          — expect black corner→transparent corner",
                    fadeRow(0, 0, 1, 1)),
            labeled("Radial on a SQUARE region (120x120)     — expect a centred circle",
                    radialRow(120, 120)),
            labeled("Radial on a WIDE region (360x90)        — expect an ELLIPSE (matches aspect)",
                    radialRow(360, 90)),
            labeled("Gradient TEXT fill (proportional)        — expect each glyph spanning the fade",
                    gradientText()),
            opacityDemo()
        );

        stage.setScene(new Scene(root, 760, 720));
        stage.setTitle("skia-fx proportional gradient test — backend: " + getGpuBackend());
        stage.show();
    }

    private static VBox labeled(String text, Region content) {
        Label l = new Label(text);
        l.setFont(Font.font("Consolas", 12));
        return new VBox(4, l, content);
    }

    private static Region fadeRow(double sx, double sy, double ex, double ey) {
        Region r = new Region();
        r.setPrefSize(400, 60);
        r.setMinSize(400, 60);
        r.setBackground(new Background(new BackgroundFill(
            new LinearGradient(sx, sy, ex, ey, /*proportional*/ true,
                CycleMethod.NO_CYCLE, FADE), null, null)));
        return r;
    }

    private static Region radialRow(double w, double h) {
        Region r = new Region();
        r.setPrefSize(w, h);
        r.setMinSize(w, h);
        r.setBackground(new Background(new BackgroundFill(
            new RadialGradient(0, 0, 0.5, 0.5, 0.5, /*proportional*/ true,
                CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#ff3b30")),
                new Stop(1, Color.web("#34c759"))), null, null)));
        return r;
    }

    private static Region gradientText() {
        Text t = new Text("Gradient Text");
        t.setFont(Font.font("Arial", 48));
        t.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
            new Stop(0, Color.web("#8e2de2")),
            new Stop(1, Color.web("#4a00e0"))));
        HBox box = new HBox(t);
        box.setMinHeight(60);
        return box;
    }

    /** The exact reported scenario: gradient HBox whose opacity is toggled. */
    private static VBox opacityDemo() {
        HBox button = new HBox();
        button.setAlignment(Pos.CENTER_RIGHT);
        button.setPrefSize(400, 60);
        button.setMinSize(400, 60);
        button.setBackground(new Background(new BackgroundFill(
            new LinearGradient(1, 0, 0, 0, true, CycleMethod.NO_CYCLE, FADE),
            null, null)));
        button.setOpacity(1.0);

        Button toggle = new Button("Toggle opacity (0 ↔ 1)");
        toggle.setOnAction(e ->
            button.setOpacity(button.getOpacity() > 0.5 ? 0.0 : 1.0));

        Label l = new Label("Reported case: gradient HBox + node opacity — fade must stay smooth at any opacity");
        l.setFont(Font.font("Consolas", 12));
        return new VBox(4, l, button, toggle);
    }

    @Override
    public void init() throws Exception {
        super.init();
        Application.setGpuBackend(GpuBackend.DIRECT3D12);
    }

    public static void main(String[] args) {
        // GPU backend selector: -Ddemo.backend=AUTO|OPENGL|DIRECT3D12|METAL|VULKAN
        // (forwarded from -Pdemo.backend by the Gradle run task). Default AUTO.
        String b = System.getProperty("demo.backend");
        if (b != null && !b.isBlank()) {
            setGpuBackend(GpuBackend.valueOf(b.trim().toUpperCase()));
            System.out.println("[demo] requested GPU backend = " + getGpuBackend());
        }
        launch(args);
    }
}
