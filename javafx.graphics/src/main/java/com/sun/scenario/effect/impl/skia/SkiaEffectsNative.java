/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import com.sun.prism.skia.impl.NativeBridge;

/**
 * Downcalls into {@code openjfx_skia_effects_ext.cpp} (runtime-effect image
 * filters for the DisplacementMap and PhongLighting peers).
 *
 * <p>Every symbol is looked up OPTIONALLY: an older native library without
 * these entry points leaves the handles {@code null}, the {@code isAvailable}
 * checks return false, and the peers degrade to their passthrough path
 * instead of failing to link.</p>
 *
 * <p>Handles returned are ordinary bridge image / filter handles, released
 * through {@link NativeBridge#imageDestroy} / {@link NativeBridge#filterDestroy}.</p>
 */
final class SkiaEffectsNative {

    private static final Linker LINKER = Linker.nativeLinker();

    private static final MethodHandle MH_FLOATMAP_IMAGE;
    private static final MethodHandle MH_DISPLACEMENT_FILTER;
    private static final MethodHandle MH_PHONG_FILTER;

    static {
        SymbolLookup lookup = bridgeLookup();
        MethodHandle fm = null, disp = null, phong = null;
        if (lookup != null) {
            // Heap access allowed: the FloatMap's float[] is passed straight
            // through (the native side makes the one sanitizing copy).
            fm = lookup.find("openjfx_skia_fx_floatmap_image")
                .map(s -> LINKER.downcallHandle(s,
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                    Linker.Option.critical(true)))
                .orElse(null);
            FunctionDescriptor filterDesc = FunctionDescriptor.of(ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
            disp = lookup.find("openjfx_skia_fx_displacement_filter")
                .map(s -> LINKER.downcallHandle(s, filterDesc))
                .orElse(null);
            phong = lookup.find("openjfx_skia_fx_phong_filter")
                .map(s -> LINKER.downcallHandle(s, filterDesc))
                .orElse(null);
        }
        MH_FLOATMAP_IMAGE = fm;
        MH_DISPLACEMENT_FILTER = disp;
        MH_PHONG_FILTER = phong;
    }

    private SkiaEffectsNative() { }

    /**
     * The bridge library is already loaded by {@link NativeBridge} (dev path,
     * explicit override or jar extraction). Reuse its exact lookup so we bind
     * against the same DLL; fall back to the class-loader lookup (jar path).
     */
    private static SymbolLookup bridgeLookup() {
        try {
            SymbolLookup sl = NativeBridge.lookup();
            if (sl != null) {
                return sl;
            }
        } catch (RuntimeException | LinkageError e) {
            // fall through
        }
        try {
            return SymbolLookup.loaderLookup();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean isDisplacementAvailable() {
        return MH_FLOATMAP_IMAGE != null && MH_DISPLACEMENT_FILTER != null;
    }

    static boolean isPhongAvailable() {
        return MH_PHONG_FILTER != null;
    }

    private static MemorySegment orNull(MemorySegment s) {
        return (s == null || s.equals(MemorySegment.NULL)) ? null : s;
    }

    /** Uploads {@code data} (w*h*4 floats) as a raw F32 image. Null on failure. */
    static MemorySegment floatMapImage(float[] data, int w, int h) {
        if (MH_FLOATMAP_IMAGE == null || data == null
                || data.length < (long) w * h * 4) {
            return null;
        }
        try {
            MemorySegment heap = MemorySegment.ofArray(data);
            return orNull((MemorySegment) MH_FLOATMAP_IMAGE.invokeExact(heap, w, h));
        } catch (Throwable t) {
            return null;
        }
    }

    /** See openjfx_skia_effects_ext.h. Null on failure. */
    static MemorySegment displacementFilter(MemorySegment mapImage,
                                            MemorySegment params, int count) {
        if (MH_DISPLACEMENT_FILTER == null || mapImage == null) return null;
        try {
            return orNull((MemorySegment) MH_DISPLACEMENT_FILTER.invokeExact(
                mapImage, params, count));
        } catch (Throwable t) {
            return null;
        }
    }

    /** See openjfx_skia_effects_ext.h. Null on failure. */
    static MemorySegment phongFilter(MemorySegment bumpImage,
                                     MemorySegment params, int count) {
        if (MH_PHONG_FILTER == null || bumpImage == null) return null;
        try {
            return orNull((MemorySegment) MH_PHONG_FILTER.invokeExact(
                bumpImage, params, count));
        } catch (Throwable t) {
            return null;
        }
    }
}
