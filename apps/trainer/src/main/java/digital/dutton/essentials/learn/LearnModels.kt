package digital.dutton.essentials.learn

data class DeckSummary(
    val id: Long,
    val name: String,
    val cardCount: Int,
    val dueCount: Int,
)

data class StudyCard(
    val id: Long,
    val deckId: Long,
    val deckName: String,
    val front: String,
    val back: String,
    val reps: Int,
)

data class ImportedCard(
    val sourceCardId: Long,
    val deckId: Long,
    val deckName: String,
    val front: String,
    val back: String,
)

data class ImportResult(
    val packageName: String,
    val totalCards: Int,
    val newCards: Int,
    val deckCount: Int,
)

enum class ReviewRating {
    Again,
    Good,
    Easy,
}
