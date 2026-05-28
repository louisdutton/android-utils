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
        title: String = "",
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
        val updatedNote = note.copy(updatedMillis = System.currentTimeMillis())
        val directory = noteDirectory(updatedNote.id).apply { mkdirs() }
        File(directory, NoteFileName).writeText(updatedNote.toMarkdownDocument())
        return updatedNote
    }

    fun delete(note: Note) {
        noteDirectory(note.id).deleteRecursively()
    }

    private fun readNote(directory: File): Note? {
        val markdownFile = File(directory, NoteFileName)
        if (!markdownFile.exists()) return null

        val document = markdownFile.readText()
        val (metadata, body) = document.parseMarkdownDocument()
        if (metadata["kind"] == "audio" || metadata["audio"] != null) return null

        val now = markdownFile.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        return Note(
            id = metadata["id"] ?: directory.name,
            title = metadata["title"] ?: "Untitled note",
            body = body,
            createdMillis = metadata["created"]?.toLongOrNull() ?: now,
            updatedMillis = metadata["updated"]?.toLongOrNull() ?: now,
        )
    }

    private fun noteDirectory(id: String): File {
        return File(rootDirectory, id)
    }

    private companion object {
        const val NotesDirectoryName = "notes"
        const val NoteFileName = "note.md"
    }
}

private fun Note.toMarkdownDocument(): String {
    return buildString {
        appendLine("---")
        appendLine("id: $id")
        appendLine("kind: text")
        appendLine("title: ${title.toMetadataValue()}")
        appendLine("created: $createdMillis")
        appendLine("updated: $updatedMillis")
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
    if (!startsWith("---")) return emptyMap<String, String>() to this

    val lines = lines()
    val endIndex = lines.drop(1).indexOfFirst { it == "---" }
    if (endIndex < 0) return emptyMap<String, String>() to this

    val metadata = lines
        .drop(1)
        .take(endIndex)
        .mapNotNull { line ->
            val separatorIndex = line.indexOf(':')
            if (separatorIndex <= 0) return@mapNotNull null
            val key = line.substring(0, separatorIndex).trim()
            val value = line.substring(separatorIndex + 1).trim()
            key to value
        }
        .toMap()
    val body = lines.drop(endIndex + 3).joinToString("\n").trimEnd()
    return metadata to body
}
