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

/**
 * One option in a {@link SelectPopup} (an {@code <option>} of an HTML
 * {@code <select>} element). Immutable.
 *
 * @since 25
 */
public final class SelectItem {

    private final String label;
    private final String value;
    private final boolean enabled;
    private final String group;

    SelectItem(String label, String value, boolean enabled, String group) {
        this.label = label == null ? "" : label;
        this.value = value == null ? "" : value;
        this.enabled = enabled;
        this.group = group == null ? "" : group;
    }

    /**
     * Returns the option's display text.
     * @return the label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Returns the option's form {@code value}.
     * @return the value
     */
    public String getValue() {
        return value;
    }

    /**
     * Returns whether the option is selectable (not {@code disabled}).
     * @return {@code true} if enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the label of the enclosing {@code <optgroup>}, or an empty string
     * if the option is not grouped.
     * @return the group label
     */
    public String getGroup() {
        return group;
    }
}
