package com.sun.javafx.scene3d;

import java.util.Objects;

import com.sun.javafx.scene.DirtyBits;
import com.sun.javafx.scene.NodeHelper;
import com.sun.javafx.sg.prism.NGSubScene;
import com.sun.javafx.tk.Toolkit;

import javafx.scene.SubScene;

/**
 * Runtime 3D controls for a single {@link SubScene} rendered by the skia-fx
 * (Skia/bgfx) pipeline.
 *
 * <p>This is an <b>internal</b> type. It is deliberately <em>not</em> part of the
 * public JavaFX surface — {@link javafx.scene.SubScene} stays byte-identical to stock
 * JavaFX and the SDK remains a drop-in replacement. The package is exported only to
 * the bundled sample app (a qualified {@code exports ... to} in {@code module-info}),
 * so it is not a general API either.</p>
 *
 * <p>It exposes capabilities the frozen public API cannot — chiefly toggling
 * multisample anti-aliasing (MSAA) at runtime, which JavaFX's constructor-only
 * {@link javafx.scene.SceneAntialiasing} cannot do.</p>
 *
 * <pre>{@code
 *   Scene3D s3d = new Scene3D(subScene);
 *   s3d.setAntiAliasing(false);   // turn AA off at runtime
 *   s3d.setSampleCount(8);        // or pick an exact MSAA level
 * }</pre>
 *
 * <h2>No exposed handles</h2>
 * <p>The API takes only a {@link SubScene} and primitives — no native handles,
 * pointers, or ids are exposed.</p>
 *
 * <h2>Threading</h2>
 * <p>Use on the JavaFX Application Thread, after the {@code SubScene} has been shown
 * (its render peer exists). A change takes effect on the next rendered frame: the
 * SubScene's GPU target is transparently rebuilt at the new sample count.</p>
 *
 * <h2>No-ops</h2>
 * <p>Calls are silently ignored when the scene has no live render peer yet or when
 * the active pipeline is not the skia-fx 3D pipeline. A sample count above the GPU's
 * support is snapped down; if MSAA is unavailable the scene renders without
 * anti-aliasing rather than failing.</p>
 */
public final class Scene3D {

    /**
     * Sample count meaning "use the pipeline default" (the value of the
     * {@code OPENJFX_SKIA_3D_MSAA} setting, or 4× when unset). Passing this to
     * {@link #setSampleCount(int)} restores default anti-aliasing.
     */
    public static final int DEFAULT_SAMPLES = -1;

    /** Sample count meaning anti-aliasing is off (one sample per pixel). */
    public static final int NO_ANTIALIASING = 1;

    private final SubScene scene;

    /**
     * Creates a 3D control bound to the given {@code SubScene}.
     *
     * @param scene the SubScene to control; must not be {@code null}
     * @throws NullPointerException if {@code scene} is {@code null}
     */
    public Scene3D(SubScene scene) {
        this.scene = Objects.requireNonNull(scene, "scene");
    }

    /** The {@code SubScene} this control operates on. */
    public SubScene getSubScene() {
        return scene;
    }

    /**
     * Turns anti-aliasing on or off at runtime. {@code true} restores the
     * pipeline-default multisample level ({@link #DEFAULT_SAMPLES}); {@code false}
     * disables anti-aliasing ({@link #NO_ANTIALIASING}). For a specific level use
     * {@link #setSampleCount(int)}.
     *
     * @param on {@code true} to anti-alias, {@code false} to disable
     */
    public void setAntiAliasing(boolean on) {
        setSampleCount(on ? DEFAULT_SAMPLES : NO_ANTIALIASING);
    }

    /**
     * Returns whether anti-aliasing is currently enabled (effective sample count
     * greater than one).
     *
     * @return {@code true} if AA is on, {@code false} if off or no 3D peer exists
     */
    public boolean isAntiAliasing() {
        NGSubScene peer = peer();
        // DEFAULT_SAMPLES (-1) resolves to the default level (AA on); only an
        // explicit count of 1 means anti-aliasing is off.
        return peer != null && peer.getMsaaSamples() != NO_ANTIALIASING;
    }

    /**
     * Sets the exact MSAA sample count. Accepted values: {@link #DEFAULT_SAMPLES}
     * (pipeline default), {@link #NO_ANTIALIASING} (off), or {@code 2}, {@code 4},
     * {@code 8}. Values above the GPU maximum are snapped down; an unsupported
     * request degrades to no anti-aliasing rather than failing.
     *
     * @param samples the desired sample count (see above)
     */
    public void setSampleCount(int samples) {
        NGSubScene peer = peer();
        if (peer == null) {
            return;
        }
        peer.setMsaaSamples(samples);
        // Force a repaint so the bgfx target rebuilds at the new sample count, and
        // pump a pulse in case the scene is otherwise idle.
        NodeHelper.markDirty(scene, DirtyBits.NODE_CONTENTS);
        Toolkit.getToolkit().requestNextPulse();
    }

    /**
     * Returns the current MSAA sample count, or {@link #NO_ANTIALIASING} if no 3D
     * peer exists. May be {@link #DEFAULT_SAMPLES} when using the pipeline default.
     *
     * @return the current sample count
     */
    public int getSampleCount() {
        NGSubScene peer = peer();
        return peer == null ? NO_ANTIALIASING : peer.getMsaaSamples();
    }

    /** Resolve the SubScene's render peer, or {@code null} if not (yet) an NGSubScene. */
    private NGSubScene peer() {
        // NodeHelper.getPeer returns the node's NG peer once the scene is shown.
        return NodeHelper.getPeer(scene) instanceof NGSubScene ng ? ng : null;
    }
}
