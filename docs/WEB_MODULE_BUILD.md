# Building javafx.web

> **⚠️ SUPERSEDED (2026-05-30).** `javafx.web` no longer uses WebKit. It is
> being rebuilt on the **Blink/Chromium content layer** (`skia-fx-webview`
> engine). The current architecture + build is documented in
> [`BLINK_INTEGRATION.md`](BLINK_INTEGRATION.md); the build tasks are
> `seedChromium` / `configureBuild` / `buildNatives` (group `chromium`), not
> the WebKit `compileWebNative` flow below. The WebKit native tree is retained
> for reference until Blink reaches parity, but is no longer built.
>
> The full Blink build guide (prerequisites, IntelliJ, troubleshooting) will
> replace the rest of this file at P5. Everything below describes the **old
> WebKit build** and is kept only for historical reference.

---

# Building javafx.web (WebKit, Skia-direct) — RETIRED

This document tracks the build setup for the `javafx.web` module on
branch `web-module`. The end goal is a WebKit native build that draws
directly into our native Skia pipeline (`openjfx_skia_shared`), with
zero intermediate textures between WebKit's `SkCanvas` and the scene's
`SkSurface`. Architecture details live in the plan file; this doc is
the *how to build it* reference.

## Status

| Phase | What | State |
|---|---|---|
| 1 | Native bridge: typed C++ accessors in `openjfx_skia_shared` | DONE |
| 2 | WebKit native build wired into Gradle, USE_SKIA=ON | IN PROGRESS |
| 3 | NGWebView passes scene `SkSurface*` into WebKit | pending |
| 4 | WebKit C++ paint ops → direct `SkCanvas->draw*` | pending |
| 5 | `ImageBufferJavaBackend` → GPU `SkSurface` | pending |
| 6 | Java `com.sun.javafx.webkit.prism.*` rewrite in place | pending |
| 7 | Remove `PlatformContextJava` + `RenderingQueue` paint paths | pending |
| 8 | Docs finalise | pending |

## Toolchain prerequisites

WebKit's build is heavier than the rest of the project. On every
platform we drive it through `Tools/Scripts/build-webkit` (the
upstream WebKit perl harness) from Gradle.

### Common

| Tool | Min version | Why |
|---|---|---|
| Java | 25 | Toolchain in use across the project |
| CMake | 3.20 | WebKit's `Source/CMakeLists.txt` minimum |
| Ninja | 1.11 | Build driver for generated CMake project |
| Python | 3.10 | WebKit generated-sources scripts |
| Perl | 5.30 | `build-webkit` script + ICU build helpers |
| Ruby | 3.0 | WebKit DOM bindings generator |

### Windows

| Tool | Where to install from |
|---|---|
| Visual Studio 2022 (any edition) | Microsoft. Provides Windows SDK + `link.exe` + `vcvars64.bat`. |
| LLVM clang-cl 17+ | `winget install LLVM.LLVM` — adds `clang-cl.exe` to `C:\Program Files\LLVM\bin`. Add that directory to `PATH`. |
| Ruby with DevKit | `winget install RubyInstallerTeam.RubyWithDevKit.3.3`. |
| MSYS2 Perl | comes with Git for Windows (`C:\msys64\usr\bin\perl.exe`) or install MSYS2 standalone. |

WebKit upstream **dropped pure-MSVC support**: on Windows the C++
compiler must be `clang-cl` (Clang frontend, MSVC ABI) and the linker
is MSVC's `link.exe`. `cl.exe` rejects WebKit's source. clang-cl needs
the VS environment (`INCLUDE` / `LIB` / `LIBPATH`) at build time. We
auto-bootstrap it via `vswhere.exe` (ships with VS) and run
`vcvars64.bat` inside the build task.

### Linux

Distro packages on Ubuntu/Debian:

```bash
sudo apt install clang libicu-dev libxml2-dev libxslt1-dev \
                 libsqlite3-dev zlib1g-dev libssl-dev \
                 libfontconfig1-dev libcairo2-dev libpng-dev \
                 ruby perl python3 ninja-build cmake
```

`build-webkit` autodetects `gcc` or `clang` (clang preferred). No
vcvars-equivalent shenanigans needed; the system's compiler env is
already correct.

### macOS

```bash
xcode-select --install        # brings Apple's Clang
brew install ninja cmake ruby # Perl + Python come with macOS
```

The toolchain is Apple's `clang` + `ld`. `build-webkit` sets
`CMAKE_OSX_DEPLOYMENT_TARGET` and `CMAKE_OSX_SYSROOT` automatically.

## Build-time environment

