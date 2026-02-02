#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"
#include "common.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static llama_model* g_model = nullptr;
static llama_context* g_context = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_digital_dutton_agent_LlamaLib_initialize(JNIEnv* env, jobject /* this */, jstring modelPath) {
    if (g_model != nullptr) {
        llama_free(g_context);
        llama_free_model(g_model);
        g_context = nullptr;
        g_model = nullptr;
    }

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading llama model from: %s", path);

    llama_model_params model_params = llama_model_default_params();
    g_model = llama_load_model_from_file(path, model_params);

    env->ReleaseStringUTFChars(modelPath, path);

    if (g_model == nullptr) {
        LOGE("Failed to load llama model");
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_threads = 4;

    g_context = llama_new_context_with_model(g_model, ctx_params);

    if (g_context == nullptr) {
        LOGE("Failed to create llama context");
        llama_free_model(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Llama model loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_digital_dutton_agent_LlamaLib_generate(JNIEnv* env, jobject /* this */, jstring prompt, jint maxTokens) {
    if (g_context == nullptr || g_model == nullptr) {
        LOGE("Llama context not initialized");
        return env->NewStringUTF("");
    }

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    LOGI("Generating with prompt: %s", promptStr);

    // Tokenize the prompt
    std::vector<llama_token> tokens(strlen(promptStr) + 1);
    int n_tokens = llama_tokenize(g_model, promptStr, strlen(promptStr), tokens.data(), tokens.size(), true, false);

    env->ReleaseStringUTFChars(prompt, promptStr);

    if (n_tokens < 0) {
        LOGE("Failed to tokenize prompt");
        return env->NewStringUTF("");
    }
    tokens.resize(n_tokens);

    // Clear KV cache
    llama_kv_cache_clear(g_context);

    // Decode prompt
    llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); i++) {
        llama_batch_add(batch, tokens[i], i, {0}, false);
    }
    batch.logits[batch.n_tokens - 1] = true;

    if (llama_decode(g_context, batch) != 0) {
        LOGE("Failed to decode prompt");
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }

    llama_batch_free(batch);

    // Generate tokens
    std::string result;
    int n_cur = tokens.size();

    for (int i = 0; i < maxTokens; i++) {
        float* logits = llama_get_logits_ith(g_context, -1);
        int n_vocab = llama_n_vocab(g_model);

        // Simple greedy sampling
        llama_token new_token = 0;
        float max_logit = logits[0];
        for (int j = 1; j < n_vocab; j++) {
            if (logits[j] > max_logit) {
                max_logit = logits[j];
                new_token = j;
            }
        }

        // Check for EOS
        if (llama_token_is_eog(g_model, new_token)) {
            break;
        }

        // Get token text
        char buf[256];
        int len = llama_token_to_piece(g_model, new_token, buf, sizeof(buf), 0, false);
        if (len > 0) {
            result.append(buf, len);
        }

        // Decode next token
        llama_batch single = llama_batch_init(1, 0, 1);
        llama_batch_add(single, new_token, n_cur, {0}, true);

        if (llama_decode(g_context, single) != 0) {
            llama_batch_free(single);
            break;
        }

        llama_batch_free(single);
        n_cur++;
    }

    LOGI("Generated: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_digital_dutton_agent_LlamaLib_release(JNIEnv* env, jobject /* this */) {
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_free_model(g_model);
        g_model = nullptr;
    }
    LOGI("Llama resources released");
}

} // extern "C"
