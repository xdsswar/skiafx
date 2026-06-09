/*
 * Copyright (c) 2011, 2024, Oracle and/or its affiliates. All rights reserved.
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

import static com.sun.glass.ui.Clipboard.DRAG_IMAGE;
import static com.sun.glass.ui.Clipboard.DRAG_IMAGE_OFFSET;
import static com.sun.glass.ui.Clipboard.IE_URL_SHORTCUT_FILENAME;
import static javafx.scene.web.WebEvent.ALERT;
import static javafx.scene.web.WebEvent.RESIZED;
import static javafx.scene.web.WebEvent.STATUS_CHANGED;
import static javafx.scene.web.WebEvent.VISIBILITY_CHANGED;

import com.sun.webkit.UIClient;
import com.sun.webkit.WebPage;
import com.sun.webkit.blink.BlinkPage;
import com.sun.webkit.graphics.WCImage;
import com.sun.webkit.graphics.WCRectangle;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.event.EventHandler;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.web.PopupFeatures;
import javafx.scene.web.PromptData;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebEvent;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Window;

public final class UIClientImpl implements UIClient {

    private static String mimeForExtension(String ext) {
        if (ext == null) return "image/png";
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp"        -> "image/webp";
            default            -> "image/png";
        };
    }

    private final Accessor accessor;
    private FileChooser chooser;
    private static final Map<String, FileExtensionInfo> fileExtensionMap = new HashMap<>();
    // for testing purposes only
    private static String[] chooseFiles = null;

    private static class FileExtensionInfo {
        private String description;
        private List<String> extensions;
        static void add(String type, String description, String... extensions) {
            FileExtensionInfo info = new FileExtensionInfo();
            info.description = description;
            info.extensions = Arrays.asList(extensions);
            fileExtensionMap.put(type, info);
        }

        private ExtensionFilter getExtensionFilter(String type) {
            final String extensionType = "*." + type;
            String desc = this.description + " ";

            if (type.equals("*")) {
                desc += extensions.stream().collect(java.util.stream.Collectors.joining(", ", "(", ")"));
                return new ExtensionFilter(desc, this.extensions);
            } else if (extensions.contains(extensionType)) {
                desc += "(" + extensionType + ")";
                return new ExtensionFilter(desc, extensionType);
            }
            return null;
        }
    }

    static {
        FileExtensionInfo.add("video", "Video Files", "*.webm", "*.mp4", "*.ogg");
        FileExtensionInfo.add("audio", "Audio Files", "*.mp3", "*.aac", "*.wav");
        FileExtensionInfo.add("text", "Text Files", "*.txt", "*.csv", "*.text", "*.ttf", "*.sdf", "*.srt", "*.htm", "*.html");
        FileExtensionInfo.add("image", "Image Files", "*.png", "*.jpg", "*.gif", "*.bmp", "*.jpeg");
    }

    public UIClientImpl(Accessor accessor) {
        this.accessor = accessor;
    }

    private WebEngine getWebEngine() {
        return accessor.getEngine();
    }

    @Override public WebPage createPage(
            boolean menu, boolean status, boolean toolbar, boolean resizable) {
        final WebEngine w = getWebEngine();
        if (w != null && w.getCreatePopupHandler() != null) {
            final PopupFeatures pf =
                    new PopupFeatures(menu, status, toolbar, resizable);
            WebEngine popup = w.getCreatePopupHandler().call(pf);
            return Accessor.getPageFor(popup);
        }
        return null;
    }

    private void dispatchWebEvent(final EventHandler handler, final WebEvent ev) {
        handler.handle(ev);
    }

    private void notifyVisibilityChanged(boolean visible) {
        WebEngine w = getWebEngine();
        if (w != null && w.getOnVisibilityChanged() != null) {
            dispatchWebEvent(
                    w.getOnVisibilityChanged(),
                    new WebEvent<>(w, VISIBILITY_CHANGED, visible));
        }
    }

    @Override public void closePage() {
        notifyVisibilityChanged(false);
    }

    @Override public void showView() {
        notifyVisibilityChanged(true);
    }

    @Override public WCRectangle getViewBounds() {
        WebView view = accessor.getView();
        Window win = null;
        if (view != null &&
            view.getScene() != null &&
            (win = view.getScene().getWindow()) != null)
        {
            return new WCRectangle(
                    (float) win.getX(), (float) win.getY(),
                    (float) win.getWidth(), (float) win.getHeight());
        }
        return null;
    }

    @Override public void setViewBounds(WCRectangle r) {
        WebEngine w = getWebEngine();
        if (w != null && w.getOnResized() != null) {
            dispatchWebEvent(
                    w.getOnResized(),
                    new WebEvent<>(w, RESIZED,
                        new Rectangle2D(r.getX(), r.getY(), r.getWidth(), r.getHeight())));
        }
    }

    @Override public void setStatusbarText(String text) {
        WebEngine w = getWebEngine();
        if (w != null && w.getOnStatusChanged() != null) {
            dispatchWebEvent(
                    w.getOnStatusChanged(),
                    new WebEvent<>(w, STATUS_CHANGED, text));
        }
    }

    @Override public void alert(String text) {
        WebEngine w = getWebEngine();
        if (w != null && w.getOnAlert() != null) {
            dispatchWebEvent(
                    w.getOnAlert(),
                    new WebEvent<>(w, ALERT, text));
            return;
        }
        // No app handler: show a built-in JavaFX alert (modal to the WebView's
        // window) rather than silently dropping the page's alert(). Runs on the
        // FX thread (the engine posts dialog requests here), so showAndWait is
        // valid; the page's JS stays suspended until it returns.
        Alert dlg = new Alert(Alert.AlertType.INFORMATION, safe(text), ButtonType.OK);
        dlg.setHeaderText(null);
        initDialogOwner(dlg);
        dlg.showAndWait();
    }

    @Override public boolean confirm(final String text) {
        final WebEngine w = getWebEngine();
        if (w != null && w.getConfirmHandler() != null) {
            return w.getConfirmHandler().call(text);
        }
        Alert dlg = new Alert(Alert.AlertType.CONFIRMATION, safe(text),
                              ButtonType.OK, ButtonType.CANCEL);
        dlg.setHeaderText(null);
        initDialogOwner(dlg);
        return dlg.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    @Override public String prompt(String text, String defaultValue) {
        final WebEngine w = getWebEngine();
        if (w != null && w.getPromptHandler() != null) {
            final PromptData data = new PromptData(text, defaultValue);
            return w.getPromptHandler().call(data);
        }
        TextInputDialog dlg = new TextInputDialog(safe(defaultValue));
        dlg.setHeaderText(null);
        dlg.setContentText(safe(text));
        initDialogOwner(dlg);
        // orElse(null): a cancelled prompt returns null (the JS prompt() contract).
        return dlg.showAndWait().orElse(null);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** Anchors a default dialog to the WebView's window (modal) when shown. */
    private void initDialogOwner(Dialog<?> dlg) {
        WebView view = accessor.getView();
        if (view != null && view.getScene() != null
                && view.getScene().getWindow() != null) {
            dlg.initOwner(view.getScene().getWindow());
        }
    }

    @Override public boolean canRunBeforeUnloadConfirmPanel() {
        return false;
    }

    @Override public boolean runBeforeUnloadConfirmPanel(String message) {
        return false;
    }

    // for testing purposes only
    static void test_setChooseFiles(String[] files) {
        chooseFiles = files;
    }

    @Override public String[] chooseFile(String initialFileName, boolean multiple, String mimeFilters) {
        if (chooseFiles != null) {
            return chooseFiles;
        }
        // get the toplevel window
        Window win = null;
        WebView view = accessor.getView();
        if (view != null && view.getScene() != null) {
            win = view.getScene().getWindow();
        }

        if (chooser == null) {
            chooser = new FileChooser();
        }

        // Remove old filters, add specific filters and finally add generic filter
        chooser.getExtensionFilters().clear();
        if (mimeFilters != null && !mimeFilters.isEmpty()) {
            addMimeFilters(chooser, mimeFilters);
        }
        chooser.getExtensionFilters().addAll(new ExtensionFilter("All Files", "*.*"));

        // set initial directory
        if (initialFileName != null) {
            File dir = new File(initialFileName);
            while (dir != null && !dir.isDirectory()) {
                dir = dir.getParentFile();
            }
            chooser.setInitialDirectory(dir);
        }

        if (multiple) {
            List<File> files = chooser.showOpenMultipleDialog(win);
            if (files != null) {
                int n = files.size();
                String[] result = new String[n];
                for (int i = 0; i < n; i++) {
                    result[i] = files.get(i).getAbsolutePath();
                }
                return result;
            }
            return null;
        } else {
            File f = chooser.showOpenDialog(win);
            return f != null
                    ? new String[] { f.getAbsolutePath() }
                    : null;
        }
    }

    private void addSpecificFilters(FileChooser chooser, String mimeString) {
        if (mimeString.contains("/")) {
            final String splittedMime[] = mimeString.split("/");
            final String mainType = splittedMime[0];
            final String subType = splittedMime[1];
            final FileExtensionInfo extensionValue = fileExtensionMap.get(mainType);

            if (extensionValue != null) {
                ExtensionFilter extFilter = extensionValue.getExtensionFilter(subType);
                if(extFilter != null) {
                    chooser.getExtensionFilters().addAll(extFilter);
                }
            }
        }
    }

    private void addMimeFilters(FileChooser chooser, String mimeFilters) {
        if (mimeFilters.contains(",")) {
            // Filter consists of multiple MIME types
            String types[] = mimeFilters.split(",");
            for (String mimeType : types) {
                addSpecificFilters(chooser, mimeType);
            }
        } else {
            // Filter consists of single MIME type
            addSpecificFilters(chooser, mimeFilters);
        }
    }

    @Override public void print() {
    }

    @Override public void openColorChooser(int id, int initialRgba, int[] suggestionsRgba) {
        accessor.openColorChooser(id, initialRgba, suggestionsRgba);
    }

    @Override public void openFileChooser(int id, int mode, String title,
                                          String initialName, String acceptCsv) {
        accessor.openFileChooser(id, mode, title, initialName, acceptCsv);
    }

    @Override public void savePdf(int requestId, String defaultName) {
        accessor.savePdf(requestId, defaultName);
    }

    @Override public void openSelectPopup(int id, boolean multiple, int selectedIndex,
            double anchorX, double anchorY, double anchorW, double anchorH,
            List<BlinkPage.SelectItemData> items) {
        accessor.openSelectPopup(id, multiple, selectedIndex,
            anchorX, anchorY, anchorW, anchorH, items);
    }

    @Override public void requestPermission(int id, int permType, String origin) {
        accessor.firePermissionRequest(id, permType, origin);
    }

    @Override public void requestAuth(int id, int scheme, boolean proxy, String host, String realm) {
        accessor.fireAuthRequest(id, scheme, proxy, host, realm);
    }

    @Override public void requestDownload(int id, String url, String suggestedName,
            String mimeType, long totalBytes) {
        accessor.fireDownloadRequest(id, url, suggestedName, mimeType, totalBytes);
    }

    @Override public void downloadProgress(int id, int state, long received, long total) {
        accessor.fireDownloadProgress(id, state, received, total);
    }

    @Override public void downloadFinished(int id, int state, String path) {
        accessor.fireDownloadFinished(id, state, path);
    }

    @Override public void contextMenu(int menuId, double x, double y, int flags, String linkUrl,
            String srcUrl, String selection) {
        accessor.fireContextMenu(menuId, x, y, flags, linkUrl, srcUrl, selection);
    }

    @Override public void fullscreenRequest(int id, boolean entering) {
        accessor.fireFullscreenRequest(id, entering);
    }

    @Override public void faviconChanged(String iconUrl) {
        accessor.fireFaviconChanged(iconUrl);
    }

    @Override public void tooltipChanged(String text) {
        accessor.fireTooltipChanged(text);
    }

    @Override public void networkRequest(int interceptId, int resourceType, String method,
            String url, String[] headerNames, String[] headerValues) {
        accessor.fireNetworkRequest(interceptId, resourceType, method, url, headerNames, headerValues);
    }

    @Override public void networkResponse(int interceptId, int status, String mimeType,
            long contentLength, String[] headerNames, String[] headerValues) {
        accessor.fireNetworkResponse(interceptId, status, mimeType, contentLength, headerNames, headerValues);
    }

    @Override public void networkComplete(int interceptId, int netError) {
        accessor.fireNetworkComplete(interceptId, netError);
    }

    @Override public void networkBodyChunk(int interceptId, int chunkSeq,
            long offset, boolean last, byte[] bytes) {
        accessor.fireNetworkBodyChunk(interceptId, chunkSeq, offset, last, bytes);
    }

    private ClipboardContent content;
    private static DataFormat getDataFormat(String mimeType) {
        synchronized (DataFormat.class) {
            DataFormat ret = DataFormat.lookupMimeType(mimeType);
            if (ret == null) {
                ret = new DataFormat(mimeType);
            }
            return ret;
        }
    }

    //copy from com.sun.glass.ui.Clipboard
    private final static DataFormat DF_DRAG_IMAGE = getDataFormat(DRAG_IMAGE);
    private final static DataFormat DF_DRAG_IMAGE_OFFSET = getDataFormat(DRAG_IMAGE_OFFSET);

    @Override public void startDrag(WCImage image,
        int imageOffsetX, int imageOffsetY,
        int eventPosX, int eventPosY,
        String[] mimeTypes, Object[] values, boolean isImageSource
    ){
        content = new ClipboardContent();
        for (int i = 0; i < mimeTypes.length; ++i) if (values[i] != null) {
            try {
                content.put(getDataFormat(mimeTypes[i]),
                    IE_URL_SHORTCUT_FILENAME.equals(mimeTypes[i])
                        ? (Object)ByteBuffer.wrap(((String)values[i]).getBytes("UTF-16LE"))
                        : (Object)values[i]);
            } catch (UnsupportedEncodingException ex) {
                //never happens
            }
        }
        if (image != null && !image.isNull()) {
            ByteBuffer dragImageOffset = ByteBuffer.allocate(8);
            dragImageOffset.rewind();
            dragImageOffset.putInt(imageOffsetX);
            dragImageOffset.putInt(imageOffsetY);
            content.put(DF_DRAG_IMAGE_OFFSET, dragImageOffset);

            int w = image.getWidth();
            int h = image.getHeight();
            ByteBuffer pixels = image.getPixelBuffer();

            ByteBuffer dragImage = ByteBuffer.allocate(8 + w*h*4);
            dragImage.putInt(w);
            dragImage.putInt(h);
            dragImage.put(pixels);
            content.put(DF_DRAG_IMAGE, dragImage);

            //The image is prepared synchronously, that is sad.
            //Image need to be created by target request only.
            //QuantumClipboard.putContent have to be rewritten in Glass manner
            //with postponed data requests (DelayedCallback data object).
            if (isImageSource) {
                String fileExtension = image.getFileExtension();
                try {
                    byte[] encoded = image.toData(mimeForExtension(fileExtension));
                    if (encoded != null) {
                        File temp = File.createTempFile("jfx", "." + fileExtension);
                        temp.deleteOnExit();
                        java.nio.file.Files.write(temp.toPath(), encoded);
                        content.put(DataFormat.FILES, Arrays.asList(temp));
                    }
                } catch (IOException | SecurityException e) {
                    //That is ok. It was just an attempt.
                    //e.printStackTrace();
                }
            }
        }
    }

    @Override public void confirmStartDrag() {
        WebView view = accessor.getView();
        if (view != null && content != null) {
            //TODO: implement native support for Drag Source actions.
            Dragboard db = view.startDragAndDrop(TransferMode.ANY);
            db.setContent(content);
        }
        content = null;
    }

    @Override public boolean isDragConfirmed() {
        return accessor.getView() != null && content != null;
    }

}
