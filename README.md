<div align="center">

# skia-fx

### A Skia-powered rendering pipeline for OpenJFX — drop-in compatible, GPU-accelerated, uncapped.

**Same JavaFX you already know. A brand-new engine underneath.**

`Java 25+` · `Gradle 9.2` · `Native Skia` · `OpenGL · Direct3D 12 · zero-copy GPU` · `Windows only for now (macOS · Linux coming)`

</div>

---

> ## EXPERIMENTAL — NOT STABLE
>
> **skia-fx is an experiment and a work in progress. It is not production-ready and
> not stable.** Expect bugs, crashes, incomplete features, breaking changes, and
> rough edges. APIs, behavior, build steps, and the native bridge can change without
> notice. Treat everything here as a research/preview project — do not rely on it for
> anything you can't afford to have break. "Working" below means "demonstrated on the
> developer's setup," not "battle-tested."
>
> **Windows only for now.** The current build and the native bridge target
> **Windows x64**. macOS and Linux are in scope and planned, but are **not yet
> built or tested** — they're coming.

---

## What is this?

**skia-fx** is a fork of **OpenJFX 25** that rips out the aging Prism renderer and
replaces it with a modern, GPU-first pipeline built directly on **[Skia](https://skia.org)** —
Google's production 2D graphics library, the same engine that paints Chrome, Android,
and Flutter.

The deal is simple and uncompromising:

> **Your existing JavaFX application runs unchanged — just faster, leaner, and more correct.**

Every public `javafx.*` API, every module name, every JAR filename, every manifest
version is **byte-for-byte identical** to stock OpenJFX 25. You drop our `sdk/lib/`
in place of `javafx-sdk-25/lib/` and your app *just runs* — except now it's drawing
through Skia on the GPU, animating at your display's true refresh rate, and rendering
text, gradients, and shapes the way a 2026 toolkit should.

No rewrites. No API breakage. No "port your app." That's the whole point. New
capabilities (such as vector SVG) are added as *additive* opt-in types — they extend
the API, they never change what already exists.

---

## Standing on the shoulders of giants

**None of this exists without the OpenJFX team.**

skia-fx is built *on top of* the extraordinary, decades-deep work of the OpenJFX
developers and the wider JavaFX community. The scene graph, the CSS engine, the
animation framework, FXML, the control library, the windowing and input layer — all
of it is their craft, and all of it is what makes JavaFX one of the finest desktop UI
toolkits ever shipped on any platform.

We didn't reinvent JavaFX. We swapped its rendering floor for a new one and kept
everything brilliant about it intact. The fact that you can read this README, build
the project, and run a real app *today* is a direct testament to the quality and
foresight of the foundation they built. **Thank you.**

> skia-fx tracks upstream OpenJFX as a living reference and ports fixes forward — this
> is a fork that *honors* its parent, not one that abandons it.

---

## Feature highlights

| Feature | Status |
|---|---|
| **Skia-backed rendering** — `SkCanvas`/`SkSurface` instead of Prism, via our own native bridge | Working |
| **GPU pipeline** — OpenGL (Ganesh) + **Direct3D 12** backends, GPU context per render thread | Working |
| **Selectable GPU backend** — `Application.setGpuBackend(...)` / `-Dprism.skia.gpu.backend`, default `AUTO`, graceful fallback when unavailable | Working |
| **Uncapped frame rate** — render at the display's *actual* refresh (120/144/240Hz+), not a hardcoded 60 | Working |
| **Runtime VSync toggle** — `Application.setVsyncEnabled(...)` / `vsyncEnabledProperty()`, global, on by default | Working |
| **Wall-clock animation** — animations driven by real time, correct at any cadence | Working |
| **Full effect suite on Skia** — every JavaFX `Effect` mapped to Skia image filters: blur, shadows, glow, color adjust, displacement, lighting and more | Working |
| **Crisp text** — Skia glyph rendering with anti-alias tuned for translucent layers; color-emoji groundwork | Working |
| **Vector SVG** — `SvgImage` / `SvgImageView`, pixel-perfect at any zoom/DPI, zoom + tint + grid, CSS-styleable | Working |
| **Bounded, observable memory** — GPU budgets, eviction policies, `Cleaner`-driven native lifecycles | Working |
| **Copy elimination** — architecturally zero-copy hot paths, per-frame off-heap arenas, no per-frame `new` | Ongoing |
| **Brand-new WebView** — a from-scratch **Chromium/Blink** engine, off-screen-rendered straight into the scene | Heavy WIP |
| **Modern media engine** — hardware decode (D3D11VA) for H.264/H.265/VP9/AV1, CPU/GPU/AUTO selection, HDR tone-mapping, zero-copy D3D11 video into the scene | Working |
| **Broad format support** — MP4/MP3/HLS out of the box; WebM, MKV, FLAC, AVI, FLV, OGG and *any* container ffmpeg can demux when its DLLs are present | Working |
| **Dual-source playback** — `Media(audioSource, videoSource)` plays separate audio + video URLs as one synced stream, with accurate seeking on fragmented (DASH-style) MP4 | Working |
| **MediaMixer** — mux separate audio + video files/URLs into a single MP4, off the FX thread, with progress callbacks | Working |
| **Custom primary stage** — generic `Application<W extends Stage>` so apps can use their own `Stage` subclass | Working |
| **Signed native libs** — per-module SHA-256 manifest + loader override for cache integrity | Working |
| **3D scene graph** — bgfx engine sharing Skia's D3D12 device: meshes, `PerspectiveCamera`, lights, `PhongMaterial`, textures, MSAA, shadows | Working |
| **glTF 2.0 model loading** — new `javafx.scene3d` module (`ModelLoader` / `Model3D`), native cgltf | Working |

---

## Architecture at a glance

```
        +-------------------------------------------------------------+
        |   YOUR APP  ·  javafx.scene.*  ·  FXML  ·  CSS  ·  Controls  |   <- 100% unchanged
        +-------------------------------------------------------------+
                                     |  (scene graph, pulses, NGNode peers)
                                     v
        +-------------------------------------------------------------+
        |     Quantum toolkit  ·  pulse lifecycle  ·  Glass windows   |   <- targeted edits
        +-------------------------------------------------------------+
                                     |  peers call into...
                                     v
        +-------------------------------------------------------------+
        |   SKIAPipeline · SkiaGraphics · SkiaResourceFactory · ...    |   <- NEW (com.sun.prism.skia)
        |            (Java glue - thin, ~20% of the work)             |
        +-------------------------------------------------------------+
                                     |  FFM / JNI
                                     v
        +-------------------------------------------------------------+
        |   Native Skia bridge  ·  SkCanvas/SkSurface  ·  GrDirectCtx  |   <- NEW (native-skia)
        |     Ganesh GL  ·  Direct3D 12  ·  WGL_NV_DX_interop2         |     (~80% of the work)
        +-------------------------------------------------------------+
```

The Java side is deliberately **thin**: peer types, pipeline registration, and glue.
The real work lives in native C++ — surface plumbing, GPU context lifecycle, glyph and
image upload, command recording, and present. That's where performance is won.

> **Native Skia, not Skija.** Earlier notes referenced JetBrains' Skija bindings; the
> shipping bridge is **our own native C++ layer** talking to Skia directly via FFM/JNI,
> for full control over the zero-copy present and GPU resource lifecycles.

---

## The modules

skia-fx keeps OpenJFX's exact JPMS module layout. Module name == directory name ==
published JAR name — one identifier, end to end.

### `javafx.base`
The foundation: properties, bindings, observable collections, events. **Untouched**
upstream API and behavior — this is pure JavaFX, used as-is.

### `javafx.graphics` — *the heart of the project*
Where the renderer lives. Public `javafx.scene.*` API is untouched; underneath it:

- **`com.sun.prism.skia`** — the new pipeline. `SKIAPipeline` registers via the
  standard `GraphicsPipeline` SPI; `SkiaGraphics` implements Prism's imperative
  `Graphics` interface by driving `SkCanvas` (fill, stroke, clip, transform, text,
  images); `SkiaResourceFactory` owns textures, render targets, and surfaces over a
  `GrDirectContext`. Supporting cast: `SkiaRTTexture`, `SkiaImageTexture`,
  `SkiaPresentable`, `SkiaShapeRep`, `SkiaTypeface`, `SkiaMediaTexture`.
- **`com.sun.prism.skia.impl`** — the machinery: `NativeBridge` (FFM entrypoints),
  `NativeHandles` (render-thread-safe `Cleaner` lifecycles with deferred GPU frees),
  `FrameArena` (per-frame off-heap bump allocator — no per-frame `new`),
  `PathEncoder`, `SkiaShaders`, `SkiaImageFilters`, `HdrToneMap`.
- **`com.sun.scenario.effect.impl.skia`** — the JavaFX effect set re-implemented on
  Skia's `SkImageFilter` chains: drop shadow, box blur, box/linear-convolve shadow,
  Gaussian, bloom/brightpass, color adjust and color matrix, sepia, invert mask, blend,
  displacement map, perspective transform, Phong lighting, zoom/radial blur — with a
  passthrough fallback so a scene paint never aborts on a missing peer.
- **`javafx.scene.image`** — adds **vector SVG** as a first-class image type (see
  below): `SvgImage` (extends `Image`) and `SvgImageView` (extends `ImageView`).
- **3D via bgfx** — the JavaFX 3D scene graph (`Box`, `Sphere`, `MeshView`,
  `PerspectiveCamera`, lighting, `PhongMaterial`) renders through a **bgfx** engine that
  **shares Skia's Direct3D 12 device** (`com.sun.javafx.scene3d.Scene3D`,
  `com.sun.prism.skia.impl.NativeBridge3D`) and composites zero-copy into the 2D scene —
  with textures, MSAA, and shadows. `SubScene` stays byte-identical; the renderer is
  swapped underneath.
