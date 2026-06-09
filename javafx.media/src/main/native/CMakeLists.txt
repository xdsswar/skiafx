# ---------------------------------------------------------------------------
# javafx.media native build — root dispatcher.
#
# Selects the host platform's subdirectory; each platform group builds
# its own set of DLLs/dylibs/sos:
#   Windows: glib-lite.dll, gstreamer-lite.dll, fxplugins.dll, jfxmedia.dll
#   macOS:   (TBD — gstreamer-mac/ uses Makefiles; not wired yet)
#   Linux:   (TBD — gstreamer-linux/ uses Makefiles; not wired yet)
#
# Outputs land in build/native/<platform>-<arch>/lib/, picked up by the
# Gradle convention plugin and packaged into the module jar.
# ---------------------------------------------------------------------------
cmake_minimum_required(VERSION 3.28)
project(javafx_media C CXX)

# Standard CMake niceties.
set(CMAKE_C_STANDARD   11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Output everything into <build>/lib so the Gradle convention plugin's
# jar-packaging step (which globs **/*.dll under lib/) picks it up.
# On the VS multi-config generator we must set the per-config dir too.
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)
foreach(cfg ${CMAKE_CONFIGURATION_TYPES})
    string(TOUPPER ${cfg} CFG_UPPER)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_${CFG_UPPER} ${CMAKE_BINARY_DIR}/lib)
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY_${CFG_UPPER} ${CMAKE_BINARY_DIR}/lib)
    set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY_${CFG_UPPER} ${CMAKE_BINARY_DIR}/lib)
endforeach()

if(WIN32)
    add_subdirectory(gstreamer-win)
elseif(APPLE)
    message(STATUS "javafx.media: macOS native build not yet wired (Phase 1.7+)")
elseif(UNIX)
    message(STATUS "javafx.media: Linux native build not yet wired (Phase 1.8+)")
endif()
