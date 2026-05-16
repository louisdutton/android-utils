package digital.dutton.agent

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.URI
import java.util.Locale

class AgentVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    companion object {
        private const val TAG = "AgentSession"
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())

    private var scrollView: ScrollView? = null
    private var conversationLayout: LinearLayout? = null
    private var userTextView: TextView? = null
    private var assistantTextView: TextView? = null
    private var statusTextView: TextView? = null

    private var audioRecorder: AudioRecorder? = null
    private var whisperClient: WhisperClient? = null
    private var ghostClient: GhostClient? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var streamJob: Job? = null
    private var conversationJob: Job? = null

    override fun onCreateContentView(): View {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            setPadding(48, 48, 48, 48)
        }

        scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        conversationLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // User message (small, dimmed)
        userTextView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.END
            setPadding(0, 0, 0, 24)
            visibility = View.GONE
        }

        // Assistant message (large)
        assistantTextView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.START
            visibility = View.GONE
        }

        conversationLayout!!.addView(userTextView)
        conversationLayout!!.addView(assistantTextView)
        scrollView!!.addView(conversationLayout)

        // Status at bottom
        statusTextView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF666666.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
            text = "Initializing..."
        }

        layout.addView(scrollView)
        layout.addView(statusTextView)

        return layout
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(TAG, "Session shown")

        // Initialize TTS
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
                Log.d(TAG, "TTS ready")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }

        // Initialize clients from saved prefs
        val prefs = context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", null)
        if (serverUrl == null) {
            updateStatus("No server configured")
            handler.postDelayed({ finish() }, 2000)
            return
        }

        try {
            val uri = URI(serverUrl)
            val whisperUrl = "${uri.scheme}://${uri.host}:5932"
            whisperClient = WhisperClient(whisperUrl)
            audioRecorder = AudioRecorder(context.cacheDir)
            ghostClient = GhostClient(serverUrl)
        } catch (e: Exception) {
            updateStatus("Failed to initialize: ${e.message}")
            handler.postDelayed({ finish() }, 2000)
            return
        }

        conversationJob = scope.launch {
            startConversation()
        }
    }

    private suspend fun startConversation() {
        try {
            val client = ghostClient ?: return

            // Connect to ghost
            updateStatus("Connecting...")
            val sessions = client.listSessions()
            if (sessions.isEmpty()) {
                client.createSession()
            } else {
                val mostRecent = sessions.maxByOrNull { it.createdAt }
                if (mostRecent != null) {
                    client.connectToSession(mostRecent.id)
                }
            }

            // Conversation loop
            while (true) {
                // Listen for user input
                updateStatus("Listening...")
                val transcription = recognizeSpeech()

                if (transcription.isBlank()) {
                    updateStatus("Tap to speak or swipe to close")
                    delay(2000)
                    continue
                }

                // Show user message
                showUserMessage(transcription)
                updateStatus("Thinking...")

                // Start streaming and collect response
                val responseBuilder = StringBuilder()
                var responseComplete = false

                streamJob = scope.launch {
                    try {
                        client.streamEvents().collect { event ->
                            when (event) {
                                is GhostEvent.Text -> {
                                    responseBuilder.append(event.content)
                                    showAssistantMessage(responseBuilder.toString())
                                }
                                is GhostEvent.TurnEnd -> {
                                    responseComplete = true
                                }
                                is GhostEvent.Error -> {
                                    showAssistantMessage("Error: ${event.message}")
                                    responseComplete = true
                                }
                                else -> {}
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Stream error", e)
                        responseComplete = true
                    }
                }

                // Send message
                client.sendMessage(transcription)

                // Wait for response to complete
                while (!responseComplete) {
                    delay(100)
                }

                // Speak the response
                val response = responseBuilder.toString()
                if (response.isNotBlank()) {
                    updateStatus("Speaking...")
                    speakAndWait(response)
                }

                // Brief pause before listening again
                delay(500)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Conversation error", e)
            updateStatus("Error: ${e.message}")
            delay(2000)
            finish()
        }
    }

    private suspend fun recognizeSpeech(): String {
        val recorder = audioRecorder ?: return ""
        val client = whisperClient ?: return ""

        val audioFile = try {
            recorder.startRecordingUntilSilence()
        } catch (e: Exception) {
            Log.e(TAG, "Recording error", e)
            return ""
        }

        if (audioFile == null || !audioFile.exists()) {
            Log.e(TAG, "No audio file")
            return ""
        }

        updateStatus("Transcribing...")

        return try {
            val text = client.transcribe(audioFile)
            audioFile.delete()
            text
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error", e)
            audioFile.delete()
            ""
        }
    }

    private suspend fun speakAndWait(text: String) {
        if (!ttsReady || tts == null) return

        val utteranceId = "response_${System.currentTimeMillis()}"
        var speaking = true

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { speaking = false }
            override fun onError(id: String?) { speaking = false }
        })

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

        while (speaking) {
            delay(100)
        }
    }

    private fun showUserMessage(text: String) {
        handler.post {
            userTextView?.text = text
            userTextView?.visibility = View.VISIBLE
            assistantTextView?.text = ""
            assistantTextView?.visibility = View.GONE
            scrollToBottom()
        }
    }

    private fun showAssistantMessage(text: String) {
        handler.post {
            assistantTextView?.text = text
            assistantTextView?.visibility = View.VISIBLE
            scrollToBottom()
        }
    }

    private fun scrollToBottom() {
        scrollView?.post {
            scrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun updateStatus(text: String) {
        handler.post {
            statusTextView?.text = text
        }
    }

    private suspend fun delay(ms: Long) {
        kotlinx.coroutines.delay(ms)
    }

    override fun onHide() {
        super.onHide()
        conversationJob?.cancel()
        streamJob?.cancel()
        audioRecorder?.stopRecording()
        audioRecorder = null
        whisperClient = null
        ghostClient?.close()
        ghostClient = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        Log.d(TAG, "Session hidden")
    }
}
