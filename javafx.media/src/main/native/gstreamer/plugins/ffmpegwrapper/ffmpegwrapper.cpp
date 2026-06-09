// ---------------------------------------------------------------------------
// ffmpegwrapper — libavcodec-backed GStreamer-Lite decoder element.
//
// Sister plugin to mfwrapper.cpp. Where mfwrapper uses MediaFoundation's
// IMFTransform for AV1 / HEVC decode, this one routes the same codecs
// through ffmpeg's libavcodec. ffmpeg is loaded at runtime from a
// user-supplied directory (no link dependency); when it isn't
// available, every is-supported query returns FALSE and codec routing
// in GstAVPlaybackPipeline falls through to mfwrapper / dshowwrapper.
//
// Element shape mirrors mfwrapper:
//   sink caps:  video/x-h264; video/x-h265; video/x-av1
//   src caps:   video/x-raw-yuv, format=(string)I420
//   props:      codec-id (in), is-supported (out)
//
// Hardware acceleration:
//   - D3D11VA hwaccel via av_hwdevice_ctx_create(AV_HWDEVICE_TYPE_D3D11VA).
//   - We create our own D3D11 device (BGRA_SUPPORT + VIDEO_SUPPORT +
//     SHARED MISC flag on output textures) so RGBA outputs can be
//     opened on the skia-interop device via wglDXRegisterObjectNV.
//   - On HW frames we use a D3D11VideoProcessor to convert NV12/P010
//     into an RGBA pooled texture, attach OpenJfxMediaD3d11Meta to a
//     dimensionally-correct (but uninitialised) I420 placeholder
//     GstBuffer, and push it downstream. The existing consumer-side
//     SkiaMediaTexture path picks up the meta and takes the zero-copy
//     route — identical to the mfwrapper M3-B flow.
//   - On SW frames (no hwaccel, e.g. user lacking GPU) we pack the
//     AVFrame's I420 planes into a real GstBuffer.
//
// First-cut implementation focuses on the HW-D3D11 path; SW fallback is
// supported but exercises less heavily.
// ---------------------------------------------------------------------------

#ifdef HAVE_CONFIG_H
#  include <config.h>
#endif

#include <gst/gst.h>

#include <atomic>
#include <cstdio>
#include <cstring>

#include "ffmpeg_loader.h"
#include "fxplugins_common.h"

#define WIN32_LEAN_AND_MEAN
#ifndef NOMINMAX
#  define NOMINMAX
#endif
#include <windows.h>
#include <d3d11.h>
#include <d3d11_4.h>

#include "openjfx_media_d3d11_meta.h"

// =============================================================================
// Forward declarations + element type
// =============================================================================

typedef struct _GstFfmpegWrapper      GstFfmpegWrapper;
typedef struct _GstFfmpegWrapperClass GstFfmpegWrapperClass;

#define GST_TYPE_FFMPEGWRAPPER (gst_ffmpegwrapper_get_type())
#define GST_FFMPEGWRAPPER(obj) (G_TYPE_CHECK_INSTANCE_CAST((obj), \
    GST_TYPE_FFMPEGWRAPPER, GstFfmpegWrapper))

GST_DEBUG_CATEGORY_STATIC(gst_ffmpegwrapper_debug);
#define GST_CAT_DEFAULT gst_ffmpegwrapper_debug

struct _GstFfmpegWrapper {
    GstElement element;
    GstPad*    sinkpad;
    GstPad*    srcpad;

    gint       codec_id;          // JFX_CODEC_ID_* (matches mfwrapper IDs)
    gint       av_codec_id;       // resolved AVCodecID — set either from
                                  // the codec-id property OR from caps
    gboolean   is_supported;
    gboolean   eos_pushed;
    gboolean   caps_negotiated;
    // Latched video frame size that the currently-negotiated src caps
    // describe. A mid-stream resolution change (adaptive streams,
    // resolution-switching codecs) must re-emit caps so downstream
    // doesn't read the new-size planes under the old strides/offsets.
    // 0 until the first video caps push. Unused on the audio path.
    int        negotiated_width;
    int        negotiated_height;

    // ffmpeg state — null until first buffer or activate.
    AVCodecContext* avctx;
    AVPacket*       pkt;
    AVFrame*        sw_frame;     // for SW fallback / transfer target
    AVBufferRef*    hw_device_ref;
    bool            hw_ready;     // hwaccel selected + open succeeded

    // Audio path (skia-fx). When `is_audio` is TRUE the chain function
    // routes through the audio decode path (no hwaccel, PCM S16LE output);
    // when FALSE the existing video path runs unchanged.
    gboolean        is_audio;
    int             aud_rate;       // sample rate from caps (Hz)
    int             aud_channels;   // channel count from caps
    // Codec-private bytes from caps `codec_data` (or vorbis streamheaders
    // packed Xiph-style). Owned by the element; freed in close_decoder
    // via the avctx (ffmpeg copies these on open).
    guint8*         aud_extradata;
    int             aud_extradata_size;

    // Container-level color metadata, lifted from the INPUT caps event
    // (e.g. qtdemux parses MP4's colr atom, matroskademux parses MKV's
    // Colour element). Bitstream metadata in AVFrame's colorspace/
    // primaries/trc fields takes precedence when set; this is the
    // fallback for malformed AV1/HEVC encodes where the bitstream
    // left those fields zero but the container has the truth.
    // -1 = unspecified, 0 = BT.601, 1 = BT.709, 2 = BT.2020.
    int             container_yuv_matrix;
    int             container_yuv_range;  // -1=unspec, 0=limited, 1=full
};

struct _GstFfmpegWrapperClass {
    GstElementClass parent_class;
};

static void     gst_ffmpegwrapper_class_init(GstFfmpegWrapperClass*);
static void     gst_ffmpegwrapper_init(GstFfmpegWrapper*);
static void     gst_ffmpegwrapper_dispose(GObject*);
static void     gst_ffmpegwrapper_set_property(GObject*, guint, const GValue*, GParamSpec*);
static void     gst_ffmpegwrapper_get_property(GObject*, guint, GValue*, GParamSpec*);

static GstFlowReturn gst_ffmpegwrapper_chain(GstPad*, GstObject*, GstBuffer*);
static gboolean      gst_ffmpegwrapper_sink_event(GstPad*, GstObject*, GstEvent*);

// =============================================================================
// G_DEFINE_TYPE expansion (matches mfwrapper's pattern)
// =============================================================================
#define gst_ffmpegwrapper_parent_class parent_class
static gpointer gst_ffmpegwrapper_parent_class = NULL;

static void gst_ffmpegwrapper_class_intern_init(gpointer klass) {
    gst_ffmpegwrapper_parent_class = g_type_class_peek_parent(klass);
    gst_ffmpegwrapper_class_init((GstFfmpegWrapperClass*)klass);
}

GType gst_ffmpegwrapper_get_type(void) {
    static volatile gsize g_define_type_id__volatile = 0;
    if (g_once_init_enter(&g_define_type_id__volatile)) {
        GType g_define_type_id = g_type_register_static_simple(
            GST_TYPE_ELEMENT, g_intern_static_string("GstFfmpegWrapper"),
            sizeof(GstFfmpegWrapperClass),
            (GClassInitFunc)gst_ffmpegwrapper_class_intern_init,
            sizeof(GstFfmpegWrapper),
            (GInstanceInitFunc)gst_ffmpegwrapper_init,
            (GTypeFlags)0);
        g_once_init_leave(&g_define_type_id__volatile, g_define_type_id);
    }
    return g_define_type_id__volatile;
}

// =============================================================================
// Pad templates (mirror mfwrapper)
// =============================================================================

// Sink caps: every mimetype the JFX demuxer chain can produce that
// libavcodec might decode. The actual codec is resolved per-stream
// from the CAPS event (see set_codec_from_mimetype below). When ffmpeg
// has no decoder for a given mimetype, ffmpegwrapper just refuses the
// stream at codec-resolution time and routing falls back to mfwrapper
// / dshowwrapper.
//
// skia-fx: this element handles both video AND audio. Audio caps below
// were added so the matroska/webm pipeline can route Opus/Vorbis/FLAC/
// AC-3 etc. through the same ffmpeg-loaded libavcodec — those codecs
// have no MediaFoundation/DirectShow equivalent, so ffmpeg is the only
// viable path. The chain function inspects the negotiated mimetype to
// decide which decode pipeline to run.
static GstStaticPadTemplate sink_factory =
    GST_STATIC_PAD_TEMPLATE("sink", GST_PAD_SINK, GST_PAD_ALWAYS,
        GST_STATIC_CAPS(
            // ===== Video =====
            // Modern HD/UHD
            "video/x-h264; "
            "video/x-h265; "
            "video/x-av1; "
            // VPx (WebM)
            "video/x-vp8; "
            "video/x-vp9; "
            // Legacy MPEG family
            "video/mpeg; "
            "video/x-h263; "
            "video/x-divx; "
            "video/x-xvid; "
            // Pro / camera
            "video/x-prores; "
            "video/x-dv; "
            "video/x-mjpeg; "
            // Theora (Ogg)
            "video/x-theora; "
            // Windows Media / VC-1
            "video/x-wmv; "
            "video/x-vc1; "
            // FLV
            "video/x-flash-video; "
            // ===== Audio (skia-fx) =====
            // mpegversion=1 layer=3 → mp3; mpegversion=4 → AAC.
            // The mimetype_to_av_codec_id_str maps from the structure.
            "audio/mpeg; "
            "audio/x-vorbis; "
            "audio/x-opus; "
            "audio/x-flac; "
            "audio/x-ac3; "
            "audio/x-eac3; "
            "audio/x-wma; "
            "audio/x-alaw; "
            "audio/x-mulaw"));

// Src caps: this template lists BOTH the video and audio output
// formats this element can negotiate. The actual caps event emitted
// to downstream is constructed at first-frame time (in chain()) based
// on whether the stream is audio or video; only the matching subset
// of this union is used per-stream.
static GstStaticPadTemplate src_factory =
    GST_STATIC_PAD_TEMPLATE("src", GST_PAD_SRC, GST_PAD_ALWAYS,
        GST_STATIC_CAPS(
            "video/x-raw-yuv, format=(string)I420; "
            "audio/x-raw, format=(string)S16LE, "
            "layout=(string)interleaved"));

// Property enum. `mimetype` is the broad-coverage path: callers set it
// to e.g. "video/x-vp9" and is-supported tells them whether ffmpeg has
// a decoder. codec-id remains for backward compat with mfwrapper's
// JFX_CODEC_ID_* mapping.
enum {
    PROP_0,
    PROP_CODEC_ID,
    PROP_MIMETYPE,
    PROP_IS_SUPPORTED,
};

// =============================================================================
// Shared D3D11 device for the hwaccel + VP path.
// Created lazily on first use; one per process. Used directly with
// ffmpeg's AVHWDEVICE_TYPE_D3D11VA hwaccel.
// =============================================================================

static ID3D11Device*         g_ff_d3d11Device = nullptr;
static ID3D11DeviceContext*  g_ff_d3d11Context = nullptr;
static std::atomic<bool>     g_ff_d3d11Tried{false};

// Producer→consumer GPU fence. After each VP Blt we signal a
// monotonically increasing value and block (via OS event, no CPU
// spin) until the GPU reports the work complete. Without this the
// consumer's wglDXLockObjectsNV on its own (separate) D3D11 device
// has no way to wait for our writes, and the GL sample can land
// somewhere between frame N's old content and frame N+1's new
// content — manifesting as "prev and next frame fighting".
static ID3D11Fence*    g_ff_fence       = nullptr;
static HANDLE          g_ff_fenceEvent  = nullptr;
static uint64_t        g_ff_fenceValue  = 0;

