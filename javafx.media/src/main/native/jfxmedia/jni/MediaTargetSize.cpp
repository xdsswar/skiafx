// ---------------------------------------------------------------------------
// MediaTargetSize — cross-DLL shared "current MediaView size" hint.
//
// NGMediaView knows the on-screen size of the video node. The producer
// side (mfwrapper, in fxplugins.dll) wants that information so it can
// downscale the GPU output texture to the actual display size and skip
// pushing unused pixels. Since fxplugins and jfxmedia are separate
// DLLs but live in the same process, we expose the two ints from
// jfxmedia.dll via __declspec(dllexport) and let mfwrapper resolve
// them at runtime via GetModuleHandle + GetProcAddress.
//
// Two ints, atomic, lock-free. Updated whenever NGMediaView renders
// with a changed bounds; sampled per frame by mfwrapper. Defaults to
// zero (= "no hint, use source dimensions").
// ---------------------------------------------------------------------------

#include <atomic>
#include <jni.h>

#ifdef _WIN32
#  define OPENJFX_MEDIA_EXPORT __declspec(dllexport)
#else
#  define OPENJFX_MEDIA_EXPORT __attribute__((visibility("default")))
#endif

namespace {
    std::atomic<int> g_targetWidth{0};
    std::atomic<int> g_targetHeight{0};
}

extern "C" {

OPENJFX_MEDIA_EXPORT void openjfx_media_get_target_size(int* w, int* h) {
    if (w) *w = g_targetWidth.load(std::memory_order_acquire);
    if (h) *h = g_targetHeight.load(std::memory_order_acquire);
}

OPENJFX_MEDIA_EXPORT void openjfx_media_set_target_size(int w, int h) {
    g_targetWidth.store(w, std::memory_order_release);
    g_targetHeight.store(h, std::memory_order_release);
}

// JNI entry called from NGMediaView whenever its on-screen rect
// changes. Cheap (two atomic stores), safe to call every render pulse;
// callers should still gate on "actually changed" to avoid noise.
JNIEXPORT void JNICALL
Java_com_sun_media_jfxmediaimpl_MediaTargetSize_nativeSet(
    JNIEnv* env, jclass cls, jint w, jint h)
{
    (void)env; (void)cls;
    openjfx_media_set_target_size((int)w, (int)h);
}

} // extern "C"
