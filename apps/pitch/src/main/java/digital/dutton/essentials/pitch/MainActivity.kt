package digital.dutton.essentials.pitch

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
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
import java.util.concurrent.atomic.AtomicBoolean
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
        onDispose { player.stopAll() }
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
    private val activeTones = LinkedHashMap<Int, ActiveTone>()

    fun play(
        noteId: Int,
        frequencyHz: Double,
    ) {
        val stopSignal = AtomicBoolean(false)
        val tonesToStop = synchronized(lock) {
            buildList {
                activeTones.remove(noteId)?.let(::add)
                while (activeTones.size >= MaxVoices) {
                    val oldestNoteId = activeTones.keys.first()
                    activeTones.remove(oldestNoteId)?.let(::add)
                }
                activeTones[noteId] = ActiveTone(stopSignal)
            }
        }
        tonesToStop.forEach { it.stopSignal.set(true) }

        Thread {
            try {
                renderTone(frequencyHz, stopSignal)
            } finally {
                synchronized(lock) {
                    if (activeTones[noteId]?.stopSignal === stopSignal) {
                        activeTones.remove(noteId)
                    }
                }
            }
        }.apply {
            name = "Pitch tone $noteId"
            isDaemon = true
            start()
        }
    }

    fun stop(noteId: Int) {
        val tone = synchronized(lock) {
            activeTones.remove(noteId)
        }
        tone?.stopSignal?.set(true)
    }

    fun stopAll() {
        val tones = synchronized(lock) {
            activeTones.values.toList().also { activeTones.clear() }
        }
        tones.forEach { it.stopSignal.set(true) }
    }

    private fun renderTone(
        frequencyHz: Double,
        stopSignal: AtomicBoolean,
    ) {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val buffer = ShortArray(ChunkSamples)
        val amplitude = Short.MAX_VALUE * 0.22
        val phaseIncrement = 2.0 * PI * frequencyHz / SampleRate
        var phase = 0.0
        var sampleIndex = 0L
        var envelope = 0.0
        var releasing = false
        var releaseIndex = 0
        var releaseStartEnvelope = 0.0

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

        try {
            track.play()

            while (true) {
                var writtenSamples = 0

                while (writtenSamples < buffer.size) {
                    if (!releasing && stopSignal.get()) {
                        releasing = true
                        releaseIndex = 0
                        releaseStartEnvelope = envelope
                    }

                    if (releasing) {
                        envelope = releaseStartEnvelope * cosineFadeOut(
                            releaseIndex.toDouble() / ReleaseSamples,
                        )
                        releaseIndex += 1
                        if (releaseIndex > ReleaseSamples) break
                    } else {
                        envelope = cosineFadeIn(
                            (sampleIndex.toDouble() / AttackSamples).coerceIn(0.0, 1.0),
                        )
                    }

                    buffer[writtenSamples] = (sin(phase) * amplitude * envelope).toInt().toShort()
                    phase += phaseIncrement
                    if (phase >= 2.0 * PI) phase -= 2.0 * PI
                    sampleIndex += 1
                    writtenSamples += 1
                }

                if (writtenSamples <= 0) break
                track.write(buffer, 0, writtenSamples, AudioTrack.WRITE_BLOCKING)

                if (releasing && releaseIndex > ReleaseSamples) break
            }
        } finally {
            runCatching { Thread.sleep(OutputDrainMillis) }
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun cosineFadeIn(progress: Double): Double {
        return 0.5 - 0.5 * cos(PI * progress.coerceIn(0.0, 1.0))
    }

    private fun cosineFadeOut(progress: Double): Double {
        return 0.5 + 0.5 * cos(PI * progress.coerceIn(0.0, 1.0))
    }

    private data class ActiveTone(
        val stopSignal: AtomicBoolean,
    )

    private companion object {
        const val SampleRate = 48_000
        const val ShortBytes = 2
        const val AttackSamples = SampleRate / 60
        const val ReleaseSamples = SampleRate / 16
        const val ChunkSamples = 384
        const val OutputDrainMillis = 80L
        const val MaxVoices = 8
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
