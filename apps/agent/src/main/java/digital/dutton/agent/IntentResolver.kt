package digital.dutton.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Settings

data class AppIntent(
    val name: String,
    val packageName: String,
    val activityName: String
)

class IntentResolver(private val context: Context) {

    fun getLaunchableApps(): List<AppIntent> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)

        return apps.mapNotNull { resolveInfo ->
            val appName = resolveInfo.loadLabel(pm).toString()
            val packageName = resolveInfo.activityInfo.packageName
            val activityName = resolveInfo.activityInfo.name

            AppIntent(appName, packageName, activityName)
        }.sortedBy { it.name.lowercase() }
    }

    fun formatAppsForPrompt(apps: List<AppIntent>): String {
        return apps.joinToString("\n") { "- ${it.name}" }
    }

    fun createLaunchIntent(app: AppIntent): Intent {
        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(app.packageName, app.activityName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun findAppByName(apps: List<AppIntent>, name: String): AppIntent? {
        val normalizedName = name.lowercase().trim()
        return apps.find { it.name.lowercase() == normalizedName }
            ?: apps.find { it.name.lowercase().contains(normalizedName) }
            ?: apps.find { normalizedName.contains(it.name.lowercase()) }
    }

    // Common system intents
    fun getSystemIntent(action: String): Intent? {
        return when (action.lowercase()) {
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            else -> null
        }?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}