static bool ensure_d3d11_device() {
    // If we cached a device from an earlier player-init in this
    // process, sanity-check that the device is still live. After a
    // GPU TDR or driver crash the cached pointer may still be
    // non-null but GetDeviceRemovedReason returns failed — using it
    // for new decoder contexts would feed the dead device to ffmpeg
    // and crash the GPU driver on first decode call. Drop the dead
    // device so we either re-borrow Skia's (which may have been
    // reset) or create a fresh one.
    if (g_ff_d3d11Device) {
        if (FAILED(g_ff_d3d11Device->GetDeviceRemovedReason())) {
            g_ff_d3d11Device->Release();
            g_ff_d3d11Device = nullptr;
            if (g_ff_d3d11Context) {
                g_ff_d3d11Context->Release();
                g_ff_d3d11Context = nullptr;
            }
            // Force re-try of the borrow / create path below.
            g_ff_d3d11Tried.store(false);
        } else {
            return true;
        }
    }
    if (g_ff_d3d11Tried.exchange(true)) return g_ff_d3d11Device != nullptr;

    // Borrow the consumer's (Skia interop) D3D11 device so producer
    // and consumer share a single device. D3D11 hazard tracking is
    // per-device — sharing means our VP writes are AUTOMATICALLY
    // serialised against the consumer's GL reads via
    // wglDXLockObjectsNV. With two separate devices the lock only
    // synced against the consumer device's queue and the producer's
    // pending Blt could still be in flight → flicker / tearing.
    //
    // Look up the bridge's get-device export by trying several DLL
    // name variants (the build artifact name has varied across
    // Skia bridge revisions). Fall back to LoadLibrary when
    // GetModuleHandle misses, so as long as the DLL is on the
    // standard search path we'll find it.
    typedef void* (*GetSkiaDeviceFn)();
    static const char* kBridgeDllNames[] = {
        "openjfx_skia_shared.dll",
        "openjfx_skia_shared",
        "openjfx-skia-shared.dll",
        "openjfx-skia-bridge.dll",
        "openjfx-skia-bridge",
        nullptr
    };
    HMODULE bridge = nullptr;
    for (int i = 0; kBridgeDllNames[i] && !bridge; ++i) {
        bridge = GetModuleHandleA(kBridgeDllNames[i]);
    }
    if (!bridge) {
        for (int i = 0; kBridgeDllNames[i] && !bridge; ++i) {
            bridge = LoadLibraryA(kBridgeDllNames[i]);
        }
    }
    if (bridge) {
        GetSkiaDeviceFn fn = (GetSkiaDeviceFn)GetProcAddress(
            bridge, "openjfx_skia_d3d11_interop_get_device");
        if (fn) {
            void* shared = fn();
            if (shared) {
                g_ff_d3d11Device = (ID3D11Device*)shared;
                g_ff_d3d11Device->AddRef();
                g_ff_d3d11Device->GetImmediateContext(&g_ff_d3d11Context);
                ID3D11Multithread* mt = nullptr;
                if (SUCCEEDED(g_ff_d3d11Context->QueryInterface(
                        __uuidof(ID3D11Multithread), (void**)&mt)) && mt) {
                    mt->SetMultithreadProtected(TRUE);
                    mt->Release();
                }
                D3D_FEATURE_LEVEL fl = g_ff_d3d11Device->GetFeatureLevel();
                if (getenv("SKIA_MEDIA_DEBUG")) {
                    g_print("[ffmpegwrapper] borrowed Skia interop D3D11 device "
                            "(FL 0x%04x, single-device sync)\n", (unsigned)fl);
                }
                (void)fl;
                return true;
            } else if (getenv("SKIA_MEDIA_DEBUG")) {
                g_print("[ffmpegwrapper] bridge get-device returned null "
                        "(interop not ready yet, falling back to own device)\n");
            }
        } else if (getenv("SKIA_MEDIA_DEBUG")) {
            g_print("[ffmpegwrapper] bridge DLL found but get-device export "
                    "missing (falling back to own device)\n");
        }
    } else if (getenv("SKIA_MEDIA_DEBUG")) {
        g_print("[ffmpegwrapper] bridge DLL not found "
                "(falling back to own device)\n");
    }

    UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT
               | D3D11_CREATE_DEVICE_VIDEO_SUPPORT;
    D3D_FEATURE_LEVEL got;
    HRESULT hr = D3D11CreateDevice(
        nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, flags,
        nullptr, 0, D3D11_SDK_VERSION,
        &g_ff_d3d11Device, &got, &g_ff_d3d11Context);
    if (FAILED(hr) || !g_ff_d3d11Device) {
        g_print("[ffmpegwrapper] D3D11CreateDevice failed 0x%08lx\n",
                (unsigned long)hr);
        return false;
    }
    // Multithread protect — mfwrapper's experience showed that without
    // this, the producer thread's blts race against the WGL interop
    // lock on the render thread.
    ID3D11Multithread* mt = nullptr;
    if (SUCCEEDED(g_ff_d3d11Device->QueryInterface(
            __uuidof(ID3D11Multithread), (void**)&mt)) && mt) {
        mt->SetMultithreadProtected(TRUE);
        mt->Release();
    }
    if (getenv("SKIA_MEDIA_DEBUG")) {
        g_print("[ffmpegwrapper] D3D11 device ready (FL 0x%04x), multithread on\n",
                (unsigned)got);
    }
    (void)got;
    return true;
}

// Lazy fence init. ID3D11Fence requires ID3D11Device5 (D3D11.3 / Win 10).
// Returns true when a fence is available; false on older drivers (we
// fall back to just imm->Flush(), which the user may still see flicker
// from, but won't crash).
static bool ensure_fence() {
    if (g_ff_fence) return true;
    if (!g_ff_d3d11Device) return false;
    ID3D11Device5* dev5 = nullptr;
    if (FAILED(g_ff_d3d11Device->QueryInterface(
            __uuidof(ID3D11Device5), (void**)&dev5)) || !dev5) {
        return false;
    }
    HRESULT hr = dev5->CreateFence(
        0, D3D11_FENCE_FLAG_NONE, __uuidof(ID3D11Fence),
        (void**)&g_ff_fence);
    dev5->Release();
    if (FAILED(hr) || !g_ff_fence) {
        g_ff_fence = nullptr;
        return false;
    }
    g_ff_fenceEvent = ::CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (!g_ff_fenceEvent) {
        g_ff_fence->Release(); g_ff_fence = nullptr;
        return false;
    }
    if (getenv("SKIA_MEDIA_DEBUG")) {
        g_print("[ffmpegwrapper] producer fence enabled "
                "(cross-device sync via OS event)\n");
    }
    return true;
}

// Block the producer thread until all GPU work submitted to the
// immediate context up to this point is complete. Uses an
// ID3D11Fence + OS event so there's no CPU spin — the thread sleeps
// until the GPU signals.
static void producer_fence_wait() {
    if (!ensure_fence()) return;
    ID3D11DeviceContext4* ctx4 = nullptr;
    if (FAILED(g_ff_d3d11Context->QueryInterface(
            __uuidof(ID3D11DeviceContext4), (void**)&ctx4)) || !ctx4) {
        return;
    }
    uint64_t v = ++g_ff_fenceValue;
    HRESULT hr = ctx4->Signal(g_ff_fence, v);
    ctx4->Release();
    if (FAILED(hr)) return;
    // Skip the wait if the GPU already completed this signal.
    if (g_ff_fence->GetCompletedValue() >= v) return;
    if (FAILED(g_ff_fence->SetEventOnCompletion(v, g_ff_fenceEvent))) return;
    ::WaitForSingleObject(g_ff_fenceEvent, INFINITE);
}

// =============================================================================
// VP + 4-slot RGBA pool.
//
// 4K playback at 60fps churns a 4 MB texture per frame; allocating
// fresh ID3D11Texture2D + WGL register every frame burns enough CPU
// and driver state to matter. The pool reuses RGBA targets, cycling
// through 4 slots. The classic "slot reused while consumer still has
// it" race is closed by two invariants that live elsewhere:
//
//   1. The consumer-side interop layer AddRefs the underlying
//      ID3D11Texture2D on register and Releases on unregister, so
//      while any GL alias is live the texture's refcount is ≥ 2
//      (pool + interop). pool_acquire only recycles a slot when its
//      refcount has dropped to 1 (pool-only), guaranteed-idle.
//   2. The interop layer dedupes by ID3D11Texture2D pointer with
//      its own refCount, so the same pool slot reappearing across
//      frames shares a single WGL handle — no flicker from dual
//      aliases on the same DX object, and no per-frame WGL register
//      cost in steady state.
// =============================================================================

struct VpState {
    ID3D11VideoDevice*              videoDevice = nullptr;
    ID3D11VideoContext*             videoContext = nullptr;
    ID3D11VideoProcessorEnumerator* enumerator = nullptr;
    ID3D11VideoProcessor*           processor = nullptr;
    UINT                            srcWidth  = 0;
    UINT                            srcHeight = 0;
    UINT                            dstWidth  = 0;
    UINT                            dstHeight = 0;
    DXGI_FORMAT                     srcFormat = DXGI_FORMAT_UNKNOWN;
};
static VpState g_vp{};

static ID3D11Texture2D* create_rgba_target(UINT w, UINT h) {
    D3D11_TEXTURE2D_DESC d = {};
    d.Width = w; d.Height = h;
    d.MipLevels = 1; d.ArraySize = 1;
    d.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    d.SampleDesc.Count = 1;
    d.Usage = D3D11_USAGE_DEFAULT;
    d.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    d.MiscFlags = D3D11_RESOURCE_MISC_SHARED;
    ID3D11Texture2D* tex = nullptr;
    HRESULT hr = g_ff_d3d11Device->CreateTexture2D(&d, nullptr, &tex);
    if (FAILED(hr) || !tex) {
        g_print("[ffmpegwrapper] CreateTexture2D(RGBA %ux%u) failed 0x%08lx\n",
                w, h, (unsigned long)hr);
        return nullptr;
    }
    return tex;
}

struct RgbaPool {
    static constexpr int N = 4;
    ID3D11Texture2D* textures[N] = {};
    UINT             width  = 0;
    UINT             height = 0;
    int              next   = 0;
};
static RgbaPool g_pool{};

static void release_pool() {
    for (int i = 0; i < RgbaPool::N; ++i) {
        if (g_pool.textures[i]) {
            g_pool.textures[i]->Release();
            g_pool.textures[i] = nullptr;
        }
    }
    g_pool.width = g_pool.height = 0;
    g_pool.next = 0;
}

// Diagnostic probe — returns true if the D3D11 device is removed
// or hung. Used in the chain function's HW-failure branch to add
// a "device lost" annotation to the dropped-frame log line, so a
// run-the-app investigation can tell apart "transient resize hiccup"
// from "GPU actually went away."
//
// We deliberately do NOT auto-tear-down on a "lost" probe here:
// the AVCodecContext's hw_device_ctx still wraps the ORIGINAL
// D3D11 device, so rebuilding ours wouldn't actually help — the
// decoder would keep emitting frames whose textures are on the
// old device. A full recovery would have to recreate the avcodec
// context, which is a bigger surgery.
static bool d3d11_device_is_lost() {
    if (!g_ff_d3d11Device) return true;
    HRESULT removed = g_ff_d3d11Device->GetDeviceRemovedReason();
    return FAILED(removed);
}

static ULONG probe_refcount(ID3D11Texture2D* t) {
    t->AddRef();
    return t->Release();
}

static ID3D11Texture2D* pool_acquire(UINT w, UINT h) {
    if (g_pool.width != w || g_pool.height != h) {
        release_pool();
        g_pool.width = w; g_pool.height = h;
    }
    for (int i = 0; i < RgbaPool::N; ++i) {
        int slot = (g_pool.next + i) % RgbaPool::N;
        ID3D11Texture2D* tex = g_pool.textures[slot];
        if (tex && probe_refcount(tex) == 1) {
            g_pool.next = (slot + 1) % RgbaPool::N;
            tex->AddRef();   // caller-owned ref
            return tex;
        }
    }
    ID3D11Texture2D* tex = create_rgba_target(w, h);
    if (!tex) return nullptr;
    for (int i = 0; i < RgbaPool::N; ++i) {
        if (!g_pool.textures[i]) {
            g_pool.textures[i] = tex;
            tex->AddRef();   // pool ref (caller-owned already +1 from Create)
            return tex;
        }
    }
    return tex;   // overflow — un-pooled, caller still owns its ref
}

static bool ensure_vp(UINT sW, UINT sH, UINT dW, UINT dH, DXGI_FORMAT fmt) {
    if (!g_ff_d3d11Device) return false;
    if (g_vp.processor
        && g_vp.srcWidth == sW && g_vp.srcHeight == sH
        && g_vp.dstWidth == dW && g_vp.dstHeight == dH
        && g_vp.srcFormat == fmt) return true;

    if (g_vp.processor)  { g_vp.processor->Release();  g_vp.processor  = nullptr; }
    if (g_vp.enumerator) { g_vp.enumerator->Release(); g_vp.enumerator = nullptr; }
    if (!g_vp.videoDevice) {
        if (FAILED(g_ff_d3d11Device->QueryInterface(
                __uuidof(ID3D11VideoDevice), (void**)&g_vp.videoDevice))) return false;
    }
    if (!g_vp.videoContext) {
        ID3D11DeviceContext* imm = nullptr;
        g_ff_d3d11Device->GetImmediateContext(&imm);
        if (!imm) return false;
        HRESULT hr = imm->QueryInterface(
            __uuidof(ID3D11VideoContext), (void**)&g_vp.videoContext);
        imm->Release();
        if (FAILED(hr)) return false;
    }
    D3D11_VIDEO_PROCESSOR_CONTENT_DESC desc = {};
    desc.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
    desc.InputFrameRate.Numerator = 60; desc.InputFrameRate.Denominator = 1;
    desc.InputWidth = sW; desc.InputHeight = sH;
    desc.OutputFrameRate.Numerator = 60; desc.OutputFrameRate.Denominator = 1;
    desc.OutputWidth = dW; desc.OutputHeight = dH;
    desc.Usage = D3D11_VIDEO_USAGE_PLAYBACK_NORMAL;
    if (FAILED(g_vp.videoDevice->CreateVideoProcessorEnumerator(
            &desc, &g_vp.enumerator))) return false;
    if (FAILED(g_vp.videoDevice->CreateVideoProcessor(
            g_vp.enumerator, 0, &g_vp.processor))) {
        g_vp.enumerator->Release(); g_vp.enumerator = nullptr;
        return false;
    }
    // Output side is invariant: full-range RGB. The input-side YCbCr
    // matrix + nominal range depend on the source colorspace and are
    // applied per-frame in run_vp_blt() from AVFrame's colorspace +
    // color_range fields. A wrong matrix here (e.g. BT.709 on a BT.601
    // SD stream, or limited on a full-range JPEG-color file) shifts
    // greens/oranges → the "amber tint" symptom users see on some
    // content.
    D3D11_VIDEO_PROCESSOR_COLOR_SPACE out{};
    out.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_0_255;
    g_vp.videoContext->VideoProcessorSetOutputColorSpace(g_vp.processor, &out);

    g_vp.srcWidth = sW; g_vp.srcHeight = sH;
    g_vp.dstWidth = dW; g_vp.dstHeight = dH;
    g_vp.srcFormat = fmt;
    if (getenv("SKIA_MEDIA_DEBUG")) {
        g_print("[ffmpegwrapper.vp] ready src=%ux%u (fmt=%u) → RGBA %ux%u\n",
                sW, sH, (unsigned)fmt, dW, dH);
    }
    return true;
}

// Manual override for the YCbCr matrix when an individual file's
// metadata lies (or is missing) about which color matrix it was
// encoded with. Set via env var OPENJFX_MEDIA_YUV_MATRIX:
//   auto              — keep engine detection (default)
//   601 / smpte170    — ITU-R BT.601 / SMPTE 170M (SD / NTSC)
//   bt470bg / pal     — ITU-R BT.470BG (PAL SD, equivalent to BT.601)
//   709 / bt709       — ITU-R BT.709 (HD)
//   2020 / bt2020     — ITU-R BT.2020 non-constant luminance (UHD)
//   2020c / bt2020cl  — ITU-R BT.2020 constant luminance (rare)
//   smpte240          — SMPTE 240M (early NTSC HDTV)
//   fcc               — FCC Title 47 (legacy NTSC 1953)
//   rgb               — Identity (RGB; bypass YUV→RGB matrix)
// Read once + cached. Maps onto the D3D11 VP YCbCr_Matrix int.
//
// VP's matrix slot only honours BT.601 (0) / BT.709 (1) historically;
// BT.2020 needs the ColorSpace1 API (flagged for follow-up). Until
// then BT.2020/240M/FCC alias to BT.709 (closest match for HD).
static int yuv_matrix_override() {
    static int cached = -2;
    if (cached != -2) return cached;
    const char* env = getenv("OPENJFX_MEDIA_YUV_MATRIX");
    cached = -1;
    if (env) {
        if      (strstr(env, "rgb"))                                cached = 0; // identity → BT.601 row mix
        else if (strstr(env, "601") || strstr(env, "smpte170"))     cached = 0;
        else if (strstr(env, "470") || strstr(env, "pal"))          cached = 0;
        else if (strstr(env, "fcc"))                                cached = 0;
        else if (strstr(env, "709"))                                cached = 1;
        else if (strstr(env, "2020") || strstr(env, "bt2020"))      cached = 1; // VP fallback to 709
        else if (strstr(env, "240"))                                cached = 1; // SMPTE 240M ~ 709
    }
    if (cached >= 0) {
        g_print("[ffmpegwrapper.color] manual YUV matrix override: %s "
                "(env='%s')\n",
                cached == 0 ? "BT.601" : "BT.709", env);
    }
    return cached;
}

