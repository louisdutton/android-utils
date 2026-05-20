#define LOG_TAG "AssistantWhisper"

#include <jni.h>
#include "defines.h"
#include "jni_common.h"
#include "org_futo_voiceinput_WhisperGGML.h"

int registerAssistantIntentModel(JNIEnv *env);

jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        AKLOGE("GetEnv failed");
        return -1;
    }

    if (!voiceinput::register_WhisperGGML(env)) {
        AKLOGE("WhisperGGML native registration failed");
        return -1;
    }

    if (!registerAssistantIntentModel(env)) {
        AKLOGE("Assistant intent model native registration failed");
        return -1;
    }

    return JNI_VERSION_1_6;
}

namespace latinime {
int registerNativeMethods(JNIEnv *env, const char *const className, const JNINativeMethod *methods,
        const int numMethods) {
    jclass clazz = env->FindClass(className);
    if (!clazz) {
        AKLOGE("Native registration unable to find class '%s'", className);
        return JNI_FALSE;
    }
    if (env->RegisterNatives(clazz, methods, numMethods) != 0) {
        AKLOGE("RegisterNatives failed for '%s'", className);
        env->DeleteLocalRef(clazz);
        return JNI_FALSE;
    }
    env->DeleteLocalRef(clazz);
    return JNI_TRUE;
}
}
