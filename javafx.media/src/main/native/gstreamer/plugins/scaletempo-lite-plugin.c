/*
 * scaletempo-lite-plugin.c
 *
 * gstreamer-lite registration entry for the scaletempo element. The
 * upstream audiofx module's own plugin entry isn't compiled (it would
 * conflict with gstreamer-lite's static registry, and it registers a
 * dozen elements we don't ship) — this wrapper exposes the lite-style
 * `plugin_init_scaletempo` entry that registers just scaletempo.
 *
 * scaletempo provides proper time-stretched (pitch-preserved) audio
 * for MediaPlayer.setRate(rate != 1.0). Without it, GstAudioBaseSink
 * only scales ring-buffer WRITE POSITIONS for non-1.0 segment rates —
 * the sample data is never stretched, so 0.5x plays as normal-pitch
 * bursts separated by silence gaps. scaletempo consumes the segment
 * rate (rewriting it into applied-rate downstream) and outputs
 * stretched samples the sink can play at 1.0x.
 *
 * Sources are fetched from the pinned gst-plugins-good tarball by
 * skiafx.matroska-conventions (same mechanism as the matroska
 * demuxer) into <generated>/audiofx/.
 *
 * skia-fx-specific addition: not in upstream OpenJFX.
 */

#ifdef HAVE_CONFIG_H
#include "config.h"
#endif

#include <gst/gst.h>
/* gstscaletempo.h uses GstAudioFormat but doesn't include the audio
 * library header itself (upstream's .c includes it first). */
#include <gst/audio/audio.h>

/* gstscaletempo.h is in the auto-fetched audiofx dir which the CMake
 * include path adds to gstreamer-lite-plugins. */
#include "gstscaletempo.h"

gboolean
plugin_init_scaletempo (GstPlugin * plugin)
{
    return GST_ELEMENT_REGISTER (scaletempo, plugin);
}
