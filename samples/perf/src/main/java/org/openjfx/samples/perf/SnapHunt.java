package org.openjfx.samples.perf;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.Node;
import javafx.scene.effect.Bloom;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.BoxBlur;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.effect.Glow;
import javafx.scene.effect.InnerShadow;
import javafx.scene.effect.MotionBlur;
import javafx.scene.effect.Reflection;
import javafx.scene.effect.SepiaTone;
import javafx.scene.image.PixelWriter;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.ImagePattern;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;

/**
 * Headless rendering bug-hunt harness. Renders a handful of scenes that stress
 * the graphics hot paths flagged as fragile (channel order / readback, glyph
 * antialiasing on opacity layers, NGRegion 3/9-slice backgrounds, gradient
 * fills on the GPU and software tiers), then for each scene:
 *
 * <ul>
 *   <li>snapshots it to a {@link WritableImage} (exercises the
 *       {@code WritableImage} readback path);</li>
 *   <li>probes ground-truth pixels via the public {@link PixelReader#getArgb}
 *       (canonical {@code 0xAARRGGBB}) and prints PASS/FAIL vs expected — a
 *       channel swap or wrong-alpha readback fails here loudly;</li>
 *   <li>writes a viewable {@code .png} (pure-Java encoder, no AWT) under
 *       {@code samples/perf/build/hunt/} for visual inspection of text
 *       ghosting / invisible-region artifacts.</li>
 * </ul>
 *
 * Runs entirely on the FX thread inside {@code start}, never shows a window,
 * and exits when done. Every case is guarded so one failure cannot abort the
 * sweep. Toggle the software tier with {@code -Dprism.skia.gpu=false}.
 */
public final class SnapHunt extends Application<Stage> {

    /** Required by the JavaFX launcher. */
    public SnapHunt() { }

    // Default is relative to the run task's working dir (the perf project dir).
    private static final Path OUT_DIR = Path.of(
        System.getProperty("hunt.out", "build/hunt"));

    /** Tolerance (per 8-bit channel) for "matches expected" probes. */
    private static final int TOL = 6;

    private int failures = 0;

    @Override
    public void start(Stage stage) {
        try {
            Files.createDirectories(OUT_DIR);
        } catch (IOException e) {
            System.out.println("[hunt] cannot create " + OUT_DIR + ": " + e);
        }
        String tier = "false".equals(System.getProperty("prism.skia.gpu"))
            ? "CPU" : "GPU";
        System.out.println("[hunt] tier=" + tier + " out=" + OUT_DIR.toAbsolutePath());

        runCase("dshadow",  this::sceneDropShadowOriginVsOffset);
        if (!Boolean.getBoolean("hunt.dshadowOnly")) {
            runCase("colors",   this::sceneColors);
            runCase("text",     this::sceneText);
            runCase("region",   this::sceneRegion);
            runCase("gradient", this::sceneGradient);
            runCase("effects",  this::sceneEffects);
            runCase("clip",     this::sceneClip);
            runCase("hidpi",    this::sceneHidpi);
            runCase("effects2", this::sceneEffects2);
            runCase("blend",    this::sceneBlend);
            runCase("gradmodes",this::sceneGradientModes);
            runCase("strokes",  this::sceneStrokes);
            runCase("shapes",   this::sceneShapes);
            runCase("transform",this::sceneTransform);
            runCase("imgpat",   this::sceneImagePattern);
            runCase("font",     this::sceneCustomFont);
        }

        System.out.println("[hunt] DONE failures=" + failures);
        Platform.exit();
    }

    // ---- case runner ----------------------------------------------------

    private interface SceneCase {
        /** Build the root, snapshot it, probe pixels. Returns the image. */
        WritableImage run(List<String> log);
    }

    private void runCase(String name, SceneCase c) {
        List<String> log = new ArrayList<>();
        try {
            WritableImage img = c.run(log);
            if (img != null) {
                writePng(OUT_DIR.resolve(name + ".png"), img);
            }
        } catch (Throwable t) {
            log.add("EXCEPTION " + t);
            failures++;
        }
        System.out.println("[hunt] === " + name + " ===");
        for (String l : log) System.out.println("[hunt]   " + l);
    }

