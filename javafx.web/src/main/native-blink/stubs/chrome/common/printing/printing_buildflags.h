// skia-fx stub for the generated chrome/common/printing/printing_buildflags.h
// (buildflag_header target in chrome/common/printing/BUILD.gn). It declares a
// single flag, PRINT_MEDIA_L10N_ENABLED. We set it to 0: the print-media (paper
// size) name localization data (print_media_l10n.cc + its generated map) is not
// built, so paper sizes show their non-localized names. All PRINT_MEDIA_L10N
// usage in printer_capabilities.cc is guarded by this flag, so 0 compiles that
// cone out cleanly. Flip to 1 + build print_media_l10n.cc if localized paper
// names are needed later.
#ifndef CHROME_COMMON_PRINTING_PRINTING_BUILDFLAGS_H_
#define CHROME_COMMON_PRINTING_PRINTING_BUILDFLAGS_H_

#include "build/buildflag.h"

#define BUILDFLAG_INTERNAL_PRINT_MEDIA_L10N_ENABLED() (0)

#endif  // CHROME_COMMON_PRINTING_PRINTING_BUILDFLAGS_H_
