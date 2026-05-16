package digital.dutton.essentials.locations

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude must be between -90 and 90." }
        require(longitude in -180.0..180.0) { "Longitude must be between -180 and 180." }
    }
}

data class MapLocation(
    val id: String,
    val name: String,
    val address: String? = null,
    val point: GeoPoint? = null,
    val source: LocationSource = LocationSource.Manual,
) {
    val displayAddress: String
        get() = address?.takeIf { it.isNotBlank() } ?: name
}

enum class LocationSource {
    Manual,
    Saved,
    Search,
    CalendarProvider,
}

data class EventLocationLink(
    val eventId: Long,
    val location: MapLocation,
    val rawProviderLocation: String,
)
