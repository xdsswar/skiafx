/*
 * VideoPlayerApp — full-featured video player for the Skia-fx media stack.
 *
 * Exercises the same chain as MediaSmokeApp (ffmpeg/mfwrapper → Skia
 * zero-copy via WGL_NV_DX_interop2) with a user-facing surface:
 *
 *   - File picker (Open…) and drag-and-drop to pick any video on disk.
 *   - Welcome screen with hint text when nothing is loaded.
 *   - Transport: Play / Pause / Stop, click-to-toggle on the video.
 *   - Seek slider with drag-to-scrub.
 *   - Volume slider + Mute.
 *   - Rate selector (0.5× / 1× / 1.5× / 2×).
 *   - Fullscreen toggle (F / Esc, or the button).
 *   - Live status + error labels at the bottom of the control bar.
 *
 * Each Media/MediaPlayer is owned by the loaded clip — picking a new
 * file disposes the previous player cleanly (no resource leaks across
 * loads).
 */
package org.openjfx.samples.ensemble;

import java.io.File;

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
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

public final class VideoPlayerApp extends Application {

    /** Required no-arg constructor for reflective {@code Application.launch}. */
    public VideoPlayerApp() {}

    // ---- Persistent UI ---------------------------------------------------
    private final MediaView    view         = new MediaView();
    private final Label        statusLabel  = new Label("");
    private final Label        errorLabel   = new Label("");
    private final Label        timeLabel    = new Label("--:-- / --:--");
    private final Label        fileLabel    = new Label("No file loaded");
    private final Slider       seekSlider   = new Slider();
    private final Slider       volumeSlider = new Slider(0, 100, 70);
    private final ChoiceBox<Double> rateBox = new ChoiceBox<>();
    private final Button       openBtn      = new Button("Open…");
    private final Button       playBtn      = new Button("Play");
    private final Button       pauseBtn     = new Button("Pause");
    private final Button       stopBtn      = new Button("Stop");
    private final Button       muteBtn      = new Button("Mute");
    private final Button       fullBtn      = new Button("Fullscreen");
    private final Label        welcome      = new Label(
        "Open a video file — Open… button, drag & drop, or Ctrl+O");

    // Single owner of the active clip's listeners so we can clean them
    // up when picking a new file.
    private MediaPlayer        player;
    private ChangeListener<Duration> currentTimeListener;

    private Stage stage;

    @Override
    public void init() {
        // Use the new Media.setFfmpegDirectory API — same effect as
        // -Dopenjfx.media.ffmpeg.dir=... at JVM launch. We fall back
        // to whatever the user set via -D or env var so an explicit
        // override always wins; this only kicks in when nothing was
        // pre-configured.
        if (Media.getFfmpegDirectory() == null) {
            String envDir = System.getenv("OPENJFX_MEDIA_FFMPEG_DIR");
            if (envDir != null && !envDir.isBlank()) {
              //  Media.setFfmpegDirectory(envDir);
            }
        }

        // CPU mode: forces software decode in the producer
        // (ffmpegwrapper) and disables the D3D11 zero-copy on the
        // consumer side. Works on machines with no usable GPU.
        //Media.setDecodeMethod(Media.DecodeMethod.AUTO);
    }

