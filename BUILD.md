# Building skia-fx

> **EXPERIMENTAL — NOT STABLE.** This is a research/preview project. The build has
> heavy native dependencies and rough edges; expect it to change. **Windows x64 only
> for now** — macOS and Linux are planned but not yet built or tested.

This document is the practical "how to build it" guide. Architecture and per-subsystem
detail live in [`docs/`](docs/) (linked throughout).

One tool drives everything: **Gradle 9.2** (via the wrapper) on **Java 25**. There is no
Ant, Make, Maven, or shell orchestration. But several pieces are large **native**
dependencies that Gradle either fetches or expects you to point it at. The most
demanding by far is the WebView engine, which needs a **full Chromium source checkout**.

---

## 1. What you can build, and what each needs

skia-fx is layered. A Java-only build is quick; each native subsystem adds a dependency.

| Layer | Needs | Cost | Opt-in |
|---|---|---|---|
| Java modules (base, controls, fxml, graphics Java glue, …) | JDK 25 only | seconds–minutes | always |
| **2D Skia renderer** (`javafx.graphics` native) | a **built Skia** at `SKIA_HOME` + C++ toolchain | minutes | when `SKIA_HOME` is set |
| **SVG** (`SvgImage`/`SvgImageView`) | Skia built with its **SVG module** (see §4) | seconds (archived from Skia objects) | auto, when the SVG objects exist |
| **3D** (`javafx.scene3d`, bgfx engine) | **bgfx** source (fetched) | minutes | when the 3D natives are built |
| **Media** (`javafx.media`) | **ffmpeg public headers** (fetched) | seconds | when headers are available |
| **WebView** (`javafx.web`, Blink engine) | **full Chromium source** + depot_tools + patches | **hours (first build)** | `-PbuildWebNative=true` |

A build with `SKIA_HOME` set but `-PbuildWebNative` off gives you the full 2D/3D/SVG
toolkit without the multi-hour Chromium step. That is the normal development build.

---

## 2. Prerequisites

### Common
- **JDK 25** — the toolchain. The Gradle daemon runs on Java 17+, but the project targets
  Java 25 (it uses FFM / `java.lang.foreign`); there are no `--release` fallbacks below 25.
- **Gradle 9.2** — provided by the wrapper (`./gradlew`); you do not install it.
- **CMake 3.28+** and **Ninja 1.11+** — drive the native builds.
- **A C++20 toolchain** — see per-platform notes.

### Windows (the supported platform today)
- **Visual Studio 2022** (any edition) — provides the Windows SDK, `cl.exe`/`clang-cl`,
  `link.exe`, `lib.exe`, and `vcvars64.bat`. The build auto-locates the MSVC tools via
  `vswhere.exe` (ships with VS); you can override the archiver with `-PlibExe=<path>`.
- A GPU/driver supporting **Direct3D 12** (preferred) and/or **OpenGL**.

> macOS (Apple clang + Metal) and Linux (gcc/clang + GL) toolchains are in scope; the
> convention plugins already branch per platform, but those targets are not built/tested
> yet.

---

## 3. Quick start

```bash
# 1. Point at a built Skia (see Section 4 to produce one)
set SKIA_HOME=F:\DEV\skia            # Windows (or pass -PskiaHome=... to any task)

# 2. Build everything except the Chromium WebView (the normal dev build):
./gradlew buildAll                   # modules + native (Skia, 3D) + bundle into sdk/

# 3. Run a sample on the Skia pipeline
./gradlew :samples:ensemble:runShowcase    # FXML dashboard: controls, FPS
./gradlew :samples:ensemble:runSvgDemo     # SVG: zoom, grid, tint, Open SVG
./gradlew :samples:ensemble:runDemo3D      # 3D scene graph (bgfx)
./gradlew :samples:ensemble:runModelDemo   # load a glTF 2.0 model
```

`buildAll` writes `sdk/lib/`, a **drop-in replacement** for `javafx-sdk-25/lib/`:

```bash
java --module-path sdk/lib --add-modules javafx.controls,javafx.fxml -jar your-app.jar
```

To include the WebView engine, add `-PbuildWebNative=true` — but read Section 7 first; the
first Chromium build takes hours and needs a large checkout.

---

## 4. Skia (required for the renderer)

skia-fx links its own native bridge against a **prebuilt Skia static library**. The repo
does **not** download or build Skia for you — you provide a Skia checkout/build and point
`SKIA_HOME` at it (env var, or `-PskiaHome=<dir>` on any task).

`SKIA_HOME` must contain:
- `include/` — Skia public + private headers (the bridge includes `include/...`,
  `modules/...`, and `src/...`).
- `out/Release/skia.lib` — a **static** (non-component) Skia archive with direct symbols.

### Recommended Skia GN args

Build Skia (with its own `gn` + `ninja`, in `SKIA_HOME/out/Release`) using args that match
what the bridge expects — static, with the backends and modules skia-fx uses:

