package digital.dutton.essentials.maps.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import digital.dutton.essentials.locations.GeoPoint
import digital.dutton.essentials.locations.LocationSource
import digital.dutton.essentials.locations.MapLocation
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidGeocoderLocationResolver(
    context: Context,
) : LocationResolver {
    private val geocoder = Geocoder(context, Locale.getDefault())

    override suspend fun search(
        query: String,
        limit: Int,
    ): List<MapLocation> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty() || !Geocoder.isPresent()) return emptyList()

        return withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            runCatching {
                geocoder.getFromLocationName(normalizedQuery, limit).orEmpty()
                    .mapIndexedNotNull { index, address ->
                        address.toMapLocation(normalizedQuery, index)
                    }
            }.getOrDefault(emptyList())
        }
    }
}

private fun Address.toMapLocation(
    query: String,
    index: Int,
): MapLocation? {
    if (!hasLatitude() || !hasLongitude()) return null

    val point = GeoPoint(
        latitude = latitude,
        longitude = longitude,
    )
    val addressText = addressText()
    val label = listOfNotNull(
        featureName?.takeIf { it.isNotBlank() },
        locality?.takeIf { it.isNotBlank() },
        adminArea?.takeIf { it.isNotBlank() },
    ).firstOrNull() ?: query

    return MapLocation(
        id = "search-${query.hashCode()}-$index-${point.latitude}-${point.longitude}",
        name = label,
        address = addressText.ifBlank { query },
        point = point,
        source = LocationSource.Search,
    )
}

private fun Address.addressText(): String {
    if (maxAddressLineIndex < 0) return ""

    return (0..maxAddressLineIndex)
        .mapNotNull { index -> getAddressLine(index)?.takeIf { it.isNotBlank() } }
        .joinToString(", ")
}
