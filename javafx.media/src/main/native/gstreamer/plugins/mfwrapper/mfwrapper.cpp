/*
 * Copyright (c) 2021, 2025, Oracle and/or its affiliates. All rights reserved.
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

#ifdef HAVE_CONFIG_H
#  include <config.h>
#endif

#include <gst/gst.h>

#include <string.h>
#include <stdio.h>

#include "mfwrapper.h"

#include <mfidl.h>
#include <Wmcodecdsp.h>
#include <d3d11.h>     // Phase-3 M2: D3D11VA path
#include <d3d11_4.h>   // Phase-3 M3-B: ID3D11Multithread, video interfaces
#include <mfobjects.h> // Phase-3 M2: IMFDXGIBuffer

#include <atomic>

#include "fxplugins_common.h"
// Phase-3 M3-B: zero-copy texture meta carried on the GstBuffer.
// Header-only — registration happens lazily through the GStreamer
// process-wide meta registry.
#include "openjfx_media_d3d11_meta.h"

#define PTS_DEBUG 0
#define MEDIA_FORMAT_DEBUG 0

// ---------------------------------------------------------------------------
// Phase-3 M2: D3D11VA decode + GPU texture observation.
//
// Opt-in via OPENJFX_MEDIA_D3D11VA=1. When set, after the decoder is
// activated we create an IMFDXGIDeviceManager backed by an ID3D11Device
// and tell MediaFoundation to use it for hardware decode. The output
// IMFSample's IMFMediaBuffer then exposes an IMFDXGIBuffer interface
// from which we can extract the underlying ID3D11Texture2D.
//
// This is the FOUNDATION for zero-copy video — M3+ will use the
// extracted texture pointer to feed Skia via WGL_NV_DX_interop2
// (already bootstrapped in openjfx_skia_d3d11_interop.{h,cpp}).
//
// When the env var is NOT set, every function here is a no-op and the
// existing CPU path runs unchanged.
// ---------------------------------------------------------------------------

static IMFDXGIDeviceManager* g_pDXGIManager = nullptr;
static ID3D11Device*         g_pD3D11Device = nullptr;

static bool mfwrapper_d3d11va_enabled() {
    // Phase-3 M3 in progress. Opt-in via OPENJFX_MEDIA_D3D11VA=1 so
    // the default install keeps the software-decode path that's
    // currently shipping correctly. When enabled:
    //   - The MFT is handed an IMFDXGIDeviceManager so AV1/HEVC
    //     decode runs on the GPU.
    //   - The decoder's output media type becomes NV12 D3D11
    //     textures instead of CPU-resident IYUV samples.
    //   - mfwrapper_process_output detects the D3D11 path via
    //     IMFDXGIBuffer and routes through a staging copy
    //     (M3-A) or WGL_NV_DX_interop2 (M3-B), bypassing the
    //     existing CPU color-converter chain entirely.
    static int cached = -1;
    if (cached < 0) {
        const char* v = getenv("OPENJFX_MEDIA_D3D11VA");
        cached = (v && *v && *v != '0' && *v != 'f' && *v != 'F') ? 1 : 0;
    }
    return cached != 0;
}

// Set up our D3D11 device + DXGI manager once, hand to the decoder via
// MFT_MESSAGE_SET_D3D_MANAGER. Subsequent decoders share the same
// manager. Returns S_FALSE if the env var isn't set, S_OK on success,
// any other HRESULT on failure (decoder keeps using software path).
static HRESULT mfwrapper_setup_d3d11va(IMFTransform* pDecoder) {
    if (!mfwrapper_d3d11va_enabled()) return S_FALSE;
    if (!pDecoder) return E_POINTER;

    if (!g_pDXGIManager) {
        UINT createFlags = D3D11_CREATE_DEVICE_BGRA_SUPPORT
                         | D3D11_CREATE_DEVICE_VIDEO_SUPPORT;
        D3D_FEATURE_LEVEL gotLevel = D3D_FEATURE_LEVEL_11_0;
        HRESULT hr = D3D11CreateDevice(
            nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
            createFlags, nullptr, 0, D3D11_SDK_VERSION,
            &g_pD3D11Device, &gotLevel, nullptr);
        if (FAILED(hr) || !g_pD3D11Device) {
            g_print("[mfwrapper.d3d11va] D3D11CreateDevice failed 0x%08lx\n",
                    (unsigned long)hr);
            return hr;
        }

        // Phase-3 M3-B: enable D3D11 multithread protection so the
        // mfwrapper thread (VideoProcessorBlt) and the JFX render
        // thread (WGL_NV_DX_interop2 lock/unlock + Skia draw) can
        // safely share the same immediate context. Without this the
        // M3-B zero-copy path produces vertical-stripe / chroma-noise
        // artifacts on the GL alias because writes from one thread
        // race against reads from the other.
        {
            ID3D11Multithread* mt = nullptr;
            if (SUCCEEDED(g_pD3D11Device->QueryInterface(
                    __uuidof(ID3D11Multithread), (void**)&mt)) && mt) {
                mt->SetMultithreadProtected(TRUE);
                mt->Release();
                g_print("[mfwrapper.d3d11va] multithread protection ON.\n");
            } else {
                g_print("[mfwrapper.d3d11va] WARNING: ID3D11Multithread "
                        "unavailable; concurrent producer/consumer access "
                        "may corrupt textures.\n");
            }
        }

        UINT resetToken = 0;
        hr = MFCreateDXGIDeviceManager(&resetToken, &g_pDXGIManager);
        if (FAILED(hr) || !g_pDXGIManager) {
            g_print("[mfwrapper.d3d11va] MFCreateDXGIDeviceManager failed 0x%08lx\n",
                    (unsigned long)hr);
            g_pD3D11Device->Release(); g_pD3D11Device = nullptr;
            return hr;
        }
        hr = g_pDXGIManager->ResetDevice(g_pD3D11Device, resetToken);
        if (FAILED(hr)) {
            g_print("[mfwrapper.d3d11va] DXGI manager ResetDevice failed 0x%08lx\n",
                    (unsigned long)hr);
            g_pDXGIManager->Release(); g_pDXGIManager = nullptr;
            g_pD3D11Device->Release();  g_pD3D11Device  = nullptr;
            return hr;
        }
        g_print("[mfwrapper.d3d11va] D3D11 + DXGI manager ready (feature level 0x%04x).\n",
                (unsigned)gotLevel);
    }

    // Tell THIS decoder to use the D3D manager. Decoder must support
    // hardware accel for this to succeed — software-only decoders
    // (e.g. some HEVC fallbacks) reject the message.
    HRESULT hr = pDecoder->ProcessMessage(
        MFT_MESSAGE_SET_D3D_MANAGER, (ULONG_PTR)g_pDXGIManager);
    if (FAILED(hr)) {
        g_print("[mfwrapper.d3d11va] SET_D3D_MANAGER rejected by decoder 0x%08lx "
                "(software decode path will run instead).\n",
                (unsigned long)hr);
    } else {
        g_print("[mfwrapper.d3d11va] decoder accepted D3D manager — hardware decode.\n");
    }
    return hr;
}

// Diagnostic: log on first delivered sample whether the IMFMediaBuffer
// exposes IMFDXGIBuffer (= GPU-resident). Helps verify SET_D3D_MANAGER
// actually changed the output buffer type. No buffer-flow change.
static void mfwrapper_diag_d3d11_buffer(IMFSample* pSample) {
    static bool s_logged = false;
    if (s_logged || !pSample || !mfwrapper_d3d11va_enabled()) return;

    IMFMediaBuffer* pBuf = nullptr;
    if (FAILED(pSample->GetBufferByIndex(0, &pBuf)) || !pBuf) return;

    IMFDXGIBuffer* pDxgi = nullptr;
    HRESULT hr = pBuf->QueryInterface(IID_PPV_ARGS(&pDxgi));
    if (SUCCEEDED(hr) && pDxgi) {
        ID3D11Texture2D* pTex = nullptr;
        if (SUCCEEDED(pDxgi->GetResource(IID_PPV_ARGS(&pTex))) && pTex) {
            UINT subres = 0;
            pDxgi->GetSubresourceIndex(&subres);
            D3D11_TEXTURE2D_DESC desc = {};
            pTex->GetDesc(&desc);
            g_print("[mfwrapper.d3d11va] FIRST sample is D3D11-BACKED: "
                    "tex=%p subres=%u dxgi_fmt=%u size=%ux%u (zero-copy ready)\n",
                    (void*)pTex, subres, (unsigned)desc.Format,
                    (unsigned)desc.Width, (unsigned)desc.Height);
            pTex->Release();
        } else {
            g_print("[mfwrapper.d3d11va] FIRST sample: IMFDXGIBuffer present "
                    "but GetResource failed.\n");
        }
        pDxgi->Release();
    } else {
        g_print("[mfwrapper.d3d11va] FIRST sample is NOT D3D11-backed "
                "(decoder kept the software path).\n");
    }
    pBuf->Release();
    s_logged = true;
}

// Returns true if `pSample` carries its data on a D3D11 texture
// (IMFDXGIBuffer present). Cheap QI — used per-frame in the
// process_output hot path so the CPU color-converter chain is
// only invoked when the decoder produced a system-memory sample.
static bool mfwrapper_sample_is_d3d11(IMFSample* pSample) {
    if (!pSample) return false;
    IMFMediaBuffer* pBuf = nullptr;
    if (FAILED(pSample->GetBufferByIndex(0, &pBuf)) || !pBuf) return false;
    IMFDXGIBuffer* pDxgi = nullptr;
    HRESULT hr = pBuf->QueryInterface(IID_PPV_ARGS(&pDxgi));
    bool isD3D11 = SUCCEEDED(hr) && pDxgi != nullptr;
    if (pDxgi) pDxgi->Release();
    pBuf->Release();
    return isD3D11;
}

// Per-decoder cache of the staging D3D11 texture used to read pixels
// out of the GPU-resident MFT output. Allocated lazily on first
// D3D11 sample, kept alive across frames, sized to the decoder's
// current output dimensions. Cleaned up alongside g_pD3D11Device.
struct StagingD3D11 {
    ID3D11Texture2D* tex      = nullptr;
    UINT             width    = 0;
    UINT             height   = 0;
    DXGI_FORMAT      format   = DXGI_FORMAT_UNKNOWN;
};
static StagingD3D11 g_staging;

// Allocate (or re-allocate after size/format change) a STAGING
// D3D11 texture matching `desc`. STAGING + CPU_ACCESS_READ lets us
// CopyResource the MFT's render-target output into it and then Map()
// it on the CPU. This is the M3-A path — it gives us GPU decode (the
// expensive AV1/HEVC entropy + transform work) while keeping the
// downstream pipeline on the existing CPU-buffer plumbing. M3-B
// replaces the CopyResource→Map readback with WGL_NV_DX_interop2.
static bool mfwrapper_ensure_staging(const D3D11_TEXTURE2D_DESC& src) {
    if (!g_pD3D11Device) return false;
    if (g_staging.tex
        && g_staging.width  == src.Width
        && g_staging.height == src.Height
        && g_staging.format == src.Format) {
        return true;
    }
    if (g_staging.tex) { g_staging.tex->Release(); g_staging.tex = nullptr; }

    D3D11_TEXTURE2D_DESC desc = {};
    desc.Width              = src.Width;
    desc.Height             = src.Height;
    desc.MipLevels          = 1;
    desc.ArraySize          = 1;
    desc.Format             = src.Format; // typically DXGI_FORMAT_NV12
    desc.SampleDesc.Count   = 1;
    desc.Usage              = D3D11_USAGE_STAGING;
    desc.BindFlags          = 0;
    desc.CPUAccessFlags     = D3D11_CPU_ACCESS_READ;
    desc.MiscFlags          = 0;
    HRESULT hr = g_pD3D11Device->CreateTexture2D(&desc, nullptr, &g_staging.tex);
    if (FAILED(hr) || !g_staging.tex) {
        g_print("[mfwrapper.d3d11va] staging CreateTexture2D failed 0x%08lx (%ux%u fmt=%u)\n",
                (unsigned long)hr, (unsigned)src.Width, (unsigned)src.Height,
                (unsigned)src.Format);
        return false;
    }
    g_staging.width  = src.Width;
    g_staging.height = src.Height;
    g_staging.format = src.Format;
    return true;
}

// ===========================================================================
// Phase-3 M3-B: NV12 → RGBA GPU conversion via ID3D11VideoProcessor.
//
// The MFT decoder outputs NV12 D3D11 textures (DXGI_FORMAT_NV12). The
// WGL_NV_DX_interop2 spec doesn't allow NV12 textures to be shared with
// OpenGL — only standard RGB/RGBA formats. So between "decoder output"
// and "interop view to GL" we run a hardware-accelerated colour-space
// conversion on the GPU: ID3D11VideoProcessor::VideoProcessorBlt.
//
// This still keeps the data wholly on the GPU. The cost is one GPU-side
// blt per frame, which is dramatically cheaper than the CPU staging
// readback + NV12→I420 deinterleave that M3-A pays.
//
// State is per-process and lazily built. Recreated on size/format
// change.
// ===========================================================================
struct VideoProcessorState {
    ID3D11VideoDevice*              videoDevice = nullptr;
    ID3D11VideoContext*             videoContext = nullptr;
    ID3D11VideoProcessorEnumerator* enumerator = nullptr;
    ID3D11VideoProcessor*           processor = nullptr;
    UINT                            srcWidth = 0;
    UINT                            srcHeight = 0;
    UINT                            dstWidth = 0;
    UINT                            dstHeight = 0;
    DXGI_FORMAT                     srcFormat = DXGI_FORMAT_UNKNOWN;
};
static VideoProcessorState g_vp;

// Tear down the VP state. Safe to call multiple times.
static void mfwrapper_release_video_processor() {
    if (g_vp.processor)    { g_vp.processor->Release();    g_vp.processor    = nullptr; }
    if (g_vp.enumerator)   { g_vp.enumerator->Release();   g_vp.enumerator   = nullptr; }
    if (g_vp.videoContext) { g_vp.videoContext->Release(); g_vp.videoContext = nullptr; }
    if (g_vp.videoDevice)  { g_vp.videoDevice->Release();  g_vp.videoDevice  = nullptr; }
    g_vp.srcWidth = g_vp.srcHeight = 0;
    g_vp.dstWidth = g_vp.dstHeight = 0;
    g_vp.srcFormat = DXGI_FORMAT_UNKNOWN;
}

// Lazily build the VideoDevice + VideoContext + Enumerator + Processor
// matching the current source dimensions/format. Returns true on success.
// dstWidth/dstHeight may differ from src to ask the VP to downscale at
// blt time (used for very-high-res sources to keep GPU bandwidth in
// check).
static bool mfwrapper_ensure_video_processor(UINT srcWidth, UINT srcHeight,
                                              UINT dstWidth, UINT dstHeight,
                                              DXGI_FORMAT srcFormat) {
    if (!g_pD3D11Device) return false;

    bool sizeMatch = (g_vp.processor
                      && g_vp.srcWidth  == srcWidth
                      && g_vp.srcHeight == srcHeight
                      && g_vp.dstWidth  == dstWidth
                      && g_vp.dstHeight == dstHeight
                      && g_vp.srcFormat == srcFormat);
    if (sizeMatch) return true;

    // Size or format changed — rebuild from scratch. Keep the device
    // and context, they're size-independent. Releasing
    // processor+enumerator only is enough.
    if (g_vp.processor)  { g_vp.processor->Release();  g_vp.processor  = nullptr; }
    if (g_vp.enumerator) { g_vp.enumerator->Release(); g_vp.enumerator = nullptr; }

    if (!g_vp.videoDevice) {
        HRESULT hr = g_pD3D11Device->QueryInterface(
            __uuidof(ID3D11VideoDevice), (void**)&g_vp.videoDevice);
        if (FAILED(hr) || !g_vp.videoDevice) {
            g_print("[mfwrapper.vp] QI ID3D11VideoDevice failed 0x%08lx\n",
                    (unsigned long)hr);
            return false;
        }
    }
    if (!g_vp.videoContext) {
        ID3D11DeviceContext* immediate = nullptr;
        g_pD3D11Device->GetImmediateContext(&immediate);
        if (!immediate) return false;
        HRESULT hr = immediate->QueryInterface(
            __uuidof(ID3D11VideoContext), (void**)&g_vp.videoContext);
        immediate->Release();
        if (FAILED(hr) || !g_vp.videoContext) {
            g_print("[mfwrapper.vp] QI ID3D11VideoContext failed 0x%08lx\n",
                    (unsigned long)hr);
            return false;
        }
    }

    D3D11_VIDEO_PROCESSOR_CONTENT_DESC desc = {};
    desc.InputFrameFormat              = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
    desc.InputFrameRate.Numerator      = 60; // hint; VP picks its own pacing
    desc.InputFrameRate.Denominator    = 1;
    desc.InputWidth                    = srcWidth;
    desc.InputHeight                   = srcHeight;
    desc.OutputFrameRate.Numerator     = 60;
    desc.OutputFrameRate.Denominator   = 1;
    desc.OutputWidth                   = dstWidth;
    desc.OutputHeight                  = dstHeight;
    desc.Usage                         = D3D11_VIDEO_USAGE_PLAYBACK_NORMAL;

    HRESULT hr = g_vp.videoDevice->CreateVideoProcessorEnumerator(
        &desc, &g_vp.enumerator);
    if (FAILED(hr) || !g_vp.enumerator) {
        g_print("[mfwrapper.vp] CreateVideoProcessorEnumerator failed 0x%08lx\n",
                (unsigned long)hr);
        return false;
    }
    hr = g_vp.videoDevice->CreateVideoProcessor(
        g_vp.enumerator, /*RateConversionIndex*/ 0, &g_vp.processor);
    if (FAILED(hr) || !g_vp.processor) {
        g_print("[mfwrapper.vp] CreateVideoProcessor failed 0x%08lx\n",
                (unsigned long)hr);
        g_vp.enumerator->Release(); g_vp.enumerator = nullptr;
        return false;
    }

    // Pass BT.709 colour space hints. Most consumer 4K AV1 / HEVC is
    // BT.709-limited; the VP driver applies the right matrix on the
    // input read path. (HDR / BT.2020 streams will look slightly off
    // — addressed later when we plumb the source colour space through
    // the meta.)
    D3D11_VIDEO_PROCESSOR_COLOR_SPACE inCs = {};
    inCs.Usage         = 0;                  // playback
    inCs.RGB_Range     = 0;                  // n/a (input is YUV)
    inCs.YCbCr_Matrix  = 1;                  // BT.709
    inCs.YCbCr_xvYCC   = 0;
    inCs.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_16_235;
    g_vp.videoContext->VideoProcessorSetStreamColorSpace(
        g_vp.processor, 0, &inCs);

    D3D11_VIDEO_PROCESSOR_COLOR_SPACE outCs = {};
    outCs.Usage         = 0;
    outCs.RGB_Range     = 0;                 // full range
    outCs.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_0_255;
    g_vp.videoContext->VideoProcessorSetOutputColorSpace(
        g_vp.processor, &outCs);

    g_vp.srcWidth  = srcWidth;
    g_vp.srcHeight = srcHeight;
    g_vp.dstWidth  = dstWidth;
    g_vp.dstHeight = dstHeight;
    g_vp.srcFormat = srcFormat;
    g_print("[mfwrapper.vp] VideoProcessor ready src=%ux%u (fmt=%u) → RGBA %ux%u\n",
            (unsigned)srcWidth, (unsigned)srcHeight, (unsigned)srcFormat,
            (unsigned)dstWidth, (unsigned)dstHeight);
    return true;
}

