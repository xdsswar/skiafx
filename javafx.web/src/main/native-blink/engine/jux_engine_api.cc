// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// jux_engine_api.cc — implementation of the engine C API.
//
// Thread model (new architecture):
//   Main thread → IS the browser UI thread. ContentMain runs here.
//     Chromium's message loop dispatches tasks AND Win32 messages.
//   Command polling → runs on the UI thread via a native Win32 timer
//     (WM_TIMER), which bypasses Chromium's DisallowBlocking for
//     posted tasks. This is critical: widget creation calls
//     CreateWindowExW (blocking), which would DCHECK if called from
//     a Chromium-posted task on the UI thread.
//   Renderer/GPU/utility → separate child processes (multi-process mode).
//
// The exe is a thin wrapper with zero Chromium dependencies. All
// Chromium code lives in this DLL. The exe calls JuxRunBrowser()
// (browser mode) or JuxSubprocessMain() (child mode) via import lib.

#ifdef UNSAFE_BUFFERS_BUILD
#pragma allow_unsafe_buffers
#endif

#include "jux/jux_engine_api.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <unordered_map>

#include "base/containers/span.h"

#include "base/at_exit.h"
#include "base/base_paths.h"
#include "base/command_line.h"
#include "base/files/file_path.h"
#include "base/functional/bind.h"
#include "base/functional/callback_helpers.h"
#include "base/json/json_writer.h"
#include "base/logging.h"
#include "base/files/file_util.h"
#include "base/memory/raw_ptr.h"
#include "base/path_service.h"
#include "base/strings/escape.h"
#include "base/strings/string_split.h"
#include "base/strings/utf_string_conversions.h"
#include "base/synchronization/waitable_event.h"
#include "base/task/single_thread_task_runner.h"
#include "base/task/thread_pool.h"
#include "base/timer/timer.h"
#include "base/threading/thread_restrictions.h"
#include "base/memory/shared_memory_mapping.h"
#include "build/build_config.h"
// skia-fx: viz-driven main-frame capture (FrameSinkVideoCapturer). The
// capturer follows the page's surface across resizes/fullscreen and keeps
// streaming the last-active content while the renderer commits the new
// size — the polling CopyFromSurface path goes dark for the whole sync
// window instead (the fullscreen/monitor-move freeze). Built directly on
// Chromium's viz/content APIs (this engine has no CEF anywhere).
#include "components/viz/common/surfaces/video_capture_target.h"
#include "components/viz/host/client_frame_sink_video_capturer.h"
#include "components/viz/host/host_frame_sink_manager.h"
#include "content/browser/compositor/surface_utils.h"
#include "content/public/browser/web_contents_observer.h"
#include "media/base/video_types.h"
#include "media/capture/mojom/video_capture_buffer.mojom.h"
#include "content/public/app/content_main.h"
#include "content/public/app/content_main_runner.h"
#include "content/public/browser/browser_task_traits.h"
#include "content/public/common/content_paths.h"
#include "content/public/common/content_switches.h"
#include "content/public/common/isolated_world_ids.h"
#include "base/location.h"
#include "base/time/time.h"
#include "components/viz/common/frame_sinks/copy_output_result.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/render_widget_host.h"
#include "content/public/browser/render_widget_host_view.h"
#include "content/public/browser/web_contents.h"
#include "content/public/browser/navigation_controller.h"
#include "content/public/browser/navigation_entry.h"
#include "content/public/browser/navigation_entry_restore_context.h"
#include "content/public/browser/restore_type.h"
#include "content/public/common/referrer.h"
#include "third_party/blink/public/common/page_state/page_state.h"
#include "services/network/public/cpp/shared_url_loader_factory.h"
#include "ui/base/page_transition_types.h"
#include "third_party/blink/public/common/user_agent/user_agent_metadata.h"
// skia-fx OSR HiDPI: SetScaleOverrideForCapture lives on the internal
// RenderWidgetHostViewBase (not the public RenderWidgetHostView). It is the
// supported per-view device-scale override used by Chromium's own capture
// paths. Requires the //content/browser GN dep (see BUILD.gn).
#include "content/browser/renderer_host/render_widget_host_view_base.h"
#include "third_party/blink/public/common/input/web_input_event.h"
#include "third_party/blink/public/common/input/web_keyboard_event.h"
#include "third_party/blink/public/common/input/web_mouse_event.h"
#include "third_party/blink/public/common/input/web_mouse_wheel_event.h"
#include "third_party/blink/public/common/input/web_pointer_properties.h"
#include "components/input/native_web_keyboard_event.h"
#include "ui/gfx/geometry/point_f.h"
#include "ui/gfx/geometry/rect.h"
#include "ui/gfx/geometry/size.h"
#include "jux/jux_browser_client.h"
#include "jux/jux_browser_main_parts.h"
#include "jux/jux_command_dispatch.h"
#include "jux/jux_dom.mojom.h"
#include "jux/jux_dom_client_impl.h"
#include "jux/jux_event_types.h"
#include "base/strings/utf_string_conversions.h"
#include "ui/gfx/geometry/point.h"
#include "ui/gfx/geometry/rect.h"
#include "jux/jux_heartbeat.h"
#include "jux/jux_ipc.h"
#include "jux/jux_main_delegate.h"
#include "jux/jux_ring_buffer.h"
#include "jux/jux_web_contents_delegate.h"
#include "jux/jux_js_dialog_manager.h"
#include "jux/jux_download_manager_delegate.h"
#include "jux/jux_login_delegate.h"
#include "jux/jux_permission_manager.h"
#include "jux/jux_widget_delegate.h"
#include "jux/jux_widget_observer.h"
#include "mojo/public/cpp/bindings/associated_remote.h"
#include "mojo/public/cpp/bindings/callback_helpers.h"
#include "mojo/public/cpp/bindings/pending_remote.h"
#include "third_party/blink/public/common/associated_interfaces/associated_interface_provider.h"
#include "ui/aura/window.h"
#include "ui/aura/window_delegate.h"
#include "ui/aura/window_tree_host.h"
#include "ui/base/cursor/cursor.h"
#include "ui/compositor/compositor.h"
#include "ui/gfx/geometry/rect.h"
#include "ui/views/controls/webview/webview.h"
#include "ui/views/widget/widget.h"
#include "ui/views/widget/widget_delegate.h"
#include "url/gurl.h"

#include "printing/buildflags/buildflags.h"
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
#include "base/memory/ref_counted_memory.h"
#include "chrome/browser/printing/pdf_nup_converter_client.h"
#include "components/printing/browser/print_composite_client.h"
#include "components/printing/browser/print_to_pdf/pdf_print_result.h"
#include "components/printing/browser/print_to_pdf/pdf_print_utils.h"
#include "jux/print_preview/shim/jux_print_preview_hook.h"
#include "chrome/browser/printing/print_preview_data_service.h"
#include "chrome/browser/printing/print_preview_dialog_controller.h"
#include "chrome/browser/printing/print_view_manager.h"
#include "chrome/browser/ui/webui/print_preview/print_preview_ui.h"
#include "content/public/browser/web_ui.h"
#include "jux/jux_select_dropdown.h"
#include "jux/print_preview/shim/jux_print_preview_hook.h"
#include "pdf/pdf.h"
#endif

#if BUILDFLAG(IS_WIN)
#include <windows.h>
#include <dwmapi.h>
#pragma comment(lib, "dwmapi.lib")
#include "content/public/app/sandbox_helper_win.h"
#include "sandbox/win/src/sandbox_types.h"
#endif

// =========================================================================
// Global state — all access must be on the UI thread except where noted.
// =========================================================================

// Defined in jux_command_dispatch.cc — set by CommandDispatcher::OnCreateWindow
// before JuxCreateWebContents is called. Used here to pass the EventWriter
// and SharedMemoryChannel to JuxWidgetDelegate and JuxWidgetObserver.
namespace jux {
extern EventWriter* g_callback_evt_writer;
extern ipc::SharedMemoryChannel* g_callback_channel;
}  // namespace jux

namespace {

// Number of frame slots in the channel's data region. MUST match
// MemoryLayout.FRAME_BUFFER_COUNT on the Java side (which sizes the region as
// FRAME_SLOT_BYTES * this). 3 slots give the consumer a full extra frame of
// headroom so the producer can't lap into the slot Java is mid-reading at
// ~60fps on both ends — that lap is what caused the occasional flicker with 2.
constexpr size_t kFrameBufferCount = 3;

// Capture cadence. Steady state is ~60 fps; for a short burst after a resize we
// tick ~4× faster so the reflowed, correctly-sized frame is grabbed promptly
// instead of waiting up to a full steady tick. kResizeFastFrames bounds the
// burst (≈ kResizeFastFrames × kResizeFastIntervalMs of accelerated capture)
// so a continuous drag-resize stays responsive while an idle page returns to
// the steady rate. Picked so the burst covers a typical reflow (~150–250 ms).
constexpr int kCaptureIntervalMs = 16;
constexpr int kResizeFastIntervalMs = 4;
constexpr int kResizeFastFrames = 48;

// OSR popup frame slots, carved off the END of the data region (after the main
// slots). These MUST match MemoryLayout.java
// (POPUP_FRAME_BUFFER_COUNT / MAX_POPUP_WIDTH / MAX_POPUP_HEIGHT) exactly, or the
// two sides compute different slot offsets and read corrupt pixels.
constexpr size_t kPopupFrameBufferCount = 2;
constexpr size_t kPopupMaxWidth = 1280;
constexpr size_t kPopupMaxHeight = 1600;
constexpr size_t kPopupSlotBytes = kPopupMaxWidth * kPopupMaxHeight * 4u;
constexpr size_t kPopupRegionBytes = kPopupSlotBytes * kPopupFrameBufferCount;

// Print-preview modal frame slots, carved off the data region BETWEEN the main
// slots and the popup region (physical order [main][preview][popup], so the popup
// offset stays at the very end). MUST match MemoryLayout.java
// (PREVIEW_FRAME_BUFFER_COUNT / MAX_PREVIEW_WIDTH / MAX_PREVIEW_HEIGHT) exactly.
constexpr size_t kPreviewFrameBufferCount = 2;
constexpr size_t kPreviewMaxWidth = 1280;
constexpr size_t kPreviewMaxHeight = 1600;
constexpr size_t kPreviewSlotBytes = kPreviewMaxWidth * kPreviewMaxHeight * 4u;
constexpr size_t kPreviewRegionBytes = kPreviewSlotBytes * kPreviewFrameBufferCount;

// Registered callbacks. Set by JuxSetCallbacks (any thread, before use).
JuxCallbacks g_callbacks = {};  // POD struct, no exit-time destructor.

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
// Handle of the active off-screen chrome://print preview WebContents, or 0 when
// none is open. UI-thread only. While set, the print preview "takes over" the
// view surface: only its captured frames are emitted as kFrameReady (the
// initiator's are suppressed — it's hidden behind the full-tab modal, exactly
// like Chrome), and forwarded mouse/keyboard input is redirected to it. This
// makes the preview a modal overlay with no second capture surface needed.
// Cleared (→ initiator resumes) when the preview is closed.
JuxWebContentsHandle g_print_preview_handle = 0;

// The Skia-rendered <select> drop-down currently open over the preview, or null.
// The preview's native Blink page-popup can't composite (hidden + captured), so
// we draw the option list ourselves and composite it through the popup overlay
// channel. Raw owning pointer (trivial global type — no exit-time destructor);
// always created/destroyed on the UI thread, freed in DismissPreviewDropdown.
jux::SkiaDropdown* g_preview_dropdown = nullptr;
uint32_t g_preview_dropdown_popup_id = 0;
#endif

// ---------------------------------------------------------------------------
// skia-fx: viz-capturer-driven main-frame capture.
//
// One JuxVideoConsumer per WebContentsEntry. It owns a
// viz::ClientFrameSinkVideoCapturer targeted at the page's frame sink and
// receives BGRA frames (media::PIXEL_FORMAT_ARGB) pushed by viz whenever the
// content changes — replacing the per-tick CopyFromSurface polling for the
// MAIN frame (popups / print preview / cursor polling stay on the tick).
//
// Why: polling CopyFromSurface targets the CURRENT (pending) surface; after a
// resize/fullscreen the pending surface doesn't activate until the renderer
// commits at the new size, so every poll fails and the WebView freezes for
// the whole relayout (measured 2–4 s on YouTube). The capturer is Chromium's
// tab-capture mechanism: it follows the surface across transitions and keeps
// delivering the last-active content, so frames flow continuously — the
// transition shows the old-size page (undistorted, self-described logical
// size) until the new size lands. Kill switch: --jux-poll-capture (set from
// Java via -Dskia.webview.pollCapture=true) restores the legacy polling.
//
// UI-thread only (capturer callbacks arrive on the creating sequence). The
// WebContentsObserver side re-targets the capturer when the page's frame
// sink changes (cross-process navigation, renderer crash recovery).
// ---------------------------------------------------------------------------
class JuxVideoConsumer final : public viz::mojom::FrameSinkVideoConsumer,
                               public content::WebContentsObserver {
 public:
  JuxVideoConsumer(JuxWebContentsHandle handle, content::WebContents* wc);
  ~JuxVideoConsumer() override;

  JuxVideoConsumer(const JuxVideoConsumer&) = delete;
  JuxVideoConsumer& operator=(const JuxVideoConsumer&) = delete;

  void StartCapture();
  void RequestRefreshFrame();

  // viz::mojom::FrameSinkVideoConsumer:
  void OnFrameCaptured(
      media::mojom::VideoBufferHandlePtr data,
      media::mojom::VideoFrameInfoPtr info,
      const gfx::Rect& content_rect,
      mojo::PendingRemote<viz::mojom::FrameSinkVideoConsumerFrameCallbacks>
          callbacks) override;
  void OnNewCaptureVersion(const media::CaptureVersion& capture_version) override;
  void OnFrameWithEmptyRegionCapture() override;
  void OnStopped() override;
  void OnLog(const std::string& message) override;

  // content::WebContentsObserver:
  void PrimaryPageChanged(content::Page& page) override;
  void RenderViewHostChanged(content::RenderViewHost* old_host,
                             content::RenderViewHost* new_host) override;

 private:
  void RetargetToCurrentView();

  const JuxWebContentsHandle handle_;
  std::unique_ptr<viz::ClientFrameSinkVideoCapturer> capturer_;
};

// Active WebContents indexed by handle. Only accessed on the UI thread.
struct WebContentsEntry {
  std::unique_ptr<content::WebContents> web_contents;
  std::unique_ptr<jux::JuxWebContentsDelegate> delegate;

  // views::Widget + views::WebView — the standard Chromium approach for
  // hosting WebContents in a desktop window. The Widget creates a proper
  // DesktopNativeWidgetAura → DesktopWindowTreeHostWin chain that correctly
  // wires the compositor to the viz service. Bare aura::WindowTreeHost
  // crashes during first-frame composition because the compositor pipeline
  // isn't properly set up.
  raw_ptr<views::Widget> widget = nullptr;        // Owned by the native widget.
  raw_ptr<views::WebView> web_view = nullptr;     // Owned by the widget's view tree.

  // Custom delegate that intercepts close requests (X button) and
  // forwards them to Java. Not owned — the Widget takes ownership via
  // delegate.release() during Init.
  raw_ptr<jux::JuxWidgetDelegate> widget_delegate = nullptr;

  // Observer that forwards resize/move/focus events to Java.
  std::unique_ptr<jux::JuxWidgetObserver> widget_observer;

  // Phase 3 DOM bridge — one per WebContents. The remote points at the
  // renderer's JuxDomHandlerImpl (bound on demand from the main frame),
  // and the client impl receives DOM events fired by the renderer.
  std::unique_ptr<jux::JuxDomClientImpl> dom_client;
  mojo::AssociatedRemote<jux::mojom::JuxDomHandler> dom_handler_remote;
  // The primary main frame the remote above is bound to. An associated
  // remote stays is_bound()==true even after a navigation swaps the
  // RenderFrameHost (common with RenderDocument), so we compare against
  // the live primary main frame to detect a stale binding and rebind.
  // Without this, post-navigation JsEval/DOM calls are sent to the dead
  // old frame's endpoint and their replies are silently dropped.
  raw_ptr<content::RenderFrameHost> dom_handler_rfh = nullptr;
  // Whether SetClient has been invoked on the current pipe. Reset when
  // the pipe is rebound (navigation / crash recovery).
  bool dom_client_bound = false;

  // Capturer handle that keeps Chromium's compositor producing frames
  // while the OS widget is still hidden (pre-first-show). Without this
  // Chromium suppresses frame production for hidden widgets, so when
  // Java finally calls kShow the compositor has to raster + composite
  // from scratch — showing a blank window for several frames until the
  // first real frame lands. With a capturer registered (stay_hidden =
  // false), the compositor behaves as if the contents were being
  // captured and keeps producing frames, so the first visible frame
  // after kShow is already composited and ready. Released in OnShow
  // once the window is visible and normal occlusion/throttling applies.
  base::ScopedClosureRunner capture_handle;

  // Last cursor type forwarded to Java (a com.sun.webkit.CursorManager constant),
  // so the capture loop only emits kCursorChanged on an actual change. -2 means
  // "nothing sent yet" (distinct from any real type, including POINTER=0).
  int last_cursor_type = -2;

  // Mouse-wheel scroll-latching state. Injected wheels must carry phase
  // transitions (kPhaseBegan → kPhaseChanged → kPhaseEnded); the begin latches
  // the scroll to the element under the cursor and the end releases it. We
  // replicate Chromium's MouseWheelPhaseHandler: a 500 ms idle timer fires a
  // synthetic zero-delta kPhaseEnded so the next scroll re-hit-tests (lets a
  // wheel over an inner scroller target that scroller, not the page).
  bool wheel_scrolling = false;
  gfx::PointF last_wheel_pos;
  int last_wheel_modifiers = 0;
  std::unique_ptr<base::OneShotTimer> wheel_end_timer;

  // Last capture scale set via JuxSetOffscreenSize (= the JavaFX render scale;
  // the captured frame is view_DIP × this). Used by JuxCaptureTick to size the
  // capture in DEVICE px so HiDPI frames are downscaled to fit a slot rather
  // than slipping past a DIP-measured guard and getting dropped.
  float capture_scale = 1.0f;

  // viz-capturer main-frame capture (see JuxVideoConsumer above). Null when
  // the --jux-poll-capture kill switch selects the legacy polling path; the
  // capture tick skips main-frame CopyFromSurface whenever this is set.
  std::unique_ptr<JuxVideoConsumer> video_consumer;

  // Last logical (DIP) size commanded via JuxSetOffscreenSize. The widget's
  // DIP size can drift under it when the hidden window's display DSF changes
  // over fixed pixel bounds (DIP = pixels / DSF) — observed as the page
  // shrinking to old_logical/scale after a cross-DPI monitor move. The
  // capture tick re-asserts SetSize when the view's DIP size diverges.
  // (--force-device-scale-factor=1 pins the DSF so this should never fire;
  // kept as a self-healing backstop.) UI-thread only.
  int last_logical_w = 0;
  int last_logical_h = 0;

  // The SetScaleOverrideForCapture multiplier currently applied to the view
  // (0 = never applied). The desired override is capture_scale / host_dsf,
  // where host_dsf is the HIDDEN capture window's monitor DSF — which updates
  // ASYNCHRONOUSLY (its WM_DPICHANGED) when Java drags the WebView across a
  // DPI boundary and the kSetPosition sync moves this window after the
  // kSetSize that computed the override. ApplyCaptureScaleOverride()
  // re-derives the desired value every capture tick and re-applies only on a
  // real change, so a stale-DSF override self-heals within one tick instead
  // of permanently rasterizing at the wrong density (whose oversized frames
  // the slot guard then drops forever — the frozen/blank-WebView-after-
  // monitor-move bug). UI-thread only.
  float applied_capture_override = 0.0f;

  // Resize fast-capture burst. After a size change the page must reflow before a
  // correctly-sized frame can be captured; at the steady 16 ms cadence that
  // reflowed frame can wait up to a full tick, so a small→maximize is slow to
  // "fit" (the prior, stale-sized frame shows until then). For a short window
  // after each JuxSetOffscreenSize we tick faster (see kResizeFastIntervalMs) so
  // the new frame lands within a few ms of the reflow. Decremented per tick by
  // JuxCaptureTick; UI-thread only (both setter and tick run on the UI thread).
  int fast_capture_frames = 0;

