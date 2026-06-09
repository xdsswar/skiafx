/*
 * skia-fx Win32 custom-decorations WindowProc.
 *
 * Ported from XDSSWAR's nfx-lib (NfxAbstract.cpp). Simplified for
 * in-tree integration: per-HWND state lives on the HWND itself via
 * SetProp / GetProp instead of a global HwndMap, and the install /
 * uninstall are driven from native (GlassWindow.cpp:_createWindow)
 * because we already own the freshly-created HWND there.
 */

#include "CustomWinProc.h"

#include <windowsx.h>   // GET_X_LPARAM / GET_Y_LPARAM
#include <shellapi.h>   // SHAppBarMessage
#include <versionhelpers.h>

// ---------------------------------------------------------------- per-HWND state

namespace {
    // Per-HWND state, attached via SetProp. The static WindowProc
    // pulls this out with GetProp on every message.
    struct PerWindowState {
        WNDPROC defaultWndProc = nullptr;
        JavaVM* jvm            = nullptr;
        jobject jWindowGlobal  = nullptr;
        // Latched at install. If the application later toggles
        // fullscreen / maximize from Java the WindowProc reads the
        // current state via GetWindowLong(GWL_STYLE) & WS_MAXIMIZE,
        // so no Java callback is needed for those.

        // ---- Hover-reset state -----------------------------------
        // Gates re-arming TrackMouseEvent(TME_NONCLIENT|TME_LEAVE).
        // Set when armed; cleared on WM_NCMOUSELEAVE so the next NC
        // entry re-arms. We rely on the OS to give us a one-shot
        // leave notification — polling on every message would be
        // wasteful.
        bool ncMouseTracking = false;
        // True between WM_ENTERSIZEMOVE and WM_EXITSIZEMOVE — the OS
        // modal sizing / moving loop. Used solely to gate the hover
        // reset: a button's :ht-* pseudo-class must NOT clear while
        // the user is operating the window edges (even if the cursor
        // briefly leaves the window during the drag).
        bool isInSizeMove = false;

        // Single-slot cache for WM_NCHITTEST results. During click /
        // double-click sequences and rapid mouse motion the OS fires
        // many WM_NCHITTEST with identical lParam — without this
        // cache each one re-enters Java and walks the scene-graph
        // for localToScene bounds (which dominates the click latency
        // on double-click-to-maximize). Invalidated on WM_SIZE /
        // WM_DPICHANGED below so a layout change doesn't return a
        // stale region.
        bool    hitCacheValid = false;
        LPARAM  hitCacheLParam = 0;
        LRESULT hitCacheCode   = HTCLIENT;

        // Paint-on-erase gate. Default WM_ERASEBKGND returns 1 with
        // no fill (matches upstream — that's the JDK-8171852 resize-
        // flicker fix). BUT on a small set of events the OS hands
        // us a freshly-allocated redirection bitmap whose contents
        // are uninitialised (white): the first show, and any
        // transition between normal / maximized. On those we fill
        // the brush once so the user doesn't see a white flash
        // before Skia's first present lands. The gate is reset to
        // true here and re-armed on maximize/restore state changes
        // in WM_SIZE.
        bool needsFillOnNextErase = true;
        bool wasMaximized         = false;
    };

    // SetProp atom name. Plain ASCII; per-HWND uniqueness is
    // guaranteed by the HWND itself, so we share one atom name.
    constexpr LPCSTR kPropName = "skia-fx.custom.winproc";

    // Mirror of ViewContainer.h's IDT_GLASS_PULSE_MODAL. Defined
    // locally rather than via include because ViewContainer.h pulls
    // in OLE / manipulation-processor types that this TU doesn't
    // need. Keep the numeric value in sync.
    constexpr UINT_PTR kIdtGlassPulseModal = 0x104;

