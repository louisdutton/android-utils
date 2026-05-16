package digital.dutton.essentials.maps.data

import digital.dutton.essentials.locations.GeoPoint
import digital.dutton.essentials.locations.MapLocation

data class MapStyleSource(
    val name: String,
    val url: String,
    val attribution: String,
)

data class RoutePreview(
    val destination: MapLocation,
    val points: List<GeoPoint>,
    val summary: String,
)

interface LocationResolver {
    suspend fun search(
        query: String,
        limit: Int = 6,
    ): List<MapLocation>
}

interface RoutePlanner {
    fun previewRoute(
        origin: GeoPoint,
        destination: MapLocation,
    ): RoutePreview?
}

class StraightLineRoutePlanner : RoutePlanner {
    override fun previewRoute(
        origin: GeoPoint,
        destination: MapLocation,
    ): RoutePreview? {
        val destinationPoint = destination.point ?: return null

        return RoutePreview(
            destination = destination,
            points = listOf(origin, destinationPoint),
            summary = "Direct preview",
        )
    }
}

object MapDataSources {
    val developmentStyle = MapStyleSource(
        name = "OpenFreeMap Liberty",
        url = "https://tiles.openfreemap.org/styles/liberty",
        attribution = "OpenStreetMap",
    )
}