  // OSR popup (Blink page-popup: <select>/<input type=color>/<datalist>).
  // The popup is a real aura window we DWM-cloak (invisible) + release its
  // capture, then capture its pixels and forward synthetic input. We never cache
  // the popup's RenderWidgetHostView (it can be freed between ticks) — every use
  // re-queries WebContents::GetPopupWidgets(). popup_active tracks open/closed so
  // we cloak once and emit kPopupClosed on the close edge; popup_capture_toggle
  // throttles capture to ~30fps.
  bool popup_active = false;
  bool popup_cloaked = false;
  bool popup_capture_toggle = false;
};
// Heap-allocated to avoid exit-time destructor (Chromium clang enforces this).
// Leaked intentionally — cleaned up by JuxShutdown().
auto* g_web_contents_map =
    new std::unordered_map<JuxWebContentsHandle, WebContentsEntry>();

// ---------------------------------------------------------------------------
// (Re-)applies the capture device-scale override so the page rasterizes at
// the JavaFX render scale (entry.capture_scale) regardless of which monitor
// the hidden capture window sits on.
//
// SetScaleOverrideForCapture is a MULTIPLIER on the view's FRESH display DSF
// (UpdateScreenInfo multiplies GetNewScreenInfosForUpdate().current()
// .device_scale_factor — the RAW monitor DSF, NOT the already-overridden
// value GetDeviceScaleFactor() returns). The aura host window's
// device_scale_factor IS that raw monitor DSF, so it is the correct divisor
// to land effective == capture_scale.
//
// The divisor is a moving target: when Java drags the WebView across a DPI
// boundary, kSetPosition moves the hidden window and its host DSF updates
// asynchronously (own WM_DPICHANGED on this UI thread) — typically AFTER the
// kSetSize that recomputed the override. A one-shot computation therefore
// goes stale: effective density lands at scale × (new_dsf / old_dsf), the
// captured frames overflow their SHM slot, and the slot guard drops every
// frame → the WebView freezes blank until the next resize. So this helper is
// called BOTH from JuxSetOffscreenSize AND every JuxCaptureTick: it
// recomputes the desired override from the CURRENT host DSF and re-applies
// only on a real change (epsilon-compared — SetScaleOverrideForCapture
// triggers a renderer UpdateScreenInfo, so don't thrash it). On re-apply it
// also grants the resize fast-capture burst so the re-rastered frame lands
// within a few ms. UI thread only. Returns the effective override in force.
float ApplyCaptureScaleOverride(WebContentsEntry& entry,
                                content::RenderWidgetHostView* view) {
  if (!view) {
    return entry.applied_capture_override;
  }
  float scale = entry.capture_scale > 0.0f ? entry.capture_scale : 1.0f;
  float host_dsf = 1.0f;
  if (entry.widget && entry.widget->GetNativeWindow() &&
      entry.widget->GetNativeWindow()->GetHost()) {
    host_dsf = entry.widget->GetNativeWindow()->GetHost()->device_scale_factor();
    if (host_dsf <= 0.0f) host_dsf = 1.0f;
  }
  const float desired = scale / host_dsf;
  if (std::fabs(desired - entry.applied_capture_override) <= 0.001f) {
    return entry.applied_capture_override;
  }
  if (::getenv("OPENJFX_SKIA_WEBDPI_DIAG")) {
    float view_dsf = static_cast<content::RenderWidgetHostViewBase*>(view)
                         ->GetDeviceScaleFactor();
    VLOG(1) << "[webdpi] ApplyCaptureScaleOverride jfxScale=" << scale
            << " hostDSF=" << host_dsf
            << " viewDSF(screenInfo,maybe-overridden)=" << view_dsf
            << " override " << entry.applied_capture_override << " -> "
            << desired << "  (want captured effectiveDSF == jfxScale)";
  }
  static_cast<content::RenderWidgetHostViewBase*>(view)
      ->SetScaleOverrideForCapture(desired);
  entry.applied_capture_override = desired;
  // The renderer re-rasterizes at the new density after an UpdateScreenInfo
  // round-trip; burst-capture so the corrected frame lands fast.
  entry.fast_capture_frames = kResizeFastFrames;
  return desired;
}

// ---------------------------------------------------------------------------
// Off-screen frame capture (software). A self-scheduling CopyFromSurface loop
// on the UI thread snapshots the main frame's composited surface (the whole
// page — all iframes folded in by viz) to an SkBitmap each tick. The
// continuous capture also keeps viz + the UI message loop active, so commands
// drain and JS results are delivered even while the windowless background page
// is otherwise idle.
//
// Each captured frame's BGRA8888 pixels are copied into the channel's data
// region (double-buffered: two viewport-sized slots), and the slot index +
// dimensions are published to Java via the kFrameReady event. Java maps the
// same region and composites the frame into the WebView scene node — there is
// no Chromium OS window; the JavaFX node is the only surface.
// ---------------------------------------------------------------------------
void JuxCaptureTick(JuxWebContentsHandle handle);

// Translate Blink's cursor enum to a com.sun.webkit.CursorManager constant (the
// integer Java's CursorManagerImpl maps to a javafx.scene.Cursor). Done here by
// NAME because the two enumerations are ordered differently (e.g. Blink kIBeam=3
// vs CursorManager MOVE=3); resolving against the named symbols avoids any
// fragile numeric coupling. Mirrors the CursorManager.* values exactly. kCustom
// (CSS cursor:url(...)) falls back to the default pointer until the bitmap path
// lands. Platform-independent (pure enum→int); the per-view read below is aura.
int JuxCursorTypeToJfx(ui::mojom::CursorType t) {
  using CT = ui::mojom::CursorType;
  switch (t) {
    case CT::kPointer:                  return 0;   // POINTER
    case CT::kCross:                    return 1;   // CROSS
    case CT::kHand:                     return 2;   // HAND
    case CT::kMove:                     return 3;   // MOVE
    case CT::kIBeam:                    return 4;   // TEXT
    case CT::kWait:                     return 5;   // WAIT
    case CT::kHelp:                     return 6;   // HELP
    case CT::kEastResize:              return 7;   // EAST_RESIZE
    case CT::kNorthResize:            return 8;   // NORTH_RESIZE
    case CT::kNorthEastResize:        return 9;   // NORTH_EAST_RESIZE
    case CT::kNorthWestResize:        return 10;  // NORTH_WEST_RESIZE
    case CT::kSouthResize:            return 11;  // SOUTH_RESIZE
    case CT::kSouthEastResize:        return 12;  // SOUTH_EAST_RESIZE
    case CT::kSouthWestResize:        return 13;  // SOUTH_WEST_RESIZE
    case CT::kWestResize:              return 14;  // WEST_RESIZE
    case CT::kNorthSouthResize:      return 15;  // NORTH_SOUTH_RESIZE
    case CT::kEastWestResize:         return 16;  // EAST_WEST_RESIZE
    case CT::kNorthEastSouthWestResize: return 17;  // NORTH_EAST_SOUTH_WEST_RESIZE
    case CT::kNorthWestSouthEastResize: return 18;  // NORTH_WEST_SOUTH_EAST_RESIZE
    case CT::kColumnResize:           return 19;  // COLUMN_RESIZE
    case CT::kRowResize:              return 20;  // ROW_RESIZE
    case CT::kMiddlePanning:          return 21;  // MIDDLE_PANNING
    case CT::kEastPanning:            return 22;  // EAST_PANNING
    case CT::kNorthPanning:           return 23;  // NORTH_PANNING
    case CT::kNorthEastPanning:       return 24;  // NORTH_EAST_PANNING
    case CT::kNorthWestPanning:       return 25;  // NORTH_WEST_PANNING
    case CT::kSouthPanning:           return 26;  // SOUTH_PANNING
    case CT::kSouthEastPanning:       return 27;  // SOUTH_EAST_PANNING
    case CT::kSouthWestPanning:       return 28;  // SOUTH_WEST_PANNING
    case CT::kWestPanning:            return 29;  // WEST_PANNING
    case CT::kVerticalText:           return 30;  // VERTICAL_TEXT
    case CT::kCell:                    return 31;  // CELL
    case CT::kContextMenu:            return 32;  // CONTEXT_MENU
    case CT::kNoDrop:                 return 33;  // NO_DROP
    case CT::kNotAllowed:             return 34;  // NOT_ALLOWED
    case CT::kProgress:               return 35;  // PROGRESS
    case CT::kAlias:                  return 36;  // ALIAS
    case CT::kZoomIn:                 return 37;  // ZOOM_IN
    case CT::kZoomOut:                return 38;  // ZOOM_OUT
    case CT::kCopy:                   return 39;  // COPY
    case CT::kNone:                   return 40;  // NONE
    case CT::kGrab:                   return 41;  // GRAB
    case CT::kGrabbing:               return 42;  // GRABBING
    default:                          return 0;   // POINTER (kNull/kCustom/newer)
  }
}

// logical_w/logical_h are the view's DIP size at capture-request time — i.e. the
// logical area this frame's pixels represent (bound in by JuxCaptureTick). Java
// draws the frame stretched to THIS size (not the node's current size), so a
// frame captured before an in-flight resize shows at its own size instead of
// being distorted by a stretch to the new node bounds. In steady state the two
// are equal; the downscale-to-slot case still fills the node because logical_w/h
// is the full DIP size even when the pixels were shrunk to fit the slot.
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
// Publishes a print-preview frame into the popup OVERLAY region (not the main
// slots) so the page keeps streaming LIVE frames on the main channel beneath it
// (it reflows on resize, no stretch). The preview (≤1000x660) fits the popup slot
// (1280x1600). x=y=0: Java recenters it over the page from the node + preview
// logical sizes. Mirrors JuxOnPopupCaptured's region write, minus the real-popup
// rect/hit-test logic. NOTE: while a preview is open this region carries the
// preview; the preview's own dropdowns (a future fix) will need a dedicated region.
void PublishPreviewOverlay(float logical_w, float logical_h,
                           const content::CopyFromSurfaceResult& result) {
  // NOTE: a former ProbePreviewPdfRaster() diagnostic used to run here to test
  // browser-process PDFium rasterization. It is REMOVED: once the preview PDF is
  // actually generated, chrome_pdf::InitializeSDK creates a V8 isolate on the
  // browser UI thread, which hard-CHECKs (v8::Isolate::Initialize) and aborts the
  // whole engine. PDFium's V8 must live in the renderer/utility process, never
  // here. See [[project_print_preview_osr]] / tools/parse_minidump.py.
  const SkBitmap& bmp = result.value().bitmap;
  const int w = bmp.width();
  const int h = bmp.height();
  if (w <= 0 || h <= 0 || !bmp.getPixels()) return;
  jux::ipc::SharedMemoryChannel* channel = jux::g_callback_channel;
  jux::EventWriter* writer = jux::g_callback_evt_writer;
  if (!channel || !writer) return;
  base::span<uint8_t> data = channel->DataBufferMut();
  if (data.size() < kPopupRegionBytes + kPreviewRegionBytes) return;

  const size_t stride = static_cast<size_t>(w) * 4u;
  const size_t frame_bytes = stride * static_cast<size_t>(h);
  if (frame_bytes > kPreviewSlotBytes) {
    static bool warned = false;
    if (!warned) {
      warned = true;
      LOG(WARNING) << "[preview] frame " << w << "x" << h << " exceeds preview slot ("
                   << kPreviewSlotBytes << " B) — dropped";
    }
    return;
  }
  // Preview region sits between the main slots and the popup region (carved off
  // the end): off = size - popup - preview.
  const size_t preview_region_off =
      data.size() - kPopupRegionBytes - kPreviewRegionBytes;
  static uint32_t next_preview_slot = 0;
  const uint32_t slot = next_preview_slot;
  next_preview_slot = (next_preview_slot + 1u) % kPreviewFrameBufferCount;
  uint8_t* dst =
      data.data() + preview_region_off + static_cast<size_t>(slot) * kPreviewSlotBytes;
  const uint8_t* src = static_cast<const uint8_t*>(bmp.getPixels());
  const size_t src_stride = bmp.rowBytes();
  if (src_stride == stride) {
    memcpy(dst, src, frame_bytes);
  } else {
    for (int row = 0; row < h; ++row) {
      memcpy(dst + static_cast<size_t>(row) * stride,
             src + static_cast<size_t>(row) * src_stride, stride);
    }
  }
  // Draw a thin elevation border around the modal so its edge is visible against a
  // white page behind (the print-preview panel is white, so a borderless edge
  // vanishes). Two greying rings approximate a soft card shadow without needing an
  // outside-the-bitmap margin. Pixels are BGRA8888.
  {
    auto put = [&](int px, int py, uint8_t b, uint8_t g, uint8_t r) {
      if (px < 0 || px >= w || py < 0 || py >= h) return;
      uint8_t* p = dst + static_cast<size_t>(py) * stride +
                   static_cast<size_t>(px) * 4u;
      p[0] = b; p[1] = g; p[2] = r; p[3] = 0xFF;
    };
    // ring 0 (outermost) lighter, ring 1 darker — a 2px grey edge.
    const uint8_t cb[2] = {0xcf, 0x9a};
    const uint8_t cg[2] = {0xcf, 0x9e};
    const uint8_t cr[2] = {0xcf, 0xa6};
    for (int t = 0; t < 2; ++t) {
      for (int xx = t; xx < w - t; ++xx) {
        put(xx, t, cb[t], cg[t], cr[t]);
        put(xx, h - 1 - t, cb[t], cg[t], cr[t]);
      }
      for (int yy = t; yy < h - t; ++yy) {
        put(t, yy, cb[t], cg[t], cr[t]);
        put(w - 1 - t, yy, cb[t], cg[t], cr[t]);
      }
    }
  }
  // Payload: [bufIndex:4][w:4][h:4][stride:4][x:f32][y:f32][dipW:f32][dipH:f32].
  uint8_t payload[32];
  auto put = [&](int off, uint32_t v) { memcpy(payload + off, &v, 4); };
  auto putf = [&](int off, float fv) {
    uint32_t v;
    memcpy(&v, &fv, 4);
    memcpy(payload + off, &v, 4);
  };
  put(0, slot);
  put(4, static_cast<uint32_t>(w));
  put(8, static_cast<uint32_t>(h));
  put(12, static_cast<uint32_t>(stride));
  putf(16, 0.0f);
  putf(20, 0.0f);
  putf(24, logical_w);
  putf(28, logical_h);
  writer->WriteEvent(jux::events::kPreviewFrame, channel->window_id(),
                     base::span<const uint8_t>(payload, sizeof(payload)));
}
#endif  // BUILDFLAG(ENABLE_PRINT_PREVIEW)

// Stall diagnostic (OPENJFX_SKIA_WEBDPI_DIAG=1): consecutive failed copies
// are the signature of the resize/fullscreen frame gap. UI-thread only.
static int g_copy_fail_streak = 0;

// Copies a BGRA main frame into the next SHM slot and publishes kFrameReady.
// Shared by BOTH capture paths: the legacy CopyFromSurface tick
// (JuxOnFrameCaptured) and the viz FrameSinkVideoCapturer consumer
// (JuxVideoConsumer::OnFrameCaptured). UI thread only.
//
// `src` points at the frame's top-left pixel, rows `src_stride` bytes apart;
// `logical_w/h` is the DIP size this frame represents (Java stretches the
// device pixels to it).
static void PublishMainFrame(JuxWebContentsHandle handle,
                             const uint8_t* src, size_t src_stride,
                             int w, int h,
                             float logical_w, float logical_h) {
  if (!src || w <= 0 || h <= 0) {
    return;
  }

  // HiDPI capture diagnostic (OPENJFX_SKIA_WEBDPI_DIAG=1). The captured device
  // size vs the logical (DIP) size reveals the EFFECTIVE device-scale the page
  // actually rasterized at: effectiveDSF = capturedW / logicalW. If that does not
  // match the JavaFX render scale we sent (SET_SIZE scale), the page rendered at
  // the wrong density for this monitor → the "renders only a piece" bug. Compare
  // this line across monitors of different scale to pinpoint the divergence.
  if (::getenv("OPENJFX_SKIA_WEBDPI_DIAG")) {
    const float eff_dsf = logical_w > 0.f ? (w / logical_w) : 0.f;
    VLOG(1) << "[webdpi] captured device=" << w << "x" << h
              << " logical(DIP)=" << logical_w << "x" << logical_h
              << " => effectiveDSF=" << eff_dsf
              << "  (should equal the JavaFX render scale for this monitor)";
  }

  jux::ipc::SharedMemoryChannel* channel = jux::g_callback_channel;
  jux::EventWriter* writer = jux::g_callback_evt_writer;
  if (!channel || !writer) {
    return;
  }
  base::span<uint8_t> data = channel->DataBufferMut();

  const size_t stride = static_cast<size_t>(w) * 4u;
  const size_t frame_bytes = stride * static_cast<size_t>(h);
  // Main slots occupy the data region MINUS the preview + popup regions carved
  // off the end (physical order [main][preview][popup]).
  const size_t overlay_bytes = kPreviewRegionBytes + kPopupRegionBytes;
  const size_t main_region =
      data.size() > overlay_bytes ? data.size() - overlay_bytes : 0;
  const size_t slot_bytes = main_region / kFrameBufferCount;
  if (slot_bytes == 0 || frame_bytes > slot_bytes) {
    static bool warned = false;
    if (!warned) {
      warned = true;
      LOG(WARNING) << "[capture] frame " << w << "x" << h << " (" << frame_bytes
                   << " B) exceeds data slot (" << slot_bytes
                   << " B) — frame dropped; raise the frame-buffer cap";
    }
    // Recovery: an oversized frame means the rasterized density diverged from
    // capture_scale (classically a stale scale override after a DPI-boundary
    // monitor move). Force the next tick to recompute + re-apply the override
    // and keep the burst alive — a one-frame drop self-heals instead of every
    // subsequent frame being dropped (frozen/blank WebView).
    auto rec = g_web_contents_map->find(handle);
    if (rec != g_web_contents_map->end()) {
      rec->second.applied_capture_override = 0.0f;
      rec->second.fast_capture_frames = kResizeFastFrames;
    }
    return;
  }

  // Alternate slots: Java may still be reading the previously published slot
  // while we fill the other one. The kFrameReady release (event ring) pairs
  // with Java's acquire so the pixels are visible before the index is seen.
  //
  // BUG-6: this counter is a function-static (process-global), which is correct
  // ONLY because there is exactly one capture channel (jux::g_callback_channel)
  // per engine process — i.e. one window per process. If multi-window-per-process
  // is ever supported, this counter AND g_callback_channel must both move to the
  // per-handle WebContentsEntry, or two windows' captures will share a slot
  // rotation + channel target and tear each other's frames.
  static uint32_t next_slot = 0;
  uint32_t slot = next_slot;
  // M13 handshake: never overwrite the slot Java is currently reading (it
  // published the index in the header via SharedMemoryChannel.publishReadingSlot).
  // Only one slot can be "reading", so a single skip suffices; with
  // kFrameBufferCount=3 there are always >=2 free slots, so this never starves.
  const int32_t reading_slot = channel->ReadFrameReadingSlot();
  if (reading_slot >= 0 && slot == static_cast<uint32_t>(reading_slot)) {
    slot = (slot + 1u) % kFrameBufferCount;
  }
  next_slot = (slot + 1u) % kFrameBufferCount;

  uint8_t* dst = data.data() + static_cast<size_t>(slot) * slot_bytes;
  if (src_stride == stride) {
    memcpy(dst, src, frame_bytes);
  } else {
    for (int row = 0; row < h; ++row) {
      memcpy(dst + static_cast<size_t>(row) * stride,
             src + static_cast<size_t>(row) * src_stride, stride);
    }
  }

  // Payload: [bufIndex:4][width:4][height:4][stride:4][logicalW:f32][logicalH:f32]
  // (windowId prepended). logicalW/H is the DIP size this frame represents; Java
  // stretches the (possibly slot-downscaled) pixels to it.
  uint8_t payload[24];
  const uint32_t wv = static_cast<uint32_t>(w);
  const uint32_t hv = static_cast<uint32_t>(h);
  const uint32_t sv = static_cast<uint32_t>(stride);
  memcpy(payload + 0, &slot, 4);
  memcpy(payload + 4, &wv, 4);
  memcpy(payload + 8, &hv, 4);
  memcpy(payload + 12, &sv, 4);
  memcpy(payload + 16, &logical_w, 4);
  memcpy(payload + 20, &logical_h, 4);
  writer->WriteEvent(jux::events::kFrameReady, channel->window_id(),
                     base::span<const uint8_t>(payload, sizeof(payload)));
}

void JuxOnFrameCaptured(JuxWebContentsHandle handle,
                        float logical_w, float logical_h,
                        const content::CopyFromSurfaceResult& result) {
  if (!result.has_value()) {
    if (::getenv("OPENJFX_SKIA_WEBDPI_DIAG")) {
      ++g_copy_fail_streak;
      if (g_copy_fail_streak == 1 || g_copy_fail_streak % 30 == 0) {
        VLOG(1) << "[webdpi] CopyFromSurface returned EMPTY (streak="
                << g_copy_fail_streak << ")";
      }
    }
    return;
  }
  if (g_copy_fail_streak > 0) {
    if (::getenv("OPENJFX_SKIA_WEBDPI_DIAG")) {
      VLOG(1) << "[webdpi] CopyFromSurface recovered after "
              << g_copy_fail_streak << " empty results";
    }
    g_copy_fail_streak = 0;
  }
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  // Keep the page (initiator) LIVE on the main channel so it reflows on resize;
  // route the preview's own frame to the popup overlay region instead (drawn
  // centered over the page by Java). The page (handle != preview) falls through.
  if (g_print_preview_handle != 0 && handle == g_print_preview_handle) {
    PublishPreviewOverlay(logical_w, logical_h, result);
    return;
  }
#endif
  // CopyFromSurface yields an N32 bitmap — BGRA8888 premultiplied on
  // Windows/macOS, which is exactly what the Skia draw helper expects.
  const SkBitmap& bmp = result.value().bitmap;
  PublishMainFrame(handle,
                   static_cast<const uint8_t*>(bmp.getPixels()),
                   bmp.rowBytes(), bmp.width(), bmp.height(),
                   logical_w, logical_h);
}

// ---------------------------------------------------------------------------
// JuxVideoConsumer — viz FrameSinkVideoCapturer consumer (see declaration).
// ---------------------------------------------------------------------------

// Largest frame box the capturer may emit, sized so any frame fits a main
// SHM slot BY CONSTRUCTION (the capturer downscales aspect-preserved into
// this box): a 16:9 box whose area equals the slot's pixel budget. For the
// default 2560x1440x4-byte slot this is exactly 2560x1440.
static gfx::Size MainSlotMaxBox() {
  size_t slot_px = 0;
  if (jux::ipc::SharedMemoryChannel* channel = jux::g_callback_channel) {
    const size_t overlay_bytes = kPreviewRegionBytes + kPopupRegionBytes;
    const size_t data_size = channel->DataBufferMut().size();
    const size_t main_region =
        data_size > overlay_bytes ? data_size - overlay_bytes : 0;
    slot_px = (main_region / kFrameBufferCount) / 4u;
  }
  if (slot_px == 0) {
    return gfx::Size(2560, 1440);
  }
  const double h = std::sqrt(static_cast<double>(slot_px) * 9.0 / 16.0);
  const int hi = std::max(256, static_cast<int>(h));
  const int wi = std::max(256, static_cast<int>(slot_px / hi));
  return gfx::Size(wi, hi);
}

JuxVideoConsumer::JuxVideoConsumer(JuxWebContentsHandle handle,
                                   content::WebContents* wc)
    : content::WebContentsObserver(wc), handle_(handle) {}

JuxVideoConsumer::~JuxVideoConsumer() {
  if (capturer_) {
    capturer_->StopAndResetConsumer();
  }
}

void JuxVideoConsumer::StartCapture() {
  capturer_ = content::GetHostFrameSinkManager()->CreateVideoCapturer();
  // BGRA bytes — identical to the SHM slot layout Java composites.
  capturer_->SetFormat(media::PIXEL_FORMAT_ARGB);
  // Damage-driven up to ~125 fps (the JavaFX side presents at the window's
  // monitor refresh, so high-refresh displays benefit and 60 Hz panels just
  // skip). Also shaves the wait for the FIRST frame at a new size after a
  // resize/fullscreen. No auto-throttling and no size-change damping: the
  // OSR consumer wants frames at the source's natural size, immediately,
  // including straight through resize transitions.
  capturer_->SetMinCapturePeriod(base::Milliseconds(8));
  capturer_->SetMinSizeChangePeriod(base::TimeDelta());
  capturer_->SetAutoThrottlingEnabled(false);
  // Aspect-preserving constraint box: frames arrive at the source's own
  // size (no scaling, no distortion) unless the source exceeds the slot
  // budget, in which case viz downscales to fit — replacing the old
  // CopyFromSurface downscale-to-fit logic.
  capturer_->SetResolutionConstraints(gfx::Size(32, 32), MainSlotMaxBox(),
                                      /*use_fixed_aspect_ratio=*/false);
  RetargetToCurrentView();
  capturer_->Start(this, viz::mojom::BufferFormatPreference::kDefault);
}

void JuxVideoConsumer::RequestRefreshFrame() {
  if (capturer_) {
    capturer_->RequestRefreshFrame();
  }
}

void JuxVideoConsumer::RetargetToCurrentView() {
  if (!capturer_ || !web_contents()) {
    return;
  }
  content::RenderWidgetHostView* view =
      web_contents()->GetRenderWidgetHostView();
  if (view) {
    capturer_->ChangeTarget(viz::VideoCaptureTarget(
        static_cast<content::RenderWidgetHostViewBase*>(view)
            ->GetFrameSinkId()));
  } else {
    capturer_->ChangeTarget(std::nullopt);
  }
}

void JuxVideoConsumer::OnFrameCaptured(
    media::mojom::VideoBufferHandlePtr data,
    media::mojom::VideoFrameInfoPtr info,
    const gfx::Rect& content_rect,
    mojo::PendingRemote<viz::mojom::FrameSinkVideoConsumerFrameCallbacks>
        callbacks) {
  // Bind first so every early-out releases the buffer back to the capturer
  // pool (an unreleased buffer would stall frame delivery).
  mojo::Remote<viz::mojom::FrameSinkVideoConsumerFrameCallbacks> done(
      std::move(callbacks));
  if (!data || !data->is_read_only_shmem_region() || !info ||
      info->pixel_format != media::PIXEL_FORMAT_ARGB) {
    if (done) done->Done();
    return;
  }
  base::ReadOnlySharedMemoryMapping mapping =
      data->get_read_only_shmem_region().Map();
  if (!mapping.IsValid()) {
    if (done) done->Done();
    return;
  }
  const int coded_w = info->coded_size.width();
  const int coded_h = info->coded_size.height();
  gfx::Rect rect = content_rect.IsEmpty() ? info->visible_rect : content_rect;
  rect.Intersect(gfx::Rect(coded_w, coded_h));
  const size_t src_stride = static_cast<size_t>(coded_w) * 4u;
  const size_t need = src_stride * static_cast<size_t>(coded_h);
  if (rect.IsEmpty() || mapping.size() < need) {
    if (done) done->Done();
    return;
  }
  const uint8_t* base_ptr = static_cast<const uint8_t*>(mapping.memory());
  const uint8_t* src = base_ptr +
      static_cast<size_t>(rect.y()) * src_stride +
      static_cast<size_t>(rect.x()) * 4u;

  // Logical (DIP) size this frame represents. Steady state: the frame is at
  // the commanded size × capture_scale — report the commanded logical so
  // rounding never drifts. Mid-transition (old-size frame after a resize):
  // self-describe from the frame's own device size, so Java draws it at its
  // true, undistorted logical size until the new size lands.
  float scale = 1.0f;
  int cmd_w = 0, cmd_h = 0;
  auto it = g_web_contents_map->find(handle_);
  if (it != g_web_contents_map->end()) {
    scale = it->second.capture_scale > 0.0f ? it->second.capture_scale : 1.0f;
    cmd_w = it->second.last_logical_w;
    cmd_h = it->second.last_logical_h;
  }
  float logical_w = rect.width() / scale;
  float logical_h = rect.height() / scale;
  if (cmd_w > 0 && cmd_h > 0 &&
      std::fabs(logical_w - cmd_w) <= 2.0f &&
      std::fabs(logical_h - cmd_h) <= 2.0f) {
    logical_w = static_cast<float>(cmd_w);
    logical_h = static_cast<float>(cmd_h);
  }

  PublishMainFrame(handle_, src, src_stride, rect.width(), rect.height(),
                   logical_w, logical_h);
  if (done) done->Done();
}

void JuxVideoConsumer::OnNewCaptureVersion(
    const media::CaptureVersion& capture_version) {}

void JuxVideoConsumer::OnFrameWithEmptyRegionCapture() {}

void JuxVideoConsumer::OnStopped() {
  // End-of-stream from viz (capturer torn down / target gone). The owner
  // (WebContentsEntry) controls our lifetime; nothing to do here.
}

void JuxVideoConsumer::OnLog(const std::string& message) {
  VLOG(1) << "[jux-capture] " << message;
}

void JuxVideoConsumer::PrimaryPageChanged(content::Page& page) {
  // Cross-document navigation can swap the RenderWidgetHostView (and its
  // frame sink). Follow it, and ask for a frame so the new document shows
  // promptly even if its first damage already happened.
  RetargetToCurrentView();
  RequestRefreshFrame();
}

void JuxVideoConsumer::RenderViewHostChanged(
    content::RenderViewHost* old_host,
    content::RenderViewHost* new_host) {
  // Renderer swap (crash recovery, process change) — re-target.
  RetargetToCurrentView();
  RequestRefreshFrame();
}

// OSR popup capture callback (UI thread). Copies the popup's BGRA pixels into a
// double-buffered popup slot in the shared-memory data region (carved off the
// end, after the main slots) and publishes the slot + rect via kPopupFrame. Same
// cheap memcpy path as the main frame — no file I/O (an earlier temp-file
// transport blocked the UI thread and froze the engine). The popup view is
// re-queried live (never a cached raw_ptr).
void JuxOnPopupCaptured(JuxWebContentsHandle handle,
                        const content::CopyFromSurfaceResult& result) {
  if (!result.has_value()) {
    return;
  }
  const SkBitmap& bmp = result.value().bitmap;
  const int w = bmp.width();
  const int h = bmp.height();
  if (w <= 0 || h <= 0 || !bmp.getPixels()) {
    return;
  }
  auto it = g_web_contents_map->find(handle);
  if (it == g_web_contents_map->end() || !it->second.web_contents) {
    return;
  }
  std::vector<content::RenderWidgetHostView*> popups =
      it->second.web_contents->GetPopupWidgets();
  if (popups.empty()) {
    return;
  }
  jux::ipc::SharedMemoryChannel* channel = jux::g_callback_channel;
  jux::EventWriter* writer = jux::g_callback_evt_writer;
  if (!channel || !writer) {
    return;
  }
  base::span<uint8_t> data = channel->DataBufferMut();
  if (data.size() < kPopupRegionBytes) {
    return;
  }

  const size_t stride = static_cast<size_t>(w) * 4u;
  const size_t frame_bytes = stride * static_cast<size_t>(h);
  if (frame_bytes > kPopupSlotBytes) {
    static bool warned = false;
    if (!warned) {
      warned = true;
      LOG(WARNING) << "[popup] frame " << w << "x" << h << " exceeds popup slot ("
                   << kPopupSlotBytes << " B) — dropped";
    }
    return;
  }

  // Popup region sits at the end of the data region; alternate its slots.
  // BUG-6: like the main-frame counter above, this static is process-global and
  // correct only under one-window-per-process (single g_callback_channel). Move
  // both to the per-handle WebContentsEntry if multi-window-per-process lands.
  const size_t popup_region_off = data.size() - kPopupRegionBytes;
  static uint32_t next_popup_slot = 0;
  const uint32_t slot = next_popup_slot;
  next_popup_slot = (next_popup_slot + 1u) % kPopupFrameBufferCount;

  uint8_t* dst =
      data.data() + popup_region_off + static_cast<size_t>(slot) * kPopupSlotBytes;
  const uint8_t* src = static_cast<const uint8_t*>(bmp.getPixels());
  const size_t src_stride = bmp.rowBytes();
  if (src_stride == stride) {
    memcpy(dst, src, frame_bytes);
  } else {
    for (int row = 0; row < h; ++row) {
      memcpy(dst + static_cast<size_t>(row) * stride,
             src + static_cast<size_t>(row) * src_stride, stride);
    }
  }

  // Rect in main-view-local DIP (= node-logical), so Java draws/hit-tests with
  // no scale math: it stretches the device bitmap (w×h) into (x,y,dipW,dipH).
  content::RenderWidgetHostView* main_view =
      it->second.web_contents->GetRenderWidgetHostView();
  gfx::Rect popup_b = popups.front()->GetViewBounds();
  gfx::Rect main_b = main_view ? main_view->GetViewBounds() : gfx::Rect();
  gfx::Point origin = main_b.origin();
  const float dip_w = static_cast<float>(popup_b.width());
  const float dip_h = static_cast<float>(popup_b.height());
  float px = static_cast<float>(popup_b.x() - origin.x());
  float py = static_cast<float>(popup_b.y() - origin.y());

  // Keep the popup inside the WebView node's area. The popup is a real aura
  // window that Chromium positions against the REAL desktop work-area, so an
  // <input type=color> / <select> / datalist near the WebView's bottom or
  // right edge opens PAST the node — and we'd composite it spilling outside
  // the WebView. Clamp its node-local origin so the bottom/right edge sits at
  // the view edge (shift up/left, exactly as a popup that ran out of room).
  // This is safe and needs no Java change: mouse routing is popup-LOCAL
  // relative to THIS reported rect, the composite uses THIS rect, and the
  // cloaked (never-shown) aura window's real screen position is irrelevant.
  // Only clamp on an axis the popup actually fits; an oversized popup is
  // pinned to the top-left corner instead of being pushed off the far edge.
  const float mw = static_cast<float>(main_b.width());
  const float mh = static_cast<float>(main_b.height());
  if (mw > 0.0f && dip_w <= mw && px > mw - dip_w) px = mw - dip_w;
  if (px < 0.0f) px = 0.0f;
  if (mh > 0.0f && dip_h <= mh && py > mh - dip_h) py = mh - dip_h;
  if (py < 0.0f) py = 0.0f;

  // Payload: [bufIndex:4][w:4][h:4][stride:4][x:f32][y:f32][dipW:f32][dipH:f32]
  uint8_t payload[32];
  auto put = [&](int off, uint32_t v) { memcpy(payload + off, &v, 4); };
  auto putf = [&](int off, float f) {
    uint32_t v;
    memcpy(&v, &f, 4);
    memcpy(payload + off, &v, 4);
  };
  put(0, slot);
  put(4, static_cast<uint32_t>(w));
  put(8, static_cast<uint32_t>(h));
  put(12, static_cast<uint32_t>(stride));
  putf(16, px);
  putf(20, py);
  putf(24, dip_w);
  putf(28, dip_h);
  writer->WriteEvent(jux::events::kPopupFrame, channel->window_id(),
                     base::span<const uint8_t>(payload, sizeof(payload)));
}

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
// Writes a Skia-rendered dropdown bitmap to the popup region and emits kPopupFrame
// at the (preview-local DIP) rect. Mirrors JuxOnPopupCaptured's region write; the
// bitmap is our own (no Blink popup), so there's no real-popup rect/clamp logic.
void PublishDropdownFrame(const SkBitmap& bmp, float px, float py,
                          float dip_w, float dip_h) {
  const int w = bmp.width();
  const int h = bmp.height();
  if (w <= 0 || h <= 0 || !bmp.getPixels()) return;
  jux::ipc::SharedMemoryChannel* channel = jux::g_callback_channel;
  jux::EventWriter* writer = jux::g_callback_evt_writer;
  if (!channel || !writer) return;
  base::span<uint8_t> data = channel->DataBufferMut();
  if (data.size() < kPopupRegionBytes) return;
  const size_t stride = static_cast<size_t>(w) * 4u;
  const size_t frame_bytes = stride * static_cast<size_t>(h);
  if (frame_bytes > kPopupSlotBytes) {
    static bool warned = false;
    if (!warned) {
      warned = true;
      LOG(WARNING) << "[preview-dropdown] " << w << "x" << h
                   << " exceeds popup slot — dropped";
    }
    return;
  }
  const size_t popup_region_off = data.size() - kPopupRegionBytes;
  static uint32_t next_slot = 0;
  const uint32_t slot = next_slot;
  next_slot = (next_slot + 1u) % kPopupFrameBufferCount;
  uint8_t* dst =
      data.data() + popup_region_off + static_cast<size_t>(slot) * kPopupSlotBytes;
  const uint8_t* src = static_cast<const uint8_t*>(bmp.getPixels());
  const size_t src_stride = bmp.rowBytes();
  if (src_stride == stride) {
    memcpy(dst, src, frame_bytes);
  } else {
    for (int row = 0; row < h; ++row) {
      memcpy(dst + static_cast<size_t>(row) * stride,
             src + static_cast<size_t>(row) * src_stride, stride);
    }
  }
  uint8_t payload[32];
  auto put = [&](int o, uint32_t v) { memcpy(payload + o, &v, 4); };
  auto putf = [&](int o, float f) {
    uint32_t v;
    memcpy(&v, &f, 4);
    memcpy(payload + o, &v, 4);
  };
  put(0, slot);
  put(4, static_cast<uint32_t>(w));
  put(8, static_cast<uint32_t>(h));
  put(12, static_cast<uint32_t>(stride));
  putf(16, px);
  putf(20, py);
  putf(24, dip_w);
  putf(28, dip_h);
  writer->WriteEvent(jux::events::kPopupFrame, channel->window_id(),
                     base::span<const uint8_t>(payload, sizeof(payload)));
}

// Re-renders the open preview dropdown and republishes it (after hover/scroll).
void RenderAndPublishPreviewDropdown() {
  if (!g_preview_dropdown) return;
  const SkBitmap& bmp = g_preview_dropdown->Render();
  PublishDropdownFrame(bmp, g_preview_dropdown->x(), g_preview_dropdown->y(),
                       g_preview_dropdown->width(), g_preview_dropdown->height());
}

// Closes the open preview dropdown. accept=true commits `option_index` to the
// <select>; otherwise the selection is unchanged. Clears the Java overlay. UI
// thread only.
void DismissPreviewDropdown(bool accept, int option_index) {
  if (!g_preview_dropdown) return;
  const uint32_t popup_id = g_preview_dropdown_popup_id;
  delete g_preview_dropdown;
  g_preview_dropdown = nullptr;
  g_preview_dropdown_popup_id = 0;
  if (accept && option_index >= 0) {
    const int32_t idx = option_index;
    JuxSelectPopupResponse(g_print_preview_handle, popup_id, &idx, 1);
  } else {
    JuxSelectPopupResponse(g_print_preview_handle, popup_id, nullptr, 0);
  }
  if (jux::g_callback_channel && jux::g_callback_evt_writer) {
    jux::g_callback_evt_writer->WriteEvent(jux::events::kPopupClosed,
                                           jux::g_callback_channel->window_id());
  }
}
#endif  // BUILDFLAG(ENABLE_PRINT_PREVIEW)

void JuxCaptureTick(JuxWebContentsHandle handle) {
  auto it = g_web_contents_map->find(handle);
  if (it == g_web_contents_map->end()) {
    return;  // WebContents destroyed — stop the loop.
  }
  content::WebContents* wc = it->second.web_contents.get();
  content::RenderWidgetHostView* view =
      wc ? wc->GetRenderWidgetHostView() : nullptr;
  // Stall diagnostic (OPENJFX_SKIA_WEBDPI_DIAG=1): distinguish "surface not
  // available" (no capture even attempted) from failing copies — the two
  // possible faces of the resize/fullscreen frame gap. UI-thread only.
  static int g_surface_unavail_streak = 0;
  if (::getenv("OPENJFX_SKIA_WEBDPI_DIAG")) {
    const bool avail = view && view->IsSurfaceAvailableForCopy();
    if (!avail) {
      ++g_surface_unavail_streak;
      if (g_surface_unavail_streak == 1 || g_surface_unavail_streak % 60 == 0) {
        VLOG(1) << "[webdpi] surface UNAVAILABLE for copy (streak="
                << g_surface_unavail_streak << ")";
      }
    } else if (g_surface_unavail_streak > 0) {
      VLOG(1) << "[webdpi] surface available again after "
              << g_surface_unavail_streak << " unavailable ticks";
      g_surface_unavail_streak = 0;
    }
  }
  // With the viz capturer active the main frame is pushed, not polled —
  // skip the CopyFromSurface block entirely (popups/cursor/preview below
  // still run on this tick). The scale-override healing stays active for
  // both paths: the capturer captures whatever density the renderer
  // rasterizes, so the override must keep tracking the JavaFX scale.
  const bool poll_main_frame = !it->second.video_consumer;
  if (view && view->IsSurfaceAvailableForCopy()) {
    // Heal the capture density every tick: the hidden window's monitor DSF
    // (the override's divisor) updates asynchronously when a DPI-boundary
    // drag moves it, so the override computed at kSetSize time can be stale.
    // No-op (one float compare) when nothing changed.
    ApplyCaptureScaleOverride(it->second, view);
    // Heal the viewport size too: a display-DSF change over fixed pixel
    // bounds silently re-derives the widget's DIP size (DIP = px / DSF),
    // shrinking/growing the page under the last commanded logical size.
    // Re-assert the commanded size when they diverge. No-op normally.
    if (it->second.last_logical_w > 0 && it->second.last_logical_h > 0) {
      gfx::Size dip = view->GetViewBounds().size();
      if (dip.width() != it->second.last_logical_w ||
          dip.height() != it->second.last_logical_h) {
        if (::getenv("OPENJFX_SKIA_WEBDPI_DIAG")) {
          VLOG(1) << "[webdpi] view DIP drifted to " << dip.width() << "x"
                  << dip.height() << "; re-asserting "
                  << it->second.last_logical_w << "x"
                  << it->second.last_logical_h;
        }
        if (it->second.widget) {
          it->second.widget->SetSize(gfx::Size(it->second.last_logical_w,
                                               it->second.last_logical_h));
        }
        it->second.web_contents->Resize(gfx::Rect(
            0, 0, it->second.last_logical_w, it->second.last_logical_h));
        it->second.fast_capture_frames = kResizeFastFrames;
      }
    }
    // Capture at NATIVE resolution (empty out_size) in the common case. Passing
    // an explicit out_size forces CopyFromSurface down the GPU rescale path
    // (e.g. the D3D11 VideoProcessor), which FAILS on some GPUs — notably AMD,
    // where "VideoProcessorGetOutputExtension failed" makes every capture return
    // no value, so the WebView renders blank. Only request an explicit (smaller)
    // size when the page genuinely exceeds a data-region slot and MUST downscale.
    //
    // The downscale guard measures in DEVICE px (= view DIP × capture_scale):
    // a DIP-only guard lets HiDPI frames (scale²× larger) slip past and get
    // dropped in JuxOnFrameCaptured. Java stretches whatever it gets back to the
    // frame's logical (DIP) size, so a downscale only lowers resolution — the
    // whole page (incl. the bottom/right edges) stays visible.
    gfx::Size out_size;  // empty == native resolution (no GPU rescale)
    jux::ipc::SharedMemoryChannel* channel = jux::g_callback_channel;
    gfx::Size view_dip = view->GetViewBounds().size();
    float cap_scale = it->second.capture_scale;
    if (cap_scale <= 0.0f) cap_scale = 1.0f;
    // Predict with the larger of the intended scale and the view's CURRENT
    // effective DSF (screen-info value, override included): mid-transition
    // the renderer can transiently rasterize denser than intended, and a
    // prediction from capture_scale alone would under-size the guard and let
    // the oversized frame through to the slot-overflow drop. Conservative
    // only — out_size stays empty unless the frame truly wouldn't fit.
    {
      const float eff_dsf =
          static_cast<content::RenderWidgetHostViewBase*>(view)
              ->GetDeviceScaleFactor();
      if (eff_dsf > cap_scale) cap_scale = eff_dsf;
    }
    if (channel && !view_dip.IsEmpty()) {
      const double dev_w = std::ceil(view_dip.width() * cap_scale);
      const double dev_h = std::ceil(view_dip.height() * cap_scale);
      const size_t overlay_bytes = kPreviewRegionBytes + kPopupRegionBytes;
      const size_t main_region = channel->data_size() > overlay_bytes
                                     ? channel->data_size() - overlay_bytes : 0;
      // The preview captures into the preview region (its own slot size); every
      // other handle uses a main slot. Pick the right cap so the downscale guard
      // shrinks an oversized frame to fit its destination instead of dropping it.
      size_t slot_px = (main_region / kFrameBufferCount) / 4u;
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
      if (handle == g_print_preview_handle) {
        slot_px = kPreviewSlotBytes / 4u;
      }
#endif
      const size_t want_px = static_cast<size_t>(dev_w * dev_h);
      if (slot_px > 0 && want_px > slot_px) {
        const double f = std::sqrt(static_cast<double>(slot_px) /
                                   static_cast<double>(want_px));
        out_size = gfx::Size(std::max(1, static_cast<int>(dev_w * f)),
                             std::max(1, static_cast<int>(dev_h * f)));
      }
      // else: leave out_size empty → native capture, no rescale.
    }
    if (poll_main_frame) {
      view->CopyFromSurface(gfx::Rect(), out_size, base::Seconds(1),
                            base::BindOnce(&JuxOnFrameCaptured, handle,
                                           static_cast<float>(view_dip.width()),
                                           static_cast<float>(view_dip.height())));
    }
  }

  // --- OSR popup capture (Blink page-popups: <select>/color/datalist) ---
  // Blink renders these into a separate popup widget (a real aura window). We
  // DWM-cloak it + release its capture so it never shows / hijacks OS input,
  // capture its pixels like the main view, and ship them to Java to composite.
  {
    std::vector<content::RenderWidgetHostView*> popups =
        wc ? wc->GetPopupWidgets()
           : std::vector<content::RenderWidgetHostView*>();
    content::RenderWidgetHostView* popup =
        popups.empty() ? nullptr : popups.front();
    const bool has = (popup != nullptr);
    if (has != it->second.popup_active) {
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
      // Diagnostic for the print-preview dropdown work: report when the (off-
      // screen) preview opens/closes a native <select> popup, with its size.
      if (handle == g_print_preview_handle) {
        gfx::Size ps = popup ? popup->GetViewBounds().size() : gfx::Size();
        VLOG(1) << "[preview-popup] handle=" << handle << " has=" << has
                  << " size=" << ps.width() << "x" << ps.height();
      }
#endif
      it->second.popup_active = has;
      it->second.popup_cloaked = false;
      if (!has) {
        jux::ipc::SharedMemoryChannel* channel = jux::g_callback_channel;
        jux::EventWriter* writer = jux::g_callback_evt_writer;
        if (channel && writer) {
          writer->WriteEvent(jux::events::kPopupClosed, channel->window_id());
        }
      }
    }
    if (popup) {
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
      // Only force the PREVIEW's popup to show — a normal webview's popups already
      // work, so never touch them (avoid the regression that on-desktop+Show
      // caused). For the off-screen preview, Show() marks the popup visible to the
      // compositor (DWM cloak still hides the HWND).
      if (handle == g_print_preview_handle && popup && !popup->IsShowing()) {
        popup->Show();
      }
#endif
      aura::Window* pw = popup->GetNativeView();
      // Drop any capture EVERY tick: the popup grabs the mouse on show and may
      // re-grab; since we drive it with synthetic input it must never hold the
      // real OS mouse (that froze the UI). Cheap no-op when it has none.
      if (pw && pw->HasCapture()) {
        pw->ReleaseCapture();
      }
      // DWM-cloak once (persistent) so the popup window never paints to screen.
      if (!it->second.popup_cloaked) {
#if BUILDFLAG(IS_WIN)
        if (pw) {
          if (aura::WindowTreeHost* host = pw->GetHost()) {
            if (HWND hwnd = host->GetAcceleratedWidget()) {
              BOOL cloak = TRUE;
              ::DwmSetWindowAttribute(hwnd, DWMWA_CLOAK, &cloak, sizeof(cloak));
            }
          }
        }
#endif
        it->second.popup_cloaked = true;
      }
      // Reposition the popup window so it can't spill outside the WebView node,
      // and do it BEFORE we capture it so the very first composited frame is
      // already in place (no visible "snap to position"). The popup is a real
      // aura window Chromium positions against the real desktop work-area; we
      // shift it by the clamp delta so its bottom/right edge sits at the view
      // edge (same result as a popup that ran out of room). Idempotent — once
      // inside the node the delta is 0, so it converges and won't fight
      // Chromium. GetViewBounds reflects the move, so JuxOnPopupCaptured's
      // composite rect AND the popup-local mouse routing both stay consistent.
      if (pw && view) {
        const gfx::Rect main_b = view->GetViewBounds();
        const gfx::Rect pop_b = popup->GetViewBounds();
        int relx = pop_b.x() - main_b.x();
        int rely = pop_b.y() - main_b.y();
        int cx = relx;
        int cy = rely;
        if (main_b.width() > 0 && pop_b.width() <= main_b.width() &&
            cx > main_b.width() - pop_b.width()) {
          cx = main_b.width() - pop_b.width();
        }
        if (cx < 0) cx = 0;
        if (main_b.height() > 0 && pop_b.height() <= main_b.height() &&
            cy > main_b.height() - pop_b.height()) {
          cy = main_b.height() - pop_b.height();
        }
        if (cy < 0) cy = 0;
        const int dx = cx - relx;
        const int dy = cy - rely;
        if (dx != 0 || dy != 0) {
          gfx::Rect nb = pw->bounds();
          nb.set_x(nb.x() + dx);
          nb.set_y(nb.y() + dy);
          pw->SetBounds(nb);
        }
      }
      // Throttle to ~30fps (every other 16ms tick): popups are near-static and
      // each capture turns into a worker-thread file write — 60fps churned the FS.
      it->second.popup_capture_toggle = !it->second.popup_capture_toggle;
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
      // Diagnostic (first N ticks): is the preview popup's surface actually
      // available to copy? If this stays surfaceAvail=0, the off-screen popup
      // never produces a compositor frame → that's the blocker (begin-frame).
      if (handle == g_print_preview_handle) {
        static int dbg = 0;
        if (dbg < 12) {
          ++dbg;
          VLOG(1) << "[preview-popup] tick surfaceAvail="
                    << popup->IsSurfaceAvailableForCopy()
                    << " showing=" << popup->IsShowing()
                    << " bounds=" << popup->GetViewBounds().ToString();
        }
      }
#endif
      if (it->second.popup_capture_toggle && popup->IsSurfaceAvailableForCopy()) {
        popup->CopyFromSurface(gfx::Rect(), gfx::Size(), base::Seconds(1),
                               base::BindOnce(&JuxOnPopupCaptured, handle));
      }
    }
  }

  // Forward the hovered element's cursor to Java. Off-screen there is no OS window
  // to apply it to, so we read the renderer's current cursor and let Java set it on
  // the WebView node. RWHVAura exposes it UNGATED via its aura::WindowDelegate::
  // GetCursor() override — the normal UpdateCursorIfOverSelf() path bails because
  // the real OS cursor sits over the JavaFX window, not this hidden one. Emit only
  // on change. aura covers Windows + Linux; macOS (RenderWidgetHostViewMac) has a
  // different cursor path — TODO, falls through to no update there.
#if !BUILDFLAG(IS_MAC)
  if (view) {
    aura::Window* win = view->GetNativeView();
    if (win && win->delegate()) {
      int jfx = JuxCursorTypeToJfx(win->delegate()->GetCursor(gfx::Point()).type());
      if (jfx != it->second.last_cursor_type) {
        it->second.last_cursor_type = jfx;
        jux::ipc::SharedMemoryChannel* channel = jux::g_callback_channel;
        jux::EventWriter* writer = jux::g_callback_evt_writer;
        if (channel && writer) {
          uint8_t payload[4];
          const uint32_t cv = static_cast<uint32_t>(jfx);
          memcpy(payload, &cv, 4);
          writer->WriteEvent(jux::events::kCursorChanged, channel->window_id(),
                             base::span<const uint8_t>(payload, sizeof(payload)));
        }
      }
    }
  }
#endif

  // Reschedule: fast cadence during a post-resize burst, else the steady rate.
  // `it` is still valid here — nothing on this synchronous UI-thread tick mutates
  // g_web_contents_map (destroy runs as a separate command, never re-entrantly).
  int next_ms = kCaptureIntervalMs;
  if (it->second.fast_capture_frames > 0) {
    --it->second.fast_capture_frames;
    next_ms = kResizeFastIntervalMs;
  }
  content::GetUIThreadTaskRunner({})->PostDelayedTask(
      FROM_HERE, base::BindOnce(&JuxCaptureTick, handle),
      base::Milliseconds(next_ms));
}

// Next handle counter.
std::atomic<JuxWebContentsHandle> g_next_handle{1};

// Shared memory channel pointer set by JuxRunBrowser before calling
// ContentMain. Read by JuxBrowserMainParts::PreMainMessageLoopRun to
// set up the IPC layer (heartbeat, command dispatch, ring buffers).
// Access: set on main thread before ContentMain, read on main thread
// during PreMainMessageLoopRun (same thread — ContentMain runs here).
jux::ipc::SharedMemoryChannel* g_run_browser_channel = nullptr;

// GPU rendering mode set by JuxSetGpuMode. Applied in JuxInit.
// 0=default (ANGLE auto-detect + SwiftShader fallback)
// 1=force software (SwiftShader via ANGLE)
// 2=disable GPU entirely (CPU compositing)
int g_gpu_mode = 0;

// Posts a task to the browser UI thread. Returns false if the browser
// thread is not running.
bool PostToBrowserThread(base::OnceClosure task) {
  if (!content::BrowserThread::IsThreadInitialized(
          content::BrowserThread::UI)) {
    return false;
  }
  content::GetUIThreadTaskRunner({})->PostTask(FROM_HERE, std::move(task));
  return true;
}

// Gets the BrowserMainParts — only valid on the UI thread after init.
jux::JuxBrowserMainParts* GetMainParts() {
  auto* client = jux::JuxBrowserClient::Get();
  if (!client) {
    return nullptr;
  }
  return client->browser_main_parts();
}

// Creates a WebContents + Widget on the UI thread. Must only be called
// from the UI thread (either directly from the command dispatch timer,
// or from a PostTask).
void CreateWebContentsOnUI(JuxWebContentsHandle handle,
                           uintptr_t parent_window) {
  auto* parts = GetMainParts();
  if (!parts || !parts->browser_context()) {
    LOG(ERROR) << "CreateWebContentsOnUI: browser context not ready";
    return;
  }

  WebContentsEntry entry;

  // 1. Create the WebContents.
  content::WebContents::CreateParams create_params(
      parts->browser_context());
  entry.web_contents = content::WebContents::Create(create_params);

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  // Attach the print managers so window.print()/Ctrl+P routes through the print
  // preview pipeline (PrintManagerHost is bound to this PrintViewManager by
  // JuxBrowserClient). PdfNupConverterClient handles pages-per-sheet conversion.
  printing::PrintViewManager::CreateForWebContents(entry.web_contents.get());
  printing::PdfNupConverterClient::CreateForWebContents(
      entry.web_contents.get());
  // Headless page→PDF (WebEngine.print()/print(location)) composites the printed
  // document via PrintCompositeClient → the PrintCompositor utility service.
  printing::PrintCompositeClient::CreateForWebContents(
      entry.web_contents.get());
#endif

  // 2. Create the delegate (handles title, load, navigation callbacks).
  entry.delegate = std::make_unique<jux::JuxWebContentsDelegate>(
      entry.web_contents.get(), handle, &g_callbacks);
  entry.web_contents->SetDelegate(entry.delegate.get());

  // 3. Create a views::Widget with a views::WebView.
  //    Widget::Init() calls CreateWindowExW which is a blocking Win32 call.
  //    Chromium's browser UI thread sets DisallowBlocking, so we use
  //    RunWithBlockingAllowed (JuxBrowserMainParts is a friend of
  //    ScopedAllowBlocking).
  //
  //    Use JuxWidgetDelegate instead of the default — it intercepts the
  //    X button close and forwards to Java via the event ring buffer.
  //
  //    IMPORTANT: SetWebContents must be called AFTER Widget::Init(),
  //    because the WebView needs a live aura window tree to properly
  //    parent the WebContents' native view (RenderWidgetHostViewAura).
  //    If set before Init, the native view has no parent and the
  //    compositor never composites the rendered content into the window.
  auto jux_delegate = std::make_unique<jux::JuxWidgetDelegate>(
      jux::g_callback_evt_writer, jux::g_callback_channel);
  auto* jux_delegate_ptr = jux_delegate.get();

  // Create the WebView without attaching WebContents yet.
  auto web_view_owned =
      std::make_unique<views::WebView>(parts->browser_context());
  auto* web_view_ptr = web_view_owned.get();
  jux_delegate->SetContentsView(std::move(web_view_owned));

  views::Widget::InitParams widget_params(
      views::Widget::InitParams::NATIVE_WIDGET_OWNS_WIDGET,
      views::Widget::InitParams::TYPE_WINDOW);
  // Frameless: a standard TYPE_WINDOW has a caption + borders, so its CLIENT
  // area (which the views::WebView fills, and which then drives the WebContents
  // size via layout — overriding our explicit web_contents->Resize) is ~39px
  // shorter than the window on Windows. For OSR that makes the page's
  // innerHeight shorter than the JavaFX node: the page footer lays out below
  // the captured area (cut off) and input Y is offset down by the caption
  // height. Removing the standard frame makes client area == window bounds, so
  // the web viewport is exactly the node size. The window is hidden anyway.
  widget_params.remove_standard_frame = true;
  widget_params.bounds = gfx::Rect(800, 600);
  widget_params.delegate = jux_delegate.release();

  // Widget::Init calls CreateWindowExW (blocking). Run inside the
  // ScopedAllowBlocking guard provided by JuxBrowserMainParts.
  auto* widget = new views::Widget();
  jux::JuxBrowserMainParts::RunWithBlockingAllowed(
      base::BindOnce([](views::Widget* w, views::Widget::InitParams p) {
        w->Init(std::move(p));
      }, widget, std::move(widget_params)));

  entry.widget = widget;
  entry.web_view = web_view_ptr;
  entry.widget_delegate = jux_delegate_ptr;

  // 4. NOW attach the WebContents to the WebView. The widget's aura
  //    window tree is live, so the WebContents' native view
  //    (RenderWidgetHostViewAura) gets properly parented and the
  //    compositor can composite rendered content into the window.
  web_view_ptr->SetWebContents(entry.web_contents.get());

  // Force a layout pass so the WebView sizes the WebContents' native
  // view to fill the widget's client area. Without this, the native
  // view may remain at 0x0 if the initial layout ran before attachment.
  widget->LayoutRootViewIfNecessary();

  // 5. Attach the WidgetObserver to forward resize/move/focus events
  //    to Java via the IPC event ring buffer.
  entry.widget_observer = std::make_unique<jux::JuxWidgetObserver>(
      jux::g_callback_evt_writer, jux::g_callback_channel);
  widget->AddObserver(entry.widget_observer.get());

  // 6. Show the widget but DWM-cloak the underlying HWND.
  //
  //    Creating the HWND with ShowWindow is what attaches a real
  //    presentation surface so Chromium's compositor can actually
  //    submit frames; a fully-hidden widget has no surface, so the
  //    first frame would only land AFTER Java's kShow — that's the
  //    "blank for a moment on slow hardware" users see.
  //
  //    DWMWA_CLOAK (Windows 8+) tells the desktop window manager to
  //    suppress presenting this HWND to the user without changing any
  //    of its rendering properties. The window is:
  //       - composited normally by Chromium (frames are real, going
  //         into the HWND's swap chain),
  //       - invisible to the user,
  //       - hidden from the taskbar.
  //    Unlike WS_EX_LAYERED, cloaking leaves the HWND as a completely
  //    standard window — WM_NCCALCSIZE / WM_NCHITTEST / DWM blur all
  //    behave normally, which keeps the door open for the future
  //    custom-chrome / hit-test work.
  //
  //    The cloak is released in JuxShowWidget (called from Java's
  //    kShow after DOC_READY_TO_SHOW), at which point DWM presents
  //    the already-composited content atomically on its next frame —
  //    no flash, no gap.
  //
  //    Cross-platform: this cloak trick is Windows-specific.
  //    - macOS: NSWindow is simply left unordered (not
  //      makeKeyAndOrderFront'd) until kShow — the CA layer composites
  //      without a visible window, so the gap is already near-zero.
  //    - Linux: the X11 window is left unmapped / the Wayland surface
  //      unattached until kShow; most compositors handle this cleanly.
  //    On non-Windows platforms we therefore keep the widget fully
  //    hidden here and rely on JuxShowWidget to call widget->Show()
  //    when Java is ready — IncrementCapturerCount below still keeps
  //    the renderer producing frames so DidFirstVisuallyNonEmptyPaint
  //    fires at the expected time.
#if BUILDFLAG(IS_WIN)
  // skia-fx OSR: the page is rendered off-screen and composited into the
  // JavaFX scene node — Chromium must never present an OS window. We
  // therefore do NOT call widget->Show() (so no window is ever mapped: no
  // taskbar button, no Alt-Tab entry, nothing on screen) and there is
  // nothing to DWM-cloak. The aura host HWND still exists so the compositor
  // has a frame sink, but it stays hidden for its entire lifetime; frame
  // production while hidden is driven by IncrementCapturerCount(stay_hidden=
  // true) below. As defence-in-depth, mark the HWND as a tool window (and
  // clear WS_EX_APPWINDOW) so even an accidental Show() could not surface a
  // taskbar button.
  if (HWND hwnd = widget->GetNativeWindow()->GetHost()->GetAcceleratedWidget();
      hwnd) {
    LONG_PTR ex = ::GetWindowLongPtrW(hwnd, GWL_EXSTYLE);
    ex = (ex | WS_EX_TOOLWINDOW) & ~static_cast<LONG_PTR>(WS_EX_APPWINDOW);
    ::SetWindowLongPtrW(hwnd, GWL_EXSTYLE, ex);
  }
#endif

  // 7. Resize the WebContents to fill the widget's client area and
  //    give it focus so the renderer knows the viewport dimensions.
  //    Use the widget's content bounds; fall back to 800x600 if the
  //    window hasn't fully realized yet.
  gfx::Rect client_bounds = widget->GetClientAreaBoundsInScreen();
  gfx::Size viewport_size = client_bounds.size();
  if (viewport_size.IsEmpty()) {
    viewport_size = gfx::Size(800, 600);
  }
  entry.web_contents->Resize(gfx::Rect(viewport_size));
  entry.web_contents->Focus();

  // 7b. Pre-warm the compositor while the widget stays hidden.
  //
  //     Chromium normally suppresses frame production for hidden
  //     widgets, so when Java's kShow eventually makes the HWND
  //     visible the compositor has to raster + composite the page
  //     from scratch — that cold path is the blank-window flash users
  //     see. IncrementCapturerCount(stay_hidden=false, stay_awake=true)
  //     tells Chromium to treat the WebContents as if something were
  //     capturing it: the compositor stays active and produces frames
  //     against the hidden surface, and DidFirstVisuallyNonEmptyPaint
  //     fires at the same time it would for a visible widget. When
  //     kShow finally arrives, widget->Show() swaps the already-
  //     composited frame onto the OS window with no perceptible gap.
  //
  //     The returned ScopedClosureRunner is parked on the entry and
  //     released in CommandDispatcher::OnShow (via JuxReleaseCaptureHandle)
  //     once the OS window is visible and normal occlusion/throttling
  //     can take over.
  // stay_hidden=true: capture the page while it stays hidden (no OS window is
  // ever shown). stay_awake=true keeps the compositor producing frames so the
  // CopyFromSurface loop has fresh content every tick. The handle is parked on
  // the entry and released only at teardown — for a windowless WebView the
  // page must keep rendering for its whole life.
  // skia-fx: stay_hidden=false makes the captured page count as VISIBLE so Blink
  // does NOT freeze its timer/task queues. stay_hidden=true keeps the compositor
  // producing frames (good for static OSR pages) but leaves page visibility
  // hidden, which freezes setInterval/the page lifecycle — JS-driven WebUI like
  // chrome://print then stalls (init chain never advances). The page is still
  // never composited to an OS window (cloaked/off-screen), so OSR is unaffected.
  //
  // capture_size MUST stay empty. A non-empty size is latched once as
  // WebContentsImpl::preferred_size_for_capture_, and the moment the page enters
  // tab-fullscreen (IsFullscreenForTabOrPending()==true) views::WebView::
  // OnBoundsChanged switches to its captured-fullscreen letterbox branch, which
  // pins the renderer viewport to that stale creation-time size — the page then
  // never resizes to the fullscreen bounds (small frame in the top-left). The
  // size is only a quality hint for the tab-capture API, which we don't use
  // (we CopyFromSurface directly); empty is Chromium's "no side effects" mode.
  entry.capture_handle = entry.web_contents->IncrementCapturerCount(
      gfx::Size(), /*stay_hidden=*/false, /*stay_awake=*/true,
      /*is_activity=*/true);
  entry.web_contents->WasShown();

  // 8. Set up the Phase 3 DOM bridge. We create a JuxDomClientImpl that
  //    listens for DOM events fired by the renderer, and stage an
  //    associated remote for the handler side. The actual pipe is bound
  //    lazily the first time a DOM API is invoked (see GetDomHandler()).
  entry.dom_client = std::make_unique<jux::JuxDomClientImpl>(
      jux::g_callback_evt_writer, jux::g_callback_channel);

  (*g_web_contents_map)[handle] = std::move(entry);
  VLOG(1) << "CreateWebContentsOnUI: handle=" << handle;

  // Main-frame capture: viz FrameSinkVideoCapturer (push, follows the
  // surface across resizes — no frame gap during fullscreen/monitor-move
  // transitions). Created AFTER the map insertion because the consumer
  // resolves its entry by handle. --jux-poll-capture (Java:
  // -Dskia.webview.pollCapture=true) falls back to the legacy polling tick.
  WebContentsEntry& live = (*g_web_contents_map)[handle];
  bool use_video_capturer = !base::CommandLine::ForCurrentProcess()->HasSwitch(
      "jux-poll-capture");
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  // The print-preview page's frames route to the preview OVERLAY region via
  // the polling path (PublishPreviewOverlay) — keep it on the tick.
  if (handle == g_print_preview_handle) {
    use_video_capturer = false;
  }
#endif
  if (use_video_capturer) {
    live.video_consumer =
        std::make_unique<JuxVideoConsumer>(handle, live.web_contents.get());
    live.video_consumer->StartCapture();
  }

  // Start the self-scheduling off-screen capture/housekeeping loop. With the
  // video consumer active it no longer copies the main frame (the capturer
  // pushes those) but still drives popup capture, cursor polling and the
  // print-preview frame.
  JuxCaptureTick(handle);
}

// Returns the JuxDomHandler remote for the given WebContents, binding
// it lazily on first use (or after renderer crash). Returns nullptr if
// the handle is unknown or the web contents has no primary main frame
// yet. Must be called on the UI thread.
mojo::AssociatedRemote<jux::mojom::JuxDomHandler>* GetDomHandler(
    JuxWebContentsHandle handle) {
  auto it = g_web_contents_map->find(handle);
  if (it == g_web_contents_map->end()) {
    LOG(WARNING) << "[jux-dom] GetDomHandler: no entry for handle=" << handle;
    return nullptr;
  }
  WebContentsEntry& entry = it->second;
  if (!entry.web_contents) {
    LOG(WARNING) << "[jux-dom] GetDomHandler: web_contents null (h="
                 << handle << ")";
    return nullptr;
  }

  auto* rfh = entry.web_contents->GetPrimaryMainFrame();
  if (!rfh) {
    LOG(WARNING) << "[jux-dom] GetDomHandler: no primary main frame (h="
                 << handle << ")";
    return nullptr;
  }
  VLOG(1) << "[jux-dom] GetDomHandler h=" << handle << " rfh=" << rfh;

  // Rebind if the remote is not bound, was disconnected (renderer crash),
  // or the primary main frame changed since we bound (a navigation swapped
  // the RenderFrameHost — the default with RenderDocument). An associated
  // remote stays is_bound()==true across an RFH swap, so the rfh comparison
  // is what actually catches the stale binding; without it every reply
  // (JsEval result included) is dropped into the dead old frame's endpoint
  // and the Java caller times out.
  if (!entry.dom_handler_remote.is_bound() || entry.dom_handler_rfh != rfh) {
    VLOG(1) << "[jux-dom] GetDomHandler: (re)binding remote (was_bound="
              << entry.dom_handler_remote.is_bound()
              << " rfh_changed=" << (entry.dom_handler_rfh != rfh) << ")";
    entry.dom_handler_remote.reset();
    rfh->GetRemoteAssociatedInterfaces()->GetInterface(
        &entry.dom_handler_remote);
    entry.dom_handler_rfh = rfh;
    entry.dom_client_bound = false;
    // Auto-reset on disconnect so the next call rebinds against the live
    // frame instead of sending into a dead pipe (and waiting out a timeout).
    entry.dom_handler_remote.set_disconnect_handler(base::BindOnce(
        [](JuxWebContentsHandle h) {
          if (!g_web_contents_map) {
            return;
          }
          auto it = g_web_contents_map->find(h);
          if (it != g_web_contents_map->end()) {
            it->second.dom_handler_remote.reset();
            it->second.dom_handler_rfh = nullptr;
            it->second.dom_client_bound = false;
          }
        },
        handle));
  }

  // Hand the renderer a pending_remote for JuxDomClient so it can post
  // DOM events back here. The matching receiver is bound to our local
  // JuxDomClientImpl, which will receive OnDomEvent calls from the
  // renderer and write them to the Java event ring buffer. Done once
  // per pipe binding.
  if (entry.dom_handler_remote.is_bound() && !entry.dom_client_bound &&
      entry.dom_client) {
    mojo::PendingRemote<jux::mojom::JuxDomClient> client_remote;
    mojo::PendingReceiver<jux::mojom::JuxDomClient> client_receiver =
        client_remote.InitWithNewPipeAndPassReceiver();
    entry.dom_client->Bind(std::move(client_receiver));
    entry.dom_handler_remote->SetClient(std::move(client_remote));
    entry.dom_client_bound = true;
  }
  return &entry.dom_handler_remote;
}

}  // namespace

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
namespace jux {

// Chrome's print-preview modal sizing (PrintPreviewDialogDelegate::GetDialogSize in
// print_preview_dialog_controller.cc): start from the initiator size floored at
// 800x480, inset by (50,25), then cap to max(70% width, 1000) on a 1000:660 aspect.
// Keeps the off-screen modal Chrome-accurate; Java centers it over the page.
gfx::Size ComputePrintPreviewDialogSize(const gfx::Size& initiator) {
  gfx::Size size(800, 480);
  size.SetToMax(initiator);
  size.Enlarge(-50, -25);
  const int max_width = std::max(size.width() * 7 / 10, 1000);
  const int max_height = std::max(max_width * 660 / 1000, 660);
  size.SetToMin(gfx::Size(max_width, max_height));
  return size;
}

// Engine-side factory for the off-screen chrome://print WebContents (registered
// as the OpenPrintPreview hook; called by PrintPreviewDialogController::PrintPreview
// when a page invokes window.print()/Ctrl+P). Runs on the UI thread — its caller
// (PrintViewManager) is already on the browser UI thread. Reuses the same OSR
// WebContents+hidden-widget+capture path as a normal WebView, so the preview is a
// second captured surface the Java FrameSurface compositor can overlay (M4).
content::WebContents* OpenPrintPreviewWebContents(
    content::WebContents* initiator) {
  // Offscreen view → no parent HWND. CreateWebContentsOnUI builds the hidden
  // widget + registers the entry for capture under a fresh handle.
  JuxWebContentsHandle handle = g_next_handle.fetch_add(1);
  VLOG(1) << "[print-preview] OpenPrintPreviewWebContents: creating preview "
               "handle=" << handle;
  CreateWebContentsOnUI(handle, /*parent_window=*/0);
  VLOG(1) << "[print-preview] preview WebContents created; navigating";

  auto it = g_web_contents_map->find(handle);
  if (it == g_web_contents_map->end() || !it->second.web_contents) {
    LOG(ERROR) << "[print-preview] failed to create preview WebContents";
    return nullptr;
  }
  content::WebContents* preview = it->second.web_contents.get();

  // The preview is INTERNAL: detach its Java event callbacks so its
  // chrome://print navigation/title/history/load state never leaks to the app as
  // the page's (otherwise the main page's URL becomes chrome://print and a reload
  // tries to load it). Its frames flow via the capture tick; open/close via the
  // dedicated kPrintPreview* events.
  if (it->second.delegate) {
    it->second.delegate->DetachJavaCallbacks();
  }

  // Size the preview as a centered modal INSET from the initiator's view, so the
  // dimmed page shows around it (Chrome-style: page behind, preview on top). Java
  // composites the page snapshot (dimmed) full-area and draws this preview frame
  // centered on top (it knows the node size + this frame's logical size).
  if (auto* init_view = initiator->GetRenderWidgetHostView()) {
    const gfx::Size sz = init_view->GetViewBounds().size();
    if (!sz.IsEmpty() && it->second.widget) {
      it->second.widget->SetBounds(
          gfx::Rect(ComputePrintPreviewDialogSize(sz)));
    }
  }

  // skia-fx: give the preview a REAL display BeginFrame source. The main WebView
  // gets one when Java's kShow calls widget->Show(); this off-screen preview
  // never receives kShow, so its widget would stay hidden for life. A hidden
  // widget + stay_awake capturer keeps the COMPOSITOR warm (rAF/capture work)
  // but leaves the renderer's DELAYED-timer scheduler throttled — setTimeout/
  // setInterval never fire, so the chrome://print WebUI never finishes
  // bootstrapping (its module graph evaluates via timers) and stays on the grey
  // "loading" skeleton. Show the widget so the renderer scheduler runs at full
  // speed, but DWM-cloak the HWND so nothing is ever presented to the user;
  // OSR capture (CopyFromSurface → composited overlay) is unaffected.
  if (it->second.widget) {
#if BUILDFLAG(IS_WIN)
    // Cloak + park the HWND BEFORE Show() so the window never flashes at its
    // default top-left position for a frame on open. The HWND exists at widget
    // creation, so the attributes are in place the instant it's shown. DWM-cloak
    // keeps content off-screen; parking off the virtual desktop is belt-and-
    // suspenders. OSR capture (CopyFromSurface) is unaffected.
    if (HWND hwnd =
            it->second.widget->GetNativeWindow()->GetHost()->GetAcceleratedWidget();
        hwnd) {
      BOOL cloak = TRUE;
      ::DwmSetWindowAttribute(hwnd, DWMWA_CLOAK, &cloak, sizeof(cloak));
      if (!::getenv("OPENJFX_PRINT_PREVIEW_ONSCREEN")) {
        ::SetWindowPos(hwnd, nullptr, -32000, -32000, 0, 0,
                       SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
      }
    }
#endif
    it->second.widget->Show();
    it->second.web_contents->WasShown();
  }
  // The persistent capturer is MANDATORY to render the off-screen preview —
  // releasing it blanks the modal (confirmed with both DWM-cloak and layered
  // windows). It also puts the WebContents in "being captured" mode, which is what
  // blocks the native <select> popup — an irreconcilable tension with this OSR
  // architecture. Do NOT release it here.

  // Mark the preview active: from now until it closes, the engine emits only its
  // frames as kFrameReady and redirects forwarded input to it (see
  // g_print_preview_handle / JuxOnFrameCaptured / JuxSendMouseEvent).
  g_print_preview_handle = handle;
  // The preview's frames must flow through the POLLING path — only
  // JuxOnFrameCaptured routes them to the preview overlay region. Drop the
  // viz consumer the generic creation path attached (the handle wasn't the
  // preview yet at creation time); the capture tick resumes polling it.
  it->second.video_consumer.reset();

  // Navigate the preview to the registered WebUI (chrome://print → PrintPreviewUI).
  VLOG(1) << "[print-preview] sized + active; issuing LoadURL(chrome://print)";
  GURL preview_url("chrome://print/");
  content::NavigationController::LoadURLParams params(preview_url);
  params.transition_type = ui::PAGE_TRANSITION_TYPED;
  preview->GetController().LoadURLWithParams(params);
  VLOG(1) << "[print-preview] LoadURL issued";

  // Signal Java that a print-preview overlay opened, carrying the preview's
  // handle (informational; the engine-side surface takeover above already makes
  // the preview composite over the view, so Java needs no special handling).
  if (jux::g_callback_channel && jux::g_callback_evt_writer) {
    uint8_t payload[4];
    const uint32_t hv = static_cast<uint32_t>(handle);
    memcpy(payload, &hv, 4);
    jux::g_callback_evt_writer->WriteEvent(
        jux::events::kPrintPreviewOpened,
        jux::g_callback_channel->window_id(),
        base::span<const uint8_t>(payload, sizeof(payload)));
  }
  VLOG(1) << "[print-preview] opened off-screen chrome://print (handle="
            << handle << ", initiator=" << initiator << ")";
  return preview;
}

// Tears down the off-screen preview `preview` (Cancel/Print in chrome://print →
// PrintPreviewUI::OnClosePrintPreviewDialog → jux::ClosePrintPreview → here).
// Ends the surface takeover so the initiator resumes, tells Java, and destroys
// the preview WebContents (deferred, so it is safe from the preview's own
// WebUI callback).
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
// Called (cross-TU, from JuxDomClientImpl::OnSelectPopup) when a <select> in the
// off-screen preview is clicked. Builds the Skia drop-down, lays it out within the
// preview view, and composites it over the modal. Returns true if it took over
// (so the caller skips the default Java select-popup path). Declared in
// jux_select_dropdown.h.
bool OpenPreviewSelectDropdown(uintptr_t handle, uint32_t popup_id,
                               uint32_t flags, int32_t selected_index,
                               double x, double y, double w, double h,
                               std::vector<DropdownOption> options) {
  (void)flags;   // print-preview selects are single-select; multi-select unused
  (void)handle;  // DOM client uses the shared global channel, so `handle` is the
                 // MAIN window id, not the preview's — can't match it here.
  // While a preview is open all input is redirected to it, so any overridden
  // <select> popup IS the preview's. Gate purely on a preview being active.
  if (g_print_preview_handle == 0) {
    return false;  // no preview — fall back to the default Java select path
  }
  auto it = g_web_contents_map->find(g_print_preview_handle);
  if (it == g_web_contents_map->end() || !it->second.web_contents) {
    return false;
  }
  content::RenderWidgetHostView* view =
      it->second.web_contents->GetRenderWidgetHostView();
  const gfx::Size vs = view ? view->GetViewBounds().size() : gfx::Size();
  float scale = 1.0f;
  if (it->second.widget && it->second.widget->GetNativeWindow() &&
      it->second.widget->GetNativeWindow()->GetHost()) {
    scale = it->second.widget->GetNativeWindow()->GetHost()->device_scale_factor();
  }
  // Replace any dropdown already open (no nested dropdowns).
  delete g_preview_dropdown;
  g_preview_dropdown = new SkiaDropdown(
      std::move(options), selected_index, static_cast<float>(x),
      static_cast<float>(y), static_cast<float>(w), static_cast<float>(h));
  g_preview_dropdown_popup_id = popup_id;
  g_preview_dropdown->Layout(static_cast<float>(vs.width()),
                             static_cast<float>(vs.height()), scale);
  RenderAndPublishPreviewDropdown();
  return true;
}
#endif  // BUILDFLAG(ENABLE_PRINT_PREVIEW)

void ClosePrintPreviewWebContents(content::WebContents* preview) {
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  DismissPreviewDropdown(/*accept=*/false, -1);  // any open dropdown goes away
#endif
  auto* controller = printing::PrintPreviewDialogController::GetInstance();
  // Reset the INITIATOR's print state so a later window.print()/Ctrl+P can open a
  // fresh preview. Real Chrome calls PrintViewManager::PrintPreviewDone when the
  // dialog closes; our custom teardown must too, or the initiator's
  // PrintRenderFrameHelper stays "in print preview" and silently drops the next
  // print request (the second-open bug). Read the initiator BEFORE removing the
  // mapping below.
  if (content::WebContents* initiator = controller->GetInitiator(preview)) {
    if (auto* pvm = printing::PrintViewManager::FromWebContents(initiator)) {
      pvm->PrintPreviewDone();
    }
  }
  // Remove the controller's initiator→preview mapping so a later open creates a
  // fresh dialog instead of matching this stale (about-to-be-destroyed) entry and
  // early-returning. Both close paths (Cancel via the hook, window-close via
  // JuxDestroyWebContents) reach here, so this is the single correct place.
  controller->RemovePreviewDialog(preview);

  JuxWebContentsHandle handle = 0;
  for (auto& kv : *g_web_contents_map) {
    if (kv.second.web_contents.get() == preview) {
      handle = kv.first;
      break;
    }
  }
  if (handle == 0) {
    return;
  }
  if (g_print_preview_handle == handle) {
    g_print_preview_handle = 0;  // initiator resumes owning the surface
    // The initiator's frames were dropped while the preview owned the surface;
    // force every other live view to repaint so a fresh frame is published right
    // away (otherwise the page can sit blank until something happens to change
    // it). WasShown() nudges the renderer to produce a new compositor frame.
    for (auto& kv : *g_web_contents_map) {
      if (kv.first != handle && kv.second.web_contents) {
        kv.second.web_contents->WasShown();
        if (auto* v = kv.second.web_contents->GetRenderWidgetHostView()) {
          v->Show();
          // Restore input focus to the page so it receives mouse/keyboard again
          // once the modal is gone (input was redirected to the preview while open).
          v->Focus();
        }
      }
    }
  }
  if (jux::g_callback_channel && jux::g_callback_evt_writer) {
    uint8_t payload[4];
    const uint32_t hv = static_cast<uint32_t>(handle);
    memcpy(payload, &hv, 4);
    jux::g_callback_evt_writer->WriteEvent(
        jux::events::kPrintPreviewClosed,
        jux::g_callback_channel->window_id(),
        base::span<const uint8_t>(payload, sizeof(payload)));
  }
  // Deferred full teardown (PostToBrowserThread) — reuses the normal path.
  JuxDestroyWebContents(handle);
  VLOG(1) << "[print-preview] closed (handle=" << handle << ")";
}

}  // namespace jux
#endif  // BUILDFLAG(ENABLE_PRINT_PREVIEW)

// Accessor for JuxBrowserMainParts to read the shared memory channel
// pointer set by JuxRunBrowser.
jux::ipc::SharedMemoryChannel* JuxGetRunBrowserChannel() {
  return g_run_browser_channel;
}

// =========================================================================
// Lifecycle
// =========================================================================

extern "C" JUX_EXPORT int JuxInit(const char* subprocess_path,
                                   const char* pak_path,
                                   int argc,
                                   const char* const* argv) {
  // Resolve file paths from the UTF-8 C strings.
  base::FilePath sub_path;
  if (subprocess_path) {
#if BUILDFLAG(IS_WIN)
    sub_path = base::FilePath(base::UTF8ToWide(subprocess_path));
#else
    sub_path = base::FilePath(subprocess_path);
#endif
  }

  base::FilePath resource_path;
  if (pak_path) {
#if BUILDFLAG(IS_WIN)
    resource_path = base::FilePath(base::UTF8ToWide(pak_path));
#else
    resource_path = base::FilePath(pak_path);
#endif
  }

  // Initialize CommandLine on the current thread. ContentMain runs on
  // THIS thread (the main thread IS the browser UI thread), so all
  // Chromium code that accesses CommandLine does so from here.
  //
  // Forward the exe's argc/argv so switches from
  // Application.engineSwitches() (which arrive via ProcessBuilder on
  // the process command line) are parsed by Chromium. On Windows this
  // is redundant with GetCommandLineW() but harmless; on POSIX it is
  // required — without it switches are silently dropped.
  base::CommandLine::Init(argc, argv);
  base::CommandLine* command_line = base::CommandLine::ForCurrentProcess();
  if (!sub_path.empty()) {
    command_line->AppendSwitchPath(
        switches::kBrowserSubprocessPath, sub_path);
    base::PathService::Override(content::CHILD_PROCESS_EXE, sub_path);
  }

  // All user-facing switches (sandbox, GPU rasterization, ANGLE backend,
  // logging, startup-speed flags, etc.) are now owned by Java via
  // Application.engineSwitches() and arrive on the process command line
  // from ProcessBuilder. On Windows, base::CommandLine::Init reads
  // GetCommandLineW() and parses them automatically; on POSIX, the exe
  // wrapper forwards argv explicitly. The only switch appended here is
  // the programmatically-derived subprocess path (handled above) — it
  // is not a user-tunable value.
  //
  // If you want to add a default (e.g. "no-sandbox"), add it to
  // Application.engineSwitches() so applications can override it. Do
  // not re-add AppendSwitch calls in this function.

  // Apply GPU mode set by JuxSetGpuMode (before JuxRunBrowser).
  if (g_gpu_mode == 1) {
    // Force software rendering via SwiftShader.
    command_line->AppendSwitchASCII("use-gl", "angle");
    command_line->AppendSwitchASCII("use-angle", "swiftshader");
    VLOG(1) << "GPU mode: forced SwiftShader software rendering";
  } else if (g_gpu_mode == 2) {
    // Disable GPU entirely — CPU compositing.
    command_line->AppendSwitch("disable-gpu");
    VLOG(1) << "GPU mode: GPU disabled, CPU compositing";
  }

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  // Force the print-preview PDF plugin IN-PROCESS (disable OOPIF PDF). OOPIF puts
  // the <embed application/x-google-chrome-pdf> in a SEPARATE child frame/process
  // whose compositor surface our OSR capture does not composite (blank document),
  // and whose RenderWidget churn flickers the preview + steals forwarded input.
  // In-process renders the PDF inside the main frame we already capture.
  command_line->AppendSwitchASCII("disable-features", "PdfOopif");
  VLOG(1) << "[print-preview] forcing in-process PDF plugin (PdfOopif off)";
#endif

  VLOG(1) << "Subprocess path set to: " << sub_path.value();

  // Create the delegate — lives for the duration of ContentMain.
  auto delegate = std::make_unique<jux::JuxMainDelegate>(resource_path);

  content::ContentMainParams params(delegate.get());

#if BUILDFLAG(IS_WIN)
  sandbox::SandboxInterfaceInfo sandbox_info = {nullptr};
  content::InitializeSandboxInfo(&sandbox_info);
  params.instance = GetModuleHandle(nullptr);
  params.sandbox_info = &sandbox_info;
#endif

  // ContentMain runs the browser message loop on the current thread.
  // This call BLOCKS until shutdown (JuxShutdown posts the quit closure).
  // BrowserMainParts::PreMainMessageLoopRun sets up the IPC layer
  // (heartbeat, command dispatch, ring buffers) using the shared memory
  // channel stored in g_run_browser_channel by JuxRunBrowser.
  return content::ContentMain(std::move(params));
}

extern "C" JUX_EXPORT int JuxSubprocessMain(int argc, const char** argv) {
  // Child process entry point. Creates a JuxMainDelegate and calls
  // ContentMain, which routes to the correct subprocess based on --type=.
  auto delegate = std::make_unique<jux::JuxMainDelegate>(base::FilePath());

  content::ContentMainParams params(delegate.get());

#if BUILDFLAG(IS_WIN)
  sandbox::SandboxInterfaceInfo sandbox_info = {nullptr};
  content::InitializeSandboxInfo(&sandbox_info);
  params.instance = GetModuleHandle(nullptr);
  params.sandbox_info = &sandbox_info;
#else
  params.argc = argc;
  params.argv = argv;
#endif

  return content::ContentMain(std::move(params));
}

extern "C" JUX_EXPORT void JuxShutdown(void) {
  // Post the quit closure to stop the message loop. Since ContentMain
  // runs on the current thread, this must be called from a non-UI context
  // (e.g., from a timer callback that breaks out of the message loop).
  auto* parts = GetMainParts();
  if (parts) {
    base::OnceClosure quit = parts->GetQuitClosure();
    if (quit) {
      std::move(quit).Run();
    }
  }
  g_web_contents_map->clear();
}

extern "C" JUX_EXPORT int JuxRunBrowser(const char* mmap_path,
                                         int argc,
                                         const char* const* argv) {
  // 1. Convert the mmap path from UTF-8 to a platform FilePath.
  base::FilePath shm_path;
#if BUILDFLAG(IS_WIN)
  shm_path = base::FilePath(base::UTF8ToWide(mmap_path));
#else
  shm_path = base::FilePath(mmap_path);
#endif

  // 2. Open the shared memory channel created by the Java side.
  auto channel = jux::ipc::SharedMemoryChannel::Open(shm_path);
  if (!channel) {
    return 1;
  }
  if (!channel->ValidateHeader()) {
    return 1;
  }

  // 3. Store the channel pointer for BrowserMainParts to access during
  //    PreMainMessageLoopRun (runs on the same thread inside ContentMain).
  g_run_browser_channel = channel.get();

  // 4. Resolve the exe path — Chromium re-invokes this same binary with
  //    --type= flags for renderer, GPU, and utility child processes.
#if BUILDFLAG(IS_WIN)
  wchar_t exe_path_buf[MAX_PATH];
  GetModuleFileNameW(nullptr, exe_path_buf, MAX_PATH);
  base::FilePath self_path(exe_path_buf);
#else
  char exe_path_buf[4096];
  ssize_t len = readlink("/proc/self/exe", exe_path_buf,
                         sizeof(exe_path_buf) - 1);
  if (len > 0) exe_path_buf[len] = '\0';
  base::FilePath self_path(exe_path_buf);
#endif

  base::FilePath pak_path = self_path.DirName().Append(
      FILE_PATH_LITERAL("skia-fx-webview.pak"));

  // 5. Call JuxInit — this BLOCKS until shutdown because ContentMain
  //    runs the browser message loop on the current thread. During
  //    ContentMain's initialization, BrowserMainParts::PreMainMessageLoopRun
  //    reads g_run_browser_channel and sets up the IPC layer.
  std::string sub_path_utf8 = self_path.AsUTF8Unsafe();
  std::string pak_path_utf8 = pak_path.AsUTF8Unsafe();
  int result = JuxInit(sub_path_utf8.c_str(), pak_path_utf8.c_str(),
                        argc, argv);

  // 6. ContentMain has returned — clean up.
  g_run_browser_channel = nullptr;

  return result;
}

// =========================================================================
// WebContents management
// =========================================================================

extern "C" JUX_EXPORT JuxWebContentsHandle JuxCreateWebContents(
    uintptr_t parent_window) {
  JuxWebContentsHandle handle = g_next_handle.fetch_add(1);

  // In the new architecture, this is called from the command dispatcher's
  // WM_TIMER callback, which runs on the UI thread (the main thread).
  // Create the widget directly — no PostTask needed, and no
  // DisallowBlocking restriction since WM_TIMER is dispatched by the
  // native Win32 message pump, not by Chromium's task runner.
  if (content::BrowserThread::CurrentlyOn(content::BrowserThread::UI)) {
    CreateWebContentsOnUI(handle, parent_window);
    return handle;
  }

  // Fallback for off-UI-thread callers (shouldn't happen in normal flow).
  base::WaitableEvent done;
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, uintptr_t parent_window,
         base::WaitableEvent* done) {
        CreateWebContentsOnUI(handle, parent_window);
        done->Signal();
      },
      handle, parent_window, &done));
  done.Wait();
  return handle;
}

