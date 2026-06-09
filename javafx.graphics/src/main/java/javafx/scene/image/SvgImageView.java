/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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

package javafx.scene.image;

import java.util.List;

import com.sun.javafx.css.CssUtil;
import com.sun.javafx.geom.BaseBounds;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.scene.DirtyBits;
import com.sun.javafx.scene.NodeHelper;
import com.sun.javafx.scene.SvgImageViewHelper;
import com.sun.javafx.sg.prism.NGNode;
import com.sun.javafx.sg.prism.NGSvgImageView;

import javafx.beans.InvalidationListener;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ObjectPropertyBase;
import javafx.css.CssMetaData;
import javafx.css.Styleable;
import javafx.css.StyleableBooleanProperty;
import javafx.css.StyleableDoubleProperty;
import javafx.css.StyleableObjectProperty;
import javafx.css.StyleableProperty;
import javafx.css.converter.BooleanConverter;
import javafx.css.converter.ColorConverter;
import javafx.css.converter.EnumConverter;
import javafx.css.converter.SizeConverter;
import javafx.scene.Node;
import javafx.scene.paint.Color;

/**
 * A resolution-independent {@link ImageView} that displays an {@link SvgImage}
 * as a crisp, hardware-accelerated vector graphic.
 *
 * <p>Where {@code ImageView} samples a fixed-resolution raster, {@code
 * SvgImageView} re-rasterizes its SVG through Skia at the exact device size it
 * is drawn at. The result stays <b>crystal-clear at any size, zoom level, or
 * screen DPI</b> — there is no upscaling blur. It is a {@link Node} like any
 * other, so it drops straight into a button, label, menu item, or tab as a
 * graphic.</p>
 *
 * <h2>Zooming</h2>
 * The view is freely zoomable via {@link #zoomProperty() zoom} (a multiplier on
 * the displayed size), bounded by {@link #minZoomProperty() minZoom} and
 * {@link #maxZoomProperty() maxZoom}. Each zoom step re-rasterizes the SVG at
 * the new resolution, so it is always sharp; the implementation reuses its GPU
 * target across frames so zooming stays fast. Convenience methods
 * {@link #zoomIn()}, {@link #zoomOut()} and {@link #resetZoom()} are provided.
 *
 * <h2>Sizing</h2>
 * {@link #fitWidthProperty() fitWidth} / {@link #fitHeightProperty() fitHeight}
 * and {@link #preserveRatioProperty() preserveRatio} (inherited from
 * {@code ImageView}) choose the base display size from the SVG's intrinsic
 * size, exactly as for a raster image; {@code zoom} then scales that.
 *
 * <h2>Color &amp; styling (CSS)</h2>
 * The rendered output can be recolored and decorated at the node level — the
 * SVG's own paint is never edited:
 * <ul>
 *   <li>{@link #tintProperty() tint} + {@link #tintModeProperty() tintMode}
 *       ({@code -svg-tint}, {@code -svg-tint-mode}) recolor the output; ideal
 *       for monochrome icons.</li>
 *   <li>{@link #backgroundColorProperty() backgroundColor}
 *       ({@code -svg-background-color}) fills the node box behind the SVG.</li>
 *   <li>A configurable <b>grid</b> can be drawn over the SVG (an inspector /
 *       measurement overlay, visible on any artwork — opaque or not):
 *       {@link #gridVisibleProperty() gridVisible} ({@code -svg-grid-visible}),
 *       {@link #gridColorProperty() gridColor} ({@code -svg-grid-color}),
 *       {@link #gridSpacingProperty() gridSpacing} ({@code -svg-grid-spacing}),
 *       and {@link #gridLineWidthProperty() gridLineWidth}
 *       ({@code -svg-grid-line-width}). The grid scales with {@code zoom}, like
 *       graph paper you zoom into.</li>
 * </ul>
 *
 * <pre>{@code
 * SvgImageView icon = new SvgImageView(new SvgImage("/icons/save.svg"));
 * icon.setFitWidth(24);            // 24-px icon, crisp on any display
 * button.setGraphic(icon);
 *
 * SvgImageView canvas = new SvgImageView("/art/diagram.svg");
 * canvas.setMaxZoom(32);
 * canvas.setGridVisible(true);
 * canvas.setGridColor(Color.gray(0.5, 0.4));
 * canvas.setGridSpacing(16);
 * scrollPane.setContent(canvas);
 * }</pre>
 *
 * @see SvgImage
 * @since 26
 */
