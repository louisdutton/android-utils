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
package com.kunzisoft.keepass.app

import android.content.Context
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.multidex.MultiDexApplication
import com.kunzisoft.keepass.activities.stylish.Stylish
import com.kunzisoft.keepass.utils.LOCK_ACTION
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class App : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()

        AppLifecycleObserver.init(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver)

        Stylish.load(this)
    }
}

object AppLifecycleObserver : DefaultLifecycleObserver {

    private var appContext: Context? = null

    var isAppInForeground: Boolean = false
        private set

    var lockBackgroundEvent = false

    private val _appJustLaunched = MutableSharedFlow<Unit>(replay = 0)
    val appJustLaunched = _appJustLaunched.asSharedFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        val wasPreviouslyInBackground = !isAppInForeground
        isAppInForeground = true
        if (!lockBackgroundEvent && wasPreviouslyInBackground) {
            GlobalScope.launch {
                _appJustLaunched.emit(Unit)
            }
        } else if (wasPreviouslyInBackground) {
            lockBackgroundEvent = false
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        isAppInForeground = false
        if (!lockBackgroundEvent) {
            appContext?.sendBroadcast(Intent(LOCK_ACTION))
        }
    }
}