- **`native-skia`** — the C++ Skia bridge: `openjfx_skia_bridge.cpp` (the draw API,
  including the `openjfx_skia_svg_*` SVG entry points), the **Ganesh OpenGL** backend,
  the **Direct3D 12** swap-chain backend (`openjfx_skia_d3d_win.cpp`), and
  **D3D11-to-GL zero-copy interop** via `WGL_NV_DX_interop2`
  (`openjfx_skia_d3d11_interop.cpp`) for direct video texture sharing. Plus the
  Quantum/Glass present glue (`PresentingPainter`, `SkiaPresentable`).

### `javafx.controls`
The full control library — Button, TableView, TreeView, the works — plus the Modena
stylesheet and CSS engine. **Untouched** API; renders through Skia like everything else.

### `javafx.fxml`
FXML loading and Scene Builder integration. **Untouched.**

### `javafx.media` — *a modernized media engine*
The public `Media` / `MediaPlayer` / `MediaView` API is preserved and extended with
additive capabilities; underneath, the GStreamer-based engine has been substantially
rebuilt around ffmpeg and GPU decode.

**Decode control (new public API on `Media`):**

- **`Media.setDecodeMethod(AUTO | CPU | GPU | GPU_PREFERRED)`** — first-class control
  over decode strategy, backed by the `skia.media.decode` system property.
