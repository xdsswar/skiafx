/*
 * Model3DDemo — glTF 2.0 model loading showcase for javafx.scene3d.
 *
 * Loads an external 3D model with a single public-API call
 * (javafx.scene.model3d.ModelLoader.load) and drops the returned Node into a
 * SubScene rendered by the Skia/bgfx 3D pipeline — the loader produces a
 * standard JavaFX scene-graph subtree (Group → MeshView → TriangleMesh +
 * PhongMaterial), so nothing here knows it came from a file. The model auto-
 * rotates and can be orbited (drag) / zoomed (scroll).
 *
 * Run:
 *   ./gradlew :samples:ensemble:runModelDemo                 (default: Duck.glb)
 *   ./gradlew :samples:ensemble:runModelDemo -Dmodel.file=C:/path/to/model.glb
 *   ./gradlew :samples:ensemble:runModelDemo -Pmodel.selftest=true   (headless asserts)
 */
package org.openjfx.samples.ensemble;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import com.sun.prism.skia.SkiaPresentable;

import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.model3d.Model3D;
import javafx.scene.model3d.ModelLoader;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import javafx.stage.Stage;

public final class Model3DDemo extends Application<Stage> {

    /** Required no-arg constructor for reflective {@code Application.launch}. */
    public Model3DDemo() {}

    private static final String[] SELFTEST_MODELS = {
        "models/Box.glb", "models/BoxTextured.glb", "models/Duck.glb",
    };
    private static final String DEFAULT_MODEL = "models/Duck.glb";

    // Marble PBR maps (Polyhaven marble_bust_01). The normal map ships as EXR,
    // which the JavaFX Image loader can't decode, so the demo uses the JPEG
    // diffuse (and treats the JPEG roughness as a specular hint).
    private static final String MARBLE_DIFFUSE = "textures/marable/textures/marble_bust_01_diff_4k.jpg";

    // Orbit state.
    private final Rotate orbitX = new Rotate(-20, Rotate.X_AXIS);
    private final Rotate orbitY = new Rotate(0, Rotate.Y_AXIS);
    private double anchorX, anchorY, anchorAngX, anchorAngY;
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private double camDistance = -600;

