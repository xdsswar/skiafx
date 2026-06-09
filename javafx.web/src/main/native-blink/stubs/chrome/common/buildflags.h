// skia-fx stub: chrome/common/ was pruned from the trimmed Chromium checkout, so
// its generated buildflag_header isn't produced. This provides the chrome/common
// buildflags the ported print-preview sources reference. Values match our minimal
// engine build — chrome features off (which also #if-excludes their extra code
// paths, reducing the ported surface); optimize_webui matches args.gn.
#ifndef CHROME_COMMON_BUILDFLAGS_H_
#define CHROME_COMMON_BUILDFLAGS_H_

#include "build/buildflag.h"

#define BUILDFLAG_INTERNAL_CHROME_ENABLE_LOGGING_BY_DEFAULT() (0)
#define BUILDFLAG_INTERNAL_CHROME_ROOT_STORE_CERT_MANAGEMENT_UI() (0)
#define BUILDFLAG_INTERNAL_SKIP_ANDROID_UNMIGRATED_ACTOR_FILES() (0)
#define BUILDFLAG_INTERNAL_ENABLE_BACKGROUND_MODE() (0)
#define BUILDFLAG_INTERNAL_ENABLE_BACKGROUND_CONTENTS() (0)
#define BUILDFLAG_INTERNAL_ENABLE_CHROME_NOTIFICATIONS() (0)
#define BUILDFLAG_INTERNAL_ENABLE_DOWNGRADE_PROCESSING() (0)
#define BUILDFLAG_INTERNAL_ENABLE_HANGOUT_SERVICES_EXTENSION() (0)
// ENABLE_PDF_SAVE_TO_DRIVE intentionally omitted — it's defined by the real
// pdf buildflags header (defining it here clashes -Wmacro-redefined).
#define BUILDFLAG_INTERNAL_ENABLE_SERVICE_DISCOVERY() (0)
#define BUILDFLAG_INTERNAL_ENABLE_SESSION_SERVICE() (0)
#define BUILDFLAG_INTERNAL_ENABLE_WEBUI_CERTIFICATE_VIEWER() (0)
#define BUILDFLAG_INTERNAL_ENABLE_WEBUI_TAB_STRIP() (0)
#define BUILDFLAG_INTERNAL_OPTIMIZE_WEBUI() (1)

#endif  // CHROME_COMMON_BUILDFLAGS_H_
