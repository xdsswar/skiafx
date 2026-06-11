// ---------------------------------------------------------------------------
// ffmpeg_remux — MediaMixer's native engine (skia-fx addition).
//
// Copies the best VIDEO stream of one input file and the best AUDIO
// stream of another into a single MP4, WITHOUT re-encoding (pure
// stream copy — fast, lossless). Used by javafx.scene.media.MediaMixer
// to merge separately-downloaded adaptive streams (e.g. a video-only
// webm + an audio-only webm) into one playable file.
//
// Lives in fxplugins.dll next to the ffmpeg loader; jfxmedia's JNI
// bridge resolves openjfx_ffmpeg_remux via GetProcAddress (the same
// cross-DLL pattern MediaFfmpegConfig uses for the loader init).
//
// Codec note: MP4 officially carries H.264/H.265/AV1/VP9 video and
// AAC/MP3/Opus audio. ffmpeg's mp4 muxer accepts these via stream
// copy. Codecs the container can't carry fail with a clear error from
// avformat_write_header — surfaced to the caller, never fatal.
//
// Error policy (project rule: errors never kill the JVM): every
// failure path returns a nonzero code with a human-readable message
// in errBuf; no exceptions, no aborts.
// ---------------------------------------------------------------------------

#ifdef HAVE_CONFIG_H
#include "config.h"
#endif

#include "ffmpeg_loader.h"

#include <cstdio>
#include <cstring>

namespace {

void fmt_err(const OpenJfxFfmpegFns* ff, char* errBuf, int errBufLen,
             const char* what, int averr) {
    if (!errBuf || errBufLen <= 0) return;
    char detail[128] = {0};
    if (ff && ff->av_strerror && averr != 0) {
        ff->av_strerror(averr, detail, sizeof(detail) - 1);
    }
    if (detail[0] != '\0') {
        std::snprintf(errBuf, (size_t)errBufLen, "%s: %s", what, detail);
    } else {
        std::snprintf(errBuf, (size_t)errBufLen, "%s", what);
    }
}

// Best stream of the wanted type, preferring the demuxer's default.
// Attached pictures (cover art in audio-tagged files) are video-typed
// streams but not video — never pick them.
int find_stream(AVFormatContext* in, enum AVMediaType type) {
    int best = -1;
    for (unsigned i = 0; i < in->nb_streams; ++i) {
        AVCodecParameters* par = in->streams[i]->codecpar;
        if (in->streams[i]->disposition & AV_DISPOSITION_ATTACHED_PIC)
            continue;
        if (par && par->codec_type == type) {
            if (best < 0) best = (int)i;
            if (in->streams[i]->disposition & AV_DISPOSITION_DEFAULT) {
                best = (int)i;
                break;
            }
        }
    }
    return best;
}

struct InputSide {
    AVFormatContext* ctx = nullptr;
    int              streamIndex = -1;
    AVStream*        outStream = nullptr;
    AVPacket*        pkt = nullptr;     // pending packet (when loaded)
    bool             loaded = false;    // pkt holds a packet
    bool             eof = false;
    double           lastSeconds = 0.0; // pts of the pending packet
};

// Pull the next packet of the selected stream into side->pkt.
// Returns false on EOF (side->eof set) — read errors count as EOF so a
// truncated input still produces a playable file up to the cut.
bool load_next(const OpenJfxFfmpegFns* ff, InputSide* side) {
    if (side->eof || side->loaded) return side->loaded;
    for (;;) {
        int rc = ff->av_read_frame(side->ctx, side->pkt);
        if (rc < 0) {
            side->eof = true;
            return false;
        }
        if (side->pkt->stream_index == side->streamIndex) {
            AVStream* ist = side->ctx->streams[side->streamIndex];
            if (side->pkt->pts != AV_NOPTS_VALUE) {
                side->lastSeconds = side->pkt->pts *
                    ((double)ist->time_base.num / (double)ist->time_base.den);
            }
            side->loaded = true;
            return true;
        }
        ff->av_packet_unref(side->pkt);
    }
}

// Write the pending packet (retimed to the output stream) and unload.
int write_pending(const OpenJfxFfmpegFns* ff, AVFormatContext* out,
                  InputSide* side) {
    AVStream* ist = side->ctx->streams[side->streamIndex];
    side->pkt->stream_index = side->outStream->index;
    ff->av_packet_rescale_ts(side->pkt, ist->time_base,
                             side->outStream->time_base);
    side->pkt->pos = -1;
    int rc = ff->av_interleaved_write_frame(out, side->pkt);
    // av_interleaved_write_frame takes ownership on success AND failure
    // (it unrefs); just mark the slot free.
    side->loaded = false;
    return rc;
}

} // namespace

