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

package com.sun.webkit;

import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.scene.paint.Color;
import com.sun.glass.utils.NativeLibLoader;
import com.sun.prism.Graphics;
import com.sun.prism.skia.SkiaSurfaceAccess;
import com.sun.javafx.logging.PlatformLogger;
import com.sun.javafx.logging.PlatformLogger.Level;
import com.sun.javafx.tk.Toolkit;
import com.sun.webkit.event.WCFocusEvent;
import com.sun.webkit.event.WCInputMethodEvent;
import com.sun.webkit.event.WCKeyEvent;
import com.sun.webkit.event.WCMouseEvent;
import com.sun.webkit.event.WCMouseWheelEvent;
import com.sun.webkit.blink.BlinkPage;
import com.sun.webkit.graphics.*;
import com.sun.webkit.network.CookieManager;
import static com.sun.webkit.network.URLs.newURL;
import java.net.CookieHandler;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import netscape.javascript.JSException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * This class provides two-side interaction between native webkit core and
 * number of clients representing different subsystems of the WebPane component
 * such as
 * <ul>
 * <li>webpage rendering({@link WebPageClient})
 * <li>creating/disposing web frames ({@link WebFrameClient})
 * <li>creating new windows, alert dialogues ... ({@link UIClient})
 * <li>handling menus {@link MenuClient}
 * <li>supporting policy checking {@link PolicyClient}
 * </ul>
 */

public final class WebPage {
    private final static PlatformLogger log = PlatformLogger.getLogger(WebPage.class.getName());
    private final static PlatformLogger paintLog = PlatformLogger.getLogger(WebPage.class.getName() + ".paint");

    private static final int MAX_FRAME_QUEUE_SIZE = 10;
    private static final int DEFAULT_BACKGROUND_INT_RGBA = 0xFFFFFFFF; // Color.WHITE

    // Native WebPage* pointer
    private long pPage = 0;

    // skia-fx Blink: the out-of-process engine page backing this WebPage.
    // Owns the shared-memory channel, the engine process, and frame delivery.
    private BlinkPage blink;
    // Synthetic main-frame id (the Blink path is single-frame for the slice;
    // WebEngine threads a frame id through every call, so we hand back a
    // stable non-zero token and ignore it on the way to the engine).
    private static final long BLINK_MAIN_FRAME = 1L;
    // Latest title / location reported by the engine, surfaced through the
    // existing getTitle()/getURL() getters that WebEngine polls on load events.
    private volatile String blinkTitle;
    private volatile String blinkUrl;
    // Set (pump thread) when the engine delivers a new off-screen frame;
    // cleared (render thread) once the node paints it. Drives the JavaFX pulse
    // repaint path — see isDirty()/isRepaintPending()/drawBlinkFrame(). This
    // replaces the dead WebKit dirtyRects/frameQueue gating in the Blink path.
    private volatile boolean blinkFramePending;
    // Device-pixel scale of the scene surface (HiDPI). Read from the render
    // thread (Graphics.getPixelScaleFactorX in NGWebView), pushed to the engine
    // so it rasterizes the off-screen page at this DSF. 1.0 until the first paint.
    private volatile double blinkScale = 1.0;

    // Engine respawn (crash recovery, Mode 2). When the Blink engine process
    // dies (browser-process crash/hang, or repeated renderer crashes it couldn't
    // auto-recover), we transparently spawn a fresh engine and reload the page
    // the user was on. Bounded to MAX_ENGINE_RESPAWN within 30s so a page that
    // keeps crashing doesn't loop forever. FX-thread-confined.
    private static final int MAX_ENGINE_RESPAWN = 3;
    private int respawnCount;
    private long lastRespawnNanos;
    // The user's last-good frame, retained across an engine respawn so the
    // WebView keeps showing their page (not a blank) until the fresh engine
    // reloads and repaints. Set on the FX thread (respawn), read on the render
    // thread (drawBlinkFrame) via a single volatile ref. The backing buffer is a
    // GC-managed auto-arena, so dropping the reference is safe (no close/UAF).
    private volatile BlinkPage.FrameSnapshot retainedFrame;
    // Latest engine-serialized session snapshot (URL + scroll + forms + history).
    // Survives a BlinkPage respawn so the fresh engine restores the user's exact
    // last-good state. Opaque bytes; updated on the FX thread from onSessionState.
    private volatile byte[] lastSessionState;

    // A flag to distinguish whether the web page hasn't been created
    // yet or had been already disposed - in both cases pPage is 0
    private boolean isDisposed = false;

    private int width, height;

    private int fontSmoothingType;
    private int backgroundIntRgba = DEFAULT_BACKGROUND_INT_RGBA;

    private final WCFrameView hostWindow;

    // List of created frames
    private final Set<Long> frames = new HashSet<>();

    // Maps load request identifiers to URLs
    private final Map<Integer, String> requestURLs =
            new HashMap<>();

    // There may be several RESOURCE_STARTED events for a resource,
    // so this map is used to convert them to RESOURCE_REDIRECTED
    private final Set<Integer> requestStarted = new HashSet<>();

    // PAGE_LOCK is used to synchronize the following operations b/w Event & Main threads:
    // - rendering of the page (Main thread)
    // - native calls & other manipulations on the page (Event & Main threads)
    // - timer invocations (Event thread)
    private static final ReentrantLock PAGE_LOCK = new ReentrantLock();

    // The queue of render frames awaiting rendering.
    // Access to this object is synchronized on its monitor.
    // Accessed on: Event thread and Main thread.
    private final Queue<RenderFrame> frameQueue = new LinkedList<>();

    // The current frame being generated.
    // Accessed on: Event thread only.
    private RenderFrame currentFrame = new RenderFrame();

    // An ID of the current updateContent cycle associated with an updateContent call.
    private int updateContentCycleID;

    static {
        // skia-fx Blink: the renderer is the out-of-process skia-fx-webview
        // engine (see the blink bridge package), not the in-process jfxwebkit
        // library. The engine bundle is extracted lazily by BlinkPage.create();
        // we do NOT loadLibrary("jfxwebkit") / twkInitWebCore — those belong to
        // the retired WebKit port. The remaining twk* declarations stay only so
        // the class compiles; the slice never calls them (HotSpot links natives
        // lazily, so uncalled declarations never resolve).
        if (CookieHandler.getDefault() == null) {
            boolean setDefault = Boolean.valueOf(System.getProperty(
                    "com.sun.webkit.setDefaultCookieHandler",
                    "true"));
            if (setDefault) {
                CookieHandler.setDefault(new CookieManager());
            }
        }

        // skia-fx Blink: WebCore/JSC init and the native shutdown hook
        // belonged to the in-process WebKit port. The Blink engine manages
        // its own process lifecycle (per-page in BlinkPage), so there is
        // nothing to initialize or tear down here.
    }

    private static boolean firstWebPageCreated = false;

    private static void collectJSCGarbages() {
        Invoker.getInvoker().checkEventThread();
        // Add dummy object to get notification as soon as it is collected
        // by the JVM GC.
        Disposer.addRecord(new Object(), WebPage::collectJSCGarbages);
        // Invoke JavaScriptCore GC.
        twkDoJSCGarbageCollection();
    }

    public WebPage(WebPageClient pageClient,
                   UIClient uiClient,
                   PolicyClient policyClient,
                   InspectorClient inspectorClient,
                   ThemeClient themeClient,
                   boolean editable)
    {
        Invoker.getInvoker().checkEventThread();

        this.pageClient = pageClient;
        this.uiClient = uiClient;
        this.policyClient = policyClient;
        this.inspectorClient = inspectorClient;
        // skia-fx Blink: the WebKit RenderTheme / ScrollBarTheme classes load
        // jfxwebkit native IDs in their static initializers (and the engine
        // renders its own scrollbars + form controls anyway), so we never build
        // them. themeClient is intentionally ignored.
        this.renderTheme = null;
        this.scrollbarTheme = null;

        hostWindow = new WCFrameView(this);

        // skia-fx Blink: spawn the out-of-process engine page. Engine events
        // are marshalled onto the FX thread and fed into the existing
        // LoadListenerClient / WebPageClient callbacks, so WebEngine's title,
        // location, load worker and console wiring are unchanged. A fresh
        // frame arriving from the engine triggers a scene repaint.
        // pPage stays 0 — there is no native WebKit page.
        try {
            blink = BlinkPage.create(new BlinkClientImpl());
            blink.setRenderCallback(this::onBlinkFrame);
        } catch (Exception e) {
            log.warning("Failed to start skia-fx-webview engine page", e);
            blink = null;
        }
    }

    /** Bridges engine events to this page's existing client callbacks (FX thread). */
    private final class BlinkClientImpl implements BlinkPage.Client {
        @Override public void onPageStarted(String url) {
            fireLoadEvent(BLINK_MAIN_FRAME, LoadListenerClient.PAGE_STARTED,
                          url != null ? url : blinkUrl, null, 0.0, 0);
        }
        @Override public void onPageFinished(String url) {
            fireLoadEvent(BLINK_MAIN_FRAME, LoadListenerClient.PAGE_FINISHED,
                          url != null ? url : blinkUrl, null, 1.0, 0);
        }
        @Override public void onTitleChanged(String title) {
            blinkTitle = title;
            fireLoadEvent(BLINK_MAIN_FRAME, LoadListenerClient.TITLE_RECEIVED,
                          blinkUrl, null, 1.0, 0);
        }
        @Override public void onNavigation(String url) {
            blinkUrl = url;
        }
        @Override public void onHistoryChanged(int currentIndex, String[] urls, String[] titles) {
            // Push the engine's session-history snapshot into the BackForwardList
            // that WebHistory wraps, so getEntries()/getCurrentIndex() and the
            // back/forward buttons reflect the real Chromium history.
            if (backForwardList != null) {
                backForwardList.updateFromEngine(currentIndex, urls, titles);
            }
        }
        @Override public void onSessionState(byte[] blob) {
            // Keep the latest serialized session so a respawn can restore the
            // user's exact last-good state (URL + scroll + forms + history).
            lastSessionState = blob;
        }
        @Override public void onLoadError(int errorCode, String url, String description) {
            fireLoadEvent(BLINK_MAIN_FRAME, LoadListenerClient.LOAD_FAILED,
                          url != null ? url : blinkUrl, null, 0.0, errorCode);
        }
        @Override public void onConsoleMessage(int level, int lineNumber,
                                               String message, String sourceId) {
            if (pageClient != null) {
                pageClient.addMessageToConsole(message, lineNumber, sourceId);
            }
        }
        @Override public void onEngineGone(int status) {
            // The engine died (browser-process crash/hang, or repeated renderer
            // crashes it gave up auto-recovering). Transparently respawn it and
            // reload the page the user was on, instead of showing a failed
            // WebView. Only surfaces LOAD_FAILED if respawn keeps failing.
            respawnEngine();
        }
        @Override public void onCursorChanged(int cursorType) {
            // Resolve the engine's cursor type to a predefined cursor id via the
            // shared CursorManager and hand it to the page client, which sets it on
            // the WebView node — the exact path stock WebKit used for fwkSetCursor.
            CursorManager cm = CursorManager.getCursorManager();
            if (pageClient != null && cm != null) {
                pageClient.setCursor(cm.getPredefinedCursorID(cursorType));
            }
        }
        @Override public void onDomTreeReady() {
            // The Java-side DOM mirror is fully built. Fire DOCUMENT_AVAILABLE so
            // WebEngine.DocumentProperty invalidates and the next getDocument()
            // returns the freshly-walked document (it would otherwise stay null on
            // the Blink path).
            fireLoadEvent(BLINK_MAIN_FRAME, LoadListenerClient.DOCUMENT_AVAILABLE,
                          blinkUrl, null, 1.0, 0);
        }
        @Override public void onDialogRequested(int dialogId, int dialogType,
                                                String message, String defaultText) {
            // The page's JS is suspended by the engine until we answer. Route to
            // the existing UIClient handlers (WebEngine.onAlert/confirmHandler/
            // promptHandler) and send the response — public API is unchanged.
            if (blink == null) {
                return;
            }
            switch (dialogType) {
                case 0 -> { // alert
                    if (uiClient != null) {
                        uiClient.alert(message);
                    }
                    blink.respondDialog(dialogId, true, null);
                }
                case 1, 3 -> { // confirm / beforeunload
                    boolean ok = uiClient != null && uiClient.confirm(message);
                    blink.respondDialog(dialogId, ok, null);
                }
                case 2 -> { // prompt
                    String r = uiClient != null ? uiClient.prompt(message, defaultText) : null;
                    blink.respondDialog(dialogId, r != null, r);
                }
                default -> blink.respondDialog(dialogId, false, null);
            }
        }
        @Override public void onColorChooser(int chooserId, int initialRgba, int[] suggestionsRgba) {
            if (uiClient != null) {
                uiClient.openColorChooser(chooserId, initialRgba, suggestionsRgba);
            } else if (blink != null) {
                blink.respondColor(chooserId, false, 0); // no UI client: cancel
            }
        }
        @Override public void onFileChooserRequested(int chooserId, int mode,
                String title, String initialName, String acceptCsv) {
            if (uiClient != null) {
                uiClient.openFileChooser(chooserId, mode, title, initialName, acceptCsv);
            } else if (blink != null) {
                blink.respondFileChooser(chooserId, new String[0]); // no UI client: cancel
            }
        }
        @Override public void onSavePdfRequested(int requestId, String defaultName) {
            if (uiClient != null) {
                uiClient.savePdf(requestId, defaultName);
            } else if (blink != null) {
                blink.respondSavePdf(requestId, ""); // no UI client: cancel
            }
        }
        @Override public void onSelectPopup(int popupId, boolean multiple, int selectedIndex,
                double anchorX, double anchorY, double anchorW, double anchorH,
                List<BlinkPage.SelectItemData> items) {
            if (uiClient != null) {
                uiClient.openSelectPopup(popupId, multiple, selectedIndex,
                    anchorX, anchorY, anchorW, anchorH, items);
            } else if (blink != null) {
                blink.respondSelect(popupId, false, new int[0]); // no UI client: cancel
            }
        }
        @Override public void onPermissionRequested(int permId, int permType, String origin) {
            if (uiClient != null) {
                uiClient.requestPermission(permId, permType, origin);
            } else if (blink != null) {
                blink.respondPermission(permId, false); // no UI client: deny
            }
        }
        @Override public void onAuthRequested(int authId, int scheme, boolean proxy,
                String host, String realm) {
            if (uiClient != null) {
                uiClient.requestAuth(authId, scheme, proxy, host, realm);
            } else if (blink != null) {
                blink.respondAuth(authId, false, "", ""); // no UI client: cancel
            }
        }
        @Override public void onDownloadRequested(int downloadId, long totalBytes,
                String url, String name, String mime) {
            if (uiClient != null) {
                uiClient.requestDownload(downloadId, url, name, mime, totalBytes);
            } else if (blink != null) {
                blink.respondDownload(downloadId, false, ""); // no UI client: deny
            }
        }
        @Override public void onDownloadProgress(int downloadId, int state,
                long received, long total) {
            if (uiClient != null) {
                uiClient.downloadProgress(downloadId, state, received, total);
            }
        }
        @Override public void onDownloadFinished(int downloadId, int state, String path) {
            if (uiClient != null) {
                uiClient.downloadFinished(downloadId, state, path);
            }
        }
        @Override public void onContextMenu(int menuId, double x, double y, int flags,
                String linkUrl, String srcUrl, String selection) {
            // The menu is rendered (and its actions run) in this foreground process
            // by the WebView layer; with no UI client there is simply no menu.
            if (uiClient != null) {
                uiClient.contextMenu(menuId, x, y, flags, linkUrl, srcUrl, selection);
            }
        }
        @Override public void onFullscreenRequested(int fsId, boolean entering) {
            if (uiClient != null) {
                uiClient.fullscreenRequest(fsId, entering);
            } else if (blink != null) {
                blink.respondFullscreen(fsId, false); // no UI client: deny
            }
        }
        @Override public void onFaviconChanged(String iconUrl) {
            if (uiClient != null) {
                uiClient.faviconChanged(iconUrl);
            }
        }
        @Override public void onTooltipChanged(String text) {
            if (uiClient != null) {
                uiClient.tooltipChanged(text);
            }
        }
        @Override public void onNetworkRequest(int interceptId, int resourceType,
                String method, String url, String[] names, String[] values) {
            if (uiClient != null) {
                uiClient.networkRequest(interceptId, resourceType, method, url, names, values);
            } else if (blink != null) {
                // No UI client to decide → proceed so the load can't hang.
                blink.resolveNetwork(interceptId, 0 /*request*/, 0 /*PROCEED*/, EMPTY_BYTES);
            }
        }
        @Override public void onNetworkResponse(int interceptId, int status, String mime,
                long contentLength, String[] names, String[] values) {
            if (uiClient != null) {
                uiClient.networkResponse(interceptId, status, mime, contentLength, names, values);
            } else if (blink != null) {
                blink.resolveNetwork(interceptId, 1 /*response*/, 5 /*RESPONSE_PROCEED*/, EMPTY_BYTES);
            }
        }
        @Override public void onNetworkComplete(int interceptId, int netError) {
            if (uiClient != null) {
                uiClient.networkComplete(interceptId, netError);
            }
        }
        @Override public void onNetworkBodyChunk(int interceptId, int chunkSeq,
                long offset, boolean last, byte[] bytes) {
            if (uiClient != null) {
                uiClient.networkBodyChunk(interceptId, chunkSeq, offset, last, bytes);
            } else if (blink != null) {
                // No UI client → pass the body through unchanged.
                blink.resolveBodyEdit(interceptId, chunkSeq, 0 /*pass*/, null);
            }
        }
    }