// Auto-detect the YCbCr matrix from ALL available AVFrame color
// metadata, not just `colorspace`. Many real-world files leave
// `colorspace` as AVCOL_SPC_UNSPECIFIED but still set valid
// `color_primaries` or `color_trc` fields — those carry the same
// signal. Symptom of misdetection is an orange/amber tint on skin
// tones (BT.709 matrix on BT.601 content) or muddied colours
// (limited applied to full-range).
//
// Priority order:
//   1. Manual override via OPENJFX_MEDIA_YUV_MATRIX env var (wins).
//   2. `colorspace` (most explicit).
//   3. `color_primaries` (commonly set when colorspace isn't).
//   4. `color_trc` (transfer/gamma; last-resort metadata signal).
//   5. Resolution heuristic (SD < 720p → BT.601, HD+ → BT.709).
//
// BT.2020 detected via any of the three is currently mapped to BT.709
// matrix (the D3D11 VP path lacks BT.2020 wiring — flagged for HDR
// follow-up).
static int detect_yuv_matrix(int colorspace, int color_primaries,
                             int color_trc, int srcHeight) {
    // 2. colorspace field
    switch (colorspace) {
        case AVCOL_SPC_BT709:       return 1;
        case AVCOL_SPC_BT470BG:
        case AVCOL_SPC_SMPTE170M:
        case AVCOL_SPC_SMPTE240M:   return 0;
        case AVCOL_SPC_BT2020_NCL:
        case AVCOL_SPC_BT2020_CL:   return 1; // BT.2020 → mapped to 709 for now
        default: break;             // UNSPECIFIED / RGB / RESERVED — fall through
    }
    // 3. color_primaries — independent metadata signal
    switch (color_primaries) {
        case AVCOL_PRI_BT709:       return 1;
        case AVCOL_PRI_BT470M:      // NTSC 1953 → BT.601 family
        case AVCOL_PRI_BT470BG:     // PAL BT.601
        case AVCOL_PRI_SMPTE170M:   // NTSC SMPTE-C BT.601
        case AVCOL_PRI_SMPTE240M:   return 0;
        case AVCOL_PRI_BT2020:      return 1;
        default: break;
    }
    // 4. color_trc — transfer characteristic. Tighter signal than
    //    primaries on some malformed files where primaries got
    //    cleared but the transfer was kept.
    switch (color_trc) {
        case AVCOL_TRC_BT709:        return 1;
        case AVCOL_TRC_GAMMA22:      // BT.470M (NTSC) → BT.601
        case AVCOL_TRC_GAMMA28:      // BT.470BG (PAL) → BT.601
        case AVCOL_TRC_SMPTE170M:    // SMPTE 170M → BT.601
        case AVCOL_TRC_SMPTE240M:    return 0;
        case AVCOL_TRC_BT2020_10:
        case AVCOL_TRC_BT2020_12:    return 1;
        default: break;
    }
    // 5. Resolution heuristic (last resort). Caller may still
    //    consult the container-level colorimetry when the bitstream
    //    is fully unspec'd; see apply_stream_colorspace below.
    return (srcHeight < 720) ? 0 : 1;
}

// Map AVFrame's color metadata to D3D11 VideoProcessor stream
// colorspace, with the multi-field auto-detection in
// detect_yuv_matrix. Override via OPENJFX_MEDIA_YUV_MATRIX=601|709
// when even all three metadata fields lie.
// Resolve a DXGI_COLOR_SPACE_TYPE for the modern ColorSpace1 API.
// Mapping covers every standard input we might see — BT.601 / BT.709 /
// BT.2020 each with studio-range or full-range variants, plus PQ
// (HDR10) and HLG (broadcast HDR) for BT.2020. Returns -1 when no
// usable mapping exists (caller falls back to the legacy
// VideoProcessorSetStreamColorSpace path).
//
// User-visible token (OPENJFX_MEDIA_YUV_COLORSPACE env var) bypasses
// auto-detect and forces a specific enum — that's the "give me every
// option" knob.
static DXGI_COLOR_SPACE_TYPE resolve_dxgi_color_space(
    int matrix, int range, int primaries, int trc, int srcHeight)
{
    // Manual override via env var.
    const char* env = getenv("OPENJFX_MEDIA_YUV_COLORSPACE");
    if (env) {
        if (strstr(env, "bt709") && strstr(env, "full"))
            return DXGI_COLOR_SPACE_YCBCR_FULL_G22_LEFT_P709;
        if (strstr(env, "bt709"))
            return DXGI_COLOR_SPACE_YCBCR_STUDIO_G22_LEFT_P709;
        if (strstr(env, "bt601") && strstr(env, "full"))
            return DXGI_COLOR_SPACE_YCBCR_FULL_G22_LEFT_P601;
        if (strstr(env, "bt601") || strstr(env, "smpte170"))
            return DXGI_COLOR_SPACE_YCBCR_STUDIO_G22_LEFT_P601;
        if (strstr(env, "bt2020") && strstr(env, "pq"))
            return DXGI_COLOR_SPACE_YCBCR_STUDIO_G2084_LEFT_P2020;
        if (strstr(env, "bt2020") && strstr(env, "hlg"))
            return DXGI_COLOR_SPACE_YCBCR_STUDIO_GHLG_TOPLEFT_P2020;
        if (strstr(env, "bt2020") && strstr(env, "full"))
            return DXGI_COLOR_SPACE_YCBCR_FULL_G22_LEFT_P2020;
        if (strstr(env, "bt2020"))
            return DXGI_COLOR_SPACE_YCBCR_STUDIO_G22_LEFT_P2020;
        if (strstr(env, "rgb-full"))
            return DXGI_COLOR_SPACE_RGB_FULL_G22_NONE_P709;
        if (strstr(env, "rgb-studio"))
            return DXGI_COLOR_SPACE_RGB_STUDIO_G22_NONE_P709;
    }

    // Auto-detect.
    bool isHDRpq  = (trc == AVCOL_TRC_SMPTE2084);
    bool isHDRhlg = (trc == AVCOL_TRC_ARIB_STD_B67);
    bool isBT2020 = (matrix == AVCOL_SPC_BT2020_NCL ||
                     matrix == AVCOL_SPC_BT2020_CL ||
                     primaries == AVCOL_PRI_BT2020);
    bool isFullRange = (range == AVCOL_RANGE_JPEG);

    if (isHDRpq && isBT2020) {
        return isFullRange
            ? DXGI_COLOR_SPACE_YCBCR_STUDIO_G2084_LEFT_P2020
            : DXGI_COLOR_SPACE_YCBCR_STUDIO_G2084_LEFT_P2020;
    }
    if (isHDRhlg && isBT2020) {
        return DXGI_COLOR_SPACE_YCBCR_STUDIO_GHLG_TOPLEFT_P2020;
    }
    if (isBT2020) {
        return isFullRange
            ? DXGI_COLOR_SPACE_YCBCR_FULL_G22_LEFT_P2020
            : DXGI_COLOR_SPACE_YCBCR_STUDIO_G22_LEFT_P2020;
    }

    bool isBT601 = (matrix == AVCOL_SPC_BT470BG ||
                    matrix == AVCOL_SPC_SMPTE170M ||
                    matrix == AVCOL_SPC_SMPTE240M);
    if (isBT601) {
        return isFullRange
            ? DXGI_COLOR_SPACE_YCBCR_FULL_G22_LEFT_P601
            : DXGI_COLOR_SPACE_YCBCR_STUDIO_G22_LEFT_P601;
    }

    // BT.709 explicit or default for HD content.
    if (matrix == AVCOL_SPC_BT709 || srcHeight >= 720) {
        return isFullRange
            ? DXGI_COLOR_SPACE_YCBCR_FULL_G22_LEFT_P709
            : DXGI_COLOR_SPACE_YCBCR_STUDIO_G22_LEFT_P709;
    }

    // SD fallback.
    return DXGI_COLOR_SPACE_YCBCR_STUDIO_G22_LEFT_P601;
}

static const char* dxgi_color_space_name(DXGI_COLOR_SPACE_TYPE cs) {
    switch (cs) {
    case DXGI_COLOR_SPACE_RGB_FULL_G22_NONE_P709:        return "RGB_FULL_G22_P709";
    case DXGI_COLOR_SPACE_RGB_STUDIO_G22_NONE_P709:      return "RGB_STUDIO_G22_P709";
    case DXGI_COLOR_SPACE_RGB_FULL_G22_NONE_P2020:       return "RGB_FULL_G22_P2020";
    case DXGI_COLOR_SPACE_YCBCR_STUDIO_G22_LEFT_P709:    return "YCbCr_STUDIO_G22_P709";
    case DXGI_COLOR_SPACE_YCBCR_FULL_G22_LEFT_P709:      return "YCbCr_FULL_G22_P709";
    case DXGI_COLOR_SPACE_YCBCR_STUDIO_G22_LEFT_P601:    return "YCbCr_STUDIO_G22_P601";
    case DXGI_COLOR_SPACE_YCBCR_FULL_G22_LEFT_P601:      return "YCbCr_FULL_G22_P601";
    case DXGI_COLOR_SPACE_YCBCR_STUDIO_G22_LEFT_P2020:   return "YCbCr_STUDIO_G22_P2020 (BT.2020 SDR)";
    case DXGI_COLOR_SPACE_YCBCR_FULL_G22_LEFT_P2020:     return "YCbCr_FULL_G22_P2020";
    case DXGI_COLOR_SPACE_YCBCR_STUDIO_G2084_LEFT_P2020: return "YCbCr_STUDIO_PQ_P2020 (HDR10)";
    case DXGI_COLOR_SPACE_YCBCR_STUDIO_GHLG_TOPLEFT_P2020: return "YCbCr_STUDIO_HLG_P2020";
    default: return "OTHER";
    }
}

