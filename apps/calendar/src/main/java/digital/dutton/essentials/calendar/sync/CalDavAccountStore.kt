package digital.dutton.essentials.calendar.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CalDavAccountStore(context: Context) {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    private val passwordVault = CalDavPasswordVault()

    fun listAccounts(): List<CalDavAccount> {
        return rawAccounts().sortedBy { it.displayName.lowercase() }
    }

    fun rawAccounts(): List<CalDavAccount> {
        return preferences.getStringSet(KeyAccountIds, emptySet()).orEmpty()
            .mapNotNull(::getAccount)
    }

    fun getAccount(id: String): CalDavAccount? {
        val prefix = "$KeyAccountPrefix$id."
        val baseUrl = preferences.getString(prefix + KeyBaseUrl, null) ?: return null
        val username = preferences.getString(prefix + KeyUsername, null) ?: return null
        val password = preferences.getString(prefix + KeyPassword, null)
            ?.let(passwordVault::decrypt)
            ?: return null
        val displayName = preferences.getString(prefix + KeyDisplayName, null) ?: return null

        return CalDavAccount(
            id = id,
            baseUrl = baseUrl,
            username = username,
            password = password,
            displayName = displayName,
            lastSyncMillis = preferences.getLong(prefix + KeyLastSyncMillis, MissingTimestamp)
                .takeUnless { it == MissingTimestamp },
            lastError = preferences.getString(prefix + KeyLastError, null),
        )
    }

    fun findAccount(
        baseUrl: String,
        username: String,
    ): CalDavAccount? {
        return rawAccounts().firstOrNull { account ->
            account.baseUrl == baseUrl && account.username == username
        }
    }

    fun upsertAccount(account: CalDavAccount) {
        val currentIds = preferences.getStringSet(KeyAccountIds, emptySet()).orEmpty()
        val prefix = "$KeyAccountPrefix${account.id}."

        preferences.edit()
            .putStringSet(KeyAccountIds, currentIds + account.id)
            .putString(prefix + KeyBaseUrl, account.baseUrl)
            .putString(prefix + KeyUsername, account.username)
            .putString(prefix + KeyPassword, passwordVault.encrypt(account.password))
            .putString(prefix + KeyDisplayName, account.displayName)
            .putNullableLong(prefix + KeyLastSyncMillis, account.lastSyncMillis)
            .putNullableString(prefix + KeyLastError, account.lastError)
            .apply()
    }

    fun removeAccount(id: String) {
        val currentIds = preferences.getStringSet(KeyAccountIds, emptySet()).orEmpty()
        val prefix = "$KeyAccountPrefix$id."

        preferences.edit()
            .putStringSet(KeyAccountIds, currentIds - id)
            .remove(prefix + KeyBaseUrl)
            .remove(prefix + KeyUsername)
            .remove(prefix + KeyPassword)
            .remove(prefix + KeyDisplayName)
            .remove(prefix + KeyLastSyncMillis)
            .remove(prefix + KeyLastError)
            .apply()
    }

    fun listCalendars(): List<CalDavCalendar> {
        return rawCalendars().sortedBy { it.displayName.lowercase() }
    }

    fun rawCalendars(): List<CalDavCalendar> {
        return preferences.getStringSet(KeyCalendarIds, emptySet()).orEmpty()
            .mapNotNull(::getCalendar)
    }

    fun listCalendars(accountId: String): List<CalDavCalendar> {
        return rawCalendars().filter { it.accountId == accountId }
            .sortedBy { it.displayName.lowercase() }
    }

    fun getCalendar(id: String): CalDavCalendar? {
        val prefix = "$KeyCalendarPrefix$id."
        val accountId = preferences.getString(prefix + KeyAccountId, null) ?: return null
        val href = preferences.getString(prefix + KeyHref, null) ?: return null
        val displayName = preferences.getString(prefix + KeyDisplayName, null) ?: return null

        return CalDavCalendar(
            id = id,
            accountId = accountId,
            localCalendarId = preferences.getLong(prefix + KeyLocalCalendarId, MissingCalendarId)
                .takeUnless { it == MissingCalendarId },
            href = href,
            displayName = displayName,
            color = preferences.getInt(prefix + KeyColor, MissingColor)
                .takeUnless { it == MissingColor },
            supportsEvents = preferences.getBoolean(prefix + KeySupportsEvents, true),
            supportsTasks = preferences.getBoolean(prefix + KeySupportsTasks, false),
            syncToken = preferences.getString(prefix + KeySyncToken, null),
            lastSyncMillis = preferences.getLong(prefix + KeyLastSyncMillis, MissingTimestamp)
                .takeUnless { it == MissingTimestamp },
            lastError = preferences.getString(prefix + KeyLastError, null),
        )
    }

    fun findCalendar(
        accountId: String,
        href: String,
    ): CalDavCalendar? {
        return rawCalendars().firstOrNull { calendar ->
            calendar.accountId == accountId && calendar.href == href
        }
    }

    fun upsertCalendar(calendar: CalDavCalendar) {
        val currentIds = preferences.getStringSet(KeyCalendarIds, emptySet()).orEmpty()
        val prefix = "$KeyCalendarPrefix${calendar.id}."

        preferences.edit()
            .putStringSet(KeyCalendarIds, currentIds + calendar.id)
            .putString(prefix + KeyAccountId, calendar.accountId)
            .putNullableLong(prefix + KeyLocalCalendarId, calendar.localCalendarId)
            .putString(prefix + KeyHref, calendar.href)
            .putString(prefix + KeyDisplayName, calendar.displayName)
            .putNullableInt(prefix + KeyColor, calendar.color)
            .putBoolean(prefix + KeySupportsEvents, calendar.supportsEvents)
            .putBoolean(prefix + KeySupportsTasks, calendar.supportsTasks)
            .putNullableString(prefix + KeySyncToken, calendar.syncToken)
            .putNullableLong(prefix + KeyLastSyncMillis, calendar.lastSyncMillis)
            .putNullableString(prefix + KeyLastError, calendar.lastError)
            .apply()
    }

    fun removeCalendar(id: String) {
        val currentIds = preferences.getStringSet(KeyCalendarIds, emptySet()).orEmpty()
        val prefix = "$KeyCalendarPrefix$id."

        preferences.edit()
            .putStringSet(KeyCalendarIds, currentIds - id)
            .remove(prefix + KeyAccountId)
            .remove(prefix + KeyLocalCalendarId)
            .remove(prefix + KeyHref)
            .remove(prefix + KeyDisplayName)
            .remove(prefix + KeyColor)
            .remove(prefix + KeySupportsEvents)
            .remove(prefix + KeySupportsTasks)
            .remove(prefix + KeySyncToken)
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

    private fun android.content.SharedPreferences.Editor.putNullableInt(
        key: String,
        value: Int?,
    ): android.content.SharedPreferences.Editor {
        return if (value == null) remove(key) else putInt(key, value)
    }

    private companion object {
        const val PreferencesName = "caldav_accounts"
        const val KeyAccountIds = "accountIds"
        const val KeyCalendarIds = "calendarIds"
        const val KeyAccountPrefix = "account."
        const val KeyCalendarPrefix = "calendar."
        const val KeyAccountId = "accountId"
        const val KeyBaseUrl = "baseUrl"
        const val KeyUsername = "username"
        const val KeyPassword = "password"
        const val KeyDisplayName = "displayName"
        const val KeyLocalCalendarId = "localCalendarId"
        const val KeyHref = "href"
        const val KeyColor = "color"
        const val KeySupportsEvents = "supportsEvents"
        const val KeySupportsTasks = "supportsTasks"
        const val KeySyncToken = "syncToken"
        const val KeyLastSyncMillis = "lastSyncMillis"
        const val KeyLastError = "lastError"
        const val MissingCalendarId = -1L
        const val MissingTimestamp = -1L
        const val MissingColor = Int.MIN_VALUE
    }
}

private class CalDavPasswordVault {
    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(
            cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        return "$EncryptedPrefix$iv:$encrypted"
    }

    fun decrypt(value: String): String {
        if (!value.startsWith(EncryptedPrefix)) return value

        val payload = value.removePrefix(EncryptedPrefix)
        val separator = payload.indexOf(':')
        if (separator <= 0) return ""

        val iv = Base64.decode(payload.substring(0, separator), Base64.NO_WRAP)
        val encrypted = Base64.decode(payload.substring(separator + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GcmTagBits, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        (keyStore.getKey(KeyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
        generator.init(
            KeyGenParameterSpec.Builder(
                KeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val AndroidKeyStore = "AndroidKeyStore"
        const val KeyAlias = "essentials_calendar_caldav_passwords"
        const val Transformation = "AES/GCM/NoPadding"
        const val EncryptedPrefix = "v1:"
        const val GcmTagBits = 128
    }
}
