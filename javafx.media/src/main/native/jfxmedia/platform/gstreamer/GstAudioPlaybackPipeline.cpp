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

#include "GstAudioPlaybackPipeline.h"
#include "GstMediaManager.h"
#include <MediaManagement/MediaTypes.h>
#include <PipelineManagement/AudioTrack.h>
#include <PipelineManagement/PlayerEventDispatcher.h>
#include <MediaManagement/Media.h>
#include <Common/VSMemory.h>
#include <Common/LeakCounter.h>
#include <Utils/LowLevelPerf.h>
#include <jni/Logger.h>
#include <fxplugins_common.h>

#include <string.h>

#define AUDIO_RESUME_DELTA_TIME   10.0 // seconds
#define VIDEO_RESUME_DELTA_TIME   10.0 // seconds
#define STALL_DELTA_TIME           1.0 // seconds

// skia-fx: cached OPENJFX_MEDIA_VERBOSE gate — GetStreamTime and the
// bus callback are hot paths; never call g_getenv there per call.
static gboolean _media_verbose(void)
{
    static gsize once = 0;
    static gboolean on = FALSE;
    if (g_once_init_enter(&once))
    {
        const char* v = g_getenv("OPENJFX_MEDIA_VERBOSE");
        on = (v != NULL && v[0] != '\0' && v[0] != '0');
        g_once_init_leave(&once, 1);
    }
    return on;
}

//*************************************************************************************************
//********** class CGstAudioPlaybackPipeline
//*************************************************************************************************

/**
 * CGstAudioPlaybackPipeline::CGstAudioPlaybackPipeline()
 *
 * Constructor
 *
 * @param   elements    GStreamer container of elements
 */
CGstAudioPlaybackPipeline::CGstAudioPlaybackPipeline(const GstElementContainer& elements, int flags, CPipelineOptions* pOptions)
:   CPipeline(pOptions),
    m_Elements(elements),
    m_pAudioEqualizer(NULL),
    m_pAudioSpectrum(NULL),
    m_AudioFlags(flags)
{
    m_dResumeDeltaTime = m_Elements[VIDEO_SINK] ? VIDEO_RESUME_DELTA_TIME : AUDIO_RESUME_DELTA_TIME;

    m_bSeekInvoked = false;
    m_fRate = 1.0F;
    m_audioSourcePadProbeHID = 0L;
    m_ulLastStreamTime = (GstClockTime)0UL;
    m_pBusSource = NULL;
    m_bIgnoreError = FALSE;

    m_StallLock = CJfxCriticalSection::Create();
    m_BufferPosition = 0.0;
    m_bHLSPBFull = false;
    m_StallOnPause = false;

    m_SeekLock = CJfxCriticalSection::Create();
    m_LastSeekTime = -1;

    m_dLastReportedDuration = DURATION_UNKNOWN;

    m_bSetClock = false;
    m_bIsClockSet = false;
    m_bDualSourceInitialResyncDone = false;
    m_DualResyncZeroPosPolls = 0;
    m_DualResyncFirstZeroPosTime = 0;

    m_StateLock = CJfxCriticalSection::Create();

#if ENABLE_PROGRESS_BUFFER
    m_llLastProgressValueStart = 0;
    m_llLastProgressValuePosition = 0;
    m_llLastProgressValueStop = 0;
    m_bLastProgressValueEOS = 0;
    m_llAudioProgressValuePosition = -1;
    m_llAudioProgressValueStop = -1;
    m_bAudioProgressEOS = FALSE;
#endif // ENABLE_PROGRESS_BUFFER

    m_audioCodecErrorCode = ERROR_NONE;

    m_pBusCallbackContent = NULL;

    m_WatchdogThread = NULL;
    m_bWatchdogStop = false;
    m_WatchdogTimeoutSec = 0;
    g_mutex_init(&m_WatchdogMutex);
    g_cond_init(&m_WatchdogCond);

    m_SeekCoalesceThread = NULL;
    m_bSeekCoalesceStop = false;
    m_bSeekCoalescePending = false;
    m_SeekCoalesceTargetSec = 0.0;
    m_SeekCoalesceLastReqUs = 0;
    m_SeekDebounceMs = 180;
    g_mutex_init(&m_SeekCoalesceMutex);
    g_cond_init(&m_SeekCoalesceCond);

    SKIAFX_LEAK_CREATED("CGstAudioPlaybackPipeline");
}

/**
 * CGstAudioPlaybackPipeline::~CGstAudioPlaybackPipeline()
 *
 * Destructor
 */
CGstAudioPlaybackPipeline::~CGstAudioPlaybackPipeline()
{
#if JFXMEDIA_DEBUG
    g_print ("CGstAudioPlaybackPipeline::~CGstAudioPlaybackPipeline()\n");
#endif
    StopWatchdog(); // no-op if Dispose already stopped it
    g_mutex_clear(&m_WatchdogMutex);
    g_cond_clear(&m_WatchdogCond);

    StopSeekCoalescer(); // no-op if Dispose already stopped it
    g_mutex_clear(&m_SeekCoalesceMutex);
    g_cond_clear(&m_SeekCoalesceCond);

    delete m_SeekLock;
    delete m_StateLock;
    delete m_StallLock;

    SKIAFX_LEAK_DESTROYED("CGstAudioPlaybackPipeline");
}

/**
 * CGstAudioPlaybackPipeline::Init()
 *
 * Init an audio-only playback pipeline.  Called by JNI layer.
 */
uint32_t CGstAudioPlaybackPipeline::Init()
{
    bool bStaticDecoderBin = false;

    m_pAudioEqualizer = new (nothrow) CGstAudioEqualizer(m_Elements[AUDIO_EQUALIZER]);
    if (m_pAudioEqualizer == NULL)
        return ERROR_MEMORY_ALLOCATION;

    m_pAudioSpectrum = new (nothrow) CGstAudioSpectrum(m_Elements[AUDIO_SPECTRUM], false);
    if (m_pAudioSpectrum == NULL)
        return ERROR_MEMORY_ALLOCATION;

    if (m_pOptions->GetBufferingEnabled())
        m_bStaticPipeline = false; // Pipeline is dynamic if we have progress buffer

    CMediaManager *pManager = NULL;
    uint32_t ret = CMediaManager::GetInstance(&pManager);
    if (ret != ERROR_NONE)
        return ret;

    m_pBusCallbackContent = new (nothrow) sBusCallbackContent;
    if (m_pBusCallbackContent == NULL)
        return ERROR_MEMORY_ALLOCATION;

    m_pBusCallbackContent->m_pPipeline = this;
    m_pBusCallbackContent->m_DisposeLock = CJfxCriticalSection::Create();
    m_pBusCallbackContent->m_bIsDisposed = false;
    m_pBusCallbackContent->m_bIsDisposeInProgress = false;
    m_pBusCallbackContent->m_bFreeMe = false;

    GstBus *pBus = gst_pipeline_get_bus (GST_PIPELINE (m_Elements[PIPELINE]));
    m_pBusSource = gst_bus_create_watch(pBus);
    if (m_pBusSource == NULL)
        return ERROR_MEMORY_ALLOCATION;

    g_source_set_callback(m_pBusSource, (GSourceFunc)BusCallback, m_pBusCallbackContent, (GDestroyNotify)BusCallbackDestroyNotify);

    ret = g_source_attach(m_pBusSource, ((CGstMediaManager*)pManager)->m_pMainContext);
    gst_object_unref (pBus);

    if (ret == 0)
    {
        delete m_pBusCallbackContent;
        return ERROR_GSTREAMER_BUS_SOURCE_ATTACH;
    }

    ((CGstMediaManager*)pManager)->StartMainLoop();

    // Check if we have static pipeline
#if TARGET_OS_LINUX | TARGET_OS_MAC | TARGET_OS_WIN32
    if (m_pOptions->GetPipelineType() == CPipelineOptions::kAudioSourcePipeline)
    {
        // For pipeline with separate audio source we need to check AUDIO_PARSER.
        // If it is not set, then audio is static, otherwise we need to check if
        // AUDIO_PARSER has static src pad or not. If it has static src pad, then
        // audio is static, otherwise AVPipeline will handle dynamic audio parser.
        // AV_DEMUXER is video only.
        if (m_Elements[AUDIO_PARSER] == NULL)
        {
            bStaticDecoderBin = true;
        }
        else
        {
            GstPad *src_pad = gst_element_get_static_pad(m_Elements[AUDIO_PARSER], "src");
            if (src_pad != NULL)
            {
                bStaticDecoderBin = true;
                gst_object_unref(src_pad);
            }
        }
    }
    else
    {
        if (m_Elements[AV_DEMUXER] == NULL)
            bStaticDecoderBin = true;
    }
#else // TARGET_OS_LINUX | TARGET_OS_MAC | TARGET_OS_WIN32
    if (m_Elements[AUDIO_PARSER] == NULL && m_Elements[AV_DEMUXER] == NULL)
        bStaticDecoderBin = true;
#endif // TARGET_OS_LINUX | TARGET_OS_MAC | TARGET_OS_WIN32

    if (bStaticDecoderBin)
    {
        m_bHasAudio = true;
        PostBuildInit();
    }
    else
    {
        if (m_Elements[AUDIO_PARSER] && m_pOptions->GetPipelineType() != CPipelineOptions::kAudioSourcePipeline) // Add method to link parser to decoder.
            g_signal_connect (m_Elements[AUDIO_PARSER], "pad-added", G_CALLBACK (OnParserSrcPadAdded), this);
    }

    // Switch the state
    if (GST_STATE_CHANGE_FAILURE == gst_element_set_state (m_Elements[PIPELINE], GST_STATE_PAUSED))
        return ERROR_GSTREAMER_PIPELINE_STATE_CHANGE;

    StartWatchdog();
    StartSeekCoalescer();

    return ERROR_NONE;
}

/**
 * skia-fx: stall/preroll watchdog. See the header comment. The thread
 * wakes every 2 s and samples a "progress signature" — download
 * progress (primary + companion) and the last observed stream time.
 * While the player is Stalled or stuck pre-Ready AND the signature is
 * frozen, a countdown runs; when it crosses the timeout, a standard
 * GST_MESSAGE_ERROR is posted on the bus (delivered to the app as a
 * MediaException by the existing BusCallback path) and the watchdog
 * exits. Any progress, or any non-monitored state, resets the clock.
 */
void CGstAudioPlaybackPipeline::StartWatchdog()
{
    int timeoutSec = 45;
    const gchar* env = g_getenv("OPENJFX_MEDIA_STALL_TIMEOUT");
    if (env != NULL && *env != '\0')
    {
        gint64 v = g_ascii_strtoll(env, NULL, 10);
        timeoutSec = (v > 0 && v <= 3600) ? (int)v : 0;
    }
    if (timeoutSec <= 0 || m_WatchdogThread != NULL)
        return;

    m_WatchdogTimeoutSec = timeoutSec;
    m_bWatchdogStop = false;
    m_WatchdogThread = g_thread_new("media-watchdog", WatchdogLoop, this);
}

void CGstAudioPlaybackPipeline::StopWatchdog()
{
    if (m_WatchdogThread == NULL)
        return;

    g_mutex_lock(&m_WatchdogMutex);
    m_bWatchdogStop = true;
    g_cond_broadcast(&m_WatchdogCond);
    g_mutex_unlock(&m_WatchdogMutex);

    g_thread_join(m_WatchdogThread);
    m_WatchdogThread = NULL;
}

// skia-fx: seek coalescing is enabled for dual-source A/V (the expensive,
// fragment-refetching path that a drag-storm hurts most). Single-source and
// audio-only keep the immediate synchronous seek. OPENJFX_MEDIA_SEEK_DEBOUNCE_MS
// overrides the window (0 disables coalescing entirely).
bool CGstAudioPlaybackPipeline::SeekCoalescingEnabled()
{
    return m_SeekCoalesceThread != NULL && m_SeekDebounceMs > 0;
}

void CGstAudioPlaybackPipeline::StartSeekCoalescer()
{
    if (m_SeekCoalesceThread != NULL)
        return;
    // Only dual-source A/V benefits; leave other pipelines on the immediate path.
    if (m_pOptions == NULL ||
        m_pOptions->GetPipelineType() != CPipelineOptions::kAudioSourcePipeline)
        return;

    int debounceMs = 180;
    const gchar* env = g_getenv("OPENJFX_MEDIA_SEEK_DEBOUNCE_MS");
    if (env != NULL && *env != '\0')
    {
        gint64 v = g_ascii_strtoll(env, NULL, 10);
        debounceMs = (v >= 0 && v <= 5000) ? (int)v : 180;
    }
    m_SeekDebounceMs = debounceMs;
    if (debounceMs <= 0)
        return; // explicitly disabled

    m_bSeekCoalesceStop = false;
    m_bSeekCoalescePending = false;
    m_SeekCoalesceThread = g_thread_new("media-seek-coalesce", SeekCoalesceLoop, this);
}

