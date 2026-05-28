package digital.dutton.essentials.scores

import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class ScoreImageTextRecognizer {
    suspend fun recognize(pages: List<ScorePageBitmap>): ScoreScannedText = withContext(Dispatchers.Default) {
        if (pages.isEmpty()) return@withContext ScoreScannedText()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val lines = mutableListOf<OcrLine>()
            val lyrics = mutableListOf<ScoreLyricText>()
            for (page in pages) {
                val result = recognizer.process(InputImage.fromBitmap(page.bitmap, 0)).await()
                result.textBlocks.forEach { block ->
                    block.lines.forEach { line ->
                        line.boundingBox?.let { box ->
                            lines += OcrLine(
                                pageIndex = page.index,
                                pageWidth = page.widthPx,
                                pageHeight = page.heightPx,
                                box = box,
                                text = line.text.cleanOcrText(),
                            )
                        }
                        line.elements.forEach { element ->
                            val text = element.text.cleanOcrText()
                            if (text.isBlank() || text.length > MaxLyricTokenLength || text.none(Char::isLetter)) {
                                return@forEach
                            }
                            val box = element.boundingBox ?: line.boundingBox ?: block.boundingBox ?: return@forEach
                            lyrics += ScoreLyricText(
                                pageIndex = page.index,
                                xPx = box.centerX().toFloat(),
                                yPx = box.centerY().toFloat(),
                                text = text,
                                leftPx = box.left.toFloat(),
                                rightPx = box.right.toFloat(),
                            )
                        }
                    }
                }
            }
            ScoreScannedText(
                title = inferTitle(lines),
                lyrics = lyrics,
            )
        } finally {
            recognizer.close()
        }
    }

    private fun inferTitle(lines: List<OcrLine>): String? {
        return lines
            .asSequence()
            .filter { it.pageIndex == 0 }
            .filter { it.text.count(Char::isLetter) >= 4 }
            .filter { it.box.centerY() < it.pageHeight * TitleSearchHeightFraction }
            .filter { it.box.width() > it.pageWidth * MinTitleWidthFraction || it.box.height() > it.pageHeight * MinTitleHeightFraction }
            .maxByOrNull { line ->
                val centered = 1f - (abs(line.box.centerX() - line.pageWidth / 2f) / (line.pageWidth / 2f)).coerceIn(0f, 1f)
                line.box.height() * 5f + line.box.width() * 0.1f + centered * 100f - line.box.top * 0.05f
            }
            ?.text
            ?.cleanTitle()
    }

    private fun String.cleanOcrText(): String {
        return replace(Regex("""\s+"""), " ")
            .trim()
            .trim('|')
            .trim()
    }

    private fun String.cleanTitle(): String? {
        val cleaned = replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '_')
        if (cleaned.length < 4) return null
        return cleaned
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            continuation.cancel()
        }
    }

    private data class OcrLine(
        val pageIndex: Int,
        val pageWidth: Int,
        val pageHeight: Int,
        val box: Rect,
        val text: String,
    )

    private companion object {
        const val TitleSearchHeightFraction = 0.32f
        const val MinTitleWidthFraction = 0.18f
        const val MinTitleHeightFraction = 0.02f
        const val MaxLyricTokenLength = 48
    }
}
