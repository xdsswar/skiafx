/*
 * Copyright (c) 2010, 2022, Oracle and/or its affiliates. All rights reserved.
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

#include "progressbuffer.h"
#include "cache.h"
#include "fxplugins_common.h"

#if ENABLE_PULL_MODE
#define NO_RANGE_REQUEST -1
#endif

/* skia-fx: cached OPENJFX_MEDIA_VERBOSE gate — getrange/chain are
 * per-buffer hot paths; never call g_getenv there. */
static gboolean pb_verbose(void)
{
    static gsize once = 0;
    static gboolean on = FALSE;
    if (g_once_init_enter(&once))
    {
        const gchar* v = g_getenv("OPENJFX_MEDIA_VERBOSE");
        on = (v != NULL && v[0] != '\0' && v[0] != '0');
        g_once_init_leave(&once, 1);
    }
    return on;
}

/***********************************************************************************
 * Debug category init
 ***********************************************************************************/
GST_DEBUG_CATEGORY (progress_buffer_debug);
#define GST_CAT_DEFAULT progress_buffer_debug

#define ELEMENT_DESCRIPTION "JFX Progress buffer element"

/***********************************************************************************
 * Properties
 ***********************************************************************************/
enum
{
    PROP_0,
    PROP_THRESHOLD,
    PROP_BANDWIDTH,
    PROP_PREBUFFER_TIME,
    PROP_WAIT_TOLERANCE
};

/***********************************************************************************
 * Element structures are hidden from outside
 ***********************************************************************************/
#define EOS_SIGNAL_LIMIT 1 // Send EOS notification only this amount of times

struct EosStatus
{
    gboolean      eos;
    gint          signal_limit;
};

struct _ProgressBuffer
{
    GstElement    parent;

    GstPad        *sinkpad;
    GstPad        *srcpad;

    GMutex        lock;
    GCond         add_cond;

    // Cache infrastructure
    Cache         *cache;
    GstEvent      *pending_src_event;
    gint64         cache_read_offset;

    GstSegment    sink_segment;
    gdouble       last_update;
    gdouble       threshold; // property controlled.

    guint64       subtotal;  // bandwidth accumulator.
    gdouble       bandwidth; // property accessible.
    gdouble       prebuffer_time; // property controlled.
    gdouble       wait_tolerance; // property controlled.
    GTimer        *bandwidth_timer;

    gboolean      unexpected;
    GstFlowReturn srcresult;

    struct EosStatus  eos_status;

    gboolean      instant_seek;
    gboolean      is_source_seeking;

    // skia-fx: parsed fragmented-mp4 segment index (sidx). Maps seek TIME
    // to the exact fragment (moof) byte offset, so a TIME seek qtdemux
    // forwards (it can't byte-seek fragmented mp4 itself) lands on a clean
    // fragment boundary instead of mid-fragment (which corrupts). Parsed
    // once, lazily, from the cached file head. See progress_buffer_parse_sidx.
    gboolean      sidx_parsed;     // parse attempted
    gboolean      sidx_valid;      // parse succeeded
    guint32       sidx_timescale;  // ticks per second
    guint         sidx_count;      // fragment count
    gint64*       sidx_time;       // [count+1] cumulative start ticks
    gint64*       sidx_byte;       // [count+1] cumulative start byte offset
    // The exact TIME (ns) the demuxer asked to seek to, before we snapped it
    // down to the enclosing fragment's byte. The fragment starts a little
    // earlier than this; the TIME segment we then emit to qtdemux carries
    // THIS requested time as its start so the sink clips the pre-target
    // frames and the video resumes in lock-step with the (sample-accurate)
    // audio instead of a fragment-length behind. -1 = none pending.
    gint64        req_seek_time_ns;

#if ENABLE_PULL_MODE
    gint64       range_start;
    gint64       range_stop;
    GThread     *monitor_thread;
#endif
};

struct _ProgressBufferClass
{
    GstElementClass parent;
};

/***********************************************************************************
 * Substitution for
 * G_DEFINE_TYPE(ProgressBuffer, progress_buffer, GstElement, GST_TYPE_ELEMENT);
 ***********************************************************************************/
#define progress_buffer_parent_class parent_class
static void progress_buffer_init          (ProgressBuffer      *self);
static void progress_buffer_class_init    (ProgressBufferClass *klass);
static gpointer progress_buffer_parent_class = NULL;
static void     progress_buffer_class_intern_init (gpointer klass)
{
    progress_buffer_parent_class = g_type_class_peek_parent (klass);
    progress_buffer_class_init ((ProgressBufferClass*) klass);
}

GType progress_buffer_get_type (void)
{
    static volatile gsize gonce_data = 0;
// INLINE - g_once_init_enter()
    if (g_once_init_enter (&gonce_data))
    {
        GType _type;
        _type = g_type_register_static_simple (GST_TYPE_ELEMENT,
               g_intern_static_string ("ProgressBuffer"),
               sizeof (ProgressBufferClass),
               (GClassInitFunc) progress_buffer_class_intern_init,
               sizeof(ProgressBuffer),
               (GInstanceInitFunc) progress_buffer_init,
               (GTypeFlags) 0);
        g_once_init_leave (&gonce_data, (gsize) _type);
    }
    return (GType) gonce_data;
}

/***********************************************************************************
 * Init stuff
 ***********************************************************************************/
static GstStaticPadTemplate sink_template = GST_STATIC_PAD_TEMPLATE ("sink",
    GST_PAD_SINK, GST_PAD_ALWAYS, GST_STATIC_CAPS_ANY);

static GstStaticPadTemplate source_template = GST_STATIC_PAD_TEMPLATE ("src",
    GST_PAD_SRC, GST_PAD_SOMETIMES, GST_STATIC_CAPS_ANY);

/***********************************************************************************
 * Instance init and forward declarations
 ***********************************************************************************/
static void             progress_buffer_set_property (GObject *object, guint property_id,
                                                      const GValue *value, GParamSpec *pspec);
static void             progress_buffer_get_property (GObject *object, guint property_id,
                                                      GValue *value, GParamSpec *pspec);
static void             progress_buffer_finalize (GObject *object);
static GstStateChangeReturn progress_buffer_change_state (GstElement *element,
                                                          GstStateChange transition);
static GstFlowReturn    progress_buffer_chain(GstPad *pad, GstObject *parent, GstBuffer *data);
static gboolean         progress_buffer_activatemode(GstPad *pad, GstObject *parent, GstPadMode mode, gboolean active);
static gboolean         progress_buffer_sink_event(GstPad *pad, GstObject *parent, GstEvent *event);
static gboolean         progress_buffer_src_event(GstPad *pad, GstObject *parent, GstEvent *event);
static void             progress_buffer_loop(void *data);
static void             progress_buffer_flush_data(ProgressBuffer *buffer);

static gboolean         progress_buffer_checkgetrange(GstPad *pad);
static GstFlowReturn    progress_buffer_getrange(GstPad *pad, GstObject *parent, guint64 start_position,
                                                 guint length, GstBuffer **data);
#if ENABLE_PULL_MODE
static gpointer         progress_buffer_range_monitor(ProgressBuffer *element);
#endif

static void             progress_buffer_set_pending_event(ProgressBuffer *element, GstEvent* new_event);
static GstEventType     progress_buffer_get_pending_event_type(ProgressBuffer *element);

/**
 * progress_buffer_class_init()
 *
 * Sets up the GLib object oriented C class structure for ProgressBuffer.
 */
