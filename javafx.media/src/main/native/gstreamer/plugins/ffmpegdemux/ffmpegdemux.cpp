// ---------------------------------------------------------------------------
// ffmpegdemux — a libavformat-backed catch-all GStreamer demuxer.
//
// skia-fx: when the ffmpeg runtime is loaded, any container libavformat can
// open becomes playable. This element sits exactly where avidemux/flvdemux/
// matroskademux sit (CreateDemuxAVPipeline("ffmpegdemux", ...)): it pulls the
// raw byte stream from the progressbuffer source, drives libavformat through
// a custom AVIOContext, and emits one dynamic src pad per audio/video stream
// with the standard caps the existing ffmpegwrapper / LoadDecoder routing
// already consumes. The rest of the pipeline (decoders, audio/video bins,
// clock, watchdog) is reused unchanged.
//
// Hybrid routing: this is only ever instantiated for content types that have
// no dedicated gst demuxer (see GstPipelineFactory). It never overrides the
// proven mp4/mkv/avi/flv/flac paths.
//
// Memory safety is the primary concern (custom AVIO + streaming task + seek +
// teardown). The contract:
//   - AVFMT_FLAG_CUSTOM_IO so avformat_close_input does NOT free our pb.
//   - teardown order: stop+join the pad task (done by the framework on
//     deactivate, before change_state PAUSED->READY runs our close) ->
//     avformat_close_input -> av_freep(avio->buffer) -> avio_context_free.
//   - packet data is copied into owned GstBuffers; av_packet_unref each loop.
//   - a `flushing` flag aborts in-flight AVIO reads during a seek/teardown.
//   - every libav call goes through the null-checked loader table; missing
//     symbols degrade to a posted error, never an abort.
// ---------------------------------------------------------------------------
#include <gst/gst.h>
#include <stdio.h> // SEEK_SET / SEEK_CUR / SEEK_END

#include "../ffmpegwrapper/ffmpeg_loader.h"
#include "ffmpeg_caps.h"

GST_DEBUG_CATEGORY_STATIC(gst_ffmpegdemux_debug);
#define GST_CAT_DEFAULT gst_ffmpegdemux_debug

#define FFD_AVIO_BUF_SIZE 32768

// One output stream (a dynamically added src pad mapped to an AV stream).
struct FFDStream {
    GstPad*    pad;
    int        av_index;   // index into AVFormatContext->streams
    AVRational time_base;   // for PTS/DTS rescale
    gboolean   is_video;
    gboolean   sent_segment;
    gboolean   discont;
    gboolean   eos;
};

typedef struct _GstFFDemux {
    GstElement element;
    GstPad*    sinkpad;

    const OpenJfxFfmpegFns* ff;

    // libavformat
    AVFormatContext* fmt;
    AVIOContext*     avio;
    unsigned char*   avio_buf;   // only owns this BEFORE avio_alloc_context takes it
    AVPacket*        pkt;

    // pull state
    gint64        read_pos;      // byte offset for gst_pad_pull_range
    gint64        total_size;    // upstream byte length, -1 unknown
    gint          flushing;      // g_atomic_int_*; aborts AVIO reads mid-seek

    // streams
    FFDStream**   streams;       // n_streams entries (the ones we expose)
    guint         n_streams;
    FFDStream**   by_av_index;   // fmt->nb_streams entries; NULL for dropped
    guint         n_av_streams;

    gboolean      opened;
    GstSegment    segment;
} GstFFDemux;

typedef struct _GstFFDemuxClass {
    GstElementClass parent_class;
} GstFFDemuxClass;

#define GST_TYPE_FFDEMUX (gst_ffmpegdemux_get_type())
#define GST_FFDEMUX(obj) (G_TYPE_CHECK_INSTANCE_CAST((obj), GST_TYPE_FFDEMUX, GstFFDemux))

static GType gst_ffmpegdemux_get_type(void);

static GstStaticPadTemplate ffd_sink_factory =
    GST_STATIC_PAD_TEMPLATE("sink", GST_PAD_SINK, GST_PAD_ALWAYS,
        GST_STATIC_CAPS_ANY);

static GstStaticPadTemplate ffd_src_factory =
    GST_STATIC_PAD_TEMPLATE("src_%u", GST_PAD_SRC, GST_PAD_SOMETIMES,
        GST_STATIC_CAPS_ANY);

