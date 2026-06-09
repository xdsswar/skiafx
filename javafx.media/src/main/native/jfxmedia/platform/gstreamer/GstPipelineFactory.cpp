/*
 * Copyright (c) 2010, 2024, Oracle and/or its affiliates. All rights reserved.
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
#include "GstPipelineFactory.h"

#include "GstAudioPlaybackPipeline.h"
#include "GstAVPlaybackPipeline.h"

#include <string>
#include <Common/ProductFlags.h>
#include <Common/VSMemory.h>
#include <MediaManagement/MediaTypes.h>
#include <Locator/LocatorStream.h>
#include <jfxmedia_errors.h>
#include <gst/gstelement.h>
#include <Utils/LowLevelPerf.h>
#include <algorithm>
#if ENABLE_VIDEOCONVERT
#include <gst/app/gstappsink.h>
#endif

// From HLSConnectionHolder.java
#define HLS_PROP_GET_HLS_MODE   2
#define HLS_PROP_GET_MIMETYPE   3
#define HLS_VALUE_MIMETYPE_MP2T 1
#define HLS_VALUE_MIMETYPE_MP3  2
#define HLS_VALUE_MIMETYPE_FMP4 3
#define HLS_VALUE_MIMETYPE_AAC  4


//*************************************************************************************************
//********** class CGstPipelineFactory
//*************************************************************************************************

CGstPipelineFactory::CGstPipelineFactory()
{
}

// Here we can only delete local resources not dependent on other libraries such as GStreamer
// because the destructor is called after the main exits and we possible don't have access
// to library functions or the are incorrect.
CGstPipelineFactory::~CGstPipelineFactory()
{}

// skia-fx: pick the companion audio demuxer + decoder from the
// companion's container content type, for the http(s) dual-source
// path that streams through the javasource Java-I/O bridge. This is
// the content-type analogue of the file-extension sniff used by the
// native filesrc companion path (CreatePlayerPipeline, below) — it
// works for extensionless URLs (e.g. .../videoplayback?...) where the
// extension sniff can't. Empty content type (HLS / single-source)
// leaves the options untouched.
static void PickCompanionAudioDecoder(CPipelineOptions* pOptions, const string& contentType)
{
    if (pOptions == NULL || contentType.empty())
        return;

#if TARGET_OS_WIN32
    if (contentType == CONTENT_TYPE_WEBM || contentType == CONTENT_TYPE_MATROSKA)
    {
        //   webm / mkv → matroskademux + ffmpegwrapper (opus/vorbis)
        pOptions->SetAudioStreamParser("matroskademux");
        pOptions->SetAudioDecoder("ffmpegwrapper");
    }
    else if (contentType == CONTENT_TYPE_MP4 || contentType == CONTENT_TYPE_M4A
          || contentType == CONTENT_TYPE_M4V)
    {
        //   mp4 / m4a / m4v → qtdemux + dshowwrapper (AAC)
        pOptions->SetAudioStreamParser("qtdemux");
        pOptions->SetAudioDecoder("dshowwrapper");
    }
    else if (contentType == CONTENT_TYPE_AAC)
    {
        //   raw adts AAC → dshowwrapper, no demuxer
        pOptions->SetAudioDecoder("dshowwrapper");
    }
    else if (contentType == CONTENT_TYPE_MPA || contentType == CONTENT_TYPE_MP3)
    {
        //   mp3 → mpegaudioparse + dshowwrapper
        pOptions->SetAudioStreamParser("mpegaudioparse");
        pOptions->SetAudioDecoder("dshowwrapper");
    }
    // else: unknown container — leave the audio bin to default
    // negotiation rather than forcing a wrong element.
#else
    (void)pOptions;
    (void)contentType;
#endif // TARGET_OS_WIN32
}

uint32_t CGstPipelineFactory::CreatePlayerPipeline(CLocator* locator, CPipelineOptions *pOptions, CPipeline** ppPipeline)
{
    LOWLEVELPERF_EXECTIMESTART("CGstPipelineFactory::CreatePlayerPipeline()");

    uint32_t uRetCode = ERROR_NONE;

    GstElementContainer Elements;

    // *ppPipeline should be set to NULL
    if (NULL == locator || NULL == pOptions || NULL != *ppPipeline)
        return ERROR_FUNCTION_PARAM_NULL;

    if (locator->GetType() != CLocator::kStreamLocatorType)
        return ERROR_LOCATOR_UNSUPPORTED_TYPE;

    if (locator->GetContentType().empty())
        return ERROR_LOCATOR_CONTENT_TYPE_NULL;

    // Save content type to options
    pOptions->SetContentType(locator->GetContentType());

    CLocatorStream* streamLocator = (CLocatorStream*)locator;
    CStreamCallbacks *callbacks = streamLocator->GetCallbacks();
    CStreamCallbacks *audioCallbacks = streamLocator->GetAudioCallbacks();

    if (NULL == callbacks)
        return ERROR_LOCATOR_NULL;

    int hlsMode = callbacks->Property(HLS_PROP_GET_HLS_MODE, 0);
    pOptions->SetHLSModeEnabled(hlsMode == 1);
    int streamMimeType = callbacks->Property(HLS_PROP_GET_MIMETYPE, 0);
    pOptions->SetStreamMimeType(streamMimeType);

    // Create main source.
    GstElement* pSource = NULL;
    GstElement* pBuffer = NULL;
    uRetCode = CreateSourceElement(locator, callbacks,
            streamMimeType, &pSource, &pBuffer, pOptions);
    if (ERROR_NONE != uRetCode)
        return uRetCode;

    // Store source element, so it can be used to build rest of pipeline
    Elements.add(SOURCE, pSource);
    Elements.add(SOURCE_BUFFER, pBuffer);

    // Check to see if we have separate audio stream
    if (audioCallbacks != NULL)
    {
        int streamMimeType = audioCallbacks->Property(HLS_PROP_GET_MIMETYPE, 0);
        pOptions->SetAudioStreamMimeType(streamMimeType);

        GstElement* pAudioSource = NULL;
        GstElement* pAudioBuffer = NULL;
        uRetCode = CreateSourceElement(locator, audioCallbacks,
                streamMimeType, &pAudioSource, &pAudioBuffer, pOptions);
        if (ERROR_NONE != uRetCode)
            return uRetCode;

        // Store source element, so it can be used to build audio portion of pipeline
        Elements.add(AUDIO_SOURCE, pAudioSource);
        Elements.add(AUDIO_SOURCE_BUFFER, pAudioBuffer);

        // Mark pipeline as multi source
        pOptions->SetPipelineType(CPipelineOptions::kAudioSourcePipeline);

        // skia-fx: dual-source companion over http(s) (GstMedia.cpp routed
        // it through the Java bridge and stamped the container content
        // type). Pick its demuxer + decoder by content type. HLS audio-ext
        // streams reach this branch too but carry no companion content
        // type — they keep their HLS-mime-driven parser/decoder.
        PickCompanionAudioDecoder(pOptions,
                streamLocator->GetCompanionAudioContentTypeStr());
    }
    else if (!streamLocator->GetCompanionAudioLocation().empty())
    {
        // skia-fx: dual-source from Media(video, audio). Unlike the
        // HLS audioCallbacks path above (which streams audio bytes
        // through the Java I/O bridge), the companion source here is
        // a STANDALONE URL that GStreamer can read directly via
        // filesrc. We construct the source element natively and feed
        // it into the same dual-source pipeline plumbing the HLS path
        // uses (kAudioSourcePipeline). Because the companion lives in
        // the same GstPipeline as the primary, both share the
        // pipeline's GstClock — sample-accurate sync comes for free
        // from GStreamer's clock distribution.
        const std::string& companionUrl = streamLocator->GetCompanionAudioLocation();
        GstElement* pAudioSource = NULL;
        uRetCode = CreateCompanionAudioSource(companionUrl, &pAudioSource);
        if (ERROR_NONE != uRetCode)
            return uRetCode;

        Elements.add(AUDIO_SOURCE, pAudioSource);
        // No SOURCE_BUFFER for the companion — filesrc does its own
        // read-ahead; downstream queues handle backpressure.

        pOptions->SetPipelineType(CPipelineOptions::kAudioSourcePipeline);

        // Pick the right audio demuxer + decoder based on the
        // companion's container extension. The audio bin downstream
        // is a [demuxer →] parser → decoder → sink chain — without a
        // demuxer the audio bin gets raw file bytes and caps
        // negotiation fails (manifests as the pipeline rapidly
        // transitioning states = video flashing). The decoder
        // selection here MUST survive the later container-factory
        // dispatch — see hasCompanionAudio guard in
        // CreateMatroskaPipeline / CreateMP4Pipeline.
        std::string lower = companionUrl;
        for (size_t i = 0; i < lower.size(); ++i) {
            lower[i] = (char)g_ascii_tolower(lower[i]);
        }
        // Strip any URL query string so ext sniffing isn't fooled by
        // "?token=..." appended to the path.
        size_t q = lower.find('?');
        if (q != std::string::npos) lower = lower.substr(0, q);

        const char* audioParser = NULL;
        const char* audioDecoder = NULL;

        // Container detection is platform-agnostic; the decoder names
        // below ARE platform-specific (dshowwrapper / ffmpegwrapper
        // exist on Windows only). When porting to macOS / Linux,
        // remap to osxaudiosink-side decoders / avaudiodecoder here.
#if TARGET_OS_WIN32
        if (lower.size() >= 4 && (
                lower.compare(lower.size() - 4, 4, ".m4a") == 0
             || lower.compare(lower.size() - 4, 4, ".mp4") == 0
             || lower.compare(lower.size() - 4, 4, ".aac") == 0))
        {
            //   .m4a / .mp4 / .aac → qtdemux + dshowwrapper (AAC)
            audioParser  = "qtdemux";
            audioDecoder = "dshowwrapper";
        }
        else if ((lower.size() >= 5 && lower.compare(lower.size() - 5, 5, ".webm") == 0)
              || (lower.size() >= 4 && lower.compare(lower.size() - 4, 4, ".mkv") == 0))
        {
            //   .webm / .mkv → matroskademux + ffmpegwrapper (vorbis/opus)
            audioParser  = "matroskademux";
            audioDecoder = "ffmpegwrapper";
        }
        // .mp3 / .ogg / .wav: no audioParser — handled by audio bin
        // defaults.
#else
        // TODO(dual-source-mac, dual-source-linux): wire equivalent
        // demuxer + decoder choices for macOS (osxaudio + native AAC)
        // and Linux (qtdemux/matroskademux + avaudiodecoder). For now
        // the companion-audio feature is Windows-only.
        (void)audioParser;
        (void)audioDecoder;
#endif // TARGET_OS_WIN32

        if (audioParser != NULL) {
            pOptions->SetAudioStreamParser(audioParser);
        }
        if (audioDecoder != NULL) {
            pOptions->SetAudioDecoder(audioDecoder);
        }
        pOptions->SetAudioStreamMimeType(0);
    }

    uRetCode = CreatePipeline(pOptions, &Elements, ppPipeline);
    if (ERROR_NONE != uRetCode) {
        return uRetCode;
    }

    if (NULL == *ppPipeline)
        return ERROR_PIPELINE_CREATION;

    LOWLEVELPERF_EXECTIMESTOP("CGstPipelineFactory::CreatePlayerPipeline()");

    return uRetCode;
}

// Creates pipeline based on options provided.
// Basically calls Create*Pipeline() based on options.
uint32_t CGstPipelineFactory::CreatePipeline(CPipelineOptions *pOptions, GstElementContainer* pElements, CPipeline** ppPipeline)
{
    LOWLEVELPERF_EXECTIMESTART("CGstPipelineFactory::CreatePipeline()");

    uint32_t uRetCode = ERROR_NONE;

    if (NULL == pOptions)
        return ERROR_FUNCTION_PARAM_NULL;

    if (CONTENT_TYPE_MP4 == pOptions->GetContentType() ||
        CONTENT_TYPE_M4A == pOptions->GetContentType() ||
        CONTENT_TYPE_M4V == pOptions->GetContentType())
    {
        GstElement* pVideoSink = NULL;
#if ENABLE_APP_SINK && !ENABLE_NATIVE_SINK
        pVideoSink = CreateElement("appsink");
        if (NULL == pVideoSink)
            return ERROR_GSTREAMER_VIDEO_SINK_CREATE;
#endif // !(ENABLE_APP_SINK && !ENABLE_NATIVE_SINK)

        if (CONTENT_TYPE_MP4 == pOptions->GetContentType() ||
            CONTENT_TYPE_M4A == pOptions->GetContentType() ||
            CONTENT_TYPE_M4V == pOptions->GetContentType())
        {
            uRetCode = CreateMP4Pipeline(pVideoSink, pOptions, pElements, ppPipeline);
            if (ERROR_NONE != uRetCode)
                return uRetCode;
        }
    }
    else if (CONTENT_TYPE_MPA == pOptions->GetContentType() ||
             CONTENT_TYPE_MP3 == pOptions->GetContentType())
    {
        uRetCode = CreateMp3AudioPipeline(pOptions, pElements, ppPipeline);
        if (ERROR_NONE != uRetCode)
            return uRetCode;
    }
    else if (CONTENT_TYPE_WAV == pOptions->GetContentType())
    {
        uRetCode = CreateWavPcmAudioPipeline(pOptions, pElements, ppPipeline);
        if (ERROR_NONE != uRetCode)
            return uRetCode;
    }
    else if (CONTENT_TYPE_AIFF == pOptions->GetContentType())
    {
        uRetCode = CreateAiffPcmAudioPipeline(pOptions, pElements, ppPipeline);
        if (ERROR_NONE != uRetCode)
            return uRetCode;
    }
    else if (CONTENT_TYPE_M3U8 == pOptions->GetContentType() ||
             CONTENT_TYPE_M3U == pOptions->GetContentType())
    {
        GstElement* pVideoSink = NULL;
#if ENABLE_APP_SINK && !ENABLE_NATIVE_SINK
        pVideoSink = CreateElement("appsink");
        if (NULL == pVideoSink)
            return ERROR_GSTREAMER_VIDEO_SINK_CREATE;
#endif // !(ENABLE_APP_SINK && !ENABLE_NATIVE_SINK)

        uRetCode = CreateHLSPipeline(pVideoSink, pOptions, pElements, ppPipeline);
        if (ERROR_NONE != uRetCode)
            return uRetCode;
    }
    else if (CONTENT_TYPE_MATROSKA == pOptions->GetContentType() ||
             CONTENT_TYPE_WEBM == pOptions->GetContentType())
    {
        // skia-fx: .mkv / .webm. matroskademux is built into
        // gstreamer-lite (skiafx.matroska-conventions); decoders are
        // picked dynamically by GstAVPlaybackPipeline::LoadDecoder
        // which prefers ffmpegwrapper when available.
        GstElement* pVideoSink = NULL;
#if ENABLE_APP_SINK && !ENABLE_NATIVE_SINK
        pVideoSink = CreateElement("appsink");
        if (NULL == pVideoSink)
            return ERROR_GSTREAMER_VIDEO_SINK_CREATE;
#endif // !(ENABLE_APP_SINK && !ENABLE_NATIVE_SINK)

        uRetCode = CreateMatroskaPipeline(pVideoSink, pOptions, pElements, ppPipeline);
        if (ERROR_NONE != uRetCode)
            return uRetCode;
    }
    else
    {
        return ERROR_LOCATOR_UNSUPPORTED_MEDIA_FORMAT;
    }

    if (NULL == *ppPipeline)
        uRetCode = ERROR_PIPELINE_CREATION;

    LOWLEVELPERF_EXECTIMESTOP("CGstPipelineFactory::CreatePipeline()");

    return uRetCode;
}

/**
  * GstElement* CreateSourceElement()
  *
  * @param   locator   Locator of the source media.
  * @param   callbacks Callbacks to read/control media stream.
  * @param   ppElement Pointer to address of source element.
  * @return  An error code.
  */