void CGstAudioPlaybackPipeline::StopSeekCoalescer()
{
    if (m_SeekCoalesceThread == NULL)
        return;

    g_mutex_lock(&m_SeekCoalesceMutex);
    m_bSeekCoalesceStop = true;
    g_cond_broadcast(&m_SeekCoalesceCond);
    g_mutex_unlock(&m_SeekCoalesceMutex);

    g_thread_join(m_SeekCoalesceThread);
    m_SeekCoalesceThread = NULL;
}

gpointer CGstAudioPlaybackPipeline::SeekCoalesceLoop(gpointer data)
{
    CGstAudioPlaybackPipeline* p = (CGstAudioPlaybackPipeline*)data;

    g_mutex_lock(&p->m_SeekCoalesceMutex);
    while (!p->m_bSeekCoalesceStop)
    {
        if (!p->m_bSeekCoalescePending)
        {
            g_cond_wait(&p->m_SeekCoalesceCond, &p->m_SeekCoalesceMutex);
            continue;
        }

        gint64 nowUs = g_get_monotonic_time();
        gint64 dueUs = p->m_SeekCoalesceLastReqUs +
                       (gint64)p->m_SeekDebounceMs * 1000;
        if (nowUs >= dueUs)
        {
            // The drag has gone quiet for the debounce window — execute the
            // latest target only.
            double target = p->m_SeekCoalesceTargetSec;
            p->m_bSeekCoalescePending = false;
            g_mutex_unlock(&p->m_SeekCoalesceMutex);

            if (_media_verbose())
                g_print("[seek-coalesce] firing target=%.3fs\n", target);
            p->DoSeekNow(target);

            g_mutex_lock(&p->m_SeekCoalesceMutex);
        }
        else
        {
            // A newer request reset the timer; wait until it's due (or a
            // newer one bumps it again / stop is requested).
            g_cond_wait_until(&p->m_SeekCoalesceCond, &p->m_SeekCoalesceMutex, dueUs);
        }
    }
    g_mutex_unlock(&p->m_SeekCoalesceMutex);
    return NULL;
}

gpointer CGstAudioPlaybackPipeline::WatchdogLoop(gpointer data)
{
    CGstAudioPlaybackPipeline* p = (CGstAudioPlaybackPipeline*)data;
    const gint64 timeoutUs = (gint64)p->m_WatchdogTimeoutSec * G_USEC_PER_SEC;
    gint64 lastChange = g_get_monotonic_time();
    gint64 lastSig = G_MININT64;

    g_mutex_lock(&p->m_WatchdogMutex);
    while (!p->m_bWatchdogStop)
    {
        // skia-fx: 1 s tick so the soft video-recovery WatchdogTick reacts
        // quickly to a frozen video chain. The hard-stall timeout below is
        // wall-clock based (now - lastChange), so a faster tick does not
        // change its semantics.
        g_cond_wait_until(&p->m_WatchdogCond, &p->m_WatchdogMutex,
                          g_get_monotonic_time() + 1 * G_USEC_PER_SEC);
        if (p->m_bWatchdogStop)
            break;
        g_mutex_unlock(&p->m_WatchdogMutex);

        // skia-fx: soft per-subclass recovery (e.g. re-seek a stalled video
        // chain onto the playing audio) before the hard stall logic below.
        p->WatchdogTick();

        // Liveness signature. Unsynchronized reads are fine here: a torn
        // or stale value at worst delays detection by one 2 s tick.
        gint64 sig = (gint64)p->m_ulLastStreamTime;
#if ENABLE_PROGRESS_BUFFER
        sig = sig * 31 + p->m_llLastProgressValuePosition;
        sig = sig * 31 + p->m_llAudioProgressValuePosition;
#endif
        // Monitored states: Stalled (buffering starvation) and the
        // pre-Ready window (preroll). Everything else — Playing, Paused,
        // Ready, Finished, Error — is the app's business, not a hang.
        bool monitored = p->IsPlayerState(Stalled) || p->IsPlayerState(Unknown);

        gint64 now = g_get_monotonic_time();
        if (!monitored || sig != lastSig)
        {
            lastSig = sig;
            lastChange = now;
        }
        else if (now - lastChange >= timeoutUs)
        {
            bool preroll = p->IsPlayerState(Unknown);
            gst_element_message_full(GST_ELEMENT(p->m_Elements[PIPELINE]),
                GST_MESSAGE_ERROR, GST_STREAM_ERROR, GST_STREAM_ERROR_FAILED,
                g_strdup_printf("Media pipeline made no progress for %d seconds (%s)",
                    p->m_WatchdogTimeoutSec,
                    preroll ? "stuck in preroll" : "stalled - source starved"),
                NULL,
                ("GstAudioPlaybackPipeline.cpp"), ("WatchdogLoop"), 0);
            // One-shot: the pipeline transitions to Error; nothing left
            // to watch.
            return NULL;
        }

        g_mutex_lock(&p->m_WatchdogMutex);
    }
    g_mutex_unlock(&p->m_WatchdogMutex);
    return NULL;
}

uint32_t CGstAudioPlaybackPipeline::PostBuildInit()
{
    if (m_bHasAudio && !m_bAudioInitDone)
    {
        bool bUseAudioDecoder = true;

        if (m_Elements[AUDIO_PARSER])
        {
            // Audio parser might not have static src pad and in this case use audio decoder.
            GstPad *pPad = gst_element_get_static_pad(m_Elements[AUDIO_PARSER], "src");
            if (NULL != pPad)
            {
                m_audioSourcePadProbeHID = gst_pad_add_probe(pPad, GST_PAD_PROBE_TYPE_BUFFER, (GstPadProbeCallback)AudioSourcePadProbe, this, NULL);
                gst_object_unref(pPad);
                bUseAudioDecoder = false; // No need to use audio decoder for AudioTrack info.
            }
        }

        if (bUseAudioDecoder && m_Elements[AUDIO_DECODER])
        {
            if (m_AudioFlags & AUDIO_DECODER_HAS_SINK_PROBE) // Add a buffer probe on the sink pad of the decoder
            {
                GstPad *pPad = gst_element_get_static_pad(m_Elements[AUDIO_DECODER], "sink");
                if (NULL == pPad)
                    return ERROR_GSTREAMER_AUDIO_DECODER_SINK_PAD;
                m_audioSinkPadProbeHID = gst_pad_add_probe(pPad, GST_PAD_PROBE_TYPE_BUFFER, (GstPadProbeCallback)AudioSinkPadProbe, this, NULL);
                gst_object_unref(pPad);
            }

            if (m_AudioFlags & AUDIO_DECODER_HAS_SOURCE_PROBE) // Add a buffer probe on the source pad of the decoder
            {
                GstPad *pPad = gst_element_get_static_pad(m_Elements[AUDIO_DECODER], "src");
                if (NULL == pPad)
                    return ERROR_GSTREAMER_AUDIO_DECODER_SRC_PAD;
                m_audioSourcePadProbeHID = gst_pad_add_probe(pPad, GST_PAD_PROBE_TYPE_BUFFER, (GstPadProbeCallback)AudioSourcePadProbe, this, NULL);
                gst_object_unref(pPad);
            }
        }

        m_bAudioInitDone = true;
    }

    return ERROR_NONE;
}

/**
 * CGstAudioPlaybackPipeline::OnParserSrcPadAdded()
 *
 * Links the parser source pad to the decoder sink pad and adds a buffer probe to
 * the parser source pad.
 *
 * @param element   The audio parser element.
 * @param pad       The audio parser source pad.
 * @param pPipeline A pointer to the audio pipeline.
 */
void CGstAudioPlaybackPipeline::OnParserSrcPadAdded(GstElement *element, GstPad *pad,
                                                    CGstAudioPlaybackPipeline* pPipeline)
{
    pPipeline->m_pBusCallbackContent->m_DisposeLock->Enter();

    if (pPipeline->m_pBusCallbackContent->m_bIsDisposeInProgress)
    {
        pPipeline->m_pBusCallbackContent->m_DisposeLock->Exit();
        return;
    }

    GstCaps *pCaps = gst_pad_get_current_caps(pad);

    if (pPipeline->IsCodecSupported(pCaps))
    {
        if (!gst_bin_add (GST_BIN (pPipeline->m_Elements[PIPELINE]), pPipeline->m_Elements[AUDIO_BIN]))
        {
            GTimeVal now;
            g_get_current_time (&now);

            if (NULL != pPipeline->m_pEventDispatcher)
            {
                if (!pPipeline->m_pEventDispatcher->SendPlayerHaltEvent ("Failed to add audio bin to pipeline!", (double)GST_TIMEVAL_TO_TIME (now)))
                {
                    if(!pPipeline->m_pEventDispatcher->SendPlayerMediaErrorEvent(ERROR_JNI_SEND_PLAYER_HALT_EVENT))
                    {
                        LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
                    }
                }
            }
        }

        gst_element_set_state(pPipeline->m_Elements[AUDIO_BIN], GST_STATE_READY);

        // Get the audio decoder sink pad.
        GstPad *peerPad = gst_element_get_static_pad(pPipeline->m_Elements[AUDIO_BIN], "sink");
        if (NULL == peerPad)
        {
            GTimeVal now;
            g_get_current_time (&now);

            if (NULL != pPipeline->m_pEventDispatcher)
            {
                if (!pPipeline->m_pEventDispatcher->SendPlayerHaltEvent ("Failed to retrieve audio bin sink pad!", (double)GST_TIMEVAL_TO_TIME (now)))
                {
                    if(!pPipeline->m_pEventDispatcher->SendPlayerMediaErrorEvent(ERROR_JNI_SEND_PLAYER_HALT_EVENT))
                    {
                        LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
                    }
                }
            }
        }

        // Link the audio parser src pad to the audio decode sink pad.
        if (GST_PAD_LINK_OK != gst_pad_link (pad, peerPad))
        {
            GTimeVal now;
            g_get_current_time (&now);

            if (NULL != pPipeline->m_pEventDispatcher)
            {
                if (!pPipeline->m_pEventDispatcher->SendPlayerHaltEvent ("Failed to link audio parser with audio bin!\n", (double)GST_TIMEVAL_TO_TIME (now)))
                {
                    if(!pPipeline->m_pEventDispatcher->SendPlayerMediaErrorEvent(ERROR_JNI_SEND_PLAYER_HALT_EVENT))
                    {
                        LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
                    }
                }
            }
        }

        if (peerPad != NULL)
        {
            gst_object_unref(peerPad);
            peerPad = NULL;
        }

        pPipeline->m_bHasAudio = true;
        pPipeline->PostBuildInit();

        if (!gst_element_sync_state_with_parent(pPipeline->m_Elements[AUDIO_BIN]))
        {
            GTimeVal now;
            g_get_current_time (&now);

            if (NULL != pPipeline->m_pEventDispatcher)
            {
                if (!pPipeline->m_pEventDispatcher->SendPlayerHaltEvent ("Failed to start audio bin!\n", (double)GST_TIMEVAL_TO_TIME (now)))
                {
                    if(!pPipeline->m_pEventDispatcher->SendPlayerMediaErrorEvent(ERROR_JNI_SEND_PLAYER_HALT_EVENT))
                    {
                        LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
                    }
                }
            }
        }
    }

    if (pCaps != NULL)
        gst_caps_unref(pCaps);

    // Disconnect this method from the "pad-added" signal of the audio parser.
    g_signal_handlers_disconnect_by_func(element, (void*)OnParserSrcPadAdded, pPipeline);

    pPipeline->CheckCodecSupport();

    pPipeline->m_pBusCallbackContent->m_DisposeLock->Exit();
}

/**
 * CGstAudioPlaybackPipeline::Dispose()
 *
 * Disposes of resources held by this object. The pipeline should not be used
 * once this method has been invoked.
 */
void CGstAudioPlaybackPipeline::Dispose()
{
#if JFXMEDIA_DEBUG
    g_print ("CGstAudioPlaybackPipeline::Dispose()\n");
#endif

    // The watchdog posts on the pipeline's bus — join it before any
    // pipeline teardown.
    StopWatchdog();
    // The coalescer can call SeekPipeline — join it before teardown too.
    StopSeekCoalescer();

    if (m_pBusCallbackContent != NULL)
    {
        m_pBusCallbackContent->m_DisposeLock->Enter();
        m_pBusCallbackContent->m_bIsDisposeInProgress = true;
        m_pBusCallbackContent->m_DisposeLock->Exit();
    }

    // Stop pipeline before lock, so all callbacks from pipeline are finished.
    if (m_Elements[PIPELINE])
    {
        gst_element_set_state (m_Elements[PIPELINE], GST_STATE_NULL); // Ignore return value.
    }

    if (m_pBusCallbackContent != NULL)
    {
        m_pBusCallbackContent->m_DisposeLock->Enter();

        if (m_pBusCallbackContent->m_bIsDisposed)
        {
            m_pBusCallbackContent->m_DisposeLock->Exit();
            return;
        }
    }

    if (m_pAudioEqualizer != NULL)
    {
        delete m_pAudioEqualizer;
        m_pAudioEqualizer = NULL;
    }

    if (m_pAudioSpectrum != NULL)
    {
        delete m_pAudioSpectrum;
        m_pAudioSpectrum = NULL;
    }

    // Destroy the pipeline. This should be done after any other cleanup to
    // avert any unexpected contention.
    if (m_Elements[PIPELINE])
    {
        if (m_pBusSource)
        {
            g_source_destroy(m_pBusSource);
            g_source_unref(m_pBusSource);
            m_pBusSource = NULL;
        }

        gst_object_unref (m_Elements[PIPELINE]);
    }

    if (m_pBusCallbackContent != NULL)
    {
        bool bFreeBusCallbackContent = m_pBusCallbackContent->m_bFreeMe;

        m_pBusCallbackContent->m_bIsDisposed = true;

        m_pBusCallbackContent->m_DisposeLock->Exit();

        if (bFreeBusCallbackContent)
        {
            delete m_pBusCallbackContent->m_DisposeLock;
            delete m_pBusCallbackContent;
        }
    }
}

