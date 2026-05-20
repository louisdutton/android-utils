package digital.dutton.essentials.assistant

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.exp

enum class IntentCategory {
    SendMessage,
    MakeCall,
    Navigate,
    OpenApp,
    OpenSettings,
    SetTimer,
    SetAlarm,
    Calendar,
    Camera,
    OpenUrl,
    WebSearch,
    Unknown
}

data class IntentClassification(
    val category: IntentCategory,
    val confidence: Float
)

class IntentLanguageModel private constructor() {
    companion object {
        private const val TAG = "AssistantLLM"
        private const val MODEL_ASSET = "ml4_q6_k.gguf"
        private const val MODEL_FILE = "intent-model.gguf"
        private const val MIN_CONFIDENCE = 0.21f
        private const val MIN_MARGIN = 0.12f

        private val lock = Mutex()
        private var handle: Long = 0L

        private val candidates = listOf(
            Candidate(IntentCategory.SendMessage, " message"),
            Candidate(IntentCategory.MakeCall, " call"),
            Candidate(IntentCategory.Navigate, " map"),
            Candidate(IntentCategory.OpenApp, " app"),
            Candidate(IntentCategory.OpenSettings, " settings"),
            Candidate(IntentCategory.SetTimer, " timer"),
            Candidate(IntentCategory.SetAlarm, " alarm"),
            Candidate(IntentCategory.Calendar, " calendar"),
            Candidate(IntentCategory.Camera, " camera"),
            Candidate(IntentCategory.OpenUrl, " link"),
            Candidate(IntentCategory.WebSearch, " search"),
            Candidate(IntentCategory.Unknown, " unknown")
        )

        init {
            System.loadLibrary("assistant_whisper")
        }

        suspend fun warm(context: Context) {
            val startedAt = SystemClock.elapsedRealtime()
            lock.withLock {
                if (handle != 0L) return
                val fileStartedAt = SystemClock.elapsedRealtime()
                val model = modelFile(context.applicationContext)
                val fileReadyAt = SystemClock.elapsedRealtime()
                handle = withContext(Dispatchers.Default) { openNative(model.absolutePath) }
                val openedAt = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "warm total=${openedAt - startedAt}ms file=${fileReadyAt - fileStartedAt}ms " +
                        "open=${openedAt - fileReadyAt}ms ready=${handle != 0L}"
                )
            }
        }

        suspend fun classify(input: String): IntentClassification? {
            val startedAt = SystemClock.elapsedRealtime()
            if (!modelReady()) {
                Log.i(TAG, "classify unavailable elapsed=${SystemClock.elapsedRealtime() - startedAt}ms")
                return null
            }
            val readyAt = SystemClock.elapsedRealtime()

            val scores = lock.withLock {
                withContext(Dispatchers.Default) {
                    scoreLabelsNative(handle, prompt(input), candidates.map { it.label }.toTypedArray())
                }
            }
            val scoredAt = SystemClock.elapsedRealtime()
            if (scores.isEmpty()) {
                Log.i(TAG, "classify empty elapsed=${scoredAt - startedAt}ms")
                return null
            }

            val ranked = scores.indices.sortedByDescending { scores[it] }
            val best = ranked.first()
            val second = ranked.getOrNull(1)?.let { scores[it] } ?: Float.NEGATIVE_INFINITY
            val confidence = softmaxConfidence(scores, best)
            val margin = scores[best] - second

            val category = candidates[best].category
            Log.i(
                TAG,
                "classify total=${scoredAt - startedAt}ms wait=${readyAt - startedAt}ms " +
                    "score=${scoredAt - readyAt}ms best=$category confidence=$confidence margin=$margin"
            )

            if (confidence < MIN_CONFIDENCE || margin < MIN_MARGIN) return null
            if (category == IntentCategory.Unknown) return null

            return IntentClassification(category, confidence)
        }

        private suspend fun modelReady(): Boolean {
            if (handle != 0L) return true
            if (lock.tryLock()) {
                return try {
                    handle != 0L
                } finally {
                    lock.unlock()
                }
            }
            lock.withLock { }
            return handle != 0L
        }

        private fun prompt(input: String): String {
            val command = input.replace(Regex("""\s+"""), " ").trim().take(180)
            return """
                Classify the phone command as one label.
                Labels: message, call, map, app, settings, timer, alarm, calendar, camera, link, search, unknown
                Command: text myself hello
                Label: message
                Command: directions to the station
                Label: map
                Command: open vault
                Label: app
                Command: turn on bluetooth
                Label: settings
                Command: $command
                Label:
            """.trimIndent()
        }

        private suspend fun modelFile(context: Context): File = withContext(Dispatchers.IO) {
            val target = File(context.filesDir, MODEL_FILE)
            if (target.exists() && target.length() > 0L) return@withContext target

            context.assets.open(MODEL_ASSET).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target
        }

        private fun softmaxConfidence(scores: FloatArray, best: Int): Float {
            val max = scores.maxOrNull() ?: return 0f
            val sum = scores.sumOf { exp((it - max).toDouble()) }
            if (sum <= 0.0) return 0f
            return (exp((scores[best] - max).toDouble()) / sum).toFloat()
        }

        @JvmStatic private external fun openNative(modelPath: String): Long
        @JvmStatic private external fun scoreLabelsNative(
            handle: Long,
            prompt: String,
            labels: Array<String>
        ): FloatArray
        @JvmStatic private external fun closeNative(handle: Long)
    }
}

private data class Candidate(
    val category: IntentCategory,
    val label: String
)
