/*
 * FlvMetadataParser — skia-fx addition (experimental).
 *
 * Reads the AMF0 "onMetaData" script tag every sane FLV mux places
 * first in the tag stream: duration, width/height, frame/data rates,
 * encoder, creation date. Stops at the script tag's end (or after a
 * handful of leading tags when there is none), so only the head of
 * the file is touched. AMF0 numbers are big-endian doubles; the base
 * class readers are big-endian throughout, which matches FLV.
 */
package com.sun.media.jfxmediaimpl.platform.java;

import java.nio.charset.StandardCharsets;

import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmediaimpl.MetadataParserImpl;

final class FlvMetadataParser extends MetadataParserImpl {

    private static final int TAG_SCRIPT = 18;
    private static final int MAX_LEADING_TAGS = 4;

    private static final int AMF_NUMBER       = 0;
    private static final int AMF_BOOLEAN      = 1;
    private static final int AMF_STRING       = 2;
    private static final int AMF_OBJECT       = 3;
    private static final int AMF_NULL         = 5;
    private static final int AMF_UNDEFINED    = 6;
    private static final int AMF_ECMA_ARRAY   = 8;
    private static final int AMF_OBJECT_END   = 9;
    private static final int AMF_STRICT_ARRAY = 10;
    private static final int AMF_DATE         = 11;
    private static final int AMF_LONG_STRING  = 12;

    FlvMetadataParser(Locator locator) {
        super(locator);
    }

    @Override
    protected void parse() {
        try {
            byte[] sig = getBytes(3);
            if (sig[0] != 'F' || sig[1] != 'L' || sig[2] != 'V') {
                return;
            }
            skipBytes(2); // version + type flags
            int dataOffset = getInteger();
            if (dataOffset > 9) {
                skipBytes(dataOffset - 9);
            }

            for (int i = 0; i < MAX_LEADING_TAGS; i++) {
                skipBytes(4); // previous tag size
                int tagType = getNextByte() & 0xff;
                int dataSize = getU24();
                skipBytes(3 + 1 + 3); // timestamp + ext + stream id
                if (tagType == TAG_SCRIPT) {
                    parseScriptData(getStreamPosition() + dataSize);
                    break;
                }
                skipBytes(dataSize);
            }
        } catch (Exception e) {
            // Deliver whatever was collected — see done() below.
        } finally {
            done();
        }
    }

    private void parseScriptData(long end) throws Exception {
        // First AMF value: the method name string, "onMetaData".
        if ((getNextByte() & 0xff) != AMF_STRING) {
            return;
        }
        String name = readShortString();
        if (!"onMetaData".equals(name)) {
            return;
        }
        // Second value: ECMA array (occasionally a plain object).
        int type = getNextByte() & 0xff;
        if (type == AMF_ECMA_ARRAY) {
            skipBytes(4); // approximate count — terminated by end marker
            readProperties(end);
        } else if (type == AMF_OBJECT) {
            readProperties(end);
        }
    }

    private void readProperties(long end) throws Exception {
        while (getStreamPosition() < end) {
            String key = readShortString();
            int type = getNextByte() & 0xff;
            if (key.isEmpty() && type == AMF_OBJECT_END) {
                return;
            }
            readValue(key, type, end, true);
        }
    }

    /** @param topLevel only top-level scalars become metadata entries */
    private void readValue(String key, int type, long end, boolean topLevel)
            throws Exception {
        switch (type) {
            case AMF_NUMBER -> {
                double v = getDouble();
                if (topLevel) {
                    addNumber(key, v);
                }
            }
            case AMF_BOOLEAN -> {
                boolean v = getNextByte() != 0;
                if (topLevel) {
                    addMetadataItem(key, Boolean.valueOf(v));
                }
            }
            case AMF_STRING -> {
                String v = readShortString();
                if (topLevel && !v.isEmpty()) {
                    addMetadataItem(key, v);
                }
            }
            case AMF_LONG_STRING -> {
                int len = getInteger();
                String v = getString(len, StandardCharsets.UTF_8);
                if (topLevel && !v.isEmpty()) {
                    addMetadataItem(key, v);
                }
            }
            case AMF_OBJECT -> readNestedProperties(end);
            case AMF_ECMA_ARRAY -> {
                skipBytes(4);
                readNestedProperties(end);
            }
            case AMF_STRICT_ARRAY -> {
                int count = getInteger();
                for (int i = 0; i < count && getStreamPosition() < end; i++) {
                    readValue(key, getNextByte() & 0xff, end, false);
                }
            }
            case AMF_DATE -> skipBytes(8 + 2);
            case AMF_NULL, AMF_UNDEFINED -> { /* no payload */ }
            default -> throw new java.io.EOFException("unhandled AMF type " + type);
        }
    }

    private void readNestedProperties(long end) throws Exception {
        while (getStreamPosition() < end) {
            String key = readShortString();
            int type = getNextByte() & 0xff;
            if (key.isEmpty() && type == AMF_OBJECT_END) {
                return;
            }
            readValue(key, type, end, false);
        }
    }

    private void addNumber(String key, double v) {
        if ("duration".equals(key)) {
            long millis = (long) (v * 1000.0);
            if (millis > 0) {
                addMetadataItem(DURATION_TAG_NAME, Long.valueOf(millis));
            }
        } else if (v == Math.floor(v) && !Double.isInfinite(v)) {
            addMetadataItem(key, Integer.valueOf((int) v));
        } else {
            addMetadataItem(key, Double.valueOf(v));
        }
    }

    private String readShortString() throws Exception {
        int len = getShort() & 0xffff;
        return (len == 0) ? "" : getString(len, StandardCharsets.UTF_8);
    }
}