/**
 * CGstAudioPlaybackPipeline::Play()
 *
 * Starts the playback of the media.
 */
uint32_t CGstAudioPlaybackPipeline::Play()
{
    LOWLEVELPERF_EXECTIMESTART("GST_STATE_PLAYING");

    m_StateLock->Enter();
    bool ready = (Finished != m_PlayerState && Error != m_PlayerState && Playing != m_PlayerState);
    if (!ready && Playing == m_PlayerState) // Re-check if we ready with pipeline
    {
        GstState state = GST_STATE_NULL;
        GstState pending = GST_STATE_VOID_PENDING;
        if (gst_element_get_state(m_Elements[PIPELINE], &state, &pending, 0) != GST_STATE_CHANGE_FAILURE)
        {
            if (state == GST_STATE_PAUSED || pending == GST_STATE_PAUSED)
                ready = true;
        }
    }
    m_StateLock->Exit();

    uint32_t ret = ERROR_NONE;
    if (ready)
    {
        if (0.0F == m_fRate)
            // Set playback resumption flag regardless of whether state change succeeds.
            m_bResumePlayOnNonzeroRate = true;
        else if (GST_STATE_CHANGE_FAILURE == gst_element_set_state(m_Elements[PIPELINE], GST_STATE_PLAYING))
               ret = ERROR_GSTREAMER_PIPELINE_STATE_CHANGE;
    }

    return ret;
}

/**
 * CGstAudioPlaybackPipeline::Stop()
 *
 * Stops the playback of the media. It will not reset stream position.
 */
uint32_t CGstAudioPlaybackPipeline::Stop()
{
    if (IsPlayerState(Stopped) || IsPlayerState(Error))
        return ERROR_NONE;

    if (0.0F == m_fRate)
        // Unset playback resumption flag regardless of whether state change succeeds.
        m_bResumePlayOnNonzeroRate = false;
    else
    {
        // Pause playback and seek to beginning of media.
        m_StateLock->Enter();
        m_PlayerPendingState = Stopped;
        m_StateLock->Exit();

        uint32_t uErrCode = InternalPause();
        if (ERROR_NONE != uErrCode)
        {
            m_StateLock->Enter();
            m_PlayerPendingState = Unknown;
            m_StateLock->Exit();
            return uErrCode;
        }
    }

    return ERROR_NONE;
}

/**
 * CGstAudioPlaybackPipeline::Finish()
 *
 * Finishs the playback of the media.
 */
uint32_t CGstAudioPlaybackPipeline::Finish()
{
    uint32_t ret = ERROR_NONE;

    if (IsPlayerState(Finished) || IsPlayerState(Error) || !IsPlayerState(Playing))
        return ERROR_NONE;

    ret = InternalPause();

    return ret;
}

/**
 * CGstAudioPlaybackPipeline::Pause()
 *
 * Pause the playback of the media
 */
uint32_t CGstAudioPlaybackPipeline::Pause()
{
    uint32_t ret = ERROR_NONE;

    if (IsPlayerState(Paused) || IsPlayerState(Error))
        return ERROR_NONE;

    // Check if we really need to pause
    m_StateLock->Enter();
    if (Stopped == m_PlayerState || Stalled == m_PlayerState)
    {
        SetPlayerState(Paused, false);
        m_StateLock->Exit();
        return ERROR_NONE;
    }
    m_PlayerPendingState = Paused;
    m_StateLock->Exit();

    ret = InternalPause();
    if (ret != ERROR_NONE)
    {
        m_StateLock->Enter();
        m_PlayerPendingState = Unknown;
        m_StateLock->Exit();
    }

    return ret;
}

uint32_t CGstAudioPlaybackPipeline::InternalPause()
{
    LOWLEVELPERF_EXECTIMESTART("GST_STATE_PAUSED");

    m_StateLock->Enter();
    bool ready = (((Finished != m_PlayerState || m_bSeekInvoked) || m_PlayerPendingState == Stopped) && Error != m_PlayerState);
    m_bSeekInvoked = false;
    m_StateLock->Exit();

    uint32_t ret = ERROR_NONE;
    // We need to pause if it goes from stop, even if we in Finished state
    if (ready)
    {
        if (0.0F == m_fRate)
            // Unset playback resumption flag regardless of whether state change succeeds.
            m_bResumePlayOnNonzeroRate = false;
        else if (GST_STATE_CHANGE_FAILURE == gst_element_set_state(m_Elements[PIPELINE], GST_STATE_PAUSED))
               ret = ERROR_GSTREAMER_PIPELINE_STATE_CHANGE;
        else
            CheckQueueSize(NULL);
    }

    return ret;
}

uint32_t CGstAudioPlaybackPipeline::SeekPipeline(gint64 seek_time)
{
    GstSeekFlags seekFlags;

    // skia-fx: seek trace (OPENJFX_MEDIA_VERBOSE only) — distinguishes
    // the dual-source initial resync from app seeks / stop-rewinds when
    // reading the audio-sink probe's SEGMENT events.
    if (_media_verbose())
        g_print("[seek-trace] SeekPipeline t=%.3fs rate=%.2f state=%d\n",
                seek_time / 1e9, m_fRate, (int)m_PlayerState);

    m_SeekLock->Enter();

    // Any flushing seek re-segments both dual-source chains — the
    // deferred initial resync (GetStreamTime) is no longer needed.
    m_bDualSourceInitialResyncDone = true;

    m_LastSeekTime = seek_time;

    if (m_fRate < -1.0F || m_fRate > 1.0F)
        seekFlags = (GstSeekFlags)(GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_SKIP);
    else
        seekFlags = (GstSeekFlags)(GST_SEEK_FLAG_FLUSH);// | GST_SEEK_FLAG_KEY_UNIT);

    if (m_pOptions->GetPipelineType() == CPipelineOptions::kAudioSourcePipeline)
    {
        gboolean bSeekResult = FALSE;
        bSeekResult |= gst_element_seek(m_Elements[PIPELINE], m_fRate, GST_FORMAT_TIME, seekFlags,
                    GST_SEEK_TYPE_SET, seek_time,
                    GST_SEEK_TYPE_NONE, GST_CLOCK_TIME_NONE);

        if (bSeekResult)
        {
            m_SeekLock->Exit();
            CheckQueueSize(NULL);
            OnSeekIssued(seek_time);
            return ERROR_NONE;
        }
    }
    else
    {
        if (m_Elements[AUDIO_SINK] != NULL && m_bHasAudio && gst_element_seek(m_Elements[AUDIO_SINK], m_fRate, GST_FORMAT_TIME, seekFlags,
            GST_SEEK_TYPE_SET, seek_time,
            GST_SEEK_TYPE_NONE, GST_CLOCK_TIME_NONE))
        {
            m_SeekLock->Exit();
            CheckQueueSize(NULL);
            OnSeekIssued(seek_time);
            return ERROR_NONE;
        }
        else if (m_Elements[VIDEO_SINK] != NULL && m_bHasVideo && gst_element_seek(m_Elements[VIDEO_SINK], m_fRate, GST_FORMAT_TIME, seekFlags,
            GST_SEEK_TYPE_SET, seek_time,
            GST_SEEK_TYPE_NONE, GST_CLOCK_TIME_NONE))
        {
            m_SeekLock->Exit();
            CheckQueueSize(NULL);
            OnSeekIssued(seek_time);
            return ERROR_NONE;
        }
    }

    m_SeekLock->Exit();

    return ERROR_GSTREAMER_PIPELINE_SEEK;
}

/**
 * CGstAudioPlaybackPipeline::Seek()
 *
 * Seek to a presentation time.
 */
uint32_t CGstAudioPlaybackPipeline::Seek(double dSeekTime)
{
    // skia-fx: arm the subclass recovery grace + lag baseline at REQUEST time
    // (before coalescing/execution) so the recovery watchdog can't fire a
    // spurious "lagging" recovery in the window between the seek applying the
    // new audio segment and the post-seek prime running.
    OnSeekRequested((gint64)(GST_SECOND * dSeekTime));

    // skia-fx: when coalescing is active (dual-source A/V), debounce rapid
    // seeks at the player level — record the latest target and let the
    // coalescer thread execute only the final one after a short quiet window,
    // so a slider drag-storm can't thrash the fragment-refetching seek path.
    // The Finished->seek-invoked latch is still set synchronously so a seek
    // out of the Finished state is recognised immediately.
    if (SeekCoalescingEnabled())
    {
        m_StateLock->Enter();
        if (m_PlayerState == Finished)
            m_bSeekInvoked = true;
        m_StateLock->Exit();

        g_mutex_lock(&m_SeekCoalesceMutex);
        m_SeekCoalesceTargetSec = dSeekTime;
        m_SeekCoalesceLastReqUs = g_get_monotonic_time();
        m_bSeekCoalescePending = true;
        g_cond_broadcast(&m_SeekCoalesceCond);
        g_mutex_unlock(&m_SeekCoalesceMutex);
        return ERROR_NONE;
    }

    return DoSeekNow(dSeekTime);
}

uint32_t CGstAudioPlaybackPipeline::DoSeekNow(double dSeekTime)
{
    uint32_t ret = ERROR_NONE;

    m_StateLock->Enter();
    bool notReady = (m_PlayerState != Ready &&
                     m_PlayerState != Playing &&
                     m_PlayerState != Paused &&
                     m_PlayerState != Stopped &&
                     m_PlayerState != Stalled &&
                     m_PlayerState != Finished);

    if (m_PlayerState == Finished)
        m_bSeekInvoked = true;
    m_StateLock->Exit();

    // We should only perform seek in Playing, Paused, Stopped, Stalled or Finished states
    if (notReady)
        return ERROR_NONE;

    ret = SeekPipeline((gint64)(GST_SECOND * dSeekTime));

    // Check if we need to resume pipeline
    m_StateLock->Enter();
    bool resume = (ret == ERROR_NONE && m_PlayerState == Finished && m_PlayerPendingState != Stopped);
    m_StateLock->Exit();

    if (resume)
    {
        if (GST_STATE_CHANGE_FAILURE == gst_element_set_state(m_Elements[PIPELINE], GST_STATE_PLAYING))
            ret = ERROR_GSTREAMER_PIPELINE_STATE_CHANGE;
    }

    return ret;
}

bool CGstAudioPlaybackPipeline::RecoverVideoSeekLocked(gint64 seekTimeNs)
{
    if (m_Elements[VIDEO_SINK] == NULL)
        return false;

    // If a real user seek is already queued in the coalescer, let it win — a
    // recovery seek now would just be superseded and could race it on the
    // shared video chain.
    g_mutex_lock(&m_SeekCoalesceMutex);
    bool userSeekPending = m_bSeekCoalescePending;
    g_mutex_unlock(&m_SeekCoalesceMutex);
    if (userSeekPending)
        return false;

    // Serialise with SeekPipeline() so two flushing seeks can't interleave on
    // the shared video chain (qtdemux/decoder/sink) and wedge it.
    m_SeekLock->Enter();
    gboolean ok = gst_element_seek(
        m_Elements[VIDEO_SINK], 1.0, GST_FORMAT_TIME,
        (GstSeekFlags)GST_SEEK_FLAG_FLUSH,
        GST_SEEK_TYPE_SET, seekTimeNs, GST_SEEK_TYPE_NONE, GST_CLOCK_TIME_NONE);
    m_SeekLock->Exit();

    return ok ? true : false;
}

/**
 * CGstAudioPlaybackPipeline::GetDuration()
 *
 * Get the time duration of the media clip.
 *
 * @return  double representing time
 */
