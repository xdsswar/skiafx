# GPU Backends and Present Path

How the Skia 2D pipeline reaches the GPU on Windows: the available backends,
how one is selected, the windowed present tiers, and the D3D12 / OpenGL
internals. Companion docs: device loss is covered in `GPU_DEVICE_RECOVERY.md`,
memory budgets in `GPU_MEMORY_BUDGET.md`, the 3D engine in `3D.md`.

## Backends

| Backend | Status | Notes |
|---|---|---|
| OpenGL (Ganesh GL) | working | Portable baseline; off-screen WGL context, Ganesh GL surface presented to the window. |
| Direct3D 12 | working | DXGI flip-model swap chain backing the Skia surface directly (no intermediate blit); also the device the bgfx 3D engine shares. |
| Metal | roadmap | macOS; not built yet. Requesting it resolves to AUTO. |
| Vulkan | roadmap | Linux/Windows; not built yet. Requesting it resolves to AUTO. |

A single `GrDirectContext` backs the pipeline, one per render thread, built
lazily on the first GPU surface and never per-window or per-frame. It is torn
down only at shutdown or on hard device loss (see `GPU_DEVICE_RECOVERY.md`).

## Selecting a backend

Default is `AUTO` (the pipeline picks the best backend for the platform).
Resolution precedence, highest first:

1. **API** — `Application.setGpuBackend(Application.GpuBackend.DIRECT3D12)`.
   Set it before the first stage is shown (the GPU context is built on the
   first window); the natural place is the app's `init()`.
2. **System property** — `-Dprism.skia.gpu.backend=auto|opengl|d3d12`
   (also accepts `gl`, `direct3d12`, `metal`, `vulkan`).
3. **Env var** — `OPENJFX_SKIA_D3D=0` restores the old GL-first order under
   AUTO (legacy switch; `=1` is now the default behaviour).
4. **AUTO** — platform default. On Windows: D3D12 on a hardware adapter →
   OpenGL on hardware → D3D12 on **WARP** (the Windows software rasterizer,
   used when there is no usable GPU or driver) → CPU raster tier. Hardware
   OpenGL is tried before WARP so a GPU whose driver lacks D3D12 still renders
   on the GPU. Measurements behind this order: `docs/PERF_MEMORY_CLARITY.md`.
   Other platforms keep their own default (macOS/Linux paths are separate).

Forcing D3D12 tries hardware, then WARP, then OpenGL. Forcing OpenGL uses GL
only (then the CPU raster tier). `OPENJFX_SKIA_D3D_WARP=1` forces WARP, for
testing the no-GPU path on a machine that has a GPU. The startup log says when
the device is WARP: `Skia GPU enabled: Direct3D 12 on the WARP software
rasterizer (no usable GPU).`

Enum: `GpuBackend { AUTO, OPENGL, DIRECT3D12, METAL, VULKAN }`. Requesting a
backend that is not available on the current platform/build (e.g. `METAL` on
Windows, or `DIRECT3D12` when device init fails) **degrades to the most
suitable backend** rather than failing.

### Plumbing

- Java: `com.sun.prism.skia.impl.SkiaGpu#probe()` resolves the request
  (API > property > env), maps it to a native preference code through
  platform suitability (`toNativePref`), calls `NativeBridge.setGpuBackend(pref)`
  *before* the first `surfaceCreateGpu`, then queries
  `NativeBridge.activeBackend()` and logs the backend that is **actually**
  active — e.g. `Skia GPU enabled: Direct3D 12.` — and notes any fallback. The
  log is not hardcoded; it reflects the real backend.
- Public API: `javafx.application.Application#setGpuBackend/getGpuBackend`
  (additive; mirrors the VSync extension).
- Native: `openjfx_skia_set_gpu_backend(int)` / `openjfx_skia_get_active_backend()`
  in `openjfx_skia_bridge.cpp`. `gpuDirectContext()` honours the explicit
  preference (force GL / force D3D) and otherwise applies the AUTO order above
  (`d3dPreferredUnderAuto()` reads the `OPENJFX_SKIA_D3D` legacy switch).
  Pref codes: `0 AUTO, 1 GL, 2 D3D12, 3 METAL, 4 VULKAN` (3/4 reserved → AUTO).
  The FFM bindings are looked up optionally, so an older native library without
  these symbols still loads (selection falls back to env/AUTO).

## Windowed present tiers

`com.sun.prism.skia.SkiaPresentable#allocate()` picks the highest tier that
succeeds, in order:

