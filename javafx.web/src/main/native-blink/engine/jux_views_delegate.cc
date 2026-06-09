// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// Implementation of JuxViewsDelegate — ensures top-level windows use
// DesktopNativeWidgetAura for proper desktop window creation.

#include "jux/jux_views_delegate.h"

#include "ui/views/widget/desktop_aura/desktop_native_widget_aura.h"
#include "ui/views/widget/native_widget_aura.h"

namespace jux {

JuxViewsDelegate::JuxViewsDelegate() = default;
JuxViewsDelegate::~JuxViewsDelegate() = default;

void JuxViewsDelegate::OnBeforeWidgetInit(
    views::Widget::InitParams* params,
    views::internal::NativeWidgetDelegate* delegate) {
  // If a native widget is already specified, don't override it.
  if (params->native_widget) {
    return;
  }

  if (params->parent &&
      params->type != views::Widget::InitParams::TYPE_MENU &&
      params->type != views::Widget::InitParams::TYPE_TOOLTIP) {
    // Child widget — embed in the parent's aura window tree.
    params->native_widget =
        new views::NativeWidgetAura(delegate->AsWidget());
  } else if (!params->parent && !params->context) {
    // Top-level window — create a DesktopNativeWidgetAura with its
    // own HWND and WindowTreeHost.
    params->native_widget =
        new views::DesktopNativeWidgetAura(delegate->AsWidget());
  }
}

}  // namespace jux
