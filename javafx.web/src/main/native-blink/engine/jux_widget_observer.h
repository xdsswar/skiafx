// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxWidgetObserver — observes the views::Widget for geometry, focus,
// and state changes, and writes the corresponding events to the IPC
// event ring buffer for Java.
//
// Replaces the dead WndProc approach that was never attached to the
// aura HWND. Using views::WidgetObserver is the Chromium-native way
// to observe widget state changes — it works on all platforms and
// integrates correctly with the views/aura compositor pipeline.
//
// Thread safety: all callbacks fire on the browser UI thread (same
// thread that owns the EventWriter), so no cross-thread issues.

#ifndef JUX_WIDGET_OBSERVER_H_
#define JUX_WIDGET_OBSERVER_H_

#include "base/memory/raw_ptr.h"
#include "ui/gfx/geometry/rect.h"
#include "ui/views/widget/widget_observer.h"

namespace jux {

class EventWriter;

namespace ipc {
class SharedMemoryChannel;
}

// Observes a views::Widget and forwards geometry, focus, and state
// change events to Java via the IPC event ring buffer.
class JuxWidgetObserver : public views::WidgetObserver {
 public:
  JuxWidgetObserver(EventWriter* evt_writer,
                    ipc::SharedMemoryChannel* channel);
  ~JuxWidgetObserver() override;

  JuxWidgetObserver(const JuxWidgetObserver&) = delete;
  JuxWidgetObserver& operator=(const JuxWidgetObserver&) = delete;

  // views::WidgetObserver overrides:
  void OnWidgetBoundsChanged(views::Widget* widget,
                             const gfx::Rect& new_bounds) override;
  void OnWidgetActivationChanged(views::Widget* widget, bool active) override;
  void OnWidgetDestroying(views::Widget* widget) override;

 private:
  // Writes a little-endian double into a buffer at the given offset.
  static void PutF64(uint8_t* buf, size_t offset, double value);

  // Returns the DPI scale factor for the given widget's native window.
  static double GetDpiScale(views::Widget* widget);

  // Event writer for sending events to Java. Not owned.
  raw_ptr<EventWriter> evt_writer_;

  // Shared memory channel — provides the window ID. Not owned.
  raw_ptr<ipc::SharedMemoryChannel> channel_;

  // Previous bounds — used to distinguish move vs resize.
  gfx::Rect last_bounds_;
};

}  // namespace jux

#endif  // JUX_WIDGET_OBSERVER_H_
