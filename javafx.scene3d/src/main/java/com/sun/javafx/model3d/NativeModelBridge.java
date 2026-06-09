/*
 * skia-fx — additive Door-2 module (javafx.scene3d). Original work; no upstream
 * OpenJFX provenance. See docs/3D.md and CLAUDE.md.
 *
 * This code is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 only, with the
 * Classpath exception, as published by the Free Software Foundation.
 */

package com.sun.javafx.model3d;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Private FFM bindings to the {@code openjfx_model3d} native asset library
 * (the cgltf-based glTF 2.0 parser). The heavy work — JSON/GLB parsing,
 * accessor decoding, the per-vertex/per-face array construction — happens in
 * C++; this class is a thin, allocation-light marshalling layer used only by
 * {@link GltfSceneBuilder}.
 *
 * <p><b>Handles never leak.</b> The native session handle is a {@code long}
 * confined to this package; nothing here is reachable from the public
 * {@code javafx.scene.model3d} API (memory {@code no-ids-handles-in-public-api}).
 * </p>
 *
 * <p>The library is optional: if it is absent or a symbol is missing (a build
 * without the native lib, or a version skew), every method degrades to
 * "unavailable" and {@link #isAvailable()} returns {@code false}. Nothing in
 * this class throws from {@code <clinit>} and no method aborts the process.</p>
 */
final class NativeModelBridge {

    private static final String LIB_BASENAME = "openjfx_model3d";

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = loadLibraryQuietly();

    // Texture slot selectors — must match the TexType enum in the C++ bridge.
    static final int TEX_BASE_COLOR = 0;
    static final int TEX_NORMAL     = 1;
    static final int TEX_EMISSIVE   = 2;
    static final int TEX_METAL_ROUGH = 3;

    // Texture source kinds — must match the TexKind enum in the C++ bridge.
    static final int KIND_NONE     = 0;
    static final int KIND_EMBEDDED = 1;
    static final int KIND_URI      = 2;

    // VertexFormat codes — must match VertexFormatCode in the C++ bridge.
    static final int FMT_POINT_TEXCOORD        = 0;
    static final int FMT_POINT_NORMAL_TEXCOORD = 1;

    private static final MethodHandle MH_VERSION = optional(
        "model3d_version", FunctionDescriptor.of(ValueLayout.JAVA_INT));
    private static final MethodHandle MH_OPEN = optional(
        "model3d_open", FunctionDescriptor.of(ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
    private static final MethodHandle MH_CLOSE = optional(
        "model3d_close", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private static final MethodHandle MH_MESH_COUNT = optional(
        "model3d_mesh_count", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle MH_PRIM_COUNT = optional(
        "model3d_primitive_count", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle MH_PRIM_INFO = optional(
        "model3d_primitive_info", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle MH_PRIM_BUILD = optional(
        "model3d_primitive_build", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static final MethodHandle MH_MAT_COUNT = optional(
        "model3d_material_count", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle MH_MAT_INFO = optional(
        "model3d_material_info", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle MH_MAT_TEX = optional(
        "model3d_material_texture", FunctionDescriptor.of(
            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    private static final MethodHandle MH_NODE_COUNT = optional(
        "model3d_node_count", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle MH_NODE_MATRIX = optional(
        "model3d_node_local_matrix", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle MH_NODE_MESH = optional(
        "model3d_node_mesh", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle MH_NODE_CHILD_COUNT = optional(
        "model3d_node_child_count", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle MH_NODE_CHILD = optional(
        "model3d_node_child", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle MH_ROOT_COUNT = optional(
        "model3d_root_count", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle MH_ROOT = optional(
        "model3d_root", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle MH_NODE_NAME = optional(
        "model3d_node_name", FunctionDescriptor.of(
            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle MH_MESH_NAME = optional(
        "model3d_mesh_name", FunctionDescriptor.of(
            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle MH_SCENE_NAME = optional(
        "model3d_scene_name", FunctionDescriptor.of(
            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    private NativeModelBridge() {}

    /** A material's scalar parameters, decoded from glTF pbrMetallicRoughness. */
    record MaterialData(float[] base, float metallic, float roughness,
                        float[] emissive, boolean doubleSided) {}

    /** A resolved texture slot: raw image bytes (embedded) or an external URI. */
    record TextureData(int kind, byte[] bytes, String uri) {
        static final TextureData NONE = new TextureData(KIND_NONE, null, null);
    }

    /** True if the native library loaded and the probe symbol resolved. */
    static boolean isAvailable() {
        if (MH_VERSION == null) {
            return false;
        }
        try {
            return (int) MH_VERSION.invokeExact() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Parse a .glb/.gltf blob. {@code baseDir} (may be null) resolves external
     * buffer/image URIs. Returns an opaque native session handle, or 0 on any
     * failure. The handle stays inside this package.
     */
    static long open(byte[] data, String baseDir) {
        if (MH_OPEN == null || data == null || data.length == 0) {
            return 0L;
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment dataSeg = a.allocate(data.length);
            MemorySegment.copy(data, 0, dataSeg, ValueLayout.JAVA_BYTE, 0, data.length);
            MemorySegment baseSeg = (baseDir == null) ? MemorySegment.NULL : a.allocateFrom(baseDir);
            MemorySegment h = (MemorySegment) MH_OPEN.invokeExact(
                dataSeg, (long) data.length, baseSeg);
            return h.address();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** Release a native session. Null/zero/double-close is a safe no-op. */
    static void close(long handle) {
        if (MH_CLOSE == null || handle == 0L) {
            return;
        }
        try {
            MH_CLOSE.invokeExact(MemorySegment.ofAddress(handle));
        } catch (Throwable t) {
            // never propagate from a release path
        }
    }

    static int meshCount(long h) {
        if (MH_MESH_COUNT == null || h == 0L) return -1;
        try { return (int) MH_MESH_COUNT.invokeExact(MemorySegment.ofAddress(h)); }
        catch (Throwable t) { return -1; }
    }

    static int primitiveCount(long h, int meshIdx) {
        if (MH_PRIM_COUNT == null || h == 0L) return -1;
        try { return (int) MH_PRIM_COUNT.invokeExact(MemorySegment.ofAddress(h), meshIdx); }
        catch (Throwable t) { return -1; }
    }

    /**
     * Returns {@code [vtxCount, faceCount, format, materialIndex, flags]} for a
     * primitive, or {@code null} on failure.
     */
    static int[] primitiveInfo(long h, int meshIdx, int primIdx) {
        if (MH_PRIM_INFO == null || h == 0L) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment vtx = a.allocate(ValueLayout.JAVA_INT);
            MemorySegment fc  = a.allocate(ValueLayout.JAVA_INT);
            MemorySegment fmt = a.allocate(ValueLayout.JAVA_INT);
            MemorySegment mat = a.allocate(ValueLayout.JAVA_INT);
            MemorySegment flg = a.allocate(ValueLayout.JAVA_INT);
            int rc = (int) MH_PRIM_INFO.invokeExact(MemorySegment.ofAddress(h),
                meshIdx, primIdx, vtx, fc, fmt, mat, flg);
            if (rc != 0) {
                return null;
            }
            return new int[] {
                vtx.get(ValueLayout.JAVA_INT, 0),
                fc.get(ValueLayout.JAVA_INT, 0),
                fmt.get(ValueLayout.JAVA_INT, 0),
                mat.get(ValueLayout.JAVA_INT, 0),
                flg.get(ValueLayout.JAVA_INT, 0),
            };
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Fill the JavaFX-ready geometry arrays for a primitive. {@code normals}
     * may be null when the format is POINT_TEXCOORD. Returns true on success.
     */
    static boolean primitiveBuild(long h, int meshIdx, int primIdx,
                                  float[] points, float[] normals,
                                  float[] texcoords, int[] faces) {
        if (MH_PRIM_BUILD == null || h == 0L || points == null
            || texcoords == null || faces == null) {
            return false;
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pSeg = a.allocate((long) points.length * Float.BYTES);
            MemorySegment nSeg = (normals != null)
                ? a.allocate((long) normals.length * Float.BYTES) : MemorySegment.NULL;
            MemorySegment tSeg = a.allocate((long) texcoords.length * Float.BYTES);
            MemorySegment fSeg = a.allocate((long) faces.length * Integer.BYTES);
            int rc = (int) MH_PRIM_BUILD.invokeExact(MemorySegment.ofAddress(h),
                meshIdx, primIdx, pSeg, nSeg, tSeg, fSeg);
            if (rc != 0) {
                return false;
            }
            MemorySegment.copy(pSeg, ValueLayout.JAVA_FLOAT, 0, points, 0, points.length);
            if (normals != null) {
                MemorySegment.copy(nSeg, ValueLayout.JAVA_FLOAT, 0, normals, 0, normals.length);
            }
            MemorySegment.copy(tSeg, ValueLayout.JAVA_FLOAT, 0, texcoords, 0, texcoords.length);
            MemorySegment.copy(fSeg, ValueLayout.JAVA_INT, 0, faces, 0, faces.length);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static int materialCount(long h) {
        if (MH_MAT_COUNT == null || h == 0L) return -1;
        try { return (int) MH_MAT_COUNT.invokeExact(MemorySegment.ofAddress(h)); }
        catch (Throwable t) { return -1; }
    }

    /** Decode a material's scalar parameters, or {@code null} on failure. */
    static MaterialData materialInfo(long h, int matIdx) {
        if (MH_MAT_INFO == null || h == 0L) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out9 = a.allocate((long) 9 * Float.BYTES);
            MemorySegment flg  = a.allocate(ValueLayout.JAVA_INT);
            int rc = (int) MH_MAT_INFO.invokeExact(MemorySegment.ofAddress(h), matIdx, out9, flg);
            if (rc != 0) {
                return null;
            }
            float[] v = new float[9];
            MemorySegment.copy(out9, ValueLayout.JAVA_FLOAT, 0, v, 0, 9);
            float[] base = new float[] { v[0], v[1], v[2], v[3] };
            float[] emissive = new float[] { v[6], v[7], v[8] };
            boolean doubleSided = (flg.get(ValueLayout.JAVA_INT, 0) & 1) != 0;
            return new MaterialData(base, v[4], v[5], emissive, doubleSided);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Resolve a material texture slot. Uses the two-call native protocol
     * (probe for kind+length, then copy) so the JVM owns the resulting bytes.
     * Never returns null — absent slots come back as {@link TextureData#NONE}.
     */
    static TextureData materialTexture(long h, int matIdx, int type) {
        if (MH_MAT_TEX == null || h == 0L) return TextureData.NONE;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment kindSeg = a.allocate(ValueLayout.JAVA_INT);
            long needed = (long) MH_MAT_TEX.invokeExact(MemorySegment.ofAddress(h),
                matIdx, type, kindSeg, MemorySegment.NULL, 0L);
            int kind = kindSeg.get(ValueLayout.JAVA_INT, 0);
            if (kind == KIND_NONE || needed <= 0) {
                return TextureData.NONE;
            }
            MemorySegment dst = a.allocate(needed);
            long got = (long) MH_MAT_TEX.invokeExact(MemorySegment.ofAddress(h),
                matIdx, type, kindSeg, dst, needed);
            if (got <= 0) {
                return TextureData.NONE;
            }
            if (kind == KIND_EMBEDDED) {
                byte[] bytes = new byte[(int) needed];
                MemorySegment.copy(dst, ValueLayout.JAVA_BYTE, 0, bytes, 0, bytes.length);
                return new TextureData(KIND_EMBEDDED, bytes, null);
            }
            // KIND_URI: a NUL-terminated UTF-8 path.
            byte[] raw = new byte[(int) needed];
            MemorySegment.copy(dst, ValueLayout.JAVA_BYTE, 0, raw, 0, raw.length);
            int len = raw.length;
            while (len > 0 && raw[len - 1] == 0) {
                len--;
            }
            String uri = new String(raw, 0, len, StandardCharsets.UTF_8);
            return new TextureData(KIND_URI, null, uri);
        } catch (Throwable t) {
            return TextureData.NONE;
        }
    }

    static int nodeCount(long h) {
        if (MH_NODE_COUNT == null || h == 0L) return -1;
        try { return (int) MH_NODE_COUNT.invokeExact(MemorySegment.ofAddress(h)); }
        catch (Throwable t) { return -1; }
    }

    /** The node's local transform as a 16-float column-major matrix, or null. */
    static float[] nodeLocalMatrix(long h, int nodeIdx) {
        if (MH_NODE_MATRIX == null || h == 0L) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment m = a.allocate((long) 16 * Float.BYTES);
            int rc = (int) MH_NODE_MATRIX.invokeExact(MemorySegment.ofAddress(h), nodeIdx, m);
            if (rc != 0) {
                return null;
            }
            float[] out = new float[16];
            MemorySegment.copy(m, ValueLayout.JAVA_FLOAT, 0, out, 0, 16);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    static int nodeMesh(long h, int nodeIdx) {
        if (MH_NODE_MESH == null || h == 0L) return -1;
        try { return (int) MH_NODE_MESH.invokeExact(MemorySegment.ofAddress(h), nodeIdx); }
        catch (Throwable t) { return -1; }
    }

    static int nodeChildCount(long h, int nodeIdx) {
        if (MH_NODE_CHILD_COUNT == null || h == 0L) return -1;
        try { return (int) MH_NODE_CHILD_COUNT.invokeExact(MemorySegment.ofAddress(h), nodeIdx); }
        catch (Throwable t) { return -1; }
    }

    static int nodeChild(long h, int nodeIdx, int k) {
        if (MH_NODE_CHILD == null || h == 0L) return -1;
        try { return (int) MH_NODE_CHILD.invokeExact(MemorySegment.ofAddress(h), nodeIdx, k); }
        catch (Throwable t) { return -1; }
    }

    static int rootCount(long h) {
        if (MH_ROOT_COUNT == null || h == 0L) return -1;
        try { return (int) MH_ROOT_COUNT.invokeExact(MemorySegment.ofAddress(h)); }
        catch (Throwable t) { return -1; }
    }

    static int root(long h, int k) {
        if (MH_ROOT == null || h == 0L) return -1;
        try { return (int) MH_ROOT.invokeExact(MemorySegment.ofAddress(h), k); }
        catch (Throwable t) { return -1; }
    }

    /** glTF node name, or "" if unnamed. */
    static String nodeName(long h, int nodeIdx) {
        return readNameIdx(MH_NODE_NAME, h, nodeIdx);
    }

    /** glTF mesh name, or "" if unnamed. */
    static String meshName(long h, int meshIdx) {
        return readNameIdx(MH_MESH_NAME, h, meshIdx);
    }

    /** glTF default-scene name, or "" if unnamed. */
    static String sceneName(long h) {
        if (MH_SCENE_NAME == null || h == 0L) {
            return "";
        }
        try (Arena a = Arena.ofConfined()) {
            long needed = (long) MH_SCENE_NAME.invokeExact(MemorySegment.ofAddress(h),
                MemorySegment.NULL, 0L);
            if (needed <= 1) {
                return "";
            }
            MemorySegment dst = a.allocate(needed);
            long got = (long) MH_SCENE_NAME.invokeExact(MemorySegment.ofAddress(h), dst, needed);
            return (got <= 0) ? "" : cString(dst, (int) needed);
        } catch (Throwable t) {
            return "";
        }
    }

    // Shared two-call reader for the (handle, index) name getters.
    private static String readNameIdx(MethodHandle mh, long h, int idx) {
        if (mh == null || h == 0L) {
            return "";
        }
        try (Arena a = Arena.ofConfined()) {
            long needed = (long) mh.invokeExact(MemorySegment.ofAddress(h), idx,
                MemorySegment.NULL, 0L);
            if (needed <= 1) {
                return "";
            }
            MemorySegment dst = a.allocate(needed);
            long got = (long) mh.invokeExact(MemorySegment.ofAddress(h), idx, dst, needed);
            return (got <= 0) ? "" : cString(dst, (int) needed);
        } catch (Throwable t) {
            return "";
        }
    }

    // Decode a NUL-terminated UTF-8 C string from a segment of `len` bytes.
    private static String cString(MemorySegment seg, int len) {
        byte[] raw = new byte[len];
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0, raw, 0, len);
        int n = len;
        while (n > 0 && raw[n - 1] == 0) {
            n--;
        }
        return new String(raw, 0, n, StandardCharsets.UTF_8);
    }

    // ---- library loading ---------------------------------------------------

    private static MethodHandle optional(String symbol, FunctionDescriptor fd) {
        if (LOOKUP == null) {
            return null;
        }
        try {
            return LOOKUP.find(symbol)
                         .map(seg -> LINKER.downcallHandle(seg, fd))
                         .orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static SymbolLookup loadLibraryQuietly() {
        try {
            // 1. Explicit dev-tree override (the sample run config sets this to
            //    the absolute path of the freshly built lib).
            String override = System.getProperty("openjfx.model3d.nativeLib");
            if (override != null) {
                Path p = Path.of(override);
                if (Files.exists(p)) {
                    return SymbolLookup.libraryLookup(p, Arena.ofAuto());
                }
            }
            // 2. Deployed: extract from THIS module's jar to ~/.skia-fx/cache and
            //    System.load (or find it on java.library.path). NativeLibLoader
            //    resolves the lib + checksums.properties against the caller's jar
            //    (this class' module), so it loads javafx.scene3d's own lib.
            com.sun.glass.utils.NativeLibLoader.loadLibrary(LIB_BASENAME);
            return SymbolLookup.loaderLookup();
        } catch (Throwable t) {
            // Optional library — absence must never break module init.
            return null;
        }
    }
}
