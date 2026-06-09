// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxUrlLoaderThrottle — defers matched requests so Java can observe and
// optionally block them. Vended from JuxBrowserClient::CreateURLLoaderThrottles.
//
// SCOPE (m147): see jux_network_interceptor.h — WillStartRequest forbids async
// request modification, so this supports observe + block + proceed only.

#ifndef JUX_URL_LOADER_THROTTLE_H_
#define JUX_URL_LOADER_THROTTLE_H_

#include <cstdint>

#include "base/memory/weak_ptr.h"
#include "third_party/blink/public/common/loader/url_loader_throttle.h"

namespace network {
struct ResourceRequest;
}

namespace jux {

class JuxUrlLoaderThrottle : public blink::URLLoaderThrottle {
 public:
  JuxUrlLoaderThrottle();
  ~JuxUrlLoaderThrottle() override;

  JuxUrlLoaderThrottle(const JuxUrlLoaderThrottle&) = delete;
  JuxUrlLoaderThrottle& operator=(const JuxUrlLoaderThrottle&) = delete;

  // blink::URLLoaderThrottle:
  void WillStartRequest(network::ResourceRequest* request, bool* defer) override;

  // Called on this throttle's sequence when Java answers. action: 0=proceed,
  // 2=block; other actions currently behave as proceed.
  void ApplyDecision(uint8_t action);

 private:
  uint32_t intercept_id_ = 0;
  bool deferred_ = false;
  base::WeakPtrFactory<JuxUrlLoaderThrottle> weak_factory_{this};
};

}  // namespace jux

#endif  // JUX_URL_LOADER_THROTTLE_H_