// ---- forward decls -------------------------------------------------------
static void     gst_ffmpegdemux_class_init(GstFFDemuxClass*);
static void     gst_ffmpegdemux_init(GstFFDemux*);
static void     gst_ffmpegdemux_finalize(GObject*);
static GstStateChangeReturn gst_ffmpegdemux_change_state(GstElement*, GstStateChange);

static gboolean ffd_sink_activate(GstPad*, GstObject*);
static gboolean ffd_sink_activate_mode(GstPad*, GstObject*, GstPadMode, gboolean);
static gboolean ffd_src_event(GstPad*, GstObject*, GstEvent*);
static gboolean ffd_src_query(GstPad*, GstObject*, GstQuery*);
static void     ffd_loop(gpointer user);
static void     ffd_close(GstFFDemux*);

// ---- G_DEFINE_TYPE expansion (mirrors ffmpegwrapper) ---------------------
#define gst_ffmpegdemux_parent_class parent_class
static gpointer gst_ffmpegdemux_parent_class = NULL;

static void gst_ffmpegdemux_class_intern_init(gpointer klass) {
    gst_ffmpegdemux_parent_class = g_type_class_peek_parent(klass);
    gst_ffmpegdemux_class_init((GstFFDemuxClass*) klass);
}

static GType gst_ffmpegdemux_get_type(void) {
    static volatile gsize type_id = 0;
    if (g_once_init_enter(&type_id)) {
        GType t = g_type_register_static_simple(
            GST_TYPE_ELEMENT, g_intern_static_string("GstFFmpegDemux"),
            sizeof(GstFFDemuxClass),
            (GClassInitFunc) gst_ffmpegdemux_class_intern_init,
            sizeof(GstFFDemux),
            (GInstanceInitFunc) gst_ffmpegdemux_init,
            (GTypeFlags) 0);
        g_once_init_leave(&type_id, t);
    }
    return type_id;
}

// ==========================================================================
// AVIO bridge — read/seek backed by the sink pad's pull-range.
// ==========================================================================
static int ffd_avio_read(void* opaque, uint8_t* buf, int size) {
    GstFFDemux* d = (GstFFDemux*) opaque;
    if (g_atomic_int_get(&d->flushing))
        return AVERROR_EXIT;
    if (size <= 0)
        return AVERROR_EXIT;

    GstBuffer* gbuf = NULL;
    GstFlowReturn fr = gst_pad_pull_range(d->sinkpad, (guint64) d->read_pos,
                                          (guint) size, &gbuf);
    if (fr != GST_FLOW_OK) {
        if (gbuf != NULL)
            gst_buffer_unref(gbuf);
        if (fr == GST_FLOW_EOS)
            return AVERROR_EOF;
        if (fr == GST_FLOW_FLUSHING)
            return AVERROR_EXIT;
        return AVERROR(EIO);
    }

    gsize avail = gst_buffer_get_size(gbuf);
    if (avail > (gsize) size)
        avail = (gsize) size;
    gsize got = (avail > 0) ? gst_buffer_extract(gbuf, 0, buf, avail) : 0;
    gst_buffer_unref(gbuf);

    if (got == 0)
        return AVERROR_EOF;
    d->read_pos += (gint64) got;
    return (int) got;
}

static int64_t ffd_avio_seek(void* opaque, int64_t offset, int whence) {
    GstFFDemux* d = (GstFFDemux*) opaque;
    if (whence & AVSEEK_SIZE)
        return d->total_size; // -1 when unknown — libavformat copes

    gint64 base;
    switch (whence & ~AVSEEK_FORCE) {
        case SEEK_SET: base = 0;            break;
        case SEEK_CUR: base = d->read_pos;  break;
        case SEEK_END:
            if (d->total_size < 0) return -1;
            base = d->total_size;
            break;
        default: return -1;
    }
    gint64 np = base + offset;
    if (np < 0)
        return -1;
    d->read_pos = np;
    return np;
}

// ==========================================================================
// Open: alloc AVIO + AVFormatContext, find streams, add src pads.
// Runs on the streaming thread (first loop iteration) with STREAM_LOCK held.
// ==========================================================================
static void ffd_query_total_size(GstFFDemux* d) {
    gint64 len = -1;
    if (gst_pad_peer_query_duration(d->sinkpad, GST_FORMAT_BYTES, &len) && len > 0)
        d->total_size = len;
    else
        d->total_size = -1;
}