    private static final byte[] EMPTY_BYTES = new byte[0];

    long getPage() {
        return pPage;
    }

    // Called from the native code
    private WCWidget getHostWindow() {
        return hostWindow;
    }

    static boolean lockPage() {
        return Invoker.getInvoker().lock(PAGE_LOCK);
    }

    static boolean unlockPage() {
        return Invoker.getInvoker().unlock(PAGE_LOCK);
    }

    // *************************************************************************
    // Backbuffer support
    // *************************************************************************

    private WCPageBackBuffer backbuffer;
    private List<WCRectangle> dirtyRects = new LinkedList<>();

    private void addDirtyRect(WCRectangle toPaint) {
        if (toPaint.getWidth() <= 0 || toPaint.getHeight() <= 0) {
            return;
        }
        for (Iterator<WCRectangle> it = dirtyRects.iterator(); it.hasNext();) {
            WCRectangle rect = it.next();
            // if already covered
            if (rect.contains(toPaint)) {
                return;
            }
            // if covers an existing one
            if (toPaint.contains(rect)) {
                it.remove();
                continue;
            }
            WCRectangle u = rect.createUnion(toPaint);
            // if squre of union is less than summary of squares
            if (u.getIntWidth() * u.getIntHeight() <
                rect.getIntWidth() * rect.getIntHeight() +
                toPaint.getIntWidth() * toPaint.getIntHeight())
            {
                it.remove();
                toPaint = u; // replace both the rects with their union
                continue;
            }
        }
        dirtyRects.add(toPaint);
    }

    public boolean isDirty() {
        // skia-fx Blink: "dirty" == a fresh engine frame is waiting to be
        // composited. (The legacy dirtyRects list is no longer populated.)
        return blinkFramePending;
    }

    /**
     * skia-fx Blink: marks that the engine delivered a new off-screen frame.
     * Called on the EventPump thread via the BlinkPage render callback; the
     * next JavaFX pulse ({@code WebView.handleStagePulse}) sees isDirty() /
     * isRepaintPending() and repaints the node. Cheap + idempotent.
     */
    private void onBlinkFrame() {
        blinkFramePending = true;
        // Request a JavaFX pulse so this fresh engine frame actually gets painted.
        // Without this, the WebView relies on the ambient pulse loop still
        // ticking — but this fork pauses the pulse timer when the scene goes idle
        // (adaptive cadence). After a monitor move / DPI change the loop can
        // settle to idle, and then engine frames pile up UNPAINTED: the video
        // freezes on the last frame and the node never re-syncs its size to the
        // new monitor until a manual resize injects a dirty event. A new
        // off-screen frame IS "something changed", so it must drive a pulse —
        // which wakes the (possibly paused) timer, repaints the node, and lets
        // renderContent re-push the size/scale (setRenderScale → setBounds) so
        // the engine recaptures at the new dimensions. Cheap: one flag set; no-op
        // when the timer is already running. Matches the video's frame cadence
        // and naturally goes idle when frames stop. (requestNextPulse is already
        // called cross-thread; PulseTask.set resumes a paused timer.)
        Toolkit tk = Toolkit.getToolkit();
        if (tk != null) {
            tk.requestNextPulse();
        }
    }

    /**
     * Transparently respawns the Blink engine after it died, then reloads the
     * page the user was on — crash recovery (Mode 2). Runs on the FX thread
     * ({@code onEngineGone} is marshalled here), which is also where engine
     * commands must originate. Bounded by {@link #MAX_ENGINE_RESPAWN} within a
     * 30s window so a page that keeps killing the engine can't loop; past that,
     * the failure surfaces as {@code LOAD_FAILED}.
     *
     * <p>Renderer-only crashes are handled inside the engine (it reloads and
     * restores scroll/form state via Chromium's PageState), so this only fires
     * for full engine death. Bounds + the current URL are restored; per-instance
     * settings re-applied on the next layout/handler change.
     */
    private void respawnEngine() {
        if (isDisposed) {
            return;
        }
        // A duplicate gone-notification after we already recovered — ignore.
        if (blink != null && blink.isAlive()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastRespawnNanos > 30_000_000_000L) {
            respawnCount = 0;
        }
        lastRespawnNanos = now;
        if (++respawnCount > MAX_ENGINE_RESPAWN) {
            log.warning("WebView engine respawn limit reached ("
                + MAX_ENGINE_RESPAWN + " in 30s); giving up");
            fireLoadEvent(BLINK_MAIN_FRAME, LoadListenerClient.LOAD_FAILED,
                          blinkUrl, null, 0.0, 0);
            return;
        }

        String url = blinkUrl;
        BlinkPage dead = blink;
        // Retain the user's last-good frame BEFORE tearing down the dead engine
        // (its channel — where the live pixels live — is unmapped on dispose), so
        // the WebView keeps showing the page during the respawn instead of a blank.
        if (dead != null) {
            BlinkPage.FrameSnapshot snap = dead.snapshotLatestFrame();
            if (snap != null) {
                retainedFrame = snap;
            }
        }
        blink = null;
        if (dead != null) {
            try {
                dead.dispose();
            } catch (Throwable ignore) {
                // best-effort teardown of the dead session
            }
        }
        try {
            BlinkPage fresh = BlinkPage.create(new BlinkClientImpl());
            fresh.setRenderCallback(this::onBlinkFrame);
            blink = fresh;
            if (width > 0 && height > 0) {
                fresh.setBounds(width, height, blinkScale);
            }
            byte[] session = lastSessionState;
            if (session != null && session.length > 0) {
                // Full restore: URL + scroll + form values + history.
                fresh.restoreSession(session);
                log.info("WebView engine respawned (attempt " + respawnCount
                    + "), restoring session for " + url);
            } else if (url != null && !url.isEmpty()) {
                fresh.open(url); // no session captured yet — at least reload the URL
                log.info("WebView engine respawned (attempt " + respawnCount
                    + "), reloading " + url);
            }
        } catch (Exception e) {
            log.warning("WebView engine respawn failed", e);
            fireLoadEvent(BLINK_MAIN_FRAME, LoadListenerClient.LOAD_FAILED,
                          blinkUrl, null, 0.0, 0);
        }
    }

    private void updateDirty(WCRectangle clip) {
        if (paintLog.isLoggable(Level.FINEST)) {
            paintLog.finest("Entering, dirtyRects: {0}, currentFrame: {1}",
                    new Object[] {dirtyRects, currentFrame});
        }

        if (isDisposed || width <= 0 || height <= 0) {
            // If there're any dirty rects left, they are invalid.
            // Clear the list so that the platform doesn't consider
            // the page dirty.
            dirtyRects.clear();
            return;
        }
        if (clip == null) {
            clip = new WCRectangle(0, 0, width, height);
        }
        List<WCRectangle> oldDirtyRects = dirtyRects;
        dirtyRects = new LinkedList<>();
        twkPrePaint(getPage());
        while (!oldDirtyRects.isEmpty()) {
            WCRectangle r = oldDirtyRects.remove(0).intersection(clip);
            if (r.getWidth() <= 0 || r.getHeight() <= 0) {
                continue;
            }
            paintLog.finest("Updating: {0}", r);
            WCRenderQueue rq = WCGraphicsManager.getGraphicsManager()
                    .createRenderQueue(r, true);
            twkUpdateContent(getPage(), rq, r.getIntX() - 1, r.getIntY() - 1,
                             r.getIntWidth() + 2, r.getIntHeight() + 2);
            currentFrame.addRenderQueue(rq);
        }
        {
            WCRenderQueue rq = WCGraphicsManager.getGraphicsManager()
                    .createRenderQueue(clip, false);
            twkPostPaint(getPage(), rq,
                         clip.getIntX(), clip.getIntY(),
                         clip.getIntWidth(), clip.getIntHeight());
            currentFrame.addRenderQueue(rq);
        }

        if (paintLog.isLoggable(Level.FINEST)) {
            paintLog.finest("Dirty rects processed, dirtyRects: {0}, currentFrame: {1}",
                    new Object[] {dirtyRects, currentFrame});
        }

        if (currentFrame.getRQList().size() > 0) {
            synchronized (frameQueue) {
                paintLog.finest("About to update frame queue, frameQueue: {0}", frameQueue);

                Iterator<RenderFrame> it = frameQueue.iterator();
                while (it.hasNext()) {
                    RenderFrame frame = it.next();
                    for (WCRenderQueue rq : currentFrame.getRQList()) {
                        WCRectangle rqRect = rq.getClip();
                        if (rq.isOpaque()
                                && rqRect.contains(frame.getEnclosingRect()))
                        {
                            paintLog.finest("Dropping: {0}", frame);
                            frame.drop();
                            it.remove();
                            break;
                        }
                    }
                }

                frameQueue.add(currentFrame);
                currentFrame = new RenderFrame();

                if (frameQueue.size() > MAX_FRAME_QUEUE_SIZE) {
                    paintLog.finest("Frame queue exceeded maximum "
                            + "size, clearing and requesting full repaint");
                    dropRenderFrames();
                    repaintAll();
                }

                paintLog.finest("Frame queue updated, frameQueue: {0}", frameQueue);
            }
        }

        if (paintLog.isLoggable(Level.FINEST)) {
            paintLog.finest("Exiting, dirtyRects: {0}, currentFrame: {1}",
                    new Object[] {dirtyRects, currentFrame});
        }
    }