static void progress_buffer_class_init (ProgressBufferClass *klass)
{
    GObjectClass *gobject_class = G_OBJECT_CLASS (klass);
    GstElementClass *element_class = GST_ELEMENT_CLASS (klass);

    gst_element_class_set_static_metadata (element_class,
        "Progressive download plugin",
        "Element",
        "Progressively stores incoming data in memory or file",
        "Oracle Corporation");

    gst_element_class_add_pad_template (element_class,
                                        gst_static_pad_template_get (&sink_template));
    gst_element_class_add_pad_template (element_class,
                                        gst_static_pad_template_get (&source_template));

    gobject_class->set_property = GST_DEBUG_FUNCPTR(progress_buffer_set_property);
    gobject_class->get_property = GST_DEBUG_FUNCPTR(progress_buffer_get_property);
    gobject_class->finalize = GST_DEBUG_FUNCPTR(progress_buffer_finalize);
    GST_ELEMENT_CLASS (klass)->change_state = GST_DEBUG_FUNCPTR(progress_buffer_change_state);

    g_object_class_install_property (gobject_class, PROP_THRESHOLD,
                                     g_param_spec_double ("threshold",
                                                          "Message threshold",
                                                          "Message emission threshold in percents.",
                                                          0.0  /* minimum value */,
                                                          100.0 /* maximum value */,
                                                          1.0  /* default value */,
                                                          G_PARAM_READWRITE | G_PARAM_CONSTRUCT));

    g_object_class_install_property (gobject_class, PROP_BANDWIDTH,
                                     g_param_spec_double ("bandwidth",
                                                          "Network bandwidth",
                                                          "Network bandwidth in bytes/second",
                                                          0.0  /* minimum value */,
                                                          G_MAXDOUBLE /* maximum value */,
                                                          0.0  /* default value */,
                                                          G_PARAM_READABLE));

    g_object_class_install_property (gobject_class, PROP_PREBUFFER_TIME,
                                     g_param_spec_double ("prebuffer-time",
                                                          "Prebuffer time",
                                                          "Controls prebuffer for prebuffer-time*bandwidth before emitting RANGE_READY event.",
                                                          0.0  /* minimum value */,
                                                          20.0 /* maximum value */,
                                                          2.0  /* default value */,
                                                          G_PARAM_READWRITE | G_PARAM_CONSTRUCT));

    g_object_class_install_property (gobject_class, PROP_WAIT_TOLERANCE,
                                     g_param_spec_double ("wait-tolerance",
                                                          "Wait tolerance timeout",
                                                          "Threshold timeout before emitting seek request to the specified range position.",
                                                          0.0  /* minimum value */,
                                                          20.0 /* maximum value */,
                                                          2.0  /* default value */,
                                                          G_PARAM_READWRITE | G_PARAM_CONSTRUCT));

    cache_static_init();
}

/**
 * progress_buffer_init()
 *
 * Initializer.  Automatically declared in the G_DEFINE_TYPE macro above.  Should be
 * only called by GStreamer.
 */
static void progress_buffer_init(ProgressBuffer *element)
{
    element->sinkpad = gst_pad_new_from_template (gst_element_class_get_pad_template (GST_ELEMENT_GET_CLASS(element), "sink"), "sink");
    gst_pad_set_chain_function       (element->sinkpad, GST_DEBUG_FUNCPTR(progress_buffer_chain));
    gst_pad_set_event_function       (element->sinkpad, GST_DEBUG_FUNCPTR(progress_buffer_sink_event));
    gst_element_add_pad (GST_ELEMENT (element), element->sinkpad);

    element->srcpad = NULL;
    element->cache = NULL;
    element->cache_read_offset = 0;
    g_mutex_init(&element->lock);
    g_cond_init(&element->add_cond);
    element->bandwidth_timer = g_timer_new();
    element->is_source_seeking = FALSE;

    // skia-fx: sidx (fragmented-mp4 fragment index) parsed lazily once the
    // head is cached; persists across seeks/flushes (head never changes).
    element->sidx_parsed = FALSE;
    element->sidx_valid = FALSE;
    element->sidx_timescale = 0;
    element->sidx_count = 0;
    element->sidx_time = NULL;
    element->sidx_byte = NULL;
    element->req_seek_time_ns = -1;

#if ENABLE_PULL_MODE
    element->monitor_thread = NULL;
#endif

    progress_buffer_flush_data(element);
}

/**
 * progress_buffer_set_property()
 *
 * Function to set properties on the element.  This is where we can add custom properties.
 */
static void progress_buffer_set_property (GObject *object, guint property_id,
                                          const GValue *value, GParamSpec *pspec)
{
    ProgressBuffer *element = PROGRESS_BUFFER(object);
    switch (property_id)
    {
        case PROP_THRESHOLD:
            element->threshold = g_value_get_double(value);
            break;
        case PROP_PREBUFFER_TIME:
            element->prebuffer_time = g_value_get_double(value);
            break;
        case PROP_WAIT_TOLERANCE:
            element->wait_tolerance = g_value_get_double(value);
            break;

        default:
            break;
    }
}

/**
 * progress_buffer_get_property()
 *
 * Function to get properties from the element.  This is where we can add custom properties.
 */
static void progress_buffer_get_property (GObject *object, guint property_id,
                                          GValue *value, GParamSpec *pspec)
{
    ProgressBuffer *element = PROGRESS_BUFFER(object);
    switch (property_id)
    {
        case PROP_THRESHOLD:
            g_value_set_double(value, element->threshold);
            break;

        case PROP_BANDWIDTH:
            g_value_set_double(value, element->bandwidth);
            break;

        case PROP_PREBUFFER_TIME:
            g_value_set_double(value, element->prebuffer_time);
            break;

        case PROP_WAIT_TOLERANCE:
            g_value_set_double(value, element->wait_tolerance);
            break;

        default:
            break;
    }
}

/**
 * progress_buffer_finalize()
 *
 * Equivalent of destructor.
 */
static void progress_buffer_finalize (GObject *object)
{
    ProgressBuffer *element = PROGRESS_BUFFER(object);

    if (element->pending_src_event)
        gst_event_unref(element->pending_src_event); // INLINE - gst_event_unref()

    if (element->cache)
        destroy_cache(element->cache);

    g_mutex_clear(&element->lock);
    g_cond_clear(&element->add_cond);
    g_timer_destroy(element->bandwidth_timer);

    // skia-fx: release the parsed sidx tables.
    g_free(element->sidx_time);
    g_free(element->sidx_byte);

    G_OBJECT_CLASS (parent_class)->finalize (object);
}

/***********************************************************************************/
static inline void reset_eos(ProgressBuffer *element, gboolean clear_pending_event)
{
    element->eos_status.eos = FALSE;
    element->eos_status.signal_limit = EOS_SIGNAL_LIMIT;

    if (clear_pending_event)
        progress_buffer_set_pending_event(element, NULL);
}

static inline gboolean pending_eos(ProgressBuffer *element)
{
    gboolean result = (element->eos_status.eos && element->eos_status.signal_limit > 0);

    if (result)
        element->eos_status.signal_limit--;

    return result;
}

/***********************************************************************************
 * Pad functions
 ***********************************************************************************/
/**
 * progress_buffer_activatepull_src()
 *
 * Set the source pad's pull mode.
 */
static gboolean progress_buffer_activatepull_src(GstPad *pad, GstObject *parent, gboolean active)
{
#if ENABLE_PULL_MODE
    ProgressBuffer *element = PROGRESS_BUFFER(parent);

    if (active) // Start a custom task in pull mode for monitoring pull_range requests
    {
        g_mutex_lock(&element->lock);
        element->srcresult = GST_FLOW_OK;
        // Do not clear pending events, since we might get events before pad is activated.
        reset_eos(element, FALSE);
        element->unexpected = FALSE;
        g_mutex_unlock(&element->lock);

        if (element->monitor_thread == NULL)
            element->monitor_thread = g_thread_new(NULL, (GThreadFunc)progress_buffer_range_monitor,
                                                        element);
        return (element->monitor_thread != NULL);
    }
    else if (!active && element->monitor_thread != NULL) // Stop the custom task if it's been created
    {
        g_mutex_lock(&element->lock);
        element->srcresult = GST_FLOW_FLUSHING;
        g_cond_broadcast(&element->add_cond);
        g_mutex_unlock(&element->lock);

        g_thread_join(element->monitor_thread);
        element->monitor_thread = NULL;
    }

    return TRUE;
#else
    return FALSE;
#endif
}

