// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxPermissionManager — surfaces page permission requests (geolocation,
// notifications, camera/mic, clipboard, MIDI, screen capture, …) to Java
// instead of auto-denying them.
//
// One BrowserContext (and one WebContents) per engine process, so a single
// process-global instance is sufficient. Lives on the browser UI thread.
//
// Flow mirrors the JS-dialog template: RequestPermissions stashes Chromium's
// result callback under a permId, fires on_permission_requested, and returns
// without running it; Java answers via JuxRespondPermission → Respond(), which
// builds the PermissionResult vector and runs the callback. Synchronous status
// queries return ASK so the request path is taken.

#ifndef JUX_PERMISSION_MANAGER_H_
#define JUX_PERMISSION_MANAGER_H_

#include <cstdint>
#include <map>
#include <vector>

#include "base/functional/callback.h"
#include "content/public/browser/permission_controller_delegate.h"
#include "content/public/browser/permission_result.h"
#include "third_party/blink/public/mojom/permissions/permission_status.mojom.h"

namespace jux {

class JuxPermissionManager : public content::PermissionControllerDelegate {
 public:
  JuxPermissionManager();
  ~JuxPermissionManager() override;

  JuxPermissionManager(const JuxPermissionManager&) = delete;
  JuxPermissionManager& operator=(const JuxPermissionManager&) = delete;

  // The process-global instance (one BrowserContext per process). Used by
  // JuxRespondPermission to deliver the Java answer.
  static JuxPermissionManager* GetInstance();

  // content::PermissionControllerDelegate:
  void RequestPermissions(
      content::RenderFrameHost* render_frame_host,
      const content::PermissionRequestDescription& request_description,
      base::OnceCallback<void(const std::vector<content::PermissionResult>&)>
          callback) override;
  void RequestPermissionsFromCurrentDocument(
      content::RenderFrameHost* render_frame_host,
      const content::PermissionRequestDescription& request_description,
      base::OnceCallback<void(const std::vector<content::PermissionResult>&)>
          callback) override;
  blink::mojom::PermissionStatus GetPermissionStatus(
      const blink::mojom::PermissionDescriptorPtr& permission,
      const GURL& requesting_origin,
      const GURL& embedding_origin) override;
  content::PermissionResult GetPermissionResultForOriginWithoutContext(
      const blink::mojom::PermissionDescriptorPtr& permission_descriptor,
      const url::Origin& requesting_origin,
      const url::Origin& embedding_origin) override;
  content::PermissionResult GetPermissionResultForCurrentDocument(
      const blink::mojom::PermissionDescriptorPtr& permission_descriptor,
      content::RenderFrameHost* render_frame_host,
      bool should_include_device_status) override;
  content::PermissionResult GetPermissionResultForWorker(
      const blink::mojom::PermissionDescriptorPtr& permission_descriptor,
      content::RenderProcessHost* render_process_host,
      const GURL& worker_origin) override;
  content::PermissionResult GetPermissionResultForEmbeddedRequester(
      const blink::mojom::PermissionDescriptorPtr& permission_descriptor,
      content::RenderFrameHost* render_frame_host,
      const url::Origin& requesting_origin) override;
  void ResetPermission(blink::PermissionType permission,
                       const GURL& requesting_origin,
                       const GURL& embedding_origin) override;

  // Runs the stashed callback for perm_id with `granted` applied to every
  // permission in the original request (browser UI thread).
  void Respond(uint32_t perm_id, bool granted);

 private:
  // Out-of-line ctor/dtor required by chromium-style (move-only callback member).
  struct Pending {
    Pending();
    ~Pending();

    base::OnceCallback<void(const std::vector<content::PermissionResult>&)>
        callback;
    size_t count = 1;
  };

  // Common entry for both Request* methods.
  void HandleRequest(
      const content::PermissionRequestDescription& request_description,
      base::OnceCallback<void(const std::vector<content::PermissionResult>&)>
          callback);

  uint32_t next_perm_id_ = 1;
  std::map<uint32_t, Pending> pending_;
};

}  // namespace jux

#endif  // JUX_PERMISSION_MANAGER_H_
