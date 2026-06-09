// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxProxyingURLLoaderFactory — full-MITM network interception.
//
// Inserted into every URLLoaderFactory the content layer creates, via
// ContentBrowserClient::WillCreateURLLoaderFactory + the m147
// network::URLLoaderFactoryBuilder seam (Append() vends a renderer-facing
// receiver + a remote to the real factory). Unlike the URLLoaderThrottle seam
// (observe + block only — async request modification is forbidden there), the
// factory proxy OWNS each request's URLLoader/URLLoaderClient pipes, so it can:
//
//   request phase  — observe, block, redirect, modify headers/method,
//                     or serve a synthetic response (never hit the network);
//   response phase — observe, edit status line + headers before the renderer
//                    sees them.
//
// Body capture/rewrite (tapping the response data pipe) is a follow-up; today
// the body pipe is forwarded unchanged.
//
// Decisions are routed back from Java through JuxNetworkInterceptor's proxy
// path (RegisterProxy + Resolve branch), posted to the sequence the loader runs
// on. Each JuxProxyingURLLoader is owned by the factory (which deletes itself
// once all its receivers AND in-flight loaders are gone).

#ifndef JUX_PROXYING_URL_LOADER_FACTORY_H_
#define JUX_PROXYING_URL_LOADER_FACTORY_H_

#include <cstdint>
#include <memory>
#include <optional>
#include <set>
#include <vector>

#include "base/containers/unique_ptr_adapters.h"
#include "base/memory/raw_ptr.h"
#include "base/memory/weak_ptr.h"
#include "mojo/public/cpp/base/big_buffer.h"
#include "mojo/public/cpp/bindings/pending_receiver.h"
#include "mojo/public/cpp/bindings/pending_remote.h"
#include "mojo/public/cpp/bindings/receiver.h"
#include "mojo/public/cpp/bindings/receiver_set.h"
#include "mojo/public/cpp/bindings/remote.h"
#include "mojo/public/cpp/system/data_pipe.h"
#include "mojo/public/cpp/system/data_pipe_drainer.h"
#include "mojo/public/cpp/system/data_pipe_producer.h"
#include "net/traffic_annotation/network_traffic_annotation.h"
#include "net/url_request/redirect_info.h"
#include "services/network/public/cpp/resource_request.h"
#include "services/network/public/cpp/url_loader_completion_status.h"
#include "services/network/public/mojom/early_hints.mojom.h"
#include "services/network/public/mojom/url_loader.mojom.h"
#include "services/network/public/mojom/url_loader_factory.mojom.h"
#include "services/network/public/mojom/url_response_head.mojom.h"

namespace jux {

class JuxProxyingURLLoader;

class JuxProxyingURLLoaderFactory : public network::mojom::URLLoaderFactory {
 public:
  // Binds `proxy_receiver` (renderer-facing) to a new factory that forwards to
  // `target_remote` (the real factory). Self-owned: destroyed when all of its
  // receivers disconnect and no loaders remain in flight.
  static void CreateProxy(
      mojo::PendingReceiver<network::mojom::URLLoaderFactory> proxy_receiver,
      mojo::PendingRemote<network::mojom::URLLoaderFactory> target_remote);

  JuxProxyingURLLoaderFactory(const JuxProxyingURLLoaderFactory&) = delete;
  JuxProxyingURLLoaderFactory& operator=(const JuxProxyingURLLoaderFactory&) =
      delete;

  // network::mojom::URLLoaderFactory:
  void CreateLoaderAndStart(
      mojo::PendingReceiver<network::mojom::URLLoader> loader,
      int32_t request_id,
      uint32_t options,
      const network::ResourceRequest& request,
      mojo::PendingRemote<network::mojom::URLLoaderClient> client,
      const net::MutableNetworkTrafficAnnotationTag& traffic_annotation)
      override;
  void Clone(mojo::PendingReceiver<network::mojom::URLLoaderFactory> receiver)
      override;

  // Forwards a request to the real factory (called by JuxProxyingURLLoader).
  network::mojom::URLLoaderFactory* target() { return target_factory_.get(); }

  // Removes a completed loader; may delete `this` if nothing is left.
  void RemoveLoader(JuxProxyingURLLoader* loader);

 private:
  JuxProxyingURLLoaderFactory(
      mojo::PendingReceiver<network::mojom::URLLoaderFactory> proxy_receiver,
      mojo::PendingRemote<network::mojom::URLLoaderFactory> target_remote);
  ~JuxProxyingURLLoaderFactory() override;

  void OnProxyDisconnect();
  void MaybeDestroySelf();

