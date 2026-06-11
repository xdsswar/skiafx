// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxBrowserClient — browser process customization.
//
// This is the ContentBrowserClient for the jux engine. It creates the
// BrowserMainParts (which sets up BrowserContext, aura, etc.) and
// provides the minimal overrides needed for an embedder.

#ifndef JUX_BROWSER_CLIENT_H_
#define JUX_BROWSER_CLIENT_H_

#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "content/public/browser/content_browser_client.h"
#include "content/public/browser/login_delegate.h"

namespace jux {

class JuxBrowserMainParts;
class JuxNetworkInterceptor;

class JuxBrowserClient : public content::ContentBrowserClient {
 public:
  // Returns the current instance (set during construction).
  static JuxBrowserClient* Get();

  JuxBrowserClient();
  ~JuxBrowserClient() override;

  JuxBrowserClient(const JuxBrowserClient&) = delete;
  JuxBrowserClient& operator=(const JuxBrowserClient&) = delete;

  // Returns the BrowserMainParts instance (valid after CreateBrowserMainParts).
  JuxBrowserMainParts* browser_main_parts() const {
    return browser_main_parts_;
  }

  // content::ContentBrowserClient overrides:
  std::unique_ptr<content::BrowserMainParts> CreateBrowserMainParts(
      bool is_integration_test) override;
  std::string GetAcceptLangs(content::BrowserContext* context) override;
  std::string GetDefaultDownloadName() override;

  // Forwards OSR-relevant switches (e.g. --disable-direct-composition) from
  // the browser command line to child processes — the GPU process reads them
  // from ITS OWN command line, and content's default forwarding list does not
  // include them, so a browser-only switch would silently do nothing.
  void AppendExtraCommandLineSwitches(base::CommandLine* command_line,
                                      int child_process_id) override;

  // Under print preview, gives the off-screen chrome://print frame (and its PDF
  // viewer subframe) a chrome-untrusted://print subresource loader factory, so the
  // PDF plugin can fetch the generated preview PDF from the untrusted byte-server
  // (otherwise the cross-scheme fetch fails with ERR_DISALLOWED_URL_SCHEME).
  void RegisterNonNetworkSubresourceURLLoaderFactories(
      int render_process_id,
      int render_frame_id,
      const std::optional<url::Origin>& request_initiator_origin,
      NonNetworkURLLoaderFactoryMap* factories) override;

  // Binds renderer→browser (non-associated) frame interfaces. Under print preview
  // this exposes jux::mojom::PdfDataProvider so the in-renderer PDF plugin can
  // fetch the generated preview PDF bytes from PrintPreviewDataService.
  void RegisterBrowserInterfaceBindersForFrame(
      content::RenderFrameHost* render_frame_host,
      mojo::BinderMapWithContext<content::RenderFrameHost*>* map) override;

  // Binds renderer→browser associated interfaces. Under print preview this binds
  // printing::mojom::PrintManagerHost to the per-WebContents PrintViewManager, so
  // window.print()/Ctrl+P from a frame reaches the print-preview pipeline.
  void RegisterAssociatedInterfaceBindersForRenderFrameHost(
      content::RenderFrameHost& render_frame_host,
      blink::AssociatedInterfaceRegistry& associated_registry) override;

  // The process-wide default User-Agent. Without this, content's embedder UA
  // is empty and some sites reject the request ("UserAgent parameter can't be
  // empty"). Returns a frozen Chrome-like UA built for the pinned Chromium
  // milestone. Per-WebView overrides go through WebContents::SetUserAgentOverride
  // and take precedence over this default.
  std::string GetUserAgent() override;

  // Client-hint metadata that matches GetUserAgent(), so sites using UA-CH get
  // consistent brand/platform values.
  blink::UserAgentMetadata GetUserAgentMetadata() override;

  // Inserts the full-MITM JuxProxyingURLLoaderFactory into each factory when the
  // network interceptor is armed (zero overhead otherwise). The factory proxy
  // supersedes the throttle for header-edit / redirect / synthetic / response-
  // phase actions, which the throttle cannot do.
  void WillCreateURLLoaderFactory(
      content::BrowserContext* browser_context,
      content::RenderFrameHost* frame,
      int render_process_id,
      URLLoaderFactoryType type,
      const url::Origin& request_initiator,
      const net::IsolationInfo& isolation_info,
      std::optional<int64_t> navigation_id,
      ukm::SourceIdObj ukm_source_id,
      network::URLLoaderFactoryBuilder& factory_builder,
      mojo::PendingRemote<network::mojom::TrustedURLLoaderHeaderClient>*
          header_client,
      bool* bypass_redirect_checks,
      bool* disable_secure_dns,
      network::mojom::URLLoaderFactoryOverridePtr* factory_override,
      scoped_refptr<base::SequencedTaskRunner> navigation_response_task_runner)
      override;

  // Vends a JuxUrlLoaderThrottle for every request so the network interceptor
  // can observe/block matched requests (no-op when not armed).
  std::vector<std::unique_ptr<blink::URLLoaderThrottle>> CreateURLLoaderThrottles(
      const network::ResourceRequest& request,
      content::BrowserContext* browser_context,
      const base::RepeatingCallback<content::WebContents*()>& wc_getter,
      content::NavigationUIData* navigation_ui_data,
      content::FrameTreeNodeId frame_tree_node_id,
      std::optional<int64_t> navigation_id) override;

  // Surfaces HTTP/proxy auth challenges to Java via a JuxLoginDelegate.
  std::unique_ptr<content::LoginDelegate> CreateLoginDelegate(
      const net::AuthChallengeInfo& auth_info,
      content::WebContents* web_contents,
      content::BrowserContext* browser_context,
      const content::GlobalRequestID& request_id,
      bool is_request_for_primary_main_frame_navigation,
      bool is_request_for_navigation,
      const GURL& url,
      scoped_refptr<net::HttpResponseHeaders> response_headers,
      bool first_auth_attempt,
      content::GuestPageHolder* guest_page_holder,
      content::LoginDelegate::LoginAuthRequiredCallback auth_required_callback)
      override;

 private:
  // Raw pointer — owned by the content layer (BrowserMainLoop).
  // Valid from CreateBrowserMainParts() until the browser shuts down.
  raw_ptr<JuxBrowserMainParts> browser_main_parts_ = nullptr;

  // Process-global request interceptor (its ctor registers the singleton used
  // by the throttles + the arm/resolve C API).
  std::unique_ptr<JuxNetworkInterceptor> network_interceptor_;
};

}  // namespace jux

#endif  // JUX_BROWSER_CLIENT_H_