    // Cached jmethodID for WinWindow.jniHitTest(int,int,boolean):int.
    // One per process; resolved in initIDs().
    jmethodID gJniHitTestMID = nullptr;
    // Cached jmethodID for WinWindow.jniClearHitRegions():void.
    // Fired from WM_NCMOUSELEAVE when the cursor is verified to be
    // genuinely outside the window AND no modal sizing/moving loop
    // is active.
    jmethodID gJniClearHitRegionsMID = nullptr;
    // Cached class+method id for
    // QuantumToolkit.firePulseFromNativeTimer():void. Held as a
    // global ref so it survives across the AttachCurrentThread cycle
    // inside EnvGuard. Resolved on first call (we can't do it from
    // initIDs because that runs with the WinWindow class env). Null
    // entries make the WM_TIMER handler a graceful no-op.
    jclass    gQuantumToolkitClass     = nullptr;
    jmethodID gQuantumFirePulseMID     = nullptr;
    bool      gQuantumFirePulseResolveTried = false;

    // Resolve gQuantumToolkitClass + gQuantumFirePulseMID. Idempotent.
    // Must be called on a thread with a live JNIEnv.
    void ResolveQuantumFirePulse(JNIEnv *env) {
        if (gQuantumFirePulseResolveTried) return;
        gQuantumFirePulseResolveTried = true;
        if (!env) return;
        jclass localCls = env->FindClass("com/sun/javafx/tk/quantum/QuantumToolkit");
        if (env->ExceptionCheck()) { env->ExceptionClear(); return; }
        if (!localCls) return;
        gQuantumToolkitClass = (jclass) env->NewGlobalRef(localCls);
        env->DeleteLocalRef(localCls);
        if (!gQuantumToolkitClass) return;
        gQuantumFirePulseMID = env->GetStaticMethodID(
            gQuantumToolkitClass, "firePulseFromNativeTimer", "()V");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            env->DeleteGlobalRef(gQuantumToolkitClass);
            gQuantumToolkitClass = nullptr;
            gQuantumFirePulseMID = nullptr;
        }
    }

    PerWindowState *stateFor(HWND hWnd) {
        return static_cast<PerWindowState *>(::GetPropA(hWnd, kPropName));
    }

    // Attach to the calling thread for the duration of the call.
    // The message thread is the JFX Glass thread, which is already
    // attached to the JVM; GetEnv returns JNI_OK and we use that
    // env directly without detaching.
    class EnvGuard {
    public:
        explicit EnvGuard(JavaVM *jvm) {
            if (!jvm) return;
            JNIEnv *e = nullptr;
            jint s = jvm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_8);
            if (s == JNI_OK) {
                env_ = e;
            } else if (s == JNI_EDETACHED &&
                       jvm->AttachCurrentThread(reinterpret_cast<void**>(&e), nullptr) == JNI_OK) {
                env_ = e;
                attachedHere_ = true;
                jvm_ = jvm;
            }
        }
        ~EnvGuard() {
            if (attachedHere_ && jvm_) jvm_->DetachCurrentThread();
        }
        JNIEnv *env() const { return env_; }
    private:
        JNIEnv *env_ = nullptr;
        JavaVM *jvm_ = nullptr;
        bool attachedHere_ = false;
    };
}

// ---------------------------------------------------------------- helpers

namespace {
    // Resize-handle width in physical pixels. The skia-fx native
    // build targets Win7 SDK headers (_WIN32_WINNT = 0x0601) so we
    // can't use GetSystemMetricsForDpi here; plain GetSystemMetrics
    // returns the value for the primary display, which Windows
    // scales for Per-Monitor-V2 windows automatically.
    int ResizeHandleHeight() {
        return ::GetSystemMetrics(SM_CXPADDEDBORDER)
             + ::GetSystemMetrics(SM_CYSIZEFRAME);
    }

