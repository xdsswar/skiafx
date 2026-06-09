/*
 * Copyright (c) 2026, skia-fx. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The skia-fx project
 * designates this particular file as subject to the "Classpath" exception
 * as provided in the LICENSE file that accompanied this code.
 */
package javafx.scene.web;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Narrows which requests a {@link NetworkInterceptor} sees. The filter is
 * evaluated <b>in the engine</b>, so requests that don't match never cross to
 * the Java side — keeping interception cheap. Build with {@link #builder()} or
 * use {@link #matchAll()}.
 *
 * @since 25
 */
public final class NetworkFilter {

    /** The interception phase(s) a filter arms. */
    public enum Phase {
        /** Before the request is sent (block/redirect/modify/synthetic). */
        REQUEST(0x1),
        /** After response headers arrive (status/header edits, body capture). */
        RESPONSE(0x2);

        private final int bit;

        Phase(int bit) {
            this.bit = bit;
        }

        int bit() {
            return bit;
        }
    }

    private final List<String> includePatterns;
    private final List<String> excludePatterns;
    private final EnumSet<ResourceType> resourceTypes;
    private final List<String> methods;
    private final EnumSet<Phase> phases;
    private final boolean captureBodies;
    private final long maxBodyBytes;

    private NetworkFilter(Builder b) {
        this.includePatterns = List.copyOf(b.includePatterns);
        this.excludePatterns = List.copyOf(b.excludePatterns);
        this.resourceTypes = b.resourceTypes.isEmpty()
            ? EnumSet.allOf(ResourceType.class) : EnumSet.copyOf(b.resourceTypes);
        this.methods = List.copyOf(b.methods);
        this.phases = b.phases.isEmpty() ? EnumSet.of(Phase.REQUEST) : EnumSet.copyOf(b.phases);
        this.captureBodies = b.captureBodies;
        this.maxBodyBytes = b.maxBodyBytes;
    }

    /**
     * Returns a filter that matches every request (request phase only).
     * @return a match-all filter
     */
    public static NetworkFilter matchAll() {
        return builder().build();
    }

    /**
     * Creates a new builder.
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Serializes this filter to the compact binary form the engine matcher reads
     * (internal). Layout (all little-endian):
     * {@code [ver:1][incCount:2]{[len:2][glob]}[excCount:2]{[len:2][glob]}
     * [typeMask:4][methodCount:2]{[len:1][method]}[phaseMask:1][capture:1][maxBody:8]}.
     * @return the serialized filter
     */
    byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1); // version
        writeStrList16(out, includePatterns);
        writeStrList16(out, excludePatterns);
        int typeMask = 0;
        for (ResourceType t : resourceTypes) {
            typeMask |= (1 << t.wireCode());
        }
        writeU32(out, typeMask);
        writeU16(out, methods.size());
        for (String m : methods) {
            byte[] b = m.getBytes(StandardCharsets.UTF_8);
            out.write(b.length & 0xFF); // u8 length (methods are short)
            out.write(b, 0, b.length);
        }
        int phaseMask = 0;
        for (Phase p : phases) {
            phaseMask |= p.bit();
        }
        out.write(phaseMask & 0xFF);
        out.write(captureBodies ? 1 : 0);
        writeU64(out, maxBodyBytes);
        return out.toByteArray();
    }

    boolean armsResponsePhase() {
        return phases.contains(Phase.RESPONSE);
    }

    boolean capturesBodies() {
        return captureBodies;
    }

    // Lazily compiled glob → regex patterns (for Java-side per-interceptor matching).
    private List<Pattern> includeRe;
    private List<Pattern> excludeRe;

    /**
     * Java-side test of whether this filter accepts a request in {@code phase}.
     * Used to pick which registered interceptor owns a given exchange.
     */
    boolean matches(String url, ResourceType type, String method, Phase phase) {
        if (!phases.contains(phase)) {
            return false;
        }
        if (type != null && !resourceTypes.contains(type)) {
            return false;
        }
        if (!methods.isEmpty() && method != null) {
            boolean ok = false;
            for (String m : methods) {
                if (m.equalsIgnoreCase(method)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return false;
            }
        }
        compilePatterns();
        String u = url == null ? "" : url;
        for (Pattern p : excludeRe) {
            if (p.matcher(u).matches()) {
                return false;
            }
        }
        if (includeRe.isEmpty()) {
            return true; // no include pattern ⇒ match all URLs
        }
        for (Pattern p : includeRe) {
            if (p.matcher(u).matches()) {
                return true;
            }
        }
        return false;
    }

    private synchronized void compilePatterns() {
        if (includeRe != null) {
            return;
        }
        List<Pattern> inc = new ArrayList<>(includePatterns.size());
        for (String g : includePatterns) {
            inc.add(globToPattern(g));
        }
        List<Pattern> exc = new ArrayList<>(excludePatterns.size());
        for (String g : excludePatterns) {
            exc.add(globToPattern(g));
        }
        excludeRe = exc;
        includeRe = inc; // set last: doubles as the "compiled" flag
    }

    private static Pattern globToPattern(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
                }
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    /**
     * Builds a conservative superset filter covering every filter in
     * {@code filters}, used to arm the engine once for several interceptors. The
     * engine gate stays broad (Java re-applies each filter precisely), so excludes
     * are dropped here and any match-all input widens the union.
     */
    static NetworkFilter union(List<NetworkFilter> filters) {
        Builder b = builder();
        boolean anyMatchAllUrls = false;
        boolean anyAllMethods = false;
        long maxBody = 0;
        for (NetworkFilter f : filters) {
            if (f.includePatterns.isEmpty()) {
                anyMatchAllUrls = true;
            } else {
                for (String g : f.includePatterns) {
                    b.includeUrlPattern(g);
                }
            }
            b.includeResourceTypes(f.resourceTypes.toArray(new ResourceType[0]));
            if (f.methods.isEmpty()) {
                anyAllMethods = true;
            } else {
                b.includeMethods(f.methods.toArray(new String[0]));
            }
            b.phases.addAll(f.phases);
            if (f.captureBodies) {
                b.captureBodies = true;
            }
            maxBody = Math.max(maxBody, f.maxBodyBytes);
        }
        if (anyMatchAllUrls) {
            b.includePatterns.clear(); // match every URL at the engine gate
        }
        if (anyAllMethods) {
            b.methods.clear();
        }
        b.maxCapturedBodyBytes(maxBody);
        return b.build();
    }

    private static void writeStrList16(ByteArrayOutputStream out, List<String> list) {
        writeU16(out, list.size());
        for (String s : list) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            writeU16(out, b.length);
            out.write(b, 0, b.length);
        }
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, int v) {
        for (int i = 0; i < 4; i++) {
            out.write((v >>> (i * 8)) & 0xFF);
        }
    }

    private static void writeU64(ByteArrayOutputStream out, long v) {
        for (int i = 0; i < 8; i++) {
            out.write((int) ((v >>> (i * 8)) & 0xFF));
        }
    }

    /**
     * Builder for {@link NetworkFilter}.
     *
     * @since 25
     */
    public static final class Builder {

        private final List<String> includePatterns = new ArrayList<>();
        private final List<String> excludePatterns = new ArrayList<>();
        private final EnumSet<ResourceType> resourceTypes = EnumSet.noneOf(ResourceType.class);
        private final List<String> methods = new ArrayList<>();
        private final EnumSet<Phase> phases = EnumSet.noneOf(Phase.class);
        private boolean captureBodies;
        private long maxBodyBytes = 8L * 1024 * 1024;

        private Builder() {
        }

        /**
         * Adds a URL glob to match (e.g. {@code "*://host/api/*"}). With no
         * include pattern, all URLs match.
         * @param glob the URL glob
         * @return this builder
         */
        public Builder includeUrlPattern(String glob) {
            if (glob != null) {
                includePatterns.add(glob);
            }
            return this;
        }

        /**
         * Adds a URL glob to exclude (takes precedence over includes).
         * @param glob the URL glob
         * @return this builder
         */
        public Builder excludeUrlPattern(String glob) {
            if (glob != null) {
                excludePatterns.add(glob);
            }
            return this;
        }

        /**
         * Restricts matching to the given resource types (default: all).
         * @param types the resource types
         * @return this builder
         */
        public Builder includeResourceTypes(ResourceType... types) {
            if (types != null) {
                for (ResourceType t : types) {
                    if (t != null) {
                        resourceTypes.add(t);
                    }
                }
            }
            return this;
        }

        /**
         * Restricts matching to the given HTTP methods (default: all).
         * @param httpMethods the methods (e.g. {@code "GET"}, {@code "POST"})
         * @return this builder
         */
        public Builder includeMethods(String... httpMethods) {
            if (httpMethods != null) {
                for (String m : httpMethods) {
                    if (m != null) {
                        methods.add(m);
                    }
                }
            }
            return this;
        }

        /**
         * Selects which phases to intercept (default: {@link Phase#REQUEST}).
         * @param wanted the phases
         * @return this builder
         */
        public Builder phases(EnumSet<Phase> wanted) {
            phases.clear();
            if (wanted != null) {
                phases.addAll(wanted);
            }
            return this;
        }

        /**
         * Arms response-body capture (delivered via
         * {@link NetworkInterceptor#onBodyChunk}). Requires {@link Phase#RESPONSE}.
         * @param on whether to capture bodies
         * @return this builder
         */
        public Builder captureResponseBodies(boolean on) {
            this.captureBodies = on;
            return this;
        }

        /**
         * Caps the bytes captured per response body; larger bodies stream through
         * without further capture. Default 4 MB.
         * @param cap the cap in bytes
         * @return this builder
         */
        public Builder maxCapturedBodyBytes(long cap) {
            this.maxBodyBytes = Math.max(0, cap);
            return this;
        }

        /**
         * Builds the filter.
         * @return the filter
         */
        public NetworkFilter build() {
            return new NetworkFilter(this);
        }
    }
}
