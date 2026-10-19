package com.sun.prism.skia.impl;

import com.sun.javafx.geom.transform.Affine2D;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.Image;
import com.sun.prism.ResourceFactory;
import com.sun.prism.Texture;
import com.sun.prism.paint.Color;
import com.sun.prism.paint.Gradient;
import com.sun.prism.paint.ImagePattern;
import com.sun.prism.paint.LinearGradient;
import com.sun.prism.paint.RadialGradient;
import com.sun.prism.paint.Stop;
import com.sun.prism.skia.SkiaImageTexture;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts Prism {@link com.sun.prism.paint.Paint} instances into
 * native SkShader handles. The returned {@link Handle} owns its
 * underlying SkShader (heap {@code sk_sp<SkShader>*}); callers should
 * close it when done with the draw.
 *
 * <p>Image patterns reuse the factory's cached REPEAT texture's SkImage
 * (see {@link #forImagePattern}).</p>
 */
public final class SkiaShaders {

    public static final class Handle implements AutoCloseable {
        public final MemorySegment shader;
        /** Optional image segment whose lifetime parallels the shader. */
        private final MemorySegment ownedImage;
        /** True when the shader belongs to the gradient cache (close() keeps it). */
        private final boolean cached;

        private Handle(MemorySegment shader, MemorySegment ownedImage) {
            this(shader, ownedImage, false);
        }

        private Handle(MemorySegment shader, MemorySegment ownedImage, boolean cached) {
            this.shader = shader;
            this.ownedImage = ownedImage;
            this.cached = cached;
        }

        public boolean isValid() {
            return shader != null && !shader.equals(MemorySegment.NULL);
        }

        @Override public void close() {
            if (isValid() && !cached) {
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
        // An absolute gradient does not depend on the fill bounds; key it
        // bounds-free so every shape sharing it hits one entry.
        if (!g.isProportional()) {
            rx = 0f; ry = 0f; rw = 0f; rh = 0f;
        }
        synchronized (GRADIENT_CACHE) {
            LOOKUP_KEY.set(g, rx, ry, rw, rh);
            MemorySegment hit = GRADIENT_CACHE.get(LOOKUP_KEY);
            if (hit != null) {
                return new Handle(hit, null, true);
            }
        }
        Handle created = createGradient(g, rx, ry, rw, rh);
        if (!created.isValid()) {
            return created;
        }
        synchronized (GRADIENT_CACHE) {
            GradientKey key = new GradientKey();
            key.set(g, rx, ry, rw, rh);
            MemorySegment prev = GRADIENT_CACHE.putIfAbsent(key, created.shader);
            if (prev != null) {
                // Lost a race with another thread: keep the cached one.
                NativeBridge.shaderDestroy(created.shader);
                return new Handle(prev, null, true);
            }
        }
        return new Handle(created.shader, null, true);
    }

    /**
     * Gradient shaders keyed by (gradient identity, resolved bounds). Prism
     * paints are immutable and the toolkit reuses one per FX paint, so a
     * Modena-style UI fills the same few gradients over the same few control
     * sizes every frame; this turns a native create + destroy per draw into a
     * lookup. Bounded LRU; evicted shaders are unref'd (an in-flight draw
     * holds its own reference natively, so eviction can never free a shader
     * that is still being drawn with on this thread).
     */
    private static final int GRADIENT_CACHE_SIZE = 128;

    private static final Map<GradientKey, MemorySegment> GRADIENT_CACHE =
        new LinkedHashMap<>(GRADIENT_CACHE_SIZE * 2, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<GradientKey, MemorySegment> e) {
                if (size() > GRADIENT_CACHE_SIZE) {
                    NativeBridge.shaderDestroy(e.getValue());
                    return true;
                }
                return false;
            }
        };

    /** Reused probe key so a cache hit allocates nothing but the Handle. */
    private static final GradientKey LOOKUP_KEY = new GradientKey();

    private static final class GradientKey {
        private Gradient g;
        private float rx, ry, rw, rh;
        private int hash;

        void set(Gradient g, float rx, float ry, float rw, float rh) {
            this.g = g;
            this.rx = rx; this.ry = ry; this.rw = rw; this.rh = rh;
            int h = System.identityHashCode(g);
            h = 31 * h + Float.floatToIntBits(rx);
            h = 31 * h + Float.floatToIntBits(ry);
            h = 31 * h + Float.floatToIntBits(rw);
            h = 31 * h + Float.floatToIntBits(rh);
            this.hash = h;
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object o) {
            return o instanceof GradientKey k && k.g == g
                && Float.floatToIntBits(k.rx) == Float.floatToIntBits(rx)
                && Float.floatToIntBits(k.ry) == Float.floatToIntBits(ry)
                && Float.floatToIntBits(k.rw) == Float.floatToIntBits(rw)
                && Float.floatToIntBits(k.rh) == Float.floatToIntBits(rh);
        }
    }

    private static Handle createGradient(Gradient g, float rx, float ry, float rw, float rh) {
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
                if (gr.hasFocus && NativeBridge.focalRadialShaderAvailable()) {
                    // Off-centre focus: two-point conical from the focus
                    // point to the gradient circle (PaintHelper focus math).
                    shader = lm
                        ? NativeBridge.shaderFocalRadialGradientLm(
                            gr.fx, gr.fy, gr.cx, gr.cy, gr.radius, n, positions, colors, tile,
                            gr.m00, gr.m01, gr.m02, gr.m10, gr.m11, gr.m12)
                        : NativeBridge.shaderFocalRadialGradientLm(
                            gr.fx, gr.fy, gr.cx, gr.cy, gr.radius, n, positions, colors, tile,
                            1f, 0f, 0f, 0f, 1f, 0f);
                } else if (lm) {
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
     * Build an SkShader from an {@link ImagePattern}, mirroring stock
     * {@code PaintHelper.getImagePatternTx}: the image is mapped onto the
     * pattern's anchor rect (resolved against {@code (rx,ry,rw,rh)} when the
     * pattern is proportional), then through the pattern transform, and tiled
     * with REPEAT from there.
     *
     * <p>The pixels come from the factory's REPEAT texture cache, so the image
     * is uploaded once per content change rather than copied twice on every
     * fill, and every pixel format the factory supports (BYTE_RGB JPEGs,
     * BYTE_ALPHA, sub-images, direct buffers) works. The shader holds its own
     * reference to the SkImage, so the cached texture keeps ownership.</p>
     */
    public static Handle forImagePattern(ImagePattern p, ResourceFactory rf,
                                         float rx, float ry, float rw, float rh) {
        Image img = p.getImage();
        if (img == null || rf == null) return new Handle(MemorySegment.NULL, null);
        Texture tex = rf.getCachedTexture(img, Texture.WrapMode.REPEAT);
        if (tex == null) return new Handle(MemorySegment.NULL, null);
        try {
            if (!(tex instanceof SkiaImageTexture st)) return new Handle(MemorySegment.NULL, null);
            long imgHandle = st.getNativeHandle();
            float cw = tex.getContentWidth();
            float ch = tex.getContentHeight();
            if (imgHandle == 0L || cw <= 0f || ch <= 0f) {
                return new Handle(MemorySegment.NULL, null);
            }
            float px = p.getX();
            float py = p.getY();
            float pw = p.getWidth();
            float ph = p.getHeight();
            if (p.isProportional()) {
                px = rx + px * rw;
                py = ry + py * rh;
                pw = pw * rw;
                ph = ph * rh;
            }
            // image px -> anchor rect -> pattern transform (user space)
            Affine2D m = PATTERN_TX.get();
            m.setToIdentity();
            BaseTransform patternTx = p.getPatternTransformNoClone();
            if (patternTx != null && !patternTx.isIdentity()) {
                m.concatenate(patternTx);
            }
            m.translate(px, py);
            m.scale(pw / cw, ph / ch);
            MemorySegment shader = NativeBridge.shaderImageLm(
                MemorySegment.ofAddress(imgHandle),
                NativeBridge.TILE_REPEAT, NativeBridge.TILE_REPEAT,
                (float) m.getMxx(), (float) m.getMxy(), (float) m.getMxt(),
                (float) m.getMyx(), (float) m.getMyy(), (float) m.getMyt());
            return new Handle(shader, null);
        } finally {
            tex.unlock();
        }
    }

    private static final ThreadLocal<Affine2D> PATTERN_TX =
        ThreadLocal.withInitial(Affine2D::new);

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
