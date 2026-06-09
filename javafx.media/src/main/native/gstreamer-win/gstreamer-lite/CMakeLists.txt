# ---------------------------------------------------------------------------
# gstreamer-lite.dll — JFX-bundled GStreamer (Windows).
#
# Translated from upstream Makefiles:
#   gstreamer/projects/win/gstreamer-lite/Makefile
#       Makefile.gstreamer  (145 .c sources — gstreamer core + base + plugins-base libs)
#       Makefile.gstplugins (29 .c + 1 .cpp — selected plugins: app, audioconvert,
#                            typefind, audioparsers, directsound, equalizer,
#                            isomp4, spectrum, wavparse, core elements, aiff)
#
# Shape: two OBJECT libs (gstreamer-core + gstreamer-plugins) mirror the
# upstream Makefile split, then a single SHARED library links them
# together with the .def file controlling exports.
# ---------------------------------------------------------------------------

set(NATIVE_ROOT   ${CMAKE_CURRENT_SOURCE_DIR}/../..)
set(GST_BASE      ${NATIVE_ROOT}/gstreamer/gstreamer-lite)
set(GLIB_BASE     ${NATIVE_ROOT}/gstreamer/3rd_party/glib)
set(GST_DEF       ${NATIVE_ROOT}/gstreamer/projects/win/gstreamer-lite.def)

# Shared MSVC compile flags for both OBJECT libs.
set(GST_COMMON_OPTIONS
    /nologo /W3 /WX- /EHsc /GS /fp:precise /Gm-
    /Zc:wchar_t /Zc:forScope /Gd /analyze- /errorReport:queue
    /wd4005 /wd4018 /wd4101 /wd4146 /wd4244 /wd4267 /wd4273 /wd4996
)
set(GST_RELEASE_OPTIONS /O1 /Oy /MD /Gy /GF)

# Common defines shared by gstreamer and gstplugins OBJECT libs.
set(GST_COMMON_DEFINES
    WIN32 _WINDOWS _USRDLL _WINDLL _MBCS _WIN64
    _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR
    _USE_MATH_DEFINES
    HAVE_CONFIG_H
    GSTREAMER_LITE
    GST_REMOVE_DEPRECATED
    GST_DISABLE_GST_DEBUG
    GST_DISABLE_LOADSAVE
)
set(GST_RELEASE_DEFINES NDEBUG G_DISABLE_DEPRECATED G_DISABLE_ASSERT)

# Includes shared by both OBJECT libs.
set(GST_COMMON_INCLUDES
    ${GST_BASE}/projects/build/win32/common
    ${GST_BASE}/projects/plugins
    ${GST_BASE}/gstreamer
    ${GST_BASE}/gstreamer/libs
    ${GST_BASE}/gst-plugins-base
    ${GST_BASE}/gst-plugins-base/gst-libs
    ${GST_BASE}/gst-plugins-base/win32/common
    ${NATIVE_ROOT}/gstreamer/plugins
    ${GLIB_BASE}
    ${GLIB_BASE}/glib
    ${GLIB_BASE}/gmodule
    ${GLIB_BASE}/build/win32/vs100
)

