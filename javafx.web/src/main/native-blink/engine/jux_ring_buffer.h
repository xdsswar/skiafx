// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// Lock-free SPSC ring buffer for IPC command/event transport.
//
// Layout of each ring buffer region:
//   [0..8)     write_pos  (AtomicI64, cache line 0 — written by producer)
//   [64..72)   read_pos   (AtomicI64, cache line 1 — written by consumer)
//   [128..136) capacity   (int64, power of 2, immutable after init)
//   [136..144) slot_size  (int64, fixed 256, immutable after init)
//   [144+)     slots[capacity] — each slot: [type:4][len:4][payload:248]
//
// Write/read positions are on separate cache lines to prevent false sharing.

#ifndef JUX_RING_BUFFER_H_
#define JUX_RING_BUFFER_H_

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "base/containers/span.h"
#include "base/memory/raw_ptr.h"

namespace jux {

// ── Ring buffer header constants ─────────────────────────────────────

inline constexpr size_t kRbOffWritePos = 0;
inline constexpr size_t kRbOffReadPos = 64;
inline constexpr size_t kRbOffCapacity = 128;
inline constexpr size_t kRbOffSlotSize = 136;
inline constexpr size_t kRbSlotsStart = 144;

// ── Slot layout constants ────────────────────────────────────────────

inline constexpr size_t kSlotTypeOffset = 0;
inline constexpr size_t kSlotLenOffset = 4;
inline constexpr size_t kSlotPayloadOffset = 8;
inline constexpr size_t kMaxPayload = 248;  // kSlotSize(256) - 8

// ── Multi-slot event framing (engine→Java) ───────────────────────────
//
// A payload larger than one slot is split across consecutive slots. This is
// safe ONLY because the event ring is single-producer (one writer thread), so
// the slots stay contiguous — nothing interleaves between them. The FIRST slot
// sets kEvtContFlag in its type and carries [windowId:4][totalUserLen:4][chunk0];
// each following kEvtContinuation slot carries [windowId:4][chunkK]. Java's
// EventRingBuffer reassembles them. Mirrors MemoryLayout.java (Java canonical).
inline constexpr uint32_t kEvtContFlag = 0x80000000u;     // high bit of slot type
inline constexpr uint32_t kEvtContinuation = 0x0000FFFEu;  // continuation slot type

// =========================================================================
// CommandSlot — a command read from the ring buffer
// =========================================================================

struct CommandSlot {
  CommandSlot();
  ~CommandSlot();
  CommandSlot(CommandSlot&&);
  CommandSlot& operator=(CommandSlot&&);

  uint32_t cmd_type = 0;
  uint32_t payload_len = 0;
  std::vector<uint8_t> payload;

  // ── Payload reading helpers ──────────────────────────────────────

  // Reads a little-endian uint32_t from the payload at the given offset.
  uint32_t ReadU32(size_t offset) const;

  // Reads a little-endian double from the payload at the given offset.
  double ReadF64(size_t offset) const;

  // Reads a boolean from the payload at the given offset.
  bool ReadBool(size_t offset) const;

  // Reads a UTF-8 string from the payload at the given offset and length.
  std::string ReadString(size_t offset, size_t len) const;

  // ── Convenience methods for common payload formats ───────────────

  // Reads [windowId:4][strLen:4][utf8:N] — returns the string.
  std::string ReadStringPayload() const;

  // Reads [windowId:4][d1:8][d2:8] — returns (d1, d2).
  std::pair<double, double> ReadTwoDoublesPayload() const;

  // Reads [windowId:4][flag:1] — returns the boolean.
  bool ReadBoolPayload() const;

  // Reads [windowId:4][value:4] — returns the uint32.
  uint32_t ReadU32Payload() const;

  // Reads [windowId:4][value:8] — returns the double.
  double ReadDoublePayload() const;
};

// =========================================================================
// CommandReader — reads Java commands from the command ring buffer
// =========================================================================

// Single-producer single-consumer reader. Java (producer) writes commands
// and advances write_pos. C++ (consumer) reads commands and advances
// read_pos. No locks — just atomic load/store with acquire/release.
class CommandReader {
 public:
  // Creates a reader over the given ring buffer region in shared memory.
  explicit CommandReader(base::span<const uint8_t> region);

  // Polls for the next command. Returns std::nullopt if empty.
  std::optional<CommandSlot> Poll();

  // Advances the read position after processing a command.
  // Must be called after Poll() returns a value.
  void Advance();

  // Returns the number of unread commands.
  int64_t Available() const;

 private:
  raw_ptr<uint8_t> base_;
  size_t region_size_;
  int64_t capacity_;
};

// =========================================================================
// EventWriter — writes events to the event ring buffer for Java
// =========================================================================

// Single-producer single-consumer writer. C++ (producer) writes events
// and advances write_pos. Java (consumer) reads events and advances
// read_pos. Thread-safe — can be called from any thread (browser thread
// callbacks, heartbeat thread).
class EventWriter {
 public:
  // Creates a writer over the given ring buffer region in shared memory.
  explicit EventWriter(base::span<const uint8_t> region);

  // Writes an event to the next available slot.
  // Returns true if written, false if buffer is full.
  bool WriteEvent(uint32_t event_type, uint32_t window_id,
                  base::span<const uint8_t> payload);

  // Convenience overload for events with no extra payload.
  bool WriteEvent(uint32_t event_type, uint32_t window_id);

  // Writes an event whose payload may exceed one slot. Small payloads take the
  // normal single-slot path; larger ones are split across a header slot
  // (kEvtContFlag) plus kEvtContinuation slots and published with a single
  // write_pos release so Java sees all slots at once. Returns false if the ring
  // lacks room for the whole message (it is never written partially).
  // Caller must be the single producer thread (browser thread).
  bool WriteEventLarge(uint32_t event_type, uint32_t window_id,
                       base::span<const uint8_t> payload);

 private:
  raw_ptr<uint8_t> base_;
  size_t region_size_;
  int64_t capacity_;
};

}  // namespace jux

#endif  // JUX_RING_BUFFER_H_
