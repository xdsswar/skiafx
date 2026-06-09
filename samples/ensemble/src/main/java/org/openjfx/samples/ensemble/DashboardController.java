/*
 * DashboardController — the brain of the Numa Dashboard demo.
 *
 * Owns the animated collapsible icon-rail sidebar, the custom title-bar hit
 * regions, section switching (with a fade/slide-in), the global FPS badge,
 * and every section body. The shell is FXML (dashboard-demo.fxml); the
 * heavier section bodies are built here in code and cached lazily.
 *
 * Built in chunks; each section is an independent builder so the file stays
 * navigable.
 */
package org.openjfx.samples.ensemble;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Accordion;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Pagination;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.SVGPath;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

public final class DashboardController {

    // Caption glyphs (0..10 grid, stroked) — morph max⇄restore.
    private static final String SVG_MAX     = "M0.5,0.5 H9.5 V9.5 H0.5 Z";
    private static final String SVG_RESTORE =
            "M0.5,2.5 H7.5 V9.5 H0.5 Z M2.5,2.5 V0.5 H9.5 V7.5 H7.5";

    // ---- FXML shell nodes -------------------------------------------------
    @FXML private HBox        rootPane;
    @FXML private VBox        sidebar;
    @FXML private HBox        sidebarHeader;
    @FXML private Label       brandLabel;
    @FXML private Label       navSectionPages;
    @FXML private Label       navSectionGeneral;
    @FXML private ToggleButton navDashboard;
    @FXML private ToggleButton navSwap;
    @FXML private ToggleButton navRegistration;
    @FXML private ToggleButton navAnalytics;
    @FXML private ToggleButton navControls;
    @FXML private ToggleButton navSettings;
    @FXML private HBox        userCell;
    @FXML private VBox        userText;
    @FXML private VBox        contentColumn;
    @FXML private HBox        topStrip;
    @FXML private StackPane   hamburger;
    @FXML private Label       pageTitle;
    @FXML private Region      captionRegion;
    @FXML private HBox        searchBox;
    @FXML private javafx.scene.control.TextField search;
    @FXML private StackPane   bellBtn;
    @FXML private HBox        profileChip;
    @FXML private HBox        winButtons;
    @FXML private StackPane   minBtn;
    @FXML private StackPane   maxBtn;
    @FXML private SVGPath     maxIcon;
    @FXML private StackPane   closeBtn;
    @FXML private StackPane   contentStack;
    @FXML private Label       statusText;
    @FXML private Label       sizeLabel;
    @FXML private Label       fpsBadge;

    // ---- state ------------------------------------------------------------
    private final ToggleGroup navGroup = new ToggleGroup();
    private final Map<String, ToggleButton> nav = new LinkedHashMap<>();
    private final Map<String, Node> cache = new java.util.HashMap<>();
    private boolean expanded = true;
    private String current;
    private Stage stage;

    // Registration step state (used by the registration section).
    private static final String[] REG_STEPS =
            {"Personal Details", "Agency Details", "Contact Person", "Work Order Scope"};
    private int regStep = 1;          // land on "Agency Details" like the reference
    private HBox regStepper;
    private StackPane regBody;

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @FXML
    private void initialize() {
        nav.put("dashboard",    navDashboard);
        nav.put("swap",         navSwap);
        nav.put("registration", navRegistration);
        nav.put("analytics",    navAnalytics);
        nav.put("controls",     navControls);
        nav.put("settings",     navSettings);

        nav.forEach((key, tb) -> {
            tb.setToggleGroup(navGroup);
            tb.setMaxWidth(Double.MAX_VALUE);
            tb.setTooltip(new Tooltip(titleOf(key)));
            tb.setOnAction(e -> showSection(key));
        });
        // never let the highlight go empty
        navGroup.selectedToggleProperty().addListener((o, ov, nv) -> {
            if (nv == null && ov != null) ov.setSelected(true);
        });

        hamburger.setOnMouseClicked(e -> toggleSidebar());
        setupResponsive();
        startFpsTimer();

        navDashboard.setSelected(true);
        showSection("dashboard");
    }

