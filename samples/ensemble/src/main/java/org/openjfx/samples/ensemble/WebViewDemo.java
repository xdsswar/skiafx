/*
 * Copyright (c) 2026, skia-fx. All rights reserved.
 */
package org.openjfx.samples.ensemble;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.scene.web.ContextMenuContext;
import javafx.scene.web.DownloadRequest;
import javafx.scene.web.NetworkExchange;
import javafx.scene.web.NetworkFilter;
import javafx.scene.web.NetworkInterceptor;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebHistory;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Subscription;

import netscape.javascript.JSObject;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Browser-style demo for the Blink-backed {@code WebView}: a gradient hero band
 * branding the pipeline, a navigation bar (SVG icon buttons + address field),
 * the page view, and a thin status/progress footer. All styling lives in
 * {@code webview-demo.css}; the layout is built in code (no FXML) so the sample
 * is self-contained.
 */
public class WebViewDemo extends Application<Stage> {

    /** Sentinel typed in the address bar to jump back to the home page. */
    private static final String HOME_TOKEN = "skia-fx";
    private static final String HOME_URL = "https://www.google.com";

    /** Classpath resource for the self-contained feature playground page. */
    private static final String FEATURES_RESOURCE = "webview-features.html";

    // Material-style 24×24 filled icon paths.
    private static final String SVG_BACK =
        "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z";
    private static final String SVG_FORWARD =
        "M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z";
    private static final String SVG_RELOAD =
        "M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8"
            + "c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6"
            + "s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z";
    private static final String SVG_HOME =
        "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z";
    private static final String SVG_SEARCH =
        "M15.5 14h-.79l-.28-.27a6.5 6.5 0 1 0-.7.7l.27.28v.79l5 4.99L20.49 19l-4.99-5z"
            + "m-6 0A4.5 4.5 0 1 1 14 9.5 4.5 4.5 0 0 1 9.5 14z";

    // URL globs for common ad / tracker endpoints, blocked by the demo's
    // NetworkInterceptor. Glob: '*' = any run. Matched against the full URL.
    // Includes a few YouTube ad/telemetry endpoints (e.g. /pagead/, stats/ads).
    // This is a demo of the Network facade, not a complete ad blocker — YouTube
    // serves video and ads from the same hosts, so some ads still get through.
    private static final String[] AD_PATTERNS = {
        "*doubleclick.net/*",
        "*googlesyndication.com/*",
        "*googleadservices.com/*",
        "*google-analytics.com/*",
        "*googletagservices.com/*",
        "*googletagmanager.com/*",
        "*adservice.google*",
        "*scorecardresearch.com/*",
        "*moatads.com/*",
        "*adnxs.com/*",
        "*/pagead/*",
        "*youtube.com/api/stats/ads*",
        "*youtube.com/ptracking*",
        "*youtube.com/get_midroll*",
    };

    /** Active ad-block registration (null when off) and a running blocked count. */
    private Subscription adBlockSub;
    private int adsBlocked;
    private ToggleButton adBlockBtn;

    // ---- LoadWorker monitor (test panel) state ----------------------------
    private Worker.State expectedTerminal;   // terminal state the active test awaits
    private boolean runningSeenThisTest;     // did RUNNING fire before the terminal?
    private boolean cancelOnRunning;         // cancel test: cancel as soon as RUNNING
    private TextArea workerLog;              // transition log sink
    private Label workerStateLabel;          // current-state readout
    private long workerT0;                    // panel-creation nanoTime for relative stamps

