/*
 * Copyright (c) 2011, 2014, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.javafx.webkit;

import java.util.List;

import javafx.beans.InvalidationListener;
import javafx.scene.Node;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import com.sun.webkit.WebPage;
import com.sun.webkit.blink.BlinkPage;

public abstract class Accessor {

    public static interface PageAccessor {
        public WebPage getPage(WebEngine w);
    }

    private static PageAccessor pageAccessor;

    public static void setPageAccessor(PageAccessor instance) {
        Accessor.pageAccessor = instance;
    }

    public static WebPage getPageFor(WebEngine w) {
        return pageAccessor.getPage(w);
    }

    public abstract WebEngine getEngine();
    public abstract WebView getView();
    public abstract WebPage getPage();
    public abstract void addChild(Node child);
    public abstract void removeChild(Node child);
    public abstract void addViewListener(InvalidationListener l);

    // Off-screen chooser dispatch. Implemented by WebEngine.AccessorImpl, which
    // can build the package-private public token classes and invoke the engine's
    // handler (or cancel when none is set). Routed here from UIClientImpl so the
    // request crosses from the engine bridge into javafx.scene.web cleanly.
    public abstract void openColorChooser(int id, int initialRgba, int[] suggestionsRgba);

    // File-input picker: WebEngine.AccessorImpl builds the FileChooserRequest and
    // either hands it to the app's FileChooserHandler (override) or shows the
    // built-in JavaFX FileChooser (default). mode: 0=open,1=openMultiple,
    // 2=uploadFolder,3=openDirectory,4=save. acceptCsv is '\n'-joined accept tokens.
    public abstract void openFileChooser(int id, int mode, String title,
                                         String initialName, String acceptCsv);

    // Print-to-PDF save location: AccessorImpl shows a JavaFX FileChooser (owned by
    // the WebView's Stage) and answers via WebPage.respondSavePdf.
    public abstract void savePdf(int requestId, String defaultName);

    public abstract void openSelectPopup(int id, boolean multiple, int selectedIndex,
            double anchorX, double anchorY, double anchorW, double anchorH,
            List<BlinkPage.SelectItemData> items);

    // Permission / auth / download dispatch (built into javafx.scene.web tokens
    // by WebEngine.AccessorImpl, then handed to the matching WebEngine handler).
    public abstract void firePermissionRequest(int id, int permType, String origin);

    public abstract void fireAuthRequest(int id, int scheme, boolean proxy,
            String host, String realm);

    public abstract void fireDownloadRequest(int id, String url, String suggestedName,
            String mimeType, long totalBytes);

    public abstract void fireDownloadProgress(int id, int state, long received, long total);

    public abstract void fireDownloadFinished(int id, int state, String path);

    // Context menu / fullscreen / favicon / tooltip dispatch.
    public abstract void fireContextMenu(int menuId, double x, double y, int flags,
            String linkUrl, String srcUrl, String selection);

    public abstract void fireFullscreenRequest(int id, boolean entering);

    public abstract void fireFaviconChanged(String iconUrl);

    public abstract void fireTooltipChanged(String text);

    // Network interception dispatch (builds NetworkExchange tokens in
    // WebEngine.AccessorImpl and invokes the registered NetworkInterceptor).
    public abstract void fireNetworkRequest(int interceptId, int resourceType,
            String method, String url, String[] headerNames, String[] headerValues);

    public abstract void fireNetworkResponse(int interceptId, int status,
            String mimeType, long contentLength,
            String[] headerNames, String[] headerValues);

    public abstract void fireNetworkComplete(int interceptId, int netError);

    public abstract void fireNetworkBodyChunk(int interceptId, int chunkSeq,
            long offset, boolean last, byte[] bytes);
}
