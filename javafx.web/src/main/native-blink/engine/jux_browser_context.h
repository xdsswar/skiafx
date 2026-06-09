// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxBrowserContext — minimal BrowserContext for the jux engine.
//
// Replaces content::ShellBrowserContext (which is test infrastructure).
// All delegate methods return nullptr — the content layer uses its
// built-in defaults for storage, permissions, downloads, and network.

#ifndef JUX_BROWSER_CONTEXT_H_
#define JUX_BROWSER_CONTEXT_H_

#include <memory>

#include "base/files/file_path.h"
#include "content/public/browser/browser_context.h"
#include "printing/buildflags/buildflags.h"

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
// chrome://print's WebUI resolves its profile via Profile::FromBrowserContext()
// (a static_cast) and reads prefs through Profile::GetPrefs(). Under print
// preview the engine's context therefore IS a (thin, content-layer) Profile with
// an in-memory PrefService. The default build keeps content::BrowserContext, so
// it stays byte-identical.
#include "chrome/browser/profiles/profile.h"
class PrefService;
#endif

namespace jux {

class JuxPermissionManager;
class JuxDownloadManagerDelegate;

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
using JuxBrowserContextBase = Profile;
#else
using JuxBrowserContextBase = content::BrowserContext;
#endif

class JuxBrowserContext : public JuxBrowserContextBase {
 public:
  // path: directory for persistent storage (cookies, cache, localStorage).
  // off_the_record: if true, nothing is persisted to disk.
  explicit JuxBrowserContext(const base::FilePath& path,
                              bool off_the_record = false);
  ~JuxBrowserContext() override;

  JuxBrowserContext(const JuxBrowserContext&) = delete;
  JuxBrowserContext& operator=(const JuxBrowserContext&) = delete;

  // content::BrowserContext overrides:
  base::FilePath GetPath() const override;
  bool IsOffTheRecord() override;
  std::unique_ptr<content::ZoomLevelDelegate> CreateZoomLevelDelegate(
      const base::FilePath& partition_path) override;
  content::DownloadManagerDelegate* GetDownloadManagerDelegate() override;
  content::BrowserPluginGuestManager* GetGuestManager() override;
  storage::SpecialStoragePolicy* GetSpecialStoragePolicy() override;
  content::PlatformNotificationService*
      GetPlatformNotificationService() override;
  content::PushMessagingService* GetPushMessagingService() override;
  content::StorageNotificationService*
      GetStorageNotificationService() override;
  content::SSLHostStateDelegate* GetSSLHostStateDelegate() override;
  content::PermissionControllerDelegate*
      GetPermissionControllerDelegate() override;
  content::ClientHintsControllerDelegate*
      GetClientHintsControllerDelegate() override;
  content::BackgroundFetchDelegate* GetBackgroundFetchDelegate() override;
  content::BackgroundSyncController* GetBackgroundSyncController() override;
  content::BrowsingDataRemoverDelegate*
      GetBrowsingDataRemoverDelegate() override;
  content::ReduceAcceptLanguageControllerDelegate*
      GetReduceAcceptLanguageControllerDelegate() override;

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  // Profile: the print-preview WebUI + handlers read settings (print-preview
  // disabled, system default printer, sticky settings, policy) through this.
  PrefService* GetPrefs() override;
#endif

 private:
  base::FilePath path_;
  bool off_the_record_;
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  // In-memory pref store with the print-preview prefs registered. Built in the
  // ctor; owned for the context lifetime.
  std::unique_ptr<PrefService> pref_service_;
#endif
  // Lazily created on first GetPermissionControllerDelegate(); surfaces page
  // permission requests to Java. Destroyed with the context.
  std::unique_ptr<JuxPermissionManager> permission_manager_;
  // Lazily created on first GetDownloadManagerDelegate(); surfaces downloads to
  // Java and reports progress. Destroyed with the context.
  std::unique_ptr<JuxDownloadManagerDelegate> download_manager_delegate_;
};

}  // namespace jux

#endif  // JUX_BROWSER_CONTEXT_H_