    private void scroll(int x, int y, int w, int h, int dx, int dy) {
        if (!isBackgroundColorOpaque()) {
            if (paintLog.isLoggable(Level.FINEST)) {
                paintLog.finest("rect=[" + x + ", " + y + " " + w + "x" + h +"]");
            }
            addDirtyRect(new WCRectangle(x, y, w, h));
            return;
        }

        if (paintLog.isLoggable(Level.FINEST)) {
            paintLog.finest("rect=[" + x + ", " + y + " " + w + "x" + h +
                            "] delta=[" + dx + ", " + dy + "]");
        }
        dx += currentFrame.scrollDx;
        dy += currentFrame.scrollDy;

        if (Math.abs(dx) < w && Math.abs(dy) < h) {
            int cx = (dx >= 0) ? x : x - dx;
            int cy = (dy >= 0) ? y : y - dy;
            int cw = (dx == 0) ? w : w - Math.abs(dx);
            int ch = (dy == 0) ? h : h - Math.abs(dy);

            WCRenderQueue rq = WCGraphicsManager.getGraphicsManager()
                    .createRenderQueue(
                            new WCRectangle(0, 0, width, height), false);
            ByteBuffer buffer = ByteBuffer.allocate(32)
                    .order(ByteOrder.nativeOrder())
                    .putInt(GraphicsDecoder.COPYREGION)
                    .putInt(backbuffer.getID())
                    .putInt(cx).putInt(cy).putInt(cw).putInt(ch)
                    .putInt(dx).putInt(dy);
            buffer.flip();
            rq.addBuffer(buffer);
            // Ignore previous COPYREGION
            currentFrame.drop();
            currentFrame.addRenderQueue(rq);
            currentFrame.scrollDx = dx;
            currentFrame.scrollDy = dy;
            // Now we have to translate "old" dirty rects that fit to the frame's
            // content as the content is already scrolled at the moment by webkit.
            if (!dirtyRects.isEmpty()) {
                WCRectangle scrollRect = new WCRectangle(x, y, w, h);
                for (WCRectangle r: dirtyRects) {
                    if (scrollRect.contains(r)) {
                        if (paintLog.isLoggable(Level.FINEST)) {
                            paintLog.finest("translating old dirty rect by the delta: " + r);
                        }
                        r.translate(dx, dy);
                    }
                }
            }
        }

        // Add the dirty (not copied) rects
        addDirtyRect(new WCRectangle(x, dy >= 0 ? y : y + h + dy,
                                     w, Math.abs(dy)));
        addDirtyRect(new WCRectangle(dx >= 0 ? x : x + w + dx, y,
                                     Math.abs(dx), h - Math.abs(dy)));
    }

    // Instances of this class may not be accessed and modified concurrently
    // by multiple threads
    private static final class RenderFrame {
        private final List<WCRenderQueue> rqList =
                new LinkedList<>();
        private int scrollDx, scrollDy;
        private final WCRectangle enclosingRect = new WCRectangle();

        // Called on: Event thread only
        private void addRenderQueue(WCRenderQueue rq) {
            if (rq.isEmpty()) {
                return;
            }
            rqList.add(rq);
            WCRectangle rqRect = rq.getClip();
            if (enclosingRect.isEmpty()) {
                enclosingRect.setFrame(rqRect.getX(), rqRect.getY(),
                                       rqRect.getWidth(), rqRect.getHeight());
            } else if (rqRect.isEmpty()) {
                // do nothing
            } else {
                WCRectangle.union(enclosingRect, rqRect, enclosingRect);
            }
        }

        // Called on: Event thread and Main thread
        private List<WCRenderQueue> getRQList() {
            return rqList;
        }

        // Called on: Event thread only
        private WCRectangle getEnclosingRect() {
            return enclosingRect;
        }

        // Called on: Event thread only
        private void drop() {
            for (WCRenderQueue rq : rqList) {
                rq.dispose();
            }
            rqList.clear();
            enclosingRect.setFrame(0, 0, 0, 0);
            scrollDx = 0;
            scrollDy = 0;
        }

        @Override
        public String toString() {
            return "RenderFrame{"
                    + "rqList=" + rqList + ", "
                    + "enclosingRect=" + enclosingRect
                    + "}";
        }
    }

    // *************************************************************************
    // Callback API
    // *************************************************************************

    private final WebPageClient pageClient;
    private final UIClient uiClient;
    private final PolicyClient policyClient;
    private InputMethodClient imClient;
    private final List<LoadListenerClient> loadListenerClients =
        new LinkedList<>();
    private final InspectorClient inspectorClient;
    private final RenderTheme renderTheme;
    private final ScrollBarTheme scrollbarTheme;

    public WebPageClient getPageClient() {
        return pageClient;
    }

    public void setInputMethodClient(InputMethodClient imClient) {
        this.imClient = imClient;
    }

    public void setInputMethodState(boolean state) {
        if (imClient != null) {
            // A web page containing multiple clients is a single client from Java
            // Input Method Framework's viewpoint. We need to control activation and
            // deactivation for each text field/area here. Also, we need to control
            // enabling and disabling input methods here so that input method events
            // won't get delivered to wrong places (e.g., background).
            imClient.activateInputMethods(state);
        }
    }

    public void addLoadListenerClient(LoadListenerClient l) {
        if (!loadListenerClients.contains(l)) {
            loadListenerClients.add(l);
        }
    }

    private RenderTheme getRenderTheme() {
        return renderTheme;
    }

    private static RenderTheme fwkGetDefaultRenderTheme() {
        return ThemeClient.getDefaultRenderTheme();
    }

    private ScrollBarTheme getScrollBarTheme() {
        return scrollbarTheme;
    }

    // *************************************************************************
    // UI stuff API
    // *************************************************************************

    public void setBounds(int x, int y, int w, int h) {
        lockPage();
        try {
            log.fine("setBounds: " + x + " " + y + " " + w + " " + h);
            if (isDisposed) {
                log.fine("setBounds() request for a disposed web page.");
                return;
            }
            width = w;
            height = h;
            if (blink != null) {
                blink.setBounds(w, h, blinkScale);
            }
            // In response to the above call, WebKit will issue many
            // repaint requests, one of which will be meant to invalidate
            // the entire visible area. However, if the current scroll
            // offset is non-zero, that repaint request will contain
            // incorrect coordinates.
            // As of time of writing this, this problem exists in both
            // MiniBrowser and WinLauncher.
            // MiniBrowser is based on WebKit2, and WebKit2 workarounds
            // this problem by calling m_drawingArea->setNeedsDisplay()
            // for the entire visible area from within the WebKit2's
            // WebPage::setSize().
            // WinLauncher workarounds this problem by setting the main
            // window class style to CS_HREDRAW | CS_VREDRAW and calling
            // MoveWindow() with bRepaint = TRUE when resizing the web
            // view.
            // We workaround this problem by invalidating the entire
            // visible area here.
            repaintAll();

        } finally {
            unlockPage();
        }
    }

    /**
     * Pushes the WebView node's on-screen origin to the engine so Blink's native
     * popups (select/color/datalist) appear over the control. {@code screenX/Y}
     * are JavaFX screen coordinates; {@code scale} is the scene's output scale.
     */
    public void setScreenOrigin(double screenX, double screenY, double scale) {
        if (isDisposed || blink == null) {
            return;
        }
        blink.setScreenOrigin(screenX, screenY, scale);
    }

    /**
     * Tells the engine which popups the app overrides (so it suppresses the native
     * UI for those and routes the request to the app's handler).
     */
    public void setPopupOverrides(boolean select, boolean color, boolean contextMenu) {
        if (isDisposed || blink == null) {
            return;
        }
        blink.setPopupOverrides(select, color, contextMenu);
    }

    public void setOpaque(long frameID, boolean isOpaque) {
        lockPage();
        try {
            log.fine("setOpaque: " + isOpaque);
            if (isDisposed) {
                log.fine("setOpaque() request for a disposed web page.");
                return;
            }
            // skia-fx Blink: opacity is handled by the engine + scene node.
        } finally {
            unlockPage();
        }
    }

    public void setBackgroundColor(long frameID, int backgroundColor) {
        backgroundIntRgba = backgroundColor;
        lockPage();
        try {
            log.fine("setBackgroundColor intRgba: {0}", backgroundColor);
            if (isDisposed) {
                log.fine("setBackgroundColor() request for a disposed web page.");
                return;
            }
            if (!frames.contains(frameID)) {
                return;
            }
            // skia-fx Blink: see setBackgroundColor(int) — twk* path retired.
            repaintAll();
        } finally {
            unlockPage();
        }
    }

    public void setBackgroundColor(Color backgroundColor) {
        log.fine("setBackgroundColor color: " + backgroundColor);
        setBackgroundColor(getIntRgba(backgroundColor));
    }

    public void setBackgroundColor(int backgroundColor) {
        backgroundIntRgba = backgroundColor;
        lockPage();
        try {
            log.fine("setBackgroundColor intRgba: {0} for all frames", backgroundColor);
            if (isDisposed) {
                log.fine("setBackgroundColor() request for a disposed web page.");
                return;
            }

            // skia-fx Blink: background colour is the engine's concern; the
            // per-frame twk* calls belonged to the WebKit page. Keep the
            // cached backgroundIntRgba (used by isBackgroundColor*()).
            repaintAll();
        } finally {
            unlockPage();
        }
    }

    /*
     * Executed on the Event Thread.
     */
    public void updateContent(WCRectangle toPaint) {
        // skia-fx Blink: the engine renders off-screen and pushes frames via
        // FRAME_READY (BlinkPage render callback → repaintAll), so the legacy
        // dirty-rect → twkPrePaint/twkUpdateContent/twkPostPaint pulse path is
        // gone. Bump the cycle id so getUpdateContentCycleID() callers advance.
        ++updateContentCycleID;
    }

    public void updateRendering() {
        // skia-fx Blink: rendering is driven by the engine's frame cadence, not
        // a Java-side pulse into WebKit. No-op.
    }

    public int getUpdateContentCycleID() {
        return updateContentCycleID;
    }

    public boolean isRepaintPending() {
        // skia-fx Blink: a pending engine frame means the node must repaint, so
        // handleStagePulse marks WEBVIEW_VIEW dirty → NGWebView.requestRender().
        return blinkFramePending;
    }

    /*
     * Executed on printing thread.
     */
    public void print(WCGraphicsContext gc,
            final int x, final int y, final int w, final int h)
    {
        lockPage();
        try {
            final WCRenderQueue rq = WCGraphicsManager.getGraphicsManager().
                    createRenderQueue(new WCRectangle(x, y, w, h), true);
            FutureTask<Void> f = new FutureTask<>(() -> {
                twkUpdateContent(getPage(), rq, x, y, w, h);
            }, null);
            Invoker.getInvoker().invokeOnEventThread(f);

            try {
                // block until job is complete
                f.get();
            } catch (ExecutionException ex) {
                throw new AssertionError(ex);
            } catch (InterruptedException ex) {
                // ignore; recovery is impossible
            }

            rq.decode(gc);
        } finally {
            unlockPage();
        }
    }

    /**
     * skia-fx Phase 3: sets the native {@code SkSurface} handle the
     * WebKit C++ port should draw into for the next {@link #paint}
     * call. The value is a {@code uintptr_t} pointer (see
     * {@code com.sun.prism.skia.SkiaSurfaceAccess}); pass {@code 0L}
     * to clear it.
     *
     * <p>When set, {@code PlatformContextSkiaJava} in WebCore's Java
     * port resolves the handle via {@code skia_fx::resolve_canvas}
     * and paints C++-side directly into the scene's surface — no
     * intermediate texture. When unset (0), the legacy
     * {@code RenderingQueue}-on-Java-decoder path runs unchanged.</p>
     *
     * <p>Called by {@code NGWebView.renderContent} on the render
     * thread; cleared in its {@code finally} block.</p>
     */
    public void setRenderTargetSurface(long surfaceHandle) {
        // skia-fx Blink: retained for source compatibility. The Blink path
        // composites via drawBlinkFrame(); there is no native render-target
        // surface to hand off here.
    }

    /**
     * skia-fx Blink: composite the latest off-screen frame into the scene's
     * Skia surface. Called from {@code NGWebView.renderContent} on the render
     * thread; {@code surfaceHandle} is 0 for non-Skia pipelines (no-op then).
     */
    /**
     * skia-fx Blink: report the scene's device-pixel render scale (HiDPI). Called
     * on the render thread from {@code NGWebView.renderContent}. When it changes
     * (e.g. the window moves to a monitor with a different DPI), re-push the
     * viewport at the new scale so the engine rasterizes crisply — marshalled to
     * the FX thread because the command ring has a single (FX-thread) producer.
     */
    public void setRenderScale(double scale) {
        if (scale <= 0 || scale == blinkScale) {
            return;
        }
        blinkScale = scale;
        // HiDPI diagnostic (-Dskia.webdpi.diag): what render scale Java detected
        // for this monitor + the device capture size it implies. Runnable WITHOUT
        // an engine rebuild — confirms the JavaFX side sends the right per-monitor
        // scale before we look at the engine's [webdpi] capture line.
        if (Boolean.getBoolean("skia.webdpi.diag")) {
            System.err.println("[webdpi] Java setRenderScale " + scale
                + " node(logical)=" + width + "x" + height
                + " => engine should capture "
                + Math.round(width * scale) + "x" + Math.round(height * scale)
                + " device px");
        }
        Invoker.getInvoker().postOnEventThread(() -> {
            if (!isDisposed && blink != null) {
                blink.setBounds(width, height, blinkScale);
            }
        });
    }

