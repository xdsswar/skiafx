/*
 * PerfAB — automated A/B harness: skia-fx vs. stock OpenJFX (Prism).
 *
 * Uses ONLY stock JavaFX API so the identical class runs on both pipelines.
 * Runs a fixed sequence of scenes for a fixed time each, then exits, printing
 * one machine-readable line per scene:
 *
 *   [perfab] label=<l> scene=<s> fps=.. mean_ms=.. sd_ms=.. p99_ms=.. max_ms=..
 *            hitch_pct=.. heap_mb=.. ws_mb=.. private_mb=.. gpu_ded_mb=..
 *
 * Frame metrics come from AnimationTimer timestamps (pulse cadence). "hitch"
 * = a frame interval longer than 1.5x the scene median. Memory is sampled at
 * the end of each scene (after a GC) via a one-shot PowerShell query on
 * Windows (working set, private bytes, GPU dedicated usage); other platforms
 * report heap only.
 *
 * System properties:
 *   -Dperfab.seconds=8        seconds per scene (after a 2 s warm-up)
 *   -Dperfab.scenes=a,b,..    subset of: shapes,effects,text,controls,idle
 *   -Dperfab.label=stock      label written into each result line
 */
package org.openjfx.samples.ensemble;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Stage;

public final class PerfAB extends Application {

    private static final long WARMUP_NS = 2_000_000_000L;

    private Stage stage;
    private String label;
    private long sceneNs;
    private List<String> scenes;
    private int sceneIdx = -1;

    // Frame-interval capture, preallocated (no per-frame allocation).
    private final double[] intervals = new double[200_000];
    private int count;
    private long sceneStart, last;
    private Runnable sceneTick;
    private boolean switching;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        label = System.getProperty("perfab.label",
                System.getProperty("prism.order", "").toLowerCase(Locale.ROOT).contains("skia") ? "skia-fx" : "javafx");
        sceneNs = (long) (Double.parseDouble(System.getProperty("perfab.seconds", "8")) * 1e9);
        scenes = Arrays.asList(System.getProperty("perfab.scenes",
                "idle,shapes,effects,text,controls").split(","));
        stage.setTitle("PerfAB [" + label + "]");
        stage.setX(80);
        stage.setY(60);
        stage.show();

