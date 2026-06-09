/*
 * Copyright (c) 2011, 2018, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.webkit.graphics;

import java.nio.ByteBuffer;

public abstract class WCImage extends Ref {
    private WCRenderQueue rq;
    private String fileExtension;

    public abstract int getWidth();

    public abstract int getHeight();

    public String getFileExtension() {
        return fileExtension;
    }

    public void setFileExtension(String fileExtension) {
        this.fileExtension = fileExtension;
    }

    public Object getPlatformImage() {return null;}

    /**
     * Returns a native {@code SkImage} handle suitable for the openjfx
     * skia bridge's surface_draw_image_rect entry point, lazily uploading
     * pixels on first call. Returns {@code 0L} for images that don't
     * have a stable, uploadable pixel snapshot (e.g. dynamic offscreen
     * surfaces) — callers must then fall back to the legacy RQ path.
     *
     * <p>The handle is owned by this WCImage and released on
     * {@link Ref#deref() deref} / dispose. Callers must NOT call
     * {@code openjfx_skia_image_destroy} on the returned handle.</p>
     */
    public long getOrCreateSkImageHandle() { return 0L; }

    /**
     * Encodes the image to a compressed byte stream in the requested
     * MIME type ("image/png", "image/jpeg", "image/webp"). Returns
     * {@code null} on unsupported MIME or encode failure. Replaces the
     * historical {@code toBufferedImage()} + {@code ImageIO.write} path,
     * which required {@code java.desktop} (AWT). Now goes straight
     * through the openjfx skia bridge encoder.
     */
    public abstract byte[] toData(String mimeType);

    protected abstract String toDataURL(String mimeType);

    public ByteBuffer getPixelBuffer() {return null;}

    protected void drawPixelBuffer() {}

    public synchronized void setRQ(WCRenderQueue rq) {
        this.rq = rq;
    }

    // should be called on render thread
    protected synchronized void flushRQ() {
        if (rq != null) {
            rq.decode();
        }
    }

    protected synchronized boolean isDirty() {
        return (rq == null)
           ? false
           : !rq.isEmpty();
    }

    public static WCImage getImage(Object imgFrame) {
        WCImage img = null;
        if (imgFrame instanceof WCImage) {
            //from BufferImage.drawPattern (canvas/fill layer):
            //NativeImagePtr is a wrapper over the WCImage
            img = (WCImage)imgFrame;
        } else if (imgFrame instanceof WCImageFrame) {
            //from BitmapImage.drawPattern (decoder/GIF animator):
            //NativeImagePtr is a wrapper over the WCImageFrame
            img = ((WCImageFrame)imgFrame).getFrame();
        }
        return img;
    }

    public boolean isNull() {
        return getWidth() <= 0 || getHeight() <= 0 || getPlatformImage() == null;
    }

    /**
     * Static convenience used by native callers: resolves either a
     * {@link WCImage} or a {@link WCImageFrame} via {@link #getImage(Object)}
     * and returns its native SkImage handle, or 0L if unresolvable / not
     * uploadable. One JNI call instead of two.
     */
    public static long getSkImageHandle(Object imgOrFrame) {
        WCImage img = getImage(imgOrFrame);
        return img != null ? img.getOrCreateSkImageHandle() : 0L;
    }

    public abstract float getPixelScale();
}
