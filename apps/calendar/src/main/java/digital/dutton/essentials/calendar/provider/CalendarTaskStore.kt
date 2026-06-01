package digital.dutton.essentials.calendar.provider

import android.content.Context
import android.content.SharedPreferences
import digital.dutton.essentials.calendar.data.CalendarTask
import digital.dutton.essentials.calendar.data.CalendarTaskStatus
import digital.dutton.essentials.calendar.sync.IcsCalendarTask
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class CalendarTaskStore(context: Context) {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    fun listAgendaTasks(
        startMillis: Long,
        endMillis: Long,
    ): List<CalendarTask> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayStartMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeIncludesToday = startMillis <= todayStartMillis && todayStartMillis < endMillis

        return allTasks()
            .asSequence()
            .filter { task -> task.status != CalendarTaskStatus.Completed && task.status != CalendarTaskStatus.Cancelled }
            .filter { task ->
                val displayMillis = task.agendaMillis(today, zone) ?: return@filter false
                displayMillis in startMillis until endMillis ||
                    (rangeIncludesToday && task.isOverdue(today, zone))
            }
            .sortedWith(
                compareBy<CalendarTask> { it.agendaMillis(today, zone) ?: Long.MAX_VALUE }
                    .thenBy { it.priority ?: Int.MAX_VALUE }
                    .thenBy { it.title.lowercase() },
            )
            .toList()
    }

    fun upsertRemoteTask(
        accountId: String,
        collectionId: String,
        collectionHref: String,
        listName: String,
        listColor: Int?,
        href: String,
        etag: String?,
        task: IcsCalendarTask,
    ): TaskStoreChange {
        val id = remoteTaskId(collectionId, task.uid)
        val storedTask = CalendarTask(
            id = id,
            accountId = accountId,
            collectionId = collectionId,
            collectionHref = collectionHref,
            href = href,
            etag = etag,
            uid = task.uid,
            listName = listName,
            listColor = listColor,
            title = task.title.ifBlank { "Untitled" },
            description = task.description,
            status = task.status.toCalendarTaskStatus(),
            dueMillis = task.due?.epochMillis,
            dueAllDay = task.due?.allDay == true,
            startMillis = task.start?.epochMillis,
            startAllDay = task.start?.allDay == true,
            completedMillis = task.completed?.epochMillis,
            createdMillis = task.created?.epochMillis,
            lastModifiedMillis = task.lastModified?.epochMillis,
            priority = task.priority,
            isReadOnly = false,
        )

        val previous = getTask(id)
        preferences.edit()
            .putStringSet(KeyTaskIds, taskIds() + id)
            .putTask(storedTask)
            .apply()

        return when {
            previous == null -> TaskStoreChange.Created
            previous == storedTask -> TaskStoreChange.Unchanged
            else -> TaskStoreChange.Updated
        }
    }

    fun deleteRemoteTasksNotIn(
        collectionId: String,
        activeHrefs: Set<String>,
    ): Int {
        val tasksToDelete = allTasks()
            .filter { task ->
                task.collectionId == collectionId &&
                    task.href != null &&
                    task.href !in activeHrefs
            }
        if (tasksToDelete.isEmpty()) return 0

        val idsToDelete = tasksToDelete.map { it.id }.toSet()
        val remainingIds = taskIds() - idsToDelete
        val editor = preferences.edit().putStringSet(KeyTaskIds, remainingIds)
        idsToDelete.forEach { id -> editor.removeTask(id) }
        editor.apply()
        return idsToDelete.size
    }

    fun deleteCollectionTasks(collectionId: String): Int {
        val idsToDelete = allTasks()
            .filter { it.collectionId == collectionId }
            .map { it.id }
            .toSet()
        if (idsToDelete.isEmpty()) return 0

        val editor = preferences.edit().putStringSet(KeyTaskIds, taskIds() - idsToDelete)
        idsToDelete.forEach { id -> editor.removeTask(id) }
        editor.apply()
        return idsToDelete.size
    }

    private fun allTasks(): List<CalendarTask> {
        return taskIds().mapNotNull(::getTask)
    }

    private fun getTask(id: String): CalendarTask? {
        val prefix = id.prefix()
        val uid = preferences.getString(prefix + KeyUid, null) ?: return null
        val title = preferences.getString(prefix + KeyTitle, null) ?: return null
        return CalendarTask(
            id = id,
            accountId = preferences.getString(prefix + KeyAccountId, null),
            collectionId = preferences.getString(prefix + KeyCollectionId, null),
            collectionHref = preferences.getString(prefix + KeyCollectionHref, null),
            href = preferences.getString(prefix + KeyHref, null),
            etag = preferences.getString(prefix + KeyEtag, null),
            uid = uid,
            listName = preferences.getString(prefix + KeyListName, null),
            listColor = preferences.getNullableInt(prefix + KeyListColor),
            title = title,
            description = preferences.getString(prefix + KeyDescription, null),
            status = preferences.getString(prefix + KeyStatus, null)
                ?.let { runCatching { CalendarTaskStatus.valueOf(it) }.getOrNull() }
                ?: CalendarTaskStatus.Unknown,
            dueMillis = preferences.getNullableLong(prefix + KeyDueMillis),
            dueAllDay = preferences.getBoolean(prefix + KeyDueAllDay, false),
            startMillis = preferences.getNullableLong(prefix + KeyStartMillis),
            startAllDay = preferences.getBoolean(prefix + KeyStartAllDay, false),
            completedMillis = preferences.getNullableLong(prefix + KeyCompletedMillis),
            createdMillis = preferences.getNullableLong(prefix + KeyCreatedMillis),
            lastModifiedMillis = preferences.getNullableLong(prefix + KeyLastModifiedMillis),
            priority = preferences.getNullableInt(prefix + KeyPriority),
            isReadOnly = preferences.getBoolean(prefix + KeyReadOnly, false),
        )
    }

    private fun taskIds(): Set<String> {
        return preferences.getStringSet(KeyTaskIds, emptySet()).orEmpty()
    }

    private fun CalendarTask.agendaMillis(
        today: LocalDate,
        zone: ZoneId,
    ): Long? {
        val primaryMillis = dueMillis ?: startMillis ?: return null
        if (!isOverdue(today, zone)) return primaryMillis
        return today.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun CalendarTask.isOverdue(
        today: LocalDate,
        zone: ZoneId,
    ): Boolean {
        val due = dueMillis ?: return false
        val dateZone = if (dueAllDay) ZoneOffset.UTC else zone
        val dueDate = Instant.ofEpochMilli(due).atZone(dateZone).toLocalDate()
        return dueDate.isBefore(today)
    }

    private fun SharedPreferences.Editor.putTask(task: CalendarTask): SharedPreferences.Editor {
        val prefix = task.id.prefix()
        return putNullableString(prefix + KeyAccountId, task.accountId)
            .putNullableString(prefix + KeyCollectionId, task.collectionId)
            .putNullableString(prefix + KeyCollectionHref, task.collectionHref)
            .putNullableString(prefix + KeyHref, task.href)
            .putNullableString(prefix + KeyEtag, task.etag)
            .putString(prefix + KeyUid, task.uid)
            .putNullableString(prefix + KeyListName, task.listName)
            .putNullableInt(prefix + KeyListColor, task.listColor)
            .putString(prefix + KeyTitle, task.title)
            .putNullableString(prefix + KeyDescription, task.description)
            .putString(prefix + KeyStatus, task.status.name)
            .putNullableLong(prefix + KeyDueMillis, task.dueMillis)
            .putBoolean(prefix + KeyDueAllDay, task.dueAllDay)
            .putNullableLong(prefix + KeyStartMillis, task.startMillis)
            .putBoolean(prefix + KeyStartAllDay, task.startAllDay)
            .putNullableLong(prefix + KeyCompletedMillis, task.completedMillis)
            .putNullableLong(prefix + KeyCreatedMillis, task.createdMillis)
            .putNullableLong(prefix + KeyLastModifiedMillis, task.lastModifiedMillis)
            .putNullableInt(prefix + KeyPriority, task.priority)
            .putBoolean(prefix + KeyReadOnly, task.isReadOnly)
    }

    private fun SharedPreferences.Editor.removeTask(id: String): SharedPreferences.Editor {
        val prefix = id.prefix()
        TaskKeys.forEach { key -> remove(prefix + key) }
        return this
    }

    private fun String.prefix(): String = "$KeyTaskPrefix$this."

    private companion object {
        const val PreferencesName = "calendar_tasks"
        const val KeyTaskIds = "taskIds"
        const val KeyTaskPrefix = "task."
        const val KeyAccountId = "accountId"
        const val KeyCollectionId = "collectionId"
        const val KeyCollectionHref = "collectionHref"
        const val KeyHref = "href"
        const val KeyEtag = "etag"
        const val KeyUid = "uid"
        const val KeyListName = "listName"
        const val KeyListColor = "listColor"
        const val KeyTitle = "title"
        const val KeyDescription = "description"
        const val KeyStatus = "status"
        const val KeyDueMillis = "dueMillis"
        const val KeyDueAllDay = "dueAllDay"
        const val KeyStartMillis = "startMillis"
        const val KeyStartAllDay = "startAllDay"
        const val KeyCompletedMillis = "completedMillis"
        const val KeyCreatedMillis = "createdMillis"
        const val KeyLastModifiedMillis = "lastModifiedMillis"
        const val KeyPriority = "priority"
        const val KeyReadOnly = "readOnly"
        const val MissingLong = Long.MIN_VALUE
        const val MissingInt = Int.MIN_VALUE

        val TaskKeys = listOf(
            KeyAccountId,
            KeyCollectionId,
            KeyCollectionHref,
            KeyHref,
            KeyEtag,
            KeyUid,
            KeyListName,
            KeyListColor,
            KeyTitle,
            KeyDescription,
            KeyStatus,
            KeyDueMillis,
            KeyDueAllDay,
            KeyStartMillis,
            KeyStartAllDay,
            KeyCompletedMillis,
            KeyCreatedMillis,
            KeyLastModifiedMillis,
            KeyPriority,
            KeyReadOnly,
        )
    }
}

