/*
 * MatroskaMetadataParser — skia-fx addition (experimental).
 *
 * Minimal EBML walk over a Matroska / WebM stream: Segment→Info gives
 * the duration and segment title, Segment→Tags gives the textual tags
 * (ffmpeg and mkvmerge write them as SimpleTag Name/String pairs).
 * The walk is strictly bounded: it stops at the first Cluster (media
 * data) — files that place Tags after the clusters (rare; needs a
 * SeekHead-guided jump) simply yield what the head contained.
 */
package com.sun.media.jfxmediaimpl.platform.java;

import java.io.EOFException;
import java.nio.charset.StandardCharsets;

import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmediaimpl.MetadataParserImpl;

final class MatroskaMetadataParser extends MetadataParserImpl {

    private static final long ID_EBML_HEADER   = 0x1A45DFA3L;
    private static final long ID_SEGMENT       = 0x18538067L;
    private static final long ID_INFO          = 0x1549A966L;
    private static final long ID_CLUSTER       = 0x1F43B675L;
    private static final long ID_TAGS          = 0x1254C367L;
    private static final long ID_TAG           = 0x7373L;
    private static final long ID_SIMPLE_TAG    = 0x67C8L;
    private static final long ID_TAG_NAME      = 0x45A3L;
    private static final long ID_TAG_STRING    = 0x4487L;
    private static final long ID_TIMECODESCALE = 0x2AD7B1L;
    private static final long ID_DURATION      = 0x4489L;
    private static final long ID_TITLE         = 0x7BA9L;

    /** Bail-out so a pathological header can't make us trawl the file. */
    private static final int MAX_HEAD_BYTES = 4 * 1024 * 1024;

    private long timecodeScaleNs = 1_000_000L; // matroska default
    private double durationTicks = -1;

    MatroskaMetadataParser(Locator locator) {
        super(locator);
    }

    @Override
    protected void parse() {
        try {
            // EBML header
            if (readElementId() != ID_EBML_HEADER) {
                return;
            }
            skipPayload(readElementSize());

            // Segment (size frequently "unknown" for streamed muxes —
            // treat its children as running to EOF/our byte cap)
            if (readElementId() != ID_SEGMENT) {
                return;
            }
            readElementSize();

            while (getStreamPosition() < MAX_HEAD_BYTES) {
                long id = readElementId();
                long size = readElementSize();
                if (id == ID_CLUSTER) {
                    break; // media data — nothing of ours past this point
                } else if (id == ID_INFO) {
                    parseInfo(endOf(size));
                } else if (id == ID_TAGS) {
                    parseTags(endOf(size));
                } else {
                    skipPayload(size);
                }
            }

            if (durationTicks > 0) {
                long millis = (long) (durationTicks * timecodeScaleNs / 1_000_000.0);
                if (millis > 0) {
                    addMetadataItem(DURATION_TAG_NAME, Long.valueOf(millis));
                }
            }
        } catch (Exception e) {
            // Deliver whatever was collected — see done() below.
        } finally {
            done();
        }
    }

    private void parseInfo(long end) throws Exception {
        while (getStreamPosition() < end) {
            long id = readElementId();
            long size = readElementSize();
            if (id == ID_TIMECODESCALE) {
                timecodeScaleNs = readUInt(size);
            } else if (id == ID_DURATION) {
                durationTicks = readFloat(size);
            } else if (id == ID_TITLE) {
                String title = getString((int) size, StandardCharsets.UTF_8);
                if (!title.isEmpty()) {
                    addMetadataItem(TITLE_TAG_NAME, title);
                }
            } else {
                skipPayload(size);
            }
        }
    }

    private void parseTags(long end) throws Exception {
        while (getStreamPosition() < end) {
            long id = readElementId();
            long size = readElementSize();
            if (id == ID_TAG) {
                parseTag(endOf(size));
            } else {
                skipPayload(size);
            }
        }
    }

    private void parseTag(long end) throws Exception {
        while (getStreamPosition() < end) {
            long id = readElementId();
            long size = readElementSize();
            if (id == ID_SIMPLE_TAG) {
                parseSimpleTag(endOf(size));
            } else {
                skipPayload(size); // Targets etc.
            }
        }
    }

