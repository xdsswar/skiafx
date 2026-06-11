# Blink integration — `skia-fx-webview`

Authoritative architecture doc for replacing WebKit with a Blink/Chromium
engine behind `javafx.scene.web.WebView` / `WebEngine`. Keep this current as
the implementation lands; plan files are ephemeral, this is not.

Status: **P0 (build plumbing) done; engine compiling.** Phases below.

---

## Goal & constraints

Replace the native WebKit port with the **Chromium content layer** (Blink +
V8 + cc/viz + net), rendered **off-screen and composited into the JavaFX
scene's Skia surface**, so `WebView` behaves exactly as the WebKit-backed one
did (a real scene node: transform / clip / opacity / z-order / `snapshot()`,
plus full `org.w3c.dom`) but with chrome-grade rendering.

- **Public API unchanged**: `javafx.scene.web.*`, module name `javafx.web`,
  jar name/exports identical.
- **Upper Java shape unchanged**: `WebEngine`/`WebView`/`WebPage` method shape,
  `org.w3c.dom` via `com.sun.webkit.dom.*`.
- **Multi-process** Blink (engine subprocess), Chromium's native model.
- **Engine artifacts named `skia-fx-webview`** (`.dll/.exe/.pak`).
- Pinned **Chromium 147.0.7727.56**; everything build-side configurable.

Decisions and rationale captured per section below.

---

## Rendering — the fast path (primary) + CPU fallback

The performance requirement: **high FPS, no rendering delays, with a CPU
fallback**. The frame-delivery mechanism is where this is won or lost — *not*
the control transport.

### Key discovery: the consumer half already exists

skia-fx already ships `javafx.graphics/.../native-skia/shared/
openjfx_skia_d3d11_interop.{cpp,h}` — a **D3D11 ⇄ OpenGL zero-copy bridge**
(`WGL_NV_DX_interop2`) built for the media zero-copy plan. It:

- owns a process-wide `ID3D11Device`;
- `register_texture(d3d11Texture) -> GL texture` aliasing the **same VRAM**
  (no CPU copy); `lock`/`unlock` for sync;
- Skia (Ganesh **GL** backend) wraps that GL texture as `SkImage` via
  `GrBackendTexture`;
- already **falls back to a CPU upload path** when interop init fails.

So skia-fx's Skia is GL-backed and shares D3D11 textures with GL on Windows.
We reuse this bridge for WebView frames.

### Accelerated path (default — zero-copy)

```
Chromium GPU process (ANGLE/D3D11)
  render compositor frame → shared D3D11 texture
     (IDXGIResource1::CreateSharedHandle, NT handle, keyed mutex)
        │   ring buffer carries only "frame N ready + damage rect"  (NOT pixels)
        ▼
skia-fx render thread (reuse openjfx_skia_d3d11_interop)
  ID3D11Device::OpenSharedResource1(handle)  [cross-process, same device]
  → register_texture() → GL texture (VRAM alias, zero copy)
  → GrBackendTexture → SkImage → drawImage into scene canvas at node xform/clip
        ▼
  JavaFX scene SkSurface → SkiaPipeline present
```

No readback, no CPU bounce; the Blink frame's VRAM is sampled directly.

### No rendering delays — how

- **Decoupled cadence.** Blink renders uncapped on its side; the JavaFX pulse
  samples the **latest-ready** texture and never blocks on Blink; Blink never
  blocks on JavaFX. This removes pipeline stalls. (Aligns with the project's
  uncapped-FPS / display-driven frame-rate policy.)
- **Triple-buffered shared textures + keyed mutex / fence.** Producer writes
  buffer N+1 while consumer samples N → no torn frames, no waiting.
- **Ring buffer is out of the pixel path** — it delivers only a small
  "frame ready" wakeup that triggers `NGWebView.requestRender()`. Never the
  throughput limiter.