static void apply_stream_colorspace(int colorspace, int color_range,
                                    int color_primaries, int color_trc,
                                    int srcHeight,
                                    int containerYuvMatrix,
                                    int containerYuvRange) {
    D3D11_VIDEO_PROCESSOR_COLOR_SPACE in{};

    // First-frame diagnostic so the user can see what the AVFrame
    // actually reports — helps confirm auto-detect did the right
    // thing (and pinpoint a manual-override case if not). Static
    // guard: prints only on the first call per process.
    static bool firstCall = true;
    if (firstCall) {
        firstCall = false;
        const char* csName =
            colorspace == AVCOL_SPC_BT709       ? "BT709"      :
            colorspace == AVCOL_SPC_BT470BG     ? "BT470BG/601" :
            colorspace == AVCOL_SPC_SMPTE170M   ? "SMPTE170M/601" :
            colorspace == AVCOL_SPC_SMPTE240M   ? "SMPTE240M"  :
            colorspace == AVCOL_SPC_BT2020_NCL  ? "BT2020-NCL" :
            colorspace == AVCOL_SPC_BT2020_CL   ? "BT2020-CL"  :
            colorspace == AVCOL_SPC_UNSPECIFIED ? "UNSPECIFIED" :
            "OTHER";
        const char* priName =
            color_primaries == AVCOL_PRI_BT709       ? "BT709" :
            color_primaries == AVCOL_PRI_BT470M      ? "BT470M/601" :
            color_primaries == AVCOL_PRI_BT470BG     ? "BT470BG/601" :
            color_primaries == AVCOL_PRI_SMPTE170M   ? "SMPTE170M/601" :
            color_primaries == AVCOL_PRI_SMPTE240M   ? "SMPTE240M" :
            color_primaries == AVCOL_PRI_BT2020      ? "BT2020" :
            color_primaries == AVCOL_PRI_UNSPECIFIED ? "UNSPECIFIED" :
            "OTHER";
        const char* trcName =
            color_trc == AVCOL_TRC_BT709        ? "BT709" :
            color_trc == AVCOL_TRC_GAMMA22      ? "GAMMA22" :
            color_trc == AVCOL_TRC_GAMMA28      ? "GAMMA28" :
            color_trc == AVCOL_TRC_SMPTE170M    ? "SMPTE170M" :
            color_trc == AVCOL_TRC_SMPTE240M    ? "SMPTE240M" :
            color_trc == AVCOL_TRC_BT2020_10    ? "BT2020-10" :
            color_trc == AVCOL_TRC_BT2020_12    ? "BT2020-12" :
            color_trc == AVCOL_TRC_UNSPECIFIED  ? "UNSPECIFIED" :
            "OTHER";
        const char* crName =
            color_range == AVCOL_RANGE_MPEG ? "MPEG/limited" :
            color_range == AVCOL_RANGE_JPEG ? "JPEG/full"    :
            "UNSPECIFIED";
        int matrix = detect_yuv_matrix(colorspace, color_primaries,
                                       color_trc, srcHeight);
        if (getenv("SKIA_MEDIA_DEBUG")) {
            g_print("[ffmpegwrapper.color] AVFrame colorspace=%d(%s) "
                    "primaries=%d(%s) trc=%d(%s) range=%d(%s) srcH=%d → "
                    "matrix=%s\n",
                    colorspace, csName, color_primaries, priName,
                    color_trc, trcName, color_range, crName, srcHeight,
                    matrix == 1 ? "BT.709" : "BT.601");
        }
        (void)matrix;
    }

    // Resolve YCbCr matrix:
    //   1. Manual override (env var) — debugging escape hatch.
    //   2. AVFrame metadata (colorspace, primaries, trc) — bitstream-
    //      level signal, normally authoritative.
    //   3. Container's colorimetry from input caps — picked up by
    //      qtdemux from MP4's colr atom etc. The "extract correct
    //      info" path the user asked for: when the bitstream's
    //      metadata is wrong/missing, the container is the next-best
    //      source of truth. BT.2020 container hints map down to
    //      BT.709 matrix for the D3D11 VP path (no BT.2020 matrix
    //      coefficients support in this VP code path yet).
    //   4. Resolution heuristic — last resort.
    int override = yuv_matrix_override();
    if (override >= 0) {
        in.YCbCr_Matrix = (override == 1) ? 1 : 0;
    } else {
        bool avFrameMetaPresent =
            (colorspace > 1 && colorspace != AVCOL_SPC_UNSPECIFIED) ||
            (color_primaries > 1 && color_primaries != AVCOL_PRI_UNSPECIFIED) ||
            (color_trc > 1 && color_trc != AVCOL_TRC_UNSPECIFIED);
        if (avFrameMetaPresent) {
            in.YCbCr_Matrix = detect_yuv_matrix(colorspace, color_primaries,
                                                color_trc, srcHeight);
        } else if (containerYuvMatrix == 0) {
            in.YCbCr_Matrix = 0; // BT.601 from container
        } else if (containerYuvMatrix == 1) {
            in.YCbCr_Matrix = 1; // BT.709 from container
        } else if (containerYuvMatrix == 2) {
            in.YCbCr_Matrix = 1; // BT.2020 → BT.709 fallback (VP limitation)
        } else {
            // Truly nothing: fall through to detect_yuv_matrix's
            // resolution heuristic.
            in.YCbCr_Matrix = detect_yuv_matrix(colorspace, color_primaries,
                                                color_trc, srcHeight);
        }
    }

    // Nominal range. JPEG-full content arrives via AVCOL_RANGE_JPEG
    // or with `pix_fmt` ∈ {YUVJ420P, YUVJ422P, YUVJ444P} — the latter
    // is decoded but the range tag may still read UNSPECIFIED.
    // OPENJFX_MEDIA_YUV_RANGE=full|limited|auto lets the user force
    // a specific interpretation when a file's range is mislabelled.
    static int rangeOverride = -2;
    if (rangeOverride == -2) {
        const char* env = getenv("OPENJFX_MEDIA_YUV_RANGE");
        if (env && strstr(env, "full"))     rangeOverride = 1;
        else if (env && strstr(env, "limit")) rangeOverride = 0;
        else                                rangeOverride = -1;
        if (rangeOverride >= 0) {
            g_print("[ffmpegwrapper.color] manual YUV range override: %s\n",
                    rangeOverride == 1 ? "full (0-255)" : "limited (16-235)");
        }
    }
    if (rangeOverride == 1) {
        in.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_0_255;
    } else if (rangeOverride == 0) {
        in.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_16_235;
    } else if (color_range == AVCOL_RANGE_JPEG) {
        in.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_0_255;
    } else if (color_range == AVCOL_RANGE_MPEG) {
        in.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_16_235;
    } else if (containerYuvRange == 1) {
        in.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_0_255;
    } else if (containerYuvRange == 0) {
        in.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_16_235;
    } else {
        in.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_16_235;
    }

    g_vp.videoContext->VideoProcessorSetStreamColorSpace(g_vp.processor, 0, &in);

    // Modern ColorSpace1 API (D3D11.1+) — natively supports BT.2020,
    // PQ/HLG HDR, full vs studio range and primary remapping. The
    // legacy SetStreamColorSpace above only carries BT.601/BT.709 +
    // limited/full range and doesn't do primary remapping — that's
    // why files using BT.2020 wide-gamut primaries (or PQ/HLG
    // transfer) look tinted no matter which matrix we picked. We
    // call the new API whenever the runtime supports it; failure
    // here is silent — the legacy SetStreamColorSpace call above
    // is still in effect.
    ID3D11VideoContext1* vc1 = nullptr;
    if (SUCCEEDED(g_vp.videoContext->QueryInterface(
            __uuidof(ID3D11VideoContext1), (void**)&vc1)) && vc1) {
        DXGI_COLOR_SPACE_TYPE cs1 = resolve_dxgi_color_space(
            colorspace, color_range, color_primaries, color_trc, srcHeight);
        static DXGI_COLOR_SPACE_TYPE lastReported = (DXGI_COLOR_SPACE_TYPE)-1;
        if (cs1 != lastReported && getenv("SKIA_MEDIA_DEBUG")) {
            lastReported = cs1;
            g_print("[ffmpegwrapper.color] ColorSpace1 → %s (enum %d)\n",
                    dxgi_color_space_name(cs1), (int)cs1);
        }
        vc1->VideoProcessorSetStreamColorSpace1(g_vp.processor, 0, cs1);
        // Output: standard SDR sRGB-space RGB. Skia samples this as
        // RGBA8 / sRGB. HDR-pipeline output goes through the same
        // texture; HDR display tone-mapping is a follow-up.
        vc1->VideoProcessorSetOutputColorSpace1(g_vp.processor,
            DXGI_COLOR_SPACE_RGB_FULL_G22_NONE_P709);
        vc1->Release();
    }
}

static bool run_vp_blt(ID3D11Texture2D* src, UINT subres,
                       ID3D11Texture2D* dst,
                       UINT sW, UINT sH, UINT dW, UINT dH,
                       int colorspace, int color_range,
                       int color_primaries, int color_trc,
                       int containerYuvMatrix, int containerYuvRange) {
    if (!g_vp.processor || !g_vp.videoContext) return false;
    apply_stream_colorspace(colorspace, color_range,
                            color_primaries, color_trc, (int)sH,
                            containerYuvMatrix, containerYuvRange);
    ID3D11VideoProcessorInputView* iv = nullptr;
    D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC ivd = {};
    ivd.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
    ivd.Texture2D.ArraySlice = subres;
    if (FAILED(g_vp.videoDevice->CreateVideoProcessorInputView(
            src, g_vp.enumerator, &ivd, &iv))) return false;
    ID3D11VideoProcessorOutputView* ov = nullptr;
    D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC ovd = {};
    ovd.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
    if (FAILED(g_vp.videoDevice->CreateVideoProcessorOutputView(
            dst, g_vp.enumerator, &ovd, &ov))) { iv->Release(); return false; }
    D3D11_VIDEO_PROCESSOR_STREAM stream = {};
    stream.Enable = TRUE; stream.pInputSurface = iv;
    RECT sr = { 0, 0, (LONG)sW, (LONG)sH };
    RECT dr = { 0, 0, (LONG)dW, (LONG)dH };
    g_vp.videoContext->VideoProcessorSetStreamSourceRect(g_vp.processor, 0, TRUE, &sr);
    g_vp.videoContext->VideoProcessorSetStreamDestRect(g_vp.processor, 0, TRUE, &dr);
    HRESULT hr = g_vp.videoContext->VideoProcessorBlt(
        g_vp.processor, ov, 0, 1, &stream);
    ov->Release(); iv->Release();
    if (FAILED(hr)) {
        g_print("[ffmpegwrapper.vp] Blt failed 0x%08lx\n", (unsigned long)hr);
        return false;
    }
    // Submit + cross-device sync. The consumer reads through a SEPARATE
    // D3D11 device (Skia's interop device), so wglDXLockObjectsNV can't
    // serialise against our Blt — D3D11 hazard tracking is per-device.
    // Block here until our Blt completes on the GPU so the texture's
    // pixels are visible to the consumer's read. Uses an ID3D11Fence +
    // OS event (no CPU spin, ~µs overhead at idle GPU, scales with
    // actual Blt time otherwise).
    ID3D11DeviceContext* imm = nullptr;
    g_ff_d3d11Device->GetImmediateContext(&imm);
    if (imm) { imm->Flush(); imm->Release(); }
    producer_fence_wait();
    return true;
}

// Compute the producer's output dimensions for this frame, honouring
// the view-size hint published by NGMediaView via MediaTargetSize.
// Same shape as mfwrapper's pick_output_size — rounded to 256-px
// buckets so small layout jitter doesn't thrash the VP.
typedef void (*GetTargetSizeFn)(int*, int*);
static GetTargetSizeFn resolve_target_size_fn() {
    static GetTargetSizeFn cached = nullptr;
    static bool tried = false;
    if (cached || tried) return cached;
    tried = true;
    HMODULE mod = GetModuleHandleA("jfxmedia.dll");
    if (!mod) mod = GetModuleHandleA("jfxmedia");
    if (mod) cached = (GetTargetSizeFn)
        GetProcAddress(mod, "openjfx_media_get_target_size");
    return cached;
}

// Pick the producer's output (RGBA) texture dimensions.
//
// skia-fx default behaviour: lock to source dimensions, capped at 4K
// to keep VRAM bounded on 8K sources. The MediaView's on-screen size
// is NOT consulted — real video players decode at source resolution
// once and let the display compositor scale. Letting window-resize
// drive the producer's texture size triggered VP rebuild + pool churn
// per resize tick, which broke the WGL_NV_DX_interop2 layer on the
// consumer side (frames stopped reaching the screen — "frozen at
// that size" symptom).
//
// Skia handles all scaling at sample time on the GPU, so the video
// still adapts to the MediaView dimension — the texture just stops
// matching it pixel-for-pixel. For typical content (1080p/4K source
// in a 720p/1080p window) the visual difference is invisible; for
// 8K → 720p the cap at 4K still gives plenty of sampling headroom.
//
// Opt back into the dynamic-sizing path via the
// `openjfx.media.useViewSizeHint=true` system property if a specific
// workload needs the old behaviour (e.g. embedding 4K video in a
// 320×180 thumbnail where source-res VRAM is the bottleneck).
static bool use_view_size_hint() {
    static int cached = -1;
    if (cached >= 0) return cached != 0;
    const char* env = getenv("OPENJFX_MEDIA_USE_VIEW_SIZE_HINT");
    if (env && (env[0] == '1' || env[0] == 't' || env[0] == 'T')) {
        cached = 1;
    } else {
        cached = 0;
    }
    return cached != 0;
}

static void pick_output_size(UINT sW, UINT sH, UINT* dW, UINT* dH) {
    // Hard cap on the producer's RGBA output to keep VRAM bounded
    // AND to keep the per-frame wglDXRegisterObjectNV / Lock cost
    // low enough that maximize/restore doesn't stall the FX thread.
    //
    // We saw the WGL interop calls hang for hundreds of ms on
    // 2K/4K textures during a swap-chain resize, freezing the UI.
    // Capping at 1080p keeps textures at 8 MB (RGBA8) which the
    // consumer-side WGL layer handles quickly even under driver
    // pressure. Sources larger than 1080p are downscaled once by
    // the producer's VideoProcessor and then Skia samples whatever
    // size the MediaView wants — quality at typical viewing
    // distance is indistinguishable from native res.
    constexpr UINT MAX_W = 1920;
    constexpr UINT MAX_H = 1080;

    if (!use_view_size_hint()) {
        // Default path: ignore the view hint, output at source dims.
        UINT rW = sW, rH = sH;
        if (rW > MAX_W || rH > MAX_H) {
            double scale = (double)MAX_W / (double)rW;
            double sh = (double)MAX_H / (double)rH;
            if (sh < scale) scale = sh;
            rW = (UINT)((rW * scale) + 0.5);
            rH = (UINT)((rH * scale) + 0.5);
        }
        rW &= ~1u; rH &= ~1u;
        if (rW < 2) rW = 2; if (rH < 2) rH = 2;
        *dW = rW; *dH = rH;
        return;
    }

    // Legacy dynamic-sizing path — preserved behind the opt-in
    // property for backwards compatibility / A/B testing.
    int viewW = 0, viewH = 0;
    GetTargetSizeFn fn = resolve_target_size_fn();
    if (fn) fn(&viewW, &viewH);
    int capW = (int)sW, capH = (int)sH;
    if (viewW > 0 && viewW < capW) capW = viewW;
    if (viewH > 0 && viewH < capH) capH = viewH;
    const int MIN_H = 720;
    if (capH < MIN_H) capH = MIN_H;
    int minWa = (int)((double)capH * sW / sH + 0.5);
    if (capW < minWa) capW = minWa;
    double sw = (double)capW / sW;
    double sh = (double)capH / sH;
    double scale = sw < sh ? sw : sh;
    if (scale > 1.0) scale = 1.0;
    UINT w = (UINT)((sW * scale) + 0.5);
    UINT h = (UINT)((sH * scale) + 0.5);
    constexpr UINT BUCKET = 256;
    UINT rW = ((w + BUCKET - 1) / BUCKET) * BUCKET;
    UINT rH = ((h + BUCKET - 1) / BUCKET) * BUCKET;
    if (rW > sW) rW = sW; if (rH > sH) rH = sH;
    rW &= ~1u; rH &= ~1u;
    if (rW < 2) rW = 2; if (rH < 2) rH = 2;
    *dW = rW; *dH = rH;
}

// =============================================================================
// hwaccel set-up — called once per AVCodecContext open.
// =============================================================================

static enum AVPixelFormat hw_get_format(AVCodecContext* ctx,
                                         const enum AVPixelFormat* pixfmts) {
    (void)ctx;
    // Prefer D3D11 hw frames when offered.
    for (const enum AVPixelFormat* p = pixfmts; *p != AV_PIX_FMT_NONE; ++p) {
        if (*p == AV_PIX_FMT_D3D11) return AV_PIX_FMT_D3D11;
    }
    // Otherwise let ffmpeg pick its software default.
    return pixfmts[0];
}

