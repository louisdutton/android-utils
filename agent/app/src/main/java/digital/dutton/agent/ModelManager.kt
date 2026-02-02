package digital.dutton.agent

import android.content.Context
import java.io.File
import java.io.FileOutputStream

class ModelManager(private val context: Context) {

    private val modelsDir = File(context.filesDir, "models")

    val whisperModelPath: String
        get() = File(modelsDir, WHISPER_MODEL_NAME).absolutePath

    fun isWhisperModelAvailable(): Boolean {
        return File(whisperModelPath).exists()
    }

    fun copyModelsFromAssets(): Boolean {
        modelsDir.mkdirs()

        return try {
            copyAssetToFile(WHISPER_MODEL_NAME)
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

    companion object {
        const val WHISPER_MODEL_NAME = "ggml-tiny.en-q5_1.bin"
    }
}
