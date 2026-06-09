// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// Implementation of the shared memory IPC channel.

#ifdef UNSAFE_BUFFERS_BUILD
#pragma allow_unsafe_buffers
#endif

#include "jux/jux_ipc.h"

#include <atomic>
#include <cstring>

#include "base/logging.h"

namespace jux {
namespace ipc {

namespace {

// Reads a little-endian uint32_t from the given offset.
uint32_t ReadLE32(const uint8_t* data, size_t offset) {
  uint32_t value;
  memcpy(&value, data + offset, sizeof(value));
  return value;
}

// Reads a little-endian uint64_t from the given offset.
uint64_t ReadLE64(const uint8_t* data, size_t offset) {
  uint64_t value;
  memcpy(&value, data + offset, sizeof(value));
  return value;
}

}  // namespace

SharedMemoryChannel::SharedMemoryChannel() = default;
SharedMemoryChannel::~SharedMemoryChannel() = default;

// static
std::unique_ptr<SharedMemoryChannel> SharedMemoryChannel::Open(
    const base::FilePath& path) {
  auto channel = std::unique_ptr<SharedMemoryChannel>(
      new SharedMemoryChannel());

  // Open the file for reading and writing.
  base::File file(path, base::File::FLAG_OPEN | base::File::FLAG_READ |
                            base::File::FLAG_WRITE);
  if (!file.IsValid()) {
    LOG(ERROR) << "Failed to open shared memory file: " << path;
    return nullptr;
  }

  // Memory-map the file. We need read-write access for heartbeat and
  // event ring buffer writes.
  if (!channel->mmap_.Initialize(std::move(file),
                                  base::MemoryMappedFile::READ_WRITE)) {
    LOG(ERROR) << "Failed to mmap shared memory file: " << path;
    return nullptr;
  }

  channel->data_ = const_cast<uint8_t*>(channel->mmap_.data());
  channel->size_ = channel->mmap_.length();

  if (channel->size_ < kHeaderSize + kHeartbeatSize) {
    LOG(ERROR) << "Shared memory file too small: " << channel->size_
               << " bytes (minimum " << kHeaderSize + kHeartbeatSize << ")";
    return nullptr;
  }

  // Cache header fields.
  const uint8_t* hdr = channel->data_.get();
  channel->window_id_ = ReadLE32(hdr, kOffWindowId);
  channel->heartbeat_offset_ = kHeaderSize;
  channel->cmd_buf_offset_ =
      static_cast<size_t>(ReadLE64(hdr, kOffCmdBufOffset));
  channel->cmd_buf_size_ =
      static_cast<size_t>(ReadLE64(hdr, kOffCmdBufSize));
  channel->evt_buf_offset_ =
      static_cast<size_t>(ReadLE64(hdr, kOffEvtBufOffset));
  channel->evt_buf_size_ =
      static_cast<size_t>(ReadLE64(hdr, kOffEvtBufSize));
  channel->journal_offset_ =
      static_cast<size_t>(ReadLE64(hdr, kOffJournalOffset));
  channel->journal_size_ =
      static_cast<size_t>(ReadLE64(hdr, kOffJournalSize));
  channel->data_offset_ =
      static_cast<size_t>(ReadLE64(hdr, kOffDataOffset));
  channel->data_size_ =
      static_cast<size_t>(ReadLE64(hdr, kOffDataSize));

  // Strict header validation. A corrupt/mismatched magic or version, or a
  // region whose [offset, offset+size) escapes the mapping, would let every
  // later ring read/write run out of bounds across the whole shared mapping.
  // Reject rather than proceed on a warning (magic/version were previously
  // only LOG(WARNING) and region bounds were never checked at all).
  if (!channel->ValidateHeader()) {
    return nullptr;
  }
  const size_t prefix = kHeaderSize + kHeartbeatSize;
  auto region_ok = [&](size_t off, size_t sz) -> bool {
    if (sz == 0) return true;                  // absent (e.g. optional data buf)
    if (off < prefix) return false;            // would overlap header/heartbeat
    // off <= size_ && sz <= size_ - off is overflow-safe (no off+sz wrap).
    return off <= channel->size_ && sz <= channel->size_ - off;
  };
  if (!region_ok(channel->cmd_buf_offset_, channel->cmd_buf_size_) ||
      !region_ok(channel->evt_buf_offset_, channel->evt_buf_size_) ||
      !region_ok(channel->journal_offset_, channel->journal_size_) ||
      !region_ok(channel->data_offset_, channel->data_size_)) {
    LOG(ERROR) << "Shared memory region out of bounds (mapping=" << channel->size_
               << " B)";
    return nullptr;
  }
  if (channel->cmd_buf_size_ == 0 || channel->evt_buf_size_ == 0) {
    LOG(ERROR) << "Shared memory command/event ring has zero size";
    return nullptr;
  }

  return channel;
}

bool SharedMemoryChannel::ValidateHeader() const {
  uint32_t magic = ReadLE32(data_.get(), kOffMagic);
  if (magic != kMagic) {
    LOG(ERROR) << "Invalid magic: 0x" << std::hex << magic
               << " (expected 0x" << kMagic << ")";
    return false;
  }
  uint32_t version = ReadLE32(data_.get(), kOffVersion);
  if (version != kVersion) {
    LOG(ERROR) << "Unsupported version: " << version
               << " (expected " << kVersion << ")";
    return false;
  }
  return true;
}

// ── Region accessors ─────────────────────────────────────────────────

base::span<const uint8_t> SharedMemoryChannel::CommandBuffer() const {
  return base::span<const uint8_t>(data_.get() + cmd_buf_offset_, cmd_buf_size_);
}

base::span<uint8_t> SharedMemoryChannel::CommandBufferMut() {
  return base::span<uint8_t>(data_.get() + cmd_buf_offset_, cmd_buf_size_);
}

base::span<const uint8_t> SharedMemoryChannel::EventBuffer() const {
  return base::span<const uint8_t>(data_.get() + evt_buf_offset_, evt_buf_size_);
}

base::span<uint8_t> SharedMemoryChannel::EventBufferMut() {
  return base::span<uint8_t>(data_.get() + evt_buf_offset_, evt_buf_size_);
}

base::span<uint8_t> SharedMemoryChannel::DataBufferMut() {
  if (data_offset_ == 0 || data_size_ == 0 ||
      data_offset_ + data_size_ > size_) {
    return base::span<uint8_t>();
  }
  return base::span<uint8_t>(data_.get() + data_offset_, data_size_);
}

// ── Heartbeat operations ─────────────────────────────────────────────

int64_t SharedMemoryChannel::ReadJavaHeartbeat() const {
  const auto* ptr = reinterpret_cast<const std::atomic<int64_t>*>(
      data_.get() + heartbeat_offset_ + kOffJavaHeartbeat);
  return ptr->load(std::memory_order_acquire);
}

void SharedMemoryChannel::WriteEngineHeartbeat(int64_t timestamp) {
  auto* ptr = reinterpret_cast<std::atomic<int64_t>*>(
      data_.get() + heartbeat_offset_ + kOffEngineHeartbeat);
  ptr->store(timestamp, std::memory_order_release);
}

void SharedMemoryChannel::SetEngineState(int32_t state) {
  auto* ptr = reinterpret_cast<std::atomic<int32_t>*>(
      data_.get() + heartbeat_offset_ + kOffEngineState);
  ptr->store(state, std::memory_order_release);
}

int32_t SharedMemoryChannel::ReadEngineState() const {
  const auto* ptr = reinterpret_cast<const std::atomic<int32_t>*>(
      data_.get() + heartbeat_offset_ + kOffEngineState);
  return ptr->load(std::memory_order_acquire);
}

int32_t SharedMemoryChannel::ReadFrameReadingSlot() const {
  // Header field (offset from the channel base, NOT the heartbeat region).
  const auto* ptr = reinterpret_cast<const std::atomic<int32_t>*>(
      data_.get() + kOffFrameReadingSlot);
  return ptr->load(std::memory_order_acquire);
}

}  // namespace ipc
}  // namespace jux
