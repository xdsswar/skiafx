# WebView Off-Screen Frame Capture

How the Blink/jux engine delivers page pixels to the JavaFX WebView node,
after the 2026-06-10 migration to viz-driven capture. skia-fx is an
experiment, not stable.

## Architecture (current)

The engine renders the page off-screen (cloaked window, never composited to
the OS). Main-frame pixels reach Java through a shared-memory ring of three
BGRA slots plus a `kFrameReady` event carrying slot index, device size,
stride, and the logical (DIP) size the frame represents. Java composites the
latest frame into the scene at the frame's own logical size — frames are
never fitted, stretched, or cropped, including during resize transitions.

**Capture is push-based**: a per-page `JuxVideoConsumer`
(`viz::mojom::FrameSinkVideoConsumer` in `jux_engine_api.cc`) owns a
`viz::ClientFrameSinkVideoCapturer` targeted at the page's frame sink —
Chromium's tab-capture mechanism, used directly through the viz/content
APIs (this engine contains no CEF). Key properties:

- `PIXEL_FORMAT_ARGB` — BGRA bytes, identical to the SHM slot layout; the
  consumer copies `content_rect` rows straight into the next slot.
- Damage-driven, up to ~125 fps (8 ms min capture period); the JavaFX side
  presents at the window's monitor refresh, so high-refresh displays get
  the full benefit and 60 Hz panels simply skip.
- Aspect-preserving resolution constraints sized from the slot byte budget
  (a 16:9 box whose area equals the slot's pixel capacity — 2560×1440 for
  the default slot), so every emitted frame fits a slot by construction.
- **Follows the surface across resizes**: during fullscreen enter/exit or
  a cross-DPI monitor move, viz keeps delivering the last-active content
  until the renderer commits the new size — frames flow continuously and
  video keeps playing. This is the property the previous architecture
  lacked (see below).
- Re-targets on `PrimaryPageChanged`/`RenderViewHostChanged` (navigation
  process swaps, renderer crash recovery); `SET_SIZE` additionally
  requests a refresh frame so pure scale changes deliver without damage.

The capture tick (`JuxCaptureTick`) still runs for everything else: Blink
page-popup (`<select>`) capture, cursor polling, the print-preview overlay
(whose frames must route to the preview region and therefore stay on the
polling path), and the per-tick capture-scale-override healing.

## HiDPI

The engine runs with `--force-device-scale-factor=1` (set by
`EngineProcessManager`): chromium-DIP == pixels on every monitor, so the
hidden capture window's monitor never perturbs geometry. Page render
density comes solely from `SetScaleOverrideForCapture(jfxScale)` — exact
from the first `SET_SIZE` after a DPI change. Two engine-side backstops
self-heal within one tick if anything drifts: the override is re-derived
each tick, and the viewport size is re-asserted if the widget's DIP size
diverges from the last commanded logical size.

`--disable-direct-composition` is also set (and explicitly forwarded to
the GPU process in `JuxBrowserClient::AppendExtraCommandLineSwitches` —
Chromium does not propagate it by default): DComp video overlays bypass
the composited surface that capture reads; an engine that never presents
an OS window gets no benefit from them.

## Why the polling path was replaced

The previous design polled `RenderWidgetHostView::CopyFromSurface` every
4–16 ms. Each request targets the *current (pending)* surface; after a
resize the pending surface does not activate until the renderer commits at
the new size, so every request died for the whole relayout — measured as
~170 consecutive empty results (2–4 s frozen WebView) on YouTube with
playing video, ~30–90 ms on light pages. With the capturer, the first
new-size frame after a fullscreen toggle arrives in ~25 ms (local page) /
~68 ms (YouTube), with old-size frames interleaved up to that moment.

## Flags

| Flag | Default | Meaning |
|---|---|---|
| `-Dskia.webview.pollCapture=true` | capturer ON | revert to legacy CopyFromSurface polling (`--jux-poll-capture`) |
| `-Dskia.webview.directComposition=true` | DComp OFF | re-enable DirectComposition for A/B debugging |
| `OPENJFX_SKIA_WEBDPI_DIAG=1` | off | `[webdpi]` capture/override/geometry tracing (with `-Dskia.webview.engineVerbose=true` for the log file) |
| `-Dskia.webview.maxFrameWidth/Height` | 2560/1440 | SHM slot dimensions (drives the capturer's constraint box) |

## Known follow-ups

- The renderer's own relayout time on heavy pages (1–2 s for YouTube
  fullscreen) bounds how fast the new-size frame can exist; frames flow
  throughout, so this reads as a brief old-size interim, not a freeze.
- Multi-window-per-process would need the slot rotation and callback
  channel moved into the per-page entry (pre-existing note in
  `PublishMainFrame`).