# ---------------------------------------------------------------------------
# OBJECT lib: gstreamer-core (from Makefile.gstreamer)
# ---------------------------------------------------------------------------
add_library(gstreamer-lite-core OBJECT
    # gstreamer/gst (core)
    ${GST_BASE}/gstreamer/gst/gst.c
    ${GST_BASE}/gstreamer/gst/gstallocator.c
    ${GST_BASE}/gstreamer/gst/gstatomicqueue.c
    ${GST_BASE}/gstreamer/gst/gstbin.c
    ${GST_BASE}/gstreamer/gst/gstbuffer.c
    ${GST_BASE}/gstreamer/gst/gstbufferlist.c
    ${GST_BASE}/gstreamer/gst/gstbufferpool.c
    ${GST_BASE}/gstreamer/gst/gstbus.c
    ${GST_BASE}/gstreamer/gst/gstcaps.c
    ${GST_BASE}/gstreamer/gst/gstcapsfeatures.c
    ${GST_BASE}/gstreamer/gst/gstchildproxy.c
    ${GST_BASE}/gstreamer/gst/gstclock.c
    ${GST_BASE}/gstreamer/gst/gstcontext.c
    ${GST_BASE}/gstreamer/gst/gstcontrolbinding.c
    ${GST_BASE}/gstreamer/gst/gstcontrolsource.c
    ${GST_BASE}/gstreamer/gst/gstdatetime.c
    ${GST_BASE}/gstreamer/gst/gstdebugutils.c
    ${GST_BASE}/gstreamer/gst/gstdynamictypefactory.c
    ${GST_BASE}/gstreamer/gst/gstelement.c
    ${GST_BASE}/gstreamer/gst/gstelementfactory.c
    ${GST_BASE}/gstreamer/gst/gstenumtypes.c
    ${GST_BASE}/gstreamer/gst/gsterror.c
    ${GST_BASE}/gstreamer/gst/gstevent.c
    ${GST_BASE}/gstreamer/gst/gstformat.c
    ${GST_BASE}/gstreamer/gst/gstghostpad.c
    ${GST_BASE}/gstreamer/gst/gstidstr.c
    ${GST_BASE}/gstreamer/gst/gstinfo.c
    ${GST_BASE}/gstreamer/gst/gstiterator.c
    ${GST_BASE}/gstreamer/gst/gstmemory.c
    ${GST_BASE}/gstreamer/gst/gstmessage.c
    ${GST_BASE}/gstreamer/gst/gstmeta.c
    ${GST_BASE}/gstreamer/gst/gstminiobject.c
    ${GST_BASE}/gstreamer/gst/gstobject.c
    ${GST_BASE}/gstreamer/gst/gstpad.c
    ${GST_BASE}/gstreamer/gst/gstpadtemplate.c
    ${GST_BASE}/gstreamer/gst/gstparamspecs.c
    ${GST_BASE}/gstreamer/gst/gstparse.c
    ${GST_BASE}/gstreamer/gst/gstpipeline.c
    ${GST_BASE}/gstreamer/gst/gstplugin.c
    ${GST_BASE}/gstreamer/gst/gstpluginfeature.c
    ${GST_BASE}/gstreamer/gst/gstpoll.c
    ${GST_BASE}/gstreamer/gst/gstprotection.c
    ${GST_BASE}/gstreamer/gst/gstquark.c
    ${GST_BASE}/gstreamer/gst/gstquery.c
    ${GST_BASE}/gstreamer/gst/gstregistry.c
    ${GST_BASE}/gstreamer/gst/gstregistrybinary.c
    ${GST_BASE}/gstreamer/gst/gstregistrychunks.c
    ${GST_BASE}/gstreamer/gst/gstsample.c
    ${GST_BASE}/gstreamer/gst/gstsegment.c
    ${GST_BASE}/gstreamer/gst/gststructure.c
    ${GST_BASE}/gstreamer/gst/gstsystemclock.c
    ${GST_BASE}/gstreamer/gst/gststreamcollection.c
    ${GST_BASE}/gstreamer/gst/gststreams.c
    ${GST_BASE}/gstreamer/gst/gsttaglist.c
    ${GST_BASE}/gstreamer/gst/gsttagsetter.c
    ${GST_BASE}/gstreamer/gst/gsttask.c
    ${GST_BASE}/gstreamer/gst/gsttaskpool.c
    ${GST_BASE}/gstreamer/gst/gsttoc.c
    ${GST_BASE}/gstreamer/gst/gsttocsetter.c
    ${GST_BASE}/gstreamer/gst/gsttracer.c
    ${GST_BASE}/gstreamer/gst/gsttracerfactory.c
    ${GST_BASE}/gstreamer/gst/gsttracerrecord.c
    ${GST_BASE}/gstreamer/gst/gsttracerutils.c
    ${GST_BASE}/gstreamer/gst/gsttypefind.c
    ${GST_BASE}/gstreamer/gst/gsttypefindfactory.c
    ${GST_BASE}/gstreamer/gst/gsturi.c
    ${GST_BASE}/gstreamer/gst/gstutils.c
    ${GST_BASE}/gstreamer/gst/gstvalue.c
    ${GST_BASE}/gstreamer/gst/gstvecdeque.c
    # gstreamer/libs/gst/base
    ${GST_BASE}/gstreamer/libs/gst/base/gstadapter.c
    ${GST_BASE}/gstreamer/libs/gst/base/gstbaseparse.c
    ${GST_BASE}/gstreamer/libs/gst/base/gstbasesink.c
    ${GST_BASE}/gstreamer/libs/gst/base/gstbasesrc.c
    ${GST_BASE}/gstreamer/libs/gst/base/gstbasetransform.c
    ${GST_BASE}/gstreamer/libs/gst/base/gstbitreader.c
    ${GST_BASE}/gstreamer/libs/gst/base/gstbytereader.c
    ${GST_BASE}/gstreamer/libs/gst/base/gstbytewriter.c
    ${GST_BASE}/gstreamer/libs/gst/base/gstcollectpads.c
    ${GST_BASE}/gstreamer/libs/gst/base/gstdataqueue.c
    ${GST_BASE}/gstreamer/libs/gst/base/gstflowcombiner.c
    ${GST_BASE}/gstreamer/libs/gst/base/gstpushsrc.c
    ${GST_BASE}/gstreamer/libs/gst/base/gstqueuearray.c
    ${GST_BASE}/gstreamer/libs/gst/base/gsttypefindhelper.c
    # gst-plugins-base/gst-libs/gst/app
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/app/gstapp-marshal.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/app/gstappsink.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/app/gstapputils.c
    # gst-plugins-base/gst-libs/gst/audio
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/audio.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/audio-channels.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/audio-channel-mixer.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/audio-buffer.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/audio-converter.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/audio-resampler.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/audio-enumtypes.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/audio-quantize.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/audio-format.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/audio-info.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/gstaudiobasesink.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/gstaudiobasesrc.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/gstaudioclock.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/gstaudiodecoder.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/gstaudioencoder.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/gstaudiofilter.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/gstaudioiec61937.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/gstaudiometa.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/gstaudiopack-dist.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/gstaudioringbuffer.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/gstaudiosink.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/gstaudiosrc.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/streamvolume.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio/gstaudioutilsprivate.c
    # gst-plugins-base/gst-libs/gst/fft
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/fft/gstfft.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/fft/gstfftf32.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/fft/kiss_fft_f32.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/fft/kiss_fftr_f32.c
    # gst-plugins-base/gst-libs/gst/pbutils
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/pbutils/codec-utils.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/pbutils/descriptions.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/pbutils/gstpluginsbaseversion.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/pbutils/missing-plugins.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/pbutils/pbutils.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/pbutils/pbutils-enumtypes.c
    # gst-plugins-base/gst-libs/gst/riff
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/riff/riff-media.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/riff/riff-read.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/riff/riff.c
    # gst-plugins-base/gst-libs/gst/tag
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/tag/gstid3tag.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/tag/gsttagdemux.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/tag/id3v2.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/tag/id3v2frames.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/tag/lang.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/tag/tags.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/tag/tag-enumtypes.c
    # gst-plugins-base/gst-libs/gst/video
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/video/gstvideometa.c
    # skia-fx: alpha meta used by VP8/VP9 with alpha (webm). Pulled in
    # by matroska-demux which reads the GstVideoCodecAlphaMeta type.
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/video/gstvideocodecalphameta.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/video/gstvideopool.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/video/gstvideotimecode.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/video/video.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/video/video-chroma.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/video/video-color.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/video/video-converter.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/video/video-format.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/video/video-frame.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/video/video-hdr.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/video/video-enumtypes.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/video/video-info.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/video/video-multiview.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/video/video-orc-dist.c
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/video/video-tile.c
)
target_compile_definitions(gstreamer-lite-core PRIVATE
    ${GST_COMMON_DEFINES}
    LIBGSTREAMER_EXPORTS
    # skia-fx: gstconfig.h gates the MSVC dllexport of GStreamer's value-type
    # data globals (_gst_value_array_type, _gst_value_list_type, …) on the
    # GST_EXPORTS define — NOT LIBGSTREAMER_EXPORTS (which is a dead legacy
    # define, unused by any source #ifdef). Without GST_EXPORTS those globals
    # link as __declspec(dllimport) and read back as 0, so GST_TYPE_ARRAY /
    # GST_TYPE_LIST resolve to 0 — the "type id '0' is invalid" GObject
    # CRITICAL, and the crash building Opus streamheader caps. Defining it on
    # the core (which DEFINES those globals) dllexports them correctly; the
    # plugins object lib keeps dllimport and resolves to these exports.
    GST_EXPORTS
    HAVE_WIN32
    LIBDSHOW_EXPORTS
    $<$<CONFIG:Release>:${GST_RELEASE_DEFINES}>
)
target_compile_options(gstreamer-lite-core PRIVATE
    ${GST_COMMON_OPTIONS}
    $<$<CONFIG:Release>:${GST_RELEASE_OPTIONS}>
)
target_include_directories(gstreamer-lite-core PRIVATE
    ${GST_COMMON_INCLUDES}
    # Each source subdir adds its own header path (matches the
    # `INCLUDES = $(addprefix -I,$(DIRLIST))` pattern in
    # upstream Makefile.gstreamer).
    ${GST_BASE}/gstreamer/gst
    ${GST_BASE}/gstreamer/libs/gst/base
    ${GST_BASE}/gstreamer/libs/gst/controller
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/app
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/audio
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/fft
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/pbutils
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/riff
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/tag
    ${GST_BASE}/gst-plugins-base/gst-libs/gst/video
)

