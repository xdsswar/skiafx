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

#ifndef _GST_AV_PLAYBACK_PIPELINE_H_
#define _GST_AV_PLAYBACK_PIPELINE_H_

#include <Common/ProductFlags.h>
#include <gst/gst.h>
#include <PipelineManagement/PipelineOptions.h>
#include "GstAudioPlaybackPipeline.h"
#include "GstPipelineFactory.h"


/**
 * class CGstAVPlaybackPipeline
 *
 * Class representing a GStreamer audio-video pipeline.
 */
class CGstAVPlaybackPipeline : public CGstAudioPlaybackPipeline
{
    friend class CGstPipelineFactory;

public:
    virtual uint32_t     Init();
    virtual uint32_t     PostBuildInit();
    virtual void         Dispose();

    virtual bool IsCodecSupported(GstCaps *pCaps);
    virtual bool CheckCodecSupport();
    virtual bool LoadDecoder(GstCaps *pCaps);
    // skia-fx: swap the preset audio decoder for ffmpegwrapper when the
    // demuxer announces a codec the preset can't decode (Opus/Vorbis/
    // FLAC in MP4). The audio analogue of LoadDecoder.
    void         SwapAudioDecoderIfNeeded(GstCaps *pCaps);

    virtual void CheckQueueSize(GstElement *element);
    virtual void OnSeekRequested(gint64 seekTimeNs);
    virtual void OnSeekIssued(gint64 seekTimeNs);
    virtual void WatchdogTick();
    void         ArmVideoPrime(gint64 seekTimeNs);

    void         SetEncodedVideoFrameRate(float frameRate);

protected:
    CGstAVPlaybackPipeline(const GstElementContainer& elements, int audioFlags, CPipelineOptions* pOptions);
    virtual ~CGstAVPlaybackPipeline();

private:
    static void     on_pad_added(GstElement *element, GstPad *pad, CGstAVPlaybackPipeline* pPipeline);
    static void     no_more_pads(GstElement *element, CGstAVPlaybackPipeline* pPipeline);
    static void     queue_overrun(GstElement *element, CGstAVPlaybackPipeline *pPipeline);
    static void     queue_underrun(GstElement *element, CGstAVPlaybackPipeline *pPipeline);

    static GstFlowReturn     OnAppSinkPreroll(GstElement* pElem, CGstAVPlaybackPipeline* pPipeline);
    static GstFlowReturn     OnAppSinkHaveFrame(GstElement* pElem, CGstAVPlaybackPipeline* pPipeline);
    static void     OnAppSinkVideoFrameDiscont(CGstAVPlaybackPipeline* pPipeline, GstSample *pSample);
    static GstPadProbeReturn VideoDecoderSrcProbe(GstPad* pPad, GstPadProbeInfo *pInfo, CGstAVPlaybackPipeline* pPipeline);

    inline float    GetEncodedVideoFrameRate()
    {
        return m_EncodedVideoFrameRate;
    }

private:
    gboolean                m_SendFrameSizeEvent;
    gint                    m_FrameWidth;
    gint                    m_FrameHeight;
    gulong                  m_videoDecoderSrcProbeHID;
    gfloat                  m_EncodedVideoFrameRate;
    int                     m_videoCodecErrorCode;
    GstClockTime            m_FirstPTS;

    // skia-fx: post-seek video catch-up. After a flushing seek the HD video
    // fragment must be fetched and decoded from its keyframe up to the seek
    // target while the audio master clock is already advancing, so the first
    // displayable video frames arrive "late". With the sink clock-synced
    // those late frames are dropped by QoS and the picture freezes. While
    // priming we turn the video sink's sync OFF (late frames render instead
    // of being dropped) and re-lock to the clock once the video PTS has
    // caught up to the audio position. -1 origin = not priming.
    volatile bool           m_bSeekVideoPrime;
    gint64                  m_SeekPrimeTargetNs;

    // skia-fx: video-stall recovery. If the video chain produces no frame for
    // a while *and* the audio master keeps advancing (so it is a video-only
    // stall, not a pause/buffering), the watchdog re-seeks ONLY the video
    // chain back onto the live audio position — re-fetching the fragment and
    // re-priming catch-up — instead of leaving a frozen picture. Bounded
    // attempts (reset whenever a video frame arrives) so it can't loop.
    volatile gint64         m_lastVideoFrameMonoUs;  // monotonic time of last frame
    volatile gint64         m_lastVideoFramePtsNs;   // stream PTS of last frame
    gint64                  m_seekIssuedMonoUs;      // monotonic time of last (re)seek
    gint64                  m_watchPrevAudioNs;
    int                     m_videoRecoverAttempts;
    // Consecutive watchdog ticks the player has been continuously PLAYING
    // (reset whenever it isn't). Recovery only acts after several of these so
    // it never fights the buffering system during startup / stall flapping —
    // a video underrun there already drives the proper whole-pipeline stall.
    int                     m_consecPlayingTicks;
};

#endif  //_GST_AV_PLAYBACK_PIPELINE_H_