extern "C" JUX_EXPORT uintptr_t JuxGetNativeWindow(
    JuxWebContentsHandle handle) {
  auto it = g_web_contents_map->find(handle);
  if (it == g_web_contents_map->end() || !it->second.widget) {
    return 0;
  }
  // Guard each dereference — any link can be null during early
  // initialization or late teardown.
  auto* native_window = it->second.widget->GetNativeWindow();
  if (!native_window) return 0;
  auto* host = native_window->GetHost();
  if (!host) return 0;
  return reinterpret_cast<uintptr_t>(host->GetAcceleratedWidget());
}

extern "C" JUX_EXPORT void JuxDestroyWebContents(
    JuxWebContentsHandle handle) {
  bool posted = PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle) {
        auto it = g_web_contents_map->find(handle);
        if (it == g_web_contents_map->end()) {
          return;
        }

        auto& entry = it->second;

        // Stop the viz video capturer FIRST: it observes the WebContents and
        // its callbacks resolve the entry by handle — both must be dead
        // before the WebContents/entry teardown below.
        entry.video_consumer.reset();

        // Release the capturer handle and cancel the wheel-end timer BEFORE the
        // WebContents is torn down below. capture_handle's ScopedClosureRunner
        // decrements the WebContents' capturer count when it runs; left to the
        // entry's destructor (which fires only after erase(), i.e. after
        // web_contents.reset()), that closure would touch a freed WebContents.
        // wheel_end_timer.reset() proactively cancels the pending
        // JuxEndWheelScroll so it can't fire mid-teardown.
        entry.capture_handle.RunAndReset();
        entry.wheel_end_timer.reset();

        // Remove the observer before destroying the widget to avoid
        // dangling pointer access during destruction.
        if (entry.widget_observer && entry.widget) {
          entry.widget->RemoveObserver(entry.widget_observer.get());
        }
        entry.widget_observer.reset();

        // Permit the close (JuxWidgetDelegate blocks close by default).
        if (entry.widget_delegate) {
          entry.widget_delegate->AllowClose();
          entry.widget_delegate = nullptr;
        }

        // Detach the WebContents from the WebView BEFORE closing the
        // widget. CloseNow() destroys the view tree (including the
        // WebView), and if the WebContents is still attached, Chromium
        // may access it during teardown → use-after-free. This also
        // ensures renderer frames are properly detached, fixing the
        // "WebFrame LEAKED" warnings in debug builds.
        if (entry.web_view) {
          entry.web_view->SetWebContents(nullptr);
        }
        entry.delegate.reset();
        entry.web_contents.reset();

        // CloseNow destroys the native window and view tree immediately.
        if (entry.widget) {
          entry.web_view = nullptr;  // Owned by widget, will be destroyed.
          entry.widget->CloseNow();
          entry.widget = nullptr;
        }

        g_web_contents_map->erase(it);

        VLOG(1) << "JuxDestroyWebContents: handle=" << handle;
      },
      handle));
  if (!posted) {
    LOG(ERROR) << "JuxDestroyWebContents: PostToBrowserThread failed";
  }
}

