package com.sun.prism.skia;

import com.sun.glass.ui.Screen;
import com.sun.javafx.font.*;
import com.sun.javafx.geom.RectBounds;
import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.Shape;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.geom.transform.GeneralTransform3D;
import com.sun.javafx.scene.text.GlyphList;
import com.sun.javafx.sg.prism.NGLightBase;
import com.sun.prism.BasicStroke;
import com.sun.prism.CompositeMode;
import com.sun.prism.RTTexture;
import com.sun.prism.ReadbackGraphics;
import com.sun.prism.RenderTarget;
import com.sun.prism.ResourceFactory;
import com.sun.prism.Texture;
import com.sun.prism.impl.BaseGraphics;
import com.sun.prism.paint.Color;
import com.sun.prism.paint.Gradient;
import com.sun.prism.paint.ImagePattern;
import com.sun.prism.paint.Paint;
import com.sun.prism.paint.Stop;
import com.sun.prism.skia.impl.FrameArena;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.prism.skia.impl.PathEncoder;
import com.sun.prism.skia.impl.SkiaGpu;
import com.sun.prism.skia.impl.SkiaShaders;
import com.sun.prism.skia.impl.SoftwareGradientCache;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

/**
 * Skia-backed {@link com.sun.prism.Graphics}.
 *
 * <p>Extends {@link BaseGraphics} for transform / clip / paint / state
 * bookkeeping. The actual rasterization happens on {@code SkCanvas} via
 * the native bridge — Prism's GPU validation/vertex-buffer code paths
 * are bypassed by overriding every draw method (the BaseGraphics
 * constructor accepts a {@code null} context for this very reason).</p>
 *
 * <p>Each draw method applies the current Skia state — transform from
 * {@link #getTransformNoClone()}, clip from {@link #getClipRect()},
 * composite mode from {@link #getCompositeMode()}, extra alpha from
 * {@link #getExtraAlpha()} — via {@link #syncBeforeDraw()} / restore
 * around the actual draw call. Paint dispatch picks color, gradient,
 * or image-pattern shader based on the {@link Paint} subtype.</p>
 */
public final class SkiaGraphics extends BaseGraphics implements ReadbackGraphics {

    private final SkiaResourceFactory factory;
    private final SkiaRTTexture target;
    private final long surfaceHandle;
    // Cached once at construction; reused for every per-draw native
    // call so we don't allocate a new MemorySegment wrapper on each
    // crossing (~1,000–1,500 wrappers/frame previously).
    private final MemorySegment surface;

    SkiaGraphics(SkiaResourceFactory factory, SkiaRTTexture target) {
        super(null /* no BaseContext — we route to SkCanvas directly */, target);
        this.factory = factory;
        this.target = target;
        this.surfaceHandle = target.getNativeHandle();
        if (this.surfaceHandle == 0L) {
            throw new IllegalStateException("RTTexture is disposed; cannot create Graphics");
        }
        this.surface = MemorySegment.ofAddress(this.surfaceHandle);
    }

    /** Cached native handle wrapper. Stable for the lifetime of this Graphics. */
    private MemorySegment handle() {
        return surface;
    }

    /**
     * Returns the native SkSurface handle ({@code uintptr_t}) backing
     * this Graphics. Package-private; cross-module callers go through
     * {@link SkiaSurfaceAccess#handleOf(com.sun.prism.Graphics)}.
     */
    long getSurfaceHandle() {
        return surfaceHandle;
    }

    // ---- Render-target wiring (override BaseGraphics's context.* paths) ---

    @Override public RenderTarget getRenderTarget()         { return target; }
    @Override public ResourceFactory getResourceFactory()   { return factory; }
    @Override public Screen getAssociatedScreen()           { return target.getAssociatedScreen(); }
    @Override public void setPerspectiveTransform(GeneralTransform3D transform) { /* 2D */ }
    @Override public void sync() { /* SkSurface CPU writes are immediate */ }

    // ---- Per-draw state synchronization -----------------------------------
    //
    // Single batched native call (surfaceBeginDraw) pushes save + clip
    // + matrix + sticky blend/alpha in one crossing; surfaceEndDraw
    // pops the save level. The clip is applied in device coords inside
    // the save block before the matrix is installed — preserves the
    // TableView/ScrollPane clipping invariant.

    // Per-Graphics blend-mode override for effect peers that need a Skia
    // SkBlendMode the Prism CompositeMode enum can't express (SRC_ATOP,
    // MULTIPLY, SCREEN, ...). -1 means "no override — use the CompositeMode".
    // Set directly via surfaceSetBlendMode the sticky native state is
    // immediately clobbered by the next surfaceBeginDraw (which always writes
    // its own blend arg), so a peer must route the mode THROUGH syncBeforeDraw.
    // Sticky until the peer resets it (try/finally), so it survives however
    // many internal draws renderImageData issues for one input.
    private int peerBlendOverride = -1;

    /**
     * Forces the SkBlendMode used by subsequent draws on this Graphics until
     * cleared with {@code -1}. Used by {@link com.sun.scenario.effect.impl.skia.SkiaBlendPeer}
     * to composite the top input with an arbitrary {@code SkBlendMode}.
     * Callers MUST reset to {@code -1} in a finally block.
     */
    public void setPeerBlendOverride(int skBlendMode) {
        this.peerBlendOverride = skBlendMode;
    }

    private void syncBeforeDraw() {
        syncBeforeDraw(peerBlendOverride >= 0
            ? peerBlendOverride
            : mapBlendMode(getCompositeMode()));
    }

    /**
     * As {@link #syncBeforeDraw()} but forces a specific Skia blend mode,
     * overriding the current {@code CompositeMode}. Used by {@link #clearQuad}
     * which must erase with CLEAR regardless of the active composite.
     */
    private void syncBeforeDraw(int blendMode) {
        BaseTransform t = getTransformNoClone();
        float sx = getPixelScaleFactorX();
        float sy = getPixelScaleFactorY();

        // getClipRectNoClone avoids the per-draw Rectangle clone that
        // BaseGraphics.getClipRect would do (~500 alloc/frame).
        //
        // skia-fx HiDPI: the Skia pipeline keeps the pixel scale OUT of the
        // Graphics transform (it's multiplied into the CTM just below, per draw),
        // so getClipRectNoClone() is in LOGICAL coords. Native applies this clip
        // under an IDENTITY CTM in DEVICE pixel space ("clip before matrix"), so
        // it must be scaled by the pixel scale here too — otherwise clipped /
        // scrollable content (ScrollPane, TableView, ListView, any -fx-clip) is
        // cut to 1/scale of its size on a 150%/175% monitor (the "content fills
        // only a piece" bug). Floor origin / ceil extent so the clip never shaves
        // a fraction of a device pixel off real content. No-op at 100% (sx=sy=1).
        Rectangle clip = getClipRectNoClone();
        boolean hasClip = (clip != null);
        int clipX = 0, clipY = 0, clipW = 0, clipH = 0;
        if (hasClip) {
            if (sx != 1f || sy != 1f) {
                clipX = (int) Math.floor(clip.x * sx);
                clipY = (int) Math.floor(clip.y * sy);
                clipW = (int) Math.ceil((clip.x + clip.width)  * sx) - clipX;
                clipH = (int) Math.ceil((clip.y + clip.height) * sy) - clipY;
            } else {
                clipX = clip.x;
                clipY = clip.y;
                clipW = clip.width;
                clipH = clip.height;
            }
        }

        NativeBridge.surfaceBeginDraw(surface,
            (float) t.getMxx() * sx, (float) t.getMxy() * sx, (float) t.getMxt() * sx,
            (float) t.getMyx() * sy, (float) t.getMyy() * sy, (float) t.getMyt() * sy,
            clipX, clipY, clipW, clipH, hasClip,
            blendMode,
            getExtraAlpha());
    }