uint32_t CGstAudioPlaybackPipeline::GetDuration(double* dDuration)
{
    gint64    duration = GST_CLOCK_TIME_NONE;

    if (IsPlayerState(Error) || !gst_element_query_duration(m_Elements[PIPELINE], GST_FORMAT_TIME, &duration))
    {
        *dDuration = -1.0;
        return ERROR_GSTREAMER_PIPELINE_QUERY_LENGTH;
    }

    if (duration < 0)
        *dDuration = -1.0;
    else
        *dDuration = (double)duration/(double)GST_SECOND;

    m_dLastReportedDuration = *dDuration;

    return ERROR_NONE;
}

/**
 * CGstAudioPlaybackPipeline::GetStreamTime()
 *
 * Get the stream/presentation time of the media clip.
 *
 * @return  true/false
 */
uint32_t CGstAudioPlaybackPipeline::GetStreamTime(double* streamTime)
{
    gint64    position = GST_CLOCK_TIME_NONE;

#if JFXMEDIA_ENABLE_GST_TRACE
    gst_alloc_trace_set_flags_all ((GstAllocTraceFlags)(GST_ALLOC_TRACE_LIVE | GST_ALLOC_TRACE_MEM_LIVE));
    if (!gst_alloc_trace_available ())
        g_warning ("Trace not available (recompile with trace enabled).");
    else
        gst_alloc_trace_print_live ();
#endif

    m_StateLock->Enter();
    bool notReady = (m_PlayerState == Stopped || m_PlayerState == Error);
    m_StateLock->Exit();

    // If we in Stopped state report 0 for stream time
    if (notReady)
    {
        *streamTime = 0;
        return ERROR_NONE;
    }

    if (!gst_element_query_position(m_Elements[PIPELINE], GST_FORMAT_TIME, &position))
    {
        // Position query failed: use timestamp of most recent buffer instead.
        position = (gint64)m_ulLastStreamTime;
    }
    else
    {
        m_ulLastStreamTime = position;
    }

    *streamTime = (double)position/(double)GST_SECOND;

    // skia-fx: dual-source diagnostic heartbeat (OPENJFX_MEDIA_VERBOSE
    // only). One line per ~10 polls (~1 Hz): pipeline position, player
    // state, encoded queue fill levels. Tells which element starves
    // first when playback freezes.
    {
        static int _hb = 0;
        if (_media_verbose() && (++_hb % 10) == 0)
        {
            guint aqBufs = 0; guint64 aqTime = 0;
            guint vqBufs = 0; guint64 vqTime = 0;
            GstState gs = GST_STATE_NULL, gp = GST_STATE_NULL;
            if (m_Elements[AUDIO_QUEUE] != NULL)
                g_object_get(m_Elements[AUDIO_QUEUE],
                             "current-level-buffers", &aqBufs,
                             "current-level-time", &aqTime, NULL);
            if (m_Elements[VIDEO_QUEUE] != NULL)
                g_object_get(m_Elements[VIDEO_QUEUE],
                             "current-level-buffers", &vqBufs,
                             "current-level-time", &vqTime, NULL);
            gst_element_get_state(m_Elements[PIPELINE], &gs, &gp, 0);
            // wall = monotonic seconds — position-delta vs wall-delta IS
            // the playback duty cycle (1.00 = smooth, <1 = wavy/underrun).
            g_print("[dual-diag] wall=%.2f pos=%.2fs ps=%d gst=%d/%d aq=%u(%ums) vq=%u(%ums)\n",
                    g_get_monotonic_time() / 1e6,
                    *streamTime, (int)m_PlayerState, (int)gs, (int)gp,
                    aqBufs, (unsigned)(aqTime / 1000000),
                    vqBufs, (unsigned)(vqTime / 1000000));
        }
    }

    // skia-fx: deferred dual-source initial resync. With two INDEPENDENT
    // remote sources (Media(audio,video)) the pipeline can reach PLAYING
    // with the two sources' segments/running-times misaligned: the
    // audio-master clock never advances, video freezes on its first
    // frame — historically until the user seeked. One flushing seek
    // re-segments BOTH sources and unfreezes the clock. This is polled
    // here (the Java side queries position every ~100 ms) instead of
    // fired from the bus callback at the PLAYING transition, so it only
    // runs (a) when the freeze is real — clock pinned at 0 for several
    // consecutive polls while PLAYING — and (b) after the pipeline has
    // settled, from an app thread, never mid-preroll. Any earlier
    // SeekPipeline call (user seek) disarms it.
    if (!m_bDualSourceInitialResyncDone &&
        m_pOptions != NULL &&
        m_pOptions->GetPipelineType() == CPipelineOptions::kAudioSourcePipeline &&
        !m_pOptions->GetHLSModeEnabled())
    {
        if (position > 0)
        {
            // Clock advanced on its own — sources are aligned.
            m_bDualSourceInitialResyncDone = true;
        }
        else if (!IsPlayerState(Playing))
        {
            // Stalled / paused / buffering — not evidence of the freeze.
            // Require CONSECUTIVE zero-position polls in stable PLAYING
            // so the resync never fires mid-stall-transition.
            m_DualResyncZeroPosPolls = 0;
        }
        else
        {
            gint64 now = g_get_monotonic_time();
            if (m_DualResyncZeroPosPolls == 0)
                m_DualResyncFirstZeroPosTime = now;
            // ~3 s of PLAYING with the clock pinned at 0 — by BOTH poll
            // count and real elapsed time (bus-thread callers can burst
            // GetStreamTime far faster than the ~100ms Java poll). The
            // threshold is deliberately high: with the source-level fixes
            // (chunked HTTP defeating the CDN's audio drip) the historical
            // freeze should no longer occur at all, and firing this flush
            // during normal startup latency MISALIGNS the video chain —
            // frames render late forever, which shows as video racing in
            // catch-up waves while audio plays normally. The done-flag is
            // set BEFORE seeking so a concurrent caller can't double-fire.
            if (++m_DualResyncZeroPosPolls >= 30 &&
                now - m_DualResyncFirstZeroPosTime >= 3 * G_USEC_PER_SEC)
            {
                m_bDualSourceInitialResyncDone = true;
                SeekPipeline(0);
            }
        }
    }

    // GStreamer may report position which is slightly bigger then duration.
    // This is fine due to different rounding errors, but we should not report position which is bigger then duration.
    if (m_dLastReportedDuration == DURATION_UNKNOWN)
    {
        double dDuration = 0;
        if (GetDuration(&dDuration) != ERROR_NONE)
            m_dLastReportedDuration = DURATION_UNKNOWN; // Hopefully duration will be available next time
    }

    if (m_dLastReportedDuration != DURATION_UNKNOWN && m_dLastReportedDuration != DURATION_INDEFINITE && *streamTime > m_dLastReportedDuration)
        *streamTime = m_dLastReportedDuration;

    return ERROR_NONE;
}

/**
 * CGstAudioPlaybackPipeline::SetRate()
 *
 * Set the playback rate.  The rate can be a positive or negative float.
 *
 * @param   fRate   positive/negative float
 */
uint32_t CGstAudioPlaybackPipeline::SetRate(float fRate)
{
    uint32_t ret = ERROR_NONE;

    if (IsPlayerState(Error))
        return ret;

    if (fRate != m_fRate)
    {
        if (0.0F == fRate)
        {
            GstState state;
            gst_element_get_state(m_Elements[PIPELINE], &state, NULL, 0);

            // It's not enough to check only m_PlayerState for playing state. There can be penging message to change the state
            // while we switch the rate.
            bool resume = (state == GST_STATE_PLAYING || IsPlayerState(Stalled));

            if (ERROR_NONE == Pause())
            {
                m_fRate = 0.0F;

                // Set playback resumption flag if currently playing or stalled.
                m_bResumePlayOnNonzeroRate = resume;
            }
            else
                ret = ERROR_GSTREAMER_PIPELINE_SET_RATE_ZERO;
        }
        else
        {
            // Determine current position.
            m_SeekLock->Enter();
            m_fRate = fRate;

            gint64 seek_time = 0;
            if (m_LastSeekTime == -1)
            {
                double streamTime = 0;
                GetStreamTime(&streamTime);
                seek_time = (gint64)(GST_SECOND*streamTime);
            }
            else
            {
                seek_time = m_LastSeekTime;
            }

            if (SeekPipeline(seek_time) == ERROR_NONE)
            {
                m_SeekLock->Exit();

                // Set flag to indicate change from zero rate.
                gboolean rateWasZero = (0.0F == m_fRate);

                // Resume play if resetting from zero rate and flag is set.
                if (rateWasZero && m_bResumePlayOnNonzeroRate)
                    Play(); // Ignore the return value. TOOD: Emit a warning?

                ret = ERROR_NONE;
            }
            else
            {
                m_SeekLock->Exit();
                ret = ERROR_GSTREAMER_PIPELINE_SEEK;
            }
        }
    }

    return ret;
}

/**
 * CGstAudioPlaybackPipeline::GetRate()
 *
 * Init an audio-only playback pipeline.
 *
 * @return  float value for the rate.
 */
uint32_t CGstAudioPlaybackPipeline::GetRate(float* rate)
{
    *rate = m_fRate;
    return ERROR_NONE;
}

/**
 * CGstAudioPlaybackPipeline::SetVolume()
 *
 * Set the volume for audio playback.
 *
 * @param   fVolume float value between 0.0f and 1.0f.
 */
uint32_t CGstAudioPlaybackPipeline::SetVolume(float volume)
{
    if (IsPlayerState(Error))
        return ERROR_NONE;

    // Clamp the value
    volume = (volume < 0.0F) ? 0.0F :
             (volume > 1.0F) ? 1.0F :
             volume;

    g_object_set (G_OBJECT (m_Elements[AUDIO_VOLUME]), "volume", volume, NULL);

    return ERROR_NONE;
}

/**
 * CGstAudioPlaybackPipeline::GetVolume()
 *
 * Get the audio volume.
 *
 * @return  a float value between -1.0f and 1.0f
 */
uint32_t CGstAudioPlaybackPipeline::GetVolume(float* volume)
{
    if (IsPlayerState(Error))
        return ERROR_NONE;

    gdouble dvolume = 1.0F;
    g_object_get (m_Elements[AUDIO_VOLUME], "volume", &dvolume, NULL);

    *volume = (gfloat)dvolume;

    return ERROR_NONE;
}

/**
 * CGstAudioPlaybackPipeline::SetBalance()
 *
 * Set the balance for the audio volume between left and right audio channel.
 *
 * @param   fBalance    float value between -1.0f and 1.0f
 */
uint32_t CGstAudioPlaybackPipeline::SetBalance(float fBalance)
{
    if (IsPlayerState(Error))
        return ERROR_NONE;

    fBalance = (fBalance < -1.0F) ? -1.0F :
              (fBalance >  1.0F) ?  1.0F :
               fBalance;

    g_object_set (G_OBJECT (m_Elements[AUDIO_BALANCE]), "panorama", fBalance, NULL);

    return ERROR_NONE;
}

/**
 * CGstAudioPlaybackPipeline::GetBalance()
 *
 * Get the audio balance between left and right channel.
 *
 * @return  float value between -1.0f and 1.0f
 */
uint32_t CGstAudioPlaybackPipeline::GetBalance(float* balance)
{
    if (IsPlayerState(Error))
        return ERROR_NONE;

    gfloat fbalance = 0.0F;
    g_object_get (m_Elements[AUDIO_BALANCE], "panorama", &fbalance, NULL);

    *balance = fbalance;

    return ERROR_NONE;
}

/**
 * CGstAudioPlaybackPipeline::SetAudioSyncDelay()
 *
 * Set an audio sync delay for the audio.  May keep audio and video in sync if video rendering
 * has a longer path.
 *
 * @param   lMillis     time delay in milliseconds
 */
uint32_t CGstAudioPlaybackPipeline::SetAudioSyncDelay(long millis)
{
    if (IsPlayerState(Error))
        return ERROR_NONE;

    g_object_set (G_OBJECT (m_Elements[AUDIO_SINK]), "ts-offset", (gint64)(millis*GST_MSECOND), NULL);

    return ERROR_NONE;
}

/**
 * CGstAudioPlaybackPipeline::GetAudioSyncDelay()
 *
 * Get the audio sync delay.
 *
 * @return  time delay value in milliseconds.
 */
uint32_t CGstAudioPlaybackPipeline::GetAudioSyncDelay(long* audioSyncDelay)
{
    if (IsPlayerState(Error))
        return ERROR_NONE;

    gint64 nanos = 0;
    g_object_get (m_Elements[AUDIO_SINK], "ts-offset", &nanos, NULL);

    *audioSyncDelay = (long)GST_TIME_AS_MSECONDS(nanos);

    return ERROR_NONE;
}

CAudioEqualizer* CGstAudioPlaybackPipeline::GetAudioEqualizer()
{
    return m_pAudioEqualizer;
}

CAudioSpectrum* CGstAudioPlaybackPipeline::GetAudioSpectrum()
{
    return m_pAudioSpectrum;
}

