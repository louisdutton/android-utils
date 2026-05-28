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
            id = locationMapId?.takeIf { it.isNotBlank() } ?: "calendar-event-$id-location",
            name = locationMapName?.takeIf { it.isNotBlank() } ?: providerLocation,
            address = providerLocation,
            point = locationPoint,
            source = if (locationPoint == null) LocationSource.CalendarProvider else LocationSource.Search,
        ),
    )
}