public class SvgImageView extends ImageView {

    /** How {@link #tintProperty() tint} is combined with the SVG output. */
    public enum TintMode {
        /** No tint; the SVG renders in its authored colors. (ordinal 0) */
        NONE,
        /** Replace the SVG's colors with the tint, keeping its shape/alpha —
         *  flattens a multicolor SVG to one color. Best for mono icons. */
        SRC_IN,
        /** Multiply the SVG's colors by the tint (modulate). */
        MULTIPLY
    }

    static {
        SvgImageViewHelper.setSvgImageViewAccessor(new SvgImageViewHelper.SvgImageViewAccessor() {
            @Override
            public NGNode doCreatePeer(Node node) {
                return ((SvgImageView) node).doCreatePeer();
            }

            @Override
            public void doUpdatePeer(Node node) {
                ((SvgImageView) node).doUpdatePeer();
            }

            @Override
            public BaseBounds doComputeGeomBounds(Node node, BaseBounds bounds, BaseTransform tx) {
                return ((SvgImageView) node).doComputeGeomBounds(bounds, tx);
            }

            @Override
            public boolean doComputeContains(Node node, double localX, double localY) {
                return ((SvgImageView) node).doComputeContains(localX, localY);
            }
        });
    }

    {
        // Installs SvgImageViewHelper, overriding the ImageViewHelper that
        // ImageView's own initializer installed (this runs afterwards).
        SvgImageViewHelper.initHelper(this);

        // The display size is derived from the inherited fitWidth / fitHeight /
        // preserveRatio. Their own invalidation clears ImageView's PRIVATE
        // width/height cache, not ours, so hook them to also invalidate our
        // geometry cache — otherwise changing fitWidth after the first layout
        // would leave the SVG at its stale size.
        InvalidationListener invalidateGeometry = o -> invalidateGeom();
        fitWidthProperty().addListener(invalidateGeometry);
        fitHeightProperty().addListener(invalidateGeometry);
        preserveRatioProperty().addListener(invalidateGeometry);
    }

    private static final String DEFAULT_STYLE_CLASS = "svg-image-view";

    /** Allocates an empty {@code SvgImageView}; set a source via {@link #setSvgImage}. */
    public SvgImageView() {
        getStyleClass().setAll(DEFAULT_STYLE_CLASS);
    }

    /**
     * Allocates an {@code SvgImageView} showing the given SVG image.
     * @param svgImage the SVG image to display
     */
    public SvgImageView(SvgImage svgImage) {
        getStyleClass().setAll(DEFAULT_STYLE_CLASS);
        setSvgImage(svgImage);
    }

    /**
     * Allocates an {@code SvgImageView} loading an SVG from the given URL /
     * resource path (same resolution rules as {@link SvgImage#SvgImage(String)}).
     * @param url the SVG resource path, file path, or URL
     */
    public SvgImageView(String url) {
        this(new SvgImage(url));
    }

    /* ********************************************************************** *
     *  Source                                                                *
     * ********************************************************************** */

    private ObjectProperty<SvgImage> svgImage;

    public final void setSvgImage(SvgImage value) { svgImageProperty().set(value); }
    public final SvgImage getSvgImage() { return svgImage == null ? null : svgImage.get(); }

    /**
     * The {@link SvgImage} displayed by this view.
     * @return the svgImage property
     * @defaultValue null
     */
    public final ObjectProperty<SvgImage> svgImageProperty() {
        if (svgImage == null) {
            svgImage = new SimpleObjectProp<>("svgImage") {
                @Override protected void invalidated() {
                    invalidateGeom();
                    NodeHelper.markDirty(SvgImageView.this, DirtyBits.NODE_CONTENTS);
                    NodeHelper.geomChanged(SvgImageView.this);
                }
            };
        }
        return svgImage;
    }

    /* ********************************************************************** *
     *  Zoom                                                                  *
     * ********************************************************************** */

    private StyleableDoubleProperty zoom;
    private StyleableDoubleProperty minZoom;
    private StyleableDoubleProperty maxZoom;

