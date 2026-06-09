// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// Implementation of JuxWidgetDelegate — intercepts window close requests
// and forwards them to Java via the event ring buffer.

#include "jux/jux_widget_delegate.h"

#include "base/logging.h"
#include "jux/jux_event_types.h"
#include "jux/jux_ipc.h"
#include "jux/jux_ring_buffer.h"

namespace jux {

JuxWidgetDelegate::JuxWidgetDelegate(EventWriter* evt_writer,
                                     ipc::SharedMemoryChannel* channel)
    : evt_writer_(evt_writer),
      channel_(channel) {
  // Standard window chrome configuration.
  SetCanResize(true);
  SetCanMaximize(true);
  SetCanMinimize(true);
  SetTitle(u"Jux Window");
}

JuxWidgetDelegate::~JuxWidgetDelegate() = default;

void JuxWidgetDelegate::AllowClose() {
  close_allowed_ = true;
}

bool JuxWidgetDelegate::OnCloseRequested(
    views::Widget::ClosedReason reason) {
  if (close_allowed_) {
    // Java has authorized the close via CMD_DESTROY_WINDOW.
    return true;
  }

  // Forward the close request to Java. Java will decide whether to
  // proceed (sending CMD_DESTROY_WINDOW) or cancel the close.
  if (evt_writer_ && channel_) {
    evt_writer_->WriteEvent(events::kWindowCloseRequest,
                            channel_->window_id());
    VLOG(1) << "Close requested — forwarded to Java (window_id="
              << channel_->window_id() << ")";
  }

  // Deny the close — wait for Java to send CMD_DESTROY_WINDOW.
  return false;
}

}  // namespace jux
