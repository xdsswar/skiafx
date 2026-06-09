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
| **Uncapped frame rate** — render at the display's *actual* refresh (120/144/240Hz+), not a hardcoded 60 | Working |
| **Runtime VSync toggle** — `Application.setVsyncEnabled(...)` / `vsyncEnabledProperty()`, global, on by default | Working |
| **Wall-clock animation** — animations driven by real time, correct at any cadence | Working |
| **Full effect suite on Skia** — every JavaFX `Effect` mapped to Skia image filters: blur, shadows, glow, color adjust, displacement, lighting and more | Working |
| **Crisp text** — Skia glyph rendering with anti-alias tuned for translucent layers; color-emoji groundwork | Working |
| **Vector SVG** — `SvgImage` / `SvgImageView`, pixel-perfect at any zoom/DPI, zoom + tint + grid, CSS-styleable | Working |
| **Bounded, observable memory** — GPU budgets, eviction policies, `Cleaner`-driven native lifecycles | Working |
| **Copy elimination** — architecturally zero-copy hot paths, per-frame off-heap arenas, no per-frame `new` | Ongoing |
| **Brand-new WebView** — a from-scratch **Chromium/Blink** engine, off-screen-rendered straight into the scene | Heavy WIP |
| **Modern media** — ffmpeg-backed decode, CPU/GPU/AUTO selection, HDR tone-mapping, zero-copy D3D11 video | Ongoing |
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

### `javafx.media`
A modernized media stack:

- **`Media.setDecodeMethod(AUTO | CPU | GPU | GPU_PREFERRED)`** and
  **`Media.setFfmpegDirectory(...)`** — first-class, public control over decode strategy
  and codec provenance, backed by the `skia.media.decode` system property.
- **ffmpeg-backed decode** with a CPU path (libdav1d preferred for AV1) and a GPU path,
  selected at runtime.
- **`SkiaMediaTexture`** — pooled scratch buffers, `Cleaner`-managed native handles,
  YUV I420 / HDR upload, **HDR tone-mapping** (BT.2390), and a **D3D11 zero-copy**
  interop path for video frames straight onto the GPU.

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
./gradlew :samples:ensemble:runShowcase  # FXML dashboard: controls, custom title bar, FPS
./gradlew :samples:ensemble:runSvgDemo   # SVG: zoom, grid, tint, Open SVG
./gradlew :samples:ensemble:runDemo3D    # 3D scene-graph showcase (bgfx)
./gradlew :samples:ensemble:runModelDemo # load a glTF 2.0 model (javafx.scene3d)
./gradlew :samples:ensemble:runWebView   # drive the Blink WebView

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
- Uncapped / display-rate frame pacing, wall-clock animations, runtime VSync toggle
- Memory budgets + render-thread-safe native lifecycles
- Custom primary stage, signed native-lib manifest

**In active development**
- **WebView / Blink** — DOM, JSObject bridge, network interception, dialogs,
  downloads, permissions, history are landing; composited-layer fidelity, more chrome,
  and robustness hardening are ongoing.
- **Media** — broader codec coverage, fuller GPU decode + zero-copy present.
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
