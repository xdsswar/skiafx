// Copyright 2026 Xtreme Software Solutions. All rights reserved.

#include "jux/jux_pdf_plugin.h"

#include "jux/jux_pdf_plugin_factory.h"

#include <algorithm>
#include <cmath>
#include <optional>

#include "base/functional/bind.h"
#include "base/location.h"
#include "base/logging.h"
#include "base/no_destructor.h"
#include "base/strings/string_number_conversions.h"
#include "base/task/sequenced_task_runner.h"
#include "base/task/single_thread_task_runner.h"
#include "base/time/time.h"
#include "skia/ext/font_utils.h"
#include "cc/paint/paint_canvas.h"
#include "cc/paint/paint_flags.h"
#include "cc/paint/paint_image.h"
#include "cc/paint/paint_image_builder.h"
#include "base/values.h"
#include "content/public/renderer/render_frame.h"
#include "content/public/renderer/v8_value_converter.h"
#include "pdf/pdf.h"
#include "third_party/blink/public/platform/browser_interface_broker_proxy.h"
#include "third_party/blink/public/platform/scheduler/web_agent_group_scheduler.h"
#include "third_party/blink/public/web/web_serialized_script_value.h"
#include "third_party/blink/public/web/web_dom_message_event.h"
#include "v8/include/v8.h"
#include "third_party/blink/public/common/input/web_coalesced_input_event.h"
#include "third_party/blink/public/common/input/web_input_event.h"
#include "third_party/blink/public/common/input/web_mouse_event.h"
#include "third_party/blink/public/common/input/web_mouse_wheel_event.h"
#include "third_party/blink/public/common/input/web_pointer_properties.h"
#include "third_party/blink/public/platform/web_input_event_result.h"
#include "third_party/blink/public/web/web_document.h"
#include "third_party/blink/public/web/web_local_frame.h"
#include "third_party/blink/public/web/web_plugin_container.h"
#include "third_party/skia/include/core/SkCanvas.h"
#include "third_party/skia/include/core/SkFont.h"
#include "third_party/skia/include/core/SkFontMgr.h"
#include "third_party/skia/include/core/SkFontMetrics.h"
#include "third_party/skia/include/core/SkFontStyle.h"
#include "third_party/skia/include/core/SkImage.h"
#include "third_party/skia/include/core/SkImageInfo.h"
#include "third_party/skia/include/core/SkPath.h"
#include "third_party/skia/include/core/SkPathBuilder.h"
#include "third_party/skia/include/core/SkRRect.h"
#include "third_party/skia/include/core/SkTextBlob.h"
#include "third_party/skia/include/core/SkTypeface.h"
#include "ui/gfx/geometry/size.h"
#include "ui/gfx/geometry/size_f.h"
#include "ui/gfx/geometry/skia_conversions.h"

