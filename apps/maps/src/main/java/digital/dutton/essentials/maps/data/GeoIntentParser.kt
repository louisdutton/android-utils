package digital.dutton.essentials.maps.data

import android.net.Uri
import digital.dutton.essentials.locations.GeoPoint
import digital.dutton.essentials.locations.LocationSource
import digital.dutton.essentials.locations.MapLocation
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

fun Uri.toMapLocation(): MapLocation? {
    if (scheme != "geo") return null

    val raw = toString()
    val coordinatePart = raw.removePrefix("geo:").substringBefore("?")
    val point = coordinatePart.toGeoPoint()
    val query = raw.extractGeoQuery()
    val label = query ?: point?.let { "${it.latitude}, ${it.longitude}" } ?: return null

    return MapLocation(
        id = "geo-${raw.hashCode()}",
        name = label,
        address = query,
        point = point,
        source = LocationSource.Search,
    )
}

private fun String.toGeoPoint(): GeoPoint? {
    val parts = split(",")
    if (parts.size < 2) return null

    val latitude = parts[0].toDoubleOrNull() ?: return null
    val longitude = parts[1].toDoubleOrNull() ?: return null
    if (latitude == 0.0 && longitude == 0.0) return null

    return runCatching { GeoPoint(latitude, longitude) }.getOrNull()
}

private fun String.extractGeoQuery(): String? {
    val encoded = Regex("[?&]q=([^&]+)").find(this)?.groupValues?.getOrNull(1) ?: return null
    return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
        .takeIf { it.isNotBlank() }
}
