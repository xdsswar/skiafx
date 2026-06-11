/*
 * MediaMixer — skia-fx addition to javafx.scene.media (experimental).
 */
package javafx.scene.media;

import java.io.File;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.Executor;

import javafx.application.Platform;

import com.sun.media.jfxmediaimpl.NativeMediaMixer;

/**
 * Merges a separately-downloaded audio file and video file into a
 * single MP4, without re-encoding (lossless stream copy). The typical
 * use is recombining adaptive-streaming downloads — e.g. a video-only
 * WebM and an audio-only WebM — into one locally playable file.
 *
 * <p>This API is part of the skia-fx fork and is experimental.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * MediaMixer mixer = new MediaMixer(audioFile, videoFile, outputFile);
 * mixer.setListener(new MediaMixerListener() {
 *     @Override public void onStart()                  { status.setText("mixing..."); }
 *     @Override public void onProgress(double p)       { bar.setProgress(p); }
 *     @Override public void onFinished(String path)    { status.setText("done: " + path); }
 *     @Override public void onError(String message)    { status.setText("failed: " + message); }
 * });
 * mixer.start();
 * }</pre>
 *
 * <p>All callbacks are invoked on the JavaFX application thread. The
 * mixing itself runs on a private background thread; {@link #start()}
 * returns immediately.</p>
 *
 * <h2>Requirements and limits</h2>
 * <ul>
 *   <li>Both inputs must be fully downloaded local files (paths or
 *       {@code file:} URIs) — remote URLs are not accepted.</li>
 *   <li>The ffmpeg runtime must be available — see
 *       {@link Media#isFfmpegAvailable()} and
 *       {@link Media#setFfmpegDirectory(String)}. When it is not, the
 *       mixer fails fast through
 *       {@link MediaMixerListener#onError(String)}.</li>
 *   <li>Streams are copied, not transcoded, so both codecs must be
 *       MP4-compatible: H.264/H.265/AV1/VP9 video and AAC/MP3/Opus
 *       audio cover the common adaptive-streaming formats. An
 *       incompatible codec fails with a clear error.</li>
 * </ul>
 */
public final class MediaMixer {

    private final String audioPath;
    private final String videoPath;
    private final String outputPath;

    private volatile MediaMixerListener listener;
    private volatile NativeMediaMixer active;
    private volatile boolean started;
    private volatile boolean fastStart = true;

    /**
     * Creates a mixer for the given inputs and output.
     *
     * @param audioSource path or {@code file:} URI of the audio input
     * @param videoSource path or {@code file:} URI of the video input
     * @param output      path or {@code file:} URI of the MP4 to write
     *                    (overwritten if it exists)
     * @throws IllegalArgumentException when any argument is null/empty
     *         or names a remote URL
     */
    public MediaMixer(String audioSource, String videoSource, String output) {
        this.audioPath  = toLocalPath(audioSource, "audioSource");
        this.videoPath  = toLocalPath(videoSource, "videoSource");
        this.outputPath = toLocalPath(output, "output");
    }

    /**
     * Sets the callback receiver. May be {@code null} to stop
     * receiving callbacks. All methods are invoked on the JavaFX
     * application thread.
     *
     * @param listener the listener, or {@code null}
     */
    public void setListener(MediaMixerListener listener) {
        this.listener = listener;
    }

    /**
     * Controls MP4 "fast start": when enabled (the default), the index
     * (moov atom) is relocated to the head of the file at finalize
     * time, so the result can begin playing before it has fully
     * downloaded when served progressively. Costs the muxer one extra
     * pass over the output at the end (the final progress step takes
     * slightly longer). Disable for the fastest possible finish on
     * files that will only be played locally.
     *
     * @param fastStart whether to relocate the index ({@code true} by
     *                  default)
     */
    public void setFastStart(boolean fastStart) {
        this.fastStart = fastStart;
    }

    /**
     * Starts mixing on a private daemon thread and returns immediately.
     * A mixer instance runs once; calling this twice fails through
     * {@link MediaMixerListener#onError(String)}.
     *
     * <p>Independent {@code MediaMixer} instances may run concurrently —
     * the engine has no shared mutable state. To control the threading
     * yourself (thread pools, priorities, several mixes on one
     * executor), use {@link #start(Executor)} instead.</p>
     */
    public void start() {
        start(task -> {
            Thread worker = new Thread(task, "JFX-MediaMixer");
            worker.setDaemon(true);
            worker.start();
        });
    }

