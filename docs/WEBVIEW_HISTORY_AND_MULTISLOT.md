# WebView session history + multi-slot event transport

Status: **implemented, unverified pending engine rebuild** (2026-06-02, branch
`web-blink`). The Java side compiles standalone; the native side needs the
Chromium/jux engine rebuilt to take effect and has not been compiled yet.

This covers three related changes to the Blink-backed `WebView`:

1. A reusable **multi-slot event transport** (payloads bigger than one ring slot).
2. **Session history** (back/forward) wired end-to-end through it.
3. A **LoadWorker `SUCCEEDED` single-fire fix** + a Worker test panel in the demo.

---

## 1. Multi-slot event transport (`WriteEventLarge`)

### Problem
The engine→Java event ring delivers **fixed 256-byte slots** (`MAX_PAYLOAD =
248`, of which the first 4 bytes are `windowId`). `EventWriter::WriteEvent`
writes exactly one slot and **clamps** anything larger, so events bigger than
~244 user bytes were truncated. The previous workaround everywhere (select-popup
list, network body chunks) was to spill to a **temp file** and send the path.

### Design
The event ring is **SPSC — a single producer** (all event writes happen on the
browser thread; `WriteEvent` has no lock/CAS, only `load`/`store` of
`write_pos`). Because there is exactly one writer, consecutive slots written for
one logical message are **guaranteed contiguous** — nothing interleaves. That
makes length-prefixed multi-slot framing safe (it would *not* be on a
multi-writer ring without per-message ids).

Framing (mirrored constants: `MemoryLayout.EVT_*` ↔ `jux_ring_buffer.h kEvt*`):

- **Header slot**: `type = originalType | EVT_CONT_FLAG (0x80000000)`,
  payload `[windowId:4][totalUserLen:4][chunk0]` (chunk0 ≤ 240 bytes).
- **Continuation slots**: `type = EVT_CONTINUATION (0x0000FFFE)`,
  payload `[windowId:4][chunkK]` (chunkK ≤ 244 bytes).
- The producer writes all N slots, then publishes with a **single
  `write_pos += N` release store**, so the consumer sees them atomically (the
  acquire load of `write_pos` guarantees all N slot bodies are visible).

### Code
- Native: `EventWriter::WriteEventLarge` (`jux_ring_buffer.{h,cc}`). Small
  payloads fall through to the normal single-slot `WriteEvent`. Returns `false`
  if the ring lacks room for the whole message (never written partially).
- Java: `EventRingBuffer.poll` detects `EVT_CONT_FLAG` and calls
  `assembleLarge`, which copies the chunks into a heap `byte[]` and backs the
  reusable `EventSlot` with `MemorySegment.ofArray(...)` (transparent to all
  `EventSlot` readers). `advance()` now advances `read_pos` by `lastSlots`.
  All untrusted lengths are clamped so a corrupt header can't over-read.

### Reuse
Any event can now be sent via `WriteEventLarge` with no per-event reassembly
code. Future cleanup: migrate the select-popup item list and network body chunks
off temp files onto this path.

---

## 2. Session history (back/forward)

Previously `BackForwardList` was fully stubbed on the Blink path (`size()=0`,
`getCurrentIndex()=-1`, `setCurrentIndex()` a no-op — the old `bfl*` natives died
with jfxwebkit), so `WebHistory.go(±1)` and the demo's back/forward buttons did
nothing. Chromium already keeps full session history per `WebContents`; this just
wires it.

### Flow
- **Navigate**: `WebHistory.go(offset)` → `BackForwardList.setCurrentIndex(idx)`
  → `WebPage.navigateToHistoryOffset(idx - current)` →
  `BlinkPage.goToHistoryOffset` → `GO_TO_OFFSET` cmd (`0x00F0`) →
  `CommandDispatcher::OnGoToOffset` → `JuxGoToOffset` →
  `NavigationController::GoToOffset` (guarded by `CanGoToOffset`).
- **Report**: engine `DidFinishNavigation` serializes the whole entry list and
  calls `on_history_changed` → `OnHistoryChanged` emits `HISTORY_STATE`
  (`0x0207`) via `WriteEventLarge` → `BlinkPage` parses inline →
  `Client.onHistoryChanged(currentIndex, urls[], titles[])` →
  `WebPage.BlinkClientImpl` → `BackForwardList.updateFromEngine(...)` →
  `notifyChanged()` → `WebHistory`'s existing change-listener updates its
  observable entry list and `currentIndex`.

`HISTORY_STATE` payload (after `windowId`):
`[currentIndex:4(int32)][count:4]{[urlLen:4][url][titleLen:4][title]}…` — sent
**inline** through the multi-slot transport (no temp file), so arbitrarily long
URLs are fine.

### Reconciliation
`BackForwardList.updateFromEngine` reconciles the full snapshot **in place**,
preserving `Entry` identity for positions whose URL is unchanged. This matters
because `WebHistory`'s incremental change-listener recognizes entries via
`Entry.isPeer` (identity). New `Entry`s for the engine path use a package-private
`Entry(String url, String title)` ctor (`pitem == 0`, so the public getters
return cached fields, never calling the dead `bfl*` natives).

---

## 3. LoadWorker `SUCCEEDED` fires exactly once

**Symptom:** the `Worker.State` went `RUNNING → SUCCEEDED → RUNNING → SUCCEEDED`
(multiple fires) per load.

**Causes & fixes (both Java-only, no rebuild):**
1. `BlinkPage` mapped **both** `DOC_READY` (main-frame `DidFinishLoad`) **and**
   `DOC_CONTENT_LOADED` (`DOMContentLoaded`, earlier) to `onPageFinished`. Now
   only `DOC_READY` drives `SUCCEEDED`; `DOC_CONTENT_LOADED` is a no-op for the
   worker.
2. `WebEngine.LoadWorker`'s `DOCUMENT_AVAILABLE` handler synthesized a
   `PAGE_STARTED` whenever state `!= RUNNING`, so a post-`SUCCEEDED`
   `DOM_TREE_READY` flipped it back to `RUNNING`. Guard tightened to
   `== State.READY` (the genuine empty-load case, JDK-8119247).

Net: `SUCCEEDED` fires once, when the **main frame** finishes.

### Demo test
`samples/ensemble/.../WebViewDemo.java` gained a **"Worker" toggle** opening a
docked **LoadWorker monitor**: it logs every state transition (timestamp +
message + progress + exception) and has three one-click tests driving
`SUCCEEDED` (local page), `FAILED` (`*.invalid` host), and `CANCELLED` (cancel on
first `RUNNING`), with a validator that flags a terminal reached without a
preceding `RUNNING`.

---

## Files touched

Java: `MemoryLayout`, `EventRingBuffer`, `NativeEventType`, `CommandType`,
`BlinkPage`, `BlinkSmokeMain` (`com.sun.webkit.blink`); `WebPage`,
`BackForwardList` (`com.sun.webkit`); `WebEngine` (`javafx.scene.web`);
`WebViewDemo` (samples).

Native (`javafx.web/src/main/native-blink/engine`): `jux_ring_buffer.{h,cc}`,
`jux_event_types.h`, `jux_command_types.h`, `jux_engine_api.{h,cc}`,
`jux_command_dispatch.{h,cc}`, `jux_web_contents_delegate.cc`.

## Remaining verification
- Rebuild the jux engine and run `WebViewDemo`: confirm back/forward navigate and
  enable/disable correctly, `getEntries()` shows full URLs/titles, and the Worker
  monitor shows a single `SUCCEEDED` per load.
- Exercise a **long-URL** history (e.g. big Google search query strings) to
  confirm the multi-slot reassembly path.
