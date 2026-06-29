package digital.dutton.essentials.weather

import android.content.res.Configuration
import android.icu.util.LocaleData
import android.icu.util.ULocale
import java.util.Locale

data class WeatherUnits(
    val locale: Locale,
    val temperatureUnit: TemperatureUnit,
    val windUnit: WindUnit,
    val precipitationUnit: PrecipitationUnit,
) {
    companion object {
        fun from(configuration: Configuration): WeatherUnits {
            val locale = configuration.locales[0] ?: Locale.getDefault()
            val measurementSystem = LocaleData.getMeasurementSystem(ULocale.forLocale(locale))
            return when (measurementSystem) {
                LocaleData.MeasurementSystem.US -> WeatherUnits(
                    locale = locale,
                    temperatureUnit = TemperatureUnit.Fahrenheit,
                    windUnit = WindUnit.Mph,
                    precipitationUnit = PrecipitationUnit.Inches,
                )
                LocaleData.MeasurementSystem.UK -> WeatherUnits(
                    locale = locale,
                    temperatureUnit = TemperatureUnit.Celsius,
                    windUnit = WindUnit.Mph,
                    precipitationUnit = PrecipitationUnit.Millimeters,
                )
                else -> WeatherUnits(
                    locale = locale,
                    temperatureUnit = TemperatureUnit.Celsius,
                    windUnit = WindUnit.Kmh,
                    precipitationUnit = PrecipitationUnit.Millimeters,
                )
            }
        }
    }
}

enum class TemperatureUnit {
    Celsius,
    Fahrenheit,
}

enum class WindUnit {
    Kmh,
    Mph,
}

enum class PrecipitationUnit {
    Millimeters,
    Inches,
}
