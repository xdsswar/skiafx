/*
 * Copyright (c) 2010, 2021, Oracle and/or its affiliates. All rights reserved.
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

#ifndef _GST_AUDIO_PLAYBACK_PIPELINE_H_
#define _GST_AUDIO_PLAYBACK_PIPELINE_H_

#include <Common/ProductFlags.h>
#include <stdint.h>
#include <jfxmedia_errors.h>
#include <Utils/JfxCriticalSection.h>
#include <PipelineManagement/Pipeline.h>
#include <gst/gst.h>
#include "GstPipelineFactory.h"
#include "GstElementContainer.h"
#include "GstAudioEqualizer.h"
#include "GstAudioSpectrum.h"
#include <string>

using namespace std;

// Pluggable audio probes and signal handlers
const int AUDIO_DECODER_HAS_SINK_PROBE   = 1 << 0;
const int AUDIO_DECODER_HAS_SOURCE_PROBE = 1 << 1;

#define DURATION_INDEFINITE -1
#define DURATION_UNKNOWN -2

// Taken from progressbuffer.h
#define PB_MESSAGE_BUFFERING        "pb_buffering"
#define PB_MESSAGE_UNDERRUN         "pb_underrun"

#define HLS_PB_MESSAGE_STALL        "hls_pb_stall"
#define HLS_PB_MESSAGE_RESUME       "hls_pb_resume"
#define HLS_PB_MESSAGE_HLS_EOS      "hls_pb_eos"
#define HLS_PB_MESSAGE_FULL         "hls_pb_full"
#define HLS_PB_MESSAGE_NOT_FULL     "hls_pb_not_full"

class CGstAudioPlaybackPipeline;
struct sBusCallbackContent
{
    CGstAudioPlaybackPipeline* m_pPipeline;
    CJfxCriticalSection*       m_DisposeLock;
    bool                       m_bIsDisposed;
    bool                       m_bIsDisposeInProgress;
    bool                       m_bFreeMe;
};

/**
 * class CGstAudioPlaybackPipeline
 *
 * Class representing a GStreamer audio-only pipeline.
 */
class CGstAudioPlaybackPipeline : public CPipeline
{
    friend class CGstPipelineFactory;

public:
    virtual uint32_t    Init();
    virtual uint32_t    PostBuildInit();
    virtual void        Dispose();

    virtual uint32_t    Play();
    virtual uint32_t    Stop();
    virtual uint32_t    Pause();
    virtual uint32_t    Finish();

    virtual uint32_t    Seek(double seek_time);

    virtual uint32_t    GetDuration(double* dDuration);
    virtual uint32_t    GetStreamTime(double* dStreamTime);

    virtual uint32_t    SetRate(float rate);
    virtual uint32_t    GetRate(float* rate);

    virtual uint32_t    SetVolume(float volume);
    virtual uint32_t    GetVolume(float* volume);

    virtual uint32_t    SetBalance(float balance);
    virtual uint32_t    GetBalance(float* balance);

    virtual uint32_t    SetAudioSyncDelay(long millis);
    virtual uint32_t    GetAudioSyncDelay(long* millis);

    virtual CAudioEqualizer*    GetAudioEqualizer();
    virtual CAudioSpectrum*     GetAudioSpectrum();

    virtual bool IsCodecSupported(GstCaps *pCaps);
    virtual bool CheckCodecSupport();
    virtual bool LoadDecoder(GstCaps *pCaps);

    virtual void CheckQueueSize(GstElement *element) {};

    // skia-fx: called the moment a seek is REQUESTED (before it executes, even
    // before coalescing), so a subclass can arm its recovery grace and reset
    // its lag baseline to the target. Without this the recovery watchdog can
    // see the stale pre-seek video PTS against the just-applied new audio
    // position and fire a spurious "lagging" recovery that races the user seek.
    virtual void OnSeekRequested(gint64 seekTimeNs) {};

    // skia-fx: called right after a flushing seek succeeds, so a subclass can
    // prime its video path (the dual-source AV pipeline temporarily lets the
    // video sink render the late post-seek catch-up frames instead of
    // dropping them, then re-locks to the audio master clock once caught up).
    virtual void OnSeekIssued(gint64 seekTimeNs) {};

