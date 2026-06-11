/*
 * MediaFfmpegConfig — Java entry point for telling the native side
 * where to find ffmpeg's DLLs.
 *
 * skia-fx loads ffmpeg dynamically at runtime (no link-time dep).
 * Applications that want to use the ffmpeg decoder set a path:
 *
 *     com.sun.media.jfxmediaimpl.MediaFfmpegConfig.initialize("C:/ffmpeg/bin");
 *
 * Or rely on automatic resolution from the OPENJFX_MEDIA_FFMPEG_DIR
 * environment variable / the openjfx.media.ffmpeg.dir system property
 * (this class reads both as fallbacks if no explicit path is given).
 *
 * Safe to call before any MediaPlayer is created. Idempotent — once
 * ffmpeg is loaded successfully, subsequent calls are no-ops.
 */
package com.sun.media.jfxmediaimpl;

public final class MediaFfmpegConfig {
    private MediaFfmpegConfig() {}

    private static native boolean nativeInit(String userDir);
    /** Sets a process-wide environment variable from Java.
     *  Used by {@link #propagateDecodeMode} to push the decode-mode
     *  choice down to the native gstreamer plugins that look it up
     *  via getenv at decoder open. {@code null}/empty value removes
     *  the variable. See the JNI side in MediaFfmpegConfig.cpp. */
    private static native void nativeSetEnv(String name, String value);

    private static volatile Boolean initialized;
    // Directory of the last FAILED attempt. Success latches forever, but
    // a failure must be retryable with a NEW directory — the failure
    // message itself tells the user to call Media.setFfmpegDirectory()
    // and try again, which would be a lie if the first failure latched.
    private static volatile String lastFailedDir;

    /**
     * Best-effort load of ffmpeg DLLs.
     *
     * @param userDir directory containing avcodec-*.dll, avutil-*.dll etc.;
     *                {@code null} to defer to PATH / env vars
     * @return {@code true} when ffmpeg is now available for the
     *         ffmpegwrapper decoder; {@code false} when ffmpeg wasn't
     *         found or has the wrong ABI. Callers can ignore the
     *         result if they're happy to fall through to mfwrapper.
     */
    public static boolean initialize(String userDir) {
        Boolean done = initialized;
        if (done != null && done.booleanValue()) return true;
        String dir = userDir;
        String src = "argument";
        if (dir == null || dir.isBlank()) {
            dir = System.getProperty("openjfx.media.ffmpeg.dir");
            src = "system property";
        }
        if (dir == null || dir.isBlank()) {
            dir = System.getenv("OPENJFX_MEDIA_FFMPEG_DIR");
            src = "env var";
        }
        if (done != null) {
            // Previous attempt failed: retry only when pointed at a NEW
            // directory (setFfmpegDirectory / property changed since).
            String failed = lastFailedDir;
            if (dir == null || dir.isBlank()
                    || (failed != null && failed.equals(dir))) {
                return false;
            }
        }
        if (isDebugEnabled()) {
            System.err.println("[MediaFfmpegConfig] resolved ffmpeg dir from " + src
                + ": " + (dir == null ? "(null)" : dir));
        }

        // Prime jfxmedia.dll. The JNI symbol nativeInit lives inside
        // jfxmedia, which NativeMediaManager loads in its constructor.
        // When initialize() runs from Application.init() — i.e. before
        // any Media/MediaPlayer is touched — jfxmedia hasn't been
        // System.load'ed yet, so a direct nativeInit call would throw
        // UnsatisfiedLinkError. Reading the manager's singleton field
        // is enough to trigger the static-init path that loads
        // glib-lite + gstreamer-lite + jfxmedia.
        try {
            NativeMediaManager.getDefaultInstance();
        } catch (Throwable ignored) {
            // If the manager itself fails to initialise we'll just hit
            // the link error below and return false.
        }

        // Prime the Skia D3D11 interop now (on whichever thread called
        // initialize — typically the FX-launcher / FX thread). The
        // producer plugin (ffmpegwrapper) will then be able to BORROW
        // the interop's D3D11 device when it spins up later on its
        // own gstreamer thread — single-device sharing eliminates the
        // cross-device sync race that otherwise causes occasional
        // flicker on the WGL_NV_DX_interop2 read path.
        try {
            Class<?> nb = Class.forName(
                "com.sun.prism.skia.impl.NativeBridge");
            nb.getMethod("d3d11InteropInit").invoke(null);
        } catch (Throwable ignored) {
            // Not fatal — producer falls back to its own D3D11 device.
        }

        // Push the high-level decode-mode choice down to the native
        // plugins via OPENJFX_MEDIA_USE_HWACCEL. This is what makes
        // {@link javafx.application.Application#setDecodeMethod
        // setDecodeMethod}(CPU) actually disable hardware decode end-
        // to-end. Without this step the Java consumer would refuse
        // zero-copy (so no green-frame bug) but the producer would
        // still emit D3D11 platform textures whose YUV planes are
        // placeholder garbage — frames would simply drop.
        propagateDecodeMode();

        try {
            boolean ok = nativeInit(dir);
            if (!ok) {
                lastFailedDir = dir;
            }
            initialized = ok;
        } catch (UnsatisfiedLinkError ule) {
            System.err.println("[MediaFfmpegConfig] nativeInit not bound: " + ule);
            return false;
        }
        return initialized;
    }

