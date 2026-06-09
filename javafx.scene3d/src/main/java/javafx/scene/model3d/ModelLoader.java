/*
 * skia-fx — additive Door-2 module (javafx.scene3d). Original work; no upstream
 * OpenJFX provenance. See docs/3D.md and CLAUDE.md.
 *
 * This code is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 only, with the
 * Classpath exception, as published by the Free Software Foundation.
 */

package javafx.scene.model3d;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

import com.sun.javafx.model3d.BuiltModel;
import com.sun.javafx.model3d.GltfSceneBuilder;

/**
 * Loads external 3D models into the JavaFX scene graph.
 *
 * <p>Two styles, both sugar. For the common case, a {@code static} one-liner:</p>
 * <pre>{@code
 * Node model = ModelLoader.load("assets/robot.glb");
 * }</pre>
 *
 * <p>When the model needs framing (3D assets arrive in wildly different units
 * and origins), chain a fluent loader:</p>
 * <pre>{@code
 * Node model = ModelLoader.of("assets/robot.glb")
 *                         .center()        // recenter its bounds on the origin
 *                         .scale(200)      // fit it to your scene's units
 *                         .load();
 * }</pre>
 *
 * <p>A loaded model is returned as an ordinary {@link javafx.scene.Node} — a
 * {@link javafx.scene.Group} whose descendants are {@link
 * javafx.scene.shape.MeshView}s backed by {@link
 * javafx.scene.shape.TriangleMesh} geometry and {@link
 * javafx.scene.paint.PhongMaterial} materials. Because the result is built
 * entirely from the public JavaFX 3D API, it renders through the standard 3D
 * pipeline, can be added to any {@link javafx.scene.SubScene}, transformed,
 * picked, and styled like any other node — and the loader never exposes a
 * native handle.</p>
 *
 * <p><b>Supported format:</b> glTF&nbsp;2.0, both {@code .gltf} (JSON, with
 * external or embedded buffers) and {@code .glb} (binary). Geometry uses
 * triangle primitives with positions, normals and the first texture-coordinate
 * set; materials map glTF metallic-roughness to {@code PhongMaterial}
 * (base-color, normal and emissive maps; metallic/roughness approximated as a
 * Phong specular term). Texture images must be PNG or JPEG. Skeletal/morph
 * animation, Draco/KTX2 compression and additional UV sets are not yet
 * supported.</p>
 *
 * <p>The orientation is corrected from glTF's right-handed, Y-up convention to
 * JavaFX's Y-down convention automatically.</p>
 *
 * <p>Loading is a blocking, CPU-bound operation — call it off the JavaFX
 * Application Thread for large models, then add the returned node to the scene
 * on the FX thread. A fluent {@code ModelLoader} is single-use; create one per
 * load.</p>
 *
 * @since 25
 */
public final class ModelLoader {

    // Exactly one of these is set; identifies the model source.
    private final Path path;
    private final InputStream stream;

    // Fluent options (applied to the returned node after loading).
    private boolean center;
    private double scale = 1.0;

    private ModelLoader(Path path, InputStream stream) {
        this.path = path;
        this.stream = stream;
    }

    // ---- one-liner sugar ---------------------------------------------------

    /**
     * Loads a glTF&nbsp;2.0 model from a file path.
     *
     * @param path the path to the {@code .gltf} or {@code .glb} file
     * @return the loaded model as a scene-graph {@link Node}
     * @throws IOException if the file cannot be read or is not a valid
     *                     glTF&nbsp;2.0 asset, or if the native loader is
     *                     unavailable on this platform
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public static Node load(String path) throws IOException {
        Objects.requireNonNull(path, "path");
        return of(path).load();
    }

    /**
     * Loads a glTF&nbsp;2.0 model from a file.
     *
     * @param file the path to the {@code .gltf} or {@code .glb} file
     * @return the loaded model as a scene-graph {@link Node}
     * @throws IOException if the file cannot be read or is not a valid
     *                     glTF&nbsp;2.0 asset, or if the native loader is
     *                     unavailable on this platform
     * @throws NullPointerException if {@code file} is {@code null}
     */
    public static Node load(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        return of(file).load();
    }

    /**
     * Loads a glTF&nbsp;2.0 model from a stream. The caller retains ownership of
     * the stream and is responsible for closing it.
     *
     * @param in the stream supplying the {@code .gltf} or {@code .glb} bytes
     * @return the loaded model as a scene-graph {@link Node}
     * @throws IOException if the stream cannot be read or is not a valid
     *                     glTF&nbsp;2.0 asset, or if the native loader is
     *                     unavailable on this platform
     * @throws NullPointerException if {@code in} is {@code null}
     */
    public static Node load(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        return of(in).load();
    }

    /**
     * Loads a glTF&nbsp;2.0 model from a file path as a {@link Model3D} —
     * the inspectable result for tools (name, mesh views, named lookup).
     *
     * @param path the path to the {@code .gltf} or {@code .glb} file
     * @return the loaded {@link Model3D}
     * @throws IOException if the file cannot be read or is not a valid
     *                     glTF&nbsp;2.0 asset, or if the native loader is
     *                     unavailable on this platform
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public static Model3D loadModel(String path) throws IOException {
        Objects.requireNonNull(path, "path");
        return of(path).loadModel();
    }

    /**
     * Loads a glTF&nbsp;2.0 model from a file as a {@link Model3D}.
     *
     * @param file the path to the {@code .gltf} or {@code .glb} file
     * @return the loaded {@link Model3D}
     * @throws IOException if the file cannot be read or is not a valid
     *                     glTF&nbsp;2.0 asset, or if the native loader is
     *                     unavailable on this platform
     * @throws NullPointerException if {@code file} is {@code null}
     */
    public static Model3D loadModel(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        return of(file).loadModel();
    }

