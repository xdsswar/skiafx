// skia-fx content-layer stub for the two pdf_extension_util entry points the
// print-preview WebUI uses (print_preview_ui.cc: GetStrings + GetResources to
// seed the embedded PDF viewer's localized strings/resources). The real impl
// (chrome/browser/pdf/pdf_extension_util.cc) drags the extensions + glic +
// mime-handler-view cone, none of which the jux engine builds.
//
// GetStrings stays empty (the viewer falls back to built-in defaults; missing
// $i18n keys are non-fatal via patch 0003). GetResources, however, MUST return
// the real PDF-viewer resource map: print_preview.js imports
// chrome://print/pdf/pdf_scripting_api.js, and if that path isn't registered on
// the data source the module graph aborts and the whole WebUI app never boots
// (page stuck on the grey "loading" skeleton). So we mirror the real
// GetResources, iterating kPdfResources from the generated pdf_resources_map.h.
//
// Only GetStrings/GetResources are defined — the other declarations in the real
// header are never called from the ported print code, so leaving them undefined
// is link-safe.
#include "chrome/browser/pdf/pdf_extension_util.h"

#include <algorithm>
#include <array>
#include <string_view>

#include "base/containers/span.h"
#include "base/notreached.h"
#include "chrome/grit/pdf_resources_map.h"

namespace pdf_extension_util {

base::DictValue GetStrings(PdfViewerContext /*context*/) {
  return base::DictValue();
}

std::vector<webui::ResourcePath> GetResources(PdfViewerContext context) {
  static constexpr auto kExcludeFromPdfViewer =
      std::to_array<std::string_view>({
          "pdf/index_print.html",
          "pdf/main_print.js",
          "pdf/pdf_print_wrapper.js",
      });
  static constexpr auto kExcludeFromPrintPreview =
      std::to_array<std::string_view>({
          "pdf/index.html",
          "pdf/main.js",
          "pdf/pdf_viewer_wrapper.js",
      });
  base::span<const std::string_view> exclusions;
  switch (context) {
    case PdfViewerContext::kPdfViewer:
      exclusions = kExcludeFromPdfViewer;
      break;
    case PdfViewerContext::kPrintPreview:
      exclusions = kExcludeFromPrintPreview;
      break;
    default:
      NOTREACHED();
  }

  std::vector<webui::ResourcePath> resources;
  resources.reserve(std::size(kPdfResources));
  for (const webui::ResourcePath& resource : kPdfResources) {
    if (std::ranges::contains(exclusions, resource.path)) {
      continue;
    }
    resources.push_back(resource);
  }
  return resources;
}

}  // namespace pdf_extension_util
