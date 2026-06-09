/*
 * matroska-lite-plugin.c
 *
 * gstreamer-lite registration entry for the matroska demuxer. The
 * upstream matroska module's own `matroska.c` defines a `plugin_init`
 * with the standard GST_PLUGIN_DEFINE macro, which conflicts with
 * gstreamer-lite's static plugin registry (everything routes through
 * `lite_plugins_init` in projects/plugins/gstplugins-lite.c). We
 * deliberately don't compile that file — this wrapper exposes the
 * lite-style `plugin_init_matroska` entry that calls the upstream
 * element register declarations.
 *
 * skia-fx-specific addition: not in upstream OpenJFX. Pulls in the
 * matroska sources fetched by skiafx.matroska-conventions, giving the
 * media engine .webm and .mkv container support.
 */

#ifdef HAVE_CONFIG_H
#include "config.h"
#endif

#include <gst/gst.h>

/* gstmatroskaelements.h is in the auto-fetched matroska dir which the
 * CMake include path adds to gstreamer-lite-plugins. */
#include "gstmatroskaelements.h"

gboolean
plugin_init_matroska (GstPlugin * plugin)
{
    /* matroska_element_init runs gst_pb_utils_init + debug-category
     * setup once. Required before registering the demux element. */
    matroska_element_init (plugin);

    /* GST_ELEMENT_REGISTER macro from gstmatroskaelements.h — sets up
     * matroskademux with PRIMARY rank. matroskaparse / matroskamux /
     * webmmux are mux-side or parser-only, not needed for playback. */
    return GST_ELEMENT_REGISTER (matroskademux, plugin);
}
