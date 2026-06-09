// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxDownloadManagerDelegate implementation.

#ifdef UNSAFE_BUFFERS_BUILD
#pragma allow_unsafe_buffers
#endif

#include "jux/jux_download_manager_delegate.h"

#include <cstring>
#include <utility>
#include <vector>

#include "components/download/public/common/download_target_info.h"
#include "jux/jux_event_types.h"
#include "jux/jux_ipc.h"
#include "jux/jux_ring_buffer.h"

namespace jux {

// Event transport globals (defined in jux_command_dispatch.cc), shared with the
// other browser-side delegates (permissions, login).
extern EventWriter* g_callback_evt_writer;
extern ipc::SharedMemoryChannel* g_callback_channel;

namespace {

JuxDownloadManagerDelegate* g_instance = nullptr;

// content::DownloadItem::DownloadState → Java DownloadState wire code.
// content: IN_PROGRESS=0, COMPLETE=1, CANCELLED=2, INTERRUPTED=3.
// Java DownloadState.fromWire: IN_PROGRESS(1), COMPLETED(2), CANCELLED(3),
// INTERRUPTED(4) → wire = content_state + 1.
int ToWireState(download::DownloadItem::DownloadState s) {
  return static_cast<int>(s) + 1;
}

bool IsTerminal(download::DownloadItem::DownloadState s) {
  return s == download::DownloadItem::COMPLETE ||
         s == download::DownloadItem::CANCELLED ||
         s == download::DownloadItem::INTERRUPTED;
}

void PutU32(std::vector<uint8_t>& b, uint32_t v) {
  size_t off = b.size();
  b.resize(off + 4);
  std::memcpy(b.data() + off, &v, 4);
}

void PutU64(std::vector<uint8_t>& b, uint64_t v) {
  size_t off = b.size();
  b.resize(off + 8);
  std::memcpy(b.data() + off, &v, 8);
}

void PutLenStr(std::vector<uint8_t>& b, const std::string& s) {
  PutU32(b, static_cast<uint32_t>(s.size()));
  if (!s.empty()) {
    size_t off = b.size();
    b.resize(off + s.size());
    std::memcpy(b.data() + off, s.data(), s.size());
  }
}

void Emit(uint32_t event_type, const std::vector<uint8_t>& p) {
  if (!g_callback_evt_writer || !g_callback_channel) {
    return;
  }
  g_callback_evt_writer->WriteEvent(
      event_type, g_callback_channel->window_id(),
      base::span<const uint8_t>(p.data(), p.size()));
}

}  // namespace

JuxDownloadManagerDelegate::Pending::Pending() = default;
JuxDownloadManagerDelegate::Pending::~Pending() = default;
JuxDownloadManagerDelegate::Pending::Pending(Pending&&) = default;
JuxDownloadManagerDelegate::Pending&
JuxDownloadManagerDelegate::Pending::operator=(Pending&&) = default;

JuxDownloadManagerDelegate::JuxDownloadManagerDelegate() {
  g_instance = this;
}

JuxDownloadManagerDelegate::~JuxDownloadManagerDelegate() {
  // Release any un-answered target callback (cancels), and stop observing.
  for (auto& entry : pending_) {
    Pending& p = entry.second;
    if (p.item) {
      p.item->RemoveObserver(this);
    }
    if (p.callback) {
      // Empty target_path => cancel; never leave the renderer hanging.
      std::move(p.callback).Run(download::DownloadTargetInfo());
    }
  }
  pending_.clear();
  by_item_.clear();
  if (g_instance == this) {
    g_instance = nullptr;
  }
}

// static
JuxDownloadManagerDelegate* JuxDownloadManagerDelegate::GetInstance() {
  return g_instance;
}

void JuxDownloadManagerDelegate::GetNextId(
    content::DownloadIdCallback callback) {
  // Chromium's internal download id (distinct from our wire downloadId).
  static uint32_t next_chromium_id = download::DownloadItem::kInvalidId + 1;
  std::move(callback).Run(next_chromium_id++);
}

bool JuxDownloadManagerDelegate::DetermineDownloadTarget(
    download::DownloadItem* item,
    download::DownloadTargetCallback* callback) {
  uint32_t id = next_download_id_++;
  Pending p;
  p.callback = std::move(*callback);
  p.item = item;
  by_item_[item] = id;
  pending_.emplace(id, std::move(p));

  // Observe from now so a page-initiated cancel / destruction cleans up the
  // raw_ptr even before the app responds.
  item->AddObserver(this);

  // Payload: [id:4][totalBytes:8][urlLen:4][url][nameLen:4][name][mimeLen:4][mime]
  std::vector<uint8_t> b;
  PutU32(b, id);
  PutU64(b, static_cast<uint64_t>(item->GetTotalBytes()));
  PutLenStr(b, item->GetURL().spec());
  PutLenStr(b, item->GetSuggestedFilename());
  PutLenStr(b, item->GetMimeType());
  Emit(events::kDownloadRequested, b);

  // We will run the callback asynchronously from Respond().
  return true;
}

void JuxDownloadManagerDelegate::Respond(uint32_t download_id,
                                         bool accepted,
                                         const std::string& path) {
  auto it = pending_.find(download_id);
  if (it == pending_.end()) {
    return;
  }
  Pending& p = it->second;
  if (!p.callback) {
    return;  // already answered
  }
  if (accepted && !path.empty()) {
    base::FilePath target = base::FilePath::FromUTF8Unsafe(path);
    download::DownloadTargetInfo info;
    info.target_path = target;
    info.intermediate_path = target;
    std::move(p.callback).Run(std::move(info));
    // Keep the entry + observer for progress/finished reporting.
  } else {
    // Empty target_path cancels the download. content then moves the item to
    // CANCELLED, which OnDownloadUpdated reports as finished.
    std::move(p.callback).Run(download::DownloadTargetInfo());
  }
}

void JuxDownloadManagerDelegate::Cancel(uint32_t download_id) {
  auto it = pending_.find(download_id);
  if (it == pending_.end()) {
    return;
  }
  download::DownloadItem* item = it->second.item;
  if (item) {
    item->Cancel(/*user_cancel=*/true);  // → OnDownloadUpdated(CANCELLED)
  }
}

void JuxDownloadManagerDelegate::OnDownloadUpdated(
    download::DownloadItem* item) {
  auto it = by_item_.find(item);
  if (it == by_item_.end()) {
    return;
  }
  uint32_t id = it->second;
  auto pit = pending_.find(id);
  if (pit == pending_.end()) {
    return;
  }
  Pending& p = pit->second;
  download::DownloadItem::DownloadState state = item->GetState();

  if (IsTerminal(state)) {
    if (!p.finished) {
      FinishAndForget(id, item);
    }
    return;
  }

  // Only report progress once the target has been accepted (callback consumed);
  // before that the item is target-pending and progress is meaningless.
  if (!p.callback && state == download::DownloadItem::IN_PROGRESS) {
    // Payload: [id:4][state:4][received:8][total:8]
    std::vector<uint8_t> b;
    PutU32(b, id);
    PutU32(b, static_cast<uint32_t>(ToWireState(state)));
    PutU64(b, static_cast<uint64_t>(item->GetReceivedBytes()));
    PutU64(b, static_cast<uint64_t>(item->GetTotalBytes()));
    Emit(events::kDownloadProgress, b);
  }
  p.last_state = static_cast<int>(state);
}

void JuxDownloadManagerDelegate::OnDownloadDestroyed(
    download::DownloadItem* item) {
  auto it = by_item_.find(item);
  if (it == by_item_.end()) {
    return;
  }
  uint32_t id = it->second;
  auto pit = pending_.find(id);
  if (pit != pending_.end()) {
    // Item gone before/without a terminal update: run any pending callback as
    // cancel so nothing hangs.
    if (pit->second.callback) {
      std::move(pit->second.callback).Run(download::DownloadTargetInfo());
    }
    pending_.erase(pit);
  }
  by_item_.erase(it);
  // The item is being destroyed; it removes its observers itself.
}

void JuxDownloadManagerDelegate::FinishAndForget(
    uint32_t download_id,
    download::DownloadItem* item) {
  download::DownloadItem::DownloadState state = item->GetState();
  // Payload: [id:4][state:4][pathLen:4][path]
  std::vector<uint8_t> b;
  PutU32(b, download_id);
  PutU32(b, static_cast<uint32_t>(ToWireState(state)));
  PutLenStr(b, item->GetTargetFilePath().AsUTF8Unsafe());
  Emit(events::kDownloadFinished, b);

  item->RemoveObserver(this);
  auto pit = pending_.find(download_id);
  if (pit != pending_.end()) {
    pit->second.finished = true;
    pending_.erase(pit);
  }
  by_item_.erase(item);
}

}  // namespace jux