bool CGstAudioPlaybackPipeline::IsCodecSupported(GstCaps *pCaps)
{
#if TARGET_OS_WIN32
    GstStructure *s = NULL;
    const gchar *mimetype = NULL;

    if (pCaps)
    {
        s = gst_caps_get_structure (pCaps, 0);
        if (s != NULL)
        {
            mimetype = gst_structure_get_name (s);
            if (mimetype != NULL)
            {
                if (strstr(mimetype, CONTENT_TYPE_MPA) != NULL || // AAC or MPEG
                    strstr(mimetype, CONTENT_TYPE_MP3) != NULL)    // MPEG-1 or -2
                {
                    gint mpegversion = 0;

                    if (gst_structure_get_int(s, "mpegversion", &mpegversion))
                    {
                        if (mpegversion == 4)
                        {
                            gboolean is_supported = FALSE;
                            g_object_set(m_Elements[AUDIO_DECODER], "codec-id", (gint)JFX_CODEC_ID_AAC, NULL);
                            g_object_get(m_Elements[AUDIO_DECODER], "is-supported", &is_supported, NULL);
                            if (is_supported)
                            {
                                return TRUE;
                            }
                            else
                            {
                                m_audioCodecErrorCode = ERROR_MEDIA_AAC_FORMAT_UNSUPPORTED;
                                return FALSE;
                            }
                        }
                    }
                }
                else if (strstr(mimetype, CONTENT_TYPE_AAC) != NULL)
                {
                    gboolean is_supported = FALSE;
                    g_object_set(m_Elements[AUDIO_DECODER], "codec-id", (gint)JFX_CODEC_ID_AAC, NULL);
                    g_object_get(m_Elements[AUDIO_DECODER], "is-supported", &is_supported, NULL);
                    if (is_supported)
                    {
                        return TRUE;
                    }
                    else
                    {
                        m_audioCodecErrorCode = ERROR_MEDIA_AAC_FORMAT_UNSUPPORTED;
                        return FALSE;
                    }
                }
            }
        }
    }

    return TRUE;
#else // TARGET_OS_WIN32
    GstStructure *s = NULL;
    const gchar *mimetype = NULL;

    if (pCaps)
    {
        s = gst_caps_get_structure (pCaps, 0);
        if (s != NULL)
        {
            mimetype = gst_structure_get_name (s);
            if (mimetype != NULL)
            {
                if (strstr(mimetype, "audio/unsupported") != NULL)
                {
                    m_audioCodecErrorCode = ERROR_MEDIA_AUDIO_FORMAT_UNSUPPORTED;
                    return FALSE;
                }
            }
        }
    }

    return TRUE;
#endif // TRAGET_OS_WIN32
}

bool CGstAudioPlaybackPipeline::CheckCodecSupport()
{
    if (!m_bHasAudio)
    {
        if (m_pEventDispatcher && m_audioCodecErrorCode != ERROR_NONE)
        {
            if (!m_pEventDispatcher->SendPlayerMediaErrorEvent(m_audioCodecErrorCode))
            {
                LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
            }

            return FALSE;
        }
    }

    return TRUE;
}

bool CGstAudioPlaybackPipeline::LoadDecoder(GstCaps *pCaps)
{
    return TRUE; // Nothing to do for audio yet
}

/**
 * CGstAudioPlaybackPipeline::BusCallback()
 *
 * GStreamer message bus for the audio pipeline.
 *
 * @param
 *
 * @return  true/false
 */
