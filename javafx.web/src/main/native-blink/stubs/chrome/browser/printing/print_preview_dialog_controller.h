// skia-fx content-layer replacement for chrome's PrintPreviewDialogController.
// Chrome hosts chrome://print inside a ConstrainedWebDialog (chrome Browser UI).
// The OSR engine instead creates an OFF-SCREEN WebContents for chrome://print and
// composites it with Skia (see plan M2). This header declares only the surface the
// ported print code actually uses; the real OSR behaviour is implemented by our
// shim (print_preview/shim/print_preview_dialog_controller_stub.cc) — initially
// no-ops so the M1 print subset links, filled in at M2.
#ifndef CHROME_BROWSER_PRINTING_PRINT_PREVIEW_DIALOG_CONTROLLER_H_
#define CHROME_BROWSER_PRINTING_PRINT_PREVIEW_DIALOG_CONTROLLER_H_

#include <map>

#include "components/printing/common/print.mojom.h"
#include "url/gurl.h"

namespace content {
class WebContents;
}

namespace printing {

class PrintPreviewDialogController {
 public:
  PrintPreviewDialogController();
  ~PrintPreviewDialogController();

  static PrintPreviewDialogController* GetInstance();

  static bool IsPrintPreviewURL(const GURL& url);
  static bool IsPrintPreviewContentURL(const GURL& url);

  // Initiates print preview for `initiator` (M2: create the off-screen
  // chrome://print WebContents and start preview generation).
  void PrintPreview(content::WebContents* initiator,
                    const mojom::RequestPrintPreviewParams& params);

  // preview dialog for `contents`, or nullptr (M2).
  content::WebContents* GetPrintPreviewForContents(
      content::WebContents* contents) const;

  // initiator for `preview_dialog`, or nullptr (M2).
  content::WebContents* GetInitiator(content::WebContents* preview_dialog);

  // Request data the WebUI handler reads for `preview_dialog`; nullptr if none
  // (M2 no-op).
  const mojom::RequestPrintPreviewParams* GetRequestParams(
      content::WebContents* preview_dialog) const;

  // Drops the initiator association for `preview_dialog`.
  void EraseInitiatorInfo(content::WebContents* preview_dialog);

  // Fully removes `preview_dialog` from the controller's map. MUST be called when
  // the preview WebContents is torn down, otherwise PrintPreview() keeps matching
  // the stale entry and a later Ctrl+P/print reuses the (destroyed) dialog instead
  // of opening a fresh one.
  void RemovePreviewDialog(content::WebContents* preview_dialog);

 private:
  // Per-preview state: the initiator that requested it + the request params the
  // WebUI handler reads back. (Chrome also tracks a tab-modal scoper; the OSR
  // overlay has no chrome tab UI, so that is dropped.)
  struct InitiatorData {
    InitiatorData();
    InitiatorData(InitiatorData&&) noexcept;
    InitiatorData& operator=(InitiatorData&&) noexcept;
    ~InitiatorData();

    content::WebContents* initiator = nullptr;
    mojom::RequestPrintPreviewParams params;
  };

  // preview WebContents -> initiator data. The preview WebContents is owned by
  // the engine (which captures + lifecycles it); this map holds raw pointers and
  // is cleared when the engine reports the preview closed.
  std::map<content::WebContents*, InitiatorData> preview_dialog_map_;
};

}  // namespace printing

#endif  // CHROME_BROWSER_PRINTING_PRINT_PREVIEW_DIALOG_CONTROLLER_H_
