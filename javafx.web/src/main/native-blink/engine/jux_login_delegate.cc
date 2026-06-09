// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxLoginDelegate implementation.

#ifdef UNSAFE_BUFFERS_BUILD
#pragma allow_unsafe_buffers
#endif

#include "jux/jux_login_delegate.h"

#include <map>
#include <optional>
#include <utility>
#include <vector>

#include "base/no_destructor.h"
#include "base/strings/utf_string_conversions.h"
#include "base/synchronization/lock.h"
#include "jux/jux_event_types.h"
#include "jux/jux_ipc.h"
#include "jux/jux_ring_buffer.h"
#include "net/base/auth.h"

namespace jux {

extern EventWriter* g_callback_evt_writer;
extern ipc::SharedMemoryChannel* g_callback_channel;

namespace {

base::Lock& MapLock() {
  static base::NoDestructor<base::Lock> lock;
  return *lock;
}
std::map<uint32_t, JuxLoginDelegate*>& DelegateMap() {
  static base::NoDestructor<std::map<uint32_t, JuxLoginDelegate*>> map;
  return *map;
}

void PutU32(std::vector<uint8_t>& out, uint32_t v) {
  out.push_back(static_cast<uint8_t>(v));
  out.push_back(static_cast<uint8_t>(v >> 8));
  out.push_back(static_cast<uint8_t>(v >> 16));
  out.push_back(static_cast<uint8_t>(v >> 24));
}

}  // namespace

JuxLoginDelegate::JuxLoginDelegate(uint32_t auth_id, int scheme, bool is_proxy,
                                   const std::string& host,
                                   const std::string& realm,
                                   LoginAuthRequiredCallback callback)
    : auth_id_(auth_id), callback_(std::move(callback)) {
  {
    base::AutoLock guard(MapLock());
    DelegateMap()[auth_id_] = this;
  }
  if (g_callback_evt_writer && g_callback_channel) {
    // Payload (after windowId): [authId:4][scheme:4][isProxy:1][hostLen:4][host]
    //                           [realmLen:4][realm]
    std::vector<uint8_t> p;
    PutU32(p, auth_id_);
    PutU32(p, static_cast<uint32_t>(scheme));
    p.push_back(is_proxy ? 1 : 0);
    PutU32(p, static_cast<uint32_t>(host.size()));
    p.insert(p.end(), host.begin(), host.end());
    PutU32(p, static_cast<uint32_t>(realm.size()));
    p.insert(p.end(), realm.begin(), realm.end());
    g_callback_evt_writer->WriteEvent(events::kAuthRequested,
                                      g_callback_channel->window_id(),
                                      base::span<const uint8_t>(p));
  }
}

JuxLoginDelegate::~JuxLoginDelegate() {
  {
    base::AutoLock guard(MapLock());
    auto it = DelegateMap().find(auth_id_);
    if (it != DelegateMap().end() && it->second == this) {
      DelegateMap().erase(it);
    }
  }
  // Cancel any still-pending challenge so the load never hangs.
  if (callback_) {
    std::move(callback_).Run(std::nullopt);
  }
}

JuxLoginDelegate* JuxLoginDelegate::GetByAuthId(uint32_t auth_id) {
  base::AutoLock guard(MapLock());
  auto it = DelegateMap().find(auth_id);
  return it == DelegateMap().end() ? nullptr : it->second;
}

void JuxLoginDelegate::Respond(bool supplied, const std::string& user,
                               const std::string& pass) {
  if (!callback_) {
    return;
  }
  if (supplied) {
    std::move(callback_).Run(net::AuthCredentials(base::UTF8ToUTF16(user),
                                                  base::UTF8ToUTF16(pass)));
  } else {
    std::move(callback_).Run(std::nullopt);
  }
}

}  // namespace jux
