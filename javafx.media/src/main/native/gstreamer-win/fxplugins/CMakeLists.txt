# ---------------------------------------------------------------------------
# fxplugins.dll — JFX GStreamer plugins (Windows).
#
# Translated from upstream Makefiles:
#   gstreamer/projects/win/fxplugins/Makefile + Makefile.BaseClasses
#
# Sources: dshowwrapper, mfwrapper (Media Foundation), javasource,
# progressbuffer, plus the top-level fxplugins.c registry. Bundled
# DirectShow base classes (BaseClasses/) compile to a static helper
# then link into fxplugins.dll. No .def — exports use __declspec.
# ---------------------------------------------------------------------------

set(NATIVE_ROOT   ${CMAKE_CURRENT_SOURCE_DIR}/../..)
set(PLUGINS_BASE  ${NATIVE_ROOT}/gstreamer/plugins)
set(GST_BASE      ${NATIVE_ROOT}/gstreamer/gstreamer-lite)
set(GLIB_BASE     ${NATIVE_ROOT}/gstreamer/3rd_party/glib)
set(BC_BASE       ${NATIVE_ROOT}/gstreamer/3rd_party/baseclasses)

# Common MSVC flags shared by baseclasses and fxplugins.
set(FXP_COMMON_OPTIONS
    /nologo /W3 /WX- /EHsc /GS /fp:precise
    /Zc:wchar_t /Zc:forScope- /analyze- /errorReport:queue /Gm-
)
set(FXP_RELEASE_OPTIONS /Oy /MD /Gy /GF)

# ---------------------------------------------------------------------------
# STATIC lib: baseclasses (Microsoft DirectShow BaseClasses)
# Self-contained .cpp set, linked into fxplugins below.
# ---------------------------------------------------------------------------
add_library(baseclasses STATIC
    ${BC_BASE}/amextra.cpp
    ${BC_BASE}/amfilter.cpp
    ${BC_BASE}/amvideo.cpp
    ${BC_BASE}/arithutil.cpp
    ${BC_BASE}/combase.cpp
    ${BC_BASE}/cprop.cpp
    ${BC_BASE}/ctlutil.cpp
    ${BC_BASE}/ddmm.cpp
    ${BC_BASE}/dllentry.cpp
    ${BC_BASE}/dllsetup.cpp
    ${BC_BASE}/mtype.cpp
    ${BC_BASE}/outputq.cpp
    ${BC_BASE}/perflog.cpp
    ${BC_BASE}/pstream.cpp
    ${BC_BASE}/pullpin.cpp
    ${BC_BASE}/refclock.cpp
    ${BC_BASE}/renbase.cpp
    ${BC_BASE}/schedule.cpp
    ${BC_BASE}/seekpt.cpp
    ${BC_BASE}/source.cpp
    ${BC_BASE}/strmctl.cpp
    ${BC_BASE}/sysclock.cpp
    ${BC_BASE}/transfrm.cpp
    ${BC_BASE}/transip.cpp
    ${BC_BASE}/videoctl.cpp
    ${BC_BASE}/vtrans.cpp
    ${BC_BASE}/winctrl.cpp
    ${BC_BASE}/winutil.cpp
    ${BC_BASE}/wxdebug.cpp
    ${BC_BASE}/wxlist.cpp
    ${BC_BASE}/wxutil.cpp
)
target_compile_definitions(baseclasses PRIVATE
    WIN32 _LIB _WIN32_DCOM _MBCS _WIN64 NODEFAULTLIB
    _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR
    _HAS_STD_BYTE=0
    $<$<CONFIG:Release>:NDEBUG>
)
target_compile_options(baseclasses PRIVATE
    ${FXP_COMMON_OPTIONS} /Gd /wd4430
    $<$<CONFIG:Release>:/O1 ${FXP_RELEASE_OPTIONS}>
)
target_include_directories(baseclasses PRIVATE ${BC_BASE})

