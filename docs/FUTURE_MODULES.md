# Future Modules — `javafx.pdf` & `javafx.svg`

**Status: planned, post-core. Not yet built. This doc exists so the core
Skia pipeline we are building now does not paint itself into a corner that
makes these modules expensive or impossible later.**

Two new, optional JavaFX modules are on the roadmap. Both are *consumers* of
the Skia render surface the core pipeline already exposes — neither owns a GPU
context, and neither changes the public `javafx.scene.*` API.

| Module | One-line | Full spec |
|--------|----------|-----------|
| `javafx.svg` | `SvgImage` — a scene-graph **primitive** (a `Region`) that loads any SVG and renders it through Skia, crisp at any size/DPI. Drop-in graphic for buttons/menus/tabs. | [MODULE_SVG.md](MODULE_SVG.md) |
| `javafx.pdf` | `PdfViewer` — a single `Control` backed by native **PDFium + Skia**: view/zoom/forms/annotations/text-select/search/save. JavaFX is a thin observable mirror. | [MODULE_PDF.md](MODULE_PDF.md) |

They are **independent of each other** — no shared code, no cross-dependency.
They share only the core's Skia surface and native-loading infrastructure.

---

## 1. Why document them now

We are mid-flight on the core pipeline (phases 1–4 of the project roadmap). Both
modules lean on a small set of *core* capabilities. If we build those
capabilities in a way that only serves the current scene-graph/WebView use
cases, retrofitting them for PDF/SVG later is painful. The goal of this doc is
to name those load-bearing hooks **once**, so each is built general enough the
first time.