extern "C" JUX_EXPORT void JuxResizeWebContents(
    JuxWebContentsHandle handle, int x, int y, int width, int height) {
  bool posted = PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, int x, int y, int w, int h) {
        auto it = g_web_contents_map->find(handle);
        if (it == g_web_contents_map->end()) {
          return;
        }
        auto& entry = it->second;

        // Resize the widget. The WebView inside uses FillLayout so it
        // automatically fills the widget's content area.
        if (entry.widget) {
          entry.widget->SetBounds(gfx::Rect(x, y, w, h));
        }
        // NOTE: window resize for the OSR WebView goes through the SET_SIZE
        // command → OnSetSize → JuxSetOffscreenSize, NOT this entry. The print-
        // preview modal is re-sized there via JuxAdaptPrintPreviewToInitiator.
      },
      handle, x, y, width, height));
  if (!posted) {
    LOG(ERROR) << "JuxResizeWebContents: PostToBrowserThread failed";
  }
}

// =========================================================================
// Widget visibility
// =========================================================================

extern "C" JUX_EXPORT void JuxShowWidget(JuxWebContentsHandle handle) {
  auto it = g_web_contents_map->find(handle);
  if (it == g_web_contents_map->end()) return;
  auto& entry = it->second;
  if (entry.widget) {
#if BUILDFLAG(IS_WIN)
    // On Windows the widget was already shown at creation and DWM-
    // cloaked. Un-cloak here — DWM presents the already-composited
    // frame on its next tick, so the window appears with content
    // already on screen. A defensive widget->Show() is included in
    // case something hid it in the interim (e.g. JuxHideWidget
    // followed by JuxShowWidget); widget->Show() is idempotent when
    // already visible.
    if (HWND hwnd = entry.widget->GetNativeWindow()->GetHost()->GetAcceleratedWidget();
        hwnd) {
      BOOL cloak = FALSE;
      ::DwmSetWindowAttribute(hwnd, DWMWA_CLOAK, &cloak, sizeof(cloak));
    }
    entry.widget->Show();
#else
    // macOS / Linux: widget was left hidden at creation, show it now.
    // The compositor was kept warm via IncrementCapturerCount so the
    // first presented frame lands promptly.
    entry.widget->Show();
#endif
  }
  // Normal visibility/occlusion semantics take over — release the
  // capturer handle that was keeping the compositor pre-warmed. Safe
  // no-op if capture_handle was never set (e.g. show after hide).
  entry.capture_handle.RunAndReset();
}

