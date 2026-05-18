package dev.octoshrimpy.quik.util

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager

object GlideApp {
    fun get(context: Context): Glide = Glide.get(context)

    fun with(context: Context): RequestManager = Glide.with(context)

    fun with(activity: Activity): RequestManager = Glide.with(activity)

    fun with(activity: FragmentActivity): RequestManager = Glide.with(activity)

    fun with(fragment: Fragment): RequestManager = Glide.with(fragment)

    fun with(view: View): RequestManager = Glide.with(view)
}
