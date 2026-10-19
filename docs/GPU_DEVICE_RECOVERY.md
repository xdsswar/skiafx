# GPU Device Recovery (D3D12)

Status (2026-10-18): **implemented and tested.** When the shared D3D12
device is removed — TDR, driver update/reset, adapter change — rendering
stops (no GPU calls on the dead device), the device is rebuilt within a few
hundred milliseconds, and every window renders again on the new device.
2D recovers fully. 3D stays disabled after a loss if it was running (see
below). GL has no equivalent loss model; the GL tier demotion covers
persistent GL failure.

## Why it is not just "make a new device"

D3D12 devices are **per-adapter singletons**: while anything in the process
still references the removed `ID3D12Device`, `D3D12CreateDevice` on that
adapter returns the same dead device. Every Skia GPU surface and
texture-backed image holds the `GrDirectContext` (`skgpu::ganesh::Device`
keeps `sk_sp<GrRecordingContext>`), the context's `GrD3DGpu` holds the device
and queue, swap chains hold the queue, and bgfx holds both. Java only learns
that its objects are stale *after* recovery, so waiting for Java to release
them would deadlock. Recovery therefore releases them natively, in place.

## Sequence

Java, render thread (`SkiaResourceFactory.isDeviceReady` →
`attemptDeviceRecovery`), while `openjfx_skia_device_lost()` reports 1:

1. Once per loss: notify `ResourceFactoryListener`s (`factoryReset`), drop
   the factory texture caches, run the Cleaner-deferred frees, and call
   `NativeBridge3D.releaseDevice()`.
2. `NativeBridge.recoverDevice()` → `openjfx_skia_device_recover()`:
   1. `releaseResourcesAndAbandonContext()` on the old context.
   2. `releaseGpuObjectsForDeviceLoss()`: every live `OpenJfxSurface` (tracked
      by its constructor/destructor) loses its GPU `SkSurface` / D3D swap
      chain, and every live image handle (`openjfx_skia_handles.h` registry)
      loses a texture-backed `SkImage`. The handle structs stay valid but
      empty, so Java draws on them are no-ops. Raster surfaces and images are
      kept (they do not reference the device).
   3. Drop the context slot, so the old context and device are destroyed.
   4. `openjfxD3DRecreateContext`: release the D3D globals, reset the
      adapter-tried flags, build a new device/queue and `GrDirectContext`.
      For the first 3 s of a loss only hardware adapters are accepted (a
      reset driver takes a moment to come back); after that WARP is accepted
      so the window renders regardless.
   5. Bump `skia_fx::d3d12_device_generation()`, install the context, and
      clear the lost latch last.
3. On success `SkiaGpu.refreshDeviceGeneration()`. A failure (driver still
   resetting) retries with back-off: 250 ms doubling to 4 s, for as long as
   the device stays lost.

After that, Prism's own device-reset contract rebuilds everything lazily:
GPU-backed textures, render targets, presentables and media textures report
`isSurfaceLost()` once their creation generation differs from the current
one (`SkiaTextureBase.isFromReplacedDevice`), and 3D meshes, materials and
mesh views report `isValid() == false`. `NGCanvas`, `CacheFilter`,
`NGSubScene`, `NGRegion`, the effect `ImagePool`, `PresentingPainter`
(`lockResources`) and the 3D NG classes already react to those exactly as
they do for Prism D3D. Image textures hold raster pixels and survive.

## 3D

bgfx shares Skia's device, and `bgfx::init()` cannot run a second time
in-process on this architecture (docs/3D.md §10.2). If bgfx was running at
the time of the loss, `openjfx_skia3d_release_device()` releases our D3D12
render targets and calls `bgfx::shutdown()` (its fence waits complete at
once on a removed device), so the device can be freed. 3D content then
renders nothing (no exceptions: `SkiaMesh` reports a successful no-op build)
until the application restarts. If bgfx had never been initialized, 3D
initializes normally on the new device. The 3D library also refuses all GPU
work whenever the device generation differs from the one bgfx was
initialized on (`deviceGone()`).

## Not preserved

- Canvas pixel content: the RT is not volatile, so there is no CPU copy.
  Stock Prism on D3D behaves the same after a device removal.
- Any texture-backed image the app does not re-supply (all JavaFX images
  are raster-backed and re-upload automatically).

## Test

`./gradlew :samples:perf:runDeviceRecoveryTest [-Prec.cycles=N] [-Prec.3d=true]`
(Windows, needs a display). It removes the device for real
(`ID3D12Device5::RemoveDevice` via `openjfx_skia_test_remove_device`) N times
while an animated scene (DropShadow, text, Canvas, image, an idle second
window, optionally a 3D SubScene) is shown. Each cycle must advance the device
generation, show freshly set content on screen and in a snapshot, and keep the
idle window intact. The exit code is the number of failed cycles.

Measured (2026-10-18, AMD RX 6800): 15/15 cycles recovered onto the hardware
GPU, typically in 20–390 ms, with bounded committed memory. With a live 3D
SubScene: 6/6, no crash, 2D recovered, 3D blank afterwards as designed.
