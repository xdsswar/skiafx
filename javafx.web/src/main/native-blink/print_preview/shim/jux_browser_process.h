// skia-fx — installs a minimal content-layer BrowserProcess as the global
// g_browser_process so the ported chrome print code can resolve it.
//
// The print pipeline reaches g_browser_process for exactly three things:
//   print_job_manager(), local_state(), print_preview_dialog_controller().
// Chrome's real BrowserProcessImpl is enormous; instead JuxBrowserProcess (in the
// .cc) implements the BrowserProcess interface with those three live and every
// other method NOTREACHED() (none are exercised by the print subset). Without
// this, PrintViewManagerBase's constructor dereferences a null g_browser_process
// and crashes the engine the moment any WebContents is created.
#ifndef JUX_PRINT_PREVIEW_SHIM_JUX_BROWSER_PROCESS_H_
#define JUX_PRINT_PREVIEW_SHIM_JUX_BROWSER_PROCESS_H_

namespace jux {

// Creates the JuxBrowserProcess and assigns it to g_browser_process if not
// already set. Call once at browser startup, before any WebContents is created.
void InstallPrintPreviewBrowserProcess();

}  // namespace jux

#endif  // JUX_PRINT_PREVIEW_SHIM_JUX_BROWSER_PROCESS_H_