    /**
     * Loads a glTF&nbsp;2.0 model from a stream as a {@link Model3D}. The caller
     * retains ownership of the stream and is responsible for closing it.
     *
     * @param in the stream supplying the {@code .gltf} or {@code .glb} bytes
     * @return the loaded {@link Model3D}
     * @throws IOException if the stream cannot be read or is not a valid
     *                     glTF&nbsp;2.0 asset, or if the native loader is
     *                     unavailable on this platform
     * @throws NullPointerException if {@code in} is {@code null}
     */
    public static Model3D loadModel(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        return of(in).loadModel();
    }

    // ---- fluent sugar ------------------------------------------------------

    /**
     * Begins a fluent load from a file path.
     *
     * @param path the path to the {@code .gltf} or {@code .glb} file
     * @return a single-use loader to configure and {@link #load()}
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public static ModelLoader of(String path) {
        Objects.requireNonNull(path, "path");
        return new ModelLoader(Path.of(path), null);
    }

    /**
     * Begins a fluent load from a file.
     *
     * @param file the path to the {@code .gltf} or {@code .glb} file
     * @return a single-use loader to configure and {@link #load()}
     * @throws NullPointerException if {@code file} is {@code null}
     */
    public static ModelLoader of(Path file) {
        Objects.requireNonNull(file, "file");
        return new ModelLoader(file, null);
    }

    /**
     * Begins a fluent load from a stream. The caller retains ownership of the
     * stream and is responsible for closing it. External file references in a
     * {@code .gltf} cannot be resolved from a stream; use {@link #of(Path)} for
     * those.
     *
     * @param in the stream supplying the {@code .gltf} or {@code .glb} bytes
     * @return a single-use loader to configure and {@link #load()}
     * @throws NullPointerException if {@code in} is {@code null}
     */
    public static ModelLoader of(InputStream in) {
        Objects.requireNonNull(in, "in");
        return new ModelLoader(null, in);
    }

    /**
     * Recenters the model so the middle of its bounding box sits at the local
     * origin {@code (0, 0, 0)}. Handy before rotating or placing it.
     *
     * @return this loader, for chaining
     */
    public ModelLoader center() {
        this.center = true;
        return this;
    }

    /**
     * Applies a uniform scale to the loaded model. glTF models are authored in
     * meters, which is often tiny relative to a JavaFX scene's pixel units —
     * a scale of a few hundred is common.
     *
     * @param factor the uniform scale factor (must be finite and positive)
     * @return this loader, for chaining
     * @throws IllegalArgumentException if {@code factor} is not positive/finite
     */
    public ModelLoader scale(double factor) {
        if (!Double.isFinite(factor) || factor <= 0.0) {
            throw new IllegalArgumentException("scale factor must be positive and finite: " + factor);
        }
        this.scale = factor;
        return this;
    }

    /**
     * Loads the model and applies any configured options.
     *
     * @return the loaded model as a scene-graph {@link Node}
     * @throws IOException if the source cannot be read or is not a valid
     *                     glTF&nbsp;2.0 asset, or if the native loader is
     *                     unavailable on this platform
     */
    public Node load() throws IOException {
        BuiltModel built = build();
        return frame(built.root());
    }

    /**
     * Loads the model (applying any configured options) as a {@link Model3D} —
     * the inspectable result for tools (name, mesh views, named lookup).
     *
     * @return the loaded {@link Model3D}
     * @throws IOException if the source cannot be read or is not a valid
     *                     glTF&nbsp;2.0 asset, or if the native loader is
     *                     unavailable on this platform
     */
    public Model3D loadModel() throws IOException {
        BuiltModel built = build();
        return new Model3D(frame(built.root()), built.name(), built.meshViews());
    }

    /** Read the source bytes and run the native parse + scene assembly. */
    private BuiltModel build() throws IOException {
        byte[] data;
        String baseDir = null;
        if (path != null) {
            data = Files.readAllBytes(path);
            Path parent = path.toAbsolutePath().getParent();
            baseDir = (parent != null) ? parent.toString() : null;
        } else {
            data = stream.readAllBytes();
        }
        return GltfSceneBuilder.build(data, baseDir);
    }

    /** Apply the optional center/scale framing, wrapping only when needed. */
    private Node frame(Node model) {
        if (!center && scale == 1.0) {
            return model;
        }
        // Wrap so the framing transforms compose cleanly with the loader's
        // internal orientation fix. Order applies center first, then scale.
        Group framed = new Group(model);
        if (scale != 1.0) {
            framed.getTransforms().add(new Scale(scale, scale, scale));
        }
        if (center) {
            Bounds b = model.getBoundsInParent();
            framed.getTransforms().add(new Translate(
                -(b.getMinX() + b.getWidth() / 2.0),
                -(b.getMinY() + b.getHeight() / 2.0),
                -(b.getMinZ() + b.getDepth() / 2.0)));
        }
        return framed;
    }
}
