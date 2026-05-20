package digital.dutton.essentials.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.Locale
import kotlin.math.max

data class AssistantResult(
    val input: String,
    val title: String,
    val detail: String,
    val confidence: Float,
    val launched: Boolean,
    val error: String? = null
)

private data class AssistantAction(
    val title: String,
    val detail: String,
    val confidence: Float,
    val intent: Intent?,
    val fallbackIntent: Intent? = null
)

private data class ParsedMessage(
    val number: String?,
    val recipientQuery: String?,
    val body: String,
    val isSelf: Boolean
)

data class LaunchableApp(
    val label: String,
    val packageName: String,
    val activityName: String
)

class AssistantEngine(private val context: Context) {
    private val model = LocalIntentModel(context)

    fun execute(input: String): AssistantResult {
        val action = model.resolve(input)
        val launchError = launch(action.intent, action.fallbackIntent)
        return AssistantResult(
            input = input,
            title = action.title,
            detail = action.detail,
            confidence = action.confidence,
            launched = action.intent != null && launchError == null,
            error = launchError
        )
    }

    fun preview(input: String): AssistantResult {
        val action = model.resolve(input)
        return AssistantResult(
            input = input,
            title = action.title,
            detail = action.detail,
            confidence = action.confidence,
            launched = false
        )
    }

    private fun launch(intent: Intent?, fallbackIntent: Intent?): String? {
        if (intent == null) return "Nothing to launch."
        return try {
            context.startActivity(Intent(intent).asNewTask())
            null
        } catch (primary: ActivityNotFoundException) {
            if (fallbackIntent == null) {
                "No installed app can handle that action."
            } else {
                try {
                    context.startActivity(Intent(fallbackIntent).asNewTask())
                    null
                } catch (fallback: ActivityNotFoundException) {
                    "No installed app can handle that action."
                }
            }
        } catch (error: RuntimeException) {
            error.message ?: "The system rejected that action."
        }
    }
}