```
is_official_build = false
is_debug = false
target_cpu = "x64"
is_component_build = false      # static skia.lib with direct (non-import) symbols
skia_use_gl = true
skia_use_direct3d = true        # Windows GPU backend used by the bridge
skia_use_freetype = true        # custom font manager / typeface-from-data
skia_use_libwebp_encode = true
skia_use_libwebp_decode = true
skia_use_expat = true           # SVG XML parsing (SkDOM / SkXMLParser)
skia_enable_svg = true          # the SVG module behind SvgImage/SvgImageView
```

Then `gn gen out/Release` and `ninja -C out/Release`.

### SVG static libs (automatic)

Skia compiles its SVG module (`modules/svg`) and the `skshaper` / `skunicode` /
`xml` pieces but does not merge them into `skia.lib`. The **`prepareSkiaSvg`** Gradle task
(run automatically before the native configure) archives them into thin, non-component
static libs (`openjfx_svg` / `openjfx_skshaper` / `openjfx_skunicode`) that link cleanly
alongside `skia.lib`. It is idempotent and degrades gracefully:

- SVG objects already compiled (Skia built with `skia_enable_svg`) → it just archives them.
- Objects missing → pass **`-PbuildSkiaSvg=true`** to have the task run `gn gen` + `ninja`
  itself, or build Skia with the args above and re-run.
- Objects missing and not requested → the build still succeeds; `SvgImage`/`SvgImageView`
  simply report a load error and render nothing.

Full detail: [`docs/MODULE_SVG.md`](docs/MODULE_SVG.md).

---

## 5. 3D engine (bgfx)

The 3D scene graph and `javafx.scene3d` render through **bgfx** (BSD-2), built from source
(its desktop D3D12/Vulkan/Metal backends are in-tree). bgfx lives in git submodules, so it
is fetched with `git clone --recursive`, not a tarball.

Resolution order for the bgfx tree (first hit wins):
1. `-PbgfxDir=<dir>`
2. `OPENJFX_BGFX_DIR` environment variable
3. the **`fetchBgfx`** task — clones `bkaradzic/bgfx.cmake` at the pinned ref (recursive)
   into `build/generated/bgfx/`.

The 3D CMake guards on the directory existing, so a build without bgfx still succeeds (the
3D pipeline is simply absent). 3D needs the **D3D12** backend (bgfx shares Skia's D3D12
device); the demos set `OPENJFX_SKIA_D3D=1`. Detail: [`docs/3D.md`](docs/3D.md).

---

## 6. Media (ffmpeg headers)

`javafx.media` uses ffmpeg via a **runtime dynamic loader** — it `LoadLibrary`s
`avcodec-*.dll` etc. at startup. The build only needs ffmpeg **public headers** (struct
layouts / signatures), never an ffmpeg `.lib`.

Resolution order (first hit wins):
1. `-PffmpegIncludeDir=<dir>`
2. `OPENJFX_FFMPEG_INCLUDE_DIR` environment variable
3. the **`fetchFfmpegHeaders`** task — downloads the pinned ffmpeg source tarball,
   verifies its SHA-256, and extracts the `libav*` headers under `build/generated/ffmpeg/`.

Without headers, `ffmpegwrapper` is skipped at the CMake level and codec routing falls back
to platform backends at runtime. Decode strategy is controlled at runtime via
`Media.setDecodeMethod(...)` / `Media.setFfmpegDirectory(...)`. Detail:
[`docs/DUAL_SOURCE_MEDIA.md`](docs/DUAL_SOURCE_MEDIA.md),
[`docs/MEDIA_COLOR_PIPELINE.md`](docs/MEDIA_COLOR_PIPELINE.md).

---

## 7. WebView engine (Chromium / Blink) — the heavy one

> **This requires a full Chromium source checkout and a multi-hour first build.** It is
> **opt-in** (`-PbuildWebNative=true`); leave it off for normal development. A Java-only
> build skips the engine compile but still packs any previously-built engine.

`javafx.web` no longer uses WebKit. The WebView is a **from-scratch Chromium/Blink engine**
(internally *jux engine*, artifacts named `skia-fx-webview.{dll,exe,pak}`), pinned to
**Chromium 147.0.7727.56**, rendered off-screen and composited into the JavaFX scene. Full
architecture: [`docs/BLINK_INTEGRATION.md`](docs/BLINK_INTEGRATION.md).

### What "full Chromium source + patches" means

