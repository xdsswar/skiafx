/*
 * MediaSmokeApp — end-to-end demo for javafx.media on the Skia pipeline.
 *
 * Phase 1.5 / 1.6 wired up the native chain (jfxmedia.dll →
 * fxplugins.dll → gstreamer-lite.dll → glib-lite.dll). Phase 2
 * landed the Skia-side BGRA upload path so MediaView renders.
 * AV1 + HEVC route through mfwrapper; H.264 through dshowwrapper.
 *
 * This app exercises that whole stack with a real-world control
 * surface:
 *   - Seekable progress slider (drag to scrub).
 *   - Play / Pause / Stop / Mute buttons.
 *   - Volume slider (0–100%).
 *   - Playback rate selector (0.5×, 1×, 1.5×, 2×).
 *   - Live status + error labels.
 *
 * Override the source URI with
 *   ./gradlew :samples:ensemble:runMediaSmoke -Dmedia.sample.uri=<uri>
 * Defaults to a Windows built-in WAV so the smoke test passes on
 * machines without any video sample on disk.
 */
package org.openjfx.samples.ensemble;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Stage;
import javafx.util.Duration;

public final class MediaSmokeApp extends Application {

    /** Required no-arg constructor for reflective {@code Application.launch}. */
    public MediaSmokeApp() {}

    private static final String DEFAULT_URI =
        "file:///C:/Windows/Media/tada.wav";

    /** Time strings — updated from FX thread. */
    private final Label statusLabel = new Label("loading…");
    private final Label errorLabel  = new Label("");
    private final Label timeLabel   = new Label("--:-- / --:--");

    /** Seekable progress bar; min/max set once duration is known. */
    private final Slider seekSlider = new Slider();

    /** Volume slider — 0..100, divide by 100 for MediaPlayer.volume. */
    private final Slider volumeSlider = new Slider(0, 100, 70);

    /** Playback rate chooser. JFX accepts 0.0 < rate ≤ 8.0; we expose the
     *  values people actually use. */
    private final ChoiceBox<Double> rateBox = new ChoiceBox<>();

    private final Button playBtn  = new Button("Play");
    private final Button pauseBtn = new Button("Pause");
    private final Button stopBtn  = new Button("Stop");
    private final Button muteBtn  = new Button("Mute");

    @Override
    public void init() {
        // The canonical place to point the runtime-dynamic ffmpeg
        // loader at its DLLs is Application.init() — it runs before
        // start(), before any MediaPlayer is created, on the JavaFX
        // launcher thread. End-user applications wanting to opt into
        // ffmpeg-backed decode would do exactly this:
        //
        //     com.sun.media.jfxmediaimpl.MediaFfmpegConfig
        //         .initialize("C:/path/to/ffmpeg/bin");
        //
        // Passing null lets the helper fall back to the
        // openjfx.media.ffmpeg.dir system property and then to the
        // OPENJFX_MEDIA_FFMPEG_DIR env var (which the Gradle
        // sample-runner sets to the auto-fetched DLL directory under
        // javafx.media/build/generated/ffmpeg/runtime/...).
        try {
            boolean ffOk = com.sun.media.jfxmediaimpl.MediaFfmpegConfig
                .initialize(null);
            System.err.println("[media.smoke] ffmpeg available: " + ffOk);
        } catch (Throwable t) {
            System.err.println("[media.smoke] ffmpeg init skipped: " + t);
        }
    }

    @Override
    public void start(Stage stage) {
        String uri = System.getProperty("media.sample.uri", DEFAULT_URI);
        System.err.println("[media.smoke] uri = " + uri);

        // ----- Construct the media + player ------------------------------
        // Both constructors can throw on bad URIs or missing native
        // decoders; surface that to the user instead of crashing.
        Media media;
        try {
            media = new Media(uri);
        } catch (Throwable t) {
            failEarly(stage, "Media construct failed", t);
            return;
        }
        MediaPlayer player;
        try {
            player = new MediaPlayer(media);
        } catch (Throwable t) {
            failEarly(stage, "MediaPlayer construct failed", t);
            return;
        }

        wireStatusListeners(media, player);
        wireSeekSlider(player);
        wireVolumeControls(player);
        wireTransportButtons(player);
        wirePlaybackRate(player);

        // ----- Layout -----------------------------------------------------
        MediaView view = new MediaView(player);
        view.setPreserveRatio(true);

        BorderPane root = new BorderPane(view);
        root.setBottom(buildControlBar());

        // Bind MediaView's fit-size to the BorderPane's center area so
        // resize/maximize scales the video. Subtract the control bar
        // height from the available vertical space.
        Region bottom = (Region) root.getBottom();
        view.fitWidthProperty().bind(root.widthProperty());
        view.fitHeightProperty().bind(
            root.heightProperty().subtract(bottom.heightProperty()));

        Scene scene = new Scene(root, 1024, 600);

        // Pull in the dedicated stylesheet so buttons / sliders /
        // picker render with visible backgrounds (the default Modena
        // theme renders the controls almost-transparent on the dark
        // video area).
        //
        // Running as a JPMS module, Class#getResource("/...") goes
        // through the module-system path and returns null when the
        // resource lives at the jar root of a named module unless
        // every package between caller and resource is open. The
        // class-loader path is the reliable fallback for root-level
        // CSS files like /media.css. Last-ditch: read bytes via the
        // module API and wrap them as a data: URL the stylesheet
        // engine can consume from anywhere.
        java.net.URL css = MediaSmokeApp.class.getResource("/media.css");
        if (css == null) {
            css = MediaSmokeApp.class.getClassLoader().getResource("media.css");
        }
        if (css == null) {
            try (java.io.InputStream in =
                    MediaSmokeApp.class.getModule().getResourceAsStream("media.css")) {
                if (in != null) {
                    byte[] bytes = in.readAllBytes();
                    String b64   = java.util.Base64.getEncoder().encodeToString(bytes);
                    css = new java.net.URI("data:text/css;base64," + b64).toURL();
                }
            } catch (Throwable t) {
                t.printStackTrace(System.err);
            }
        }
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        } else {
            System.err.println("[media.smoke] WARN: media.css not on classpath; "
                + "controls will render with the default Modena skin");
        }

