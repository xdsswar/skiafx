// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxRendererClient — minimal ContentRendererClient for the renderer process.
//
// The content layer requires a non-null ContentRendererClient in the
// renderer process. RenderThreadImpl calls GetContentClient()->renderer()
// during initialization and dereferences it without null-checking.
// Without this, the renderer crashes immediately on startup.
//
// Additionally, this client attaches a per-frame JuxDomHandlerImpl so
// the browser side can call DOM commands (tree walk, manipulation,
// event listener registration) via Mojo.

#ifndef JUX_RENDERER_CLIENT_H_
#define JUX_RENDERER_CLIENT_H_

#include "content/public/renderer/content_renderer_client.h"

namespace blink {
class WebPlugin;
struct WebPluginParams;
}  // namespace blink

namespace jux {

class JuxRendererClient : public content::ContentRendererClient {
 public:
  JuxRendererClient();
  ~JuxRendererClient() override;

  JuxRendererClient(const JuxRendererClient&) = delete;
  JuxRendererClient& operator=(const JuxRendererClient&) = delete;

  // content::ContentRendererClient overrides:
  void RenderFrameCreated(content::RenderFrame* render_frame) override;

  // Routes the chrome://print PDF viewer's <embed application/x-google-chrome-pdf>
  // to the internal PDF plugin (PdfViewWebPlugin) so the generated preview PDF
  // renders. Returns false for everything else (default plugin handling).
  bool OverrideCreatePlugin(content::RenderFrame* render_frame,
                            const blink::WebPluginParams& params,
                            blink::WebPlugin** plugin) override;
};

}  // namespace jux

#endif  // JUX_RENDERER_CLIENT_H_