    @Override
    public void start(Stage stage) {
        final WebView view = new WebView();
        final WebEngine engine = view.getEngine();

        // ---- Hero band --------------------------------------------------
        final Label heroTitle = new Label("This is pure JavaFX");
        heroTitle.getStyleClass().add("hero-title");

        final TextFlow heroSubtitle = new TextFlow(
            plain("rendered by "), accent("Skia"),
            plain(" GPU  ·  "), accent("Blink WebView"),
            plain(" running "), accent("Chromium"));
        heroSubtitle.getStyleClass().add("hero-subtitle");
        heroSubtitle.setMaxWidth(Region.USE_PREF_SIZE);

        // Translucent overlay laid directly ON TOP of the WebView. This only
        // composites correctly because the WebView is a real JavaFX node in the
        // Skia scene graph — a native child HWND could never show JavaFX content
        // (let alone semi-transparent content) painted over it. Mouse-transparent
        // so clicks/scroll fall through to the page underneath.
        final VBox hero = new VBox(heroTitle, heroSubtitle);
        hero.getStyleClass().add("hero-overlay");
        hero.setMouseTransparent(true);
        hero.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        // ---- Navigation controls ----------------------------------------
        final Button back    = navButton(SVG_BACK,    "Back");
        final Button forward = navButton(SVG_FORWARD, "Forward");
        final Button reload  = navButton(SVG_RELOAD,  "Reload");
        final Button home    = navButton(SVG_HOME,    "Home");

        final TextField urlBar = new TextField(HOME_TOKEN);
        urlBar.getStyleClass().add("url-bar");
        urlBar.setPromptText("Search Google or type a URL");
        HBox.setHgrow(urlBar, Priority.ALWAYS);

        final Button go = iconButton(SVG_SEARCH, "go-button", "go-icon", "Go / Search");
        go.setDefaultButton(true);

        // Ad / tracker blocker via the new Network facade. On by default; toggle
        // here or from the right-click context menu's "Block ads" item.
        adBlockBtn = new ToggleButton("Ad-block: on");
        adBlockBtn.setFocusTraversable(false);
        adBlockBtn.setTooltip(new Tooltip("Block common ad / tracker requests"));
        adBlockBtn.setOnAction(e -> setAdBlockEnabled(adBlockBtn.isSelected(), engine));

        // Toggles the LoadWorker monitor / notification-test panel (built below).
        final ToggleButton workerBtn = new ToggleButton("Worker");
        workerBtn.setFocusTraversable(false);
        workerBtn.setTooltip(new Tooltip("Open the LoadWorker state monitor / tests"));

        // Toggles the Java↔JS bridge (JSObject) tester (built below).
        final ToggleButton jsBtn = new ToggleButton("JS");
        jsBtn.setFocusTraversable(false);
        jsBtn.setTooltip(new Tooltip("Open the Java↔JS bridge (JSObject) tester"));

        // Crash-recovery test: forcibly kills the engine process so you can watch
        // the WebView self-heal — respawn + restore scroll/forms (session-restore),
        // with the last frame held on screen during the gap. Scroll the page and
        // wait a couple seconds (for a session snapshot) before clicking.
        final Button crashBtn = new Button("Crash engine");
        crashBtn.setFocusTraversable(false);
        crashBtn.setTooltip(new Tooltip(
            "Kill the engine process to test crash recovery (respawn + state restore)"));

        final Runnable navigate = () -> {
            String t = urlBar.getText() == null ? "" : urlBar.getText().trim();
            if (HOME_TOKEN.equalsIgnoreCase(t) || t.isEmpty()) {
                loadFeatures(engine);  // feature playground (real .html resource)
            } else {
                engine.load(normalizeUrl(t));
            }
        };
        go.setOnAction(e -> navigate.run());
        urlBar.setOnAction(e -> navigate.run());
        home.setOnAction(e -> { urlBar.setText(HOME_TOKEN); navigate.run(); });
        reload.setOnAction(e -> engine.reload());
        // go(offset) navigates the engine's session history; guard the range so
        // a stray click at an end can't throw IndexOutOfBoundsException.
        back.setOnAction(e -> {
            WebHistory h = engine.getHistory();
            if (h.getCurrentIndex() > 0) h.go(-1);
        });
        forward.setOnAction(e -> {
            WebHistory h = engine.getHistory();
            if (h.getCurrentIndex() < h.getEntries().size() - 1) h.go(1);
        });

        final HBox navGroup = new HBox(back, forward, reload, home);
        navGroup.getStyleClass().add("nav-group");

        final HBox toolbar = new HBox(navGroup, urlBar, adBlockBtn, workerBtn, jsBtn, crashBtn, go);
        toolbar.getStyleClass().add("toolbar");

        // ---- Status / progress footer -----------------------------------
        final Label status = new Label("Ready");
        status.getStyleClass().add("status-label");
        final ProgressBar progress = new ProgressBar(0);
        progress.setVisible(false);
        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        final HBox footer = new HBox(status, spacer, progress);
        footer.getStyleClass().add("footer");

        // ---- WebView fills the middle, with the hero overlaid on top -----
        final StackPane viewHost = new StackPane(view, hero);
        StackPane.setAlignment(hero, Pos.TOP_CENTER);
        StackPane.setMargin(hero, new Insets(100, 0, 0, 0));
        HBox.setHgrow(viewHost, Priority.ALWAYS);

        // ---- Live wiring ------------------------------------------------
        engine.titleProperty().addListener((o, ov, nv) ->
            stage.setTitle((nv == null || nv.isBlank())
                ? "skia-fx WebView demo" : nv + " — skia-fx WebView"));
        engine.locationProperty().addListener((o, ov, nv) -> {
            if (nv != null && !nv.isBlank()) urlBar.setText(nv);
        });

        final Worker<Void> loader = engine.getLoadWorker();
        loader.stateProperty().addListener((o, ov, nv) -> {
            switch (nv) {
                case RUNNING   -> { status.setText("Loading…");      progress.setVisible(true); }
                case SUCCEEDED -> { status.setText("Done");          progress.setVisible(false); }
                case FAILED    -> { status.setText("Failed to load"); progress.setVisible(false); }
                case CANCELLED -> { status.setText("Cancelled");      progress.setVisible(false); }
                default        -> { }
            }
        });
        progress.progressProperty().bind(loader.progressProperty());

        // Crash-recovery test wiring. Logs the pre-crash scroll (so you can verify
        // it's restored), then kills the engine. The WebView holds the last frame,
        // respawns, and restores the page at the same scroll/forms.
        crashBtn.setOnAction(e -> {
            status.setText("Killing engine — watch it recover…");
            try {
                Object y = engine.executeScript("Math.round(window.scrollY)");
                System.out.println("[recovery-test] scrollY before crash = " + y);
            } catch (Exception ignore) {
                // executeScript may already be unavailable mid-teardown
            }
            killEngineProcess();
        });

        // ---- Docked test panels (right): LoadWorker monitor + JS bridge ----
        final Region workerPanel = buildWorkerTestPanel(engine, loader);
        workerPanel.visibleProperty().bind(workerBtn.selectedProperty());
        workerPanel.managedProperty().bind(workerBtn.selectedProperty());

        final Region jsPanel = buildJsBridgePanel(engine);
        jsPanel.visibleProperty().bind(jsBtn.selectedProperty());
        jsPanel.managedProperty().bind(jsBtn.selectedProperty());

        final VBox sideDock = new VBox(8, workerPanel, jsPanel);
        final HBox center = new HBox(viewHost, sideDock);
        VBox.setVgrow(center, Priority.ALWAYS);

        final VBox root = new VBox(toolbar, center, footer);
        root.getStyleClass().add("app-root");

        // Back/forward reflect the engine-fed session history reactively. The
        // BackForwardList behind WebHistory is updated by HISTORY_STATE events
        // from the engine, so binding the buttons' disabled state to the history's
        // currentIndex + entry count keeps them correct without polling on every
        // load-state change. (Empty history: currentIndex == -1 → both disabled.)
        final WebHistory history = engine.getHistory();
        back.disableProperty().bind(history.currentIndexProperty().lessThanOrEqualTo(0));
        forward.disableProperty().bind(history.currentIndexProperty()
            .greaterThanOrEqualTo(Bindings.size(history.getEntries()).subtract(1)));

        // ---- New Blink-WebView features: context menu, tooltip, color &
        //      select pickers, downloads (all driven by the page) -----------
        wireBrowserFeatures(view, engine, status, stage);

        // Ad-block on by default (registers the interceptor on the engine's Network).
        setAdBlockEnabled(true, engine);

        final Scene scene = new Scene(root, 1180, 860);
        // Resolve via the system class loader (classpath), NOT
        // WebViewDemo.class.getResource("/..."): the sample runs as a named
        // JPMS module, and Gradle puts build/resources/main on the class path
        // (only build/classes/java/main, which has module-info, lands on the
        // module path). A module-scoped getResource would therefore return null.
        // Same pattern as CustomTitleBarDemo.
        var css = ClassLoader.getSystemResource("webview-demo.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        } else {
            System.err.println("[WebViewDemo] webview-demo.css not found on classpath");
        }
        stage.setTitle("Skia-fx WebView demo");
        stage.setMinWidth(720);
        stage.setMinHeight(520);
        stage.setScene(scene);
        stage.show();

        // Open on the self-demonstrating feature playground (type a URL or
        // "skia-fx" to return here). A -Dwebdemo.page=<url|path> override jumps
        // straight to a given page (used for print-preview / feature smoke runs).
        String startPage = System.getProperty("webdemo.page");
        if (startPage != null && !startPage.isBlank()) {
            urlBar.setText(startPage);
            engine.load(normalizeUrl(startPage));
        } else {
            urlBar.setText(HOME_TOKEN);
            loadFeatures(engine);
        }

        // Customize the WebView's single, reused context menu: it arrives populated
        // with the built-in defaults (Copy / Open Link / Back / Reload, contextual
        // per engine.getContextMenuContext()); we add app items. The WebView owns,
        // shows, and hides the menu. The "Block ads" check item toggles the blocker,
        // so the menu is testable here too (and exercises a CheckMenuItem).
        engine.setContextMenuCustomizer(menu -> {
            // Context-aware item: when text is selected, offer to search for it.
            // (The default menu already added Cut/Copy/Paste/Select All as applicable.)
            ContextMenuContext ctx = engine.getContextMenuContext();
            String sel = ctx.getSelectedText();
            if (!sel.isEmpty()) {
                String shown = sel.length() > 40 ? sel.substring(0, 40) + "…" : sel;
                MenuItem search = new MenuItem("Search Google for \"" + shown + "\"");
                search.setOnAction(a -> engine.load("https://www.google.com/search?q="
                    + URLEncoder.encode(sel, StandardCharsets.UTF_8)));
                menu.getItems().add(0, search);
                menu.getItems().add(1, new SeparatorMenuItem());
            }
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem homeItem = new MenuItem("Home — skia-fx");
            homeItem.setOnAction(a -> loadFeatures(engine));
            CheckMenuItem blockAds = new CheckMenuItem("Block ads");
            blockAds.setSelected(isAdBlockOn());
            blockAds.setOnAction(a -> setAdBlockEnabled(!isAdBlockOn(), engine));
            menu.getItems().addAll(homeItem, blockAds);
        });
    }

