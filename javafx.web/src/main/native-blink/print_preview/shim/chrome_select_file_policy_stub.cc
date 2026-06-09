// skia-fx content-layer impl of ChromeSelectFilePolicy. The real
// chrome/browser/ui/chrome_select_file_policy.cc consults local_state() and
// shows an infobar on the source WebContents when file pickers are disabled by
// policy — pulling the chrome infobar + browser_process + prefs cone. The jux
// engine has no such policy and no infobar surface, so file pickers are always
// allowed. Used by pdf_printer_handler.cc for the "Save as PDF" dialog.
#include "chrome/browser/ui/chrome_select_file_policy.h"

ChromeSelectFilePolicy::ChromeSelectFilePolicy(
    content::WebContents* source_contents)
    : source_contents_(source_contents) {}

ChromeSelectFilePolicy::~ChromeSelectFilePolicy() = default;

bool ChromeSelectFilePolicy::CanOpenSelectFileDialog() {
  return true;
}

void ChromeSelectFilePolicy::SelectFileDenied() {}

// static
bool ChromeSelectFilePolicy::FileSelectDialogsAllowed() {
  return true;
}
