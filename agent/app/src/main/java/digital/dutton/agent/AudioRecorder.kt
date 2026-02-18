package digital.dutton.agent

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioRecorder {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT
    ).coerceAtLeast(SAMPLE_RATE * 2) // At least 1 second buffer

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording) return false

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            return false
        }

        audioRecord?.startRecording()
        isRecording = true
        return true
    }

    suspend fun recordForDuration(durationMs: Long): FloatArray = withContext(Dispatchers.IO) {
        val totalSamples = (SAMPLE_RATE * durationMs / 1000).toInt()
        val shortBuffer = ShortArray(bufferSize)
        val allSamples = mutableListOf<Short>()

        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < durationMs && isRecording) {
            val read = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
            if (read > 0) {
                for (i in 0 until read) {
                    allSamples.add(shortBuffer[i])
                }
            }
        }

        // Convert to float array normalized to [-1, 1]
        FloatArray(allSamples.size) { i ->
            allSamples[i].toFloat() / Short.MAX_VALUE
        }
    }

    fun stopRecording(): FloatArray {
        isRecording = false
        audioRecord?.stop()

        // Read any remaining data
        val shortBuffer = ShortArray(bufferSize)
        val remainingSamples = mutableListOf<Short>()

        audioRecord?.let { record ->
            var read: Int
            do {
                read = record.read(shortBuffer, 0, shortBuffer.size)
                if (read > 0) {
                    for (i in 0 until read) {
                        remainingSamples.add(shortBuffer[i])
                    }
                }
            } while (read > 0)
        }

        audioRecord?.release()
        audioRecord = null

        return FloatArray(remainingSamples.size) { i ->
            remainingSamples[i].toFloat() / Short.MAX_VALUE
        }
    }

    fun release() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