static bool open_hwaccel(GstFfmpegWrapper* self, const AVCodec* codec) {
    const OpenJfxFfmpegFns* ff = openjfx_ffmpeg_loader_fns();
    if (!ff) return false;

    if (!ensure_d3d11_device()) return false;

    // Build an AVBufferRef wrapping our device.
    AVBufferRef* devRef = ff->av_hwdevice_ctx_alloc(AV_HWDEVICE_TYPE_D3D11VA);
    if (!devRef) {
        g_print("[ffmpegwrapper] av_hwdevice_ctx_alloc failed\n");
        return false;
    }
    AVHWDeviceContext* devCtx = (AVHWDeviceContext*)devRef->data;
    AVD3D11VADeviceContext* d3d = (AVD3D11VADeviceContext*)devCtx->hwctx;
    d3d->device = g_ff_d3d11Device;
    g_ff_d3d11Device->AddRef();   // AVHWDevice owns its ref
    d3d->device_context = g_ff_d3d11Context;
    g_ff_d3d11Context->AddRef();
    // Don't override d3d->video_device / video_context — ffmpeg fills
    // them by querying the device.

    int rc = ff->av_hwdevice_ctx_init(devRef);
    if (rc < 0) {
        g_print("[ffmpegwrapper] av_hwdevice_ctx_init failed %d\n", rc);
        ff->av_buffer_unref(&devRef);
        return false;
    }

    self->hw_device_ref = devRef;
    self->avctx->hw_device_ctx = ff->av_buffer_ref(devRef);
    self->avctx->get_format = hw_get_format;
    self->hw_ready = true;
    g_print("[ffmpegwrapper] D3D11VA hwaccel configured for %s\n", codec->name);
    return true;
}

// =============================================================================
// Decoder open / close
// =============================================================================

static enum AVCodecID jfx_to_av_codec_id(gint codec_id) {
    switch (codec_id) {
        case JFX_CODEC_ID_AV1:  return AV_CODEC_ID_AV1;
        case JFX_CODEC_ID_H265: return AV_CODEC_ID_HEVC;
#ifdef JFX_CODEC_ID_H264
        case JFX_CODEC_ID_H264: return AV_CODEC_ID_H264;
#endif
#ifdef JFX_CODEC_ID_AVC1
        case JFX_CODEC_ID_AVC1: return AV_CODEC_ID_H264;
#endif
        // skia-fx: audio mappings so the AAC-in-MKV codec probe in
        // CGstAudioPlaybackPipeline::IsCodecSupported (which sets
        // codec-id=JFX_CODEC_ID_AAC and reads is-supported) returns
        // TRUE when ffmpeg has the AAC decoder. Other audio codecs
        // (Opus/Vorbis/...) don't have a JFX codec-id and route via
        // the mimetype/CAPS path instead.
        case JFX_CODEC_ID_AAC:  return AV_CODEC_ID_AAC;
        default: return AV_CODEC_ID_NONE;
    }
}

// Map a GStreamer caps mimetype string to ffmpeg's AVCodecID. Returns
// AV_CODEC_ID_NONE for unsupported mimetypes — the caller then
// declines the stream. The mapping has to handle the upstream caps
// the JFX demuxer chain can emit; anything ffmpeg can decode beyond
// that needs both the right demuxer AND a mapping here.
static enum AVCodecID mimetype_to_av_codec_id_str(const gchar* name,
                                                    gint mpegVersion) {
    if (!name) return AV_CODEC_ID_NONE;

    if (strstr(name, "video/x-h264"))    return AV_CODEC_ID_H264;
    if (strstr(name, "video/x-h265"))    return AV_CODEC_ID_HEVC;
    if (strstr(name, "video/x-av1"))     return AV_CODEC_ID_AV1;
    if (strstr(name, "video/x-vp8"))     return AV_CODEC_ID_VP8;
    if (strstr(name, "video/x-vp9"))     return AV_CODEC_ID_VP9;
    if (strstr(name, "video/x-h263"))    return AV_CODEC_ID_H263;
    if (strstr(name, "video/x-divx") ||
        strstr(name, "video/x-xvid"))    return AV_CODEC_ID_MPEG4;
    if (strstr(name, "video/x-prores"))  return AV_CODEC_ID_PRORES;
    if (strstr(name, "video/x-dv"))      return AV_CODEC_ID_DVVIDEO;
    if (strstr(name, "video/x-mjpeg"))   return AV_CODEC_ID_MJPEG;
    if (strstr(name, "video/x-theora"))  return AV_CODEC_ID_THEORA;
    if (strstr(name, "video/x-flash-video")) return AV_CODEC_ID_FLV1;
    if (strstr(name, "video/x-wmv") ||
        strstr(name, "video/x-vc1"))     return AV_CODEC_ID_VC1;
    if (strstr(name, "video/mpeg")) {
        switch (mpegVersion) {
            case 1: return AV_CODEC_ID_MPEG1VIDEO;
            case 2: return AV_CODEC_ID_MPEG2VIDEO;
            case 4: return AV_CODEC_ID_MPEG4;
            default: return AV_CODEC_ID_MPEG2VIDEO; // best guess
        }
    }

    // ===== Audio (skia-fx) =====
    // audio/mpeg covers both MP3 (mpegversion=1, layer=3) and AAC
    // (mpegversion=2 or 4). mpegversion=1 with no layer info also
    // commonly means MP3 in practice.
    if (strstr(name, "audio/mpeg")) {
        switch (mpegVersion) {
            case 2:
            case 4:  return AV_CODEC_ID_AAC;
            case 1:
            default: return AV_CODEC_ID_MP3;
        }
    }
    if (strstr(name, "audio/x-vorbis"))  return AV_CODEC_ID_VORBIS;
    if (strstr(name, "audio/x-opus"))    return AV_CODEC_ID_OPUS;
    if (strstr(name, "audio/x-flac"))    return AV_CODEC_ID_FLAC;
    if (strstr(name, "audio/x-eac3"))    return AV_CODEC_ID_EAC3;
    if (strstr(name, "audio/x-ac3"))     return AV_CODEC_ID_AC3;
    if (strstr(name, "audio/x-wma"))     return AV_CODEC_ID_WMAV2;
    if (strstr(name, "audio/x-alaw"))    return AV_CODEC_ID_PCM_ALAW;
    if (strstr(name, "audio/x-mulaw"))   return AV_CODEC_ID_PCM_MULAW;
    if (strstr(name, "audio/aac"))       return AV_CODEC_ID_AAC;

    return AV_CODEC_ID_NONE;
}

// Detect whether the negotiated codec is audio (true) or video (false).
// Used by chain()/sink_event() to pick the right code path without
// re-parsing the mimetype each frame.
static bool av_codec_is_audio(enum AVCodecID id) {
    switch (id) {
        case AV_CODEC_ID_AAC:
        case AV_CODEC_ID_MP3:
        case AV_CODEC_ID_MP2:
        case AV_CODEC_ID_VORBIS:
        case AV_CODEC_ID_OPUS:
        case AV_CODEC_ID_FLAC:
        case AV_CODEC_ID_AC3:
        case AV_CODEC_ID_EAC3:
        case AV_CODEC_ID_WMAV1:
        case AV_CODEC_ID_WMAV2:
        case AV_CODEC_ID_WMAPRO:
        case AV_CODEC_ID_PCM_ALAW:
        case AV_CODEC_ID_PCM_MULAW:
            return true;
        default:
            return false;
    }
}

// Convenience wrapper that pulls mpegversion out of a GstStructure
// for the caps-event call path.
static enum AVCodecID mimetype_to_av_codec_id(const GstStructure* s) {
    if (!s) return AV_CODEC_ID_NONE;
    const gchar* name = gst_structure_get_name(s);
    gint v = 0;
    if (name && (strstr(name, "video/mpeg") || strstr(name, "audio/mpeg"))) {
        gst_structure_get_int(s, "mpegversion", &v);
    }
    return mimetype_to_av_codec_id_str(name, v);
}

static bool open_decoder(GstFfmpegWrapper* self) {
    const OpenJfxFfmpegFns* ff = openjfx_ffmpeg_loader_fns();
    if (!ff) return false;
    if (self->avctx) return true; // already open

    // Prefer av_codec_id resolved from caps; fall back to the legacy
    // jfx codec-id mapping when callers set the property directly
    // (the IsCodecSupported probe path does that).
    enum AVCodecID id = (enum AVCodecID)self->av_codec_id;
    if (id == AV_CODEC_ID_NONE) id = jfx_to_av_codec_id(self->codec_id);
    if (id == AV_CODEC_ID_NONE) return false;

    // Decode-mode-aware codec selection. The Win32 OS-level env block
    // is the canonical source — Media.setDecodeMethod() writes to it
    // via jfxmedia.dll's SetEnvironmentVariableA, and fxplugins.dll's
    // own CRT might have a stale snapshot, so we read OS-level here
    // too (mirrors the choice further below for hwaccel itself).
    char hwEnvBuf[16] = {0};
    bool hwForbidden = false;
#ifdef _WIN32
    DWORD got = GetEnvironmentVariableA("OPENJFX_MEDIA_USE_HWACCEL",
                                         hwEnvBuf, sizeof(hwEnvBuf));
    const char* hwEarlyEnv = (got > 0 && got < sizeof(hwEnvBuf))
                              ? hwEnvBuf
                              : getenv("OPENJFX_MEDIA_USE_HWACCEL");
#else
    const char* hwEarlyEnv = getenv("OPENJFX_MEDIA_USE_HWACCEL");
#endif
    if (hwEarlyEnv && (hwEarlyEnv[0] == '0' || hwEarlyEnv[0] == 'f'
                                          || hwEarlyEnv[0] == 'F')) {
        hwForbidden = true;
    }

    // For AV1 specifically, the choice of decoder depends on the
    // decode mode:
    //   - HW-allowed (AUTO / GPU): prefer the *native* ffmpeg av1
    //     decoder because it has the D3D11VA / NVDEC / Vulkan
    //     hwaccel hooks libdav1d lacks. On 8K content the GPU path
    //     breezes through; libdav1d caps near real-time even on
    //     strong CPUs.
    //   - CPU-only (Media.DecodeMethod.CPU): prefer libdav1d. The
    //     native "av1" decoder in some lgpl-shared ffmpeg builds
    //     fails get_format negotiation when no hw_device_ctx is set,
    //     erroring with "Failed to get pixel format". libdav1d is
    //     a self-contained SW AV1 decoder and Just Works.
    // Either way we fall back to whichever decoder
    // avcodec_find_decoder picks if the preferred name is missing.
    const AVCodec* codec = nullptr;
    if (id == AV_CODEC_ID_AV1 && ff->avcodec_find_decoder_by_name) {
        const char* preferred = hwForbidden ? "libdav1d" : "av1";
        codec = ff->avcodec_find_decoder_by_name(preferred);
        if (!codec) {
            // Preferred missing — try the other one before giving up.
            const char* alternate = hwForbidden ? "av1" : "libdav1d";
            codec = ff->avcodec_find_decoder_by_name(alternate);
        }
    }
    if (!codec) codec = ff->avcodec_find_decoder(id);
    if (!codec) {
        g_print("[ffmpegwrapper] no decoder for codec id %d\n", (int)id);
        return false;
    }
    if (getenv("SKIA_MEDIA_DEBUG")) {
        g_print("[ffmpegwrapper] picked decoder '%s' for codec id %d "
                "(hwForbidden=%d)\n", codec->name, (int)id, (int)hwForbidden);
    }
    self->avctx = ff->avcodec_alloc_context3(codec);
    if (!self->avctx) return false;

    if (self->is_audio) {
        // Audio: no hwaccel. Apply extradata + rate/channels captured
        // from the upstream caps event so libavcodec can prime the
        // decoder (Vorbis needs the 3-header extradata, Opus needs the
        // OpusHead, AAC needs the ESDS-derived AudioSpecificConfig).
        if (self->aud_extradata && self->aud_extradata_size > 0) {
            self->avctx->extradata =
                (uint8_t*)ff->av_malloc(self->aud_extradata_size + AV_INPUT_BUFFER_PADDING_SIZE);
            if (self->avctx->extradata) {
                memcpy(self->avctx->extradata,
                       self->aud_extradata, self->aud_extradata_size);
                memset(self->avctx->extradata + self->aud_extradata_size, 0,
                       AV_INPUT_BUFFER_PADDING_SIZE);
                self->avctx->extradata_size = self->aud_extradata_size;
            }
        }
        if (self->aud_rate > 0) self->avctx->sample_rate = self->aud_rate;
        if (self->aud_channels > 0) {
            // ffmpeg 6+ prefers ch_layout; older releases still use
            // `channels` only. Set both to be safe across DLL versions.
            self->avctx->ch_layout.order = AV_CHANNEL_ORDER_UNSPEC;
            self->avctx->ch_layout.nb_channels = self->aud_channels;
        }
    } else {
        // Video: hwaccel — best effort, fall back to SW if it fails.
        //
        // Hard kill switch: when OPENJFX_MEDIA_USE_HWACCEL=0/false/F,
        // skip D3D11VA entirely so the decoder outputs software I420
        // frames. Paired with -Dskia.media.d3d11ZeroCopy=false on the
        // consumer side, this completely bypasses the WGL_NV_DX_interop2
        // layer — which is required when that layer hangs the FX
        // thread on swap-chain resize.
        // Read directly from the Windows process env block, NOT from
        // the C runtime's getenv(). The CRT in fxplugins.dll snapshots
        // env vars once at DLL load (before our jfxmedia.dll setenv
        // could ever run), so getenv() here would never see a runtime
        // Media.setDecodeMethod(CPU) switch. GetEnvironmentVariableA
        // reads the live OS-level block which SetEnvironmentVariableA
        // in jfxmedia.dll updates — all DLLs in the process agree.
        // On non-Windows the CRT shares one block so plain getenv is
        // fine.
        const char* hwEnvCrt = getenv("OPENJFX_MEDIA_USE_HWACCEL");
        char hwEnvOs[16] = {0};
#ifdef _WIN32
        DWORD got = GetEnvironmentVariableA("OPENJFX_MEDIA_USE_HWACCEL",
                                             hwEnvOs, sizeof(hwEnvOs));
        const char* hwEnv = (got > 0 && got < sizeof(hwEnvOs)) ? hwEnvOs : hwEnvCrt;
#else
        const char* hwEnv = hwEnvCrt;
#endif
        bool hwAllowed = !(hwEnv && (hwEnv[0] == '0' || hwEnv[0] == 'f' || hwEnv[0] == 'F'));
        if (getenv("SKIA_MEDIA_DEBUG")) {
            g_print("[ffmpegwrapper] OPENJFX_MEDIA_USE_HWACCEL: crt=%s os=%s -> %s\n",
                    hwEnvCrt ? hwEnvCrt : "(null)",
                    hwEnvOs[0] ? hwEnvOs : "(null)",
                    hwAllowed ? "hw-allowed" : "sw-only");
        }
        if (hwAllowed) {
            open_hwaccel(self, codec);
        }
    }

    if (ff->avcodec_open2(self->avctx, codec, NULL) < 0) {
        g_print("[ffmpegwrapper] avcodec_open2 failed for %s\n", codec->name);
        ff->avcodec_free_context(&self->avctx);
        if (self->hw_device_ref) ff->av_buffer_unref(&self->hw_device_ref);
        return false;
    }
    self->pkt      = ff->av_packet_alloc();
    self->sw_frame = ff->av_frame_alloc();
    g_print("[ffmpegwrapper] opened %s (%s)\n",
            codec->name,
            self->is_audio ? "audio"
                : (self->hw_ready ? "D3D11VA hw" : "software"));
    return true;
}

