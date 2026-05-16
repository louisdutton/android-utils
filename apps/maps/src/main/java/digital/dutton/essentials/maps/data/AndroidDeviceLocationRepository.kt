package digital.dutton.essentials.maps.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import digital.dutton.essentials.locations.GeoPoint
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidDeviceLocationRepository(
    private val context: Context,
) {
    private val locationManager = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): GeoPoint? {
        if (!hasPermission()) return null

        val provider = preferredProvider() ?: return lastKnownLocation()
        val cancellationSignal = CancellationSignal()

        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                cancellationSignal.cancel()
            }

            runCatching {
                locationManager.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    context.mainExecutor,
                ) { location ->
                    continuation.resume(location?.toGeoPoint() ?: lastKnownLocation())
                }
            }.onFailure {
                continuation.resume(lastKnownLocation())
            }
        }
    }

    private fun preferredProvider(): String? {
        return listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        ).firstOrNull { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): GeoPoint? {
        if (!hasPermission()) return null

        return listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        ).firstNotNullOfOrNull { provider ->
            runCatching {
                locationManager.getLastKnownLocation(provider)?.toGeoPoint()
            }.getOrNull()
        }
    }
}

private fun Location.toGeoPoint(): GeoPoint {
    return GeoPoint(latitude = latitude, longitude = longitude)
}
