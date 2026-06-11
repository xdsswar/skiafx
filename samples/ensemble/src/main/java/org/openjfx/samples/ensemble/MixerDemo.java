/*
 * MixerDemo — exercises javafx.scene.media.MediaMixer.
 *
 * Inputs via system properties (forwarded by the runMixerDemo task):
 *   -Dmixer.audio=<path>   audio input (local file)
 *   -Dmixer.video=<path>   video input (local file)
 *   -Dmixer.out=<path>     output mp4
 *
 * Shows a progress bar, logs every callback to stderr, and (on
 * success) offers to play the result in a MediaView for an immediate
 * eyeball check of the produced file.
 */
package org.openjfx.samples.ensemble;

import java.io.File;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaMixer;
import javafx.scene.media.MediaMixerListener;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Stage;

public final class MixerDemo extends Application {

    public MixerDemo() {}

    private MediaPlayer player;

    @Override
    public void init() {
        if (Media.getFfmpegDirectory() == null) {
            String envDir = System.getenv("OPENJFX_MEDIA_FFMPEG_DIR");
            if (envDir != null && !envDir.isBlank()) {
                Media.setFfmpegDirectory(envDir);
            }
        }
    }

    @Override
    public void start(Stage stage) {
        String audio = System.getProperty("mixer.audio");
        String video = System.getProperty("mixer.video");
        String out   = System.getProperty("mixer.out");

        Label status = new Label("ffmpeg available: " + Media.isFfmpegAvailable());
        ProgressBar bar = new ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        Button playBtn = new Button("Play result");
        playBtn.setDisable(true);

        MediaView view = new MediaView();
        view.setPreserveRatio(true);

        VBox controls = new VBox(8, status, bar, playBtn);
        controls.setPadding(new Insets(12));
        controls.setAlignment(Pos.CENTER_LEFT);

        BorderPane root = new BorderPane(view, null, null, controls, null);
        root.setStyle("-fx-background-color: #0b0e13;");
        status.setStyle("-fx-text-fill: #d0d6e0;");
        stage.setScene(new Scene(root, 960, 640));
        stage.setTitle("Skia-fx MediaMixer demo");
        stage.setOnHidden(e -> { if (player != null) player.dispose(); });
        stage.show();

        if (audio == null || video == null || out == null) {
            status.setText("set -Dmixer.audio / -Dmixer.video / -Dmixer.out");
            return;
        }

        MediaMixer mixer = new MediaMixer(audio, video, out);
        mixer.setListener(new MediaMixerListener() {
            @Override public void onStart() {
                System.err.println("[mixer.demo] onStart");
                status.setText("mixing…");
            }
            @Override public void onProgress(double p) {
                bar.setProgress(p);
                System.err.printf("[mixer.demo] progress %.2f%n", p);
            }
            @Override public void onFinished(String path) {
                System.err.println("[mixer.demo] onFinished: " + path);
                status.setText("done: " + path
                    + "  (" + new File(path).length() / (1024 * 1024) + " MB)");
                bar.setProgress(1);
                playBtn.setDisable(false);
                playBtn.setOnAction(e -> {
                    if (player != null) player.dispose();
                    player = new MediaPlayer(new Media(new File(path).toURI().toString()));
                    view.setMediaPlayer(player);
                    view.fitWidthProperty().bind(root.widthProperty());
                    player.setAutoPlay(true);
                });
            }
            @Override public void onError(String message) {
                System.err.println("[mixer.demo] onError: " + message);
                status.setText("FAILED: " + message);
            }
        });
        mixer.start();
    }
}
