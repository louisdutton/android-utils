#include <jni.h>
#include <string>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static whisper_context* g_context = nullptr;

extern "C" {

JNIEXPORT jstring JNICALL
Java_digital_dutton_agent_WhisperLib_hello(JNIEnv* env, jobject /* this */) {
    LOGI("Hello from JNI!");
    return env->NewStringUTF("Hello from native code!");
}

JNIEXPORT jboolean JNICALL
Java_digital_dutton_agent_WhisperLib_initialize(JNIEnv* env, jobject /* this */, jstring modelPath) {
    LOGI("initialize() called");

    if (g_context != nullptr) {
        LOGI("Freeing existing context");
        whisper_free(g_context);
        g_context = nullptr;
    }

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Model path: %s", path);

    LOGI("Creating default params...");
    whisper_context_params cparams = whisper_context_default_params();

    LOGI("Calling whisper_init_from_file_with_params...");
    g_context = whisper_init_from_file_with_params(path, cparams);

    env->ReleaseStringUTFChars(modelPath, path);

    if (g_context == nullptr) {
        LOGE("Failed to initialize whisper context");
        return JNI_FALSE;
    }

    LOGI("Whisper model loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_digital_dutton_agent_WhisperLib_transcribe(JNIEnv* env, jobject /* this */, jfloatArray audioData) {
    if (g_context == nullptr) {
        LOGE("Whisper context not initialized");
        return env->NewStringUTF("");
    }

    jsize length = env->GetArrayLength(audioData);
    jfloat* data = env->GetFloatArrayElements(audioData, nullptr);

    LOGI("Transcribing %d samples", length);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_special = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.single_segment = true;
    params.language = "en";

    int result = whisper_full(g_context, params, data, length);

    env->ReleaseFloatArrayElements(audioData, data, 0);

    if (result != 0) {
        LOGE("Whisper transcription failed with code: %d", result);
        return env->NewStringUTF("");
    }

    int numSegments = whisper_full_n_segments(g_context);
    std::string text;

    for (int i = 0; i < numSegments; i++) {
        const char* segmentText = whisper_full_get_segment_text(g_context, i);
        text += segmentText;
    }

    LOGI("Transcription: %s", text.c_str());
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_digital_dutton_agent_WhisperLib_release(JNIEnv* env, jobject /* this */) {
    if (g_context != nullptr) {
        whisper_free(g_context);
        g_context = nullptr;
        LOGI("Whisper context released");
    }
}

} // extern "C"