- **No per-frame heap on the render thread** — pool the `SkImage` wrappers /
  import records (the project's render-thread zero-allocation rule).

### CPU fallback (automatic)

When D3D11 interop init fails (no GPU, SwiftShader, RDP, VM, driver mismatch)
or when forced: Blink renders to a **shared-memory BGRA bitmap** (CEF
`OnPaint` model) with a damage rect; skia-fx uploads it through a persistent
staging texture (one copy). Selection mirrors the media path's existing
`interop_ready()`-or-fallback logic.

### `FrameSink` abstraction

A single Java + native `FrameSink` hides which path is active. The software
path stands up first (also the fallback, and the quickest way to get correct
pixels on screen); the accelerated D3D11-shared path is the default for speed.
`NGWebView.renderContent(g)` resolves the scene canvas via
`SkiaSurfaceAccess.handleOf(g)` → `skia_fx::resolve_canvas` and asks the
`FrameSink` to draw the latest frame at the current matrix/clip.

### Cross-platform

- **Windows**: shared D3D11 texture + `NV_DX_interop2` (above) — reuse exists.
- **macOS**: `IOSurface` → Metal/GL import. Same shape, new handle type.
- **Linux**: `dmabuf` / `EGLImage`. Same shape.
Software fallback is cross-platform from day one.

### Reuse vs new

- ✅ Reuse: consumer-side D3D11→GL→Skia zero-copy bridge (built, smoke-tested).
- 🔨 New: (a) open a **cross-process** shared handle on skia-fx's device
  (`OpenSharedResource1` + keyed mutex) — small extension to the interop;
  (b) producer side — Blink OSR into a shareable texture, emit handle
  (modeled on CEF `OnAcceleratedPaint`); (c) triple-buffer swap + frame-ready
  signal; (d) software shared-mem path + staging upload.

---

## Transport — shared-memory ring buffers (control / events / signals)

Ported from jux (`com.sun.webkit.blink`, FFM/Panama):

- **Java→engine command ring**, **engine→Java event ring** (SPSC, lock-free),
  heartbeat + watchdog, `EngineProcessManager`, command/event type enums.
- Carries: navigation, `executeScript`, bounds/resize, **input events**
  (high-frequency — ring is ideal), DOM commands, and engine callbacks
  (load status, console, URL, **frame-ready signal**).
- **Request/response** (e.g. `executeScript` result, DOM getters) ride the
  ring via a correlation id + a bounded wait on the event ring.
- Large payloads (big `innerHTML`, long scripts) use jux's overflow data
  region, not a fixed slot.

Why the ring (not pipes/sockets/Mojo): input events and frame signals are
high-frequency and latency-sensitive; lock-free shared memory avoids a syscall
per message. Pixels never travel the ring. Mojo has no Java bindings.

---

## DOM — full `org.w3c.dom` over a node-id bridge

- Keep `com.sun.webkit.dom.*` (the ~109 `org.w3c.dom` impls) and the public
  `WebEngine.getDocument()` contract.
- Their native peer calls are rewired to `com.sun.webkit.blink.DomBridge`,
  which maps the opaque `long peer` to a Blink **node-id** and issues
  ring-buffer DOM commands (the engine's `jux_dom.mojom` handler already
  implements create/append/setAttr/textContent/listener/…).
- **Synchronous getters don't stall the FX thread**: a Java-side DOM cache is
  populated by tree-sync events (`on_dom_element`/`on_dom_text`), mutations
  write through; most getters resolve locally. This — not the transport — is
  the fix for out-of-process synchronous DOM.
- `executeScript` / `JSObject` interop via `SfxWebExecuteJS` + result event.

---

## Java coding constraints (hard rules)

These apply to all Blink-integration Java code (`com.sun.webkit.*`,
`com.sun.webkit.blink.*`, `com.sun.javafx.*`, and anything in `javafx.web`):

- **No `CompletableFuture`** in `javafx.web` (public or internal). Async
  results are delivered through **custom `<T>` callback interfaces** (named,
  e.g. `interface ResultHandler<T> { void onResult(T value); }` — with an
  `onError` where failures are possible), invoked **on the FX thread**. Do
  **not** use `java.util.function.Consumer<T>`. `javafx.concurrent.Worker<T>`
  remains fine for long-running task state. Applies to `executeScript`/JS
  results, DOM-ready, frame/load notifications, etc.
- **No inline fully-qualified type names** in declarations or calls — always
  `import`; never `com.foo.Bar.baz()` inline. Forbidden even in diagnostic /
  scratch code.
- **No engine/transport classes in public signatures.** `javafx.scene.web.*`
  and the `org.w3c.dom` surface never expose `com.sun.webkit.blink.*` or any
  Chromium/engine type — not even in package-private signatures. Facades take
  `WebEngine`/`WebView`, not bridge handles.
- **No AWT/Swing/ImageIO** outside the `javafx.swing` module. Use
  `SkiaImageAccess` for image encode/decode needs.
- **FFM over JNI** for new native interop (`java.lang.foreign`); confined
  per-frame arenas, no per-frame heap churn on the render thread.

**Native-first (for speed).** Push as much work as possible into the engine
C++ side; keep Java a thin glue layer. The hot paths — frame production &
texture sharing, input-event translation, DOM tree diffing/caching, command
batching, JSON/result marshalling — live in native code and cross the FFM
boundary in coarse, batched calls, not chatty per-item ones. Java does:
lifecycle, FX-thread callback dispatch, public-API shape, and `org.w3c.dom`
object identity. Anything latency- or throughput-sensitive belongs in C++.

## JavaScript dialogs (alert / confirm / prompt / beforeunload)

Page JS dialogs must surface to JavaFX and **block the page until the user
accepts/cancels** — and the same applies when the app drives them. The engine
has no dialog handling today (no `JavaScriptDialogManager` ⇒ Chromium
auto-cancels), so we add it by **overriding** Chromium's hook:

- **Engine:** a `JuxJsDialogManager` returned from
  `WebContentsDelegate::GetJavaScriptDialogManager()`, implementing
  `RunJavaScriptDialog` + `RunBeforeUnloadDialog`. It stashes Chromium's
  `DialogClosedCallback` under a `dialogId` and fires `DIALOG_REQUESTED`
  (`[windowId][dialogId][type][message][defaultText]`) **without invoking the
  callback** — so Chromium keeps the renderer's JS suspended. That is the
  "everything waits" semantics, enforced by Chromium itself.
- **Resume:** new command `DIALOG_RESPONSE`
  (`[windowId][dialogId][accepted:1][text]`); the dispatcher invokes the
  stashed callback `(accepted, text)`, resuming the renderer.
- **Java:** `BlinkPage` → `WebPage` → existing `UIClient.alert/confirm/prompt`,
  surfaced unchanged by `WebEngine.onAlert` / `setConfirmHandler` /
  `setPromptHandler`. Public API is untouched.
- **New IDs** added to `jux_event_types.h` / `jux_command_types.h` + the Java
  mirrors (`NativeEventType` / `CommandType`). Requires an engine rebuild.

Part of P1's control surface (render-independent), implemented after the core
bridge is proven (load/title/JS round-trip).

## Build

- Convention plugin `skiafx.blink-native-conventions.gradle` (ported from
  jux `chromium-tools.gradle`).
- Engine C++ at `javafx.web/src/main/native-blink/{engine,stubs}` (copied from
  jux, provenance-commented). Internal GN names stay `jux_*`/`//jux` (our
  checkout is isolated); only outputs are `skia-fx-webview.*`.
- Workspace: isolated, gitignored `<repoRoot>/.chromium` (override
  `-PchromiumHome=`), seeded once by `seedChromium` (full copy, no re-download).

### Tasks (group `chromium`)

| Task | What |
|---|---|
| `seedChromium` | Full isolated copy of an existing checkout (no re-download) |
| `setupEnv` | Fresh-machine download at the pinned version |
| `pullVersions` | List ChromiumDash releases |
| `configureBuild` | Copy engine/stubs, patch src, write `args.gn`, `gn gen` |
| `buildNatives` | `ninja … jux_all` → `skia-fx-webview.{dll,exe,pak}` |
| `copyNativeEngine` + `generateChecksums` | Stage the full bundle + SHA-256 manifest for the jar |

`buildWebNative=true` (in `gradle.properties`) wires the chain into `assemble`
and the root `buildAll`: `configureBuild → buildNatives → copyNativeEngine →
generateChecksums → jar`. The jar bundles **all** native libs + required files
at its root: `skia-fx-webview.{dll,exe,pak}`, `icudtl.dat`,
`v8_context_snapshot.bin`, `snapshot_blob.bin`, `libEGL.dll`, `libGLESv2.dll`,
`d3dcompiler_47.dll`, `vk_swiftshader.dll`, `vulkan-1.dll`,
`checksums.properties`. (Runtime extraction is the P1 loader step.)

### Configurability (everything overridable)

- `chromium.version` (set specifically; `-Pchromium.version=`), `channel`,
  `buildType`, `buildDir`, `syncJobs`, `buildJobs`.
- Codecs: `chromium.codecs.{proprietary,hevc,dolbyVision}`.
- Features: `chromium.feature.{pdf,printing,printPreview}`.
- **Any GN flag**: `-Pchromium.gn.<flag>=<value>` (wins over defaults) + raw
  `-Pchromium.extraGnArgs=` escape hatch.

### Dropped for size

- **PDF (PDFium viewer)** — `enable_pdf=false`.
- **Printing + print preview** — `enable_printing=false`,
  `enable_print_preview=false`. On Windows the printing stack *requires* PDF,
  so the two go together. The engine's print command handlers
  (`jux_command_dispatch.cc`) are guarded by `SFXWEB_ENABLE_PRINTING` (never
  defined) and the `//printing` dep is removed; they compile to no-ops.
  Re-enable: `-Pchromium.feature.pdf=true -Pchromium.feature.printing=true`
  **and** define `SFXWEB_ENABLE_PRINTING` + restore `//printing` in the engine
  `BUILD.gn`.
- Inherited jux trims: WebRTC, NaCl, spellcheck, extensions, Safe Browsing,
  TFLite, Widevine, AV1, WebUSB/HID/Bluetooth/XR, GL-desktop/Metal ANGLE
  backends, DevTools frontend resources, etc.
- **WASM**: left ON for now ("make it work first"); revisit
  (`v8_enable_webassembly` is build-risky — Blink references WASM widely).

---

## Phases

- **P0 — build plumbing.** ✅ Plugin, engine/stubs copied + renamed outputs,
  configurable args.gn (PDF/printing dropped), seed + configure + compile chain.
- **P1 — process + control.** FFM ring buffer in `com.sun.webkit.blink`;
  `NativeLibLoader` extracts the engine bundle; `WebPage` spawns the engine and
  marshals load/JS/title/load events.
- **P2 — software OSR → scene.** `FrameSink` + shared-mem BGRA path drawn into
  the scene canvas; input events wired.
- **P3 — accelerated OSR (zero-copy).** Cross-process shared D3D11 texture →
  reuse `openjfx_skia_d3d11_interop` → `SkImage`; triple-buffer + keyed mutex.
- **P4 — full `org.w3c.dom`.** `DomBridge` + local DOM cache; `getDocument()`.
- **P5 — cleanup + docs.** Remove WebKit native tree; finalize this doc.

---

## Open risks

- **Blink OSR on the raw content layer** — jux only does child-window
  compositing; CEF's windowless model is the reference but lives in CEF, not
  vanilla `content/`. Validate how to get off-screen `CompositorFrame`s +
  shareable texture from `content::WebContents`/`RenderWidgetHostView` in m147.
  Software path de-risks correctness first.
- **Cross-process device sharing** — Chromium's GPU process has its own D3D11
  device; confirm `CreateSharedHandle`/`OpenSharedResource1` + keyed mutex
  interop with skia-fx's interop device. Software fallback covers gaps.
- **Synchronous DOM latency** — mitigated by the local DOM cache, not the
  transport.
- **`print()`** — printing dropped; JavaFX `WebEngine.print()` path TBD
  (Chromium print-to-bitmap, or document as unsupported in v1).
