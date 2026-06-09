// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxNetworkInterceptor implementation.

#ifdef UNSAFE_BUFFERS_BUILD
#pragma allow_unsafe_buffers
#endif

#include "jux/jux_network_interceptor.h"

#include <cstring>

#include "base/files/file_util.h"
#include "base/functional/bind.h"
#include "content/public/browser/browser_task_traits.h"
#include "content/public/browser/browser_thread.h"
#include "jux/jux_event_types.h"
#include "jux/jux_ipc.h"
#include "jux/jux_proxying_url_loader_factory.h"
#include "jux/jux_ring_buffer.h"
#include "jux/jux_url_loader_throttle.h"

namespace jux {

extern EventWriter* g_callback_evt_writer;
extern ipc::SharedMemoryChannel* g_callback_channel;

namespace {

JuxNetworkInterceptor* g_instance = nullptr;

// Marshal an event onto the UI thread before writing it to the callback ring.
// EventWriter is a single-producer (SPSC) ring; the rest of the engine writes
// it only from the UI thread. The network interceptor, however, fires from
// URLLoaderThrottle / proxy network sequences. Writing the ring from those
// threads races the UI-thread producer's write_pos/slots -> torn writes and a
// desynced ring (garbage events / reader OOB on the Java side). Building the
// payload off-thread is fine; only the WriteEvent must be serialized onto the
// single producer thread.
void WriteEventNow(uint32_t event_type, std::vector<uint8_t> payload) {
  if (g_callback_evt_writer && g_callback_channel) {
    g_callback_evt_writer->WriteEvent(event_type,
                                      g_callback_channel->window_id(),
                                      base::span<const uint8_t>(payload));
  }
}

void WriteEventOnUI(uint32_t event_type, std::vector<uint8_t> payload) {
  // base::BindOnce only binds free functions / methods (not capturing lambdas),
  // so the payload rides as a bound move-only argument.
  if (content::BrowserThread::CurrentlyOn(content::BrowserThread::UI)) {
    WriteEventNow(event_type, std::move(payload));
  } else {
    content::GetUIThreadTaskRunner({})->PostTask(
        FROM_HERE,
        base::BindOnce(&WriteEventNow, event_type, std::move(payload)));
  }
}

// Case-insensitive glob match supporting '*' and '?'.
bool GlobMatch(const std::string& pat, const std::string& str) {
  size_t p = 0, s = 0;
  size_t star = std::string::npos, ss = 0;
  auto lower = [](char c) {
    return static_cast<char>((c >= 'A' && c <= 'Z') ? c + 32 : c);
  };
  while (s < str.size()) {
    if (p < pat.size() && (pat[p] == '?' || lower(pat[p]) == lower(str[s]))) {
      ++p;
      ++s;
    } else if (p < pat.size() && pat[p] == '*') {
      star = p++;
      ss = s;
    } else if (star != std::string::npos) {
      p = star + 1;
      s = ++ss;
    } else {
      return false;
    }
  }
  while (p < pat.size() && pat[p] == '*') {
    ++p;
  }
  return p == pat.size();
}

// Little-endian readers over a byte blob with bounds checks.
struct Reader {
  const uint8_t* b;
  size_t len;
  size_t off = 0;
  bool ok = true;
  uint8_t u8() {
    if (off + 1 > len) { ok = false; return 0; }
    return b[off++];
  }
  uint16_t u16() {
    if (off + 2 > len) { ok = false; return 0; }
    uint16_t v = static_cast<uint16_t>(b[off] | (b[off + 1] << 8));
    off += 2;
    return v;
  }
  uint32_t u32() {
    if (off + 4 > len) { ok = false; return 0; }
    uint32_t v = b[off] | (b[off + 1] << 8) | (b[off + 2] << 16) |
                 (static_cast<uint32_t>(b[off + 3]) << 24);
    off += 4;
    return v;
  }
  void skip(size_t n) {
    if (off + n > len) { ok = false; return; }
    off += n;
  }
  std::string str(size_t n) {
    if (off + n > len) { ok = false; return {}; }
    std::string s(reinterpret_cast<const char*>(b + off), n);
    off += n;
    return s;
  }
};

void PutU32(std::vector<uint8_t>& out, uint32_t v) {
  out.push_back(static_cast<uint8_t>(v));
  out.push_back(static_cast<uint8_t>(v >> 8));
  out.push_back(static_cast<uint8_t>(v >> 16));
  out.push_back(static_cast<uint8_t>(v >> 24));
}
void PutU16(std::vector<uint8_t>& out, uint16_t v) {
  out.push_back(static_cast<uint8_t>(v));
  out.push_back(static_cast<uint8_t>(v >> 8));
}
void PutStr16(std::vector<uint8_t>& out, const std::string& s) {
  uint16_t n = static_cast<uint16_t>(s.size() > 0xFFFFu ? 0xFFFFu : s.size());
  PutU16(out, n);
  out.insert(out.end(), s.begin(), s.begin() + n);
}

}  // namespace

JuxNetworkInterceptor::Filter::Filter() = default;
JuxNetworkInterceptor::Filter::Filter(Filter&&) = default;
JuxNetworkInterceptor::Filter& JuxNetworkInterceptor::Filter::operator=(Filter&&) =
    default;
JuxNetworkInterceptor::Filter::~Filter() = default;
JuxNetworkInterceptor::Pending::Pending() = default;
JuxNetworkInterceptor::Pending::~Pending() = default;

JuxNetworkInterceptor::JuxNetworkInterceptor() {
  g_instance = this;
}

JuxNetworkInterceptor::~JuxNetworkInterceptor() {
  if (g_instance == this) {
    g_instance = nullptr;
  }
  // Drop any stragglers so the WeakPtr/task-runner refs they hold are released
  // (throttles/proxies normally Complete() themselves; this is the backstop for
  // any still in flight when the interceptor is torn down with the context).
  base::AutoLock guard(lock_);
  pending_.clear();
}

JuxNetworkInterceptor* JuxNetworkInterceptor::GetInstance() {
  return g_instance;
}

void JuxNetworkInterceptor::Arm(const uint8_t* blob, size_t len) {
  Filter f;
  f.armed = true;
  Reader r{blob, len};
  r.u8();  // version
  uint16_t inc = r.u16();
  for (uint16_t i = 0; i < inc && r.ok; ++i) {
    f.includes.push_back(r.str(r.u16()));
  }
  uint16_t exc = r.u16();
  for (uint16_t i = 0; i < exc && r.ok; ++i) {
    f.excludes.push_back(r.str(r.u16()));
  }
  f.type_mask = r.u32();
  uint16_t mc = r.u16();
  for (uint16_t i = 0; i < mc && r.ok; ++i) {
    f.methods.push_back(r.str(r.u8()));
  }
  f.phase_mask = r.u8();
  // capture flag + maxBody trail are response-phase concerns (ignored here).

  base::AutoLock guard(lock_);
  filter_ = std::move(f);
}

void JuxNetworkInterceptor::Disarm() {
  base::AutoLock guard(lock_);
  filter_ = Filter{};
}

bool JuxNetworkInterceptor::armed() const {
  base::AutoLock guard(lock_);
  return filter_.armed;
}

bool JuxNetworkInterceptor::MatchesRequest(const std::string& url,
                                           int resource_type,
                                           const std::string& method) {
  base::AutoLock guard(lock_);
  if (!filter_.armed || (filter_.phase_mask & 0x1) == 0) {
    return false;
  }
  if (resource_type >= 0 && resource_type < 32 &&
      ((filter_.type_mask >> resource_type) & 1u) == 0) {
    return false;
  }
  if (!filter_.methods.empty()) {
    bool ok = false;
    for (const auto& m : filter_.methods) {
      if (m.size() == method.size()) {
        bool eq = true;
        for (size_t i = 0; i < m.size(); ++i) {
          char a = m[i], b = method[i];
          if (a >= 'a' && a <= 'z') a -= 32;
          if (b >= 'a' && b <= 'z') b -= 32;
          if (a != b) { eq = false; break; }
        }
        if (eq) { ok = true; break; }
      }
    }
    if (!ok) {
      return false;
    }
  }
  for (const auto& g : filter_.excludes) {
    if (GlobMatch(g, url)) {
      return false;
    }
  }
  if (filter_.includes.empty()) {
    return true;
  }
  for (const auto& g : filter_.includes) {
    if (GlobMatch(g, url)) {
      return true;
    }
  }
  return false;
}

uint32_t JuxNetworkInterceptor::Register(
    base::WeakPtr<JuxUrlLoaderThrottle> throttle,
    scoped_refptr<base::SequencedTaskRunner> runner) {
  base::AutoLock guard(lock_);
  uint32_t id = next_id_++;
  Pending& entry = pending_[id];
  entry.throttle = std::move(throttle);
  entry.runner = std::move(runner);
  return id;
}

uint32_t JuxNetworkInterceptor::RegisterProxy(
    base::WeakPtr<JuxProxyingURLLoader> proxy,
    scoped_refptr<base::SequencedTaskRunner> runner) {
  base::AutoLock guard(lock_);
  uint32_t id = next_id_++;
  Pending& entry = pending_[id];
  entry.proxy = std::move(proxy);
  entry.runner = std::move(runner);
  return id;
}

bool JuxNetworkInterceptor::MatchesResponse(const std::string& url,
                                            int resource_type,
                                            const std::string& method) {
  base::AutoLock guard(lock_);
  if (!filter_.armed || (filter_.phase_mask & 0x2) == 0) {
    return false;
  }
  if (resource_type >= 0 && resource_type < 32 &&
      ((filter_.type_mask >> resource_type) & 1u) == 0) {
    return false;
  }
  if (!filter_.methods.empty()) {
    bool ok = false;
    for (const auto& m : filter_.methods) {
      if (m.size() != method.size()) {
        continue;
      }
      bool eq = true;
      for (size_t i = 0; i < m.size(); ++i) {
        char a = m[i], b = method[i];
        if (a >= 'a' && a <= 'z') a -= 32;
        if (b >= 'a' && b <= 'z') b -= 32;
        if (a != b) { eq = false; break; }
      }
      if (eq) { ok = true; break; }
    }
    if (!ok) {
      return false;
    }
  }
  for (const auto& g : filter_.excludes) {
    if (GlobMatch(g, url)) {
      return false;
    }
  }
  if (filter_.includes.empty()) {
    return true;
  }
  for (const auto& g : filter_.includes) {
    if (GlobMatch(g, url)) {
      return true;
    }
  }
  return false;
}

void JuxNetworkInterceptor::FireResponse(
    uint32_t intercept_id, int status, const std::string& mime,
    int64_t content_length, const std::vector<std::string>& header_keys,
    const std::vector<std::string>& header_values) {
  if (!g_callback_evt_writer || !g_callback_channel) {
    return;
  }
  // Payload (after windowId): [interceptId:4][status:4][mimeLen:2][mime]
  // [contentLength:8][hdrBlobLen:4][hdrBlob][flags:4]
  std::vector<uint8_t> p;
  PutU32(p, intercept_id);
  PutU32(p, static_cast<uint32_t>(status));
  PutStr16(p, mime);
  uint64_t cl = static_cast<uint64_t>(content_length);
  for (int i = 0; i < 8; ++i) {
    p.push_back(static_cast<uint8_t>(cl >> (i * 8)));
  }

  std::vector<uint8_t> hdr;
  size_t n = std::min(header_keys.size(), header_values.size());
  PutU32(hdr, static_cast<uint32_t>(n));
  for (size_t i = 0; i < n; ++i) {
    PutStr16(hdr, header_keys[i]);
    PutStr16(hdr, header_values[i]);
  }
  PutU32(p, static_cast<uint32_t>(hdr.size()));
  p.insert(p.end(), hdr.begin(), hdr.end());
  PutU32(p, 0);  // flags (reserved)

  WriteEventOnUI(events::kResponseReceived, std::move(p));
}

void JuxNetworkInterceptor::FireBodyChunk(uint32_t intercept_id,
                                          uint32_t chunk_seq, uint64_t offset,
                                          bool last,
                                          base::span<const uint8_t> bytes) {
  if (!g_callback_evt_writer || !g_callback_channel) {
    return;
  }
  // The chunk can exceed the ring slot, so it rides a temp file (Java reads then
  // deletes it).
  std::string path;
  base::FilePath tmp;
  if (!bytes.empty() && base::CreateTemporaryFile(&tmp)) {
    base::WriteFile(tmp, bytes);
    path = tmp.AsUTF8Unsafe();
  }
  // Payload (after windowId):
  //   [interceptId:4][chunkSeq:4][offset:8][last:1][len:4][pathLen:4][path]
  std::vector<uint8_t> p;
  PutU32(p, intercept_id);
  PutU32(p, chunk_seq);
  for (int i = 0; i < 8; ++i) {
    p.push_back(static_cast<uint8_t>(offset >> (i * 8)));
  }
  p.push_back(last ? 1 : 0);
  PutU32(p, static_cast<uint32_t>(bytes.size()));
  PutU32(p, static_cast<uint32_t>(path.size()));
  p.insert(p.end(), path.begin(), path.end());
  WriteEventOnUI(events::kResponseBodyChunk, std::move(p));
}

void JuxNetworkInterceptor::ResolveBodyEdit(uint32_t intercept_id,
                                            uint32_t chunk_seq, uint8_t kind,
                                            const uint8_t* replacement,
                                            size_t replacement_len) {
  base::WeakPtr<JuxProxyingURLLoader> proxy;
  scoped_refptr<base::SequencedTaskRunner> runner;
  {
    base::AutoLock guard(lock_);
    auto it = pending_.find(intercept_id);
    if (it == pending_.end()) {
      return;
    }
    proxy = it->second.proxy;
    runner = it->second.runner;
  }
  if (!runner || !proxy.MaybeValid()) {
    return;
  }
  std::vector<uint8_t> repl;
  if (replacement && replacement_len > 0) {
    repl.assign(replacement, replacement + replacement_len);
  }
  runner->PostTask(FROM_HERE,
                   base::BindOnce(&JuxProxyingURLLoader::ApplyBodyEdit, proxy,
                                  kind, std::move(repl)));
}

void JuxNetworkInterceptor::FireRequest(
    uint32_t intercept_id, int resource_type, const std::string& method,
    const std::string& url, const std::vector<std::string>& header_keys,
    const std::vector<std::string>& header_values) {
  if (!g_callback_evt_writer || !g_callback_channel) {
    return;
  }
  // Payload (after windowId): [interceptId:4][resourceType:4][methodLen:2]
  // [method][urlLen:4][url][hdrBlobLen:4][hdrBlob]
  std::vector<uint8_t> p;
  PutU32(p, intercept_id);
  PutU32(p, static_cast<uint32_t>(resource_type));
  PutStr16(p, method);
  PutU32(p, static_cast<uint32_t>(url.size()));
  p.insert(p.end(), url.begin(), url.end());

  std::vector<uint8_t> hdr;
  size_t n = std::min(header_keys.size(), header_values.size());
  PutU32(hdr, static_cast<uint32_t>(n));
  for (size_t i = 0; i < n; ++i) {
    PutStr16(hdr, header_keys[i]);
    PutStr16(hdr, header_values[i]);
  }
  PutU32(p, static_cast<uint32_t>(hdr.size()));
  p.insert(p.end(), hdr.begin(), hdr.end());

  WriteEventOnUI(events::kRequestWillBeSent, std::move(p));
}

void JuxNetworkInterceptor::Resolve(uint32_t intercept_id, uint8_t phase,
                                    uint8_t action, const uint8_t* tail,
                                    size_t tail_len) {
  base::WeakPtr<JuxUrlLoaderThrottle> throttle;
  base::WeakPtr<JuxProxyingURLLoader> proxy;
  scoped_refptr<base::SequencedTaskRunner> runner;
  {
    base::AutoLock guard(lock_);
    auto it = pending_.find(intercept_id);
    if (it == pending_.end()) {
      return;
    }
    throttle = it->second.throttle;
    proxy = it->second.proxy;
    runner = it->second.runner;
  }
  if (!runner) {
    return;
  }
  if (proxy.MaybeValid()) {
    // Full-MITM proxy seam — pass phase + action + a copy of the tail.
    std::vector<uint8_t> tail_copy;
    if (tail && tail_len > 0) {
      tail_copy.assign(tail, tail + tail_len);
    }
    runner->PostTask(FROM_HERE,
                     base::BindOnce(&JuxProxyingURLLoader::ApplyDecision, proxy,
                                    phase, action, std::move(tail_copy)));
    return;
  }
  // Throttle seam (observe/block/proceed only).
  runner->PostTask(
      FROM_HERE,
      base::BindOnce(&JuxUrlLoaderThrottle::ApplyDecision, throttle, action));
}

void JuxNetworkInterceptor::Complete(uint32_t intercept_id, int net_error) {
  {
    base::AutoLock guard(lock_);
    pending_.erase(intercept_id);
  }
  if (g_callback_evt_writer && g_callback_channel) {
    std::vector<uint8_t> p;
    PutU32(p, intercept_id);
    PutU32(p, static_cast<uint32_t>(net_error));
    WriteEventOnUI(events::kInterceptComplete, std::move(p));
  }
}

}  // namespace jux