    public final void setZoom(double value) { zoomProperty().set(value); }
    public final double getZoom() { return zoom == null ? 1.0 : zoom.get(); }

    /**
     * The zoom multiplier applied to the display size. Effective rendering is
     * clamped to {@code [minZoom, maxZoom]}. Styleable via {@code -svg-zoom}.
     * @return the zoom property
     * @defaultValue 1.0
     */
    public final DoubleProperty zoomProperty() {
        if (zoom == null) {
            zoom = new GeomStyleableDouble("zoom", 1.0, StyleableProperties.ZOOM);
        }
        return zoom;
    }

    public final void setMinZoom(double value) { minZoomProperty().set(value); }
    public final double getMinZoom() { return minZoom == null ? 0.1 : minZoom.get(); }

    /**
     * Lower bound for the effective {@link #zoomProperty() zoom}. Styleable via
     * {@code -svg-min-zoom}.
     * @return the minZoom property
     * @defaultValue 0.1
     */
    public final DoubleProperty minZoomProperty() {
        if (minZoom == null) {
            minZoom = new GeomStyleableDouble("minZoom", 0.1, StyleableProperties.MIN_ZOOM);
        }
        return minZoom;
    }

    public final void setMaxZoom(double value) { maxZoomProperty().set(value); }
    public final double getMaxZoom() { return maxZoom == null ? 64.0 : maxZoom.get(); }

    /**
     * Upper bound for the effective {@link #zoomProperty() zoom}. Styleable via
     * {@code -svg-max-zoom}.
     * @return the maxZoom property
     * @defaultValue 64.0
     */
    public final DoubleProperty maxZoomProperty() {
        if (maxZoom == null) {
            maxZoom = new GeomStyleableDouble("maxZoom", 64.0, StyleableProperties.MAX_ZOOM);
        }
        return maxZoom;
    }

    /** The {@link #zoomProperty() zoom} clamped to {@code [minZoom, maxZoom]}. */
    public final double getEffectiveZoom() {
        double z = getZoom();
        if (!Double.isFinite(z)) z = 1.0;   // NaN/Inf would poison the clamp
        double lo = getMinZoom();
        if (!Double.isFinite(lo) || lo <= 0) lo = 0.001;
        double hi = getMaxZoom();
        if (!Double.isFinite(hi) || hi < lo) hi = lo;
        return Math.max(lo, Math.min(hi, z));
    }

    /** Multiplies the current zoom by {@code 1.25} (clamped). */
    public final void zoomIn() { setZoom(getEffectiveZoom() * 1.25); }
    /** Divides the current zoom by {@code 1.25} (clamped). */
    public final void zoomOut() { setZoom(getEffectiveZoom() / 1.25); }
    /** Resets the zoom to {@code 1.0}. */
    public final void resetZoom() { setZoom(1.0); }

    /* ********************************************************************** *
     *  Color: tint + background                                              *
     * ********************************************************************** */

    private StyleableObjectProperty<Color> tint;
    private StyleableObjectProperty<TintMode> tintMode;
    private StyleableObjectProperty<Color> backgroundColor;

    public final void setTint(Color value) { tintProperty().set(value); }
    public final Color getTint() { return tint == null ? null : tint.get(); }

    /**
     * A node-level recolor applied to the rendered SVG (never edits the SVG's
     * paint). {@code null} keeps the SVG's authored colors. Styleable via
     * {@code -svg-tint}. See {@link #tintModeProperty() tintMode}.
     * @return the tint property
     * @defaultValue null
     */
    public final ObjectProperty<Color> tintProperty() {
        if (tint == null) {
            tint = new VisualStyleableObject<>("tint", null, StyleableProperties.TINT);
        }
        return tint;
    }

    public final void setTintMode(TintMode value) { tintModeProperty().set(value); }
    public final TintMode getTintMode() { return tintMode == null ? TintMode.NONE : tintMode.get(); }

    /**
     * How {@link #tintProperty() tint} is combined with the SVG. Styleable via
     * {@code -svg-tint-mode}.
     * @return the tintMode property
     * @defaultValue {@link TintMode#NONE}
     */
    public final ObjectProperty<TintMode> tintModeProperty() {
        if (tintMode == null) {
            tintMode = new VisualStyleableObject<>("tintMode", TintMode.NONE, StyleableProperties.TINT_MODE);
        }
        return tintMode;
    }

