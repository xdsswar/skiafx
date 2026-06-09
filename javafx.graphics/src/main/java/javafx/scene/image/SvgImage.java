/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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

package javafx.scene.image;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.LongConsumer;

import com.sun.javafx.util.DataURI;
import com.sun.prism.skia.impl.NativeBridge;
import com.sun.prism.skia.impl.NativeHandles;

/**
 * A vector {@link Image} backed by an SVG document.
 *
 * <p>{@code SvgImage} is a drop-in {@code Image} whose pixels come from a
 * parsed SVG rather than a raster file. Because the source is vector data, an
 * {@code SvgImage} has no fixed resolution: when displayed through
 * {@link SvgImageView} it is re-rasterized through Skia at the exact device
 * size it is drawn at, so it stays <em>crystal-clear at any size, zoom level,
 * or screen DPI</em> — there is never any pixelation from upscaling.</p>
 *
 * <p>The SVG is parsed once, on construction, into a native Skia document.
 * The intrinsic size reported by {@link #getWidth()} / {@link #getHeight()} is
 * taken from the document's {@code width}/{@code height} attributes or its
 * {@code viewBox}, so an {@code SvgImage} lays out sensibly wherever an
 * {@code Image} is accepted (for example as a button or label graphic via
 * {@link SvgImageView}).</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * // load from the classpath / a file / a URL, exactly like Image
 * SvgImage icon = new SvgImage("/icons/save.svg");
 *
 * // or from raw markup
 * SvgImage logo = SvgImage.ofContent("<svg viewBox='0 0 24 24'>...</svg>");
 *
 * // display it crisply at any size
 * SvgImageView view = new SvgImageView(icon);
 * view.setFitWidth(48);
 * button.setGraphic(view);
 * }</pre>
 *
 * <p><b>Threading / lifecycle.</b> The native document is released
 * automatically when this {@code SvgImage} becomes unreachable (via a
 * {@link Cleaner}), or eagerly via {@link #dispose()}. The same
 * {@code SvgImage} may be shared by many {@link SvgImageView}s; each rasterizes
 * independently at its own size.</p>
 *
 * <p><b>Limitations.</b> SVG animation (SMIL / CSS animation) is not played —
 * the document renders as a static frame. External resource references
 * (remote {@code <image href>}) are not fetched.</p>
 *
 * @see SvgImageView
 * @since 26
 */
public class SvgImage extends Image {

    /**
     * Frees the native {@code SkSVGDOM} — always on the <b>render thread</b>
     * (deferred), never inline on the caller's thread. The render thread is the
     * only thread that renders the document, so a deferred free can never run
     * concurrently with an in-flight {@code render()} of the same document
     * (which would be a use-after-free). Static so it captures no
     * {@code SvgImage} reference (the {@link NativeHandles} Cleaner must be able
     * to collect the owner). The native side also poisons the handle on free,
     * so any stale lookup is rejected rather than dereferenced.
     */
    private static final LongConsumer DESTROY =
        h -> NativeHandles.deferOnRenderThread(() -> NativeBridge.svgDestroy(h));

    // Owns the native handle; null when the SVG failed to parse (handle 0).
    private final NativeHandles.Slot slot;

    /**
     * Constructs an {@code SvgImage} from an SVG resource path, file path, or
     * URL — resolved with the same rules as {@link Image#Image(String)}.
     *
     * @param url a resource path, file path, or URL of an SVG document
     * @throws NullPointerException if {@code url} is null
     * @throws IllegalArgumentException if {@code url} is invalid
     */
    public SvgImage(String url) {
        this(loadUrl(validateUrl(url)), url);
    }

    /**
     * Constructs an {@code SvgImage} from the given input stream. The stream is
     * fully read but not closed.
     *
     * @param is the stream from which to read SVG markup
     * @throws NullPointerException if {@code is} is null
     */
    public SvgImage(InputStream is) {
        this(readBytes(Objects.requireNonNull(is, "is")), null);
    }

    /** Private content/bytes constructor: parses {@code svgBytes} once. */
    private SvgImage(byte[] svgBytes, String url) {
        // Master Image constructor that sets fields but loads no raster
        // (initialize() is never called). The vector document lives natively.
        super(url, null, 0, 0, false, false, false);

        long h = NativeBridge.svgParse(svgBytes);
        // Register on 'this' so the document is freed (render-thread deferred)
        // when the SvgImage is collected, or eagerly via dispose(). register()
        // requires a non-zero handle, so only wrap a successful parse.
        this.slot = (h != 0L) ? NativeHandles.register(this, h, DESTROY) : null;

        if (h == 0L) {
            // Parse failure, empty source, or no SVG support in this build.
            String why = (svgBytes == null || svgBytes.length == 0)
                    ? "SVG source was empty or could not be read"
                    : NativeBridge.hasSvg()
                        ? "Failed to parse SVG document"
                        : "SVG support is not available in this build";
            setVectorError(new IllegalArgumentException(why));
            // Still give it a nominal size so it lays out instead of vanishing.
            setVectorSize(1, 1);
            return;
        }

        float[] wh = new float[2];
        if (NativeBridge.svgGetSize(h, wh) && wh[0] > 0 && wh[1] > 0) {
            setVectorSize(wh[0], wh[1]);
        } else {
            setVectorSize(100, 100); // sane default for a size-less document
        }
    }

    /**
     * Creates an {@code SvgImage} from raw SVG markup.
     *
     * @param svgContent the SVG document as a string
     * @return a new {@code SvgImage}; its {@link #isError() error} property is
     *         set if the markup could not be parsed
     * @throws NullPointerException if {@code svgContent} is null
     */
    public static SvgImage ofContent(String svgContent) {
        Objects.requireNonNull(svgContent, "svgContent");
        return new SvgImage(svgContent.getBytes(StandardCharsets.UTF_8), null);
    }

    /**
     * Returns the native {@code SkSVGDOM} handle, or {@code 0} if this image
     * failed to load or has been disposed. Package-private: only the rendering
     * peer borrows it; it is never exposed publicly and never freed by callers.
     */
    long nativeHandle() {
        return slot == null ? 0L : slot.get();
    }

    /**
     * Eagerly releases the native SVG document. Optional — the document is
     * also freed automatically when this image is collected. After
     * {@code dispose()} the image renders nothing. Idempotent and safe to call
     * from any thread (the actual native free is deferred to the render thread).
     */
    public void dispose() {
        if (slot != null) {
            slot.close();
        }
    }

    /**
     * A vector image has no fixed raster, so it exposes no {@code PixelReader}
     * (returning one would dereference a non-existent platform image). Use
     * {@code new SvgImageView(this).snapshot(...)} to rasterize to pixels.
     */
    @Override
    boolean pixelsReadable() {
        return false;
    }

    // ---- byte loading ------------------------------------------------------

    /**
     * Reads all bytes from a validated URL string. Returns {@code null} on an
     * I/O failure so the resulting {@code SvgImage} reports a load error
     * instead of throwing — consistent with how a failed network/disk read
     * surfaces on an {@link Image}.
     */
    private static byte[] loadUrl(String validatedUrl) {
        // RFC 2397 data: URIs have no URL stream handler — decode them directly,
        // matching Image's data-URI support.
        if (DataURI.matchScheme(validatedUrl)) {
            DataURI data = DataURI.tryParse(validatedUrl);
            return data == null ? null : data.getData();
        }
        try (InputStream in = URI.create(validatedUrl).toURL().openStream()) {
            return readBytes(in);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Reads a stream fully; returns {@code null} on I/O failure. */
    private static byte[] readBytes(InputStream in) {
        try {
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }
}
