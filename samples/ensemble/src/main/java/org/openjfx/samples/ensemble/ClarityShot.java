/*
 * ClarityShot — renders a crispness test card and saves a screen capture.
 *
 * Stock JavaFX API only, so the identical class runs on skia-fx and stock
 * OpenJFX for a pixel-level clarity comparison. Captures the real on-screen
 * window pixels (javafx.scene.robot.Robot), i.e. exactly what the user sees
 * after the pipeline's present path, then writes a PNG (java.util.zip only)
 * and exits.
 *
 *   -Dclarity.out=path.png   output file (default build/clarity/<label>.png)
 *   -Dclarity.label=name     label for the default file name
 *   -Dglass.win.uiScale=1.5  force a render scale (Windows) to test HiDPI
 */
package org.openjfx.samples.ensemble;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.CacheHint;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Duration;

public final class ClarityShot extends Application {

    @Override
    public void start(Stage stage) {
        VBox root = new VBox(10);
        root.setPadding(new Insets(12));
        root.setStyle("-fx-background-color: white;");

        // 1) 1px bordered regions at fractional-friendly positions.
        HBox boxes = new HBox(7);
        for (int i = 0; i < 6; i++) {
            Region r = new Region();
            r.setPrefSize(37 + i, 23);
            r.setStyle("-fx-border-color: black; -fx-border-width: 1; -fx-background-color: #e8f0ff;");
            boxes.getChildren().add(r);
        }

        // 2) Small text at several sizes.
        VBox texts = new VBox(2);
        for (int s : new int[] {9, 11, 12, 13, 15}) {
            Text t = new Text("The quick brown fox jumps over the lazy dog 0123456789 (" + s + "px)");
            t.setFont(Font.font("Segoe UI", s));
            texts.getChildren().add(t);
        }

        // 3) Hairlines + shapes.
        Group lines = new Group();
        for (int i = 0; i < 10; i++) lines.getChildren().add(new Line(i * 9 + 0.5, 0, i * 9 + 0.5, 30));
        Rectangle rr = new Rectangle(60, 30, Color.web("#3366cc"));
        rr.setArcWidth(12);
        rr.setArcHeight(12);
        Circle c = new Circle(15, Color.web("#cc3333"));

        // 4) Opacity group, cached node, rounded clip.
        Label op = new Label("Opacity 0.9 group text");
        op.setStyle("-fx-border-color: #444; -fx-padding: 4;");
        StackPane opGroup = new StackPane(op);
        opGroup.setOpacity(0.9);
        Label cached = new Label("Cached node text");
        cached.setStyle("-fx-border-color: #444; -fx-padding: 4;");
        cached.setCache(true);
        cached.setCacheHint(CacheHint.QUALITY);
        Label clipped = new Label("Rounded clip text");
        clipped.setStyle("-fx-background-color: #ffe9b3; -fx-padding: 6;");
        Rectangle clip = new Rectangle(140, 30);
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        clipped.setClip(clip);

        // 5) Pixel-art canvas, smoothing off, scaled 6x.
        Canvas art = new Canvas(64, 64);
        GraphicsContext g = art.getGraphicsContext2D();
        WritableImage src = new WritableImage(8, 8);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++)
            src.getPixelWriter().setColor(x, y, ((x + y) & 1) == 0 ? Color.BLACK : Color.web("#33bb55"));
        g.setImageSmoothing(false);
        g.drawImage(src, 0, 0, 64, 64);

        // 6) Controls.
        HBox controls = new HBox(8, new Button("Button"), new CheckBox("Check"), new TextField("Text field"));

        FlowPane misc = new FlowPane(10, 10, lines, rr, c, opGroup, cached, clipped, art);
        root.getChildren().addAll(boxes, texts, misc, controls);

        Scene scene = new Scene(root, 640, 360);
        stage.setScene(scene);
        stage.setTitle("ClarityShot");
        stage.setX(100);
        stage.setY(100);
        stage.show();

        PauseTransition wait = new PauseTransition(Duration.millis(1200));
        wait.setOnFinished(e -> {
            Rectangle2D area = new Rectangle2D(
                    stage.getX() + scene.getX(), stage.getY() + scene.getY(),
                    scene.getWidth(), scene.getHeight());
            // scaleToFit=false: raw device pixels, no resampling by the robot.
            WritableImage shot = new Robot().getScreenCapture(null, area, false);
            String label = System.getProperty("clarity.label", "run");
            Path out = Path.of(System.getProperty("clarity.out", "build/clarity/" + label + ".png"));
            try {
                if (out.getParent() != null) Files.createDirectories(out.getParent());
                writePng(shot, out);
                System.out.println("[clarity] wrote " + out.toAbsolutePath()
                        + " " + (int) shot.getWidth() + "x" + (int) shot.getHeight());
            } catch (IOException ex) {
                System.out.println("[clarity] failed: " + ex);
            }
            Platform.exit();
        });
        wait.play();
    }

    /** Minimal RGB PNG encoder (java.util.zip only). */
    private static void writePng(WritableImage img, Path path) throws IOException {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        PixelReader pr = img.getPixelReader();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(raw, new Deflater(6))) {
            byte[] row = new byte[1 + w * 3];
            for (int y = 0; y < h; y++) {
                row[0] = 0;
                for (int x = 0; x < w; x++) {
                    int argb = pr.getArgb(x, y);
                    row[1 + x * 3] = (byte) (argb >> 16);
                    row[2 + x * 3] = (byte) (argb >> 8);
                    row[3 + x * 3] = (byte) argb;
                }
                z.write(row);
            }
        }
        try (OutputStream o = Files.newOutputStream(path)) {
            o.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
            ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
            writeInt(ihdr, w);
            writeInt(ihdr, h);
            ihdr.write(new byte[] {8, 2, 0, 0, 0});
            chunk(o, "IHDR", ihdr.toByteArray());
            chunk(o, "IDAT", raw.toByteArray());
            chunk(o, "IEND", new byte[0]);
        }
    }

    private static void chunk(OutputStream o, String type, byte[] data) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeInt(b, data.length);
        o.write(b.toByteArray());
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        o.write(t);
        o.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        b.reset();
        writeInt(b, (int) crc.getValue());
        o.write(b.toByteArray());
    }

    private static void writeInt(OutputStream o, int v) throws IOException {
        o.write(v >>> 24);
        o.write(v >>> 16);
        o.write(v >>> 8);
        o.write(v);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
