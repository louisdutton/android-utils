package digital.dutton.essentials.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class CurrentLocationProvider(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(ReverseGeocodeTimeoutMillis, TimeUnit.MILLISECONDS)
        .build(),
) {
    private val locationManager = context.getSystemService(LocationManager::class.java)

    fun hasLocationPermission(): Boolean {
        return hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    suspend fun currentWeatherLocation(): WeatherLocation? {
        val lastKnown = newestLastKnownLocation()
        val location = lastKnown?.takeIf { it.isRecent() } ?: currentLocation() ?: lastKnown ?: return null
        val label = reverseLocationLabel(location)
            ?: reverseLocationLabelFromOpenStreetMap(location)
            ?: location.coordinateLabel()
        return WeatherLocation(
            id = CurrentLocationId,
            name = "Current location",
            country = "",
            adminArea = label,
            latitude = location.latitude,
            longitude = location.longitude,
            timezone = null,
            isCurrentLocation = true,
        )
    }

    private suspend fun currentLocation(): Location? {
        for (provider in liveProviders()) {
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

    @Suppress("DEPRECATION")
    private fun reverseLocationLabel(location: Location): String? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            val address = Geocoder(context, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?: return null
            listOfNotNull(
                address.locality,
                address.adminArea,
                address.countryName,
            )
                .distinct()
                .joinToString(", ")
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun reverseLocationLabelFromOpenStreetMap(location: Location): String? {
        val locale = Locale.getDefault()
        val url = "https://nominatim.openstreetmap.org/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("lat", location.latitude.toString())
            .addQueryParameter("lon", location.longitude.toString())
            .addQueryParameter("zoom", "14")
            .addQueryParameter("addressdetails", "1")
            .addQueryParameter("accept-language", locale.toLanguageTag())
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GrapheneOS-Essentials-Weather/0.1")
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                val address = JSONObject(body).optJSONObject("address") ?: return null
                address.locationLabel()
            }
        }.getOrNull()
    }

    private fun liveProviders(): List<String> {
        val hasFine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        return buildList {
            add(LocationManager.NETWORK_PROVIDER)
            if (hasFine) add(LocationManager.GPS_PROVIDER)
        }.filter { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }
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

    private fun Location.isRecent(): Boolean {
        return System.currentTimeMillis() - time <= RecentLocationMaxAgeMillis
    }

    private fun Location.coordinateLabel(): String {
        return "${latitude.coordinatePart("N", "S")}, ${longitude.coordinatePart("E", "W")}"
    }

    private fun Double.coordinatePart(
        positiveSuffix: String,
        negativeSuffix: String,
    ): String {
        val suffix = if (this >= 0.0) positiveSuffix else negativeSuffix
        return String.format(Locale.US, "%.3f\u00B0%s", kotlin.math.abs(this), suffix)
    }

    private companion object {
        const val CurrentLocationId = "current-location"
        const val CurrentLocationTimeoutMillis = 2_500L
        const val RecentLocationMaxAgeMillis = 3 * 60 * 60 * 1_000L
        const val ReverseGeocodeTimeoutMillis = 2_500L
    }
}

private fun JSONObject.locationLabel(): String? {
    val locality = firstNonBlank(
        "city",
        "town",
        "village",
        "hamlet",
        "municipality",
        "suburb",
        "county",
    ) ?: return null
    val region = optString("state").takeIf { it.isNotBlank() && it != locality }
    val country = optString("country").takeIf { it.isNotBlank() && it != locality && it != region }
    return listOfNotNull(locality, region, country).joinToString(", ")
}

private fun JSONObject.firstNonBlank(vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key -> optString(key).takeIf { it.isNotBlank() } }
}
