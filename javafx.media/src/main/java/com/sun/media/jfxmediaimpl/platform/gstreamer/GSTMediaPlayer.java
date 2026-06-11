/*
 * Copyright (c) 2010, 2016, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.media.jfxmediaimpl.platform.gstreamer;

import com.sun.media.jfxmedia.MediaError;
import com.sun.media.jfxmedia.MediaException;
import com.sun.media.jfxmedia.effects.AudioEqualizer;
import com.sun.media.jfxmedia.effects.AudioSpectrum;
import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmedia.control.MediaPlayerOverlay;
import com.sun.media.jfxmediaimpl.NativeMediaPlayer;

/**
 * GStreamer implementation of a MediaPlayer.
 */
final class GSTMediaPlayer extends NativeMediaPlayer {
    private GSTMedia gstMedia = null;
    private float mutedVolume = 1.0f;  // last volume before mute
    private boolean muteEnabled = false;
    private AudioEqualizer audioEqualizer;
    private AudioSpectrum audioSpectrum;

    private GSTMediaPlayer(GSTMedia sourceMedia) {
        super(sourceMedia);
        init();
        gstMedia = sourceMedia;

        int rc = gstInitPlayer(gstMedia.getNativeMediaRef());
        if (0 != rc) {
            dispose();
            throwMediaErrorException(rc, null);
        }

        long mediaRef = gstMedia.getNativeMediaRef();
        audioSpectrum = createNativeAudioSpectrum(gstGetAudioSpectrum(mediaRef));
        audioEqualizer = createNativeAudioEqualizer(gstGetAudioEqualizer(mediaRef));
    }

    GSTMediaPlayer(Locator source) {
        this(new GSTMedia(source));
    }

    @Override
    public AudioEqualizer getEqualizer() {
        return audioEqualizer;
    }

    @Override
    public AudioSpectrum getAudioSpectrum() {
        return audioSpectrum;
    }

    @Override
    public MediaPlayerOverlay getMediaPlayerOverlay() {
        return null; // Not needed
    }

    // FIXME: this should be pushed down to native instead of returning an int value
    private void throwMediaErrorException(int code, String message)
            throws MediaException
    {
        MediaError me = MediaError.getFromCode(code);
        throw new MediaException(message, null, me);
    }

    /**
     * skia-fx: dispose() races events already queued on the FX thread
     * (volume sync, duration queries from READY handling) — every
     * player method must snapshot {@code gstMedia} and go inert when it
     * is gone instead of throwing NullPointerException on the FX thread.
     */
    private GSTMedia mediaOrNull() {
        return gstMedia;
    }

    @Override
    protected long playerGetAudioSyncDelay() throws MediaException {
        GSTMedia media = mediaOrNull();
        if (media == null) return 0;
        long[] audioSyncDelay = new long[1];
        int rc = gstGetAudioSyncDelay(media.getNativeMediaRef(), audioSyncDelay);
        if (0 != rc) {
            throwMediaErrorException(rc, null);
        }
        return audioSyncDelay[0];
    }

    @Override
    protected void playerSetAudioSyncDelay(long delay) throws MediaException {
        GSTMedia media = mediaOrNull();
        if (media == null) return;
        int rc = gstSetAudioSyncDelay(media.getNativeMediaRef(), delay);
        if (0 != rc) {
            throwMediaErrorException(rc, null);
        }
    }

    @Override
    protected void playerPlay() throws MediaException {
        GSTMedia media = mediaOrNull();
        if (media == null) return;
        int rc = gstPlay(media.getNativeMediaRef());
        if (0 != rc) {
            throwMediaErrorException(rc, null);
        }
    }

    @Override
    protected void playerStop() throws MediaException {
        GSTMedia media = mediaOrNull();
        if (media == null) return;
        int rc = gstStop(media.getNativeMediaRef());
        if (0 != rc) {
            throwMediaErrorException(rc, null);
        }
    }

    @Override
    protected void playerPause() throws MediaException {
        GSTMedia media = mediaOrNull();
        if (media == null) return;
        int rc = gstPause(media.getNativeMediaRef());
        if (0 != rc) {
            throwMediaErrorException(rc, null);
        }
    }

    @Override
    protected void playerFinish() throws MediaException {
        GSTMedia media = mediaOrNull();
        if (media == null) return;
        int rc = gstFinish(media.getNativeMediaRef());
        if (0 != rc) {
            throwMediaErrorException(rc, null);
        }
    }

    @Override
    protected float playerGetRate() throws MediaException {
        GSTMedia media = mediaOrNull();
        if (media == null) return 1.0f;
        float[] rate = new float[1];
        int rc = gstGetRate(media.getNativeMediaRef(), rate);
        if (0 != rc) {
            throwMediaErrorException(rc, null);
        }
        return rate[0];
    }

    @Override
    protected void playerSetRate(float rate) throws MediaException {
        GSTMedia media = mediaOrNull();
        if (media == null) return;
        int rc = gstSetRate(media.getNativeMediaRef(), rate);
        if (0 != rc) {
            throwMediaErrorException(rc, null);
        }
    }

