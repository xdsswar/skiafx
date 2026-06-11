# WebView cursor sync (Blink OSR)

How the off-screen Blink WebView reacts to hover cursors (links → hand, text →
I-beam, resize handles, `cursor:` CSS, …) exactly like Chrome.

## Why this is needed

The Blink WebView renders Chromium **off-screen** (windowless) and composites the
captured frame into a JavaFX node; input is injected into a hidden Chromium widget.
Chromium computes the correct cursor for the hovered element but, having no OS window
under the real cursor, never applies it — so without this feature the JavaFX cursor
never changes on hover.

## Root cause (why the obvious hook doesn't work)

In `content/browser/renderer_host/render_widget_host_view_aura.cc`:

```
renderer → RenderWidgetHostImpl::SetCursor → RWHVAura::UpdateCursor
        → GetCursorManager()->UpdateCursor(this, cursor)   // stores current_cursor_
        → DisplayCursor → UpdateCursorIfOverSelf()
```

`UpdateCursorIfOverSelf()` is the **only** path that calls
`cursor_client->SetCursor(...)`, and it early-returns at:

```cpp
if (root_window->GetEventHandlerForPoint(screen->GetCursorScreenPoint()) != window_)
    return;
```

The real OS cursor is over the **JavaFX window**, never the hidden Chromium window, so
this always bails. An aura `CursorClient` hook (or a wrapper around it) would therefore
**never fire** for OSR. The cursor must be read *upstream* of this gate.

## The hook (no Chromium core patch)

`RWHVAura` exposes the renderer's current cursor **ungated** through its
`aura::WindowDelegate::GetCursor(const gfx::Point&)` override (returns `current_cursor_`).
We poll it from the existing 16 ms capture loop (`JuxCaptureTick` in
`jux_engine_api.cc`), which already runs per-WebContents on the UI thread:

```cpp
aura::Window* win = view->GetNativeView();           // aura::Window* on Win/Linux
ui::Cursor c = win->delegate()->GetCursor(gfx::Point());
int jfx = JuxCursorTypeToJfx(c.type());              // → CursorManager constant
if (jfx != entry.last_cursor_type) { emit kCursorChanged([cursorType:4]); }
```

Emits only on change; latency ≤16 ms + immediate event-ring delivery.

## End-to-end path

```
RWHVAura.current_cursor_ (ungated GetCursor)
  → JuxCaptureTick poll → kCursorChanged event  [windowId:4][cursorType:4]
  → EventPump → BlinkPage.onEvent → Client.onCursorChanged(type)   (FX thread)
  → WebPage.BlinkClientImpl.onCursorChanged
  → CursorManager.getPredefinedCursorID(type) → id
  → WebPageClient.setCursor(id) → CursorManagerImpl.getCursor(id) → node.setCursor(Cursor)
```

The JavaFX-side stack (`CursorManager`, `CursorManagerImpl`, `WebPageClientImpl`,
the `com.sun.javafx.webkit.Cursors` image bundle) is **stock OpenJFX, reused as-is** —
it already maps all 43 types to `javafx.scene.Cursor`, including bundled-image cursors
(`help`, `zoom.*`, `grab`, `panning.*`, diagonal resizes).

## Type mapping

`JuxCursorTypeToJfx(ui::mojom::CursorType)` in `jux_engine_api.cc` translates Blink's
enum to the `com.sun.webkit.CursorManager.*` integer **by name** — the two enums are
ordered differently (e.g. Blink `kIBeam=3` vs `CursorManager.MOVE=3`), so name-based
mapping avoids fragile numeric coupling. `kCustom` (CSS `cursor:url(...)`) currently
falls back to `POINTER` (see Part B).

## Cross-platform

- aura path = **Windows + Linux** with one implementation. The read is guarded by
  `#if !BUILDFLAG(IS_MAC)`.
- **macOS** uses `RenderWidgetHostViewMac` (a different cursor path) — TODO; falls
  through to no cursor update there (default cursor, no regression).
- All JavaFX-side code is platform-neutral.

## Files

- `engine/jux_event_types.h` — `kCursorChanged = 0x0108`.
- `engine/jux_engine_api.cc` — `WebContentsEntry.last_cursor_type`,
  `JuxCursorTypeToJfx`, the poll + emit in `JuxCaptureTick`.
- `blink/NativeEventType.java` — `CURSOR_CHANGED = 0x0108`.
- `blink/BlinkPage.java` — `Client.onCursorChanged`, event decode.
- `webkit/WebPage.java` — `BlinkClientImpl.onCursorChanged`.
- `webkit/CursorManager.java` — `getPredefinedCursorID` made public (additive).

## Status

- **Part A — keyword/UA cursors: DONE.** Every standard UA + CSS keyword cursor,
  including bundled-image ones. No shared-memory layout change.
- **Part B — CSS `cursor: url(...)` custom bitmaps: TODO.** Needs a small
  `CURSOR_STAGING` region in the channel (the 248-byte event slot can't carry a
  bitmap): engine copies `ui::Cursor::custom_bitmap()` (BGRA premultiplied) + hotspot
  there and emits `kCursorChanged[type=CUSTOM][w][h][hsX][hsY]`; Java wraps it via
  `WCGraphicsManager.createFrame(w,h,ByteBuffer)` → `getCustomCursorID(frame,hsX,hsY)`
  → `setCursor(id)`. Until then custom-image cursors fall back to the keyword cursor
  Chromium also reports.
- **macOS read: TODO** (RWHVMac cursor path).

## Build / verify

1. Rebuild engine: `./gradlew :javafx.web:configureBuild :javafx.web:buildNatives -PbuildWebNative=true`
   (Part A touches `jux_engine_api.cc` + `jux_event_types.h`). Java is jar-only.
2. `runWebView` a page with: a link (→ hand), an `<input>`/text (→ I-beam), and
   `style="cursor:wait|move|grab|not-allowed|col-resize|zoom-in"` regions.
3. Hover each; the JavaFX window cursor should match Chrome. Stationary pointer over
   one element ⇒ no cursor traffic (emit-on-change). Leaving the WebView restores the
   app cursor.
