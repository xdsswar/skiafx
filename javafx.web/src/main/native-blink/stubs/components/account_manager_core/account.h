// Stub — jux-engine build. No Google account manager.
#ifndef COMPONENTS_ACCOUNT_MANAGER_CORE_ACCOUNT_H_
#define COMPONENTS_ACCOUNT_MANAGER_CORE_ACCOUNT_H_
#include <string>
namespace account_manager {
struct Account {
  std::string id;
  std::string email;
  enum Type { kUnknown = 0, kGaia = 1 };
  Type type = kUnknown;
};
}
#endif