private class LocalIntentModel(
    private val context: Context
) {
    private val messagesPackage = "digital.dutton.essentials.messages"
    private val appResolver = AppResolver(context)

    fun resolve(input: String): AssistantAction {
        val original = input.trim()
        if (original.isBlank()) {
            return AssistantAction(
                title = "No action",
                detail = "Empty request",
                confidence = 0f,
                intent = null
            )
        }

        val text = original.normalized()

        resolveSettings(text)?.let { return it }
        resolveTimer(text)?.let { return it }
        resolveAlarm(text)?.let { return it }
        resolveCall(text)?.let { return it }
        resolveMessage(text, original)?.let { return it }
        resolveNavigation(text, original)?.let { return it }
        resolveCalendar(text, original)?.let { return it }
        resolveCamera(text)?.let { return it }
        resolveUrl(original)?.let { return it }
        resolveWebSearch(text, original)?.let { return it }
        resolveApp(text)?.let { return it }

        return webSearch(original, confidence = 0.35f)
    }

    private fun resolveSettings(text: String): AssistantAction? {
        val settings = listOf(
            SettingTarget("wifi", "Wi-Fi settings", Settings.ACTION_WIFI_SETTINGS),
            SettingTarget("wi fi", "Wi-Fi settings", Settings.ACTION_WIFI_SETTINGS),
            SettingTarget("bluetooth", "Bluetooth settings", Settings.ACTION_BLUETOOTH_SETTINGS),
            SettingTarget("display", "Display settings", Settings.ACTION_DISPLAY_SETTINGS),
            SettingTarget("screen", "Display settings", Settings.ACTION_DISPLAY_SETTINGS),
            SettingTarget("sound", "Sound settings", Settings.ACTION_SOUND_SETTINGS),
            SettingTarget("volume", "Sound settings", Settings.ACTION_SOUND_SETTINGS),
            SettingTarget("battery", "Battery settings", Settings.ACTION_BATTERY_SAVER_SETTINGS),
            SettingTarget("location", "Location settings", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            SettingTarget("privacy", "Privacy settings", Settings.ACTION_PRIVACY_SETTINGS),
            SettingTarget("security", "Security settings", Settings.ACTION_SECURITY_SETTINGS),
            SettingTarget("apps", "App settings", Settings.ACTION_APPLICATION_SETTINGS),
            SettingTarget("assistant", "Default assistant settings", Settings.ACTION_VOICE_INPUT_SETTINGS)
        )

        val isSettingsRequest = text.containsWord("settings") ||
            text.startsWith("turn on ") ||
            text.startsWith("turn off ") ||
            text.startsWith("enable ") ||
            text.startsWith("disable ")

        if (!isSettingsRequest) return null

        val target = settings.firstOrNull { text.containsWord(it.key) }
            ?: if (text.containsWord("settings")) {
                SettingTarget("settings", "Settings", Settings.ACTION_SETTINGS)
            } else {
                return null
            }

        return AssistantAction(
            title = "Open ${target.label}",
            detail = "System settings",
            confidence = 0.9f,
            intent = Intent(target.action)
        )
    }

    private fun resolveTimer(text: String): AssistantAction? {
        if (!text.containsWord("timer")) return null
        val seconds = parseDurationSeconds(text)
        val intent = if (seconds != null && seconds > 0) {
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        } else {
            Intent(AlarmClock.ACTION_SHOW_TIMERS)
        }
        val detail = if (seconds != null) "Timer for ${formatDuration(seconds)}" else "Clock timers"
        return AssistantAction(
            title = if (seconds != null) "Set timer" else "Open timers",
            detail = detail,
            confidence = 0.86f,
            intent = intent
        )
    }

    private fun resolveAlarm(text: String): AssistantAction? {
        if (!text.containsWord("alarm")) return null
        val time = parseAlarmTime(text)
        val intent = if (time != null) {
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, time.first)
                .putExtra(AlarmClock.EXTRA_MINUTES, time.second)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        } else {
            Intent(AlarmClock.ACTION_SHOW_ALARMS)
        }
        val detail = if (time != null) "Alarm at %02d:%02d".format(time.first, time.second) else "Clock alarms"
        return AssistantAction(
            title = if (time != null) "Set alarm" else "Open alarms",
            detail = detail,
            confidence = 0.85f,
            intent = intent
        )
    }

    private fun resolveCall(text: String): AssistantAction? {
        val match = Regex("""\b(call|dial|phone)\s+(.+)""").find(text) ?: return null
        val target = match.groupValues[2].trim()
        val number = target.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        val uri = if (number.length >= 3) Uri.parse("tel:${Uri.encode(number)}") else Uri.parse("tel:")
        return AssistantAction(
            title = "Dial",
            detail = if (number.isBlank()) "Phone dialer" else number,
            confidence = if (number.isBlank()) 0.55f else 0.82f,
            intent = Intent(Intent.ACTION_DIAL, uri)
        )
    }

    private fun resolveMessage(text: String, original: String): AssistantAction? {
        if (!text.containsWord("text") && !text.containsWord("message") && !text.containsWord("sms")) {
            return null
        }

        parseMessage(original)?.let { message ->
            val ownNumber = if (message.isSelf) ownPhoneNumber() else null
            val number = message.number ?: ownNumber
            val primary = messageIntent(number, message.body, message.recipientQuery).apply {
                if (appResolver.isPackageInstalled(messagesPackage)) setPackage(messagesPackage)
            }
            val fallback = messageIntent(number, message.body, message.recipientQuery)
            val target = when {
                message.isSelf && number != null -> "your number"
                message.isSelf -> "choose yourself"
                number != null -> number
                message.recipientQuery != null -> message.recipientQuery
                else -> "choose recipient"
            }
            return AssistantAction(
                title = "Send message",
                detail = listOfNotNull(target, message.body.takeIf { it.isNotBlank() })
                    .joinToString(": "),
                confidence = 0.82f,
                intent = primary,
                fallbackIntent = fallback
            )
        }

        val primary = messageIntent(number = null, body = "", recipientQuery = null).apply {
            if (appResolver.isPackageInstalled(messagesPackage)) setPackage(messagesPackage)
        }
        return AssistantAction(
            title = "Send message",
            detail = "choose recipient",
            confidence = 0.64f,
            intent = primary,
            fallbackIntent = messageIntent(number = null, body = "", recipientQuery = null)
        )
    }

    private fun parseMessage(original: String): ParsedMessage? {
        val command = Regex(
            """\b(?:send\s+(?:a\s+)?)?(?:text|message|sms)(?:\s+message)?\b""",
            RegexOption.IGNORE_CASE
        ).find(original) ?: return null

        val rawRemainder = original.substring(command.range.last + 1).trim()
        val remainder = rawRemainder
            .replace(Regex("""^(?:to|for)\s+""", RegexOption.IGNORE_CASE), "")
            .trim()

        if (remainder.isBlank()) {
            return ParsedMessage(number = null, recipientQuery = null, body = "", isSelf = false)
        }

        val numberMatch = Regex("""^([+\d][+\d\s().-]{2,})(?:\s+(.+))?$""").find(remainder)
        if (numberMatch != null) {
            val number = numberMatch.groupValues[1].filter { it.isDigit() || it == '+' }
            return ParsedMessage(
                number = number,
                recipientQuery = null,
                body = cleanMessageBody(numberMatch.groupValues.getOrNull(2).orEmpty()),
                isSelf = false
            )
        }

        val selfMatch = Regex(
            """^(?:me|myself|my\s+number|my\s+phone|self)\b(?:\s+(.+))?$""",
            RegexOption.IGNORE_CASE
        ).find(remainder)
        if (selfMatch != null) {
            return ParsedMessage(
                number = null,
                recipientQuery = "me",
                body = cleanMessageBody(selfMatch.groupValues.getOrNull(1).orEmpty()),
                isSelf = true
            )
        }

        val parts = splitRecipientAndBody(remainder)
        return ParsedMessage(
            number = null,
            recipientQuery = parts.first.takeIf { it.isNotBlank() },
            body = parts.second,
            isSelf = false
        )
    }

    private fun splitRecipientAndBody(value: String): Pair<String, String> {
        val divider = Regex(
            """\b(?:saying|that\s+says|with\s+message|with\s+the\s+message|and\s+say)\b""",
            RegexOption.IGNORE_CASE
        ).find(value) ?: return value.trim() to ""

        return value.substring(0, divider.range.first).trim() to
            value.substring(divider.range.last + 1).trim()
    }

    private fun cleanMessageBody(value: String): String {
        return value
            .replace(
                Regex(
                    """^(?:saying|that\s+says|with\s+message|with\s+the\s+message|and\s+say)\s+""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .trim()
    }

    private fun messageIntent(number: String?, body: String, recipientQuery: String?): Intent {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number.orEmpty())}"))
        if (body.isNotBlank()) {
            intent.putExtra("sms_body", body)
            intent.putExtra(Intent.EXTRA_TEXT, body)
        }
        if (!recipientQuery.isNullOrBlank()) {
            intent.putExtra("recipient_query", recipientQuery)
        }
        return intent
    }

    @SuppressLint("MissingPermission")
    private fun ownPhoneNumber(): String? {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val subscriptions = runCatching {
            subscriptionManager?.activeSubscriptionInfoList.orEmpty()
        }.getOrDefault(emptyList())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && subscriptionManager != null) {
            subscriptions.firstNotNullOfOrNull { subscription ->
                runCatching { subscriptionManager.getPhoneNumber(subscription.subscriptionId) }
                    .getOrNull()
                    ?.cleanPhoneNumber()
            }?.let { return it }
        }

        subscriptions.firstNotNullOfOrNull { subscription ->
            @Suppress("DEPRECATION")
            subscription.number?.cleanPhoneNumber()
        }?.let { return it }

        return runCatching {
            context.getSystemService(TelephonyManager::class.java)?.line1Number
        }.getOrNull()?.cleanPhoneNumber()
    }

    private fun resolveNavigation(text: String, original: String): AssistantAction? {
        val command = listOf(
            "navigate to ",
            "directions to ",
            "route to ",
            "take me to ",
            "go to ",
            "map "
        ).firstOrNull { text.startsWith(it) }

        if (command == null && !text.containsWord("map") && !text.containsWord("maps")) return null

        if (command == null) {
            appResolver.launchKnownApp("maps")?.let {
                return AssistantAction(
                    title = "Open Maps",
                    detail = "digital.dutton.essentials.maps",
                    confidence = 0.75f,
                    intent = it
                )
            }
        }

        val destination = command?.let { original.drop(it.length).trim() }.orEmpty()
        if (destination.isBlank()) return null

        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}"))
        val primary = Intent(fallback).apply {
            if (appResolver.isPackageInstalled("digital.dutton.essentials.maps")) {
                setPackage("digital.dutton.essentials.maps")
            }
        }

        return AssistantAction(
            title = "Open map",
            detail = destination,
            confidence = 0.78f,
            intent = primary,
            fallbackIntent = fallback
        )
    }

    private fun resolveCalendar(text: String, original: String): AssistantAction? {
        if (!text.containsWord("calendar") && !text.containsWord("event")) return null
        val isCreate = text.containsWord("add") ||
            text.containsWord("create") ||
            text.containsWord("schedule") ||
            text.containsWord("new")

        if (isCreate) {
            val title = original
                .replace(Regex("""(?i)\b(add|create|schedule|new)\b"""), "")
                .replace(Regex("""(?i)\b(calendar|event)\b"""), "")
                .trim()

            val intent = Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
            if (title.isNotBlank()) intent.putExtra(CalendarContract.Events.TITLE, title)

            return AssistantAction(
                title = "Create event",
                detail = if (title.isBlank()) "Calendar event" else title,
                confidence = 0.72f,
                intent = intent
            )
        }

        appResolver.launchKnownApp("calendar")?.let {
            return AssistantAction(
                title = "Open Calendar",
                detail = "digital.dutton.essentials.calendar",
                confidence = 0.75f,
                intent = it
            )
        }

        return null
    }

    private fun resolveCamera(text: String): AssistantAction? {
        val wantsCamera = text.containsWord("camera") ||
            text.containsWord("photo") ||
            text.containsWord("picture")
        if (!wantsCamera) return null
        return AssistantAction(
            title = "Open camera",
            detail = "Camera capture",
            confidence = 0.72f,
            intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
    }

    private fun resolveUrl(original: String): AssistantAction? {
        val match = Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE).find(original) ?: return null
        val raw = match.value
        val url = if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw"
        return AssistantAction(
            title = "Open link",
            detail = url,
            confidence = 0.92f,
            intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        )
    }

    private fun resolveWebSearch(text: String, original: String): AssistantAction? {
        val prefixes = listOf("search for ", "search ", "look up ", "find ", "web search ")
        val prefix = prefixes.firstOrNull { text.startsWith(it) } ?: return null
        val query = original.drop(prefix.length).trim()
        if (query.isBlank()) return null
        return webSearch(query, confidence = 0.78f)
    }

    private fun resolveApp(text: String): AssistantAction? {
        val appQuery = listOf("open ", "launch ", "start ", "show ")
            .firstOrNull { text.startsWith(it) }
            ?.let { text.drop(it.length).trim() }
            ?: text

        appResolver.launchKnownApp(appQuery)?.let {
            return AssistantAction(
                title = "Open ${appQuery.titleCase()}",
                detail = it.`package` ?: "Installed app",
                confidence = 0.83f,
                intent = it
            )
        }

        val app = appResolver.findApp(appQuery) ?: return null
        return AssistantAction(
            title = "Open ${app.label}",
            detail = app.packageName,
            confidence = appResolver.matchConfidence(appQuery, app.label),
            intent = appResolver.launchIntent(app)
        )
    }

    private fun webSearch(query: String, confidence: Float): AssistantAction {
        val primary = Intent(Intent.ACTION_WEB_SEARCH)
            .putExtra(SearchManager.QUERY, query)
        val fallback = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://duckduckgo.com/?q=${Uri.encode(query)}")
        )
        return AssistantAction(
            title = "Search",
            detail = query,
            confidence = confidence,
            intent = primary,
            fallbackIntent = fallback
        )
    }

    private fun parseDurationSeconds(text: String): Int? {
        val hours = Regex("""(\d+)\s*(hours?|hrs?|h)\b""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d+)\s*(minutes?|mins?|m)\b""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val seconds = Regex("""(\d+)\s*(seconds?|secs?|s)\b""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = hours * 3600 + minutes * 60 + seconds
        if (total > 0) return total

        return Regex("""\b(\d+)\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { it * 60 }
    }

    private fun parseAlarmTime(text: String): Pair<Int, Int>? {
        val match = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""").find(text) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        val marker = match.groupValues.getOrNull(3).orEmpty()

        if (minute !in 0..59) return null
        if (marker == "pm" && hour in 1..11) hour += 12
        if (marker == "am" && hour == 12) hour = 0
        if (hour !in 0..23) return null
        return hour to minute
    }

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return listOfNotNull(
            hours.takeIf { it > 0 }?.let { "$it h" },
            minutes.takeIf { it > 0 }?.let { "$it min" },
            secs.takeIf { it > 0 }?.let { "$it sec" }
        ).joinToString(" ")
    }

    private data class SettingTarget(
        val key: String,
        val label: String,
        val action: String
    )
}

private class AppResolver(private val context: Context) {
    private val knownPackages = mapOf(
        "assistant" to "digital.dutton.essentials.assistant",
        "calendar" to "digital.dutton.essentials.calendar",
        "events" to "digital.dutton.essentials.calendar",
        "maps" to "digital.dutton.essentials.maps",
        "map" to "digital.dutton.essentials.maps",
        "navigation" to "digital.dutton.essentials.maps",
        "messages" to "digital.dutton.essentials.messages",
        "message" to "digital.dutton.essentials.messages",
        "sms" to "digital.dutton.essentials.messages",
        "keyboard" to "digital.dutton.essentials.keyboard",
        "wallet" to "digital.dutton.essentials.wallet",
        "cards" to "digital.dutton.essentials.wallet",
        "vault" to "digital.dutton.essentials.vault",
        "passwords" to "digital.dutton.essentials.vault",
        "password" to "digital.dutton.essentials.vault"
    )

    fun launchKnownApp(query: String): Intent? {
        val normalized = query.normalized()
        val packageName = knownPackages.entries.firstOrNull { normalized.containsWord(it.key) }?.value
            ?: return null
        return context.packageManager.getLaunchIntentForPackage(packageName)?.asNewTask()
    }

    fun isPackageInstalled(packageName: String): Boolean {
        return context.packageManager.getLaunchIntentForPackage(packageName) != null
    }

    fun findApp(query: String): LaunchableApp? {
        val normalized = query.normalized()
        if (normalized.length < 2) return null
        return launchableApps()
            .map { it to matchScore(normalized, it.label.normalized()) }
            .filter { it.second > 0f }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= 0.52f }
            ?.first
    }

    fun matchConfidence(query: String, label: String): Float {
        return max(0.45f, matchScore(query.normalized(), label.normalized()))
    }

    fun launchIntent(app: LaunchableApp): Intent {
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.packageName, app.activityName)
            .asNewTask()
    }

    private fun launchableApps(): List<LaunchableApp> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return context.packageManager.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                val label = runCatching { info.loadLabel(context.packageManager).toString() }.getOrNull()
                    ?: return@mapNotNull null
                LaunchableApp(
                    label = label,
                    packageName = activity.packageName,
                    activityName = activity.name
                )
            }
            .distinctBy { it.packageName to it.activityName }
    }

    private fun matchScore(query: String, label: String): Float {
        if (query == label) return 1f
        if (label.contains(query)) return 0.82f
        if (query.contains(label)) return 0.78f

        val queryTokens = query.split(" ").filter { it.isNotBlank() }.toSet()
        val labelTokens = label.split(" ").filter { it.isNotBlank() }.toSet()
        if (queryTokens.isEmpty() || labelTokens.isEmpty()) return 0f

        val overlap = queryTokens.intersect(labelTokens).size.toFloat()
        return overlap / max(queryTokens.size, labelTokens.size).toFloat()
    }
}

private fun Intent.asNewTask(): Intent = apply {
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

private fun String.normalized(): String {
    return lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9+:.#/ ]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun String.containsWord(word: String): Boolean {
    val escaped = Regex.escape(word.normalized())
    return Regex("""(^|\s)$escaped($|\s)""").containsMatchIn(normalized())
}

private fun String.titleCase(): String {
    return split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
        }
}

private fun String.cleanPhoneNumber(): String? {
    val cleaned = filter { it.isDigit() || it == '+' }
    return cleaned.takeIf { it.count(Char::isDigit) >= 3 }
}
