/*
 * StressScene — a deterministic, animated particle field plus a ready-made
 * benchmark panel (slider-controlled node count + live FPS / frame-time
 * metrics).
 *
 * IMPORTANT: this file is intentionally 100% stock JavaFX API — no
 * StageStyle.CUSTOM, no caption-region calls, no skia-fx-only types. That
 * keeps it byte-for-byte runnable against a vanilla OpenJFX 25 SDK, which
 * is what makes the A/B comparison (skia-fx vs. stock) honest: the exact
 * same scene and metering code runs on both pipelines.
 *
 * Both the in-app "Benchmark" section (ShowcaseController) and the
 * standalone ShowcaseBenchmark Application build their stress scene from
 * here, so there is a single source of truth for the workload.
 */
package org.openjfx.samples.ensemble;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

public final class StressScene {

    private StressScene() { }

    /** Hard ceiling so the slider can't melt a weak GPU. */
    public static final int MAX_NODES = 20_000;

    // ------------------------------------------------------------------
    // Field — the animated particle cloud.
    // ------------------------------------------------------------------

    /**
     * A {@link Pane} of unmanaged shapes bouncing around the bounds, driven
     * by a single {@link AnimationTimer} using wall-clock delta time (so the
     * motion is identical at 60 Hz or 240 Hz — a requirement of the uncapped
     * frame-rate policy).
     */
    public static final class Field extends Pane {

        /** Per-frame callback, used by the benchmark to meter render rate. */
        public interface FrameListener { void onFrame(long nowNanos, double dtSec); }

        private final Random rng = new Random(0xBADC0DE);
        private final ArrayList<Node> nodes = new ArrayList<>();
        private double[] vx = new double[16];   // px/sec, parallel to nodes
        private double[] vy = new double[16];
        private double[] spin = new double[16];  // deg/sec for rectangles

        private AnimationTimer timer;
        private long last;
        private FrameListener frameListener;

        public Field() {
            setMinSize(0, 0);
            setPrefSize(640, 420);
            getStyleClass().add("stress-field");
            // Clip so particles never paint outside the card.
            Rectangle clip = new Rectangle();
            clip.widthProperty().bind(widthProperty());
            clip.heightProperty().bind(heightProperty());
            setClip(clip);
        }

        public void setFrameListener(FrameListener l) { this.frameListener = l; }

        public int getCount() { return nodes.size(); }

        /** Grow or shrink the particle population to exactly {@code n}. */
        public void setCount(int n) {
            n = Math.clamp(n, 0, MAX_NODES);
            while (nodes.size() < n) addOne();
            while (nodes.size() > n) removeOne();
        }

        private void ensureCap(int need) {
            if (vx.length >= need) return;
            int cap = vx.length * 2;
            while (cap < need) cap *= 2;
            vx   = Arrays.copyOf(vx, cap);
            vy   = Arrays.copyOf(vy, cap);
            spin = Arrays.copyOf(spin, cap);
        }

        private void addOne() {
            int i = nodes.size();
            ensureCap(i + 1);

            double w = Math.max(80, getWidth());
            double h = Math.max(80, getHeight());
            Color c = Color.hsb(rng.nextDouble() * 360, 0.62, 0.96, 0.92);

            Node node;
            int kind = i % 3;
            if (kind == 0) {
                double r = 3 + rng.nextDouble() * 4;
                Circle circ = new Circle(r, c);
                node = circ;
            } else if (kind == 1) {
                double s = 6 + rng.nextDouble() * 8;
                Rectangle rect = new Rectangle(s, s, c);
                rect.setArcWidth(s * 0.5);
                rect.setArcHeight(s * 0.5);
                node = rect;
            } else {
                double r = 2.5 + rng.nextDouble() * 3.5;
                Circle ring = new Circle(r);
                ring.setFill(null);
                ring.setStroke(c);
                ring.setStrokeWidth(2);
                node = ring;
            }
            node.setManaged(false);              // keep our layoutX/Y, no relayout
            node.setLayoutX(rng.nextDouble() * w);
            node.setLayoutY(rng.nextDouble() * h);

            double ang = rng.nextDouble() * Math.PI * 2;
            double sp  = 45 + rng.nextDouble() * 135;   // px/sec
            vx[i]   = Math.cos(ang) * sp;
            vy[i]   = Math.sin(ang) * sp;
            spin[i] = (rng.nextDouble() - 0.5) * 220;

            nodes.add(node);
            getChildren().add(node);
        }

        private void removeOne() {
            int i = nodes.size() - 1;
            getChildren().remove(nodes.remove(i));
        }

        public void start() {
            if (timer != null) return;
            last = 0;
            timer = new AnimationTimer() {
                @Override public void handle(long now) {
                    step(now);
                }
            };
            timer.start();
        }

        public void stop() {
            if (timer != null) { timer.stop(); timer = null; }
        }

