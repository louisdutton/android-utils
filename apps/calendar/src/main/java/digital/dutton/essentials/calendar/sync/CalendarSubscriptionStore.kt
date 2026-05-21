package digital.dutton.essentials.calendar.sync

import android.content.Context

class CalendarSubscriptionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    fun list(): List<CalendarSubscription> {
        return preferences.getStringSet(KeyIds, emptySet()).orEmpty()
            .mapNotNull(::get)
            .sortedBy { it.displayName.lowercase() }
    }

    fun get(id: String): CalendarSubscription? {
        val prefix = "$KeySubscriptionPrefix$id."
        val url = preferences.getString(prefix + KeyUrl, null) ?: return null
        val displayName = preferences.getString(prefix + KeyDisplayName, null) ?: return null
        val calendarId = preferences.getLong(prefix + KeyCalendarId, MissingCalendarId)
            .takeUnless { it == MissingCalendarId }
            ?: return null

        return CalendarSubscription(
            id = id,
            url = url,
            displayName = displayName,
            calendarId = calendarId,
            lastEtag = preferences.getString(prefix + KeyLastEtag, null),
            lastModified = preferences.getString(prefix + KeyLastModified, null),
            lastSyncMillis = preferences.getLong(prefix + KeyLastSyncMillis, MissingTimestamp)
                .takeUnless { it == MissingTimestamp },
            lastError = preferences.getString(prefix + KeyLastError, null),
        )
    }

    fun upsert(subscription: CalendarSubscription) {
        val currentIds = preferences.getStringSet(KeyIds, emptySet()).orEmpty()
        val prefix = "$KeySubscriptionPrefix${subscription.id}."

        preferences.edit()
            .putStringSet(KeyIds, currentIds + subscription.id)
            .putString(prefix + KeyUrl, subscription.url)
            .putString(prefix + KeyDisplayName, subscription.displayName)
            .putLong(prefix + KeyCalendarId, subscription.calendarId)
            .putNullableString(prefix + KeyLastEtag, subscription.lastEtag)
            .putNullableString(prefix + KeyLastModified, subscription.lastModified)
            .putNullableLong(prefix + KeyLastSyncMillis, subscription.lastSyncMillis)
            .putNullableString(prefix + KeyLastError, subscription.lastError)
            .apply()
    }

    fun remove(id: String) {
        val currentIds = preferences.getStringSet(KeyIds, emptySet()).orEmpty()
        val prefix = "$KeySubscriptionPrefix$id."

        preferences.edit()
            .putStringSet(KeyIds, currentIds - id)
            .remove(prefix + KeyUrl)
            .remove(prefix + KeyDisplayName)
            .remove(prefix + KeyCalendarId)
            .remove(prefix + KeyLastEtag)
            .remove(prefix + KeyLastModified)
            .remove(prefix + KeyLastSyncMillis)
            .remove(prefix + KeyLastError)
            .apply()
    }

    private fun android.content.SharedPreferences.Editor.putNullableString(
        key: String,
        value: String?,
    ): android.content.SharedPreferences.Editor {
        return if (value == null) remove(key) else putString(key, value)
    }

    private fun android.content.SharedPreferences.Editor.putNullableLong(
        key: String,
        value: Long?,
    ): android.content.SharedPreferences.Editor {
        return if (value == null) remove(key) else putLong(key, value)
    }

    private companion object {
        const val PreferencesName = "calendar_subscriptions"
        const val KeyIds = "ids"
        const val KeySubscriptionPrefix = "subscription."
        const val KeyUrl = "url"
        const val KeyDisplayName = "displayName"
        const val KeyCalendarId = "calendarId"
        const val KeyLastEtag = "lastEtag"
        const val KeyLastModified = "lastModified"
        const val KeyLastSyncMillis = "lastSyncMillis"
        const val KeyLastError = "lastError"
        const val MissingCalendarId = -1L
        const val MissingTimestamp = -1L
    }
}
