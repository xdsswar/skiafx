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

package com.sun.media.jfxmedia.locator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.Map;

import com.sun.media.jfxmedia.logging.Logger;

/**
 * Connection holders hold and maintain connection do different kinds of sources
 *
 */
public abstract class ConnectionHolder {
    private static int DEFAULT_BUFFER_SIZE = 4096;

    ReadableByteChannel channel;
    ByteBuffer          buffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE);

    static ConnectionHolder createMemoryConnectionHolder(ByteBuffer buffer) {
        return new MemoryConnectionHolder(buffer);
    }

    static ConnectionHolder createURIConnectionHolder(URI uri, Map<String,Object> connectionProperties) throws IOException {
        return new URIConnectionHolder(uri, connectionProperties);
    }

    static ConnectionHolder createFileConnectionHolder(URI uri) throws IOException {
        return new FileConnectionHolder(uri);
    }

    static ConnectionHolder createHLSConnectionHolder(URI uri) {
        return new HLSConnectionHolder(uri);
    }

    /**
     * Reads a block of data from the current position of the opened stream.
     *
     * @return The number of bytes read, possibly zero, or -1 if the channel
     * has reached end-of-stream.
     *
     * @throws ClosedChannelException if an attempt is made to read after
     * closeConnection has been called
     */
    public int readNextBlock() throws IOException {
        buffer.rewind();
        if (buffer.limit() < buffer.capacity()) {
            buffer.limit(buffer.capacity());
        }
        // avoid NPE if channel does not exist or has been closed
        if (null == channel) {
            throw new ClosedChannelException();
        }
        return channel.read(buffer);
    }

    public ByteBuffer getBuffer() {
        return buffer;
    }

    /**
     * Reads a block of data from the arbitrary position of the opened stream.
     *
     * @return The number of bytes read, possibly zero, or -1 if the given position
     * is greater than or equal to the file's current size.
     *
     * @throws ClosedChannelException if an attempt is made to read after
     * closeConnection has been called
     */
    abstract int readBlock(long position, int size) throws IOException;

    /**
     * Detects whether this source needs buffering at the pipeline level.
     * When true the pipeline contains progressbuffer after the source.
     *
     * @return true if the source needs a buffer, false otherwise.
     */
    abstract boolean needBuffer();

    /**
     * Detects whether the source is seekable.
     * @return true if the source is seekable, false otherwise.
     */
    abstract boolean isSeekable();

    /**
     * Detects whether the source is a random access source. If the method returns
     * true then the source is capable of working in pull mode. To be able to work
     * in pull mode holder must provide implementation.
     * @return true is the source is random access, false otherwise.
     */
    abstract boolean isRandomAccess();

    /**
     * Performs a seek request to the desired position.
     *
     * @return -1 if the seek request failed or new stream position
     */
    public abstract long seek(long position);

    /**
     * Closes connection when done.
     * Overriding methods should call this method in the beginning of their implementation.
     */
    public void closeConnection() {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ioex) {}
        finally {
            channel = null;
        }
    }

    /**
     * Get or set properties.
     *
     * @param prop - Property ID.
     * @param value - Depends on property ID.
     * @return - Depends on property ID.
     */
    int property(int prop, int value) {
        return 0;
    }

    /**
     * Returns ConnectionHolder for additional audio stream if any.
     * Currently used by HLS with EXT-X-MEDIA tag.
     */
    public ConnectionHolder getAudioStream() {
        return null;
    }

    /**
     * Skia-fx: total stream length in bytes, or {@code -1} when unknown.
     * Used to give the native pipeline the companion stream's true size
     * (otherwise the primary's size hint gets stamped onto it).
     */
    long getContentLength() {
        return -1;
    }

    private static class FileConnectionHolder extends ConnectionHolder {
        private RandomAccessFile file = null;

        FileConnectionHolder(URI uri) throws IOException {
            channel = openFile(uri);
        }

        @Override
        boolean needBuffer() {
            return false;
        }

        @Override
        boolean isRandomAccess() {
            return true;
        }

        @Override
        boolean isSeekable() {
            return true;
        }

        @Override
        long getContentLength() {
            // Exact for local files — the dual-source companion size
            // hint relies on this (the base class reports -1/unknown,
            // which makes the native side fall back to the PRIMARY
            // locator's size: wrong byte math for the companion).
            try {
                return ((FileChannel) channel).size();
            } catch (IOException ioex) {
                return -1;
            }
        }

        @Override
        public long seek(long position) {
            try {
                ((FileChannel)channel).position(position);
                return position;
            } catch(IOException ioex) {
                return -1;
            }
        }

        @Override
        int readBlock(long position, int size) throws IOException {
            if (null == channel) {
                throw new ClosedChannelException();
            }

            if (buffer.capacity() < size) {
                buffer = ByteBuffer.allocateDirect(size);
            }
            buffer.rewind().limit(size);
            return ((FileChannel)channel).read(buffer, position);
        }

        private ReadableByteChannel openFile(final URI uri) throws IOException {
            if (file != null) {
                file.close();
            }

            file = new RandomAccessFile(new File(uri), "r");
            return file.getChannel();
        }

        @Override
        public void closeConnection() {
            super.closeConnection();

            if (file != null) {
                try {
                    file.close();
                } catch (IOException ex) {
                } finally {
                    file = null;
                }
            }
        }
    }

    private static class URIConnectionHolder extends ConnectionHolder {
        /**
         * Skia-fx: chunked-range HTTP reading.
         *
         * Several large CDNs (notably googlevideo) throttle a long-lived
         * GET after the first few megabytes — measured here: ~86 Mbps for
         * the first 8 MB of a stream, ~22 Mbps sustained on the same
         * request, ~80 Mbps when the same bytes are fetched as separate
         * 8 MB range requests. A single unbounded GET therefore starves
         * high-bitrate (4K) playback into stall/resume cycles. Browsers
         * avoid this by re-requesting media in chunks; we do the same:
         * once {@code CHUNK_SIZE} bytes have been consumed from the
         * current response, the next bytes are requested with a bounded
         * {@code Range:} header (HTTP keep-alive reuses the socket, so
         * the rotation is cheap). If the server ever answers without
         * 206/PARTIAL, chunking is disabled permanently for this
         * connection and behaviour reverts to the single-GET stock path.
         *
         * {@code -Dskia.media.httpChunkMB=<n>} tunes the chunk size
         * (default 8, {@code 0} disables chunking).
         */
        private static final long CHUNK_SIZE;
        static {
            long mb = 8;
            try {
                String v = System.getProperty("skia.media.httpChunkMB");
                if (v != null && !v.isEmpty()) {
                    mb = Long.parseLong(v);
                }
            } catch (Exception ignored) {}
            CHUNK_SIZE = (mb > 0 && mb <= 256) ? mb * 1024 * 1024 : (mb <= 0 ? 0 : 8 * 1024 * 1024);
        }

        private URI                 uri;
        private URLConnection       urlConnection;
        private final Map<String,Object> connectionProperties;
        // Chunked-range state: absolute stream position of the next byte
        // the channel will deliver, total length (-1 = unknown), bytes
        // left in the current response, the per-stream chunk size, and
        // the mode flag.
        private long                streamPosition = 0;
        private long                contentLength = -1;
        private long                chunkBytesLeft = Long.MAX_VALUE;
        private long                chunkSize = CHUNK_SIZE;
        private boolean             chunkedMode = false;

        URIConnectionHolder(URI uri, Map<String,Object> connectionProperties) throws IOException {
            this.uri = uri;
            this.connectionProperties = connectionProperties;
            urlConnection = uri.toURL().openConnection();
            applyConnectionProperties(urlConnection);
            channel = openChannel(null);

            if (urlConnection instanceof HttpURLConnection) {
                contentLength = urlConnection.getContentLengthLong();
                if (CHUNK_SIZE > 0 && contentLength > 0) {
                    chunkedMode = true;
                    // Scale the chunk to the stream: a small file (e.g. a
                    // dual-source audio companion of a few MB) must ALSO
                    // rotate several times, because the per-request burst
                    // is what defeats the server's drip throttle — one
                    // request for the whole small file gets rate-limited
                    // to ~real-time just like a big one. ~8 requests per
                    // file, floor 256 KB, cap CHUNK_SIZE.
                    chunkSize = Math.min(CHUNK_SIZE,
                                Math.max(256L * 1024, contentLength / 8));
                    // The initial response is an unbounded GET. For large
                    // streams ride it for one chunk (the per-request burst
                    // covers it). Small streams get NO burst on unbounded
                    // requests from drip-throttling CDNs (measured:
                    // googlevideo serves a 2.75 MB audio-only stream at a
                    // steady ~32 KB/s on a single GET, but at 14 Mbps as
                    // bounded range requests) — rotate to a bounded range
                    // immediately.
                    chunkBytesLeft = chunkSize;
                    if (chunkSize < CHUNK_SIZE) {
                        rotateChunk(0);
                    }
                }
            }
        }

        @Override
        long getContentLength() {
            return contentLength;
        }

        private void applyConnectionProperties(URLConnection connection) {
            if (connectionProperties != null) {
                for(Map.Entry<String,Object> entry : connectionProperties.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof String) {
                        connection.setRequestProperty(entry.getKey(), (String)value);
                    }
                }
            }
        }

        /**
         * Open a bounded range request at {@code position} and swap it in as
         * the active connection/channel. Returns true on success (206); on
         * any failure the current connection is left untouched and false is
         * returned (caller decides whether to keep streaming or give up).
         */
        private boolean rotateChunk(long position) {
            return rotateRange(position, true);
        }

        private boolean rotateRange(long position, boolean bounded) {
            URLConnection tmpURLConnection = null;
            try {
                tmpURLConnection = uri.toURL().openConnection();
                HttpURLConnection httpConnection = (HttpURLConnection)tmpURLConnection;
                httpConnection.setRequestMethod("GET");
                httpConnection.setUseCaches(false);
                applyConnectionProperties(httpConnection);
                long end = position + chunkSize - 1;
                if (contentLength > 0 && end >= contentLength) {
                    end = contentLength - 1;
                }
                httpConnection.setRequestProperty("Range",
                    bounded ? ("bytes=" + position + "-" + end)
                            : ("bytes=" + position + "-"));
                if (httpConnection.getResponseCode() == HttpURLConnection.HTTP_PARTIAL
                        && contentRangeStartsAt(httpConnection, position)) {
                    closeCurrentConnection();
                    urlConnection = tmpURLConnection;
                    tmpURLConnection = null;
                    channel = openChannel(null);
                    streamPosition = position;
                    if (bounded) {
                        chunkBytesLeft = end - position + 1;
                    } else {
                        // Open-ended response: no chunk edge to rotate at.
                        chunkBytesLeft = Long.MAX_VALUE;
                        chunkedMode = false;
                    }
                    return true;
                }
                if (READ_DIAG) {
                    String cr = httpConnection.getHeaderField("Content-Range");
                    System.err.println("[holder-diag] " + Integer.toHexString(System.identityHashCode(this))
                        + " rotateRange(" + position + ", bounded=" + bounded + ") rejected: code="
                        + httpConnection.getResponseCode() + " Content-Range=" + cr);
                }
                return false;
            } catch (IOException ioex) {
                if (READ_DIAG) {
                    System.err.println("[holder-diag] " + Integer.toHexString(System.identityHashCode(this))
                        + " rotateRange(" + position + ") IOException: " + ioex);
                }
                return false;
            } finally {
                if (tmpURLConnection != null) {
                    Locator.closeConnection(tmpURLConnection);
                }
            }
        }

        /**
         * Re-establish the connection at {@code position} after a chunk is
         * spent or a rotation failed. Bounded rotation is retried (transient
         * network errors, CDN hiccups), then an open-ended range is tried
         * (server stopped honouring bounded ranges). Once a bounded response
         * has been consumed there is no unbounded connection to fall back
         * to, so total failure here is a connection loss, not end-of-stream.
         */
        /**
         * Verify the 206 response actually starts where we asked. A buggy
         * CDN/proxy that answers 206 with the wrong offset would otherwise
         * feed misaligned bytes straight into the demuxer as silent stream
         * corruption. A missing/unparseable header is accepted (the 206
         * itself is the contract); only a CONTRADICTING header rejects.
         */
        private static boolean contentRangeStartsAt(HttpURLConnection connection,
                                                    long position) {
            String range = connection.getHeaderField("Content-Range");
            if (range == null) {
                return true;
            }
            // Format: "bytes <start>-<end>/<total>" (RFC 9110 §14.4)
            try {
                String r = range.trim();
                if (!r.regionMatches(true, 0, "bytes", 0, 5)) {
                    return true; // unknown unit — don't second-guess
                }
                int from = 5;
                while (from < r.length() && r.charAt(from) == ' ') from++;
                int dash = r.indexOf('-', from);
                if (dash < 0) {
                    return true;
                }
                long start = Long.parseLong(r.substring(from, dash).trim());
                return start == position;
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                return true;
            }
        }

        /** One-shot warning so a truncated stream is at least diagnosable. */
        private void connectionLost() {
            diagRead(-1, "reconnect-failed");
            Logger.logMsg(Logger.WARNING,
                "Media connection lost at byte " + streamPosition + " of "
                + contentLength + " (" + uri + "); ending stream early.");
        }

        private boolean reconnectAt(long position) {
            for (int attempt = 0; attempt < 3; attempt++) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(attempt == 1 ? 50 : 250);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                if (rotateRange(position, true)) {
                    return true;
                }
            }
            return rotateRange(position, false);
        }

        private void closeCurrentConnection() {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException ignored) {
            } finally {
                channel = null;
            }
            Locator.closeConnection(urlConnection);
            urlConnection = null;
        }

        /** Diagnostic (OPENJFX_MEDIA_VERBOSE env): per-holder read accounting. */
        private static final boolean READ_DIAG;
        static {
            String v = System.getenv("OPENJFX_MEDIA_VERBOSE");
            READ_DIAG = v != null && !v.isEmpty() && !v.equals("0");
        }
        private long diagTotal = 0;
        private long diagNextLog = 0;

        private void diagRead(int n, String tag) {
            if (!READ_DIAG) return;
            if (n > 0) diagTotal += n;
            if (n < 0 || diagTotal >= diagNextLog) {
                System.err.println("[holder-diag] " + Integer.toHexString(System.identityHashCode(this))
                    + " len=" + contentLength + " total=" + diagTotal
                    + (n < 0 ? " EOS" : "") + (tag != null ? (" " + tag) : ""));
                diagNextLog = diagTotal + 262144;
            }
        }

        @Override
        public int readNextBlock() throws IOException {
            if (READ_DIAG && diagTotal == 0 && diagNextLog == 0) diagRead(0, "first-read");
            if (chunkedMode) {
                if (null == channel) {
                    throw new ClosedChannelException();
                }
                if (chunkBytesLeft <= 0 || streamPosition >= contentLength) {
                    if (streamPosition >= contentLength) {
                        return -1; // genuine end of stream
                    }
                    // The current bounded response is spent — there is no
                    // unbounded connection to "fall back" to. A failed
                    // reconnect is a lost connection. NOTE: throwing here
                    // would NOT surface as a player error — the JNI bridge
                    // maps a Java exception to -2 and javasource maps -2
                    // to GST_FLOW_FLUSHING, which pauses the source task
                    // silently and PERMANENTLY (frozen player, no event).
                    // EOS at least ends playback cleanly, exactly like a
                    // stock unbounded GET whose connection died.
                    if (!reconnectAt(streamPosition)) {
                        connectionLost();
                        return -1;
                    }
                }
                int n = super.readNextBlock();
                if (n > 0) {
                    streamPosition += n;
                    chunkBytesLeft -= n;
                } else if (n < 0 && streamPosition < contentLength) {
                    // Bounded responses EOF at the chunk edge — rotate and
                    // keep reading rather than reporting end-of-stream.
                    if (!reconnectAt(streamPosition)) {
                        connectionLost();
                        return -1;
                    }
                    n = super.readNextBlock();
                    if (n > 0) {
                        streamPosition += n;
                        chunkBytesLeft -= n;
                    }
                }
                diagRead(n, null);
                return n;
            }
            int n = super.readNextBlock();
            diagRead(n, "plain");
            return n;
        }

        @Override
        boolean needBuffer() {
            String scheme = uri.getScheme().toLowerCase();
            return ("http".equals(scheme) || "https".equals(scheme));
        }

        @Override
        boolean isSeekable() {
            return (urlConnection instanceof HttpURLConnection) ||
                   (urlConnection instanceof JarURLConnection) ||
                   isJRT() || isResource();
        }

        @Override
        boolean isRandomAccess() {
            return false;
        }

        @Override
        int readBlock(long position, int size) throws IOException {
            throw new IOException();
        }

        @Override
        public long seek(long position) {
            if (READ_DIAG) {
                System.err.println("[holder-diag] " + Integer.toHexString(System.identityHashCode(this))
                    + " seek(" + position + ") total=" + diagTotal);
            }
            if (urlConnection instanceof HttpURLConnection) {
                // Skia-fx: in chunked mode a seek is just a chunk rotation
                // at the new offset — bounded range, keep-alive friendly,
                // and it keeps the anti-throttle behaviour after seeks.
                if (chunkedMode) {
                    boolean ok = rotateChunk(position);
                    if (READ_DIAG) {
                        System.err.println("[holder-diag] " + Integer.toHexString(System.identityHashCode(this))
                            + " chunked rotate(" + position + ") -> " + (ok ? "OK" : "FAIL"));
                    }
                    return ok ? position : -1;
                }

                URLConnection tmpURLConnection = null;

                //closeConnection();
                try{
                    tmpURLConnection = uri.toURL().openConnection();

                    HttpURLConnection httpConnection = (HttpURLConnection)tmpURLConnection;
                    httpConnection.setRequestMethod("GET");
                    httpConnection.setUseCaches(false);
                    applyConnectionProperties(httpConnection);
                    httpConnection.setRequestProperty("Range", "bytes=" + position + "-");
                    // If range request worked properly we should get responce code 206 (HTTP_PARTIAL)
                    // Else fail seek and let progressbuffer to download all data. It is pointless for us to download it and throw away.
                    if (httpConnection.getResponseCode() == HttpURLConnection.HTTP_PARTIAL) {
                        closeConnection();
                        urlConnection = tmpURLConnection;
                        tmpURLConnection = null;
                        channel = openChannel(null);
                        return position;
                    } else {
                        return -1;
                    }
                } catch (IOException ioex) {
                    return -1;
                } finally {
                    if (tmpURLConnection != null) {
                        Locator.closeConnection(tmpURLConnection);
                    }
                }
            } else if ((urlConnection instanceof JarURLConnection) || isJRT() || isResource()) {
                try {
                    closeConnection();

                    urlConnection = uri.toURL().openConnection();

                    // Skip data that we do not need
                    long skip_left = position;
                    InputStream inputStream = urlConnection.getInputStream();
                    do {
                        long skip = inputStream.skip(skip_left);
                        skip_left -= skip;
                    } while (skip_left > 0);

                    channel = openChannel(inputStream);

                    return position;
                } catch (IOException ioex) {
                    return -1;
                }
            }

            return -1;
        }

        @Override
        public void closeConnection() {
            super.closeConnection();

            Locator.closeConnection(urlConnection);
            urlConnection = null;
        }

        private ReadableByteChannel openChannel(InputStream inputStream) throws IOException {
            return (inputStream == null) ?
                    Channels.newChannel(urlConnection.getInputStream()) :
                    Channels.newChannel(inputStream);
        }

        private boolean isJRT() {
            String scheme = uri.getScheme().toLowerCase();
            return "jrt".equals(scheme);
        }

        private boolean isResource() {
            String scheme = uri.getScheme().toLowerCase();
            return "resource".equals(scheme);
        }

    }

    // A "ConnectionHolder" that "reads" from a ByteBuffer, generally loaded from
    // some unsupported or buggy source
    private static class MemoryConnectionHolder extends ConnectionHolder {
        private final ByteBuffer backingBuffer;

        public MemoryConnectionHolder(ByteBuffer buf) {
            if (null == buf) {
                throw new IllegalArgumentException("Can't connect to null buffer...");
            }

            if (buf.isDirect()) {
                // we can use it, or rather a duplicate directly
                backingBuffer = buf.duplicate();
            } else {
                // operate on a copy of the buffer
                backingBuffer = ByteBuffer.allocateDirect(buf.capacity());
                backingBuffer.put(buf);
            }

            // rewind since the default position is expected to be at zero
            backingBuffer.rewind();

            // readNextBlock should never be called since we're random access
            // but just to be safe (and for unit tests...)
            channel = new ReadableByteChannel() {
                @Override
                public int read(ByteBuffer bb) throws IOException {
                    if (backingBuffer.remaining() <= 0) {
                        return -1; // EOS
                    }

                    int actual;
                    if (bb.equals(buffer)) {
                        // we'll cheat here as we know that bb is buffer and rather
                        // than copy the data, just slice it like for readBlock
                        actual = Math.min(DEFAULT_BUFFER_SIZE, backingBuffer.remaining());
                        if (actual > 0) {
                            buffer = backingBuffer.slice();
                            buffer.limit(actual);
                        }
                    } else {
                        actual = Math.min(bb.remaining(), backingBuffer.remaining());
                        if (actual > 0) {
                            backingBuffer.limit(backingBuffer.position() + actual);
                            bb.put(backingBuffer);
                            backingBuffer.limit(backingBuffer.capacity());
                        }
                    }
                    return actual;
                }

                @Override
                public boolean isOpen() {
                    return true; // open 24/7/365
                }

                @Override
                public void close() throws IOException {
                    // never closed...
                }
            };
        }

        @Override
        int readBlock(long position, int size) throws IOException {
            // mimic stream behavior
            if (null == channel) {
                throw new ClosedChannelException();
            }

            if ((int)position > backingBuffer.capacity()) {
                return -1; //EOS
            }
            backingBuffer.position((int)position);

            buffer = backingBuffer.slice();

            int actual = Math.min(backingBuffer.remaining(), size);
            buffer.limit(actual); // only give as much as asked
            backingBuffer.position(backingBuffer.position() + actual);

            return actual;
        }

        @Override
        boolean needBuffer() {
            return false;
        }

        @Override
        boolean isSeekable() {
            return true;
        }

        @Override
        boolean isRandomAccess() {
            return true;
        }

        @Override
        public long seek(long position) {
            if ((int)position < backingBuffer.capacity()) {
                backingBuffer.limit(backingBuffer.capacity());
                backingBuffer.position((int)position);
                return position;
            }
            return -1;
        }

        @Override
        public void closeConnection() {
            // more stream behavior mimicry
            channel = null;
        }
    }
}