// ---- RGBA texture pool (Phase-3 M3-B M3-D early) -------------------------
// 4K RGBA = 33 MB, 8K = 132 MB. Allocating a fresh one per frame at
// 24-60 fps means 0.8-8 GB/s of texture allocation, which the D3D11
// driver can pool but the per-frame overhead still shows up as visible
// lag. Recycling a small ring of textures keeps the steady-state
// allocator quiescent.
//
// Lifetime: the pool retains one ref per slot. mfwrapper_pool_acquire
// hands the caller an additional ref via AddRef; the meta destructor
// Releases. When the last meta carrying the texture is freed, the ref
// count drops to the pool-only ref, and the slot is available for the
// next acquire.
//
// Sizing trade-off: more slots = more GPU memory but tolerate deeper
// downstream buffering before the producer recycles a still-in-use
// slot. N=4 covers the JFX media buffer + appsink + render thread
// chain with one frame of headroom.
struct RgbaPool {
    static constexpr int N = 4;
    ID3D11Texture2D* textures[N] = {};
    UINT             width  = 0;
    UINT             height = 0;
    int              next   = 0;
};
static RgbaPool g_rgbaPool;

static ID3D11Texture2D* mfwrapper_create_rgba_target(UINT width, UINT height);

static void mfwrapper_release_rgba_pool() {
    for (int i = 0; i < RgbaPool::N; i++) {
        if (g_rgbaPool.textures[i]) {
            g_rgbaPool.textures[i]->Release();
            g_rgbaPool.textures[i] = nullptr;
        }
    }
    g_rgbaPool.width = g_rgbaPool.height = 0;
    g_rgbaPool.next  = 0;
}

// Probe an ID3D11Texture2D's refcount without modifying it. AddRef
// followed by Release returns the count AFTER the Release call; the
// pool's invariant is that an idle slot has refcount==1 (the pool's
// own reference). Anything higher means a meta is still in flight.
static ULONG mfwrapper_probe_refcount(ID3D11Texture2D* tex) {
    tex->AddRef();
    return tex->Release();
}

// Acquire a pooled RGBA texture matching width/height. AddRef'd once
// for the caller. Walks the pool looking for a slot whose only
// outstanding ref is the pool's — recycling a slot while a meta still
// points at it would race with downstream consumers (a frame in flight
// would suddenly see the next decoded frame's pixels, producing
// "back-and-forward" stutter). When every pooled slot is busy, allocate
// a fresh texture; if a free slot exists in the array, install it
// there, otherwise return it un-pooled (caller still gets a valid
// texture, we just don't recycle it).
static ID3D11Texture2D* mfwrapper_pool_acquire(UINT width, UINT height) {
    if (g_rgbaPool.width != width || g_rgbaPool.height != height) {
        mfwrapper_release_rgba_pool();
        g_rgbaPool.width  = width;
        g_rgbaPool.height = height;
    }

    // Pass 1: round-robin starting from g_rgbaPool.next, take the first
    // slot whose texture is idle.
    for (int i = 0; i < RgbaPool::N; i++) {
        int slot = (g_rgbaPool.next + i) % RgbaPool::N;
        ID3D11Texture2D* tex = g_rgbaPool.textures[slot];
        if (tex != nullptr && mfwrapper_probe_refcount(tex) == 1) {
            g_rgbaPool.next = (slot + 1) % RgbaPool::N;
            tex->AddRef();
            return tex;
        }
    }

    // Pass 2: every populated slot is busy. Allocate a new texture.
    ID3D11Texture2D* tex = mfwrapper_create_rgba_target(width, height);
    if (!tex) return nullptr;

    // If there's an empty pool slot, install it there. Otherwise
    // return it un-pooled — the meta will hold the only ref, and the
    // texture is freed when the meta is.
    for (int i = 0; i < RgbaPool::N; i++) {
        if (g_rgbaPool.textures[i] == nullptr) {
            g_rgbaPool.textures[i] = tex;
            g_rgbaPool.textures[i]->AddRef(); // pool retains its own ref
            return tex; // tex already has caller's ref from CreateTexture2D
        }
    }
    return tex; // un-pooled; tex has caller's ref only
}

// Allocate a fresh RGBA D3D11 texture sized to the source. Caller takes
// ownership (must Release). The texture is bind-flagged for
// RENDER_TARGET (VideoProcessor output) AND SHADER_RESOURCE (the
// downstream WGL interop register).
static ID3D11Texture2D* mfwrapper_create_rgba_target(UINT width, UINT height) {
    if (!g_pD3D11Device) return nullptr;
    D3D11_TEXTURE2D_DESC desc = {};
    desc.Width              = width;
    desc.Height             = height;
    desc.MipLevels          = 1;
    desc.ArraySize          = 1;
    desc.Format             = DXGI_FORMAT_R8G8B8A8_UNORM;
    desc.SampleDesc.Count   = 1;
    desc.Usage              = D3D11_USAGE_DEFAULT;
    desc.BindFlags          = D3D11_BIND_RENDER_TARGET
                            | D3D11_BIND_SHADER_RESOURCE;
    desc.CPUAccessFlags     = 0;
    desc.MiscFlags          = D3D11_RESOURCE_MISC_SHARED; // permits WGL interop
    ID3D11Texture2D* tex = nullptr;
    HRESULT hr = g_pD3D11Device->CreateTexture2D(&desc, nullptr, &tex);
    if (FAILED(hr) || !tex) {
        g_print("[mfwrapper.vp] CreateTexture2D(RGBA, %ux%u) failed 0x%08lx\n",
                (unsigned)width, (unsigned)height, (unsigned long)hr);
        return nullptr;
    }
    return tex;
}

// Perform the NV12 → RGBA conversion on the GPU. Submits a single Blt;
// the work is pipelined and completes by the time the consumer issues
// a GL Lock + draw on the WGL-interop view. Returns true on success.
static bool mfwrapper_convert_to_rgba(ID3D11Texture2D* src, UINT srcSubres,
                                       ID3D11Texture2D* dst,
                                       UINT srcWidth, UINT srcHeight,
                                       UINT dstWidth, UINT dstHeight) {
    if (!g_vp.processor || !g_vp.videoContext) return false;

    ID3D11VideoProcessorInputView* inView = nullptr;
    D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC iv = {};
    iv.FourCC        = 0; // use the texture's native format
    iv.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
    iv.Texture2D.MipSlice    = 0;
    iv.Texture2D.ArraySlice  = srcSubres;
    HRESULT hr = g_vp.videoDevice->CreateVideoProcessorInputView(
        src, g_vp.enumerator, &iv, &inView);
    if (FAILED(hr) || !inView) {
        g_print("[mfwrapper.vp] CreateVideoProcessorInputView failed 0x%08lx\n",
                (unsigned long)hr);
        return false;
    }

    ID3D11VideoProcessorOutputView* outView = nullptr;
    D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC ov = {};
    ov.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
    ov.Texture2D.MipSlice = 0;
    hr = g_vp.videoDevice->CreateVideoProcessorOutputView(
        dst, g_vp.enumerator, &ov, &outView);
    if (FAILED(hr) || !outView) {
        g_print("[mfwrapper.vp] CreateVideoProcessorOutputView failed 0x%08lx\n",
                (unsigned long)hr);
        inView->Release();
        return false;
    }

    D3D11_VIDEO_PROCESSOR_STREAM stream = {};
    stream.Enable             = TRUE;
    stream.OutputIndex        = 0;
    stream.InputFrameOrField  = 0;
    stream.PastFrames         = 0;
    stream.FutureFrames       = 0;
    stream.pInputSurface      = inView;

    RECT srcRect = { 0, 0, (LONG)srcWidth, (LONG)srcHeight };
    RECT dstRect = { 0, 0, (LONG)dstWidth, (LONG)dstHeight };
    g_vp.videoContext->VideoProcessorSetStreamSourceRect(
        g_vp.processor, 0, TRUE, &srcRect);
    g_vp.videoContext->VideoProcessorSetStreamDestRect(
        g_vp.processor, 0, TRUE, &dstRect);

    hr = g_vp.videoContext->VideoProcessorBlt(
        g_vp.processor, outView, /*OutputFrame*/ 0, /*Streams*/ 1, &stream);

    outView->Release();
    inView->Release();

    if (FAILED(hr)) {
        g_print("[mfwrapper.vp] VideoProcessorBlt failed 0x%08lx\n",
                (unsigned long)hr);
        return false;
    }

    // Flush submits the blt to the GPU. We don't spin-wait for it on
    // the producer thread — that turned out to make video decode slow
    // enough to desync from audio, producing audible distortion.
    // Trust wglDXLockObjectsNV on the consumer side to be the
    // synchronisation point.
    ID3D11DeviceContext* immediate = nullptr;
    g_pD3D11Device->GetImmediateContext(&immediate);
    if (immediate) {
        immediate->Flush();
        immediate->Release();
    }
    return true;
}

// Build a meta-only GstBuffer: correct dimensions and timestamps so
// downstream validators are happy, but DON'T do the CPU staging readback
// or the NV12→I420 deinterleave. The pixel data is uninitialised; the
// consumer must take the D3D11 zero-copy path via the meta attached
// afterwards, and never read the plane buffers. Returns NULL on failure;
// caller should fall back to the full CPU readback path in that case.
static GstBuffer* mfwrapper_make_meta_only_gstbuffer(
        GstMFWrapper* decoder, IMFSample* pSample) {
    (void)decoder;
    if (!pSample) return nullptr;

    IMFMediaBuffer* pBuf = nullptr;
    if (FAILED(pSample->GetBufferByIndex(0, &pBuf)) || !pBuf) return nullptr;
    IMFDXGIBuffer* pDxgi = nullptr;
    HRESULT hr = pBuf->QueryInterface(IID_PPV_ARGS(&pDxgi));
    pBuf->Release();
    if (FAILED(hr) || !pDxgi) return nullptr;

    ID3D11Texture2D* pSrcTex = nullptr;
    hr = pDxgi->GetResource(IID_PPV_ARGS(&pSrcTex));
    pDxgi->Release();
    if (FAILED(hr) || !pSrcTex) return nullptr;

    D3D11_TEXTURE2D_DESC srcDesc = {};
    pSrcTex->GetDesc(&srcDesc);
    pSrcTex->Release();

    const int width   = (int)srcDesc.Width;
    const int height  = (int)srcDesc.Height;
    const int chromaW = (width  + 1) / 2;
    const int chromaH = (height + 1) / 2;
    // I420-sized payload so GstVideoFrame's caps + offset math doesn't
    // reject the buffer as too small. Content is left uninitialised on
    // purpose — the meta is the real payload.
    const gsize totalSize =
        (gsize)width * height + 2 * (gsize)chromaW * chromaH;

    GstBuffer* pGstBuf = gst_buffer_new_allocate(NULL, totalSize, NULL);
    if (!pGstBuf) return nullptr;

    LONGLONG ts = 0, dur = 0;
    if (SUCCEEDED(pSample->GetSampleTime(&ts))) {
        GST_BUFFER_TIMESTAMP(pGstBuf) = ts * 100;
    }
    if (SUCCEEDED(pSample->GetSampleDuration(&dur))) {
        GST_BUFFER_DURATION(pGstBuf) = dur * 100;
    }
    return pGstBuf;
}

// Pick the producer-side output dimensions for a given source size.
//
// Strategy (the "video-player trick"): the texture only needs to be as
// big as where it'll actually be displayed. NGMediaView publishes its
// current on-screen rect into jfxmedia.dll via MediaTargetSize; we
// resolve that lazily by GetProcAddress and read it per frame here.
//
// Clamps:
//   - never upscale (cap at source resolution — useless to make the
//     texture bigger than the decoder output)
//   - floor at 144p height (some MediaViews are 1-2 px during layout
//     and we don't want to produce ridiculous 1x1 textures)
//
// OPENJFX_MEDIA_MAX_OUTPUT env var still works as a hard cap on top
// of the view-size logic. Set to "native" to disable any cap at all.
typedef void (*OpenJfxGetTargetSizeFn)(int*, int*);

static OpenJfxGetTargetSizeFn mfwrapper_resolve_target_size_fn() {
    static std::atomic<OpenJfxGetTargetSizeFn> s_cached{nullptr};
    static std::atomic<int> s_tried{0};
    OpenJfxGetTargetSizeFn fn = s_cached.load(std::memory_order_acquire);
    if (fn) return fn;
    if (s_tried.exchange(1) != 0) return s_cached.load();
    // jfxmedia.dll exports openjfx_media_get_target_size from
    // MediaTargetSize.cpp; we live in fxplugins.dll, same process.
    HMODULE mod = GetModuleHandleA("jfxmedia.dll");
    if (!mod) mod = GetModuleHandleA("jfxmedia");
    if (mod) {
        fn = (OpenJfxGetTargetSizeFn)GetProcAddress(mod,
            "openjfx_media_get_target_size");
        if (fn) s_cached.store(fn, std::memory_order_release);
    }
    return fn;
}

