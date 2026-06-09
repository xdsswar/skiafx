/*
 * Dashboard sample for the Skia pipeline.
 *
 * Stress-tests the rendering path with:
 *   - Sidebar navigation + multi-view content area (TableView, ListView,
 *     several Chart types, an animation sandbox).
 *   - Live FPS counter driven by an AnimationTimer.
 *   - Drag-resize across many controls / clipped subtrees, exercising
 *     ScrollPane viewport bucketing (RESIZE_INVARIANT #8) under real load.
 *   - Continuous animations to exercise the per-frame submit path.
 *
 * Run with: ./gradlew :samples:ensemble:run
 */
package org.openjfx.samples.ensemble;

import javafx.animation.*;
import javafx.application.Application;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * Dashboard test app with sidebar navigation + multiple stress views.
 *
 * <p>Five views switchable via the sidebar:</p>
 * <ul>
 *   <li><b>Overview</b> — KPI cards + a small line chart, with a
 *       continuously-pulsing accent indicator to verify per-frame
 *       submission is alive.</li>
 *   <li><b>Tables</b> — 200-row TableView with mixed cell types,
 *       inside a ScrollPane.</li>
 *   <li><b>Lists</b> — 500-item ListView.</li>
 *   <li><b>Charts</b> — Line, Area, Bar, Pie laid out in a 2x2 grid.</li>
 *   <li><b>Animation</b> — Rotating, scaling, fading shapes for a
 *       sustained motion stress test.</li>
 * </ul>
 *
 * <p>The sidebar's bottom shows a live FPS counter (computed from
 * {@link AnimationTimer#handle(long)} timestamps) and the current
 * wall clock.</p>
 */
public final class DashboardApp extends Application {

    /** Required no-arg constructor for reflective {@code Application.launch}. */
    public DashboardApp() {}

    // ---- Palette ----------------------------------------------------------
    // Sidebar is DARK (slate-950), content area is LIGHT (slate-50) so the
    // app reads as a typical modern dashboard.
    // Sidebar — dark.
    private static final String BG_SIDEBAR     = "#0b1220"; // slate-950
    private static final String SIDE_TEXT      = "#f1f5f9"; // slate-100
    private static final String SIDE_TEXT_DIM  = "#94a3b8"; // slate-400
    // Content — light.
    private static final String BG_CONTENT     = "#f1f5f9"; // slate-100
    private static final String BG_CARD        = "#ffffff"; // pure white
    private static final String BG_CARD_HOV    = "#f8fafc"; // slate-50
    private static final String BORDER         = "#e2e8f0"; // slate-200
    private static final String BORDER_STRONG  = "#cbd5e1"; // slate-300
    private static final String TEXT_FG        = "#0f172a"; // slate-900
    private static final String TEXT_DIM       = "#64748b"; // slate-500
    // Brand / status colors.
    private static final String ACCENT         = "#06b6d4"; // cyan-500
    private static final String ACCENT_DIM     = "#0891b2"; // cyan-600
    private static final String OK_FG          = "#16a34a"; // green-600
    private static final String WARN_FG        = "#d97706"; // amber-600
    private static final String DANGER_FG      = "#dc2626"; // red-600
    // Row-state colors for tables / lists. Soft tints so all the
    // existing label foregrounds stay legible against them — a fully
    // saturated selection-bar would eat the chip/badge/IP colors.
    private static final String ROW_HOVER_BG   = "#f1f5f9"; // slate-100
    private static final String ROW_SELECT_BG  = "#e0f2fe"; // sky-100
    private static final String ROW_SELECT_BAR = ACCENT;    // 3-px left accent rail on selected row

    private final StringProperty navTarget = new SimpleStringProperty("overview");

    @Override
    public void start(Stage stage) {
        VBox sidebar = buildSidebar();
        StackPane contentHost = new StackPane();
        contentHost.setStyle("-fx-background-color: " + BG_CONTENT + ";");

        // Pre-build all views; navTarget.set(...) just swaps which is
        // visible so we don't pay re-creation cost on every nav click.
        Region overview  = buildOverview();
        Region tables    = buildTablesView();
        Region lists     = buildListsView();
        Region charts    = buildChartsView();
        Region animation = buildAnimationView();
        contentHost.getChildren().addAll(overview, tables, lists, charts, animation);

        navTarget.addListener((o, prev, target) -> {
            overview.setVisible("overview".equals(target));
            tables.setVisible("tables".equals(target));
            lists.setVisible("lists".equals(target));
            charts.setVisible("charts".equals(target));
            animation.setVisible("animation".equals(target));
        });
        // Apply initial selection.
        navTarget.set("overview");

        BorderPane root = new BorderPane();
        root.setLeft(sidebar);
        root.setCenter(contentHost);

        Scene scene = new Scene(root, 1280, 800, Color.web(BG_CONTENT));
        // Attach the dashboard stylesheet — provides the table/list
        // hover/selected/pressed pseudo-class rules + custom scrollbars.
        java.net.URL css = DashboardApp.class.getResource("/dashboard.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(scene);
        stage.setTitle("Skia Dashboard — Stress Test");
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.show();
    }

    // ---- Sidebar ----------------------------------------------------------

    private VBox buildSidebar() {
        VBox sidebar = new VBox();
        sidebar.setPrefWidth(240);
        sidebar.setMinWidth(240);
        sidebar.setStyle(
            "-fx-background-color: " + BG_SIDEBAR + ";"
                + "-fx-border-color: " + BORDER + ";"
                + "-fx-border-width: 0 1 0 0;"
        );

        // Brand block at top.
        Label brand = new Label("● skia.dashboard");
        brand.setStyle(
            "-fx-text-fill: " + ACCENT + ";"
                + "-fx-font-size: 18px;"
                + "-fx-font-weight: bold;"
                + "-fx-padding: 28 0 28 24;"
        );
        sidebar.getChildren().add(brand);

        // Nav items.
        sidebar.getChildren().addAll(
            navItem("overview",  "🏠  Overview"),
            navItem("tables",    "📋  Tables"),
            navItem("lists",     "📃  Lists"),
            navItem("charts",    "📊  Charts"),
            navItem("animation", "✨  Animation")
        );

        // Spacer pushes the FPS panel to the bottom.
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        sidebar.getChildren().add(spacer);

        sidebar.getChildren().add(buildFpsPanel());
        return sidebar;
    }

    private Label navItem(String target, String text) {
        Label item = new Label(text);
        item.setMaxWidth(Double.MAX_VALUE);
        item.setPrefHeight(44);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setStyle(itemStyle(false));
        item.setOnMouseEntered(e -> {
            if (!target.equals(navTarget.get())) {
                item.setStyle(itemStyle(true));
            }
        });
        item.setOnMouseExited(e -> {
            if (!target.equals(navTarget.get())) {
                item.setStyle(itemStyle(false));
            }
        });
        item.setOnMouseClicked((MouseEvent e) -> navTarget.set(target));
        // Live style update when the active target changes.
        navTarget.addListener((o, prev, now) ->
            item.setStyle(target.equals(now) ? itemStyleActive() : itemStyle(false)));
        return item;
    }

    private String itemStyle(boolean hover) {
        return "-fx-padding: 0 24 0 24;"
            + "-fx-text-fill: " + (hover ? SIDE_TEXT : SIDE_TEXT_DIM) + ";"
            + "-fx-font-size: 13.5px;"
            + "-fx-background-color: " + (hover ? "#11192b" : BG_SIDEBAR) + ";"
            + "-fx-cursor: hand;";
    }

    private String itemStyleActive() {
        return "-fx-padding: 0 24 0 20;"
            + "-fx-text-fill: " + SIDE_TEXT + ";"
            + "-fx-font-size: 13.5px;"
            + "-fx-font-weight: bold;"
            + "-fx-background-color: #11192b;"
            + "-fx-border-color: " + ACCENT + ";"
            + "-fx-border-width: 0 0 0 4;"
            + "-fx-cursor: hand;";
    }

    // ---- FPS panel --------------------------------------------------------

    private VBox buildFpsPanel() {
        Label fpsValue = new Label("…");
        fpsValue.setStyle(
            "-fx-text-fill: " + ACCENT + ";"
                + "-fx-font-size: 34px;"
                + "-fx-font-weight: bold;"
        );
        Label fpsCaption = new Label("frames per second");
        fpsCaption.setStyle("-fx-text-fill: " + SIDE_TEXT_DIM + "; -fx-font-size: 11px;");

        Label clock = new Label();
        clock.setStyle("-fx-text-fill: " + SIDE_TEXT_DIM + "; -fx-font-size: 11px;");

        Label memValue = new Label();
        memValue.setStyle("-fx-text-fill: " + SIDE_TEXT_DIM + "; -fx-font-size: 11px;");

        VBox box = new VBox(2, fpsValue, fpsCaption,
            new Spacer(8), clock, memValue);
        box.setPadding(new Insets(20, 24, 24, 24));
        box.setStyle("-fx-border-color: " + BORDER + "; -fx-border-width: 1 0 0 0;");

        DateTimeFormatter clockFmt = DateTimeFormatter.ofPattern("HH:mm:ss");

        new AnimationTimer() {
            long windowStart = 0;
            int  frames      = 0;
            long lastClock   = 0;

            @Override public void handle(long now) {
                frames++;
                if (windowStart == 0) windowStart = now;
                long elapsed = now - windowStart;
                if (elapsed >= 500_000_000L) { // refresh twice a second
                    double fps = frames * 1_000_000_000.0 / elapsed;
                    fpsValue.setText(String.format("%.0f", fps));
                    String color = fps >= 100 ? OK_FG
                        : fps >= 50 ? ACCENT
                          : fps >= 30 ? WARN_FG : DANGER_FG;
                    fpsValue.setStyle(
                        "-fx-text-fill: " + color + ";"
                            + "-fx-font-size: 34px;"
                            + "-fx-font-weight: bold;"
                    );
                    windowStart = now;
                    frames = 0;
                }
                if (now - lastClock > 250_000_000L) {
                    clock.setText("⏰  " + LocalTime.now().format(clockFmt));
                    Runtime r = Runtime.getRuntime();
                    long usedMb = (r.totalMemory() - r.freeMemory()) >> 20;
                    long maxMb  = r.maxMemory() >> 20;
                    memValue.setText("🧠  " + usedMb + " / " + maxMb + " MB");
                    lastClock = now;
                }
            }
        }.start();
        return box;
    }

    // ---- Overview view ----------------------------------------------------

    private Region buildOverview() {
        VBox v = new VBox(20);
        v.setPadding(new Insets(28));

        Label heading = sectionHeading("Overview", "Live system metrics + a small trend chart.");
        v.getChildren().add(heading);

        // KPI row.
        HBox kpis = new HBox(16);
        kpis.getChildren().addAll(
            kpiCard("Latency",  "12.4 ms", "−1.8% wow", OK_FG),
            kpiCard("Requests", "84,231",  "+12% wow",  ACCENT),
            kpiCard("Errors",   "0.04%",   "stable",    WARN_FG),
            kpiCard("Users",    "2,194",   "+3.1% wow", OK_FG)
        );
        HBox.setHgrow(kpis, Priority.ALWAYS);
        v.getChildren().add(kpis);

        // Small chart + pulse indicator side-by-side.
        HBox row = new HBox(16);
        row.getChildren().addAll(buildOverviewChart(), buildPulseCard());
        HBox.setHgrow(row, Priority.ALWAYS);
        v.getChildren().add(row);

        // Notifications feed — explicit ScrollPane wrapping a VBox of
        // many entries, both for visual interest and to stress the
        // clipping path under the LIGHT-theme styling.
        v.getChildren().add(buildNotificationsCard());

        // Wrap the whole overview in a ScrollPane so very small windows
        // can still see all content.
        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background: " + BG_CONTENT + "; -fx-background-color: " + BG_CONTENT + ";");
        return sp;
    }

    private Region buildNotificationsCard() {
        VBox header = new VBox(buildCardHeader("Recent activity",
            "Live feed — scrollable. Tests ScrollPane clipping on the LIGHT theme."));
        VBox entries = new VBox(0);
        Random rnd = new Random(1234);
        String[] icons = { "✅", "⚠️", "ℹ️", "🔧", "🚀", "🔒", "📦" };
        String[] verbs = { "deployed", "scaled to", "rolled back", "patched",
            "promoted", "drained", "restored", "rotated" };
        String[] hosts = { "api-001", "api-042", "web-117", "db-200",
            "cache-303", "queue-512", "cdn-700", "lb-808" };
        for (int i = 0; i < 60; i++) {
            HBox row = new HBox(12);
            row.setPadding(new Insets(10, 16, 10, 16));
            row.setStyle("-fx-border-color: " + BORDER + "; -fx-border-width: 0 0 1 0;");
            Label ic = new Label(icons[rnd.nextInt(icons.length)]);
            ic.setStyle("-fx-font-size: 14px;");
            Label msg = new Label(hosts[rnd.nextInt(hosts.length)] + " "
                + verbs[rnd.nextInt(verbs.length)] + " — duration "
                + (50 + rnd.nextInt(900)) + " ms");
            msg.setStyle("-fx-text-fill: " + TEXT_FG + "; -fx-font-size: 12.5px;");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Label t = new Label((rnd.nextInt(58) + 1) + "m ago");
            t.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 11px;");
            row.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().addAll(ic, msg, spacer, t);
            entries.getChildren().add(row);
        }
        ScrollPane inner = new ScrollPane(entries);
        inner.setFitToWidth(true);
        inner.setPrefViewportHeight(320);
        inner.setStyle("-fx-background: " + BG_CARD + "; -fx-background-color: " + BG_CARD + ";");
        VBox card = new VBox(header, inner);
        card.setPadding(new Insets(16, 16, 0, 16));
        card.setStyle(cardStyle());
        return card;
    }

    private Region kpiCard(String label, String value, String delta, String deltaColor) {
        Label l = new Label(label);
        l.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 12px;");
        Label v = new Label(value);
        v.setStyle("-fx-text-fill: " + TEXT_FG + "; -fx-font-size: 28px; -fx-font-weight: bold;");
        Label d = new Label(delta);
        d.setStyle("-fx-text-fill: " + deltaColor + "; -fx-font-size: 11px;");
        VBox card = new VBox(4, l, v, d);
        card.setPadding(new Insets(20));
        card.setStyle(cardStyle());
        HBox.setHgrow(card, Priority.ALWAYS);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private Region buildOverviewChart() {
        NumberAxis x = new NumberAxis(1, 30, 5);
        NumberAxis y = new NumberAxis();
        x.setTickLabelFill(Color.web(TEXT_DIM));
        y.setTickLabelFill(Color.web(TEXT_DIM));

        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setTitle(null);
        chart.setStyle(
            "-fx-background-color: " + BG_CARD + ";"
                + "CHART_COLOR_1: " + ACCENT + ";"
                + "-fx-text-fill: " + TEXT_FG + ";"
        );

        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        Random rnd = new Random(42);
        double v = 12;
        for (int i = 1; i <= 30; i++) {
            v += (rnd.nextDouble() - 0.45) * 3;
            if (v < 5) v = 5;
            if (v > 25) v = 25;
            s.getData().add(new XYChart.Data<>(i, v));
        }
        chart.getData().add(s);

        VBox wrap = new VBox(buildCardHeader("Latency over time", "30-pulse rolling window"), chart);
        wrap.setPadding(new Insets(16));
        wrap.setStyle(cardStyle());
        HBox.setHgrow(wrap, Priority.ALWAYS);
        return wrap;
    }

    private Region buildPulseCard() {
        // Three pulsing rings — drive an opacity + scale animation so the
        // user can verify the pipeline is actually rendering at the FPS
        // the sidebar shows.
        Circle c1 = pulseCircle(60, ACCENT);
        Circle c2 = pulseCircle(40, ACCENT_DIM);
        Circle c3 = pulseCircle(20, OK_FG);
        StackPane rings = new StackPane(c1, c2, c3);
        rings.setPrefSize(180, 180);

        Timeline tl = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(c1.scaleXProperty(), 0.8),
                new KeyValue(c1.scaleYProperty(), 0.8),
                new KeyValue(c1.opacityProperty(), 0.9),
                new KeyValue(c2.scaleXProperty(), 1.0),
                new KeyValue(c2.scaleYProperty(), 1.0),
                new KeyValue(c3.scaleXProperty(), 1.0),
                new KeyValue(c3.scaleYProperty(), 1.0)),
            new KeyFrame(Duration.seconds(1.2),
                new KeyValue(c1.scaleXProperty(), 1.4, Interpolator.EASE_BOTH),
                new KeyValue(c1.scaleYProperty(), 1.4, Interpolator.EASE_BOTH),
                new KeyValue(c1.opacityProperty(), 0.0),
                new KeyValue(c2.scaleXProperty(), 1.3, Interpolator.EASE_BOTH),
                new KeyValue(c2.scaleYProperty(), 1.3, Interpolator.EASE_BOTH),
                new KeyValue(c3.scaleXProperty(), 1.2, Interpolator.EASE_BOTH),
                new KeyValue(c3.scaleYProperty(), 1.2, Interpolator.EASE_BOTH))
        );
        tl.setCycleCount(Timeline.INDEFINITE);
        tl.setAutoReverse(true);
        tl.play();

        Label heading = new Label("Pulse");
        heading.setStyle("-fx-text-fill: " + TEXT_FG + "; -fx-font-size: 14px; -fx-font-weight: bold;");
        Label sub = new Label("Indicates the render thread is alive.");
        sub.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 11px;");

        VBox box = new VBox(8, heading, sub, new Spacer(8), rings);
        box.setAlignment(Pos.TOP_LEFT);
        box.setPadding(new Insets(16));
        box.setStyle(cardStyle());
        box.setPrefWidth(280);
        return box;
    }

    private Circle pulseCircle(double r, String color) {
        Circle c = new Circle(r);
        c.setFill(Color.web(color, 0.18));
        c.setStroke(Color.web(color, 0.8));
        c.setStrokeWidth(2);
        return c;
    }

    // ---- Tables view ------------------------------------------------------

    private Region buildTablesView() {
        Label heading = sectionHeading("Tables",
            "TableView with 500 rows + custom cell factories (avatar circles, "
                + "status dots, gradient value bars). Heavy per-row paint stress.");

        TableView<Row> table = new TableView<>();
        // Soft selection bg keeps our colored chips / dots / badges
        // readable on selected rows. selection-bar-text is forced to
        // TEXT_FG so the few labels we let the CSS engine color stay
        // dark on light.
        table.setStyle(
            "-fx-base: " + BG_CARD + ";"
                + "-fx-background-color: " + BG_CARD + ";"
                + "-fx-control-inner-background: " + BG_CARD + ";"
                + "-fx-control-inner-background-alt: " + BG_CARD + ";"
                + "-fx-table-cell-border-color: " + BORDER + ";"
                + "-fx-text-background-color: " + TEXT_FG + ";"
                + "-fx-selection-bar: " + ROW_SELECT_BG + ";"
                + "-fx-selection-bar-text: " + TEXT_FG + ";"
                + "-fx-selection-bar-non-focused: " + ROW_HOVER_BG + ";"
                + "-fx-cell-hover-color: " + ROW_HOVER_BG + ";"
        );
        table.setFixedCellSize(44);
        // Row hover / selected / pressed styling lives in dashboard.css
        // — single source of truth, no per-row listener churn.

        // ---- #  ---------------------------------------------------------
        TableColumn<Row, Integer> id = new TableColumn<>("#");
        id.setCellValueFactory(new PropertyValueFactory<>("id"));
        id.setPrefWidth(64);
        id.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Integer v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); setGraphic(null); return; }
                Label l = new Label(String.format("%03d", v));
                l.setStyle("-fx-text-fill: " + TEXT_DIM + ";"
                    + "-fx-font-family: 'Consolas','Menlo',monospace;"
                    + "-fx-font-size: 12;");
                setGraphic(l); setText(null);
            }
        });

        // ---- Name (avatar + name + ip subtitle) -------------------------
        TableColumn<Row, String> name = new TableColumn<>("Service");
        name.setCellValueFactory(new PropertyValueFactory<>("name"));
        name.setPrefWidth(260);
        name.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); setGraphic(null); return; }
                Row row = getTableRow() != null ? getTableRow().getItem() : null;
                Color avatarBg = colorFromHash(v, 0.55, 0.45);
                Circle avatar = new Circle(14, avatarBg);
                Label initials = new Label(v.substring(4, Math.min(6, v.length())).toUpperCase());
                initials.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 10;");
                StackPane av = new StackPane(avatar, initials);
                Label title = new Label(v);
                title.setStyle("-fx-text-fill: " + TEXT_FG + "; -fx-font-weight: 600;");
                Label sub = new Label(row != null ? row.getIp() : "");
                sub.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 11; "
                    + "-fx-font-family: 'Consolas','Menlo',monospace;");
                VBox text = new VBox(2, title, sub);
                HBox box = new HBox(10, av, text);
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box); setText(null);
            }
        });

        // ---- Region (colored chip) --------------------------------------
        TableColumn<Row, String> region = new TableColumn<>("Region");
        region.setCellValueFactory(new PropertyValueFactory<>("region"));
        region.setPrefWidth(150);
        region.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); setGraphic(null); return; }
                Color chipBg = colorFromHash(v, 0.92, 0.75);
                Color chipFg = colorFromHash(v, 0.35, 0.40);
                Label chip = new Label(v);
                chip.setStyle(
                    "-fx-background-color: " + toHex(chipBg) + ";"
                        + "-fx-text-fill: " + toHex(chipFg) + ";"
                        + "-fx-background-radius: 10;"
                        + "-fx-padding: 3 10 3 10;"
                        + "-fx-font-size: 11;"
                        + "-fx-font-weight: 600;"
                );
                setGraphic(chip); setText(null);
            }
        });

        // ---- Status (animated dot + text) -------------------------------
        TableColumn<Row, String> status = new TableColumn<>("Status");
        status.setCellValueFactory(new PropertyValueFactory<>("status"));
        status.setPrefWidth(140);
        status.setCellFactory(c -> new TableCell<>() {
            private Timeline pulse;
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                if (pulse != null) { pulse.stop(); pulse = null; }
                if (empty || v == null) { setText(null); setGraphic(null); return; }
                Color dotColor;
                String label;
                if (v.contains("healthy"))      { dotColor = Color.web(OK_FG);    label = "Healthy"; }
                else if (v.contains("degraded")){ dotColor = Color.web(WARN_FG);  label = "Degraded"; }
                else if (v.contains("down"))    { dotColor = Color.web(DANGER_FG);label = "Down"; }
                else                            { dotColor = Color.web(TEXT_DIM); label = "Stale"; }

                Circle dot = new Circle(5, dotColor);
                // Pulse "degraded" and "down" so the user can spot
                // them from across the room — also stresses per-frame
                // submission on many simultaneously-animating nodes.
                if (label.equals("Degraded") || label.equals("Down")) {
                    pulse = new Timeline(
                        new KeyFrame(Duration.ZERO,    new KeyValue(dot.opacityProperty(), 1.0)),
                        new KeyFrame(Duration.millis(700), new KeyValue(dot.opacityProperty(), 0.25))
                    );
                    pulse.setAutoReverse(true);
                    pulse.setCycleCount(Timeline.INDEFINITE);
                    pulse.play();
                }
                Label t = new Label(label);
                t.setStyle("-fx-text-fill: " + toHex(dotColor) + "; -fx-font-weight: 600;");
                HBox box = new HBox(8, dot, t);
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box); setText(null);
            }
        });

        // ---- Value (gradient bar + percent text) ------------------------
        TableColumn<Row, Double> value = new TableColumn<>("Load");
        value.setCellValueFactory(new PropertyValueFactory<>("value"));
        value.setPrefWidth(180);
        value.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); setGraphic(null); return; }
                double pct = Math.max(0, Math.min(100, v));
                String trackColor = BORDER;
                String fillCol = pct < 40 ? OK_FG : pct < 75 ? WARN_FG : DANGER_FG;
                Rectangle track = new Rectangle(120, 6, Color.web(trackColor));
                track.setArcWidth(6); track.setArcHeight(6);
                Rectangle fill = new Rectangle(120 * (pct / 100.0), 6, Color.web(fillCol));
                fill.setArcWidth(6); fill.setArcHeight(6);
                StackPane bar = new StackPane(track, fill);
                StackPane.setAlignment(fill, Pos.CENTER_LEFT);
                Label pctLabel = new Label(String.format("%.1f%%", pct));
                pctLabel.setStyle("-fx-text-fill: " + TEXT_FG + "; -fx-font-size: 11;"
                    + "-fx-font-family: 'Consolas','Menlo',monospace;");
                pctLabel.setMinWidth(46);
                HBox box = new HBox(10, bar, pctLabel);
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box); setText(null);
            }
        });

        // ---- Updated (clock + relative time) ----------------------------
        TableColumn<Row, String> updated = new TableColumn<>("Updated");
        updated.setCellValueFactory(new PropertyValueFactory<>("updated"));
        updated.setPrefWidth(140);
        updated.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); setGraphic(null); return; }
                Circle clock = new Circle(4, Color.web(TEXT_DIM));
                Label t = new Label(v);
                t.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 12;"
                    + "-fx-font-family: 'Consolas','Menlo',monospace;");
                HBox box = new HBox(8, clock, t);
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box); setText(null);
            }
        });

        // Both warnings come from the same line: `new TableColumn[]` is
        // a raw-type array creation (rawtypes) which then narrows to a
        // generic-typed reference (unchecked). There's no way to write
        // a properly-typed array literal in Java for generic types, so
        // suppress both. This is local sample code and the type
        // contract is obvious from the next line.
        @SuppressWarnings({"unchecked", "rawtypes"})
        TableColumn<Row, ?>[] cols = new TableColumn[] {
            id, name, region, status, value, updated };
        for (TableColumn<Row, ?> col : cols) {
            table.getColumns().add(col);
        }
        table.setItems(generateRows(500));

        VBox card = new VBox(table);
        card.setPadding(new Insets(0));
        card.setStyle(cardStyle());
        VBox.setVgrow(table, Priority.ALWAYS);

        return wrapContent(heading, card);
    }

    private ObservableList<Row> generateRows(int n) {
        String[] regions = { "us-east-1", "us-west-2", "eu-central-1",
            "ap-southeast-1", "sa-east-1" };
        String[] statuses = { "🟢 healthy", "🟡 degraded", "🔴 down", "⚪ stale" };
        String[] firstNames = { "alpha", "beta", "gamma", "delta", "epsilon",
            "zeta", "eta", "theta", "iota", "kappa" };
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
        Random rnd = new Random(31337);
        ObservableList<Row> rows = FXCollections.observableArrayList();
        for (int i = 1; i <= n; i++) {
            rows.add(new Row(
                i,
                "svc-" + firstNames[rnd.nextInt(firstNames.length)]
                    + "-" + String.format("%03d", rnd.nextInt(1000)),
                "10." + rnd.nextInt(256) + "." + rnd.nextInt(256)
                    + "." + (1 + rnd.nextInt(254)),
                regions[rnd.nextInt(regions.length)],
                statuses[rnd.nextInt(statuses.length)],
                Math.round(rnd.nextDouble() * 10000d) / 100d,
                LocalTime.now().minusSeconds(rnd.nextInt(3600)).format(fmt)
            ));
        }
        return rows;
    }

    /** Row record exposed as JavaFX bean for PropertyValueFactory. */
    public static final class Row {
        private final int id;
        private final String name, ip, region, status, updated;
        private final double value;

        public Row(int id, String name, String ip, String region, String status,
                   double value, String updated) {
            this.id = id; this.name = name; this.ip = ip; this.region = region;
            this.status = status; this.value = value; this.updated = updated;
        }
        public int    getId()      { return id; }
        public String getName()    { return name; }
        public String getIp()      { return ip; }
        public String getRegion()  { return region; }
        public String getStatus()  { return status; }
        public double getValue()   { return value; }
        public String getUpdated() { return updated; }
    }

    // ---- Color helpers used by custom cell factories ---------------------

    /** Deterministic HSB color from a string's hash — same input always
     *  yields the same chip / avatar color, so a row's appearance is
     *  stable across re-virtualization. */
    private static Color colorFromHash(String s, double saturation, double brightness) {
        int h = s.hashCode();
        double hue = ((h & 0xFFFF) / 65535.0) * 360.0;
        return Color.hsb(hue, saturation, brightness);
    }

    /** Color → "#RRGGBB" for use in inline CSS strings. */
    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x",
            (int)(c.getRed()   * 255),
            (int)(c.getGreen() * 255),
            (int)(c.getBlue()  * 255));
    }

    /**
     * Applies the row's background + optional left-accent rail given
     * its current selected/hover/empty state. The 3-px left rail
     * makes the selected row obvious even when the bg tint is soft.
     */
    private static void applyRowStyle(javafx.scene.control.IndexedCell<?> cell,
                                      boolean empty,
                                      boolean selected,
                                      boolean hover) {
        if (empty) { cell.setStyle(""); return; }
        if (selected) {
            cell.setStyle(
                "-fx-background-color: " + ROW_SELECT_BAR + ", " + ROW_SELECT_BG + ";"
                    + "-fx-background-insets: 0, 0 0 0 3;"
            );
        } else if (hover) {
            cell.setStyle("-fx-background-color: " + ROW_HOVER_BG + ";");
        } else {
            cell.setStyle("");
        }
    }

    /** ListCell-friendly shim around {@link #applyRowStyle}. */
    private static void applyListCellStyle(ListCell<?> c) {
        applyRowStyle(c, c.isEmpty(), c.isSelected(), c.isHover());
    }

    // ---- Lists view -------------------------------------------------------

    private Region buildListsView() {
        Label heading = sectionHeading("Lists",
            "ListView with 1000 events + custom cells (avatar gradient, "
                + "multi-line text, severity bar, duration badge). Per-row "
                + "scene graph is heavy by design.");

        ListView<Event> list = new ListView<>();
        list.setFixedCellSize(64);
        list.setStyle(
            "-fx-base: " + BG_CARD + ";"
                + "-fx-background-color: " + BG_CARD + ";"
                + "-fx-control-inner-background: " + BG_CARD + ";"
                + "-fx-control-inner-background-alt: " + BG_CARD + ";"
                + "-fx-text-background-color: " + TEXT_FG + ";"
                + "-fx-selection-bar: " + ROW_SELECT_BG + ";"
                + "-fx-selection-bar-text: " + TEXT_FG + ";"
                + "-fx-selection-bar-non-focused: " + ROW_HOVER_BG + ";"
                + "-fx-cell-hover-color: " + ROW_HOVER_BG + ";"
        );

        list.setCellFactory(lv -> new ListCell<Event>() {
            // Pressed / hover / selected backgrounds come from
            // dashboard.css (loaded once in start()). We don't need
            // per-cell listeners for that — the CSS pseudo-classes
            // cover every transition correctly, including the gap
            // between mouse-down and mouse-up across two rows.
            @Override protected void updateItem(Event ev, boolean empty) {
                super.updateItem(ev, empty);
                if (empty || ev == null) {
                    setGraphic(null); setText(null);
                    applyListCellStyle(this);
                    return;
                }

                // Left: severity rail (4-px-wide colored stripe).
                String sevCol = switch (ev.severity) {
                    case INFO -> ACCENT;
                    case WARN -> WARN_FG;
                    case ERROR -> DANGER_FG;
                };
                Rectangle rail = new Rectangle(4, 44, Color.web(sevCol));
                rail.setArcWidth(4); rail.setArcHeight(4);

                // Avatar: gradient circle keyed off the host name.
                Color a = colorFromHash(ev.host, 0.65, 0.55);
                Color b = colorFromHash(ev.host, 0.65, 0.85);
                Circle avatar = new Circle(20);
                avatar.setStyle(
                    "-fx-fill: linear-gradient(to bottom right, "
                        + toHex(a) + ", " + toHex(b) + ");");
                Label initials = new Label(ev.host.substring(5, 8));
                initials.setStyle("-fx-text-fill: white; -fx-font-weight: bold;"
                    + "-fx-font-size: 11;");
                StackPane av = new StackPane(avatar, initials);

                // Middle: title + subtitle.
                Label title = new Label(ev.title);
                title.setStyle("-fx-text-fill: " + TEXT_FG + ";"
                    + "-fx-font-weight: 600; -fx-font-size: 13;");
                Label sub = new Label(ev.host + "  ·  " + ev.region
                    + "  ·  " + ev.timestamp);
                sub.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 11;"
                    + "-fx-font-family: 'Consolas','Menlo',monospace;");
                VBox text = new VBox(3, title, sub);
                text.setAlignment(Pos.CENTER_LEFT);

                // Right: duration badge.
                Color badgeBg = ev.durationMs < 200 ? Color.web(OK_FG)
                    : ev.durationMs < 600 ? Color.web(WARN_FG)
                      : Color.web(DANGER_FG);
                Label badge = new Label(ev.durationMs + " ms");
                badge.setStyle(
                    "-fx-background-color: " + toHex(badgeBg.deriveColor(0,1,1,0.18)) + ";"
                        + "-fx-text-fill: " + toHex(badgeBg) + ";"
                        + "-fx-background-radius: 10;"
                        + "-fx-padding: 4 12 4 12;"
                        + "-fx-font-size: 11;"
                        + "-fx-font-weight: 700;"
                        + "-fx-font-family: 'Consolas','Menlo',monospace;");

                HBox row = new HBox(14, rail, av, text, badge);
                row.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(text, Priority.ALWAYS);
                row.setPadding(new Insets(8, 14, 8, 0));

                setGraphic(row); setText(null);
            }
        });

        list.setItems(generateEvents(1000));

        VBox card = new VBox(list);
        card.setStyle(cardStyle());
        VBox.setVgrow(list, Priority.ALWAYS);

        return wrapContent(heading, card);
    }

    private enum Severity { INFO, WARN, ERROR }

    /** Event record consumed by the ListView's custom cell factory. */
    private static final class Event {
        final String   host, region, title, timestamp;
        final int      durationMs;
        final Severity severity;
        Event(String host, String region, String title, String timestamp,
              int durationMs, Severity severity) {
            this.host = host; this.region = region; this.title = title;
            this.timestamp = timestamp; this.durationMs = durationMs;
            this.severity = severity;
        }
    }

    private ObservableList<Event> generateEvents(int n) {
        String[] hosts = { "node-001", "node-042", "node-117", "node-200",
            "node-303", "node-512", "node-700", "node-808",
            "node-915", "node-A0F" };
        String[] regions = { "us-east-1", "us-west-2", "eu-central-1",
            "ap-southeast-1", "sa-east-1" };
        String[] titles = {
            "Pod deployed to production cluster",
            "Scaled service replica count to 8",
            "Restarted hung worker process",
            "Patched libssl3 vulnerability CVE-2026-0117",
            "Drained node for maintenance window",
            "Promoted canary to stable channel",
            "Evicted unresponsive container",
            "Database failover completed",
            "Cache invalidated after schema migration",
            "Triggered manual rollback to v2.4.1",
        };
        Severity[] sevs = Severity.values();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
        Random rnd = new Random(7);
        ObservableList<Event> out = FXCollections.observableArrayList();
        for (int i = 1; i <= n; i++) {
            out.add(new Event(
                hosts[rnd.nextInt(hosts.length)],
                regions[rnd.nextInt(regions.length)],
                titles[rnd.nextInt(titles.length)],
                LocalTime.now().minusSeconds(rnd.nextInt(86400)).format(fmt),
                50 + rnd.nextInt(950),
                sevs[rnd.nextInt(sevs.length)]
            ));
        }
        return out;
    }

    // ---- Charts view ------------------------------------------------------

    private Region buildChartsView() {
        Label heading = sectionHeading("Charts", "Line / Area / Bar / Pie — all rendering through Skia.");

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(16);
        grid.add(card(buildLineChart()),  0, 0);
        grid.add(card(buildAreaChart()),  1, 0);
        grid.add(card(buildBarChart()),   0, 1);
        grid.add(card(buildPieChart()),   1, 1);

        // Equal-size columns + rows.
        javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
        cc.setPercentWidth(50);
        grid.getColumnConstraints().addAll(cc, cc);
        javafx.scene.layout.RowConstraints rc = new javafx.scene.layout.RowConstraints();
        rc.setPercentHeight(50);
        rc.setVgrow(Priority.ALWAYS);
        grid.getRowConstraints().addAll(rc, rc);

        return wrapContent(heading, grid);
    }

    private LineChart<String, Number> buildLineChart() {
        CategoryAxis x = new CategoryAxis();
        NumberAxis   y = new NumberAxis();
        x.setTickLabelFill(Color.web(TEXT_DIM));
        y.setTickLabelFill(Color.web(TEXT_DIM));
        LineChart<String, Number> chart = new LineChart<>(x, y);
        chart.setLegendVisible(true);
        chart.setAnimated(false);
        chart.setTitle("Throughput");
        chart.setStyle("CHART_COLOR_1: " + ACCENT + "; CHART_COLOR_2: " + WARN_FG + ";");
        chart.getData().add(seriesXY("CPU",  10, 12, 14, 11, 19, 22, 18, 24));
        chart.getData().add(seriesXY("Net",   8,  9,  9, 12, 11, 14, 16, 17));
        return chart;
    }

    private AreaChart<String, Number> buildAreaChart() {
        CategoryAxis x = new CategoryAxis();
        NumberAxis   y = new NumberAxis();
        x.setTickLabelFill(Color.web(TEXT_DIM));
        y.setTickLabelFill(Color.web(TEXT_DIM));
        AreaChart<String, Number> chart = new AreaChart<>(x, y);
        chart.setAnimated(false);
        chart.setTitle("Memory usage");
        chart.setStyle("CHART_COLOR_1: " + ACCENT + ";");
        chart.getData().add(seriesXY("Heap", 200, 240, 280, 260, 300, 320, 290, 340, 330));
        return chart;
    }

    private BarChart<String, Number> buildBarChart() {
        CategoryAxis x = new CategoryAxis();
        NumberAxis   y = new NumberAxis();
        x.setTickLabelFill(Color.web(TEXT_DIM));
        y.setTickLabelFill(Color.web(TEXT_DIM));
        BarChart<String, Number> chart = new BarChart<>(x, y);
        chart.setAnimated(false);
        chart.setTitle("By region");
        chart.setStyle("CHART_COLOR_1: " + ACCENT + "; CHART_COLOR_2: " + OK_FG
            + "; CHART_COLOR_3: " + WARN_FG + ";");
        XYChart.Series<String, Number> s1 = new XYChart.Series<>();
        s1.setName("Reqs/s");
        s1.getData().add(new XYChart.Data<>("us-east", 240));
        s1.getData().add(new XYChart.Data<>("us-west", 180));
        s1.getData().add(new XYChart.Data<>("eu",      210));
        s1.getData().add(new XYChart.Data<>("apac",    160));
        s1.getData().add(new XYChart.Data<>("sa",       90));
        XYChart.Series<String, Number> s2 = new XYChart.Series<>();
        s2.setName("Errors/s");
        s2.getData().add(new XYChart.Data<>("us-east",  2));
        s2.getData().add(new XYChart.Data<>("us-west",  4));
        s2.getData().add(new XYChart.Data<>("eu",       3));
        s2.getData().add(new XYChart.Data<>("apac",     1));
        s2.getData().add(new XYChart.Data<>("sa",       2));
        // Add via add()+add() instead of varargs addAll() — the varargs
        // version triggers "unchecked generic array creation for varargs
        // parameter" because Series<String,Number>[] can't be safely
        // instantiated at runtime. Two add() calls have identical
        // semantics with no warning.
        chart.getData().add(s1);
        chart.getData().add(s2);
        return chart;
    }

    private PieChart buildPieChart() {
        PieChart pie = new PieChart(FXCollections.observableArrayList(
            new PieChart.Data("us-east", 45),
            new PieChart.Data("us-west", 25),
            new PieChart.Data("eu",      18),
            new PieChart.Data("apac",     8),
            new PieChart.Data("sa",       4)
        ));
        pie.setAnimated(false);
        pie.setTitle("Traffic share");
        pie.setLabelsVisible(true);
        return pie;
    }

    private XYChart.Series<String, Number> seriesXY(String name, double... vs) {
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        s.setName(name);
        for (int i = 0; i < vs.length; i++) {
            s.getData().add(new XYChart.Data<>("t" + i, vs[i]));
        }
        return s;
    }

    // ---- Animation view ---------------------------------------------------

    private Region buildAnimationView() {
        Label heading = sectionHeading("Animation",
            "Heavy motion stress test — 250 bouncing shapes + 400 particles "
                + "+ 3 concentric orbiting rings + a wave field. ~700+ animated nodes.");

        StackPane field = new StackPane();
        field.setPrefSize(0, 0);
        field.setStyle(cardStyle());
        Random rnd = new Random(7);
        String[] palette = { ACCENT, OK_FG, WARN_FG, DANGER_FG, "#a78bfa", "#ec4899", "#3b82f6" };

        // 1) BOUNCING SHAPES — 250 rounded rectangles with rotate + translate
        //    Timelines. Big test of per-frame scene-graph cost.
        int N_BOXES = 250;
        for (int i = 0; i < N_BOXES; i++) {
            Rectangle r = new Rectangle(20 + rnd.nextInt(60), 20 + rnd.nextInt(60));
            r.setArcWidth(8); r.setArcHeight(8);
            r.setFill(Color.web(palette[rnd.nextInt(palette.length)], 0.45));
            r.setStroke(Color.web(palette[rnd.nextInt(palette.length)]));
            r.setStrokeWidth(1.5);
            r.setOpacity(0.85);
            r.setTranslateX((rnd.nextDouble() - 0.5) * 800);
            r.setTranslateY((rnd.nextDouble() - 0.5) * 500);
            r.setRotate(rnd.nextDouble() * 360);
            field.getChildren().add(r);

            double dur = 3 + rnd.nextDouble() * 7;
            Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO,
                    new KeyValue(r.translateXProperty(), r.getTranslateX()),
                    new KeyValue(r.translateYProperty(), r.getTranslateY()),
                    new KeyValue(r.rotateProperty(),      r.getRotate()),
                    new KeyValue(r.scaleXProperty(),      0.6)),
                new KeyFrame(Duration.seconds(dur),
                    new KeyValue(r.translateXProperty(), (rnd.nextDouble() - 0.5) * 900, Interpolator.EASE_BOTH),
                    new KeyValue(r.translateYProperty(), (rnd.nextDouble() - 0.5) * 600, Interpolator.EASE_BOTH),
                    new KeyValue(r.rotateProperty(),      r.getRotate() + 360, Interpolator.LINEAR),
                    new KeyValue(r.scaleXProperty(),      1.4, Interpolator.EASE_BOTH))
            );
            tl.setCycleCount(Timeline.INDEFINITE);
            tl.setAutoReverse(true);
            tl.play();
        }

        // 2) PARTICLE FIELD — 400 small circles drifting in random directions.
        //    Pure GPU-visible primitives; cheap individually, expensive in
        //    aggregate. Good test of draw-call batching.
        int N_PARTICLES = 400;
        for (int i = 0; i < N_PARTICLES; i++) {
            Circle p = new Circle(2 + rnd.nextDouble() * 6);
            p.setFill(Color.web(palette[rnd.nextInt(palette.length)], 0.7));
            p.setTranslateX((rnd.nextDouble() - 0.5) * 800);
            p.setTranslateY((rnd.nextDouble() - 0.5) * 500);
            field.getChildren().add(p);

            double dur = 2 + rnd.nextDouble() * 5;
            Timeline t = new Timeline(
                new KeyFrame(Duration.ZERO,
                    new KeyValue(p.translateXProperty(), p.getTranslateX()),
                    new KeyValue(p.translateYProperty(), p.getTranslateY()),
                    new KeyValue(p.opacityProperty(),     0.2)),
                new KeyFrame(Duration.seconds(dur),
                    new KeyValue(p.translateXProperty(), (rnd.nextDouble() - 0.5) * 900, Interpolator.LINEAR),
                    new KeyValue(p.translateYProperty(), (rnd.nextDouble() - 0.5) * 600, Interpolator.LINEAR),
                    new KeyValue(p.opacityProperty(),     1.0, Interpolator.EASE_BOTH))
            );
            t.setCycleCount(Timeline.INDEFINITE);
            t.setAutoReverse(true);
            t.play();
        }

        // 3) CONCENTRIC ORBITING RINGS — three rings of dots rotating at
        //    different speeds in opposite directions. Visual centerpiece.
        for (int ring = 0; ring < 3; ring++) {
            StackPane ringPane = new StackPane();
            int RING_COUNT = 18 + ring * 6;
            double radius = 80 + ring * 50;
            for (int i = 0; i < RING_COUNT; i++) {
                Circle dot = new Circle(5 + ring * 1.5);
                dot.setFill(Color.web(palette[ring % palette.length], 0.85));
                double angle = (Math.PI * 2 * i) / RING_COUNT;
                dot.setTranslateX(Math.cos(angle) * radius);
                dot.setTranslateY(Math.sin(angle) * radius);
                ringPane.getChildren().add(dot);
            }
            // Alternating directions so adjacent rings counter-rotate.
            double endAngle = (ring % 2 == 0) ? 360 : -360;
            Timeline rotate = new Timeline(
                new KeyFrame(Duration.ZERO,
                    new KeyValue(ringPane.rotateProperty(), 0)),
                new KeyFrame(Duration.seconds(6 + ring * 2),
                    new KeyValue(ringPane.rotateProperty(), endAngle, Interpolator.LINEAR))
            );
            rotate.setCycleCount(Timeline.INDEFINITE);
            rotate.play();
            StackPane.setAlignment(ringPane, Pos.CENTER);
            field.getChildren().add(ringPane);
        }

        // 4) WAVE FIELD — a horizontal row of small dots, each animated on
        //    its own phase-shifted sine wave via an AnimationTimer (frame-
        //    accurate, no Timeline overhead). Demonstrates per-frame
        //    computed positions are not a bottleneck.
        int WAVE_COUNT = 80;
        Circle[] wave = new Circle[WAVE_COUNT];
        for (int i = 0; i < WAVE_COUNT; i++) {
            Circle d = new Circle(4);
            d.setFill(Color.web(ACCENT, 0.8));
            d.setTranslateX(-380 + i * (760.0 / WAVE_COUNT));
            d.setTranslateY(250);
            wave[i] = d;
            field.getChildren().add(d);
        }
        new AnimationTimer() {
            long start = 0;
            @Override public void handle(long now) {
                if (start == 0) start = now;
                double t = (now - start) / 1_000_000_000.0;
                for (int i = 0; i < wave.length; i++) {
                    double phase = i * 0.18;
                    wave[i].setTranslateY(250 + Math.sin(t * 2 + phase) * 30);
                }
            }
        }.start();

        VBox.setVgrow(field, Priority.ALWAYS);
        return wrapContent(heading, field);
    }

    // ---- Helpers ----------------------------------------------------------

    private Region wrapContent(Label heading, Region body) {
        VBox v = new VBox(20, heading, body);
        v.setPadding(new Insets(28));
        VBox.setVgrow(body, Priority.ALWAYS);
        ScrollPane sp = new ScrollPane(v);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setStyle(
            "-fx-background: " + BG_CONTENT + ";"
                + "-fx-background-color: " + BG_CONTENT + ";"
        );
        sp.setPrefViewportHeight(Region.USE_COMPUTED_SIZE);
        sp.setMaxWidth(Double.MAX_VALUE);
        sp.setMaxHeight(Double.MAX_VALUE);
        return sp;
    }

    private Region card(Region inner) {
        VBox v = new VBox(inner);
        v.setPadding(new Insets(12));
        v.setStyle(cardStyle());
        v.setMaxWidth(Double.MAX_VALUE);
        v.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(inner, Priority.ALWAYS);
        return v;
    }

    private Label sectionHeading(String title, String sub) {
        Label t = new Label(title);
        t.setFont(Font.font("System", FontWeight.BOLD, 24));
        t.setStyle("-fx-text-fill: " + TEXT_FG + ";");
        Label s = new Label(sub);
        s.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 12px;");
        VBox v = new VBox(2, t, s);
        Label wrapper = new Label();
        wrapper.setGraphic(v);
        wrapper.setStyle("-fx-padding: 0;");
        return wrapper;
    }

    private HBox buildCardHeader(String title, String sub) {
        Label t = new Label(title);
        t.setStyle("-fx-text-fill: " + TEXT_FG + "; -fx-font-size: 14px; -fx-font-weight: bold;");
        Label s = new Label(sub);
        s.setStyle("-fx-text-fill: " + TEXT_DIM + "; -fx-font-size: 11px; -fx-padding: 0 0 0 12;");
        HBox h = new HBox(8, t, s);
        h.setAlignment(Pos.BASELINE_LEFT);
        h.setPadding(new Insets(0, 0, 12, 0));

        return h;
    }

    private String cardStyle() {
        return "-fx-background-color: " + BG_CARD + ";"
            + "-fx-background-radius: 8;"
            + "-fx-border-color: " + BORDER + ";"
            + "-fx-border-width: 1;"
            + "-fx-border-radius: 8;";
    }

    /** Tiny inline VBox spacer of the given height. */
    private static final class Spacer extends Region {
        Spacer(double h) {
            setMinHeight(h);
            setPrefHeight(h);
            setMaxHeight(h);
        }
    }


    @Override
    public void init() throws Exception {
        Application.setVsyncEnabled(true);
    }
}
