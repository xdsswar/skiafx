// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// Implementation of the SPSC ring buffer for IPC transport.

#ifdef UNSAFE_BUFFERS_BUILD
#pragma allow_unsafe_buffers
#endif

#include "jux/jux_ring_buffer.h"

#include <atomic>
#include <cstring>

#include "base/logging.h"
#include "jux/jux_ipc.h"  // for ipc::kSlotSize

namespace jux {

namespace {

// Reads a little-endian int64_t from the buffer at the given offset.
int64_t ReadI64(const uint8_t* buf, size_t offset) {
  int64_t value;
  memcpy(&value, buf + offset, sizeof(value));
  return value;
}

// Reads a little-endian uint32_t from the buffer at the given offset.
uint32_t ReadU32(const uint8_t* buf, size_t offset) {
  uint32_t value;
  memcpy(&value, buf + offset, sizeof(value));
  return value;
}

// Writes a little-endian uint32_t to the buffer at the given offset.
void WriteU32(uint8_t* buf, size_t offset, uint32_t value) {
  memcpy(buf + offset, &value, sizeof(value));
}

// Returns an atomic reference to the int64 at the given pointer.
std::atomic<int64_t>* AtomicI64At(uint8_t* ptr) {
  return reinterpret_cast<std::atomic<int64_t>*>(ptr);
}

}  // namespace

// =========================================================================
// CommandSlot
// =========================================================================

CommandSlot::CommandSlot() = default;
CommandSlot::~CommandSlot() = default;
CommandSlot::CommandSlot(CommandSlot&&) = default;
CommandSlot& CommandSlot::operator=(CommandSlot&&) = default;

uint32_t CommandSlot::ReadU32(size_t offset) const {
  if (offset + 4 > payload.size()) return 0;
  uint32_t value;
  memcpy(&value, payload.data() + offset, sizeof(value));
  return value;
}

double CommandSlot::ReadF64(size_t offset) const {
  if (offset + 8 > payload.size()) return 0.0;
  double value;
  memcpy(&value, payload.data() + offset, sizeof(value));
  return value;
}

bool CommandSlot::ReadBool(size_t offset) const {
  if (offset >= payload.size()) return false;
  return payload[offset] != 0;
}

std::string CommandSlot::ReadString(size_t offset, size_t len) const {
  if (offset + len > payload.size()) return {};
  return std::string(reinterpret_cast<const char*>(payload.data() + offset),
                     len);
}

std::string CommandSlot::ReadStringPayload() const {
  uint32_t str_len = ReadU32(4);
  return ReadString(8, str_len);
}

std::pair<double, double> CommandSlot::ReadTwoDoublesPayload() const {
  return {ReadF64(4), ReadF64(12)};
}

bool CommandSlot::ReadBoolPayload() const {
  return ReadBool(4);
}

uint32_t CommandSlot::ReadU32Payload() const {
  return ReadU32(4);
}

double CommandSlot::ReadDoublePayload() const {
  return ReadF64(4);
}

// =========================================================================
// CommandReader
// =========================================================================

CommandReader::CommandReader(base::span<const uint8_t> region)
    : base_(const_cast<uint8_t*>(region.data())),
      region_size_(region.size()),
      capacity_(ReadI64(region.data(), kRbOffCapacity)) {}

std::optional<CommandSlot> CommandReader::Poll() {
  uint8_t* b = base_.get();
  int64_t write_pos =
      AtomicI64At(b + kRbOffWritePos)->load(std::memory_order_acquire);
  int64_t read_pos =
      AtomicI64At(b + kRbOffReadPos)->load(std::memory_order_acquire);

  if (read_pos >= write_pos) {
    return std::nullopt;  // Buffer empty
  }

  // Calculate slot offset using bitmask (capacity is power of 2).
  size_t slot_index = static_cast<size_t>(read_pos & (capacity_ - 1));
  size_t slot_offset = kRbSlotsStart + slot_index * ipc::kSlotSize;

  CommandSlot slot;
  slot.cmd_type = ReadU32(b, slot_offset + kSlotTypeOffset);
  slot.payload_len = ReadU32(b, slot_offset + kSlotLenOffset);

  // Copy payload from the slot.
  size_t copy_len = std::min(static_cast<size_t>(slot.payload_len), kMaxPayload);
  slot.payload.resize(copy_len);
  if (copy_len > 0) {
    memcpy(slot.payload.data(), b + slot_offset + kSlotPayloadOffset,
           copy_len);
  }

  return slot;
}

void CommandReader::Advance() {
  uint8_t* b = base_.get();
  auto* read_pos_ptr = AtomicI64At(b + kRbOffReadPos);
  int64_t current = read_pos_ptr->load(std::memory_order_acquire);
  read_pos_ptr->store(current + 1, std::memory_order_release);
}

int64_t CommandReader::Available() const {
  uint8_t* b = base_.get();
  int64_t write_pos =
      AtomicI64At(b + kRbOffWritePos)->load(std::memory_order_acquire);
  int64_t read_pos =
      AtomicI64At(b + kRbOffReadPos)->load(std::memory_order_acquire);
  return write_pos - read_pos;
}

// =========================================================================
// EventWriter
// =========================================================================

EventWriter::EventWriter(base::span<const uint8_t> region)
    : base_(const_cast<uint8_t*>(region.data())),
      region_size_(region.size()),
      capacity_(ReadI64(region.data(), kRbOffCapacity)) {}

bool EventWriter::WriteEvent(uint32_t event_type, uint32_t window_id,
                             base::span<const uint8_t> payload) {
  uint8_t* b = base_.get();
  auto* write_pos_ptr = AtomicI64At(b + kRbOffWritePos);
  auto* read_pos_ptr = AtomicI64At(b + kRbOffReadPos);

  int64_t write_pos = write_pos_ptr->load(std::memory_order_acquire);
  int64_t read_pos = read_pos_ptr->load(std::memory_order_acquire);

  // Check if buffer is full.
  if (write_pos - read_pos >= capacity_) {
    LOG(WARNING) << "Event ring buffer full — dropping event type 0x"
                 << std::hex << event_type;
    return false;
  }

  // Calculate slot offset.
  size_t slot_index = static_cast<size_t>(write_pos & (capacity_ - 1));
  size_t slot_offset = kRbSlotsStart + slot_index * ipc::kSlotSize;
  uint8_t* slot = b + slot_offset;

  // Write slot header: [type:4][len:4].
  // The declared length MUST equal the bytes actually written (4 for window_id
  // + the COPIED payload, which is clamped to the slot). Declaring the full
  // payload.size() when it exceeds the slot left the reader trusting a length
  // beyond the valid bytes — harmless today (Java re-clamps to the slot) but a
  // latent desync/garbage-read for any future >slot event. Clamp both together.
  size_t copy_len = std::min(payload.size(), kMaxPayload - 4);
  uint32_t total_len = 4 + static_cast<uint32_t>(copy_len);
  WriteU32(slot, kSlotTypeOffset, event_type);
  WriteU32(slot, kSlotLenOffset, total_len);

  // Write window_id as the first 4 bytes of the payload area.
  WriteU32(slot, kSlotPayloadOffset, window_id);

  // Write user payload after the window_id.
  if (copy_len > 0) {
    memcpy(slot + kSlotPayloadOffset + 4, payload.data(), copy_len);
  }

  // Advance write position (release fence makes slot visible to consumer).
  write_pos_ptr->store(write_pos + 1, std::memory_order_release);

  return true;
}

bool EventWriter::WriteEventLarge(uint32_t event_type, uint32_t window_id,
                                  base::span<const uint8_t> payload) {
  // Fits one slot (windowId + payload) → normal single-slot path.
  if (payload.size() + 4 <= kMaxPayload) {
    return WriteEvent(event_type, window_id, payload);
  }

  uint8_t* b = base_.get();
  auto* write_pos_ptr = AtomicI64At(b + kRbOffWritePos);
  auto* read_pos_ptr = AtomicI64At(b + kRbOffReadPos);

  // Per-slot user-byte capacities: the header slot also stores windowId(4) +
  // totalLen(4); continuation slots store windowId(4).
  const size_t kFirstCap = kMaxPayload - 8;  // 240
  const size_t kContCap = kMaxPayload - 4;   // 244
  const size_t total = payload.size();
  const size_t after_first = total - kFirstCap;
  const int64_t needed =
      1 + static_cast<int64_t>((after_first + kContCap - 1) / kContCap);

  int64_t write_pos = write_pos_ptr->load(std::memory_order_acquire);
  int64_t read_pos = read_pos_ptr->load(std::memory_order_acquire);
  if (write_pos - read_pos + needed > capacity_) {
    LOG(WARNING) << "Event ring full — dropping large event type 0x" << std::hex
                 << event_type << std::dec << " (" << needed << " slots)";
    return false;
  }

  // Header slot: [type|CONT_FLAG][len][windowId][totalLen][chunk0].
  {
    size_t idx = static_cast<size_t>(write_pos & (capacity_ - 1));
    uint8_t* slot = b + kRbSlotsStart + idx * ipc::kSlotSize;
    size_t chunk0 = std::min(total, kFirstCap);
    WriteU32(slot, kSlotTypeOffset, event_type | kEvtContFlag);
    WriteU32(slot, kSlotLenOffset, static_cast<uint32_t>(8 + chunk0));
    WriteU32(slot, kSlotPayloadOffset, window_id);
    WriteU32(slot, kSlotPayloadOffset + 4, static_cast<uint32_t>(total));
    memcpy(slot + kSlotPayloadOffset + 8, payload.data(), chunk0);
  }

  // Continuation slots: [CONTINUATION][len][windowId][chunkK].
  size_t off = kFirstCap;
  for (int64_t i = 1; i < needed; ++i) {
    size_t idx = static_cast<size_t>((write_pos + i) & (capacity_ - 1));
    uint8_t* slot = b + kRbSlotsStart + idx * ipc::kSlotSize;
    size_t chunk = std::min(total - off, kContCap);
    WriteU32(slot, kSlotTypeOffset, kEvtContinuation);
    WriteU32(slot, kSlotLenOffset, static_cast<uint32_t>(4 + chunk));
    WriteU32(slot, kSlotPayloadOffset, window_id);
    memcpy(slot + kSlotPayloadOffset + 4, payload.data() + off, chunk);
    off += chunk;
  }

  // Publish all slots at once so the consumer sees them atomically (release
  // pairs with the reader's acquire load of write_pos).
  write_pos_ptr->store(write_pos + needed, std::memory_order_release);
  return true;
}

bool EventWriter::WriteEvent(uint32_t event_type, uint32_t window_id) {
  return WriteEvent(event_type, window_id, base::span<const uint8_t>());
}

}  // namespace jux