    // Per-monitor window DPI. GetDpiForWindow (user32, Win10 1607+) returns
    // THIS window's monitor DPI; GetDeviceCaps(LOGPIXELSX) returns the primary/
    // system DPI for a per-monitor-aware process, which is wrong on secondary
    // monitors — that broke title-bar hit-testing on 150%/175% displays. Loaded
    // dynamically because the constant/prototype aren't in our Win7 SDK headers.
    typedef UINT (WINAPI *FnGetDpiForWindow)(HWND);
    UINT WindowDpi(HWND hWnd) {
        static FnGetDpiForWindow pGetDpiForWindow = []() -> FnGetDpiForWindow {
            HMODULE u = ::GetModuleHandleW(L"user32.dll");
            return u ? (FnGetDpiForWindow) ::GetProcAddress(u, "GetDpiForWindow")
                     : nullptr;
        }();
        if (pGetDpiForWindow) {
            UINT dpi = pGetDpiForWindow(hWnd);
            if (dpi > 0) return dpi;
        }
        HDC hdc = ::GetDC(hWnd);
        if (!hdc) return USER_DEFAULT_SCREEN_DPI;
        UINT dpi = (UINT) ::GetDeviceCaps(hdc, LOGPIXELSX);
        ::ReleaseDC(hWnd, hdc);
        return dpi == 0 ? USER_DEFAULT_SCREEN_DPI : dpi;
    }

    int PxToDip(int px, UINT dpi) {
        if (dpi == 0) dpi = USER_DEFAULT_SCREEN_DPI;
        return ::MulDiv(px, USER_DEFAULT_SCREEN_DPI, (int) dpi);
    }

    POINT LParamScreenToClient(HWND hWnd, LPARAM lp) {
        POINT pt = { GET_X_LPARAM(lp), GET_Y_LPARAM(lp) };
        ::ScreenToClient(hWnd, &pt);
        return pt;
    }

    LRESULT ScreenToWindowCoordinates(HWND hWnd, LPARAM lp) {
        RECT wr;
        ::GetWindowRect(hWnd, &wr);
        int x = GET_X_LPARAM(lp) - wr.left;
        int y = GET_Y_LPARAM(lp) - wr.top;
        return MAKELONG(x, y);
    }

    bool HasAutoHideTaskbar(int edge, RECT rcMonitor) {
        APPBARDATA d = { 0 };
        d.cbSize = sizeof(d);
        d.uEdge  = edge;
        d.rc     = rcMonitor;
        DWORD msg = ::IsWindows8OrGreater() ? ABM_GETAUTOHIDEBAREX : ABM_GETAUTOHIDEBAR;
        return ::SHAppBarMessage(msg, &d) != NULL;
    }

    void SendToClientArea(HWND hWnd, UINT msg, LPARAM lParam) {
        ::SendMessage(hWnd, msg, 0, ScreenToWindowCoordinates(hWnd, lParam));
    }

    void SetMenuItemEnabled(HMENU menu, UINT item, bool enabled) {
        ::EnableMenuItem(menu, item,
            MF_BYCOMMAND | (enabled ? MF_ENABLED : (MF_DISABLED | MF_GRAYED)));
    }

    // Open the platform system menu at the given screen coords; used
    // when the application's caption / system-menu region receives
    // a non-client right-click. Mirrors nfx-lib's openSystemMenu().
    void OpenSystemMenu(HWND hWnd, int xScreen, int yScreen) {
        HMENU menu = ::GetSystemMenu(hWnd, FALSE);
        if (!menu) return;

        LONG style       = ::GetWindowLong(hWnd, GWL_STYLE);
        bool isMaximized = ::IsZoomed(hWnd);
        SetMenuItemEnabled(menu, SC_RESTORE,  isMaximized);
        SetMenuItemEnabled(menu, SC_MOVE,     !isMaximized);
        SetMenuItemEnabled(menu, SC_SIZE,     (style & WS_THICKFRAME) != 0 && !isMaximized);
        SetMenuItemEnabled(menu, SC_MINIMIZE, (style & WS_MINIMIZEBOX) != 0);
        SetMenuItemEnabled(menu, SC_MAXIMIZE, (style & WS_MAXIMIZEBOX) != 0 && !isMaximized);
        SetMenuItemEnabled(menu, SC_CLOSE,    true);
        ::SetMenuDefaultItem(menu, SC_CLOSE, FALSE);

        UINT cmd = ::TrackPopupMenu(
            menu,
            TPM_RETURNCMD | TPM_RIGHTBUTTON | TPM_TOPALIGN | TPM_LEFTALIGN,
            xScreen, yScreen, 0, hWnd, nullptr);
        if (cmd != 0) {
            ::PostMessage(hWnd, WM_SYSCOMMAND, cmd, 0);
        }
    }
}