- **`Media.setFfmpegDirectory(dir)`** / **`Media.getFfmpegDirectory()`** — point the
  engine at an ffmpeg runtime; also honored via `OPENJFX_MEDIA_FFMPEG_DIR`.
- **`Media.isFfmpegAvailable()`** / **`Media.getFfmpegStatus()`** — query (and
  diagnose) whether the ffmpeg tier is active.

**Two format tiers** (see [`docs/MEDIA_FORMATS.md`](docs/MEDIA_FORMATS.md)):

- **Default tier** — no external dependencies: MP4 (H.264/H.265 + AAC), MP3, ADTS AAC,
  WAV, AIFF, FLV, and HLS, using the platform decoders (DirectShow / Media Foundation).
- **ffmpeg tier** — when the ffmpeg DLLs are present: WebM (VP8/VP9/AV1 + Opus/Vorbis),
  Matroska, FLAC, AVI, extended FLV — and a **libavformat catch-all demuxer**
  (`ffmpegdemux`) that plays *any container ffmpeg can open* (MOV, OGG, MPEG-TS,
  WMV/ASF, 3GP, MPEG-PS, ...). A missing or broken ffmpeg never degrades the default
  tier: the loader fails once with a clear message and everything stock keeps playing.
- Java-side **metadata parsers** for the new containers (Matroska/WebM, FLAC
  vorbis-comments, AVI, FLV) feed `Media.getMetadata()` without touching the pipeline.

**Hardware decode + zero-copy video:**

- **D3D11VA hardware decode** through ffmpeg for H.264, H.265, VP9 and AV1 (tested
  up to 4K), with automatic software fallback (libdav1d preferred for CPU AV1).
