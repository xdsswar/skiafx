package com.sun.prism.skia.impl;

import com.sun.javafx.geom.transform.Affine2D;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.paint.LinearGradient;
import com.sun.prism.paint.RadialGradient;

/**
 * Resolves a Prism {@link com.sun.prism.paint.Gradient}'s geometry into the
 * absolute coordinates Skia needs, mirroring the stock pipeline's
 * {@code com.sun.prism.impl.ps.PaintHelper}.
 *
 * <p>Prism gradients store {@code proportional} (0..1) endpoints plus a
 * {@code gradientTransform}; the raw getters return those unresolved. Stock
 * Prism resolves them against the fill-shape bounds at paint time
 * ({@code x = rx + frac*rw}, PaintHelper#getLinearGradientTx) and folds the
 * gradient transform — and, for radial gradients on a non-square shape, an
 * elliptical correction — into the gradient-to-device matrix. The Skia
 * pipeline replaced {@code BaseShaderGraphics} without porting this, so
 * proportional gradients collapsed to a ~1px gradient line smeared by
 * {@code kClamp}. This helper restores the math.</p>
 *
 * <p>Coordinate space: {@code SkiaGraphics.syncBeforeDraw} sets the canvas CTM
 * to {@code nodeTransform × pixelScale} and fill coordinates are node-local, so
 * endpoints/centre resolve against the <b>local</b> fill bounds and the local
 * matrix is expressed in local space (the CTM is applied on top by Skia). Do
 * not pre-apply pixel scale here.</p>
 *
 * <p>Instances are per-thread and reused; {@code resolveLinear}/
 * {@code resolveRadial} overwrite the result fields and allocate nothing.</p>
 */
final class GradientResolver {

    /** Resolved linear endpoints (valid after {@link #resolveLinear}). */
    float x1, y1, x2, y2;
    /** Resolved radial centre + radius (valid after {@link #resolveRadial}). */
    float cx, cy, radius;

    /**
     * True when a non-identity local matrix is needed (a non-identity
     * gradientTransform, or — radial only — a non-square elliptical
     * correction). When false the plain (no-matrix) native shader is correct.
     */
    boolean hasLocalMatrix;
    /** Forward local matrix rows: [m00 m01 m02 / m10 m11 m12]. */
    float m00, m01, m02, m10, m11, m12;

    private final Affine2D scratch = new Affine2D();

    private static final ThreadLocal<GradientResolver> TL =
        ThreadLocal.withInitial(GradientResolver::new);

    static GradientResolver current() {
        return TL.get();
    }

    /** Mirrors PaintHelper#getLinearGradientTx proportional branch. */
    void resolveLinear(LinearGradient lg, float rx, float ry, float rw, float rh) {
        float ax1 = lg.getX1(), ay1 = lg.getY1();
        float ax2 = lg.getX2(), ay2 = lg.getY2();
        if (lg.isProportional()) {
            ax1 = rx + ax1 * rw;  ay1 = ry + ay1 * rh;
            ax2 = rx + ax2 * rw;  ay2 = ry + ay2 * rh;
        }
        x1 = ax1; y1 = ay1; x2 = ax2; y2 = ay2;

        // Linear has no elliptical case; only a non-identity gradientTransform
        // needs a local matrix.
        BaseTransform gt = lg.getGradientTransformNoClone();
        if (gt != null && !gt.isIdentity()) {
            captureMatrix(gt);
        } else {
            hasLocalMatrix = false;
        }
    }

    /** Mirrors PaintHelper#setRadialGradient proportional branch (focus dropped,
     *  matching the concentric-only Skia native radial path). */
    void resolveRadial(RadialGradient rg, float rx, float ry, float rw, float rh) {
        float ccx = rg.getCenterX(), ccy = rg.getCenterY(), r = rg.getRadius();
        boolean elliptical = false;
        float bcx = 0f, bcy = 0f, sclX = 1f, sclY = 1f;
        if (rg.isProportional()) {
            bcx = rx + rw / 2f;
            bcy = ry + rh / 2f;
            float scale = Math.min(rw, rh);
            ccx = (ccx - 0.5f) * scale + bcx;
            ccy = (ccy - 0.5f) * scale + bcy;
            if (rw != rh && rw != 0f && rh != 0f) {
                elliptical = true;
                sclX = rw / scale;
                sclY = rh / scale;
            }
            r = r * scale;
        }
        cx = ccx; cy = ccy; radius = r;

        BaseTransform gt = rg.getGradientTransformNoClone();
        boolean gtIdentity = (gt == null) || gt.isIdentity();
        if (!elliptical && gtIdentity) {
            hasLocalMatrix = false;
            return;
        }
        // lm = ellipticalCorrection · gradientTransform (forward; Skia applies
        // the local matrix to the gradient points / unit circle).
        Affine2D m = scratch;
        m.setToIdentity();
        if (elliptical) {
            m.translate(bcx, bcy);
            m.scale(sclX, sclY);
            m.translate(-bcx, -bcy);
        }
        if (!gtIdentity) {
            m.concatenate(gt);
        }
        captureMatrix(m);
    }

    private void captureMatrix(BaseTransform m) {
        m00 = (float) m.getMxx(); m01 = (float) m.getMxy(); m02 = (float) m.getMxt();
        m10 = (float) m.getMyx(); m11 = (float) m.getMyy(); m12 = (float) m.getMyt();
        hasLocalMatrix = true;
    }
}
