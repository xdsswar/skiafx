# WebView — DOM, dialogs/choosers/permissions, network interception

Status tracker for the web-blink effort that makes `javafx.web` behave like a
real browser to the embedding app: full `org.w3c.dom`, every browser
dialog/chooser/permission scenario, and network MITM. Plan:
`~/.claude/plans/calm-inventing-tulip.md`. Keep this current (it's authoritative;
plan files are ephemeral).

## Routing pattern (every feature)

engine event → `BlinkPage.Client` (decode on pump thread, marshal to FX) →
`WebPage.BlinkClientImpl` → `UIClient`/`UIClientImpl` (default methods) →
`Accessor` (abstract) → `WebEngine.AccessorImpl` (builds the public token via
package-private ctors, invokes the app handler). Response: token →
`WebEngine` pkg-private responder → `WebPage` public → `BlinkPage.respond*`/
`resolve*` → command ring. Engine mirrors the JS-dialog stash/resume template
(`jux_js_dialog_manager`): stash Chromium continuation by id, fire event WITHOUT
running it, resume on the response command.

**Hard rules:** no engine types AND no raw ids/handles/long-pointers in
`javafx.scene.web` signatures (tokens keep ids private); no `CompletableFuture`/
`Consumer` (named FX-thread callbacks); javadoc every public member incl enum
constants; no inline FQNs; Cleaner lifecycle + bounded buffers + release-pending-
on-teardown.

## Done

- **Part A — W3C DOM: DONE, works.** Fresh `org.w3c.dom` impls in
  **`com.sun.webkit.blink.dom`** (NOT `com.sun.webkit.dom` — the ~109 legacy
  WebKit-generated classes are still on the source path and collide). `DomBridge`
  + cache in `com.sun.webkit.blink`. `getDocument()` → real `HTMLDocument`; fires
  `DOCUMENT_AVAILABLE` on `kDomTreeReady`. DOM event decoders clamp strings to the
  slot (large text truncates, no crash).
- **Part B — public API DONE (all 4 sub-phases, compiling).** Engine:
  - **B1 JS dialogs — DONE + BUILDS** (fix: `base/functional/callback.h` for the
    `OnceCallback` map value).
  - **B4 fullscreen + favicon — WIRED** (delegate overrides + `JuxRespondFullscreen`).
  - **B3 permissions — WIRED** (`jux_permission_manager` = `PermissionControllerDelegate`,
    via `JuxBrowserContext::GetPermissionControllerDelegate`).
  - **B3 auth — WIRED** (`jux_login_delegate` = `content::LoginDelegate` stash/resume,
    `CreateLoginDelegate` + `JuxRespondAuth`; dtor cancels unanswered → no hung load).
  - **B3 downloads — WIRED** (`jux_download_manager_delegate` =
    `DownloadManagerDelegate` + `DownloadItem::Observer`; `DetermineDownloadTarget`
    stash/resume via `JuxRespondDownload`, progress/finished via the observer,
    `GetNextId` counter, `JuxCancelDownload`; dtor + `OnDownloadDestroyed` release
    pending as cancel. Lazy-created via `GetDownloadManagerDelegate`).
- **User-Agent — WIRED.** Global default via `JuxBrowserClient::GetUserAgent()` +
  `GetUserAgentMetadata()` (frozen reduced Chrome UA, fixes empty-UA site failures
  e.g. Spotify). Per-WebView override: `WebEngine.userAgent` → `WebPage.setUserAgent`
  → `BlinkPage` → `SET_USER_AGENT` (0x003C) → `WebContents::SetUserAgentOverride`;
  `JuxLoadURL` sets `override_user_agent` when an override is present. Empty clears.
  `BlinkPage.DEFAULT_USER_AGENT` mirrors the engine UA byte-for-byte (frozen per-OS
  platform token).
- **Select + color (renderer shims) — WIRED.** Both via the `JuxDocListener`
  capture-phase `mousedown` listener (desktop `OpenColorChooser` is
  Android/iOS-only AND the `ColorChooserFactory.OpenColorChooser` mojom is
  `[EnableIf=is_android|is_ios]` → factory path impossible on desktop, so color
  uses the same shim as select). **Select**: detect `HTMLSelectElement`,
  `preventDefault`, gather options via `GetListItems()` (skip `<optgroup>`, track
  group label), `OnSelectPopup` → browser writes items to a temp file (Java's
  `readSelectItemsFile` format) + `kSelectPopupOpen` (0x0441); response
  `kSelectPopupResponse` → `JuxSelectPopupResponse` → `SelectPopupResponse`
  (maps options-only indices → list indices → `SelectOptionByPopup` /
  `SelectMultipleOptions`). **Color**: detect `<input type=color>` (via type
  attr), `OnColorChooser` (value parsed `#rrggbb`→0xRRGGBBAA) → `kColorChooserOpen`
  (0x0440); response `kColorChooserResponse` → `JuxColorChooserResponse` →
  `ColorChooserResponse` (`SetValue` with `kDispatchInputAndChangeEvent`). Both
  add a browser→renderer round-trip on `JuxDomHandler`. Likely iteration points:
  `HTMLSelectElement::GetListItems()` element types, `SetValue` overload,
  `EqualIgnoringASCIICase` include.
