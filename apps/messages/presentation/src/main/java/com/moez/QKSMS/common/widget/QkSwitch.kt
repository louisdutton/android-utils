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
package dev.octoshrimpy.quik.common.widget

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import androidx.appcompat.widget.SwitchCompat
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.common.util.extensions.withAlpha
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.util.Preferences
import com.google.android.material.R as MaterialR
import javax.inject.Inject

class QkSwitch @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : SwitchCompat(context, attrs) {

    @Inject lateinit var prefs: Preferences

    init {
        if (!isInEditMode) {
            appComponent.inject(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (!isInEditMode) {
            val primary = context.resolveThemeColor(androidx.appcompat.R.attr.colorPrimary)
            val onSurface = context.resolveThemeColor(MaterialR.attr.colorOnSurface)
            val outline = context.resolveThemeColor(MaterialR.attr.colorOutline)
            val surfaceContainerHighest = context.resolveThemeColor(MaterialR.attr.colorSurfaceContainerHighest)
            val states = arrayOf(
                    intArrayOf(-android.R.attr.state_enabled),
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf())

            thumbTintList = ColorStateList(states, intArrayOf(
                    onSurface.withAlpha(0x61),
                    primary,
                    outline))

            trackTintList = ColorStateList(states, intArrayOf(
                    surfaceContainerHighest.withAlpha(0x61),
                    primary.withAlpha(0x52),
                    surfaceContainerHighest))
        }
    }
}