static void mfwrapper_pick_output_size(UINT srcW, UINT srcH,
                                        UINT* dstW, UINT* dstH) {
    // Hard cap from env var, mostly for testing. "native" or unset = no cap.
    static int s_envMaxW = -1;
    static int s_envMaxH = -1;
    if (s_envMaxW < 0) {
        s_envMaxW = INT_MAX;
        s_envMaxH = INT_MAX;
        const char* v = getenv("OPENJFX_MEDIA_MAX_OUTPUT");
        if (v && *v && strcmp(v, "native") != 0 && strcmp(v, "0") != 0) {
            int w = 0, h = 0;
            if (sscanf(v, "%dx%d", &w, &h) == 2 && w > 0 && h > 0) {
                s_envMaxW = w; s_envMaxH = h;
            }
        }
    }

    // View-size hint from NGMediaView. Zero (or unresolved fn) means
    // "no hint, fall through to source size limited only by env cap".
    int viewW = 0, viewH = 0;
    OpenJfxGetTargetSizeFn fn = mfwrapper_resolve_target_size_fn();
    if (fn) fn(&viewW, &viewH);

    // Compose the effective cap: min(source, env, view-hint).
    int capW = (int)srcW;
    int capH = (int)srcH;
    if (s_envMaxW < capW) capW = s_envMaxW;
    if (s_envMaxH < capH) capH = s_envMaxH;
    if (viewW > 0 && viewW < capW) capW = viewW;
    if (viewH > 0 && viewH < capH) capH = viewH;

    // Floor at 720p height. 144p was too aggressive — even when a
    // MediaView is small on screen the user can full-screen it
    // mid-playback, and 144p → 1080p upscale looks awful. 720p is a
    // good balance: small enough for cheap GPU bandwidth, big enough
    // that a resize doesn't reveal a soft image until the producer
    // catches up.
    const int MIN_H = 720;
    if (capH < MIN_H) capH = MIN_H;
    int minW_for_aspect = (int)((double)capH * srcW / srcH + 0.5);
    if (capW < minW_for_aspect) capW = minW_for_aspect;

    // Fit inside (capW, capH) while preserving source aspect.
    double sw = (double)capW / srcW;
    double sh = (double)capH / srcH;
    double scale = sw < sh ? sw : sh;
    if (scale > 1.0) scale = 1.0;     // never upscale

    UINT w = (UINT)((srcW * scale) + 0.5);
    UINT h = (UINT)((srcH * scale) + 0.5);

    // Round UP to the nearest 256 pixels per side so small layout
    // jitter (drag-resize) doesn't trigger a VideoProcessor rebuild
    // on every pulse. The texture is always ≥ the on-screen rect, so
    // visual quality stays at "for the size you've chosen" and the
    // VP only rebuilds when the user crosses a bucket boundary.
    constexpr UINT BUCKET = 256;
    UINT roundedW = ((w + BUCKET - 1) / BUCKET) * BUCKET;
    UINT roundedH = ((h + BUCKET - 1) / BUCKET) * BUCKET;
    // But never exceed source dimensions.
    if (roundedW > srcW) roundedW = srcW;
    if (roundedH > srcH) roundedH = srcH;
    // Even — VP prefers it.
    roundedW &= ~1u;
    roundedH &= ~1u;
    if (roundedW < 2) roundedW = 2;
    if (roundedH < 2) roundedH = 2;

    *dstW = roundedW;
    *dstH = roundedH;
}

// Attach an OpenJfxMediaD3d11Meta to `pGstBuf` carrying a freshly-built
// RGBA texture that mirrors `pSrcSample`. Returns true on success; on
// failure the GstBuffer is left untouched (consumer falls back to the
// existing plane-buffer path).
static bool mfwrapper_attach_d3d11_meta(GstBuffer* pGstBuf, IMFSample* pSrcSample) {
    if (!pGstBuf || !pSrcSample || !g_pD3D11Device) return false;

    // 1. Pull the source NV12 texture + subres out of the MFT sample.
    IMFMediaBuffer* pBuf = nullptr;
    if (FAILED(pSrcSample->GetBufferByIndex(0, &pBuf)) || !pBuf) return false;
    IMFDXGIBuffer* pDxgi = nullptr;
    HRESULT hr = pBuf->QueryInterface(IID_PPV_ARGS(&pDxgi));
    pBuf->Release();
    if (FAILED(hr) || !pDxgi) return false;

    ID3D11Texture2D* pSrcTex = nullptr;
    UINT srcSub = 0;
    hr = pDxgi->GetResource(IID_PPV_ARGS(&pSrcTex));
    pDxgi->GetSubresourceIndex(&srcSub);
    pDxgi->Release();
    if (FAILED(hr) || !pSrcTex) return false;

    D3D11_TEXTURE2D_DESC srcDesc = {};
    pSrcTex->GetDesc(&srcDesc);

    // Pick downscaled output dimensions when source exceeds the cap.
    UINT dstW = 0, dstH = 0;
    mfwrapper_pick_output_size(srcDesc.Width, srcDesc.Height, &dstW, &dstH);

    // 2. Lazily set up the VP, acquire a pooled RGBA target, run the blt.
    bool ok = mfwrapper_ensure_video_processor(srcDesc.Width, srcDesc.Height,
                                                dstW, dstH, srcDesc.Format);
    ID3D11Texture2D* pRgba = ok ? mfwrapper_pool_acquire(dstW, dstH) : nullptr;
    if (pRgba) {
        ok = mfwrapper_convert_to_rgba(pSrcTex, srcSub, pRgba,
                                        srcDesc.Width, srcDesc.Height,
                                        dstW, dstH);
    } else {
        ok = false;
    }
    pSrcTex->Release();

    if (!ok) {
        if (pRgba) pRgba->Release();
        return false;
    }

    // 3. Attach the meta. openjfx_media_d3d11_meta_add AddRefs the
    // texture; the meta destructor Releases it when the GstBuffer is
    // disposed. We then Release our local pool-acquired ref — the pool
    // itself still retains its own ref, plus the meta holds one, so
    // the texture survives until BOTH the meta is freed and the next
    // pool cycle.
    // Meta carries the OUTPUT dimensions (what the texture actually
    // contains after the VP downscale), so the consumer samples the
    // right rect.
    OpenJfxMediaD3d11Meta* meta = openjfx_media_d3d11_meta_add(
        pGstBuf, pRgba,
        /*subresource*/ 0,           // standalone tex, single subresource
        dstW, dstH);
    pRgba->Release();
    if (!meta) {
        g_print("[mfwrapper.vp] meta_add failed; consumer will fall back.\n");
        return false;
    }

    static bool firstTimeLogged = false;
    if (!firstTimeLogged) {
        firstTimeLogged = true;
        g_print("[mfwrapper.vp] FIRST zero-copy meta attached src=%ux%u → %ux%u RGBA.\n",
                (unsigned)srcDesc.Width, (unsigned)srcDesc.Height,
                (unsigned)dstW, (unsigned)dstH);
    }
    return true;
}

// 3 buffers is enough for rendering. During testing 2 buffers is actually
// enough, but in some case 3 were allocated.
#define MIN_BUFFERS 3
// 6 buffers max, just in case.
#define MAX_BUFFERS 6

enum
{
    PROP_0,
    PROP_CODEC_ID,
    PROP_IS_SUPPORTED,
};

enum
{
    PO_DELIVERED,
    PO_NEED_MORE_DATA,
    PO_FLUSHING,
    PO_FAILED,
};

GST_DEBUG_CATEGORY_STATIC(gst_mfwrapper_debug);
#define GST_CAT_DEFAULT gst_mfwrapper_debug

// The input capabilities
static GstStaticPadTemplate sink_factory =
GST_STATIC_PAD_TEMPLATE("sink",
    GST_PAD_SINK,
    GST_PAD_ALWAYS,
    GST_STATIC_CAPS(
        // Codecs Windows Media Foundation can decode (when the
        // corresponding OS extension is installed):
        //   H.265 / HEVC — needs "HEVC Video Extensions"  (MS Store)
        //   AV1          — needs "AV1 Video Extension"   (MS Store, free)
        // H.264 stays with dshowwrapper.
        "video/x-h265; "
        "video/x-av1"
    ));

// The output capabilities
static GstStaticPadTemplate src_factory =
GST_STATIC_PAD_TEMPLATE("src",
    GST_PAD_SRC,
    GST_PAD_ALWAYS,
    GST_STATIC_CAPS(
        // YV12
        "video/x-raw-yuv, "
        "format=(string)YV12"
    ));

// Forward declarations
static void gst_mfwrapper_dispose(GObject* object);
static void gst_mfwrapper_set_property(GObject *object, guint property_id, const GValue *value, GParamSpec *pspec);
static void gst_mfwrapper_get_property(GObject *object, guint property_id, GValue *value, GParamSpec *pspec);

static GstFlowReturn mfwrapper_chain(GstPad* pad, GstObject *parent, GstBuffer* buf);

static gboolean mfwrapper_sink_event(GstPad* pad, GstObject *parent, GstEvent* event);
static gboolean mfwrapper_sink_set_caps(GstPad * pad, GstObject *parent, GstCaps * caps);
static gboolean mfwrapper_activate(GstPad* pad, GstObject *parent);
static gboolean mfwrapper_activatemode(GstPad *pad, GstObject *parent, GstPadMode mode, gboolean active);

static HRESULT mfwrapper_load_decoder_caps(GstMFWrapper *decoder, GstCaps *caps);
static HRESULT mfwrapper_load_decoder_media_types(GstMFWrapper *decoder, GUID majorType, GUID subType);

static gboolean mfwrapper_is_decoder_by_codec_id_supported(GstMFWrapper *decoder, gint codec_id);

template <class T> void SafeRelease(T **ppT)
{
    if (*ppT)
    {
        (*ppT)->Release();
        *ppT = NULL;
    }
}

/***********************************************************************************
* Substitution for
* G_DEFINE_TYPE (GstMFWrapper, gst_mfwrapper, GstElement, GST_TYPE_ELEMENT);
***********************************************************************************/
#define gst_mfwrapper_parent_class parent_class
static void gst_mfwrapper_init(GstMFWrapper      *self);
static void gst_mfwrapper_class_init(GstMFWrapperClass *klass);
static gpointer gst_mfwrapper_parent_class = NULL;
static void     gst_mfwrapper_class_intern_init(gpointer klass)
{
    gst_mfwrapper_parent_class = g_type_class_peek_parent(klass);
    gst_mfwrapper_class_init((GstMFWrapperClass*)klass);
}

GType gst_mfwrapper_get_type(void)
{
    static volatile gsize gonce_data = 0;
    // INLINE - g_once_init_enter()
    if (g_once_init_enter(&gonce_data))
    {
        GType _type;
        _type = g_type_register_static_simple(GST_TYPE_ELEMENT,
            g_intern_static_string("GstMFWrapper"),
            sizeof(GstMFWrapperClass),
            (GClassInitFunc)gst_mfwrapper_class_intern_init,
            sizeof(GstMFWrapper),
            (GInstanceInitFunc)gst_mfwrapper_init,
            (GTypeFlags)0);
        g_once_init_leave(&gonce_data, (gsize)_type);
    }
    return (GType)gonce_data;
}

