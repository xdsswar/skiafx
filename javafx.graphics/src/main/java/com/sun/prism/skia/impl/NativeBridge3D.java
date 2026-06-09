package com.sun.prism.skia.impl;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FFM bindings to the SEPARATE {@code openjfx_skia_3d} native library —
 * the bgfx-backed 3D pipeline (see {@code openjfx_skia3d_bridge.h} and
 * docs/3D.md).
 *
 * <p>This library is loaded <em>optionally</em>. It only exists on
 * platforms where the 3D renderer has been built (Windows first), links
 * the 2D bridge ({@code openjfx_skia_shared}), and depends on bgfx. If
 * the library — or any individual symbol — is absent (non-Windows host,
 * a build without bgfx, or a Java/native version skew), every method
 * here degrades to "unavailable" / no-op and the 2D pipeline is never
 * affected. Nothing in this class throws from {@code <clinit>}.</p>
 */
public final class NativeBridge3D {

    private static final String LIB_BASENAME = "openjfx_skia_3d";

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = loadLibraryQuietly();

    private static final MethodHandle MH_AVAILABLE = optional(
        "openjfx_skia3d_available",
        FunctionDescriptor.of(ValueLayout.JAVA_INT));

    private static final MethodHandle MH_SPIKE_COMPOSITE = optional(
        "openjfx_skia3d_spike_composite",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,    // return: 0 ok, nonzero failure code
            ValueLayout.ADDRESS,     // surface handle (uintptr_t)
            ValueLayout.JAVA_INT,    // w
            ValueLayout.JAVA_INT));  // h

    // ---- Door 1: 3D resource + render entry points -------------------------
    // Handles (uintptr_t) cross as ADDRESS; create-methods return ADDRESS and
    // the Java wrappers expose them as long. All optional so a stub/old native
    // lib never breaks <clinit>.

    private static final MethodHandle MH_MESH_CREATE = optional(
        "openjfx_skia3d_mesh_create", FunctionDescriptor.of(ValueLayout.ADDRESS));
    private static final MethodHandle MH_MESH_BUILD_INT = optional(
        "openjfx_skia3d_mesh_build_int", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,   // vbuf, vlen
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT)); // ibuf(int32), ilen
    private static final MethodHandle MH_MESH_BUILD_SHORT = optional(
        "openjfx_skia3d_mesh_build_short", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,   // vbuf, vlen
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT)); // ibuf(int16), ilen
    private static final MethodHandle MH_MESH_DESTROY = optional(
        "openjfx_skia3d_mesh_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private static final MethodHandle MH_MATERIAL_CREATE = optional(
        "openjfx_skia3d_material_create", FunctionDescriptor.of(ValueLayout.ADDRESS));
    private static final MethodHandle MH_MATERIAL_SET_DIFFUSE = optional(
        "openjfx_skia3d_material_set_diffuse", FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
    private static final MethodHandle MH_MATERIAL_SET_SPECULAR = optional(
        "openjfx_skia3d_material_set_specular", FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
    private static final MethodHandle MH_MATERIAL_SET_TEXTURE = optional(
        "openjfx_skia3d_material_set_texture", FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
    private static final MethodHandle MH_MATERIAL_BIND_TEXTURE = optional(
        "openjfx_skia3d_material_bind_texture", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
    private static final MethodHandle MH_MATERIAL_DESTROY = optional(
        "openjfx_skia3d_material_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private static final MethodHandle MH_MESHVIEW_CREATE = optional(
        "openjfx_skia3d_meshview_create", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle MH_MESHVIEW_SET_MATERIAL = optional(
        "openjfx_skia3d_meshview_set_material", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle MH_MESHVIEW_SET_CULLING = optional(
        "openjfx_skia3d_meshview_set_culling", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle MH_MESHVIEW_SET_WIREFRAME = optional(
        "openjfx_skia3d_meshview_set_wireframe", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle MH_MESHVIEW_SET_AMBIENT = optional(
        "openjfx_skia3d_meshview_set_ambient", FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
    private static final MethodHandle MH_MESHVIEW_SET_LIGHT = optional(
        "openjfx_skia3d_meshview_set_light", FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,                              // handle, index
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, // x y z
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, // r g b
            ValueLayout.JAVA_FLOAT,                                                 // w
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, // ca la qa
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,                         // isAtt maxRange
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, // dir x y z
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));// inner outer falloff
    private static final MethodHandle MH_MESHVIEW_DESTROY = optional(
        "openjfx_skia3d_meshview_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private static final MethodHandle MH_DRAW3D = optional(
        "openjfx_skia3d_draw", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, // target, meshview, material
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,                      // projView[16], model[16]
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT)); // camPos

    private static final MethodHandle MH_TARGET_CREATE = optional(
        "openjfx_skia3d_target_create", FunctionDescriptor.of(
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle MH_TARGET_DESTROY = optional(
        "openjfx_skia3d_target_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    private static final MethodHandle MH_TARGET_BEGIN = optional(
        "openjfx_skia3d_target_begin", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT,
            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
    private static final MethodHandle MH_TARGET_END = optional(
        "openjfx_skia3d_target_end", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle MH_TARGET_WRAP_IMAGE = optional(
        "openjfx_skia3d_target_wrap_image", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle MH_NOTIFY_PRESENT = optional(
        "openjfx_skia3d_notify_present", FunctionDescriptor.ofVoid());

    private NativeBridge3D() {}

    /**
     * True if the native 3D renderer is present and usable on the active
     * GPU backend (currently: Windows + D3D12). Never throws.
     */
    public static boolean available() {
        if (MH_AVAILABLE == null) {
            return false;
        }
        try {
            return (int) MH_AVAILABLE.invokeExact() != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * SPIKE ONLY: render one bgfx cube into a texture on Skia's device
     * and composite it, zero-copy, onto the given Skia surface. Returns
     * the native status code (0 on success), or a negative value if the
     * 3D library / symbol is unavailable. Never throws — callers ignore
     * the result so the spike can never break normal painting.
     */
    public static int spikeComposite(long surfaceHandle, int w, int h) {
        if (MH_SPIKE_COMPOSITE == null || surfaceHandle == 0L) {
            return -1;
        }
        try {
            return (int) MH_SPIKE_COMPOSITE.invokeExact(
                MemorySegment.ofAddress(surfaceHandle), w, h);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---- Mesh ---------------------------------------------------------------

    public static long meshCreate() {
        if (MH_MESH_CREATE == null) return 0L;
        try { return ((MemorySegment) MH_MESH_CREATE.invokeExact()).address(); }
        catch (Throwable t) { return 0L; }
    }

    public static boolean meshBuildInt(long mesh, float[] vb, int vlen, int[] ib, int ilen) {
        if (MH_MESH_BUILD_INT == null || mesh == 0L) return false;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment vseg = a.allocate((long) vlen * Float.BYTES);
            MemorySegment.copy(vb, 0, vseg, ValueLayout.JAVA_FLOAT, 0, vlen);
            MemorySegment iseg = a.allocate((long) ilen * Integer.BYTES);
            MemorySegment.copy(ib, 0, iseg, ValueLayout.JAVA_INT, 0, ilen);
            return (int) MH_MESH_BUILD_INT.invokeExact(
                MemorySegment.ofAddress(mesh), vseg, vlen, iseg, ilen) != 0;
        } catch (Throwable t) { return false; }
    }

    public static boolean meshBuildShort(long mesh, float[] vb, int vlen, short[] ib, int ilen) {
        if (MH_MESH_BUILD_SHORT == null || mesh == 0L) return false;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment vseg = a.allocate((long) vlen * Float.BYTES);
            MemorySegment.copy(vb, 0, vseg, ValueLayout.JAVA_FLOAT, 0, vlen);
            MemorySegment iseg = a.allocate((long) ilen * Short.BYTES);
            MemorySegment.copy(ib, 0, iseg, ValueLayout.JAVA_SHORT, 0, ilen);
            return (int) MH_MESH_BUILD_SHORT.invokeExact(
                MemorySegment.ofAddress(mesh), vseg, vlen, iseg, ilen) != 0;
        } catch (Throwable t) { return false; }
    }

    public static void meshDestroy(long mesh) {
        if (MH_MESH_DESTROY == null || mesh == 0L) return;
        try { MH_MESH_DESTROY.invokeExact(MemorySegment.ofAddress(mesh)); } catch (Throwable t) { }
    }

    // ---- Material -----------------------------------------------------------

    public static long materialCreate() {
        if (MH_MATERIAL_CREATE == null) return 0L;
        try { return ((MemorySegment) MH_MATERIAL_CREATE.invokeExact()).address(); }
        catch (Throwable t) { return 0L; }
    }

    public static void materialSetDiffuseColor(long m, float r, float g, float b, float a) {
        if (MH_MATERIAL_SET_DIFFUSE == null || m == 0L) return;
        try { MH_MATERIAL_SET_DIFFUSE.invokeExact(MemorySegment.ofAddress(m), r, g, b, a); }
        catch (Throwable t) { }
    }

    public static void materialSetSpecularColor(long m, boolean set, float r, float g, float b) {
        if (MH_MATERIAL_SET_SPECULAR == null || m == 0L) return;
        try { MH_MATERIAL_SET_SPECULAR.invokeExact(MemorySegment.ofAddress(m), set ? 1 : 0, r, g, b); }
        catch (Throwable t) { }
    }

    /**
     * Upload (or clear) a material texture map.
     *
     * @param m            native material handle
     * @param typeOrdinal  {@code com.sun.prism.PhongMaterial.MapType} ordinal
     *                     (0=diffuse, 1=specular, 2=bump/normal, 3=self-illum)
     * @param pixels       tightly-packed RGBA8 pixels ({@code w*h*4} bytes), or
     *                     {@link MemorySegment#NULL} to clear the slot
     * @param w            width in pixels
     * @param h            height in pixels
     * @param imageId      stable per-Image id ({@code 0} = un-shared) so the uploaded
     *                     texture can be reused by other materials via
     *                     {@link #materialBindTexture}
     */
    public static void materialSetTexture(long m, int typeOrdinal, MemorySegment pixels, int w, int h, long imageId) {
        if (MH_MATERIAL_SET_TEXTURE == null || m == 0L) return;
        // Hoist the nullable segment to a typed local: passing a reference ternary
        // directly to invokeExact makes javac type it as Object → WrongMethodType.
        MemorySegment px = (pixels == null) ? MemorySegment.NULL : pixels;
        try {
            MH_MATERIAL_SET_TEXTURE.invokeExact(MemorySegment.ofAddress(m), typeOrdinal, px, w, h, imageId);
        } catch (Throwable t) { }
    }

    /**
     * Bind an already-uploaded shared texture (by {@code imageId}) into a material slot,
     * taking a reference. Returns {@code true} if the id was found and bound, {@code false}
     * if it is not in the registry (the caller must then upload via
     * {@link #materialSetTexture}). Skips the RGBA8 conversion + GPU upload for repeats.
     */
    public static boolean materialBindTexture(long m, int typeOrdinal, long imageId) {
        if (MH_MATERIAL_BIND_TEXTURE == null || m == 0L || imageId == 0L) return false;
        try {
            return (int) MH_MATERIAL_BIND_TEXTURE.invokeExact(
                    MemorySegment.ofAddress(m), typeOrdinal, imageId) != 0;
        } catch (Throwable t) { return false; }
    }

    public static void materialDestroy(long m) {
        if (MH_MATERIAL_DESTROY == null || m == 0L) return;
        try { MH_MATERIAL_DESTROY.invokeExact(MemorySegment.ofAddress(m)); } catch (Throwable t) { }
    }

    // ---- MeshView -----------------------------------------------------------

    public static long meshViewCreate(long mesh) {
        if (MH_MESHVIEW_CREATE == null) return 0L;
        try { return ((MemorySegment) MH_MESHVIEW_CREATE.invokeExact(MemorySegment.ofAddress(mesh))).address(); }
        catch (Throwable t) { return 0L; }
    }

    public static void meshViewSetMaterial(long mv, long material) {
        if (MH_MESHVIEW_SET_MATERIAL == null || mv == 0L) return;
        try { MH_MESHVIEW_SET_MATERIAL.invokeExact(MemorySegment.ofAddress(mv), MemorySegment.ofAddress(material)); }
        catch (Throwable t) { }
    }

    public static void meshViewSetCulling(long mv, int mode) {
        if (MH_MESHVIEW_SET_CULLING == null || mv == 0L) return;
        try { MH_MESHVIEW_SET_CULLING.invokeExact(MemorySegment.ofAddress(mv), mode); } catch (Throwable t) { }
    }

    public static void meshViewSetWireframe(long mv, boolean wf) {
        if (MH_MESHVIEW_SET_WIREFRAME == null || mv == 0L) return;
        try { MH_MESHVIEW_SET_WIREFRAME.invokeExact(MemorySegment.ofAddress(mv), wf ? 1 : 0); } catch (Throwable t) { }
    }

    public static void meshViewSetAmbient(long mv, float r, float g, float b) {
        if (MH_MESHVIEW_SET_AMBIENT == null || mv == 0L) return;
        try { MH_MESHVIEW_SET_AMBIENT.invokeExact(MemorySegment.ofAddress(mv), r, g, b); } catch (Throwable t) { }
    }

    public static void meshViewSetLight(long mv, int index, float x, float y, float z,
            float r, float g, float b, float w, float ca, float la, float qa,
            float isAttenuated, float maxRange, float dirX, float dirY, float dirZ,
            float innerAngle, float outerAngle, float falloff) {
        if (MH_MESHVIEW_SET_LIGHT == null || mv == 0L) return;
        try {
            MH_MESHVIEW_SET_LIGHT.invokeExact(MemorySegment.ofAddress(mv), index, x, y, z,
                r, g, b, w, ca, la, qa, isAttenuated, maxRange, dirX, dirY, dirZ,
                innerAngle, outerAngle, falloff);
        } catch (Throwable t) { }
    }

    public static void meshViewDestroy(long mv) {
        if (MH_MESHVIEW_DESTROY == null || mv == 0L) return;
        try { MH_MESHVIEW_DESTROY.invokeExact(MemorySegment.ofAddress(mv)); } catch (Throwable t) { }
    }

    // ---- Per-SubScene target + draw ----------------------------------------

    /**
     * Create a native bgfx 3D target of {@code w×h} device pixels.
     *
     * @param samples desired MSAA sample count: {@code <=0} = pipeline default
     *                ({@code OPENJFX_SKIA_3D_MSAA}), {@code 1} = no AA, {@code 2/4/8}
     *                = that count (snapped to device support). 0 if 3D unavailable.
     */
    public static long targetCreate(int w, int h, int samples) {
        if (MH_TARGET_CREATE == null) return 0L;
        try { return ((MemorySegment) MH_TARGET_CREATE.invokeExact(w, h, samples)).address(); }
        catch (Throwable t) { return 0L; }
    }

    public static void targetDestroy(long target) {
        if (MH_TARGET_DESTROY == null || target == 0L) return;
        try { MH_TARGET_DESTROY.invokeExact(MemorySegment.ofAddress(target)); } catch (Throwable t) { }
    }

    /** Begin a SubScene 3D pass: bind framebuffer + clear to (r,g,b,a). */
    public static int targetBegin(long target, float r, float g, float b, float a) {
        if (MH_TARGET_BEGIN == null || target == 0L) return -1;
        try { return (int) MH_TARGET_BEGIN.invokeExact(MemorySegment.ofAddress(target), r, g, b, a); }
        catch (Throwable t) { return -1; }
    }

    /** End a SubScene 3D pass (submit). */
    public static int targetEnd(long target) {
        if (MH_TARGET_END == null || target == 0L) return -1;
        try { return (int) MH_TARGET_END.invokeExact(MemorySegment.ofAddress(target)); }
        catch (Throwable t) { return -1; }
    }

    /**
     * Notify the 3D pipeline that one real swap-chain present completed. Drives the
     * deferred target-free latency so a 3D render target is only reclaimed once the
     * GPU has presented past the frame that last used it. Cheap (atomic increment +
     * a drain check) and a no-op when the 3D library isn't loaded; safe to call once
     * per present for every window (3D or not).
     */
    public static void notifyPresent() {
        if (MH_NOTIFY_PRESENT == null) return;
        try { MH_NOTIFY_PRESENT.invokeExact(); }
        catch (Throwable t) { /* never break presentation */ }
    }

    /** Wrap the target's color as a zero-copy SkImage; returns an image handle (0 on failure). */
    public static long targetWrapImage(long target) {
        if (MH_TARGET_WRAP_IMAGE == null || target == 0L) return 0L;
        try { return ((MemorySegment) MH_TARGET_WRAP_IMAGE.invokeExact(MemorySegment.ofAddress(target))).address(); }
        catch (Throwable t) { return 0L; }
    }

    public static int draw3D(long target, long meshView, long material,
                             float[] projView, float[] model, float cx, float cy, float cz) {
        if (MH_DRAW3D == null || target == 0L || meshView == 0L) return -1;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pv = a.allocate(16L * Float.BYTES);
            MemorySegment.copy(projView, 0, pv, ValueLayout.JAVA_FLOAT, 0, 16);
            MemorySegment md = a.allocate(16L * Float.BYTES);
            MemorySegment.copy(model, 0, md, ValueLayout.JAVA_FLOAT, 0, 16);
            return (int) MH_DRAW3D.invokeExact(
                MemorySegment.ofAddress(target), MemorySegment.ofAddress(meshView),
                MemorySegment.ofAddress(material), pv, md, cx, cy, cz);
        } catch (Throwable t) { return -1; }
    }

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
            // openjfx_skia_3d depends on openjfx_skia_shared: ensure the
            // 2D bridge is resident first so the OS resolves that
            // dependency by its already-loaded module name (covers both
            // the dev and the exported ~/.skia-fx/cache cases — the two
            // libs always live in the same directory).
            NativeBridge.version();

            // 1. Explicit override for this lib.
            String override = System.getProperty("openjfx.skia3d.nativeLib");
            if (override != null) {
                return SymbolLookup.libraryLookup(Path.of(override), Arena.ofAuto());
            }
            // 2. Dev tree: the 3D lib sits next to the 2D bridge, whose
            //    absolute path the sample run config sets via
            //    openjfx.skia.nativeLib. Derive the sibling — robust
            //    regardless of the process working directory.
            Path sibling = siblingOfSharedLib();
            if (sibling != null && Files.exists(sibling)) {
                return SymbolLookup.libraryLookup(sibling, Arena.ofAuto());
            }
            // 3. Exported / deployed: extract from the jar to
            //    ~/.skia-fx/cache and System.load (NativeLibLoader),
            //    or find it on java.library.path. Same path every other
            //    native uses.
            com.sun.glass.utils.NativeLibLoader.loadLibrary(LIB_BASENAME);
            return SymbolLookup.loaderLookup();
        } catch (Throwable t) {
            // Optional library — absence must never break the pipeline.
            return null;
        }
    }

    /**
     * The 3D lib next to the already-resolved 2D bridge. The sample run
     * config sets {@code openjfx.skia.nativeLib} to the absolute path of
     * {@code openjfx_skia_shared}; the 3D lib is built into the same
     * directory, so swapping the file name yields its path without any
     * dependence on the process working directory.
     */
    private static Path siblingOfSharedLib() {
        String shared = System.getProperty("openjfx.skia.nativeLib");
        if (shared == null) {
            return null;
        }
        Path dir = Path.of(shared).toAbsolutePath().getParent();
        if (dir == null) {
            return null;
        }
        for (String filename : libFilenames()) {
            Path candidate = dir.resolve(filename);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String[] libFilenames() {
        String os = System.getProperty("os.name").toLowerCase();
        String platform = os.contains("win") ? "win"
                        : os.contains("mac") ? "mac"
                        : "linux";
        return switch (platform) {
            case "win" -> new String[] {
                LIB_BASENAME + ".dll",
                "lib" + LIB_BASENAME + ".dll",
            };
            case "mac" -> new String[] { "lib" + LIB_BASENAME + ".dylib" };
            default    -> new String[] { "lib" + LIB_BASENAME + ".so" };
        };
    }
}
