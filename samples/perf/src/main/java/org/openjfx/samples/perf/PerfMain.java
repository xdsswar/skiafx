package org.openjfx.samples.perf;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Duration;

import com.sun.prism.skia.SkiaPresentable;
import com.sun.prism.skia.impl.Copies;
import com.sun.prism.skia.impl.PaintStats;

/**
 * Rendering-pipeline benchmark scenes. One scene per run, selected with
 * {@code -Dperf.scene=}; emits one JSON line per second to stdout (and
 * optionally a file) for trending.
 *
 * <p>Scenes:</p>
 * <ul>
 *   <li>{@code static}  — control-heavy form, idle except a blinking text
 *       caret. Measures present overhead at tiny dirty rects.</li>
 *   <li>{@code scroll}  — continuously scrolling list. Large dirty rect
 *       every frame.</li>
 *   <li>{@code fade}    — large group under an infinite fade. The
 *       subtree-cache target case.</li>
 *   <li>{@code full}    — full-window animated background + moving nodes.
 *       Worst case: everything repaints every frame.</li>
 * </ul>
 *
 * <p>Flags:</p>
 * <ul>
 *   <li>{@code -Dperf.scene=static|scroll|fade|full} (default {@code static})</li>
 *   <li>{@code -Dperf.seconds=N} — auto-exit after N seconds of samples
 *       (default 20; {@code 0} = run until closed)</li>
 *   <li>{@code -Dperf.out=path} — also append JSON lines to this file</li>
 *   <li>{@code -Dperf.width / -Dperf.height} — scene size (default 1920×1080)</li>
 * </ul>
 *
 * <p>Run WITHOUT {@code -Dskia.verbose}: the verbose FPS logger consumes
 * the {@link Copies} counters ({@code sumThenReset}) and would race this
 * harness for them. The summary line at exit reports the median present
 * fps over the steady window (warm-up samples dropped).</p>
 */
public final class PerfMain extends Application<Stage> {

    /** Required by the JavaFX launcher. */
    public PerfMain() { }

    private static final String SCENE   = System.getProperty("perf.scene", "static");
    private static final int    SECONDS = Integer.getInteger("perf.seconds", 20);
    private static final String OUT     = System.getProperty("perf.out");
    private static final int    WIDTH   = Integer.getInteger("perf.width", 1920);
    private static final int    HEIGHT  = Integer.getInteger("perf.height", 1080);
    private static final int    WARMUP_SECONDS = 3;

    private final List<Double> steadyFps = new ArrayList<>();
    private PrintWriter fileOut;

    @Override
    public void start(Stage stage) {
        Parent root = switch (SCENE) {
            case "scroll" -> buildScroll();
            case "fade"   -> buildFade();
            case "full"   -> buildFull();
            case "static" -> buildStatic();
            default -> throw new IllegalArgumentException(
                "unknown -Dperf.scene=" + SCENE + " (static|scroll|fade|full)");
        };

        if (OUT != null) {
            try {
                fileOut = new PrintWriter(Files.newBufferedWriter(
                    Path.of(OUT), StandardCharsets.UTF_8));
            } catch (IOException e) {
                System.err.println("[perf] cannot open -Dperf.out=" + OUT + ": " + e);
            }
        }

        stage.setTitle("skia-fx perf: " + SCENE);
        stage.setScene(new Scene(root, WIDTH, HEIGHT));
        stage.show();

        startTelemetry();
    }

    // ---- telemetry ------------------------------------------------------

    private void startTelemetry() {
        AnimationTimer timer = new AnimationTimer() {
            private long windowStart;
            private int  pulses;
            private int  second;

            @Override public void handle(long now) {
                pulses++;
                if (windowStart == 0) { windowStart = now; return; }
                if (now - windowStart < 1_000_000_000L) return;

                double pulseHz = pulses * 1_000_000_000.0 / (now - windowStart);
                emitSample(++second, pulseHz);
                pulses = 0;
                windowStart = now;

                if (SECONDS > 0 && second >= WARMUP_SECONDS + SECONDS) {
                    stop();
                    finish();
                }
            }
        };
        timer.start();
    }

