# GPU Device Recovery — Design Notes (not yet implemented)

Status (2026-06-10): **deferred by decision.** Device loss is fully
*guarded* — `gDeviceLost` latches (TDR, adapter change, DEVICE_HUNG via
`GetDeviceRemovedReason()` probes on every failed Present/ResizeBuffers),
`SkiaResourceFactory.isDeviceReady()` stops all GPU work, windows hold
their last frame, the JVM never crashes — but the device is never
*recreated*. `NativeBridge.recoverDevice()` resolves
`openjfx_skia_device_recover`, which has **no native implementation**;
the optional FFM lookup degrades it to `return false`.

## What a real implementation needs

Native (`openjfx_skia_d3d_win.cpp` + `openjfx_skia_bridge.cpp`):

1. `gpuDirectContext()` (bridge.cpp, `gpuDirectContext` function-local
   static) must stop being write-once: refactor to a mutable
   `static sk_sp<GrDirectContext>` behind an accessor so it can be
   released and rebuilt. Same for the `D3DGlobal` state (`g()` in
   d3d_win.cpp) — `releaseD3DGlobals()` must reset `initialized` so
   `ensureD3DInitialized()` can run again.
2. Teardown order on recovery:
   a. Java side first: `SkiaResourceFactory.attemptDeviceRecovery()`
      already drops all Java-held GPU resources via factoryReset
      listeners — this must complete before native teardown.
   b. `grCtx->releaseResourcesAndAbandonContext()` on the old context
      (the device is gone; free Skia's bookkeeping without touching it).
   c. Destroy any remaining swap chains (presentables are already
      disposed by the painter's device-not-ready path, but assert/sweep).
   d. `releaseD3DGlobals()` → `ensureD3DInitialized()` →
      `GrDirectContexts::MakeDirect3D` → re-register
      `shared_gr_context()` for the 3D DLL.
   e. Clear `gDeviceLost` LAST, after everything above succeeded.
3. **bgfx is the riskiest piece**: `ensureBgfxInit()`
   (openjfx_skia3d_bridge.cpp) is one-shot with no shutdown path. A
   recovery must `bgfx::shutdown()` + make init re-runnable + invalidate
   every Java-side 3D resource (meshes, RT textures, the Scene3D
   instances). Until bgfx can cycle, recovery with 3D content loaded
   should either be refused (stay latched) or the 3D subsystem must be
   left permanently disabled post-recovery (degrade 3D, recover 2D).
4. Resources rebuild lazily on the next pulse (textures re-upload on
   first use; presentables recreate per window). The effect-class
   warm-up in `SKIAPipeline.init` already guards against classload
   storms during the rebuild.

## Java side (mostly exists)

`SkiaResourceFactory.attemptDeviceRecovery()` (called from
`isDeviceReady()`) already: disposes GPU resources, calls
`NativeBridge.recoverDevice()`, and returns rendering to normal when it
reports success. The contract holds as long as the native call only
returns 0 after a complete, verified re-init.

## Test plan

- `dxcap -forcetdr` (Windows SDK) repeatedly while a D3D-backed window
  animates: rendering must resume within a few pulses, VRAM must return
  to baseline (`-Djavafx.gpu.memDump=true`), no crash across ≥ 10 cycles.
- Cross-DPI monitor drag (the historical loss trigger).
- With and without 3D content loaded (see bgfx caveat above).
- GL backend: no equivalent loss model — out of scope (GL tier demotion
  already covers persistent GL failure).