static void close_decoder(GstFfmpegWrapper* self) {
    const OpenJfxFfmpegFns* ff = openjfx_ffmpeg_loader_fns();
    if (ff) {
        if (self->pkt)      ff->av_packet_free(&self->pkt);
        if (self->sw_frame) ff->av_frame_free(&self->sw_frame);
        if (self->avctx)    ff->avcodec_free_context(&self->avctx);
        if (self->hw_device_ref) ff->av_buffer_unref(&self->hw_device_ref);
    }
    self->hw_ready = false;
    if (self->aud_extradata) {
        g_free(self->aud_extradata);
        self->aud_extradata = nullptr;
        self->aud_extradata_size = 0;
    }
}

// =============================================================================
// HW frame → meta-only GstBuffer + D3D11 meta
// =============================================================================

static GstBuffer* make_meta_only_buffer(int width, int height,
                                         GstClockTime ts, GstClockTime dur) {
    int chromaW = (width  + 1) / 2;
    int chromaH = (height + 1) / 2;
    gsize sz = (gsize)width * height + 2 * (gsize)chromaW * chromaH;
    GstBuffer* buf = gst_buffer_new_allocate(NULL, sz, NULL);
    if (!buf) return nullptr;
    GST_BUFFER_TIMESTAMP(buf) = ts;
    GST_BUFFER_DURATION(buf)  = dur;
    return buf;
}

// =============================================================================
// SW frame → I420 GstBuffer
// =============================================================================

static GstBuffer* pack_sw_frame_to_i420(AVFrame* frame,
                                         GstClockTime ts, GstClockTime dur) {
    int w = frame->width;
    int h = frame->height;
    int chromaW = (w + 1) / 2;
    int chromaH = (h + 1) / 2;
    // Guard: this packer assumes a 3-plane planar YUV source (4:2:0 / 4:2:2 /
    // 4:4:4, 8- or 10/12/16-bit). NV12, monochrome, or any 2-plane / 1-plane
    // format has a null or undersized data[1]/data[2]; a get_format hook should
    // keep those off this path, but validate defensively so a surprise format
    // degrades (drop frame -> caller pushes nothing) instead of dereferencing a
    // null plane and segfaulting the decode thread ("errors never kill JVM").
    if (w <= 0 || h <= 0
        || !frame->data[0] || !frame->data[1] || !frame->data[2]
        || frame->linesize[0] <= 0 || frame->linesize[1] <= 0
        || frame->linesize[2] <= 0) {
        return nullptr;
    }
    gsize sz = (gsize)w * h + 2 * (gsize)chromaW * chromaH;
    GstBuffer* buf = gst_buffer_new_allocate(NULL, sz, NULL);
    if (!buf) return nullptr;
    GstMapInfo info = {};
    if (!gst_buffer_map(buf, &info, GST_MAP_WRITE)) {
        gst_buffer_unref(buf);
        return nullptr;
    }
    guint8* yDst = info.data;
    guint8* uDst = yDst + (gsize)w * h;
    guint8* vDst = uDst + (gsize)chromaW * chromaH;

    // 10/12-bit planar formats (YUV420P10LE=62, YUV420P12LE, etc.) store
    // each sample as a little-endian uint16_t with the value in the low
    // bits. Downconvert to 8-bit I420 by shifting right (bit_depth - 8).
    // Without this the high byte of every 10-bit sample becomes the Y
    // channel and the output looks like green kaleidoscope corruption.
    int fmt = frame->format;
    int shift = 0;
    switch (fmt) {
        case AV_PIX_FMT_YUV420P10LE: case AV_PIX_FMT_YUV422P10LE:
        case AV_PIX_FMT_YUV444P10LE:
            shift = 2; break;
        case AV_PIX_FMT_YUV420P12LE: case AV_PIX_FMT_YUV422P12LE:
        case AV_PIX_FMT_YUV444P12LE:
            shift = 4; break;
        case AV_PIX_FMT_YUV420P16LE: case AV_PIX_FMT_YUV422P16LE:
        case AV_PIX_FMT_YUV444P16LE:
            shift = 8; break;
        default: shift = 0; break;
    }

    if (shift > 0) {
        for (int y = 0; y < h; ++y) {
            const uint16_t* src = (const uint16_t*)(frame->data[0]
                + (gsize)y * frame->linesize[0]);
            guint8* dst = yDst + (gsize)y * w;
            for (int x = 0; x < w; ++x) dst[x] = (guint8)(src[x] >> shift);
        }
        for (int y = 0; y < chromaH; ++y) {
            const uint16_t* uSrc = (const uint16_t*)(frame->data[1]
                + (gsize)y * frame->linesize[1]);
            const uint16_t* vSrc = (const uint16_t*)(frame->data[2]
                + (gsize)y * frame->linesize[2]);
            guint8* uRow = uDst + (gsize)y * chromaW;
            guint8* vRow = vDst + (gsize)y * chromaW;
            for (int x = 0; x < chromaW; ++x) {
                uRow[x] = (guint8)(uSrc[x] >> shift);
                vRow[x] = (guint8)(vSrc[x] >> shift);
            }
        }
    } else {
        for (int y = 0; y < h; ++y) {
            memcpy(yDst + (gsize)y * w,
                frame->data[0] + (gsize)y * frame->linesize[0], w);
        }
        for (int y = 0; y < chromaH; ++y) {
            memcpy(uDst + (gsize)y * chromaW,
                frame->data[1] + (gsize)y * frame->linesize[1], chromaW);
            memcpy(vDst + (gsize)y * chromaW,
                frame->data[2] + (gsize)y * frame->linesize[2], chromaW);
        }
    }
    gst_buffer_unmap(buf, &info);
    GST_BUFFER_TIMESTAMP(buf) = ts;
    GST_BUFFER_DURATION(buf)  = dur;
    return buf;
}

// =============================================================================
// Audio path (skia-fx) — AVFrame → interleaved S16LE PCM GstBuffer
//
// libavcodec emits audio in whichever sample format the codec uses
// natively: Vorbis/Opus/AAC are typically float-planar (FLTP), MP3 is
// usually S16P, FLAC is S16P or S32P, AC-3 is FLTP. The downstream
// audio bin includes audioconvert + the JFX equalizer/spectrum/sink
// chain, all of which work in S16. Converting to S16LE here is the
// simplest universal hand-off; audioconvert later up-converts to F32
// for the equalizer when needed.
//
// We hand-convert (no libswresample dep) because the loader's swr
// table isn't populated and the maths is trivial for the common
// formats. Unsupported sample formats fail loudly; they're rare in
// practice for the codecs we care about.
// =============================================================================

static inline int16_t clamp_to_s16(float v) {
    v *= 32768.0f;
    if (v >  32767.0f) return  32767;
    if (v < -32768.0f) return -32768;
    return (int16_t)v;
}

static GstBuffer* pack_audio_frame_to_s16le(const OpenJfxFfmpegFns* ff,
                                            AVFrame* f,
                                            GstClockTime ts, GstClockTime dur) {
    (void)ff;
    int channels = f->ch_layout.nb_channels;
    if (channels <= 0) channels = 1;
    int nb = f->nb_samples;
    if (nb <= 0) return nullptr;

    gsize sz = (gsize)nb * channels * sizeof(int16_t);
    GstBuffer* buf = gst_buffer_new_allocate(NULL, sz, NULL);
    if (!buf) return nullptr;
    GstMapInfo info = {};
    if (!gst_buffer_map(buf, &info, GST_MAP_WRITE)) {
        gst_buffer_unref(buf);
        return nullptr;
    }
    int16_t* out = (int16_t*)info.data;

    int fmt = f->format;
    switch (fmt) {
    case AV_SAMPLE_FMT_S16: {
        // Already interleaved S16 — straight copy.
        memcpy(out, f->extended_data[0], sz);
        break;
    }
    case AV_SAMPLE_FMT_S16P: {
        for (int s = 0; s < nb; ++s) {
            for (int c = 0; c < channels; ++c) {
                out[s * channels + c] = ((const int16_t*)f->extended_data[c])[s];
            }
        }
        break;
    }
    case AV_SAMPLE_FMT_FLT: {
        const float* src = (const float*)f->extended_data[0];
        gsize total = (gsize)nb * channels;
        for (gsize i = 0; i < total; ++i) out[i] = clamp_to_s16(src[i]);
        break;
    }
    case AV_SAMPLE_FMT_FLTP: {
        for (int s = 0; s < nb; ++s) {
            for (int c = 0; c < channels; ++c) {
                out[s * channels + c] =
                    clamp_to_s16(((const float*)f->extended_data[c])[s]);
            }
        }
        break;
    }
    case AV_SAMPLE_FMT_S32: {
        const int32_t* src = (const int32_t*)f->extended_data[0];
        gsize total = (gsize)nb * channels;
        for (gsize i = 0; i < total; ++i) out[i] = (int16_t)(src[i] >> 16);
        break;
    }
    case AV_SAMPLE_FMT_S32P: {
        for (int s = 0; s < nb; ++s) {
            for (int c = 0; c < channels; ++c) {
                out[s * channels + c] =
                    (int16_t)(((const int32_t*)f->extended_data[c])[s] >> 16);
            }
        }
        break;
    }
    case AV_SAMPLE_FMT_U8: {
        const uint8_t* src = (const uint8_t*)f->extended_data[0];
        gsize total = (gsize)nb * channels;
        for (gsize i = 0; i < total; ++i)
            out[i] = (int16_t)((int)src[i] - 128) << 8;
        break;
    }
    case AV_SAMPLE_FMT_U8P: {
        for (int s = 0; s < nb; ++s) {
            for (int c = 0; c < channels; ++c) {
                out[s * channels + c] = (int16_t)
                    ((int)((const uint8_t*)f->extended_data[c])[s] - 128) << 8;
            }
        }
        break;
    }
    default:
        // DBL / DBLP and exotic formats — fail cleanly. The pipeline
        // will see GST_FLOW_ERROR and surface the audio-codec error.
        gst_buffer_unmap(buf, &info);
        gst_buffer_unref(buf);
        g_print("[ffmpegwrapper.audio] unsupported sample format %d\n", fmt);
        return nullptr;
    }
    gst_buffer_unmap(buf, &info);
    GST_BUFFER_TIMESTAMP(buf) = ts;
    GST_BUFFER_DURATION(buf)  = dur;
    return buf;
}

// =============================================================================
// Chain: GstBuffer → AVPacket → frame(s) → push downstream
// =============================================================================