extern "C" OPENJFX_FFMPEG_EXPORT int openjfx_ffmpeg_remux(
    const char* audioPath, const char* videoPath, const char* outPath,
    int flags,
    OpenJfxRemuxProgressFn onProgress, OpenJfxRemuxCancelledFn isCancelled,
    void* user, char* errBuf, int errBufLen)
{
    if (errBuf && errBufLen > 0) errBuf[0] = '\0';

    const OpenJfxFfmpegFns* ff = openjfx_ffmpeg_loader_fns();
    if (!ff) {
        fmt_err(nullptr, errBuf, errBufLen,
                "ffmpeg runtime not loaded - set Media.setFfmpegDirectory()", 0);
        return 1;
    }
    if (!ff->remux_ok) {
        fmt_err(nullptr, errBuf, errBufLen,
                "the loaded ffmpeg lacks the avformat functions the mixer needs", 0);
        return 2;
    }
    if (!audioPath || !videoPath || !outPath ||
        !*audioPath || !*videoPath || !*outPath) {
        fmt_err(nullptr, errBuf, errBufLen, "audio/video/output path missing", 0);
        return 3;
    }

    InputSide audio, video;
    AVFormatContext* out = nullptr;
    int rc = 0;
    int result = 0;
    bool headerWritten = false;

    // --- open inputs -------------------------------------------------------
    if ((rc = ff->avformat_open_input(&audio.ctx, audioPath, nullptr, nullptr)) < 0) {
        fmt_err(ff, errBuf, errBufLen, "cannot open audio input", rc);
        result = 10; goto done;
    }
    if ((rc = ff->avformat_find_stream_info(audio.ctx, nullptr)) < 0) {
        fmt_err(ff, errBuf, errBufLen, "cannot read audio stream info", rc);
        result = 11; goto done;
    }
    if ((rc = ff->avformat_open_input(&video.ctx, videoPath, nullptr, nullptr)) < 0) {
        fmt_err(ff, errBuf, errBufLen, "cannot open video input", rc);
        result = 12; goto done;
    }
    if ((rc = ff->avformat_find_stream_info(video.ctx, nullptr)) < 0) {
        fmt_err(ff, errBuf, errBufLen, "cannot read video stream info", rc);
        result = 13; goto done;
    }

    audio.streamIndex = find_stream(audio.ctx, AVMEDIA_TYPE_AUDIO);
    video.streamIndex = find_stream(video.ctx, AVMEDIA_TYPE_VIDEO);
    if (audio.streamIndex < 0) {
        fmt_err(nullptr, errBuf, errBufLen, "no audio stream in the audio input", 0);
        result = 14; goto done;
    }
    if (video.streamIndex < 0) {
        fmt_err(nullptr, errBuf, errBufLen, "no video stream in the video input", 0);
        result = 15; goto done;
    }

    // --- output ------------------------------------------------------------
    if ((rc = ff->avformat_alloc_output_context2(&out, nullptr, "mp4", outPath)) < 0
        || out == nullptr) {
        fmt_err(ff, errBuf, errBufLen, "cannot create mp4 output", rc);
        result = 16; goto done;
    }

    // Video stream first (index 0 by convention), then audio.
    video.outStream = ff->avformat_new_stream(out, nullptr);
    audio.outStream = ff->avformat_new_stream(out, nullptr);
    if (!video.outStream || !audio.outStream) {
        fmt_err(nullptr, errBuf, errBufLen, "cannot allocate output streams", 0);
        result = 17; goto done;
    }
    if ((rc = ff->avcodec_parameters_copy(video.outStream->codecpar,
            video.ctx->streams[video.streamIndex]->codecpar)) < 0 ||
        (rc = ff->avcodec_parameters_copy(audio.outStream->codecpar,
            audio.ctx->streams[audio.streamIndex]->codecpar)) < 0) {
        fmt_err(ff, errBuf, errBufLen, "cannot copy codec parameters", rc);
        result = 18; goto done;
    }
    // Container-specific tags from the SOURCE container are wrong for
    // mp4 — let the muxer pick.
    video.outStream->codecpar->codec_tag = 0;
    audio.outStream->codecpar->codec_tag = 0;

    if ((rc = ff->avio_open(&out->pb, outPath, AVIO_FLAG_WRITE)) < 0) {
        fmt_err(ff, errBuf, errBufLen, "cannot open the output file for writing", rc);
        result = 19; goto done;
    }
    {
        // faststart relocates the moov atom to the head of the file at
        // trailer time so the mp4 can start playing before it is fully
        // present (progressive playback / serving). Costs the muxer one
        // extra pass over the output.
        AVDictionary* muxOpts = nullptr;
        if (flags & OPENJFX_REMUX_FLAG_FASTSTART) {
            ff->av_dict_set(&muxOpts, "movflags", "+faststart", 0);
        }
        rc = ff->avformat_write_header(out, muxOpts ? &muxOpts : nullptr);
        if (muxOpts) ff->av_dict_free(&muxOpts);
        if (rc < 0) {
            // Typically "codec not currently supported in container".
            fmt_err(ff, errBuf, errBufLen,
                    "cannot start the mp4 (are both codecs mp4-compatible?)", rc);
            result = 20; goto done;
        }
    }
    headerWritten = true;

    // --- interleave by presentation time ------------------------------------
    {
        audio.pkt = ff->av_packet_alloc();
        video.pkt = ff->av_packet_alloc();
        if (!audio.pkt || !video.pkt) {
            fmt_err(nullptr, errBuf, errBufLen, "out of memory", 0);
            result = 21; goto done;
        }

        double totalSeconds = 0.0;
        if (video.ctx->duration > 0)
            totalSeconds = (double)video.ctx->duration / AV_TIME_BASE;
        if (audio.ctx->duration > 0 &&
            (double)audio.ctx->duration / AV_TIME_BASE > totalSeconds)
            totalSeconds = (double)audio.ctx->duration / AV_TIME_BASE;

        double lastReported = -1.0;
        unsigned pktCount = 0;

        for (;;) {
            // The cancel poll is a JNI up-call — at remux speed (tens of
            // thousands of packets/sec) per-packet polling costs real
            // time. Every 256 packets keeps cancel latency in the low ms.
            if (isCancelled && (pktCount++ & 0xFF) == 0 && isCancelled(user)) {
                fmt_err(nullptr, errBuf, errBufLen, "cancelled", 0);
                result = 30; goto done;
            }

            bool haveA = load_next(ff, &audio);
            bool haveV = load_next(ff, &video);
            if (!haveA && !haveV) break; // both drained

            InputSide* next;
            if (haveA && haveV)
                next = (video.lastSeconds <= audio.lastSeconds) ? &video : &audio;
            else
                next = haveV ? &video : &audio;

            double written = next->lastSeconds;
            if ((rc = write_pending(ff, out, next)) < 0) {
                fmt_err(ff, errBuf, errBufLen, "write failed", rc);
                result = 22; goto done;
            }

            if (onProgress && totalSeconds > 0.0) {
                double frac = written / totalSeconds;
                if (frac > 1.0) frac = 1.0;
                if (frac - lastReported >= 0.01) {
                    lastReported = frac;
                    onProgress(frac, user);
                }
            }
        }

        // av_write_trailer must run exactly once — even on failure it
        // tears down muxer state, so clear the flag BEFORE the call or
        // the best-effort trailer in cleanup would run it twice
        // (use-after-free inside the muxer).
        headerWritten = false;
        if ((rc = ff->av_write_trailer(out)) < 0) {
            fmt_err(ff, errBuf, errBufLen, "cannot finalize the mp4", rc);
            result = 23; goto done;
        }
        if (onProgress) onProgress(1.0, user);
    }

done:
    if (audio.pkt) {
        if (audio.loaded) ff->av_packet_unref(audio.pkt);
        ff->av_packet_free(&audio.pkt);
    }
    if (video.pkt) {
        if (video.loaded) ff->av_packet_unref(video.pkt);
        ff->av_packet_free(&video.pkt);
    }
    if (out) {
        if (headerWritten) ff->av_write_trailer(out); // best-effort close
        if (out->pb) ff->avio_closep(&out->pb);
        ff->avformat_free_context(out);
    }
    if (audio.ctx) ff->avformat_close_input(&audio.ctx);
    if (video.ctx) ff->avformat_close_input(&video.ctx);
    return result;
}
