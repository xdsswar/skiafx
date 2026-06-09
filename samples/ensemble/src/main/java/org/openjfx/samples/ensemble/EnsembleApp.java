/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 */
package org.openjfx.samples.ensemble;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/**
 * Step-1 sample: a minimal ScrollPane test for the Skia pipeline.
 *
 * <p>The point is to isolate ScrollPane drag-resize behavior on Windows
 * with the Skia direct-present path. The scene is intentionally tiny:</p>
 *
 * <ul>
 *   <li>An {@code AnchorPane} root that fills the window. AnchorPane
 *       resolves child positions from explicit anchors; it does not
 *       negotiate preferred sizes with its children.</li>
 *   <li>One {@code ScrollPane} anchored to all four sides. Vertical
 *       scrollbar is {@code ALWAYS} so its width is reserved up front
 *       and viewport width is constant across frames (a known cure for
 *       the AS_NEEDED scrollbar-flip oscillation).</li>
 *   <li>{@code fitToWidth=true} so the content stretches with the
 *       viewport. No {@code minWidth} on the content — it shrinks
 *       cleanly as the window shrinks.</li>
 *   <li>The content is a {@code VBox} of 30 plain {@code Label} rows.
 *       Each row has a fixed height and {@code maxWidth=MAX_VALUE} so
 *       it stretches across the viewport. No wrap text, no bias —
 *       prefHeight does not depend on width. Eliminates the
 *       width→prefHeight→nodeHeight→relayout cycle that an
 *       iterative ScrollPaneSkin solver can chase.</li>
 * </ul>
 *
 * <p>When dragging the window edge the content rows should track the
 * viewport width without flicker or "fighting". This is the baseline;
 * once it is clean we layer additional controls back in, each in its
 * own class.</p>
 */
public final class EnsembleApp extends Application {

    /** Required no-arg constructor for reflective {@code Application.launch}. */
    public EnsembleApp() {}

    /** Window background — slate-900 ({@code #0f172a}). */
    private static final Color BG = Color.web("#0f172a");
    private static final String ROW_BG  = "#1e293b";
    private static final String ROW_FG  = "#e2e8f0";
    private static final String ROW_BR  = "#2a3554";

    @Override
    public void start(Stage stage) {
        VBox content = buildContent();

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle(
            "-fx-background: " + cssRgb(BG) + ";"
          + "-fx-background-color: " + cssRgb(BG) + ";"
          + "-fx-padding: 0;"
        );

        AnchorPane root = new AnchorPane(scroll);
        AnchorPane.setTopAnchor(scroll,    0.0);
        AnchorPane.setLeftAnchor(scroll,   0.0);
        AnchorPane.setRightAnchor(scroll,  0.0);
        AnchorPane.setBottomAnchor(scroll, 0.0);

        Scene scene = new Scene(root, 960, 640, BG);
        stage.setScene(scene);
        stage.setTitle("ScrollPane drag-resize test");
        stage.setMinWidth(360);
        stage.setMinHeight(240);
        stage.show();

        // -Dskia.resize.diag=true — print scene/scroll/content widths on
        // every change to characterize the layout chain during drag.
        // Quiet by default.
        if (Boolean.getBoolean("skia.resize.diag")) {
            installResizeDiag(scene, root, scroll, content);
        }
    }

    /** Logs the width chain Scene → root → scroll → content so we can
     *  tell empirically whether they stay in lockstep with the window
     *  during a drag, or diverge in a way that explains the symptom. */
    private static void installResizeDiag(Scene scene,
                                          AnchorPane root,
                                          ScrollPane scroll,
                                          VBox content) {
        scene.widthProperty().addListener((o, prev, w) ->
            System.err.printf("[diag.demo] t=%d ms  scene=%.1f  root=%.1f  scroll=%.1f  content=%.1f%n",
                System.currentTimeMillis() % 100_000,
                w.doubleValue(), root.getWidth(),
                scroll.getWidth(), content.getWidth()));
        scroll.widthProperty().addListener((o, prev, w) ->
            System.err.printf("[diag.demo] t=%d ms  ...     scroll.width  -> %.1f  (scene=%.1f content=%.1f)%n",
                System.currentTimeMillis() % 100_000,
                w.doubleValue(), scene.getWidth(), content.getWidth()));
        content.widthProperty().addListener((o, prev, w) ->
            System.err.printf("[diag.demo] t=%d ms  ...     content.width -> %.1f  (scene=%.1f scroll=%.1f)%n",
                System.currentTimeMillis() % 100_000,
                w.doubleValue(), scene.getWidth(), scroll.getWidth()));
    }

    private VBox buildContent() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(20));
        for (int i = 1; i <= 30; i++) {
            box.getChildren().add(buildRow(i));
        }
        return box;
    }

    private Label buildRow(int n) {
        Label row = new Label("Row " + n
            + " — drag the window edge; this row should track the viewport "
            + "width and never resize horizontally on its own.");
        row.setMinHeight(44);
        row.setPrefHeight(44);
        row.setMaxHeight(44);                    // height is locked: no bias.
        row.setMaxWidth(Double.MAX_VALUE);       // stretch across viewport.
        row.setStyle(
            "-fx-background-color: " + ROW_BG + ";"
          + "-fx-background-radius: 8;"
          + "-fx-border-color: " + ROW_BR + ";"
          + "-fx-border-radius: 8;"
          + "-fx-border-width: 1;"
          + "-fx-text-fill: " + ROW_FG + ";"
          + "-fx-font-size: 13px;"
          + "-fx-padding: 0 16 0 16;"
        );
        return row;
    }

    /** Color → CSS {@code rgb(r,g,b)} string (no alpha). */
    private static String cssRgb(Color c) {
        return String.format("rgb(%d,%d,%d)",
            (int)(c.getRed()   * 255),
            (int)(c.getGreen() * 255),
            (int)(c.getBlue()  * 255));
    }
}