gboolean CGstAudioPlaybackPipeline::BusCallback(GstBus* bus, GstMessage* msg, sBusCallbackContent* pBusCallbackContent)
{
    pBusCallbackContent->m_DisposeLock->Enter();

    LOWLEVELPERF_EXECTIMESTART("BusCallback()");

    if (pBusCallbackContent->m_bIsDisposed)
    {
        pBusCallbackContent->m_DisposeLock->Exit();
        return FALSE; // Tell to stop sending messaged
    }
    else if (pBusCallbackContent->m_bIsDisposeInProgress)
    {
        pBusCallbackContent->m_DisposeLock->Exit();
        return TRUE; // Continue processing messages while we disposing, but
                     // ignore them.
    }

    CGstAudioPlaybackPipeline* pPipeline = pBusCallbackContent->m_pPipeline;

    switch (GST_MESSAGE_TYPE (msg)) {

        case GST_MESSAGE_DURATION_CHANGED:
        {
            if(NULL != pPipeline->m_pEventDispatcher)
            {
                GstFormat format;
                gint64 durationNanos;

                // Parse the message to obtain the value and its format.
                gst_message_parse_duration(msg, &format, &durationNanos);

                // Continue if the format is time.
                if (format == GST_FORMAT_TIME && durationNanos > 0)
                {
                    // Convert the duration from nanoseconds to seconds.
                    double duration = (double)durationNanos/(double)GST_SECOND;

                    // Dispatch the event.
                    if (!pPipeline->m_pEventDispatcher->SendDurationUpdateEvent(duration))
                    {
                        if(!pPipeline->m_pEventDispatcher->SendPlayerMediaErrorEvent(ERROR_JNI_SEND_DURATION_UPDATE_EVENT))
                        {
                            LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
                        }
                    }
                }
            }
        }
            break;

        case GST_MESSAGE_EOS:
        {
            // In some cases we may receive several GST_MESSAGE_EOS and signal Finsihed state several times.
            // We should enter and signal Finished state only once.
            // GST_MESSAGE_EOS will be send several times, because of bug or design issue in gstbin.
            // gstbin will check all sinks for EOS message and if all sinks posted EOS message it will forward message to application.
            // However, gstbin does not clear EOS message on sinks, which will result in several EOS messages being posted to application.
            // This condition reproduces after EOS-> Seek to restart playback -> EOS (2 messages received).
            if (!pPipeline->IsPlayerState(Finished))
            {
                // Set the state to Finished which may only be exited by seeking back before the finish time.
                pPipeline->SetPlayerState(Finished, false);

#if ENABLE_PROGRESS_BUFFER
                if (pPipeline->m_pOptions->GetHLSModeEnabled())
                    pPipeline->m_bLastProgressValueEOS = FALSE; // Otherwise we will resume playback if we loop and user hits stop
#endif // ENABLE_PROGRESS_BUFFER
            }
        }
            break;

        case GST_MESSAGE_ERROR:
        {
            gchar  *debug = NULL;
            GError *error = NULL;

            gst_message_parse_error (msg, &error, &debug);

            if (error && error->message)
                LOGGER_LOGMSG(LOGGER_ERROR, error->message);

            if (debug)
                LOGGER_LOGMSG(LOGGER_DEBUG, debug);

            // skia-fx: the mapped Java error code loses the real reason
            // (element, domain, message) — surface it when debugging.
            if (_media_verbose() || g_getenv("SKIA_MEDIA_DEBUG") != NULL)
                g_print("[gst-error] from=%s domain=%d code=%d msg='%s' debug='%s'\n",
                        GST_MESSAGE_SRC(msg) ? GST_OBJECT_NAME(GST_MESSAGE_SRC(msg)) : "?",
                        error ? (int)error->domain : -1,
                        error ? error->code : -1,
                        (error && error->message) ? error->message : "",
                        debug ? debug : "");

            // Handle connection lost error
            if (error)
            {
                if (pPipeline != NULL && pPipeline->m_pEventDispatcher != NULL && error->domain == GST_RESOURCE_ERROR && error->code == GST_RESOURCE_ERROR_READ)
                {
                    if (!pPipeline->m_pEventDispatcher->SendPlayerMediaErrorEvent(ERROR_LOCATOR_CONNECTION_LOST))
                    {
                        LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
                    }
                    pPipeline->m_bIgnoreError = TRUE;
                    g_error_free (error);
                    if (debug)
                        g_free(debug);
                    break;
                }
                // GstBaseSrc will send GST_STREAM_ERROR_FAILED when connection is lost
                // We need to ignore this error if it was received right after GST_RESOURCE_ERROR_READ
                else if (pPipeline != NULL && pPipeline->m_bIgnoreError && error->domain == GST_STREAM_ERROR && error->code == GST_STREAM_ERROR_FAILED)
                {
                    pPipeline->m_bIgnoreError = FALSE;
                    g_error_free (error);
                    if (debug)
                        g_free(debug);
                    break;
                }
                else if (pPipeline != NULL && pPipeline->m_pEventDispatcher != NULL && error->domain == GST_STREAM_ERROR &&
                    (error->code == GST_STREAM_ERROR_DECODE || error->code == GST_STREAM_ERROR_WRONG_TYPE))
                {
                    if (!pPipeline->m_pEventDispatcher->SendPlayerMediaErrorEvent(ERROR_MEDIA_INVALID))
                    {
                        LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
                    }
                    g_error_free (error);
                    if (debug)
                        g_free(debug);
                    break;
                }
                else if (pPipeline != NULL && pPipeline->m_pEventDispatcher != NULL && error->domain == GST_STREAM_ERROR &&
                    (error->code == GST_STREAM_ERROR_CODEC_NOT_FOUND ||
                     error->code == GST_STREAM_ERROR_FAILED ||
                     error->code == GST_STREAM_ERROR_TYPE_NOT_FOUND))
                {
                    if (pPipeline->m_pOptions->GetHLSModeEnabled())
                    {
                        if (!pPipeline->m_pEventDispatcher->SendPlayerMediaErrorEvent(ERROR_MEDIA_HLS_FORMAT_UNSUPPORTED))
                        {
                            LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
                        }
                    }
                    else
                    {
                        if (!pPipeline->m_pEventDispatcher->SendPlayerMediaErrorEvent(ERROR_MEDIA_INVALID))
                        {
                            LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
                        }
                    }
                    g_error_free (error);
                    if (debug)
                        g_free(debug);
                    break;
                }
                else if (pPipeline != NULL && pPipeline->m_pEventDispatcher != NULL && error->domain == JFX_GST_ERROR)
                {
                    if (error->code == JFX_GST_MISSING_LIBSWSCALE)
                    {
                        if (!pPipeline->m_pEventDispatcher->SendPlayerMediaErrorEvent(ERROR_MISSING_LIBSWSCALE))
                        {
                            LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
                        }
                    }
                    else if (error->code == JFX_GST_INVALID_LIBSWSCALE)
                    {
                        if (!pPipeline->m_pEventDispatcher->SendPlayerMediaErrorEvent(ERROR_INVALID_LIBSWSCALE))
                        {
                            LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
                        }
                    }
                }
            }

            // Clear ignore error in case if we did not receive GST_STREAM_ERROR_FAILED after GST_RESOURCE_ERROR_READ.
            pPipeline->m_bIgnoreError = FALSE;

            // Tear down GStreamer pipeline only if PlayerState is not Error, becuase when GST_MESSAGE_ERROR
            // is generated during state change, we may have infinite loop by getting GST_MESSAGE_ERROR
            // each time when we try to set pipeline to GST_STATE_NULL.
            if (!pPipeline->IsPlayerState(Error))
                gst_element_set_state(pPipeline->m_Elements[PIPELINE], GST_STATE_NULL); // Ignore return value.

            pPipeline->SetPlayerState(Error, true);

            if (error)
            {
                if (NULL != pPipeline->m_pEventDispatcher)
                {
                    if (error->domain == GST_STREAM_ERROR && error->code == GST_STREAM_ERROR_DEMUX)
                    {
                        if (!pPipeline->m_pEventDispatcher->SendPlayerMediaErrorEvent(ERROR_MEDIA_CORRUPTED))
                        {
                            LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
                        }
                    }
                    else
                    {
                        if (!pPipeline->m_pEventDispatcher->SendPlayerHaltEvent(error->message, (double)msg->timestamp / GST_SECOND))
                        {
                            if(!pPipeline->m_pEventDispatcher->SendPlayerMediaErrorEvent(ERROR_JNI_SEND_PLAYER_HALT_EVENT))
                            {
                                LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
                            }
                        }
                    }
                }
                g_error_free (error);
            }

            if (debug)
                g_free (debug);
        }
            break;

        case GST_MESSAGE_WARNING:
        {
            gchar  *debug = NULL;
            GError *warning = NULL;

            gst_message_parse_warning (msg, &warning, &debug);

            if (warning)
            {
                pPipeline->m_pEventDispatcher->Warning(WARNING_GSTREAMER_PIPELINE_WARNING,
                                                       (const char*)warning->message);
                LOGGER_LOGMSG(LOGGER_WARNING, warning->message);
                g_error_free (warning);
            }

            if (debug)
            {
                LOGGER_LOGMSG(LOGGER_DEBUG, debug);
                g_free (debug);
            }
        }
            break;

        case GST_MESSAGE_INFO:
        {
            gchar  *debug = NULL;
            GError *info = NULL;

            gst_message_parse_info (msg, &info, &debug);

            if (info)
            {
                pPipeline->m_pEventDispatcher->Warning(WARNING_GSTREAMER_PIPELINE_INFO_ERROR,
                                                       (const char*)info->message);
                LOGGER_LOGMSG(LOGGER_ERROR, info->message);
                g_error_free (info);
            }

            if (debug)
            {
                LOGGER_LOGMSG(LOGGER_DEBUG, debug);
                g_free (debug);
            }
        }
            break;

        case GST_MESSAGE_STATE_CHANGED:
        {
            GstState oldState, newState, pendingState;

            gst_message_parse_state_changed(msg, &oldState, &newState, &pendingState);
#if JFXMEDIA_DEBUG
            if (GST_MESSAGE_SRC(msg) == GST_OBJECT(pPipeline->m_Elements[PIPELINE]))
                g_print ("%s: %s->%s pending(%s)\n",
                        GST_OBJECT_NAME(GST_MESSAGE_SRC(msg)),
                        gst_element_state_get_name(oldState),
                        gst_element_state_get_name(newState),
                        gst_element_state_get_name(pendingState));
#endif

            // Check if we need to set clock
            // Based on GStreamer documentation audio sink should provide clock when it in PAUSED state.
            // In NULL or READY state clock maybe invalid.
            if (!pPipeline->m_bIsClockSet && pPipeline->m_Elements[AUDIO_SINK] != NULL && pPipeline->m_bHasAudio && GST_MESSAGE_SRC(msg) == GST_OBJECT(pPipeline->m_Elements[AUDIO_SINK]) && pendingState == GST_STATE_VOID_PENDING && newState == GST_STATE_READY)
            {
                pPipeline->m_bSetClock = true;
                pPipeline->m_bIsClockSet = true;
            }

            // Check if sink are ready
            if (!pPipeline->m_bDynamicElementsReady)
            {
                if (pPipeline->m_Elements[AUDIO_SINK] == NULL)
                    pPipeline->m_bAudioSinkReady = true;
                else if (GST_MESSAGE_SRC(msg) == GST_OBJECT(pPipeline->m_Elements[AUDIO_SINK]) && newState == GST_STATE_PAUSED && oldState == GST_STATE_READY && pendingState == GST_STATE_VOID_PENDING)
                    pPipeline->m_bAudioSinkReady = true;

                if (pPipeline->m_Elements[VIDEO_SINK] == NULL)
                    pPipeline->m_bVideoSinkReady = true;
                else if (GST_MESSAGE_SRC(msg) == GST_OBJECT(pPipeline->m_Elements[VIDEO_SINK]) && newState == GST_STATE_PAUSED && oldState == GST_STATE_READY && pendingState == GST_STATE_VOID_PENDING)
                    pPipeline->m_bVideoSinkReady = true;

                if (pPipeline->m_bAudioSinkReady && pPipeline->m_bVideoSinkReady)
                    pPipeline->m_bDynamicElementsReady = true;
            }

            // Update clock if needed
            // Audio sink will provide clock when it in paused or playing state.
            // Our pipeline will not find audio sink clock, because we use audio sink inside bin and bin hides clock distribution.
            // When pipeline cannot find clock it will use GstSystemClock, so we need to set correct clock to pipeline.
            //
            // skia-fx: for a dual-source pipeline the audio sink (the clock
            // provider) is ready long before the dynamically-built video
            // chain, so also accept the audio-sink-reached-PAUSED arm for
            // non-static pipelines — waiting for m_bDynamicElementsReady
            // could push the swap past the PLAYING transition.
            if (pPipeline->m_bSetClock && ((pPipeline->m_Elements[AUDIO_SINK] != NULL && pPipeline->m_bHasAudio && GST_MESSAGE_SRC(msg) == GST_OBJECT(pPipeline->m_Elements[AUDIO_SINK]) && pendingState == GST_STATE_VOID_PENDING && newState == GST_STATE_PAUSED) || pPipeline->m_bDynamicElementsReady))
            {
                // skia-fx: NEVER swap the clock once the pipeline is
                // PLAYING. gst_pipeline_set_clock does not redistribute
                // base_time; base_time was computed against the previous
                // (system) clock's epoch, so after a mid-PLAYING swap every
                // video frame's running time is permanently in the past —
                // the video sink stops waiting and renders at decode speed
                // ("video racing in waves" while the audio sink, master of
                // its own clock, stays smooth). Staying on the system clock
                // is harmless by comparison (correct pacing, only long-term
                // drift risk).
                GstState curState = GST_STATE_NULL, curPending = GST_STATE_VOID_PENDING;
                gst_element_get_state(pPipeline->m_Elements[PIPELINE], &curState, &curPending, 0);
                if (curState == GST_STATE_PLAYING || curPending == GST_STATE_PLAYING)
                {
                    // Not now — but KEEP m_bSetClock armed: the next safe
                    // window (e.g. a user pause: state PAUSED, pending
                    // VOID) installs the clock, and the following
                    // PAUSED->PLAYING transition recomputes base_time
                    // against it. Clearing the flag here would pin
                    // autoplaying pipelines (pending PLAYING before the
                    // sink's PAUSED message is processed) to the system
                    // clock for their whole life — long-term A/V drift.
                    if (_media_verbose())
                        g_print("[clock] pipeline PLAYING/pending-PLAYING - deferring "
                                "audio-clock install to the next safe state window\n");
                }
                else
                {
                pPipeline->m_bSetClock = false;

                // Get clock from audio sink
                GstClock *clock = gst_element_provide_clock(pPipeline->m_Elements[AUDIO_SINK]);

                // Set it to pipeline only if we have one
                // If we set NULL as clock pipeline will render as fast as possible and we do not want this to happen.
                // In case if we did not get clock, pipeline will use GstSystemClock which is better then using NULL.
                if (clock != NULL)
                {
                    gst_pipeline_set_clock(GST_PIPELINE(pPipeline->m_Elements[PIPELINE]), clock);
                    gst_object_unref(clock);
                    if (_media_verbose())
                        g_print("[clock] audio sink clock installed (pipeline state=%d)\n",
                                (int)curState);
                }
                }
            }

            // We have special case when we in Paused or Stall state and we going to Stopped or Paused state. In this case
            // newState and oldState will be set to GST_STATE_PAUSED.
            if (GST_MESSAGE_SRC(msg) == GST_OBJECT(pPipeline->m_Elements[PIPELINE])
                && ((pendingState == GST_STATE_VOID_PENDING && newState != oldState && !pPipeline->IsPlayerState(Unknown)) // Regular state change
                || ((pPipeline->IsPlayerPendingState(Stopped) || pPipeline->IsPlayerPendingState(Paused) || pPipeline->m_StallOnPause) && newState == GST_STATE_PAUSED && oldState == GST_STATE_PAUSED && pendingState == GST_STATE_VOID_PENDING) // Special cases for pause, stall and stop
                     || (pPipeline->IsPlayerState(Unknown) && newState == GST_STATE_PAUSED && (oldState == GST_STATE_READY || oldState == GST_STATE_PAUSED) && pendingState == GST_STATE_VOID_PENDING && !pPipeline->m_bStaticPipeline && pPipeline->m_bDynamicElementsReady) // Ready for dynamic pipeline
                     || (pPipeline->IsPlayerState(Unknown) && newState == GST_STATE_PAUSED && oldState == GST_STATE_READY && pendingState == GST_STATE_VOID_PENDING && pPipeline->m_bStaticPipeline))) // Ready for static pipeline
            {
                if (GST_STATE_PAUSED == newState)
                {
                    LOWLEVELPERF_EXECTIMESTOP("GST_STATE_PAUSED");

#if ENABLE_PROGRESS_BUFFER
                    // Update buffer position only if progress buffer got EOS.
                    // In some case progress may not be reported yet, because duration was not available yet.
                    // By now it should be available, so lets update buffer position.
                    if (pPipeline->m_bLastProgressValueEOS)
                        pPipeline->UpdateBufferPosition();
#endif // ENABLE_PROGRESS_BUFFER
                }

                // Update the player state.
                pPipeline->UpdatePlayerState(newState, oldState);

                // skia-fx: the dual-source initial resync used to fire HERE,
                // eagerly, on the first PLAYING transition. That raced the
                // dynamic video-chain build: the flushing seek could land
                // while the 4K video bin was still completing its first
                // preroll, wedging the pipeline in async limbo (audio ring
                // buffer never restarted, master clock pinned at 0, even
                // user seeks couldn't recover). The resync now fires from
                // GetStreamTime — deferred until the clock is actually
                // observed frozen in PLAYING. See m_DualResyncZeroPosPolls.
            }
        }
            break;

#if ENABLE_PROGRESS_BUFFER
        case GST_MESSAGE_APPLICATION:       //This currently handles messages from the progress buffer element
        {
            const GstStructure *pStr = gst_message_get_structure(msg);

            // skia-fx: dual-source companion-audio bottleneck correction.
            // In Media(audio,video) mode there are two progressbuffers —
            // one per remote source. The companion AUDIO stream is tiny
            // (e.g. 131 kbps Opus) and downloads almost instantly, so its
            // progressbuffer keeps reporting "fully buffered / far ahead".
            // The high-bitrate VIDEO progressbuffer is the real bottleneck.
            // Because this handler tracks a single m_BufferPosition without
            // distinguishing the source, the audio buffer's "ahead" reports
            // drive premature STALLED->PLAYING resumes while the video is
            // still filling — producing rapid stall/resume flapping and A/V
            // desync. Ignore the companion audio progressbuffer's
            // buffering/underrun messages whenever a separate video pipeline
            // is present; let the video buffer alone drive the stall logic.
            GstObject *msgSrc = GST_MESSAGE_SRC(msg);
            if (pPipeline->m_Elements[VIDEO_SINK] != NULL &&
                pPipeline->m_Elements[AUDIO_SOURCE] != NULL &&
                msgSrc != NULL)
            {
                // Walk msgSrc's parent chain looking for the companion
                // AUDIO_SOURCE bin. gstreamer-lite doesn't export
                // gst_object_has_as_ancestor / gst_object_get_parent, so
                // read the parent field directly via the GST_OBJECT_PARENT
                // macro (no ref-counting; all objects are alive for the
                // duration of this bus callback).
                GstObject *audioSrc = GST_OBJECT(pPipeline->m_Elements[AUDIO_SOURCE]);
                gboolean fromCompanionAudio = FALSE;
                for (GstObject *cur = msgSrc; cur != NULL; cur = GST_OBJECT_PARENT(cur))
                {
                    if (cur == audioSrc)
                    {
                        fromCompanionAudio = TRUE;
                        break;
                    }
                }
                if (fromCompanionAudio)
                {
                    // skia-fx: the companion's BUFFERING reports must not
                    // feed the SHARED stall/resume math (the tiny audio
                    // stream reports "far ahead" and causes premature
                    // resumes) — but they ARE recorded into the audio-side
                    // fields so the resume condition can require an audio
                    // cushion. The companion's UNDERRUN passes through:
                    // ignoring it meant a starving companion froze
                    // playback silently (frozen clock) instead of
                    // stalling cleanly.
                    if (gst_structure_has_name(pStr, PB_MESSAGE_BUFFERING))
                    {
                        const GValue *position_v = gst_structure_get_value(pStr, "position");
                        const GValue *stop_v     = gst_structure_get_value(pStr, "stop");
                        const GValue *eos_v      = gst_structure_get_value(pStr, "eos");
                        pPipeline->m_StallLock->Enter();
                        pPipeline->m_llAudioProgressValuePosition = g_value_get_int64(position_v);
                        pPipeline->m_llAudioProgressValueStop     = g_value_get_int64(stop_v);
                        pPipeline->m_bAudioProgressEOS            = g_value_get_boolean(eos_v);
                        pPipeline->m_StallLock->Exit();
                        // Re-evaluate the resume condition — the audio
                        // cushion may have just become sufficient. Only
                        // meaningful while stalled; calling it otherwise
                        // would just re-send the (unchanged) VIDEO
                        // progress event to Java once per ~1% of the
                        // audio download.
                        if (pPipeline->IsPlayerState(Stalled))
                            pPipeline->UpdateBufferPosition();
                    }
                    else if (gst_structure_has_name(pStr, PB_MESSAGE_UNDERRUN))
                    {
                        pPipeline->BufferUnderrun();
                    }
                    break;
                }
            }

            if (gst_structure_has_name(pStr, PB_MESSAGE_BUFFERING))
            {
                // See comment to progressbuffer.c:send_position_message for more details.
                const GValue *start_v    = gst_structure_get_value(pStr, "start");
                const GValue *position_v = gst_structure_get_value(pStr, "position");
                const GValue *stop_v     = gst_structure_get_value(pStr, "stop");
                const GValue *eos_v      = gst_structure_get_value(pStr, "eos");

                gint64    start     = g_value_get_int64(start_v);
                gint64    position  = g_value_get_int64(position_v);
                gint64    stop      = g_value_get_int64(stop_v);
                gboolean  eos       = g_value_get_boolean(eos_v); // eos indicates if progress buffer received EOS event.
                                                                    // This mean that progress buffer will not send any progress messages anymore and no more data will be available.

                // When we receive GST_MESSAGE_APPLICATION pipeline may not fully complete transition to PAUSE state.
                // In this case duration will not be available, thus we cannot report progress.
                // Also, file may be very small and in this case progress buffer will able to download all data (no more GST_MESSAGE_APPLICATION)
                // untill pipeline completes transition to PAUSE state. In such case we will never report any progress.
                // To solve this lets save last reported value and update progress when pipeline completed transition to PAUSE state.
                pPipeline->m_llLastProgressValueStart = start;
                pPipeline->m_llLastProgressValuePosition = position;
                pPipeline->m_llLastProgressValueStop = stop;
                pPipeline->m_bLastProgressValueEOS = eos;

                // Update buffer position
                pPipeline->UpdateBufferPosition();
            }
            else if (gst_structure_has_name(pStr, PB_MESSAGE_UNDERRUN))
                pPipeline->BufferUnderrun();
            else if (gst_structure_has_name(pStr, HLS_PB_MESSAGE_STALL))
                pPipeline->HLSBufferStall();
            else if (gst_structure_has_name(pStr, HLS_PB_MESSAGE_RESUME))
                pPipeline->HLSBufferResume(false);
            else if (gst_structure_has_name(pStr, HLS_PB_MESSAGE_HLS_EOS))
                pPipeline->HLSBufferResume(true);
            else if (gst_structure_has_name(pStr, HLS_PB_MESSAGE_FULL))
            {
                pPipeline->m_StallLock->Enter();
                pPipeline->m_bHLSPBFull = true;
                pPipeline->m_StallLock->Exit();
                pPipeline->HLSBufferResume(false);
            }
            else if (gst_structure_has_name(pStr, HLS_PB_MESSAGE_NOT_FULL))
                pPipeline->m_bHLSPBFull = false;
        }
            break;
#endif  //ENABLE_PROGRESS_BUFFER

        case GST_MESSAGE_ELEMENT:
        {
            const GstStructure *pStr = gst_message_get_structure (msg);
            if (gst_structure_has_name(pStr, "spectrum"))
            {
                GstClockTime timestamp, duration;

                if (!gst_structure_get_clock_time (pStr, "timestamp", &timestamp))
                    timestamp = GST_CLOCK_TIME_NONE;

                if (!gst_structure_get_clock_time (pStr, "duration", &duration))
                    duration = GST_CLOCK_TIME_NONE;

                size_t bandsNum = pPipeline->GetAudioSpectrum()->GetBands();

                if (bandsNum > 0)
                {
                    float *magnitudes = new float[bandsNum];
                    float *phases = new float[bandsNum];

                    const GValue *magnitudes_value = gst_structure_get_value(pStr, "magnitude");
                    const GValue *phases_value = gst_structure_get_value(pStr, "phase");
                    for (int i=0; i < bandsNum; i++)
                    {
                        magnitudes[i] = g_value_get_float( gst_value_list_get_value (magnitudes_value, i));
                        phases[i] = g_value_get_float( gst_value_list_get_value (phases_value, i));
                    }
                    pPipeline->GetAudioSpectrum()->UpdateBands((int)bandsNum, magnitudes, phases);

                    delete [] magnitudes;
                    delete [] phases;
                }

                if (!pPipeline->m_pEventDispatcher->SendAudioSpectrumEvent(GST_TIME_AS_SECONDS((double)timestamp),
                    GST_TIME_AS_SECONDS((double)duration), false)) // Always false, since GStreamer does not need it,
                                                                   // but if it will be required such case needs to be
                                                                   // tested.
                {
                    if(!pPipeline->m_pEventDispatcher->SendPlayerMediaErrorEvent(ERROR_JNI_SEND_AUDIO_SPECTRUM_EVENT))
                    {
                        LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
                    }
                }
          }

        }
            break;

        case GST_MESSAGE_ASYNC_DONE:
            pPipeline->m_SeekLock->Enter();
            pPipeline->m_LastSeekTime = -1;
            pPipeline->m_SeekLock->Exit();
            break;

        case GST_MESSAGE_LATENCY:
            gst_bin_recalculate_latency (GST_BIN(pPipeline->m_Elements[PIPELINE]));
            break;

        default:
            break;
    }

    LOWLEVELPERF_EXECTIMESTOP("BusCallback()");

    pBusCallbackContent->m_DisposeLock->Exit();

    return TRUE;
}