    /**
     * Loads the feature playground from a real {@code .html} file on the
     * classpath (resolved via the system class loader, like the CSS above) and
     * navigates to its {@code file:} URL — no inline {@code loadContent}.
     */
    private static void loadFeatures(WebEngine engine) {
        var url = ClassLoader.getSystemResource(FEATURES_RESOURCE);
        if (url != null) {
            engine.load(url.toExternalForm());
        } else {
            System.err.println("[WebViewDemo] " + FEATURES_RESOURCE
                + " not found on classpath");
        }
    }

    /**
     * Wires the page-driven WebView features that still need application code.
     * The {@code <select>} drop-down and {@code <input type=color>} chooser are
     * now rendered natively by the engine (no handler needed). This wires:
     * <ul>
     *   <li><b>Tooltips</b> — the page's {@code title} text via
     *       {@link WebEngine#toolTipTextProperty()}.</li>
     *   <li><b>Downloads</b> — a {@link FileChooser} save dialog.</li>
     * </ul>
     * (The context menu is wired in {@code start()} via the standard
     * {@code setOnContextMenuRequested}.)
     */
    private void wireBrowserFeatures(WebView view, WebEngine engine,
                                     Label status, Stage stage) {
        // --- Page tooltips (title attributes) ------------------------------
        final Tooltip pageTip = new Tooltip();
        engine.toolTipTextProperty().addListener((o, ov, nv) -> {
            if (nv == null || nv.isBlank()) {
                Tooltip.uninstall(view, pageTip);
            } else {
                pageTip.setText(nv);
                Tooltip.install(view, pageTip);
            }
        });

        // --- Downloads → FileChooser ---------------------------------------
        engine.setDownloadHandler((DownloadRequest req) -> {
            FileChooser fc = new FileChooser();
            if (req.getSuggestedFileName() != null
                    && !req.getSuggestedFileName().isBlank()) {
                fc.setInitialFileName(req.getSuggestedFileName());
            }
            File target = fc.showSaveDialog(stage);
            if (target != null) {
                req.accept(target);
                status.setText("Downloading " + target.getName() + " …");
            } else {
                req.deny();
            }
        });
    }

