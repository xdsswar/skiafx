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

// Load by ABSOLUTE path with the search-order flags. These DLLs run
// in-process, so the resolution must be deterministic: never the
// current working directory (classic planting vector), and a DLL's own
// dependencies (avcodec → avutil) resolve from its OWN directory first.
HMODULE load_absolute(const std::string& full) {
    HMODULE h = LoadLibraryExA(full.c_str(), NULL,
        LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR |
        LOAD_LIBRARY_SEARCH_APPLICATION_DIR |
        LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    if (h) return h;
    // Fallback for older Windows that doesn't honour the flags —
    // safe because the path is absolute.
    return LoadLibraryA(full.c_str());
}

// Resolve dllName against the PATH entries EXPLICITLY. LoadLibrary's
// own search would consult the working directory (planting vector) and
// LOAD_LIBRARY_SEARCH_DEFAULT_DIRS never consults PATH at all — this
// walk gives the documented "or the system PATH" behaviour without
// either problem.
HMODULE load_from_path_env(const char* dllName) {
    const char* path = std::getenv("PATH");
    if (!path) return NULL;
    const char* p = path;
    for (;;) {
        const char* sep = std::strchr(p, ';');
        size_t len = sep ? (size_t)(sep - p) : std::strlen(p);
        if (len > 0 && len < MAX_PATH) {
            std::string full(p, len);
            if (full.back() != '\\' && full.back() != '/') full += '\\';
            full += dllName;
            DWORD attrs = GetFileAttributesA(full.c_str());
            if (attrs != INVALID_FILE_ATTRIBUTES &&
                !(attrs & FILE_ATTRIBUTE_DIRECTORY)) {
                HMODULE h = load_absolute(full);
                if (h) return h;
            }
        }
        if (!sep) break;
        p = sep + 1;
    }
    return NULL;
}

HMODULE try_load_one(const std::string& dir, const char* dllName) {
    if (!dir.empty()) {
        std::string full = dir;
        if (full.back() != '\\' && full.back() != '/') full += '\\';
        full += dllName;
        // Canonicalize: the LOAD_LIBRARY_SEARCH flags reject relative
        // paths, and a relative fallback would resolve against the
        // working directory. A configured dir must mean ONE directory,
        // wherever the process happens to be cwd'd.
        char abs[MAX_PATH] = {0};
        DWORD n = GetFullPathNameA(full.c_str(), sizeof(abs), abs, NULL);
        if (n == 0 || n >= sizeof(abs)) return NULL;
        return load_absolute(abs);
    }
    // No dir hint: default search dirs (application dir, System32,
    // AddDllDirectory entries) first, then an explicit PATH walk.
    HMODULE h = LoadLibraryExA(dllName, NULL,
        LOAD_LIBRARY_SEARCH_DEFAULT_DIRS | LOAD_LIBRARY_SEARCH_USER_DIRS);
    if (h) return h;
    return load_from_path_env(dllName);
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

    // --- avformat remux group (MediaMixer) — OPTIONAL. Decode paths
    // never depend on these; remux_ok gates openjfx_ffmpeg_remux. ---
    if (g_fns.hAvformat) {
        bool rok = true;
        rok &= resolve(g_fns.hAvformat, "avformat_open_input",            g_fns.avformat_open_input);
        rok &= resolve(g_fns.hAvformat, "avformat_close_input",           g_fns.avformat_close_input);
        rok &= resolve(g_fns.hAvformat, "avformat_find_stream_info",      g_fns.avformat_find_stream_info);
        rok &= resolve(g_fns.hAvformat, "avformat_alloc_output_context2", g_fns.avformat_alloc_output_context2);
        rok &= resolve(g_fns.hAvformat, "avformat_free_context",          g_fns.avformat_free_context);
        rok &= resolve(g_fns.hAvformat, "avformat_new_stream",            g_fns.avformat_new_stream);
        rok &= resolve(g_fns.hAvformat, "avformat_write_header",          g_fns.avformat_write_header);
        rok &= resolve(g_fns.hAvformat, "av_write_trailer",               g_fns.av_write_trailer);
        rok &= resolve(g_fns.hAvformat, "av_read_frame",                  g_fns.av_read_frame);
        rok &= resolve(g_fns.hAvformat, "av_interleaved_write_frame",     g_fns.av_interleaved_write_frame);
        rok &= resolve(g_fns.hAvformat, "avio_open",                      g_fns.avio_open);
        rok &= resolve(g_fns.hAvformat, "avio_closep",                    g_fns.avio_closep);
        rok &= resolve(g_fns.hAvcodec,  "avcodec_parameters_copy",        g_fns.avcodec_parameters_copy);
        rok &= resolve(g_fns.hAvcodec,  "av_packet_rescale_ts",           g_fns.av_packet_rescale_ts);
        rok &= resolve(g_fns.hAvutil,   "av_strerror",                    g_fns.av_strerror);
        g_fns.remux_ok = rok ? 1 : 0;
    }

    // --- avformat demux group (ffmpegdemux catch-all) — OPTIONAL.
    // Decode / remux paths never depend on these; demux_ok gates the
    // ffmpegdemux GStreamer element. Resolved separately so a build with
    // an older avformat that lacks one of these still does decode+remux. ---
    if (g_fns.hAvformat) {
        bool dok = true;
        dok &= resolve(g_fns.hAvformat, "avformat_alloc_context", g_fns.avformat_alloc_context);
        dok &= resolve(g_fns.hAvformat, "avio_alloc_context",     g_fns.avio_alloc_context);
        dok &= resolve(g_fns.hAvformat, "avio_context_free",      g_fns.avio_context_free);
        dok &= resolve(g_fns.hAvformat, "av_seek_frame",          g_fns.av_seek_frame);
        dok &= resolve(g_fns.hAvformat, "avformat_seek_file",     g_fns.avformat_seek_file);
        dok &= resolve(g_fns.hAvutil,   "av_freep",               g_fns.av_freep);
        // av_read_frame / avformat_open_input / _close_input /
        // _find_stream_info come from the remux group above; the element
        // needs them too, so demux is usable only when both resolved.
        g_fns.demux_ok = (dok && g_fns.remux_ok) ? 1 : 0;
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
    unsigned avformatMajor = 0;
    if (g_fns.hAvformat) {
        auto fmtVer = (VerFn)GetProcAddress(g_fns.hAvformat, "avformat_version");
        if (fmtVer) avformatMajor = (fmtVer() >> 16) & 0xff;
    }

    // ABI guard: the wrapper passes AVFrame/AVCodecContext structs
    // across this boundary, so the loaded avcodec MAJOR must match the
    // headers we compiled against. A drifted runtime doesn't fail
    // loudly — deep struct fields silently read garbage (observed:
    // AVFrame.ch_layout as 0 → mono audio packing → audio playing
    // double-speed in chunks). Refusing the load degrades cleanly to
    // "ffmpeg unavailable": mp4/AAC/H.264, mp3 and wav keep playing on
    // the platform decoders; only ffmpeg-dependent codecs are lost.
    if (g_fns.avcodec_version_major != 0 &&
        g_fns.avcodec_version_major != (LIBAVCODEC_VERSION_MAJOR)) {
        set_status("ffmpeg ABI mismatch: found avcodec %d.%d but this build "
            "expects avcodec %d (ffmpeg %s) — refusing to load it. Point "
            "Media.setFfmpegDirectory()/OPENJFX_MEDIA_FFMPEG_DIR at a "
            "matching ffmpeg, or remove the stale one. webm/mkv "
            "(Opus/Vorbis/VP9) and other ffmpeg-decoded formats are "
            "disabled until then; mp4/AAC/H.264, mp3 and wav still play.",
            g_fns.avcodec_version_major, g_fns.avcodec_version_minor,
            (int)LIBAVCODEC_VERSION_MAJOR,
            "matching the bundled headers");
        rollback();
        return false;
    }
    // The other two libs cross structs over this boundary too: AVFrame /
    // AVChannelLayout live in avutil's ABI, AVFormatContext / AVStream in
    // avformat's (the remux engine reads them directly). A PATH that
    // mixes DLLs from different ffmpeg builds can pass the avcodec check
    // and still skew these — validate all three majors.
    if (g_fns.avutil_version_major != 0 &&
        g_fns.avutil_version_major != (LIBAVUTIL_VERSION_MAJOR)) {
        set_status("ffmpeg ABI mismatch: found avutil %d.%d but this build "
            "expects avutil %d — the DLLs in the configured directory/PATH "
            "mix ffmpeg builds. Refusing to load; provide one consistent "
            "ffmpeg via Media.setFfmpegDirectory()/OPENJFX_MEDIA_FFMPEG_DIR.",
            g_fns.avutil_version_major, g_fns.avutil_version_minor,
            (int)LIBAVUTIL_VERSION_MAJOR);
        rollback();
        return false;
    }
    if (avformatMajor != 0 && avformatMajor != (LIBAVFORMAT_VERSION_MAJOR)) {
        // avformat is optional (decode works without it) — but a DRIFTED
        // avformat must not be used: disable the remux group instead of
        // failing the whole load.
        g_fns.remux_ok = 0;
        std::fprintf(stderr, "[ffmpeg.loader] avformat %u does not match the "
            "expected major %d - media mixing disabled (decode unaffected)\n",
            avformatMajor, (int)LIBAVFORMAT_VERSION_MAJOR);
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
    if (g_initTried.load()) {
        // Success latches. A FAILED attempt is retryable when the caller
        // provides an explicit directory (Media.setFfmpegDirectory after
        // the first failure — the failure message tells users to do
        // exactly that); do_init's rollback left no loaded DLLs behind.
        if (g_ready.load() || user_dir == nullptr || *user_dir == '\0')
            return g_ready.load();
    }
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