    private void restoreAfterDraw() {
        NativeBridge.surfaceEndDraw(surface);
    }

    // ---- Clear -------------------------------------------------------------

    @Override
    public void clear() {
        if (target.isOpaque()) {
            clear(Color.BLACK);
        } else {
            clear(Color.TRANSPARENT);
        }
    }

    @Override
    public void clear(Color color) {
        syncBeforeDraw();
        try {
            NativeBridge.surfaceClear(handle(),
                c8(color.getRed()), c8(color.getGreen()),
                c8(color.getBlue()), c8(color.getAlpha()));
        } finally { restoreAfterDraw(); }
    }

    @Override
    public void clearQuad(float x1, float y1, float x2, float y2) {
        // Erase the region to transparent with the CLEAR blend mode (replace,
        // not SRC_OVER). A transparent SRC_OVER fill is a no-op, so the old
        // code left the surface's default opaque-black showing through — e.g.
        // black boxes behind WebView/Canvas form inputs. Going through
        // syncBeforeDraw/restoreAfterDraw also honors the current transform
        // and clip, which the bare fillRect ignored.
        int x = (int) Math.floor(Math.min(x1, x2));
        int y = (int) Math.floor(Math.min(y1, y2));
        int w = (int) (Math.ceil(Math.max(x1, x2)) - Math.floor(Math.min(x1, x2)));
        int h = (int) (Math.ceil(Math.max(y1, y2)) - Math.floor(Math.min(y1, y2)));
        syncBeforeDraw(NativeBridge.BLEND_CLEAR);
        try {
            // Color is ignored under CLEAR; the rect is written as transparent.
            NativeBridge.surfaceFillRect(handle(), x, y, w, h, 0, 0, 0, 0);
        } finally { restoreAfterDraw(); }
    }

    // ---- Fill --------------------------------------------------------------

    @Override
    public void fillRect(float x, float y, float width, float height) {
        Paint p = getPaint();
        syncBeforeDraw();
        try {
            if (p instanceof Color c) {
                NativeBridge.surfaceFillRect(
                    handle(),
                    (int) Math.floor(x), (int) Math.floor(y),
                    (int) Math.ceil(width), (int) Math.ceil(height),
                    c8(c.getRed()), c8(c.getGreen()), c8(c.getBlue()), c8(c.getAlpha()));
            } else {
                // Software tier: large gradient fills run Skia's scalar
                // pipeline (~120 ns/px under MSVC) — serve repeated ones
                // from the cached-image blit instead. Falls through to the
                // direct fill whenever ineligible; GPU tiers skip entirely.
                if (p instanceof Gradient grad && SkiaGpu.isResolvedSoftware()
                        && SoftwareGradientCache.tryFill(surface,
                            getTransformNoClone(),
                            getPixelScaleFactorX(), getPixelScaleFactorY(),
                            grad, x, y, width, height, 0, 0)) {
                    return;
                }
                try (SkiaShaders.Handle s = shaderFor(p)) {
                    if (!s.isValid()) return;
                    NativeBridge.surfaceFillRectShader(handle(), x, y, width, height,
                        s.shader, 0xFF);
                }
            }
        } finally { restoreAfterDraw(); }
    }

    @Override
    public void fillQuad(float x1, float y1, float x2, float y2) {
        fillRect(Math.min(x1, x2), Math.min(y1, y2),
                 Math.abs(x2 - x1), Math.abs(y2 - y1));
    }

    @Override
    public void fillRoundRect(float x, float y, float w, float h, float arcw, float arch) {
        Paint p = getPaint();
        syncBeforeDraw();
        try {
            if (p instanceof Color c) {
                NativeBridge.surfaceFillRoundRect(handle(), x, y, w, h, arcw, arch,
                    c8(c.getRed()), c8(c.getGreen()), c8(c.getBlue()), c8(c.getAlpha()));
            } else {
                // Software tier: see fillRect — cached blit for repeated
                // large gradient fills (CSS rounded backgrounds).
                if (p instanceof Gradient grad && SkiaGpu.isResolvedSoftware()
                        && SoftwareGradientCache.tryFill(surface,
                            getTransformNoClone(),
                            getPixelScaleFactorX(), getPixelScaleFactorY(),
                            grad, x, y, w, h, arcw, arch)) {
                    return;
                }
                try (SkiaShaders.Handle s = shaderFor(p)) {
                    if (!s.isValid()) return;
                    NativeBridge.surfaceFillRoundRectShader(
                        handle(), x, y, w, h, arcw, arch, s.shader, 0xFF);
                }
            }
        } finally { restoreAfterDraw(); }
    }

    @Override
    public void fillEllipse(float x, float y, float w, float h) {
        Paint p = getPaint();
        syncBeforeDraw();
        try {
            if (p instanceof Color c) {
                NativeBridge.surfaceFillOval(handle(), x, y, w, h,
                    c8(c.getRed()), c8(c.getGreen()), c8(c.getBlue()), c8(c.getAlpha()));
            } else {
                try (SkiaShaders.Handle s = shaderFor(p)) {
                    if (!s.isValid()) return;
                    NativeBridge.surfaceFillOvalShader(
                        handle(), x, y, w, h, s.shader, 0xFF);
                }
            }
        } finally { restoreAfterDraw(); }
    }

    @Override
    public void fill(Shape shape) {
        Paint p = getPaint();
        syncBeforeDraw();
        try (PathEncoder.Encoded path = PathEncoder.encode(shape)) {
            if (p instanceof Color c) {
                NativeBridge.surfaceFillPath(handle(),
                    path.verbs, path.verbCount,
                    path.coords, path.coordCount,
                    path.fillRule,
                    c8(c.getRed()), c8(c.getGreen()), c8(c.getBlue()), c8(c.getAlpha()));
            } else {
                try (SkiaShaders.Handle s = shaderFor(p)) {
                    if (!s.isValid()) return;
                    NativeBridge.surfaceFillPathShader(handle(),
                        path.verbs, path.verbCount,
                        path.coords, path.coordCount,
                        path.fillRule, s.shader, 0xFF);
                }
            }
        } finally { restoreAfterDraw(); }
    }