// ---------------------------------------------------------------- message handlers

namespace {
    // WM_NCCALCSIZE — call DefWindowProc to compute the standard
    // caption + thick-frame insets, then restore the top so there's
    // no top NC area. That suppresses the OS caption (and the system
    // buttons drawn inside it) while keeping L / R / B frames for
    // OS-driven edge resize, Aero Snap, and Win11 snap layouts.
    LRESULT OnNcCalcSize(HWND hWnd, PerWindowState *s,
                         UINT msg, WPARAM wParam, LPARAM lParam)
    {
        if (wParam != TRUE) {
            return ::CallWindowProc(s->defaultWndProc, hWnd, msg, wParam, lParam);
        }

        NCCALCSIZE_PARAMS *p = (NCCALCSIZE_PARAMS *) lParam;
        const LONG originalTop = p->rgrc[0].top;

        LRESULT r = ::CallWindowProc(s->defaultWndProc, hWnd, msg, wParam, lParam);
        if (r != 0) return r;

        p->rgrc[0].top = originalTop;

        // Maximized: the OS extends the window past the screen edges
        // by the resize-handle width. Inset the client by that
        // amount so content stays on-screen.
        if (::IsZoomed(hWnd)) {
            const int inset = ResizeHandleHeight();
            p->rgrc[0].top += inset;

            HMONITOR mon = ::MonitorFromWindow(hWnd, MONITOR_DEFAULTTONEAREST);
            if (mon) {
                MONITORINFO mi = { 0 };
                mi.cbSize = sizeof(mi);
                ::GetMonitorInfo(mon, &mi);

                APPBARDATA abState = { 0 };
                abState.cbSize = sizeof(abState);
                if ((::SHAppBarMessage(ABM_GETSTATE, &abState) & ABS_AUTOHIDE) == ABS_AUTOHIDE) {
                    if      (HasAutoHideTaskbar(ABE_TOP,    mi.rcMonitor)) p->rgrc[0].top    += 1;
                    else if (HasAutoHideTaskbar(ABE_BOTTOM, mi.rcMonitor)) p->rgrc[0].bottom -= 1;
                    else if (HasAutoHideTaskbar(ABE_LEFT,   mi.rcMonitor)) p->rgrc[0].left   += 1;
                    else if (HasAutoHideTaskbar(ABE_RIGHT,  mi.rcMonitor)) p->rgrc[0].right  -= 1;
                }
            }
        }
        return 0;
    }

    // WM_NCHITTEST — defer to the OS for edge / corner classification
    // (we keep L / R / B frames so Aero Snap works), then ask Java
    // to classify points inside the client area + the top strip.
    // Local helper: arm one-shot WM_NCMOUSELEAVE tracking when the
    // cursor is in the NC area. ONLY called from paths that have
    // confirmed the final hit code is non-HTCLIENT; arming while the
    // cursor is in the client area would cause WM_NCMOUSELEAVE to
    // fire immediately and produce a re-arm loop on every mouse move.
    void ArmNcLeaveTracking(HWND hWnd, PerWindowState *s) {
        if (s->ncMouseTracking) return;
        TRACKMOUSEEVENT tme = { 0 };
        tme.cbSize    = sizeof(tme);
        tme.dwFlags   = TME_LEAVE | TME_NONCLIENT;
        tme.hwndTrack = hWnd;
        if (::TrackMouseEvent(&tme)) {
            s->ncMouseTracking = true;
        }
    }

