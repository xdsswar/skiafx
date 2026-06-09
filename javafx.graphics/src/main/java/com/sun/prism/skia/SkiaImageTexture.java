package com.sun.prism.skia;

import com.sun.prism.Image;
import com.sun.prism.MediaFrame;
import com.sun.prism.PixelFormat;
import com.sun.prism.Texture.WrapMode;
import com.sun.prism.skia.impl.NativeBridge;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Skia-backed {@link com.sun.prism.Texture} for immutable image content.
 * Wraps an {@code SkImage} created from a Prism {@link Image}.
 *
 * <p>The Skia native side copies the pixels at creation time (via
 * {@code SkImages::RasterFromPixmapCopy}), so the source {@link Image}
 * may be released or mutated after the texture is constructed.</p>
 *
 * <p><b>Phase-1 supported formats:</b> {@code BYTE_BGRA_PRE},
 * {@code INT_ARGB_PRE} (same byte layout on little-endian),
 * {@code BYTE_GRAY}. {@code BYTE_RGB} and others go via a
 * format-conversion path (TODO).</p>
 */
public final class SkiaImageTexture extends SkiaTextureBase {

    public SkiaImageTexture(Image source, WrapMode wrapMode, boolean useMipmap) {
        super(uploadImage(source),
              SkiaImageTexture::destroyNative,
              source.getPixelFormat(),
              source.getWidth(), source.getHeight(),
              source.getWidth(), source.getHeight(),
              wrapMode,
              useMipmap,
              true /* countBytes — owns its native SkImage */);
    }

    /** Internal: wrap an already-created native SkImage handle (1×1 placeholder). */
    private SkiaImageTexture(long nativeHandle, WrapMode wrapMode) {
        super(nativeHandle,
              SkiaImageTexture::destroyNative,
              PixelFormat.BYTE_BGRA_PRE,
              1, 1, 1, 1,
              wrapMode,
              false,
              true /* countBytes — owns its native SkImage */);
    }

