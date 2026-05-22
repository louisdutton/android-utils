package digital.dutton.essentials.notes

import android.content.Context
import java.io.File
import java.util.UUID

data class Note(
    val id: String,
    val title: String,
    val body: String,
    val createdMillis: Long,
    val updatedMillis: Long,
    val audioFileName: String? = null,
    val audioDurationMillis: Long? = null,
)

class NotesStore(context: Context) {
    private val rootDirectory = File(context.filesDir, NotesDirectoryName).apply {
        mkdirs()
    }

    fun listNotes(): List<Note> {
        return rootDirectory
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull(::readNote)
            .sortedByDescending { it.updatedMillis }
    }

    fun createNote(
        title: String = "Untitled note",
        body: String = "",
    ): Note {
        val now = System.currentTimeMillis()
        val note = Note(
            id = UUID.randomUUID().toString(),
            title = title,
            body = body,
            createdMillis = now,
            updatedMillis = now,
        )
        save(note)
        return note
    }

    fun save(note: Note): Note {
        val updatedNote = note.copy(
            title = note.title.ifBlank { "Untitled note" },
            updatedMillis = System.currentTimeMillis(),
        )
        val directory = noteDirectory(updatedNote.id).apply { mkdirs() }
        File(directory, NoteFileName).writeText(updatedNote.toMarkdownDocument())
        return updatedNote
    }

    fun delete(note: Note) {
        noteDirectory(note.id).deleteRecursively()
    }

    fun createAudioFile(note: Note): File {
        return File(noteDirectory(note.id).apply { mkdirs() }, AudioFileName)
    }

    fun audioFile(note: Note): File? {
        val fileName = note.audioFileName ?: return null
        return File(noteDirectory(note.id), fileName).takeIf { it.exists() }
    }

    private fun readNote(directory: File): Note? {
        val markdownFile = File(directory, NoteFileName)
        if (!markdownFile.exists()) return null

        val document = markdownFile.readText()
        val (metadata, body) = document.parseMarkdownDocument()
        val now = markdownFile.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()

        return Note(
            id = metadata["id"] ?: directory.name,
            title = metadata["title"]?.takeIf { it.isNotBlank() } ?: "Untitled note",
            body = body,
            createdMillis = metadata["created"]?.toLongOrNull() ?: now,
            updatedMillis = metadata["updated"]?.toLongOrNull() ?: now,
            audioFileName = metadata["audio"]?.takeIf { it.isNotBlank() },
            audioDurationMillis = metadata["audioDurationMillis"]?.toLongOrNull(),
        )
    }

    private fun noteDirectory(id: String): File {
        return File(rootDirectory, id)
    }

    private companion object {
        const val NotesDirectoryName = "notes"
        const val NoteFileName = "note.md"
        const val AudioFileName = "audio.m4a"
    }
}

private fun Note.toMarkdownDocument(): String {
    return buildString {
        appendLine("---")
        appendLine("id: $id")
        appendLine("title: ${title.toMetadataValue()}")
        appendLine("created: $createdMillis")
        appendLine("updated: $updatedMillis")
        audioFileName?.let { appendLine("audio: ${it.toMetadataValue()}") }
        audioDurationMillis?.let { appendLine("audioDurationMillis: $it") }
        appendLine("---")
        appendLine()
        append(body.trimEnd())
        appendLine()
    }
}

private fun String.toMetadataValue(): String {
    return replace('\n', ' ').replace('\r', ' ').trim()
}

private fun String.parseMarkdownDocument(): Pair<Map<String, String>, String> {
    if (!startsWith("---\n")) return emptyMap<String, String>() to this

    val endIndex = indexOf("\n---\n", startIndex = 4)
    if (endIndex < 0) return emptyMap<String, String>() to this

    val metadata = substring(4, endIndex)
        .lineSequence()
        .mapNotNull { line ->
            val separatorIndex = line.indexOf(':')
            if (separatorIndex <= 0) return@mapNotNull null
            val key = line.substring(0, separatorIndex).trim()
            val value = line.substring(separatorIndex + 1).trim()
            key to value
        }
        .toMap()
    val body = substring(endIndex + "\n---\n".length)
        .trimStart('\n', '\r')

    return metadata to body
}
