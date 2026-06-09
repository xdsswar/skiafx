// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxViewsDelegate — production ViewsDelegate for the jux engine.
//
// Overrides OnBeforeWidgetInit to ensure top-level windows use
// DesktopNativeWidgetAura instead of NativeWidgetAura. Without this
// override, Widget::Init DCHECKs on params.parent || params.context
// because NativeWidgetAura requires an aura context for embedding,
// while DesktopNativeWidgetAura creates its own WindowTreeHost.
//
// This is the production equivalent of Chromium's DesktopTestViewsDelegate
// from content_shell, which performs the same native widget selection.

#ifndef JUX_VIEWS_DELEGATE_H_
#define JUX_VIEWS_DELEGATE_H_

#include "ui/views/views_delegate.h"

namespace jux {

class JuxViewsDelegate : public views::ViewsDelegate {
 public:
  JuxViewsDelegate();
  ~JuxViewsDelegate() override;

  JuxViewsDelegate(const JuxViewsDelegate&) = delete;
  JuxViewsDelegate& operator=(const JuxViewsDelegate&) = delete;

  // views::ViewsDelegate override:
  // Ensures top-level windows (TYPE_WINDOW with no parent) use
  // DesktopNativeWidgetAura, which creates its own HWND and
  // WindowTreeHost. Child widgets with a parent use NativeWidgetAura
  // for embedding within an existing aura window tree.
  void OnBeforeWidgetInit(
      views::Widget::InitParams* params,
      views::internal::NativeWidgetDelegate* delegate) override;
};

}  // namespace jux

#endif  // JUX_VIEWS_DELEGATE_H_
