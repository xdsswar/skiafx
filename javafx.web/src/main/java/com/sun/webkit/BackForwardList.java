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

package com.sun.webkit;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import com.sun.webkit.event.WCChangeEvent;
import com.sun.webkit.event.WCChangeListener;
import com.sun.webkit.graphics.WCImage;

import static com.sun.webkit.network.URLs.newURL;

public final class BackForwardList {

    public static final class Entry {
        /**
         * Native pointer to the HistoryItem object.
         * If 0, the corresponding object has already been destroyed.
         */
        private long pitem = 0;

        // Native pointer to the Page object.
        private long ppage = 0;

        private Entry[] children;
        private URL url;
        private String title;
        private Date lastVisitedDate;
        private WCImage icon;
        private String target;
        private boolean isTargetItem;

        // Only called from the native code.
        private Entry(long pitem, long ppage) {
            this.pitem = pitem;
            this.ppage = ppage;

            // When the Entry is disposed its fields may be
            // left uninitialized. As the Entry may still
            // be referenced from a global history, the fields
            // should get their initial values here.
            getURL();
            getTitle();
            getLastVisitedDate();
            getIcon();
            getTarget();
            isTargetItem();
            getChildren();
        }

        // skia-fx Blink: construct an entry from engine-reported session history
        // (no native HistoryItem). With pitem==0 the public getters return these
        // cached fields directly rather than calling the retired bfl* natives.
        Entry(String urlStr, String title) {
            this.pitem = 0;
            this.ppage = 0;
            this.children = new Entry[0];
            this.target = null;
            this.isTargetItem = true;
            updateEngineFields(urlStr, title);
        }

        // skia-fx Blink: refresh the mutable fields when the engine re-reports
        // this history position, and notify listeners.
        void updateEngineFields(String urlStr, String title) {
            try {
                this.url = (urlStr == null || urlStr.isEmpty()) ? null : newURL(urlStr);
            } catch (MalformedURLException ex) {
                this.url = null;
            }
            this.title = title;
            this.lastVisitedDate = new Date(System.currentTimeMillis());
            notifyItemChanged();
        }

        // Only called from the native code.
        private void notifyItemDestroyed() {
            pitem = 0;
        }

        // Called from the native code as well.
        private void notifyItemChanged() {
            for (WCChangeListener l : listenerList) {
                l.stateChanged(new WCChangeEvent(this));
            }
        }

        public URL getURL() {
            try {
                return (pitem == 0 ? url : (url = newURL(bflItemGetURL(pitem))));
            } catch (MalformedURLException ex) {
                return url = null;
            }
        }

        public String getTitle() {
            return (pitem == 0 ? title : (title = bflItemGetTitle(pitem)));
        }

        public WCImage getIcon() {
            return (pitem == 0 ? icon : (icon = bflItemGetIcon(pitem)));
        }

        public String getTarget() {
            return (pitem == 0 ? target : (target = bflItemGetTarget(pitem)));
        }

        public Date getLastVisitedDate() {
            return lastVisitedDate == null ? null : (Date)lastVisitedDate.clone();
        }

        private void updateLastVisitedDate() {
            lastVisitedDate = new Date(System.currentTimeMillis());
            notifyItemChanged();
        }

        public boolean isTargetItem() {
            return (pitem == 0 ? isTargetItem : (isTargetItem = bflItemIsTargetItem(pitem)));
        }

        public Entry[] getChildren() {
            return (pitem == 0 ? children : (children = bflItemGetChildren(pitem, ppage)));
        }

        @Override
        public String toString() {
            return "url=" + getURL() +
                    ",title=" + getTitle() +
                    ",date=" + getLastVisitedDate();
        }


        private final List<WCChangeListener> listenerList =
            new LinkedList<>();

        public void addChangeListener(WCChangeListener l) {
            if (l == null)
                return;
            listenerList.add(l);
        }

        public void removeChangeListener(WCChangeListener l) {
            if (l == null)
                return;
            listenerList.remove(l);
        }
    }

    private final WebPage page;
    private final List<WCChangeListener> listenerList =
        new LinkedList<>();

    BackForwardList(WebPage page) {
        this.page = page;

        // WebKit doesn't set a page's visiting date. We do it here as workaround.
        // This way it works for page reload as well.
        page.addLoadListenerClient(new LoadListenerClient() {
            @Override public void dispatchLoadEvent(long frame,
                                                    int state,
                                                    String url,
                                                    String contentType,
                                                    double progress,
                                                    int errorCode)
            {
                if (state == LoadListenerClient.DOCUMENT_AVAILABLE) {
                    Entry entry = getCurrentEntry();
                    if (entry != null) {
                        entry.updateLastVisitedDate();
                    }
                }
            }

            @Override public void dispatchResourceLoadEvent(long frame,
                                                            int state,
                                                            String url,
                                                            String contentType,
                                                            double progress,
                                                            int errorCode)
            {}
        });
    }

    // skia-fx Blink: session history is fed by the engine (HISTORY_STATE events
    // → updateFromEngine), mirroring Chromium's NavigationController, rather than
    // the retired jfxwebkit bfl* natives. setCurrentIndex navigates the engine,
    // which echoes a fresh snapshot. The native declarations below are kept
    // (unused) so the rest of the class is otherwise untouched.

