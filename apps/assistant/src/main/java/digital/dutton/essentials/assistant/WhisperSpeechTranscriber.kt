package digital.dutton.essentials.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.futo.voiceinput.shared.ggml.DecodingMode
import org.futo.voiceinput.shared.ggml.WhisperGGML
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.sqrt

private const val WHISPER_MODEL = "tiny_en_acft_q8_0.bin.not.tflite"
private const val SAMPLE_RATE = 16_000
private const val MAX_RECORDING_MS = 12_000L
private const val START_TIMEOUT_MS = 4_500L
private const val MIN_RECORDING_MS = 800L
private const val END_SILENCE_MS = 900L
private const val SPEECH_RMS_THRESHOLD = 0.012f

object WhisperSpeechTranscriber {
    private val modelLock = Mutex()
    private var model: WhisperGGML? = null
    private var modelBuffer: ByteBuffer? = null

    suspend fun transcribe(context: Context): String {
        val samples = recordSpeech(context.applicationContext)
        val text = model(context.applicationContext).infer(
            samples = samples,
            prompt = "",
            languages = arrayOf("en"),
            decodingMode = DecodingMode.Greedy,
            suppressNonSpeechTokens = true,
            partialResultCallback = {}
        ).trim()

        if (text.isBlank()) throw SpeechUnavailableException("No speech was recognized.")
        return text
    }

    private suspend fun model(context: Context): WhisperGGML = modelLock.withLock {
        model ?: WhisperGGML(loadModel(context)).also { model = it }
    }

    private suspend fun loadModel(context: Context): ByteBuffer = withContext(Dispatchers.IO) {
        modelBuffer ?: context.assets.open(WHISPER_MODEL).use { input ->
            val bytes = input.readBytes()
            ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .put(bytes)
                .apply { rewind() }
                .also { modelBuffer = it }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordSpeech(context: Context): FloatArray = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SpeechUnavailableException("Microphone permission is required.")
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) throw SpeechUnavailableException("Audio capture is unavailable.")

        val readBufferSize = max(minBufferSize, SAMPLE_RATE / 10 * Short.SIZE_BYTES)
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(readBufferSize * 2)
            .build()

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw SpeechUnavailableException("Audio capture could not start.")
        }

        val samples = FloatSampleBuffer(SAMPLE_RATE * 4)
        val pcm = ShortArray(readBufferSize / Short.SIZE_BYTES)
        var heardSpeech = false
        var lastSpeechAt = 0L
        val startedAt = SystemClock.elapsedRealtime()

        try {
            recorder.startRecording()
            while (true) {
                coroutineContext.ensureActive()

                val count = recorder.read(pcm, 0, pcm.size)
                if (count <= 0) throw SpeechUnavailableException("Audio capture failed.")

                samples.append(pcm, count)

                val now = SystemClock.elapsedRealtime()
                val elapsed = now - startedAt
                if (rms(pcm, count) >= SPEECH_RMS_THRESHOLD) {
                    heardSpeech = true
                    lastSpeechAt = now
                }

                val canEnd = elapsed >= MIN_RECORDING_MS
                val silenceElapsed = heardSpeech && now - lastSpeechAt >= END_SILENCE_MS
                val startTimedOut = !heardSpeech && elapsed >= START_TIMEOUT_MS
                val maxedOut = elapsed >= MAX_RECORDING_MS

                if (canEnd && (silenceElapsed || maxedOut)) break
                if (startTimedOut) throw SpeechUnavailableException("No speech was heard.")
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        if (!heardSpeech) throw SpeechUnavailableException("No speech was heard.")
        samples.toArray()
    }

    private fun rms(buffer: ShortArray, count: Int): Float {
        var sum = 0.0
        for (index in 0 until count) {
            val value = buffer[index] / Short.MAX_VALUE.toDouble()
            sum += value * value
        }
        return sqrt(sum / count).toFloat()
    }
}

private class FloatSampleBuffer(initialCapacity: Int) {
    private var values = FloatArray(initialCapacity)
    private var size = 0

    fun append(pcm: ShortArray, count: Int) {
        ensureCapacity(size + count)
        for (index in 0 until count) {
            values[size + index] = pcm[index] / Short.MAX_VALUE.toFloat()
        }
        size += count
    }

    fun toArray(): FloatArray = values.copyOf(size)

    private fun ensureCapacity(required: Int) {
        if (required <= values.size) return
        var capacity = values.size
        while (capacity < required) capacity *= 2
        values = values.copyOf(capacity)
    }
}