    /**
     * Translates the {@code skia.media.decode} system property into the
     * legacy {@code OPENJFX_MEDIA_USE_HWACCEL} env var that the gstreamer
     * plugins read at decoder open. CPU mode → env var "false"; every
     * other mode → env var unset (let the existing detection logic pick).
     *
     * <p>Public so applications that bypass {@link #initialize} (rare —
     * the launcher normally calls it) can still trigger the propagation
     * explicitly. Idempotent. Safe to call from any thread before
     * decoder open.</p>
     */
    public static void propagateDecodeMode() {
        String mode = System.getProperty("skia.media.decode", "AUTO");
        if (mode == null) mode = "AUTO";
        boolean cpu = "CPU".equalsIgnoreCase(mode.trim());

        // Capture the JVM-launch env value BEFORE the first time we
        // touch it — that's the user's baseline. Subsequent toggles
        // restore to this rather than blindly unset, so an app launched
        // with -Denv=OPENJFX_MEDIA_USE_HWACCEL=false (or the gradle
        // launcher's `-PuseHwaccel=false`) keeps that intent on a
        // mode switch back to AUTO.
        String desired;
        if (cpu) {
            desired = "false";
        } else {
            // Switching away from CPU mode: restore the original
            // launch-time value (typically null = unset = HW allowed).
            desired = LAUNCH_TIME_HW_ENV;
        }

        try {
            // desired == null clears the variable from the process
            // env block; non-null sets it. Either way the change is
            // visible to every loaded DLL via GetEnvironmentVariableA.
            nativeSetEnv("OPENJFX_MEDIA_USE_HWACCEL", desired);
            if (isDebugEnabled()) {
                System.err.println("[MediaFfmpegConfig] decode=" + mode
                    + " → OPENJFX_MEDIA_USE_HWACCEL="
                    + (desired != null ? desired : "(unset)"));
            }
        } catch (UnsatisfiedLinkError ule) {
            // Older jfxmedia.dll without nativeSetEnv — accept the
            // gap. Java side still disables zero-copy when CPU mode
            // is set; only the ffmpeg HW path will keep running.
            // This warning is always printed because it indicates a
            // capability gap a user should know about.
            System.err.println(
                "[MediaFfmpegConfig] nativeSetEnv not available; "
                + "OPENJFX_MEDIA_USE_HWACCEL must be set at JVM launch "
                + "for full CPU-mode coverage.");
        }
    }

    /** Aggregate verbose-log gate. Same semantics as
     *  {@code SkiaMediaTexture.isMediaDebug}: returns true when
     *  {@code SKIA_MEDIA_DEBUG} env var is set or
     *  {@code -Dskia.media.debug=true} is on the command line. */
    private static boolean isDebugEnabled() {
        if (Boolean.getBoolean("skia.media.debug")) return true;
        String env = System.getenv("SKIA_MEDIA_DEBUG");
        return env != null && !env.isEmpty() && !"0".equals(env)
                && !"false".equalsIgnoreCase(env);
    }

    /** Captured at class load — the original launch-time value of
     *  {@code OPENJFX_MEDIA_USE_HWACCEL} that runtime mode-switches
     *  restore to when leaving CPU mode. {@code null} when the env
     *  var was unset at launch (the common case). */
    private static final String LAUNCH_TIME_HW_ENV =
        System.getenv("OPENJFX_MEDIA_USE_HWACCEL");

    /** Whether a previous {@link #initialize} call succeeded. */
    public static boolean isAvailable() {
        return initialized != null && initialized.booleanValue();
    }

    /**
     * The native loader's human-readable status — where ffmpeg loaded
     * from and which versions, or precisely why the load failed (ABI
     * mismatch, mixed builds, missing DLLs). {@code null} when no load
     * has been attempted yet.
     */
    public static String getStatus() {
        if (initialized == null) {
            return null;
        }
        try {
            return nativeGetStatus();
        } catch (UnsatisfiedLinkError ule) {
            return null;
        }
    }

    private static native String nativeGetStatus();
}
