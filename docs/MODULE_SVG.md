# SVG support — `SvgImage` / `SvgImageView`

> **Status: IMPLEMENTED (static SVG).** Lives in the core `javafx.graphics`
> module, public package `javafx.scene.image`. Animations are NOT yet played
> (see §7). This file documents the real implementation; it supersedes the
> earlier standalone-`javafx.svg`/`extends Region` design sketch.

`skia-fx` renders SVG as a first-class, resolution-independent **`Image`**:

| Type | Extends | Package | Role |
|------|---------|---------|------|
| `SvgImage` | `javafx.scene.image.Image` | `javafx.scene.image` | parsed SVG document (a vector image) |
| `SvgImageView` | `javafx.scene.image.ImageView` | `javafx.scene.image` | crisp, zoomable, styleable view of an `SvgImage` |
| `SvgImageView.TintMode` | enum | — | `NONE` / `SRC_IN` / `MULTIPLY` |

Because the source is vector data, the view **re-rasterizes through Skia at the
exact device-pixel size it is drawn at** — crystal-clear at any size, zoom
level, or DPI, with no upscaling blur. `SvgImageView` is a `Node`, so it drops
into any graphic slot (buttons, labels, menu items, tabs).

```java
SvgImage icon = new SvgImage("/icons/save.svg");   // classpath / file / URL
button.setGraphic(new SvgImageView(icon));         // crisp 24px icon

SvgImage art = SvgImage.ofContent("<svg viewBox='0 0 24 24'>…</svg>");
SvgImageView v = new SvgImageView(art);
v.setFitWidth(400); v.setPreserveRatio(true);
v.setMaxZoom(40); v.setGridVisible(true);
scrollPane.setContent(v);
```

Demo: `./gradlew :samples:ensemble:runSvgDemo` (busy SVG + zoom/grid/tint/icons).
Headless smoke: `./gradlew :samples:ensemble:runSvgSmoke`.

---

## 1. Architecture

```
SvgImage (javafx.scene.image)
  └─ parses SVG once → native SkSVGDOM handle (Cleaner-owned), intrinsic size
SvgImageView extends ImageView
  ├─ SvgImageViewHelper (extends NodeHelper directly — bypasses ImageView's
  │    NGImageView-casting peer path)
  └─ NGSvgImageView (NGNode peer)
        └─ each frame: device size = logical size × transform scale
           → re-rasterize SVG into a GPU RTTexture (cached, grow-only)
           → draw the device-sized texture into the logical rect (CTM cancels)
Native bridge (openjfx_skia_bridge.cpp)
  ├─ svg_parse(utf8) → handle           (SkSVGDOM::Builder, copies bytes)
  ├─ svg_get_size(handle) → w,h          (container size, else viewBox)
  ├─ svg_render_in_place(surface,handle,x,y,w,h, bg,tint,tintMode, grid…)
  │     background → SVG → optional tint → grid overlay  (drawn under the
  │     live device transform; vector, no intermediate texture)
  └─ svg_destroy(handle)                 (poison-on-free; stale use rejected)
```

**Crisp at any zoom/DPI:** the peer reads the render transform's scale, sizes
the offscreen target in device pixels, renders the SVG into it, then draws it
into the logical rectangle — the CTM scale and the device/logical ratio cancel,
landing one texel per device pixel. A DPI change or zoom step changes the
device size, which triggers a fresh vector rasterization.

**Fast zoom (and future PDF):** the GPU render target is reused across frames
and only *grows* (zooming back out / steady redraw reuses it; only a new zoom
peak reallocates). It is composited with a direct GPU surface blit — no
read-back. A re-render happens only when something pixel-affecting changes
(device size, SVG, tint, background, grid). At most one target per peer; freed
on `release()`.

**Memory / safety:** the `SkSVGDOM` handle is owned by `SvgImage` and released
via a `Cleaner` (or `dispose()`); the peer only borrows it. Native handles are
magic-guarded and poisoned on free, so a stale/double-freed handle is rejected
(returns an error / draws nothing) rather than dereferenced — no use-after-free.
The parse copies the input bytes, so no Java buffer is pinned across the FFM
boundary.

---

## 2. `SvgImage`

| Member | Notes |
|--------|-------|
| `SvgImage(String url)` | resource path / file / URL (same rules as `Image(String)`) |
| `SvgImage(InputStream)` | reads markup from a stream |
| `static SvgImage.ofContent(String)` | raw SVG markup |
| inherited `getWidth()/getHeight()` | intrinsic size (width/height attrs or viewBox) |
| inherited `isError()/getException()` | parse/load failure (renders nothing, never throws on bad I/O) |
| `dispose()` | eager native release (also automatic via `Cleaner`) |

A failed or unsupported parse sets the `error` property and renders nothing —
it never crashes. The same `SvgImage` can be shared by many `SvgImageView`s;
each rasterizes independently at its own size.

## 3. `SvgImageView` properties

| Property | CSS | Default | Notes |
|----------|-----|---------|-------|
| `svgImage` | — | null | the source `SvgImage` |
| `fitWidth` / `fitHeight` / `preserveRatio` | (inherited) | 0 / 0 / false | base display size from intrinsic size |
| `zoom` | `-svg-zoom` | 1.0 | display multiplier, clamped to `[minZoom,maxZoom]` |
| `minZoom` / `maxZoom` | `-svg-min-zoom` / `-svg-max-zoom` | 0.1 / 64 | zoom bounds |
| `tint` | `-svg-tint` | null | node-level recolor (never edits SVG paint) |
| `tintMode` | `-svg-tint-mode` | `NONE` | `NONE` / `SRC_IN` / `MULTIPLY` |
| `backgroundColor` | `-svg-background-color` | null | solid fill behind the SVG |
| `gridVisible` | `-svg-grid-visible` | false | grid overlay on/off (drawn over the SVG) |
| `gridColor` | `-svg-grid-color` | translucent gray | grid line color |
| `gridSpacing` | `-svg-grid-spacing` | 16 | cell size (unzoomed px; scales with zoom) |
| `gridLineWidth` | `-svg-grid-line-width` | 1 | line thickness (px) |

