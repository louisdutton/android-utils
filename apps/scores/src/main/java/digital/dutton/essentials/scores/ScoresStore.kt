package digital.dutton.essentials.scores

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class ScoresStore private constructor(
    private val rootDirectory: File,
) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, ScoresDirectoryName))

    private val recordsFile = File(rootDirectory, RecordsFileName)

    init {
        rootDirectory.mkdirs()
    }

    fun list(): List<ScoreRecord> {
        if (!recordsFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(recordsFile.readText())
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toScoreRecord()?.let(::add)
                }
            }.sortedByDescending { it.updatedAtMillis }
        }.getOrDefault(emptyList())
    }

    fun read(id: String): ScoreRecord? {
        return list().firstOrNull { it.id == id }
    }

    fun createQueued(
        title: String,
        sourceMime: String,
    ): ScoreRecord {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val directory = recordDirectory(id).apply { mkdirs() }
        return ScoreRecord(
            id = id,
            title = title.ifBlank { "Untitled score" },
            sourceMime = sourceMime,
            sourcePath = File(directory, SourceFileName).absolutePath,
            musicXmlPath = null,
            state = ScoreImportState.Queued,
            pageCount = 0,
            warnings = emptyList(),
            createdAtMillis = now,
            updatedAtMillis = now,
        ).also(::save)
    }

    fun save(record: ScoreRecord): ScoreRecord {
        val updated = record.copy(updatedAtMillis = System.currentTimeMillis())
        val records = (list().filterNot { it.id == updated.id } + updated)
            .sortedByDescending { it.updatedAtMillis }
        writeRecords(records)
        return updated
    }

    fun sourceFile(record: ScoreRecord): File {
        return File(record.sourcePath)
    }

    fun musicXmlFile(record: ScoreRecord): File {
        return File(recordDirectory(record.id).apply { mkdirs() }, MusicXmlFileName)
    }

    fun renderCacheDirectory(record: ScoreRecord): File {
        return File(recordDirectory(record.id).apply { mkdirs() }, RenderCacheDirectoryName).apply { mkdirs() }
    }

    fun delete(record: ScoreRecord) {
        recordDirectory(record.id).deleteRecursively()
        writeRecords(list().filterNot { it.id == record.id })
    }

    private fun recordDirectory(id: String): File {
        return File(rootDirectory, id)
    }

    private fun writeRecords(records: List<ScoreRecord>) {
        rootDirectory.mkdirs()
        val tmpFile = File(rootDirectory, "$RecordsFileName.tmp")
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        tmpFile.writeText(array.toString())
        if (!tmpFile.renameTo(recordsFile)) {
            recordsFile.delete()
            check(tmpFile.renameTo(recordsFile)) { "Unable to update scores metadata." }
        }
    }

    companion object {
        fun inDirectory(directory: File): ScoresStore = ScoresStore(directory)

        private const val ScoresDirectoryName = "scores"
        private const val RecordsFileName = "scores.json"
        private const val SourceFileName = "source"
        private const val MusicXmlFileName = "score.musicxml"
        private const val RenderCacheDirectoryName = "rendered"
    }
}

private fun ScoreRecord.toJson(): JSONObject {
    val warningsArray = JSONArray()
    warnings.forEach { warning ->
        warningsArray.put(
            JSONObject()
                .put("pageIndex", warning.pageIndex)
                .put("code", warning.code)
                .put("message", warning.message),
        )
    }
    return JSONObject()
        .put("id", id)
        .put("title", title)
        .put("sourceMime", sourceMime)
        .put("sourcePath", sourcePath)
        .put("musicXmlPath", musicXmlPath)
        .put("state", state.name)
        .put("pageCount", pageCount)
        .put("warnings", warningsArray)
        .put("createdAtMillis", createdAtMillis)
        .put("updatedAtMillis", updatedAtMillis)
}

private fun JSONObject.toScoreRecord(): ScoreRecord? {
    val id = optString("id").takeIf { it.isNotBlank() } ?: return null
    val warningsArray = optJSONArray("warnings") ?: JSONArray()
    val warnings = buildList {
        for (index in 0 until warningsArray.length()) {
            val item = warningsArray.optJSONObject(index) ?: continue
            add(
                ScoreWarning(
                    pageIndex = if (item.isNull("pageIndex")) null else item.optInt("pageIndex"),
                    code = item.optString("code"),
                    message = item.optString("message"),
                ),
            )
        }
    }

    return ScoreRecord(
        id = id,
        title = optString("title").takeIf { it.isNotBlank() } ?: "Untitled score",
        sourceMime = optString("sourceMime"),
        sourcePath = optString("sourcePath"),
        musicXmlPath = optString("musicXmlPath").takeIf { it.isNotBlank() && it != "null" },
        state = runCatching { ScoreImportState.valueOf(optString("state")) }
            .getOrDefault(ScoreImportState.Failed),
        pageCount = optInt("pageCount", 0),
        warnings = warnings,
        createdAtMillis = optLong("createdAtMillis", 0L),
        updatedAtMillis = optLong("updatedAtMillis", 0L),
    )
}
