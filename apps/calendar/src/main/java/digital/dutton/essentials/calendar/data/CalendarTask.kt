package digital.dutton.essentials.calendar.data

data class CalendarTask(
    val id: String,
    val accountId: String?,
    val collectionId: String?,
    val collectionHref: String?,
    val href: String?,
    val etag: String?,
    val uid: String,
    val listName: String?,
    val listColor: Int?,
    val title: String,
    val description: String?,
    val status: CalendarTaskStatus,
    val dueMillis: Long?,
    val dueAllDay: Boolean,
    val startMillis: Long?,
    val startAllDay: Boolean,
    val completedMillis: Long?,
    val createdMillis: Long?,
    val lastModifiedMillis: Long?,
    val priority: Int?,
    val isReadOnly: Boolean,
)

enum class CalendarTaskStatus {
    NeedsAction,
    InProcess,
    Completed,
    Cancelled,
    Unknown,
}
