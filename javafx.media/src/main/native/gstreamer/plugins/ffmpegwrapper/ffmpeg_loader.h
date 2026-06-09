// ---------------------------------------------------------------------------
// ffmpeg_loader — runtime-dynamic ffmpeg DLL loader.
//
// skia-fx never links against any ffmpeg .lib. At startup we probe a
// few well-known locations (user-supplied path, PATH, common installs)
// for avcodec-*.dll + friends, LoadLibrary them, and GetProcAddress
// every function we use into the FfmpegFns table below. The decoder
// plugin (ffmpegwrapper.cpp) calls through the table rather than via
// direct symbol references.
//
// When ffmpeg isn't installed, ffmpeg_loader_init returns false and
// ffmpeg_loader_fns() returns null. The decoder plugin then reports
// is-supported=FALSE for every codec, and JFX's GstAVPlaybackPipeline
// falls through to mfwrapper / dshowwrapper.
//
// Target ffmpeg ABI: 7.x. DLL filenames we look for on Windows:
//   avcodec-61.dll  avformat-61.dll  avutil-59.dll
//   swresample-5.dll  swscale-8.dll
// Function signatures match libavcodec 61.x / libavutil 59.x.
//
// All entries are extern "C" so the JFX side can dlsym them from
// another DLL — this loader is itself reusable as the cross-plugin
// runtime registration point.
// ---------------------------------------------------------------------------
#ifndef OPENJFX_FFMPEG_LOADER_H
#define OPENJFX_FFMPEG_LOADER_H

#include <stdbool.h>
#include <stdint.h>

#ifdef _WIN32
#  include <windows.h>
// d3d11.h has to be included OUTSIDE any extern "C" block because it
// declares operator== / operator!= overloads for D3D11_VIEWPORT etc.
// in C++ scope. ffmpeg's hwcontext_d3d11va.h pulls in d3d11.h and
// would put it inside extern "C" if we wrap the ffmpeg includes that
// way. Pre-include here so the second include from ffmpeg is a no-op.
#  include <d3d11.h>
#endif

// ffmpeg public headers come from skiafx.ffmpeg-conventions at build
// time (build/generated/ffmpeg/include/<version>/). The libav* headers
// already have their own `extern "C"` wrappers inside, so we don't
// wrap them here — wrapping would break the d3d11.h pre-include.
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_d3d11va.h>
#include <libavutil/buffer.h>
#include <libavutil/frame.h>
#include <libavutil/log.h>
#include <libavutil/opt.h>
#include <libavutil/pixfmt.h>

#ifdef _WIN32
#  define OPENJFX_FFMPEG_EXPORT __declspec(dllexport)
#else
#  define OPENJFX_FFMPEG_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

// Function-pointer table populated by ffmpeg_loader_init(). All
// pointers are non-null after a successful init; failures leave the
// whole table zeroed (memset on init-failure).
typedef struct OpenJfxFfmpegFns {
    // --- avutil ---
    AVFrame*    (*av_frame_alloc)(void);
    void        (*av_frame_free)(AVFrame**);
    void        (*av_frame_unref)(AVFrame*);
    int         (*av_frame_get_buffer)(AVFrame*, int);
    int         (*av_hwframe_transfer_data)(AVFrame*, const AVFrame*, int);
    AVBufferRef* (*av_buffer_ref)(AVBufferRef*);
    void        (*av_buffer_unref)(AVBufferRef**);
    AVBufferRef* (*av_hwdevice_ctx_alloc)(enum AVHWDeviceType);
    int         (*av_hwdevice_ctx_create)(AVBufferRef**, enum AVHWDeviceType,
                                          const char*, AVDictionary*, int);
    int         (*av_hwdevice_ctx_init)(AVBufferRef*);
    AVBufferRef* (*av_hwframe_ctx_alloc)(AVBufferRef*);
    int         (*av_hwframe_ctx_init)(AVBufferRef*);
    void        (*av_log_set_level)(int);
    void        (*av_log_set_callback)(void (*)(void*, int, const char*, va_list));
    int         (*av_dict_set)(AVDictionary**, const char*, const char*, int);
    void        (*av_dict_free)(AVDictionary**);
    void*       (*av_malloc)(size_t);
    void        (*av_free)(void*);

    // --- avcodec ---
    const AVCodec*         (*avcodec_find_decoder)(enum AVCodecID);
    const AVCodec*         (*avcodec_find_decoder_by_name)(const char*);
    AVCodecContext*        (*avcodec_alloc_context3)(const AVCodec*);
    void                   (*avcodec_free_context)(AVCodecContext**);
    int                    (*avcodec_open2)(AVCodecContext*, const AVCodec*,
                                            AVDictionary**);
    int                    (*avcodec_send_packet)(AVCodecContext*,
                                                  const AVPacket*);
    int                    (*avcodec_receive_frame)(AVCodecContext*, AVFrame*);
    int                    (*avcodec_parameters_from_context)(AVCodecParameters*,
                                                              const AVCodecContext*);
    int                    (*avcodec_parameters_to_context)(AVCodecContext*,
                                                            const AVCodecParameters*);
    AVPacket*              (*av_packet_alloc)(void);
    void                   (*av_packet_free)(AVPacket**);
    int                    (*av_new_packet)(AVPacket*, int);
    void                   (*av_packet_unref)(AVPacket*);
    const AVCodecHWConfig* (*avcodec_get_hw_config)(const AVCodec*, int);
    void                   (*avcodec_flush_buffers)(AVCodecContext*);

#ifdef _WIN32
    HMODULE  hAvcodec;
    HMODULE  hAvformat;
    HMODULE  hAvutil;
    HMODULE  hSwresample;   // reserved — audio path (not used yet)
    HMODULE  hSwscale;      // reserved — SW colour-convert fallback
#endif

    // Populated from the resolved avcodec / avutil DLLs at init time.
    // Useful for diagnostics ("Loaded ffmpeg 61.13.100 / 59.39.100").
    int avcodec_version_major;
    int avcodec_version_minor;
    int avutil_version_major;
    int avutil_version_minor;
} OpenJfxFfmpegFns;

// Resolve ffmpeg DLLs and populate the function table. Returns true
// on success. user_dir may be NULL — in that case we probe PATH and
// common install locations. Idempotent: subsequent calls with the
// same args return cached state.
OPENJFX_FFMPEG_EXPORT bool openjfx_ffmpeg_loader_init(const char* user_dir);

// Returns the loaded function table, or NULL when init hasn't been
// called (or failed). Safe to call from any thread.
OPENJFX_FFMPEG_EXPORT const OpenJfxFfmpegFns* openjfx_ffmpeg_loader_fns(void);

// True when a decoder for `codec_id` is available in the loaded
// avcodec build. Returns false when ffmpeg isn't loaded.
OPENJFX_FFMPEG_EXPORT bool openjfx_ffmpeg_loader_has_codec(int codec_id);

// One-line human-readable status, owned by the loader. NULL until
// init has been attempted. Useful for the demo to log what we
// found / didn't find.
OPENJFX_FFMPEG_EXPORT const char* openjfx_ffmpeg_loader_status(void);

// Unload the DLLs. Safe to call when init was never tried.
OPENJFX_FFMPEG_EXPORT void openjfx_ffmpeg_loader_shutdown(void);

#ifdef __cplusplus
}
#endif

#endif // OPENJFX_FFMPEG_LOADER_H
