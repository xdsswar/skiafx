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

package javafx.scene.media;

import com.sun.media.jfxmedia.MetadataParser;
import com.sun.media.jfxmediaimpl.MediaFfmpegConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javafx.application.Platform;
import javafx.beans.NamedArg;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ObjectPropertyBase;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.scene.image.Image;
import javafx.util.Duration;

import com.sun.media.jfxmedia.locator.Locator;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import com.sun.media.jfxmedia.events.MetadataListener;

/**
 * The <code>Media</code> class represents a media resource. It is instantiated
 * from the string form of a source URI. Information about the media such as
 * duration, metadata, tracks, and video resolution may be obtained from a
 * <code>Media</code> instance. The media information is obtained asynchronously
 * and so not necessarily available immediately after instantiation of the class.
 * All information should however be available if the instance has been
 * associated with a {@link MediaPlayer} and that player has transitioned to
 * {@link MediaPlayer.Status#READY} status. To be notified when metadata or
 * {@link Track}s are added, observers may be registered with the collections
 * returned by {@link #getMetadata()}and {@link #getTracks()}, respectively.
 *
 * <p>The same <code>Media</code> object may be shared among multiple
 * <code>MediaPlayer</code> objects. Such a shared instance might manage a single
 * copy of the source media data to be used by all players, or it might require a
 * separate copy of the data for each player. The choice of implementation will
 * not however have any effect on player behavior at the interface level.</p>
 *
 * @see MediaPlayer
 * @see MediaException
 * @since JavaFX 2.0
 */
public final class Media {

    // ==================================================================
    // skia-fx — global media configuration.
    //
    // Two pieces of process-wide config every skia-fx app deals with
    // live as static state on this class:
    //
    //   1. Decode method — CPU vs GPU vs AUTO. See {@link DecodeMethod}.
    //   2. The ffmpeg DLL directory. See {@link #setFfmpegDirectory}.
    //
    // Both are read at media-init time, so they must be set BEFORE
    // the first Media instance is constructed (typically from
    // {@code Application.init()}). Setting them later won't crash but
    // won't take effect on already-constructed streams.
    //
    // Each piece has a `set...` and `get...` static method, plus a
    // public {@code String} constant naming the underlying system
    // property — apps that prefer the command-line route can use
    // {@code -D<property>=<value>} at JVM start with identical effect.
    // ==================================================================

    /** Decode strategy. See {@link Media#setDecodeMethod}. */
    public enum DecodeMethod {
        /** Best available on this machine. The runtime picks GPU
         *  paths when they work and silently falls through to CPU
         *  otherwise. The default. */
        AUTO,
        /** Prefer GPU paths; transparently fall back to CPU on a
         *  per-layer basis when a specific GPU step can't init. */
        GPU_PREFERRED,
        /** Strictly require GPU. Playback fails fast if hwaccel or
         *  zero-copy can't initialise. Useful for perf testing. */
        GPU,
        /** Force software decoding everywhere — works on machines
         *  with no usable GPU (servers, VMs without GPU passthrough,
         *  broken drivers). */
        CPU
    }

    /** System property name read by the runtime to pick the decode
     *  mode. {@link #setDecodeMethod} writes this property as a
     *  side-effect, so launching with
     *  {@code java -Dskia.media.decode=CPU ...} is equivalent. */
    public static final String DECODE_METHOD_PROPERTY = "skia.media.decode";

    /** System property name for the ffmpeg DLL directory.
     *  {@link #setFfmpegDirectory} writes this property; the native
     *  loader reads it at first {@code Media} construction. Set via
     *  {@code java -Dopenjfx.media.ffmpeg.dir=C:/ffmpeg/bin ...} for
     *  the same effect. */
    public static final String FFMPEG_DIR_PROPERTY = "openjfx.media.ffmpeg.dir";