# ---------------------------------------------------------------------------
# OBJECT lib: gstreamer-plugins (from Makefile.gstplugins)
# ---------------------------------------------------------------------------
add_library(gstreamer-lite-plugins OBJECT
    ${GST_BASE}/gst-plugins-bad/gst/aiff/aiff.c
    ${GST_BASE}/gst-plugins-bad/gst/aiff/aiffparse.c
    ${GST_BASE}/gst-plugins-bad/gst/aiff/gstaiffelement.c
    ${GST_BASE}/gst-plugins-base/gst/app/gstapp.c
    ${GST_BASE}/gst-plugins-base/gst/app/gstappsink.c
    ${GST_BASE}/gst-plugins-base/gst/audioconvert/gstaudioconvert.c
    ${GST_BASE}/gst-plugins-base/gst/audioconvert/plugin.c
    ${GST_BASE}/gst-plugins-base/gst/typefind/gsttypefindfunctions.c
    ${GST_BASE}/gst-plugins-base/gst/typefind/gsttypefindfunctionsplugin.c
    # skia-fx: aacparse compiled into the lite build on Windows.
    # qtdemux on .m4a/.mp4 emits raw AAC; aacparse re-frames to ADTS
    # so the downstream decoder gets the right caps. Without this,
    # AAC audio in the dual-source media path decodes at the wrong
    # sample rate (manifests as ~2x speed playback).
    ${GST_BASE}/gst-plugins-good/gst/audioparsers/gstaacparse.c
    ${GST_BASE}/gst-plugins-good/gst/audioparsers/gstmpegaudioparse.c
    ${GST_BASE}/gst-plugins-good/gst/audioparsers/parsersplugin.c
    ${GST_BASE}/gst-plugins-good/sys/directsound/gstdirectsoundsink.c
    ${GST_BASE}/gst-plugins-good/sys/directsound/gstdirectsoundplugin.c
    ${GST_BASE}/gst-plugins-good/sys/directsound/gstdirectsoundnotify.cpp
    ${GST_BASE}/gst-plugins-good/gst/equalizer/gstiirequalizer.c
    ${GST_BASE}/gst-plugins-good/gst/equalizer/gstiirequalizernbands.c
    ${GST_BASE}/gst-plugins-good/gst/equalizer/gstiirequalizerplugin.c
    ${GST_BASE}/gst-plugins-good/gst/isomp4/isomp4-plugin.c
    ${GST_BASE}/gst-plugins-good/gst/isomp4/gstisomp4element.c
    ${GST_BASE}/gst-plugins-good/gst/isomp4/qtdemux-webvtt.c
    ${GST_BASE}/gst-plugins-good/gst/isomp4/qtdemux.c
    ${GST_BASE}/gst-plugins-good/gst/isomp4/gstisoff.c
    ${GST_BASE}/gst-plugins-good/gst/isomp4/qtdemux_dump.c
    ${GST_BASE}/gst-plugins-good/gst/isomp4/qtdemux_lang.c
    ${GST_BASE}/gst-plugins-good/gst/isomp4/qtdemux_tags.c
    ${GST_BASE}/gst-plugins-good/gst/isomp4/qtdemux_tree.c
    ${GST_BASE}/gst-plugins-good/gst/isomp4/qtdemux_types.c
    ${GST_BASE}/gst-plugins-good/gst/spectrum/gstspectrum.c
    ${GST_BASE}/gst-plugins-good/gst/wavparse/gstwavparse.c
    ${GST_BASE}/gstreamer/plugins/elements/gstcoreelementsplugin.c
    # skia-fx: filesrc reinstated from upstream gstreamer mainline so
    # the dual-source media path (Media(video, audio) companion
    # file://) can construct a working pipeline. Stock gstreamer-lite
    # strips this file but leaves the registration line in
    # gstcoreelementsplugin.c — without the implementation,
    # gst_element_factory_make("filesrc", ...) returns NULL.
    ${GST_BASE}/gstreamer/plugins/elements/gstfilesrc.c
    ${GST_BASE}/gstreamer/plugins/elements/gstqueue.c
    ${GST_BASE}/gstreamer/plugins/elements/gsttypefindelement.c
    ${GST_BASE}/projects/plugins/gstplugins-lite.c

    # matroska demuxer sources — fetched at build time by
    # skiafx.matroska-conventions and pointed at via
    # OPENJFX_GST_MATROSKA_DIR. The upstream matroska.c is deliberately
    # NOT in this list — its GST_PLUGIN_DEFINE would clash with our
    # static lite registry — we ship our own thin plugin entry that
    # exposes `plugin_init_matroska` (lite-style) instead. Other files
    # in the fetched dir (matroska-mux, webm-mux, ebml-write) are mux
    # paths we don't need.
    $<$<BOOL:${OPENJFX_GST_MATROSKA_DIR}>:${OPENJFX_GST_MATROSKA_DIR}/gstmatroskaelement.c>
    $<$<BOOL:${OPENJFX_GST_MATROSKA_DIR}>:${OPENJFX_GST_MATROSKA_DIR}/matroska-demux.c>
    $<$<BOOL:${OPENJFX_GST_MATROSKA_DIR}>:${OPENJFX_GST_MATROSKA_DIR}/matroska-parse.c>
    $<$<BOOL:${OPENJFX_GST_MATROSKA_DIR}>:${OPENJFX_GST_MATROSKA_DIR}/matroska-ids.c>
    $<$<BOOL:${OPENJFX_GST_MATROSKA_DIR}>:${OPENJFX_GST_MATROSKA_DIR}/matroska-read-common.c>
    $<$<BOOL:${OPENJFX_GST_MATROSKA_DIR}>:${OPENJFX_GST_MATROSKA_DIR}/ebml-read.c>
    $<$<BOOL:${OPENJFX_GST_MATROSKA_DIR}>:${OPENJFX_GST_MATROSKA_DIR}/lzo.c>
    $<$<BOOL:${OPENJFX_GST_MATROSKA_DIR}>:${NATIVE_ROOT}/gstreamer/plugins/matroska-lite-plugin.c>
    $<$<BOOL:${OPENJFX_GST_MATROSKA_DIR}>:${NATIVE_ROOT}/gstreamer/plugins/matroska-compat.c>
)
if(OPENJFX_GST_MATROSKA_DIR)
    target_compile_definitions(gstreamer-lite-plugins PRIVATE OPENJFX_HAVE_MATROSKA)
