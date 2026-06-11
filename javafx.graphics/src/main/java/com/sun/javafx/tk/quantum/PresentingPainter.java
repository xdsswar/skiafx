/*
 * Copyright (c) 2011, 2021, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.javafx.tk.quantum;

import java.lang.foreign.MemorySegment;

import com.sun.javafx.logging.PulseLogger;
import static com.sun.javafx.logging.PulseLogger.PULSE_LOGGING_ENABLED;
import com.sun.javafx.sg.prism.NGNode;
import com.sun.prism.Graphics;
import com.sun.prism.GraphicsPipeline;
import com.sun.prism.impl.Disposer;
import com.sun.prism.skia.SkiaPresentable;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.prism.skia.impl.NativeHandles;
import com.sun.prism.skia.impl.PaintStats;
import com.sun.prism.skia.impl.SkiaGpu;

/**
 * Painter that drives the Skia pipeline's per-pulse render and
 * present.
 *
 * <p>On top of the upstream behavior (paint into the presentable,
 * prepare, present), this painter layers two Skia-specific
 * optimizations:</p>
 *
 * <ol>
 *   <li><b>Change detection</b> — {@link NGNode#SCENE_DIRTY_VERSION}
 *       bumps on every dirty mark. When that version hasn't moved
 *       since the last frame we actually presented, the scene is
 *       visually unchanged and we skip both paint AND present this
 *       pulse. AnimationTimers still fire (the master timer keeps
 *       running), so app code that polls timestamps is unaffected.</li>
 *   <li><b>Present-rate cap (multi-monitor aware)</b> — pulses fire
 *       at whatever rate JFX is configured for, but actual
 *       SwapBuffers calls are throttled to at most the window's
 *       monitor refresh. Presenting faster overruns DWM's flip
 *       queue and produces visible "current ↔ previous frame"
 *       oscillation as DWM picks frames non-deterministically.
 *       The monitor refresh is queried per pulse from the native
 *       bridge, so dragging a window onto a different-refresh
 *       monitor retargets the cap on the very next pulse.</li>
 * </ol>
 *
 * <p>{@code UploadingPainter} is used when we need to render into
 * an offscreen buffer instead of the main screen.</p>
 */
final class PresentingPainter extends ViewPainter {

    /** {@link NGNode#SCENE_DIRTY_VERSION} value at the last frame we
     *  successfully presented. When the version hasn't moved since,
     *  the scene is visually unchanged and we skip both paint and
     *  present. Reset to {@code -1} on presentable rebuild so the
     *  next pulse always paints + presents at least once. */
    private long lastPresentedDirtyVersion = -1L;

    /** Surface (output device) size the presentable had last pulse. A presentable
     *  can resize its swapchain IN PLACE inside {@code lockResources} (D3D
     *  ResizeBuffers on a drag-resize, or a monitor-move scale change) and return
     *  {@code false} to keep itself; the resized back buffers are then undefined
     *  until repainted. Without forcing a fresh full paint, a STATIC scene whose
     *  dirty flag was already consumed would skip the paint and present the blank
     *  resized buffer — a one-frame blank flash on monitor move. We detect the
     *  size change here and force {@code freshBackBuffer}. Seeded to -1 so the
     *  first pulse after a (re)build doesn't spuriously trip. */
    private int lastContentWidth = -1;
    private int lastContentHeight = -1;

    // ---- Present-rate cap (multi-monitor aware) --------------------------
    //
    // Pulses fire as fast as JFX can drive them (configurable via
    // -Djavafx.animation.pulse=N), but actual SwapBuffers calls are
    // throttled to at most the *monitor's* refresh rate of the window
    // this painter is rendering for. Above the display refresh, swaps
    // get dropped/queued by DWM → visible tearing / "current ↔
    // previous frame" oscillation. Below it, GPU work piles up
    // wastefully.
    //
    // Behavior:
    //  - default: cap = current monitor's refresh, queried via
    //    NativeBridge.windowGetRefreshHz(hwnd) per pulse (cheap on
    //    Windows: MonitorFromWindow + EnumDisplaySettings). A window
    //    dragged from a 60-Hz secondary onto a 144-Hz primary will
    //    pick up 144 Hz on the very next pulse — no special event
    //    needed.
    //  - if the native query returns 0 (non-Windows for now, or the
    //    driver hides the rate), fall back to PRESENT_MAX_HZ_FALLBACK.
    //  - -Dskia.present.maxHz=N forces a hard global cap (overrides
    //    per-monitor); useful for benchmarking. 0 = uncapped.
    //
    // The query is so cheap (a single Win32 round-trip) we do it every
    // pulse rather than caching. This way moving a window between
    // monitors needs zero special handling.
    private static final int PRESENT_MAX_HZ_OVERRIDE =
        Integer.getInteger("skia.present.maxHz", -1);
    private static final int PRESENT_MAX_HZ_FALLBACK = 144;
    private long lastPresentTimeNs = 0L;

