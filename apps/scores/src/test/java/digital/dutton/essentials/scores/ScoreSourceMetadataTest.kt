package digital.dutton.essentials.scores

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreSourceMetadataTest {
    @Test
    fun readsIndirectPdfTitle() {
        val file = tempPdf(
            """
            %PDF-1.3
            7 0 obj
            (Handel_Serse_Ombra_mai_fu_D.mus)
            endobj
            1 0 obj
            << /Title 7 0 R >>
            endobj
            """.trimIndent(),
        )

        assertEquals(
            "Handel Serse Ombra mai fu D",
            ScoreSourceMetadata.title(file, ScoreMimeTypes.Pdf),
        )
    }

    @Test
    fun ignoresNonPdfSources() {
        val file = tempPdf("(Prelude)")

        assertNull(ScoreSourceMetadata.title(file, "image/png"))
    }

    private fun tempPdf(contents: String): File {
        return File.createTempFile("score-metadata", ".pdf").apply {
            writeText(contents, Charsets.ISO_8859_1)
            deleteOnExit()
        }
    }
}
