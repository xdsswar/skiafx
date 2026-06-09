// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxMainDelegate — the master ContentMainDelegate for the jux engine.
//
// This is the first Chromium customization point. It creates all process-
// specific client objects (browser, renderer, GPU, utility) and handles
// resource bundle initialization.
//
// Replaces content::ShellMainDelegate. No test/web-test code, no crash
// reporter integration (jux handles crash recovery at the Java layer
// via the heartbeat/watchdog system).

#ifndef JUX_MAIN_DELEGATE_H_
#define JUX_MAIN_DELEGATE_H_

#include <memory>
#include <optional>
#include <string>
#include <variant>

#include "base/files/file_path.h"
#include "content/public/app/content_main_delegate.h"

namespace jux {

class JuxContentClient;
class JuxBrowserClient;
class JuxRendererClient;
class JuxContentUtilityClient;

class JuxMainDelegate : public content::ContentMainDelegate {
 public:
  // pak_path: absolute path to jux-engine.pak (resource bundle).
  // The subprocess path (--browser-subprocess-path) is set on the process
  // CommandLine by JuxInit() before ContentMain runs.
  explicit JuxMainDelegate(const base::FilePath& pak_path);

  JuxMainDelegate(const JuxMainDelegate&) = delete;
  JuxMainDelegate& operator=(const JuxMainDelegate&) = delete;

  ~JuxMainDelegate() override;

  // content::ContentMainDelegate overrides:
  std::optional<int> BasicStartupComplete() override;
  void PreSandboxStartup() override;
  std::optional<int> PostEarlyInitialization(InvokedIn invoked_in) override;
  content::ContentClient* CreateContentClient() override;
  content::ContentBrowserClient* CreateContentBrowserClient() override;
  content::ContentRendererClient* CreateContentRendererClient() override;
  content::ContentUtilityClient* CreateContentUtilityClient() override;

 private:
  // Loads jux-engine.pak into the shared ResourceBundle.
  void InitializeResourceBundle();

  // Absolute path to the resource pak file.
  base::FilePath pak_path_;

  // Owned client instances — alive for the lifetime of the process.
  std::unique_ptr<JuxContentClient> content_client_;
  std::unique_ptr<JuxBrowserClient> browser_client_;
  std::unique_ptr<JuxRendererClient> renderer_client_;
  std::unique_ptr<JuxContentUtilityClient> utility_client_;
};

}  // namespace jux

#endif  // JUX_MAIN_DELEGATE_H_
