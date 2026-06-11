# Window-show flicker — post-mortem

> For the *current* design (how paint-before-show works today,
> invariants, configuration, file map), see
> [`PAINT_BEFORE_SHOW.md`](PAINT_BEFORE_SHOW.md). This file is the
> historical record of the diagnosis and the false starts.

## Symptom

After `Stage.show()`, the **DualStreamPlayer** demo (and to a lesser
extent Dashboard / VideoPlayerApp) appeared with visibly flickering
content. The flashing continued indefinitely, synchronized with the
TextField caret blink at roughly 2 Hz.

Reproducer: `./gradlew :samples:ensemble:runDualPlayer`.

## Root cause

A classic dirty-rect-vs-flip-swap bug.

1. `ViewPainter.paintImpl` runs JFX's dirty-region optimization: when
   `freshBackBuffer == false` and `PrismSettings.dirtyOptsEnabled`,
   it only repaints the *changed* rectangle (e.g. the caret's
   bounding box), assuming the rest of the back buffer still holds
   the previous frame.
2. `SkiaPresentable.present()` uses **flip-style** swap chains in
   both tiers:
   - GL: `wglSwapBuffers` — after swap, new back buffer contents
     are *undefined*.
   - D3D12: `IDXGISwapChain3::Present(0, DXGI_PRESENT_ALLOW_TEARING)`
     on a flip-model chain — same semantics; old back buffer is
     not copied forward.
3. `PresentingPainter` set `freshBackBuffer = true` only on
   presentable rebuild and *never reset it after a successful
   present*. So after the first paint, every subsequent paint
   redrew only the caret region into a back buffer whose other
   regions contained whatever garbage the GL/D3D driver handed
   back from the swap pool. The user saw that garbage flash
   through everywhere except the caret area, once per blink.

The original investigation observation — *"`SCENE_DIRTY_VERSION`
bumps every pulse for ~60 frames after show, then continues at
~2 Hz"* — was the same mechanism in two phases:
- Initial 60-frame burst: layout / CSS / focus-glow convergence
  dirties many nodes, dirty regions cover most of the window,
  flicker is dramatic.
- Steady-state 2 Hz: only the focused TextField's caret is
  dirtying anything, so the flicker visibly tracks the caret.

## How we identified it

Added gated stack-trace instrumentation in `NGNode.markTreeDirty`
(guarded by `-Dskia.dirty.trace=true`). The first ~25 bumps in a
DualStreamPlayer run were *all identical*:

```
NGNode.markTreeDirty
  ← NGNode.markDirty
  ← NGNode.setOpacity        ← opacity toggling on an NGPath
  ← Node.doUpdatePeer
  ← Scene$ScenePulseListener.synchronizeSceneNodes
```

Cadence: ~500 ms apart, exactly the caret blink rate. From there,
mapping the symptom (whole-window flash synchronized with the
caret) to `paintImpl`'s dirty-region path + flip-style swap was
mechanical.

## The fix

`javafx.graphics/src/main/java/com/sun/javafx/tk/quantum/PresentingPainter.java`,
inside the `if (presented) { ... }` block:

```java
if (presented) {
    lastPresentedDirtyVersion = currentDirtyVersion;
    lastPresentTimeNs         = nowNs;
    // Flip-style swap leaves the new back buffer undefined —
    // force the next paint to renderEverything so the entire
    // scene is valid each frame.
    freshBackBuffer = true;
}
```

Setting `freshBackBuffer = true` after every successful present
forces `ViewPainter.paintImpl`'s `renderEverything` predicate to
`true` on the next pulse, which routes through the full-scene
`doPaint(g, null)` branch (clip = null, no dirty-region clipping).
Dirty-region machinery still runs for `accumulateDirtyRegions` /
pre-culling bookkeeping, but the final draw covers the full window.

This matches what flip-style swap chains require. There is no
runtime cost beyond a single full-scene render per frame, which
Skia handles trivially for typical UI scenes (it's what every
modern compositor does anyway).

## What we tried first and why it failed

