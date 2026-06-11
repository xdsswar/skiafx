# ---------------------------------------------------------------------------
# jfxmedia.dll — JFX Java↔native media bridge (Windows).
#
# Translated from upstream Makefile:
#   jfxmedia/projects/win/Makefile
#
# 30 .cpp + 1 .c sources, organized by responsibility:
#   jni/                  — Java callable functions (JNI entry points)
#   PipelineManagement/   — GStreamer pipeline construction
#   MediaManagement/      — Media object lifecycle
#   Locator/              — Media URI / network handling
#   Utils/                — color conversion, threading, exceptions
#   platform/gstreamer/   — Windows-specific GStreamer bridge
#
# Depends on glib-lite, gstreamer-lite, fxplugins, plus JDK headers
# from JAVA_HOME.
# ---------------------------------------------------------------------------

set(NATIVE_ROOT   ${CMAKE_CURRENT_SOURCE_DIR}/../..)
set(JFXM_BASE     ${NATIVE_ROOT}/jfxmedia)
set(PLUGINS_BASE  ${NATIVE_ROOT}/gstreamer/plugins)
set(GST_BASE      ${NATIVE_ROOT}/gstreamer/gstreamer-lite)
set(GLIB_BASE     ${NATIVE_ROOT}/gstreamer/3rd_party/glib)

# JDK headers — set via JAVA_HOME env var. CMake's FindJNI would also
# work but JAVA_HOME is the project convention (Gradle toolchain).
if(NOT DEFINED ENV{JAVA_HOME})
    message(FATAL_ERROR
        "jfxmedia requires JAVA_HOME to be set (for jni.h headers).")
endif()
set(JAVA_HOME $ENV{JAVA_HOME})