    /**
     * Test affordance: forcibly kills the Blink engine process(es) so the
     * WebView's crash recovery runs (the watchdog notices the dead heartbeat,
     * WebPage respawns a fresh engine, and the session is restored). Kills by
     * image name — fine for the single-WebView demo.
     */
    private static void killEngineProcess() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            ProcessBuilder pb = os.contains("win")
                ? new ProcessBuilder("taskkill", "/F", "/IM", "skia-fx-webview.exe")
                : new ProcessBuilder("sh", "-c", "pkill -f skia-fx-webview");
            pb.redirectErrorStream(true);
            pb.start();
        } catch (Exception ex) {
            System.err.println("[recovery-test] failed to kill engine: " + ex);
        }
    }

    /** Whether the ad/tracker blocker is currently registered. */
    private boolean isAdBlockOn() {
        return adBlockSub != null;
    }

    /**
     * Turns the ad/tracker blocker on or off and keeps the toggle button in sync,
     * whether driven from the toolbar button or the context menu. Registering on
     * the engine's {@link javafx.scene.web.Network} arms interception for the
     * union of all filters; {@link Subscription#unsubscribe()} removes it (and
     * disarms once nothing is left).
     */
    private void setAdBlockEnabled(boolean on, WebEngine engine) {
        if (on && adBlockSub == null) {
            NetworkFilter.Builder b = NetworkFilter.builder();
            for (String p : AD_PATTERNS) {
                b.includeUrlPattern(p);
            }
            adBlockSub = engine.getNetwork().add(b.build(), new NetworkInterceptor() {
                @Override public void onRequest(NetworkExchange ex) {
                    adsBlocked++;
                    // Callbacks run on the FX thread, so updating the button is safe.
                    System.out.println("[ad-block] blocked " + ex.request().url());
                    if (adBlockBtn != null) {
                        adBlockBtn.setText("Ad-block: " + adsBlocked);
                    }
                    ex.block();
                }
            });
        } else if (!on && adBlockSub != null) {
            adBlockSub.unsubscribe();
            adBlockSub = null;
        }
        if (adBlockBtn != null) {
            adBlockBtn.setSelected(on);
            adBlockBtn.setText(!on ? "Ad-block: off"
                : adsBlocked == 0 ? "Ad-block: on" : "Ad-block: " + adsBlocked);
        }
    }

    // =====================================================================
    //  Java↔JS bridge (netscape.javascript.JSObject) tester
    // =====================================================================

    /** Sink for the JS-bridge panel log (also used by the Java-from-JS bridge). */
    private TextArea jsLog;

    /**
     * A plain Java object exposed to JavaScript via {@code window.jfx} for the
     * Java-from-JS test. When the page calls {@code window.jfx.fromJs(x)} the
     * engine invokes this method on the FX thread (slice B). Public so the
     * bridge's reflection can reach it across modules.
     */
    public final class JsBridge {
        public void fromJs(String message) {
            if (jsLog != null) {
                jsLog.appendText("  ◀ Java received from JS: " + message + "\n");
            }
        }

        /**
         * Returns a value to JavaScript. The host-proxy call returns a Promise,
         * so the page does {@code await jfx.addNumbers(3, 4)} (or
         * {@code .then(...)}) to receive this result.
         */
        public int addNumbers(int a, int b) {
            int sum = a + b;
            if (jsLog != null) {
                jsLog.appendText("  ◀ Java addNumbers(" + a + ", " + b
                    + ") → returning " + sum + "\n");
            }
            return sum;
        }
    }

    /**
     * A functional interface exposed to JavaScript so the page can hand a value
     * — typically a live JS object — straight to Java by calling it as a
     * function: {@code window.onData = <lambda>; onData({…})}. The host proxy
     * built for a Java object is callable, so JS invokes the single abstract
     * method (slice B). Public, with a public SAM, so the bridge's reflection
     * can reach it across modules.
     */
    @FunctionalInterface
    public interface JsDataReceiver {
        void receive(Object value);
    }

    /**
     * Builds the JS-bridge tester: an expression box plus preset buttons that
     * exercise typed {@code executeScript} results and {@link JSObject}
     * get/call/eval, and expose a Java object back to JS. Results (and their
     * Java types) stream into the log.
     */
    private Region buildJsBridgePanel(WebEngine engine) {
        final Label heading = new Label("JS bridge");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        jsLog = new TextArea();
        jsLog.setEditable(false);
        jsLog.setWrapText(true);
        jsLog.setPrefRowCount(16);
        jsLog.setStyle("-fx-font-family: 'Consolas','Menlo','monospace'; -fx-font-size: 11px;");
        VBox.setVgrow(jsLog, Priority.ALWAYS);

        final TextField jsInput = new TextField("1 + 1");
        final Button run = new Button("Run");
        final Runnable runExpr = () -> {
            String expr = jsInput.getText();
            try {
                jsLog.appendText("▶ " + expr + "  ⇒  "
                    + describe(engine.executeScript(expr)) + "\n");
            } catch (Exception ex) {
                jsLog.appendText("▶ " + expr + "  ✗ " + ex.getMessage() + "\n");
            }
        };
        run.setOnAction(e -> runExpr.run());
        jsInput.setOnAction(e -> runExpr.run());
        final HBox runRow = new HBox(6, jsInput, run);
        HBox.setHgrow(jsInput, Priority.ALWAYS);

        // Typed-result coverage: every JS type → its Java mapping.
        final Button typed = new Button("Typed results");
        typed.setOnAction(e -> {
            for (String s : new String[] { "1 + 1", "2.5", "true", "'hello'",
                    "null", "[1,2,3]", "({a:1,b:2})", "document.title" }) {
                try {
                    jsLog.appendText("  " + s + "  ⇒  "
                        + describe(engine.executeScript(s)) + "\n");
                } catch (Exception ex) {
                    jsLog.appendText("  " + s + "  ✗ " + ex.getMessage() + "\n");
                }
            }
        });

        // JSObject navigation: get a live object, read a member, eval, call.
        final Button objectOps = new Button("JSObject get / eval / call");
        objectOps.setOnAction(e -> {
            try {
                Object win = engine.executeScript("window");
                jsLog.appendText("  window  ⇒  " + describe(win) + "\n");
                if (win instanceof JSObject w) {
                    jsLog.appendText("  .getMember(location)  ⇒  "
                        + describe(w.getMember("location")) + "\n");
                    jsLog.appendText("  .eval(document.title)  ⇒  "
                        + describe(w.eval("document.title")) + "\n");
                    // Call a JS function with args + typed return. NOTE: never
                    // call a JS function that re-enters the FX thread (e.g.
                    // alert()/confirm()) from a synchronous JSObject.call — it
                    // deadlocks (call blocks the FX thread; the dialog needs it).
                    engine.executeScript(
                        "window.__demoAdd = function(a, b) { return a + b; };");
                    jsLog.appendText("  .call(__demoAdd, 3, 4)  ⇒  "
                        + describe(w.call("__demoAdd", 3, 4)) + "\n");
                }
            } catch (Exception ex) {
                jsLog.appendText("  ✗ " + ex.getMessage() + "\n");
            }
        });

        // Java-from-JS (slice B): expose a Java object, then have JS call it.
        final Button expose = new Button("Expose Java → JS, call it");
        expose.setOnAction(e -> {
            try {
                Object win = engine.executeScript("window");
                if (win instanceof JSObject w) {
                    w.setMember("jfx", new JsBridge());
                    jsLog.appendText("  window.jfx = <Java JsBridge>\n");
                    engine.executeScript("jfx.fromJs('called at ' + Date.now())");
                    jsLog.appendText("  ran jfx.fromJs(…) — a ◀ line means"
                        + " Java-from-JS works\n");
                }
            } catch (Exception ex) {
                jsLog.appendText("  ✗ " + ex.getMessage() + "\n");
            }
        });

        // Java-from-JS via a *functional interface*: JS calls the exposed Java
        // lambda directly and hands it a JS object, which Java reads back.
        final Button functional = new Button("Java functional interface ← JS object");
        functional.setOnAction(e -> {
            try {
                Object win = engine.executeScript("window");
                if (win instanceof JSObject w) {
                    w.setMember("onData", (JsDataReceiver) value -> {
                        // Runs on the FX thread. `value` is whatever JS passed —
                        // a JSObject for an object literal; read it back here.
                        String shown;
                        if (value instanceof JSObject obj) {
                            shown = "JSObject{name=" + obj.getMember("name")
                                + ", n=" + obj.getMember("n") + "}";
                        } else {
                            shown = describe(value);
                        }
                        jsLog.appendText("  ◀ Java functional got: " + shown + "\n");
                    });
                    jsLog.appendText("  window.onData = <Java lambda>\n");
                    engine.executeScript("onData({ name: 'hello', n: 7 })");
                    jsLog.appendText("  ran onData({…}) — a ◀ line means JS→Java"
                        + " (passing the JS object) works\n");
                }
            } catch (Exception ex) {
                jsLog.appendText("  ✗ " + ex.getMessage() + "\n");
            }
        });

        // Java return value → JS: the page awaits a Java method's result
        // (the host-proxy call returns a Promise settled with the return value).
        final Button awaitReturn = new Button("Await Java return value in JS");
        awaitReturn.setOnAction(e -> {
            try {
                Object win = engine.executeScript("window");
                if (win instanceof JSObject w) {
                    w.setMember("jfx", new JsBridge());
                    // A Java callback JS hands the awaited result back to, so the
                    // round-trip is visible in the log.
                    w.setMember("onResult", (JsDataReceiver) v ->
                        jsLog.appendText("  ◀ JS awaited Java return: " + describe(v) + "\n"));
                    jsLog.appendText("  window.jfx = <Java>, window.onResult = <Java lambda>\n");
                    // jfx.addNumbers(...) returns a Promise; await it, then hand
                    // the value back to Java via onResult.
                    engine.executeScript(
                        "(async () => { const s = await jfx.addNumbers(3, 4);"
                        + " onResult('addNumbers(3,4) = ' + s); })()");
                    jsLog.appendText("  ran await jfx.addNumbers(3,4) — two ◀ lines"
                        + " means Java→JS return values work\n");
                }
            } catch (Exception ex) {
                jsLog.appendText("  ✗ " + ex.getMessage() + "\n");
            }
        });

        final Button clear = new Button("Clear log");
        clear.setOnAction(e -> jsLog.clear());

        for (Button b : new Button[] { typed, objectOps, expose, functional, awaitReturn, clear }) {
            b.setMaxWidth(Double.MAX_VALUE);
            b.setFocusTraversable(false);
        }

        final VBox buttons = new VBox(6, typed, objectOps, expose, functional, awaitReturn, clear);
        final VBox panel = new VBox(8, heading, runRow, buttons, jsLog);
        panel.setPrefWidth(330);
        panel.setMinWidth(290);
        panel.setPadding(new Insets(10));
        panel.setStyle("-fx-background-color: white; -fx-border-color: #d0d0d0; "
            + "-fx-border-width: 0 0 0 1;");
        return panel;
    }

    /** Formats a JS result as {@code value (JavaType)} for the log. */
    private static String describe(Object v) {
        if (v == null) {
            return "null";
        }
        String val = v.toString();
        if (val.length() > 60) {
            val = val.substring(0, 60) + "…";
        }
        return val + "  (" + v.getClass().getSimpleName() + ")";
    }

    // =====================================================================
    //  LoadWorker monitor / notification test
    // =====================================================================

    /**
     * Builds the LoadWorker monitor: a docked panel that records EVERY
     * {@link Worker.State} transition (with relative timestamp, message,
     * progress and any exception) and offers three one-click tests that each
     * drive the worker to a distinct terminal state — SUCCEEDED, FAILED and
     * CANCELLED — so you can confirm the worker always notifies. A lightweight
     * validator flags anomalies (a terminal reached with no preceding RUNNING).
     */
    private Region buildWorkerTestPanel(WebEngine engine, Worker<Void> loader) {
        workerT0 = System.nanoTime();

        final Label heading = new Label("LoadWorker monitor");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        workerStateLabel = new Label("state: " + loader.getState());
        workerStateLabel.setStyle("-fx-text-fill: #444;");

        workerLog = new TextArea();
        workerLog.setEditable(false);
        workerLog.setWrapText(true);
        workerLog.setPrefRowCount(18);
        workerLog.setStyle("-fx-font-family: 'Consolas','Menlo','monospace'; -fx-font-size: 11px;");
        VBox.setVgrow(workerLog, Priority.ALWAYS);

        final Button testOk     = new Button("Test → SUCCEEDED");
        final Button testFail   = new Button("Test → FAILED");
        final Button testCancel = new Button("Test → CANCELLED");
        final Button clear      = new Button("Clear log");
        for (Button b : new Button[] { testOk, testFail, testCancel, clear }) {
            b.setMaxWidth(Double.MAX_VALUE);
            b.setFocusTraversable(false);
        }

        testOk.setOnAction(e -> {
            beginTest("SUCCEEDED — load local feature page", Worker.State.SUCCEEDED, false);
            loadFeatures(engine);
        });
        testFail.setOnAction(e -> {
            // The reserved *.invalid TLD never resolves → UNKNOWN_HOST →
            // LOAD_FAILED → FAILED, with no live network required.
            beginTest("FAILED — unresolvable host (*.invalid)", Worker.State.FAILED, false);
            engine.load("https://skia-fx-does-not-exist.invalid/");
        });
        testCancel.setOnAction(e -> {
            // Cancel as soon as the load reaches RUNNING. Needs an in-flight
            // (typically remote) load to have a RUNNING window to cancel in;
            // offline this may report FAILED before the cancel lands.
            beginTest("CANCELLED — cancel on first RUNNING", Worker.State.CANCELLED, true);
            engine.load(HOME_URL);
        });
        clear.setOnAction(e -> workerLog.clear());

        // Record the starting state, then every subsequent transition. A
        // dedicated listener (independent of the footer's) keeps the test
        // self-contained.
        workerLogLine(loader.getState(), loader, "(initial)");
        loader.stateProperty().addListener((o, ov, nv) -> {
            workerStateLabel.setText("state: " + nv);
            workerLogLine(nv, loader, null);

            if (nv == Worker.State.RUNNING) {
                runningSeenThisTest = true;
                if (cancelOnRunning) {
                    cancelOnRunning = false;
                    // Defer out of the change notification to avoid mutating the
                    // worker's state re-entrantly from inside its own listener.
                    Platform.runLater(loader::cancel);
                }
            }

            if (isTerminal(nv) && expectedTerminal != null) {
                boolean match = (nv == expectedTerminal);
                String verdict = match ? "PASS" : "MISMATCH (expected " + expectedTerminal + ")";
                // CANCELLED legitimately skips RUNNING when cancelled very early;
                // for the others, a terminal with no RUNNING is suspicious.
                if (match && !runningSeenThisTest && nv != Worker.State.CANCELLED) {
                    verdict += " — WARNING: terminal without a preceding RUNNING";
                }
                append("  └─ " + verdict);
                expectedTerminal = null;
            }
        });

        final VBox buttons = new VBox(6, testOk, testFail, testCancel, clear);
        final VBox panel = new VBox(8, heading, workerStateLabel, buttons, workerLog);
        panel.setPrefWidth(330);
        panel.setMinWidth(290);
        panel.setPadding(new Insets(10));
        panel.setStyle("-fx-background-color: white; -fx-border-color: #d0d0d0; "
            + "-fx-border-width: 0 0 0 1;");
        return panel;
    }

    /** Arms the validator for a test that expects to end in {@code expected}. */
    private void beginTest(String name, Worker.State expected, boolean cancelOnRun) {
        this.expectedTerminal = expected;
        this.runningSeenThisTest = false;
        this.cancelOnRunning = cancelOnRun;
        append("▶ " + name);
    }

    private static boolean isTerminal(Worker.State s) {
        return s == Worker.State.SUCCEEDED || s == Worker.State.FAILED
            || s == Worker.State.CANCELLED;
    }

    /** Appends one timestamped transition line (state + message + progress + ex). */
    private void workerLogLine(Worker.State s, Worker<Void> loader, String note) {
        StringBuilder sb = new StringBuilder(s.toString());
        String msg = loader.getMessage();
        if (msg != null && !msg.isBlank()) sb.append("  \"").append(msg).append('"');
        double p = loader.getProgress();
        if (p >= 0) sb.append(String.format("  %.0f%%", p * 100));
        Throwable ex = loader.getException();
        if (s == Worker.State.FAILED && ex != null) sb.append("  ex=").append(ex.getMessage());
        if (note != null) sb.append("  ").append(note);
        append(sb.toString());
    }

    private void append(String line) {
        double ms = (System.nanoTime() - workerT0) / 1_000_000.0;
        workerLog.appendText(String.format("[%8.1f ms] %s%n", ms, line));
    }

    /** Flat circular icon button for the nav cluster (styled via {@code .nav-button}). */
    private static Button navButton(String svg, String tip) {
        Button b = iconButton(svg, "nav-button", "nav-icon", tip);
        b.setFocusTraversable(false);
        return b;
    }

    /** Builds a graphic-only {@link Button} whose icon is an {@link SVGPath}. */
    private static Button iconButton(String svg, String buttonClass,
                                     String iconClass, String tip) {
        SVGPath icon = new SVGPath();
        icon.setContent(svg);
        icon.getStyleClass().add(iconClass);
        Button b = new Button();
        b.setGraphic(icon);
        b.setPickOnBounds(true);
        b.getStyleClass().add(buttonClass);
        b.setTooltip(new Tooltip(tip));
        return b;
    }

    private static Text accent(String s) {
        Text t = new Text(s);
        t.getStyleClass().add("accent");
        return t;
    }

    private static Text plain(String s) {
        Text t = new Text(s);
        t.getStyleClass().add("plain");
        return t;
    }

    /** Adds a scheme to a bare host/path so the engine gets an absolute URL. */
    private static String normalizeUrl(String t) {
        if (t.contains("://") || t.startsWith("data:") || t.startsWith("file:")) {
            return t;
        }
        // Looks like a domain/path → assume https; otherwise treat as a search.
        if (t.contains(".") && !t.contains(" ")) {
            return "https://" + t;
        }
        return "https://www.google.com/search?q="
            + URLEncoder.encode(t, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
