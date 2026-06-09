/*
 * skia-fx Win32 custom-decorations WindowProc.
 *
 * Subclasses the GlassWindow HWND for stages created with
 * StageStyle.CUSTOM. Ported from XDSSWAR's
 * nfx-lib (NfxAbstract.cpp / NfxWinProc.h), simplified for in-tree
 * integration:
 *
 *   - No HwndMap. Per-HWND state is stored via SetProp on the
 *     HWND itself; the static window proc retrieves it with
 *     GetProp. We never need to look up by HWND from a global map.
 *
 *   - Installs on the HWND immediately after Glass creates it, in
 *     GlassWindow.cpp's _createWindow, before the window is ever
 *     shown. There is no Java-side install() call — Glass already
 *     owns the HWND in native scope.
 *
 *   - JNI callback is on com.sun.glass.ui.win.WinWindow:
 *         private int jniHitTest(int x, int y, boolean onResizeBorder)
 *     resolved via cached jmethodID. Returns an HT_* code matching
 *     the Win32 constants (HTCLIENT, HTCAPTION, HTMINBUTTON, etc.).
 *
 * The WindowProc owns:
 *   - WM_NCCALCSIZE  → restore top so the OS caption strip is gone
 *                      while L / R / B frames stay for resize
 *   - WM_NCHITTEST   → DefWindowProc for edge classification, then
 *                      Java for caption / button hit-testing
 *   - WM_NCMOUSEMOVE → forward HTMIN/MAX/CLOSE/CAPTION/SYSMENU to
 *                      the client area so JavaFX sees the mouse
 *   - WM_NCLBUTTONDOWN/UP → forward button hits to client area
 *   - WM_NCRBUTTONUP → open the platform system menu on caption
 *                      right-click
 *   - Maximized + auto-hide-taskbar adjustments
 *
 * Everything else is delegated to the original Glass WindowProc
 * via CallWindowProc — the rest of JFX's message handling is
 * preserved untouched.
 */
#ifndef SKIA_FX_CUSTOM_WIN_PROC_H
#define SKIA_FX_CUSTOM_WIN_PROC_H

#include <windows.h>
#include <jni.h>

class SkiaCustomWinProc {
public:
    /**
     * Subclasses the given HWND so its WindowProc routes
     * NC messages through the custom-decoration handlers.
     *
     * Called from GlassWindow.cpp:_createWindow right after
     * CreateWindowEx returns, before the window is shown. The
     * Java object reference (the WinWindow instance) is used
     * for the {@code jniHitTest} callback.
     *
     * @param env JNI env from the calling JNI method (used to
     *            create a global ref and capture the JavaVM)
     * @param hWnd window to subclass; must not yet be visible
     * @param jWindow local reference to the Java WinWindow; the
     *                installer takes a global ref and releases it
     *                on uninstall
     * @return true on success
     */
    static bool install(JNIEnv *env, HWND hWnd, jobject jWindow);

    /**
     * Removes the subclassing and frees per-HWND state. Called
     * from the WindowProc itself on WM_NCDESTROY; the application
     * does not invoke this directly.
     */
    static void uninstall(HWND hWnd);

    /**
     * One-time JNI ID resolution. Called from
     * Java_com_sun_glass_ui_win_WinWindow__1initIDs.
     */
    static void initIDs(JNIEnv *env, jclass winWindowClass);
};

#endif // SKIA_FX_CUSTOM_WIN_PROC_H
