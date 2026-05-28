package digital.dutton.essentials.scores

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

interface OmrEngine {
    suspend fun recognize(
        title: String,
        pages: List<ScorePageBitmap>,
        progress: ImportProgressCallback,
    ): OmrResult
}

class OnDeviceOmrEngine(
    private val context: Context,
) : OmrEngine {
    override suspend fun recognize(
        title: String,
        pages: List<ScorePageBitmap>,
        progress: ImportProgressCallback,
    ): OmrResult = withContext(Dispatchers.Default) {
        for (page in pages) {
            coroutineContext.ensureActive()
            progress(
                ImportProgress(
                    stage = ImportStage.Recognizing,
                    pageIndex = page.index,
                    pageCount = pages.size,
                    message = "Recognizing page ${page.index + 1} of ${pages.size}",
                ),
            )
        }

        val missingAssets = RequiredModelAssets.filterNot(::assetExists)
        if (missingAssets.isNotEmpty()) {
            return@withContext OmrResult(
                musicXml = null,
                pageCount = pages.size,
                warnings = listOf(
                    ScoreWarning(
                        pageIndex = null,
                        code = "omr_assets_missing",
                        message = "OMR model assets are not bundled in this build: ${missingAssets.joinToString()}.",
                    ),
                ),
            )
        }

        NativeScoreOmr(context).recognize(
            title = title,
            pages = pages,
        ) { pageIndex, pageCount, message ->
            progress(
                ImportProgress(
                    stage = ImportStage.Recognizing,
                    pageIndex = pageIndex,
                    pageCount = pageCount,
                    message = message,
                ),
            )
        }
    }

    private fun assetExists(path: String): Boolean {
        return runCatching {
            context.assets.open(path).use { true }
        }.getOrDefault(false)
    }

    private companion object {
        val RequiredModelAssets = listOf(
            "omr/segmentation.tflite",
            "omr/encoder.tflite",
            "omr/decoder.onnx",
        )
    }
}