    /**
     * Starts mixing on the given executor and returns immediately. The
     * whole mix runs as a single task on whatever thread the executor
     * provides; callbacks are still delivered on the JavaFX application
     * thread. A mixer instance runs once.
     *
     * @param executor runs the (long, blocking) mix task — supply your
     *                 own pool to bound concurrency across several
     *                 simultaneous mixes
     */
    public void start(Executor executor) {
        Objects.requireNonNull(executor, "executor");

        // Fail fast with a precise message before any native work — a
        // mistyped path should not cost an ffmpeg init or a worker thread.
        String problem = validatePaths();
        if (problem != null) {
            postError(problem);
            return;
        }

        synchronized (this) {
            if (started) {
                postError("this MediaMixer has already been started");
                return;
            }
            started = true;
        }

        NativeMediaMixer mixer = new NativeMediaMixer(this::postProgress);
        active = mixer;

        int flags = fastStart ? NativeMediaMixer.FLAG_FASTSTART : 0;
        // The task body must never throw: Executor.execute may run it
        // synchronously in the calling thread (caller-runs policies), and
        // an escaping exception would be indistinguishable from a
        // rejected submission below — and on a pool thread it would kill
        // the worker with the listener never hearing a terminal callback.
        Runnable task = () -> {
            try {
                post(MediaMixerListener::onStart);
                String error = mixer.run(audioPath, videoPath, outputPath, flags);
                if (error == null) {
                    String result = new File(outputPath).getAbsolutePath();
                    post(l -> l.onFinished(result));
                } else {
                    postError(error);
                }
            } catch (Throwable unexpected) {
                postError("media mixing failed: " + unexpected);
            } finally {
                active = null;
            }
        };

        try {
            executor.execute(task);
        } catch (RuntimeException rejected) {
            // The executor refused the task (shut down / saturated): the
            // mix never ran (the task itself cannot throw) — unwind so
            // the instance isn't wedged as "running forever", then let
            // the caller see the rejection.
            active = null;
            synchronized (this) {
                started = false;
            }
            throw rejected;
        }
    }

    /**
     * Requests cancellation. Best effort: the mix stops at the next
     * packet boundary and {@link MediaMixerListener#onError(String)}
     * fires with {@code "cancelled"}. The partially-written output
     * file is left on disk.
     */
    public void cancel() {
        NativeMediaMixer mixer = active;
        if (mixer != null) {
            mixer.cancel();
        }
    }

    /** @return {@code true} while the background mix is running. */
    public boolean isRunning() {
        return active != null;
    }

    // ------------------------------------------------------------------

    private interface ListenerCall {
        void call(MediaMixerListener listener);
    }

    private void post(ListenerCall call) {
        MediaMixerListener l = listener;
        if (l != null) {
            Platform.runLater(() -> {
                MediaMixerListener now = listener;
                if (now != null) {
                    call.call(now);
                }
            });
        }
    }

    private void postProgress(double fraction) {
        post(l -> l.onProgress(fraction));
    }

    private void postError(String message) {
        post(l -> l.onError(message));
    }

    /** @return a human-readable problem, or {@code null} when all paths are usable. */
    private String validatePaths() {
        String problem = checkReadableFile(audioPath, "audio input");
        if (problem == null) {
            problem = checkReadableFile(videoPath, "video input");
        }
        if (problem == null) {
            File out = new File(outputPath);
            if (out.isDirectory()) {
                problem = "output is a directory, not a file: " + outputPath;
            } else {
                File parent = out.getAbsoluteFile().getParentFile();
                if (parent != null && !parent.isDirectory()) {
                    problem = "output directory does not exist: " + parent;
                } else if (out.exists() && !out.canWrite()) {
                    problem = "output file is not writable: " + outputPath;
                }
            }
        }
        return problem;
    }

    private static String checkReadableFile(String path, String role) {
        File f = new File(path);
        if (!f.exists()) {
            return role + " does not exist: " + path;
        }
        if (!f.isFile()) {
            return role + " is not a file: " + path;
        }
        if (!f.canRead()) {
            return role + " is not readable: " + path;
        }
        return null;
    }

    /** Accepts a plain path or a file: URI; rejects anything remote. */
    private static String toLocalPath(String source, String name) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be null or empty");
        }
        if (source.regionMatches(true, 0, "file:", 0, 5)) {
            try {
                return new File(new URI(source)).getAbsolutePath();
            } catch (Exception e) {
                throw new IllegalArgumentException(
                    name + " is not a valid file URI: " + source, e);
            }
        }
        if (source.indexOf("://") > 0) {
            throw new IllegalArgumentException(
                name + " must be a local file (downloaded first), not a remote URL");
        }
        return new File(source).getAbsolutePath();
    }
}
