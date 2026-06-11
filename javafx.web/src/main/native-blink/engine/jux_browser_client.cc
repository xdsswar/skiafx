// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxBrowserClient — creates BrowserMainParts and provides browser config.

#include "jux/jux_browser_client.h"

#include <atomic>
#include <memory>
#include <string>
#include <utility>

#include "base/command_line.h"
#include "components/embedder_support/user_agent_utils.h"
#include "jux/jux_browser_main_parts.h"
#include "jux/jux_login_delegate.h"
#include "jux/jux_network_interceptor.h"
#include "jux/jux_proxying_url_loader_factory.h"
#include "jux/jux_url_loader_throttle.h"
#include "net/base/auth.h"
#include "services/network/public/cpp/url_loader_factory_builder.h"

#include "printing/buildflags/buildflags.h"
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
#include "base/functional/bind.h"
#include "chrome/browser/printing/print_view_manager.h"
#include "components/printing/common/print.mojom.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/web_ui_url_loader_factory.h"
#include "content/public/common/url_constants.h"
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
#include "base/containers/span.h"
#include "base/memory/ref_counted_memory.h"
#include "chrome/browser/ui/webui/print_preview/print_preview_ui_untrusted.h"
#include "jux/jux_pdf.mojom.h"
#include "mojo/public/cpp/bindings/binder_map.h"
#include "mojo/public/cpp/bindings/pending_receiver.h"
#include "mojo/public/cpp/bindings/self_owned_receiver.h"
#endif
#include "mojo/public/cpp/bindings/pending_associated_receiver.h"
#include "third_party/blink/public/common/associated_interfaces/associated_interface_registry.h"
#endif

