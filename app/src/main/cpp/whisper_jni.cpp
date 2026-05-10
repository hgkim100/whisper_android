// JNI bridge between Kotlin (com.hgkim.whisperandroid.whisper.WhisperJni)
// and whisper.cpp. Three entrypoints: init / transcribe / release.
//
// Design notes (see docs/IMPLEMENTATION_NOTES.md §3):
//   - language is hard-coded to "en" (single-language English-only app).
//   - context handle is returned as jlong (raw whisper_context*).
//   - blocking; callers wrap in Dispatchers.Default coroutine.

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "whisper.h"

#define LOG_TAG "whisper_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hgkim_whisperandroid_whisper_WhisperJni_init(
    JNIEnv* env, jclass /*clazz*/, jstring modelPath) {

    if (modelPath == nullptr) {
        LOGE("init: modelPath is null");
        return 0L;
    }

    const char* path_c = env->GetStringUTFChars(modelPath, nullptr);
    if (path_c == nullptr) {
        LOGE("init: GetStringUTFChars returned null");
        return 0L;
    }

    whisper_context_params cparams = whisper_context_default_params();
    // Mobile: keep things simple — no GPU.
    cparams.use_gpu = false;

    whisper_context* ctx = whisper_init_from_file_with_params(path_c, cparams);
    env->ReleaseStringUTFChars(modelPath, path_c);

    if (ctx == nullptr) {
        LOGE("init: whisper_init_from_file_with_params returned null");
        return 0L;
    }

    LOGI("init: whisper context loaded, ptr=%p", (void*)ctx);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_hgkim_whisperandroid_whisper_WhisperJni_transcribe(
    JNIEnv* env, jclass /*clazz*/, jlong ctxHandle, jfloatArray pcm) {

    if (ctxHandle == 0L) {
        LOGE("transcribe: ctx handle is 0");
        return env->NewStringUTF("");
    }
    if (pcm == nullptr) {
        LOGE("transcribe: pcm array is null");
        return env->NewStringUTF("");
    }

    auto* ctx = reinterpret_cast<whisper_context*>(ctxHandle);

    const jsize n = env->GetArrayLength(pcm);
    if (n <= 0) {
        LOGW("transcribe: empty pcm input");
        return env->NewStringUTF("");
    }

    jfloat* samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) {
        LOGE("transcribe: GetFloatArrayElements returned null");
        return env->NewStringUTF("");
    }

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.language         = "en";   // English-only (see §A)
    wparams.translate        = false;
    wparams.print_progress   = false;
    wparams.print_realtime   = false;
    wparams.print_timestamps = false;
    wparams.print_special    = false;
    wparams.no_context       = true;
    wparams.single_segment   = false;

    const int rc = whisper_full(ctx, wparams,
                                static_cast<const float*>(samples),
                                static_cast<int>(n));

    // JNI_ABORT — read-only access, don't copy back.
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);

    if (rc != 0) {
        LOGE("transcribe: whisper_full failed rc=%d", rc);
        return env->NewStringUTF("");
    }

    const int n_seg = whisper_full_n_segments(ctx);
    std::string out;
    out.reserve(256);
    for (int i = 0; i < n_seg; ++i) {
        const char* seg = whisper_full_get_segment_text(ctx, i);
        if (seg != nullptr) {
            out.append(seg);
        }
    }

    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_hgkim_whisperandroid_whisper_WhisperJni_release(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong ctxHandle) {

    if (ctxHandle == 0L) {
        LOGW("release: ctx handle is 0, nothing to do");
        return;
    }
    auto* ctx = reinterpret_cast<whisper_context*>(ctxHandle);
    whisper_free(ctx);
    LOGI("release: whisper context freed, ptr=%p", (void*)ctx);
}

} // extern "C"