        stage.setScene(scene);
        stage.setTitle("MediaSmokeApp — " + uri);
        stage.setOnHidden(e -> {
            try { player.stop();    } catch (Throwable ignored) {}
            try { player.dispose(); } catch (Throwable ignored) {}
        });
        stage.show();
    }

    // ---------------------------------------------------------------------
    // Wiring
    // ---------------------------------------------------------------------

    private void wireStatusListeners(Media media, MediaPlayer player) {
        player.statusProperty().addListener((obs, oldV, newV) -> {
            System.err.println("[media.smoke] status: " + oldV + " → " + newV);
            Platform.runLater(() -> statusLabel.setText("Status: " + newV));
        });
        player.setOnError(() -> {
            MediaPlayer.Status s = player.getStatus();
            Throwable err = (media.getError() != null) ? media.getError()
                : player.getError();
            String msg = "ERROR @ " + s + ": "
                + (err != null ? err.toString() : "<no error object>");
            System.err.println("[media.smoke] " + msg);
            if (err != null) err.printStackTrace(System.err);
            Platform.runLater(() -> errorLabel.setText(msg));
        });
        player.setOnEndOfMedia(() -> {
            System.err.println("[media.smoke] end of media");
            Platform.runLater(() -> statusLabel.setText("DONE"));
        });
        player.setOnReady(() -> {
            // Tracks and duration are only valid once READY fires.
            System.err.println("[media.smoke] ready — duration="
                + media.getDuration());
            System.err.println("[media.smoke] tracks: "
                + media.getTracks().size());
            media.getTracks().forEach(t ->
                System.err.println("[media.smoke]   - " + t));

            Duration total = media.getDuration();
            if (total != null && !total.isUnknown() && !total.isIndefinite()) {
                seekSlider.setMax(total.toMillis());
                seekSlider.setDisable(false);
            }
            updateTimeLabel(player.getCurrentTime(), total);
            player.play();
        });
    }

    /** Seek slider <-> player currentTime binding. Three rules:
     *
     *   1. While the user is touching the slider (mouse pressed and/or
     *      `valueChanging`), the player's currentTime is NOT allowed
     *      to write the slider — otherwise the thumb fights the drag
     *      and snaps back to the playhead every pulse.
     *
     *   2. We seek ONCE on release (mouse up or value-changing flag
     *      falling), not continuously while dragging. Live-scrub
     *      seeks on every value change burn through MF's keyframe
     *      flush + resync per movement, which is fine on a short SD
     *      clip but tanks 4K AV1.
     *
     *   3. The time label updates from the slider position during
     *      drag so the user sees the target timestamp move smoothly,
     *      even though the video itself only catches up on release. */
    private void wireSeekSlider(MediaPlayer player) {
        seekSlider.setDisable(true);
        seekSlider.setMin(0);
        seekSlider.setMax(1);
        HBox.setHgrow(seekSlider, Priority.ALWAYS);

        // Playback advances → move the thumb iff the user isn't
        // grabbing it.
        ChangeListener<Duration> currentTimeListener = (obs, oldV, newV) -> {
            if (newV == null) return;
            if (!seekSlider.isValueChanging() && !seekSlider.isPressed()) {
                seekSlider.setValue(newV.toMillis());
            }
            updateTimeLabel(newV, player.getMedia().getDuration());
        };
        player.currentTimeProperty().addListener(currentTimeListener);

        // Drag preview: while dragging, mirror the slider position
        // into the time readout so the user can target a timestamp
        // before committing. No seek happens here.
        seekSlider.valueProperty().addListener((obs, oldV, newV) -> {
            if (seekSlider.isValueChanging() || seekSlider.isPressed()) {
                updateTimeLabel(Duration.millis(newV.doubleValue()),
                                player.getMedia().getDuration());
            }
        });

        // The actual seek: fires on mouseReleased, which covers both
        // the click-on-track and drag-and-release cases. One MF
        // keyframe resync per gesture instead of dozens per second.
        seekSlider.setOnMouseReleased(e ->
            player.seek(Duration.millis(seekSlider.getValue())));

        // Belt-and-braces: also commit on valueChanging falling, for
        // keyboard-driven thumb moves where the mouse listener never
        // fires.
        seekSlider.valueChangingProperty().addListener((obs, was, isChanging) -> {
            if (!isChanging) {
                player.seek(Duration.millis(seekSlider.getValue()));
            }
        });
    }

    private void wireVolumeControls(MediaPlayer player) {
        volumeSlider.setShowTickMarks(false);
        volumeSlider.setShowTickLabels(false);
        volumeSlider.setPrefWidth(140);
        // Apply initial volume from the slider so the demo opens at a
        // sane level instead of inheriting whatever the JFX default is.
        player.setVolume(volumeSlider.getValue() / 100.0);
        volumeSlider.valueProperty().addListener((obs, oldV, newV) ->
            player.setVolume(newV.doubleValue() / 100.0));

        muteBtn.setOnAction(e -> {
            boolean nowMuted = !player.isMute();
            player.setMute(nowMuted);
            muteBtn.setText(nowMuted ? "Unmute" : "Mute");
        });
    }

    private void wireTransportButtons(MediaPlayer player) {
        playBtn.setOnAction(e  -> player.play());
        pauseBtn.setOnAction(e -> player.pause());
        stopBtn.setOnAction(e  -> {
            player.stop();
            // stop() leaves currentTime at the stop position; reset the
            // slider to match so the next Play resumes from the start.
            seekSlider.setValue(0);
        });
    }

    private void wirePlaybackRate(MediaPlayer player) {
        rateBox.getItems().setAll(0.5, 1.0, 1.5, 2.0);
        rateBox.setValue(1.0);
        rateBox.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) player.setRate(newV);
        });
    }

    // ---------------------------------------------------------------------
    // Layout helpers
    // ---------------------------------------------------------------------

    private VBox buildControlBar() {
        // Style classes are all in /media.css. Setting them here means
        // every visual property (color, hover, focus, padding) lives in
        // one place and the demo's layout code stays geometry-only.
        timeLabel.getStyleClass().add("label-time");
        statusLabel.getStyleClass().add("label-status");
        errorLabel.getStyleClass().add("label-error");
        seekSlider.getStyleClass().add("slider-seek");
        volumeSlider.getStyleClass().add("slider-volume");
        playBtn.getStyleClass().add("button-primary");

        // Row 1: scrubber + time readout. seek slider stretches to fill.
        HBox seekRow = new HBox(10, seekSlider, timeLabel);
        seekRow.setAlignment(Pos.CENTER);
        HBox.setHgrow(seekSlider, Priority.ALWAYS);

        // Row 2: transport + volume + rate
        Label volLabel  = captionLabel("Vol");
        Label rateLabel = captionLabel("Rate");

        HBox controlsRow = new HBox(10,
            playBtn, pauseBtn, stopBtn, muteBtn,
            spacer(),
            volLabel, volumeSlider,
            rateLabel, rateBox);
        controlsRow.setAlignment(Pos.CENTER_LEFT);

        // Row 3: status + error (last so it's least visually noisy)
        HBox statusRow = new HBox(12, statusLabel, errorLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        VBox bar = new VBox(10, seekRow, controlsRow, statusRow);
        bar.setPadding(new Insets(12, 16, 14, 16));
        bar.getStyleClass().add("control-bar");
        return bar;
    }

    private static Label captionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("label-caption");
        return l;
    }

    /** Pushes anything after it to the right end of the HBox. */
    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Format `current / total` as `MM:SS / MM:SS`, or `--:--` for
     *  unknown durations (e.g. before READY). */
    private void updateTimeLabel(Duration current, Duration total) {
        String cur = formatTime(current);
        String tot = formatTime(total);
        Platform.runLater(() -> timeLabel.setText(cur + " / " + tot));
    }

    private static String formatTime(Duration d) {
        if (d == null || d.isUnknown() || d.isIndefinite()) return "--:--";
        long totalSec = (long) Math.floor(d.toSeconds());
        long min = totalSec / 60;
        long sec = totalSec % 60;
        // Hour bucket only appears for clips > 1 hour so short videos
        // keep the compact MM:SS display.
        if (min >= 60) {
            long hr = min / 60;
            min %= 60;
            return String.format("%d:%02d:%02d", hr, min, sec);
        }
        return String.format("%02d:%02d", min, sec);
    }

    /** Construct-time fatal error: render a minimal status pane and
     *  show the stage so the failure is visible instead of a silent
     *  exit. */
    private void failEarly(Stage stage, String title, Throwable t) {
        errorLabel.setText(title + ": " + t.getMessage());
        t.printStackTrace(System.err);
        VBox root = new VBox(8, statusLabel, errorLabel);
        root.setPadding(new Insets(16));
        stage.setScene(new Scene(root, 640, 240));
        stage.setTitle("MediaSmokeApp — error");
        stage.show();
    }
}
