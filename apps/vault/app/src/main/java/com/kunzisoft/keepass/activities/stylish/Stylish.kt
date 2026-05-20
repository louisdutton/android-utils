/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.activities.stylish

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StyleRes
import com.google.android.material.color.DynamicColors
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.settings.PreferencesUtil

/**
 * Class that provides functions to retrieve and assign a theme to a module
 */
object Stylish {

    /**
     * Initialize the class with a theme preference
     * @param context Context to retrieve the theme preference
     */
    fun load(context: Context) {
        // Vault always follows the system Material theme; this remains as a compatibility hook.
    }

    fun retrieveEquivalentSystemStyle(context: Context, styleString: String?): String {
        return if (isSystemNight(context)) {
            context.getString(R.string.list_style_name_night)
        } else {
            context.getString(R.string.list_style_name_light)
        }
    }

    fun retrieveEquivalentLightStyle(context: Context, styleString: String): String {
        return defaultStyle(context)
    }

    fun defaultStyle(context: Context): String {
        return context.getString(R.string.list_style_name_light)
    }

    /**
     * Assign the style to the class attribute
     * @param styleString Style id String
     */
    fun assignStyle(context: Context, styleString: String) {
        load(context)
    }

    fun isDynamic(context: Context): Boolean {
        return DynamicColors.isDynamicColorAvailable()
    }

    fun isPureBlackOledActive(context: Context): Boolean {
        return PreferencesUtil.usePureBlackOled(context) && isSystemNight(context)
    }

    /**
     * Function that returns the current id of the style selected in the preference
     * @param context Context to retrieve the id
     * @return Id of the style
     */
    @StyleRes
    fun getThemeId(context: Context): Int {
        return R.style.KeepassDXStyle_System
    }

    private fun isSystemNight(context: Context): Boolean {
        return context.resources.configuration.uiMode.and(Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }
}