// ---------------------------------------------------------------------------
// Skia-fx: companion-audio source element factory.
//
// When the caller built Media(audio, video[, headers]), the audio URL
// arrives here as a plain string. Build the matching GStreamer source
// element directly:
//
//   file:///path/file.m4a   →  filesrc location=/path/file.m4a
//   https://host/audio.aac  →  souphttpsrc location=https://host/audio.aac
//                                          user-agent=...   (when set)
//                                          extra-headers=… (when set)
//
// No Java InputStream bridge, no progressbuffer — filesrc and
// souphttpsrc do their own I/O / read-ahead and downstream queues
// handle backpressure. The source goes straight into the existing
// kAudioSourcePipeline dual-source plumbing.
// ---------------------------------------------------------------------------
uint32_t CGstPipelineFactory::CreateCompanionAudioSource(
    const std::string& url, GstElement** ppElement)
{
    if (url.empty() || ppElement == NULL)
        return ERROR_FUNCTION_PARAM_NULL;

    GstElement* source = NULL;

    if (url.compare(0, 5, "file:") == 0)
    {
        // filesrc is registered cross-platform (the gstreamer-lite
        // source drop strips it by default; we reinstate it via the
        // upstream gstfilesrc.c added in the gstreamer/plugins/elements
        // tree — see skia-fx comment in gstcoreelementsplugin.c).
        source = gst_element_factory_make("filesrc", NULL);
        if (NULL == source) return ERROR_GSTREAMER_ELEMENT_CREATE;

        gchar* fsPath = g_filename_from_uri(url.c_str(), NULL, NULL);
        if (fsPath != NULL)
        {
            g_object_set(source, "location", fsPath, NULL);
            g_free(fsPath);
        }
        else
        {
            // Couldn't parse — fall back to stripping the scheme.
            const char* tail = url.c_str() + 5;
            while (*tail == '/') tail++;
            g_object_set(source, "location", tail, NULL);
        }
    }
    else if (url.compare(0, 7, "http://") == 0
          || url.compare(0, 8, "https://") == 0)
    {
        // souphttpsrc isn't currently in our gstreamer-lite build —
        // it depends on libsoup which we don't ship. Until that's
        // wired up, http(s):// companion URLs are unsupported.
        // TODO(dual-source-http): bring souphttpsrc (or write a small
        // libcurl-backed shim) into the lite plugin set, then apply
        // user-agent + extra-headers from the Locator here.
        g_warning("companion http(s) URL not yet supported: %s", url.c_str());
        return ERROR_GSTREAMER_ELEMENT_CREATE;
    }
    else
    {
        // Unknown scheme. We only ship filesrc in gstreamer-lite;
        // rtsp / rtmp / etc. would need their respective plugins,
        // which aren't part of the JFX build. Surface a clear error
        // rather than a cryptic plugin-not-found later.
        g_warning("unsupported companion URL scheme: %s "
                  "(only file:// is supported)", url.c_str());
        return ERROR_GSTREAMER_ELEMENT_CREATE;
    }

    *ppElement = source;
    return ERROR_NONE;
}

