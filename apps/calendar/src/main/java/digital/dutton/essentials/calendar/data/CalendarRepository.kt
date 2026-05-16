package digital.dutton.essentials.calendar.data

interface CalendarRepository {
    suspend fun listCalendars(): List<CalendarSource>

    suspend fun listEvents(
        startMillis: Long,
        endMillis: Long,
    ): List<CalendarEvent>
}
