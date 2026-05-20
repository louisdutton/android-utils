package org.futo.voiceinput.shared.ggml

import androidx.annotation.Keep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.Buffer

enum class DecodingMode(val value: Int) {
    Greedy(0),
    BeamSearch5(5)
}

class InferenceCancelledException : Exception()
class InvalidModelException : Exception("The Whisper model could not be loaded")

@Keep
class WhisperGGML(modelBuffer: Buffer) {
    private var handle: Long = 0L
    private var partialResultCallback: (String) -> Unit = {}

    init {
        System.loadLibrary("assistant_whisper")
        handle = openFromBufferNative(modelBuffer)
        if (handle == 0L) throw InvalidModelException()
    }

    @Keep
    private fun invokePartialResult(text: String) {
        partialResultCallback(text.trim())
    }

    @Throws(InferenceCancelledException::class)
    suspend fun infer(
        samples: FloatArray,
        prompt: String,
        languages: Array<String>,
        decodingMode: DecodingMode,
        suppressNonSpeechTokens: Boolean,
        partialResultCallback: (String) -> Unit
    ): String = withContext(Dispatchers.Default) {
        check(handle != 0L) { "WhisperGGML has already been closed" }
        this@WhisperGGML.partialResultCallback = partialResultCallback

        val result = inferNative(
            handle,
            samples,
            prompt,
            languages,
            emptyArray(),
            decodingMode.value,
            suppressNonSpeechTokens
        ).trim()

        if (result.contains("<>CANCELLED<>")) throw InferenceCancelledException()
        result
    }

    fun cancel() {
        if (handle != 0L) cancelNative(handle)
    }

    suspend fun close() = withContext(Dispatchers.Default) {
        if (handle != 0L) closeNative(handle)
        handle = 0L
    }

    private external fun openNative(path: String): Long
    private external fun openFromBufferNative(buffer: Buffer): Long
    private external fun inferNative(
        handle: Long,
        samples: FloatArray,
        prompt: String,
        languages: Array<String>,
        bailLanguages: Array<String>,
        decodingMode: Int,
        suppressNonSpeechTokens: Boolean
    ): String
    private external fun cancelNative(handle: Long)
    private external fun closeNative(handle: Long)
}
