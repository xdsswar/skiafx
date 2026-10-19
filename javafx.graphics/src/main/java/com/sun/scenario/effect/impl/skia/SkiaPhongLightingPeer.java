/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.Graphics;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.scenario.effect.Color4f;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.Filterable;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.PhongLighting;
import com.sun.scenario.effect.impl.EffectPeer;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.prism.PrEffectHelper;
import com.sun.scenario.effect.impl.state.RenderState;
import com.sun.scenario.effect.light.DistantLight;
import com.sun.scenario.effect.light.Light;
import com.sun.scenario.effect.light.PointLight;
import com.sun.scenario.effect.light.SpotLight;

/**
 * Skia peer for the {@code "PhongLighting_DISTANT"} / {@code "_POINT"} /
 * {@code "_SPOT"} dispatch names.
 *
 * <p>Reproduces upstream {@code PhongLighting.jsl} (+ the CompilePhong light
 * variants) with an {@code SkRuntimeEffect} image filter
 * ({@code openjfx_skia_effects_ext.cpp}): the surface normal comes from a
 * Sobel filter over the BUMP input's alpha ({@code inputs[0]}), and the
 * CONTENT input ({@code inputs[1]}) is modulated by the diffuse term with the
 * specular term composited over it — {@code content x lighting}, both
 * diffuse and specular.</p>
 *
 * <p>The content is drawn through the filtered layer; the bump input is bound
 * as an image shader from a snapshot of its drawable. Light positions follow
 * the Decora peers exactly: {@code pixcoord} is the destination pixel
 * coordinate (relative to the result bounds' origin, in render-space
 * pixels), and point/spot light positions are compared against it
 * untransformed — the same convention every upstream backend (D3D, ES2, SW)
 * uses.</p>
 */
public final class SkiaPhongLightingPeer extends EffectPeer<RenderState> {

    private static final int PARAM_COUNT = 21;

    /** Reused native parameter block (render thread only). */
    private final MemorySegment params =
        Arena.ofAuto().allocate(ValueLayout.JAVA_FLOAT, PARAM_COUNT);

    public SkiaPhongLightingPeer(FilterContext fctx, Renderer r, String name) {
        super(fctx, r, name);
    }

    @Override
    public ImageData filter(Effect effect,
                            RenderState rstate,
                            BaseTransform transform,
                            Rectangle outputClip,
                            ImageData... inputs) {
        final FilterContext fctx = getFilterContext();
        final ImageData bumpData = inputs[0];
        final ImageData contentData = (inputs.length > 1) ? inputs[1] : inputs[0];

        if (SkiaEffectRenderer.shouldBypassForDrag()) {
            SkiaEffectRenderer.BYPASS_COUNT.incrementAndGet();
            contentData.addref();
            return contentData;
        }

        if (!(effect instanceof PhongLighting pl)
                || pl.getLight() == null
                || !SkiaEffectsNative.isPhongAvailable()) {
            contentData.addref();
            return contentData;
        }

        // Upstream: result bounds come from the content input.
        Rectangle contentBounds = contentData.getTransformedBounds(null);
        Rectangle dstBounds = new Rectangle(contentBounds);
        if (outputClip != null) {
            dstBounds.intersectWith(outputClip);
        }
        if (dstBounds.width <= 0 || dstBounds.height <= 0) {
            // Empty result: a real cleared drawable, like upstream (never image-less).
            return SkiaEffectRenderer.emptyResult(fctx, getRenderer(), dstBounds);
        }
        if (!contentData.validate(fctx) || !bumpData.validate(fctx)) {
            return SkiaEffectRenderer.emptyResult(fctx, getRenderer(), dstBounds);
        }

        // Build the filter first so a native failure falls back to
        // passthrough without checking out (and leaking) a pooled drawable.
        MemorySegment filter = createLightingFilter(getUniqueName(), pl, bumpData, dstBounds);
        if (filter == null) {
            contentData.addref();
            return contentData;
        }

        try {
            SkiaPrDrawable dst = (SkiaPrDrawable)
                getRenderer().getCompatibleImage(dstBounds.width, dstBounds.height);
            if (dst == null) {
                return new ImageData(fctx, null, dstBounds);
            }
            Graphics gdst = dst.createGraphics();
            if (gdst == null) {
                return new ImageData(fctx, dst, dstBounds);
            }
            MemorySegment dstSeg = MemorySegment.ofAddress(dst.getSurfaceHandle());
            if (dstSeg.equals(MemorySegment.NULL)) {
                return new ImageData(fctx, dst, dstBounds);
            }
            NativeBridge.surfaceSaveLayerWithFilter(dstSeg, filter);
            try {
                PrEffectHelper.renderImageData(gdst, contentData, dstBounds);
            } finally {
                NativeBridge.surfaceRestore(dstSeg);
            }
            return new ImageData(fctx, dst, dstBounds);
        } finally {
            NativeBridge.filterDestroy(filter);
        }
    }