uint32_t CGstPipelineFactory::CreateSourceElement(CLocator *locator, CStreamCallbacks *callbacks,
                                                  int streamMimeType, GstElement **ppElement,
                                                   GstElement **ppBuffer, CPipelineOptions *pOptions)
{
    GstElement *source = NULL;
    GstElement *buffer = NULL;

   if (NULL == locator || NULL == callbacks)
        return ERROR_FUNCTION_PARAM_NULL;

    GstElement *javaSource = CreateElement("javasource");
    if (NULL == javaSource)
        return ERROR_GSTREAMER_ELEMENT_CREATE;

    bool isRandomAccess = callbacks->IsRandomAccess();

    g_signal_connect(javaSource, "read-next-block", G_CALLBACK(SourceReadNextBlock), callbacks);
    g_signal_connect(javaSource, "copy-block", G_CALLBACK(SourceCopyBlock), callbacks);
    g_signal_connect(javaSource, "seek-data", G_CALLBACK(SourceSeekData), callbacks);
    g_signal_connect(javaSource, "close-connection", G_CALLBACK(SourceCloseConnection), callbacks);
    g_signal_connect(javaSource, "property", G_CALLBACK(SourceProperty), callbacks);

    if (isRandomAccess)
        g_signal_connect(javaSource, "read-block", G_CALLBACK(SourceReadBlock), callbacks);

    if (pOptions->GetHLSModeEnabled())
        g_object_set(javaSource, "hls-mode", TRUE, NULL);

    if (streamMimeType == HLS_VALUE_MIMETYPE_MP2T)
        g_object_set(javaSource, "mimetype", CONTENT_TYPE_MP2T, NULL);
    else if (streamMimeType == HLS_VALUE_MIMETYPE_MP3)
        g_object_set(javaSource, "mimetype", CONTENT_TYPE_MPA, NULL);
    else if (streamMimeType == HLS_VALUE_MIMETYPE_FMP4)
        g_object_set(javaSource, "mimetype", CONTENT_TYPE_FMP4, NULL);
    else if (streamMimeType == HLS_VALUE_MIMETYPE_AAC)
        g_object_set(javaSource, "mimetype", CONTENT_TYPE_AAC, NULL);

    g_object_set(javaSource,
                 "size", (gint64)locator->GetSizeHint(),
                 "is-seekable", (gboolean)callbacks->IsSeekable(),
                 "is-random-access", (gboolean)isRandomAccess,
                 "location", locator->GetLocation().c_str(),
                 NULL);

    bool needBuffer = callbacks->NeedBuffer();
    pOptions->SetBufferingEnabled(needBuffer);

    if (needBuffer)
    {
        g_object_set(javaSource, "stop-on-pause", FALSE, NULL);
        source = gst_bin_new(NULL);
        if (NULL == source)
            return ERROR_GSTREAMER_BIN_CREATE;

        if (pOptions->GetHLSModeEnabled())
            buffer = CreateElement("hlsprogressbuffer");
        else
            buffer = CreateElement("progressbuffer");

        if (NULL == buffer)
            return ERROR_GSTREAMER_ELEMENT_CREATE;

        // skia-fx: OPT-IN deeper read-ahead cushion on remote streams.
        // The progressbuffer caches `prebuffer-time * measured-bandwidth`
        // bytes before it reports ready AND before it resumes the src task
        // after an underrun (progressbuffer.c: range_stop = end + bandwidth
        // * prebuffer_time). A larger value loads further ahead so transient
        // network dips are absorbed. OFF by default (stays at the stock 2s)
        // because a bigger prebuffer slows the initial start of ALL remote
        // playback; it's a knob for the still-pending remote dual-source
        // work. Set OPENJFX_MEDIA_PREBUFFER_TIME (0 < t <= 20 seconds).
        if (!pOptions->GetHLSModeEnabled() &&
            g_object_class_find_property(G_OBJECT_GET_CLASS(buffer),
                                         "prebuffer-time") != NULL)
        {
            const gchar* envPb = g_getenv("OPENJFX_MEDIA_PREBUFFER_TIME");
            if (envPb != NULL && *envPb != '\0')
            {
                gdouble v = g_ascii_strtod(envPb, NULL);
                if (v > 0.0 && v <= 20.0)
                    g_object_set(buffer, "prebuffer-time", v, NULL);
            }
        }

        gst_bin_add_many(GST_BIN(source), javaSource, buffer, NULL);

        if (!gst_element_link(javaSource, buffer))
            return ERROR_GSTREAMER_ELEMENT_LINK;
    }
    else
    {
        source = javaSource;
    }

    *ppElement = source;
    *ppBuffer = buffer;

    return ERROR_NONE;
}

gint CGstPipelineFactory::SourceReadNextBlock(GstElement *src, gpointer data)
{
    return ((CStreamCallbacks*)data)->ReadNextBlock();
}

gint CGstPipelineFactory::SourceReadBlock(GstElement *src, guint64 position, guint size, gpointer data)
{
    return ((CStreamCallbacks*)data)->ReadBlock(position, size);
}

void CGstPipelineFactory::SourceCopyBlock(GstElement *src, gpointer buffer, int size, gpointer data)
{
    ((CStreamCallbacks*)data)->CopyBlock(buffer, size);
}

gint64 CGstPipelineFactory::SourceSeekData(GstElement *src, guint64 offset, gpointer data)
{
    return (gint64)((CStreamCallbacks*)data)->Seek((int64_t)offset);
}

int CGstPipelineFactory::SourceProperty(GstElement *src, int prop, int value, gpointer data)
{
    return ((CStreamCallbacks*)data)->Property(prop, value);
}

