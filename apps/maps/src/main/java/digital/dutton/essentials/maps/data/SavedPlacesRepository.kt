package digital.dutton.essentials.maps.data

import android.content.Context
import digital.dutton.essentials.locations.GeoPoint
import digital.dutton.essentials.locations.LocationSource
import digital.dutton.essentials.locations.MapLocation
import org.json.JSONArray
import org.json.JSONObject

class SavedPlacesRepository(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("maps_saved_places", Context.MODE_PRIVATE)

    fun load(): List<MapLocation> {
        val raw = preferences.getString(KEY_PLACES, null) ?: return defaultPlaces()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toMapLocation()?.let(::add)
                }
            }
        }.getOrElse { defaultPlaces() }
    }

    fun save(location: MapLocation): List<MapLocation> {
        val savedLocation = location.copy(
            id = location.id.ifBlank { "saved-${System.currentTimeMillis()}" },
            source = LocationSource.Saved,
        )
        val next = load()
            .filterNot { it.id == savedLocation.id }
            .plus(savedLocation)
            .sortedBy { it.name.lowercase() }

        persist(next)
        return next
    }

    private fun persist(locations: List<MapLocation>) {
        val array = JSONArray()
        locations.forEach { location ->
            array.put(location.toJson())
        }
        preferences.edit().putString(KEY_PLACES, array.toString()).apply()
    }

    private fun defaultPlaces(): List<MapLocation> {
        return emptyList()
    }

    private companion object {
        const val KEY_PLACES = "places"
    }
}

private fun MapLocation.toJson(): JSONObject {
    return JSONObject()
        .put("id", id)
        .put("name", name)
        .put("address", address)
        .put("latitude", point?.latitude)
        .put("longitude", point?.longitude)
}

private fun JSONObject.toMapLocation(): MapLocation? {
    val id = optString("id").takeIf { it.isNotBlank() } ?: return null
    val name = optString("name").takeIf { it.isNotBlank() } ?: return null
    val latitude = optDoubleOrNull("latitude")
    val longitude = optDoubleOrNull("longitude")

    return MapLocation(
        id = id,
        name = name,
        address = optString("address").takeIf { it.isNotBlank() },
        point = if (latitude != null && longitude != null) {
            runCatching { GeoPoint(latitude, longitude) }.getOrNull()
        } else {
            null
        },
        source = LocationSource.Saved,
    )
}

private fun JSONObject.optDoubleOrNull(name: String): Double? {
    if (isNull(name)) return null
    return runCatching { getDouble(name) }.getOrNull()
}