endif()
target_compile_definitions(gstreamer-lite-plugins PRIVATE
    ${GST_COMMON_DEFINES}
    LIBGSTELEMENTS_EXPORTS
    _WIN32_DCOM
    COBJMACROS
    $<$<CONFIG:Release>:${GST_RELEASE_DEFINES}>
)
target_compile_options(gstreamer-lite-plugins PRIVATE
    ${GST_COMMON_OPTIONS}
    $<$<CONFIG:Release>:${GST_RELEASE_OPTIONS}>
)
target_include_directories(gstreamer-lite-plugins PRIVATE
    ${GST_COMMON_INCLUDES}
    # Per-plugin source dirs (matches upstream Makefile.gstplugins DIRLIST).
    ${GST_BASE}/gst-plugins-bad/gst/aiff
    ${GST_BASE}/gst-plugins-base/gst/app
    ${GST_BASE}/gst-plugins-base/gst/audioconvert
    ${GST_BASE}/gst-plugins-base/gst/typefind
    ${GST_BASE}/gst-plugins-good/gst/audioparsers
    ${GST_BASE}/gst-plugins-good/sys/directsound
    ${GST_BASE}/gst-plugins-good/gst/equalizer
    ${GST_BASE}/gst-plugins-good/gst/isomp4
    ${GST_BASE}/gst-plugins-good/gst/spectrum
    ${GST_BASE}/gst-plugins-good/gst/wavparse
    ${GST_BASE}/gstreamer/plugins/elements
    ${GST_BASE}/gstreamer/plugins/indexers
    ${GST_BASE}/projects/plugins
)
if(OPENJFX_GST_MATROSKA_DIR)
    target_include_directories(gstreamer-lite-plugins PRIVATE
        ${OPENJFX_GST_MATROSKA_DIR}
    )