    public final void setBackgroundColor(Color value) { backgroundColorProperty().set(value); }
    public final Color getBackgroundColor() { return backgroundColor == null ? null : backgroundColor.get(); }

    /**
     * A solid fill painted in the node box behind the SVG (and behind the
     * grid). {@code null} means transparent. Styleable via
     * {@code -svg-background-color}.
     * @return the backgroundColor property
     * @defaultValue null
     */
    public final ObjectProperty<Color> backgroundColorProperty() {
        if (backgroundColor == null) {
            backgroundColor = new VisualStyleableObject<>("backgroundColor", null,
                StyleableProperties.BACKGROUND_COLOR);
        }
        return backgroundColor;
    }

    /* ********************************************************************** *
     *  Grid backdrop                                                         *
     * ********************************************************************** */

    private StyleableBooleanProperty gridVisible;
    private StyleableObjectProperty<Color> gridColor;
    private StyleableDoubleProperty gridSpacing;
    private StyleableDoubleProperty gridLineWidth;

    public final void setGridVisible(boolean value) { gridVisibleProperty().set(value); }
    public final boolean isGridVisible() { return gridVisible != null && gridVisible.get(); }

    /**
     * Whether a grid is drawn over the SVG (an overlay). Styleable via
     * {@code -svg-grid-visible}.
     * @return the gridVisible property
     * @defaultValue false
     */
    public final BooleanProperty gridVisibleProperty() {
        if (gridVisible == null) {
            gridVisible = new VisualStyleableBoolean("gridVisible", false,
                StyleableProperties.GRID_VISIBLE);
        }
        return gridVisible;
    }

    public final void setGridColor(Color value) { gridColorProperty().set(value); }
    public final Color getGridColor() {
        return gridColor == null ? DEFAULT_GRID_COLOR : gridColor.get();
    }

    private static final Color DEFAULT_GRID_COLOR = Color.gray(0.5, 0.35);

    /**
     * The color of the grid lines. Styleable via {@code -svg-grid-color}.
     * @return the gridColor property
     * @defaultValue a translucent gray
     */
    public final ObjectProperty<Color> gridColorProperty() {
        if (gridColor == null) {
            gridColor = new VisualStyleableObject<>("gridColor", DEFAULT_GRID_COLOR,
                StyleableProperties.GRID_COLOR);
        }
        return gridColor;
    }

    public final void setGridSpacing(double value) { gridSpacingProperty().set(value); }
    public final double getGridSpacing() { return gridSpacing == null ? 16.0 : gridSpacing.get(); }

    /**
     * Spacing between grid lines, in unzoomed display pixels (the grid scales
     * with {@link #zoomProperty() zoom}). Styleable via {@code -svg-grid-spacing}.
     * @return the gridSpacing property
     * @defaultValue 16.0
     */
    public final DoubleProperty gridSpacingProperty() {
        if (gridSpacing == null) {
            gridSpacing = new VisualStyleableDouble("gridSpacing", 16.0,
                StyleableProperties.GRID_SPACING);
        }
        return gridSpacing;
    }

    public final void setGridLineWidth(double value) { gridLineWidthProperty().set(value); }
    public final double getGridLineWidth() { return gridLineWidth == null ? 1.0 : gridLineWidth.get(); }

    /**
     * Thickness of the grid lines, in display pixels (constant on screen,
     * does not scale with zoom). Styleable via {@code -svg-grid-line-width}.
     * @return the gridLineWidth property
     * @defaultValue 1.0
     */
    public final DoubleProperty gridLineWidthProperty() {
        if (gridLineWidth == null) {
            gridLineWidth = new VisualStyleableDouble("gridLineWidth", 1.0,
                StyleableProperties.GRID_LINE_WIDTH);
        }
        return gridLineWidth;
    }

    /* ********************************************************************** *
     *  Geometry                                                              *
     * ********************************************************************** */

    private boolean validWH;
    private double dispWidth, dispHeight;

    private void invalidateGeom() { validWH = false; }

