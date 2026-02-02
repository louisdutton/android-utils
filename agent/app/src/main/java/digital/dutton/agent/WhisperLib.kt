package digital.dutton.agent

object WhisperLib {
    init {
        System.loadLibrary("agent_jni")
    }

    external fun initialize(modelPath: String): Boolean
    external fun transcribe(audioData: FloatArray): String
    external fun release()
}
