package com.sun.prism.skia;

import com.sun.javafx.geom.Shape;
import com.sun.prism.Graphics;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.prism.skia.impl.PathEncoder;

import java.lang.foreign.MemorySegment;

/**
 * Public hook for external modules (today: javafx.web) to interact with
 * the native SkSurface that backs a {@link Graphics} produced by the
 * Skia pipeline.
 *
 * <p>The {@link #handleOf(Graphics)} accessor returns a
 * {@code uintptr_t}-sized pointer to a heap-allocated native surface
 * record (or {@code 0} for non-Skia pipelines). The handle is stable
 * for the lifetime of the issuing {@code Graphics} and becomes invalid
 * once the underlying RTTexture is disposed.</p>
 *
 * <p>The {@code save / clipPath / restore} helpers let callers drive
 * the live SkCanvas's state stack without recursive surface allocation.
 * Used by {@code WCGraphicsPrismContext.SkiaClipLayer} so that WebKit
 * sees pixel-precise rounded-rect / SVG / clip-path clipping on Skia
 * surfaces, where the legacy ClipLayer's readback + composite path
 * crashes (see memory note webview-open-followups #12).</p>
 *
 * <p>This indirection exists because {@link SkiaGraphics} and
 * {@link NativeBridge} are not exported. Only the few accessors the
 * WebKit / WebView integration needs are surfaced here.</p>
 */
public final class SkiaSurfaceAccess {

    private SkiaSurfaceAccess() { /* no instances */ }

    /**
     * Returns the SkSurface handle backing {@code g} if it is a
     * Skia-backed Graphics; returns {@code 0L} otherwise.
     */
    public static long handleOf(Graphics g) {
        return (g instanceof SkiaGraphics sg) ? sg.getSurfaceHandle() : 0L;
    }

    /**
     * Push a save level onto the canvas state stack for the surface
     * identified by {@code skSurfaceHandle}. Must be paired with a
     * matching {@link #restoreSurfaceState} call.
     */
    public static void saveSurfaceState(long skSurfaceHandle) {
        if (skSurfaceHandle == 0L) return;
        NativeBridge.surfaceSave(MemorySegment.ofAddress(skSurfaceHandle));
    }

    /**
     * Pop one save level from the canvas state stack of the surface
     * identified by {@code skSurfaceHandle}. Pairs with
     * {@link #saveSurfaceState}.
     */
    public static void restoreSurfaceState(long skSurfaceHandle) {
        if (skSurfaceHandle == 0L) return;
        NativeBridge.surfaceRestore(MemorySegment.ofAddress(skSurfaceHandle));
    }

    /**
     * Push a Skia saveLayer with alpha-only composition. Subsequent
     * draws on the surface go into an internal layer that Skia manages;
     * the matching {@link #restoreSurfaceState} composites the layer
     * back with the given opacity.
     *
     * <p>This is the Chrome-grade primitive for CSS {@code opacity} /
     * WebKit {@code beginTransparencyLayer} — Skia owns the off-screen
     * layer, so we avoid the recursive SkSurface allocation +
     * surface_draw_surface composite that crashes on the legacy
     * {@code TransparencyLayer} path (memory note
     * webview-open-followups #12).</p>
     *
     * @param skSurfaceHandle the handle from {@link #handleOf}
     * @param opacity in [0, 1]; clamped to that range
     */
    public static void saveSurfaceLayerAlpha(long skSurfaceHandle, float opacity) {
        if (skSurfaceHandle == 0L) return;
        int alpha = Math.round(Math.max(0f, Math.min(1f, opacity)) * 255f);
        NativeBridge.surfaceSaveLayerAlpha(
            MemorySegment.ofAddress(skSurfaceHandle), alpha);
    }

    /**
     * Fill {@code shape} (translated by {@code dx,dy}) with a gaussian-blurred
     * edge — the native primitive behind WebView CSS {@code box-shadow}. No-op
     * unless {@code g} is a Skia-backed Graphics. Draws straight on the live
     * SkCanvas (honoring the current transform + clip), bypassing the Prism
     * DropShadow effect whose intermediate-surface composite is broken on Skia
     * (hard / missing shadows).
     *
     * @param g      a Skia-backed Graphics
     * @param shape  the shadow geometry, in user space (pre-offset)
     * @param dx     X offset applied to the shape before blurring
     * @param dy     Y offset applied to the shape before blurring
     * @param sigma  SkMaskFilter blur sigma (≈ CSS blur radius / 2)
     * @param argb   shadow color packed as 0xAARRGGBB
     */
    public static void drawShapeShadow(Graphics g, Shape shape,
                                       float dx, float dy, float sigma, int argb) {
        if (shape != null && g instanceof SkiaGraphics sg) {
            sg.fillShapeBlur(shape, dx, dy, sigma, argb);
        }
    }

