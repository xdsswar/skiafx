/*
 * Copyright (c) 2026 skia-fx contributors.
 */
package com.sun.media.jfxmedia;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.media.jfxmediaimpl.MediaFfmpegConfig;

/**
 * Master control for how the skia-fx media pipeline decodes video.
 *
 * <h2>What this controls</h2>
 *
 * Every layer that has both a CPU and a GPU path consults
 * {@link #get()} when it needs to pick one:
 *
 * <ul>
 *   <li>The ffmpeg-backed decoder ({@code ffmpegwrapper}) chooses
 *       between D3D11VA hardware acceleration and pure libavcodec
 *       software decode.</li>
 *   <li>The Skia consumer ({@code SkiaMediaTexture}) chooses between
 *       the WGL_NV_DX_interop2 zero-copy fast path (samples directly
 *       from the decoder's D3D11 texture) and the CPU upload paths
 *       (YUV-native via Skia's GPU shader, or BGRA raster copy).</li>
 *   <li>The HDR pipeline chooses between the native
 *       {@code SkRuntimeEffect} BT.2390 tone-mapper (GPU) and the
 *       pure-Java LUT-based tone-mapper (CPU). See
 *       {@code com.sun.prism.skia.impl.HdrToneMap}.</li>
 * </ul>
 *
 * <h2>How to set it</h2>
 *
 * Two equivalent ways, set both either way:
 *
 * <pre>{@code
 * // 1. System property — picks up from -Dskia.media.decode at startup:
 * //    java -Dskia.media.decode=CPU MyApp
 *
 * // 2. Programmatic API — set before constructing the first Media:
 * @Override
 * public void init() {
 *     MediaDecoding.set(MediaDecoding.Mode.CPU);
 * }
 * }</pre>
 *
 * Programmatic {@link #set(Mode)} overrides the property. Set early
 * — once a {@code Media} starts decoding, the per-pipeline choice is
 * baked in for that stream's lifetime, and a later change only
 * affects subsequently-constructed streams.
 *
 * <h2>Mode semantics</h2>
 *
 * Each mode is a contract about <em>capability</em>, not necessarily
 * about <em>performance</em>:
 *
 * <ul>
 *   <li>{@link Mode#AUTO} — the default. Use the best available on
 *       this machine right now: GPU where it works (D3D11VA on
 *       Windows + zero-copy + GPU tone-map), CPU where it doesn't
 *       (no GPU driver, broken WGL interop, etc.). The runtime
 *       silently falls back; you get the fastest correct path.</li>
 *   <li>{@link Mode#GPU_PREFERRED} — like AUTO but instruments
 *       fallbacks. Prefers GPU, but if any GPU step fails it falls
 *       through to its CPU equivalent without aborting playback.
 *       Useful for "I want GPU when available but never want stream
 *       failure".</li>
 *   <li>{@link Mode#GPU} — strictly require GPU. If hwaccel can't
 *       initialise, the stream fails fast instead of going to CPU.
 *       Use for perf testing or when you want to know your fleet's
 *       actual GPU coverage.</li>
 *   <li>{@link Mode#CPU} — force every layer to its CPU path. Used
 *       on machines with no usable GPU (headless servers, broken
 *       drivers, virtual machines without GPU passthrough). The
 *       full pipeline still works: software libavcodec decode →
 *       Skia raster YUV upload → Skia raster present.</li>
 * </ul>
 */
public final class MediaDecoding {

    /** Decode strategy. */
    public enum Mode {
        /** Best available on this machine. */
        AUTO,
        /** Prefer GPU; transparently fall back to CPU per-layer on failure. */
        GPU_PREFERRED,
        /** Require GPU; fail playback if any GPU layer can't initialise. */
        GPU,
        /** Force software decode + CPU upload everywhere. */
        CPU
    }

    /** System property name: {@value}. */
    public static final String PROPERTY = "skia.media.decode";