static gboolean ffd_open(GstFFDemux* d) {
    const OpenJfxFfmpegFns* ff = d->ff;
    if (ff == NULL || !ff->demux_ok) {
        GST_ELEMENT_ERROR(d, LIBRARY, INIT,
            ("ffmpeg libavformat is not available"), (NULL));
        return FALSE;
    }

    ffd_query_total_size(d);

    d->avio_buf = (unsigned char*) ff->av_malloc(FFD_AVIO_BUF_SIZE);
    if (d->avio_buf == NULL) {
        GST_ELEMENT_ERROR(d, RESOURCE, NO_SPACE_LEFT, ("avio buffer alloc"), (NULL));
        return FALSE;
    }

    d->avio = ff->avio_alloc_context(d->avio_buf, FFD_AVIO_BUF_SIZE,
                                     0 /*write*/, d,
                                     ffd_avio_read, NULL, ffd_avio_seek);
    if (d->avio == NULL) {
        ff->av_freep(&d->avio_buf);
        GST_ELEMENT_ERROR(d, RESOURCE, FAILED, ("avio_alloc_context"), (NULL));
        return FALSE;
    }
    // The AVIOContext now owns avio_buf (and may realloc it). Stop tracking
    // it separately so teardown frees exactly avio->buffer, never twice.
    d->avio_buf = NULL;
    d->avio->seekable = (d->total_size >= 0) ? AVIO_SEEKABLE_NORMAL : 0;

    d->fmt = ff->avformat_alloc_context();
    if (d->fmt == NULL) {
        GST_ELEMENT_ERROR(d, RESOURCE, FAILED, ("avformat_alloc_context"), (NULL));
        return FALSE; // ffd_close frees avio
    }
    d->fmt->pb = d->avio;
    d->fmt->flags |= AVFMT_FLAG_CUSTOM_IO; // avformat_close_input won't free pb

    int r = ff->avformat_open_input(&d->fmt, NULL, NULL, NULL);
    if (r < 0) {
        // On failure libavformat frees the context and NULLs it, but leaves
        // our custom pb intact — ffd_close still releases the avio.
        GST_ELEMENT_ERROR(d, STREAM, DEMUX,
            ("ffmpeg could not open this stream"), ("av_err=%d", r));
        return FALSE;
    }

    r = ff->avformat_find_stream_info(d->fmt, NULL);
    if (r < 0) {
        GST_ELEMENT_ERROR(d, STREAM, DEMUX,
            ("ffmpeg could not read stream info"), ("av_err=%d", r));
        return FALSE;
    }

    const guint nb = (guint) d->fmt->nb_streams;
    d->by_av_index = g_new0(FFDStream*, nb > 0 ? nb : 1);
    d->n_av_streams = nb;
    d->streams = g_new0(FFDStream*, nb > 0 ? nb : 1);
    d->n_streams = 0;

    gst_segment_init(&d->segment, GST_FORMAT_TIME);

    for (guint i = 0; i < nb; ++i) {
        AVStream* st = d->fmt->streams[i];
        if (st == NULL || st->codecpar == NULL ||
            (st->codecpar->codec_type != AVMEDIA_TYPE_VIDEO &&
             st->codecpar->codec_type != AVMEDIA_TYPE_AUDIO)) {
            if (st != NULL) st->discard = AVDISCARD_ALL;
            continue;
        }

        gboolean is_video = FALSE;
        GstCaps* caps = openjfx_ffmpeg_caps_from_stream(st, &is_video);
        if (caps == NULL) {
            st->discard = AVDISCARD_ALL; // codec we don't route
            continue;
        }

        gchar* pad_name = g_strdup_printf("src_%u", i);
        GstPad* pad = gst_pad_new_from_static_template(&ffd_src_factory, pad_name);
        gst_pad_set_event_function(pad, GST_DEBUG_FUNCPTR(ffd_src_event));
        gst_pad_set_query_function(pad, GST_DEBUG_FUNCPTR(ffd_src_query));
        gst_pad_use_fixed_caps(pad);
        gst_pad_set_active(pad, TRUE);

        gchar* stream_id = gst_pad_create_stream_id(pad, GST_ELEMENT(d), pad_name);
        gst_pad_push_event(pad, gst_event_new_stream_start(stream_id));
        g_free(stream_id);
        gst_pad_push_event(pad, gst_event_new_caps(caps));
        gst_caps_unref(caps);
        g_free(pad_name);

        FFDStream* s = g_new0(FFDStream, 1);
        s->pad          = pad;
        s->av_index     = (int) i;
        s->time_base    = st->time_base;
        s->is_video     = is_video;
        s->sent_segment = FALSE;
        s->discont      = TRUE;
        s->eos          = FALSE;

        d->by_av_index[i]        = s;
        d->streams[d->n_streams] = s;
        d->n_streams++;

        gst_element_add_pad(GST_ELEMENT(d), pad); // emits pad-added -> JFX links
    }

    if (d->n_streams == 0) {
        GST_ELEMENT_ERROR(d, STREAM, DEMUX,
            ("no playable audio/video streams in this container"), (NULL));
        return FALSE;
    }

    gst_element_no_more_pads(GST_ELEMENT(d));
    d->opened = TRUE;
    return TRUE;
}

