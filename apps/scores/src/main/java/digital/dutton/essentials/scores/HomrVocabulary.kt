package digital.dutton.essentials.scores

data class HomrSymbol(
    val rhythm: String,
    val lift: String,
    val pitch: String,
    val articulationIndex: Int,
    val position: String,
    val lyric: HomrLyric? = null,
)

data class HomrLyric(
    val text: String,
    val syllabic: String = "single",
)

object HomrVocabulary {
    private val rhythms: List<String> = buildList {
        addAll(listOf("PAD", "BOS", "EOS", "chord"))
        addAll(listOf("barline", "doublebarline", "bolddoublebarline"))
        addAll(listOf("repeatStart", "repeatEnd", "repeatEndStart"))
        addAll(listOf("voltaStart", "voltaStop", "voltaDiscontinue"))
        for (line in 3..5) add("clef_F$line")
        for (line in 1..5) add("clef_C$line")
        for (line in 1..2) add("clef_G$line")
        for (fifths in -7..7) add("keySignature_$fifths")
        listOf(1, 2, 3, 4, 6, 8, 12, 16, 32, 48).forEach { add("timeSignature/$it") }
        for (count in 2..10) add("rest_${count}m")

        val baseDurations = listOf(0, 1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 32, 64, 128)
        val dots = listOf("", ".", "..")
        val grace = listOf("", "G")
        val kernValues = baseDurations.flatMap { duration ->
            grace.flatMap { graceMark ->
                dots.map { dot -> "$duration$graceMark$dot" }
            }
        }
        val irregular = listOf(7, 11, 13, 18, 20, 21, 22, 24, 26, 28, 30, 34, 36, 40, 48, 56, 96)

        kernValues.forEach { add("note_$it") }
        irregular.forEach { add("note_$it") }
        kernValues.forEach { add("rest_$it") }
        irregular.forEach { add("rest_$it") }
        add("tieSlur")
    }

    private val lifts = listOf(".", "_", "#", "##", "N", "b", "bb")
    private val positions = listOf(".", "upper", "lower")
    private val pitches: List<String> = buildList {
        add(".")
        add("_")
        val noteNames = listOf("C", "D", "E", "F", "G", "A", "B")
        val generated = mutableListOf<String>()
        for (octave in 0..9) {
            for (note in noteNames) {
                generated += "$note$octave"
            }
        }
        addAll(generated.asReversed())
    }

    fun decode(tokens: Array<LongArray>): List<HomrSymbol> {
        val symbols = mutableListOf<HomrSymbol>()
        val maxTokens = tokens.minOfOrNull { it.size } ?: return emptyList()
        for (index in 0 until maxTokens) {
            val rhythmIndex = tokens[0][index].toInt() - 1
            if (rhythmIndex == -1 || rhythmIndex == 2) break
            val rhythm = rhythms.getOrNull(rhythmIndex) ?: continue
            if (rhythm == "PAD" || rhythm == "BOS" || rhythm == "EOS") continue
            symbols += HomrSymbol(
                rhythm = rhythm,
                lift = lifts.getOrNull(tokens[1][index].toInt() - 1) ?: ".",
                pitch = pitches.getOrNull(tokens[2][index].toInt() - 1) ?: ".",
                articulationIndex = tokens[3][index].toInt() - 1,
                position = positions.getOrNull(tokens[4][index].toInt() - 1) ?: "upper",
            )
        }
        return cleanup(symbols)
    }

    private fun cleanup(symbols: List<HomrSymbol>): List<HomrSymbol> {
        val cleaned = mutableListOf<HomrSymbol>()
        var lastClef: String? = null
        var lastKey: String? = null
        var lastTime: String? = null

        for (symbol in symbols) {
            when {
                symbol.rhythm.startsWith("clef") -> {
                    if (symbol.rhythm != lastClef) cleaned += symbol
                    lastClef = symbol.rhythm
                }
                symbol.rhythm.startsWith("keySignature") -> {
                    if (symbol.rhythm != lastKey) cleaned += symbol
                    lastKey = symbol.rhythm
                }
                symbol.rhythm.startsWith("timeSignature") -> {
                    if (symbol.rhythm != lastTime) cleaned += symbol
                    lastTime = symbol.rhythm
                }
                else -> cleaned += symbol
            }
        }
        return cleaned
    }
}
