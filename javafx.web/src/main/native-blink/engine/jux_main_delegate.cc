// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxMainDelegate — bootstrap and client creation for all process types.
//
// This delegate is constructed by JuxInit() (browser process) or by the
// same executable in child process mode (--type=). It sets up the command
// line, loads the resource bundle, and creates the appropriate client objects.

#include "jux/jux_main_delegate.h"

#include "base/files/file_path.h"
#include "base/logging.h"
#include "base/logging/logging_settings.h"
#include "base/path_service.h"
#include "build/build_config.h"
#include "content/public/app/initialize_mojo_core.h"
#include "jux/jux_browser_client.h"
#include "jux/jux_content_client.h"
#include "jux/jux_content_utility_client.h"
#include "jux/jux_renderer_client.h"
#include "ui/base/resource/resource_bundle.h"

#if BUILDFLAG(IS_WIN)
#include "base/win/win_util.h"
#endif

namespace jux {

JuxMainDelegate::JuxMainDelegate(const base::FilePath& pak_path)
    : pak_path_(pak_path) {}

JuxMainDelegate::~JuxMainDelegate() = default;

std::optional<int> JuxMainDelegate::BasicStartupComplete() {
  // The subprocess path (--browser-subprocess-path) is already set on the
  // process CommandLine by JuxInit() on the main thread before ContentMain
  // starts. We must not call AppendSwitchPath here — this runs on the
  // browser thread and would violate the CommandLine sequence checker.

  // User data path is managed by JuxBrowserContext::GetPath() — no path
  // provider overrides needed.

  // Log to a file instead of stderr — stderr logging causes
  // AllocConsole() after FreeConsole(), spawning visible CMD windows.
  // The log file goes next to the engine executable.
  base::FilePath exe_dir;
  base::PathService::Get(base::DIR_EXE, &exe_dir);
  base::FilePath log_path = exe_dir.Append(FILE_PATH_LITERAL("debug.log"));

  logging::LoggingSettings settings;
  settings.logging_dest = logging::LOG_TO_FILE;
  settings.log_file_path = log_path.value().c_str();

  // Append in ALL processes (browser + children). A fresh browser log would be
  // nicer, but on a crash the engine is respawned by Java and the new browser
  // process would otherwise TRUNCATE the crashed instance's log before we can
  // read it. Appending keeps the crash trace across respawns (delete debug.log
  // manually to reset). TODO: gate truncation behind a non-respawn launch.
  settings.delete_old = logging::APPEND_TO_OLD_LOG_FILE;

  logging::InitLogging(settings);
  logging::SetLogItems(true /* PID */, true /* TID */,
                       true /* timestamp */, false /* tick count */);

  return std::nullopt;
}

void JuxMainDelegate::PreSandboxStartup() {
  // Load the resource bundle before the sandbox locks down filesystem access.
  InitializeResourceBundle();
}

std::optional<int> JuxMainDelegate::PostEarlyInitialization(
    InvokedIn invoked_in) {
  // In the browser process, we manage FeatureList and Mojo initialization
  // ourselves. In child processes, the content layer handles it.
  if (std::holds_alternative<InvokedInBrowserProcess>(invoked_in)) {
    content::InitializeMojoCore();
  }
  return std::nullopt;
}

content::ContentClient* JuxMainDelegate::CreateContentClient() {
  content_client_ = std::make_unique<JuxContentClient>();
  return content_client_.get();
}

content::ContentBrowserClient* JuxMainDelegate::CreateContentBrowserClient() {
  browser_client_ = std::make_unique<JuxBrowserClient>();
  return browser_client_.get();
}

content::ContentRendererClient* JuxMainDelegate::CreateContentRendererClient() {
  // The renderer process dereferences GetContentClient()->renderer()
  // without null-checking (RenderThreadImpl::Init calls
  // renderer()->RenderThreadStarted()). A null return here crashes
  // the renderer immediately on startup.
  renderer_client_ = std::make_unique<JuxRendererClient>();
  return renderer_client_.get();
}

content::ContentUtilityClient* JuxMainDelegate::CreateContentUtilityClient() {
  // Registers chrome's PrintingService in utility processes so print preview's
  // pages-per-sheet (N-up) conversion can run. content's default utility client
  // registers none of chrome's services.
  utility_client_ = std::make_unique<JuxContentUtilityClient>();
  return utility_client_.get();
}

void JuxMainDelegate::InitializeResourceBundle() {
  // Use the pak path provided by JuxInit(). If empty, fall back to
  // looking next to the DLL/executable.
  base::FilePath pak_file = pak_path_;
  if (pak_file.empty()) {
    base::FilePath exe_dir;
    base::PathService::Get(base::DIR_EXE, &exe_dir);
    pak_file = exe_dir.Append(FILE_PATH_LITERAL("skia-fx-webview.pak"));
  }

  ui::ResourceBundle::InitSharedInstanceWithPakPath(pak_file);
}

}  // namespace jux