- **Context-menu + tooltip (renderer shims) — WIRED.** New `JuxDomClient` mojom
  methods `OnContextMenu`/`OnTooltipChanged`; a `JuxDocListener`
  (`blink::NativeEventListener`) installed on the document for `contextmenu` +
  `mouseover` (installed by `EnsureDocListeners` from `SetClient` +
  `DidCreateNewDocument`, torn down in `ResetPerDocumentState`). Renderer extracts
  click `clientX/Y`, nearest `<a>/<area>` href + `<img>/<video>/<audio>` src
  (resolved via `Document::CompleteURL`), `FrameSelection::SelectedText`, and
  `blink::IsEditable` (flags bit0); tooltip walks ancestors for the nearest
  `title` (debounced via `last_tooltip_`). Browser writes `kContextMenuRequested`
  (0x0480) / `kTooltipChanged` (0x0483). Likely build-iteration points:
  `MouseEvent::clientX/Y` return type, `blink::IsEditable` include/signature,
  `FrameSelection` access.
- **Part C — public API + inbound/outbound routing + C++ header mirror DONE
  (compiling). Engine C1 (request phase: observe + block + proceed) WIRED**
  (`jux_network_interceptor` + `jux_url_loader_throttle` + `CreateURLLoaderThrottles`).
  Multi-interceptor via the `Network` facade: `webEngine.getNetwork().add(filter,
  interceptor)` returns a `javafx.util.Subscription` (`unsubscribe()` removes that
  registration); also `interceptors()` and `clear()`. First-match-by-filter owns the
  decision. (The old loose `WebEngine.addNetworkInterceptor/remove/get` methods were
  replaced by this facade — `Network` is the only public surface; `NetworkImpl` stays
  package-private.)
- **Part C — full-MITM `URLLoaderFactory` proxy CORE WIRED.**
  `jux_proxying_url_loader_factory.{h,cc}` (`URLLoaderFactory` + per-request
  `URLLoader`/`URLLoaderClient` interposer) inserted via
  `JuxBrowserClient::WillCreateURLLoaderFactory` + `URLLoaderFactoryBuilder::Append`
  (armed-gated → zero overhead when no interceptor). Decisions route through a NEW
  `JuxNetworkInterceptor` proxy path (`RegisterProxy` + `Resolve` branch — the
  throttle path is untouched). Supported: **request phase** proceed / proceed-
  modified (header+method edits) / block / redirect (transparent) / synthetic
  response; **response phase** status-line + header edits (`FireResponse` →
  `kResponseReceived`). Tail decoders mirror `NetworkExchange.encodeEdits/
  encodeSynthetic`; `PHASE_REQUEST=0`/`PHASE_RESPONSE=1` confirmed.
  **Response-body REPLACEMENT — WIRED** (`A_REPLACE_BODY`=6): `ReplaceHeldBody`
  swaps the held response body for the app-provided bytes (new data pipe +
  Content-Length fixup) before forwarding — covers "intercept + override the
  content".
  **Response-body CAPTURE + edit — WIRED** (`captureBody()` → `onBodyChunk` →
  `BodyEdit`). On a capture-flagged response-proceed (`action | 0x80`),
  `StartBodyCapture` interposes a fresh body pipe (renderer reads it), drains the
  real body via `mojo::DataPipeDrainer` into `captured_body_`, then
  `FireBodyChunk` surfaces the **whole body as one `last` chunk** through a temp
  file + `kResponseBodyChunk` (0x0702). Java: `BlinkPage.decodeBodyChunk` →
  `onNetworkBodyChunk` → `WebPage` → `UIClient.networkBodyChunk` →
  `Accessor.fireNetworkBodyChunk` → `WebEngine` (`matchNetworkInterceptor` →
  `onBodyChunk(ex, BodyChunk)` → `BodyEdit`) → `resolveBodyEdit` →
  `kInterceptBodyEdit` (0x00E5, replacement in a temp file). Engine
  `OnInterceptBodyEdit` → `ResolveBodyEdit` → `ApplyBodyEdit` writes
  pass/replace/drop bytes via `mojo::DataPipeProducer`+`StringDataSource`; the
  renderer's `OnComplete` is deferred until that write finishes. Note: it's
  **whole-body** (one chunk), not incremental streaming — simpler + avoids a
  per-chunk hold/backpressure state machine; large bodies buffer in memory
  (app opts in via `captureBody()`). Likely build-iteration points: `base::span`/
  `StringDataSource` ctors, the factory self-deletion re-entrancy on the loader
  stack.