namespace jux {

namespace {
// Chrome's print-preview document backdrop grey (Google Grey 300, #DADCE0).
constexpr SkColor kBackdrop = SkColorSetRGB(0xDA, 0xDC, 0xE0);
// Constant gap between stacked pages, in SCREEN pixels (does not scale with zoom).
constexpr float kGapPx = 12.0f;
// Supersampling factor so the page stays crisp when scaled in Paint().
constexpr int kRenderScale = 2;
// Memory bound on a rasterized page.
constexpr int kMaxDim = 4096;
}  // namespace

JuxPdfPlugin::JuxPdfPlugin(const blink::WebURL& url) : url_(url) {}

JuxPdfPlugin::~JuxPdfPlugin() = default;

bool JuxPdfPlugin::Initialize(blink::WebPluginContainer* container) {
  container_ = container;
  StartLoad();
  return true;
}

void JuxPdfPlugin::StartLoad() {
  if (!container_ || pdf_provider_) {
    return;
  }
  blink::WebLocalFrame* frame = container_->GetDocument().GetFrame();
  content::RenderFrame* render_frame =
      frame ? content::RenderFrame::FromWebFrame(frame) : nullptr;
  if (!render_frame) {
    LOG(ERROR) << "[jux-pdf] StartLoad: no render frame";
    return;
  }
  render_frame->GetBrowserInterfaceBroker().GetInterface(
      pdf_provider_.BindNewPipeAndPassReceiver());
  // Load page 0 from the <embed src> immediately for a quick first frame. The
  // print preview then re-drives everything via resetPrintPreviewMode /
  // loadPreviewPage (settings changes, custom page ranges, multi-page).
  FetchPreviewData(url_.GetString().Utf8(), /*dest_index=*/0);
}

void JuxPdfPlugin::FetchPreviewData(const std::string& url, int dest_index) {
  if (!pdf_provider_) {
    return;
  }
  // The chrome://print/pdf frame can't fetch the cross-scheme byte-server, so ask
  // the browser. The byte-server path is the URL after the host, e.g.
  // "chrome-untrusted://print/1/0/print.pdf" → "1/0/print.pdf".
  std::string data_path;
  const std::string marker = "://print/";
  const size_t pos = url.find(marker);
  if (pos != std::string::npos) {
    data_path = url.substr(pos + marker.size());
  }
  if (data_path.empty()) {
    return;
  }
  pdf_provider_->GetPreviewPdf(
      data_path, base::BindOnce(&JuxPdfPlugin::OnPreviewPageFetched,
                                weak_factory_.GetWeakPtr(), dest_index));
  VLOG(1) << "[jux-pdf] fetch " << data_path << " -> slot " << dest_index;
}

void JuxPdfPlugin::OnPreviewPageFetched(int dest_index,
                                        const std::vector<uint8_t>& data) {
  if (data.empty()) {
    LOG(ERROR) << "[jux-pdf] browser returned 0 bytes for slot " << dest_index;
    return;
  }
  page_pdfs_[dest_index] = data;
  load_failed_ = false;
  // First-frame raster (page 0) before the viewer sends a viewport message.
  if (dest_index == 0 && !has_viewport_) {
    const int w = plugin_rect_.width() > 0 ? plugin_rect_.width() : 816;
    RasterizeForWidth(w);
  }
  VLOG(1) << "[jux-pdf] slot " << dest_index << " delivered " << data.size()
            << " bytes; pages loaded=" << page_pdfs_.size() << "/" << page_count_;
  RebuildAndNotify();
  if (container_) {
    container_->Invalidate();
  }
}

void JuxPdfPlugin::PostMessageToViewer(base::Value message) {
  if (!container_) {
    return;
  }
  blink::WebLocalFrame* frame = container_->GetDocument().GetFrame();
  if (!frame) {
    return;
  }
  v8::Isolate* isolate = frame->GetAgentGroupScheduler()->Isolate();
  v8::Isolate::Scope isolate_scope(isolate);
  v8::HandleScope handle_scope(isolate);
  v8::Local<v8::Context> context = frame->MainWorldScriptContext();
  if (context.IsEmpty()) {
    return;
  }
  v8::Context::Scope context_scope(context);
  std::unique_ptr<content::V8ValueConverter> converter =
      content::V8ValueConverter::Create();
  v8::Local<v8::Value> v8_message = converter->ToV8Value(message, context);
  container_->EnqueueMessageEvent(blink::WebDOMMessageEvent(
      blink::WebSerializedScriptValue::Serialize(isolate, v8_message)));
}

void JuxPdfPlugin::RebuildAndNotify() {
  if (page_pdfs_.empty()) {
    return;
  }
  // Rebuild the page layout (one stacked entry per printable page) from the loaded
  // PDFs. Each page_pdfs_ entry may itself hold multiple internal pages (a printed
  // PDF file) or a single page (a printed web page). Pages render lazily in Paint
  // so document length is unbounded. Sizes are PDF points (viewer px at 100%).
  BuildLayout();

  base::ListValue pages;
  for (const PageSlot& s : layout_) {
    base::DictValue rect;
    rect.Set("x", 0.0);
    rect.Set("y", s.y_pts);
    rect.Set("width", s.w_pts);
    rect.Set("height", s.h_pts);
    pages.Append(base::Value(std::move(rect)));
  }

  base::DictValue dims;
  dims.Set("type", "documentDimensions");
  dims.Set("width", doc_w_pts_);
  dims.Set("height", doc_h_pts_);
  base::DictValue layout;
  layout.Set("direction", 0);
  layout.Set("defaultPageOrientation", 0);
  layout.Set("twoUpViewEnabled", false);
  dims.Set("layoutOptions", base::Value(std::move(layout)));
  dims.Set("pageDimensions", base::Value(std::move(pages)));
  PostMessageToViewer(base::Value(std::move(dims)));

  // Report progress, but only declare the document loaded once EVERY expected
  // page has arrived — otherwise the viewer finalizes at the first page and
  // ignores the rest (showing a single page with no scrollbar). page_count_==0
  // means a complete PDF arrived in slot 0.
  const int total = page_count_ > 0 ? page_count_ : 1;
  // Count actual internal pages (layout_ has one entry per printable page,
  // filled by BuildLayout above), not slots: a single-slot multi-page PDF
  // (whole doc in slot 0, page_count_>0) would otherwise never reach `total`
  // and leave the viewer stuck "loading". The normal web-page case (N slots ×
  // 1 page each) still yields layout_.size()==N==page_count_.
  const int loaded = static_cast<int>(layout_.size());
  const bool complete = loaded >= total;
  base::DictValue progress;
  progress.Set("type", "loadProgress");
  progress.Set("progress", complete ? 100.0 : (loaded * 100.0 / total));
  PostMessageToViewer(base::Value(std::move(progress)));

  if (complete) {
    base::DictValue loaded_msg;
    loaded_msg.Set("type", "printPreviewLoaded");
    PostMessageToViewer(base::Value(std::move(loaded_msg)));
  }
}

void JuxPdfPlugin::Destroy() {
  container_ = nullptr;
  delete this;
}

blink::WebPluginContainer* JuxPdfPlugin::Container() const {
  return container_;
}

// Blink does not stream the <embed>'s resource into a bare WebPlugin, so these
// element-load callbacks never fire for us — all bytes come via the browser
// (FetchPreviewData) driven by the embed src + resetPrintPreviewMode/
// loadPreviewPage. Kept as no-ops to satisfy the WebPlugin interface.
void JuxPdfPlugin::DidReceiveData(base::span<const char>) {}

void JuxPdfPlugin::DidFinishLoading() {}

void JuxPdfPlugin::DidFailLoading(const blink::WebURLError&) {
  load_failed_ = true;
  LOG(ERROR) << "[jux-pdf] JuxPdfPlugin load failed for "
             << url_.GetString().Utf8();
  if (container_) {
    container_->Invalidate();
  }
}

v8::Local<v8::Object> JuxPdfPlugin::V8ScriptableObject(v8::Isolate* isolate) {
  if (scriptable_receiver_.IsEmpty()) {
    if (!v8_value_converter_) {
      v8_value_converter_ = content::V8ValueConverter::Create();
    }
    scriptable_receiver_.Reset(
        isolate, chrome_pdf::PostMessageReceiver::Create(
                     isolate, weak_factory_.GetWeakPtr(),
                     weak_factory_.GetWeakPtr(),
                     base::SequencedTaskRunner::GetCurrentDefault()));
  }
  return scriptable_receiver_.Get(isolate);
}

std::unique_ptr<base::Value> JuxPdfPlugin::FromV8Value(
    v8::Local<v8::Value> value,
    v8::Local<v8::Context> context) {
  if (!v8_value_converter_) {
    v8_value_converter_ = content::V8ValueConverter::Create();
  }
  return v8_value_converter_->FromV8Value(value, context);
}

void JuxPdfPlugin::OnMessage(const base::DictValue& message) {
  const std::string* type = message.FindString("type");
  if (!type) {
    return;
  }
  if (*type == "resetPrintPreviewMode") {
    HandleResetPrintPreviewMode(message);
    return;
  }
  if (*type == "loadPreviewPage") {
    HandleLoadPreviewPage(message);
    return;
  }
  if (*type != "viewport") {
    return;  // other viewer messages are not relevant to our renderer
  }
  // The plugin owns BOTH scroll axes (the viewer's #sizer is hidden for remote
  // content, so its xOffset/yOffset are always 0 — applying them would yank the
  // page to the top-left on every zoom). We take only the zoom and, when it
  // changes, refocus the scroll so the point at the viewport CENTER stays put
  // (zoom-to-center). This keeps the page centered when it fits and scrollable
  // (via our own scrollbars) when it doesn't.
  std::optional<double> z = message.FindDouble("zoom");
  if (z && *z > 0 && *z != zoom_ && zoom_ > 0 && has_viewport_ &&
      !layout_.empty()) {
    const double vp_w = plugin_rect_.width();
    const double vp_h = plugin_rect_.height();
    // Horizontal axis has no gaps — a plain point-space refocus keeps it centered.
    const double old_mx = std::max(0.0, (vp_w - doc_w_pts_ * zoom_) / 2.0);
    const double focus_x = (scroll_x_ + vp_w / 2.0 - old_mx) / zoom_;
    // Vertical axis: find the anchor page + fraction under the viewport center at
    // the OLD zoom, accounting for the constant pixel gaps, then place that same
    // anchor back under the center at the NEW zoom. (Pure point-space math would
    // mis-handle the un-zoomed gaps.)
    const double old_my = std::max(0.0, (vp_h - DocPixelHeight()) / 2.0);
    const double center_abs = scroll_y_ + vp_h / 2.0 - old_my;
    int anchor = 0;
    double frac = 0.0;
    for (int i = 0; i < static_cast<int>(layout_.size()); ++i) {
      const double top = PageTopPx(i);
      const double h = layout_[i].h_pts * zoom_;
      anchor = i;
      frac = h > 0 ? std::clamp((center_abs - top) / h, 0.0, 1.0) : 0.0;
      if (center_abs < top + h) {
        break;  // center is within (or before) this page
      }
    }

    zoom_ = *z;
    const double new_mx = std::max(0.0, (vp_w - doc_w_pts_ * zoom_) / 2.0);
    const double new_my = std::max(0.0, (vp_h - DocPixelHeight()) / 2.0);
    const double new_center_abs =
        PageTopPx(anchor) + frac * layout_[anchor].h_pts * zoom_;
    scroll_x_ =
        std::clamp(focus_x * zoom_ + new_mx - vp_w / 2.0, 0.0, MaxScrollX());
    scroll_y_ = std::clamp(new_center_abs - vp_h / 2.0 + new_my, 0.0,
                           MaxScrollY());
    target_scroll_y_ = scroll_y_;
  } else if (z && *z > 0) {
    zoom_ = *z;
  }
  has_viewport_ = true;
  // No bulk re-render here: Paint() rasterizes only the pages near the viewport,
  // and a zoom change is picked up lazily by PageBitmap() (it drops the cache and
  // re-rasterizes visible pages at the new zoom for crisp quality). This keeps
  // scroll/zoom cheap and never blocks on an N-page document.
  if (container_) {
    container_->Invalidate();
  }
}

void JuxPdfPlugin::HandleResetPrintPreviewMode(const base::DictValue& message) {
  const std::string* url = message.FindString("url");
  if (!url) {
    return;
  }
  // A new preview generation — first load, a settings change, or a custom page
  // range. Drop the old pages and load the first (or the complete PDF, when
  // pageCount==0 for a printed PDF file).
  page_count_ = message.FindInt("pageCount").value_or(0);
  // Color setting: the renderer always emits color, so the "Black and white"
  // option is applied at rasterization time (use_color=false in PageBitmap),
  // mirroring Chrome's PDF plugin (PDFiumEngine::SetGrayscale).
  grayscale_ = message.FindBool("grayscale").value_or(false);
  page_pdfs_.clear();
  layout_.clear();
  page_cache_.clear();
  cache_zoom_ = 0.0;
  VLOG(1) << "[jux-pdf] resetPrintPreviewMode pageCount=" << page_count_
            << " grayscale=" << grayscale_;
  FetchPreviewData(*url, /*dest_index=*/0);
}

void JuxPdfPlugin::HandleLoadPreviewPage(const base::DictValue& message) {
  const std::string* url = message.FindString("url");
  std::optional<int> index = message.FindInt("index");
  if (!url || !index) {
    return;
  }
  if (*index == 0) {
    return;  // page 0 already loaded via resetPrintPreviewMode
  }
  FetchPreviewData(*url, *index);
}

void JuxPdfPlugin::BuildLayout() {
  layout_.clear();
  page_cache_.clear();
  cache_zoom_ = 0.0;
  doc_w_pts_ = 0.0;
  doc_h_pts_ = 0.0;
  // y_pts is the CONTENT-only stacked offset (no gaps). The inter-page gap is a
  // constant screen-pixel amount (kGapPx) added per page boundary in screen space
  // so it does NOT scale with zoom — the pages zoom, the gaps don't.
  double y = 0.0;
  for (const auto& entry : page_pdfs_) {
    base::span<const uint8_t> buf(entry.second);
    int n = 0;
    if (!chrome_pdf::GetPDFDocInfo(buf, &n, nullptr) || n <= 0) {
      n = 1;
    }
    for (int i = 0; i < n; ++i) {
      std::optional<gfx::SizeF> sz = chrome_pdf::GetPDFPageSizeByIndex(buf, i);
      const double pw = (sz && sz->width() > 0) ? sz->width() : 612.0;
      const double ph = (sz && sz->height() > 0) ? sz->height() : 792.0;
      layout_.push_back({entry.first, i, pw, ph, y});
      doc_w_pts_ = std::max(doc_w_pts_, pw);
      y += ph;
    }
  }
  doc_h_pts_ = y;  // total page content height, gaps excluded
}

const SkBitmap& JuxPdfPlugin::PageBitmap(int layout_index) {
  static const base::NoDestructor<SkBitmap> kEmpty;
  const SkBitmap& empty = *kEmpty;
  if (layout_index < 0 || layout_index >= static_cast<int>(layout_.size())) {
    return empty;
  }
  // A zoom change invalidates every cached page — re-rasterize at the new scale so
  // the page stays crisp instead of being stretched. Only the visible pages get
  // re-rendered (Paint requests them), so this stays cheap and never blocks.
  if (cache_zoom_ != zoom_) {
    page_cache_.clear();
    cache_zoom_ = zoom_;
  }
  if (auto it = page_cache_.find(layout_index); it != page_cache_.end()) {
    return it->second;
  }
  const PageSlot& s = layout_[layout_index];
  auto entry = page_pdfs_.find(s.slot);
  if (entry == page_pdfs_.end()) {
    return empty;
  }
  // Pick the render resolution so the page is always at least pixel-exact (1:1
  // with its on-screen size) and supersampled when there's headroom:
  //   * Prefer s.*_pts * zoom * doc_quality_ (supersampled → crisp when the page
  //     is small on screen, downscaled in Paint for anti-aliasing).
  //   * If that exceeds kMaxDim, drop the supersample to 1:1 device pixels — still
  //     perfectly crisp, just not supersampled, and keeps zooming sharp far longer.
  //   * Only if even 1:1 exceeds kMaxDim do we clamp below device resolution
  //     (unavoidable softening at extreme zoom), preserving aspect ratio.
  double scale = zoom_ * doc_quality_;
  if (s.w_pts * scale > kMaxDim || s.h_pts * scale > kMaxDim) {
    scale = zoom_;  // fall back to 1:1 device pixels
  }
  int pgw = std::max(1, static_cast<int>(std::lround(s.w_pts * scale)));
  int pgh = std::max(1, static_cast<int>(std::lround(s.h_pts * scale)));
  if (pgw > kMaxDim) {
    pgh = static_cast<int>(std::lround(static_cast<double>(pgh) * kMaxDim / pgw));
    pgw = kMaxDim;
  }
  if (pgh > kMaxDim) {
    pgw = static_cast<int>(std::lround(static_cast<double>(pgw) * kMaxDim / pgh));
    pgh = kMaxDim;
  }
  SkBitmap page;
  if (!page.tryAllocPixels(SkImageInfo::Make(pgw, pgh, kBGRA_8888_SkColorType,
                                             kPremul_SkAlphaType))) {
    return empty;
  }
  page.eraseColor(SK_ColorWHITE);
  // DPI is computed per-axis: the independent kMaxDim clamps above can leave the
  // width/height scaling out of step, so a single width-only DPI would skew the
  // render on one axis.
  const int dpi_x =
      std::max(1, static_cast<int>(std::lround(pgw * 72.0 / s.w_pts)));
  const int dpi_y =
      std::max(1, static_cast<int>(std::lround(pgh * 72.0 / s.h_pts)));
  const chrome_pdf::RenderOptions options{
      /*stretch_to_bounds=*/false, /*keep_aspect_ratio=*/true,
      /*autorotate=*/false, /*use_color=*/!grayscale_,
      chrome_pdf::RenderDeviceType::kDisplay};
  base::span<const uint8_t> buf(entry->second);
  // A transient PDFium failure must NOT be cached — return the empty bitmap
  // without inserting into page_cache_ so a later repaint retries this page.
  if (!chrome_pdf::RenderPDFPageToBitmap(buf, s.page, page.getPixels(),
                                         gfx::Size(pgw, pgh),
                                         gfx::Size(dpi_x, dpi_y), options)) {
    return empty;
  }
  page.setImmutable();
  return page_cache_.emplace(layout_index, std::move(page)).first->second;
}

void JuxPdfPlugin::RasterizeForWidth(int css_width) {
  auto slot0 = page_pdfs_.find(0);
  if (slot0 == page_pdfs_.end() || slot0->second.empty() || css_width <= 0) {
    return;
  }
  base::span<const uint8_t> buf(slot0->second);

  int page_count = 0;
  if (!chrome_pdf::GetPDFDocInfo(buf, &page_count, nullptr) || page_count <= 0) {
    load_failed_ = true;
    LOG(ERROR) << "[jux-pdf] GetPDFDocInfo failed (bytes="
               << slot0->second.size() << ")";
    return;
  }
  std::optional<gfx::SizeF> page_pts =
      chrome_pdf::GetPDFPageSizeByIndex(buf, 0);
  if (!page_pts || page_pts->width() <= 0 || page_pts->height() <= 0) {
    load_failed_ = true;
    return;
  }

  int bmp_w = std::max(1, css_width * kRenderScale);
  int bmp_h = std::max(
      1, static_cast<int>(std::lround(bmp_w * page_pts->height() /
                                      page_pts->width())));
  if (bmp_w > kMaxDim) {
    bmp_h = static_cast<int>(std::lround(static_cast<double>(bmp_h) * kMaxDim /
                                         bmp_w));
    bmp_w = kMaxDim;
  }
  if (bmp_h > kMaxDim) {
    bmp_w = static_cast<int>(std::lround(static_cast<double>(bmp_w) * kMaxDim /
                                         bmp_h));
    bmp_h = kMaxDim;
  }

  SkBitmap bmp;
  if (!bmp.tryAllocPixels(SkImageInfo::Make(bmp_w, bmp_h, kBGRA_8888_SkColorType,
                                            kPremul_SkAlphaType))) {
    load_failed_ = true;
    return;
  }
  bmp.eraseColor(SK_ColorWHITE);

  const int dpi =
      std::max(1, static_cast<int>(std::lround(bmp_w * 72.0 /
                                               page_pts->width())));
  const chrome_pdf::RenderOptions options{
      /*stretch_to_bounds=*/false,
      /*keep_aspect_ratio=*/true,
      /*autorotate=*/false,
      /*use_color=*/!grayscale_,
      /*render_device_type=*/chrome_pdf::RenderDeviceType::kDisplay};
  if (!chrome_pdf::RenderPDFPageToBitmap(buf, /*page_index=*/0, bmp.getPixels(),
                                         gfx::Size(bmp_w, bmp_h),
                                         gfx::Size(dpi, dpi), options)) {
    load_failed_ = true;
    LOG(ERROR) << "[jux-pdf] RenderPDFPageToBitmap failed";
    return;
  }
  bmp.setImmutable();
  page_bitmap_ = bmp;
  rasterized_for_width_ = css_width;
}

void JuxPdfPlugin::Paint(cc::PaintCanvas* canvas, const gfx::Rect& rect) {
  const SkRect clip =
      gfx::RectToSkRect(gfx::IntersectRects(plugin_rect_, rect));
  cc::PaintCanvasAutoRestore auto_restore(canvas, /*save=*/true);
  canvas->clipRect(clip);

  cc::PaintFlags bg;
  bg.setColor(kBackdrop);
  bg.setBlendMode(SkBlendMode::kSrc);
  canvas->drawRect(gfx::RectToSkRect(plugin_rect_), bg);

  // Scroll/zoom view: once the viewer has sent a viewport, paint the stacked
  // pages offset by the scroll position (the clip above limits it to the visible
  // area). Each visible page is rasterized on demand at doc_quality_ px per CSS px.
  if (has_viewport_ && !layout_.empty()) {
    const double doc_w_px = doc_w_pts_ * zoom_;
    const double doc_h_px = DocPixelHeight();  // includes constant inter-page gaps
    // Center the document horizontally when it's narrower than the viewport, like
    // Chrome's PDF plugin. Same vertically for a short document.
    const double margin_x =
        std::max(0.0, (plugin_rect_.width() - doc_w_px) / 2.0);
    const double margin_y =
        std::max(0.0, (plugin_rect_.height() - doc_h_px) / 2.0);
    // Top-left of the stacked document in widget coordinates.
    const double origin_x = plugin_rect_.x() + margin_x - scroll_x_;
    const double origin_y = plugin_rect_.y() + margin_y - scroll_y_;

    // Draw only the pages that intersect the visible viewport, rasterizing each on
    // demand. This keeps work proportional to what's on screen, not to document
    // length, so a 100-page document scrolls and zooms without freezing. The gap
    // between pages is the constant kGapPx (added per page index), so it stays the
    // same on screen at any zoom.
    int first_vis = -1, last_vis = -1;
    for (int i = 0; i < static_cast<int>(layout_.size()); ++i) {
      const PageSlot& s = layout_[i];
      const double top = origin_y + PageTopPx(i);
      const double bot = top + s.h_pts * zoom_;
      if (bot < plugin_rect_.y() || top > plugin_rect_.bottom()) {
        continue;  // off-screen
      }
      if (first_vis < 0) {
        first_vis = i;
      }
      last_vis = i;
      const SkBitmap& pb = PageBitmap(i);
      if (pb.drawsNothing()) {
        continue;
      }
      const double page_x = origin_x + (doc_w_pts_ - s.w_pts) / 2.0 * zoom_;
      // Scale the (possibly kMaxDim-clamped) bitmap to exactly fill the page's
      // on-screen slot: s.w_pts*zoom × s.h_pts*zoom. Using a fixed 1/doc_quality_
      // would leave a clamped page smaller than its slot at very high zoom, which
      // read as a growing inter-page gap.
      const float sx = static_cast<float>(s.w_pts * zoom_ / pb.width());
      const float sy = static_cast<float>(s.h_pts * zoom_ / pb.height());
      cc::PaintCanvasAutoRestore page_restore(canvas, /*save=*/true);
      canvas->translate(static_cast<float>(page_x), static_cast<float>(top));
      canvas->scale(sx, sy);
      sk_sp<SkImage> image = SkImages::RasterFromBitmap(pb);
      cc::PaintImage paint_image =
          cc::PaintImageBuilder::WithDefault()
              .set_id(cc::PaintImage::GetNextId())
              .set_image(std::move(image), cc::PaintImage::GetNextContentId())
              .TakePaintImage();
      canvas->drawImage(paint_image, 0, 0);
    }
    // Evict cached pages outside the visible window (+1 page of slack) so memory
    // stays bounded by what's on screen rather than the whole document.
    if (first_vis >= 0) {
      for (auto it = page_cache_.begin(); it != page_cache_.end();) {
        if (it->first < first_vis - 1 || it->first > last_vis + 1) {
          it = page_cache_.erase(it);
        } else {
          ++it;
        }
      }
    }
    // The viewer treats the plugin as "remote content" and hides its #sizer, so
    // there's no native scrollbar — draw our own (like the real PDF plugin) on the
    // right edge when the document is taller than the viewport. Geometry comes from
    // ThumbRect() so hit-testing in HandleInputEvent matches exactly.
    constexpr float kSbW = 12.0f;
    auto thumb_color = [](bool pressed, bool hover) {
      return pressed ? SkColorSetRGB(0x5f, 0x63, 0x68)    // pressed (Grey 700)
             : hover ? SkColorSetRGB(0x9a, 0xa0, 0xa6)    // hover   (Grey 500)
                     : SkColorSetRGB(0xbd, 0xc1, 0xc6);   // normal  (Grey 400)
    };
    cc::PaintFlags track;
    track.setColor(kBackdrop);  // blends in, like Chrome's overlay scrollbar

    // Vertical scrollbar (right edge).
    float tx, ty, tw, th;
    if (ThumbRect(&tx, &ty, &tw, &th)) {
      const float track_x = plugin_rect_.right() - kSbW;
      const float vp_h = static_cast<float>(plugin_rect_.height());
      canvas->drawRect(
          SkRect::MakeXYWH(track_x, plugin_rect_.y(), kSbW, vp_h), track);
      cc::PaintFlags thumb;
      thumb.setAntiAlias(true);
      thumb.setColor(thumb_color(dragging_, hovering_thumb_));
      canvas->drawRRect(
          SkRRect::MakeRectXY(SkRect::MakeXYWH(tx, ty, tw, th), 4.0f, 4.0f),
          thumb);

      // Page-number bubble beside the scrollbar while scrolling (auto-hides
      // ~1.5s after scrolling stops). The viewer's own indicator is disabled.
      if (!bubble_deadline_.is_null() &&
          base::TimeTicks::Now() < bubble_deadline_) {
        DrawPageBubble(canvas, track_x, ty + th / 2.0f);
      }
    }

    // Horizontal scrollbar (bottom edge) — appears when zoomed wider than the
    // viewport so the centered page can be panned left/right.
    float hx, hy, hw, hh;
    if (HThumbRect(&hx, &hy, &hw, &hh)) {
      const float track_y = plugin_rect_.bottom() - kSbW;
      const float vp_w = static_cast<float>(plugin_rect_.width());
      canvas->drawRect(
          SkRect::MakeXYWH(plugin_rect_.x(), track_y, vp_w, kSbW), track);
      cc::PaintFlags thumb;
      thumb.setAntiAlias(true);
      thumb.setColor(thumb_color(dragging_h_, hovering_hthumb_));
      canvas->drawRRect(
          SkRRect::MakeRectXY(SkRect::MakeXYWH(hx, hy, hw, hh), 4.0f, 4.0f),
          thumb);
    }
    return;
  }

  if (page_bitmap_.drawsNothing()) {
    return;
  }

  canvas->translate(plugin_rect_.x(), plugin_rect_.y());

  constexpr float kMargin = 8.0f;
  float avail_w = plugin_rect_.width() - 2 * kMargin;
  if (avail_w < 1.0f) {
    avail_w = plugin_rect_.width();
  }
  const float scale = avail_w / page_bitmap_.width();
  const float draw_w = page_bitmap_.width() * scale;
  const float ox = (plugin_rect_.width() - draw_w) / 2.0f;
  canvas->translate(ox, kMargin);
  canvas->scale(scale, scale);

  sk_sp<SkImage> image = SkImages::RasterFromBitmap(page_bitmap_);
  cc::PaintImage paint_image =
      cc::PaintImageBuilder::WithDefault()
          .set_id(cc::PaintImage::GetNextId())
          .set_image(std::move(image), cc::PaintImage::GetNextContentId())
          .TakePaintImage();
  canvas->drawImage(paint_image, 0, 0);
}

void JuxPdfPlugin::UpdateGeometry(const gfx::Rect& window_rect,
                                  const gfx::Rect& /*clip_rect*/,
                                  const gfx::Rect& /*unobscured_rect*/,
                                  bool /*is_visible*/) {
  plugin_rect_ = window_rect;
  if (page_pdfs_.empty() || load_failed_ || window_rect.width() <= 0) {
    return;
  }
  // The full-width page-0 raster is only used by Paint's pre-viewport branch.
  // Once the viewer has sent a viewport, Paint takes the stacked-layout branch
  // and never draws page_bitmap_, so rasterizing here is wasted work — skip it.
  if (!has_viewport_) {
    const int w = window_rect.width();
    if (rasterized_for_width_ == 0 ||
        std::abs(w - rasterized_for_width_) > rasterized_for_width_ / 10) {
      RasterizeForWidth(w);
      if (container_) {
        container_->Invalidate();
      }
    }
  }
}

double JuxPdfPlugin::DocPixelHeight() const {
  const int gaps = std::max(0, static_cast<int>(layout_.size()) - 1);
  return doc_h_pts_ * zoom_ + gaps * kGapPx;
}

double JuxPdfPlugin::PageTopPx(int i) const {
  return layout_[i].y_pts * zoom_ + i * kGapPx;
}

double JuxPdfPlugin::MaxScrollY() const {
  return std::max(0.0, DocPixelHeight() - plugin_rect_.height());
}

double JuxPdfPlugin::MaxScrollX() const {
  const double doc_w_px = doc_w_pts_ * zoom_;
  return std::max(0.0, doc_w_px - plugin_rect_.width());
}

void JuxPdfPlugin::SetScrollX(double x) {
  const double clamped = std::clamp(x, 0.0, MaxScrollX());
  if (clamped == scroll_x_) {
    return;
  }
  scroll_x_ = clamped;
  if (container_) {
    container_->Invalidate();
  }
}

void JuxPdfPlugin::SetScrollY(double y) {
  const double clamped = std::clamp(y, 0.0, MaxScrollY());
  target_scroll_y_ = clamped;  // keep the smooth-scroll target in sync
  if (clamped == scroll_y_) {
    return;
  }
  scroll_y_ = clamped;
  if (container_) {
    container_->Invalidate();
  }
}

void JuxPdfPlugin::ScheduleScrollAnimation() {
  if (scroll_anim_scheduled_) {
    return;
  }
  scroll_anim_scheduled_ = true;
  base::SingleThreadTaskRunner::GetCurrentDefault()->PostDelayedTask(
      FROM_HERE,
      base::BindOnce(&JuxPdfPlugin::StepScrollAnimation,
                     weak_factory_.GetWeakPtr()),
      base::Milliseconds(8));
}

void JuxPdfPlugin::StepScrollAnimation() {
  scroll_anim_scheduled_ = false;
  const double target = std::clamp(target_scroll_y_, 0.0, MaxScrollY());
  target_scroll_y_ = target;
  const double diff = target - scroll_y_;
  if (std::abs(diff) < 0.5) {
    if (scroll_y_ != target) {
      scroll_y_ = target;
      if (container_) {
        container_->Invalidate();
      }
    }
    return;
  }
  // Ease-out toward the target — fluid, like Chrome's smooth scrolling.
  scroll_y_ += diff * 0.28;
  ShowPageBubble();  // keep the page bubble alive while the animation runs
  if (container_) {
    container_->Invalidate();
  }
  ScheduleScrollAnimation();
}

int JuxPdfPlugin::CurrentPage() const {
  if (layout_.empty()) {
    return 1;
  }
  // The page occupying ~40% down the viewport reads as the "current" page.
  const double probe = scroll_y_ + plugin_rect_.height() * 0.4;
  int best = 0;
  for (int i = 0; i < static_cast<int>(layout_.size()); ++i) {
    if (PageTopPx(i) <= probe) {
      best = i;
    } else {
      break;
    }
  }
  return best + 1;
}

void JuxPdfPlugin::ShowPageBubble() {
  bubble_deadline_ = base::TimeTicks::Now() + base::Milliseconds(1400);
  if (!bubble_hide_scheduled_) {
    bubble_hide_scheduled_ = true;
    base::SingleThreadTaskRunner::GetCurrentDefault()->PostDelayedTask(
        FROM_HERE,
        base::BindOnce(&JuxPdfPlugin::MaybeHidePageBubble,
                       weak_factory_.GetWeakPtr()),
        base::Milliseconds(1450));
  }
}

void JuxPdfPlugin::MaybeHidePageBubble() {
  bubble_hide_scheduled_ = false;
  const base::TimeTicks now = base::TimeTicks::Now();
  if (now < bubble_deadline_) {
    // Scrolled again since we were scheduled — re-arm for the remaining time.
    bubble_hide_scheduled_ = true;
    base::SingleThreadTaskRunner::GetCurrentDefault()->PostDelayedTask(
        FROM_HERE,
        base::BindOnce(&JuxPdfPlugin::MaybeHidePageBubble,
                       weak_factory_.GetWeakPtr()),
        bubble_deadline_ - now + base::Milliseconds(20));
    return;
  }
  if (container_) {
    container_->Invalidate();  // repaint without the now-expired bubble
  }
}

void JuxPdfPlugin::DrawPageBubble(cc::PaintCanvas* canvas, float track_x,
                                 float center_y) {
  const std::string num = base::NumberToString(CurrentPage());
  // Bold ~13px Segoe UI, matching the rest of our print-preview chrome.
  sk_sp<SkFontMgr> mgr = skia::DefaultFontMgr();
  SkFontStyle style(SkFontStyle::kBold_Weight, SkFontStyle::kNormal_Width,
                    SkFontStyle::kUpright_Slant);
  sk_sp<SkTypeface> tf;
  if (mgr) {
    tf = mgr->matchFamilyStyle("Segoe UI", style);
    if (!tf) {
      tf = mgr->matchFamilyStyle(nullptr, style);
    }
  }
  SkFont font(tf, 13.0f);
  font.setEdging(SkFont::Edging::kAntiAlias);
  font.setSubpixel(true);
  const float text_w =
      font.measureText(num.c_str(), num.size(), SkTextEncoding::kUTF8);

  constexpr float kPadX = 14.0f, kBubH = 30.0f, kTri = 6.0f, kGap = 8.0f;
  const float bub_w = std::max(40.0f, text_w + 2 * kPadX);
  const float tip_x = track_x - kGap;
  const float body_right = tip_x - kTri;
  const float body_left = body_right - bub_w;
  const float cy = std::clamp(center_y, plugin_rect_.y() + kBubH / 2.0f,
                              plugin_rect_.bottom() - kBubH / 2.0f);
  const float top = cy - kBubH / 2.0f;

  cc::PaintFlags fill;
  fill.setAntiAlias(true);
  fill.setColor(SkColorSetARGB(0xF2, 0x5f, 0x63, 0x68));  // Chrome dark-grey bubble
  canvas->drawRRect(
      SkRRect::MakeRectXY(
          SkRect::MakeLTRB(body_left, top, body_right, top + kBubH), 8.0f, 8.0f),
      fill);
  // Pointer triangle toward the scrollbar.
  SkPathBuilder tri;
  tri.moveTo(body_right - 0.5f, cy - 7.0f);
  tri.lineTo(tip_x, cy);
  tri.lineTo(body_right - 0.5f, cy + 7.0f);
  tri.close();
  canvas->drawPath(tri.detach(), fill);
  // Centered white page number.
  SkFontMetrics fm;
  font.getMetrics(&fm);
  const float baseline = cy - (fm.fAscent + fm.fDescent) / 2.0f;
  cc::PaintFlags text;
  text.setAntiAlias(true);
  text.setColor(SK_ColorWHITE);
  sk_sp<SkTextBlob> blob = SkTextBlob::MakeFromString(num.c_str(), font);
  canvas->drawTextBlob(blob, body_left + (bub_w - text_w) / 2.0f, baseline, text);
}

bool JuxPdfPlugin::ThumbRect(float* x, float* y, float* w, float* h) const {
  const float vp_h = static_cast<float>(plugin_rect_.height());
  const double doc_h_px = DocPixelHeight();
  if (!(doc_h_px > vp_h + 1.0)) {
    return false;
  }
  constexpr float kSbW = 12.0f;
  constexpr float kPad = 2.0f;
  const float track_x = plugin_rect_.right() - kSbW;
  const float thumb_h =
      std::max(36.0f, static_cast<float>(vp_h * vp_h / doc_h_px));
  const float max_scroll = static_cast<float>(doc_h_px - vp_h);
  const float t =
      max_scroll > 0
          ? std::clamp(static_cast<float>(scroll_y_) / max_scroll, 0.0f, 1.0f)
          : 0.0f;
  const float thumb_y = plugin_rect_.y() + t * (vp_h - thumb_h);
  *x = track_x + kPad;
  *y = thumb_y + kPad;
  *w = kSbW - 2 * kPad;
  *h = thumb_h - 2 * kPad;
  return true;
}

bool JuxPdfPlugin::HThumbRect(float* x, float* y, float* w, float* h) const {
  const float vp_w = static_cast<float>(plugin_rect_.width());
  const double doc_w_px = doc_w_pts_ * zoom_;
  if (!(doc_w_px > vp_w + 1.0)) {
    return false;
  }
  constexpr float kSbW = 12.0f;
  constexpr float kPad = 2.0f;
  const float track_y = plugin_rect_.bottom() - kSbW;
  const float thumb_w =
      std::max(36.0f, static_cast<float>(vp_w * vp_w / doc_w_px));
  const float max_scroll = static_cast<float>(doc_w_px - vp_w);
  const float t =
      max_scroll > 0
          ? std::clamp(static_cast<float>(scroll_x_) / max_scroll, 0.0f, 1.0f)
          : 0.0f;
  const float thumb_x = plugin_rect_.x() + t * (vp_w - thumb_w);
  *x = thumb_x + kPad;
  *y = track_y + kPad;
  *w = thumb_w - 2 * kPad;
  *h = kSbW - 2 * kPad;
  return true;
}

blink::WebInputEventResult JuxPdfPlugin::HandleInputEvent(
    const blink::WebCoalescedInputEvent& event,
    ui::Cursor* /*cursor*/) {
  using Type = blink::WebInputEvent::Type;
  const blink::WebInputEvent& e = event.Event();
  const auto kHandled = blink::WebInputEventResult::kHandledApplication;
  const auto kNotHandled = blink::WebInputEventResult::kNotHandled;

  // Mouse-wheel: the viewer does not scroll remote content, so we own it.
  if (e.GetType() == Type::kMouseWheel) {
    // Ctrl+wheel is a zoom gesture — let it fall through to the viewer, which
    // owns zoom AND repositions the document. Consuming it here would scroll the
    // page during a zoom and leave it stuck at the top-left.
    if (e.GetModifiers() & blink::WebInputEvent::kControlKey) {
      return kNotHandled;
    }
    const auto& wheel = static_cast<const blink::WebMouseWheelEvent&>(e);
    // Horizontal: trackpad lateral scroll, or Shift+wheel, when the page is
    // wider than the viewport.
    const bool shift = (e.GetModifiers() & blink::WebInputEvent::kShiftKey) != 0;
    const double hdelta =
        wheel.delta_x != 0.0f ? wheel.delta_x : (shift ? wheel.delta_y : 0.0);
    if (hdelta != 0.0 && MaxScrollX() > 0.0) {
      SetScrollX(scroll_x_ - hdelta);
      ShowPageBubble();
      return kHandled;
    }
    // Vertical: add to the animation target so StepScrollAnimation eases (fluid).
    if (MaxScrollY() <= 0.0) {
      return kNotHandled;
    }
    target_scroll_y_ =
        std::clamp(target_scroll_y_ - wheel.delta_y, 0.0, MaxScrollY());
    ShowPageBubble();
    ScheduleScrollAnimation();
    return kHandled;
  }

  constexpr float kSbW = 12.0f;
  // Vertical scrollbar geometry.
  float tx, ty, tw, th;
  const bool has_vbar = ThumbRect(&tx, &ty, &tw, &th);
  const float vtrack_x = plugin_rect_.right() - kSbW;
  const float vp_h = static_cast<float>(plugin_rect_.height());
  const float thumb_full_h = th + 4.0f;  // re-add the 2*kPad inset
  const float vtravel = std::max(1.0f, vp_h - thumb_full_h);
  // Horizontal scrollbar geometry.
  float hx, hy, hw, hh;
  const bool has_hbar = HThumbRect(&hx, &hy, &hw, &hh);
  const float htrack_y = plugin_rect_.bottom() - kSbW;
  const float vp_w = static_cast<float>(plugin_rect_.width());
  const float thumb_full_w = hw + 4.0f;
  const float htravel = std::max(1.0f, vp_w - thumb_full_w);

  if (e.GetType() == Type::kMouseDown) {
    const auto& mouse = static_cast<const blink::WebMouseEvent&>(e);
    if (mouse.button == blink::WebPointerProperties::Button::kLeft) {
      const gfx::PointF p = mouse.PositionInWidget();
      // Vertical thumb / track.
      if (has_vbar && p.x() >= vtrack_x - 1.0f && p.x() <= plugin_rect_.right()) {
        if (p.y() < ty || p.y() > ty + th) {
          const float t = std::clamp(
              (p.y() - plugin_rect_.y() - thumb_full_h / 2.0f) / vtravel, 0.0f,
              1.0f);
          SetScrollY(t * MaxScrollY());
        }
        dragging_ = true;
        drag_anchor_mouse_y_ = p.y();
        drag_anchor_scroll_y_ = scroll_y_;
        ShowPageBubble();
        if (container_) {
          container_->Invalidate();
        }
        return kHandled;
      }
      // Horizontal thumb / track.
      if (has_hbar && p.y() >= htrack_y - 1.0f &&
          p.y() <= plugin_rect_.bottom()) {
        if (p.x() < hx || p.x() > hx + hw) {
          const float t = std::clamp(
              (p.x() - plugin_rect_.x() - thumb_full_w / 2.0f) / htravel, 0.0f,
              1.0f);
          SetScrollX(t * MaxScrollX());
        }
        dragging_h_ = true;
        drag_anchor_mouse_x_ = p.x();
        drag_anchor_scroll_x_ = scroll_x_;
        if (container_) {
          container_->Invalidate();
        }
        return kHandled;
      }
    }
  }

  if (e.GetType() == Type::kMouseMove) {
    const auto& mouse = static_cast<const blink::WebMouseEvent&>(e);
    const gfx::PointF p = mouse.PositionInWidget();
    if (dragging_) {
      const double dy = p.y() - drag_anchor_mouse_y_;
      SetScrollY(drag_anchor_scroll_y_ + dy * (MaxScrollY() / vtravel));
      ShowPageBubble();
      return kHandled;
    }
    if (dragging_h_) {
      const double dx = p.x() - drag_anchor_mouse_x_;
      SetScrollX(drag_anchor_scroll_x_ + dx * (MaxScrollX() / htravel));
      return kHandled;
    }
    // Hover: darken the thumb the pointer is over.
    const bool over_v = has_vbar && p.x() >= vtrack_x - 1.0f &&
                        p.x() <= plugin_rect_.right() && p.y() >= ty &&
                        p.y() <= ty + th;
    const bool over_h = has_hbar && p.y() >= htrack_y - 1.0f &&
                        p.y() <= plugin_rect_.bottom() && p.x() >= hx &&
                        p.x() <= hx + hw;
    if (over_v != hovering_thumb_ || over_h != hovering_hthumb_) {
      hovering_thumb_ = over_v;
      hovering_hthumb_ = over_h;
      if (container_) {
        container_->Invalidate();
      }
    }
  }

  if (e.GetType() == Type::kMouseLeave &&
      (hovering_thumb_ || hovering_hthumb_)) {
    hovering_thumb_ = false;
    hovering_hthumb_ = false;
    if (container_) {
      container_->Invalidate();
    }
  }

  if (e.GetType() == Type::kMouseUp && (dragging_ || dragging_h_)) {
    dragging_ = false;
    dragging_h_ = false;
    if (container_) {
      container_->Invalidate();
    }
    return kHandled;
  }

  return kNotHandled;
}

blink::WebPlugin* CreateJuxPdfPlugin(const blink::WebURL& url) {
  return new JuxPdfPlugin(url);
}

}  // namespace jux
