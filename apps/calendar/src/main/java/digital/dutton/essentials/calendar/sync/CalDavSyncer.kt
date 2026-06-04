package digital.dutton.essentials.calendar.sync

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.provider.CalendarContract
import digital.dutton.essentials.calendar.data.CalendarTask
import digital.dutton.essentials.calendar.data.CalendarTaskDraft
import digital.dutton.essentials.calendar.data.CalendarTaskStatus
import digital.dutton.essentials.calendar.provider.CalendarEventLocationStore
import digital.dutton.essentials.calendar.provider.CalendarTaskStore
import digital.dutton.essentials.calendar.provider.StoredEventLocation
import digital.dutton.essentials.calendar.provider.TaskStoreChange
import digital.dutton.essentials.calendar.provider.remoteCalendarTaskId
import digital.dutton.essentials.locations.GeoPoint
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CalDavSyncer(
    private val context: Context,
    private val store: CalDavAccountStore = CalDavAccountStore(context),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val client: CalDavClient = CalDavClient(),
    private val parser: IcsCalendarParser = IcsCalendarParser(),
) {
    private val eventLocationStore = CalendarEventLocationStore(context)
    private val taskStore = CalendarTaskStore(context)

    suspend fun connect(
        baseUrl: String,
        username: String,
        password: String,
        requestedName: String?,
    ): CalDavSyncSummary = withContext(dispatcher) {
        requireCalendarPermissions()
        val normalizedUrl = normalizeCalDavUrl(baseUrl)
        val cleanedUsername = username.trim()
        require(cleanedUsername.isNotBlank()) { "Add a CalDAV username." }
        require(password.isNotBlank()) { "Add a CalDAV password or app password." }

        val displayName = requestedName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: cleanedUsername

        val existing = store.findAccount(normalizedUrl, cleanedUsername)
        val account = (existing ?: CalDavAccount(
            baseUrl = normalizedUrl,
            username = cleanedUsername,
            password = password,
            displayName = displayName,
        )).copy(
            password = password,
            displayName = displayName,
            lastError = null,
        )
        store.upsertAccount(account)
        CalendarProviderAccountRegistrar.ensureCalDavAccount(context, account.id)

        syncAccount(account.id, createDefaultCalendar = true)
    }

    suspend fun syncAll(): List<CalDavSyncSummary> = withContext(dispatcher) {
        val accounts = store.listAccounts()
        val summaries = mutableListOf<CalDavSyncSummary>()
        val failures = mutableListOf<Throwable>()

        accounts.forEach { account ->
            runCatching {
                syncAccount(account.id)
            }.onSuccess { summary ->
                summaries += summary
            }.onFailure { error ->
                failures += error
            }
        }

        if (summaries.isEmpty() && failures.isNotEmpty()) {
            throw failures.first()
        }

        summaries
    }

    suspend fun syncAccount(accountId: String): CalDavSyncSummary =
        syncAccount(accountId, createDefaultCalendar = false)

    suspend fun hasMissingProviderCalendars(): Boolean = withContext(dispatcher) {
        requireCalendarPermissions()
        store.listAccounts().forEach { account ->
            CalendarProviderAccountRegistrar.ensureCalDavAccount(context, account.id)
        }
        store.listCalendars().any { calendar ->
            calendar.supportsEvents && !calendarExists(calendar)
        }
    }

    suspend fun disconnect(accountId: String) = withContext(dispatcher) {
        requireCalendarPermissions()
        val calendars = store.listCalendars(accountId)
        calendars.forEach { calendar ->
            deleteCalendar(calendar)
            taskStore.deleteCollectionTasks(calendar.id)
            store.removeCalendar(calendar.id)
        }
        store.removeAccount(accountId)
    }

    suspend fun renameCalendar(
        calendarId: String,
        displayName: String,
        color: Int,
    ) = withContext(dispatcher) {
        requireCalendarPermissions()
        val cleanedDisplayName = displayName.trim()
        require(cleanedDisplayName.isNotBlank()) { "Add a calendar name." }
        val calendar = store.getCalendar(calendarId)
            ?: throw IllegalArgumentException("CalDAV calendar was not found.")
        val account = store.getAccount(calendar.accountId)
            ?: throw IllegalArgumentException("CalDAV account was not found.")

        runCatching {
            client.updateCalendarProperties(
                endpoint = account.endpoint(),
                calendarHref = calendar.href,
                displayName = cleanedDisplayName,
                color = color,
            )
        }.getOrElse {
            client.renameCalendar(
                endpoint = account.endpoint(),
                calendarHref = calendar.href,
                displayName = cleanedDisplayName,
            )
        }

        val renamedCalendar = calendar.copy(displayName = cleanedDisplayName, color = color)
        updateCalDavCalendar(
            localCalendar = renamedCalendar,
            remoteCalendar = CalDavDiscoveredCalendar(
                href = renamedCalendar.href,
                displayName = renamedCalendar.displayName,
                color = color,
                syncToken = renamedCalendar.syncToken,
                supportsEvents = renamedCalendar.supportsEvents,
                supportsTasks = renamedCalendar.supportsTasks,
            ),
        )
        store.upsertCalendar(renamedCalendar)
        taskStore.updateCollectionDetails(
            collectionId = renamedCalendar.id,
            listName = renamedCalendar.displayName,
            listColor = renamedCalendar.color,
        )
    }

    suspend fun createTask(draft: CalendarTaskDraft): CalendarTask = withContext(dispatcher) {
        requireCalendarPermissions()
        val cleanedTitle = draft.title.trim()
        require(cleanedTitle.isNotBlank()) { "Add a task title." }

        val account = store.listAccounts().firstOrNull()
            ?: throw IllegalStateException("Connect a CalDAV account before creating tasks.")
        val taskList = draft.collectionId
            ?.let { store.getCalendar(it) }
            ?.takeIf { it.accountId == account.id && it.supportsTasks }
            ?: store.listCalendars(account.id).firstOrNull { it.supportsTasks }
            ?: createDefaultTaskList(account)

        val uid = UUID.randomUUID().toString()
        val href = taskList.href.trimEnd('/') + "/" + Uri.encode(uid, "@._-") + ".ics"
        val etag = client.putEvent(
            endpoint = account.endpoint(),
            calendarHref = taskList.href,
            eventHref = href,
            etag = null,
            body = draft.toTaskIcs(uid),
        )
        val now = System.currentTimeMillis()
        val task = CalendarTask(
            id = remoteCalendarTaskId(account.id, taskList.href, uid),
            accountId = account.id,
            collectionId = taskList.id,
            collectionHref = taskList.href,
            href = href,
            etag = etag,
            uid = uid,
            listName = taskList.displayName,
            listColor = taskList.color,
            title = cleanedTitle,
            description = draft.description,
            status = CalendarTaskStatus.NeedsAction,
            dueMillis = draft.dueMillis,
            dueAllDay = draft.dueAllDay,
            startMillis = null,
            startAllDay = false,
            completedMillis = null,
            createdMillis = now,
            lastModifiedMillis = now,
            recurrenceRule = draft.recurrenceRule,
            priority = draft.priority,
            isReadOnly = false,
        )

        taskStore.upsertTask(task)
        store.upsertCalendar(taskList.copy(lastSyncMillis = now, lastError = null))
        store.upsertAccount(account.copy(lastSyncMillis = now, lastError = null))
        task
    }

    suspend fun setTaskCompleted(
        task: CalendarTask,
        completed: Boolean,
    ): CalendarTask = withContext(dispatcher) {
        requireCalendarPermissions()
        val storedTask = taskStore.getTask(task.id.substringBeforeLast("@"))
            ?: taskStore.getTask(task.id)
            ?: task
        val accountId = storedTask.accountId
            ?: throw IllegalStateException("Only synced CalDAV tasks can be updated.")
        val account = store.getAccount(accountId)
            ?: throw IllegalStateException("CalDAV account was not found.")
        val calendar = storedTask.collectionId
            ?.let(store::getCalendar)
            ?: storedTask.collectionHref?.let { store.findCalendar(accountId, it) }
            ?: throw IllegalStateException("CalDAV task list was not found.")
        val href = storedTask.href
            ?: throw IllegalStateException("CalDAV task does not have a remote href.")
        val now = System.currentTimeMillis()
        val updatedTask = storedTask.copy(
            id = remoteCalendarTaskId(account.id, calendar.href, storedTask.uid),
            accountId = account.id,
            collectionId = calendar.id,
            collectionHref = calendar.href,
            listName = calendar.displayName,
            listColor = calendar.color,
            status = if (completed) CalendarTaskStatus.Completed else CalendarTaskStatus.NeedsAction,
            completedMillis = if (completed) now else null,
            lastModifiedMillis = now,
        )
        val etag = client.putEvent(
            endpoint = account.endpoint(),
            calendarHref = calendar.href,
            eventHref = href,
            etag = storedTask.etag,
            body = updatedTask.toTaskIcs(),
        ) ?: storedTask.etag
        val savedTask = updatedTask.copy(etag = etag)

        taskStore.upsertTask(savedTask)
        store.upsertCalendar(calendar.copy(lastSyncMillis = now, lastError = null))
        store.upsertAccount(account.copy(lastSyncMillis = now, lastError = null))
        savedTask
    }

    suspend fun repairAccounts() = withContext(dispatcher) {
        requireCalendarPermissions()
        val accountsToResync = mutableSetOf<String>()
        store.listCalendars().forEach { calendar ->
            if (calendar.supportsEvents && !calendarExists(calendar)) {
                store.removeCalendar(calendar.id)
                accountsToResync += calendar.accountId
            }
        }
        accountsToResync.forEach { accountId ->
            runCatching { syncAccount(accountId) }
        }
    }

    private suspend fun syncAccount(
        accountId: String,
        createDefaultCalendar: Boolean,
    ): CalDavSyncSummary = withContext(dispatcher) {
        requireCalendarPermissions()
        val account = store.getAccount(accountId)
            ?: throw IllegalArgumentException("CalDAV account was not found.")
        CalendarProviderAccountRegistrar.ensureCalDavAccount(context, account.id)

        runCatching {
            syncAccountInternal(account, createDefaultCalendar)
        }.getOrElse { error ->
            store.upsertAccount(account.copy(lastError = error.message ?: "Unable to sync CalDAV account."))
            throw error
        }
    }

    private fun syncAccountInternal(
        account: CalDavAccount,
        createDefaultCalendar: Boolean,
    ): CalDavSyncSummary {
        CalendarProviderAccountRegistrar.ensureCalDavAccount(context, account.id)
        val endpoint = account.endpoint()
        val discovery = client.discover(endpoint)
        val discoveredCalendars = discovery.calendars.ifEmpty {
            if (createDefaultCalendar) {
                listOf(
                    client.createCalendar(
                        endpoint = endpoint,
                        homeUrl = discovery.homeUrl,
                        displayName = account.displayName,
                    ),
                )
            } else {
                emptyList()
            }
        }
        require(discoveredCalendars.isNotEmpty()) {
            "No CalDAV calendars or task lists were found for this account."
        }

        val activeCalendars = reconcileCalendars(account, discoveredCalendars)
        val discoveredByHref = discoveredCalendars.associateBy { it.href }
        var created = 0
        var updated = 0
        var deleted = 0
        var conflicts = 0

        activeCalendars.forEach { calendar ->
            val remoteSyncToken = discoveredByHref[calendar.href]?.syncToken
            val remoteUnchanged = calendar.lastSyncMillis != null &&
                remoteSyncToken != null &&
                calendar.syncToken == remoteSyncToken
            var hasLocalChanges = false

            if (calendar.supportsEvents && calendar.localCalendarId != null) {
                val localChanges = uploadLocalChanges(account, calendar)
                hasLocalChanges = localChanges.hasChanges()
                created += localChanges.created
                updated += localChanges.updated
                deleted += localChanges.deleted
                conflicts += localChanges.conflicts

                if (remoteUnchanged && !hasLocalChanges) {
                    markCalendarSynced(calendar, remoteSyncToken)
                } else {
                    val remoteChanges = downloadRemoteEvents(account, calendar, remoteSyncToken)
                    created += remoteChanges.created
                    updated += remoteChanges.updated
                    deleted += remoteChanges.deleted
                    conflicts += remoteChanges.conflicts
                }
            }

            if (calendar.supportsTasks) {
                if (remoteUnchanged && !hasLocalChanges) {
                    if (!calendar.supportsEvents) markCalendarSynced(calendar, remoteSyncToken)
                } else {
                    val taskChanges = downloadRemoteTasks(account, calendar, remoteSyncToken)
                    created += taskChanges.created
                    updated += taskChanges.updated
                    deleted += taskChanges.deleted
                    conflicts += taskChanges.conflicts
                }
            }
        }

        deleted += taskStore.deleteTasksOutsideCollections(activeCalendars.map { it.id }.toSet())

        store.upsertAccount(
            account.copy(
                lastSyncMillis = System.currentTimeMillis(),
                lastError = null,
            ),
        )

        return CalDavSyncSummary(
            accountId = account.id,
            created = created,
            updated = updated,
            deleted = deleted,
            conflicts = conflicts,
        )
    }

    private fun reconcileCalendars(
        account: CalDavAccount,
        discoveredCalendars: List<CalDavDiscoveredCalendar>,
    ): List<CalDavCalendar> {
        val discoveredByHref = discoveredCalendars.associateBy { it.href }
        store.listCalendars(account.id)
            .filter { it.href !in discoveredByHref }
            .forEach { staleCalendar ->
                deleteCalendar(staleCalendar)
                taskStore.deleteCollectionTasks(staleCalendar.id)
                store.removeCalendar(staleCalendar.id)
            }

        return discoveredCalendars.map { remote ->
            val storedCalendars = store.listCalendars(account.id).filter { it.href == remote.href }
            val validStoredCalendars = storedCalendars.filter { stored ->
                !remote.supportsEvents || calendarExists(stored)
            }
            val existing = validStoredCalendars.preferredCalDavCalendar()
            storedCalendars
                .filter { it.id != existing?.id }
                .forEach { duplicate ->
                    deleteCalendar(duplicate)
                    taskStore.deleteCollectionTasks(duplicate.id)
                    store.removeCalendar(duplicate.id)
                }

            val localCalendarId = if (remote.supportsEvents) {
                existing?.localCalendarId?.takeIf { calendarExists(existing) }
                    ?: createCalDavCalendar(account, remote)
            } else {
                null
            }

            val calendar = if (existing == null) {
                CalDavCalendar(
                    id = calDavCalendarId(account.id, remote.href),
                    accountId = account.id,
                    localCalendarId = localCalendarId,
                    href = remote.href,
                    displayName = remote.displayName,
                    color = remote.color,
                    supportsEvents = remote.supportsEvents,
                    supportsTasks = remote.supportsTasks,
                    syncToken = remote.syncToken,
                )
            } else {
                val updatedCalendar = existing.copy(
                    localCalendarId = localCalendarId,
                    supportsEvents = remote.supportsEvents,
                    supportsTasks = remote.supportsTasks,
                )
                val calendarColor = existing.color ?: remote.color
                updateCalDavCalendar(updatedCalendar, remote.copy(color = calendarColor))
                existing.copy(
                    localCalendarId = localCalendarId,
                    displayName = remote.displayName,
                    color = calendarColor,
                    supportsEvents = remote.supportsEvents,
                    supportsTasks = remote.supportsTasks,
                    syncToken = existing.syncToken,
                )
            }
            store.upsertCalendar(calendar)
            calendar
        }
    }

    private fun uploadLocalChanges(
        account: CalDavAccount,
        calendar: CalDavCalendar,
    ): CalDavSyncSummary {
        val changes = dirtyLocalEvents(calendar)
        var created = 0
        var updated = 0
        var deleted = 0
        var conflicts = 0

        changes.forEach { event ->
            runCatching {
                if (event.deleted) {
                    event.href?.let { href ->
                        client.deleteEvent(
                            endpoint = account.endpoint(),
                            eventHref = href,
                            etag = event.etag,
                        )
                    }
                    deleteLocalEvent(event.id, account.id)
                    deleted += 1
                } else {
                    val uid = event.uid ?: UUID.randomUUID().toString()
                    val href = event.href ?: calendar.href.trimEnd('/') + "/" + Uri.encode(uid, "@._-") + ".ics"
                    val etag = client.putEvent(
                        endpoint = account.endpoint(),
                        calendarHref = calendar.href,
                        eventHref = href,
                        etag = event.etag,
                        body = event.toIcs(uid),
                    ) ?: event.etag
                    markLocalEventClean(
                        eventId = event.id,
                        accountId = account.id,
                        uid = uid,
                        href = href,
                        etag = etag,
                    )
                    if (event.href == null) created += 1 else updated += 1
                }
            }.onFailure { error ->
                if (error is CalDavConflictException) {
                    conflicts += 1
                } else {
                    throw error
                }
            }
        }

        return CalDavSyncSummary(account.id, created, updated, deleted, conflicts)
    }

    private fun downloadRemoteEvents(
        account: CalDavAccount,
        calendar: CalDavCalendar,
        remoteSyncToken: String?,
    ): CalDavSyncSummary {
        val localCalendarId = calendar.localCalendarId ?: return CalDavSyncSummary(account.id, 0, 0, 0, 0)
        val incrementalChanges = calendar.incrementalSyncToken()?.let { syncToken ->
            runCatching {
                client.fetchEventChanges(
                    endpoint = account.endpoint(),
                    calendarHref = calendar.href,
                    syncToken = syncToken,
                )
            }.getOrNull()
        }
        val isFullSync = incrementalChanges == null
        val remoteEvents = incrementalChanges?.changed
            ?: client.fetchEvents(account.endpoint(), calendar.href)
        val changedHrefsWithoutEvents = mutableSetOf<String>()
        val incoming = remoteEvents.flatMap { remote ->
            val parsedEvents = parser.parse(remote.calendarData).events
            if (!isFullSync && parsedEvents.isEmpty()) {
                changedHrefsWithoutEvents += remote.href
            }
            parsedEvents.map { event ->
                RemoteParsedEvent(
                    event = event,
                    href = remote.href,
                    etag = remote.etag,
                )
            }
        }.distinctBy { it.remoteId }

        val existing = queryExistingRemoteEvents(calendar)
        val dirtyRemoteIds = queryDirtyRemoteIds(calendar)
        val dirtyRemoteHrefs = queryDirtyRemoteHrefs(calendar)
        val incomingIds = incoming.map { it.remoteId }.toSet()
        var created = 0
        var updated = 0
        var skipped = 0

        incoming.forEach { remote ->
            val values = remote.event.toContentValues(
                calendar = calendar,
                accountId = account.id,
                href = remote.href,
                etag = remote.etag,
            )
            val existingId = existing[remote.remoteId]
            if (existingId == null) {
                val uri = context.contentResolver.insert(
                    CalendarContract.Events.CONTENT_URI.asCalDavSyncAdapter(account.id),
                    values,
                )
                if (uri == null) {
                    skipped += 1
                } else {
                    eventLocationStore.put(ContentUris.parseId(uri), remote.event.storedLocation())
                    created += 1
                }
            } else if (remote.remoteId in dirtyRemoteIds) {
                skipped += 1
            } else {
                val rows = context.contentResolver.update(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingId)
                        .asCalDavSyncAdapter(account.id),
                    values,
                    null,
                    null,
                )
                if (rows > 0) {
                    eventLocationStore.put(existingId, remote.event.storedLocation())
                    updated += 1
                } else {
                    skipped += 1
                }
            }
        }

        var deleted = 0
        if (isFullSync) {
            existing
                .filterKeys { it !in incomingIds && it !in dirtyRemoteIds }
                .values
                .forEach { eventId ->
                    deleted += context.contentResolver.delete(
                        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                            .asCalDavSyncAdapter(account.id),
                        null,
                        null,
                    )
                }
        } else {
            val existingByHref = queryExistingRemoteEventHrefs(calendar)
            (incrementalChanges.deletedHrefs + changedHrefsWithoutEvents)
                .filter { href -> href !in dirtyRemoteHrefs }
                .forEach { href ->
                    existingByHref[href]?.let { eventId ->
                        deleted += context.contentResolver.delete(
                            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                                .asCalDavSyncAdapter(account.id),
                            null,
                            null,
                        )
                    }
                }
            }

        val syncedCalendar = calendar.copy(
            syncToken = incrementalChanges?.syncToken ?: remoteSyncToken ?: calendar.syncToken,
            lastSyncMillis = System.currentTimeMillis(),
            lastError = null,
        )
        store.upsertCalendar(syncedCalendar)

        return CalDavSyncSummary(
            accountId = account.id,
            created = created,
            updated = updated,
            deleted = deleted,
            conflicts = skipped,
        )
    }

    private fun downloadRemoteTasks(
        account: CalDavAccount,
        calendar: CalDavCalendar,
        remoteSyncToken: String?,
    ): CalDavSyncSummary {
        val incrementalChanges = calendar.incrementalSyncToken()?.let { syncToken ->
            runCatching {
                client.fetchTaskChanges(
                    endpoint = account.endpoint(),
                    calendarHref = calendar.href,
                    syncToken = syncToken,
                )
            }.getOrNull()
        }
        val isFullSync = incrementalChanges == null
        val remoteTasks = incrementalChanges?.changed
            ?: client.fetchTasks(account.endpoint(), calendar.href)
        val incomingHrefs = remoteTasks.map { it.href }.toSet()
        val changedHrefsWithoutTasks = mutableSetOf<String>()
        var created = 0
        var updated = 0

        remoteTasks.forEach { remote ->
            val tasks = parser.parse(remote.calendarData).tasks
            if (!isFullSync && tasks.isEmpty()) {
                changedHrefsWithoutTasks += remote.href
            }
            tasks.forEach { task ->
                when (
                    taskStore.upsertRemoteTask(
                        accountId = account.id,
                        collectionId = calendar.id,
                        collectionHref = calendar.href,
                        listName = calendar.displayName,
                        listColor = calendar.color,
                        href = remote.href,
                        etag = remote.etag,
                        task = task,
                    )
                ) {
                    TaskStoreChange.Created -> created += 1
                    TaskStoreChange.Updated -> updated += 1
                    TaskStoreChange.Unchanged -> Unit
                }
            }
        }

        val deleted = if (isFullSync) {
            taskStore.deleteRemoteTasksNotIn(calendar.id, incomingHrefs)
        } else {
            taskStore.deleteRemoteTasksByHref(
                collectionId = calendar.id,
                deletedHrefs = incrementalChanges.deletedHrefs + changedHrefsWithoutTasks,
            )
        }
        store.upsertCalendar(
            calendar.copy(
                syncToken = incrementalChanges?.syncToken ?: remoteSyncToken ?: calendar.syncToken,
                lastSyncMillis = System.currentTimeMillis(),
                lastError = null,
            ),
        )

        return CalDavSyncSummary(
            accountId = account.id,
            created = created,
            updated = updated,
            deleted = deleted,
            conflicts = 0,
        )
    }

    private fun markCalendarSynced(
        calendar: CalDavCalendar,
        remoteSyncToken: String?,
    ) {
        store.upsertCalendar(
            calendar.copy(
                syncToken = remoteSyncToken ?: calendar.syncToken,
                lastSyncMillis = System.currentTimeMillis(),
                lastError = null,
            ),
        )
    }

    private fun createDefaultTaskList(account: CalDavAccount): CalDavCalendar {
        val discovery = client.discover(account.endpoint())
        val remote = client.createCalendar(
            endpoint = account.endpoint(),
            homeUrl = discovery.homeUrl,
            displayName = "Tasks",
            components = setOf("VTODO"),
        )
        val calendar = CalDavCalendar(
            id = calDavCalendarId(account.id, remote.href),
            accountId = account.id,
            localCalendarId = null,
            href = remote.href,
            displayName = remote.displayName,
            color = remote.color ?: DefaultTaskListColor,
            supportsEvents = false,
            supportsTasks = true,
            syncToken = remote.syncToken,
        )
        store.upsertCalendar(calendar)
        return calendar
    }

    private fun dirtyLocalEvents(calendar: CalDavCalendar): List<LocalCalDavEvent> {
        val localCalendarId = calendar.localCalendarId ?: return emptyList()
        val events = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI.asCalDavSyncAdapter(calendar.accountId),
            LocalEventProjection,
            "${CalendarContract.Events.CALENDAR_ID} = ? AND (${CalendarContract.Events.DIRTY} = 1 OR ${CalendarContract.Events.DELETED} = 1)",
            arrayOf(localCalendarId.toString()),
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toLocalCalDavEvent())
                }
            }
        }.orEmpty()
        val locationsByEventId = eventLocationStore.list(events.map { it.id }.toSet())
        return events.map { event ->
            val linkedLocation = locationsByEventId[event.id] ?: return@map event
            event.copy(
                geoPoint = linkedLocation.point,
                locationMapName = linkedLocation.name,
                locationMapId = linkedLocation.mapId,
            )
        }
    }

    private fun queryExistingRemoteEvents(calendar: CalDavCalendar): Map<String, Long> {
        val localCalendarId = calendar.localCalendarId ?: return emptyMap()
        val existingEvents = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI.asCalDavSyncAdapter(calendar.accountId),
            ExistingEventProjection,
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DELETED} = 0",
            arrayOf(localCalendarId.toString()),
            null,
        )?.use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val eventId = cursor.requireLong(CalendarContract.Events._ID)
                    val remoteId = cursor.optionalString(CalendarContract.Events._SYNC_ID)
                    if (remoteId != null) put(eventId, remoteId)
                }
            }
        }.orEmpty()

        return existingEvents
            .entries
            .groupBy({ it.value }, { it.key })
            .mapValues { (_, eventIds) ->
                val keepId = eventIds.maxOrNull()
                    ?: throw IllegalArgumentException("No CalDAV events to compare.")
                eventIds
                    .filter { it != keepId }
                    .forEach { eventId -> deleteLocalEvent(eventId, calendar.accountId) }
                keepId
            }
    }

    private fun queryExistingRemoteEventHrefs(calendar: CalDavCalendar): Map<String, Long> {
        val localCalendarId = calendar.localCalendarId ?: return emptyMap()
        val existingEvents = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI.asCalDavSyncAdapter(calendar.accountId),
            ExistingEventProjection,
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DELETED} = 0",
            arrayOf(localCalendarId.toString()),
            null,
        )?.use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val eventId = cursor.requireLong(CalendarContract.Events._ID)
                    val href = cursor.optionalString(CalendarContract.Events.SYNC_DATA2)
                    if (href != null) put(eventId, href)
                }
            }
        }.orEmpty()

        return existingEvents
            .entries
            .groupBy({ it.value }, { it.key })
            .mapValues { (_, eventIds) ->
                eventIds.maxOrNull()
                    ?: throw IllegalArgumentException("No CalDAV event hrefs to compare.")
            }
    }

    private fun queryDirtyRemoteIds(calendar: CalDavCalendar): Set<String> {
        val localCalendarId = calendar.localCalendarId ?: return emptySet()
        return context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI.asCalDavSyncAdapter(calendar.accountId),
            DirtyEventProjection,
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DIRTY} = 1",
            arrayOf(localCalendarId.toString()),
            null,
        )?.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    cursor.optionalString(CalendarContract.Events._SYNC_ID)?.let(::add)
                }
            }
        }.orEmpty()
    }

    private fun queryDirtyRemoteHrefs(calendar: CalDavCalendar): Set<String> {
        val localCalendarId = calendar.localCalendarId ?: return emptySet()
        return context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI.asCalDavSyncAdapter(calendar.accountId),
            DirtyEventProjection,
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DIRTY} = 1",
            arrayOf(localCalendarId.toString()),
            null,
        )?.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    cursor.optionalString(CalendarContract.Events.SYNC_DATA2)?.let(::add)
                }
            }
        }.orEmpty()
    }

    private fun createCalDavCalendar(
        account: CalDavAccount,
        calendar: CalDavDiscoveredCalendar,
    ): Long {
        CalendarProviderAccountRegistrar.ensureCalDavAccount(context, account.id)
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account.id)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalDavAccountType)
            put(CalendarContract.Calendars.NAME, calendar.href)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, calendar.displayName)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account.username)
            put(CalendarContract.Calendars.CALENDAR_COLOR, calendar.color ?: DefaultCalDavCalendarColor)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CAN_MODIFY_TIME_ZONE, 1)
            put(CalendarContract.Calendars.CAN_ORGANIZER_RESPOND, 1)
        }

        val uri = context.contentResolver.insert(
            CalendarContract.Calendars.CONTENT_URI.asCalDavSyncAdapter(account.id),
            values,
        ) ?: throw IllegalStateException("Android Calendar Provider did not return a calendar URI.")

        return ContentUris.parseId(uri)
    }

    private fun updateCalDavCalendar(
        localCalendar: CalDavCalendar,
        remoteCalendar: CalDavDiscoveredCalendar,
    ) {
        val localCalendarId = localCalendar.localCalendarId ?: return
        if (!calendarExists(localCalendar)) return

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, remoteCalendar.displayName)
            put(CalendarContract.Calendars.CALENDAR_COLOR, remoteCalendar.color ?: DefaultCalDavCalendarColor)
        }
        context.contentResolver.update(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, localCalendarId)
                .asCalDavSyncAdapter(localCalendar.accountId),
            values,
            null,
            null,
        )
    }

    private fun markLocalEventClean(
        eventId: Long,
        accountId: String,
        uid: String,
        href: String,
        etag: String?,
    ) {
        val remoteId = "$href|$uid"
        val values = ContentValues().apply {
            put(CalendarContract.Events.UID_2445, uid)
            put(CalendarContract.Events._SYNC_ID, remoteId)
            put(CalendarContract.Events.SYNC_DATA1, accountId)
            put(CalendarContract.Events.SYNC_DATA2, href)
            putNullableString(CalendarContract.Events.SYNC_DATA3, etag)
            put(CalendarContract.Events.DIRTY, 0)
            put(CalendarContract.Events.DELETED, 0)
        }
        context.contentResolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                .asCalDavSyncAdapter(accountId),
            values,
            null,
            null,
        )
    }

    private fun deleteLocalEvent(
        eventId: Long,
        accountId: String,
    ) {
        context.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                .asCalDavSyncAdapter(accountId),
            null,
            null,
        )
    }

    private fun deleteCalendar(calendar: CalDavCalendar) {
        val localCalendarId = calendar.localCalendarId ?: return
        if (!calendarExists(calendar)) return

        context.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, localCalendarId)
                .asCalDavSyncAdapter(calendar.accountId),
            null,
            null,
        )
    }

    private fun calendarExists(calendar: CalDavCalendar): Boolean {
        val localCalendarId = calendar.localCalendarId ?: return false
        return context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            CalendarExistsProjection,
            "${CalendarContract.Calendars._ID} = ? AND " +
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND " +
                "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(localCalendarId.toString(), calendar.accountId, CalDavAccountType),
            null,
        )?.use { it.moveToFirst() } ?: false
    }

    private fun IcsCalendarEvent.toContentValues(
        calendar: CalDavCalendar,
        accountId: String,
        href: String,
        etag: String?,
    ): ContentValues {
        val end = resolvedEnd()
        val recurrenceDuration = if (recurrenceRule != null || recurrenceDates != null) {
            resolvedDuration(end)
        } else {
            null
        }
        val eventRemoteId = "$href|$remoteId"
        val localCalendarId = requireNotNull(calendar.localCalendarId) {
            "CalDAV event collection does not have an Android calendar."
        }

        return ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, localCalendarId)
            put(CalendarContract.Events.TITLE, title.ifBlank { "Untitled" })
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DTSTART, start.epochMillis)
            put(CalendarContract.Events.ALL_DAY, if (start.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, start.timeZone)
            put(CalendarContract.Events.EVENT_END_TIMEZONE, end.timeZone)
            put(CalendarContract.Events.AVAILABILITY, providerAvailability())
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
            put(CalendarContract.Events.HAS_ALARM, 0)
            put(CalendarContract.Events.UID_2445, uid)
            put(CalendarContract.Events._SYNC_ID, eventRemoteId)
            put(CalendarContract.Events.SYNC_DATA1, accountId)
            put(CalendarContract.Events.SYNC_DATA2, href)
            putNullableString(CalendarContract.Events.SYNC_DATA3, etag)
            put(CalendarContract.Events.DIRTY, 0)
            put(CalendarContract.Events.DELETED, 0)

            if (recurrenceDuration != null) {
                putNull(CalendarContract.Events.DTEND)
                put(CalendarContract.Events.DURATION, recurrenceDuration)
            } else {
                put(CalendarContract.Events.DTEND, end.epochMillis)
                putNull(CalendarContract.Events.DURATION)
            }

            putNullableString(CalendarContract.Events.RRULE, recurrenceRule)
            putNullableString(CalendarContract.Events.RDATE, recurrenceDates)
            putNullableString(CalendarContract.Events.EXRULE, exceptionRule)
            putNullableString(CalendarContract.Events.EXDATE, exceptionDates)
        }
    }

    private fun IcsCalendarEvent.resolvedEnd(): IcsEventDateTime {
        end?.let { return it }

        val defaultDuration = duration ?: if (start.allDay) {
            Duration.ofDays(1)
        } else {
            Duration.ofHours(1)
        }

        return start.copy(epochMillis = start.epochMillis + defaultDuration.toMillis())
    }

    private fun IcsCalendarEvent.resolvedDuration(end: IcsEventDateTime): String {
        duration?.let { return it.toAndroidDuration(start.allDay) }
        val computedDuration = Duration.ofMillis(end.epochMillis - start.epochMillis)
        return computedDuration.toAndroidDuration(start.allDay)
    }

    private fun Duration.toAndroidDuration(allDay: Boolean): String {
        val seconds = seconds.coerceAtLeast(0)
        if (allDay && seconds % SecondsPerDay == 0L) {
            return "P${seconds / SecondsPerDay}D"
        }

        val hours = seconds / SecondsPerHour
        val minutes = (seconds % SecondsPerHour) / SecondsPerMinute
        val remainingSeconds = seconds % SecondsPerMinute
        return buildString {
            append("P")
            append("T")
            if (hours > 0) append(hours).append("H")
            if (minutes > 0) append(minutes).append("M")
            if (remainingSeconds > 0 || (hours == 0L && minutes == 0L)) {
                append(remainingSeconds).append("S")
            }
        }
    }

    private fun IcsCalendarEvent.providerAvailability(): Int {
        return if (transparency.equals("TRANSPARENT", ignoreCase = true)) {
            CalendarContract.Events.AVAILABILITY_FREE
        } else {
            CalendarContract.Events.AVAILABILITY_BUSY
        }
    }

    private fun IcsCalendarEvent.storedLocation(): StoredEventLocation? {
        val point = geoPoint ?: return null
        return StoredEventLocation(
            point = point,
            name = locationMapName?.takeIf { it.isNotBlank() } ?: location,
            mapId = locationMapId?.takeIf { it.isNotBlank() },
        )
    }

    private fun LocalCalDavEvent.toIcs(uid: String): String {
        val now = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        return buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//Essentials Calendar//Android//EN")
            appendLine("BEGIN:VEVENT")
            appendLine("UID:${uid.escapeIcsText()}")
            appendLine("DTSTAMP:$now")
            appendLine("SUMMARY:${title.escapeIcsText()}")
            location?.takeIf { it.isNotBlank() }?.let { appendLine("LOCATION:${it.escapeIcsText()}") }
            geoPoint?.let { appendLine("GEO:${it.latitude};${it.longitude}") }
            locationMapName?.takeIf { it.isNotBlank() }?.let {
                appendLine("X-ESSENTIALS-MAP-NAME:${it.escapeIcsText()}")
            }
            locationMapId?.takeIf { it.isNotBlank() }?.let {
                appendLine("X-ESSENTIALS-MAP-ID:${it.escapeIcsText()}")
            }
            description?.takeIf { it.isNotBlank() }?.let { appendLine("DESCRIPTION:${it.escapeIcsText()}") }
            appendEventDate("DTSTART", startMillis, allDay, timeZone)
            appendEventDate("DTEND", endMillis, allDay, timeZone)
            recurrenceRule?.takeIf { it.isNotBlank() }?.let { appendLine("RRULE:$it") }
            appendLine("TRANSP:${if (availability == CalendarContract.Events.AVAILABILITY_FREE) "TRANSPARENT" else "OPAQUE"}")
            appendLine("END:VEVENT")
            appendLine("END:VCALENDAR")
        }
    }

    private fun CalendarTaskDraft.toTaskIcs(uid: String): String {
        val now = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        return buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//Essentials Calendar//Android//EN")
            appendLine("BEGIN:VTODO")
            appendLine("UID:${uid.escapeIcsText()}")
            appendLine("DTSTAMP:$now")
            appendLine("CREATED:$now")
            appendLine("LAST-MODIFIED:$now")
            appendLine("STATUS:NEEDS-ACTION")
            appendLine("SUMMARY:${title.trim().escapeIcsText()}")
            description?.takeIf { it.isNotBlank() }?.let {
                appendLine("DESCRIPTION:${it.escapeIcsText()}")
            }
            priority?.let { appendLine("PRIORITY:$it") }
            appendTaskDate("DUE", dueMillis, dueAllDay, timeZone)
            recurrenceRule?.takeIf { it.isNotBlank() }?.let { appendLine("RRULE:$it") }
            appendLine("END:VTODO")
            appendLine("END:VCALENDAR")
        }
    }

    private fun CalendarTask.toTaskIcs(): String {
        val now = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        return buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//Essentials Calendar//Android//EN")
            appendLine("BEGIN:VTODO")
            appendLine("UID:${uid.escapeIcsText()}")
            appendLine("DTSTAMP:$now")
            appendLine("CREATED:${formatUtcIcsDateTime(createdMillis ?: lastModifiedMillis ?: System.currentTimeMillis())}")
            appendLine("LAST-MODIFIED:$now")
            appendLine("STATUS:${status.toIcsStatus()}")
            completedMillis?.let { appendLine("COMPLETED:${formatUtcIcsDateTime(it)}") }
            appendLine("SUMMARY:${title.trim().escapeIcsText()}")
            description?.takeIf { it.isNotBlank() }?.let {
                appendLine("DESCRIPTION:${it.escapeIcsText()}")
            }
            priority?.let { appendLine("PRIORITY:$it") }
            dueMillis?.let { appendTaskDate("DUE", it, dueAllDay, ZoneId.systemDefault().id) }
            startMillis?.let { appendTaskDate("DTSTART", it, startAllDay, ZoneId.systemDefault().id) }
            recurrenceRule?.takeIf { it.isNotBlank() }?.let { appendLine("RRULE:$it") }
            appendLine("END:VTODO")
            appendLine("END:VCALENDAR")
        }
    }

    private fun StringBuilder.appendTaskDate(
        property: String,
        epochMillis: Long,
        allDay: Boolean,
        timeZone: String,
    ) {
        if (allDay) {
            val date = Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
            appendLine("$property;VALUE=DATE:${date.format(DateTimeFormatter.BASIC_ISO_DATE)}")
        } else {
            val zone = runCatching { ZoneId.of(timeZone) }.getOrNull() ?: ZoneId.systemDefault()
            val dateTime = Instant.ofEpochMilli(epochMillis)
                .atZone(zone)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            appendLine("$property;TZID=${zone.id}:$dateTime")
        }
    }

    private fun StringBuilder.appendEventDate(
        property: String,
        epochMillis: Long,
        allDay: Boolean,
        timeZone: String?,
    ) {
        if (allDay) {
            val date = Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
            appendLine("$property;VALUE=DATE:${date.format(DateTimeFormatter.BASIC_ISO_DATE)}")
        } else {
            val zone = timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
            val dateTime = Instant.ofEpochMilli(epochMillis)
                .atZone(zone)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            appendLine("$property;TZID=${zone.id}:$dateTime")
        }
    }

    private fun String.escapeIcsText(): String {
        return replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace(";", "\\;")
            .replace(",", "\\,")
    }

    private fun CalendarTaskStatus.toIcsStatus(): String {
        return when (this) {
            CalendarTaskStatus.NeedsAction -> "NEEDS-ACTION"
            CalendarTaskStatus.InProcess -> "IN-PROCESS"
            CalendarTaskStatus.Completed -> "COMPLETED"
            CalendarTaskStatus.Cancelled -> "CANCELLED"
            CalendarTaskStatus.Unknown -> "NEEDS-ACTION"
        }
    }

    private fun formatUtcIcsDateTime(epochMillis: Long): String {
        return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(epochMillis))
    }

    private fun Cursor.toLocalCalDavEvent(): LocalCalDavEvent {
        return LocalCalDavEvent(
            id = requireLong(CalendarContract.Events._ID),
            title = optionalString(CalendarContract.Events.TITLE) ?: "Untitled",
            location = optionalString(CalendarContract.Events.EVENT_LOCATION),
            description = optionalString(CalendarContract.Events.DESCRIPTION),
            startMillis = requireLong(CalendarContract.Events.DTSTART),
            endMillis = optionalLong(CalendarContract.Events.DTEND)
                ?: requireLong(CalendarContract.Events.DTSTART) + Duration.ofHours(1).toMillis(),
            allDay = optionalInt(CalendarContract.Events.ALL_DAY) == 1,
            timeZone = optionalString(CalendarContract.Events.EVENT_TIMEZONE),
            availability = optionalInt(CalendarContract.Events.AVAILABILITY)
                ?: CalendarContract.Events.AVAILABILITY_BUSY,
            recurrenceRule = optionalString(CalendarContract.Events.RRULE),
            uid = optionalString(CalendarContract.Events.UID_2445),
            href = optionalString(CalendarContract.Events.SYNC_DATA2),
            etag = optionalString(CalendarContract.Events.SYNC_DATA3),
            deleted = optionalInt(CalendarContract.Events.DELETED) == 1,
            geoPoint = null,
            locationMapName = null,
            locationMapId = null,
        )
    }

    private fun normalizeCalDavUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val uri = Uri.parse(trimmed)
        require(uri.scheme == "http" || uri.scheme == "https") {
            "Use an HTTP or HTTPS CalDAV server URL."
        }
        require(!uri.host.isNullOrBlank()) { "Use a CalDAV URL with a host." }
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    private fun CalDavAccount.endpoint(): CalDavEndpoint {
        return CalDavEndpoint(
            baseUrl = baseUrl,
            username = username,
            password = password,
        )
    }

    private fun requireCalendarPermissions() {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Calendar read and write permissions have not been granted.")
        }
    }

    private fun Cursor.requireLong(columnName: String): Long {
        return getLong(getColumnIndexOrThrow(columnName))
    }

    private fun Cursor.optionalLong(columnName: String): Long? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private fun Cursor.optionalInt(columnName: String): Int? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getInt(index) else null
    }

    private fun Cursor.optionalString(columnName: String): String? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private data class RemoteParsedEvent(
        val event: IcsCalendarEvent,
        val href: String,
        val etag: String?,
    ) {
        val remoteId = "$href|${event.remoteId}"
    }

    private data class LocalCalDavEvent(
        val id: Long,
        val title: String,
        val location: String?,
        val description: String?,
        val startMillis: Long,
        val endMillis: Long,
        val allDay: Boolean,
        val timeZone: String?,
        val availability: Int,
        val recurrenceRule: String?,
        val uid: String?,
        val href: String?,
        val etag: String?,
        val deleted: Boolean,
        val geoPoint: GeoPoint?,
        val locationMapName: String?,
        val locationMapId: String?,
    )

    private companion object {
        val CalendarExistsProjection = arrayOf(CalendarContract.Calendars._ID)
        val ExistingEventProjection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events._SYNC_ID,
            CalendarContract.Events.SYNC_DATA2,
        )
        val LocalEventProjection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.AVAILABILITY,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.UID_2445,
            CalendarContract.Events.SYNC_DATA2,
            CalendarContract.Events.SYNC_DATA3,
            CalendarContract.Events.DELETED,
        )
        val DirtyEventProjection = arrayOf(
            CalendarContract.Events._SYNC_ID,
            CalendarContract.Events.SYNC_DATA2,
        )
        const val SecondsPerMinute = 60L
        const val SecondsPerHour = 60L * SecondsPerMinute
        const val SecondsPerDay = 24L * SecondsPerHour
        val DefaultCalDavCalendarColor: Int = Color.rgb(0x1E, 0x88, 0xE5)
        val DefaultTaskListColor: Int = Color.rgb(0x7E, 0x57, 0xC2)
    }
}

private fun ContentValues.putNullableString(
    key: String,
    value: String?,
) {
    if (value == null) {
        putNull(key)
    } else {
        put(key, value)
    }
}

private fun List<CalDavCalendar>.preferredCalDavCalendar(): CalDavCalendar? {
    return maxWithOrNull(
        compareBy<CalDavCalendar> { it.lastSyncMillis ?: Long.MIN_VALUE }
            .thenBy { it.id },
    )
}

private fun CalDavSyncSummary.hasChanges(): Boolean {
    return created > 0 || updated > 0 || deleted > 0 || conflicts > 0
}

private fun CalDavCalendar.incrementalSyncToken(): String? {
    if (lastSyncMillis == null) return null
    return syncToken?.takeIf { it.isNotBlank() }
}

private fun calDavCalendarId(
    accountId: String,
    href: String,
): String {
    return UUID.nameUUIDFromBytes(
        "$accountId|${href.trimEnd('/')}".toByteArray(Charsets.UTF_8),
    ).toString()
}

private fun Uri.asCalDavSyncAdapter(accountId: String): Uri {
    return buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountId)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalDavAccountType)
        .build()
}
