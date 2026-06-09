// skia-fx — see jux_browser_process.h.
#include "jux/print_preview/shim/jux_browser_process.h"

#include <memory>
#include <string>

#include "base/functional/callback.h"
#include "base/memory/scoped_refptr.h"
#include "base/notreached.h"
#include "build/build_config.h"
#include "chrome/browser/browser_process.h"
#include "chrome/common/buildflags.h"
#include "components/os_crypt/async/browser/key_provider.h"
#include "components/safe_browsing/buildflags.h"
#include "chrome/browser/printing/print_job_manager.h"
#include "chrome/browser/printing/print_preview_dialog_controller.h"
#include "components/prefs/in_memory_pref_store.h"
#include "components/prefs/pref_registry_simple.h"
#include "components/prefs/pref_service.h"
#include "components/prefs/pref_service_factory.h"

namespace jux {
namespace {

// The three members the print pipeline actually uses are live; everything else is
// nullptr / no-op / NOTREACHED (the print subset never reaches them).
class JuxBrowserProcess : public BrowserProcess {
 public:
  JuxBrowserProcess() {
    PrefServiceFactory factory;
    factory.set_user_prefs(base::MakeRefCounted<InMemoryPrefStore>());
    auto registry = base::MakeRefCounted<PrefRegistrySimple>();
    local_state_ = factory.Create(registry.get());
  }
  ~JuxBrowserProcess() override = default;

  // --- live ---
  PrefService* local_state() override { return local_state_.get(); }
  printing::PrintJobManager* print_job_manager() override {
    return &print_job_manager_;
  }
  printing::PrintPreviewDialogController* print_preview_dialog_controller()
      override {
    return printing::PrintPreviewDialogController::GetInstance();
  }
  const std::string& GetApplicationLocale() override { return locale_; }
  void SetApplicationLocale(const std::string& locale) override {
    locale_ = locale;
  }
  bool IsShuttingDown() override { return false; }

  // --- void no-ops (never exercised by the print subset) ---
  void EndSession() override {}
  void FlushLocalStateAndReply(base::OnceClosure reply) override {
    if (reply) {
      std::move(reply).Run();
    }
  }
  void CreateDevToolsProtocolHandler() override {}
  void CreateDevToolsAutoOpener() override {}
#if BUILDFLAG(IS_WIN) || BUILDFLAG(IS_LINUX)
  void StartAutoupdateTimer() override {}
#endif
#if BUILDFLAG(ENABLE_BACKGROUND_MODE)
  void set_background_mode_manager_for_test(
      std::unique_ptr<BackgroundModeManager>) override {}
#endif
  void set_additional_os_crypt_async_provider_for_test(
      size_t,
      std::unique_ptr<os_crypt_async::KeyProvider>) override {}

  // --- references with no backing: not reachable by the print subset ---
  ui::UnownedUserDataHost& GetUnownedUserDataHost() override { NOTREACHED(); }
  const ui::UnownedUserDataHost& GetUnownedUserDataHost() const override {
    NOTREACHED();
  }
  supervised_user::DeviceParentalControls& device_parental_controls()
      override {
    NOTREACHED();
  }

  // --- pointer accessors: null (unused) ---
  metrics_services_manager::MetricsServicesManager* GetMetricsServicesManager()
      override {
    return nullptr;
  }
  embedder_support::OriginTrialsSettingsStorage*
  GetOriginTrialsSettingsStorage() override {
    return nullptr;
  }
  metrics::MetricsService* metrics_service() override { return nullptr; }
  ProfileManager* profile_manager() override { return nullptr; }
  scoped_refptr<network::SharedURLLoaderFactory> shared_url_loader_factory()
      override {
    return nullptr;
  }
  signin::ActivePrimaryAccountsMetricsRecorder*
  active_primary_accounts_metrics_recorder() override {
    return nullptr;
  }
  variations::VariationsService* variations_service() override {
    return nullptr;
  }
  BrowserProcessPlatformPart* platform_part() override { return nullptr; }
  NotificationUIManager* notification_ui_manager() override { return nullptr; }
  NotificationPlatformBridge* notification_platform_bridge() override {
    return nullptr;
  }
  SystemNetworkContextManager* system_network_context_manager() override {
    return nullptr;
  }
  network::NetworkQualityTracker* network_quality_tracker() override {
    return nullptr;
  }
  policy::ChromeBrowserPolicyConnector* browser_policy_connector() override {
    return nullptr;
  }
  policy::PolicyService* policy_service() override { return nullptr; }
  IconManager* icon_manager() override { return nullptr; }
  GpuModeManager* gpu_mode_manager() override { return nullptr; }
  printing::BackgroundPrintingManager* background_printing_manager() override {
    return nullptr;
  }
  IntranetRedirectDetector* intranet_redirect_detector() override {
    return nullptr;
  }
  DownloadStatusUpdater* download_status_updater() override { return nullptr; }
  DownloadRequestLimiter* download_request_limiter() override {
    return nullptr;
  }
#if BUILDFLAG(ENABLE_BACKGROUND_MODE)
  BackgroundModeManager* background_mode_manager() override { return nullptr; }
#endif
  StatusTray* status_tray() override { return nullptr; }
#if BUILDFLAG(SAFE_BROWSING_AVAILABLE)
  safe_browsing::SafeBrowsingService* safe_browsing_service() override {
    return nullptr;
  }
#endif
  subresource_filter::RulesetService* subresource_filter_ruleset_service()
      override {
    return nullptr;
  }
  StartupData* startup_data() override { return nullptr; }
  activity_reporter::ActivityReporter* activity_reporter() override {
    return nullptr;
  }
  component_updater::ComponentUpdateService* component_updater() override {
    return nullptr;
  }
#if BUILDFLAG(IS_CHROMEOS)
  MediaFileSystemRegistry* media_file_system_registry() override {
    return nullptr;
  }
#endif
  WebRtcLogUploader* webrtc_log_uploader() override { return nullptr; }
  network_time::NetworkTimeTracker* network_time_tracker() override {
    return nullptr;
  }
  gcm::GCMDriver* gcm_driver() override { return nullptr; }
  resource_coordinator::TabManager* GetTabManager() override {
    return nullptr;
  }
  resource_coordinator::ResourceCoordinatorParts* resource_coordinator_parts()
      override {
    return nullptr;
  }
  SerialPolicyAllowedPorts* serial_policy_allowed_ports() override {
    return nullptr;
  }
  HidSystemTrayIcon* hid_system_tray_icon() override { return nullptr; }
  UsbSystemTrayIcon* usb_system_tray_icon() override { return nullptr; }
  os_crypt_async::OSCryptAsync* os_crypt_async() override { return nullptr; }
  BuildState* GetBuildState() override { return nullptr; }
  GlobalFeatures* GetFeatures() override { return nullptr; }

 private:
  printing::PrintJobManager print_job_manager_;
  std::unique_ptr<PrefService> local_state_;
  std::string locale_ = "en-US";
};

}  // namespace

void InstallPrintPreviewBrowserProcess() {
  if (!g_browser_process) {
    g_browser_process = new JuxBrowserProcess();  // process-lifetime singleton
  }
}

}  // namespace jux
