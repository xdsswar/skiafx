/*
 * skia-fx — additive Door-2 module (javafx.scene3d). Original work; no upstream
 * OpenJFX provenance. See docs/3D.md and CLAUDE.md.
 *
 * This code is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 only, with the
 * Classpath exception, as published by the Free Software Foundation.
 */

package com.sun.javafx.model3d;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.shape.VertexFormat;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Scale;

/**
 * Turns a parsed glTF native session into a standard JavaFX scene-graph subtree
 * ({@code Group} → {@code MeshView} with {@code TriangleMesh} + {@code
 * PhongMaterial}). The native side (cgltf) does the heavy parsing/decoding and
 * hands back JavaFX-ready arrays; this class only assembles public JavaFX
 * objects, so the result renders through the existing Door-1 3D pipeline and no
 * native handle is exposed.
 *
 * <p>One builder instance corresponds to one open native session; the static
 * {@link #build(byte[], String)} entry point owns the open/close lifecycle.</p>
 */
public final class GltfSceneBuilder {

    private static final Logger LOG = System.getLogger(GltfSceneBuilder.class.getName());

    private final long handle;
    private final String baseDir;

    // Per-(mesh,prim) geometry is shared across node instances that reuse the
    // same glTF mesh (glTF's lightweight instancing). Key = (meshIdx<<32)|prim.
    private final Map<Long, TriangleMesh> meshCache = new HashMap<>();

    // Every MeshView created, in build order — surfaced to the public Model3D.
    private final List<MeshView> meshViews = new ArrayList<>();

    // Material caches, indexed by glTF material index. Built lazily.
    private final PhongMaterial[] materials;
    private final boolean[] doubleSided;
    private final boolean[] materialBuilt;
    private final PhongMaterial defaultMaterial = new PhongMaterial(Color.LIGHTGRAY);

    private GltfSceneBuilder(long handle, String baseDir) {
        this.handle = handle;
        this.baseDir = baseDir;
        int mc = Math.max(0, NativeModelBridge.materialCount(handle));
        this.materials = new PhongMaterial[mc];
        this.doubleSided = new boolean[mc];
        this.materialBuilt = new boolean[mc];
    }

    /**
     * Parse {@code data} (a .glb or .gltf blob) and return the assembled model
     * (root subtree + scene name + every MeshView). {@code baseDir} (may be
     * null) resolves external buffer/image URIs. Opens and always closes the
     * native session.
     *
     * @throws IOException if the native loader is unavailable or the data is
     *                     not a valid glTF&nbsp;2.0 asset
     */
    public static BuiltModel build(byte[] data, String baseDir) throws IOException {
        if (!NativeModelBridge.isAvailable()) {
            throw new IOException("native glTF loader (openjfx_model3d) is unavailable on this platform/build");
        }
        long h = NativeModelBridge.open(data, baseDir);
        if (h == 0L) {
            throw new IOException("not a valid glTF 2.0 asset (parse/validate failed)");
        }
        try {
            GltfSceneBuilder b = new GltfSceneBuilder(h, baseDir);
            Node root = b.assemble();
            return new BuiltModel(root, NativeModelBridge.sceneName(h), List.copyOf(b.meshViews));
        } finally {
            NativeModelBridge.close(h);
        }
    }

    /** Build the root container and walk the node hierarchy under it. */
    private Node assemble() {
        Group root = new Group();
        // glTF is right-handed, +Y up; JavaFX is Y-down. A single negative-Y
        // scale on the root reconciles them. Because this makes the world
        // transform determinant negative, NGShape3D auto-swaps the cull face,
        // so glTF's CCW front faces stay correct under the default CullFace.BACK
        // — we must NOT also reverse triangle winding (that would double-correct).
        root.getTransforms().add(new Scale(1, -1, 1));

        int roots = NativeModelBridge.rootCount(handle);
        if (roots > 0) {
            for (int k = 0; k < roots; k++) {
                int nodeIdx = NativeModelBridge.root(handle, k);
                if (nodeIdx >= 0) {
                    Node n = buildNode(nodeIdx, 0);
                    if (n != null) {
                        root.getChildren().add(n);
                    }
                }
            }
        } else {
            // No scene/node hierarchy — attach every mesh directly so geometry-
            // only files still render.
            int meshes = Math.max(0, NativeModelBridge.meshCount(handle));
            for (int m = 0; m < meshes; m++) {
                addMeshViews(root, m);
            }
        }
        return root;
    }

    // Guard against a pathological/cyclic node graph (glTF should be a tree, but
    // we never want to recurse unbounded on a malformed file).
    private static final int MAX_DEPTH = 256;

