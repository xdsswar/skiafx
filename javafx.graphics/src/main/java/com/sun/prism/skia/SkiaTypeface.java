package com.sun.prism.skia;

import com.sun.javafx.font.FontResource;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.prism.skia.impl.NativeHandles;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skia {@code SkTypeface} wrapper, built from a JavaFX
 * {@link FontResource}'s font file.
 *
 * <p>Typefaces are process-lived and shared: {@link #forFontResource}
 * builds one per font file and caches it. {@link SkiaGraphics#drawString}
 * uses them to render text via {@code SkTextBlob} instead of decomposing
 * every glyph into a path.</p>
 *
 * <p>The cache holds each typeface strongly for the JVM lifetime, so the
 * registered {@link NativeHandles} cleaner is only a leak safety net —
 * fonts in a UI are few and shared, not a memory concern.</p>
 *
 * <p><b>Known limitation:</b> {@code .ttc} font collections are loaded at
 * face index 0; a non-primary face in a collection would resolve to the
 * wrong glyphs. Callers fall back to glyph-as-path rendering when
 * {@code forFontResource} returns {@code null}.</p>
 */
public final class SkiaTypeface implements AutoCloseable {

    /** Cache marker for fonts with no usable file — avoids re-probing. */
    private static final SkiaTypeface UNAVAILABLE = new SkiaTypeface();

    /** One entry per font-file path; see {@link #forFontResource}. */
    private static final ConcurrentHashMap<String, SkiaTypeface> CACHE =
        new ConcurrentHashMap<>();

    /** Owned native handle; {@code null} only for {@link #UNAVAILABLE}. */
    private final NativeHandles.Slot slot;

    private SkiaTypeface() {
        this.slot = null;
    }

    private SkiaTypeface(long handle) {
        this.slot = NativeHandles.register(this, handle, SkiaTypeface::destroyNative);
    }

    private static void destroyNative(long handle) {
        NativeBridge.typefaceDestroy(MemorySegment.ofAddress(handle));
    }

    /**
     * Returns a shared {@code SkiaTypeface} for {@code fr}, or
     * {@code null} when the font has no readable file or Skia could not
     * parse it. A {@code null} return is the signal for the caller to
     * fall back to glyph-as-path rendering.
     */
    public static SkiaTypeface forFontResource(FontResource fr) {
        if (fr == null) {
            return null;
        }
        String file = fr.getFileName();
        if (file == null || file.isBlank()) {
            return null;
        }
        SkiaTypeface tf = CACHE.computeIfAbsent(file, SkiaTypeface::load);
        return tf == UNAVAILABLE ? null : tf;
    }

    private static SkiaTypeface load(String file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Path.of(file));
        } catch (IOException | RuntimeException e) {
            return UNAVAILABLE;
        }
        if (bytes.length == 0) {
            return UNAVAILABLE;
        }
        // The native side copies the bytes into Skia's own SkData, so a
        // confined arena scoped to this call is enough.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.length);
            MemorySegment handle = NativeBridge.typefaceCreateFromData(seg, bytes.length);
            if (handle == null || handle.equals(MemorySegment.NULL)) {
                return UNAVAILABLE;
            }
            return new SkiaTypeface(handle.address());
        }
    }

    /** Native {@code SkTypeface} handle, or 0 if closed/unavailable. */
    public long getNativeHandle() {
        return slot == null ? 0L : slot.get();
    }

    /** The native handle as a {@link MemorySegment} for bridge calls. */
    public MemorySegment handleSegment() {
        return MemorySegment.ofAddress(getNativeHandle());
    }

    /** True once the native typeface has been released. */
    public boolean isClosed() {
        return slot == null || slot.isClosed();
    }

    @Override
    public void close() {
        if (slot != null) {
            slot.close();
        }
    }
}
