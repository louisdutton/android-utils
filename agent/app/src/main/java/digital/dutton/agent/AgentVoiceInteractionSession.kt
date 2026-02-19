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
import java.net.URI

class AgentVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    companion object {
        private const val TAG = "AgentSession"
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())

    private var statusText: TextView? = null
    private var intentResolver: IntentResolver? = null
    private var audioRecorder: AudioRecorder? = null
    private var whisperClient: WhisperClient? = null
    private var recordingJob: Job? = null

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

        // Initialize whisper client from saved prefs
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
        } catch (e: Exception) {
            updateStatus("Failed to initialize: ${e.message}")
            handler.postDelayed({ finish() }, 2000)
            return
        }

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

    private suspend fun recognizeSpeech(): String {
        val recorder = audioRecorder ?: return ""
        val client = whisperClient ?: return ""

        // Start recording in background
        recordingJob = scope.launch(Dispatchers.IO) {
            try {
                recorder.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
            }
        }

        // Wait for 3 seconds of recording (or implement silence detection later)
        delay(3000)

        // Stop recording
        val audioFile = recorder.stopRecording()
        recordingJob?.join()

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
        recordingJob?.cancel()
        audioRecorder?.stopRecording()
        audioRecorder = null
        whisperClient = null
        Log.d(TAG, "Session hidden")
    }
}
