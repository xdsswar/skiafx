package org.openjfx.samples.ensemble;

import javafx.animation.Interpolator;
import javafx.animation.RotateTransition;
import javafx.geometry.Pos;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.control.Label;
import javafx.util.Duration;

/**
 * Two-arc rotating loader. Outer arc spins clockwise, inner counter-clockwise
 * at a different period — gives a "dual" feel without external deps.
 *
 * <p>Style hooks: {@code .dual-loader}, {@code .dual-loader > .outer-arc},
 * {@code .dual-loader > .inner-arc}, {@code .dual-loader > .loader-label}.
 */
public final class DualLoader extends VBox {

    private final Arc outerArc;
    private final Arc innerArc;
    private final RotateTransition outerSpin;
    private final RotateTransition innerSpin;

    public DualLoader() {
        getStyleClass().add("dual-loader");
        setAlignment(Pos.CENTER);
        setSpacing(18);

        outerArc = new Arc(0, 0, 36, 36, 0, 270);
        outerArc.setType(ArcType.OPEN);
        outerArc.setStrokeLineCap(StrokeLineCap.ROUND);
        outerArc.setStrokeWidth(4);
        outerArc.setFill(null);
        outerArc.getStyleClass().add("outer-arc");

        innerArc = new Arc(0, 0, 22, 22, 90, 200);
        innerArc.setType(ArcType.OPEN);
        innerArc.setStrokeLineCap(StrokeLineCap.ROUND);
        innerArc.setStrokeWidth(3);
        innerArc.setFill(null);
        innerArc.getStyleClass().add("inner-arc");

        StackPane arcs = new StackPane(outerArc, innerArc);
        arcs.setPrefSize(96, 96);
        arcs.setMinSize(96, 96);

        Label label = new Label("Loading");
        label.getStyleClass().add("loader-label");

        getChildren().addAll(arcs, label);

        outerSpin = new RotateTransition(Duration.seconds(1.4), outerArc);
        outerSpin.setByAngle(360);
        outerSpin.setCycleCount(RotateTransition.INDEFINITE);
        outerSpin.setInterpolator(Interpolator.LINEAR);

        innerSpin = new RotateTransition(Duration.seconds(1.0), innerArc);
        innerSpin.setByAngle(-360);
        innerSpin.setCycleCount(RotateTransition.INDEFINITE);
        innerSpin.setInterpolator(Interpolator.LINEAR);
    }

    public void start() {
        outerSpin.play();
        innerSpin.play();
    }

    public void stop() {
        outerSpin.pause();
        innerSpin.pause();
    }
}
