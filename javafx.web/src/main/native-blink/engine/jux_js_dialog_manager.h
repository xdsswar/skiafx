// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxJsDialogManager — surfaces page JavaScript dialogs (alert / confirm /
// prompt / beforeunload) to the Java side instead of letting Chromium
// auto-cancel them (which is what happens when an embedder provides no
// JavaScriptDialogManager).
//
// Flow (mirrors docs/BLINK_INTEGRATION.md "JavaScript dialogs"):
//   1. Chromium calls RunJavaScriptDialog / RunBeforeUnloadDialog on the
//      browser UI thread when page JS opens a dialog.
//   2. We stash Chromium's DialogClosedCallback under a monotonic dialogId
//      and fire on_dialog_requested to Java *without* invoking the callback —
//      so Chromium keeps the renderer's JS suspended ("everything waits").
//   3. Java answers via JuxRespondDialog(handle, dialogId, accepted, text),
//      which routes to Respond() and runs the stashed callback, resuming JS.
//
// One WebContents per engine process, so per-instance (not global) state is
// sufficient. Lives on (and is only touched from) the browser UI thread.

#ifndef JUX_JS_DIALOG_MANAGER_H_
#define JUX_JS_DIALOG_MANAGER_H_

#include <cstdint>
#include <map>
#include <string>

#include "base/functional/callback.h"  // full base::OnceCallback (the map value type)
#include "base/memory/raw_ptr.h"
#include "content/public/browser/javascript_dialog_manager.h"
#include "jux/jux_engine_api.h"

namespace jux {

class JuxJsDialogManager : public content::JavaScriptDialogManager {
 public:
  JuxJsDialogManager(JuxWebContentsHandle handle, const JuxCallbacks* callbacks);
  ~JuxJsDialogManager() override;

  JuxJsDialogManager(const JuxJsDialogManager&) = delete;
  JuxJsDialogManager& operator=(const JuxJsDialogManager&) = delete;

  // content::JavaScriptDialogManager:
  void RunJavaScriptDialog(content::WebContents* web_contents,
                           content::RenderFrameHost* render_frame_host,
                           content::JavaScriptDialogType dialog_type,
                           const std::u16string& message_text,
                           const std::u16string& default_prompt_text,
                           DialogClosedCallback callback,
                           bool* did_suppress_message) override;
  void RunBeforeUnloadDialog(content::WebContents* web_contents,
                             content::RenderFrameHost* render_frame_host,
                             bool is_reload,
                             DialogClosedCallback callback) override;
  bool HandleJavaScriptDialog(content::WebContents* web_contents,
                              bool accept,
                              const std::u16string* prompt_override) override;
  void CancelDialogs(content::WebContents* web_contents,
                     bool reset_state) override;

  // Runs the continuation stashed under dialog_id (browser UI thread). No-op if
  // the id is unknown (already answered, or cancelled on teardown).
  void Respond(uint32_t dialog_id, bool accepted, const std::string& text);

 private:
  // dialogType wire values shared with the Java side.
  static constexpr uint32_t kTypeAlert = 0;
  static constexpr uint32_t kTypeConfirm = 1;
  static constexpr uint32_t kTypePrompt = 2;
  static constexpr uint32_t kTypeBeforeUnload = 3;

  // Stashes the callback and fires on_dialog_requested. Returns the new id.
  uint32_t Surface(uint32_t dialog_type,
                   const std::u16string& message_text,
                   const std::u16string& default_prompt_text,
                   DialogClosedCallback callback);

  JuxWebContentsHandle handle_;
  raw_ptr<const JuxCallbacks> callbacks_;
  uint32_t next_dialog_id_ = 1;
  std::map<uint32_t, DialogClosedCallback> pending_;
};

}  // namespace jux

#endif  // JUX_JS_DIALOG_MANAGER_H_
