// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxDownloadManagerDelegate — surfaces downloads to Java instead of writing
// them silently, and reports progress/completion back.
//
// One BrowserContext per engine process, so a single process-global instance
// is sufficient. Lives on the browser UI thread.
//
// Flow mirrors the permission/dialog template: DetermineDownloadTarget stashes
// Chromium's DownloadTargetCallback under a downloadId, fires
// kDownloadRequested, and returns true (handled async) WITHOUT running the
// callback (the download stays in target-pending). Java answers via
// JuxRespondDownload → Respond(), which either supplies the chosen path (and
// starts observing the item for progress) or cancels with an empty target.

#ifndef JUX_DOWNLOAD_MANAGER_DELEGATE_H_
#define JUX_DOWNLOAD_MANAGER_DELEGATE_H_

#include <cstdint>
#include <map>
#include <string>

#include "base/memory/raw_ptr.h"
#include "components/download/public/common/download_item.h"
#include "content/public/browser/download_manager_delegate.h"

namespace jux {

class JuxDownloadManagerDelegate : public content::DownloadManagerDelegate,
                                   public download::DownloadItem::Observer {
 public:
  JuxDownloadManagerDelegate();
  ~JuxDownloadManagerDelegate() override;

  JuxDownloadManagerDelegate(const JuxDownloadManagerDelegate&) = delete;
  JuxDownloadManagerDelegate& operator=(const JuxDownloadManagerDelegate&) =
      delete;

  // The process-global instance. Used by JuxRespondDownload/JuxCancelDownload
  // to deliver the Java answer.
  static JuxDownloadManagerDelegate* GetInstance();

  // content::DownloadManagerDelegate:
  // Hands out monotonically-increasing download ids (the base default returns
  // kInvalidId for every download, which collides the in-progress manager's
  // keys).
  void GetNextId(content::DownloadIdCallback callback) override;
  bool DetermineDownloadTarget(
      download::DownloadItem* item,
      download::DownloadTargetCallback* callback) override;

  // download::DownloadItem::Observer:
  void OnDownloadUpdated(download::DownloadItem* item) override;
  void OnDownloadDestroyed(download::DownloadItem* item) override;

  // Supplies the chosen save path (accepted=true) or cancels the download
  // (accepted=false). Browser UI thread.
  void Respond(uint32_t download_id, bool accepted, const std::string& path);

  // Cancels an in-progress download. Browser UI thread.
  void Cancel(uint32_t download_id);

 private:
  // Out-of-line ctor/dtor required by chromium-style (move-only callback +
  // raw_ptr members).
  struct Pending {
    Pending();
    ~Pending();
    Pending(Pending&&);
    Pending& operator=(Pending&&);

    download::DownloadTargetCallback callback;  // null after the target is set
    raw_ptr<download::DownloadItem> item = nullptr;
    int last_state = -1;     // last reported content DownloadState (dedup)
    bool finished = false;   // a terminal state has already been reported
  };

  // Fires kDownloadFinished and tears down tracking for a terminal item.
  void FinishAndForget(uint32_t download_id, download::DownloadItem* item);

  uint32_t next_download_id_ = 1;
  std::map<uint32_t, Pending> pending_;                 // by download id
  std::map<download::DownloadItem*, uint32_t> by_item_;  // reverse lookup
};

}  // namespace jux

#endif  // JUX_DOWNLOAD_MANAGER_DELEGATE_H_