enum class TaskStoreChange {
    Created,
    Updated,
    Unchanged,
}

private fun String.toCalendarTaskStatus(): CalendarTaskStatus {
    return when (trim().uppercase()) {
        "NEEDS-ACTION" -> CalendarTaskStatus.NeedsAction
        "IN-PROCESS" -> CalendarTaskStatus.InProcess
        "COMPLETED" -> CalendarTaskStatus.Completed
        "CANCELLED" -> CalendarTaskStatus.Cancelled
        else -> CalendarTaskStatus.Unknown
    }
}

private fun remoteTaskId(
    collectionId: String,
    uid: String,
): String {
    return UUID.nameUUIDFromBytes("$collectionId|$uid".toByteArray(StandardCharsets.UTF_8)).toString()
}

private fun SharedPreferences.getNullableLong(key: String): Long? {
    return getLong(key, CalendarTaskStoreMissingLong).takeUnless { it == CalendarTaskStoreMissingLong }
}

private fun SharedPreferences.getNullableInt(key: String): Int? {
    return getInt(key, CalendarTaskStoreMissingInt).takeUnless { it == CalendarTaskStoreMissingInt }
}

private fun SharedPreferences.Editor.putNullableString(
    key: String,
    value: String?,
): SharedPreferences.Editor {
    return if (value == null) remove(key) else putString(key, value)
}

private fun SharedPreferences.Editor.putNullableLong(
    key: String,
    value: Long?,
): SharedPreferences.Editor {
    return if (value == null) remove(key) else putLong(key, value)
}

private fun SharedPreferences.Editor.putNullableInt(
    key: String,
    value: Int?,
): SharedPreferences.Editor {
    return if (value == null) remove(key) else putInt(key, value)
}

private const val CalendarTaskStoreMissingLong = Long.MIN_VALUE
private const val CalendarTaskStoreMissingInt = Int.MIN_VALUE
