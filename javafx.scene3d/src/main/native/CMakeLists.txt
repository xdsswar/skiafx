# openjfx_model3d — native glTF 2.0 parser for the javafx.scene3d module.
#
# Self-contained: depends only on cgltf (vendored single header) and the C++
# standard library. NO Skia, NO bgfx, NO D3D, NO JNI — it is reached purely via
# FFM (java.lang.foreign) by com.sun.javafx.model3d.NativeModelBridge, so there
# are no generated JNI headers to consume either.
#
# Cross-platform: builds identically on Windows (MSVC), Linux (GCC/Clang) and
# macOS (Clang). The native-conventions Gradle plugin drives this via CMake and
# packs the resulting lib (+ a SHA-256 checksums.properties) at the jar root,
# from where NativeLibLoader extracts it on first use — same path every other
# skia-fx native lib follows.

cmake_minimum_required(VERSION 3.28)

project(openjfx_model3d_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Honor MSVC_RUNTIME_LIBRARY target properties (CMake policy CMP0091).
cmake_policy(SET CMP0091 NEW)

# Centralize output so the Gradle plugin + Java locator can predict the path:
# every config (incl. the Visual Studio multi-config generator's Release) lands
# in <build>/lib, which is exactly what native-conventions scans + packs.
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")
foreach(cfg IN ITEMS DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_${cfg} "${CMAKE_BINARY_DIR}/lib")
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY_${cfg} "${CMAKE_BINARY_DIR}/lib")
    set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY_${cfg} "${CMAKE_BINARY_DIR}/lib")
endforeach()

add_library(openjfx_model3d SHARED openjfx_model3d_bridge.cpp)

target_include_directories(openjfx_model3d PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

# Match the static MSVC runtime used elsewhere in the project so the lib has no
# external CRT DLL dependency at extract-and-load time.
if(MSVC)
    set_property(TARGET openjfx_model3d PROPERTY
        MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    # cgltf uses standard C string/stdio; silence MSVC's CRT-secure warnings.
    target_compile_definitions(openjfx_model3d PRIVATE _CRT_SECURE_NO_WARNINGS)
endif()
