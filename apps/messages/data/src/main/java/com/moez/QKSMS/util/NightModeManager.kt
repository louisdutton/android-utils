/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import dev.octoshrimpy.quik.database.ScheduledMessageDao
import dev.octoshrimpy.quik.manager.WidgetManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

@Singleton
class NightModeManager @Inject constructor(
    private val context: Context,
    private val scheduledMessageDao: ScheduledMessageDao,
    private val widgetManager: WidgetManager
) {

    fun updateCurrentTheme() {
        cancelLegacyNightModeAlarms()
        cancelLegacyScheduledMessageAlarms()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        widgetManager.updateTheme()
    }

    private fun cancelLegacyNightModeAlarms() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val component = ComponentName(context.packageName, "dev.octoshrimpy.quik.receiver.NightModeReceiver")

        listOf(0, 1).forEach { requestCode ->
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent().setComponent(component),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
            )
            pendingIntent?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }
    }

    private fun cancelLegacyScheduledMessageAlarms() {
        thread(name = "legacy-scheduled-message-cleanup", isDaemon = true) {
            val ids = tryOrNull { scheduledMessageDao.ids() }.orEmpty()
            ids.forEach { id ->
                cancelLegacyReceiverAlarm(
                    "dev.octoshrimpy.quik.receiver.SendScheduledMessageReceiver",
                    id.toInt()
                )
            }
            if (ids.isNotEmpty()) {
                tryOrNull { scheduledMessageDao.delete(ids) }
            }
        }
    }

    private fun cancelLegacyReceiverAlarm(className: String, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent().setComponent(ComponentName(context.packageName, className)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

}
