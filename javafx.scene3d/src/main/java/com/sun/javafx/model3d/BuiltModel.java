/*
 * skia-fx — additive Door-2 module (javafx.scene3d). Original work; no upstream
 * OpenJFX provenance. See docs/3D.md and CLAUDE.md.
 *
 * This code is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 only, with the
 * Classpath exception, as published by the Free Software Foundation.
 */

package com.sun.javafx.model3d;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.shape.MeshView;

/**
 * Low-level carrier from {@link GltfSceneBuilder} up to the public
 * {@code javafx.scene.model3d.ModelLoader}: the assembled scene-graph root, the
 * glTF scene name, and every {@code MeshView} in the model (already id-stamped
 * with its glTF name). This type lives in the unexported {@code com.sun.*}
 * implementation package and never appears in any public API signature — the
 * public {@code Model3D} result is built from it in the public package.
 */
public record BuiltModel(Node root, String name, List<MeshView> meshViews) {}