static gboolean ffd_all_eos(GstFFDemux* d) {
    for (guint i = 0; i < d->n_streams; ++i)
        if (!d->streams[i]->eos)
            return FALSE;
    return TRUE;
}

// ==========================================================================
// The streaming loop — one av_read_frame per call. Started by the pad task,
// so the sink STREAM_LOCK is held throughout each invocation.
// ==========================================================================
static void ffd_loop(gpointer user) {
    GstFFDemux* d = GST_FFDEMUX(user);
    const OpenJfxFfmpegFns* ff = d->ff;

    if (!d->opened) {
        if (!ffd_open(d)) {
            // open already posted a precise error; emit EOS so downstream
            // shuts down cleanly, then stop the task.
            for (guint i = 0; i < d->n_streams; ++i)
                gst_pad_push_event(d->streams[i]->pad, gst_event_new_eos());
            gst_pad_pause_task(d->sinkpad);
            return;
        }
    }

    int r = ff->av_read_frame(d->fmt, d->pkt);
    if (r < 0) {
        // EOF or unrecoverable read error — av_read_frame leaves pkt clean.
        for (guint i = 0; i < d->n_streams; ++i) {
            if (!d->streams[i]->eos) {
                gst_pad_push_event(d->streams[i]->pad, gst_event_new_eos());
                d->streams[i]->eos = TRUE;
            }
        }
        gst_pad_pause_task(d->sinkpad);
        return;
    }

    FFDStream* s = NULL;
    if (d->pkt->stream_index >= 0 &&
        (guint) d->pkt->stream_index < d->n_av_streams)
        s = d->by_av_index[d->pkt->stream_index];

    if (s != NULL && !s->eos && d->pkt->size > 0) {
        GstBuffer* buf = gst_buffer_new_allocate(NULL, (gsize) d->pkt->size, NULL);
        if (buf != NULL) {
            gst_buffer_fill(buf, 0, d->pkt->data, (gsize) d->pkt->size);

            const AVRational tb = s->time_base;
            // Negative pts/dts (edit-list/audio-priming) would become huge
            // when cast to unsigned — drop them to "no timestamp" instead.
            if (d->pkt->pts != AV_NOPTS_VALUE && d->pkt->pts >= 0 && tb.den > 0)
                GST_BUFFER_PTS(buf) = gst_util_uint64_scale(
                    (guint64) d->pkt->pts, GST_SECOND * (guint64) tb.num, (guint64) tb.den);
            if (d->pkt->dts != AV_NOPTS_VALUE && d->pkt->dts >= 0 && tb.den > 0)
                GST_BUFFER_DTS(buf) = gst_util_uint64_scale(
                    (guint64) d->pkt->dts, GST_SECOND * (guint64) tb.num, (guint64) tb.den);
            if (d->pkt->duration > 0 && tb.den > 0)
                GST_BUFFER_DURATION(buf) = gst_util_uint64_scale(
                    (guint64) d->pkt->duration, GST_SECOND * (guint64) tb.num, (guint64) tb.den);

            if (!(d->pkt->flags & AV_PKT_FLAG_KEY))
                GST_BUFFER_FLAG_SET(buf, GST_BUFFER_FLAG_DELTA_UNIT);
            if (s->discont) {
                GST_BUFFER_FLAG_SET(buf, GST_BUFFER_FLAG_DISCONT);
                s->discont = FALSE;
            }

            if (!s->sent_segment) {
                gst_pad_push_event(s->pad, gst_event_new_segment(&d->segment));
                s->sent_segment = TRUE;
            }

            GstFlowReturn fr = gst_pad_push(s->pad, buf);
            if (fr == GST_FLOW_EOS) {
                s->eos = TRUE;
            } else if (fr != GST_FLOW_OK && fr != GST_FLOW_NOT_LINKED &&
                       fr != GST_FLOW_FLUSHING) {
                ff->av_packet_unref(d->pkt);
                GST_ELEMENT_ERROR(d, STREAM, FAILED,
                    ("downstream error"), ("flow=%s", gst_flow_get_name(fr)));
                gst_pad_pause_task(d->sinkpad);
                return;
            }
        }
    }

    ff->av_packet_unref(d->pkt);

    if (d->n_streams > 0 && ffd_all_eos(d))
        gst_pad_pause_task(d->sinkpad);
}