    /** Wire the custom chrome once the Stage exists (called by DashboardDemo). */
    void installStage(Stage stage) {
        this.stage = stage;
        // Drag zones: the brand header and the flexible caption strip.
        stage.setCaptionRegions(sidebarHeader, captionRegion);
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

    // =====================================================================
    // Sidebar collapse / responsive
    // =====================================================================

    private void toggleSidebar() { setExpanded(!expanded, true); }

    private void setExpanded(boolean exp, boolean animate) {
        if (exp == expanded && animate) return;
        expanded = exp;
        double w = exp ? 252 : 76;

        nav.values().forEach(tb ->
                tb.setContentDisplay(exp ? ContentDisplay.LEFT : ContentDisplay.GRAPHIC_ONLY));

        if (exp) sidebar.getStyleClass().remove("collapsed");
        else if (!sidebar.getStyleClass().contains("collapsed")) sidebar.getStyleClass().add("collapsed");

        toggleVis(brandLabel, exp);
        toggleVis(navSectionPages, exp);
        toggleVis(navSectionGeneral, exp);
        toggleVis(userText, exp);

        if (animate) {
            Timeline t = new Timeline(new KeyFrame(Duration.millis(220),
                    new KeyValue(sidebar.prefWidthProperty(), w, Interpolator.EASE_BOTH),
                    new KeyValue(sidebar.minWidthProperty(),  w, Interpolator.EASE_BOTH),
                    new KeyValue(sidebar.maxWidthProperty(),  w, Interpolator.EASE_BOTH)));
            t.play();
        } else {
            sidebar.setPrefWidth(w); sidebar.setMinWidth(w); sidebar.setMaxWidth(w);
        }
    }

    private static void toggleVis(Node n, boolean show) {
        n.setVisible(show);
        n.setManaged(show);
    }

    /** Auto-collapse below a width threshold, re-expand above it. */
    private void setupResponsive() {
        rootPane.widthProperty().addListener((o, ov, nv) -> {
            double w = nv.doubleValue(), old = ov.doubleValue();
            if (w < 1000 && old >= 1000 && expanded)       setExpanded(false, true);
            else if (w >= 1000 && old < 1000 && !expanded) setExpanded(true, true);
        });
    }

    // =====================================================================
    // Section switching (fade/slide-in)
    // =====================================================================

    private void showSection(String key) {
        if (key.equals(current)) return;
        current = key;
        pageTitle.setText(titleOf(key));
        statusText.setText(titleOf(key));

        Node body = cache.computeIfAbsent(key, this::buildSection);
        contentStack.getChildren().setAll(body);
        animateIn(body);
    }

    private Node buildSection(String key) {
        return switch (key) {
            case "dashboard"    -> buildDashboard();
            case "swap"         -> buildSwap();
            case "registration" -> buildRegistration();
            case "analytics"    -> buildAnalytics();
            case "controls"     -> buildControls();
            case "settings"     -> buildSettings();
            default             -> new Label("Unknown: " + key);
        };
    }

    private static String titleOf(String key) {
        return switch (key) {
            case "dashboard"    -> "Dashboard";
            case "swap"         -> "Swap";
            case "registration" -> "Registration";
            case "analytics"    -> "Analytics";
            case "controls"     -> "Controls";
            case "settings"     -> "Settings";
            default             -> key;
        };
    }

    private static void animateIn(Node n) {
        n.setOpacity(0);
        n.setTranslateY(16);
        FadeTransition f = new FadeTransition(Duration.millis(320), n);
        f.setToValue(1);
        TranslateTransition t = new TranslateTransition(Duration.millis(360), n);
        t.setToY(0);
        t.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(f, t).play();
    }

    // =====================================================================
    // FPS badge
    // =====================================================================

    private void startFpsTimer() {
        new AnimationTimer() {
            long windowStart = 0;
            int frames = 0;
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

    // =====================================================================
    // Shared building blocks
    // =====================================================================

    /** A titled, scrollable section wrapper with a transparent viewport. */
    private static ScrollPane sectionScroller(String title, String sub, Node body) {
        Label t = new Label(title);
        t.getStyleClass().add("section-title");
        Label s = new Label(sub);
        s.getStyleClass().add("section-sub");
        VBox head = new VBox(4, t, s);

        VBox content = new VBox(20, head, body);
        content.getStyleClass().add("section");

        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("content-area");
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return sp;
    }

    /** A generic white card with a bold title over arbitrary content. */
    static VBox card(String title, Node content) {
        Label t = new Label(title);
        t.getStyleClass().add("card-title");
        VBox v = new VBox(14, t, content);
        v.getStyleClass().add("card");
        v.setPadding(new Insets(18));
        return v;
    }

    /** A stroked SVGPath glyph on a 24-grid. */
    static SVGPath thinIcon(String svg) {
        SVGPath p = new SVGPath();
        p.setContent(svg);
        p.getStyleClass().add("thin-icon");
        return p;
    }

    static Label chip(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("chip");
        return l;
    }

    static StackPane avatar(String initials, String extra) {
        Label l = new Label(initials);
        l.getStyleClass().add("avatar-text");
        StackPane sp = new StackPane(l);
        sp.getStyleClass().addAll("avatar", extra);
        return sp;
    }

    // =====================================================================
    // Section: Dashboard
    // =====================================================================

    private Node buildDashboard() {
        FlowPane kpis = new FlowPane(16, 16,
                statCard("M12 1v22 M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6",
                        "Balance", "$48,250", "+12.4%", true),
                statCard("M3 3v18h18 M7 14l3-3 3 3 5-6",
                        "Active trades", "1,284", "+5.1%", true),
                statCard("M20 6L9 17l-5-5",
                        "Completed", "3,914", "+8.2%", true),
                statCard("M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9",
                        "Alerts", "27", "-3 today", false));
        kpis.setPrefWrapLength(1100);

        AreaChart<Number, Number> chart = emeraldArea(new double[]{
                32, 35, 33, 40, 44, 42, 48, 52, 49, 55, 58, 61, 59, 64});
        chart.setPrefHeight(300);
        VBox chartCard = card("Portfolio value", chart);
        HBox.setHgrow(chartCard, Priority.ALWAYS);
        chartCard.setMinWidth(360);

        VBox feed = card("Recent activity", new VBox(6,
                activityRow("AM", "Ava Morgan", "swapped 0.5 ETH → BTC", "2m"),
                activityRow("RK", "Ravi Kapoor", "deposited $1,200", "18m"),
                activityRow("LS", "Lena Soto", "withdrew $480", "1h"),
                activityRow("DT", "Diego Torres", "added a watchlist", "3h"),
                activityRow("YN", "Yuki Nakamura", "verified identity", "5h")));
        feed.setMinWidth(320);
        feed.setPrefWidth(340);

        HBox row = new HBox(16, chartCard, feed);

        VBox body = new VBox(20, kpis, row);
        return sectionScroller("Good afternoon, Austin",
                "Here's what's happening across your workspace today.", body);
    }

    private Region statCard(String iconSvg, String label, String value, String delta, boolean up) {
        StackPane ic = new StackPane(thinIcon(iconSvg));
        ic.getStyleClass().add("stat-icon");

        Label l = new Label(label);
        l.getStyleClass().add("stat-label");
        Label v = new Label(value);
        v.getStyleClass().add("stat-value");
        Label d = new Label(delta);
        d.getStyleClass().addAll("stat-delta", up ? "up" : "down");

        Polyline spark = new Polyline();
        spark.getStyleClass().add("stat-spark");
        double[] ys = {20, 14, 17, 9, 13, 6, 11, 4};
        for (int i = 0; i < ys.length; i++) spark.getPoints().addAll(i * 16.0, ys[i]);

        VBox texts = new VBox(4, l, v, d);
        HBox top = new HBox(14, ic, texts);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, top, spark);
        box.getStyleClass().addAll("card", "stat-card");
        box.setPadding(new Insets(18));
        return box;
    }

    private static Node activityRow(String initials, String who, String what, String when) {
        Text w = new Text(who + "  ");
        w.getStyleClass().add("act-who");
        Text a = new Text(what);
        a.getStyleClass().add("act-what");
        TextFlow flow = new TextFlow(w, a);
        Label t = new Label(when);
        t.getStyleClass().add("act-when");
        Region s = new Region();
        HBox.setHgrow(s, Priority.ALWAYS);
        HBox row = new HBox(12, avatar(initials, "avatar-feed"), flow, s, t);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("act-row");
        return row;
    }

    /** Emerald area chart fed with a fixed value series. */
    private AreaChart<Number, Number> emeraldArea(double[] vals) {
        NumberAxis x = new NumberAxis();
        x.setTickLabelsVisible(false);
        x.setMinorTickVisible(false);
        NumberAxis y = new NumberAxis();
        AreaChart<Number, Number> c = new AreaChart<>(x, y);
        c.getStyleClass().add("area-emerald");
        c.setLegendVisible(false);
        c.setCreateSymbols(false);
        c.setAnimated(false);
        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        for (int i = 0; i < vals.length; i++) s.getData().add(new XYChart.Data<>(i, vals[i]));
        c.getData().add(s);
        return c;
    }

    private static PieChart donut() {
        PieChart pie = new PieChart(FXCollections.observableArrayList(
                new PieChart.Data("ETH", 42),
                new PieChart.Data("BTC", 28),
                new PieChart.Data("USDT", 18),
                new PieChart.Data("Other", 12)));
        pie.setLegendVisible(true);
        pie.setAnimated(false);
        pie.setPrefHeight(240);
        return pie;
    }

    // =====================================================================
    // Sections still to be filled in (chunk 2+). Stubs keep the build green.
    // =====================================================================

    private Node buildSwap() {
        // ---- left: the swap card ----
        Label swapTitle = new Label("Swap");
        swapTitle.getStyleClass().add("card-title");
        ToggleButton cryptoTab = new ToggleButton("Crypto");
        cryptoTab.getStyleClass().add("seg-toggle");
        cryptoTab.setSelected(true);
        Region th = new Region();
        HBox.setHgrow(th, Priority.ALWAYS);
        HBox swapHead = new HBox(swapTitle, th, cryptoTab);
        swapHead.setAlignment(Pos.CENTER_LEFT);

        VBox swapCard = new VBox(12,
                swapHead,
                fieldLabel("You pay"),
                swapRow("27.0458", "ETH", "#627eea"),
                flipRow(),
                fieldLabel("You receive"),
                swapRow("1.3421", "BTC", "#f7931a"),
                new Separator(),
                infoRow("Minimum received", "1.3387 BTC"),
                infoRow("Gas fee", "$16.34"),
                infoRow("Price impact", "+0.0%"),
                swapButton());
        swapCard.getStyleClass().add("card");
        swapCard.setPadding(new Insets(18));
        swapCard.setMinWidth(360);
        swapCard.setMaxWidth(380);

        // ---- right: price + chart + route ----
        Label price = new Label("1,859.07 USDT");
        price.getStyleClass().add("price-big");
        Label pair = new Label("ETH / USDT");
        pair.getStyleClass().add("card-sub");
        Label up = new Label("▲ 2.84% · 24h");
        up.getStyleClass().add("price-up");
        VBox priceBox = new VBox(2, price, pair);
        Region ph = new Region();
        HBox.setHgrow(ph, Priority.ALWAYS);

        ToggleGroup tf = new ToggleGroup();
        HBox segs = new HBox(4);
        for (String s : new String[]{"1H", "1D", "1W", "1M", "1Y"}) {
            ToggleButton b = new ToggleButton(s);
            b.getStyleClass().add("seg-toggle");
            b.setToggleGroup(tf);
            if (s.equals("1W")) b.setSelected(true);
            segs.getChildren().add(b);
        }
        VBox segWrap = new VBox(6, up, segs);
        segWrap.setAlignment(Pos.CENTER_RIGHT);
        HBox chartHead = new HBox(14, priceBox, ph, segWrap);
        chartHead.setAlignment(Pos.CENTER_LEFT);

        AreaChart<Number, Number> chart = emeraldArea(new double[]{
                1810, 1825, 1818, 1840, 1835, 1852, 1848, 1859, 1872, 1865, 1858, 1859});
        chart.setPrefHeight(280);

        VBox chartCard = new VBox(14, chartHead, chart);
        chartCard.getStyleClass().add("card");
        chartCard.setPadding(new Insets(18));

        VBox routeCard = card("Your trade route", new HBox(10,
                routeNode("0.5 ETH"), routeArrow(), routeNode("WETH"),
                routeArrow(), routeNode("USDC"), routeArrow(), routeNode("1.3421 BTC")));

        VBox right = new VBox(16, chartCard, routeCard);
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox row = new HBox(16, swapCard, right);
        return sectionScroller("Swap",
                "Buy or sell any token instantly at the best price.", row);
    }

    private static Label fieldLabel(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("card-sub");
        return l;
    }

    private static HBox swapRow(String amount, String token, String color) {
        TextField tf = new TextField(amount);
        tf.getStyleClass().add("swap-amount");
        HBox.setHgrow(tf, Priority.ALWAYS);
        HBox row = new HBox(10, tf, tokenChip(token, color));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("swap-row");
        return row;
    }

    private static HBox tokenChip(String token, String color) {
        Region dot = new Region();
        dot.getStyleClass().add("token-dot");
        dot.setStyle("-fx-background-color: " + color + ";");
        Label l = new Label(token);
        l.getStyleClass().add("profile-name");
        HBox chip = new HBox(dot, l, thinIcon("M6 9l6 6 6-6"));
        chip.getStyleClass().add("token-chip");
        chip.setAlignment(Pos.CENTER);
        return chip;
    }

    private static HBox flipRow() {
        StackPane flip = new StackPane(thinIcon("M7 1v16 M3 13l4 4 4-4 M17 23V7 M13 11l4-4 4 4"));
        flip.getStyleClass().add("swap-flip");
        HBox row = new HBox(flip);
        row.setAlignment(Pos.CENTER);
        return row;
    }

    private static HBox infoRow(String label, String value) {
        Label l = new Label(label);
        l.getStyleClass().add("card-sub");
        Label v = new Label(value);
        v.getStyleClass().add("profile-name");
        Region s = new Region();
        HBox.setHgrow(s, Priority.ALWAYS);
        HBox row = new HBox(l, s, v);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Button swapButton() {
        Button b = new Button("SWAP");
        b.getStyleClass().add("accent-btn");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> statusText.setText("Swap submitted (demo)"));
        return b;
    }

    private static HBox routeNode(String text) {
        HBox n = new HBox(chip(text));
        n.getStyleClass().add("route-node");
        n.setAlignment(Pos.CENTER);
        return n;
    }

    private static Node routeArrow() {
        SVGPath a = thinIcon("M5 12h14 M13 6l6 6-6 6");
        a.setStyle("-fx-stroke: -accent;");
        StackPane wrap = new StackPane(a);
        wrap.setMinWidth(28);
        return wrap;
    }
    private Node buildRegistration() {
        regStepper = new HBox(0);
        regStepper.getStyleClass().add("stepper");
        regStepper.setAlignment(Pos.CENTER_LEFT);

        regBody = new StackPane();

        Button cancel = new Button("CANCEL");
        cancel.getStyleClass().add("ghost-btn");
        cancel.setOnAction(e -> renderStep(0));
        Button cont = new Button("CONTINUE  →");
        cont.getStyleClass().add("accent-btn");
        cont.setOnAction(e -> {
            if (regStep < REG_STEPS.length - 1) renderStep(regStep + 1);
            else info("Registration", "Your registration has been submitted.");
        });
        Region fs = new Region();
        HBox.setHgrow(fs, Priority.ALWAYS);
        HBox footer = new HBox(12, fs, cancel, cont);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(8, 0, 0, 0));

        VBox cardBody = new VBox(18, regStepper, new Separator(), regBody, footer);
        VBox cardWrap = new VBox(cardBody);
        cardWrap.getStyleClass().add("card");
        cardWrap.setPadding(new Insets(22));

        renderStep(regStep);
        return sectionScroller("Agent Registration",
                "Complete every step — fields marked with an asterisk (*) are required.", cardWrap);
    }

    private void renderStep(int step) {
        regStep = step;

        // ---- stepper header ----
        regStepper.getChildren().clear();
        for (int i = 0; i < REG_STEPS.length; i++) {
            HBox item = new HBox(10);
            item.getStyleClass().add("step");
            item.setAlignment(Pos.CENTER_LEFT);
            if (i == regStep) item.getStyleClass().add("active");
            else if (i < regStep) item.getStyleClass().add("done");

            StackPane idx = new StackPane();
            idx.getStyleClass().add("step-index");
            if (i < regStep) {
                SVGPath check = new SVGPath();
                check.setContent("M2 6 L5 9 L10 2.5");
                check.getStyleClass().add("check-mark");
                idx.getChildren().add(check);
            } else {
                Label n = new Label(String.valueOf(i + 1));
                n.getStyleClass().add("index-text");
                idx.getChildren().add(n);
            }
            Label lbl = new Label(REG_STEPS[i]);
            lbl.getStyleClass().add("step-label");
            item.getChildren().addAll(idx, lbl);
            int target = i;
            item.setOnMouseClicked(e -> renderStep(target));
            regStepper.getChildren().add(item);

            if (i < REG_STEPS.length - 1) {
                Region rule = new Region();
                rule.getStyleClass().add("step-rule");
                if (i < regStep) rule.getStyleClass().add("active");
                HBox.setHgrow(rule, Priority.ALWAYS);
                HBox.setMargin(rule, new Insets(0, 10, 0, 10));
                regStepper.getChildren().add(rule);
            }
        }

        // ---- step body ----
        Node form = switch (regStep) {
            case 0  -> personalForm();
            case 1  -> agencyForm();
            case 2  -> contactForm();
            default -> scopeForm();
        };
        Label sub = new Label(REG_STEPS[regStep]);
        sub.getStyleClass().add("card-title");
        VBox wrapped = new VBox(14, sub, form);
        regBody.getChildren().setAll(wrapped);
        animateIn(wrapped);
    }

    private static GridPane grid3() {
        GridPane g = new GridPane();
        g.setHgap(18);
        g.setVgap(16);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(100.0 / 3);
            cc.setHgrow(Priority.ALWAYS);
            g.getColumnConstraints().add(cc);
        }
        return g;
    }

