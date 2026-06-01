package digital.dutton.essentials.calendar.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import digital.dutton.essentials.calendar.MainActivity
import digital.dutton.essentials.calendar.R

class AgendaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(appWidgetId, buildWidgetViews(context, appWidgetId))
        }
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_agenda_list)
    }

    companion object {
        fun updateAll(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, AgendaWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(component)
            if (appWidgetIds.isNotEmpty()) {
                AgendaWidgetProvider().onUpdate(context, appWidgetManager, appWidgetIds)
            }
        }

        private fun buildWidgetViews(
            context: Context,
            appWidgetId: Int,
        ): RemoteViews {
            val adapterIntent = Intent(context, AgendaWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            val openIntent = Intent(context, MainActivity::class.java)
            val openPendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            return RemoteViews(context.packageName, R.layout.widget_agenda).apply {
                setRemoteAdapter(R.id.widget_agenda_list, adapterIntent)
                setEmptyView(R.id.widget_agenda_list, R.id.widget_empty)
                setOnClickPendingIntent(R.id.widget_root, openPendingIntent)
                setPendingIntentTemplate(R.id.widget_agenda_list, openPendingIntent)
            }
        }
    }
}
