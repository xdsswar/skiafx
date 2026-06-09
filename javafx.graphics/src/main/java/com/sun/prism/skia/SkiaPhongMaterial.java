package com.sun.prism.skia;

import com.sun.javafx.tk.Toolkit;
import com.sun.prism.Image;
import com.sun.prism.PixelFormat;
import com.sun.prism.TextureMap;
import com.sun.prism.impl.BasePhongMaterial;
import com.sun.prism.impl.Disposer;
import com.sun.prism.skia.impl.NativeBridge3D;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Skia/bgfx-backed {@link com.sun.prism.PhongMaterial}.
 *
 * <p>Mirrors {@code ES2PhongMaterial}: a thin wrapper over a native material handle.
 * Forwards the diffuse + specular colors and all four JavaFX texture maps
 * ({@code DIFFUSE}, {@code SPECULAR}, {@code BUMP}/normal, {@code SELF_ILLUM}) to the
 * native bgfx renderer, which samples them in the Phong shader. See docs/3D.md
 * (Door 1).</p>
 *
 * <p>Texture maps are uploaded lazily in {@link #lockTextureMaps()} (called by
 * {@link SkiaMeshView#render} before each draw, on the render thread) and cached by
 * image identity so an unchanged map is uploaded only once. Each prism {@link Image}
 * is converted to tightly-packed RGBA8 before being handed to the native layer.</p>
 */
final class SkiaPhongMaterial extends BasePhongMaterial {

    private final long nativeHandle;
    // The map set last applied by the scene-graph sync (indexed by MapType ordinal).
    private final TextureMap[] maps = new TextureMap[MAX_MAP_TYPE];
    // The Image last uploaded to native for each slot — used to skip redundant
    // uploads when the map hasn't changed between renders.
    private final Image[] uploaded = new Image[MAX_MAP_TYPE];

    private SkiaPhongMaterial(long nativeHandle, Disposer.Record disposerRecord) {
        super(disposerRecord);
        this.nativeHandle = nativeHandle;
    }

    static SkiaPhongMaterial create() {
        long h = NativeBridge3D.materialCreate();
        return new SkiaPhongMaterial(h, new SkiaPhongMaterialDisposerRecord(h));
    }

    long getNativeHandle() { return nativeHandle; }

    @Override
    public void setDiffuseColor(float r, float g, float b, float a) {
        NativeBridge3D.materialSetDiffuseColor(nativeHandle, r, g, b, a);
    }

    @Override
    public void setSpecularColor(boolean set, float r, float g, float b, float a) {
        // Specular shading uses rgb + power; color alpha is unused.
        NativeBridge3D.materialSetSpecularColor(nativeHandle, set, r, g, b);
    }

    @Override
    public void setTextureMap(TextureMap map) {
        // Record the map; the actual GPU upload happens in lockTextureMaps() on the
        // render thread (where bgfx texture creation must occur).
        maps[map.getType().ordinal()] = map;
    }

    @Override
    public void lockTextureMaps() {
        // Upload any map whose backing image changed since the last render. Cheap
        // when nothing changed (identity comparison short-circuits).
        for (int i = 0; i < maps.length; i++) {
            TextureMap map = maps[i];
            uploadIfChanged(i, map == null ? null : map.getImage());
        }
    }

    @Override
    public void unlockTextureMaps() {
        // Nothing to unlock: the native texture owns its own copy of the pixels.
    }

    @Override
    public void dispose() {
        disposerRecord.dispose();
    }

    /**
     * Upload {@code img} into slot {@code ordinal} if it differs from what is already
     * there. A {@code null} image clears the slot (shader falls back to solid color).
     * Unsupported pixel formats leave the slot empty rather than failing.
     */
    private void uploadIfChanged(int ordinal, Image img) {
        if (img == uploaded[ordinal]) {
            return; // unchanged (covers both-null)
        }
        uploaded[ordinal] = img;
        if (img == null) {
            NativeBridge3D.materialSetTexture(nativeHandle, ordinal, null, 0, 0, 0L);
            return;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0) {
            uploaded[ordinal] = null;
            return;
        }
        // Fast path: if another material already uploaded this exact Image, the native
        // side keeps one refcounted GPU texture per source image. Bind that shared
        // texture instead of re-converting + re-uploading the (often multi-MB) pixels
        // and rebuilding its mipmap chain. This is the common case for a texture tiled
        // across several shapes and is the bulk of the 3D cold-start cost otherwise.
        long imageId = imageId(img);
        if (NativeBridge3D.materialBindTexture(nativeHandle, ordinal, imageId)) {
            return; // cheap: bound a shared texture, no budget consumed
        }
        // A real upload (RGBA8 conversion + GPU upload + mipmap build) is expensive
        // (tens of ms for a 4K map). Cap how many happen per frame so a scene with
        // several large maps doesn't stall its first frame; defer the rest to the
        // next frame, requesting a pulse so even a fully static scene finishes
        // texturing. Until a slot's map lands, the shader uses the solid color.
        if (uploadBudget <= 0) {
            uploaded[ordinal] = null; // retry this slot next frame
            requestAnotherPulse();
            return;
        }
        uploadBudget--;
        byte[] rgba = toRgba8(img, w, h);
        if (rgba == null) {
            // Unsupported format (e.g. an HDR/EXR or YCbCr buffer) — skip, keep the
            // slot empty so the material renders with its solid colors.
            uploaded[ordinal] = null;
            return;
        }
        // Copy to off-heap memory for the FFM call; native copies it into the bgfx
        // texture, so the arena can be released immediately afterwards. The native
        // side registers the texture under imageId so later materials hit the bind
        // fast path above.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(rgba.length);
            MemorySegment.copy(rgba, 0, seg, ValueLayout.JAVA_BYTE, 0, rgba.length);
            NativeBridge3D.materialSetTexture(nativeHandle, ordinal, seg, w, h, imageId);
        }
    }

    // Stable non-zero id per prism Image, shared across materials so the native layer
    // can dedup GPU textures by source image. Weak keys: a collected Image drops its
    // mapping (its native texture is refcounted independently and re-registers on a
    // later re-upload under a fresh id). Render-thread only — guarded for safety.
    private static final Map<Image, Long> IMAGE_IDS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicLong NEXT_IMAGE_ID = new AtomicLong(1);

    private static long imageId(Image img) {
        return IMAGE_IDS.computeIfAbsent(img, k -> NEXT_IMAGE_ID.getAndIncrement());
    }

    // ---- Per-frame texture-upload throttle ----------------------------------
    // Max expensive (convert + upload + mipmap) texture uploads per SubScene 3D pass.
    // Tunable; default 1 = smoothest cold start (one big map per frame). Render-thread
    // only, so a plain field is fine.
    private static final int UPLOADS_PER_FRAME =
            Math.max(1, Integer.getInteger("skia.3d.texUploadsPerFrame", 1));
    private static int uploadBudget = UPLOADS_PER_FRAME;

    /** Reset the per-frame budget; called once when each SubScene 3D pass begins. */
    static void beginUploadPass() {
        uploadBudget = UPLOADS_PER_FRAME;
    }

    /**
     * Ask for another pulse so deferred uploads finish even on a static (non-animating)
     * scene. {@code requestNextPulse()} only sets an atomic flag, so it is safe to call
     * from the render thread. Best-effort: any failure just means an animating scene
     * still drains the queue on its own.
     */
    private static void requestAnotherPulse() {
        try {
            Toolkit.getToolkit().requestNextPulse();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Convert a prism {@link Image} to tightly-packed straight RGBA8, or {@code null}
     * for an unsupported pixel format. Handles the formats the JavaFX image loader
     * produces for PNG/JPG maps: BYTE_BGRA_PRE, BYTE_RGB and INT_ARGB_PRE.
     */
    private static byte[] toRgba8(Image img, int w, int h) {
        // w*h*4 in int overflows for a pathologically large (e.g. >23000px-square)
        // app-supplied map → NegativeArraySizeException out of the render thread.
        // Degrade: a map this big can't be a Java byte[] anyway, so skip it (the
        // material renders with its solid colours) rather than aborting the pass.
        if ((long) w * h * 4L > Integer.MAX_VALUE) {
            return null;
        }
        PixelFormat pf = img.getPixelFormat();
        Buffer pb = img.getPixelBuffer();
        int strideBytes = img.getScanlineStride();
        byte[] out = new byte[w * h * 4];
        switch (pf) {
            case BYTE_BGRA_PRE -> {
                // Bulk-copy each row (one absolute get instead of 4·w bounds-checked
                // gets), then swap B<->R in place. Far cheaper for large (4K) maps.
                ByteBuffer bb = (ByteBuffer) pb;
                int rowLen = w * 4;
                for (int y = 0; y < h; y++) {
                    bb.get(y * strideBytes, out, y * rowLen, rowLen);
                }
                for (int p = 0; p < out.length; p += 4) {
                    byte b = out[p];
                    out[p] = out[p + 2]; // R <- B
                    out[p + 2] = b;      // B <- R
                }
            }
            case BYTE_RGB -> {
                ByteBuffer bb = (ByteBuffer) pb;
                for (int y = 0; y < h; y++) {
                    int row = y * strideBytes;
                    int o = y * w * 4;
                    for (int x = 0; x < w; x++) {
                        int i = row + x * 3;
                        out[o++] = bb.get(i);     // R
                        out[o++] = bb.get(i + 1); // G
                        out[o++] = bb.get(i + 2); // B
                        out[o++] = (byte) 0xFF;   // A
                    }
                }
            }
            case BYTE_GRAY -> {
                // Single-channel (e.g. a grayscale specular/mask map) → replicate to RGB.
                ByteBuffer bb = (ByteBuffer) pb;
                for (int y = 0; y < h; y++) {
                    int row = y * strideBytes;
                    int o = y * w * 4;
                    for (int x = 0; x < w; x++) {
                        byte g = bb.get(row + x);
                        out[o++] = g;            // R
                        out[o++] = g;            // G
                        out[o++] = g;            // B
                        out[o++] = (byte) 0xFF;  // A
                    }
                }
            }
            case INT_ARGB_PRE -> {
                // Bulk-read each row of packed ints (one absolute get per row) then
                // unpack 0xAARRGGBB → RGBA bytes from the array.
                IntBuffer ib = (IntBuffer) pb;
                int strideInts = strideBytes / 4;
                int[] rowInts = new int[w];
                for (int y = 0; y < h; y++) {
                    ib.get(y * strideInts, rowInts, 0, w);
                    int o = y * w * 4;
                    for (int x = 0; x < w; x++) {
                        int p = rowInts[x]; // 0xAARRGGBB
                        out[o++] = (byte) (p >> 16); // R
                        out[o++] = (byte) (p >> 8);  // G
                        out[o++] = (byte) p;         // B
                        out[o++] = (byte) (p >> 24); // A
                    }
                }
            }
            default -> {
                return null; // unsupported (HDR/EXR, gray, YCbCr, ...)
            }
        }
        return out;
    }

    static final class SkiaPhongMaterialDisposerRecord implements Disposer.Record {
        private long handle;
        SkiaPhongMaterialDisposerRecord(long handle) { this.handle = handle; }
        @Override public void dispose() {
            if (handle != 0L) {
                NativeBridge3D.materialDestroy(handle);
                handle = 0L;
            }
        }
    }
}
