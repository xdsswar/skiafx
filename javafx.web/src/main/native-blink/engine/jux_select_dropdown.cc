// Copyright 2026 Xtreme Software Solutions. All rights reserved.

#include "jux/jux_select_dropdown.h"

#include <algorithm>
#include <cmath>

#include "skia/ext/font_utils.h"
#include "third_party/skia/include/core/SkBlurTypes.h"
#include "third_party/skia/include/core/SkCanvas.h"
#include "third_party/skia/include/core/SkColor.h"
#include "third_party/skia/include/core/SkFont.h"
#include "third_party/skia/include/core/SkFontMetrics.h"
#include "third_party/skia/include/core/SkFontMgr.h"
#include "third_party/skia/include/core/SkFontStyle.h"
#include "third_party/skia/include/core/SkImageInfo.h"
#include "third_party/skia/include/core/SkMaskFilter.h"
#include "third_party/skia/include/core/SkPaint.h"
#include "third_party/skia/include/core/SkPath.h"
#include "third_party/skia/include/core/SkRRect.h"
#include "third_party/skia/include/core/SkRect.h"
#include "third_party/skia/include/core/SkTypeface.h"

namespace jux {

namespace {

// Chrome-on-Windows native <select> popup metrics (DIP).
constexpr float kRowH = 26.0f;       // option row height
constexpr float kHeaderH = 22.0f;    // optgroup label row height
constexpr float kTextPadX = 10.0f;   // left/right text inset
constexpr float kFontPx = 13.0f;
constexpr float kHeaderFontPx = 11.0f;
constexpr float kMinW = 96.0f;
constexpr float kMaxW = 460.0f;
constexpr float kRadius = 2.0f;
constexpr float kShadowPad = 8.0f;   // margin in the bitmap for the drop shadow
constexpr float kBorder = 1.0f;
constexpr float kScrollbarW = 6.0f;
constexpr float kGapBelow = 1.0f;    // gap between control and menu

// Palette — matches the native Chrome/Windows <select> popup: white list, the
// highlighted (hovered / on-open selected) row a solid blue with white text.
constexpr SkColor kMenuBg = SK_ColorWHITE;
constexpr SkColor kBorderCol = SkColorSetRGB(0xb0, 0xb0, 0xb0);
constexpr SkColor kText = SkColorSetRGB(0x20, 0x21, 0x24);
constexpr SkColor kTextDisabled = SkColorSetRGB(0xbd, 0xc1, 0xc6);
constexpr SkColor kHeaderText = SkColorSetRGB(0x5f, 0x63, 0x68);
constexpr SkColor kHover = SkColorSetRGB(0x1b, 0x6e, 0xf3);   // solid highlight blue
constexpr SkColor kHoverText = SK_ColorWHITE;
constexpr SkColor kScrollThumb = SkColorSetARGB(0x66, 0x5f, 0x63, 0x68);

SkFont MakeFont(float px, bool bold) {
  sk_sp<SkFontMgr> mgr = skia::DefaultFontMgr();
  SkFontStyle style(bold ? SkFontStyle::kBold_Weight : SkFontStyle::kNormal_Weight,
                    SkFontStyle::kNormal_Width, SkFontStyle::kUpright_Slant);
  sk_sp<SkTypeface> tf;
  if (mgr) {
    tf = mgr->matchFamilyStyle("Segoe UI", style);
    if (!tf) tf = mgr->matchFamilyStyle(nullptr, style);
  }
  SkFont font(tf, px);
  font.setEdging(SkFont::Edging::kAntiAlias);
  font.setSubpixel(true);
  return font;
}

float MeasureText(const SkFont& font, const std::string& s) {
  return font.measureText(s.c_str(), s.size(), SkTextEncoding::kUTF8);
}

// Returns `s` truncated with a trailing ellipsis so it fits in `max_w`.
std::string Ellipsize(const SkFont& font, const std::string& s, float max_w) {
  if (MeasureText(font, s) <= max_w) return s;
  const std::string ell = "\xE2\x80\xA6";  // …
  std::string out = s;
  while (!out.empty() &&
         MeasureText(font, out + ell) > max_w) {
    out.pop_back();
  }
  return out + ell;
}

}  // namespace

SkiaDropdown::SkiaDropdown(std::vector<DropdownOption> options,
                           int selected_option_index,
                           float anchor_x, float anchor_y,
                           float anchor_w, float anchor_h)
    : selected_option_index_(selected_option_index),
      anchor_x_(anchor_x),
      anchor_y_(anchor_y),
      anchor_w_(anchor_w),
      anchor_h_(anchor_h) {
  std::string cur_group;
  int opt = 0;
  for (auto& o : options) {
    if (!o.group.empty() && o.group != cur_group) {
      cur_group = o.group;
      rows_.push_back(Row{cur_group, /*header=*/true, /*enabled=*/false, -1});
    }
    rows_.push_back(Row{std::move(o.label), /*header=*/false, o.enabled, opt});
    ++opt;
  }
  // Initial hover follows the current selection so keyboard/typeahead feel right.
  for (size_t i = 0; i < rows_.size(); ++i) {
    if (rows_[i].option_index == selected_option_index_) {
      hover_row_ = static_cast<int>(i);
      break;
    }
  }
}

SkiaDropdown::~SkiaDropdown() = default;

void SkiaDropdown::Layout(float view_w, float view_h, float device_scale) {
  scale_ = device_scale > 0.1f ? device_scale : 1.0f;

  // Content height = sum of row heights.
  content_h_ = 0;
  for (const auto& r : rows_) content_h_ += r.header ? kHeaderH : kRowH;

  // Width: widest label (+ check column) clamped, at least the control width.
  SkFont font = MakeFont(kFontPx, false);
  SkFont hfont = MakeFont(kHeaderFontPx, true);
  float widest = 0;
  for (const auto& r : rows_) {
    float w = (r.header ? MeasureText(hfont, r.label)
                        : MeasureText(font, r.label));
    widest = std::max(widest, w);
  }
  body_w_ = widest + 2 * kTextPadX;
  body_w_ = std::max(body_w_, anchor_w_);
  body_w_ = std::clamp(body_w_, kMinW, std::min(kMaxW, std::max(kMinW, view_w - 8)));

  // Vertical placement: prefer below the control, flip above if it fits better,
  // otherwise clamp to the view and scroll.
  const float avail_below = view_h - (anchor_y_ + anchor_h_) - kShadowPad - 2;
  const float avail_above = anchor_y_ - kShadowPad - 2;
  body_h_ = content_h_;
  bool below = true;
  if (content_h_ > avail_below && avail_above > avail_below) {
    below = false;
  }
  const float avail = below ? avail_below : avail_above;
  if (body_h_ > avail) body_h_ = std::max(kRowH, avail);

  body_x_ = anchor_x_;
  if (body_x_ + body_w_ > view_w - kShadowPad)
    body_x_ = view_w - kShadowPad - body_w_;
  body_x_ = std::max(body_x_, kShadowPad);

  if (below) {
    body_y_ = anchor_y_ + anchor_h_ + kGapBelow;
  } else {
    body_y_ = anchor_y_ - kGapBelow - body_h_;
  }
  body_y_ = std::max(body_y_, kShadowPad);

  // Scroll so the selected/hover row is visible.
  float sel_top = 0;
  for (const auto& r : rows_) {
    if (r.option_index == selected_option_index_) break;
    sel_top += r.header ? kHeaderH : kRowH;
  }
  if (content_h_ > body_h_) {
    scroll_ = std::clamp(sel_top - body_h_ / 2, 0.0f, content_h_ - body_h_);
  } else {
    scroll_ = 0;
  }

  px_ = body_x_ - kShadowPad;
  py_ = body_y_ - kShadowPad;
  pw_ = body_w_ + 2 * kShadowPad;
  ph_ = body_h_ + 2 * kShadowPad;
}

const SkBitmap& SkiaDropdown::Render() {
  const int bw = std::max(1, static_cast<int>(std::lround(pw_ * scale_)));
  const int bh = std::max(1, static_cast<int>(std::lround(ph_ * scale_)));
  if (bitmap_.width() != bw || bitmap_.height() != bh) {
    bitmap_.allocPixels(
        SkImageInfo::Make(bw, bh, kBGRA_8888_SkColorType, kPremul_SkAlphaType));
  }
  SkCanvas canvas(bitmap_);
  canvas.clear(SK_ColorTRANSPARENT);
  canvas.scale(scale_, scale_);
  // Work in body-local space: origin at the menu body's top-left.
  canvas.translate(kShadowPad, kShadowPad);

  const SkRect body = SkRect::MakeWH(body_w_, body_h_);
  const SkRRect rr = SkRRect::MakeRectXY(body, kRadius, kRadius);

  // Soft drop shadow.
  SkPaint shadow;
  shadow.setAntiAlias(true);
  shadow.setColor(SkColorSetARGB(0x40, 0, 0, 0));
  shadow.setMaskFilter(SkMaskFilter::MakeBlur(kNormal_SkBlurStyle, 4.0f));
  canvas.save();
  canvas.translate(0, 1.5f);
  canvas.drawRRect(rr, shadow);
  canvas.restore();

  // Menu background + border.
  SkPaint bg;
  bg.setAntiAlias(true);
  bg.setColor(kMenuBg);
  canvas.drawRRect(rr, bg);

  // Clip rows to the rounded body.
  canvas.save();
  canvas.clipRRect(rr, true);

  SkFont font = MakeFont(kFontPx, false);
  SkFont hfont = MakeFont(kHeaderFontPx, true);
  SkFontMetrics fm;
  font.getMetrics(&fm);

  SkPaint text;
  text.setAntiAlias(true);
  const float text_x = kTextPadX;
  float y = -scroll_;
  for (size_t i = 0; i < rows_.size(); ++i) {
    const Row& r = rows_[i];
    const float rh = r.header ? kHeaderH : kRowH;
    if (y + rh > 0 && y < body_h_) {
      const SkRect row = SkRect::MakeXYWH(0, y, body_w_, rh);
      // The highlighted row (hovered, or the current selection on open) gets a
      // solid blue bar with white text — like the native Chrome/Windows popup.
      const bool active =
          !r.header && r.enabled && static_cast<int>(i) == hover_row_;
      if (active) {
        SkPaint hp;
        hp.setColor(kHover);
        canvas.drawRect(row, hp);
      }
      const SkFont& f = r.header ? hfont : font;
      f.getMetrics(&fm);
      const float baseline = y + (rh - (fm.fDescent - fm.fAscent)) / 2 - fm.fAscent;
      const float avail_text = body_w_ - text_x - kTextPadX -
                               (content_h_ > body_h_ ? kScrollbarW : 0);
      std::string label = Ellipsize(f, r.label, avail_text);
      text.setColor(r.header
                        ? kHeaderText
                        : (active ? kHoverText
                                  : (r.enabled ? kText : kTextDisabled)));
      canvas.drawString(label.c_str(), text_x, baseline, f, text);
    }
    y += rh;
  }

  // Scrollbar thumb.
  if (content_h_ > body_h_) {
    const float track_h = body_h_;
    const float thumb_h = std::max(24.0f, track_h * body_h_ / content_h_);
    const float t = (content_h_ - body_h_) > 0
                        ? scroll_ / (content_h_ - body_h_)
                        : 0;
    const float thumb_y = t * (track_h - thumb_h);
    SkPaint tp;
    tp.setAntiAlias(true);
    tp.setColor(kScrollThumb);
    SkRect thumb = SkRect::MakeXYWH(body_w_ - kScrollbarW - 1.0f, thumb_y + 1.0f,
                                    kScrollbarW - 1.0f, thumb_h - 2.0f);
    canvas.drawRRect(SkRRect::MakeRectXY(thumb, 3, 3), tp);
  }

  canvas.restore();  // row clip

  // Crisp 1px border on top.
  SkPaint border;
  border.setAntiAlias(true);
  border.setStyle(SkPaint::kStroke_Style);
  border.setStrokeWidth(kBorder);
  border.setColor(kBorderCol);
  canvas.drawRRect(rr, border);

  return bitmap_;
}

bool SkiaDropdown::Contains(float lx, float ly) const {
  return lx >= body_x_ && lx < body_x_ + body_w_ && ly >= body_y_ &&
         ly < body_y_ + body_h_;
}

bool SkiaDropdown::AnchorContains(float lx, float ly) const {
  return lx >= anchor_x_ && lx < anchor_x_ + anchor_w_ && ly >= anchor_y_ &&
         ly < anchor_y_ + anchor_h_;
}

int SkiaDropdown::RowAt(float lx, float ly) const {
  if (!Contains(lx, ly)) return -1;
  float local_y = ly - body_y_ + scroll_;
  float y = 0;
  for (size_t i = 0; i < rows_.size(); ++i) {
    const float rh = rows_[i].header ? kHeaderH : kRowH;
    if (local_y >= y && local_y < y + rh) return static_cast<int>(i);
    y += rh;
  }
  return -1;
}

bool SkiaDropdown::SetHover(int row) {
  if (row >= 0 && row < static_cast<int>(rows_.size()) &&
      (rows_[row].header || !rows_[row].enabled)) {
    row = -1;  // can't hover a header or disabled row
  }
  if (row == hover_row_) return false;
  hover_row_ = row;
  return true;
}

bool SkiaDropdown::ScrollBy(float dy_dip) {
  if (content_h_ <= body_h_) return false;
  const float old = scroll_;
  scroll_ = std::clamp(scroll_ + dy_dip, 0.0f, content_h_ - body_h_);
  return scroll_ != old;
}

int SkiaDropdown::OptionIndexForRow(int row) const {
  if (row < 0 || row >= static_cast<int>(rows_.size())) return -1;
  const Row& r = rows_[row];
  if (r.header || !r.enabled) return -1;
  return r.option_index;
}

}  // namespace jux
