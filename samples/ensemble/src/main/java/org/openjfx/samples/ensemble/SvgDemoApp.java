package org.openjfx.samples.ensemble;

import java.io.File;
import java.io.InputStream;

import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.image.SvgImage;
import javafx.scene.image.SvgImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * Showcase for {@link SvgImage} / {@link SvgImageView}: an SVG rendered through
 * Skia and kept <b>pixel-perfect at any zoom level and DPI</b> (vector, drawn
 * straight onto the surface — no upscaled raster). Demonstrates zoom-to-cursor
 * (mouse wheel), drag-to-pan, the styleable grid backdrop, node-level tint,
 * background fill, and the same SVG reused as crisp button icons. Loads
 * {@code /test.svg} by default; use <b>Open SVG…</b> to load any file.
 *
 * <p>Run: {@code ./gradlew :samples:ensemble:runSvgDemo}</p>
 */
public class SvgDemoApp extends Application<Stage> {

    private static final String CRAZY_SVG = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 300">
          <defs>
            <radialGradient id="sky" cx="50%" cy="35%" r="75%">
              <stop offset="0%" stop-color="#1b2a4a"/><stop offset="100%" stop-color="#0a0e1a"/>
            </radialGradient>
            <linearGradient id="metal" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stop-color="#ff6ec7"/><stop offset="50%" stop-color="#7873f5"/>
              <stop offset="100%" stop-color="#4ade80"/>
            </linearGradient>
          </defs>
          <rect width="400" height="300" fill="url(#sky)"/>
          <polygon transform="translate(110,150) rotate(-18)"
            points="0,-60 17,-18 62,-18 26,8 39,52 0,26 -39,52 -26,8 -62,-18 -17,-18"
            fill="url(#metal)" stroke="#fff" stroke-width="2"/>
          <text x="210" y="100" font-family="Arial" font-size="22" font-weight="700"
            fill="url(#metal)">Skia SVG</text>
        </svg>
        """;

    /** Loads {@code /test.svg} from the classpath; falls back to the inline SVG. */
    private static SvgImage loadSvg() {
        try (InputStream in = SvgDemoApp.class.getResourceAsStream("/test.svg")) {
            if (in != null) {
                return new SvgImage(in);
            }
        } catch (Exception ignore) {
            // fall through to the bundled inline SVG
        }
        return SvgImage.ofContent(CRAZY_SVG);
    }

    private SvgImageView view;
    private Pane viewport;
    private boolean centered;

    @Override
    public void start(Stage stage) {
        view = new SvgImageView(loadSvg());
        view.setFitWidth(420);
        view.setPreserveRatio(true);
        view.setMinZoom(0.1);
        view.setMaxZoom(200);          // deep zoom — stays vector-sharp
        view.setManaged(false);        // we position it ourselves via translate

        viewport = new Pane(view);
        viewport.setStyle("-fx-background-color: #11151c;");
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(viewport.widthProperty());
        clip.heightProperty().bind(viewport.heightProperty());
        viewport.setClip(clip);

        // Center the artwork once the viewport has a size.
        viewport.widthProperty().addListener((o, a, b) -> centerIfNeeded());
        viewport.heightProperty().addListener((o, a, b) -> centerIfNeeded());

        // Zoom toward the mouse pointer (the point under the cursor stays fixed).
        viewport.setOnScroll(e -> {
            double step = e.getDeltaY() > 0 ? 1.15 : 1 / 1.15;
            zoomAround(view.getEffectiveZoom() * step, e.getX(), e.getY());
            e.consume();
        });
        // Drag to pan.
        final double[] last = new double[2];
        viewport.setOnMousePressed(e -> { last[0] = e.getX(); last[1] = e.getY(); });
        viewport.setOnMouseDragged(e -> {
            view.setTranslateX(view.getTranslateX() + e.getX() - last[0]);
            view.setTranslateY(view.getTranslateY() + e.getY() - last[1]);
            last[0] = e.getX(); last[1] = e.getY();
        });

        stage.setScene(new Scene(new BorderPane(viewport, topBar(), controls(), null, null), 1100, 720));
        stage.setTitle("SvgImageView — pixel-perfect vector at any zoom / DPI");
        stage.show();
    }

    /** Sets zoom while keeping the content point under (mx,my) fixed in the viewport. */
    private void zoomAround(double newZoom, double mx, double my) {
        double z0 = view.getEffectiveZoom();
        // Clamp before setting so the zoom property can't run away past the
        // bounds when the wheel keeps spinning at the min/max.
        newZoom = Math.max(view.getMinZoom(), Math.min(view.getMaxZoom(), newZoom));
        double px = (mx - view.getTranslateX()) / z0;   // content point, base coords
        double py = (my - view.getTranslateY()) / z0;
        view.setZoom(newZoom);
        double z1 = view.getEffectiveZoom();
        view.setTranslateX(mx - z1 * px);
        view.setTranslateY(my - z1 * py);
    }

    private void centerIfNeeded() {
        if (centered || viewport.getWidth() <= 0 || view.getBoundsInLocal().getWidth() <= 0) {
            return;
        }
        view.setTranslateX((viewport.getWidth() - view.getBoundsInLocal().getWidth()) / 2);
        view.setTranslateY((viewport.getHeight() - view.getBoundsInLocal().getHeight()) / 2);
        centered = true;
    }

    private void zoomToCenter(double newZoom) {
        zoomAround(newZoom, viewport.getWidth() / 2, viewport.getHeight() / 2);
    }

    /** Right-hand control panel: zoom, grid, tint, background. */
    private VBox controls() {
        Label zoomLabel = new Label();
        zoomLabel.textProperty().bind(view.zoomProperty().multiply(100).asString("Zoom: %.0f%%"));
        zoomLabel.setStyle("-fx-text-fill:#cdd9ec; -fx-font-size:14;");
        Label hint = new Label("Mouse wheel = zoom to cursor · drag = pan");
        hint.setStyle("-fx-text-fill:#6f819c;");
        hint.setWrapText(true);

        HBox zoomBtns = new HBox(6,
            btn("–", () -> zoomToCenter(view.getEffectiveZoom() / 1.4)),
            btn("Reset", () -> { centered = false; view.resetZoom(); centerIfNeeded(); }),
            btn("+", () -> zoomToCenter(view.getEffectiveZoom() * 1.4)));

        CheckBox grid = new CheckBox("Grid overlay");
        view.gridVisibleProperty().bind(grid.selectedProperty());
        ColorPicker gridColor = new ColorPicker((Color) view.getGridColor());
        view.gridColorProperty().bind(gridColor.valueProperty());
        Slider gridSpacing = new Slider(4, 64, view.getGridSpacing());
        view.gridSpacingProperty().bind(gridSpacing.valueProperty());
        Slider gridWidth = new Slider(0.5, 4, view.getGridLineWidth());
        view.gridLineWidthProperty().bind(gridWidth.valueProperty());

        CheckBox tintOn = new CheckBox("Tint output");
        ColorPicker tintColor = new ColorPicker(Color.web("#4a90d9"));
        ComboBox<SvgImageView.TintMode> tintMode = new ComboBox<>();
        tintMode.getItems().setAll(SvgImageView.TintMode.values());
        tintMode.setValue(SvgImageView.TintMode.SRC_IN);
        view.tintProperty().bind(Bindings.when(tintOn.selectedProperty())
            .then(tintColor.valueProperty()).otherwise((Color) null));
        view.tintModeProperty().bind(tintMode.valueProperty());

        CheckBox bgOn = new CheckBox("Background fill");
        ColorPicker bgColor = new ColorPicker(Color.web("#1e2633"));
        view.backgroundColorProperty().bind(Bindings.when(bgOn.selectedProperty())
            .then(bgColor.valueProperty()).otherwise((Color) null));

        VBox box = new VBox(8,
            section("Zoom"), zoomLabel, hint, zoomBtns,
            new Separator(),
            section("Grid"), grid, row("Color", gridColor), row("Spacing", gridSpacing), row("Width", gridWidth),
            new Separator(),
            section("Tint"), tintOn, row("Color", tintColor), row("Mode", tintMode),
            new Separator(),
            section("Background"), bgOn, row("Color", bgColor));
        box.setPadding(new Insets(14));
        box.setPrefWidth(290);
        box.setStyle("-fx-background-color: #161b24;");
        return box;
    }

    /** Top bar: open any SVG file, plus the same SVG reused as crisp icons. */
    private HBox topBar() {
        HBox bar = new HBox(10);
        bar.setPadding(new Insets(10));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color: #0d1017;");

        Button open = new Button("Open SVG…");
        Label status = new Label();
        status.setStyle("-fx-text-fill:#9fb3d1;");
        open.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Open an SVG file");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("SVG files", "*.svg"));
            File file = fc.showOpenDialog(open.getScene().getWindow());
            if (file == null) {
                return;
            }
            SvgImage svg = new SvgImage(file.toURI().toString());
            if (svg.isError()) {
                status.setText("Could not load: " + file.getName());
            } else {
                view.setSvgImage(svg);
                centered = false;
                view.resetZoom();
                centerIfNeeded();
                status.setText(file.getName() + "  ("
                        + (int) svg.getWidth() + "×" + (int) svg.getHeight() + ")");
            }
        });

        bar.getChildren().addAll(open, status, new Separator(Orientation.VERTICAL),
            new Label("Reused as crisp icons:  "));
        for (int px : new int[] {16, 24, 32, 48}) {
            SvgImageView icon = new SvgImageView(loadSvg());
            icon.setFitWidth(px);
            icon.setPreserveRatio(true);
            bar.getChildren().add(new Button(px + "px", icon));
        }
        return bar;
    }

    private static Label section(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-text-fill:#9fb3d1; -fx-font-weight:bold;");
        return l;
    }

    private static HBox row(String label, Node control) {
        Label l = new Label(label);
        l.setMinWidth(64);
        l.setStyle("-fx-text-fill:#cdd9ec;");
        HBox h = new HBox(8, l, control);
        h.setAlignment(Pos.CENTER_LEFT);
        if (control instanceof Slider s) { HBox.setHgrow(s, Priority.ALWAYS); s.setPrefWidth(150); }
        return h;
    }

    private static Button btn(String text, Runnable action) {
        Button b = new Button(text);
        b.setOnAction(e -> action.run());
        return b;
    }

    public static void main(String[] args) {
        Application.launch(args);
    }
}
