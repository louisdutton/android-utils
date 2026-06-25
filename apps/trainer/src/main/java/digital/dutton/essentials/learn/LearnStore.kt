package digital.dutton.essentials.learn

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.util.concurrent.TimeUnit

class LearnStore(context: Context) : SQLiteOpenHelper(
    context,
    DatabaseName,
    null,
    DatabaseVersion,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE decks (
                    id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL
                )
            """.trimIndent(),
        )
        db.execSQL(
            """
                CREATE TABLE cards (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_card_id INTEGER NOT NULL UNIQUE,
                    deck_id INTEGER NOT NULL,
                    deck_name TEXT NOT NULL,
                    front TEXT NOT NULL,
                    back TEXT NOT NULL,
                    due_at INTEGER NOT NULL,
                    interval_days INTEGER NOT NULL DEFAULT 0,
                    ease_factor INTEGER NOT NULL DEFAULT 250,
                    reps INTEGER NOT NULL DEFAULT 0,
                    lapses INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX cards_deck_due_idx ON cards(deck_id, due_at)")
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        db.execSQL("DROP TABLE IF EXISTS cards")
        db.execSQL("DROP TABLE IF EXISTS decks")
        onCreate(db)
    }

    fun importCards(
        packageName: String,
        cards: List<ImportedCard>,
    ): ImportResult {
        if (cards.isEmpty()) {
            return ImportResult(
                packageName = packageName,
                totalCards = 0,
                newCards = 0,
                deckCount = 0,
            )
        }

        val now = Instant.now().toEpochMilli()
        var newCards = 0
        writableDatabase.useTransaction {
            cards.groupBy { it.deckId }.forEach { (deckId, deckCards) ->
                val name = deckCards.first().deckName
                insertWithOnConflict(
                    "decks",
                    null,
                    ContentValues().apply {
                        put("id", deckId)
                        put("name", name)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            cards.forEach { card ->
                val inserted = insertWithOnConflict(
                    "cards",
                    null,
                    ContentValues().apply {
                        put("source_card_id", card.sourceCardId)
                        put("deck_id", card.deckId)
                        put("deck_name", card.deckName)
                        put("front", card.front)
                        put("back", card.back)
                        put("due_at", now)
                        put("updated_at", now)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (inserted != -1L) newCards += 1
            }
        }

        return ImportResult(
            packageName = packageName,
            totalCards = cards.size,
            newCards = newCards,
            deckCount = cards.map { it.deckId }.distinct().size,
        )
    }

    fun listDecks(): List<DeckSummary> {
        val now = Instant.now().toEpochMilli()
        return readableDatabase.rawQuery(
            """
                SELECT
                    deck_id,
                    deck_name,
                    COUNT(*) AS card_count,
                    SUM(CASE WHEN due_at <= ? THEN 1 ELSE 0 END) AS due_count
                FROM cards
                GROUP BY deck_id, deck_name
                ORDER BY deck_name COLLATE NOCASE
            """.trimIndent(),
            arrayOf(now.toString()),
        ).use { cursor ->
            buildList {
                val deckIdIndex = cursor.getColumnIndexOrThrow("deck_id")
                val nameIndex = cursor.getColumnIndexOrThrow("deck_name")
                val cardCountIndex = cursor.getColumnIndexOrThrow("card_count")
                val dueCountIndex = cursor.getColumnIndexOrThrow("due_count")
                while (cursor.moveToNext()) {
                    add(
                        DeckSummary(
                            id = cursor.getLong(deckIdIndex),
                            name = cursor.getString(nameIndex),
                            cardCount = cursor.getInt(cardCountIndex),
                            dueCount = cursor.getInt(dueCountIndex),
                        ),
                    )
                }
            }
        }
    }

    fun nextCard(deckId: Long): StudyCard? {
        val now = Instant.now().toEpochMilli()
        return readableDatabase.rawQuery(
            """
                SELECT id, deck_id, deck_name, front, back, reps
                FROM cards
                WHERE deck_id = ?
                ORDER BY
                    CASE WHEN due_at <= ? THEN 0 ELSE 1 END,
                    due_at,
                    id
                LIMIT 1
            """.trimIndent(),
            arrayOf(deckId.toString(), now.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            StudyCard(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                deckId = cursor.getLong(cursor.getColumnIndexOrThrow("deck_id")),
                deckName = cursor.getString(cursor.getColumnIndexOrThrow("deck_name")),
                front = cursor.getString(cursor.getColumnIndexOrThrow("front")),
                back = cursor.getString(cursor.getColumnIndexOrThrow("back")),
                reps = cursor.getInt(cursor.getColumnIndexOrThrow("reps")),
            )
        }
    }

    fun recordReview(
        card: StudyCard,
        rating: ReviewRating,
    ) {
        val now = Instant.now().toEpochMilli()
        val current = readableDatabase.rawQuery(
            "SELECT interval_days, ease_factor, lapses FROM cards WHERE id = ?",
            arrayOf(card.id.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            CardSchedule(
                intervalDays = cursor.getInt(cursor.getColumnIndexOrThrow("interval_days")),
                easeFactor = cursor.getInt(cursor.getColumnIndexOrThrow("ease_factor")),
                lapses = cursor.getInt(cursor.getColumnIndexOrThrow("lapses")),
            )
        }

        val next = when (rating) {
            ReviewRating.Again -> current.copy(
                intervalDays = 0,
                easeFactor = (current.easeFactor - 20).coerceAtLeast(130),
                lapses = current.lapses + 1,
            )
            ReviewRating.Good -> current.copy(
                intervalDays = if (current.intervalDays <= 0) 1 else current.intervalDays * 2,
            )
            ReviewRating.Easy -> current.copy(
                intervalDays = if (current.intervalDays <= 0) 3 else current.intervalDays * 3,
                easeFactor = (current.easeFactor + 15).coerceAtMost(350),
            )
        }
        val dueAt = when (rating) {
            ReviewRating.Again -> now + TimeUnit.MINUTES.toMillis(10)
            else -> now + TimeUnit.DAYS.toMillis(next.intervalDays.toLong())
        }

        writableDatabase.update(
            "cards",
            ContentValues().apply {
                put("due_at", dueAt)
                put("interval_days", next.intervalDays)
                put("ease_factor", next.easeFactor)
                put("lapses", next.lapses)
                put("reps", card.reps + 1)
                put("updated_at", now)
            },
            "id = ?",
            arrayOf(card.id.toString()),
        )
    }

    private fun SQLiteDatabase.useTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private data class CardSchedule(
        val intervalDays: Int,
        val easeFactor: Int,
        val lapses: Int,
    )

    private companion object {
        const val DatabaseName = "learn.db"
        const val DatabaseVersion = 1
    }
}
