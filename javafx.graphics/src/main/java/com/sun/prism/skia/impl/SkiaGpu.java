package com.sun.prism.skia.impl;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.MemorySegment;

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
                + "software raster (prism.skia.gpu=true requested but the "
                + "Ganesh GL backend is unavailable on this platform/build).");
            enabled = Boolean.FALSE;
            return false;
        }
        NativeBridge.surfaceDestroy(h);
        LOG.log(Level.INFO, "Skia GPU (Ganesh GL) enabled.");
        enabled = Boolean.TRUE;
        return true;
    }
}