/**
 * progress_buffer_activatepush_src()
 *
 * Set the source pad's push mode.
 */
static gboolean progress_buffer_activatepush_src(GstPad *pad, GstObject *parent, gboolean active)
{
    ProgressBuffer *element = PROGRESS_BUFFER(parent);

    if (active)
    {
        g_mutex_lock(&element->lock);
        element->srcresult = GST_FLOW_OK;
        // Do not clear pending events, since we might get events before pad is activated.
        reset_eos(element, FALSE);
        element->unexpected = FALSE;
        g_mutex_unlock(&element->lock);

        if (gst_pad_is_linked(pad))
            return gst_pad_start_task(pad, progress_buffer_loop, element, NULL);
        else
            return FALSE;
    }
    else
    {
        g_mutex_lock(&element->lock);
        element->srcresult = GST_FLOW_FLUSHING;
        g_cond_broadcast(&element->add_cond);
        g_mutex_unlock(&element->lock);

        return gst_pad_stop_task(pad);
    }
}

static gboolean progress_buffer_activatemode(GstPad *pad, GstObject *parent, GstPadMode mode, gboolean active)
{
    gboolean res = FALSE;

    switch (mode) {
        case GST_PAD_MODE_PUSH:
            res = progress_buffer_activatepush_src(pad, parent, active);
            break;
        case GST_PAD_MODE_PULL:
            res = progress_buffer_activatepull_src(pad, parent, active);
            break;
        default:
            /* unknown scheduling mode */
            res = FALSE;
            break;
    }

    return res;
}

/**
 * progress_buffer_create_sourcepad()
 *
 */
static void progress_buffer_create_sourcepad(ProgressBuffer *element)
{
    element->srcpad = gst_pad_new_from_template (gst_element_class_get_pad_template (GST_ELEMENT_GET_CLASS(element), "src"), "src");

    gst_pad_set_activatemode_function  (element->srcpad, GST_DEBUG_FUNCPTR(progress_buffer_activatemode));
    gst_pad_set_event_function         (element->srcpad, GST_DEBUG_FUNCPTR(progress_buffer_src_event));
    gst_pad_set_getrange_function      (element->srcpad, GST_DEBUG_FUNCPTR(progress_buffer_getrange));
    GST_PAD_UNSET_FLUSHING(element->srcpad);

    // Add pad
    gst_element_add_pad (GST_ELEMENT (element), element->srcpad);

    // Activate pad
    gst_pad_set_active(element->srcpad, TRUE);

    // Send "no-more-pads"
    gst_element_no_more_pads(GST_ELEMENT (element));
}

/***********************************************************************************
 * Internal functions
 ***********************************************************************************/
static void progress_buffer_flush_data(ProgressBuffer *element)
{
    element->last_update = 0.0;
    element->bandwidth = 0.0;
    element->subtotal = 0;
    element->pending_src_event = NULL;
    gst_segment_init (&element->sink_segment, GST_FORMAT_BYTES);

#if ENABLE_PULL_MODE
    element->range_start = NO_RANGE_REQUEST;
    element->range_stop = NO_RANGE_REQUEST;
#endif
}

static void progress_buffer_set_pending_event(ProgressBuffer *element, GstEvent* new_event)
{
    if (element->pending_src_event)
        gst_event_unref(element->pending_src_event); // INLINE - gst_event_unref()
    element->pending_src_event = new_event;
}

static GstEventType progress_buffer_get_pending_event_type(ProgressBuffer *element)
{
    if (element->pending_src_event)
        return GST_EVENT_TYPE(element->pending_src_event);
    else
        return GST_EVENT_UNKNOWN;
}

/**
 * send_position_message
 * Sends application message on the BUS with the following parameters:
 *  - structure name is the constant defined as PB_MESSAGE_BUFFERING
 *  - "start" as gint64 is the start position of the current buffer
 *  - "position" as gint64 is current position up to which data has been read from the source
 *  - "stop" as gint64 is the duration of the current segment, usually equals to the whole duration
 *
 * gboolean "mandatory" flag desribes whether the message must be sent anyways.
 * If it's TRUE message is aways sent, otherwise if it's FALSE the function tries to
 * avoid sending messages every time - it sends messages every percent of the whole size.
 */
static gboolean send_position_message(ProgressBuffer *element, gboolean mandatory)
{
    gdouble percent = (double)element->sink_segment.position/element->sink_segment.stop * 100;
    mandatory |= (percent - element->last_update) > element->threshold; // Prevent sending update messages to often

    if (mandatory)
    {
        GstStructure *s = gst_structure_new(PB_MESSAGE_BUFFERING,
                                            "start", G_TYPE_INT64, element->sink_segment.start,
                                            "position", G_TYPE_INT64, element->sink_segment.position,
                                            "stop", G_TYPE_INT64, element->sink_segment.stop,
                                            "eos", G_TYPE_BOOLEAN, element->eos_status.eos,
                                            NULL);
        GstMessage *msg = gst_message_new_application(GST_OBJECT(element), s);

        gst_element_post_message(GST_ELEMENT(element), msg);
        element->last_update = percent;
    }
    return mandatory;
}

/**
 * progress_buffer_enqueue_item()
 *
 * Add an item in the queue. Must be called in the locked context.  Item may be event or data.
 */
static GstFlowReturn progress_buffer_enqueue_item(ProgressBuffer *element, GstMiniObject *item)
{
    gboolean signal = FALSE;

    if (GST_IS_BUFFER (item))
    {
        gdouble elapsed;
        // update sink segment position
        element->sink_segment.position = GST_BUFFER_OFFSET(GST_BUFFER(item)) + gst_buffer_get_size (GST_BUFFER(item));

        if(element->sink_segment.stop < element->sink_segment.position) // This must never happen.
            return  GST_FLOW_ERROR;

        cache_write_buffer(element->cache, GST_BUFFER(item));

        elapsed = g_timer_elapsed(element->bandwidth_timer, NULL);
        element->subtotal += gst_buffer_get_size (GST_BUFFER(item));

        if (elapsed > 1.0)
        {
            element->bandwidth = element->subtotal/elapsed;
            element->subtotal = 0;
            g_timer_start(element->bandwidth_timer);
        }

        // send buffer progress position up (used to track buffer fill, etc.)
        signal = send_position_message(element, signal);
    }
    else if (GST_IS_EVENT (item))
    {
        GstEvent *event = GST_EVENT_CAST (item);

        switch (GST_EVENT_TYPE (event))
        {
            case GST_EVENT_EOS:
                element->eos_status.eos = TRUE;
                if (element->sink_segment.position < element->sink_segment.stop)
                    element->sink_segment.stop = element->sink_segment.position;

                // Do not clear pending EOS event if set. If progress buffer
                // set pending EOS event we need to deliver it, otherwise
                // downstream will wait for data forever.
                if (progress_buffer_get_pending_event_type(element) != GST_EVENT_EOS)
                    progress_buffer_set_pending_event(element, NULL);

                signal = send_position_message(element, TRUE);
                gst_event_unref(event); // INLINE - gst_event_unref()
                break;

            case GST_EVENT_SEGMENT:
            {
                GstSegment segment;

                element->unexpected = FALSE;

                gst_event_copy_segment (event, &segment);

                if (segment.format != GST_FORMAT_BYTES)
                {
                    gst_element_message_full(GST_ELEMENT(element), GST_MESSAGE_ERROR, GST_STREAM_ERROR, GST_STREAM_ERROR_FORMAT,
                                             g_strdup("GST_FORMAT_BYTES buffers expected."), NULL,
                                             ("progressbuffer.c"), ("progress_buffer_enqueue_item"), 0);
                    gst_event_unref(event); // INLINE - gst_event_unref()
                    return GST_FLOW_ERROR;
                 }

                if (segment.stop - segment.start <= 0)
                {
                    gst_element_message_full(GST_ELEMENT(element), GST_MESSAGE_ERROR, GST_STREAM_ERROR, GST_STREAM_ERROR_WRONG_TYPE,
                                             g_strdup("Only limited content is supported by progressbuffer."), NULL,
                                             ("progressbuffer.c"), ("progress_buffer_enqueue_item"), 0);
                    gst_event_unref(event); // INLINE - gst_event_unref()
                    return GST_FLOW_ERROR;
                }

                if ((segment.flags & GST_SEGMENT_FLAG_UPDATE) == GST_SEGMENT_FLAG_UPDATE) // Updating segments create new cache.
                {
                    if (element->cache)
                        destroy_cache(element->cache);

                    element->cache = create_cache();
                    if (!element->cache)
                    {
                        gst_element_message_full(GST_ELEMENT(element), GST_MESSAGE_ERROR, GST_RESOURCE_ERROR, GST_RESOURCE_ERROR_OPEN_READ_WRITE,
                                                 g_strdup("Couldn't create backing cache"), NULL,
                                                 ("progressbuffer.c"), ("progress_buffer_enqueue_item"), 0);
                        gst_event_unref(event); // INLINE - gst_event_unref()
                        return GST_FLOW_ERROR;
                    }
                }
                else
                {
                    cache_set_write_position(element->cache, 0);
                    cache_set_read_position(element->cache, 0);
                    element->cache_read_offset = segment.start;
                }

                gst_segment_copy_into (&segment, &element->sink_segment);
                progress_buffer_set_pending_event(element, event);
                element->instant_seek = TRUE;

                signal = send_position_message(element, TRUE);
                break;
            }

            default:
                gst_event_unref(event); // INLINE - gst_event_unref()
                break;
        }
    }

    if (signal)
        g_cond_broadcast(&element->add_cond);

    return GST_FLOW_OK;
}

