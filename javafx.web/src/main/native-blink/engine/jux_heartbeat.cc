// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// Implementation of the heartbeat liveness protocol.

#include "jux/jux_heartbeat.h"

#include <atomic>

#include "base/logging.h"
#include "base/process/process.h"
#include "base/threading/thread.h"
#include "jux/jux_ipc.h"

#if BUILDFLAG(IS_WIN)
#include <windows.h>
#elif BUILDFLAG(IS_MAC)
#include <mach/mach_time.h>
#elif BUILDFLAG(IS_LINUX)
#include <time.h>
#endif

namespace jux {

namespace {

// Heartbeat interval: 200ms between writes.
constexpr int kHeartbeatIntervalMs = 200;

// Maximum allowed staleness for Java's heartbeat before the JVM is presumed
// dead. 1.5s is far larger than any plausible clock drift between Java's
// System.nanoTime() and our MonotonicNanos() (both QPC-based on Windows), so
// it won't false-positive, yet it tears the engine down promptly.
constexpr int64_t kJavaHeartbeatTimeoutNs = 1'500'000'000LL;

// Number of consecutive stale reads before declaring Java dead (200ms each).
constexpr int kStaleThreshold = 2;

// The heartbeat thread. Heap-allocated and leaked intentionally to avoid
// exit-time destructor (Chromium clang enforces this).
base::Thread* g_heartbeat_thread = nullptr;

// Signal to stop the heartbeat thread.
std::atomic<bool> g_heartbeat_running{false};

}  // namespace

// ── MonotonicNanos ───────────────────────────────────────────────────

int64_t MonotonicNanos() {
#if BUILDFLAG(IS_WIN)
  // QueryPerformanceCounter — same source as Java's System.nanoTime().
  static LARGE_INTEGER freq = []() {
    LARGE_INTEGER f;
    QueryPerformanceFrequency(&f);
    return f;
  }();

  LARGE_INTEGER counter;
  QueryPerformanceCounter(&counter);

  // Convert to nanoseconds: counter * 1e9 / freq.
  // Use 128-bit multiplication to avoid overflow.
  // (counter.QuadPart * 1000000000) could overflow int64 for large values.
  __int64 sec = counter.QuadPart / freq.QuadPart;
  __int64 rem = counter.QuadPart % freq.QuadPart;
  return sec * 1'000'000'000LL + rem * 1'000'000'000LL / freq.QuadPart;

#elif BUILDFLAG(IS_MAC)
  // mach_absolute_time — same source as Java's System.nanoTime().
  static mach_timebase_info_data_t timebase = []() {
    mach_timebase_info_data_t tb;
    mach_timebase_info(&tb);
    return tb;
  }();

  uint64_t ticks = mach_absolute_time();
  return static_cast<int64_t>(ticks * timebase.numer / timebase.denom);

#elif BUILDFLAG(IS_LINUX)
  // clock_gettime(CLOCK_MONOTONIC) — same source as Java's System.nanoTime().
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000LL + ts.tv_nsec;

#else
#error "Unsupported platform for MonotonicNanos"
#endif
}

// ── Heartbeat thread ─────────────────────────────────────────────────

void StartHeartbeat(ipc::SharedMemoryChannel* channel) {
  if (g_heartbeat_thread) {
    LOG(WARNING) << "Heartbeat thread already running";
    return;
  }

  g_heartbeat_running.store(true, std::memory_order_release);

  g_heartbeat_thread = new base::Thread("jux-heartbeat");
  base::Thread::Options options;
  options.message_pump_type = base::MessagePumpType::DEFAULT;
  g_heartbeat_thread->StartWithOptions(std::move(options));

  // Post the heartbeat loop to the thread.
  g_heartbeat_thread->task_runner()->PostTask(
      FROM_HERE,
      base::BindOnce(
          [](ipc::SharedMemoryChannel* ch) {
            int consecutive_stale = 0;

            while (g_heartbeat_running.load(std::memory_order_acquire)) {
              // Write engine heartbeat using monotonic nanos.
              int64_t now = MonotonicNanos();
              ch->WriteEngineHeartbeat(now);

              // Read Java heartbeat and check staleness. The engine is a
              // background, windowless process — if the JVM dies (crash, kill,
              // or normal exit without a clean teardown) it would otherwise
              // linger invisibly forever. So when Java's heartbeat goes stale
              // we self-terminate immediately. Terminating the browser process
              // brings its renderer/GPU/utility children down with it
              // (Chromium's children monitor the browser and exit on its
              // death), so no part of the engine is orphaned.
              //
              // Only arm this after Java has written at least one heartbeat
              // (java_hb > 0) so startup, before Java's HeartbeatWriter spins
              // up, never trips it.
              int64_t java_hb = ch->ReadJavaHeartbeat();
              if (java_hb > 0) {
                int64_t age = now - java_hb;
                if (age > kJavaHeartbeatTimeoutNs) {
                  consecutive_stale++;
                  if (consecutive_stale >= kStaleThreshold) {
                    LOG(ERROR)
                        << "Java heartbeat stale for " << age << "ns ("
                        << consecutive_stale
                        << " consecutive checks) — JVM presumed dead, "
                        << "terminating engine to avoid an orphan process";
                    base::Process::TerminateCurrentProcessImmediately(0);
                  }
                } else {
                  consecutive_stale = 0;
                }
              }
              // If java_hb == 0, Java hasn't started writing yet — skip.

              base::PlatformThread::Sleep(
                  base::Milliseconds(kHeartbeatIntervalMs));
            }
          },
          base::Unretained(channel)));

  VLOG(1) << "Heartbeat thread started (interval: " << kHeartbeatIntervalMs
            << "ms, monotonic nanos)";
}

void StopHeartbeat() {
  g_heartbeat_running.store(false, std::memory_order_release);
  if (g_heartbeat_thread) {
    g_heartbeat_thread->Stop();
    delete g_heartbeat_thread;
    g_heartbeat_thread = nullptr;
  }
}

}  // namespace jux
