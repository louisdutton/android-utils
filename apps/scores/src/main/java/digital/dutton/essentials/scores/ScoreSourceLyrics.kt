package digital.dutton.essentials.scores

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.InflaterInputStream
import kotlin.math.max

object ScoreSourceLyrics {
    fun extract(
        file: File,
        mimeType: String,
        pages: List<ScorePageGeometry>,
    ): List<ScoreLyricText> {
        if (!ScoreMimeTypes.isPdf(mimeType) || pages.isEmpty()) return emptyList()
        return runCatching {
            val pdf = file.readBytes().toString(Charsets.ISO_8859_1)
            val objects = parseObjects(pdf)
            val streams = objects.mapNotNull { obj -> obj.streamText()?.let { obj.number to it } }.toMap()
            orderedPages(objects).flatMapIndexed { pageIndex, pageObject ->
                val pageGeometry = pages.getOrNull(pageIndex) ?: return@flatMapIndexed emptyList()
                val mediaBox = pageObject.mediaBox() ?: MediaBox(0f, 0f, 612f, 792f)
                pageObject.contentRefs().flatMap { ref ->
                    extractText(
                        content = streams[ref].orEmpty(),
                        pageIndex = pageIndex,
                        mediaBox = mediaBox,
                        page = pageGeometry,
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseObjects(pdf: String): List<PdfObject> {
        return Regex("""(?s)(\d+)\s+(\d+)\s+obj\s*(.*?)\s*endobj""")
            .findAll(pdf)
            .mapNotNull { match ->
                val number = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                PdfObject(number = number, body = match.groupValues[3])
            }
            .toList()
    }

    private fun orderedPages(objects: List<PdfObject>): List<PdfObject> {
        val pages = objects
            .filter { Regex("""/Type\s*/Page\b""").containsMatchIn(it.body) }
            .associateBy { it.number }
        val kids = objects
            .firstOrNull { Regex("""/Type\s*/Pages\b""").containsMatchIn(it.body) }
            ?.body
            ?.let { body -> Regex("""(\d+)\s+\d+\s+R""").findAll(body.substringAfter("/Kids", "")).mapNotNull { it.groupValues[1].toIntOrNull() }.toList() }
            .orEmpty()
        return kids.mapNotNull { pages[it] }.ifEmpty { pages.values.sortedBy { it.number } }
    }

    private fun extractText(
        content: String,
        pageIndex: Int,
        mediaBox: MediaBox,
        page: ScorePageGeometry,
    ): List<ScoreLyricText> {
        if (content.isBlank()) return emptyList()
        return TextBlockRegex.findAll(content).mapNotNull { match ->
            val blockStart = match.range.first
            val block = match.groupValues[1]
            val transform = precedingTransform(content, blockStart)
            val textPosition = TextMatrixRegex.find(block) ?: return@mapNotNull null
            val textX = textPosition.groupValues[5].toFloatOrNull() ?: return@mapNotNull null
            val textY = textPosition.groupValues[6].toFloatOrNull() ?: return@mapNotNull null
            val userX = transform.a * textX + transform.c * textY + transform.e
            val userY = transform.b * textX + transform.d * textY + transform.f
            val text = block.visibleText().cleanLyricCandidate() ?: return@mapNotNull null
            ScoreLyricText(
                pageIndex = pageIndex,
                xPx = ((userX - mediaBox.left) / mediaBox.width * page.widthPx).coerceIn(0f, page.widthPx.toFloat()),
                yPx = ((mediaBox.top - userY) / mediaBox.height * page.heightPx).coerceIn(0f, page.heightPx.toFloat()),
                text = text,
            )
        }.toList()
    }

    private fun precedingTransform(
        content: String,
        blockStart: Int,
    ): PdfTransform {
        val prefix = content.substring(max(0, blockStart - 180), blockStart)
        val match = CmRegex.findAll(prefix).lastOrNull() ?: return PdfTransform.Identity
        return PdfTransform(
            a = match.groupValues[1].toFloatOrNull() ?: 1f,
            b = match.groupValues[2].toFloatOrNull() ?: 0f,
            c = match.groupValues[3].toFloatOrNull() ?: 0f,
            d = match.groupValues[4].toFloatOrNull() ?: 1f,
            e = match.groupValues[5].toFloatOrNull() ?: 0f,
            f = match.groupValues[6].toFloatOrNull() ?: 0f,
        )
    }

    private fun String.visibleText(): String {
        val out = StringBuilder()
        var currentFont = ""
        FontOrShowRegex.findAll(this).forEach { match ->
            val font = match.groupValues[1]
            if (font.isNotBlank()) {
                currentFont = font
                return@forEach
            }
            val literal = match.groupValues[2]
            if (literal.isNotBlank()) {
                out.append(decodeLiteral(literal).normalizePdfText(currentFont))
                return@forEach
            }
            val array = match.groupValues[3]
            if (array.isNotBlank()) {
                LiteralStringRegex.findAll(array).forEach { item ->
                    out.append(decodeLiteral(item.value).normalizePdfText(currentFont))
                }
            }
        }
        return out.toString()
    }

    private fun String.normalizePdfText(font: String): String {
        if (font.startsWith("F1")) return ""
        return when {
            font.startsWith("F2.1") && this == "!" -> "-"
            else -> this
        }
    }

    private fun String.cleanLyricCandidate(): String? {
        val cleaned = replace('\u0000', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (cleaned.none { it.isLetter() }) return null
        if (cleaned.length > 48) return null
        return cleaned
    }

    private fun decodeLiteral(literal: String): String {
        val body = literal.removePrefix("(").removeSuffix(")")
        val out = StringBuilder()
        var index = 0
        while (index < body.length) {
            val char = body[index++]
            if (char != '\\' || index >= body.length) {
                out.append(char.decodePdfDocChar())
                continue
            }
            out.append(
                when (val escaped = body[index++]) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'b' -> '\b'
                    'f' -> '\u000c'
                    '(', ')', '\\' -> escaped
                    else -> escaped.decodePdfDocChar()
                },
            )
        }
        return out.toString()
    }

    private fun Char.decodePdfDocChar(): Char {
        return when (code) {
            0x8F -> '\u00e8'
            0x9D -> '\u00f9'
            0xD5 -> '\u2019'
            else -> this
        }
    }

    private fun PdfObject.streamText(): String? {
        val start = body.indexOf("stream").takeIf { it >= 0 } ?: return null
        val end = body.indexOf("endstream", start).takeIf { it > start } ?: return null
        var stream = body.substring(start + "stream".length, end)
        if (stream.startsWith("\r\n")) stream = stream.drop(2) else if (stream.startsWith("\n") || stream.startsWith("\r")) stream = stream.drop(1)
        if (stream.endsWith("\r\n")) stream = stream.dropLast(2) else if (stream.endsWith("\n") || stream.endsWith("\r")) stream = stream.dropLast(1)
        val bytes = stream.toByteArray(Charsets.ISO_8859_1)
        val decoded = if ("/FlateDecode" in body) {
            InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } else {
            bytes
        }
        return decoded.toString(Charsets.ISO_8859_1)
    }

    private fun PdfObject.contentRefs(): List<Int> {
        val match = ContentsRegex.find(body) ?: return emptyList()
        val inlineRef = match.groupValues[2].toIntOrNull()
        if (inlineRef != null) return listOf(inlineRef)
        return Regex("""(\d+)\s+\d+\s+R""")
            .findAll(match.groupValues[1])
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toList()
    }

    private fun PdfObject.mediaBox(): MediaBox? {
        val values = MediaBoxRegex.find(body)?.groupValues?.drop(1)?.mapNotNull { it.toFloatOrNull() } ?: return null
        if (values.size != 4) return null
        return MediaBox(
            left = values[0],
            bottom = values[1],
            right = values[2],
            top = values[3],
        )
    }

    private data class PdfObject(
        val number: Int,
        val body: String,
    )

    private data class MediaBox(
        val left: Float,
        val bottom: Float,
        val right: Float,
        val top: Float,
    ) {
        val width: Float = (right - left).takeIf { it > 0f } ?: 612f
        val height: Float = (top - bottom).takeIf { it > 0f } ?: 792f
    }

    private data class PdfTransform(
        val a: Float,
        val b: Float,
        val c: Float,
        val d: Float,
        val e: Float,
        val f: Float,
    ) {
        companion object {
            val Identity = PdfTransform(1f, 0f, 0f, 1f, 0f, 0f)
        }
    }

    private val Number = """[-+]?(?:\d+(?:\.\d+)?|\.\d+)"""
    private val TextBlockRegex = Regex("""(?s)BT\s+(.*?)\s+ET""")
    private val CmRegex = Regex("""($Number)\s+($Number)\s+($Number)\s+($Number)\s+($Number)\s+($Number)\s+cm""")
    private val TextMatrixRegex = Regex("""($Number)\s+($Number)\s+($Number)\s+($Number)\s+($Number)\s+($Number)\s+Tm""")
    private val LiteralStringRegex = Regex("""\((?:\\.|[^\\)])*\)""")
    private val FontOrShowRegex = Regex("""(?s)/([^\s]+)\s+$Number\s+Tf|(\((?:\\.|[^\\)])*\))\s*Tj|\[(.*?)\]\s*TJ""")
    private val ContentsRegex = Regex("""(?s)/Contents\s+(?:\[(.*?)\]|(\d+)\s+\d+\s+R)""")
    private val MediaBoxRegex = Regex("""/MediaBox\s*\[\s*($Number)\s+($Number)\s+($Number)\s+($Number)\s*]""")
}