Convenience: `zoomIn()`, `zoomOut()`, `resetZoom()`, `getEffectiveZoom()`.

```css
.svg-image-view:hover   { -svg-tint: #4a90d9; -svg-tint-mode: src-in; }
.svg-image-view         { -svg-grid-color: rgba(128,128,128,0.35); }
```

The **grid** draws over the SVG as an overlay (background → SVG → tint → grid),
so it's visible on any artwork (opaque or not); it scales with
zoom like graph paper, and is fully styleable (color, spacing, line width).

---

## 4. What's supported (static SVG: comprehensive)

Rendered by Skia's `modules/svg` (`SkSVGDOM`):

- All basic shapes & `<path>`; fills, strokes, dash, caps/joins, fill-rule.
- **Colors / paint:** solid, `opacity`/`fill-opacity`/`stroke-opacity`, linear &
  radial **gradients**, **patterns**.
- **Clipping & masking:** `clipPath`, `mask`.
- **Filter effects:** `feGaussianBlur`, `feColorMatrix`, `feBlend`, `feComposite`,
  `feFlood`, `feImage`, `feMerge`, `feMorphology`, `feOffset`, `feTurbulence`,
  `feDisplacementMap`, lighting — i.e. the SVG filter primitive set.
- **Transforms**, groups, `use`/`defs`, symbols.
- **Text** (`<text>`): rendered with real system fonts (DirectWrite on Windows)
  and shaping, so text appears with correct font/size/position. Complex-script
  shaping uses Skia's primitive shaper.
- CSS presentation attributes / inline style.

## 5. Native build (how the SVG libs are produced)

The bridge statically links Skia's SVG module. Skia compiles `modules/svg` (and
`skshaper` / `skunicode` / `xml`/`SkDOM`) but does **not** merge them into the
core `skia.lib`, so the build archives each module's own objects into **thin,
non-component** static libs that link cleanly (no duplicate symbols):

- `openjfx_svg.lib`  ← `modules/svg` + `src/xml` (SkDOM) objects
- `openjfx_skshaper.lib`, `openjfx_skunicode.lib`
- plus `skresources` / `harfbuzz` / `icu` / `expat` (linked, not re-archived)

`CMakeLists.txt` links these **before** `skia.lib` (MSVC resolves a library's
core references from libraries listed *after* it) and defines
`OPENJFX_WITH_SKIA_SVG`. If the SVG archive is absent the bridge still builds and
the `svg_*` entry points become no-ops (SvgImage reports a load error).

### `buildAll` is self-contained

`./gradlew :javafx.graphics:prepareSkiaSvg` (run automatically before
`nativeConfigure`) archives the thin libs from Skia's compiled objects. It is
idempotent (skips when up to date) and degrades gracefully.

**Skia must be built with these gn args** (`out/Release/args.gn`):

```
is_component_build = false      # static skia.lib with direct (non-import) symbols
skia_use_gl = true
skia_use_direct3d = true        # Windows GPU backend used by the bridge
skia_use_freetype = true        # custom font manager / typeface-from-data
skia_use_libwebp_encode = true
skia_use_libwebp_decode = true
skia_use_expat = true           # SVG XML parsing (SkDOM / SkXMLParser)
skia_enable_svg = true
```

If Skia hasn't been built with the SVG module, run `prepareSkiaSvg` with
`-PbuildSkiaSvg=true` (drives `gn gen` + `ninja`), or build Skia manually with
the args above. Override the archiver with `-PlibExe=<path>` if `vswhere`
can't locate `lib.exe`.

## 6. Frame-rate / DPI behavior

Re-rasterization is driven by the pulse; it follows the project's
variable-framerate, wall-clock model. Each distinct device size is rasterized
once and cached, so steady-state redraw and pan are free; only zoom/DPI changes
re-render. Works at any per-window DPI (the device scale comes from the live
render transform).

## 7. Not yet supported — animations

`SkSVGDOM` renders a **static frame**: SMIL (`<animate>`, `<animateTransform>`,
`<animateMotion>`, `<set>`) and CSS-in-SVG animation are parsed but **not
played** — an animated SVG shows its initial state.

**Planned approach (Skia-native, reuses this pipeline):** a SMIL/animation
driver that, on each pulse, computes the current-time value of each animation
element and applies it to the `SkSVGDOM` nodes via `SkSVGNode::setAttribute`,
then re-renders (the peer already re-renders cheaply on change, driven by
wall-clock time per the frame-rate policy). Increment 1 would cover
`animateTransform` (rotate/scale/translate) and `animate` on numeric/color
attributes — enough for spinners, loaders, and most animated icons — with the
SMIL timing model (`begin`/`dur`/`repeatCount`/`keyTimes`/`values`/`calcMode`,
`additive`/`accumulate`) filled in incrementally. (For designer-authored motion,
Skia's `modules/skottie` Lottie player is the richer alternative, exposed as a
separate node.)

## 8. Out of scope

- SVG scripting (`<script>`), external/remote resource fetching (`<image href>`
  to network), interactive event handling inside the SVG.
- Editing the SVG DOM (this is a render/display node).