Building Blink needs the **entire Chromium checkout** (the `chromium/src` tree, fetched
with Google's **depot_tools** + `gclient sync` + runhooks). skia-fx adds three things on
top of that checkout, all tracked in this repo under `javafx.web/src/main/native-blink/`:

- **`engine/`** — our C++ engine overlay (the off-screen Blink host, frame transport, OSR
  input, DOM/JS bridge, network interception). Copied into `chromium/src/jux/`.
- **`stubs/`** — replacement source files for the Google services we **prune** (sign-in,
  sync, metrics, etc.); each top-level dir under `stubs/` is merged into `chromium/src/`.
- **`patches/`** — reproducible **unified-diff patches** applied to the Chromium tree
  (e.g. `//chrome/...` edits needed for the off-screen print-preview and content wiring).

The build is multi-process Blink; `chromium.*` properties in `gradle.properties` pin the
version/channel/build type and toggle features (PDF, printing, print-preview, codecs).

### Workspace

Fully isolated and gitignored. Default `<repoRoot>/.chromium` (override with
`-PchromiumHome=<dir>`):

```
<chromiumHome>/depot_tools/
<chromiumHome>/chromium/src/                 the checkout
<chromiumHome>/chromium/src/jux/             our engine overlay (copied from engine/)
<chromiumHome>/chromium/src/out/skiafxweb/   ninja output
```

### Tasks (group `chromium` / `blink`)

```bash
# Fresh machine: download depot_tools, fetch + checkout the pinned Chromium,
# gclient sync, run hooks. (Large download; long.)
./gradlew setupEnv

# OR, if you already have a compatible checkout to copy from:
./gradlew seedChromium

# Copy engine/ + stubs/ into the tree, apply patches, write args.gn, run gn gen:
./gradlew configureBuild

# Compile the engine (ninja -C out/skiafxweb jux_all). First build: hours.
./gradlew buildNatives

# Then a normal build that includes + packs the engine:
./gradlew buildAll -PbuildWebNative=true

# Drive the WebView:
./gradlew runWebView
```

`pullVersions` lists available Chromium releases (ChromiumDash). See
`docs/BLINK_INTEGRATION.md` and `docs/WEB_MODULE_BUILD.md` for the phase status,
prerequisites per OS, and troubleshooting.

---

## 8. Build commands (reference)

```bash
# Top level
./gradlew buildAll        # all modules + native libs + bundle sdk/
./gradlew assemble        # all artifacts, no tests
./gradlew check           # verification (tests, etc.)
./gradlew clean           # wipe build outputs
./gradlew versions        # every pinned version + active overrides (read before filing issues)

# Single module / just native
./gradlew :javafx.graphics:assemble        # compile the graphics module (Java)
./gradlew :javafx.graphics:nativeBuild     # build the native Skia bridge
./gradlew :javafx.graphics:collectIntoSdk  # stage jar + native libs into sdk/

# Dependency fetch (idempotent)
./gradlew fetchBgfx
./gradlew fetchFfmpegHeaders
./gradlew prepareSkiaSvg                    # archive the SVG static libs from Skia

# SVG / 3D verification + demos
./gradlew :samples:ensemble:runSvgSmoke        # headless SVG parse/size test
./gradlew :samples:ensemble:runSvgRenderTest   # headless tint/resize render test
./gradlew :samples:ensemble:runSvgLiveTest     # live-repaint test
./gradlew :samples:ensemble:runSvgDemo         # interactive SVG showcase
./gradlew :samples:ensemble:runDemo3D          # 3D scene graph
./gradlew :samples:ensemble:runModelDemo       # glTF model loading
```

---

## 9. Useful properties and overrides

Set on the CLI (`-Pname=value`) or in `gradle.properties`:

| Property / env | Effect |
|---|---|
| `SKIA_HOME` / `-PskiaHome=` | location of the built Skia (required for the renderer) |
| `-PbuildSkiaSvg=true` | let `prepareSkiaSvg` run `gn`+`ninja` if the SVG objects are missing |
| `-PlibExe=<path>` | explicit MSVC `lib.exe` if `vswhere` can't find it |
| `-PbgfxDir=` / `OPENJFX_BGFX_DIR` | prebuilt/vendored bgfx tree (skip `fetchBgfx`) |
| `-PffmpegIncludeDir=` / `OPENJFX_FFMPEG_INCLUDE_DIR` | ffmpeg headers (skip the fetch) |
| `-PbuildWebNative=true` | build/include the Chromium/Blink WebView engine |
| `-PchromiumHome=<dir>` | Chromium workspace location (default `<repo>/.chromium`) |
| `chromium.*` (in `gradle.properties`) | pinned Chromium version/channel/build type + feature toggles |

Runtime (not build) toggles worth knowing: `-Djavafx.animation.fullspeed=true` (uncapped
frame rate) and `Application.setVsyncEnabled(false)` (present uncapped / allow tearing).

---

## 10. Notes

- **Configuration cache is on** (`gradle.properties`); custom tasks are written to be
  config-cache-clean. If you add a task, keep it so (no `Project` access at execution).
- **Reproducibility:** the Gradle wrapper, JDK toolchain, and dependency versions are
  pinned; `./gradlew versions` prints them. The native dependencies (Skia, Chromium) are
  pinned by the version/args documented above.
- **First builds are slow, incremental is fast:** the native pieces (Skia, bgfx, and
  especially Chromium) dominate a cold build; Ninja makes subsequent rebuilds quick.
- This is a moving target — if something here drifts from reality, the per-subsystem docs
  in [`docs/`](docs/) are the authoritative source, and `./gradlew versions` is ground
  truth for what is pinned.
