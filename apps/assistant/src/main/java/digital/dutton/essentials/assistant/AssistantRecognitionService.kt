package digital.dutton.essentials.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AssistantRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeJob: Job? = null

    override fun onStartListening(intent: Intent?, listener: Callback?) {
        activeJob?.cancel()
        listener?.readyForSpeech(Bundle.EMPTY)
        listener?.beginningOfSpeech()
        activeJob = scope.launch {
            runCatching {
                LocalSpeechTranscriber.transcribe(this@AssistantRecognitionService, allowPlatform = false)
            }.onSuccess { text ->
                listener?.endOfSpeech()
                listener?.results(Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                })
            }.onFailure {
                listener?.endOfSpeech()
                listener?.error(SpeechRecognizer.ERROR_NO_MATCH)
            }
        }
    }

    override fun onCancel(listener: Callback?) {
        activeJob?.cancel()
        activeJob = null
    }

    override fun onStopListening(listener: Callback?) {
        // Recording ends automatically on silence or at the maximum capture window.
    }

    override fun onDestroy() {
        activeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