/***********************************************************************************
 * Seek implementation
 ***********************************************************************************/
// skia-fx: read exactly n cached bytes at offset into out. Caller holds
// element->lock. FALSE if the bytes aren't cached yet.
static gboolean pb_cache_read_exact(ProgressBuffer *element, gint64 offset, guint n, guint8 *out)
{
    if (element->cache == NULL)
        return FALSE;
    // offset is stream-absolute; the cache window starts at cache_read_offset.
    // A negative cache position means the head isn't in this window (the
    // source has range-seeked past it) — fail so the parse retries later.
    gint64 cachePos = offset - element->cache_read_offset;
    if (cachePos < 0)
        return FALSE;
    GstBuffer *b = NULL;
    if (cache_read_buffer_from_position(element->cache, cachePos, n, &b) != GST_FLOW_OK || b == NULL)
        return FALSE;
    gsize got = gst_buffer_extract(b, 0, out, n);
    gst_buffer_unref(b); // INLINE - gst_buffer_unref()
    return got == n;
}

static inline guint32 pb_rd_be32(const guint8 *p)
{ return ((guint32)p[0] << 24) | ((guint32)p[1] << 16) | ((guint32)p[2] << 8) | (guint32)p[3]; }

static inline guint64 pb_rd_be64(const guint8 *p)
{ return ((guint64)pb_rd_be32(p) << 32) | (guint64)pb_rd_be32(p + 4); }

// skia-fx: parse the fragmented-mp4 sidx from the cached head into a
// time/byte table. Caller holds element->lock. Lazy + one-shot
// (sidx_parsed latches). Returns sidx_valid.
static gboolean progress_buffer_parse_sidx(ProgressBuffer *element)
{
    if (element->sidx_parsed)
        return element->sidx_valid;
    element->sidx_parsed = TRUE; // attempt once (retried only if data was short — see below)

    // Walk top-level boxes from 0 looking for "sidx".
    gint64 off = 0, sidxOff = -1;
    guint64 sidxSize = 0;
    guint  sidxHdr = 8;
    for (int i = 0; i < 64; i++)
    {
        guint8 hdr[16];
        if (!pb_cache_read_exact(element, off, 8, hdr))
        { element->sidx_parsed = FALSE; return FALSE; } // head not downloaded yet — retry later
        guint64 size = pb_rd_be32(hdr);
        guint hdrLen = 8;
        if (size == 1)
        {
            if (!pb_cache_read_exact(element, off + 8, 8, hdr + 8))
            { element->sidx_parsed = FALSE; return FALSE; }
            size = pb_rd_be64(hdr + 8);
            hdrLen = 16;
        }
        if (size < hdrLen) break; // malformed
        if (memcmp(hdr + 4, "sidx", 4) == 0)
        { sidxOff = off; sidxSize = size; sidxHdr = hdrLen; break; }
        off += (gint64) size;
    }
    if (sidxOff < 0) return FALSE; // not a fragmented mp4 with a sidx

    // Parse the sidx fullbox.
    gint64 p = sidxOff + sidxHdr;
    guint8 vf[4];
    if (!pb_cache_read_exact(element, p, 4, vf)) { element->sidx_parsed = FALSE; return FALSE; }
    guint version = vf[0];
    p += 4;
    p += 4; // reference_ID
    guint8 ts[4];
    if (!pb_cache_read_exact(element, p, 4, ts)) { element->sidx_parsed = FALSE; return FALSE; }
    guint32 timescale = pb_rd_be32(ts);
    p += 4;
    guint64 first_offset;
    if (version == 0)
    {
        guint8 fo[8];
        if (!pb_cache_read_exact(element, p, 8, fo)) { element->sidx_parsed = FALSE; return FALSE; }
        first_offset = pb_rd_be32(fo + 4); // [0..3]=earliest_pt, [4..7]=first_offset
        p += 8;
    }
    else
    {
        guint8 fo[16];
        if (!pb_cache_read_exact(element, p, 16, fo)) { element->sidx_parsed = FALSE; return FALSE; }
        first_offset = pb_rd_be64(fo + 8); // [0..7]=earliest_pt, [8..15]=first_offset
        p += 16;
    }
    guint8 rc[4];
    if (!pb_cache_read_exact(element, p, 4, rc)) { element->sidx_parsed = FALSE; return FALSE; }
    guint count = ((guint) rc[2] << 8) | (guint) rc[3]; // reserved(2)+reference_count(2)
    p += 4;
    if (timescale == 0 || count == 0 || count > 200000) return FALSE;

    guint8 *refs = (guint8 *) g_try_malloc((gsize) count * 12);
    if (refs == NULL) return FALSE;
    if (!pb_cache_read_exact(element, p, count * 12, refs))
    { g_free(refs); element->sidx_parsed = FALSE; return FALSE; } // refs not fully downloaded — retry later

    element->sidx_time = (gint64 *) g_try_malloc((gsize)(count + 1) * sizeof(gint64));
    element->sidx_byte = (gint64 *) g_try_malloc((gsize)(count + 1) * sizeof(gint64));
    if (element->sidx_time == NULL || element->sidx_byte == NULL)
    { g_free(refs); g_free(element->sidx_time); g_free(element->sidx_byte);
      element->sidx_time = element->sidx_byte = NULL; return FALSE; }

    gint64 curTime = 0;
    gint64 curByte = (gint64)(sidxOff + (gint64) sidxSize) + (gint64) first_offset; // first moof
    for (guint i = 0; i < count; i++)
    {
        element->sidx_time[i] = curTime;
        element->sidx_byte[i] = curByte;
        guint32 refTypeSize = pb_rd_be32(refs + i * 12);
        guint32 refSize = refTypeSize & 0x7fffffffu;       // referenced_size
        guint32 dur     = pb_rd_be32(refs + i * 12 + 4);   // subsegment_duration (ticks)
        curByte += (gint64) refSize;
        curTime += (gint64) dur;
    }
    element->sidx_time[count] = curTime; // end sentinel
    element->sidx_byte[count] = curByte;
    g_free(refs);

    element->sidx_timescale = timescale;
    element->sidx_count = count;
    element->sidx_valid = TRUE;
    if (pb_verbose())
        g_print("[pb-sidx] %s parsed %u fragments, timescale=%u, firstMoof=%lld, totalDur=%.1fs\n",
                GST_ELEMENT_NAME(element), count, timescale,
                (long long) element->sidx_byte[0], (double) curTime / (double) timescale);
    return TRUE;
}

