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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Reader view over the channel's double-buffered off-screen frame region.
 *
 * <p>The engine copies each captured page frame (BGRA8888, premultiplied)
 * into one of {@link MemoryLayout#FRAME_BUFFER_COUNT} viewport-sized slots in
 * the channel's data region and publishes the slot index + dimensions via the
 * {@code FRAME_READY} event. This class turns that notification into a
 * {@link Frame} pointing at the slot's pixels, which the WebView node draws
 * into the JavaFX scene surface via the native helper.
 *
 * <h2>Threading</h2>
 * {@link #publish} is called only on the {@link EventPump} thread (single
 * writer); {@link #latest} is read on the render thread. The published
 * {@link Frame} is swapped through a single {@code volatile} reference, so the
 * render thread always sees a fully-formed, self-consistent descriptor (no
 * field tearing). Double-buffering gives the reader a ~1-frame window to copy
 * the pixels before the engine reuses that slot.
 *
 * <p>Internal; never exported from {@code javafx.web}.
 */
final class FrameSurface {

    /**
     * Immutable descriptor of the most recently published main frame.
     * {@code logicalW/logicalH} are the DIP size this frame represents (the
     * WebView's logical size at capture time); the device pixels
     * ({@code width × height}, possibly slot-downscaled) are stretched into it.
     * Drawing to the frame's own logical size — rather than the node's current
     * size — keeps a frame captured just before an in-flight resize from being
     * distorted by a stretch to the new bounds.
     */
    record Frame(long address, int width, int height, int stride,
                 double logicalW, double logicalH) { }

    /**
     * Immutable descriptor of the most recently published OSR popup frame.
     * {@code x/y/logicalW/logicalH} are the popup's rect in the WebView node's
     * local (logical) coordinate space; the device bitmap is stretched into it.
     */
    record PopupFrame(long address, int width, int height, int stride,
                      double x, double y, double logicalW, double logicalH) { }

    /**
     * Immutable descriptor of the most recently published print-preview modal
     * frame (its own region, separate from the popup so the preview's dropdowns
     * can use the popup region). {@code logicalW/logicalH} are the preview's DIP
     * size; Java centers the device bitmap over the page.
     */
    record PreviewFrame(long address, int width, int height, int stride,
                        double logicalW, double logicalH) { }

    private final MemorySegment data;   // whole data region (native, mapped)
    private final long baseAddress;     // native start of the data region
    private final long slotBytes;       // bytes per main double-buffer slot
    private final long previewRegionOffset; // start of the preview slots (after main)
    private final long popupRegionOffset;   // start of the popup slots (after preview)

    private volatile Frame latest;
    private volatile PopupFrame popup;
    private volatile PreviewFrame previewFrame;

    FrameSurface(MemorySegment dataRegion) {
        this.data = dataRegion;
        this.baseAddress = dataRegion.address();
        // The data region is [main slots][preview slots][popup slots]; the main
        // slot size must exclude BOTH the preview and popup regions (carved off
        // the end). Mirrors the engine's
        // (data.size() - kPopupRegionBytes - kPreviewRegionBytes) / kFrameBufferCount.
        long overlays = MemoryLayout.PREVIEW_REGION_BYTES
            + MemoryLayout.POPUP_REGION_BYTES;
        long mainRegion = dataRegion.byteSize() - overlays;
        this.slotBytes = mainRegion / MemoryLayout.FRAME_BUFFER_COUNT;
        this.previewRegionOffset = mainRegion;                       // preview after main
        this.popupRegionOffset = mainRegion
            + MemoryLayout.PREVIEW_REGION_BYTES;                     // popup at the very end
    }

    /**
     * Records a newly-captured frame. Validates the engine-supplied geometry
     * against the slot capacity before exposing it, so a corrupt event can
     * never point the native draw helper outside the mapped region.
     *
     * @param logicalW the DIP width this frame represents (node logical width at
     *                 capture); the device pixels are stretched to it on draw
     * @param logicalH the DIP height this frame represents
     * @return {@code true} if the frame was accepted and published
     */
    boolean publish(int bufIndex, int width, int height, int stride,
                    double logicalW, double logicalH) {
        if (bufIndex < 0 || bufIndex >= MemoryLayout.FRAME_BUFFER_COUNT) {
            warnRejected("bad slot index", bufIndex, width, height, stride);
            return false;
        }
        if (width <= 0 || height <= 0 || stride < (long) width * 4) {
            warnRejected("invalid geometry", bufIndex, width, height, stride);
            return false;
        }
        long need = (long) stride * height;
        if (need <= 0 || need > slotBytes) {
            warnRejected("exceeds slot capacity", bufIndex, width, height, stride);
            return false;
        }
        long address = baseAddress + (long) bufIndex * slotBytes;
        latest = new Frame(address, width, height, stride, logicalW, logicalH);
        return true;
    }

    // A rejected frame is silently invisible to the user (the node keeps
    // showing the previous frame), which made the stale-DPI-override freeze
    // hard to diagnose. Surface the first rejection once — never per-frame.
    private static volatile boolean warnedRejected;

    private void warnRejected(String reason, int bufIndex,
                              int width, int height, int stride) {
        if (warnedRejected) {
            return;
        }
        warnedRejected = true;
        System.getLogger(FrameSurface.class.getName()).log(
            System.Logger.Level.WARNING,
            "WebView frame rejected (" + reason + "): slot=" + bufIndex
            + " " + width + "x" + height + " stride=" + stride
            + " slotBytes=" + slotBytes
            + " (reported once; frames may be stale until recovery)");
    }

    /** The most recently published frame, or {@code null} if none yet. */
    Frame latest() {
        return latest;
    }

    /**
     * Slot index (0..{@link MemoryLayout#FRAME_BUFFER_COUNT}-1) of a SPECIFIC
     * frame, or {@code -1} if {@code f} is null / out of range. The M13 handshake
     * must publish the slot of the EXACT frame being read, not a fresh
     * {@link #latest()} (the pump thread can swap {@code latest} between the read
     * and the publish, which would tell the engine to protect the wrong slot —
     * the tearing the handshake exists to prevent).
     */
    int slotIndexOf(Frame f) {
        if (f == null) {
            return -1;
        }
        long idx = (f.address() - baseAddress) / slotBytes;
        return (idx < 0 || idx >= MemoryLayout.FRAME_BUFFER_COUNT) ? -1 : (int) idx;
    }

    /** Slot of the most-recently published main frame, or {@code -1}. */
    int latestSlotIndex() {
        return slotIndexOf(latest);
    }

    /**
     * Copies the latest frame's pixels into a fresh GC-managed (auto-arena)
     * off-heap buffer, returning a self-contained snapshot, or {@code null} if
     * there's no frame. The copy is bounds-checked against the mapped region.
     * Used to retain the last-good frame across an engine respawn (the live
     * pixels live in the channel, which is unmapped when the engine dies).
     */
    BlinkPage.FrameSnapshot snapshotLatest() {
        Frame f = latest;
        if (f == null) {
            return null;
        }
        long bytes = (long) f.stride() * f.height();
        long offset = f.address() - baseAddress;
        if (bytes <= 0 || offset < 0 || offset + bytes > data.byteSize()) {
            return null;
        }
        MemorySegment dst = Arena.ofAuto().allocate(bytes);
        MemorySegment.copy(data, offset, dst, 0L, bytes);
        return new BlinkPage.FrameSnapshot(dst, f.width(), f.height(),
            f.stride(), f.logicalW(), f.logicalH());
    }

    /**
     * Records a newly-captured OSR popup frame into a popup slot. Validates the
     * geometry against the popup slot capacity. {@code x/y/logicalW/logicalH} are
     * the popup's rect in node-local logical coords (engine-supplied DIP).
     *
     * @return {@code true} if accepted and published
     */
    boolean publishPopup(int bufIndex, int width, int height, int stride,
                         double x, double y, double logicalW, double logicalH) {
        if (bufIndex < 0 || bufIndex >= MemoryLayout.POPUP_FRAME_BUFFER_COUNT) {
            return false;
        }
        if (width <= 0 || height <= 0 || stride < (long) width * 4) {
            return false;
        }
        long need = (long) stride * height;
        if (need <= 0 || need > MemoryLayout.POPUP_FRAME_SLOT_BYTES) {
            return false;
        }
        long address = baseAddress + popupRegionOffset
            + (long) bufIndex * MemoryLayout.POPUP_FRAME_SLOT_BYTES;
        popup = new PopupFrame(address, width, height, stride, x, y, logicalW, logicalH);
        return true;
    }

    /** The most recently published popup frame, or {@code null} if none open. */
    PopupFrame popupLatest() {
        return popup;
    }

    /** Clears the popup overlay (popup closed). */
    void clearPopup() {
        popup = null;
    }

    /**
     * Records a newly-captured print-preview modal frame into a preview slot.
     * Validates the geometry against the preview slot capacity. {@code logicalW/
     * logicalH} are the preview's DIP size (Java centers it over the page).
     *
     * @return {@code true} if accepted and published
     */
    boolean publishPreview(int bufIndex, int width, int height, int stride,
                           double logicalW, double logicalH) {
        if (bufIndex < 0 || bufIndex >= MemoryLayout.PREVIEW_FRAME_BUFFER_COUNT) {
            return false;
        }
        if (width <= 0 || height <= 0 || stride < (long) width * 4) {
            return false;
        }
        long need = (long) stride * height;
        if (need <= 0 || need > MemoryLayout.PREVIEW_FRAME_SLOT_BYTES) {
            return false;
        }
        long address = baseAddress + previewRegionOffset
            + (long) bufIndex * MemoryLayout.PREVIEW_FRAME_SLOT_BYTES;
        previewFrame = new PreviewFrame(address, width, height, stride, logicalW, logicalH);
        return true;
    }

    /** The most recently published preview modal frame, or {@code null} if none. */
    PreviewFrame previewLatest() {
        return previewFrame;
    }

    /** Clears the preview modal overlay (preview closed). */
    void clearPreview() {
        previewFrame = null;
    }
}
