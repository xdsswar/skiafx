# glass-skia-mac — macOS Glass→SkSurface plumbing (NSView + CAMetalLayer).

add_library(openjfx_glass_skia_mac SHARED
    glass_skia_mac.mm
)

target_link_libraries(openjfx_glass_skia_mac PRIVATE
    openjfx_skia_shared
    "-framework AppKit"
    "-framework Metal"
    "-framework QuartzCore")