// skia-fx: parse the sidx if not yet done, saving/restoring the shared cache
// read position (the parse reads the cached head, which moves it, and the
// streaming loop reads sequentially from there). Caller holds element->lock.
static gboolean progress_buffer_ensure_sidx(ProgressBuffer *element)
{
    if (!element->sidx_parsed && element->cache != NULL)
    {
        gint64 saved = cache_get_read_position(element->cache);
        progress_buffer_parse_sidx(element);
        cache_set_read_position(element->cache, saved);
    }
    return element->sidx_valid;
}

// skia-fx: map a TIME (ns) to the start byte of the fragment containing it,
// using the parsed sidx. Caller holds element->lock. -1 if unavailable.
static gint64 progress_buffer_sidx_byte_for_time(ProgressBuffer *element, gint64 timeNs)
{
    if (!progress_buffer_ensure_sidx(element))
        return -1;
    // target ticks = timeNs * timescale / 1e9
    gint64 targetTicks = (gint64) gst_util_uint64_scale(
        (guint64)(timeNs < 0 ? 0 : timeNs), (guint64) element->sidx_timescale, GST_SECOND);
    // find last fragment whose start <= targetTicks
    guint lo = 0, hi = element->sidx_count;
    while (lo + 1 < hi)
    {
        guint mid = (lo + hi) / 2;
        if (element->sidx_time[mid] <= targetTicks) lo = mid; else hi = mid;
    }
    return element->sidx_byte[lo];
}

// skia-fx: map a byte offset to the TIME (ns) of the fragment containing it
// (inverse of the above). Caller holds element->lock. -1 if unavailable.
static gint64 progress_buffer_sidx_time_for_byte(ProgressBuffer *element, gint64 byteOff)
{
    if (!progress_buffer_ensure_sidx(element))
        return -1;
    // find last fragment whose start byte <= byteOff
    guint lo = 0, hi = element->sidx_count;
    while (lo + 1 < hi)
    {
        guint mid = (lo + hi) / 2;
        if (element->sidx_byte[mid] <= byteOff) lo = mid; else hi = mid;
    }
    return (gint64) gst_util_uint64_scale(
        (guint64) element->sidx_time[lo], GST_SECOND, (guint64) element->sidx_timescale);
}

// skia-fx: total media duration (ns) from the sidx, or -1. Caller holds lock.
static gint64 progress_buffer_sidx_total_duration(ProgressBuffer *element)
{
    if (!progress_buffer_ensure_sidx(element))
        return -1;
    return (gint64) gst_util_uint64_scale(
        (guint64) element->sidx_time[element->sidx_count], GST_SECOND,
        (guint64) element->sidx_timescale);
}

// skia-fx: rewrite a BYTES segment event into a TIME segment for a fragmented
// mp4 (sidx present). qtdemux only drives fragmented playback correctly when
// its upstream is in TIME format — then it treats each seek as "re-download
// the fragment" (forwarded upstream, where we sidx-map it to a byte) and
// derives frame times from the moof tfdt. Fed a BYTES segment it instead
// looks the offset up in its (incomplete) fragment sample table and freezes.
// Returns a new TIME segment event (caller owns it) or NULL to keep the
// original. Caller holds element->lock.
static GstEvent *progress_buffer_maybe_time_segment(ProgressBuffer *element, GstEvent *event)
{
    if (GST_EVENT_TYPE(event) != GST_EVENT_SEGMENT)
        return NULL;

    const GstSegment *inSeg = NULL;
    gst_event_parse_segment(event, &inSeg);
    if (inSeg == NULL || inSeg->format != GST_FORMAT_BYTES)
        return NULL;

    gint64 fragTime = progress_buffer_sidx_time_for_byte(element, (gint64) inSeg->start);
    if (fragTime < 0)
        return NULL; // not a fragmented mp4 (or sidx not yet available)

    // Display origin: the exact seek target if this segment follows a seek
    // (so the sink clips the fragment's pre-target frames and lines up with
    // the sample-accurate audio), otherwise the fragment start (linear /
    // initial segment). The fragment's keyframe (at fragTime <= reqTime) is
    // still fed to qtdemux for decode; it just isn't displayed.
    gint64 originTime = fragTime;
    if (element->req_seek_time_ns >= 0 && element->req_seek_time_ns >= fragTime)
        originTime = element->req_seek_time_ns;
    element->req_seek_time_ns = -1; // one-shot, consumed by this segment

    GstSegment seg;
    gst_segment_init(&seg, GST_FORMAT_TIME);
    seg.rate = inSeg->rate;
    seg.applied_rate = inSeg->applied_rate;
    seg.start = (guint64) originTime;
    seg.time = (guint64) originTime;
    seg.position = (guint64) originTime;
    // skia-fx: do NOT bound the segment by the sidx total duration. A sidx can
    // under-report (chained/partial sidx, or a parse that only saw the first
    // index), and a too-short stop makes qtdemux EOS the video early — the
    // position then jumps to that bogus end and the progress bar shoots to the
    // end ("seek goes crazy"). Leave it unbounded like the audio chain; the
    // real EOS comes from the byte stream ending.
    seg.stop = GST_CLOCK_TIME_NONE;
    seg.duration = GST_CLOCK_TIME_NONE;

    GstEvent *timeEvent = gst_event_new_segment(&seg);
    gst_event_set_seqnum(timeEvent, gst_event_get_seqnum(event));

    if (pb_verbose())
        g_print("[pb-sidx-seg] %s BYTES start=%lld -> TIME frag=%.3fs origin=%.3fs stop=unbounded\n",
                GST_ELEMENT_NAME(element), (long long) inSeg->start,
                fragTime / 1e9, originTime / 1e9);

    return timeEvent;
}

