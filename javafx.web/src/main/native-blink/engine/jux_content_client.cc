// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxContentClient — resource bundle and localization support.
//
// All methods delegate to the shared ResourceBundle instance, which is
// initialized by JuxMainDelegate::PreSandboxStartup() from jux-engine.pak.

#include "jux/jux_content_client.h"

#include "ui/base/l10n/l10n_util.h"
#include "ui/base/resource/resource_bundle.h"

namespace jux {

JuxContentClient::JuxContentClient() = default;
JuxContentClient::~JuxContentClient() = default;

std::u16string JuxContentClient::GetLocalizedString(int message_id) {
  return l10n_util::GetStringUTF16(message_id);
}

std::string_view JuxContentClient::GetDataResource(
    int resource_id,
    ui::ResourceScaleFactor scale_factor) {
  return ui::ResourceBundle::GetSharedInstance().GetRawDataResourceForScale(
      resource_id, scale_factor);
}

base::RefCountedMemory* JuxContentClient::GetDataResourceBytes(
    int resource_id) {
  return ui::ResourceBundle::GetSharedInstance().LoadDataResourceBytes(
      resource_id);
}

std::string JuxContentClient::GetDataResourceString(int resource_id) {
  return ui::ResourceBundle::GetSharedInstance().LoadDataResourceString(
      resource_id);
}

gfx::Image& JuxContentClient::GetNativeImageNamed(int resource_id) {
  return ui::ResourceBundle::GetSharedInstance().GetNativeImageNamed(
      resource_id);
}

}  // namespace jux