    /**
     * Intersect the current clip with {@code path} on the canvas of the
     * surface identified by {@code skSurfaceHandle}. The clip persists
     * until the matching {@link #restoreSurfaceState} call unwinds the
     * save level it was applied at — so callers must bracket this with
     * {@code saveSurfaceState} / {@code restoreSurfaceState}.
     *
     * @param skSurfaceHandle the handle from {@link #handleOf}
     * @param shape the clip geometry, in surface coordinates
     * @param translateDx X translation applied to the path before clip
     * @param translateDy Y translation applied to the path before clip
     */
    public static void clipPath(long skSurfaceHandle, Shape shape,
                                float translateDx, float translateDy) {
        if (skSurfaceHandle == 0L || shape == null) return;
        MemorySegment handle = MemorySegment.ofAddress(skSurfaceHandle);
        try (PathEncoder.Encoded enc =
                 PathEncoder.encodeTranslated(shape, translateDx, translateDy)) {
            NativeBridge.surfaceClipPath(handle,
                enc.verbs, enc.verbCount,
                enc.coords, enc.coordCount,
                enc.fillRule,
                /*clipOp=intersect*/ 0);
        }
    }

    /**
     * Composite a raw BGRA8888 (premultiplied, top-down) pixel buffer onto the
     * live SkCanvas of {@code skSurfaceHandle}, scaling {@code srcW x srcH}
     * into the destination rect. The canvas already carries the WebView node's
     * transform / clip / opacity, so the frame composites like any scene node.
     * The pixels are copied into a Skia-managed image, so {@code pixels} (e.g.
     * a shared-memory frame slot) may be reused immediately. No-op when the
     * handle is 0 (non-Skia pipeline). Render-thread only.
     *
     * <p>This is the in-process path for the Blink WebView's off-screen frames:
     * one Skia, one {@code GrDirectContext}, one copy.</p>
     *
     * @param skSurfaceHandle handle from {@link #handleOf}
     * @param pixels native address of the BGRA buffer
     */
    public static void drawBgraFrame(Graphics g, long pixels,
                                     int srcW, int srcH, int srcStride) {
        drawBgraFrame(g, pixels, srcW, srcH, srcStride, 0, 0);
    }

    /**
     * As {@link #drawBgraFrame(Graphics, long, int, int, int)} but stretches the
     * frame to {@code dstLogicalW × dstLogicalH} logical px (the WebView node's
     * size). Use this for the Blink WebView so a capture that was downscaled to
     * fit a shared-memory slot still fills the whole node — otherwise the
     * bottom/right of the page (e.g. a footer) is left unrendered.
     */
    public static void drawBgraFrame(Graphics g, long pixels,
                                     int srcW, int srcH, int srcStride,
                                     int dstLogicalW, int dstLogicalH) {
        if (pixels == 0L || !(g instanceof SkiaGraphics sg)) return;
        // Goes through SkiaGraphics.drawBgra → syncBeforeDraw, so the node's
        // transform (position!) + clip + pixel scale apply, exactly like every
        // other scene draw. Drawing on the bare canvas (ignoring the transform)
        // put the frame at the surface origin — a WebView offset below other
        // content then rendered + hit-tested too high (clicks missed).
        sg.drawBgra(pixels, srcW, srcH, srcStride, dstLogicalW, dstLogicalH);
    }

    /**
     * Draws a BGRA overlay (e.g. an OSR {@code <select>}/colour popup) on top of
     * what the node already drew, at node-local ({@code dstX},{@code dstY}) with
     * logical size {@code dstLogicalW × dstLogicalH}. Translates the node's
     * transform so the overlay lands at the offset, then restores it. Render-thread
     * only; no-op on non-Skia pipelines.
     */
    public static void drawBgraOverlay(Graphics g, long pixels,
                                       int srcW, int srcH, int srcStride,
                                       double dstX, double dstY,
                                       int dstLogicalW, int dstLogicalH) {
        if (pixels == 0L || !(g instanceof SkiaGraphics sg)) return;
        // Translate, draw, then translate back by the exact negatives — restores
        // the node transform with NO allocation (vs getTransformNoClone().copy(),
        // which would allocate a BaseTransform every overlay frame on the render
        // thread; CLAUDE.md mandates zero per-frame render-thread allocation).
        final float tx = (float) dstX;
        final float ty = (float) dstY;
        g.translate(tx, ty);
        try {
            sg.drawBgra(pixels, srcW, srcH, srcStride, dstLogicalW, dstLogicalH);
        } finally {
            g.translate(-tx, -ty);
        }
    }
}