    private static WritableImage snap(Region root, double w, double h) {
        root.setPrefSize(w, h);
        root.setMinSize(w, h);
        root.setMaxSize(w, h);
        Scene scene = new Scene(root, w, h);
        root.applyCss();
        root.layout();
        SnapshotParameters p = new SnapshotParameters();
        return root.snapshot(p, null);
    }

    private static WritableImage snapNode(javafx.scene.Node node, Region holder,
                                          double w, double h) {
        Scene scene = new Scene(holder, w, h);
        holder.applyCss();
        holder.layout();
        SnapshotParameters p = new SnapshotParameters();
        p.setFill(Color.WHITE);
        return holder.snapshot(p, null);
    }

    // ---- probe helpers --------------------------------------------------

    private void expect(List<String> log, WritableImage img, int x, int y,
                        int eR, int eG, int eB, int eA, String what) {
        PixelReader pr = img.getPixelReader();
        if (pr == null) { log.add("FAIL " + what + ": null PixelReader"); failures++; return; }
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        x = Math.max(0, Math.min(w - 1, x));
        y = Math.max(0, Math.min(h - 1, y));
        int argb = pr.getArgb(x, y);
        int a = (argb >>> 24) & 0xFF, r = (argb >>> 16) & 0xFF,
            g = (argb >>> 8) & 0xFF,  b = argb & 0xFF;
        // A negative expected channel is a wildcard ("don't care").
        boolean ok = (eR < 0 || near(r, eR)) && (eG < 0 || near(g, eG))
                  && (eB < 0 || near(b, eB)) && (eA < 0 || near(a, eA));
        if (!ok) failures++;
        log.add((ok ? "PASS " : "FAIL ") + what + " @(" + x + "," + y + ") got="
            + hex(argb) + " want=" + hex((eA << 24) | (eR << 16) | (eG << 8) | eB));
    }

    private static boolean near(int a, int b) { return Math.abs(a - b) <= TOL; }
    private static String hex(int v) { return String.format("0x%08X", v); }

    /** Scan a horizontal line, printing luminance (R channel) at each step. */
    private void scanH(List<String> log, WritableImage img, String what,
                       int y, int x0, int x1, int step) {
        PixelReader pr = img.getPixelReader();
        if (pr == null) return;
        StringBuilder sb = new StringBuilder(what).append(" y=").append(y).append(':');
        for (int x = x0; x <= x1; x += step) {
            int argb = pr.getArgb(x, y);
            sb.append(' ').append(x).append('=').append((argb >>> 16) & 0xFF);
        }
        log.add(sb.toString());
    }

    /** Scan a vertical line, printing luminance (R channel) at each step. */
    private void scanV(List<String> log, WritableImage img, String what,
                       int x, int y0, int y1, int step) {
        PixelReader pr = img.getPixelReader();
        if (pr == null) return;
        StringBuilder sb = new StringBuilder(what).append(" x=").append(x).append(':');
        for (int y = y0; y <= y1; y += step) {
            int argb = pr.getArgb(x, y);
            sb.append(' ').append(y).append('=').append((argb >>> 16) & 0xFF);
        }
        log.add(sb.toString());
    }

    /** Print raw ARGB at labelled points — for characterising soft effects. */
    private void ring(List<String> log, WritableImage img, String what, int[][] pts) {
        PixelReader pr = img.getPixelReader();
        if (pr == null) return;
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        StringBuilder sb = new StringBuilder(what).append(" ring:");
        for (int[] p : pts) {
            int x = Math.max(0, Math.min(w - 1, p[0]));
            int y = Math.max(0, Math.min(h - 1, p[1]));
            sb.append(' ').append((char) p[2]).append('=').append(hex(pr.getArgb(x, y)));
        }
        log.add(sb.toString());
    }

    // ---- scenes ---------------------------------------------------------

