package com.sun.javafx.font;

/**
 * Exposes the face index of a font inside a TrueType/OpenType collection
 * ({@code .ttc}/{@code .otc}) to the Skia text path, which loads typefaces
 * from the font file and must select the same face Prism laid the glyphs
 * out with. Additive accessor: {@link PrismFontFile} keeps its contract.
 */
public final class FontFaceIndex {

    private FontFaceIndex() { }

    /** Face index of {@code fr} in its file; 0 for single-face files. */
    public static int of(FontResource fr) {
        return (fr instanceof PrismFontFile pff) ? Math.max(0, pff.fontIndex) : 0;
    }
}
