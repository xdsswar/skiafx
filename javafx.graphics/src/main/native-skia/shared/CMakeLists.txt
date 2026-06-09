# shared — cross-platform Skia bridge (FFM-callable C ABI).
#
# Pass -DSKIA_HOME=<path> on the CMake command line, or set the
# environment variable SKIA_HOME, to point at a Skia distribution
# providing:
#   ${SKIA_HOME}/include/...           (Skia public headers)
#   ${SKIA_HOME}/out/Release/skia.lib  (Windows static lib)
#       or libskia.a / libskia.so      (Linux/macOS)
#
# When SKIA_HOME is unset the bridge builds in stub mode: the FFM
# entry points still work (raster fallback) so we can verify the
# Java↔native plumbing without a Skia toolchain.

if(NOT DEFINED SKIA_HOME AND DEFINED ENV{SKIA_HOME})
    set(SKIA_HOME "$ENV{SKIA_HOME}")
endif()

set(_OPENJFX_SKIA_SOURCES openjfx_skia_bridge.cpp)
if(WIN32)
    # D3D12 / DXGI / Ganesh integration lives in its own TU so that
    # <d3d12.h>'s macro pollution (interface, small, near, far, …)
    # stays out of the main Skia bridge.
    list(APPEND _OPENJFX_SKIA_SOURCES openjfx_skia_d3d_win.cpp)
    # Phase-3 D3D11 ⇄ GL interop (NV_DX_interop2). Separate TU for
    # the same macro-isolation reason and so it remains buildable
    # without SKIA_HOME (zero Skia dependencies — pure Win32 + GL).
    list(APPEND _OPENJFX_SKIA_SOURCES openjfx_skia_d3d11_interop.cpp)
endif()

add_library(openjfx_skia_shared SHARED ${_OPENJFX_SKIA_SOURCES})