    private void recomputeWidthHeight() {
        if (validWH) return;
        SvgImage svg = getSvgImage();
        double iw = svg == null ? 0 : svg.getWidth();
        double ih = svg == null ? 0 : svg.getHeight();
        double w = 0, h = 0;
        if (iw > 0 && ih > 0) {
            double fw = getFitWidth();
            double fh = getFitHeight();
            if (isPreserveRatio()) {
                if (fw <= 0 && fh <= 0) {
                    w = iw; h = ih;
                } else if (fw > 0 && fh <= 0) {
                    w = fw; h = ih * fw / iw;
                } else if (fh > 0 && fw <= 0) {
                    h = fh; w = iw * fh / ih;
                } else {
                    double s = Math.min(fw / iw, fh / ih);
                    w = iw * s; h = ih * s;
                }
            } else {
                w = fw > 0 ? fw : iw;
                h = fh > 0 ? fh : ih;
            }
        }
        double z = getEffectiveZoom();
        dispWidth = w * z;
        dispHeight = h * z;
        // Guard against a non-finite fitWidth/fitHeight poisoning bounds + the
        // native draw (NaN slips past a plain "<= 0" check).
        if (!Double.isFinite(dispWidth) || !Double.isFinite(dispHeight)
                || dispWidth < 0 || dispHeight < 0) {
            dispWidth = 0;
            dispHeight = 0;
        }
        validWH = true;
    }

    /*
     * Note: This method MUST only be called via its accessor method.
     */
    private NGNode doCreatePeer() {
        return new NGSvgImageView();
    }

    /*
     * Note: This method MUST only be called via its accessor method.
     */
    private BaseBounds doComputeGeomBounds(BaseBounds bounds, BaseTransform tx) {
        recomputeWidthHeight();
        bounds = bounds.deriveWithNewBounds((float) getX(), (float) getY(), 0.0f,
                (float) (getX() + dispWidth), (float) (getY() + dispHeight), 0.0f);
        bounds = tx.transform(bounds, bounds);
        return bounds;
    }

    /*
     * Note: This method MUST only be called via its accessor method.
     */
    private boolean doComputeContains(double localX, double localY) {
        recomputeWidthHeight();
        double dx = localX - getX();
        double dy = localY - getY();
        return dx >= 0 && dy >= 0 && dx < dispWidth && dy < dispHeight;
    }

    /*
     * Note: This method MUST only be called via its accessor method.
     */
    private void doUpdatePeer() {
        final NGSvgImageView peer = NodeHelper.getPeer(this);
        recomputeWidthHeight();

        SvgImage svg = getSvgImage();
        peer.setSvgHandle(svg == null ? 0L : svg.nativeHandle());
        peer.setPosition((float) getX(), (float) getY());
        peer.setDimensions((float) dispWidth, (float) dispHeight);

        int bg = argb(getBackgroundColor());
        Color tintColor = getTint();
        TintMode mode = getTintMode();
        int tintArgb = (mode == TintMode.NONE) ? 0 : argb(tintColor);
        int tintModeOrd = (tintColor == null) ? 0 : mode.ordinal();
        peer.setColors(bg, tintArgb, tintModeOrd);

        if (isGridVisible() && getGridSpacing() > 0) {
            // Grid spacing is authored unzoomed; pre-multiply by the effective
            // zoom so it tracks the content. Line width stays constant on screen.
            float cell = (float) (getGridSpacing() * getEffectiveZoom());
            peer.setGrid(argb(getGridColor()), cell, (float) getGridLineWidth());
        } else {
            peer.setGrid(0, 0f, 0f);
        }
    }

