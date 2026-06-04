package digital.dutton.essentials.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

class SingingPitchRecorder(
    private val onObservation: (PitchObservation) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val lock = Any()
    @Volatile
    private var running = false
    private var recordThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        synchronized(lock) {
            if (running) return true
            running = true
        }

        recordThread = Thread {
            val minBufferBytes = AudioRecord.getMinBufferSize(
                SampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferBytes <= 0) {
                running = false
                onError("Unable to open the microphone.")
                return@Thread
            }

            val recorder = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBufferBytes * 2, FrameSize * ShortBytes * 2))
                    .build()
            }.getOrElse { error ->
                running = false
                onError(error.message ?: "Unable to open the microphone.")
                return@Thread
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                running = false
                onError("Unable to initialize the microphone.")
                return@Thread
            }

            val buffer = ShortArray(FrameSize)
            try {
                recorder.startRecording()
                while (running) {
                    val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        onObservation(detectPitch(buffer, read))
                    }
                }
            } catch (error: Throwable) {
                if (running) {
                    onError(error.message ?: "Microphone capture failed.")
                }
            } finally {
                running = false
                runCatching { recorder.stop() }
                recorder.release()
            }
        }.apply {
            name = "Sight singing pitch recorder"
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        val thread = synchronized(lock) {
            running = false
            recordThread
        }
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(StopJoinMillis) }
        }
    }

    private fun detectPitch(
        samples: ShortArray,
        count: Int,
    ): PitchObservation {
        var sum = 0.0
        var squareSum = 0.0
        repeat(count) { index ->
            val value = samples[index].toDouble() / Short.MAX_VALUE
            sum += value
            squareSum += value * value
        }

        val mean = sum / count
        val rms = sqrt((squareSum / count) - mean * mean).toFloat().coerceAtLeast(0f)
        if (rms < MinimumVoiceLevel) {
            return PitchObservation(
                frequencyHz = null,
                confidence = 0.0,
                level = rms,
            )
        }

        val minLag = SampleRate / MaximumSingingFrequencyHz
        val maxLag = SampleRate / MinimumSingingFrequencyHz
        val correlations = DoubleArray(maxLag + 1)
        var bestLag = 0
        var bestCorrelation = 0.0

        for (lag in minLag..maxLag) {
            var cross = 0.0
            var energyA = 0.0
            var energyB = 0.0
            val end = count - lag
            for (index in 0 until end) {
                val a = samples[index].toDouble() / Short.MAX_VALUE - mean
                val b = samples[index + lag].toDouble() / Short.MAX_VALUE - mean
                cross += a * b
                energyA += a * a
                energyB += b * b
            }
            val correlation = if (energyA > 0.0 && energyB > 0.0) {
                cross / sqrt(energyA * energyB)
            } else {
                0.0
            }
            correlations[lag] = correlation
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation
                bestLag = lag
            }
        }

        if (bestLag == 0 || bestCorrelation < MinimumPitchConfidence) {
            return PitchObservation(
                frequencyHz = null,
                confidence = bestCorrelation,
                level = rms,
            )
        }

        val refinedLag = refineLag(bestLag, correlations)
        return PitchObservation(
            frequencyHz = SampleRate / refinedLag,
            confidence = bestCorrelation,
            level = rms,
        )
    }

    private fun refineLag(
        lag: Int,
        correlations: DoubleArray,
    ): Double {
        if (lag <= 1 || lag >= correlations.lastIndex) return lag.toDouble()
        val previous = correlations[lag - 1]
        val center = correlations[lag]
        val next = correlations[lag + 1]
        val denominator = previous - 2.0 * center + next
        if (denominator == 0.0) return lag.toDouble()
        return lag + 0.5 * (previous - next) / denominator
    }

    private companion object {
        const val SampleRate = 44_100
        const val ShortBytes = 2
        const val FrameSize = 4_096
        const val MinimumSingingFrequencyHz = 80
        const val MaximumSingingFrequencyHz = 900
        const val MinimumVoiceLevel = 0.014f
        const val MinimumPitchConfidence = 0.58
        const val StopJoinMillis = 250L
    }
}
