// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxLoginDelegate — surfaces HTTP/proxy authentication challenges to Java.
//
// Created per challenge by JuxBrowserClient::CreateLoginDelegate. The ctor
// stashes Chromium's LoginAuthRequiredCallback, fires kAuthRequested, and the
// challenge stays open until Java answers via JuxRespondAuth → Respond(), which
// runs the callback with credentials (supply) or std::nullopt (cancel). The
// destructor cancels any unanswered challenge so the load never hangs.

#ifndef JUX_LOGIN_DELEGATE_H_
#define JUX_LOGIN_DELEGATE_H_

#include <cstdint>
#include <string>

#include "content/public/browser/login_delegate.h"

namespace jux {

class JuxLoginDelegate : public content::LoginDelegate {
 public:
  JuxLoginDelegate(uint32_t auth_id, int scheme, bool is_proxy,
                   const std::string& host, const std::string& realm,
                   LoginAuthRequiredCallback callback);
  ~JuxLoginDelegate() override;

  JuxLoginDelegate(const JuxLoginDelegate&) = delete;
  JuxLoginDelegate& operator=(const JuxLoginDelegate&) = delete;

  // Looks up a live delegate by its auth id (browser UI thread).
  static JuxLoginDelegate* GetByAuthId(uint32_t auth_id);

  // Runs the stashed callback with credentials (supplied=true) or cancels.
  void Respond(bool supplied, const std::string& user, const std::string& pass);

 private:
  uint32_t auth_id_;
  LoginAuthRequiredCallback callback_;
};

}  // namespace jux

#endif  // JUX_LOGIN_DELEGATE_H_
