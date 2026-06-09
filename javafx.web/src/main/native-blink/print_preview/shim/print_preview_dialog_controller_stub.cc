// skia-fx content-layer implementation of PrintPreviewDialogController (replaces
// chrome's ConstrainedWebDialog-based controller). M1: no-op stubs so the print
// subset links. M2: GetInstance() owns the off-screen chrome://print WebContents,
// PrintPreview() creates it for the initiator, and the Get* methods map
// preview<->initiator (rendered OSR + composited by the engine).
#include "chrome/browser/printing/print_preview_dialog_controller.h"

#include "base/logging.h"
#include "base/no_destructor.h"
#include "jux/print_preview/shim/jux_print_preview_hook.h"
#include "url/gurl.h"

namespace printing {

PrintPreviewDialogController::PrintPreviewDialogController() = default;
PrintPreviewDialogController::~PrintPreviewDialogController() = default;

PrintPreviewDialogController::InitiatorData::InitiatorData() = default;
PrintPreviewDialogController::InitiatorData::InitiatorData(
    InitiatorData&&) noexcept = default;
PrintPreviewDialogController::InitiatorData&
PrintPreviewDialogController::InitiatorData::operator=(
    InitiatorData&&) noexcept = default;
PrintPreviewDialogController::InitiatorData::~InitiatorData() = default;

// static
PrintPreviewDialogController* PrintPreviewDialogController::GetInstance() {
  static base::NoDestructor<PrintPreviewDialogController> instance;
  return instance.get();
}

// static
bool PrintPreviewDialogController::IsPrintPreviewURL(const GURL& url) {
  return url.SchemeIs("chrome") && url.host() == "print";
}

// static
bool PrintPreviewDialogController::IsPrintPreviewContentURL(const GURL& url) {
  return url.SchemeIs("chrome-untrusted") && url.host() == "print";
}

void PrintPreviewDialogController::PrintPreview(
    content::WebContents* initiator,
    const mojom::RequestPrintPreviewParams& params) {
  LOG(INFO) << "[print-preview] controller PrintPreview(initiator=" << initiator
            << ")";
  if (!initiator) {
    return;
  }
  // If a preview already exists for this initiator, reuse it (a second
  // window.print() while the panel is open just refreshes the request params).
  for (auto& [preview, data] : preview_dialog_map_) {
    if (data.initiator == initiator) {
      data.params = params;
      return;
    }
  }
  // Ask the engine to create the off-screen chrome://print WebContents (reusing
  // the OSR capture/composite path). The engine owns + lifecycles it.
  content::WebContents* preview = jux::OpenPrintPreview(initiator);
  if (!preview) {
    LOG(ERROR) << "[print-preview] engine did not create a preview WebContents "
                  "(OpenPrintPreview hook not registered?).";
    return;
  }
  InitiatorData data;
  data.initiator = initiator;
  data.params = params;
  preview_dialog_map_[preview] = std::move(data);
}

content::WebContents* PrintPreviewDialogController::GetPrintPreviewForContents(
    content::WebContents* contents) const {
  // `contents` may be an initiator (→ its preview) or a preview (→ itself).
  for (const auto& [preview, data] : preview_dialog_map_) {
    if (preview == contents || data.initiator == contents) {
      return preview;
    }
  }
  return nullptr;
}

content::WebContents* PrintPreviewDialogController::GetInitiator(
    content::WebContents* preview_dialog) {
  auto it = preview_dialog_map_.find(preview_dialog);
  return it == preview_dialog_map_.end() ? nullptr : it->second.initiator;
}

const mojom::RequestPrintPreviewParams*
PrintPreviewDialogController::GetRequestParams(
    content::WebContents* preview_dialog) const {
  auto it = preview_dialog_map_.find(preview_dialog);
  return it == preview_dialog_map_.end() ? nullptr : &it->second.params;
}

void PrintPreviewDialogController::EraseInitiatorInfo(
    content::WebContents* preview_dialog) {
  auto it = preview_dialog_map_.find(preview_dialog);
  if (it != preview_dialog_map_.end()) {
    it->second.initiator = nullptr;
  }
}

void PrintPreviewDialogController::RemovePreviewDialog(
    content::WebContents* preview_dialog) {
  const size_t erased = preview_dialog_map_.erase(preview_dialog);
  LOG(INFO) << "[print-preview] controller RemovePreviewDialog(preview="
            << preview_dialog << ") erased=" << erased;
}

}  // namespace printing
