package digital.dutton.essentials.learn

import android.content.Context
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import android.provider.OpenableColumns
import android.text.Html
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import org.json.JSONObject

class AnkiPackageImporter(
    private val context: Context,
) {
    fun readPackage(uri: Uri): Pair<String, List<ImportedCard>> {
        val packageName = displayName(uri)
        val importDir = File(context.cacheDir, "anki-import-${System.nanoTime()}").apply {
            mkdirs()
        }
        val collectionFile = File(importDir, "collection.anki2")

        try {
            extractCollection(uri, collectionFile)
            val database = SQLiteDatabase.openDatabase(
                collectionFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            return database.use { db ->
                packageName to readCards(db)
            }
        } finally {
            importDir.deleteRecursively()
        }
    }

    private fun extractCollection(
        uri: Uri,
        outputFile: File,
    ) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected package." }
            ZipInputStream(input).use { zip ->
                var unsupportedCompressedCollection = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        "collection.anki2",
                        "collection.anki21" -> {
                            FileOutputStream(outputFile).use { output ->
                                zip.copyTo(output)
                            }
                            return
                        }
                        "collection.anki21b" -> unsupportedCompressedCollection = true
                    }
                    zip.closeEntry()
                }
                if (unsupportedCompressedCollection) {
                    error("This package uses Anki's compressed collection format, which is not supported yet.")
                }
                error("No collection.anki2 database was found in this Anki package.")
            }
        }
    }

    private fun readCards(database: SQLiteDatabase): List<ImportedCard> {
        val deckNames = readDeckNames(database)
        val cards = mutableListOf<ImportedCard>()
        database.rawQuery(
            """
                SELECT cards.id, cards.did, cards.ord, notes.flds
                FROM cards
                JOIN notes ON notes.id = cards.nid
                WHERE cards.queue != -1
                ORDER BY cards.id
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            val cardIdIndex = cursor.getColumnIndexOrThrow("id")
            val deckIdIndex = cursor.getColumnIndexOrThrow("did")
            val ordIndex = cursor.getColumnIndexOrThrow("ord")
            val fieldsIndex = cursor.getColumnIndexOrThrow("flds")
            while (cursor.moveToNext()) {
                val sourceCardId = cursor.getLong(cardIdIndex)
                val deckId = cursor.getLong(deckIdIndex)
                val ord = cursor.getInt(ordIndex)
                val fields = cursor.getString(fieldsIndex)
                    .split(AnkiFieldSeparator)
                    .map(::cleanField)
                    .filter { it.isNotBlank() }
                if (fields.isEmpty()) continue

                val front = if (ord % 2 == 1 && fields.size > 1) {
                    fields[1]
                } else {
                    fields.first()
                }
                val back = if (ord % 2 == 1 && fields.size > 1) {
                    fields.first()
                } else {
                    fields.drop(1).joinToString("\n\n").ifBlank { fields.first() }
                }
                cards += ImportedCard(
                    sourceCardId = sourceCardId,
                    deckId = deckId,
                    deckName = deckNames[deckId] ?: "Imported",
                    front = front,
                    back = back,
                )
            }
        }
        return cards
    }

    private fun readDeckNames(database: SQLiteDatabase): Map<Long, String> {
        val decksJson = database.rawQuery("SELECT decks FROM col LIMIT 1", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "{}"
        }
        val decks = JSONObject(decksJson)
        return buildMap {
            decks.keys().forEach { key ->
                val deck = decks.optJSONObject(key) ?: return@forEach
                val id = key.toLongOrNull() ?: deck.optLong("id", 0L)
                if (id != 0L) {
                    put(id, deck.optString("name", "Imported"))
                }
            }
        }
    }

    private fun cleanField(value: String): String {
        val withoutSounds = value.replace(SoundReferenceRegex, "")
        val withLineBreaks = withoutSounds.replace(LineBreakRegex, "\n")
        return Html.fromHtml(withLineBreaks, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(NonBreakingSpace, ' ')
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
    }

    private fun displayName(uri: Uri): String {
        val fromProvider = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else {
                null
            }
        }
        return fromProvider ?: uri.lastPathSegment ?: "Anki package"
    }

    private companion object {
        const val AnkiFieldSeparator = "\u001f"
        const val NonBreakingSpace = '\u00a0'
        val SoundReferenceRegex = Regex("\\[sound:[^]]+]")
        val LineBreakRegex = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    }
}