    /** Packs a {@link Color} into a 0xAARRGGBB int; {@code null} → 0. */
    private static int argb(Color c) {
        if (c == null) return 0;
        int a = (int) Math.round(c.getOpacity() * 255.0);
        int r = (int) Math.round(c.getRed() * 255.0);
        int g = (int) Math.round(c.getGreen() * 255.0);
        int b = (int) Math.round(c.getBlue() * 255.0);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /* ********************************************************************** *
     *  Property base classes (shared invalidation behavior)                 *
     * ********************************************************************** */

    private abstract class SimpleObjectProp<T> extends ObjectPropertyBase<T> {
        private final String name;
        SimpleObjectProp(String name) { this.name = name; }
        @Override public Object getBean() { return SvgImageView.this; }
        @Override public String getName() { return name; }
    }

    /** Styleable double that affects geometry (size) → geomChanged. */
    private final class GeomStyleableDouble extends StyleableDoubleProperty {
        private final String name;
        private final CssMetaData<? extends Styleable, Number> md;
        GeomStyleableDouble(String name, double initial, CssMetaData<? extends Styleable, Number> md) {
            super(initial); this.name = name; this.md = md;
        }
        @Override protected void invalidated() {
            invalidateGeom();
            NodeHelper.markDirty(SvgImageView.this, DirtyBits.NODE_CONTENTS);
            NodeHelper.geomChanged(SvgImageView.this);
        }
        @Override public Object getBean() { return SvgImageView.this; }
        @Override public String getName() { return name; }
        @Override public CssMetaData<? extends Styleable, Number> getCssMetaData() { return md; }
    }

    /** Styleable double that only affects pixels (e.g. grid) → NODE_CONTENTS. */
    private final class VisualStyleableDouble extends StyleableDoubleProperty {
        private final String name;
        private final CssMetaData<? extends Styleable, Number> md;
        VisualStyleableDouble(String name, double initial, CssMetaData<? extends Styleable, Number> md) {
            super(initial); this.name = name; this.md = md;
        }
        @Override protected void invalidated() {
            NodeHelper.markDirty(SvgImageView.this, DirtyBits.NODE_CONTENTS);
        }
        @Override public Object getBean() { return SvgImageView.this; }
        @Override public String getName() { return name; }
        @Override public CssMetaData<? extends Styleable, Number> getCssMetaData() { return md; }
    }

    private final class VisualStyleableBoolean extends StyleableBooleanProperty {
        private final String name;
        private final CssMetaData<? extends Styleable, Boolean> md;
        VisualStyleableBoolean(String name, boolean initial, CssMetaData<? extends Styleable, Boolean> md) {
            super(initial); this.name = name; this.md = md;
        }
        @Override protected void invalidated() {
            NodeHelper.markDirty(SvgImageView.this, DirtyBits.NODE_CONTENTS);
        }
        @Override public Object getBean() { return SvgImageView.this; }
        @Override public String getName() { return name; }
        @Override public CssMetaData<? extends Styleable, Boolean> getCssMetaData() { return md; }
    }

    private final class VisualStyleableObject<T> extends StyleableObjectProperty<T> {
        private final String name;
        private final CssMetaData<? extends Styleable, T> md;
        VisualStyleableObject(String name, T initial, CssMetaData<? extends Styleable, T> md) {
            super(initial); this.name = name; this.md = md;
        }
        @Override protected void invalidated() {
            NodeHelper.markDirty(SvgImageView.this, DirtyBits.NODE_CONTENTS);
        }
        @Override public Object getBean() { return SvgImageView.this; }
        @Override public String getName() { return name; }
        @Override public CssMetaData<? extends Styleable, T> getCssMetaData() { return md; }
    }

    /* ********************************************************************** *
     *  CSS metadata                                                          *
     * ********************************************************************** */

    private static class StyleableProperties {
        private static final CssMetaData<SvgImageView, Number> ZOOM =
            new CssMetaData<>("-svg-zoom", SizeConverter.getInstance(), 1.0) {
                @Override public boolean isSettable(SvgImageView n) { return n.zoom == null || !n.zoom.isBound(); }
                @Override public StyleableProperty<Number> getStyleableProperty(SvgImageView n) {
                    return (StyleableProperty<Number>) n.zoomProperty();
                }
            };
        private static final CssMetaData<SvgImageView, Number> MIN_ZOOM =
            new CssMetaData<>("-svg-min-zoom", SizeConverter.getInstance(), 0.1) {
                @Override public boolean isSettable(SvgImageView n) { return n.minZoom == null || !n.minZoom.isBound(); }
                @Override public StyleableProperty<Number> getStyleableProperty(SvgImageView n) {
                    return (StyleableProperty<Number>) n.minZoomProperty();
                }
            };
        private static final CssMetaData<SvgImageView, Number> MAX_ZOOM =
            new CssMetaData<>("-svg-max-zoom", SizeConverter.getInstance(), 64.0) {
                @Override public boolean isSettable(SvgImageView n) { return n.maxZoom == null || !n.maxZoom.isBound(); }
                @Override public StyleableProperty<Number> getStyleableProperty(SvgImageView n) {
                    return (StyleableProperty<Number>) n.maxZoomProperty();
                }
            };
        private static final CssMetaData<SvgImageView, Color> TINT =
            new CssMetaData<>("-svg-tint", ColorConverter.getInstance(), null) {
                @Override public boolean isSettable(SvgImageView n) { return n.tint == null || !n.tint.isBound(); }
                @Override public StyleableProperty<Color> getStyleableProperty(SvgImageView n) {
                    return (StyleableProperty<Color>) n.tintProperty();
                }
            };
        private static final CssMetaData<SvgImageView, TintMode> TINT_MODE =
            new CssMetaData<>("-svg-tint-mode", new EnumConverter<>(TintMode.class), TintMode.NONE) {
                @Override public boolean isSettable(SvgImageView n) { return n.tintMode == null || !n.tintMode.isBound(); }
                @Override public StyleableProperty<TintMode> getStyleableProperty(SvgImageView n) {
                    return (StyleableProperty<TintMode>) n.tintModeProperty();
                }
            };
        private static final CssMetaData<SvgImageView, Color> BACKGROUND_COLOR =
            new CssMetaData<>("-svg-background-color", ColorConverter.getInstance(), null) {
                @Override public boolean isSettable(SvgImageView n) { return n.backgroundColor == null || !n.backgroundColor.isBound(); }
                @Override public StyleableProperty<Color> getStyleableProperty(SvgImageView n) {
                    return (StyleableProperty<Color>) n.backgroundColorProperty();
                }
            };
        private static final CssMetaData<SvgImageView, Boolean> GRID_VISIBLE =
            new CssMetaData<>("-svg-grid-visible", BooleanConverter.getInstance(), Boolean.FALSE) {
                @Override public boolean isSettable(SvgImageView n) { return n.gridVisible == null || !n.gridVisible.isBound(); }
                @Override public StyleableProperty<Boolean> getStyleableProperty(SvgImageView n) {
                    return (StyleableProperty<Boolean>) n.gridVisibleProperty();
                }
            };
        private static final CssMetaData<SvgImageView, Color> GRID_COLOR =
            new CssMetaData<>("-svg-grid-color", ColorConverter.getInstance(), DEFAULT_GRID_COLOR) {
                @Override public boolean isSettable(SvgImageView n) { return n.gridColor == null || !n.gridColor.isBound(); }
                @Override public StyleableProperty<Color> getStyleableProperty(SvgImageView n) {
                    return (StyleableProperty<Color>) n.gridColorProperty();
                }
            };
        private static final CssMetaData<SvgImageView, Number> GRID_SPACING =
            new CssMetaData<>("-svg-grid-spacing", SizeConverter.getInstance(), 16.0) {
                @Override public boolean isSettable(SvgImageView n) { return n.gridSpacing == null || !n.gridSpacing.isBound(); }
                @Override public StyleableProperty<Number> getStyleableProperty(SvgImageView n) {
                    return (StyleableProperty<Number>) n.gridSpacingProperty();
                }
            };
        private static final CssMetaData<SvgImageView, Number> GRID_LINE_WIDTH =
            new CssMetaData<>("-svg-grid-line-width", SizeConverter.getInstance(), 1.0) {
                @Override public boolean isSettable(SvgImageView n) { return n.gridLineWidth == null || !n.gridLineWidth.isBound(); }
                @Override public StyleableProperty<Number> getStyleableProperty(SvgImageView n) {
                    return (StyleableProperty<Number>) n.gridLineWidthProperty();
                }
            };

        private static final List<CssMetaData<? extends Styleable, ?>> STYLEABLES = CssUtil.combine(
            ImageView.getClassCssMetaData(),
            ZOOM, MIN_ZOOM, MAX_ZOOM,
            TINT, TINT_MODE, BACKGROUND_COLOR,
            GRID_VISIBLE, GRID_COLOR, GRID_SPACING, GRID_LINE_WIDTH
        );
    }

    /**
     * Gets the {@code CssMetaData} associated with this class, which may
     * include the {@code CssMetaData} of its superclasses.
     * @return the {@code CssMetaData}
     */
    public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
        return StyleableProperties.STYLEABLES;
    }

    @Override
    public List<CssMetaData<? extends Styleable, ?>> getCssMetaData() {
        return getClassCssMetaData();
    }
}
