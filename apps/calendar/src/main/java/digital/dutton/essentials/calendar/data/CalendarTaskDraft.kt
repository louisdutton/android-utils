package digital.dutton.essentials.calendar.data

data class CalendarTaskDraft(
    val collectionId: String?,
    val title: String,
    val description: String?,
    val dueMillis: Long,
    val dueAllDay: Boolean,
    val timeZone: String,
    val recurrenceRule: String?,
    val priority: Int?,
)
