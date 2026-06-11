# WebView Print Preview (chrome://print in OSR)

Goal: render Chrome's **exact** `chrome://print` preview UI **off-screen** in the
jux engine and composite it over the page with the Skia engine, forwarding input
— not the native OS print dialog. Desktop only. Everything is gated behind
`enable_print_preview` (off by default; the default engine build is byte-identical).

## Build flags / reproducibility

- Built only when `enable_print_preview = true` (set in `gradle.properties` for
  this work). Brings in `enable_printing` + `enable_pdf`.
- Ported Chromium `//chrome/...` sources are built **in place**; our edits to them
  are reproducible **patches** under `javafx.web/src/main/native-blink/patches/`
  (`0001` = print_preview_ui base swap + OSR/cone edits; `0002` = handler/printer
  handlers). Added/restored files live in `native-blink/stubs/`; new glue lives in
  `native-blink/print_preview/` and the engine. See `project_chromium_patch_infra`.

## Pipeline (what happens on window.print() / Ctrl+P)

```
window.print() / Ctrl+P  (page renderer)
  → printing::PrintRenderFrameHelper            [renderer]  JuxRendererClient::RenderFrameCreated
  → printing::mojom::PrintManagerHost           [mojo]      bound by JuxBrowserClient
  → printing::PrintViewManager                  [browser]   attached per-WebContents (CreateWebContentsOnUI)
  → PrintPreviewDialogController::PrintPreview               (content-layer shim — preview_dialog_map_)
  → jux::OpenPrintPreview (hook)                            print_preview/shim/jux_print_preview_hook.*
  → jux::OpenPrintPreviewWebContents            [engine]    jux_engine_api.cc
        • new JuxWebContentsHandle
        • CreateWebContentsOnUI(handle, parent=0)  ← reuses the OSR hidden-widget + capture path
        • navigate → chrome://print/
        • emit kPrintPreviewOpened(windowId, [previewHandle])  ← Java's M4 signal
  → chrome://print loads PrintPreviewUI (WebUIConfigMap) → Polymer UI from the pak
  → PrintPreviewHandler asks the initiator to render a preview PDF (generation)
```

### chrome://print hosting
- Registered in `JuxBrowserMainParts::PreMainMessageLoopRun` via
  `content::WebUIConfigMap::GetInstance().AddWebUIConfig(make_unique<PrintPreviewUIConfig>())`.
- `JuxBrowserContext`, gated, **is a `Profile`** with an in-memory `PrefService`
  (InMemoryPrefStore + PrefRegistrySyncable; kPrintPreviewDisabled,
  kPrintPreviewUseSystemDefaultPrinter, sticky-settings + policy prefs).
- Resources: the real `chrome/browser/resources/{print_preview,pdf}` Polymer apps
  build (`build_webui` TS+grit). `webui_resources.pak` (framework) +
  `print_preview_resources.pak` are merged into `skia-fx-webview.pak` under the
  flag (engine `repack("pak")`). `stubs/chrome/common/features.gni` populates
  `chrome_grit_defines` (the jux stub ships it `= []`). Do NOT disable
  `enable_pdf_ink2`/`enable_pdf_save_to_drive` — that leaves dangling TS imports.

### Verify the UI renders today
`webView.getEngine().load("chrome://print")` renders the exact Chrome panel
(no scheme gate). Strings are currently **placeholder ids** (blank labels) until
the chrome string paks are added — see "Known gaps".

## Status

| Piece | State |
|---|---|
| M0 patch infra | done |
| M1 print pipeline (PrintJob/PrintViewManager/compositor/XPS/PDF-convert) | links |
| M2 chrome://print ported + packed + registered + Profile/PrefService | done |
| Resource pak (exact Polymer UI in the engine pak) | done |
| M3 trigger (renderer helper + PrintManagerHost bind + PVM attach) | done |
| Controller (off-screen WebContents + preview_dialog_map_) | done |
| **M4 visibility (surface takeover + input + close)** | **done (compiles+links; needs headed verify)** |
| M3 generation (actual preview PDF content) | needs runtime verification |
| Strings (real localized text) | placeholders |
| M5 print / Save-as-PDF execution + Java API + leak pass | TODO |

## M4 — surface takeover (implemented; needs a headed run to confirm pixels)

The preview is a **full second WebContents**, and the engine's OSR model is **one
main surface per channel** (+ small popup slots). Rather than add a second surface
region, M4 uses the fact that Chrome print preview is a **full-tab modal** (the
page is hidden behind it) and lets the preview **take over the existing surface** —
entirely engine-side, **no Java changes needed**:

- A global `g_print_preview_handle` (UI thread) holds the active preview handle
  (`jux_engine_api.cc`). Set in `OpenPrintPreviewWebContents` (which also sizes the
  preview widget to the initiator's view so frames fill the node 1:1); cleared in
  `ClosePrintPreviewWebContents`.
- **Frames:** `JuxOnFrameCaptured` drops the initiator's frames while a preview is
  active, so only the preview is emitted as the normal `kFrameReady` — Java
  composites it unchanged.
- **Input:** `JuxSendMouseEvent` / `JuxSendWheelEvent` / `JuxSendKeyEvent` redirect
  to `g_print_preview_handle` while active — page input goes to the preview.
- **Open:** window.print()/Ctrl+P → … → `PrintPreviewDialogController::PrintPreview`
  → `jux::OpenPrintPreview` → `OpenPrintPreviewWebContents`. Emits
  `kPrintPreviewOpened` (informational).
- **Close:** Cancel/Print → `PrintPreviewUI::OnClosePrintPreviewDialog` →
  `jux::ClosePrintPreview` → `ClosePrintPreviewWebContents`: clears the takeover
  (page resumes), emits `kPrintPreviewClosed`, and tears the preview WebContents
  down via the normal deferred `JuxDestroyWebContents`.

`PRINT_PREVIEW_OPENED`/`CLOSED` (0x0610/0x0611) are defined on both sides for Java
to react if it wants (e.g. Escape-to-cancel), but basic show/interact/close needs
no Java handling.

### Open follow-ups (M4 polish / M5)
- **Headed verify**: confirm the preview composites + is interactive + closes.
- **Sizing on view-resize while open**: the preview widget is sized once at open;
  resizing the WebView while the preview is up isn't re-propagated yet.
- **Controller map cleanup**: `preview_dialog_map_` isn't erased on close (stale
  entry to a destroyed WebContents) — clean up in the leak pass.
- **Popups from the preview** (the Destination `<select>`) ride `kPopupFrame`;
  verify they composite correctly over the taken-over surface.

## Known gaps
- **Preview content (M3 generation):** chrome://print loads, but the page→PDF
  preview rendering (PrintCompositor + PDF viewer) is unverified end-to-end.
- **Strings:** ~118 `IDS_PRINT_PREVIEW_*` are placeholder ids in
  `stubs/chrome/grit/generated_resources.h` → blank labels until the
  `generated_resources`/`components_strings` string paks are added.
- **"Manage printers"** is a no-op; theme (chrome://theme) source not registered.