    /**
     * skia-fx Blink: composite the latest off-screen frame into the scene via
     * the node's {@code Graphics} (carries transform + clip + pixel scale).
     * Called from {@code NGWebView.renderContent} on the render thread.
     */
    public void drawBlinkFrame(Graphics g) {
        // The node is painting now, so the pending frame is consumed. A frame
        // that lands after this (mid-pulse) re-sets the flag and repaints next
        // pulse. Clear before drawing so we never miss a frame that arrives
        // during the draw.
        blinkFramePending = false;
        BlinkPage b = blink;
        if (b != null && b.hasFrame()) {
            // Live engine has a frame — drop any retained recovery frame and draw
            // it. Pass the node's logical size so the frame fills the whole
            // WebView even when the engine downscaled the capture to fit a slot.
            retainedFrame = null;
            b.drawLatestFrame(g, width, height);
            return;
        }
        // No live frame yet (engine respawning after a crash): keep the user's
        // last-good frame on screen so they don't see a blank WebView.
        BlinkPage.FrameSnapshot rf = retainedFrame;
        if (rf != null) {
            int lw = rf.logicalW() > 0 ? (int) Math.round(rf.logicalW()) : width;
            int lh = rf.logicalH() > 0 ? (int) Math.round(rf.logicalH()) : height;
            SkiaSurfaceAccess.drawBgraFrame(g, rf.address(), rf.width(),
                rf.height(), rf.stride(), lw, lh);
        } else if (b != null) {
            b.drawLatestFrame(g, width, height);
        }
    }

    /*
     * Executed on the Render Thread.
     */
    public void paint(WCGraphicsContext gc, int x, int y, int w, int h) {
        lockPage();
        try {
            if (pageClient != null && pageClient.isBackBufferSupported()) {
                if (!backbuffer.validate(width, height)) {
                    // We need to repaint the whole page on the next turn
                    Invoker.getInvoker().invokeOnEventThread(() -> {
                        repaintAll();
                    });
                    return;
                }
                WCGraphicsContext bgc = backbuffer.createGraphics();
                try {
                    paint2GC(bgc);
                    bgc.flush();
                } finally {
                    backbuffer.disposeGraphics(bgc);
                }
                backbuffer.flush(gc, x, y, w, h);
            } else {
                paint2GC(gc);
            }
        } finally {
            unlockPage();
        }
    }

    private void paint2GC(WCGraphicsContext gc) {
        paintLog.finest("Entering");
        gc.setFontSmoothingType(this.fontSmoothingType);

        List<RenderFrame> framesToRender;
        synchronized (frameQueue) {
            framesToRender = new ArrayList(frameQueue);
            frameQueue.clear();
        }

        paintLog.finest("Frames to render: {0}", framesToRender);

        for (RenderFrame frame : framesToRender) {
            paintLog.finest("Rendering: {0}", frame);
            for (WCRenderQueue rq : frame.getRQList()) {
                gc.saveState();
                WCRectangle clip = rq.getClip();
                if (clip != null) {
                    if (isBackgroundColorTransparent()) {
                        // As backbuffer is enabled, new clips are drawn over the old rendered frames
                        // regardless the alpha channel. While that works fine for alpha > 0,
                        // for alpha == 0 we need to clear the old frame or it will still be visible.
                        gc.clearRect((int) clip.getX(), (int) clip.getY(), (int) clip.getWidth(), (int) clip.getHeight());
                    }
                    gc.setClip(clip);
                }
                rq.decode(gc);
                gc.restoreState();
            }
        }
        paintLog.finest("Exiting");
    }

    /*
     * Executed on the Event Thread.
     */
    public void dropRenderFrames() {
        lockPage();
        try {
            currentFrame.drop();
            synchronized (frameQueue) {
                for (RenderFrame frame = frameQueue.poll(); frame != null; frame = frameQueue.poll()) {
                    frame.drop();
                }
            }
        } finally {
            unlockPage();
        }
    }

    // skia-fx Blink: blink::WebInputEvent::Modifiers bit values, verified
    // against third_party/blink/public/common/input/web_input_event.h.
    private static final int BLINK_MOD_SHIFT        = 1 << 0;
    private static final int BLINK_MOD_CTRL         = 1 << 1;
    private static final int BLINK_MOD_ALT          = 1 << 2;
    private static final int BLINK_MOD_META         = 1 << 3;
    private static final int BLINK_MOD_LEFT_BUTTON  = 1 << 6;
    private static final int BLINK_MOD_MIDDLE_BUTTON = 1 << 7;
    private static final int BLINK_MOD_RIGHT_BUTTON = 1 << 8;

    private static int blinkModifiers(boolean shift, boolean ctrl,
                                      boolean alt, boolean meta) {
        int m = 0;
        if (shift) m |= BLINK_MOD_SHIFT;
        if (ctrl)  m |= BLINK_MOD_CTRL;
        if (alt)   m |= BLINK_MOD_ALT;
        if (meta)  m |= BLINK_MOD_META;
        return m;
    }

    /** Maps a WCMouseEvent buttonMask to Blink's *ButtonDown modifier bits. */
    private static int blinkButtonModifiers(int buttonMask) {
        int m = 0;
        if ((buttonMask & WCMouseEvent.BUTTON1) != 0) m |= BLINK_MOD_LEFT_BUTTON;
        if ((buttonMask & WCMouseEvent.BUTTON2) != 0) m |= BLINK_MOD_MIDDLE_BUTTON;
        if ((buttonMask & WCMouseEvent.BUTTON3) != 0) m |= BLINK_MOD_RIGHT_BUTTON;
        return m;
    }

    /**
     * Runs a Blink editor command on the focused frame
     * ({@code 0=Copy,1=Cut,2=Paste,3=SelectAll,4=Undo,5=Redo,6=Delete}). Drives the
     * context-menu editing items and the Ctrl+C/X/V/A/Z/Y shortcuts.
     */
    public void execEditingCommand(int cmd) {
        lockPage();
        try {
            if (!isDisposed && blink != null) {
                blink.execEditingCommand(cmd);
            }
        } finally {
            unlockPage();
        }
    }

    public void dispatchFocusEvent(WCFocusEvent fe) {
        lockPage();
        try {
            log.finest("dispatchFocusEvent: " + fe);
            if (isDisposed || blink == null) {
                return;
            }
            // WCFocusEvent.FOCUS_GAINED == 0 (others are lost/forward/backward).
            blink.sendFocus(fe.getID() == WCFocusEvent.FOCUS_GAINED);
        } finally {
            unlockPage();
        }
    }

    public boolean dispatchKeyEvent(WCKeyEvent ke) {
        lockPage();
        try {
            log.finest("dispatchKeyEvent: " + ke);
            if (isDisposed || blink == null) {
                return false;
            }
            if (WCKeyEvent.filterEvent(ke)) {
                log.finest("filtered");
                return false;
            }
            // WCKeyEvent: KEY_TYPED=0 (char), KEY_PRESSED=1 (down), KEY_RELEASED=2 (up).
            int type;
            switch (ke.getType()) {
                case WCKeyEvent.KEY_RELEASED -> type = 1; // keyup
                case WCKeyEvent.KEY_TYPED    -> type = 2; // char
                default                      -> type = 0; // keydown (raw)
            }
            int mods = blinkModifiers(ke.isShiftDown(), ke.isCtrlDown(),
                                      ke.isAltDown(), ke.isMetaDown());
            blink.sendKey(type, ke.getWindowsVirtualKeyCode(),
                          ke.getWindowsVirtualKeyCode(), mods, ke.getText());
            return true;
        } finally {
            unlockPage();
        }
    }

    public boolean dispatchMouseEvent(WCMouseEvent me) {
        lockPage();
        try {
            log.finest("dispatchMouseEvent: " + me.getX() + "," + me.getY());
            if (isDisposed || blink == null) {
                return false;
            }
            // WCMouseEvent id: PRESSED=0, RELEASED=1, MOVED=2, DRAGGED=3.
            // Blink mouse type: 0=move, 1=down, 2=up.
            int type;
            switch (me.getID()) {
                case WCMouseEvent.MOUSE_PRESSED  -> type = 1;
                case WCMouseEvent.MOUSE_RELEASED -> type = 2;
                default                          -> type = 0; // moved / dragged
            }
            // WCMouseEvent button: BUTTON1=1(left), BUTTON2=2(middle), BUTTON3=4(right).
            // Blink button: 0=left, 1=middle, 2=right.
            int button;
            switch (me.getButton()) {
                case WCMouseEvent.BUTTON2 -> button = 1;
                case WCMouseEvent.BUTTON3 -> button = 2;
                default                   -> button = 0;
            }
            int mods = blinkModifiers(me.isShiftDown(), me.isControlDown(),
                                      me.isAltDown(), me.isMetaDown());
            // Carry the held-button state in the modifier mask. Blink needs
            // the *ButtonDown bits on mouse-MOVE events to treat a move as a
            // drag — without them there is no text selection while dragging.
            mods |= blinkButtonModifiers(me.getButtonMask());
            if (!isDragConfirmed()) {
                blink.sendMouse(type, me.getX(), me.getY(), button,
                                me.getClickCount(), mods);
            }
            return true;
        } finally {
            unlockPage();
        }
    }

    /**
     * The open OSR popup's rect {@code [x,y,w,h]} in WebView-local logical coords,
     * or {@code null} if no popup is open. Used to route clicks to the popup.
     */
    public double[] getPopupRect() {
        return blink != null ? blink.getPopupRect() : null;
    }

    /**
     * Routes a mouse event to the open OSR popup instead of the page, translating
     * the coordinates to popup-local ({@code popupX/popupY} is the popup's origin
     * in WebView-local logical coords).
     */
    public boolean dispatchPopupMouseEvent(WCMouseEvent me, double popupX, double popupY) {
        lockPage();
        try {
            if (isDisposed || blink == null) {
                return false;
            }
            int type;
            switch (me.getID()) {
                case WCMouseEvent.MOUSE_PRESSED  -> type = 1;
                case WCMouseEvent.MOUSE_RELEASED -> type = 2;
                default                          -> type = 0;
            }
            int button;
            switch (me.getButton()) {
                case WCMouseEvent.BUTTON2 -> button = 1;
                case WCMouseEvent.BUTTON3 -> button = 2;
                default                   -> button = 0;
            }
            int mods = blinkModifiers(me.isShiftDown(), me.isControlDown(),
                                      me.isAltDown(), me.isMetaDown());
            mods |= blinkButtonModifiers(me.getButtonMask());
            blink.sendPopupMouse(type,
                (float) (me.getX() - popupX), (float) (me.getY() - popupY),
                button, me.getClickCount(), mods);
            return true;
        } finally {
            unlockPage();
        }
    }

    /**
     * Routes a wheel event to the open OSR popup instead of the page (scroll a
     * long {@code <select>}/datalist list), translating to popup-local coords
     * ({@code popupX/popupY} is the popup's origin in WebView-local logical
     * coords). Same delta sign convention as {@link #dispatchMouseWheelEvent}.
     */
    public boolean dispatchPopupWheelEvent(WCMouseWheelEvent me, double popupX, double popupY) {
        lockPage();
        try {
            if (isDisposed || blink == null) {
                return false;
            }
            int mods = blinkModifiers(me.isShiftDown(), me.isControlDown(),
                                      me.isAltDown(), me.isMetaDown());
            blink.sendPopupWheel((float) (me.getX() - popupX), (float) (me.getY() - popupY),
                                 -me.getDeltaX(), -me.getDeltaY(), mods);
            return true;
        } finally {
            unlockPage();
        }
    }

    /** Routes a key event to the open OSR popup (arrow/Enter/Esc/type-ahead). */
    public boolean dispatchPopupKeyEvent(WCKeyEvent ke) {
        lockPage();
        try {
            if (isDisposed || blink == null) {
                return false;
            }
            if (WCKeyEvent.filterEvent(ke)) {
                return false;
            }
            int type;
            switch (ke.getType()) {
                case WCKeyEvent.KEY_RELEASED -> type = 1; // keyup
                case WCKeyEvent.KEY_TYPED    -> type = 2; // char
                default                      -> type = 0; // keydown (raw)
            }
            int mods = blinkModifiers(ke.isShiftDown(), ke.isCtrlDown(),
                                      ke.isAltDown(), ke.isMetaDown());
            blink.sendPopupKey(type, ke.getWindowsVirtualKeyCode(),
                               ke.getWindowsVirtualKeyCode(), mods, ke.getText());
            return true;
        } finally {
            unlockPage();
        }
    }

    public boolean dispatchMouseWheelEvent(WCMouseWheelEvent me) {
        lockPage();
        try {
            log.finest("dispatchMouseWheelEvent: " + me);
            if (isDisposed || blink == null) {
                return false;
            }
            int mods = blinkModifiers(me.isShiftDown(), me.isControlDown(),
                                      me.isAltDown(), me.isMetaDown());
            // WCMouseWheelEvent deltas are already in PIXELS — WebView.process-
            // ScrollEvent applies the platform scroll speed (getDeltaX/Y, already
            // pixel-valued) times fontScale × scaleX/Y and negates for the WebKit
            // convention. Blink's wheel delta is also in pixels with the opposite
            // sign (positive delta_y scrolls content up), so negate once more to
            // restore the natural direction and pass the pixel delta straight
            // through — do NOT re-multiply by a per-line factor, that scrolled
            // ~40× too far ("too aggressive").
            blink.sendWheel(me.getX(), me.getY(),
                            -me.getDeltaX(), -me.getDeltaY(), mods);
            return true;
        } finally {
            unlockPage();
        }
    }

