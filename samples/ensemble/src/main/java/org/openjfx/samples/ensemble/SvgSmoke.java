package org.openjfx.samples.ensemble;

import javafx.scene.image.SvgImage;

/**
 * Headless smoke test for the native SVG pipeline (no window). Verifies that
 * the Skia bridge loads, {@code svg_parse} / {@code svg_get_size} work, and an
 * SVG with gradients + text parses and reports a sensible intrinsic size.
 *
 * <p>Run: {@code ./gradlew :samples:ensemble:runSvgSmoke}</p>
 */
public final class SvgSmoke {
    public static void main(String[] args) {
        // viewBox 240x90, gradient fill, and a <text> run.
        String svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 90">
              <defs><linearGradient id="g"><stop offset="0" stop-color="#f06"/>
              <stop offset="1" stop-color="#09f"/></linearGradient></defs>
              <rect width="240" height="90" rx="12" fill="url(#g)"/>
              <text x="18" y="56" font-size="34" font-family="Arial" fill="white">Hello SVG</text>
            </svg>
            """;

        SvgImage img = SvgImage.ofContent(svg);
        System.out.println("[svg-smoke] error=" + img.isError()
                + " width=" + img.getWidth() + " height=" + img.getHeight()
                + (img.getException() != null ? " ex=" + img.getException() : ""));

        // A clearly-malformed source must fail gracefully (error set, no crash).
        SvgImage bad = SvgImage.ofContent("not an svg at all");
        System.out.println("[svg-smoke] malformed-handled error=" + bad.isError());

        // data: URI (base64) must load like any URL.
        String tiny = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 60 40'>"
                + "<rect width='60' height='40' fill='blue'/></svg>";
        String dataUri = "data:image/svg+xml;base64,"
                + java.util.Base64.getEncoder().encodeToString(
                        tiny.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SvgImage data = new SvgImage(dataUri);
        boolean dataOk = !data.isError() && Math.abs(data.getWidth() - 60) < 0.5;
        System.out.println("[svg-smoke] dataUri error=" + data.isError()
                + " width=" + data.getWidth() + " ok=" + dataOk);

        boolean ok = !img.isError()
                && Math.abs(img.getWidth() - 240) < 0.5
                && Math.abs(img.getHeight() - 90) < 0.5
                && bad.isError()
                && dataOk;

        img.dispose();
        bad.dispose();
        data.dispose();

        System.out.println("[svg-smoke] RESULT=" + (ok ? "PASS" : "FAIL"));
        System.exit(ok ? 0 : 1);
    }

    private SvgSmoke() {}
}
