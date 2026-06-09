# glass-skia-linux — Linux Glass→SkSurface plumbing (X11 / Wayland).

add_library(openjfx_glass_skia_linux SHARED
    glass_skia_linux.cpp
)

target_link_libraries(openjfx_glass_skia_linux PRIVATE
    openjfx_skia_shared)
