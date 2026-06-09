# native-iio — JavaFX image I/O native (libjpeg-turbo plus jpegloader.c
# JNI shim). Pure C.

cmake_minimum_required(VERSION 3.28)
project(openjfx_javafx_iio LANGUAGES C)

find_package(JNI REQUIRED)

if(MSVC)
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

# Bundled libjpeg sources — files are listed by upstream's build but we
# enumerate them via glob since libjpeg's own configuration system is
# upstream-managed and stable across versions.
file(GLOB LIBJPEG_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/libjpeg/*.c")
# Drop only the alternate memory backends and the CLI driver/example
# files. Keep jmemnobs.c — it provides jpeg_get_small / jpeg_free_small
# / jpeg_mem_init etc. which jmemmgr.c references.
list(FILTER LIBJPEG_SOURCES EXCLUDE REGEX ".*/(jmemansi|jmemname|jmemdos|jmemmac|cjpeg|djpeg|jpegtran|rdjpgcom|wrjpgcom|ckconfig|example)\\.c$")

set(IIO_SOURCES
    jpegloader.c
    ${LIBJPEG_SOURCES}
)

add_library(javafx_iio SHARED ${IIO_SOURCES})

target_include_directories(javafx_iio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/libjpeg
    ${JNI_INCLUDE_DIRS}
)
if(DEFINED OPENJFX_JNI_HEADERS_DIR)
    target_include_directories(javafx_iio PRIVATE ${OPENJFX_JNI_HEADERS_DIR})
endif()

set_target_properties(javafx_iio PROPERTIES
    OUTPUT_NAME javafx_iio
    PREFIX ""
    C_STANDARD 99
)
