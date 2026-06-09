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
package com.sun.webkit.blink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import com.sun.prism.Graphics;
import com.sun.prism.skia.SkiaSurfaceAccess;
import com.sun.webkit.Invoker;

import netscape.javascript.JSException;

/**
 * Owns one out-of-process {@code skia-fx-webview} engine page: the shared-memory
 * channel, the engine process, the liveness threads, and the event pump. Drives
 * the engine via the command ring and delivers engine events to a {@link Client}
 * on the FX thread.
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li>Control methods ({@link #open}, {@link #loadContent}, {@link #executeScript},
 *       {@link #dispose}) are called on the FX thread — the single command-ring
 *       producer.</li>
 *   <li>The {@link EventPump} thread is the single event-ring consumer; it
 *       decodes each event and marshals {@link Client} callbacks to the FX
 *       thread via {@link Invoker}.</li>
 *   <li>{@link #executeScript(String)} blocks the caller on a latch that the
 *       pump thread releases — it does <b>not</b> route the result through the
 *       FX dispatch queue, so a synchronous call on the FX thread cannot
 *       deadlock against the pump.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * {@link #dispose()} tears down in a fixed order and is idempotent; pending
 * synchronous {@code executeScript} callers are released with an error so they
 * never hang past disposal. Internal; never exposed to {@code javafx.scene.web}.
 */
public final class BlinkPage {

    /** Semantic engine→page callbacks, all delivered on the FX thread. */
    public interface Client {
        void onPageStarted(String url);
        void onPageFinished(String url);
        void onTitleChanged(String title);
        void onNavigation(String url);
        /**
         * Session history changed after a committed navigation. {@code urls} and
         * {@code titles} are the full entry list (equal length, oldest→newest);
         * {@code currentIndex} is the active entry. Drives the back/forward list.
         */
        void onHistoryChanged(int currentIndex, String[] urls, String[] titles);
        /**
         * The engine's serialized session snapshot (URL + scroll + forms +
         * history) for crash recovery. Opaque bytes — store the latest and replay
         * via {@link #restoreSession} after an engine respawn.
         */
        void onSessionState(byte[] blob);
        void onLoadError(int errorCode, String url, String description);
        void onConsoleMessage(int level, int lineNumber, String message, String sourceId);
        void onEngineGone(int status);
        /** Hovered-element cursor changed; {@code cursorType} is a CursorManager constant. */
        void onCursorChanged(int cursorType);
        /**
         * The engine finished walking the freshly-loaded document into the
         * Java-side DOM cache. Fired after every load so the page can arm
         * {@code getDocument()} (e.g. via {@code DOCUMENT_AVAILABLE}). The cache
         * is fully built by the time this runs on the FX thread.
         */
        void onDomTreeReady();

        /**
         * A page JS dialog ({@code alert}/{@code confirm}/{@code prompt}/
         * {@code beforeunload}) is open and the engine has suspended the page
         * until {@link #respondDialog} answers it. {@code dialogType}: 0=alert,
         * 1=confirm, 2=prompt, 3=beforeunload.
         */
        void onDialogRequested(int dialogId, int dialogType, String message, String defaultText);

        /** The engine needs a save location for a print-to-PDF (WebEngine.print()
         *  / the preview's Save button). Show a save dialog and answer via
         *  {@link #respondSavePdf}. */
        void onSavePdfRequested(int requestId, String defaultName);

        /** An {@code <input type=color>} requested a color. {@code initialRgba}/suggestions are {@code 0xRRGGBBAA}. */
        void onColorChooser(int chooserId, int initialRgba, int[] suggestionsRgba);

        /** An {@code <input type=file>} requested files. Resume via {@link #respondFileChooser}.
         *  mode: 0=open,1=multiple,2=uploadFolder,3=directory,4=save; acceptCsv is '\n'-joined. */
        void onFileChooserRequested(int chooserId, int mode, String title,
                                    String initialName, String acceptCsv);

        /** An HTML {@code <select>} opened its drop-down. Resume via {@link #respondSelect}. */
        void onSelectPopup(int popupId, boolean multiple, int selectedIndex,
                           double anchorX, double anchorY, double anchorW, double anchorH,
                           List<SelectItemData> items);

        /** A page requested a capability ({@code permType} is a PermissionType wire code). */
        void onPermissionRequested(int permId, int permType, String origin);

        /** An HTTP/proxy auth challenge. Resume via {@link #respondAuth}. */
        void onAuthRequested(int authId, int scheme, boolean proxy, String host, String realm);

        /** A download is starting. Resume via {@link #respondDownload}. */
        void onDownloadRequested(int downloadId, long totalBytes, String url, String name, String mime);

        /** Progress for an accepted download. */
        void onDownloadProgress(int downloadId, int state, long received, long total);

        /** An accepted download finished (state terminal). */
        void onDownloadFinished(int downloadId, int state, String path);

        /** The user right-clicked; the app builds and renders the menu itself (foreground). */
        void onContextMenu(int menuId, double x, double y, int flags, String linkUrl,
                           String srcUrl, String selection);

        /** A page requested a fullscreen transition. Resume via {@link #respondFullscreen}. */
        void onFullscreenRequested(int fsId, boolean entering);

        /** The page's favicon URL changed. */
        void onFaviconChanged(String iconUrl);

        /** The hovered element's tooltip text changed (empty when none). */
        void onTooltipChanged(String text);

        /** A matched request is about to be sent (interception request phase). */
        void onNetworkRequest(int interceptId, int resourceType, String method,
                              String url, String[] headerNames, String[] headerValues);

        /** Response headers arrived for a matched request (interception response phase). */
        void onNetworkResponse(int interceptId, int status, String mimeType,
                               long contentLength, String[] headerNames, String[] headerValues);

        /** An intercepted exchange finished (load resumed/failed); frees its state. */
        void onNetworkComplete(int interceptId, int netError);

        /** A captured response body chunk (whole body, {@code last=true}); the
         *  handler answers via {@link #resolveBodyEdit}. */
        void onNetworkBodyChunk(int interceptId, int chunkSeq, long offset,
                                boolean last, byte[] bytes);
    }

    /** Plain carrier for a {@code <select>} option crossing to the routing layer (no public-API dep here). */
    public record SelectItemData(String label, String value, boolean enabled, String group) { }

    /** Async JS result callback (FX thread). Custom interface — not Consumer/CompletableFuture. */
    public interface JsCallback {
        /** Typed result: {@code Integer}/{@code Double}/{@code Boolean}/{@code String}/
         *  {@code null}/{@code netscape.javascript.JSObject} (see JSValueCodec). */
        void onResult(Object result);
        void onError(String message);
    }

    private static final AtomicInteger WINDOW_IDS = new AtomicInteger(1);
    private static final long JS_TIMEOUT_MS = 15_000;
    private static final boolean DEBUG = Boolean.getBoolean("skia.webview.engineVerbose");