    private static void cell(GridPane g, Node node, int col, int row) {
        cell(g, node, col, row, 1);
    }

    private static void cell(GridPane g, Node node, int col, int row, int span) {
        g.add(node, col, row, span, 1);
        GridPane.setHgrow(node, Priority.ALWAYS);
    }

    /** label (+ optional required star) over a control. */
    private static VBox field(String label, boolean req, Region control) {
        control.setMaxWidth(Double.MAX_VALUE);
        Label l = new Label(label);
        l.getStyleClass().add("form-label");
        HBox head;
        if (req) {
            Label star = new Label(" *");
            star.getStyleClass().add("form-req");
            head = new HBox(l, star);
        } else {
            head = new HBox(l);
        }
        return new VBox(6, head, control);
    }

    private static ComboBox<String> combo(String... items) {
        ComboBox<String> c = new ComboBox<>(FXCollections.observableArrayList(items));
        c.getSelectionModel().selectFirst();
        return c;
    }

    private Node personalForm() {
        GridPane g = grid3();
        cell(g, field("First Name", true, new TextField()), 0, 0);
        cell(g, field("Last Name", true, new TextField()), 1, 0);
        cell(g, field("Date of Birth", true, new DatePicker(LocalDate.of(1990, 1, 1))), 2, 0);

        ToggleGroup gender = new ToggleGroup();
        RadioButton m = new RadioButton("Male");
        RadioButton f = new RadioButton("Female");
        RadioButton o = new RadioButton("Other");
        m.setToggleGroup(gender); f.setToggleGroup(gender); o.setToggleGroup(gender);
        m.setSelected(true);
        HBox genders = new HBox(20, m, f, o);
        genders.setAlignment(Pos.CENTER_LEFT);
        cell(g, field("Gender", false, genders), 0, 1);
        cell(g, field("Nationality", false, combo("United States", "Canada", "Mexico", "Other")), 1, 1);
        cell(g, field("Phone", false, new TextField()), 2, 1);
        return g;
    }

