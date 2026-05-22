package digital.dutton.essentials.finance.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class FinanceConnectionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    private val secureStrings = SecureStringVault()

    fun getOctopusCredentials(): OctopusCredentials? {
        val accountNumber = preferences.getString(KeyOctopusAccountNumber, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val apiKey = preferences.getString(KeyOctopusApiKey, null)
            ?.let { encrypted -> runCatching { secureStrings.decrypt(encrypted) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return OctopusCredentials(
            apiKey = apiKey,
            accountNumber = accountNumber,
        )
    }

    fun saveOctopusCredentials(credentials: OctopusCredentials) {
        preferences.edit()
            .putString(KeyOctopusAccountNumber, credentials.accountNumber)
            .putString(KeyOctopusApiKey, secureStrings.encrypt(credentials.apiKey))
            .apply()
    }

    fun clearOctopusCredentials() {
        preferences.edit()
            .remove(KeyOctopusAccountNumber)
            .remove(KeyOctopusApiKey)
            .remove(KeyOctopusLastRefreshMillis)
            .apply()
    }

    fun setOctopusLastRefreshMillis(value: Long?) {
        preferences.edit()
            .putNullableLong(KeyOctopusLastRefreshMillis, value)
            .apply()
    }

    fun getOctopusLastRefreshMillis(): Long? {
        return preferences.getLong(KeyOctopusLastRefreshMillis, MissingTimestamp)
            .takeUnless { it == MissingTimestamp }
    }

    private fun android.content.SharedPreferences.Editor.putNullableLong(
        key: String,
        value: Long?,
    ): android.content.SharedPreferences.Editor {
        return if (value == null) remove(key) else putLong(key, value)
    }

    private companion object {
        const val PreferencesName = "finance_connections"
        const val KeyOctopusAccountNumber = "octopus.accountNumber"
        const val KeyOctopusApiKey = "octopus.apiKey"
        const val KeyOctopusLastRefreshMillis = "octopus.lastRefreshMillis"
        const val MissingTimestamp = -1L
    }
}

private class SecureStringVault {
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
        val parts = value.removePrefix(EncryptedPrefix).split(":", limit = 2)
        require(parts.size == 2) { "Invalid encrypted value." }

        val cipher = Cipher.getInstance(Transformation)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GcmTagBits, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        val decrypted = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
        return decrypted.toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        val existing = keyStore.getEntry(KeyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
        val spec = KeyGenParameterSpec.Builder(
            KeyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private companion object {
        const val AndroidKeyStore = "AndroidKeyStore"
        const val KeyAlias = "finance_connection_secrets"
        const val Transformation = "AES/GCM/NoPadding"
        const val EncryptedPrefix = "enc:v1:"
        const val GcmTagBits = 128
    }
}