static gboolean progress_buffer_perform_push_seek(ProgressBuffer *element, GstPad *pad, GstEvent *event)
{
    GstFormat    format;
    gdouble      rate;
    GstSeekFlags flags;
    GstSeekType  start_type, stop_type;
    gint64       position;
    GstSegment   segment;
    guint32      seqnum;
    gboolean     est_forced = FALSE; // sidx-mapped TIME seek: force a real range request

    gst_event_parse_seek(event, &rate, &format, &flags, &start_type, &position, &stop_type, NULL);
    seqnum = gst_event_get_seqnum(event);

    if (pb_verbose())
        g_print("[pb-pushseek] %s incoming fmt=%d pos=%lld flags=0x%x seqnum=%u\n",
                GST_ELEMENT_NAME(element), (int) format, (long long) position,
                (unsigned) flags, (unsigned) seqnum);

    // skia-fx: qtdemux can't byte-seek a fragmented mp4 in push mode, so it
    // forwards the seek to us in TIME. Map it to the exact fragment (moof)
    // byte via the parsed sidx — fragment-aligned, so qtdemux resyncs
    // cleanly (an unaligned/estimated byte corrupts). If we have no sidx
    // (not fragmented mp4, or head not downloaded) the TIME seek is
    // rejected below, unchanged.
    if (format == GST_FORMAT_TIME && start_type == GST_SEEK_TYPE_SET)
    {
        g_mutex_lock(&element->lock);
        gint64 fragByte = progress_buffer_sidx_byte_for_time(element, position);
        if (fragByte >= 0)
            // Remember the exact requested time so the TIME segment we emit
            // after this seek snaps the sink's display origin to it (not to
            // the earlier fragment boundary) — keeps video synced to audio
            // on both forward and backward seeks.
            element->req_seek_time_ns = position;
        g_mutex_unlock(&element->lock);
        if (fragByte >= 0)
        {
            if (pb_verbose())
                g_print("[pb-sidx-seek] %s TIME %lld ns -> fragment BYTE %lld\n",
                        GST_ELEMENT_NAME(element), (long long) position, (long long) fragByte);
            position = fragByte;
            format = GST_FORMAT_BYTES;
            est_forced = TRUE; // target fragment isn't in cache — fetch it
        }
    }

    if (format != GST_FORMAT_BYTES || start_type != GST_SEEK_TYPE_SET)
        return FALSE;

    if (stop_type != GST_SEEK_TYPE_NONE)
    {
        gst_element_message_full(GST_ELEMENT(element),
            GST_MESSAGE_WARNING,
            GST_CORE_ERROR,
            GST_CORE_ERROR_SEEK, g_strdup("stop_type != GST_SEEK_TYPE_NONE. Seeking to stop is not supported."), NULL,
            ("progressbuffer.c"), ("progress_buffer_perform_push_seek"), 0);
        return FALSE;
    }

    if (flags & GST_SEEK_FLAG_FLUSH)
    {
        GstEvent *e = gst_event_new_flush_start();
        gst_event_set_seqnum(e, seqnum);
        gst_pad_push_event(pad, e);
    }

    // Signal the task to stop if it's waiting.
    g_mutex_lock(&element->lock);
    element->srcresult = GST_FLOW_FLUSHING;
    g_cond_broadcast(&element->add_cond);
    g_mutex_unlock(&element->lock);

    GST_PAD_STREAM_LOCK(pad); // Wait for task to stop

    g_mutex_lock(&element->lock);
    element->srcresult = GST_FLOW_OK;

#ifdef ENABLE_SOURCE_SEEKING
    // skia-fx: a sidx-mapped fragment byte is (almost always) outside the
    // cached window, so an "instant" seek would read stale/short cache and
    // hand qtdemux a truncated fragment (-> MEDIA_CORRUPTED). Force the
    // real upstream range-request path for it.
    element->instant_seek = (!est_forced &&
                             position >= element->sink_segment.start &&
                             (position - (gint64)element->sink_segment.position) <= element->bandwidth * element->wait_tolerance);

    if (element->instant_seek)
    {
        cache_set_read_position(element->cache, position - element->cache_read_offset);
        gst_segment_init(&segment, GST_FORMAT_BYTES);
        segment.rate = rate;
        segment.start = position;
        segment.stop = element->sink_segment.stop;
        segment.position = position;
        progress_buffer_set_pending_event(element, gst_event_new_segment(&segment));
    }
    else
    {
        // Clear any pending events, since we doing seek.
        reset_eos(element, TRUE);
    }
#else
    cache_set_read_position(element->cache, position - element->cache_read_offset);
    gst_segment_init(&segment, GST_FORMAT_BYTES);
    segment.rate = rate;
    segment.start = position;
    segment.stop = element->sink_segment.stop;
    segment.position = position;
    progress_buffer_set_pending_event(element, gst_event_new_segment(&segment));
#endif

    g_mutex_unlock(&element->lock);

#ifdef ENABLE_SOURCE_SEEKING
    if (!element->instant_seek)
    {
        element->is_source_seeking = TRUE;
        GstEvent *e = gst_event_new_seek(rate, GST_FORMAT_BYTES, flags, GST_SEEK_TYPE_SET, position, GST_SEEK_TYPE_NONE, 0);
        gst_event_set_seqnum(e, seqnum);
        if (!gst_pad_push_event(element->sinkpad, e))
        {
            element->instant_seek = TRUE;
            cache_set_read_position(element->cache, position - element->cache_read_offset);
            gst_segment_init(&segment, GST_FORMAT_BYTES);
            segment.rate = rate;
            segment.start = position;
            segment.stop = element->sink_segment.stop;
            segment.position = position;
            progress_buffer_set_pending_event(element, gst_event_new_segment(&segment));
        }
        element->is_source_seeking = FALSE;
    }
#endif

    if (flags & GST_SEEK_FLAG_FLUSH) {
        GstEvent *e = gst_event_new_flush_stop(TRUE);
        gst_event_set_seqnum(e, seqnum);
        gst_pad_push_event(pad, e);
    }

    gst_pad_start_task(element->srcpad, progress_buffer_loop, element, NULL);
    GST_PAD_STREAM_UNLOCK(pad);

// INLINE - gst_event_unref()
    gst_event_unref(event);
    return TRUE;
}

/***********************************************************************************
 * chain, loop, sink_event and src_event, buffer_alloc
 ***********************************************************************************/
/**
 * progress_buffer_chain()
 *
 * Primary function for push-mode.  Receives data from progressbuffer's sink pad.
 */
static GstFlowReturn progress_buffer_chain(GstPad *pad, GstObject *parent, GstBuffer *data)
{
    ProgressBuffer *element = PROGRESS_BUFFER(parent);
    GstFlowReturn  result = GST_FLOW_OK;

    //Try to enqueue the data
    g_mutex_lock(&element->lock);

    if (element->eos_status.eos || element->unexpected)
        result = GST_FLOW_EOS;
    else
        result = progress_buffer_enqueue_item(element, GST_MINI_OBJECT_CAST(data));

    /* skia-fx diagnostic (OPENJFX_MEDIA_VERBOSE): a non-OK chain return
     * pauses the upstream javasource task permanently — log why. */
    if (result != GST_FLOW_OK)
    {
        if (pb_verbose())
            g_print("[pb-chain] %s returning flow=%d (eos=%d unexpected=%d pos=%"
                    G_GINT64_FORMAT " stop=%" G_GINT64_FORMAT ")\n",
                    GST_ELEMENT_NAME(element), (int)result,
                    (int)element->eos_status.eos, (int)element->unexpected,
                    element->sink_segment.position, element->sink_segment.stop);
    }

    g_mutex_unlock(&element->lock);

// INLINE - gst_buffer_unref()
    gst_buffer_unref(data);

    // Here we can maintain some prebuffering strategy.
    if (result != GST_FLOW_ERROR && !element->srcpad)
        progress_buffer_create_sourcepad(element);

    return result;
}

/**
 * send_underrun_message
 *
 * Sends UNDERRUN message to the bus.
 */
static void send_underrun_message(ProgressBuffer* element)
{
    GstStructure *s = gst_structure_new_empty(PB_MESSAGE_UNDERRUN);
    GstMessage *msg = gst_message_new_application(GST_OBJECT(element), s);

    /* skia-fx: diagnostic (OPENJFX_MEDIA_VERBOSE only, capped) — shows
     * WHICH progressbuffer starves and when. */
    {
        static guint64 _urCount = 0;
        if (pb_verbose() &&
            (_urCount < 20 || (_urCount % 200) == 0))
        {
            g_print("[pb-underrun] %s #%llu\n",
                    GST_ELEMENT_NAME(element),
                    (unsigned long long)_urCount);
        }
        _urCount++;
    }

    gst_element_post_message(GST_ELEMENT(element), msg);
}

/**
 * progress_buffer_loop()
 *
 * Primary function for push-mode.  Pulls data from progressbuffer's cache queue.
 */
