#ifdef UNSAFE_BUFFERS_BUILD
#pragma allow_unsafe_buffers
#endif

// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// jux-engine — thin executable entry point.
//
// This binary serves two roles:
//   1. Browser process: when called with a shared memory path as the first
//      argument (e.g. jux-engine.exe <mmap-path>). Delegates to
//      JuxRunBrowser() in the DLL, which opens shared memory, initializes
//      Chromium, and enters the message loop.
//   2. Child process: when Chromium spawns it with --type=renderer/gpu/utility.
//      Delegates to JuxSubprocessMain() in the DLL.
//
// This executable contains ZERO Chromium dependencies. All Chromium code
// lives exclusively in jux-engine.dll (the shared library). The exe only
// calls exported C functions via the import library. This eliminates
// duplicate //base and //content symbols between the exe and DLL, which
// previously caused child process crashes and forced --single-process mode.

#include <cstdio>
#include <string>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#include <shellapi.h>
#else
#include <cstring>
#include <unistd.h>
#endif

// Only the C API header — no Chromium headers, no base/ includes.
#include "jux/jux_engine_api.h"

// =========================================================================
// Windows entry point
// =========================================================================

#ifdef _WIN32

namespace {

// Crash handler — writes to debug output (not stderr, which would
// trigger AllocConsole and spawn a CMD window).
LONG WINAPI JuxCrashHandler(EXCEPTION_POINTERS* info) {
  char buf[256];
  snprintf(buf, sizeof(buf),
           "[jux-engine] FATAL CRASH: exception 0x%08lX at address %p\n",
           info->ExceptionRecord->ExceptionCode,
           info->ExceptionRecord->ExceptionAddress);
  OutputDebugStringA(buf);
  return EXCEPTION_CONTINUE_SEARCH;
}

// Checks if the process was launched as a Chromium child process.
// Child processes always have a --type= flag on the command line.
bool IsChildProcess() {
  int argc;
  LPWSTR* wargv = CommandLineToArgvW(GetCommandLineW(), &argc);
  if (!wargv) return false;

  bool is_child = false;
  for (int i = 1; i < argc; ++i) {
    if (wcsncmp(wargv[i], L"--type=", 7) == 0) {
      is_child = true;
      break;
    }
  }
  LocalFree(wargv);
  return is_child;
}

}  // namespace

int APIENTRY wWinMain(HINSTANCE instance, HINSTANCE, wchar_t* cmd_line, int) {
  // Detach from any inherited console. Java's ProcessBuilder may pass
  // the JVM's console handles to the engine, and Chromium's child
  // processes (renderer, GPU) would inherit them — showing CMD windows.
  FreeConsole();

  // Install crash handler for diagnostics.
  SetUnhandledExceptionFilter(JuxCrashHandler);

  // Child process mode — route to JuxSubprocessMain in the DLL.
  if (IsChildProcess()) {
    return JuxSubprocessMain(0, nullptr);
  }

  // Browser process mode — extract mmap path from command line AND
  // forward every argv to the DLL so Chromium's CommandLine sees the
  // switches passed from Java's Application.engineSwitches() via
  // ProcessBuilder. On Windows CommandLine::Init re-reads
  // GetCommandLineW() internally, but we still forward argv for parity
  // with POSIX and for future use.
  int argc;
  LPWSTR* wargv = CommandLineToArgvW(GetCommandLineW(), &argc);
  if (!wargv || argc < 2) {
    OutputDebugStringA("[jux-engine] Usage: jux-engine <mmap-path>\n");
    if (wargv) LocalFree(wargv);
    return 1;
  }

  // Convert every arg from UTF-16 to UTF-8. Own the storage here so
  // the const char* pointers remain valid for the entire JuxRunBrowser
  // call — Chromium will copy them internally into its CommandLine
  // singleton during JuxInit.
  std::vector<std::string> argv_utf8;
  argv_utf8.reserve(argc);
  for (int i = 0; i < argc; ++i) {
    int wlen = lstrlenW(wargv[i]);
    int u8_bytes = WideCharToMultiByte(
        CP_UTF8, 0, wargv[i], wlen, nullptr, 0, nullptr, nullptr);
    std::string s(static_cast<size_t>(u8_bytes), '\0');
    if (u8_bytes > 0) {
      WideCharToMultiByte(CP_UTF8, 0, wargv[i], wlen,
                          s.data(), u8_bytes, nullptr, nullptr);
    }
    argv_utf8.push_back(std::move(s));
  }
  LocalFree(wargv);

  std::vector<const char*> argv_ptrs;
  argv_ptrs.reserve(argv_utf8.size());
  for (const auto& s : argv_utf8) argv_ptrs.push_back(s.c_str());

  // Run the browser process — blocks until shutdown. argv[1] is the
  // mmap path; pass it separately for convenience but also leave it in
  // the argv vector since Chromium simply ignores loose positional args.
  return JuxRunBrowser(argv_utf8[1].c_str(),
                        static_cast<int>(argv_ptrs.size()),
                        argv_ptrs.data());
}

// =========================================================================
// POSIX entry point
// =========================================================================

#else

int main(int argc, const char** argv) {
  // Check if this is a Chromium child process (--type= flag).
  for (int i = 1; i < argc; ++i) {
    if (strncmp(argv[i], "--type=", 7) == 0) {
      return JuxSubprocessMain(argc, argv);
    }
  }

  // Browser process mode.
  if (argc < 2) {
    fprintf(stderr, "[jux-engine] Usage: jux-engine <mmap-path>\n");
    return 1;
  }

  // Forward the full argv to the DLL so switches passed from Java's
  // Application.engineSwitches() via ProcessBuilder reach Chromium's
  // CommandLine. On POSIX this is the only path — CommandLine::Init
  // does not auto-read /proc/self/cmdline.
  return JuxRunBrowser(argv[1], argc, argv);
}

#endif