static GstFlowReturn gst_ffmpegwrapper_chain(GstPad* pad, GstObject* parent,
                                              GstBuffer* in) {
    GstFfmpegWrapper* self = GST_FFMPEGWRAPPER(parent);
    const OpenJfxFfmpegFns* ff = openjfx_ffmpeg_loader_fns();
    if (!ff || !self->is_supported || !open_decoder(self)) {
        gst_buffer_unref(in);
        return GST_FLOW_ERROR;
    }

    GstMapInfo info = {};
    if (!gst_buffer_map(in, &info, GST_MAP_READ)) {
        gst_buffer_unref(in);
        return GST_FLOW_ERROR;
    }

    // Build a packet that references the input buffer's bytes. We
    // make a fresh allocation rather than alias — the input GstBuffer
    // is unref'd before the GPU finishes with it on the hw path.
    ff->av_packet_unref(self->pkt);
    if (ff->av_new_packet(self->pkt, (int)info.size) < 0) {
        gst_buffer_unmap(in, &info);
        gst_buffer_unref(in);
        return GST_FLOW_ERROR;
    }
    memcpy(self->pkt->data, info.data, info.size);
    self->pkt->pts = GST_BUFFER_TIMESTAMP_IS_VALID(in)
        ? (int64_t)(GST_BUFFER_TIMESTAMP(in) / 100) : AV_NOPTS_VALUE;
    GstClockTime in_ts  = GST_BUFFER_TIMESTAMP(in);
    GstClockTime in_dur = GST_BUFFER_DURATION(in);
    gst_buffer_unmap(in, &info);
    gst_buffer_unref(in);

    int rc = ff->avcodec_send_packet(self->avctx, self->pkt);
    if (rc < 0 && rc != AVERROR(EAGAIN) && rc != AVERROR_EOF) {
        g_print("[ffmpegwrapper] send_packet error %d\n", rc);
        return GST_FLOW_ERROR;
    }

    AVFrame* frame = ff->av_frame_alloc();
    if (!frame) return GST_FLOW_ERROR;
    GstFlowReturn ret = GST_FLOW_OK;
    for (;;) {
        rc = ff->avcodec_receive_frame(self->avctx, frame);
        if (rc == AVERROR(EAGAIN) || rc == AVERROR_EOF) break;
        if (rc < 0) {
            g_print("[ffmpegwrapper] receive_frame error %d\n", rc);
            ret = GST_FLOW_ERROR;
            break;
        }
        // Negotiate src caps before pushing the first buffer. Branch
        // on audio vs video: the audio path emits S16LE PCM caps with
        // rate + channels from the decoder context (post-open), the
        // video path emits I420 with strides/offsets matching the
        // tight-packed GstBuffer we'll push below.
        //
        // The video path also re-negotiates mid-stream when the decoded
        // frame size changes (adaptive / resolution-switching streams):
        // the I420 caps below carry size-dependent offsets and strides,
        // so a new-size buffer pushed under the previous caps would be
        // read with stale plane offsets. Re-emitting caps for the new
        // size keeps the downstream consumer in sync. Audio caps are
        // one-shot (size-independent). The normal fixed-resolution case
        // is unaffected: dimensions match, so no re-push occurs.
        bool videoSizeChanged =
            !self->is_audio && self->caps_negotiated
            && frame->width > 0 && frame->height > 0
            && (frame->width  != self->negotiated_width
             || frame->height != self->negotiated_height);
        if (!self->caps_negotiated || videoSizeChanged) {
            if (self->is_audio) {
                int rate = self->avctx->sample_rate;
                int channels = self->avctx->ch_layout.nb_channels;
                if (channels <= 0) channels = self->aud_channels;
                if (rate <= 0)     rate = self->aud_rate;
                if (channels <= 0) channels = 2;
                if (rate <= 0)     rate = 48000;
                GstCaps* caps = gst_caps_new_simple("audio/x-raw",
                    "format",   G_TYPE_STRING, "S16LE",
                    "layout",   G_TYPE_STRING, "interleaved",
                    "rate",     G_TYPE_INT,    rate,
                    "channels", G_TYPE_INT,    channels,
                    NULL);
                GstEvent* capsEvent = gst_event_new_caps(caps);
                if (capsEvent) gst_pad_push_event(self->srcpad, capsEvent);
                gst_caps_unref(caps);
                if (getenv("SKIA_MEDIA_DEBUG")) {
                    g_print("[ffmpegwrapper.audio] pushed src caps S16LE %d Hz %d ch\n",
                            rate, channels);
                }
                self->caps_negotiated = TRUE;
            } else if (frame->width > 0 && frame->height > 0) {
                int w = frame->width;
                int h = frame->height;
                int chromaW = (w + 1) / 2;
                int chromaH = (h + 1) / 2;
                // skia-fx: surface the YUV colour-space hint to the
                // consumer via the standard "colorimetry" caps field.
                // The matrix decision uses the same multi-field
                // detection (colorspace → primaries → trc → all-zero
                // fallback → resolution heuristic) as the HW Blt path
                // — so software-decoded frames carry the same colour
                // metadata into the Skia upload.
                int matrix = detect_yuv_matrix(
                    (int)frame->colorspace,
                    (int)frame->color_primaries,
                    (int)frame->color_trc,
                    (int)h);

                // Build a long-form "R:M:T:P" colorimetry string so
                // the full HDR descriptor (matrix + transfer +
                // primaries + range) reaches the Skia consumer via the
                // standard caps field. The R:M:T:P codes follow the
                // ISO/IEC 23001-8 enum that GStreamer also adopts:
                //   R = 1 limited / 2 full
                //   M = 1 BT.601 / 3 BT.709 / 5 BT.2020-NCL
                //   T = 1 BT.709 / 13 sRGB / 16 SMPTE-2084 PQ /
                //       18 ARIB-B67 HLG
                //   P = 1 BT.709 / 5 BT.470BG / 6 SMPTE170M /
                //       9 BT.2020 / 11 DCI-P3
                int rangeCode = (frame->color_range == AVCOL_RANGE_JPEG) ? 2 : 1;
                int matrixCode = (matrix == 1) ? 3 : 1;
                if (frame->colorspace == AVCOL_SPC_BT2020_NCL
                 || frame->colorspace == AVCOL_SPC_BT2020_CL) {
                    matrixCode = 5;
                }
                int transferCode = 1; // default BT.709 OETF
                switch (frame->color_trc) {
                    case AVCOL_TRC_SMPTE2084: transferCode = 16; break;
                    case AVCOL_TRC_ARIB_STD_B67: transferCode = 18; break;
                    case AVCOL_TRC_IEC61966_2_1: transferCode = 13; break;
                    case AVCOL_TRC_BT2020_10:
                    case AVCOL_TRC_BT2020_12:
                    case AVCOL_TRC_BT709:
                    default:
                        transferCode = (matrixCode == 5) ? 14 : 1;
                        break;
                }
                int primariesCode = 1; // default BT.709
                switch (frame->color_primaries) {
                    case AVCOL_PRI_BT2020:    primariesCode = 9;  break;
                    case AVCOL_PRI_SMPTE170M: primariesCode = 6;  break;
                    case AVCOL_PRI_BT470BG:   primariesCode = 5;  break;
                    case AVCOL_PRI_SMPTE432:  primariesCode = 11; break;
                    case AVCOL_PRI_SMPTE428:  primariesCode = 12; break;
                    case AVCOL_PRI_BT709:
                    default:
                        primariesCode = (matrixCode == 5) ? 9 : 1;
                        break;
                }
                gchar* colorimetryStr = g_strdup_printf("%d:%d:%d:%d",
                    rangeCode, matrixCode, transferCode, primariesCode);

                GstCaps* caps = gst_caps_new_simple("video/x-raw-yuv",
                    "format",    G_TYPE_STRING,   "I420",
                    "width",     G_TYPE_INT,      w,
                    "height",    G_TYPE_INT,      h,
                    "offset-y",  G_TYPE_INT,      0,
                    "offset-u",  G_TYPE_INT,      w * h,
                    "offset-v",  G_TYPE_INT,      w * h + chromaW * chromaH,
                    "stride-y",  G_TYPE_INT,      w,
                    "stride-u",  G_TYPE_INT,      chromaW,
                    "stride-v",  G_TYPE_INT,      chromaW,
                    "colorimetry", G_TYPE_STRING, colorimetryStr,
                    NULL);
                g_free(colorimetryStr);
                GstEvent* capsEvent = gst_event_new_caps(caps);
                if (capsEvent) gst_pad_push_event(self->srcpad, capsEvent);
                gst_caps_unref(caps);
                if (getenv("SKIA_MEDIA_DEBUG")) {
                    g_print("[ffmpegwrapper] %s src caps %dx%d I420 "
                            "(range=%d matrix=%d transfer=%d primaries=%d)\n",
                            videoSizeChanged ? "re-pushed" : "pushed",
                            w, h, rangeCode, matrixCode, transferCode, primariesCode);
                }
                self->caps_negotiated = TRUE;
                // Latch the size these caps describe so a later frame at
                // a different resolution re-triggers negotiation above.
                self->negotiated_width  = w;
                self->negotiated_height = h;
            }
        }
        static bool firstFrame = true;
        if (firstFrame && getenv("SKIA_MEDIA_DEBUG")) {
            firstFrame = false;
            if (self->is_audio) {
                g_print("[ffmpegwrapper.audio] FIRST decoded frame "
                        "fmt=%d rate=%d ch=%d nb=%d\n",
                        (int)frame->format, frame->sample_rate,
                        frame->ch_layout.nb_channels, frame->nb_samples);
            } else {
                g_print("[ffmpegwrapper] FIRST decoded frame %dx%d format=%d (D3D11=%d)\n",
                        frame->width, frame->height, (int)frame->format,
                        (int)(frame->format == AV_PIX_FMT_D3D11));
            }
        }

        GstBuffer* out = nullptr;
        if (self->is_audio) {
            // Audio path. PCM S16LE buffer.
            out = pack_audio_frame_to_s16le(ff, frame, in_ts, in_dur);
        } else if (frame->format == AV_PIX_FMT_D3D11 && self->hw_ready) {
            // HW path. data[0] = ID3D11Texture2D*, data[1] = subres
            // (the ffmpeg convention is uint8_t* set to (intptr_t)idx).
            ID3D11Texture2D* hwTex = (ID3D11Texture2D*)frame->data[0];
            UINT subres = (UINT)(uintptr_t)frame->data[1];
            if (!hwTex) {
                // Malformed/partial HW frame: data[0] is null. Dereferencing it
                // would SIGSEGV the decode thread (violates "errors never kill
                // the JVM"). Drop this frame and continue decoding.
                ff->av_frame_unref(frame);
                continue;
            }
            D3D11_TEXTURE2D_DESC srcDesc = {};
            hwTex->GetDesc(&srcDesc);

            // Decoder textures are block-aligned (typically 16- or 32-px
            // multiples) for codec compliance; the actual content lives
            // in frame->width × frame->height. VP-blting the full texture
            // extent including the padding region pulls in uninitialised
            // decoder data → the green/dirty band along the bottom (and
            // right) edge. Use the codec's content dims for the source
            // rect; the VP enumerator can still be sized to the larger
            // texture envelope.
            UINT srcContentW = (UINT)frame->width;
            UINT srcContentH = (UINT)frame->height;
            if (srcContentW == 0 || srcContentW > srcDesc.Width)
                srcContentW = srcDesc.Width;
            if (srcContentH == 0 || srcContentH > srcDesc.Height)
                srcContentH = srcDesc.Height;

            // Convert NV12/P010 → RGBA at the producer's chosen output
            // size (view-size hint, bucketed). The pooled RGBA target
            // is what the consumer's WGL_NV_DX_interop2 will open.
            UINT dstW = 0, dstH = 0;
            pick_output_size(srcContentW, srcContentH, &dstW, &dstH);

            ID3D11Texture2D* rgba = nullptr;
            bool vp_ok = false;
            if (ensure_vp(srcDesc.Width, srcDesc.Height, dstW, dstH, srcDesc.Format)) {
                rgba = pool_acquire(dstW, dstH);
            }
            if (rgba) {
                vp_ok = run_vp_blt(hwTex, subres, rgba,
                                   srcContentW, srcContentH, dstW, dstH,
                                   (int)frame->colorspace,
                                   (int)frame->color_range,
                                   (int)frame->color_primaries,
                                   (int)frame->color_trc,
                                   self->container_yuv_matrix,
                                   self->container_yuv_range);
                if (vp_ok) {
                    out = make_meta_only_buffer(srcContentW, srcContentH,
                                                 in_ts, in_dur);
                    if (out) {
                        openjfx_media_d3d11_meta_add(out, rgba, 0, dstW, dstH);
                    }
                }
                rgba->Release();
            }

            // HW path failure handling.
            //
            // Two cases:
            //
            // 1) Transient single-frame failure (CreateTexture2D / VP
            //    Blt / pool acquisition fails for ONE frame, usually
            //    around window-state transitions: maximize/restore,
            //    fullscreen toggle, monitor reconfig). Returning
            //    GST_FLOW_ERROR would kill the whole pipeline for a
            //    recoverable hiccup, so we drop just this frame —
            //    the consumer keeps showing the previous SkImage and
            //    the next decoded frame typically succeeds. SW
            //    fallback for one frame would defeat zero-copy.
            //
            // 2) D3D11 device-removed (e.g. driver TDR, crash, GPU
            //    reset). The AVCodecContext's hw_device_ctx still
            //    wraps the dead device — every subsequent decode
            //    call hits it and the AMD/NVIDIA usermode driver
            //    eventually segfaults from the repeated invalid
            //    access. We MUST stop touching the device. Emit a
            //    one-shot warning and return GST_FLOW_ERROR so the
            //    bus handler surfaces a clean MediaError to Java.
            //    The application can rebuild the player to recover
            //    once the driver is back.
            if (!out) {
                if (d3d11_device_is_lost()) {
                    static bool warned_once = false;
                    if (!warned_once) {
                        g_warning("[ffmpegwrapper] D3D11 device-removed reason "
                                  "0x%08lx — stopping pipeline (the AVCodecContext "
                                  "is bound to the dead device; continuing would "
                                  "crash the GPU driver). Recreate the MediaPlayer "
                                  "to recover.",
                                  (unsigned long)g_ff_d3d11Device->GetDeviceRemovedReason());
                        warned_once = true;
                    }
                    ff->av_frame_unref(frame);
                    ret = GST_FLOW_ERROR;
                    break;
                }
                // Transient single-frame failure: drop and continue.
                ff->av_frame_unref(frame);
                continue;
            }
        } else {
            // SW path.
            out = pack_sw_frame_to_i420(frame, in_ts, in_dur);
        }

        ff->av_frame_unref(frame);

        if (!out) { ret = GST_FLOW_ERROR; break; }
        GstFlowReturn pushed = gst_pad_push(self->srcpad, out);
        if (pushed != GST_FLOW_OK) { ret = pushed; break; }
    }
    ff->av_frame_free(&frame);
    (void)pad;
    return ret;
}

// =============================================================================
// Sink-pad event handling (caps, EOS, flush, …)
// =============================================================================