// This function will be called in 2 cases and it will be always called when no more BusCallbacks is expected:
// 1 - When g_source_destroy() is called from Dispose() and there are no pending or in-progress BusCallbacks. It will be called from Dispose() thread.
// 2 - When g_source_destroy() is called from Dispose() and all pending or in-progress BusCallbacks are done. It will be called from main loop thread and pipeline will be gone at this time.
// So lets figure out who will be responsible to free memory, since DisposeLock is used by Dispose() as well.
void CGstAudioPlaybackPipeline::BusCallbackDestroyNotify(sBusCallbackContent* pBusCallbackContent)
{
    if (pBusCallbackContent)
    {
        bool bFreeMeHere = false;

        pBusCallbackContent->m_DisposeLock->Enter();
        if (pBusCallbackContent->m_bIsDisposed)
            bFreeMeHere = true; // Everything is gone, so free me here.
        else
            pBusCallbackContent->m_bFreeMe = true; // Ask Dispose() when it is done to free me
        pBusCallbackContent->m_DisposeLock->Exit();

        if (bFreeMeHere)
        {
            delete pBusCallbackContent->m_DisposeLock;
            delete pBusCallbackContent;
        }
    }
}

/**
 * CGstAudioPlaybackPipeline::SetPlayerState()
 *
 * Sets our "player" state.  This is not the same as the gst pipeline state.  This function should not be
 * called for normal state changes.  This is for out-of-band changes like stalled condition or EOS.
 *
 */
void CGstAudioPlaybackPipeline::SetPlayerState(PlayerState newPlayerState, bool bSilent)
{
    m_StateLock->Enter();

    // Determine if we need to send an event out
    bool updateState = newPlayerState != m_PlayerState;
    if (updateState)
    {
        if (NULL != m_pEventDispatcher && !bSilent)
        {
            m_PlayerState = newPlayerState;

            if (!m_pEventDispatcher->SendPlayerStateEvent(newPlayerState, 0.0))
            {
                if(!m_pEventDispatcher->SendPlayerMediaErrorEvent(ERROR_JNI_SEND_PLAYER_STATE_EVENT))
                {
                    LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
                }
            }
        }
        else
        {
            m_PlayerState = newPlayerState;
        }
    }

    m_StateLock->Exit();

#if ENABLE_PROGRESS_BUFFER
    if ((updateState && newPlayerState == Stalled && m_bLastProgressValueEOS) ||
        (updateState && newPlayerState == Stalled && m_bHLSPBFull))
#else // ENABLE_PROGRESS_BUFFER
    if ((updateState && newPlayerState == Stalled) ||
        (updateState && newPlayerState == Stalled && m_bHLSPBFull))
#endif // ENABLE_PROGRESS_BUFFER
    {
       Play();
    }
}

/**
 * CGstAudioPlaybackPipeline::IsPlayerState()
 *
 * Synchronously tests if the player state equals to the mentioned
 */
bool CGstAudioPlaybackPipeline::IsPlayerState(PlayerState state)
{
    m_StateLock->Enter();
    bool result = (m_PlayerState == state);
    m_StateLock->Exit();

    return result;
}

/**
 * CGstAudioPlaybackPipeline::IsPlayerPendingState()
 *
 * Synchronously tests if the player pending state equals to the mentioned
 */
bool CGstAudioPlaybackPipeline::IsPlayerPendingState(PlayerState state)
{
    m_StateLock->Enter();
    bool result = (m_PlayerPendingState == state);
    m_StateLock->Exit();

    return result;
}

/**
 * CGstAudioPlaybackPipeline::UpdatePlayerState()
 *
 * Intermediates between Gst pipeline state and our "player" state.  This is called when we get a pipeline
 * state change.
 *
 */
void CGstAudioPlaybackPipeline::UpdatePlayerState(GstState newState, GstState oldState)
{
    m_StateLock->Enter();

    PlayerState newPlayerState = m_PlayerState;
    bool        bSilent = false;

    switch(m_PlayerState)
    {
        case Unknown:
            if((GST_STATE_READY == oldState && GST_STATE_PAUSED == newState) || (GST_STATE_PAUSED == oldState && GST_STATE_PAUSED == newState))
            {
                newPlayerState = Ready;
            }
            break;

        case Ready:
            if(GST_STATE_PAUSED == oldState)
            {
                if(GST_STATE_READY == newState)
                    newPlayerState = Unknown;
                else if(GST_STATE_PLAYING == newState)
                    newPlayerState = Playing;
            }
            break;

        case Playing:
            if(GST_STATE_PLAYING == oldState)
            {
                if(GST_STATE_PAUSED == newState)
                {
                    if(m_PlayerPendingState == Stopped)
                    {
                        m_StallOnPause = false;
                        m_PlayerPendingState = Unknown;
                        newPlayerState = Stopped;
                    }
                    else if (m_StallOnPause && m_PlayerPendingState != Paused)
                    {
                        m_StallOnPause = false;
                        newPlayerState = Stalled;
                    }
                    else if (m_PlayerPendingState == Paused)
                    {
                        m_StallOnPause = false;
                        m_PlayerPendingState = Unknown;
                        newPlayerState = Paused;
                    }
                    else
                    {
                        newPlayerState = Finished;
                    }
                }
            }
            else if(GST_STATE_PAUSED == oldState) // May happen during seek
            {
                if(GST_STATE_PAUSED == newState)
                {
                    if(m_PlayerPendingState == Stopped)
                    {
                        m_StallOnPause = false;
                        m_PlayerPendingState = Unknown;
                        newPlayerState = Stopped;
                    }
                    else if (m_StallOnPause && m_PlayerPendingState != Paused)
                    {
                        m_StallOnPause = false;
                        newPlayerState = Stalled;
                    }
                    else if (m_PlayerPendingState == Paused)
                    {
                        m_StallOnPause = false;
                        m_PlayerPendingState = Unknown;
                        newPlayerState = Paused;
                    }
                }
            }
            break;

        case Paused:
            if(GST_STATE_PAUSED == oldState)
            {
                if(m_PlayerPendingState == Stopped)
                {
                    m_PlayerPendingState = Unknown;
                    newPlayerState = Stopped;
                }
                else
                {
                    if(GST_STATE_PLAYING == newState)
                        newPlayerState = Playing;
                    else if(GST_STATE_READY == newState)
                        newPlayerState = Unknown;
                }
            }
            break;

        case Stopped:
            if(GST_STATE_PAUSED == oldState)
            {
                if (m_PlayerPendingState == Paused && GST_STATE_PAUSED == newState)
                {
                    m_PlayerPendingState = Unknown;
                    newPlayerState = Paused;
                }
                else if(GST_STATE_PLAYING == newState)
                {
                    newPlayerState = Playing;
                }
                else if(GST_STATE_READY == newState)
                {
                    newPlayerState = Unknown;
                }
            }
            break;

        case Stalled:
        {
            if (GST_STATE_PAUSED == oldState && GST_STATE_PLAYING == newState)
                newPlayerState = Playing;
            else if (GST_STATE_PAUSED == oldState && GST_STATE_PAUSED == newState)
            {
                if (m_PlayerPendingState == Stopped)
                {
                    m_PlayerPendingState = Unknown;
                    newPlayerState = Stopped;
                }
                else if (m_PlayerPendingState == Paused)
                {
                    m_PlayerPendingState = Unknown;
                    newPlayerState = Paused;
                }
            }
            break;
        }

        case Finished:
            if(GST_STATE_PLAYING == oldState)
            {
                if(GST_STATE_PAUSED == newState)
                {
                    if(m_PlayerPendingState == Stopped)
                    {
                        m_PlayerPendingState = Unknown;
                        m_bSeekInvoked = false;
                        newPlayerState = Stopped;
                    }
                    // No need to switch to paused state, since Pause is not valid in Finished state
                }
            }
            else if(GST_STATE_PAUSED == oldState)
            {
                if(GST_STATE_PLAYING == newState)
                {
                    // We can go from Finished to Playing only when seek happens (or repeat)
                    // This state change should be silent.
                    newPlayerState = Playing;
                    m_bSeekInvoked = false;
                    bSilent = true;
                }
                else if(GST_STATE_PAUSED == newState)
                {
                    if(m_PlayerPendingState == Stopped)
                    {
                        m_PlayerPendingState = Unknown;
                        m_bSeekInvoked = false;
                        newPlayerState = Stopped;
                    }
                    else
                    {
                        m_bSeekInvoked = false;
                        newPlayerState = Paused;
                    }
                }
            }
            break;

        case Error:
            break;
    }

    SetPlayerState(newPlayerState, bSilent);
    m_StateLock->Exit();
}

