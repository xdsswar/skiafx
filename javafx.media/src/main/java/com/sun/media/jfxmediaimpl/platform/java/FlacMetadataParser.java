/*
 * FlacMetadataParser — skia-fx addition (experimental).
 *
 * Parses the metadata blocks of a raw FLAC stream: STREAMINFO for the
 * duration, VORBIS_COMMENT for the textual tags, PICTURE for embedded
 * cover art. Stops at the first audio frame, so only the head of the
 * file is read. The wire format is the FLAC spec's: a 4-byte "fLaC"
 * marker followed by metadata blocks, each with a 1-byte
 * last-flag/type header and a 24-bit big-endian length — except
 * VORBIS_COMMENT's interior, which is little-endian per the Vorbis
 * comment spec.
 */
package com.sun.media.jfxmediaimpl.platform.java;

import java.nio.charset.StandardCharsets;

import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmediaimpl.MetadataParserImpl;

final class FlacMetadataParser extends MetadataParserImpl {

    private static final int BLOCK_STREAMINFO     = 0;
    private static final int BLOCK_VORBIS_COMMENT = 4;
    private static final int BLOCK_PICTURE        = 6;

    FlacMetadataParser(Locator locator) {
        super(locator);
    }

    @Override
    protected void parse() {
        try {
            byte[] marker = getBytes(4);
            if (marker[0] != 'f' || marker[1] != 'L'
                    || marker[2] != 'a' || marker[3] != 'C') {
                return;
            }

            boolean last = false;
            while (!last) {
                int header = getNextByte() & 0xff;
                last = (header & 0x80) != 0;
                int type = header & 0x7f;
                int length = getU24();

                switch (type) {
                    case BLOCK_STREAMINFO -> parseStreamInfo(length);
                    case BLOCK_VORBIS_COMMENT -> parseVorbisComment(length);
                    case BLOCK_PICTURE -> parsePicture(length);
                    default -> skipBytes(length);
                }
            }
        } catch (Exception e) {
            // Whatever was collected before the failure still gets
            // delivered below — a truncated tail must not lose the tags.
        } finally {
            done();
        }
    }

    /** 34 bytes; sample rate is 20 bits, total samples 36 bits. */
    private void parseStreamInfo(int length) throws Exception {
        if (length < 34) {
            skipBytes(length);
            return;
        }
        byte[] b = getBytes(34);
        skipBytes(length - 34);

        int sampleRate = ((b[10] & 0xff) << 12)
                       | ((b[11] & 0xff) << 4)
                       | ((b[12] & 0xf0) >>> 4);
        long totalSamples = ((long) (b[13] & 0x0f) << 32)
                          | ((long) (b[14] & 0xff) << 24)
                          | ((long) (b[15] & 0xff) << 16)
                          | ((long) (b[16] & 0xff) << 8)
                          |  (long) (b[17] & 0xff);
        if (sampleRate > 0 && totalSamples > 0) {
            addMetadataItem(DURATION_TAG_NAME,
                    Long.valueOf(totalSamples * 1000L / sampleRate));
        }
    }

    private void parseVorbisComment(int length) throws Exception {
        long end = getStreamPosition() + (long) length;

        int vendorLen = getIntLE();
        if (vendorLen < 0 || vendorLen > length) {
            skipToPosition(end);
            return;
        }
        skipBytes(vendorLen);

        int count = getIntLE();
        for (int i = 0; i < count && getStreamPosition() + 4 <= end; i++) {
            int entryLen = getIntLE();
            if (entryLen < 0 || getStreamPosition() + entryLen > end) {
                break;
            }
            String entry = getString(entryLen, StandardCharsets.UTF_8);
            int eq = entry.indexOf('=');
            if (eq > 0) {
                addVorbisTag(entry.substring(0, eq), entry.substring(eq + 1));
            }
        }
        skipToPosition(end);
    }

    private void addVorbisTag(String key, String value) {
        if (value.isEmpty()) {
            return;
        }
        switch (key.toUpperCase()) {
            case "TITLE"       -> addMetadataItem(TITLE_TAG_NAME, value);
            case "ARTIST"      -> addMetadataItem(ARTIST_TAG_NAME, value);
            case "ALBUM"       -> addMetadataItem(ALBUM_TAG_NAME, value);
            case "ALBUMARTIST", "ALBUM ARTIST"
                               -> addMetadataItem(ALBUMARTIST_TAG_NAME, value);
            case "GENRE"       -> addMetadataItem(GENRE_TAG_NAME, value);
            case "COMMENT"     -> addMetadataItem(COMMENT_TAG_NAME, value);
            case "COMPOSER"    -> addMetadataItem(COMPOSER_TAG_NAME, value);
            case "TRACKNUMBER" -> addNumeric(TRACKNUMBER_TAG_NAME, value);
            case "TRACKTOTAL"  -> addNumeric(TRACKCOUNT_TAG_NAME, value);
            case "DISCNUMBER"  -> addNumeric(DISCNUMBER_TAG_NAME, value);
            case "DISCTOTAL"   -> addNumeric(DISCCOUNT_TAG_NAME, value);
            case "DATE", "YEAR" -> addYear(value);
            default -> { /* unmapped vorbis keys are dropped, like ID3 */ }
        }
    }

    private void addNumeric(String tag, String value) {
        try {
            addMetadataItem(tag, Integer.valueOf(value.trim().split("/")[0]));
        } catch (NumberFormatException ignored) {
        }
    }

    private void addYear(String value) {
        // DATE is commonly "2026" or "2026-06-11" — the year prefix.
        try {
            String y = value.trim();
            if (y.length() >= 4) {
                addMetadataItem(YEAR_TAG_NAME, Integer.valueOf(y.substring(0, 4)));
            }
        } catch (NumberFormatException ignored) {
        }
    }

    /** type(4) + mime + description + 4×u32 geometry + data. */
    private void parsePicture(int length) throws Exception {
        long end = getStreamPosition() + (long) length;
        skipBytes(4); // picture type
        int mimeLen = getInteger();
        if (mimeLen < 0 || mimeLen > length) {
            skipToPosition(end);
            return;
        }
        skipBytes(mimeLen);
        int descLen = getInteger();
        if (descLen < 0 || getStreamPosition() + descLen > end) {
            skipToPosition(end);
            return;
        }
        skipBytes(descLen);
        skipBytes(16); // width, height, depth, colors
        int dataLen = getInteger();
        if (dataLen > 0 && getStreamPosition() + dataLen <= end) {
            addMetadataItem(IMAGE_TAG_NAME, getBytes(dataLen));
        }
        skipToPosition(end);
    }

    // ------------------------------------------------------------------

    /** The base class readers are big-endian; vorbis comments are LE. */
    private int getIntLE() throws Exception {
        int b0 = getNextByte() & 0xff;
        int b1 = getNextByte() & 0xff;
        int b2 = getNextByte() & 0xff;
        int b3 = getNextByte() & 0xff;
        return (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
    }

    private void skipToPosition(long target) throws Exception {
        long remaining = target - getStreamPosition();
        if (remaining > 0 && remaining <= Integer.MAX_VALUE) {
            skipBytes((int) remaining);
        }
    }
}