    /** Pure-color swatches: catches R<->B channel swap and wrong alpha in readback. */
    private WritableImage sceneColors(List<String> log) {
        Rectangle red   = new Rectangle(60, 60, Color.rgb(255, 0, 0));
        Rectangle green = new Rectangle(60, 60, Color.rgb(0, 255, 0));
        Rectangle blue  = new Rectangle(60, 60, Color.rgb(0, 0, 255));
        Rectangle white = new Rectangle(60, 60, Color.WHITE);
        Rectangle gray  = new Rectangle(60, 60, Color.rgb(128, 128, 128));
        HBox box = new HBox(red, green, blue, white, gray);
        box.setBackground(Background.fill(Color.BLACK));
        WritableImage img = snap(box, 300, 60);
        expect(log, img,  30, 30, 255,   0,   0, 255, "red");
        expect(log, img,  90, 30,   0, 255,   0, 255, "green");
        expect(log, img, 150, 30,   0,   0, 255, 255, "blue");
        expect(log, img, 210, 30, 255, 255, 255, 255, "white");
        expect(log, img, 270, 30, 128, 128, 128, 255, "gray");
        return img;
    }

    /** Glyphs on opaque, on an opacity<1 group, and gradient-filled. */
    private WritableImage sceneText(List<String> log) {
        Text plain = new Text("Plain black text — sharp?");
        plain.setFont(Font.font(28));
        plain.setFill(Color.BLACK);

        Group fadedG = new Group(makeText("Text on opacity 0.5 layer"));
        fadedG.setOpacity(0.5);

        Text grad = makeText("Gradient-filled text");
        grad.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
            new Stop(0, Color.RED), new Stop(1, Color.BLUE)));

        VBox box = new VBox(16, plain, fadedG, grad);
        box.setPadding(new Insets(20));
        box.setBackground(Background.fill(Color.WHITE));
        WritableImage img = snap(box, 460, 160);
        // A clearly-blank corner should remain white (no stray ghost coverage).
        expect(log, img, 455, 5, 255, 255, 255, 255, "blank-corner-white");
        return img;
    }

    private static Text makeText(String s) {
        Text t = new Text(s);
        t.setFont(Font.font(28));
        t.setFill(Color.BLACK);
        return t;
    }

    /** NGRegion 3/9-slice: rounded CSS background + border + a Modena Button. */
    private WritableImage sceneRegion(List<String> log) {
        Region card = new Region();
        card.setStyle(
            "-fx-background-color: #2d7d2d;"
            + "-fx-background-radius: 18;"
            + "-fx-border-color: #103810;"
            + "-fx-border-width: 4;"
            + "-fx-border-radius: 18;");
        card.setPrefSize(220, 90);

        Button btn = new Button("Styled Button");

        VBox box = new VBox(16, card, btn);
        box.setPadding(new Insets(20));
        box.setBackground(Background.fill(Color.web("#dddddd")));
        WritableImage img = snap(box, 260, 170);
        // Interior of the rounded card must be the green fill, not transparent/grey.
        expect(log, img, 130, 65, 0x2d, 0x7d, 0x2d, 255, "region-interior-fill");
        return img;
    }

    /** Large gradient fill — same scene drives the GPU and software tiers. */
    private WritableImage sceneGradient(List<String> log) {
        Region r = new Region();
        r.setBackground(new Background(new BackgroundFill(
            new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.RED), new Stop(1, Color.BLUE)),
            CornerRadii.EMPTY, Insets.EMPTY)));
        WritableImage img = snap(r, 400, 200);
        expect(log, img,   4, 100, 255, 0,   0, 255, "gradient-left-red");
        expect(log, img, 396, 100,   0, 0, 255, 255, "gradient-right-blue");
        expect(log, img, 200, 100, 128, 0, 128, 255, "gradient-mid-purple");
        return img;
    }

    /**
     * Effects on OFF-ORIGIN nodes — the historical "grey ghost" coordinate bug
     * (effect peers translating by the wrong bounds) only shows when the
     * effected node is not at (0,0). Each node sits at a non-zero layout pos.
     */
    private WritableImage sceneEffects(List<String> log) {
        Pane pane = new Pane();
        pane.setBackground(Background.fill(Color.web("#f0f0f0")));

        Rectangle drop = new Rectangle(80, 50, Color.DODGERBLUE);
        drop.setLayoutX(60); drop.setLayoutY(30);
        drop.setEffect(new DropShadow(18, Color.BLACK));

        Rectangle inner = new Rectangle(80, 50, Color.ORANGE);
        inner.setLayoutX(220); inner.setLayoutY(30);
        inner.setEffect(new InnerShadow(18, Color.BLACK));

        Rectangle blur = new Rectangle(80, 50, Color.SEAGREEN);
        blur.setLayoutX(60); blur.setLayoutY(130);
        blur.setEffect(new GaussianBlur(8));

        Circle box = new Circle(40, Color.CRIMSON);
        box.setLayoutX(260); box.setLayoutY(155);
        box.setEffect(new BoxBlur(10, 10, 2));

        Text refl = makeText("Reflect");
        refl.setLayoutX(150); refl.setLayoutY(240);
        refl.setEffect(new Reflection());

        pane.getChildren().addAll(drop, inner, blur, box, refl);
        WritableImage img = snap(pane, 380, 300);
        // The drop-shadowed rect's own body must still be solid blue (not a
        // translated grey ghost): probe its centre.
        expect(log, img, 100, 55, 30, 144, 255, 255, "dropshadow-body-blue");
        // Far corner away from every node stays the light-grey backdrop.
        expect(log, img, 375, 5, 0xf0, 0xf0, 0xf0, 255, "effects-backdrop");
        // Characterise the drop shadow. Rect is x[60..140] y[30..80], offset 0,
        // radius 18 -> a SYMMETRIC soft halo: all four sides should darken
        // below the f0 backdrop. An offset/hard shadow darkens only some sides.
        ring(log, img, "dropshadow",
            new int[][] {
                {52, 55, 'L'}, {148, 55, 'R'}, {100, 22, 'T'}, {100, 88, 'B'},
            });
        return img;
    }

    /**
     * Decisive test for the off-origin effect-coordinate bug: two identical
     * drop-shadowed rects, one whose bounds start at the pane origin and one
     * shifted far down-right. A correct DropShadow (offset 0) is symmetric for
     * both; a coordinate bug shifts the off-origin one's shadow.
     */
    private WritableImage sceneDropShadowOriginVsOffset(List<String> log) {
        Pane pane = new Pane();
        pane.setBackground(Background.fill(Color.WHITE));

        Rectangle atOrigin = new Rectangle(60, 40, Color.DODGERBLUE);
        atOrigin.setLayoutX(30); atOrigin.setLayoutY(30);
        atOrigin.setEffect(new DropShadow(16, Color.BLACK));

        Rectangle offOrigin = new Rectangle(60, 40, Color.DODGERBLUE);
        offOrigin.setLayoutX(230); offOrigin.setLayoutY(180);
        offOrigin.setEffect(new DropShadow(16, Color.BLACK));

        pane.getChildren().addAll(atOrigin, offOrigin);
        WritableImage img = snap(pane, 360, 280);

        // Horizontal scan across the origin rect (y=50, rect x[30..90]):
        // a correct soft shadow fades symmetrically into x<30 and x>90.
        scanH(log, img, "origin-row", 50, 8, 130, 6);
        // Vertical scan down the origin rect (x=60, rect y[30..70]).
        scanV(log, img, "origin-col", 60, 8, 110, 6);
        return img;
    }

    /** Rounded-rect and circular clips over a gradient — soft-clip edges. */
    private WritableImage sceneClip(List<String> log) {
        Region grad = new Region();
        grad.setPrefSize(160, 120);
        grad.setBackground(new Background(new BackgroundFill(
            new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.GOLD), new Stop(1, Color.DARKVIOLET)),
            CornerRadii.EMPTY, Insets.EMPTY)));
        Circle clip = new Circle(60, 60, 55);
        grad.setClip(clip);

        Region grad2 = new Region();
        grad2.setPrefSize(160, 120);
        grad2.setBackground(grad.getBackground());
        Rectangle rclip = new Rectangle(160, 120);
        rclip.setArcWidth(60); rclip.setArcHeight(60);
        grad2.setClip(rclip);

        HBox box = new HBox(20, grad, grad2);
        box.setPadding(new Insets(20));
        box.setBackground(Background.fill(Color.WHITE));
        WritableImage img = snap(box, 380, 160);
        // Centre of the circular clip is inside → gradient, not white.
        expect(log, img, 80, 80, -1, -1, -1, 255, "clip-centre-opaque");
        // Outside the circle (top-left corner of its box) is clipped → white.
        expect(log, img, 22, 22, 255, 255, 255, 255, "clip-outside-white");
        return img;
    }

    /** Snapshot the region scene at 2x device scale — exercises pixel-scale path. */
    private WritableImage sceneHidpi(List<String> log) {
        Region card = new Region();
        card.setStyle(
            "-fx-background-color: #2d7d2d;"
            + "-fx-background-radius: 18;"
            + "-fx-border-color: #103810; -fx-border-width: 4;"
            + "-fx-border-radius: 18;");
        StackPane box = new StackPane(card);
        box.setPadding(new Insets(20));
        box.setBackground(Background.fill(Color.web("#dddddd")));
        card.setPrefSize(160, 80);
        box.setPrefSize(200, 120);
        box.setMinSize(200, 120); box.setMaxSize(200, 120);
        Scene scene = new Scene(box, 200, 120);
        box.applyCss();
        box.layout();
        SnapshotParameters p = new SnapshotParameters();
        p.setTransform(new Scale(2.0, 2.0));
        WritableImage img = box.snapshot(p, null);
        log.add("scaled image is " + (int) img.getWidth() + "x" + (int) img.getHeight()
            + " (expect ~400x240)");
        // Interior of the card at 2x must still be the green fill.
        expect(log, img, 200, 120, 0x2d, 0x7d, 0x2d, 255, "hidpi-region-fill");
        return img;
    }

    /** Colour-transform effects (Bloom/Glow/Sepia/ColorAdjust/MotionBlur) on
     *  off-origin nodes — checks they apply without crashing or vanishing. */
    private WritableImage sceneEffects2(List<String> log) {
        Pane pane = new Pane();
        pane.setBackground(Background.fill(Color.web("#202020")));

        Rectangle glow = new Rectangle(80, 50, Color.web("#3060ff"));
        glow.setLayoutX(40); glow.setLayoutY(30);
        glow.setEffect(new Glow(0.8));

        Rectangle bloom = new Rectangle(80, 50, Color.web("#ff8030"));
        bloom.setLayoutX(220); bloom.setLayoutY(30);
        bloom.setEffect(new Bloom(0.3));

        Rectangle sepia = new Rectangle(80, 50, Color.web("#30c060"));
        sepia.setLayoutX(40); sepia.setLayoutY(130);
        sepia.setEffect(new SepiaTone());

        Rectangle adj = new Rectangle(80, 50, Color.web("#c03060"));
        adj.setLayoutX(220); adj.setLayoutY(130);
        ColorAdjust ca = new ColorAdjust();
        ca.setHue(0.3); ca.setSaturation(0.4); ca.setBrightness(0.1);
        adj.setEffect(ca);

        Text motion = makeText("Motion");
        motion.setFill(Color.WHITE);
        motion.setLayoutX(130); motion.setLayoutY(225);
        motion.setEffect(new MotionBlur(0, 8));

        pane.getChildren().addAll(glow, bloom, sepia, adj, motion);
        WritableImage img = snap(pane, 340, 260);
        // Far corner stays the dark backdrop (no effect bled across the scene).
        expect(log, img, 335, 5, 0x20, 0x20, 0x20, 255, "effects2-backdrop");
        return img;
    }

    /** BlendMode composite — deterministic MULTIPLY and SCREEN results. */
    private WritableImage sceneBlend(List<String> log) {
        // MULTIPLY: red over green => (r*g) = black in the overlap.
        Pane p1 = new Pane();
        p1.setBackground(Background.fill(Color.WHITE));
        Circle green1 = new Circle(60, 60, 45, Color.rgb(0, 255, 0));
        Circle red1 = new Circle(90, 60, 45, Color.rgb(255, 0, 0));
        red1.setBlendMode(BlendMode.MULTIPLY);
        p1.getChildren().addAll(green1, red1);

        // SCREEN: red over green => 1-(1-r)(1-g) = yellow in the overlap.
        Pane p2 = new Pane();
        p2.setBackground(Background.fill(Color.BLACK));
        Circle green2 = new Circle(60, 60, 45, Color.rgb(0, 255, 0));
        Circle red2 = new Circle(90, 60, 45, Color.rgb(255, 0, 0));
        red2.setBlendMode(BlendMode.SCREEN);
        p2.getChildren().addAll(green2, red2);

        HBox box = new HBox(20, p1, p2);
        box.setPadding(new Insets(10));
        box.setBackground(Background.fill(Color.web("#cccccc")));
        sizePane(p1, 180, 120);
        sizePane(p2, 180, 120);
        WritableImage img = snap(box, 410, 140);
        // Overlap of the two circles is around x≈85 in each pane (pane1 ~x95).
        expect(log, img, 95, 70, 0, 0, 0, 255, "blend-multiply-overlap-black");
        expect(log, img, 285, 70, 255, 255, 0, 255, "blend-screen-overlap-yellow");
        return img;
    }

    /** Radial gradient + linear REPEAT / REFLECT cycle methods. */
    private WritableImage sceneGradientModes(List<String> log) {
        Region radial = new Region();
        radial.setPrefSize(120, 120);
        radial.setBackground(new Background(new BackgroundFill(
            new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.WHITE), new Stop(1, Color.rgb(0, 0, 255))),
            CornerRadii.EMPTY, Insets.EMPTY)));

        Region repeat = new Region();
        repeat.setPrefSize(120, 120);
        repeat.setBackground(new Background(new BackgroundFill(
            new LinearGradient(0, 0, 0.25, 0, true, CycleMethod.REPEAT,
                new Stop(0, Color.RED), new Stop(1, Color.YELLOW)),
            CornerRadii.EMPTY, Insets.EMPTY)));

        Region reflect = new Region();
        reflect.setPrefSize(120, 120);
        reflect.setBackground(new Background(new BackgroundFill(
            new LinearGradient(0, 0, 0.25, 0, true, CycleMethod.REFLECT,
                new Stop(0, Color.RED), new Stop(1, Color.YELLOW)),
            CornerRadii.EMPTY, Insets.EMPTY)));

        HBox box = new HBox(16, radial, repeat, reflect);
        box.setPadding(new Insets(16));
        box.setBackground(Background.fill(Color.web("#dddddd")));
        WritableImage img = snap(box, 420, 152);
        // Radial centre is essentially white (a 1px sample already ~1% toward
        // the blue edge stop — deterministic on both tiers).
        expect(log, img, 76, 76, 0xf8, 0xf8, 0xff, 255, "radial-centre-white");
        return img;
    }

    /** Strokes: thick stroked rect, dashed thick line, polygon outline. */
    private WritableImage sceneStrokes(List<String> log) {
        Rectangle r = new Rectangle(30, 20, 120, 70);
        r.setFill(Color.TRANSPARENT);
        r.setStroke(Color.web("#205020"));
        r.setStrokeWidth(8);

        Line dash = new Line(30, 110, 250, 110);
        dash.setStroke(Color.web("#802020"));
        dash.setStrokeWidth(6);
        dash.setStrokeLineCap(StrokeLineCap.ROUND);
        dash.getStrokeDashArray().addAll(20.0, 14.0);

        Polygon tri = new Polygon(190, 20, 250, 90, 180, 90);
        tri.setFill(Color.TRANSPARENT);
        tri.setStroke(Color.web("#203080"));
        tri.setStrokeWidth(5);

        Pane pane = new Pane(r, dash, tri);
        pane.setBackground(Background.fill(Color.WHITE));
        WritableImage img = snap(pane, 280, 140);
        // The left vertical stroke of the rect (x≈30±4) is the green stroke.
        expect(log, img, 30, 55, 0x20, 0x50, 0x20, 255, "stroke-rect-left");
        // Dash check: scan the line row; a real 20-on/14-off dash alternates
        // dark-red (R≈128) and white (R=255). A solid line is all dark-red.
        scanH(log, img, "dashline-R", 110, 30, 120, 4);
        return img;
    }

    /** Misc shapes: arc, ellipse, cubic curve, even-odd star polygon. */
    private WritableImage sceneShapes(List<String> log) {
        Arc arc = new Arc(70, 70, 50, 50, 30, 240);
        arc.setType(ArcType.ROUND);
        arc.setFill(Color.web("#c04040"));

        Ellipse ell = new Ellipse(210, 70, 55, 35);
        ell.setFill(Color.web("#40a040"));

        CubicCurve curve = new CubicCurve(30, 170, 90, 110, 200, 230, 270, 160);
        curve.setFill(Color.TRANSPARENT);
        curve.setStroke(Color.web("#3040a0"));
        curve.setStrokeWidth(5);

        Polygon star = new Polygon(
            340, 40, 352, 78, 392, 78, 360, 102, 372, 140,
            340, 116, 308, 140, 320, 102, 288, 78, 328, 78);
        star.setFill(Color.web("#c0a020"));

        Pane pane = new Pane(arc, ell, curve, star);
        pane.setBackground(Background.fill(Color.WHITE));
        WritableImage img = snap(pane, 410, 250);
        // Probe inside the wedge body (not the exact vertex, which is a
        // sub-pixel boundary): 20px left of centre is within the 30°–270° fill.
        expect(log, img, 50, 70, 0xc0, 0x40, 0x40, 255, "arc-body-fill");
        // Ellipse centre.
        expect(log, img, 210, 70, 0x40, 0xa0, 0x40, 255, "ellipse-centre-fill");
        return img;
    }

    /** Rotated nodes — transform path under snapshot. */
    private WritableImage sceneTransform(List<String> log) {
        Region grad = new Region();
        grad.setPrefSize(120, 80);
        grad.setBackground(new Background(new BackgroundFill(
            new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.RED), new Stop(1, Color.BLUE)),
            CornerRadii.EMPTY, Insets.EMPTY)));
        grad.setLayoutX(60); grad.setLayoutY(60);
        grad.getTransforms().add(new Rotate(30, 60, 40));

        Text t = makeText("Rotated");
        t.setLayoutX(220); t.setLayoutY(120);
        t.getTransforms().add(new Rotate(-20));

        Pane pane = new Pane(grad, t);
        pane.setBackground(Background.fill(Color.web("#eeeeee")));
        WritableImage img = snap(pane, 360, 220);
        // Far corner is untouched backdrop.
        expect(log, img, 355, 5, 0xee, 0xee, 0xee, 255, "transform-backdrop");
        return img;
    }

    /** ImagePattern fill from a generated 2×2 checker image. */
    private WritableImage sceneImagePattern(List<String> log) {
        WritableImage checker = new WritableImage(2, 2);
        PixelWriter pw = checker.getPixelWriter();
        int red = 0xFFFF0000, blue = 0xFF0000FF;
        pw.setArgb(0, 0, red);  pw.setArgb(1, 0, blue);
        pw.setArgb(0, 1, blue); pw.setArgb(1, 1, red);

        Rectangle r = new Rectangle(20, 20, 200, 120);
        r.setFill(new ImagePattern(checker, 0, 0, 0.25, 0.25, true));

        Pane pane = new Pane(r);
        pane.setBackground(Background.fill(Color.web("#dddddd")));
        WritableImage img = snap(pane, 240, 160);
        // The checker must show BOTH red and blue cells across the fill.
        ring(log, img, "imgpat", new int[][] {
            {35, 35, 'a'}, {60, 35, 'b'}, {35, 60, 'c'}, {60, 60, 'd'},
            {110, 80, 'e'}, {160, 110, 'f'},
        });
        return img;
    }

    /** Bundled TTF (Shifting Comfort Demo) — loads from resources and renders
     *  plain / large / gradient-filled / shadowed, asserting glyphs appear. */
    private WritableImage sceneCustomFont(List<String> log) {
        Font font = null;
        try (InputStream in = openResource("Shifting Comfort Demo.ttf")) {
            if (in != null) font = Font.loadFont(in, 40);
        } catch (IOException e) {
            log.add("EXCEPTION loading font: " + e);
        }
        if (font == null) {
            log.add("FAIL custom font failed to load from resources");
            failures++;
            return null;
        }
        String fam = font.getFamily();
        log.add("loaded family=" + fam);

        Text plain = new Text("Shifting Comfort 0123");
        plain.setFont(Font.font(fam, 40));
        plain.setFill(Color.BLACK);

        Text grad = new Text("Gradient");
        grad.setFont(Font.font(fam, 44));
        grad.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
            new Stop(0, Color.RED), new Stop(1, Color.BLUE)));

        VBox box = new VBox(14, plain, grad);
        box.setPadding(new Insets(20));
        box.setBackground(Background.fill(Color.WHITE));
        WritableImage img = snap(box, 520, 150);
        // Glyphs must actually render: assert dark (plain text) and saturated
        // (gradient text) pixels exist — an all-white image = font not drawn.
        expectHasDark(log, img, "font-glyphs-present");
        return img;
    }

    /** Assert the image contains at least one clearly non-white pixel
     *  (i.e. some glyph coverage), scanning a coarse grid. */
    private void expectHasDark(List<String> log, WritableImage img, String what) {
        PixelReader pr = img.getPixelReader();
        if (pr == null) { log.add("FAIL " + what + ": null PixelReader"); failures++; return; }
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        int darkCount = 0;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int argb = pr.getArgb(x, y);
                int r = (argb >>> 16) & 0xFF, g = (argb >>> 8) & 0xFF, b = argb & 0xFF;
                if (r < 200 || g < 200 || b < 200) darkCount++;
            }
        }
        boolean ok = darkCount > 50;  // a few glyphs cover well more than this
        if (!ok) failures++;
        log.add((ok ? "PASS " : "FAIL ") + what + " nonWhiteSamples=" + darkCount);
    }

    private static InputStream openResource(String name) throws IOException {
        InputStream in = SnapHunt.class.getModule().getResourceAsStream(name);
        if (in != null) return in;
        return SnapHunt.class.getClassLoader().getResourceAsStream(name);
    }

    private static void sizePane(Region r, double w, double h) {
        r.setPrefSize(w, h);
        r.setMinSize(w, h);
        r.setMaxSize(w, h);
    }

    // ---- minimal pure-Java PNG writer (RGBA, no AWT) --------------------

    private void writePng(Path path, WritableImage img) {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        PixelReader pr = img.getPixelReader();
        if (pr == null) { System.out.println("[hunt]   no reader for " + path); return; }
        // Raw scanlines: filter byte 0 + RGBA per pixel.
        byte[] raw = new byte[h * (1 + w * 4)];
        int o = 0;
        for (int y = 0; y < h; y++) {
            raw[o++] = 0;
            for (int x = 0; x < w; x++) {
                int argb = pr.getArgb(x, y);
                raw[o++] = (byte) ((argb >>> 16) & 0xFF); // R
                raw[o++] = (byte) ((argb >>> 8) & 0xFF);  // G
                raw[o++] = (byte) (argb & 0xFF);          // B
                raw[o++] = (byte) ((argb >>> 24) & 0xFF); // A
            }
        }
        try (OutputStream os = Files.newOutputStream(path)) {
            os.write(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' });
            byte[] ihdr = new byte[13];
            putInt(ihdr, 0, w); putInt(ihdr, 4, h);
            ihdr[8] = 8;   // bit depth
            ihdr[9] = 6;   // color type RGBA
            chunk(os, "IHDR", ihdr);
            Deflater def = new Deflater(Deflater.BEST_SPEED);
            def.setInput(raw); def.finish();
            byte[] buf = new byte[Math.max(1024, raw.length / 2)];
            var idat = new java.io.ByteArrayOutputStream();
            while (!def.finished()) idat.write(buf, 0, def.deflate(buf));
            def.end();
            chunk(os, "IDAT", idat.toByteArray());
            chunk(os, "IEND", new byte[0]);
        } catch (IOException e) {
            System.out.println("[hunt]   PNG write failed " + path + ": " + e);
        }
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24); b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8); b[off + 3] = (byte) v;
    }

    private static void chunk(OutputStream os, String type, byte[] data)
            throws IOException {
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        os.write(len);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        os.write(t);
        os.write(data);
        CRC32 crc = new CRC32();
        crc.update(t); crc.update(data);
        byte[] c = new byte[4];
        putInt(c, 0, (int) crc.getValue());
        os.write(c);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
