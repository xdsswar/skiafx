/*
 * skia-fx custom decorations — per-stage hit-region holder.
 */
package com.sun.javafx.stage.custom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.WindowCornerPreference;

/**
 * Internal holder for the custom-decoration state of a single
 * {@link javafx.stage.Stage} created with
 * {@link javafx.stage.StageStyle#CUSTOM}.
 *
 * <p>Holds the application-declared hit regions (caption area,
 * close / max / min / full-screen / system-menu buttons) plus the
 * DWM accent state (title bar / text / border colours and corner
 * preference). The native side reads the regions snapshot on each
 * {@code WM_NCHITTEST}; mutations on the FX thread publish a new
 * immutable snapshot atomically via the {@code volatile} reference.</p>
 *
 * <p>For stages whose style is <em>not</em> CUSTOM, this holder
 * still accepts mutations (silent no-op policy) — the values are
 * stored but never consulted.</p>
 *
 * <p>Not part of any public API.</p>
 */
public final class CustomDecorations {

    /** CSS pseudo-class applied to the close-region node while hovered. */
    public static final PseudoClass HT_CLOSE_PC    = PseudoClass.getPseudoClass("ht-close");
    /** CSS pseudo-class applied to the max-region node while hovered. */
    public static final PseudoClass HT_MAX_PC      = PseudoClass.getPseudoClass("ht-max");
    /** CSS pseudo-class applied to the min-region node while hovered. */
    public static final PseudoClass HT_MIN_PC      = PseudoClass.getPseudoClass("ht-min");
    /** CSS pseudo-class applied to the full-screen-region node while hovered. */
    public static final PseudoClass HT_FSCREEN_PC  = PseudoClass.getPseudoClass("ht-fscreen");
    /** CSS pseudo-class applied to any caption-region node while hovered. */
    public static final PseudoClass HT_CAPTION_PC  = PseudoClass.getPseudoClass("ht-caption");

    // ---- region state -----------------------------------------------------

    private volatile List<HitRegion> snapshot = List.of();
    private List<Region> captionRegions = List.of();
    private Region closeRegion;
    private Region maxRegion;
    private Region minRegion;
    private Region fullScreenRegion;
    private Region sysMenuRegion;

    // ---- DWM accent state -------------------------------------------------

    private Color titleBarColor;
    private Color captionTextColor;
    private Color borderColor;
    private WindowCornerPreference cornerPreference = WindowCornerPreference.DEFAULT;

    /**
     * Immutable snapshot of hit regions in priority order (button
     * regions before caption regions — the native walk returns the
     * first match). Safe to read from any thread.
     */
    public List<HitRegion> snapshot() {
        return snapshot;
    }

    // ---- region setters (called from Stage public API on FX thread) -------

    public void setCaptionRegions(Region... regions) {
        captionRegions = regions == null || regions.length == 0
            ? List.of()
            : List.copyOf(Arrays.asList(regions));
        rebuildSnapshot();
    }

    public void setCloseRegion(Region region)        { closeRegion       = region; rebuildSnapshot(); }
    public void setMaxRegion(Region region)          { maxRegion         = region; rebuildSnapshot(); }
    public void setMinRegion(Region region)          { minRegion         = region; rebuildSnapshot(); }
    public void setFullScreenRegion(Region region)   { fullScreenRegion  = region; rebuildSnapshot(); }
    public void setSystemMenuRegion(Region region)   { sysMenuRegion     = region; rebuildSnapshot(); }

    public List<Region> getCaptionRegions()  { return captionRegions; }
    public Region getCloseRegion()           { return closeRegion; }
    public Region getMaxRegion()             { return maxRegion; }
    public Region getMinRegion()             { return minRegion; }
    public Region getFullScreenRegion()      { return fullScreenRegion; }
    public Region getSystemMenuRegion()      { return sysMenuRegion; }

    // ---- DWM accent setters ----------------------------------------------

    public void setTitleBarColor(Color c)                       { this.titleBarColor    = c; }
    public void setCaptionTextColor(Color c)                    { this.captionTextColor = c; }
    public void setBorderColor(Color c)                         { this.borderColor      = c; }
    public void setCornerPreference(WindowCornerPreference p)   {
        this.cornerPreference = Objects.requireNonNullElse(p, WindowCornerPreference.DEFAULT);
    }

    public Color getTitleBarColor()                  { return titleBarColor; }
    public Color getCaptionTextColor()               { return captionTextColor; }
    public Color getBorderColor()                    { return borderColor; }
    public WindowCornerPreference getCornerPreference() { return cornerPreference; }

    // ---- hover tracking --------------------------------------------------