// Initialize mfwrapper's class.
static void gst_mfwrapper_class_init(GstMFWrapperClass *klass)
{
    GstElementClass *element_class = (GstElementClass*)klass;
    GObjectClass *gobject_class = (GObjectClass*)klass;

    gst_element_class_set_metadata(element_class,
        "MFWrapper",
        "Codec/Decoder/Audio/Video",
        "Media Foundation Wrapper",
        "Oracle Corporation");

    gst_element_class_add_pad_template(element_class,
        gst_static_pad_template_get(&src_factory));
    gst_element_class_add_pad_template(element_class,
        gst_static_pad_template_get(&sink_factory));

    gobject_class->dispose = gst_mfwrapper_dispose;
    gobject_class->set_property = gst_mfwrapper_set_property;
    gobject_class->get_property = gst_mfwrapper_get_property;

    g_object_class_install_property(gobject_class, PROP_CODEC_ID,
        g_param_spec_int("codec-id", "Codec ID", "Codec ID", -1, G_MAXINT, 0,
        (GParamFlags)(G_PARAM_READWRITE | G_PARAM_CONSTRUCT | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_IS_SUPPORTED,
        g_param_spec_boolean("is-supported", "Is supported", "Is codec ID supported", FALSE,
        (GParamFlags)(G_PARAM_READWRITE | G_PARAM_CONSTRUCT | G_PARAM_STATIC_STRINGS)));
}

// Initialize the new element
// Instantiate pads and add them to element
// Set pad calback functions
// Initialize instance structure
static void gst_mfwrapper_init(GstMFWrapper *decoder)
{
    // Input
    decoder->sinkpad = gst_pad_new_from_static_template(&sink_factory, "sink");
    gst_element_add_pad(GST_ELEMENT(decoder), decoder->sinkpad);
    gst_pad_set_chain_function(decoder->sinkpad, mfwrapper_chain);
    gst_pad_set_event_function(decoder->sinkpad, mfwrapper_sink_event);
    gst_pad_set_activate_function(decoder->sinkpad, mfwrapper_activate);
    gst_pad_set_activatemode_function(decoder->sinkpad, mfwrapper_activatemode);

    // Output
    decoder->srcpad = gst_pad_new_from_static_template(&src_factory, "src");
    gst_element_add_pad(GST_ELEMENT(decoder), decoder->srcpad);

    decoder->is_flushing = FALSE;
    decoder->is_eos_received = FALSE;
    decoder->is_eos = FALSE;
    decoder->is_decoder_initialized = FALSE;
    decoder->is_decoder_error = FALSE;
    decoder->is_force_discontinuity = FALSE;
    decoder->is_force_output_discontinuity = FALSE;

    // Initialize Media Foundation
    bool bCallCoUninitialize = true;

    if (FAILED(CoInitializeEx(NULL, COINIT_MULTITHREADED | COINIT_DISABLE_OLE1DDE)))
        bCallCoUninitialize = false;

    decoder->hr_mfstartup = MFStartup(MF_VERSION, MFSTARTUP_LITE);

    if (bCallCoUninitialize)
        CoUninitialize();

    decoder->pDecoder = NULL;
    decoder->pDecoderOutput = NULL;
    decoder->pDecoderBuffer = NULL;

    for (int i = 0; i < MAX_COLOR_CONVERT; i++)
    {
        decoder->pColorConvert[i] = NULL;
        decoder->pColorConvertOutput[i] = NULL;
        decoder->pColorConvertBuffer[i] = NULL;
    }

    decoder->pool = NULL;

    decoder->header = NULL;
    decoder->header_size = 0;
    decoder->is_send_header = FALSE;

    decoder->input_colorimetry = NULL;

    decoder->width = 1920;
    decoder->height = 1080;
    decoder->framerate_num = 2997;
    decoder->framerate_den = 100;

    decoder->defaultStride = 0;
    decoder->pixel_num = 0;
    decoder->pixel_den = 0;

    decoder->is_set_caps = TRUE;
}

static void gst_mfwrapper_dispose(GObject* object)
{
    GstMFWrapper *decoder = GST_MFWRAPPER(object);

    if (decoder->header != NULL)
    {
        delete[] decoder->header;
        decoder->header = NULL;
        decoder->header_size = 0;
    }

    SafeRelease(&decoder->pDecoderOutput);
    // No need to free pDecoderBuffer, it will be released when
    // pDecoderOutput is released.
    decoder->pDecoderBuffer = NULL;
    SafeRelease(&decoder->pDecoder);

    for (int i = 0; i < MAX_COLOR_CONVERT; i++)
    {
        SafeRelease(&decoder->pColorConvertOutput[i]);
        // No need to free pColorConvertBuffer, it will be released when
        // pColorConvertOutput is released.
        decoder->pColorConvertBuffer[i] = NULL;
        SafeRelease(&decoder->pColorConvert[i]);
    }

    if (decoder->pool)
    {
        if (gst_buffer_pool_is_active(decoder->pool))
            gst_buffer_pool_set_active(decoder->pool, FALSE);

        gst_object_unref(decoder->pool);
        decoder->pool = NULL;
    }

    if (decoder->hr_mfstartup == S_OK)
        MFShutdown();

    if (decoder->input_colorimetry != NULL)
    {
        g_free(decoder->input_colorimetry);
        decoder->input_colorimetry = NULL;
    }

    G_OBJECT_CLASS(parent_class)->dispose(object);
}

static void gst_mfwrapper_set_property(GObject *object, guint property_id, const GValue *value, GParamSpec *pspec)
{
    GstMFWrapper *decoder = GST_MFWRAPPER(object);
    switch (property_id)
    {
    case PROP_CODEC_ID:
        decoder->codec_id = g_value_get_int(value);
        break;
    default:
        break;
    }
}

static void gst_mfwrapper_get_property(GObject *object, guint property_id, GValue *value, GParamSpec *pspec)
{
    GstMFWrapper *decoder = GST_MFWRAPPER(object);
    gboolean is_supported = FALSE;
    switch (property_id)
    {
    case PROP_IS_SUPPORTED:
        is_supported = mfwrapper_is_decoder_by_codec_id_supported(decoder, decoder->codec_id);
        g_value_set_boolean(value, is_supported);
        break;
    default:
        break;
    }
}

static gboolean mfwrapper_is_decoder_by_codec_id_supported(GstMFWrapper *decoder, gint codec_id)
{
    HRESULT hr = S_FALSE;

    switch (codec_id)
    {
    case JFX_CODEC_ID_H265:
    {
        // Dummy caps to load H.265 decoder. Requires the "HEVC Video
        // Extensions" from Microsoft Store (Microsoft holds the
        // MPEG-LA HEVC patent license).
        GstCaps *caps = gst_caps_new_simple("video/x-h265",
            "width", G_TYPE_INT, 1920,
            "height", G_TYPE_INT, 1080,
            NULL);
        hr = mfwrapper_load_decoder_caps(decoder, caps);
        gst_caps_unref(caps);
        break;
    }
    case JFX_CODEC_ID_AV1:
    {
        // Dummy caps to load AV1 decoder. Requires the free "AV1
        // Video Extension" from Microsoft Store. AV1 is royalty-free
        // per AOM patent covenant; the extension is just Microsoft's
        // implementation.
        GstCaps *caps = gst_caps_new_simple("video/x-av1",
            "width", G_TYPE_INT, 1920,
            "height", G_TYPE_INT, 1080,
            NULL);
        hr = mfwrapper_load_decoder_caps(decoder, caps);
        gst_caps_unref(caps);
        break;
    }
    }

    if (hr == S_OK)
        return TRUE;
    else
        return FALSE;
}

static HRESULT mfwrapper_create_sample(IMFSample **ppSample, DWORD dwSize, CMFGSTBuffer **ppMFGSTBuffer)
{
    if (ppSample == NULL || dwSize == 0 || ppMFGSTBuffer == NULL)
        return E_INVALIDARG;

    HRESULT hr = MFCreateSample(ppSample);
    if (SUCCEEDED(hr))
    {
        (*ppMFGSTBuffer) = new (nothrow) CMFGSTBuffer(dwSize);
        if ((*ppMFGSTBuffer) == NULL)
            return E_OUTOFMEMORY;

        IMFMediaBuffer *pBuffer = NULL;
        hr = (*ppMFGSTBuffer)->QueryInterface(IID_IMFMediaBuffer, (void **)&pBuffer);
        if (FAILED(hr) || pBuffer == NULL)
        {
            delete (*ppMFGSTBuffer);
            return E_NOINTERFACE;
        }

        (*ppSample)->AddBuffer(pBuffer);
        SafeRelease(&pBuffer);
    }

    return S_OK;
}

static void mfwrapper_set_src_caps(GstMFWrapper *decoder)
{
    GstCaps *srcCaps = NULL;
    HRESULT hr = S_OK;
    MFT_OUTPUT_STREAM_INFO outputStreamInfo;

    // IYUV/YV12 plane layout — round chroma up so odd widths/heights
    // (3190x2160, 1281x720, etc.) describe the same byte layout that
    // MF actually delivers. Plain `width / 2` floor-divides and
    // mis-sizes the U/V planes by one chroma sample whenever the
    // dimension is odd, which then shifts every later offset.
    // MF's actual Y-plane row stride may also be larger than width
    // (alignment padding); cache_defaultStride preserves whatever
    // mfwrapper_set_decoder_output_type already pulled from
    // MF_MT_DEFAULT_STRIDE so we don't shadow the decoder's truth.
    gint y_stride = (decoder->defaultStride > 0)
                  ? (gint)decoder->defaultStride
                  : (gint)decoder->width;
    gint chroma_w = (gint)((decoder->width  + 1) / 2);
    gint chroma_h = (gint)((decoder->height + 1) / 2);
    gint c_stride = chroma_w;
    gint y_size   = y_stride * (gint)decoder->height;
    gint u_size   = c_stride * chroma_h;
    gint offset_u = y_size;
    gint offset_v = y_size + u_size;

    // The MF AV1 / HEVC decoder writes its output as I420 layout:
    // Y first, then Cb (U) at offset W*H, then Cr (V) at offset
    // W*H + W*H/4. That's exactly what set_decoder_output_type
    // configures (MFVideoFormat_IYUV is identical to I420).
    //
    // We MUST tag the GstCaps as I420 — NOT YV12 — because
    // GstVideoFrame.cpp:184 only flips `m_bIsI420 = true` for
    // caps named "I420", and that flag is what tells the FX
    // MediaFrame layer to treat plane 1 as Cb (U) and plane 2 as
    // Cr (V). With the old "YV12" tag, FX swallowed the buffer in
    // YV12 plane order (Y, V, U); the BGRA fallback corrected for
    // that internally, but the YUV-native upload path read plane 1
    // as Cb when it was actually Cr — Skia's GPU shader then
    // produced the classic R↔B-shifted output (blue skin, cyan
    // foliage, amber backlight).
    GstCaps *padCaps = gst_pad_get_current_caps(decoder->srcpad);
    if (padCaps == NULL)
    {
        srcCaps = gst_caps_new_simple("video/x-raw-yuv",
            "format", G_TYPE_STRING, "I420",
            "framerate", GST_TYPE_FRACTION, decoder->framerate_num, decoder->framerate_den,
            "width", G_TYPE_INT, decoder->width,
            "height", G_TYPE_INT, decoder->height,
            "offset-y", G_TYPE_INT, 0,
            "offset-u", G_TYPE_INT, offset_u,
            "offset-v", G_TYPE_INT, offset_v,
            "stride-y", G_TYPE_INT, y_stride,
            "stride-u", G_TYPE_INT, c_stride,
            "stride-v", G_TYPE_INT, c_stride,
            NULL);
    }
    else
    {
        srcCaps = gst_caps_copy(padCaps);
        gst_caps_unref(padCaps);
        if (srcCaps == NULL)
            return;

        // Force the format field too — the cached pad caps from
        // an earlier negotiation may still have a stale "YV12".
        gst_caps_set_simple(srcCaps,
            "format", G_TYPE_STRING, "I420",
            "width", G_TYPE_INT, decoder->width,
            "height", G_TYPE_INT, decoder->height,
            "offset-y", G_TYPE_INT, 0,
            "offset-u", G_TYPE_INT, offset_u,
            "offset-v", G_TYPE_INT, offset_v,
            "stride-y", G_TYPE_INT, y_stride,
            "stride-u", G_TYPE_INT, c_stride,
            "stride-v", G_TYPE_INT, c_stride,
            NULL);
    }

    // Forward the upstream colorimetry through the decoder — MFT
    // produces samples in the same colour space the input declared,
    // and without this the Skia consumer falls back to its
    // resolution heuristic. With this, AV1 / H.265 in MP4 / MKV
    // arrive at the consumer with their real BT.709 / BT.2020 / PQ
    // / HLG metadata intact.
    if (decoder->input_colorimetry != NULL)
    {
        gst_caps_set_simple(srcCaps,
            "colorimetry", G_TYPE_STRING, decoder->input_colorimetry,
            NULL);
    }

    GstEvent *caps_event = gst_event_new_caps(srcCaps);
    if (caps_event)
    {
        gst_pad_push_event(decoder->srcpad, caps_event);
        decoder->is_force_output_discontinuity = TRUE;
    }
    gst_caps_unref(srcCaps);

    // Allocate or update decoder output buffer
    SafeRelease(&decoder->pDecoderOutput);

    if (SUCCEEDED(hr))
        hr = decoder->pDecoder->GetOutputStreamInfo(0, &outputStreamInfo);

    if (SUCCEEDED(hr) && outputStreamInfo.cbSize > 0)
    {
        // Always pre-allocate the output sample, even when the MFT
        // advertises MFT_OUTPUT_STREAM_PROVIDES_SAMPLES /
        // CAN_PROVIDE_SAMPLES. The MS Store AV1 decoder sets that
        // flag, and skipping allocation here used to leave
        // decoder->pDecoderOutput NULL — which then made every
        // subsequent mfwrapper_deliver_sample call fail with
        // GST_FLOW_ERROR before any frame ever reached the
        // downstream appsink (and therefore before MediaPlayer
        // could transition to READY). Pre-allocating our own
        // CMFGSTBuffer-backed IMFSample lets ProcessOutput write
        // straight into a GstBuffer-mapped memory region the rest
        // of the pipeline already understands; HEVC, which doesn't
        // advertise that flag, kept working through the original
        // code path so this strictly broadens what we accept.
        hr = mfwrapper_create_sample(&decoder->pDecoderOutput,
                outputStreamInfo.cbSize, &decoder->pDecoderBuffer);
    }
}

#if MEDIA_FORMAT_DEBUG
static void mfwrapper_print_media_format(GUID format)
{
    if (IsEqualGUID(format, MFVideoFormat_I420))
        g_print("JFXMEDIA MFVideoFormat_I420\n");
    else if (IsEqualGUID(format, MFVideoFormat_IYUV))
        g_print("JFXMEDIA MFVideoFormat_IYUV\n");
    else if (IsEqualGUID(format, MFVideoFormat_NV12))
        g_print("JFXMEDIA MFVideoFormat_NV12\n");
    else if (IsEqualGUID(format, MFVideoFormat_YUY2))
        g_print("JFXMEDIA MFVideoFormat_YUY2\n");
    else if (IsEqualGUID(format, MFVideoFormat_YV12))
        g_print("JFXMEDIA MFVideoFormat_YV12\n");
    else if (IsEqualGUID(format, MFVideoFormat_P010))
        g_print("JFXMEDIA MFVideoFormat_P010\n");
    else if (IsEqualGUID(format, MFVideoFormat_ARGB32))
        g_print("JFXMEDIA MFVideoFormat_ARGB32\n");
    else if (IsEqualGUID(format, MFVideoFormat_RGB32))
        g_print("JFXMEDIA MFVideoFormat_RGB32\n");
    else if (IsEqualGUID(format, MFVideoFormat_A2R10G10B10))
        g_print("JFXMEDIA MFVideoFormat_A2R10G10B10\n");
    else if (IsEqualGUID(format, MFVideoFormat_A16B16G16R16F))
        g_print("JFXMEDIA MFVideoFormat_A16B16G16R16F\n");
    else if (IsEqualGUID(format, MFVideoFormat_RGB24))
        g_print("JFXMEDIA MFVideoFormat_RGB24\n");
    else if (IsEqualGUID(format, MFVideoFormat_AYUV))
        g_print("JFXMEDIA MFVideoFormat_AYUV\n");
    else
        g_print("JFXMEDIA Unknown MF Format\n");
}

static void mfwrapper_print_output_media_formats(IMFTransform *pMFTrasnform, const char *name)
{
    HRESULT hr = S_OK;
    GUID subType;
    DWORD dwTypeIndex = 0;
    IMFMediaType *pType = NULL;

    g_print("JFXMEDIA MF Transform (%s) output formats:\n", name);
    if (pMFTrasnform == NULL)
    {
        g_print("JFXMEDIA Error: pMFTrasnform == NULL\n");
        return;
    }

    do
    {
        hr = pMFTrasnform->GetOutputAvailableType(0, dwTypeIndex, &pType);
        if (SUCCEEDED(hr))
        {
            hr = pType->GetGUID(MF_MT_SUBTYPE, &subType);
            mfwrapper_print_media_format(subType);
            SafeRelease(&pType);
            dwTypeIndex++;
        }
    } while (hr != MF_E_NO_MORE_TYPES && SUCCEEDED(hr));
}
#endif // MEDIA_FORMAT_DEBUG

static void mfwrapper_nalu_to_start_code(BYTE *pbBuffer, gsize size)
{
    gint leftSize = size;

    if (pbBuffer == NULL || size < 4)
        return;

    do
    {
        guint naluLen = ((guint)*(guint8*)pbBuffer) << 24;
        naluLen |= ((guint)*(guint8*)(pbBuffer + 1)) << 16;
        naluLen |= ((guint)*(guint8*)(pbBuffer + 2)) << 8;
        naluLen |= ((guint)*(guint8*)(pbBuffer + 3));

        if (naluLen <= 1) // Start code or something wrong
            return;

        pbBuffer[0] = 0x00;
        pbBuffer[1] = 0x00;
        pbBuffer[2] = 0x00;
        pbBuffer[3] = 0x01;

        leftSize -= (naluLen + 4);
        pbBuffer += (naluLen + 4);

    } while (leftSize > 0);
}

static gboolean mfwrapper_process_input(GstMFWrapper *decoder, GstBuffer *buf)
{
    IMFSample *pSample = NULL;
    IMFMediaBuffer *pBuffer = NULL;
    DWORD dwBufferSize = 0;
    BYTE *pbBuffer = NULL;
    GstMapInfo info;
    gboolean unmap_buf = FALSE;
    gboolean unlock_buf = FALSE;

    if (!decoder->pDecoder)
        return FALSE;

    HRESULT hr = MFCreateSample(&pSample);

    if (SUCCEEDED(hr) && decoder->is_force_discontinuity)
    {
        hr = pSample->SetUINT32(MFSampleExtension_Discontinuity, TRUE);
        decoder->is_force_discontinuity = FALSE;
    }

    if (SUCCEEDED(hr) && GST_BUFFER_PTS_IS_VALID(buf))
        hr = pSample->SetSampleTime(GST_BUFFER_PTS(buf) / 100);

    if (SUCCEEDED(hr) && GST_BUFFER_DURATION_IS_VALID(buf))
        hr = pSample->SetSampleDuration(GST_BUFFER_DURATION(buf) / 100);

    if (SUCCEEDED(hr) && gst_buffer_map(buf, &info, GST_MAP_READ))
        unmap_buf = TRUE;
    else
        hr = E_FAIL;

    if (SUCCEEDED(hr) && decoder->is_send_header &&
            decoder->header != NULL && decoder->header_size > 0)
        dwBufferSize = (DWORD)decoder->header_size + (DWORD)info.size;
    else if (SUCCEEDED(hr))
        dwBufferSize = (DWORD)info.size;

    if (SUCCEEDED(hr))
        hr = MFCreateMemoryBuffer(dwBufferSize, &pBuffer);

    if (SUCCEEDED(hr))
        hr = pBuffer->SetCurrentLength(dwBufferSize);

    if (SUCCEEDED(hr))
        hr = pBuffer->Lock(&pbBuffer, NULL, NULL);

    if (SUCCEEDED(hr))
        unlock_buf = TRUE;

    if (SUCCEEDED(hr) && decoder->is_send_header &&
            decoder->header != NULL && decoder->header_size > 0)
    {
        decoder->is_send_header = FALSE;
        if (dwBufferSize >= decoder->header_size)
        {
            memcpy_s(pbBuffer, dwBufferSize, decoder->header, decoder->header_size);
            pbBuffer += decoder->header_size;
            dwBufferSize -= decoder->header_size;

            if (dwBufferSize >= info.size)
            {
                memcpy_s(pbBuffer, dwBufferSize, info.data, info.size);
                // HEVC frames arrive length-prefixed (MP4 NALU
                // format) — convert to Annex B start codes for MF.
                // AV1 frames are already OBUs and need no rewrite.
                if (decoder->codec_id != JFX_CODEC_ID_AV1)
                    mfwrapper_nalu_to_start_code(pbBuffer, info.size);
            }
            else
            {
                hr = E_FAIL;
            }
        }
        else
        {
            hr = E_FAIL;
        }
    }
    else if (SUCCEEDED(hr))
    {
        memcpy_s(pbBuffer, dwBufferSize, info.data, info.size);
        if (decoder->codec_id != JFX_CODEC_ID_AV1)
            mfwrapper_nalu_to_start_code(pbBuffer, info.size);
    }

    if (unlock_buf)
        hr = pBuffer->Unlock();

    if (unmap_buf)
        gst_buffer_unmap(buf, &info);

    if (SUCCEEDED(hr))
        hr = pSample->AddBuffer(pBuffer);

    if (SUCCEEDED(hr))
        hr = decoder->pDecoder->ProcessInput(0, pSample, 0);

    gst_buffer_unref(buf);

    SafeRelease(&pBuffer);
    SafeRelease(&pSample);

    if (SUCCEEDED(hr))
        return TRUE;
    else
        return FALSE;
}

static HRESULT mfwrapper_configure_colorconvert_input_type(GstMFWrapper *decoder,
                                                           IMFTransform *pInput,
                                                           IMFTransform *pColorConvert)
{
    HRESULT hr = S_OK;
    IMFMediaType *pInputOutputType = NULL;
    IMFMediaType *pColorConvertInputType = NULL;
    GUID subType;

    if (decoder == NULL || pInput == NULL || pColorConvert == NULL)
        return E_POINTER;

    // Get decoder output type. It should be already configured.
    if (SUCCEEDED(hr))
        hr = pInput->GetOutputCurrentType(0, &pInputOutputType);

    if (SUCCEEDED(hr))
        hr = pInputOutputType->GetGUID(MF_MT_SUBTYPE, &subType);

#if MEDIA_FORMAT_DEBUG
    g_print("JFXMEDIA mfwrapper_configure_colorconvert_input_type() Input output type:\n");
    mfwrapper_print_media_format(subType);
#endif // MEDIA_FORMAT_DEBUG

    // Set input type on color converter. Create new one with all information we know.
    // Setting one from decoder will not work since it does not contain all information.
    if (SUCCEEDED(hr))
        hr = MFCreateMediaType(&pColorConvertInputType);

    if (SUCCEEDED(hr))
        hr = pColorConvertInputType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);

    if (SUCCEEDED(hr))
        hr = pColorConvertInputType->SetGUID(MF_MT_SUBTYPE, subType);

    if (SUCCEEDED(hr))
    {
        hr = MFSetAttributeSize(pColorConvertInputType, MF_MT_FRAME_SIZE,
                decoder->width, decoder->height);
    }

    if (SUCCEEDED(hr))
    {
        hr = MFSetAttributeRatio(pColorConvertInputType, MF_MT_FRAME_RATE,
                decoder->framerate_num, decoder->framerate_den);
    }

    if (SUCCEEDED(hr) && decoder->defaultStride != 0)
    {
        hr = pColorConvertInputType->SetUINT32(MF_MT_DEFAULT_STRIDE,
                (UINT32)decoder->defaultStride);
    }

    if (SUCCEEDED(hr) && decoder->pixel_num != 0 && decoder->pixel_den != 0)
    {
        hr = MFSetAttributeRatio(pColorConvertInputType, MF_MT_PIXEL_ASPECT_RATIO,
                (UINT32)decoder->pixel_num, (UINT32)decoder->pixel_den);
    }

    if (SUCCEEDED(hr))
        hr = pColorConvert->SetInputType(0, pColorConvertInputType, 0);

    SafeRelease(&pColorConvertInputType);
    SafeRelease(&pInputOutputType);

    return hr;
}

static HRESULT mfwrapper_set_colorconvert_output_type(GstMFWrapper *decoder,
                                                      IMFMediaType *pOutputType,
                                                      IMFTransform *pColorConvert)
{
    HRESULT hr = S_OK;
    GUID subType;
    IMFMediaType *pNewOutputType = NULL;
    IMFMediaType *pCurrentOutputType = NULL;
    GUID currentSubType;
    guint width = 0;
    guint height = 0;

    if (decoder == NULL || pOutputType == NULL || pColorConvert == NULL)
    {
        return E_POINTER;
    }

    // We only need subtype
    hr = pOutputType->GetGUID(MF_MT_SUBTYPE, &subType);

    // For color convert we need to re-create output type with more information
    if (SUCCEEDED(hr))
        hr = MFCreateMediaType(&pNewOutputType);

    if (SUCCEEDED(hr))
        hr = pNewOutputType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);

    if (SUCCEEDED(hr))
        hr = pNewOutputType->SetGUID(MF_MT_SUBTYPE, subType);

    if (SUCCEEDED(hr))
        hr = MFSetAttributeSize(pNewOutputType, MF_MT_FRAME_SIZE, decoder->width, decoder->height);

    if (SUCCEEDED(hr))
        hr = MFSetAttributeRatio(pNewOutputType, MF_MT_FRAME_RATE, decoder->framerate_num, decoder->framerate_den);

    if (SUCCEEDED(hr))
    {
#if MEDIA_FORMAT_DEBUG
        g_print("JFXMEDIA Setting color convert output type:\n");
        mfwrapper_print_media_format(subType);
#endif // MEDIA_FORMAT_DEBUG
        hr = pColorConvert->SetOutputType(0, pNewOutputType, 0);
        SafeRelease(&pNewOutputType);
        if (hr != S_OK) // S_OK means format was set
        {
#if MEDIA_FORMAT_DEBUG
            g_print("JFXMEDIA Failed setting color convert output type (hr=0x%X):\n", hr);
            mfwrapper_print_media_format(subType);
#endif // MEDIA_FORMAT_DEBUG
            return E_FAIL;
        }

        // Re-check format just in case
        hr = pColorConvert->GetOutputCurrentType(0, &pCurrentOutputType);
        if (SUCCEEDED(hr))
            hr = pCurrentOutputType->GetGUID(MF_MT_SUBTYPE, &currentSubType);

        SafeRelease(&pCurrentOutputType);

        if (SUCCEEDED(hr) && !IsEqualGUID(subType, currentSubType))
        {
#if MEDIA_FORMAT_DEBUG
            g_print("JFXMEDIA Error: unexpected sub type vs current sub type\n");
            mfwrapper_print_media_format(subType);
            mfwrapper_print_media_format(currentSubType);
#endif // MEDIA_FORMAT_DEBUG
            return E_FAIL;
        }
    }

    return hr;
}

