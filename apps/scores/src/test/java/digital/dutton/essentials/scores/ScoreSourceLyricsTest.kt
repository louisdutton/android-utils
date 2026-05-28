package digital.dutton.essentials.scores

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreSourceLyricsTest {
    @Test
    fun extractsPdfTextLayerLyrics() {
        val file = tempPdf(
            """
            %PDF-1.3
            3 0 obj
            << /Type /Pages /Count 1 /Kids [ 2 0 R ] >>
            endobj
            2 0 obj
            << /Type /Page /Parent 3 0 R /Contents 4 0 R /MediaBox [0 0 612 792] >>
            endobj
            4 0 obj
            << /Length 260 >>
            stream
            q 0.06 0 0 0.06 0 0 cm BT 150 0 0 150 2368.604 8561.5 Tm /F2.0 1 Tf [ (F) 0.2 (r) 17.7 (on) ] TJ ET Q
            q 0.06 0 0 0.06 0 0 cm BT 150 0 0 150 2539.062 8561.5 Tm /F2.0 1 Tf [ (d) -0.2 (a) ] TJ /F2.1 1 Tf (!) Tj /F2.0 1 Tf [ (i) 0.2 (l) ] TJ ET Q
            endstream
            endobj
            """.trimIndent(),
        )

        val lyrics = ScoreSourceLyrics.extract(
            file = file,
            mimeType = ScoreMimeTypes.Pdf,
            pages = listOf(ScorePageGeometry(index = 0, widthPx = 1920, heightPx = 2485)),
        )

        assertEquals(listOf("Fron", "da-il"), lyrics.map { it.text })
        assertTrue(lyrics.all { it.pageIndex == 0 })
        assertTrue(lyrics.all { it.xPx > 0f && it.yPx > 0f })
    }

    @Test
    fun decodesPdfDocEncodingInLyrics() {
        val file = tempPdf(
            """
            %PDF-1.3
            3 0 obj
            << /Type /Pages /Count 1 /Kids [ 2 0 R ] >>
            endobj
            2 0 obj
            << /Type /Page /Parent 3 0 R /Contents 4 0 R /MediaBox [0 0 612 792] >>
            endobj
            4 0 obj
            << /Length 180 >>
            stream
            q 0.06 0 0 0.06 0 0 cm BT 150 0 0 150 1000 5000 Tm /F2.0 1 Tf (n${'\u008f'}) Tj ET Q
            q 0.06 0 0 0.06 0 0 cm BT 150 0 0 150 1300 5000 Tm /F2.0 1 Tf (v${'\u00d5'}ol) Tj ET Q
            q 0.06 0 0 0.06 0 0 cm BT 150 0 0 150 1600 5000 Tm /F2.0 1 Tf (pi${'\u009d'},) Tj ET Q
            endstream
            endobj
            """.trimIndent(),
        )

        val lyrics = ScoreSourceLyrics.extract(
            file = file,
            mimeType = ScoreMimeTypes.Pdf,
            pages = listOf(ScorePageGeometry(index = 0, widthPx = 1920, heightPx = 2485)),
        )

        assertEquals(listOf("n\u00e8", "v\u2019ol", "pi\u00f9,"), lyrics.map { it.text })
    }

    private fun tempPdf(contents: String): File {
        return File.createTempFile("score-lyrics", ".pdf").apply {
            writeText(contents, Charsets.ISO_8859_1)
            deleteOnExit()
        }
    }
}
