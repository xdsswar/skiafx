// skia-fx content-layer glue: defines the few chrome globals/switches the ported
// print code references but whose defining .cc (browser_process_impl, the giant
// chrome_switches.cc, bad_message.cc) we don't compile.
#include "chrome/browser/bad_message.h"
#include "chrome/browser/browser_process.h"
// Include the declaring headers so these definitions match the (extern)
// declarations and get external linkage (a bare `const char[]` at namespace scope
// would be internal-linkage + flagged unused).
#include "chrome/browser/pdf/pdf_pref_names.h"
#include "chrome/common/chrome_switches.h"
#include "chrome/common/printing/printer_capabilities.h"

// g_browser_process is now defined by chrome/browser/browser_process.cc (built
// into jux_print_preview) and populated with a JuxBrowserProcess at startup by
// jux::InstallPrintPreviewBrowserProcess (jux_browser_process.cc).

namespace switches {
// Command-line switch name; value only matters at runtime (kiosk printing), which
// the engine doesn't use.
const char kKioskModePrinting[] = "kiosk-printing";
}  // namespace switches

// printing::kPrinter is now provided by the real chrome/common/printing/
// printer_capabilities.cc (built into jux_print_preview), so the M1 stub
// definition was removed to avoid a duplicate symbol.

namespace prefs {
// Defined by chrome/browser/pdf/pdf_pref_names.cc (not compiled here).
const char kPdfUseSkiaRendererEnabled[] = "pdf.enable_skia_renderer";
}  // namespace prefs

namespace bad_message {
// Renderer-kill on bad IPC: the engine logs/ignores rather than killing here.
void ReceivedBadMessage(content::RenderProcessHost* /*host*/,
                        BadMessageReason /*reason*/) {}
}  // namespace bad_message
