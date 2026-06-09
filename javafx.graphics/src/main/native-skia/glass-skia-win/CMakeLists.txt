# glass-skia-win — Windows Glass→SkSurface plumbing.
#
# HWND → DXGI swap chain → SkSurface (Ganesh D3D backend) is the long-
# term path. Phase-1 stub builds an empty shared library that simply
# links against openjfx_skia_shared so the project layout is complete.

add_library(openjfx_glass_skia_win SHARED
    glass_skia_win.cpp
)

target_link_libraries(openjfx_glass_skia_win PRIVATE
    openjfx_skia_shared
    user32 gdi32)