    @Override
    protected double playerGetPresentationTime() throws MediaException {
        GSTMedia media = mediaOrNull();
        if (media == null) return 0.0;
        double[] presentationTime = new double[1];
        int rc = gstGetPresentationTime(media.getNativeMediaRef(), presentationTime);
        if (0 != rc) {
            throwMediaErrorException(rc, null);
        }
        return presentationTime[0];
    }

    @Override
    protected boolean playerGetMute() throws MediaException {
        return muteEnabled;
    }

    @Override
    protected synchronized void playerSetMute(boolean enable) throws MediaException {
        if (enable != muteEnabled) {
            if (enable) {
                // Cache the current volume.
                float currentVolume = getVolume();

                // Set the volume to zero.
                playerSetVolume(0);

                // Set the mute flag. It is necessary to do this after
                // calling setVolume() as otherwise the volume will not
                // be set to zero.
                muteEnabled = true;

                // Save the pre-mute volume.
                mutedVolume = currentVolume;
            }
            else {
                // Unset the mute flag. It is necessary to do this before
                // calling setVolume() as otherwise the volume will not
                // be set to the cached value.
                muteEnabled = false;

                // Set the volume to the cached value.
                playerSetVolume(mutedVolume);
            }
        }
    }

    @Override
    protected float playerGetVolume() throws MediaException {
        synchronized(this) {
            if (muteEnabled)
                return mutedVolume;
        }
        GSTMedia media = mediaOrNull();
        if (media == null) return 0.0f;
        float[] volume = new float[1];
        int rc = gstGetVolume(media.getNativeMediaRef(), volume);
        if (0 != rc) {
            throwMediaErrorException(rc, null);
        }
        return volume[0];
    }

    @Override
    protected synchronized void playerSetVolume(float volume) throws MediaException {
        if (!muteEnabled) {
            GSTMedia media = mediaOrNull();
            if (media == null) return;
            int rc = gstSetVolume(media.getNativeMediaRef(), volume);
            if (0 != rc) {
                throwMediaErrorException(rc, null);
            } else {
                mutedVolume = volume;
            }
        } else {
            mutedVolume = volume;
        }
    }

    @Override
    protected float playerGetBalance() throws MediaException {
        GSTMedia media = mediaOrNull();
        if (media == null) return 0.0f;
        float[] balance = new float[1];
        int rc = gstGetBalance(media.getNativeMediaRef(), balance);
        if (0 != rc) {
            throwMediaErrorException(rc, null);
        }
        return balance[0];
    }

    @Override
    protected void playerSetBalance(float balance) throws MediaException {
        GSTMedia media = mediaOrNull();
        if (media == null) return;
        int rc = gstSetBalance(media.getNativeMediaRef(), balance);
        if (0 != rc) {
            throwMediaErrorException(rc, null);
        }
    }

    @Override
    protected double playerGetDuration() throws MediaException {
        GSTMedia media = mediaOrNull();
        if (media == null) return Double.POSITIVE_INFINITY;
        double[] duration = new double[1];
        int rc = gstGetDuration(media.getNativeMediaRef(), duration);
        if (0 != rc) {
            throwMediaErrorException(rc, null);
        }
        if (duration[0] == -1.0) {
            return Double.POSITIVE_INFINITY;
        } else {
            return duration[0];
        }
    }

    @Override
    protected void playerSeek(double streamTime) throws MediaException {
        GSTMedia media = mediaOrNull();
        if (media == null) return;
        int rc = gstSeek(media.getNativeMediaRef(), streamTime);
        if (0 != rc) {
            throwMediaErrorException(rc, null);
        }
    }

    @Override
    protected void playerInit() throws MediaException {
    }

    @Override
    protected void playerDispose() {
        audioEqualizer = null;
        audioSpectrum = null;
        gstMedia = null;
    }

    // Native methods
    private native int gstInitPlayer(long refNativeMedia);
    private native long gstGetAudioEqualizer(long refNativeMedia);
    private native long gstGetAudioSpectrum(long refNativeMedia);
    private native int gstGetAudioSyncDelay(long refNativeMedia, long[] syncDelay);
    private native int gstSetAudioSyncDelay(long refNativeMedia, long delay);
    private native int gstPlay(long refNativeMedia);
    private native int gstPause(long refNativeMedia);
    private native int gstStop(long refNativeMedia);
    private native int gstFinish(long refNativeMedia);
    private native int gstGetRate(long refNativeMedia, float[] rate);
    private native int gstSetRate(long refNativeMedia, float rate);
    private native int gstGetPresentationTime(long refNativeMedia, double[] time);
    private native int gstGetVolume(long refNativeMedia, float[] volume);
    private native int gstSetVolume(long refNativeMedia, float volume);
    private native int gstGetBalance(long refNativeMedia, float[] balance);
    private native int gstSetBalance(long refNativeMedia, float balance);
    private native int gstGetDuration(long refNativeMedia, double[] duration);
    private native int gstSeek(long refNativeMedia, double streamTime);
}