- **`SkiaMediaTexture`** — pooled scratch buffers, `Cleaner`-managed native handles,
  YUV I420 / HDR upload, **HDR tone-mapping** (BT.2390), and a **D3D11 zero-copy**
  interop path (`WGL_NV_DX_interop2`) that lands decoded frames on the GPU with no
  CPU round-trip. The interop is quiesced around window resizes / fullscreen
  transitions so swap-chain rebuilds and GPU video can never deadlock the driver.

**Dual-source playback (new public constructors):**

- **`Media(audioSource, videoSource)`** and
  **`Media(audioSource, videoSource, headers)`** — play separate audio and video URLs
  (adaptive-streaming style: a video-only stream plus an audio-only companion) as one
  player with one timeline. The two pipelines share a clock and are sync-corrected to
  within about a video frame; the headers overload applies HTTP headers and an optional
  `User-Agent` to both streams.
- **Accurate seeking on fragmented (DASH-style) MP4** — the engine parses the `sidx`
  fragment index and maps time seeks to exact fragment byte ranges, so video lands on
  target and stays in lock-step with the sample-accurate audio, forward and backward.
- **Seek hardening** — rapid seeks (slider drags) are coalesced at the player level so
  only the final target executes; post-seek the video fast-forwards to the audio clock;
  a watchdog detects a frozen or lagging video chain and re-syncs it onto the live
  audio position with bounded, backed-off retries. See
  [`docs/DUAL_SOURCE_MEDIA.md`](docs/DUAL_SOURCE_MEDIA.md).
- **HTTP source hardening** — bounded range-request rotation defeats per-connection
  CDN throttling, with verified `Content-Range` handling and reconnect-on-truncation.

**`MediaMixer` (new public class):**

- **`MediaMixer(audioSource, videoSource, output)`** muxes separate audio + video
  inputs (files or URLs) into a single MP4 — stream copy, no re-encode — off the FX
  thread, reporting through **`MediaMixerListener`** (`onStart` / `onProgress` /
  `onFinished` / `onError`), with cancel support and optional faststart. See
  [`docs/MEDIA_MIXER.md`](docs/MEDIA_MIXER.md).

**Robustness** (see [`docs/MEDIA_HARDENING.md`](docs/MEDIA_HARDENING.md)): a stall
watchdog turns silent pipeline hangs into ordinary catchable `MediaException`s,
buffering stalls resume with anti-flap hysteresis, native error paths surface as
events instead of process death, and the native side is leak-counted in dev builds.

### `javafx.web` — *a completely new browser engine*
The WebView is no longer WebKit. skia-fx embeds a **from-scratch Chromium/Blink engine**
(internally the *jux engine*), **off-screen rendered** and composited **directly into
the JavaFX scene** through the Skia surface — no window-in-a-window, no native overlay.
The entire public `javafx.scene.web` API (`WebView`, `WebEngine`, `WebHistory`,
`HTMLEditor`, JS bridge) is preserved, and we *added* a rich, modern surface on top:

- **Off-screen GPU rendering** of live Chromium pages, shared-memory frame transport,
  HiDPI-correct scaling, and OSR input (mouse, wheel-with-phase, keyboard, hover cursor).
- **`org.w3c.dom` DOM access** over Blink — `Document`, `Element`, `NodeList`,
  `HTMLCollection`, `NamedNodeMap`, attributes, text, events — the real W3C interfaces.
- **`netscape.javascript.JSObject` bridge** — call JS from Java and Java from JS, with a
  tagged value codec and request-id-correlated, non-blocking round-trips.
- **Network interception** — a public `NetworkInterceptor` / `NetworkExchange` API to
  observe, block, redirect, rewrite headers, or synthesize responses, with body capture
  and editing (`BodyChunk`, `BodyEdit`, `SyntheticResponse`).
- **Full chrome callbacks** — JS dialogs (alert/confirm/prompt), **file choosers**,
  **color pickers**, **`<select>` popups**, **downloads** (with progress), **permission
  prompts**, **HTTP auth**, and **fullscreen** requests, each via a clean handler API
  (`FileChooserHandler`, `DownloadHandler`, `PermissionHandler`, `AuthHandler`, ...).
- **History / back-forward** fed live from Chromium's navigation controller, and a
  configurable **User-Agent**.
- **Off-screen print preview** — `chrome://print` rendered off-screen and composited
  into the scene, with the PDF viewer (per-page render, zoom, scroll) and print actions.

