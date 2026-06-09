/*
 * ShowcaseController — the brain of the skia-fx Showcase Dashboard.
 *
 * Owns: the animated collapsible sidebar, responsive auto-collapse, the
 * loader-gated section switching, every section's node tree, the global
 * FPS badge, and (via installStage) the custom title-bar hit regions.
 *
 * The shell is FXML (showcase.fxml); the heavy section bodies are built
 * here in code and cached lazily.
 */
package org.openjfx.samples.ensemble;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;

import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.FillTransition;
import javafx.animation.PathTransition;
import javafx.animation.RotateTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.StrokeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.util.Duration;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Pagination;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TreeView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;

import javafx.scene.chart.AreaChart;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.BubbleChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.ScatterChart;
import javafx.scene.chart.StackedAreaChart;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;

import javafx.scene.effect.Bloom;
import javafx.scene.effect.BoxBlur;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Effect;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.effect.Glow;
import javafx.scene.effect.InnerShadow;
import javafx.scene.effect.Light;
import javafx.scene.effect.Lighting;
import javafx.scene.effect.MotionBlur;
import javafx.scene.effect.Reflection;
import javafx.scene.effect.SepiaTone;

import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Line;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.QuadCurve;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public final class ShowcaseController {

    // Caption-button glyphs (0..10 grid, stroked). Mirror CustomTitleBarDemo.
    private static final String SVG_MAX     = "M0.5,0.5 H9.5 V9.5 H0.5 Z";
    private static final String SVG_RESTORE =
            "M0.5,2.5 H7.5 V9.5 H0.5 Z M2.5,2.5 V0.5 H9.5 V7.5 H7.5";

    // ---- FXML-injected shell nodes ----------------------------------
    @FXML private BorderPane rootPane;
    @FXML private HBox       titleBar;
    @FXML private HBox       captionRegion;
    @FXML private StackPane  minBtn;
    @FXML private StackPane  maxBtn;
    @FXML private StackPane  closeBtn;
    @FXML private SVGPath    maxIcon;
    @FXML private VBox       sidebar;
    @FXML private HBox       sidebarHeader;
    @FXML private StackPane  hamburger;
    @FXML private Label      sidebarBrand;
    @FXML private Label      sidebarFooter;
    @FXML private VBox       navBox;
    @FXML private StackPane  contentStack;
    @FXML private Label      statusText;
    @FXML private Label      nodeCountLabel;
    @FXML private Label      sizeLabel;
    @FXML private Label      fpsBadge;

    // ---- state ------------------------------------------------------
    private final ToggleGroup navGroup = new ToggleGroup();
    private final List<ToggleButton> navItems = new ArrayList<>();
    private final Map<String, Section> cache = new HashMap<>();
    private boolean expanded = true;
    private String currentKey;
    private Stage stage;
    private StressScene.BenchPanel benchPanel;

    /** A built section plus optional show/hide hooks for its animations. */
    private record Section(Node node, Runnable onShow, Runnable onHide) {
        Section(Node node) { this(node, null, null); }
    }

    // =================================================================
    // Lifecycle
    // =================================================================

    @FXML
    private void initialize() {
        buildNav();
        setupResponsive();
        hamburger.setOnMouseClicked(e -> toggleSidebar());
        startFpsTimer();
        selectNav("overview");
    }

    /** Called by ShowcaseApp once the Stage exists: wire custom chrome. */
    void installStage(Stage stage) {
        this.stage = stage;
        stage.setCaptionRegions(captionRegion);
        stage.setMinRegion(minBtn);
        stage.setMaxRegion(maxBtn);
        stage.setCloseRegion(closeBtn);

        minBtn.setOnMouseClicked(e -> stage.setIconified(true));
        maxBtn.setOnMouseClicked(e -> stage.setMaximized(!stage.isMaximized()));
        closeBtn.setOnMouseClicked(e -> stage.close());

        maxIcon.setContent(stage.isMaximized() ? SVG_RESTORE : SVG_MAX);
        stage.maximizedProperty().addListener((o, was, isMax) ->
                maxIcon.setContent(isMax ? SVG_RESTORE : SVG_MAX));

        sizeLabel.textProperty().bind(Bindings.createStringBinding(
                () -> Math.round(stage.getWidth()) + " × " + Math.round(stage.getHeight()) + " px",
                stage.widthProperty(), stage.heightProperty()));
    }

    /** Flush any open benchmark metrics file on shutdown. */
    void shutdown() {
        if (benchPanel != null) benchPanel.close();
    }

    // =================================================================
    // Sidebar navigation
    // =================================================================

    private void buildNav() {
        addNav("overview",  "Overview",        Icons.GRID);
        addNav("controls",  "Controls",        Icons.SLIDERS);
        addNav("charts",    "Charts",          Icons.BARS);
        addNav("shapes",    "Shapes & Canvas", Icons.SHAPES);
        addNav("effects",   "Effects",         Icons.SPARK);
        addNav("animation", "Animation Lab",   Icons.PLAY);
        addNav("benchmark", "Benchmark",       Icons.GAUGE);

        // Never allow the selection to go empty (keeps the active highlight).
        navGroup.selectedToggleProperty().addListener((o, ov, nv) -> {
            if (nv == null && ov != null) ov.setSelected(true);
        });
    }

    private void addNav(String key, String label, String svg) {
        ToggleButton tb = new ToggleButton(label, navIcon(svg));
        tb.getStyleClass().add("nav-item");
        tb.setToggleGroup(navGroup);
        tb.setMaxWidth(Double.MAX_VALUE);
        tb.setContentDisplay(ContentDisplay.LEFT);
        tb.setUserData(key);
        tb.setOnAction(e -> showSection(key));
        navItems.add(tb);
        navBox.getChildren().add(tb);
    }

    private void selectNav(String key) {
        for (ToggleButton tb : navItems) {
            if (key.equals(tb.getUserData())) { tb.setSelected(true); break; }
        }
        showSection(key);
    }

    private void toggleSidebar() {
        setExpanded(!expanded, true);
    }

    private void setExpanded(boolean exp, boolean animate) {
        expanded = exp;
        double w = exp ? 236 : 74;

        for (ToggleButton tb : navItems) {
            tb.setContentDisplay(exp ? ContentDisplay.LEFT : ContentDisplay.GRAPHIC_ONLY);
        }
        if (exp) sidebar.getStyleClass().remove("collapsed");
        else if (!sidebar.getStyleClass().contains("collapsed")) sidebar.getStyleClass().add("collapsed");

        sidebarBrand.setVisible(exp);  sidebarBrand.setManaged(exp);
        sidebarFooter.setVisible(exp); sidebarFooter.setManaged(exp);

        if (animate) {
            Timeline t = new Timeline(new KeyFrame(Duration.millis(240),
                    new KeyValue(sidebar.prefWidthProperty(), w, Interpolator.EASE_BOTH),
                    new KeyValue(sidebar.minWidthProperty(),  w, Interpolator.EASE_BOTH),
                    new KeyValue(sidebar.maxWidthProperty(),  w, Interpolator.EASE_BOTH)));
            t.play();
        } else {
            sidebar.setPrefWidth(w); sidebar.setMinWidth(w); sidebar.setMaxWidth(w);
        }
    }

    /** Auto-collapse below a width threshold; expand again above it. */
    private void setupResponsive() {
        rootPane.widthProperty().addListener((o, ov, nv) -> {
            double w = nv.doubleValue(), old = ov.doubleValue();
            if (w < 980 && old >= 980 && expanded)        setExpanded(false, true);
            else if (w >= 980 && old < 980 && !expanded)  setExpanded(true, true);
        });
    }

    private static SVGPath navIcon(String svg) {
        SVGPath p = new SVGPath();
        p.setContent(svg);
        p.getStyleClass().add("nav-icon");
        return p;
    }

    // =================================================================
    // Loader-gated section switching
    // =================================================================

    private void showSection(String key) {
        if (key.equals(currentKey)) return;
        String prev = currentKey;
        currentKey = key;

        if (prev != null) {
            Section ps = cache.get(prev);
            if (ps != null && ps.onHide() != null) ps.onHide().run();
        }
        statusText.setText("Loading " + titleOf(key) + "…");

        // Spinner overlay on top of whatever is currently shown.
        DualLoader loader = new DualLoader();
        loader.start();
        StackPane overlay = new StackPane(loader);
        overlay.getStyleClass().add("loader-overlay");
        overlay.setOpacity(0);
        contentStack.getChildren().add(overlay);
        FadeTransition fin = new FadeTransition(Duration.millis(150), overlay);
        fin.setToValue(1);
        fin.play();

        boolean first = !cache.containsKey(key);
        PauseTransition hold = new PauseTransition(
                first ? Duration.millis(620) : Duration.millis(160));
        hold.setOnFinished(e -> {
            Section sec = cache.computeIfAbsent(key, this::buildSection);
            contentStack.getChildren().removeIf(n -> n != overlay);
            contentStack.getChildren().add(0, sec.node());
            animateIn(sec.node());
            if (sec.onShow() != null) sec.onShow().run();
            statusText.setText(titleOf(key));
            updateNodeCount(sec.node());

            FadeTransition fout = new FadeTransition(Duration.millis(220), overlay);
            fout.setToValue(0);
            fout.setOnFinished(ev -> {
                loader.stop();
                contentStack.getChildren().remove(overlay);
            });
            fout.play();
        });
        hold.play();
    }

    private static void animateIn(Node n) {
        n.setOpacity(0);
        n.setTranslateY(18);
        n.setScaleX(0.985); n.setScaleY(0.985);
        FadeTransition f = new FadeTransition(Duration.millis(340), n);
        f.setToValue(1);
        TranslateTransition t = new TranslateTransition(Duration.millis(380), n);
        t.setToY(0); t.setInterpolator(Interpolator.EASE_OUT);
        ScaleTransition s = new ScaleTransition(Duration.millis(380), n);
        s.setToX(1); s.setToY(1); s.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(f, t, s).play();
    }

    private Section buildSection(String key) {
        return switch (key) {
            case "overview"  -> buildOverview();
            case "controls"  -> buildControls();
            case "charts"    -> buildCharts();
            case "shapes"    -> buildShapes();
            case "effects"   -> buildEffects();
            case "animation" -> buildAnimation();
            case "benchmark" -> buildBenchmark();
            default          -> new Section(new Label("Unknown section: " + key));
        };
    }

    private static String titleOf(String key) {
        return switch (key) {
            case "overview"  -> "Overview";
            case "controls"  -> "Controls";
            case "charts"    -> "Charts";
            case "shapes"    -> "Shapes & Canvas";
            case "effects"   -> "Effects";
            case "animation" -> "Animation Lab";
            case "benchmark" -> "Benchmark";
            default          -> key;
        };
    }

    private void updateNodeCount(Node section) {
        int n = countNodes(section);
        nodeCountLabel.setText(String.format("%,d nodes", n));
    }

    private static int countNodes(Node n) {
        int c = 1;
        if (n instanceof Parent p) {
            List<Node> kids = p.getChildrenUnmodifiable();
            for (int i = 0; i < kids.size(); i++) c += countNodes(kids.get(i));
        }
        return c;
    }

    // =================================================================
    // Global FPS badge
    // =================================================================

    private void startFpsTimer() {
        new AnimationTimer() {
            long windowStart = 0;
            int  frames = 0;
            @Override public void handle(long now) {
                frames++;
                if (windowStart == 0) { windowStart = now; return; }
                long elapsed = now - windowStart;
                if (elapsed < 500_000_000L) return;
                double fps = frames * 1_000_000_000.0 / elapsed;
                fpsBadge.setText(String.format("%.0f fps", fps));
                fpsBadge.getStyleClass().removeAll("fps-good", "fps-okay", "fps-slow");
                fpsBadge.getStyleClass().add(fps >= 100 ? "fps-good" : fps >= 50 ? "fps-okay" : "fps-slow");
                windowStart = now;
                frames = 0;
            }
        }.start();
    }

    // =================================================================
    // Shared building blocks
    // =================================================================

    /** Nav glyphs on a 0..10 grid (stroked, no fill). */
    private static final class Icons {
        static final String GRID    = "M0.5,0.5 H4 V4 H0.5 Z M6,0.5 H9.5 V4 H6 Z M0.5,6 H4 V9.5 H0.5 Z M6,6 H9.5 V9.5 H6 Z";
        static final String SLIDERS = "M0,2 H10 M0,5 H10 M0,8 H10";
        static final String BARS    = "M0.5,10 V6 M3.5,10 V3 M6.5,10 V7 M9.5,10 V1";
        static final String SHAPES  = "M5,0.5 L9.5,9.5 H0.5 Z";
        static final String SPARK   = "M5,0 V10 M0,5 H10 M1.5,1.5 L8.5,8.5 M8.5,1.5 L1.5,8.5";
        static final String PLAY    = "M1,0.5 L9.5,5 L1,9.5 Z";
        static final String GAUGE   = "M0.5,8.5 A4.5,4.5 0 1 1 9.5,8.5 M5,8.5 L8,4.5";
        private Icons() { }
    }

    private final Random rng = new Random(7);

    /** A titled, scrollable section wrapper with a transparent viewport. */
    private static ScrollPane sectionScroller(String title, String sub, Node body) {
        Label t = new Label(title);
        t.getStyleClass().add("section-title");
        Label s = new Label(sub);
        s.getStyleClass().add("section-sub");
        VBox head = new VBox(4, t, s);

        VBox content = new VBox(20, head, body);
        content.getStyleClass().add("section");
        VBox.setVgrow(body, Priority.ALWAYS);

        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("content-area");
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return sp;
    }

    /** A generic glass card with a bold title over arbitrary content. */
    private static VBox card(String title, Node content) {
        Label t = new Label(title);
        t.getStyleClass().add("card-title");
        VBox v = new VBox(12, t, content);
        v.getStyleClass().add("card");
        v.setPadding(new Insets(18));
        return v;
    }

    /** A label + control row for forms. */
    private static HBox labeled(String caption, Node control) {
        Label c = new Label(caption);
        c.getStyleClass().add("card-sub");
        c.setMinWidth(110);
        HBox row = new HBox(12, c, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label chip(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("chip");
        return l;
    }

    // =================================================================
    // Section: Overview
    // =================================================================

    private Section buildOverview() {
        FlowPane kpis = new FlowPane(16, 16);
        kpis.getChildren().addAll(
                kpiCard("Renders / s", "1,284", "+12.4%", true),
                kpiCard("Frame time", "8.3 ms", "-0.6 ms", true),
                kpiCard("GPU memory", "412 MB", "stable", true),
                kpiCard("Dropped", "0", "0.00%", true));

        // A continuously pulsing accent dot — proves per-frame submission is
        // alive even when nothing else on screen is moving.
        Circle pulse = new Circle(9);
        pulse.getStyleClass().add("kpi-dot");
        ScaleTransition st = new ScaleTransition(Duration.seconds(0.9), pulse);
        st.setFromX(1); st.setFromY(1); st.setToX(1.9); st.setToY(1.9);
        st.setAutoReverse(true);
        st.setCycleCount(ScaleTransition.INDEFINITE);
        st.play();
        Label live = new Label("live");
        live.getStyleClass().add("card-sub");
        HBox liveRow = new HBox(10, pulse, live);
        liveRow.setAlignment(Pos.CENTER_LEFT);
        VBox liveCard = card("Pipeline heartbeat", liveRow);
        liveCard.setMinWidth(190);
        kpis.getChildren().add(liveCard);

        VBox lineCard = card("Throughput", miniLineChart());
        VBox pieCard  = card("Workload mix", miniPie());
        HBox.setHgrow(lineCard, Priority.ALWAYS);
        HBox.setHgrow(pieCard, Priority.ALWAYS);
        lineCard.setMinWidth(280);
        pieCard.setMinWidth(280);
        FlowPane charts = new FlowPane(16, 16, lineCard, pieCard);

        VBox body = new VBox(20, kpis, charts);
        return new Section(sectionScroller("Overview",
                "Live KPIs and continuous motion — a quick pulse of the rendering pipeline.",
                body));
    }

    private VBox kpiCard(String label, String value, String delta, boolean up) {
        Label l = new Label(label);
        l.getStyleClass().add("kpi-label");
        Label v = new Label(value);
        v.getStyleClass().add("kpi-value");
        Label d = new Label(delta);
        d.getStyleClass().addAll("kpi-delta", up ? "up" : "down");

        Polyline spark = new Polyline();
        spark.getStyleClass().add("kpi-spark");
        double x = 0;
        double base = 22;
        for (int i = 0; i < 12; i++) {
            spark.getPoints().addAll(x, base - rng.nextDouble() * 20);
            x += 12;
        }

        VBox box = new VBox(6, l, v, d, spark);
        box.getStyleClass().addAll("card", "kpi-card");
        box.setPadding(new Insets(18));
        return box;
    }

    private LineChart<Number, Number> miniLineChart() {
        NumberAxis x = new NumberAxis();
        NumberAxis y = new NumberAxis();
        x.setTickLabelsVisible(false);
        LineChart<Number, Number> lc = new LineChart<>(x, y);
        lc.setCreateSymbols(false);
        lc.setLegendVisible(false);
        lc.setAnimated(false);
        lc.setPrefHeight(220);
        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        double val = 50;
        for (int i = 0; i < 24; i++) {
            val += (rng.nextDouble() - 0.45) * 14;
            s.getData().add(new XYChart.Data<>(i, Math.max(5, val)));
        }
        lc.getData().add(s);
        return lc;
    }

    private PieChart miniPie() {
        PieChart pie = new PieChart(FXCollections.observableArrayList(
                new PieChart.Data("Shapes", 32),
                new PieChart.Data("Text", 24),
                new PieChart.Data("Images", 18),
                new PieChart.Data("Effects", 14),
                new PieChart.Data("Charts", 12)));
        pie.setLegendVisible(true);
        pie.setAnimated(false);
        pie.setPrefHeight(220);
        return pie;
    }

    // =================================================================
    // Section: Controls
    // =================================================================

    private Section buildControls() {
        FlowPane grid = new FlowPane(18, 18);
        grid.getChildren().addAll(
                card("Buttons", buttonsDemo()),
                card("Selection", selectionDemo()),
                card("Inputs", inputsDemo()),
                card("Progress", progressDemo()),
                card("List", listDemo()),
                card("Table", tableDemo()),
                card("Tree", treeDemo()),
                card("Tree table", treeTableDemo()),
                card("Containers", containersDemo()),
                card("Pagination & bars", barsDemo()),
                card("Tooltips & menus", tooltipsAndMenusDemo()));
        return new Section(sectionScroller("Controls",
                "A gallery exercising nearly every javafx.controls node in one place.",
                grid));
    }

    private Node buttonsDemo() {
        Button primary = new Button("Primary");
        primary.getStyleClass().add("btn-accent");
        Button ghost = new Button("Ghost");
        ghost.getStyleClass().add("btn-ghost");

        ToggleGroup tg = new ToggleGroup();
        ToggleButton a = new ToggleButton("On");
        ToggleButton b = new ToggleButton("Off");
        a.setToggleGroup(tg); b.setToggleGroup(tg); a.setSelected(true);

        MenuButton menu = new MenuButton("Menu", null,
                new MenuItem("Action one"), new MenuItem("Action two"));
        SplitMenuButton split = new SplitMenuButton(
                new MenuItem("Save as…"), new MenuItem("Export"));
        split.setText("Save");

        Hyperlink link = new Hyperlink("skia-fx docs");
        Tooltip.install(primary, new Tooltip("A tooltip on the primary button"));

        FlowPane fp = new FlowPane(10, 10, primary, ghost, a, b, menu, split, link);
        fp.setPrefWidth(320);
        return fp;
    }

    /**
     * Tooltips, a context menu and a menu bar — every one of these is a separate
     * popup window rendered through the upload tier, so this card is the place to
     * catch popup/HiDPI rendering and hover regressions: hover the buttons (and
     * the label) for tooltips, right-click the label for a context menu (with a
     * cascading submenu, separators, check + radio items), and open the menu bar
     * menus (including the nested "Open recent" submenu).
     */
    private Node tooltipsAndMenusDemo() {
        Button shortTip = new Button("Short tip");
        Tooltip.install(shortTip, quickTip("A short tooltip."));

        Button wrapTip = new Button("Wrapping tip");
        Tooltip wrap = quickTip("A deliberately long tooltip whose text wraps over "
                + "several lines, so the popup's rounded corners, clip and drop "
                + "shadow are all exercised on 150%/175% monitors.");
        wrap.setWrapText(true);
        wrap.setMaxWidth(240);
        Tooltip.install(wrapTip, wrap);

        Button richTip = new Button("Rich tip");
        Tooltip rich = quickTip("Tooltip with a graphic");
        rich.setGraphic(new Circle(7, Color.web("#5b8cff")));
        Tooltip.install(richTip, rich);

        Label ctxTarget = new Label("Right-click for a context menu");
        ctxTarget.getStyleClass().add("card-title");
        ctxTarget.setContextMenu(sampleContextMenu());
        Tooltip.install(ctxTarget,
                quickTip("This label has a tooltip too — hover, then right-click."));

        MenuBar bar = new MenuBar();
        Menu file = new Menu("File");
        Menu recent = new Menu("Open recent");
        recent.getItems().addAll(new MenuItem("scene-a.fxml"),
                new MenuItem("scene-b.fxml"), new MenuItem("scene-c.fxml"));
        file.getItems().addAll(new MenuItem("New"), new MenuItem("Open…"), recent,
                new SeparatorMenuItem(), new MenuItem("Exit"));
        Menu view = new Menu("View");
        CheckMenuItem wire = new CheckMenuItem("Wireframe");
        ToggleGroup backend = new ToggleGroup();
        RadioMenuItem ganesh = new RadioMenuItem("Ganesh");
        ganesh.setToggleGroup(backend);
        ganesh.setSelected(true);
        RadioMenuItem graphite = new RadioMenuItem("Graphite");
        graphite.setToggleGroup(backend);
        view.getItems().addAll(wire, new SeparatorMenuItem(), ganesh, graphite);
        bar.getMenus().addAll(file, view);

        VBox v = new VBox(12,
                new HBox(10, shortTip, wrapTip, richTip),
                ctxTarget,
                bar);
        v.setPrefWidth(320);
        return v;
    }

    private static Tooltip quickTip(String text) {
        Tooltip t = new Tooltip(text);
        // Short delays so a hover reveals the popup quickly while testing.
        t.setShowDelay(Duration.millis(150));
        t.setShowDuration(Duration.seconds(30));
        t.setHideDelay(Duration.millis(120));
        return t;
    }

    private ContextMenu sampleContextMenu() {
        Menu insert = new Menu("Insert");
        insert.getItems().addAll(new MenuItem("Rectangle"), new MenuItem("Ellipse"),
                new MenuItem("Path"));
        CheckMenuItem snap = new CheckMenuItem("Snap to grid");
        snap.setSelected(true);
        return new ContextMenu(
                new MenuItem("Cut"), new MenuItem("Copy"), new MenuItem("Paste"),
                new SeparatorMenuItem(), insert, new SeparatorMenuItem(), snap);
    }

    private Node selectionDemo() {
        ToggleGroup radios = new ToggleGroup();
        RadioButton r1 = new RadioButton("Skia");
        RadioButton r2 = new RadioButton("Prism");
        r1.setToggleGroup(radios); r2.setToggleGroup(radios); r1.setSelected(true);

        CheckBox c1 = new CheckBox("Vsync");
        CheckBox c2 = new CheckBox("Uncapped");
        c2.setSelected(true);
        CheckBox c3 = new CheckBox("HiDPI");
        c3.setIndeterminate(true);

        VBox v = new VBox(10, new HBox(14, r1, r2), c1, c2, c3);
        v.setPrefWidth(220);
        return v;
    }

    private Node inputsDemo() {
        TextField tf = new TextField();
        tf.setPromptText("Type here…");
        PasswordField pf = new PasswordField();
        pf.setPromptText("Password");

        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(
                "Ganesh", "Graphite", "Software"));
        combo.setEditable(true);
        combo.getSelectionModel().selectFirst();

        ChoiceBox<String> choice = new ChoiceBox<>(FXCollections.observableArrayList(
                "Low", "Medium", "High"));
        choice.getSelectionModel().select(1);

        ColorPicker color = new ColorPicker(Color.web("#5b8cff"));
        DatePicker date = new DatePicker(LocalDate.now());
        Spinner<Integer> spinner = new Spinner<>(0, 240, 144);
        spinner.setEditable(true);
        spinner.setPrefWidth(90);

        VBox v = new VBox(10,
                labeled("Text", tf),
                labeled("Password", pf),
                labeled("Combo", combo),
                labeled("Choice", choice),
                labeled("Colour", color),
                labeled("Date", date),
                labeled("Spinner", spinner));
        v.setPrefWidth(320);
        return v;
    }

    private Node progressDemo() {
        ProgressBar bar = new ProgressBar(0.4);
        bar.setPrefWidth(220);
        ProgressIndicator ind = new ProgressIndicator(0.4);
        Slider slider = new Slider(0, 1, 0.4);
        slider.valueProperty().addListener((o, ov, nv) -> {
            bar.setProgress(nv.doubleValue());
            ind.setProgress(nv.doubleValue());
        });
        ProgressIndicator spin = new ProgressIndicator(); // indeterminate
        spin.setPrefSize(34, 34);

        VBox v = new VBox(12, slider, bar, new HBox(18, ind, spin));
        v.setPrefWidth(260);
        return v;
    }

    private Node listDemo() {
        ListView<String> list = new ListView<>();
        for (int i = 1; i <= 40; i++) list.getItems().add("Render pass #" + i);
        list.setPrefSize(240, 220);
        return list;
    }

    /** Simple JavaFX bean for the table demos (lambda cell factories, no reflection). */
    public static final class Row {
        private final StringProperty name, role, status;
        public Row(String n, String r, String s) {
            name = new SimpleStringProperty(n);
            role = new SimpleStringProperty(r);
            status = new SimpleStringProperty(s);
        }
        public StringProperty nameProperty()   { return name; }
        public StringProperty roleProperty()   { return role; }
        public StringProperty statusProperty() { return status; }
    }

    private Node tableDemo() {
        TableView<Row> table = new TableView<>();
        TableColumn<Row, String> c1 = new TableColumn<>("Node");
        c1.setCellValueFactory(cd -> cd.getValue().nameProperty());
        TableColumn<Row, String> c2 = new TableColumn<>("Kind");
        c2.setCellValueFactory(cd -> cd.getValue().roleProperty());
        TableColumn<Row, String> c3 = new TableColumn<>("State");
        c3.setCellValueFactory(cd -> cd.getValue().statusProperty());
        table.getColumns().add(c1);
        table.getColumns().add(c2);
        table.getColumns().add(c3);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        for (int i = 1; i <= 30; i++) {
            table.getItems().add(new Row("Layer " + i,
                    (i % 2 == 0) ? "Shape" : "Text",
                    (i % 3 == 0) ? "cached" : "dirty"));
        }
        table.setPrefSize(320, 220);
        return table;
    }

    private Node treeDemo() {
        TreeItem<String> root = new TreeItem<>("Scene");
        root.setExpanded(true);
        for (int g = 1; g <= 3; g++) {
            TreeItem<String> group = new TreeItem<>("Group " + g);
            for (int n = 1; n <= 3; n++) group.getChildren().add(new TreeItem<>("Node " + g + "." + n));
            group.setExpanded(true);
            root.getChildren().add(group);
        }
        TreeView<String> tree = new TreeView<>(root);
        tree.setPrefSize(240, 220);
        return tree;
    }

    private Node treeTableDemo() {
        TreeItem<Row> root = new TreeItem<>(new Row("Root", "Group", "—"));
        root.setExpanded(true);
        for (int i = 1; i <= 4; i++) {
            root.getChildren().add(new TreeItem<>(
                    new Row("Region " + i, "Region", i % 2 == 0 ? "ok" : "warn")));
        }
        TreeTableView<Row> tt = new TreeTableView<>(root);
        TreeTableColumn<Row, String> a = new TreeTableColumn<>("Name");
        a.setCellValueFactory(cd -> cd.getValue().getValue().nameProperty());
        TreeTableColumn<Row, String> b = new TreeTableColumn<>("State");
        b.setCellValueFactory(cd -> cd.getValue().getValue().statusProperty());
        tt.getColumns().add(a);
        tt.getColumns().add(b);
        tt.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        tt.setPrefSize(280, 220);
        return tt;
    }

    private Node containersDemo() {
        TabPane tabs = new TabPane();
        tabs.getTabs().addAll(
                new Tab("Render", new Label("  Render settings…  ")),
                new Tab("Memory", new Label("  Memory budgets…  ")),
                new Tab("About", new Label("  skia-fx pipeline  ")));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setPrefHeight(120);

        Accordion acc = new Accordion(
                new TitledPane("Glyph atlas", new Label("16 MB soft / 64 MB hard")),
                new TitledPane("Image atlas", new Label("32 MB soft / 128 MB hard")),
                new TitledPane("Picture cache", new Label("32 MB soft / 128 MB hard")));

        SplitPane split = new SplitPane(new Label("  Left  "), new Label("  Right  "));
        split.setPrefHeight(70);

        VBox v = new VBox(12, tabs, acc, split);
        v.setPrefWidth(320);
        return v;
    }

    private Node barsDemo() {
        MenuBar menuBar = new MenuBar(
                new Menu("File", null, new MenuItem("New"), new MenuItem("Open")),
                new Menu("Edit", null, new MenuItem("Undo"), new MenuItem("Redo")),
                new Menu("View", null, new MenuItem("Zoom in"), new MenuItem("Zoom out")));

        ToolBar toolBar = new ToolBar(
                new Button("Cut"), new Button("Copy"), new Separator(),
                new ToggleButton("B"), new ToggleButton("I"));

        Pagination pagination = new Pagination(8, 0);
        pagination.setPageFactory(i -> {
            Label l = new Label("Page " + (i + 1) + " of 8");
            l.setPadding(new Insets(14));
            return l;
        });
        pagination.setPrefHeight(120);

        VBox v = new VBox(12, menuBar, toolBar, pagination);
        v.setPrefWidth(320);
        return v;
    }

    // =================================================================
    // Section: Charts
    // =================================================================

    private Section buildCharts() {
        FlowPane grid = new FlowPane(18, 18);
        grid.getChildren().addAll(
                chartCard("Line", lineChart()),
                chartCard("Area", areaChart()),
                chartCard("Stacked area", stackedAreaChart()),
                chartCard("Bar", barChart()),
                chartCard("Stacked bar", stackedBarChart()),
                chartCard("Scatter", scatterChart()),
                chartCard("Bubble", bubbleChart()),
                chartCard("Pie", miniPie()));
        return new Section(sectionScroller("Charts",
                "Every javafx.scene.chart type, fed with live randomised data.", grid));
    }

    private VBox chartCard(String title, Node chart) {
        chart.setStyle("-fx-min-width: 340; -fx-min-height: 240;");
        VBox c = card(title, chart);
        c.setPrefWidth(380);
        return c;
    }

    private XYChart.Series<Number, Number> numberSeries(String name, int n) {
        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        s.setName(name);
        double v = 50;
        for (int i = 0; i < n; i++) {
            v += (rng.nextDouble() - 0.45) * 18;
            s.getData().add(new XYChart.Data<>(i, Math.max(2, v)));
        }
        return s;
    }

    private XYChart.Series<String, Number> catSeries(String name, String[] cats) {
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        s.setName(name);
        for (String c : cats) s.getData().add(new XYChart.Data<>(c, 10 + rng.nextDouble() * 90));
        return s;
    }

    private LineChart<Number, Number> lineChart() {
        LineChart<Number, Number> c = new LineChart<>(new NumberAxis(), new NumberAxis());
        c.setAnimated(false);
        c.getData().add(numberSeries("CPU", 20));
        c.getData().add(numberSeries("GPU", 20));
        return c;
    }

    private AreaChart<Number, Number> areaChart() {
        AreaChart<Number, Number> c = new AreaChart<>(new NumberAxis(), new NumberAxis());
        c.setAnimated(false);
        c.getData().add(numberSeries("Frames", 16));
        return c;
    }

    private StackedAreaChart<Number, Number> stackedAreaChart() {
        StackedAreaChart<Number, Number> c = new StackedAreaChart<>(new NumberAxis(), new NumberAxis());
        c.setAnimated(false);
        c.getData().add(numberSeries("Paint", 16));
        c.getData().add(numberSeries("Sync", 16));
        return c;
    }

    private BarChart<String, Number> barChart() {
        String[] cats = {"Q1", "Q2", "Q3", "Q4"};
        BarChart<String, Number> c = new BarChart<>(new CategoryAxis(), new NumberAxis());
        c.setAnimated(false);
        c.getData().add(catSeries("Skia", cats));
        c.getData().add(catSeries("Prism", cats));
        return c;
    }

    private StackedBarChart<String, Number> stackedBarChart() {
        String[] cats = {"Mon", "Tue", "Wed", "Thu", "Fri"};
        StackedBarChart<String, Number> c = new StackedBarChart<>(new CategoryAxis(), new NumberAxis());
        c.setAnimated(false);
        c.getData().add(catSeries("Shapes", cats));
        c.getData().add(catSeries("Text", cats));
        return c;
    }

    private ScatterChart<Number, Number> scatterChart() {
        ScatterChart<Number, Number> c = new ScatterChart<>(new NumberAxis(), new NumberAxis());
        c.setAnimated(false);
        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        for (int i = 0; i < 40; i++) s.getData().add(new XYChart.Data<>(rng.nextDouble() * 100, rng.nextDouble() * 100));
        c.getData().add(s);
        return c;
    }

    private BubbleChart<Number, Number> bubbleChart() {
        BubbleChart<Number, Number> c = new BubbleChart<>(new NumberAxis(), new NumberAxis());
        c.setAnimated(false);
        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        for (int i = 0; i < 16; i++) {
            s.getData().add(new XYChart.Data<>(rng.nextDouble() * 100, rng.nextDouble() * 100, rng.nextDouble() * 12));
        }
        c.getData().add(s);
        return c;
    }

    // =================================================================
    // Section: Shapes & Canvas
    // =================================================================

    private Section buildShapes() {
        FlowPane shapes = new FlowPane(16, 16);
        shapes.getChildren().addAll(
                shapeTile("Rectangle", roundedRect()),
                shapeTile("Circle", new Circle(34, accentFill())),
                shapeTile("Ellipse", ellipse()),
                shapeTile("Line", line()),
                shapeTile("Polygon", polygon()),
                shapeTile("Polyline", polyline()),
                shapeTile("Arc", arc()),
                shapeTile("QuadCurve", quad()),
                shapeTile("CubicCurve", cubic()),
                shapeTile("Path", pathShape()),
                shapeTile("SVGPath", svgShape()),
                shapeTile("Text", gradientText()));

        Canvas canvas = new Canvas(640, 300);
        CanvasField field = new CanvasField(canvas);
        StackPane canvasWrap = new StackPane(canvas);
        canvasWrap.getStyleClass().add("card");
        canvasWrap.setPadding(new Insets(6));
        canvas.widthProperty().bind(canvasWrap.widthProperty().subtract(12));
        canvas.heightProperty().bind(canvasWrap.heightProperty().subtract(12));
        // A Canvas is NOT resizable, so it reports its current width/height as its
        // MIN and PREF size, and the StackPane derives its own size from that
        // child. With the canvas bound to the wrapper's size, that is a feedback
        // ratchet in BOTH axes: the wrapper can never shrink below the canvas, so
        // every resize grows it further and it overflows the viewport (the
        // content gets wider/taller than the window, even maximized). Break the
        // derive-from-child loop by giving the wrapper EXPLICIT min/pref/max:
        // width 0 (the VBox stretches it to the viewport width via fillWidth, and
        // the bound canvas follows), height fixed at 320. The canvas then simply
        // tracks the wrapper instead of driving it.
        canvasWrap.setMinWidth(0);
        canvasWrap.setPrefWidth(0);
        canvasWrap.setMinHeight(320);
        canvasWrap.setPrefHeight(320);
        canvasWrap.setMaxHeight(320);
        VBox canvasCard = new VBox(12, new Label("Animated Canvas (GraphicsContext)") {{
            getStyleClass().add("card-title");
        }}, canvasWrap);

        VBox body = new VBox(20, card("Shape primitives", shapes), canvasCard);
        return new Section(
                sectionScroller("Shapes & Canvas",
                        "Geometry primitives plus a live GraphicsContext particle wave.", body),
                field::start, field::stop);
    }

    private Region shapeTile(String name, Node shape) {
        StackPane art = new StackPane(shape);
        art.setMinSize(120, 90);
        art.setPrefSize(120, 90);
        VBox tile = new VBox(8, art, chip(name));
        tile.setAlignment(Pos.CENTER);
        tile.getStyleClass().add("card");
        tile.setPadding(new Insets(12));
        return tile;
    }

    private static Color accentFill() { return Color.web("#5b8cff"); }

    private Node roundedRect() {
        Rectangle r = new Rectangle(90, 56, accentFill());
        r.setArcWidth(22); r.setArcHeight(22);
        return r;
    }
    private Node ellipse() { return new Ellipse(45, 30) {{ setFill(Color.web("#9b5bff")); }}; }
    private Node line() {
        Line l = new Line(0, 0, 90, 50);
        l.setStroke(accentFill()); l.setStrokeWidth(4);
        return l;
    }
    private Node polygon() {
        Polygon p = new Polygon(45, 0, 90, 34, 72, 86, 18, 86, 0, 34);
        p.setFill(Color.web("#18b663"));
        return p;
    }
    private Node polyline() {
        Polyline p = new Polyline(0, 60, 20, 20, 40, 50, 60, 10, 90, 55);
        p.setStroke(Color.web("#f0a020")); p.setStrokeWidth(3); p.setFill(null);
        return p;
    }
    private Node arc() {
        Arc a = new Arc(45, 45, 40, 40, 30, 250);
        a.setType(ArcType.ROUND);
        a.setFill(Color.web("#ef4d61"));
        return a;
    }
    private Node quad() {
        QuadCurve q = new QuadCurve(0, 80, 45, -20, 90, 80);
        q.setStroke(accentFill()); q.setStrokeWidth(3); q.setFill(null);
        return q;
    }
    private Node cubic() {
        CubicCurve c = new CubicCurve(0, 70, 20, 0, 70, 100, 90, 20);
        c.setStroke(Color.web("#9b5bff")); c.setStrokeWidth(3); c.setFill(null);
        return c;
    }
    private Node pathShape() {
        Path p = new Path(
                new MoveTo(10, 80), new LineTo(45, 10), new LineTo(80, 80), new ClosePath());
        p.setStroke(Color.web("#18b663")); p.setStrokeWidth(3);
        p.setFill(Color.web("#18b66333"));
        return p;
    }
    private Node svgShape() {
        SVGPath s = new SVGPath();
        s.setContent("M45,5 L56,34 L88,34 L62,52 L72,82 L45,64 L18,82 L28,52 L2,34 L34,34 Z");
        s.setFill(Color.web("#f0a020"));
        return s;
    }
    private Node gradientText() {
        Text t = new Text("Skia");
        t.setFont(Font.font("Segoe UI", FontWeight.BOLD, 40));
        t.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#5b8cff")), new Stop(1, Color.web("#9b5bff"))));
        return t;
    }

    /** A GraphicsContext particle-wave, driven by an AnimationTimer. */
    private static final class CanvasField {
        private final Canvas canvas;
        private AnimationTimer timer;
        private long startNanos = 0;

        CanvasField(Canvas canvas) { this.canvas = canvas; }

        void start() {
            if (timer != null) return;
            startNanos = 0;
            timer = new AnimationTimer() {
                @Override public void handle(long now) {
                    if (startNanos == 0) startNanos = now;
                    draw((now - startNanos) / 1_000_000_000.0);
                }
            };
            timer.start();
        }

        void stop() { if (timer != null) { timer.stop(); timer = null; } }

        private void draw(double t) {
            double w = canvas.getWidth(), h = canvas.getHeight();
            GraphicsContext g = canvas.getGraphicsContext2D();
            g.clearRect(0, 0, w, h);
            int cols = 64;
            double step = w / cols;
            for (int i = 0; i < cols; i++) {
                double x = i * step;
                double phase = t * 1.4 + i * 0.18;
                double y = h * 0.5 + Math.sin(phase) * h * 0.28 * Math.cos(t * 0.6 + i * 0.05);
                double r = 3 + (Math.sin(phase) + 1) * 3;
                double hue = (i * 5 + t * 60) % 360;
                g.setFill(Color.hsb(hue, 0.6, 0.95, 0.9));
                g.fillOval(x - r, y - r, r * 2, r * 2);
            }
        }
    }

    // =================================================================
    // Section: Effects
    // =================================================================

    private Section buildEffects() {
        FlowPane grid = new FlowPane(16, 16);
        grid.getChildren().addAll(
                effectTile("DropShadow", new DropShadow(18, Color.web("#5b8cff"))),
                effectTile("Glow", new Glow(0.8)),
                effectTile("GaussianBlur", new GaussianBlur(8)),
                effectTile("BoxBlur", new BoxBlur(8, 8, 3)),
                effectTile("InnerShadow", new InnerShadow(14, Color.web("#9b5bff"))),
                effectTile("Reflection", new Reflection()),
                effectTile("SepiaTone", new SepiaTone()),
                effectTile("Bloom", new Bloom(0.4)),
                effectTile("Lighting", lighting()),
                effectTile("MotionBlur", new MotionBlur(45, 12)),
                effectTile("ColorAdjust", colorAdjust()));

        // A live blur whose radius tracks a slider.
        Text liveText = bigLabel("blur");
        GaussianBlur liveBlur = new GaussianBlur(2);
        liveText.setEffect(liveBlur);
        Slider radius = new Slider(0, 24, 2);
        radius.valueProperty().addListener((o, ov, nv) -> liveBlur.setRadius(nv.doubleValue()));
        StackPane stage = new StackPane(liveText);
        stage.setMinHeight(120);
        VBox liveCard = card("Live GaussianBlur", new VBox(12, stage, labeled("Radius", radius)));

        VBox body = new VBox(20, card("Effect gallery", grid), liveCard);
        return new Section(sectionScroller("Effects",
                "javafx.scene.effect filters mapped onto Skia's SkImageFilter chains.", body));
    }

    private Region effectTile(String name, Effect effect) {
        Text t = bigLabel("Fx");
        t.setEffect(effect);
        StackPane art = new StackPane(t);
        art.setMinSize(130, 96);
        VBox tile = new VBox(8, art, chip(name));
        tile.setAlignment(Pos.CENTER);
        tile.getStyleClass().add("card");
        tile.setPadding(new Insets(12));
        return tile;
    }

    private Text bigLabel(String s) {
        Text t = new Text(s);
        // Font size via CSS (.fx-glyph), NOT setFont(): a programmatic font is
        // overridden by .root's inherited -fx-font-family on re-style (focus/
        // click), which shrinks the glyph. See showcase.css ".fx-glyph".
        t.getStyleClass().add("fx-glyph");
        t.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#5b8cff")), new Stop(1, Color.web("#9b5bff"))));
        return t;
    }

    private Effect lighting() {
        Lighting l = new Lighting(new Light.Distant(225, 55, Color.WHITE));
        l.setSurfaceScale(6);
        return l;
    }

    private Effect colorAdjust() {
        ColorAdjust ca = new ColorAdjust();
        ca.setHue(0.4);
        ca.setSaturation(0.5);
        ca.setBrightness(0.05);
        return ca;
    }

    // =================================================================
    // Section: Animation Lab
    // =================================================================

    private Section buildAnimation() {
        // The thing we animate: a rounded gradient card with a label.
        Rectangle box = new Rectangle(150, 96);
        box.setArcWidth(26); box.setArcHeight(26);
        box.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#5b8cff")), new Stop(1, Color.web("#9b5bff"))));
        box.setStroke(Color.web("#5b8cff"));
        box.setStrokeWidth(0);
        Text label = new Text("skia-fx");
        label.setFont(Font.font("Segoe UI", FontWeight.BOLD, 20));
        label.setFill(Color.WHITE);
        StackPane target = new StackPane(box, label);

        // A faint path for the PathTransition to follow.
        Path track = new Path(new MoveTo(40, 60), new LineTo(360, 60),
                new LineTo(360, 180), new LineTo(40, 180), new ClosePath());
        track.setStroke(Color.web("#5b8cff55"));
        track.setStrokeWidth(1.5);
        track.setFill(null);

        Group stageArea = new Group(track, target);
        target.setLayoutX(110); target.setLayoutY(70);
        StackPane stageWrap = new StackPane(stageArea);
        stageWrap.getStyleClass().add("card");
        stageWrap.setMinHeight(280);
        stageWrap.setPadding(new Insets(8));

        FlowPane buttons = new FlowPane(10, 10,
                animBtn("Fade",     () -> { FadeTransition t = new FadeTransition(Duration.millis(600), target); t.setFromValue(1); t.setToValue(0.15); t.setAutoReverse(true); t.setCycleCount(2); return t; }),
                animBtn("Translate",() -> { TranslateTransition t = new TranslateTransition(Duration.millis(600), target); t.setByX(120); t.setAutoReverse(true); t.setCycleCount(2); return t; }),
                animBtn("Scale",    () -> { ScaleTransition t = new ScaleTransition(Duration.millis(500), target); t.setToX(1.4); t.setToY(1.4); t.setAutoReverse(true); t.setCycleCount(2); return t; }),
                animBtn("Rotate",   () -> { RotateTransition t = new RotateTransition(Duration.millis(700), target); t.setByAngle(360); return t; }),
                animBtn("Fill",     () -> { FillTransition t = new FillTransition(Duration.millis(700), box, Color.web("#5b8cff"), Color.web("#18b663")); t.setAutoReverse(true); t.setCycleCount(2); return t; }),
                animBtn("Stroke",   () -> { box.setStrokeWidth(4); StrokeTransition t = new StrokeTransition(Duration.millis(700), box, Color.web("#5b8cff"), Color.web("#f0a020")); t.setAutoReverse(true); t.setCycleCount(2); return t; }),
                animBtn("Path",     () -> { PathTransition t = new PathTransition(Duration.millis(1800), track, target); t.setOrientation(PathTransition.OrientationType.NONE); return t; }),
                animBtn("Parallel", () -> { ScaleTransition s = new ScaleTransition(Duration.millis(700), target); s.setToX(1.3); s.setToY(1.3); s.setAutoReverse(true); s.setCycleCount(2); RotateTransition r = new RotateTransition(Duration.millis(700), target); r.setByAngle(180); return new ParallelTransition(s, r); }),
                animBtn("Sequential",() -> { TranslateTransition a = new TranslateTransition(Duration.millis(400), target); a.setByX(100); FadeTransition b = new FadeTransition(Duration.millis(400), target); b.setToValue(0.3); b.setAutoReverse(true); b.setCycleCount(2); TranslateTransition c = new TranslateTransition(Duration.millis(400), target); c.setByX(-100); return new SequentialTransition(a, b, c); }));

        VBox body = new VBox(18, stageWrap, card("Transitions", buttons));
        return new Section(sectionScroller("Animation Lab",
                "Fire every Transition type at one node — all wall-clock driven.", body));
    }

    private interface AnimFactory { javafx.animation.Animation make(); }

    private Button animBtn(String name, AnimFactory factory) {
        Button b = new Button(name);
        b.getStyleClass().add("btn-ghost");
        b.setOnAction(e -> factory.make().play());
        return b;
    }

    // =================================================================
    // Section: Benchmark
    // =================================================================

    private Section buildBenchmark() {
        StressScene.BenchPanel panel = new StressScene.BenchPanel(1200);
        benchPanel = panel;
        ScrollPane sp = new ScrollPane(panel);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.getStyleClass().add("content-area");
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return new Section(sp, panel::start, panel::stop);
    }
}