void CGstPipelineFactory::SourceCloseConnection(GstElement *src, gpointer data)
{
    CStreamCallbacks* callbacks = (CStreamCallbacks*)data;
    callbacks->CloseConnection();
    g_signal_handlers_disconnect_by_func (src, (void*)G_CALLBACK (SourceReadNextBlock), callbacks);
    g_signal_handlers_disconnect_by_func (src, (void*)G_CALLBACK (SourceReadBlock), callbacks);
    g_signal_handlers_disconnect_by_func (src, (void*)G_CALLBACK (SourceCopyBlock), callbacks);
    g_signal_handlers_disconnect_by_func (src, (void*)G_CALLBACK (SourceSeekData), callbacks);
    g_signal_handlers_disconnect_by_func (src, (void*)G_CALLBACK (SourceCloseConnection), callbacks);
    g_signal_handlers_disconnect_by_func (src, (void*)G_CALLBACK (SourceProperty), callbacks);
    delete callbacks;
}

/**
    * GstElement* CreateAudioSinkElement(char* name)
    *
    * @param   name    The name to assign to the audio sink element.
    * @return  The audio sink element.
    */
GstElement* CGstPipelineFactory::CreateAudioSinkElement()
{
#if TARGET_OS_WIN32
    return CreateElement("directsoundsink");
#elif  TARGET_OS_MAC
    return CreateElement("osxaudiosink");
#elif  TARGET_OS_LINUX
    return CreateElement("alsasink");
#else
    return NULL;
#endif
}

void CGstPipelineFactory::OnBufferPadAdded(GstElement* element, GstPad* pad, GstElement* peer)
{
    uint32_t uErrorCode = ERROR_NONE;

    GstElement* source_bin = GST_ELEMENT_PARENT(element);
    GstElement* pipeline = GST_ELEMENT_PARENT(source_bin);

    GstPad *src_pad = gst_ghost_pad_new("src", pad);
    if (NULL == src_pad)
        uErrorCode = ERROR_GSTREAMER_CREATE_GHOST_PAD;

    if (ERROR_NONE == uErrorCode)
    {
        if (!gst_pad_set_active(src_pad, TRUE) || !gst_element_add_pad(source_bin, src_pad))
            uErrorCode = ERROR_GSTREAMER_ELEMENT_ADD_PAD;

        if (ERROR_NONE == uErrorCode)
        {
            if (!gst_bin_add(GST_BIN(pipeline), peer))
                uErrorCode = ERROR_GSTREAMER_BIN_ADD_ELEMENT;

            if (ERROR_NONE == uErrorCode)
            {
                if (GST_STATE_CHANGE_FAILURE == gst_element_set_state(peer, GST_STATE_READY))
                    uErrorCode = ERROR_GSTREAMER_PIPELINE_STATE_CHANGE;

                if (ERROR_NONE == uErrorCode)
                {
                    if (!gst_element_link(source_bin, peer))
                        uErrorCode = ERROR_GSTREAMER_ELEMENT_LINK;

                    if (ERROR_NONE == uErrorCode)
                        if (!gst_element_sync_state_with_parent(peer))
                            uErrorCode = ERROR_GSTREAMER_PIPELINE_STATE_CHANGE;
                }
            }
        }
    }

    if (ERROR_NONE != uErrorCode)
    {
        GstBus* bus = gst_pipeline_get_bus(GST_PIPELINE (pipeline));
        GError* error = g_error_new (0, uErrorCode, "%s",
                                     "Error in CGstPipelineFactory::OnBufferPadAdded().");
        GstMessage* message = gst_message_new_error (GST_OBJECT (pipeline), error,
                                                     "Error in CGstPipelineFactory::OnBufferPadAdded().");
        gst_bus_post (bus, message);
        gst_object_unref (bus);
    }

    g_signal_handlers_disconnect_by_func(element, (void*)G_CALLBACK(OnBufferPadAdded), peer);
}

uint32_t CGstPipelineFactory::AttachToSource(GstBin* bin, GstElement* source, GstElement* buffer, GstElement* element)
{
    // Look for progressbuffer element in the source
    GstElement* progressbuffer = GetByFactoryName(source, "progressbuffer");
    if (progressbuffer)
    {
#if ENABLE_BREAK_MY_DATA
        GstElement* dataBreaker = CreateElement ("breakmydata");
        g_object_set (G_OBJECT (dataBreaker), "skip", BREAK_MY_DATA_SKIP, "probability", BREAK_MY_DATA_PROBABILITY, NULL);
        if (!gst_bin_add (bin, dataBreaker))
            return ERROR_GSTREAMER_BIN_ADD_ELEMENT;
        if (!gst_element_link(dataBreaker, element))
            return ERROR_GSTREAMER_ELEMENT_LINK;
        g_signal_connect (progressbuffer, "pad-added", G_CALLBACK (OnBufferPadAdded), dataBreaker);
#else
        g_signal_connect (progressbuffer, "pad-added", G_CALLBACK (OnBufferPadAdded), element);
#endif
        gst_object_unref(progressbuffer);
        return ERROR_NONE;
    }

    // Source does not contain "progressbuffer".
    if (!gst_bin_add(bin, element))
        return ERROR_GSTREAMER_BIN_ADD_ELEMENT;

#if ENABLE_BREAK_MY_DATA
    GstElement* dataBreaker = CreateElement ("breakmydata");
    g_object_set (G_OBJECT (dataBreaker), "skip", BREAK_MY_DATA_SKIP, "probability", BREAK_MY_DATA_PROBABILITY, NULL);
    gst_bin_add (GST_BIN (pipeline), dataBreaker, NULL);
    gst_element_link_many(source, dataBreaker, element);
#else

    // Create src pad on source bin if we have hlsprogressbuffer
    GstElement* hlsprogressbuffer = NULL;
    if (buffer)
    {
        gst_object_ref(buffer);
        hlsprogressbuffer = buffer;
    }
    else
        hlsprogressbuffer = GetByFactoryName(source, "hlsprogressbuffer");

    if (hlsprogressbuffer)
    {
        GstPad* src_pad = gst_element_get_static_pad(hlsprogressbuffer, "src");
        if (NULL == src_pad)
            return ERROR_GSTREAMER_ELEMENT_GET_PAD;

        // Auto assign pad name, since we might have several of them
        GstPad* ghost_pad = gst_ghost_pad_new(NULL, src_pad);
        if (NULL == ghost_pad)
        {
            gst_object_unref(src_pad);
            return ERROR_GSTREAMER_CREATE_GHOST_PAD;
        }

        if (!gst_element_add_pad(source, ghost_pad))
        {
            gst_object_unref(src_pad);
            return ERROR_GSTREAMER_ELEMENT_ADD_PAD;
        }

        gst_object_unref(src_pad);

        gst_object_unref(hlsprogressbuffer);
    }

    if (!gst_element_link(source, element))
        return ERROR_GSTREAMER_ELEMENT_LINK;
#endif

    return ERROR_NONE;
}

/**
    *  GstElement* CreateMP4Pipeline(GstElement* source, char* demux_factory,
    *                              char* audiodec_factory, char* videodec_factory,
    *                              GstElement* audiosink, GstElement* videosink)
    *
    *  @param  source              Pipeline source element; must not be NULL.
    *  @param  demux_factory       Name of the demuxer factory.
    *  @param  audiodec_factory    Name of the audio decoder factory.
    *  @param  videodec_factory    Name of the video decoder factory.
    *  @param  audiosink           The audio sink element; if NULL one will be created internally.
    *  @param  videosink           The video sink element; if NULL one will be created internally.
    *
    *  @return An audio-visual playback pipeline for MP4 playback.
    */
