package digital.dutton.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AgentVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    companion object {
        private const val TAG = "AgentSession"
        private const val RECORDING_DURATION_MS = 5000L
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())

    private var statusText: TextView? = null
    private var audioRecorder: AudioRecorder? = null
    private var modelManager: ModelManager? = null
    private var intentResolver: IntentResolver? = null
    private var whisperInitialized = false

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

        modelManager = ModelManager(context)
        intentResolver = IntentResolver(context)
        audioRecorder = AudioRecorder()

        scope.launch {
            processVoiceCommand()
        }
    }

    private suspend fun processVoiceCommand() {
        try {
            // Initialize whisper if needed
            if (!whisperInitialized) {
                updateStatus("Loading model...")
                val initialized = initializeWhisper()
                if (!initialized) {
                    updateStatus("Model not available.\nPlease install model first.")
                    delay(2000)
                    finish()
                    return
                }
                whisperInitialized = true
            }

            // Record audio
            updateStatus("🎤 Listening...")
            val audioData = recordAudio()

            if (audioData.isEmpty()) {
                updateStatus("No audio captured")
                delay(1000)
                finish()
                return
            }

            // Transcribe
            updateStatus("🔄 Transcribing...")
            val transcription = withContext(Dispatchers.IO) {
                WhisperLib.transcribe(audioData)
            }

            if (transcription.isBlank()) {
                updateStatus("Couldn't understand that")
                delay(1000)
                finish()
                return
            }

            updateStatus("Heard: \"$transcription\"")

            // Get available apps and map intent using keyword matching
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

    private suspend fun initializeWhisper(): Boolean = withContext(Dispatchers.IO) {
        val mm = modelManager ?: return@withContext false

        if (!mm.isWhisperModelAvailable()) {
            mm.copyModelsFromAssets()
        }

        if (!mm.isWhisperModelAvailable()) {
            Log.e(TAG, "Whisper model not available at ${mm.whisperModelPath}")
            return@withContext false
        }

        val ok = WhisperLib.initialize(mm.whisperModelPath)
        if (!ok) {
            Log.e(TAG, "Failed to initialize Whisper")
            return@withContext false
        }

        true
    }

    private suspend fun recordAudio(): FloatArray {
        val recorder = audioRecorder ?: return floatArrayOf()

        if (!recorder.startRecording()) {
            Log.e(TAG, "Failed to start recording")
            return floatArrayOf()
        }

        val audioData = recorder.recordForDuration(RECORDING_DURATION_MS)
        recorder.stopRecording()

        Log.d(TAG, "Recorded ${audioData.size} samples")
        return audioData
    }

    private fun mapToIntent(transcription: String, apps: List<AppIntent>): Intent? {
        val ir = intentResolver ?: return null
        val text = transcription.lowercase()

        // Check for system intents
        val systemKeywords = listOf("settings", "wifi", "bluetooth", "display", "sound", "battery", "location", "airplane")
        for (keyword in systemKeywords) {
            if (text.contains(keyword)) {
                ir.getSystemIntent(keyword)?.let { return it }
            }
        }

        // Extract app name from common patterns
        val patterns = listOf(
            Regex("(?:open|launch|start|run)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$"),
            Regex("(?:go to|show me)\\s+(.+)"),
            Regex("^(.+?)\\s*$")  // fallback: try whole phrase
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
        audioRecorder?.release()
        Log.d(TAG, "Session hidden")
    }
}
