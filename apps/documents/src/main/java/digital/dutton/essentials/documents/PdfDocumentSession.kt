package digital.dutton.essentials.documents

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PdfPageSpec(
    val index: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

data class RenderedPdfPage(
    val index: Int,
    val widthPx: Int,
    val heightPx: Int,
    val bitmap: Bitmap,
)

data class PdfSearchHit(
    val pageIndex: Int,
    val bounds: List<RectF>,
    val textStartIndex: Int,
)

enum class PdfFormKind {
    None,
    AcroForm,
    XfaForeground,
    XfaFull,
    Unsupported,
    Unknown,
}

class PdfDocumentSession private constructor(
    val uri: Uri,
    val displayName: String,
    private val cacheFile: File,
    private val renderer: PdfRenderer,
    val pages: List<PdfPageSpec>,
    val formKind: PdfFormKind,
) : Closeable {
    private val renderLock = Mutex()
    private val renderedPages = object : LruCache<String, RenderedPdfPage>(bitmapCacheSizeKb()) {
        override fun sizeOf(key: String, value: RenderedPdfPage): Int {
            return (value.bitmap.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    val pageCount: Int = pages.size

    suspend fun renderPage(
        pageIndex: Int,
        targetWidthPx: Int,
    ): RenderedPdfPage = withContext(Dispatchers.IO) {
        val widthPx = targetWidthPx.coerceIn(320, 3200)
        val cacheKey = "$pageIndex:$widthPx"
        renderedPages.get(cacheKey)?.let { return@withContext it }

        renderLock.withLock {
            renderedPages.get(cacheKey)?.let { return@withLock it }

            val page = renderer.openPage(pageIndex)
            try {
                val heightPx = (widthPx * (page.height.toFloat() / page.width.toFloat()))
                    .roundToInt()
                    .coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.prepareToDraw()

                RenderedPdfPage(
                    index = pageIndex,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    bitmap = bitmap,
                ).also { renderedPages.put(cacheKey, it) }
            } finally {
                page.close()
            }
        }
    }

    @SuppressLint("NewApi")
    suspend fun search(query: String): List<PdfSearchHit> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM || query.isBlank()) {
            return@withContext emptyList()
        }

        renderLock.withLock {
            pages.flatMap { pageSpec ->
                val page = renderer.openPage(pageSpec.index)
                try {
                    page.searchText(query).map { match ->
                        PdfSearchHit(
                            pageIndex = pageSpec.index,
                            bounds = match.bounds,
                            textStartIndex = match.textStartIndex,
                        )
                    }
                } finally {
                    page.close()
                }
            }
        }
    }

    override fun close() {
        renderedPages.evictAll()
        runCatching { renderer.close() }
        runCatching { cacheFile.delete() }
    }

    companion object {
        suspend fun open(
            context: Context,
            uri: Uri,
        ): PdfDocumentSession = withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val displayName = appContext.displayNameFor(uri)
            val cacheDir = File(appContext.cacheDir, "documents").apply { mkdirs() }
            val cacheFile = File.createTempFile("document-", ".pdf", cacheDir)

            runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: error("Unable to open document stream.")

                val fileDescriptor = ParcelFileDescriptor.open(
                    cacheFile,
                    ParcelFileDescriptor.MODE_READ_ONLY,
                )
                val renderer = try {
                    PdfRenderer(fileDescriptor)
                } catch (error: Throwable) {
                    fileDescriptor.close()
                    throw error
                }
                val pages = renderer.readPageSpecs()

                PdfDocumentSession(
                    uri = uri,
                    displayName = displayName,
                    cacheFile = cacheFile,
                    renderer = renderer,
                    pages = pages,
                    formKind = renderer.readFormKind(),
                )
            }.getOrElse { error ->
                cacheFile.delete()
                throw error
            }
        }

        private fun bitmapCacheSizeKb(): Int {
            val runtimeKb = Runtime.getRuntime().maxMemory() / 1024
            return (runtimeKb / 8).coerceIn(24 * 1024, 96 * 1024).toInt()
        }
    }
}

private fun PdfRenderer.readPageSpecs(): List<PdfPageSpec> {
    return (0 until pageCount).map { index ->
        val page = openPage(index)
        try {
            PdfPageSpec(
                index = index,
                sourceWidth = page.width.coerceAtLeast(1),
                sourceHeight = page.height.coerceAtLeast(1),
            )
        } finally {
            page.close()
        }
    }
}

@SuppressLint("NewApi")
private fun PdfRenderer.readFormKind(): PdfFormKind {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        return PdfFormKind.Unsupported
    }

    return when (getPdfFormType()) {
        PdfRenderer.PDF_FORM_TYPE_NONE -> PdfFormKind.None
        PdfRenderer.PDF_FORM_TYPE_ACRO_FORM -> PdfFormKind.AcroForm
        PdfRenderer.PDF_FORM_TYPE_XFA_FOREGROUND -> PdfFormKind.XfaForeground
        PdfRenderer.PDF_FORM_TYPE_XFA_FULL -> PdfFormKind.XfaFull
        else -> PdfFormKind.Unknown
    }
}

private fun Context.displayNameFor(uri: Uri): String {
    val queriedName = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor: Cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        } else {
            null
        }
    }

    return queriedName
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "Document.pdf"
}