static HRESULT mfwrapper_configure_colorconvert_output_type(GstMFWrapper *decoder,
                                                            IMFTransform *pColorConvert,
                                                            GUID *outputType)
{
    HRESULT hr = S_OK;
    IMFMediaType *pOutputType = NULL;
    GUID subType;
    DWORD dwTypeIndex = 0;

    // We need following types:
    // MFVideoFormat_IYUV (prefered)
    // MFVideoFormat_NV12 (requires second converter)
    IMFMediaType *pOutputTypeIYUV = NULL;
    IMFMediaType *pOutputTypeNV12 = NULL;

    if (decoder == NULL || pColorConvert == NULL || outputType == NULL)
        return E_POINTER;

#if MEDIA_FORMAT_DEBUG
    mfwrapper_print_output_media_formats(pColorConvert, "Color Converter");
#endif // MEDIA_FORMAT_DEBUG

    do
    {
        hr = pColorConvert->GetOutputAvailableType(0, dwTypeIndex, &pOutputType);
        if (hr == MF_E_NO_MORE_TYPES)
            break;

        if (SUCCEEDED(hr))
            hr = pOutputType->GetGUID(MF_MT_SUBTYPE, &subType);

        if (SUCCEEDED(hr) && IsEqualGUID(subType, MFVideoFormat_IYUV))
            pOutputTypeIYUV = pOutputType;
        else if (SUCCEEDED(hr) && IsEqualGUID(subType, MFVideoFormat_NV12))
            pOutputTypeNV12 = pOutputType;
        else if (SUCCEEDED(hr))
            SafeRelease(&pOutputType);

        pOutputType = NULL;

        dwTypeIndex++;
    } while (hr != MF_E_NO_MORE_TYPES && SUCCEEDED(hr));

    // Set hr to error code, it might be SUCCEEDED after loop
    // and pOutputTypeIYUV can be NULL, so we will try other
    // formats as well.
    hr = E_FAIL;

    // We should cache as much supported formats as possible.
    // Try them in order we prefered.
    if (pOutputTypeIYUV)
    {
        hr = mfwrapper_set_colorconvert_output_type(decoder, pOutputTypeIYUV,
                                                    pColorConvert);
        if (SUCCEEDED(hr))
            (*outputType) = MFVideoFormat_IYUV;
    }

    // Try only if previous one failed
    if (hr != S_OK && pOutputTypeNV12)
    {
        hr = mfwrapper_set_colorconvert_output_type(decoder, pOutputTypeNV12,
                                                    pColorConvert);
        if (SUCCEEDED(hr))
            (*outputType) = MFVideoFormat_NV12;
    }

    SafeRelease(&pOutputTypeIYUV);
    SafeRelease(&pOutputTypeNV12);

    return hr;
}

// pInput - Input transform for which mfwrapper_init_colorconvert() will create
// color convert with best possible output type.
// ppColorConvert - Receives pointer to color convert.
// ppColorConvertOutput - Receives pointer to color convert output buffer.
// outputType - Will be set to color convert output type (IYUV or NV12).
// ppMFGSTBuffer - Receives CMFGSTBuffer object related to ppColorConvertOutput.
static HRESULT mfwrapper_init_colorconvert(GstMFWrapper *decoder,
                                           IMFTransform *pInput,
                                           IMFTransform **ppColorConvert,
                                           IMFSample **ppColorConvertOutput,
                                           GUID *outputType,
                                           CMFGSTBuffer **ppMFGSTBuffer)
{
    DWORD dwStatus = 0;
    MFT_OUTPUT_STREAM_INFO outputStreamInfo;

    if (pInput == NULL || ppColorConvert == NULL ||
        ppColorConvertOutput == NULL || outputType == NULL ||
        ppMFGSTBuffer == NULL)
    {
        return E_POINTER;
    }

    HRESULT hr = CoCreateInstance(CLSID_VideoProcessorMFT, NULL, CLSCTX_ALL, IID_PPV_ARGS(ppColorConvert));
    if (SUCCEEDED(hr))
        hr = mfwrapper_configure_colorconvert_input_type(decoder, pInput, (*ppColorConvert));

    if (SUCCEEDED(hr))
        hr = mfwrapper_configure_colorconvert_output_type(decoder, (*ppColorConvert), outputType);

    if (SUCCEEDED(hr))
        hr = (*ppColorConvert)->GetOutputStreamInfo(0, &outputStreamInfo);

    if (SUCCEEDED(hr))
    {
        if (!((outputStreamInfo.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES) ||
              (outputStreamInfo.dwFlags & MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES)))
        {
            hr = mfwrapper_create_sample(ppColorConvertOutput,
                outputStreamInfo.cbSize, ppMFGSTBuffer);
        }
    }

    if (SUCCEEDED(hr))
        hr = (*ppColorConvert)->GetInputStatus(0, &dwStatus);

    if (FAILED(hr) || dwStatus != MFT_INPUT_STATUS_ACCEPT_DATA) {
        return hr;
    }

    if (SUCCEEDED(hr))
        hr = (*ppColorConvert)->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, NULL);

    if (SUCCEEDED(hr))
        hr = (*ppColorConvert)->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, NULL);

    if (SUCCEEDED(hr))
        hr = (*ppColorConvert)->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, NULL);

    return hr;
}

static void mfwrapper_get_gst_buffer_src(GstBuffer **ppBuffer, long lSize,
        sCallbackData *pCallbackData)
{
    GstFlowReturn ret = GST_FLOW_OK;
    GstMFWrapper *decoder = (GstMFWrapper*)pCallbackData->pCallbackData;
    if (decoder == NULL || decoder->pool == NULL)
    {
        (*ppBuffer) = NULL;
        return;
    }

    ret = gst_buffer_pool_acquire_buffer(decoder->pool, ppBuffer, NULL);
    if (ret == GST_FLOW_OK)
        return;

    // Pool might fail in case of flushing, but MF still might want buffer.
    // It is better to give buffer to MF just in case, then fail
    // CMFGSTBuffer::Lock().
    (*ppBuffer) = gst_buffer_new_allocate(NULL, lSize, NULL);
}

// Gets max length of configured media buffer we using for final rendering from
// decoder or color convert.
static HRESULT mfwrapper_get_media_buffer_max_length(GstMFWrapper *decoder, DWORD *pdwMaxLength)
{
    HRESULT hr = S_OK;

    if (decoder == NULL || pdwMaxLength == NULL)
        return E_INVALIDARG;

    CMFGSTBuffer *pBuffer = NULL;
    if (decoder->pColorConvertOutput[COLOR_CONVERT_IYUV] != NULL)
        pBuffer = decoder->pColorConvertBuffer[COLOR_CONVERT_IYUV];
    else if (decoder->pDecoderOutput != NULL)
        pBuffer = decoder->pDecoderBuffer;

    if (pBuffer == NULL)
        return E_FAIL;

    return pBuffer->GetMaxLength(pdwMaxLength);
}

static HRESULT mfwrapper_configure_media_buffer(GstMFWrapper *decoder)
{
    HRESULT hr = S_OK;

    CMFGSTBuffer *pBuffer = NULL;
    if (decoder->pColorConvertOutput[COLOR_CONVERT_IYUV] != NULL)
        pBuffer = decoder->pColorConvertBuffer[COLOR_CONVERT_IYUV];
    else if (decoder->pDecoderOutput != NULL)
        pBuffer = decoder->pDecoderBuffer;

    if (pBuffer == NULL)
        return E_FAIL;

    sCallbackData callbackData;
    ZeroMemory(&callbackData, sizeof(sCallbackData));
    callbackData.pCallbackData = (void*)decoder;
    hr = pBuffer->SetCallbackData(&callbackData);
    if (FAILED(hr))
        return hr;

    hr = pBuffer->SetGetGstBufferCallback(&mfwrapper_get_gst_buffer_src);
    if (FAILED(hr))
        return hr;

    return hr;
}

static HRESULT mfwrapper_configure_buffer_pool(GstMFWrapper *decoder)
{
    // Free old pool. We might be called during format change.
    if (decoder->pool)
    {
        if (gst_buffer_pool_is_active(decoder->pool))
            gst_buffer_pool_set_active(decoder->pool, FALSE);

        gst_object_unref(decoder->pool);
        decoder->pool = NULL;
    }

    DWORD dwMaxLength = 0;
    HRESULT hr = mfwrapper_get_media_buffer_max_length(decoder, &dwMaxLength);
    // Pool only supports upto unsigned int, but buffer can be unsigned long.
    if (FAILED(hr) || dwMaxLength > G_MAXUINT)
        return E_FAIL;

    decoder->pool = gst_buffer_pool_new();
    if (decoder->pool == NULL)
        return E_FAIL;

    GstStructure *config = gst_buffer_pool_get_config(decoder->pool);
    if (config == NULL)
        return E_FAIL;

    // By now we should caps configured on pad, so just use it.
    GstCaps *caps = gst_pad_get_current_caps(decoder->srcpad);
    if (caps == NULL)
        return E_FAIL;

    gst_buffer_pool_config_set_params(config, caps,
            (guint)dwMaxLength, MIN_BUFFERS, MAX_BUFFERS);
    gst_caps_unref(caps); // INLINE - gst_caps_unref()

    if (!gst_buffer_pool_set_config(decoder->pool, config))
        return E_FAIL;

    gst_buffer_pool_set_active(decoder->pool, TRUE);

    return S_OK;
}

