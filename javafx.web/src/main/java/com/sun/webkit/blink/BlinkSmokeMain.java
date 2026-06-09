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

import java.util.List;

/**
 * Headless smoke test for the Blink IPC bridge — proves the whole native path
 * end-to-end without the scene graph: spawn the engine, create a (hidden)
 * WebContents, load a page, observe events, and round-trip {@code executeScript}.
 *
 * <p>Run via {@code ./gradlew :javafx.web:runBlinkSmoke -PbuildWebNative=true},
 * which sets {@code -Dskia.webview.engineDir} to the built engine output dir.
 * Not part of the public API; exists only for bring-up verification.
 */
public final class BlinkSmokeMain {

    private BlinkSmokeMain() { }

    private static String timed(String script, BlinkPage page) {
        long t = System.nanoTime();
        Object r = page.executeScript(script);
        long ms = (System.nanoTime() - t) / 1_000_000;
        System.out.println("  executeScript(" + script + ") -> " + r + "  [" + ms + " ms]");
        return r == null ? null : r.toString();
    }

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0]
            : "data:text/html,<html><head><title>SkiaFXBlink</title></head>"
                + "<body><h1>ok</h1><script>console.log('hello from blink');</script></body></html>";

        BlinkPage.Client client = new BlinkPage.Client() {
            @Override public void onPageStarted(String u) { System.out.println("[evt] started " + u); }
            @Override public void onPageFinished(String u) { System.out.println("[evt] finished " + u); }
            @Override public void onTitleChanged(String t) { System.out.println("[evt] title=" + t); }
            @Override public void onNavigation(String u) { System.out.println("[evt] navigation " + u); }
            @Override public void onHistoryChanged(int idx, String[] urls, String[] titles) {
                System.out.println("[evt] history idx=" + idx + " count=" + urls.length);
            }
            @Override public void onSessionState(byte[] blob) {
                System.out.println("[evt] sessionState " + blob.length + " bytes");
            }
            @Override public void onLoadError(int c, String u, String d) {
                System.out.println("[evt] loadError code=" + c + " url=" + u + " desc=" + d);
            }
            @Override public void onConsoleMessage(int level, int line, String msg, String src) {
                System.out.println("[console:" + level + "] " + msg + " (" + src + ":" + line + ")");
            }
            @Override public void onEngineGone(int status) { System.out.println("[evt] engineGone " + status); }
            @Override public void onCursorChanged(int type) { System.out.println("[evt] cursor=" + type); }
            @Override public void onDomTreeReady() { System.out.println("[evt] domTreeReady"); }
            @Override public void onDialogRequested(int id, int t, String m, String d) {
                System.out.println("[evt] dialog type=" + t + " msg=" + m);
            }
            @Override public void onSavePdfRequested(int requestId, String defaultName) {
                System.out.println("[evt] savePdf name=" + defaultName);
            }
            @Override public void onColorChooser(int id, int rgba, int[] sugg) {
                System.out.println("[evt] colorChooser rgba=" + Integer.toHexString(rgba));
            }
            @Override public void onFileChooserRequested(int id, int mode, String title,
                    String initialName, String acceptCsv) {
                System.out.println("[evt] fileChooser mode=" + mode + " accept=" + acceptCsv);
            }
            @Override public void onSelectPopup(int id, boolean multi, int sel,
                    double ax, double ay, double aw, double ah,
                    List<BlinkPage.SelectItemData> items) {
                System.out.println("[evt] selectPopup items=" + items.size());
            }
            @Override public void onPermissionRequested(int id, int t, String origin) {
                System.out.println("[evt] permission type=" + t + " origin=" + origin);
            }
            @Override public void onAuthRequested(int id, int s, boolean proxy, String h, String r) {
                System.out.println("[evt] auth host=" + h + " realm=" + r);
            }
            @Override public void onDownloadRequested(int id, long total, String u, String n, String m) {
                System.out.println("[evt] download url=" + u + " name=" + n);
            }
            @Override public void onDownloadProgress(int id, int st, long rcv, long tot) { }
            @Override public void onDownloadFinished(int id, int st, String path) {
                System.out.println("[evt] downloadFinished path=" + path);
            }
            @Override public void onContextMenu(int menuId, double x, double y, int flags,
                    String link, String src, String sel) {
                System.out.println("[evt] contextMenu id=" + menuId + " x=" + x + " y=" + y + " link=" + link);
            }
            @Override public void onFullscreenRequested(int id, boolean entering) {
                System.out.println("[evt] fullscreen entering=" + entering);
            }
            @Override public void onFaviconChanged(String url) {
                System.out.println("[evt] favicon=" + url);
            }
            @Override public void onTooltipChanged(String text) {
                System.out.println("[evt] tooltip=" + text);
            }
            @Override public void onNetworkRequest(int id, int rt, String m, String url,
                    String[] hn, String[] hv) {
                System.out.println("[evt] netRequest " + m + " " + url);
            }
            @Override public void onNetworkResponse(int id, int status, String mime,
                    long len, String[] hn, String[] hv) {
                System.out.println("[evt] netResponse " + status + " " + mime);
            }
            @Override public void onNetworkComplete(int id, int netError) {
                System.out.println("[evt] netComplete err=" + netError);
            }
            @Override public void onNetworkBodyChunk(int id, int chunkSeq, long offset,
                    boolean last, byte[] bytes) {
                System.out.println("[evt] netBodyChunk len="
                        + (bytes == null ? 0 : bytes.length) + " last=" + last);
            }
        };

        System.out.println("== creating BlinkPage (spawning skia-fx-webview engine) ==");
        long t0 = System.nanoTime();
        BlinkPage page = BlinkPage.create(client);
        System.out.printf("engine ready in %d ms%n", (System.nanoTime() - t0) / 1_000_000);

        boolean ok = false;
        try {
            // Give CREATE_WINDOW time to build the WebContents before scripting.
            Thread.sleep(1000);
            System.out.println("== loading: " + url + " ==");
            page.open(url);

            // Poll document.readyState until the page finishes (bounded).
            String ready = null;
            for (int i = 0; i < 40; i++) {
                Object rv = page.executeScript("document.readyState");
                ready = rv == null ? null : rv.toString();
                System.out.println("readyState=" + ready);
                if (ready != null && ready.contains("complete")) {
                    break;
                }
                Thread.sleep(250);
            }

            // Optional richer probe (app-state inspection) via -Dwebsmoke.probe=<js>.
            // Poll FAST from the start so we catch the WebUI app's state before the
            // (separate) JS-bridge reply channel goes quiet post-boot.
            String probe = System.getProperty("websmoke.probe");
            if (probe != null && !probe.isEmpty()) {
                for (int i = 0; i < 25; i++) {
                    Object pr = null;
                    try { pr = page.executeScript(probe); }
                    catch (Throwable t) { pr = "EXC:" + t.getMessage(); }
                    System.out.println("PROBE[" + i + "] = " + pr);
                    Thread.sleep(300);
                }
            }

            // DEBUG: dump the rendered OSR frame to ground-truth what's on screen.
            String dumpPath = System.getProperty("websmoke.dumpFrame");
            if (dumpPath != null && !dumpPath.isEmpty()) {
                Thread.sleep(1500);
                System.out.println("FRAME_DUMP " + dumpPath + " -> " + page.dumpLatestFrame(dumpPath));
            }

            String title = timed("document.title", page);
            String math = timed("1+1", page);
            String href = timed("location.href", page);
            System.out.println("== results ==");
            System.out.println("  document.title = " + title);
            System.out.println("  1+1            = " + math);
            System.out.println("  location.href  = " + href);

            ok = math != null && math.contains("2")
                && title != null && title.contains("SkiaFXBlink");
            System.out.println(ok ? "SMOKE PASS" : "SMOKE FAIL");
        } finally {
            System.out.println("== disposing ==");
            page.dispose();
            System.out.println("engine alive after dispose? " + page.isAlive());
        }
        System.exit(ok ? 0 : 1);
    }
}