uint32_t CGstPipelineFactory::CreateMP4Pipeline(GstElement* pVideoSink,
                                                CPipelineOptions* pOptions, GstElementContainer* pElements, CPipeline** ppPipeline)
{
    // skia-fx: when a companion audio source is present
    // (Media(video, audio) dual-source), the audio bin is wired to
    // the companion — its decoder was picked by the ext-sniff block
    // in CreatePlayerPipeline. Don't clobber it here. Same reasoning
    // as CreateMatroskaPipeline (see comment there).
    const bool hasCompanionAudio =
        (pOptions->GetPipelineType() == CPipelineOptions::kAudioSourcePipeline);

#if TARGET_OS_WIN32
    // We need to load dshowwrapper (H.264) or mfwrapper (H.265), but we do not know which one based on .mp4
    // extension, so intead we will load video decoder dynamically when qtdemux will signal video pad added.
    pOptions->SetStreamParser("qtdemux");
    if (!hasCompanionAudio) {
        pOptions->SetAudioDecoder("dshowwrapper");
    }
    return CreateAVPipeline(true, pVideoSink, pOptions, pElements, ppPipeline);
#elif TARGET_OS_MAC
    return ERROR_PLATFORM_UNSUPPORTED;
#elif TARGET_OS_LINUX
    pOptions->SetStreamParser("qtdemux")->SetVideoDecoder("avvideodecoder");
    if (!hasCompanionAudio) {
        pOptions->SetAudioDecoder("avaudiodecoder");
    }
    return CreateAVPipeline(false, pVideoSink, pOptions, pElements, ppPipeline);
#else
    return ERROR_PLATFORM_UNSUPPORTED;
#endif // TARGET_OS_WIN32
}

/**
 * CGstPipelineFactory::CreateMatroskaPipeline
 *
 * skia-fx: matroska / webm container. Stream parser is matroskademux
 * (compiled into gstreamer-lite via skiafx.matroska-conventions). The
 * video decoder is left null and selected dynamically in
 * CGstAVPlaybackPipeline::LoadDecoder once the demuxer announces its
 * caps — which is identical to how the MP4 path resolves H.264 vs
 * H.265 vs AV1.
 *
 * Audio decoder is preset to ffmpegwrapper. ffmpegwrapper exposes its
 * own audio caps surface (mp3/aac/opus/vorbis/flac/ac3/...) and falls
 * through to is-supported=FALSE when ffmpeg isn't loaded; in that case
 * the audio bin construction fails and the caller sees an AAC-style
 * error. Most matroska/webm audio is Opus or Vorbis which neither
 * dshowwrapper nor mfwrapper handles, so routing through ffmpeg is the
 * only viable default here.
 */
uint32_t CGstPipelineFactory::CreateMatroskaPipeline(GstElement* pVideoSink,
                                                     CPipelineOptions* pOptions,
                                                     GstElementContainer* pElements,
                                                     CPipeline** ppPipeline)
{
    // skia-fx: when a companion audio source is present
    // (Media(video, audio) dual-source), the audio bin built by
    // CreateAVPipeline is wired to the companion, NOT to the matroska
    // container's embedded audio (which my on_pad_added suppression
    // drops). The companion-specific audio decoder was picked by the
    // ext-sniff block in CreatePlayerPipeline (e.g. "dshowwrapper" for
    // a .m4a AAC companion). If we overwrite it here with
    // ffmpegwrapper, the companion's AAC ends up being decoded by
    // ffmpegwrapper's AAC path, which emits buffers with an unusual
    // size/PTS shape that downstream sinks read as 2x speed +
    // burst-and-gap playback. Only set ffmpegwrapper as the default
    // when there's no companion overriding our decision.
    const bool hasCompanionAudio =
        (pOptions->GetPipelineType() == CPipelineOptions::kAudioSourcePipeline);

#if TARGET_OS_WIN32
    pOptions->SetStreamParser("matroskademux");
    if (!hasCompanionAudio) {
        pOptions->SetAudioDecoder("ffmpegwrapper");
    }
    return CreateAVPipeline(true, pVideoSink, pOptions, pElements, ppPipeline);
#elif TARGET_OS_LINUX
    pOptions->SetStreamParser("matroskademux")
        ->SetVideoDecoder("avvideodecoder");
    if (!hasCompanionAudio) {
        pOptions->SetAudioDecoder("avaudiodecoder");
    }
    return CreateAVPipeline(false, pVideoSink, pOptions, pElements, ppPipeline);
#else
    return ERROR_PLATFORM_UNSUPPORTED;
#endif
}

/**
    *  GstElement* CreateMp3AudioPipeline(GstElement* source, char* audiodec_factory,
    *                                 char* audiosink)
    *
    *  @param  source              Pipeline source element; must not be NULL.
    *  @param  audiosink           The audio sink element; if NULL one will be created internally.
    *
    *  @return An audio playback pipeline.
    */

uint32_t CGstPipelineFactory::CreateMp3AudioPipeline(CPipelineOptions *pOptions, GstElementContainer* pElements, CPipeline** ppPipeline)
{
#if TARGET_OS_WIN32
    pOptions->SetStreamParser("mpegaudioparse")->SetAudioDecoder("dshowwrapper");
#elif TARGET_OS_MAC
    return ERROR_PLATFORM_UNSUPPORTED;
#elif TARGET_OS_LINUX
    pOptions->SetStreamParser("mpegaudioparse")->SetAudioDecoder("avaudiodecoder");
#else
    return ERROR_PLATFORM_UNSUPPORTED;
#endif // TARGET_OS_WIN32

    return CreateAudioPipeline(false, pOptions, pElements, ppPipeline);
}

uint32_t CGstPipelineFactory::CreateWavPcmAudioPipeline(CPipelineOptions *pOptions, GstElementContainer* pElements, CPipeline** ppPipeline)
{
    pOptions->SetStreamParser("wavparse");
    return CreateAudioPipeline(true, pOptions, pElements, ppPipeline);
}

uint32_t CGstPipelineFactory::CreateAiffPcmAudioPipeline(CPipelineOptions *pOptions, GstElementContainer* pElements, CPipeline** ppPipeline)
{
    pOptions->SetStreamParser("aiffparse");
    return CreateAudioPipeline(true, pOptions, pElements, ppPipeline);
}

