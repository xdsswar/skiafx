/*
 * MediaMixerListener — skia-fx addition to javafx.scene.media
 * (experimental).
 */
package javafx.scene.media;

/**
 * Receives {@link MediaMixer} lifecycle callbacks. Every method is
 * invoked on the JavaFX application thread and has an empty default,
 * so implementations override only what they need.
 *
 * <p>This API is part of the skia-fx fork and is experimental.</p>
 */
public interface MediaMixerListener {

    /** Mixing has begun. */
    default void onStart() {}

    /**
     * Mixing progress.
     *
     * @param progress fraction complete, from {@code 0.0} to {@code 1.0}
     */
    default void onProgress(double progress) {}

    /**
     * Mixing completed successfully.
     *
     * @param outputPath absolute path of the written MP4 file
     */
    default void onFinished(String outputPath) {}

    /**
     * Mixing failed or was cancelled.
     *
     * @param message human-readable reason ({@code "cancelled"} when
     *                {@link MediaMixer#cancel()} was requested)
     */
    default void onError(String message) {}
}