extern "C" JUX_EXPORT void JuxHideWidget(JuxWebContentsHandle handle) {
  auto it = g_web_contents_map->find(handle);
  if (it != g_web_contents_map->end() && it->second.widget) {
    it->second.widget->Hide();
  }
}

extern "C" JUX_EXPORT void JuxAllowClose(JuxWebContentsHandle handle) {
  auto it = g_web_contents_map->find(handle);
  if (it != g_web_contents_map->end() && it->second.widget_delegate) {
    it->second.widget_delegate->AllowClose();
  }
}

// =========================================================================
// Navigation
// =========================================================================

extern "C" JUX_EXPORT void JuxLoadURL(JuxWebContentsHandle handle,
                                       const char* url) {
  std::string url_str(url);
  bool posted = PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, std::string url) {
        VLOG(1) << "JuxLoadURL executing: " << url;
        auto it = g_web_contents_map->find(handle);
        if (it == g_web_contents_map->end()) {
          LOG(ERROR) << "JuxLoadURL: handle " << handle << " not found";
          return;
        }
        GURL gurl(url);
        content::NavigationController::LoadURLParams load_params(gurl);
        load_params.transition_type = ui::PAGE_TRANSITION_TYPED;
        // If a per-WebView UA override is set (via JuxSetUserAgent), apply it
        // to this navigation; otherwise inherit (uses the global default UA
        // from JuxBrowserClient::GetUserAgent).
        if (!it->second.web_contents->GetUserAgentOverride()
                 .ua_string_override.empty()) {
          load_params.override_user_agent =
              content::NavigationController::UA_OVERRIDE_TRUE;
        }
        it->second.web_contents->GetController().LoadURLWithParams(
            load_params);
      },
      handle, std::move(url_str)));
  if (!posted) {
    LOG(ERROR) << "JuxLoadURL: PostToBrowserThread failed — UI thread not initialized";
  }
}

