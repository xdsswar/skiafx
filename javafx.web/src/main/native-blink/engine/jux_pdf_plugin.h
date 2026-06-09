// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxPdfPlugin — minimal in-renderer PDF viewer for the print-preview document.
//
// Replaces the OOPIF-only pdf::CreateInternalPlugin. Blink streams the
// <embed src="chrome-untrusted://print/<id>/<page>/print.pdf"> bytes (served by
// PrintPreviewUIUntrusted) into this plugin via DidReceiveData; on completion we
// rasterize the first page with chrome_pdf::RenderPDFPageToBitmap (PDFium) and
// Paint() it scaled to fit the plugin rect. No extensions/guest_view, no
// dedicated PDF process, no OOPIF — it paints into the page's content layer and
// rides the existing OSR capture pipeline. v1 renders page 0 (multi-page/scroll
// is a follow-up). See jux_renderer_client.cc + CLAUDE.md.

#ifndef JUX_JUX_PDF_PLUGIN_H_
#define JUX_JUX_PDF_PLUGIN_H_

#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "base/containers/span.h"
#include "base/memory/weak_ptr.h"
#include "base/time/time.h"
#include "base/values.h"
#include "jux/jux_pdf.mojom.h"
#include "mojo/public/cpp/bindings/remote.h"
#include "pdf/post_message_receiver.h"
#include "pdf/v8_value_converter.h"
#include "third_party/blink/public/platform/web_url.h"
#include "third_party/blink/public/web/web_plugin.h"
#include "third_party/skia/include/core/SkBitmap.h"
#include "ui/gfx/geometry/rect.h"
#include "v8/include/v8.h"

namespace blink {
class WebPluginContainer;
}
namespace content {
class V8ValueConverter;
}

