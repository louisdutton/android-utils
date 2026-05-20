package org.futo.inputmethod.latin.uix.settings.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

internal fun Context.copyToClipboard(text: CharSequence, label: String = "Copied Text") {
    val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipData = ClipData.newPlainText(label, text)
    clipboardManager.setPrimaryClip(clipData)
}
