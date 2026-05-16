package digital.dutton.essentials.calendar.data

import digital.dutton.essentials.locations.EventLocationLink
import digital.dutton.essentials.locations.LocationSource
import digital.dutton.essentials.locations.MapLocation

fun CalendarEvent.locationLink(): EventLocationLink? {
    val providerLocation = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    return EventLocationLink(
        eventId = id,
        rawProviderLocation = providerLocation,
        location = MapLocation(
            id = "calendar-event-$id-location",
            name = providerLocation,
            address = providerLocation,
            source = LocationSource.CalendarProvider,
        ),
    )
}
