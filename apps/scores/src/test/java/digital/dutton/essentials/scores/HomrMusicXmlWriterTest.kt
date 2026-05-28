package digital.dutton.essentials.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomrMusicXmlWriterTest {
    @Test
    fun writesValidMusicXmlForRecognizedSymbols() {
        val musicXml = HomrMusicXmlWriter.write(
            title = "Recognized",
            symbols = listOf(
                HomrSymbol("clef_G2", ".", ".", -1, "upper"),
                HomrSymbol("keySignature_0", ".", ".", -1, "upper"),
                HomrSymbol("timeSignature/4", ".", ".", -1, "upper"),
                HomrSymbol("note_4", ".", "C4", -1, "upper"),
                HomrSymbol("note_4", ".", "D4", -1, "upper"),
                HomrSymbol("note_4", ".", "E4", -1, "upper"),
                HomrSymbol("note_4", ".", "F4", -1, "upper"),
            ),
        )

        assertEquals("Recognized", MusicXmlFiles.title(musicXml))
        assertTrue("<!DOCTYPE" !in musicXml.decodeToString())
        assertFalse(MusicXmlFiles.validate(musicXml).any { it.code == "musicxml_parse_failed" })
        assertFalse(MusicXmlFiles.validate(musicXml).any { it.code == "measure_duration_mismatch" })
    }

    @Test
    fun writesMultiplePartsWithoutInflatingPerPartMeasureCount() {
        val part = listOf(
            HomrSymbol("note_1", ".", "C4", -1, "upper"),
            HomrSymbol("barline", ".", ".", -1, "upper"),
            HomrSymbol("note_1", ".", "D4", -1, "upper"),
        )

        val musicXml = HomrMusicXmlWriter.writeParts(
            title = "Recognized",
            parts = listOf(
                HomrPart("Voice", part),
                HomrPart("Piano", part),
            ),
        )

        assertEquals(4, MusicXmlFiles.measureCount(musicXml))
        assertEquals(2, MusicXmlFiles.maxMeasureCountInPart(musicXml))
    }

    @Test
    fun writesGrandStaffAsOneMultiStaffPart() {
        val musicXml = HomrMusicXmlWriter.writeParts(
            title = "Piano",
            parts = listOf(
                HomrPart(
                    name = "Piano",
                    symbols = listOf(HomrSymbol("note_1", ".", "C4", -1, "upper")),
                    additionalStaves = listOf(
                        listOf(HomrSymbol("note_1", ".", "C3", -1, "upper")),
                    ),
                ),
            ),
        ).decodeToString()

        assertEquals(1, "<score-part id=".toRegex().findAll(musicXml).count())
        assertTrue("<staves>2</staves>" in musicXml)
        assertTrue("<staff>1</staff>" in musicXml)
        assertTrue("<staff>2</staff>" in musicXml)
    }

    @Test
    fun writesBeamsForAdjacentEighthNotes() {
        val musicXml = HomrMusicXmlWriter.write(
            title = "Beamed",
            symbols = listOf(
                HomrSymbol("note_8", ".", "C4", -1, "upper"),
                HomrSymbol("note_8", ".", "D4", -1, "upper"),
                HomrSymbol("note_8", ".", "E4", -1, "upper"),
                HomrSymbol("note_8", ".", "F4", -1, "upper"),
            ),
        ).decodeToString()

        assertEquals(2, """<beam number="1">begin</beam>""".toRegex().findAll(musicXml).count())
        assertEquals(2, """<beam number="1">end</beam>""".toRegex().findAll(musicXml).count())
    }

    @Test
    fun doesNotBeamAcrossRestsOrBeatBoundaries() {
        val musicXml = HomrMusicXmlWriter.write(
            title = "Rest separated beams",
            symbols = listOf(
                HomrSymbol("note_8", ".", "G4", -1, "upper"),
                HomrSymbol("rest_16", ".", ".", -1, "upper"),
                HomrSymbol("note_16", ".", "A4", -1, "upper"),
                HomrSymbol("note_8", ".", "B4", -1, "upper"),
                HomrSymbol("note_8", ".", "C5", -1, "upper"),
            ),
        ).decodeToString()

        val noteBlocks = Regex("""<note>.*?</note>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(musicXml)
            .map { it.value }
            .toList()
        assertFalse("<beam" in noteBlocks[0])
        assertFalse("<beam" in noteBlocks[1])
        assertFalse("<beam" in noteBlocks[2])
        assertTrue("""<beam number="1">begin</beam>""" in noteBlocks[3])
        assertTrue("""<beam number="1">end</beam>""" in noteBlocks[4])
    }

    @Test
    fun writesExplicitAccidentals() {
        val musicXml = HomrMusicXmlWriter.write(
            title = "Accidentals",
            symbols = listOf(
                HomrSymbol("note_4", "#", "C4", -1, "upper"),
                HomrSymbol("note_4", "N", "C4", -1, "upper"),
                HomrSymbol("note_4", "b", "E4", -1, "upper"),
                HomrSymbol("note_4", "##", "F4", -1, "upper"),
            ),
        ).decodeToString()

        assertTrue("<accidental>sharp</accidental>" in musicXml)
        assertTrue("<accidental>natural</accidental>" in musicXml)
        assertTrue("<accidental>flat</accidental>" in musicXml)
        assertTrue("<accidental>double-sharp</accidental>" in musicXml)
    }

    @Test
    fun suppressesRepeatedVisibleAccidentalsWithinMeasure() {
        val musicXml = HomrMusicXmlWriter.write(
            title = "Repeated accidentals",
            symbols = listOf(
                HomrSymbol("note_4", "#", "F4", -1, "upper"),
                HomrSymbol("note_4", "#", "F4", -1, "upper"),
                HomrSymbol("note_4", "N", "F4", -1, "upper"),
                HomrSymbol("note_4", "N", "F4", -1, "upper"),
                HomrSymbol("barline", ".", ".", -1, "upper"),
                HomrSymbol("note_1", "#", "F4", -1, "upper"),
            ),
        ).decodeToString()

        assertEquals(3, "<alter>1</alter>".toRegex().findAll(musicXml).count())
        assertEquals(2, "<alter>0</alter>".toRegex().findAll(musicXml).count())
        assertEquals(2, "<accidental>sharp</accidental>".toRegex().findAll(musicXml).count())
        assertEquals(1, "<accidental>natural</accidental>".toRegex().findAll(musicXml).count())
    }

    @Test
    fun suppressesAccidentalsAlreadyImpliedByKeySignature() {
        val musicXml = HomrMusicXmlWriter.write(
            title = "Key accidentals",
            symbols = listOf(
                HomrSymbol("keySignature_1", ".", ".", -1, "upper"),
                HomrSymbol("note_4", "#", "F4", -1, "upper"),
                HomrSymbol("note_4", "#", "F4", -1, "upper"),
                HomrSymbol("note_4", "N", "F4", -1, "upper"),
                HomrSymbol("note_4", "N", "F4", -1, "upper"),
            ),
        ).decodeToString()

        assertEquals(0, "<accidental>sharp</accidental>".toRegex().findAll(musicXml).count())
        assertEquals(1, "<accidental>natural</accidental>".toRegex().findAll(musicXml).count())
    }

    @Test
    fun writesLyricsOnNotes() {
        val musicXml = HomrMusicXmlWriter.write(
            title = "Lyrics",
            symbols = listOf(
                HomrSymbol("note_4", ".", "C4", -1, "upper", HomrLyric("Fron", "begin")),
                HomrSymbol("note_4", ".", "D4", -1, "upper", HomrLyric("di", "end")),
            ),
        ).decodeToString()

        assertTrue("<lyric>" in musicXml)
        assertTrue("<syllabic>begin</syllabic>" in musicXml)
        assertTrue("<text>Fron</text>" in musicXml)
        assertTrue("<syllabic>end</syllabic>" in musicXml)
        assertTrue("<text>di</text>" in musicXml)
    }

    @Test
    fun decodesTransformerTokensUntilEndOfSequence() {
        val symbols = HomrVocabulary.decode(
            arrayOf(
                longArrayOf(5, 3),
                longArrayOf(1, 1),
                longArrayOf(1, 1),
                longArrayOf(1, 1),
                longArrayOf(2, 1),
            ),
        )

        assertEquals(listOf("barline"), symbols.map { it.rhythm })
    }
}