    public boolean dispatchInputMethodEvent(WCInputMethodEvent ie) {
        lockPage();
        try {
            log.finest("dispatchInputMethodEvent: " + ie);
            if (isDisposed) {
                log.fine("InputMethod event for a disposed web page.");
                return false;
            }
            // skia-fx Blink: IME composition routing to the engine is a later
            // refinement; committed text already flows via dispatchKeyEvent
            // char events. No-op here for now.
            return false;

        } finally {
            unlockPage();
        }
    }

    public final static int DND_DST_ENTER = 0;
    public final static int DND_DST_OVER = 1;
    public final static int DND_DST_CHANGE = 2;
    public final static int DND_DST_EXIT = 3;
    public final static int DND_DST_DROP = 4;

    public final static int DND_SRC_ENTER = 100;
    public final static int DND_SRC_OVER = 101;
    public final static int DND_SRC_CHANGE = 102;
    public final static int DND_SRC_EXIT = 103;
    public final static int DND_SRC_DROP = 104;

    public int dispatchDragOperation(
            int commandId,
            String[] mimeTypes, String[] values,
            int x, int y,
            int screenX, int screenY,
            int dndActionId)
    {
        lockPage();
        try {
            log.finest("dispatchDragOperation: " + x + "," + y
                    + " dndCommand:" + commandId
                    + " dndAction" + dndActionId);
            if (isDisposed) {
                log.fine("DnD event for a disposed web page.");
                return 0;
            }
            // Blink mode: the legacy jfxwebkit twk* natives are not linked, so
            // calling twkProcessDrag throws UnsatisfiedLinkError. Drag-and-drop
            // into the page is not wired for the Blink engine yet; treat as a
            // no-op (return "no action") rather than crashing.
            if (blink != null) {
                return 0;
            }
            return twkProcessDrag(getPage(),
                    commandId,
                    mimeTypes, values,
                    x, y,
                    screenX, screenY,
                    dndActionId);
        } finally {
            unlockPage();
        }
    }

    public void confirmStartDrag() {
        if (uiClient != null)
            uiClient.confirmStartDrag();
    }

    public boolean isDragConfirmed(){
        return (uiClient != null)
            ? uiClient.isDragConfirmed()
            : false;
    }

    // *************************************************************************
    // Input methods
    // *************************************************************************

    public int[] getClientTextLocation(int index) {
        lockPage();
        try {
            if (isDisposed) {
                log.fine("getClientTextLocation() request for a disposed web page.");
                return new int[] { 0, 0, 0, 0 };
            }
            if (blink != null) {
                // Blink mode: jfxwebkit IME natives are not loaded. No IME
                // query path yet — return a safe default rather than linking.
                return new int[] { 0, 0, 0, 0 };
            }
            Invoker.getInvoker().checkEventThread();
            return twkGetTextLocation(getPage(), index);

        } finally {
            unlockPage();
        }
    }

    public int getClientLocationOffset(int x, int y) {
        lockPage();
        try {
            if (isDisposed) {
                log.fine("getClientLocationOffset() request for a disposed web page.");
                return 0;
            }
            if (blink != null) {
                return 0; // Blink mode: no IME query native.
            }
            Invoker.getInvoker().checkEventThread();
            return twkGetInsertPositionOffset(getPage());

        } finally {
            unlockPage();
        }
    }

    public int getClientInsertPositionOffset() {
        lockPage();
        try {
            if (isDisposed) {
                log.fine("getClientInsertPositionOffset() request for a disposed web page.");
                return 0;
            }
            if (blink != null) {
                return 0; // Blink mode: no IME query native.
            }
            return twkGetInsertPositionOffset(getPage());

        } finally {
            unlockPage();
        }
    }

    public int getClientCommittedTextLength() {
        lockPage();
        try {
            if (isDisposed) {
                log.fine("getClientCommittedTextOffset() request for a disposed web page.");
                return 0;
            }
            if (blink != null) {
                return 0; // Blink mode: no IME query native.
            }
            return twkGetCommittedTextLength(getPage());

        } finally {
            unlockPage();
        }
    }

    public String getClientCommittedText() {
        lockPage();
        try {
            if (isDisposed) {
                log.fine("getClientCommittedText() request for a disposed web page.");
                return "";
            }
            if (blink != null) {
                return ""; // Blink mode: no IME query native.
            }
            return twkGetCommittedText(getPage());

        } finally {
            unlockPage();
        }
    }

    public String getClientSelectedText() {
        lockPage();
        try {
            if (isDisposed) {
                log.fine("getClientSelectedText() request for a disposed web page.");
                return "";
            }
            if (blink != null) {
                return ""; // Blink mode: no IME query native.
            }
            final String selectedText = twkGetSelectedText(getPage());
            return selectedText != null ? selectedText : "";

        } finally {
            unlockPage();
        }
    }

    // *************************************************************************
    // Browser API
    // *************************************************************************

    public void dispose() {
        lockPage();
        try {
            log.finer("dispose");

            dropRenderFrames();
            isDisposed = true;

            // skia-fx Blink: tear down the engine page (stops threads, kills
            // the process, unmaps the channel). Idempotent.
            if (blink != null) {
                blink.dispose();
                blink = null;
            }
            pPage = 0;
            frames.clear();

            if (backbuffer != null) {
                backbuffer.deref();
                backbuffer = null;
            }
        } finally {
            unlockPage();
        }
    }

    public String getName(long frameID) {
        lockPage();
        try {
            log.fine("Get Name: frame = " + frameID);
            if (isDisposed) {
                log.fine("getName() request for a disposed web page.");
                return null;
            }
            if (!frames.contains(frameID)) {
                return null;
            }
            return twkGetName(frameID);

        } finally {
            unlockPage();
        }
    }

    public String getURL(long frameID) {
        lockPage();
        try {
            log.fine("Get URL: frame = " + frameID);
            if (isDisposed) {
                log.fine("getURL() request for a disposed web page.");
                return null;
            }
            return blinkUrl;

        } finally {
            unlockPage();
        }
    }

    public String getEncoding() {
        lockPage();
        try {
            log.fine("Get encoding");
            if (isDisposed) {
                log.fine("getEncoding() request for a disposed web page.");
                return null;
            }
            if (blink != null) {
                // Blink mode: no encoding native. Modern pages are UTF-8.
                return "UTF-8";
            }
            return twkGetEncoding(getPage());

        } finally {
            unlockPage();
        }
    }

    public void setEncoding(String encoding) {
        lockPage();
        try {
            log.fine("Set encoding: encoding = " + encoding);
            if (isDisposed) {
                log.fine("setEncoding() request for a disposed web page.");
                return;
            }
            if (blink != null) {
                return; // Blink mode: no encoding native; no-op.
            }
            if (encoding != null && !encoding.isEmpty()) {
                twkSetEncoding(getPage(), encoding);
            }

        } finally {
            unlockPage();
        }
    }

    // DRT support
    public String getInnerText(long frameID) {
        lockPage();
        try {
            log.fine("Get inner text: frame = " + frameID);
            if (isDisposed) {
                log.fine("getInnerText() request for a disposed web page.");
                return null;
            }
            if (!frames.contains(frameID)) {
                return null;
            }
            return twkGetInnerText(frameID);

        } finally {
            unlockPage();
        }
    }

    // DRT support
    public String getRenderTree(long frameID) {
        lockPage();
        try {
            log.fine("Get render tree: frame = " + frameID);
            if (isDisposed) {
                log.fine("getRenderTree() request for a disposed web page.");
                return null;
            }
            if (!frames.contains(frameID)) {
                return null;
            }
            return twkGetRenderTree(frameID);

        } finally {
            unlockPage();
        }
    }

    // DRT support
    public int getUnloadEventListenersCount(long frameID) {
        lockPage();
        try {
            log.fine("frame: " + frameID);
            if (isDisposed) {
                log.fine("request for a disposed web page.");
                return 0;
            }
            if (!frames.contains(frameID)) {
                return 0;
            }
            return twkGetUnloadEventListenersCount(frameID);

        } finally {
            unlockPage();
        }
    }

    // DRT support
    public void forceRepaint() {
        repaintAll();
        updateContent(new WCRectangle(0, 0, width, height));
    }

    public String getContentType(long frameID) {
        lockPage();
        try {
            log.fine("Get content type: frame = " + frameID);
            if (isDisposed) {
                log.fine("getContentType() request for a disposed web page.");
                return null;
            }
            if (!frames.contains(frameID)) {
                return null;
            }
            return twkGetContentType(frameID);

        } finally {
            unlockPage();
        }
    }

    public String getTitle(long frameID) {
        lockPage();
        try {
            log.fine("Get title: frame = " + frameID);
            if (isDisposed) {
                log.fine("getTitle() request for a disposed web page.");
                return null;
            }
            return blinkTitle;

        } finally {
            unlockPage();
        }
    }

    public WCImage getIcon(long frameID) {
        lockPage();
        try {
            log.fine("Get icon: frame = " + frameID);
            if (isDisposed) {
                log.fine("getIcon() request for a disposed web page.");
                return null;
            }
            if (!frames.contains(frameID)) {
                return null;
            }
            String iconURL = twkGetIconURL(frameID);
            // do we need any cache for icons here?
            if (iconURL != null && !iconURL.isEmpty()) {
                return WCGraphicsManager.getGraphicsManager().getIconImage(iconURL);
            }
            return null;

        } finally {
            unlockPage();
        }
    }

    public void open(final long frameID, final String url) {
        lockPage();
        try {
            log.fine("Open URL: " + url);
            if (isDisposed) {
                log.fine("open() request for a disposed web page.");
                return;
            }
            blinkUrl = url;
            if (blink != null) {
                blink.open(url);
            }
        } finally {
            unlockPage();
        }
    }

    /** Opens the interactive chrome print preview for this page. */
    public void showPrintPreview() {
        lockPage();
        try {
            if (isDisposed) {
                return;
            }
            if (blink != null) {
                blink.showPrintPreview();
            }
        } finally {
            unlockPage();
        }
    }

    /**
     * Renders this page to a PDF. A non-empty {@code path} writes directly there;
     * an empty/null path pops a native "Save As" dialog first.
     */
    public void printToPdf(String path) {
        lockPage();
        try {
            if (isDisposed) {
                return;
            }
            if (blink != null) {
                blink.printToPdf(path);
            }
        } finally {
            unlockPage();
        }
    }

    public void load(final long frameID, final String text, final String contentType) {
        lockPage();
        try {
            log.fine("Load text: " + text);
            if (text == null) {
                return;
            }
            if (isDisposed) {
                log.fine("load() request for a disposed web page.");
                return;
            }
            if (!frames.contains(frameID)) {
                return;
            }
            if (blink != null) {
                blink.loadContent(text, contentType);
            }
        } finally {
            unlockPage();
        }
    }

    public void stop(final long frameID) {
        lockPage();
        try {
            log.fine("Stop loading: frame = " + frameID);

            if (isDisposed) {
                log.fine("cancel() request for a disposed web page.");
                return;
            }
            if (!frames.contains(frameID)) {
                return;
            }
            if (blink != null) {
                // Blink OSR: the legacy twk* natives aren't linked. A pending
                // navigation is superseded by the next open()/load(), so just
                // notify LOAD_STOPPED with the cached URL. (No STOP command yet.)
                fireLoadEvent(frameID, LoadListenerClient.LOAD_STOPPED, blinkUrl, null, 1.0, 0);
                return;
            }
            String url = twkGetURL(frameID);
            String contentType = twkGetContentType(frameID);
            twkStop(frameID);
            // WebKit doesn't send any notifications about loading stopped,
            // so sending it here
            fireLoadEvent(frameID, LoadListenerClient.LOAD_STOPPED, url, contentType, 1.0, 0);

        } finally {
            unlockPage();
        }
    }

    // stops all loading synchronously
    public void stop() {
        lockPage();
        try {
            log.fine("Stop loading sync");
            if (isDisposed) {
                log.fine("stopAll() request for a disposed web page.");
                return;
            }
            if (blink != null) {
                // Blink OSR: no legacy twk* natives; superseded by the next load.
                return;
            }
            twkStopAll(getPage());

        } finally {
            unlockPage();
        }
    }

    public void refresh(final long frameID) {
        lockPage();
        try {
            log.fine("Refresh: frame = " + frameID);
            if (isDisposed) {
                log.fine("refresh() request for a disposed web page.");
                return;
            }
            if (!frames.contains(frameID)) {
                return;
            }
            if (blink != null) {
                // Blink OSR: no twkRefresh native. Reload = re-navigate to the
                // current URL. (A dedicated RELOAD command is a future refinement.)
                if (blinkUrl != null && !blinkUrl.isBlank()) {
                    blink.open(blinkUrl);
                }
                return;
            }
            twkRefresh(frameID);

        } finally {
            unlockPage();
        }
    }

    public BackForwardList createBackForwardList() {
        // Keep a reference so engine HISTORY_STATE events (via BlinkClientImpl.
        // onHistoryChanged) can push session history into the list that WebHistory
        // wraps. On the Blink path the list is fed by the engine, not the dead
        // bfl* natives.
        backForwardList = new BackForwardList(this);
        return backForwardList;
    }

    /** The BackForwardList created for {@link WebHistory}, fed by engine events. */
    private BackForwardList backForwardList;