extern "C" JUX_EXPORT void JuxGoToOffset(JuxWebContentsHandle handle,
                                          int32_t offset) {
  bool posted = PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, int32_t offset) {
        auto it = g_web_contents_map->find(handle);
        if (it == g_web_contents_map->end()) {
          LOG(ERROR) << "JuxGoToOffset: handle " << handle << " not found";
          return;
        }
        content::NavigationController& controller =
            it->second.web_contents->GetController();
        if (controller.CanGoToOffset(offset)) {
          controller.GoToOffset(offset);
        }
      },
      handle, offset));
  if (!posted) {
    LOG(ERROR) << "JuxGoToOffset: PostToBrowserThread failed — UI thread "
                  "not initialized";
  }
}

extern "C" JUX_EXPORT void JuxRestoreSession(JuxWebContentsHandle handle,
                                             const uint8_t* data, uint32_t len) {
  std::vector<uint8_t> blob(data, data + len);
  bool posted = PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle h, std::vector<uint8_t> blob) {
        auto it = g_web_contents_map->find(h);
        if (it == g_web_contents_map->end() || !it->second.web_contents) {
          return;
        }
        content::WebContents* wc = it->second.web_contents.get();

        // Parse the SerializeSession() blob:
        //   [count:4][currentIndex:4]{ [urlLen:4][url][psLen:4][pageState] }
        const std::vector<uint8_t>& b = blob;
        size_t off = 0;
        auto get_u32 = [&](uint32_t* out) -> bool {
          if (off + 4 > b.size()) return false;
          *out = static_cast<uint32_t>(b[off]) |
                 (static_cast<uint32_t>(b[off + 1]) << 8) |
                 (static_cast<uint32_t>(b[off + 2]) << 16) |
                 (static_cast<uint32_t>(b[off + 3]) << 24);
          off += 4;
          return true;
        };
        auto get_str = [&](std::string* out) -> bool {
          uint32_t slen = 0;
          if (!get_u32(&slen) || off + slen > b.size()) return false;
          out->assign(reinterpret_cast<const char*>(b.data() + off), slen);
          off += slen;
          return true;
        };

        uint32_t count = 0, current_u = 0;
        if (!get_u32(&count) || !get_u32(&current_u)) {
          return;
        }
        std::vector<std::unique_ptr<content::NavigationEntry>> entries;
        std::unique_ptr<content::NavigationEntryRestoreContext> ctx =
            content::NavigationEntryRestoreContext::Create();
        for (uint32_t i = 0; i < count; ++i) {
          std::string url, page_state;
          if (!get_str(&url) || !get_str(&page_state)) break;
          std::unique_ptr<content::NavigationEntry> entry =
              content::NavigationController::CreateNavigationEntry(
                  GURL(url), content::Referrer(),
                  /*initiator_origin=*/std::nullopt,
                  /*initiator_base_url=*/std::nullopt,
                  ui::PAGE_TRANSITION_RELOAD,
                  /*is_renderer_initiated=*/false,
                  /*extra_headers=*/std::string(), wc->GetBrowserContext(),
                  /*blob_url_loader_factory=*/nullptr);
          if (!page_state.empty()) {
            entry->SetPageState(
                blink::PageState::CreateFromEncodedData(page_state), ctx.get());
          }
          entries.push_back(std::move(entry));
        }
        if (entries.empty()) {
          return;
        }
        int current = static_cast<int>(current_u);
        if (current < 0 || current >= static_cast<int>(entries.size())) {
          current = static_cast<int>(entries.size()) - 1;
        }
        wc->GetController().Restore(current, content::RestoreType::kRestored,
                                    &entries);
        wc->GetController().LoadIfNecessary();
      },
      handle, std::move(blob)));
  if (!posted) {
    LOG(ERROR) << "JuxRestoreSession: PostToBrowserThread failed";
  }
}

extern "C" JUX_EXPORT void JuxSetUserAgent(JuxWebContentsHandle handle,
                                            const char* user_agent) {
  // Empty/null clears the override (subsequent navigations fall back to the
  // global default UA from JuxBrowserClient::GetUserAgent).
  std::string ua_str(user_agent ? user_agent : "");
  bool posted = PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, std::string ua) {
        auto it = g_web_contents_map->find(handle);
        if (it == g_web_contents_map->end()) {
          return;
        }
        // UserAgentOnly => override the UA string with blank client hints.
        // override_in_new_tabs=true so renderer-initiated navigations also
        // pick it up. The actual application per-navigation is gated by
        // JuxLoadURL setting override_user_agent when this string is set.
        it->second.web_contents->SetUserAgentOverride(
            blink::UserAgentOverride::UserAgentOnly(ua),
            /*override_in_new_tabs=*/true);
      },
      handle, std::move(ua_str)));
  if (!posted) {
    LOG(ERROR) << "JuxSetUserAgent: PostToBrowserThread failed — UI thread "
                  "not initialized";
  }
}

extern "C" JUX_EXPORT void JuxLoadHTML(JuxWebContentsHandle handle,
                                        const char* html,
                                        const char* base_url) {
  std::string html_str(html ? html : "");
  // Empty (not "about:blank") when the caller gave no base — see below.
  std::string base_str(base_url ? base_url : "");
  bool posted = PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, std::string html, std::string base_url) {
        auto it = g_web_contents_map->find(handle);
        if (it == g_web_contents_map->end()) {
          return;
        }
        // Percent-encode the HTML body (encodeURIComponent-equivalent) before
        // splicing it into the data: URL. Raw concatenation truncated the
        // document at the first '#' (and corrupted on '%', '&', etc.); a
        // single CSS color like "#fff" or an href="#anchor" was enough to
        // cut the page off. EscapeQueryParamValue escapes everything the URL
        // parser would otherwise treat as structure, and the parser decodes
        // it back to the original bytes.
        GURL data_url("data:text/html;charset=utf-8," +
                      base::EscapeQueryParamValue(html, /*use_plus=*/false));
        content::NavigationController::LoadURLParams params(data_url);
        params.load_type = content::NavigationController::LOAD_TYPE_DATA;
        if (!base_url.empty()) {
          // Honor the caller's base URL (previously computed and thrown away):
          // relative URLs in the HTML resolve against it, and it's shown to the
          // user instead of the giant data: blob — matching
          // WebEngine.loadContent(content, baseUrl) semantics.
          GURL base_gurl(base_url);
          params.base_url_for_data_url = base_gurl;
          params.virtual_url_for_special_cases = base_gurl;
        }
        it->second.web_contents->GetController().LoadURLWithParams(params);
      },
      handle, std::move(html_str), std::move(base_str)));
  if (!posted) {
    LOG(ERROR) << "JuxLoadHTML: PostToBrowserThread failed";
  }
}

// =========================================================================
// JavaScript execution
// =========================================================================

// --- JSObject op result plumbing (browser thread) --------------------------
//
// Mojo replies from the renderer's Js* ops land here and route to the typed
// result event (on_js_value) or the error event (on_js_error), correlated by
// request_id. Reply-bearing ops (get/call/eval) carry (value, error); ack-only
// ops (set/remove) carry just error and report success as a null JS_VALUE so
// the Java waiter's latch always releases.

namespace {

void JuxDeliverJsValue(JuxWebContentsHandle handle, uint32_t request_id,
                       const std::vector<uint8_t>& value,
                       const std::string& error) {
  VLOG(1) << "[jux-js] reply reqid=" << request_id << " err='" << error
            << "' vlen=" << value.size();
  if (!error.empty()) {
    if (g_callbacks.on_js_error) {
      g_callbacks.on_js_error(handle, request_id, error.c_str(),
                              static_cast<uint32_t>(error.size()));
    }
  } else if (g_callbacks.on_js_value) {
    g_callbacks.on_js_value(handle, request_id, value.data(),
                            static_cast<uint32_t>(value.size()));
  }
}

void JuxDeliverJsAck(JuxWebContentsHandle handle, uint32_t request_id,
                     const std::string& error) {
  if (!error.empty()) {
    if (g_callbacks.on_js_error) {
      g_callbacks.on_js_error(handle, request_id, error.c_str(),
                              static_cast<uint32_t>(error.size()));
    }
  } else if (g_callbacks.on_js_value) {
    const uint8_t kNull[1] = {0};  // tag 0 = null/undefined ack
    g_callbacks.on_js_value(handle, request_id, kNull, 1);
  }
}

void JuxDeliverJsError(JuxWebContentsHandle handle, uint32_t request_id,
                       const std::string& message) {
  if (g_callbacks.on_js_error) {
    g_callbacks.on_js_error(handle, request_id, message.c_str(),
                            static_cast<uint32_t>(message.size()));
  }
}

// Sentinel error delivered to Java when a JS reply pipe is torn down without
// an answer (renderer crash, frame swap mid-call). Keeps the round-trip
// total: the Java caller always gets a value or a JSException, never a hang.
constexpr char kJsReplyDropped[] = "JS reply lost (renderer disconnected)";

// Wrap a value-returning JS reply so Mojo still invokes it (with an empty
// value + the dropped-reply error) if the renderer never answers. Without
// this, a dropped reply silently abandons the callback and the caller waits
// out the full executeScript timeout.
base::OnceCallback<void(const std::vector<uint8_t>&, const std::string&)>
MakeJsValueReply(JuxWebContentsHandle handle, uint32_t request_id) {
  return mojo::WrapCallbackWithDefaultInvokeIfNotRun(
      base::BindOnce(&JuxDeliverJsValue, handle, request_id),
      std::vector<uint8_t>{}, std::string(kJsReplyDropped));
}

// Same guarantee for ack-only ops (set member/slot/etc.), whose reply carries
// just an error string.
base::OnceCallback<void(const std::string&)> MakeJsAckReply(
    JuxWebContentsHandle handle, uint32_t request_id) {
  return mojo::WrapCallbackWithDefaultInvokeIfNotRun(
      base::BindOnce(&JuxDeliverJsAck, handle, request_id),
      std::string(kJsReplyDropped));
}

}  // namespace

extern "C" JUX_EXPORT void JuxExecuteJS(JuxWebContentsHandle handle,
                                          const char* script,
                                          uint32_t request_id) {
  // executeScript == JsEval in the global scope. Routing through the renderer's
  // V8 (rather than ExecuteJavaScriptForTests) yields a typed, live result so
  // object results become real JSObjects.
  std::string script_str(script ? script : "");
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle h, uint32_t reqid, std::string s) {
        auto* remote = GetDomHandler(h);
        VLOG(1) << "[jux-js] executeJS reqid=" << reqid
                  << " bound=" << (remote && remote->is_bound());
        if (!remote || !remote->is_bound()) {
          JuxDeliverJsError(h, reqid, "JS engine not ready");
          return;
        }
        (*remote)->JsEval(0, s, MakeJsValueReply(h, reqid));
      },
      handle, request_id, std::move(script_str)));
}

extern "C" JUX_EXPORT void JuxJsGetMember(JuxWebContentsHandle handle,
                                          uint32_t request_id, int32_t object_id,
                                          const char* name) {
  std::string name_str(name ? name : "");
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle h, uint32_t reqid, int32_t obj, std::string nm) {
        auto* remote = GetDomHandler(h);
        if (!remote || !remote->is_bound()) {
          JuxDeliverJsError(h, reqid, "JS engine not ready");
          return;
        }
        (*remote)->JsGetMember(obj, nm,
                               MakeJsValueReply(h, reqid));
      },
      handle, request_id, object_id, std::move(name_str)));
}

extern "C" JUX_EXPORT void JuxJsSetMember(JuxWebContentsHandle handle,
                                          uint32_t request_id, int32_t object_id,
                                          const char* name, const uint8_t* value,
                                          uint32_t value_len) {
  std::string name_str(name ? name : "");
  std::vector<uint8_t> val(value, value + value_len);
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle h, uint32_t reqid, int32_t obj, std::string nm,
         std::vector<uint8_t> v) {
        auto* remote = GetDomHandler(h);
        if (!remote || !remote->is_bound()) {
          JuxDeliverJsError(h, reqid, "JS engine not ready");
          return;
        }
        (*remote)->JsSetMember(obj, nm, v,
                               MakeJsAckReply(h, reqid));
      },
      handle, request_id, object_id, std::move(name_str), std::move(val)));
}

extern "C" JUX_EXPORT void JuxJsRemoveMember(JuxWebContentsHandle handle,
                                             uint32_t request_id,
                                             int32_t object_id,
                                             const char* name) {
  std::string name_str(name ? name : "");
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle h, uint32_t reqid, int32_t obj, std::string nm) {
        auto* remote = GetDomHandler(h);
        if (!remote || !remote->is_bound()) {
          JuxDeliverJsError(h, reqid, "JS engine not ready");
          return;
        }
        (*remote)->JsRemoveMember(obj, nm,
                                  MakeJsAckReply(h, reqid));
      },
      handle, request_id, object_id, std::move(name_str)));
}

extern "C" JUX_EXPORT void JuxJsGetSlot(JuxWebContentsHandle handle,
                                        uint32_t request_id, int32_t object_id,
                                        int32_t index) {
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle h, uint32_t reqid, int32_t obj, int32_t idx) {
        auto* remote = GetDomHandler(h);
        if (!remote || !remote->is_bound()) {
          JuxDeliverJsError(h, reqid, "JS engine not ready");
          return;
        }
        (*remote)->JsGetSlot(obj, idx,
                             MakeJsValueReply(h, reqid));
      },
      handle, request_id, object_id, index));
}

extern "C" JUX_EXPORT void JuxJsSetSlot(JuxWebContentsHandle handle,
                                        uint32_t request_id, int32_t object_id,
                                        int32_t index, const uint8_t* value,
                                        uint32_t value_len) {
  std::vector<uint8_t> val(value, value + value_len);
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle h, uint32_t reqid, int32_t obj, int32_t idx,
         std::vector<uint8_t> v) {
        auto* remote = GetDomHandler(h);
        if (!remote || !remote->is_bound()) {
          JuxDeliverJsError(h, reqid, "JS engine not ready");
          return;
        }
        (*remote)->JsSetSlot(obj, idx, v,
                             MakeJsAckReply(h, reqid));
      },
      handle, request_id, object_id, index, std::move(val)));
}

extern "C" JUX_EXPORT void JuxJsCall(JuxWebContentsHandle handle,
                                     uint32_t request_id, int32_t object_id,
                                     const char* name, uint32_t argc,
                                     const uint8_t* args, uint32_t args_len) {
  std::string name_str(name ? name : "");
  std::vector<uint8_t> arg_bytes(args, args + args_len);
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle h, uint32_t reqid, int32_t obj, std::string nm,
         uint32_t n, std::vector<uint8_t> a) {
        auto* remote = GetDomHandler(h);
        if (!remote || !remote->is_bound()) {
          JuxDeliverJsError(h, reqid, "JS engine not ready");
          return;
        }
        (*remote)->JsCall(obj, nm, n, a,
                          MakeJsValueReply(h, reqid));
      },
      handle, request_id, object_id, std::move(name_str), argc,
      std::move(arg_bytes)));
}

extern "C" JUX_EXPORT void JuxJsEval(JuxWebContentsHandle handle,
                                     uint32_t request_id, int32_t object_id,
                                     const char* script) {
  std::string script_str(script ? script : "");
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle h, uint32_t reqid, int32_t obj, std::string s) {
        auto* remote = GetDomHandler(h);
        if (!remote || !remote->is_bound()) {
          JuxDeliverJsError(h, reqid, "JS engine not ready");
          return;
        }
        (*remote)->JsEval(obj, s, MakeJsValueReply(h, reqid));
      },
      handle, request_id, object_id, std::move(script_str)));
}

extern "C" JUX_EXPORT void JuxJsRelease(JuxWebContentsHandle handle,
                                        int32_t object_id) {
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle h, int32_t obj) {
        auto* remote = GetDomHandler(h);
        if (remote && remote->is_bound()) {
          (*remote)->JsRelease(obj);
        }
      },
      handle, object_id));
}

extern "C" JUX_EXPORT void JuxResolveJavaCall(JuxWebContentsHandle handle,
                                              int32_t call_id, bool ok,
                                              const uint8_t* value,
                                              uint32_t value_len,
                                              const char* error) {
  std::vector<uint8_t> val(value, value + value_len);
  std::string err(error ? error : "");
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle h, int32_t cid, bool good,
         std::vector<uint8_t> v, std::string e) {
        auto* remote = GetDomHandler(h);
        if (remote && remote->is_bound()) {
          (*remote)->ResolveJavaCall(cid, good, v, e);
        }
      },
      handle, call_id, ok, std::move(val), std::move(err)));
}

extern "C" JUX_EXPORT void JuxRespondDialog(JuxWebContentsHandle handle,
                                            uint32_t dialog_id, bool accepted,
                                            const char* text, uint32_t text_len) {
  // Build from (ptr,len) — NOT c_str() — so a prompt reply with an embedded
  // '\0' survives intact all the way to the page's prompt() return value.
  std::string text_str(text ? std::string(text, text_len) : std::string());
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, uint32_t dialog_id, bool accepted,
         std::string text) {
        auto it = g_web_contents_map->find(handle);
        if (it == g_web_contents_map->end() || !it->second.delegate) {
          return;
        }
        jux::JuxJsDialogManager* mgr =
            it->second.delegate->js_dialog_manager();
        if (mgr) {
          mgr->Respond(dialog_id, accepted, text);
        }
      },
      handle, dialog_id, accepted, std::move(text_str)));
}

extern "C" JUX_EXPORT void JuxRespondFullscreen(JuxWebContentsHandle handle,
                                                uint32_t fs_id, bool allowed) {
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, uint32_t fs_id, bool allowed) {
        auto it = g_web_contents_map->find(handle);
        if (it == g_web_contents_map->end() || !it->second.delegate) {
          return;
        }
        it->second.delegate->RespondFullscreen(fs_id, allowed);
      },
      handle, fs_id, allowed));
}

extern "C" JUX_EXPORT void JuxRespondPermission(JuxWebContentsHandle handle,
                                                uint32_t perm_id, bool granted) {
  PostToBrowserThread(base::BindOnce(
      [](uint32_t perm_id, bool granted) {
        jux::JuxPermissionManager* mgr = jux::JuxPermissionManager::GetInstance();
        if (mgr) {
          mgr->Respond(perm_id, granted);
        }
      },
      perm_id, granted));
}

extern "C" JUX_EXPORT void JuxRespondAuth(JuxWebContentsHandle handle,
                                          uint32_t auth_id, bool supplied,
                                          const char* user, const char* pass) {
  std::string user_str(user ? user : "");
  std::string pass_str(pass ? pass : "");
  PostToBrowserThread(base::BindOnce(
      [](uint32_t auth_id, bool supplied, std::string user, std::string pass) {
        jux::JuxLoginDelegate* d = jux::JuxLoginDelegate::GetByAuthId(auth_id);
        if (d) {
          d->Respond(supplied, user, pass);
        }
      },
      auth_id, supplied, std::move(user_str), std::move(pass_str)));
}

extern "C" JUX_EXPORT void JuxRespondDownload(JuxWebContentsHandle handle,
                                              uint32_t download_id,
                                              bool accepted, const char* path) {
  std::string path_str(path ? path : "");
  PostToBrowserThread(base::BindOnce(
      [](uint32_t download_id, bool accepted, std::string path) {
        jux::JuxDownloadManagerDelegate* d =
            jux::JuxDownloadManagerDelegate::GetInstance();
        if (d) {
          d->Respond(download_id, accepted, path);
        }
      },
      download_id, accepted, std::move(path_str)));
}

extern "C" JUX_EXPORT void JuxCancelDownload(JuxWebContentsHandle handle,
                                             uint32_t download_id) {
  PostToBrowserThread(base::BindOnce(
      [](uint32_t download_id) {
        jux::JuxDownloadManagerDelegate* d =
            jux::JuxDownloadManagerDelegate::GetInstance();
        if (d) {
          d->Cancel(download_id);
        }
      },
      download_id));
}

// =========================================================================
// DevTools
// =========================================================================

extern "C" JUX_EXPORT void JuxOpenDevTools(JuxWebContentsHandle handle) {
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle) {
        auto it = g_web_contents_map->find(handle);
        if (it == g_web_contents_map->end()) {
          return;
        }
        // TODO(Phase 5): Open DevTools frontend window.
        // Requires creating a DevToolsAgentHost and a frontend WebContents.
        VLOG(1) << "JuxOpenDevTools: not yet implemented";
      },
      handle));
}

extern "C" JUX_EXPORT void JuxCloseDevTools(JuxWebContentsHandle handle) {
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle) {
        // TODO(Phase 5): Close DevTools frontend.
        VLOG(1) << "JuxCloseDevTools: not yet implemented";
      },
      handle));
}

// =========================================================================
// Focus and input
// =========================================================================

extern "C" JUX_EXPORT void JuxNotifyFocus(JuxWebContentsHandle handle,
                                            int focused) {
  bool posted = PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, bool focused) {
        auto it = g_web_contents_map->find(handle);
        if (it == g_web_contents_map->end()) {
          return;
        }
        if (focused) {
          it->second.web_contents->Focus();
        } else {
          it->second.web_contents->StoreFocus();
        }
      },
      handle, focused != 0));
  if (!posted) {
    LOG(ERROR) << "JuxNotifyFocus: PostToBrowserThread failed";
  }
}

extern "C" JUX_EXPORT void JuxNotifyScaleFactorChanged(
    JuxWebContentsHandle handle, float scale_factor) {
  // Superseded by JuxSetOffscreenSize, which carries width/height + scale
  // together so the view is resized and re-scaled atomically.
}

