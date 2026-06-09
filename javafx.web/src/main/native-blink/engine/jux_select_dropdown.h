// Copyright 2026 Xtreme Software Solutions. All rights reserved.
//
// A Chrome-styled HTML <select> drop-down rendered with Skia in the BROWSER
// process. The off-screen print preview's own native Blink page-popup cannot
// composite (the preview WebContents is hidden + captured), so when the user
// clicks one of the preview's <select> controls we suppress Blink's popup
// (select-override) and draw the option list ourselves here, compositing it over
// the modal through the existing popup-overlay channel. Self-contained: no Blink,
// no JavaFX nodes — just Skia. Gated behind ENABLE_PRINT_PREVIEW.
#ifndef JUX_SELECT_DROPDOWN_H_
#define JUX_SELECT_DROPDOWN_H_

#include <cstdint>
#include <string>
#include <vector>

#include "third_party/skia/include/core/SkBitmap.h"

namespace jux {

// One <option> surfaced by the renderer (optgroup headers are derived from
// `group` changing).
struct DropdownOption {
  std::string label;
  std::string group;  // enclosing <optgroup> label, "" if none
  bool enabled = true;
};

// A drop-down list anchored under a <select>. Lays itself out within the preview
// view, renders to a BGRA8888 premultiplied bitmap, and hit-tests pointer input.
class SkiaDropdown {
 public:
  SkiaDropdown(std::vector<DropdownOption> options,
               int selected_option_index,
               float anchor_x, float anchor_y, float anchor_w, float anchor_h);
  ~SkiaDropdown();

  SkiaDropdown(const SkiaDropdown&) = delete;
  SkiaDropdown& operator=(const SkiaDropdown&) = delete;

  // Positions + sizes the popup within a view of `view_w`×`view_h` DIP rendered
  // at `device_scale`. Call once before Render().
  void Layout(float view_w, float view_h, float device_scale);

  // Renders the current state (hover/scroll) into an internal bitmap and returns
  // it (BGRA8888, premultiplied, device pixels).
  const SkBitmap& Render();

  // Popup rectangle in preview-local DIP — where Java composites the bitmap.
  // Includes the soft-shadow margin so the menu body sits inset within it.
  float x() const { return px_; }
  float y() const { return py_; }
  float width() const { return pw_; }
  float height() const { return ph_; }

  // Pointer hit-testing, all in preview-local DIP.
  bool Contains(float lx, float ly) const;       // inside the menu body
  bool AnchorContains(float lx, float ly) const; // on the originating <select>
  // Display row under (lx,ly), or -1 if outside the body / on a header.
  int RowAt(float lx, float ly) const;
  bool SetHover(int row);                          // true if it changed
  bool ScrollBy(float dy_dip);                     // true if it changed

  // Maps a display row to the originating <option> index, or -1 (header/disabled).
  int OptionIndexForRow(int row) const;
  int selected_option_index() const { return selected_option_index_; }

 private:
  struct Row {
    std::string label;
    bool header = false;     // optgroup label (non-selectable)
    bool enabled = true;
    int option_index = -1;   // -1 for header rows
  };

  std::vector<Row> rows_;
  int selected_option_index_;
  int hover_row_ = -1;
  float scroll_ = 0.0f;          // content scrolled up, DIP

  const float anchor_x_, anchor_y_, anchor_w_, anchor_h_;

  // Layout outputs (DIP). Body = menu without the shadow margin.
  float body_x_ = 0, body_y_ = 0, body_w_ = 0, body_h_ = 0;
  float px_ = 0, py_ = 0, pw_ = 0, ph_ = 0;  // incl. shadow margin
  float content_h_ = 0;          // total height of all rows
  float scale_ = 1.0f;

  SkBitmap bitmap_;
};

// Browser-process bridge: opens (or replaces) the print preview's <select>
// drop-down, rendering it with Skia and compositing it over the modal. Called by
// JuxDomClientImpl::OnSelectPopup when the popup belongs to the active preview.
// Implemented in jux_engine_api.cc (which owns the popup region, the preview
// handle, and pointer routing). Returns true if it took over the popup (the
// caller then skips the default Java select-popup event). `handle` is a
// JuxWebContentsHandle.
bool OpenPreviewSelectDropdown(uintptr_t handle, uint32_t popup_id,
                               uint32_t flags, int32_t selected_index,
                               double x, double y, double w, double h,
                               std::vector<DropdownOption> options);

}  // namespace jux

#endif  // JUX_SELECT_DROPDOWN_H_
