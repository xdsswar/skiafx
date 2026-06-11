/*
 * NativeMediaMixer — JNI-facing side of javafx.scene.media.MediaMixer.
 *
 * skia-fx addition. The native method lives in jfxmedia (see
 * MediaMixerBridge.cpp), which forwards to the remux engine in
 * fxplugins (ffmpeg_remux.cpp). nativeRemux is SYNCHRONOUS — the
 * public MediaMixer runs it on a worker thread; the native side calls
 * postProgress / isCancelledNative back on that same thread.
 */
package com.sun.media.jfxmediaimpl;

import java.util.function.DoubleConsumer;

public final class NativeMediaMixer {

    /** Mirrors OPENJFX_REMUX_FLAG_FASTSTART (ffmpeg_loader.h). */
    public static final int FLAG_FASTSTART = 1;

    private final DoubleConsumer progressSink;
    private volatile boolean cancelled;

    public NativeMediaMixer(DoubleConsumer progressSink) {
        this.progressSink = progressSink;
    }

    /** Requests cancellation; the native loop polls between packets. */
    public void cancel() {
        cancelled = true;
    }

    /**
     * Runs the remux synchronously on the calling thread.
     *
     * @return {@code null} on success, otherwise a human-readable
     *         error message (also used for cancellation: "cancelled")
     */
    public String run(String audioPath, String videoPath, String outputPath,
                      int flags) {
        // jfxmedia.dll must be loaded for the JNI symbol; the manager
        // loads it. ffmpeg must be loaded for the engine; initialize()
        // resolves it from the configured directory (and force-loads
        // fxplugins.dll, where the engine lives).
        try {
            NativeMediaManager.getDefaultInstance();
            if (!MediaFfmpegConfig.initialize(null)) {
                return "ffmpeg runtime not available - set Media.setFfmpegDirectory() "
                     + "or the OPENJFX_MEDIA_FFMPEG_DIR environment variable";
            }
            return nativeRemux(audioPath, videoPath, outputPath, flags);
        } catch (UnsatisfiedLinkError ule) {
            return "media mixing is not available in this build: " + ule.getMessage();
        } catch (Throwable t) {
            // Missing/corrupt natives surface from the manager init as
            // ExceptionInInitializerError etc. — return a message, never
            // kill the worker (the listener must always hear something).
            return "media mixing failed to initialize: " + t;
        }
    }

    // ---- called from native (MediaMixerBridge.cpp) ----

    /** Progress upcall, 0..1, on the worker thread. */
    private void postProgress(double fraction) {
        DoubleConsumer sink = progressSink;
        if (sink != null) {
            try {
                sink.accept(fraction);
            } catch (Throwable ignored) {
                // A broken app callback must not kill the mix.
            }
        }
    }

    /** Cancellation poll, on the worker thread. */
    private boolean isCancelledNative() {
        return cancelled;
    }

    private native String nativeRemux(String audioPath, String videoPath,
                                      String outputPath, int flags);
}
