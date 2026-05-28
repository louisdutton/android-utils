package digital.dutton.essentials.recorder

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Recording(
    val id: String,
    val file: File,
    val createdMillis: Long,
    val durationMillis: Long,
    val sizeBytes: Long,
)

class RecorderStore(context: Context) {
    private val rootDirectory = File(
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
        RecordingsDirectoryName,
    ).apply { mkdirs() }

    fun listRecordings(): List<Recording> {
        return rootDirectory
            .listFiles { file -> file.isFile && file.extension.equals(AudioExtension, ignoreCase = true) }
            .orEmpty()
            .map(::readRecording)
            .sortedByDescending { it.createdMillis }
    }

    fun createOutputFile(): File {
        rootDirectory.mkdirs()
        val timestamp = Instant.now()
            .atZone(ZoneId.systemDefault())
            .format(FileNameFormatter)
        return File(rootDirectory, "recording-$timestamp.$AudioExtension")
    }

    fun delete(recording: Recording) {
        recording.file.delete()
    }

    private fun readRecording(file: File): Recording {
        return Recording(
            id = file.name,
            file = file,
            createdMillis = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
            durationMillis = file.durationMillis(),
            sizeBytes = file.length(),
        )
    }

    private companion object {
        const val RecordingsDirectoryName = "recordings"
        const val AudioExtension = "m4a"
        val FileNameFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

private fun File.durationMillis(): Long {
    return runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }.getOrNull() ?: 0L
}