    // WM_NCHITTEST — defer to the OS for edge / corner classification
    // (we keep L / R / B frames so Aero Snap works), then ask Java
    // to classify points inside the client area + the top strip.
    LRESULT OnNcHitTest(HWND hWnd, PerWindowState *s, LPARAM lParam)
    {
        // Cache lookup. WM_NCHITTEST fires many times with identical
        // lParam during click / double-click sequences and steady
        // mouse hover; returning the previous result skips the JNI
        // hit-test and the scene-graph walk that dominates per-call
        // cost. Invalidated by WM_SIZE / WM_DPICHANGED so it never
        // returns a result from a stale layout.
        if (s->hitCacheValid && s->hitCacheLParam == lParam) {
            return s->hitCacheCode;
        }

        // Phase 1: let the OS classify the frame edges.
        LRESULT osHit = ::CallWindowProc(s->defaultWndProc, hWnd,
                                         WM_NCHITTEST, 0, lParam);

        // Inside the OS modal sizing / moving loop, WM_NCHITTEST
        // fires on every cursor pixel for cursor-classification
        // purposes — the actual drag/resize behaviour is already
        // driven by the HT code returned at WM_NCLBUTTONDOWN time.
        // Skip the JNI hit-test (which walks the JFX scene graph
        // for localToScene bounds and is the per-message cost that
        // makes the drag feel slow). Just return what the OS gave
        // us; the cursor stays correct because DefWindowProc's
        // modal loop manages the move / resize cursor itself.
        if (s->isInSizeMove) {
            // Don't cache loop hits — when the loop ends the same
            // lParam might want the Java-classified code instead.
            return osHit;
        }

        if (osHit != HTCLIENT) {
            // OS classified the hit as NC (resize border etc.).
            // Arm tracking so we get WM_NCMOUSELEAVE when the cursor
            // leaves the NC area.
            ArmNcLeaveTracking(hWnd, s);
            s->hitCacheValid  = true;
            s->hitCacheLParam = lParam;
            s->hitCacheCode   = osHit;
            return osHit;
        }

        // Phase 2: client hit. Convert to DIP and ask Java.
        POINT pt = LParamScreenToClient(hWnd, lParam);
        const UINT dpi = WindowDpi(hWnd);
        const int  xDip = PxToDip(pt.x, dpi);
        const int  yDip = PxToDip(pt.y, dpi);
        // The top resize-handle band — Java uses this to upgrade an
        // "unclaimed caption" hit to HTTOP (so the cursor changes
        // and OS resize engages on parts of the title bar that
        // aren't a button or drag region).
        const bool onResizeBorder = pt.y < ResizeHandleHeight();

        if (!gJniHitTestMID) return HTCLIENT;

        EnvGuard guard(s->jvm);
        JNIEnv *env = guard.env();
        if (!env) return HTCLIENT;

        jint code = env->CallIntMethod(s->jWindowGlobal, gJniHitTestMID,
            (jint) xDip, (jint) yDip,
            onResizeBorder ? JNI_TRUE : JNI_FALSE);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return HTCLIENT;
        }
        // Java may upgrade a client hit to a button / caption / top
        // resize-band NC code. When it does, arm tracking so the
        // hover-clear fires when the cursor later leaves NC.
        // When the code is HTCLIENT (cursor over plain client area),
        // do NOT arm — there's nothing to clear and arming here would
        // fire WM_NCMOUSELEAVE immediately and re-arm on every move.
        if (code != (jint) HTCLIENT) {
            ArmNcLeaveTracking(hWnd, s);
        }
        s->hitCacheValid  = true;
        s->hitCacheLParam = lParam;
        s->hitCacheCode   = (LRESULT) code;
        return (LRESULT) code;
    }

    // WM_NCMOUSEMOVE on a button-classified area — forward to the
    // client so JavaFX gets hover events for the underlying Region.
    // Required for the Win11 maximize-button snap-layouts to fire
    // and for our JFX-side hover pseudo-classes to update.
    void OnNcMouseMove(HWND hWnd, WPARAM wParam, LPARAM lParam) {
        if (wParam == HTMINBUTTON || wParam == HTMAXBUTTON || wParam == HTCLOSE
            || wParam == HTCAPTION || wParam == HTSYSMENU) {
            SendToClientArea(hWnd, WM_MOUSEMOVE, lParam);
        }
    }

    // WM_NCLBUTTONDOWN / WM_NCLBUTTONUP on a button — forward as
    // client mouse so JFX onMouseClicked handlers fire. Returning 0
    // tells the OS we handled the message, suppressing its default
    // close / minimize / maximize behaviour for those hit codes.
    bool OnNcLButton(HWND hWnd, UINT msg, WPARAM wParam, LPARAM lParam) {
        if (wParam == HTMINBUTTON || wParam == HTMAXBUTTON || wParam == HTCLOSE) {
            UINT clientMsg = (msg == WM_NCLBUTTONDOWN) ? WM_LBUTTONDOWN : WM_LBUTTONUP;
            SendToClientArea(hWnd, clientMsg, lParam);
            return true;
        }
        return false;
    }
}

