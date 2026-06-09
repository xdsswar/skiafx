// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxPermissionManager implementation.

#ifdef UNSAFE_BUFFERS_BUILD
#pragma allow_unsafe_buffers
#endif

#include "jux/jux_permission_manager.h"

#include <cstring>
#include <set>
#include <utility>

#include "content/public/browser/permission_request_description.h"
#include "jux/jux_event_types.h"
#include "jux/jux_ipc.h"
#include "jux/jux_ring_buffer.h"
#include "third_party/blink/public/common/permissions/permission_utils.h"
#include "url/gurl.h"

namespace jux {

// Event transport globals (defined in jux_command_dispatch.cc). The permission
// manager writes its request event directly through the event writer rather
// than via a JuxCallbacks fn-ptr (g_callbacks is file-local to the API unit and
// the BrowserContext that owns us has no callbacks pointer to pass in).
extern EventWriter* g_callback_evt_writer;
extern ipc::SharedMemoryChannel* g_callback_channel;

namespace {

JuxPermissionManager* g_instance = nullptr;

// Java-side PermissionType wire codes (mirror PermissionType.java).
constexpr int kGeolocation = 0;
constexpr int kNotifications = 1;
constexpr int kCamera = 2;
constexpr int kMicrophone = 3;
constexpr int kCameraAndMic = 4;
constexpr int kClipboardRead = 5;
constexpr int kMidi = 6;
constexpr int kMidiSysex = 7;
constexpr int kScreenCapture = 8;
constexpr int kPersistentStorage = 9;
constexpr int kSensors = 10;
constexpr int kIdleDetection = 11;
constexpr int kWindowManagement = 12;
constexpr int kUnknown = -1;

int MapPermission(blink::PermissionType t) {
  using PT = blink::PermissionType;
  switch (t) {
    case PT::GEOLOCATION:
    case PT::GEOLOCATION_APPROXIMATE:    return kGeolocation;
    case PT::NOTIFICATIONS:              return kNotifications;
    case PT::VIDEO_CAPTURE:              return kCamera;
    case PT::AUDIO_CAPTURE:              return kMicrophone;
    case PT::CLIPBOARD_READ_WRITE:       return kClipboardRead;
    case PT::MIDI:                       return kMidi;
    case PT::MIDI_SYSEX:                 return kMidiSysex;
    case PT::DISPLAY_CAPTURE:
    case PT::CAPTURED_SURFACE_CONTROL:   return kScreenCapture;
    case PT::PERSISTENT_STORAGE:         return kPersistentStorage;
    case PT::SENSORS:                    return kSensors;
    case PT::IDLE_DETECTION:             return kIdleDetection;
    case PT::WINDOW_MANAGEMENT:          return kWindowManagement;
    default:                             return kUnknown;
  }
}

void PutU32(uint8_t* b, size_t off, uint32_t v) {
  std::memcpy(b + off, &v, sizeof(v));
}

}  // namespace

JuxPermissionManager::Pending::Pending() = default;
JuxPermissionManager::Pending::~Pending() = default;

JuxPermissionManager::JuxPermissionManager() {
  g_instance = this;
}

JuxPermissionManager::~JuxPermissionManager() {
  // Release any still-pending request as denied so the renderer never hangs.
  for (auto& entry : pending_) {
    if (entry.second.callback) {
      std::vector<content::PermissionResult> results(
          entry.second.count,
          content::PermissionResult(blink::mojom::PermissionStatus::DENIED));
      std::move(entry.second.callback).Run(results);
    }
  }
  pending_.clear();
  if (g_instance == this) {
    g_instance = nullptr;
  }
}

JuxPermissionManager* JuxPermissionManager::GetInstance() {
  return g_instance;
}

void JuxPermissionManager::HandleRequest(
    const content::PermissionRequestDescription& request_description,
    base::OnceCallback<void(const std::vector<content::PermissionResult>&)>
        callback) {
  const auto& descriptors = request_description.permissions;
  size_t count = descriptors.size();

  // Resolve the wire type to surface. A combined audio+video request (the
  // getUserMedia case) collapses to CAMERA_AND_MICROPHONE; otherwise the first
  // recognized permission is reported.
  std::set<int> codes;
  int primary = kUnknown;
  for (const auto& d : descriptors) {
    std::optional<blink::PermissionType> pt =
        blink::MaybePermissionDescriptorToPermissionType(d);
    int code = pt.has_value() ? MapPermission(*pt) : kUnknown;
    codes.insert(code);
    if (primary == kUnknown) {
      primary = code;
    }
  }
  int wire_type = primary;
  if (codes.count(kCamera) && codes.count(kMicrophone)) {
    wire_type = kCameraAndMic;
  }

  uint32_t perm_id = next_perm_id_++;
  Pending& entry = pending_[perm_id];
  entry.callback = std::move(callback);
  entry.count = (count == 0 ? 1 : count);

  if (!g_callback_evt_writer || !g_callback_channel) {
    return;  // no transport — leave pending (released on teardown as denied)
  }
  std::string origin = request_description.requesting_origin.spec();
  uint32_t origin_len = static_cast<uint32_t>(origin.size());
  // Payload (after windowId): [permId:4][permType:4][originLen:4][origin]
  std::vector<uint8_t> p(4 + 4 + 4 + origin_len);
  PutU32(p.data(), 0, perm_id);
  PutU32(p.data(), 4, static_cast<uint32_t>(wire_type));
  PutU32(p.data(), 8, origin_len);
  if (origin_len > 0) {
    std::memcpy(p.data() + 12, origin.data(), origin_len);
  }
  g_callback_evt_writer->WriteEvent(
      events::kPermissionRequested, g_callback_channel->window_id(),
      base::span<const uint8_t>(p.data(), p.size()));
}

void JuxPermissionManager::RequestPermissions(
    content::RenderFrameHost* render_frame_host,
    const content::PermissionRequestDescription& request_description,
    base::OnceCallback<void(const std::vector<content::PermissionResult>&)>
        callback) {
  HandleRequest(request_description, std::move(callback));
}

void JuxPermissionManager::RequestPermissionsFromCurrentDocument(
    content::RenderFrameHost* render_frame_host,
    const content::PermissionRequestDescription& request_description,
    base::OnceCallback<void(const std::vector<content::PermissionResult>&)>
        callback) {
  HandleRequest(request_description, std::move(callback));
}

void JuxPermissionManager::Respond(uint32_t perm_id, bool granted) {
  auto it = pending_.find(perm_id);
  if (it == pending_.end()) {
    return;
  }
  base::OnceCallback<void(const std::vector<content::PermissionResult>&)> callback =
      std::move(it->second.callback);
  size_t count = it->second.count;
  pending_.erase(it);
  if (!callback) {
    return;
  }
  blink::mojom::PermissionStatus status =
      granted ? blink::mojom::PermissionStatus::GRANTED
              : blink::mojom::PermissionStatus::DENIED;
  std::vector<content::PermissionResult> results(
      count, content::PermissionResult(status));
  std::move(callback).Run(results);
}

// ── Synchronous status queries — undecided, so report ASK ───────────────

blink::mojom::PermissionStatus JuxPermissionManager::GetPermissionStatus(
    const blink::mojom::PermissionDescriptorPtr& permission,
    const GURL& requesting_origin,
    const GURL& embedding_origin) {
  return blink::mojom::PermissionStatus::ASK;
}

content::PermissionResult
JuxPermissionManager::GetPermissionResultForOriginWithoutContext(
    const blink::mojom::PermissionDescriptorPtr& permission_descriptor,
    const url::Origin& requesting_origin,
    const url::Origin& embedding_origin) {
  return content::PermissionResult(blink::mojom::PermissionStatus::ASK);
}

content::PermissionResult
JuxPermissionManager::GetPermissionResultForCurrentDocument(
    const blink::mojom::PermissionDescriptorPtr& permission_descriptor,
    content::RenderFrameHost* render_frame_host,
    bool should_include_device_status) {
  return content::PermissionResult(blink::mojom::PermissionStatus::ASK);
}

content::PermissionResult JuxPermissionManager::GetPermissionResultForWorker(
    const blink::mojom::PermissionDescriptorPtr& permission_descriptor,
    content::RenderProcessHost* render_process_host,
    const GURL& worker_origin) {
  return content::PermissionResult(blink::mojom::PermissionStatus::ASK);
}

content::PermissionResult
JuxPermissionManager::GetPermissionResultForEmbeddedRequester(
    const blink::mojom::PermissionDescriptorPtr& permission_descriptor,
    content::RenderFrameHost* render_frame_host,
    const url::Origin& requesting_origin) {
  return content::PermissionResult(blink::mojom::PermissionStatus::ASK);
}

void JuxPermissionManager::ResetPermission(blink::PermissionType permission,
                                           const GURL& requesting_origin,
                                           const GURL& embedding_origin) {
  // No persistent permission store — nothing to reset.
}

}  // namespace jux
