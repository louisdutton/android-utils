package digital.dutton.essentials.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SpeechUnavailableException(message: String) : RuntimeException(message)

object LocalSpeechTranscriber {
    fun isAvailable(context: Context): Boolean {
        return SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ||
            context.packageManager.hasSystemFeature("android.hardware.microphone")
    }

    suspend fun transcribe(context: Context, allowPlatform: Boolean = true): String {
        if (!allowPlatform || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return WhisperSpeechTranscriber.transcribe(context)
        }

        return transcribeWithPlatform(context)
    }

    private suspend fun transcribeWithPlatform(context: Context): String = withContext(Dispatchers.Main.immediate) {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            throw SpeechUnavailableException("On-device speech recognition is unavailable.")
        }

        suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            var completed = false

            fun finish(block: () -> Unit) {
                if (completed) return
                completed = true
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
                block()
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onError(error: Int) {
                    finish {
                        continuation.resumeWithException(
                            SpeechUnavailableException(errorMessage(error))
                        )
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                        .trim()

                    finish {
                        if (text.isBlank()) {
                            continuation.resumeWithException(
                                SpeechUnavailableException("No speech was recognized.")
                            )
                        } else {
                            continuation.resume(text)
                        }
                    }
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

            continuation.invokeOnCancellation {
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
            }

            recognizer.startListening(intent)
        }
    }

    private fun errorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio capture failed."
            SpeechRecognizer.ERROR_CLIENT -> "Speech recognition was cancelled."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
            SpeechRecognizer.ERROR_NETWORK -> "Speech recognition requires unavailable network support."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition timed out."
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is already running."
            SpeechRecognizer.ERROR_SERVER -> "Speech recognition service failed."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was heard."
            else -> "Speech recognition failed."
        }
    }
}
