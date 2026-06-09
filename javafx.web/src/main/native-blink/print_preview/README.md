# Content-layer / OSR Chrome print preview (chrome://print)

Hosts Chrome's print-preview WebUI in the content-layer engine and renders it
off-screen (see the project plan, M1–M5).

## Architecture: build the chrome sources IN PLACE

The Chrome print-preview C++ (`//chrome/browser/ui/webui/print_preview/*`,
`//chrome/browser/printing/print_preview*`, `print_view_manager*`, …) is **not
copied** — `BUILD.gn` here (`jux_print_preview`) lists those sources at their real
`//chrome/...` paths and builds them directly. This is deliberate:

- Their `#include "chrome/browser/..."` cross-references resolve naturally (one
  source of truth in the tree); no fragile mass include-rewrite.
- We never build `//chrome/browser`, so listing these sources in our target does
  not duplicate-compile or clash.

How the chrome coupling is removed, in order of preference:
1. **Stub headers** — most `chrome/browser` deps (e.g. `browser_process.h`,
   `profiles/profile.h`, `theme_source.h`) are satisfied by lightweight stubs in
   `native-blink/stubs/chrome/...`, merged over the tree by `ConfigureBuildTask`
   (we never build the real ones). No edit to the chrome source needed.
2. **Patches** — the few unavoidable behavioral edits to chrome sources
   (e.g. `PrintPreviewUI`'s base class `ConstrainedWebDialogUI` →
   `content::WebUIController`) live in `../patches/*.patch`, applied via
   `git apply` (the reproducible "patch utils" path you required).
3. **New glue** — our content-layer additions (WebUI factory, off-screen preview
   controller, BrowserContext/PrefService shims) live **in this directory** and
   are added to `jux_print_preview`'s `sources`. This dir is copied to
   `chromium/src/jux/print_preview/`.

## Gating

`jux_print_preview` asserts `enable_print_preview` and is pulled into
`//jux:jux_engine` only under that flag, so the default engine build is
byte-identical and current features are untouched.

## Build (on the build machine)

```
:javafx.web:configureBuild
:javafx.web:buildNatives \
    -Pchromium.feature.pdf=true \
    -Pchromium.feature.printing=true \
    -Pchromium.feature.printPreview=true
```

## Status

M1 — bootstrapping. `BUILD.gn` starts with a tier-1 (low-coupling) source list;
we grow it tier-by-tier, fixing the gn/compile/link error cascade (add a stub,
copy a missing dep dir from the reference, adjust deps). Tiers still to add:
`PrintPreviewUI` (+ base swap), `PrintPreviewHandler`, printer handlers,
`PrintViewManager` / `PrintViewManagerBase`, `PrintPreviewDialogController`
(trimmed for OSR), the `print_preview_resources.pak`, then the WebUI factory +
off-screen controller (M2).
