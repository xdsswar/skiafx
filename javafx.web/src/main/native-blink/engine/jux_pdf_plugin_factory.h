// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// Lightweight factory for the in-renderer print-preview PDF plugin.
//
// JuxPdfPlugin pulls gin (via chrome_pdf::PostMessageReceiver) through its header,
// which the Blink-GC clang plugin rejects. So JuxPdfPlugin is compiled in its own
// source_set WITHOUT the Blink-GC plugin, and Blink-GC-scoped callers (e.g.
// jux_renderer_client.cc) create it through this header instead of including the
// full plugin definition.
#ifndef JUX_JUX_PDF_PLUGIN_FACTORY_H_
#define JUX_JUX_PDF_PLUGIN_FACTORY_H_

namespace blink {
class WebPlugin;
class WebURL;
}  // namespace blink

namespace jux {

// Creates the in-renderer PDF plugin for the print-preview document `url`
// (chrome-untrusted://print/<id>/<page>/print.pdf). Ownership passes to Blink.
blink::WebPlugin* CreateJuxPdfPlugin(const blink::WebURL& url);

}  // namespace jux

#endif  // JUX_JUX_PDF_PLUGIN_FACTORY_H_
