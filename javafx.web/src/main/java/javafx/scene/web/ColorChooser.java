/*
 * Copyright (c) 2026, skia-fx. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The skia-fx project
 * designates this particular file as subject to the "Classpath" exception
 * as provided in the LICENSE file that accompanied this code.
 */
package javafx.scene.web;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.scene.paint.Color;

/**
 * A request to choose a color, raised when the page focuses an
 * {@code <input type="color">} control. Because the {@link WebView} is rendered
 * off-screen, the browser engine cannot show its own native color picker, so the
 * request is surfaced to the application through
 * {@link WebEngine#colorChooserHandlerProperty() colorChooserHandler}. The
 * application presents its own color UI (for example a {@code ColorPicker} in a
 * popup) and answers by calling {@link #choose(Color)} or {@link #cancel()}.
 *
 * <p>The page's script is suspended until the request is answered, so the
 * application may respond asynchronously. The request is <b>single-shot</b>:
 * after the first answer, further calls to {@link #choose(Color)} or
 * {@link #cancel()} have no effect.
 *
 * @since 25
 */
public final class ColorChooser {

    private final WebEngine engine;
    private final int id;
    private final Color initial;
    private final List<Color> suggestions;
    private final AtomicBoolean responded = new AtomicBoolean();

    // Constructed internally by the WebView engine bridge.
    ColorChooser(WebEngine engine, int id, Color initial, List<Color> suggestions) {
        this.engine = engine;
        this.id = id;
        this.initial = initial == null ? Color.BLACK : initial;
        this.suggestions = suggestions == null
            ? List.of() : Collections.unmodifiableList(suggestions);
    }

    /**
     * Returns the {@code WebEngine} whose page raised this request.
     * @return the source engine
     */
    public WebEngine getSource() {
        return engine;
    }

    /**
     * Returns the control's current color (the {@code value} of the input).
     * @return the initial color, never {@code null}
     */
    public Color getInitialColor() {
        return initial;
    }

    /**
     * Returns the page-suggested swatch colors (from the input's
     * {@code <datalist>}), or an empty list if none.
     * @return an unmodifiable list of suggested colors
     */
    public List<Color> getSuggestions() {
        return suggestions;
    }

    /**
     * Returns whether this request has already been answered.
     * @return {@code true} once {@link #choose(Color)} or {@link #cancel()} ran
     */
    public boolean isResponded() {
        return responded.get();
    }

    /**
     * Accepts the chooser with the given color and resumes the page.
     * @param color the chosen color; {@code null} is treated as a cancel
     */
    public void choose(Color color) {
        if (!responded.compareAndSet(false, true)) {
            return;
        }
        if (color == null) {
            engine.respondColorChooser(id, false, 0);
        } else {
            engine.respondColorChooser(id, true, toRgba(color));
        }
    }

    /**
     * Cancels the chooser (the control keeps its current value) and resumes the page.
     */
    public void cancel() {
        if (!responded.compareAndSet(false, true)) {
            return;
        }
        engine.respondColorChooser(id, false, 0);
    }

    /** Packs a {@code Color} into {@code 0xRRGGBBAA}. */
    private static int toRgba(Color c) {
        int r = (int) Math.round(c.getRed() * 255.0);
        int g = (int) Math.round(c.getGreen() * 255.0);
        int b = (int) Math.round(c.getBlue() * 255.0);
        int a = (int) Math.round(c.getOpacity() * 255.0);
        return (r << 24) | (g << 16) | (b << 8) | a;
    }
}