// ==========================================================================
// Seek — the standard pull-mode demuxer dance, memory-safe.
// ==========================================================================
static gboolean ffd_do_seek(GstFFDemux* d, GstEvent* event) {
    const OpenJfxFfmpegFns* ff = d->ff;
    gdouble rate;
    GstFormat format;
    GstSeekFlags flags;
    GstSeekType start_type, stop_type;
    gint64 start, stop;
    gst_event_parse_seek(event, &rate, &format, &flags,
                         &start_type, &start, &stop_type, &stop);

    if (format != GST_FORMAT_TIME) {
        GST_WARNING_OBJECT(d, "only TIME-format seeks are supported");
        return FALSE;
    }
    if (!d->opened || d->fmt == NULL)
        return FALSE;

    const gboolean flush = (flags & GST_SEEK_FLAG_FLUSH) != 0;

    if (flush) {
        gst_pad_push_event(d->sinkpad, gst_event_new_flush_start());   // upstream
        for (guint i = 0; i < d->n_streams; ++i)
            gst_pad_push_event(d->streams[i]->pad, gst_event_new_flush_start());
        g_atomic_int_set(&d->flushing, 1);
    }

    gst_pad_pause_task(d->sinkpad);
    GST_PAD_STREAM_LOCK(d->sinkpad); // wait for the loop to finish its iteration
    g_atomic_int_set(&d->flushing, 0);

    int64_t ts_av = (int64_t) gst_util_uint64_scale(
        (guint64) (start < 0 ? 0 : start), AV_TIME_BASE, GST_SECOND);
    int seek_flags = AVSEEK_FLAG_BACKWARD; // land on the keyframe at/just-before
    int r = ff->av_seek_frame(d->fmt, -1, ts_av, seek_flags);
    if (r < 0)
        GST_WARNING_OBJECT(d, "av_seek_frame failed (av_err=%d)", r);

    // Update the outgoing segment and re-arm per-stream segment/discont.
    gst_segment_do_seek(&d->segment, rate, GST_FORMAT_TIME, flags,
                        start_type, start, stop_type, stop, NULL);
    for (guint i = 0; i < d->n_streams; ++i) {
        d->streams[i]->sent_segment = FALSE;
        d->streams[i]->discont      = TRUE;
        d->streams[i]->eos          = FALSE;
    }

    if (flush) {
        gst_pad_push_event(d->sinkpad, gst_event_new_flush_stop(TRUE)); // upstream
        for (guint i = 0; i < d->n_streams; ++i)
            gst_pad_push_event(d->streams[i]->pad, gst_event_new_flush_stop(TRUE));
    }

    gst_pad_start_task(d->sinkpad, (GstTaskFunction) ffd_loop, d, NULL);
    GST_PAD_STREAM_UNLOCK(d->sinkpad);
    return (r >= 0);
}

static gboolean ffd_src_event(GstPad* pad, GstObject* parent, GstEvent* event) {
    GstFFDemux* d = GST_FFDEMUX(parent);
    switch (GST_EVENT_TYPE(event)) {
        case GST_EVENT_SEEK: {
            gboolean ok = ffd_do_seek(d, event);
            gst_event_unref(event);
            return ok;
        }
        default:
            return gst_pad_event_default(pad, parent, event);
    }
}