static HRESULT mfwrapper_set_decoder_output_type(GstMFWrapper *decoder,
                                                 IMFMediaType *pOutputType,
                                                 gboolean bInitColorConverter)
{
    HRESULT hr = S_OK;
    GUID subType;
    IMFMediaType *pCurrentOutputType = NULL;
    GUID currentSubType;
    guint width = 0;
    guint height = 0;

    if (decoder == NULL && pOutputType == NULL)
        return E_POINTER;

    hr = pOutputType->GetGUID(MF_MT_SUBTYPE, &subType);
    if (SUCCEEDED(hr))
    {
#if MEDIA_FORMAT_DEBUG
        g_print("JFXMEDIA Setting decoder output type:\n");
        mfwrapper_print_media_format(subType);
#endif // MEDIA_FORMAT_DEBUG
        hr = decoder->pDecoder->SetOutputType(0, pOutputType, 0);
        if (hr != S_OK) // S_OK means format was set
        {
#if MEDIA_FORMAT_DEBUG
            g_print("JFXMEDIA Failed setting decoder output type (hr=0x%X):\n", hr);
            mfwrapper_print_media_format(subType);
#endif // MEDIA_FORMAT_DEBUG
            return E_FAIL;
        }

        // Re-check format just in case
        hr = decoder->pDecoder->GetOutputCurrentType(0, &pCurrentOutputType);
        if (SUCCEEDED(hr))
            hr = pCurrentOutputType->GetGUID(MF_MT_SUBTYPE, &currentSubType);

        SafeRelease(&pCurrentOutputType);

        if (SUCCEEDED(hr) && !IsEqualGUID(subType, currentSubType))
        {
#if MEDIA_FORMAT_DEBUG
            g_print("JFXMEDIA Error: unexpected sub type vs current sub type\n");
            mfwrapper_print_media_format(subType);
            mfwrapper_print_media_format(currentSubType);
#endif // MEDIA_FORMAT_DEBUG
            return E_FAIL;
        }
    }

    if (SUCCEEDED(hr))
    {
        // Update width and height from configured decoder output type.
        // We need to do this before color convert, so we pass correct
        // resolution to color convert and caps.
        hr = MFGetAttributeSize(pOutputType, MF_MT_FRAME_SIZE, &width, &height);
        if (SUCCEEDED(hr) && (decoder->width != width || decoder->height != height))
        {
            decoder->width = width;
            decoder->height = height;

            decoder->is_set_caps = TRUE; // Only set caps if resolution changed, so
            // we do not trigger it during decoder reload.
        }
        hr = S_OK; // Ok if we do not have above attribute

        // Cache stride and pixel aspect ratio. Ok if we do not have it.
        UINT32 unDefaultStride = 0;
        hr = pOutputType->GetUINT32(MF_MT_DEFAULT_STRIDE, &unDefaultStride);
        if (SUCCEEDED(hr))
        {
            decoder->defaultStride = unDefaultStride;
        }
        hr = S_OK;

        UINT32 unNumerator = 0;
        UINT32 unDenominator = 0;
        hr = MFGetAttributeRatio(pOutputType, MF_MT_PIXEL_ASPECT_RATIO, &unNumerator, &unDenominator);
        if (SUCCEEDED(hr))
        {
            decoder->pixel_num = unNumerator;
            decoder->pixel_den = unDenominator;
        }
        hr = S_OK;
    }

    // Init color converter if needed. SKIPPED entirely under D3D11VA:
    // the VideoProcessorMFT chain is software / CPU-buffer based and
    // chokes (access violation) when fed a D3D11 input sample. With
    // D3D11VA on, mfwrapper_process_output detects the D3D11 sample
    // and routes it through mfwrapper_readback_d3d11_to_new_gstbuffer
    // which handles the NV12→I420 conversion in C++ directly.
    if (SUCCEEDED(hr) && bInitColorConverter && decoder->is_set_caps
        && !mfwrapper_d3d11va_enabled())
    {
        IMFTransform *pColorConvert = NULL;
        IMFSample *pColorConvertOutput = NULL;
        GUID outputType;
        CMFGSTBuffer *pMFGSTBuffer = NULL;

        // Free old ones if any
        for (int i = 0; i < MAX_COLOR_CONVERT; i++)
        {
            SafeRelease(&decoder->pColorConvertOutput[i]);
            decoder->pColorConvertBuffer[i] = NULL;
            SafeRelease(&decoder->pColorConvert[i]);
        }

        hr = mfwrapper_init_colorconvert(decoder, decoder->pDecoder,
                    &pColorConvert, &pColorConvertOutput, &outputType, &pMFGSTBuffer);
        if (SUCCEEDED(hr) && IsEqualGUID(outputType, MFVideoFormat_NV12)) {
            decoder->pColorConvert[COLOR_CONVERT_NV12] = pColorConvert;
            decoder->pColorConvertOutput[COLOR_CONVERT_NV12] = pColorConvertOutput;
            decoder->pColorConvertBuffer[COLOR_CONVERT_NV12] = pMFGSTBuffer;

            // We got NV12, so init second one for NV12->IYUV
            hr = mfwrapper_init_colorconvert(decoder,
                    decoder->pColorConvert[COLOR_CONVERT_NV12], &pColorConvert,
                    &pColorConvertOutput, &outputType, &pMFGSTBuffer);
        }

        if (SUCCEEDED(hr) && IsEqualGUID(outputType, MFVideoFormat_IYUV)) {
            decoder->pColorConvert[COLOR_CONVERT_IYUV] = pColorConvert;
            decoder->pColorConvertOutput[COLOR_CONVERT_IYUV] = pColorConvertOutput;
            decoder->pColorConvertBuffer[COLOR_CONVERT_IYUV] = pMFGSTBuffer;
        }
    }

    // Update caps on src pad in case if something changed
    if (SUCCEEDED(hr) && decoder->is_set_caps)
    {
        mfwrapper_set_src_caps(decoder);

        // By now we should have output sample created. Figure out which one we
        // will use to deliver frames and update media buffer in this sample to
        // use GStreamer memory directly.
        if (SUCCEEDED(hr))
            hr = mfwrapper_configure_media_buffer(decoder);

        // Configure GStreamer buffer pool to avoid memory allocation for each
        // buffer.
        if (SUCCEEDED(hr))
            hr = mfwrapper_configure_buffer_pool(decoder);

        decoder->is_set_caps = FALSE;
    }

    return hr;
}

static HRESULT mfwrapper_configure_decoder_output_type(GstMFWrapper *decoder)
{
    HRESULT hr = S_OK;
    IMFMediaType *pOutputType = NULL;
    GUID subType;
    DWORD dwTypeIndex = 0;

    // Note: See JDK-8336277. Looks like "H.265 / HEVC Video Decoder" has
    // a bug and if we succesfully called SetOutputType() on given media
    // type it does not mean that decoder actually switch format. So, to
    // consider format set succesfully we need to check return value of
    // SetOutputType() and re-read back format via GetOutputCurrentType().

    // We need to support following formats:
    // MFVideoFormat_IYUV - Our prefered format, since we can render it directly.
    // MFVideoFormat_NV12 - Decoder prefered, but requires color converter.
    // MFVideoFormat_P010 - Decoder prefered, but requires color converter (10-bit video).
    IMFMediaType *pOutputTypeIYUV = NULL;
    IMFMediaType *pOutputTypeNV12 = NULL;
    IMFMediaType *pOutputTypeP010 = NULL;

#if MEDIA_FORMAT_DEBUG
    mfwrapper_print_output_media_formats(decoder->pDecoder, "Video Decoder");
#endif // MEDIA_FORMAT_DEBUG

    do
    {
        hr = decoder->pDecoder->GetOutputAvailableType(0, dwTypeIndex, &pOutputType);
        if (hr == MF_E_NO_MORE_TYPES)
            break;

        if (SUCCEEDED(hr))
            hr = pOutputType->GetGUID(MF_MT_SUBTYPE, &subType);

        if (SUCCEEDED(hr) && IsEqualGUID(subType, MFVideoFormat_IYUV))
            pOutputTypeIYUV = pOutputType;
        else if (SUCCEEDED(hr) && IsEqualGUID(subType, MFVideoFormat_NV12))
            pOutputTypeNV12 = pOutputType;
        else if (SUCCEEDED(hr) && IsEqualGUID(subType, MFVideoFormat_P010))
            pOutputTypeP010 = pOutputType;
        else if (SUCCEEDED(hr))
            SafeRelease(&pOutputType);

        pOutputType = NULL;

        dwTypeIndex++;
    } while (hr != MF_E_NO_MORE_TYPES && SUCCEEDED(hr));

    // Set hr to error code, it might be SUCCEEDED after loop
    // and pOutputTypeIYUV can be NULL, so we will try other
    // formats as well.
    hr = E_FAIL;

    // We should cache as much supported formats as possible.
    // Try them in order we prefered.
    if (pOutputTypeIYUV)
        hr = mfwrapper_set_decoder_output_type(decoder, pOutputTypeIYUV, false);

    // Try only if previous one failed
    if (hr != S_OK && pOutputTypeNV12)
        hr = mfwrapper_set_decoder_output_type(decoder, pOutputTypeNV12, true);

    if (hr != S_OK && pOutputTypeP010)
        hr = mfwrapper_set_decoder_output_type(decoder, pOutputTypeP010, true);

    SafeRelease(&pOutputTypeIYUV);
    SafeRelease(&pOutputTypeNV12);
    SafeRelease(&pOutputTypeP010);

    return hr;
}

static gboolean mfwrapper_convert_output_helper(GstMFWrapper *decoder,
                                                IMFSample *pInputSample,
                                                IMFTransform *pColorConvert,
                                                IMFSample *pColorConvertOutput)
{
    DWORD dwFlags = 0;
    DWORD dwStatus = 0;
    MFT_OUTPUT_DATA_BUFFER outputDataBuffer;
    outputDataBuffer.dwStreamID = 0;
    outputDataBuffer.pSample = pColorConvertOutput;
    outputDataBuffer.dwStatus = 0;
    outputDataBuffer.pEvents = NULL;
    IMFMediaType *pOutputType = NULL;

    if (decoder == NULL || pColorConvert == NULL || pColorConvertOutput == NULL)
        return FALSE;

    // Extra call to unblock color converter, since it expects ProcessOutput to be called
    // until it returns MF_E_TRANSFORM_NEED_MORE_INPUT
    HRESULT hr = pColorConvert->ProcessOutput(0, 1, &outputDataBuffer, &dwStatus);

    hr = pColorConvert->ProcessInput(0, pInputSample, 0);

    if (SUCCEEDED(hr))
        hr = pColorConvert->GetOutputStatus(&dwFlags);

    if (SUCCEEDED(hr) && dwFlags != MFT_OUTPUT_STATUS_SAMPLE_READY)
        return FALSE;

    hr = pColorConvert->ProcessOutput(0, 1, &outputDataBuffer, &dwStatus);
    SafeRelease(&outputDataBuffer.pEvents);
    if (hr == MF_E_TRANSFORM_STREAM_CHANGE)
    {
        if (outputDataBuffer.dwStatus == MFT_OUTPUT_DATA_BUFFER_FORMAT_CHANGE)
        {
            hr = pColorConvert->GetOutputAvailableType(0, 0, &pOutputType);

            if (SUCCEEDED(hr))
                hr = pOutputType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_IYUV);

            if (SUCCEEDED(hr))
                hr = pColorConvert->SetOutputType(0, pOutputType, 0);

            SafeRelease(&pOutputType);
        }
    }
    else if (SUCCEEDED(hr))
    {
        if (outputDataBuffer.dwStatus == 0)
        {
            return TRUE;
        }
    }

    return FALSE;
}

static gboolean mfwrapper_convert_output(GstMFWrapper *decoder)
{
    gboolean result = TRUE;
    // Sample to convert. Always start from decoder
    IMFSample *pInputSample = decoder->pDecoderOutput;

    if (decoder == NULL || pInputSample == NULL)
        return FALSE;

    if (decoder->pColorConvert[COLOR_CONVERT_NV12] &&
        decoder->pColorConvertOutput[COLOR_CONVERT_NV12])
    {
        result = mfwrapper_convert_output_helper(decoder,
                                                 pInputSample,
                                                 decoder->pColorConvert[COLOR_CONVERT_NV12],
                                                 decoder->pColorConvertOutput[COLOR_CONVERT_NV12]);
        pInputSample = decoder->pColorConvertOutput[COLOR_CONVERT_NV12]; // Keep converting
    }

    if (result && pInputSample != NULL &&
        decoder->pColorConvert[COLOR_CONVERT_IYUV] &&
        decoder->pColorConvertOutput[COLOR_CONVERT_IYUV])
    {
        result = mfwrapper_convert_output_helper(decoder,
                                                 pInputSample,
                                                 decoder->pColorConvert[COLOR_CONVERT_IYUV],
                                                 decoder->pColorConvertOutput[COLOR_CONVERT_IYUV]);

    }

    return result;
}

// M3-A readback: convert a D3D11-backed MFT output sample (NV12) into
// the decoder's pre-allocated CMFGSTBuffer/GstBuffer as I420 so the
// downstream CPU path consumes it as if no D3D11VA had ever been used.
//
// Path:
//   D3D11 NV12 tex (GPU)        — what the MFT just produced
//   ──► CopySubresourceRegion ──► STAGING D3D11 tex (CPU-readable)
//   ──► Map() ──► NV12 system memory
//   ──► deinterleave UV ──► I420 bytes written into the GstBuffer
//   ──► gst_pad_push downstream
//
// This file is Windows-only by definition (mfwrapper wraps Media
// Foundation), but the *abstraction* it implements — "hardware decode
// produced a GPU texture, hand it through the rest of the pipeline" —
// must stay platform-neutral on the Java side. M3-B+ will introduce a
// `MediaFrame` capability flag (or a sibling MediaFrame subclass)
// that exposes an opaque platform texture handle:
//
//   Windows: ID3D11Texture2D* + subresource index (this file)
//   macOS:   CVPixelBufferRef / IOSurfaceRef     (avfwrapper TBD)
//   Linux:   VASurfaceID or DMA-BUF fd           (vaapi/v4l2 TBD)
//
// The Skia bridge then has per-platform interop code
// (WGL_NV_DX_interop2, CGLTexImageIOSurface2D,
// EGL_EXT_image_dma_buf_import) that builds an SkImage from that
// handle. Decoder selection (mfwrapper / avfwrapper / vaapi) already
// happens per-platform in GstPipelineFactory, so each native side
// independently feeds the same Java-side renderer contract.
//
// True zero-copy (M3-B) replaces CopySubresourceRegion+Map with
// wglDXRegisterObjectNV / wglDXLockObjectsNV so Skia samples the
// D3D11 texture directly from GL via WGL_NV_DX_interop2.
// Builds a fresh, correctly-sized GstBuffer in I420 layout from the
// D3D11 sample and returns it. Caller owns the buffer ref and pushes
// it downstream. Returns NULL on any failure.
//
// We DON'T reuse `decoder->pDecoderBuffer` here: with D3D11VA,
// MFT_OUTPUT_STREAM_PROVIDES_SAMPLES means the decoder's
// GetOutputStreamInfo.cbSize is a sentinel (often 0) describing
// "MFT provides its own samples, you don't allocate", not the actual
// pixel data size. The pre-allocated GstBuffer would be too small and
// the readback memcpy would overrun it (the access violation that was
// crashing the previous iteration of this milestone). Allocating
// here, sized exactly to W*H*3/2 for I420, sidesteps that entirely.
static GstBuffer* mfwrapper_readback_d3d11_to_new_gstbuffer(
        GstMFWrapper* decoder, IMFSample* pSample) {
    (void)decoder;
    if (!pSample || !g_pD3D11Device) return nullptr;

    // 1. Pull the source ID3D11Texture2D + subresource index out of the
    //    MFT's IMFSample. Every MF buffer that lives on a D3D11 texture
    //    exposes IMFDXGIBuffer; we verified that on the caller side via
    //    mfwrapper_sample_is_d3d11.
    IMFMediaBuffer* pBuf = nullptr;
    if (FAILED(pSample->GetBufferByIndex(0, &pBuf)) || !pBuf) return nullptr;
    IMFDXGIBuffer* pDxgi = nullptr;
    HRESULT hr = pBuf->QueryInterface(IID_PPV_ARGS(&pDxgi));
    pBuf->Release();
    if (FAILED(hr) || !pDxgi) return nullptr;

    ID3D11Texture2D* pSrcTex = nullptr;
    UINT             srcSub  = 0;
    hr = pDxgi->GetResource(IID_PPV_ARGS(&pSrcTex));
    pDxgi->GetSubresourceIndex(&srcSub);
    pDxgi->Release();
    if (FAILED(hr) || !pSrcTex) return nullptr;

    D3D11_TEXTURE2D_DESC srcDesc = {};
    pSrcTex->GetDesc(&srcDesc);

    // 2. Lazily (re-)allocate the staging texture to match the source's
    //    dimensions + format. ensure_staging is idempotent when size is
    //    unchanged, so this is one-time on stream start + on every
    //    resolution change.
    if (!mfwrapper_ensure_staging(srcDesc)) {
        pSrcTex->Release();
        return nullptr;
    }

    // 3. Copy the subresource into our standalone staging texture.
    //    D3D11VA decoders often pool outputs as a single Texture2DArray
    //    to avoid per-frame allocations, so subres index is non-zero.
    ID3D11DeviceContext* pCtx = nullptr;
    g_pD3D11Device->GetImmediateContext(&pCtx);
    if (!pCtx) { pSrcTex->Release(); return nullptr; }
    pCtx->CopySubresourceRegion(g_staging.tex, 0, 0, 0, 0,
                                pSrcTex, srcSub, nullptr);

    D3D11_MAPPED_SUBRESOURCE mapped = {};
    hr = pCtx->Map(g_staging.tex, 0, D3D11_MAP_READ, 0, &mapped);
    if (FAILED(hr)) {
        pCtx->Release();
        pSrcTex->Release();
        return nullptr;
    }

    // 4. Allocate a fresh GstBuffer sized exactly for I420 at the source
    //    dimensions (W*H luma + 2 * (W/2 * H/2) chroma = W*H*3/2).
    const int width   = (int)srcDesc.Width;
    const int height  = (int)srcDesc.Height;
    const int chromaW = (width  + 1) / 2;
    const int chromaH = (height + 1) / 2;
    const gsize totalSize =
        (gsize)width * height + 2 * (gsize)chromaW * chromaH;

    GstBuffer* pGstBuf = gst_buffer_new_allocate(NULL, totalSize, NULL);
    if (!pGstBuf) {
        pCtx->Unmap(g_staging.tex, 0);
        pCtx->Release();
        pSrcTex->Release();
        return nullptr;
    }

    GstMapInfo info = {};
    if (!gst_buffer_map(pGstBuf, &info, GST_MAP_WRITE)) {
        gst_buffer_unref(pGstBuf);
        pCtx->Unmap(g_staging.tex, 0);
        pCtx->Release();
        pSrcTex->Release();
        return nullptr;
    }

    // 5. NV12 → I420 layout conversion. Staging map gives us:
    //   - Y plane:  [0 ..              pitch * H)
    //   - UV plane: [pitch * H ..      pitch * H * 3/2)  (interleaved U,V,U,V,…)
    // I420 destination is contiguous Y, then U, then V, each tight-packed
    // at `width` / `chromaW` strides. We *unpad* the source pitch so
    // downstream sees a clean width-aligned buffer.
    const UINT pitch  = mapped.RowPitch;
    BYTE* yDst = info.data;
    BYTE* uDst = info.data + (size_t)width * height;
    BYTE* vDst = uDst + (size_t)chromaW * chromaH;
    BYTE* ySrc  = (BYTE*)mapped.pData;
    BYTE* uvSrc = ySrc + (size_t)pitch * height;

    // Y plane: row-by-row memcpy because mapped.RowPitch may exceed
    // width (D3D11 alignment padding); destination is tight-packed.
    for (int y = 0; y < height; ++y) {
        memcpy(yDst + (size_t)y * width, ySrc + (size_t)y * pitch, width);
    }
    // UV plane deinterleave. Each interleaved row holds chromaW U bytes
    // and chromaW V bytes, alternating. Split into separate destination
    // planes; ½ frame of arithmetic but stride-independent so any pitch
    // works.
    for (int y = 0; y < chromaH; ++y) {
        BYTE* row = uvSrc + (size_t)y * pitch;
        BYTE* uOut = uDst + (size_t)y * chromaW;
        BYTE* vOut = vDst + (size_t)y * chromaW;
        for (int x = 0; x < chromaW; ++x) {
            uOut[x] = row[x * 2];
            vOut[x] = row[x * 2 + 1];
        }
    }

    gst_buffer_unmap(pGstBuf, &info);
    pCtx->Unmap(g_staging.tex, 0);
    pCtx->Release();
    pSrcTex->Release();

    // 6. Propagate timestamps from the MFT sample. Times are 100-ns
    //    ticks (MF convention); GST uses nanoseconds, so ×100.
    LONGLONG ts = 0, dur = 0;
    if (SUCCEEDED(pSample->GetSampleTime(&ts))) {
        GST_BUFFER_TIMESTAMP(pGstBuf) = ts * 100;
    }
    if (SUCCEEDED(pSample->GetSampleDuration(&dur))) {
        GST_BUFFER_DURATION(pGstBuf) = dur * 100;
    }
    return pGstBuf;
}

