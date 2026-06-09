/*
 * skia-fx — additive Door-2 module. Original work (no upstream provenance);
 * stock OpenJFX 25 has no 3D asset loader. See docs/3D.md and CLAUDE.md.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 */

/**
 * Defines the skia-fx 3D asset-loading API — load external 3D models
 * (glTF&nbsp;2.0) directly into the JavaFX 3D scene graph.
 *
 * <p>This is an <em>additive</em> module: it has no counterpart in stock
 * OpenJFX&nbsp;25 and depends only on the public JavaFX 3D scene-graph API.
 * A loaded model is returned as an ordinary {@link javafx.scene.Node}
 * subtree ({@code Group} of {@code MeshView} with {@code TriangleMesh} and
 * {@code PhongMaterial}), so it renders through the standard 3D pipeline and
 * the API never exposes any native handle.</p>
 *
 * <p><b>Note:</b> like all JavaFX modules these classes must be loaded from a
 * named {@code javafx.*} module on the <em>module path</em>.</p>
 *
 * @moduleGraph
 */
module javafx.scene3d {
    // javafx.scene.*, javafx.scene.shape.*, javafx.scene.paint.PhongMaterial,
    // javafx.scene.image.Image, javafx.scene.transform.* — transitive so a
    // consumer that handles the returned Node automatically reads javafx.graphics.
    requires transitive javafx.graphics;

    exports javafx.scene.model3d;
}
