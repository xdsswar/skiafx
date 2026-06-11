// ---------------------------------------------------------------------------
// ffmpeg_caps — map a libavformat stream (AVStream/AVCodecParameters) to the
// GStreamer caps the JFX demuxer chain expects, so ffmpegdemux can hand its
// streams to the existing ffmpegwrapper / LoadDecoder routing unchanged.
//
// The mimetypes produced here MUST match ffmpegwrapper's
// mimetype_to_av_codec_id_str() and GstAVPlaybackPipeline::on_pad_added —
// they are the contract. Anything we can't map to one of those mimetypes
// returns NULL and the stream is dropped (st->discard).
// ---------------------------------------------------------------------------
#ifndef OPENJFX_FFMPEG_CAPS_H
#define OPENJFX_FFMPEG_CAPS_H

#include <gst/gst.h>

struct AVStream;

#ifdef __cplusplus
extern "C" {
#endif

// Build new caps (transfer-full; caller unrefs) describing `st` for
// downstream. Sets *is_video. Returns NULL when the codec isn't one we
// route to ffmpegwrapper — caller must then discard the stream.
GstCaps* openjfx_ffmpeg_caps_from_stream(const struct AVStream* st,
                                         gboolean* is_video);

#ifdef __cplusplus
}
#endif

#endif // OPENJFX_FFMPEG_CAPS_H
