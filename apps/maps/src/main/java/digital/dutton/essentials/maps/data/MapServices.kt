package digital.dutton.essentials.maps.data

import digital.dutton.essentials.locations.GeoPoint
import digital.dutton.essentials.locations.MapLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class MapStyleSource(
    val name: String,
    val url: String,
    val attribution: String,
)

data class RoutePreview(
    val destination: MapLocation,
    val points: List<GeoPoint>,
    val summary: String,
    val notice: String? = null,
)

interface LocationResolver {
    suspend fun search(
        query: String,
        limit: Int = 6,
    ): List<MapLocation>
}

interface RoutePlanner {
    suspend fun previewRoute(
        origin: GeoPoint,
        destination: MapLocation,
    ): RoutePreview?
}

class StraightLineRoutePlanner : RoutePlanner {
    override suspend fun previewRoute(
        origin: GeoPoint,
        destination: MapLocation,
    ): RoutePreview? {
        val destinationPoint = destination.point ?: return null

        return RoutePreview(
            destination = destination,
            points = listOf(origin, destinationPoint),
            summary = "Direct line only",
            notice = "Routing is unavailable. Showing a direct line until offline routing is installed.",
        )
    }
}

class PublicOsrmRoutePlanner : RoutePlanner {
    override suspend fun previewRoute(
        origin: GeoPoint,
        destination: MapLocation,
    ): RoutePreview? = withContext(Dispatchers.IO) {
        val destinationPoint = destination.point ?: return@withContext null
        val routeUrl = URL(
            "https://routing.openstreetmap.de/routed-car/route/v1/driving/" +
                "${origin.longitude},${origin.latitude};" +
                "${destinationPoint.longitude},${destinationPoint.latitude}" +
                "?overview=full&geometries=geojson&steps=false",
        )

        val connection = (routeUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "GrapheneOSEssentialsMaps/0.1 development")
        }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val response = JSONObject(body)
            if (response.optString("code") != "Ok") {
                return@withContext null
            }

            val route = response.getJSONArray("routes").optJSONObject(0) ?: return@withContext null
            val geometry = route.getJSONObject("geometry")
            val coordinates = geometry.getJSONArray("coordinates")
            val points = buildList {
                for (index in 0 until coordinates.length()) {
                    val coordinate = coordinates.getJSONArray(index)
                    add(
                        GeoPoint(
                            latitude = coordinate.getDouble(1),
                            longitude = coordinate.getDouble(0),
                        ),
                    )
                }
            }

            if (points.size < 2) {
                return@withContext null
            }

            RoutePreview(
                destination = destination,
                points = points,
                summary = "${formatDistance(route.getDouble("distance"))}, " +
                    "${formatDuration(route.getDouble("duration"))} (public routing)",
                notice = "Temporary public OSRM route. Offline routing is still the target.",
            )
        } catch (ignored: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}

class FallbackRoutePlanner(
    private val primary: RoutePlanner,
    private val fallback: RoutePlanner,
) : RoutePlanner {
    override suspend fun previewRoute(
        origin: GeoPoint,
        destination: MapLocation,
    ): RoutePreview? {
        return primary.previewRoute(origin, destination)
            ?: fallback.previewRoute(origin, destination)
    }
}

object MapDataSources {
    val developmentStyle = MapStyleSource(
        name = "OpenFreeMap Liberty",
        url = "https://tiles.openfreemap.org/styles/liberty",
        attribution = "OpenStreetMap",
    )
}

private fun formatDistance(meters: Double): String {
    return if (meters >= 1000.0) {
        String.format(Locale.US, "%.1f km", meters / 1000.0)
    } else {
        String.format(Locale.US, "%.0f m", meters)
    }
}

private fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60.0).toInt().coerceAtLeast(1)
    if (minutes < 60) {
        return "$minutes min"
    }

    val hours = minutes / 60
    val remainder = minutes % 60
    return if (remainder == 0) {
        "$hours h"
    } else {
        "$hours h $remainder min"
    }
}