// ---------------------------------------------------------------- static entry

namespace {
    LRESULT CALLBACK StaticWindowProc(HWND hWnd, UINT msg,
                                       WPARAM wParam, LPARAM lParam)
    {
        PerWindowState *s = stateFor(hWnd);
        if (!s) {
            return ::DefWindowProc(hWnd, msg, wParam, lParam);
        }

        switch (msg) {
            case WM_NCCALCSIZE:
                return OnNcCalcSize(hWnd, s, msg, wParam, lParam);

            case WM_NCHITTEST:
                return OnNcHitTest(hWnd, s, lParam);

            case WM_NCMOUSEMOVE:
                OnNcMouseMove(hWnd, wParam, lParam);
                break;

            case WM_NCLBUTTONDOWN:
            case WM_NCLBUTTONUP:
                if (OnNcLButton(hWnd, msg, wParam, lParam)) {
                    return 0;
                }
                break;

            case WM_NCRBUTTONUP:
                if (wParam == HTCAPTION || wParam == HTSYSMENU) {
                    OpenSystemMenu(hWnd, GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam));
                    return 0;
                }
                break;

            // The OS modal sizing / moving loop. Used solely to gate
            // the hover reset below — during this loop the cursor may
            // briefly leave the window (e.g. dragging an edge past the
            // screen edge), but we MUST NOT clear the buttons' :ht-*
            // pseudo-class because the user is still operating the
            // window. Fall through to DefWindowProc afterwards.
            case WM_ENTERSIZEMOVE: {
                s->isInSizeMove = true;
                // Fire one pulse immediately so JFX animations
                // advance at drag start, before DefWindowProc's
                // modal pump takes over and the first SetTimer
                // heartbeat is still 16ms away.
                {
                    EnvGuard guard(s->jvm);
                    JNIEnv *env = guard.env();
                    if (env) {
                        ResolveQuantumFirePulse(env);
                        if (gQuantumToolkitClass && gQuantumFirePulseMID) {
                            env->CallStaticVoidMethod(
                                gQuantumToolkitClass,
                                gQuantumFirePulseMID);
                            if (env->ExceptionCheck()) env->ExceptionClear();
                        }
                    }
                }
                // Heartbeat timer so JFX pulses still fire while the
                // OS modal sizing/moving loop holds Glass's main
                // message thread. WM_TIMER is one of the message
                // types DefWindowProc's pump dispatches; the
                // handler below re-enters
                // QuantumToolkit.firePulseFromNativeTimer.
                ::SetTimer(hWnd, kIdtGlassPulseModal, 16, nullptr);
                break;
            }

            case WM_EXITSIZEMOVE: {
                s->isInSizeMove = false;
                // Loop end: window dimensions may have changed, so
                // any cached hit code for the previous layout is now
                // potentially stale.
                s->hitCacheValid = false;
                ::KillTimer(hWnd, kIdtGlassPulseModal);
                // One more pulse so the render resumes immediately
                // instead of waiting for the next master-timer tick.
                EnvGuard guard(s->jvm);
                JNIEnv *env = guard.env();
                if (env && gQuantumToolkitClass && gQuantumFirePulseMID) {
                    env->CallStaticVoidMethod(
                        gQuantumToolkitClass, gQuantumFirePulseMID);
                    if (env->ExceptionCheck()) env->ExceptionClear();
                }
                break;
            }

            case WM_TIMER:
                if (wParam == kIdtGlassPulseModal) {
                    EnvGuard guard(s->jvm);
                    JNIEnv *env = guard.env();
                    if (env) {
                        ResolveQuantumFirePulse(env);
                        if (gQuantumToolkitClass && gQuantumFirePulseMID) {
                            env->CallStaticVoidMethod(
                                gQuantumToolkitClass,
                                gQuantumFirePulseMID);
                            if (env->ExceptionCheck()) env->ExceptionClear();
                        }
                    }
                    return 0;
                }
                break;

            // Cache invalidation: any layout-affecting OS event
            // forces the next WM_NCHITTEST to re-classify, otherwise
            // an old region snapshot's bounds could be returned for
            // the new layout. Also detects normal↔maximized
            // transitions and re-arms the WM_ERASEBKGND fill so the
            // newly-exposed redirection-bitmap region doesn't show
            // white before Skia presents.
            case WM_SIZE: {
                s->hitCacheValid = false;
                bool isMax = (wParam == SIZE_MAXIMIZED);
                if (isMax != s->wasMaximized) {
                    s->wasMaximized = isMax;
                    s->needsFillOnNextErase = true;
                }
                break;
            }

            case WM_DPICHANGED:
                s->hitCacheValid = false;
                // DPI change can resize the window — same redirection
                // bitmap reallocation risk as a maximize transition.
                s->needsFillOnNextErase = true;
                break;

            case WM_NCMOUSELEAVE: {
                // Cursor has left the NC area. Re-arm tracking on the
                // next NC hit by clearing the flag here. Then decide
                // whether to fire the Java hover-clear:
                //   (a) cursor still over our window (NC ↔ client
                //       transition only) → skip
                //   (b) OS modal sizing / moving loop active → skip
                //   (c) cursor genuinely outside → fire clear
                s->ncMouseTracking = false;
                if (s->isInSizeMove) {
                    break;
                }
                POINT pt;
                if (::GetCursorPos(&pt)) {
                    HWND under = ::WindowFromPoint(pt);
                    if (under == hWnd ||
                        (under != nullptr && ::IsChild(hWnd, under))) {
                        break;
                    }
                }
                if (gJniClearHitRegionsMID) {
                    EnvGuard guard(s->jvm);
                    JNIEnv *env = guard.env();
                    if (env) {
                        env->CallVoidMethod(s->jWindowGlobal, gJniClearHitRegionsMID);
                        if (env->ExceptionCheck()) env->ExceptionClear();
                    }
                }
                return 0;
            }

            case WM_ERASEBKGND: {
                // Default: match upstream GlassWindow::WindowProc —
                // return 1 without painting. The bare FillRect on
                // every erase is exactly JDK-8171852 "JavaFX Stage
                // flickers on resize on Windows": every WM_ERASEBKGND
                // during a drag-resize paints a solid colour before
                // Skia's render-thread present can update the swap
                // chain, and the user sees the flash.
                //
                // EXCEPTION: a small set of events hand us a freshly-
                // allocated redirection bitmap whose contents are
                // OS-default (white) — the very first erase after
                // creation, and any transition between normal /
                // maximized. On those we fill once so the user
                // doesn't see a white flash before Skia presents.
                // The flag is re-armed in WM_SIZE on max/restore
                // state changes; for all other erases (drag-resize,
                // steady-state paint cycles) it stays false and we
                // return 1 with no flicker.
                if (!s->needsFillOnNextErase) {
                    return 1;
                }
                s->needsFillOnNextErase = false;
                HDC hdc = (HDC) wParam;
                HBRUSH brush = (HBRUSH) ::GetClassLongPtr(hWnd, GCLP_HBRBACKGROUND);
                if (!brush) brush = (HBRUSH) ::GetStockObject(BLACK_BRUSH);
                if (hdc) {
                    RECT rc;
                    ::GetClientRect(hWnd, &rc);
                    ::FillRect(hdc, &rc, brush);
                }
                return 1;
            }

            case WM_NCDESTROY:
                // Last message — restore the original WindowProc and
                // free state. After this point GetProp returns null.
                SkiaCustomWinProc::uninstall(hWnd);
                return ::DefWindowProc(hWnd, msg, wParam, lParam);

            default:
                break;
        }

