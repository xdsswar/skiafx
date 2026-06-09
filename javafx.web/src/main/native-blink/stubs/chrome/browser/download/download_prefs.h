// skia-fx minimal stub for chrome/browser/download/download_prefs.h.
//
// The real DownloadPrefs (chrome/browser/download/download_prefs.cc) pulls the
// full chrome download-manager cone (DownloadManager, prefs registration,
// policy). The print-preview PDF printer handler (pdf_printer_handler.cc) only
// needs four path accessors to choose/remember the "Save as PDF" directory:
//   FromBrowserContext(), DownloadPath(), SaveFilePath(), SetSaveFilePath().
//
// This header-only stub provides exactly those, backed by a process-global
// last-used path. M2: compiles chrome://print; Save-as-PDF defaults to the
// process working dir. M5 wires this to the jux engine's real download dir.
#ifndef CHROME_BROWSER_DOWNLOAD_DOWNLOAD_PREFS_H_
#define CHROME_BROWSER_DOWNLOAD_DOWNLOAD_PREFS_H_

#include "base/files/file_path.h"

namespace content {
class BrowserContext;
}

class DownloadPrefs {
 public:
  // One process-global instance is enough for the preview's save-path needs;
  // the BrowserContext is ignored (single embedder browser context).
  static DownloadPrefs* FromBrowserContext(content::BrowserContext* /*ctx*/) {
    static DownloadPrefs* instance = new DownloadPrefs();
    return instance;
  }

  base::FilePath DownloadPath() const { return save_file_path_; }
  base::FilePath SaveFilePath() const { return save_file_path_; }
  void SetSaveFilePath(const base::FilePath& path) { save_file_path_ = path; }

 private:
  DownloadPrefs() = default;
  base::FilePath save_file_path_;
};

#endif  // CHROME_BROWSER_DOWNLOAD_DOWNLOAD_PREFS_H_