static GstFlowReturn mfwrapper_deliver_sample(GstMFWrapper *decoder,
        IMFSample *pSample, CMFGSTBuffer *pMFGSTBuffer)
{
    GstFlowReturn ret = GST_FLOW_OK;
    GstBuffer *pGstBuffer = NULL;
    LONGLONG llTimestamp = 0;
    LONGLONG llDuration = 0;

    if (decoder == NULL || pSample == NULL || pMFGSTBuffer == NULL)
        return GST_FLOW_ERROR;

    // Phase-3 M2: one-shot diagnostic — logs whether the decoder is
    // producing D3D11-backed buffers we can use for zero-copy. No-op
    // when OPENJFX_MEDIA_D3D11VA isn't set. Buffer flow continues
    // through the existing CPU path regardless.
    mfwrapper_diag_d3d11_buffer(pSample);

    HRESULT hr = pMFGSTBuffer->GetGstBuffer(&pGstBuffer);
    if (FAILED(hr))
        return GST_FLOW_ERROR;

    hr = pSample->GetSampleTime(&llTimestamp);
    GST_BUFFER_TIMESTAMP(pGstBuffer) = llTimestamp * 100;

    if (SUCCEEDED(hr))
    {
        hr = pSample->GetSampleDuration(&llDuration);
        GST_BUFFER_DURATION(pGstBuffer) = llDuration * 100;
    }

    if (SUCCEEDED(hr) && decoder->is_force_output_discontinuity)
    {
        pGstBuffer = gst_buffer_make_writable(pGstBuffer);
        GST_BUFFER_FLAG_SET(pGstBuffer, GST_BUFFER_FLAG_DISCONT);
        decoder->is_force_output_discontinuity = FALSE;
    }

#if PTS_DEBUG
    if (GST_BUFFER_TIMESTAMP_IS_VALID(pGstBuffer) && GST_BUFFER_DURATION_IS_VALID(pGstBuffer))
        g_print("JFXMEDIA H265 %I64u %I64u\n", GST_BUFFER_TIMESTAMP(pGstBuffer), GST_BUFFER_DURATION(pGstBuffer));
    else if (GST_BUFFER_TIMESTAMP_IS_VALID(pGstBuffer) && !GST_BUFFER_DURATION_IS_VALID(pGstBuffer))
        g_print("JFXMEDIA H265 %I64u -1\n", GST_BUFFER_TIMESTAMP(pGstBuffer));
    else
        g_print("JFXMEDIA H265 -1\n");
#endif

    return gst_pad_push(decoder->srcpad, pGstBuffer);
}

static gint mfwrapper_process_output(GstMFWrapper *decoder)
{
    MFT_OUTPUT_DATA_BUFFER outputDataBuffer;
    outputDataBuffer.dwStreamID = 0;
    outputDataBuffer.pSample = decoder->pDecoderOutput;
    outputDataBuffer.dwStatus = 0;
    outputDataBuffer.pEvents = NULL;
    DWORD dwFlags = 0;
    DWORD dwStatus = 0;
    GstFlowReturn ret = GST_FLOW_OK;

    if (!decoder->pDecoder)
        return PO_FAILED;

    if (decoder->is_eos || decoder->is_flushing)
        return PO_FLUSHING;

    HRESULT hr = decoder->pDecoder->GetOutputStatus(&dwFlags);
    if (SUCCEEDED(hr) && dwFlags != MFT_OUTPUT_STATUS_SAMPLE_READY)
        return PO_NEED_MORE_DATA;

    hr = decoder->pDecoder->ProcessOutput(0, 1, &outputDataBuffer, &dwStatus);
    SafeRelease(&outputDataBuffer.pEvents);
    if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT)
    {
        return PO_NEED_MORE_DATA;
    }
    else if (hr == MF_E_TRANSFORM_STREAM_CHANGE)
    {
        if (outputDataBuffer.dwStatus == MFT_OUTPUT_DATA_BUFFER_FORMAT_CHANGE)
        {
            hr = mfwrapper_configure_decoder_output_type(decoder);
        }
    }
    else if (SUCCEEDED(hr))
    {
        if (outputDataBuffer.dwStatus == 0)
        {
            // M3-A: when the MFT decoded into a D3D11 texture, the CPU
            // color-converter chain (VideoProcessorMFT trying to memcpy
            // through D3D11_USAGE_DEFAULT memory) crashes with an access
            // violation. Detect the D3D11 sample, read it back into a
            // freshly-allocated I420 GstBuffer, and push it directly via
            // the srcpad — bypassing the pre-allocated CMFGSTBuffer
            // entirely, since with PROVIDES_SAMPLES that buffer's
            // dimensions are unrelated to the actual frame size.
            // Releases the MFT-allocated sample whether we accepted it
            // or not.
            IMFSample* pOut = outputDataBuffer.pSample;
            bool mfOwnedSample = (pOut != decoder->pDecoderOutput);
            if (mfwrapper_d3d11va_enabled()
                && pOut != nullptr
                && mfwrapper_sample_is_d3d11(pOut))
            {
                // M3-B: when OPENJFX_MEDIA_D3D11_META=1, skip the CPU
                // staging readback entirely. The meta carries the GPU
                // texture so the consumer can sample it directly, and
                // we save the largest per-frame cost (CopySubresource +
                // Map + NV12→I420 deinterleave). Falls back to the M3-A
                // readback if the meta attach fails.
                static int metaEnabled = -1;
                if (metaEnabled < 0) {
                    const char* v = getenv("OPENJFX_MEDIA_D3D11_META");
                    metaEnabled = (v && *v && *v != '0' && *v != 'f' && *v != 'F') ? 1 : 0;
                }
                GstBuffer* pBuf = nullptr;
                if (metaEnabled) {
                    pBuf = mfwrapper_make_meta_only_gstbuffer(decoder, pOut);
                    if (pBuf) {
                        if (!mfwrapper_attach_d3d11_meta(pBuf, pOut)) {
                            // VP/pool failed — fall through to the full
                            // readback so the consumer still sees pixels.
                            gst_buffer_unref(pBuf);
                            pBuf = nullptr;
                        }
                    }
                }
                if (!pBuf) {
                    pBuf = mfwrapper_readback_d3d11_to_new_gstbuffer(decoder, pOut);
                }
                if (pBuf) {
                    ret = gst_pad_push(decoder->srcpad, pBuf);
                } else {
                    ret = GST_FLOW_ERROR;
                }
                if (mfOwnedSample) { pOut->Release(); }
            }
            // Existing CPU path: NV12 from the decoder → IYUV via the
            // color-converter MFT → push. Untouched.
            else if (decoder->pColorConvert[COLOR_CONVERT_IYUV] &&
                     decoder->pColorConvertOutput[COLOR_CONVERT_IYUV])
            {
                if (mfwrapper_convert_output(decoder))
                {
                    ret = mfwrapper_deliver_sample(decoder,
                                decoder->pColorConvertOutput[COLOR_CONVERT_IYUV],
                                decoder->pColorConvertBuffer[COLOR_CONVERT_IYUV]);
                }
            }
            else
            {
                ret = mfwrapper_deliver_sample(decoder, decoder->pDecoderOutput,
                            decoder->pDecoderBuffer);
            }
        }
    }
    else
    {
        decoder->is_decoder_error = TRUE;
        gst_element_message_full(GST_ELEMENT(decoder), GST_MESSAGE_ERROR,
                GST_STREAM_ERROR, GST_STREAM_ERROR_DECODE,
                g_strdup_printf("Failed to decode stream (0x%X)", hr), NULL,
                ("mfwrapper.c"), ("mfwrapper_process_output"), 0);
    }

    if (decoder->is_eos || decoder->is_flushing || ret != GST_FLOW_OK)
        return PO_FLUSHING;
    else if (SUCCEEDED(hr))
        return PO_DELIVERED;
    else
        return PO_FAILED;
}

// Processes input buffers
static GstFlowReturn mfwrapper_chain(GstPad *pad, GstObject *parent, GstBuffer *buf)
{
    GstFlowReturn ret = GST_FLOW_OK;
    GstMFWrapper *decoder = GST_MFWRAPPER(parent);

    if (decoder->is_flushing || decoder->is_eos_received)
    {
        // INLINE - gst_buffer_unref()
        gst_buffer_unref(buf);
        return GST_FLOW_FLUSHING;
    }

    if (!mfwrapper_process_input(decoder, buf))
        return GST_FLOW_FLUSHING;

    gint po_ret = mfwrapper_process_output(decoder);
    if (po_ret != PO_DELIVERED && po_ret != PO_NEED_MORE_DATA)
        return GST_FLOW_FLUSHING;

    if (decoder->is_flushing)
        return GST_FLOW_FLUSHING;

    return ret;
}

static gboolean mfwrapper_push_sink_event(GstMFWrapper *decoder, GstEvent *event)
{
    gboolean ret = TRUE;

    if (gst_pad_is_linked(decoder->srcpad))
        ret = gst_pad_push_event(decoder->srcpad, gst_event_ref(event));  // INLINE - gst_event_ref()

    // INLINE - gst_event_unref()
    gst_event_unref(event);

    return ret;
}

// This function will unload old instance of decoder and will create a new one.
// Input and Output media formats will be exactly same as old one.
// This function will not trigger format change downstream, so it should not
// be used as reload for format change.
// NOTE: This function should be called when stream lock is aquired. From
// serialized events for example like GST_EVENT_FLUSH_STOP.
static gboolean mfwrapper_reload_decoder(GstMFWrapper *decoder)
{
    HRESULT hr = S_OK;

    IMFMediaType *pInputType = NULL;
    IMFMediaType *pOutputType = NULL;

    DWORD dwStatus = 0;

    GUID majorType;
    GUID subType;

    if (decoder == NULL || decoder->pDecoder == NULL)
        return false;

    // Save copy of old decoder
    IMFTransform *pOldDecoder = decoder->pDecoder;

    decoder->pDecoder = NULL;

    hr = pOldDecoder->GetInputCurrentType(0, &pInputType);
    if (SUCCEEDED(hr))
        hr = pOldDecoder->GetOutputCurrentType(0, &pOutputType);
    if (SUCCEEDED(hr))
        hr = pInputType->GetGUID(MF_MT_MAJOR_TYPE, &majorType);
    if (SUCCEEDED(hr))
        hr = pInputType->GetGUID(MF_MT_SUBTYPE, &subType);

    // Load decoder based on media types of current one
    hr = mfwrapper_load_decoder_media_types(decoder, majorType, subType);

    // Copy input and output types and we should be good to go
    if (SUCCEEDED(hr))
        hr = decoder->pDecoder->SetInputType(0, pInputType, 0);
    if (SUCCEEDED(hr))
        hr = decoder->pDecoder->SetOutputType(0, pOutputType, 0);

    if (SUCCEEDED(hr))
        hr = decoder->pDecoder->GetInputStatus(0, &dwStatus);

    if (FAILED(hr) || dwStatus != MFT_INPUT_STATUS_ACCEPT_DATA) {
        hr = E_FAIL;
    }

    if (SUCCEEDED(hr))
        hr = decoder->pDecoder->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, NULL);

    if (SUCCEEDED(hr))
        hr = decoder->pDecoder->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, NULL);

    if (SUCCEEDED(hr))
        hr = decoder->pDecoder->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, NULL);

    SafeRelease(&pInputType);
    SafeRelease(&pOutputType);
    SafeRelease(&pOldDecoder);

    if (FAILED(hr))
        return false;

    return true;
}

static gboolean mfwrapper_sink_event(GstPad* pad, GstObject *parent, GstEvent *event)
{
    gboolean ret = FALSE;
    GstMFWrapper *decoder = GST_MFWRAPPER(parent);
    HRESULT hr = S_OK;

    switch (GST_EVENT_TYPE(event))
    {
    case GST_EVENT_SEGMENT:
    {
        decoder->is_force_discontinuity = TRUE;
        ret = mfwrapper_push_sink_event(decoder, event);
        decoder->is_eos_received = FALSE;
        decoder->is_eos = FALSE;
    }
    break;
    case GST_EVENT_FLUSH_START:
    {
        decoder->is_flushing = TRUE;

        ret = mfwrapper_push_sink_event(decoder, event);
    }
    break;
    case GST_EVENT_FLUSH_STOP:
    {
        if (!decoder->is_decoder_error)
        {
            if (!mfwrapper_reload_decoder(decoder))
            {
                decoder->is_decoder_error = TRUE;
                gst_element_message_full(GST_ELEMENT(decoder), GST_MESSAGE_ERROR,
                                     GST_STREAM_ERROR, GST_STREAM_ERROR_DECODE,
                                     g_strdup("Failed to reload decoder"), NULL,
                                     ("mfwrapper.c"), ("mfwrapper_sink_event"), 0);
            }
            else
            {
                // Send header after reload
                decoder->is_send_header = TRUE;

                for (int i = 0; i < MAX_COLOR_CONVERT; i++)
                {
                    if (decoder->pColorConvert[i])
                    {
                        decoder->pColorConvert[i]->
                                ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
                    }
                }
            }
        }

        // Even if reload failed with critical error push event to unblock
        // pipeline.
        ret = mfwrapper_push_sink_event(decoder, event);

        decoder->is_flushing = FALSE;
    }
    break;
    case GST_EVENT_EOS:
    {
        decoder->is_eos_received = TRUE;

        if (!decoder->is_decoder_error)
        {
            // Let decoder know that we got end of stream
            hr = decoder->pDecoder->
                ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);

            // Ask decoder to produce all remaining data
            if (SUCCEEDED(hr))
            {
                decoder->pDecoder->
                        ProcessMessage(MFT_MESSAGE_COMMAND_DRAIN, 0);
            }

            // Deliver remaining data
            gint po_ret;
            do
            {
                po_ret = mfwrapper_process_output(decoder);
            } while (po_ret == PO_DELIVERED);

            for (int i = 0; i < MAX_COLOR_CONVERT; i++)
            {
                if (decoder->pColorConvert[i])
                {
                    hr = decoder->pColorConvert[i]->
                            ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
                    if (SUCCEEDED(hr))
                        hr = decoder->pColorConvert[i]->
                                ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
                }
            }
        }

        // We done pushing all frames. Deliver EOS.
        ret = mfwrapper_push_sink_event(decoder, event);

        decoder->is_eos = TRUE;
    }
    break;
    case GST_EVENT_CAPS:
    {
        GstCaps *caps;

        gst_event_parse_caps(event, &caps);
        if (!mfwrapper_sink_set_caps(pad, parent, caps))
        {
            gst_element_message_full(GST_ELEMENT(decoder), GST_MESSAGE_ERROR,
                                     GST_STREAM_ERROR, GST_STREAM_ERROR_DECODE,
                                     g_strdup("Failed to decode stream"), NULL,
                                     ("mfwrapper.c"), ("mfwrapper_sink_event"), 0);
        }

        // INLINE - gst_event_unref()
        gst_event_unref(event);
        ret = TRUE;
    }
    break;
    default:
        ret = mfwrapper_push_sink_event(decoder, event);
        break;
    }

    return ret;
}

