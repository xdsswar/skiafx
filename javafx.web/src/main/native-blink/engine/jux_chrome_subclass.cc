// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// Implementation of the Windows custom-chrome subclass, following
// hittest.md (ported from F:\DEV\jux-toolkit).

#ifdef UNSAFE_BUFFERS_BUILD
#pragma allow_unsafe_buffers
#endif

#include "jux/jux_chrome_subclass.h"

#if BUILDFLAG(IS_WIN)

#include <windowsx.h>
#include <atomic>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "base/logging.h"
#include "jux/jux_engine_api.h"
#include "jux/jux_event_types.h"
#include "jux/jux_ipc.h"
#include "jux/jux_ring_buffer.h"

namespace jux {

// Defined in chromium/src/content/browser/renderer_host/delegated_frame_host.cc
// by the configureBuild patch. Drives the parent-side surface-activation
// deadline so a user drag-resize can wait briefly for the renderer to
// produce a frame at the new size, eliminating the visible gap between
// the new window edge and the last-painted content. Forward-declared
// here to avoid pulling Chromium content/browser headers into this TU.
void SetJuxResizeDeadlineFrames(uint32_t frames);

// Globals owned by jux_command_dispatch.cc — the subclass uses these
// to emit window-state events when WM_SIZE fires with a min/max/
// restore wParam. Null until CommandDispatcher::OnCreateWindow has
// initialized them.
extern EventWriter* g_callback_evt_writer;
extern ipc::SharedMemoryChannel* g_callback_channel;
extern JuxWebContentsHandle g_callback_web_contents;

// Forward declaration — defined in jux_engine_api.cc. Hops to the
// browser UI thread and forwards the hover state to Blink via the
// per-WebContents JuxDomHandler Mojo remote.
void SetDomHitSpotHovered(JuxWebContentsHandle handle,
                          uint32_t code,
                          bool hovered);

namespace {

// Property name under which each subclassed HWND stashes a pointer to
// its ChromeSubclassState. Per-HWND GetProp/SetProp keeps lookup O(1).
constexpr wchar_t kStatePropName[] = L"JuxChromeSubclassState";

// Unified window class for every per-hit-spot overlay (HTCAPTION /
// HTMAXBUTTON / HTMINBUTTON / HTCLOSE). Each overlay HWND stores its
// HT code via SetPropW; a single OverlayWndProc branches on that code.
//
// Why overlays at all: Windows dispatches WM_NCHITTEST to the DEEPEST
// HWND under the cursor. Chromium's compositor child fills the whole
// client area of the top-level window, so our parent subclass never
// sees WM_NCHITTEST for pixels over HTML content. Overlay HWNDs are
// sibling children of the top-level raised above the compositor — they
// intercept the hit-test first and return the real HT code for the
// button/caption they cover.
constexpr wchar_t kChromeOverlayClassName[] = L"JuxChromeOverlay";
bool g_chrome_overlay_class_registered = false;

// Per-overlay property name. Value is the HT code stored as an
// intptr_t cast to HANDLE.
constexpr wchar_t kOverlayHtCodeProp[] = L"JuxChromeOverlayHtCode";

// Per-overlay hover flag. Non-null = we've told Blink the element is
// hovered; null = not. Lets us post the SetHovered(true/false) Mojo
// call only on the enter/leave *edges* rather than once per mousemove
// tick. Value stored as an intptr_t (1 / 0) cast to HANDLE.
constexpr wchar_t kOverlayHoveredProp[] = L"JuxChromeOverlayHovered";

// Private window message used to trigger a deferred RaiseAllOverlays
// after Chromium's own restore/activate work has drained from the
// queue. Posted from WM_SIZE (SIZE_RESTORED / SIZE_MAXIMIZED) and
// WM_ACTIVATE. WM_APP range guarantees no collision with system or
// framework messages.
constexpr UINT kJuxDeferredRaiseMsg = WM_APP + 0x4A55;  // 'JU'

// Per-HWND subclass state. Mutation from the Java-driven command
// dispatch thread races with reads from the UI thread's WndProc, so
// all mutable fields sit behind `mu`.
struct ChromeSubclassState {
  HWND hwnd = nullptr;
  WNDPROC old_wndproc = nullptr;

  std::mutex mu;
  double title_bar_height_dip = 0.0;
  bool resizable = true;
  std::vector<HitSpotRect> hit_spots;

  // One overlay HWND per hit-spot rect, keyed by HT code. Vector per
  // code supports multiple drag regions (HTCAPTION); min/max/close
  // normally have exactly one overlay each. Updated on every
  // SetChromeHitSpots call to match the current rect list.
  std::unordered_map<uint32_t, std::vector<HWND>> overlays;

  // Solid fill used to paint the parent client area on resize, so the
  // strip exposed between the WebView child's old right/bottom edge
  // and the parent's new edge shows this color instead of the OS's
  // default white. Owned by the state — created lazily, recreated on
  // SetChromeBackgroundColor, destroyed in UninstallChromeSubclass.
  // Default initialised in InstallChromeSubclass.
  HBRUSH bg_brush = nullptr;
  COLORREF bg_color = 0;  // default applied in InstallChromeSubclass

