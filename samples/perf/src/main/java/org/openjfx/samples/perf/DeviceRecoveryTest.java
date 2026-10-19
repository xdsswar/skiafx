package org.openjfx.samples.perf;

import com.sun.prism.skia.impl.NativeBridge;
import com.sun.prism.skia.impl.SkiaGpu;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SnapshotParameters;
import javafx.scene.SubScene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.robot.Robot;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Sphere;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * Device-loss recovery test (Windows, D3D12, needs a real display).
 *
 * <p>Removes the shared D3D12 device for real ({@code ID3D12Device5::RemoveDevice},
 * exactly what a TDR / driver reset does) N times while an animated 2D scene
 * (effect, text, Canvas, image, optional 3D SubScene) is on screen. Each cycle
 * must: recover onto a new device (generation +1), render fresh content to the
 * screen and into a snapshot. Canvas pixels are reported but not asserted (they
 * do not survive a device removal, as in stock Prism). With {@code -Drec.3d=true}
 * the 3D SubScene renders until the first loss and stays blank afterwards
 * (3D cannot restart in-process). Exit code = number of failed cycles.</p>
 *
 * <pre>./gradlew :samples:perf:runDeviceRecoveryTest [-Prec.cycles=15] [-Prec.3d=true]</pre>
 */
public class DeviceRecoveryTest extends Application {
    static final int CYCLES = Integer.getInteger("rec.cycles", 5);
    static final boolean WITH_3D = Boolean.getBoolean("rec.3d");
    volatile long frames;
    int fails;
    Rectangle mover;
    Rectangle card;
    static final Color[] CYCLE_COLORS = { Color.web("#3366cc"), Color.web("#7a1fa2"), Color.web("#00897b") };
    volatile Color expect = CYCLE_COLORS[0];
    Stage stage;
    Stage idleStage;   // a second window with static content and no animation

