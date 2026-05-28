package digital.dutton.essentials.scores

import android.content.Context
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class ScoreImporter(
    private val context: Context,
    private val store: ScoresStore = ScoresStore(context),
    private val rasterizer: ScorePageRasterizer = ScorePageRasterizer(context),
    private val omrEngine: OmrEngine = OnDeviceOmrEngine(context),
) {
    suspend fun `import`(
        source: ScoreSource,
        progress: ImportProgressCallback,
    ): ImportResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val sourceMime = ScoreMimeTypes.normalize(source.mimeType, source.displayName)
        require(ScoreMimeTypes.isSupported(sourceMime)) { "Unsupported score source type: $sourceMime" }

        var record = store.createQueued(
            title = source.displayName.scoreTitleFromFileName(),
            sourceMime = sourceMime,
        )

        try {
            progress(ImportProgress(stage = ImportStage.Copying, message = "Copying source"))
            appContext.contentResolver.openInputStream(source.uri)?.use { input ->
                store.sourceFile(record).outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to open score source.")

            val sourceTitle = ScoreSourceMetadata.title(
                file = store.sourceFile(record),
                mimeType = sourceMime,
            ) ?: source.displayName.scoreTitleFromFileName()
            record = store.save(
                record.copy(
                    title = sourceTitle,
                    state = ScoreImportState.Processing,
                ),
            )
            coroutineContext.ensureActive()

            if (ScoreMimeTypes.isMusicXml(sourceMime) || ScoreMimeTypes.isCompressedMusicXml(sourceMime)) {
                progress(ImportProgress(stage = ImportStage.Validating, message = "Validating MusicXML"))
                val normalized = MusicXmlFiles.normalize(
                    bytes = store.sourceFile(record).readBytes(),
                    mimeType = sourceMime,
                    displayName = source.displayName,
                )
                val musicXmlFile = store.musicXmlFile(record)
                musicXmlFile.writeBytes(normalized.bytes)
                val title = MusicXmlFiles.title(normalized.bytes)
                    ?: source.displayName.scoreTitleFromFileName()
                record = store.save(
                    record.copy(
                        title = title,
                        musicXmlPath = musicXmlFile.absolutePath,
                        state = ScoreImportState.Complete,
                        pageCount = MusicXmlFiles.estimatedPageCount(normalized.bytes),
                        warnings = normalized.warnings,
                    ),
                )
                progress(ImportProgress(stage = ImportStage.Complete, message = "Import complete"))
                return@withContext ImportResult(record)
            }

            progress(ImportProgress(stage = ImportStage.Rasterizing, message = "Preparing pages"))
            val pages = rasterizer.rasterize(store.sourceFile(record), sourceMime)
            try {
                val omrResult = omrEngine.recognize(
                    title = sourceTitle,
                    pages = pages,
                    progress = progress,
                )
                val musicXml = omrResult.musicXml
                if (musicXml == null) {
                    record = store.save(
                        record.copy(
                            state = ScoreImportState.Failed,
                            pageCount = omrResult.pageCount,
                            warnings = omrResult.warnings,
                        ),
                    )
                    val message = if (omrResult.warnings.any { it.code == "omr_low_confidence" }) {
                        "OMR result was too uncertain"
                    } else {
                        "OMR unavailable"
                    }
                    progress(ImportProgress(stage = ImportStage.Failed, message = message))
                    return@withContext ImportResult(record)
                }

                progress(ImportProgress(stage = ImportStage.Validating, message = "Validating MusicXML"))
                val warnings = omrResult.warnings + MusicXmlFiles.validate(musicXml)
                val musicXmlFile = store.musicXmlFile(record)
                musicXmlFile.writeBytes(musicXml)
                val title = MusicXmlFiles.title(musicXml) ?: sourceTitle
                record = store.save(
                    record.copy(
                        title = title,
                        musicXmlPath = musicXmlFile.absolutePath,
                        state = ScoreImportState.Complete,
                        pageCount = omrResult.pageCount,
                        warnings = warnings,
                    ),
                )
                progress(ImportProgress(stage = ImportStage.Complete, message = "Import complete"))
                ImportResult(record)
            } finally {
                pages.forEach { page -> page.bitmap.recycle() }
            }
        } catch (error: CancellationException) {
            store.save(
                record.copy(
                    state = ScoreImportState.Cancelled,
                    warnings = record.warnings + ScoreWarning(
                        pageIndex = null,
                        code = "import_cancelled",
                        message = "Import was cancelled.",
                    ),
                ),
            )
            progress(ImportProgress(stage = ImportStage.Cancelled, message = "Import cancelled"))
            throw error
        } catch (error: Throwable) {
            record = store.save(
                record.copy(
                    state = ScoreImportState.Failed,
                    warnings = record.warnings + ScoreWarning(
                        pageIndex = null,
                        code = "import_failed",
                        message = error.message ?: "Import failed.",
                    ),
                ),
            )
            progress(ImportProgress(stage = ImportStage.Failed, message = "Import failed"))
            ImportResult(record)
        }
    }
}
