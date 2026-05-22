package digital.dutton.essentials.notes

import android.content.Context
import java.io.File
import java.util.UUID

enum class NoteKind {
    Text,
    Audio,
}

data class Note(
    val id: String,
    val kind: NoteKind,
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

    fun createTextNote(
        title: String = "",
        body: String = "",
    ): Note {
        val now = System.currentTimeMillis()
        val note = Note(
            id = UUID.randomUUID().toString(),
            kind = NoteKind.Text,
            title = title,
            body = body,
            createdMillis = now,
            updatedMillis = now,
        )
        save(note)
        return note
    }

    fun createAudioNote(
        title: String = "",
    ): Note {
        val now = System.currentTimeMillis()
        val note = Note(
            id = UUID.randomUUID().toString(),
            kind = NoteKind.Audio,
            title = title,
            body = "",
            createdMillis = now,
            updatedMillis = now,
        )
        save(note)
        return note
    }

    fun save(note: Note): Note {
        val updatedNote = when (note.kind) {
            NoteKind.Text -> note.copy(
                title = note.title,
                body = note.body,
                audioFileName = null,
                audioDurationMillis = null,
                updatedMillis = System.currentTimeMillis(),
            )
            NoteKind.Audio -> note.copy(
                title = note.title,
                body = "",
                updatedMillis = System.currentTimeMillis(),
            )
        }
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
        val audioFileName = metadata["audio"]?.takeIf { it.isNotBlank() }
        val kind = metadata["kind"]?.toNoteKind()
            ?: if (audioFileName != null && body.isBlank()) NoteKind.Audio else NoteKind.Text
        val defaultTitle = when (kind) {
            NoteKind.Text -> "Untitled note"
            NoteKind.Audio -> "Audio note"
        }

        return Note(
            id = metadata["id"] ?: directory.name,
            kind = kind,
            title = metadata["title"] ?: defaultTitle,
            body = if (kind == NoteKind.Text) body else "",
            createdMillis = metadata["created"]?.toLongOrNull() ?: now,
            updatedMillis = metadata["updated"]?.toLongOrNull() ?: now,
            audioFileName = if (kind == NoteKind.Audio) audioFileName else null,
            audioDurationMillis = if (kind == NoteKind.Audio) {
                metadata["audioDurationMillis"]?.toLongOrNull()
            } else {
                null
            },
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
        appendLine("kind: ${kind.toMetadataValue()}")
        appendLine("title: ${title.toMetadataValue()}")
        appendLine("created: $createdMillis")
        appendLine("updated: $updatedMillis")
        if (kind == NoteKind.Audio) {
            audioFileName?.let { appendLine("audio: ${it.toMetadataValue()}") }
            audioDurationMillis?.let { appendLine("audioDurationMillis: $it") }
        }
        appendLine("---")
        appendLine()
        if (kind == NoteKind.Text) {
            append(body.trimEnd())
        }
        appendLine()
    }
}

private fun String.toNoteKind(): NoteKind? {
    return when (trim().lowercase()) {
        "text" -> NoteKind.Text
        "audio" -> NoteKind.Audio
        else -> null
    }
}

private fun NoteKind.toMetadataValue(): String {
    return when (this) {
        NoteKind.Text -> "text"
        NoteKind.Audio -> "audio"
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
