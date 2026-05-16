package digital.dutton.essentials.calendar.sync

interface CalendarSyncGateway {
    suspend fun sync(request: CalendarSyncRequest): CalendarSyncResult
}

data class CalendarSyncRequest(
    val accountId: String,
    val forceFullSync: Boolean = false,
)

data class CalendarSyncResult(
    val created: Int,
    val updated: Int,
    val deleted: Int,
    val conflicts: Int,
)