| Attempt | Why it didn't work |
|---|---|
| Defer `ShowWindow` until first paint | JFX render pipeline gates painting on `Window.isVisible()` — chicken-and-egg. |
| Paint to a hidden HWND, then show | WGL reallocates the backbuffer on the visible transition — painted content is discarded. |
| Eager `SkiaGpu.probe()` at toolkit startup | Made startup worse; blocked toolkit init. |
| `Window.setAlpha(0)` pre-show + ramp to 1 | Worked, but Glass' `setAlpha` adds `WS_EX_LAYERED`, which suppresses the OS zoom show animation. UX regression. |
| Force `applyCss()` + `layout()` before `ShowWindow` | No measurable effect — the dirtying was happening after layout converged (caret blink), not during it. |
| `DWMWA_CLOAK` + uncloak after 250 ms / 1500 ms | Hides the symptom for the cloak window, but DWM doesn't replay the show animation on uncloak — the OS zoom-in is lost. Also leaves the underlying bug present (resize, focus loss, anything else that bumps dirty would still flash). |

All these attempts were treating the symptom. None of them touched
the actual mechanism: a back buffer assumed to be preserved by a
swap chain that does not preserve it.

## Aftermath

Final state on Windows. Three fixes, each addressing a distinct
piece of the flicker:

### 1. Caret-blink whole-window flash

`PresentingPainter.freshBackBuffer = true` after every successful
present. Every present forces the next paint to render the entire
scene, which is what flip-style swap chains require. This kills
the 2 Hz visible flash that was synced to the TextField caret.

### 2. WM_ERASEBKGND class-brush flash

`WM_ERASEBKGND` returns `1` in `GlassWindow::WindowProc` —
Microsoft's documented pattern for windows that paint their own
content. Suppresses the class-brush erase that DWM would otherwise
pick up into the redirection bitmap and show during the OS show
animation.

### 3. White flash during first OS show animation (the big one)

**Paint-before-show.** The native HWND is created at Stage
construction, well before `Stage.show()`. The Skia swap chain
binds to that HWND. DWM's redirection bitmap accepts swap-chain
Presents *before* `WS_VISIBLE` is set — we verified this with a
gated probe (`SwapBuffers` returns success against a hidden HWND,
and DWM composites the resulting bitmap when the show animation
later fires).

`WindowStage.setVisible(true)` on a fresh first show now:

1. Sets `paintBeforeShow = true`, creates a `CountDownLatch`.
2. Calls `super.setVisible(true)` — opens the render gate by
   adding the scene to `PaintCollector`'s dirty list. The native
   HWND stays hidden.
3. Calls `QuantumToolkit.pulse(true)` synchronously — runs the
   full pulse cycle (FX-side CSS/layout/sync **and**
   `PaintCollector.renderAll()` which signals the render thread).
4. Blocks on the latch (200 ms timeout via
   `-Dskia.preshow.timeoutMs`). Render thread paints the scene,
   creates `SkiaPresentable` (the swap chain), and `Present`s
   into DWM's redirection bitmap.
5. `PresentingPainter` counts down the latch after the first
   successful present for that scene.
6. FX thread unblocks, calls `platformWindow.setVisible(true)` →
   native `ShowWindow` fires at its natural moment with the
   redirection bitmap already populated. The OS show animation
   reveals the painted scene; modal blocking, focus, activation
   all run in their normal sequence after.

Why this works where the previous defer-show attempt failed
(commits 00973a47 / 9cf9b8a6, reverted):

- `ShowWindow` runs on the FX thread synchronously in the
  natural `setVisible` flow — no `Application.invokeLater`
  thread hop, so the OS state machine still gives it the show
  animation treatment.
- `super.setVisible(true)` and `platformWindow.setVisible(true)`
  are still in the same `setVisible` call, separated only by the
  brief latch wait. Modal blocking, focus, `SetForegroundWindow`
  all happen at their natural points after ShowWindow.
- Bounded by a tight 200 ms timeout — graceful fallback to the
  stock (brief flash) behaviour if first paint is slow.

Opt-out: `-Dskia.preshow.paint=false` reverts to stock order
(native ShowWindow first, render later) if the new behaviour
ever causes trouble.

Implementation surface:

| File | Change |
|---|---|
| `WindowStage.java` | `paintBeforeShow` flag, `firstPresentLatch`, `notifyFirstPresented()`, rewritten `setVisible(true)` first-show path |
| `ViewPainter.java` | `validateStageGraphics()` bypasses `isWindowVisible` when the scene's owning `WindowStage.isPaintBeforeShow()` is true |
| `PresentingPainter.java` | After first successful present, calls `WindowStage.notifyFirstPresented()` |
| `GlassWindow.cpp` | `WM_ERASEBKGND` returns 1 (item 2 above) |

No native ShowWindow / cloak code — fully Java-side coordination
on top of stock JFX Glass behaviour.