    // skia-fx: called once per watchdog wake (~2 s) so a subclass can run a
    // soft recovery (the dual-source AV pipeline re-seeks a stalled video
    // chain back onto the still-playing audio instead of freezing).
    virtual void WatchdogTick() {};

    GstElementContainer m_Elements;

protected:
    CGstAudioPlaybackPipeline(const GstElementContainer& elements, int flags, CPipelineOptions* pOptions);
    virtual ~CGstAudioPlaybackPipeline();

    static gboolean     BusCallback(GstBus *pBus, GstMessage *message, sBusCallbackContent* pBusCallbackContent);
    static void         BusCallbackDestroyNotify(sBusCallbackContent* pBusCallbackContent);
    void                SetPlayerState(PlayerState newPlayerState, bool bSilent);
    void                UpdatePlayerState(GstState newState, GstState oldState);
    bool                IsPlayerState(PlayerState state);
    bool                IsPlayerPendingState(PlayerState state);

    sBusCallbackContent* m_pBusCallbackContent;

protected:
    double              m_dResumeDeltaTime;
    float               m_fRate;
    volatile bool       m_bSeekInvoked;
    GstClockTime        m_ulLastStreamTime;
    CGstAudioEqualizer* m_pAudioEqualizer;
    CGstAudioSpectrum*  m_pAudioSpectrum;
    int                 m_audioCodecErrorCode;

    // Stall handling stuff
    volatile bool        m_StallOnPause; // True if paused because of stall condition

    // skia-fx: re-seek ONLY the video chain to a TIME (ns), serialised through
    // m_SeekLock so it can't interleave with a coalesced full SeekPipeline()
    // (a concurrent flushing seek on the shared video chain wedges it).
    // Returns false (no-op) if a user seek is already queued in the coalescer.
    bool                RecoverVideoSeekLocked(gint64 seekTimeNs);

#if ENABLE_LOWLEVELPERF
    // Proportion value of QoS event if enabled:
    // http://gstreamer.freedesktop.org/data/doc/gstreamer/head/gstreamer/html/gstreamer-GstEvent.html#gst-event-new-qos
    // For video streams this will be the value at the sink pad of the video sink and for
    // audio-only streams the value at the sink pad of the audio sink.
    double              m_dUpstreamDataRate;
#endif

private:
    static void         OnParserSrcPadAdded(GstElement *element, GstPad *pad, CGstAudioPlaybackPipeline* pPipeline);
    static GstPadProbeReturn     AudioSourcePadProbe(GstPad* pPad, GstPadProbeInfo *pInfo, CGstAudioPlaybackPipeline* pPipeline);
    static GstPadProbeReturn     AudioSinkPadProbe(GstPad* pPad, GstPadProbeInfo *pInfo, CGstAudioPlaybackPipeline* pPipeline);

    void                SendTrackEvent();
    uint32_t            InternalPause();
    uint32_t            SeekPipeline(gint64 seek_time);

#if ENABLE_PROGRESS_BUFFER
    void                BufferUnderrun();
    void                UpdateBufferPosition();
    void                HLSBufferStall();
    void                HLSBufferResume(bool bEOS);
#endif // ENABLE_PROGRESS_BUFFER

    int                 m_AudioFlags;
    gulong              m_audioSinkPadProbeHID;
    gulong              m_audioSourcePadProbeHID;

    // Stall handling stuff
    CJfxCriticalSection* m_StallLock;
    gdouble              m_BufferPosition;
    bool                 m_bHLSPBFull;

    // Seek/Rate
    CJfxCriticalSection* m_SeekLock;
    gint64               m_LastSeekTime;

    // Incrementally filled structure. Earlier it's filled earlier we send AudioTrack event.
    struct AudioTrackInfo
    {
        gboolean trackEnabled;
        int64_t trackID;
        string  mimeType;
        gint    channels;
        gint    rate;
        gint    mpegversion;
        gint    layer;

        AudioTrackInfo() : trackEnabled(FALSE), trackID(0), channels(-1), rate(-1), mpegversion(-1), layer(-1) {}
    };

    AudioTrackInfo      m_AudioTrackInfo;

    GSource*            m_pBusSource;
    gboolean            m_bIgnoreError;
    bool                m_bResumePlayOnNonzeroRate;

    double              m_dLastReportedDuration;

