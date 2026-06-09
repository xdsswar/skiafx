// ---------------------------------------------------------------------------
// ffmpeg_loader.cpp — see header for the contract.
//
// Implementation notes:
//   - All symbol resolution happens once, under a process-wide guard.
//     Subsequent calls reuse the cached state regardless of args.
//   - On any failed function lookup the whole load is rolled back —
//     half-resolved tables are worse than no table.
//   - Errors go to stderr with a `[ffmpeg.loader]` prefix; the
//     decoder plugin reads `openjfx_ffmpeg_loader_status()` for a
//     human-readable summary too.
// ---------------------------------------------------------------------------

#include "ffmpeg_loader.h"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

#ifdef _WIN32
#  include <windows.h>
#  include <strsafe.h>
#endif

namespace {

std::mutex          g_initMutex;
std::atomic<bool>   g_initTried{false};
std::atomic<bool>   g_ready{false};
OpenJfxFfmpegFns    g_fns{};
std::string         g_status;

// Windows DLL filenames for ffmpeg. Each logical library has multiple
// candidate filenames so we span ABI versions — ffmpeg 7.x ships
// avcodec-61, avutil-59; master / 8.x ships avcodec-62, avutil-60.
// We probe newest first so a system with both installed picks the
// newer DLL set (which is presumably what the user wanted).
const char* const DLLS_AVCODEC[]    = { "avcodec-62.dll", "avcodec-61.dll", NULL };
const char* const DLLS_AVFORMAT[]   = { "avformat-62.dll", "avformat-61.dll", NULL };
const char* const DLLS_AVUTIL[]     = { "avutil-60.dll",  "avutil-59.dll",  NULL };
const char* const DLLS_SWRESAMPLE[] = { "swresample-6.dll", "swresample-5.dll", NULL };
const char* const DLLS_SWSCALE[]    = { "swscale-9.dll", "swscale-8.dll", NULL };

#ifdef _WIN32

HMODULE try_load_one(const std::string& dir, const char* dllName) {
    if (!dir.empty()) {
        std::string full = dir;
        if (full.back() != '\\' && full.back() != '/') full += '\\';
        full += dllName;
        // LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR makes Windows resolve THIS
        // DLL's dependencies (e.g. avcodec → avutil) by looking in
        // the DLL's own directory first. Without this flag, plain
        // LoadLibraryA would search the EXE's directory + System32
        // for avutil, miss it, and fail. The application directory
        // search is also enabled so any system-installed deps still
        // resolve.
        HMODULE h = LoadLibraryExA(full.c_str(), NULL,
            LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR |
            LOAD_LIBRARY_SEARCH_APPLICATION_DIR |
            LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
        if (h) return h;
        // Fallback for older Windows that doesn't honour the flags.
        h = LoadLibraryA(full.c_str());
        if (h) return h;
    }
    // No dir hint — pure PATH search.
    return LoadLibraryExA(dllName, NULL,
        LOAD_LIBRARY_SEARCH_DEFAULT_DIRS | LOAD_LIBRARY_SEARCH_USER_DIRS);
}

HMODULE try_load(const std::string& dir, const char* const* candidates) {
    for (int i = 0; candidates[i] != NULL; ++i) {
        HMODULE h = try_load_one(dir, candidates[i]);
        if (h) return h;
    }
    return NULL;
}

template <typename FN>
bool resolve(HMODULE mod, const char* name, FN& out) {
    FARPROC p = GetProcAddress(mod, name);
    out = reinterpret_cast<FN>(p);
    if (!out) {
        char buf[256];
        StringCbPrintfA(buf, sizeof(buf),
            "[ffmpeg.loader] missing symbol '%s'\n", name);
        ::OutputDebugStringA(buf);
        std::fprintf(stderr, "%s", buf);
        return false;
    }
    return true;
}

void rollback() {
    if (g_fns.hAvcodec)    { FreeLibrary(g_fns.hAvcodec);    g_fns.hAvcodec    = nullptr; }
    if (g_fns.hAvformat)   { FreeLibrary(g_fns.hAvformat);   g_fns.hAvformat   = nullptr; }
    if (g_fns.hAvutil)     { FreeLibrary(g_fns.hAvutil);     g_fns.hAvutil     = nullptr; }
    if (g_fns.hSwresample) { FreeLibrary(g_fns.hSwresample); g_fns.hSwresample = nullptr; }
    if (g_fns.hSwscale)    { FreeLibrary(g_fns.hSwscale);    g_fns.hSwscale    = nullptr; }
    std::memset(&g_fns, 0, sizeof(g_fns));
}

#endif // _WIN32

void set_status(const char* fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    g_status = buf;
}

bool do_init(const char* user_dir) {
#ifndef _WIN32
    (void)user_dir;
    set_status("ffmpeg loader: only Windows is wired today");
    return false;
#else
    std::string dir = (user_dir && *user_dir) ? user_dir : "";

    // 1. avutil first — almost everything else depends on it.
    g_fns.hAvutil    = try_load(dir, DLLS_AVUTIL);
    g_fns.hAvcodec   = try_load(dir, DLLS_AVCODEC);
    g_fns.hAvformat  = try_load(dir, DLLS_AVFORMAT);
    g_fns.hSwresample = try_load(dir, DLLS_SWRESAMPLE);  // optional today
    g_fns.hSwscale   = try_load(dir, DLLS_SWSCALE);      // optional today
    if (!g_fns.hAvutil || !g_fns.hAvcodec) {
        set_status("ffmpeg DLLs not found%s%s",
            (dir.empty() ? " on PATH" : " in "), dir.c_str());
        rollback();
        return false;
    }

    bool ok = true;
    // --- avutil ---
    ok &= resolve(g_fns.hAvutil, "av_frame_alloc",            g_fns.av_frame_alloc);
    ok &= resolve(g_fns.hAvutil, "av_frame_free",             g_fns.av_frame_free);
    ok &= resolve(g_fns.hAvutil, "av_frame_unref",            g_fns.av_frame_unref);
    ok &= resolve(g_fns.hAvutil, "av_frame_get_buffer",       g_fns.av_frame_get_buffer);
    ok &= resolve(g_fns.hAvutil, "av_hwframe_transfer_data",  g_fns.av_hwframe_transfer_data);
    ok &= resolve(g_fns.hAvutil, "av_buffer_ref",             g_fns.av_buffer_ref);
    ok &= resolve(g_fns.hAvutil, "av_buffer_unref",           g_fns.av_buffer_unref);
    ok &= resolve(g_fns.hAvutil, "av_hwdevice_ctx_alloc",     g_fns.av_hwdevice_ctx_alloc);
    ok &= resolve(g_fns.hAvutil, "av_hwdevice_ctx_create",    g_fns.av_hwdevice_ctx_create);
    ok &= resolve(g_fns.hAvutil, "av_hwdevice_ctx_init",      g_fns.av_hwdevice_ctx_init);
    ok &= resolve(g_fns.hAvutil, "av_hwframe_ctx_alloc",      g_fns.av_hwframe_ctx_alloc);
    ok &= resolve(g_fns.hAvutil, "av_hwframe_ctx_init",       g_fns.av_hwframe_ctx_init);
    ok &= resolve(g_fns.hAvutil, "av_log_set_level",          g_fns.av_log_set_level);
    ok &= resolve(g_fns.hAvutil, "av_log_set_callback",       g_fns.av_log_set_callback);
    ok &= resolve(g_fns.hAvutil, "av_dict_set",               g_fns.av_dict_set);
    ok &= resolve(g_fns.hAvutil, "av_dict_free",              g_fns.av_dict_free);
    ok &= resolve(g_fns.hAvutil, "av_malloc",                 g_fns.av_malloc);
    ok &= resolve(g_fns.hAvutil, "av_free",                   g_fns.av_free);

    // --- avcodec ---
    ok &= resolve(g_fns.hAvcodec, "avcodec_find_decoder",            g_fns.avcodec_find_decoder);
    ok &= resolve(g_fns.hAvcodec, "avcodec_find_decoder_by_name",    g_fns.avcodec_find_decoder_by_name);
    ok &= resolve(g_fns.hAvcodec, "avcodec_alloc_context3",          g_fns.avcodec_alloc_context3);
    ok &= resolve(g_fns.hAvcodec, "avcodec_free_context",            g_fns.avcodec_free_context);
    ok &= resolve(g_fns.hAvcodec, "avcodec_open2",                   g_fns.avcodec_open2);
    ok &= resolve(g_fns.hAvcodec, "avcodec_send_packet",             g_fns.avcodec_send_packet);
    ok &= resolve(g_fns.hAvcodec, "avcodec_receive_frame",           g_fns.avcodec_receive_frame);
    ok &= resolve(g_fns.hAvcodec, "avcodec_parameters_from_context", g_fns.avcodec_parameters_from_context);
    ok &= resolve(g_fns.hAvcodec, "avcodec_parameters_to_context",   g_fns.avcodec_parameters_to_context);
    ok &= resolve(g_fns.hAvcodec, "av_packet_alloc",                 g_fns.av_packet_alloc);
    ok &= resolve(g_fns.hAvcodec, "av_packet_free",                  g_fns.av_packet_free);
    ok &= resolve(g_fns.hAvcodec, "av_new_packet",                   g_fns.av_new_packet);
    ok &= resolve(g_fns.hAvcodec, "av_packet_unref",                 g_fns.av_packet_unref);
    ok &= resolve(g_fns.hAvcodec, "avcodec_get_hw_config",           g_fns.avcodec_get_hw_config);
    ok &= resolve(g_fns.hAvcodec, "avcodec_flush_buffers",           g_fns.avcodec_flush_buffers);

    if (!ok) {
        rollback();
        set_status("ffmpeg loaded but symbol resolution failed — check ABI version");
        return false;
    }

    // Optional: query version symbols if present (best effort).
    typedef unsigned int (*VerFn)(void);
    auto codecVer = (VerFn)GetProcAddress(g_fns.hAvcodec, "avcodec_version");
    auto utilVer  = (VerFn)GetProcAddress(g_fns.hAvutil,  "avutil_version");
    if (codecVer) {
        unsigned v = codecVer();
        g_fns.avcodec_version_major = (v >> 16) & 0xff;
        g_fns.avcodec_version_minor = (v >>  8) & 0xff;
    }
    if (utilVer) {
        unsigned v = utilVer();
        g_fns.avutil_version_major = (v >> 16) & 0xff;
        g_fns.avutil_version_minor = (v >>  8) & 0xff;
    }

    set_status("ffmpeg loaded from '%s' (avcodec %d.%d, avutil %d.%d)",
        dir.empty() ? "PATH" : dir.c_str(),
        g_fns.avcodec_version_major, g_fns.avcodec_version_minor,
        g_fns.avutil_version_major, g_fns.avutil_version_minor);
    return true;
#endif
}

} // anonymous namespace

extern "C" OPENJFX_FFMPEG_EXPORT bool
openjfx_ffmpeg_loader_init(const char* user_dir) {
    std::lock_guard<std::mutex> lock(g_initMutex);
    if (g_initTried.load()) return g_ready.load();
    g_initTried.store(true);
    bool ok = do_init(user_dir);
    g_ready.store(ok);
    // Init failures always surface (user needs to know). Successful
    // load is gated behind SKIA_MEDIA_DEBUG — it's noise once you
    // know the loader works.
    if (!ok) {
        std::fprintf(stderr, "[ffmpeg.loader] init failed: %s\n", g_status.c_str());
    } else if (std::getenv("SKIA_MEDIA_DEBUG")) {
        std::fprintf(stderr, "[ffmpeg.loader] %s\n", g_status.c_str());
    }
    return ok;
}

extern "C" OPENJFX_FFMPEG_EXPORT const OpenJfxFfmpegFns*
openjfx_ffmpeg_loader_fns(void) {
    return g_ready.load() ? &g_fns : nullptr;
}

extern "C" OPENJFX_FFMPEG_EXPORT bool
openjfx_ffmpeg_loader_has_codec(int codec_id) {
    if (!g_ready.load()) return false;
    if (!g_fns.avcodec_find_decoder) return false;
    return g_fns.avcodec_find_decoder((enum AVCodecID)codec_id) != nullptr;
}

extern "C" OPENJFX_FFMPEG_EXPORT const char*
openjfx_ffmpeg_loader_status(void) {
    return g_status.empty() ? nullptr : g_status.c_str();
}

extern "C" OPENJFX_FFMPEG_EXPORT void
openjfx_ffmpeg_loader_shutdown(void) {
    std::lock_guard<std::mutex> lock(g_initMutex);
    if (!g_ready.exchange(false)) return;
#ifdef _WIN32
    rollback();
#endif
    g_initTried.store(false);
    g_status.clear();
}