1. **D3D12 swap chain** — `NativeBridge.surfaceCreateSwapChainD3d(hwnd, w, h)`;
   active only when the GrDirectContext backend is D3D. Skia draws straight into
   the current back buffer (zero intermediate).
2. **GL direct framebuffer** — Ganesh GL FBO 0 presented via `SwapBuffers`.
3. **GPU offscreen + readback** — for windows that can't take a direct surface.
4. **Software raster** — final fallback.

HWND validation (`openjfx_skia_surface_create_swap_chain_d3d`) rejects layered
(`WS_EX_LAYERED`) and owned (`GW_OWNER != null`) windows and returns 0 so the
tier search falls through — owned windows on a secondary GPU otherwise present
nothing (the readback tier handles them).

## Direct3D 12 internals (`openjfx_skia_d3d_win.cpp`)

- **Init** — `ensureD3DInitialized()`: `CreateDXGIFactory2`, enumerate hardware
  adapters, `D3D12CreateDevice` (feature level 11.0), a DIRECT `CreateCommandQueue`,
  and an `IDXGIFactory5` `DXGI_FEATURE_PRESENT_ALLOW_TEARING` probe.
  `openjfxD3DMakeContext()` then builds `GrDirectContext::MakeDirect3D`; any
  failure logs and returns null so the bridge falls back to GL.
- **Swap chain** — `openjfxD3DCreateSwapChain()`: `DXGI_SWAP_CHAIN_DESC1`,
  `R8G8B8A8_UNORM`, 3 buffers, `DXGI_SWAP_EFFECT_FLIP_DISCARD`, `ALLOW_TEARING`
  flag when supported; `CreateSwapChainForHwnd` → `IDXGISwapChain3`.
  `wrapBackBuffers()` wraps each back buffer as a `GrBackendRenderTarget` →
  `SkSurfaces::WrapBackendRenderTarget` (state `D3D12_RESOURCE_STATE_PRESENT`).
- **Per-frame acquire** — `openjfxD3DAcquireNextBuffer()`:
  `GetCurrentBackBufferIndex()`, then a per-buffer fence wait so the CPU keeps a
  bounded lead (3 buffers, no extra frame-latency stall).
- **Present** — `openjfxD3DPresent(vsync)`: `grCtx->flushAndSubmit()`, then
  `Present(syncInterval, flags)` where `syncInterval = vsync ? 1 : 0` and
  `flags = vsync ? 0 : (allowTearing ? DXGI_PRESENT_ALLOW_TEARING : 0)`; signals
  the per-buffer fence after Present. Present failures classed as device removal
  latch device-lost.
- **Resize** — `openjfxD3DResize()`: drain the fence, `flushAndSubmit(kYes)`,
  drop the wrapped surfaces, `ResizeBuffers`, re-wrap. DWM treats `ResizeBuffers`
  as a semantic resize, so there is no stretch artifact during a drag (the GL
  tier defers FBO 0 reallocation and can show a one-frame stretch).

## OpenGL backend

`ensureWindowsGlContext()` creates an off-screen WGL context;
`GrGLMakeNativeInterface` + `GrDirectContexts::MakeGL` build the context. The
window surface wraps FBO 0; present is `SwapBuffers`; resize re-wraps FBO 0
without destroying it.

## VSync

`Application.vsyncEnabledProperty()` (default on, global to all windows) flips
the per-`Present` sync interval at runtime — no swap-chain rebuild. Off presents
uncapped (`Present(0, ALLOW_TEARING)` on D3D), which is how throughput exceeds
the display refresh on a high-refresh panel; on presents vblank-synced. See
`RESIZE_INVARIANTS.md` for how present interacts with resize.

## 3D and the shared device

The bgfx 3D engine shares the Skia D3D12 device and queue
(`d3d12_device()` / `d3d12_queue()` exports) so 3D textures alias 2D VRAM
zero-copy. 3D therefore requires the D3D12 backend; under the GL backend the 3D
path is unavailable. A single shared device means one shared device-lost latch
(see `GPU_DEVICE_RECOVERY.md`).

## Key files

- `javafx.graphics/src/main/java/com/sun/prism/skia/SkiaPresentable.java`
- `javafx.graphics/src/main/java/com/sun/prism/skia/impl/SkiaGpu.java`
- `javafx.graphics/src/main/java/com/sun/prism/skia/impl/NativeBridge.java`
- `javafx.graphics/src/main/java/javafx/application/Application.java`
- `javafx.graphics/src/main/native-skia/shared/openjfx_skia_bridge.cpp`
- `javafx.graphics/src/main/native-skia/shared/openjfx_skia_d3d_win.cpp`