    private void emitSample(int second, double pulseHz) {
        double presentFps = SkiaPresentable.LAST_PRESENT_FPS;
        double paintsSec  = PaintStats.LAST_PAINTS_PER_SEC;
        double paintMs    = PaintStats.LAST_PAINT_AVG_MS;
        Map<Copies.Category, Long> copies = Copies.snapshot();

        boolean steady = second > WARMUP_SECONDS;
        if (steady) steadyFps.add(presentFps);

        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"scene\":\"").append(SCENE)
          .append("\",\"sec\":").append(second)
          .append(",\"warmup\":").append(!steady)
          .append(",\"pulseHz\":").append(fmt(pulseHz))
          .append(",\"presentFps\":").append(fmt(presentFps))
          .append(",\"paintsPerSec\":").append(fmt(paintsSec))
          .append(",\"paintAvgMs\":").append(fmt(paintMs))
          .append(",\"copies\":{");
        boolean first = true;
        for (Map.Entry<Copies.Category, Long> e : copies.entrySet()) {
            if (e.getValue() == 0L) continue;
            if (!first) sb.append(',');
            sb.append('"').append(e.getKey().name()).append("\":")
              .append(e.getValue());
            first = false;
        }
        sb.append("}}");
        String line = sb.toString();
        System.out.println(line);
        if (fileOut != null) fileOut.println(line);
    }

    private void finish() {
        steadyFps.sort(null);
        double median = steadyFps.isEmpty() ? 0
            : steadyFps.get(steadyFps.size() / 2);
        double avg = steadyFps.stream().mapToDouble(Double::doubleValue)
            .average().orElse(0);
        String summary = String.format(Locale.ROOT,
            "{\"scene\":\"%s\",\"summary\":true,\"samples\":%d,"
            + "\"medianPresentFps\":%s,\"avgPresentFps\":%s}",
            SCENE, steadyFps.size(), fmt(median), fmt(avg));
        System.out.println(summary);
        if (fileOut != null) {
            fileOut.println(summary);
            fileOut.close();
        }
        Platform.exit();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ---- scene: static (control-heavy, blinking caret only) -------------

    private Parent buildStatic() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(20));
        String[] labels = { "Name", "Address", "City", "State", "Zip",
            "Phone", "Email", "Company", "Title", "Notes", "Tags", "Id" };
        for (int i = 0; i < labels.length; i++) {
            grid.add(new Label(labels[i] + ":"), 0, i);
            TextField tf = new TextField("value " + i);
            tf.setPrefColumnCount(24);
            grid.add(tf, 1, i);
            grid.add(new CheckBox("flag " + i), 2, i);
        }
        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(
            "Alpha", "Beta", "Gamma", "Delta"));
        combo.getSelectionModel().selectFirst();
        Slider slider = new Slider(0, 100, 42);
        HBox controls = new HBox(12, new Button("OK"), new Button("Cancel"),
            new Button("Apply"), combo, slider);
        controls.setPadding(new Insets(0, 20, 20, 20));

        ListView<String> list = new ListView<>();
        for (int i = 0; i < 100; i++) list.getItems().add("List row " + i);
        list.setPrefHeight(300);

        TextField focused = new TextField();
        focused.setPromptText("focused — caret blinks here");
        HBox caretRow = new HBox(12, new Label("Caret:"), focused);
        caretRow.setPadding(new Insets(0, 20, 10, 20));

        VBox root = new VBox(10, grid, caretRow, controls, list);
        // Caret blink = the only periodic dirty region in this scene.
        Platform.runLater(focused::requestFocus);
        return root;
    }

    // ---- scene: scroll (smoothly scrolling tall content) -----------------

    private Parent buildScroll() {
        VBox content = new VBox(2);
        for (int i = 0; i < 400; i++) {
            HBox row = new HBox(10,
                new Label(String.format("Row %04d", i)),
                new Button("Edit"),
                new CheckBox("active"),
                new Text("The quick brown fox jumps over the lazy dog " + i));
            row.setPadding(new Insets(4));
            row.setBackground(Background.fill(
                i % 2 == 0 ? Color.gray(0.95) : Color.WHITE));
            content.getChildren().add(row);
        }
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);

        Timeline scroll = new Timeline(
            new KeyFrame(Duration.ZERO,        new KeyValue(sp.vvalueProperty(), 0)),
            new KeyFrame(Duration.seconds(10), new KeyValue(sp.vvalueProperty(), 1)));
        scroll.setAutoReverse(true);
        scroll.setCycleCount(Animation.INDEFINITE);
        scroll.play();
        return sp;
    }

    // ---- scene: fade (large group under infinite fade) -------------------

    private Parent buildFade() {
        Group faded = new Group();
        for (int i = 0; i < 12; i++) {
            StackPane card = new StackPane();
            card.setPrefSize(180, 120);
            card.setLayoutX((i % 4) * 200 + 40);
            card.setLayoutY((i / 4) * 150 + 40);
            card.setBackground(new Background(new BackgroundFill(
                new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.hsb(i * 30, 0.6, 0.95)),
                    new Stop(1, Color.hsb(i * 30 + 40, 0.7, 0.7))),
                new CornerRadii(10), Insets.EMPTY)));
            Text t = new Text("Card " + i);
            t.setFont(Font.font(22));
            card.getChildren().add(t);
            faded.getChildren().add(card);
        }
        FadeTransition fade = new FadeTransition(Duration.seconds(1.2), faded);
        fade.setFromValue(1.0);
        fade.setToValue(0.2);
        fade.setAutoReverse(true);
        fade.setCycleCount(Animation.INDEFINITE);
        fade.play();

        Pane root = new Pane(faded);
        root.setBackground(Background.fill(Color.gray(0.2)));
        return root;
    }

    // ---- scene: full (everything repaints every frame) --------------------

    private Parent buildFull() {
        Pane root = new Pane();
        List<Rectangle> rects = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            Rectangle r = new Rectangle(60, 60, Color.hsb(i * 3, 0.7, 0.9));
            r.setArcWidth(14);
            r.setArcHeight(14);
            rects.add(r);
            root.getChildren().add(r);
        }
        AnimationTimer anim = new AnimationTimer() {
            @Override public void handle(long now) {
                double t = now / 1_000_000_000.0;
                // Full-window background change -> full-scene dirty region.
                root.setBackground(Background.fill(
                    Color.hsb((t * 40) % 360, 0.35, 0.85)));
                double w = root.getWidth(), h = root.getHeight();
                for (int i = 0; i < rects.size(); i++) {
                    Rectangle r = rects.get(i);
                    double phase = t + i * 0.21;
                    r.setX((0.5 + 0.45 * Math.sin(phase)) * (w - 60));
                    r.setY((0.5 + 0.45 * Math.cos(phase * 0.83)) * (h - 60));
                    r.setRotate(phase * 40 % 360);
                }
            }
        };
        anim.start();
        return root;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