    /**
     * Sets how skia-fx media streams created from this point forward
     * decode and upload their frames.
     *
     * <p>Call this from {@code Application.init()} or before
     * constructing the first {@link Media} — already-running streams
     * keep their original choice. CPU mode is propagated down to the
     * native gstreamer plugins via the
     * {@code OPENJFX_MEDIA_USE_HWACCEL} env var, so a runtime switch
     * to CPU disables ffmpeg's D3D11VA hwaccel on the next decoder
     * open even when the JVM was launched without an explicit env
     * var.</p>
     *
     * @param method new decode method; {@code null} resets to
     *               {@link DecodeMethod#AUTO}.
     */
    public static void setDecodeMethod(DecodeMethod method) {
        DecodeMethod m = (method != null) ? method : DecodeMethod.AUTO;
        System.setProperty(DECODE_METHOD_PROPERTY, m.name());
        // Push the choice down to native via OPENJFX_MEDIA_USE_HWACCEL
        // so the ffmpeg producer follows suit. Skips silently when
        // jfxmedia.dll isn't loaded yet — the propagation will run
        // again when MediaFfmpegConfig.initialize() fires later.
        try {
            com.sun.media.jfxmediaimpl.MediaFfmpegConfig.propagateDecodeMode();
        } catch (Throwable ignored) {
            // Pre-load — propagation happens on initialize() instead.
        }
    }

    /**
     * @return the currently-effective decode method. Reads the
     *         {@value #DECODE_METHOD_PROPERTY} system property and
     *         falls back to {@link DecodeMethod#AUTO} when unset
     *         or unrecognised.
     */
    public static DecodeMethod getDecodeMethod() {
        String v = System.getProperty(DECODE_METHOD_PROPERTY);
        if (v == null || v.isEmpty()) return DecodeMethod.AUTO;
        try {
            return DecodeMethod.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignore) {
            return DecodeMethod.AUTO;
        }
    }

    /**
     * Sets the directory the runtime looks in for the ffmpeg DLLs
     * (avcodec, avutil, avformat, swresample, …). Call this once,
     * before constructing the first {@link Media}, when shipping
     * ffmpeg alongside the app instead of relying on system PATH.
     *
     * <p>Writes the {@value #FFMPEG_DIR_PROPERTY} system property;
     * equivalent to launching with
     * {@code -Dopenjfx.media.ffmpeg.dir=...}.</p>
     *
     * @param dir absolute path to the directory holding the ffmpeg
     *            DLLs/SOs. {@code null} or empty clears the override
     *            and lets the runtime fall back to env var / PATH.
     */
    public static void setFfmpegDirectory(String dir) {
        if (dir == null || dir.isEmpty()) {
            System.clearProperty(FFMPEG_DIR_PROPERTY);
        } else {
            System.setProperty(FFMPEG_DIR_PROPERTY, dir);
        }
    }

    /** @return the currently-configured ffmpeg directory, or
     *          {@code null} if neither the property nor the
     *          {@code OPENJFX_MEDIA_FFMPEG_DIR} env var is set. */
    public static String getFfmpegDirectory() {
        String v = System.getProperty(FFMPEG_DIR_PROPERTY);
        if (v != null && !v.isEmpty()) return v;
        String env = System.getenv("OPENJFX_MEDIA_FFMPEG_DIR");
        return (env != null && !env.isEmpty()) ? env : null;
    }