uint32_t CGstPipelineFactory::CreateHLSPipeline(GstElement* pVideoSink, CPipelineOptions* pOptions, GstElementContainer* pElements, CPipeline** ppPipeline)
{
#if TARGET_OS_WIN32
    if (pOptions->GetPipelineType() == CPipelineOptions::kAudioSourcePipeline)
    {
        // For HLS streams with EXT-X-MEDIA first stream (video) is MP2T or FMP4
        if (pOptions->GetStreamMimeType() == HLS_VALUE_MIMETYPE_MP2T)
            pOptions->SetStreamParser("dshowwrapper")->SetVideoDecoder("dshowwrapper");
        else if (pOptions->GetStreamMimeType() == HLS_VALUE_MIMETYPE_FMP4)
            pOptions->SetStreamParser("qtdemux"); // Video decoder loaded dynamically
        else
            return ERROR_PLATFORM_UNSUPPORTED;

        // Audio stream can be FMP4 or AAC
        if (pOptions->GetAudioStreamMimeType() == HLS_VALUE_MIMETYPE_FMP4)
            pOptions->SetAudioStreamParser("qtdemux")->SetAudioDecoder("dshowwrapper");
        else if (pOptions->GetAudioStreamMimeType() == HLS_VALUE_MIMETYPE_AAC)
            pOptions->SetAudioDecoder("dshowwrapper");
        else
            return ERROR_PLATFORM_UNSUPPORTED;

        return CreateAVPipeline(true, pVideoSink, pOptions, pElements, ppPipeline);
    }
    else
    {
        if (pOptions->GetStreamMimeType() == HLS_VALUE_MIMETYPE_MP2T)
        {
            pOptions->SetStreamParser("dshowwrapper")->SetAudioDecoder("dshowwrapper")->SetVideoDecoder("dshowwrapper");
            return CreateAVPipeline(true, pVideoSink, pOptions, pElements, ppPipeline);
        }
        else if (pOptions->GetStreamMimeType() == HLS_VALUE_MIMETYPE_MP3)
        {
            pOptions->SetStreamParser("mpegaudioparse")->SetAudioDecoder("dshowwrapper");
            return CreateAudioPipeline(false, pOptions, pElements, ppPipeline);
        }
        else if (pOptions->GetStreamMimeType() == HLS_VALUE_MIMETYPE_AAC)
        {
            pOptions->SetAudioDecoder("dshowwrapper");
            return CreateAudioPipeline(false, pOptions, pElements, ppPipeline);
        }
        else if (pOptions->GetStreamMimeType() == HLS_VALUE_MIMETYPE_FMP4)
        {
            // Video decoder is loaded dynamically
            pOptions->SetStreamParser("qtdemux")->SetAudioDecoder("dshowwrapper");
            return CreateAVPipeline(true, pVideoSink, pOptions, pElements, ppPipeline);
        }
        else
        {
            return ERROR_PLATFORM_UNSUPPORTED;
        }
    }
#elif TARGET_OS_MAC
    return ERROR_PLATFORM_UNSUPPORTED;
#elif TARGET_OS_LINUX
    if (pOptions->GetPipelineType() == CPipelineOptions::kAudioSourcePipeline)
    {
        bool bConvertFormat = false;

        // For HLS streams with EXT-X-MEDIA first stream (video) is MP2T or FMP4
        if (pOptions->GetStreamMimeType() == HLS_VALUE_MIMETYPE_MP2T)
            pOptions->SetStreamParser("avmpegtsdemuxer")->SetVideoDecoder("avvideodecoder");
        else if (pOptions->GetStreamMimeType() == HLS_VALUE_MIMETYPE_FMP4)
            pOptions->SetStreamParser("qtdemux")->SetVideoDecoder("avvideodecoder");
        else
            return ERROR_PLATFORM_UNSUPPORTED;

        // Audio stream can be FMP4 or AAC
        if (pOptions->GetAudioStreamMimeType() == HLS_VALUE_MIMETYPE_FMP4)
        {
            pOptions->SetAudioStreamParser("qtdemux")->SetAudioDecoder("avaudiodecoder");
            bConvertFormat = true;
        }
        else if (pOptions->GetAudioStreamMimeType() == HLS_VALUE_MIMETYPE_AAC)
        {
            pOptions->SetAudioStreamParser("aacparse")->SetAudioDecoder("avaudiodecoder");
            bConvertFormat = false;
            //pOptions->SetAudioDecoder("avaudiodecoder");
        }
        else
            return ERROR_PLATFORM_UNSUPPORTED;

        return CreateAVPipeline(bConvertFormat, pVideoSink, pOptions, pElements, ppPipeline);
    }
    else
    {
        if (pOptions->GetStreamMimeType() == HLS_VALUE_MIMETYPE_MP2T)
        {
            pOptions->SetStreamParser("avmpegtsdemuxer")->SetAudioDecoder("avaudiodecoder")->SetVideoDecoder("avvideodecoder");
            return CreateAVPipeline(false, pVideoSink, pOptions, pElements, ppPipeline);
        }
        else if (pOptions->GetStreamMimeType() == HLS_VALUE_MIMETYPE_MP3)
        {
            pOptions->SetStreamParser("mpegaudioparse")->SetAudioDecoder("avaudiodecoder");
            return CreateAudioPipeline(false, pOptions, pElements, ppPipeline);
        }
        else if (pOptions->GetStreamMimeType() == HLS_VALUE_MIMETYPE_AAC)
        {
            pOptions->SetStreamParser("aacparse")->SetAudioDecoder("avaudiodecoder");
            return CreateAudioPipeline(false, pOptions, pElements, ppPipeline);
        }
        else if (pOptions->GetStreamMimeType() == HLS_VALUE_MIMETYPE_FMP4)
        {
            pOptions->SetStreamParser("qtdemux")->SetAudioDecoder("avaudiodecoder")->SetVideoDecoder("avvideodecoder");
            return CreateAVPipeline(true, pVideoSink, pOptions, pElements, ppPipeline);
        }
        else
        {
            return ERROR_PLATFORM_UNSUPPORTED;
        }
    }
#else
    return ERROR_PLATFORM_UNSUPPORTED;
#endif // TARGET_OS_WIN32
}

uint32_t CGstPipelineFactory::CreateAudioPipeline(bool bConvertFormat, CPipelineOptions *pOptions, GstElementContainer* pElements, CPipeline** ppPipeline)
{
    uint32_t uRetCode = ERROR_NONE;

    // All audio pipelines are single source for now
    GstElement* source = (*pElements)[SOURCE];
    if (NULL == source)
        return ERROR_FUNCTION_PARAM_NULL;

    GstElement *pipeline = gst_pipeline_new (NULL);
    if (NULL == pipeline)
        return ERROR_GSTREAMER_PIPELINE_CREATION;
    if(!gst_bin_add(GST_BIN (pipeline), source))
        return ERROR_GSTREAMER_BIN_ADD_ELEMENT;

    int flags = 0;
    GstElement* audiobin;
    uRetCode = CreateAudioBin(pOptions->GetStreamParser(),
                              pOptions->GetAudioDecoder(),
                              bConvertFormat, pElements, &flags, &audiobin);
    if (ERROR_NONE != uRetCode)
        return uRetCode;

    uRetCode = AttachToSource(GST_BIN (pipeline), source, NULL, audiobin);
    if (ERROR_NONE != uRetCode)
        return uRetCode;

    pElements->add(PIPELINE, pipeline);

    *ppPipeline = new CGstAudioPlaybackPipeline(*pElements, flags, pOptions);
    if (NULL == ppPipeline)
        uRetCode = ERROR_MEMORY_ALLOCATION;

    return uRetCode;
}

/**
 *  GstElement* CreateAVPipeline(GstElement* source, char* demux_factory,
 *                              char* audiodec_factory, char* videodec_factory,
 *                              GstElement* audiosink, GstElement* videosink)
 *
 *  @param  source                Pipeline source element; must not be NULL.
 *  @param  strDemultiplexerName  Name of the demuxer factory.
 *  @param  strAudioDecoderName   Name of the audio decoder factory.
 *  @param  bConvertFormat        Add or not an audioconverter.
 *  @param  strVideoDecoderName   Name of the video decoder factory.
 *  @param  videosink             The video sink element; if NULL one will be created internally.
 *  @param  pOptions              Diffferent pipeline options that come alone during creation process.
 *  @param  ppPipeline            Result.
 *
 *  @return An audio-visual playback pipeline.
 */
uint32_t CGstPipelineFactory::CreateAVPipeline(bool bConvertFormat, GstElement* pVideoSink,
                                               CPipelineOptions* pOptions, GstElementContainer* pElements,
                                               CPipeline** ppPipeline)
{
    uint32_t uRetCode = ERROR_NONE;
    bool bAudioStream = (pOptions->GetPipelineType() == CPipelineOptions::kAudioSourcePipeline);

    GstElement* source = (*pElements)[SOURCE];
    if (NULL == source)
        return ERROR_FUNCTION_PARAM_NULL;

    GstElement* audioSource = (*pElements)[AUDIO_SOURCE];
    if (bAudioStream && NULL == audioSource)
        return ERROR_FUNCTION_PARAM_NULL;

    // Create pipeline
    GstElement *pipeline = gst_pipeline_new(NULL);
    if (NULL == pipeline)
        return ERROR_GSTREAMER_PIPELINE_CREATION;

    // Add demuxer and attached it to source for video and audio stream or video only
    GstElement *demuxer = CreateElement(pOptions->GetStreamParser());
    if (NULL == demuxer)
        return ERROR_GSTREAMER_ELEMENT_CREATE;
    // Configure demuxer if needed. `disable-mp2t-pts-reset` is a
    // dshowwrapper-specific property (HLS MP2T dual-source). For a
    // matroska/webm dual-source companion the demuxer is matroskademux,
    // which has no such property — setting an absent property on the
    // bundled GLib faults (GValue type-0) rather than warning, so guard
    // on the property actually existing.
    if (bAudioStream &&
        g_object_class_find_property(G_OBJECT_GET_CLASS(demuxer),
                                     "disable-mp2t-pts-reset") != NULL) {
        g_object_set(demuxer, "disable-mp2t-pts-reset", TRUE, NULL);
    }
    if (!gst_bin_add (GST_BIN (pipeline), source))
        return ERROR_GSTREAMER_BIN_ADD_ELEMENT;
    uRetCode = AttachToSource(GST_BIN (pipeline), source, (*pElements)[SOURCE_BUFFER], demuxer);
    if (ERROR_NONE != uRetCode)
        return uRetCode;

    GstElement *audioDemuxer = NULL;
    if (audioSource)
    {
        if (!gst_bin_add (GST_BIN (pipeline), audioSource))
            return ERROR_GSTREAMER_BIN_ADD_ELEMENT;

        if (pOptions->GetAudioStreamParser() != NULL)
        {
            audioDemuxer = CreateElement(pOptions->GetAudioStreamParser());
            if (NULL == audioDemuxer)
                return ERROR_GSTREAMER_ELEMENT_CREATE;

            uRetCode = AttachToSource(GST_BIN (pipeline), audioSource, (*pElements)[AUDIO_SOURCE_BUFFER], audioDemuxer);
            if (ERROR_NONE != uRetCode)
                return uRetCode;
        }
    }

    int audioFlags = 0;
    GstElement *audiobin = NULL;
    uRetCode = CreateAudioBin(NULL, pOptions->GetAudioDecoder(), bConvertFormat,
                              pElements, &audioFlags, &audiobin);
    if (ERROR_NONE != uRetCode)
        return uRetCode;

    // Attach audio bin to audio source if we have one
    if (bAudioStream && audioDemuxer == NULL)
    {
        uRetCode = AttachToSource(GST_BIN (pipeline), audioSource, (*pElements)[AUDIO_SOURCE_BUFFER], audiobin);
        if (ERROR_NONE != uRetCode)
            return uRetCode;
    }
    else if (bAudioStream && audioDemuxer != NULL)
    {
        // Audio demuxer can have static or dynamic src pad.
        // If static then connect it here. For dynamic we
        // will connect it in GstAVPlaybackPipeline.
        GstPad *src_pad = gst_element_get_static_pad(audioDemuxer, "src");
        if (src_pad != NULL)
        {
            gst_object_unref(src_pad);
            if (!gst_bin_add(GST_BIN (pipeline), audiobin))
                return ERROR_GSTREAMER_BIN_ADD_ELEMENT;
            if (!gst_element_link(audioDemuxer, audiobin))
                return ERROR_GSTREAMER_ELEMENT_LINK;
        }
    }

    GstElement *videobin;
    uRetCode = CreateVideoBin(pOptions->GetVideoDecoder(), pVideoSink, pElements, &videobin);
    if (ERROR_NONE != uRetCode)
        return uRetCode;

    pElements->add(PIPELINE, pipeline);
    pElements->add(AV_DEMUXER, demuxer);
    if (audioDemuxer != NULL)
        pElements->add(AUDIO_PARSER, audioDemuxer);

    *ppPipeline = new CGstAVPlaybackPipeline(*pElements, audioFlags, pOptions);
    if( NULL == *ppPipeline)
        return ERROR_MEMORY_ALLOCATION;

    return uRetCode;
}