> `jdk.jsobject` provides the `netscape.javascript` package the bridge implements —
> unchanged public API, Blink-backed underneath.

### `javafx.swing`
`SwingNode` / `JFXPanel` interop. Public API is **unchanged** (and per project rules,
AWT/Swing stays strictly inside this module — nowhere else), but the module no longer
depends on the JDK's **`jdk.unsupported.desktop`** module, which is deprecated and slated
for removal. The small set of interop wrappers it used to provide
(`jdk.swing.interop.*`, over `JLightweightFrame` and the AWT drag/drop and grab peers)
were ported **into this module** as `com.sun.javafx.embed.swing.interop`, reaching the
underlying `sun.swing` / `sun.awt` internals directly.

Because those JDK-internal packages aren't exported to us, the module is built — and any
app using `JFXPanel` or `SwingNode` must be **launched** — with four `--add-exports`:

```bash
java --add-exports java.desktop/sun.swing=javafx.swing \
     --add-exports java.desktop/sun.awt=javafx.swing \
     --add-exports java.desktop/sun.awt.dnd=javafx.swing \
     --add-exports java.desktop/java.awt.dnd.peer=javafx.swing \
     --module-path sdk/lib --add-modules javafx.swing,javafx.controls \
     -jar your-app.jar
```

This is the unavoidable cost of dropping a module that is going away (a named module
reaching a non-exported package needs `--add-exports` at compile and runtime alike). It
is the only place in skia-fx where existing Swing-interop apps need a launch-flag change.

Status: **`JFXPanel`** (JavaFX embedded inside Swing) works and renders through the Skia
pipeline. **`SwingNode`** (Swing embedded inside JavaFX) is temporarily unavailable in
the dev tree for an *unrelated* reason — its constructor loads a native `prism_common`
library that skia-fx does not build yet. The `samples:swinginterop` demo
(`./gradlew :samples:swinginterop:run`, or `:runSelfCheck` for a headless check) exercises
both directions and degrades gracefully where the native is missing. See
[`docs/MODULE_SWING_DROP_UNSUPPORTED_DESKTOP.md`](docs/MODULE_SWING_DROP_UNSUPPORTED_DESKTOP.md).

### `javafx.scene3d` — *new: glTF 2.0 model loading*
A **new, additive** module (it does not exist in stock OpenJFX) for loading 3D models
into the scene graph:

- **`ModelLoader`** — a fluent loader (`ModelLoader.of(url).center().scale(...).load()`)
  that parses **glTF 2.0** via native **cgltf** and builds a JavaFX 3D subtree.
- **`Model3D`** — the loaded result; glTF node names are exposed as node IDs so you can
  look up and animate parts by name.

The loaded geometry renders through the same bgfx 3D engine described above. (The model
parsing/build is in `com.sun.javafx.model3d` over `openjfx_model3d_bridge.cpp`.)

---

## Vector graphics (SVG)

skia-fx adds **resolution-independent SVG** as a native image type, rendered through
Skia's SVG module. It lives in the public `javafx.scene.image` package and mirrors the
familiar `Image` / `ImageView` pair:

- **`SvgImage extends Image`** — parses an SVG document once into a native Skia
  `SkSVGDOM`. Load from a URL, file, classpath resource, `InputStream`, `data:` URI, or
  raw markup (`SvgImage.ofContent(...)`). Intrinsic size comes from the document's
  `width`/`height` or its `viewBox`. It behaves like any `Image` for layout and error
  reporting, but holds vector data instead of a fixed raster.
- **`SvgImageView extends ImageView`** — displays an `SvgImage` and **re-rasterizes it
  as vectors straight onto the scene surface** at the exact device size it is drawn at.
  There is no intermediate raster texture, so it stays **crystal-clear at any zoom level
  or DPI** — no upscaling blur, no resolution cap. It is a `Node`, so it drops into any
  graphic slot (button, label, menu item, tab).

What it supports:

- **Zoom** — `zoom`, `minZoom`, `maxZoom` (plus `zoomIn()`, `zoomOut()`, `resetZoom()`),
  each step rasterized at full device resolution.
- **Sizing** — the inherited `fitWidth` / `fitHeight` / `preserveRatio` (exactly like
  `ImageView`); the full document always fits the box, even for SVGs authored with
  absolute `width`/`height`.
