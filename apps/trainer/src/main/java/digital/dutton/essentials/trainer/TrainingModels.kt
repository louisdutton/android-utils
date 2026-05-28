package digital.dutton.essentials.trainer

import kotlin.math.pow
import kotlin.random.Random

enum class PracticeMode(
    val label: String,
) {
    Theory("Theory"),
    SightReading("Sight reading"),
    Dictation("Dictation"),
}

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
    Treble("Treble", NoteName.E, 4),
    Bass("Bass", NoteName.G, 2);

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

data class TheoryChallenge(
    val category: String,
    val question: String,
    val detail: String,
    val options: List<String>,
    val correctAnswer: String,
)

data class StaffNoteChallenge(
    val clef: Clef,
    val pitch: NaturalPitch,
) {
    val correctAnswer: NoteName = pitch.note
    val staffStepsFromBottomLine: Int = pitch.diatonicNumber - clef.bottomLineDiatonicNumber
}

data class DictationChallenge(
    val keyLabel: String,
    val notes: List<NaturalPitch>,
    val tempoBpm: Int,
) {
    val correctAnswer: List<NoteName> = notes.map { it.note }
    val answerLabel: String = correctAnswer.joinToString(" ") { it.label }
}

object TrainingChallengeFactory {
    fun nextTheory(random: Random = Random.Default): TheoryChallenge {
        return when (random.nextInt(3)) {
            0 -> nextIntervalChallenge(random)
            1 -> nextTriadChallenge(random)
            else -> nextKeySignatureChallenge(random)
        }
    }

    fun nextStaffNote(random: Random = Random.Default): StaffNoteChallenge {
        val clef = Clef.entries.random(random)
        val pitchPool = when (clef) {
            Clef.Treble -> TrebleSightPitches
            Clef.Bass -> BassSightPitches
        }
        return StaffNoteChallenge(
            clef = clef,
            pitch = pitchPool.random(random),
        )
    }

    fun nextDictation(random: Random = Random.Default): DictationChallenge {
        val melody = buildList {
            var index = random.nextInt(DictationPitches.size)
            repeat(4) {
                add(DictationPitches[index])
                val step = listOf(-2, -1, 1, 2).random(random)
                index = (index + step).coerceIn(0, DictationPitches.lastIndex)
            }
        }
        return DictationChallenge(
            keyLabel = "C major",
            notes = melody,
            tempoBpm = 84,
        )
    }

    private fun nextIntervalChallenge(random: Random): TheoryChallenge {
        val example = IntervalExamples.random(random)
        return TheoryChallenge(
            category = "Interval",
            question = "${example.root} to ${example.target}",
            detail = "Ascending interval",
            options = shuffledOptions(
                correct = example.interval,
                pool = IntervalLabels,
                random = random,
            ),
            correctAnswer = example.interval,
        )
    }

    private fun nextTriadChallenge(random: Random): TheoryChallenge {
        val example = TriadExamples.random(random)
        return TheoryChallenge(
            category = "Triad",
            question = example.notes.joinToString("-"),
            detail = "Root ${example.root}",
            options = shuffledOptions(
                correct = example.quality,
                pool = TriadQualityLabels,
                random = random,
            ),
            correctAnswer = example.quality,
        )
    }

    private fun nextKeySignatureChallenge(random: Random): TheoryChallenge {
        val key = MajorKeySignatures.random(random)
        return TheoryChallenge(
            category = "Key signature",
            question = key.name,
            detail = "Major key",
            options = shuffledOptions(
                correct = key.signature,
                pool = MajorKeySignatures.map { it.signature }.distinct(),
                random = random,
            ),
            correctAnswer = key.signature,
        )
    }
}

