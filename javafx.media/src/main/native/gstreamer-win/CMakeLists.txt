# ---------------------------------------------------------------------------
# javafx.media native build — Windows platform group.
#
# Builds the 4 DLLs that make up the GStreamer-Lite + jfxmedia stack:
#   1. glib-lite.dll       — GLib + libffi (foundation, no deps)
#   2. gstreamer-lite.dll  — Core GStreamer + plugins (depends on glib-lite)
#   3. fxplugins.dll       — JFX-specific GStreamer plugins, DirectShow,
#                            Media Foundation wrappers (depends on both above)
#   4. jfxmedia.dll        — JNI bridge (depends on all three + JDK)
#
# Each subdir translates the corresponding upstream Makefile recipe
# (gstreamer/projects/win/<name>/Makefile, jfxmedia/projects/win/Makefile)
# into modern CMake. The Makefiles remain in tree as the recipe
# reference for future updates.
# ---------------------------------------------------------------------------

add_subdirectory(glib-lite)
add_subdirectory(gstreamer-lite)
add_subdirectory(fxplugins)
add_subdirectory(jfxmedia)
