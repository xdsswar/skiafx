/*
 * DualStreamPlayer — same UI/UX as VideoPlayerApp but with a SEPARATE
 * audio + video source pair.
 *
 * Demonstrates the Skia-fx Media(audioSource, videoSource[, headers])
 * constructors. Two text fields (video URL, audio URL) — each can be:
 *   - a local file (use the "Pick…" buttons to browse), or
 *   - a remote URL (file:/// , http(s):// , …).
 *
 * Optional HTTP-config inputs feed Media.setHeader / setUserAgent for
 * authenticated remote streams.
 *
 * Internally MediaPlayer spins up two pipelines and sync-corrects them.
 */
package org.openjfx.samples.ensemble;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DualStreamPlayer extends Application {

    /** Required no-arg constructor for reflective {@code Application.launch}. */
    public DualStreamPlayer() {}

    // ---- Persistent UI ---------------------------------------------------
    private final MediaView    view         = new MediaView();
    private final Label        statusLabel  = new Label("");
    private final Label        errorLabel   = new Label("");
    private final Label        timeLabel    = new Label("--:-- / --:--");
    private final Slider       seekSlider   = new Slider();
    private final Slider       volumeSlider = new Slider(0, 100, 70);
    private final ChoiceBox<Double> rateBox = new ChoiceBox<>();
    private final Button       playBtn      = new Button("Play");
    private final Button       pauseBtn     = new Button("Pause");
    private final Button       stopBtn      = new Button("Stop");
    private final Button       muteBtn      = new Button("Mute");
    private final Button       fullBtn      = new Button("Fullscreen");
    private final Label        welcome      = new Label(
        "Set the video + audio sources below, then press Load.");

    // Source inputs — two URL/path text fields + pickers, plus
    // optional HTTP config fields for authenticated remote streams.
    private final TextField    videoSourceField = new TextField();
    private final TextField    audioSourceField = new TextField();
    private final Button       pickVideoBtn     = new Button("Pick video…");
    private final Button       pickAudioBtn     = new Button("Pick audio…");
    private final Button       loadBtn          = new Button("Load");
    private final TextField    userAgentField   = new TextField();
    private final TextField    headerNameField  = new TextField();
    private final TextField    headerValueField = new TextField();
    private final Button       addHeaderBtn     = new Button("Add header");
    private final Label        headersList      = new Label("(no headers)");
    private final Map<String, String> pendingHeaders = new LinkedHashMap<>();

    // Single owner of the active pair's listeners so we can clean them
    // up when picking new sources.
    private MediaPlayer        player;
    private ChangeListener<Duration> currentTimeListener;

    private Stage stage;

    @Override
    public void init() {
        // Same ffmpeg-dir auto-fill as VideoPlayerApp: pick up the env
        // var when nothing was set explicitly.
        if (Media.getFfmpegDirectory() == null) {
            String envDir = System.getenv("OPENJFX_MEDIA_FFMPEG_DIR");
            if (envDir != null && !envDir.isBlank()) {
                Media.setFfmpegDirectory(envDir);
            }
        }
    }

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;

        // Belt-and-braces: surface any uncaught exception in the UI.
        Thread.setDefaultUncaughtExceptionHandler((thr, ex) -> {
            System.err.println("[dual.player] uncaught on " + thr.getName());
            ex.printStackTrace(System.err);
            Platform.runLater(() -> {
                String msg = ex.getMessage();
                if (msg == null || msg.isBlank()) msg = ex.getClass().getSimpleName();
                errorLabel.setText("Error: " + msg);
            });
        });

        view.setPreserveRatio(true);

        welcome.getStyleClass().add("welcome");
        StackPane videoArea = new StackPane(view, welcome);
        videoArea.setStyle("-fx-background-color: #0b0e13;");
        videoArea.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (player == null) return;
            if (player.getStatus() == MediaPlayer.Status.PLAYING) {
                player.pause();
            } else {
                player.play();
            }
        });

        VBox controlBar = buildControlBar();
        AnchorPane root = new AnchorPane(videoArea, controlBar);
        root.setStyle("-fx-background-color: #0b0e13;");

        AnchorPane.setLeftAnchor(controlBar, 0.0);
        AnchorPane.setRightAnchor(controlBar, 0.0);
        AnchorPane.setBottomAnchor(controlBar, 0.0);

        AnchorPane.setTopAnchor(videoArea, 0.0);
        AnchorPane.setLeftAnchor(videoArea, 0.0);
        AnchorPane.setRightAnchor(videoArea, 0.0);

        // Compute the control bar's real height BEFORE the scene's
        // first layout pass, so the initial bottom anchor on the
        // video area is already correct. Without this, AnchorPane
        // used a hardcoded 280px guess, then a heightProperty
        // listener corrected it after CSS + layout converged — the
        // intermediate frame painted at the wrong size, and the
        // resize-then-rebind chain (videoArea → view.fitHeight →
        // MediaView) caused the visible flicker on show.
        controlBar.applyCss();
        controlBar.layout();
        double controlBarH = controlBar.prefHeight(-1);
        AnchorPane.setBottomAnchor(videoArea, controlBarH);
        controlBar.heightProperty().addListener((obs, oldH, newH) -> {
            if (newH != null && newH.doubleValue() > 0) {
                AnchorPane.setBottomAnchor(videoArea, newH.doubleValue());
            }
        });

        view.fitWidthProperty().bind(videoArea.widthProperty());
        view.fitHeightProperty().bind(videoArea.heightProperty());

        Scene scene = new Scene(root, 1280, 800);
        loadStylesheet(scene);
        wireKeyboardShortcuts(scene);

        setTransportEnabled(false);

        pickVideoBtn.setOnAction(e -> pickIntoField(videoSourceField, "Pick video file"));
        pickAudioBtn.setOnAction(e -> pickIntoField(audioSourceField, "Pick audio file"));
        loadBtn.setOnAction(e -> loadSources());
        addHeaderBtn.setOnAction(e -> stashHeader());
        fullBtn.setOnAction(e -> stage.setFullScreen(!stage.isFullScreen()));

        stage.setScene(scene);
        stage.setTitle("Skia-fx Dual-Stream Player (separate audio + video)");
        stage.setMinWidth(520);
        stage.setMinHeight(540);
        stage.setOnHidden(e -> disposePlayer());
        stage.show();

        prefillFromProperties();
    }

    /**
     * Optional source prefill for scripted runs:
     * {@code -Ddual.video=<url> -Ddual.audio=<url>} fills the fields and
     * auto-loads the pair; {@code -Ddual.urlfile=<path>} reads the first
     * URL line as the video source and the second as the audio source
     * (non-URL lines are ignored). Explicit dual.video/dual.audio win
     * over the file.
     */
    private void prefillFromProperties() {
        String video = System.getProperty("dual.video");
        String audio = System.getProperty("dual.audio");

        String urlFile = System.getProperty("dual.urlfile");
        if ((video == null || audio == null) && urlFile != null && !urlFile.isBlank()) {
            try {
                java.util.List<String> urls =
                    java.nio.file.Files.readAllLines(java.nio.file.Path.of(urlFile))
                        .stream()
                        .map(String::trim)
                        .filter(l -> l.startsWith("http://") || l.startsWith("https://")
                                  || l.startsWith("file:"))
                        .toList();
                if (video == null && urls.size() >= 1) video = urls.get(0);
                if (audio == null && urls.size() >= 2) audio = urls.get(1);
            } catch (Exception e) {
                System.err.println("[dual.player] could not read -Ddual.urlfile="
                    + urlFile + ": " + e);
            }
        }

        if (video != null && !video.isBlank()) {
            videoSourceField.setText(video.trim());
            if (audio != null && !audio.isBlank()) {
                audioSourceField.setText(audio.trim());
            }
            Platform.runLater(this::loadSources);
        }
    }

    // ---------------------------------------------------------------------
    // Source picking + loading
    // ---------------------------------------------------------------------

    /** Open a file chooser and store the result into the given field as
     *  a {@code file://} URL. The user can also type a remote URL
     *  directly into the field. */
    private void pickIntoField(TextField target, String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Media files",
                "*.mp4", "*.mkv", "*.mov", "*.avi", "*.webm", "*.m4v",
                "*.ts",  "*.mpg", "*.mpeg", "*.flv", "*.wmv", "*.3gp",
                "*.mp3", "*.m4a", "*.aac", "*.wav", "*.flac", "*.ogg"),
            new FileChooser.ExtensionFilter("All files", "*.*"));
        File f = chooser.showOpenDialog(stage);
        if (f != null) target.setText(f.toURI().toString());
    }

    /** Save a name/value pair from the header inputs into
     *  {@link #pendingHeaders}, applied on the next load. */
    private void stashHeader() {
        String name  = headerNameField.getText().trim();
        String value = headerValueField.getText();
        if (name.isEmpty()) return;
        pendingHeaders.put(name, value);
        refreshHeadersListLabel();
        headerNameField.clear();
        headerValueField.clear();
    }

    private void refreshHeadersListLabel() {
        if (pendingHeaders.isEmpty()) {
            headersList.setText("(no headers)");
        } else {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : pendingHeaders.entrySet()) {
                if (sb.length() > 0) sb.append(" • ");
                sb.append(e.getKey()).append(": ").append(e.getValue());
            }
            headersList.setText(sb.toString());
        }
    }

    /** Read the two source fields, dispose any prior player, then
     *  construct {@code Media(audio, video[, headers])} and a
     *  {@code MediaPlayer} for the pair. */
    private void loadSources() {
        String videoUrl = videoSourceField.getText().trim();
        String audioUrl = audioSourceField.getText().trim();
        if (videoUrl.isEmpty()) {
            errorLabel.setText("Video source is required.");
            return;
        }

        disposePlayer();
        statusLabel.setText("loading…");
        errorLabel.setText("");

        // Build the Media. Three flavours of constructor depending on
        // what the user filled in:
        //  - video only        → single-source legacy ctor
        //  - video + audio     → dual-source ctor
        //  - + headers / UA    → dual-source ctor with headers map
        Media media;
        try {
            String ua = userAgentField.getText().trim();
            Map<String, String> headers = new LinkedHashMap<>(pendingHeaders);
            if (!ua.isEmpty()) headers.put("User-Agent", ua);

            if (audioUrl.isEmpty()) {
                media = new Media(videoUrl);
                if (!headers.isEmpty()) media.setHeaders(headers);
                if (!ua.isEmpty())      media.setUserAgent(ua);
            } else if (headers.isEmpty()) {
                media = new Media(audioUrl, videoUrl);
            } else {
                media = new Media(audioUrl, videoUrl, headers);
            }
        } catch (Throwable t) {
            reportLoadFailure(t);
            return;
        }
        media.errorProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) reportLoadFailure(newV);
        });
        if (media.getError() != null) {
            reportLoadFailure(media.getError());
            return;
        }

        MediaPlayer p;
        try {
            p = new MediaPlayer(media);
        } catch (Throwable t) {
            reportLoadFailure(t);
            return;
        }
        player = p;
        try {
            view.setMediaPlayer(player);
        } catch (Throwable t) {
            reportLoadFailure(t);
            disposePlayer();
            return;
        }
        welcome.setVisible(false);

        wireStatusListeners();
        wireSeekSlider();
        wireVolumeControls();
        wireTransportButtons();
        wirePlaybackRate();

        setTransportEnabled(true);
    }

    private void reportLoadFailure(Throwable t) {
        String raw = t.getMessage();
        final String msg = (raw == null || raw.isBlank())
            ? t.getClass().getSimpleName() : raw;
        t.printStackTrace(System.err);
        // The media error listener fires on a non-FX thread; all UI
        // mutation must hop to the FX thread. runLater from the FX thread
        // (the load path) just defers harmlessly.
        runOnFx(() -> {
            errorLabel.setText("Couldn't open: " + msg);
            statusLabel.setText("");
            welcome.setVisible(true);
            setTransportEnabled(false);
        });
    }

    /** Run on the FX thread, whether the caller is on it or not. */
    private static void runOnFx(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }

    private void disposePlayer() {
        if (player == null) return;
        try {
            if (currentTimeListener != null) {
                player.currentTimeProperty().removeListener(currentTimeListener);
            }
            player.stop();
            player.dispose();
        } catch (Throwable ignored) {}
        player = null;
        currentTimeListener = null;
        view.setMediaPlayer(null);
        seekSlider.setValue(0);
        timeLabel.setText("--:-- / --:--");
    }

    private void setTransportEnabled(boolean enabled) {
        playBtn.setDisable(!enabled);
        pauseBtn.setDisable(!enabled);
        stopBtn.setDisable(!enabled);
        muteBtn.setDisable(!enabled);
        rateBox.setDisable(!enabled);
        seekSlider.setDisable(!enabled);
    }

    // ---------------------------------------------------------------------
    // Listeners — same logic as VideoPlayerApp, adapted to operate
    // against the active player so they can be re-wired on each load.
    // ---------------------------------------------------------------------

    private void wireStatusListeners() {
        Media media = player.getMedia();
        player.statusProperty().addListener((obs, oldV, newV) -> {
            System.err.println("[dual.player] status: " + oldV + " → " + newV);
            Platform.runLater(() -> statusLabel.setText("Status: " + newV));
        });
        player.setOnError(() -> {
            MediaPlayer.Status s = player.getStatus();
            Throwable err = (media.getError() != null) ? media.getError()
                : player.getError();
            String msg = "ERROR @ " + s + ": "
                + (err != null ? err.toString() : "<no error object>");
            System.err.println("[dual.player] " + msg);
            if (err != null) err.printStackTrace(System.err);
            Platform.runLater(() -> errorLabel.setText(msg));
        });
        player.setOnEndOfMedia(() ->
            Platform.runLater(() -> statusLabel.setText("DONE")));
        player.setOnReady(() -> {
            Duration total = media.getDuration();
            if (total != null && !total.isUnknown() && !total.isIndefinite()) {
                seekSlider.setMax(total.toMillis());
            }
            updateTimeLabel(player.getCurrentTime(), total);
            player.play();
            runSeekScriptIfRequested();
        });
    }

    /**
     * Opt-in headless seek exercise for the hardened seekTo() path:
     * {@code -Ddual.seektest=true} fires a sequence of seeks once playback
     * starts — mid-stream jumps plus the edge cases (negative, past-end,
     * rapid duplicate) — logging the landed position after each so a dual-
     * source seek that wedges or desyncs shows up in the run output.
     */
    private boolean seekScriptDone = false;
    private void runSeekScriptIfRequested() {
        if (seekScriptDone || !Boolean.getBoolean("dual.seektest")) return;
        seekScriptDone = true;
        Thread t = new Thread(() -> {
            try {
                MediaPlayer p = player;
                if (p == null) return;
                Duration dur = p.getMedia().getDuration();
                double max = (dur != null && !dur.isUnknown() && !dur.isIndefinite())
                    ? dur.toMillis() : 60000.0;
                double[] targets = {
                    max * 0.25, max * 0.50, max * 0.10,
                    -3000.0,            // clamps to 0
                    max + 10000.0,      // clamps to end
                    max * 0.40, max * 0.40 // rapid duplicate → second de-duped
                };
                for (double tgt : targets) {
                    Thread.sleep(2500);
                    runOnFx(() -> seekTo(tgt));
                    Thread.sleep(600);
                    runOnFx(() -> System.err.println("[dual.seektest] requested="
                        + (long) tgt + "ms landed=" + player.getCurrentTime()
                        + " status=" + player.getStatus()));
                }
                runOnFx(() -> System.err.println("[dual.seektest] DONE — no wedge/crash"));

                // Stress phase: many rapid back/forward seeks. The player must
                // stay alive and keep playing in sync afterwards (the bar for
                // "stable"). -Ddual.stresstest=true (implied by seektest) or
                // standalone.
                if (Boolean.getBoolean("dual.stresstest")
                        || Boolean.getBoolean("dual.seektest")) {
                    runStressSeeks(p, max);
                }
            } catch (InterruptedException ignored) {
            } catch (Throwable ex) {
                ex.printStackTrace(System.err);
            }
        }, "dual-seektest");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Hammer the seek path: alternating back/forward jumps at a fast cadence
     * (faster than the native debounce window for part of it, to exercise
     * coalescing), then settle and confirm playback resumes in sync. Any
     * wedge/crash/permanent freeze fails the "stress back/forward" bar.
     */
    private void runStressSeeks(MediaPlayer p, double max) throws InterruptedException {
        runOnFx(() -> System.err.println("[dual.stress] begin"));
        java.util.Random rnd = new java.util.Random(12345);
        // Burst 1: 40 rapid alternating seeks ~120ms apart (sub-debounce →
        // coalescer should collapse most), random positions.
        for (int i = 0; i < 40; i++) {
            double tgt = max * (0.05 + 0.9 * rnd.nextDouble());
            runOnFx(() -> seekTo(tgt));
            Thread.sleep(120);
        }
        Thread.sleep(3000);
        runOnFx(() -> System.err.println("[dual.stress] after burst1 status="
            + p.getStatus() + " t=" + p.getCurrentTime()));
        // Burst 2: 20 back/forward seeks ~400ms apart (each fires past the
        // debounce → real seeks).
        for (int i = 0; i < 20; i++) {
            double tgt = (i % 2 == 0) ? max * 0.15 : max * 0.80;
            runOnFx(() -> seekTo(tgt));
            Thread.sleep(400);
        }
        Thread.sleep(4000);
        runOnFx(() -> System.err.println("[dual.stress] DONE status="
            + p.getStatus() + " t=" + p.getCurrentTime()));
    }

    /** Verbose position trace (≤1 line / 5 s, only with -Dskia.verbose=true). */
    private long lastTraceMs = Long.MIN_VALUE;

    // Seek de-duplication: a single slider gesture fires both
    // mouseReleased and valueChanging→false with the same value; without
    // this guard each scrub issues two redundant seeks back-to-back
    // (extra flush/resync churn, worse on a dual-source pair).
    private double lastSeekMillis   = Double.NaN;
    private long   lastSeekAtNanos  = 0L;
    private static final double SEEK_DEDUP_MS        = 200.0;
    private static final long   SEEK_DEDUP_WINDOW_NS = 300_000_000L; // 300 ms

    /**
     * The single hardened entry point for every seek (slider + keyboard).
     * Clamps the target to {@code [0, duration]}, refuses to seek when the
     * player isn't in a seekable state, collapses the duplicate seek a
     * drag gesture would otherwise emit, and never lets a seek failure
     * escape as an uncaught exception.
     */
    private void seekTo(double millis) {
        MediaPlayer p = player;
        if (p == null) return;

        MediaPlayer.Status s = p.getStatus();
        if (s == null
                || s == MediaPlayer.Status.DISPOSED
                || s == MediaPlayer.Status.HALTED
                || s == MediaPlayer.Status.UNKNOWN) {
            return; // not seekable yet / anymore
        }

        double target = Math.max(0.0, millis);
        Media media = p.getMedia();
        Duration dur = (media != null) ? media.getDuration() : null;
        if (dur != null && !dur.isUnknown() && !dur.isIndefinite()) {
            double maxMs = dur.toMillis();
            if (maxMs > 0.0) {
                // Stay just shy of the end so a seek-to-end doesn't race
                // straight into EOS before the frame shows.
                target = Math.min(target, Math.max(0.0, maxMs - 100.0));
            }
        }

        long now = System.nanoTime();
        if (!Double.isNaN(lastSeekMillis)
                && Math.abs(target - lastSeekMillis) < SEEK_DEDUP_MS
                && (now - lastSeekAtNanos) < SEEK_DEDUP_WINDOW_NS) {
            return; // duplicate from the same gesture
        }
        lastSeekMillis  = target;
        lastSeekAtNanos = now;

        final double tgt = target;
        seekSlider.setValue(tgt);
        try {
            p.seek(Duration.millis(tgt));
        } catch (Throwable t) {
            String m = (t.getMessage() != null) ? t.getMessage()
                : t.getClass().getSimpleName();
            runOnFx(() -> errorLabel.setText("Seek failed: " + m));
        }
    }

    private void wireSeekSlider() {
        seekSlider.setMin(0);
        seekSlider.setMax(1);
        final boolean trace = Boolean.getBoolean("skia.verbose");
        currentTimeListener = (obs, oldV, newV) -> {
            if (newV == null) return;
            if (trace) {
                long now = System.currentTimeMillis();
                if (now - lastTraceMs >= 5000) {
                    lastTraceMs = now;
                    System.err.println("[dual.player] position "
                        + formatTime(newV) + " status=" + player.getStatus());
                }
            }
            if (!seekSlider.isValueChanging() && !seekSlider.isPressed()) {
                seekSlider.setValue(newV.toMillis());
            }
            updateTimeLabel(newV, player.getMedia().getDuration());
        };
        player.currentTimeProperty().addListener(currentTimeListener);

        seekSlider.valueProperty().addListener((obs, oldV, newV) -> {
            if (seekSlider.isValueChanging() || seekSlider.isPressed()) {
                updateTimeLabel(Duration.millis(newV.doubleValue()),
                                player.getMedia().getDuration());
            }
        });
        // Both triggers route through seekTo(), which de-dupes the
        // double-fire a single drag produces and clamps/guards the target.
        seekSlider.setOnMouseReleased(e -> seekTo(seekSlider.getValue()));
        seekSlider.valueChangingProperty().addListener((obs, was, isChanging) -> {
            if (!isChanging) seekTo(seekSlider.getValue());
        });
    }

    private void wireVolumeControls() {
        player.setVolume(volumeSlider.getValue() / 100.0);
        volumeSlider.valueProperty().addListener((obs, oldV, newV) -> {
            if (player != null) player.setVolume(newV.doubleValue() / 100.0);
        });
        muteBtn.setOnAction(e -> {
            if (player == null) return;
            boolean nowMuted = !player.isMute();
            player.setMute(nowMuted);
            muteBtn.setText(nowMuted ? "Unmute" : "Mute");
        });
    }

    private void wireTransportButtons() {
        playBtn.setOnAction(e  -> { if (player != null) player.play();  });
        pauseBtn.setOnAction(e -> { if (player != null) player.pause(); });
        stopBtn.setOnAction(e  -> {
            if (player == null) return;
            player.stop();
            seekSlider.setValue(0);
        });
    }

    private void wirePlaybackRate() {
        if (rateBox.getItems().isEmpty()) {
            rateBox.getItems().setAll(0.5, 1.0, 1.5, 2.0);
            rateBox.setValue(1.0);
            rateBox.valueProperty().addListener((obs, oldV, newV) -> {
                if (newV != null && player != null) player.setRate(newV);
            });
        }
        player.setRate(rateBox.getValue());
    }

    private void wireKeyboardShortcuts(Scene scene) {
        scene.setOnKeyPressed(ev -> {
            if (player == null) return;
            switch (ev.getCode()) {
                case SPACE -> {
                    if (player.getStatus() == MediaPlayer.Status.PLAYING) {
                        player.pause();
                    } else {
                        player.play();
                    }
                }
                case F -> stage.setFullScreen(!stage.isFullScreen());
                case ESCAPE -> stage.setFullScreen(false);
                case LEFT  -> seekTo(player.getCurrentTime().toMillis() - 5000);
                case RIGHT -> seekTo(player.getCurrentTime().toMillis() + 5000);
                case UP    -> volumeSlider.setValue(
                                Math.min(100, volumeSlider.getValue() + 5));
                case DOWN  -> volumeSlider.setValue(
                                Math.max(0,   volumeSlider.getValue() - 5));
                case M -> muteBtn.fire();
                default -> {}
            }
        });
    }

    // ---------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------

    private VBox buildControlBar() {
        timeLabel.getStyleClass().add("label-time");
        statusLabel.getStyleClass().add("label-status");
        errorLabel.getStyleClass().add("label-error");
        seekSlider.getStyleClass().add("slider-seek");
        volumeSlider.getStyleClass().add("slider-volume");
        playBtn.getStyleClass().add("button-primary");
        loadBtn.getStyleClass().add("button-primary");

        // Source-input rows. Each row is a plain HBox so HBox.setHgrow
        // can give the text field every leftover pixel — buttons stay
        // at their natural width on the right and never get clipped.
        // (GridPane without ColumnConstraints would shrink each column
        // to content width, which pushes the right-most button off the
        // visible area on narrow windows.)
        videoSourceField.setPromptText("file:///path/video.mkv   or   https://host/path/video.mp4");
        audioSourceField.setPromptText("file:///path/audio.mp4   or   https://host/path/audio.mp4   (optional)");
        userAgentField.setPromptText("optional User-Agent header");
        headerNameField.setPromptText("Header name");
        headerValueField.setPromptText("Header value");

        Label videoCap = captionLabel("Video");
        Label audioCap = captionLabel("Audio");
        Label uaCap    = captionLabel("UA");
        // Right-pad caption labels to a fixed width so the three
        // fields line up vertically below each other.
        videoCap.setMinWidth(48);
        audioCap.setMinWidth(48);
        uaCap   .setMinWidth(48);

        HBox videoRow = new HBox(8, videoCap, videoSourceField, pickVideoBtn);
        HBox audioRow = new HBox(8, audioCap, audioSourceField, pickAudioBtn);
        HBox uaRow    = new HBox(8, uaCap,    userAgentField,   loadBtn);
        videoRow.setAlignment(Pos.CENTER_LEFT);
        audioRow.setAlignment(Pos.CENTER_LEFT);
        uaRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(videoSourceField, Priority.ALWAYS);
        HBox.setHgrow(audioSourceField, Priority.ALWAYS);
        HBox.setHgrow(userAgentField,   Priority.ALWAYS);

        HBox headerRow = new HBox(8,
            captionLabel("Header"),
            headerNameField,
            headerValueField,
            addHeaderBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(headerNameField, Priority.ALWAYS);
        HBox.setHgrow(headerValueField, Priority.ALWAYS);

        headersList.getStyleClass().add("label-status");
        headersList.setWrapText(true);

        HBox seekRow = new HBox(10, seekSlider, timeLabel);
        seekRow.setAlignment(Pos.CENTER);
        HBox.setHgrow(seekSlider, Priority.ALWAYS);

        Label volLabel  = captionLabel("Vol");
        Label rateLabel = captionLabel("Rate");

        HBox controlsRow = new HBox(10,
            playBtn, pauseBtn, stopBtn, muteBtn,
            spacer(),
            volLabel, volumeSlider,
            rateLabel, rateBox,
            fullBtn);
        controlsRow.setAlignment(Pos.CENTER_LEFT);

        HBox statusRow = new HBox(12, statusLabel, errorLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        VBox bar = new VBox(8,
            videoRow,
            audioRow,
            uaRow,
            headerRow,
            headersList,
            seekRow,
            controlsRow,
            statusRow);
        bar.setPadding(new Insets(10, 16, 12, 16));
        bar.getStyleClass().add("control-bar");
        return bar;
    }

    private static Label captionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("label-caption");
        return l;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

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
        if (min >= 60) {
            long hr = min / 60;
            min %= 60;
            return String.format("%d:%02d:%02d", hr, min, sec);
        }
        return String.format("%02d:%02d", min, sec);
    }

    /** Same media.css the VideoPlayerApp uses. Three-step lookup
     *  because the JPMS module system returns {@code null} from
     *  {@code Class.getResource("/media.css")} unless the package
     *  containing the resource is open — we fall back to the class
     *  loader and finally to a data: URL wrapping the raw bytes. */
    private void loadStylesheet(Scene scene) {
        java.net.URL css = DualStreamPlayer.class.getResource("/media.css");
        if (css == null) {
            css = DualStreamPlayer.class.getClassLoader().getResource("media.css");
        }
        if (css == null) {
            try (java.io.InputStream in =
                    DualStreamPlayer.class.getModule().getResourceAsStream("media.css")) {
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
            System.err.println("[dual.player] WARNING: media.css not found — "
                + "controls will use default Modena styling.");
        }
    }
}
