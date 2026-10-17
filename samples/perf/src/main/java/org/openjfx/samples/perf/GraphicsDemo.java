package org.openjfx.samples.perf;

import java.io.IOException;
import java.io.InputStream;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.effect.Bloom;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.BoxBlur;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Effect;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.effect.Glow;
import javafx.scene.effect.InnerShadow;
import javafx.scene.effect.MotionBlur;
import javafx.scene.effect.Reflection;
import javafx.scene.effect.SepiaTone;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.ImagePattern;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
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
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;

/**
 * Visual gallery exercising the graphics features verified and fixed during the
 * Skia-pipeline stabilization pass. Each section renders a representative set
 * of nodes so the output can be eyeballed.
 *
 * <p>Two sections cover bugs fixed on this branch:</p>
 * <ul>
 *   <li><b>Drop Shadows</b> — a default {@code DropShadow} (offset 0) must be a
 *       soft, symmetric halo. The rect-shadow fast-path used inverted texture
 *       coordinates that the Skia bridge dropped, producing a hard offset slab;
 *       now mirrored correctly.</li>
 *   <li><b>Dashed Strokes</b> — the stroke dash array was never forwarded to the
 *       native paint, so every dashed stroke rendered solid. Now plumbed through
 *       an {@code SkDashPathEffect}.</li>
 * </ul>
 *
 * <p>The remaining sections (effects, gradients, blend modes, clipping, shapes,
 * image patterns, transforms, text) confirm those paths still render correctly.</p>
 */
public final class GraphicsDemo extends Application<Stage> {

    /** Required by the JavaFX launcher. */
    public GraphicsDemo() { }

    private static final Color BG   = Color.web("#1e2128");
    private static final Color CARD = Color.web("#2b2f3a");
    private static final Color INK  = Color.web("#e6e8ee");
    private static final Color SUB  = Color.web("#9aa3b2");

    /** Bundled demo TTF (resources), loaded at startup. Null if unavailable. */
    private static final String FONT_RESOURCE = "Shifting Comfort Demo.ttf";
    private Font customFont;

    @Override
    public void start(Stage stage) {
        loadCustomFont();
        VBox content = new VBox(22);
        content.setPadding(new Insets(24));
        content.setFillWidth(true);

        Label title = new Label("skia-fx — GraphicsDemo");
        title.setTextFill(INK);
        title.setFont(Font.font("System", FontWeight.BOLD, 26));
        Label sub = new Label("Visual check for the stabilized 2D rendering paths "
            + "(✓ fixed sections are marked).");
        sub.setTextFill(SUB);
        sub.setFont(Font.font(13));
        VBox header = new VBox(4, title, sub);

        content.getChildren().addAll(
            header,
            section("Drop Shadows  ✓ fixed", dropShadowItems()),
            section("Dashed Strokes  ✓ fixed", dashedStrokeItems()),
            section("Custom Font (TTF) — Shifting Comfort Demo", customFontItems()),
            section("Effects", effectItems()),
            section("Gradients", gradientItems()),
            section("Blend Modes", blendItems()),
            section("Clipping", clipItems()),
            section("Shapes", shapeItems()),
            section("Image Pattern", imagePatternItems()),
            section("Transforms", transformItems()),
            section("Text", textItems()));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: #1e2128; -fx-background-color: #1e2128;");

        Scene scene = new Scene(scroll, 1080, 820, BG);
        stage.setTitle("skia-fx GraphicsDemo");
        stage.setScene(scene);
        stage.show();
    }

    // ---- custom font ----------------------------------------------------

    private void loadCustomFont() {
        // Module-root resource. Under JPMS exploded runs (classes and resources
        // in separate dirs), Class.getResourceAsStream misses it — go through
        // the module / classloader, matching the ensemble samples' pattern.
        try (InputStream in = openResource(FONT_RESOURCE)) {
            if (in != null) {
                customFont = Font.loadFont(in, 30);
            } else {
                System.err.println("[GraphicsDemo] font resource not found: " + FONT_RESOURCE);
            }
        } catch (IOException e) {
            System.err.println("[GraphicsDemo] custom font load failed: " + e);
        }
    }

