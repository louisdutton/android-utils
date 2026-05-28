package digital.dutton.essentials.scores

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class ScorePageRasterizer(
    private val context: Context,
) {
    suspend fun rasterize(
        sourceFile: File,
        mimeType: String,
    ): List<ScorePageBitmap> = withContext(Dispatchers.IO) {
        when {
            ScoreMimeTypes.isPdf(mimeType) -> rasterizePdf(sourceFile)
            ScoreMimeTypes.isImage(mimeType) -> listOf(rasterizeImage(sourceFile))
            else -> error("Unsupported score source type: $mimeType")
        }
    }

    private fun rasterizePdf(sourceFile: File): List<ScorePageBitmap> {
        val descriptor = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)
        return try {
            buildList {
                for (index in 0 until renderer.pageCount) {
                    val page = renderer.openPage(index)
                    try {
                        val size = targetOmrSize(page.width, page.height)
                        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        add(
                            ScorePageBitmap(
                                index = index,
                                widthPx = bitmap.width,
                                heightPx = bitmap.height,
                                bitmap = bitmap,
                            ),
                        )
                    } finally {
                        page.close()
                    }
                }
            }
        } finally {
            renderer.close()
            descriptor.close()
        }
    }

    private fun rasterizeImage(sourceFile: File): ScorePageBitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unable to decode image score." }

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeFile(sourceFile.absolutePath, options)
            ?: error("Unable to decode image score.")
        val oriented = decoded.applyExifOrientation(sourceFile)
        if (oriented !== decoded) decoded.recycle()

        return ScorePageBitmap(
            index = 0,
            widthPx = oriented.width,
            heightPx = oriented.height,
            bitmap = oriented.scaledForOmr(),
        )
    }

    private fun Bitmap.applyExifOrientation(sourceFile: File): Bitmap {
        val orientation = runCatching {
            ExifInterface(sourceFile.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return this
        }

        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun sampleSizeFor(
        width: Int,
        height: Int,
    ): Int {
        var sampleSize = 1
        while ((width / sampleSize) > MaxDecodeWidthPx || (height / sampleSize) > MaxDecodeHeightPx) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun Bitmap.scaledForOmr(): Bitmap {
        val size = targetOmrSize(width, height)
        if (width == size.width && height == size.height) return this
        val scaled = Bitmap.createScaledBitmap(this, size.width, size.height, true)
        recycle()
        return scaled
    }

    private fun targetOmrSize(
        sourceWidth: Int,
        sourceHeight: Int,
    ): RasterSize {
        val safeWidth = sourceWidth.coerceAtLeast(1)
        val safeHeight = sourceHeight.coerceAtLeast(1)
        val targetHeightAtWidth = (TargetRasterWidthPx * safeHeight.toFloat() / safeWidth.toFloat()).roundToInt()
        if (targetHeightAtWidth <= MaxRasterHeightPx) {
            return RasterSize(TargetRasterWidthPx, targetHeightAtWidth.coerceAtLeast(1))
        }

        val targetWidthAtHeight = (MaxRasterHeightPx * safeWidth.toFloat() / safeHeight.toFloat()).roundToInt()
        return RasterSize(targetWidthAtHeight.coerceAtLeast(1), MaxRasterHeightPx)
    }

    private companion object {
        const val TargetRasterWidthPx = 1920
        const val MaxRasterHeightPx = 3200
        const val MaxDecodeWidthPx = 3840
        const val MaxDecodeHeightPx = 5400
    }
}

private data class RasterSize(
    val width: Int,
    val height: Int,
)
