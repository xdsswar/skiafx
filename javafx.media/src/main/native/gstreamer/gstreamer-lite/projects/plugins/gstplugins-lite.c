/*
 * Copyright (c) 2011, 2015, Oracle and/or its affiliates. All rights reserved.
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

#ifdef HAVE_CONFIG_H
#  include "config.h"
#endif

#include <gst/gst.h>

#include "gstplugins-lite.h"

gboolean lite_plugins_init (GstPlugin * plugin)
{
  if (!plugin_init_elements(plugin) ||
      !plugin_init_typefind(plugin) ||
      !plugin_init_audioconvert(plugin) ||
      !plugin_init_equalizer(plugin) ||
      !plugin_init_spectrum(plugin) ||
      !plugin_init_wavparse(plugin) ||
      !plugin_init_aiff(plugin) ||
      !plugin_init_app(plugin) ||
      !plugin_init_audioparsers(plugin) ||
      !plugin_init_qtdemux(plugin))
    return FALSE;

#ifdef OPENJFX_HAVE_MATROSKA
  /* skia-fx addition: matroskademux for .webm / .mkv containers.
   * Sources are fetched by skiafx.matroska-conventions and compiled
   * into this object library; the matroska-lite-plugin.c wrapper
   * exposes plugin_init_matroska for lite-style static registration.
   * Failing this leaves all other plugins working — caller decides
   * whether to require matroska support. */
  {
    extern gboolean plugin_init_matroska (GstPlugin * plugin);
    if (!plugin_init_matroska (plugin)) {
      return FALSE;
    }
  }

  /* skia-fx addition: scaletempo for time-stretched (pitch-preserved)
   * audio at MediaPlayer.setRate(rate != 1.0). Fetched from the same
   * gst-plugins-good tarball (audiofx/) by skiafx.matroska-conventions;
   * registered via the scaletempo-lite-plugin.c wrapper. Shares the
   * OPENJFX_HAVE_MATROSKA guard because the sources arrive together. */
  {
    extern gboolean plugin_init_scaletempo (GstPlugin * plugin);
    if (!plugin_init_scaletempo (plugin)) {
      return FALSE;
    }
  }

  /* skia-fx addition: avi + flv demuxers and the flac parser — the
   * format-expansion set. All fetched from the same pinned
   * gst-plugins-good tarball; each registered by its own thin
   * *-lite-plugin.c wrapper. Decoders are the existing ones
   * (ffmpegwrapper/dshowwrapper picked per caps). */
  {
    extern gboolean plugin_init_avi (GstPlugin * plugin);
    extern gboolean plugin_init_flv (GstPlugin * plugin);
    extern gboolean plugin_init_flacparse (GstPlugin * plugin);
    const gchar* dbg = g_getenv ("SKIA_MEDIA_DEBUG");
    if (!plugin_init_flacparse (plugin)) return FALSE;
    if (dbg) g_print ("[lite] flacparse registered\n");
    if (!plugin_init_avi (plugin)) return FALSE;
    if (dbg) g_print ("[lite] avi registered\n");
    if (!plugin_init_flv (plugin)) return FALSE;
    if (dbg) g_print ("[lite] flv registered\n");
  }
#endif

#ifdef WIN32
  if (!plugin_init_directsound(plugin))
    return FALSE;
#endif

#ifdef OSX
  if (!plugin_init_audiofx(plugin) ||
      !plugin_init_osxaudio(plugin))
    return FALSE;
#endif

#ifdef LINUX
  if (!plugin_init_audiofx(plugin) ||
      !plugin_init_alsa(plugin) ||
      !plugin_init_volume(plugin))
    return FALSE;
#endif

  return TRUE;
}
