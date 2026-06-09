/*
 * Copyright (c) 2011, 2017, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.webkit;

import java.util.List;

import com.sun.webkit.blink.BlinkPage;
import com.sun.webkit.graphics.WCImage;
import com.sun.webkit.graphics.WCRectangle;

public interface UIClient {

    public WebPage createPage(
            boolean menu, boolean status, boolean toolbar, boolean resizable);
    public void closePage();
    public void showView();
    public WCRectangle getViewBounds();
    public void setViewBounds(WCRectangle bounds);

    public void setStatusbarText(String text);

    public void alert(String text);
    public boolean confirm(String text);
    public String prompt(String text, String defaultValue);

    public boolean canRunBeforeUnloadConfirmPanel();
    public boolean runBeforeUnloadConfirmPanel(String message);

    public String[] chooseFile(String initialFileName, boolean multiple, String mimeFilters);
    public void print();

    public void startDrag(
            WCImage frame,
            int imageOffsetX, int imageOffsetY,
            int eventPosX, int eventPosY,
            String[] mimeTypes, Object[] values,
            boolean isImageSource);
    public void confirmStartDrag();
    public boolean isDragConfirmed();

    // Off-screen chooser requests surfaced by the Blink engine (no native popup
    // exists for an off-screen WebView). Default no-ops keep other implementors
    // source-compatible; UIClientImpl routes them to the WebEngine handlers.

    /** A page {@code <input type=color>} requested a color (rgba = 0xRRGGBBAA). */
    default void openColorChooser(int id, int initialRgba, int[] suggestionsRgba) { }

    /** A page {@code <input type=file>} requested files (mode 0=open,1=multiple,
     *  2=uploadFolder,3=directory,4=save; acceptCsv is '\n'-joined). */
    default void openFileChooser(int id, int mode, String title,
                                 String initialName, String acceptCsv) { }

    /** The engine needs a save location for a print-to-PDF (WebEngine.print() /
     *  the print preview's Save button). Show a save dialog and answer via the
     *  page's {@code respondSavePdf}. */
    default void savePdf(int requestId, String defaultName) { }

    /** A page HTML {@code <select>} opened its drop-down. */
    default void openSelectPopup(int id, boolean multiple, int selectedIndex,
            double anchorX, double anchorY, double anchorW, double anchorH,
            List<BlinkPage.SelectItemData> items) { }

    /** A page requested a capability (geolocation/notifications/camera/…). */
    default void requestPermission(int id, int permType, String origin) { }

    /** An HTTP/proxy authentication challenge was raised. */
    default void requestAuth(int id, int scheme, boolean proxy, String host, String realm) { }

    /** A download is starting and needs an accept/deny + target path. */
    default void requestDownload(int id, String url, String suggestedName,
            String mimeType, long totalBytes) { }

    /** Progress for an accepted download. */
    default void downloadProgress(int id, int state, long received, long total) { }

    /** An accepted download reached a terminal state. */
    default void downloadFinished(int id, int state, String path) { }

    /**
     * The user right-clicked; the app builds the menu and renders it itself
     * (the WebView layer shows a JavaFX context menu in the foreground process).
     */
    default void contextMenu(int menuId, double x, double y, int flags, String linkUrl,
            String srcUrl, String selection) { }

    /** A page requested a fullscreen transition. */
    default void fullscreenRequest(int id, boolean entering) { }

    /** The page's favicon URL changed. */
    default void faviconChanged(String iconUrl) { }

    /** The hovered element's tooltip text changed (empty when none). */
    default void tooltipChanged(String text) { }

    /** A matched request is about to be sent (interception request phase). */
    default void networkRequest(int interceptId, int resourceType, String method,
            String url, String[] headerNames, String[] headerValues) { }

    /** Response headers arrived for a matched request (interception response phase). */
    default void networkResponse(int interceptId, int status, String mimeType,
            long contentLength, String[] headerNames, String[] headerValues) { }

    /** An intercepted exchange completed. */
    default void networkComplete(int interceptId, int netError) { }

    /** A captured response body chunk arrived (whole body, {@code last=true}). */
    default void networkBodyChunk(int interceptId, int chunkSeq, long offset,
            boolean last, byte[] bytes) { }
}
