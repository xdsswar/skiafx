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

#include <gst/gst.h>

#include <fxplugins_common.h>
#include <javasource.h>
#include <progressbuffer.h>
#include <hlsprogressbuffer.h>

#if defined(WIN32)
gboolean dshowwrapper_init(GstPlugin* dshowwrapper);
gboolean mfwrapper_init(GstPlugin* mfwrapper);
/* Optional: ffmpegwrapper is only compiled when ffmpeg headers are
 * available at build time (gated by OPENJFX_FFMPEG_INCLUDE_DIR in
 * the fxplugins CMakeLists). The macro below is defined by that
 * CMakeLists when it adds the ffmpegwrapper sources, so callers can
 * skip the registration call when the plugin wasn't built. */
#ifdef OPENJFX_HAVE_FFMPEGWRAPPER
gboolean ffmpegwrapper_init(GstPlugin* ffmpegwrapper);
/* Defined in ffmpeg_loader.cpp. Auto-init the runtime ffmpeg loader
 * from the OPENJFX_MEDIA_FFMPEG_DIR environment variable so end-user
 * code never has to call internal API. Declared as int return type
 * here (fxplugins.c is C, not C++) — C++ side returns bool, the
 * implicit conversion across the ABI is well-defined for a 1-byte
 * scalar. Returns non-zero when the loader is now usable. */
int openjfx_ffmpeg_loader_init(const char* user_dir);
#endif
/* Optional: ffmpegdemux (libavformat catch-all demuxer) is compiled
 * together with the wrapper under OPENJFX_FFMPEG_INCLUDE_DIR. It plays
 * any container ffmpeg can open and is created by name from the JFX
 * pipeline factory for content types with no dedicated gst demuxer. */
#ifdef OPENJFX_HAVE_FFMPEGDEMUX
gboolean ffmpegdemux_init(GstPlugin* ffmpegdemux);
#endif
#endif

#ifdef STATIC_BUILD
gboolean fxplugins_init (GstPlugin * plugin)
#else
static gboolean fxplugins_init (GstPlugin * plugin)
#endif
{
    return java_source_plugin_init(plugin) &&
           hls_progress_buffer_plugin_init(plugin) &&

#if defined(WIN32)
           dshowwrapper_init(plugin) &&
           mfwrapper_init(plugin) &&
#  ifdef OPENJFX_HAVE_FFMPEGWRAPPER
           /* Returns TRUE even when ffmpeg DLLs aren't found at
            * runtime — the element registers and reports
            * `is-supported = FALSE` for every codec so the routing
            * code in GstAVPlaybackPipeline falls through to the
            * mfwrapper / dshowwrapper path.
            *
            * Self-init the ffmpeg loader from the standard env var so
            * user applications don't need to call any private Java
            * API. Process-wide env var read; harmless when unset. */
           (openjfx_ffmpeg_loader_init(getenv("OPENJFX_MEDIA_FFMPEG_DIR")),
            ffmpegwrapper_init(plugin)) &&
#  endif
#  ifdef OPENJFX_HAVE_FFMPEGDEMUX
           /* Registers at GST_RANK_NONE — never auto-plugged; only the
            * pipeline factory creates it by name. Returns TRUE even with
            * no ffmpeg at runtime (the element fails the state change with
            * a catchable error if it's ever reached without the loader). */
           ffmpegdemux_init(plugin) &&
#  endif
#endif // WIN32
           progress_buffer_plugin_init(plugin);
}

#if defined(WIN32)
extern __declspec(dllexport) GstPluginDesc gst_plugin_desc =
#else // WIN32
GstPluginDesc gst_plugin_desc =
#endif // WIN32
{
    GST_VERSION_MAJOR,
    GST_VERSION_MINOR,
    "fxplugins",
    "FX Plugins",
    fxplugins_init,
    "1.0",
    "Proprietary",
    "JFXMedia",
    "JFXMedia",
    "http://javafx.com/",
    NULL
};