    /**
     * Navigates the engine's session history by a signed offset (-1 = back,
     * +1 = forward) relative to the current entry. Invoked by
     * {@link BackForwardList#setCurrentIndex(int)} on the Blink path.
     */
    public void navigateToHistoryOffset(int offset) {
        if (blink != null) {
            blink.goToHistoryOffset(offset);
        }
    }

    public boolean goBack() {
        lockPage();
        try {
            log.fine("Go back");
            if (isDisposed) {
                log.fine("goBack() request for a disposed web page.");
                return false;
            }
            return twkGoBackForward(getPage(), -1);

        } finally {
            unlockPage();
        }
    }

    public boolean goForward() {
        lockPage();
        try {
            log.fine("Go forward");
            if (isDisposed) {
                log.fine("goForward() request for a disposed web page.");
                return false;
            }
            return twkGoBackForward(getPage(), 1);

        } finally {
            unlockPage();
        }
    }

    public boolean copy() {
        lockPage();
        try {
            log.fine("Copy");
            if (isDisposed) {
                log.fine("copy() request for a disposed web page.");
                return false;
            }
            long frameID = getMainFrame();
            if (!frames.contains(frameID)) {
                return false;
            }
            return twkCopy(frameID);

        } finally {
            unlockPage();
        }
    }

    // Find in page
    public boolean find(String stringToFind, boolean forward, boolean wrap, boolean matchCase) {
        lockPage();
        try {
            log.fine("Find in page: stringToFind = " + stringToFind + ", " +
                    (forward ? "forward" : "backward") + (wrap ? ", wrap" : "") + (matchCase ? ", matchCase" : ""));
            if (isDisposed) {
                log.fine("find() request for a disposed web page.");
                return false;
            }
            return twkFindInPage(getPage(), stringToFind, forward, wrap, matchCase);

        } finally {
            unlockPage();
        }
    }

    // Find in frame
    public boolean find(long frameID,
        String stringToFind, boolean forward, boolean wrap, boolean matchCase)
    {
        lockPage();
        try {
            log.fine("Find in frame: stringToFind = " + stringToFind + ", " +
                    (forward ? "forward" : "backward") + (wrap ? ", wrap" : "") + (matchCase ? ", matchCase" : ""));
            if (isDisposed) {
                log.fine("find() request for a disposed web page.");
                return false;
            }
            if (!frames.contains(frameID)) {
                return false;
            }
            return twkFindInFrame(frameID, stringToFind, forward, wrap, matchCase);

        } finally {
            unlockPage();
        }
    }

    public void overridePreference(String key, String value) {
        lockPage();
        try {
            twkOverridePreference(getPage(), key, value);
        } finally {
            unlockPage();
        }
    }

    public void resetToConsistentStateBeforeTesting() {
        lockPage();
        try {
            twkResetToConsistentStateBeforeTesting(getPage());
        } finally {
            unlockPage();
        }
    }

    public float getZoomFactor(boolean textOnly) {
        lockPage();
        try {
            log.fine("Get zoom factor, textOnly=" + textOnly);
            if (isDisposed) {
                log.fine("getZoomFactor() request for a disposed web page.");
                return 1.0f;
            }
            // skia-fx Blink: zoom is not wired in the current slice.
            return 1.0f;
        } finally {
            unlockPage();
        }
    }

    public void setZoomFactor(float zoomFactor, boolean textOnly) {
        lockPage();
        try {
            log.fine(String.format("Set zoom factor %.2f, textOnly=%b", zoomFactor, textOnly));
            if (isDisposed) {
                log.fine("setZoomFactor() request for a disposed web page.");
                return;
            }
            long frameID = getMainFrame();
            if ((frameID == 0) || !frames.contains(frameID)) {
                return;
            }
            twkSetZoomFactor(frameID, zoomFactor, textOnly);
        } finally {
            unlockPage();
        }
    }

    public void setFontSmoothingType(int fontSmoothingType) {
        this.fontSmoothingType = fontSmoothingType;
        repaintAll();
    }

    // DRT support
    public void reset(long frameID) {
        lockPage();
        try {
            log.fine("Reset: frame = " + frameID);
            if (isDisposed) {
                log.fine("reset() request for a disposed web page.");
                return;
            }
            if ((frameID == 0) || !frames.contains(frameID)) {
                return;
            }
            twkReset(frameID);

        } finally {
            unlockPage();
        }
    }

    public Object executeScript(long frameID, String script) throws JSException {
        lockPage();
        try {
            log.fine("execute script: \"" + script + "\" in frame = " + frameID);
            if (isDisposed) {
                log.fine("executeScript() request for a disposed web page.");
                return null;
            }
            // skia-fx Blink: synchronous JS via the engine (bounded wait off
            // the FX thread inside BlinkPage). Returns the JSON-decoded
            // primitive/string; full JSObject marshalling is a later phase.
            return blink != null ? blink.executeScript(script) : null;

        } finally {
            unlockPage();
        }
    }

    public long getMainFrame() {
        lockPage();
        try {
            if (isDisposed) {
                log.fine("getMainFrame() request for a disposed web page.");
                return 0L;
            }
            // skia-fx Blink: single synthetic main frame (no native frame tree).
            frames.add(BLINK_MAIN_FRAME);
            return BLINK_MAIN_FRAME;
        } finally {
            unlockPage();
        }
    }

    public long getParentFrame(long childID) {
        lockPage();
        try {
            log.fine("getParentFrame: child = " + childID);
            if (isDisposed) {
                log.fine("getParentFrame() request for a disposed web page.");
                return 0L;
            }
            if (!frames.contains(childID)) {
                return 0L;
            }
            return twkGetParentFrame(childID);
        } finally {
            unlockPage();
        }
    }

    public List<Long> getChildFrames(long parentID) {
        lockPage();
        try {
            log.fine("getChildFrames: parent = " + parentID);
            if (isDisposed) {
                log.fine("getChildFrames() request for a disposed web page.");
                return null;
            }
            if (!frames.contains(parentID)) {
                return null;
            }
            long[] children = twkGetChildFrames(parentID);
            List<Long> childrenList = new LinkedList<>();
            for (long child : children) {
                childrenList.add(Long.valueOf(child));
            }
            return childrenList;
        } finally {
            unlockPage();
        }
    }

    public WCRectangle getVisibleRect(long frameID) {
        lockPage();
        try {
            if (!frames.contains(frameID)) {
                return null;
            }
            int[] arr = twkGetVisibleRect(frameID);
            if (arr != null) {
                return new WCRectangle(arr[0], arr[1], arr[2], arr[3]);
            }
            return null;
        } finally {
            unlockPage();
        }
    }

    public void scrollToPosition(long frameID, WCPoint p) {
        lockPage();
        try {
            if (!frames.contains(frameID)) {
                return;
            }
            twkScrollToPosition(frameID, p.getIntX(), p.getIntY());
        } finally {
            unlockPage();
        }
    }

    public WCSize getContentSize(long frameID) {
        lockPage();
        try {
            if (!frames.contains(frameID)) {
                return null;
            }
            int[] arr = twkGetContentSize(frameID);
            if (arr != null) {
                return new WCSize(arr[0], arr[1]);
            }
            return null;
        } finally {
            unlockPage();
        }
    }

    // ---- DOM ---- //

    public Document getDocument(long frameID) {
        lockPage();
        try {
            log.fine("getDocument");
            if (isDisposed) {
                log.fine("getDocument() request for a disposed web page.");
                return null;
            }

            if (!frames.contains(frameID)) {
                return null;
            }
            // skia-fx Blink: the W3C DOM is mirrored from the out-of-process
            // engine into a Java-side cache (see com.sun.webkit.blink.DomBridge),
            // not the dead in-process WebKit DOM. Returns null until the first
            // DOM_TREE_READY (which fires DOCUMENT_AVAILABLE) for this load.
            return blink != null ? blink.document() : null;
        } finally {
            unlockPage();
        }
    }

    // ---- Off-screen chooser/dialog responses ---- //
    // Called from WebEngine (FX thread) when the app answers a request the engine
    // surfaced. Each resumes the suspended page via the Blink command ring.

    public void respondColorChooser(int id, boolean chosen, int rgba) {
        if (blink != null) {
            blink.respondColor(id, chosen, rgba);
        }
    }

    public void respondFileChooser(int id, String[] paths) {
        if (blink != null) {
            blink.respondFileChooser(id, paths);
        }
    }

    /** Answers a print-to-PDF save request with the chosen path (empty = cancel). */
    public void respondSavePdf(int requestId, String path) {
        if (blink != null) {
            blink.respondSavePdf(requestId, path);
        }
    }

    public void respondSelectPopup(int id, boolean accepted, int[] indices) {
        if (blink != null) {
            blink.respondSelect(id, accepted, indices);
        }
    }

    public void respondPermission(int id, boolean granted) {
        if (blink != null) {
            blink.respondPermission(id, granted);
        }
    }

    public void respondAuth(int id, boolean supplied, String user, String pass) {
        if (blink != null) {
            blink.respondAuth(id, supplied, user, pass);
        }
    }

    public void respondDownload(int id, boolean accepted, String path) {
        if (blink != null) {
            blink.respondDownload(id, accepted, path);
        }
    }

    public void cancelDownloadById(int id) {
        if (blink != null) {
            blink.cancelDownload(id);
        }
    }

    public void respondFullscreen(int id, boolean allowed) {
        if (blink != null) {
            blink.respondFullscreen(id, allowed);
        }
    }

    // ---- Network interception ---- //

    public void armNetworkInterception(byte[] filterBlob) {
        if (blink != null) {
            blink.armNetworkInterception(filterBlob);
        }
    }

    public void disarmNetworkInterception() {
        if (blink != null) {
            blink.disarmNetworkInterception();
        }
    }

    public void resolveNetwork(int interceptId, int phase, int action, byte[] tail) {
        if (blink != null) {
            blink.resolveNetwork(interceptId, phase, action, tail);
        }
    }

    public void resolveBodyEdit(int interceptId, int chunkSeq, int kind, byte[] replacement) {
        if (blink != null) {
            blink.resolveBodyEdit(interceptId, chunkSeq, kind, replacement);
        }
    }

    public Element getOwnerElement(long frameID) {
        lockPage();
        try {
            log.fine("getOwnerElement");
            if (isDisposed) {
                log.fine("getOwnerElement() request for a disposed web page.");
                return null;
            }

            if (!frames.contains(frameID)) {
                return null;
            }
            return twkGetOwnerElement(frameID);
        } finally {
            unlockPage();
        }
    }

   // ---- EDITING SUPPORT ---- //

    public boolean executeCommand(String command, String value) {
        lockPage();
        try {
            if (log.isLoggable(Level.FINE)) {
                log.fine("command: [{0}], value: [{1}]",
                        new Object[] {command, value});
            }
            if (isDisposed) {
                log.fine("Web page is already disposed");
                return false;
            }
            if (blink != null) {
                // Blink mode: jfxwebkit editing natives are not loaded.
                // HTMLEditor-over-Blink routing is a separate feature; degrade
                // safely rather than throwing UnsatisfiedLinkError.
                return false;
            }

            boolean result = twkExecuteCommand(getPage(), command, value);

            log.fine("result: [{0}]", result);
            return result;
        } finally {
            unlockPage();
        }
    }

    public boolean queryCommandEnabled(String command) {
        lockPage();
        try {
            log.fine("command: [{0}]", command);
            if (isDisposed) {
                log.fine("Web page is already disposed");
                return false;
            }

            if (blink != null) {
                return false; // Blink mode: no editing-command native.
            }

            boolean result = twkQueryCommandEnabled(getPage(), command);

            log.fine("result: [{0}]", result);
            return result;
        } finally {
            unlockPage();
        }
    }

    public boolean queryCommandState(String command) {
        lockPage();
        try {
            log.fine("command: [{0}]", command);
            if (isDisposed) {
                log.fine("Web page is already disposed");
                return false;
            }

            if (blink != null) {
                return false; // Blink mode: no editing-command native.
            }

            boolean result = twkQueryCommandState(getPage(), command);

            log.fine("result: [{0}]", result);
            return result;
        } finally {
            unlockPage();
        }
    }

    public String queryCommandValue(String command) {
        lockPage();
        try {
            log.fine("command: [{0}]", command);
            if (isDisposed) {
                log.fine("Web page is already disposed");
                return null;
            }

            if (blink != null) {
                return null; // Blink mode: no editing-command native.
            }

            String result = twkQueryCommandValue(getPage(), command);

            log.fine("result: [{0}]", result);
            return result;
        } finally {
            unlockPage();
        }
    }

    public boolean isEditable() {
        lockPage();
        try {
            log.fine("isEditable");
            if (isDisposed) {
                log.fine("isEditable() request for a disposed web page.");
                return false;
            }

            return twkIsEditable(getPage());
        } finally {
            unlockPage();
        }
    }

    public void setEditable(boolean editable) {
        lockPage();
        try {
            log.fine("setEditable");
            if (isDisposed) {
                log.fine("setEditable() request for a disposed web page.");
                return;
            }

            twkSetEditable(getPage(), editable);
        } finally {
            unlockPage();
        }
    }

    /**
     * @return HTML content of the frame,
     *         or null if frame document is absent or non-HTML.
     */
    public String getHtml(long frameID) {
        lockPage();
        try {
            log.fine("getHtml");
            if (isDisposed) {
                log.fine("getHtml() request for a disposed web page.");
                return null;
            }
            if (!frames.contains(frameID)) {
                return null;
            }
            return twkGetHtml(frameID);
        } finally {
            unlockPage();
        }
    }

    // ---- PRINTING SUPPORT ---- //