    /**
     * Builds the Phong filter for this frame. The bump drawable is
     * snapshotted (a ref on its texture, no copy while it is not redrawn)
     * and positioned in layer coordinates. Null on any failure.
     */
    private MemorySegment createLightingFilter(String dispatchName, PhongLighting pl,
                                               ImageData bumpData, Rectangle dstBounds) {
        Filterable f = bumpData.getUntransformedImage();
        if (!(f instanceof SkiaPrDrawable bumpDrawable)) {
            return null;
        }
        long bumpHandle = bumpDrawable.getSurfaceHandle();
        if (bumpHandle == 0L) {
            return null;
        }

        // Bump content rect in layer (destination) coordinates.
        Rectangle bb = bumpData.getUntransformedBounds();
        BaseTransform btx = bumpData.getTransform();
        float bx, by;
        if (btx.isTranslateOrIdentity()) {
            bx = (float) (bb.x + btx.getMxt()) - dstBounds.x;
            by = (float) (bb.y + btx.getMyt()) - dstBounds.y;
        } else {
            // Non-translate bump transforms do not occur for this
            // RenderSpace effect; approximate with the transformed origin.
            Rectangle tb = bumpData.getTransformedBounds(null);
            bx = tb.x - dstBounds.x;
            by = tb.y - dstBounds.y;
        }

        Light light = pl.getLight();
        Color4f col = light.getColor();
        float ca = col.getAlpha();
        float lightType;
        float px = 0f, py = 0f, pz = 0f;
        float dx = 0f, dy = 0f, dz = 0f;
        float spotExp = 0f;
        if (dispatchName.endsWith("_DISTANT") && light instanceof DistantLight dl) {
            lightType = 0f;
            double a = Math.toRadians(dl.getAzimuth());
            double e = Math.toRadians(dl.getElevation());
            float x = (float) (Math.cos(a) * Math.cos(e));
            float y = (float) (Math.sin(a) * Math.cos(e));
            float z = (float) Math.sin(e);
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len == 0f) len = 1f;
            px = x / len;
            py = y / len;
            pz = z / len;
        } else if (dispatchName.endsWith("_SPOT") && light instanceof SpotLight sp) {
            lightType = 2f;
            px = sp.getX();
            py = sp.getY();
            pz = sp.getZ();
            float sx = sp.getPointsAtX() - px;
            float sy = sp.getPointsAtY() - py;
            float sz = sp.getPointsAtZ() - pz;
            float len = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (len == 0f) len = 1f;
            dx = sx / len;
            dy = sy / len;
            dz = sz / len;
            spotExp = sp.getSpecularExponent();
        } else if (dispatchName.endsWith("_POINT") && light instanceof PointLight pt) {
            lightType = 1f;
            px = pt.getX();
            py = pt.getY();
            pz = pt.getZ();
        } else {
            return null;
        }

        MemorySegment p = params;
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 0, bx);
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 1, by);
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 2, bb.width);
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 3, bb.height);
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 4, pl.getSurfaceScale());
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 5, pl.getDiffuseConstant());
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 6, pl.getSpecularConstant());
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 7, pl.getSpecularExponent());
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 8, lightType);
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 9,  col.getRed()   * ca);
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 10, col.getGreen() * ca);
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 11, col.getBlue()  * ca);
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 12, px);
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 13, py);
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 14, pz);
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 15, dx);
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 16, dy);
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 17, dz);
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 18, spotExp);
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 19, 0f);
        p.setAtIndex(ValueLayout.JAVA_FLOAT, 20, 0f);

        MemorySegment bumpImage = NativeBridge.surfaceSnapshotToImage(
            MemorySegment.ofAddress(bumpHandle));
        if (bumpImage == null || bumpImage.equals(MemorySegment.NULL)) {
            return null;
        }
        try {
            // The filter holds its own ref on the snapshot.
            return SkiaEffectsNative.phongFilter(bumpImage, p, PARAM_COUNT);
        } finally {
            NativeBridge.imageDestroy(bumpImage);
        }
    }
}
