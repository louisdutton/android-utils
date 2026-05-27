package digital.dutton.essentials.scores

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

interface ScoreRenderer {
    suspend fun render(
        musicXml: ByteArray,
        targetWidthPx: Int,
    ): List<RenderedScorePage>
}

class VerovioScoreRenderer : ScoreRenderer {
    override suspend fun render(
        musicXml: ByteArray,
        targetWidthPx: Int,
    ): List<RenderedScorePage> = withContext(Dispatchers.Default) {
        val title = MusicXmlFiles.title(musicXml) ?: "Score"
        val pageCount = MusicXmlFiles.estimatedPageCount(musicXml)
        VerovioBridge.renderSvgPages(
            title = title,
            pageCount = pageCount,
            targetWidthPx = targetWidthPx.coerceIn(320, 2400),
        ).mapIndexed { index, svg ->
            renderSvgPage(index, svg, targetWidthPx.coerceIn(320, 2400))
        }
    }

    private fun renderSvgPage(
        index: Int,
        svgText: String,
        targetWidthPx: Int,
    ): RenderedScorePage {
        val svg = SVG.getFromString(svgText)
        val documentWidth = svg.documentWidth.takeIf { it > 0f } ?: targetWidthPx.toFloat()
        val documentHeight = svg.documentHeight.takeIf { it > 0f } ?: (targetWidthPx * 4 / 3f)
        val scale = targetWidthPx / documentWidth
        val targetHeightPx = (documentHeight * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(targetWidthPx, targetHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        svg.renderToCanvas(canvas)
        bitmap.prepareToDraw()

        return RenderedScorePage(
            index = index,
            widthPx = bitmap.width,
            heightPx = bitmap.height,
            bitmap = bitmap,
        )
    }
}

object VerovioBridge {
    private val loaded = runCatching {
        System.loadLibrary("scores_verovio_bridge")
    }.isSuccess

    private external fun renderPlaceholderSvgPages(
        title: String,
        pageCount: Int,
        targetWidthPx: Int,
    ): Array<String>

    fun renderSvgPages(
        title: String,
        pageCount: Int,
        targetWidthPx: Int,
    ): List<String> {
        return if (loaded) {
            runCatching {
                renderPlaceholderSvgPages(title, pageCount, targetWidthPx).toList()
            }.getOrElse {
                fallbackSvgPages(title, pageCount, targetWidthPx)
            }
        } else {
            fallbackSvgPages(title, pageCount, targetWidthPx)
        }
    }

    private fun fallbackSvgPages(
        title: String,
        pageCount: Int,
        targetWidthPx: Int,
    ): List<String> {
        val safePageCount = pageCount.coerceAtLeast(1)
        val safeWidth = targetWidthPx.coerceAtLeast(320)
        val height = safeWidth * 4 / 3
        return (0 until safePageCount).map { pageIndex ->
            """
            <svg xmlns="http://www.w3.org/2000/svg" width="$safeWidth" height="$height" viewBox="0 0 $safeWidth $height">
              <rect width="100%" height="100%" fill="#ffffff"/>
              <text x="${safeWidth / 2}" y="64" text-anchor="middle" font-size="28" font-family="sans-serif" fill="#202124">${title.escapeXml()}</text>
              <line x1="${safeWidth / 8}" y1="180" x2="${safeWidth - safeWidth / 8}" y2="180" stroke="#2b2b2b" stroke-width="2"/>
              <line x1="${safeWidth / 8}" y1="198" x2="${safeWidth - safeWidth / 8}" y2="198" stroke="#2b2b2b" stroke-width="2"/>
              <line x1="${safeWidth / 8}" y1="216" x2="${safeWidth - safeWidth / 8}" y2="216" stroke="#2b2b2b" stroke-width="2"/>
              <line x1="${safeWidth / 8}" y1="234" x2="${safeWidth - safeWidth / 8}" y2="234" stroke="#2b2b2b" stroke-width="2"/>
              <line x1="${safeWidth / 8}" y1="252" x2="${safeWidth - safeWidth / 8}" y2="252" stroke="#2b2b2b" stroke-width="2"/>
              <text x="${safeWidth / 2}" y="${height - 56}" text-anchor="middle" font-size="20" font-family="sans-serif" fill="#6b6b6b">Verovio bridge unavailable · page ${pageIndex + 1} of $safePageCount</text>
            </svg>
            """.trimIndent()
        }
    }
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
