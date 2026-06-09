// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// jux_chrome_subclass — Windows-only HWND subclass for custom chrome.
// Handles WM_NCCALCSIZE (eat caption), WM_NCHITTEST (hit-spot cache),
// and falls through to the original Chromium WndProc for everything
// else.

#ifndef JUX_CHROME_SUBCLASS_H_
#define JUX_CHROME_SUBCLASS_H_

#include "build/build_config.h"

#if BUILDFLAG(IS_WIN)

#include <windows.h>
#include <cstdint>

namespace jux {

// A single hit-spot entry pushed from Java. Rect is in device-independent
// pixels (DIP) relative to the window's client area. `code` is the Win32
// HT* return value to deliver when the cursor falls inside the rect.
struct HitSpotRect {
  uint32_t code;
  double x;
  double y;
  double w;
  double h;
};

// Install the chrome subclass on `hwnd`. Safe to call multiple times.
bool InstallChromeSubclass(HWND hwnd);

// Remove the chrome subclass from `hwnd`. Safe if not installed.
void UninstallChromeSubclass(HWND hwnd);

// Update the title-bar drag-band height in DIP. Top `height` DIP pixels
// of the client area return HTCAPTION when no hit-spot matches.
void SetChromeTitleBarHeight(HWND hwnd, double height_dip);

// Replace the hit-spot cache for `hwnd`.
void SetChromeHitSpots(HWND hwnd, const HitSpotRect* spots, size_t count);

// Tell the subclass whether the window is resizable (gates the thin
// top HTTOP resize band).
void SetChromeResizable(HWND hwnd, bool resizable);

// Update the parent HWND's resize-fill color. `rgb` is a packed
// 0x00RRGGBB integer. The fill paints the strip exposed during a
// fast resize between the WebView child's old edge and the parent's
// new edge — without it, that strip shows the OS default white. The
// app should set this to match the page body's background color.
void SetChromeBackgroundColor(HWND hwnd, uint32_t rgb);

// True while the user is mid-drag-resize (between WM_ENTERSIZEMOVE
// and WM_EXITSIZEMOVE). Read from the bounds-change path so the
// compositor can apply a non-zero surface-deadline policy *only* during
// a user drag — programmatic SetBounds and first-show should keep the
// default zero-deadline so they don't pick up any avoidable latency.
// Returns false if no subclass is installed on hwnd.
bool IsChromeDragging(HWND hwnd);

}  // namespace jux

#endif  // BUILDFLAG(IS_WIN)

#endif  // JUX_CHROME_SUBCLASS_H_
