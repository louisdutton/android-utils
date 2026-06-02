package digital.dutton.essentials.pitch

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PitchApp() }
    }
}

@Composable
private fun PitchApp() {
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            PitchScreen()
        }
    }
}

@Composable
private fun PitchScreen() {
    val player = remember { PitchTonePlayer() }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        PianoKeyboard(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            pitches = KeyboardPitches,
            onPitchPressed = { pitch -> player.play(pitch.midiNumber, pitch.frequencyHz) },
            onPitchReleased = { pitch -> player.stop(pitch.midiNumber) },
        )
    }
}

@Composable
private fun PianoKeyboard(
    modifier: Modifier = Modifier,
    pitches: List<Pitch>,
    onPitchPressed: (Pitch) -> Unit,
    onPitchReleased: (Pitch) -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier,
    ) {
        val whitePitches = pitches.filterNot { it.isBlackKey }
        val blackPitches = pitches.filter { it.isBlackKey }
        val whiteKeyGap = 4.dp
        val keyShape = RoundedCornerShape(6.dp)
        val whiteKeyWidth = (maxWidth - whiteKeyGap * (whitePitches.size - 1)) / whitePitches.size
        val blackKeyWidth = whiteKeyWidth * 0.64f
        val blackKeyHeight = maxHeight * 0.62f

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(whiteKeyGap),
        ) {
            whitePitches.forEach { pitch ->
                val interactionSource = remember(pitch.midiNumber) { MutableInteractionSource() }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(keyShape)
                        .pitchPress(
                            pitch = pitch,
                            interactionSource = interactionSource,
                            onPitchPressed = onPitchPressed,
                            onPitchReleased = onPitchReleased,
                        ),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = keyShape,
                ) {
                    Column(
                        modifier = Modifier.padding(bottom = 12.dp),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = pitch.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        blackPitches.forEach { pitch ->
            val interactionSource = remember(pitch.midiNumber) { MutableInteractionSource() }
            val boundaryAfterWhiteKey = whitePitches.indexOfLast { it.midiNumber < pitch.midiNumber } + 1
            val left = (whiteKeyWidth + whiteKeyGap) * boundaryAfterWhiteKey -
                whiteKeyGap / 2 -
                blackKeyWidth / 2

            Surface(
                modifier = Modifier
                    .offset(x = left)
                    .width(blackKeyWidth)
                    .height(blackKeyHeight)
                    .clip(keyShape)
                    .pitchPress(
                        pitch = pitch,
                        interactionSource = interactionSource,
                        onPitchPressed = onPitchPressed,
                        onPitchReleased = onPitchReleased,
                    ),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = keyShape,
            ) {
                Column(
                    modifier = Modifier.padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = pitch.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

private fun Modifier.pitchPress(
    pitch: Pitch,
    interactionSource: MutableInteractionSource,
    onPitchPressed: (Pitch) -> Unit,
    onPitchReleased: (Pitch) -> Unit,
): Modifier {
    return indication(
        interactionSource = interactionSource,
        indication = ripple(),
    ).pointerInput(pitch, interactionSource) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = PressInteraction.Press(down.position)
            interactionSource.tryEmit(press)
            onPitchPressed(pitch)
            val up = waitForUpOrCancellation()
            onPitchReleased(pitch)
            if (up == null) {
                interactionSource.tryEmit(PressInteraction.Cancel(press))
            } else {
                interactionSource.tryEmit(PressInteraction.Release(press))
            }
        }
    }
}

private class PitchTonePlayer {
    private val lock = Any()
    private val voices = mutableListOf<PianoVoice>()
    @Volatile private var isRunning = true
    @Volatile private var audioTrack: AudioTrack? = null

    private val audioThread = Thread(::runAudio).apply {
        name = "Piano audio mixer"
        isDaemon = true
        priority = Thread.MAX_PRIORITY
        start()
    }

    fun play(
        noteId: Int,
        frequencyHz: Double,
    ) {
        synchronized(lock) {
            while (activeVoiceCount() >= MaxVoices) {
                var releasedVoice = false
                for (index in voices.indices) {
                    val voice = voices[index]
                    if (!voice.isReleasing) {
                        voice.beginRelease(VoiceStealReleaseSamples)
                        releasedVoice = true
                        break
                    }
                }
                if (!releasedVoice) {
                    break
                }
            }
            voices += PianoVoice(noteId, frequencyHz)
        }
    }

    fun stop(noteId: Int) {
        synchronized(lock) {
            for (index in voices.indices) {
                val voice = voices[index]
                if (voice.noteId == noteId && !voice.isReleasing) {
                    voice.beginRelease(ReleaseSamples)
                }
            }
        }
    }

    fun stopAll() {
        synchronized(lock) {
            for (index in voices.indices) {
                val voice = voices[index]
                if (!voice.isReleasing) {
                    voice.beginRelease(ReleaseSamples)
                }
            }
        }
    }

    fun release() {
        isRunning = false
        synchronized(lock) { voices.clear() }
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioThread.join(AudioThreadJoinMillis) }
    }

    private fun runAudio() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }

        val minBufferSize = AudioTrack.getMinBufferSize(
            SampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val buffer = ShortArray(ChunkSamples)

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
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(minBufferSize, StableBufferFrames * ShortBytes))
            .build()

        try {
            audioTrack = track
            runCatching { track.setBufferSizeInFrames(LowLatencyBufferFrames) }
            track.play()

            while (isRunning) {
                synchronized(lock) {
                    for (sampleIndex in buffer.indices) {
                        var mixed = 0.0
                        for (voiceIndex in voices.indices) {
                            mixed += voices[voiceIndex].nextSample()
                        }
                        buffer[sampleIndex] = toPcm16(mixed)
                    }
                    for (index in voices.lastIndex downTo 0) {
                        if (voices[index].isFinished) {
                            voices.removeAt(index)
                        }
                    }
                }

                var offset = 0
                while (offset < buffer.size && isRunning) {
                    val written = track.write(
                        buffer,
                        offset,
                        buffer.size - offset,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    if (written <= 0) break
                    offset += written
                }
            }
        } finally {
            audioTrack = null
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
        }
    }

    private fun activeVoiceCount(): Int {
        var count = 0
        for (index in voices.indices) {
            if (!voices[index].isReleasing) {
                count += 1
            }
        }
        return count
    }

    private fun toPcm16(sample: Double): Short {
        val guarded = (sample * MasterGain).coerceIn(-1.0, 1.0)
        return (guarded * Short.MAX_VALUE).toInt().toShort()
    }

    private class PianoVoice(
        val noteId: Int,
        frequencyHz: Double,
    ) {
        private val phaseIncrement = 2.0 * PI * frequencyHz / SampleRate
        private var phase = 0.0
        private var ageSamples = 0
        private var releaseAgeSamples = 0
        private var releaseSamples = ReleaseSamples
        private var releaseStartEnvelope = 0.0

        var isReleasing = false
            private set
        var isFinished = false
            private set

        fun beginRelease(samples: Int) {
            if (isReleasing) return
            releaseStartEnvelope = pressEnvelope()
            releaseSamples = samples
            releaseAgeSamples = 0
            isReleasing = true
        }

        fun nextSample(): Double {
            if (isFinished) return 0.0

            val envelope = if (isReleasing) {
                val progress = releaseAgeSamples.toDouble() / releaseSamples
                releaseStartEnvelope * cosineFadeOut(progress)
            } else {
                pressEnvelope()
            }
            val sample = sin(phase) + sin(phase * 2.0) * SecondHarmonicLevel
            phase += phaseIncrement
            if (phase >= 2.0 * PI) phase -= 2.0 * PI

            ageSamples += 1
            if (isReleasing) {
                releaseAgeSamples += 1
                if (releaseAgeSamples >= releaseSamples) isFinished = true
            }

            return sample * envelope
        }

        private fun pressEnvelope(): Double {
            val age = ageSamples.toDouble()
            return when {
                age < AttackSamples -> {
                    val progress = (age / AttackSamples).coerceIn(0.0, 1.0)
                    progress
                }
                age < AttackSamples + DecaySamples -> {
                    val progress = ((age - AttackSamples) / DecaySamples).coerceIn(0.0, 1.0)
                    SustainLevel + (1.0 - SustainLevel) * cosineFadeOut(progress)
                }
                else -> {
                    val sustainAge = age - AttackSamples - DecaySamples
                    SustainLevel * SustainDampingPerSecond.pow(sustainAge / SampleRate)
                }
            }
        }
    }

    private companion object {
        fun cosineFadeOut(progress: Double): Double {
            return 0.5 + 0.5 * cos(PI * progress.coerceIn(0.0, 1.0))
        }

        const val SampleRate = 48_000
        const val ShortBytes = 2
        const val AttackSamples = SampleRate / 220
        const val DecaySamples = SampleRate / 2
        const val ReleaseSamples = SampleRate / 5
        const val VoiceStealReleaseSamples = SampleRate / 80
        const val ChunkSamples = 256
        const val StableBufferFrames = 2048
        const val LowLatencyBufferFrames = 1024
        const val MaxVoices = 10
        const val AudioThreadJoinMillis = 200L
        const val MasterGain = 0.16
        const val SecondHarmonicLevel = 0.06
        const val SustainLevel = 0.18
        const val SustainDampingPerSecond = 0.86
    }
}

private data class Pitch(
    val note: PitchClass,
    val octave: Int,
    val midiNumber: Int,
    val frequencyHz: Double,
) {
    val label: String = "${note.name}$octave"
    val isBlackKey: Boolean = "#" in note.name
}

private data class PitchClass(
    val name: String,
)

private fun pitchFromMidi(midiNumber: Int): Pitch {
    val note = PitchClasses[midiNumber % PitchClasses.size]
    val octave = midiNumber / PitchClasses.size - 1
    val frequencyHz = 440.0 * 2.0.pow((midiNumber - A4Midi).toDouble() / PitchClasses.size)
    return Pitch(
        note = note,
        octave = octave,
        midiNumber = midiNumber,
        frequencyHz = frequencyHz,
    )
}

private val PitchClasses = listOf(
    PitchClass("C"),
    PitchClass("C#"),
    PitchClass("D"),
    PitchClass("D#"),
    PitchClass("E"),
    PitchClass("F"),
    PitchClass("F#"),
    PitchClass("G"),
    PitchClass("G#"),
    PitchClass("A"),
    PitchClass("A#"),
    PitchClass("B"),
)
private val KeyboardPitches = (KeyboardStartMidi..KeyboardEndMidi).map(::pitchFromMidi)
private const val KeyboardStartMidi = 60
private const val KeyboardEndMidi = 76
private const val A4Midi = 69
