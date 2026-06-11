/*
 * avi-lite-plugin.c
 *
 * gstreamer-lite registration entry for the AVI demuxer. The upstream
 * module's own `gstavi.c` defines a `plugin_init` with the standard
 * GST_PLUGIN_DEFINE macro, which conflicts with gstreamer-lite's
 * static plugin registry (everything routes through
 * `lite_plugins_init` in projects/plugins/gstplugins-lite.c). We
 * deliberately don't compile that file — this wrapper exposes the
 * lite-style `plugin_init_avi` entry instead.
 *
 * skia-fx-specific addition: not in upstream OpenJFX. Sources are
 * fetched from the pinned gst-plugins-good tarball by
 * skiafx.matroska-conventions (same fetch as matroska/scaletempo).
 */

#ifdef HAVE_CONFIG_H
#include "config.h"
#endif

#include <gst/gst.h>

/* gstavielements.h is in the auto-fetched avi dir which the CMake
 * include path adds to gstreamer-lite-plugins. */
#include "gstavielements.h"

gboolean
plugin_init_avi (GstPlugin * plugin)
{
    /* Debug categories + riff init, required before registration. */
    avi_element_init (plugin);

    /* avisubtitle is instantiated by avidemux for AVIs carrying GAB2
     * subtitle streams; register it so those files don't fail on a
     * missing factory. avimux is mux-side, not needed for playback. */
    gboolean ok = GST_ELEMENT_REGISTER (avidemux, plugin);
    ok &= GST_ELEMENT_REGISTER (avisubtitle, plugin);
    return ok;
}
