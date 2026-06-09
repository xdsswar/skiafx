// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxNetworkInterceptor — process-global request interceptor backing
// WebEngine.addNetworkInterceptor on the Java side.
//
// Holds the armed filter (parsed from the serialized NetworkFilter blob),
// allocates interceptIds, fires kRequestWillBeSent, and routes Java decisions
// back to the deferring URLLoaderThrottle on its own sequence.
//
// SCOPE (m147): blink::URLLoaderThrottle::WillStartRequest forbids modifying a
// request after an async defer (url_loader_throttle.h: "asynchronously touching
// the pointer in defer case is not valid"). So this throttle seam supports
// observe + block + proceed only. Header edits / redirect / synthetic responses
// / response-phase / body rewrite require a URLLoaderFactory proxy (follow-up);
// those actions currently fall back to proceed.

#ifndef JUX_NETWORK_INTERCEPTOR_H_
#define JUX_NETWORK_INTERCEPTOR_H_

#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "base/containers/span.h"
#include "base/memory/scoped_refptr.h"
#include "base/memory/weak_ptr.h"
#include "base/synchronization/lock.h"
#include "base/task/sequenced_task_runner.h"

namespace jux {

class JuxUrlLoaderThrottle;
class JuxProxyingURLLoader;

class JuxNetworkInterceptor {
 public:
  // Decision actions (mirror NetworkExchange in Java). Only PROCEED/BLOCK are
  // honored by the throttle seam; the rest currently behave as PROCEED.
  static constexpr uint8_t kActionProceed = 0;
  static constexpr uint8_t kActionBlock = 2;

  static JuxNetworkInterceptor* GetInstance();

  JuxNetworkInterceptor();
  ~JuxNetworkInterceptor();

  JuxNetworkInterceptor(const JuxNetworkInterceptor&) = delete;
  JuxNetworkInterceptor& operator=(const JuxNetworkInterceptor&) = delete;

  // Arms with a serialized NetworkFilter blob (see NetworkFilter.serialize()).
  void Arm(const uint8_t* blob, size_t len);
  void Disarm();
  bool armed() const;

  // True if the request should be surfaced to Java (request phase).
  bool MatchesRequest(const std::string& url, int resource_type,
                      const std::string& method);

  // Registers a deferring throttle and returns its interceptId.
  uint32_t Register(base::WeakPtr<JuxUrlLoaderThrottle> throttle,
                    scoped_refptr<base::SequencedTaskRunner> runner);

  // Registers a full-MITM proxy loader and returns its interceptId. Decisions
  // are routed to JuxProxyingURLLoader::ApplyDecision on `runner`.
  uint32_t RegisterProxy(base::WeakPtr<JuxProxyingURLLoader> proxy,
                         scoped_refptr<base::SequencedTaskRunner> runner);

  // True if the response should be surfaced to Java (response phase).
  bool MatchesResponse(const std::string& url, int resource_type,
                       const std::string& method);

  // Serializes and writes a kResponseReceived event.
  void FireResponse(uint32_t intercept_id, int status, const std::string& mime,
                    int64_t content_length,
                    const std::vector<std::string>& header_keys,
                    const std::vector<std::string>& header_values);

  // Writes the captured body bytes to a temp file and fires a kResponseBodyChunk
  // event referencing it (the chunk can exceed the ring slot). Java reads then
  // deletes the file. Used by the full-MITM proxy when captureBody() is set.
  void FireBodyChunk(uint32_t intercept_id, uint32_t chunk_seq, uint64_t offset,
                     bool last, base::span<const uint8_t> bytes);

  // Routes an app BodyEdit (kind: 0=pass, 1=replace, 2=drop) back to the proxy
  // loader on its sequence.
  void ResolveBodyEdit(uint32_t intercept_id, uint32_t chunk_seq, uint8_t kind,
                       const uint8_t* replacement, size_t replacement_len);

  // Serializes and writes a kRequestWillBeSent event.
  void FireRequest(uint32_t intercept_id, int resource_type,
                   const std::string& method, const std::string& url,
                   const std::vector<std::string>& header_keys,
                   const std::vector<std::string>& header_values);

  // Routes a Java decision to the throttle on its own sequence.
  void Resolve(uint32_t intercept_id, uint8_t phase, uint8_t action,
               const uint8_t* tail, size_t tail_len);

  // Fires kInterceptComplete and drops the registration.
  void Complete(uint32_t intercept_id, int net_error);

 private:
  // Out-of-line ctor/dtor required by chromium-style for structs with
  // non-trivial members.
  struct Filter {
    Filter();
    Filter(Filter&&);
    Filter& operator=(Filter&&);
    ~Filter();

    bool armed = false;
    std::vector<std::string> includes;
    std::vector<std::string> excludes;
    uint32_t type_mask = 0xFFFFFFFFu;
    std::vector<std::string> methods;  // empty = all
    uint8_t phase_mask = 0x1;          // bit0=REQUEST, bit1=RESPONSE
  };
  struct Pending {
    Pending();
    ~Pending();

    base::WeakPtr<JuxUrlLoaderThrottle> throttle;   // throttle seam
    base::WeakPtr<JuxProxyingURLLoader> proxy;       // full-MITM proxy seam
    scoped_refptr<base::SequencedTaskRunner> runner;
  };

  mutable base::Lock lock_;
  Filter filter_;
  uint32_t next_id_ = 1;
  std::map<uint32_t, Pending> pending_;
};

}  // namespace jux

#endif  // JUX_NETWORK_INTERCEPTOR_H_
