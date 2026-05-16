package digital.dutton.essentials.calendar.sync

data class CalDavEndpoint(
    val baseUrl: String,
    val username: String,
    val calendarHref: String?,
)
