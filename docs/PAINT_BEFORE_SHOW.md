# Paint-before-show — how skia-fx hides the first-frame flash

When a Win32 window is first shown (`ShowWindow(SW_SHOW)`), DWM grabs
the window's redirection bitmap and runs the standard zoom-in show
animation against it. If nothing has painted into that bitmap yet —
the default state for any newly-created HWND — the user sees a brief
flash of the OS-default background colour (white in light mode, dark
in dark mode) before the application's first frame lands.

skia-fx eliminates that flash by **painting the scene into the swap
chain *before* `ShowWindow` runs**. The OS show animation then
reveals the already-rendered scene from frame zero, with the natural
OS zoom-in animation intact.

This is the "paint-before-show" mechanism documented in this file.
The historical journey of false starts (DWM cloak, deferred
ShowWindow trampolining via `Application.invokeLater`, alpha-ramp,
…) is recorded in `WINDOW_FLICKER_INVESTIGATION.md`. This file
documents the *current* design and the invariants it relies on.

## Architecture

The native HWND is created at `Stage` construction time (in
`WindowStage.initPlatformWindow`), not lazily at `Stage.show()`.
That's stock Win32 / Glass behaviour — the HWND exists, just
without `WS_VISIBLE`. The Skia swap chain (created lazily inside
`SkiaPresentable.allocate`) binds to that HWND. **Crucially:
`wglSwapBuffers` and `IDXGISwapChain3::Present` both succeed
against a hidden HWND, and DWM's redirection bitmap accepts the
contents.** Verified by gated probe; see commit history.

So we have all the pieces in place — we just need to schedule a
paint between the FX thread's `Stage.show()` and the native
`ShowWindow`. The control flow:

```
FX thread                            Render thread
─────────                            ─────────────
WindowStage.setVisible(true)
  paintBeforeShow = true
  firstPresentLatch = new(1)
  super.setVisible(true)              ── scene added to PaintCollector dirty list
  QuantumToolkit.pulse(true)           ── triggers renderAll() which dispatches
                                          a paint job to the render thread
                                                     PresentingPainter.run()
                                                       validateStageGraphics()    ← gate bypass
                                                       createPresentable()        ← swap chain on hidden HWND
                                                       paint scene
                                                       present()                  ← wglSwapBuffers (succeeds)
                                                       notifyFirstPresented()     ← signals latch
  firstPresentLatch.await(200ms)      ── wakes up
  paintBeforeShow = false
  firstPresentLatch = null
  platformWindow.setVisible(true)     ── ShowWindow fires, swap chain already
                                         populated → OS animation reveals
                                         real content
  (modal blocking, focus, …)          ── all run after, in natural position
```

The full pulse cycle (`QuantumToolkit.pulse(true)`, not just
`firePulse()`) is required: `firePulse` runs only the FX-side
listeners (CSS / layout / `syncPeer`); the actual render-thread
dispatch lives in `PaintCollector.renderAll()` which runs *after*
`firePulse` inside `pulse()`. Calling only `firePulse` queues no
work for the render thread and the latch will timeout — confirmed
empirically during the probe phase.

## Invariants

Each invariant has a comment at the corresponding call site. Search
for `paintBeforeShow` to find every touch point.

### 1. `paintBeforeShow` and `firstPresentLatch` are per-WindowStage

These are *instance* fields on `WindowStage`. Multiple windows
showing concurrently each coordinate independently. Never replace
with static / global flags — the LoaderDemo + DualStreamPlayer
running together exercises this.

### 2. `super.setVisible` runs BEFORE the native ShowWindow

The first show sequence is:

1. `paintBeforeShow = true`
2. `super.setVisible(true)` (opens render gate via
   `GlassStage.setVisible` → `scene.stageVisible(true)` →
   `PaintCollector.addDirtyScene`)
3. `pulse(true)` (full pulse: FX listeners + `renderAll`)
4. Wait on latch
5. `platformWindow.setVisible(true)` (native `ShowWindow`)

Reversing 2 and 5 is the stock JFX order — and the source of the
white flash. The non-pre-show path (`PAINT_BEFORE_SHOW_ENABLED`
false, or a re-show from iconified, or any window without a scene)
keeps the stock order.

### 3. Modal blocking, focus, activation run AFTER `platformWindow.setVisible(true)`

The setup → modal-blocking sequence in `WindowStage.setVisible` is:

- (visible=false branch — unblock, then native hide)
- (visible=true branch — paint-before-show OR stock setVisible)
- modal-blocking on visible=true

Modal blocking, `SetForegroundWindow`, focus rules etc. all execute
in their natural positions *after* the native `ShowWindow`. The
previous defer-show attempt (commits 00973a47/9cf9b8a6, reverted)
broke OS show animation specifically because modal blocking ran on
a logically-visible but natively-hidden window, and the eventual
`ShowWindow` ran from `Application.invokeLater` on a different
thread — the OS state machine didn't give it animation treatment.
Don't reintroduce that pattern.

### 4. `firstPresentLatch` is null-safe and idempotent

