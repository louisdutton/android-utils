package digital.dutton.essentials.calendar.data

import digital.dutton.essentials.locations.GeoPoint

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
    val isCalDav: Boolean,
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
    val locationPoint: GeoPoint? = null,
    val locationMapName: String? = null,
    val locationMapId: String? = null,
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
    val locationPoint: GeoPoint? = null,
    val locationMapName: String? = null,
    val locationMapId: String? = null,
)

enum class EventAvailability {
    Busy,
    Free,
    Tentative,
    Unknown,
}