    @Override public void start(Stage st) {
        stage = st;
        Pane root = new Pane();
        root.setStyle("-fx-background-color: white;");
        card = new Rectangle(20, 20, 160, 90);
        card.setFill(Color.web("#3366cc"));
        card.setEffect(new DropShadow(12, Color.BLACK));
        Text text = new Text(30, 150, "Recovery 0123");
        text.setFont(Font.font("Segoe UI", 22));
        Canvas cv = new Canvas(120, 80);
        cv.setLayoutX(220); cv.setLayoutY(20);
        GraphicsContext gc = cv.getGraphicsContext2D();
        gc.setFill(Color.ORANGE); gc.fillOval(10, 10, 100, 60);
        WritableImage img = new WritableImage(64, 64);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++)
            img.getPixelWriter().setColor(x, y, ((x / 8 + y / 8) & 1) == 0 ? Color.GREEN : Color.YELLOW);
        ImageView iv = new ImageView(img); iv.setLayoutX(220); iv.setLayoutY(110);
        mover = new Rectangle(0, 200, 30, 30); mover.setFill(Color.CRIMSON);
        root.getChildren().addAll(card, text, cv, iv, mover);
        if (WITH_3D) {
            PhongMaterial m = new PhongMaterial(Color.gray(0.6));
            Sphere s = new Sphere(40); s.setMaterial(m); s.setTranslateX(60); s.setTranslateY(60);
            PointLight l = new PointLight(Color.WHITE); l.setTranslateZ(-300);
            SubScene sub = new SubScene(new Group(s, l), 120, 120, true, SceneAntialiasing.DISABLED);
            sub.setFill(Color.BLACK); sub.setCamera(new PerspectiveCamera());
            sub.setLayoutX(350); sub.setLayoutY(20);
            root.getChildren().add(sub);
        }
        st.setScene(new Scene(root, 500, 260));
        st.setX(100); st.setY(100);
        st.show();
        st.toFront();
        st.setAlwaysOnTop(true);
        new AnimationTimer() {
            @Override public void handle(long now) {
                frames++;
                mover.setX((frames * 3) % 440);
            }
        }.start();
        idleStage = new Stage();
        Pane idleRoot = new Pane();
        idleRoot.setStyle("-fx-background-color: #ff00ff;");
        idleStage.setScene(new Scene(idleRoot, 160, 100));
        idleStage.setX(640); idleStage.setY(100);
        idleStage.setAlwaysOnTop(true);
        idleStage.show();
        Thread t = new Thread(this::driver, "recover-driver");
        t.setDaemon(true);
        t.start();
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { } }

    static <T> T fx(Callable<T> c) {
        FutureTask<T> f = new FutureTask<>(c);
        Platform.runLater(f);
        try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    void driver() {
        sleep(1500);
        System.out.println("[rec] baseline " + fx(this::probe) + " committedMB=" + committedMb());
        for (int i = 1; i <= CYCLES; i++) {
            long f0 = frames;
            int gen0 = SkiaGpu.deviceGeneration();
            int rc = NativeBridge.testRemoveDevice();
            if (rc != 0 && i == 1) {
                // Not on the D3D12 backend (GL / software / non-Windows): the
                // device-removal path does not apply.
                System.out.println("[rec] SKIP: no removable D3D12 device (rc=" + rc + ")");
                Platform.runLater(() -> { Platform.exit(); System.exit(0); });
                return;
            }
            sleep(150);
            boolean lostSeen = NativeBridge.isDeviceLost();
            long t0 = System.nanoTime();
            while (NativeBridge.isDeviceLost() && System.nanoTime() - t0 < 10_000_000_000L) sleep(20);
            long recMs = (System.nanoTime() - t0) / 1_000_000;
            sleep(500); // let a few pulses render on the new device
            Color next = CYCLE_COLORS[i % CYCLE_COLORS.length];
            fx(() -> { card.setFill(next); expect = next; return null; });
            sleep(400); // fresh content must reach the screen
            String p = fx(this::probe);
            boolean pass = rc == 0 && SkiaGpu.deviceGeneration() == gen0 + 1
                && !NativeBridge.isDeviceLost() && p.startsWith("OK");
            if (!pass) fails++;
            System.out.printf("[rec] cycle %d remove=%d lostSeen=%s recoveredIn=%dms gen=%d frames+%d %s | %s committedMB=%d%n",
                i, rc, lostSeen, recMs, SkiaGpu.deviceGeneration(), frames - f0,
                pass ? "PASS" : "FAIL", p, committedMb());
        }
        System.out.println("[rec] DONE fails=" + fails);
        final int exitCode = fails;
        Platform.runLater(() -> {
            Platform.exit();
            System.exit(exitCode);
        });
    }

    static long committedMb() {
        var os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return os.getCommittedVirtualMemorySize() >> 20;
    }

    /** Fixed content on screen + animation alive + a scene snapshot. */
    String probe() {
        Robot robot = new Robot();
        StringBuilder sb = new StringBuilder();
        boolean ok = true;
        int card = screen(robot, 100, 65);
        int oval = screen(robot, 280, 60);
        int chk = screen(robot, 224, 114);
        ok &= near(card, expect);
        // Canvas pixels do not survive a real device removal (as in stock
        // Prism: the RT is not volatile, no CPU copy) — reported, not asserted.
        ok &= g(chk) > 100 && b(chk) < 80;
        sb.append(String.format("card=%06X oval=%06X chk=%06X", card, oval, chk));
        WritableImage snap = stage.getScene().getRoot().snapshot(new SnapshotParameters(), null);
        int sc = snap.getPixelReader().getArgb(100, 65);
        ok &= near(sc, expect);
        sb.append(String.format(" snapCard=%06X", sc & 0xFFFFFF));
        if (WITH_3D) {
            sb.append(String.format(" sphere=%06X", screen(robot, 410, 80)));
        }
        // The idle second window has no animation: it must still come back
        // after its swap chain was destroyed by the recovery.
        Point2D ip = idleStage.getScene().getRoot().localToScreen(80, 50);
        int idle = robot.getScreenCapture(null, ip.getX(), ip.getY(), 1, 1, false)
            .getPixelReader().getArgb(0, 0) & 0xFFFFFF;
        ok &= near(idle, Color.web("#ff00ff"));
        sb.append(String.format(" idleWin=%06X", idle));
        return (ok ? "OK " : "BAD ") + sb;
    }

    int screen(Robot robot, double x, double y) {
        Point2D p = stage.getScene().getRoot().localToScreen(x, y);
        WritableImage px = robot.getScreenCapture(null, p.getX(), p.getY(), 1, 1, false);
        return px.getPixelReader().getArgb(0, 0) & 0xFFFFFF;
    }

    static boolean near(int rgb, Color c) {
        return Math.abs(r(rgb) - c.getRed() * 255) < 12
            && Math.abs(g(rgb) - c.getGreen() * 255) < 12
            && Math.abs(b(rgb) - c.getBlue() * 255) < 12;
    }

    static int r(int a) { return (a >> 16) & 255; }
    static int g(int a) { return (a >> 8) & 255; }
    static int b(int a) { return a & 255; }

    public static void main(String[] a) { launch(a); }
}