//*************************************************************************************************
//* Scanning tracks information
//*************************************************************************************************
void CGstAudioPlaybackPipeline::SendTrackEvent()
{
    if (NULL != m_pEventDispatcher)
    {
        CTrack::Encoding encoding;
        int              channelMask;

        // Detect the encoding type from the information that we have from caps.
        if (m_AudioTrackInfo.mimeType.find("audio/x-raw") != string::npos)
            encoding = CTrack::PCM;
        else if (m_AudioTrackInfo.mimeType.find(CONTENT_TYPE_MPA) != string::npos ||
                 m_AudioTrackInfo.mimeType.find(CONTENT_TYPE_MP3) != string::npos)
        {
            if (m_AudioTrackInfo.mpegversion == 1)
                encoding = (m_AudioTrackInfo.layer == 3) ? CTrack::MPEG1LAYER3 : CTrack::MPEG1AUDIO;
            else if (m_AudioTrackInfo.mpegversion == 4)
                encoding = CTrack::AAC;
            else
                encoding = CTrack::CUSTOM;
        }
        else if (m_AudioTrackInfo.mimeType.find(CONTENT_TYPE_AAC))
        {
            encoding = CTrack::AAC;
        }
        else
            encoding = CTrack::CUSTOM;

        // Detect the channelmask from the number of channels
        switch (m_AudioTrackInfo.channels)
        {
            case 1:
                channelMask = CAudioTrack::FRONT_CENTER;
                break;

            case 2:
                channelMask = CAudioTrack::FRONT_RIGHT | CAudioTrack::FRONT_LEFT;
                break;

            case 4:
                channelMask = CAudioTrack::FRONT_RIGHT | CAudioTrack::FRONT_LEFT | CAudioTrack::REAR_RIGHT | CAudioTrack::REAR_LEFT;
                break;

            case 0:
            default:
                channelMask = CAudioTrack::UNKNOWN;
                break;
        }

        CAudioTrack *p_AudioTrack = new CAudioTrack(m_AudioTrackInfo.trackID,
                                                    m_AudioTrackInfo.mimeType,
                                                    encoding,
                                                    (bool)m_AudioTrackInfo.trackEnabled,
                                                    "und",
                                                    m_AudioTrackInfo.channels,
                                                    channelMask,
                                                    (float)m_AudioTrackInfo.rate);

        if (!m_pEventDispatcher->SendAudioTrackEvent(p_AudioTrack))
        {
            if(!m_pEventDispatcher->SendPlayerMediaErrorEvent(ERROR_JNI_SEND_AUDIO_TRACK_EVENT))
            {
                LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
            }
        }

        delete p_AudioTrack;
    }
}

GstPadProbeReturn CGstAudioPlaybackPipeline::AudioSinkPadProbe(GstPad* pPad, GstPadProbeInfo *pInfo, CGstAudioPlaybackPipeline* pPipeline)
{
    // Make sure we got requested probe
    if ((pInfo->type & GST_PAD_PROBE_TYPE_BUFFER) != GST_PAD_PROBE_TYPE_BUFFER || pInfo->data == NULL)
        return GST_PAD_PROBE_OK;

    GstCaps* pCaps = gst_pad_get_current_caps(pPad);
    if (NULL == pCaps || gst_caps_get_size(pCaps) < 1)
    {
        if (pCaps != NULL)
            gst_caps_unref(pCaps);
        return GST_PAD_PROBE_OK;
    }

    GstStructure *pStructure = gst_caps_get_structure(pCaps, 0);
    pPipeline->m_AudioTrackInfo.mimeType = gst_structure_get_name(pStructure);

    gint trackID;
    gboolean enabled;
    if (!gst_structure_get_boolean(pStructure, "track_enabled", &enabled)) {
        enabled = TRUE; // default to enabled if container doesn't support it
    }
    if (pPipeline->m_pOptions->ForceDefaultTrackID() ||
            !gst_structure_get_int(pStructure, "track_id", &trackID)) {
        // Use default ID in case container doesn't have track IDs
        trackID = DEFAULT_AUDIO_TRACK_ID;
    }
    pPipeline->m_AudioTrackInfo.trackEnabled = enabled;
    pPipeline->m_AudioTrackInfo.trackID = (int64_t)trackID;

    // Don't use shortcut evaluation here. Try to get as much as possible.
    gboolean ready = gst_structure_get_int(pStructure, "channels", &pPipeline->m_AudioTrackInfo.channels) &
                     gst_structure_get_int(pStructure, "rate", &pPipeline->m_AudioTrackInfo.rate);

    if (pPipeline->m_AudioTrackInfo.mimeType.find("mpeg") != string::npos)
    {
        ready &= gst_structure_get_int(pStructure, "mpegversion", &pPipeline->m_AudioTrackInfo.mpegversion);
        gst_structure_get_int(pStructure, "layer", &pPipeline->m_AudioTrackInfo.layer); // Layer is optional.
    }

    if (ready)
    {
        pPipeline->SendTrackEvent();

        if (pPipeline->m_audioSourcePadProbeHID)    // Remove source probe if any because we've got all we need.
        {
            GstPad *pPad = gst_element_get_static_pad(pPipeline->m_Elements[AUDIO_DECODER], "src");
            gst_pad_remove_probe (pPad, pPipeline->m_audioSourcePadProbeHID);
            gst_object_unref(pPad);
        }
    }

    if (pCaps != NULL)
        gst_caps_unref(pCaps);

    return GST_PAD_PROBE_REMOVE;
}

GstPadProbeReturn CGstAudioPlaybackPipeline::AudioSourcePadProbe(GstPad* pPad, GstPadProbeInfo *pInfo, CGstAudioPlaybackPipeline* pPipeline)
{
    GstPadProbeReturn ret = GST_PAD_PROBE_OK;
    GstStructure *pStructure = NULL;
    GstCaps* pCaps = NULL;

    // Make sure we got requested probe
    if ((pInfo->type & GST_PAD_PROBE_TYPE_BUFFER) != GST_PAD_PROBE_TYPE_BUFFER || pInfo->data == NULL)
        goto exit;

    pCaps = gst_pad_get_current_caps(pPad);
    if (NULL == pCaps || gst_caps_get_size(pCaps) < 1)
        goto exit;

    pStructure = gst_caps_get_structure(pCaps, 0);

    // Here we only fill in empty fields. All fields would be empty if this is the only track test probe.
    if (pPipeline->m_AudioTrackInfo.mimeType.empty())
        pPipeline->m_AudioTrackInfo.mimeType = gst_structure_get_name(pStructure);

    if (pPipeline->m_AudioTrackInfo.channels < 0)
        gst_structure_get_int(pStructure, "channels", &pPipeline->m_AudioTrackInfo.channels);

    if (pPipeline->m_AudioTrackInfo.rate < 0)
      gst_structure_get_int(pStructure, "rate", &pPipeline->m_AudioTrackInfo.rate);

    if (pPipeline->m_AudioTrackInfo.mimeType.find("mpeg") != string::npos)
    {
        if (pPipeline->m_AudioTrackInfo.mpegversion < 0)
            gst_structure_get_int(pStructure, "mpegversion", &pPipeline->m_AudioTrackInfo.mpegversion);

        if (pPipeline->m_AudioTrackInfo.layer < 0)
            gst_structure_get_int(pStructure, "layer", &pPipeline->m_AudioTrackInfo.layer);
    }

    pPipeline->SendTrackEvent(); // Send track event anyways. We won't get any more information.

    ret = GST_PAD_PROBE_REMOVE; // Don't discard the data.

exit:
    if (pCaps != NULL)
        gst_caps_unref(pCaps);

    return ret;
}

#if ENABLE_PROGRESS_BUFFER
// This callback is called when progressbuffer runs out of data.
// This can happen when we running out of data during playback, because we cannot download data fast enough.
void CGstAudioPlaybackPipeline::BufferUnderrun()
{
    if (IsPlayerState(Stalled) || IsPlayerState(Ready) || IsPlayerState(Error))
        return;

    GstState state, pending_state;
    gst_element_get_state(m_Elements[PIPELINE], &state, &pending_state, 0);

    bool finished = IsPlayerState(Finished);
    double streamTime;
    GetStreamTime(&streamTime);

    m_StallLock->Enter();
    // Make sure we do not have more data in progress buffer.
    // Stall is valid only in PLAY state, when we do seek, pipeline will be in PAUSED state.
    // Stall is not valid in Finished state, but pipeline will be in PLAY state, when we in Finsihed state.
    bool suspend = m_BufferPosition > 0 &&
                   state == GST_STATE_PLAYING && pending_state != GST_STATE_PAUSED &&
                   !m_bLastProgressValueEOS &&
                   !finished;

    m_StallLock->Exit();

    if (suspend)
    {
        m_StallOnPause = true;
        InternalPause();
    }
}

// We do not need to protect this function with mutex, because we
// call it from only one thread (BusCallback).
// skia-fx: dual-source companion audio cushion check, used by the
// stall-resume condition. TRUE when the companion has buffered at least
// m_dResumeDeltaTime past the playhead, has fully downloaded (EOS), or
// when there is nothing to check (single-source / HLS / no progress
// report yet — the legacy behaviour, so startup can't deadlock on a
// companion that hasn't reported).
bool CGstAudioPlaybackPipeline::IsCompanionAudioCushionOk(double streamTime, double duration)
{
    if (m_pOptions == NULL ||
        m_pOptions->GetPipelineType() != CPipelineOptions::kAudioSourcePipeline ||
        m_pOptions->GetHLSModeEnabled())
        return true;

    m_StallLock->Enter();
    gint64 pos = m_llAudioProgressValuePosition;
    gint64 stop = m_llAudioProgressValueStop;
    gboolean eos = m_bAudioProgressEOS;
    m_StallLock->Exit();

    if (eos || stop <= 0 || pos < 0)
        return true;

    // duration comes from the caller (UpdateBufferPosition already
    // queried it) — a second pipeline-wide duration query per call
    // would be pure waste on the bus thread.
    if (duration <= 0)
        return true;

    double audioBufferPosition = duration * (double)pos / (double)stop;
    return (audioBufferPosition - streamTime) > m_dResumeDeltaTime;
}

void CGstAudioPlaybackPipeline::UpdateBufferPosition()
{
    if (NULL != m_pEventDispatcher && m_llLastProgressValueStop > 0)
    {
        double duration;
        GetDuration(&duration);

        if (!m_pEventDispatcher->SendBufferProgressEvent(duration, m_llLastProgressValueStart,
            m_llLastProgressValueStop, m_llLastProgressValuePosition))
        {
            if(!m_pEventDispatcher->SendPlayerMediaErrorEvent(ERROR_JNI_SEND_BUFFER_PROGRESS_EVENT))
            {
                LOGGER_LOGMSG(LOGGER_ERROR, "Cannot send media error event.\n");
            }
        }

        double bufferPosition = duration * m_llLastProgressValuePosition/m_llLastProgressValueStop;

        double streamTime;
        GetStreamTime(&streamTime);

        m_StallLock->Enter();
        m_BufferPosition = bufferPosition;
        m_StallLock->Exit();

        // We need to unblock when we have atleast data for duration of m_dResumeDeltaTime or
        // if progress buffer got eos, since buffer position will not be updated anymore and no more data will be available.
        // skia-fx: for dual-source pipelines the AUDIO cushion must also
        // be sufficient (or the companion fully downloaded) — resuming on
        // the video cushion alone replays the starvation freeze.
        bool resume = IsPlayerState(Stalled) && ((bufferPosition - streamTime > m_dResumeDeltaTime) || m_bLastProgressValueEOS) && !IsPlayerPendingState(Paused) && !IsPlayerPendingState(Stopped)
            && IsCompanionAudioCushionOk(streamTime, duration);

        if (resume)
        {
            Play();
        }
    }
}

void CGstAudioPlaybackPipeline::HLSBufferStall()
{
    if (!IsPlayerState(Playing))
        return;

    GstState state, pending_state;
    gst_element_get_state(m_Elements[PIPELINE], &state, &pending_state, 0);

    m_StallLock->Enter();
    // Stall is valid only in PLAY state, when we do seek, pipeline will be in PAUSED state.
    bool suspend = (state == GST_STATE_PLAYING) && (pending_state == GST_STATE_VOID_PENDING) && !m_bLastProgressValueEOS && !m_bHLSPBFull;
    m_StallLock->Exit();

    if (suspend)
    {
        m_StallOnPause = true;
        InternalPause();
    }
}

void CGstAudioPlaybackPipeline::HLSBufferResume(bool bEOS)
{
    m_StallLock->Enter();
    if (bEOS)
        m_bLastProgressValueEOS = bEOS;
    bool resume = (IsPlayerState(Stalled) && !IsPlayerPendingState(Paused) && !IsPlayerPendingState(Stopped)) || (m_bLastProgressValueEOS && IsPlayerState(Playing) && !IsPlayerPendingState(Paused) && !IsPlayerPendingState(Stopped));
    m_StallLock->Exit();

    if (resume)
    {
        Play();
    }
}
#endif // ENABLE_PROGRESS_BUFFER
