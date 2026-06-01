package digital.dutton.essentials.scores

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreLyricLineSelectorTest {
    @Test
    fun keepsRightEdgeContinuationTokenOnNearbyBaseline() {
        val selected = ScoreLyricLineSelector.selectForStaff(
            text = listOf(
                lyric("del", x = 240f, y = 170f),
                lyric("mio", x = 360f, y = 171f),
                lyric("pla-ta-no", x = 500f, y = 169f),
                lyric("a-ma-to,", x = 700f, y = 170f),
                lyric("per", x = 870f, y = 205f),
            ),
            sourceLeft = 100f,
            sourceTop = 100f,
            sourceRight = 900f,
            sourceBottom = 200f,
            nextStaffTop = 350f,
        )

        assertEquals(listOf("del", "mio", "pla-ta-no", "a-ma-to,", "per"), selected.map { it.text })
    }

    @Test
    fun skipsSeparateVerseLineThatDoesNotExtendPrimaryText() {
        val selected = ScoreLyricLineSelector.selectForStaff(
            text = listOf(
                lyric("del", x = 240f, y = 170f),
                lyric("mio", x = 360f, y = 171f),
                lyric("second", x = 245f, y = 226f),
                lyric("verse", x = 365f, y = 227f),
            ),
            sourceLeft = 100f,
            sourceTop = 100f,
            sourceRight = 900f,
            sourceBottom = 200f,
            nextStaffTop = 350f,
        )

        assertEquals(listOf("del", "mio"), selected.map { it.text })
    }

    private fun lyric(
        text: String,
        x: Float,
        y: Float,
    ): ScoreLyricText {
        return ScoreLyricText(
            pageIndex = 0,
            xPx = x,
            yPx = y,
            text = text,
            leftPx = x - text.length * 3f,
            rightPx = x + text.length * 3f,
        )
    }
}
