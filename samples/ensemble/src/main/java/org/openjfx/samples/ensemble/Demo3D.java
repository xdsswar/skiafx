/*
 * Demo3D — a 3D capability showcase for the Skia pipeline.
 *
 * Exercises the JavaFX 3D scene-graph surface end-to-end against the
 * Skia-backed renderer:
 *
 *   - SubScene with a depth buffer + BALANCED scene antialiasing.
 *   - PerspectiveCamera (fixed-eye) with mouse-orbit + scroll-zoom.
 *   - Built-in primitives: Box, Sphere, Cylinder.
 *   - A hand-built TriangleMesh (parametric torus) to prove arbitrary
 *     geometry uploads + indexed faces + tex-coords render correctly.
 *   - PhongMaterial with diffuse + specular + self-illumination maps.
 *   - AmbientLight + two orbiting coloured PointLights.
 *   - DrawMode.FILL ⇄ DrawMode.LINE (wireframe) toggle.
 *   - Continuous animation (Rotate transforms driven per-frame) so the
 *     uncapped submit path is exercised under real 3D load.
 *
 * The whole thing lives inside a StageStyle.CUSTOM window — the demo
 * paints its own title bar and hands the OS the hit-region geometry
 * (drag-to-move, double-click-maximize, Aero snap, edge resize), with
 * a translucent 2D HUD composited over the live 3D SubScene.
 *
 * Run: ./gradlew :samples:ensemble:runDemo3D
 */
package org.openjfx.samples.ensemble;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.*;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;

import javafx.stage.Stage;
import javafx.stage.StageStyle;


public final class Demo3D extends Application<Stage> {

    /** Required no-arg constructor for reflective {@code Application.launch}. */
    public Demo3D() {}

    // Caption glyphs on a 0..10 grid, stroked (not filled).
    private static final String SVG_MIN     = "M0,5 H10";
    private static final String SVG_MAX     = "M0.5,0.5 H9.5 V9.5 H0.5 Z";
    private static final String SVG_RESTORE =
            "M0.5,2.5 H7.5 V9.5 H0.5 Z M2.5,2.5 V0.5 H9.5 V7.5 H7.5";
    private static final String SVG_CLOSE   = "M0,0 L10,10 M10,0 L0,10";

    // ---- Orbit / zoom state ------------------------------------------------
    private final Rotate worldRotX = new Rotate(-22, Rotate.X_AXIS);
    private final Rotate worldRotY = new Rotate(  0, Rotate.Y_AXIS);
    // Camera must stay farther from the origin than the largest geometry's
    // distance from it, or rotating geometry sweeps past the camera's near
    // plane and "explodes" across the view (correct perspective, ugly demo).
    // Floor below is 1500² (half-diagonal ~1060 + y offset ~360 => ~1120),
    // so any |cameraDistance| comfortably > ~1200 is safe.
    private double anchorX, anchorY, anchorRotX, anchorRotY;
    private double cameraDistance = -2000;

    // Toggle state.
    private boolean autoRotate = true;
    private boolean lightsOrbit = true;
    private boolean wireframe = false;
    private boolean antiAliasing = true;   // toggled at runtime with the 'A' key

    // The live 3D SubScene, captured so the 'A' key can toggle its anti-aliasing at
    // runtime via the public SubScene.setAntiAliasing(boolean) (skia-fx extension).
    private SubScene aaSubScene;

    // Animated nodes, captured for the per-frame timer.
    private Group spinners;       // self-rotating primitive ring
    private MeshView torus;       // central custom mesh
    private Group lightRig;       // orbiting coloured lights
    private final java.util.List<Shape3D> allShapes = new java.util.ArrayList<>();

