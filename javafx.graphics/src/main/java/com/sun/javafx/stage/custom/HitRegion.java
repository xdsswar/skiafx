/*
 * skia-fx custom decorations — internal hit-region descriptor.
 */
package com.sun.javafx.stage.custom;

import javafx.scene.layout.Region;

/**
 * Internal descriptor for a single non-client hit region on a
 * custom-decorated window.
 *
 * <p>One region maps to one Win32 HTCODE constant (or platform
 * equivalent). The native side queries {@code CustomDecorations}
 * for a snapshot of these on every {@code WM_NCHITTEST}, walks the
 * list, and returns the matching {@code htCode} for the first
 * region whose scene bounds contain the cursor point (in DIP).</p>
 *
 * <p>Not part of any public API; never escapes the {@code
 * com.sun.javafx} package boundary.</p>
 */
public record HitRegion(Region node, int htCode) {

    /** {@code HTCLIENT} — normal client area. */
    public static final int HT_CLIENT     = 1;
    /** {@code HTCAPTION} — draggable title bar area. */
    public static final int HT_CAPTION    = 2;
    /** {@code HTSYSMENU} — system-menu hot zone (icon area). */
    public static final int HT_SYS_MENU   = 3;
    /** {@code HTMINBUTTON} — minimize button. */
    public static final int HT_MIN_BUTTON = 8;
    /** {@code HTMAXBUTTON} — maximize button (enables Win11 snap-layouts flyout on hover). */
    public static final int HT_MAX_BUTTON = 9;
    /** {@code HTCLOSE} — close button. */
    public static final int HT_CLOSE      = 20;
    /** {@code HTHELP} — overloaded as full-screen-toggle so it doesn't collide with HT_CAPTION. */
    public static final int HT_FSCREEN    = 21;
}
