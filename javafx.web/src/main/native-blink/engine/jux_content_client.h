// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxContentClient — shared across all process types.
//
// Provides resource bundle access, user agent string, and origin trial
// policy. This is the ContentClient implementation used by all jux
// engine processes (browser, renderer, GPU, utility).

#ifndef JUX_CONTENT_CLIENT_H_
#define JUX_CONTENT_CLIENT_H_

#include <string>
#include <string_view>

#include "content/public/common/content_client.h"

namespace jux {

class JuxContentClient : public content::ContentClient {
 public:
  JuxContentClient();
  ~JuxContentClient() override;

  JuxContentClient(const JuxContentClient&) = delete;
  JuxContentClient& operator=(const JuxContentClient&) = delete;

  // content::ContentClient overrides:
  std::u16string GetLocalizedString(int message_id) override;
  std::string_view GetDataResource(
      int resource_id,
      ui::ResourceScaleFactor scale_factor) override;
  base::RefCountedMemory* GetDataResourceBytes(int resource_id) override;
  std::string GetDataResourceString(int resource_id) override;
  gfx::Image& GetNativeImageNamed(int resource_id) override;
};

}  // namespace jux

#endif  // JUX_CONTENT_CLIENT_H_