        return ::CallWindowProc(s->defaultWndProc, hWnd, msg, wParam, lParam);
    }
}

// ---------------------------------------------------------------- public API

bool SkiaCustomWinProc::install(JNIEnv *env, HWND hWnd, jobject jWindow)
{
    if (!hWnd || !env || !jWindow) return false;
    if (stateFor(hWnd)) return false; // already installed

    PerWindowState *s = new PerWindowState();

    // Capture the JavaVM from the calling env so the message
    // handler can re-attach later on any thread.
    if (env->GetJavaVM(&s->jvm) != JNI_OK || !s->jvm) {
        delete s;
        return false;
    }
    s->jWindowGlobal = env->NewGlobalRef(jWindow);
    if (!s->jWindowGlobal) {
        delete s;
        return false;
    }

    s->defaultWndProc = reinterpret_cast<WNDPROC>(
        ::SetWindowLongPtr(hWnd, GWLP_WNDPROC, (LONG_PTR) StaticWindowProc));
    if (!s->defaultWndProc) {
        // Couldn't subclass — clean up.
        JNIEnv *env = nullptr;
        s->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
        if (env) env->DeleteGlobalRef(s->jWindowGlobal);
        delete s;
        return false;
    }

    ::SetPropA(hWnd, kPropName, (HANDLE) s);

    // Force WM_NCCALCSIZE to re-fire through our new WindowProc
    // immediately, so the visible frame is corrected before the
    // window is shown for the first time.
    ::SetWindowPos(hWnd, nullptr, 0, 0, 0, 0,
        SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE
        | SWP_NOZORDER | SWP_NOACTIVATE);
    return true;
}

