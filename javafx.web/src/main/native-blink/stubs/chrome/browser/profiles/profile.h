// skia-fx stub: chrome's Profile is deeply coupled to chrome/browser. The
// content-layer engine uses content::BrowserContext directly. This thin Profile
// derives from BrowserContext and exposes only what the ported print code needs
// (grown as the port widens). FromBrowserContext is a static downcast — valid
// once the engine's BrowserContext derives from Profile (wired in M2/M3); for the
// M1 compile/link it only needs to resolve symbolically.
#ifndef CHROME_BROWSER_PROFILES_PROFILE_H_
#define CHROME_BROWSER_PROFILES_PROFILE_H_

#include "content/public/browser/browser_context.h"
#include "content/public/browser/web_contents.h"
#include "content/public/browser/web_ui.h"

class PrefService;

class Profile : public content::BrowserContext {
 public:
  static Profile* FromBrowserContext(content::BrowserContext* browser_context) {
    return static_cast<Profile*>(browser_context);
  }

  // The print-preview WebUI + handler resolve their profile from the WebUI's
  // WebContents browser context.
  static Profile* FromWebUI(content::WebUI* web_ui) {
    return FromBrowserContext(web_ui->GetWebContents()->GetBrowserContext());
  }

  // IsOffTheRecord() is inherited from content::BrowserContext.
  virtual PrefService* GetPrefs() = 0;
};

#endif  // CHROME_BROWSER_PROFILES_PROFILE_H_