static void progress_buffer_loop(void *data)
{
    ProgressBuffer *element = PROGRESS_BUFFER(data);
    GstFlowReturn  result;
    gboolean       skip = FALSE;

    g_mutex_lock(&element->lock);

next_item:
    while (element->srcresult == GST_FLOW_OK &&
           element->pending_src_event == NULL &&
           (!cache_has_enough_data(element->cache) || !element->instant_seek))
    {
        if (element->instant_seek)
            send_underrun_message(element);
        g_cond_wait(&element->add_cond, &element->lock);
    }

    result = element->srcresult;

    if (result == GST_FLOW_OK)
    {
        if (element->pending_src_event)
        {
            GstEvent *event = gst_event_ref(element->pending_src_event);
            progress_buffer_set_pending_event(element, NULL);

            switch(GST_EVENT_TYPE (event))
            {
                case GST_EVENT_EOS:
                    result = GST_FLOW_EOS;
                    break;
                case GST_EVENT_SEGMENT:
                {
                    skip = FALSE;
                    // skia-fx: for a fragmented mp4 (sidx present) hand qtdemux
                    // a TIME segment instead of BYTES, so it drives fragmented
                    // playback/seek via the moof tfdt instead of its incomplete
                    // sample table (which freezes the video). No-op for every
                    // other stream (sidx absent -> NULL). The cached head is
                    // present by now (the loop only runs past the buffering
                    // threshold), so the lazy sidx parse can succeed here.
                    GstEvent *timeSeg = progress_buffer_maybe_time_segment(element, event);
                    if (timeSeg != NULL)
                    {
                        gst_event_unref(event); // INLINE - gst_event_unref()
                        event = timeSeg;
                    }
                    break;
                }
                default:
                    if (skip)
                    {
                        gst_event_unref (event); // INLINE - gst_event_unref()
                        goto next_item;
                    }
                    break;
            }
            element->srcresult = result;
            g_mutex_unlock(&element->lock);
            gst_pad_push_event (element->srcpad, event);
        }
        else // create a buffer
        {
            GstBuffer *buffer = NULL;
            guint64 read_position = cache_read_buffer(element->cache, &buffer);
            read_position += element->cache_read_offset;
            GST_BUFFER_OFFSET(buffer) = read_position - gst_buffer_get_size(buffer);

            if (read_position == element->sink_segment.stop)
                progress_buffer_set_pending_event(element, gst_event_new_eos());

            if (skip)
            {
                gst_buffer_unref(buffer); // INLINE - gst_buffer_unref()
                goto next_item;
            }
            else
            {
                g_mutex_unlock(&element->lock);

                // Send the data to the progressbuffer source pad
                result = gst_pad_push(element->srcpad, buffer);

                // Switch to skip mode. No we can only pass EOS and NEWSEGMENT events.
                if (result == GST_FLOW_EOS)
                {
                    g_mutex_lock(&element->lock);
                    skip = TRUE;
                    goto next_item;
                }

                g_mutex_lock(&element->lock);
                element->srcresult = result;
                g_mutex_unlock(&element->lock);
            }
        }
    }
    else
    {
        if (skip) // Run out of items in skip mode. Expecting only EOS or NEWSEGMENT in _chain()
        {
            element->unexpected = TRUE;
            result = GST_FLOW_OK;
        }
        g_mutex_unlock(&element->lock);
    }

    if (result != GST_FLOW_OK)
        gst_pad_pause_task(element->srcpad);
}

/**
 * progress_buffer_sink_event()
 *
 * Receives event from the sink pad (currently, data from javasource).  When an event comes in,
 * we get the data from the pad by getting at the ProgressBuffer* object associated with the pad.
 */
static gboolean progress_buffer_sink_event(GstPad *pad, GstObject *parent, GstEvent *event)
{
    ProgressBuffer *element = PROGRESS_BUFFER(parent);
    gboolean       result = TRUE;

    // Ignore GST_EVENT_FLUSH_START and GST_EVENT_FLUSH_STOP if source seeking
    if (element->is_source_seeking)
    {
        if (GST_EVENT_TYPE(event) == GST_EVENT_FLUSH_START || GST_EVENT_TYPE(event) == GST_EVENT_FLUSH_STOP)
        {
            // INLINE - gst_event_unref()
            gst_event_unref(event);
            return TRUE;
        }
    }

    if (GST_EVENT_IS_SERIALIZED (event) && GST_EVENT_TYPE(event) != GST_EVENT_FLUSH_STOP)
    {
        g_mutex_lock(&element->lock);

        if (element->eos_status.eos)
        {
// INLINE - gst_event_unref()
            gst_event_unref(event);
            result = FALSE;
        }
        else
            progress_buffer_enqueue_item(element, GST_MINI_OBJECT_CAST(event));

        g_mutex_unlock(&element->lock);
    }
    else
        result = gst_pad_push_event(element->srcpad, event);

    return result;

}

static gboolean progress_buffer_src_event(GstPad *pad, GstObject *parent, GstEvent *event)
{
    ProgressBuffer *element = PROGRESS_BUFFER(parent);
    if (GST_PAD_MODE(pad) == GST_PAD_MODE_PUSH)
    {
        switch (GST_EVENT_TYPE (event))
        {
            case GST_EVENT_SEEK:
                return progress_buffer_perform_push_seek(element, pad, event);
            default:
                break;
        }
    }
    else if (GST_PAD_MODE(pad) == GST_PAD_MODE_PULL) // Isolate the source element from all upcoming events
    {
// INLINE - gst_event_unref()
        gst_event_unref(event);
        return TRUE;
    }

    return gst_pad_event_default(pad, parent, event);
}

/***********************************************************************************
 * Pull-range function
 ***********************************************************************************/
#if ENABLE_PULL_MODE
#define VALID_RANGE(value)  (value != NO_RANGE_REQUEST)

static inline gboolean pending_range_start(ProgressBuffer *element)
{
    return (VALID_RANGE(element->range_start) &&
            element->sink_segment.start > element->range_start);
}

static inline gboolean pending_range_stop(ProgressBuffer *element)
{
    return (VALID_RANGE(element->range_stop) &&
            element->sink_segment.position < element->range_stop);
}

static gpointer progress_buffer_range_monitor(ProgressBuffer *element)
{
    g_mutex_lock(&element->lock);

check_loop:
    while (element->srcresult == GST_FLOW_OK && !pending_eos(element) &&
           (pending_range_start(element) || pending_range_stop(element) ||
           !VALID_RANGE(element->range_start) && !VALID_RANGE(element->range_stop)))
    {
        g_cond_wait(&element->add_cond, &element->lock);
    }

    if (element->srcresult == GST_FLOW_OK && (VALID_RANGE(element->range_start) || VALID_RANGE(element->range_stop)))
    {
        element->range_stop = element->range_start = NO_RANGE_REQUEST;
        g_mutex_unlock(&element->lock);
        gst_pad_push_event(element->srcpad, gst_event_new_custom(FX_EVENT_RANGE_READY, NULL));
        g_mutex_lock(&element->lock);
        goto check_loop;
    }
    else
        g_mutex_unlock(&element->lock);

    return NULL;
}
#endif

