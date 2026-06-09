/*
 * matroska-compat.c
 *
 * Plug a few helper functions that upstream OpenJFX stripped from its
 * gstreamer-lite build but that matroska-demux + the re-enabled Opus
 * codec-utils helpers depend on. Compiled only when the matroska
 * demuxer is being built (OPENJFX_HAVE_MATROSKA), so non-matroska
 * builds aren't carrying any of this.
 *
 * skia-fx-only addition; not in upstream OpenJFX.
 */

#ifdef HAVE_CONFIG_H
#include "config.h"
#endif

#include <gst/gst.h>
#include <gst/tag/tag.h>
#include <string.h>

/*
 * gst_tag_list_to_vorbiscomment_buffer — minimal vorbis-comment block.
 *
 * Real upstream lives in gst-libs/gst/tag/gstvorbistag.c and produces a
 * fully-encoded vorbis-comment buffer from a GstTagList. Pulling that
 * file in transitively wants iconv + the rest of the tag library, which
 * the lite build deliberately omits.
 *
 * Our only call site is `gst_codec_utils_opus_create_caps_from_header`
 * (matroska-demux → opus caps), which invokes it with an empty taglist
 * solely to construct the OpusTags streamheader. An OpusTags header
 * with zero vendor + zero comments is a valid vorbis-comment payload:
 *
 *   [id_data][LE u32 vendor_len = 0][LE u32 comment_count = 0]
 *
 * That's 8 (or however many id bytes) + 4 + 4 = 16 bytes for "OpusTags".
 * Real tag content is never used by the pipeline downstream — opus
 * decode only cares about the OpusHead block, not OpusTags.
 */
GstBuffer *
gst_tag_list_to_vorbiscomment_buffer (const GstTagList * list,
                                       const guint8 * id_data,
                                       const guint id_data_length,
                                       const gchar * vendor_string)
{
    (void) list;
    (void) vendor_string;

    guint vendor_len = 0;
    gsize sz = (gsize) id_data_length + 4u + vendor_len + 4u;
    GstBuffer *buf = gst_buffer_new_allocate (NULL, sz, NULL);
    if (!buf) return NULL;

    GstMapInfo info;
    if (!gst_buffer_map (buf, &info, GST_MAP_WRITE)) {
        gst_buffer_unref (buf);
        return NULL;
    }
    guint8 *p = info.data;
    if (id_data && id_data_length) {
        memcpy (p, id_data, id_data_length);
        p += id_data_length;
    }
    /* vendor_len = 0, comment_count = 0 — both little-endian uint32. */
    memset (p, 0, 8);
    gst_buffer_unmap (buf, &info);
    return buf;
}