static gboolean mfwrapper_get_mf_media_types(GstCaps *caps, GUID *pMajorType, GUID *pSubType)
{
    GstStructure *s = NULL;
    const gchar *mimetype = NULL;

    if (caps == NULL || pMajorType == NULL || pSubType == NULL)
        return FALSE;

    s = gst_caps_get_structure(caps, 0);
    if (s != NULL)
    {
        mimetype = gst_structure_get_name(s);
        if (mimetype != NULL)
        {
            if (strstr(mimetype, "video/x-h265") != NULL)
            {
                *pMajorType = MFMediaType_Video;
                *pSubType = MFVideoFormat_HEVC;

                return TRUE;
            }
            else if (strstr(mimetype, "video/x-av1") != NULL)
            {
                *pMajorType = MFMediaType_Video;
                *pSubType = MFVideoFormat_AV1;

                return TRUE;
            }
        }
    }

    return FALSE;
}

static HRESULT mfwrapper_load_decoder_caps(GstMFWrapper *decoder, GstCaps *caps)
{
    GUID majorType;
    GUID subType;

    if (decoder->pDecoder)
        return S_OK;

    if (!mfwrapper_get_mf_media_types(caps, &majorType, &subType))
        return E_FAIL;

    return mfwrapper_load_decoder_media_types(decoder, majorType, subType);
}

static HRESULT mfwrapper_load_decoder_media_types(GstMFWrapper *decoder,
                                                  GUID majorType, GUID subType)
{
    HRESULT hr = S_OK;
    UINT32 count = 0;

    IMFActivate **ppActivate = NULL;

    MFT_REGISTER_TYPE_INFO info = { 0 };

    if (decoder->pDecoder)
        return S_OK;

    info.guidMajorType = majorType;
    info.guidSubtype = subType;

    hr = MFTEnumEx(MFT_CATEGORY_VIDEO_DECODER,
        MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_LOCALMFT | MFT_ENUM_FLAG_SORTANDFILTER,
        &info,
        NULL,
        &ppActivate,
        &count);
    if (SUCCEEDED(hr) && count == 0)
    {
        hr = E_FAIL;
    }

    if (SUCCEEDED(hr))
    {
        hr = ppActivate[0]->ActivateObject(IID_PPV_ARGS(&decoder->pDecoder));
    }

    for (UINT32 i = 0; i < count; i++)
    {
        ppActivate[i]->Release();
    }

    CoTaskMemFree(ppActivate);

    // Friendly error: no MF decoder found OR activation failed. The
    // common cause on a fresh Windows install is the missing optional
    // Microsoft Store codec extension. Print one identifiable line
    // per codec so the JFX error message + log makes the cause clear.
    if (FAILED(hr))
    {
        const char* codecName  = "(unknown)";
        const char* msStoreApp = "(unknown)";
        if (subType == MFVideoFormat_HEVC) {
            codecName  = "HEVC (H.265)";
            msStoreApp = "HEVC Video Extensions";
        } else if (subType == MFVideoFormat_AV1) {
            codecName  = "AV1";
            msStoreApp = "AV1 Video Extension";
        }
        g_print("[mfwrapper] %s decoder unavailable (hr=0x%08lx). "
                "Install \"%s\" from the Microsoft Store, then retry.\n",
                codecName, (unsigned long)hr, msStoreApp);
    }

    // Phase-3 M2: opt-in D3D11VA path. No-op when OPENJFX_MEDIA_D3D11VA
    // isn't set, so the existing software/CPU decode path is unchanged.
    if (SUCCEEDED(hr) && decoder && decoder->pDecoder)
    {
        mfwrapper_setup_d3d11va(decoder->pDecoder);
    }

    return hr;
}

gsize mfwrapper_get_hevc_config(void *in, gsize in_size, BYTE *out, gsize out_size)
{
    guintptr bdata = (guintptr)in;
    guint8 arrayCount = 0;
    guint16 nalUnitsCount = 0;
    guint16 nalUnitLength = 0;
    guint ii = 0;
    guint jj = 0;
    gsize in_bytes_count = 22;
    gsize out_bytes_count = 0;
    guint8 startCode[4] = { 0x00, 0x00, 0x00, 0x01 };

    if (in_bytes_count > in_size)
        return 0;

    // Skip first 22 bytes
    bdata += in_bytes_count;

    // Get array count
    arrayCount = *(guint8*)bdata;
    bdata++; in_bytes_count++;

    for (ii = 0; ii < arrayCount; ii++) {
        if ((in_bytes_count + 3) > in_size)
            return 0;

        // Skip 1 byte, not needed
        bdata++; in_bytes_count++;

        // 2 bytes number of nal units in array
        nalUnitsCount = ((guint16)*(guint8*)bdata) << 8;
        bdata++; in_bytes_count++;
        nalUnitsCount |= (guint16)*(guint8*)bdata;
        bdata++; in_bytes_count++;

        for (jj = 0; jj < nalUnitsCount; jj++) {
            if ((in_bytes_count + 2) > in_size)
                return 0;

            nalUnitLength = ((guint16)*(guint8*)bdata) << 8;
            bdata++; in_bytes_count++;
            nalUnitLength |= (guint16)*(guint8*)bdata;
            bdata++; in_bytes_count++;

            if ((out_bytes_count + 4) > out_size)
                return 0;

            // Set start code
            memcpy(out, &startCode[0], sizeof(startCode));
            out += sizeof(startCode); out_bytes_count += sizeof(startCode);

            if ((out_bytes_count + nalUnitLength) > out_size)
                return 0;

            if ((in_bytes_count + nalUnitLength) > in_size)
                return 0;

            // Copy nal unit
            memcpy(out, (guint8*)bdata, nalUnitLength);
            bdata += nalUnitLength; in_bytes_count += nalUnitLength;
            out += nalUnitLength; out_bytes_count += nalUnitLength;
        }
    }

    return out_bytes_count;
}

// AV1CodecConfigurationRecord (AV1-ISOBMFF §2.3.2): 4 bytes of fixed
// fields (marker/version/profile/level/tier/bit-depth/monochrome/
// chroma-subsampling/position) followed by `configOBUs[]` — the
// sequence header OBU and any optional metadata OBUs concatenated
// together. MediaFoundation's AV1 decoder accepts the OBUs directly
// when prepended to the first frame's data, so we just strip the
// 4-byte fixed header and emit the rest unchanged.
gsize mfwrapper_get_av1_config(void *in, gsize in_size, BYTE *out, gsize out_size)
{
    const gsize fixed_header_size = 4;
    if (in_size <= fixed_header_size) return 0;
    gsize obu_size = in_size - fixed_header_size;
    if (obu_size > out_size) return 0;
    memcpy(out, (BYTE *)in + fixed_header_size, obu_size);
    return obu_size;
}

static HRESULT mfwrapper_set_input_media_type(GstMFWrapper *decoder, GstCaps *caps)
{
    HRESULT hr = S_OK;

    IMFMediaType *pInputType = NULL;
    GUID majorType;
    GUID subType;
    GstStructure *s = NULL;

    s = gst_caps_get_structure(caps, 0);
    if (s == NULL)
        return E_FAIL;

    if (!mfwrapper_get_mf_media_types(caps, &majorType, &subType))
        return E_FAIL;

    hr = MFCreateMediaType(&pInputType);

    if (SUCCEEDED(hr))
        hr = pInputType->SetGUID(MF_MT_MAJOR_TYPE, majorType);

    if (SUCCEEDED(hr))
        hr = pInputType->SetGUID(MF_MT_SUBTYPE, subType);

    if (SUCCEEDED(hr) && gst_structure_get_int(s, "width", (gint*)&decoder->width) && gst_structure_get_int(s, "height", (gint*)&decoder->height))
        hr = MFSetAttributeSize(pInputType, MF_MT_FRAME_SIZE, decoder->width, decoder->height);

    if (SUCCEEDED(hr) && gst_structure_get_fraction(s, "framerate", (gint*)&decoder->framerate_num, (gint*)&decoder->framerate_den))
        hr = MFSetAttributeRatio(pInputType, MF_MT_FRAME_RATE, decoder->framerate_num, decoder->framerate_den);

    if (SUCCEEDED(hr))
        hr = decoder->pDecoder->SetInputType(0, pInputType, 0);

    SafeRelease(&pInputType);

    return hr;
}

static HRESULT mfwrapper_set_output_media_type(GstMFWrapper *decoder, GstCaps *caps)
{
    HRESULT hr = S_OK;

    IMFMediaType *pOutputType = NULL;

    hr = MFCreateMediaType(&pOutputType);

    if (SUCCEEDED(hr))
        hr = pOutputType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);

    // D3D11VA decoders only emit NV12 (or P010 for 10-bit). Asking for
    // IYUV here results in SetOutputType failing or, worse, succeeding
    // with the MFT-equivalent of an internal type-mismatch that
    // crashes on the first ProcessOutput. Pick NV12 up-front when the
    // GPU path is active; the readback in mfwrapper_process_output
    // does the NV12→I420 unpack.
    // D3D11VA decoders only emit NV12 (or P010 for 10-bit). Asking for
    // IYUV under the GPU path makes SetOutputType silently install an
    // incompatible internal type that crashes on the first
    // ProcessOutput with an access violation. Pick NV12 up-front when
    // GPU decode is active; mfwrapper_readback_d3d11_to_new_gstbuffer
    // unpacks NV12→I420 during the staging readback.
    if (SUCCEEDED(hr)) {
        hr = pOutputType->SetGUID(MF_MT_SUBTYPE,
                mfwrapper_d3d11va_enabled() ? MFVideoFormat_NV12
                                            : MFVideoFormat_IYUV);
    }

    if (SUCCEEDED(hr))
        hr = MFSetAttributeSize(pOutputType, MF_MT_FRAME_SIZE, decoder->width, decoder->height);

    if (SUCCEEDED(hr))
        hr = MFSetAttributeRatio(pOutputType, MF_MT_FRAME_RATE, decoder->framerate_num, decoder->framerate_den);

    if (SUCCEEDED(hr))
        hr = decoder->pDecoder->SetOutputType(0, pOutputType, 0);

    // Set srcpad caps
    mfwrapper_set_src_caps(decoder);

    SafeRelease(&pOutputType);

    return hr;
}

static gboolean mfwrapper_init_mf(GstMFWrapper *decoder, GstCaps *caps)
{
    HRESULT hr = S_OK;
    DWORD dwStatus = 0;
    GstStructure *s = NULL;
    const GValue *codec_data_value = NULL;
    GstBuffer *codec_data = NULL;
    gint skipSize = 0;
    IMFAttributes *pAttributes = NULL;
    UINT32 unFormatChange = FALSE;

    if (!decoder->is_decoder_initialized)
    {
        if (SUCCEEDED(hr))
            hr = mfwrapper_set_input_media_type(decoder, caps);

        if (SUCCEEDED(hr))
            hr = mfwrapper_set_output_media_type(decoder, caps);

        if (SUCCEEDED(hr))
            hr = decoder->pDecoder->GetInputStatus(0, &dwStatus);

        if (FAILED(hr) || dwStatus != MFT_INPUT_STATUS_ACCEPT_DATA) {
            return FALSE;
        }
    }

    if (SUCCEEDED(hr))
        s = gst_caps_get_structure(caps, 0);

    if (s == NULL)
        return FALSE;

    // Get HEVC Config
    GstMapInfo info;

    if (SUCCEEDED(hr))
        codec_data_value = gst_structure_get_value(s, "codec_data");

    if (codec_data_value)
        codec_data = gst_value_get_buffer(codec_data_value);

    if (codec_data != NULL)
    {
        if (gst_buffer_map(codec_data, &info, GST_MAP_READ) && info.size > 0)
        {
            // Free old one if exist
            if (decoder->header)
                delete[] decoder->header;

            decoder->header = new BYTE[info.size * 2]; // Should be enough, since we will only add several 4 bytes start codes to 3 nal units
            if (decoder->header == NULL)
            {
                gst_buffer_unmap(codec_data, &info);
                return FALSE;
            }

            // Dispatch on codec: HEVC parses hvcC → Annex B NALUs,
            // AV1 strips the 4-byte av1C fixed header and keeps the
            // configOBUs portion verbatim.
            if (decoder->codec_id == JFX_CODEC_ID_AV1)
            {
                decoder->header_size = mfwrapper_get_av1_config(
                    info.data, info.size, decoder->header, info.size * 2);
            }
            else
            {
                decoder->header_size = mfwrapper_get_hevc_config(
                    info.data, info.size, decoder->header, info.size * 2);
            }
            gst_buffer_unmap(codec_data, &info);

            if (decoder->header_size <= 0)
            {
                delete[] decoder->header;
                decoder->header = NULL;
                return FALSE;
            }

            decoder->is_send_header = TRUE;
        }
    }

    if (!decoder->is_decoder_initialized)
    {
        if (SUCCEEDED(hr))
            hr = decoder->pDecoder->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, NULL);

        if (SUCCEEDED(hr))
            hr = decoder->pDecoder->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, NULL);

        if (SUCCEEDED(hr))
            hr = decoder->pDecoder->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, NULL);

        if (SUCCEEDED(hr))
            decoder->is_decoder_initialized = TRUE;
    }

    if (SUCCEEDED(hr))
        return TRUE;
    else
        return FALSE;
}

static gboolean mfwrapper_sink_set_caps(GstPad * pad, GstObject *parent, GstCaps * caps)
{
    gboolean ret = FALSE;
    GstMFWrapper *decoder = GST_MFWRAPPER(parent);

    if (pad == decoder->sinkpad)
    {
        // Capture the upstream colorimetry before configuring MFT.
        // qtdemux / matroskademux set this from container metadata
        // and MFT itself doesn't preserve it on the decoded output —
        // so we ferry it around the decoder to the src caps below.
        GstStructure *s = gst_caps_get_structure(caps, 0);
        if (s != NULL)
        {
            const gchar *ci = gst_structure_get_string(s, "colorimetry");
            if (decoder->input_colorimetry != NULL)
            {
                g_free(decoder->input_colorimetry);
                decoder->input_colorimetry = NULL;
            }
            if (ci != NULL)
            {
                decoder->input_colorimetry = g_strdup(ci);
            }
        }

        ret = mfwrapper_init_mf(decoder, caps);
    }

    return ret;
}

static gboolean mfwrapper_activate(GstPad *pad, GstObject *parent)
{
    return gst_pad_activate_mode(pad, GST_PAD_MODE_PUSH, TRUE);
}

static gboolean mfwrapper_activatemode(GstPad *pad, GstObject *parent, GstPadMode mode, gboolean active)
{
    gboolean res = FALSE;
    GstMFWrapper *decoder = GST_MFWRAPPER(parent);

    switch (mode) {
    case GST_PAD_MODE_PUSH:
        res = TRUE;
        break;
    case GST_PAD_MODE_PULL:
        res = TRUE;
        break;
    default:
        /* unknown scheduling mode */
        res = FALSE;
        break;
    }

    return res;
}

gboolean mfwrapper_init(GstPlugin* mfwrapper)
{
    return gst_element_register(mfwrapper, "mfwrapper", 512, GST_TYPE_MFWRAPPER);
}
