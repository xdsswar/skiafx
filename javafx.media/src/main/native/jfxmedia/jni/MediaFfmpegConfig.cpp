// ---------------------------------------------------------------------------
// MediaFfmpegConfig (jfxmedia JNI side)
//
// Java calls into jfxmedia.dll via this JNI bridge. The actual ffmpeg
// loader (openjfx_ffmpeg_loader_init) lives in fxplugins.dll which is
// in the same process but a different DLL. We resolve the loader entry
// point at runtime via GetModuleHandle + GetProcAddress — same pattern
// as the existing MediaTargetSize bridge.
//
// Why DLL-to-DLL? fxplugins.dll is loaded by GStreamer-Lite as a
// plugin; jfxmedia.dll doesn't link it. Cross-DLL data sharing through
// the OS module table is the cheapest option.
// ---------------------------------------------------------------------------

#include <jni.h>
#include <cstdio>
#include <cstdlib>     // _putenv_s on Windows, setenv/unsetenv on POSIX
#include <cstring>

#ifdef _WIN32
#  include <windows.h>
#endif

#ifdef _WIN32
typedef bool (*OpenJfxFfmpegLoaderInitFn)(const char*);

// Resolve the loader entry point from fxplugins.dll. Application.init()
// runs BEFORE GStreamer loads fxplugins as a plugin (that happens at
// first Media construction), so on the first call we must force-load
// fxplugins ourselves. LoadLibrary refcounts internally — a later
// gst_init() loading the same DLL is fine.
static OpenJfxFfmpegLoaderInitFn resolve_loader_init() {
    static OpenJfxFfmpegLoaderInitFn cached = nullptr;
    if (cached) return cached;

    HMODULE mod = GetModuleHandleA("fxplugins.dll");
    if (!mod) mod = GetModuleHandleA("fxplugins");
    if (!mod) {
        // Look in the same directory as jfxmedia.dll — that's where
        // the sdk + dev-tree layouts both stage native libs.
        char self[MAX_PATH] = {0};
        HMODULE selfMod = nullptr;
        if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS
                | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                (LPCSTR)&resolve_loader_init, &selfMod)
            && selfMod
            && GetModuleFileNameA(selfMod, self, sizeof(self))) {
            char* slash = strrchr(self, '\\');
            if (slash) {
                strcpy(slash + 1, "fxplugins.dll");
                mod = LoadLibraryA(self);
            }
        }
        if (!mod) {
            // Last resort: PATH search.
            mod = LoadLibraryA("fxplugins.dll");
        }
    }
    if (!mod) return nullptr;
    cached = (OpenJfxFfmpegLoaderInitFn)
        GetProcAddress(mod, "openjfx_ffmpeg_loader_init");
    return cached;
}
#endif

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_sun_media_jfxmediaimpl_MediaFfmpegConfig_nativeInit(
    JNIEnv* env, jclass cls, jstring jUserDir)
{
    (void)cls;
#ifdef _WIN32
    OpenJfxFfmpegLoaderInitFn init = resolve_loader_init();
    if (!init) return JNI_FALSE;  // fxplugins not loaded yet or no ffmpeg build

    const char* dir = nullptr;
    if (jUserDir != nullptr) {
        dir = env->GetStringUTFChars(jUserDir, nullptr);
    }
    bool ok = init(dir);
    if (dir) env->ReleaseStringUTFChars(jUserDir, dir);
    return ok ? JNI_TRUE : JNI_FALSE;
#else
    (void)env; (void)jUserDir;
    return JNI_FALSE;
#endif
}

// ---------------------------------------------------------------------------
// nativeSetEnv — sets a process-level environment variable from Java.
//
// Java's standard library has no portable setenv. The skia-fx pipeline
// needs one specifically so the high-level
// {@link javafx.application.Application#setDecodeMethod} can propagate
// CPU mode down to native gstreamer plugins (ffmpegwrapper /
// mfwrapper / dshowwrapper) that read OPENJFX_MEDIA_USE_HWACCEL via
// getenv() at decoder open time.
//
// Called from {@link MediaFfmpegConfig#initialize} BEFORE the first
// decoder element is constructed, so the new env value is in place
// when ffmpegwrapper / mfwrapper getenv it.
//
// Behaviour: a NULL or empty value REMOVES the variable from the
// environment (so callers can "reset" it), matching the conventional
// setenv(name, "", 1) vs unsetenv(name) distinction on POSIX.
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_sun_media_jfxmediaimpl_MediaFfmpegConfig_nativeSetEnv(
    JNIEnv* env, jclass cls, jstring jName, jstring jValue)
{
    (void)cls;
    if (jName == nullptr) return;
    const char* name = env->GetStringUTFChars(jName, nullptr);
    if (name == nullptr) return;

    const char* value = nullptr;
    if (jValue != nullptr) {
        value = env->GetStringUTFChars(jValue, nullptr);
    }

#ifdef _WIN32
    // Two writes are needed on Windows. _putenv_s updates the C
    // runtime's env block, which is what getenv() reads. But each
    // DLL can have its own CRT instance (linker config + msvcrt
    // version), so _putenv_s in jfxmedia.dll's CRT doesn't always
    // propagate to fxplugins.dll's CRT — and ffmpegwrapper's
    // getenv() lives there.
    //
    // SetEnvironmentVariableA writes to the OS-level process env
    // block (the same block CreateProcess inherits to children).
    // This is the canonical write: every DLL in the process can
    // read it back via GetEnvironmentVariableA regardless of which
    // CRT it links.
    //
    // _putenv_s additionally updates *this* DLL's (jfxmedia's) CRT
    // env block so a getenv() inside jfxmedia agrees. It does NOT
    // necessarily propagate to fxplugins.dll's CRT — each DLL can
    // snapshot its own env block at load. That's deliberately fine
    // here: the real reader, ffmpegwrapper (in fxplugins.dll),
    // reads OPENJFX_MEDIA_USE_HWACCEL via GetEnvironmentVariableA
    // (the live OS block this call just updated), with getenv() only
    // as a non-Windows fallback. So no CRT-refresh trick is needed —
    // the OS-block write below is what the producer actually sees.
    BOOL osOk = SetEnvironmentVariableA(name, (value && *value) ? value : nullptr);
    int crtOk = _putenv_s(name, value ? value : "");
    // Quiet by default. Set SKIA_MEDIA_DEBUG=1 in the env to surface
    // the setenv trace when something looks wrong.
    if (getenv("SKIA_MEDIA_DEBUG")) {
        fprintf(stderr,
            "[MediaFfmpegConfig.cpp] setEnv name=%s value=%s "
            "(SetEnvironmentVariableA=%d _putenv_s=%d)\n",
            name, value ? value : "(null)", (int)osOk, crtOk);
        fflush(stderr);
    }
    (void)osOk; (void)crtOk;
#else
    int rc;
    if (value && *value) {
        rc = setenv(name, value, 1);
    } else {
        rc = unsetenv(name);
    }
    if (getenv("SKIA_MEDIA_DEBUG")) {
        fprintf(stderr,
            "[MediaFfmpegConfig.cpp] setEnv name=%s value=%s (rc=%d)\n",
            name, value ? value : "(null)", rc);
        fflush(stderr);
    }
    (void)rc;
#endif

    env->ReleaseStringUTFChars(jName, name);
    if (value) env->ReleaseStringUTFChars(jValue, value);
}

} // extern "C"