        new AnimationTimer() {
            @Override public void handle(long now) { onPulse(now); }
        }.start();
        nextScene(System.nanoTime());
    }

    private void nextScene(long now) {
        sceneIdx++;
        if (sceneIdx >= scenes.size()) {
            switching = true;
            Platform.exit();
            return;
        }
        String s = scenes.get(sceneIdx).trim();
        switching = true;   // setScene can re-enter a pulse (live resize repaint)
        sceneTick = null;
        Parent root = switch (s) {
            case "shapes"   -> shapes();
            case "effects"  -> effects();
            case "text"     -> text();
            default         -> controls();   // controls; idle = same UI, static
        };
        if ("idle".equals(s)) sceneTick = null;
        stage.setScene(new Scene(root, 1280, 800));
        count = 0;
        last = 0;
        sceneStart = System.nanoTime();
        switching = false;
    }

    private void onPulse(long now) {
        if (switching) return;
        if (sceneTick != null) sceneTick.run();
        long t = System.nanoTime() - sceneStart;
        if (t >= WARMUP_NS) {
            if (last != 0 && count < intervals.length) intervals[count++] = (now - last) / 1e6;
            last = now;
        }
        if (t >= WARMUP_NS + sceneNs) {
            report(scenes.get(sceneIdx).trim(), t - WARMUP_NS);
            nextScene(System.nanoTime());
        }
    }

    private void report(String scene, long measuredNs) {
        double[] v = Arrays.copyOf(intervals, count);
        double sum = 0;
        for (double x : v) sum += x;
        double fps = sum > 0 ? v.length * 1000.0 / sum : 0;
        double mean = 0, sd = 0, p99 = 0, max = 0, hitch = 0;
        if (v.length > 0) {
            for (double x : v) mean += x;
            mean /= v.length;
            for (double x : v) sd += (x - mean) * (x - mean);
            sd = Math.sqrt(sd / v.length);
            double[] s = v.clone();
            Arrays.sort(s);
            double med = s[s.length / 2];
            p99 = s[Math.min(s.length - 1, (int) Math.ceil(s.length * 0.99) - 1)];
            max = s[s.length - 1];
            int h = 0;
            for (double x : v) if (x > med * 1.5) h++;
            hitch = 100.0 * h / v.length;
        }
        System.gc();
        Runtime rt = Runtime.getRuntime();
        double heap = (rt.totalMemory() - rt.freeMemory()) / 1048576.0;
        String mem = processMemory();
        System.out.printf(Locale.ROOT,
                "[perfab] label=%s scene=%s fps=%.1f mean_ms=%.2f sd_ms=%.2f p99_ms=%.2f max_ms=%.2f hitch_pct=%.1f heap_mb=%.1f %s%n",
                label, scene, fps, mean, sd, p99, max, hitch, heap, mem);
        System.out.flush();
    }

    /** Windows-only: working set, private bytes, GPU dedicated usage (MB). */
    private static String processMemory() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return "";
        long pid = ProcessHandle.current().pid();
        String ps = "$ProgressPreference='SilentlyContinue';$p=Get-Process -Id " + pid + ";"
                + "$g=0; try { $g=((Get-Counter \"\\GPU Process Memory(pid_" + pid + "_*)\\Dedicated Usage\" -ErrorAction Stop).CounterSamples"
                + " | Measure-Object -Property CookedValue -Sum).Sum } catch {};"
                + "'ws_mb={0:F1} private_mb={1:F1} gpu_ded_mb={2:F1}' -f ($p.WorkingSet64/1MB),($p.PrivateMemorySize64/1MB),($g/1MB)";
        try {
            String enc = Base64.getEncoder().encodeToString(ps.getBytes(StandardCharsets.UTF_16LE));
            Process pr = new ProcessBuilder("powershell", "-NoProfile", "-EncodedCommand", enc)
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {
                String line, lastLine = "";
                while ((line = r.readLine()) != null) if (line.contains("ws_mb=")) lastLine = line.trim();
                pr.waitFor();
                return lastLine.replace(',', '.');
            }
        } catch (Exception e) {
            return "mem_err=" + e.getClass().getSimpleName();
        }
    }

    // ---------------------------------------------------------------- scenes

    private Parent shapes() {
        StressScene.Field field = new StressScene.Field();
        field.setPrefSize(1280, 800);
        field.setCount(3000);
        field.start();
        return new StackPane(field);
    }

    /** Cards with drop shadows + gradients, a blurred backdrop, slow motion. */
    private Parent effects() {
        FlowPane flow = new FlowPane(18, 18);
        flow.setPadding(new Insets(24));
        List<StackPane> cards = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            Rectangle bg = new Rectangle(170, 110);
            bg.setArcWidth(18);
            bg.setArcHeight(18);
            bg.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.hsb(i * 7.5, 0.55, 0.95)), new Stop(1, Color.hsb(i * 7.5 + 40, 0.75, 0.65))));
            bg.setEffect(new DropShadow(14, 0, 4, Color.rgb(0, 0, 0, 0.35)));
            Text t = new Text("Card " + i);
            t.setFont(Font.font(16));
            t.setFill(Color.WHITE);
            StackPane card = new StackPane(bg, t);
            cards.add(card);
            flow.getChildren().add(card);
        }
        Rectangle blob = new Rectangle(1280, 800, new RadialGradient(0, 0, 0.5, 0.5, 0.6, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#3a4cff")), new Stop(1, Color.web("#0b0f24"))));
        blob.setEffect(new GaussianBlur(30));
        StackPane root = new StackPane(blob, flow);
        double[] phase = {0};
        sceneTick = () -> {
            phase[0] += 0.02;
            for (int i = 0; i < cards.size(); i++) {
                StackPane c = cards.get(i);
                c.setTranslateY(Math.sin(phase[0] + i * 0.4) * 6);
                c.setRotate(Math.sin(phase[0] * 0.7 + i) * 3);
            }
        };
        return root;
    }

    /** Auto-scrolling list + a big changing text block (glyph-heavy). */
    private Parent text() {
        ObservableList<String> items = FXCollections.observableArrayList();
        for (int i = 0; i < 5000; i++) items.add("Row " + i + " — The quick brown fox jumps over the lazy dog " + (i * 31 % 997));
        ListView<String> list = new ListView<>(items);
        list.setPrefWidth(640);
        Text big = new Text();
        big.setFont(Font.font(13));
        big.setWrappingWidth(560);
        VBox right = new VBox(big);
        right.setPadding(new Insets(12));
        BorderPane root = new BorderPane(list);
        root.setRight(right);
        int[] row = {0};
        long[] frame = {0};
        StringBuilder sb = new StringBuilder();
        sceneTick = () -> {
            frame[0]++;
            row[0] = (row[0] + 1) % 4900;
            list.scrollTo(row[0]);
            if ((frame[0] & 3) == 0) {
                sb.setLength(0);
                for (int i = 0; i < 40; i++) sb.append("Line ").append(i).append(": value ").append((frame[0] * 13 + i * 7) % 10007).append('\n');
                big.setText(sb.toString());
            }
        };
        return root;
    }

    /** Typical business UI: table, form, buttons, progress animation. */
    private Parent controls() {
        TableView<Row> table = new TableView<>();
        for (int ci = 0; ci < 3; ci++) {
            final int k = ci;
            TableColumn<Row, String> col = new TableColumn<>(new String[] {"Name", "City", "Amount"}[k]);
            col.setCellValueFactory(cd -> new ReadOnlyStringWrapper(
                    k == 0 ? cd.getValue().name : k == 1 ? cd.getValue().city : cd.getValue().amount));
            col.setPrefWidth(180);
            table.getColumns().add(col);
        }
        for (int i = 0; i < 400; i++) table.getItems().add(new Row("Person " + i, "City " + (i % 37), String.valueOf(i * 13.5)));
        VBox form = new VBox(10);
        form.setPadding(new Insets(16));
        ProgressBar pb = new ProgressBar(0);
        pb.setPrefWidth(300);
        for (int i = 0; i < 8; i++) form.getChildren().add(new HBox(8, new Label("Field " + i), new TextField("value " + i)));
        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList("One", "Two", "Three"));
        combo.getSelectionModel().select(0);
        form.getChildren().addAll(new CheckBox("Option"), new Slider(0, 100, 40), combo, pb,
                new HBox(8, new Button("Save"), new Button("Cancel"), new Button("Help")));
        BorderPane root = new BorderPane(table);
        root.setRight(form);
        sceneTick = () -> pb.setProgress((pb.getProgress() + 0.004) % 1.0);
        return root;
    }

    public static final class Row {
        private final String name, city, amount;
        public Row(String n, String c, String a) { name = n; city = c; amount = a; }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