namespace jux {

// Implements the PDF-viewer scripting endpoints (PostMessageReceiver::Client to
// receive the viewer's viewport/scroll messages, V8ValueConverter for the gin
// receiver's v8↔base::Value conversion) in addition to being the WebPlugin, so
// Chrome's pdf_viewer_print.ts can drive scrolling/zoom of our rendered document.
class JuxPdfPlugin final : public blink::WebPlugin,
                           public chrome_pdf::PostMessageReceiver::Client,
                           public chrome_pdf::V8ValueConverter {
 public:
  explicit JuxPdfPlugin(const blink::WebURL& url);

  JuxPdfPlugin(const JuxPdfPlugin&) = delete;
  JuxPdfPlugin& operator=(const JuxPdfPlugin&) = delete;

  // blink::WebPlugin:
  bool Initialize(blink::WebPluginContainer* container) override;
  void Destroy() override;
  blink::WebPluginContainer* Container() const override;
  void UpdateAllLifecyclePhases(blink::DocumentUpdateReason) override {}
  void Paint(cc::PaintCanvas* canvas, const gfx::Rect& rect) override;
  void UpdateGeometry(const gfx::Rect& window_rect,
                      const gfx::Rect& clip_rect,
                      const gfx::Rect& unobscured_rect,
                      bool is_visible) override;
  void UpdateFocus(bool focused, blink::mojom::FocusType) override {}
  void UpdateVisibility(bool) override {}
  blink::WebInputEventResult HandleInputEvent(
      const blink::WebCoalescedInputEvent& event,
      ui::Cursor* cursor) override;
  void DidReceiveResponse(const blink::WebURLResponse&) override {}
  void DidReceiveData(base::span<const char> data) override;
  void DidFinishLoading() override;
  void DidFailLoading(const blink::WebURLError&) override;
  // Returns the scriptable object the viewer calls postMessage() on.
  v8::Local<v8::Object> V8ScriptableObject(v8::Isolate* isolate) override;

  // chrome_pdf::PostMessageReceiver::Client — viewer → plugin messages (viewport).
  void OnMessage(const base::DictValue& message) override;
  // chrome_pdf::V8ValueConverter — used by the gin receiver to convert messages.
  std::unique_ptr<base::Value> FromV8Value(
      v8::Local<v8::Value> value,
      v8::Local<v8::Context> context) override;

 private:
  ~JuxPdfPlugin() override;

  // Binds the browser PDF-data interface and loads page 0 from the <embed src>.
  void StartLoad();

  // Ask the browser for a preview PDF page (the chrome://print/pdf frame can't
  // fetch the cross-scheme byte-server). `dest_index` is the page slot the bytes
  // belong to; reply arrives at OnPreviewPageFetched.
  void FetchPreviewData(const std::string& url, int dest_index);
  void OnPreviewPageFetched(int dest_index, const std::vector<uint8_t>& data);

  // Viewer scripting: a new preview generation (settings change / first load) and
  // per-page delivery. This is how the print preview applies settings, custom
  // page ranges, and multi-page documents — each regeneration re-sends these.
  void HandleResetPrintPreviewMode(const base::DictValue& message);
  void HandleLoadPreviewPage(const base::DictValue& message);

  // Rebuilds the page layout from the currently-loaded pages and tells the viewer
  // (dimensions + progress). Called after each page arrives.
  void RebuildAndNotify();

  // Scrolling. The viewer treats the plugin as remote content and does NOT scroll
  // it on wheel/drag, so the plugin owns scrolling: wheel animates toward a target
  // (fluid, like Chrome) and scrollbar-thumb drag sets the position directly.
  // Total stacked document height in screen px: page content scaled by zoom plus
  // the constant (un-zoomed) inter-page gaps.
  double DocPixelHeight() const;
  // Screen Y of the top of layout page `i` relative to the document stack top,
  // i.e. y_pts*zoom + i*gap. Add the document origin to get widget coordinates.
  double PageTopPx(int i) const;
  double MaxScrollY() const;   // 0 when the doc fits the viewport vertically
  double MaxScrollX() const;   // 0 when the doc fits the viewport horizontally
  void SetScrollY(double y);   // clamp to [0, MaxScrollY()], repaint (immediate)
  void SetScrollX(double x);   // clamp to [0, MaxScrollX()], repaint (immediate)
  void ScheduleScrollAnimation();  // start/continue the smooth-scroll tick
  void StepScrollAnimation();      // ease scroll_y_ toward target_scroll_y_
  // Geometry of the vertical/horizontal scrollbar thumbs in plugin (widget)
  // coordinates; return false when that scrollbar is not shown (document fits).
  bool ThumbRect(float* x, float* y, float* w, float* h) const;
  bool HThumbRect(float* x, float* y, float* w, float* h) const;

  // 1-based page number under the viewport (used by the scroll bubble).
  int CurrentPage() const;
  // Page-number bubble shown beside the scrollbar while scrolling. ShowPageBubble
  // (re)arms the ~1.5s auto-dismiss; MaybeHidePageBubble repaints once it expires.
  void ShowPageBubble();
  void MaybeHidePageBubble();
  void DrawPageBubble(cc::PaintCanvas* canvas, float track_x, float center_y);

  // Posts a message to the embedding PDF-viewer JS (chrome's pdf_viewer_print.ts)
  // via a DOM 'message' event — the same channel the real PDF plugin uses. Drives
  // the viewer's load state so it shows the document + lifts the loading overlay.
  void PostMessageToViewer(base::Value message);

  // Rasterize page 0 of the first loaded PDF into page_bitmap_ (BGRA), sized to
  // fit the
  // current plugin width. Used for the initial frame before the viewer sends a
  // viewport (scroll) message.
  void RasterizeForWidth(int css_width);

  // (Re)builds layout_ (one entry per printable page, stacked vertically) and the
  // document size doc_w_pts_/doc_h_pts_ from the loaded PDFs. Clears the page
  // cache. Called whenever the set of loaded pages changes.
  void BuildLayout();

  // Returns page `layout_index` rasterized at the current zoom (×doc_quality_),
  // rendering+caching on demand. Empty bitmap if the index is invalid. Only the
  // pages near the viewport are kept resident (see Paint()), so memory is bounded
  // regardless of document length.
  const SkBitmap& PageBitmap(int layout_index);

  const blink::WebURL url_;
  blink::WebPluginContainer* container_ = nullptr;
  mojo::Remote<jux::mojom::PdfDataProvider> pdf_provider_;
  std::unique_ptr<content::V8ValueConverter> v8_value_converter_;
  v8::Global<v8::Object> scriptable_receiver_;

  // Loaded preview pages keyed by destination index. For a printed PDF file this
  // is a single entry [0] holding the whole multi-page document; for a printed
  // web page it is one single-page PDF per slot, filled in via loadPreviewPage.
  std::map<int, std::vector<uint8_t>> page_pdfs_;
  int page_count_ = 0;       // expected pages (0 = a complete PDF in slot 0)
  bool load_failed_ = false;
  bool grayscale_ = false;   // "Black and white" color option (render grayscale)

  SkBitmap page_bitmap_;       // rasterized page 0, BGRA (initial frame)
  int rasterized_for_width_ = 0;  // css width page_bitmap_ was rendered for
  gfx::Rect plugin_rect_;      // current geometry (window/CSS px)

  // Page layout: one entry per printable page, stacked top-to-bottom. Pages are
  // rasterized lazily and only while near the viewport, so an N-page document
  // never needs an N-page-tall bitmap (which blew past the memory cap).
  struct PageSlot {
    int slot;        // key into page_pdfs_
    int page;        // page index within that PDF
    double w_pts;    // page size in PDF points
    double h_pts;
    double y_pts;    // top offset of this page in the stacked layout (points)
  };
  std::vector<PageSlot> layout_;
  std::map<int, SkBitmap> page_cache_;  // layout index → bitmap at cache_zoom_
  double cache_zoom_ = 0.0;             // zoom page_cache_ was rendered at

  double doc_quality_ = 2.0;       // supersample factor for page bitmaps
  double doc_w_pts_ = 0.0;         // document width in PDF points (for centering)
  double doc_h_pts_ = 0.0;         // total document height in PDF points
  double zoom_ = 1.0;
  double scroll_x_ = 0.0;          // CSS px
  double scroll_y_ = 0.0;          // CSS px
  bool has_viewport_ = false;      // a 'viewport' message has arrived

  // Scrollbar-thumb drag state (plugin owns scrolling for remote content).
  bool dragging_ = false;              // vertical thumb drag
  bool hovering_thumb_ = false;        // pointer over the vertical thumb
  double drag_anchor_mouse_y_ = 0.0;   // PositionInWidget().y() at mousedown
  double drag_anchor_scroll_y_ = 0.0;  // scroll_y_ at mousedown
  bool dragging_h_ = false;            // horizontal thumb drag
  bool hovering_hthumb_ = false;       // pointer over the horizontal thumb
  double drag_anchor_mouse_x_ = 0.0;   // PositionInWidget().x() at mousedown
  double drag_anchor_scroll_x_ = 0.0;  // scroll_x_ at mousedown

  // Smooth (fluid) wheel scrolling: scroll_y_ eases toward target_scroll_y_.
  double target_scroll_y_ = 0.0;
  bool scroll_anim_scheduled_ = false;

  // Page-number bubble: visible while bubble_deadline_ is in the future.
  base::TimeTicks bubble_deadline_;
  bool bubble_hide_scheduled_ = false;

  base::WeakPtrFactory<JuxPdfPlugin> weak_factory_{this};
};

}  // namespace jux

#endif  // JUX_JUX_PDF_PLUGIN_H_
