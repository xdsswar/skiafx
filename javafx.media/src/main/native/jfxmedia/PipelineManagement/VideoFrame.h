/*
 * Copyright (c) 2010, 2023, Oracle and/or its affiliates. All rights reserved.
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

#ifndef _VIDEO_FRAME_H_
#define _VIDEO_FRAME_H_

#include <stdlib.h>
#include <stdint.h>

#define MAX_PLANE_COUNT 4

/**
 * class CVideoFrame
 *
 * Class representing a video frame.  Specific engines may have their own derived
 * classes.  For example, CGstVideoFrame.
 */
class CVideoFrame
{
public:
    enum FrameType
    {
        UNKNOWN,
        // NOTE: These MUST be kept in sync with the native types in com.sun.media.jfxmedia.control.VideoFormat
        ARGB = 1,
        BGRA_PRE = 2,
        YCbCr_420p = 100,
        YCbCr_422 = 101,
        YCbCr_422_rev = 102
    };

public:
    CVideoFrame();
    virtual ~CVideoFrame();

    virtual void        Dispose() {}

    double              GetTime();

    unsigned int        GetWidth();
    unsigned int        GetHeight();
    unsigned int        GetEncodedWidth();
    unsigned int        GetEncodedHeight();

    FrameType           GetType();
    bool                HasAlpha();

    unsigned int        GetPlaneCount();
    void                SetPlaneCount(unsigned int count);
    void*               GetDataForPlane(unsigned int planeIndex);
    unsigned long       GetSizeForPlane(unsigned int planeIndex);
    unsigned int        GetStrideForPlane(unsigned int planeIndex);

    virtual CVideoFrame *ConvertToFormat(FrameType type);

    bool                GetFrameDirty() { return m_FrameDirty; }
    void                SetFrameDirty(bool dirty) { m_FrameDirty = dirty; }

    // ----- Optional platform-GPU-texture capability ---------------------
    // Mirrors com.sun.prism.MediaFrame.PLATFORM_TEXTURE_KIND_*. HW decoders
    // (e.g. MediaFoundation D3D11VA) populate these via the meta produced
    // by the producer plugin. Software frames keep the defaults (kind = 0,
    // handle = 0) and the consumer takes the plane-buffer path.
    enum PlatformTextureKind {
        PLATFORM_TEXTURE_KIND_NONE      = 0,
        PLATFORM_TEXTURE_KIND_D3D11     = 1,
        PLATFORM_TEXTURE_KIND_IOSURFACE = 2,
        PLATFORM_TEXTURE_KIND_DMABUF    = 3,
    };
    int                 GetPlatformTextureKind()        { return m_iPlatformTextureKind; }
    void*               GetPlatformTextureHandle()       { return m_pvPlatformTextureHandle; }
    unsigned int        GetPlatformTextureSubresource()  { return m_uiPlatformTextureSubresource; }
    unsigned int        GetPlatformTextureWidth()        { return m_uiPlatformTextureWidth; }
    unsigned int        GetPlatformTextureHeight()       { return m_uiPlatformTextureHeight; }
    void SetPlatformTexture(int kind, void* handle, unsigned int subresource,
                            unsigned int texWidth, unsigned int texHeight) {
        m_iPlatformTextureKind         = kind;
        m_pvPlatformTextureHandle      = handle;
        m_uiPlatformTextureSubresource = subresource;
        m_uiPlatformTextureWidth       = texWidth;
        m_uiPlatformTextureHeight      = texHeight;
    }

    // ----- YUV colour space (skia-fx) -----------------------------------
    // Filled by the demuxer/decoder via the GstCaps "colorimetry" field
    // when present; otherwise stays AUTO and the consumer side falls
    // back to its own resolution-based heuristic. Mirrors the YUV
    // constants in com.sun.prism.skia.impl.NativeBridge — keep in sync.
    enum YuvColorSpace {
        YUV_COLORSPACE_AUTO   = -1, // no metadata; let consumer decide
        YUV_COLORSPACE_BT601  = 0,
        YUV_COLORSPACE_BT709  = 1,
        YUV_COLORSPACE_BT2020 = 2,
        YUV_COLORSPACE_JPEG   = 3,  // BT.601 full range
    };
    int                 GetYuvColorSpace() const { return m_iYuvColorSpace; }
    void                SetYuvColorSpace(int cs) { m_iYuvColorSpace = cs; }