## Native-by-default popups + window-origin positioning (2026-06-01, supersedes below)

Final direction: **use the engine's own native UIs, positioned correctly**, and let the app
override (the context menu is even native-rendered from app-supplied items).

- **Window-origin sync** (`WebViewScreenSync` → `SET_SCREEN_ORIGIN` 0x003D → `JuxSetScreenOrigin`):
  moves the hidden engine window's origin to the JavaFX node's on-screen position (keeps size,
  never shows it), so Blink's native page-popups land over the control instead of at (0,0).
  DPI mapping (JavaFX screen coords → views DIP) is the calibration point — `--v=1` logs
  `[skia.webview.popup] origin -> …`.
- **`<select>` + `<input type=color>` → Blink's own page-popups by default.** Renderer
  `HandleSelectMouseDown` only intercepts (preventDefault + surface to Java) when the app set a
  handler; gated by `SET_POPUP_OVERRIDES` (0x003E → mojom `SetPopupOverrides`). `WebEngine`
  pushes the override bits when handlers change.
- **Context menu → ONE reused JavaFX `ContextMenu` the WebView owns; app customizes its items.**
  The engine never shows a native menu (`HandleContextMenu`→true). Flow: right-click →
  `WebView` capturing filter consumes the contextless OS `ContextMenuEvent` and defers
  (`WebEngine.armContextMenu`, with a ~250 ms fallback for pages that suppress `contextmenu`)
  → renderer `OnContextMenu` fires `kContextMenuRequested` (0x0480, selection/link/image/
  editable) → `WebEngine` stores it as `ContextMenuContext`, populates the single reused menu
  with the contextual defaults (Copy / Open Link / Copy Address / Back / Reload), runs the app's
  `setContextMenuCustomizer(Consumer<ContextMenu>)` (add/remove `MenuItem`s; reads
  `getContextMenuContext()` → `getSelectedText()`/`getLinkUrl()`/`getImageUrl()`/`isEditable()`),
  then shows it. There is exactly ONE menu per WebView — a single reused `ContextMenu`
  (popup window created once → no per-right-click video hitch), shown/hidden by the WebView
  (`autoHide` + explicit hide on page click/scroll). A delivery guard (`ctxDeliveredNanos`)
  prevents a slow engine signal from double-showing after the fallback fired. The earlier
  `ContextMenuModel`/`ContextMenuHandler`/`ContextMenuRequest` + `CONTEXT_MENU_SHOW`/
  `CONTEXT_MENU_COMMAND` round-trip and `jux_context_menu.{h,cc}` were removed. **No ids/handles in any `javafx.scene.web`
  signature** — `menuId` is internal only.
- Framework ships **no default JavaFX popup UI** anymore — `DefaultPopupUI`/`AutoDismiss`
  removed; the only JavaFX path is an app `<select>`/`color` handler drawing its own control.

**Open risks (verify on engine rebuild):** DPI calibration; `views::MenuRunner::RunMenuAt`
signature + `MenuAnchorPosition`/`ui::mojom::MenuSourceType`/`ui::NORMAL_SEPARATOR` enums on the
147 tree; `MenuRunner` parented to a hidden widget; `base/bit_cast.h` path; BUILD.gn deps for
`//ui/views`+`//ui/base/models`. Color page-popup availability (fall back to a JavaFX dialog if
absent).

---

## Default popup UI + native suppression + scroll-dismiss (2026-06-01, superseded by the section above)

The off-screen WebView now ships **built-in default JavaFX UI** so `<select>`,
`<input type=color>`, and right-click work with no app handler (app handlers
still override). Plus deterministic native suppression of the engine's own
(cloaked-window) popups, and scroll-dismiss.