| Variable | Set by | Default |
|---|---|---|
| `SKIA_HOME` | you (or `-PskiaHome=`) | required for the Skia integration |
| `JAVA_HOME` | the Gradle daemon's JVM | passed through to `build-webkit` |
| `WEBKIT_OUTPUTDIR` | the convention plugin | `<module>/build/native/webkit-<host>-<arch>` |

## Commands

WebKit's native build is opt-in (it adds a long step to the build).

```bash
# Build everything including WebKit native (Windows / Linux / macOS):
./gradlew assemble -PbuildWebNative=true

# Just the web module's natives:
./gradlew :javafx.web:compileWebNative

# Clean only the WebKit output (without nuking the whole build/):
./gradlew :javafx.web:cleanWebNative
```

The first WebKit build is slow (hundreds of generated source files +
WTF, JavaScriptCore, WebCore, WebKitLegacy each as a static lib +
final `jfxwebkit` shared lib). Incremental rebuilds after a one-line
C++ change are fast (single TU recompile + link).

## Output layout

Per host platform:

```
javafx.web/build/native/webkit-<host>-<arch>/
├── bin/                       # Windows: jfxwebkit.dll, DumpRenderTreeJava.dll
├── lib/                       # Linux/macOS equivalents (.so / .dylib)
├── DerivedSources/            # Generated C++ from .idl / .json
├── ForwardingHeaders/         # WebKit's forwarded include layout
├── WTF/, JavaScriptCore/, WebCore/, WebKitLegacy/   # static libs + objs
├── DumpRenderTree/            # only when -PbuildWebNative=true … (test runner)
└── icu/                       # ICU intermediates
```

The final `jfxwebkit.dll` / `libjfxwebkit.so` / `libjfxwebkit.dylib`
gets copied to the module's standard native output dir and packaged
into the `javafx.web` jar at the jar root, where
`com.sun.glass.utils.NativeLibLoader` picks it up. Same convention as
`javafx_font.dll`, `glass.dll`, `openjfx_skia_shared.dll`.

## Skia bridge integration

The native build receives these CMake variables (added by the
convention plugin):

| CMake var | Value |
|---|---|
| `SKIA_FX_HOME` | `$SKIA_HOME` |
| `SKIA_FX_BRIDGE_INCLUDE` | `javafx.graphics/src/main/native-skia/shared/include` |
| `SKIA_FX_BRIDGE_LIB_DIR` | `javafx.graphics/build/native/<host>-<arch>/lib` |

WebCore links against `openjfx_skia_shared` (the .lib from our graphics
module's native build) and includes `skia_fx_bridge.h` to obtain a
`SkSurface*` / `SkCanvas*` from a handle issued by the Java side.
There is one Skia, one `GrDirectContext`, one handle namespace across
the whole process — see `javafx.graphics/src/main/native-skia/shared/include/skia_fx_bridge.h`
for the typed C++ accessors.

## Troubleshooting

**`clang-cl: command not found` on Windows.** The LLVM installer's
silent mode doesn't add to PATH. Add `C:\Program Files\LLVM\bin` to
your user PATH (System Properties → Environment Variables → User →
PATH), restart the shell, retry.

**`fatal error LNK1104: cannot open file 'kernel32.lib'` on Windows.**
The VS environment didn't load. Confirm Visual Studio 2022 is
installed and `vswhere.exe` finds it:
```
"C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe" -latest -property installationPath
```

**`Can't locate File/Find.pm` on Windows.** The perl on PATH is the
stripped-down Git-for-Windows perl, not full MSYS2. Set PATH so that
`C:\msys64\usr\bin\perl.exe` (or Strawberry Perl) wins over Git's
`/usr/bin/perl`.

**`No such file or directory: ruby` on Linux.** Some distros split
ruby and `ruby-devel` packages. Install both.

**ICU mismatch.** WebKit bundles its own ICU sources under
`Source/ThirdParty/icu/`. Don't try to point it at a system ICU.

**Incremental rebuild does everything again.** Check that
`WEBKIT_OUTPUTDIR` is consistent across invocations (printed by
`build-webkit` on each run). If Gradle's task isn't tracking inputs
correctly, the convention plugin may be re-creating the output dir.

## What's NOT built

- WebKit's WebDriver, WebInspectorUI, WebKit2 — disabled in
  `OptionsJava.cmake`.
- `DumpRenderTree` — built only when `-PbuildWebDRT=true` is added
  (default off). The DRT test harness is large and irrelevant to
  scene-graph rendering.
- WebRTC, ANGLE, WebGL, WebAssembly, WebCrypto — disabled.
- Accelerated compositing layers (`TextureMapper` GraphicsLayer
  hierarchy) — code present but unused for the Java port.
