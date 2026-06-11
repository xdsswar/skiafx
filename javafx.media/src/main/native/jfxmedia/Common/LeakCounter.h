/*
 * LeakCounter — skia-fx dev-build native leak detection for the media
 * stack. Zero overhead unless SKIA_MEDIA_DEBUG is set in the
 * environment; then every instrumented type counts constructions and
 * destructions, and a process-exit dump flags any imbalance:
 *
 *   [media-leak] CGstAudioPlaybackPipeline   created=12 destroyed=11  <-- LEAK
 *
 * Usage: SKIAFX_LEAK_CREATED("CFoo") in the constructor,
 * SKIAFX_LEAK_DESTROYED("CFoo") in the destructor. Pass string
 * LITERALS — slots key on the pointer first, contents second.
 */

#ifndef _SKIAFX_LEAK_COUNTER_H_
#define _SKIAFX_LEAK_COUNTER_H_

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>

namespace skiafx_leak {

constexpr int kMaxTypes = 32;

struct Slot {
    const char*       name;
    std::atomic<long> created;
    std::atomic<long> destroyed;
};

inline Slot* slots() {
    static Slot s[kMaxTypes] = {};
    return s;
}

inline std::mutex& registryMutex() {
    static std::mutex m;
    return m;
}

inline int& slotCount() {
    static int n = 0;
    return n;
}

inline bool enabled() {
    static bool on = std::getenv("SKIA_MEDIA_DEBUG") != nullptr;
    return on;
}

inline void dump() {
    Slot* s = slots();
    int n;
    {
        std::lock_guard<std::mutex> lock(registryMutex());
        n = slotCount();
    }
    std::fprintf(stderr, "[media-leak] ---- native object balance ----\n");
    for (int i = 0; i < n; ++i) {
        long c = s[i].created.load();
        long d = s[i].destroyed.load();
        std::fprintf(stderr, "[media-leak] %-32s created=%ld destroyed=%ld%s\n",
                     s[i].name, c, d, (c != d) ? "  <-- LEAK" : "");
    }
    std::fflush(stderr);
}

inline Slot* slotFor(const char* name) {
    Slot* s = slots();
    std::lock_guard<std::mutex> lock(registryMutex());
    int n = slotCount();
    for (int i = 0; i < n; ++i) {
        if (s[i].name == name || std::strcmp(s[i].name, name) == 0) {
            return &s[i];
        }
    }
    if (n >= kMaxTypes) {
        return nullptr;
    }
    s[n].name = name;
    if (n == 0) {
        std::atexit(dump);
    }
    slotCount() = n + 1;
    return &s[n];
}

inline void created(const char* name) {
    if (!enabled()) return;
    Slot* s = slotFor(name);
    if (s) s->created.fetch_add(1, std::memory_order_relaxed);
}

inline void destroyed(const char* name) {
    if (!enabled()) return;
    Slot* s = slotFor(name);
    if (s) s->destroyed.fetch_add(1, std::memory_order_relaxed);
}

} // namespace skiafx_leak

#define SKIAFX_LEAK_CREATED(name)   skiafx_leak::created(name)
#define SKIAFX_LEAK_DESTROYED(name) skiafx_leak::destroyed(name)

#endif // _SKIAFX_LEAK_COUNTER_H_
