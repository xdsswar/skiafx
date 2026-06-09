/*
 * Copyright (c) 2011, 2022, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.javafx.webkit.prism;

import java.util.Base64;

import com.sun.prism.Graphics;
import com.sun.prism.Image;
import com.sun.prism.skia.SkiaImageAccess;
import com.sun.webkit.graphics.WCImage;

/**
 * skia-fx: the upstream OpenJFX path encoded canvas / image bytes via
 * AWT BufferedImage + ImageIO. That pulled {@code java.desktop} into
 * {@code javafx.web}. We replace it with a native Skia encode through
 * the openjfx skia bridge, so no AWT/Swing leaves the dedicated
 * {@code javafx.swing} module.
 */
abstract class PrismImage extends WCImage {
    abstract Image getImage();
    abstract Graphics getGraphics();
    abstract void draw(Graphics g,
            int dstx1, int dsty1, int dstx2, int dsty2,
            int srcx1, int srcy1, int srcx2, int srcy2);
    abstract void dispose();

    @Override
    public Object getPlatformImage() {
       return getImage();
    }

    @Override
    public synchronized void deref() {
        super.deref();
        if (!hasRefs()) {
            dispose();
        }
    }

    @Override
    public final byte[] toData(String mimeType) {
        long handle = getOrCreateSkImageHandle();
        if (handle == 0L) {
            return null;
        }
        int format;
        int quality;
        switch (mimeType) {
            case "image/png"  -> { format = SkiaImageAccess.FMT_PNG;  quality = 100; }
            case "image/jpeg" -> { format = SkiaImageAccess.FMT_JPEG; quality = 92;  }
            case "image/webp" -> { format = SkiaImageAccess.FMT_WEBP; quality = 80;  }
            default -> { return null; }
        }
        return SkiaImageAccess.encodeImage(handle, format, quality);
    }

    @Override
    protected final String toDataURL(String mimeType) {
        final byte[] data = toData(mimeType);
        if (data != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("data:").append(mimeType).append(";base64,");
            sb.append(Base64.getMimeEncoder().encodeToString(data));
            return sb.toString();
        }
        return null;
    }
}
