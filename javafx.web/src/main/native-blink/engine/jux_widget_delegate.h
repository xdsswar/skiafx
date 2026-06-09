// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxWidgetDelegate — custom WidgetDelegate that intercepts the window
// close button (X) and forwards the request to Java via the event ring
// buffer instead of allowing immediate destruction.
//
// Without this, clicking X on the aura-managed window triggers
// Widget::Close() → default WidgetDelegate accepts → widget destroyed
// immediately, bypassing Java's close lifecycle. With this delegate,
// the close is deferred until Java sends DESTROY_WINDOW.

#ifndef JUX_WIDGET_DELEGATE_H_
#define JUX_WIDGET_DELEGATE_H_

#include "base/memory/raw_ptr.h"
#include "ui/views/widget/widget_delegate.h"

namespace jux {

class EventWriter;

namespace ipc {
class SharedMemoryChannel;
}

// Custom WidgetDelegate that intercepts close requests from the window
// chrome (X button) and forwards them to Java via the IPC event ring
// buffer. The widget is only allowed to close after Java responds with
// CMD_DESTROY_WINDOW.
//
// Thread safety: all methods must be called on the browser UI thread.
class JuxWidgetDelegate : public views::WidgetDelegate {
 public:
  JuxWidgetDelegate(EventWriter* evt_writer,
                    ipc::SharedMemoryChannel* channel);
  ~JuxWidgetDelegate() override;

  JuxWidgetDelegate(const JuxWidgetDelegate&) = delete;
  JuxWidgetDelegate& operator=(const JuxWidgetDelegate&) = delete;

  // Permits the next close attempt to proceed. Called by the command
  // dispatcher when it receives CMD_DESTROY_WINDOW from Java.
  void AllowClose();

  // views::WidgetDelegate overrides:
  bool OnCloseRequested(views::Widget::ClosedReason reason) override;

 private:
  // Event writer for sending WINDOW_CLOSE_REQUEST to Java.
  raw_ptr<EventWriter> evt_writer_;

  // Shared memory channel — provides the window ID for events.
  raw_ptr<ipc::SharedMemoryChannel> channel_;

  // Whether the close has been authorized by Java (DESTROY_WINDOW received).
  bool close_allowed_ = false;
};

}  // namespace jux

#endif  // JUX_WIDGET_DELEGATE_H_