    @Override
    public void start(Stage stage) {
        stage.initStyle(StageStyle.CUSTOM);
        stage.setTitle("Skia 3D Showcase");

        // The whole point of this demo is to test 3D on the Skia
        // pipeline. If the running pipeline does not advertise SCENE3D,
        // attempting to render a PerspectiveCamera + depth-buffered
        // SubScene produces a blank, unresponsive window (there is no
        // fallback pipeline in this fork). Detect it up-front and show
        // an honest capability readout instead of hanging.
        boolean scene3D = Platform.isSupported(ConditionalFeature.SCENE3D);

        Region viewport = scene3D ? build3DViewport() : buildUnsupportedPanel();

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");
        root.setTop(buildTitleBar(stage));
        root.setCenter(viewport);

        Scene scene = new Scene(root, 1100, 720);
        var css = ClassLoader.getSystemResource("demo3d.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        // Press 'A' to toggle 3D anti-aliasing at runtime via the public
        // SubScene.setAntiAliasing (SceneAntialiasing itself is constructor-only).
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.A && aaSubScene != null) {
                antiAliasing = !antiAliasing;
                aaSubScene.setAntiAliasing(antiAliasing);
            } else if (e.getCode() == KeyCode.W) {
                // Toggle wireframe (DrawMode.LINE) on every 3D shape.
                wireframe = !wireframe;
                DrawMode dm = wireframe ? DrawMode.LINE : DrawMode.FILL;
                for (Shape3D s : allShapes) s.setDrawMode(dm);
            } else if (e.getCode() == KeyCode.S) {
                // Snapshot the live 3D SubScene and report content coverage.
                snapshotAndReport(aaSubScene);
            }
        });

        stage.setScene(scene);
        stage.setMinWidth(640);
        stage.setMinHeight(440);
        stage.show();

        // Headless self-test hook: -Dskia.3d.selftest=true runs one snapshot
        // (snapshot + PixelReader readback verification) a few frames after show,
        // then exits. Used to verify the offscreen-RTT 3D path without a keypress.
        if (Boolean.getBoolean("skia.3d.selftest")) {
            scheduleSelfTest();
        }
    }

    /** Runs {@link #snapshotAndReport} ~90 frames after show, then exits. */
    private void scheduleSelfTest() {
        new AnimationTimer() {
            private int frames = 0;
            @Override public void handle(long now) {
                if (++frames < 90) {
                    return;
                }
                stop();
                System.out.println("[selftest] camera = "
                        + (Boolean.getBoolean("skia.3d.parallelcam") ? "parallel" : "perspective"));
                snapshotAndReport(aaSubScene);
                Platform.exit();
            }
        }.start();
    }

    // ====================================================================
    //  3D scene construction
    // ====================================================================

    /** Builds the live 3D SubScene with the 2D HUD composited over it. */
    private Region build3DViewport() {
        SubScene subScene = build3DSubScene();
        Region hud = buildHud();
        // Front overlay transparent so the 3D viewport is fully visible.
        hud.setOpacity(0);
        hud.setMouseTransparent(true);

        // SubScene is NOT a resizable node — it keeps a fixed width/height,
        // so we must (a) drive its size via a binding, and (b) stop its
        // size from flooring the parent's minimum (else the window grows
        // but won't shrink). We override the container's min size to 0 so
        // it can shrink, keep the SubScene MANAGED so it's laid out at the
        // origin, and CLIP to the container so the 3D can never overflow —
        // including on the very first frame before the binding settles.
        StackPane viewport = new StackPane(subScene, hud) {
            @Override protected double computeMinWidth(double h)  { return 0; }
            @Override protected double computeMinHeight(double w) { return 0; }
        };
        viewport.getStyleClass().add("viewport");
        subScene.widthProperty().bind(viewport.widthProperty());
        subScene.heightProperty().bind(viewport.heightProperty());

        // Always-visible real-time FPS readout (green) pinned to the top-right.
        Label fpsLabel = new Label("— fps");
        fpsLabel.setStyle("-fx-text-fill: #2bff6a; -fx-font-size: 22px; "
                + "-fx-font-weight: bold; -fx-font-family: 'Consolas','Courier New',monospace;");
        fpsLabel.setMouseTransparent(true);
        StackPane.setAlignment(fpsLabel, Pos.TOP_RIGHT);
        StackPane.setMargin(fpsLabel, new Insets(10, 14, 0, 0));
        startFpsMeter(fpsLabel);
        viewport.getChildren().add(fpsLabel);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(viewport.widthProperty());
        clip.heightProperty().bind(viewport.heightProperty());
        viewport.setClip(clip);
        return viewport;
    }

    private SubScene build3DSubScene() {
        Group world = new Group();
        world.getTransforms().addAll(worldRotY, worldRotX);

        // ---- Ground reference plane (a large, thin box) --------------
        //  Textured with the bundled rocky-terrain maps (diffuse + specular) to
        //  exercise the Skia/bgfx Phong texture path. The normal/roughness maps in
        //  the set are EXR (HDR) which the JavaFX image loader can't decode, so they
        //  are intentionally not applied (true PBR/normal is Door 2 — see docs/3D.md).
        Box floor = new Box(1500, 8, 1500);
        floor.setTranslateY(360);
        // Diffuse COLOR is white: the Phong shader computes diffuse = map × color
        // (same as stock JavaFX), so a dark color would multiply the texture down to
        // black. White lets the rocky diffuse map show at full brightness.
        PhongMaterial floorMat = new PhongMaterial(Color.WHITE);
        floorMat.setSpecularColor(Color.web("#1e3a5f"));
        withMaps(floorMat,
                loadImage("textures/textures/rocky_terrain_02_diff_4k.jpg"),
                loadImage("textures/textures/rocky_terrain_02_spec_4k.png"));
        floor.setMaterial(floorMat);
        allShapes.add(floor);

        // No central custom mesh in this scene (removed to test whether the
        // periodic glitch is tied to MeshView; this scene is built-ins only).
        torus = null;

        // ---- A clean row of distinct built-in primitives -------------
        //  cube · sphere · cylinder, each textured with the bundled "poplin" fabric
        //  maps (diffuse + specular). Their distinct tints multiply the texture so
        //  each reads as a differently-coloured textured body.
        spinners = new Group();
        Image poplinDiffuse  = loadImage("textures/other/textures/stretch_poplin_diff_4k.jpg");
        Image poplinSpecular = loadImage("textures/other/textures/stretch_poplin_spec_ior_4k.png");
        // A code-generated tangent-space normal map (the bundled sets' normals are EXR,
        // undecodable here) applied as the bump map on every primitive, so the
        // Skia/bgfx normal-mapping path is visibly exercised: the spinning shapes show
        // moving quilted highlights under the orbiting lights.
        Image normalMap = proceduralNormalMap(512, 8, 6.0);

        Box cube = new Box(280, 280, 280);
        cube.setMaterial(withMaps(phong("#f472b6", "#ffffff", 32), poplinDiffuse, poplinSpecular));
        ((PhongMaterial) cube.getMaterial()).setBumpMap(normalMap);
        cube.setTranslateX(-560);

        Sphere ball = new Sphere(170);
        ball.setMaterial(withMaps(phong("#60a5fa", "#dbeafe", 48), poplinDiffuse, poplinSpecular));
        ((PhongMaterial) ball.getMaterial()).setBumpMap(normalMap);

        Cylinder cyl = new Cylinder(130, 340);
        cyl.setMaterial(withMaps(phong("#34d399", "#d1fae5", 24), poplinDiffuse, poplinSpecular));
        ((PhongMaterial) cyl.getMaterial()).setBumpMap(normalMap);
        cyl.setTranslateX(560);

        for (Shape3D s : new Shape3D[] { cube, ball, cyl }) {
            spinners.getChildren().add(s);
            allShapes.add(s);
        }

        // ---- Lighting ------------------------------------------------
        AmbientLight ambient = new AmbientLight(Color.web("#1f2937"));

        lightRig = new Group();
        PointLight cyan = new PointLight(Color.web("#67e8f9"));
        cyan.setTranslateX(700); cyan.setTranslateY(-500); cyan.setTranslateZ(-300);
        PointLight magenta = new PointLight(Color.web("#f0abfc"));
        magenta.setTranslateX(-700); magenta.setTranslateY(-300); magenta.setTranslateZ(500);
        // A warm SpotLight aimed down at the row from above — exercises the
        // unified light model's cone + falloff + attenuation path (A3): a
        // tight inner/outer angle gives a visible pool of light on the floor.
        SpotLight spot = new SpotLight(Color.web("#fde68a"));
        spot.setTranslateY(-650);
        spot.setDirection(new javafx.geometry.Point3D(0, 1, 0)); // straight down
        spot.setInnerAngle(14);
        spot.setOuterAngle(34);
        spot.setFalloff(1.2);
        // Tiny emissive marker spheres so the light positions are visible.
        lightRig.getChildren().addAll(cyan, magenta, spot,
                lightMarker(cyan), lightMarker(magenta), lightMarker(spot));

        world.getChildren().addAll(floor, spinners, ambient, lightRig);

        // ---- Camera --------------------------------------------------
        // Default is a fixed-eye PerspectiveCamera. Setting -Dskia.3d.parallelcam=true
        // swaps in an orthographic ParallelCamera instead, which verifies the
        // non-perspective projViewTx path renders through the Skia/bgfx pipeline.
        SubScene sub;
        if (Boolean.getBoolean("skia.3d.parallelcam")) {
            // ParallelCamera maps the SubScene's pixel space directly (origin at
            // the top-left, no perspective foreshortening), so wrap the
            // origin-centred world in a group translated to the viewport centre
            // and scaled to fit.
            Group ortho = new Group(world);
            ortho.setTranslateX(550);
            ortho.setTranslateY(340);
            ortho.getTransforms().add(new Scale(0.45, 0.45, 0.45));

            ParallelCamera camera = new ParallelCamera();
            camera.setNearClip(0.1);
            camera.setFarClip(10000);

            sub = new SubScene(ortho, 1100, 680, true, SceneAntialiasing.BALANCED);
            sub.setFill(Color.web("#070b14"));
            sub.setCamera(camera);
            installOrbit(sub); // drag-to-rotate (ortho has no eye to dolly)
        } else {
            PerspectiveCamera camera = new PerspectiveCamera(true);
            camera.setNearClip(0.1);
            camera.setFarClip(10000);
            camera.setFieldOfView(45);
            camera.setTranslateZ(cameraDistance);

            sub = new SubScene(world, 1100, 680, true, SceneAntialiasing.BALANCED);
            sub.setFill(Color.web("#070b14"));
            sub.setCamera(camera);
            installCameraControls(sub, camera);
        }

        installPicking(sub);   // click a shape → log the PickResult
        startAnimation();
        aaSubScene = sub;   // captured for the runtime anti-aliasing toggle ('A' key)
        return sub;
    }

    /**
     * Loads a bundled texture by its resource path (under {@code resources/}) as a
     * JavaFX {@link Image}, or {@code null} if it isn't present / can't be decoded
     * (e.g. the EXR maps in the sets, which the image loader doesn't support).
     */
    private static Image loadImage(String resourcePath) {
        var url = ClassLoader.getSystemResource(resourcePath);
        return url == null ? null : new Image(url.toExternalForm());
    }

    /** Sets diffuse/specular maps on a material (ignoring nulls) and returns it. */
    private static PhongMaterial withMaps(PhongMaterial m, Image diffuse, Image specular) {
        if (diffuse != null)  m.setDiffuseMap(diffuse);
        if (specular != null) m.setSpecularMap(specular);
        return m;
    }

    /**
     * Generates a tangent-space normal map (a "quilted pillow" bump pattern) entirely
     * in code, so the Skia/bgfx normal-mapping path can be exercised without an asset
     * (the bundled texture sets ship their normals as EXR, which the JavaFX image
     * loader can't decode). Each texel encodes a perturbed surface normal: a flat
     * area is (0.5, 0.5, 1.0) = +Z; the bumps tilt it in X/Y. Applied as the
     * PhongMaterial bump map, it makes the spinning shapes show moving normal-mapped
     * highlights under the orbiting lights — visible proof the TBN + bump path works.
     */
    private static Image proceduralNormalMap(int size, double bumps, double strength) {
        WritableImage img = new WritableImage(size, size);
        PixelWriter pw = img.getPixelWriter();
        double k = 2.0 * Math.PI * bumps;
        for (int y = 0; y < size; y++) {
            double v = (double) y / size;
            for (int x = 0; x < size; x++) {
                double u = (double) x / size;
                // height = sin(u)·sin(v); the tangent-space normal is the (negated)
                // gradient of the height field, with +Z out of the surface.
                double dhdu = k * Math.cos(k * u) * Math.sin(k * v) * strength;
                double dhdv = k * Math.sin(k * u) * Math.cos(k * v) * strength;
                double nx = -dhdu, ny = -dhdv, nz = 1.0;
                double inv = 1.0 / Math.sqrt(nx * nx + ny * ny + nz * nz);
                int r = (int) Math.round((nx * inv * 0.5 + 0.5) * 255.0);
                int g = (int) Math.round((ny * inv * 0.5 + 0.5) * 255.0);
                int b = (int) Math.round((nz * inv * 0.5 + 0.5) * 255.0);
                pw.setArgb(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    /** A PhongMaterial with diffuse + specular colours and specular power. */
    private static PhongMaterial phong(String diffuse, String specular, double power) {
        PhongMaterial m = new PhongMaterial(Color.web(diffuse));
        m.setSpecularColor(Color.web(specular));
        m.setSpecularPower(power);
        return m;
    }

    /** A small bright sphere co-located with a PointLight to mark it. */
    private static Sphere lightMarker(PointLight light) {
        Sphere s = new Sphere(14);
        PhongMaterial m = new PhongMaterial(light.getColor());
        // The light's own colour drives a strong specular so the marker
        // reads as a glowing bulb rather than a flat dot.
        m.setSpecularColor(Color.WHITE);
        m.setSpecularPower(8);
        s.setMaterial(m);
        s.translateXProperty().bind(light.translateXProperty());
        s.translateYProperty().bind(light.translateYProperty());
        s.translateZProperty().bind(light.translateZProperty());
        return s;
    }

    /**
     * Builds a parametric torus as an explicit {@link TriangleMesh}
     * (POINT_TEXCOORD format): {@code ringDiv} segments around the main
     * ring, {@code tubeDiv} around the tube cross-section.
     */
    private static TriangleMesh torusMesh(float meanR, float tubeR,
                                          int ringDiv, int tubeDiv) {
        TriangleMesh mesh = new TriangleMesh(VertexFormat.POINT_TEXCOORD);

        float[] points = new float[ringDiv * tubeDiv * 3];
        float[] tex    = new float[ringDiv * tubeDiv * 2];
        int pi = 0, ti = 0;
        for (int i = 0; i < ringDiv; i++) {
            double phi = 2 * Math.PI * i / ringDiv;
            double cosPhi = Math.cos(phi), sinPhi = Math.sin(phi);
            for (int j = 0; j < tubeDiv; j++) {
                double theta = 2 * Math.PI * j / tubeDiv;
                double r = meanR + tubeR * Math.cos(theta);
                points[pi++] = (float) (r * cosPhi);
                points[pi++] = (float) (tubeR * Math.sin(theta));
                points[pi++] = (float) (r * sinPhi);
                tex[ti++] = (float) i / ringDiv;
                tex[ti++] = (float) j / tubeDiv;
            }
        }
        mesh.getPoints().setAll(points);
        mesh.getTexCoords().setAll(tex);

        int[] faces = new int[ringDiv * tubeDiv * 2 * 6];
        int fi = 0;
        for (int i = 0; i < ringDiv; i++) {
            int iN = (i + 1) % ringDiv;
            for (int j = 0; j < tubeDiv; j++) {
                int jN = (j + 1) % tubeDiv;
                int a = i * tubeDiv + j;
                int b = iN * tubeDiv + j;
                int c = iN * tubeDiv + jN;
                int d = i * tubeDiv + jN;
                // Quad (a,b,c,d) → two triangles. Point index == tex index.
                faces[fi++] = a; faces[fi++] = a;
                faces[fi++] = b; faces[fi++] = b;
                faces[fi++] = c; faces[fi++] = c;
                faces[fi++] = a; faces[fi++] = a;
                faces[fi++] = c; faces[fi++] = c;
                faces[fi++] = d; faces[fi++] = d;
            }
        }
        mesh.getFaces().setAll(faces);
        return mesh;
    }

    // ====================================================================
    //  Camera controls + animation
    // ====================================================================

    private void installCameraControls(SubScene sub, PerspectiveCamera camera) {
        installOrbit(sub);
        sub.setOnScroll(e -> {
            cameraDistance += e.getDeltaY() * 1.4;
            cameraDistance = Math.clamp(cameraDistance, -3600, -1300);
            camera.setTranslateZ(cameraDistance);
        });
    }

    /** Drag-to-rotate: shared by the perspective and parallel camera paths. */
    private void installOrbit(SubScene sub) {
        sub.setOnMousePressed(e -> {
            anchorX = e.getSceneX();
            anchorY = e.getSceneY();
            anchorRotX = worldRotX.getAngle();
            anchorRotY = worldRotY.getAngle();
        });
        sub.setOnMouseDragged(e -> {
            if (e.getButton() == MouseButton.SECONDARY) return;
            double dx = e.getSceneX() - anchorX;
            double dy = e.getSceneY() - anchorY;
            worldRotY.setAngle(anchorRotY + dx * 0.30);
            // Clamp pitch so the scene never flips past the poles.
            double pitch = anchorRotX - dy * 0.30;
            worldRotX.setAngle(Math.clamp(pitch, -89, 89));
        });
    }

    /**
     * Click-to-pick: JavaFX 3D picking is a pipeline-independent CPU ray↔triangle
     * test in the scene graph, so a click on the SubScene reports which Shape3D
     * was hit and where. Logs the {@link PickResult} to confirm picking works
     * against the Skia-rendered scene.
     */
    private void installPicking(SubScene sub) {
        sub.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            PickResult pick = e.getPickResult();
            Node hit = pick == null ? null : pick.getIntersectedNode();
            if (hit instanceof Shape3D shape) {
                System.out.printf("[pick] hit %s at distance %.1f, point %s%n",
                        shape.getClass().getSimpleName(),
                        pick.getIntersectedDistance(),
                        pick.getIntersectedPoint());
            } else {
                System.out.println("[pick] no shape under cursor (background)");
            }
        });
    }

    /**
     * Snapshots the live 3D SubScene to a {@link WritableImage} and samples it
     * through a {@link PixelReader}, reporting how much of the frame is non-fill
     * content. This exercises the offscreen-RTT path: the 3D pass renders into
     * the SubScene's own render target and is composited there before readback
     * (unlike the window path, which composites into the swapchain surface).
     */
    private void snapshotAndReport(SubScene sub) {
        if (sub == null) {
            return;
        }
        WritableImage img = sub.snapshot(new SnapshotParameters(), null);
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        PixelReader pr = img.getPixelReader();
        if (pr == null) {
            System.out.println("[snapshot] readback returned no PixelReader");
            return;
        }
        // The SubScene fill is #070b14. Count pixels that differ from it — i.e.
        // actual rendered 3D content (shapes, floor, lit areas).
        Color fill = Color.web("#070b14");
        long content = 0;
        long total = 0;
        for (int y = 0; y < h; y += 4) {
            for (int x = 0; x < w; x += 4) {
                total++;
                Color c = pr.getColor(x, y);
                if (Math.abs(c.getRed()   - fill.getRed())   > 0.04
                 || Math.abs(c.getGreen() - fill.getGreen()) > 0.04
                 || Math.abs(c.getBlue()  - fill.getBlue())  > 0.04) {
                    content++;
                }
            }
        }
        double pct = total == 0 ? 0 : (100.0 * content / total);
        System.out.printf("[snapshot] %dx%d, %.1f%% non-fill content; "
                + "TL=%s centre=%s BR=%s%n",
                w, h, pct,
                pr.getColor(2, 2), pr.getColor(w / 2, h / 2), pr.getColor(w - 3, h - 3));
    }

    private void startAnimation() {
        new AnimationTimer() {
            long start = 0;
            long last = 0;
            @Override public void handle(long now) {
                if (start == 0) { start = now; last = now; }
                double t = (now - start) / 1_000_000_000.0;
                double dt = (now - last) / 1_000_000_000.0;
                last = now;

                if (autoRotate) {
                    // Wall-clock driven (degrees/second) so the spin rate is
                    // independent of frame rate — the uncapped Skia pipeline
                    // runs thousands of fps, where a per-frame increment flies.
                    worldRotY.setAngle(worldRotY.getAngle() + dt * 14.0);
                }

                // Central custom mesh (if any) tumbles on its own X axis.
                if (torus != null) {
                    torus.setRotationAxis(Rotate.X_AXIS);
                    torus.setRotate(t * 36);
                }

                // Each ring primitive self-spins around Y.
                double spin = t * 90;
                for (var node : spinners.getChildren()) {
                    node.setRotationAxis(Rotate.Y_AXIS);
                    node.setRotate(spin);
                }
                // The whole primitive ring orbits the torus.
                spinners.setRotationAxis(Rotate.Y_AXIS);
                spinners.setRotate(-t * 18);

                if (lightsOrbit) {
                    lightRig.setRotationAxis(Rotate.Y_AXIS);
                    lightRig.setRotate(t * 40);
                }
            }
        }.start();
    }

    // ====================================================================
    //  Capability fallback (3D not advertised by the active pipeline)
    // ====================================================================

    /**
     * Shown when {@code ConditionalFeature.SCENE3D} is unsupported — i.e.
     * the active pipeline is 2D-only (the Skia pipeline today). Reports
     * the live capability matrix so the demo doubles as a feature probe
     * rather than silently rendering nothing.
     */
    private Region buildUnsupportedPanel() {
        Label heading = new Label("3D not supported by the active pipeline");
        heading.getStyleClass().add("hud-heading");

        Label body = new Label(
                "The Skia render pipeline currently advertises "
              + "ConditionalFeature.SCENE3D = false, so PerspectiveCamera, "
              + "3D primitives (Box / Sphere / Cylinder), MeshView and "
              + "lighting are not rendered. There is no secondary pipeline "
              + "in this build to fall back to, so the full 3D scene is "
              + "skipped to keep the window responsive.\n\n"
              + "When the pipeline starts reporting SCENE3D = true, this "
              + "same demo renders its orbiting-primitives + custom torus "
              + "scene with no further changes.");
        body.getStyleClass().add("hud-sub");
        body.setWrapText(true);
        body.setMaxWidth(560);

        VBox caps = new VBox(6);
        caps.getStyleClass().add("cap-list");
        caps.getChildren().add(capRow("SCENE3D", ConditionalFeature.SCENE3D));
        caps.getChildren().add(capRow("EFFECT", ConditionalFeature.EFFECT));
        caps.getChildren().add(capRow("SHAPE_CLIP", ConditionalFeature.SHAPE_CLIP));
        caps.getChildren().add(capRow("GRAPHICS", ConditionalFeature.GRAPHICS));
        caps.getChildren().add(capRow("TRANSPARENT_WINDOW", ConditionalFeature.TRANSPARENT_WINDOW));

        VBox panel = new VBox(14, heading, body, caps);
        panel.getStyleClass().add("hud-panel");
        panel.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        StackPane host = new StackPane(panel);
        host.getStyleClass().add("viewport");
        StackPane.setMargin(panel, new Insets(28));
        return host;
    }

    private HBox capRow(String name, ConditionalFeature feature) {
        boolean ok = Platform.isSupported(feature);
        Label dot = new Label(ok ? "●" : "○");
        dot.getStyleClass().add(ok ? "cap-ok" : "cap-no");
        Label label = new Label(name);
        label.getStyleClass().add("cap-name");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Label val = new Label(ok ? "supported" : "unsupported");
        val.getStyleClass().add(ok ? "cap-ok" : "cap-no");
        HBox row = new HBox(10, dot, label, gap, val);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(360);
        return row;
    }

    // ====================================================================
    //  Custom title bar (StageStyle.CUSTOM)
    // ====================================================================

    private HBox buildTitleBar(Stage stage) {
        Region appMark = new Region();
        appMark.getStyleClass().add("app-mark");
        appMark.setPrefSize(14, 14);
        appMark.setMinSize(14, 14);
        appMark.setMaxSize(14, 14);

        Label title = new Label("Skia 3D Showcase");
        title.getStyleClass().add("title");

        HBox titleLeft = new HBox(10, appMark, title);
        titleLeft.setAlignment(Pos.CENTER_LEFT);
        titleLeft.setPadding(new Insets(0, 0, 0, 14));
        HBox.setHgrow(titleLeft, Priority.ALWAYS);

        SVGPath maxIcon = icon(SVG_MAX);
        Region minBtn   = captionButton("min", icon(SVG_MIN));
        Region maxBtn   = captionButton("max", maxIcon);
        Region closeBtn = captionButton("close", icon(SVG_CLOSE));

        maxIcon.setContent(stage.isMaximized() ? SVG_RESTORE : SVG_MAX);
        stage.maximizedProperty().addListener((o, was, isMax) ->
                maxIcon.setContent(isMax ? SVG_RESTORE : SVG_MAX));

        minBtn.setOnMouseClicked(e -> stage.setIconified(true));
        maxBtn.setOnMouseClicked(e -> stage.setMaximized(!stage.isMaximized()));
        closeBtn.setOnMouseClicked(e -> stage.close());

        HBox bar = new HBox(titleLeft, minBtn, maxBtn, closeBtn);
        bar.getStyleClass().add("ctb-title-bar");
        bar.setAlignment(Pos.CENTER);
        bar.setPrefHeight(40);
        bar.setMinHeight(40);
        bar.setMaxHeight(40);

        stage.setCaptionRegions(titleLeft);
        stage.setMinRegion(minBtn);
        stage.setMaxRegion(maxBtn);
        stage.setCloseRegion(closeBtn);
        return bar;
    }

    private static SVGPath icon(String svg) {
        SVGPath p = new SVGPath();
        p.setContent(svg);
        p.getStyleClass().add("ctb-icon");
        return p;
    }

    private static Region captionButton(String role, SVGPath glyph) {
        StackPane b = new StackPane(glyph);
        b.getStyleClass().addAll("win-btn", "win-btn-" + role);
        b.setPrefSize(46, 34);
        b.setMinSize(46, 34);
        b.setMaxSize(46, 34);
        return b;
    }

    // ====================================================================
    //  2D HUD overlay (composited over the SubScene)
    // ====================================================================

    private Region buildHud() {
        // --- Top-left info panel ---
        Label heading = new Label("3D pipeline test");
        heading.getStyleClass().add("hud-heading");
        Label sub = new Label(
                "Custom TriangleMesh torus · primitives · Phong materials · "
              + "orbiting point lights — all through Skia.");
        sub.getStyleClass().add("hud-sub");
        sub.setWrapText(true);
        sub.setMaxWidth(380);

        Label fps = new Label("— fps");
        fps.getStyleClass().add("hud-fps");
        startFpsMeter(fps);

        VBox info = new VBox(4, heading, sub, fps);
        info.getStyleClass().add("hud-panel");
        info.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(info, Pos.TOP_LEFT);
        StackPane.setMargin(info, new Insets(18));

        // --- Bottom controls ---
        ToggleButton spin = pill("Auto-rotate", autoRotate);
        spin.setOnAction(e -> autoRotate = spin.isSelected());
        ToggleButton lights = pill("Light orbit", lightsOrbit);
        lights.setOnAction(e -> lightsOrbit = lights.isSelected());
        ToggleButton wire = pill("Wireframe", wireframe);
        wire.setOnAction(e -> {
            wireframe = wire.isSelected();
            DrawMode dm = wireframe ? DrawMode.LINE : DrawMode.FILL;
            for (Shape3D s : allShapes) s.setDrawMode(dm);
        });

        Label hint = new Label("drag: orbit   ·   scroll: zoom");
        hint.getStyleClass().add("hud-hint");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        HBox controls = new HBox(10, spin, lights, wire, gap, hint);
        controls.getStyleClass().add("hud-controls");
        controls.setAlignment(Pos.CENTER_LEFT);
        StackPane.setAlignment(controls, Pos.BOTTOM_CENTER);
        StackPane.setMargin(controls, new Insets(18));

        StackPane hud = new StackPane(info, controls);
        hud.getStyleClass().add("hud");
        // The HUD must not eat 3D mouse-orbit drags — only its actual
        // controls are interactive; the rest is click-through.
        hud.setPickOnBounds(false);
        info.setPickOnBounds(false);
        controls.setPickOnBounds(false);
        return hud;
    }

    private ToggleButton pill(String text, boolean selected) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().add("pill");
        b.setSelected(selected);
        b.setFocusTraversable(false);
        return b;
    }

    private void startFpsMeter(Label fps) {
        new AnimationTimer() {
            long windowStart = 0;
            int frames = 0;
            @Override public void handle(long now) {
                frames++;
                if (windowStart == 0) windowStart = now;
                long elapsed = now - windowStart;
                if (elapsed >= 500_000_000L) {
                    double v = frames * 1_000_000_000.0 / elapsed;
                    fps.setText(String.format("%.0f fps", v));
                    windowStart = now;
                    frames = 0;
                }
            }
        }.start();
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
