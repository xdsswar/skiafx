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

import java.io.IOException;
import java.lang.ref.Cleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

/**
 * Spawns and supervises one {@code skia-fx-webview} engine process and waits
 * for it to reach {@code ENGINE_RUNNING}. Command line:
 * {@code <exe> <mmap-path> [switches...]}.
 *
 * <h2>Lifecycle</h2>
 * {@link #stop()} requests a graceful exit then force-kills after a grace
 * window. A {@link Cleaner} backstop force-kills the process if this manager is
 * dropped without {@code stop()} — so a leaked page never orphans a subprocess.
 * The cleanup action captures only the {@code Process} (never {@code this}).
 * Internal.
 */
final class EngineProcessManager {

    private static final Cleaner CLEANER = Cleaner.create();
    private static final long START_TIMEOUT_MS = 60_000;
    private static final long POLL_INTERVAL_NANOS = 50_000_000L; // 50 ms
    private static final long STOP_GRACE_MS = 1_000;

    // Every live engine process, so a single JVM shutdown hook can force-kill
    // them all. The engine is a background, windowless process — if the JVM
    // exits (normal quit, Platform.exit(), last window closed, or a crash that
    // still runs hooks) without each BlinkPage being disposed, the engine would
    // linger invisibly. The hook is the hard guarantee that it dies with us;
    // BlinkPage.dispose() / stop() remove their process from the set first so a
    // cleanly-torn-down page isn't double-killed. (The engine also self-
    // terminates on a stale Java heartbeat — see jux_heartbeat.cc — as a
    // backstop for a hard JVM crash that bypasses shutdown hooks.)
    private static final java.util.Set<Process> LIVE_PROCESSES =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Process p : LIVE_PROCESSES) {
                killTree(p);
            }
        }, "skia-fx-webview-shutdown"));
    }

    /** Force-kills a process and all of its descendants (renderer/GPU/utility). */
    private static void killTree(Process p) {
        if (p == null) {
            return;
        }
        try {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (Throwable ignore) {
            // descendant enumeration is best-effort
        }
        p.destroyForcibly();
    }

    private final Path exe;
    private final SharedMemoryChannel channel;
    private final List<String> switches;

    private Process process;
    private Cleaner.Cleanable cleanable;
    private volatile boolean alive;

    EngineProcessManager(Path exe, SharedMemoryChannel channel, List<String> switches) {
        this.exe = exe;
        this.channel = channel;
        this.switches = switches == null ? List.of() : List.copyOf(switches);
    }

    /** Spawns the engine and blocks until it reports {@code ENGINE_RUNNING}. */
    void start() throws IOException, TimeoutException, InterruptedException {
        if (!Files.exists(exe)) {
            throw new IOException("engine executable not found: " + exe);
        }
        channel.resetEngineState();

        boolean verbose = Boolean.getBoolean("skia.webview.engineVerbose");

        List<String> command = new ArrayList<>();
        command.add(exe.toString());
        command.add(channel.getPath().toString());
        // Pin the engine's display device-scale to 1.0 on EVERY monitor. The
        // page's render density is supplied entirely by the per-view capture
        // scale override (= the JavaFX render scale sent with SET_SIZE), so
        // the engine's own display scale must be a CONSTANT: the hidden
        // capture window follows the WebView across monitors, and with real
        // per-monitor DSFs its scale flips asynchronously mid-drag — the
        // override's divisor and the widget's DIP size both drift (DIP =
        // pixels / DSF), which rendered the page at the wrong density/size
        // and froze or shrank the WebView after a cross-DPI monitor move.
        // With the scale forced to 1.0, DIP == pixels everywhere, the first
        // SET_SIZE after a DPI change is exact, and the reaction is a single
        // renderer reflow — no convergence dance. Window-positioning code is
        // unaffected (it uses Win32 GetDpiForWindow, not display::Display).
        command.add("--force-device-scale-factor=1");
        // Off-screen rendering reads frames via CopyFromSurface, which can
        // only see what viz COMPOSITES. DirectComposition promotes fullscreen
        // video to an overlay/decode swap chain whose pixels bypass the
        // composited surface — measured as a ~4 s frame stall on entering
        // YouTube fullscreen (capture returned nothing until promotion fell
        // back). With DComp off, everything composites into the readable
        // surface and fullscreen-enter converges in ~300 ms. The engine
        // window is never presented on screen, so DComp's presentation
        // benefits don't apply here. -Dskia.webview.directComposition=true
        // re-enables it for A/B debugging.
        if (!Boolean.getBoolean("skia.webview.directComposition")) {
            command.add("--disable-direct-composition");
        }
        // Main-frame capture path. Default: viz FrameSinkVideoCapturer (push,
        // follows the page's surface across resizes/fullscreen — no frame gap
        // while heavy pages relayout). -Dskia.webview.pollCapture=true falls
        // back to the legacy per-tick CopyFromSurface polling.
        if (Boolean.getBoolean("skia.webview.pollCapture")) {
            command.add("--jux-poll-capture");
        }
        command.addAll(switches);
        Path chromiumLog = exe.toAbsolutePath().getParent().resolve("skia-fx-webview-chromium.log");
        if (verbose) {
            // --log-file makes EVERY process (browser + child renderer/gpu/utility)
            // append to one file with its PID, so child CHECK/FATAL messages are
            // captured (children don't reliably inherit a redirected stderr).
            command.add("--enable-logging");
            command.add("--log-file=" + chromiumLog);
            command.add("--v=1");
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        // Run from the engine's own directory so any relative resource lookups
        // (pak / icu / companion DLLs) and child-process re-exec resolve.
        pb.directory(exe.toAbsolutePath().getParent().toFile());
        pb.redirectErrorStream(true);
        if (verbose) {
            // The engine is a windowed (no-console) subsystem app, so INHERIT
            // shows nothing from a non-console launcher. Redirect to a log file.
            Path log = exe.toAbsolutePath().getParent().resolve("skia-fx-webview-engine.log");
            pb.redirectOutput(ProcessBuilder.Redirect.to(log.toFile()));
            System.err.println("[skia-fx-webview] engine log: " + log);
        } else {
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }

        Process p = pb.start();
        this.process = p;
        this.alive = true;
        LIVE_PROCESSES.add(p);
        this.cleanable = CLEANER.register(this, killAction(p));

        waitForRunning(p);
    }

    private void waitForRunning(Process p) throws TimeoutException, InterruptedException {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive()) {
                alive = false;
                throw new TimeoutException(
                    "engine exited during startup with code " + p.exitValue());
            }
            if (channel.readEngineState() == MemoryLayout.ENGINE_RUNNING) {
                return;
            }
            LockSupport.parkNanos(POLL_INTERVAL_NANOS);
            if (Thread.interrupted()) {
                throw new InterruptedException("interrupted waiting for engine startup");
            }
        }
        alive = false;
        throw new TimeoutException("engine did not reach RUNNING within " + START_TIMEOUT_MS + " ms");
    }

    boolean isAlive() {
        Process p = process;
        return alive && p != null && p.isAlive();
    }

    /** Requests graceful exit, then force-kills after a grace window. Idempotent. */
    void stop() {
        alive = false;
        Process p = process;
        if (p == null) {
            return;
        }
        // No longer needs the shutdown-hook backstop — we're killing it now.
        LIVE_PROCESSES.remove(p);
        if (p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    killTree(p);
                }
            } catch (InterruptedException e) {
                killTree(p);
                Thread.currentThread().interrupt();
            }
        }
        if (cleanable != null) {
            // Detach the backstop so it doesn't re-kill (already dead).
            cleanable.clean();
            cleanable = null;
        }
        process = null;
    }

    /** Static — captures only the Process, never the manager. */
    private static Runnable killAction(Process p) {
        return () -> {
            LIVE_PROCESSES.remove(p);
            if (p.isAlive()) {
                killTree(p);
            }
        };
    }
}
