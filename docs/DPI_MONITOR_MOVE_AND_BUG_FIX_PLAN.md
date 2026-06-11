# DPI monitor-move crash spam + bugs.md fix plan

Branch: `web-blink`. Date: 2026-06-06.
Scope: (1) root-cause + fix the `.tmp/err.txt` failure seen when dragging a
window between 150% and 175% DPI monitors; (2) a non-breaking, prioritized plan
to clear the verified findings in `.tmp/bugs.md`.

Guiding constraint (project policy): **public API unchanged, toolkit
threading/resize contract unchanged, errors degrade — never abort, every change
verified + leak-checked before "done".**

> ## Implementation status (2026-06-06)
> **PR 1 landed (compiles clean, `:javafx.graphics:compileJava` BUILD SUCCESSFUL):**
> - Fix 1 — effect-class warm-up in `SKIAPipeline.init()` ✅ (the primary err.txt fix)
> - Fix 3 — gated the per-presentable INFO log in `SkiaPresentable` ✅
> - H3 — CacheFilter scaleHint Y-axis `cachedScaleY` ✅
> - NEW-H2 — effect-renderer RTTexture leak: release corpse + re-checkout pooled ✅
> - NEW-H1 — scroll-cache device-coords in `moveCacheBy` + dirty scissor ✅
>
> **Still needs RUNTIME verification** (couldn't run headed here): reproduce the
> 150↔175% monitor drag → expect zero `RenderState` errors + effects keep
> rendering (Fix 1); smooth-scroll a ScrollPane/ListView on a 150% monitor →
> no trailing strips (NEW-H1); monitor-move soak → flat GPU mem (NEW-H2);
> a cached node on non-square DPI → correct height (H3).
>
> **PR 3 landed (compiles clean):** warm-up trimmed to the 4-class RenderState
> family; M7 (PaintCollector countdown in finally), M8 (dispose stale presentable
> on device-not-ready), M17 (drain deferred GPU frees in QuantumToolkit.dispose),
> M16 (JS_VALUE decode try/catch + JSValueCodec length guard), M14 (removeChild
> feeds the move-safe pendingEvict path), M6 (passthrough via pool), M4
> (LinearConvolveShadow cache keyed on WeakReference== not identityHashCode).
> Interrupt-hunt conclusion: WindowStage:815 was the sole offender (already
> fixed); the other InterruptedException catches clear the flag — left as-is.
> M5 deferred (documented). See plan atomic-yawning-wilkes.md.
>
> **PR 4 — Enter-key engine doorbell (Batch E) landed in source (needs Chromium
> rebuild):** a Windows-only dedicated thread in `JuxBrowserMainParts`
> (`StartCommandDoorbell`/`StopCommandDoorbell`) watches `HasPendingCommands()` and
> PostMessages the existing `WM_JUX_DRAIN` the instant a command arrives, so the
> last keystroke before idle (Enter) drains immediately instead of waiting up to
> one 8ms `cmd_timer_` tick. Edited the canonical engine source
> (`javafx.web/src/main/native-blink/engine/jux_browser_main_parts.{h,cc}`) AND
> the generated `.chromium/.../jux/` copy. Non-Windows keeps the 8ms timer
> unchanged. Take effect on `-PbuildWebNative=true`.
>
> **Batch 4 (LOW cleanup) landed (compiles clean):** H1 symmetric `handle != 0`
> guards in `SkiaGraphics.drawTexture`/`drawTextureVO`; L8 inline FQN → imports in
> SkiaBlendPeer/SkiaLinearConvolvePeer/SkiaZoomRadialBlurPeer/SkiaEffectRenderer.
> M2/M3 left as-is (native magic-poison check already makes them safe; a "true"
> fix would rework the cached handle on the hot draw path — not worth the risk).
>
> **Deferred items NOW DONE (compile clean; plan atomic-yawning-wilkes.md):**
> - **Fix 2** — debounce the presentable rebuild in `SkiaPresentable.lockResources`:
>   only rebuild once the (scale,size) pair has settled for 2 consecutive pulses;
>   the storm (~300 rebuilds) collapses to ~2–3. Java only.
> - **M13** — frame-slot handshake: Java publishes the slot it's reading
>   (`OFF_FRAME_READING_SLOT` header field, release store) and the engine skips it
>   (`ReadFrameReadingSlot` acquire load in `JuxOnFrameCaptured`). Java + engine
>   (canonical `engine/` source); **needs `-PbuildWebNative=true` engine rebuild**.
> - **M18** — real GPU texture budget. 18a (accounting + budgets + reclaim-on-
>   pressure + `-Djavafx.gpu.memDump`) ON by default; 18b (LRU eviction of unlocked
>   RTs) opt-in via `-Djavafx.gpu.evictUnlockedRTs`; 18c roadmap. See
>   docs/GPU_MEMORY_BUDGET.md.
>
> **PR 1 verified working (user test, 2026-06-06):** err.txt went 12,422 → 100
> lines, **0 RenderState errors** — the warm-up fixed the monitor-move flood.
>
> **PR 2 landed (compiles clean):** H6 (JS wrapper identity cache + owner-gated
> release), H7 (clear javaObjects + jsObjectCache on nav/dispose), H4
> (requestNextPulse on rate-cap deferral), H2 (ZoomRadialBlur 3σ pad + DECAL).
>
> ### Follow-on root cause found via PR-1 test: spurious ClassNotFoundException
> Once effects stopped crashing, the user hit a NEW intermittent error on every
> few scrolls: `NoClassDefFoundError: ScrollEvent$HorizontalTextScrollUnits`
> (`Scene.java:2866`, FX Application Thread) — even though the class **is** in the
> jar + exploded classes. Root cause: `WindowStage.java:815` re-asserted
> `Thread.currentThread().interrupt()` on the FX thread after an interrupted
> paint-before-show latch wait. The FX thread never consumes that flag, so the
> next *interruptible* NIO read — a lazy class load from the exploded module dir —
> fails with `ClosedByInterruptException`, surfaced as a spurious CNFE for
> whatever class loads next. Same transient-classload family as the RenderState
> poison, different thread/loader. **Fixed at the source** (don't re-interrupt the
> FX thread; the catch also clears any stray flag) — this kills the whole family,
> not just ScrollEvent, so per-class warm-up is unnecessary. ✅ compiles.

---

## PART 1 — The monitor-move failure (`err.txt`)

### What the log actually is
`err.txt` is 12,422 lines but only **one** distinct failure, repeated:

```
NoClassDefFoundError: Could not initialize class
    com.sun.scenario.effect.impl.state.RenderState
  Caused by: ExceptionInInitializerError:
    Exception NoClassDefFoundError: com/sun/scenario/effect/impl/state/RenderState$1
    at ...RenderState.<clinit>(RenderState.java:87)
```

Interleaved with bursts of `SkiaPresentable <init> "Presentable created ..."`
at **two alternating sizes — 1920×974 and 1579×1293** — all stamped within a
~2-second window (18:19:44–45).

### Root cause (two-stage, verified)

**Stage A — the presentable-rebuild storm.**
`SkiaPresentable.lockResources` (`SkiaPresentable.java:165-231`) returns `true`
(force dispose+recreate) whenever the render **scale** changes
(lines 187-192) and, on the READBACK tier, whenever the **size** changes
(line 230). `PresentingPainter` then disposes and rebuilds the presentable.
Dragging a window across a 150%↔175% DPI boundary makes Windows emit a rapid
`WM_DPICHANGED`→`SetWindowPos`→`WM_SIZE` sequence (per-monitor-v2, the
`GlassWindow.cpp` `HandleDPIEvent` path), and at the boundary the window genuinely
oscillates between the two monitors' client rects — hence the two distinct
aspect ratios (1920×974 vs 1579×1293) alternating in the log. Each pulse the
state differs → rebuild. ~120 pulses over a 2 s drag × the ping-pong ⇒ ~300
dispose+recreate cycles. This is a **resource-pressure storm** on
`QuantumRenderer-0` (GL framebuffers, the `surface_prime_window` ~33 MB heap
vector per show — bugs.md NEW-M1, CacheFilter device-res textures, the NEW-H2
RTTexture leak).

**Stage B — the storm transiently poisons the effect system (the real damage).**
The *first* effect render (Modena uses DropShadow/Blend everywhere, reached via
`Merge.getRenderState` → `RenderState`) lazily runs `RenderState.<clinit>`, which
at `RenderState.java:87` constructs the anonymous class `RenderState$1`. Landing
inside the storm, that classload **transiently** fails with
`NoClassDefFoundError: RenderState$1`. **The class file is present in the
shipped jar** (verified: `RenderState$1/$2/$3.class` are in both
`javafx.graphics/build/libs/...jar` and `sdk/lib/...jar`) — so this is **not** a
packaging/missing-class bug. It is a transient runtime classload failure
(NIO module read aborted under interrupt, or an OOM/GL-exhaustion at the moment
of `defineClass`). The JVM then **permanently records the failed
initialization**: every later effect render throws
`Could not initialize class RenderState` for the rest of the JVM session → the
12k identical lines. Effects silently die session-wide; the app keeps running.

This matches every observed property:
- *Intermittent* — needs the storm to coincide with the **first-ever** effect
  class init. On a steady monitor, effects init cleanly at startup and can never
  re-poison (a successfully-initialized class never re-runs `<clinit>`).
- *Only on monitor move at 150/175%* — that drag is what produces the storm.
- *"Doesn't break anything" visibly* — effects fall back / draw nothing; no crash.

### Confirmed NOT the cause (so we don't chase them)
- Missing/duplicate `RenderState$1.class` — present in the jar; only our tree
  compiles it (`/jfx-master` is reference-only, no split package).
- `notifyScaleChanged` not syncing `renderScaleX` (`Window.java:615-624`) — this
  matches stock OpenJFX; `renderScale` is synced via the separate rescale path.
  Not the trigger.

### Fix — three changes, smallest-blast-radius first

**Fix 1 (PRIMARY, makes the symptom impossible regardless of storm): eager
warm-up of the effect classes at pipeline init.**
A class that is already successfully initialized can never be poisoned later.
Force-init `RenderState` and the effect graph **once, on the render thread, at
pipeline startup**, before any storm can occur.

- Insert in `SKIAPipeline.init()` (`SKIAPipeline.java:60-85`), right before
  `return true;`. `init()` runs once on `QuantumRenderer-0` when the pipeline is
  created — the correct thread and the earliest safe point.
- Implementation: a private `warmUpEffectClasses()` that calls
  `Class.forName(name, /*initialize*/ true, loader)` for each class, **each in
  its own try/catch** so a warm-up miss is logged and ignored — it must never
  make `init()` decline the pipeline.
- Class list (force `<clinit>`; the state classes are what reach `RenderState`):
  `com.sun.scenario.effect.impl.state.RenderState` (the critical one — pulls in
  `$1/$2/$3`), `...state.LinearConvolveRenderState`, `...state.GaussianRenderState`,
  `...state.BoxRenderState`, and the common `com.sun.scenario.effect.*` effects
  actually used by Modena/controls: `Merge`, `Blend`, `DropShadow`, `InnerShadow`,
  `GaussianBlur`, `BoxBlur`, `ColorAdjust`, `Reflection`, `SepiaTone`,
  `DisplacementMap`, `InvertMask`, `PerspectiveTransform`, `ZoomRadialBlur`,
  `Bloom`, `Glow`, `MotionBlur`, `GaussianShadow`, `BoxShadow`, `Crop`, `Flood`,
  `PhongLighting`, `LinearConvolveCoreEffect`.
- Cost: one-time tens-of-ms at startup, off the hot path. Non-breaking: pure
  pre-loading of classes that load anyway.
- **This single change clears `err.txt` even if the storm stays.**

**Fix 2 (reduce the storm — quality, not correctness): collapse redundant
presentable rebuilds during a DPI-boundary drag.**
The storm is partly inherent to crossing a DPI boundary, but 300 rebuilds is
pathological. Options, least-risky first:
- **2a (recommended):** in `PresentingPainter` keep the rebuild decision but
  **coalesce** — if `lockResources` asks to rebuild again within the same pulse
  cadence as the previous rebuild, allow it (it is correct), but skip the
  per-rebuild *INFO* logging (Fix 3) and ensure the disposed presentable's GPU
  resources are released synchronously (ties into NEW-H2). Do **not** add a
  time-debounce that could drop a real final resize (would reintroduce the
  "content fills only a piece" bug the rebuild path was added to fix).
- **2b (investigate, do not assume):** confirm whether the window truly
  ping-pongs between two screens at the boundary or whether a stale per-screen
  scale is re-reported. If a stale read is found, fix it at the source
  (`PresentableState.update` / the per-screen scale lookup added in the
  "true DPI aware per screen" commits) rather than masking in `lockResources`.
  Verify with `-Dskia.resize.diag=true` (already wired, `SkiaPresentable.java:100`).

**Fix 3 (log hygiene): gate the per-presentable "Presentable created" INFO line.**
`SkiaPresentable.java:94-96` logs at `INFO` unconditionally → 300 lines/storm.
Move it behind the existing `skia.verbose`/`skia.resize.diag` flag (or `DEBUG`).
Removes the log flood without touching behavior.

### Verification for Part 1
- Repro: drag a maximized window repeatedly across the 150%↔175% boundary with
  a DropShadow-heavy scene (Ensemble/Modena). Before: `err.txt` fills. After
  Fix 1: zero `RenderState` errors; effects keep rendering across the move.
- Confirm warm-up ran on `QuantumRenderer-0` (add a one-line `DEBUG` log).
- Leak check (Fix 2): presentable rebuild count and GPU mem bounded across 50
  boundary crossings (`-Dskia.verbose` FPS/copy line + the NEW-H2 fix).

---

## PART 2 — bugs.md fix plan (non-breaking, prioritized)

Ordered by (user impact × confidence), each noting why it can't break the
toolkit. IDs map to `.tmp/bugs.md`. The 6 REFUTED items + M20 are **left
untouched** by design.

### Batch 0 — ships with Part 1 (HiDPI correctness, same surface)
These are the regressions the recent DPI/effects commits introduced; fix them
together with the monitor-move work.

- **NEW-H2 — effect resize-fix leaks an RTTexture per resize.**
  `SkiaEffectRenderer.java:171-179` substitutes a raw `createCompatibleImage`
  drawable that is never inserted into `ImagePool.locked`, so `checkIn` drops it
  → deterministic GPU leak on every guard-trip during a resize/monitor-move.
  Fix: route the substitute through `ImagePool.checkOut` (so it lands in
  `locked`), or explicitly dispose the substitute on check-in. Verify: monitor-move
  soak shows flat GPU mem. **Directly compounds the Part-1 storm — do it first.**

- **NEW-H1 — scroll-cache corruption on HiDPI.**
  `CacheFilter.java:635-639` + `moveCacheBy` 835-865 + `computeDirtyRegionForTranslate`
  142-153: device-res cache texture scrolled by logical deltas at 150/175%.
  Fix: scale `lastXDelta/lastYDelta`, the copied sub-rect, and the dirty clip by
  `cachePixelScale` so the scroll/clear happen in device space. Verify: smooth-scroll
  a cache-hinted ScrollPane on a 150% monitor — no trailing strips. **Touch only
  the non-scaleHint Skia path; leave the scaleHint branch (that's H3).**

- **H3 — CacheFilter scaleHint uses `scaleX` on the Y axis.**
  `CacheFilter.java:562-567` (now ~587-589): `setTransform(cachedScaleX,0,0,cachedScaleX,…)`
  — the d (Y) term must be `cachedScaleY` (computed at 563 but unused). One-line,
  fork-introduced, only bites under anisotropic/non-square DPI. Verify: text/cached
  node on a non-square-DPI setup renders correct height.

### Batch 1 — HIGH correctness/leak, bounded scope
- **H6 — JS object wrappers have no identity map** (`BlinkPage.java:1209-1211`,
  `JSObjectImpl.java:43-56`). Add an `objectId → WeakReference<JSObjectImpl>`
  identity cache in `wrapJsObject`; one Cleaner per V8 id, not per wrapper.
  Prevents GC of one wrapper releasing the shared `v8::Global` out from under
  siblings (use-after-release) and fixes `JSObject.equals`. Non-breaking:
  internal-only; public `netscape.javascript.JSObject` contract unchanged.
- **H7 — `javaObjects` never cleared on navigation** (`BlinkPage.java:272,
  1214-1221`). Clear the registry on `DOC_LOADING`/navigation and in `dispose()`.
  Bounded leak fix; no API change.
- **H4 — present-rate-cap deferral strands the final frame when idle.**
  `PresentingPainter.java:332-349`: the deferral sets `freshBackBuffer=true` but
  returns without `requestNextPulse()`, so a quiescent final frame isn't
  presented until an unrelated dirty event. Fix: schedule one follow-up pulse
  (or don't defer the very last frame). Verify: animation ending leaves the
  correct final frame on screen. (Downgraded H→M in Pass 2, but cheap + visible.)
- **H2 — ZoomRadialBlur clips the halo.** `SkiaZoomRadialBlurPeer.java:54-56,74`:
  pad `dstBounds` by ~3σ and switch the blur tile mode to `TILE_DECAL` (matching
  the other blur peers). Cosmetic-correctness; isolated to one peer.

### Batch 2 — MEDIUM robustness / shutdown safety
- **M7 — `PaintCollector.done()` countdown not in `finally`**
  (`PaintCollector.java:269-318`). Move `countDown()` into `finally` so an
  internal-invariant throw can't deadlock the FX thread in
  `waitForRenderingToComplete`. Pure safety; no behavior change on the happy path.
- **M16 — truncated `JS_VALUE` blocks the FX waiter full timeout**
  (`JSValueCodec.java:119-126`, `BlinkPage.java:1602-1607`). Validate the length
  prefix before the read and complete the request on malformed input so the FX
  caller unblocks immediately. Robustness only.
- **M8 — device-loss early-return keeps a stale `presentable`**
  (`PresentingPainter.java:240-244`). Call `disposePresentable()` on the
  device-not-ready path like every other failure path.
- **M17 — deferred GPU teardown may never drain at shutdown**
  (`SkiaMediaTexture.java:449-451`, `PresentingPainter.java:432`). Drain the
  deferred-free queue once on toolkit shutdown / last-window-close so a GC'd
  media texture's GPU SkImage + D3D interop is released.
- **M14 — `removeChild` leaves stale wrapper + listeners**
  (`DomBridge.java:739-742`). Evict `cache`/`wrappers`/`listeners` on
  Java-initiated `removeChild`, matching `removeElement`.
- **M4 — LinearConvolveShadow output cache keyed on recycled
  `identityHashCode`** (`SkiaLinearConvolveShadowPeer.java:138-141`). Key on a
  stable generation/id, or drop the cache (low hit-rate anyway).
- **M6 — passthrough 1×1 drawable created outside the pool**
  (`SkiaPassthroughPeer.java:56-59`). Allocate via the pool so check-in is
  symmetric (same class of bug as NEW-H2).
- **M5 — cached filter handles vs device loss** (multiple peers). Add a
  context-generation guard that drops cached `SkImageFilter` handles on device
  loss. Impact is soft (filters are largely context-agnostic) — low urgency.

### Batch 3 — MEDIUM, tracked Phase-work
- **M18 — `SkiaTextureResourcePool` is a no-op stub** (`SkiaTextureResourcePool.java:26-41`).
  Implement real soft/hard budgets + LRU eviction per the project's memory-management
  policy. Larger effort; schedule as the Phase-3 memory item. Until then it
  is a known gap, not a regression.
- **M13 — frame-data region has no producer/consumer handshake**
  (`FrameSurface.java:103-118`). Add a slot-ack so a fast engine can't overwrite
  a slot mid-copy under render stall. Visual-tearing only; coordinate with the
  Blink transport owner.

### Batch 4 — LOW cleanup (opportunistic, zero-risk)
Do these as we touch the surrounding files; none are crashes:
- **H1 / M2 / M3** — add the symmetric `handle != 0` guard in
  `SkiaGraphics.drawTexture`/`readBack` (native already early-returns; this is a
  consistency fix, not a crash fix).
- **L8 / no-inline-FQN** — replace the remaining inline fully-qualified type
  names (`SkiaBlendPeer.java:119`, `SkiaLinearConvolvePeer.java:162`,
  `SkiaZoomRadialBlurPeer.java:81`, `SkiaEffectRenderer.java:238,257,276`) with
  imports (memory: no-inline-qualified-names).
- **L11 / L1 / L5 / L6 / L21** and the rest of the LOW table — perf/latent; batch
  opportunistically.

### Explicitly DO NOT touch (verified correct)
H5, H8, M1, M11, M12, M19, M20 — the refuted set + M20. Re-flagging them is wasted
effort; bugs.md records why each is safe.

---

## PART 3 — sequencing & global verification

1. **PR 1 (this work, ships first):** Part 1 Fixes 1–3 + Batch 0 (NEW-H2, NEW-H1,
   H3). One coherent "HiDPI monitor-move correctness + effect-init hardening"
   change. Gate: monitor-move soak (50 crossings) shows **zero RenderState
   errors, flat GPU mem, correct scroll/cache on 150/175%**.
2. **PR 2:** Batch 1 (JS bridge H6/H7, present H4/H2).
3. **PR 3:** Batch 2 robustness.
4. **PR 4+:** Batch 3 (budget/eviction — Phase-3 sized), Batch 4 cleanup folded in.

Every PR: build `:javafx.graphics`, run Ensemble/Modena headed, `-Dskia.verbose`
copy+FPS line stays at expected minimums, and a leak-soak per the
`always-verify-no-leaks` rule. No public `javafx.*` signature changes in any PR.
