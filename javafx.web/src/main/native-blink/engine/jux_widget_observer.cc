// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// Implementation of JuxWidgetObserver — forwards widget state changes
// to Java via the event ring buffer.

#ifdef UNSAFE_BUFFERS_BUILD
#pragma allow_unsafe_buffers
#endif

#include "jux/jux_widget_observer.h"

#include <cstring>

#include "base/containers/span.h"
#include "base/logging.h"
#include "jux/jux_event_types.h"
#include "jux/jux_ipc.h"
#include "jux/jux_ring_buffer.h"
#include "ui/aura/window.h"
#include "ui/aura/window_tree_host.h"
#include "ui/views/widget/widget.h"

#if BUILDFLAG(IS_WIN)
#include <windows.h>
#endif

namespace jux {

JuxWidgetObserver::JuxWidgetObserver(EventWriter* evt_writer,
                                     ipc::SharedMemoryChannel* channel)
    : evt_writer_(evt_writer),
      channel_(channel) {}

JuxWidgetObserver::~JuxWidgetObserver() = default;

// static
void JuxWidgetObserver::PutF64(uint8_t* buf, size_t offset, double value) {
  memcpy(buf + offset, &value, sizeof(value));
}

// static
double JuxWidgetObserver::GetDpiScale(views::Widget* widget) {
#if BUILDFLAG(IS_WIN)
  if (widget && widget->GetNativeWindow() &&
      widget->GetNativeWindow()->GetHost()) {
    HWND hwnd = widget->GetNativeWindow()->GetHost()->GetAcceleratedWidget();
    if (hwnd) {
      UINT dpi = GetDpiForWindow(hwnd);
      return dpi / 96.0;
    }
  }
#endif
  return 1.0;
}

void JuxWidgetObserver::OnWidgetBoundsChanged(views::Widget* widget,
                                               const gfx::Rect& new_bounds) {
  if (!evt_writer_ || !channel_) {
    return;
  }

  uint32_t wid = channel_->window_id();
  double scale = GetDpiScale(widget);

  // Check if the origin changed (window moved).
  if (new_bounds.origin() != last_bounds_.origin()) {
    // Convert physical pixels to logical pixels for Java.
    double lx = new_bounds.x() / scale;
    double ly = new_bounds.y() / scale;

    // User payload: [x:8(double)][y:8(double)]
    // EventWriter prepends window_id automatically.
    uint8_t payload[16];
    PutF64(payload, 0, lx);
    PutF64(payload, 8, ly);
    evt_writer_->WriteEvent(events::kWindowMoved, wid,
                            base::span<const uint8_t>(payload, sizeof(payload)));
  }

  // Check if the size changed (window resized).
  if (new_bounds.size() != last_bounds_.size()) {
    // Convert physical pixels to logical pixels for Java.
    double lw = new_bounds.width() / scale;
    double lh = new_bounds.height() / scale;

    // User payload: [width:8(double)][height:8(double)]
    uint8_t payload[16];
    PutF64(payload, 0, lw);
    PutF64(payload, 8, lh);
    evt_writer_->WriteEvent(events::kWindowResized, wid,
                            base::span<const uint8_t>(payload, sizeof(payload)));
  }

  last_bounds_ = new_bounds;
}

void JuxWidgetObserver::OnWidgetActivationChanged(views::Widget* widget,
                                                    bool active) {
  if (!evt_writer_ || !channel_) {
    return;
  }

  uint32_t wid = channel_->window_id();

  if (active) {
    evt_writer_->WriteEvent(events::kWindowFocused, wid);
  } else {
    evt_writer_->WriteEvent(events::kWindowUnfocused, wid);
  }
}

void JuxWidgetObserver::OnWidgetDestroying(views::Widget* widget) {
  // Detach to prevent dangling pointer access after destruction.
  widget->RemoveObserver(this);
}

}  // namespace jux