static GstFlowReturn progress_buffer_getrange(GstPad *pad, GstObject *parent, guint64 start_position,
                                              guint size, GstBuffer **buffer)
{
#if ENABLE_PULL_MODE
    ProgressBuffer *element = PROGRESS_BUFFER(parent);
    GstFlowReturn  result = GST_FLOW_OK;
    guint64        end_position = start_position + size;

    // skia-fx: this function used to return GST_FLOW_FLUSHING on a cache
    // miss (range not downloaded yet). A pull-mode demuxer (matroskademux
    // drives the dual-source companion in pull mode) treats FLUSHING as
    // "shut down": it silently pauses its streaming task — permanently,
    // because the FX_EVENT_RANGE_READY custom event progressbuffer emits
    // later means nothing to it. The companion chain then went dead a
    // couple of seconds in (whatever was pulled before the first miss),
    // the audio sink starved, the audio master clock froze, and the whole
    // pipeline wedged at 00:00 ("plays two notes then freezes"; a manual
    // seek sometimes revived it only because the demux's seek handler
    // restarts its own task).
    //
    // The fix: behave like a blocking pull source (queue2's download
    // mode) — wait until the requested range is cached, an EOS clamps
    // the stream short, or the element shuts down. Wake-ups come from
    // enqueue_item (download progress + EOS) and the shutdown /
    // deactivate paths, which all signal add_cond.
    /* skia-fx diagnostic (OPENJFX_MEDIA_VERBOSE, capped): trace pulls. */
    static guint64 _grCount = 0;
    gboolean _grLog = FALSE;
    {
        if (pb_verbose() &&
            (_grCount < 40 || (_grCount % 500) == 0))
        {
            _grLog = TRUE;
            g_print("[pb-getrange] %s #%llu start=%llu size=%u\n",
                    GST_ELEMENT_NAME(element), (unsigned long long)_grCount,
                    (unsigned long long)start_position, size);
        }
        _grCount++;
    }

    g_mutex_lock(&element->lock); // Use one lock for push and pull modes

    // Per-pull state: fire UNDERRUN once per pull (not once per wakeup —
    // enqueue_item signals for every arriving buffer), and latch the
    // upstream byte-seek per segment generation so wakeups caused by
    // still-in-flight pre-seek data don't re-push the seek (connection
    // churn storm on slow links).
    gboolean underrun_sent = FALSE;
    gboolean seek_pushed = FALSE;
    gint64   seek_pushed_seg_start = 0;

    for (;;)
    {
        gboolean needs_seeking = FALSE;

        if (element->srcresult != GST_FLOW_OK)
        {
            // Shutting down / deactivating.
            result = element->srcresult;
            break;
        }

        if (element->sink_segment.stop < (gint64)end_position)
        {
            // Requested range crosses the end of stream. Note: the stop
            // is clamped down to the real size when upstream EOSes (the
            // initial value comes from the size hint, which can be too
            // large — e.g. the dual-source companion inherits the
            // primary's size). Serve a short read for a partial overlap,
            // EOS when nothing overlaps.
            if ((gint64)start_position >= element->sink_segment.stop)
            {
                result = GST_FLOW_EOS;
                break;
            }
            end_position = element->sink_segment.stop;
            size = (guint)(end_position - start_position);
            continue; // re-evaluate with the clamped size
        }

        if (element->sink_segment.start <= (gint64)start_position &&
            element->sink_segment.position >= (gint64)end_position)
        {
            result = cache_read_buffer_from_position(element->cache, start_position, size, buffer);
            break;
        }

        // Range not cached yet — request it and wait.
#if ENABLE_SOURCE_SEEKING
        needs_seeking = element->sink_segment.start > (gint64)start_position;
        if (needs_seeking)
        {
            element->range_start = start_position;
            reset_eos(element, TRUE);
        }
#endif
        if (element->sink_segment.position < (gint64)end_position)
        {
            element->range_stop = end_position + (gint64)(element->bandwidth * element->prebuffer_time);

            if (element->sink_segment.stop < element->range_stop)
                element->range_stop = element->sink_segment.stop;

#if ENABLE_SOURCE_SEEKING
            needs_seeking = needs_seeking || (element->bandwidth > 0 &&
                end_position - element->sink_segment.position > element->bandwidth * element->wait_tolerance);
#endif
        }

        if (!underrun_sent)
        {
            send_underrun_message(element);
            underrun_sent = TRUE;
        }

        if (needs_seeking &&
            (!seek_pushed || element->sink_segment.start != seek_pushed_seg_start))
        {
            // Ask the source to jump to the requested offset (range
            // request on the connection) rather than waiting for the
            // sequential download to get there. Push outside the lock.
            // The latch above keeps this to one seek per segment
            // generation: wakeups from in-flight pre-seek buffers see
            // the same sink_segment.start and just wait; only a real
            // SEGMENT update (the source processed our seek) re-arms it.
            seek_pushed = TRUE;
            seek_pushed_seg_start = element->sink_segment.start;
            g_mutex_unlock(&element->lock);
            gboolean seeked = gst_pad_push_event(element->sinkpad,
                gst_event_new_seek(element->sink_segment.rate, GST_FORMAT_BYTES, GST_SEEK_FLAG_NONE,
                    GST_SEEK_TYPE_SET, start_position, GST_SEEK_TYPE_NONE, 0));
            g_mutex_lock(&element->lock);
            if (!seeked && element->sink_segment.start > (gint64)start_position)
            {
                // Source can't seek backwards — the bytes will never
                // arrive. Fail the pull rather than wait forever.
                result = GST_FLOW_ERROR;
                break;
            }
            // Wait for the source to process the seek (its SEGMENT
            // update signals add_cond) — otherwise this loop would
            // re-evaluate stale segment state and re-push the seek.
            if (element->srcresult == GST_FLOW_OK)
                g_cond_wait(&element->add_cond, &element->lock);
        }
        else
        {
            g_cond_wait(&element->add_cond, &element->lock);
        }
    }

    g_mutex_unlock(&element->lock);

    if (_grLog || result != GST_FLOW_OK)
    {
        if (pb_verbose())
            g_print("[pb-getrange] %s result=%d (start=%llu)\n",
                    GST_ELEMENT_NAME(element), (int)result,
                    (unsigned long long)start_position);
    }

    return result;
#else
    ProgressBuffer *element = PROGRESS_BUFFER(GST_PAD_PARENT(pad));
    return gst_pad_pull_range(element->sinkpad, parent, start_position, size, buffer);
#endif
}

static gboolean progress_buffer_checkgetrange(GstPad *pad)
{
    ProgressBuffer *element = PROGRESS_BUFFER(GST_PAD_PARENT(pad));
#if ENABLE_PULL_MODE
    gboolean    result = FALSE;
    GstStructure *s = gst_structure_new(GETRANGE_QUERY_NAME, NULL, NULL);
    GstQuery *query = gst_query_new_custom(GST_QUERY_CUSTOM, s);
    if (gst_pad_peer_query(pad, query))
        result = gst_structure_get_boolean(s, GETRANGE_QUERY_SUPPORTS_FIELDNANE, &result) && result;
// INLINE - gst_query_unref()
    gst_query_unref(query);
    return result;
#else
    return gst_pad_check_pull_range(element->sinkpad);
#endif
}

/***********************************************************************************
 * State change handler
 ***********************************************************************************/
static GstStateChangeReturn progress_buffer_change_state (GstElement *e,
                                                          GstStateChange transition)
{
    ProgressBuffer *element = PROGRESS_BUFFER(e);
    GstStateChangeReturn ret = GST_ELEMENT_CLASS (parent_class)->change_state (e, transition);

    if (ret == GST_STATE_CHANGE_FAILURE)
        return ret;

    switch (transition)
    {
        case GST_STATE_CHANGE_PAUSED_TO_READY:
            g_mutex_lock(&element->lock);
            element->srcresult = GST_FLOW_FLUSHING;
            progress_buffer_flush_data(element);
            g_cond_broadcast(&element->add_cond); // Signal the task to stop if it's waiting.
            g_mutex_unlock(&element->lock);
            break;

        default:
            break;
    }
    return ret;
}

/***********************************************************************************
 * Plugin registration infrastructure
 ***********************************************************************************/

gboolean progress_buffer_plugin_init (GstPlugin *plugin)
{
    GST_DEBUG_CATEGORY_INIT (progress_buffer_debug, PROGRESS_BUFFER_PLUGIN_NAME,
            0, ELEMENT_DESCRIPTION);

    return gst_element_register (plugin, PROGRESS_BUFFER_PLUGIN_NAME,
                                 GST_RANK_NONE,
                                 PROGRESS_BUFFER_TYPE);
}
