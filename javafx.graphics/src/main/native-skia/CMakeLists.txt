cmake_minimum_required(VERSION 3.28)

# Detect Objective-C++ availability before declaring it as a project
# language. macOS has it via Apple Clang; Linux/Windows compilers don't.
project(openjfx_skia_native LANGUAGES CXX)

if(APPLE)
    enable_language(OBJCXX)
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Honor MSVC_RUNTIME_LIBRARY target properties (CMake policy CMP0091).
cmake_policy(SET CMP0091 NEW)

# Centralize output dir so Java's locator can predict the path.
# Multi-config generators (Visual Studio) append the config to the
# directory by default; we override per-config so all variants land
# in the same `lib/` regardless of generator.
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")
foreach(cfg IN ITEMS DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_${cfg} "${CMAKE_BINARY_DIR}/lib")
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY_${cfg} "${CMAKE_BINARY_DIR}/lib")
    set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY_${cfg} "${CMAKE_BINARY_DIR}/lib")
endforeach()

# Shared cross-platform plumbing — built always.
add_subdirectory(shared)

# Per-platform Glass→SkSurface integration — only the host's variant.
if(WIN32)
    add_subdirectory(glass-skia-win)
elseif(APPLE)
    add_subdirectory(glass-skia-mac)
else()
    add_subdirectory(glass-skia-linux)
endif()

# 3D pipeline (bgfx-backed) — separate lib openjfx_skia_3d so the bgfx
# dependency is isolated from the 2D bridge. Windows-first; Linux
# (Vulkan/GL) and macOS (native Metal) extension points live in
# skia3d/CMakeLists.txt but aren't wired yet. See docs/3D.md.
if(WIN32)
    add_subdirectory(skia3d)
endif()

# ----------------------------------------------------------------------------
# Upstream natives — Glass (windowing), font, IIO. The Prism backend
# natives (prism_sw, prism_d3d, prism_es2, prism_mtl) have been removed
# from openjfx-skia: Skia replaces all of them via SkiaPresentable's
# four-tier allocation (D3D12 swap-chain → GL window-bound →
# off-screen GPU + readback → CPU SkSurface raster).
#
# Each subdir below has its own CMakeLists.txt; they're added per-host
# only when the relevant native source was copied in.
# ----------------------------------------------------------------------------
set(_OPENJFX_UPSTREAM_NATIVES_DIR "${CMAKE_CURRENT_LIST_DIR}/..")

if(WIN32 AND EXISTS "${_OPENJFX_UPSTREAM_NATIVES_DIR}/native-glass/win/CMakeLists.txt")
    add_subdirectory("${_OPENJFX_UPSTREAM_NATIVES_DIR}/native-glass/win"
                     "${CMAKE_BINARY_DIR}/upstream-glass-win")
endif()

if(EXISTS "${_OPENJFX_UPSTREAM_NATIVES_DIR}/native-font/CMakeLists.txt")
    add_subdirectory("${_OPENJFX_UPSTREAM_NATIVES_DIR}/native-font"
                     "${CMAKE_BINARY_DIR}/upstream-font")
endif()

if(EXISTS "${_OPENJFX_UPSTREAM_NATIVES_DIR}/native-iio/CMakeLists.txt")
    add_subdirectory("${_OPENJFX_UPSTREAM_NATIVES_DIR}/native-iio"
                     "${CMAKE_BINARY_DIR}/upstream-iio")
endif()