- **Node-level color** — `tint` + `tintMode` (`NONE` / `SRC_IN` / `MULTIPLY`) recolor the
  rendered output (ideal for monochrome icons); `backgroundColor` fills the node box
  behind the SVG. The SVG's own paint is never edited.
- **Grid overlay** — an optional measurement/inspector grid drawn over the artwork, with
  styleable color, spacing, and line width.
- **CSS** — `-svg-zoom`, `-svg-min-zoom`, `-svg-max-zoom`, `-svg-tint`, `-svg-tint-mode`,
  `-svg-background-color`, `-svg-grid-visible`, `-svg-grid-color`, `-svg-grid-spacing`,
  `-svg-grid-line-width`.

The full static SVG feature set is covered — shapes and paths, solid/gradient/pattern
paint, opacity, clipping and masking, filter effects (blur, color-matrix, blend,
composite, turbulence, displacement, lighting), transforms, and text. The native build
is self-contained: the `prepareSkiaSvg` Gradle task produces the SVG static libraries
so a plain `buildAll` works with no manual steps. **SVG animation (SMIL / CSS-in-SVG) is
not yet played** — an animated document renders its static first frame. See
[`docs/MODULE_SVG.md`](docs/MODULE_SVG.md) for the full design.

```java
// Crisp icon on a button, any DPI, no @2x assets
button.setGraphic(new SvgImageView(new SvgImage("/icons/save.svg")));

// A zoomable, styleable view
SvgImageView view = new SvgImageView(new SvgImage(diagramUrl));
view.setFitWidth(480);
view.setPreserveRatio(true);
view.setMaxZoom(40);
view.setGridVisible(true);
```

Try it: `./gradlew :samples:ensemble:runSvgDemo` (zoom-to-cursor, pan, grid, tint,
background, and an "Open SVG..." file chooser).

---

## The pipeline, in depth

### GPU backends
- **Ganesh / OpenGL** — the portable baseline; an off-screen WGL context owns the Ganesh
  GL surface, presented to the window.
- **Direct3D 12** — preferred on Windows; a flip-model swap chain wired so the Skia
  surface is backed *directly* by the swap-chain image (architecturally zero intermediate
  blit). Survives resize, focus loss, minimize/restore.
- **D3D11-to-GL interop** — `WGL_NV_DX_interop2` aliasing so decoded video textures land
  on the GPU without a CPU round-trip.

One `GrDirectContext` per render thread, never per-window or per-frame; torn down only on
shutdown or hard device loss, with cache rebuild on recovery.

### Selecting a GPU backend

By default skia-fx picks the best backend for the platform (**`AUTO`**). You can request
a specific one through a small additive API on `Application`:

```java
public enum GpuBackend { AUTO, OPENGL, DIRECT3D12, METAL, VULKAN }
```

The backend is chosen **once**, early in startup (the GPU context is built lazily on the
first window), so set it **before the first stage is shown**. The cleanest place is your
`Application`'s `init()` — it runs before `start()`, ahead of any window:

```java
public class MyApp extends Application {

    @Override
    public void init() {
        // Force Direct3D 12 on Windows. AUTO (the default) would also pick it
        // up when appropriate; an unavailable choice falls back automatically.
        Application.setGpuBackend(GpuBackend.DIRECT3D12);
    }

    @Override
    public void start(Stage stage) {
        // ... build your scene as usual ...
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
```

Setting it in `main()` before `launch(...)` works too:

```java
public static void main(String[] args) {
    Application.setGpuBackend(GpuBackend.OPENGL);
    launch(args);
}
```

Equivalent ways to choose, without code (precedence: **API call > system property > env
var > AUTO**):

```bash
# System property (accepts: auto, opengl/gl, direct3d12/d3d12/d3d, metal, vulkan/vk)
java -Dprism.skia.gpu.backend=d3d12 --module-path sdk/lib --add-modules javafx.controls -jar your-app.jar

# Legacy env var (D3D12 opt-in under AUTO)
set OPENJFX_SKIA_D3D=1
```

**Graceful fallback.** Requesting a backend that isn't available on the current
platform/build — `METAL` on Windows, `VULKAN` before its path lands, or `DIRECT3D12` when
device init fails — degrades to the most suitable backend instead of failing. The startup
log reports the backend that is **actually** active (and notes any fallback), for example:

```
INFO: Skia GPU enabled: Direct3D 12.
INFO: Requested GPU backend METAL was not available; using OpenGL (Ganesh GL) instead.
```

