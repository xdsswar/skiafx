# Graphics stabilization pass (2026-10-18)

A deep bug hunt over `javafx.graphics` (Java Skia pipeline, effect peers,
scene-graph glue) and its natives (`native-skia/shared`, `native-skia/skia3d`),
measured against stock JavaFX 27 and against how Chromium drives Skia.
skia-fx remains an experiment; this pass is about stability and parity, not a
stability guarantee.

## How it was verified

All checks run the *same* compiled test classes against `sdk/lib` (skia-fx,
D3D12) and against stock `org.openjfx:*:27:win` jars:

- **Pixel regression suite** (`node.snapshot()` + `PixelReader`; screen
  capture is not needed): Canvas mirrored/negative draws, image minification,
  node blend modes at 1x/1.5x, gradient banding, `.ttc` collection faces,
  text weight vs. JavaFX's own outline, PhongMaterial specular.
- **Effects suite**: DisplacementMap, InnerShadow (offset 0 / 5), Lighting
  (distant + point), DropShadow, GaussianBlur. skia-fx output is
  pixel-identical to stock on the measured probes.
- **On-screen text** via `javafx.scene.robot.Robot.getScreenCapture` (works on
  this box): ClearType colour-fringing ratio and zoomed crops vs. stock.
- **FFM parity**: every `FunctionDescriptor` in `NativeBridge*.java` checked
  against the C prototype (164 functions), and every `invokeExact` call site
  checked against its descriptor's arity. A mismatch only fails at runtime and
  is often swallowed by `catch (Throwable)`.
- **PerfAB A/B** and a 2-minute memory soak (12 scenes) vs. stock 27.

## Results vs. stock JavaFX 27 (D3D12, 165 Hz panel)

| scene | fps skia-fx / stock | frame sd ms | private MB |
|---|---|---|---|
| idle | 62.5 / 62.5 | 0.53 / 0.62 | 379 / 577 |
| shapes | 136 / 98 | 3.2 / 8.4 | 377 / 529 |
| effects | 121 / 79 | 6.0 / 9.4 | 461 / 505 |
| text | 138 / 115 | 4.9 / 6.9 | 473 / 523 |
| controls | 132 / 123 | 5.6 / 6.2 | 482 / 521 |

Soak: GPU dedicated memory flat (bounded); Java heap after GC grows
~2 MB/scene identically on stock (harness / toolkit, not the Skia pipeline);
end-of-soak private 488 MB vs. 557 MB stock. Known gap: idle working set is
higher (274 vs 175 MB) — mapped Skia/bgfx code pages.

## Fixes

### Crashes, leaks, undefined behaviour
- Native bridge did not compile on Linux/macOS (Windows-only fields/functions
  outside `_WIN32`).
- `create_window_gpu` ran WGL setup (permanent `SetPixelFormat`, a GL context)
  on the D3D12 backend after a transient swap-chain failure; now GL-only.
  `ReleaseDC` no longer runs while the context is current on that DC.
- GL offscreen-FBO realloc/destroy: queued draws flushed before the FBO is
  deleted; Skia's GL state reset after we rebind behind its back.
- `SoftwareGradientCache` could draw an image its own insertion just evicted
  and freed (entries larger than the budget).
- SVG intrinsic size from document attributes could produce a non-finite CTM.
- `QuantumToolkit.dispose` ran GPU frees on the FX thread while the render
  thread could still be running; now the final drain is the last render job.
- `drawString` leaked an ImagePattern shader if `syncBeforeDraw` threw, and
  pinned the longest run's strikes/typefaces in thread-local scratch forever.
- `PrEffectHelper.render` left the Graphics with a null transform on its
  early returns (null filter result) and on exceptions.
- `SkiaRTTexture.readPixels` reported success on a short heap buffer.
- `NGSubScene.renderSG` written cross-thread without `volatile`.

### Per-pulse exception storms (paint pass aborted every frame)
- Drawing an image/media texture with no native image (disposed, or a media
  texture whose first frame has not uploaded) threw; now the draw is dropped.
