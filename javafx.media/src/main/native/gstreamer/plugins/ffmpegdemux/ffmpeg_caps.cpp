// ---------------------------------------------------------------------------
// ffmpeg_caps.cpp — see header. Reads only stable public AVCodecParameters /
// AVStream fields (codec_id, codec_type, extradata, width/height,
// sample_rate, ch_layout.nb_channels). The loader's ABI guard pins the
// runtime major to the headers we compile against, so these reads are safe.
// ---------------------------------------------------------------------------
#include "ffmpeg_caps.h"

// ffmpeg_loader.h pulls in the libav* public headers (avformat/avcodec/
// avutil) with the right pre-includes for Windows. We only need the types.
#include "../ffmpegwrapper/ffmpeg_loader.h"

extern "C"
GstCaps* openjfx_ffmpeg_caps_from_stream(const struct AVStream* st,
                                         gboolean* is_video)
{
    if (st == NULL || st->codecpar == NULL)
        return NULL;

    const AVCodecParameters* par = st->codecpar;
    const gboolean video = (par->codec_type == AVMEDIA_TYPE_VIDEO);
    const char* name = NULL;
    int mpegversion = 0;

    switch (par->codec_id) {
        // ===== Video =====
        case AV_CODEC_ID_H264:       name = "video/x-h264";  break;
        case AV_CODEC_ID_HEVC:       name = "video/x-h265";  break;
        case AV_CODEC_ID_AV1:        name = "video/x-av1";   break;
        case AV_CODEC_ID_VP8:        name = "video/x-vp8";   break;
        case AV_CODEC_ID_VP9:        name = "video/x-vp9";   break;
        case AV_CODEC_ID_H263:       name = "video/x-h263";  break;
        case AV_CODEC_ID_MPEG4:      name = "video/x-divx";  break; // ffmpegwrapper: divx/xvid -> MPEG4
        case AV_CODEC_ID_MPEG1VIDEO: name = "video/mpeg";    mpegversion = 1; break;
        case AV_CODEC_ID_MPEG2VIDEO: name = "video/mpeg";    mpegversion = 2; break;
        case AV_CODEC_ID_PRORES:     name = "video/x-prores";break;
        case AV_CODEC_ID_DVVIDEO:    name = "video/x-dv";    break;
        case AV_CODEC_ID_MJPEG:      name = "video/x-mjpeg"; break;
        case AV_CODEC_ID_THEORA:     name = "video/x-theora";break;
        case AV_CODEC_ID_FLV1:       name = "video/x-flash-video"; break;
        case AV_CODEC_ID_VC1:
        case AV_CODEC_ID_WMV1:
        case AV_CODEC_ID_WMV2:
        case AV_CODEC_ID_WMV3:       name = "video/x-wmv";   break;

        // ===== Audio =====
        case AV_CODEC_ID_AAC:        name = "audio/mpeg";    mpegversion = 4; break;
        case AV_CODEC_ID_MP3:        name = "audio/mpeg";    mpegversion = 1; break;
        case AV_CODEC_ID_VORBIS:     name = "audio/x-vorbis";break;
        case AV_CODEC_ID_OPUS:       name = "audio/x-opus";  break;
        case AV_CODEC_ID_FLAC:       name = "audio/x-flac";  break;
        case AV_CODEC_ID_AC3:        name = "audio/x-ac3";   break;
        case AV_CODEC_ID_EAC3:       name = "audio/x-eac3";  break;
        case AV_CODEC_ID_WMAV1:
        case AV_CODEC_ID_WMAV2:      name = "audio/x-wma";   break;
        case AV_CODEC_ID_PCM_ALAW:   name = "audio/x-alaw";  break;
        case AV_CODEC_ID_PCM_MULAW:  name = "audio/x-mulaw"; break;

        default:
            return NULL; // not a codec the ffmpegwrapper routing handles
    }

    GstCaps* caps = gst_caps_new_empty_simple(name);
    if (caps == NULL)
        return NULL;

    if (mpegversion != 0)
        gst_caps_set_simple(caps, "mpegversion", G_TYPE_INT, mpegversion, NULL);

    if (video) {
        if (par->width > 0 && par->height > 0)
            gst_caps_set_simple(caps, "width",  G_TYPE_INT, par->width,
                                      "height", G_TYPE_INT, par->height, NULL);
        if (st->avg_frame_rate.num > 0 && st->avg_frame_rate.den > 0)
            gst_caps_set_simple(caps, "framerate", GST_TYPE_FRACTION,
                                st->avg_frame_rate.num, st->avg_frame_rate.den, NULL);

        // H.264/H.265 carry a stream-format. avcC/hvcC extradata begins
        // with 0x01 (configurationVersion); annex-B has none.
        if (par->codec_id == AV_CODEC_ID_H264 || par->codec_id == AV_CODEC_ID_HEVC) {
            const gboolean iso = (par->extradata != NULL &&
                                  par->extradata_size > 0 &&
                                  par->extradata[0] == 1);
            const char* sf;
            if (par->codec_id == AV_CODEC_ID_H264)
                sf = iso ? "avc" : "byte-stream";
            else
                sf = iso ? "hvc1" : "byte-stream";
            gst_caps_set_simple(caps, "stream-format", G_TYPE_STRING, sf,
                                      "alignment",     G_TYPE_STRING, "au", NULL);
        }
    } else {
        if (par->sample_rate > 0)
            gst_caps_set_simple(caps, "rate", G_TYPE_INT, par->sample_rate, NULL);
        const int channels = par->ch_layout.nb_channels;
        if (channels > 0)
            gst_caps_set_simple(caps, "channels", G_TYPE_INT, channels, NULL);
        // AAC from a non-ADTS container is raw + ASC in codec_data; ADTS
        // streams have no extradata. ffmpegwrapper ignores this field but
        // it keeps the caps honest for any other downstream inspection.
        if (par->codec_id == AV_CODEC_ID_AAC)
            gst_caps_set_simple(caps, "stream-format", G_TYPE_STRING,
                                (par->extradata && par->extradata_size > 0) ? "raw" : "adts",
                                NULL);
    }

    // codec_data = libavcodec extradata, verbatim. ffmpegwrapper copies it
    // straight into AVCodecContext->extradata (it is libavcodec on both
    // sides, so the representation round-trips: avcC/hvcC, AAC ASC, Opus
    // OpusHead, Vorbis xiph headers, FLAC STREAMINFO, ...).
    if (par->extradata != NULL && par->extradata_size > 0) {
        GstBuffer* cd = gst_buffer_new_allocate(NULL, par->extradata_size, NULL);
        if (cd != NULL) {
            gst_buffer_fill(cd, 0, par->extradata, (gsize) par->extradata_size);
            gst_caps_set_simple(caps, "codec_data", GST_TYPE_BUFFER, cd, NULL);
            gst_buffer_unref(cd);
        }
    }

    if (is_video != NULL)
        *is_video = video;
    return caps;
}
