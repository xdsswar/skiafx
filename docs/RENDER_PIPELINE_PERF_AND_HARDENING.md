# Render Pipeline: CPU-Path Performance + GPU-Path Hardening

Authoritative record of the `pipeline/cpu-perf-gpu-hardening` branch work
(2026-06-10). skia-fx is an experiment, not stable; numbers below are from
the dev machine (Windows 11, 144 Hz display, 100% scale) and will differ
elsewhere — re-measure with the harness before drawing conclusions.

## TL;DR

The software-raster (READBACK) tier was below 24 fps on animating scenes.
Root causes found and fixed, in order of impact:

1. **Raster surfaces were created in the non-native byte order** —
   `kRGBA_8888` on a Windows Skia build whose native N32 is BGRA
   (`SK_R32_SHIFT=16`). Every image blit (cached nodes, region caches)
   missed Skia's specialized blitters and ran the general per-pixel
   pipeline at ~75 ns/px. A 780×420 cached-node blit cost **25 ms; 0.2 ms
   after switching to `kN32`** (125×). This was THE systemic slowdown.
2. **Full-frame present for any change** — a blinking caret moved ~25 MB
   per frame (full readback + full copy + full GDI blit). Now the painter's
   dirty-region union flows to the presentable: the readback and the OS
   blit shrink to the changed rect, with per-buffer and per-window
   staleness tracking keeping pooled buffers and dropped frames correct.
3. **Fading groups re-rasterized their subtree every frame** — re-landed
   the CPU-only auto subtree cache (originally removed in 2be6e0ef) with
   its three defects fixed structurally (separate render-thread-owned
   field; automatic setCache reconciliation; Disposer-backed lifecycle).

GPU path hardening (no happy-path change): device-lost latched on the whole
removal class, D3D tier window bails mirroring GL, glGetError hygiene,
deferred-GL-delete drain on present, GL failure degrade chain with a
process-wide tier-demotion latch.

## Benchmark harness

`samples/perf` (`runPerfBench`) — four scenes, JSON line per second:

```
./gradlew :samples:perf:runPerfBench -Pperf.scene=fade -Pperf.cpu=true \
    -Pperf.seconds=10 [-Pperf.width=. -Pperf.height=. -Pperf.out=path]
```

Scenes: `static` (caret-only dirty rect), `scroll` (full-viewport text/
controls), `fade` (large group under FadeTransition), `full` (everything
moves). Metrics: `pulseHz`, `presentFps` (`SkiaPresentable.LAST_PRESENT_FPS`),
`paintsPerSec`/`paintAvgMs` (`PaintStats`, always-on), `Copies` counters
(`PRESENT_COPY` = readback-tier present copies, 2/frame steady state).
Do not combine with `-Dskia.verbose` (it consumes the same counters).

## Results (1080p, this machine)

| Scene (CPU path) | before | after | notes |
|---|---|---|---|
| fade   | 18.5 fps / 51 ms | **110 fps / ~1.5 ms** | N32 + auto-cache |
| static | full-frame copies per caret blink | caret-rect-only (195 px diff verified on screen) | partial present |
| full   | 62 fps (pulse cap) / 6 ms | 62 fps / 6.6 ms | was never blit-bound |
| scroll | 19–22 fps / 42–60 ms | 22–24 fps / 44 ms | see "deferred" below |
| GPU (GL & D3D12), all scenes | 144–147 fps | 144–147 fps | parity, unchanged |

## What changed (by commit)

1. `perf(bench)` — real benchmark scenes + `PRESENT_COPY` category +
   `PaintStats` + diag-flag hoisting in `PresentingPainter`.
2. `fix(skia/gpu) PR-1` — failure-path guards:
   - D3D Present/ResizeBuffers latch device-lost via
     `GetDeviceRemovedReason() != S_OK` (covers DEVICE_HUNG etc.);
     AcquireNextBuffer early-out when lost.
   - `create_swap_chain_d3d` bails for `WS_EX_LAYERED` / owned windows
     (read-only style checks, same rationale as the GL tier) → readback tier.
   - `allocateOffscreenFbo`: glGetError drain + storage-alloc checks.
   - `present_window` drains deferred GL deletes (orphaned FBO VRAM was
     reclaimed "maybe never").
   - `window_clear` error drain (log-once, diagnostic).
3. `perf(skia/cpu)` Stage 1 — readback writes straight into the pooled
   direct-buffer Glass `Pixels` (3 → 2 copies/frame); cached
   `MemorySegment` views; optional `create_raster_bgra`
   (`-Dskia.raster.bgra`, ships OFF — superseded by the N32 fix).
