/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package org.openjfx.samples.ensemble;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.Background;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;

/**
 * CPU rendering-path demo — a typical desktop-application workload sized
 * for the software-raster tier, not a GPU stress scene.
 *
 * <p>Launch via {@code :samples:ensemble:runCpuDemo}, which sets
 * {@code -Dprism.skia.gpu=false} so every GPU tier (D3D12 swap chain, GL
 * direct, GPU readback) is skipped and the whole frame is rasterized by
 * Skia on the CPU, then presented through the readback blit. The pulse is
 * capped at ~60 fps on this path by QuantumToolkit, so a healthy run
 * shows the FPS meter pinned near the cap while the animation tab is
 * active, and paints stopping entirely when "Animate" is unchecked
 * (adaptive cadence: idle scene → no paints).</p>
 *
 * <p>The scene mixes the workloads a real app puts on the raster path:
 * standard controls, styled text, gradient/vector shapes, a Canvas, and a
 * light wall-clock-driven animation that dirties the full window each
 * frame.</p>
 */
public final class CpuDemo extends Application {

    private static final int BALL_COUNT = 16;

    private final Label fpsLabel = new Label("fps: --");
    private long frames;
    private long windowStartNs;

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setTop(buildHeader());
        root.setCenter(buildTabs());
        root.setBottom(buildStatusBar());

