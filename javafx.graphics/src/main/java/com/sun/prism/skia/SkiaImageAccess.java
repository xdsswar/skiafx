package com.sun.prism.skia;

import com.sun.prism.PixelFormat;
import com.sun.prism.skia.impl.NativeBridge;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Public hook for external modules (today: javafx.web) onto the Skia
 * pipeline's image-bridge entry points. {@link com.sun.prism.skia.impl}
 * is internal; everything cross-module must come through here.
 *
 * <p>All handles are {@code uintptr_t}-sized opaque pointers into the
 * native bridge's SkImage / SkData store. The caller owns the lifetime
 * — match every {@link #createRasterImage} / successful encode with the
 * corresponding {@link #destroyImage} (the encode buffer is freed
 * internally by {@link NativeBridge}).</p>
 *
 * <p>Wraps Skia's raster image upload + encode entry points so JavaFX
 * subsystems do not need {@code java.desktop}/AWT just to round-trip an
 * image through PNG/JPEG.</p>
 */
public final class SkiaImageAccess {

    /** PNG: lossless, ignores quality. */
    public static final int FMT_PNG  = NativeBridge.FMT_PNG;
    /** JPEG: lossy, 0..100. */
    public static final int FMT_JPEG = NativeBridge.FMT_JPEG;
    /** WebP: lossy, 0..100. */
    public static final int FMT_WEBP = NativeBridge.FMT_WEBP;

    private SkiaImageAccess() { /* no instances */ }

    /**
     * Uploads a CPU pixel buffer to a fresh SkImage and returns the
     * native handle. Returns 0 on unsupported format or failure.
     *
     * @param width / height image dims in pixels
     * @param rowBytes scanline stride in bytes
     * @param fmt one of the Prism {@link PixelFormat}s — currently
     *            {@code BYTE_BGRA_PRE}, {@code INT_ARGB_PRE},
     *            {@code BYTE_GRAY} are supported.
     * @param pixels source buffer (heap- or direct-backed)
     */
    public static long createRasterImage(int width, int height, int rowBytes,
                                         PixelFormat fmt, Buffer pixels) {
        if (width <= 0 || height <= 0 || pixels == null) return 0L;
        int colorType = switch (fmt) {
            case BYTE_BGRA_PRE -> NativeBridge.CT_BGRA_8888_PREMUL;
            case INT_ARGB_PRE  -> NativeBridge.CT_BGRA_8888_PREMUL;
            case BYTE_GRAY     -> NativeBridge.CT_GRAY_8;
            case BYTE_ALPHA    -> NativeBridge.CT_ALPHA_8;
            default            -> -1;
        };
        if (colorType < 0) return 0L;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = copyBufferToArena(arena, pixels, rowBytes, height);
            MemorySegment h = NativeBridge.imageCreateRaster(
                width, height, rowBytes, seg, colorType);
            if (h == null || h.equals(MemorySegment.NULL)) return 0L;
            return h.address();
        }
    }

    /** Releases a handle from {@link #createRasterImage}. Safe on 0. */
    public static void destroyImage(long handle) {
        if (handle == 0L) return;
        NativeBridge.imageDestroy(MemorySegment.ofAddress(handle));
    }

    /**
     * Encodes the image referenced by {@code handle} into PNG / JPEG /
     * WebP bytes. Returns {@code null} on failure (bad handle, unsupported
     * format, encode error, Skia not compiled in).
     */
    public static byte[] encodeImage(long handle, int format, int quality) {
        if (handle == 0L) return null;
        return NativeBridge.imageEncode(MemorySegment.ofAddress(handle), format, quality);
    }

    private static MemorySegment copyBufferToArena(Arena arena, Buffer buf,
                                                   int rowBytes, int h) {
        long byteSize = (long) rowBytes * h;
        MemorySegment dst = arena.allocate(byteSize);
        if (buf instanceof ByteBuffer bb) {
            int pos = bb.position();
            int n = (int) Math.min(bb.limit() - pos, byteSize);
            if (bb.hasArray()) {
                MemorySegment.copy(bb.array(), bb.arrayOffset() + pos,
                        dst, ValueLayout.JAVA_BYTE, 0L, n);
            } else {
                byte[] tmp = new byte[n];
                bb.duplicate().get(tmp);
                MemorySegment.copy(tmp, 0, dst, ValueLayout.JAVA_BYTE, 0L, n);
            }
            return dst;
        }
        if (buf instanceof IntBuffer ib) {
            int pos = ib.position();
            int nInts = (int) Math.min(ib.limit() - pos, byteSize / 4);
            if (ib.hasArray()) {
                MemorySegment.copy(ib.array(), ib.arrayOffset() + pos,
                        dst, ValueLayout.JAVA_INT, 0L, nInts);
            } else {
                int[] tmp = new int[nInts];
                ib.duplicate().get(tmp);
                MemorySegment.copy(tmp, 0, dst, ValueLayout.JAVA_INT, 0L, nInts);
            }
            return dst;
        }
        throw new UnsupportedOperationException(
            "SkiaImageAccess: unsupported Buffer type " + buf.getClass().getSimpleName());
    }
}
