package digital.dutton.essentials.weather

import java.time.LocalDate
import java.time.LocalDateTime

data class WeatherLocation(
    val id: String,
    val name: String,
    val country: String,
    val adminArea: String?,
    val latitude: Double,
    val longitude: Double,
    val timezone: String?,
    val isCurrentLocation: Boolean = false,
) {
    val title: String
        get() = name

    val subtitle: String
        get() = listOfNotNull(adminArea?.takeIf { it.isNotBlank() }, country.takeIf { it.isNotBlank() })
            .joinToString(", ")
}

data class CurrentWeather(
    val time: LocalDateTime,
    val temperatureC: Double,
    val apparentTemperatureC: Double,
    val humidityPercent: Int,
    val precipitationMm: Double,
    val windSpeedKmh: Double,
    val windDirectionDegrees: Int,
    val weatherCode: Int,
)

data class HourlyWeather(
    val time: LocalDateTime,
    val temperatureC: Double,
    val precipitationProbabilityPercent: Int?,
    val weatherCode: Int,
)

data class DailyWeather(
    val date: LocalDate,
    val highC: Double,
    val lowC: Double,
    val precipitationProbabilityPercent: Int?,
    val weatherCode: Int,
)

data class WeatherSnapshot(
    val location: WeatherLocation,
    val current: CurrentWeather,
    val hourly: List<HourlyWeather>,
    val daily: List<DailyWeather>,
)

fun weatherDescription(code: Int): String {
    return when (code) {
        0 -> "Clear"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm hail"
        else -> "Unknown"
    }
}
