package digital.dutton.essentials.calendar.provider

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.CalendarContract
import digital.dutton.essentials.locations.GeoPoint

data class StoredEventLocation(
    val point: GeoPoint,
    val name: String?,
    val mapId: String?,
)

private const val PreferencesName = "calendar_event_locations"
private const val PropertyLatitude = "digital.dutton.essentials.calendar.location.latitude"
private const val PropertyLongitude = "digital.dutton.essentials.calendar.location.longitude"
private const val PropertyName = "digital.dutton.essentials.calendar.location.name"
private const val PropertyMapId = "digital.dutton.essentials.calendar.location.mapId"

private val PropertyNames = listOf(PropertyLatitude, PropertyLongitude, PropertyName, PropertyMapId)

private val ExtendedPropertyProjection = arrayOf(
    CalendarContract.ExtendedProperties.EVENT_ID,
    CalendarContract.ExtendedProperties.NAME,
    CalendarContract.ExtendedProperties.VALUE,
)

class CalendarEventLocationStore(
    private val context: Context,
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    fun list(eventIds: Set<Long>): Map<Long, StoredEventLocation> {
        if (eventIds.isEmpty()) return emptyMap()

        val storedLocations = mutableMapOf<Long, StoredEventLocation>()
        eventIds.forEach { eventId ->
            preferences.getStoredLocation(eventId)?.let { storedLocations[eventId] = it }
        }

        val placeholders = eventIds.joinToString(",") { "?" }
        val selection = "${CalendarContract.ExtendedProperties.EVENT_ID} IN ($placeholders) AND " +
            "${CalendarContract.ExtendedProperties.NAME} IN (?,?,?,?)"
        val selectionArgs = eventIds.map { it.toString() } + PropertyNames
        val valuesByEvent = mutableMapOf<Long, MutableMap<String, String>>()

        runCatching {
            context.contentResolver.query(
                CalendarContract.ExtendedProperties.CONTENT_URI,
                ExtendedPropertyProjection,
                selection,
                selectionArgs.toTypedArray(),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val eventId =
                        cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.ExtendedProperties.EVENT_ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.ExtendedProperties.NAME))
                    val value = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.ExtendedProperties.VALUE))
                    valuesByEvent.getOrPut(eventId) { mutableMapOf() }[name] = value
                }
            }
        }

        valuesByEvent.mapNotNull { (eventId, properties) ->
            val latitude = properties[PropertyLatitude]?.toDoubleOrNull() ?: return@mapNotNull null
            val longitude = properties[PropertyLongitude]?.toDoubleOrNull() ?: return@mapNotNull null
            val point = runCatching { GeoPoint(latitude, longitude) }.getOrNull() ?: return@mapNotNull null
            eventId to StoredEventLocation(
                point = point,
                name = properties[PropertyName]?.takeIf { it.isNotBlank() },
                mapId = properties[PropertyMapId]?.takeIf { it.isNotBlank() },
            )
        }.forEach { (eventId, location) -> storedLocations[eventId] = location }

        return storedLocations
    }

    fun put(
        eventId: Long,
        location: StoredEventLocation?,
    ) {
        if (location == null) {
            delete(eventId)
            return
        }

        preferences.edit()
            .putString(eventId.key(PropertyLatitude), location.point.latitude.toString())
            .putString(eventId.key(PropertyLongitude), location.point.longitude.toString())
            .putNullableString(eventId.key(PropertyName), location.name)
            .putNullableString(eventId.key(PropertyMapId), location.mapId)
            .apply()

        runCatching {
            upsert(eventId, PropertyLatitude, location.point.latitude.toString())
            upsert(eventId, PropertyLongitude, location.point.longitude.toString())
            upsert(eventId, PropertyName, location.name)
            upsert(eventId, PropertyMapId, location.mapId)
        }
    }

    fun delete(eventId: Long) {
        preferences.edit()
            .remove(eventId.key(PropertyLatitude))
            .remove(eventId.key(PropertyLongitude))
            .remove(eventId.key(PropertyName))
            .remove(eventId.key(PropertyMapId))
            .apply()

        runCatching {
            context.contentResolver.delete(
                CalendarContract.ExtendedProperties.CONTENT_URI.asLocationMetadataSyncAdapter(),
                "${CalendarContract.ExtendedProperties.EVENT_ID} = ? AND " +
                    "${CalendarContract.ExtendedProperties.NAME} IN (?,?,?,?)",
                (listOf(eventId.toString()) + PropertyNames).toTypedArray(),
            )
        }
    }

    private fun upsert(
        eventId: Long,
        name: String,
        value: String?,
    ) {
        if (value.isNullOrBlank()) {
            context.contentResolver.delete(
                CalendarContract.ExtendedProperties.CONTENT_URI.asLocationMetadataSyncAdapter(),
                "${CalendarContract.ExtendedProperties.EVENT_ID} = ? AND ${CalendarContract.ExtendedProperties.NAME} = ?",
                arrayOf(eventId.toString(), name),
            )
            return
        }

        val values = ContentValues().apply {
            put(CalendarContract.ExtendedProperties.EVENT_ID, eventId)
            put(CalendarContract.ExtendedProperties.NAME, name)
            put(CalendarContract.ExtendedProperties.VALUE, value)
        }
        val rows = context.contentResolver.update(
            CalendarContract.ExtendedProperties.CONTENT_URI.asLocationMetadataSyncAdapter(),
            values,
            "${CalendarContract.ExtendedProperties.EVENT_ID} = ? AND ${CalendarContract.ExtendedProperties.NAME} = ?",
            arrayOf(eventId.toString(), name),
        )

        if (rows == 0) {
            context.contentResolver.insert(
                CalendarContract.ExtendedProperties.CONTENT_URI.asLocationMetadataSyncAdapter(),
                values,
            )
        }
    }
}

private fun SharedPreferences.getStoredLocation(eventId: Long): StoredEventLocation? {
    val latitude = getString(eventId.key(PropertyLatitude), null)
        ?.toDoubleOrNull()
        ?: return null
    val longitude = getString(eventId.key(PropertyLongitude), null)
        ?.toDoubleOrNull()
        ?: return null
    val point = runCatching { GeoPoint(latitude, longitude) }.getOrNull() ?: return null
    return StoredEventLocation(
        point = point,
        name = getString(eventId.key(PropertyName), null)
            ?.takeIf { it.isNotBlank() },
        mapId = getString(eventId.key(PropertyMapId), null)
            ?.takeIf { it.isNotBlank() },
    )
}

private fun SharedPreferences.Editor.putNullableString(
    key: String,
    value: String?,
): SharedPreferences.Editor {
    return if (value.isNullOrBlank()) remove(key) else putString(key, value)
}

private fun Long.key(name: String): String = "$this.$name"

private fun Uri.asLocationMetadataSyncAdapter(): Uri {
    return buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LocationMetadataAccountName)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, LocationMetadataAccountType)
        .build()
}

private const val LocationMetadataAccountName = "Essentials calendar location metadata"
private const val LocationMetadataAccountType = "digital.dutton.essentials.calendar.location"
