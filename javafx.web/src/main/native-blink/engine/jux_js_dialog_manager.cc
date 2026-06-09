// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxJsDialogManager implementation.

#include "jux/jux_js_dialog_manager.h"

#include <utility>

#include "base/strings/utf_string_conversions.h"

namespace jux {

JuxJsDialogManager::JuxJsDialogManager(JuxWebContentsHandle handle,
                                       const JuxCallbacks* callbacks)
    : handle_(handle), callbacks_(callbacks) {}

JuxJsDialogManager::~JuxJsDialogManager() {
  // Any still-pending dialog must be released so the renderer is never left
  // suspended forever when the manager is torn down.
  for (auto& entry : pending_) {
    if (entry.second) {
      std::move(entry.second).Run(false, std::u16string());
    }
  }
  pending_.clear();
}

uint32_t JuxJsDialogManager::Surface(uint32_t dialog_type,
                                     const std::u16string& message_text,
                                     const std::u16string& default_prompt_text,
                                     DialogClosedCallback callback) {
  uint32_t id = next_dialog_id_++;
  pending_[id] = std::move(callback);

  if (callbacks_ && callbacks_->on_dialog_requested) {
    std::string message = base::UTF16ToUTF8(message_text);
    std::string def = base::UTF16ToUTF8(default_prompt_text);
    callbacks_->on_dialog_requested(
        handle_, id, dialog_type,
        message.c_str(), static_cast<uint32_t>(message.size()),
        def.c_str(), static_cast<uint32_t>(def.size()));
  }
  // Deliberately do NOT run the callback here — Chromium keeps the renderer's
  // JS suspended until Respond() runs it.
  return id;
}

void JuxJsDialogManager::RunJavaScriptDialog(
    content::WebContents* web_contents,
    content::RenderFrameHost* render_frame_host,
    content::JavaScriptDialogType dialog_type,
    const std::u16string& message_text,
    const std::u16string& default_prompt_text,
    DialogClosedCallback callback,
    bool* did_suppress_message) {
  if (did_suppress_message) {
    *did_suppress_message = false;
  }
  uint32_t type = kTypeAlert;
  switch (dialog_type) {
    case content::JAVASCRIPT_DIALOG_TYPE_ALERT:   type = kTypeAlert; break;
    case content::JAVASCRIPT_DIALOG_TYPE_CONFIRM: type = kTypeConfirm; break;
    case content::JAVASCRIPT_DIALOG_TYPE_PROMPT:  type = kTypePrompt; break;
  }
  Surface(type, message_text, default_prompt_text, std::move(callback));
}

void JuxJsDialogManager::RunBeforeUnloadDialog(
    content::WebContents* web_contents,
    content::RenderFrameHost* render_frame_host,
    bool is_reload,
    DialogClosedCallback callback) {
  // beforeunload has no author-supplied message in modern browsers; the Java
  // side maps it to confirmHandler, so an empty message/default is fine.
  Surface(kTypeBeforeUnload, std::u16string(), std::u16string(),
          std::move(callback));
}

bool JuxJsDialogManager::HandleJavaScriptDialog(
    content::WebContents* web_contents,
    bool accept,
    const std::u16string* prompt_override) {
  // Programmatic accept/dismiss (e.g. from automation) is not supported; Java
  // drives every dialog explicitly through Respond().
  return false;
}

void JuxJsDialogManager::CancelDialogs(content::WebContents* web_contents,
                                       bool reset_state) {
  // The page is navigating away / closing — release every pending dialog as
  // cancelled so the renderer is never left suspended.
  auto pending = std::move(pending_);
  pending_.clear();
  for (auto& entry : pending) {
    if (entry.second) {
      std::move(entry.second).Run(false, std::u16string());
    }
  }
}

void JuxJsDialogManager::Respond(uint32_t dialog_id, bool accepted,
                                 const std::string& text) {
  auto it = pending_.find(dialog_id);
  if (it == pending_.end()) {
    return;  // unknown id: already answered, or cancelled on teardown
  }
  DialogClosedCallback callback = std::move(it->second);
  pending_.erase(it);
  if (callback) {
    std::move(callback).Run(accepted, base::UTF8ToUTF16(text));
  }
}

}  // namespace jux