    private static InputStream openResource(String name) throws IOException {
        InputStream in = GraphicsDemo.class.getModule().getResourceAsStream(name);
        if (in != null) return in;
        return GraphicsDemo.class.getClassLoader().getResourceAsStream(name);
    }

    private Node[] customFontItems() {
        if (customFont == null) {
            Label miss = new Label("'" + FONT_RESOURCE + "' failed to load");
            miss.setTextFill(Color.web("#f87171"));
            miss.setFont(Font.font(13));
            return new Node[] { card("load error", miss, 280, 80) };
        }
        String fam = customFont.getFamily();

        Text glyphs = customText("AaBbCc 0123", 30, INK);
        Text large  = customText("Skia", 56, Color.web("#22d3ee"));

        Text shadowed = customText("Custom + Shadow", 28, INK);
        shadowed.setEffect(new DropShadow(8, 3, 3, Color.color(0, 0, 0, 0.7)));

        Text gradient = customText("Gradient", 40, null);
        gradient.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
            new Stop(0, Color.web("#f43f5e")), new Stop(1, Color.web("#3b82f6"))));

        Text pangram = customText("The quick brown fox", 22, INK);

        return new Node[] {
            card("family: " + fam, glyphs, 230, 90),
            card("large", large, 170, 100),
            card("+ drop shadow", shadowed, 240, 90),
            card("+ gradient fill", gradient, 190, 90),
            card("pangram", pangram, 280, 80),
        };
    }

    private Text customText(String s, double size, Color fill) {
        Text t = new Text(s);
        t.setFont(Font.font(customFont.getFamily(), size));
        if (fill != null) t.setFill(fill);
        return t;
    }

    // ---- section / card scaffolding -------------------------------------

    private Node section(String title, Node... items) {
        Label header = new Label(title);
        header.setTextFill(INK);
        header.setFont(Font.font("System", FontWeight.SEMI_BOLD, 17));

        FlowPane flow = new FlowPane(18, 18, items);
        flow.setAlignment(Pos.TOP_LEFT);

        VBox box = new VBox(12, header, flow);
        box.setPadding(new Insets(16));
        box.setBackground(new Background(new BackgroundFill(
            CARD, new CornerRadii(14), Insets.EMPTY)));
        return box;
    }

    /** A demo node plus a caption underneath, on a neutral tile. */
    private Node card(String caption, Node demo, double w, double h) {
        StackPane stage = new StackPane(demo);
        stage.setPrefSize(w, h);
        stage.setMinSize(w, h);
        stage.setMaxSize(w, h);
        stage.setBackground(new Background(new BackgroundFill(
            Color.web("#11131a"), new CornerRadii(8), Insets.EMPTY)));
        Label cap = new Label(caption);
        cap.setTextFill(SUB);
        cap.setFont(Font.font(11.5));
        VBox v = new VBox(6, stage, cap);
        v.setAlignment(Pos.CENTER);
        return v;
    }

    // ---- drop shadows (fixed) -------------------------------------------

    private Node[] dropShadowItems() {
        Rectangle sym = new Rectangle(90, 56, Color.web("#3b82f6"));
        sym.setEffect(new DropShadow(18, Color.BLACK));

        Rectangle offset = new Rectangle(90, 56, Color.web("#f59e0b"));
        offset.setEffect(new DropShadow(14, 10, 10, Color.color(0, 0, 0, 0.6)));

        Rectangle colored = new Rectangle(90, 56, Color.web("#10b981"));
        DropShadow neon = new DropShadow(26, Color.web("#22d3ee"));
        colored.setEffect(neon);

        Text glow = new Text("Shadowed");
        glow.setFont(Font.font("System", FontWeight.BOLD, 24));
        glow.setFill(Color.web("#e6e8ee"));
        glow.setEffect(new DropShadow(10, 4, 4, Color.color(0, 0, 0, 0.7)));

        Circle round = new Circle(30, Color.web("#a78bfa"));
        round.setEffect(new DropShadow(20, Color.BLACK));

        return new Node[] {
            card("symmetric (offset 0)", sym, 150, 110),
            card("offset 10,10", offset, 150, 110),
            card("colored shadow", colored, 150, 110),
            card("shadowed text", glow, 170, 110),
            card("on a circle", round, 150, 110),
        };
    }

    // ---- dashed strokes (fixed) -----------------------------------------

    private Node[] dashedStrokeItems() {
        Line l1 = new Line(0, 0, 130, 0);
        l1.setStroke(Color.web("#f87171"));
        l1.setStrokeWidth(4);
        l1.getStrokeDashArray().addAll(18.0, 12.0);

        Line l2 = new Line(0, 0, 130, 0);
        l2.setStroke(Color.web("#34d399"));
        l2.setStrokeWidth(6);
        l2.setStrokeLineCap(StrokeLineCap.ROUND);
        l2.getStrokeDashArray().addAll(2.0, 16.0);  // dotted

        Rectangle box = new Rectangle(120, 64);
        box.setFill(Color.TRANSPARENT);
        box.setStroke(Color.web("#60a5fa"));
        box.setStrokeWidth(3);
        box.getStrokeDashArray().addAll(10.0, 8.0);

        Circle ring = new Circle(34);
        ring.setFill(Color.TRANSPARENT);
        ring.setStroke(Color.web("#fbbf24"));
        ring.setStrokeWidth(4);
        ring.getStrokeDashArray().addAll(14.0, 9.0);

        Line solid = new Line(0, 0, 130, 0);  // control: still solid
        solid.setStroke(Color.web("#9aa3b2"));
        solid.setStrokeWidth(4);

        return new Node[] {
            card("dashed line", l1, 160, 90),
            card("dotted (round cap)", l2, 160, 90),
            card("dashed rect border", box, 160, 110),
            card("dashed ring", ring, 150, 110),
            card("solid (control)", solid, 160, 90),
        };
    }

    // ---- effects --------------------------------------------------------

    private Node[] effectItems() {
        return new Node[] {
            card("Glow", fxRect("#3b82f6", new Glow(0.9)), 130, 100),
            card("Bloom", fxRect("#f59e0b", new Bloom(0.3)), 130, 100),
            card("GaussianBlur", fxRect("#10b981", new GaussianBlur(6)), 130, 100),
            card("BoxBlur", fxRect("#ef4444", new BoxBlur(8, 8, 2)), 130, 100),
            card("SepiaTone", fxRect("#22c55e", new SepiaTone()), 130, 100),
            card("ColorAdjust", fxRect("#a855f7", colorAdjust()), 130, 100),
            card("InnerShadow", fxRect("#e5e7eb", new InnerShadow(16, Color.web("#1e3a8a"))), 130, 100),
            card("MotionBlur", fxText("Motion", new MotionBlur(0, 8)), 150, 100),
            card("Reflection", fxText("Mirror", new Reflection()), 150, 120),
        };
    }

    private static ColorAdjust colorAdjust() {
        ColorAdjust c = new ColorAdjust();
        c.setHue(0.35);
        c.setSaturation(0.4);
        c.setBrightness(0.05);
        return c;
    }

    private Node fxRect(String color, Effect e) {
        Rectangle r = new Rectangle(80, 54, Color.web(color));
        r.setArcWidth(12);
        r.setArcHeight(12);
        r.setEffect(e);
        return r;
    }

    private Node fxText(String s, Effect e) {
        Text t = new Text(s);
        t.setFont(Font.font("System", FontWeight.BOLD, 26));
        t.setFill(INK);
        t.setEffect(e);
        return t;
    }

    // ---- gradients ------------------------------------------------------

    private Node[] gradientItems() {
        Region linear = gradRegion(new LinearGradient(0, 0, 1, 1, true,
            CycleMethod.NO_CYCLE,
            new Stop(0, Color.web("#f43f5e")), new Stop(1, Color.web("#3b82f6"))));

        Region radial = gradRegion(new RadialGradient(0, 0, 0.5, 0.5, 0.55, true,
            CycleMethod.NO_CYCLE,
            new Stop(0, Color.web("#fde68a")), new Stop(1, Color.web("#7c3aed"))));

        Region repeat = gradRegion(new LinearGradient(0, 0, 0.22, 0, true,
            CycleMethod.REPEAT,
            new Stop(0, Color.web("#ef4444")), new Stop(1, Color.web("#fde047"))));

        Region reflect = gradRegion(new LinearGradient(0, 0, 0.22, 0, true,
            CycleMethod.REFLECT,
            new Stop(0, Color.web("#06b6d4")), new Stop(1, Color.web("#1e293b"))));

        Region multi = gradRegion(new LinearGradient(0, 0, 1, 0, true,
            CycleMethod.NO_CYCLE,
            new Stop(0, Color.web("#ef4444")), new Stop(0.5, Color.web("#22c55e")),
            new Stop(1, Color.web("#3b82f6"))));

        Text gradText = new Text("Gradient");
        gradText.setFont(Font.font("System", FontWeight.BOLD, 28));
        gradText.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
            new Stop(0, Color.web("#f43f5e")), new Stop(1, Color.web("#3b82f6"))));

        return new Node[] {
            card("linear", linear, 120, 110),
            card("radial", radial, 120, 110),
            card("repeat", repeat, 120, 110),
            card("reflect", reflect, 120, 110),
            card("multi-stop", multi, 120, 110),
            card("gradient text", gradText, 160, 110),
        };
    }

    private Region gradRegion(Paint fill) {
        Region r = new Region();
        r.setPrefSize(96, 80);
        r.setMinSize(96, 80);
        r.setMaxSize(96, 80);
        r.setBackground(new Background(new BackgroundFill(
            fill, new CornerRadii(8), Insets.EMPTY)));
        return r;
    }

    // ---- blend modes ----------------------------------------------------

    private Node[] blendItems() {
        return new Node[] {
            card("MULTIPLY", blendPair(BlendMode.MULTIPLY), 130, 110),
            card("SCREEN", blendPair(BlendMode.SCREEN), 130, 110),
            card("OVERLAY", blendPair(BlendMode.OVERLAY), 130, 110),
            card("DIFFERENCE", blendPair(BlendMode.DIFFERENCE), 130, 110),
            card("ADD", blendPair(BlendMode.ADD), 130, 110),
        };
    }

    private Node blendPair(BlendMode mode) {
        Circle a = new Circle(36, 40, 34, Color.web("#22c55e"));
        Circle b = new Circle(60, 40, 34, Color.web("#ef4444"));
        b.setBlendMode(mode);
        Pane p = new Pane(a, b);
        p.setPrefSize(96, 80);
        return p;
    }

    // ---- clipping -------------------------------------------------------

    private Node[] clipItems() {
        Region g1 = gradRegion(new LinearGradient(0, 0, 1, 1, true,
            CycleMethod.NO_CYCLE,
            new Stop(0, Color.web("#fde68a")), new Stop(1, Color.web("#db2777"))));
        Circle cc = new Circle(48, 40, 38);
        g1.setClip(cc);

        Region g2 = gradRegion(new LinearGradient(0, 0, 1, 1, true,
            CycleMethod.NO_CYCLE,
            new Stop(0, Color.web("#34d399")), new Stop(1, Color.web("#1d4ed8"))));
        Rectangle rr = new Rectangle(96, 80);
        rr.setArcWidth(48);
        rr.setArcHeight(48);
        g2.setClip(rr);

        return new Node[] {
            card("circular clip", g1, 120, 110),
            card("rounded clip", g2, 120, 110),
        };
    }

    // ---- shapes ---------------------------------------------------------

    private Node[] shapeItems() {
        Arc arc = new Arc(45, 45, 38, 38, 35, 250);
        arc.setType(ArcType.ROUND);
        arc.setFill(Color.web("#ef4444"));

        Ellipse ell = new Ellipse(48, 38, 42, 28);
        ell.setFill(Color.web("#22c55e"));

        Polygon star = new Polygon(
            48, 6, 58, 36, 90, 36, 64, 56, 74, 88,
            48, 68, 22, 88, 32, 56, 6, 36, 38, 36);
        star.setFill(Color.web("#f59e0b"));

        CubicCurve curve = new CubicCurve(6, 70, 30, 10, 70, 90, 96, 24);
        curve.setFill(Color.TRANSPARENT);
        curve.setStroke(Color.web("#60a5fa"));
        curve.setStrokeWidth(5);

        return new Node[] {
            card("arc (round)", arc, 120, 110),
            card("ellipse", ell, 120, 110),
            card("star (even-odd)", star, 120, 110),
            card("cubic curve", curve, 120, 110),
        };
    }

    // ---- image pattern --------------------------------------------------

    private Node[] imagePatternItems() {
        WritableImage checker = new WritableImage(2, 2);
        PixelWriter pw = checker.getPixelWriter();
        int a = 0xFF1f2937, b = 0xFFf59e0b;
        pw.setArgb(0, 0, a); pw.setArgb(1, 0, b);
        pw.setArgb(0, 1, b); pw.setArgb(1, 1, a);

        Rectangle rect = new Rectangle(110, 80);
        rect.setArcWidth(10);
        rect.setArcHeight(10);
        rect.setFill(new ImagePattern(checker, 0, 0, 0.18, 0.25, true));

        Circle circ = new Circle(40);
        circ.setFill(new ImagePattern(checker, 0, 0, 0.22, 0.22, true));

        return new Node[] {
            card("checker fill (rect)", rect, 140, 110),
            card("checker fill (circle)", circ, 120, 110),
        };
    }

    // ---- transforms -----------------------------------------------------

    private Node[] transformItems() {
        Region grad = gradRegion(new LinearGradient(0, 0, 1, 0, true,
            CycleMethod.NO_CYCLE,
            new Stop(0, Color.web("#ef4444")), new Stop(1, Color.web("#3b82f6"))));
        Group g = new Group(grad);
        grad.getTransforms().add(new Rotate(28, 48, 40));

        Text rot = new Text("Rotated");
        rot.setFont(Font.font("System", FontWeight.BOLD, 22));
        rot.setFill(INK);
        rot.getTransforms().add(new Rotate(-18));

        Region scaled = gradRegion(new RadialGradient(0, 0, 0.5, 0.5, 0.5, true,
            CycleMethod.NO_CYCLE,
            new Stop(0, Color.web("#fde047")), new Stop(1, Color.web("#b91c1c"))));
        scaled.getTransforms().add(new Scale(0.7, 1.2, 48, 40));

        return new Node[] {
            card("rotated gradient", g, 130, 120),
            card("rotated text", rot, 150, 110),
            card("non-uniform scale", scaled, 130, 120),
        };
    }

    // ---- text -----------------------------------------------------------

    private Node[] textItems() {
        Text plain = styledText("Plain", FontWeight.NORMAL, FontPosture.REGULAR, INK);
        Text bold = styledText("Bold", FontWeight.BOLD, FontPosture.REGULAR, INK);
        Text italic = styledText("Italic", FontWeight.NORMAL, FontPosture.ITALIC, INK);

        Group faded = new Group(styledText("Opacity 0.5", FontWeight.BOLD,
            FontPosture.REGULAR, INK));
        faded.setOpacity(0.5);

        Text big = styledText("Big", FontWeight.BOLD, FontPosture.REGULAR,
            Color.web("#22d3ee"));
        big.setFont(Font.font("System", FontWeight.BOLD, 40));

        return new Node[] {
            card("regular", plain, 120, 80),
            card("bold", bold, 120, 80),
            card("italic", italic, 120, 80),
            card("on opacity layer", faded, 150, 80),
            card("large", big, 120, 90),
        };
    }

    private Text styledText(String s, FontWeight w, FontPosture p, Color fill) {
        Text t = new Text(s);
        t.setFont(Font.font("System", w, p, 22));
        t.setFill(fill);
        return t;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