    public int beginPrinting(float width, float height) {
        lockPage();
        try {
            if (isDisposed) {
                log.warning("beginPrinting() called for a disposed web page.");
                return 0;
            }
            AtomicReference<Integer> retVal = new AtomicReference<>(0);
            final CountDownLatch l = new CountDownLatch(1);
            Invoker.getInvoker().invokeOnEventThread(() -> {
                try {
                    int nPages = twkBeginPrinting(getPage(), width, height);
                    retVal.set(nPages);
                } finally {
                    l.countDown();
                }
            });

            try {
                l.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return retVal.get();
        } finally {
            unlockPage();
        }
    }

    public void endPrinting() {
        lockPage();
        try {
            if (isDisposed) {
                log.warning("endPrinting() called for a disposed web page.");
                return;
            }
            final CountDownLatch l = new CountDownLatch(1);
            Invoker.getInvoker().invokeOnEventThread(() -> {
                try {
                    twkEndPrinting(getPage());
                } finally {
                    l.countDown();
                }
            });

            try {
                l.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } finally {
            unlockPage();
        }
    }

    public void print(final WCGraphicsContext gc, final int pageNumber, final float width) {
        lockPage();
        try {
            if (isDisposed) {
                log.warning("print() called for a disposed web page.");
                return;
            }
            final WCRenderQueue rq = WCGraphicsManager.getGraphicsManager().
                    createRenderQueue(null, true);
            final CountDownLatch l = new CountDownLatch(1);
            Invoker.getInvoker().invokeOnEventThread(() -> {
                try {
                    twkPrint(getPage(), rq, pageNumber, width);
                } finally {
                    l.countDown();
                }
            });

            try {
                l.await();
            } catch (InterruptedException e) {
                rq.dispose();
                return;
            }
            rq.decode(gc);
        } finally {
            unlockPage();
        }
    }

    public int getPageHeight() {
        return getFrameHeight(getMainFrame());
    }

    public int getFrameHeight(long frameID) {
        lockPage();
        try {
            log.fine("Get page height");
            if (isDisposed) {
                log.fine("getFrameHeight() request for a disposed web page.");
                return 0;
            }
            if (!frames.contains(frameID)) {
                return 0;
            }
            int height = twkGetFrameHeight(frameID);
            log.fine("Height = " + height);
            return height;
        } finally {
            unlockPage();
        }
    }

    public float adjustFrameHeight(long frameID,
                                   float oldTop, float oldBottom, float bottomLimit)
    {
        lockPage();
        try {
            log.fine("Adjust page height");
            if (isDisposed) {
                log.fine("adjustFrameHeight() request for a disposed web page.");
                return 0;
            }
            if (!frames.contains(frameID)) {
                return 0;
            }
            return twkAdjustFrameHeight(frameID, oldTop, oldBottom, bottomLimit);
        } finally {
            unlockPage();
        }
    }

    // ---- SETTINGS ---- //

    /**
     * Returns the usePageCache settings field.
     * @return {@code true} if this object uses the page cache,
     *         {@code false} otherwise.
     */
    public boolean getUsePageCache() {
        lockPage();
        try {
            return twkGetUsePageCache(getPage());
        } finally {
            unlockPage();
        }
    }

    /**
     * Sets the usePageCache settings field.
     * @param usePageCache {@code true} to use the page cache,
     *        {@code false} to not use the page cache.
     */
    public void setUsePageCache(boolean usePageCache) {
        lockPage();
        try {
            twkSetUsePageCache(getPage(), usePageCache);
        } finally {
            unlockPage();
        }
    }

    public boolean getDeveloperExtrasEnabled() {
        lockPage();
        try {
            boolean result = twkGetDeveloperExtrasEnabled(getPage());
            log.fine("Getting developerExtrasEnabled, result: [{0}]", result);
            return result;
        } finally {
            unlockPage();
        }
    }

    public void setDeveloperExtrasEnabled(boolean enabled) {
        lockPage();
        try {
            log.fine("Setting developerExtrasEnabled, value: [{0}]", enabled);
            twkSetDeveloperExtrasEnabled(getPage(), enabled);
        } finally {
            unlockPage();
        }
    }

    public boolean isJavaScriptEnabled() {
        lockPage();
        try {
            return twkIsJavaScriptEnabled(getPage());
        } finally {
            unlockPage();
        }
    }

    public void setJavaScriptEnabled(boolean enable) {
        lockPage();
        try {
            twkSetJavaScriptEnabled(getPage(), enable);
        } finally {
            unlockPage();
        }
    }

    public boolean isContextMenuEnabled() {
        lockPage();
        try {
            return twkIsContextMenuEnabled(getPage());
        } finally {
            unlockPage();
        }
    }

    public void setContextMenuEnabled(boolean enable) {
        // skia-fx Blink: context-menu policy is engine-side; the twk* call
        // belonged to the WebKit page. No-op for the current slice (called by
        // WebView during skin/property setup).
    }

    public void setUserStyleSheetLocation(String url) {
        // skia-fx Blink: user stylesheet injection is engine-managed; the twk*
        // call belonged to the WebKit page. No-op for the current slice.
    }

    public String getUserAgent() {
        // skia-fx Blink: UA is engine-managed (twk* path is dead, pPage=0).
        if (blink != null) {
            return blink.getUserAgent();
        }
        return null;
    }

    public void setUserAgent(String userAgent) {
        // skia-fx Blink: per-WebView UA override routed to the engine, which
        // applies it via WebContents::SetUserAgentOverride.
        if (blink != null) {
            blink.setUserAgent(userAgent);
        }
    }

    public void setLocalStorageDatabasePath(String path) {
        // skia-fx Blink: storage is managed by the engine's browser context;
        // the twk* call belonged to the WebKit page. No-op (called on every
        // load via WebEngine.applyUserDataDirectory).
    }

    public void setLocalStorageEnabled(boolean enabled) {
        // skia-fx Blink: see setLocalStorageDatabasePath — engine-managed.
    }

    // ---- INSPECTOR SUPPORT ---- //

    public void connectInspectorFrontend() {
        lockPage();
        try {
            log.fine("Connecting inspector frontend");
            twkConnectInspectorFrontend(getPage());
        } finally {
            unlockPage();
        }
    }

    public void disconnectInspectorFrontend() {
        lockPage();
        try {
            log.fine("Disconnecting inspector frontend");
            twkDisconnectInspectorFrontend(getPage());
        } finally {
            unlockPage();
        }
    }

    public void dispatchInspectorMessageFromFrontend(String message) {
        lockPage();
        try {
            if (log.isLoggable(Level.FINE)) {
                log.fine("Dispatching inspector message from frontend, "
                        + "message: [{0}]",  message);
            }
            twkDispatchInspectorMessageFromFrontend(getPage(), message);
        } finally {
            unlockPage();
        }
    }

    // *************************************************************************
    // Native callbacks
    // *************************************************************************

    private void fwkFrameCreated(long frameID) {
        log.fine("Frame created: frame = " + frameID);
        if (frames.contains(frameID)) {
            log.fine("Error in fwkFrameCreated: frame is already in frames");
            return;
        }
        frames.add(frameID);
    }

    private void fwkFrameDestroyed(long frameID) {
        log.fine("Frame destroyed: frame = " + frameID);
        if (!frames.contains(frameID)) {
            log.fine("Error in fwkFrameDestroyed: frame is not found in frames");
            return;
        }
        frames.remove(frameID);
    }

    private void fwkRepaint(int x, int y, int w, int h) {
        lockPage();
        try {
            if (paintLog.isLoggable(Level.FINEST)) {
                paintLog.finest("x: {0}, y: {1}, w: {2}, h: {3}",
                        new Object[] {x, y, w, h});
            }
            addDirtyRect(new WCRectangle(x, y, w, h));
        } finally {
            unlockPage();
        }
    }

    private void fwkScroll(int x, int y, int w, int h, int deltaX, int deltaY) {
        if (paintLog.isLoggable(Level.FINEST)) {
            paintLog.finest("Scroll: " + x + " " + y + " " + w + " " + h + "  " + deltaX + " " + deltaY);
        }
        if (pageClient == null || !pageClient.isBackBufferSupported()) {
            paintLog.finest("blit scrolling is switched off");
            // TODO: check why we return void, not boolean (see ScrollView::m_canBlitOnScroll)
            return;
        }
        scroll(x, y, w, h, deltaX, deltaY);
    }

    private void fwkTransferFocus(boolean forward) {
        log.finer("Transfer focus " + (forward ? "forward" : "backward"));

        if (pageClient != null) {
            pageClient.transferFocus(forward);
        }
    }

    private void fwkSetCursor(long id) {
        log.finer("Set cursor: " + id);

        if (pageClient != null) {
            pageClient.setCursor(id);
        }
    }

    private void fwkSetFocus(boolean focus) {
        log.finer("Set focus: " + (focus ? "true" : "false"));

        if (pageClient != null) {
            pageClient.setFocus(focus);
        }
    }

    private void fwkSetTooltip(String tooltip) {
        log.finer("Set tooltip: " + tooltip);

        if (pageClient != null) {
            pageClient.setTooltip(tooltip);
        }
    }

    private void fwkPrint() {
        log.finer("Print");

        if (uiClient != null) {
            uiClient.print();
        }
    }

    private void fwkSetRequestURL(long pFrame, int id, String url) {
        log.finer("Set request URL: id = " + id + ", url = " + url);

        synchronized (requestURLs) {
            requestURLs.put(id, url);
        }
    }

    private void fwkRemoveRequestURL(long pFrame, int id) {
        log.finer("Set request URL: id = " + id);

        synchronized (requestURLs) {
            requestURLs.remove(id);
            requestStarted.remove(id);
        }
    }

    private WebPage fwkCreateWindow(
            boolean menu, boolean status, boolean toolbar, boolean resizable) {
        log.finer("Create window");

        if (uiClient != null) {
            return uiClient.createPage(menu, status, toolbar, resizable);
        }
        return null;
    }

    private void fwkShowWindow() {
        log.finer("Show window");

        if (uiClient != null) {
            uiClient.showView();
        }
    }

    private void fwkCloseWindow() {
        log.finer("Close window");

        if (permitCloseWindowAction()) {
            if (uiClient != null) {
                uiClient.closePage();
            }
        }
    }

    private WCRectangle fwkGetWindowBounds() {
        log.fine("Get window bounds");

        if (uiClient != null) {
            WCRectangle bounds = uiClient.getViewBounds();
            if (bounds != null) {
                return bounds;
            }
        }
        return fwkGetPageBounds();
    }

    private void fwkSetWindowBounds(int x, int y, int w, int h) {
        log.finer("Set window bounds: " + x + " " + y + " " + w + " " + h);

        if (uiClient != null) {
            uiClient.setViewBounds(new WCRectangle(x, y, w, h));
        }
    }

    private WCRectangle fwkGetPageBounds() {
        log.finer("Get page bounds");
        return new WCRectangle(0, 0, width, height);
    }

    private void fwkSetScrollbarsVisible(boolean visible) {
        // TODO: handle this request internally
    }

    private void fwkSetStatusbarText(String text) {
        log.finer("Set statusbar text: " + text);

        if (uiClient != null) {
            uiClient.setStatusbarText(text);
        }
    }

    private String[] fwkChooseFile(String initialFileName, boolean multiple, String mimeFilters) {
        log.finer("Choose file, initial=" + initialFileName);

        return uiClient != null
                ? uiClient.chooseFile(initialFileName, multiple, mimeFilters)
                : null;
    }

    private void fwkStartDrag(
          Object image,
          int imageOffsetX, int imageOffsetY,
          int eventPosX, int eventPosY,
          String[] mimeTypes, Object[] values,
          boolean isImageSource)
    {
        log.finer("Start drag: ");
        if (uiClient != null) {
            uiClient.startDrag(
                  WCImage.getImage(image),
                  imageOffsetX, imageOffsetY,
                  eventPosX, eventPosY,
                  mimeTypes, values,
                  isImageSource);
        }
    }

    private WCPoint fwkScreenToWindow(WCPoint ptScreen) {
        log.finer("fwkScreenToWindow");

        if (pageClient != null) {
            return pageClient.screenToWindow(ptScreen);
        }
        return ptScreen;
    }

    private WCPoint fwkWindowToScreen(WCPoint ptWindow) {
        log.finer("fwkWindowToScreen");

        if (pageClient != null) {
            return pageClient.windowToScreen(ptWindow);
        }
        return ptWindow;
    }


    private void fwkAlert(String text) {
        log.fine("JavaScript alert(): text = " + text);

        if (uiClient != null) {
            uiClient.alert(text);
        }
    }

    private boolean fwkConfirm(String text) {
        log.fine("JavaScript confirm(): text = " + text);

        if (uiClient != null) {
            return uiClient.confirm(text);
        }
        return false;
    }

    private String fwkPrompt(String text, String defaultValue) {
        log.fine("JavaScript prompt(): text = " + text + ", default = " + defaultValue);

        if (uiClient != null) {
            return uiClient.prompt(text, defaultValue);
        }
        return null;
    }

    private boolean fwkCanRunBeforeUnloadConfirmPanel() {
        log.fine("JavaScript canRunBeforeUnloadConfirmPanel()");

        if (uiClient != null) {
            return uiClient.canRunBeforeUnloadConfirmPanel();
        }
        return false;
    }

    private boolean fwkRunBeforeUnloadConfirmPanel(String message) {
        log.fine("JavaScript runBeforeUnloadConfirmPanel(): message = " + message);

        if (uiClient != null) {
            return uiClient.runBeforeUnloadConfirmPanel(message);
        }
        return false;
    }

    private void fwkAddMessageToConsole(String message, int lineNumber,
            String sourceId)
    {
        log.fine("fwkAddMessageToConsole(): message = " + message
                + ", lineNumber = " + lineNumber + ", sourceId = " + sourceId);
        if (pageClient != null) {
            pageClient.addMessageToConsole(message, lineNumber, sourceId);
        }
    }

    private void fwkFireLoadEvent(long frameID, int state,
                                  String url, String contentType,
                                  double progress, int errorCode)
    {
        log.finer("Load event: pFrame = " + frameID + ", state = " + state +
                ", url = " + url + ", contenttype=" + contentType +
                ", progress = " + progress + ", error = " + errorCode);

        fireLoadEvent(frameID, state, url, contentType, progress, errorCode);
    }

    private void fwkFireResourceLoadEvent(long frameID, int state,
                                          int id, String contentType,
                                          double progress, int errorCode)
    {
        log.finer("Resource load event: pFrame = " + frameID + ", state = " + state +
                ", id = " + id + ", contenttype=" + contentType +
                ", progress = " + progress + ", error = " + errorCode);

        String url = requestURLs.get(id);
        if (url == null) {
            log.fine("Error in fwkFireResourceLoadEvent: unknown request id " + id);
            return;
        }

        int eventState = state;
        // convert second and all subsequent STARTED into REDIRECTED
        if (state == LoadListenerClient.RESOURCE_STARTED) {
            if (requestStarted.contains(id)) {
                eventState = LoadListenerClient.RESOURCE_REDIRECTED;
            } else {
                requestStarted.add(id);
            }
        }

        fireResourceLoadEvent(frameID, eventState, url, contentType, progress, errorCode);
    }

    private boolean fwkPermitNavigateAction(long pFrame, String url) {
        log.fine("Policy: permit NAVIGATE: pFrame = " + pFrame + ", url = " + url);

        if (policyClient != null) {
            return policyClient.permitNavigateAction(pFrame, str2url(url));
        }
        return true;
    }

    private boolean fwkPermitRedirectAction(long pFrame, String url) {
        log.fine("Policy: permit REDIRECT: pFrame = " + pFrame + ", url = " + url);

        if (policyClient != null) {
            return policyClient.permitRedirectAction(pFrame, str2url(url));
        }
        return true;
    }

    private boolean fwkPermitAcceptResourceAction(long pFrame, String url) {
        log.fine("Policy: permit ACCEPT_RESOURCE: pFrame + " + pFrame + ", url = " + url);

        if (policyClient != null) {
            return policyClient.permitAcceptResourceAction(pFrame, str2url(url));
        }
        return true;
    }

    private boolean fwkPermitSubmitDataAction(long pFrame, String url,
                                              String httpMethod, boolean isSubmit)
    {
        log.fine("Policy: permit " + (isSubmit ? "" : "RE") + "SUBMIT_DATA: pFrame = " +
                pFrame + ", url = " + url + ", httpMethod = " + httpMethod);

        if (policyClient != null) {
            if (isSubmit) {
                return policyClient.permitSubmitDataAction(pFrame, str2url(url), httpMethod);
            } else {
                return policyClient.permitResubmitDataAction(pFrame, str2url(url), httpMethod);
            }
        }
        return true;
    }

    private boolean fwkPermitEnableScriptsAction(long pFrame, String url) {
        log.fine("Policy: permit ENABLE_SCRIPTS: pFrame + " + pFrame + ", url = " + url);

        if (policyClient != null) {
            return policyClient.permitEnableScriptsAction(pFrame, str2url(url));
        }
        return true;
    }

    private boolean fwkPermitNewWindowAction(long pFrame, String url) {
        log.fine("Policy: permit NEW_PAGE: pFrame = " + pFrame + ", url = " + url);

        if (policyClient != null) {
            return policyClient.permitNewPageAction(pFrame, str2url(url));
        }
        return true;
    }

    // Called from fwkCloseWindow, that's why no "fwk" prefix
    private boolean permitCloseWindowAction() {
        log.fine("Policy: permit CLOSE_PAGE");

        if (policyClient != null) {
            // Unfortunately, webkit doesn't provide an information about what
            // web frame initiated close window request, so using main frame here
            return policyClient.permitClosePageAction(getMainFrame());
        }
        return true;
    }

    private void fwkRepaintAll() {
        log.fine("Repainting the entire page");
        repaintAll();
    }

    private boolean fwkSendInspectorMessageToFrontend(String message) {
        if (log.isLoggable(Level.FINE)) {
            log.fine("Sending inspector message to frontend, message: [{0}]",
                    message);
        }
        boolean result = false;
        if (inspectorClient != null) {
            log.fine("Invoking inspector client");
            result = inspectorClient.sendMessageToFrontend(message);
        }
        if (log.isLoggable(Level.FINE)) {
            log.fine("Result: [{0}]", result);
        }
        return result;
    }

    // ---- DumpRenderTree support ---- //

    public static int getWorkerThreadCount() {
        return twkWorkerThreadCount();
    }

    private static native int twkWorkerThreadCount();

    private void fwkDidClearWindowObject(long pContext, long pWindowObject) {
        if (pageClient != null) {
            pageClient.didClearWindowObject(pContext, pWindowObject);
        }
    }

    // *************************************************************************
    // Private methods
    // *************************************************************************

    private URL str2url(String url) {
        try {
            return newURL(url);
        } catch (MalformedURLException ex) {
            log.fine("Exception while converting \"" + url + "\" to URL", ex);
        }
        return null;
    }

    private void fireLoadEvent(long frameID, int state, String url,
            String contentType, double progress, int errorCode)
    {
        setBackgroundColor(backgroundIntRgba);
        for (LoadListenerClient l : loadListenerClients) {
            l.dispatchLoadEvent(frameID, state, url, contentType, progress, errorCode);
        }
    }

    private void fireResourceLoadEvent(long frameID, int state, String url,
            String contentType, double progress, int errorCode)
    {
        for (LoadListenerClient l : loadListenerClients) {
            l.dispatchResourceLoadEvent(frameID, state, url, contentType, progress, errorCode);
        }
    }

    private void repaintAll() {
        dirtyRects.clear();
        addDirtyRect(new WCRectangle(0, 0, width, height));
    }

    private boolean isBackgroundColorTransparent() {
        return (backgroundIntRgba & 0x000000FF) == 0;
    }

    private boolean isBackgroundColorOpaque() {
        return (backgroundIntRgba & 0x000000FF) == 255;
    }

    private static int getIntRgba(Color color) {
        if (color == null) {
            return DEFAULT_BACKGROUND_INT_RGBA;
        }
        int red = (int) Math.round(color.getRed() * 255.0);
        int green = (int) Math.round(color.getGreen() * 255.0);
        int blue = (int) Math.round(color.getBlue() * 255.0);
        int alpha = (int) Math.round(color.getOpacity() * 255.0);

        // return 32 bit integer representation compatible with WebKit
        return (red << 24) | (green << 16) | (blue << 8) | alpha;
    }

    // Package scope method for testing
    int test_getFramesCount() {
        return frames.size();
    }

    // *************************************************************************
    // Native methods
    // *************************************************************************

    private static native void twkInitWebCore(boolean useJIT, boolean useDFGJIT, boolean useCSS3D);
    private native long twkCreatePage(boolean editable);
    private native void twkInit(long pPage, boolean usePlugins, float devicePixelScale);
    private native void twkDestroyPage(long pPage);
    // skia-fx Phase 3: hand the scene's SkSurface handle to native
    // WebKit for the next paint. See setRenderTargetSurface.
    private native void twkSetRenderTargetSurface(long pPage, long surfaceHandle);

    private native long twkGetMainFrame(long pPage);
    private native long twkGetParentFrame(long pFrame);
    private native long[] twkGetChildFrames(long pFrame);

    private native String twkGetName(long pFrame);
    private native String twkGetURL(long pFrame);
    private native String twkGetInnerText(long pFrame);
    private native String twkGetRenderTree(long pFrame);
    private native String twkGetContentType(long pFrame);
    private native String twkGetTitle(long pFrame);
    private native String twkGetIconURL(long pFrame);
    private native static Document twkGetDocument(long pFrame);
    private native static Element twkGetOwnerElement(long pFrame);

    private native void twkOpen(long pFrame, String url);
    private native void twkOverridePreference(long pPage, String key, String value);
    private native void twkResetToConsistentStateBeforeTesting(long pPage);
    private native void twkLoad(long pFrame, String text, String contentType);
    private native boolean twkIsLoading(long pFrame);
    private native void twkStop(long pFrame);
    private native void twkStopAll(long pPage); // sync
    private native void twkRefresh(long pFrame);

    private native boolean twkGoBackForward(long pPage, int distance);

    private native boolean twkCopy(long pFrame);
    private native boolean twkFindInPage(long pPage,
                                         String stringToFind, boolean forward,
                                         boolean wrap, boolean matchCase);
    private native boolean twkFindInFrame(long pFrame,
                                          String stringToFind, boolean forward,
                                          boolean wrap, boolean matchCase);

    private native float twkGetZoomFactor(long pFrame, boolean textOnly);
    private native void twkSetZoomFactor(long pFrame, float zoomFactor, boolean textOnly);

    private native Object twkExecuteScript(long pFrame, String script);

    private native void twkReset(long pFrame);

    private native int twkGetFrameHeight(long pFrame);
    private native int twkBeginPrinting(long pPage, float width, float height);
    private native void twkEndPrinting(long pPage);
    private native void twkPrint(long pPage, WCRenderQueue gc, int pageNumber, float width);
    private native float twkAdjustFrameHeight(long pFrame, float oldTop, float oldBottom, float bottomLimit);

    private native int[] twkGetVisibleRect(long pFrame);
    private native void twkScrollToPosition(long pFrame, int x, int y);
    private native int[] twkGetContentSize(long pFrame);
    private native void twkSetTransparent(long pFrame, boolean isTransparent);
    private native void twkSetBackgroundColor(long pFrame, int backgroundColor);

    private native void twkSetBounds(long pPage, int x, int y, int w, int h);
    private native void twkPrePaint(long pPage);
    private native void twkUpdateContent(long pPage, WCRenderQueue rq, int x, int y, int w, int h);
    private native void twkUpdateRendering(long pPage);
    private native void twkPostPaint(long pPage, WCRenderQueue rq,
                                     int x, int y, int w, int h);

    private native String twkGetEncoding(long pPage);
    private native void twkSetEncoding(long pPage, String encoding);

    private native void twkProcessFocusEvent(long pPage, int id, int direction);
    private native boolean twkProcessKeyEvent(long pPage, int type, String text,
                                              String keyIdentifier,
                                              int windowsVirtualKeyCode,
                                              boolean shift, boolean ctrl,
                                              boolean alt, boolean meta, double when);
    private native boolean twkProcessMouseEvent(long pPage, int id,
                                                int button, int buttonMask, int clickCount,
                                                int x, int y, int sx, int sy,
                                                boolean shift, boolean control, boolean alt, boolean meta,
                                                boolean popupTrigger, double when);
    private native boolean twkProcessMouseWheelEvent(long pPage,
                                                     int x, int y, int sx, int sy,
                                                     float dx, float dy,
                                                     boolean shift, boolean control, boolean alt, boolean meta,
                                                     double when);
    private native boolean twkProcessInputTextChange(long pPage, String committed, String composed,
                                                     int[] attributes, int caretPosition);
    private native boolean twkProcessCaretPositionChange(long pPage, int caretPosition);
    private native int[] twkGetTextLocation(long pPage, int charIndex);
    private native int twkGetInsertPositionOffset(long pPage);
    private native int twkGetCommittedTextLength(long pPage);
    private native String twkGetCommittedText(long pPage);
    private native String twkGetSelectedText(long pPage);

    private native int twkProcessDrag(long page,
            int commandId,
            String[] mimeTypes, String[] values,
            int x, int y,
            int screenX, int screenY,
            int dndActionId);

    private native boolean twkExecuteCommand(long page, String command,
                                             String value);
    private native boolean twkQueryCommandEnabled(long page, String command);
    private native boolean twkQueryCommandState(long page, String command);
    private native String twkQueryCommandValue(long page, String command);
    private native boolean twkIsEditable(long page);
    private native void twkSetEditable(long page, boolean editable);
    private native String twkGetHtml(long pFrame);

    private native boolean twkGetUsePageCache(long page);
    private native void twkSetUsePageCache(long page, boolean usePageCache);
    private native boolean twkGetDeveloperExtrasEnabled(long page);
    private native void twkSetDeveloperExtrasEnabled(long page,
                                                     boolean enabled);
    private native boolean twkIsJavaScriptEnabled(long page);
    private native void twkSetJavaScriptEnabled(long page, boolean enable);
    private native boolean twkIsContextMenuEnabled(long page);
    private native void twkSetContextMenuEnabled(long page, boolean enable);
    private native void twkSetUserStyleSheetLocation(long page, String url);
    private native String twkGetUserAgent(long page);
    private native void twkSetUserAgent(long page, String userAgent);
    private native void twkSetLocalStorageDatabasePath(long page, String path);
    private native void twkSetLocalStorageEnabled(long page, boolean enabled);

    private native int twkGetUnloadEventListenersCount(long pFrame);

    private native void twkConnectInspectorFrontend(long pPage);
    private native void twkDisconnectInspectorFrontend(long pPage);
    private native void twkDispatchInspectorMessageFromFrontend(long pPage,
                                                                String message);
    private static native void twkDoJSCGarbageCollection();
}
