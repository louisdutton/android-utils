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
import java.time.ZonedDateTime
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
            .flatMap { task -> task.agendaOccurrences(startMillis, endMillis, today, zone, rangeIncludesToday) }
            .sortedWith(
                compareBy<CalendarTask> { it.agendaMillis(today, zone) ?: Long.MAX_VALUE }
                    .thenBy { it.priority ?: Int.MAX_VALUE }
                    .thenBy { it.title.lowercase() },
            )
            .toList()
    }

    fun upsertTask(task: CalendarTask): TaskStoreChange {
        val previous = getTask(task.id)
        preferences.edit()
            .putStringSet(KeyTaskIds, taskIds() + task.id)
            .putTask(task)
            .apply()

        return when {
            previous == null -> TaskStoreChange.Created
            previous == task -> TaskStoreChange.Unchanged
            else -> TaskStoreChange.Updated
        }
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
        val id = remoteCalendarTaskId(accountId, collectionHref, task.uid)
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
            recurrenceRule = task.recurrenceRule,
            priority = task.priority,
            isReadOnly = false,
        )

        val previous = getTask(id)
        val duplicateIds = allTasks()
            .filter { existing ->
                existing.id != id &&
                    existing.matchesRemoteTask(
                        accountId = accountId,
                        collectionHref = collectionHref,
                        href = href,
                        uid = task.uid,
                    )
            }
            .map { it.id }
            .toSet()

        val editor = preferences.edit()
            .putStringSet(KeyTaskIds, taskIds() + id - duplicateIds)
            .putTask(storedTask)
        duplicateIds.forEach { duplicateId -> editor.removeTask(duplicateId) }
        editor.apply()

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

    fun deleteTasksOutsideCollections(activeCollectionIds: Set<String>): Int {
        val idsToDelete = allTasks()
            .filter { task -> task.collectionId != null && task.collectionId !in activeCollectionIds }
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

    private fun CalendarTask.matchesRemoteTask(
        accountId: String,
        collectionHref: String,
        href: String,
        uid: String,
    ): Boolean {
        return this.accountId == accountId &&
            (
                this.href == href ||
                    (this.collectionHref == collectionHref && this.uid == uid)
                )
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
            recurrenceRule = preferences.getString(prefix + KeyRecurrenceRule, null),
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
            .putNullableString(prefix + KeyRecurrenceRule, task.recurrenceRule)
            .putNullableInt(prefix + KeyPriority, task.priority)
            .putBoolean(prefix + KeyReadOnly, task.isReadOnly)
    }

    private fun CalendarTask.agendaOccurrences(
        rangeStartMillis: Long,
        rangeEndMillis: Long,
        today: LocalDate,
        zone: ZoneId,
        rangeIncludesToday: Boolean,
    ): Sequence<CalendarTask> {
        val recurrence = recurrenceRule?.toRecurrenceStep()
        if (recurrence == null) {
            val displayMillis = agendaMillis(today, zone) ?: return emptySequence()
            return if (
                displayMillis in rangeStartMillis until rangeEndMillis ||
                (rangeIncludesToday && isOverdue(today, zone))
            ) {
                sequenceOf(this)
            } else {
                emptySequence()
            }
        }

        val sourceMillis = dueMillis ?: startMillis ?: return emptySequence()
        val allDay = if (dueMillis != null) dueAllDay else startAllDay
        val dateZone = if (allDay) ZoneOffset.UTC else zone
        var occurrence = Instant.ofEpochMilli(sourceMillis).atZone(dateZone)
        var iterations = 0
        while (occurrence.toInstant().toEpochMilli() < rangeStartMillis && iterations < MaxRecurrenceSearchIterations) {
            occurrence = occurrence.plusRecurrence(recurrence)
            iterations += 1
        }

        return sequence {
            var current = occurrence
            var count = 0
            while (count < MaxRecurrenceOccurrences) {
                val currentMillis = current.toInstant().toEpochMilli()
                if (currentMillis >= rangeEndMillis) break
                if (currentMillis >= rangeStartMillis) {
                    yield(copyWithOccurrenceMillis(currentMillis))
                }
                current = current.plusRecurrence(recurrence)
                count += 1
            }
        }
    }

    private fun CalendarTask.copyWithOccurrenceMillis(millis: Long): CalendarTask {
        return if (dueMillis != null) {
            copy(id = "$id@$millis", dueMillis = millis)
        } else {
            copy(id = "$id@$millis", startMillis = millis)
        }
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
        const val KeyRecurrenceRule = "recurrenceRule"
        const val KeyPriority = "priority"
        const val KeyReadOnly = "readOnly"
        const val MissingLong = Long.MIN_VALUE
        const val MissingInt = Int.MIN_VALUE
        const val MaxRecurrenceOccurrences = 500
        const val MaxRecurrenceSearchIterations = 5000

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
            KeyRecurrenceRule,
            KeyPriority,
            KeyReadOnly,
        )
    }
}

private data class RecurrenceStep(
    val frequency: String,
    val interval: Long,
)

private fun String.toRecurrenceStep(): RecurrenceStep? {
    val parts = split(';')
        .mapNotNull { part ->
            val key = part.substringBefore('=', missingDelimiterValue = "").uppercase()
            val value = part.substringAfter('=', missingDelimiterValue = "")
            if (key.isBlank() || value.isBlank()) null else key to value.uppercase()
        }
        .toMap()
    val frequency = parts["FREQ"] ?: return null
    if (frequency !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) return null
    val interval = parts["INTERVAL"]?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
    return RecurrenceStep(frequency, interval)
}

private fun ZonedDateTime.plusRecurrence(step: RecurrenceStep): ZonedDateTime {
    return when (step.frequency) {
        "DAILY" -> plusDays(step.interval)
        "WEEKLY" -> plusWeeks(step.interval)
        "MONTHLY" -> plusMonths(step.interval)
        "YEARLY" -> plusYears(step.interval)
        else -> this
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

internal fun remoteCalendarTaskId(
    accountId: String,
    collectionHref: String,
    uid: String,
): String {
    return UUID.nameUUIDFromBytes(
        "$accountId|${collectionHref.trimEnd('/')}|$uid".toByteArray(StandardCharsets.UTF_8),
    ).toString()
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
