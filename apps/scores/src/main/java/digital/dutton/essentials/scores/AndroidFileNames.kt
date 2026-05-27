package digital.dutton.essentials.scores

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns

fun Context.displayNameFor(uri: Uri): String {
    val queriedName = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor: Cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        } else {
            null
        }
    }

    return queriedName
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "Score"
}

fun String.scoreTitleFromFileName(): String {
    return substringBeforeLast('.').trim().ifBlank { this }
}
