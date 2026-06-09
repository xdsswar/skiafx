// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// Shared memory IPC channel between Java and the C++ engine.
//
// The Java side creates a shared memory file (mmap) containing a header,
// heartbeat region, command ring buffer, event ring buffer, journal, and
// overflow data heap. The engine opens this file and communicates with
// Java through lock-free atomic operations.
//
// All constants must exactly match MemoryLayout.java on the Java side.

#ifndef JUX_IPC_H_
#define JUX_IPC_H_

#include <cstdint>
#include <memory>
#include <string>

#include "base/containers/span.h"
#include "base/files/file.h"
#include "base/files/file_path.h"
#include "base/files/memory_mapped_file.h"
#include "base/memory/raw_ptr.h"

namespace jux {
namespace ipc {

// =========================================================================
// Constants — must match MemoryLayout.java exactly
// =========================================================================

// Magic number identifying a valid Jux shared memory file ("JUXF").
inline constexpr uint32_t kMagic = 0x4A555846;

// Current protocol version.
inline constexpr uint32_t kVersion = 1;

// Region sizes (bytes).
inline constexpr size_t kHeaderSize = 256;
inline constexpr size_t kHeartbeatSize = 128;
inline constexpr size_t kCacheLine = 64;
inline constexpr size_t kSlotSize = 256;

// ── Header field offsets ─────────────────────────────────────────────
inline constexpr size_t kOffMagic = 0;
inline constexpr size_t kOffVersion = 4;
inline constexpr size_t kOffWindowId = 8;
inline constexpr size_t kOffCmdBufOffset = 12;
inline constexpr size_t kOffCmdBufSize = 20;
inline constexpr size_t kOffEvtBufOffset = 28;
inline constexpr size_t kOffEvtBufSize = 36;
inline constexpr size_t kOffJournalOffset = 44;
inline constexpr size_t kOffJournalSize = 52;
inline constexpr size_t kOffDataOffset = 60;
inline constexpr size_t kOffDataSize = 68;
// Frame-slot handshake (M13): the main data-region frame slot Java is currently
// reading (-1 = none). Cache-line aligned in the header tail; release/acquire
// atomic int. Must match MemoryLayout.OFF_FRAME_READING_SLOT.
inline constexpr size_t kOffFrameReadingSlot = 128;

// ── Heartbeat field offsets (relative to heartbeat region start) ─────
inline constexpr size_t kOffJavaHeartbeat = 0;
inline constexpr size_t kOffEngineHeartbeat = kCacheLine;       // 64
inline constexpr size_t kOffEngineState = kCacheLine + 8;       // 72

// ── Engine state values ──────────────────────────────────────────────
inline constexpr int32_t kEngineStarting = 0;
inline constexpr int32_t kEngineRunning = 1;
inline constexpr int32_t kEngineShutdown = 2;

// =========================================================================
// SharedMemoryChannel
// =========================================================================

// Handle to the shared memory file used for IPC with Java.
//
// Provides access to header fields, heartbeat region (with atomic
// read/write), and command/event ring buffer regions. Thread-safe
// for concurrent reads from disjoint regions.
class SharedMemoryChannel {
 public:
  ~SharedMemoryChannel();

  // Opens an existing shared memory file created by the Java side.
  // Returns nullptr on failure (logs the error).
  static std::unique_ptr<SharedMemoryChannel> Open(
      const base::FilePath& path);

  // Validates the header magic number and protocol version.
  // Returns true if valid.
  bool ValidateHeader() const;

  // ── Header accessors ───────────────────────────────────────────
  uint32_t window_id() const { return window_id_; }
  size_t size() const { return size_; }

  // ── Region accessors ───────────────────────────────────────────
  base::span<const uint8_t> CommandBuffer() const;
  base::span<uint8_t> CommandBufferMut();
  base::span<const uint8_t> EventBuffer() const;
  base::span<uint8_t> EventBufferMut();

  // skia-fx OSR: the data region carries the double-buffered captured page
  // pixels (BGRA8888). Java sizes the mmap so this region holds two
  // viewport-sized slots; the engine writes the back slot each capture and
  // publishes its index via the kFrameReady event. Empty span if the
  // channel was created without a data region.
  base::span<uint8_t> DataBufferMut();
  size_t data_size() const { return data_size_; }

  // ── Heartbeat operations (atomic, cross-process safe) ──────────

  // Reads the Java heartbeat timestamp (monotonic nanoseconds).
  int64_t ReadJavaHeartbeat() const;

  // Writes the engine heartbeat timestamp (monotonic nanoseconds).
  void WriteEngineHeartbeat(int64_t timestamp);

  // Sets the engine state (kEngineStarting, kEngineRunning, kEngineShutdown).
  void SetEngineState(int32_t state);

  // Reads the engine state.
  int32_t ReadEngineState() const;

  // Reads the frame slot Java is currently reading (-1 = none), for the M13
  // anti-tearing handshake. Acquire load; pairs with Java's release store.
  int32_t ReadFrameReadingSlot() const;

  // Returns the raw pointer to the mapped memory (for ring buffer access).
  uint8_t* data() { return data_; }
  const uint8_t* data() const { return data_; }

 private:
  SharedMemoryChannel();

  // The memory-mapped file.
  base::MemoryMappedFile mmap_;
  raw_ptr<uint8_t> data_ = nullptr;
  size_t size_ = 0;

  // Cached offsets from header (read once at open time).
  size_t heartbeat_offset_ = 0;
  size_t cmd_buf_offset_ = 0;
  size_t cmd_buf_size_ = 0;
  size_t evt_buf_offset_ = 0;
  size_t evt_buf_size_ = 0;
  size_t journal_offset_ = 0;
  size_t journal_size_ = 0;
  size_t data_offset_ = 0;
  size_t data_size_ = 0;
  uint32_t window_id_ = 0;
};

}  // namespace ipc
}  // namespace jux

#endif  // JUX_IPC_H_
