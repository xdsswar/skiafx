# 3D SubScene Scale on Cross-DPI Monitor Move (open investigation)

Status: **open / not yet fixed.** This is an investigation record with the
current-state map, the leading root-cause hypothesis, and a fix direction — to
verify before implementing. The 2D monitor-move handling (separate, working) is
documented in `DPI_MONITOR_MOVE_AND_BUG_FIX_PLAN.md` and `RESIZE_INVARIANTS.md`;
the 3D engine in `3D.md`.

## Symptom

Dragging a window that contains a 3D `SubScene` between monitors of different
DPI (e.g. 100% ↔ 150%) renders the 3D content at the wrong size for one or more
frames after the scale change. The 2D scene around it scales correctly.

## What is correct today

`com.sun.javafx.sg.prism.NGSubScene#renderContent` sizes the SubScene's render
target the right way:

- It reads the parent `Graphics` transform's logical scale and multiplies by the
  parent's pixel-scale factor to get the **device** scale.
- It computes device-pixel RTT dimensions (`rtWidth`/`rtHeight`), allocates a
  fresh `SkiaRTTexture` at that size, copies the pixel-scale factors onto the
  RTT's `Graphics`, and scales the RTT render transform by the **logical** scale
  only (the RTT is device-sized; the scene is drawn at logical scale into it).

Pixel scale reaches it each frame via
`com.sun.javafx.tk.quantum.ViewPainter` (`g.setPixelScaleFactors(...)` from the
presentable's `renderScaleX/Y`), and `SkiaPresentable#lockResources` debounces
2D scale-change rebuilds until the `(scale, size)` pair settles.

## Leading hypothesis (to verify)

The mis-size is a **debounce timing mismatch** between the RTT (sized
immediately on the new DPI) and the bgfx 3D target (rebuilt on a delay):

`com.sun.prism.skia.SkiaRTTexture#ensureNative3DTarget` rebuilds the native bgfx
`Target3D` only when the size has *settled*: sample count changed, size stable
for 2 consecutive frames, or unsettled for `MAX_UNSETTLED_3D_FRAMES` (8). On the
frame the DPI changes, `NGSubScene` allocates a **fresh** `SkiaRTTexture` whose
`pending3dW/pending3dH` start at `-1`, so `settled` is false and the 3D-target
rebuild is **deferred**. For that frame (and until it settles) the bgfx target
viewport — `openjfx_skia3d_bridge.cpp` `target_begin` →
`bgfx::setViewRect(kView3D, 0, 0, t->w, t->h)` using the target's cached `w,h` —
does not match the new RTT size, so the 3D content is drawn at the old size into
the new-sized RTT (or not at all until the target exists).

In short: the 2D path debounces on the settled `(scale, size)` pair; the 3D
target's debounce keys off the RTT's own `pending` size, which resets to `-1` on
every freshly-allocated RTT, re-introducing a lag exactly when the DPI (hence the
RTT identity) changes.

## Evidence (files)

- `javafx.graphics/src/main/java/com/sun/javafx/sg/prism/NGSubScene.java`
  — device-scale RTT sizing (correct).
- `javafx.graphics/src/main/java/com/sun/prism/skia/SkiaRTTexture.java`
  — `ensureNative3DTarget` size-change debounce (suspected lag).
- `javafx.graphics/src/main/native-skia/skia3d/openjfx_skia3d_bridge.cpp`
  — `openjfx_skia3d_target_create` stores `w,h`; `target_begin` sets the bgfx
  view rect from those cached values.
- `javafx.graphics/src/main/java/com/sun/javafx/tk/quantum/ViewPainter.java`
  — per-frame pixel-scale propagation.
- `javafx.graphics/src/main/java/com/sun/prism/skia/SkiaPresentable.java`
  — `lockResources` 2D scale-change debounce (the working reference).

## Fix direction (proposed, not yet implemented)

Make the 3D target track the RTT's *current device content size* without the
fresh-RTT `-1` debounce penalty. Options, in order of preference:

1. On a fresh RTT, seed the 3D-target pending size from the RTT's actual content
   size so the first frame rebuilds the target at the correct size instead of
   waiting for "settled" (the debounce is meant to absorb drag jitter, not the
   discrete DPI step).
2. Rebuild the bgfx target eagerly when the size change comes from a pixel-scale
   change (discrete, known-final) versus a continuous drag-resize (jittery);
   reuse the `(scale, size)` settle signal `SkiaPresentable` already computes.
3. As a backstop, clamp `setViewRect` to the live RTT content size each
   `target_begin` so a stale target never renders at the wrong viewport.

Verify by dumping the RTT and bgfx target sizes per frame across a 100%↔150%
drag (the colorRes-to-PNG technique used for the earlier 3D floor/near-plane
bug) and confirming they agree on the first post-change frame.

## Constraints

`SubScene` public API and behavior stay byte-identical; the fix is internal to
`SkiaRTTexture`/the 3D bridge sizing. No per-frame heap allocation on the render
thread.