        stage.setTitle("skia-fx — CPU rendering path demo");
        stage.setScene(new Scene(root, 1100, 720));
        stage.show();
    }

    // ---- header: title + live FPS ----------------------------------------

    private Region buildHeader() {
        Label title = new Label("CPU demo — software raster");
        title.setFont(Font.font("System", FontWeight.BOLD, 18));
        title.setTextFill(Color.WHITE);

        fpsLabel.setFont(Font.font("Consolas", FontWeight.BOLD, 16));
        fpsLabel.setTextFill(Color.web("#9be89b"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(12, title, spacer, fpsLabel);
        header.setPadding(new Insets(12, 16, 12, 16));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setBackground(Background.fill(
            new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#1e3a2f")),
                new Stop(1, Color.web("#14532d")))));
        return header;
    }

    // ---- tabs: controls / shapes / animation / text -----------------------

    private TabPane buildTabs() {
        TabPane tabs = new TabPane(
            animationTab(), controlsTab(), shapesTab(), textTab());
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        return tabs;
    }

    private Tab controlsTab() {
        TextField text = new TextField("type here");
        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().addAll("Skia raster", "Readback present", "62 fps cap");
        combo.getSelectionModel().selectFirst();

        ToggleGroup group = new ToggleGroup();
        RadioButton r1 = new RadioButton("RGBA surface");
        RadioButton r2 = new RadioButton("BGRA surface (-Dskia.raster.bgra)");
        r1.setToggleGroup(group);
        r2.setToggleGroup(group);
        r1.setSelected(true);

        Slider slider = new Slider(0, 100, 40);
        slider.setShowTickMarks(true);
        slider.setShowTickLabels(true);

        ProgressBar progress = new ProgressBar();
        progress.progressProperty().bind(slider.valueProperty().divide(100));

        VBox form = new VBox(14,
            new Label("Standard controls on the CPU raster path:"),
            text, combo, new CheckBox("A checkbox"), r1, r2,
            slider, progress, new Button("A button"));
        form.setPadding(new Insets(20));
        form.setMaxWidth(420);
        return new Tab("Controls", form);
    }

    private Tab shapesTab() {
        Pane pane = new Pane();
        // A grid of gradient-filled rounded rects + circles: pure vector
        // fills, the bread-and-butter of the SkCanvas raster backend.
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 4; j++) {
                Rectangle r = new Rectangle(40 + i * 165, 30 + j * 150, 140, 120);
                r.setArcWidth(24);
                r.setArcHeight(24);
                r.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.hsb((i * 60 + j * 15) % 360, 0.55, 0.95)),
                    new Stop(1, Color.hsb((i * 60 + j * 15 + 40) % 360, 0.75, 0.55))));
                r.setStroke(Color.web("#00000033"));
                Circle c = new Circle(110 + i * 165, 90 + j * 150, 28,
                    Color.web("#ffffff55"));
                pane.getChildren().addAll(r, c);
            }
        }
        return new Tab("Shapes", pane);
    }

    private Tab animationTab() {
        Pane balls = new Pane();
        balls.setBackground(Background.fill(
            new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#0f172a")),
                new Stop(1, Color.web("#1e293b")))));

        Circle[] circles = new Circle[BALL_COUNT];
        double[] vx = new double[BALL_COUNT];
        double[] vy = new double[BALL_COUNT];
        for (int i = 0; i < BALL_COUNT; i++) {
            Circle c = new Circle(60 + (i * 53) % 800, 60 + (i * 97) % 400,
                14 + (i % 4) * 6,
                Color.hsb(i * 360.0 / BALL_COUNT, 0.7, 0.95, 0.9));
            circles[i] = c;
            vx[i] = 60 + (i % 5) * 35;   // px/sec — wall-clock driven
            vy[i] = 50 + (i % 7) * 30;
            balls.getChildren().add(c);
        }

        Canvas canvas = new Canvas(260, 120);
        canvas.setLayoutX(16);
        canvas.setLayoutY(16);
        balls.getChildren().add(canvas);

        CheckBox animate = new CheckBox("Animate (uncheck: scene goes idle, paints stop)");
        animate.setSelected(true);
        animate.setTextFill(Color.WHITE);
        animate.setLayoutX(16);
        animate.setLayoutY(150);
        balls.getChildren().add(animate);

        AnimationTimer timer = new AnimationTimer() {
            private long lastNs;

            @Override public void handle(long now) {
                tickFps(now);
                if (lastNs == 0) { lastNs = now; return; }
                double dt = (now - lastNs) / 1_000_000_000.0;
                lastNs = now;
                if (!animate.isSelected()) return;

                double w = balls.getWidth(), h = balls.getHeight();
                for (int i = 0; i < BALL_COUNT; i++) {
                    Circle c = circles[i];
                    double x = c.getCenterX() + vx[i] * dt;
                    double y = c.getCenterY() + vy[i] * dt;
                    double r = c.getRadius();
                    if (x < r || x > w - r) { vx[i] = -vx[i]; x = Math.clamp(x, r, w - r); }
                    if (y < r || y > h - r) { vy[i] = -vy[i]; y = Math.clamp(y, r, h - r); }
                    c.setCenterX(x);
                    c.setCenterY(y);
                }
                drawWave(canvas.getGraphicsContext2D(), now);
            }
        };
        timer.start();
        return new Tab("Animation", balls);
    }

    /** Small Canvas workload: exercises the direct SkCanvas path each frame. */
    private static void drawWave(GraphicsContext g, long nowNs) {
        double w = g.getCanvas().getWidth(), h = g.getCanvas().getHeight();
        g.setFill(Color.web("#00000066"));
        g.fillRoundRect(0, 0, w, h, 12, 12);
        g.setStroke(Color.web("#38bdf8"));
        g.setLineWidth(2);
        double t = nowNs / 1_000_000_000.0;
        g.beginPath();
        for (int x = 0; x <= w - 20; x += 4) {
            double y = h / 2 + Math.sin(t * 2 + x * 0.05) * h * 0.3;
            if (x == 0) g.moveTo(10, y); else g.lineTo(10 + x, y);
        }
        g.stroke();
    }

    private Tab textTab() {
        ListView<String> list = new ListView<>();
        for (int i = 1; i <= 200; i++) {
            list.getItems().add("Row " + i
                + " — glyph runs rendered by the Skia CPU rasterizer");
        }
        Text heading = new Text("Text rendering");
        heading.setFont(Font.font("System", FontWeight.BOLD, 22));
        VBox box = new VBox(12, heading, list);
        box.setPadding(new Insets(16));
        return new Tab("Text", box);
    }

    // ---- status bar -------------------------------------------------------

    private Region buildStatusBar() {
        String gpuProp = System.getProperty("prism.skia.gpu", "(unset)");
        Label status = new Label("prism.skia.gpu=" + gpuProp
            + ("false".equalsIgnoreCase(gpuProp)
                ? "  →  software raster + readback present, pulse capped ~60 fps"
                : "  →  WARNING: GPU tiers active — launch via runCpuDemo"));
        status.setPadding(new Insets(6, 16, 6, 16));
        HBox bar = new HBox(status);
        bar.setBackground(Background.fill(Color.web("#e2e8f0")));
        return bar;
    }

    /** Once-a-second FPS meter (AnimationTimer fires once per pulse). */
    private void tickFps(long nowNs) {
        if (windowStartNs == 0) windowStartNs = nowNs;
        frames++;
        long elapsed = nowNs - windowStartNs;
        if (elapsed >= 1_000_000_000L) {
            double fps = frames * 1_000_000_000.0 / elapsed;
            fpsLabel.setText(String.format("fps: %.1f", fps));
            frames = 0;
            windowStartNs = nowNs;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
