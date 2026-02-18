package digital.dutton.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AgentVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    companion object {
        private const val TAG = "AgentSession"
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())

    private var statusText: TextView? = null
    private var intentResolver: IntentResolver? = null
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreateContentView(): View {
        val layout = FrameLayout(context).apply {
            setBackgroundColor(0xDD000000.toInt())
            setPadding(48, 48, 48, 48)
        }

        statusText = TextView(context).apply {
            text = "Initializing..."
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }

        layout.addView(statusText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        return layout
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(TAG, "Session shown")

        intentResolver = IntentResolver(context)

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            updateStatus("Speech recognition not available")
            handler.postDelayed({ finish() }, 2000)
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        scope.launch {
            processVoiceCommand()
        }
    }

    private suspend fun processVoiceCommand() {
        try {
            updateStatus("Listening...")
            val transcription = recognizeSpeech()

            if (transcription.isBlank()) {
                updateStatus("Couldn't understand that")
                delay(1000)
                finish()
                return
            }

            updateStatus("Heard: \"$transcription\"")

            val apps = intentResolver!!.getLaunchableApps()
            val intent = mapToIntent(transcription, apps)

            if (intent != null) {
                updateStatus("Opening...")
                delay(500)
                context.startActivity(intent)
            } else {
                updateStatus("Couldn't find matching action for:\n\"$transcription\"")
                delay(2000)
            }

            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error processing command", e)
            updateStatus("Error: ${e.message}")
            delay(2000)
            finish()
        }
    }

    private suspend fun recognizeSpeech(): String = suspendCoroutine { continuation ->
        val recognizer = speechRecognizer ?: run {
            continuation.resume("")
            return@suspendCoroutine
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
                updateStatus("Processing...")
            }

            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    else -> "Error code: $error"
                }
                Log.e(TAG, "Recognition error: $errorMsg")
                continuation.resume("")
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val result = matches?.firstOrNull() ?: ""
                Log.d(TAG, "Recognition result: $result")
                continuation.resume(result)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.firstOrNull()?.let { partial ->
                    updateStatus("Hearing: \"$partial\"")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer.startListening(intent)
    }

    private fun mapToIntent(transcription: String, apps: List<AppIntent>): Intent? {
        val ir = intentResolver ?: return null
        val text = transcription.lowercase()

        val systemKeywords = listOf("settings", "wifi", "bluetooth", "display", "sound", "battery", "location", "airplane")
        for (keyword in systemKeywords) {
            if (text.contains(keyword)) {
                ir.getSystemIntent(keyword)?.let { return it }
            }
        }

        val patterns = listOf(
            Regex("(?:open|launch|start|run)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$"),
            Regex("(?:go to|show me)\\s+(.+)"),
            Regex("^(.+?)\\s*$")
        )

        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val appQuery = match.groupValues[1].trim()
                val app = ir.findAppByName(apps, appQuery)
                if (app != null) {
                    Log.d(TAG, "Matched '$appQuery' to app '${app.name}'")
                    return ir.createLaunchIntent(app)
                }
            }
        }

        return null
    }

    private fun updateStatus(text: String) {
        handler.post {
            statusText?.text = text
        }
    }

    private suspend fun delay(ms: Long) {
        kotlinx.coroutines.delay(ms)
    }

    override fun onHide() {
        super.onHide()
        speechRecognizer?.destroy()
        speechRecognizer = null
        Log.d(TAG, "Session hidden")
    }
}