target_include_directories(openjfx_skia_shared
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# Drives the __declspec(dllexport) branch in skia_fx_bridge.h while
# building the lib; consumers (e.g. javafx.web's WebKit) see dllimport
# because they don't define this.
target_compile_definitions(openjfx_skia_shared PRIVATE OPENJFX_BUILDING_SKIA_BRIDGE)

if(SKIA_HOME)
    message(STATUS "Skia integration enabled: SKIA_HOME=${SKIA_HOME}")
    target_compile_definitions(openjfx_skia_shared PRIVATE OPENJFX_WITH_SKIA)
    # SkTypes.h undefines SK_GL / SK_VULKAN / SK_DIRECT3D / etc. unless
    # SK_GANESH (or SK_GRAPHITE) is defined — i.e. those backend gates
    # only stay open when the user has signaled "I'm consuming the
    # Ganesh API surface". Skia's lib is built with Ganesh enabled;
    # we tell the public headers to expose it.
    target_compile_definitions(openjfx_skia_shared PRIVATE SK_GANESH)
    if(WIN32)
        # Unlock the D3D12-flavored Skia API surface (GrDirectContext::
        # MakeDirect3D, the GrBackendRenderTarget(w,h,GrD3DTextureResourceInfo)
        # ctor, etc.) — gated on SK_DIRECT3D AND requires SK_GANESH
        # above (otherwise SkTypes.h undefines it before headers see it).
        target_compile_definitions(openjfx_skia_shared PRIVATE SK_DIRECT3D)
    endif()
    target_include_directories(openjfx_skia_shared PRIVATE
        ${SKIA_HOME}
        ${SKIA_HOME}/include)

    # Skia's official Windows build uses the static MSVC runtime (/MT).
    # Link our library against the same runtime so the C++ stdlib
    # symbols don't collide.
    if(MSVC)
        set_property(TARGET openjfx_skia_shared PROPERTY
            MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    endif()

    find_library(SKIA_LIBRARY
        NAMES skia libskia
        HINTS
            ${SKIA_HOME}/out/Release
            ${SKIA_HOME}/out/Static
            ${SKIA_HOME}/lib
            ${SKIA_HOME})
    if(NOT SKIA_LIBRARY)
        message(FATAL_ERROR
            "SKIA_HOME is set but skia library not found under "
            "${SKIA_HOME}. Looked in out/Release, out/Static, lib/, root.")
    endif()
    # NOTE: link ORDER matters with the MSVC linker — it does not go back to an
    # earlier library to satisfy a symbol introduced by a later one. The SVG
    # module archives below reference core Skia symbols (SkArenaAlloc, SkOSPath,
    # SkStrSearch, …) and expat, so they are listed BEFORE skia.lib; skia.lib is
    # linked last (just before its own freetype/png/zlib companions) so it
    # resolves everyone's core references in a single pass.

    # --- SVG module (modules/svg) ----------------------------------------
    # SvgImage / SvgImageView render through Skia's SVG module, which Skia
    # ships as separate archives (svg + its skshaper / skunicode /
    # skresources / harfbuzz / icu dependencies) that we static-link
    # alongside skia.lib. The `prepareSkiaSvg` build task produces the
    # *_static archives from Skia's compiled objects so `buildAll` is
    # self-contained. When the svg archive is present we define
    # OPENJFX_WITH_SKIA_SVG and the svg_* bridge entry points go live; when
    # absent the bridge still builds and those entry points degrade to
    # no-ops (SvgImage reports a load error and renders nothing — never a
    # link failure, never a crash).
    # Thin, non-component static archives produced by `prepareSkiaSvg`
    # (openjfx_svg / openjfx_skshaper / openjfx_skunicode contain only their
    # module's own objects, so they link cleanly against the core skia.lib
    # without duplicate symbols — unlike gn's merged module libs).
    find_library(SKIA_SVG_LIBRARY
        NAMES openjfx_svg libopenjfx_svg
        HINTS
            ${SKIA_HOME}/out/Release
            ${SKIA_HOME}/out/Static
            ${SKIA_HOME}/lib
            ${SKIA_HOME})
    if(SKIA_SVG_LIBRARY)
        message(STATUS "Skia SVG module enabled: ${SKIA_SVG_LIBRARY}")
        target_compile_definitions(openjfx_skia_shared PRIVATE OPENJFX_WITH_SKIA_SVG)
        target_link_libraries(openjfx_skia_shared PRIVATE ${SKIA_SVG_LIBRARY})
        # svg -> skshaper -> { harfbuzz, skunicode -> icu }; svg also uses
        # skresources. Link every dependency archive we can find — the linker
        # pulls in only the objects actually referenced, so this stays lean.
        foreach(svgdep IN ITEMS openjfx_skshaper openjfx_skunicode skresources
                                harfbuzz icu expat)
            find_library(SKIA_SVGDEP_${svgdep}
                NAMES ${svgdep}
                HINTS
                    ${SKIA_HOME}/out/Release
                    ${SKIA_HOME}/out/Static
                    ${SKIA_HOME}/lib
                    ${SKIA_HOME})
            if(SKIA_SVGDEP_${svgdep})
                target_link_libraries(openjfx_skia_shared PRIVATE
                    ${SKIA_SVGDEP_${svgdep}})
            endif()
        endforeach()
    else()
        message(WARNING
            "Skia SVG module not found under ${SKIA_HOME}; SvgImage/"
            "SvgImageView will render nothing. Run './gradlew prepareSkiaSvg' "
            "(buildAll runs it automatically) to produce the SVG archives.")
    endif()
    # --- end SVG module --------------------------------------------------

    # skia.lib LAST (after the SVG module archives above) so the MSVC linker
    # resolves their core-Skia references — see the ordering note above.
    target_link_libraries(openjfx_skia_shared PRIVATE ${SKIA_LIBRARY})

    if(WIN32)
        # Skia's FreeType-backed font manager (SkFontMgr_New_Custom_Empty,
        # used for SkTextBlob text rendering) pulls in FreeType, which in
        # turn needs libpng + zlib. These ship as separate static archives
        # alongside skia.lib in the Skia build output.
        foreach(companion IN ITEMS freetype2 libpng zlib)
            find_library(SKIA_COMPANION_${companion}
                NAMES ${companion}
                HINTS
                    ${SKIA_HOME}/out/Release
                    ${SKIA_HOME}/out/Static
                    ${SKIA_HOME}/lib
                    ${SKIA_HOME})
            if(SKIA_COMPANION_${companion})
                target_link_libraries(openjfx_skia_shared PRIVATE
                    ${SKIA_COMPANION_${companion}})
            else()
                message(WARNING
                    "Skia companion library '${companion}' not found under "
                    "${SKIA_HOME}; SkTextBlob text rendering may fail to link.")
            endif()
        endforeach()
        # Skia was built with skia_use_direct3d=true and skia_use_vulkan=true,
        # so once Ganesh code is pulled in the linker also pulls D3D12 /
        # Vulkan backend objects from skia.lib. Link the corresponding
        # Windows SDK import libs even though we only use the GL backend —
        # the references come from skia.lib, not our code.
        target_link_libraries(openjfx_skia_shared PRIVATE
            opengl32 gdi32 user32 fontsub usp10
            d3d11 d3d12 d3dcompiler dxgi dxguid)
    elseif(APPLE)
        target_link_libraries(openjfx_skia_shared PRIVATE
            "-framework AppKit" "-framework CoreFoundation"
            "-framework CoreGraphics" "-framework CoreText"
            "-framework Foundation" "-framework Metal"
            "-framework QuartzCore")
    else()
        find_package(Threads REQUIRED)
        target_link_libraries(openjfx_skia_shared PRIVATE
            Threads::Threads dl GL fontconfig freetype)
    endif()
else()
    message(STATUS "Skia integration disabled (set SKIA_HOME to enable).")
endif()
