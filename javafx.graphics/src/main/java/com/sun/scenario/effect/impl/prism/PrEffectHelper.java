/*
 * Copyright (c) 2009, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.scenario.effect.impl.prism;

import com.sun.glass.ui.Screen;
import com.sun.javafx.geom.PickRay;
import com.sun.javafx.geom.Point2D;
import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.Vec3d;
import com.sun.javafx.geom.transform.Affine2D;
import com.sun.javafx.geom.transform.Affine3D;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.geom.transform.NoninvertibleTransformException;
import com.sun.javafx.sg.prism.NGCamera;
import com.sun.javafx.sg.prism.NGPerspectiveCamera;
import com.sun.prism.Graphics;
import com.sun.prism.RenderTarget;
import com.sun.prism.skia.SkiaGraphics;
import com.sun.prism.ResourceFactory;
import com.sun.prism.Texture;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.EffectPeer;
import com.sun.scenario.effect.impl.ImagePool;
import java.util.concurrent.atomic.AtomicBoolean;

public class PrEffectHelper {

    // Tripped once when the validate-retry cap fires for the first
    // time, so the diagnostic in render() stays a one-shot.
    private static final AtomicBoolean giveUpReported = new AtomicBoolean(false);
    // Gate for the transient effect give-up diagnostic (a one-pulse pool miss
    // that self-heals next pulse). Off by default; -Dprism.effect.verbose=true.
    private static final boolean VERBOSE = Boolean.getBoolean("prism.effect.verbose");

    /**
     * Applies the given filter effect to the series of inputs and then renders
     * the result to the provided {@code Graphics} at the specified
     * location.
     * This method is similar to the following pseudo-code:
     * <pre>
     *     g.drawTexture(effect.filter(g.getTransform()), x, y);
     * </pre>
     * except that it is likely to be more efficient (and correct).
     *
     * @param effect the effect to be rendered
     * @param g the {@code Graphics} to which the {@code Effect} will be
     *          rendered
     * @param x the x location of the filtered result
     * @param y the y location of the filtered result
     * @param defaultInput the default input {@code Effect} to be used if
     *                     any of the inputs for any of the effects in the
     *                     chain are unspecified (i.e. {@code null}).
     */
    public static void render(Effect effect,
                              Graphics g, float x, float y,
                              Effect defaultInput)
    {
        BaseTransform transform;
        Rectangle rclip = getGraphicsClipNoClone(g);
        BaseTransform origtx = g.getTransformNoClone().copy();
        BaseTransform rendertx;
        // skia-fx HiDPI: the Skia pipeline keeps the pixel scale SEPARATE from
        // the Graphics transform (SkiaGraphics applies it per draw), so origtx
        // is LOGICAL. Filtering an effect at the logical transform produces a
        // LOGICAL-resolution result that SkiaGraphics then upscales by the pixel
        // scale → blurry effects (card drop-shadows, gallery blurs) on 150%/175%
        // monitors. Fix: filter at DEVICE scale (origtx prepended with the pixel
        // scale) so the whole effect graph — input snapshot, peer intermediates,
        // output — is device resolution, then draw the device-res result back
        // pre-scaled by 1/pixelScale so SkiaGraphics' per-draw ×pixelScale nets
        // to 1:1. No effect on stock Prism (origtx already device) or at 100%.
        float psX = 1f, psY = 1f;
        if (g instanceof SkiaGraphics) {
            psX = g.getPixelScaleFactorX();
            psY = g.getPixelScaleFactorY();
        }
        final boolean hidpiEffect = (psX != 1f || psY != 1f);
        if (origtx.is2D()) {
            // process the effect using the current 2D transform, and then
            // render the resulting image in device space (i.e., with identity)
            if (x != 0f || y != 0f || !origtx.isIdentity() || hidpiEffect) {
                Affine2D dev = new Affine2D();
                if (hidpiEffect) {
                    dev.scale(psX, psY);          // dev = S(ps)
                }
                dev.concatenate(origtx);          // dev = S(ps) · origtx
                dev.translate(x, y);
                transform = dev;
            } else {
                transform = BaseTransform.IDENTITY_TRANSFORM;
            }
            g.setTransform(null);
            // Counteract SkiaGraphics' per-draw ×pixelScale so the device-res
            // result composites 1:1 (identity in Prism / at 100%).
            if (hidpiEffect) {
                Affine2D rt = new Affine2D();
                rt.scale(1.0 / psX, 1.0 / psY);
                rendertx = rt;
            } else {
                rendertx = null;
            }
        } else {
            // process the effect with an identity (2D) transform, and then
            // render the resulting image using the current (3D) modelview
            // and/or projection transform
            // JDK-8090833
            // TODO: this will not work if the effect is applied to a Group
            // that has children with 3D transforms (relative to the Group),
            // but at least it's good enough for simple effects applied to
            // leaf nodes (e.g. applying a Reflection to a leaf ImageView node)
            double scalex = Math.hypot(origtx.getMxx(), origtx.getMyx());
            double scaley = Math.hypot(origtx.getMxy(), origtx.getMyy());
            double scale = Math.max(scalex, scaley);
            if (scale <= 1.0) {
                transform = BaseTransform.IDENTITY_TRANSFORM;
                rendertx = origtx;
            } else {
                transform = BaseTransform.getScaleInstance(scale, scale);
                rendertx = new Affine3D(origtx);
                scale = 1.0 / scale;
                ((Affine3D) rendertx).scale(scale, scale);
            }
            NGCamera cam = g.getCameraNoClone();
            BaseTransform inv;
            try {
                inv = rendertx.createInverse();
            } catch (NoninvertibleTransformException e) {
                return;
            }
            PickRay ray = new PickRay();
            Vec3d tmpvec = new Vec3d();
            // See FilterEffect.untransformClip for a description of
            // why we round in by half a pixel here.
            float x1 = rclip.x + 0.5f;
            float y1 = rclip.y + 0.5f;
            float x2 = rclip.x + rclip.width - 0.5f;
            float y2 = rclip.y + rclip.height - 0.5f;
            double rtw = g.getRenderTarget().getContentWidth();
            double rth = g.getRenderTarget().getContentHeight();
            Point2D cul = project(x1, y1, rtw, rth, cam, inv, ray, tmpvec, null);
            Point2D cur = project(x2, y1, rtw, rth, cam, inv, ray, tmpvec, null);
            Point2D cll = project(x1, y2, rtw, rth, cam, inv, ray, tmpvec, null);
            Point2D clr = project(x2, y2, rtw, rth, cam, inv, ray, tmpvec, null);
            rclip = clipbounds(cul, cur, cll, clr);
        }

        Screen screen = g.getAssociatedScreen();
        FilterContext fctx;
        // JDK-8090833
        if (screen == null) {
            ResourceFactory factory = g.getResourceFactory();
            fctx = PrFilterContext.getPrinterContext(factory);
        } else {
            fctx = PrFilterContext.getInstance(screen);
        }
        // TODO: Pass the camera down so that nodes can render with it
        // for proper perspective below this level.
        PrRenderInfo prinfo;
        if (rendertx != null) {
            // Whatever results are produced will have to be post-transformed
            // so attempts at direct rendering would use the wrong transform.
            prinfo = null;
        } else if (g.isDepthBuffer() && g.isDepthTest()) {
            // Some of the multi-step operations may produce both flat image
            // results that would not track the actual Z depth of any direct
            // Node rendering so we must disable direct rendering to avoid
            // depth buffer conflicts.
            prinfo = null;
        } else {
            // If none of the above conditions apply, then the PrRenderInfo
            // can represent all information necessary to directly render
            // any ImageData or Node to the destination.
            prinfo = new PrRenderInfo(g);
        }
        boolean valid;
        ImagePool.numEffects++;

        // skia-fx: bound the validation-retry loop. The upstream code
        // had an open-ended `do { ... } while (!valid);` here. When an
        // effect peer returns an ImageData whose validate() always
        // returns false (e.g. SkiaPassthroughPeer with no inputs, or a
        // peer that hands back an image type the renderer doesn't
        // recognise), the render thread spins forever — observed at
        // 250000+ surface_begin_draw calls/sec during a maximize→
        // restore window-state change, with the FX thread parked in
        // PaintCollector.waitForRenderingToComplete for 3+ seconds.
        //
        // Cap the retries; if the result still isn't valid after a few
        // attempts, give up and skip drawing this effect for this
        // pulse. The very next pulse retries the whole thing — same
        // recovery semantic as the existing `if (res == null) return;`
        // bail-out above. The cap is overridable for diagnosis.
        final int maxRetries = Math.max(1, Integer.getInteger(
            "prism.effect.maxValidateRetries", 3));
        int attempts = 0;
        do {
            ImageData res = effect.filter(fctx, transform, rclip, prinfo, defaultInput);
            if (res == null) return;

            valid = res.validate(fctx);
            String failReason = null;
            boolean degenerate = false;
            if (valid) {
                Rectangle r = res.getUntransformedBounds();
                // the actual image may be much larger than the region
                // of interest ("r"), so to improve performance we render
                // only that subregion here
                Texture tex = ((PrTexture)res.getUntransformedImage()).getTextureObject();
                g.setTransform(rendertx);
                g.transform(res.getTransform());
                g.drawTexture(tex, r.x, r.y, r.width, r.height);
            } else {
                // An invalid result with DEGENERATE bounds (null, or non-positive
                // width/height) means there is simply nothing to draw — typically
                // a transient layout/resize pulse where a node's clip-intersected
                // bounds collapse to zero or invert (Rectangle.intersectWith of
                // non-overlapping rects yields a negative extent, e.g.
                // width=72,height=-3). That is NOT a render failure: retrying it
                // is deterministic waste (the 3x re-render tax) and it shouldn't
                // log a give-up. Skip cleanly this pulse; the next pulse retries
                // the whole effect normally.
                Rectangle rb = res.getUntransformedBounds();
                degenerate = (rb == null) || rb.width <= 0 || rb.height <= 0;
                if (!degenerate) {
                    // Genuine failure (plausible bounds but null/lost image):
                    // capture WHY before unref so the give-up diagnostic is useful.
                    Object im = res.getUntransformedImage();
                    failReason = "image=" + (im == null ? "null" : "lost?")
                        + " bounds=" + rb;
                }
            }
            res.unref();
            if (degenerate) {
                break; // nothing to draw; not an error — no retry, no give-up
            }
            if (!valid && ++attempts >= maxRetries) {
                // Rate-limited diagnostic — only the FIRST give-up
                // prints; subsequent ones are silent so the log
                // doesn't flood when the failure persists across
                // pulses.
                // A one-pulse null/lost compatible-image is an expected transient
                // under render-target pool churn (e.g. a monitor-move / resize
                // storm): the effect simply drops for THIS pulse and the next
                // pulse re-renders it cleanly. So this is silent by default — gate
                // it behind -Dprism.effect.verbose=true for diagnosis. Still
                // rate-limited to the first occurrence when enabled.
                if (VERBOSE && PrEffectHelper.giveUpReported.compareAndSet(false, true)) {
                    System.err.println("[prism.effect] giving up on "
                        + effect.getClass().getSimpleName()
                        + " after " + attempts
                        + " failed validate() retries (" + failReason + ")"
                        + " — effect dropped "
                        + "for this pulse (next pulse will retry; tunable "
                        + "via -Dprism.effect.maxValidateRetries=N)");
                }
                break;
            }
        } while (!valid);
        g.setTransform(origtx);
    }

    static Point2D project(float x, float y, double vw, double vh,
                           NGCamera cam, BaseTransform inv,
                           PickRay tmpray, Vec3d tmpvec, Point2D ret)
    {
        // Calculations in cam.computePickRay are done relative to the
        // view w,h in the camera which may not match our actual view
        // dimensions so we scale them to that rectangle, compute the
        // pick rays, then scale the back to the actual device space before
        // intersecting with our chosen rendering plane.
        double xscale = cam.getViewWidth() / vw;
        double yscale = cam.getViewHeight() / vh;
        x *= xscale;
        y *= yscale;
        tmpray = cam.computePickRay(x, y, tmpray);
        unscale(tmpray.getOriginNoClone(), xscale, yscale);
        unscale(tmpray.getDirectionNoClone(), xscale, yscale);
        return tmpray.projectToZeroPlane(inv, cam instanceof NGPerspectiveCamera,
                                         tmpvec, ret);
    }
    private static void unscale(Vec3d v, double sx, double sy) {
        v.x /= sx;
        v.y /= sy;
    }

    static Rectangle clipbounds(Point2D cul, Point2D cur, Point2D cll, Point2D clr) {
        // Note that 3D perspective transforms frequently deal with infinite
        // values as a plane is rotated towards an end-on view from the eye.
        // The standard ways of getting the bounds of 4 float points tend to
        // ignore overflow, but we would frequently see trouble as objects are
        // flipped over if we didn't have the tests for integer overflow near
        // the bottom of this method.  When those conditions occur it usually
        // means we can see down an arbitrary distance (perhaps to the horizon)
        // on the plane of the node being rendered so we need to render it
        // with no clip to make sure we get all the data for the effect.
        if (cul != null && cur != null && cll != null && clr != null) {
            double x1, y1, x2, y2;
            if (cul.x < cur.x) {
                x1 = cul.x; x2 = cur.x;
            } else {
                x1 = cur.x; x2 = cul.x;
            }
            if (cul.y < cur.y) {
                y1 = cul.y; y2 = cur.y;
            } else {
                y1 = cur.y; y2 = cul.y;
            }
            if (cll.x < clr.x) {
                x1 = Math.min(x1, cll.x); x2 = Math.max(x2, clr.x);
            } else {
                x1 = Math.min(x1, clr.x); x2 = Math.max(x2, cll.x);
            }
            if (cll.y < clr.y) {
                y1 = Math.min(y1, cll.y); y2 = Math.max(y2, clr.y);
            } else {
                y1 = Math.min(y1, clr.y); y2 = Math.max(y2, cll.y);
            }
            // See FilterEffect.untransformClip for a description of
            // why we round out by half a pixel here.
            x1 = Math.floor(x1-0.5f);
            y1 = Math.floor(y1-0.5f);
            x2 = Math.ceil(x2+0.5f)-x1;
            y2 = Math.ceil(y2+0.5f)-y1;
            int x = (int) x1;
            int y = (int) y1;
            int w = (int) x2;
            int h = (int) y2;
            if (x == x1 && y == y1 && w == x2 && h == y2) {
                // Return a valid rectangle only if we do not overflow,
                // otherwise let the method return a null below for
                // unclipped operation.
                return new Rectangle(x, y, w, h);
            }
        }
        return null;
    }

    public static Rectangle getGraphicsClipNoClone(Graphics g) {
        Rectangle rclip = g.getClipRectNoClone();
        if (rclip == null) {
            RenderTarget rt = g.getRenderTarget();
            rclip = new Rectangle(rt.getContentWidth(), rt.getContentHeight());
        }
        return rclip;
    }

    public static void renderImageData(Graphics gdst,
                                       ImageData srcData,
                                       Rectangle dstBounds)
    {
        int w = dstBounds.width;
        int h = dstBounds.height;
        PrDrawable src = (PrDrawable) srcData.getUntransformedImage();
        BaseTransform srcTx = srcData.getTransform();
        Rectangle srcBounds = srcData.getUntransformedBounds();
        float dx1 = 0f;
        float dy1 = 0f;
        float dx2 = dx1 + w;
        float dy2 = dy1 + h;
        if (srcTx.isTranslateOrIdentity()) {
            float tx = (float) srcTx.getMxt();
            float ty = (float) srcTx.getMyt();
            float sx1 = dstBounds.x - (srcBounds.x + tx);
            float sy1 = dstBounds.y - (srcBounds.y + ty);
            float sx2 = sx1 + w;
            float sy2 = sy1 + h;
            gdst.drawTexture(src.getTextureObject(),
                             dx1, dy1, dx2, dy2,
                             sx1, sy1, sx2, sy2);
        } else {
            float[] srcRect = new float[8];
            int srcCoords =
                EffectPeer.getTextureCoordinates(srcRect,
                                                 srcBounds.x, srcBounds.y,
                                                 src.getPhysicalWidth(),
                                                 src.getPhysicalHeight(),
                                                 dstBounds, srcTx);
            if (srcCoords < 8) {
                gdst.drawTextureRaw(src.getTextureObject(),
                                    dx1, dy1, dx2, dy2,
                                    srcRect[0], srcRect[1],
                                    srcRect[2], srcRect[3]);
            } else {
                gdst.drawMappedTextureRaw(src.getTextureObject(),
                                          dx1, dy1, dx2, dy2,
                                          srcRect[0], srcRect[1],
                                          srcRect[4], srcRect[5],
                                          srcRect[6], srcRect[7],
                                          srcRect[2], srcRect[3]);
            }
        }
    }
}
