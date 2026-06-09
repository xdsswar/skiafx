/*
 * Copyright (c) 2008, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.prism;

import java.nio.ByteBuffer;

/**
 * This interface describes a video image as received by the media stack. It is
 * generally just a wrapper/shim for VideoDataBuffer to avoid dependency issues.
 */
public interface MediaFrame {
    /**
     * @param plane the numeric index of the plane, for chunky formats pass zero
     * @return the {@code ByteBuffer} for the specified plane or null for
     * non-existent planes
     */
    public ByteBuffer getBufferForPlane(int plane);

    /**
     * @return {@link PixelFormat} describing how pixels are stored in this
     * frame's buffer
     */
    public PixelFormat getPixelFormat();

    /**
     * @return width in pixels of the video image contained in this frame
     */
    public int getWidth();

    /**
     * @return height in pixels of the video image contained in this frame
     */
    public int getHeight();

    /**
     * @return width in pixels of the video image as produced by the video
     * decoder
     */
    public int getEncodedWidth();

    /**
     * @return height in pixels of the video image as produced by the video
     * decoder
     */
    public int getEncodedHeight();

    /**
     * @return the number of component planes, for packed formats this will
     * always be one
     */
    public int planeCount();

    /**
     * The plane line stride is the number of bytes between two consecutive
     * lines in the buffer. This number will vary depending on the frame's
     * {@code PixelFormat} and decoder output.
     * @return int array containing the line stride for each plane
     */
    public int[] planeStrides();

    /**
     * The plane line stride is the number of bytes between two consecutive
     * lines in the buffer. This number will vary depending on the frame's
     * {@code PixelFormat} and decoder output.
     * @param planeIndex which plane to get the stride for, valid range is zero
     * to {@link #planeCount() planeCount()} non-inclusive
     * @return the line stride for the specified plane
     */
    public int strideForPlane(int planeIndex);

    /**
     * Converts the video frame to a different video format.
     * @param fmt The new video pixel format, if the same format is specified then
     * the same frame will be returned. If a conversion is unsupported then this
     * will return null. The converted frame must be released when you're done
     * with it by calling {@link #releaseFrame} or it will leak.
     * @return valid MediaFrame in the specified format, or null if it cannot be
     * converted
     */
    public MediaFrame convertToFormat(PixelFormat fmt);

    /**
     * This method will prevent the frame from being deallocated or recycled. It
     * is very important to balance the use of this method by calling releaseFrame
     * when you're done with it otherwise the memory occupied by the frame will
     * never be released which could lead to out of memory conditions.
     */
    public void holdFrame();

    /**
     * When you're finished with a video frame, call this to allow the media
     * subsystem to deallocate or recycle the frame immediately.
     */
    public void releaseFrame();

    // ------------------------------------------------------------------
    // Optional platform-GPU-texture capability (additive).
    //
    // Hardware-accelerated decoders can produce frames that already live
    // on the GPU. When that's the case, a frame can expose a raw native
    // texture handle so the pipeline (Skia) can sample it directly
    // instead of reading pixel bytes out and re-uploading. Frames that
    // don't have a GPU-resident form return KIND_NONE / 0L and callers
    // fall back to the existing plane-buffer path.
    //
    // The handle is an opaque platform pointer:
    //   - KIND_D3D11:    ID3D11Texture2D*   (Windows / MediaFoundation)
    //   - KIND_IOSURFACE: IOSurfaceRef       (macOS / VideoToolbox)
    //   - KIND_DMABUF:   dma-buf fd cast    (Linux / VA-API)
    // ------------------------------------------------------------------

    /** No platform-GPU texture; the plane-buffer path is the only one. */
    public static final int PLATFORM_TEXTURE_KIND_NONE      = 0;
    /** Windows: handle is an {@code ID3D11Texture2D*}. */
    public static final int PLATFORM_TEXTURE_KIND_D3D11     = 1;
    /** macOS: handle is an {@code IOSurfaceRef}. */
    public static final int PLATFORM_TEXTURE_KIND_IOSURFACE = 2;
    /** Linux: handle is a dma-buf file descriptor. */
    public static final int PLATFORM_TEXTURE_KIND_DMABUF    = 3;

    /**
     * @return one of the {@code PLATFORM_TEXTURE_KIND_*} constants
     * describing what {@link #getPlatformTextureHandle()} returns. The
     * default is {@link #PLATFORM_TEXTURE_KIND_NONE}; only HW-decoded
     * frames produced by a platform with GPU interop wired up override
     * this.
     */
    public default int getPlatformTextureKind() {
        return PLATFORM_TEXTURE_KIND_NONE;
    }

    /**
     * Opaque native pointer to a platform GPU texture that contains
     * this frame's pixels, or {@code 0L} when no such texture is
     * available. The pointer's type is determined by
     * {@link #getPlatformTextureKind()}. The pointer is owned by the
     * frame and only valid until {@link #releaseFrame()} runs; callers
     * must finish their interop registration / draw before releasing.
     */
    public default long getPlatformTextureHandle() {
        return 0L;
    }

