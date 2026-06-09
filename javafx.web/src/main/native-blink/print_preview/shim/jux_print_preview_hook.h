// skia-fx — bridge between the content-layer PrintPreviewDialogController (built
// in jux_print_preview) and the engine's OSR WebContents machinery (built in
// jux_engine). jux_engine depends on jux_print_preview, so the controller cannot
// include engine headers directly; instead the engine registers a factory here
// at startup and the controller calls it.
//
// The factory creates the OFF-SCREEN chrome://print WebContents — reusing the
// same hidden-window + CopyFromSurface capture path as a normal OSR WebView, but
// tagged as a print-preview overlay so Java composites it modally over the page.
#ifndef JUX_PRINT_PREVIEW_SHIM_JUX_PRINT_PREVIEW_HOOK_H_
#define JUX_PRINT_PREVIEW_SHIM_JUX_PRINT_PREVIEW_HOOK_H_

#include <string>

#include "base/files/file_path.h"
#include "base/functional/callback.h"

namespace content {
class WebContents;
}

namespace jux {

// Creates the off-screen chrome://print WebContents for `initiator` and returns
// it (ownership stays with the engine, which captures + lifecycles it). Returns
// nullptr if the engine has not registered a factory.
using OpenPrintPreviewFn =
    content::WebContents* (*)(content::WebContents* initiator);

// Registered once by the engine (JuxBrowserMainParts) at startup.
void SetOpenPrintPreviewHook(OpenPrintPreviewFn fn);

// Called by PrintPreviewDialogController::PrintPreview.
content::WebContents* OpenPrintPreview(content::WebContents* initiator);

// Tears down the off-screen preview `preview`: stops the surface takeover (the
// initiator resumes), notifies Java (kPrintPreviewClosed), and destroys the
// preview WebContents. Safe to call from within the preview's own WebUI callback
// (the teardown is deferred).
using ClosePrintPreviewFn = void (*)(content::WebContents* preview);
void SetClosePrintPreviewHook(ClosePrintPreviewFn fn);
void ClosePrintPreview(content::WebContents* preview);

// Shows a NATIVE "Save As" dialog for a PDF, parented to the foreground (the
// JavaFX Stage) window, and runs `on_done` on the caller's sequence with the
// chosen path (empty if cancelled). The engine implements this with a Win32
// IFileSaveDialog on a COM-STA thread: our print-preview/page WebContents are
// rendered off-screen (OSR) and have no on-screen window for ui::SelectFileDialog
// to parent to, so its dialog never appeared. Set once by the engine at startup.
using SavePdfDialogFn = void (*)(const std::u16string& default_name,
                                 base::OnceCallback<void(base::FilePath)> on_done);
void SetSavePdfDialogHook(SavePdfDialogFn fn);
void ShowSavePdfDialog(const std::u16string& default_name,
                       base::OnceCallback<void(base::FilePath)> on_done);

}  // namespace jux

#endif  // JUX_PRINT_PREVIEW_SHIM_JUX_PRINT_PREVIEW_HOOK_H_