`METAL` (macOS) and `VULKAN` (Linux/Windows) are reserved for the per-platform backends
on the roadmap; until those native paths land they resolve to `AUTO`.

Try it from the Gradle panel:

```bash
./gradlew :samples:gradient:run                            # AUTO
./gradlew :samples:gradient:run -Pdemo.backend=DIRECT3D12  # force a backend
```

### Frame rate — yours, not 60
Stock JavaFX caps at vsync (~60Hz). skia-fx doesn't. Pulse cadence is driven by the
display's real refresh rate; an **uncapped** mode (`-Djavafx.animation.fullspeed=true`)
exists for benchmarks and real-time apps. Animations run off **wall-clock delta time**,
so they're correct whether you're at 30, 60, 144, or 360 fps.

**VSync is a runtime on/off toggle** — a public, observable property on `Application`,
global to every window, **on by default**:

```java
Application.setVsyncEnabled(false);             // present uncapped (may tear)
Application.setVsyncEnabled(true);              // back on (no tearing)
Application.vsyncEnabledProperty().bind(...);   // or bind it to a control
```

It's a *presentation* choice (whether the swap chain waits for the display), flipped
live via a per-present swap-chain flag — no pipeline rebuild, safe to toggle at any
time. Turning it off is how you actually exceed the display's refresh on a high-refresh
panel; leaving it on keeps presentation tear-free.

### Memory — bounded and observable
GPU resources live under **soft/hard budgets** with eviction policies (glyph atlas, image
atlas, standalone textures, render targets, picture cache), tunable via system properties.
Native handles use explicit `Cleaner`-registered lifecycles — **GPU frees are deferred to
the render thread**, never run on a daemon thread where they'd corrupt the GPU heap. The
render-thread hot path targets **zero per-frame heap allocation** via pooled objects and
per-frame confined off-heap arenas.

---

## Build system

One tool, one command. **Gradle 9.2** + **Java 25**, Groovy DSL, convention plugins in
`build-logic/`. No Ant, no Make, no shell orchestration, no Maven.

> **See [`BUILD.md`](BUILD.md) for the full build guide** — toolchain prerequisites and
> the native dependencies: a built **Skia** (`SKIA_HOME`), **bgfx** for 3D, **ffmpeg**
> headers for media, and — for the WebView — a **full Chromium source checkout** plus
> patches (an opt-in, multi-hour build). The summary below is the short version.

The normal development build needs a built Skia at `SKIA_HOME` (the repo does not build
Skia for you) and a C++ toolchain. The Chromium/Blink WebView engine is opt-in
(`-PbuildWebNative=true`) because its first build takes hours.

```bash
# Build everything: all modules + native libs + bundle into sdk/
./gradlew buildAll

# Run a sample on the Skia pipeline
./gradlew :samples:ensemble:runShowcase   # FXML dashboard: controls, custom title bar, FPS
./gradlew :samples:ensemble:runSvgDemo    # SVG: zoom, grid, tint, Open SVG
./gradlew :samples:ensemble:runDemo3D     # 3D scene-graph showcase (bgfx)
./gradlew :samples:ensemble:runModelDemo  # load a glTF 2.0 model (javafx.scene3d)
./gradlew :samples:ensemble:runWebView    # drive the Blink WebView
./gradlew :samples:ensemble:runDualPlayer # dual-source player (separate audio + video URLs)
./gradlew :samples:ensemble:runMixerDemo  # MediaMixer: audio + video files -> one MP4
./gradlew :samples:gradient:run           # gradient render test + GPU-backend selector (-Pdemo.backend=...)

# Compile a single module / just the native Skia bridge
./gradlew :javafx.graphics:assemble
./gradlew :javafx.graphics:nativeBuild

# Show every pinned version + active overrides (read this before filing a build issue)
./gradlew versions
```

The output `sdk/lib/` is a drop-in replacement for `javafx-sdk-25/lib/`. Point your
`--module-path` at it and run your existing app:

```bash
java --module-path sdk/lib --add-modules javafx.controls,javafx.fxml -jar your-app.jar
```

---

## Roadmap

**Working today**
- Skia GPU pipeline (OpenGL + D3D12), full 2D draw surface
- Full JavaFX effect set on Skia image filters
- Vector SVG (`SvgImage` / `SvgImageView`) — crisp at any zoom/DPI, zoom + tint + grid
- 3D scene graph via bgfx (meshes, `PerspectiveCamera`, lights, `PhongMaterial`, textures,
  MSAA, shadows) + glTF 2.0 model loading (`javafx.scene3d`)
