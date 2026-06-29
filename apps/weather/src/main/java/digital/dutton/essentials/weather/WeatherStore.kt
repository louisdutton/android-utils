package digital.dutton.essentials.weather

import android.content.Context
import org.json.JSONObject

class WeatherStore(context: Context) {
    private val preferences = context.getSharedPreferences("weather", Context.MODE_PRIVATE)

    fun loadLocation(): WeatherLocation? {
        val encoded = preferences.getString(LocationKey, null) ?: return null
        return runCatching { JSONObject(encoded).toWeatherLocation() }.getOrNull()
    }

    fun saveLocation(location: WeatherLocation) {
        preferences.edit()
            .putString(LocationKey, location.toJson().toString())
            .apply()
    }

    private companion object {
        const val LocationKey = "location"
    }
}

private fun WeatherLocation.toJson(): JSONObject {
    return JSONObject()
        .put("id", id)
        .put("name", name)
        .put("country", country)
        .put("adminArea", adminArea)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("timezone", timezone)
        .put("isCurrentLocation", isCurrentLocation)
}

private fun JSONObject.toWeatherLocation(): WeatherLocation {
    return WeatherLocation(
        id = getString("id"),
        name = getString("name"),
        country = optString("country"),
        adminArea = optString("adminArea").takeIf { it.isNotBlank() },
        latitude = getDouble("latitude"),
        longitude = getDouble("longitude"),
        timezone = optString("timezone").takeIf { it.isNotBlank() },
        isCurrentLocation = optBoolean("isCurrentLocation", false),
    )
}