    @Override
    public void start(Stage stage) {
        if (Boolean.getBoolean("model.selftest")) {
            int code = runSelfTest();
            Platform.exit();
            // Give the toolkit a beat to unwind, then hard-set the exit code.
            Runtime.getRuntime().halt(code);
            return;
        }

        Model3D model;
        try {
            model = loadConfiguredModel();
        } catch (Exception ex) {
            stage.setTitle("Model3DDemo — load failed");
            Label err = new Label("Failed to load model:\n" + ex.getMessage());
            err.setStyle("-fx-text-fill: white; -fx-font-size: 14;");
            StackPane errRoot = new StackPane(err);
            errRoot.setStyle("-fx-background-color: #20242b;");
            stage.setScene(new Scene(errRoot, 720, 480));
            stage.show();
            return;
        }

        // Fit the loaded glTF model to a consistent on-screen size + spin, and
        // place it on the left.
        Rotate spin = new Rotate(0, Rotate.Y_AXIS);
        Group fitted = fitToView(model.getRoot(), spin);
        fitted.setTranslateX(-170);

        // Beside it, a sphere wearing the bundled marble texture — shows a
        // file-loaded PhongMaterial next to the glTF model.
        Rotate marbleSpin = new Rotate(0, Rotate.Y_AXIS);
        Sphere marble = new Sphere(120);
        marble.setMaterial(buildMarbleMaterial());
        marble.setTranslateX(180);
        marble.getTransforms().add(marbleSpin);

        Group world = new Group(fitted, marble);
        world.getTransforms().addAll(orbitX, orbitY);
        world.getChildren().add(new AmbientLight(Color.color(0.35, 0.35, 0.40)));
        PointLight key = new PointLight(Color.color(0.95, 0.95, 0.90));
        key.setTranslateX(300);
        key.setTranslateY(-400);
        key.setTranslateZ(-500);
        world.getChildren().add(key);

        camera.setNearClip(0.1);
        camera.setFarClip(10000);
        camera.setFieldOfView(40);
        camera.setTranslateZ(camDistance);

        SubScene sub = new SubScene(world, 960, 640, true, SceneAntialiasing.BALANCED);
        sub.setFill(Color.web("#20242b"));
        sub.setCamera(camera);
        sub.setAntiAliasing(false);

        StackPane root = new StackPane(sub);
        Label hud = new Label(describe(model));
        hud.setWrapText(true);
        hud.setStyle("-fx-text-fill: #d8dde6; -fx-background-color: rgba(0,0,0,0.45);"
            + " -fx-padding: 8 12; -fx-font-size: 12;");
        StackPane.setAlignment(hud, Pos.TOP_LEFT);
        StackPane.setMargin(hud, new Insets(12));
        root.getChildren().add(hud);

        // Window-present FPS (reflects VSync: capped near refresh when on,
        // uncapped when off). Read from SkiaPresentable.LAST_PRESENT_FPS.
        Label fpsLabel = new Label("— fps");
        fpsLabel.setStyle("-fx-text-fill: #b6f0c0; -fx-background-color: rgba(0,0,0,0.45);"
            + " -fx-padding: 8 12; -fx-font-size: 13; -fx-font-weight: bold;");
        StackPane.setAlignment(fpsLabel, Pos.TOP_RIGHT);
        StackPane.setMargin(fpsLabel, new Insets(12));
        root.getChildren().add(fpsLabel);

        sub.widthProperty().bind(root.widthProperty());
        sub.heightProperty().bind(root.heightProperty());

        installOrbit(sub);

        // Continuous spin, wall-clock driven (no fixed 60fps assumption).
        AnimationTimer timer = new AnimationTimer() {
            private long last = 0;
            @Override public void handle(long now) {
                if (last != 0) {
                    double dt = (now - last) / 1e9;
                    spin.setAngle(spin.getAngle() + dt * 30.0);       // 30°/s
                    marbleSpin.setAngle(marbleSpin.getAngle() + dt * 18.0);
                }
                last = now;
                fpsLabel.setText(String.format("%.0f fps", SkiaPresentable.LAST_PRESENT_FPS));
            }
        };
        timer.start();

        // Runtime VSync switch (skia-fx Application.vsyncEnabledProperty). Flip
        // the switch (or press V) to toggle: with VSync off the spinning scene
        // presents uncapped (watch the fps climb) and may tear; with it on the
        // present rate caps near the display refresh and the tearing stops. The
        // bidirectional bind keeps the switch and the V key in sync.
        CheckBox vsyncSwitch = new CheckBox("VSync");
        vsyncSwitch.setStyle("-fx-text-fill: #d8dde6; -fx-font-size: 13;"
            + " -fx-background-color: rgba(0,0,0,0.45); -fx-padding: 6 12;");
        vsyncSwitch.selectedProperty().bindBidirectional(Application.vsyncEnabledProperty());
        StackPane.setAlignment(vsyncSwitch, Pos.BOTTOM_LEFT);
        StackPane.setMargin(vsyncSwitch, new Insets(12));
        root.getChildren().add(vsyncSwitch);

        Scene scene = new Scene(root, 960, 640, true, SceneAntialiasing.BALANCED);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.V) {
                Application.setVsyncEnabled(!Application.isVsyncEnabled());
            }
        });
        stage.setTitle("Model3DDemo — javafx.scene3d glTF loader");
        stage.setScene(scene);
        stage.show();
    }

    /** Load the model named by {@code -Dmodel.file}, else the bundled default. */
    private Model3D loadConfiguredModel() throws Exception {
        String file = System.getProperty("model.file");
        if (file != null && !file.isBlank()) {
            return ModelLoader.loadModel(Path.of(file));
        }
        try (InputStream in = resource(DEFAULT_MODEL)) {
            return ModelLoader.loadModel(in);
        }
    }

    /** A PhongMaterial built from the bundled marble JPEG diffuse map. */
    private PhongMaterial buildMarbleMaterial() {
        PhongMaterial m = new PhongMaterial(Color.web("#d9d4c8"));
        try (InputStream in = resource(MARBLE_DIFFUSE)) {
            Image diff = new Image(in);
            if (!diff.isError()) {
                m.setDiffuseMap(diff);
            }
        } catch (Exception ex) {
            System.out.println("[Model3DDemo] marble texture unavailable: " + ex);
        }
        m.setSpecularColor(Color.web("#f2efe6"));
        m.setSpecularPower(48);
        return m;
    }

    /** Center the node at the origin and scale it to a consistent view size. */
    private static Group fitToView(Node node, Rotate spin) {
        Bounds b = node.getBoundsInParent();
        double maxDim = Math.max(b.getWidth(), Math.max(b.getHeight(), b.getDepth()));
        double scale = (maxDim > 0) ? 260.0 / maxDim : 1.0;
        double cx = b.getMinX() + b.getWidth() / 2.0;
        double cy = b.getMinY() + b.getHeight() / 2.0;
        double cz = b.getMinZ() + b.getDepth() / 2.0;

        Group g = new Group(node);
        // Applied innermost-to-outermost: center, then scale, then spin.
        g.getTransforms().addAll(spin, new Scale(scale, scale, scale), new Translate(-cx, -cy, -cz));
        return g;
    }

    private void installOrbit(SubScene sub) {
        sub.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            anchorX = e.getSceneX();
            anchorY = e.getSceneY();
            anchorAngX = orbitX.getAngle();
            anchorAngY = orbitY.getAngle();
        });
        sub.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            orbitY.setAngle(anchorAngY + (e.getSceneX() - anchorX) * 0.4);
            orbitX.setAngle(anchorAngX - (e.getSceneY() - anchorY) * 0.4);
        });
        sub.addEventHandler(ScrollEvent.SCROLL, e -> {
            camDistance += e.getDeltaY() * 0.8;
            camDistance = Math.max(-4000, Math.min(-60, camDistance));
            camera.setTranslateZ(camDistance);
        });
    }

    private static String describe(Model3D model) {
        int tris = triangleCount(model);
        String name = model.getName().isEmpty() ? "(unnamed)" : model.getName();
        return "ModelLoader.loadModel  •  \"" + name + "\"  •  "
            + model.getMeshViews().size() + " mesh"
            + (model.getMeshViews().size() == 1 ? "" : "es")
            + "  •  " + tris + " triangles    +  marble PhongMaterial\n"
            + "drag to orbit  •  scroll to zoom";
    }

    private static int triangleCount(Model3D model) {
        int tris = 0;
        for (MeshView mv : model.getMeshViews()) {
            if (mv.getMesh() instanceof TriangleMesh tm) {
                // faces holds vertexIndexSize ints per vertex, 3 vertices/triangle.
                int perTriangle = tm.getVertexFormat().getVertexIndexSize() * 3;
                if (perTriangle > 0) {
                    tris += tm.getFaces().size() / perTriangle;
                }
            }
        }
        return tris;
    }

    // ---- self-test (headless asserts; no window) ---------------------------

    private int runSelfTest() {
        System.out.println("[model.selftest] javafx.scene3d glTF loader");
        boolean ok = true;

        for (String res : SELFTEST_MODELS) {
            try (InputStream in = resource(res)) {
                Model3D m = ModelLoader.loadModel(in);
                int meshes = m.getMeshViews().size();
                int tris = triangleCount(m);
                boolean pass = meshes >= 1 && tris >= 1;
                ok &= pass;
                System.out.printf("[model.selftest]   %-18s name=\"%s\" meshes=%d triangles=%d -> %s%n",
                    res, m.getName(), meshes, tris, pass ? "PASS" : "FAIL");
            } catch (Exception ex) {
                ok = false;
                System.out.println("[model.selftest]   " + res + " -> FAIL (" + ex + ")");
            }
        }

        // Garbage bytes must fail cleanly (IOException), not crash.
        try {
            ModelLoader.load(new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
            System.out.println("[model.selftest]   garbage-bytes -> FAIL (expected IOException)");
            ok = false;
        } catch (java.io.IOException expected) {
            System.out.println("[model.selftest]   garbage-bytes -> PASS (rejected cleanly)");
        } catch (Exception other) {
            System.out.println("[model.selftest]   garbage-bytes -> FAIL (" + other + ")");
            ok = false;
        }

        // Leak/parity: load repeatedly; the native session is opened + closed
        // per load. A crash or a steady leak would show here.
        try {
            byte[] duck = resource(DEFAULT_MODEL).readAllBytes();
            for (int i = 0; i < 50; i++) {
                Node node = ModelLoader.load(new ByteArrayInputStream(duck));
                if (node == null) { ok = false; break; }
            }
            System.out.println("[model.selftest]   50x load loop -> PASS (no crash)");
        } catch (Exception ex) {
            System.out.println("[model.selftest]   50x load loop -> FAIL (" + ex + ")");
            ok = false;
        }

        System.out.println("[model.selftest] RESULT: " + (ok ? "PASS" : "FAIL"));
        return ok ? 0 : 1;
    }

    /**
     * Open a module-root resource. Under the split classes/resources Gradle
     * layout, {@code Class.getResourceAsStream} misses module-root resources
     * (and encapsulates packaged ones); the system class loader walks every
     * module-path entry. Mirrors ShowcaseApp's resolver.
     */
    private InputStream resource(String name) {
        java.net.URL u = ClassLoader.getSystemResource(name);
        if (u == null) {
            u = getClass().getClassLoader().getResource(name);
        }
        if (u == null) {
            u = getClass().getResource("/" + name);
        }
        if (u == null) {
            throw new IllegalStateException("bundled resource not found: " + name);
        }
        try {
            return u.openStream();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("failed to open resource: " + name, ex);
        }
    }
}