- Media: hardware decode (H.264/H.265/VP9/AV1, up to 4K) with zero-copy video, broad
  container support (MP4/MP3/HLS stock; WebM/MKV/FLAC/AVI/FLV + any-container with
  ffmpeg), dual-source `Media(audio, video)` with accurate fragmented-MP4 seeking,
  `MediaMixer` muxing
- Uncapped / display-rate frame pacing, wall-clock animations, runtime VSync toggle
- Selectable GPU backend (`Application.setGpuBackend` / `-Dprism.skia.gpu.backend`,
  default `AUTO`) with graceful fallback and a truthful active-backend log
- Memory budgets + render-thread-safe native lifecycles
- Custom primary stage, signed native-lib manifest

**In active development**
- **WebView / Blink** — DOM, JSObject bridge, network interception, dialogs,
  downloads, permissions, history are landing; composited-layer fidelity, more chrome,
  and robustness hardening are ongoing.
- **Media** — Linux/macOS wiring for the new format tiers, subtitle tracks, and
  continued soak-hardening of the streaming paths.
- **Copy elimination and profiling** — `SkPicture` subtree caching, atlas tuning,
  persistent staging buffers, copy/memory instrumentation.
- **SVG animation** — SMIL / CSS-in-SVG playback (a render-thread-driven animation pass);
  today an animated SVG shows its static first frame.
- **3D** — the bgfx engine and glTF loading work today; skeletal/keyframe **animation**
  and **PBR** materials are the next increments.

**Later**
- **macOS and Linux desktop** — currently Windows x64 only; the per-platform Glass
  surface plumbing and backends (Metal on macOS) are in scope and planned next.
- iOS / Android.

---

## Requirements

- **Java 25 or newer. No exceptions.** skia-fx uses modern language and FFM
  (`java.lang.foreign`) features and does **not** support Java 24 or earlier — there are
  no `--release` fallbacks below 25.
- **Gradle 9.2** (provided via the wrapper; you don't need it pre-installed).
- A C++20 toolchain for native builds (auto-detected per platform), and a GPU/driver
  supporting OpenGL or Direct3D 12.
- **Platforms:** **Windows x64 only for now.** macOS and Linux (desktop) are planned
  and in scope but not yet built or tested. iOS/Android come later.

---

## Compatibility contract

| Surface | Stock OpenJFX 25 | skia-fx |
|---|---|---|
| JPMS module names | `javafx.base`, `javafx.controls`, ... | **identical** |
| Existing public API | `javafx.scene.*`, ... | **identical** (new types are additive) |
| Artifact JAR filenames | `javafx.base-25.0.0.jar` | **identical** |
| `Specification-Version` | `25` | **identical** |
| `--module-path` usage | unchanged | **works identically** |
| Native library names | `glass.dll`, `javafx_font.dll`, ... | **identical**, plus ours |
| `Implementation-Version` | upstream | `25.0.0-skia-fx-<overlay>` |

Existing applications run unchanged. New capabilities (such as `SvgImage` /
`SvgImageView`) are *added* to the API surface; they never alter or remove anything that
already shipped. If a change to existing public API ever looks necessary, the answer is:
**it isn't.** There's an internal-side path. That promise is the project.

> One launch-flag exception: apps using `JFXPanel` / `SwingNode` must add four
> `--add-exports` for `javafx.swing` (see the `javafx.swing` section above). The API,
> module name, and behavior are unchanged — only the launch command gains flags, because
> the JDK's `jdk.unsupported.desktop` module that supplied the interop glue is being
> removed.

---

## Credits and license

- **The OpenJFX / JavaFX team** — for the foundation this is built upon. Every module
  here that *isn't* the Skia renderer is their work, and the parts that are stand on it.
- **The Skia team** — for the graphics engine doing the painting (including its SVG module).
- **The Chromium team** — for the Blink engine powering the new WebView.

skia-fx is distributed under the **GPL v2 with the Classpath Exception**, the same
license as OpenJFX. See the `LICENSE` file.

<div align="center">

---

**skia-fx** — *the JavaFX you love, on an engine built for 2026.*

Built on the shoulders of OpenJFX.

</div>