uint32_t CGstPipelineFactory::CreateAudioBin(const char* strParserName, const char* strDecoderName,
                                             bool bConvertFormat,
                                             GstElementContainer* elements, int* pFlags,
                                             GstElement** ppAudiobin)
{
    if ((NULL == strParserName && NULL == strDecoderName) || NULL == elements || NULL == pFlags || NULL == ppAudiobin)
        return ERROR_FUNCTION_PARAM_NULL;

    *ppAudiobin = gst_bin_new(NULL);
    if (NULL == *ppAudiobin)
        return ERROR_GSTREAMER_BIN_CREATE;

    GstElement* head = NULL;

    GstElement *audioparse = NULL;
    if (NULL != strParserName)
    {
        audioparse = CreateElement (strParserName);
        if (NULL == audioparse)
            return ERROR_MEDIA_AUDIO_FORMAT_UNSUPPORTED;
        if(!gst_bin_add(GST_BIN(*ppAudiobin), audioparse))
            return ERROR_GSTREAMER_BIN_ADD_ELEMENT;
        head = audioparse;
    }

    GstElement *audioqueue = CreateElement ("queue");
    if (NULL == audioqueue)
        return ERROR_GSTREAMER_ELEMENT_CREATE;
    // skia-fx: OPT-IN deeper encoded-audio queue so a brief stall in the
    // upstream feed (e.g. the companion remote source's download/decode
    // thread getting starved of CPU by 4K video render in dual-source
    // playback) doesn't drain the audio path and glitch the sink. OFF by
    // default (stock queue limits) — a knob for the still-pending remote
    // dual-source work. Set OPENJFX_MEDIA_AUDIO_QUEUE_TIME (seconds) to
    // enable; limits by time rather than buffer count.
    {
        const gchar* envAq = g_getenv("OPENJFX_MEDIA_AUDIO_QUEUE_TIME");
        if (envAq != NULL && *envAq != '\0')
        {
            gint64 v = g_ascii_strtoll(envAq, NULL, 10);
            if (v > 0 && v <= 60)
                g_object_set(audioqueue,
                             "max-size-time", (guint64)((guint64)v * GST_SECOND),
                             "max-size-buffers", (guint)0,
                             "max-size-bytes", (guint)0,
                             NULL);
        }
    }
    if (!gst_bin_add(GST_BIN(*ppAudiobin), audioqueue))
        return ERROR_GSTREAMER_BIN_ADD_ELEMENT;
    if (NULL != audioparse)
    {
        if (!gst_element_link(audioparse, audioqueue))
            return ERROR_GSTREAMER_ELEMENT_LINK_AUDIO_BIN;
    }

    GstElement* tail = audioqueue;
    if (NULL == head)
    {
        head = audioqueue;
    }

    GstElement *audiodec = NULL;
    if (NULL != strDecoderName)
    {
        audiodec = CreateElement (strDecoderName);
        if (NULL == audiodec)
            return ERROR_MEDIA_AUDIO_FORMAT_UNSUPPORTED;

        if (!gst_bin_add(GST_BIN(*ppAudiobin), audiodec))
            return ERROR_GSTREAMER_BIN_ADD_ELEMENT;
        if (!gst_element_link(audioqueue, audiodec))
            return ERROR_GSTREAMER_ELEMENT_LINK_AUDIO_BIN;
        tail = audiodec;
    }

    if (bConvertFormat)
    {
        GstElement *audioconv  = CreateElement ("audioconvert");
        if (!gst_bin_add(GST_BIN(*ppAudiobin), audioconv))
            return ERROR_GSTREAMER_BIN_ADD_ELEMENT;
        if (!gst_element_link(tail, audioconv))
            return ERROR_GSTREAMER_ELEMENT_LINK_AUDIO_BIN;
        tail = audioconv;
    }

    GstElement *audioequalizer = CreateElement ("equalizer-nbands");
    GstElement *audiospectrum = CreateElement ("spectrum");
    if (NULL == audioequalizer || NULL == audiospectrum)
        return ERROR_GSTREAMER_ELEMENT_CREATE;

    GstElement *audiosink  = CreateAudioSinkElement();
    if (NULL == audiosink)
        return ERROR_GSTREAMER_AUDIO_SINK_CREATE;

    // skia-fx: OPT-IN deepening of the audio sink's device ring buffer.
    // A larger ring buffer holds more decoded samples so the audio device
    // doesn't underrun when the audio sink thread is briefly starved of
    // CPU (4K video decode + Skia render in dual-source remote playback).
    // It is OFF by default because a large buffer-time adds A/V latency to
    // ALL playback (single-source too); it's a knob for the still-pending
    // remote dual-source audio work. Set OPENJFX_MEDIA_AUDIO_BUFFER_MS
    // (100..10000 ms) to enable. directsoundsink derives from
    // GstAudioBaseSink ("buffer-time"/"latency-time", microseconds).
    {
        const gchar* envBuf = g_getenv("OPENJFX_MEDIA_AUDIO_BUFFER_MS");
        if (envBuf != NULL && *envBuf != '\0')
        {
            gint64 bufMs = g_ascii_strtoll(envBuf, NULL, 10);
            if (bufMs >= 100 && bufMs <= 10000)
            {
                GObjectClass* sinkCls = G_OBJECT_GET_CLASS(audiosink);
                if (g_object_class_find_property(sinkCls, "buffer-time") != NULL)
                    g_object_set(audiosink, "buffer-time", (gint64)(bufMs * 1000), NULL);
                if (g_object_class_find_property(sinkCls, "latency-time") != NULL)
                    g_object_set(audiosink, "latency-time", (gint64)50000, NULL);
            }
        }
    }

    gst_bin_add_many(GST_BIN(*ppAudiobin), audioequalizer, audiospectrum, audiosink, NULL);
#if TARGET_OS_WIN32
    if (!gst_element_link_many (tail, audioequalizer, NULL))
        return ERROR_GSTREAMER_ELEMENT_LINK_AUDIO_BIN;
    tail = audioequalizer;
#else // TARGET_OS_WIN32
    GstElement *audiobal = CreateElement ("audiopanorama");
    if (!gst_bin_add(GST_BIN(*ppAudiobin), audiobal))
        return ERROR_GSTREAMER_BIN_ADD_ELEMENT;
    if (!gst_element_link_many (tail, audioequalizer, audiobal, NULL))
        return ERROR_GSTREAMER_ELEMENT_LINK_AUDIO_BIN;
    tail = audiobal;
#endif // TARGET_OS_WIN32


    // Add volume element exclusively for Linux. alsamixer sets the system volume.
    // Audiosinks on other platforms allow setting application only volume level.
#if TARGET_OS_LINUX
    GstElement *volume = CreateElement ("volume");
    if (!gst_bin_add(GST_BIN(*ppAudiobin), volume))
        return ERROR_GSTREAMER_BIN_ADD_ELEMENT;
    if (!gst_element_link_many (tail, volume, NULL))
        return ERROR_GSTREAMER_ELEMENT_LINK_AUDIO_BIN;
    tail = volume;
#endif

    if (!gst_element_link_many (tail, audiospectrum, audiosink, NULL))
        return ERROR_GSTREAMER_ELEMENT_LINK_AUDIO_BIN;

    GstPad *sink_pad = gst_element_get_static_pad(head, "sink");
    if (NULL == sink_pad)
        return ERROR_GSTREAMER_ELEMENT_GET_PAD;
    GstPad *ghost_pad = gst_ghost_pad_new("sink", sink_pad);
    if (NULL == ghost_pad)
        return ERROR_GSTREAMER_CREATE_GHOST_PAD;
    gst_element_add_pad(*ppAudiobin, ghost_pad);
    gst_object_unref(sink_pad);

    elements->add(AUDIO_BIN, *ppAudiobin).
        add(AUDIO_QUEUE, audioqueue).
        add(AUDIO_EQUALIZER, audioequalizer).
        add(AUDIO_SPECTRUM, audiospectrum).
#if TARGET_OS_WIN32
        add(AUDIO_BALANCE, audiosink).
#else // TARGET_OS_WIN32
        add(AUDIO_BALANCE, audiobal).
#endif // TARGET_OS_WIN32

#if TARGET_OS_LINUX
        add(AUDIO_VOLUME, volume).
#else // TARGET_OS_LINUX
        add(AUDIO_VOLUME, audiosink).
#endif // TARGET_OS_LINUX

        add(AUDIO_SINK, audiosink);

    if (NULL != audioparse)
        elements->add(AUDIO_PARSER, audioparse);

    if (NULL != audiodec)
    {
        elements->add(AUDIO_DECODER, audiodec);
        *pFlags |= AUDIO_DECODER_HAS_SOURCE_PROBE | AUDIO_DECODER_HAS_SINK_PROBE;
    }

    // Switch off limiting of the audioqueue for bytes and buffers.
    g_object_set(audioqueue, "max-size-bytes", (guint)0, "max-size-buffers", (guint)10, "max-size-time", (guint64)0, NULL);

    return ERROR_NONE;
}