endif()

# ---------------------------------------------------------------------------
# Final SHARED library: gstreamer-lite.dll
# ---------------------------------------------------------------------------
add_library(gstreamer-lite SHARED
    $<TARGET_OBJECTS:gstreamer-lite-core>
    $<TARGET_OBJECTS:gstreamer-lite-plugins>
)
set_target_properties(gstreamer-lite PROPERTIES
    OUTPUT_NAME gstreamer-lite
    PREFIX ""
)
target_link_options(gstreamer-lite PRIVATE
    /DEF:${GST_DEF}
    /SUBSYSTEM:WINDOWS
    /TLBID:1
    /DYNAMICBASE
    /NXCOMPAT
    /ERRORREPORT:QUEUE
    /MANIFESTUAC:"level='asInvoker' uiAccess='false'"
    $<$<CONFIG:Release>:/OPT:REF /OPT:ICF>
    # skia-fx: RelWithDebInfo emits a linker .map (symbol->RVA, plain text)
    # for crash symbolication without a debugger. Release is unaffected.
    $<$<CONFIG:RelWithDebInfo>:/MAP>
)
target_link_libraries(gstreamer-lite PRIVATE
    glib-lite      # depends on glib-lite.dll built in this same project
    Ws2_32.lib
    kernel32.lib
    user32.lib
    shell32.lib
    advapi32.lib
    ole32.lib
    DSound.lib
)
