/*
 * CustomTitleBarDemo — exercises StageStyle.CUSTOM end-to-end.
 *
 * The window has no platform decorations; this sample draws its own
 * title bar (icon + title text + minimize/maximize/close buttons)
 * directly in the scene graph, then tells the system which parts of
 * the scene are draggable / button hit zones via the Stage hit-region
 * API. The OS still does the heavy lifting (drag to move, double-click
 * to maximize, Aero snap, right-click for system menu, edge resize) —
 * we just provide the geometry.
 *
 * The caption buttons are drawn with SVGPath glyphs (stroked, Fluent
 * style) instead of font characters, so they stay crisp at any DPI and
 * the maximize button can swap its shape (single square ⇄ overlapping
 * squares) as the window maximizes / restores.
 *
 * Around that chrome the sample builds a realistic, app-shaped UI:
 * a collapsible navigation rail switching between six views (Dashboard,
 * Inbox, Projects, Analytics, Team, Settings), each populated with real
 * JavaFX controls — stat cards, charts, a master/detail mail list, a
 * sortable project table with progress bars, member cards, and a
 * settings form. It doubles as a broad control-coverage smoke test for
 * the Skia pipeline.
 *
 * Run: ./gradlew :samples:ensemble:runCustomTitleBar
 */
package org.openjfx.samples.ensemble;

import java.util.List;
import java.util.function.Supplier;