static gboolean ffd_src_query(GstPad* pad, GstObject* parent, GstQuery* query) {
    GstFFDemux* d = GST_FFDEMUX(parent);
    switch (GST_QUERY_TYPE(query)) {
        case GST_QUERY_DURATION: {
            GstFormat fmt;
            gst_query_parse_duration(query, &fmt, NULL);
            if (fmt == GST_FORMAT_TIME && d->fmt != NULL &&
                d->fmt->duration != AV_NOPTS_VALUE) {
                gint64 dur = (gint64) gst_util_uint64_scale(
                    (guint64) d->fmt->duration, GST_SECOND, AV_TIME_BASE);
                gst_query_set_duration(query, GST_FORMAT_TIME, dur);
                return TRUE;
            }
            return FALSE;
        }
        case GST_QUERY_SEEKING: {
            GstFormat fmt;
            gst_query_parse_seeking(query, &fmt, NULL, NULL, NULL);
            if (fmt == GST_FORMAT_TIME) {
                gint64 dur = GST_CLOCK_TIME_NONE;
                if (d->fmt != NULL && d->fmt->duration != AV_NOPTS_VALUE)
                    dur = (gint64) gst_util_uint64_scale(
                        (guint64) d->fmt->duration, GST_SECOND, AV_TIME_BASE);
                gst_query_set_seeking(query, GST_FORMAT_TIME,
                                      (d->total_size >= 0), 0, dur);
                return TRUE;
            }
            return FALSE;
        }
        default:
            return gst_pad_query_default(pad, parent, query);
    }
}

// ==========================================================================
// Sink-pad activation — pull mode only (libavformat needs random access).
// ==========================================================================
static gboolean ffd_sink_activate(GstPad* pad, GstObject* parent) {
    GstQuery* q = gst_query_new_scheduling();
    gboolean pull = FALSE;
    if (gst_pad_peer_query(pad, q))
        pull = gst_query_has_scheduling_mode_with_flags(
            q, GST_PAD_MODE_PULL, GST_SCHEDULING_FLAG_SEEKABLE);
    gst_query_unref(q);

    if (pull)
        return gst_pad_activate_mode(pad, GST_PAD_MODE_PULL, TRUE);

    GST_ERROR_OBJECT(parent, "ffmpegdemux needs a seekable pull-mode source");
    return FALSE;
}

static gboolean ffd_sink_activate_mode(GstPad* pad, GstObject* parent,
                                       GstPadMode mode, gboolean active) {
    GstFFDemux* d = GST_FFDEMUX(parent);
    if (mode != GST_PAD_MODE_PULL)
        return FALSE;

    if (active) {
        g_atomic_int_set(&d->flushing, 0);
        return gst_pad_start_task(pad, (GstTaskFunction) ffd_loop, d, NULL);
    } else {
        g_atomic_int_set(&d->flushing, 1); // unblock any in-flight AVIO read
        return gst_pad_stop_task(pad);      // joins the task
    }
}

// ==========================================================================
// Teardown — runs in change_state PAUSED->READY, AFTER the framework has
// deactivated the sink pad (loop joined). Safe to free libav state here.
// ==========================================================================
static void ffd_close(GstFFDemux* d) {
    const OpenJfxFfmpegFns* ff = d->ff;

    // Remove the dynamic src pads first (we added them with add_pad).
    if (d->streams != NULL) {
        for (guint i = 0; i < d->n_streams; ++i) {
            FFDStream* s = d->streams[i];
            if (s == NULL) continue;
            if (s->pad != NULL) {
                gst_pad_set_active(s->pad, FALSE);
                gst_element_remove_pad(GST_ELEMENT(d), s->pad);
            }
            g_free(s);
        }
        g_free(d->streams);
        d->streams = NULL;
    }
    g_free(d->by_av_index);
    d->by_av_index = NULL;
    d->n_streams = 0;
    d->n_av_streams = 0;

    if (ff != NULL) {
        if (d->fmt != NULL)
            ff->avformat_close_input(&d->fmt); // CUSTOM_IO: leaves pb alone
        if (d->avio != NULL) {
            if (d->avio->buffer != NULL)
                ff->av_freep(&d->avio->buffer); // the live (possibly realloc'd) buffer
            ff->avio_context_free(&d->avio);
        }
        if (d->avio_buf != NULL)
            ff->av_freep(&d->avio_buf); // only set if avio_alloc_context failed
    }
    d->fmt = NULL;
    d->avio = NULL;
    d->avio_buf = NULL;
    d->opened = FALSE;
    d->read_pos = 0;
    d->total_size = -1;
}

