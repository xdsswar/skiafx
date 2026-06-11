// ---------------------------------------------------------------------------
// MediaMixerBridge (jfxmedia JNI side) — skia-fx addition.
//
// JNI entry for com.sun.media.jfxmediaimpl.NativeMediaMixer. The actual
// remux engine (openjfx_ffmpeg_remux) lives in fxplugins.dll next to
// the ffmpeg loader; we resolve it at runtime via GetModuleHandle +
// GetProcAddress — the same cross-DLL pattern MediaFfmpegConfig uses.
//
// Threading: nativeRemux is synchronous and runs on the Java worker
// thread MediaMixer spawns; the progress / cancel callbacks therefore
// run on a thread with a valid JNIEnv (the caller's), so no
// AttachCurrentThread dance is needed.
// ---------------------------------------------------------------------------

#include <jni.h>
#include <cstdio>
#include <cstring>

#ifdef _WIN32
#  include <windows.h>

typedef void (*RemuxProgressFn)(double, void*);
typedef int  (*RemuxCancelledFn)(void*);
typedef int  (*RemuxFn)(const char*, const char*, const char*, int,
                        RemuxProgressFn, RemuxCancelledFn, void*,
                        char*, int);

static RemuxFn resolve_remux() {
    static RemuxFn cached = nullptr;
    if (cached) return cached;

    HMODULE mod = GetModuleHandleA("fxplugins.dll");
    if (!mod) mod = GetModuleHandleA("fxplugins");
    if (!mod) {
        // Same-directory load (sdk + dev-tree layouts stage all media
        // natives together).
        char self[MAX_PATH] = {0};
        HMODULE selfMod = nullptr;
        if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS
                | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                (LPCSTR)&resolve_remux, &selfMod)
            && selfMod
            && GetModuleFileNameA(selfMod, self, sizeof(self))) {
            char* slash = strrchr(self, '\\');
            if (slash) {
                strcpy(slash + 1, "fxplugins.dll");
                mod = LoadLibraryA(self);
            }
        }
        if (!mod) mod = LoadLibraryA("fxplugins.dll");
    }
    if (!mod) return nullptr;
    cached = (RemuxFn)GetProcAddress(mod, "openjfx_ffmpeg_remux");
    return cached;
}

namespace {

struct CallbackCtx {
    JNIEnv*   env;
    jobject   self;
    jmethodID midProgress;   // void postProgress(double)
    jmethodID midCancelled;  // boolean isCancelledNative()
};

void bridge_progress(double frac, void* user) {
    CallbackCtx* ctx = (CallbackCtx*)user;
    if (ctx->midProgress) {
        ctx->env->CallVoidMethod(ctx->self, ctx->midProgress, (jdouble)frac);
        if (ctx->env->ExceptionCheck()) ctx->env->ExceptionClear();
    }
}

int bridge_cancelled(void* user) {
    CallbackCtx* ctx = (CallbackCtx*)user;
    if (!ctx->midCancelled) return 0;
    jboolean c = ctx->env->CallBooleanMethod(ctx->self, ctx->midCancelled);
    if (ctx->env->ExceptionCheck()) {
        ctx->env->ExceptionClear();
        return 1; // a broken callback aborts the mix rather than looping
    }
    return c == JNI_TRUE ? 1 : 0;
}

} // namespace
#endif // _WIN32

extern "C" {

// Returns null on success, a human-readable error message on failure.
JNIEXPORT jstring JNICALL
Java_com_sun_media_jfxmediaimpl_NativeMediaMixer_nativeRemux(
    JNIEnv* env, jobject self,
    jstring jAudioPath, jstring jVideoPath, jstring jOutPath, jint jFlags)
{
#ifdef _WIN32
    RemuxFn remux = resolve_remux();
    if (!remux) {
        return env->NewStringUTF(
            "media mixing unavailable: fxplugins.dll / openjfx_ffmpeg_remux not found");
    }
    if (!jAudioPath || !jVideoPath || !jOutPath) {
        return env->NewStringUTF("audio/video/output path missing");
    }

    // Check each conversion before issuing the next JNI call — a NULL
    // return leaves a pending OutOfMemoryError, and any further JNI
    // call with an exception pending is undefined behavior.
    const char* audio = env->GetStringUTFChars(jAudioPath, nullptr);
    if (!audio) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return env->NewStringUTF("string conversion failed");
    }
    const char* video = env->GetStringUTFChars(jVideoPath, nullptr);
    if (!video) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->ReleaseStringUTFChars(jAudioPath, audio);
        return env->NewStringUTF("string conversion failed");
    }
    const char* out = env->GetStringUTFChars(jOutPath, nullptr);
    if (!out) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->ReleaseStringUTFChars(jAudioPath, audio);
        env->ReleaseStringUTFChars(jVideoPath, video);
        return env->NewStringUTF("string conversion failed");
    }

    CallbackCtx ctx = {};
    ctx.env = env;
    ctx.self = self;
    jclass cls = env->GetObjectClass(self);
    if (cls) {
        ctx.midProgress  = env->GetMethodID(cls, "postProgress", "(D)V");
        if (env->ExceptionCheck()) { env->ExceptionClear(); ctx.midProgress = nullptr; }
        ctx.midCancelled = env->GetMethodID(cls, "isCancelledNative", "()Z");
        if (env->ExceptionCheck()) { env->ExceptionClear(); ctx.midCancelled = nullptr; }
        env->DeleteLocalRef(cls);
    }

    char errBuf[512] = {0};
    int rc = remux(audio, video, out, (int)jFlags,
                   bridge_progress, bridge_cancelled, &ctx,
                   errBuf, (int)sizeof(errBuf));

    env->ReleaseStringUTFChars(jAudioPath, audio);
    env->ReleaseStringUTFChars(jVideoPath, video);
    env->ReleaseStringUTFChars(jOutPath, out);

    if (rc == 0) return nullptr;
    return env->NewStringUTF(errBuf[0] ? errBuf : "media mix failed");
#else
    (void)self; (void)jAudioPath; (void)jVideoPath; (void)jOutPath; (void)jFlags;
    return env->NewStringUTF("media mixing is not wired on this platform yet");
#endif
}

} // extern "C"
