package digital.dutton.essentials.documents

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RecentDocument(
    val uri: Uri,
    val name: String,
    val openedAtMillis: Long,
)

class RecentDocumentsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "recent_documents",
        Context.MODE_PRIVATE,
    )

    fun list(): List<RecentDocument> {
        val encoded = preferences.getString(RecentDocumentsKey, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val uri = item.optString("uri").takeIf { it.isNotBlank() } ?: continue
                    val name = item.optString("name").takeIf { it.isNotBlank() } ?: "Document.pdf"
                    val openedAt = item.optLong("openedAtMillis", 0L)
                    add(
                        RecentDocument(
                            uri = Uri.parse(uri),
                            name = name,
                            openedAtMillis = openedAt,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun record(
        uri: Uri,
        name: String,
    ) {
        val updated = (list().filterNot { it.uri == uri } + RecentDocument(
            uri = uri,
            name = name,
            openedAtMillis = System.currentTimeMillis(),
        ))
            .sortedByDescending { it.openedAtMillis }
            .take(MaxRecentDocuments)

        preferences.edit()
            .putString(RecentDocumentsKey, updated.toJson().toString())
            .apply()
    }

    fun remove(uri: Uri) {
        val updated = list().filterNot { it.uri == uri }
        preferences.edit()
            .putString(RecentDocumentsKey, updated.toJson().toString())
            .apply()
    }

    private fun List<RecentDocument>.toJson(): JSONArray {
        val array = JSONArray()
        forEach { item ->
            array.put(
                JSONObject()
                    .put("uri", item.uri.toString())
                    .put("name", item.name)
                    .put("openedAtMillis", item.openedAtMillis),
            )
        }
        return array
    }

    private companion object {
        const val RecentDocumentsKey = "recent_documents"
        const val MaxRecentDocuments = 12
    }
}