This is the same discipline the specs themselves apply ("decide undo/redo
now", "build the coordinate transform first") — applied one level up, to the
boundary between these modules and the core.

---

## 2. Core capabilities both modules depend on

These already exist or are in flight in `javafx.graphics`. Each is a contract
these future modules will rely on — keep them stable and general.

### 2.1 Host-surface access — `com.sun.prism.skia.SkiaSurfaceAccess`

Both specs assume "the host surface hands me an `SkCanvas`/surface handle; I
draw into it; the surface decides zero-copy GPU vs. CPU fallback." That hook
exists today:

- `SkiaSurfaceAccess.handleOf(Graphics)` → native SkSurface handle (or `0`
  for non-Skia pipelines).
- `save / clipPath / restore` helpers drive the live `SkCanvas` state stack
  without allocating a nested surface.

It was introduced for `javafx.web`. **`javafx.svg` is its second consumer**,
and a clean one: `SvgImage` is the canonical "external module that owns a
`Node`, wants to draw native Skia content into the node's slot." When building
the SVG module, generalize `SkiaSurfaceAccess` only as far as a second consumer
demands — don't pre-abstract.

**Hard rule it must respect (already learned the hard way with WebKit):** the
surface handle is **render-thread-scoped**. Drawing into it off the render
thread, or after the backing `RTTexture` is disposed, corrupts the GPU heap.
See memory note *webkit-skia-direct-is-record-time-unsafe* and
`docs/WEBKIT...`/`WEB_MODULE_BUILD.md`. Both module designs already honor this:

- **SVG** rasterizes inside the node's `layoutChildren`/paint path on the
  render thread, and caches the result; no per-frame, no off-thread draw into
  the live canvas. Off-thread work is limited to *parsing* (`SkSVGDOM`), which
  touches no GPU surface.
- **PDF** never lets pixels cross JNI at all: heavy `FPDF_RenderPageBitmap`
  rasterization runs on a worker thread into a native bitmap, wrapped as an
  `SkImage`/texture, and only the **opaque image reference** is posted back to
  the FX thread for compositing. This is strictly safer than the SVG path and
  needs nothing new from `SkiaSurfaceAccess`.

### 2.2 Native library loading — `com.sun.glass.utils.NativeLibLoader`

Both modules ship a native lib (`javafx_svg`, `javafx_pdf`). They must load it
exactly the way the core libs do:

- `NativeLibLoader.loadLibrary("javafx_pdf")` — handles in-jar extraction,
  full-path dev runs, and `System.loadLibrary` fallback.
- Per-module `checksums.properties` SHA-256 manifest at the jar root, with
  `.sha256` sidecar override support in `~/.skia-fx/cache` (memory note:
  *native-checksums-manifest*). PDFium and the SVG shim are third-party native
  surfaces running in-process — they get the **same** checksum scrutiny as
  Skia under the project's version-pinning and dependency-verification rules.

No new loader machinery is needed; both modules are additional clients.

### 2.3 Cleaner-based native lifecycle

Every native handle these modules hold (`FPDF_DOCUMENT`, page bitmaps, text
pages, fonts, `SkSVGDOM`) follows the project-wide rule (memory notes
*skiamediatexture-memory-layout*, *always-verify-no-leaks*):

- Hold a `MemorySegment`/pointer, register a `Cleaner` action at creation, no
  `finalize()`.
- Provide an explicit `close()`/`dispose()` for deterministic release
  (`PdfDocument` is `AutoCloseable`; `SvgImage.dispose()`).
- Bounded, LRU-evicted caches (PDF tile/bitmap cache; SVG parse + render
  caches). This is the **same budget/eviction discipline** as the glyph/image
  atlases in the project's memory-management policy — these modules' caches should
  surface their budgets through the same config-property convention
  (`javafx.svg.renderCacheBytes`, `javafx.pdf.tileCacheBytes`, TBD names).

### 2.4 Errors degrade, never abort

Per the hard rule (memory note *errors-never-kill-jvm*): a corrupt PDF, a
billion-laughs SVG, a PDFium failure, or device loss must surface as a
catchable Java exception / observable `error` property and a safe fallback
(render nothing / placeholder), never `abort()`/SIGSEGV. Both specs build this
in (PDF registry-ids so stale handles fail safely; SVG `error` property +
sandboxed parse mode for untrusted input).

### 2.5 CSS metadata pattern

Both modules are CSS-styleable through the standard `CssMetaData` mechanism
already used across `javafx.controls`. Nothing core-side is needed; noted only
so the styling surface (`-svg-tint`, `-pdf-page-gap`, …) is designed against
the existing `StyleableProperty` plumbing, not a bespoke one.

---

## 3. What the core must NOT do (forward constraints)

1. **Don't make `SkiaSurfaceAccess` WebKit-specific.** It is the general
   "external module draws into a node's Skia surface" boundary. Keep its
   contract about *surfaces and clip state*, not about WebView.
2. **Don't assume a single native consumer in `NativeLibLoader` /
   checksum manifest.** Multiple optional modules will each carry their own
   native lib + manifest.
3. **Don't hardwire the zero-copy-vs-CPU-fallback decision into call sites.**
   The SVG spec's whole premise is "the surface decides." Keep that decision
   inside the surface/`SkiaGraphics`, reachable by external draw callbacks, so
   `SvgImage` (and later `PdfPageNode`) inherit it for free.
4. **Don't let the coordinate / DPI transform logic stay private to one
   module.** The PDF module needs a bidirectional PDF-points ↔ screen
   transform (its phase 0); HiDPI device-pixel sizing is also what SVG keys its
   render cache on. The per-monitor backing-scale + Y-flip primitives should
   live somewhere reusable, not buried in one node.

---

## 4. Build ordering relative to current phases

These slot **after** the core 2D Skia pipeline is solid (roadmap phase 2–3),
because both consume a working GPU surface + CPU fallback.

| When | Module | Rationale |
|------|--------|-----------|
| After phase 2 (GPU Skia via Ganesh) is stable, before/alongside phase 3 polish | **`javafx.svg`** | Smallest, lowest-risk. Pure consumer of `SkiaSurfaceAccess`; no forms, no worker threads, no document model. Validates the "external module draws native Skia into a Node" path end-to-end and shakes out `SkiaSurfaceAccess` generality. Good proving ground before PDF. |
| After `javafx.svg` proves the surface hook, as its own track | **`javafx.pdf`** | Large (PDFium engine, forms, annotations, undo/redo, threading). Follows its own internal phase plan ([MODULE_PDF.md §16](MODULE_PDF.md)), starting with the coordinate transform + threading/lifecycle contract. Independent of SVG. |

Neither blocks core work; both are additive modules registered in
`settings.gradle` (flat layout) applying the existing `build-logic` convention
plugins, exactly like `javafx.web`.

---

## 5. Open cross-module questions (decide when we start)

- **PDFium provenance & build:** fetched prebuilt (like Skija) vs. built from
  source under Gradle/CMake. Whichever, it obeys the project's pinned-version +
  checksum-verification rules. (Spec drops V8/JS — no V8 build
  needed, which keeps the PDFium footprint smaller.)
- **SVG engine:** Skia's built-in `SkSVGDOM` (per spec) vs. a richer parser.
  Start with `SkSVGDOM`; it renders a static frame (no SMIL animation — out of
  scope) and is reasonably contained for the sandboxed-parse story.
- **Where the shared coordinate/DPI transform lives** (see §3.4) — a small
  `com.sun.javafx.geom`-adjacent utility vs. per-module. Decide when the SVG
  render-cache DPI key and the PDF transform are both being written.

---

*Provenance: distilled from the two module design specs authored 2026-06-06,
preserved verbatim alongside this doc as `MODULE_SVG.md` and `MODULE_PDF.md`.
This file is the architectural glue: it records how they attach to the skia-fx
core and what the core must keep general for them.*
