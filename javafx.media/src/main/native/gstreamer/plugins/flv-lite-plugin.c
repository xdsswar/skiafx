/*
 * flv-lite-plugin.c
 *
 * gstreamer-lite registration entry for the FLV demuxer. The upstream
 * module's own `gstflvplugin.c` defines a `plugin_init` with the
 * standard GST_PLUGIN_DEFINE macro, which conflicts with
 * gstreamer-lite's static plugin registry — this wrapper exposes the
 * lite-style `plugin_init_flv` entry instead.
 *
 * Note: gstflvdemux.c #includes its private gstindex.c/gstmemindex.c
 * copies directly, so those files must NOT be compiled separately.
 *
 * skia-fx-specific addition: not in upstream OpenJFX. Sources are
 * fetched from the pinned gst-plugins-good tarball by
 * skiafx.matroska-conventions (same fetch as matroska/scaletempo).
 */

#ifdef HAVE_CONFIG_H
#include "config.h"
#endif

#include <gst/gst.h>

/* gstflvelements.h is in the auto-fetched flv dir which the CMake
 * include path adds to gstreamer-lite-plugins. */
#include "gstflvelements.h"

gboolean
plugin_init_flv (GstPlugin * plugin)
{
    /* flvdemux's REGISTER_DEFINE_WITH_CODE runs flv_element_init
     * itself; flvmux is mux-side, not needed for playback. */
    return GST_ELEMENT_REGISTER (flvdemux, plugin);
}
