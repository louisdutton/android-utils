package digital.dutton.agent

import android.content.Context
import java.io.File
import java.io.FileOutputStream

class ModelManager(private val context: Context) {

    private val modelsDir = File(context.filesDir, "models")

    val whisperModelPath: String
        get() = File(modelsDir, WHISPER_MODEL_NAME).absolutePath

    val llamaModelPath: String
        get() = File(modelsDir, LLAMA_MODEL_NAME).absolutePath

    fun isWhisperModelAvailable(): Boolean {
        return File(whisperModelPath).exists()
    }

    fun isLlamaModelAvailable(): Boolean {
        return File(llamaModelPath).exists()
    }

    fun areModelsAvailable(): Boolean {
        return isWhisperModelAvailable() && isLlamaModelAvailable()
    }

    fun copyModelsFromAssets(): Boolean {
        modelsDir.mkdirs()

        return try {
            copyAssetToFile(WHISPER_MODEL_NAME) && copyAssetToFile(LLAMA_MODEL_NAME)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun copyAssetToFile(assetName: String): Boolean {
        val outFile = File(modelsDir, assetName)
        if (outFile.exists()) return true

        return try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // For development: allow setting model paths directly
    fun setModelPathsFromExternal(whisperPath: String?, llamaPath: String?) {
        modelsDir.mkdirs()

        whisperPath?.let { path ->
            val src = File(path)
            if (src.exists()) {
                src.copyTo(File(whisperModelPath), overwrite = true)
            }
        }

        llamaPath?.let { path ->
            val src = File(path)
            if (src.exists()) {
                src.copyTo(File(llamaModelPath), overwrite = true)
            }
        }
    }

    companion object {
        const val WHISPER_MODEL_NAME = "ggml-tiny.en-q5_1.bin"
        const val LLAMA_MODEL_NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    }
}
