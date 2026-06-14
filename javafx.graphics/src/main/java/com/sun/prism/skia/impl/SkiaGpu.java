package com.sun.prism.skia.impl;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.MemorySegment;
import java.util.Locale;

import javafx.application.Application;
import javafx.application.Application.GpuBackend;

/**
 * Phase-2 GPU mode toggle.
 *
 * <p>Probes the native bridge once by trying to create a 1×1 GPU
 * surface. If the probe succeeds GPU mode is on for the lifetime of
 * the process; if the probe fails — no GL driver, no Ganesh build — the
 * pipeline falls back to software raster with a one-shot warning. The
 * probe is cached so the steady-state cost is one volatile read per
 * {@code SkiaRTTexture} construction.</p>
 *
 * <p><b>Default: GPU on when probe succeeds.</b> Pass
 * {@code -Dprism.skia.gpu=false} to force software raster (useful for
 * regression debugging or constrained environments).</p>
 */
public final class SkiaGpu {

    private static final Logger LOG = System.getLogger(SkiaGpu.class.getName());

    /** Cached probe result; null until {@link #probe} runs. */
    private static volatile Boolean enabled;

    private SkiaGpu() {}

    /** True if GPU-backed surfaces are available for this process. */
    public static boolean isEnabled() {
        Boolean cached = enabled;
        return cached != null ? cached : probe();
    }

    /**
     * True only if the process has <em>already</em> resolved to the
     * software-raster fallback. Reads the cached probe result with no
     * side effects: it never triggers {@link #probe()} and so never
     * issues a native GPU surface call. Safe to call from any thread —
     * including the FX/toolkit thread, where forcing a probe (a
     * render-thread operation) would be incorrect.
     *
     * <p>Returns {@code true} once {@code -Dprism.skia.gpu=false} has been
     * observed, or once the render thread has run a failing probe; returns
     * {@code false} while the decision is still pending or GPU is enabled.</p>
     */
    public static boolean isResolvedSoftware() {
        return Boolean.FALSE.equals(enabled);
    }

    /**
     * True only if the process has already resolved to GPU-backed
     * surfaces. Side-effect-free counterpart to {@link #isResolvedSoftware()}
     * (never triggers {@link #probe()}); lets callers cheaply short-circuit
     * the GPU steady state with a single volatile read. The resolved GPU
     * state never flips back to software for the lifetime of the process.
     */
    public static boolean isResolvedGpu() {
        return Boolean.TRUE.equals(enabled);
    }

    private static synchronized boolean probe() {
        Boolean cached = enabled;
        if (cached != null) return cached;

        // GPU is on by default (Phase-2 increment 2 lands direct-present,
        // so GPU is unambiguously faster than software). Set
        // -Dprism.skia.gpu=false to force the software-raster path.
        String prop = System.getProperty("prism.skia.gpu");
        if (prop != null && prop.equalsIgnoreCase("false")) {
            enabled = Boolean.FALSE;
            return false;
        }

        // Select the GPU backend BEFORE the first GPU surface triggers the
        // GrDirectContext build. An unavailable/unsupported request degrades to
        // the most suitable backend for the platform (see resolveBackendPref).
        GpuBackend requested = resolveRequestedBackend();
        NativeBridge.setGpuBackend(toNativePref(requested));

        MemorySegment h;
        try {
            h = NativeBridge.surfaceCreateGpu(1, 1);
        } catch (Throwable t) {
            LOG.log(Level.WARNING,
                "Skia GPU init failed; falling back to software raster.", t);
            enabled = Boolean.FALSE;
            return false;
        }
        if (h == null || h.equals(MemorySegment.NULL)) {
            LOG.log(Level.WARNING,
                "Skia GPU surface creation returned NULL; falling back to "
                + "software raster (prism.skia.gpu=true requested but no GPU "
                + "backend is available on this platform/build).");
            enabled = Boolean.FALSE;
            return false;
        }
        NativeBridge.surfaceDestroy(h);

        // Report the backend that is ACTUALLY active (queried from native), not a
        // hardcoded name — D3D init can fall back to GL, and AUTO is resolved natively.
        int active = NativeBridge.activeBackend();
        String activeName = backendName(active);
        LOG.log(Level.INFO, "Skia GPU enabled: " + activeName + ".");
        if (requested != GpuBackend.AUTO && !matchesActive(requested, active)) {
            LOG.log(Level.INFO, "Requested GPU backend " + requested
                + " was not available; using " + activeName + " instead.");
        }
        enabled = Boolean.TRUE;
        return true;
    }

    /**
     * The backend the app asked for: the explicit {@link Application#getGpuBackend()}
     * choice, else the {@code -Dprism.skia.gpu.backend} system property, else AUTO.
     */
    private static GpuBackend resolveRequestedBackend() {
        GpuBackend req = Application.getGpuBackend();
        if (req == null) req = GpuBackend.AUTO;
        if (req == GpuBackend.AUTO) {
            String p = System.getProperty("prism.skia.gpu.backend");
            if (p != null) req = parseBackend(p);
        }
        return req;
    }

    private static GpuBackend parseBackend(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "gl", "opengl", "ganesh"            -> GpuBackend.OPENGL;
            case "d3d", "d3d12", "direct3d",
                 "direct3d12", "dx12"                -> GpuBackend.DIRECT3D12;
            case "metal", "mtl"                      -> GpuBackend.METAL;
            case "vulkan", "vk"                      -> GpuBackend.VULKAN;
            default                                  -> GpuBackend.AUTO;
        };
    }

    /**
     * Map a requested backend to a native preference code, degrading to the most
     * suitable backend when the request is not supported on this platform/build.
     * Backends without a native path yet (Metal, Vulkan) resolve to AUTO so the
     * native side picks the platform default rather than failing.
     */
    private static int toNativePref(GpuBackend req) {
        boolean windows = osIs("win");
        return switch (req) {
            case OPENGL     -> NativeBridge.BACKEND_GL;
            case DIRECT3D12 -> windows ? NativeBridge.BACKEND_D3D12 : NativeBridge.BACKEND_AUTO;
            case METAL      -> NativeBridge.BACKEND_AUTO;   // roadmap → most suitable
            case VULKAN     -> NativeBridge.BACKEND_AUTO;   // roadmap → most suitable
            case AUTO       -> NativeBridge.BACKEND_AUTO;
        };
    }

    private static boolean matchesActive(GpuBackend req, int active) {
        return switch (req) {
            case OPENGL     -> active == NativeBridge.BACKEND_GL;
            case DIRECT3D12 -> active == NativeBridge.BACKEND_D3D12;
            case METAL      -> active == NativeBridge.BACKEND_METAL;
            case VULKAN     -> active == NativeBridge.BACKEND_VULKAN;
            case AUTO       -> true;
        };
    }

    private static String backendName(int code) {
        return switch (code) {
            case NativeBridge.BACKEND_GL     -> "OpenGL (Ganesh GL)";
            case NativeBridge.BACKEND_D3D12  -> "Direct3D 12";
            case NativeBridge.BACKEND_METAL  -> "Metal";
            case NativeBridge.BACKEND_VULKAN -> "Vulkan";
            default                          -> "GPU";
        };
    }

    private static boolean osIs(String token) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains(token);
    }
}
