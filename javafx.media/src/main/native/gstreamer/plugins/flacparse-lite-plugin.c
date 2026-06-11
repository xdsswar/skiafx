/*
 * flacparse-lite-plugin.c
 *
 * gstreamer-lite registration entry for the FLAC parser, plus the two
 * libgsttag functions it needs that the lite tag library was pruned
 * of. flacparse frames raw .flac streams (and emits the stream's
 * vorbis-comment tags); the actual decode happens in ffmpegwrapper.
 *
 * skia-fx-specific addition: not in upstream OpenJFX. gstflacparse.c
 * is fetched from the pinned gst-plugins-good tarball by
 * skiafx.matroska-conventions (same fetch as matroska/scaletempo).
 */

#ifdef HAVE_CONFIG_H
#include "config.h"
#endif

#include <string.h>
#include <gst/gst.h>
#include <gst/tag/tag.h>


/* NOT the fetched gstaudioparserselements.h: the in-tree (older)
 * audioparsers dir is earlier on the include path and ships a
 * same-named header without flacparse. The declare macro avoids the
 * header entirely. */
GST_ELEMENT_REGISTER_DECLARE (flacparse);

gboolean
plugin_init_flacparse (GstPlugin * plugin)
{
    return GST_ELEMENT_REGISTER (flacparse, plugin);
}

/* ------------------------------------------------------------------
 * libgsttag compat — the lite tag library ships only the id3 side;
 * the vorbis-comment reader below is a faithful implementation of the
 * upstream wire format (RFC-style: vendor + count + "KEY=value"
 * entries, all lengths little-endian).
 * ------------------------------------------------------------------ */

static void
flacparse_compat_add_tag (GstTagList * list, const gchar * key, guint key_len,
                          const gchar * value, guint value_len)
{
    /* Map the common vorbis-comment keys onto GStreamer tags. Unknown
     * keys are skipped — same observable behaviour as upstream for
     * tags GStreamer has no mapping for. */
    const gchar* gst_tag = NULL;
    gboolean is_uint = FALSE;
    if (g_ascii_strncasecmp (key, "TITLE", key_len) == 0 && key_len == 5)
        gst_tag = GST_TAG_TITLE;
    else if (g_ascii_strncasecmp (key, "ARTIST", key_len) == 0 && key_len == 6)
        gst_tag = GST_TAG_ARTIST;
    else if (g_ascii_strncasecmp (key, "ALBUM", key_len) == 0 && key_len == 5)
        gst_tag = GST_TAG_ALBUM;
    else if (g_ascii_strncasecmp (key, "GENRE", key_len) == 0 && key_len == 5)
        gst_tag = GST_TAG_GENRE;
    else if (g_ascii_strncasecmp (key, "COMMENT", key_len) == 0 && key_len == 7)
        gst_tag = GST_TAG_COMMENT;
    else if (g_ascii_strncasecmp (key, "COMPOSER", key_len) == 0 && key_len == 8)
        gst_tag = GST_TAG_COMPOSER;
    else if (g_ascii_strncasecmp (key, "ALBUMARTIST", key_len) == 0 && key_len == 11)
        gst_tag = GST_TAG_ALBUM_ARTIST;
    else if (g_ascii_strncasecmp (key, "TRACKNUMBER", key_len) == 0 && key_len == 11)
    {
        gst_tag = GST_TAG_TRACK_NUMBER;
        is_uint = TRUE;
    }
    if (gst_tag == NULL)
        return;

    gchar* utf8 = g_strndup (value, value_len);
    if (utf8 == NULL)
        return;
    if (!g_utf8_validate (utf8, -1, NULL))
    {
        g_free (utf8);
        return;
    }
    if (is_uint)
    {
        guint64 n = g_ascii_strtoull (utf8, NULL, 10);
        if (n > 0 && n <= G_MAXUINT)
            gst_tag_list_add (list, GST_TAG_MERGE_APPEND, gst_tag, (guint) n, NULL);
    }
    else
    {
        gst_tag_list_add (list, GST_TAG_MERGE_APPEND, gst_tag, utf8, NULL);
    }
    g_free (utf8);
}

GstTagList *
gst_tag_list_from_vorbiscomment (const guint8 * data, gsize size,
                                 const guint8 * id_data,
                                 const guint id_data_length,
                                 gchar ** vendor_string)
{
    if (data == NULL || size < id_data_length + 8)
        return NULL;
    if (id_data_length > 0 && id_data != NULL &&
        memcmp (data, id_data, id_data_length) != 0)
        return NULL;

    const guint8* p   = data + id_data_length;
    const guint8* end = data + size;

    /* vendor string */
    if (p + 4 > end) return NULL;
    guint32 vendor_len = GST_READ_UINT32_LE (p);
    p += 4;
    if (vendor_len > (gsize)(end - p)) return NULL;
    if (vendor_string != NULL)
        *vendor_string = g_strndup ((const gchar *) p, vendor_len);
    p += vendor_len;

    /* comment count + entries */
    if (p + 4 > end) return NULL;
    guint32 count = GST_READ_UINT32_LE (p);
    p += 4;

    GstTagList* list = gst_tag_list_new_empty ();
    for (guint32 i = 0; i < count && p + 4 <= end; i++)
    {
        guint32 len = GST_READ_UINT32_LE (p);
        p += 4;
        if (len > (gsize)(end - p))
            break;
        const gchar* entry = (const gchar *) p;
        const gchar* eq = memchr (entry, '=', len);
        if (eq != NULL && eq != entry)
        {
            guint key_len = (guint)(eq - entry);
            flacparse_compat_add_tag (list, entry, key_len,
                                      eq + 1, len - key_len - 1);
        }
        p += len;
    }
    return list;
}

/* gst_tag_list_add_id3_image is NOT duplicated here — the lite tag
 * library's gstid3tag.c already provides it. */
