// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxProxyingURLLoaderFactory / JuxProxyingURLLoader implementation.

#ifdef UNSAFE_BUFFERS_BUILD
#pragma allow_unsafe_buffers
#endif

#include "jux/jux_proxying_url_loader_factory.h"

#include <cstring>
#include <string>
#include <utility>
#include <vector>

#include "base/functional/bind.h"
#include "base/strings/string_number_conversions.h"
#include "base/strings/stringprintf.h"
#include "base/task/sequenced_task_runner.h"
#include "jux/jux_network_interceptor.h"
#include "mojo/public/cpp/system/data_pipe.h"
#include "mojo/public/cpp/system/string_data_source.h"
#include "net/base/net_errors.h"
#include "net/http/http_request_headers.h"
#include "net/http/http_response_headers.h"
#include "services/network/public/mojom/url_response_head.mojom.h"
#include "url/gurl.h"

namespace jux {

namespace {

// NetworkExchange action codes (mirror NetworkExchange.java).
constexpr uint8_t kAProceed = 0;
constexpr uint8_t kAProceedModified = 1;
constexpr uint8_t kABlock = 2;
constexpr uint8_t kARedirect = 3;
constexpr uint8_t kASynthetic = 4;
constexpr uint8_t kAResponseProceed = 5;
constexpr uint8_t kAReplaceBody = 6;
constexpr uint8_t kCaptureFlag = 0x80;  // OR'd onto a response-proceed action

// Maps a Chromium RequestDestination to the Java ResourceType wire code.
// MUST match MapDestination in jux_url_loader_throttle.cc.
int MapDestination(network::mojom::RequestDestination d) {
  using D = network::mojom::RequestDestination;
  switch (d) {
    case D::kDocument:
    case D::kIframe:        return 0;  // DOCUMENT
    case D::kStyle:         return 1;  // STYLESHEET
    case D::kScript:
    case D::kWorker:
    case D::kSharedWorker:
    case D::kServiceWorker: return 2;  // SCRIPT
    case D::kImage:         return 3;  // IMAGE
    case D::kFont:          return 4;  // FONT
    case D::kEmpty:         return 6;  // FETCH / XHR
    case D::kAudio:
    case D::kVideo:
    case D::kTrack:         return 7;  // MEDIA
    default:                return 9;  // OTHER
  }
}

// Little-endian reader over the decision tail (mirrors NetworkExchange's
// writeU16/writeU32/writeStr16 encoders).
struct TailReader {
  const uint8_t* b;
  size_t len;
  size_t off = 0;
  bool ok = true;
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
  std::string str16() {
    uint16_t n = u16();
    if (!ok || off + n > len) { ok = false; return {}; }
    std::string s(reinterpret_cast<const char*>(b + off), n);
    off += n;
    return s;
  }
};

}  // namespace

// ===========================================================================
// JuxProxyingURLLoaderFactory
// ===========================================================================

// static
void JuxProxyingURLLoaderFactory::CreateProxy(
    mojo::PendingReceiver<network::mojom::URLLoaderFactory> proxy_receiver,
    mojo::PendingRemote<network::mojom::URLLoaderFactory> target_remote) {
  // Owns itself; freed by MaybeDestroySelf when receivers + loaders are gone.
  new JuxProxyingURLLoaderFactory(std::move(proxy_receiver),
                                  std::move(target_remote));
}

JuxProxyingURLLoaderFactory::JuxProxyingURLLoaderFactory(
    mojo::PendingReceiver<network::mojom::URLLoaderFactory> proxy_receiver,
    mojo::PendingRemote<network::mojom::URLLoaderFactory> target_remote)
    : target_factory_(std::move(target_remote)) {
  proxy_receivers_.Add(this, std::move(proxy_receiver));
  proxy_receivers_.set_disconnect_handler(base::BindRepeating(
      &JuxProxyingURLLoaderFactory::OnProxyDisconnect, base::Unretained(this)));
  target_factory_.set_disconnect_handler(base::BindOnce(
      &JuxProxyingURLLoaderFactory::OnProxyDisconnect, base::Unretained(this)));
}

JuxProxyingURLLoaderFactory::~JuxProxyingURLLoaderFactory() = default;

void JuxProxyingURLLoaderFactory::CreateLoaderAndStart(
    mojo::PendingReceiver<network::mojom::URLLoader> loader,
    int32_t request_id,
    uint32_t options,
    const network::ResourceRequest& request,
    mojo::PendingRemote<network::mojom::URLLoaderClient> client,
    const net::MutableNetworkTrafficAnnotationTag& traffic_annotation) {
  auto proxy = std::make_unique<JuxProxyingURLLoader>(
      this, std::move(loader), request_id, options, request, std::move(client),
      traffic_annotation);
  JuxProxyingURLLoader* raw = proxy.get();
  loaders_.insert(std::move(proxy));
  raw->Start();
}

void JuxProxyingURLLoaderFactory::Clone(
    mojo::PendingReceiver<network::mojom::URLLoaderFactory> receiver) {
  proxy_receivers_.Add(this, std::move(receiver));
}

void JuxProxyingURLLoaderFactory::RemoveLoader(JuxProxyingURLLoader* loader) {
  auto it = loaders_.find(loader);
  if (it != loaders_.end()) {
    loaders_.erase(it);  // deletes the loader
  }
  MaybeDestroySelf();
}

void JuxProxyingURLLoaderFactory::OnProxyDisconnect() {
  // A factory receiver (or the target) dropped. If the renderer is fully gone,
  // tear down once outstanding loaders finish.
  if (proxy_receivers_.empty()) {
    MaybeDestroySelf();
  }
}

void JuxProxyingURLLoaderFactory::MaybeDestroySelf() {
  if (proxy_receivers_.empty() && loaders_.empty()) {
    delete this;
  }
}

// ===========================================================================
// JuxProxyingURLLoader
// ===========================================================================

JuxProxyingURLLoader::JuxProxyingURLLoader(
    JuxProxyingURLLoaderFactory* factory,
    mojo::PendingReceiver<network::mojom::URLLoader> loader_receiver,
    int32_t request_id,
    uint32_t options,
    const network::ResourceRequest& request,
    mojo::PendingRemote<network::mojom::URLLoaderClient> client,
    const net::MutableNetworkTrafficAnnotationTag& traffic_annotation)
    : factory_(factory),
      request_(request),
      request_id_(request_id),
      options_(options),
      traffic_annotation_(traffic_annotation) {
  loader_receiver_.Bind(std::move(loader_receiver));
  loader_receiver_.set_disconnect_handler(base::BindOnce(
      &JuxProxyingURLLoader::OnClientDisconnect, base::Unretained(this)));
  target_client_.Bind(std::move(client));
}

JuxProxyingURLLoader::~JuxProxyingURLLoader() = default;

uint32_t JuxProxyingURLLoader::EnsureRegistered() {
  if (!registered_) {
    JuxNetworkInterceptor* interceptor = JuxNetworkInterceptor::GetInstance();
    if (interceptor) {
      intercept_id_ = interceptor->RegisterProxy(
          weak_factory_.GetWeakPtr(),
          base::SequencedTaskRunner::GetCurrentDefault());
      registered_ = true;
    }
  }
  return intercept_id_;
}

void JuxProxyingURLLoader::Start() {
  JuxNetworkInterceptor* interceptor = JuxNetworkInterceptor::GetInstance();
  const std::string url = request_.url.spec();
  const std::string method = request_.method;
  const int type = MapDestination(request_.destination);

  if (interceptor && interceptor->armed() &&
      interceptor->MatchesRequest(url, type, method)) {
    EnsureRegistered();
    std::vector<std::string> keys;
    std::vector<std::string> values;
    for (const auto& kv : request_.headers.GetHeaderVector()) {
      keys.push_back(kv.key);
      values.push_back(kv.value);
    }
    interceptor->FireRequest(intercept_id_, type, method, url, keys, values);
    return;  // hold until ApplyDecision(phase=0)
  }
  StartReal();
}

void JuxProxyingURLLoader::StartReal() {
  if (!factory_ || !factory_->target()) {
    Finish(net::ERR_FAILED);
    return;
  }
  factory_->target()->CreateLoaderAndStart(
      real_loader_.BindNewPipeAndPassReceiver(), request_id_, options_, request_,
      real_client_receiver_.BindNewPipeAndPassRemote(), traffic_annotation_);
  real_client_receiver_.set_disconnect_handler(base::BindOnce(
      &JuxProxyingURLLoader::OnClientDisconnect, base::Unretained(this)));
}

void JuxProxyingURLLoader::ApplyDecision(uint8_t phase, uint8_t action,
                                         std::vector<uint8_t> tail) {
  if (completed_) {
    return;
  }
  if (phase == 0) {  // request phase
    switch (action) {
      case kAProceed:
        StartReal();
        break;
      case kAProceedModified:
        ApplyRequestEdits(tail);
        StartReal();
        break;
      case kABlock:
        if (target_client_) {
          target_client_->OnComplete(network::URLLoaderCompletionStatus(
              net::ERR_BLOCKED_BY_CLIENT));
        }
        Finish(net::ERR_BLOCKED_BY_CLIENT);
        break;
      case kARedirect: {
        std::string new_url(tail.begin(), tail.end());
        GURL gurl(new_url);
        if (gurl.is_valid()) {
          request_.url = gurl;
        }
        StartReal();  // transparent: fetch the new URL in place
        break;
      }
      case kASynthetic:
        ServeSynthetic(tail);
        break;
      default:
        StartReal();
        break;
    }
    return;
  }

  // response phase. The capture bit (kCaptureFlag) tees the response body to
  // Java for inspection/edit; body REPLACEMENT swaps it outright.
  uint8_t a = action & ~kCaptureFlag;
  bool capture = (action & kCaptureFlag) != 0;
  if (a == kAResponseProceed) {
    ApplyResponseEdits(tail);
    if (capture) {
      StartBodyCapture();  // forwards head now, body after the app's BodyEdit
      return;
    }
  } else if (a == kAReplaceBody) {
    ReplaceHeldBody(tail);
  }
  ForwardHeldResponse();
}

void JuxProxyingURLLoader::StartBodyCapture() {
  if (!held_head_ || !target_client_) {
    ForwardHeldResponse();
    return;
  }
  // Interpose our own body pipe: the renderer reads from `consumer`; we write
  // the edited bytes into `client_body_producer_` after the app decides.
  mojo::ScopedDataPipeProducerHandle producer;
  mojo::ScopedDataPipeConsumerHandle consumer;
  if (mojo::CreateDataPipe(nullptr, producer, consumer) != MOJO_RESULT_OK) {
    ForwardHeldResponse();  // capture failed → fall back to pass-through
    return;
  }
  client_body_producer_ = std::move(producer);
  mojo::ScopedDataPipeConsumerHandle real_body = std::move(held_body_);
  held_body_ = std::move(consumer);
  capturing_ = true;

  // Forward the (edited) head + our renderer-facing body pipe now. Do NOT use
  // ForwardHeldResponse(): it would flush a queued OnComplete before the body
  // is written.
  holding_response_ = false;
  target_client_->OnReceiveResponse(std::move(held_head_), std::move(held_body_),
                                    std::move(held_metadata_));

  if (real_body.is_valid()) {
    body_drainer_ =
        std::make_unique<mojo::DataPipeDrainer>(this, std::move(real_body));
  } else {
    OnDataComplete();  // no body → surface an empty chunk
  }
}

void JuxProxyingURLLoader::OnDataAvailable(base::span<const uint8_t> data) {
  // A captured body buffers fully in memory (the app opted in via captureBody()),
  // so guard against a runaway/huge response OOMing the engine. Past the ceiling
  // we stop accumulating — the app receives a truncated body rather than the
  // process dying. (Honoring the filter's per-request maxBody exactly is a
  // follow-up; this is the hard safety backstop.)
  constexpr size_t kMaxCapturedBody = 256u * 1024 * 1024;  // 256 MB
  if (captured_body_.size() >= kMaxCapturedBody) {
    return;
  }
  size_t room = kMaxCapturedBody - captured_body_.size();
  size_t take = data.size() < room ? data.size() : room;
  captured_body_.append(reinterpret_cast<const char*>(data.data()), take);
}

void JuxProxyingURLLoader::OnDataComplete() {
  // Surface the whole captured body to Java as a single (last) chunk and wait
  // for the BodyEdit (ApplyBodyEdit). If there's no interceptor/transport,
  // pass the original body straight through.
  JuxNetworkInterceptor* interceptor = JuxNetworkInterceptor::GetInstance();
  if (interceptor && registered_) {
    interceptor->FireBodyChunk(
        intercept_id_, /*chunk_seq=*/0, /*offset=*/0, /*last=*/true,
        base::span<const uint8_t>(
            reinterpret_cast<const uint8_t*>(captured_body_.data()),
            captured_body_.size()));
    return;  // wait for ApplyBodyEdit
  }
  ApplyBodyEdit(/*kind=*/0, {});  // pass-through
}

void JuxProxyingURLLoader::ApplyBodyEdit(uint8_t kind,
                                         std::vector<uint8_t> replacement) {
  if (body_written_ || !client_body_producer_.is_valid()) {
    return;
  }
  // kind: 0=pass, 1=replace, 2=drop.
  if (kind == 1) {
    final_body_.assign(reinterpret_cast<const char*>(replacement.data()),
                       replacement.size());
  } else if (kind == 2) {
    final_body_.clear();
  } else {
    // Pass-through: move (don't copy) the captured body into final_body_ — it's
    // not read again, and a copy would double peak memory for large bodies.
    final_body_ = std::move(captured_body_);
  }
  body_writer_ =
      std::make_unique<mojo::DataPipeProducer>(std::move(client_body_producer_));
  // StringDataSource keeps a view into final_body_, which outlives the write.
  body_writer_->Write(
      std::make_unique<mojo::StringDataSource>(
          base::span<const char>(final_body_.data(), final_body_.size()),
          mojo::StringDataSource::AsyncWritingMode::
              STRING_STAYS_VALID_UNTIL_COMPLETION),
      base::BindOnce(&JuxProxyingURLLoader::OnBodyWriteDone,
                     weak_factory_.GetWeakPtr()));
}

void JuxProxyingURLLoader::OnBodyWriteDone(MojoResult result) {
  body_written_ = true;
  // Don't destroy body_writer_ (the DataPipeProducer invoking this callback) or
  // `this` from inside the callback — defer the teardown one task.
  base::SequencedTaskRunner::GetCurrentDefault()->PostTask(
      FROM_HERE, base::BindOnce(&JuxProxyingURLLoader::FinishCapture,
                                weak_factory_.GetWeakPtr()));
}

void JuxProxyingURLLoader::FinishCapture() {
  body_writer_.reset();  // close producer → renderer sees EOF (now safe)
  if (target_client_) {
    target_client_->OnComplete(
        pending_complete_.value_or(network::URLLoaderCompletionStatus(net::OK)));
  }
  Finish(pending_complete_ ? pending_complete_->error_code : net::OK);
}

void JuxProxyingURLLoader::ReplaceHeldBody(const std::vector<uint8_t>& body) {
  if (!held_head_) {
    return;
  }
  mojo::ScopedDataPipeProducerHandle producer;
  mojo::ScopedDataPipeConsumerHandle consumer;
  uint32_t cap = body.empty() ? 1u : static_cast<uint32_t>(body.size());
  if (mojo::CreateDataPipe(cap, producer, consumer) != MOJO_RESULT_OK) {
    return;  // keep the original body on failure
  }
  if (!body.empty()) {
    size_t written = body.size();
    producer->WriteData(base::span<const uint8_t>(body),
                        MOJO_WRITE_DATA_FLAG_ALL_OR_NONE, written);
  }
  producer.reset();  // close → renderer sees EOF after the replacement bytes
  held_body_ = std::move(consumer);
  held_head_->content_length = static_cast<int64_t>(body.size());
  if (held_head_->headers) {
    held_head_->headers->SetHeader("Content-Length",
                                   base::NumberToString(body.size()));
  }
}

void JuxProxyingURLLoader::ApplyRequestEdits(const std::vector<uint8_t>& tail) {
  TailReader r{tail.data(), tail.size()};
  uint16_t set_count = r.u16();
  for (uint16_t i = 0; i < set_count && r.ok; ++i) {
    std::string name = r.str16();
    std::string value = r.str16();
    if (r.ok && !name.empty()) {
      request_.headers.SetHeader(name, value);
    }
  }
  uint16_t rm_count = r.u16();
  for (uint16_t i = 0; i < rm_count && r.ok; ++i) {
    std::string name = r.str16();
    if (r.ok && !name.empty()) {
      request_.headers.RemoveHeader(name);
    }
  }
  std::string method = r.str16();
  if (r.ok && !method.empty()) {
    request_.method = method;
  }
  // status/reason (rest of encodeEdits) are response-phase only — ignored here.
}

void JuxProxyingURLLoader::ServeSynthetic(const std::vector<uint8_t>& tail) {
  TailReader r{tail.data(), tail.size()};
  uint32_t status = r.u32();
  std::string reason = r.str16();
  if (status == 0) {
    status = 200;
  }
  if (reason.empty()) {
    reason = "OK";
  }
  auto head = network::mojom::URLResponseHead::New();
  auto headers = base::MakeRefCounted<net::HttpResponseHeaders>(std::string());
  headers->ReplaceStatusLine(
      base::StringPrintf("HTTP/1.1 %u %s", status, reason.c_str()));
  uint16_t hcount = r.u16();
  for (uint16_t i = 0; i < hcount && r.ok; ++i) {
    std::string name = r.str16();
    std::string value = r.str16();
    if (r.ok && !name.empty()) {
      headers->AddHeader(name, value);
    }
  }
  uint32_t body_len = r.u32();
  std::vector<uint8_t> body;
  if (r.ok && body_len > 0 && r.off + body_len <= r.len) {
    body.assign(tail.begin() + r.off, tail.begin() + r.off + body_len);
  }

  std::string mime;
  std::string charset;
  bool had_charset = false;
  headers->GetMimeTypeAndCharset(&mime, &charset);
  had_charset = !charset.empty();
  head->headers = headers;
  head->mime_type = mime;
  head->charset = had_charset ? charset : head->charset;
  head->content_length = static_cast<int64_t>(body.size());

  // Body pipe sized to the (app-controlled, typically small) synthetic body.
  mojo::ScopedDataPipeProducerHandle producer;
  mojo::ScopedDataPipeConsumerHandle consumer;
  uint32_t cap = body.empty() ? 1u : static_cast<uint32_t>(body.size());
  if (mojo::CreateDataPipe(cap, producer, consumer) != MOJO_RESULT_OK) {
    Finish(net::ERR_INSUFFICIENT_RESOURCES);
    return;
  }
  if (target_client_) {
    target_client_->OnReceiveResponse(std::move(head), std::move(consumer),
                                      std::nullopt);
  }
  if (!body.empty()) {
    size_t written = body.size();
    // One-shot write; the pipe is sized to the body (ALL_OR_NONE).
    producer->WriteData(base::span<const uint8_t>(body),
                        MOJO_WRITE_DATA_FLAG_ALL_OR_NONE, written);
  }
  producer.reset();  // close → consumer sees EOF
  if (target_client_) {
    target_client_->OnComplete(network::URLLoaderCompletionStatus(net::OK));
  }
  Finish(net::OK);
}

void JuxProxyingURLLoader::ApplyResponseEdits(const std::vector<uint8_t>& tail) {
  if (!held_head_ || !held_head_->headers) {
    return;
  }
  net::HttpResponseHeaders* headers = held_head_->headers.get();
  TailReader r{tail.data(), tail.size()};
  uint16_t set_count = r.u16();
  std::vector<std::pair<std::string, std::string>> sets;
  for (uint16_t i = 0; i < set_count && r.ok; ++i) {
    std::string name = r.str16();
    std::string value = r.str16();
    if (r.ok && !name.empty()) {
      sets.emplace_back(std::move(name), std::move(value));
    }
  }
  uint16_t rm_count = r.u16();
  for (uint16_t i = 0; i < rm_count && r.ok; ++i) {
    std::string name = r.str16();
    if (r.ok && !name.empty()) {
      headers->RemoveHeader(name);
    }
  }
  // Apply sets after removes so a set wins (SetHeader replaces existing).
  for (auto& kv : sets) {
    headers->SetHeader(kv.first, kv.second);
  }
  std::string method = r.str16();  // request-only; ignored for response
  (void)method;
  uint32_t status = r.u32();
  std::string reason = r.str16();
  if (r.ok && status != 0) {
    headers->ReplaceStatusLine(base::StringPrintf(
        "HTTP/1.1 %u %s", status, reason.empty() ? "" : reason.c_str()));
  }
}

void JuxProxyingURLLoader::FireResponseEvent() {
  JuxNetworkInterceptor* interceptor = JuxNetworkInterceptor::GetInstance();
  if (!interceptor || !held_head_) {
    return;
  }
  int status = held_head_->headers ? held_head_->headers->response_code() : 0;
  std::string mime = held_head_->mime_type;
  int64_t content_length = held_head_->content_length;
  std::vector<std::string> keys;
  std::vector<std::string> values;
  if (held_head_->headers) {
    size_t iter = 0;
    std::string name;
    std::string value;
    while (held_head_->headers->EnumerateHeaderLines(&iter, &name, &value)) {
      keys.push_back(name);
      values.push_back(value);
    }
  }
  interceptor->FireResponse(intercept_id_, status, mime, content_length, keys,
                            values);
}

void JuxProxyingURLLoader::ForwardHeldResponse() {
  if (!holding_response_) {
    return;
  }
  holding_response_ = false;
  if (target_client_) {
    target_client_->OnReceiveResponse(std::move(held_head_),
                                      std::move(held_body_),
                                      std::move(held_metadata_));
  }
  if (pending_complete_) {
    network::URLLoaderCompletionStatus s = *pending_complete_;
    pending_complete_.reset();
    if (target_client_) {
      target_client_->OnComplete(s);
    }
    Finish(s.error_code);
  }
}

// ── network::mojom::URLLoader (renderer → us) ──────────────────────────────

void JuxProxyingURLLoader::FollowRedirect(
    const std::vector<std::string>& removed_headers,
    const net::HttpRequestHeaders& modified_headers,
    const net::HttpRequestHeaders& modified_cors_exempt_headers,
    const std::optional<GURL>& new_url) {
  if (real_loader_) {
    real_loader_->FollowRedirect(removed_headers, modified_headers,
                                 modified_cors_exempt_headers, new_url);
  }
}

void JuxProxyingURLLoader::SetPriority(net::RequestPriority priority,
                                       int32_t intra_priority_value) {
  if (real_loader_) {
    real_loader_->SetPriority(priority, intra_priority_value);
  }
}

// ── network::mojom::URLLoaderClient (real loader → us) ─────────────────────

void JuxProxyingURLLoader::OnReceiveEarlyHints(
    network::mojom::EarlyHintsPtr early_hints) {
  if (target_client_) {
    target_client_->OnReceiveEarlyHints(std::move(early_hints));
  }
}

void JuxProxyingURLLoader::OnReceiveResponse(
    network::mojom::URLResponseHeadPtr head,
    mojo::ScopedDataPipeConsumerHandle body,
    std::optional<mojo_base::BigBuffer> cached_metadata) {
  JuxNetworkInterceptor* interceptor = JuxNetworkInterceptor::GetInstance();
  const std::string url = request_.url.spec();
  const std::string method = request_.method;
  const int type = MapDestination(request_.destination);

  if (interceptor && interceptor->armed() &&
      interceptor->MatchesResponse(url, type, method)) {
    EnsureRegistered();
    held_head_ = std::move(head);
    held_body_ = std::move(body);
    held_metadata_ = std::move(cached_metadata);
    holding_response_ = true;
    FireResponseEvent();
    return;  // hold until ApplyDecision(phase=1)
  }
  if (target_client_) {
    target_client_->OnReceiveResponse(std::move(head), std::move(body),
                                      std::move(cached_metadata));
  }
}

void JuxProxyingURLLoader::OnReceiveRedirect(
    const net::RedirectInfo& redirect_info,
    network::mojom::URLResponseHeadPtr head) {
  if (target_client_) {
    target_client_->OnReceiveRedirect(redirect_info, std::move(head));
  }
}

void JuxProxyingURLLoader::OnUploadProgress(int64_t current_position,
                                            int64_t total_size,
                                            OnUploadProgressCallback callback) {
  if (target_client_) {
    target_client_->OnUploadProgress(current_position, total_size,
                                     std::move(callback));
  } else {
    std::move(callback).Run();
  }
}

void JuxProxyingURLLoader::OnTransferSizeUpdated(int32_t transfer_size_diff) {
  if (target_client_) {
    target_client_->OnTransferSizeUpdated(transfer_size_diff);
  }
}

void JuxProxyingURLLoader::OnComplete(
    const network::URLLoaderCompletionStatus& status) {
  if (holding_response_) {
    pending_complete_ = status;  // deferred until the response is forwarded
    return;
  }
  if (capturing_ && !body_written_) {
    // The network body is done, but we haven't written the (edited) body to the
    // renderer yet — defer OnComplete until OnBodyWriteDone.
    pending_complete_ = status;
    return;
  }
  if (target_client_) {
    target_client_->OnComplete(status);
  }
  Finish(status.error_code);
}

void JuxProxyingURLLoader::OnClientDisconnect() {
  Finish(net::ERR_ABORTED);
}

void JuxProxyingURLLoader::Finish(int net_error) {
  if (completed_) {
    return;
  }
  completed_ = true;
  if (registered_) {
    JuxNetworkInterceptor* interceptor = JuxNetworkInterceptor::GetInstance();
    if (interceptor) {
      interceptor->Complete(intercept_id_, net_error);
    }
  }
  // Deletes `this` (and possibly the factory). Must be the last statement;
  // no member access follows.
  if (factory_) {
    factory_->RemoveLoader(this);
  }
}

}  // namespace jux