fun midiToFrequencyHz(midiNumber: Int): Double {
    return 440.0 * 2.0.pow((midiNumber - A4Midi).toDouble() / 12.0)
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

private fun shuffledOptions(
    correct: String,
    pool: List<String>,
    random: Random,
    count: Int = 4,
): List<String> {
    val distractors = pool
        .filterNot { it == correct }
        .shuffled(random)
        .take(count - 1)
    return (distractors + correct).shuffled(random)
}

private data class IntervalExample(
    val root: String,
    val target: String,
    val interval: String,
)

private data class TriadExample(
    val root: String,
    val notes: List<String>,
    val quality: String,
)

private data class MajorKeySignature(
    val name: String,
    val signature: String,
)

private val IntervalLabels = listOf(
    "Minor second",
    "Major second",
    "Minor third",
    "Major third",
    "Perfect fourth",
    "Tritone",
    "Perfect fifth",
    "Minor sixth",
    "Major sixth",
    "Minor seventh",
    "Major seventh",
)

private val IntervalExamples = listOf(
    IntervalExample("C", "Db", "Minor second"),
    IntervalExample("C", "D", "Major second"),
    IntervalExample("C", "Eb", "Minor third"),
    IntervalExample("C", "E", "Major third"),
    IntervalExample("C", "F", "Perfect fourth"),
    IntervalExample("C", "F#", "Tritone"),
    IntervalExample("C", "G", "Perfect fifth"),
    IntervalExample("C", "Ab", "Minor sixth"),
    IntervalExample("C", "A", "Major sixth"),
    IntervalExample("C", "Bb", "Minor seventh"),
    IntervalExample("C", "B", "Major seventh"),
    IntervalExample("D", "Eb", "Minor second"),
    IntervalExample("D", "E", "Major second"),
    IntervalExample("D", "F", "Minor third"),
    IntervalExample("D", "F#", "Major third"),
    IntervalExample("D", "G", "Perfect fourth"),
    IntervalExample("D", "G#", "Tritone"),
    IntervalExample("D", "A", "Perfect fifth"),
    IntervalExample("D", "Bb", "Minor sixth"),
    IntervalExample("D", "B", "Major sixth"),
    IntervalExample("D", "C", "Minor seventh"),
    IntervalExample("D", "C#", "Major seventh"),
    IntervalExample("F", "Gb", "Minor second"),
    IntervalExample("F", "G", "Major second"),
    IntervalExample("F", "Ab", "Minor third"),
    IntervalExample("F", "A", "Major third"),
    IntervalExample("F", "Bb", "Perfect fourth"),
    IntervalExample("F", "B", "Tritone"),
    IntervalExample("F", "C", "Perfect fifth"),
    IntervalExample("F", "Db", "Minor sixth"),
    IntervalExample("F", "D", "Major sixth"),
    IntervalExample("F", "Eb", "Minor seventh"),
    IntervalExample("F", "E", "Major seventh"),
)

private val TriadQualityLabels = listOf(
    "Major",
    "Minor",
    "Diminished",
    "Augmented",
)

private val TriadExamples = listOf(
    TriadExample("C", listOf("C", "E", "G"), "Major"),
    TriadExample("D", listOf("D", "F#", "A"), "Major"),
    TriadExample("F", listOf("F", "A", "C"), "Major"),
    TriadExample("G", listOf("G", "B", "D"), "Major"),
    TriadExample("A", listOf("A", "C", "E"), "Minor"),
    TriadExample("D", listOf("D", "F", "A"), "Minor"),
    TriadExample("E", listOf("E", "G", "B"), "Minor"),
    TriadExample("G", listOf("G", "Bb", "D"), "Minor"),
    TriadExample("B", listOf("B", "D", "F"), "Diminished"),
    TriadExample("C", listOf("C", "Eb", "Gb"), "Diminished"),
    TriadExample("F#", listOf("F#", "A", "C"), "Diminished"),
    TriadExample("C", listOf("C", "E", "G#"), "Augmented"),
    TriadExample("F", listOf("F", "A", "C#"), "Augmented"),
    TriadExample("Ab", listOf("Ab", "C", "E"), "Augmented"),
)

private val MajorKeySignatures = listOf(
    MajorKeySignature("C major", "0 sharps or flats"),
    MajorKeySignature("G major", "1 sharp"),
    MajorKeySignature("D major", "2 sharps"),
    MajorKeySignature("A major", "3 sharps"),
    MajorKeySignature("E major", "4 sharps"),
    MajorKeySignature("B major", "5 sharps"),
    MajorKeySignature("F# major", "6 sharps"),
    MajorKeySignature("F major", "1 flat"),
    MajorKeySignature("Bb major", "2 flats"),
    MajorKeySignature("Eb major", "3 flats"),
    MajorKeySignature("Ab major", "4 flats"),
    MajorKeySignature("Db major", "5 flats"),
    MajorKeySignature("Gb major", "6 flats"),
)

private val TrebleSightPitches = naturalPitchRange(
    first = NaturalPitch(NoteName.C, 4),
    last = NaturalPitch(NoteName.A, 5),
)

private val BassSightPitches = naturalPitchRange(
    first = NaturalPitch(NoteName.E, 2),
    last = NaturalPitch(NoteName.C, 4),
)

private val DictationPitches = naturalPitchRange(
    first = NaturalPitch(NoteName.C, 4),
    last = NaturalPitch(NoteName.B, 4),
)

private const val A4Midi = 69
