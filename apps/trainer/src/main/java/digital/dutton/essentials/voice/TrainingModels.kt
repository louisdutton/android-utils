package digital.dutton.essentials.voice

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

enum class NoteName(
    val label: String,
    val semitone: Int,
) {
    C("C", 0),
    D("D", 2),
    E("E", 4),
    F("F", 5),
    G("G", 7),
    A("A", 9),
    B("B", 11),
}

enum class Clef(
    val label: String,
    private val bottomLineNote: NoteName,
    private val bottomLineOctave: Int,
) {
    Treble("Treble", NoteName.E, 4);

    val bottomLineDiatonicNumber: Int
        get() = diatonicNumber(bottomLineNote, bottomLineOctave)
}

data class NaturalPitch(
    val note: NoteName,
    val octave: Int,
) {
    val label: String = "${note.label}$octave"
    val midiNumber: Int = 12 * (octave + 1) + note.semitone
    val frequencyHz: Double = midiToFrequencyHz(midiNumber)
    val diatonicNumber: Int = diatonicNumber(note, octave)
}

data class SightSingingChallenge(
    val clef: Clef,
    val pitch: NaturalPitch,
) {
    val staffStepsFromBottomLine: Int = pitch.diatonicNumber - clef.bottomLineDiatonicNumber
}

data class PitchObservation(
    val frequencyHz: Double?,
    val confidence: Double,
    val level: Float,
)

object SightSingingChallengeFactory {
    fun next(random: Random = Random.Default): SightSingingChallenge {
        return SightSingingChallenge(
            clef = Clef.Treble,
            pitch = SightSingingPitches.random(random),
        )
    }
}

fun midiToFrequencyHz(midiNumber: Int): Double {
    return 440.0 * 2.0.pow((midiNumber - A4Midi).toDouble() / SemitonesPerOctave)
}

fun centsBetween(
    frequencyHz: Double,
    targetFrequencyHz: Double,
): Double {
    return SemitonesPerOctave * CentsPerSemitone *
        ln(frequencyHz / targetFrequencyHz) / ln(2.0)
}

fun nearestPitchLabel(frequencyHz: Double): String {
    val midi = (A4Midi + SemitonesPerOctave * ln(frequencyHz / 440.0) / ln(2.0))
        .roundToInt()
        .coerceIn(0, 127)
    val note = ChromaticPitchLabels[midi.mod(ChromaticPitchLabels.size)]
    val octave = midi / ChromaticPitchLabels.size - 1
    return "$note$octave"
}

private fun diatonicNumber(
    note: NoteName,
    octave: Int,
): Int {
    return octave * NoteName.entries.size + note.ordinal
}

private fun pitchFromDiatonicNumber(number: Int): NaturalPitch {
    val notes = NoteName.entries
    return NaturalPitch(
        note = notes[number.mod(notes.size)],
        octave = number.floorDiv(notes.size),
    )
}

private fun naturalPitchRange(
    first: NaturalPitch,
    last: NaturalPitch,
): List<NaturalPitch> {
    return (first.diatonicNumber..last.diatonicNumber).map(::pitchFromDiatonicNumber)
}

private val SightSingingPitches = naturalPitchRange(
    first = NaturalPitch(NoteName.C, 4),
    last = NaturalPitch(NoteName.G, 5),
)

val ReferencePitch = NaturalPitch(NoteName.C, 4)

private val ChromaticPitchLabels = listOf(
    "C",
    "C#",
    "D",
    "D#",
    "E",
    "F",
    "F#",
    "G",
    "G#",
    "A",
    "A#",
    "B",
)

private const val A4Midi = 69
private const val SemitonesPerOctave = 12.0
private const val CentsPerSemitone = 100.0