    // Bounds the async executeScript path: the sync path blocks on a latch with a
    // timeout, but the async path only registers a callback, so a lost result would
    // leak the pendingJs entry + callback forever. This watchdog fails it after
    // JS_TIMEOUT_MS. Shared, daemon-threaded so it never blocks JVM shutdown.
    private static final ScheduledExecutorService JS_TIMEOUT_SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BlinkPage-js-timeout");
            t.setDaemon(true);
            return t;
        });

    private static void dbg(String m) {
        if (DEBUG) {
            System.err.println("[blink] " + m);
        }
    }

    private final int windowId;
    private final SharedMemoryChannel channel;
    private final CommandRingBuffer commands;
    private final EngineProcessManager engine;
    private final HeartbeatWriter heartbeat;
    private final Watchdog watchdog;
    private final EventPump pump;
    private final Client client;

    /** Java-side mirror of the live Blink DOM; backs {@code WebEngine.getDocument()}. */
    private final DomBridge dom;

    /** Reader over the double-buffered captured frames (OSR). */
    private final FrameSurface frameSurface;

    // Print-preview modal overlay state. While active, the page keeps streaming
    // live on the main channel, the preview rides its own PREVIEW region (drawn
    // centered), and the preview's own dropdowns ride the popup region. previewOx/
    // Oy are the last-drawn modal centering, reused by getPopupRect so dropdown
    // input routes with the same offset the modal is drawn at.
    private volatile boolean previewActive = false;
    private volatile int previewOx = 0;
    private volatile int previewOy = 0;
    /** Invoked (on the pump thread) when a new frame lands — the node repaints. */
    private volatile Runnable renderCallback;

    /**
     * Outstanding executeScript calls keyed by request id. The engine echoes the
     * id in each JS_RESULT/JS_ERROR, so results correlate exactly even if one is
     * dropped, times out, or a stray result arrives (e.g. a {@code window.print}
     * echo) — FIFO matching desynced the queue in those cases.
     */
    private final ConcurrentHashMap<Integer, Pending> pendingJs = new ConcurrentHashMap<>();
    private final AtomicInteger nextJsId = new AtomicInteger(1);
    private volatile boolean disposed;

    // Serializes a render-thread frame draw (which reads the mapped shared-memory
    // segment) against the FX-thread channel unmap in dispose(). drawLatestFrame
    // takes the read lock; dispose() takes the write lock around channel.close()
    // so the unmap waits for any in-flight draw — otherwise the native draw could
    // read freed/unmapped memory and SIGSEGV.
    private final ReentrantReadWriteLock frameLock = new ReentrantReadWriteLock();

    // OSR popup overlay (Blink page-popup composited over the node): the engine
    // writes the popup's BGRA pixels into a double-buffered slot in the channel's
    // data region (after the main slots) — the same shared-memory mechanism as
    // the main frame, so {@link #frameSurface} owns it and the existing
    // {@link #frameLock} covers its lifecycle (no extra lock / off-heap scratch).

    /** Most recent {@link #loadContent} temp file, deleted on the next call / dispose. */
    private volatile Path lastContentFile;

    private static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        final JsCallback async; // null for synchronous calls
        volatile Object result;
        volatile String error;
        Pending(JsCallback async) { this.async = async; }
    }

    // Java objects exposed to JS via JSObject.setMember (slice B). The page owns
    // them (strong refs); the renderer holds only the int id, and a JS_CALLBACK
    // event carries that id back when the page invokes a method. Entries live
    // until page dispose (no JS→Java GC feedback in v1).
    private final ConcurrentHashMap<Integer, Object> javaObjects = new ConcurrentHashMap<>();
    private final AtomicInteger nextJavaObjId = new AtomicInteger(1);
    // Reverse identity map (obj -> id) so the SAME Java object exposed to JS more
    // than once reuses one id instead of minting a new entry each time. Without
    // this, an SPA that never navigates (so never hits the DOC_LOADING clear) and
    // re-passes the same callback/bean leaks one strong ref per pass. Guarded by
    // javaObjectsLock together with javaObjects so the two maps stay consistent.
    private final IdentityHashMap<Object, Integer> javaObjectIds = new IdentityHashMap<>();
    private final Object javaObjectsLock = new Object();

    // Identity map for engine JS-object wrappers (slice B). The engine reuses the
    // same objectId for the same underlying V8 object, so wrapJsObject MUST return
    // the same JSObjectImpl for a given id — otherwise N wrappers each register a
    // Cleaner and the FIRST one GC'd fires JS_RELEASE, dropping the shared
    // v8::Global out from under its live siblings (use-after-release), and
    // JSObject identity (==) breaks. WeakReference so a wrapper stays collectible;
    // the release is gated on the cache still owning that wrapper's token
    // (jsReleaseIfOwner) to avoid the GC-resurrection race. (bugs.md H6)
    private final ConcurrentHashMap<Integer, WeakReference<JSObjectImpl>> jsObjectCache =
        new ConcurrentHashMap<>();

    // --- Load-completion coordinator (touched only on the pump thread) -------
    // We must drive the LoadWorker to SUCCEEDED exactly once per navigation,
    // and only when BOTH the page has finished loading AND the DOM mirror is
    // populated (so WebEngine.getDocument() is usable the moment SUCCEEDED
    // fires). Two independent engine signals feed this:
    //   pageLoaded — DOC_READY (sync DidFinishLoad) OR, as a backstop,
    //                DOC_INTERACTIVE (DidStopLoading, the reliable network-idle
    //                terminal). DidFinishLoad does not fire for every load
    //                (e.g. a subresource that blocks onload), which is why a
    //                load could previously hang in RUNNING forever.
    //   domReady   — DOM_TREE_READY. This rides the RequestDomTree Mojo reply,
    //                which can stall when the engine is idle, so it must NEVER
    //                be the sole gate — DOC_INTERACTIVE force-completes past it.
    // loadTerminal latches the one-and-only terminal transition for this load
    // (SUCCEEDED here, or FAILED via LOAD_ERROR) so a failed load never flips
    // to SUCCEEDED and SUCCEEDED never fires twice.
    private boolean pageLoaded;
    private boolean domReady;
    private boolean loadTerminal;

    // Document generation. Bumped on every navigation (DOC_LOADING). Each
    // JSObjectImpl captures the generation it was minted under; an op on a
    // wrapper whose generation no longer matches is rejected (checkLive) — this
    // stops an app-held JSObject from a previous document from aliasing a reused
    // engine object id into the new document. volatile: bumped on the pump
    // thread, read by JSObject ops on the caller/FX thread.
    private volatile int jsGeneration;

    private BlinkPage(int windowId, SharedMemoryChannel channel, CommandRingBuffer commands,
                      EngineProcessManager engine, Client client) {
        this.windowId = windowId;
        this.channel = channel;
        this.commands = commands;
        this.engine = engine;
        this.client = client;
        this.heartbeat = new HeartbeatWriter(channel);
        this.watchdog = new Watchdog(channel, this::onEngineDead);
        this.pump = new EventPump(new EventRingBuffer(channel.eventBuffer()), channel, this::onEvent);
        this.frameSurface = new FrameSurface(channel.dataBuffer());
        this.dom = new DomBridge(commands, windowId);
    }

    /**
     * The live document mirror. Returns the cached {@code org.w3c.dom.Document}
     * (an {@code HTMLDocument}) or {@code null} if no page has been walked yet.
     * Exposes only {@code org.w3c.dom} types — the {@link DomBridge} never
     * escapes.
     */
    public Document document() {
        Node d = dom.document();
        return d instanceof Document doc ? doc : null;
    }

    /**
     * Extracts the engine bundle, spawns the engine, creates a (hidden) window,
     * and starts the liveness + event threads. On any failure everything started
     * so far is torn down before the exception propagates.
     */
    public static BlinkPage create(Client client) throws IOException {
        Path exe = BlinkBundle.ensureExtracted();
        int windowId = WINDOW_IDS.getAndIncrement();
        // Size the channel so its data region holds the double-buffered OSR
        // frame slots (the engine writes captured pixels there).
        SharedMemoryChannel channel =
            SharedMemoryChannel.create(windowId, MemoryLayout.channelSizeForFrames());
        EngineProcessManager engine = null;
        BlinkPage page = null;
        try {
            CommandRingBuffer commands = new CommandRingBuffer(channel.commandBuffer());
            engine = new EngineProcessManager(exe, channel, engineSwitches());
            try {
                engine.start();
            } catch (Exception e) {
                throw new IOException("failed to start skia-fx-webview engine", e);
            }
            page = new BlinkPage(windowId, channel, commands, engine, client);
            page.heartbeat.start();
            page.watchdog.start();
            page.pump.start();
            // Create the WebContents. Stays hidden (no SHOW) — the engine runs
            // in the background, never in the taskbar. Command draining works
            // regardless of window visibility thanks to the engine's dedicated
            // command-poll thread (jux_browser_main_parts.cc).
            page.commands.writeWindowOnly(CommandType.CREATE_WINDOW, windowId);
            return page;
        } catch (IOException | RuntimeException e) {
            // Once the page exists its liveness threads (heartbeat/watchdog/pump)
            // are running; a failure after that (e.g. writeWindowOnly throwing)
            // must go through dispose() to stop them — engine.stop()+channel.close()
            // alone would leak the threads. dispose() is idempotent and ordered.
            if (page != null) {
                page.dispose();
            } else {
                if (engine != null) {
                    engine.stop();
                }
                channel.close();
            }
            throw e;
        }
    }

    // --- Control (FX thread) --------------------------------------------

    /** Navigates to {@code url}. Long URLs (> slot size) are not yet supported. */
    public void open(String url) {
        if (disposed || url == null) {
            return;
        }
        commands.writeString(CommandType.LOAD_URL, windowId, url);
    }

    /** Opens the interactive chrome print preview for this page. */
    public void showPrintPreview() {
        if (disposed) {
            return;
        }
        commands.writeWindowOnly(CommandType.SHOW_PRINT_PREVIEW, windowId);
    }

    /**
     * Renders this page to a PDF. A non-empty {@code path} writes the PDF there
     * directly; an empty path pops a native "Save As" dialog first.
     */
    public void printToPdf(String path) {
        if (disposed) {
            return;
        }
        commands.writeString(CommandType.PRINT_TO_PDF, windowId,
                             path == null ? "" : path);
    }

    /**
     * Navigates the engine's session history by a signed offset relative to the
     * current entry (-1 = back, +1 = forward). The engine validates the offset
     * and, on commit, echoes a fresh HISTORY_STATE event. A zero offset is a
     * no-op handled by the caller.
     */
    public void goToHistoryOffset(int offset) {
        if (disposed || offset == 0) {
            return;
        }
        commands.writeNode(CommandType.GO_TO_OFFSET, windowId, offset);
    }

    /**
     * Restores a serialized session blob (from {@link Client#onSessionState})
     * into this (freshly-respawned) engine — bringing back the URL, scroll
     * position, form values and history. Staged to a temp file (the blob can be
     * large with form data) the engine reads then deletes.
     */
    public void restoreSession(byte[] blob) {
        if (disposed || blob == null || blob.length == 0) {
            return;
        }
        try {
            Path tmp = Files.createTempFile("skia-fx-webview-session-", ".bin");
            Files.write(tmp, blob);
            boolean ok = commands.writeString(
                CommandType.RESTORE_SESSION, windowId, tmp.toString());
            finishStaged(tmp.toString(), ok);
        } catch (IOException e) {
            dbg("restoreSession: staging failed: " + e);
        }
    }

    /**
     * The process-wide default User-Agent, mirroring the engine's
     * {@code JuxBrowserClient::GetUserAgent()} (the frozen "reduced" Chrome UA
     * built by {@code BuildUnifiedPlatformUserAgentFromProduct}). The platform
     * token is a fixed per-OS string in reduced-UA mode, so this mirror is
     * byte-identical to what the engine actually sends. Keep the Chrome major
     * version in sync with {@code kJuxUserAgentProduct} in jux_browser_client.cc.
     */
    static final String DEFAULT_USER_AGENT = buildDefaultUserAgent();

    private static String buildDefaultUserAgent() {
        String os = System.getProperty("os.name", "").toLowerCase();
        final String platform;
        if (os.contains("win")) {
            platform = "Windows NT 10.0; Win64; x64";
        } else if (os.contains("mac")) {
            platform = "Macintosh; Intel Mac OS X 10_15_7";
        } else {
            platform = "X11; Linux x86_64";
        }
        return "Mozilla/5.0 (" + platform + ") AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36";
    }

    /** Current per-WebView UA override, or {@code null} when using the default. */
    private String userAgentOverride;

    /**
     * Returns the User-Agent this WebView reports: the override set via
     * {@link #setUserAgent(String)} if any, otherwise {@link #DEFAULT_USER_AGENT}.
     */
    public String getUserAgent() {
        return userAgentOverride != null ? userAgentOverride : DEFAULT_USER_AGENT;
    }

    /**
     * Overrides the User-Agent for this WebView. A {@code null} or empty value
     * clears the override (reverting to {@link #DEFAULT_USER_AGENT}). The change
     * applies to subsequent navigations.
     */
    public void setUserAgent(String userAgent) {
        if (disposed) {
            return;
        }
        boolean clear = userAgent == null || userAgent.isEmpty();
        userAgentOverride = clear ? null : userAgent;
        // Empty payload tells the engine to clear its WebContents UA override.
        commands.writeString(CommandType.SET_USER_AGENT, windowId,
                             clear ? "" : userAgent);
    }

    /**
     * Loads inline content by writing it to a temp file and navigating to its
     * {@code file://} URL (the engine's LOAD_HTML accepts a path). This keeps the
     * whole payload out of the 256-byte ring slot — no overflow region needed.
     */
    public void loadContent(String content, String contentType) {
        if (disposed) {
            return;
        }
        try {
            String suffix = (contentType != null && contentType.contains("xml")) ? ".xhtml" : ".html";
            Path tmp = Files.createTempFile("skia-fx-webview-content-", suffix);
            // NB: deliberately NOT File.deleteOnExit() — that adds a permanent,
            // non-removable entry to the static DeleteOnExitHook set on EVERY
            // call, so a page that re-renders inline content repeatedly leaks one
            // hook entry + retained path String per call for the JVM's lifetime.
            // The previous-file delete below + the dispose() cleanup reclaim the
            // temp file deterministically instead.
            Files.write(tmp, content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
            // Delete the previous staged file — otherwise repeated loadContent
            // calls leak one temp file each.
            Path prev = lastContentFile;
            lastContentFile = tmp;
            if (prev != null) {
                try { Files.deleteIfExists(prev); } catch (IOException ignore) { }
            }
            commands.writeString(CommandType.LOAD_HTML, windowId, tmp.toString());
        } catch (IOException e) {
            Invoker.getInvoker().postOnEventThread(() ->
                client.onLoadError(-1, "", "failed to stage content: " + e.getMessage()));
        }
    }

    /**
     * Sets the off-screen viewport to {@code width × height} logical px rendered
     * at device-pixel {@code scale}. The engine captures at {@code width*scale ×
     * height*scale} device px (HiDPI-crisp). Driven by NGWebView whenever the
     * node size or the scene's render scale changes (e.g. moving the window to a
     * monitor with a different DPI).
     */
    public void setBounds(int width, int height, double scale) {
        if (disposed) {
            return;
        }
        commands.writeThreeDoubles(CommandType.SET_SIZE, windowId, width, height, scale);
    }

    /**
     * Moves the hidden engine window's origin to the WebView node's on-screen
     * position ({@code screenX/screenY} in JavaFX screen coords, {@code scale} the
     * scene's output scale) so Blink's native page-popups (select drop-down,
     * colour picker, datalist) appear over the control instead of at the screen
     * origin. The window stays hidden — only the popup it spawns is visible.
     */
    public void setScreenOrigin(double screenX, double screenY, double scale) {
        if (disposed) {
            return;
        }
        commands.writeThreeDoubles(CommandType.SET_SCREEN_ORIGIN, windowId, screenX, screenY, scale);
    }

    /**
     * Tells the engine which popups the app overrides (bit0=select, bit1=color,
     * bit2=contextMenu). For an overridden popup the engine suppresses its native
     * UI and surfaces the request to Java; otherwise it shows the native UI.
     */
    public void setPopupOverrides(boolean select, boolean color, boolean contextMenu) {
        if (disposed) {
            return;
        }
        int bits = (select ? 0x1 : 0) | (color ? 0x2 : 0) | (contextMenu ? 0x4 : 0);
        commands.writeNode(CommandType.SET_POPUP_OVERRIDES, windowId, bits);
    }

    // --- Off-screen rendering (render thread + pump thread) --------------

    /**
     * Sets the repaint trigger invoked when a fresh frame arrives. Called on
     * the pump thread, so the supplied runnable must be cheap and thread-safe
     * (it should merely request a scene repaint, not paint inline).
     */
    public void setRenderCallback(Runnable r) {
        this.renderCallback = r;
    }

    /**
     * Composites the most recently captured frame into the scene's Skia
     * surface at the given device-pixel rect. No-op if there is no frame yet
     * or the surface handle is 0 (non-Skia pipeline). Called on the render
     * thread from {@code NGWebView.renderContent}.
     */
    public void drawLatestFrame(Graphics g, int dstLogicalW, int dstLogicalH) {
        if (disposed || g == null) {
            return;
        }
        frameLock.readLock().lock();
        try {
            if (disposed) {   // re-check under the lock vs a concurrent dispose()
                return;
            }
            drawLatestFrameLocked(g, dstLogicalW, dstLogicalH);
        } finally {
            frameLock.readLock().unlock();
        }
    }

    /**
     * DEBUG ONLY: write the latest captured OSR frame to a 32-bit BMP at {@code path}.
     * Returns "WxH" on success or a reason string. Used by smoke diagnostics to
     * ground-truth what the page actually renders (no AWT/encoder needed).
     */
    public String dumpLatestFrame(String path) {
        frameLock.readLock().lock();
        try {
            if (disposed) {
                return "disposed";
            }
            FrameSurface.Frame f = frameSurface.latest();
            if (f == null) {
                return "no-frame";
            }
            int w = f.width(), h = f.height(), stride = f.stride();
            var seg = java.lang.foreign.MemorySegment.ofAddress(f.address())
                .reinterpret((long) stride * h);
            int rowBytes = w * 4;
            int fileSize = 54 + rowBytes * h;
            byte[] out = new byte[fileSize];
            // BITMAPFILEHEADER + BITMAPINFOHEADER (32bpp, BGRA, bottom-up).
            out[0] = 'B'; out[1] = 'M';
            putLE(out, 2, fileSize); putLE(out, 10, 54);
            putLE(out, 14, 40); putLE(out, 18, w); putLE(out, 22, h);
            out[26] = 1; out[28] = 32; putLE(out, 34, rowBytes * h);
            int o = 54;
            for (int y = h - 1; y >= 0; y--) {
                long base = (long) y * stride;
                for (int x = 0; x < w; x++) {
                    long p = base + (long) x * 4;
                    out[o++] = seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, p);     // B
                    out[o++] = seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, p + 1); // G
                    out[o++] = seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, p + 2); // R
                    out[o++] = (byte) 0xFF;                                              // A
                }
            }
            java.nio.file.Files.write(java.nio.file.Path.of(path), out);
            return w + "x" + h;
        } catch (Throwable t) {
            return "EXC:" + t;
        } finally {
            frameLock.readLock().unlock();
        }
    }

    private static void putLE(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16); b[off + 3] = (byte) (v >> 24);
    }

    private void drawLatestFrameLocked(Graphics g, int dstLogicalW, int dstLogicalH) {
        FrameSurface.Frame f = frameSurface.latest();
        // Draw the page full-area. The page stays LIVE on the main channel even
        // while the print preview is open (the engine no longer drops it), so it
        // keeps rendering and reflows on resize — never a stretched snapshot. We
        // stretch to the FRAME's own logical size, not the node's current size:
        // the engine may have downscaled the capture to fit a shared-memory slot
        // (large/HiDPI pages), and stretching to its captured DIP size undoes that
        // without distortion AND keeps the whole page visible.
        if (f != null) {
            int lw = f.logicalW() > 0 ? (int) Math.round(f.logicalW()) : dstLogicalW;
            int lh = f.logicalH() > 0 ? (int) Math.round(f.logicalH()) : dstLogicalH;
            // M13: tell the engine which slot we're about to read so it doesn't
            // overwrite it mid-copy (tearing). Publish the slot of THIS exact frame
            // f — NOT a fresh latest() read, which the pump thread could have
            // advanced to a different slot, telling the engine to protect the
            // wrong one. Published for the native read only; cleared to -1 in
            // finally so the engine is free to reuse it the rest of the time.
            channel.publishReadingSlot(frameSurface.slotIndexOf(f));
            try {
                SkiaSurfaceAccess.drawBgraFrame(g, f.address(),
                    f.width(), f.height(), f.stride(), lw, lh);
            } finally {
                channel.publishReadingSlot(-1);
            }
        }
        // Print-preview MODAL frame (its own region) composited centered over the
        // page. Record the centering (previewOx/Oy) so the dropdown overlay below
        // and getPopupRect (input routing) use the exact same offset.
        int ox = 0, oy = 0;
        FrameSurface.PreviewFrame pv = previewActive ? frameSurface.previewLatest() : null;
        if (pv != null) {
            int pw = pv.logicalW() > 0 ? (int) Math.round(pv.logicalW()) : dstLogicalW;
            int ph = pv.logicalH() > 0 ? (int) Math.round(pv.logicalH()) : dstLogicalH;
            ox = Math.max(0, (dstLogicalW - pw) / 2);
            oy = Math.max(0, (dstLogicalH - ph) / 2);
            previewOx = ox;
            previewOy = oy;
            SkiaSurfaceAccess.drawBgraOverlay(g, pv.address(),
                pv.width(), pv.height(), pv.stride(), ox, oy, pw, ph);
        }
        // OSR popup overlay (a native <select>/datalist drop-down) on top. When a
        // preview is open the popup is the preview's OWN dropdown, in the preview's
        // coord space, so offset it by the modal centering (ox,oy); otherwise it's
        // a page popup drawn at its node-local rect.
        FrameSurface.PopupFrame pop = frameSurface.popupLatest();
        if (pop != null) {
            int popW = Math.max(1, (int) Math.round(pop.logicalW()));
            int popH = Math.max(1, (int) Math.round(pop.logicalH()));
            int px = (int) Math.round(pop.x()) + (previewActive ? ox : 0);
            int py = (int) Math.round(pop.y()) + (previewActive ? oy : 0);
            SkiaSurfaceAccess.drawBgraOverlay(g, pop.address(),
                pop.width(), pop.height(), pop.stride(), px, py, popW, popH);
        }
    }

    /**
     * The open OSR popup's rect in node-local logical coords as
     * {@code [x, y, w, h]}, or {@code null} if no popup is open. Used by the
     * WebView to route mouse events to the popup. Lock-free (volatile snapshot).
     */
    public double[] getPopupRect() {
        FrameSurface.PopupFrame pop = frameSurface.popupLatest();
        if (previewActive) {
            // The preview's <select> dropdown is drawn by the engine (Skia) into the
            // popup region and composited here, but its pointer input is handled
            // ENGINE-side inside JuxSendMouseEvent/JuxSendWheelEvent (hover / select /
            // dismiss). So always return null while the preview is up: input must
            // flow through the normal mouse/wheel redirect (NOT the popup-widget
            // path), letting the engine route it to the dropdown or the preview body.
            return null;
        }
        if (pop == null) {
            return null;
        }
        return new double[] { pop.x(), pop.y(), pop.logicalW(), pop.logicalH() };
    }

    /** Forwards a mouse event to the open OSR popup. {@code x,y} are popup-local logical. */
    public void sendPopupMouse(int type, float x, float y, int button,
                               int clickCount, int modifiers) {
        if (!disposed) {
            commands.writePopupMouse(windowId, type, x, y, button, clickCount, modifiers);
        }
    }

    /** Forwards a wheel event to the open OSR popup. {@code x,y} are popup-local logical. */
    public void sendPopupWheel(float x, float y, float deltaX, float deltaY, int modifiers) {
        if (!disposed) {
            commands.writePopupWheel(windowId, x, y, deltaX, deltaY, modifiers);
        }
    }

    /** Forwards a key event to the open OSR popup. @param type 0=keydown,1=keyup,2=char. */
    public void sendPopupKey(int type, int windowsKeyCode, int nativeKeyCode,
                             int modifiers, String text) {
        if (!disposed) {
            commands.writePopupKey(windowId, type, windowsKeyCode, nativeKeyCode, modifiers, text);
        }
    }

    /** True once at least one frame has been captured. */
    public boolean hasFrame() {
        return !disposed && frameSurface.latest() != null;
    }

    /**
     * An off-heap copy of the last captured frame (BGRA), used to keep the
     * WebView showing its last-good content while the engine respawns (the dead
     * engine's shared-memory channel — where the live frame lived — is gone).
     * The backing segment is owned by a GC-managed auto-arena, so it stays valid
     * while referenced and is reclaimed automatically once dropped (no explicit
     * close → no use-after-free across the render/FX threads).
     */
    public record FrameSnapshot(MemorySegment seg, int width, int height,
                                int stride, double logicalW, double logicalH) {
        public long address() {
            return seg.address();
        }
    }

    /**
     * Copies the most recent frame into a fresh auto-arena buffer (or returns
     * {@code null} if none / disposed). Taken under the frame read-lock so it
     * can't race a concurrent channel unmap. Called by {@code WebPage} just
     * before disposing a dead engine, to retain the last-good frame.
     */
    public FrameSnapshot snapshotLatestFrame() {
        if (disposed) {
            return null;
        }
        frameLock.readLock().lock();
        try {
            return disposed ? null : frameSurface.snapshotLatest();
        } finally {
            frameLock.readLock().unlock();
        }
    }

    // --- Off-screen input injection (FX thread) -------------------------

    /** @param type 0=move, 1=down, 2=up. */
    public void sendMouse(int type, float x, float y, int button,
                          int clickCount, int modifiers) {
        if (!disposed) {
            commands.writeMouse(windowId, type, x, y, button, clickCount, modifiers);
        }
    }

    public void sendWheel(float x, float y, float deltaX, float deltaY, int modifiers) {
        if (!disposed) {
            commands.writeWheel(windowId, x, y, deltaX, deltaY, modifiers);
        }
    }

    /** @param type 0=keydown, 1=keyup, 2=char. */
    public void sendKey(int type, int windowsKeyCode, int nativeKeyCode,
                        int modifiers, String text) {
        if (!disposed) {
            commands.writeKey(windowId, type, windowsKeyCode, nativeKeyCode, modifiers, text);
        }
    }

    public void sendFocus(boolean focused) {
        if (!disposed) {
            commands.writeFocus(windowId, focused);
        }
    }

    /** Runs a Blink editor command (0=Copy,1=Cut,2=Paste,3=SelectAll,4=Undo,5=Redo,6=Delete). */
    public void execEditingCommand(int cmd) {
        if (!disposed) {
            commands.writeNode(CommandType.EXEC_EDITING_COMMAND, windowId, cmd);
        }
    }

    // --- Dialog / chooser / permission responses (FX thread) ------------
    // Each resumes the page the engine suspended when it surfaced the request.
    //
    // CRITICAL: these are MUST-DELIVER. The engine parks the renderer (a JS
    // alert()/confirm()/prompt(), a permission/auth/select/download/fullscreen
    // decision) until the matching response arrives. The command ring DROPS on
    // full (write() returns false, never blocks), so a fire-and-forget write can
    // silently strand the suspended renderer forever — "sometimes the dialog
    // never returns". They're rare and run on the FX thread, so we spin-retry
    // until the write lands (the engine drains the ring from another process),
    // bounded so a wedged engine can't hang the FX thread indefinitely.

    private static final long RESUME_DELIVER_TIMEOUT_NANOS = 2_000_000_000L; // 2s

    private void writeResumeReliably(String what, BooleanSupplier write) {
        if (write.getAsBoolean()) {
            return;
        }
        long deadline = System.nanoTime() + RESUME_DELIVER_TIMEOUT_NANOS;
        int spins = 0;
        while (System.nanoTime() < deadline) {
            if (++spins < 128) {
                Thread.onSpinWait();
            } else {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (write.getAsBoolean()) {
                return;
            }
        }
        dbg("resume response " + what + " could not be delivered "
            + "(command ring full / engine pump stalled) — renderer may stay suspended");
    }

    /** Answers a JS dialog: {@code accepted} (confirm/prompt OK), {@code text} (prompt value). */
    public void respondDialog(int dialogId, boolean accepted, String text) {
        if (!disposed) {
            writeResumeReliably("DIALOG_RESPONSE", () -> commands.writeIdFlagString(
                CommandType.DIALOG_RESPONSE, windowId, dialogId, accepted, text));
        }
    }

    /** Answers a {@link Client#onSavePdfRequested} with the chosen path (empty/null
     *  = cancelled); the engine then writes the PDF there. */
    public void respondSavePdf(int requestId, String path) {
        if (!disposed) {
            commands.writeStringWithId(CommandType.SAVE_PDF_RESPONSE, windowId,
                                       requestId, path == null ? "" : path);
        }
    }

    /** Answers a color chooser: {@code chosen}, {@code rgba} packed {@code 0xRRGGBBAA}. */
    public void respondColor(int chooserId, boolean chosen, int rgba) {
        if (!disposed) {
            writeResumeReliably("COLOR_CHOOSER_RESPONSE", () -> commands.writeIdFlagInt(
                CommandType.COLOR_CHOOSER_RESPONSE, windowId, chooserId, chosen, rgba));
        }
    }

    /**
     * Answers a file chooser with the chosen NATIVE paths ({@code paths} empty or
     * {@code null} ⇒ cancel). The paths are staged to a temp file — a ring slot
     * (248 B) can't hold many/long paths — which the engine reads, hands to Blink,
     * then deletes. Only paths cross IPC; Blink streams the file contents straight
     * from disk, so arbitrarily large uploads work.
     */
    public void respondFileChooser(int id, String[] paths) {
        if (disposed) {
            return;
        }
        int count = paths == null ? 0 : paths.length;
        if (count == 0) {
            writeResumeReliably("FILE_CHOOSER_RESPONSE",
                () -> commands.writeFileChooserResponse(windowId, id, 0, ""));
            return;
        }
        String tempPath;
        try {
            Path tmp = Files.createTempFile("jfxupload", ".lst");
            StringBuilder sb = new StringBuilder();
            for (String p : paths) {
                sb.append(p).append('\n');
            }
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            tmp.toFile().deleteOnExit(); // backstop if the engine never consumes it
            tempPath = tmp.toString();
        } catch (IOException e) {
            writeResumeReliably("FILE_CHOOSER_RESPONSE", // stage failed → cancel
                () -> commands.writeFileChooserResponse(windowId, id, 0, ""));
            return;
        }
        final String fp = tempPath;
        final int n = count;
        writeResumeReliably("FILE_CHOOSER_RESPONSE",
            () -> commands.writeFileChooserResponse(windowId, id, n, fp));
    }

    /** Answers a &lt;select&gt; popup with the chosen indices ({@code accepted=false} ⇒ cancel). */
    public void respondSelect(int popupId, boolean accepted, int[] indices) {
        if (!disposed) {
            writeResumeReliably("SELECT_POPUP_RESPONSE", () -> commands.writeIdFlagIntArray(
                CommandType.SELECT_POPUP_RESPONSE, windowId, popupId, accepted, indices));
        }
    }

    /** Grants or denies a permission request. */
    public void respondPermission(int permId, boolean granted) {
        if (!disposed) {
            writeResumeReliably("PERMISSION_RESPONSE", () -> commands.writeIdFlag(
                CommandType.PERMISSION_RESPONSE, windowId, permId, granted));
        }
    }

    /** Supplies (or cancels) HTTP/proxy auth credentials. */
    public void respondAuth(int authId, boolean supplied, String user, String pass) {
        if (!disposed) {
            writeResumeReliably("AUTH_RESPONSE", () -> commands.writeAuthResponse(
                CommandType.AUTH_RESPONSE, windowId, authId, supplied, user, pass));
        }
    }

    /** Accepts (with a target path) or denies a download. */
    public void respondDownload(int downloadId, boolean accepted, String path) {
        if (!disposed) {
            writeResumeReliably("DOWNLOAD_RESPONSE", () -> commands.writeIdFlagString(
                CommandType.DOWNLOAD_RESPONSE, windowId, downloadId, accepted, path));
        }
    }

    /** Cancels an in-flight download. */
    public void cancelDownload(int downloadId) {
        if (!disposed) {
            commands.writeNode(CommandType.DOWNLOAD_CANCEL, windowId, downloadId);
        }
    }

    /** Allows or denies an element's fullscreen request. */
    public void respondFullscreen(int fsId, boolean allowed) {
        if (!disposed) {
            writeResumeReliably("FULLSCREEN_RESPONSE", () -> commands.writeIdFlag(
                CommandType.FULLSCREEN_RESPONSE, windowId, fsId, allowed));
        }
    }

    // --- Network interception (FX thread) -------------------------------

    /** Arms request/response interception with a serialized {@link javafx.scene.web.NetworkFilter} blob. */
    public void armNetworkInterception(byte[] filterBlob) {
        if (disposed) {
            return;
        }
        byte[] blob = filterBlob == null ? new byte[0] : filterBlob;
        ByteBuffer bb = ByteBuffer.allocate(8 + blob.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(windowId);
        bb.putInt(blob.length);
        bb.put(blob);
        byte[] p = bb.array();
        if (p.length <= MemoryLayout.MAX_PAYLOAD) {
            commands.write(CommandType.ARM_INTERCEPTION, p, p.length);
        } else {
            String path = stageBytes(blob, ".flt");
            if (path != null) {
                finishStaged(path, commands.writeString(
                    CommandType.ARM_INTERCEPTION_FILE, windowId, path));
            }
        }
    }

    /** Disarms interception (un-armed requests pass through with no Java round-trip). */
    public void disarmNetworkInterception() {
        if (!disposed) {
            commands.writeWindowOnly(CommandType.DISARM_INTERCEPTION, windowId);
        }
    }

    /**
     * Delivers a per-exchange decision: {@code [windowId][interceptId][phase:1]
     * [action:1][tailLen:4][tail]} inline, or the temp-file variant when the tail
     * (e.g. a synthetic/replacement body) exceeds one slot.
     */
    public void resolveNetwork(int interceptId, int phase, int action, byte[] tail) {
        if (disposed) {
            return;
        }
        byte[] t = tail == null ? new byte[0] : tail;
        int total = 4 + 4 + 1 + 1 + 4 + t.length;
        if (total <= MemoryLayout.MAX_PAYLOAD) {
            ByteBuffer bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(windowId);
            bb.putInt(interceptId);
            bb.put((byte) phase);
            bb.put((byte) action);
            bb.putInt(t.length);
            bb.put(t);
            commands.write(CommandType.INTERCEPT_DECISION, bb.array(), total);
            return;
        }
        // Oversize tail → temp file; header carries the path.
        String path = stageBytes(t, ".dec");
        if (path == null) {
            return;
        }
        byte[] pb = path.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + 1 + 1 + 4 + pb.length)
            .order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(windowId);
        bb.putInt(interceptId);
        bb.put((byte) phase);
        bb.put((byte) action);
        bb.putInt(pb.length);
        bb.put(pb);
        finishStaged(path, commands.write(
            CommandType.INTERCEPT_DECISION_FILE, bb.array(), bb.position()));
    }

    /**
     * Answers a captured body chunk. kind: 0=pass-through, 1=replace, 2=drop.
     * For replace, the new body is staged to a temp file (it can be large).
     */
    public void resolveBodyEdit(int interceptId, int chunkSeq, int kind, byte[] replacement) {
        if (disposed) {
            return;
        }
        String path = "";
        if (kind == 1 && replacement != null && replacement.length > 0) {
            String p = stageBytes(replacement, ".body");
            if (p != null) {
                path = p;
            }
        }
        byte[] pb = path.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + 4 + 1 + 4 + pb.length)
            .order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(windowId);
        bb.putInt(interceptId);
        bb.putInt(chunkSeq);
        bb.put((byte) kind);
        bb.putInt(pb.length);
        bb.put(pb);
        boolean enqueued = commands.write(
            CommandType.INTERCEPT_BODY_EDIT, bb.array(), bb.position());
        finishStaged(path, enqueued); // no-op when path is empty (non-replace)
    }

    /** Stages {@code data} to a temp file the engine reads then deletes; returns its path. */
    private String stageBytes(byte[] data, String suffix) {
        try {
            Path tmp = Files.createTempFile("skia-fx-webview-net-", suffix);
            Files.write(tmp, data);
            return tmp.toString();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Finalizes a staged temp file against its enqueue result: if the command
     * was enqueued, register {@code deleteOnExit} as a crash backstop (the engine
     * deletes it after reading); if the ring was full and the command dropped,
     * delete it now so it doesn't orphan on disk (and leave a dangling
     * deleteOnExit entry) until JVM exit. (BUG-5)
     */
    private static void finishStaged(String path, boolean enqueued) {
        if (path == null || path.isEmpty()) {
            return;
        }
        Path p = Path.of(path);
        if (enqueued) {
            p.toFile().deleteOnExit();
        } else {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignore) {
                // best-effort; OS temp cleanup reclaims it
            }
        }
    }

    /**
     * Synchronous JS execution. Blocks the caller (bounded) until the engine
     * answers. Returns the typed result — {@code Integer}/{@code Double}/
     * {@code Boolean}/{@code String}/{@code null}/{@code JSObject}.
     *
     * <p>Throws {@link JSException} on a JavaScript error (syntax error, a script
     * V8 rejects, an uncaught exception) carrying the engine's message, and on a
     * timeout or a dead renderer — rather than freezing or returning a silent
     * {@code null}. A genuine JS {@code null}/{@code undefined} result still
     * returns Java {@code null}; only a real failure throws. A disposed page or
     * {@code null} script returns {@code null} (not an error).
     */
    public Object executeScript(String script) throws JSException {
        if (disposed || script == null) {
            return null;
        }
        int id = nextJsId.getAndIncrement();
        Pending p = new Pending(null);
        pendingJs.put(id, p);
        if (!writeJs(id, script)) {
            pendingJs.remove(id);
            throw new JSException("JavaScript could not be sent (script too large or command ring full)");
        }
        try {
            if (!p.latch.await(JS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                pendingJs.remove(id, p);
                throw new JSException("JavaScript execution timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendingJs.remove(id, p);
            throw new JSException("JavaScript execution interrupted");
        }
        if (p.error != null) {
            throw new JSException(p.error);
        }
        return p.result;
    }

    /** Asynchronous JS execution; the callback fires on the FX thread. */
    public void executeScript(String script, JsCallback callback) {
        if (disposed || script == null) {
            return;
        }
        int id = nextJsId.getAndIncrement();
        Pending p = new Pending(callback);
        pendingJs.put(id, p);
        if (!writeJs(id, script)) {
            pendingJs.remove(id);
            if (callback != null) {
                Invoker.getInvoker().postOnEventThread(() -> callback.onError("command ring full"));
            }
            return;
        }
        // Arm a bounded watchdog: if the result never arrives, drop the entry and
        // fail the callback so it doesn't leak forever. Use the value-conditional
        // remove(id, p): this fires JS_TIMEOUT_MS later, by which point the int id
        // may have been recycled by another in-flight op — a plain remove(id) could
        // evict that unrelated live entry. remove(id, p) can only ever drop OUR own.
        JS_TIMEOUT_SCHEDULER.schedule(() -> {
            if (pendingJs.remove(id, p) && p.async != null) {
                post(() -> p.async.onError("JS operation timed out"));
            }
        }, JS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    // --- JSObject operations (FX thread; block on the request-id latch) -----
    //
    // Each mirrors a netscape.javascript.JSObject method. objId 0 = the global
    // window. Values are tagged via JSValueCodec. Results return typed or throw
    // JSException on a JS error (matching the public JSObject contract).

    Object jsGetMember(int objId, String name) throws JSException {
        JsHeader h = jsHeader(objId);
        ByteArrayOutputStream o = h.out;
        leStr(o, name);
        return sendJsOp(CommandType.JS_GET_MEMBER, h.reqId, o);
    }

    void jsSetMember(int objId, String name, Object value) throws JSException {
        JsHeader h = jsHeader(objId);
        ByteArrayOutputStream o = h.out;
        leStr(o, name);
        o.writeBytes(JSValueCodec.encode(value, this));
        sendJsOp(CommandType.JS_SET_MEMBER, h.reqId, o);
    }

    void jsRemoveMember(int objId, String name) throws JSException {
        JsHeader h = jsHeader(objId);
        ByteArrayOutputStream o = h.out;
        leStr(o, name);
        sendJsOp(CommandType.JS_REMOVE_MEMBER, h.reqId, o);
    }

    Object jsGetSlot(int objId, int index) throws JSException {
        JsHeader h = jsHeader(objId);
        ByteArrayOutputStream o = h.out;
        leInt(o, index);
        return sendJsOp(CommandType.JS_GET_SLOT, h.reqId, o);
    }

    void jsSetSlot(int objId, int index, Object value) throws JSException {
        JsHeader h = jsHeader(objId);
        ByteArrayOutputStream o = h.out;
        leInt(o, index);
        o.writeBytes(JSValueCodec.encode(value, this));
        sendJsOp(CommandType.JS_SET_SLOT, h.reqId, o);
    }

    Object jsCall(int objId, String methodName, Object... args) throws JSException {
        JsHeader h = jsHeader(objId);
        ByteArrayOutputStream o = h.out;
        leStr(o, methodName);
        int argc = (args == null) ? 0 : args.length;
        leInt(o, argc);
        for (int i = 0; i < argc; i++) {
            o.writeBytes(JSValueCodec.encode(args[i], this));
        }
        return sendJsOp(CommandType.JS_CALL, h.reqId, o);
    }

    Object jsEval(int objId, String script) throws JSException {
        JsHeader h = jsHeader(objId);
        ByteArrayOutputStream o = h.out;
        leStr(o, script);
        return sendJsOp(CommandType.JS_EVAL, h.reqId, o);
    }

    /**
     * Guard a {@link JSObjectImpl} operation. Throws if the page is disposed or if
     * the wrapper belongs to a previous document (its {@code generation} no longer
     * matches the live {@link #jsGeneration}) — the engine reuses object ids per
     * document, so using a stale wrapper would silently target an unrelated V8
     * object in the new page. Called at the head of every JSObject op.
     */
    void checkLive(int generation) throws JSException {
        if (disposed) {
            throw new JSException("WebView page disposed");
        }
        if (generation != jsGeneration) {
            throw new JSException("stale JSObject: its document was replaced by a navigation");
        }
    }

    /** Fire-and-forget release of a JS object id (from {@link JSObjectImpl}'s Cleaner). */
    void jsRelease(int objId) {
        if (disposed || objId == 0) {
            return;
        }
        // The command ring is single-producer (FX thread). The Cleaner runs on
        // its own thread, so marshal the release onto the FX thread.
        Invoker invoker = Invoker.getInvoker();
        if (invoker != null) {
            invoker.postOnEventThread(() -> {
                if (!disposed) {
                    commands.writeNode(CommandType.JS_RELEASE, windowId, objId);
                }
            });
        }
    }

    /**
     * Wraps an engine-assigned JS object id; id 0 is JS {@code null}/{@code
     * undefined}. Returns the SAME {@link JSObjectImpl} for a given id as long as
     * a prior wrapper is still reachable, so there is exactly one Cleaner (one
     * {@code JS_RELEASE}) per live id and {@code ==} identity holds. (bugs.md H6)
     */
    JSObjectImpl wrapJsObject(int id) {
        if (id == 0) {
            return null;
        }
        for (;;) {
            WeakReference<JSObjectImpl> ref = jsObjectCache.get(id);
            if (ref != null) {
                JSObjectImpl existing = ref.get();
                if (existing != null) {
                    return existing;
                }
                // Stale token (wrapper GC'd; its Cleaner may not have run yet).
                // Drop it only if still mapped to this exact token, then recreate.
                jsObjectCache.remove(id, ref);
            }
            JSObjectImpl created = new JSObjectImpl(this, id, jsGeneration);
            WeakReference<JSObjectImpl> token = new WeakReference<>(created);
            if (jsObjectCache.putIfAbsent(id, token) == null) {
                // We own the cache slot — arm the release against this token so a
                // later re-wrap that replaces the slot suppresses our JS_RELEASE.
                created.arm(token);
                return created;
            }
            // Lost the race to a concurrent wrap; `created` was never armed and
            // never owned the slot, so it releases nothing. Retry to get the winner.
        }
    }

    /**
     * Cleaner callback for a GC'd {@link JSObjectImpl}: send {@code JS_RELEASE}
     * only if the cache slot for {@code id} is still owned by this wrapper's
     * {@code token}. If a newer wrapper has replaced the slot, the id now belongs
     * to that live wrapper and must NOT be released. (bugs.md H6)
     */
    void jsReleaseIfOwner(int id, WeakReference<JSObjectImpl> token) {
        if (jsObjectCache.remove(id, token)) {
            jsRelease(id);
        }
    }

    /** Registers a Java object for exposure to JS (slice B); returns its id (0 for null). */
    int registerJavaObject(Object obj) {
        if (obj == null) {
            return 0;
        }
        synchronized (javaObjectsLock) {
            Integer existing = javaObjectIds.get(obj);
            if (existing != null) {
                return existing;   // same object already exposed — reuse its id
            }
            int id = nextJavaObjId.getAndIncrement();
            javaObjects.put(id, obj);
            javaObjectIds.put(obj, id);
            return id;
        }
    }

    /** Drops the exposed-Java-object bridge (both directions) atomically. */
    private void clearJavaObjects() {
        synchronized (javaObjectsLock) {
            javaObjects.clear();
            javaObjectIds.clear();
        }
    }

    /** Resolves a javaObject id back to the original Java object (the JS→Java unwrap rule). */
    Object lookupJavaObject(int id) {
        return id == 0 ? null : javaObjects.get(id);
    }

    // ── Java-from-JS (slice B) ──────────────────────────────────────────────
    //
    // The renderer host proxy for an exposed Java object fires JS_CALLBACK when
    // the page calls it, and the JS call gets back a Promise. We resolve the
    // target, pick the method (a named method by arg count, or — for `fn(arg)`
    // on a functional interface / lambda — its single abstract method), coerce
    // the decoded args, invoke it on the FX thread, and ship the return value
    // (or exception) back via JS_CALLBACK_RESULT to settle that promise. The
    // renderer never blocks waiting (the promise is async), so a Java callback
    // may itself do a blocking Java→JS op without a reentrant FX deadlock.

    private void invokeJavaFromJs(int callId, int javaId, String name, Object[] args) {
        Object target = lookupJavaObject(javaId);
        if (target == null) {
            sendJavaCallResult(callId, false, null, "no such Java object");
            return;
        }
        post(() -> {
            boolean ok = false;
            Object result = null;
            String error = null;
            try {
                Method m = name.isEmpty()
                    ? functionalMethod(target.getClass())
                    : namedMethod(target.getClass(), name, args.length);
                if (m == null) {
                    error = "no matching Java method: "
                        + (name.isEmpty() ? "<functional>" : name) + "/" + args.length;
                } else {
                    try {
                        m.setAccessible(true);
                    } catch (RuntimeException ignore) {
                        // Strong encapsulation may refuse; a public interface
                        // method is still invokable, so press on.
                    }
                    result = m.invoke(target, coerceArgs(m.getParameterTypes(), args));
                    ok = true;
                }
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
                error = cause.toString();
            } catch (Throwable t) {
                error = t.toString();
            }
            sendJavaCallResult(callId, ok, result, error);
        });
    }

    /**
     * Settles the JS promise for a host-proxy call: encodes the Java return
     * value (or error) and writes JS_CALLBACK_RESULT. Runs on the FX thread (the
     * command ring's single producer). A return value too large for one command
     * slot is reported as an error rather than silently hanging the promise.
     */
    private void sendJavaCallResult(int callId, boolean ok, Object value, String error) {
        if (disposed) {
            return;
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream(32);
        leInt(o, windowId);
        leInt(o, callId);
        if (ok) {
            byte[] enc = JSValueCodec.encode(value, this);
            if (enc.length > MemoryLayout.MAX_PAYLOAD - 9) {
                sendJavaCallResult(callId, false, null,
                    "Java return value too large to marshal to JS");
                return;
            }
            o.write(0);                       // status: success
            o.write(enc, 0, enc.length);
        } else {
            o.write(1);                       // status: error
            String msg = error != null ? error : "Java error";
            if (msg.length() > 180) {
                msg = msg.substring(0, 180);
            }
            byte[] m = msg.getBytes(StandardCharsets.UTF_8);
            leInt(o, m.length);
            o.write(m, 0, m.length);
        }
        commands.writeBytes(CommandType.JS_CALLBACK_RESULT, o.toByteArray());
    }

    /** Public method named {@code name} with {@code argc} parameters (JFX overload-by-arity rule). */
    private static Method namedMethod(Class<?> cls, String name, int argc) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argc) {
                return m;
            }
        }
        return null;
    }

    /** The single abstract method of an implemented functional interface, or null. */
    private static Method functionalMethod(Class<?> cls) {
        for (Class<?> iface : collectInterfaces(cls)) {
            Method sam = null;
            for (Method m : iface.getMethods()) {
                if (!Modifier.isAbstract(m.getModifiers()) || isObjectMethod(m)) {
                    continue;
                }
                if (sam != null) {
                    sam = null;   // >1 abstract method → not a SAM interface
                    break;
                }
                sam = m;
            }
            if (sam != null) {
                return sam;
            }
        }
        return null;
    }

    private static Set<Class<?>> collectInterfaces(Class<?> cls) {
        Set<Class<?>> out = new LinkedHashSet<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                addInterface(i, out);
            }
        }
        return out;
    }

    private static void addInterface(Class<?> i, Set<Class<?>> out) {
        if (out.add(i)) {
            for (Class<?> s : i.getInterfaces()) {
                addInterface(s, out);
            }
        }
    }

    /** {@code equals}/{@code hashCode}/{@code toString} are never the functional SAM. */
    private static boolean isObjectMethod(Method m) {
        if (m.getDeclaringClass() == Object.class) {
            return true;
        }
        String n = m.getName();
        int pc = m.getParameterCount();
        return (pc == 0 && (n.equals("toString") || n.equals("hashCode")))
            || (pc == 1 && n.equals("equals"));
    }

    private static Object[] coerceArgs(Class<?>[] types, Object[] args) {
        Object[] out = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            out[i] = coerce(types[i], i < args.length ? args[i] : null);
        }
        return out;
    }

    /** Best-effort conversion of a decoded JS value to the Java parameter type. */
    private static Object coerce(Class<?> type, Object a) {
        if (a == null) {
            return defaultValue(type);
        }
        if (type.isInstance(a)) {
            return a;
        }
        if (a instanceof Number n) {
            if (type == int.class || type == Integer.class) return n.intValue();
            if (type == long.class || type == Long.class) return n.longValue();
            if (type == double.class || type == Double.class) return n.doubleValue();
            if (type == float.class || type == Float.class) return n.floatValue();
            if (type == short.class || type == Short.class) return n.shortValue();
            if (type == byte.class || type == Byte.class) return n.byteValue();
        }
        if ((type == boolean.class || type == Boolean.class) && a instanceof Boolean b) {
            return b;
        }
        if (type == String.class) {
            return a.toString();
        }
        return a;   // last resort; an incompatible arg throws in invoke() (caught)
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return 0;   // int
    }

    // Carries the command stream and its freshly-allocated reqId together, so the
    // reqId is never shared mutable state (JSObject ops can run off the FX thread
    // concurrently — a shared field would race and register the wrong reqId).
    private static final class JsHeader {
        final ByteArrayOutputStream out;
        final int reqId;
        JsHeader(ByteArrayOutputStream out, int reqId) { this.out = out; this.reqId = reqId; }
    }

    // Builds the common [windowId][reqId][objId] command prefix, returning both the
    // stream and the allocated reqId so each op uses its own local reqId.
    private JsHeader jsHeader(int objId) {
        int reqId = nextJsId.getAndIncrement();
        ByteArrayOutputStream o = new ByteArrayOutputStream(32);
        leInt(o, windowId);
        leInt(o, reqId);
        leInt(o, objId);
        return new JsHeader(o, reqId);
    }

    private Object sendJsOp(int type, int reqId, ByteArrayOutputStream o)
            throws JSException {
        if (disposed) {
            throw new JSException("WebView page disposed");
        }
        Pending p = new Pending(null);
        pendingJs.put(reqId, p);
        if (!commands.writeBytes(type, o.toByteArray())) {
            pendingJs.remove(reqId);
            throw new JSException("JS command could not be sent (payload too large or ring full)");
        }
        try {
            if (!p.latch.await(JS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                pendingJs.remove(reqId, p);
                throw new JSException("JS operation timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendingJs.remove(reqId, p);
            throw new JSException("JS operation interrupted");
        }
        if (p.error != null) {
            throw new JSException(p.error);
        }
        return p.result;
    }

    private static void leInt(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }

    private static void leStr(ByteArrayOutputStream o, String s) {
        byte[] u = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        leInt(o, u.length);
        o.writeBytes(u);
    }

    /**
     * Writes an EXECUTE_JS command carrying {@code requestId}. Small scripts go
     * inline through the ring slot; larger ones (script injection, minified
     * bundles) are staged to a temp file and sent as an EXECUTE_JS_FILE path —
     * the engine reads the file, deletes it, and executes the contents. This
     * lifts the ring-slot size cap so scripts of any size run.
     */
    private boolean writeJs(int id, String script) {
        byte[] u = script.getBytes(StandardCharsets.UTF_8);
        if (12 + u.length <= MemoryLayout.MAX_PAYLOAD) {
            return commands.writeStringWithId(CommandType.EXECUTE_JS, windowId, id, script);
        }
        try {
            Path tmp = Files.createTempFile("skia-fx-webview-js-", ".js");
            Files.write(tmp, u);
            boolean ok = commands.writeStringWithId(
                CommandType.EXECUTE_JS_FILE, windowId, id, tmp.toString());
            finishStaged(tmp.toString(), ok); // delete the temp if the ring dropped it
            return ok;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isAlive() {
        return !disposed && engine.isAlive();
    }

    /**
     * Engine command-line switches. Overridable for bring-up/diagnostics via
     * {@code -Dskia.webview.switches=--a,--b}. Default {@code --no-sandbox}
     * (the GPU/child sandbox broker is not yet wired for this embedder).
     */
    private static List<String> engineSwitches() {
        String prop = System.getProperty("skia.webview.switches");
        if (prop != null && !prop.isBlank()) {
            return List.of(prop.split("\\s*,\\s*"));
        }
        return List.of("--no-sandbox");
    }

    // --- Event handling (pump thread) -----------------------------------

    private void onEvent(EventSlot slot) {
        switch (slot.eventType) {
            case NativeEventType.DOC_LOADING -> {
                // New navigation. Reset the load-completion coordinator so the
                // SUCCEEDED of the PREVIOUS load can never satisfy THIS one.
                pageLoaded = false;
                domReady = false;
                loadTerminal = false;
                // Drop the previous document mirror before the engine streams the
                // new tree (DOM_ELEMENT/DOM_TEXT) in.
                dom.reset();
                // The renderer's per-page JS table and the Java-objects bridge
                // belong to the OLD document's JS context; the new page gets a
                // fresh context. Drop both so they don't accumulate across loads.
                // Stale JSObject wrappers' Cleaners later no-op (jsReleaseIfOwner
                // finds the token gone). javaObjects held strong refs that
                // otherwise lived until dispose — the cross-navigation leak. (H7)
                // Bump the generation FIRST so any JSObject the app still holds
                // from the previous document throws on use (checkLive) instead of
                // aliasing a reused id into the new document's V8 context.
                jsGeneration++;
                jsObjectCache.clear();
                clearJavaObjects();
                // Any JS call still in flight targets the OLD document's V8
                // context, which is gone — its reply will never arrive. Fail
                // them now so the Pending entries + callbacks are freed and any
                // blocked synchronous caller is released instead of leaking and
                // hanging until dispose.
                failAllPendingJs("WebView navigated away before the script returned");
                post(() -> client.onPageStarted(null));
            }
            case NativeEventType.DOC_READY -> {
                // Main-frame load finished (sync DidFinishLoad). One half of the
                // load-complete gate; the other is DOM_TREE_READY. We do NOT fire
                // onPageFinished directly here anymore — maybeFinishLoad() emits
                // the single SUCCEEDED once both the page and the DOM are ready.
                pageLoaded = true;
                maybeFinishLoad(false);
            }
            case NativeEventType.DOC_INTERACTIVE -> {
                // DidStopLoading — the reliable "all frames stopped loading"
                // (network-idle) terminal. Backstop for two failure modes:
                //   * DidFinishLoad never fired (e.g. a subresource that blocks
                //     onload) — without this the worker hangs in RUNNING forever.
                //   * The DOM_TREE_READY Mojo reply stalled while idle.
                // Hence force=true: complete on page-load alone, not waiting on
                // the DOM walk. The DOM mirror still streams in via DOM_ELEMENT/
                // DOM_TEXT; getDocument() blocks on it separately if needed.
                pageLoaded = true;
                maybeFinishLoad(true);
            }
            case NativeEventType.DOC_CONTENT_LOADED -> {
                // DOMContentLoaded: the document is parsed but subresources may
                // still be loading — not a load-complete signal for the worker.
                // The engine uses it to kick off the DOM-tree walk; intentionally
                // not routed to onPageFinished (see DOC_READY above).
            }
            case NativeEventType.DOC_TITLE_CHANGED -> {
                String title = slot.readLenString(4); // after windowId
                post(() -> client.onTitleChanged(title));
            }
            case NativeEventType.DOC_NAVIGATION -> {
                String url = slot.readLenString(4);
                post(() -> client.onNavigation(url));
            }
            case NativeEventType.HISTORY_STATE -> {
                // [windowId:4][currentIndex:4][count:4]{[urlLen:4][url][titleLen:4][title]}…
                // The full list rides inline; EventRingBuffer transparently
                // reassembles it from multiple ring slots when it exceeds one.
                int currentIndex = slot.readInt(4);
                int count = slot.readInt(8);
                int maxByBytes = Math.max(0, (slot.length - 12) / 8); // min 8 B/entry
                if (count < 0) count = 0;
                if (count > maxByBytes) count = maxByBytes;
                String[] urls = new String[count];
                String[] titles = new String[count];
                int off = 12;
                for (int i = 0; i < count; i++) {
                    String u = slot.readLenString(off);
                    off += 4 + u.getBytes(StandardCharsets.UTF_8).length;
                    String t = slot.readLenString(off);
                    off += 4 + t.getBytes(StandardCharsets.UTF_8).length;
                    urls[i] = u;
                    titles[i] = t;
                }
                final int ci = currentIndex;
                post(() -> client.onHistoryChanged(ci, urls, titles));
            }
            case NativeEventType.SESSION_STATE -> {
                // [windowId:4][blob:N] — opaque engine session snapshot. Reassembled
                // by EventRingBuffer when large.
                int blobLen = slot.length - 4;
                if (blobLen > 0) {
                    byte[] blob = slot.readBytes(4, blobLen);
                    post(() -> client.onSessionState(blob));
                }
            }
            case NativeEventType.LOAD_ERROR -> {
                int code = slot.readInt(4);
                String url = slot.readLenString(8);
                int descAt = 8 + 4 + url.getBytes(StandardCharsets.UTF_8).length;
                String desc = slot.readLenString(descAt);
                // Terminal failure for this load. Latch it so the DOC_INTERACTIVE
                // backstop (DidStopLoading fires after DidFailLoad) can't override
                // FAILED with a spurious SUCCEEDED.
                loadTerminal = true;
                post(() -> client.onLoadError(code, url, desc));
            }
            case NativeEventType.CONSOLE_MESSAGE -> {
                int level = slot.readInt(4);
                int line = slot.readInt(8);
                String msg = slot.readLenString(12);
                int srcAt = 12 + 4 + msg.getBytes(StandardCharsets.UTF_8).length;
                String src = slot.readLenString(srcAt);
                post(() -> client.onConsoleMessage(level, line, msg, src));
            }
            case NativeEventType.JS_RESULT -> {
                // Legacy untyped result (kept for back-compat): [windowId:4][requestId:4][len:4][utf8].
                int reqId = slot.readInt(4);
                String result = slot.readLenString(8);
                completeJs(reqId, result, null);
            }
            case NativeEventType.JS_VALUE -> {
                // Typed result of any sync JS op: [windowId:4][requestId:4][value:tagged].
                int reqId = slot.readInt(4);
                Object value = null;
                String error = null;
                try {
                    value = JSValueCodec.decode(slot, 8, this);
                } catch (RuntimeException ex) {
                    // A truncated/corrupt slot makes the tagged-value decode (or
                    // its internal length read) throw IllegalStateException. If it
                    // escaped here the pump would drop the event WITHOUT calling
                    // completeJs, leaving the synchronous FX caller blocked the
                    // full JS_TIMEOUT_MS. Complete the request with an error so the
                    // FX thread unblocks immediately. (bugs.md M16)
                    error = "malformed JS value: " + ex;
                }
                completeJs(reqId, value, error);
            }
            case NativeEventType.JS_ERROR -> {
                // [windowId:4][requestId:4][errLen:4][utf8]
                int reqId = slot.readInt(4);
                String err = slot.readLenString(8);
                completeJs(reqId, null, err);
            }
            case NativeEventType.JS_CALLBACK -> {
                // JS called a Java object exposed via setMember/a call arg:
                // [windowId:4][javaObjectId:4][callId:4][nameLen:4][name][argc:4]{value}…
                // Rides WriteEventLarge, so `slot` is already reassembled. callId
                // correlates the JS_CALLBACK_RESULT that settles the JS promise.
                int javaId = slot.readInt(4);
                int callId = slot.readInt(8);
                String name = slot.readLenString(12);
                int nameBytes = name.getBytes(StandardCharsets.UTF_8).length;
                int off = 12 + 4 + nameBytes;
                int argc = slot.readInt(off);
                off += 4;
                // argc is engine-supplied; a corrupt value would force an
                // oversized allocation on the pump thread. Each decoded value
                // is at least one tag byte, so argc can never exceed the bytes
                // remaining in the (already reassembled) slot — reject anything
                // larger rather than allocate gigabytes.
                int remaining = slot.length - off;
                if (argc < 0 || argc > remaining) {
                    // Corrupt count. MUST settle the call — a bare return would
                    // leave the JS-side Promise for this host call pending forever
                    // (page hang). Send a failure result instead.
                    sendJavaCallResult(callId, false, null, "malformed callback arguments");
                    return;
                }
                Object[] args = new Object[argc];
                int[] consumed = new int[1];
                try {
                    for (int i = 0; i < args.length; i++) {
                        // Only TAG_STRING bounds-guards its reads; a fixed-width
                        // tag (DOUBLE/INT/JS_OBJECT/…) whose bytes run past the
                        // slot throws. Catch it and settle, rather than letting it
                        // escape onEvent and strand the JS Promise in RUNNING.
                        args[i] = JSValueCodec.decode(slot, off, this, consumed);
                        off += consumed[0];
                    }
                } catch (RuntimeException ex) {
                    sendJavaCallResult(callId, false, null, "malformed callback arguments");
                    return;
                }
                invokeJavaFromJs(callId, javaId, name, args);
            }
            case NativeEventType.RENDER_PROCESS_GONE, NativeEventType.ENGINE_SHUTDOWN,
                 NativeEventType.ENGINE_ERROR -> {
                int status = slot.length >= 8 ? slot.readInt(4) : 0;
                // Release any in-flight executeScript / JSObject waiters now with
                // an error, so a script that crashed the renderer throws JSException
                // immediately instead of blocking the FX thread for the full
                // JS_TIMEOUT_MS (the "frozen GUI" symptom).
                failAllPendingJs("WebView renderer/engine terminated");
                post(() -> client.onEngineGone(status));
            }
            case NativeEventType.CURSOR_CHANGED -> {
                // [windowId:4][cursorType:4]
                int cursorType = slot.readInt(4);
                post(() -> client.onCursorChanged(cursorType));
            }
            case NativeEventType.FRAME_READY -> {
                // [windowId:4][bufIndex:4][width:4][height:4][stride:4][logicalW:f32][logicalH:f32]
                int bufIndex = slot.readInt(4);
                int w = slot.readInt(8);
                int h = slot.readInt(12);
                int stride = slot.readInt(16);
                double logicalW = Float.intBitsToFloat(slot.readInt(20));
                double logicalH = Float.intBitsToFloat(slot.readInt(24));
                boolean published =
                    frameSurface.publish(bufIndex, w, h, stride, logicalW, logicalH);
                if (published) {
                    Runnable cb = renderCallback;
                    if (cb != null && !disposed) {
                        cb.run(); // cheap: schedules a node repaint (see WebPage)
                    }
                }
            }
            case NativeEventType.POPUP_FRAME -> {
                // [windowId:4][bufIndex:4][w:4][h:4][stride:4][x:f32][y:f32][dipW:f32][dipH:f32]
                int bufIndex = slot.readInt(4);
                int w = slot.readInt(8);
                int h = slot.readInt(12);
                int stride = slot.readInt(16);
                double x = Float.intBitsToFloat(slot.readInt(20));
                double y = Float.intBitsToFloat(slot.readInt(24));
                double dipW = Float.intBitsToFloat(slot.readInt(28));
                double dipH = Float.intBitsToFloat(slot.readInt(32));
                boolean ok = frameSurface.publishPopup(bufIndex, w, h, stride, x, y, dipW, dipH);
                if (ok) {
                    Runnable cb = renderCallback;
                    if (cb != null && !disposed) {
                        cb.run();
                    }
                }
            }
            case NativeEventType.POPUP_CLOSED -> {
                frameSurface.clearPopup();
                Runnable cb = renderCallback;
                if (cb != null && !disposed) {
                    cb.run();
                }
            }
            case NativeEventType.PREVIEW_FRAME -> {
                // [windowId:4][bufIndex:4][w:4][h:4][stride:4][x:f32][y:f32][dipW:f32][dipH:f32]
                // x/y are 0 (Java centers); dipW/dipH = preview DIP size.
                int bufIndex = slot.readInt(4);
                int w = slot.readInt(8);
                int h = slot.readInt(12);
                int stride = slot.readInt(16);
                double dipW = Float.intBitsToFloat(slot.readInt(28));
                double dipH = Float.intBitsToFloat(slot.readInt(32));
                boolean ok = frameSurface.publishPreview(bufIndex, w, h, stride, dipW, dipH);
                if (ok) {
                    Runnable cb = renderCallback;
                    if (cb != null && !disposed) {
                        cb.run();
                    }
                }
            }
            case NativeEventType.PRINT_PREVIEW_OPENED -> {
                // The preview is composited as a centered overlay via the popup
                // channel; the page stays live on the main channel underneath (the
                // engine keeps publishing it, and reflows it on resize). Input is
                // redirected to the preview engine-side, so the page receives none
                // while the modal is up. Just flag the modal active.
                previewActive = true;
                Runnable cb = renderCallback;
                if (cb != null && !disposed) {
                    cb.run();
                }
            }
            case NativeEventType.PRINT_PREVIEW_CLOSED -> {
                previewActive = false;
                // Drop the preview modal + any open dropdown so the live page
                // (which never stopped rendering on the main channel) shows cleanly.
                frameSurface.clearPreview();
                frameSurface.clearPopup();
                Runnable cb = renderCallback;
                if (cb != null && !disposed) {
                    cb.run();
                }
            }
            case NativeEventType.DIALOG_REQUESTED -> {
                // [windowId:4][dialogId:4][dialogType:4][msgLen:4][msg][defLen:4][def]
                int dialogId = slot.readInt(4);
                int dialogType = slot.readInt(8);
                String msg = slot.readLenString(12);
                int defAt = 12 + 4 + utf8Len(msg);
                String def = slot.readLenString(defAt);
                post(() -> client.onDialogRequested(dialogId, dialogType, msg, def));
            }
            case NativeEventType.SAVE_PDF_REQUESTED -> {
                // [windowId:4][requestId:4][nameLen:4][utf8Name:N]
                int requestId = slot.readInt(4);
                String name = slot.readLenString(8);
                post(() -> client.onSavePdfRequested(requestId, name));
            }
            case NativeEventType.COLOR_CHOOSER_OPEN -> {
                // [windowId:4][chooserId:4][initialRgba:4][suggCount:4]{[rgba:4]}…
                int chooserId = slot.readInt(4);
                int initialRgba = slot.readInt(8);
                int[] suggestions = readIdArray(slot, 12);
                post(() -> client.onColorChooser(chooserId, initialRgba, suggestions));
            }
            case NativeEventType.FILE_CHOOSER_REQUESTED -> {
                // [windowId:4][chooserId:4][mode:4][titleLen:4][title][initLen:4][init][acceptLen:4][accept]
                int chooserId = slot.readInt(4);
                int mode = slot.readInt(8);
                String title = slot.readLenString(12);
                int initAt = 12 + 4 + utf8Len(title);
                String initialName = slot.readLenString(initAt);
                int acceptAt = initAt + 4 + utf8Len(initialName);
                String acceptCsv = slot.readLenString(acceptAt);
                post(() -> client.onFileChooserRequested(chooserId, mode, title,
                        initialName, acceptCsv));
            }
            case NativeEventType.SELECT_POPUP_OPEN -> decodeSelectPopup(slot);
            case NativeEventType.PERMISSION_REQUESTED -> {
                // [windowId:4][permId:4][permType:4][originLen:4][origin]
                int permId = slot.readInt(4);
                int permType = slot.readInt(8);
                String origin = slot.readLenString(12);
                post(() -> client.onPermissionRequested(permId, permType, origin));
            }
            case NativeEventType.AUTH_REQUESTED -> {
                // [windowId:4][authId:4][scheme:4][isProxy:1][hostLen:4][host][realmLen:4][realm]
                int authId = slot.readInt(4);
                int scheme = slot.readInt(8);
                boolean proxy = slot.readByte(12) != 0;
                String host = slot.readLenString(13);
                int realmAt = 13 + 4 + utf8Len(host);
                String realm = slot.readLenString(realmAt);
                post(() -> client.onAuthRequested(authId, scheme, proxy, host, realm));
            }
            case NativeEventType.DOWNLOAD_REQUESTED -> {
                // [windowId:4][downloadId:4][totalBytes:8][urlLen:4][url][nameLen:4][name][mimeLen:4][mime]
                int downloadId = slot.readInt(4);
                long totalBytes = slot.readLong(8);
                String url = slot.readLenString(16);
                int nameAt = 16 + 4 + utf8Len(url);
                String name = slot.readLenString(nameAt);
                int mimeAt = nameAt + 4 + utf8Len(name);
                String mime = slot.readLenString(mimeAt);
                post(() -> client.onDownloadRequested(downloadId, totalBytes, url, name, mime));
            }
            case NativeEventType.DOWNLOAD_PROGRESS -> {
                // [windowId:4][downloadId:4][state:4][received:8][total:8]
                int downloadId = slot.readInt(4);
                int state = slot.readInt(8);
                long received = slot.readLong(12);
                long total = slot.readLong(20);
                post(() -> client.onDownloadProgress(downloadId, state, received, total));
            }
            case NativeEventType.DOWNLOAD_FINISHED -> {
                // [windowId:4][downloadId:4][state:4][pathLen:4][path]
                int downloadId = slot.readInt(4);
                int state = slot.readInt(8);
                String path = slot.readLenString(12);
                post(() -> client.onDownloadFinished(downloadId, state, path));
            }
            case NativeEventType.CONTEXT_MENU_REQUESTED -> {
                // [windowId:4][menuId:4][x:f32][y:f32][flags:4][linkLen:4][link][srcLen:4][src][selLen:4][sel]
                int menuId = slot.readInt(4);
                double x = Float.intBitsToFloat(slot.readInt(8));
                double y = Float.intBitsToFloat(slot.readInt(12));
                int flags = slot.readInt(16);
                String link = slot.readLenString(20);
                int srcAt = 20 + 4 + utf8Len(link);
                String src = slot.readLenString(srcAt);
                int selAt = srcAt + 4 + utf8Len(src);
                String sel = slot.readLenString(selAt);
                post(() -> client.onContextMenu(menuId, x, y, flags, link, src, sel));
            }
            case NativeEventType.FULLSCREEN_REQUESTED -> {
                // [windowId:4][fsId:4][entering:1]
                int fsId = slot.readInt(4);
                boolean entering = slot.readByte(8) != 0;
                post(() -> client.onFullscreenRequested(fsId, entering));
            }
            case NativeEventType.FAVICON_CHANGED -> {
                String url = slot.readLenString(4);
                post(() -> client.onFaviconChanged(url));
            }
            case NativeEventType.TOOLTIP_CHANGED -> {
                String text = slot.readLenString(4);
                post(() -> client.onTooltipChanged(text));
            }
            case NativeEventType.REQUEST_WILL_BE_SENT -> decodeNetworkRequest(slot);
            case NativeEventType.RESPONSE_RECEIVED -> decodeNetworkResponse(slot);
            case NativeEventType.RESPONSE_BODY_CHUNK -> decodeBodyChunk(slot);
            case NativeEventType.INTERCEPT_COMPLETE -> {
                int interceptId = slot.readInt(4);
                int netError = slot.readInt(8);
                post(() -> client.onNetworkComplete(interceptId, netError));
            }
            case NativeEventType.DOM_ELEMENT -> decodeDomElement(slot);
            case NativeEventType.DOM_TEXT -> decodeDomText(slot);
            case NativeEventType.DOM_TREE_READY -> {
                dom.onDomTreeReady();
                post(client::onDomTreeReady);
                // Second half of the load-complete gate: the DOM mirror is now
                // populated. Fire the single SUCCEEDED if the page also finished.
                domReady = true;
                maybeFinishLoad(false);
            }
            case NativeEventType.MUTATION_ATTRIBUTE -> {
                int nodeId = slot.readInt(4);
                int off = 8;
                String name = str16(slot, off); off += 2 + utf8Len(name);
                String oldV = str16(slot, off); off += 2 + utf8Len(oldV);
                String newV = str16(slot, off);
                dom.onMutationAttribute(nodeId, name, oldV, newV);
            }
            case NativeEventType.MUTATION_CHILDREN -> decodeMutationChildren(slot);
            case NativeEventType.MUTATION_TEXT -> {
                int nodeId = slot.readInt(4);
                int off = 8;
                String oldV = str16(slot, off); off += 2 + utf8Len(oldV);
                String newV = str16(slot, off);
                dom.onMutationText(nodeId, oldV, newV);
            }
            default -> decodeDomInteraction(slot);
        }
    }

    // --- DOM event decoders (pump thread) -------------------------------
    // All DOM events carry [windowId:4] (prepended by the engine's EventWriter),
    // so the node id is at offset 4. Verified against jux_dom_client_impl.cc and
    // jux_command_dispatch.cc (OnDomElement/OnDomText). Lengths: tag/id are u8,
    // class/text are u16-LE; mutation name/old/new are u16-LE.

    // NOTE on truncation: the event ring slot caps the payload at MAX_PAYLOAD
    // (248 B). A node with a large text/class field (e.g. an inline <script> or
    // <style> body) does not fit, so the slot carries only the leading bytes and
    // the inner length prefix can exceed what is actually present. Every string
    // read below is therefore CLAMPED to the bytes in the slot — a partial value
    // is fine (DOM structure stays correct), an over-read is not. (The engine
    // capping the field to the slot so the declared length is honest is a
    // follow-up; this guard makes the Java reader robust regardless.)

    private void decodeDomElement(EventSlot slot) {
        int nodeId = slot.readInt(4);
        int parentId = slot.readInt(8);
        int off = 12;
        int tagLen = u8(slot, off); off += 1;
        String tag = clamped(slot, off, tagLen); off += tagLen;
        int idLen = u8(slot, off); off += 1;
        String idAttr = clamped(slot, off, idLen); off += idLen;
        String className = str16(slot, off);
        dom.onDomElement(nodeId, parentId, tag, idAttr, className);
    }

    private void decodeDomText(EventSlot slot) {
        int nodeId = slot.readInt(4);
        int parentId = slot.readInt(8);
        String text = str16(slot, 12);
        dom.onDomText(nodeId, parentId, text);
    }

    private void decodeMutationChildren(EventSlot slot) {
        int parentId = slot.readInt(4);
        int off = 8;
        int[] added = readIdArray(slot, off);
        off += 4 + added.length * 4;
        int[] removed = readIdArray(slot, off);
        dom.onMutationChildren(parentId, added, removed);
    }

    /** Reads {@code [count:4]{[id:4]}…}, clamping count to the bytes present. */
    private static int[] readIdArray(EventSlot s, int off) {
        if (off + 4 > s.length) {
            return new int[0];
        }
        int count = s.readInt(off);
        int avail = (s.length - (off + 4)) / 4;
        if (count < 0 || count > avail) {
            count = Math.max(0, avail);
        }
        int[] ids = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = s.readInt(off + 4 + i * 4);
        }
        return ids;
    }

    private void decodeDomInteraction(EventSlot slot) {
        String name = DomBridge.domEventName(slot.eventType);
        if (name.isEmpty()) {
            return; // genuinely unhandled event type
        }
        int nodeId = slot.readInt(4);
        float x = 0f, y = 0f;
        int button = 0;
        if (DomBridge.isMouseInteraction(slot.eventType) && slot.length >= 20) {
            x = Float.intBitsToFloat(slot.readInt(8));
            y = Float.intBitsToFloat(slot.readInt(12));
            button = slot.readInt(16);
        }
        final float fx = x, fy = y;
        final int fbtn = button;
        post(() -> dom.fireDomEvent(nodeId, name, fx, fy, fbtn));
    }

    private void decodeSelectPopup(EventSlot slot) {
        // [windowId:4][popupId:4][flags:4][selIndex:4][ax:f32][ay:f32][aw:f32][ah:f32][pathLen:4][utf8Path]
        int popupId = slot.readInt(4);
        int flags = slot.readInt(8);
        boolean multiple = (flags & 0x1) != 0;
        int selIndex = slot.readInt(12);
        double ax = Float.intBitsToFloat(slot.readInt(16));
        double ay = Float.intBitsToFloat(slot.readInt(20));
        double aw = Float.intBitsToFloat(slot.readInt(24));
        double ah = Float.intBitsToFloat(slot.readInt(28));
        String path = slot.readLenString(32);
        List<SelectItemData> items = readSelectItemsFile(path);
        post(() -> client.onSelectPopup(popupId, multiple, selIndex, ax, ay, aw, ah, items));
    }

    /**
     * Reads the option list the engine staged to a temp file (too large for a
     * ring slot), then deletes it. Compact binary format:
     * {@code [count:4]{[labelLen:2][label][valueLen:2][value][enabled:1][groupLen:2][group]}…},
     * all little-endian — symmetric with the engine writer.
     */
    private static List<SelectItemData> readSelectItemsFile(String path) {
        List<SelectItemData> out = new ArrayList<>();
        if (path == null || path.isEmpty()) {
            return out;
        }
        Path p = Path.of(path);
        try {
            byte[] bytes = Files.readAllBytes(p);
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int count = b.remaining() >= 4 ? b.getInt() : 0;
            for (int i = 0; i < count && b.remaining() >= 5; i++) {
                String label = getStr16(b);
                String value = getStr16(b);
                boolean enabled = b.hasRemaining() && b.get() != 0;
                String group = getStr16(b);
                out.add(new SelectItemData(label, value, enabled, group));
            }
        } catch (IOException | RuntimeException e) {
            dbg("failed to read select items file: " + e);
        } finally {
            try { Files.deleteIfExists(p); } catch (IOException ignore) { }
        }
        return out;
    }

    /** Reads a little-endian u16-length-prefixed UTF-8 string from {@code b}, bounded. */
    private static String getStr16(ByteBuffer b) {
        if (b.remaining() < 2) {
            return "";
        }
        int len = b.getShort() & 0xFFFF;
        len = Math.min(len, b.remaining());
        if (len <= 0) {
            return "";
        }
        byte[] s = new byte[len];
        b.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }

    private void decodeNetworkRequest(EventSlot slot) {
        // [windowId:4][interceptId:4][resourceType:4][methodLen:2][method]
        // [urlLen:4][url][hdrBlobLen:4][hdrBlob]
        int interceptId = slot.readInt(4);
        int resourceType = slot.readInt(8);
        int off = 12;
        int methodLen = u16(slot, off);
        String method = clamped(slot, off + 2, methodLen);
        off += 2 + methodLen;
        String url = slot.readLenString(off);
        off += 4 + utf8Len(url);
        // hdrBlobLen prefix, then the [count:4]{pairs} blob.
        off += 4;
        String[][] hdrs = decodeHeaderPairs(slot, off);
        final String[] names = hdrs[0], values = hdrs[1];
        post(() -> client.onNetworkRequest(interceptId, resourceType, method, url, names, values));
    }

    private void decodeNetworkResponse(EventSlot slot) {
        // [windowId:4][interceptId:4][status:4][mimeLen:2][mime][contentLen:8]
        // [hdrBlobLen:4][hdrBlob][flags:4]
        int interceptId = slot.readInt(4);
        int status = slot.readInt(8);
        int off = 12;
        int mimeLen = u16(slot, off);
        String mime = clamped(slot, off + 2, mimeLen);
        off += 2 + mimeLen;
        long contentLength = (off + 8 <= slot.length) ? slot.readLong(off) : -1;
        off += 8;
        off += 4; // skip hdrBlobLen prefix
        String[][] hdrs = decodeHeaderPairs(slot, off);
        final String[] names = hdrs[0], values = hdrs[1];
        post(() -> client.onNetworkResponse(interceptId, status, mime, contentLength, names, values));
    }

    private void decodeBodyChunk(EventSlot slot) {
        // [windowId:4][interceptId:4][chunkSeq:4][offset:8][last:1][len:4][pathLen:4][path]
        int interceptId = slot.readInt(4);
        int chunkSeq = slot.readInt(8);
        long offset = slot.readLong(12);
        boolean last = slot.readByte(20) != 0;
        int len = slot.readInt(21);
        String path = slot.readLenString(25);
        byte[] bytes = readBodyChunkFile(path, len);
        post(() -> client.onNetworkBodyChunk(interceptId, chunkSeq, offset, last, bytes));
    }

    private static byte[] readBodyChunkFile(String path, int len) {
        if (path == null || path.isEmpty() || len <= 0) {
            return new byte[0];
        }
        Path p = Path.of(path);
        try {
            byte[] all = Files.readAllBytes(p);
            // Honor the engine-declared chunk length: deliver exactly `len` bytes.
            // The spill file's on-disk size must match `len`; if it disagrees
            // (truncated/partial write, slot reuse, future multi-chunk layout),
            // clamp so we never hand downstream body-edit accounting a buffer of
            // the wrong size or do an unbounded read off an engine-controlled path.
            if (all.length == len) {
                return all;
            }
            return Arrays.copyOf(all, Math.min(all.length, len));
        } catch (IOException | RuntimeException e) {
            return new byte[0];
        } finally {
            try { Files.deleteIfExists(p); } catch (IOException ignore) { }
        }
    }

    /**
     * Decodes a header blob {@code [count:4]{[nameLen:2][name][valLen:2][val]}}
     * starting at {@code off}, clamped to the slot (a header set larger than one
     * slot is delivered partially rather than over-read).
     */
    private static String[][] decodeHeaderPairs(EventSlot s, int off) {
        if (off < 0 || off + 4 > s.length) {
            return new String[][] { new String[0], new String[0] };
        }
        int count = s.readInt(off);
        off += 4;
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < count && off + 2 <= s.length; i++) {
            String name = str16(s, off);
            off += 2 + utf8Len(name);
            String val = str16(s, off);
            off += 2 + utf8Len(val);
            names.add(name);
            values.add(val);
        }
        return new String[][] { names.toArray(new String[0]), values.toArray(new String[0]) };
    }

    /** Reads a u8 at {@code off}, or {@code 0} if past the slot. */
    private static int u8(EventSlot s, int off) {
        return (off >= 0 && off < s.length) ? (s.readByte(off) & 0xFF) : 0;
    }

    /** Reads a little-endian u16 at {@code off}, or {@code 0} if it doesn't fit. */
    private static int u16(EventSlot s, int off) {
        return (off < 0 || off + 2 > s.length) ? 0
            : (s.readByte(off) & 0xFF) | ((s.readByte(off + 1) & 0xFF) << 8);
    }

    /** Reads {@code declaredLen} UTF-8 bytes at {@code off}, clamped to the slot. */
    private static String clamped(EventSlot s, int off, int declaredLen) {
        if (declaredLen <= 0 || off < 0 || off >= s.length) {
            return "";
        }
        int n = Math.min(declaredLen, s.length - off);
        return n <= 0 ? "" : s.readUtf8(off, n);
    }

    /** Reads a u16-length-prefixed UTF-8 string at {@code off}, clamped to the slot. */
    private static String str16(EventSlot s, int off) {
        return clamped(s, off + 2, u16(s, off));
    }

    private static int utf8Len(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Releases every outstanding executeScript / JSObject waiter with an error,
     * so they throw {@link JSException} immediately instead of blocking until
     * {@code JS_TIMEOUT_MS} when the renderer/engine dies or the page is
     * disposed (the "frozen GUI" symptom). Safe to call from any thread.
     */
    private void failAllPendingJs(String reason) {
        for (Integer id : new ArrayList<>(pendingJs.keySet())) {
            completeJs(id, null, reason);
        }
    }

    /**
     * Emit the one-and-only {@code onPageFinished} (→ LoadWorker SUCCEEDED) for
     * the current load. Pump-thread only.
     *
     * <p>Normal path ({@code force == false}): completes only when BOTH the page
     * has finished loading and the DOM mirror is ready, so SUCCEEDED implies a
     * usable {@code getDocument()}. Backstop path ({@code force == true}, from
     * DidStopLoading): completes on page-load alone, because the DOM_TREE_READY
     * Mojo reply can stall when the engine is idle and the load must never hang
     * in RUNNING.
     *
     * <p>{@link #loadTerminal} guarantees exactly one terminal transition per
     * navigation: a no-op if the load already reached SUCCEEDED here or FAILED
     * via {@code LOAD_ERROR}.
     */
    private void maybeFinishLoad(boolean force) {
        if (loadTerminal || !pageLoaded) {
            return;
        }
        if (!force && !domReady) {
            return;
        }
        loadTerminal = true;
        post(() -> client.onPageFinished(null));
    }

    private void completeJs(int requestId, Object result, String error) {
        Pending p = pendingJs.remove(requestId);
        if (p == null) {
            return; // stray/late result (already timed out, or window.print echo) — ignore
        }
        p.result = result;
        p.error = error;
        if (p.async != null) {
            post(() -> {
                if (error != null) {
                    p.async.onError(error);
                } else {
                    p.async.onResult(result);
                }
            });
        }
        p.latch.countDown(); // release a synchronous waiter (no FX dispatch)
    }

    private void post(Runnable r) {
        if (disposed) {
            return;
        }
        // Marshal to the FX thread. Tolerate a headless context (no toolkit /
        // no registered Invoker) so the bridge works in smoke tests — JS
        // results never use this path, only listener callbacks do.
        try {
            Invoker invoker = Invoker.getInvoker();
            if (invoker != null) {
                invoker.postOnEventThread(r);
            }
        } catch (Throwable ignore) {
            // no FX thread available; drop the listener callback
        }
    }

    private void onEngineDead() {
        // The watchdog saw the engine heartbeat go stale (hung/crashed). Fail any
        // in-flight JS waiters now so they throw rather than block for the full
        // JS_TIMEOUT_MS — the watchdog detects death well before that.
        failAllPendingJs("WebView engine not responding");
        post(() -> client.onEngineGone(-1));
    }

    // --- Teardown -------------------------------------------------------

    /** Idempotent. Stops threads, kills the engine, unmaps the channel. */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        // 1. Stop reading/writing the rings.
        pump.stop();
        heartbeat.stop();
        watchdog.stop();
        // 2. Release every in-flight JS waiter so none hangs. Synchronous callers
        //    block on the latch (countDown); asynchronous callers expect their
        //    JsCallback.onError. post() is gated on !disposed (already true here),
        //    so dispatch the async errors through the Invoker directly.
        Invoker inv = Invoker.getInvoker();
        for (Pending p : pendingJs.values()) {
            p.error = "page disposed";
            p.latch.countDown();
            if (p.async != null && inv != null) {
                inv.postOnEventThread(() -> p.async.onError("page disposed"));
            }
        }
        pendingJs.clear();
        // 2b. Drop the DOM mirror (cache/wrappers/listeners) so a stray Node
        //     reference held by the app can't pin the whole tree after disposal.
        dom.reset();
        // Drop the JS-object wrapper cache and the exposed Java-objects bridge so
        // a stray app reference can't pin them past disposal. (bugs.md H7)
        jsObjectCache.clear();
        clearJavaObjects();
        // 3. Ask the engine to destroy the window (best effort), then kill it.
        try {
            commands.writeWindowOnly(CommandType.DESTROY_WINDOW, windowId);
        } catch (RuntimeException ignore) {
            // channel may already be unusable; the process kill below is the backstop
        }
        engine.stop();
        // 4. Unmap + delete the shared-memory file — under the write lock so it
        //    waits for any in-flight render-thread frame draw to finish (which
        //    holds the read lock and reads the mapped segment). disposed=true is
        //    already set, so no new draw will start once this returns.
        frameLock.writeLock().lock();
        try {
            channel.close();
        } finally {
            frameLock.writeLock().unlock();
        }
        // (The OSR popup pixels live in the same data region as the main frame,
        // so the channel.close() above — under the frame write lock — already
        // covers them; no separate popup teardown is needed.)
        // 5. Delete the last staged loadContent temp file.
        Path lcf = lastContentFile;
        lastContentFile = null;
        if (lcf != null) {
            try { Files.deleteIfExists(lcf); } catch (IOException ignore) { }
        }
        // 6. Drop the render callback (WebPage::onBlinkFrame) so a stray reference
        //    to this disposed page can't transitively pin the WebPage. Safe: the
        //    pump is stopped and every decode re-checks disposed before firing it.
        //    (We do NOT null `client` — already-queued FX tasks read it at run time.)
        renderCallback = null;
    }
}