    /**
     * Reports whether the ffmpeg runtime libraries are available to the
     * media engine. The first call attempts to locate and load them from
     * the configured directory (see {@link #setFfmpegDirectory}), the
     * {@code OPENJFX_MEDIA_FFMPEG_DIR} environment variable, or the
     * system path; the result is cached.
     *
     * <p>When ffmpeg is unavailable, common formats still play through
     * the platform decoders (MP4/AAC/H.264, MP3, WAV). Formats decoded
     * by ffmpeg — WebM/MKV (VP8/VP9/AV1, Opus, Vorbis) and dual-source
     * {@code Media(audio, video)} audio — require it, and attempting to
     * play them surfaces a {@code MediaException}. Use this method to
     * detect the situation up front and inform the user.</p>
     *
     * <p>An ffmpeg whose ABI does not match the engine's expectations is
     * refused (reported as unavailable) rather than risk corrupting
     * playback.</p>
     *
     * @return {@code true} when ffmpeg is loaded and usable
     */
    public static boolean isFfmpegAvailable() {
        try {
            return MediaFfmpegConfig.initialize(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Returns a human-readable description of the ffmpeg runtime's load
     * state: where the libraries were loaded from and their versions on
     * success, or the precise reason the load failed (libraries not
     * found, ABI mismatch, mixed builds from different ffmpeg versions).
     * Intended for diagnostics and error reporting alongside
     * {@link #isFfmpegAvailable()}.
     *
     * <p>This API is part of the skia-fx fork and is experimental.</p>
     *
     * @return the loader status message, or {@code null} when no load
     *         has been attempted yet
     * @see #isFfmpegAvailable()
     * @see #setFfmpegDirectory(String)
     */
    public static String getFfmpegStatus() {
        try {
            return MediaFfmpegConfig.getStatus();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A property set to a MediaException value when an error occurs.
     * If <code>error</code> is non-<code>null</code>, then the media could not
     * be loaded and is not usable. If {@link #onErrorProperty onError} is non-<code>null</code>,
     * it will be invoked when the <code>error</code> property is set.
     *
     * @see MediaException
     */
    private ReadOnlyObjectWrapper<MediaException> error;

    private void setError(MediaException value) {
        if (getError() == null) {
            errorPropertyImpl().set(value);
        }
    }

    /**
     * Return any error encountered in the media.
     * @return a {@link MediaException} or <code>null</code> if there is no error.
     */
    public final MediaException getError() {
        return error == null ? null : error.get();
    }

    public ReadOnlyObjectProperty<MediaException> errorProperty() {
        return errorPropertyImpl().getReadOnlyProperty();
    }

    private ReadOnlyObjectWrapper<MediaException> errorPropertyImpl() {
        if (error == null) {
            error = new ReadOnlyObjectWrapper<>() {

                @Override
                protected void invalidated() {
                    if (getOnError() != null) {
                        Platform.runLater(getOnError());
                    }
                }

                @Override
                public Object getBean() {
                    return Media.this;
                }

                @Override
                public String getName() {
                    return "error";
                }
            };
        }
        return error;
    }
    /**
     * Event handler called when an error occurs. This will happen
     * if a malformed or invalid URL is passed to the constructor or there is
     * a problem accessing the URL.
     */
    private ObjectProperty<Runnable> onError;

    /**
     * Set the event handler to be called when an error occurs.
     * @param value the error event handler.
     */
    public final void setOnError(Runnable value) {
        onErrorProperty().set(value);
    }

    /**
     * Retrieve the error handler to be called if an error occurs.
     * @return the error handler or <code>null</code> if none is defined.
     */
    public final Runnable getOnError() {
        return onError == null ? null : onError.get();
    }

    public ObjectProperty<Runnable> onErrorProperty() {
        if (onError == null) {
            onError = new ObjectPropertyBase<>() {

                @Override
                protected void invalidated() {
                    /*
                     * if we have an existing error condition schedule the handler to be
                     * called immediately. This way the client app does not have to perform
                     * an explicit error check.
                     */
                    if (get() != null && getError() != null) {
                        Platform.runLater(get());
                    }
                }

                @Override
                public Object getBean() {
                    return Media.this;
                }

                @Override
                public String getName() {
                    return "onError";
                }
            };
        }
        return onError;
    }

    private MetadataListener metadataListener = new _MetadataListener();

    /**
     * An {@link ObservableMap} of metadata which can contain information about
     * the media. Metadata entries use {@link String}s for keys and contain
     * {@link Object} values. This map is unmodifiable: its contents or stored
     * values cannot be changed.
     */
    // FIXME: define standard metadata keys and the corresponding objects types
    // FIXME: figure out how to make the entries read-only to observers, we'll
    //        need to enhance javafx.collections a bit to accomodate this
    private ObservableMap<String, Object> metadata;

    /**
     * Retrieve the metadata contained in this media source. If there are
     * no metadata, the returned {@link ObservableMap} will be empty.
     * @return the metadata contained in this media source.
     */
    public final ObservableMap<String, Object> getMetadata() {
        return metadata;
    }

    private final ObservableMap<String,Object> metadataBacking = FXCollections.observableMap(new HashMap<String,Object>());
    /**
     * The width in pixels of the source media.
     * This may be zero if the media has no width, e.g., when playing audio,
     * or if the width is currently unknown which may occur with streaming
     * media.
     * @see height
     */
    private ReadOnlyIntegerWrapper width;


    final void setWidth(int value) {
        widthPropertyImpl().set(value);
    }

    /**
     * Retrieve the width in pixels of the media.
     * @return the media width or zero if the width is undefined or unknown.
     */
    public final int getWidth() {
        return width == null ? 0 : width.get();
    }

    public ReadOnlyIntegerProperty widthProperty() {
        return widthPropertyImpl().getReadOnlyProperty();
    }

    private ReadOnlyIntegerWrapper widthPropertyImpl() {
        if (width == null) {
            width = new ReadOnlyIntegerWrapper(this, "width");
        }
        return width;
    }
    /**
     * The height in pixels of the source media.
     * This may be zero if the media has no height, e.g., when playing audio,
     * or if the height is currently unknown which may occur with streaming
     * media.
     * @see width
     */
    private ReadOnlyIntegerWrapper height;


    final void setHeight(int value) {
        heightPropertyImpl().set(value);
    }

    /**
     * Retrieve the height in pixels of the media.
     * @return the media height or zero if the height is undefined or unknown.
     */
    public final int getHeight() {
        return height == null ? 0 : height.get();
    }

    public ReadOnlyIntegerProperty heightProperty() {
        return heightPropertyImpl().getReadOnlyProperty();
    }

    private ReadOnlyIntegerWrapper heightPropertyImpl() {
        if (height == null) {
            height = new ReadOnlyIntegerWrapper(this, "height");
        }
        return height;
    }
    /**
     * The duration in seconds of the source media. If the media duration is
     * unknown then this property value will be {@link Duration#UNKNOWN}.
     */
    private ReadOnlyObjectWrapper<Duration> duration;

    final void setDuration(Duration value) {
        durationPropertyImpl().set(value);
    }

    /**
     * Retrieve the duration in seconds of the media.
     * @return the duration of the media, {@link Duration#UNKNOWN} if unknown or {@link Duration#INDEFINITE} for live streams
     */
    public final Duration getDuration() {
        return duration == null || duration.get() == null ? Duration.UNKNOWN : duration.get();
    }

    public ReadOnlyObjectProperty<Duration> durationProperty() {
        return durationPropertyImpl().getReadOnlyProperty();
    }

    private ReadOnlyObjectWrapper<Duration> durationPropertyImpl() {
        if (duration == null) {
            duration = new ReadOnlyObjectWrapper<>(this, "duration");
        }
        return duration;
    }
    /**
     * An <code>ObservableList</code> of tracks contained in this media object.
     * A <code>Media</code> object can contain multiple tracks, such as a video track
     * with several audio track. This list is unmodifiable: the contents cannot
     * be changed.
     * @see Track
     */
    private ObservableList<Track> tracks;

    /**
     * Retrieve the tracks contained in this media source. If there are
     * no tracks, the returned {@link ObservableList} will be empty.
     * @return the tracks contained in this media source.
     */
    public final ObservableList<Track> getTracks() {
        return tracks;
    }
    private final ObservableList<Track> tracksBacking = FXCollections.observableArrayList();

    /**
     * The markers defined on this media source. A marker is defined to be a
     * mapping from a name to a point in time between the beginning and end of
     * the media.
     */
    private ObservableMap<String, Duration> markers = FXCollections.observableMap(new HashMap<String,Duration>());

    /**
     * Retrieve the markers defined on this <code>Media</code> instance. If
     * there are no markers the returned {@link ObservableMap} will be empty.
     * Programmatic markers may be added by inserting entries in the returned
     * <code>Map</code>.
     *
     * @return the markers defined on this media source.
     */
    public final ObservableMap<String, Duration> getMarkers() {
        return markers;
    }

    /**
     * Constructs a <code>Media</code> instance.  This is the only way to
     * specify the media source. The source must represent a valid <code>URI</code>
     * and is immutable. Only HTTP, HTTPS, FILE, and JAR <code>URL</code>s are supported. If the
     * provided URL is invalid then an exception will be thrown.  If an
     * asynchronous error occurs, the {@link #errorProperty error} property will be set. Listen
     * to this property to be notified of any such errors.
     *
     * <p>If the source uses a non-blocking protocol such as FILE, then any
     * problems which can be detected immediately will cause a <code>MediaException</code>
     * to be thrown. Such problems include the media being inaccessible or in an
     * unsupported format. If however a potentially blocking protocol such as
     * HTTP is used, then the connection will be initialized asynchronously so
     * that these sorts of errors will be signaled by setting the {@link #errorProperty error}
     * property.</p>
     *
     * <p>Constraints:
     * <ul>
     * <li>The supplied URI must conform to RFC-2396 as required by
     * <A href="https://docs.oracle.com/javase/8/docs/api/java/net/URI.html">java.net.URI</A>.</li>
     * <li>Only HTTP, HTTPS, FILE, and JAR URIs are supported.</li>
     * </ul>
     *
     * <p>See <A href="https://docs.oracle.com/javase/8/docs/api/java/net/URI.html">java.net.URI</A>
     * for more information about URI formatting in general.
     * JAR URL syntax is specified in <a href="https://docs.oracle.com/javase/8/docs/api/java/net/JarURLConnection.html">java.net.JarURLConnection</A>.
     *
     * @param source The URI of the source media.
     * @throws NullPointerException if the URI string is <code>null</code>.
     * @throws IllegalArgumentException if the URI string does not conform to RFC-2396
     * or, if appropriate, the Jar URL specification, or is in a non-compliant
     * form which cannot be modified to a compliant form.
     * @throws IllegalArgumentException if the URI string has a <code>null</code>
     * scheme.
     * @throws UnsupportedOperationException if the protocol specified for the
     * source is not supported.
     * @throws MediaException if the media source cannot be connected
     * (type {@link MediaException.Type#MEDIA_INACCESSIBLE}) or is not supported
     * (type {@link MediaException.Type#MEDIA_UNSUPPORTED}).
     */
    public Media(@NamedArg("source") String source) {
        this(source, null, null, null);
    }

    /**
     * Skia-fx: construct a Media with a separate audio source.
     *
     * <p>Use when audio and video live at different URLs (e.g.
     * remuxed HLS variants, an external audio dub for a silent video,
     * or asset-pipeline workflows that ship A/V separately). The
     * {@code videoSource}'s audio track — if any — is muted when the
     * stream is played, and the {@code audioSource} drives audio
     * playback. The two pipelines are sync-corrected internally to
     * within ~1 video frame.</p>
     *
     * @param audioSource URI of the audio-only companion stream;
     *                    may be {@code null} or empty to behave
     *                    like {@link #Media(String)}.
     * @param videoSource URI of the primary (video) stream — must be
     *                    non-null. Same URI rules as
     *                    {@link #Media(String)}.
     */
    public Media(@NamedArg("audioSource") String audioSource,
                 @NamedArg("videoSource") String videoSource) {
        this(videoSource, (audioSource != null && !audioSource.isEmpty())
                              ? audioSource : null,
             null, null);
    }

    /**
     * Skia-fx: dual-source variant with HTTP headers + optional User-Agent
     * applied to BOTH streams.
     *
     * @param audioSource URI of the audio-only companion stream
     *                    (nullable / empty → single-source behaviour).
     * @param videoSource URI of the primary (video) stream.
     * @param headers     map of HTTP request headers applied to remote
     *                    sources. The well-known key {@code "User-Agent"}
     *                    is honoured if present; otherwise the engine
     *                    default User-Agent is used. {@code null}
     *                    behaves like the no-headers overload.
     */
    public Media(@NamedArg("audioSource") String audioSource,
                 @NamedArg("videoSource") String videoSource,
                 @NamedArg("headers")     Map<String, String> headers) {
        this(videoSource, (audioSource != null && !audioSource.isEmpty())
                              ? audioSource : null,
             headers, extractUserAgent(headers));
    }

    /** Private chained constructor — single source of truth for
     *  initialisation. Public constructors all flow through here so
     *  there's one code path that creates the {@code Locator}s, kicks
     *  off the metadata parser, etc. */
    private Media(String videoSource, String audioSource,
                  Map<String, String> headers,
                  String userAgent) {
        this.source      = videoSource;
        this.audioSource = audioSource;
        if (headers != null && !headers.isEmpty()) {
            this.httpHeaders.putAll(headers);
        }
        this.userAgent = userAgent;

        URI uri = null;
        try {
            // URI will throw NPE if videoSource == null: do not catch it!
            uri = new URI(videoSource);
        } catch(URISyntaxException use) {
            throw new IllegalArgumentException(use);
        }

        metadata = FXCollections.unmodifiableObservableMap(metadataBacking);
        tracks = FXCollections.unmodifiableObservableList(tracksBacking);

        Locator locator = null;
        try {
            locator = new com.sun.media.jfxmedia.locator.Locator(uri);
            applyHttpConfig(locator);
            jfxLocator = locator;
            if (locator.canBlock()) {
                InitLocator locatorInit = new InitLocator();
                Thread t = new Thread(locatorInit);
                t.setDaemon(true);
                t.start();
            } else {
                locator.init();
                runMetadataParser();
            }
        } catch(URISyntaxException use) {
            throw new IllegalArgumentException(use);
        } catch(FileNotFoundException fnfe) {
            throw new MediaException(MediaException.Type.MEDIA_UNAVAILABLE, fnfe.getMessage());
        } catch(IOException ioe) {
            throw new MediaException(MediaException.Type.MEDIA_INACCESSIBLE, ioe.getMessage());
        } catch(com.sun.media.jfxmedia.MediaException me) {
            throw new MediaException(MediaException.Type.MEDIA_UNSUPPORTED, me.getMessage());
        }

        // Skia-fx dual-source: just stash the companion URL on the
        // primary Locator. The native pipeline factory (CLocator +
        // GstAVPlaybackPipeline) reads it during pipeline build and
        // wires a second source bin into the same GstPipeline. No
        // second Java Locator is needed — that path was removed
        // when dual-source sync moved to the native side, where
        // GStreamer's shared GstClock can do real lip-sync.
        if (audioSource != null && locator != null) {
            locator.setCompanionAudioUrl(audioSource);
        }
    }

    /** Pulls the conventional "User-Agent" header out of a map, if
     *  present. Case-insensitive — accepts "User-Agent" /
     *  "user-agent" / "USER-AGENT". */
    private static String extractUserAgent(Map<String, String> headers) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null
             && "User-Agent".equalsIgnoreCase(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Push the current HTTP config (user-agent + headers) onto a
     *  {@link Locator}. The native side reads these properties when
     *  it constructs the http source element (e.g. {@code souphttpsrc}). */
    private void applyHttpConfig(Locator locator) {
        if (userAgent != null && !userAgent.isEmpty()) {
            try {
                locator.setUserAgent(userAgent);
            } catch (Throwable ignored) {
                // Locator from older builds might not expose setUserAgent —
                // fall through to system property as a soft fallback so
                // requests at least use one stable UA per process.
                System.setProperty("openjfx.media.userAgent", userAgent);
            }
        }
        if (!httpHeaders.isEmpty()) {
            try {
                locator.setHttpHeaders(new LinkedHashMap<>(httpHeaders));
            } catch (Throwable ignored) {
                // Same fallback: older Locator without per-instance
                // headers ignores the call. Live with the headers
                // being unset rather than throwing.
            }
        }
    }

    private void runMetadataParser() {
        try {
            jfxParser = com.sun.media.jfxmedia.MediaManager.getMetadataParser(jfxLocator);
            jfxParser.addListener(metadataListener);
            jfxParser.startParser();
        } catch (Exception e) {
            jfxParser = null;
        }
    }

    /**
     * The source URI of the media;
     */
    private final String source;

    /**
     * Retrieve the source URI of the media.
     * @return the media source URI as a {@link String}.
     */
    public String getSource() {
        return source;
    }

    /**
     * Skia-fx: optional companion audio source URI. {@code null} for
     * the common single-container case.
     */
    private final String audioSource;

    /**
     * @return the companion audio source URI when this Media was
     *         constructed with a separate audio stream, otherwise
     *         {@code null}.
     */
    public String getAudioSource() {
        return audioSource;
    }

    /**
     * Skia-fx: HTTP request headers applied to remote sources (both
     * the primary and the audio companion, if any). Always
     * non-null — empty when no headers were set.
     */
    private final LinkedHashMap<String, String> httpHeaders =
        new LinkedHashMap<>();

    /**
     * Skia-fx: HTTP {@code User-Agent}. {@code null} = engine default.
     */
    private volatile String userAgent;

    /**
     * @return an unmodifiable view of the HTTP request headers
     *         currently configured on this Media. Never null.
     */
    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(httpHeaders);
    }

    /**
     * @return the configured HTTP {@code User-Agent}, or {@code null}
     *         when the engine default is in effect.
     */
    public String getUserAgent() {
        return userAgent;
    }

    /**
     * Sets the {@code User-Agent} for HTTP requests to remote
     * sources. Effective only if called <em>before</em> the
     * {@link MediaPlayer} starts loading — the native HTTP source
     * reads it once when negotiating the connection.
     *
     * @param userAgent the User-Agent string, or {@code null}
     *                  to clear and use the engine default.
     */
    public void setUserAgent(String userAgent) {
        this.userAgent = (userAgent != null && !userAgent.isEmpty()) ? userAgent : null;
        if (jfxLocator != null) applyHttpConfig(jfxLocator);
    }

    /**
     * Sets a single HTTP request header. Repeated calls add to the
     * map; pass {@code null} as the value to remove a previously-set
     * header. Like {@link #setUserAgent}, effective only before
     * loading begins.
     */
    public void setHeader(String name, String value) {
        if (name == null || name.isEmpty()) return;
        if (value == null) {
            httpHeaders.remove(name);
        } else {
            httpHeaders.put(name, value);
            if ("User-Agent".equalsIgnoreCase(name)) {
                this.userAgent = value;
            }
        }
        if (jfxLocator != null) applyHttpConfig(jfxLocator);
    }

    /**
     * Replace all HTTP headers with the entries in {@code headers}.
     * Pass {@code null} or an empty map to clear all headers.
     */
    public void setHeaders(Map<String, String> headers) {
        httpHeaders.clear();
        if (headers != null) httpHeaders.putAll(headers);
        String ua = extractUserAgent(headers);
        if (ua != null) this.userAgent = ua;
        if (jfxLocator != null) applyHttpConfig(jfxLocator);
    }

    /**
     * Locator used by the jfxmedia player, MediaPlayer needs access to this
     */
    private final Locator jfxLocator;
    Locator retrieveJfxLocator() {
        return jfxLocator;
    }

    private MetadataParser jfxParser;

    private Track getTrackWithID(long trackID) {
        for (Track track : tracksBacking) {
            if (track.getTrackID() == trackID) {
                return track;
            }
        }
        return null;
    }

    // JDK-8092403
    // TODO: Remove this entire method (and associated stuff) when we switch to track parsing in MetadataParser
    void _updateMedia(com.sun.media.jfxmedia.Media _media) {
        try {
            List<com.sun.media.jfxmedia.track.Track> trackList = _media.getTracks();

            if (trackList != null) {
                for (com.sun.media.jfxmedia.track.Track trackElement : trackList) {
                    long trackID = trackElement.getTrackID();
                    if (getTrackWithID(trackID) == null) {
                        Track newTrack = null;
                        Map<String,Object> trackMetadata = new HashMap<>();
                        if (null != trackElement.getName()) {
                            // FIXME: need constants for metadata keys (globally)
                            trackMetadata.put("name", trackElement.getName());
                        }
                        if (null != trackElement.getLocale()) {
                            trackMetadata.put("locale", trackElement.getLocale());
                        }
                        trackMetadata.put("encoding", trackElement.getEncodingType().toString());
                        trackMetadata.put("enabled", Boolean.valueOf(trackElement.isEnabled()));

                        if (trackElement instanceof com.sun.media.jfxmedia.track.VideoTrack) {
                            com.sun.media.jfxmedia.track.VideoTrack vt =
                                    (com.sun.media.jfxmedia.track.VideoTrack) trackElement;

                            int videoWidth = vt.getFrameSize().getWidth();
                            int videoHeight = vt.getFrameSize().getHeight();

                            // FIXME: this isn't valid when there are multiple video tracks...
                            setWidth(videoWidth);
                            setHeight(videoHeight);

                            trackMetadata.put("video width", Integer.valueOf(videoWidth));
                            trackMetadata.put("video height", Integer.valueOf(videoHeight));

                            newTrack = new VideoTrack(trackElement.getTrackID(), trackMetadata);
                        } else if (trackElement instanceof com.sun.media.jfxmedia.track.AudioTrack) {
                            newTrack = new AudioTrack(trackElement.getTrackID(), trackMetadata);
                        } else if (trackElement instanceof com.sun.media.jfxmedia.track.SubtitleTrack) {
                            newTrack = new SubtitleTrack(trackID, trackMetadata);
                        }

                        if (null != newTrack) {
                            tracksBacking.add(newTrack);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Save any async exceptions as an error.
            setError(new MediaException(MediaException.Type.UNKNOWN, e));
        }
    }

    void _setError(MediaException.Type type, String message) {
        setError(new MediaException(type, message));
    }

    private synchronized void updateMetadata(Map<String, Object> metadata) {
        if (metadata != null) {
            for (Map.Entry<String,Object> entry : metadata.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (key.equals(MetadataParser.IMAGE_TAG_NAME) && value instanceof byte[]) {
                    byte[] imageData = (byte[]) value;
                    Image image = new Image(new ByteArrayInputStream(imageData));
                    if (!image.isError()) {
                        metadataBacking.put(MetadataParser.IMAGE_TAG_NAME, image);
                    }
                } else if (key.equals(MetadataParser.DURATION_TAG_NAME) && value instanceof java.lang.Long) {
                    Duration d = new Duration((Long) value);
                    if (d != null) {
                        metadataBacking.put(MetadataParser.DURATION_TAG_NAME, d);
                    }
                } else {
                    metadataBacking.put(key, value);
                }
            }
        }
    }

    private class _MetadataListener implements MetadataListener {
        @Override
        public void onMetadata(final Map<String, Object> metadata) {
            // Clean up metadata
            Platform.runLater(() -> {
                updateMetadata(metadata);
                jfxParser.removeListener(metadataListener);
                jfxParser.stopParser();
                jfxParser = null;
            });
        }
    }

    private class InitLocator implements Runnable {

        @Override
        public void run() {
            try {
                jfxLocator.init();
                runMetadataParser();
            } catch (URISyntaxException use) {
                _setError(MediaException.Type.OPERATION_UNSUPPORTED, use.getMessage());
            } catch (FileNotFoundException fnfe) {
                _setError(MediaException.Type.MEDIA_UNAVAILABLE, fnfe.getMessage());
            } catch (IOException ioe) {
                _setError(MediaException.Type.MEDIA_INACCESSIBLE, ioe.getMessage());
            } catch (com.sun.media.jfxmedia.MediaException me) {
                _setError(MediaException.Type.MEDIA_UNSUPPORTED, me.getMessage());
            } catch (Exception e) {
                _setError(MediaException.Type.UNKNOWN, e.getMessage());
            }
        }
    }
}
