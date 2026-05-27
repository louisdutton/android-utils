package digital.dutton.essentials.scores

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

interface OmrEngine {
    suspend fun recognize(
        pages: List<ScorePageBitmap>,
        progress: ImportProgressCallback,
    ): OmrResult
}

class OnDeviceOmrEngine(
    private val context: Context,
) : OmrEngine {
    override suspend fun recognize(
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

        OmrResult(
            musicXml = null,
            pageCount = pages.size,
            warnings = listOf(
                ScoreWarning(
                    pageIndex = null,
                    code = "omr_runner_unavailable",
                    message = "HOMR model assets are present, but the native HOMR inference runner is not wired yet.",
                ),
            ),
        )
    }

    private fun assetExists(path: String): Boolean {
        return runCatching {
            context.assets.open(path).use { true }
        }.getOrDefault(false)
    }

    private companion object {
        val RequiredModelAssets = listOf(
            "omr/segmentation.onnx",
            "omr/encoder.onnx",
            "omr/decoder.onnx",
        )
    }
}
