package digital.dutton.essentials.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class CurrentLocationProvider(private val context: Context) {
    private val locationManager = context.getSystemService(LocationManager::class.java)

    fun hasLocationPermission(): Boolean {
        return hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    suspend fun currentWeatherLocation(): WeatherLocation? {
        val location = currentLocation() ?: newestLastKnownLocation() ?: return null
        return WeatherLocation(
            id = CurrentLocationId,
            name = "Current location",
            country = "",
            adminArea = null,
            latitude = location.latitude,
            longitude = location.longitude,
            timezone = null,
            isCurrentLocation = true,
        )
    }

    private suspend fun currentLocation(): Location? {
        for (provider in enabledProviders()) {
            val location = withTimeoutOrNull(CurrentLocationTimeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val cancellationSignal = CancellationSignal()
                    continuation.invokeOnCancellation { cancellationSignal.cancel() }
                    runCatching {
                        locationManager.getCurrentLocation(
                            provider,
                            cancellationSignal,
                            ContextCompat.getMainExecutor(context),
                        ) { location ->
                            if (continuation.isActive) continuation.resume(location)
                        }
                    }.onFailure {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
            if (location != null) return location
        }
        return null
    }

    private fun newestLastKnownLocation(): Location? {
        return enabledProviders()
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun enabledProviders(): List<String> {
        val hasFine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        return buildList {
            add(LocationManager.NETWORK_PROVIDER)
            if (hasFine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }.filter { provider ->
            provider == LocationManager.PASSIVE_PROVIDER ||
                runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val CurrentLocationId = "current-location"
        const val CurrentLocationTimeoutMillis = 8_000L
    }
}
