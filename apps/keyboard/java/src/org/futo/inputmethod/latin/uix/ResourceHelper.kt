package org.futo.inputmethod.latin.uix

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.runBlocking
import org.futo.inputmethod.engine.GlobalIMEMessage
import org.futo.inputmethod.engine.IMEMessage
import org.futo.inputmethod.latin.Dictionary
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.ReadOnlyBinaryDictionary
import org.futo.inputmethod.latin.Subtypes
import org.futo.inputmethod.latin.localeFromString
import org.futo.inputmethod.latin.utils.Dictionaries
import org.futo.voiceinput.shared.BUILTIN_ENGLISH_MODEL
import org.futo.voiceinput.shared.types.ModelFileFile
import org.futo.voiceinput.shared.types.ModelLoader
import java.io.File
import java.util.Locale

data class PersonalWord(
    val word: String,
    val frequency: Int,
    val locale: String?,
    val appId: Int,
    val shortcut: String?
)

enum class FileKind {
    VoiceInput,
    Transformer,
    Dictionary,
    Invalid
}

fun FileKind.preferenceKeyFor(locale: String): Preferences.Key<String> {
    assert(this != FileKind.Invalid)
    val sanitizedLocale = locale.replace("#", "H")
    return stringPreferencesKey("resource_${name}_${sanitizedLocale}")
}

fun FileKind.namePreferenceKeyFor(locale: String): Preferences.Key<String> {
    assert(this != FileKind.Invalid)
    val sanitizedLocale = locale.replace("#", "H")
    return stringPreferencesKey("resourcename_${name}_${sanitizedLocale}")
}

fun FileKind.kindTitle(resources: Resources): String {
    return resources.getString(when(this) {
        FileKind.VoiceInput -> R.string.file_kind_voice_input_model
        FileKind.Transformer -> R.string.file_kind_transformer_model
        FileKind.Dictionary -> R.string.file_kind_dictionary
        FileKind.Invalid -> R.string.file_kind_invalid_file
    })
}

fun FileKind.icon(): Int {
    return when(this) {
        FileKind.VoiceInput -> R.drawable.mic
        FileKind.Transformer -> R.drawable.cpu
        FileKind.Dictionary -> R.drawable.book
        FileKind.Invalid -> R.drawable.close
    }
}

object ResourceHelper {
    val BuiltInVoiceInputFallbacks = mapOf(
        "en" to BUILTIN_ENGLISH_MODEL
    )

    fun findKeyForLocaleAndKind(context: Context, locale: Locale, kind: FileKind): String? {
        val keysToTry = listOf(
            locale.toString(),
            locale.language,
            "${locale.language}_${locale.country.ifEmpty { locale.language }}",
            "${locale.language.lowercase()}_${locale.country.ifEmpty { locale.language }.uppercase()}",
        )

        return keysToTry.firstNotNullOfOrNull { key ->
            context.getSetting(kind.preferenceKeyFor(key), "").ifEmpty { null }?.let { key }
        }
    }

    fun findFileForKind(context: Context, locale: Locale, kind: FileKind): File? {
        val key = findKeyForLocaleAndKind(context, locale, kind) ?: return null
        val settingValue = context.getSetting(kind.preferenceKeyFor(key), "")
        val file = File(context.getExternalFilesDir(null), settingValue)

        if(!file.exists()) {
            return null
        }

        return file
    }

    fun tryFindingVoiceInputModelForLocale(context: Context, locale: Locale): ModelLoader? {
        val file = runBlocking { findFileForKind(context, locale, FileKind.VoiceInput) }
            ?: return BuiltInVoiceInputFallbacks[locale.language]

        return ModelFileFile(R.string.settings_external_model_name, file)
    }

    fun tryOpeningCustomMainDictionaryForLocale(context: Context, locale: Locale): ReadOnlyBinaryDictionary? {
        val file = runBlocking { findFileForKind(context, locale, FileKind.Dictionary) } ?: return null

        return ReadOnlyBinaryDictionary(
            file.absolutePath,
            0,
            file.length(),
            false,
            locale,
            Dictionary.TYPE_MAIN
        )
    }