        private void step(long now) {
            if (last == 0) { last = now; return; }
            double dt = (now - last) / 1_000_000_000.0;
            last = now;
            if (dt > 0.05) dt = 0.05;            // clamp after a stall

            double w = getWidth(), h = getHeight();
            if (w > 0 && h > 0) {
                int n = nodes.size();
                for (int i = 0; i < n; i++) {     // index loop — no iterator alloc
                    Node node = nodes.get(i);
                    double x = node.getLayoutX() + vx[i] * dt;
                    double y = node.getLayoutY() + vy[i] * dt;
                    if (x < 0)      { x = 0;  vx[i] = -vx[i]; }
                    else if (x > w) { x = w;  vx[i] = -vx[i]; }
                    if (y < 0)      { y = 0;  vy[i] = -vy[i]; }
                    else if (y > h) { y = h;  vy[i] = -vy[i]; }
                    node.setLayoutX(x);
                    node.setLayoutY(y);
                    if (spin[i] != 0) node.setRotate(node.getRotate() + spin[i] * dt);
                }
            }
            if (frameListener != null) frameListener.onFrame(now, dt);
        }
    }

    // ------------------------------------------------------------------
    // BenchPanel — field + controls + live metrics, fully self-contained.
    // ------------------------------------------------------------------

    /**
     * Drop-in benchmark surface: a node-count slider and a live readout of
     * FPS, instantaneous frame time, rolling average and p99. Call
     * {@link #start()} when shown and {@link #stop()} when hidden.
     */
    public static final class BenchPanel extends VBox {

        private final Field field = new Field();

        private final Label fpsValue   = metric("—");
        private final Label msValue    = metric("—");
        private final Label avgValue   = metric("—");
        private final Label p99Value   = metric("—");
        private final Label countValue = metric("—");

        // Rolling window of recent frame times (ms). Preallocated — no
        // per-frame allocation; we only allocate a copy when we sort (≈2 Hz).
        private final double[] ring = new double[256];
        private int ringIdx = 0, ringFill = 0;

        private int frames = 0;
        private long windowStart = 0;
        private long firstNanos = 0;
        private double lastReportedFps = 0;

        private final String pipeline;
        private final Recorder recorder;

        public BenchPanel(int initialCount) {
            this(initialCount, defaultLabel());
        }

        public BenchPanel(int initialCount, String pipelineLabel) {
            this.pipeline = pipelineLabel;
            this.recorder = Recorder.fromSystemProperties(pipelineLabel);
            getStyleClass().add("section");
            setSpacing(16);
            setFillWidth(true);

            Label title = new Label("Stress benchmark");
            title.getStyleClass().add("section-title");
            String subText = "Same scene + metering runs on stock OpenJFX — compare the numbers.";
            if (recorder != null) subText += "  Logging → " + recorder.path();
            Label sub = new Label(subText);
            sub.getStyleClass().add("section-sub");

            Slider slider = new Slider(100, 8000, initialCount);
            slider.setMajorTickUnit(2000);
            slider.setMinorTickCount(3);
            slider.setBlockIncrement(200);
            slider.setShowTickMarks(true);
            HBox.setHgrow(slider, Priority.ALWAYS);
            slider.valueProperty().addListener((o, ov, nv) -> {
                int c = (int) Math.round(nv.doubleValue());
                field.setCount(c);
                countValue.setText(Integer.toString(field.getCount()));
            });

            Label sliderCaption = new Label("Nodes");
            sliderCaption.getStyleClass().add("card-sub");

            HBox sliderRow = new HBox(14, sliderCaption, slider);
            sliderRow.setAlignment(Pos.CENTER_LEFT);

            HBox metrics = new HBox(10,
                    metricBox("FPS", fpsValue),
                    metricBox("Frame", msValue),
                    metricBox("Avg", avgValue),
                    metricBox("p99", p99Value),
                    metricBox("Nodes", countValue));
            metrics.setAlignment(Pos.CENTER_LEFT);

            VBox controls = new VBox(12, metrics, sliderRow);
            controls.getStyleClass().add("card");
            controls.setPadding(new Insets(16));

            StackPane fieldCard = new StackPane(field);
            fieldCard.getStyleClass().add("card");
            fieldCard.setPadding(new Insets(4));
            VBox.setVgrow(fieldCard, Priority.ALWAYS);

            getChildren().addAll(title, sub, controls, fieldCard);

            field.setCount(initialCount);
            countValue.setText(Integer.toString(field.getCount()));
            field.setFrameListener(this::onFrame);
        }

        public void start() { field.start(); }
        public void stop()  { field.stop(); if (recorder != null) recorder.flush(); }

        /** Flush + close the metrics file (call on app shutdown). */
        public void close() { if (recorder != null) recorder.close(); }

        /** Rolling FPS at the last 500 ms window — used by the stdout summary. */
        public double lastFps() { return lastReportedFps; }
        public int nodeCount()  { return field.getCount(); }

