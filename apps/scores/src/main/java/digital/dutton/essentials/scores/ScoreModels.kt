package digital.dutton.essentials.scores

import android.graphics.Bitmap
import android.net.Uri

object ScoreMimeTypes {
    const val Pdf = "application/pdf"
    const val MusicXml = "application/vnd.recordare.musicxml+xml"
    const val CompressedMusicXml = "application/vnd.recordare.musicxml"

    fun normalize(
        mimeType: String?,
        displayName: String,
    ): String {
        val cleaned = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (cleaned.isNotBlank() && cleaned != "application/octet-stream") return cleaned

        return when (displayName.substringAfterLast('.', "").lowercase()) {
            "pdf" -> Pdf
            "mxl" -> CompressedMusicXml
            "musicxml", "xml" -> MusicXml
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> cleaned
        }
    }

    fun isPdf(mimeType: String): Boolean = mimeType == Pdf

    fun isImage(mimeType: String): Boolean = mimeType.startsWith("image/")

    fun isMusicXml(mimeType: String): Boolean {
        return mimeType == MusicXml || mimeType == "application/xml" || mimeType == "text/xml"
    }

    fun isCompressedMusicXml(mimeType: String): Boolean = mimeType == CompressedMusicXml

    fun isSupported(mimeType: String): Boolean {
        return isPdf(mimeType) || isImage(mimeType) || isMusicXml(mimeType) || isCompressedMusicXml(mimeType)
    }
}

enum class ScoreImportState {
    Queued,
    Processing,
    Complete,
    Failed,
    Cancelled,
}

enum class ImportStage {
    Queued,
    Copying,
    Rasterizing,
    Recognizing,
    Validating,
    Rendering,
    Complete,
    Failed,
    Cancelled,
}

data class ScoreWarning(
    val pageIndex: Int?,
    val code: String,
    val message: String,
)

data class ScoreRecord(
    val id: String,
    val title: String,
    val sourceMime: String,
    val sourcePath: String,
    val musicXmlPath: String?,
    val state: ScoreImportState,
    val pageCount: Int,
    val warnings: List<ScoreWarning>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

data class ScoreSource(
    val uri: Uri,
    val mimeType: String?,
    val displayName: String,
)

data class ScorePageBitmap(
    val index: Int,
    val widthPx: Int,
    val heightPx: Int,
    val bitmap: Bitmap,
)

data class ScorePageGeometry(
    val index: Int,
    val widthPx: Int,
    val heightPx: Int,
)

data class ScoreLyricText(
    val pageIndex: Int,
    val xPx: Float,
    val yPx: Float,
    val text: String,
    val leftPx: Float = xPx,
    val rightPx: Float = xPx,
)

data class ScoreScannedText(
    val title: String? = null,
    val lyrics: List<ScoreLyricText> = emptyList(),
)

data class ImportProgress(
    val stage: ImportStage,
    val pageIndex: Int? = null,
    val pageCount: Int? = null,
    val message: String,
)

typealias ImportProgressCallback = suspend (ImportProgress) -> Unit

data class OmrResult(
    val musicXml: ByteArray?,
    val pageCount: Int,
    val warnings: List<ScoreWarning>,
)

data class ImportResult(
    val record: ScoreRecord,
)

data class RenderedScorePage(
    val index: Int,
    val widthPx: Int,
    val heightPx: Int,
    val bitmap: Bitmap,
)