    /**
     * A 1×1 fully transparent texture used as a <b>fail-soft placeholder</b>
     * when a real image upload fails (unsupported pixel format, transient
     * native OOM). Drawing it stretched over a node's bounds paints nothing —
     * the node degrades to a blank frame instead of aborting paint or showing
     * GPU garbage — and the caller is free to retry the real upload on the next
     * frame. Returns {@code null} only if even a 4-byte allocation fails, at
     * which point the process is already out of memory.
     */
    static SkiaImageTexture newTransparentPlaceholder(WrapMode wrapMode) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(4); // zero-filled → transparent premultiplied BGRA
            MemorySegment h = NativeBridge.imageCreateRaster(
                1, 1, 4, seg, NativeBridge.CT_BGRA_8888_PREMUL);
            if (h == null || h.equals(MemorySegment.NULL)) {
                return null;
            }
            return new SkiaImageTexture(h.address(), wrapMode);
        }
    }

    private static void destroyNative(long handle) {
        NativeBridge.imageDestroy(MemorySegment.ofAddress(handle));
    }

    // ---- Texture update overloads (immutable; reject all) -----------------

    @Override public void update(Image img) { rejectUpdate(); }
    @Override public void update(Image img, int dstx, int dsty) { rejectUpdate(); }
    @Override public void update(Image img, int dstx, int dsty, int srcw, int srch) { rejectUpdate(); }
    @Override public void update(Image img, int dstx, int dsty, int srcw, int srch, boolean skipFlush) { rejectUpdate(); }
    @Override public void update(Buffer buffer, PixelFormat format,
                                 int dstx, int dsty, int srcx, int srcy, int srcw, int srch,
                                 int srcscan, boolean skipFlush) { rejectUpdate(); }
    @Override public void update(MediaFrame frame, boolean skipFlush) { rejectUpdate(); }

    private static void rejectUpdate() {
        throw new UnsupportedOperationException(
            "SkiaImageTexture is immutable. To re-upload, dispose and recreate.");
    }

    // ---- Upload helper ----------------------------------------------------

    private static long uploadImage(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int rowBytes = image.getScanlineStride();
        PixelFormat fmt = image.getPixelFormat();
        Buffer buf = image.getPixelBuffer();

        // BYTE_RGB has no direct SkColorType (Skia's kRGB_888x is 4-byte
        // stride with the 4th byte ignored). Expand on the Java side to a
        // padded RGBX buffer and upload as kRGB_888x. WebKit hits this for
        // any opaque source image (JPEGs etc.) that the decoder hands back
        // in tight-RGB form.
        if (fmt == PixelFormat.BYTE_RGB) {
            return uploadByteRgb(w, h, rowBytes, buf);
        }

        int colorType = switch (fmt) {
            case BYTE_BGRA_PRE -> NativeBridge.CT_BGRA_8888_PREMUL;
            // INT_ARGB_PRE: each int is 0xAARRGGBB. On little-endian this
            // is BGRA byte order, so it maps to BGRA_8888.
            case INT_ARGB_PRE  -> NativeBridge.CT_BGRA_8888_PREMUL;
            case BYTE_GRAY     -> NativeBridge.CT_GRAY_8;
            // BYTE_ALPHA — drop-shadow / blur masks etc. Single channel
            // interpreted as coverage on the destination.
            case BYTE_ALPHA    -> NativeBridge.CT_ALPHA_8;
            default -> throw new UnsupportedOperationException(
                "SkiaImageTexture: pixel format " + fmt + " not yet supported "
                + "(need format-conversion upload path).");
        };

        // Copy the buffer data into a confined arena, hand the pointer
        // to the native side which copies it again into Skia's internal
        // SkImage storage. The arena closes here, freeing our copy.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = toMemorySegment(arena, buf, fmt, h, rowBytes);
            MemorySegment imgHandle = NativeBridge.imageCreateRaster(
                w, h, rowBytes, seg, colorType);
            if (imgHandle == null || imgHandle.equals(MemorySegment.NULL)) {
                throw new IllegalStateException(
                    "Skia image upload failed (" + w + "x" + h + ", " + fmt + ")");
            }
            return imgHandle.address();
        }
    }

    /**
     * Expand a tight 3-byte-per-pixel RGB image into a 4-byte-per-pixel
     * RGBX layout and upload as kRGB_888x. The fourth byte (X) is set to
     * 0xFF so Skia callers that mis-treat it as alpha still see opaque.
     */
    private static long uploadByteRgb(int w, int h, int srcRowBytes, Buffer buf) {
        if (!(buf instanceof ByteBuffer bb)) {
            throw new UnsupportedOperationException(
                "SkiaImageTexture: BYTE_RGB upload expects a ByteBuffer, got "
                + buf.getClass().getSimpleName());
        }
        final int dstRowBytes = w * 4;
        final long dstByteSize = (long) dstRowBytes * h;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(dstByteSize);
            // Take a view at the buffer's current position without
            // disturbing the caller's mark / position.
            ByteBuffer src = bb.duplicate();
            byte[] srcRow = new byte[Math.max(srcRowBytes, w * 3)];
            byte[] dstRow = new byte[dstRowBytes];
            for (int y = 0; y < h; y++) {
                src.position(y * srcRowBytes);
                src.get(srcRow, 0, w * 3);
                for (int x = 0; x < w; x++) {
                    int si = x * 3;
                    int di = x * 4;
                    dstRow[di    ] = srcRow[si    ]; // R
                    dstRow[di + 1] = srcRow[si + 1]; // G
                    dstRow[di + 2] = srcRow[si + 2]; // B
                    dstRow[di + 3] = (byte) 0xFF;    // X-pad (ignored by Skia)
                }
                MemorySegment.copy(dstRow, 0, dst,
                    java.lang.foreign.ValueLayout.JAVA_BYTE,
                    (long) y * dstRowBytes, dstRowBytes);
            }
            MemorySegment imgHandle = NativeBridge.imageCreateRaster(
                w, h, dstRowBytes, dst, NativeBridge.CT_RGB_888x);
            if (imgHandle == null || imgHandle.equals(MemorySegment.NULL)) {
                throw new IllegalStateException(
                    "Skia BYTE_RGB upload failed (" + w + "x" + h + ")");
            }
            return imgHandle.address();
        }
    }

    private static MemorySegment toMemorySegment(Arena arena, Buffer buf,
                                                 PixelFormat fmt, int h, int rowBytes) {
        long byteSize = (long) rowBytes * h;
        MemorySegment dst = arena.allocate(byteSize);
        if (buf instanceof ByteBuffer bb) {
            int pos = bb.position();
            int lim = bb.limit();
            int n = lim - pos;
            if (bb.hasArray()) {
                MemorySegment.copy(bb.array(), bb.arrayOffset() + pos,
                                   dst, java.lang.foreign.ValueLayout.JAVA_BYTE,
                                   0L, Math.min(n, (int) byteSize));
            } else {
                // Fallback: copy via a temporary array
                byte[] tmp = new byte[Math.min(n, (int) byteSize)];
                bb.duplicate().get(tmp);
                MemorySegment.copy(tmp, 0, dst,
                                   java.lang.foreign.ValueLayout.JAVA_BYTE,
                                   0L, tmp.length);
            }
            return dst;
        }
        if (buf instanceof IntBuffer ib) {
            int pos = ib.position();
            int lim = ib.limit();
            int nInts = lim - pos;
            if (ib.hasArray()) {
                MemorySegment.copy(ib.array(), ib.arrayOffset() + pos,
                                   dst, java.lang.foreign.ValueLayout.JAVA_INT,
                                   0L, Math.min(nInts, (int) (byteSize / 4)));
            } else {
                int[] tmp = new int[Math.min(nInts, (int) (byteSize / 4))];
                ib.duplicate().get(tmp);
                MemorySegment.copy(tmp, 0, dst,
                                   java.lang.foreign.ValueLayout.JAVA_INT,
                                   0L, tmp.length);
            }
            return dst;
        }
        throw new UnsupportedOperationException(
            "SkiaImageTexture: unsupported Buffer type " + buf.getClass().getSimpleName());
    }
}
