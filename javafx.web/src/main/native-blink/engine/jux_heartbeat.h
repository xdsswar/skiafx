// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// Heartbeat protocol for liveness detection between Java and the engine.
//
// The engine writes engine_heartbeat every 200ms and reads java_heartbeat.
// If Java's heartbeat is stale (>3 seconds over 5 consecutive checks),
// the engine self-terminates — the Java process has died.
//
// Clock contract: Both sides use monotonic nanosecond clocks. Java uses
// System.nanoTime() and the engine uses QueryPerformanceCounter (Windows),
// clock_gettime(CLOCK_MONOTONIC) (Linux), or mach_absolute_time (macOS).
// These are the same OS clock source, so the difference between readings
// from the same machine is meaningful for staleness detection.

#ifndef JUX_HEARTBEAT_H_
#define JUX_HEARTBEAT_H_

#include <cstdint>
#include <memory>

namespace jux {

namespace ipc {
class SharedMemoryChannel;
}

// Returns a monotonic nanosecond timestamp compatible with Java's
// System.nanoTime(). Uses the same OS clock source.
int64_t MonotonicNanos();

// Starts the heartbeat thread. The thread writes engine_heartbeat every
// 200ms and monitors java_heartbeat for staleness. If Java appears dead
// (>3s stale for 5 consecutive checks), the engine exits.
//
// The channel pointer must remain valid for the lifetime of the thread.
void StartHeartbeat(ipc::SharedMemoryChannel* channel);

// Stops the heartbeat thread (for clean shutdown).
void StopHeartbeat();

}  // namespace jux

#endif  // JUX_HEARTBEAT_H_