    private void parseSimpleTag(long end) throws Exception {
        String name = null;
        String value = null;
        while (getStreamPosition() < end) {
            long id = readElementId();
            long size = readElementSize();
            if (id == ID_TAG_NAME) {
                name = getString((int) size, StandardCharsets.UTF_8);
            } else if (id == ID_TAG_STRING) {
                value = getString((int) size, StandardCharsets.UTF_8);
            } else if (id == ID_SIMPLE_TAG) {
                parseSimpleTag(endOf(size)); // nested refinements
            } else {
                skipPayload(size);
            }
        }
        if (name != null && value != null && !value.isEmpty()) {
            addMatroskaTag(name.toUpperCase(), value);
        }
    }

    private void addMatroskaTag(String name, String value) {
        switch (name) {
            case "TITLE"    -> addMetadataItem(TITLE_TAG_NAME, value);
            case "ARTIST"   -> addMetadataItem(ARTIST_TAG_NAME, value);
            case "ALBUM"    -> addMetadataItem(ALBUM_TAG_NAME, value);
            case "ALBUM_ARTIST", "ALBUMARTIST"
                            -> addMetadataItem(ALBUMARTIST_TAG_NAME, value);
            case "GENRE"    -> addMetadataItem(GENRE_TAG_NAME, value);
            case "COMMENT"  -> addMetadataItem(COMMENT_TAG_NAME, value);
            case "COMPOSER" -> addMetadataItem(COMPOSER_TAG_NAME, value);
            case "PART_NUMBER", "TRACKNUMBER" -> {
                try {
                    addMetadataItem(TRACKNUMBER_TAG_NAME,
                            Integer.valueOf(value.trim().split("/")[0]));
                } catch (NumberFormatException ignored) {
                }
            }
            case "DATE_RELEASED", "DATE" -> {
                try {
                    if (value.trim().length() >= 4) {
                        addMetadataItem(YEAR_TAG_NAME,
                                Integer.valueOf(value.trim().substring(0, 4)));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            default -> { /* ENCODER etc. — dropped, like unmapped ID3 frames */ }
        }
    }

    // ------------------------------------------------------------------
    // EBML primitives
    // ------------------------------------------------------------------

    /** Element IDs keep their length-marker bits. */
    private long readElementId() throws Exception {
        int first = getNextByte() & 0xff;
        int extra = lengthFromMarker(first);
        long id = first;
        for (int i = 0; i < extra; i++) {
            id = (id << 8) | (getNextByte() & 0xff);
        }
        return id;
    }

    /** Sizes drop the marker bit. All 1s = "unknown" → Long.MAX_VALUE. */
    private long readElementSize() throws Exception {
        int first = getNextByte() & 0xff;
        int extra = lengthFromMarker(first);
        long size = first & (0xff >>> (extra + 1));
        boolean allOnes = size == (0xffL >>> (extra + 1));
        for (int i = 0; i < extra; i++) {
            int b = getNextByte() & 0xff;
            allOnes &= (b == 0xff);
            size = (size << 8) | b;
        }
        return allOnes ? Long.MAX_VALUE : size;
    }

    private static int lengthFromMarker(int first) throws EOFException {
        if (first == 0) {
            throw new EOFException("invalid EBML length marker");
        }
        return Integer.numberOfLeadingZeros(first) - 24;
    }

    private long readUInt(long size) throws Exception {
        long v = 0;
        for (long i = 0; i < size; i++) {
            v = (v << 8) | (getNextByte() & 0xff);
        }
        return v;
    }

    private double readFloat(long size) throws Exception {
        if (size == 4) {
            return Float.intBitsToFloat((int) readUInt(4));
        }
        if (size == 8) {
            return Double.longBitsToDouble(readUInt(8));
        }
        skipPayload(size);
        return -1;
    }

    private long endOf(long size) {
        return (size == Long.MAX_VALUE)
                ? Long.MAX_VALUE : getStreamPosition() + size;
    }

    private void skipPayload(long size) throws Exception {
        if (size == Long.MAX_VALUE) {
            throw new EOFException("unknown-size element cannot be skipped");
        }
        while (size > 0) {
            int chunk = (int) Math.min(size, Integer.MAX_VALUE);
            skipBytes(chunk);
            size -= chunk;
        }
    }
}
