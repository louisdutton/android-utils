package digital.dutton.essentials.scores

import kotlin.math.abs

object HomrMusicXmlWriter {
    private const val Divisions = 24
    private const val MeasureDuration = Divisions * 4

    fun write(
        title: String,
        symbols: List<HomrSymbol>,
    ): ByteArray {
        return writeParts(
            title = title,
            parts = listOf(HomrPart(name = "Score", symbols = symbols)),
        )
    }

    fun writeParts(
        title: String,
        parts: List<HomrPart>,
    ): ByteArray {
        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8" standalone="no"?>""")
            appendLine("""<score-partwise version="4.0">""")
            appendLine("  <work><work-title>${title.escapeXml()}</work-title></work>")
            appendLine("  <part-list>")
            parts.forEachIndexed { index, part ->
                appendLine("""    <score-part id="P${index + 1}"><part-name>${part.name.escapeXml()}</part-name></score-part>""")
            }
            appendLine("  </part-list>")
            parts.forEachIndexed { index, part ->
                appendLine("""  <part id="P${index + 1}">""")
                append(buildPart(part))
                appendLine("  </part>")
            }
            appendLine("</score-partwise>")
        }
        return xml.encodeToByteArray()
    }

    private fun buildPart(part: HomrPart): String {
        val staves = part.staves
        return if (staves.size <= 1) {
            buildSingleStaffPart(staves.firstOrNull().orEmpty())
        } else {
            buildMultiStaffPart(staves)
        }
    }

    private fun buildSingleStaffPart(symbols: List<HomrSymbol>): String {
        val out = StringBuilder()
        var measureNumber = 1
        var measureDuration = 0
        var measureOpen = false
        var pendingChord = false
        var clef = Clef(sign = "G", line = 2)
        var fifths = 0
        var beatType = 4
        var beats = 4
        val beamer = BeamBuffer { event ->
            out.append(
                event.symbol.toMusicXmlNote(
                    duration = event.duration,
                    isChord = event.isChord,
                    staffNumber = event.staffNumber,
                    voice = event.voice,
                    beam = event.beam,
                ),
            )
        }

        fun openMeasure(forceAttributes: Boolean = false) {
            if (measureOpen) return
            out.appendLine("""    <measure number="$measureNumber">""")
            measureOpen = true
            if (measureNumber == 1 || forceAttributes) {
                out.appendLine("      <attributes>")
                out.appendLine("        <divisions>$Divisions</divisions>")
                out.appendLine("        <key><fifths>$fifths</fifths></key>")
                out.appendLine("        <time><beats>$beats</beats><beat-type>$beatType</beat-type></time>")
                out.appendLine("        <clef><sign>${clef.sign}</sign><line>${clef.line}</line></clef>")
                out.appendLine("      </attributes>")
            }
        }

        fun closeMeasure() {
            if (!measureOpen) return
            beamer.flush()
            if (measureDuration == 0) {
                out.appendLine("      <note><rest measure=\"yes\"/><duration>$MeasureDuration</duration><voice>1</voice><type>whole</type></note>")
            } else if (measureDuration < MeasureDuration) {
                val missing = MeasureDuration - measureDuration
                out.appendLine("      <note><rest/><duration>$missing</duration><voice>1</voice><type>${durationType(missing)}</type></note>")
            }
            out.appendLine("    </measure>")
            measureOpen = false
            measureDuration = 0
            measureNumber += 1
        }

        openMeasure(forceAttributes = true)
        for (symbol in symbols) {
            when {
                symbol.rhythm == "chord" -> {
                    pendingChord = true
                }
                symbol.rhythm.contains("barline") || symbol.rhythm.startsWith("repeatEnd") -> {
                    closeMeasure()
                    openMeasure()
                }
                symbol.rhythm.startsWith("repeatStart") -> {
                    closeMeasure()
                    openMeasure()
                }
                symbol.rhythm.startsWith("clef") -> {
                    clef = symbol.toClef() ?: clef
                    openMeasure(forceAttributes = true)
                    out.appendLine("      <attributes>")
                    out.appendLine("        <clef><sign>${clef.sign}</sign><line>${clef.line}</line></clef>")
                    out.appendLine("      </attributes>")
                }
                symbol.rhythm.startsWith("keySignature") -> {
                    fifths = symbol.rhythm.substringAfter('_').toIntOrNull() ?: fifths
                    openMeasure(forceAttributes = true)
                    out.appendLine("      <attributes><key><fifths>$fifths</fifths></key></attributes>")
                }
                symbol.rhythm.startsWith("timeSignature") -> {
                    beatType = symbol.rhythm.substringAfter('/').toIntOrNull()?.takeIf { it > 0 } ?: beatType
                    beats = 4
                    openMeasure(forceAttributes = true)
                    out.appendLine("      <attributes><time><beats>$beats</beats><beat-type>$beatType</beat-type></time></attributes>")
                }
                symbol.rhythm.startsWith("note") || symbol.rhythm.startsWith("rest") -> {
                    val note = symbol.toNoteDuration()
                    if (!pendingChord && measureDuration + note.duration > MeasureDuration && measureDuration > 0) {
                        closeMeasure()
                        openMeasure()
                    }
                    openMeasure()
                    beamer.append(
                        NoteEvent(
                            symbol = symbol,
                            duration = note,
                            isChord = pendingChord,
                            staffNumber = null,
                            voice = 1,
                        ),
                    )
                    if (!pendingChord) {
                        measureDuration += note.duration
                    }
                    pendingChord = false
                }
            }
        }
        closeMeasure()
        return out.toString()
    }

    private fun buildMultiStaffPart(staves: List<List<HomrSymbol>>): String {
        val measuresByStaff = staves.map(::splitMeasures)
        val measureCount = measuresByStaff.maxOfOrNull { it.size } ?: 1
        val clefs = staves.mapIndexed { index, symbols ->
            symbols.firstNotNullOfOrNull { it.toClef() } ?: if (index == 1) Clef("F", 4) else Clef("G", 2)
        }
        val out = StringBuilder()

        for (measureIndex in 0 until measureCount) {
            out.appendLine("""    <measure number="${measureIndex + 1}">""")
            if (measureIndex == 0) {
                out.appendLine("      <attributes>")
                out.appendLine("        <divisions>$Divisions</divisions>")
                out.appendLine("        <key><fifths>0</fifths></key>")
                out.appendLine("        <time><beats>4</beats><beat-type>4</beat-type></time>")
                out.appendLine("        <staves>${staves.size}</staves>")
                clefs.forEachIndexed { staffIndex, clef ->
                    out.appendLine("""        <clef number="${staffIndex + 1}"><sign>${clef.sign}</sign><line>${clef.line}</line></clef>""")
                }
                out.appendLine("      </attributes>")
            }

            staves.forEachIndexed { staffIndex, _ ->
                if (staffIndex > 0) {
                    out.appendLine("      <backup><duration>$MeasureDuration</duration></backup>")
                }
                val symbols = measuresByStaff[staffIndex].getOrNull(measureIndex).orEmpty()
                appendStaffMeasure(
                    out = out,
                    symbols = symbols,
                    staffNumber = staffIndex + 1,
                    voice = staffIndex + 1,
                )
            }
            out.appendLine("    </measure>")
        }

        return out.toString()
    }

    private fun splitMeasures(symbols: List<HomrSymbol>): List<List<HomrSymbol>> {
        val measures = mutableListOf<MutableList<HomrSymbol>>()
        var current = mutableListOf<HomrSymbol>()
        fun closeCurrent() {
            measures += current
            current = mutableListOf()
        }

        for (symbol in symbols) {
            when {
                symbol.rhythm.contains("barline") || symbol.rhythm.startsWith("repeatEnd") || symbol.rhythm.startsWith("repeatStart") -> {
                    closeCurrent()
                }
                else -> current += symbol
            }
        }
        if (current.isNotEmpty() || measures.isEmpty()) closeCurrent()
        return measures
    }

    private fun appendStaffMeasure(
        out: StringBuilder,
        symbols: List<HomrSymbol>,
        staffNumber: Int,
        voice: Int,
    ) {
        var measureDuration = 0
        var pendingChord = false
        val beamer = BeamBuffer { event ->
            out.append(
                event.symbol.toMusicXmlNote(
                    duration = event.duration,
                    isChord = event.isChord,
                    staffNumber = event.staffNumber,
                    voice = event.voice,
                    beam = event.beam,
                ),
            )
        }

        for (symbol in symbols) {
            when {
                symbol.rhythm == "chord" -> pendingChord = true
                symbol.rhythm.startsWith("note") || symbol.rhythm.startsWith("rest") -> {
                    val note = symbol.toNoteDuration()
                    if (!pendingChord && measureDuration + note.duration > MeasureDuration && measureDuration > 0) break
                    beamer.append(
                        NoteEvent(
                            symbol = symbol,
                            duration = note,
                            isChord = pendingChord,
                            staffNumber = staffNumber,
                            voice = voice,
                        ),
                    )
                    if (!pendingChord) {
                        measureDuration += note.duration
                    }
                    pendingChord = false
                }
            }
        }
        beamer.flush()

        if (measureDuration == 0) {
            out.appendLine(
                """      <note><rest measure="yes"/><duration>$MeasureDuration</duration><voice>$voice</voice><type>whole</type><staff>$staffNumber</staff></note>""",
            )
        } else if (measureDuration < MeasureDuration) {
            val missing = MeasureDuration - measureDuration
            out.appendLine(
                """      <note><rest/><duration>$missing</duration><voice>$voice</voice><type>${durationType(missing)}</type><staff>$staffNumber</staff></note>""",
            )
        }
    }

    private fun HomrSymbol.toMusicXmlNote(
        duration: NoteDuration,
        isChord: Boolean,
        staffNumber: Int? = null,
        voice: Int = 1,
        beam: String? = null,
    ): String = buildString {
        val isRest = rhythm.startsWith("rest") || pitch == "." || pitch == "_"
        appendLine("      <note>")
        if (isChord) appendLine("        <chord/>")
        if (isRest) {
            if (rhythm.endsWith("m")) {
                appendLine("""        <rest measure="yes"/>""")
            } else {
                appendLine("        <rest/>")
            }
        } else {
            appendLine("        <pitch>")
            appendLine("          <step>${pitch.first()}</step>")
            lift.toAlter()?.let { appendLine("          <alter>$it</alter>") }
            appendLine("          <octave>${pitch.drop(1).toIntOrNull() ?: 4}</octave>")
            appendLine("        </pitch>")
        }
        appendLine("        <duration>${duration.duration}</duration>")
        appendLine("        <voice>$voice</voice>")
        appendLine("        <type>${duration.type}</type>")
        repeat(duration.dots) { appendLine("        <dot/>") }
        if (!isRest) {
            lift.toAccidental()?.let { appendLine("        <accidental>$it</accidental>") }
        }
        staffNumber?.let { appendLine("        <staff>$it</staff>") }
        beam?.let { appendLine("""        <beam number="1">$it</beam>""") }
        appendLine("      </note>")
    }

    private fun HomrSymbol.toClef(): Clef? {
        val signAndLine = rhythm.substringAfter('_', "")
        if (signAndLine.length < 2) return null
        val sign = signAndLine.first().toString()
        val line = signAndLine.drop(1).toIntOrNull() ?: return null
        return Clef(sign, line)
    }

    private fun HomrSymbol.toNoteDuration(): NoteDuration {
        val raw = rhythm.substringAfter('_', "4").removeSuffix("m")
        val numeric = raw.takeWhile { it.isDigit() }.toIntOrNull() ?: 4
        val dots = raw.count { it == '.' }.coerceIn(0, 2)
        val base = when (numeric) {
            0 -> MeasureDuration
            1 -> Divisions * 4
            2 -> Divisions * 2
            3 -> Divisions * 4 / 3
            4 -> Divisions
            5, 6 -> (Divisions * 4f / numeric).toInt().coerceAtLeast(1)
            8 -> Divisions / 2
            10, 12 -> (Divisions * 4f / numeric).toInt().coerceAtLeast(1)
            16 -> Divisions / 4
            32 -> Divisions / 8
            64 -> Divisions / 16
            128 -> (Divisions / 32).coerceAtLeast(1)
            else -> (Divisions * 4f / numeric).toInt().coerceAtLeast(1)
        }
        var duration = base
        var add = base / 2
        repeat(dots) {
            duration += add
            add /= 2
        }
        return NoteDuration(
            duration = duration.coerceAtLeast(1),
            type = durationType(base),
            dots = dots,
        )
    }

    private fun durationType(duration: Int): String {
        val closest = listOf(
            Divisions * 4 to "whole",
            Divisions * 2 to "half",
            Divisions to "quarter",
            Divisions / 2 to "eighth",
            Divisions / 4 to "16th",
            Divisions / 8 to "32nd",
            Divisions / 16 to "64th",
        ).minBy { abs(it.first - duration) }
        return closest.second
    }

    private fun String.toAlter(): Int? {
        return when (this) {
            "N" -> 0
            "#" -> 1
            "##" -> 2
            "b" -> -1
            "bb" -> -2
            else -> null
        }
    }

    private fun String.toAccidental(): String? {
        return when (this) {
            "N" -> "natural"
            "#" -> "sharp"
            "##" -> "double-sharp"
            "b" -> "flat"
            "bb" -> "flat-flat"
            else -> null
        }
    }

    private class BeamBuffer(
        private val emit: (NoteEvent) -> Unit,
    ) {
        private val pending = mutableListOf<NoteEvent>()
        private var duration = 0

        fun append(event: NoteEvent) {
            if (!event.isBeamable) {
                flush()
                emit(event)
                return
            }

            pending += event
            if (!event.isChord) {
                duration += event.duration.duration
            }
            if (duration >= Divisions) {
                flush()
            }
        }

        fun flush() {
            if (pending.size < 2) {
                pending.forEach(emit)
            } else {
                pending.forEachIndexed { index, event ->
                    val beam = when (index) {
                        0 -> "begin"
                        pending.lastIndex -> "end"
                        else -> "continue"
                    }
                    emit(event.copy(beam = beam))
                }
            }
            pending.clear()
            duration = 0
        }
    }

    private data class NoteEvent(
        val symbol: HomrSymbol,
        val duration: NoteDuration,
        val isChord: Boolean,
        val staffNumber: Int?,
        val voice: Int,
        val beam: String? = null,
    ) {
        val isBeamable: Boolean
            get() = !symbol.rhythm.startsWith("rest") &&
                symbol.pitch != "." &&
                symbol.pitch != "_" &&
                duration.type in BeamableTypes
    }

    private data class NoteDuration(
        val duration: Int,
        val type: String,
        val dots: Int,
    )

    private data class Clef(
        val sign: String,
        val line: Int,
    )

    private val BeamableTypes = setOf("eighth", "16th", "32nd", "64th")
}

data class HomrPart(
    val name: String,
    val symbols: List<HomrSymbol>,
    val additionalStaves: List<List<HomrSymbol>> = emptyList(),
) {
    val staves: List<List<HomrSymbol>>
        get() = listOf(symbols) + additionalStaves
}

private fun String.escapeXml(): String {
    return buildString {
        for (char in this@escapeXml) {
            append(
                when (char) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> char
                },
            )
        }
    }
}