import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public final class CustomTitleBarDemo extends Application {

    // Caption glyphs on a 0..10 grid, stroked (not filled). The .5
    // offsets keep the 1px stroke on pixel centres so edges stay crisp.
    private static final String SVG_MIN     = "M0,5 H10";
    private static final String SVG_MAX     = "M0.5,0.5 H9.5 V9.5 H0.5 Z";
    // Restore: a front square plus the visible top/right "L" of the
    // square behind it — the classic Windows restore mark.
    private static final String SVG_RESTORE =
            "M0.5,2.5 H7.5 V9.5 H0.5 Z M2.5,2.5 V0.5 H9.5 V7.5 H7.5";
    private static final String SVG_CLOSE   = "M0,0 L10,10 M10,0 L0,10";

    // Feather-style 24-grid nav glyphs, stroked. Crisp at any DPI.
    private static final String SVG_NAV_DASH =
            "M3 3h7v9H3z M14 3h7v5h-7z M14 12h7v9h-7z M3 16h7v5H3z";
    private static final String SVG_NAV_INBOX =
            "M22 12h-6l-2 3h-4l-2-3H2 M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16"
            + "a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24"
            + "a2 2 0 0 0-1.79 1.11z";
    private static final String SVG_NAV_PROJECTS =
            "M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9"
            + "a2 2 0 0 1 2 2z";
    private static final String SVG_NAV_ANALYTICS =
            "M18 20V10 M12 20V4 M6 20v-6";
    private static final String SVG_NAV_TEAM =
            "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2 "
            + "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z "
            + "M23 21v-2a4 4 0 0 0-3-3.87 M16 3.13a4 4 0 0 1 0 7.75";
    private static final String SVG_NAV_SETTINGS =
            "M4 21v-7 M4 10V3 M12 21v-9 M12 8V3 M20 21v-5 M20 12V3 "
            + "M1 14h6 M9 8h6 M17 16h6";
    private static final String SVG_HAMBURGER = "M2 5h20 M2 12h20 M2 19h20";

    private Stage stage;
    private final BorderPane viewHolder = new BorderPane();
    private final Label crumb = new Label("Dashboard");

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.initStyle(StageStyle.CUSTOM);
        stage.setTitle("Nebula Workspace");

        // ---- caption (window) buttons --------------------------------
        // No full-width title bar: the sidebar runs the whole height and
        // the window buttons live in the top-right of the content column.
        SVGPath minIcon   = icon(SVG_MIN);
        SVGPath maxIcon    = icon(SVG_MAX);
        SVGPath closeIcon  = icon(SVG_CLOSE);

        Region minBtn   = button("min", minIcon);
        Region maxBtn   = button("max", maxIcon);
        Region closeBtn = button("close", closeIcon);

        // The maximize button morphs between "maximize" and "restore"
        // as the window state changes — driven straight off the Stage
        // property so it stays correct no matter who triggered it (our
        // button, a double-click on the caption, Win+Up, Aero snap…).
        maxIcon.setContent(stage.isMaximized() ? SVG_RESTORE : SVG_MAX);
        stage.maximizedProperty().addListener((obs, was, isMax) ->
                maxIcon.setContent(isMax ? SVG_RESTORE : SVG_MAX));

        // Wire up the actual actions. The OS sends SC_MINIMIZE /
        // SC_MAXIMIZE / SC_CLOSE for HT_MIN/MAX/CLOSE hit codes
        // automatically, but JavaFX consumers usually want to drive
        // these from the JFX-level Stage API.
        minBtn.setOnMouseClicked(e -> stage.setIconified(true));
        maxBtn.setOnMouseClicked(e -> stage.setMaximized(!stage.isMaximized()));
        closeBtn.setOnMouseClicked(e -> stage.close());

        HBox winButtons = new HBox(minBtn, maxBtn, closeBtn);
        winButtons.getStyleClass().add("win-buttons");
        winButtons.setAlignment(Pos.TOP_RIGHT);

        // ---- navigation rail (full height) --------------------------
        ToggleGroup nav = new ToggleGroup();
        ToggleButton tDash      = navButton(nav, "Dashboard", SVG_NAV_DASH, this::dashboardView);
        ToggleButton tInbox     = navButton(nav, "Inbox", SVG_NAV_INBOX, this::inboxView);
        ToggleButton tProjects  = navButton(nav, "Projects", SVG_NAV_PROJECTS, this::projectsView);
        ToggleButton tAnalytics = navButton(nav, "Analytics", SVG_NAV_ANALYTICS, this::analyticsView);
        ToggleButton tTeam      = navButton(nav, "Team", SVG_NAV_TEAM, this::teamView);
        ToggleButton tSettings  = navButton(nav, "Settings", SVG_NAV_SETTINGS, this::settingsView);

        VBox navItems = new VBox(4, tDash, tInbox, tProjects, tAnalytics, tTeam,
                new Separator(), tSettings);
        navItems.getStyleClass().add("nav-items");

        Region navHeaderMark = new Region();
        navHeaderMark.getStyleClass().add("app-mark");
        navHeaderMark.setPrefSize(20, 20);
        navHeaderMark.setMinSize(20, 20);
        navHeaderMark.setMaxSize(20, 20);
        Label navHeaderText = new Label("Nebula");
        navHeaderText.getStyleClass().add("nav-brand");
        HBox navHeader = new HBox(10, navHeaderMark, navHeaderText);
        navHeader.setAlignment(Pos.CENTER_LEFT);
        navHeader.getStyleClass().add("nav-header");

        // Footer: a tiny "logged-in user" cell, like every real app.
        VBox navRail = new VBox(navHeader, navItems, grow(), userCell());
        navRail.getStyleClass().add("nav-rail");
        navRail.setMinWidth(212);
        navRail.setPrefWidth(212);

        // Collapse / expand the rail (icons-only ⇄ icons+labels).
        Region hamburger = button("nav-toggle", icon24(SVG_HAMBURGER));
        hamburger.getStyleClass().add("rail-toggle");
        hamburger.setOnMouseClicked(e -> toggleRail(navRail, navHeaderText));

        // ---- content header (breadcrumb + actions) ------------------
        crumb.getStyleClass().add("crumb");

        TextField search = new TextField();
        search.setPromptText("Search…  ( Ctrl + K )");
        search.getStyleClass().add("search-field");
        search.setPrefWidth(280);

        Button newBtn = new Button("New");
        newBtn.getStyleClass().add("accent-btn");
        newBtn.setOnAction(e -> info("New item",
                "This is where a create flow would open."));

        // Left tools (collapse + breadcrumb) and right tools (search +
        // New) flank a flexible, draggable caption strip. The window
        // buttons sit flush in the top-right corner of the content column.
        HBox leftTools = new HBox(10, hamburger, crumb);
        leftTools.setAlignment(Pos.CENTER_LEFT);
        leftTools.setPadding(new Insets(0, 0, 0, 8));

        Region capDrag = new Region();
        HBox.setHgrow(capDrag, Priority.ALWAYS);

        HBox rightTools = new HBox(12, search, newBtn);
        rightTools.setAlignment(Pos.CENTER_RIGHT);
        rightTools.setPadding(new Insets(0, 12, 0, 12));

        HBox topStrip = new HBox(leftTools, capDrag, rightTools, winButtons);
        topStrip.getStyleClass().add("top-strip");
        topStrip.setAlignment(Pos.CENTER_LEFT);
        topStrip.setMinHeight(44);
        topStrip.setPrefHeight(44);

        viewHolder.getStyleClass().add("view-holder");
        VBox.setVgrow(viewHolder, Priority.ALWAYS);

        // ---- status bar ---------------------------------------------
        Label stateDot = new Label();
        stateDot.getStyleClass().add("state-dot");

        Label stateLabel = new Label();
        stateLabel.getStyleClass().add("status-text");
        stateLabel.textProperty().bind(Bindings
                .when(stage.maximizedProperty())
                .then("Maximized")
                .otherwise("Restored"));

        Label syncLabel = new Label("All changes saved");
        syncLabel.getStyleClass().add("status-text");

        Region statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);

        Label sizeLabel = new Label();
        sizeLabel.getStyleClass().add("status-text");
        sizeLabel.textProperty().bind(Bindings.createStringBinding(
                () -> Math.round(stage.getWidth()) + " × " + Math.round(stage.getHeight()) + " px",
                stage.widthProperty(), stage.heightProperty()));

        HBox statusBar = new HBox(8, stateDot, stateLabel, sep(), syncLabel,
                statusSpacer, sizeLabel);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(0, 16, 0, 16));
        statusBar.setMinHeight(30);
        statusBar.setPrefHeight(30);

        // ---- assemble + stylesheet ----------------------------------
        // Content column: top strip · view · status bar. The nav rail to
        // its left spans the full window height (top-to-bottom sidebar).
        VBox rightColumn = new VBox(topStrip, viewHolder, statusBar);
        rightColumn.getStyleClass().add("content-column");
        HBox.setHgrow(rightColumn, Priority.ALWAYS);

        HBox root = new HBox(navRail, rightColumn);
        root.getStyleClass().add("root-pane");

        Scene scene = new Scene(root, 1120, 720);
        var css = ClassLoader.getSystemResource("custom-titlebar-demo.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }

        // ---- register hit regions ----------------------------------
        // Drag zones: the sidebar header (top of the full-height rail)
        // and the flexible caption strip in the content column. The
        // buttons get their dedicated hit codes so hover styling /
        // Win11 snap-layouts work.
        stage.setCaptionRegions(navHeader, capDrag);
        stage.setMinRegion(minBtn);
        stage.setMaxRegion(maxBtn);
        stage.setCloseRegion(closeBtn);

        // Land on the dashboard.
        tDash.setSelected(true);

        stage.setScene(scene);
        stage.show();
    }

    // ============================================================ VIEWS

    /** Dashboard: greeting, KPI cards, a trend chart and an activity feed. */
    private Node dashboardView() {
        Label hi = new Label("Good afternoon, Jordan");
        hi.getStyleClass().add("heading");
        Label sub = new Label("Here's what's happening across your workspace today.");
        sub.getStyleClass().add("subtitle");

        FlowPane kpis = new FlowPane(16, 16,
                statCard("Revenue", "$48.2k", "+12.4% vs last week", true),
                statCard("Active users", "3,914", "+5.1% vs last week", true),
                statCard("Open tickets", "27", "-8 since Monday", true),
                statCard("Churn", "1.8%", "+0.3% vs last week", false));
        kpis.getStyleClass().add("card-row");

        // Trend line — two series.
        NumberAxis x = new NumberAxis(1, 12, 1);
        x.setLabel("Week");
        NumberAxis y = new NumberAxis();
        y.setLabel("k$");
        LineChart<Number, Number> trend = new LineChart<>(x, y);
        trend.getStyleClass().add("panel");
        trend.setTitle("Revenue trend");
        trend.setPrefHeight(300);
        trend.setCreateSymbols(false);
        trend.getData().add(series("This year",
                32, 35, 33, 40, 44, 42, 48, 52, 49, 55, 58, 61));
        trend.getData().add(series("Last year",
                28, 30, 31, 29, 34, 36, 38, 37, 41, 40, 44, 46));

        VBox trendPanel = new VBox(trend);
        HBox.setHgrow(trendPanel, Priority.ALWAYS);

        VBox feed = panel("Recent activity",
                activityRow("AM", "Ava Morgan", "closed ticket #4821", "2m"),
                activityRow("RK", "Ravi Kapoor", "merged \"Skia present path\"", "18m"),
                activityRow("LS", "Lena Soto", "commented on Q3 roadmap", "1h"),
                activityRow("DT", "Diego Torres", "uploaded design specs", "3h"),
                activityRow("YN", "Yuki Nakamura", "invited 2 members", "5h"));
        feed.setMinWidth(320);
        feed.setPrefWidth(320);

        HBox charts = new HBox(16, trendPanel, feed);

        VBox content = new VBox(20, header(hi, sub), kpis, charts);
        return page(content);
    }

    /** Inbox: a master/detail mail client (list + reading pane). */
    private Node inboxView() {
        record Mail(String from, String initials, String subject, String preview, String time) {}
        ObservableList<Mail> mails = FXCollections.observableArrayList(
                new Mail("Ava Morgan", "AM", "Q3 roadmap review",
                        "I've left a few comments on the milestones — mainly around the…", "09:24"),
                new Mail("GitHub", "GH", "[nebula] 3 new pull requests",
                        "skia-present-zero-copy, glyph-atlas-lru and dpi-per-monitor are…", "08:51"),
                new Mail("Ravi Kapoor", "RK", "Re: render thread profiling",
                        "Numbers look great after the staging-buffer change. Frame time…", "08:10"),
                new Mail("Lena Soto", "LS", "Design handoff: settings panel",
                        "Specs and tokens are in Figma. Spacing is on an 8px grid as…", "Yesterday"),
                new Mail("Billing", "$", "Your invoice is ready",
                        "Invoice #2025-118 for the Pro plan is attached. No action is…", "Yesterday"),
                new Mail("Diego Torres", "DT", "Lunch?",
                        "Anyone up for the new ramen place at 12:30? It's a 5 minute…", "Mon"));

        ListView<Mail> list = new ListView<>(mails);
        list.getStyleClass().add("mail-list");
        list.setMinWidth(340);
        list.setPrefWidth(340);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Mail m, boolean empty) {
                super.updateItem(m, empty);
                if (empty || m == null) { setGraphic(null); setText(null); return; }
                Label from = new Label(m.from());
                from.getStyleClass().add("mail-from");
                Label time = new Label(m.time());
                time.getStyleClass().add("mail-time");
                Region s = new Region();
                HBox.setHgrow(s, Priority.ALWAYS);
                HBox top = new HBox(from, s, time);
                top.setAlignment(Pos.CENTER_LEFT);
                Label subj = new Label(m.subject());
                subj.getStyleClass().add("mail-subject");
                Label prev = new Label(m.preview());
                prev.getStyleClass().add("mail-preview");
                VBox text = new VBox(2, top, subj, prev);
                HBox.setHgrow(text, Priority.ALWAYS);
                HBox cell = new HBox(12, avatar(m.initials(), "av-mail"), text);
                cell.setAlignment(Pos.CENTER_LEFT);
                cell.setPadding(new Insets(10, 6, 10, 6));
                setGraphic(cell);
            }
        });

        // Reading pane — bound to the current selection.
        Label readFrom = new Label();
        readFrom.getStyleClass().add("read-from");
        Label readSubject = new Label();
        readSubject.getStyleClass().add("read-subject");
        Label readBody = new Label();
        readBody.getStyleClass().add("read-body");
        readBody.setWrapText(true);

        Runnable showSel = () -> {
            Mail m = list.getSelectionModel().getSelectedItem();
            if (m == null) { readFrom.setText(""); readSubject.setText(""); readBody.setText(""); return; }
            readFrom.setText(m.from() + "  ·  " + m.time());
            readSubject.setText(m.subject());
            readBody.setText(m.preview() + "\n\n"
                    + "This is a rendered reading pane for the selected message. "
                    + "Pick another item from the list on the left to update it. "
                    + "Everything here is a live JavaFX control tree composited by "
                    + "the Skia pipeline.\n\n— " + m.from());
        };
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showSel.run());
        list.getSelectionModel().selectFirst();
        showSel.run();

        Button reply = new Button("Reply");
        reply.getStyleClass().add("accent-btn");
        reply.setOnAction(e -> info("Reply", "A compose window would open here."));
        Button archive = new Button("Archive");
        archive.getStyleClass().add("ghost-btn");
        Button delete = new Button("Delete");
        delete.getStyleClass().add("ghost-btn");
        HBox readActions = new HBox(10, reply, archive, delete);

        VBox reader = new VBox(14, readFrom, readSubject, new Separator(), readBody, grow(), readActions);
        reader.getStyleClass().add("panel");
        reader.setPadding(new Insets(20));
        HBox.setHgrow(reader, Priority.ALWAYS);

        HBox split = new HBox(16, list, reader);
        VBox.setVgrow(split, Priority.ALWAYS);
        VBox.setVgrow(list, Priority.ALWAYS);
        return page(new VBox(split));
    }

    /** Projects: a sortable table with status badges and progress bars. */
    private Node projectsView() {
        record Project(String name, String owner, String status, double progress, String due) {}
        ObservableList<Project> rows = FXCollections.observableArrayList(
                new Project("Skia present path", "Ravi Kapoor", "On track", 0.82, "Jun 14"),
                new Project("Glyph atlas LRU", "Ava Morgan", "On track", 0.64, "Jun 20"),
                new Project("Per-monitor DPI", "Lena Soto", "At risk", 0.41, "Jun 11"),
                new Project("WebView OSR input", "Diego Torres", "On track", 0.93, "Jun 09"),
                new Project("Media dual-source", "Yuki Nakamura", "Blocked", 0.28, "Jun 27"),
                new Project("Copy counter UI", "Ava Morgan", "Done", 1.00, "May 30"),
                new Project("Effects gallery", "Ravi Kapoor", "On track", 0.71, "Jun 18"));

        TableView<Project> table = new TableView<>(rows);
        table.getStyleClass().add("panel");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<Project, String> cName = new TableColumn<>("Project");
        cName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        cName.setPrefWidth(220);

        TableColumn<Project, String> cOwner = new TableColumn<>("Owner");
        cOwner.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().owner()));
        cOwner.setPrefWidth(160);

        TableColumn<Project, String> cStatus = new TableColumn<>("Status");
        cStatus.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status()));
        cStatus.setPrefWidth(120);
        cStatus.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setGraphic(null); return; }
                Label badge = new Label(s);
                badge.getStyleClass().addAll("badge", "badge-" + s.toLowerCase().replace(' ', '-'));
                setGraphic(badge);
            }
        });

        TableColumn<Project, Double> cProg = new TableColumn<>("Progress");
        cProg.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().progress()));
        cProg.setPrefWidth(200);
        cProg.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar();
            private final Label pct = new Label();
            private final HBox box = new HBox(8, bar, pct);
            { box.setAlignment(Pos.CENTER_LEFT); bar.setPrefWidth(110); }
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setGraphic(null); return; }
                bar.setProgress(v);
                pct.setText(Math.round(v * 100) + "%");
                pct.getStyleClass().setAll("status-text");
                setGraphic(box);
            }
        });

        TableColumn<Project, String> cDue = new TableColumn<>("Due");
        cDue.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().due()));

        table.getColumns().setAll(List.of(cName, cOwner, cStatus, cProg, cDue));

        Label h = new Label("Projects");
        h.getStyleClass().add("heading");
        Label sub = new Label(rows.size() + " active projects · click a column header to sort");
        sub.getStyleClass().add("subtitle");

        VBox content = new VBox(16, header(h, sub), table);
        VBox.setVgrow(content, Priority.ALWAYS);
        return page(content);
    }

    /** Analytics: a bar chart and a pie chart side by side. */
    private Node analyticsView() {
        CategoryAxis bx = new CategoryAxis();
        NumberAxis by = new NumberAxis();
        by.setLabel("Sessions (k)");
        BarChart<String, Number> bar = new BarChart<>(bx, by);
        bar.getStyleClass().add("panel");
        bar.setTitle("Sessions by channel");
        bar.setPrefHeight(340);
        XYChart.Series<String, Number> s1 = new XYChart.Series<>();
        s1.setName("Q2");
        s1.getData().add(new XYChart.Data<>("Direct", 42));
        s1.getData().add(new XYChart.Data<>("Search", 58));
        s1.getData().add(new XYChart.Data<>("Social", 31));
        s1.getData().add(new XYChart.Data<>("Email", 24));
        s1.getData().add(new XYChart.Data<>("Referral", 19));
        bar.getData().add(s1);
        HBox.setHgrow(bar, Priority.ALWAYS);

        PieChart pie = new PieChart(FXCollections.observableArrayList(
                new PieChart.Data("Desktop", 54),
                new PieChart.Data("Mobile", 33),
                new PieChart.Data("Tablet", 13)));
        pie.getStyleClass().add("panel");
        pie.setTitle("Devices");
        pie.setLabelsVisible(true);
        pie.setLegendSide(Side.BOTTOM);
        pie.setPrefWidth(360);
        pie.setPrefHeight(340);

        Label h = new Label("Analytics");
        h.getStyleClass().add("heading");
        Label sub = new Label("Traffic and engagement for the current quarter.");
        sub.getStyleClass().add("subtitle");

        HBox charts = new HBox(16, bar, pie);
        return page(new VBox(20, header(h, sub), charts));
    }

    /** Team: a responsive grid of member cards. */
    private Node teamView() {
        Label h = new Label("Team");
        h.getStyleClass().add("heading");
        Label sub = new Label("9 members · 3 online");
        sub.getStyleClass().add("subtitle");

        FlowPane grid = new FlowPane(16, 16,
                memberCard("AM", "Ava Morgan", "Engineering Lead", true),
                memberCard("RK", "Ravi Kapoor", "Graphics Engineer", true),
                memberCard("LS", "Lena Soto", "Product Designer", false),
                memberCard("DT", "Diego Torres", "Frontend Engineer", true),
                memberCard("YN", "Yuki Nakamura", "Media Engineer", false),
                memberCard("PM", "Priya Mehta", "QA Engineer", false),
                memberCard("OK", "Omar Khan", "DevOps", false),
                memberCard("EC", "Emma Clark", "Product Manager", false),
                memberCard("NB", "Noah Bennett", "Support", false));
        grid.getStyleClass().add("card-row");

        return page(new VBox(20, header(h, sub), grid));
    }

    /** Settings: a real form — text fields, combos, toggles, a slider. */
    private Node settingsView() {
        Label h = new Label("Settings");
        h.getStyleClass().add("heading");
        Label sub = new Label("Workspace preferences. Changes are saved per account.");
        sub.getStyleClass().add("subtitle");

        TextField name = new TextField("Jordan Avery");
        TextField email = new TextField("jordan@nebula.app");

        ComboBox<String> theme = new ComboBox<>(FXCollections.observableArrayList(
                "System", "Dark", "Light"));
        theme.getSelectionModel().select("Dark");
        ComboBox<String> density = new ComboBox<>(FXCollections.observableArrayList(
                "Comfortable", "Cozy", "Compact"));
        density.getSelectionModel().select("Comfortable");

        CheckBox notif = new CheckBox("Desktop notifications");
        notif.setSelected(true);
        CheckBox sounds = new CheckBox("Notification sounds");
        CheckBox beta = new CheckBox("Join the beta channel");

        Slider sidebarWidth = new Slider(180, 320, 212);
        sidebarWidth.setShowTickMarks(true);
        sidebarWidth.setShowTickLabels(true);
        sidebarWidth.setMajorTickUnit(70);
        Label widthVal = new Label();
        widthVal.getStyleClass().add("status-text");
        widthVal.textProperty().bind(Bindings.createStringBinding(
                () -> Math.round(sidebarWidth.getValue()) + " px", sidebarWidth.valueProperty()));

        VBox form = new VBox(2,
                formRow("Display name", name),
                formRow("Email", email),
                formRow("Theme", theme),
                formRow("Density", density),
                formRow("Sidebar width", new HBox(12, sidebarWidth, widthVal)),
                formRow("Notifications", new VBox(8, notif, sounds, beta)));
        form.getStyleClass().add("panel");
        form.setPadding(new Insets(8, 20, 20, 20));
        form.setMaxWidth(620);

        Button save = new Button("Save changes");
        save.getStyleClass().add("accent-btn");
        save.setOnAction(e -> info("Saved", "Your preferences have been saved."));
        Button reset = new Button("Reset");
        reset.getStyleClass().add("ghost-btn");
        Button alertBtn = new Button("Show alert");
        alertBtn.getStyleClass().add("ghost-btn");
        // Diagnostic: nested event loop (showAndWait) in a non-WebView app.
        alertBtn.setOnAction(e -> {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "This is dialog content — buttons + this message should be visible.",
                ButtonType.OK, ButtonType.CANCEL);
            a.setHeaderText("Confirmation");
            a.initOwner(stage);
            a.showAndWait();
        });
        HBox actions = new HBox(12, save, reset, alertBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        return page(new VBox(20, header(h, sub), form, actions));
    }

    // ====================================================== UI HELPERS

    /** Builds a stroked SVGPath caption glyph (0..10 grid). */
    private static SVGPath icon(String svg) {
        SVGPath p = new SVGPath();
        p.setContent(svg);
        p.getStyleClass().add("ctb-icon");
        return p;
    }

    /** Stroked SVGPath glyph on a 24-grid (nav / toolbar icons). */
    private static SVGPath icon24(String svg) {
        SVGPath p = new SVGPath();
        p.setContent(svg);
        p.getStyleClass().add("nav-icon");
        return p;
    }

    /**
     * Wraps an icon in a fixed-size caption button. Returns a Region
     * (StackPane) so it can be handed straight to the Stage hit-region
     * API.
     */
    private static Region button(String role, SVGPath glyph) {
        StackPane b = new StackPane(glyph);
        b.getStyleClass().addAll("win-btn", "win-btn-" + role);
        b.setPrefSize(46, 44);
        b.setMinSize(46, 44);
        b.setMaxSize(46, 44);
        return b;
    }

    /** A selectable navigation-rail entry (icon + label). */
    private ToggleButton navButton(ToggleGroup group, String name, String svg,
                                   Supplier<Node> viewFactory) {
        SVGPath glyph = icon24(svg);
        Label label = new Label(name);
        label.getStyleClass().add("nav-label");
        HBox box = new HBox(12, glyph, label);
        box.setAlignment(Pos.CENTER_LEFT);

        ToggleButton b = new ToggleButton();
        b.setGraphic(box);
        b.getStyleClass().add("nav-button");
        b.setToggleGroup(group);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setTooltip(new Tooltip(name));
        // Lazy view, rebuilt on each navigation (keeps the sample simple
        // and exercises full scene-graph construction each switch).
        b.selectedProperty().addListener((o, was, sel) -> {
            if (sel) {
                crumb.setText(name);
                viewHolder.setCenter(viewFactory.get());
            }
        });
        return b;
    }

    private void toggleRail(VBox rail, Label brand) {
        boolean collapsing = rail.getPrefWidth() > 80;
        double w = collapsing ? 64 : 212;
        rail.setMinWidth(w);
        rail.setPrefWidth(w);
        rail.setMaxWidth(w);
        // Hide text labels when collapsed.
        boolean show = !collapsing;
        brand.setVisible(show);
        brand.setManaged(show);
        rail.lookupAll(".nav-label").forEach(n -> { n.setVisible(show); n.setManaged(show); });
        if (collapsing) rail.getStyleClass().add("collapsed");
        else rail.getStyleClass().remove("collapsed");
    }

    /** Header block (title + subtitle). */
    private static VBox header(Label heading, Label subtitle) {
        subtitle.setWrapText(true);
        VBox v = new VBox(4, heading, subtitle);
        return v;
    }

    /** Wraps page content in a padded, scrollable surface. */
    private static Node page(VBox content) {
        content.getStyleClass().add("content");
        content.setPadding(new Insets(24, 28, 24, 28));
        ScrollPane sp = new ScrollPane(content);
        sp.getStyleClass().add("page-scroll");
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        return sp;
    }

    /** A KPI / stat card: label, big value, delta line. */
    private static Region statCard(String label, String value, String delta, boolean good) {
        Label l = new Label(label);
        l.getStyleClass().add("stat-label");
        Label v = new Label(value);
        v.getStyleClass().add("stat-value");
        Label d = new Label(delta);
        d.getStyleClass().addAll("stat-delta", good ? "delta-up" : "delta-down");
        VBox card = new VBox(6, l, v, d);
        card.getStyleClass().addAll("card", "stat-card");
        card.setPrefWidth(232);
        card.setPadding(new Insets(18));
        return card;
    }

    /** A titled panel containing a vertical stack of children. */
    private static VBox panel(String titleText, Node... children) {
        Label t = new Label(titleText);
        t.getStyleClass().add("panel-title");
        VBox v = new VBox(10);
        v.getStyleClass().add("panel");
        v.setPadding(new Insets(18));
        v.getChildren().add(t);
        v.getChildren().addAll(children);
        return v;
    }

    private static Node activityRow(String initials, String who, String what, String when) {
        TextFlow flow = new TextFlow(text(who + "  ", "act-who"), text(what, "act-what"));
        Label t = new Label(when);
        t.getStyleClass().add("act-when");
        Region s = new Region();
        HBox.setHgrow(s, Priority.ALWAYS);
        HBox row = new HBox(12, avatar(initials, "av-feed"), flow, s, t);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("act-row");
        return row;
    }

    private static Text text(String s, String styleClass) {
        Text t = new Text(s);
        t.getStyleClass().add(styleClass);
        return t;
    }

    /** A circular monogram avatar. */
    private static StackPane avatar(String initials, String styleClass) {
        Circle c = new Circle(18);
        c.getStyleClass().addAll("avatar-bg", styleClass);
        Label l = new Label(initials);
        l.getStyleClass().add("avatar-text");
        StackPane sp = new StackPane(c, l);
        sp.setMinSize(36, 36);
        sp.setMaxSize(36, 36);
        return sp;
    }

    private static Region memberCard(String initials, String name, String role, boolean online) {
        StackPane av = avatar(initials, "av-member");
        Label n = new Label(name);
        n.getStyleClass().add("member-name");
        Label r = new Label(role);
        r.getStyleClass().add("member-role");
        Label status = new Label(online ? "● Online" : "○ Offline");
        status.getStyleClass().addAll("member-status", online ? "online" : "offline");
        VBox card = new VBox(8, av, n, r, status);
        card.setAlignment(Pos.TOP_LEFT);
        card.getStyleClass().addAll("card", "member-card");
        card.setPrefWidth(220);
        card.setPadding(new Insets(18));
        return card;
    }

    /** The "logged-in user" cell at the bottom of the rail. */
    private static Node userCell() {
        Label n = new Label("Jordan Avery");
        n.getStyleClass().add("user-name");
        Label e = new Label("Pro plan");
        e.getStyleClass().add("user-sub");
        VBox text = new VBox(0, n, e);
        HBox cell = new HBox(10, avatar("JA", "av-user"), text);
        cell.setAlignment(Pos.CENTER_LEFT);
        cell.getStyleClass().add("user-cell");
        cell.setPadding(new Insets(12, 14, 14, 14));
        return cell;
    }

    private static Node formRow(String label, Node control) {
        Label l = new Label(label);
        l.getStyleClass().add("form-label");
        l.setMinWidth(150);
        l.setPrefWidth(150);
        if (control instanceof Region r && !(control instanceof HBox) && !(control instanceof VBox)) {
            r.setPrefWidth(320);
        }
        HBox row = new HBox(16, l, control);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("form-row");
        row.setPadding(new Insets(12, 0, 12, 0));
        return row;
    }

    private static XYChart.Series<Number, Number> series(String name, double... ys) {
        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        s.setName(name);
        for (int i = 0; i < ys.length; i++) {
            s.getData().add(new XYChart.Data<>(i + 1, ys[i]));
        }
        return s;
    }

    private static Region grow() {
        Region r = new Region();
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    private static Node sep() {
        Separator s = new Separator(Orientation.VERTICAL);
        s.getStyleClass().add("status-sep");
        return s;
    }

    private void info(String header, String body) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, body, ButtonType.OK);
        a.setHeaderText(header);
        a.initOwner(stage);
        a.show();
    }

    @Override
    public void init() throws Exception {
        super.init();
        setGpuBackend(GpuBackend.DIRECT3D12);
    }

    static void main(String[] args) {
        launch(args);
    }
}
