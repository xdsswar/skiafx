/*
 * AviMetadataParser — skia-fx addition (experimental).
 *
 * Walks the RIFF chunk tree of an AVI: the 'avih' main header gives
 * the duration (µs-per-frame × total frames), an INFO LIST gives the
 * textual tags (INAM title, IART artist, …). Stops at the 'movi'
 * LIST (the media data). RIFF sizes are little-endian — the base
 * class readers are big-endian, so this file carries its own LE
 * helpers. Chunks are word-aligned (odd sizes get one pad byte).
 */
package com.sun.media.jfxmediaimpl.platform.java;

import java.nio.charset.StandardCharsets;

import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmediaimpl.MetadataParserImpl;

final class AviMetadataParser extends MetadataParserImpl {

    AviMetadataParser(Locator locator) {
        super(locator);
    }

    @Override
    protected void parse() {
        try {
            if (!"RIFF".equals(fourCC())) {
                return;
            }
            skipBytes(4); // riff size
            if (!"AVI ".equals(fourCC())) {
                return;
            }

            while (true) {
                String cc = fourCC();
                int size = getIntLE();
                long end = getStreamPosition() + size;

                if ("LIST".equals(cc)) {
                    String listType = fourCC();
                    if ("movi".equals(listType)) {
                        break; // media data — nothing of ours past this
                    } else if ("hdrl".equals(listType)) {
                        parseHeaderList(end);
                    } else if ("INFO".equals(listType)) {
                        parseInfoList(end);
                    }
                    skipToPosition(end);
                } else {
                    skipToPosition(end);
                }
                if ((size & 1) != 0) {
                    skipBytes(1); // word alignment pad
                }
            }
        } catch (Exception e) {
            // Deliver whatever was collected — see done() below.
        } finally {
            done();
        }
    }

    private void parseHeaderList(long end) throws Exception {
        while (getStreamPosition() < end) {
            String cc = fourCC();
            int size = getIntLE();
            long chunkEnd = getStreamPosition() + size;
            if ("avih".equals(cc) && size >= 24) {
                long usPerFrame = getIntLE() & 0xffffffffL;
                skipBytes(4 * 3); // maxBytesPerSec, padding, flags
                long totalFrames = getIntLE() & 0xffffffffL;
                if (usPerFrame > 0 && totalFrames > 0) {
                    addMetadataItem(DURATION_TAG_NAME,
                            Long.valueOf(usPerFrame * totalFrames / 1000L));
                }
            } else if ("LIST".equals(cc)) {
                fourCC(); // strl etc. — not needed
            }
            skipToPosition(chunkEnd);
            if ((size & 1) != 0) {
                skipBytes(1);
            }
        }
    }

    private void parseInfoList(long end) throws Exception {
        while (getStreamPosition() < end) {
            String cc = fourCC();
            int size = getIntLE();
            long chunkEnd = getStreamPosition() + size;
            String value = getString(size, StandardCharsets.UTF_8);
            int nul = value.indexOf('\0');
            if (nul >= 0) {
                value = value.substring(0, nul);
            }
            value = value.trim();
            if (!value.isEmpty()) {
                addInfoTag(cc, value);
            }
            skipToPosition(chunkEnd);
            if ((size & 1) != 0) {
                skipBytes(1);
            }
        }
    }

    private void addInfoTag(String cc, String value) {
        switch (cc) {
            case "INAM" -> addMetadataItem(TITLE_TAG_NAME, value);
            case "IART" -> addMetadataItem(ARTIST_TAG_NAME, value);
            case "IPRD" -> addMetadataItem(ALBUM_TAG_NAME, value);
            case "IGNR" -> addMetadataItem(GENRE_TAG_NAME, value);
            case "ICMT" -> addMetadataItem(COMMENT_TAG_NAME, value);
            case "IMUS" -> addMetadataItem(COMPOSER_TAG_NAME, value);
            case "IPRT", "ITRK" -> {
                try {
                    addMetadataItem(TRACKNUMBER_TAG_NAME,
                            Integer.valueOf(value.split("/")[0].trim()));
                } catch (NumberFormatException ignored) {
                }
            }
            case "ICRD" -> {
                try {
                    if (value.length() >= 4) {
                        addMetadataItem(YEAR_TAG_NAME,
                                Integer.valueOf(value.substring(0, 4)));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            default -> { /* ISFT etc. — dropped, like unmapped ID3 frames */ }
        }
    }

    // ------------------------------------------------------------------

    private String fourCC() throws Exception {
        return getString(4, StandardCharsets.US_ASCII);
    }

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
