package com.sun.prism.skia.impl;

import com.sun.prism.paint.Color;
import com.sun.prism.paint.Gradient;
import com.sun.prism.paint.ImagePattern;
import com.sun.prism.paint.LinearGradient;
import com.sun.prism.paint.RadialGradient;
import com.sun.prism.paint.Stop;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

/**
 * Converts Prism {@link com.sun.prism.paint.Paint} instances into
 * native SkShader handles. The returned {@link Handle} owns its
 * underlying SkShader (heap {@code sk_sp<SkShader>*}); callers should
 * close it when done with the draw.
 *
 * <p>Image-pattern support requires an upload step (creating an
 * SkImage first), which is the slow path. For repeated use we'd cache
 * the SkImage handle on the {@link ImagePattern} itself; phase-1
 * builds the SkImage every call. CLAUDE.md atlas/cache work later.</p>
 */
public final class SkiaShaders {

    public static final class Handle implements AutoCloseable {
        public final MemorySegment shader;
        /** Optional image segment whose lifetime parallels the shader. */
        private final MemorySegment ownedImage;

        private Handle(MemorySegment shader, MemorySegment ownedImage) {
            this.shader = shader;
            this.ownedImage = ownedImage;
        }

        public boolean isValid() {
            return shader != null && !shader.equals(MemorySegment.NULL);
        }

        @Override public void close() {
            if (isValid()) {
                NativeBridge.shaderDestroy(shader);
            }
            if (ownedImage != null && !ownedImage.equals(MemorySegment.NULL)) {
                NativeBridge.imageDestroy(ownedImage);
            }
        }
    }

    private SkiaShaders() {}

    private static volatile boolean loggedDegenerate;

    /**
     * Build an SkShader for the given gradient (linear or radial).
     *
     * <p>A gradient can resolve to a <b>degenerate</b> geometry — a linear
     * gradient whose two endpoints coincide ({@code x1==x2 && y1==y2}), or a
     * radial gradient with radius {@code <= 0}. This happens transiently for a
     * <em>proportional</em> gradient resolved against empty shape bounds, e.g.
     * while a {@code VirtualFlow} recycles a list cell mid-scroll. Skia returns
     * a <b>null</b> shader for those, and a fill with a null shader paints with
     * SkPaint's default — <b>opaque black</b> (the "black avatar" bug). Per the
     * SVG/CSS rule a zero-length gradient paints the last stop's solid colour,
     * so we fall back to a solid-colour shader of the last stop instead of
     * letting the fill go black.</p>
     */
    public static Handle forGradient(Gradient g, float rx, float ry, float rw, float rh) {
        List<Stop> stops = g.getStops();
        int n = stops.size();
        if (n < 1) return new Handle(MemorySegment.NULL, null);
        if (n < 2) return new Handle(solidColorShader(stops.get(0).getColor()), null);

        MemorySegment shader;
        // Use the per-thread frame arena: the native side copies the
        // stop data when constructing the SkShader, so the buffers
        // can be released as soon as this call returns.
        try (FrameArena.Lease lease = FrameArena.current().open()) {
            MemorySegment positions = lease.allocateFloats(n);
            MemorySegment colors    = lease.allocateInts(n);
            for (int i = 0; i < n; i++) {
                Stop s = stops.get(i);
                positions.setAtIndex(ValueLayout.JAVA_FLOAT, i, s.getOffset());
                colors.setAtIndex(ValueLayout.JAVA_INT, i, packRgba(s.getColor()));
            }
            int tile = mapSpread(g.getSpreadMethod());
            // Resolve proportional endpoints against the fill-shape bounds and
            // fold the gradient transform (+ elliptical radial) into a local
            // matrix, mirroring stock PaintHelper. Without this a proportional
            // gradient was drawn with a ~1px gradient line and kClamp smeared a
            // single stop across the whole shape.
            GradientResolver gr = GradientResolver.current();
            boolean lm = false;
            if (g instanceof LinearGradient lg) {
                gr.resolveLinear(lg, rx, ry, rw, rh);
                lm = gr.hasLocalMatrix && NativeBridge.lmShadersAvailable();
                if (lm) {
                    shader = NativeBridge.shaderLinearGradientLm(
                        gr.x1, gr.y1, gr.x2, gr.y2, n, positions, colors, tile,
                        gr.m00, gr.m01, gr.m02, gr.m10, gr.m11, gr.m12);
                } else {
                    shader = NativeBridge.shaderLinearGradient(
                        gr.x1, gr.y1, gr.x2, gr.y2, n, positions, colors, tile);
                }
            } else if (g instanceof RadialGradient rg) {
                gr.resolveRadial(rg, rx, ry, rw, rh);
                lm = gr.hasLocalMatrix && NativeBridge.lmShadersAvailable();
                if (lm) {
                    shader = NativeBridge.shaderRadialGradientLm(
                        gr.cx, gr.cy, gr.radius, n, positions, colors, tile,
                        gr.m00, gr.m01, gr.m02, gr.m10, gr.m11, gr.m12);
                } else {
                    shader = NativeBridge.shaderRadialGradient(
                        gr.cx, gr.cy, gr.radius, n, positions, colors, tile);
                }
            } else {
                return new Handle(MemorySegment.NULL, null);
            }
        }

        if (shader == null || shader.equals(MemorySegment.NULL)) {
            // Degenerate geometry → Skia returned null → would paint black.
            // Render the last stop's solid colour instead.
            if (!loggedDegenerate && Boolean.getBoolean("skia.verbose")) {
                loggedDegenerate = true;
                System.err.println("[skia.shader] degenerate gradient -> solid "
                    + "last-stop fallback (" + g.getClass().getSimpleName() + ")");
            }
            shader = solidColorShader(stops.get(n - 1).getColor());
        }
        return new Handle(shader, null);
    }