    /** Last refresh-rate value pulled from the native query, for
     *  diagnostic / change-detection logging. 0 until first pulse. */
    private int lastObservedRefreshHz = 0;

    /**
     * Resolves the minimum gap between two presents for this pulse.
     * Reads {@code -Dskia.present.maxHz} first (force-override path),
     * otherwise asks the native bridge for this window's current
     * monitor refresh, otherwise falls back to a safe default.
     */
    private long minPresentIntervalNs() {
        // 1) Explicit override wins, even = 0 (uncapped).
        if (PRESENT_MAX_HZ_OVERRIDE >= 0) {
            return PRESENT_MAX_HZ_OVERRIDE == 0
                ? 0L
                : 1_000_000_000L / PRESENT_MAX_HZ_OVERRIDE;
        }
        // 2) Per-window monitor refresh via native query.
        int hz = 0;
        try {
            long hwnd = sceneState.getNativeWindow();
            if (hwnd != 0L) {
                hz = NativeBridge.windowGetRefreshHz(MemorySegment.ofAddress(hwnd));
            }
        } catch (Throwable t) {
            // Bridge missing the symbol on some build / ABI weirdness:
            // fall through to fallback. One-shot warn would spam logs
            // when called per-pulse, so stay silent.
        }
        if (hz > 0) {
            if (hz != lastObservedRefreshHz) {
                int prev = lastObservedRefreshHz;
                lastObservedRefreshHz = hz;
                // Refresh-rate detection / change. Silent by default; enable
                // with -Dskia.present.diag=true to confirm multi-monitor.
                if (PRESENT_DIAG) {
                    if (prev == 0) {
                        System.err.printf("[skia.present] cap %d Hz "
                            + "(detected from window's monitor)%n", hz);
                    } else {
                        System.err.printf("[skia.present] cap %d → %d Hz "
                            + "(window moved to different-refresh monitor)%n",
                            prev, hz);
                    }
                }
            }
            return 1_000_000_000L / hz;
        }
        // 3) Native query said "unknown" — pick a reasonable default
        //    that won't over-queue on the common 60/100/120/144 panels.
        return 1_000_000_000L / PRESENT_MAX_HZ_FALLBACK;
    }

    PresentingPainter(ViewScene view) {
        super(view);
    }

    // ---- Paint-rate diagnostic --------------------------------------------
    // Diagnostic flags hoisted to constants: Boolean.getBoolean is a
    // synchronized Properties lookup and these were evaluated up to four
    // times per pulse on the hot path.
    private static final boolean PAINT_DIAG   = Boolean.getBoolean("skia.paint.diag");
    private static final boolean RESIZE_DIAG  = Boolean.getBoolean("skia.resize.diag");
    private static final boolean PRESENT_DIAG = Boolean.getBoolean("skia.present.diag");

    // Always-on paint accounting (two nanoTime reads + a few adds per
    // paint — noise next to the paint itself). Publishes paints/sec and
    // avg paint ms to PaintStats once a second for benchmark harnesses;
    // -Dskia.paint.diag=true additionally prints the same numbers to
    // stderr. Lets us tell at a glance whether the painter is running
    // cheaply (change detection + dirty regions effective) or doing full
    // scene work each frame.
    private long diagPaintCount = 0;
    private long diagPaintTimeNs = 0;
    private long diagWindowStart = 0;
    private void countPaint(long durationNs) {
        long now = System.nanoTime();
        if (diagWindowStart == 0) diagWindowStart = now;
        diagPaintCount++;
        diagPaintTimeNs += durationNs;
        long elapsed = now - diagWindowStart;
        if (elapsed >= 1_000_000_000L) {
            double paintsPerSec = diagPaintCount * 1_000_000_000.0 / elapsed;
            double avgMs = diagPaintTimeNs / (double) diagPaintCount / 1_000_000.0;
            PaintStats.LAST_PAINTS_PER_SEC = paintsPerSec;
            PaintStats.LAST_PAINT_AVG_MS   = avgMs;
            if (PAINT_DIAG) {
                System.err.printf("[skia.paint] %.0f paints/sec  avg=%.2f ms%n",
                    paintsPerSec, avgMs);
            }
            diagPaintCount = 0;
            diagPaintTimeNs = 0;
            diagWindowStart = now;
        }
    }

