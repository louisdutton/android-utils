#define LOG_TAG "AssistantIntentModel"

#include <algorithm>
#include <cmath>
#include <limits>
#include <memory>
#include <string>
#include <vector>

#include <jni.h>

#include "defines.h"
#include "ggml/LanguageModel.h"
#include "jni_common.h"
#include "jni_utils.h"

namespace {

struct AssistantIntentModelState {
    std::unique_ptr<LanguageModel> model;
};

float logProbability(const std::vector<float> &logits, int token) {
    if (token < 0 || token >= static_cast<int>(logits.size())) {
        return -std::numeric_limits<float>::infinity();
    }

    float maxLogit = -std::numeric_limits<float>::infinity();
    for (float logit : logits) {
        maxLogit = std::max(maxLogit, logit);
    }

    double sum = 0.0;
    for (float logit : logits) {
        sum += std::exp(static_cast<double>(logit - maxLogit));
    }

    return logits[token] - maxLogit - static_cast<float>(std::log(sum));
}

jlong openModel(JNIEnv *env, jclass, jstring modelPath) {
    const std::string path = jstring2string(env, modelPath);
    if (path.empty()) return 0L;

    llama_backend_init(false);
    auto *languageModel = LlamaAdapter::createLanguageModel(path);
    if (languageModel == nullptr) return 0L;

    auto *state = new AssistantIntentModelState();
    state->model = std::unique_ptr<LanguageModel>(languageModel);
    return reinterpret_cast<jlong>(state);
}

jfloatArray scoreLabels(JNIEnv *env, jclass, jlong handle, jstring prompt, jobjectArray labels) {
    auto *state = reinterpret_cast<AssistantIntentModelState *>(handle);
    const int labelCount = env->GetArrayLength(labels);
    jfloatArray output = env->NewFloatArray(labelCount);
    if (state == nullptr || state->model == nullptr || labelCount <= 0) return output;

    const std::string promptText = jstring2string(env, prompt);
    std::vector<float> scores(labelCount, -std::numeric_limits<float>::infinity());

    state->model->updateContext(promptText.c_str());
    std::vector<float> baseLogits = state->model->infer();

    for (int labelIndex = 0; labelIndex < labelCount; labelIndex++) {
        auto label = static_cast<jstring>(env->GetObjectArrayElement(labels, labelIndex));
        std::string labelText = jstring2string(env, label);
        env->DeleteLocalRef(label);

        token_sequence tokens = state->model->tokenize(labelText.c_str());
        if (tokens.empty()) continue;

        float total = 0.0f;
        std::vector<float> logits = baseLogits;
        token_sequence prefix;

        for (size_t tokenIndex = 0; tokenIndex < tokens.size(); tokenIndex++) {
            total += logProbability(logits, tokens[tokenIndex]);
            prefix.push_back(tokens[tokenIndex]);

            if (tokenIndex + 1 < tokens.size()) {
                logits = state->model->temporarilyInfer(prefix);
            }
        }

        scores[labelIndex] = total / static_cast<float>(tokens.size());
    }

    env->SetFloatArrayRegion(output, 0, labelCount, scores.data());
    return output;
}

void closeModel(JNIEnv *, jclass, jlong handle) {
    auto *state = reinterpret_cast<AssistantIntentModelState *>(handle);
    delete state;
}

static const JNINativeMethod methods[] = {
        {
                const_cast<char *>("openNative"),
                const_cast<char *>("(Ljava/lang/String;)J"),
                reinterpret_cast<void *>(openModel)
        },
        {
                const_cast<char *>("scoreLabelsNative"),
                const_cast<char *>("(JLjava/lang/String;[Ljava/lang/String;)[F"),
                reinterpret_cast<void *>(scoreLabels)
        },
        {
                const_cast<char *>("closeNative"),
                const_cast<char *>("(J)V"),
                reinterpret_cast<void *>(closeModel)
        },
};

} // namespace

int registerAssistantIntentModel(JNIEnv *env) {
    const char *className = "digital/dutton/essentials/assistant/IntentLanguageModel";
    return latinime::registerNativeMethods(env, className, methods, NELEMS(methods));
}
