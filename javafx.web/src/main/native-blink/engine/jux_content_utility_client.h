// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxContentUtilityClient — registers the services the jux engine needs running
// in a utility process.
//
// content's default ContentUtilityClient registers NONE of chrome's services, so
// without this the chrome PrintingService never launches and print preview's
// pages-per-sheet (N-up) conversion — PdfNupConverterClient::DoNupPdfConvert →
// GetPrintingService()->BindPdfNupConverter() — silently fails. See CLAUDE.md and
// jux_main_delegate.cc (CreateContentUtilityClient).

#ifndef JUX_CONTENT_UTILITY_CLIENT_H_
#define JUX_CONTENT_UTILITY_CLIENT_H_

#include "content/public/utility/content_utility_client.h"

namespace jux {

class JuxContentUtilityClient : public content::ContentUtilityClient {
 public:
  JuxContentUtilityClient();
  ~JuxContentUtilityClient() override;

  JuxContentUtilityClient(const JuxContentUtilityClient&) = delete;
  JuxContentUtilityClient& operator=(const JuxContentUtilityClient&) = delete;

  // content::ContentUtilityClient:
  void RegisterMainThreadServices(mojo::ServiceFactory& services) override;
};

}  // namespace jux

#endif  // JUX_CONTENT_UTILITY_CLIENT_H_
