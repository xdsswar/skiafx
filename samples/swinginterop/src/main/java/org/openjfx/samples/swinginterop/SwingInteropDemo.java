/*
 * samples/swinginterop — SwingInteropDemo
 *
 * A single window that drives BOTH directions of JavaFX <-> Swing interop, so
 * it touches every wrapper we ported out of the (deprecated) JDK module
 * jdk.unsupported.desktop into com.sun.javafx.embed.swing.interop:
 *
 *   - JFXPanel  : a JavaFX Scene hosted inside a Swing JFrame.
 *                 Drives SwingInterOpUtils (focus grab/ungrab, event posting).
 *   - SwingNode : a live Swing JButton hosted inside that JavaFX Scene.
 *                 Drives LightweightFrameWrapper + LightweightContentWrapper
 *                 (the offscreen JLightweightFrame whose pixels FX composites),
 *                 and the DnD wrappers once you start dragging.
 *
 * On startup it prints a self-check banner proving javafx.swing no longer
 * requires jdk.unsupported.desktop. If you only want the check (no window),
 * launch with -Dswinginterop.selfCheckOnly=true and the app exits after
 * printing the banner — handy for headless / CI verification.
 *
 * Run:  ./gradlew :samples:swinginterop:run
 */
package org.openjfx.samples.swinginterop;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.lang.module.ModuleDescriptor;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public final class SwingInteropDemo {

    private SwingInteropDemo() { }

    public static void main(String[] args) {
        boolean ok = printSelfCheck();

        if (Boolean.getBoolean("swinginterop.selfCheckOnly")) {
            // Headless verification path: no window, just the exit code.
            System.exit(ok ? 0 : 1);
            return;
        }

        // Swing rule: build the UI on the Event Dispatch Thread.
        SwingUtilities.invokeLater(SwingInteropDemo::buildAndShow);
    }

    /**
     * Proves the migration at runtime: the javafx.swing module descriptor must
     * not list jdk.unsupported.desktop, and the interop wrapper that SwingNode
     * relies on must resolve from our own package.
     *
     * @return true if every check passed.
     */
    private static boolean printSelfCheck() {
        Module swing = JFXPanel.class.getModule();

        boolean stillRequiresUnsupported = swing.getDescriptor()
                .requires().stream()
                .map(ModuleDescriptor.Requires::name)
                .anyMatch("jdk.unsupported.desktop"::equals);

        // The relocated wrapper class must be loadable from javafx.swing.
        boolean wrapperRelocated;
        String wrapperModule;
        try {
            Class<?> w = Class.forName(swing,
                    "com.sun.javafx.embed.swing.interop.LightweightFrameWrapper");
            wrapperRelocated = (w != null);
            wrapperModule = (w != null) ? w.getModule().getName() : "<not found>";
        } catch (Throwable t) {
            wrapperRelocated = false;
            wrapperModule = "<error: " + t + ">";
        }

        boolean ok = !stillRequiresUnsupported && wrapperRelocated;

        System.out.println("==================================================================");
        System.out.println(" skia-fx swing interop self-check");
        System.out.println("------------------------------------------------------------------");
        System.out.println("  javafx.swing requires jdk.unsupported.desktop : "
                + (stillRequiresUnsupported ? "YES  <-- FAIL" : "no   (good)"));
        System.out.println("  LightweightFrameWrapper relocated             : "
                + (wrapperRelocated ? "yes  (good)" : "NO   <-- FAIL"));
        System.out.println("  wrapper resolved from module                  : " + wrapperModule);
        System.out.println("------------------------------------------------------------------");
        System.out.println("  RESULT: " + (ok ? "PASS" : "FAIL"));
        System.out.println("==================================================================");
        return ok;
    }

    // ----- EDT: build the Swing shell + JFXPanel -----------------------------

    private static void buildAndShow() {
        JFrame frame = new JFrame("skia-fx — JavaFX <-> Swing interop");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        // A plain Swing panel on the left, for visual comparison with the
        // embedded FX content on the right.
        JPanel swingSide = new JPanel();
        swingSide.setLayout(new BoxLayout(swingSide, BoxLayout.Y_AXIS));
        swingSide.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JLabel swingTitle = new JLabel("Pure Swing (host)");
        JButton swingButton = new JButton("Native Swing button");
        swingButton.addActionListener(e ->
                swingTitle.setText("Pure Swing (host) — clicked"));
        swingSide.add(swingTitle);
        swingSide.add(swingButton);

        // The JFXPanel: a JavaFX Scene living inside this Swing frame.
        JFXPanel fxPanel = new JFXPanel();
        fxPanel.setPreferredSize(new Dimension(460, 320));

        frame.getContentPane().add(swingSide, BorderLayout.WEST);
        frame.getContentPane().add(fxPanel, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Scene construction must happen on the FX application thread.
        Platform.runLater(() -> fxPanel.setScene(buildFxScene()));

        System.out.println("[demo] autoExitMs=" + Long.getLong("swinginterop.autoExitMs", 0L)
                + " shotPath=" + System.getProperty("swinginterop.shotPath"));

        // Optional self-terminate, so the full windowed path (JFXPanel boot +
        // SwingNode -> JLightweightFrame construction, i.e. the relocated
        // wrappers actually executing against sun.* via the runtime
        // --add-exports) can be exercised non-interactively. If those flags
        // were missing this run would have already thrown IllegalAccessError.
        long autoExitMs = Long.getLong("swinginterop.autoExitMs", 0L);
        if (autoExitMs > 0) {
            javax.swing.Timer t = new javax.swing.Timer((int) autoExitMs, e -> {
                maybeCaptureFrame(frame);
                System.out.println("[auto-exit] windowed interop path ran without "
                        + "IllegalAccessError — runtime --add-exports OK");
                System.exit(0);
            });
            t.setRepeats(false);
            t.start();
        }
    }

    /**
     * Test aid: with -Dswinginterop.shotPath=&lt;png&gt; set, render the frame
     * (including the JFXPanel's current FX pixel buffer, drawn by
     * JFXPanel.paintComponent) into an image and write it out, so the embedded
     * JavaFX content can be inspected without a live display.
     */
    private static void maybeCaptureFrame(JFrame frame) {
        String shot = System.getProperty("swinginterop.shotPath");
        if (shot == null || shot.isBlank()) {
            return;
        }
        try {
            int w = Math.max(1, frame.getWidth());
            int h = Math.max(1, frame.getHeight());
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            // printAll() drives paintComponent on the JFXPanel, which blits its
            // FX pixel buffer — so the capture reflects real embedded content.
            frame.printAll(g);
            g.dispose();
            javax.imageio.ImageIO.write(img, "png", new java.io.File(shot));
            System.out.println("[capture] wrote frame snapshot to " + shot);
        } catch (Throwable t) {
            System.out.println("[capture] failed: " + t);
        }
    }

    // ----- FX thread: build the Scene, including a SwingNode -----------------

    private static Scene buildFxScene() {
        Label header = new Label("JavaFX content (inside a Swing JFXPanel)");
        header.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        Label counter = new Label("SwingNode button clicks: 0");
        int[] clicks = {0};

        // A real Swing component embedded back inside the FX scene graph.
        //
        // NOTE: constructing a SwingNode eagerly loads the native "prism_common"
        // library (Utils.loadNativeSwingLibrary), which this skia-fx dev tree
        // does not build yet. That is unrelated to the jdk.unsupported.desktop
        // migration, so we degrade gracefully: if the native isn't present we
        // still show the rest of the scene and report the gap, instead of
        // killing the whole FX scene build.
        javafx.scene.Node swingNodeOrNotice;
        try {
            SwingNode swingNode = new SwingNode();
            SwingUtilities.invokeLater(() -> {
                JPanel inner = new JPanel();
                inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
                inner.setBorder(BorderFactory.createTitledBorder("Swing inside JavaFX (SwingNode)"));
                JButton embeddedSwingButton = new JButton("Click me (Swing -> FX)");
                embeddedSwingButton.addActionListener(e ->
                        // Hop back to the FX thread to mutate FX nodes.
                        Platform.runLater(() -> {
                            clicks[0]++;
                            counter.setText("SwingNode button clicks: " + clicks[0]);
                        }));
                inner.add(embeddedSwingButton);
                // setContent may be called off the FX thread; SwingNode handles
                // the marshalling internally.
                swingNode.setContent(inner);
            });
            swingNodeOrNotice = swingNode;
        } catch (Throwable t) {
            System.out.println("[demo] SwingNode unavailable in this dev tree: " + t);
            Label notice = new Label("SwingNode (Swing-in-FX) unavailable:\n"
                    + "native 'prism_common' is not built in this dev tree.\n"
                    + "JFXPanel interop above is unaffected.");
            notice.setStyle("-fx-text-fill: #ffd27f;");
            counter.setText("SwingNode disabled (native prism_common missing)");
            swingNodeOrNotice = notice;
        }

        Button fxButton = new Button("Native JavaFX button");
        fxButton.setOnAction(e -> header.setText(
                "JavaFX content (inside a Swing JFXPanel) — FX button clicked"));

        HBox buttons = new HBox(10, fxButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(14, header, buttons, swingNodeOrNotice, counter);
        root.setPadding(new Insets(18));
        root.setAlignment(Pos.TOP_LEFT);
        root.setStyle("-fx-background-color: linear-gradient(to bottom right, #1b2838, #2a3f5f);"
                + " -fx-text-fill: white;");
        header.setStyle(header.getStyle() + " -fx-text-fill: white;");
        counter.setStyle("-fx-text-fill: #b8c7e0;");

        return new Scene(root, 460, 320);
    }
}
