package digital.dutton.essentials.calendar.sync

import java.time.Duration

internal const val SubscriptionAccountName = "Essentials subscribed calendars"
internal const val SubscriptionAccountType = "digital.dutton.essentials.calendar.subscription"

data class CalendarSubscription(
    val id: String,
    val url: String,
    val displayName: String,
    val calendarId: Long,
    val lastEtag: String? = null,
    val lastModified: String? = null,
    val lastSyncMillis: Long? = null,
    val lastError: String? = null,
)

data class IcsSubscriptionSyncSummary(
    val subscriptionId: String,
    val created: Int,
    val updated: Int,
    val deleted: Int,
    val skipped: Int,
)

data class IcsCalendarFeed(
    val displayName: String?,
    val events: List<IcsCalendarEvent>,
)

data class IcsCalendarEvent(
    val uid: String,
    val recurrenceId: String?,
    val title: String,
    val description: String?,
    val location: String?,
    val start: IcsEventDateTime,
    val end: IcsEventDateTime?,
    val duration: Duration?,
    val recurrenceRule: String?,
    val recurrenceDates: String?,
    val exceptionRule: String?,
    val exceptionDates: String?,
    val transparency: String?,
)

data class IcsEventDateTime(
    val epochMillis: Long,
    val allDay: Boolean,
    val timeZone: String,
)

internal val IcsCalendarEvent.remoteId: String
    get() = listOf(uid, recurrenceId)
        .filterNotNull()
        .joinToString("|")
