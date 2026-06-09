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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An immutable, case-insensitive view of HTTP headers, as seen by a
 * {@link NetworkInterceptor}. A header name may appear more than once (e.g.
 * {@code Set-Cookie}); {@link #all(String)} returns every value, {@link #first}
 * the first.
 *
 * @since 25
 */
public final class NetworkHeaders {

    /** name (lower-case) → {originalName, values}. Insertion-ordered. */
    private final Map<String, Entry> map;

    private record Entry(String name, List<String> values) { }

    NetworkHeaders(Map<String, Entry> map) {
        this.map = map;
    }

    /**
     * Builds headers from parallel name/value arrays (internal use).
     * @param names header names
     * @param values header values
     * @return the header view
     */
    static NetworkHeaders of(String[] names, String[] values) {
        Map<String, Entry> m = new LinkedHashMap<>();
        int n = Math.min(names == null ? 0 : names.length,
                         values == null ? 0 : values.length);
        for (int i = 0; i < n; i++) {
            String key = names[i].toLowerCase(Locale.ROOT);
            Entry e = m.get(key);
            if (e == null) {
                List<String> vs = new ArrayList<>(1);
                vs.add(values[i]);
                m.put(key, new Entry(names[i], vs));
            } else {
                e.values().add(values[i]);
            }
        }
        return new NetworkHeaders(m);
    }

    /** An empty header set. */
    static NetworkHeaders empty() {
        return new NetworkHeaders(new LinkedHashMap<>());
    }

    /**
     * Returns the first value of {@code name}, or {@code null} if absent.
     * @param name the header name (case-insensitive)
     * @return the first value, or {@code null}
     */
    public String first(String name) {
        if (name == null) {
            return null;
        }
        Entry e = map.get(name.toLowerCase(Locale.ROOT));
        return e == null || e.values().isEmpty() ? null : e.values().get(0);
    }

    /**
     * Returns all values of {@code name}, in order, or an empty list if absent.
     * @param name the header name (case-insensitive)
     * @return an unmodifiable list of values
     */
    public List<String> all(String name) {
        if (name == null) {
            return List.of();
        }
        Entry e = map.get(name.toLowerCase(Locale.ROOT));
        return e == null ? List.of() : Collections.unmodifiableList(e.values());
    }

    /**
     * Returns whether {@code name} is present.
     * @param name the header name (case-insensitive)
     * @return {@code true} if present
     */
    public boolean contains(String name) {
        return name != null && map.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the header names in their original case, in insertion order.
     * @return an unmodifiable set of names
     */
    public Set<String> names() {
        Map<String, Boolean> ordered = new LinkedHashMap<>();
        for (Entry e : map.values()) {
            ordered.put(e.name(), Boolean.TRUE);
        }
        return Collections.unmodifiableSet(ordered.keySet());
    }
}
