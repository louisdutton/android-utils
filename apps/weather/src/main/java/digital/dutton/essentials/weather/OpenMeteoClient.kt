package digital.dutton.essentials.weather

import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class OpenMeteoClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    fun searchLocations(query: String): List<WeatherLocation> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", trimmed)
            .addQueryParameter("count", "8")
            .addQueryParameter("language", "en")
            .addQueryParameter("format", "json")
            .build()

        val json = getJson(url.toString())
        val results = json.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                add(
                    WeatherLocation(
                        id = item.optLong("id").toString(),
                        name = item.optString("name"),
                        country = item.optString("country"),
                        adminArea = item.optString("admin1").takeIf { it.isNotBlank() },
                        latitude = item.optDouble("latitude"),
                        longitude = item.optDouble("longitude"),
                        timezone = item.optString("timezone").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    fun fetchForecast(location: WeatherLocation): WeatherSnapshot {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", location.latitude.toString())
            .addQueryParameter("longitude", location.longitude.toString())
            .addQueryParameter(
                "current",
                "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m,precipitation",
            )
            .addQueryParameter("hourly", "temperature_2m,precipitation_probability,weather_code")
            .addQueryParameter(
                "daily",
                "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
            )
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "7")
            .build()

        return parseForecast(location, getJson(url.toString()))
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GrapheneOS-Essentials-Weather/0.1")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Weather service returned ${response.code}.")
            }
            return JSONObject(body)
        }
    }

    private fun parseForecast(
        location: WeatherLocation,
        json: JSONObject,
    ): WeatherSnapshot {
        val current = json.getJSONObject("current")
        val currentWeather = CurrentWeather(
            time = LocalDateTime.parse(current.getString("time")),
            temperatureC = current.getDouble("temperature_2m"),
            apparentTemperatureC = current.getDouble("apparent_temperature"),
            humidityPercent = current.getInt("relative_humidity_2m"),
            precipitationMm = current.optDouble("precipitation", 0.0),
            windSpeedKmh = current.getDouble("wind_speed_10m"),
            windDirectionDegrees = current.optInt("wind_direction_10m"),
            weatherCode = current.getInt("weather_code"),
        )

        val hourlyJson = json.getJSONObject("hourly")
        val hourlyTimes = hourlyJson.getJSONArray("time")
        val currentTimeKey = current.getString("time")
        val hourly = buildList {
            for (index in 0 until hourlyTimes.length()) {
                val timeKey = hourlyTimes.getString(index)
                if (timeKey < currentTimeKey) continue
                add(
                    HourlyWeather(
                        time = LocalDateTime.parse(timeKey),
                        temperatureC = hourlyJson.getJSONArray("temperature_2m").getDouble(index),
                        precipitationProbabilityPercent = hourlyJson.optJSONArray("precipitation_probability")
                            ?.optNullableInt(index),
                        weatherCode = hourlyJson.getJSONArray("weather_code").getInt(index),
                    ),
                )
                if (size == 24) break
            }
        }

        val dailyJson = json.getJSONObject("daily")
        val dailyTimes = dailyJson.getJSONArray("time")
        val daily = buildList {
            for (index in 0 until dailyTimes.length()) {
                add(
                    DailyWeather(
                        date = LocalDate.parse(dailyTimes.getString(index)),
                        highC = dailyJson.getJSONArray("temperature_2m_max").getDouble(index),
                        lowC = dailyJson.getJSONArray("temperature_2m_min").getDouble(index),
                        precipitationProbabilityPercent = dailyJson.optJSONArray("precipitation_probability_max")
                            ?.optNullableInt(index),
                        weatherCode = dailyJson.getJSONArray("weather_code").getInt(index),
                    ),
                )
            }
        }

        return WeatherSnapshot(
            location = location,
            current = currentWeather,
            hourly = hourly,
            daily = daily,
        )
    }
}

private fun JSONArray.optNullableInt(index: Int): Int? {
    return if (isNull(index)) null else optInt(index)
}