    fun deleteResourceForLanguage(context: Context, kind: FileKind, locale: Locale) {
        val setting = kind.preferenceKeyFor(locale.toString())
        val value = runBlocking { context.getSetting(setting, "") }
        if(value.isNotBlank()) {
            File(context.getExternalFilesDir(null), value).delete()
        }

        runBlocking { context.setSetting(kind.preferenceKeyFor(locale.toString()), "") }
        runBlocking { context.setSetting(kind.namePreferenceKeyFor(locale.toString()), "") }

        GlobalIMEMessage.tryEmit(IMEMessage.ReloadResources)
    }
}

val ImportedUserDictFilesSetting = SettingsKey(
    stringSetPreferencesKey("imported_user_dict_files"),
    setOf()
)

fun getImportedUserDictFilesForLocale(context: Context, locale: Locale?): List<Pair<File, String>> {
    val setting = context.getSetting(ImportedUserDictFilesSetting)
    return setting.filter {
        locale == null || (localeFromString(it.split(':', limit = 2)[1]).language == locale.language)
    }.map {
        val file = it.split(':', limit = 2)[0]
        val name = file.split(' ', limit = 2)[0]

        File(context.getExternalFilesDir(null), file) to name
    }
}

suspend fun removeImportedUserDictFile(context: Context, value: Pair<File, String>) {
    val setting = context.getSetting(ImportedUserDictFilesSetting).filter {
        !it.startsWith(value.first.name)
    }.toSet()
    value.first.delete()
    context.setSetting(ImportedUserDictFilesSetting.key, setting)
    GlobalIMEMessage.tryEmit(IMEMessage.ReloadResources)
}

object MissingDictionaryHelper {
    sealed class DictCheckResult {
        object CheckFailed : DictCheckResult()
        object DontShowDictNotice : DictCheckResult()
        data class ShowDictNotice(val locale: Locale, val dismissalSetting: SettingsKey<Int>) : DictCheckResult()
    }

    fun checkIfDictInstalled(context: Context): DictCheckResult {
        if(context.isDeviceLocked) return DictCheckResult.CheckFailed

        val locale = Subtypes.getLocale(Subtypes.getActiveSubtype(context))
        val hasImportedDict = ResourceHelper.findKeyForLocaleAndKind(
            context,
            locale,
            FileKind.Dictionary
        ) != null
        val hasBuiltInDict = Dictionaries.getDictionaryIfExists(context, locale, Dictionaries.DictionaryKind.Any) != null

        val langsWithDownloadableDictionaries = setOf(
            "ar", "hy", "as", "bn", "eu", "be", "bg", "ca", "hr", "cs", "da", "nl", "en", "eo", "fi",
            "fr", "gl", "ka", "de", "gom", "el", "gu", "he", "iw", "hi", "hu", "it", "kn", "ks", "lv",
            "lt", "lb", "mai", "ml", "mr", "nb", "or", "pl", "pt", "pa", "ro", "ru", "sa", "sat", "sr",
            "sd", "sl", "es", "sv", "ta", "te", "tok", "tcy", "tr", "uk", "ur", "af", "bn", "bg",
            "id", "kab", "kk", "pms", "sk", "vi", "ja", "zh"
        )

        val undismissableLanguages = setOf("ja", "zh")
        val dismissalSetting = SettingsKey(
            intPreferencesKey("dictionary_notice_dismiss_${locale.language}"),
            0
        )

        if(
            !hasImportedDict &&
            !hasBuiltInDict &&
            langsWithDownloadableDictionaries.contains(locale.language) &&
            (context.getSetting(dismissalSetting) < 15 || undismissableLanguages.contains(locale.language))
        ) {
            return DictCheckResult.ShowDictNotice(locale, dismissalSetting)
        }

        return DictCheckResult.DontShowDictNotice
    }

    class NoDictionaryNotice(
        val dismissalSetting: SettingsKey<Int>,
        val locale: Locale,
        val string: String,
        val resetNotice: () -> Unit
    ) : ImportantNotice {
        @Composable
        override fun getText(): String {
            return string
        }

        override fun onDismiss(context: Context, auto: Boolean) {
            resetNotice()
            context.setSettingBlocking(
                dismissalSetting.key,
                context.getSetting(dismissalSetting) + if(auto) 1 else 5
            )
        }

        override fun onOpen(context: Context) {
            onDismiss(context, false)
        }
    }
}