    @Override
    public void start(Stage primaryStage) {
        // Nothing extra needed here — the ffmpeg loader is initialised
        // lazily by NativeMediaManager the first time a Media is built,
        // using the system property set above.
        this.stage = primaryStage;

        // Belt-and-braces: surface any exception that escapes a media
        // event handler in the UI instead of letting it propagate to a
        // process-level handler that might kill the JVM. Errors during
        // load are caught structurally; this catches the long tail.
        Thread.setDefaultUncaughtExceptionHandler((thr, ex) -> {
            System.err.println("[video.player] uncaught on " + thr.getName());
            ex.printStackTrace(System.err);
            javafx.application.Platform.runLater(() -> {
                String msg = ex.getMessage();
                if (msg == null || msg.isBlank()) {
                    msg = ex.getClass().getSimpleName();
                }
                errorLabel.setText("Error: " + msg);
            });
        });

        view.setPreserveRatio(true);

        // Welcome overlay sits ABOVE the (initially-empty) MediaView so
        // the user sees instruction text before picking a clip.
        welcome.getStyleClass().add("welcome");
        StackPane videoArea = new StackPane(view, welcome);
        videoArea.setStyle("-fx-background-color: #0b0e13;");

        // Toggle play/pause when clicking the video itself.
        videoArea.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (player == null) return;
            if (player.getStatus() == MediaPlayer.Status.PLAYING) {
                player.pause();
            } else {
                player.play();
            }
        });

        // Layout root: AnchorPane (NOT BorderPane). BorderPane uses
        // its children's pref sizes in layout, which created a
        // feedback loop with MediaView — MediaView reports its bounds
        // as the preserveRatio-fitted size (e.g. 2208×1242 for a 4K
        // source in a 2560×1242 area), the center pane then shrank
        // to that, the fitWidth binding shrank with it, MediaView
        // re-fit, the system stabilised at the wrong size and the
        // bottom slot got squeezed off-screen on resize. Verified by
        // the diagnostic [ng.mediaview.setViewport] log showing
        // fitW=2560 → fitW=2208 round-tripping back through the
        // binding.
        //
        // AnchorPane breaks the cycle: each child's position+size is
        // declared explicitly via setTop/Left/Right/Bottom anchors.
        // The video area's height is anchored to (root.height -
        // controlBar.height), so it's a function of the WINDOW and
        // the CONTROL BAR — never of the MediaView. The control bar
        // stays anchored to the bottom regardless of media state.
        VBox controlBar = buildControlBar();

        AnchorPane root = new AnchorPane(videoArea, controlBar);
        root.setStyle("-fx-background-color: #0b0e13;");

        // Control bar pinned to the bottom + full width.
        AnchorPane.setLeftAnchor(controlBar, 0.0);
        AnchorPane.setRightAnchor(controlBar, 0.0);
        AnchorPane.setBottomAnchor(controlBar, 0.0);

        // Video area: anchor to top + sides; bottom anchor tracks the
        // control bar's height so the video area always fills the
        // space above. heightProperty fires after each layout pass,
        // which is exactly when we need to update the anchor.
        AnchorPane.setTopAnchor(videoArea, 0.0);
        AnchorPane.setLeftAnchor(videoArea, 0.0);
        AnchorPane.setRightAnchor(videoArea, 0.0);
        AnchorPane.setBottomAnchor(videoArea, 120.0); // sensible default until measured
        controlBar.heightProperty().addListener((obs, oldH, newH) -> {
            if (newH != null && newH.doubleValue() > 0) {
                AnchorPane.setBottomAnchor(videoArea, newH.doubleValue());
            }
        });

        // Fit-size binding. videoArea's size is now externally
        // constrained by the AnchorPane (it doesn't shrink to fit
        // MediaView's bounds), so this binding is no longer cyclic.
        // preserveRatio=true ensures the video letterboxes inside
        // the available area while keeping aspect ratio.
        view.fitWidthProperty().bind(videoArea.widthProperty());
        view.fitHeightProperty().bind(videoArea.heightProperty());

        Scene scene = new Scene(root, 1280, 720);
        loadStylesheet(scene);
        wireDragAndDrop(scene);
        wireKeyboardShortcuts(scene);

        // Transport controls only become enabled once a clip is loaded.
        setTransportEnabled(false);

        openBtn.setOnAction(e -> pickFile());
        fullBtn.setOnAction(e -> stage.setFullScreen(!stage.isFullScreen()));

        stage.setScene(scene);
        stage.setTitle("Skia-fx Video Player");
        // Prevent the window from shrinking small enough to deadlock
        // the Skia GL present path (see NGMediaView's small-dim guard
        // — the present pipeline hangs on a tiny framebuffer, freezing
        // the UI). Real players (VLC, mpv, etc.) all enforce a minimum
        // window size for the same reason. 480×320 keeps the control
        // bar fully usable and well clear of the dangerous threshold.
        stage.setMinWidth(480);
        stage.setMinHeight(360);
        stage.setOnHidden(e -> disposePlayer());
        stage.show();

        startFreezeWatchdog();

        // If a file path is given on the command line, open it
        // immediately so launching from the shell with a path works.
        getParameters().getRaw().stream()
            .filter(s -> !s.startsWith("-"))
            .findFirst()
            .ifPresent(arg -> {
                File f = new File(arg);
                if (f.exists()) loadFile(f);
            });
    }

    // ---------------------------------------------------------------------
    // File picking + loading
    // ---------------------------------------------------------------------

    private void pickFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open video");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Video files",
                "*.mp4", "*.mkv", "*.mov", "*.avi", "*.webm", "*.m4v",
                "*.ts",  "*.mpg", "*.mpeg", "*.flv", "*.wmv", "*.3gp"),
            new FileChooser.ExtensionFilter("Audio files",
                "*.mp3", "*.m4a", "*.aac", "*.wav", "*.flac", "*.ogg"),
            new FileChooser.ExtensionFilter("All files", "*.*"));
        File f = chooser.showOpenDialog(stage);
        if (f != null) loadFile(f);
    }

    private void loadFile(File file) {
        // Tear down any prior player before constructing a new one.
        // Skipping this leaks the prior decoder pipeline (and on the
        // Skia zero-copy path, the WGL aliases held by SkiaMediaTexture).
        disposePlayer();

        statusLabel.setText("loading…");
        errorLabel.setText("");
        fileLabel.setText(file.getName());

        // Media#init runs asynchronously: the container signature check,
        // format probe and demuxer wiring all happen on an internal
        // thread. For unsupported formats (webm / mkv when matroska
        // isn't built, broken files, etc.) the failure surfaces via
        // `media.errorProperty()` AFTER the constructor returns — not
        // by throwing. Listen to it *before* constructing the player,
        // otherwise an async error can land on a different code path
        // and look like a crash.
        Media media;
        try {
            media = new Media(file.toURI().toString());
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
            // setMediaPlayer can throw if the peer's native lifecycle
            // got into a bad state from a prior failed load — recover.
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
        stage.setTitle("Skia-fx Video Player — " + file.getName());
    }

    private void reportLoadFailure(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) msg = t.getClass().getSimpleName();
        errorLabel.setText("Couldn't open: " + msg);
        statusLabel.setText("");
        // Stack trace stays in the console for diagnostics. UI shows
        // the short message so the user knows the file was rejected
        // (most common case: container we don't have a demuxer for,
        // like .webm / .mkv).
        t.printStackTrace(System.err);
        welcome.setVisible(true);
        setTransportEnabled(false);
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
    // Listeners — identical logic to MediaSmokeApp, adapted to operate
    // against the `player` field so they can be re-wired on each load.
    // ---------------------------------------------------------------------

    private void wireStatusListeners() {
        Media media = player.getMedia();
        player.statusProperty().addListener((obs, oldV, newV) -> {
            System.err.println("[video.player] status: " + oldV + " → " + newV);
            Platform.runLater(() -> statusLabel.setText("Status: " + newV));
        });
        player.setOnError(() -> {
            MediaPlayer.Status s = player.getStatus();
            Throwable err = (media.getError() != null) ? media.getError()
                : player.getError();
            String msg = "ERROR @ " + s + ": "
                + (err != null ? err.toString() : "<no error object>");
            System.err.println("[video.player] " + msg);
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
        });
    }

    private void wireSeekSlider() {
        seekSlider.setMin(0);
        seekSlider.setMax(1);
        currentTimeListener = (obs, oldV, newV) -> {
            if (newV == null) return;
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
        seekSlider.setOnMouseReleased(e -> {
            if (player != null) player.seek(Duration.millis(seekSlider.getValue()));
        });
        seekSlider.valueChangingProperty().addListener((obs, was, isChanging) -> {
            if (!isChanging && player != null) {
                player.seek(Duration.millis(seekSlider.getValue()));
            }
        });
    }

    private void wireVolumeControls() {
        player.setVolume(volumeSlider.getValue() / 100.0);
        // Replace any listener attached from a prior clip.
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

    // ---------------------------------------------------------------------
    // Drag & drop + keyboard shortcuts
    // ---------------------------------------------------------------------

    private void wireDragAndDrop(Scene scene) {
        scene.setOnDragOver((DragEvent e) -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles() && db.getFiles().stream().anyMatch(File::isFile)) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        scene.setOnDragDropped((DragEvent e) -> {
            Dragboard db = e.getDragboard();
            boolean ok = false;
            if (db.hasFiles()) {
                File f = db.getFiles().stream()
                    .filter(File::isFile).findFirst().orElse(null);
                if (f != null) { loadFile(f); ok = true; }
            }
            e.setDropCompleted(ok);
            e.consume();
        });
    }

    private void wireKeyboardShortcuts(Scene scene) {
        scene.setOnKeyPressed(ev -> {
            if (ev.isControlDown() && ev.getCode() == KeyCode.O) {
                pickFile();
                return;
            }
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
                case LEFT  -> player.seek(player.getCurrentTime()
                                .subtract(Duration.seconds(5)));
                case RIGHT -> player.seek(player.getCurrentTime()
                                .add(Duration.seconds(5)));
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
        fileLabel.getStyleClass().add("label-status");
        seekSlider.getStyleClass().add("slider-seek");
        volumeSlider.getStyleClass().add("slider-volume");
        playBtn.getStyleClass().add("button-primary");
        openBtn.getStyleClass().add("button-primary");

        HBox seekRow = new HBox(10, seekSlider, timeLabel);
        seekRow.setAlignment(Pos.CENTER);
        HBox.setHgrow(seekSlider, Priority.ALWAYS);

        Label volLabel  = captionLabel("Vol");
        Label rateLabel = captionLabel("Rate");

        HBox controlsRow = new HBox(10,
            openBtn,
            playBtn, pauseBtn, stopBtn, muteBtn,
            spacer(),
            volLabel, volumeSlider,
            rateLabel, rateBox,
            fullBtn);
        controlsRow.setAlignment(Pos.CENTER_LEFT);

        HBox statusRow = new HBox(12, fileLabel, statusLabel, errorLabel);
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

    // ---------------------------------------------------------------------
    // Freeze watchdog
    //
    // Pings the FX thread once per second. If the ping doesn't run
    // within 2.5s (~2 missed pulses), the FX thread is hung. The
    // watchdog dumps every Java + native thread's stack trace to
    // freeze-dump.txt in the working directory and prints a banner
    // to stderr so the user knows where to look. The dump tells us
    // exactly which thread is stuck and on what call — answer to
    // "what's actually blocking on maximize/restore?"
    // ---------------------------------------------------------------------

    private static volatile long lastFxAliveMs = System.currentTimeMillis();
    private static volatile boolean dumpedAlready = false;

    private void startFreezeWatchdog() {
        Thread t = new Thread(() -> {
            // Heartbeat publisher — runs on the FX thread when scheduled.
            // We can't directly observe FX-thread liveness from another
            // thread, but if Platform.runLater's callback isn't running
            // within reasonable time, the FX thread is stuck.
            //
            // The pattern: schedule a runLater every second; the
            // runLater updates lastFxAliveMs. The watchdog reads
            // lastFxAliveMs and compares to wall clock — if it's
            // stale by more than 2.5s, dump.
            //
            // Daemon thread so it doesn't prevent JVM shutdown.
            while (true) {
                try {
                    Platform.runLater(
                        () -> lastFxAliveMs = System.currentTimeMillis());
                } catch (Throwable ignored) {
                    return; // toolkit gone
                }
                long staleMs = System.currentTimeMillis() - lastFxAliveMs;
                if (staleMs > 2500 && !dumpedAlready) {
                    dumpedAlready = true;
                    dumpAllThreads(staleMs);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }, "freeze-watchdog");
        t.setDaemon(true);
        t.start();
        System.err.println("[freeze-watchdog] armed — will dump to "
            + "freeze-dump.txt when FX thread stalls >2.5s");
    }

    private static void dumpAllThreads(long staleMs) {
        // FULL-depth Java stack dump. ThreadInfo.toString() truncates at
        // ~8 frames by default; we want every frame so the render
        // thread's caller chain into surface_begin_draw is visible.
        java.lang.management.ThreadMXBean tmx =
            java.lang.management.ManagementFactory.getThreadMXBean();
        java.lang.management.ThreadInfo[] infos =
            tmx.dumpAllThreads(true, true, Integer.MAX_VALUE);

        StringBuilder sb = new StringBuilder();
        sb.append("=== FX FREEZE detected, FX thread stale ").append(staleMs)
          .append(" ms ===\n");
        sb.append("Captured at: ").append(java.time.Instant.now()).append("\n");
        sb.append("PID: ").append(ProcessHandle.current().pid()).append("\n\n");
        for (java.lang.management.ThreadInfo info : infos) {
            // Build a manual full-depth dump — ThreadInfo.toString()
            // still truncates by default. Include every StackTraceElement.
            sb.append('"').append(info.getThreadName()).append("\" Id=")
              .append(info.getThreadId()).append(' ').append(info.getThreadState());
            if (info.getLockName() != null) {
                sb.append(" on ").append(info.getLockName());
            }
            if (info.getLockOwnerName() != null) {
                sb.append(" owned by \"").append(info.getLockOwnerName())
                  .append("\" Id=").append(info.getLockOwnerId());
            }
            sb.append('\n');
            for (StackTraceElement el : info.getStackTrace()) {
                sb.append("\tat ").append(el).append('\n');
            }
            sb.append('\n');
        }

        try {
            java.io.File out = new java.io.File("freeze-dump.txt").getAbsoluteFile();
            try (java.io.PrintWriter pw = new java.io.PrintWriter(out, "UTF-8")) {
                pw.println(sb);
            }
            System.err.println("=============================================");
            System.err.println("[freeze-watchdog] FX thread frozen "
                + staleMs + " ms");
            System.err.println("[freeze-watchdog] Java thread dump → " + out);
            printNativeDumpInstructions();
        } catch (Throwable t) {
            System.err.println("[freeze-watchdog] failed to write dump: " + t);
            System.err.println(sb);
        }
        System.err.println("=============================================");
    }

    /**
     * Self-attaching jhsdb from inside the hung JVM can deadlock the
     * JVM further (SA suspends threads of the target). Print a copy-
     * paste-ready command for the user to run from a separate terminal
     * WHILE the JVM is still frozen — that captures the native frames
     * via an out-of-process attach, which is safe.
     */
    private static void printNativeDumpInstructions() {
        long pid = ProcessHandle.current().pid();
        String jdkBin = System.getProperty("java.home") + java.io.File.separator + "bin";
        String jhsdb = jdkBin + java.io.File.separator + "jhsdb"
            + (System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "");
        java.io.File nativeOut =
            new java.io.File("freeze-native-dump.txt").getAbsoluteFile();
        System.err.println(
            "[freeze-watchdog] NATIVE STACK: while this JVM is still hung,\n"
          + "[freeze-watchdog] run this in a separate terminal:\n"
          + "[freeze-watchdog]\n"
          + "[freeze-watchdog]   \"" + jhsdb + "\" jstack --mixed --pid " + pid
          + " > \"" + nativeOut + "\"\n"
          + "[freeze-watchdog]\n"
          + "[freeze-watchdog] That writes the mixed Java+C++ stack to:\n"
          + "[freeze-watchdog]   " + nativeOut);
    }

    private void loadStylesheet(Scene scene) {
        // Same resource-loading dance as MediaSmokeApp — module-system
        // path returns null for jar-root resources unless the package
        // is open, so we fall back to the class-loader and finally to a
        // data: URL wrapping the bytes.
        java.net.URL css = VideoPlayerApp.class.getResource("/media.css");
        if (css == null) {
            css = VideoPlayerApp.class.getClassLoader().getResource("media.css");
        }
        if (css == null) {
            try (java.io.InputStream in =
                    VideoPlayerApp.class.getModule().getResourceAsStream("media.css")) {
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
        }
    }
}
