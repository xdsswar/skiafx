/**
 * Skia rendering pipeline — the sole graphics backend in openjfx-skia.
 *
 * <p>The Prism-era backends ({@code com.sun.prism.{d3d,es2,mtl,sw,j2d}})
 * have been removed; this package implements the {@code com.sun.prism.*}
 * SPI directly on top of Skia (via the {@code openjfx_skia_shared}
 * native bridge). The public {@code javafx.scene.*} API is unchanged.</p>
 *
 * <p>See {@code CLAUDE.md} for the full design rationale.</p>
 */
package com.sun.prism.skia;
