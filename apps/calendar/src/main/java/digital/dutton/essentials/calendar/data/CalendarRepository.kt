package digital.dutton.essentials.calendar.data

interface CalendarRepository {
    suspend fun listCalendars(): List<CalendarSource>

    suspend fun listEvents(
        startMillis: Long,
        endMillis: Long,
    ): List<CalendarEvent>

    suspend fun createEvent(event: CalendarEventDraft): Long

    suspend fun updateEvent(
        eventId: Long,
        event: CalendarEventDraft,
    )

    suspend fun deleteEvent(eventId: Long)
}
