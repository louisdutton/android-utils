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
    private var modelsInitialized = false

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
            // Initialize models if needed
            if (!modelsInitialized) {
                updateStatus("Loading models...")
                val initialized = initializeModels()
                if (!initialized) {
                    updateStatus("Models not available.\nPlease install models first.")
                    delay(2000)
                    finish()
                    return
                }
                modelsInitialized = true
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

            updateStatus("Heard: \"$transcription\"\n\n🤔 Thinking...")

            // Get available apps and map intent
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

    private suspend fun initializeModels(): Boolean = withContext(Dispatchers.IO) {
        val mm = modelManager ?: return@withContext false

        if (!mm.areModelsAvailable()) {
            // Try copying from assets
            mm.copyModelsFromAssets()
        }

        if (!mm.isWhisperModelAvailable()) {
            Log.e(TAG, "Whisper model not available at ${mm.whisperModelPath}")
            return@withContext false
        }

        if (!mm.isLlamaModelAvailable()) {
            Log.e(TAG, "Llama model not available at ${mm.llamaModelPath}")
            return@withContext false
        }

        val whisperOk = WhisperLib.initialize(mm.whisperModelPath)
        if (!whisperOk) {
            Log.e(TAG, "Failed to initialize Whisper")
            return@withContext false
        }

        val llamaOk = LlamaLib.initialize(mm.llamaModelPath)
        if (!llamaOk) {
            Log.e(TAG, "Failed to initialize Llama")
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

    private suspend fun mapToIntent(transcription: String, apps: List<AppIntent>): Intent? {
        val ir = intentResolver ?: return null

        // First check for system intents
        val systemKeywords = listOf("settings", "wifi", "bluetooth", "display", "sound", "battery", "location", "airplane")
        for (keyword in systemKeywords) {
            if (transcription.lowercase().contains(keyword)) {
                ir.getSystemIntent(keyword)?.let { return it }
            }
        }

        // Use LLM to determine which app to open
        val appList = ir.formatAppsForPrompt(apps)
        val prompt = """<|im_start|>system
You are a voice assistant. Given a user's voice command and a list of available apps, respond with ONLY the exact app name to open. If no app matches, respond with "NONE".
<|im_end|>
<|im_start|>user
Command: "$transcription"

Available apps:
$appList

Which app should I open? Respond with only the app name or NONE.<|im_end|>
<|im_start|>assistant
"""

        val response = withContext(Dispatchers.IO) {
            LlamaLib.generate(prompt, 32)
        }.trim()

        Log.d(TAG, "LLM response: $response")

        if (response.equals("NONE", ignoreCase = true) || response.isBlank()) {
            return null
        }

        // Find the app
        val app = ir.findAppByName(apps, response)
        return app?.let { ir.createLaunchIntent(it) }
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