    /**
     * Resets BOTH change-detection cursors so the next pulse paints
     * AND presents unconditionally. Called whenever the presentable
     * is rebuilt (size change, lost-device recovery) — a fresh
     * back-buffer needs at least one full paint AND a present, no
     * matter how recently we presented through the previous
     * (now-disposed) presentable.
     *
     * <p>Resetting {@link #lastPresentTimeNs} is required to keep
     * the present-rate cap from blocking the very first post-rebuild
     * present — the rebuild has the latest paint sitting in the new
     * back buffer, and the user must see it.</p>
     */
    private void resetChangeDetection() {
        lastPresentedDirtyVersion = -1L;
        lastPresentTimeNs         = 0L;
    }

    @Override public void run() {
        renderLock.lock();

        boolean locked = false;
        boolean valid = false;
        boolean errored = false;

        try {
            valid = validateStageGraphics();
            if (!valid) {
                if (QuantumToolkit.verbose) {
                    System.err.println("PresentingPainter: validateStageGraphics failed");
                }
                if (RESIZE_DIAG) {
                    System.err.println("[skia.present] VALIDATE FAILED -> blank  view="
                        + viewWidth + "x" + viewHeight);
                }
                paintImpl(null);
                return;
            }

            /*
             * As Glass is responsible for creating the rendering contexts,
             * locking should be done prior to the Prism calls.
             */
            sceneState.lock();
            locked = true;

            if (factory == null) {
                factory = GraphicsPipeline.getDefaultResourceFactory();
            }
            if (factory == null || !factory.isDeviceReady()) {
                sceneState.getScene().entireSceneNeedsRepaint();
                factory = null;
                // Dispose the presentable on the device-not-ready path too, like
                // every other failure path (prepare/present/exception all call
                // disposePresentable). Otherwise a presentable built against the
                // lost device lingers in the field and is reused next pulse if the
                // size is unchanged. Idempotent (no-op when presentable == null).
                // (bugs.md M8)
                disposePresentable();
                return;
            }

            boolean rebuilt = false;
            if (presentable != null && presentable.lockResources(sceneState)) {
                disposePresentable();
                resetChangeDetection();
            }
            if (presentable == null) {
                presentable = factory.createPresentable(sceneState);
                penWidth  = viewWidth;
                penHeight = viewHeight;
                freshBackBuffer = true;
                resetChangeDetection();
                rebuilt = true;
            }

            if (presentable != null) {
                // lockResources may have resized the surface IN PLACE (kept the
                // presentable, returned false). The resized back buffers are
                // undefined until painted, so force a full paint when the output
                // size changed and we did NOT just rebuild (rebuild already sets
                // freshBackBuffer). Prevents a one-frame blank flash on monitor
                // move / drag-resize for a static (non-animating) scene whose
                // dirty flag was already consumed. See lastContentWidth javadoc.
                int cw = presentable.getContentWidth();
                int ch = presentable.getContentHeight();
                if (!rebuilt && lastContentWidth != -1
                        && (cw != lastContentWidth || ch != lastContentHeight)) {
                    freshBackBuffer = true;
                }
                lastContentWidth = cw;
                lastContentHeight = ch;

                ViewScene vs = (ViewScene) sceneState.getScene();

                // Change detection: skip paint + present entirely when
                // the scene's dirty version hasn't moved since the last
                // present (see class Javadoc, item 1). freshBackBuffer
                // forces at least one paint after presentable rebuild.
                // entireSceneNeedsRepaint() routes through markTreeDirty
                // and bumps the dirty version, so forced-repaint paths
                // still work as expected.
                // A forced full repaint must never be skipped or deferred:
                // entireSceneNeedsRepaint() (fired on show / view-resize) sets the
                // scene's entire-dirty flag but does NOT bump the global
                // SCENE_DIRTY_VERSION. Without this, a freshly-shown window whose
                // first paint landed while the Glass view was still 0x0 (so paintImpl
                // bailed to blank) is then resized, but change-detection sees an
                // unchanged version and skips the corrective repaint — the window
                // stays blank forever (e.g. an Alert / modal dialog).
                // forcedRepaint = a *genuine* must-show-now full repaint (window
                // shown, view resized, entireSceneNeedsRepaint). freshBackBuffer
                // is the routine flip-chain "back buffer is undefined after the
                // last present" signal — it forces a full *paint* but must NOT
                // exempt the present from the rate cap, otherwise the cap is dead
                // (freshBackBuffer is set true after every present, so without
                // this split fullRepaint is always true at the cap below).
                boolean forcedRepaint = vs.isEntireSceneDirty();
                boolean fullRepaint = freshBackBuffer || forcedRepaint;
                long currentDirtyVersion = NGNode.SCENE_DIRTY_VERSION.get();
                boolean canSkip = !fullRepaint
                    && currentDirtyVersion == lastPresentedDirtyVersion;

                if (RESIZE_DIAG) {
                    System.err.println("[skia.present] view=" + viewWidth + "x" + viewHeight
                        + " scene=" + vs.getClass().getSimpleName()
                        + " fresh=" + freshBackBuffer + " entireDirty=" + vs.isEntireSceneDirty()
                        + " canSkip=" + canSkip + " ver=" + currentDirtyVersion
                        + "/" + lastPresentedDirtyVersion + " doPresent=" + vs.getDoPresent());
                }

                if (canSkip) {
                    // Nothing visual changed — display already shows the
                    // right frame. Return without paint or present;
                    // AnimationTimers continue running on the master
                    // timer regardless.
                    return;
                }

                Graphics g = presentable.createGraphics();
                if (g != null) {
                    long paintStart = System.nanoTime();
                    paintImpl(g);
                    countPaint(System.nanoTime() - paintStart);
                    freshBackBuffer = false;
                } else if (RESIZE_DIAG) {
                    System.err.println("[skia.present] createGraphics NULL -> no paint  view="
                        + viewWidth + "x" + viewHeight);
                }

                if (PULSE_LOGGING_ENABLED) {
                    PulseLogger.newPhase("Presenting");
                }
                // Present-rate cap (see class Javadoc, item 2). The
                // paint just above already updated the offscreen FBO,
                // so the latest content survives a deferred present —
                // the next allowed pulse will pick it up cleanly.
                // Gate on forcedRepaint (not fullRepaint): a genuine
                // must-show-now repaint is never deferred, but the routine
                // post-present freshBackBuffer repaint IS subject to the cap.
                // The first frame after a presentable rebuild is already
                // exempt because resetChangeDetection() zeros lastPresentTimeNs.
                long minIntervalNs = minPresentIntervalNs();
                long nowNs = System.nanoTime();
                if (!forcedRepaint
                    && minIntervalNs > 0
                    && lastPresentTimeNs != 0
                    && nowNs - lastPresentTimeNs < minIntervalNs) {
                    // Present deferred by the rate cap. paintImpl just cleared
                    // freshBackBuffer (line above), so the NEXT pulse would do a
                    // PARTIAL (dirty-region) repaint. On a flip-style swap chain
                    // that re-renders any effect node CLIPPED to the dirty rect —
                    // blanking the part of the effect (blur/shadow halo, or even
                    // the whole "Fx") that lies outside the dirty region, until a
                    // full repaint restores it. (Confirmed via the CLIP-SHRANK
                    // diagnostic: a 213px-wide effect node clipped to a 2px
                    // offscreen.) Re-arm a full repaint so the next paint
                    // re-renders the whole scene — effects intact — before it is
                    // actually presented.
                    freshBackBuffer = true;
                    // Schedule the follow-up pulse explicitly. Without this, if the
                    // scene goes idle right after this deferral (no animation, no
                    // dirty event) postPulse pauses the master timer and the frame
                    // we just painted is NEVER presented until some unrelated dirty
                    // event — a visibly stale final frame on quiescence. A
                    // requestNextPulse keeps pulsing until enough time has elapsed
                    // for the rate cap to let the present through (a few pulses at
                    // most, bounded by minPresentIntervalNs). (bugs.md H4)
                    QuantumToolkit.getToolkit().requestNextPulse();
                    return;
                }

                // Hand the painted union (device px; null = whole surface)
                // to the presentable so the readback tier can limit its
                // per-frame copy + OS blit to the area that changed.
                if (!presentable.prepare(getPaintedRegion())) {
                    disposePresentable();
                    sceneState.getScene().entireSceneNeedsRepaint();
                    return;
                }

                // Paint-before-show: the window is still hidden, waiting for its
                // first frame before the native ShowWindow. Prime its DWM
                // redirection bitmap with the frame just rendered so the OS show
                // animation reveals real UI — a flip-model swap-chain Present
                // alone does not reach that bitmap (why CUSTOM windows used to
                // flash). One-shot; degrades silently if the native prime fails.
                GlassStage preShowStage = sceneState.getScene().getStage();
                if (preShowStage instanceof WindowStage pws && pws.isPaintBeforeShow()
                        && presentable instanceof SkiaPresentable sp) {
                    sp.primeWindowForShow();
                }

                /* present for vsync buffer swap */
                boolean presented = true; // doPresent=false counts as "nothing to fail"
                if (vs.getDoPresent()) {
                    presented = presentable.present();
                    if (!presented) {
                        disposePresentable();
                        sceneState.getScene().entireSceneNeedsRepaint();
                    }
                }
                // Only record success — recording on a failed present
                // would canSkip the next pulse (display freezes on a
                // stale frame) and stick a stale lastPresentTimeNs into
                // the rate cap (blocks the rebuild's first frame).
                if (presented) {
                    lastPresentedDirtyVersion = currentDirtyVersion;
                    lastPresentTimeNs         = nowNs;
                    // skia-fx: the GPU present paths are flip-style swap
                    // chains — wglSwapBuffers and DXGI flip-model
                    // ALLOW_TEARING both leave the new back buffer's
                    // contents undefined after present. ViewPainter's
                    // dirty-region path otherwise only repaints the
                    // changed rectangle (e.g. a blinking caret) into
                    // that undefined buffer, so the unchanged regions
                    // show garbage on every swap — visible as a
                    // whole-window flash synchronized with whatever
                    // node is dirtying. Forcing the next paint to
                    // renderEverything keeps the entire scene valid
                    // each frame, which is what flip-style swap
                    // chains require.
                    //
                    // The software-raster (CPU) tier is NOT a flip chain:
                    // its SkSurface is a persistent CPU buffer and present()
                    // reads the WHOLE surface back every frame, so unchanged
                    // pixels stay valid and the existing dirty-region path
                    // can repaint just the changed nodes. Re-arming a full
                    // repaint there is pure waste — it forces all nodes to
                    // re-rasterize on the CPU every frame (the dominant cost
                    // of the software path). Skip the re-arm only on the
                    // software path; every GPU tier is unchanged.
                    if (!SkiaGpu.isResolvedSoftware()) {
                        freshBackBuffer = true;
                    }

                    // skia-fx: paint-before-show signal. The FX
                    // thread inside WindowStage.setVisible is
                    // waiting on a CountDownLatch for the first
                    // successful present so it can call native
                    // ShowWindow with the swap chain already
                    // populated. See WindowStage.paintBeforeShow.
                    GlassStage stage = sceneState.getScene().getStage();
                    if (stage instanceof WindowStage ws
                            && ws.isPaintBeforeShow()) {
                        ws.notifyFirstPresented();
                    }
                }
            }
        } catch (Throwable th) {
            errored = true;
            th.printStackTrace(System.err);
            // A throw from paint/present (e.g. device loss surfacing as an
            // exception rather than a false return) must rebuild the presentable
            // next pulse — otherwise the corrupt swap chain is reused and the
            // window wedges in repeated failure. Mirror the prepare()/present()
            // false-return recovery (dispose + full-scene repaint).
            disposePresentable();
            try {
                sceneState.getScene().entireSceneNeedsRepaint();
            } catch (Throwable ignored) {
                // best-effort: never let recovery itself escape the render loop
            }
        } finally {
            Disposer.cleanUp();
            // Free any GPU handles the Cleaner deferred off its daemon thread
            // (BUG-4): Skia/GL/D3D destroys are render-thread-confined, and we
            // are on the render thread here. No-op when nothing leaked.
            NativeHandles.drainDeferred();

            if (locked) {
                sceneState.unlock();
            }

            ViewScene viewScene = (ViewScene)sceneState.getScene();
            viewScene.setPainting(false);

            if (factory != null) {
                factory.getTextureResourcePool().freeDisposalRequestedAndCheckResources(errored);
            }

            renderLock.unlock();
        }
    }
}