extern "C" JUX_EXPORT void JuxAdaptPrintPreviewToInitiator(
    JuxWebContentsHandle initiator, int width, int height, float scale) {
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  if (g_print_preview_handle == 0 || initiator == g_print_preview_handle) return;
  if (width <= 0 || height <= 0) return;
  // A window resize / maximize / restore must close any open drop-down.
  DismissPreviewDropdown(/*accept=*/false, -1);
  // Re-run Chrome's GetDialogSize for the initiator's new logical size and resize
  // the off-screen preview through the SAME OSR path the page uses (DIP size +
  // capture scale + fast-capture burst), so the centered modal tracks the window.
  const gfx::Size ps =
      jux::ComputePrintPreviewDialogSize(gfx::Size(width, height));
  JuxSetOffscreenSize(g_print_preview_handle, ps.width(), ps.height(), scale);
#endif
}

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
namespace {

// Writes `data` to `path` on a blocking thread (the print PDF is already in hand).
void WritePrintPdf(const base::FilePath& path,
                   scoped_refptr<base::RefCountedMemory> data) {
  base::ThreadPool::PostTaskAndReplyWithResult(
      FROM_HERE, {base::MayBlock(), base::TaskPriority::USER_VISIBLE},
      base::BindOnce(
          [](base::FilePath p, scoped_refptr<base::RefCountedMemory> d) {
            return base::WriteFile(
                p, base::span<const uint8_t>(d->data(), d->size()));
          },
          path, data),
      base::BindOnce(
          [](base::FilePath p, bool ok) {
            VLOG(1) << "[jux-print] print-to-pdf saved=" << ok
                      << " path=" << p.AsUTF8Unsafe();
          },
          path));
}

void JuxOnPrintToPdfDone(std::string path,
                         print_to_pdf::PdfPrintResult result,
                         scoped_refptr<base::RefCountedMemory> data) {
  if (result != print_to_pdf::PdfPrintResult::kPrintSuccess) {
    LOG(ERROR) << "[jux-print] print-to-pdf failed: "
               << print_to_pdf::PdfPrintResultToString(result);
    return;
  }
  if (!data || data->size() == 0) {
    LOG(ERROR) << "[jux-print] print-to-pdf: empty data";
    return;
  }
  if (!path.empty()) {
    WritePrintPdf(base::FilePath::FromUTF8Unsafe(path), std::move(data));
    return;
  }
  // No path → ask Java for a save location (JavaFX FileChooser), then write.
  jux::ShowSavePdfDialog(
      u"document.pdf",
      base::BindOnce(
          [](scoped_refptr<base::RefCountedMemory> d, base::FilePath chosen) {
            if (!chosen.empty()) {
              WritePrintPdf(chosen, std::move(d));
            }
          },
          std::move(data)));
}

}  // namespace
#endif  // BUILDFLAG(ENABLE_PRINT_PREVIEW)

// Opens the interactive chrome://print preview for `handle` (same path as the
// page's window.print()/Ctrl+P). No-op when print preview is disabled.
extern "C" JUX_EXPORT void JuxShowPrintPreview(JuxWebContentsHandle handle) {
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  auto it = g_web_contents_map->find(handle);
  if (it == g_web_contents_map->end() || !it->second.web_contents) {
    return;
  }
  content::WebContents* wc = it->second.web_contents.get();
  content::RenderFrameHost* rfh = wc->GetPrimaryMainFrame();
  if (!rfh) {
    return;
  }
  if (auto* pvm = printing::PrintViewManager::FromWebContents(wc)) {
    pvm->PrintPreviewNow(rfh, /*has_selection=*/false);
  }
#endif
}

// Headlessly renders `handle`'s page to a PDF. A non-empty `path` writes the PDF
// directly there; an empty/null `path` pops a native "Save As" dialog first.
extern "C" JUX_EXPORT void JuxPrintToPdf(JuxWebContentsHandle handle,
                                         const char* path) {
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  auto it = g_web_contents_map->find(handle);
  if (it == g_web_contents_map->end() || !it->second.web_contents) {
    return;
  }
  content::WebContents* wc = it->second.web_contents.get();
  content::RenderFrameHost* rfh = wc->GetPrimaryMainFrame();
  if (!rfh) {
    return;
  }
  auto params_or_err = print_to_pdf::GetPrintPagesParams(
      rfh->GetLastCommittedURL(), /*landscape=*/std::nullopt,
      /*display_header_footer=*/false, /*print_background=*/std::nullopt,
      /*scale=*/std::nullopt, /*paper_width=*/std::nullopt,
      /*paper_height=*/std::nullopt, /*margin_top=*/std::nullopt,
      /*margin_bottom=*/std::nullopt, /*margin_left=*/std::nullopt,
      /*margin_right=*/std::nullopt, /*header_template=*/std::nullopt,
      /*footer_template=*/std::nullopt, /*prefer_css_page_size=*/std::nullopt,
      /*generate_tagged_pdf=*/std::nullopt,
      /*generate_document_outline=*/std::nullopt);
  if (std::holds_alternative<std::string>(params_or_err)) {
    LOG(ERROR) << "[jux-print] print-to-pdf params error: "
               << std::get<std::string>(params_or_err);
    return;
  }
  auto params = std::move(
      std::get<printing::mojom::PrintPagesParamsPtr>(params_or_err));
  std::string save_path = path ? std::string(path) : std::string();
  if (auto* pvm = printing::PrintViewManager::FromWebContents(wc)) {
    pvm->PrintToPdf(rfh, /*page_ranges=*/std::string(), std::move(params),
                    base::BindOnce(&JuxOnPrintToPdfDone, save_path));
  }
#endif
}

extern "C" JUX_EXPORT void JuxSetOffscreenSize(JuxWebContentsHandle handle,
                                               int width, int height,
                                               float scale) {
  if (width <= 0 || height <= 0) return;
  if (scale <= 0.0f) scale = 1.0f;
  bool posted = PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, int w, int h, float scale) {
        auto it = g_web_contents_map->find(handle);
        if (it == g_web_contents_map->end() || !it->second.web_contents) {
          return;
        }
        auto& entry = it->second;
        entry.capture_scale = scale;  // device frame = w*scale × h*scale
        entry.last_logical_w = w;     // re-asserted by the tick on DIP drift
        entry.last_logical_h = h;
        // Burst-capture briefly so the post-reflow frame at the new size lands
        // fast (otherwise it waits up to a full steady tick → slow to "fit",
        // most visibly on small→maximize). A continuous drag keeps refreshing
        // this, so the whole resize stays responsive. No new tick loop is
        // started — JuxCaptureTick just shortens its own reschedule interval.
        entry.fast_capture_frames = kResizeFastFrames;
        // Size the views::Widget / WebView to the LOGICAL (DIP) size, so the
        // RenderWidgetHostView's viewport is exactly w x h DIP regardless of
        // which monitor the hidden window happens to sit on.
        if (entry.widget) {
          entry.widget->SetSize(gfx::Size(w, h));
        }
        entry.web_contents->Resize(gfx::Rect(0, 0, w, h));

        content::RenderWidgetHostView* view =
            entry.web_contents->GetRenderWidgetHostView();
        if (view) {
          // Make the renderer's EFFECTIVE device-scale equal the JavaFX render
          // scale, so the captured frame is w*scale x h*scale device px —
          // HiDPI-crisp and identical no matter which monitor (and DPI) the
          // hidden capture window lives on. The override's divisor (the hidden
          // window's host DSF) can be STALE right after a DPI-boundary monitor
          // move, so the same helper is also re-run every JuxCaptureTick and
          // self-heals once the DSF settles — see ApplyCaptureScaleOverride.
          // (The helper no-ops when the desired override is unchanged; the
          // resize fast burst was already granted above regardless.)
          ApplyCaptureScaleOverride(entry, view);
          if (::getenv("OPENJFX_SKIA_WEBDPI_DIAG")) {
            VLOG(1) << "[webdpi] SetOffscreenSize logical=" << w << "x" << h
                      << " jfxScale=" << scale
                      << " override=" << entry.applied_capture_override;
          }
        }
        // Viz-capturer path: nudge a refresh so a pure scale/size change
        // delivers a frame promptly even if the page produced no damage yet.
        if (entry.video_consumer) {
          entry.video_consumer->RequestRefreshFrame();
        }
      },
      handle, width, height, scale));
  if (!posted) {
    LOG(ERROR) << "JuxSetOffscreenSize: PostToBrowserThread failed";
  }
}

// =========================================================================
// Off-screen input injection
// =========================================================================

namespace {

// Returns the RenderWidgetHost for a handle, or nullptr. UI thread only.
content::RenderWidgetHost* GetWidgetHost(JuxWebContentsHandle handle) {
  auto it = g_web_contents_map->find(handle);
  if (it == g_web_contents_map->end() || !it->second.web_contents) {
    return nullptr;
  }
  content::RenderWidgetHostView* view =
      it->second.web_contents->GetRenderWidgetHostView();
  return view ? view->GetRenderWidgetHost() : nullptr;
}

// Returns the open OSR popup's RenderWidgetHost, or nullptr. UI thread only.
// Re-queried live every call — never a cached raw_ptr, which could dangle if
// the popup closed since the event was queued.
content::RenderWidgetHost* GetPopupWidgetHost(JuxWebContentsHandle handle) {
  auto it = g_web_contents_map->find(handle);
  if (it == g_web_contents_map->end() || !it->second.web_contents) {
    return nullptr;
  }
  std::vector<content::RenderWidgetHostView*> popups =
      it->second.web_contents->GetPopupWidgets();
  return popups.empty() ? nullptr : popups.front()->GetRenderWidgetHost();
}

blink::WebPointerProperties::Button JuxButtonToBlink(int button) {
  switch (button) {
    case 1:  return blink::WebPointerProperties::Button::kMiddle;
    case 2:  return blink::WebPointerProperties::Button::kRight;
    default: return blink::WebPointerProperties::Button::kLeft;
  }
}

// For a mouse MOVE while a button is held (a drag), the WebMouseEvent must carry
// the held button in its `button` field — not just the *ButtonDown modifier bit.
// Chromium's own synthetic-drag injection (DevTools Input.dispatchMouseEvent)
// sets both; Blink's EventHandler keys drag/selection extension and scrollbar
// thumb tracking off event.button() being the pressed button, so a move with
// kNoButton is treated as a plain hover and the drag never continues. Resolve
// the held button from the Blink modifier mask (kLeftButtonDown=1<<6,
// kMiddleButtonDown=1<<7, kRightButtonDown=1<<8), preferring left.
blink::WebPointerProperties::Button JuxHeldButtonFromModifiers(int modifiers) {
  if (modifiers & (1 << 6)) return blink::WebPointerProperties::Button::kLeft;
  if (modifiers & (1 << 7)) return blink::WebPointerProperties::Button::kMiddle;
  if (modifiers & (1 << 8)) return blink::WebPointerProperties::Button::kRight;
  return blink::WebPointerProperties::Button::kNoButton;
}

// Ends an in-progress wheel scroll by sending a synthetic zero-delta
// kPhaseEnded wheel at the last position, which makes the queue emit a
// GestureScrollEnd and release the latch. Fired by the per-entry idle timer
// 500 ms after the last wheel event (mirrors MouseWheelPhaseHandler). Runs on
// the UI thread.
void JuxEndWheelScroll(JuxWebContentsHandle handle) {
  auto it = g_web_contents_map->find(handle);
  if (it == g_web_contents_map->end()) return;
  WebContentsEntry& entry = it->second;
  if (!entry.wheel_scrolling) return;
  entry.wheel_scrolling = false;
  content::RenderWidgetHost* rwh = GetWidgetHost(handle);
  if (!rwh) return;
  blink::WebMouseWheelEvent ev(blink::WebInputEvent::Type::kMouseWheel,
                               entry.last_wheel_modifiers,
                               base::TimeTicks::Now());
  ev.SetPositionInWidget(entry.last_wheel_pos.x(), entry.last_wheel_pos.y());
  ev.SetPositionInScreen(entry.last_wheel_pos.x(), entry.last_wheel_pos.y());
  ev.delta_x = 0;
  ev.delta_y = 0;
  ev.has_synthetic_phase = true;
  ev.phase = blink::WebMouseWheelEvent::kPhaseEnded;
  rwh->ForwardWheelEvent(ev);
}

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
// When a print preview is open it is composited CENTERED (inset) over the dimmed
// page, so node-local input must be translated into the preview's space. Returns
// the (dx,dy) to SUBTRACT from node coords; {0,0} if sizes are unavailable.
void PreviewInputOffset(JuxWebContentsHandle initiator, float* dx, float* dy) {
  *dx = 0;
  *dy = 0;
  auto a = g_web_contents_map->find(initiator);
  auto b = g_web_contents_map->find(g_print_preview_handle);
  if (a == g_web_contents_map->end() || b == g_web_contents_map->end()) return;
  auto* av = a->second.web_contents
                 ? a->second.web_contents->GetRenderWidgetHostView()
                 : nullptr;
  auto* bv = b->second.web_contents
                 ? b->second.web_contents->GetRenderWidgetHostView()
                 : nullptr;
  if (!av || !bv) return;
  const gfx::Size as = av->GetViewBounds().size();
  const gfx::Size bs = bv->GetViewBounds().size();
  *dx = (as.width() - bs.width()) / 2.0f;
  *dy = (as.height() - bs.height()) / 2.0f;
}
#endif

}  // namespace

extern "C" JUX_EXPORT void JuxSendMouseEvent(JuxWebContentsHandle handle,
                                             int type, float x, float y,
                                             int button, int click_count,
                                             int modifiers) {
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, int type, float x, float y, int button,
         int click_count, int modifiers) {
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
        // While the print preview is open, route input to it — translated into
        // the (centered/inset) preview's coordinate space.
        if (g_print_preview_handle != 0) {
          float dx, dy;
          PreviewInputOffset(handle, &dx, &dy);
          x -= dx;
          y -= dy;
          // A Skia-rendered <select> drop-down is open over the preview: route
          // pointer input to it and don't forward to the page behind. (x,y) are
          // already in preview-local DIP here.
          if (g_preview_dropdown) {
            if (type == 0) {  // move → hover highlight
              if (g_preview_dropdown->SetHover(g_preview_dropdown->RowAt(x, y))) {
                RenderAndPublishPreviewDropdown();
              }
              return;
            }
            if (type == 1) {  // press
              if (g_preview_dropdown->Contains(x, y)) {
                int opt = g_preview_dropdown->OptionIndexForRow(
                    g_preview_dropdown->RowAt(x, y));
                if (opt >= 0) {
                  DismissPreviewDropdown(/*accept=*/true, opt);
                }
                return;  // consume (selected; header/disabled keeps it open)
              }
              if (g_preview_dropdown->AnchorContains(x, y)) {
                DismissPreviewDropdown(/*accept=*/false, -1);  // toggle off
                return;
              }
              // Press elsewhere: close this menu, then let the click THROUGH so a
              // click on ANOTHER <select> opens that one (and clicks elsewhere
              // behave normally). Fall through — do NOT return.
              DismissPreviewDropdown(/*accept=*/false, -1);
            } else if (type == 2) {  // release — swallow while the menu owns input
              return;
            }
          }
          handle = g_print_preview_handle;
          // THE FIX: the internal preview never gets JuxNotifyFocus (that's how a
          // normal WebView's frame is focused on click — web_contents->Focus()),
          // and Blink will NOT open a <select> page-popup for an UNFOCUSED frame.
          // Focus the preview on mouse-down so its dropdowns open exactly like a
          // normal page's. (Cheap; idempotent once focused.)
          if (type == 1) {
            auto pit = g_web_contents_map->find(g_print_preview_handle);
            if (pit != g_web_contents_map->end() && pit->second.web_contents) {
              pit->second.web_contents->Focus();
            }
          }
          // skia-fx DIAGNOSTIC (temporary, capped): confirm down/up clicks are
          // forwarded to the preview and at what translated coords.
          if (type != 0) {
            static int dbg = 0;
            if (dbg < 30) {
              ++dbg;
              VLOG(1) << "[preview-input] mouse type=" << type
                        << " -> preview at (" << x << "," << y << ") off=("
                        << dx << "," << dy << ")";
            }
          }
        }
#endif
        content::RenderWidgetHost* rwh = GetWidgetHost(handle);
        if (!rwh) return;
        blink::WebInputEvent::Type wt;
        switch (type) {
          case 1:  wt = blink::WebInputEvent::Type::kMouseDown; break;
          case 2:  wt = blink::WebInputEvent::Type::kMouseUp; break;
          default: wt = blink::WebInputEvent::Type::kMouseMove; break;
        }
        // On a MOVE, carry the held button (from the modifier mask) so Blink
        // treats it as a drag (text selection, scrollbar thumb tracking) rather
        // than a hover. On down/up the explicit button is the one that changed.
        blink::WebPointerProperties::Button btn =
            (type == 0) ? JuxHeldButtonFromModifiers(modifiers)
                        : JuxButtonToBlink(button);
        blink::WebMouseEvent ev(
            wt, gfx::PointF(x, y), gfx::PointF(x, y),
            btn, click_count, modifiers, base::TimeTicks::Now());
        rwh->ForwardMouseEvent(ev);
      },
      handle, type, x, y, button, click_count, modifiers));
}

// Forwards a synthetic mouse event to the open OSR popup's RenderWidgetHost.
// (x, y) are popup-local DIP coords. No-op if no popup is open.
extern "C" JUX_EXPORT void JuxSendPopupMouseEvent(JuxWebContentsHandle handle,
                                                  int type, float x, float y,
                                                  int button, int click_count,
                                                  int modifiers) {
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, int type, float x, float y, int button,
         int click_count, int modifiers) {
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
        // While a print preview is open, the open popup is the PREVIEW's own
        // dropdown (<select>/datalist), so target the preview's popup widget.
        if (g_print_preview_handle != 0) handle = g_print_preview_handle;
#endif
        content::RenderWidgetHost* rwh = GetPopupWidgetHost(handle);
        if (!rwh) {
          return;
        }
        blink::WebInputEvent::Type wt;
        switch (type) {
          case 1:  wt = blink::WebInputEvent::Type::kMouseDown; break;
          case 2:  wt = blink::WebInputEvent::Type::kMouseUp; break;
          default: wt = blink::WebInputEvent::Type::kMouseMove; break;
        }
        blink::WebPointerProperties::Button btn =
            (type == 0) ? JuxHeldButtonFromModifiers(modifiers)
                        : JuxButtonToBlink(button);
        blink::WebMouseEvent ev(
            wt, gfx::PointF(x, y), gfx::PointF(x, y),
            btn, click_count, modifiers, base::TimeTicks::Now());
        rwh->ForwardMouseEvent(ev);
      },
      handle, type, x, y, button, click_count, modifiers));
}

// Forwards a synthetic wheel event to the open OSR popup's RenderWidgetHost so a
// long <select>/datalist list scrolls. (x, y) are popup-local DIP. No-op if no
// popup is open. Each tick is a self-contained kPhaseBegan wheel (no latch/timer
// like the main view): the popup is short-lived and a listbox scrolls per tick.
extern "C" JUX_EXPORT void JuxSendPopupWheelEvent(JuxWebContentsHandle handle,
                                                  float x, float y, float delta_x,
                                                  float delta_y, int modifiers) {
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, float x, float y, float dx, float dy,
         int modifiers) {
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
        // While a print preview is open, the open popup is the PREVIEW's own
        // dropdown (<select>/datalist), so target the preview's popup widget.
        if (g_print_preview_handle != 0) handle = g_print_preview_handle;
#endif
        content::RenderWidgetHost* rwh = GetPopupWidgetHost(handle);
        if (!rwh) return;
        blink::WebMouseWheelEvent ev(
            blink::WebInputEvent::Type::kMouseWheel, modifiers,
            base::TimeTicks::Now());
        ev.SetPositionInWidget(x, y);
        ev.SetPositionInScreen(x, y);
        ev.delta_x = dx;
        ev.delta_y = dy;
        ev.wheel_ticks_x = dx / 120.0f;
        ev.wheel_ticks_y = dy / 120.0f;
        ev.has_synthetic_phase = true;
        ev.phase = blink::WebMouseWheelEvent::kPhaseBegan;
        rwh->ForwardWheelEvent(ev);
      },
      handle, x, y, delta_x, delta_y, modifiers));
}

// Forwards a synthetic key event to the open OSR popup's RenderWidgetHost
// (arrow/Enter/Esc/type-ahead). No-op if no popup is open. Mirrors
// JuxSendKeyEvent but targets the popup widget.
extern "C" JUX_EXPORT void JuxSendPopupKeyEvent(JuxWebContentsHandle handle,
                                                int type, int windows_key_code,
                                                int native_key_code, int modifiers,
                                                const char* text) {
  std::u16string text16 = base::UTF8ToUTF16(text ? text : "");
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, int type, int wkc, int nkc, int modifiers,
         std::u16string text16) {
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
        // While a print preview is open, the open popup is the PREVIEW's own
        // dropdown (<select>/datalist), so target the preview's popup widget.
        if (g_print_preview_handle != 0) handle = g_print_preview_handle;
#endif
        content::RenderWidgetHost* rwh = GetPopupWidgetHost(handle);
        if (!rwh) return;
        blink::WebInputEvent::Type wt;
        switch (type) {
          case 1:  wt = blink::WebInputEvent::Type::kKeyUp; break;
          case 2:  wt = blink::WebInputEvent::Type::kChar; break;
          default: wt = blink::WebInputEvent::Type::kRawKeyDown; break;
        }
        blink::WebKeyboardEvent ev(wt, modifiers, base::TimeTicks::Now());
        ev.windows_key_code = wkc;
        ev.native_key_code = nkc;
        const size_t n = std::min<size_t>(
            text16.size(), blink::WebKeyboardEvent::kTextLengthCap - 1);
        for (size_t i = 0; i < n; ++i) {
          ev.text[i] = text16[i];
          ev.unmodified_text[i] = text16[i];
        }
        rwh->ForwardKeyboardEvent(input::NativeWebKeyboardEvent(ev, nullptr));
      },
      handle, type, windows_key_code, native_key_code, modifiers,
      std::move(text16)));
}

extern "C" JUX_EXPORT void JuxSendWheelEvent(JuxWebContentsHandle handle,
                                             float x, float y, float delta_x,
                                             float delta_y, int modifiers) {
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, float x, float y, float dx, float dy,
         int modifiers) {
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
        if (g_print_preview_handle != 0) {
          float odx, ody;
          PreviewInputOffset(handle, &odx, &ody);
          x -= odx;
          y -= ody;
          // Scroll while a preview dropdown is open: scroll the list when the
          // pointer is over it, otherwise dismiss it (scrolling the page behind an
          // open menu looks broken). Consume the wheel either way.
          if (g_preview_dropdown) {
            if (g_preview_dropdown->Contains(x, y)) {
              if (g_preview_dropdown->ScrollBy(-dy)) {
                RenderAndPublishPreviewDropdown();
              }
            } else {
              DismissPreviewDropdown(/*accept=*/false, -1);
            }
            return;
          }
          handle = g_print_preview_handle;
        }
#endif
        auto it = g_web_contents_map->find(handle);
        if (it == g_web_contents_map->end()) return;
        WebContentsEntry& entry = it->second;
        content::RenderWidgetHost* rwh = GetWidgetHost(handle);
        if (!rwh) return;
        blink::WebMouseWheelEvent ev(
            blink::WebInputEvent::Type::kMouseWheel, modifiers,
            base::TimeTicks::Now());
        ev.SetPositionInWidget(x, y);
        ev.SetPositionInScreen(x, y);
        ev.delta_x = dx;
        ev.delta_y = dy;
        ev.wheel_ticks_x = dx / 120.0f;
        ev.wheel_ticks_y = dy / 120.0f;
        // Phase latching: the first wheel of a sequence is kPhaseBegan (the
        // queue hit-tests + latches the scroll to the element under the cursor);
        // subsequent ones are kPhaseChanged (keep scrolling the latched element).
        // has_synthetic_phase marks these browser-synthesized, like the platform
        // MouseWheelPhaseHandler we're standing in for (we ForwardWheelEvent
        // directly, bypassing that handler). The idle timer below ends the
        // sequence so the next scroll re-hit-tests.
        ev.has_synthetic_phase = true;
        ev.phase = entry.wheel_scrolling
                       ? blink::WebMouseWheelEvent::kPhaseChanged
                       : blink::WebMouseWheelEvent::kPhaseBegan;
        entry.wheel_scrolling = true;
        entry.last_wheel_pos = gfx::PointF(x, y);
        entry.last_wheel_modifiers = modifiers;
        rwh->ForwardWheelEvent(ev);

        // (Re)arm the 500 ms idle timer that releases the latch (matches
        // kDefaultMouseWheelLatchingTransaction). Continuous scrolling keeps
        // pushing it out; 500 ms after the last tick a synthetic kPhaseEnded
        // fires (JuxEndWheelScroll).
        if (!entry.wheel_end_timer) {
          entry.wheel_end_timer = std::make_unique<base::OneShotTimer>();
        }
        entry.wheel_end_timer->Start(
            FROM_HERE, base::Milliseconds(500),
            base::BindOnce(&JuxEndWheelScroll, handle));
      },
      handle, x, y, delta_x, delta_y, modifiers));
}

