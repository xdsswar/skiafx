// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxBrowserContext — production BrowserContext for the jux engine.
//
// Every delegate method returns nullptr — the content layer uses
// built-in defaults. No Chrome-level code needed.

#include "jux/jux_browser_context.h"

#include "base/files/file_util.h"
#include "jux/jux_download_manager_delegate.h"
#include "jux/jux_permission_manager.h"

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
#include "base/memory/scoped_refptr.h"
#include "chrome/browser/pdf/pdf_pref_names.h"
#include "chrome/browser/printing/print_preview_sticky_settings.h"
#include "chrome/browser/ui/webui/print_preview/policy_settings.h"
#include "chrome/common/pref_names.h"
#include "components/pref_registry/pref_registry_syncable.h"
#include "components/prefs/in_memory_pref_store.h"
#include "components/prefs/pref_service.h"
#include "components/prefs/pref_service_factory.h"
#include "components/user_prefs/user_prefs.h"
#endif

namespace jux {

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
namespace {

// Builds the in-memory PrefService the print-preview WebUI reads. Only the prefs
// the ported chrome://print code touches are registered; the store is not
// persisted (print settings stickiness lives for the process lifetime).
std::unique_ptr<PrefService> BuildPrintPreviewPrefs() {
  auto registry = base::MakeRefCounted<user_prefs::PrefRegistrySyncable>();
  // Read by PrintViewManagerBase's ctor (printing_enabled_.Init) for every
  // WebContents — must be registered or that BooleanPrefMember CHECKs.
  registry->RegisterBooleanPref(prefs::kPrintingEnabled, true);
  registry->RegisterBooleanPref(prefs::kPrintPreviewDisabled, false);
  registry->RegisterBooleanPref(prefs::kPrintPreviewUseSystemDefaultPrinter,
                                false);
  // Read by PdfNupConverterClient's ctor for every WebContents.
  registry->RegisterBooleanPref(prefs::kPdfUseSkiaRendererEnabled, false);
  printing::PrintPreviewStickySettings::RegisterProfilePrefs(registry.get());
  printing::PolicySettings::RegisterProfilePrefs(registry.get());
  // Read by PrintPreviewHandler but NOT covered by PolicySettings above. In real
  // Chrome these are registered in chrome/browser/prefs (which we don't build).
  // A GetString/GetBoolean on an UNregistered pref CHECK-crashes the engine, so
  // register them with Chrome's defaults. (kPrintPreviewDefaultDestination-
  // SelectionRules → SendInitialSettings GetString; kSilentPrintingEnabled →
  // SilentPrintingEnabled(); kPrintPdfAsImage* → the PDF printer handler.)
  registry->RegisterStringPref(
      prefs::kPrintPreviewDefaultDestinationSelectionRules, std::string());
  registry->RegisterBooleanPref(prefs::kSilentPrintingEnabled, false);
  registry->RegisterBooleanPref(prefs::kPrintPdfAsImageAvailability, false);
  registry->RegisterBooleanPref(prefs::kPrintPdfAsImageDefault, false);

  PrefServiceFactory factory;
  factory.set_user_prefs(base::MakeRefCounted<InMemoryPrefStore>());
  return factory.Create(registry.get());
}

}  // namespace
#endif

JuxBrowserContext::JuxBrowserContext(const base::FilePath& path,
                                     bool off_the_record)
    : path_(path), off_the_record_(off_the_record) {
  // Ensure the data directory exists. Chromium creates subdirectories
  // (WebStorage, Cache, etc.) on demand, but the parent must exist.
  // Without this, web storage APIs (localStorage, sessionStorage) fail
  // silently with "Couldn't open QuotaManager" errors.
  base::CreateDirectory(path_);

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  pref_service_ = BuildPrintPreviewPrefs();
  // Attach the prefs to the context so user_prefs::UserPrefs::Get(context)
  // (used by some printing components) resolves to the same service.
  user_prefs::UserPrefs::Set(this, pref_service_.get());
#endif
}

JuxBrowserContext::~JuxBrowserContext() {
  ShutdownStoragePartitions();
}

base::FilePath JuxBrowserContext::GetPath() const { return path_; }
bool JuxBrowserContext::IsOffTheRecord() { return off_the_record_; }

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
PrefService* JuxBrowserContext::GetPrefs() { return pref_service_.get(); }
#endif

std::unique_ptr<content::ZoomLevelDelegate>
JuxBrowserContext::CreateZoomLevelDelegate(const base::FilePath&) {
  return nullptr;
}

content::DownloadManagerDelegate*
JuxBrowserContext::GetDownloadManagerDelegate() {
  if (!download_manager_delegate_) {
    download_manager_delegate_ =
        std::make_unique<JuxDownloadManagerDelegate>();
  }
  return download_manager_delegate_.get();
}
content::BrowserPluginGuestManager*
JuxBrowserContext::GetGuestManager() { return nullptr; }
storage::SpecialStoragePolicy*
JuxBrowserContext::GetSpecialStoragePolicy() { return nullptr; }
content::PlatformNotificationService*
JuxBrowserContext::GetPlatformNotificationService() { return nullptr; }
content::PushMessagingService*
JuxBrowserContext::GetPushMessagingService() { return nullptr; }
content::StorageNotificationService*
JuxBrowserContext::GetStorageNotificationService() { return nullptr; }
content::SSLHostStateDelegate*
JuxBrowserContext::GetSSLHostStateDelegate() { return nullptr; }
content::PermissionControllerDelegate*
JuxBrowserContext::GetPermissionControllerDelegate() {
  if (!permission_manager_) {
    permission_manager_ = std::make_unique<JuxPermissionManager>();
  }
  return permission_manager_.get();
}
content::ClientHintsControllerDelegate*
JuxBrowserContext::GetClientHintsControllerDelegate() { return nullptr; }
content::BackgroundFetchDelegate*
JuxBrowserContext::GetBackgroundFetchDelegate() { return nullptr; }
content::BackgroundSyncController*
JuxBrowserContext::GetBackgroundSyncController() { return nullptr; }
content::BrowsingDataRemoverDelegate*
JuxBrowserContext::GetBrowsingDataRemoverDelegate() { return nullptr; }
content::ReduceAcceptLanguageControllerDelegate*
JuxBrowserContext::GetReduceAcceptLanguageControllerDelegate() {
  return nullptr;
}

}  // namespace jux