namespace jux {

namespace {
JuxBrowserClient* g_instance = nullptr;
}

// static
JuxBrowserClient* JuxBrowserClient::Get() {
  return g_instance;
}

JuxBrowserClient::JuxBrowserClient() {
  g_instance = this;
  // Register the process-global network interceptor (its ctor sets the
  // singleton consulted by the throttles and the arm/resolve C API).
  network_interceptor_ = std::make_unique<JuxNetworkInterceptor>();
}

JuxBrowserClient::~JuxBrowserClient() {
  if (g_instance == this) {
    g_instance = nullptr;
  }
}

std::unique_ptr<content::BrowserMainParts>
JuxBrowserClient::CreateBrowserMainParts(bool is_integration_test) {
  auto parts = std::make_unique<JuxBrowserMainParts>();
  browser_main_parts_ = parts.get();
  return parts;
}

std::string JuxBrowserClient::GetAcceptLangs(
    content::BrowserContext* context) {
  return "en-US,en";
}

std::string JuxBrowserClient::GetDefaultDownloadName() {
  return "download";
}

void JuxBrowserClient::AppendExtraCommandLineSwitches(
    base::CommandLine* command_line,
    int child_process_id) {
  // The GPU process decides DirectComposition support from ITS OWN command
  // line (gl::init); content's default child-process forwarding list does
  // not include this switch, so without this hook a browser-side
  // --disable-direct-composition silently does nothing.
  const base::CommandLine& browser_cmd = *base::CommandLine::ForCurrentProcess();
  static const char* const kForwarded[] = {
      "disable-direct-composition",
  };
  for (const char* sw : kForwarded) {
    if (browser_cmd.HasSwitch(sw) && !command_line->HasSwitch(sw)) {
      command_line->AppendSwitch(sw);
    }
  }
}

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
namespace {
// Serves the generated preview PDF bytes to the in-renderer PDF plugin (which
// can't fetch the cross-scheme byte-server). Reads PrintPreviewDataService via
// the untrusted UI's static accessor. One self-owned instance per request.
class JuxPdfDataProviderImpl : public jux::mojom::PdfDataProvider {
 public:
  void GetPreviewPdf(const std::string& data_path,
                     GetPreviewPdfCallback callback) override {
    scoped_refptr<base::RefCountedMemory> data =
        printing::PrintPreviewUIUntrusted::GetPrintPreviewDataForTest(data_path);
    std::vector<uint8_t> out;
    if (data && data->size() > 0) {
      base::span<const uint8_t> bytes(*data);
      out.assign(bytes.begin(), bytes.end());
    }
    std::move(callback).Run(std::move(out));
  }
};
}  // namespace
#endif  // BUILDFLAG(ENABLE_PRINT_PREVIEW)

void JuxBrowserClient::RegisterBrowserInterfaceBindersForFrame(
    content::RenderFrameHost* render_frame_host,
    mojo::BinderMapWithContext<content::RenderFrameHost*>* map) {
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  map->Add<jux::mojom::PdfDataProvider>(base::BindRepeating(
      [](content::RenderFrameHost*,
         mojo::PendingReceiver<jux::mojom::PdfDataProvider> receiver) {
        mojo::MakeSelfOwnedReceiver(std::make_unique<JuxPdfDataProviderImpl>(),
                                    std::move(receiver));
      }));
#endif
}

void JuxBrowserClient::RegisterNonNetworkSubresourceURLLoaderFactories(
    int render_process_id,
    int render_frame_id,
    const std::optional<url::Origin>& request_initiator_origin,
    NonNetworkURLLoaderFactoryMap* factories) {
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  // The print-preview PDF viewer (chrome://print/pdf/index_print.html, a subframe)
  // embeds <embed src="chrome-untrusted://print/<id>/<page>/print.pdf">. Our
  // in-renderer PDF plugin self-fetches that URL, but a chrome:// frame has no
  // factory for the chrome-untrusted:// scheme → ERR_DISALLOWED_URL_SCHEME. Add
  // one for any chrome://print frame so the untrusted byte-server (registered as a
  // WebUIConfig) serves the generated PDF bytes.
  content::RenderFrameHost* rfh =
      content::RenderFrameHost::FromID(render_process_id, render_frame_id);
  if (rfh) {
    const GURL& url = rfh->GetLastCommittedURL();
    if (url.SchemeIs(content::kChromeUIScheme) &&
        url.host() == "print") {
      factories->emplace(
          content::kChromeUIUntrustedScheme,
          content::CreateWebUIURLLoaderFactory(
              rfh, content::kChromeUIUntrustedScheme,
              /*allowed_hosts=*/{"print"}));
    }
  }
#endif
}

void JuxBrowserClient::RegisterAssociatedInterfaceBindersForRenderFrameHost(
    content::RenderFrameHost& render_frame_host,
    blink::AssociatedInterfaceRegistry& associated_registry) {
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  // window.print()/Ctrl+P in a frame → renderer PrintRenderFrameHelper →
  // (this) PrintManagerHost → per-WebContents PrintViewManager → the
  // PrintPreviewDialogController, which opens the off-screen chrome://print view.
  associated_registry.AddInterface<printing::mojom::PrintManagerHost>(
      base::BindRepeating(
          [](content::RenderFrameHost* rfh,
             mojo::PendingAssociatedReceiver<printing::mojom::PrintManagerHost>
                 receiver) {
            printing::PrintViewManager::BindPrintManagerHost(std::move(receiver),
                                                             rfh);
          },
          &render_frame_host));
#endif  // BUILDFLAG(ENABLE_PRINT_PREVIEW)
}

namespace {
// Frozen "reduced" UA product token for the pinned Chromium milestone. Chrome
// freezes the minor/build/patch to ".0.0.0", so only the major version varies.
// Keep the major in sync with the checkout (147.x) and with
// BlinkPage.DEFAULT_USER_AGENT on the Java side.
constexpr char kJuxUserAgentProduct[] = "Chrome/147.0.0.0";
}  // namespace

std::string JuxBrowserClient::GetUserAgent() {
  // Honor an explicit --user-agent command-line switch if present (parity with
  // content shell), otherwise build the frozen platform UA from our product.
  std::optional<std::string> cli_ua =
      embedder_support::GetUserAgentFromCommandLine();
  if (cli_ua.has_value()) {
    return cli_ua.value();
  }
  return embedder_support::BuildUnifiedPlatformUserAgentFromProduct(
      kJuxUserAgentProduct);
}

blink::UserAgentMetadata JuxBrowserClient::GetUserAgentMetadata() {
  // Brand/platform client-hint values consistent with GetUserAgent().
  return embedder_support::GetUserAgentMetadata();
}

void JuxBrowserClient::WillCreateURLLoaderFactory(
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
    scoped_refptr<base::SequencedTaskRunner> navigation_response_task_runner) {
  // Only insert the proxy when an interceptor is actually armed — otherwise this
  // path stays a zero-cost no-op for every request.
  JuxNetworkInterceptor* interceptor = JuxNetworkInterceptor::GetInstance();
  if (!interceptor || !interceptor->armed()) {
    return;
  }
  // Append() hands us the renderer-facing receiver + a remote to the real
  // factory; the proxy is self-owned.
  auto [proxy_receiver, target_remote] = factory_builder.Append();
  JuxProxyingURLLoaderFactory::CreateProxy(std::move(proxy_receiver),
                                           std::move(target_remote));
}

std::vector<std::unique_ptr<blink::URLLoaderThrottle>>
JuxBrowserClient::CreateURLLoaderThrottles(
    const network::ResourceRequest& request,
    content::BrowserContext* browser_context,
    const base::RepeatingCallback<content::WebContents*()>& wc_getter,
    content::NavigationUIData* navigation_ui_data,
    content::FrameTreeNodeId frame_tree_node_id,
    std::optional<int64_t> navigation_id) {
  std::vector<std::unique_ptr<blink::URLLoaderThrottle>> throttles;
  // One throttle per request; it only defers when armed + matched (cheap no-op
  // otherwise), so it's safe to add unconditionally.
  throttles.push_back(std::make_unique<JuxUrlLoaderThrottle>());
  return throttles;
}

namespace {
// Maps a net auth scheme string to the Java AuthScheme wire code.
int MapAuthScheme(const std::string& scheme) {
  if (scheme == "basic") return 0;
  if (scheme == "digest") return 1;
  if (scheme == "ntlm") return 2;
  if (scheme == "negotiate") return 3;
  return -1;  // UNKNOWN
}
}  // namespace

std::unique_ptr<content::LoginDelegate> JuxBrowserClient::CreateLoginDelegate(
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
    content::LoginDelegate::LoginAuthRequiredCallback auth_required_callback) {
  static std::atomic<uint32_t> next_auth_id{1};
  uint32_t auth_id = next_auth_id.fetch_add(1, std::memory_order_relaxed);
  return std::make_unique<JuxLoginDelegate>(
      auth_id, MapAuthScheme(auth_info.scheme), auth_info.is_proxy,
      auth_info.challenger.host(), auth_info.realm,
      std::move(auth_required_callback));
}

}  // namespace jux