extern "C" JUX_EXPORT void JuxSendKeyEvent(JuxWebContentsHandle handle,
                                           int type, int windows_key_code,
                                           int native_key_code, int modifiers,
                                           const char* text) {
  std::u16string text16 = base::UTF8ToUTF16(text ? text : "");
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, int type, int wkc, int nkc, int modifiers,
         std::u16string text16) {
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
        // Ctrl+P opens the print preview. Chrome handles Ctrl+P as a browser
        // accelerator (browser UI) — Blink itself ignores it — so with no chrome
        // UI we drive it browser-side here. type 1=keyUp, 2=char; trigger on the
        // key-down. Only when no preview is already open.
        if (g_print_preview_handle == 0 && type != 1 && type != 2 &&
            (modifiers & blink::WebInputEvent::kControlKey) && wkc == 'P') {
          auto it = g_web_contents_map->find(handle);
          if (it != g_web_contents_map->end() && it->second.web_contents) {
            content::RenderFrameHost* rfh =
                it->second.web_contents->GetPrimaryMainFrame();
            auto* pvm = printing::PrintViewManager::FromWebContents(
                it->second.web_contents.get());
            VLOG(1) << "[print-preview] Ctrl+P → PrintPreviewNow (pvm="
                      << pvm << ", rfh=" << rfh << ")";
            if (pvm && rfh) {
              pvm->PrintPreviewNow(rfh, /*has_selection=*/false);
            }
          }
          return;  // consume Ctrl+P (don't forward to the page)
        }
        // Escape closes an open preview (releases the surface takeover so the
        // page returns) — important as an escape hatch if the preview fails.
        if (g_print_preview_handle != 0 && type != 1 && wkc == 0x1B /*VK_ESCAPE*/) {
          auto it = g_web_contents_map->find(g_print_preview_handle);
          if (it != g_web_contents_map->end() && it->second.web_contents) {
            jux::ClosePrintPreviewWebContents(it->second.web_contents.get());
          } else {
            g_print_preview_handle = 0;  // fallback: just release the takeover
          }
          return;
        }
        if (g_print_preview_handle != 0) handle = g_print_preview_handle;
#endif
        content::RenderWidgetHost* rwh = GetWidgetHost(handle);
        if (!rwh) return;
        blink::WebInputEvent::Type wt;
        switch (type) {
          case 1:  wt = blink::WebInputEvent::Type::kKeyUp; break;
          case 2:  wt = blink::WebInputEvent::Type::kChar; break;
          default: wt = blink::WebInputEvent::Type::kRawKeyDown; break;
        }
        blink::WebKeyboardEvent ev(wt, modifiers, base::TimeTicks::Now());
        ev.windows_key_code = wkc;
        ev.native_key_code = nkc;
        // Copy up to kTextLengthCap-1 UTF-16 units into text/unmodified_text.
        const size_t n = std::min<size_t>(
            text16.size(), blink::WebKeyboardEvent::kTextLengthCap - 1);
        for (size_t i = 0; i < n; ++i) {
          ev.text[i] = text16[i];
          ev.unmodified_text[i] = text16[i];
        }
        rwh->ForwardKeyboardEvent(input::NativeWebKeyboardEvent(ev, nullptr));
      },
      handle, type, windows_key_code, native_key_code, modifiers,
      std::move(text16)));
}

extern "C" JUX_EXPORT void JuxSendFocusEvent(JuxWebContentsHandle handle,
                                             int focused) {
  JuxNotifyFocus(handle, focused);
}

// =========================================================================
// DOM manipulation — Phase 3 (Mojo → renderer)
//
// Every API below posts to the UI thread, fetches the JuxDomHandler
// remote for the given WebContents (binding lazily the first time),
// and fires off the matching Mojo call. All calls are fire-and-forget
// except JuxRequestDomTree which takes a reply callback and turns each
// returned DomNode into on_dom_element/on_dom_text calls followed by
// on_dom_tree_ready.
// =========================================================================

namespace {

// Helper: posts a task to the UI thread that runs `fn(remote)` with the
// DOM handler remote for the given handle. If the handle is unknown or
// the pipe isn't ready, the task is silently skipped (logs once).
template <typename Fn>
void RunOnDomHandler(JuxWebContentsHandle handle, Fn fn) {
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle h, Fn f) {
        auto* remote = GetDomHandler(h);
        if (!remote || !remote->is_bound()) {
          LOG(WARNING) << "DOM handler not bound for handle=" << h;
          return;
        }
        f(remote->get());
      },
      handle, std::move(fn)));
}

}  // namespace

extern "C" JUX_EXPORT void JuxCreateElement(JuxWebContentsHandle handle,
                                              int64_t node_id,
                                              const char* tag) {
  std::string tag_str(tag ? tag : "");
  RunOnDomHandler(handle, [node_id, tag = std::move(tag_str)](
                              jux::mojom::JuxDomHandler* h) {
    h->CreateElement(node_id, tag);
  });
}

extern "C" JUX_EXPORT void JuxRemoveElement(JuxWebContentsHandle handle,
                                              int64_t node_id) {
  RunOnDomHandler(handle, [node_id](jux::mojom::JuxDomHandler* h) {
    h->RemoveElement(node_id);
  });
}

extern "C" JUX_EXPORT void JuxSetAttribute(JuxWebContentsHandle handle,
                                             int64_t node_id,
                                             const char* name,
                                             const char* value) {
  std::string n(name ? name : ""), v(value ? value : "");
  RunOnDomHandler(handle, [node_id, n = std::move(n), v = std::move(v)](
                              jux::mojom::JuxDomHandler* h) {
    h->SetAttribute(node_id, n, v);
  });
}

extern "C" JUX_EXPORT void JuxRemoveAttribute(JuxWebContentsHandle handle,
                                                int64_t node_id,
                                                const char* name) {
  std::string n(name ? name : "");
  RunOnDomHandler(handle, [node_id, n = std::move(n)](
                              jux::mojom::JuxDomHandler* h) {
    h->RemoveAttribute(node_id, n);
  });
}

extern "C" JUX_EXPORT void JuxSelectPopupResponse(JuxWebContentsHandle handle,
                                                  uint32_t popup_id,
                                                  const int32_t* indices,
                                                  uint32_t count) {
  std::vector<int32_t> idx;
  idx.reserve(count);
  for (uint32_t i = 0; i < count; ++i) {
    idx.push_back(indices[i]);
  }
  RunOnDomHandler(handle, [popup_id, idx = std::move(idx)](
                              jux::mojom::JuxDomHandler* h) {
    h->SelectPopupResponse(popup_id, idx);
  });
}

extern "C" JUX_EXPORT void JuxColorChooserResponse(JuxWebContentsHandle handle,
                                                   uint32_t chooser_id,
                                                   bool chosen, uint32_t rgba) {
  RunOnDomHandler(handle, [chooser_id, chosen, rgba](
                              jux::mojom::JuxDomHandler* h) {
    h->ColorChooserResponse(chooser_id, chosen, rgba);
  });
}

// Hands the parsed native paths to the pending chooser's delegate. UI thread.
static void DeliverFileChooserOnUI(JuxWebContentsHandle handle,
                                   uint32_t chooser_id,
                                   std::vector<base::FilePath> paths) {
  auto it = g_web_contents_map->find(handle);
  if (it == g_web_contents_map->end() || !it->second.delegate) {
    return;
  }
  it->second.delegate->RespondFileChooser(chooser_id, std::move(paths));
}

extern "C" JUX_EXPORT void JuxFileChooserResponse(JuxWebContentsHandle handle,
                                                  uint32_t chooser_id,
                                                  uint32_t count,
                                                  const char* temp_path) {
  std::string temp(temp_path ? temp_path : "");
  if (count == 0 || temp.empty()) {
    // Cancel — no file I/O, answer directly on the UI thread.
    PostToBrowserThread(base::BindOnce(&DeliverFileChooserOnUI, handle,
                                       chooser_id,
                                       std::vector<base::FilePath>()));
    return;
  }
  // Blocking file I/O isn't allowed on the UI thread, so read the (tiny) staged
  // path list on the thread pool, then hop back to the UI thread to answer Blink.
  base::ThreadPool::PostTask(
      FROM_HERE, {base::MayBlock(), base::TaskPriority::USER_BLOCKING},
      base::BindOnce(
          [](JuxWebContentsHandle h, uint32_t cid, std::string t) {
            base::FilePath fp = base::FilePath::FromUTF8Unsafe(t);
            std::string contents;
            std::vector<base::FilePath> paths;
            if (base::ReadFileToString(fp, &contents)) {
              for (const auto& line :
                   base::SplitString(contents, "\n", base::TRIM_WHITESPACE,
                                     base::SPLIT_WANT_NONEMPTY)) {
                paths.push_back(base::FilePath::FromUTF8Unsafe(line));
              }
            }
            (void)base::DeleteFile(fp);  // best-effort cleanup of the staged list
            content::GetUIThreadTaskRunner({})->PostTask(
                FROM_HERE, base::BindOnce(&DeliverFileChooserOnUI, h, cid,
                                          std::move(paths)));
          },
          handle, chooser_id, std::move(temp)));
}

// Moves the hidden engine window's origin (keeping its current size) so Blink's
// native page-popups land over the on-screen WebView node. The window stays
// hidden — SetBounds on a never-shown widget does not present it.
extern "C" JUX_EXPORT void JuxSetScreenOrigin(JuxWebContentsHandle handle,
                                              double screen_x, double screen_y,
                                              double scale) {
  bool posted = PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, double sx, double sy, double scale) {
        auto it = g_web_contents_map->find(handle);
        if (it == g_web_contents_map->end() || !it->second.widget) {
          return;
        }
        auto& entry = it->second;
        gfx::Rect cur = entry.widget->GetWindowBoundsInScreen();
        int w = cur.width() > 0 ? cur.width() : 800;
        int h = cur.height() > 0 ? cur.height() : 600;
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
        // A real window move closes any open preview drop-down (its anchor would
        // otherwise drift). Guarded on an actual origin change so routine
        // same-position re-syncs don't spuriously dismiss it.
        if (g_preview_dropdown &&
            (cur.x() != static_cast<int>(sx) || cur.y() != static_cast<int>(sy))) {
          DismissPreviewDropdown(/*accept=*/false, -1);
        }
#endif
        // JavaFX screen coords are treated as DIP (views screen space). If a
        // HiDPI calibration gap shows up, this is where to adjust using `scale`.
        entry.widget->SetBounds(gfx::Rect(static_cast<int>(sx),
                                          static_cast<int>(sy), w, h));
        // `--v=1` logging to calibrate the JavaFX→views coordinate mapping.
        VLOG(1) << "[skia.webview.popup] origin -> (" << sx << "," << sy
                << ") scale=" << scale << " size=" << w << "x" << h
                << " -> " << entry.widget->GetWindowBoundsInScreen().ToString();
      },
      handle, screen_x, screen_y, scale));
  if (!posted) {
    LOG(ERROR) << "JuxSetScreenOrigin: PostToBrowserThread failed";
  }
}

// Tells the renderer which form popups the app overrides (select/color). For an
// overridden control the renderer intercepts the click and surfaces it to Java;
// otherwise Blink shows its own native page-popup.
extern "C" JUX_EXPORT void JuxSetPopupOverrides(JuxWebContentsHandle handle,
                                                bool select_overridden,
                                                bool color_overridden) {
  RunOnDomHandler(handle, [select_overridden, color_overridden](
                              jux::mojom::JuxDomHandler* h) {
    h->SetPopupOverrides(select_overridden, color_overridden);
  });
}

// Runs a Blink editor command on the focused frame: 0=Copy, 1=Cut, 2=Paste,
// 3=SelectAll, 4=Undo, 5=Redo, 6=Delete. WebContents routes each to the focused
// frame's editor and (for copy/cut/paste) the browser-process clipboard, so it
// works for both page selections and form fields. Drives the JavaFX context-menu
// editing items and the Ctrl+C/X/V/A/Z/Y shortcuts.
extern "C" JUX_EXPORT void JuxExecEditingCommand(JuxWebContentsHandle handle,
                                                 uint32_t cmd) {
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle handle, uint32_t cmd) {
        auto it = g_web_contents_map->find(handle);
        if (it == g_web_contents_map->end() || !it->second.web_contents) {
          return;
        }
        content::WebContents* wc = it->second.web_contents.get();
        switch (cmd) {
          case 0: wc->Copy(); break;
          case 1: wc->Cut(); break;
          case 2: wc->Paste(); break;
          case 3: wc->SelectAll(); break;
          case 4: wc->Undo(); break;
          case 5: wc->Redo(); break;
          case 6: wc->Delete(); break;
          default: break;
        }
      },
      handle, cmd));
}

// (JuxShowContextMenu retired: the context menu is now a JavaFX menu rendered in
// the foreground process directly from the ContextMenuModel — no engine-side
// views::MenuRunner, which couldn't receive input as a background process.)

extern "C" JUX_EXPORT void JuxSetTextContent(JuxWebContentsHandle handle,
                                               int64_t node_id,
                                               const char* text) {
  std::string t(text ? text : "");
  RunOnDomHandler(handle, [node_id, t = std::move(t)](
                              jux::mojom::JuxDomHandler* h) {
    h->SetTextContent(node_id, t);
  });
}

extern "C" JUX_EXPORT void JuxSetInnerHTML(JuxWebContentsHandle handle,
                                             int64_t node_id,
                                             const char* html) {
  std::string s(html ? html : "");
  RunOnDomHandler(handle, [node_id, s = std::move(s)](
                              jux::mojom::JuxDomHandler* h) {
    h->SetInnerHtml(node_id, s);
  });
}

extern "C" JUX_EXPORT void JuxAppendChild(JuxWebContentsHandle handle,
                                            int64_t parent_id,
                                            int64_t child_id) {
  RunOnDomHandler(handle, [parent_id, child_id](
                              jux::mojom::JuxDomHandler* h) {
    h->AppendChild(parent_id, child_id);
  });
}

extern "C" JUX_EXPORT void JuxInsertBefore(JuxWebContentsHandle handle,
                                             int64_t parent_id,
                                             int64_t child_id,
                                             int64_t ref_id) {
  RunOnDomHandler(handle, [parent_id, child_id, ref_id](
                              jux::mojom::JuxDomHandler* h) {
    h->InsertBefore(parent_id, child_id, ref_id);
  });
}

extern "C" JUX_EXPORT void JuxSetStyleProperty(JuxWebContentsHandle handle,
                                                  int64_t node_id,
                                                  const char* prop,
                                                  const char* value) {
  std::string p(prop ? prop : ""), v(value ? value : "");
  RunOnDomHandler(handle, [node_id, p = std::move(p), v = std::move(v)](
                              jux::mojom::JuxDomHandler* h) {
    h->SetStyleProperty(node_id, p, v);
  });
}

extern "C" JUX_EXPORT void JuxAddClass(JuxWebContentsHandle handle,
                                         int64_t node_id,
                                         const char* class_name) {
  std::string c(class_name ? class_name : "");
  RunOnDomHandler(handle, [node_id, c = std::move(c)](
                              jux::mojom::JuxDomHandler* h) {
    h->AddClass(node_id, c);
  });
}

extern "C" JUX_EXPORT void JuxRemoveClass(JuxWebContentsHandle handle,
                                            int64_t node_id,
                                            const char* class_name) {
  std::string c(class_name ? class_name : "");
  RunOnDomHandler(handle, [node_id, c = std::move(c)](
                              jux::mojom::JuxDomHandler* h) {
    h->RemoveClass(node_id, c);
  });
}

// Internal (non-exported, non-C-linkage) helper used by the
// chrome-subclass overlay WndProc to poke Blink's :hover state for
// the element registered at `code` in the hit-spot subscription.
// Same threading contract as the extern-C JuxAddClass above:
// RunOnDomHandler hops to the browser UI thread before calling into
// the Mojo remote.
namespace jux {
void SetDomHitSpotHovered(JuxWebContentsHandle handle,
                          uint32_t code,
                          bool hovered) {
  RunOnDomHandler(handle, [code, hovered](
                              jux::mojom::JuxDomHandler* h) {
    h->SetHitSpotHovered(code, hovered);
  });
}
}  // namespace jux

extern "C" JUX_EXPORT void JuxAddStylesheet(JuxWebContentsHandle handle,
                                              uint32_t id,
                                              const char* css,
                                              uint32_t css_len) {
  std::string css_str(css ? css : "", css_len);
  RunOnDomHandler(handle, [id, css_str = std::move(css_str)](
                              jux::mojom::JuxDomHandler* h) {
    h->AddStylesheet(id, css_str);
  });
}

extern "C" JUX_EXPORT void JuxRemoveStylesheet(JuxWebContentsHandle handle,
                                                 uint32_t id) {
  RunOnDomHandler(handle, [id](jux::mojom::JuxDomHandler* h) {
    h->RemoveStylesheet(id);
  });
}

extern "C" JUX_EXPORT void JuxAddEventListener(JuxWebContentsHandle handle,
                                                 int64_t node_id,
                                                 const char* event_type) {
  std::string et(event_type ? event_type : "");
  VLOG(1) << "[jux-dom] JuxAddEventListener: handle=" << handle
            << " node_id=" << node_id << " event=" << et;
  RunOnDomHandler(handle, [node_id, et](
                              jux::mojom::JuxDomHandler* h) {
    h->AddEventListener(node_id, et);
  });
}

extern "C" JUX_EXPORT void JuxRemoveEventListener(
    JuxWebContentsHandle handle,
    int64_t node_id,
    const char* event_type) {
  std::string et(event_type ? event_type : "");
  RunOnDomHandler(handle, [node_id, et = std::move(et)](
                              jux::mojom::JuxDomHandler* h) {
    h->RemoveEventListener(node_id, et);
  });
}

// Pushes the hit-spot subscription to the renderer. Copies the buffer
// into an owned vector before posting so the caller can free its copy
// the moment this returns.
extern "C" JUX_EXPORT void JuxSetHitSpotNodes(JuxWebContentsHandle handle,
                                                const JuxHitSpotNode* nodes,
                                                uint32_t count) {
  std::vector<jux::mojom::HitSpotNodePtr> owned;
  owned.reserve(count);
  for (uint32_t i = 0; i < count; ++i) {
    auto p = jux::mojom::HitSpotNode::New();
    p->code = nodes[i].code;
    p->node_id = nodes[i].node_id;
    owned.push_back(std::move(p));
  }
  RunOnDomHandler(handle, [owned = std::move(owned)](
                              jux::mojom::JuxDomHandler* h) mutable {
    h->SetHitSpotNodes(std::move(owned));
  });
}

// Walks the DOM tree on the renderer and translates each returned node
// into an on_dom_element / on_dom_text callback, followed by
// on_dom_tree_ready. The callbacks are set via JuxSetCallbacks and
// eventually write DOM_ELEMENT / DOM_TEXT / DOM_TREE_READY events to
// the Java-visible ring buffer.
extern "C" JUX_EXPORT void JuxBindDomPipe(JuxWebContentsHandle handle) {
  // Binding-only touch: GetDomHandler establishes the handler remote AND the
  // renderer->browser client (which arms the renderer's document listeners —
  // EnsureDocListeners requires a bound client). No tree request, so nothing
  // is surfaced to Java — safe for callback-detached pages like the
  // off-screen print preview, whose engine-drawn <select> dropdown depends
  // on the renderer's capture-phase mousedown interception.
  VLOG(1) << "[jux-dom] JuxBindDomPipe: handle=" << handle;
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle h) {
        auto* remote = GetDomHandler(h);
        if (!remote || !remote->is_bound()) {
          LOG(WARNING) << "[jux-dom] JuxBindDomPipe: DOM handler not bound (h="
                       << h << ")";
        }
      },
      handle));
}

extern "C" JUX_EXPORT void JuxRequestDomTree(JuxWebContentsHandle handle) {
  VLOG(1) << "[jux-dom] JuxRequestDomTree: handle=" << handle;
  PostToBrowserThread(base::BindOnce(
      [](JuxWebContentsHandle h) {
        auto* remote = GetDomHandler(h);
        if (!remote || !remote->is_bound()) {
          LOG(WARNING) << "[jux-dom] JuxRequestDomTree: DOM handler not "
                       << "bound (h=" << h << ")";
          return;
        }
        VLOG(1) << "[jux-dom] JuxRequestDomTree: calling remote for h=" << h;
        (*remote)->RequestDomTree(base::BindOnce(
            [](JuxWebContentsHandle h,
               std::vector<jux::mojom::DomNodePtr> nodes) {
              VLOG(1) << "[jux-dom] RequestDomTree reply: "
                        << nodes.size() << " nodes for h=" << h
                        << " — on_dom_element="
                        << (g_callbacks.on_dom_element ? "set" : "NULL")
                        << " on_dom_text="
                        << (g_callbacks.on_dom_text ? "set" : "NULL")
                        << " on_dom_tree_ready="
                        << (g_callbacks.on_dom_tree_ready ? "set" : "NULL");
              size_t count_elem = 0, count_text = 0, count_null = 0;
              for (const auto& n : nodes) {
                if (!n) { count_null++; continue; }
                if (n->is_text) {
                  count_text++;
                  if (g_callbacks.on_dom_text) {
                    g_callbacks.on_dom_text(
                        h, n->node_id, n->parent_id,
                        n->text.c_str(),
                        static_cast<uint32_t>(n->text.size()));
                  }
                } else {
                  count_elem++;
                  if (g_callbacks.on_dom_element) {
                    g_callbacks.on_dom_element(
                        h, n->node_id, n->parent_id,
                        n->tag.c_str(),
                        static_cast<uint32_t>(n->tag.size()),
                        n->id_attr.c_str(),
                        static_cast<uint32_t>(n->id_attr.size()),
                        n->class_attr.c_str(),
                        static_cast<uint32_t>(n->class_attr.size()));
                  }
                }
              }
              VLOG(1) << "[jux-dom] RequestDomTree loop done — "
                        << "elements=" << count_elem
                        << " texts=" << count_text
                        << " null=" << count_null
                        << " — about to fire on_dom_tree_ready";
              if (g_callbacks.on_dom_tree_ready) {
                g_callbacks.on_dom_tree_ready(h);
              }
              // Fire DOC_READY now — AFTER the Java side has seen every
              // DOM_ELEMENT / DOM_TEXT / DOM_TREE_READY event. This is
              // the right moment for Java's DocumentEvent.READY: the
              // Document mirror is fully populated and ready to use.
              if (g_callbacks.on_load_status_changed) {
                g_callbacks.on_load_status_changed(h, 3);  // DOC_READY
              }
              VLOG(1) << "[jux-dom] RequestDomTree reply handler returning";
            },
            h));
      },
      handle));
}

// =========================================================================
// Callbacks
// =========================================================================

extern "C" JUX_EXPORT void JuxSetCallbacks(JuxCallbacks callbacks) {
  // Copy the struct — safe to call from any thread before use.
  g_callbacks = callbacks;
}

extern "C" JUX_EXPORT void JuxSetGpuMode(int mode) {
  // Must be called before JuxRunBrowser / JuxInit. The mode is applied
  // as command-line flags during JuxInit.
  g_gpu_mode = mode;
}
