/*
 * Copyright (c) 2026, skia-fx contributors.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 */

package javafx.stage;

/**
 * Controls the corner radius preference of a custom-decorated window
 * frame on platforms where the window manager supports per-window
 * corner styling.
 *
 * <p>Currently honoured on Windows 11 via the
 * {@code DWMWA_WINDOW_CORNER_PREFERENCE} DWM attribute. On platforms
 * that do not support a corner preference (Windows 10, macOS, Linux),
 * setting this value has no observable effect.</p>
 *
 * <p>This preference applies only to stages created with
 * {@link StageStyle#CUSTOM};
 * for other styles the value is stored but unused.</p>
 *
 * @since 25
 * @see Stage#setCornerPreference(WindowCornerPreference)
 */
public enum WindowCornerPreference {
    /** Let the platform window manager decide. */
    DEFAULT,
    /** Force square (non-rounded) corners. */
    SQUARE,
    /** Use the platform's standard rounded-corner radius (8 px on Windows 11). */
    ROUND,
    /** Use a smaller rounded-corner radius (4 px on Windows 11). */
    ROUND_SMALL
}
