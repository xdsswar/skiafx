// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxRendererClient — ContentRendererClient implementation.
//
// Creates a JuxDomHandlerImpl for every RenderFrame so the browser can
// drive Blink's DOM via the jux DOM Mojo pipe. The handler's lifetime
// is managed by the RenderFrame itself (JuxDomHandlerImpl is a
// RenderFrameObserver that self-destructs on OnDestruct).

#include "jux/jux_renderer_client.h"

#include "content/public/renderer/render_frame.h"
#include "jux/jux_dom_handler_impl.h"

#include "printing/buildflags/buildflags.h"
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
#include <cstdlib>
#include <memory>

#include "base/logging.h"
#include "components/pdf/common/constants.h"
#include "components/printing/renderer/print_render_frame_helper.h"
#include "jux/jux_pdf_plugin_factory.h"
#include "third_party/blink/public/web/web_element.h"
#include "third_party/blink/public/web/web_local_frame.h"
#include "third_party/blink/public/web/web_plugin_params.h"
#endif

namespace jux {

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
namespace {

// Minimal content-layer delegate for PrintRenderFrameHelper. The jux engine has
// no PDF-extension viewer in the renderer, so GetPdfElement returns an empty
// element (the "print an already-open PDF plugin" path doesn't apply); print
// preview is always enabled; nothing overrides the default print flow.
class JuxPrintRenderFrameHelperDelegate
    : public printing::PrintRenderFrameHelper::Delegate {
 public:
  JuxPrintRenderFrameHelperDelegate() = default;
  ~JuxPrintRenderFrameHelperDelegate() override = default;

  blink::WebElement GetPdfElement(blink::WebLocalFrame* /*frame*/) override {
    return blink::WebElement();
  }
  bool IsPrintPreviewEnabled() override { return true; }
  bool OverridePrint(blink::WebLocalFrame* /*frame*/) override { return false; }
};

}  // namespace
#endif  // BUILDFLAG(ENABLE_PRINT_PREVIEW)

JuxRendererClient::JuxRendererClient() = default;
JuxRendererClient::~JuxRendererClient() = default;

void JuxRendererClient::RenderFrameCreated(content::RenderFrame* render_frame) {
  // Attach a DOM handler to the frame. The handler registers itself as a
  // RenderFrameObserver and deletes itself in OnDestruct(), so we don't
  // retain a pointer here.
  new JuxDomHandlerImpl(render_frame);

#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  // PrintRenderFrameHelper drives window.print()/Ctrl+P: it sends the scripted
  // print-preview request to the browser-side PrintViewManager (which routes to
  // PrintPreviewDialogController). Self-owned RenderFrameObserver.
  new printing::PrintRenderFrameHelper(
      render_frame, std::make_unique<JuxPrintRenderFrameHelperDelegate>());
#endif
}

bool JuxRendererClient::OverrideCreatePlugin(
    content::RenderFrame* render_frame,
    const blink::WebPluginParams& params,
    blink::WebPlugin** plugin) {
#if BUILDFLAG(ENABLE_PRINT_PREVIEW)
  // The chrome://print PDF viewer embeds <embed type="application/x-google-chrome-
  // pdf"> to display the generated preview PDF. Create the internal Pepper-free
  // PDF plugin (PdfViewWebPlugin) for it. Allowed origins: the print-preview WebUI
  // and the untrusted PDF stream host.
  if (params.mime_type.Utf8() == pdf::kInternalPluginMimeType) {
    // Render the print-preview document with our minimal in-renderer PDFium
    // plugin (JuxPdfPlugin). The upstream pdf::CreateInternalPlugin is OOPIF-only
    // (hard-CHECKs IsPdfRenderer() + parent_frame->IsWebRemoteFrame()), which our
    // lean engine — extensions/guest_view OFF, no dedicated PDF process — cannot
    // satisfy (it aborts the renderer). JuxPdfPlugin sidesteps all of that: Blink
    // streams the chrome-untrusted://print/.../print.pdf bytes (served by
    // PrintPreviewUIUntrusted) into it via DidReceiveData, and it rasterizes the
    // page with chrome_pdf::RenderPDFPageToBitmap and paints it. No OOPIF, no
    // extensions, no crash. See jux_pdf_plugin.{h,cc}.
    VLOG(1) << "[jux-pdf] OverrideCreatePlugin: creating JuxPdfPlugin url="
              << params.url.GetString().Utf8();
    *plugin = CreateJuxPdfPlugin(params.url);
    return true;
  }
#endif
  return false;
}

}  // namespace jux