    private Node agencyForm() {
        GridPane g = grid3();
        cell(g, field("Agency Name", true, new TextField()), 0, 0);
        cell(g, field("Agency Type", true, combo("Agent", "Broker", "Owner")), 1, 0);
        PasswordField pw = new PasswordField();
        pw.setText("secret");
        cell(g, field("Password", true, pw), 2, 0);

        cell(g, field("Address", true, new TextField()), 0, 1);
        TextField city = new TextField();
        TextField state = new TextField();
        HBox.setHgrow(city, Priority.ALWAYS);
        HBox.setHgrow(state, Priority.ALWAYS);
        city.setMaxWidth(Double.MAX_VALUE);
        state.setMaxWidth(Double.MAX_VALUE);
        HBox cityState = new HBox(8, field("City", true, city), field("State", true, state));
        cell(g, cityState, 1, 1);
        cell(g, field("Telephone Number", true, new TextField()), 2, 1);

        cell(g, field("Toll-Free Number", true, new TextField()), 0, 2);
        cell(g, field("Email ID", true, new TextField()), 1, 2);
        cell(g, field("Website", true, new TextField()), 2, 2);

        cell(g, field("Company Name", true, new TextField()), 0, 3);
        cell(g, field("Office Space", false, ownedRental()), 1, 3);
        cell(g, field("Company Logo", true, fileCell()), 2, 3);
        return g;
    }

