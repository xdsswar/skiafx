// skia-fx content-layer shim: replaces chrome/browser/printing/print_error_dialog.cc
// (which shows a chrome Browser message box via browser_finder/simple_message_box).
// The OSR engine has no chrome Browser UI; these print-error entry points become
// logged no-ops. A future revision can surface print errors to Java via an event.
#include "chrome/browser/printing/print_error_dialog.h"

#include "base/functional/callback.h"
#include "base/logging.h"

void ShowPrintErrorDialogForInvalidPrinterError() {
  LOG(WARNING) << "[jux-print] invalid printer settings";
}

void ShowPrintErrorDialogForGenericError() {
  LOG(WARNING) << "[jux-print] printing failed";
}

void SetShowPrintErrorDialogForTest(base::RepeatingClosure /*callback*/) {}