4. `perf(skia/cpu)` Stage 2 — dirty-rect partial present:
   `ViewPainter` painted-union → `prepare(rect)` →
   `read_pixels_argb_stride` → `RectQueuedPixelSource` →
   `PresentableState/SceneState.uploadPixelsRect` → `View.uploadPixelsRect`
   (full-upload default; Windows partial `SetDIBitsToDevice`).
   Buffer staleness: ring of last 8 frame rects + per-Pixels fill seq.
   Window staleness: the source unions dropped frames' rects into the next
   upload. Transparent windows always take the full `UpdateLayeredWindow`
   path. Stale-native-lib fallback latch in `View`.
5. `fix(skia/gpu) PR-2` — GL degrade chain: resize falls back to FBO 0
   in place; present blit error → still SwapBuffers, rc 4 → rebuild;
   5 consecutive GL present failures → tier permanently demoted
   (process-wide by design; one WARNING).
6. `perf(skia/cpu)` — **kN32 raster surfaces** + aligned-blit fast path
   (kNearest + kFast for 1:1 integer-aligned blits). See TL;DR #1.
   `OPENJFX_SKIA_DS_DIAG=1` prints per-blit snapshot/blit timing.
7. `perf(skia/cpu)` — auto subtree cache re-land. Policy: NGGroup-only,
   ≥ 8 children, 256²–4096² px area, attach after 3 stable fading frames,
   process cap 8. Lifecycle via per-node `AutoCacheRecord` registered with
   `Disposer` (normal detach / `release()` / GC all dispose exactly once,
   on the render thread).

## Flags (all kill switches default-sane)

| Flag | Default | Meaning |
|---|---|---|
| `-Dskia.partial.present=false` | partial ON | full readback + blit per present |
| `-Dskia.autocache=false` | ON (software only) | disable auto subtree cache |
| `-Djavafx.autocache.max/attachFrames/minArea/maxArea/minChildren` | 8/3/256²/4096²/8 | auto-cache tuning |
| `-Dskia.raster.bgra=true` | OFF | explicit-BGRA Tier-3 surface (moot post-N32) |
| `-Dskia.paint.diag` / `skia.resize.diag` / `skia.present.diag` | off | painter diagnostics |
| `OPENJFX_SKIA_DS_DIAG=1` | off | per-blit snapshot/blit ms in draw_surface |

## Deferred follow-ups (with decision gates)

- **Scroll-class scenes (tile-parallel raster / command recording).**
  Post-N32 profile of `scroll` (full-viewport dense controls): ~26% of
  render-thread time in the per-draw `surfaceBeginDraw` bracket (thousands
  of FFM crossings/frame), ~38% in path fills (Modena control skins),
  ~28% in glyph drawing. No single hotspot — the levers are (a) recording
  the frame into an SkPicture and replaying into horizontal bands on a
  small native thread pool (recorder infra already exists in the bridge),
  and/or (b) coalescing the per-draw begin/end bracket. Gate was
  ">12 ms avg paint after Stages 1–3" — scroll sits at ~44 ms, so this is
  justified whenever someone takes it on.
- **GPU device recovery** — see `docs/GPU_DEVICE_RECOVERY.md`. Today a
  lost device degrades safely (latched, frozen frame, no crash) but never
  recovers.
- **Software pulse-cap flapping** — with paints now sub-2 ms, the ≤60 fps
  software pulse cap intermittently disengages (observed 62.5 ↔ 145 Hz
  windows on the fade scene under `fullspeed`). Harmless (more fps when
  cheap; paint-limited machines never see it), but the cap logic in
  `QuantumToolkit` could be made sticky.
- **Linux/macOS partial blit natives** — `View.uploadPixelsRect` falls
  back to full uploads there (correct, not yet fast). GTK: cairo rect
  paint; macOS: `setNeedsDisplayInRect:`.
- **D3D adapter selection** — first hardware adapter; a discrete-GPU
  preference (`EnumAdapterByGpuPreference`) would be a happy-path change,
  out of hardening scope.

## Verification done on this branch

- Bench parity per stage on GL and `OPENJFX_SKIA_D3D=1` (full scene,
  144–147 fps, no new stderr).
- Partial-present correctness: consecutive window captures diff only in
  the caret rect (195 px) / focus row; zero diffs between blinks; scroll
  scene visually clean (no offset/stale rects).
- N32 color correctness: hue-ordered card scene captured and inspected —
  no R/B swap, gradients/text correct.
- Auto-cache: pulse logger shows exactly one attach + one cache build per
  fade; `-Dskia.autocache=false` control run 39.5 fps vs 110 fps.
- UnsatisfiedLinkError path (stale glass.dll): exercised live; the View
  fallback latch logs once and presents full-frame.

Remaining manual scenarios worth running before merging to master:
`dxcap -forcetdr` during D3D animation (expect one latch log, frozen
frame, no crash); cross-DPI monitor drag; minimize/restore + overlapping
windows on the CPU path; 10-minute drag-resize + window-churn soak with
flat VRAM/GDI counts; `-Djavafx.gpu.memDump=true` balanced at exit.