- **Java defaults** — new internal `com.sun.javafx.webkit.DefaultPopupUI`
  (`showSelect`/`showColorChooser`/`showContextMenu`) + `AutoDismiss` helper.
  `WebEngine.AccessorImpl` now invokes these when no handler is set (instead of
  `cancel()`/no-op). Select = `ContextMenu` of `RadioMenuItem` (single) /
  `CheckMenuItem` + Done (multi); colour = `CustomColorDialog` (the standard JFX
  dialog, Save→choose, Cancel/close→cancel); context menu = Copy / Open Link /
  Copy Link / Copy Image / Back / Reload, gated on `WebView.isContextMenuEnabled()`.
  `AutoDismiss` removes all listeners on close (scroll filter, window x/y/w/h,
  scene, load-worker state) — idempotent, leak-free; **focus-loss is not a
  trigger** (a popup grabbing focus would self-dismiss).
- **Native context-menu suppression** — `JuxWebContentsDelegate::HandleContextMenu`
  returns `true` (no native Views menu). `OnContextMenu` still fires from the
  renderer `contextmenu` listener, so Java still gets the request.
- **Native select/color suppression** — renderer `JuxDomHandlerImpl` now
  `preventDefault`s the `<select>`/colour interaction on **mousedown + mouseup +
  click** (capture), since Blink's open-trigger varies by platform (the likely
  "sometimes double-popup" cause); the request is surfaced to Java once (mousedown).
  Desktop has no native colour-chooser path (factory is Android/iOS-only), so the
  renderer shim is the whole story for colour.
- **Scroll-dismiss** — new event `kPopupDismissRequested` (0x0484, no payload) +
  mojom `JuxDomClient.OnPopupDismiss`. Renderer adds a capture-phase document
  `scroll` listener (`JuxDocListener::kScroll` → `HandleScrollEvent`), gated on a
  `popup_open_` flag so it fires once per open popup. Java route: `BlinkPage.Client.
  onPopupDismiss` → `WebPage` → `UIClient.dismissPopups` → `DefaultPopupUI.dismissActive`.
  The Java `AutoDismiss` scroll filter already covers wheel-over-WebView; the engine
  signal adds keyboard/scrollbar/programmatic scrolls. **Needs engine rebuild.**
- **Demo** — `WebViewDemo` dropped its hand-rolled select/colour/context handlers
  (now framework defaults) and loads `webview-features.html` from the classpath via
  `engine.load(url)` instead of inline `loadContent`.

Open native risk to verify after rebuild: if a native `<select>` popup still
appears, the next lever is a browser-side `RenderViewHostDelegateView::ShowPopupMenu`
interception (no clean `WebContentsDelegate` hook exists for select popups).

## Deferred follow-ups (note for later)

1. **`URLLoaderFactory` proxy (full MITM) — DONE (see above).** Request
   modify/block/redirect/synthetic + response status/header edit + body
   replacement + whole-body capture/edit all wired.
2. **Body capture transport — DONE via temp file** (not a shared-mem region):
   `kResponseBodyChunk` (0x0702) writes the body to a temp file, Java reads+deletes
   it; the `BodyEdit` replacement returns the same way (`kInterceptBodyEdit`).
   A shared-mem region would only matter for very-high-throughput incremental
   streaming (not the whole-body model used today).
3. **Network request-event truncation:** `kRequestWillBeSent` builds the URL+headers
   inline; a long URL/large header set exceeds the 248 B slot and truncates (Java
   clamps, no crash). Needs the overflow region for full fidelity.
4. **B2 engine — color + `<select>`: DONE (see above).** Color datalist
   suggestions are sent empty today (minor enhancement: read `<datalist>` options).
5. **B4 engine — context-menu + tooltip: DONE (see above).**
6. **B3 engine — downloads.** (Auth: DONE, see above.) Downloads =
   `DownloadManagerDelegate` via the existing
   `JuxBrowserContext::GetDownloadManagerDelegate` hook (same pattern permissions
   used). Java API done.
7. **DOM follow-ups:** A5 `JSObject`/`executeScript` node-tag interop
   (`{"__jux_node__":id}`), capture/bubble multi-phase dispatch (at-target only
   today), innerHTML/textContent > 248 B chunking.
8. **getUserMedia/screen-capture** may be inert until WebRTC is re-enabled in the
   Chromium build (`enable_webrtc` trimmed per BLINK_INTEGRATION.md).

## Build gotchas

- Chromium interfaces used as `std::map` values need the full `base/functional/callback.h`
  (callback_forward.h is only a fwd decl).
- IDE clang shows spurious "header not found" / "uint32_t unknown" on all engine
  files (no GN include paths) — ignore; the real toolchain has them.
- Java is canonical for command/event IDs; mirror byte-for-byte into
  `jux_command_types.h` / `jux_event_types.h`.