static gboolean gst_ffmpegwrapper_sink_event(GstPad* pad, GstObject* parent,
                                               GstEvent* event) {
    GstFfmpegWrapper* self = GST_FFMPEGWRAPPER(parent);
    const OpenJfxFfmpegFns* ff = openjfx_ffmpeg_loader_fns();
    switch (GST_EVENT_TYPE(event)) {
        case GST_EVENT_CAPS: {
            // Resolve the codec from the upstream caps if codec-id
            // wasn't set explicitly. This lets ffmpegwrapper handle
            // codecs that don't have a JFX codec-id enum (VP9, MPEG-4,
            // etc.) — the routing layer just sees any video/x-* caps
            // and queries is-supported.
            //
            // skia-fx: also harvests audio parameters when the caps
            // are audio/* so the audio decode path can prime the
            // AVCodecContext with extradata, rate and channels before
            // avcodec_open2 — Vorbis/Opus/AAC all need this.
            GstCaps* caps = nullptr;
            gst_event_parse_caps(event, &caps);
            if (caps && ff) {
                const GstStructure* s = gst_caps_get_structure(caps, 0);
                enum AVCodecID id = mimetype_to_av_codec_id(s);
                if (id != AV_CODEC_ID_NONE && ff->avcodec_find_decoder(id)) {
                    self->av_codec_id = (gint)id;
                    self->is_supported = TRUE;
                    self->is_audio = av_codec_is_audio(id) ? TRUE : FALSE;
                }
                // Container-level colour metadata (skia-fx). qtdemux
                // reads MP4's `colr` atom, matroskademux reads MKV's
                // `Colour` element and sets the corresponding
                // "colorimetry" field on the caps that flow into this
                // element. The bitstream's metadata (in the AVFrame)
                // is preferred when present, but for files where the
                // encoder left it zero, the container is often the
                // authoritative source — extract it here.
                const gchar* cm = (s != nullptr)
                    ? gst_structure_get_string(s, "colorimetry")
                    : nullptr;
                self->container_yuv_matrix = -1;
                self->container_yuv_range  = -1;
                if (cm != nullptr) {
                    if (g_strrstr(cm, "bt709") != nullptr) {
                        self->container_yuv_matrix = 1;
                    } else if (g_strrstr(cm, "bt2020") != nullptr) {
                        self->container_yuv_matrix = 2;
                    } else if (g_strrstr(cm, "bt601")    != nullptr ||
                               g_strrstr(cm, "smpte170") != nullptr ||
                               g_strrstr(cm, "smpte240") != nullptr ||
                               g_strrstr(cm, "bt470")    != nullptr) {
                        self->container_yuv_matrix = 0;
                    } else if (g_strrstr(cm, "jpeg") != nullptr) {
                        self->container_yuv_matrix = 0;
                        self->container_yuv_range  = 1;
                    }
                    // Long form "R:M:T:P" — R=2 full range, R=1 limited.
                    gchar** parts = g_strsplit(cm, ":", -1);
                    if (parts && g_strv_length(parts) >= 2) {
                        long r = g_ascii_strtoll(parts[0], nullptr, 10);
                        long m = g_ascii_strtoll(parts[1], nullptr, 10);
                        if (self->container_yuv_range == -1) {
                            if (r == 2) self->container_yuv_range = 1;
                            else if (r == 1) self->container_yuv_range = 0;
                        }
                        if (self->container_yuv_matrix == -1) {
                            if (m == 1)      self->container_yuv_matrix = 0;
                            else if (m == 3) self->container_yuv_matrix = 1;
                            else if (m == 5 || m == 6) self->container_yuv_matrix = 2;
                        }
                    }
                    if (parts) g_strfreev(parts);
                    if (getenv("SKIA_MEDIA_DEBUG")) {
                        g_print("[ffmpegwrapper.color] container caps colorimetry='%s' "
                                "→ matrix=%d range=%d\n",
                                cm, self->container_yuv_matrix,
                                self->container_yuv_range);
                    }
                } else if (getenv("SKIA_MEDIA_DEBUG")) {
                    g_print("[ffmpegwrapper.color] no container colorimetry in input caps\n");
                }
                if (self->is_audio && s) {
                    gint rate = 0, channels = 0;
                    gst_structure_get_int(s, "rate", &rate);
                    gst_structure_get_int(s, "channels", &channels);
                    if (rate > 0)     self->aud_rate     = rate;
                    if (channels > 0) self->aud_channels = channels;

                    // Codec-private bytes can arrive two ways:
                    //
                    //  - `codec_data` — a single GstBuffer (AAC's
                    //    AudioSpecificConfig, Opus's OpusHead, FLAC's
                    //    fLaC header, WMA's WAVEFORMATEX tail).
                    //
                    //  - `streamheader` — a GValueArray of GstBuffers
                    //    (Vorbis's three Xiph headers, sometimes
                    //    Opus's OpusHead + OpusTags pair). For
                    //    multi-buffer streamheaders we re-pack them
                    //    into the Xiph-lacing format that libavcodec
                    //    expects on Vorbis extradata.
                    //
                    // open_decoder() pushes self->aud_extradata into
                    // avctx->extradata at avcodec_open2 time.
                    if (self->aud_extradata) {
                        g_free(self->aud_extradata);
                        self->aud_extradata = nullptr;
                        self->aud_extradata_size = 0;
                    }
                    const GValue* cdv = gst_structure_get_value(s, "codec_data");
                    if (cdv && G_VALUE_HOLDS(cdv, GST_TYPE_BUFFER)) {
                        GstBuffer* cb = gst_value_get_buffer(cdv);
                        GstMapInfo m = {};
                        if (cb && gst_buffer_map(cb, &m, GST_MAP_READ)) {
                            self->aud_extradata = (guint8*)g_malloc(m.size);
                            if (self->aud_extradata) {
                                memcpy(self->aud_extradata, m.data, m.size);
                                self->aud_extradata_size = (int)m.size;
                            }
                            gst_buffer_unmap(cb, &m);
                        }
                    } else {
                        const GValue* shv = gst_structure_get_value(s, "streamheader");
                        if (shv && GST_VALUE_HOLDS_ARRAY(shv)) {
                            guint n = gst_value_array_get_size(shv);
                            if (n == 1) {
                                const GValue* hv0 = gst_value_array_get_value(shv, 0);
                                if (hv0 && G_VALUE_HOLDS(hv0, GST_TYPE_BUFFER)) {
                                    GstBuffer* b = gst_value_get_buffer(hv0);
                                    GstMapInfo m = {};
                                    if (b && gst_buffer_map(b, &m, GST_MAP_READ)) {
                                        self->aud_extradata = (guint8*)g_malloc(m.size);
                                        if (self->aud_extradata) {
                                            memcpy(self->aud_extradata, m.data, m.size);
                                            self->aud_extradata_size = (int)m.size;
                                        }
                                        gst_buffer_unmap(b, &m);
                                    }
                                }
                            } else if (n >= 2) {
                                // Xiph-style multi-header lacing for
                                // Vorbis / Theora: byte 0 = (n-1), then
                                // (n-1) length-encoded sizes (each =
                                // floor(size/255) * 0xFF bytes followed
                                // by (size%255)), then concatenated
                                // header bytes. libavcodec's vorbis
                                // decoder reads exactly this layout.
                                gsize total = 1; // header count byte
                                gsize sizes[16] = {0};
                                if (n > 16) n = 16; // sanity cap
                                guint8* bufs[16] = {nullptr};
                                gsize bufsz[16] = {0};
                                bool ok = true;
                                for (guint i = 0; i < n && ok; ++i) {
                                    const GValue* hv = gst_value_array_get_value(shv, i);
                                    if (!hv || !G_VALUE_HOLDS(hv, GST_TYPE_BUFFER)) {
                                        ok = false; break;
                                    }
                                    GstBuffer* b = gst_value_get_buffer(hv);
                                    GstMapInfo m = {};
                                    if (!b || !gst_buffer_map(b, &m, GST_MAP_READ)) {
                                        ok = false; break;
                                    }
                                    sizes[i] = m.size;
                                    bufs[i]  = (guint8*)g_malloc(m.size);
                                    bufsz[i] = m.size;
                                    if (bufs[i]) memcpy(bufs[i], m.data, m.size);
                                    else ok = false;
                                    gst_buffer_unmap(b, &m);
                                    if (i + 1 < n) {
                                        // Length-encoded size for all but last.
                                        total += (sizes[i] / 255) + 1;
                                    }
                                    total += sizes[i];
                                }
                                if (ok) {
                                    self->aud_extradata = (guint8*)g_malloc(total);
                                    if (self->aud_extradata) {
                                        guint8* p = self->aud_extradata;
                                        *p++ = (guint8)(n - 1);
                                        for (guint i = 0; i + 1 < n; ++i) {
                                            gsize sz2 = sizes[i];
                                            while (sz2 >= 255) { *p++ = 0xff; sz2 -= 255; }
                                            *p++ = (guint8)sz2;
                                        }
                                        for (guint i = 0; i < n; ++i) {
                                            memcpy(p, bufs[i], bufsz[i]);
                                            p += bufsz[i];
                                        }
                                        self->aud_extradata_size = (int)total;
                                    }
                                }
                                for (guint i = 0; i < n; ++i) {
                                    if (bufs[i]) g_free(bufs[i]);
                                }
                            }
                        }
                    }
                }
            }
            return gst_pad_event_default(pad, parent, event);
        }
        case GST_EVENT_FLUSH_START:
            return gst_pad_event_default(pad, parent, event);
        case GST_EVENT_FLUSH_STOP:
            if (ff && self->avctx) ff->avcodec_flush_buffers(self->avctx);
            return gst_pad_event_default(pad, parent, event);
        case GST_EVENT_EOS:
            if (ff && self->avctx) {
                ff->avcodec_send_packet(self->avctx, NULL);
                // Drain frames left in the decoder.
                AVFrame* f = ff->av_frame_alloc();
                if (f) {
                    while (ff->avcodec_receive_frame(self->avctx, f) == 0) {
                        ff->av_frame_unref(f);
                    }
                    ff->av_frame_free(&f);
                }
            }
            return gst_pad_event_default(pad, parent, event);
        default:
            return gst_pad_event_default(pad, parent, event);
    }
}

// =============================================================================
// GObject boilerplate
// =============================================================================

static void gst_ffmpegwrapper_class_init(GstFfmpegWrapperClass* klass) {
    GObjectClass* gobj_class = G_OBJECT_CLASS(klass);
    GstElementClass* elem_class = GST_ELEMENT_CLASS(klass);

    gobj_class->dispose      = gst_ffmpegwrapper_dispose;
    gobj_class->set_property = gst_ffmpegwrapper_set_property;
    gobj_class->get_property = gst_ffmpegwrapper_get_property;

    g_object_class_install_property(gobj_class, PROP_CODEC_ID,
        g_param_spec_int("codec-id", "Codec ID", "JFX codec id",
            0, G_MAXINT, 0, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_CONSTRUCT)));
    g_object_class_install_property(gobj_class, PROP_MIMETYPE,
        g_param_spec_string("mimetype", "Mimetype",
            "GStreamer caps mimetype to probe (e.g. video/x-vp9)",
            NULL, (GParamFlags)G_PARAM_WRITABLE));
    g_object_class_install_property(gobj_class, PROP_IS_SUPPORTED,
        g_param_spec_boolean("is-supported", "Is supported",
            "Decoder available for the configured codec-id / mimetype",
            FALSE, (GParamFlags)G_PARAM_READABLE));

    gst_element_class_add_pad_template(elem_class,
        gst_static_pad_template_get(&sink_factory));
    gst_element_class_add_pad_template(elem_class,
        gst_static_pad_template_get(&src_factory));

    gst_element_class_set_static_metadata(elem_class,
        "FFmpeg decoder (skia-fx)",
        "Codec/Decoder/Video",
        "Decode AV1/HEVC/H.264 via libavcodec (runtime-loaded)",
        "JFXMedia <openjfx@openjdk.org>");
}

static void gst_ffmpegwrapper_init(GstFfmpegWrapper* self) {
    self->sinkpad = gst_pad_new_from_static_template(&sink_factory, "sink");
    self->srcpad  = gst_pad_new_from_static_template(&src_factory,  "src");
    gst_pad_set_chain_function(self->sinkpad,
        GST_DEBUG_FUNCPTR(gst_ffmpegwrapper_chain));
    gst_pad_set_event_function(self->sinkpad,
        GST_DEBUG_FUNCPTR(gst_ffmpegwrapper_sink_event));
    gst_element_add_pad(GST_ELEMENT(self), self->sinkpad);
    gst_element_add_pad(GST_ELEMENT(self), self->srcpad);

    self->codec_id     = 0;
    self->is_supported = FALSE;
    self->is_audio     = FALSE;
    self->aud_rate     = 0;
    self->aud_channels = 0;
    self->aud_extradata = nullptr;
    self->aud_extradata_size = 0;
    self->container_yuv_matrix = -1;
    self->container_yuv_range  = -1;
    self->negotiated_width  = 0;
    self->negotiated_height = 0;
}

static void gst_ffmpegwrapper_dispose(GObject* obj) {
    GstFfmpegWrapper* self = GST_FFMPEGWRAPPER(obj);
    close_decoder(self);
    G_OBJECT_CLASS(parent_class)->dispose(obj);
}

static void gst_ffmpegwrapper_set_property(GObject* obj, guint id,
                                             const GValue* val, GParamSpec* spec) {
    GstFfmpegWrapper* self = GST_FFMPEGWRAPPER(obj);
    switch (id) {
        case PROP_CODEC_ID:
            self->codec_id = g_value_get_int(val);
            // Re-evaluate is-supported when the codec-id is assigned.
            // Note: keeps av_codec_id if it was already set from caps.
            {
                enum AVCodecID jfxMapped = jfx_to_av_codec_id(self->codec_id);
                if (jfxMapped != AV_CODEC_ID_NONE) {
                    self->av_codec_id = (gint)jfxMapped;
                    self->is_audio = av_codec_is_audio(jfxMapped) ? TRUE : FALSE;
                }
            }
            self->is_supported = openjfx_ffmpeg_loader_has_codec(self->av_codec_id);
            break;
        case PROP_MIMETYPE: {
            const gchar* m = g_value_get_string(val);
            self->av_codec_id = AV_CODEC_ID_NONE;
            self->is_supported = FALSE;
            self->is_audio     = FALSE;
            if (m) {
                enum AVCodecID id2 = mimetype_to_av_codec_id_str(m, 0);
                if (id2 != AV_CODEC_ID_NONE) {
                    self->av_codec_id   = (gint)id2;
                    self->is_supported  = openjfx_ffmpeg_loader_has_codec((int)id2);
                    self->is_audio      = av_codec_is_audio(id2) ? TRUE : FALSE;
                }
            }
            break;
        }
        default:
            G_OBJECT_WARN_INVALID_PROPERTY_ID(obj, id, spec);
            break;
    }
}

static void gst_ffmpegwrapper_get_property(GObject* obj, guint id,
                                             GValue* val, GParamSpec* spec) {
    GstFfmpegWrapper* self = GST_FFMPEGWRAPPER(obj);
    switch (id) {
        case PROP_CODEC_ID:
            g_value_set_int(val, self->codec_id);
            break;
        case PROP_IS_SUPPORTED:
            g_value_set_boolean(val, self->is_supported);
            break;
        default:
            G_OBJECT_WARN_INVALID_PROPERTY_ID(obj, id, spec);
            break;
    }
}

// =============================================================================
// Plugin registration entry — called from fxplugins.c::fxplugins_init.
// =============================================================================

extern "C" gboolean ffmpegwrapper_init(GstPlugin* plugin) {
    GST_DEBUG_CATEGORY_INIT(gst_ffmpegwrapper_debug,
        "ffmpegwrapper", 0, "ffmpeg-backed decoder");
    // Try to load ffmpeg now so the very first codec query in
    // GstAVPlaybackPipeline gets a real is-supported answer. Soft
    // failure is fine — is-supported will report FALSE and routing
    // falls back to mfwrapper / dshowwrapper.
    openjfx_ffmpeg_loader_init(NULL);
    return gst_element_register(plugin, "ffmpegwrapper",
        512 /* GST_RANK_PRIMARY */, GST_TYPE_FFMPEGWRAPPER);
}