  mojo::ReceiverSet<network::mojom::URLLoaderFactory> proxy_receivers_;
  mojo::Remote<network::mojom::URLLoaderFactory> target_factory_;
  std::set<std::unique_ptr<JuxProxyingURLLoader>, base::UniquePtrComparator>
      loaders_;
};

// One per intercepted request. Interposes both the renderer↔proxy pipes (we are
// the URLLoader the renderer drives) and the proxy↔network pipes (we are the
// URLLoaderClient the real loader calls back). Owned by the factory.
class JuxProxyingURLLoader : public network::mojom::URLLoader,
                            public network::mojom::URLLoaderClient,
                            public mojo::DataPipeDrainer::Client {
 public:
  JuxProxyingURLLoader(
      JuxProxyingURLLoaderFactory* factory,
      mojo::PendingReceiver<network::mojom::URLLoader> loader_receiver,
      int32_t request_id,
      uint32_t options,
      const network::ResourceRequest& request,
      mojo::PendingRemote<network::mojom::URLLoaderClient> client,
      const net::MutableNetworkTrafficAnnotationTag& traffic_annotation);
  ~JuxProxyingURLLoader() override;

  JuxProxyingURLLoader(const JuxProxyingURLLoader&) = delete;
  JuxProxyingURLLoader& operator=(const JuxProxyingURLLoader&) = delete;

  // Consults the interceptor for the request phase and either starts the real
  // load (no match) or fires kRequestWillBeSent and waits for a decision.
  void Start();

  // Applies a Java decision (browser/network sequence). phase: 0=request,
  // 1=response. action/tail mirror NetworkExchange's A_* codes + encodeEdits/
  // encodeSynthetic.
  void ApplyDecision(uint8_t phase, uint8_t action, std::vector<uint8_t> tail);

  // Applies an app body decision for a captured response (kind: 0=pass,
  // 1=replace, 2=drop). Posted by JuxNetworkInterceptor::ResolveBodyEdit.
  void ApplyBodyEdit(uint8_t kind, std::vector<uint8_t> replacement);

  // network::mojom::URLLoader (renderer → us):
  void FollowRedirect(
      const std::vector<std::string>& removed_headers,
      const net::HttpRequestHeaders& modified_headers,
      const net::HttpRequestHeaders& modified_cors_exempt_headers,
      const std::optional<GURL>& new_url) override;
  void SetPriority(net::RequestPriority priority,
                   int32_t intra_priority_value) override;

  // network::mojom::URLLoaderClient (real loader → us):
  void OnReceiveEarlyHints(network::mojom::EarlyHintsPtr early_hints) override;
  void OnReceiveResponse(
      network::mojom::URLResponseHeadPtr head,
      mojo::ScopedDataPipeConsumerHandle body,
      std::optional<mojo_base::BigBuffer> cached_metadata) override;
  void OnReceiveRedirect(const net::RedirectInfo& redirect_info,
                         network::mojom::URLResponseHeadPtr head) override;
  void OnUploadProgress(int64_t current_position,
                        int64_t total_size,
                        OnUploadProgressCallback callback) override;
  void OnTransferSizeUpdated(int32_t transfer_size_diff) override;
  void OnComplete(const network::URLLoaderCompletionStatus& status) override;

  // mojo::DataPipeDrainer::Client (draining the real response body for capture):
  void OnDataAvailable(base::span<const uint8_t> data) override;
  void OnDataComplete() override;

 private:
  // Registers with the interceptor once; returns the shared interceptId so the
  // app correlates request and response events for the same exchange.
  uint32_t EnsureRegistered();

  void StartReal();                              // forward request to network
  void ApplyRequestEdits(const std::vector<uint8_t>& tail);  // encodeEdits
  void ServeSynthetic(const std::vector<uint8_t>& tail);     // encodeSynthetic
  void ApplyResponseEdits(const std::vector<uint8_t>& tail); // status/headers
  void ReplaceHeldBody(const std::vector<uint8_t>& body);    // swap response body
  void StartBodyCapture();                       // tee body to Java for editing
  void OnBodyWriteDone(MojoResult result);       // edited-body write callback
  void FinishCapture();                          // deferred capture teardown
  void ForwardHeldResponse();                    // flush held OnReceiveResponse
  void FireResponseEvent();                      // kResponseReceived
  void Finish(int net_error);                    // complete + ask factory to drop
  void OnClientDisconnect();

  raw_ptr<JuxProxyingURLLoaderFactory> factory_;
  network::ResourceRequest request_;
  const int32_t request_id_;
  const uint32_t options_;
  net::MutableNetworkTrafficAnnotationTag traffic_annotation_;

  mojo::Receiver<network::mojom::URLLoader> loader_receiver_{this};
  mojo::Remote<network::mojom::URLLoaderClient> target_client_;
  mojo::Remote<network::mojom::URLLoader> real_loader_;
  mojo::Receiver<network::mojom::URLLoaderClient> real_client_receiver_{this};

  uint32_t intercept_id_ = 0;
  bool registered_ = false;
  bool completed_ = false;

  // Response held while waiting for a response-phase decision.
  bool holding_response_ = false;
  network::mojom::URLResponseHeadPtr held_head_;
  mojo::ScopedDataPipeConsumerHandle held_body_;
  std::optional<mojo_base::BigBuffer> held_metadata_;
  std::optional<network::URLLoaderCompletionStatus> pending_complete_;

  // Whole-body capture state (captureBody()): the real body is drained into
  // captured_body_ and surfaced to Java as one chunk; the app's BodyEdit
  // (pass/replace/drop) is then written to the renderer-facing pipe. The
  // renderer's OnComplete is deferred until that write finishes.
  bool capturing_ = false;
  bool body_written_ = false;
  std::string captured_body_;
  std::string final_body_;  // kept alive while DataPipeProducer writes it
  mojo::ScopedDataPipeProducerHandle client_body_producer_;
  std::unique_ptr<mojo::DataPipeDrainer> body_drainer_;
  std::unique_ptr<mojo::DataPipeProducer> body_writer_;

  base::WeakPtrFactory<JuxProxyingURLLoader> weak_factory_{this};
};

}  // namespace jux

#endif  // JUX_PROXYING_URL_LOADER_FACTORY_H_