    /** Atomic so set() from any thread is visible to readers
     *  immediately. The runtime never holds onto the cached value
     *  across {@code Media} lifetimes — each new stream re-queries. */
    private static final AtomicReference<Mode> CURRENT =
        new AtomicReference<>(parseProperty());

    /** @return the current decode mode (defaults to {@link Mode#AUTO}). */
    public static Mode get() { return CURRENT.get(); }

    /** Set the decode mode for streams constructed after this call.
     *  Null is treated as {@link Mode#AUTO}.
     *
     *  <p>Mirrors {@link javafx.scene.media.Media#setDecodeMethod}: in
     *  addition to caching the value for in-process readers
     *  ({@link #get()}), it writes the {@value #PROPERTY} system
     *  property and propagates the choice to the native plugins via
     *  {@code MediaFfmpegConfig.propagateDecodeMode()}. Without those
     *  two steps the real consumers — {@code SkiaMediaTexture} (reads
     *  the property) and {@code ffmpegwrapper} (reads
     *  {@code OPENJFX_MEDIA_USE_HWACCEL} set by propagate) — would
     *  never see the change, making this a dead switch. */
    public static void set(Mode mode) {
        Mode m = (mode != null) ? mode : Mode.AUTO;
        CURRENT.set(m);
        System.setProperty(PROPERTY, m.name());
        // Push the choice down to native via OPENJFX_MEDIA_USE_HWACCEL
        // so the ffmpeg producer follows suit. Skips silently when
        // jfxmedia.dll isn't loaded yet — the propagation re-runs from
        // MediaFfmpegConfig.initialize() later.
        try {
            MediaFfmpegConfig.propagateDecodeMode();
        } catch (Throwable ignored) {
            // Pre-load — propagation happens on initialize() instead.
        }
    }

    /** Convenience: force software decoding everywhere. */
    public static void useCpu() { set(Mode.CPU); }

    /** Convenience: prefer GPU but allow per-layer CPU fallback. */
    public static void useGpu() { set(Mode.GPU_PREFERRED); }

    /** Convenience: best available (the default). */
    public static void useAuto() { set(Mode.AUTO); }

    /** True when GPU paths are allowed (modes other than {@link Mode#CPU}).
     *  Used by the consumer-side decision points to decide whether to
     *  even <em>attempt</em> the zero-copy / GPU tone-map paths. */
    public static boolean isGpuAllowed() { return CURRENT.get() != Mode.CPU; }

    /** True when GPU is required (mode {@link Mode#GPU}).
     *  When true and a GPU layer fails to initialise, the pipeline
     *  surfaces an error rather than falling back. */
    public static boolean isGpuRequired() { return CURRENT.get() == Mode.GPU; }

    /** True when CPU is the only path (mode {@link Mode#CPU}). */
    public static boolean isCpuOnly() { return CURRENT.get() == Mode.CPU; }

    /** True when fallback from GPU to CPU is OK on individual layer
     *  failures (modes {@link Mode#AUTO} and {@link Mode#GPU_PREFERRED}). */
    public static boolean allowsGpuFallback() {
        Mode m = CURRENT.get();
        return m == Mode.AUTO || m == Mode.GPU_PREFERRED;
    }

    /** One-line human-readable summary for diagnostic logs. */
    public static String describe() {
        Mode m = CURRENT.get();
        switch (m) {
            case AUTO:          return "AUTO (best available, fall through silently)";
            case GPU_PREFERRED: return "GPU_PREFERRED (prefer GPU, fall back per-layer)";
            case GPU:           return "GPU (require GPU; fail fast if unavailable)";
            case CPU:           return "CPU (force software everywhere)";
            default:            return m.toString();
        }
    }

    private static Mode parseProperty() {
        String v = System.getProperty(PROPERTY, "AUTO");
        if (v == null || v.isEmpty()) return Mode.AUTO;
        try {
            return Mode.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignore) {
            // Unknown token → fall back to AUTO. Don't crash on a typo
            // in the user's startup flags.
            return Mode.AUTO;
        }
    }

    private MediaDecoding() {}
}