add_library(jfxmedia SHARED
    # JNI entry points
    ${JFXM_BASE}/jni/com_sun_media_jfxmedia_logging_Logger.cpp
    ${JFXM_BASE}/jni/JavaBandsHolder.cpp
    ${JFXM_BASE}/jni/JavaMediaWarningListener.cpp
    ${JFXM_BASE}/jni/JavaPlayerEventDispatcher.cpp
    ${JFXM_BASE}/jni/JniUtils.cpp
    ${JFXM_BASE}/jni/Logger.cpp
    ${JFXM_BASE}/jni/NativeVideoBuffer.cpp
    ${JFXM_BASE}/jni/NativeAudioEqualizer.cpp
    ${JFXM_BASE}/jni/NativeAudioSpectrum.cpp
    ${JFXM_BASE}/jni/NativeEqualizerBand.cpp
    ${JFXM_BASE}/jni/JavaInputStreamCallbacks.cpp
    # Pipeline management
    ${JFXM_BASE}/PipelineManagement/AudioTrack.cpp
    ${JFXM_BASE}/PipelineManagement/Pipeline.cpp
    ${JFXM_BASE}/PipelineManagement/PipelineFactory.cpp
    ${JFXM_BASE}/PipelineManagement/Track.cpp
    ${JFXM_BASE}/PipelineManagement/VideoFrame.cpp
    ${JFXM_BASE}/PipelineManagement/VideoTrack.cpp
    ${JFXM_BASE}/PipelineManagement/SubtitleTrack.cpp
    # Media management
    ${JFXM_BASE}/MediaManagement/Media.cpp
    ${JFXM_BASE}/MediaManagement/MediaManager.cpp
    # Locator
    ${JFXM_BASE}/Locator/Locator.cpp
    ${JFXM_BASE}/Locator/LocatorStream.cpp
    # Platform-specific GStreamer bridge
    ${JFXM_BASE}/platform/gstreamer/GstMedia.cpp
    ${JFXM_BASE}/platform/gstreamer/GstMediaPlayer.cpp
    ${JFXM_BASE}/platform/gstreamer/GstPlatform.cpp
    ${JFXM_BASE}/platform/gstreamer/GstAudioEqualizer.cpp
    ${JFXM_BASE}/platform/gstreamer/GstAudioPlaybackPipeline.cpp
    ${JFXM_BASE}/platform/gstreamer/GstAudioSpectrum.cpp
    ${JFXM_BASE}/platform/gstreamer/GstAVPlaybackPipeline.cpp
    ${JFXM_BASE}/platform/gstreamer/GstElementContainer.cpp
    ${JFXM_BASE}/platform/gstreamer/GstJniUtils.cpp
    ${JFXM_BASE}/platform/gstreamer/GstMediaManager.cpp
    ${JFXM_BASE}/platform/gstreamer/GstPipelineFactory.cpp
    ${JFXM_BASE}/platform/gstreamer/GstVideoFrame.cpp
    # Phase-3 M3-B zero-copy texture meta (shared with fxplugins)
    ${PLUGINS_BASE}/openjfx_media_d3d11_meta.cpp
    # Phase-3 M3-B view-size hint exported to mfwrapper across DLLs
    ${JFXM_BASE}/jni/MediaTargetSize.cpp
    # Phase-4 ffmpeg config bridge — Java sets a library dir which
    # we forward to the loader living in fxplugins.dll via GetProcAddress
    ${JFXM_BASE}/jni/MediaFfmpegConfig.cpp
    # MediaMixer JNI bridge — resolves openjfx_ffmpeg_remux from
    # fxplugins.dll (same cross-DLL pattern as MediaFfmpegConfig)
    ${JFXM_BASE}/jni/MediaMixerBridge.cpp
    # Utilities (cross-platform + Windows-specific)
    ${JFXM_BASE}/Utils/ColorConverter.c
    ${JFXM_BASE}/Utils/MediaWarningDispatcher.cpp
    ${JFXM_BASE}/Utils/LowLevelPerf.cpp
    ${JFXM_BASE}/Utils/win32/WinCriticalSection.cpp
    ${JFXM_BASE}/Utils/win32/WinDllMain.cpp
    ${JFXM_BASE}/Utils/win32/WinThread.cpp
    ${JFXM_BASE}/Utils/win32/WinExceptionHandler.cpp
)
set_target_properties(jfxmedia PROPERTIES
    OUTPUT_NAME jfxmedia
    PREFIX ""
)
target_compile_definitions(jfxmedia PRIVATE
    WIN32 _WINDOWS _USRDLL _WINDLL _MBCS _WIN64
    _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR
    _HAS_STD_BYTE=0
    JFXMEDIA_JNI_EXPORTS
    TARGET_OS_WIN32=1
    _WIN32_WINNT=0x0500
    GST_DISABLE_LOADSAVE
    GST_REMOVE_DEPRECATED
    G_DISABLE_DEPRECATED
    GSTREAMER_LITE
    $<$<CONFIG:Release>:NDEBUG>
)
target_compile_options(jfxmedia PRIVATE
    /nologo /W3 /WX- /Gm- /EHsc /GS /fp:precise
    /Zc:wchar_t- /Zc:forScope /Gd /errorReport:queue
    # Upstream uses /O2 here, not /O1 like the other targets.
    $<$<CONFIG:Release>:/O2 /MD>
)
target_include_directories(jfxmedia PRIVATE
    ${JFXM_BASE}
    ${JFXM_BASE}/jni
    "${JAVA_HOME}/include"
    "${JAVA_HOME}/include/win32"
    # JNI headers generated by `javac -h` from Java sources
    # (com_sun_media_*_*.h). The native convention plugin passes this
    # via -DOPENJFX_JNI_HEADERS_DIR.
    ${OPENJFX_JNI_HEADERS_DIR}
    ${GLIB_BASE}
    ${GLIB_BASE}/glib
    ${GLIB_BASE}/gmodule
    ${GLIB_BASE}/build/win32/vs100
    ${GST_BASE}/gstreamer
    ${GST_BASE}/gst-plugins-base/gst-libs
    ${GST_BASE}/gst-plugins-base/win32/common
    ${GST_BASE}/gstreamer/libs
    ${PLUGINS_BASE}
)
target_link_options(jfxmedia PRIVATE
    /SUBSYSTEM:WINDOWS
    /DYNAMICBASE
    /NXCOMPAT
    /ERRORREPORT:QUEUE
    /MANIFESTUAC:"level='asInvoker' uiAccess='false'"
    $<$<CONFIG:Release>:/OPT:REF /OPT:ICF>
    # skia-fx: RelWithDebInfo emits a linker .map for crash symbolication.
    $<$<CONFIG:RelWithDebInfo>:/MAP>
)
target_link_libraries(jfxmedia PRIVATE
    gstreamer-lite
    glib-lite
    Winmm.lib
    kernel32.lib
    user32.lib
    comdlg32.lib
    advapi32.lib
)
