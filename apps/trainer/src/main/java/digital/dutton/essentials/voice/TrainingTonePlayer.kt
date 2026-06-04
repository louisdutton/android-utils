package digital.dutton.essentials.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class TrainingTonePlayer {
    private val lock = Any()
    private var activePlayback: ActivePlayback? = null

    fun playNote(
        midiNumber: Int,
        durationMillis: Long,
    ) {
        playSequence(
            midiNumbers = listOf(midiNumber),
            noteDurationMillis = durationMillis,
            gapDurationMillis = 0L,
        )
    }

    fun playSequence(
        midiNumbers: List<Int>,
        tempoBpm: Int,
    ) {
        val beatMillis = 60_000L / tempoBpm.coerceAtLeast(40)
        playSequence(
            midiNumbers = midiNumbers,
            noteDurationMillis = (beatMillis * 0.82f).toLong(),
            gapDurationMillis = beatMillis - (beatMillis * 0.82f).toLong(),
        )
    }

    private fun playSequence(
        midiNumbers: List<Int>,
        noteDurationMillis: Long,
        gapDurationMillis: Long,
    ) {
        if (midiNumbers.isEmpty()) return

        val playback = ActivePlayback()
        synchronized(lock) {
            activePlayback?.stop()
            activePlayback = playback
        }

        Thread {
            try {
                renderSequence(midiNumbers, noteDurationMillis, gapDurationMillis, playback)
            } finally {
                synchronized(lock) {
                    if (activePlayback === playback) {
                        activePlayback = null
                    }
                }
            }
        }.apply {
            name = "Voice reference playback"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        synchronized(lock) {
            activePlayback?.stop()
            activePlayback = null
        }
    }

    private fun renderSequence(
        midiNumbers: List<Int>,
        noteDurationMillis: Long,
        gapDurationMillis: Long,
        playback: ActivePlayback,
    ) {
        if (playback.stopSignal.get()) return

        val minBufferSize = AudioTrack.getMinBufferSize(
            SampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBufferSize, ChunkSamples * ShortBytes * 4))
            .build()

        playback.track = track
        val toneSamples = (SampleRate * noteDurationMillis / MillisPerSecond).toInt()
        val gapSamples = (SampleRate * gapDurationMillis / MillisPerSecond).toInt()

        try {
            track.play()
            midiNumbers.forEach { midiNumber ->
                if (playback.stopSignal.get()) return
                writeTone(
                    track = track,
                    frequencyHz = midiToFrequencyHz(midiNumber),
                    totalSamples = toneSamples,
                    stopSignal = playback.stopSignal,
                )
                writeSilence(
                    track = track,
                    totalSamples = gapSamples,
                    stopSignal = playback.stopSignal,
                )
            }
        } finally {
            runCatching { Thread.sleep(OutputDrainMillis) }
            runCatching { track.stop() }
            track.release()
            playback.track = null
        }
    }

    private fun writeTone(
        track: AudioTrack,
        frequencyHz: Double,
        totalSamples: Int,
        stopSignal: AtomicBoolean,
    ) {
        val buffer = ShortArray(ChunkSamples)
        val amplitude = Short.MAX_VALUE * 0.20
        val phaseIncrement = 2.0 * PI * frequencyHz / SampleRate
        var phase = 0.0
        var written = 0

        while (written < totalSamples && !stopSignal.get()) {
            val count = min(buffer.size, totalSamples - written)
            for (index in 0 until count) {
                val sampleIndex = written + index
                val envelope = envelopeFor(sampleIndex, totalSamples)
                buffer[index] = (sin(phase) * amplitude * envelope).toInt().toShort()
                phase += phaseIncrement
                if (phase >= 2.0 * PI) phase -= 2.0 * PI
            }
            track.write(buffer, 0, count, AudioTrack.WRITE_BLOCKING)
            written += count
        }
    }

    private fun writeSilence(
        track: AudioTrack,
        totalSamples: Int,
        stopSignal: AtomicBoolean,
    ) {
        val buffer = ShortArray(ChunkSamples)
        var written = 0
        while (written < totalSamples && !stopSignal.get()) {
            val count = min(buffer.size, totalSamples - written)
            track.write(buffer, 0, count, AudioTrack.WRITE_BLOCKING)
            written += count
        }
    }

    private fun envelopeFor(
        sampleIndex: Int,
        totalSamples: Int,
    ): Double {
        val fadeSamples = min(FadeSamples, totalSamples / 3).coerceAtLeast(1)
        return when {
            sampleIndex < fadeSamples -> cosineFade(sampleIndex.toDouble() / fadeSamples)
            sampleIndex > totalSamples - fadeSamples -> {
                cosineFade((totalSamples - sampleIndex).toDouble() / fadeSamples)
            }
            else -> 1.0
        }
    }

    private fun cosineFade(progress: Double): Double {
        return 0.5 - 0.5 * cos(PI * progress.coerceIn(0.0, 1.0))
    }

    private class ActivePlayback {
        val stopSignal = AtomicBoolean(false)
        @Volatile
        var track: AudioTrack? = null

        fun stop() {
            stopSignal.set(true)
            track?.let { activeTrack ->
                runCatching { activeTrack.pause() }
                runCatching { activeTrack.flush() }
                runCatching { activeTrack.stop() }
            }
        }
    }

    private companion object {
        const val SampleRate = 48_000
        const val ShortBytes = 2
        const val ChunkSamples = 384
        const val FadeSamples = SampleRate / 80
        const val OutputDrainMillis = 60L
        const val MillisPerSecond = 1_000L
    }
}