    /**
     * Fill {@code shape} (translated by {@code dx,dy}) with a gaussian-blurred
     * edge of color {@code argb}. The native primitive behind WebView CSS
     * box-shadow — drives the live SkCanvas directly so the blur never routes
     * through the Prism DropShadow effect's intermediate-surface composite,
     * which is broken on Skia. {@code sigma} is the SkMaskFilter blur sigma
     * (≈ CSS blur radius / 2). Uses the same per-draw begin/end bracket as
     * {@link #fill}, so the current transform + clip apply.
     */
    public void fillShapeBlur(Shape shape, float dx, float dy, float sigma, int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>>  8) & 0xFF;
        int b =  argb         & 0xFF;
        syncBeforeDraw();
        try (PathEncoder.Encoded path = PathEncoder.encodeTranslated(shape, dx, dy)) {
            NativeBridge.surfaceFillPathBlur(handle(),
                path.verbs, path.verbCount,
                path.coords, path.coordCount,
                path.fillRule, sigma,
                r, g, b, a);
        } finally { restoreAfterDraw(); }
    }

    // ---- Stroke ----------------------------------------------------------
    // Stroke dispatches on Paint type just like fill: solid color → fast
    // path with byte color args, gradient/pattern → SkShader path.

    @Override
    public void draw(Shape shape) {
        Paint paint = getPaint();
        BasicStroke st = currentStroke();
        syncBeforeDraw();
        try (PathEncoder.Encoded path = PathEncoder.encode(shape)) {
            if (paint instanceof Color c) {
                NativeBridge.surfaceStrokePath(handle(),
                    path.verbs, path.verbCount,
                    path.coords, path.coordCount,
                    path.fillRule,
                    st.getLineWidth(), st.getEndCap(), st.getLineJoin(), st.getMiterLimit(),
                    c8(c.getRed()), c8(c.getGreen()), c8(c.getBlue()), c8(c.getAlpha()));
            } else {
                try (SkiaShaders.Handle s = shaderFor(paint)) {
                    if (!s.isValid()) return;
                    NativeBridge.surfaceStrokePathShader(handle(),
                        path.verbs, path.verbCount,
                        path.coords, path.coordCount,
                        path.fillRule,
                        st.getLineWidth(), st.getEndCap(), st.getLineJoin(), st.getMiterLimit(),
                        s.shader, 0xFF);
                }
            }
        } finally { restoreAfterDraw(); }
    }

    @Override public void drawLine(float x1, float y1, float x2, float y2) {
        Paint paint = getPaint();
        BasicStroke s = currentStroke();
        syncBeforeDraw();
        try {
            if (paint instanceof Color c) {
                NativeBridge.surfaceStrokeLine(handle(), x1, y1, x2, y2,
                    s.getLineWidth(), s.getEndCap(), s.getLineJoin(), s.getMiterLimit(),
                    c8(c.getRed()), c8(c.getGreen()), c8(c.getBlue()), c8(c.getAlpha()));
            } else {
                try (SkiaShaders.Handle sh = shaderFor(paint)) {
                    if (!sh.isValid()) return;
                    NativeBridge.surfaceStrokeLineShader(handle(), x1, y1, x2, y2,
                        s.getLineWidth(), s.getEndCap(), s.getLineJoin(), s.getMiterLimit(),
                        sh.shader, 0xFF);
                }
            }
        } finally { restoreAfterDraw(); }
    }

    @Override public void drawRect(float x, float y, float w, float h) {
        Paint paint = getPaint();
        BasicStroke s = currentStroke();
        syncBeforeDraw();
        try {
            if (paint instanceof Color c) {
                NativeBridge.surfaceStrokeRect(handle(), x, y, w, h,
                    s.getLineWidth(), s.getEndCap(), s.getLineJoin(), s.getMiterLimit(),
                    c8(c.getRed()), c8(c.getGreen()), c8(c.getBlue()), c8(c.getAlpha()));
            } else {
                try (SkiaShaders.Handle sh = shaderFor(paint)) {
                    if (!sh.isValid()) return;
                    NativeBridge.surfaceStrokeRectShader(handle(), x, y, w, h,
                        s.getLineWidth(), s.getEndCap(), s.getLineJoin(), s.getMiterLimit(),
                        sh.shader, 0xFF);
                }
            }
        } finally { restoreAfterDraw(); }
    }

    @Override public void drawRoundRect(float x, float y, float w, float h, float arcw, float arch) {
        Paint paint = getPaint();
        BasicStroke s = currentStroke();
        syncBeforeDraw();
        try {
            if (paint instanceof Color c) {
                NativeBridge.surfaceStrokeRoundRect(handle(), x, y, w, h, arcw, arch,
                    s.getLineWidth(), s.getEndCap(), s.getLineJoin(), s.getMiterLimit(),
                    c8(c.getRed()), c8(c.getGreen()), c8(c.getBlue()), c8(c.getAlpha()));
            } else {
                try (SkiaShaders.Handle sh = shaderFor(paint)) {
                    if (!sh.isValid()) return;
                    NativeBridge.surfaceStrokeRoundRectShader(
                        handle(), x, y, w, h, arcw, arch,
                        s.getLineWidth(), s.getEndCap(), s.getLineJoin(), s.getMiterLimit(),
                        sh.shader, 0xFF);
                }
            }
        } finally { restoreAfterDraw(); }
    }

    @Override public void drawEllipse(float x, float y, float w, float h) {
        Paint paint = getPaint();
        BasicStroke s = currentStroke();
        syncBeforeDraw();
        try {
            if (paint instanceof Color c) {
                NativeBridge.surfaceStrokeOval(handle(), x, y, w, h,
                    s.getLineWidth(), s.getEndCap(), s.getLineJoin(), s.getMiterLimit(),
                    c8(c.getRed()), c8(c.getGreen()), c8(c.getBlue()), c8(c.getAlpha()));
            } else {
                try (SkiaShaders.Handle sh = shaderFor(paint)) {
                    if (!sh.isValid()) return;
                    NativeBridge.surfaceStrokeOvalShader(handle(), x, y, w, h,
                        s.getLineWidth(), s.getEndCap(), s.getLineJoin(), s.getMiterLimit(),
                        sh.shader, 0xFF);
                }
            }
        } finally { restoreAfterDraw(); }
    }

    // ---- Text -------------------------------------------------------------

    @Override
    public void drawString(GlyphList gl, FontStrike strike, float x, float y,
                           Color selectColor, int selectStart, int selectEnd) {
        int n = gl.getGlyphCount();
        if (n == 0) return;

        // Text fill paint. A solid Color renders through the per-glyph colour
        // path; a Gradient / ImagePattern fill builds one SkShader for the whole
        // run (below) and renders through the shader-glyph native entry. The
        // representative solid colour is the never-throw render-thread degrade:
        // if no shader can be built, the run still paints a sane colour instead
        // of aborting the frame.
        final Paint paint = getPaint();
        final Color fill = representativeColor(paint);
        int fr = c8(fill.getRed());
        int fg = c8(fill.getGreen());
        int fb = c8(fill.getBlue());
        int fa = c8(fill.getAlpha());
        boolean hasSelection = (selectColor != null && selectStart < selectEnd);
        int sr = hasSelection ? c8(selectColor.getRed())   : fr;
        int sg = hasSelection ? c8(selectColor.getGreen()) : fg;
        int sb = hasSelection ? c8(selectColor.getBlue())  : fb;
        int sa = hasSelection ? c8(selectColor.getAlpha()) : fa;

        // One strike → one font size for the whole run. CompositeStrike
        // shares its size across every fallback slot.
        float size = strike.getSize();
        CompositeStrike composite =
            (strike instanceof CompositeStrike cs) ? cs : null;

        // Resolve every glyph up front: the physical strike it belongs
        // to (composite text spans fallback fonts), its physical glyph
        // id, baseline position, and selection state. A glyph with a
        // usable SkTypeface renders via SkTextBlob; one without falls
        // back to the hardened glyph-as-path encoder.
        //
        // Per-thread pooled scratch arrays — eliminates 7×N allocations
        // per drawString. Reused across calls; resized only when n
        // exceeds the high-water-mark.
        DrawStringScratch scratch = DRAW_STRING_SCRATCH.get();
        scratch.ensure(n);
        SkiaTypeface[] faces = scratch.faces;
        FontStrike[]   pstr  = scratch.pstr;
        short[]        gids  = scratch.gids;
        float[]        gxs   = scratch.gxs;
        float[]        gys   = scratch.gys;
        boolean[]      sel   = scratch.sel;
        boolean[]      skip  = scratch.skip;
        // Reset the slots we'll use this call. (`for-loop` below
        // overwrites faces/pstr/gids/gxs/gys/sel/skip for [0..n) so we
        // don't need an explicit zero-fill, but skip[] must start
        // false because the for-loop only sets it on the failure
        // branch — clear it explicitly.)
        java.util.Arrays.fill(skip, 0, n, false);

        for (int i = 0; i < n; i++) {
            int code = gl.getGlyphCode(i);
            FontStrike ps;
            int physCode;
            if (composite != null) {
                ps = composite.getStrikeSlot(composite.getStrikeSlotForGlyph(code));
                physCode = code & CompositeGlyphMapper.GLYPHMASK;
            } else {
                ps = strike;
                physCode = code;
            }
            float gx = x + gl.getPosX(i);
            float gy = y + gl.getPosY(i);
            gxs[i] = gx;
            gys[i] = gy;
            pstr[i] = ps;
            gids[i] = (short) physCode;

            int charOff = gl.getCharOffset(i);
            sel[i] = hasSelection && charOff >= selectStart && charOff < selectEnd;

            if (ps == null
                    || (physCode & 0xFFFF) == CharToGlyphMapper.INVISIBLE_GLYPH_ID
                    || !Float.isFinite(gx) || !Float.isFinite(gy)) {
                skip[i] = true;
            } else {
                faces[i] = SkiaTypeface.forFontResource(ps.getFontResource());
            }
        }

        // Build the fill shader (gradient / image-pattern) after glyph
        // resolution so a thrown resolve can't leak it. shaderFor handles
        // degenerate gradients internally (last-stop solid fallback); an
        // invalid handle here means no usable shader, so we degrade to the
        // representative solid colour. Never throws on the render thread.
        SkiaShaders.Handle fillShader = null;
        if (paint instanceof Gradient || paint instanceof ImagePattern) {
            try {
                SkiaShaders.Handle h = shaderFor(paint);
                if (h != null && h.isValid()) {
                    fillShader = h;
                } else if (h != null) {
                    h.close();
                }
            } catch (Throwable t) {
                fillShader = null; // degrade to representative solid colour
            }
        }
        final MemorySegment shaderSeg = fillShader != null ? fillShader.shader : null;

        syncBeforeDraw();
        try {
            MemorySegment surface = handle();
            int i = 0;
            while (i < n) {
                if (skip[i]) { i++; continue; }
                boolean selected = sel[i];
                // Non-selected glyphs use the gradient/pattern shader when one
                // is active; selected glyphs always use the solid selection
                // colour (Prism only ever supplies a Color selection paint).
                boolean shadeRun = shaderSeg != null && !selected;
                int rr = selected ? sr : fr;
                int gg = selected ? sg : fg;
                int bb = selected ? sb : fb;
                int aa = selected ? sa : fa;

                SkiaTypeface face = faces[i];
                if (face == null) {
                    // No SkTypeface for this font — hardened path fallback.
                    if (shadeRun) {
                        drawGlyphAsPathShader(surface, pstr[i], gids[i] & 0xFFFF,
                                              gxs[i], gys[i], shaderSeg);
                    } else {
                        drawGlyphAsPath(surface, pstr[i], gids[i] & 0xFFFF,
                                        gxs[i], gys[i], rr, gg, bb, aa);
                    }
                    i++;
                    continue;
                }
                // Extend a maximal run of consecutive glyphs sharing this
                // typeface and selection state, then emit it as one SkTextBlob.
                int runStart = i;
                int j = i + 1;
                while (j < n && !skip[j]
                       && faces[j] == face && sel[j] == selected) {
                    j++;
                }
                if (shadeRun) {
                    emitGlyphRunShader(surface, face, size,
                                       gids, gxs, gys, runStart, j - runStart,
                                       shaderSeg);
                } else {
                    emitGlyphRun(surface, face, size,
                                 gids, gxs, gys, runStart, j - runStart,
                                 rr, gg, bb, aa);
                }
                i = j;
            }
        } finally {
            restoreAfterDraw();
            if (fillShader != null) fillShader.close();
        }
    }

    /**
     * Emits one {@code SkTextBlob} for {@code count} glyphs starting at
     * {@code start}, all sharing {@code face} and color. Glyph ids and
     * positions are copied into per-call native buffers from the
     * render thread's {@link FrameArena}.
     */
    private void emitGlyphRun(MemorySegment surface, SkiaTypeface face, float size,
                              short[] gids, float[] gxs, float[] gys,
                              int start, int count,
                              int r, int g, int b, int a) {
        if (count <= 0) return;
        long tf = face.getNativeHandle();
        if (tf == 0L) return; // typeface released — skip rather than crash
        try (FrameArena.Lease lease = FrameArena.current().open()) {
            MemorySegment gidSeg = lease.allocateShorts(count);
            MemorySegment pxSeg  = lease.allocateFloats(count);
            MemorySegment pySeg  = lease.allocateFloats(count);
            for (int k = 0; k < count; k++) {
                gidSeg.setAtIndex(ValueLayout.JAVA_SHORT, k, gids[start + k]);
                pxSeg.setAtIndex(ValueLayout.JAVA_FLOAT, k, gxs[start + k]);
                pySeg.setAtIndex(ValueLayout.JAVA_FLOAT, k, gys[start + k]);
            }
            NativeBridge.surfaceDrawGlyphs(surface, MemorySegment.ofAddress(tf),
                size, gidSeg, count, pxSeg, pySeg, r, g, b, a);
        }
    }

    /**
     * Fallback glyph rendering for fonts with no usable {@code SkTypeface}
     * (embedded fonts, parse failures): decomposes the glyph outline into
     * an {@code SkPath} and fills it. The native side rejects non-finite
     * geometry, so this path can no longer crash the rasterizer.
     */
    private void drawGlyphAsPath(MemorySegment surface, FontStrike strike, int code,
                                 float gx, float gy, int r, int g, int b, int a) {
        Glyph glyph = strike.getGlyph(code);
        if (glyph == null) return;
        Shape outline = glyph.getShape();
        if (outline == null) return;
        try (PathEncoder.Encoded p = PathEncoder.encodeTranslated(outline, gx, gy)) {
            NativeBridge.surfaceFillPath(surface,
                p.verbs, p.verbCount, p.coords, p.coordCount,
                p.fillRule, r, g, b, a);
        }
    }

    /**
     * Shader-filled variant of {@link #emitGlyphRun}: fills the glyph coverage
     * with {@code shader} (a gradient / image-pattern SkShader) instead of a
     * solid colour. Used for gradient/pattern-filled {@code Text} nodes.
     */
    private void emitGlyphRunShader(MemorySegment surface, SkiaTypeface face, float size,
                                    short[] gids, float[] gxs, float[] gys,
                                    int start, int count,
                                    MemorySegment shader) {
        if (count <= 0 || shader == null) return;
        long tf = face.getNativeHandle();
        if (tf == 0L) return; // typeface released — skip rather than crash
        try (FrameArena.Lease lease = FrameArena.current().open()) {
            MemorySegment gidSeg = lease.allocateShorts(count);
            MemorySegment pxSeg  = lease.allocateFloats(count);
            MemorySegment pySeg  = lease.allocateFloats(count);
            for (int k = 0; k < count; k++) {
                gidSeg.setAtIndex(ValueLayout.JAVA_SHORT, k, gids[start + k]);
                pxSeg.setAtIndex(ValueLayout.JAVA_FLOAT, k, gxs[start + k]);
                pySeg.setAtIndex(ValueLayout.JAVA_FLOAT, k, gys[start + k]);
            }
            NativeBridge.surfaceDrawGlyphsShader(surface, MemorySegment.ofAddress(tf),
                size, gidSeg, count, pxSeg, pySeg, shader, 0xFF);
        }
    }

    /** Shader-filled variant of {@link #drawGlyphAsPath}. */
    private void drawGlyphAsPathShader(MemorySegment surface, FontStrike strike, int code,
                                       float gx, float gy, MemorySegment shader) {
        if (shader == null) return;
        Glyph glyph = strike.getGlyph(code);
        if (glyph == null) return;
        Shape outline = glyph.getShape();
        if (outline == null) return;
        try (PathEncoder.Encoded p = PathEncoder.encodeTranslated(outline, gx, gy)) {
            NativeBridge.surfaceFillPathShader(surface,
                p.verbs, p.verbCount, p.coords, p.coordCount,
                p.fillRule, shader, 0xFF);
        }
    }

    // ---- Texture / blit ---------------------------------------------------

    @Override
    public void blit(RTTexture srcTex, RTTexture dstTex,
                     int srcX0, int srcY0, int srcX1, int srcY1,
                     int dstX0, int dstY0, int dstX1, int dstY1) {
        // Source surface → snapshot SkImage → drawImage on dst surface.
        // Phase-1 path: read pixels from src, upload as SkImage to dst,
        // draw, dispose. Slow but correct. Faster bridge entry can come
        // when this is a hotspot.
        if (!(srcTex instanceof SkiaRTTexture src)) {
            throw new IllegalArgumentException(
                "blit source must be a SkiaRTTexture; got " + srcTex.getClass().getSimpleName());
        }
        // For dst, this Graphics IS the surface — ignore dstTex.
        long srcHandle = src.getNativeHandle();
        if (srcHandle == 0L) throw new IllegalStateException("Source RTTexture disposed");

        // Read source rect via the existing readPixels path.
        int sw = srcX1 - srcX0;
        int sh = srcY1 - srcY0;
        if (sw <= 0 || sh <= 0) return;

        // Read into the pooled per-thread scratch buffer (grown monotonically,
        // never freed) rather than a fresh confined Arena per blit. blit runs
        // on the render thread, where a per-call sw*sh*4 malloc/free is needless
        // churn. The raster image is created, drawn, and destroyed before this
        // thread's scratch buffer can next be reused, so sharing it is safe.
        MemorySegment buf = SkiaRTTexture.ensureReadBuffer((long) sw * sh * 4);
        int rc = NativeBridge.surfaceReadPixels(
            MemorySegment.ofAddress(srcHandle), buf, srcX0, srcY0, sw, sh);
        if (rc != 0) return;

        MemorySegment img = NativeBridge.imageCreateRaster(
            sw, sh, sw * 4, buf, NativeBridge.CT_RGBA_8888_PREMUL);
        if (img == null || img.equals(MemorySegment.NULL)) return;
        try {
            syncBeforeDraw();
            try {
                NativeBridge.surfaceDrawImageRect(handle(), img,
                    0, 0, sw, sh,
                    dstX0, dstY0, dstX1 - dstX0, dstY1 - dstY0);
            } finally { restoreAfterDraw(); }
        } finally {
            NativeBridge.imageDestroy(img);
        }
    }

    /**
     * Composites a raw BGRA8888 (premultiplied, top-down) buffer at native
     * address {@code pixels} onto this graphics' surface, honoring the current
     * node transform + clip (via {@link #syncBeforeDraw()}) exactly like any
     * other draw. The source is {@code srcW×srcH} device px; it is drawn at the
     * node origin sized to its own LOGICAL size ({@code src / pixelScale}), so
     * the begin-draw matrix (transform × pixelScale) scales it back to 1:1
     * device px and positions it at the node's location. This is what makes the
     * off-screen Blink WebView frame land at the right place (not the surface
     * origin) and at correct scale (clip/letterbox on resize, no stretch).
     */
    public void drawBgra(long pixels, int srcW, int srcH, int srcStride) {
        drawBgra(pixels, srcW, srcH, srcStride, 0, 0);
    }

    /**
     * Draws a BGRA frame stretched to {@code dstLogicalW × dstLogicalH} logical
     * px at the node origin (the begin-draw matrix then scales to device px and
     * positions it). When {@code dstLogicalW/H <= 0} the destination falls back
     * to {@code src / pixelScale} (assumes the frame is exactly node×scale).
     *
     * <p>Passing the node's logical size is required for the off-screen Blink
     * WebView: the engine may downscale the capture to fit a shared-memory slot
     * (large or HiDPI pages), so {@code srcW/srcH} is NOT necessarily
     * {@code node×scale}. Stretching to the node size keeps the whole page —
     * including the bottom and right edges (e.g. a page footer) — visible at any
     * frame resolution, instead of drawing a too-small frame anchored top-left
     * and leaving the bottom/right strips blank.
     */
    public void drawBgra(long pixels, int srcW, int srcH, int srcStride,
                         int dstLogicalW, int dstLogicalH) {
        if (pixels == 0L || srcW <= 0 || srcH <= 0) {
            return;
        }
        float dstW;
        float dstH;
        if (dstLogicalW > 0 && dstLogicalH > 0) {
            dstW = dstLogicalW;
            dstH = dstLogicalH;
        } else {
            float sx = getPixelScaleFactorX();
            float sy = getPixelScaleFactorY();
            dstW = sx > 0 ? srcW / sx : srcW;
            dstH = sy > 0 ? srcH / sy : srcH;
        }
        syncBeforeDraw();
        try {
            NativeBridge.surfaceDrawBgra(surfaceHandle, pixels,
                srcW, srcH, srcStride, 0, 0, Math.round(dstW), Math.round(dstH));
        } finally {
            restoreAfterDraw();
        }
    }

    @Override
    public void drawTexture(Texture tex, float x, float y, float w, float h) {
        if (tex instanceof SkiaRTTexture src) {
            if (src.is3DPassBegun()) { composite3DRtt(src, x, y, w, h); return; }
            // Quantum draws RT-cached node images via drawTexture.
            // Snapshot the source SkSurface and draw it.
            long srcHandle = src.getNativeHandle();
            if (srcHandle == 0L) {
                // Disposed RTTexture source: drop the draw. The native side
                // already early-returns on a 0/poisoned handle, but guard here
                // for symmetry with blit()/imageHandleOf(). (bugs.md H1)
                return;
            }
            syncBeforeDraw();
            try {
                NativeBridge.surfaceDrawSurface(handle(),
                    MemorySegment.ofAddress(srcHandle),
                    0, 0, src.getContentWidth(), src.getContentHeight(),
                    x, y, w, h);
            } finally { restoreAfterDraw(); }
            return;
        }
        long imgHandle = imageHandleOf(tex);
        syncBeforeDraw();
        try {
            NativeBridge.surfaceDrawImage(handle(),
                MemorySegment.ofAddress(imgHandle), x, y, w, h);
        } finally { restoreAfterDraw(); }
    }

    @Override
    public void drawTexture(Texture tex,
                            float dx1, float dy1, float dx2, float dy2,
                            float sx1, float sy1, float sx2, float sy2) {
        // NGRegion's drawTexture3Slice/9Slice can produce a degenerate
        // source (sx1 == sx2 or sy1 == sy2) for the stretchable middle
        // strip when the cached region has no central +1 column / row.
        // Skia's drawImageRect with kStrict_SrcRectConstraint drops a
        // zero-width / zero-height source, leaving controls' interiors
        // unpainted. Expand the degenerate dimension by a single pixel
        // (sampled at sx1 / sy1) so Skia stretches a single column / row
        // across the destination — matching Prism's effective semantics.
        //
        // Only the degenerate case is touched. Non-degenerate sources
        // pass through verbatim — many callers (WebKit image draws,
        // HiDPI render passes) sample ranges that legitimately reach
        // the texture's rightmost / bottommost pixel, and trimming
        // there would cut off the image. When we DO expand a degenerate
        // dimension, the +1 pixel could push sx1+1 past the texture
        // edge; back sx1 off by 1 in that case so the 1-px sample stays
        // in bounds (Skia crashes deep in canvas state on OOB strict
        // src rects when the source is an RTT snapshot — see memory
        // note ngregion-3slice-zero-source / webview-open-followups
        // #12 for the recursive surface_draw_surface crash signature).
        float sw = sx2 - sx1;
        float sh = sy2 - sy1;
        if (sw <= 0) {
            sw = 1f;
            int tw = tex.getContentWidth();
            if (tw > 0 && sx1 + sw > tw) {
                sx1 = tw - 1;
            }
        }
        if (sh <= 0) {
            sh = 1f;
            int th = tex.getContentHeight();
            if (th > 0 && sy1 + sh > th) {
                sy1 = th - 1;
            }
        }

        if (tex instanceof SkiaRTTexture src) {
            if (src.is3DPassBegun()) {
                composite3DRtt(src, dx1, dy1, dx2 - dx1, dy2 - dy1);
                return;
            }
            long srcHandle = src.getNativeHandle();
            if (srcHandle == 0L) {
                return; // disposed RTTexture source — drop the draw (H1)
            }
            syncBeforeDraw();
            try {
                NativeBridge.surfaceDrawSurface(handle(),
                    MemorySegment.ofAddress(srcHandle),
                    sx1, sy1, sw, sh,
                    dx1, dy1, dx2 - dx1, dy2 - dy1);
            } finally { restoreAfterDraw(); }
            return;
        }
        long imgHandle = imageHandleOf(tex);
        syncBeforeDraw();
        try {
            NativeBridge.surfaceDrawImageRect(handle(),
                MemorySegment.ofAddress(imgHandle),
                sx1, sy1, sw, sh,
                dx1, dy1, dx2 - dx1, dy2 - dy1);
        } finally { restoreAfterDraw(); }
    }

    /**
     * Renders a parsed SVG document ({@code svgHandle}, from {@code SvgImage})
     * directly into the logical box {@code (x,y,w,h)} as vectors under the
     * current transform/clip — pixel-perfect at any zoom/DPI, clipped to the
     * box. Composites background → grid → SVG → optional tint. Used by
     * {@code NGSvgImageView}. No-op if {@code svgHandle} is 0 or the native
     * SVG module is unavailable.
     */
    public void drawSvg(long svgHandle, float x, float y, float w, float h,
                        int bgArgb, int tintArgb, int tintMode,
                        int gridArgb, float gridCell, float gridLineWidth) {
        if (svgHandle == 0L || w <= 0 || h <= 0) {
            return;
        }
        syncBeforeDraw();
        try {
            NativeBridge.svgRenderInPlace(handle(), svgHandle, x, y, w, h,
                bgArgb, tintArgb, tintMode, gridArgb, gridCell, gridLineWidth);
        } finally { restoreAfterDraw(); }
    }

    @Override
    public void drawTexture3SliceH(Texture tex,
                                   float dx1, float dy1, float dx2, float dy2,
                                   float sx1, float sy1, float sx2, float sy2,
                                   float dh1, float dh2, float sh1, float sh2) {
        // Three horizontal slices: [sx1..sh1] left, [sh1..sh2] middle, [sh2..sx2] right.
        // Stretches middle to fill [dh1..dh2] in the destination.
        drawTexture(tex, dx1, dy1, dh1, dy2,  sx1, sy1, sh1, sy2);
        drawTexture(tex, dh1, dy1, dh2, dy2,  sh1, sy1, sh2, sy2);
        drawTexture(tex, dh2, dy1, dx2, dy2,  sh2, sy1, sx2, sy2);
    }

    @Override
    public void drawTexture3SliceV(Texture tex,
                                   float dx1, float dy1, float dx2, float dy2,
                                   float sx1, float sy1, float sx2, float sy2,
                                   float dv1, float dv2, float sv1, float sv2) {
        drawTexture(tex, dx1, dy1, dx2, dv1,  sx1, sy1, sx2, sv1);
        drawTexture(tex, dx1, dv1, dx2, dv2,  sx1, sv1, sx2, sv2);
        drawTexture(tex, dx1, dv2, dx2, dy2,  sx1, sv2, sx2, sy2);
    }

    @Override
    public void drawTexture9Slice(Texture tex,
                                  float dx1, float dy1, float dx2, float dy2,
                                  float sx1, float sy1, float sx2, float sy2,
                                  float dh1, float dv1, float dh2, float dv2,
                                  float sh1, float sv1, float sh2, float sv2) {
        // 9-slice grid: corners untouched, edges stretched in one dimension,
        // center stretched in both. Decompose into 9 drawTexture calls.
        drawTexture(tex, dx1, dy1, dh1, dv1,  sx1, sy1, sh1, sv1); // tl
        drawTexture(tex, dh1, dy1, dh2, dv1,  sh1, sy1, sh2, sv1); // top
        drawTexture(tex, dh2, dy1, dx2, dv1,  sh2, sy1, sx2, sv1); // tr
        drawTexture(tex, dx1, dv1, dh1, dv2,  sx1, sv1, sh1, sv2); // left
        drawTexture(tex, dh1, dv1, dh2, dv2,  sh1, sv1, sh2, sv2); // center
        drawTexture(tex, dh2, dv1, dx2, dv2,  sh2, sv1, sx2, sv2); // right
        drawTexture(tex, dx1, dv2, dh1, dy2,  sx1, sv2, sh1, sy2); // bl
        drawTexture(tex, dh1, dv2, dh2, dy2,  sh1, sv2, sh2, sy2); // bottom
        drawTexture(tex, dh2, dv2, dx2, dy2,  sh2, sv2, sx2, sy2); // br
    }

    @Override
    public void drawTextureVO(Texture tex,
                              float topopacity, float botopacity,
                              float dx1, float dy1, float dx2, float dy2,
                              float sx1, float sy1, float sx2, float sy2) {
        // Reflection's mirror+fade. The destination quad arrives vertically
        // inverted (dy1 > dy2) to encode the flip, and the top/bottom opacities
        // ramp the fade — neither of which a plain drawTexture can express (a
        // negative-height dst is dropped by Skia). The native VO does the flip
        // and the gradient-alpha mask in one pass. PrReflectionPeer is the only
        // caller and always hands us an RTTexture (its source is a PrDrawable).
        if (tex instanceof SkiaRTTexture src) {
            long srcHandle = src.getNativeHandle();
            if (srcHandle == 0L) {
                return; // disposed RTTexture source — drop the draw (H1)
            }
            syncBeforeDraw();
            try {
                NativeBridge.surfaceDrawSurfaceVO(handle(),
                    MemorySegment.ofAddress(srcHandle),
                    dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2,
                    topopacity, botopacity);
            } finally { restoreAfterDraw(); }
            return;
        }
        // Defensive fallback for a non-RT source (not produced by Reflection):
        // draw opaque without the fade rather than dropping the inverted quad.
        drawTexture(tex,
            Math.min(dx1, dx2), Math.min(dy1, dy2),
            Math.max(dx1, dx2), Math.max(dy1, dy2),
            sx1, sy1, sx2, sy2);
    }

    @Override
    public void drawTextureRaw(Texture tex,
                               float dx1, float dy1, float dx2, float dy2,
                               float tx1, float ty1, float tx2, float ty2) {
        // "Raw" means texture coords are in [0..1] normalized space.
        // Convert to pixel space and dispatch.
        float tw = tex.getContentWidth();
        float th = tex.getContentHeight();
        drawTexture(tex,
            dx1, dy1, dx2, dy2,
            tx1 * tw, ty1 * th, tx2 * tw, ty2 * th);
    }

    @Override
    public void drawMappedTextureRaw(Texture tex,
                                     float dx1, float dy1, float dx2, float dy2,
                                     float tx11, float ty11, float tx21, float ty21,
                                     float tx12, float ty12, float tx22, float ty22) {
        // Fully general 4-corner mapping. Phase-1 maps to the bounding
        // rect; non-axis-aligned mappings need SkCanvas::drawAtlas or
        // a custom mesh draw which is out of scope here.
        float tw = tex.getContentWidth();
        float th = tex.getContentHeight();
        float minTX = Math.min(Math.min(tx11, tx21), Math.min(tx12, tx22));
        float maxTX = Math.max(Math.max(tx11, tx21), Math.max(tx12, tx22));
        float minTY = Math.min(Math.min(ty11, ty21), Math.min(ty12, ty22));
        float maxTY = Math.max(Math.max(ty11, ty21), Math.max(ty12, ty22));
        drawTexture(tex,
            dx1, dy1, dx2, dy2,
            minTX * tw, minTY * th, maxTX * tw, maxTY * th);
    }

    private long imageHandleOf(Texture tex) {
        // Both SkiaImageTexture and SkiaMediaTexture expose an SkImage
        // handle via getNativeHandle(); SkiaRTTexture's surface case
        // is handled in the drawTexture callers above before we get here.
        long h;
        if (tex instanceof SkiaImageTexture skTex) {
            h = skTex.getNativeHandle();
        } else if (tex instanceof SkiaMediaTexture mediaTex) {
            h = mediaTex.getNativeHandle();
        } else {
            throw new IllegalArgumentException(
                "Texture must be a SkiaImageTexture or SkiaMediaTexture; got "
                + tex.getClass().getSimpleName());
        }
        if (h == 0L) throw new IllegalStateException("Texture is disposed");
        return h;
    }

    // ---- Shape rendering --------------------------------------------------

    @Override
    protected void renderShape(Shape shape, BasicStroke stroke,
                               float x, float y, float w, float h) {
        if (stroke != null) {
            BasicStroke prev = getStroke();
            setStroke(stroke);
            try { draw(shape); }
            finally { setStroke(prev); }
        } else {
            fill(shape);
        }
    }

    // ---- Misc state -------------------------------------------------------

    @Override public void setNodeBounds(RectBounds bounds) { /* batching hint */ }

    private NGLightBase[] lights;
    @Override public void setLights(NGLightBase[] lights) { this.lights = lights; }
    @Override public NGLightBase[] getLights()           { return lights; }

    // setPixelScaleFactors is inherited from BaseGraphics — it stores
    // the scale; we apply it in syncBeforeDraw().
    private boolean state3D;
    @Override public void setState3D(boolean flag)   { this.state3D = flag; }
    @Override public boolean isState3D()             { return state3D; }
    @Override public void setup3DRendering()         { /* state pulled per-draw in SkiaMeshView */ }

    /**
     * Door 1: ensure the SubScene render target this Graphics draws into
     * has a native bgfx 3D target (shared color+depth), beginning a pass
     * (clear) on first use. Returns the native target handle, or 0 if 3D
     * isn't available. {@link SkiaMeshView#render} calls this before each
     * mesh draw; the parent's {@link #drawTexture} composites the result.
     */
    long ensure3DTarget() {
        long t = target.ensureNative3DTarget();
        if (t == 0L) {
            return 0L;
        }
        target.begin3DPassIfNeeded();
        return t;
    }

    /**
     * Composite a SubScene RTT whose 3D pass was begun: end the bgfx pass,
     * wrap its color zero-copy as an SkImage, and draw it into this surface.
     * Returns true if the source was a live 3D pass (and was handled).
     */
    private boolean composite3DRtt(SkiaRTTexture src, float dx, float dy, float dw, float dh) {
        long imgHandle = src.composite3DEnd(); // bgfx::frame + BorrowTextureFrom
        if (imgHandle == 0L) {
            return true; // pass ended; nothing to draw
        }
        syncBeforeDraw();
        try {
            NativeBridge.surfaceDrawImageRect(handle(),
                MemorySegment.ofAddress(imgHandle),
                0, 0, src.getContentWidth(), src.getContentHeight(),
                dx, dy, dw, dh);
        } finally { restoreAfterDraw(); }
        NativeBridge.imageDestroy(MemorySegment.ofAddress(imgHandle));
        return true;
    }

    // ---- Helpers ----------------------------------------------------------

    /**
     * A representative solid colour for any text-fill {@link Paint}. Never
     * throws — this is the render-thread-safe degrade for paints that can't be
     * shader-filled. A {@link Color} returns itself; a {@link Gradient} returns
     * the average of its stops; anything else returns opaque mid-grey.
     */
    // ImagePattern / unknown-paint fallback: a single shared opaque mid-grey, so
    // the per-draw path never allocates one (CLAUDE.md: no `new` in per-draw
    // methods). (M6)
    private static final Color FALLBACK_GREY = new Color(0.5f, 0.5f, 0.5f, 1f);
    // 1-entry identity cache for the gradient-average approximation, to avoid a
    // per-draw Color allocation when the same gradient text run repaints each
    // frame (video/animation). Benign racy — it's an approximation either way.
    private static volatile Gradient lastGradient;
    private static volatile Color lastGradientColor;

    private static Color representativeColor(Paint p) {
        if (p instanceof Color c) return c;
        if (p instanceof Gradient g) {
            Color cached = lastGradientColor;
            if (g == lastGradient && cached != null) {
                return cached;
            }
            List<Stop> stops = g.getStops();
            int n = stops.size();
            if (n == 0) return Color.BLACK;
            float r = 0f, gr = 0f, b = 0f, a = 0f;
            for (int i = 0; i < n; i++) {
                Color c = stops.get(i).getColor();
                r += c.getRed();   gr += c.getGreen();
                b += c.getBlue();  a  += c.getAlpha();
            }
            Color avg = new Color(r / n, gr / n, b / n, a / n);
            lastGradient = g;
            lastGradientColor = avg;
            return avg;
        }
        // ImagePattern or any other paint: opaque mid-grey is a safe neutral.
        return FALLBACK_GREY;
    }

    private BasicStroke currentStroke() { return getStroke(); }

    private SkiaShaders.Handle shaderFor(Paint p) {
        if (p instanceof Gradient g) {
            return SkiaShaders.forGradient(g);
        }
        if (p instanceof ImagePattern ip) {
            return SkiaShaders.forImagePattern(ip);
        }
        throw new UnsupportedOperationException(
            "Unsupported Paint subtype: " + p.getClass().getSimpleName());
    }

    /** Map Prism's {@link CompositeMode} → Skia blend mode int. */
    private static int mapBlendMode(CompositeMode m) {
        if (m == null) return NativeBridge.BLEND_SRC_OVER;
        return switch (m) {
            case CLEAR    -> NativeBridge.BLEND_CLEAR;
            case SRC      -> NativeBridge.BLEND_SRC;
            case SRC_OVER -> NativeBridge.BLEND_SRC_OVER;
            case ADD      -> NativeBridge.BLEND_PLUS;
            default       -> NativeBridge.BLEND_SRC_OVER;
        };
    }

    /** Float [0..1] color → byte [0..255]. */
    private static int c8(double v) {
        if (v <= 0.0) return 0;
        if (v >= 1.0) return 255;
        return (int) (v * 255.0 + 0.5);
    }

    // ---- drawString scratch pool ---------------------------------------------

    /** Per-thread reusable parallel arrays for {@link #drawString}. */
    private static final ThreadLocal<DrawStringScratch> DRAW_STRING_SCRATCH =
        ThreadLocal.withInitial(DrawStringScratch::new);

    /**
     * Pooled scratch buffers for the parallel-arrays pass in
     * {@code drawString}. Lives per-thread on whatever calls
     * {@code drawString} (Quantum's render thread in normal use).
     * Grows to the high-water-mark glyph count and never shrinks.
     */
    private static final class DrawStringScratch {
        SkiaTypeface[] faces = new SkiaTypeface[0];
        FontStrike[]   pstr  = new FontStrike[0];
        short[]        gids  = new short[0];
        float[]        gxs   = new float[0];
        float[]        gys   = new float[0];
        boolean[]      sel   = new boolean[0];
        boolean[]      skip  = new boolean[0];

        void ensure(int n) {
            if (faces.length < n) {
                // Grow with a small headroom factor to avoid back-to-back
                // resizes when a real high-water mark gradually climbs.
                int cap = Math.max(n, faces.length * 2);
                faces = new SkiaTypeface[cap];
                pstr  = new FontStrike[cap];
                gids  = new short[cap];
                gxs   = new float[cap];
                gys   = new float[cap];
                sel   = new boolean[cap];
                skip  = new boolean[cap];
            }
        }
    }

    // ===== ReadbackGraphics ==================================================
    //
    // Skia natively supports surface-to-surface blits via the bridge's
    // surface_draw_surface entry, so readBack is a pure GPU/CPU copy
    // depending on which backend the source surface is on. Used by
    // WCGraphicsPrismContext.ClipLayer to composite arbitrary-path
    // clips: a snapshot of the scene under the clip's bounding rect
    // is laid down on the layer's offscreen, then the clip-path mask
    // and the new content are composited on top. Pixel-precise
    // clipping without the bounding-rect approximation hack.

    @Override
    public boolean canReadBack() {
        return surfaceHandle != 0L;
    }

    @Override
    public RTTexture readBack(Rectangle view) {
        if (surfaceHandle == 0L || view == null
            || view.width <= 0 || view.height <= 0) {
            return null;
        }
        // Allocate a destination RTTexture sized to the requested view.
        // CLAMP_NOT_NEEDED because the caller (ClipLayer) samples within
        // bounds only; no wrap behaviour required.
        RTTexture rtt = factory.createRTTexture(view.width, view.height,
            Texture.WrapMode.CLAMP_NOT_NEEDED);
        if (!(rtt instanceof SkiaRTTexture dst) || dst.getNativeHandle() == 0L) {
            if (rtt != null) rtt.dispose();
            return null;
        }
        NativeBridge.surfaceDrawSurface(
            MemorySegment.ofAddress(dst.getNativeHandle()),
            surface,
            view.x, view.y, view.width, view.height,
            0,      0,      view.width, view.height);
        return dst;
    }

    @Override
    public void releaseReadBackBuffer(RTTexture view) {
        if (view != null) {
            view.dispose();
        }
    }
}