    private final List<Entry> engineEntries = new ArrayList<>();
    private int engineCurrentIndex = -1;
    private int maximumSize = 100;

    public int size() {
        return engineEntries.size();
    }

    public int getMaximumSize() {
        return maximumSize;
    }

    public void setMaximumSize(int size) {
        this.maximumSize = size;
    }

    public int getCurrentIndex() {
        return engineCurrentIndex;
    }

    public boolean isEmpty() {
        return engineEntries.isEmpty();
    }

    public void setEnabled(boolean flag) {
        // Always enabled on the Blink path (no native toggle).
    }

    public boolean isEnabled() {
        return true;
    }

    public Entry get(int index) {
        return (index >= 0 && index < engineEntries.size()) ? engineEntries.get(index) : null;
    }

    public Entry getCurrentEntry() {
        return get(engineCurrentIndex);
    }

    public void clearBackForwardListForDRT() {
        engineEntries.clear();
        engineCurrentIndex = -1;
        notifyChanged();
    }

    public int indexOf(Entry e) {
        return engineEntries.indexOf(e);
    }

    public boolean contains(Entry e) {
        return indexOf(e) >= 0;
    }

    public Entry[] toArray() {
        return engineEntries.toArray(new Entry[0]);
    }

    /**
     * Navigates to an absolute history index. On the Blink path this does not
     * mutate local state directly: it asks the engine to navigate by the
     * corresponding offset, and the resulting committed navigation echoes a
     * fresh HISTORY_STATE that updates this list (and the current index).
     */
    public void setCurrentIndex(int index) {
        if (index == engineCurrentIndex || index < 0 || index >= engineEntries.size()) {
            return;
        }
        page.navigateToHistoryOffset(index - engineCurrentIndex);
    }

    /**
     * Applies an engine session-history snapshot (FX thread). Reconciles in
     * place, preserving {@link Entry} identity for positions whose URL is
     * unchanged so {@code WebHistory}'s incremental change-listener recognizes
     * peers, then fires a single change notification.
     */
    public void updateFromEngine(int newCurrentIndex, String[] urls, String[] titles) {
        int n = (urls == null) ? 0 : urls.length;
        // Drop any removed tail (e.g. a new navigation truncated forward entries).
        while (engineEntries.size() > n) {
            engineEntries.remove(engineEntries.size() - 1);
        }
        for (int i = 0; i < n; i++) {
            String u = urls[i];
            String t = (titles != null && i < titles.length) ? titles[i] : null;
            if (i < engineEntries.size()) {
                Entry e = engineEntries.get(i);
                String existing = (e.getURL() == null) ? "" : e.getURL().toString();
                if (!existing.equals(u == null ? "" : u)) {
                    engineEntries.set(i, new Entry(u, t)); // diverged → new identity
                } else {
                    e.updateEngineFields(u, t);            // same URL → refresh title/date
                }
            } else {
                engineEntries.add(new Entry(u, t));
            }
        }
        this.engineCurrentIndex = (newCurrentIndex >= 0 && newCurrentIndex < engineEntries.size())
                ? newCurrentIndex : engineEntries.size() - 1;
        notifyChanged();
    }

    private boolean canGoBack(int index) {
        return index > 0;
    }

    public boolean canGoBack() {
        return canGoBack(getCurrentIndex());
    }

    public boolean goBack() {
        int index = getCurrentIndex();
        if (canGoBack(index)) {
            setCurrentIndex(index - 1);
            return true;
        }
        return false;
    }

    private boolean canGoForward(int index) {
        return index < (size() - 1);
    }

    public boolean canGoForward() {
        return canGoForward(getCurrentIndex());
    }

    public boolean goForward() {
        int index = getCurrentIndex();
        if (canGoForward(index)) {
            setCurrentIndex(index + 1);
            return true;
        }
        return false;
    }

    public void addChangeListener(WCChangeListener l) {
        if (l == null) {
            return;
        }
        listenerList.add(l);
    }

    public void removeChangeListener(WCChangeListener l) {
        if (l == null) {
            return;
        }
        listenerList.remove(l);
    }

    public WCChangeListener[] getChangeListeners() {
        return listenerList.toArray(new WCChangeListener[0]);
    }

    // Only called from the native code.
    private void notifyChanged() {
        for (WCChangeListener l : listenerList) {
            l.stateChanged(new WCChangeEvent(this));
        }
    }

    native private static String bflItemGetURL(long item);
    native private static String bflItemGetTitle(long item);
    native private static WCImage bflItemGetIcon(long item);
    native private static long bflItemGetLastVisitedDate(long item);
    native private static boolean bflItemIsTargetItem(long item);
    native private static Entry[] bflItemGetChildren(long item, long page);
    native private static String bflItemGetTarget(long item);
    native private static void bflClearBackForwardListForDRT(long page);

    native private static int bflSize(long page);
    native private static int bflGetMaximumSize(long page);
    native private static void bflSetMaximumSize(long page, int size);
    native private static int bflGetCurrentIndex(long page);
    native private static int bflIndexOf(long page, long item, boolean reverse);
    native private static void bflSetEnabled(long page, boolean flag);
    native private static boolean bflIsEnabled(long page);
    native private static Object bflGet(long page, int index);
    native private static int bflSetCurrentIndex(long page, int index);
    native private static void bflSetHostObject(long page, Object host);
}