        private void onFrame(long now, double dtSec) {
            double ms = dtSec * 1000.0;
            ring[ringIdx] = ms;
            ringIdx = (ringIdx + 1) % ring.length;
            if (ringFill < ring.length) ringFill++;

            frames++;
            if (firstNanos == 0) firstNanos = now;
            if (windowStart == 0) { windowStart = now; return; }
            long elapsed = now - windowStart;
            if (elapsed < 500_000_000L) return;

            double fps = frames * 1_000_000_000.0 / elapsed;
            lastReportedFps = fps;
            fpsValue.setText(String.format("%.0f", fps));
            msValue.setText(String.format("%.2f", ms));

            double[] copy = Arrays.copyOf(ring, ringFill);
            Arrays.sort(copy);
            double sum = 0;
            for (double v : copy) sum += v;
            double avg = copy.length == 0 ? 0 : sum / copy.length;
            int p99i = copy.length == 0 ? 0 : (int) Math.min(copy.length - 1, Math.ceil(copy.length * 0.99) - 1);
            double p99 = copy.length == 0 ? 0 : copy[Math.max(0, p99i)];
            avgValue.setText(String.format("%.2f", avg));
            p99Value.setText(String.format("%.2f", p99));

            if (recorder != null) {
                long tMs = (now - firstNanos) / 1_000_000L;
                recorder.write(tMs, fps, ms, avg, p99, field.getCount());
            }

            windowStart = now;
            frames = 0;
        }

        private static String defaultLabel() {
            // -Dshowcase.bench.label wins; otherwise sniff the active pipeline.
            String l = System.getProperty("showcase.bench.label");
            if (l != null && !l.isBlank()) return l;
            String order = System.getProperty("prism.order", "");
            return order.toLowerCase().contains("skia") ? "skia-fx" : "javafx";
        }

        private static Label metric(String t) {
            Label l = new Label(t);
            l.getStyleClass().add("kpi-value");
            l.setStyle("-fx-font-size: 22px;");
            return l;
        }

        private static Region metricBox(String caption, Label value) {
            Label c = new Label(caption);
            c.getStyleClass().add("kpi-label");
            VBox b = new VBox(2, value, c);
            b.setAlignment(Pos.CENTER_LEFT);
            b.setMinWidth(96);
            return b;
        }
    }

    // ------------------------------------------------------------------
    // Recorder — appends one CSV row per metering window (~2 Hz).
    // ------------------------------------------------------------------

    /**
     * Tiny CSV logger so two runs (skia-fx vs. stock OpenJFX) can be charted
     * side by side afterwards. Plain {@code java.nio} only, so it works on a
     * stock SDK as well.
     *
     * <p>Controlled by system properties:</p>
     * <ul>
     *   <li>{@code -Dshowcase.bench.out=path.csv} — output file. Default:
     *       {@code build/bench/showcase-metrics-<label>.csv}.</li>
     *   <li>{@code -Dshowcase.bench.record=false} — disable logging entirely.</li>
     * </ul>
     * Columns: {@code t_ms,fps,frame_ms,avg_ms,p99_ms,nodes,pipeline}.
     */
    public static final class Recorder {

        private final Path path;
        private final String pipeline;
        private BufferedWriter out;
        private boolean failed = false;

        private Recorder(Path path, String pipeline) {
            this.path = path;
            this.pipeline = pipeline;
        }

        /** Returns a Recorder, or {@code null} when logging is disabled. */
        static Recorder fromSystemProperties(String pipeline) {
            if ("false".equalsIgnoreCase(System.getProperty("showcase.bench.record", "true"))) {
                return null;
            }
            String safe = pipeline == null ? "run" : pipeline.replaceAll("[^a-zA-Z0-9._-]", "_");
            String custom = System.getProperty("showcase.bench.out");
            Path p = (custom != null && !custom.isBlank())
                    ? Path.of(custom)
                    : Path.of("build", "bench", "showcase-metrics-" + safe + ".csv");
            return new Recorder(p.toAbsolutePath(), pipeline);
        }

        Path path() { return path; }

        private synchronized void open() {
            if (out != null || failed) return;
            try {
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                boolean fresh = !Files.exists(path) || Files.size(path) == 0;
                out = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (fresh) {
                    out.write("t_ms,fps,frame_ms,avg_ms,p99_ms,nodes,pipeline");
                    out.newLine();
                }
            }
            catch (IOException e) {
                failed = true;
                System.err.println("[StressScene] metrics logging disabled: " + e.getMessage());
            }
        }

        synchronized void write(long tMs, double fps, double frameMs, double avgMs, double p99Ms, int nodes) {
            open();
            if (out == null) return;
            try {
                out.write(String.format("%d,%.2f,%.3f,%.3f,%.3f,%d,%s%n", tMs, fps, frameMs, avgMs, p99Ms, nodes, pipeline));
            }
            catch (IOException e) {
                failed = true;
            }
        }

        synchronized void flush() {
            if (out != null) { try { out.flush(); } catch (IOException ignored) { } }
        }

        synchronized void close() {
            if (out != null) { try { out.close(); } catch (IOException ignored) { } out = null; }
        }
    }
}
