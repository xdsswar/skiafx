// skia-fx — see jux_print_preview_hook.h.
#include "jux/print_preview/shim/jux_print_preview_hook.h"

namespace jux {

namespace {
OpenPrintPreviewFn g_open_print_preview = nullptr;
ClosePrintPreviewFn g_close_print_preview = nullptr;
SavePdfDialogFn g_save_pdf_dialog = nullptr;
}  // namespace

void SetOpenPrintPreviewHook(OpenPrintPreviewFn fn) {
  g_open_print_preview = fn;
}

content::WebContents* OpenPrintPreview(content::WebContents* initiator) {
  return g_open_print_preview ? g_open_print_preview(initiator) : nullptr;
}

void SetClosePrintPreviewHook(ClosePrintPreviewFn fn) {
  g_close_print_preview = fn;
}

void ClosePrintPreview(content::WebContents* preview) {
  if (g_close_print_preview) {
    g_close_print_preview(preview);
  }
}

void SetSavePdfDialogHook(SavePdfDialogFn fn) {
  g_save_pdf_dialog = fn;
}

void ShowSavePdfDialog(const std::u16string& default_name,
                       base::OnceCallback<void(base::FilePath)> on_done) {
  if (g_save_pdf_dialog) {
    g_save_pdf_dialog(default_name, std::move(on_done));
  } else if (on_done) {
    std::move(on_done).Run(base::FilePath());
  }
}

}  // namespace jux
