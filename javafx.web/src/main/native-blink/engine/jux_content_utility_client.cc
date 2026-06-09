// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// See jux_content_utility_client.h.

#include "jux/jux_content_utility_client.h"

#include <memory>

#include "mojo/public/cpp/bindings/service_factory.h"

#if defined(SFXWEB_ENABLE_PRINT_PREVIEW)
#include "chrome/services/printing/printing_service.h"
#include "chrome/services/printing/public/mojom/printing_service.mojom.h"
#include "components/services/print_compositor/print_compositor_impl.h"
#include "components/services/print_compositor/public/mojom/print_compositor.mojom.h"
#include "content/public/utility/utility_thread.h"
#include "mojo/public/cpp/bindings/pending_receiver.h"
#endif

namespace jux {

#if defined(SFXWEB_ENABLE_PRINT_PREVIEW)
namespace {
// Hosts PdfNupConverter (pages-per-sheet) + the other print utilities. Mirrors
// chrome/utility/services.cc RunPrintingService.
auto RunPrintingService(
    mojo::PendingReceiver<printing::mojom::PrintingService> receiver) {
  return std::make_unique<printing::PrintingService>(std::move(receiver));
}

// Hosts the PrintCompositor — composites a document's pages into a single PDF.
// Required by print_to_pdf::PdfPrintJob (WebEngine.print()/print(location)).
auto RunPrintCompositor(
    mojo::PendingReceiver<printing::mojom::PrintCompositor> receiver) {
  return std::make_unique<printing::PrintCompositorImpl>(
      std::move(receiver), /*initialize_environment=*/true,
      content::UtilityThread::Get()->GetIOTaskRunner());
}
}  // namespace
#endif

JuxContentUtilityClient::JuxContentUtilityClient() = default;
JuxContentUtilityClient::~JuxContentUtilityClient() = default;

void JuxContentUtilityClient::RegisterMainThreadServices(
    mojo::ServiceFactory& services) {
#if defined(SFXWEB_ENABLE_PRINT_PREVIEW)
  services.Add(RunPrintingService);
  services.Add(RunPrintCompositor);
#endif
}

}  // namespace jux