void SkiaCustomWinProc::uninstall(HWND hWnd)
{
    PerWindowState *s = stateFor(hWnd);
    if (!s) return;

    // Defensive cleanup of the modal-loop pulse heartbeat. Win32
    // cleans up window timers automatically on HWND destruction, so
    // this only matters if uninstall is called outside the normal
    // WM_NCDESTROY path (e.g. a future programmatic teardown). Cheap
    // and idempotent — KillTimer on a non-existent timer just
    // returns FALSE.
    ::KillTimer(hWnd, kIdtGlassPulseModal);

    ::RemovePropA(hWnd, kPropName);

    if (s->defaultWndProc) {
        ::SetWindowLongPtr(hWnd, GWLP_WNDPROC, (LONG_PTR) s->defaultWndProc);
    }
    if (s->jvm && s->jWindowGlobal) {
        JNIEnv *env = nullptr;
        s->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
        if (env) env->DeleteGlobalRef(s->jWindowGlobal);
    }
    delete s;
}

void SkiaCustomWinProc::initIDs(JNIEnv *env, jclass winWindowClass)
{
    gJniHitTestMID = env->GetMethodID(winWindowClass, "jniHitTest", "(IIZ)I");
    if (env->ExceptionCheck()) env->ExceptionClear();
    gJniClearHitRegionsMID = env->GetMethodID(winWindowClass, "jniClearHitRegions", "()V");
    // Either ID may stay null; the corresponding handler gracefully
    // no-ops so custom-decorated windows still work even if the Java
    // side hasn't been re-built.
    if (env->ExceptionCheck()) env->ExceptionClear();
}