uint32_t CGstPipelineFactory::CreateVideoBin(const char* strDecoderName, GstElement* pVideoSink,
                                             GstElementContainer* elements, GstElement** ppVideobin)
{
    *ppVideobin = gst_bin_new(NULL);
    if (NULL == *ppVideobin)
        return ERROR_GSTREAMER_BIN_CREATE;

    GstElement *videodec   = strDecoderName != NULL ? CreateElement (strDecoderName) : NULL;
    GstElement *videoqueue = CreateElement ("queue");
    if ((NULL != strDecoderName && NULL == videodec) || NULL == videoqueue)
        return ERROR_GSTREAMER_ELEMENT_CREATE;

    if(NULL == pVideoSink)
    {
        pVideoSink = CreateElement ("autovideosink");
        if (NULL == pVideoSink)
            return ERROR_GSTREAMER_VIDEO_SINK_CREATE;
    }

#if ENABLE_NATIVE_SINK || ENABLE_VIDEOCONVERT
    GstElement* videoconv = CreateElement ("ffmpegcolorspace");
    if (NULL == videoconv)
        return ERROR_GSTREAMER_ELEMENT_CREATE;

#if ENABLE_VIDEOCONVERT
    GstCaps* appSinkCaps = gst_caps_new_simple("video/x-raw-rgb",
            "bpp", G_TYPE_INT, 32,
            "depth", G_TYPE_INT, 32,
            "red_mask", G_TYPE_INT, 0x0000FF00,
            "green_mask", G_TYPE_INT, 0x00FF0000,
            "blue_mask", G_TYPE_INT, 0xFF000000,
            "alpha_mask", G_TYPE_INT, 0x000000FF,
            NULL);
    gst_app_sink_set_caps(GST_APP_SINK(pVideoSink), appSinkCaps);
#endif
    gst_bin_add_many (GST_BIN (*ppVideobin), videoqueue, videodec, videoconv, pVideoSink, NULL);
    if(!gst_element_link_many (videoqueue, videodec, videoconv, pVideoSink, NULL))
        return ERROR_GSTREAMER_ELEMENT_LINK_VIDEO_BIN;
#else
    if (videodec)
    {
        gst_bin_add_many(GST_BIN(*ppVideobin), videoqueue, videodec, pVideoSink, NULL);
        if (!gst_element_link_many(videoqueue, videodec, pVideoSink, NULL))
            return ERROR_GSTREAMER_ELEMENT_LINK_VIDEO_BIN;
    }
    else
    {
        gst_bin_add_many(GST_BIN(*ppVideobin), videoqueue, pVideoSink, NULL);
    }
#endif
    GstPad* sink_pad = gst_element_get_static_pad(videoqueue, "sink");
    if (NULL == sink_pad)
        return ERROR_GSTREAMER_ELEMENT_GET_PAD;

    GstPad* ghost_pad = gst_ghost_pad_new("sink", sink_pad);
    if (NULL == ghost_pad)
    {
        gst_object_unref(sink_pad);
        return ERROR_GSTREAMER_CREATE_GHOST_PAD;
    }
    if (!gst_element_add_pad(*ppVideobin, ghost_pad))
    {
        gst_object_unref(sink_pad);
        return ERROR_GSTREAMER_ELEMENT_ADD_PAD;
    }
    gst_object_unref(sink_pad);

    elements->add(VIDEO_BIN, *ppVideobin).
    add(VIDEO_QUEUE, videoqueue).
    add(VIDEO_DECODER, videodec).
    add(VIDEO_SINK, pVideoSink);

    // Switch off limiting of the videoqueue for bytes and buffers.
    g_object_set(videoqueue, "max-size-bytes", (guint)0, "max-size-buffers", (guint)10, "max-size-time", (guint64)0, NULL);
    g_object_set(pVideoSink, "qos", TRUE, NULL);

    return ERROR_NONE;
}

GstElement* CGstPipelineFactory::CreateElement(const char* strFactoryName)
{
    if (strFactoryName == NULL)
        return NULL;

    return gst_element_factory_make (strFactoryName, NULL);
}

GstElement* CGstPipelineFactory::GetByFactoryName(GstElement* bin, const char* strFactoryName)
{
    if (!GST_IS_BIN(bin))
        return NULL;

    GstIterator *it = gst_bin_iterate_elements(GST_BIN(bin));
    GValue item = { 0, };
    GstElement  *element = NULL;
    gboolean    done = FALSE;
    while (!done)
    {
        switch (gst_iterator_next (it, &item))
        {
            case GST_ITERATOR_OK:
            {
                element = (GstElement*)g_value_get_object(&item);
                GstElementFactory* factory = gst_element_get_factory(element);
                if (g_str_has_prefix(GST_OBJECT_NAME(factory), strFactoryName))
                {
                    done = TRUE;
                }
                else
                {
                    g_value_reset(&item);
                    element = NULL;
                }
                break;
            }
            case GST_ITERATOR_RESYNC:
                gst_iterator_resync (it);
                break;

            case GST_ITERATOR_ERROR:
            case GST_ITERATOR_DONE:
                done = TRUE;
                break;
        }
    }
    g_value_unset(&item);
    gst_iterator_free (it);

    return element ? (GstElement*)gst_object_ref(element) : NULL;
}
