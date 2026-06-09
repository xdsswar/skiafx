# native-font — JavaFX font integration. Per-platform: directwrite on
# Windows, coretext on macOS, pango/freetype on Linux. Plus shared
# fontpath / dfontdecoder C sources.

cmake_minimum_required(VERSION 3.28)
project(openjfx_javafx_font LANGUAGES C CXX)

find_package(JNI REQUIRED)

if(MSVC)
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

set(FONT_SOURCES
    fontpath.c
    dfontdecoder.c
)

if(WIN32)
    list(APPEND FONT_SOURCES directwrite.cpp)
elseif(APPLE)
    list(APPEND FONT_SOURCES coretext.c MacFontFinder.c)
else()
    list(APPEND FONT_SOURCES freetype.c pango.c fontpath_linux.c)
endif()

add_library(javafx_font SHARED ${FONT_SOURCES})

target_include_directories(javafx_font PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${JNI_INCLUDE_DIRS}
)
if(DEFINED OPENJFX_JNI_HEADERS_DIR)
    target_include_directories(javafx_font PRIVATE ${OPENJFX_JNI_HEADERS_DIR})
endif()

if(WIN32)
    target_link_libraries(javafx_font PRIVATE
        advapi32 gdi32 user32 dwrite d2d1 windowscodecs ole32)
endif()

set_target_properties(javafx_font PROPERTIES
    OUTPUT_NAME javafx_font
    PREFIX ""
    CXX_STANDARD 17
    C_STANDARD 99
)
