/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package com.sun.scenario.effect.impl.skia;

import java.lang.foreign.MemorySegment;

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.scenario.effect.Color4f;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.PhongLighting;
import com.sun.scenario.effect.impl.EffectPeer;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.prism.PrEffectHelper;
import com.sun.scenario.effect.impl.state.RenderState;
import com.sun.scenario.effect.light.DistantLight;
import com.sun.scenario.effect.light.PointLight;
import com.sun.scenario.effect.light.SpotLight;
import com.sun.scenario.effect.light.Light;

/**
 * Skia peer for the {@code "PhongLighting_DISTANT"} /
 * {@code "_POINT"} / {@code "_SPOT"} dispatch names. Each maps to
 * Skia's corresponding {@code SkImageFilters::*LitDiffuse} primitive.
 *
 * <p>JFX's PhongLighting has both diffuse and specular outputs blended
 * together via its surface-scale knob; this v1 peer renders the
 * <em>diffuse</em> component only (the visually dominant term).
 * Specular highlights are tracked as a follow-up.</p>
 *
 * <p>Source content is treated as a height/bump map (alpha channel
 * gradients become surface normals) per the W3C filter-effects
 * spec — same as JFX's HW peer.</p>
 */
public final class SkiaPhongLightingPeer extends EffectPeer<RenderState> {

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
        final ImageData srcData = inputs[0];

        if (SkiaEffectRenderer.shouldBypassForDrag()) {
            SkiaEffectRenderer.BYPASS_COUNT.incrementAndGet();
            srcData.addref();
            return srcData;
        }

        if (!(effect instanceof PhongLighting pl)) {
            srcData.addref();
            return srcData;
        }

        Rectangle srcBounds = srcData.getTransformedBounds(null);
        Rectangle dstBounds = new Rectangle(srcBounds);
        dstBounds.intersectWith(outputClip);
        if (dstBounds.width <= 0 || dstBounds.height <= 0) {
            return new ImageData(fctx, null, srcBounds);
        }

        SkiaPrDrawable dst = (SkiaPrDrawable)
            getRenderer().getCompatibleImage(dstBounds.width, dstBounds.height);
        if (dst == null || !srcData.validate(fctx)) {
            return new ImageData(fctx, dst, dstBounds);
        }

        // Build the lighting filter per the light type encoded in the
        // dispatch-name suffix.
        MemorySegment filter = createLightingFilter(getUniqueName(), pl);
        if (filter == null) {
            srcData.addref();
            return srcData;
        }

        try {
            com.sun.prism.Graphics gdst = dst.createGraphics();
            if (gdst == null) {
                return new ImageData(fctx, dst, dstBounds);
            }
            MemorySegment dstSeg = MemorySegment.ofAddress(dst.getSurfaceHandle());
            if (dstSeg.equals(MemorySegment.NULL)) {
                return new ImageData(fctx, dst, dstBounds);
            }
            NativeBridge.surfaceSaveLayerWithFilter(dstSeg, filter);
            try {
                PrEffectHelper.renderImageData(gdst, srcData, dstBounds);
            } finally {
                NativeBridge.surfaceRestore(dstSeg);
            }
        } finally {
            NativeBridge.filterDestroy(filter);
        }
        return new ImageData(fctx, dst, dstBounds);
    }

    private static MemorySegment createLightingFilter(String dispatchName,
                                                      PhongLighting pl) {
        float surfaceScale  = pl.getSurfaceScale();
        float diffuseConst  = pl.getDiffuseConstant();
        Light light         = pl.getLight();
        if (light == null) return null;
        Color4f col = light.getColor();
        int r = (int) (col.getRed()   * 255f + 0.5f);
        int g = (int) (col.getGreen() * 255f + 0.5f);
        int b = (int) (col.getBlue()  * 255f + 0.5f);
        int a = (int) (col.getAlpha() * 255f + 0.5f);

        // Pick the right Skia lighting filter per type. Suffix is
        // "PhongLighting_DISTANT" / "_POINT" / "_SPOT".
        if (dispatchName.endsWith("_DISTANT") && light instanceof DistantLight dl) {
            // Convert azimuth/elevation to a direction vector.
            float az = (float) Math.toRadians(dl.getAzimuth());
            float el = (float) Math.toRadians(dl.getElevation());
            float cosEl = (float) Math.cos(el);
            float dx = cosEl * (float) Math.cos(az);
            float dy = cosEl * (float) Math.sin(az);
            float dz = (float) Math.sin(el);
            return NativeBridge.filterDistantLitDiffuse(
                dx, dy, dz, r, g, b, a, surfaceScale, diffuseConst, null);
        }
        if (dispatchName.endsWith("_POINT") && light instanceof PointLight pt) {
            return NativeBridge.filterPointLitDiffuse(
                pt.getX(), pt.getY(), pt.getZ(),
                r, g, b, a, surfaceScale, diffuseConst, null);
        }
        if (dispatchName.endsWith("_SPOT") && light instanceof SpotLight sp) {
            return NativeBridge.filterSpotLitDiffuse(
                sp.getX(),         sp.getY(),         sp.getZ(),
                sp.getPointsAtX(), sp.getPointsAtY(), sp.getPointsAtZ(),
                sp.getSpecularExponent(),
                30f, // approx cutoff angle; Skia clamps internally.
                r, g, b, a, surfaceScale, diffuseConst, null);
        }
        return null;
    }
}
