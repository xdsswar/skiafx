# GPU memory budget (M18)

Replaces the Phase-1 no-op `SkiaTextureResourcePool` with real accounting, a
soft/hard budget, observability, and (opt-in) eviction. Implements the project's
memory-management goal: GPU memory **bounded, observable, predictable**.

## Status

| Phase | What | Default |
|---|---|---|
| **18a** | Byte accounting + budgets + reclaim-on-pressure + shutdown dump | **ON** |
| **18b** | Active LRU eviction of unlocked render targets | **OFF** (opt-in) |
| **18c** | Per-category budgets (atlas/glyph/SkPicture) + image re-decode fallback | **roadmap** |

## What's accounted today
Everything through `SkiaTextureBase` — standalone image textures
(`SkiaImageTexture`) and render targets (`SkiaRTTexture`, incl. the presentable's
surface). Accounting is **exact across both release paths**: the ctor calls
`recordAllocated`, and wraps the native destroy callback so `recordFree` fires on
the single native-free point (explicit `dispose()` *or* the leaked-wrapper
Cleaner). The byte size is computed as a ctor-local (never captured via `this`),
so the Cleaner can still collect the wrapper.

**Media textures (`SkiaMediaTexture`) are now counted too** — the per-frame GPU
`SkImage` (`~w*h*4`) is `recordAllocated` on upload and `recordFree`d in lockstep
with the one-slot retire / teardown, so a playing video's GPU footprint shows up
in `managedBytes` with no drift across its create/retire/dispose paths. Effect
intermediate RTs are already bounded by the effect `ImagePool`.

## Configuration (system properties)
- `-Djavafx.gpu.textureBudget=1g` — hard cap (default 1 GiB). Suffixes `k`/`m`/`g`.
- `-Djavafx.gpu.textureTarget=768m` — soft target (default 75% of the cap); 18b
  evicts down to this.
- `-Djavafx.gpu.evictUnlockedRTs=true` — enable 18b active eviction (default off).
- `-Djavafx.gpu.memDump=true` — print managed/peak/evictions/warns on shutdown.
  (Also parsed for later use: `javafx.gpu.atlasBudget`, etc. — 18c.)

## Behavior under pressure (`prepareForAllocation`, render thread)
1. If `managed + size ≤ max` → allow.
2. Else reclaim already-dead natives: `Disposer.cleanUp()` + `NativeHandles.drainDeferred()`; re-check.
3. Else, if `evictUnlockedRTs` → evict unlocked, non-permanent, live RTs **LRU-first**
   (dispose their `SkSurface`) down to the soft target; re-check.
4. Else → **allow anyway**, with a rate-limited WARNING. The hard cap *degrades*,
   it never crashes (errors-degrade rule). An evicted RT's holder re-renders on next use via
   `isSurfaceLost()` (CacheFilter + the effect ImagePool already do this).

## Why 18b is opt-in
Evicting a live-but-unlocked RT is only safe for holders that re-check
`isSurfaceLost()` before drawing from/into it. CacheFilter and the effect
ImagePool do; not every path is audited, and this can't be runtime-verified
without a GPU soak. So eviction is gated until proven. With it off, the budget
still accounts + reclaims + degrades gracefully — the common case (managed ≪ max)
never evicts anyway. Presentables and non-owning (SkPicture) wraps are **never**
tracked as candidates.

## 18c roadmap (not started)
- Per-category budgets for glyph atlas / image atlas / SkPicture cache — wired in
  as those subsystems land (they don't exist in the Skia pipeline yet; Phase-4 work).
- Image-texture eviction needs `SkiaImageTexture` to retain its source/decode info
  to reload (the re-decode fallback); then 18b's eviction can cover image textures.
- Account `SkiaMediaTexture`.
- Optional JMX bean (system-property dump is the first cut).

## Verify (18a / 18b)
Run an effects+image-heavy scene and a long media/WebView soak under
`-Dskia.verbose -Djavafx.gpu.memDump=true` (add `-Djavafx.gpu.evictUnlockedRTs=true`
+ a small `-Djavafx.gpu.textureBudget=128m` to exercise eviction): on shutdown the
`[gpu.mem]` line shows managed tracking real allocations, staying under `max`,
with `evictions` rising only under pressure and no visible flicker (no wrongly-
evicted live RT). Leak-soak: managed returns to ~baseline after closing windows.
