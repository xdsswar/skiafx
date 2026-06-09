# skia3d — Skia 3D integration via bgfx (Windows-first).
#
# Builds the SEPARATE shared library `openjfx_skia_3d`, isolating the
# bgfx dependency from the production 2D bridge (openjfx_skia_shared).
# It ships at the JAR root like every other native lib (the
# native-conventions plugin packs build/native/<plat>/lib/*.dll and
# checksums them — no plugin change needed).
#
# bgfx is consumed via OPENJFX_BGFX_DIR (a bkaradzic/bgfx.cmake tree
# fetched by skiafx.bgfx-conventions). When that dir is absent the lib
# still builds as a stub (OPENJFX_SKIA3D_HAVE_BGFX undefined) so a
# bgfx-less build never breaks.
#
# Cross-platform note: only added on WIN32 today (see ../CMakeLists.txt).
# Linux (Vulkan/GL) and macOS (native Metal) drop in by adding their own
# resource-creation TU + Skia GrBackendTexture variant; the bgfx
# PlatformData device-share shape is identical.

# bgfx calls only. ALL D3D12 resource creation + Skia (GrBackendTexture /
# BorrowTextureFrom) work lives in openjfx_skia_shared so there is exactly
# ONE Skia + GrDirectContext per process; skia3d reaches it via the
# exported skia_fx::d3d12_* accessors.
set(_SKIA3D_SOURCES openjfx_skia3d_bridge.cpp)

add_library(openjfx_skia_3d SHARED ${_SKIA3D_SOURCES})

target_include_directories(openjfx_skia_3d PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../shared
    ${CMAKE_CURRENT_SOURCE_DIR}/../shared/include)

# skia_fx_bridge.h transitively includes Skia's SkRefCnt.h (sk_sp<> in
# register_image's signature). We need that header path only — NOT Skia's
# Ganesh API and NOT skia.lib (no Skia symbols are referenced here).
if(SKIA_HOME)
    target_include_directories(openjfx_skia_3d PRIVATE
        ${SKIA_HOME} ${SKIA_HOME}/include)
endif()

