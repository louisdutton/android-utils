package digital.dutton.agent

object LlamaLib {
    init {
        System.loadLibrary("agent_jni")
    }

    external fun initialize(modelPath: String): Boolean
    external fun generate(prompt: String, maxTokens: Int): String
    external fun release()
}