    private Node buildNode(int nodeIdx, int depth) {
        if (depth > MAX_DEPTH) {
            LOG.log(Level.WARNING, "glTF node hierarchy exceeds max depth; truncating");
            return null;
        }
        Group g = new Group();

        // Carry the glTF node name as the JavaFX node id so tools can address
        // parts of a loaded model with standard lookup ("#name") / getId().
        String name = NativeModelBridge.nodeName(handle, nodeIdx);
        if (!name.isEmpty()) {
            g.setId(name);
        }

        float[] m = NativeModelBridge.nodeLocalMatrix(handle, nodeIdx);
        if (m != null) {
            g.getTransforms().add(affineFromColumnMajor(m));
        }

        int meshIdx = NativeModelBridge.nodeMesh(handle, nodeIdx);
        if (meshIdx >= 0) {
            addMeshViews(g, meshIdx);
        }

        int childCount = NativeModelBridge.nodeChildCount(handle, nodeIdx);
        for (int k = 0; k < childCount; k++) {
            int child = NativeModelBridge.nodeChild(handle, nodeIdx, k);
            if (child >= 0) {
                Node cn = buildNode(child, depth + 1);
                if (cn != null) {
                    g.getChildren().add(cn);
                }
            }
        }
        return g;
    }

    /** Add a MeshView for each renderable primitive of a glTF mesh to {@code g}. */
    private void addMeshViews(Group g, int meshIdx) {
        int prims = NativeModelBridge.primitiveCount(handle, meshIdx);
        for (int p = 0; p < prims; p++) {
            int[] info = NativeModelBridge.primitiveInfo(handle, meshIdx, p);
            if (info == null) {
                continue;
            }
            int faceCount = info[1];
            int matIdx = info[3];
            if (faceCount <= 0) {
                // Non-triangle primitive (points/lines/strips) — unsupported this
                // increment. Skip rather than render garbage.
                LOG.log(Level.DEBUG, () -> "skipping non-triangle primitive in mesh " + meshIdx);
                continue;
            }
            TriangleMesh tm = meshFor(meshIdx, p, info);
            if (tm == null) {
                continue;
            }
            MeshView mv = new MeshView(tm);
            mv.setMaterial(materialFor(matIdx));
            if (isDoubleSided(matIdx)) {
                mv.setCullFace(CullFace.NONE);
            }
            String meshName = NativeModelBridge.meshName(handle, meshIdx);
            if (!meshName.isEmpty()) {
                mv.setId(meshName);
            }
            meshViews.add(mv);
            g.getChildren().add(mv);
        }
    }

    /** Build (or reuse) the TriangleMesh for one primitive. */
    private TriangleMesh meshFor(int meshIdx, int primIdx, int[] info) {
        long key = ((long) meshIdx << 32) | (primIdx & 0xffffffffL);
        TriangleMesh cached = meshCache.get(key);
        if (cached != null) {
            return cached;
        }
        int vtx = info[0];
        int faceCount = info[1];
        int format = info[2];
        boolean pnt = (format == NativeModelBridge.FMT_POINT_NORMAL_TEXCOORD);

        float[] points = new float[vtx * 3];
        float[] normals = pnt ? new float[vtx * 3] : null;
        float[] texcoords = new float[vtx * 2];
        int[] faces = new int[faceCount * (pnt ? 9 : 6)];

        if (!NativeModelBridge.primitiveBuild(handle, meshIdx, primIdx,
                points, normals, texcoords, faces)) {
            LOG.log(Level.WARNING, () -> "failed to build geometry for mesh " + meshIdx + " prim " + primIdx);
            return null;
        }
        // Defensive self-validation: TriangleMesh silently drops a mesh (empty
        // bounds, only a logger warning) on any out-of-range index, so verify
        // before handing it over.
        if (!indicesInRange(faces, vtx)) {
            LOG.log(Level.WARNING, () -> "glTF mesh " + meshIdx + " prim " + primIdx
                + " has out-of-range indices; skipping");
            return null;
        }

        TriangleMesh tm = new TriangleMesh(pnt
            ? VertexFormat.POINT_NORMAL_TEXCOORD : VertexFormat.POINT_TEXCOORD);
        tm.getPoints().setAll(points);
        if (pnt) {
            tm.getNormals().setAll(normals);
        }
        tm.getTexCoords().setAll(texcoords);
        tm.getFaces().setAll(faces);

        meshCache.put(key, tm);
        return tm;
    }

    private static boolean indicesInRange(int[] faces, int vtxCount) {
        for (int f : faces) {
            if (f < 0 || f >= vtxCount) {
                return false;
            }
        }
        return true;
    }

    // ---- Materials ---------------------------------------------------------

    private PhongMaterial materialFor(int matIdx) {
        if (matIdx < 0 || matIdx >= materials.length) {
            return defaultMaterial;
        }
        if (!materialBuilt[matIdx]) {
            materials[matIdx] = buildMaterial(matIdx);
            materialBuilt[matIdx] = true;
        }
        return materials[matIdx] != null ? materials[matIdx] : defaultMaterial;
    }

    private boolean isDoubleSided(int matIdx) {
        return matIdx >= 0 && matIdx < doubleSided.length && doubleSided[matIdx];
    }

