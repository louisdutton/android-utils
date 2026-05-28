package digital.dutton.essentials.scores

import java.io.File
import java.util.Locale

object ScoreSourceMetadata {
    fun title(
        file: File,
        mimeType: String,
    ): String? {
        if (!ScoreMimeTypes.isPdf(mimeType)) return null
        return runCatching {
            val text = file.readBytes().toString(Charsets.ISO_8859_1)
            pdfInfoString(text, "Title")?.cleanScoreTitle()
        }.getOrNull()
    }

    private fun pdfInfoString(
        pdf: String,
        key: String,
    ): String? {
        val direct = Regex("""/$key\s*(\(|<)""").findAll(pdf).lastOrNull()
        direct?.let { match ->
            return readPdfString(pdf, match.range.last)?.takeIf { it.isNotBlank() }
        }

        val indirect = Regex("""/$key\s+(\d+)\s+(\d+)\s+R""").findAll(pdf).lastOrNull() ?: return null
        val objectNumber = indirect.groupValues[1]
        val generation = indirect.groupValues[2]
        val objectMatch = Regex(
            """(?s)\b$objectNumber\s+$generation\s+obj\s*(.*?)\s*endobj""",
        ).find(pdf) ?: return null
        return readPdfString(objectMatch.groupValues[1], 0)?.takeIf { it.isNotBlank() }
    }

    private fun readPdfString(
        text: String,
        start: Int,
    ): String? {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index += 1
        if (index >= text.length) return null
        return when (text[index]) {
            '(' -> readLiteralPdfString(text, index)
            '<' -> readHexPdfString(text, index)
            else -> null
        }
    }

    private fun readLiteralPdfString(
        text: String,
        start: Int,
    ): String? {
        val out = StringBuilder()
        var depth = 1
        var index = start + 1
        while (index < text.length && depth > 0) {
            when (val char = text[index++]) {
                '\\' -> {
                    if (index >= text.length) return null
                    val escaped = text[index++]
                    out.append(
                        when (escaped) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'b' -> '\b'
                            'f' -> '\u000c'
                            '\n', '\r' -> ""
                            else -> escaped
                        },
                    )
                }
                '(' -> {
                    depth += 1
                    out.append(char)
                }
                ')' -> {
                    depth -= 1
                    if (depth > 0) out.append(char)
                }
                else -> out.append(char)
            }
        }
        return out.toString().decodeUtf16BomIfPresent()
    }

    private fun readHexPdfString(
        text: String,
        start: Int,
    ): String? {
        val end = text.indexOf('>', start + 1).takeIf { it > start } ?: return null
        val hex = text.substring(start + 1, end).filterNot { it.isWhitespace() }
        if (hex.isEmpty()) return ""
        val padded = if (hex.length % 2 == 0) hex else "${hex}0"
        val bytes = ByteArray(padded.length / 2)
        for (index in bytes.indices) {
            bytes[index] = padded.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return bytes.decodePdfStringBytes()
    }

    private fun ByteArray.decodePdfStringBytes(): String {
        return if (size >= 2 && this[0] == 0xFE.toByte() && this[1] == 0xFF.toByte()) {
            copyOfRange(2, size).toString(Charsets.UTF_16BE)
        } else {
            toString(Charsets.ISO_8859_1)
        }
    }

    private fun String.decodeUtf16BomIfPresent(): String {
        if (length < 2) return this
        return if (this[0].code == 0xFE && this[1].code == 0xFF) {
            val bytes = ByteArray(length) { index -> this[index].code.toByte() }
            bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
        } else {
            this
        }
    }

    private fun String.cleanScoreTitle(): String? {
        val stripped = trim()
            .replace(Regex("""\.(pdf|mxl|musicxml|xml|mus|mid|midi)$""", RegexOption.IGNORE_CASE), "")
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '_')
        if (stripped.length < 2) return null
        val lower = stripped.lowercase(Locale.US)
        if (lower == "untitled" || lower == "score") return null
        return stripped
    }
}