- Media textures reported "surface lost" until their first frame uploaded,
  so the handler rebuilt them every pulse; upload failures now keep the
  previous frame (Prism's contract) and never skip `releaseFrame`.
- `createTexture(PixelFormat, ...)` threw (SwingNode); returns `null`.
- Image upload failures logged a stack trace every frame; now once.

### D3D12 present
- Back buffers are flushed with `BackendSurfaceAccess::kPresent` (RENDER_TARGET
  -> PRESENT transition) before `Present`, as Skia's own D3D12 window context
  does. Presenting in RENDER_TARGET state is invalid D3D12.
- Grow-only headroom back buffers (DXGI_SCALING_NONE resize work) are exact
  size for STRETCH swap chains; the scaling mode is recreated, not resized,
  when render scale vs. output scale flips; resize failure leaves a
  consistent state; `prime_window` reads back only the content area;
  `set_background_d3d` is an optional symbol.
- GL swap interval tracked per window surface (WGL binds it to the drawable).

### Rendering correctness (parity with Prism)
- Canvas `drawImage` with a negative width/height now mirrors (was dropped).
- Negative-size `fillRect`/`strokeRect`/ovals/round rects draw nothing, as in
  Prism (Skia sorted the rect and painted it).
- A rejected (non-finite) CTM skips the draw instead of drawing at identity;
  very large finite translates (huge scrolled panes) are accepted.
- Blend mode / extra alpha no longer leak past `end_draw` into later
  out-of-bracket native draws (cached shadows, readback).
- `CompositeMode.DST_OUT` mapped (was SRC_OVER).
- Node `BlendMode` at HiDPI: content, background readback and Blend inputs
  now all in device pixels (read the wrong background patch before).
- Effects: DisplacementMap (units, map stretch, offset, wrap, signed half-float
  map, modification-count cache invalidation), InnerShadow (InvertMask pad +
  offset), Lighting (content x light, bump via Sobel), blur peers keep the
  kernel margin around the clip, pooled drawables released on passthrough.
  Native side: `openjfx_skia_effects_ext.cpp`; handle layouts shared through
  `openjfx_skia_handles.h`.

### 3D (bgfx)
- **SCENE3D was unsupported out of the box**: it was still opt-in through
  `OPENJFX_SKIA_D3D=1`, from before D3D12 became the Windows default. It now
  follows the backend: on with D3D12, off when the app forces GL or software.
- **Every mesh was lit from the wrong direction**: the vertex shader decoded
  the normal as q*(+Z); JavaFX's tangent frame (stock `quatToMatrix` /
  `getLocalVector`) uses normal = q*(+X), tangent = q*(+Y),
  bitangent = q*(+Z) negated when q.w < 0.
- **Specular never showed**: the reflection vector was built from the
  eye->point vector; stock reflects point->eye. Also the specular colour FFM
  call passed 5 args to a 6-arg handle (`WrongMethodTypeException`, silently
  swallowed) and the power was hardcoded to 32; colour and power now reach
  the shader.
- **Static 3D SubScenes rendered black on clean pulses and in snapshots**:
  the 3D result was composited into the parent only. The RTT now keeps the
  last pass's zero-copy image and draws it over its own surface (fill + 2D
  children) on every composite.
- Sphere probe vs. stock after the fixes: mean abs difference 0.46/255.

### GPU device-loss recovery
After a D3D12 device removal (TDR, driver reset, adapter change) the device,
Skia context and swap chains are rebuilt and every window renders again;
previously the window froze on its last frame forever. Details and test:
`GPU_DEVICE_RECOVERY.md` (`:samples:perf:runDeviceRecoveryTest`, 15/15 cycles
on hardware).

### Text and image quality (Chromium-level)
Already at Chromium parity (verified): text contrast/gamma (sRGB, contrast
1.0), hinting (slight == DirectWrite normal), subpixel positioning + baseline
snap, unquantized float glyph positions, glyphs rasterized at device size.

- **ClearType (LCD) text** under exactly Prism's conditions (LCD strike,
  opaque Color, SRC_OVER, 2D, no extra alpha). Only on-screen window surfaces
  carry the system's subpixel geometry (`SPI_GETFONTSMOOTHING*`); offscreen,
  effect and translucent layers stay grayscale (Skia falls back by itself),
  which avoids the old translucent-layer ghost stripes. On-screen: 88.1%
  colour-fringed edge pixels vs. 87.3% stock; grayscale text 0%.
  `OPENJFX_SKIA_LCD_TEXT=0` disables.
- **`.ttc` collection faces** (Yu Gothic UI, MS UI Gothic, JhengHei UI,
  Cambria Math...) load at their real face index, memory-mapped
  (`typeface_create_from_file`); previously face 0's outlines were drawn.
- Underline / strikethrough snapped to whole device pixels at fractional DPI
  (stock leaves them straddling rows at 125/150/175%).
- Images minified more than 2x use linear + nearest-mipmap sampling
  (Chromium's default image quality).
- Gradients are dithered (as Blink does); 8-bit banding on subtle gradients
  goes from 100 px flat runs (stock) to 74 px.

## Still open

- 3D stays disabled after a GPU device loss until restart (2D recovers; see
  `GPU_DEVICE_RECOVERY.md`); Canvas pixels are not preserved across a loss.
- Native handles are raw pointers guarded by a magic value; a freed-and-reused
  address can pass the check. A generation-checked handle table would close it.
- Mutable `createTexture(PixelFormat, ...)` texture for SwingNode.
- Canvas content at fractional DPI is bilinear-downsampled from the ceil
  scale (same as stock).
- Idle working set above stock.