    // ----- Transfer function + primaries + range + peak nits (skia-fx HDR) -
    // Full colour descriptor lifted from caps. AUTO sentinels (= -1)
    // mean "not present in caps"; the consumer combines with the
    // resolution heuristic to decide whether to take the HDR
    // tone-mapping path. Mirrors com.sun.prism.MediaFrame.
    enum ColorTransfer {
        TRANSFER_AUTO    = -1,
        TRANSFER_SRGB    = 0,
        TRANSFER_REC709  = 1,
        TRANSFER_PQ      = 2,
        TRANSFER_HLG     = 3,
        TRANSFER_LINEAR  = 4,
    };
    enum ColorPrimaries {
        PRIMARIES_AUTO    = -1,
        PRIMARIES_SRGB    = 0,
        PRIMARIES_REC2020 = 1,
        PRIMARIES_DCI_P3  = 2,
        PRIMARIES_REC601  = 3,
    };
    enum ColorRange {
        RANGE_AUTO    = -1,
        RANGE_LIMITED = 0,
        RANGE_FULL    = 1,
    };
    int    GetColorTransfer()      const { return m_iColorTransfer; }
    int    GetColorPrimaries()     const { return m_iColorPrimaries; }
    int    GetColorRange()         const { return m_iColorRange; }
    float  GetMasteringPeakNits()  const { return m_fMasteringPeakNits; }
    void   SetColorTransfer(int v)     { m_iColorTransfer  = v; }
    void   SetColorPrimaries(int v)    { m_iColorPrimaries = v; }
    void   SetColorRange(int v)        { m_iColorRange     = v; }
    void   SetMasteringPeakNits(float v) { m_fMasteringPeakNits = v; }

protected:
    unsigned int        m_uiWidth;
    unsigned int        m_uiHeight;
    unsigned int        m_uiEncodedWidth;
    unsigned int        m_uiEncodedHeight;
    FrameType           m_typeFrame;
    bool                m_bHasAlpha;
    double              m_dTime;
    bool                m_FrameDirty;

    // frame data buffers
    void*               m_pvPlaneData[MAX_PLANE_COUNT];
    unsigned long       m_pulPlaneSize[MAX_PLANE_COUNT];
    unsigned int        m_puiPlaneStrides[MAX_PLANE_COUNT];

    // Platform-GPU-texture capability (M3-B zero-copy media). Reset by
    // CVideoFrame::Reset() to the no-texture defaults; set by derived
    // classes (CGstVideoFrame) when a producer-side GstMeta carries a
    // texture handle.
    int                 m_iPlatformTextureKind         = PLATFORM_TEXTURE_KIND_NONE;
    void*               m_pvPlatformTextureHandle      = nullptr;
    unsigned int        m_uiPlatformTextureSubresource = 0;
    unsigned int        m_uiPlatformTextureWidth       = 0;
    unsigned int        m_uiPlatformTextureHeight      = 0;

    // YUV colour space picked up from GstCaps's "colorimetry" field.
    // YUV_COLORSPACE_AUTO when the caps don't carry one — caller falls
    // back to its own heuristic. CGstVideoFrame parses the string form
    // (e.g. "bt709", "1:3:5:4") in SetFrameCaps.
    int                 m_iYuvColorSpace               = YUV_COLORSPACE_AUTO;

    // Transfer function, primaries, range and mastering peak —
    // populated alongside m_iYuvColorSpace from caps.
    int                 m_iColorTransfer               = TRANSFER_AUTO;
    int                 m_iColorPrimaries              = PRIMARIES_AUTO;
    int                 m_iColorRange                  = RANGE_AUTO;
    float               m_fMasteringPeakNits           = 0.f;

    void Reset();
    void SwapPlanes(unsigned int aa, unsigned int bb);

    // CalcSize(), AddSize(), CalcPlanePointer() requires bValid to be set to
    // true initially, if bValid is false these functions do nothing. It is
    // implemented this way, so all these functions can be chain called without
    // checking bValid after each call. bValid will be set to false only if
    // calculation failed and will never be set to true.
    // Multiplies a and b, bValid set to false if integer overflow detected.
    unsigned long CalcSize(unsigned int a, unsigned int b, bool *pbValid);
    // Adds a and b, bValid set to false if integer overflow detected.
    unsigned long AddSize(unsigned long a, unsigned long b, bool *pbValid);
    // Calculates plane pointer (baseAddress + offset) and checks that calculated
    // pointer within buffer. Returns NULL and sets bValid to false if calculated
    // pointer is invalid.
    void* CalcPlanePointer(intptr_t baseAddress, unsigned int offset,
                           unsigned long planeSize, unsigned long baseSize,
                           bool *pbValid);

private:
    unsigned int        m_uiPlaneCount;
};

#endif  //_VIDEO_FRAME_H_
