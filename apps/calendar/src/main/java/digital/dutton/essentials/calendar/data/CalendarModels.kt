package digital.dutton.essentials.calendar.data

data class CalendarSource(
    val id: Long,
    val displayName: String,
    val accountName: String?,
    val accountType: String?,
    val color: Int?,
    val isPrimary: Boolean,
    val isVisible: Boolean,
    val isWritable: Boolean,
    val isSubscribed: Boolean,
)

data class CalendarEvent(
    val id: Long,
    val calendarId: Long,
    val calendarName: String?,
    val calendarColor: Int?,
    val title: String,
    val location: String?,
    val description: String?,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timeZone: String?,
    val recurrenceRule: String?,
    val availability: EventAvailability,
    val isReadOnly: Boolean,
)

data class CalendarEventDraft(
    val calendarId: Long,
    val title: String,
    val location: String?,
    val description: String?,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timeZone: String,
    val availability: EventAvailability,
)

enum class EventAvailability {
    Busy,
    Free,
    Tentative,
    Unknown,
}
