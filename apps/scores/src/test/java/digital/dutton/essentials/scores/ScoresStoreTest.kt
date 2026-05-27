package digital.dutton.essentials.scores

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
