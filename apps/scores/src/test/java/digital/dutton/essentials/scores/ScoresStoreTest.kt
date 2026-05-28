package digital.dutton.essentials.scores

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoresStoreTest {
    @Test
    fun persistsRecordsAndWarningsAcrossStoreInstances() {
        val directory = Files.createTempDirectory("scores-store-test").toFile()
        try {
            val store = ScoresStore.inDirectory(directory)
            val queued = store.createQueued(
                title = "Prelude",
                sourceMime = ScoreMimeTypes.MusicXml,
            )
            val musicXmlFile = store.musicXmlFile(queued)
            musicXmlFile.writeText("<score-partwise/>")
            store.save(
                queued.copy(
                    state = ScoreImportState.Complete,
                    musicXmlPath = musicXmlFile.absolutePath,
                    pageCount = 1,
                    warnings = listOf(
                        ScoreWarning(
                            pageIndex = null,
                            code = "test_warning",
                            message = "Check this measure.",
                        ),
                    ),
                ),
            )

            val restored = ScoresStore.inDirectory(directory).list().single()

            assertEquals("Prelude", restored.title)
            assertEquals(ScoreImportState.Complete, restored.state)
            assertEquals(1, restored.pageCount)
            assertEquals("test_warning", restored.warnings.single().code)
            assertNotNull(restored.musicXmlPath)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun revalidationRemovesStaleMusicXmlParserWarnings() {
        val directory = Files.createTempDirectory("scores-store-revalidate-test").toFile()
        try {
            val store = ScoresStore.inDirectory(directory)
            val queued = store.createQueued(
                title = "Prelude",
                sourceMime = ScoreMimeTypes.MusicXml,
            )
            val musicXmlFile = store.musicXmlFile(queued)
            musicXmlFile.writeText(ValidStoreMusicXml)
            store.save(
                queued.copy(
                    state = ScoreImportState.Complete,
                    musicXmlPath = musicXmlFile.absolutePath,
                    pageCount = 1,
                    warnings = listOf(
                        ScoreWarning(
                            pageIndex = null,
                            code = "native_omr_review_required",
                            message = "Review required.",
                        ),
                        ScoreWarning(
                            pageIndex = null,
                            code = "musicxml_parse_failed",
                            message = "This parser does not support specification \"Unknown\" version \"0.0\"",
                        ),
                    ),
                ),
            )

            val restored = store.revalidateCompletedRecords().single()

            assertTrue(restored.warnings.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun revalidationFailsImplausibleImageOmrRecord() {
        val directory = Files.createTempDirectory("scores-store-quality-test").toFile()
        try {
            val store = ScoresStore.inDirectory(directory)
            val queued = store.createQueued(
                title = "Bad OMR",
                sourceMime = "image/png",
            )
            store.sourceFile(queued).writeBytes(byteArrayOf(0))
            val musicXmlFile = store.musicXmlFile(queued)
            musicXmlFile.writeText(musicXmlWithMeasures(40))
            store.save(
                queued.copy(
                    state = ScoreImportState.Complete,
                    musicXmlPath = musicXmlFile.absolutePath,
                    pageCount = 4,
                    warnings = listOf(
                        ScoreWarning(
                            pageIndex = null,
                            code = "native_omr_review_required",
                            message = "Review required.",
                        ),
                    ),
                ),
            )

            val restored = store.revalidateCompletedRecords().single()

            assertEquals(ScoreImportState.Failed, restored.state)
            assertEquals(1, restored.pageCount)
            assertEquals(listOf("omr_low_confidence"), restored.warnings.map { it.code })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun revalidationFailsInterruptedImports() {
        val directory = Files.createTempDirectory("scores-store-interrupted-test").toFile()
        try {
            val store = ScoresStore.inDirectory(directory)
            val queued = store.createQueued(
                title = "Interrupted",
                sourceMime = ScoreMimeTypes.Pdf,
            )
            store.save(queued.copy(state = ScoreImportState.Processing))

            val restored = store.revalidateCompletedRecords().single()

            assertEquals(ScoreImportState.Failed, restored.state)
            assertEquals(listOf("import_interrupted"), restored.warnings.map { it.code })
        } finally {
            directory.deleteRecursively()
        }
    }
}

private val ValidStoreMusicXml = """
    <?xml version="1.0" encoding="UTF-8"?>
    <score-partwise version="4.0">
      <work><work-title>Prelude</work-title></work>
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
          <note><rest/><duration>4</duration><voice>1</voice><type>whole</type></note>
        </measure>
      </part>
    </score-partwise>
""".trimIndent()

private fun musicXmlWithMeasures(measures: Int): String {
    val body = buildString {
        repeat(measures) { index ->
            appendLine("""    <measure number="${index + 1}">""")
            if (index == 0) {
                appendLine("      <attributes>")
                appendLine("        <divisions>1</divisions>")
                appendLine("        <key><fifths>0</fifths></key>")
                appendLine("        <time><beats>4</beats><beat-type>4</beat-type></time>")
                appendLine("        <clef><sign>G</sign><line>2</line></clef>")
                appendLine("      </attributes>")
            }
            appendLine("      <note><rest measure=\"yes\"/><duration>4</duration><voice>1</voice><type>whole</type></note>")
            appendLine("    </measure>")
        }
    }
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <score-partwise version="4.0">
          <work><work-title>Bad OMR</work-title></work>
          <part-list>
            <score-part id="P1"><part-name>Piano</part-name></score-part>
          </part-list>
          <part id="P1">
        $body
          </part>
        </score-partwise>
    """.trimIndent().trimStart()
}
