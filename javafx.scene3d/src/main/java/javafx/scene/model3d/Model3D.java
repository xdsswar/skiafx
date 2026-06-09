/*
 * skia-fx — additive Door-2 module (javafx.scene3d). Original work; no upstream
 * OpenJFX provenance. See docs/3D.md and CLAUDE.md.
 *
 * This code is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 only, with the
 * Classpath exception, as published by the Free Software Foundation.
 */

package javafx.scene.model3d;

import java.util.List;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.shape.MeshView;

/**
 * A loaded 3D model — the scene-graph node plus a little metadata for tools that
 * want to inspect or manipulate the model's parts.
 *
 * <p>Obtain one from {@link ModelLoader#loadModel(String)} (and friends) or the
 * fluent {@link ModelLoader#of(String)}{@code .loadModel()}. If you only need to
 * drop the model into a scene, the plain {@link ModelLoader#load(String)} that
 * returns a {@link Node} is simpler — this type is for model viewers/editors
 * that select, highlight or re-material individual pieces.</p>
 *
 * <p>Every {@code Node} and {@code MeshView} carries its glTF name as its
 * {@linkplain Node#getId() id}, so you can also address parts with standard
 * JavaFX — {@code model.getRoot().lookup("#Wheel")} — in addition to
 * {@link #lookup(String)} here. The exposed objects are ordinary,
 * fully-mutable JavaFX nodes; no native handle is involved.</p>
 *
 * @since 25
 */
public final class Model3D {

    private final Node root;
    private final String name;
    private final List<MeshView> meshViews;

    // Constructed only by ModelLoader (same package) from the internal builder
    // result — never by application code, and never from a com.sun.* type.
    Model3D(Node root, String name, List<MeshView> meshViews) {
        this.root = root;
        this.name = name;
        this.meshViews = List.copyOf(meshViews);
    }

    /**
     * The model's scene-graph subtree, ready to add to a {@code SubScene}.
     *
     * @return the root {@link Node} (a {@code Group})
     */
    public Node getRoot() {
        return root;
    }

    /**
     * The model's name (the glTF scene name), or an empty string if the asset
     * is unnamed.
     *
     * @return the model name, never {@code null}
     */
    public String getName() {
        return name;
    }

    /**
     * Every {@link MeshView} in the model, in load order — the renderable
     * pieces a tool can select, hide, or re-material.
     *
     * @return an unmodifiable list of the model's mesh views
     */
    public List<MeshView> getMeshViews() {
        return meshViews;
    }

    /**
     * Finds a node in the model by its glTF name (its JavaFX
     * {@linkplain Node#getId() id}). The first match in a depth-first walk is
     * returned.
     *
     * @param name the glTF node/mesh name to look up
     * @return the matching node, or empty if none has that name
     */
    public Optional<Node> lookup(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(find(root, name));
    }

    private static Node find(Node n, String id) {
        if (id.equals(n.getId())) {
            return n;
        }
        if (n instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                Node hit = find(child, id);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }
}
