package digital.dutton.essentials.scores

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicXmlFilesTest {
    @Test
    fun normalizesCompressedMusicXmlUsingContainerRootfile() {
        val archive = mxlArchive("scores/root.musicxml", ValidMusicXml)
        val normalized = MusicXmlFiles.normalize(
            bytes = archive,
            mimeType = ScoreMimeTypes.CompressedMusicXml,
            displayName = "fixture.mxl",
        )

        assertEquals("Fixture", MusicXmlFiles.title(normalized.bytes))
        assertTrue(normalized.warnings.isEmpty())
    }

    @Test
    fun reportsMalformedMusicXml() {
        val warnings = MusicXmlFiles.validate("<score-partwise>".encodeToByteArray())

        assertTrue(warnings.any { it.code == "musicxml_parse_failed" })
    }

    @Test
    fun ignoresMusicXmlDocumentTypeDuringValidation() {
        val warnings = MusicXmlFiles.validate(
            ValidMusicXml.replace(
                "<score-partwise version=\"4.0\">",
                """
                <!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
                <score-partwise version="4.0">
                """.trimIndent(),
            ).encodeToByteArray(),
        )

        assertTrue(warnings.none { it.code == "musicxml_parse_failed" })
    }

    @Test
    fun reportsSingleVoiceMeasureDurationMismatch() {
        val warnings = MusicXmlFiles.validate(MismatchedDurationMusicXml.encodeToByteArray())

        assertTrue(warnings.any { it.code == "measure_duration_mismatch" })
    }

    @Test
    fun countsMeasures() {
        assertEquals(1, MusicXmlFiles.measureCount(ValidMusicXml.encodeToByteArray()))
    }

    private fun mxlArchive(
        rootPath: String,
        musicXml: String,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write(ScoreMimeTypes.CompressedMusicXml.encodeToByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0">
                  <rootfiles>
                    <rootfile full-path="$rootPath" media-type="${ScoreMimeTypes.MusicXml}"/>
                  </rootfiles>
                </container>
                """.trimIndent().encodeToByteArray(),
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(rootPath))
            zip.write(musicXml.encodeToByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}

private val ValidMusicXml = """
    <?xml version="1.0" encoding="UTF-8"?>
    <score-partwise version="4.0">
      <movement-title>Fixture</movement-title>
      <part-list>
        <score-part id="P1"><part-name>Piano</part-name></score-part>
      </part-list>
      <part id="P1">
        <measure number="1">
          <attributes>
            <divisions>1</divisions>
            <key><fifths>0</fifths></key>
            <time><beats>4</beats><beat-type>4</beat-type></time>
            <clef><sign>G</sign><line>2</line></clef>
          </attributes>
          <note>
            <pitch><step>C</step><octave>4</octave></pitch>
            <duration>4</duration>
            <type>whole</type>
          </note>
        </measure>
      </part>
    </score-partwise>
""".trimIndent()

private val MismatchedDurationMusicXml = ValidMusicXml.replace(
    "<duration>4</duration>",
    "<duration>3</duration>",
)
