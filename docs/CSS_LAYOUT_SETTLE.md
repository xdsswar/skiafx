# CSS settle after layout — fixing the "black avatar while scrolling a ListView"

## Symptom

In a `ListView` (or any virtualized control) with a **cell factory that builds
new CSS-styled nodes in `updateItem`**, cells that are recycled *while
scrolling* flash **black**: a CSS-filled `Shape` (e.g. a gradient-filled
`Circle` avatar) renders as a solid **black** circle and the cell's text / other
CSS-styled bits disappear for that frame. Under continuous or fast scrolling the
flash lands on different cells every frame, so it *looks* like a persistent
"black avatars" bug — most visibly on the cell(s) you just selected, because
selecting scrolls the list to the selection and recycles those cells.

It is **transient**: once scrolling stops, the very next pulse renders every
cell correctly. It is **not** GPU-, present-tier-, or dirty-region-specific
(reproduces on GPU and software raster, with `prism.dirtyopts` on or off).

## Root cause

JavaFX runs each pulse as **CSS pass → layout pass → sync → render**
(`Scene.ScenePulseListener.pulse()`).

`VirtualFlow` creates and recycles its cells **inside `layoutChildren()`** — i.e.
*during* the layout pass, **after** that pulse's CSS pass already ran. A cell
factory whose `updateItem` constructs **new** nodes (a new `Circle` with
`-fx-fill: linear-gradient(...)`, new `Label`s, …) adds those nodes to the scene
graph after CSS has been processed for the frame. Their CSS is therefore only
applied on the **next** pulse.

For that one frame the new nodes render with their **default** state:

- A `Shape` whose fill comes from CSS (`-fx-fill`) falls back to the `Shape`
  default fill, which is **opaque black** → the black avatar.
- A `Label` that isn't laid out yet has zero size → no glyphs → missing text.

Nodes whose paint does **not** depend on CSS are unaffected, which is the tell
that pinned this down:

| Avatar fill set via…                         | Renders during recycle |
|----------------------------------------------|------------------------|
| CSS `setStyle("-fx-fill: linear-gradient")`  | **black** (CSS not yet applied) |
| Java API `circle.setFill(new LinearGradient)`| correct                |
| Constructor `new Circle(r, Color.RED)`       | correct                |

This is inherent to the stock JavaFX CSS-before-layout ordering (stock flashes
too with node-recreating cell factories); the well-known app-side mitigation is
to **reuse** nodes across `updateItem` calls rather than recreate them. skia-fx
fixes it in the pipeline so apps don't have to.

## Fix

`Scene.ScenePulseListener.pulse()` — after the normal `doCSSPass()` +
`doLayoutPass()`, **settle CSS for nodes created during layout** before sync /
render:

```java
Scene.this.doCSSPass();
Scene.this.doLayoutPass();

// Re-run CSS + layout while the root still reports pending CSS — i.e. while the
// layout pass kept adding CSS-pending nodes (VirtualFlow cells). Bounded so a
// pathological never-converging tree can never spin the pulse.
final Parent settleRoot = Scene.this.getRoot();
int settlePasses = 0;
while (settleRoot != null
        && settleRoot.cssFlag != CssFlags.CLEAN
        && settlePasses++ < 3) {
    Scene.this.doCSSPass();
    Scene.this.doLayoutPass();
}
```

Why it is safe and cheap:

- **Targeted signal.** When a node is added to a CSS-active tree it marks itself
  CSS-dirty and propagates `cssFlag != CLEAN` up to the root. The loop body only
  runs when the *layout pass itself* produced new CSS work — exactly the
  recycle-during-scroll case. On a static scene the root is already `CLEAN`
  after the first layout, so the loop is skipped entirely (**zero overhead**).
- **Bounded.** Capped at 3 extra settle passes. If a tree genuinely never
  converges (a node that re-dirties CSS during its own layout), we fall through
  after 3 iterations to exactly today's behaviour (a one-frame lag) — never an
  infinite pulse.
- **No re-entrancy.** The extra `doCSSPass()` / `doLayoutPass()` calls run after
  the initial `doLayoutPass()` has returned (`performingLayout == false`), the
  same way `Scene.preferredSize()` already calls both in sequence.

### Related robustness fix (degenerate gradient → last colour, not black)

While tracing this, `SkiaShaders.forGradient` was hardened independently: a
gradient that resolves to a **degenerate** geometry (a linear gradient whose two
endpoints coincide, or a radial with radius ≤ 0) makes Skia return a **null**
shader, and a fill with a null shader paints with `SkPaint`'s default — **opaque
black**. Per the SVG/CSS rule a zero-length gradient paints the **last stop's
solid colour**, so `forGradient` now falls back to a solid-colour shader of the
last stop instead of letting the fill go black. This was *not* the cause of the
ListView symptom (that was the CSS-settle issue above), but it removes a real
latent "black fill" edge case for genuinely degenerate gradients.

## How it was found / how to reproduce

The bug only manifests on-screen (a `snapshot()` takes a separate *full-render*
path and won't reproduce it). It was captured by dumping the actual rendered
back buffer to PNG and reading it back. To reproduce manually: a `ListView` with
~40+ items, a cell factory that builds a CSS-gradient-filled `Circle` plus
`Label`s in `updateItem`, and code that selects + `scrollTo` a new row every
pulse — the recycled cells flash black without the fix and render correctly with
it.

## Files

- `javafx.graphics/src/main/java/javafx/scene/Scene.java` — the CSS-settle loop
  in `ScenePulseListener.pulse()` (search `skia-fx: settle CSS`).
- `javafx.graphics/src/main/java/com/sun/prism/skia/impl/SkiaShaders.java` —
  degenerate-gradient → last-stop solid-colour fallback in `forGradient`.