    private static HBox ownedRental() {
        ToggleGroup tg = new ToggleGroup();
        RadioButton owned = new RadioButton("Owned");
        RadioButton rental = new RadioButton("Rental");
        owned.setToggleGroup(tg); rental.setToggleGroup(tg);
        owned.setSelected(true);
        HBox h = new HBox(owned, rental);
        h.getStyleClass().add("seg-radio");
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private HBox fileCell() {
        Button choose = new Button("Choose File");
        choose.getStyleClass().add("file-btn");
        Label name = new Label("No file chosen.");
        name.getStyleClass().add("field-hint");
        choose.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select company logo");
            var f = (stage != null) ? fc.showOpenDialog(stage) : null;
            name.setText(f != null ? f.getName() : "No file chosen.");
        });
        HBox cellBox = new HBox(choose, name);
        cellBox.getStyleClass().add("file-cell");
        cellBox.setAlignment(Pos.CENTER_LEFT);
        return cellBox;
    }

    private Node contactForm() {
        GridPane g = grid3();
        cell(g, field("Contact Name", true, new TextField()), 0, 0);
        cell(g, field("Email", true, new TextField()), 1, 0);
        cell(g, field("Phone", true, new TextField()), 2, 0);
        cell(g, field("Role", false, combo("Owner", "Manager", "Assistant", "Accountant")), 0, 1);
        TextArea notes = new TextArea();
        notes.setPromptText("Anything we should know about the contact person…");
        notes.setPrefRowCount(3);
        cell(g, field("Notes", false, notes), 1, 1, 2);
        return g;
    }

    private Node scopeForm() {
        CheckBox a = new CheckBox("Property management");
        CheckBox b = new CheckBox("Leasing");
        CheckBox c = new CheckBox("Maintenance");
        CheckBox d = new CheckBox("Inspections");
        a.setSelected(true); c.setSelected(true);
        VBox services = new VBox(8, a, b, c, d);

        Slider budget = new Slider(0, 100000, 35000);
        budget.setShowTickLabels(true);
        budget.setShowTickMarks(true);
        budget.setMajorTickUnit(25000);
        Label budgetVal = new Label();
        budgetVal.getStyleClass().add("field-hint");
        budgetVal.textProperty().bind(Bindings.createStringBinding(
                () -> String.format("$%,d / yr", Math.round(budget.getValue())), budget.valueProperty()));

        TextArea scope = new TextArea();
        scope.setPromptText("Describe the work order scope…");
        scope.setPrefRowCount(4);

        GridPane g = grid3();
        cell(g, field("Services", false, services), 0, 0);
        cell(g, field("Priority", false, combo("Low", "Medium", "High", "Urgent")), 1, 0);
        cell(g, field("Annual budget", false, new VBox(6, budget, budgetVal)), 2, 0);
        cell(g, field("Scope details", false, scope), 0, 1, 3);
        return g;
    }

    private void info(String header, String body) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, body, ButtonType.OK);
        a.setHeaderText(header);
        if (stage != null) a.initOwner(stage);
        a.show();
    }
    private Node buildAnalytics() {
        AreaChart<Number, Number> area = emeraldArea(new double[]{
                20, 24, 22, 30, 28, 35, 33, 40, 44, 41, 48, 52});
        area.setPrefHeight(260);
        VBox areaCard = chartCard("Revenue", area);

        BarChart<String, Number> bar = new BarChart<>(new CategoryAxis(), new NumberAxis());
        bar.setAnimated(false);
        bar.setLegendVisible(true);
        bar.getData().add(catSeries("Q2", "Direct", "Search", "Social", "Email", "Referral"));
        bar.setPrefHeight(260);
        VBox barCard = chartCard("Sessions by channel", bar);

        LineChart<Number, Number> line = new LineChart<>(new NumberAxis(), new NumberAxis());
        line.setAnimated(false);
        line.setCreateSymbols(false);
        line.getData().add(numberSeries("This year", 12));
        line.getData().add(numberSeries("Last year", 12));
        line.setPrefHeight(260);
        VBox lineCard = chartCard("Trend", line);

        VBox pieCard = chartCard("Asset mix", donut());

        FlowPane grid = new FlowPane(18, 18, areaCard, barCard, lineCard, pieCard);
        return sectionScroller("Analytics",
                "Traffic and engagement across the current quarter.", grid);
    }

    private static VBox chartCard(String title, Node chart) {
        VBox c = card(title, chart);
        c.setPrefWidth(440);
        c.setMinWidth(360);
        return c;
    }

    private XYChart.Series<String, Number> catSeries(String name, String... cats) {
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        s.setName(name);
        double[] vals = {42, 58, 31, 24, 19};
        for (int i = 0; i < cats.length; i++) {
            s.getData().add(new XYChart.Data<>(cats[i], vals[i % vals.length]));
        }
        return s;
    }

    private XYChart.Series<Number, Number> numberSeries(String name, int n) {
        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        s.setName(name);
        double v = 40;
        double[] steps = {6, -3, 9, 2, -4, 7, 3, -2, 8, 1, 5, -1};
        for (int i = 0; i < n; i++) {
            v += steps[i % steps.length];
            s.getData().add(new XYChart.Data<>(i, v));
        }
        return s;
    }
    private Node buildControls() {
        FlowPane grid = new FlowPane(18, 18,
                card("Buttons", buttonsDemo()),
                card("Selection", selectionDemo()),
                card("Inputs", inputsDemo()),
                card("Progress", progressDemo()),
                card("List", listDemo()),
                card("Table", tableDemo()),
                card("Tree", treeDemo()),
                card("Containers", containersDemo()),
                card("Bars & pagination", barsDemo()),
                card("Menus & tooltips", menusDemo()));
        return sectionScroller("Controls",
                "A gallery exercising nearly every javafx.controls node — all emerald-themed.", grid);
    }

    private Node buttonsDemo() {
        Button primary = new Button("Primary");
        primary.getStyleClass().add("accent-btn");
        Button ghost = new Button("Ghost");
        ghost.getStyleClass().add("ghost-btn");
        Button plain = new Button("Default");

        ToggleGroup tg = new ToggleGroup();
        ToggleButton on = new ToggleButton("On");
        ToggleButton off = new ToggleButton("Off");
        on.setToggleGroup(tg); off.setToggleGroup(tg); on.setSelected(true);

        MenuButton menu = new MenuButton("Menu", null,
                new MenuItem("Action one"), new MenuItem("Action two"));
        SplitMenuButton split = new SplitMenuButton(
                new MenuItem("Save as…"), new MenuItem("Export"));
        split.setText("Save");
        Hyperlink link = new Hyperlink("Numa docs");
        Tooltip.install(primary, new Tooltip("A tooltip on the primary button"));

        FlowPane fp = new FlowPane(10, 10, primary, ghost, plain, on, off, menu, split, link);
        fp.setPrefWidth(320);
        return fp;
    }

    private Node selectionDemo() {
        ToggleGroup radios = new ToggleGroup();
        RadioButton r1 = new RadioButton("Emerald");
        RadioButton r2 = new RadioButton("Slate");
        r1.setToggleGroup(radios); r2.setToggleGroup(radios); r1.setSelected(true);

        CheckBox c1 = new CheckBox("Auto-save");
        CheckBox c2 = new CheckBox("Notifications");
        c2.setSelected(true);
        CheckBox c3 = new CheckBox("Beta features");
        c3.setIndeterminate(true);

        VBox v = new VBox(10, new HBox(16, r1, r2), c1, c2, c3);
        v.setPrefWidth(240);
        return v;
    }

    private Node inputsDemo() {
        TextField tf = new TextField();
        tf.setPromptText("Type here…");
        PasswordField pf = new PasswordField();
        pf.setPromptText("Password");
        ComboBox<String> cb = combo("United States", "Canada", "Mexico");
        cb.setEditable(true);
        ChoiceBox<String> choice = new ChoiceBox<>(FXCollections.observableArrayList("Low", "Medium", "High"));
        choice.getSelectionModel().select(1);
        ColorPicker color = new ColorPicker(Color.web("#18b673"));
        DatePicker date = new DatePicker(LocalDate.now());
        Spinner<Integer> spinner = new Spinner<>(0, 240, 144);
        spinner.setEditable(true);
        spinner.setPrefWidth(100);

        VBox v = new VBox(10,
                labeled("Text", tf), labeled("Password", pf), labeled("Combo", cb),
                labeled("Choice", choice), labeled("Colour", color),
                labeled("Date", date), labeled("Spinner", spinner));
        v.setPrefWidth(320);
        return v;
    }

    private Node progressDemo() {
        ProgressBar bar = new ProgressBar(0.45);
        bar.setPrefWidth(220);
        ProgressIndicator ind = new ProgressIndicator(0.45);
        Slider slider = new Slider(0, 1, 0.45);
        slider.valueProperty().addListener((o, ov, nv) -> {
            bar.setProgress(nv.doubleValue());
            ind.setProgress(nv.doubleValue());
        });
        ProgressIndicator spin = new ProgressIndicator();
        spin.setPrefSize(34, 34);
        VBox v = new VBox(12, slider, bar, new HBox(18, ind, spin));
        v.setPrefWidth(260);
        return v;
    }

    private Node listDemo() {
        ListView<String> list = new ListView<>();
        for (int i = 1; i <= 30; i++) list.getItems().add("Transaction #" + i);
        list.setPrefSize(240, 220);
        return list;
    }

    /** Simple bean for the table demo. */
    public static final class Proj {
        private final StringProperty name, owner, status;
        private final javafx.beans.property.DoubleProperty progress;
        public Proj(String n, String o, String s, double p) {
            name = new SimpleStringProperty(n);
            owner = new SimpleStringProperty(o);
            status = new SimpleStringProperty(s);
            progress = new javafx.beans.property.SimpleDoubleProperty(p);
        }
        public StringProperty nameProperty()   { return name; }
        public StringProperty ownerProperty()  { return owner; }
        public StringProperty statusProperty() { return status; }
        public double getProgress()            { return progress.get(); }
    }

    private Node tableDemo() {
        TableView<Proj> table = new TableView<>(FXCollections.observableArrayList(
                new Proj("Skia present", "Ravi", "On track", 0.82),
                new Proj("Glyph atlas", "Ava", "On track", 0.64),
                new Proj("Per-monitor DPI", "Lena", "At risk", 0.41),
                new Proj("WebView OSR", "Diego", "Done", 1.00),
                new Proj("Media dual-src", "Yuki", "Blocked", 0.28)));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<Proj, String> cName = new TableColumn<>("Project");
        cName.setCellValueFactory(c -> c.getValue().nameProperty());
        TableColumn<Proj, String> cOwner = new TableColumn<>("Owner");
        cOwner.setCellValueFactory(c -> c.getValue().ownerProperty());
        TableColumn<Proj, String> cStatus = new TableColumn<>("Status");
        cStatus.setCellValueFactory(c -> c.getValue().statusProperty());
        cStatus.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                setGraphic(empty || s == null ? null : badge(s));
            }
        });
        TableColumn<Proj, Double> cProg = new TableColumn<>("Progress");
        cProg.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getProgress()));
        cProg.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar pb = new ProgressBar();
            { pb.setPrefWidth(110); }
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setGraphic(empty || v == null ? null : pb);
                if (v != null) pb.setProgress(v);
            }
        });
        table.getColumns().add(cName);
        table.getColumns().add(cOwner);
        table.getColumns().add(cStatus);
        table.getColumns().add(cProg);
        table.setPrefSize(360, 220);
        return table;
    }

    private static Label badge(String status) {
        Label l = new Label(status);
        l.getStyleClass().addAll("badge", "badge-" + status.toLowerCase().replace(' ', '-'));
        return l;
    }

    private Node treeDemo() {
        TreeItem<String> root = new TreeItem<>("Workspace");
        root.setExpanded(true);
        for (int g = 1; g <= 3; g++) {
            TreeItem<String> group = new TreeItem<>("Folder " + g);
            for (int n = 1; n <= 3; n++) group.getChildren().add(new TreeItem<>("Item " + g + "." + n));
            group.setExpanded(true);
            root.getChildren().add(group);
        }
        TreeView<String> tree = new TreeView<>(root);
        tree.setPrefSize(260, 220);
        return tree;
    }

    private Node containersDemo() {
        TabPane tabs = new TabPane();
        tabs.getTabs().addAll(
                new Tab("Overview", new Label("  Overview content…  ")),
                new Tab("Details", new Label("  Details content…  ")),
                new Tab("Activity", new Label("  Activity content…  ")));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setPrefHeight(120);

        Accordion acc = new Accordion(
                new TitledPane("General", new Label("General settings")),
                new TitledPane("Security", new Label("Security settings")),
                new TitledPane("Billing", new Label("Billing settings")));

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

    private Node menusDemo() {
        Button tip = new Button("Hover for a tooltip");
        Tooltip.install(tip, new Tooltip("This popup is rendered through the upload tier."));

        Label ctxTarget = new Label("Right-click me for a context menu");
        ctxTarget.getStyleClass().add("card-title");
        Menu insert = new Menu("Insert");
        insert.getItems().addAll(new MenuItem("Rectangle"), new MenuItem("Ellipse"));
        CheckMenuItem snap = new CheckMenuItem("Snap to grid");
        snap.setSelected(true);
        ToggleGroup mg = new ToggleGroup();
        RadioMenuItem ganesh = new RadioMenuItem("Ganesh");
        ganesh.setToggleGroup(mg); ganesh.setSelected(true);
        RadioMenuItem graphite = new RadioMenuItem("Graphite");
        graphite.setToggleGroup(mg);
        ctxTarget.setContextMenu(new ContextMenu(
                new MenuItem("Cut"), new MenuItem("Copy"), new MenuItem("Paste"),
                new SeparatorMenuItem(), insert, new SeparatorMenuItem(), snap,
                new SeparatorMenuItem(), ganesh, graphite));

        VBox v = new VBox(12, tip, ctxTarget);
        v.setPrefWidth(300);
        return v;
    }

    private static HBox labeled(String caption, Node control) {
        Label l = new Label(caption);
        l.getStyleClass().add("form-label");
        l.setMinWidth(96);
        HBox row = new HBox(12, l, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
    private Node buildSettings() {
        GridPane g = grid3();
        cell(g, field("Display name", false, new TextField("Austin Robertson")), 0, 0);
        cell(g, field("Email", false, new TextField("austin@numa.app")), 1, 0);
        cell(g, field("Theme", false, combo("System", "Light", "Dark")), 2, 0);

        CheckBox notif = new CheckBox("Desktop notifications");
        notif.setSelected(true);
        CheckBox sounds = new CheckBox("Notification sounds");
        CheckBox beta = new CheckBox("Join the beta channel");
        VBox notifs = new VBox(8, notif, sounds, beta);
        cell(g, field("Notifications", false, notifs), 0, 1);
        cell(g, field("Density", false, combo("Comfortable", "Cozy", "Compact")), 1, 1);

        Slider width = new Slider(180, 320, 252);
        width.setShowTickMarks(true);
        width.setShowTickLabels(true);
        width.setMajorTickUnit(70);
        Label widthVal = new Label();
        widthVal.getStyleClass().add("field-hint");
        widthVal.textProperty().bind(Bindings.createStringBinding(
                () -> Math.round(width.getValue()) + " px", width.valueProperty()));
        cell(g, field("Sidebar width", false, new VBox(6, width, widthVal)), 2, 1);

        VBox form = new VBox(g);
        form.getStyleClass().add("card");
        form.setPadding(new Insets(22));

        Button save = new Button("Save changes");
        save.getStyleClass().add("accent-btn");
        save.setOnAction(e -> info("Saved", "Your preferences have been saved."));
        Button reset = new Button("Reset");
        reset.getStyleClass().add("ghost-btn");
        HBox actions = new HBox(12, save, reset);

        VBox body = new VBox(18, form, actions);
        return sectionScroller("Settings",
                "Workspace preferences. Changes are saved per account.", body);
    }

    private Node placeholder(String title) {
        Label l = new Label(title + " — coming together…");
        l.getStyleClass().add("card-title");
        VBox c = card(title, l);
        c.setMaxWidth(420);
        return sectionScroller(title, "This section is being built.", c);
    }
}
