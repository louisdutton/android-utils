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
package dev.octoshrimpy.quik.feature.settings

import dev.octoshrimpy.quik.repository.SyncRepository

data class SettingsState(
    val autoEmojiEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val deliveryEnabled: Boolean = false,
    val unreadAtTopEnabled: Boolean = false,
    val signature: String = "",
    val splitSmsEnabled: Boolean = false,
    val stripUnicodeEnabled: Boolean = false,
    val mobileOnly: Boolean = false,
    val longAsMms: Boolean = false,
    val maxMmsSizeSummary: String = "100KB",
    val maxMmsSizeId: Int = 100,
    val messageLinkHandlingSummary: String = "Ask before opening",
    val messageLinkHandlingId: Int = 2,
    val disableScreenshotsEnabled: Boolean = false,
    val syncProgress: SyncRepository.SyncProgress = SyncRepository.SyncProgress.Idle
)
