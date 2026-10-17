# Performance, memory and clarity vs. stock JavaFX

skia-fx is an experiment, not a stable release. This document records how the
Skia pipeline is measured against stock OpenJFX (Prism), what was fixed, and
the numbers as of 2026-10-16.

## How to measure

Two stock-API-only samples run unchanged on both pipelines:

| Tool | What it does |
|---|---|
| `./gradlew :samples:ensemble:runPerfAB` | `PerfAB`: runs the scenes `idle, shapes, effects, text, controls` for a fixed time each and prints one `[perfab]` line per scene: fps, frame-interval sd/p99/max, hitch % (interval > 1.5x median), heap, process working set / private bytes and GPU dedicated memory (Windows performance counters). |
| `./gradlew :samples:ensemble:runClarity` | `ClarityShot`: renders a crispness test card (1px borders, small text, hairlines, opacity group, cached node, rounded clip, pixel-art Canvas, controls), captures the real on-screen pixels with `javafx.scene.robot.Robot` and writes a PNG. |

Useful properties (pass as `-P<name>=<value>`): `perfab.seconds`,
`perfab.scenes`, `prism.skia.gpu.backend=d3d12`, `prism.skia.gpu=false`
(CPU raster), `OPENJFX_SKIA_D3D_WARP=1` (force the WARP software device),
`glass.win.uiScale=1.25` (force a HiDPI scale), `skia.verbose=true`
(per-second presented fps + Skia GPU cache usage).

For the stock side, compile `PerfAB.java`, `StressScene.java` and
`ClarityShot.java` against the OpenJFX SDK jars and run with
`--module-path <sdk>/lib --add-modules javafx.controls`.

Pulse fps alone is not smoothness: also check the presented-frame rate in the
`-Dskia.verbose` line. When pulses run faster than presents, frames are being
dropped.

## Results (Windows 11, 165 Hz panel, AMD RX 6800 + Intel iGPU, 1280x800)

fps = animation pulses (capped by the display when VSync is on).

| Scene | Stock D3D | skia D3D12 | skia GL | Stock SW | skia WARP | skia CPU raster |
|---|---|---|---|---|---|---|
| shapes (3000 animated nodes) | 145-164 | **163** | 154 | 62 | **92** | 11 |
| effects (48 drop shadows + blur) | 112-115 | **165** | 120 | 0.8 | **1.9** | 0.7 |
| text (scrolling list) | 165 | 165 | 164 | 62 | **165** | 23 |
| controls (table + form) | 165 | 165 | 164 | 62 | **88** | 28 |
| shapes p99 frame interval | 12.6-16.5 ms | **9.9 ms** | 10.7 ms | | | |
| private bytes, controls | 528 MB | **465 MB** | 498 MB | 360 MB | 506 MB | 284 MB |
| GPU dedicated, idle | 37 MB | 53 MB | 112 MB | | | |

Clarity (mean absolute pixel difference vs. stock, lower = closer):
100% 2.80 -> 1.24, 125% 2.60 -> 1.48, 150% -> 1.30. Remaining difference is
glyph-level text rasterization; borders, clips, opacity groups, cached nodes
and pixel art now match.

## Root causes fixed

Performance / smoothness
- **Shapes went through the generic path renderer.** `SkiaShapeRep` handed
  every Circle / Ellipse / rounded Rectangle to `fill(Shape)` (cubic-curve
  `SkPath`, tessellated every frame). It now dispatches to the analytic
  `fillEllipse` / `fillRoundRect` (Skia `drawOval` / `drawRRect`).
- **`getenv` on every draw.** The per-draw `begin_draw` bracket called
  `getenv` on each call (its diag guard never latched). Cached once; this
  alone nearly doubled shape throughput.
- **FFM call overhead.** Hot leaf downcalls use `Linker.Option.critical`.
  `-Dskia.ffm.critical=false` turns it off (JFR only attributes native time
  to non-critical calls).
- **Present-rate cap dropped frames.** With VSync on, the wall-clock cap in
  `PresentingPainter` (based on the integer Hz the OS reports) skipped about
  every other present. It is now skipped whenever the presentable paces itself
  (`SkiaPresentable.pacesToDisplay()`: GPU swap-chain tiers with VSync on).
  The readback tier keeps the cap.
- **D3D12 fell to the readback tier after `Stage.setScene`.** DXGI allows one
  flip-model swap chain per HWND and Skia still referenced the old back
  buffers, so the new swap chain failed (about 20 fps, a CPU copy per frame).
  The swap-chain destroy now runs a synchronous Skia flush before releasing
  the swap chain, and a per-HWND owner registry releases a stale swap chain
  before creating a new one.

CPU fallback
- **WARP.** When no hardware D3D12 adapter works (no GPU, no driver, basic
  display adapter), or OpenGL is unusable under AUTO, the D3D12 backend runs
  on WARP, the software rasterizer built into Windows. It beats Prism's
  software pipeline in every scene. The Skia CPU raster tier remains the last
  resort (`prism.skia.gpu=false`). Its MSVC build of Skia runs the raster
  pipeline scalar, which is why it is slow. Other platforms: Linux Mesa
  provides llvmpipe for GL and macOS always has Metal (paths still TBD).

Memory
- **Skia GPU cache is bounded and trimmed.** `-Djavafx.gpu.skiaCacheBudget`
  (default 96 MB, was Skia's 256 MB default) and a once-per-second
  `performDeferredCleanup(5 s)` from present. `-Dskia.verbose` prints current
  and purgeable cache bytes.

Clarity
- **`fillRect` used integer logical coordinates.** At 125/150/175% that moved
  Region backgrounds and borders between device pixels. Solid fills now use
  float geometry.
- **Rounded / shape clips.** A filled, opaque shape clip now becomes a native
  anti-aliased path clip (`SkiaGraphics.pushClipShape`) instead of two
  logical-resolution offscreens plus a blend. That blend path drew at the
  wrong size and position on HiDPI.
- **Opacity groups** render into a device-pixel-snapped offscreen with the
  scale baked into the transform and composite back 1:1.
- **Pixel-aligned blits** are detected in device space (with tolerance) and
  drawn with nearest sampling at the snapped device rect.
- **Image smoothing.** `Texture.getLinearFiltering()` reaches Skia, so
  `GraphicsContext.setImageSmoothing(false)` and `ImageView.setSmooth(false)`
  render nearest-neighbour.
- **Text.** Subpixel X positioning with baseline snap (even letter spacing,
  no row straddling) and a text contrast tuned against stock
  (`OPENJFX_SKIA_TEXT_CONTRAST` / `OPENJFX_SKIA_TEXT_GAMMA` override it).

## Open items

- D3D12 is the Windows AUTO default (hardware D3D12 -> hardware OpenGL ->
  D3D12 WARP -> CPU raster). GL uses more GPU memory than D3D12 (driver
  overhead, not Skia's cache).
- Software effects (heavy blur) are slow on every CPU path, as on Prism SW.
- Plan items not yet done: D3D12 frame-latency waitable, resize scaling mode,
  async readback for owned windows, effect `ImagePool` byte cap, release on
  minimize, image upload copies, media frame copies.
