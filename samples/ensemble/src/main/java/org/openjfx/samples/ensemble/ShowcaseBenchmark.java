/*
 * ShowcaseBenchmark — the portable A/B harness.
 *
 * Deliberately uses ONLY stock JavaFX API (plain DECORATED window, no
 * StageStyle.CUSTOM, no caption regions, no skia-fx additions). That means
 * this exact class compiles and runs against a vanilla OpenJFX 25 SDK, so
 * you can run the identical workload on both pipelines and diff the CSVs.
 *
 *   skia-fx :  ./gradlew :samples:ensemble:runBench
 *   stock   :  java --module-path <javafx-sdk-25>/lib \
 *                   --add-modules javafx.controls \
 *                   -Dshowcase.bench.label=stock \
 *                   -cp showcase-classes \
 *                   org.openjfx.samples.ensemble.ShowcaseBenchmark
 *
 * Both runs append rows to build/bench/showcase-metrics-<label>.csv
 * (columns: t_ms,fps,frame_ms,avg_ms,p99_ms,nodes,pipeline) — load both
 * into your charting tool of choice and compare.
 *
 * Optional system properties:
 *   -Dshowcase.bench.nodes=2000     initial particle count
 *   -Dshowcase.bench.label=stock    pipeline tag written into the CSV
 *   -Dshowcase.bench.out=path.csv   override the output file
 *   -Dshowcase.bench.record=false   disable CSV logging
 */
package org.openjfx.samples.ensemble;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public final class ShowcaseBenchmark extends Application {

    private StressScene.BenchPanel panel;

    @Override
    public void start(Stage stage) {
        int nodes = intProp("showcase.bench.nodes", 1500);
        String label = System.getProperty("showcase.bench.label",
                System.getProperty("prism.order", "").toLowerCase().contains("skia") ? "skia-fx" : "javafx");

        panel = new StressScene.BenchPanel(nodes, label);
        panel.setPadding(new Insets(20));

        BorderPane root = new BorderPane(panel);
        Scene scene = new Scene(root, 1100, 760);
        var css = ClassLoader.getSystemResource("showcase.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        stage.setTitle("skia-fx · Stress Benchmark [" + label + "]");
        stage.setScene(scene);
        stage.setOnHidden(e -> { panel.stop(); panel.close(); });
        stage.show();

        panel.start();
        startStdoutSummary(label);
    }

    /** Print a one-line FPS summary every 2s so headless/CI runs leave a trail. */
    private void startStdoutSummary(String label) {
        new AnimationTimer() {
            long last = 0;
            @Override public void handle(long now) {
                if (last == 0) { last = now; return; }
                if (now - last < 2_000_000_000L) return;
                last = now;
                System.out.printf("[bench:%s] %.0f fps @ %,d nodes%n",
                        label, panel.lastFps(), panel.nodeCount());
            }
        }.start();
    }

    private static int intProp(String key, int def) {
        try {
            String v = System.getProperty(key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