    bool                m_bSetClock;
    bool                m_bIsClockSet;
    // skia-fx: one-shot guard for the dual-source initial resync seek
    // (see GetStreamTime). Two independent remote sources can reach
    // PLAYING with misaligned segments so the audio-master clock never
    // advances; one flushing seek re-segments them (the same effect a
    // manual seek has). The resync is DEFERRED: it fires from the
    // position-poll path only after the clock has been observed frozen
    // at 0 for several polls in PLAYING. Firing it eagerly from the
    // bus callback at the PLAYING transition (the previous design)
    // raced the dynamic video-chain build — flushing mid-preroll
    // wedged the pipeline in async limbo (ring buffer never restarted,
    // clock pinned at 0, unrecoverable even by user seeks).
    bool                m_bDualSourceInitialResyncDone;
    // Consecutive position polls observed at 0 while PLAYING, and the
    // monotonic time of the first one. GetStreamTime is called from
    // more than the ~100ms Java poll (bus-thread UpdateBufferPosition
    // fires it per BUFFERING message, bursty during startup), so the
    // poll COUNT alone can accumulate in well under the intended ~3s —
    // the detector requires both the count AND real elapsed time.
    int                 m_DualResyncZeroPosPolls;
    gint64              m_DualResyncFirstZeroPosTime;

    // skia-fx: stall/preroll watchdog. A pipeline that sits in Stalled
    // or stuck in preroll with ZERO progress (no download movement, no
    // position movement) for OPENJFX_MEDIA_STALL_TIMEOUT seconds
    // (default 45, 0 disables) posts a bus ERROR instead of freezing
    // the player forever — every silent-hang bug class becomes an
    // ordinary catchable MediaException. One-shot per pipeline.
    GThread*            m_WatchdogThread;
    GMutex              m_WatchdogMutex;
    GCond               m_WatchdogCond;
    bool                m_bWatchdogStop;
    int                 m_WatchdogTimeoutSec;
    void                StartWatchdog();
    void                StopWatchdog();
    static gpointer     WatchdogLoop(gpointer data);

    // skia-fx: player-level seek coalescing. Rapid seeks (slider dragging,
    // programmatic scrubbing) are debounced HERE rather than relying on every
    // app to "seek on mouse release" — only the latest target after a short
    // quiet window (OPENJFX_MEDIA_SEEK_DEBOUNCE_MS, default 180, 0 disables)
    // is actually executed, so a drag-storm can't thrash the (expensive,
    // fragment-refetching) seek path. Active for dual-source A/V; single
    // source keeps the immediate synchronous seek. DoSeekNow() is the actual
    // work, run on the coalescer thread.
    GThread*            m_SeekCoalesceThread;
    GMutex              m_SeekCoalesceMutex;
    GCond               m_SeekCoalesceCond;
    bool                m_bSeekCoalesceStop;
    bool                m_bSeekCoalescePending;
    double              m_SeekCoalesceTargetSec;
    gint64              m_SeekCoalesceLastReqUs;
    int                 m_SeekDebounceMs;
    bool                SeekCoalescingEnabled();
    void                StartSeekCoalescer();
    void                StopSeekCoalescer();
    static gpointer     SeekCoalesceLoop(gpointer data);
    uint32_t            DoSeekNow(double dSeekTime);

    CJfxCriticalSection* m_StateLock;

#if ENABLE_PROGRESS_BUFFER
    gint64    m_llLastProgressValueStart;
    gint64    m_llLastProgressValuePosition;
    gint64    m_llLastProgressValueStop;
    gboolean  m_bLastProgressValueEOS;

    // skia-fx: dual-source companion-audio buffering state, tracked
    // SEPARATELY from the primary's progress values above. The
    // companion's BUFFERING messages must not feed the shared resume
    // math (its tiny stream reports "far ahead" and causes premature
    // resumes), but ignoring its UNDERRUN entirely meant a starving
    // companion froze playback silently. The companion's progress is
    // recorded here and the stall-resume condition requires BOTH
    // cushions (video AND audio-or-EOS). See UpdateBufferPosition /
    // IsCompanionAudioCushionOk.
    gint64    m_llAudioProgressValuePosition;
    gint64    m_llAudioProgressValueStop;
    gboolean  m_bAudioProgressEOS;

    bool      IsCompanionAudioCushionOk(double streamTime, double duration);
#endif // ENABLE_PROGRESS_BUFFER
};

#endif  //_GST_AUDIO_PLAYBACK_PIPELINE_H_
