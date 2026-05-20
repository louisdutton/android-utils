package digital.dutton.essentials.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AssistantVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = AssistantEngine(context.applicationContext)

    private var transcript: TextView? = null
    private var result: TextView? = null
    private var status: TextView? = null

    override fun onCreateContentView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundColor(themeColor(android.R.attr.colorBackground, 0xFFFFFFFF.toInt()))
        }

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        transcript = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(themeColor(android.R.attr.textColorPrimary, 0xFF000000.toInt()))
            visibility = View.GONE
        }

        result = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(themeColor(android.R.attr.textColorPrimary, 0xFF000000.toInt()))
            setPadding(0, dp(16), 0, dp(4))
            visibility = View.GONE
        }

        status = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColor(android.R.attr.textColorSecondary, 0xFF666666.toInt()))
            setPadding(0, 0, 0, dp(16))
            text = "Ready"
        }

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }

        val speakButton = Button(context).apply {
            text = "Speak"
            setOnClickListener { listen() }
        }
        val closeButton = Button(context).apply {
            text = "Close"
            setOnClickListener { finish() }
        }

        buttons.addView(speakButton)
        buttons.addView(closeButton)

        content.addView(status)
        content.addView(transcript)
        content.addView(result)
        scroll.addView(content)

        root.addView(scroll)
        root.addView(buttons)

        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (hasAudioPermission()) {
            listen()
        } else {
            updateStatus("Microphone permission is required")
        }
    }

    override fun onHide() {
        super.onHide()
        scope.cancel()
    }

    private fun listen() {
        if (!hasAudioPermission()) {
            updateStatus("Microphone permission is required")
            return
        }

        scope.launch {
            updateStatus("Listening")
            runCatching { LocalSpeechTranscriber.transcribe(context.applicationContext) }
                .onSuccess { text ->
                    runRequest(text)
                }
                .onFailure { error ->
                    updateStatus(error.message ?: "Speech recognition failed")
                }
        }
    }

    private fun runRequest(text: String) {
        if (text.isBlank()) {
            updateStatus("Ready")
            return
        }

        scope.launch {
            transcript?.text = text
            transcript?.visibility = View.VISIBLE
            updateStatus("Running")

            val executed = engine.execute(text)
            result?.text = buildString {
                append(executed.title)
                if (executed.detail.isNotBlank()) append("\n").append(executed.detail)
                executed.error?.let { append("\n").append(it) }
            }
            result?.visibility = View.VISIBLE
            updateStatus(if (executed.error == null) "Done" else "Failed")
        }
    }

    private fun updateStatus(text: String) {
        status?.text = text
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(attr, typedValue, true)) typedValue.data else fallback
    }
}