`PresentingPainter` may run multiple times before the latch fires
(rare, but possible under load). `notifyFirstPresented` reads the
volatile field once and counts down only if non-null. After the FX
thread clears the latch in `finally`, subsequent calls are no-ops.

### 5. Timeout is a hard cap, not a deadline

`PAINT_BEFORE_SHOW_TIMEOUT_MS` (default 200 ms; tunable via
`-Dskia.preshow.timeoutMs=N`) is the maximum time we'll wait for
the first present. If the render thread genuinely can't paint
within that window (massive scene, blocked native init, GPU device
loss), we **proceed anyway**. The user sees the stock-behaviour
brief flash — not worse than before — and the show is never hung
on us.

Do not raise this above ~500 ms without strong justification. The
user perceives `Stage.show()` latency, and 500 ms+ feels broken.

### 6. The gate bypass in `ViewPainter.validateStageGraphics` is scoped to one specific window

The bypass is:

```java
if (!sceneState.isWindowMinimized()) {
    GlassStage stage = sceneState.getScene().getStage();
    if (stage instanceof WindowStage ws && ws.isPaintBeforeShow()) {
        return true;
    }
}
return sceneState.isWindowVisible() && !sceneState.isWindowMinimized();
```

The bypass only fires when the **owning** WindowStage has the flag
set. Other windows being painted concurrently fall through to the
normal `isWindowVisible()` check. Don't globalise the bypass — it
would let the painter target swap chains of windows that aren't
actually about to be shown.

## Configuration

| Property | Default | Effect |
|---|---|---|
| `-Dskia.preshow.paint` | `true` | `false` reverts to stock order (no paint-before-show). Use if a broken render-thread path makes the latch wait timeout consistently. |
| `-Dskia.preshow.timeoutMs` | `200` | Max FX-thread wait for first present. Raise for genuinely huge first-paint workloads. Don't go above ~500. |

No native flags or env vars in production. The historical
`SKIA_PROBE_PAINT_BEFORE_SHOW` env var was probe-only and is
removed.

## When paint-before-show does NOT engage

Falls through to stock setVisible order in any of these:

- `-Dskia.preshow.paint=false` (opt-out).
- The visibility change is to `false` (hiding, not showing).
- `platformWindow.isVisible()` is already true (re-show from
  iconified — the redirection bitmap is still populated).
- `getScene() == null` — nothing to paint.

## Related pieces of the flicker fix

Paint-before-show only addresses the *first-show flash*. Two other
fixes in the same area address related issues:

### `freshBackBuffer = true` after every present (`PresentingPainter.java`)

The Skia present paths (GL `wglSwapBuffers`, D3D12 DXGI flip-model)
are both flip-style — the new back buffer's contents are undefined
after present. The painter's dirty-region path otherwise repaints
only the changed rectangle into that undefined back buffer, so
unchanged regions show garbage on every swap — visible as a
whole-window flash synced to whatever node is dirtying (originally
the TextField caret, at 2 Hz).

Setting `freshBackBuffer = true` after every successful present
forces the next paint to render the entire scene, which is what
flip-style swap chains require.

### `WM_ERASEBKGND` returns 1 (`GlassWindow.cpp`)

`WindowStage` pushes the Scene's fill colour into
`GCLP_HBRBACKGROUND` via `_setBackground2`. Stock JFX lets
`DefWindowProc` paint that brush on every `WM_ERASEBKGND`
including the implicit one DWM issues during first show — a flash
of the scene fill colour. Returning 1 tells Windows "background is
already erased" so the brush is never painted. Microsoft's
documented pattern for windows that paint their own content.

## File map

| File | Role |
|---|---|
| `javafx.graphics/src/main/java/com/sun/javafx/tk/quantum/WindowStage.java` | `paintBeforeShow` / `firstPresentLatch` fields, `notifyFirstPresented()`, rewritten first-show `setVisible(true)` path |
| `javafx.graphics/src/main/java/com/sun/javafx/tk/quantum/ViewPainter.java` | `validateStageGraphics()` gate bypass when the owning WindowStage is in pre-show paint |
| `javafx.graphics/src/main/java/com/sun/javafx/tk/quantum/PresentingPainter.java` | After first successful present, calls `WindowStage.notifyFirstPresented()` |
| `javafx.graphics/src/main/native-glass/win/GlassWindow.cpp` | `WM_ERASEBKGND` returns 1 (related, see above) |

No native ShowWindow / cloak / alpha / region code — fully
Java-side coordination on top of stock JFX Glass.

## Verifying it works

Run any sample that previously flashed white on first show:

```
./gradlew :samples:ensemble:runLoaderDemo
./gradlew :samples:ensemble:runDualPlayer
./gradlew :samples:ensemble:run             # dashboard
```

Watch the OS show animation. The window should zoom-in revealing
already-rendered content from frame zero, with the standard Win11
fade. No white flash, no missing animation.

Regression mode (`-Dskia.preshow.paint=false`): same demos should
show the original brief white flash plus the OS animation.