  // True while the user is mid-drag-resize. Set on WM_ENTERSIZEMOVE,
  // cleared on WM_EXITSIZEMOVE. Read by IsChromeDragging() from the
  // browser UI thread so the bounds-change path can switch the
  // compositor's surface-deadline policy from "use default (0)" to
  // "wait N frames" — eliminating the visible gap between the new
  // window edge and the renderer's last-painted size during a fast
  // drag, without slowing programmatic resizes / first show.
  // std::atomic so the read from the UI thread is lock-free; writes
  // happen on the same UI thread but the atomic costs nothing here
  // and keeps the contract obvious.
  std::atomic<bool> dragging{false};
};

ChromeSubclassState* GetState(HWND hwnd) {
  return reinterpret_cast<ChromeSubclassState*>(
      ::GetPropW(hwnd, kStatePropName));
}

// ─── Unified per-hit-spot overlay ────────────────────────────────────

// Reads the HT code stashed on the overlay HWND at creation time.
uint32_t OverlayHtCode(HWND hwnd) {
  HANDLE h = ::GetPropW(hwnd, kOverlayHtCodeProp);
  return static_cast<uint32_t>(reinterpret_cast<intptr_t>(h));
}

// Reads the current hover-edge flag on an overlay HWND.
bool OverlayIsHovered(HWND hwnd) {
  return ::GetPropW(hwnd, kOverlayHoveredProp) != nullptr;
}

// Flips the hover-edge flag on an overlay HWND. Returns true if the
// flag actually changed (i.e. the caller should emit the SetHovered
// Mojo call); false if we were already in the requested state.
bool SetOverlayHovered(HWND hwnd, bool hovered) {
  bool was = OverlayIsHovered(hwnd);
  if (was == hovered) return false;
  if (hovered) {
    ::SetPropW(hwnd, kOverlayHoveredProp,
               reinterpret_cast<HANDLE>(static_cast<intptr_t>(1)));
  } else {
    ::RemovePropW(hwnd, kOverlayHoveredProp);
  }
  return true;
}

// Maps an overlay's HT code to the WM_SYSCOMMAND SC_* to post on click.
// Returns 0 for HTCAPTION / HTSYSMENU (those take a different path).
UINT ScFromHtCode(uint32_t ht, HWND parent) {
  switch (ht) {
    case HTMINBUTTON: return SC_MINIMIZE;
    case HTCLOSE:     return SC_CLOSE;
    case HTMAXBUTTON: return ::IsZoomed(parent) ? SC_RESTORE : SC_MAXIMIZE;
    default:          return 0;
  }
}

LRESULT CALLBACK OverlayWndProc(HWND hwnd,
                                UINT msg,
                                WPARAM wp,
                                LPARAM lp) {
  const uint32_t ht = OverlayHtCode(hwnd);

  switch (msg) {
    case WM_NCHITTEST: {
      // Every visible pixel of this overlay is the HT code it was
      // created for. For HTMAXBUTTON this arms the Win11 snap-layout
      // flyout; for HTCAPTION it tells DWM the region is caption;
      // HTMINBUTTON / HTCLOSE are returned purely so our own
      // WM_NCLBUTTON* handlers fire with the right wparam.
      //
      // Exception: HTCAPTION overlays eat the top resize band unless
      // we translate the topmost strip into HTTOP. The overlay sits
      // flush against the top of the client area (drag bar is
      // frameless), so cursors in the first few pixels would otherwise
      // trigger caption drag instead of vertical resize. Match the
      // resize-frame thickness the parent's HandleNcHitTest uses
      // (SM_CYFRAME + SM_CXPADDEDBORDER) so the band width is
      // consistent with the bottom/left/right resize borders.
      if (ht == HTCAPTION) {
        HWND parent = ::GetParent(hwnd);
        if (parent && !::IsZoomed(parent)) {
          UINT dpi = ::GetDpiForWindow(parent);
          if (dpi == 0) dpi = 96;
          int frame_px = ::GetSystemMetricsForDpi(SM_CYFRAME, dpi) +
                         ::GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
          POINT pt = {GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
          RECT overlay_rect;
          ::GetWindowRect(hwnd, &overlay_rect);
          // lp is screen coords for WM_NCHITTEST; only translate to
          // HTTOP when the cursor is in the top slice of this overlay
          // AND that slice sits at the client-area top edge. The
          // second guard stops a mid-window drag strip from stealing
          // resize behavior (only the top of the window is resizable
          // from the top edge).
          RECT client_rect;
          ::GetClientRect(parent, &client_rect);
          POINT client_tl = {client_rect.left, client_rect.top};
          ::ClientToScreen(parent, &client_tl);
          if (overlay_rect.top <= client_tl.y + 1 &&
              pt.y < overlay_rect.top + frame_px) {
            return HTTOP;
          }
        }
      }
      return ht != 0 ? static_cast<LRESULT>(ht) : HTCLIENT;
    }

    case WM_ERASEBKGND:
      return 1;

    case WM_PAINT: {
      PAINTSTRUCT ps;
      ::BeginPaint(hwnd, &ps);
      ::EndPaint(hwnd, &ps);
      return 0;
    }

    // Cursor shape. lparam low-word is the HT code of the hit area
    // just reported from WM_NCHITTEST. For HTTOP (the top resize band
    // we serve out of the HTCAPTION overlay), Windows would otherwise
    // not get a chance to load the IDC_SIZENS cursor because we
    // unconditionally forced IDC_ARROW — so the cursor stayed as an
    // arrow even over the resize band, and the user couldn't tell the
    // top edge was draggable. Map the resize codes to their matching
    // IDC_* cursors; anything else (HTCAPTION / HTMAXBUTTON / the
    // button overlays) keeps the arrow.
    case WM_SETCURSOR: {
      WORD hit = LOWORD(lp);
      LPCWSTR cursor = IDC_ARROW;
      switch (hit) {
        case HTTOP:
        case HTBOTTOM:
          cursor = IDC_SIZENS; break;
        case HTLEFT:
        case HTRIGHT:
          cursor = IDC_SIZEWE; break;
        case HTTOPLEFT:
        case HTBOTTOMRIGHT:
          cursor = IDC_SIZENWSE; break;
        case HTTOPRIGHT:
        case HTBOTTOMLEFT:
          cursor = IDC_SIZENESW; break;
        default: break;
      }
      ::SetCursor(::LoadCursorW(nullptr, cursor));
      return TRUE;
    }

    // Cursor moved over a button overlay (HTMAXBUTTON / HTMINBUTTON /
    // HTCLOSE). Two things to do:
    //
    //   1. Arm WM_NCMOUSELEAVE so we get a leave callback later.
    //   2. On the enter edge (first move after WM_NCMOUSELEAVE cleared
    //      the hover flag), ask Blink to mark the registered button
    //      element as :hover via Node::SetHovered(true). Driving the
    //      real :hover state machine makes the author's CSS
    //      `.foo:hover {}` rules match without any JS, custom class,
    //      or attribute selector — which is what the overlay
    //      otherwise blocks (it eats the cursor so Blink never
    //      hit-tests the button on its own).
    //
    // HTCAPTION is a drag strip with no button styling, so it skips
    // this path.
    case WM_NCMOUSEMOVE: {
      if (ht != HTMAXBUTTON && ht != HTMINBUTTON && ht != HTCLOSE) break;
      TRACKMOUSEEVENT tme = {};
      tme.cbSize = sizeof(tme);
      tme.dwFlags = TME_LEAVE | TME_NONCLIENT;
      tme.hwndTrack = hwnd;
      ::TrackMouseEvent(&tme);
      if (SetOverlayHovered(hwnd, true) && g_callback_web_contents) {
        SetDomHitSpotHovered(g_callback_web_contents, ht, true);
      }
      return 0;
    }

    // Cursor left the overlay rect. Clear Blink's :hover state so
    // the button's hover styling drops. Edge-triggered via the
    // hover-flag prop — guards against paired leave notifications.
    case WM_NCMOUSELEAVE: {
      if (ht != HTMAXBUTTON && ht != HTMINBUTTON && ht != HTCLOSE) break;
      if (SetOverlayHovered(hwnd, false) && g_callback_web_contents) {
        SetDomHitSpotHovered(g_callback_web_contents, ht, false);
      }
      return 0;
    }

    // HTCAPTION fires on DOWN via the canonical ReleaseCapture + SC_MOVE
    // idiom — the drag loop must start from the down edge. HTMAXBUTTON /
    // HTMINBUTTON / HTCLOSE are intentionally eaten on DOWN and fired on
    // UP (see WM_NCLBUTTONUP): posting SC_MAXIMIZE while Windows is still
    // tracking the overlay's NC-button-down state races with DefWindowProc
    // on Win11 and the command is silently dropped (snap layouts stop
    // arming, maximize stops toggling). Deferring to UP lets the click
    // sequence complete cleanly first.
    case WM_NCLBUTTONDOWN: {
      HWND parent = ::GetParent(hwnd);
      if (!parent) return 0;
      // wparam is the HT code the WM_NCHITTEST above returned for this
      // click. It may differ from the overlay's base `ht` — an HTCAPTION
      // overlay synthesises HTTOP in its top resize band so the user can
      // grab the top edge to resize. Dispatch on wparam, not on ht, so
      // the resize-edge case takes the SC_SIZE path instead of SC_MOVE.
      WORD hit = static_cast<WORD>(wp);
      bool is_resize_edge =
          (hit == HTTOP || hit == HTBOTTOM ||
           hit == HTLEFT || hit == HTRIGHT ||
           hit == HTTOPLEFT || hit == HTTOPRIGHT ||
           hit == HTBOTTOMLEFT || hit == HTBOTTOMRIGHT);
      if (is_resize_edge) {
        // Same ReleaseCapture + deferred PostMessage idiom as the
        // HTCAPTION drag path below. SC_SIZE's low nibble wants a
        // WMSZ_* direction code (1..8), NOT the HT* hit code (10..17)
        // — they happen to map sequentially (HTLEFT -> WMSZ_LEFT) so
        // the conversion is a single subtraction. Sending SC_SIZE |
        // HT* directly makes DefWindowProc drop the message because
        // the low nibble (0xC for HTTOP) isn't a valid WMSZ, so the
        // resize loop never starts.
        WORD wmsz = static_cast<WORD>(hit - HTLEFT + 1);
        ::ReleaseCapture();
        ::PostMessageW(parent, WM_SYSCOMMAND,
                       SC_SIZE | wmsz, lp);
        return 0;
      }
      if (ht == HTCAPTION) {
        // Why ReleaseCapture + PostMessage instead of
        // SendMessage(parent, WM_NCLBUTTONDOWN, HTCAPTION, lp):
        //
        // When the overlay receives WM_NCLBUTTONDOWN, Windows has
        // already given it implicit NC-button-down tracking. If we
        // SendMessage WM_NCLBUTTONDOWN to the parent, DefWindowProc's
        // SC_MOVE modal loop does SetCapture(parent) and consumes the
        // matching mouseup inside the loop — our overlay then never
        // sees WM_NCLBUTTONUP and is left in "button stuck down" state,
        // so the next user click never produces a fresh
        // WM_NCLBUTTONDOWN. Drag works exactly once.
        //
        // ReleaseCapture drops the overlay's implicit tracking *before*
        // the modal loop starts, and PostMessage defers the SYSCOMMAND
        // so this WndProc returns cleanly before DefWindowProc reenters
        // on the deferred message. SC_MOVE | HTCAPTION is the documented
        // WM_SYSCOMMAND for "begin titlebar drag at current cursor."
        ::ReleaseCapture();
        ::PostMessageW(parent, WM_SYSCOMMAND,
                       SC_MOVE | HTCAPTION, 0);
        return 0;
      }
      // Eat DOWN for button codes — we fire on UP to avoid racing with
      // Windows' own NC-button-down tracking.
      return 0;
    }

    // Button codes fire their WM_SYSCOMMAND here on UP. By UP time the
    // user has released the mouse and Windows has cleared its NC-button
    // tracking on the overlay, so SC_MAXIMIZE / SC_MINIMIZE / SC_CLOSE
    // land cleanly and the next flyout hover still arms. Matches the
    // pre-refactor behavior that worked in-tree.
    case WM_NCLBUTTONUP: {
      if (ht == HTMINBUTTON || ht == HTMAXBUTTON || ht == HTCLOSE) {
        HWND parent = ::GetParent(hwnd);
        if (!parent) return 0;
        UINT sc = ScFromHtCode(ht, parent);
        if (sc != 0) {
          VLOG(1) << "Overlay UP → WM_SYSCOMMAND SC_" << sc
                    << " (ht=" << ht << ", parent=" << parent << ")";
          ::SendMessageW(parent, WM_SYSCOMMAND, sc, 0);
        }
        return 0;
      }
      break;
    }

    // Double-click handling.
    //
    // HTCAPTION: toggle maximize/restore — the standard Windows
    // double-click-caption behavior. Posted via WM_SYSCOMMAND so the
    // state change round-trips through WM_SIZE → kWindowMaximized /
    // kWindowRestored → Java syncWindowState, keeping the Java
    // windowState property in lockstep with the native state.
    //
    // HTMINBUTTON / HTMAXBUTTON / HTCLOSE: eat the DBLCLK entirely.
    // With CS_DBLCLKS set, a fast two-click sequence on the overlay
    // delivers DOWN → UP → DBLCLK → UP. The first UP already fired the
    // authoritative SC_* in our WM_NCLBUTTONUP handler; acting on the
    // DBLCLK would issue a second SC_* and toggle twice (e.g. max →
    // restore on a double-click of the maximize button).
    case WM_NCLBUTTONDBLCLK: {
      HWND parent = ::GetParent(hwnd);
      if (!parent) return 0;
      if (ht == HTCAPTION) {
        ::PostMessageW(parent, WM_SYSCOMMAND,
                       ::IsZoomed(parent) ? SC_RESTORE : SC_MAXIMIZE, 0);
        return 0;
      }
      return 0;
    }

    // Right-click on caption/sysmenu → forward to parent so its
    // DefWindowProc opens the standard system menu.
    case WM_NCRBUTTONUP: {
      if (ht == HTCAPTION || ht == HTSYSMENU) {
        HWND parent = ::GetParent(hwnd);
        if (!parent) return 0;
        ::SendMessageW(parent, WM_NCRBUTTONUP, HTCAPTION, lp);
        return 0;
      }
      break;
    }
  }
  return ::DefWindowProcW(hwnd, msg, wp, lp);
}

// Registers the unified overlay window class on first use. UI-thread only.
void EnsureChromeOverlayClass() {
  if (g_chrome_overlay_class_registered) return;
  WNDCLASSEXW wc = {};
  wc.cbSize = sizeof(wc);
  wc.style = CS_HREDRAW | CS_VREDRAW | CS_DBLCLKS;
  wc.lpfnWndProc = &OverlayWndProc;
  wc.hInstance = ::GetModuleHandleW(nullptr);
  wc.hCursor = ::LoadCursorW(nullptr, IDC_ARROW);
  wc.lpszClassName = kChromeOverlayClassName;
  ::RegisterClassExW(&wc);
  g_chrome_overlay_class_registered = true;
}

// Creates or resizes a single overlay HWND to match the given rect.
// On create, stashes the HT code via SetPropW so OverlayWndProc knows
// which HT code to return from WM_NCHITTEST.
HWND CreateOrResizeOverlay(HWND parent,
                           uint32_t ht_code,
                           int x, int y, int w, int h,
                           HWND existing) {
  if (existing) {
    ::SetWindowPos(existing, HWND_TOP, x, y, w, h, SWP_NOACTIVATE);
    return existing;
  }
  HWND o = ::CreateWindowExW(
      WS_EX_NOACTIVATE,
      kChromeOverlayClassName, nullptr,
      WS_CHILD | WS_VISIBLE | WS_CLIPSIBLINGS,
      x, y, w, h,
      parent, nullptr,
      ::GetModuleHandleW(nullptr), nullptr);
  if (!o) {
    LOG(WARNING) << "CreateOrResizeOverlay: CreateWindowExW failed ht=" << ht_code;
    return nullptr;
  }
  ::SetPropW(o, kOverlayHtCodeProp,
             reinterpret_cast<HANDLE>(static_cast<intptr_t>(ht_code)));
  VLOG(1) << "ChromeOverlay created: hwnd=" << o
            << "  parent=" << parent
            << "  ht=" << ht_code
            << "  rect=(" << x << "," << y << "," << w << "," << h << ")";
  return o;
}

// Forward declaration — defined below. UpdateOverlays needs to tear
// down overlays that no longer have a matching rect, and the teardown
// path must also clear any lingering Blink :hover state on the
// associated DOM element.
void DestroyOverlay(HWND o, uint32_t code);

// Reconciles the `state->overlays` map against the incoming hit-spot
// list. Partitions the incoming rects by HT code, then for each code
// resizes existing overlays in order and creates/destroys as needed.
// Runs on the UI thread.
void UpdateOverlays(ChromeSubclassState* state,
                    const std::vector<HitSpotRect>& spots) {
  if (!state || !state->hwnd) return;
  EnsureChromeOverlayClass();

  UINT dpi = ::GetDpiForWindow(state->hwnd);
  if (dpi == 0) dpi = 96;
  const double scale = static_cast<double>(dpi) / 96.0;

  // Bucket the requested rects by code while converting DIP → px.
  // Overlays exist for HTCAPTION, HTMINBUTTON, HTMAXBUTTON, and
  // HTCLOSE — every region that the HTCAPTION overlay would otherwise
  // swallow needs its own sibling HWND raised above HTCAPTION so the
  // intended hit code reaches the OS:
  //   - HTCAPTION: drag strip; DefWindowProc's SC_MOVE picks it up.
  //   - HTMAXBUTTON: returning HTMAXBUTTON arms the Win11 snap flyout;
  //     overlay's WM_NCLBUTTONUP posts SC_MAXIMIZE/SC_RESTORE.
  //   - HTMINBUTTON / HTCLOSE: without dedicated overlays the HTCAPTION
  //     overlay (which spans the whole titlebar div) eats clicks on the
  //     min/close buttons, so the DOM `click` never fires and
  //     Window.setMinimizeControl / setCloseControl never get notified.
  //     The dedicated overlays return HTMINBUTTON/HTCLOSE from
  //     WM_NCHITTEST and post SC_MINIMIZE / SC_CLOSE on UP. SC_CLOSE
  //     routes through JuxWidgetDelegate::OnCloseRequested which fires
  //     kWindowCloseRequest to Java — preserving the requestClose()
  //     veto path. CSS :hover keeps working because the overlay drives
  //     Blink's :hover via SetDomHitSpotHovered (Mojo SetHovered),
  //     identical to the HTMAXBUTTON path.
  struct RectPx { int x, y, w, h; };
  std::unordered_map<uint32_t, std::vector<RectPx>> buckets;
  for (const auto& s : spots) {
    if (s.code != static_cast<uint32_t>(HTCAPTION) &&
        s.code != static_cast<uint32_t>(HTMINBUTTON) &&
        s.code != static_cast<uint32_t>(HTMAXBUTTON) &&
        s.code != static_cast<uint32_t>(HTCLOSE)) {
      continue;
    }
    RectPx r;
    r.x = static_cast<int>(s.x * scale);
    r.y = static_cast<int>(s.y * scale);
    r.w = static_cast<int>(s.w * scale);
    r.h = static_cast<int>(s.h * scale);
    if (r.w <= 0 || r.h <= 0) continue;
    buckets[s.code].push_back(r);
  }

  // Process codes in a fixed order so Z-order is deterministic:
  // HTCAPTION first (lowest), then the button overlays. Newly-created
  // / raised WS_CHILD windows go to HWND_TOP, so whichever code is
  // processed last ends up on top. The button overlays MUST sit above
  // HTCAPTION — if caption (which typically spans the whole titlebar)
  // ended up on top of a button, hover/click on the button would
  // hit-test as HTCAPTION and either drag the window or fail to fire
  // the button action. Within the button group, order doesn't matter
  // because the button rects don't overlap each other; HTMAXBUTTON
  // is processed last by convention so the snap-layout-critical
  // overlay is unambiguously on top.
  const uint32_t kOrderedCodes[] = {
      static_cast<uint32_t>(HTCAPTION),
      static_cast<uint32_t>(HTMINBUTTON),
      static_cast<uint32_t>(HTCLOSE),
      static_cast<uint32_t>(HTMAXBUTTON),
  };
  for (uint32_t code : kOrderedCodes) {
    auto it = buckets.find(code);
    if (it == buckets.end()) continue;
    const auto& rects = it->second;
    auto& existing = state->overlays[code];
    for (size_t i = 0; i < rects.size(); ++i) {
      HWND prev = (i < existing.size()) ? existing[i] : nullptr;
      HWND o = CreateOrResizeOverlay(state->hwnd, code,
                                     rects[i].x, rects[i].y,
                                     rects[i].w, rects[i].h,
                                     prev);
      if (i < existing.size()) {
        existing[i] = o;
      } else if (o) {
        existing.push_back(o);
      }
    }
    while (existing.size() > rects.size()) {
      HWND o = existing.back();
      existing.pop_back();
      DestroyOverlay(o, code);
    }
  }

  // Destroy overlays for codes no longer present in the incoming set.
  for (auto it = state->overlays.begin(); it != state->overlays.end(); ) {
    if (buckets.find(it->first) == buckets.end()) {
      for (HWND o : it->second) {
        DestroyOverlay(o, it->first);
      }
      it = state->overlays.erase(it);
    } else {
      ++it;
    }
  }
}

// Clears Blink's :hover state for an overlay HWND if it was marked
// hovered, then destroys the HWND. Without the pre-destroy clear,
// tearing down an overlay while the cursor was inside it would
// leave Blink stuck with the element flagged :hover forever — the
// author's hover styling would never drop.
void DestroyOverlay(HWND o, uint32_t code) {
  if (!o) return;
  if (OverlayIsHovered(o) && g_callback_web_contents) {
    SetDomHitSpotHovered(g_callback_web_contents, code, false);
  }
  ::DestroyWindow(o);
}

// Destroys every overlay HWND owned by the subclass state.
void DestroyAllOverlays(ChromeSubclassState* state) {
  if (!state) return;
  for (auto& [code, v] : state->overlays) {
    for (HWND o : v) {
      DestroyOverlay(o, code);
    }
  }
  state->overlays.clear();
}

// Re-raises every overlay to the top of the sibling Z-order.
// Chromium creates child HWNDs during its lifecycle and each new
// WS_CHILD goes to HWND_TOP on creation, pushing our overlays behind.
//
// Raise order matters: the last overlay raised ends up on top. Raise
// HTCAPTION first, then the button overlays — the button overlays must
// sit above HTCAPTION so clicks on min/max/close don't get swallowed
// by the caption drag strip. HTMAXBUTTON is raised last so the snap-
// layout-critical overlay is unambiguously on top.
void RaiseAllOverlays(ChromeSubclassState* state) {
  if (!state) return;
  const uint32_t kOrderedCodes[] = {
      static_cast<uint32_t>(HTCAPTION),
      static_cast<uint32_t>(HTMINBUTTON),
      static_cast<uint32_t>(HTCLOSE),
      static_cast<uint32_t>(HTMAXBUTTON),
  };
  for (uint32_t code : kOrderedCodes) {
    auto it = state->overlays.find(code);
    if (it == state->overlays.end()) continue;
    for (HWND o : it->second) {
      if (o) {
        ::SetWindowPos(o, HWND_TOP, 0, 0, 0, 0,
                       SWP_NOACTIVATE | SWP_NOMOVE | SWP_NOSIZE);
      }
    }
  }
}

// ─── Parent WndProc handlers (hittest.md §2) ─────────────────────────

// WM_NCCALCSIZE handler (hittest.md §2b).
bool HandleNcCalcSize(ChromeSubclassState* state,
                      HWND hwnd,
                      WPARAM wp,
                      LPARAM lp,
                      LRESULT* out_result) {
  if (wp == 0) return false;

  NCCALCSIZE_PARAMS* params = reinterpret_cast<NCCALCSIZE_PARAMS*>(lp);
  LONG original_top = params->rgrc[0].top;
  // Snapshot the proposed-new and current-old window rects BEFORE
  // calling the default handler. When wp==TRUE, rgrc[0] is the
  // proposed new window rect, rgrc[1] is the current (old) window
  // rect, rgrc[2] is the current client rect — the default handler
  // overwrites all three on return, so we have to capture rgrc[0]
  // and rgrc[1] here for the WVR_VALIDRECTS path below.
  RECT new_window = params->rgrc[0];
  RECT old_window = params->rgrc[1];

  // Let the default handler fill in sensible rgrc[0]/rgrc[1]/rgrc[2]
  // values and compute redraw hints. We IGNORE its return value and
  // always apply our own frameless + zoomed-inset overrides below.
  //
  // The previous implementation early-returned when default_result
  // was non-zero (e.g. WVR_HREDRAW|WVR_VREDRAW, which Chromium's
  // HWNDMessageHandler returns during maximize transitions). That
  // early-return skipped the frameless caption extension AND the
  // monitor work-area inset, so on maximize the client area retained
  // the default framed geometry — a visible caption strip was left as
  // non-client (painted white by DefWindowProc) and the client bled
  // off-screen on left/right/bottom. Symptom: a large white L-shape
  // around the Chromium content when maximized. Always running our
  // overrides eliminates that path.
  ::CallWindowProcW(state->old_wndproc, hwnd, WM_NCCALCSIZE, wp, lp);

  // Extend the client into the caption area — the frameless look.
  params->rgrc[0].top = original_top;

  // Zoomed inset. When a window is maximized, Windows positions its
  // window rect with the frame extending past the monitor work area
  // on ALL four sides (top/left/right/bottom, each by roughly
  // SM_CXPADDEDBORDER + SM_C[XY]FRAME pixels). That's deliberate — on
  // a normal framed window those extra pixels are the frame itself
  // and sit invisibly beyond the screen edge. But our frameless
  // WM_NCCALCSIZE makes client == window, so those same pixels are
  // *content* pixels that bleed off-screen, and the visible client
  // area no longer lines up with the monitor. Symptom: the content
  // appears offset (shifted up-and-left past the monitor edge) and
  // the right/bottom edges get clipped by the taskbar/screen.
  //
  // Use the monitor work area as the authoritative client rect when
  // zoomed — that way the client exactly matches the usable screen
  // surface regardless of DPI or frame-metric quirks. MONITOR_
  // DEFAULTTONEAREST handles multi-monitor setups: the window's
  // containing monitor's work area excludes the taskbar on whichever
  // edge it lives.
  if (::IsZoomed(hwnd)) {
    HMONITOR mon = ::MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
    MONITORINFO mi = {};
    mi.cbSize = sizeof(mi);
    if (::GetMonitorInfoW(mon, &mi)) {
      params->rgrc[0] = mi.rcWork;
    } else {
      // GetMonitorInfo shouldn't fail for a valid HMONITOR, but
      // fall back to the old per-metric inset just in case so the
      // window still clips sanely rather than bleeding.
      UINT dpi = ::GetDpiForWindow(hwnd);
      if (dpi == 0) dpi = 96;
      int padded = ::GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
      int xframe = ::GetSystemMetricsForDpi(SM_CXFRAME, dpi);
      int yframe = ::GetSystemMetricsForDpi(SM_CYFRAME, dpi);
      params->rgrc[0].top    += padded + yframe;
      params->rgrc[0].left   += padded + xframe;
      params->rgrc[0].right  -= padded + xframe;
      params->rgrc[0].bottom -= padded + yframe;
    }
  }

  // Bit-copy hint: preserve the overlapping pixels between the old
  // and new client areas at the same screen position during live
  // resize. Without this, returning 0 tells Windows "no valid source
  // rect — invalidate the whole client and redraw from scratch". The
  // DWM composition layer then presents a blank/white strip in the
  // newly exposed area every frame until Chromium's compositor can
  // push a fresh frame at the new size, which is visible as the
  // white gap on the right/bottom edge during a drag-resize.
  //
  // Returning WVR_VALIDRECTS with rgrc[1]=source and rgrc[2]=dest set
  // to the overlap of old/new clients tells Windows "the pixels in
  // this region are still valid at the same screen position — leave
  // them in place, only invalidate the newly exposed strip". The
  // exposed strip still needs a new compositor frame, but the old
  // content stays visible through the resize tick, eliminating the
  // flash.
  //
  // Coord spaces: rgrc[1] is read by Windows as a SOURCE rect in
  // screen coords (subset of the OLD window rect); rgrc[2] as a
  // DESTINATION rect in screen coords (subset of the NEW window
  // rect). We want source==dest==the screen-space overlap of the
  // two client areas so the overlap is a no-op blit (same pixel
  // position) and Windows discards nothing inside it.
  //
  // CAVEAT: this no-op blit is only valid when the window's ORIGIN
  // hasn't moved. The optimization preserves pixels at their absolute
  // screen position, but a content pixel's MEANING (which client
  // coordinate it represents) depends on the window origin. If the
  // origin shifts — maximize, restore, drag-resize from the top or
  // left edge — old pixels at screen (X, Y) belong to old client
  // coords (X-old.left, Y-old.top), but in the new layout the same
  // screen position is new client coords (X-new.left, Y-new.top),
  // a different cell of the page. The user sees the previous frame's
  // content offset inside the new client and a stale band where the
  // old origin used to be — which is the "half blank after maximize"
  // symptom. For origin-changing transitions, fall through to the 0
  // return so Windows fully invalidates and Chromium repaints from
  // scratch at the new bounds.
  if (new_window.left == old_window.left &&
      new_window.top == old_window.top) {
    RECT new_client = params->rgrc[0];
    RECT overlap;
    // IntersectRect returns FALSE for empty intersections, but it can
    // also return TRUE with a degenerate (zero-area) rect on some edge
    // cases — first NCCALCSIZE before the window has any size, or a
    // resize that reduces the client to zero on one axis. WVR_VALIDRECTS
    // with a degenerate source/dest is undefined; fall through to the
    // full-invalidate path so DWM doesn't try to bitblt a zero-pixel
    // rect (which can manifest as a residual blank band).
    if (::IntersectRect(&overlap, &old_window, &new_client) &&
        overlap.right > overlap.left &&
        overlap.bottom > overlap.top) {
      params->rgrc[1] = overlap;
      params->rgrc[2] = overlap;
      *out_result = WVR_VALIDRECTS;
      return true;
    }
  }

  *out_result = 0;
  return true;
}

// WM_NCHITTEST handler. The parent subclass only sees hit-tests for
// pixels not covered by any child HWND — i.e., the thin border strip
// around the compositor. Every real hit-spot (HTCAPTION, HTMINBUTTON,
// HTMAXBUTTON, HTCLOSE) is owned by a sibling overlay HWND that
// intercepts the cursor first. So this handler only needs to (a) let
// the default proc produce resize-border codes (HTLEFT…HTBOTTOMRIGHT)
// and (b) add a thin HTTOP strip inside the client area so the top
// edge stays resizable even with WM_NCCALCSIZE removing the caption.
bool HandleNcHitTest(ChromeSubclassState* state,
                     HWND hwnd,
                     WPARAM wp,
                     LPARAM lp,
                     LRESULT* out_result) {
  LRESULT default_hit =
      ::CallWindowProcW(state->old_wndproc, hwnd, WM_NCHITTEST, wp, lp);

  if (default_hit >= HTLEFT && default_hit <= HTBOTTOMRIGHT) {
    *out_result = default_hit;
    return true;
  }

  POINT pt = {GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
  ::ScreenToClient(hwnd, &pt);

  UINT dpi = ::GetDpiForWindow(hwnd);
  if (dpi == 0) dpi = 96;
  const double scale = static_cast<double>(dpi) / 96.0;
  const double dip_y = static_cast<double>(pt.y) / scale;

  bool resizable;
  {
    std::lock_guard<std::mutex> lk(state->mu);
    resizable = state->resizable;
  }

  // Thin top resize band when not maximized.
  if (resizable && !::IsZoomed(hwnd)) {
    int frame_px = ::GetSystemMetricsForDpi(SM_CYFRAME, dpi) +
                   ::GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
    double frame_dip = static_cast<double>(frame_px) / scale;
    if (dip_y >= 0.0 && dip_y < frame_dip) {
      *out_result = HTTOP;
      return true;
    }
  }

  *out_result = HTCLIENT;
  return true;
}

LRESULT CALLBACK ChromeWndProc(HWND hwnd,
                               UINT msg,
                               WPARAM wp,
                               LPARAM lp) {
  ChromeSubclassState* state = GetState(hwnd);
  if (!state) {
    return ::DefWindowProcW(hwnd, msg, wp, lp);
  }

  LRESULT result = 0;
  switch (msg) {
    case WM_NCCALCSIZE:
      if (HandleNcCalcSize(state, hwnd, wp, lp, &result)) {
        return result;
      }
      break;

    // Fill the client with our configured background brush. During
    // a fast resize the WebView child HWND lags the parent's new
    // size by a frame or two; the strip exposed on the right/bottom
    // would otherwise show white (Win11's default uncovered-bitmap
    // color). Filling here paints that strip in the page's body
    // color instead, so the gap blends with the rendered content
    // until the compositor catches up.
    case WM_ERASEBKGND: {
      if (state->bg_brush) {
        HDC hdc = reinterpret_cast<HDC>(wp);
        RECT client;
        ::GetClientRect(hwnd, &client);
        ::FillRect(hdc, &client, state->bg_brush);
      }
      return 1;
    }

    case WM_NCHITTEST:
      if (HandleNcHitTest(state, hwnd, wp, lp, &result)) {
        return result;
      }
      break;

    // Overlays own every hit-spot click in the content area. The parent
    // only sees NC clicks for pixels outside every overlay — i.e., the
    // resize-border strip. HTCAPTION / HTSYSMENU forwarded from an
    // overlay lands here too; route those to DefWindowProc so the
    // native SC_MOVE drag loop runs on the top-level.
    case WM_NCLBUTTONDOWN:
      if (wp == HTCAPTION || wp == HTSYSMENU) {
        return ::DefWindowProcW(hwnd, msg, wp, lp);
      }
      break;

    case WM_NCRBUTTONUP:
      if (wp == HTCAPTION || wp == HTSYSMENU) {
        return ::DefWindowProcW(hwnd, msg, wp, lp);
      }
      break;

    // User started a drag-resize / drag-move modal loop. Set the
    // dragging flag so the bounds-change path inside the engine
    // (jux_engine_api.cc — DesktopWindowTreeHostWin observer) can
    // switch the compositor's surface deadline policy from the default
    // 0 frames to a small non-zero value: this makes the parent-side
    // surface activation wait up to N frames for the renderer to
    // submit a CompositorFrame matching the new size, eliminating the
    // gap between the new window edge and the last-painted content.
    // We don't touch the deadline outside a drag — programmatic
    // resizes (Window.setSize from Java) and first-show need the
    // fastest possible activation.
    case WM_ENTERSIZEMOVE:
      state->dragging.store(true, std::memory_order_release);
      // Tell the patched DelegatedFrameHostClient::GetResizeDeadlinePolicy
      // to wait up to 2 display frames (~33 ms @ 60 Hz) for the renderer
      // to produce a CompositorFrame matching the new size before viz
      // activates the surface. Eliminates the visible gap on the resized
      // edge during a fast drag. Cleared on WM_EXITSIZEMOVE.
      SetJuxResizeDeadlineFrames(2u);
      break;

    // Drag-resize / drag-move modal loop ended. Clear the flag so the
    // next programmatic bounds change (or the resting state) goes back
    // to the default zero-deadline policy.
    case WM_EXITSIZEMOVE:
      state->dragging.store(false, std::memory_order_release);
      SetJuxResizeDeadlineFrames(0u);
      break;

    // Delegate WM_SIZE to Chromium's WndProc FIRST, then do our own
    // overlay work and emit the Java-side window-state event. Chromium
    // uses WM_SIZE to resize its compositor swap chain and kick the
    // renderer for a frame at the new dimensions — every millisecond
    // spent here before handing off is a millisecond of visible resize
    // gap on the right/bottom edge. Overlay raise + event emission are
    // pure post-processing and don't need to block the compositor path.
    // wParam: SIZE_RESTORED=0, SIZE_MINIMIZED=1, SIZE_MAXIMIZED=2 —
    // the event switch below round-trips the state into Java.
    case WM_SIZE: {
      LRESULT r = ::CallWindowProcW(state->old_wndproc, hwnd, msg, wp, lp);
      RaiseAllOverlays(state);
      if (wp == SIZE_RESTORED || wp == SIZE_MAXIMIZED) {
        ::PostMessageW(hwnd, kJuxDeferredRaiseMsg, 0, 0);
      }
      if (g_callback_evt_writer && g_callback_channel) {
        uint32_t wid = g_callback_channel->window_id();
        switch (wp) {
          case SIZE_MINIMIZED:
            g_callback_evt_writer->WriteEvent(
                events::kWindowMinimized, wid);
            break;
          case SIZE_MAXIMIZED:
            g_callback_evt_writer->WriteEvent(
                events::kWindowMaximized, wid);
            break;
          case SIZE_RESTORED:
            g_callback_evt_writer->WriteEvent(
                events::kWindowRestored, wid);
            break;
          default:
            break;
        }
      }
      return r;
    }

    // Same ordering logic as WM_SIZE — let Chromium reposition its
    // compositor child first, then re-raise overlays to sit on top.
    case WM_WINDOWPOSCHANGED: {
      LRESULT r = ::CallWindowProcW(state->old_wndproc, hwnd, msg, wp, lp);
      RaiseAllOverlays(state);
      return r;
    }

    // DPI change — fired when the window crosses to a monitor with a
    // different DPI, or when the user changes the system scaling. The
    // overlay HWNDs were sized in physical pixels using the OLD DPI, so
    // their rects are stale immediately after the transition: snap-
    // layout flyout would arm at the wrong screen position, button
    // clicks would miss, drag region would shift.
    //
    // Let Chromium's WndProc handle the actual window resize first
    // (lParam carries the suggested new bounds in pixels for the new
    // DPI; HWNDMessageHandler::OnDpiChanged applies them). Then re-flow
    // every overlay against the same DIP-coord hit_spots — UpdateOverlays
    // reads GetDpiForWindow(state->hwnd) inline so the new pixel rects
    // come out correct without Java having to re-poll the DOM.
    case WM_DPICHANGED: {
      LRESULT r = ::CallWindowProcW(state->old_wndproc, hwnd, msg, wp, lp);
      std::vector<HitSpotRect> spots_copy;
      {
        std::lock_guard<std::mutex> lk(state->mu);
        spots_copy = state->hit_spots;
      }
      if (!spots_copy.empty()) {
        UpdateOverlays(state, spots_copy);
      }
      return r;
    }

    // Restore-from-minimize activates the window. Chromium often
    // re-raises its compositor on activation; catch both WA_ACTIVE and
    // WA_CLICKACTIVE so the overlays stay above the compositor.
    case WM_ACTIVATE:
      if (LOWORD(wp) != WA_INACTIVE) {
        RaiseAllOverlays(state);
        ::PostMessageW(hwnd, kJuxDeferredRaiseMsg, 0, 0);
      }
      break;

    // Chromium creates child HWNDs during its lifecycle (widget attach,
    // navigation, etc.) and each new WS_CHILD goes to HWND_TOP on
    // creation, pushing our overlays behind. Re-raise every time a new
    // sibling appears so hit-testing never races with Chromium's
    // internal HWND creation.
    case WM_PARENTNOTIFY:
      if (LOWORD(wp) == WM_CREATE) {
        RaiseAllOverlays(state);
      }
      break;

    case WM_NCDESTROY: {
      WNDPROC original = state->old_wndproc;
      LRESULT final_result =
          ::CallWindowProcW(original, hwnd, msg, wp, lp);
      UninstallChromeSubclass(hwnd);
      return final_result;
    }

    default:
      // Deferred re-raise posted from WM_SIZE / WM_ACTIVATE — runs
      // after Chromium's own restore/activate work has drained from
      // the queue, so overlays end up on top and stay there.
      if (msg == kJuxDeferredRaiseMsg) {
        RaiseAllOverlays(state);
        return 0;
      }
      break;
  }

  return ::CallWindowProcW(state->old_wndproc, hwnd, msg, wp, lp);
}

}  // namespace

bool InstallChromeSubclass(HWND hwnd) {
  if (!hwnd) return false;
  if (GetState(hwnd)) return true;  // already installed

  auto* state = new ChromeSubclassState();
  state->hwnd = hwnd;

  LONG_PTR prev = ::SetWindowLongPtrW(
      hwnd, GWLP_WNDPROC,
      reinterpret_cast<LONG_PTR>(&ChromeWndProc));
  if (prev == 0) {
    delete state;
    LOG(WARNING) << "InstallChromeSubclass: SetWindowLongPtrW failed";
    return false;
  }
  state->old_wndproc = reinterpret_cast<WNDPROC>(prev);
  ::SetPropW(hwnd, kStatePropName, reinterpret_cast<HANDLE>(state));

  // Default background fill: a neutral page-grey (#f4f6f9) so the
  // strip exposed on the right/bottom during a fast resize blends
  // with the page body until Chromium's compositor catches up. The
  // app overrides this via SetChromeBackgroundColor whenever it
  // knows the page's actual body color (dark mode, themed UI, …).
  state->bg_color = RGB(0xF4, 0xF6, 0xF9);
  state->bg_brush = ::CreateSolidBrush(state->bg_color);

  // Point the class's HBRBACKGROUND at our brush. Windows uses this
  // when it wants to fill before our WndProc runs (e.g., between
  // CreateWindow and the first WM_PAINT, or during DWM redirection-
  // bitmap growth on a fast resize). Combined with the WM_ERASEBKGND
  // FillRect above, the exposed strip never shows white.
  ::SetClassLongPtrW(hwnd, GCLP_HBRBACKGROUND,
                     reinterpret_cast<LONG_PTR>(state->bg_brush));

  // Per-hit-spot overlays are created lazily on the first
  // SetChromeHitSpots call — by that point the WebView child is
  // attached and the overlays' Z-order above it is meaningful.

  // Per hittest.md §0.2 the caller fires SWP_FRAMECHANGED afterwards.
  VLOG(1) << "Chrome subclass installed (HWND=" << hwnd << ")";
  return true;
}

void UninstallChromeSubclass(HWND hwnd) {
  if (!hwnd) return;
  ChromeSubclassState* state = GetState(hwnd);
  if (!state) return;

  DestroyAllOverlays(state);

  // Clear the class-level brush reference before destroying it so
  // Windows doesn't try to paint with a freed handle on subsequent
  // messages.
  ::SetClassLongPtrW(hwnd, GCLP_HBRBACKGROUND, 0);
  if (state->bg_brush) {
    ::DeleteObject(state->bg_brush);
    state->bg_brush = nullptr;
  }

  if (state->old_wndproc) {
    ::SetWindowLongPtrW(
        hwnd, GWLP_WNDPROC,
        reinterpret_cast<LONG_PTR>(state->old_wndproc));
  }
  ::RemovePropW(hwnd, kStatePropName);
  delete state;

  ::SetWindowPos(hwnd, nullptr, 0, 0, 0, 0,
                 SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_FRAMECHANGED);

  // Unregister the process-global overlay window class now its windows are gone.
  // UnregisterClassW fails harmlessly (returns FALSE) if any window of the class
  // still exists (another subclassed window) — so it self-gates to the last one.
  // Only clear the flag when it actually unregisters, so a later install
  // re-registers correctly.
  if (g_chrome_overlay_class_registered &&
      ::UnregisterClassW(kChromeOverlayClassName, ::GetModuleHandleW(nullptr))) {
    g_chrome_overlay_class_registered = false;
  }

  VLOG(1) << "Chrome subclass uninstalled (HWND=" << hwnd << ")";
}

void SetChromeBackgroundColor(HWND hwnd, uint32_t rgb) {
  if (!hwnd) return;
  ChromeSubclassState* state = GetState(hwnd);
  if (!state) return;
  COLORREF color = RGB((rgb >> 16) & 0xFF,
                       (rgb >> 8)  & 0xFF,
                       (rgb >> 0)  & 0xFF);
  // Build the new brush before swapping so we never have a window
  // referencing a freed handle, even momentarily.
  HBRUSH new_brush = ::CreateSolidBrush(color);
  HBRUSH old_brush = state->bg_brush;
  state->bg_brush = new_brush;
  state->bg_color = color;
  ::SetClassLongPtrW(hwnd, GCLP_HBRBACKGROUND,
                     reinterpret_cast<LONG_PTR>(new_brush));
  if (old_brush) {
    ::DeleteObject(old_brush);
  }
  // Force an immediate repaint so the new color appears without
  // waiting for the next resize / DWM frame.
  ::InvalidateRect(hwnd, nullptr, TRUE);
}

void SetChromeTitleBarHeight(HWND hwnd, double height_dip) {
  ChromeSubclassState* state = GetState(hwnd);
  if (!state) return;
  std::lock_guard<std::mutex> lk(state->mu);
  state->title_bar_height_dip = height_dip;
}

void SetChromeHitSpots(HWND hwnd,
                       const HitSpotRect* spots,
                       size_t count) {
  ChromeSubclassState* state = GetState(hwnd);
  if (!state) return;

  std::vector<HitSpotRect> v(spots, spots + count);

  {
    std::lock_guard<std::mutex> lk(state->mu);
    state->hit_spots = v;
  }

  // Overlay reconciliation runs outside the mutex — Win32 HWND ops
  // must not block under a lock, and the overlay HWNDs are UI-thread-
  // owned state (not protected by mu). UpdateOverlays creates one
  // child HWND per (code, rect) tuple for HTMIN / HTMAX / HTCLOSE /
  // HTCAPTION, returning the right HT code from their own WM_NCHITTEST.
  UpdateOverlays(state, v);
}

void SetChromeResizable(HWND hwnd, bool resizable) {
  ChromeSubclassState* state = GetState(hwnd);
  if (!state) return;
  std::lock_guard<std::mutex> lk(state->mu);
  state->resizable = resizable;
}

bool IsChromeDragging(HWND hwnd) {
  ChromeSubclassState* state = GetState(hwnd);
  if (!state) return false;
  return state->dragging.load(std::memory_order_acquire);
}

}  // namespace jux

#endif  // BUILDFLAG(IS_WIN)
