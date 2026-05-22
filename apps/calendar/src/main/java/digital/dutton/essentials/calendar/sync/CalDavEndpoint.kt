package digital.dutton.essentials.calendar.sync

import java.util.UUID

internal const val CalDavAccountType = "digital.dutton.essentials.calendar.caldav"

data class CalDavEndpoint(
    val baseUrl: String,
    val username: String,
    val password: String,
)

data class CalDavAccount(
    val id: String = UUID.randomUUID().toString(),
    val baseUrl: String,
    val username: String,
    val password: String,
    val displayName: String,
    val lastSyncMillis: Long? = null,
    val lastError: String? = null,
)

data class CalDavCalendar(
    val id: String,
    val accountId: String,
    val localCalendarId: Long,
    val href: String,
    val displayName: String,
    val color: Int? = null,
    val syncToken: String? = null,
    val lastSyncMillis: Long? = null,
    val lastError: String? = null,
)

data class CalDavSyncSummary(
    val accountId: String,
    val created: Int,
    val updated: Int,
    val deleted: Int,
    val conflicts: Int,
)

data class CalDavDiscoveredCalendar(
    val href: String,
    val displayName: String,
    val color: Int?,
    val syncToken: String?,
)