    /**
     * Whichever {@link HitRegion} the cursor is currently over, or
     * {@code null} if not over a registered region. Mutated by the
     * platform message thread via {@link #updateHovered(HitRegion)};
     * the corresponding pseudo-class change is dispatched to the FX
     * thread.
     */
    private volatile HitRegion currentHovered;

    /**
     * Walk the snapshot and return the first {@link HitRegion} whose
     * scene bounds contain the given (DIP) coordinates, or
     * {@code null} if none. Order matches {@link #rebuildSnapshot()}:
     * buttons take precedence over caption regions when their bounds
     * overlap. Safe to call from any thread — reads the immutable
     * snapshot and {@code localToScene} bounds via JFX's thread-safe
     * snapshot accessors.
     */
    public HitRegion findRegionAt(double sceneX, double sceneY) {
        for (HitRegion r : snapshot) {
            Region n = r.node();
            if (n == null) continue;
            Bounds b;
            try {
                b = n.localToScene(n.getBoundsInLocal());
            } catch (Throwable t) {
                continue;
            }
            if (b == null) continue;
            if (sceneX >= b.getMinX() && sceneX < b.getMaxX()
                && sceneY >= b.getMinY() && sceneY < b.getMaxY()) {
                return r;
            }
        }
        return null;
    }

    /**
     * Update the currently-hovered region. If different from the
     * previous one, schedule a pseudo-class flip on the FX thread:
     * remove the {@code :ht-*} pseudo-class from the old region's
     * node and set it on the new one's. Cheap no-op when the hover
     * didn't change (the common case during steady-state mouse
     * tracking inside a single region).
     *
     * <p>Called from the platform message thread on every WM_NCHITTEST
     * (or platform equivalent); the actual JFX state mutation always
     * runs on the FX thread.</p>
     */
    public void updateHovered(HitRegion next) {
        HitRegion prev = currentHovered;
        if (prev == next) return;
        currentHovered = next;
        Platform.runLater(() -> {
            if (prev != null) {
                Region pn = prev.node();
                PseudoClass pc = pseudoClassFor(prev.htCode());
                if (pn != null && pc != null) {
                    pn.pseudoClassStateChanged(pc, false);
                }
            }
            if (next != null) {
                Region nn = next.node();
                PseudoClass pc = pseudoClassFor(next.htCode());
                if (nn != null && pc != null) {
                    nn.pseudoClassStateChanged(pc, true);
                }
            }
        });
    }

    /** Map an HT_* code to its {@code :ht-*} pseudo-class. */
    private static PseudoClass pseudoClassFor(int htCode) {
        return switch (htCode) {
            case HitRegion.HT_CLOSE      -> HT_CLOSE_PC;
            case HitRegion.HT_MAX_BUTTON -> HT_MAX_PC;
            case HitRegion.HT_MIN_BUTTON -> HT_MIN_PC;
            case HitRegion.HT_FSCREEN    -> HT_FSCREEN_PC;
            case HitRegion.HT_CAPTION    -> HT_CAPTION_PC;
            default                       -> null;
        };
    }

    // ---- snapshot construction -------------------------------------------

    /**
     * Build a new immutable snapshot from the current field values
     * and publish it via the volatile reference. Buttons come first
     * (they take precedence over the caption drag area when a single
     * Region covers both — e.g. the close button being inside the
     * caption bar).
     */
    private void rebuildSnapshot() {
        List<HitRegion> next = new ArrayList<>(8);
        // Use the canonical Win32 HT* codes for each button — that's
        // what activates the OS-side behaviors we want to keep:
        // HTMAXBUTTON triggers Win11 snap-layouts hover, the close /
        // minimize / maximize actions all run with proper system
        // animations, and the system menu opens on right-click for
        // HTCAPTION / HTSYSMENU. Same mapping as nfx-lib.
        if (closeRegion      != null) next.add(new HitRegion(closeRegion,      HitRegion.HT_CLOSE));
        if (maxRegion        != null) next.add(new HitRegion(maxRegion,        HitRegion.HT_MAX_BUTTON));
        if (minRegion        != null) next.add(new HitRegion(minRegion,        HitRegion.HT_MIN_BUTTON));
        if (fullScreenRegion != null) next.add(new HitRegion(fullScreenRegion, HitRegion.HT_FSCREEN));
        if (sysMenuRegion    != null) next.add(new HitRegion(sysMenuRegion,    HitRegion.HT_SYS_MENU));
        for (Region r : captionRegions) {
            if (r != null) next.add(new HitRegion(r, HitRegion.HT_CAPTION));
        }
        snapshot = Collections.unmodifiableList(next);
    }
}