    private PhongMaterial buildMaterial(int matIdx) {
        NativeModelBridge.MaterialData md = NativeModelBridge.materialInfo(handle, matIdx);
        if (md == null) {
            return new PhongMaterial(Color.LIGHTGRAY);
        }
        doubleSided[matIdx] = md.doubleSided();

        PhongMaterial pm = new PhongMaterial();

        // Diffuse: baseColorFactor (alpha clamped away from 0 — a 0-alpha
        // PhongMaterial renders nothing) × optional baseColorTexture.
        float[] b = md.base();
        float alpha = Math.max(b[3], 1f / 255f);
        pm.setDiffuseColor(color(b[0], b[1], b[2], alpha));
        Image diffuse = image(NativeModelBridge.materialTexture(handle, matIdx, NativeModelBridge.TEX_BASE_COLOR));
        if (diffuse != null) {
            pm.setDiffuseMap(diffuse);
        }

        // Normal map → bumpMap (PhongMaterial's bumpMap IS a tangent-space normal
        // map; the bgfx Phong shader already samples it via the full TBN basis).
        Image normal = image(NativeModelBridge.materialTexture(handle, matIdx, NativeModelBridge.TEX_NORMAL));
        if (normal != null) {
            pm.setBumpMap(normal);
        }

        // Emissive → self-illumination (which cannot be a flat color, so a
        // factor-only emission becomes a 1×1 image of that color).
        Image emissive = image(NativeModelBridge.materialTexture(handle, matIdx, NativeModelBridge.TEX_EMISSIVE));
        float[] e = md.emissive();
        if (emissive != null) {
            pm.setSelfIlluminationMap(emissive);
        } else if (e[0] > 0f || e[1] > 0f || e[2] > 0f) {
            pm.setSelfIlluminationMap(solidColorImage(color(e[0], e[1], e[2], 1f)));
        }

        // Specular: an approximation of metallic/roughness until real PBR lands.
        // Smoother (low roughness) → tighter, brighter highlight; metallic tints
        // the highlight toward the base color, dielectric keeps it near-gray.
        pm.setSpecularColor(approxSpecularColor(b, md.metallic(), md.roughness()));
        pm.setSpecularPower(approxSpecularPower(md.roughness()));
        return pm;
    }

    private Image image(NativeModelBridge.TextureData td) {
        try {
            if (td.kind() == NativeModelBridge.KIND_EMBEDDED && td.bytes() != null) {
                Image img = new Image(new ByteArrayInputStream(td.bytes()));
                return img.isError() ? null : img;
            }
            if (td.kind() == NativeModelBridge.KIND_URI && td.uri() != null) {
                Path candidate = Path.of(td.uri());
                if (!candidate.isAbsolute() && baseDir != null) {
                    candidate = Path.of(baseDir).resolve(td.uri());
                }
                if (Files.exists(candidate)) {
                    Image img = new Image(candidate.toUri().toString());
                    return img.isError() ? null : img;
                }
                LOG.log(Level.DEBUG, () -> "external texture not found: " + td.uri());
            }
        } catch (Exception ex) {
            LOG.log(Level.DEBUG, "texture decode failed", ex);
        }
        return null;
    }

    private static Image solidColorImage(Color c) {
        WritableImage img = new WritableImage(1, 1);
        PixelWriter pw = img.getPixelWriter();
        pw.setColor(0, 0, c);
        return img;
    }

    private static Color approxSpecularColor(float[] base, float metallic, float roughness) {
        double gray = clamp01(0.04 + (1.0 - roughness) * 0.16); // dielectric reflectance
        double r = lerp(gray, base[0], metallic);
        double g = lerp(gray, base[1], metallic);
        double bl = lerp(gray, base[2], metallic);
        return color((float) r, (float) g, (float) bl, 1f);
    }

    private static double approxSpecularPower(float roughness) {
        // 2^(1 + (1-roughness)*9) → ~2 (rough) up to ~1024 (mirror-smooth).
        return Math.max(1.0, Math.pow(2.0, 1.0 + (1.0 - roughness) * 9.0));
    }

    private static Color color(float r, float g, float b, float a) {
        return Color.color(clamp01(r), clamp01(g), clamp01(b), clamp01(a));
    }

    private static double lerp(double from, double to, double t) {
        double tt = clamp01(t);
        return from + (to - from) * tt;
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    /**
     * Convert a glTF 16-float column-major matrix into a JavaFX {@link Affine}.
     * glTF column-major layout: {@code m[col*4 + row]}, translation in column 3.
     */
    private static Affine affineFromColumnMajor(float[] m) {
        return new Affine(
            m[0], m[4], m[8],  m[12],   // row 0: mxx mxy mxz tx
            m[1], m[5], m[9],  m[13],   // row 1: myx myy myz ty
            m[2], m[6], m[10], m[14]);  // row 2: mzx mzy mzz tz
    }
}