    /**
     * A valid SkShader that paints {@code c} everywhere — a two-stop linear
     * gradient over a unit segment with both stops the same colour. Used as the
     * degenerate-gradient fallback so the fill paints a solid colour, never the
     * default black.
     */
    private static MemorySegment solidColorShader(Color c) {
        try (FrameArena.Lease lease = FrameArena.current().open()) {
            MemorySegment positions = lease.allocateFloats(2);
            MemorySegment colors    = lease.allocateInts(2);
            positions.setAtIndex(ValueLayout.JAVA_FLOAT, 0, 0f);
            positions.setAtIndex(ValueLayout.JAVA_FLOAT, 1, 1f);
            int rgba = packRgba(c);
            colors.setAtIndex(ValueLayout.JAVA_INT, 0, rgba);
            colors.setAtIndex(ValueLayout.JAVA_INT, 1, rgba);
            return NativeBridge.shaderLinearGradient(
                0f, 0f, 1f, 0f, 2, positions, colors, NativeBridge.TILE_CLAMP);
        }
    }

    private static int packRgba(Color c) {
        int r = clamp8(c.getRed());
        int gv = clamp8(c.getGreen());
        int bv = clamp8(c.getBlue());
        int a = clamp8(c.getAlpha());
        return (a << 24) | (bv << 16) | (gv << 8) | r;
    }

    /**
     * Build an SkShader from an {@link ImagePattern}. Uploads the
     * pattern's source image as an SkImage and wraps it.
     */
    public static Handle forImagePattern(ImagePattern p) {
        // Build an SkImage from the pattern's pixels. We currently
        // upload a fresh image each call; cache later.
        com.sun.prism.Image src = p.getImage();
        long imgHandle = uploadImage(src);
        if (imgHandle == 0L) return new Handle(MemorySegment.NULL, null);
        MemorySegment imgSeg = MemorySegment.ofAddress(imgHandle);
        MemorySegment shader = NativeBridge.shaderImage(
            imgSeg, NativeBridge.TILE_REPEAT, NativeBridge.TILE_REPEAT);
        if (shader == null || shader.equals(MemorySegment.NULL)) {
            NativeBridge.imageDestroy(imgSeg);
            return new Handle(MemorySegment.NULL, null);
        }
        return new Handle(shader, imgSeg);
    }

    // Upload a Prism Image to a one-shot SkImage handle. Mirrors
    // SkiaImageTexture.uploadImage but kept minimal and self-contained.
    private static long uploadImage(com.sun.prism.Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int rowBytes = image.getScanlineStride();
        int colorType = switch (image.getPixelFormat()) {
            case BYTE_BGRA_PRE, INT_ARGB_PRE -> NativeBridge.CT_BGRA_8888_PREMUL;
            case BYTE_GRAY                   -> NativeBridge.CT_GRAY_8;
            default                          -> -1;
        };
        if (colorType < 0) return 0L;

        try (FrameArena.Lease lease = FrameArena.current().open()) {
            long byteSize = (long) rowBytes * h;
            MemorySegment seg = lease.allocate(byteSize, 4);
            java.nio.Buffer buf = image.getPixelBuffer();
            if (buf instanceof java.nio.ByteBuffer bb && bb.hasArray()) {
                int pos = bb.position();
                int n = bb.limit() - pos;
                MemorySegment.copy(bb.array(), bb.arrayOffset() + pos,
                                   seg, ValueLayout.JAVA_BYTE,
                                   0L, Math.min(n, (int) byteSize));
            } else if (buf instanceof java.nio.IntBuffer ib && ib.hasArray()) {
                int pos = ib.position();
                int nInts = ib.limit() - pos;
                MemorySegment.copy(ib.array(), ib.arrayOffset() + pos,
                                   seg, ValueLayout.JAVA_INT,
                                   0L, Math.min(nInts, (int) (byteSize / 4)));
            } else {
                return 0L;
            }
            MemorySegment imgHandle = NativeBridge.imageCreateRaster(
                w, h, rowBytes, seg, colorType);
            if (imgHandle == null || imgHandle.equals(MemorySegment.NULL)) return 0L;
            return imgHandle.address();
        }
    }

    private static int clamp8(double v) {
        if (v <= 0) return 0;
        if (v >= 1) return 255;
        return (int) (v * 255 + 0.5);
    }

    private static int mapSpread(int prismSpread) {
        return switch (prismSpread) {
            case Gradient.PAD     -> NativeBridge.TILE_CLAMP;
            case Gradient.REFLECT -> NativeBridge.TILE_MIRROR;
            case Gradient.REPEAT  -> NativeBridge.TILE_REPEAT;
            default               -> NativeBridge.TILE_CLAMP;
        };
    }
}