# ---------------------------------------------------------------------------
# SHARED library: fxplugins.dll (direct compile, no intermediate OBJECT lib)
# ---------------------------------------------------------------------------
add_library(fxplugins SHARED
    # javasource — Java-driven push source
    ${PLUGINS_BASE}/javasource/javasource.c
    ${PLUGINS_BASE}/javasource/marshal.c
    # progressbuffer — HTTP/HLS seek buffering
    ${PLUGINS_BASE}/progressbuffer/progressbuffer.c
    ${PLUGINS_BASE}/progressbuffer/hlsprogressbuffer.c
    ${PLUGINS_BASE}/progressbuffer/win32/filecache.c
    # top-level plugin registry
    ${PLUGINS_BASE}/fxplugins.c
    # dshowwrapper — DirectShow → GStreamer bridge
    ${PLUGINS_BASE}/dshowwrapper/Allocator.cpp
    ${PLUGINS_BASE}/dshowwrapper/dshowwrapper.cpp
    ${PLUGINS_BASE}/dshowwrapper/Sink.cpp
    ${PLUGINS_BASE}/dshowwrapper/Src.cpp
    # mfwrapper — Media Foundation → GStreamer bridge
    ${PLUGINS_BASE}/mfwrapper/mfwrapper.cpp
    ${PLUGINS_BASE}/mfwrapper/mfgstbuffer.cpp
    # Phase-3 M3-B zero-copy texture meta (shared with jfxmedia)
    ${PLUGINS_BASE}/openjfx_media_d3d11_meta.cpp
    # Phase-4 ffmpeg runtime-dynamic decoder. Gated on
    # OPENJFX_FFMPEG_INCLUDE_DIR being set — without ffmpeg headers
    # available the loader + plugin TUs can't compile, so we omit
    # them and the rest of fxplugins keeps building. At runtime, an
    # absent ffmpegwrapper means codec routing falls back to
    # mfwrapper / dshowwrapper.
    $<$<BOOL:${OPENJFX_FFMPEG_INCLUDE_DIR}>:${PLUGINS_BASE}/ffmpegwrapper/ffmpeg_loader.cpp>
    $<$<BOOL:${OPENJFX_FFMPEG_INCLUDE_DIR}>:${PLUGINS_BASE}/ffmpegwrapper/ffmpegwrapper.cpp>
    # MediaMixer remux engine (openjfx_ffmpeg_remux) — stream-copy
    # mux of separate audio + video files into MP4 via the loader's
    # optional avformat group. jfxmedia's MediaMixerBridge resolves
    # it cross-DLL.
    $<$<BOOL:${OPENJFX_FFMPEG_INCLUDE_DIR}>:${PLUGINS_BASE}/ffmpegwrapper/ffmpeg_remux.cpp>
    # ffmpegdemux — libavformat-backed catch-all demuxer. Same ffmpeg gate
    # as the wrapper above; plays any container ffmpeg can open when the
    # runtime DLLs are present (routed by GstPipelineFactory for content
    # types with no dedicated gst demuxer).
    $<$<BOOL:${OPENJFX_FFMPEG_INCLUDE_DIR}>:${PLUGINS_BASE}/ffmpegdemux/ffmpeg_caps.cpp>
    $<$<BOOL:${OPENJFX_FFMPEG_INCLUDE_DIR}>:${PLUGINS_BASE}/ffmpegdemux/ffmpegdemux.cpp>
)
set_target_properties(fxplugins PROPERTIES
    OUTPUT_NAME fxplugins
    PREFIX ""
)
target_compile_definitions(fxplugins PRIVATE
    WIN32 _WINDOWS _USRDLL _WINDLL _MBCS _WIN64
    _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR
    # _HAS_STD_BYTE=0 disables std::byte (C++17) so the Win SDK's
    # `byte` typedef in strmif.h doesn't clash with it. Required for
    # DirectShow + modern MSVC C++17 mode.
    _HAS_STD_BYTE=0
    ENABLE_PULL_MODE=1
    ENABLE_SOURCE_SEEKING=1
    GSTREAMER_LITE
    GST_REMOVE_DEPRECATED
    GST_REMOVE_DISABLED
    GST_DISABLE_GST_DEBUG
    GST_DISABLE_LOADSAVE
    G_DISABLE_DEPRECATED
    G_DISABLE_ASSERT
    INITGUID
    $<$<CONFIG:Release>:NDEBUG>
    # Marks that ffmpegwrapper.cpp + ffmpeg_loader.cpp are compiled
    # into this DLL. fxplugins.c uses it to gate the registration
    # call. Mirrors the source-list generator expression above.
    $<$<BOOL:${OPENJFX_FFMPEG_INCLUDE_DIR}>:OPENJFX_HAVE_FFMPEGWRAPPER>
    # Marks that ffmpegdemux is compiled in; fxplugins.c gates its
    # registration call on this (built together with the wrapper).
    $<$<BOOL:${OPENJFX_FFMPEG_INCLUDE_DIR}>:OPENJFX_HAVE_FFMPEGDEMUX>
)
target_compile_options(fxplugins PRIVATE
    ${FXP_COMMON_OPTIONS}
    # Upstream uses /O1 here, not /O2 (Makefile O1FLAGS).
    $<$<CONFIG:Release>:/O1 ${FXP_RELEASE_OPTIONS}>
)
target_include_directories(fxplugins PRIVATE
    # Plugin sources need their own dir + sibling plugin dirs.
    ${PLUGINS_BASE}
    ${PLUGINS_BASE}/dshowwrapper
    ${PLUGINS_BASE}/javasource
    ${PLUGINS_BASE}/progressbuffer
    ${PLUGINS_BASE}/progressbuffer/win32
    ${PLUGINS_BASE}/mfwrapper
    ${PLUGINS_BASE}/ffmpegwrapper
    ${PLUGINS_BASE}/ffmpegdemux
    # GLib + GStreamer + BaseClasses headers.
    ${GLIB_BASE}
    ${GLIB_BASE}/glib
    ${GLIB_BASE}/gmodule
    ${GLIB_BASE}/build/win32/vs100
    ${GST_BASE}/gstreamer
    ${GST_BASE}/gstreamer/libs
    ${GST_BASE}/gst-plugins-base/gst-libs
    ${BC_BASE}
    # ffmpeg public headers from skiafx.ffmpeg-conventions. Empty
    # generator expression when the var is unset — does NOT prevent
    # the build, just makes ffmpeg-dependent TUs unbuildable (already
    # gated out of the source list above).
    $<$<BOOL:${OPENJFX_FFMPEG_INCLUDE_DIR}>:${OPENJFX_FFMPEG_INCLUDE_DIR}>
)
target_link_options(fxplugins PRIVATE
    /SUBSYSTEM:WINDOWS
    /TLBID:1
    /DYNAMICBASE
    /NXCOMPAT
    /NODEFAULTLIB:libcmt
    /ERRORREPORT:QUEUE
    /MANIFESTUAC:"level='asInvoker' uiAccess='false'"
    $<$<CONFIG:Release>:/OPT:REF /OPT:ICF>
    # skia-fx: RelWithDebInfo emits a linker .map for crash symbolication.
    $<$<CONFIG:RelWithDebInfo>:/MAP>
)
target_link_libraries(fxplugins PRIVATE
    baseclasses
    gstreamer-lite
    glib-lite
    winmm.lib
    strmiids.lib
    kernel32.lib
    user32.lib
    shell32.lib
    advapi32.lib
    ole32.lib
    oleaut32.lib
    Mfplat.lib
    mfuuid.lib
    d3d11.lib       # Phase-3 M2: D3D11VA + IMFDXGIBuffer in mfwrapper
)
