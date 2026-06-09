// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxUrlLoaderThrottle implementation.

#include "jux/jux_url_loader_throttle.h"

#include <string>
#include <vector>

#include "base/task/sequenced_task_runner.h"
#include "jux/jux_network_interceptor.h"
#include "net/base/net_errors.h"
#include "net/http/http_request_headers.h"
#include "services/network/public/cpp/resource_request.h"

namespace jux {

namespace {

// Maps a Chromium RequestDestination to the Java ResourceType wire code.
int MapDestination(network::mojom::RequestDestination d) {
  using D = network::mojom::RequestDestination;
  switch (d) {
    case D::kDocument:
    case D::kIframe:        return 0;  // DOCUMENT
    case D::kStyle:         return 1;  // STYLESHEET
    case D::kScript:
    case D::kWorker:
    case D::kSharedWorker:
    case D::kServiceWorker: return 2;  // SCRIPT
    case D::kImage:         return 3;  // IMAGE
    case D::kFont:          return 4;  // FONT
    case D::kEmpty:         return 6;  // FETCH / XHR
    case D::kAudio:
    case D::kVideo:
    case D::kTrack:         return 7;  // MEDIA
    default:                return 9;  // OTHER
  }
}

}  // namespace

JuxUrlLoaderThrottle::JuxUrlLoaderThrottle() = default;

JuxUrlLoaderThrottle::~JuxUrlLoaderThrottle() {
  // If we're torn down while still deferring (request cancelled, page navigated
  // away, or the loader destroyed before Java's decision arrives), the
  // interceptor still holds our pending_ entry. Complete it so the entry is
  // erased and Java's matching pending request is released — otherwise the
  // interceptor's pending_ map grows unbounded on a long-lived page with an
  // armed interceptor (each abandoned match leaks a WeakPtr + task-runner ref).
  if (deferred_ && intercept_id_ != 0) {
    JuxNetworkInterceptor* interceptor = JuxNetworkInterceptor::GetInstance();
    if (interceptor) {
      interceptor->Complete(intercept_id_, net::ERR_ABORTED);
    }
  }
}

void JuxUrlLoaderThrottle::WillStartRequest(network::ResourceRequest* request,
                                            bool* defer) {
  JuxNetworkInterceptor* interceptor = JuxNetworkInterceptor::GetInstance();
  if (!interceptor || !interceptor->armed() || !request) {
    return;
  }
  std::string url = request->url.spec();
  std::string method = request->method;
  int type = MapDestination(request->destination);
  if (!interceptor->MatchesRequest(url, type, method)) {
    return;  // not for any registered interceptor — pass through untouched
  }

  std::vector<std::string> keys;
  std::vector<std::string> values;
  for (const auto& kv : request->headers.GetHeaderVector()) {
    keys.push_back(kv.key);
    values.push_back(kv.value);
  }

  intercept_id_ = interceptor->Register(
      weak_factory_.GetWeakPtr(),
      base::SequencedTaskRunner::GetCurrentDefault());
  interceptor->FireRequest(intercept_id_, type, method, url, keys, values);
  *defer = true;
  deferred_ = true;
}

void JuxUrlLoaderThrottle::ApplyDecision(uint8_t action) {
  if (!deferred_ || !delegate_) {
    return;
  }
  deferred_ = false;
  if (action == JuxNetworkInterceptor::kActionBlock) {
    delegate_->CancelWithError(net::ERR_BLOCKED_BY_CLIENT);
  } else {
    // proceed (header-edit / redirect / synthetic fall back to proceed until
    // the URLLoaderFactory proxy lands).
    delegate_->Resume();
  }
  JuxNetworkInterceptor* interceptor = JuxNetworkInterceptor::GetInstance();
  if (interceptor) {
    interceptor->Complete(intercept_id_, 0);
  }
}

}  // namespace jux