static GstStateChangeReturn
gst_ffmpegdemux_change_state(GstElement* element, GstStateChange transition) {
    GstFFDemux* d = GST_FFDEMUX(element);

    if (transition == GST_STATE_CHANGE_READY_TO_PAUSED) {
        // Resolve the loader and allocate the reusable packet BEFORE the
        // parent activates the sink pad (which starts the loop).
        d->ff = openjfx_ffmpeg_loader_fns();
        if (d->ff == NULL || !d->ff->demux_ok) {
            GST_ELEMENT_ERROR(d, LIBRARY, INIT,
                ("ffmpeg libavformat is not available for the catch-all demuxer"),
                (NULL));
            return GST_STATE_CHANGE_FAILURE;
        }
        d->pkt = d->ff->av_packet_alloc();
        if (d->pkt == NULL) {
            GST_ELEMENT_ERROR(d, RESOURCE, NO_SPACE_LEFT, ("av_packet_alloc"), (NULL));
            return GST_STATE_CHANGE_FAILURE;
        }
    }

    GstStateChangeReturn ret =
        GST_ELEMENT_CLASS(parent_class)->change_state(element, transition);
    if (ret == GST_STATE_CHANGE_FAILURE)
        return ret;

    if (transition == GST_STATE_CHANGE_PAUSED_TO_READY) {
        ffd_close(d); // sink pad already deactivated -> loop joined
        if (d->pkt != NULL && d->ff != NULL)
            d->ff->av_packet_free(&d->pkt);
        d->pkt = NULL;
    }

    return ret;
}

// ==========================================================================
// GObject / GstElement boilerplate
// ==========================================================================
static void gst_ffmpegdemux_class_init(GstFFDemuxClass* klass) {
    GObjectClass*    gobj = G_OBJECT_CLASS(klass);
    GstElementClass* elem = GST_ELEMENT_CLASS(klass);

    gobj->finalize = gst_ffmpegdemux_finalize;
    elem->change_state = gst_ffmpegdemux_change_state;

    gst_element_class_add_pad_template(elem,
        gst_static_pad_template_get(&ffd_sink_factory));
    gst_element_class_add_pad_template(elem,
        gst_static_pad_template_get(&ffd_src_factory));

    gst_element_class_set_static_metadata(elem,
        "FFmpeg demuxer (skia-fx)",
        "Codec/Demuxer",
        "Demux any libavformat container (runtime-loaded ffmpeg)",
        "JFXMedia <openjfx@openjdk.org>");
}

static void gst_ffmpegdemux_init(GstFFDemux* d) {
    d->sinkpad = gst_pad_new_from_static_template(&ffd_sink_factory, "sink");
    gst_pad_set_activate_function(d->sinkpad,
        GST_DEBUG_FUNCPTR(ffd_sink_activate));
    gst_pad_set_activatemode_function(d->sinkpad,
        GST_DEBUG_FUNCPTR(ffd_sink_activate_mode));
    gst_element_add_pad(GST_ELEMENT(d), d->sinkpad);

    d->ff           = NULL;
    d->fmt          = NULL;
    d->avio         = NULL;
    d->avio_buf     = NULL;
    d->pkt          = NULL;
    d->read_pos     = 0;
    d->total_size   = -1;
    d->flushing     = 0;
    d->streams      = NULL;
    d->n_streams    = 0;
    d->by_av_index  = NULL;
    d->n_av_streams = 0;
    d->opened       = FALSE;
    gst_segment_init(&d->segment, GST_FORMAT_TIME);
}

static void gst_ffmpegdemux_finalize(GObject* obj) {
    GstFFDemux* d = GST_FFDEMUX(obj);
    // Normal teardown happens in PAUSED->READY; this is the last-resort net
    // for an element disposed without a full state-down cycle.
    ffd_close(d);
    if (d->pkt != NULL && d->ff != NULL)
        d->ff->av_packet_free(&d->pkt);
    G_OBJECT_CLASS(parent_class)->finalize(obj);
}

// ==========================================================================
// Plugin registration entry — called from fxplugins.c::fxplugins_init.
// ==========================================================================
extern "C" gboolean ffmpegdemux_init(GstPlugin* plugin) {
    GST_DEBUG_CATEGORY_INIT(gst_ffmpegdemux_debug,
        "ffmpegdemux", 0, "libavformat-backed catch-all demuxer");
    // Rank NONE: never auto-plugged by typefind; only used when the JFX
    // pipeline factory explicitly creates it by name for the catch-all
    // content type. Keeps the proven gst demuxers preferred.
    return gst_element_register(plugin, "ffmpegdemux",
        GST_RANK_NONE, GST_TYPE_FFDEMUX);
}