# Match Skia's static MSVC runtime (/MT) on our lib AND on every bgfx
# target created below. CMP0091 is NEW (root CMakeLists), so setting the
# variable before add_subdirectory makes bgfx/bx/bimg inherit it — this
# avoids the classic /MT vs /MD link mismatch with Skia.
if(MSVC)
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    set_property(TARGET openjfx_skia_3d PROPERTY
        MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

# bgfx (bkaradzic/bgfx.cmake). Build only the static library — no
# examples, no tools yet (shaderc is enabled in Phase 3 when we add the
# cube shaders). EXCLUDE_FROM_ALL so bgfx only builds because we link it.
if(DEFINED OPENJFX_BGFX_DIR AND EXISTS "${OPENJFX_BGFX_DIR}/CMakeLists.txt")
    message(STATUS "skia3d: bgfx from ${OPENJFX_BGFX_DIR}")
    set(BGFX_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(BGFX_BUILD_TOOLS    ON  CACHE BOOL "" FORCE)  # need shaderc + bin2c
    set(BGFX_LIBRARY_TYPE   "STATIC" CACHE STRING "" FORCE)
    add_subdirectory("${OPENJFX_BGFX_DIR}"
                     "${CMAKE_BINARY_DIR}/bgfx-build" EXCLUDE_FROM_ALL)
    target_link_libraries(openjfx_skia_3d PRIVATE bgfx bx bimg)
    target_compile_definitions(openjfx_skia_3d PRIVATE OPENJFX_SKIA3D_HAVE_BGFX)

    # Compile the cube shaders to embedded C headers (D3D11 SM5 bytecode).
    # Output: ${_SKIA3D_SHADER_OUT}/dxbc/{vs,fs}_cube.sc.bin.h with bin2c
    # arrays vs_cube_dxbc / fs_cube_dxbc.
    include("${OPENJFX_BGFX_DIR}/cmake/bgfxToolUtils.cmake")
    set(_SKIA3D_SHADER_OUT "${CMAKE_CURRENT_BINARY_DIR}/generated_shaders")
    bgfx_compile_shaders(
        TYPE VERTEX
        SHADERS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/vs_cube.sc"
        VARYING_DEF "${CMAKE_CURRENT_SOURCE_DIR}/shaders/varying.def.sc"
        OUTPUT_DIR "${_SKIA3D_SHADER_OUT}"
        OUT_FILES_VAR _SKIA3D_VS_OUT
        INCLUDE_DIRS "${OPENJFX_BGFX_DIR}/bgfx/src"
        AS_HEADERS
        PROFILES s_5_0)
    bgfx_compile_shaders(
        TYPE FRAGMENT
        SHADERS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/fs_cube.sc"
        VARYING_DEF "${CMAKE_CURRENT_SOURCE_DIR}/shaders/varying.def.sc"
        OUTPUT_DIR "${_SKIA3D_SHADER_OUT}"
        OUT_FILES_VAR _SKIA3D_FS_OUT
        INCLUDE_DIRS "${OPENJFX_BGFX_DIR}/bgfx/src"
        AS_HEADERS
        PROFILES s_5_0)
    # Phong shaders for the real scene-graph path (Door 1). Note the
    # separate varying def: pos + texcoord0 + tangent(=normal quaternion).
    bgfx_compile_shaders(
        TYPE VERTEX
        SHADERS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/vs_phong.sc"
        VARYING_DEF "${CMAKE_CURRENT_SOURCE_DIR}/shaders/varying_phong.def.sc"
        OUTPUT_DIR "${_SKIA3D_SHADER_OUT}"
        OUT_FILES_VAR _SKIA3D_VSP_OUT
        INCLUDE_DIRS "${OPENJFX_BGFX_DIR}/bgfx/src"
        AS_HEADERS
        PROFILES s_5_0)
    bgfx_compile_shaders(
        TYPE FRAGMENT
        SHADERS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/fs_phong.sc"
        VARYING_DEF "${CMAKE_CURRENT_SOURCE_DIR}/shaders/varying_phong.def.sc"
        OUTPUT_DIR "${_SKIA3D_SHADER_OUT}"
        OUT_FILES_VAR _SKIA3D_FSP_OUT
        INCLUDE_DIRS "${OPENJFX_BGFX_DIR}/bgfx/src"
        AS_HEADERS
        PROFILES s_5_0)
    # Shadow-map depth pass (render scene depth from the light's POV → R32F map).
    bgfx_compile_shaders(
        TYPE VERTEX
        SHADERS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/vs_shadow.sc"
        VARYING_DEF "${CMAKE_CURRENT_SOURCE_DIR}/shaders/varying_shadow.def.sc"
        OUTPUT_DIR "${_SKIA3D_SHADER_OUT}"
        OUT_FILES_VAR _SKIA3D_VSS_OUT
        INCLUDE_DIRS "${OPENJFX_BGFX_DIR}/bgfx/src"
        AS_HEADERS
        PROFILES s_5_0)
    bgfx_compile_shaders(
        TYPE FRAGMENT
        SHADERS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/fs_shadow.sc"
        VARYING_DEF "${CMAKE_CURRENT_SOURCE_DIR}/shaders/varying_shadow.def.sc"
        OUTPUT_DIR "${_SKIA3D_SHADER_OUT}"
        OUT_FILES_VAR _SKIA3D_FSS_OUT
        INCLUDE_DIRS "${OPENJFX_BGFX_DIR}/bgfx/src"
        AS_HEADERS
        PROFILES s_5_0)

    target_sources(openjfx_skia_3d PRIVATE
        ${_SKIA3D_VS_OUT} ${_SKIA3D_FS_OUT} ${_SKIA3D_VSP_OUT} ${_SKIA3D_FSP_OUT}
        ${_SKIA3D_VSS_OUT} ${_SKIA3D_FSS_OUT})
    target_include_directories(openjfx_skia_3d PRIVATE "${_SKIA3D_SHADER_OUT}")
    target_compile_definitions(openjfx_skia_3d PRIVATE OPENJFX_SKIA3D_HAVE_SHADERS)
else()
    message(WARNING
        "skia3d: OPENJFX_BGFX_DIR unset/not found — building the 3D stub "
        "(no bgfx). Run :javafx.graphics:fetchBgfx or set -PbgfxDir.")
endif()

# The 2D bridge provides the skia_fx::* accessors (device/queue/context,
# register_image, surface composite). Link it; the OS resolves the
# already-resident module at runtime (NativeBridge3D loads it first).
target_link_libraries(openjfx_skia_3d PRIVATE openjfx_skia_shared)
if(WIN32)
    target_link_libraries(openjfx_skia_3d PRIVATE d3d12 dxgi dxguid)
endif()