    /**
     * For texture-array-backed platform handles (e.g. D3D11VA pools its
     * outputs in a {@code Texture2DArray}), the subresource index of
     * this frame's slice within the array. Zero for non-array textures.
     */
    public default int getPlatformTextureSubresource() {
        return 0;
    }

    /**
     * Actual pixel width of the platform texture. May differ from
     * {@link #getWidth()} when the producer downscales high-resolution
     * sources at the GPU level (e.g. 8K source rendered into a 4K
     * texture). Returns 0 when no platform texture is attached.
     */
    public default int getPlatformTextureWidth() {
        return 0;
    }

    /** Actual pixel height of the platform texture. See {@link #getPlatformTextureWidth()}. */
    public default int getPlatformTextureHeight() {
        return 0;
    }

    // ------------------------------------------------------------------
    // YUV colour-space (skia-fx).
    //
    // Demuxers surface the container's colour matrix metadata (BT.601 /
    // BT.709 / BT.2020 / JPEG-full-range) via the source pad caps. The
    // consumer reads it here so {@link com.sun.prism.skia.SkiaMediaTexture}
    // can pick the right YUV→RGB matrix at shader-sample time instead
    // of guessing from resolution. Returns {@link #YUV_COLORSPACE_AUTO}
    // when the demuxer didn't carry any colorimetry — the consumer then
    // falls back to its own heuristic.
    // ------------------------------------------------------------------

    /** No colour-space hint from the demuxer; caller falls back. */
    public static final int YUV_COLORSPACE_AUTO   = -1;
    /** ITU-R BT.601 / SMPTE 170M (SD, NTSC/PAL legacy content). */
    public static final int YUV_COLORSPACE_BT601  = 0;
    /** ITU-R BT.709 (modern HD). */
    public static final int YUV_COLORSPACE_BT709  = 1;
    /** ITU-R BT.2020 (UHD / HDR). */
    public static final int YUV_COLORSPACE_BT2020 = 2;
    /** BT.601 with full-range (JFIF / sRGB-range YUV). */
    public static final int YUV_COLORSPACE_JPEG   = 3;

    /** YUV matrix lifted from the source's container metadata. */
    public default int getYuvColorSpace() {
        return YUV_COLORSPACE_AUTO;
    }

    // ------------------------------------------------------------------
    // Transfer function + primaries + range (skia-fx HDR).
    //
    // Full colour descriptor required to display HDR content (PQ /
    // HLG) correctly. The matrix above only describes how to go from
    // Y'CbCr to non-linear R'G'B'; the transfer function describes
    // how to go from non-linear R'G'B' to linear-light RGB; the
    // primaries describe which RGB gamut the values are in. For SDR
    // content the defaults (sRGB transfer + sRGB primaries) match
    // every well-behaved player and need no metadata. For HDR
    // content all three plus the source's mastering peak luminance
    // are needed to tone-map cleanly into an SDR display.
    // ------------------------------------------------------------------

    public static final int TRANSFER_AUTO    = -1;
    public static final int TRANSFER_SRGB    = 0;   // gamma ~2.2 / sRGB OETF
    public static final int TRANSFER_REC709  = 1;   // BT.709 OETF (HD)
    public static final int TRANSFER_PQ      = 2;   // SMPTE ST 2084 (HDR10)
    public static final int TRANSFER_HLG     = 3;   // BT.2100 HLG
    public static final int TRANSFER_LINEAR  = 4;   // debug / pass-through

    public static final int PRIMARIES_AUTO    = -1;
    public static final int PRIMARIES_SRGB    = 0;   // Rec.709 / sRGB primaries
    public static final int PRIMARIES_REC2020 = 1;   // wide-gamut BT.2020
    public static final int PRIMARIES_DCI_P3  = 2;   // Display P3 / DCI-P3
    public static final int PRIMARIES_REC601  = 3;   // SDTV primaries

    public static final int RANGE_AUTO     = -1;
    public static final int RANGE_LIMITED  = 0;      // 16-235 / 16-240
    public static final int RANGE_FULL     = 1;      // 0-255

    /** Transfer function carried on the source caps, or
     *  {@link #TRANSFER_AUTO} when absent. */
    public default int getColorTransfer() {
        return TRANSFER_AUTO;
    }

    /** RGB gamut primaries carried on the source caps, or
     *  {@link #PRIMARIES_AUTO} when absent. */
    public default int getColorPrimaries() {
        return PRIMARIES_AUTO;
    }

    /** YUV value range (limited / full) from caps, or
     *  {@link #RANGE_AUTO} when absent. */
    public default int getColorRange() {
        return RANGE_AUTO;
    }

    /** Mastering display peak luminance in nits (MaxCLL or container
     *  metadata). Returns 0 when unknown — the renderer assumes
     *  1000 nits for PQ/HLG content lacking explicit metadata. */
    public default float getMasteringPeakNits() {
        return 0.f;
    }
}
