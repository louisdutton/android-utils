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
import digital.dutton.essentials.calendar.provider.CalendarEventLocationStore
import digital.dutton.essentials.calendar.provider.StoredEventLocation
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IcsSubscriptionSyncer(
    private val context: Context,
    private val store: CalendarSubscriptionStore = CalendarSubscriptionStore(context),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val parser: IcsCalendarParser = IcsCalendarParser(),
) {
    private val eventLocationStore = CalendarEventLocationStore(context)

    suspend fun subscribe(
        rawUrl: String,
        requestedName: String?,
    ): IcsSubscriptionSyncSummary = withContext(dispatcher) {
        requireCalendarPermissions()
        val url = normalizeSubscriptionUrl(rawUrl)
        pruneDuplicateSubscriptions()
        val existingSubscription = findExistingSubscription(url)
        if (existingSubscription != null) {
            return@withContext syncSubscription(existingSubscription.id)
        }

        val fetch = fetchCalendar(url = url, etag = null, lastModified = null)
        val feed = parser.parse(fetch.body ?: "")
        val displayName = requestedName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: feed.displayName
            ?: URL(url).host.takeIf { it.isNotBlank() }
            ?: "Subscribed calendar"
        var calendarId: Long? = null

        try {
            calendarId = createSubscriptionCalendar(displayName)
            val subscription = CalendarSubscription(
                id = UUID.randomUUID().toString(),
                url = url,
                displayName = displayName,
                calendarId = calendarId,
                lastEtag = fetch.etag,
                lastModified = fetch.lastModified,
            )
            val summary = writeEvents(subscription, feed.events)
            store.upsert(
                subscription.copy(
                    lastSyncMillis = System.currentTimeMillis(),
                    lastError = null,
                ),
            )
            summary
        } catch (error: Throwable) {
            calendarId?.let { deleteCalendar(it) }
            throw error
        }
    }

    suspend fun syncSubscription(subscriptionId: String): IcsSubscriptionSyncSummary = withContext(dispatcher) {
        requireCalendarPermissions()
        val storedSubscription = store.get(subscriptionId)
            ?: throw IllegalArgumentException("Calendar subscription was not found.")
        var activeSubscription = storedSubscription

        runCatching {
            activeSubscription = ensureSubscriptionCalendar(activeSubscription)
            val fetch = fetchCalendar(
                url = activeSubscription.url,
                etag = activeSubscription.lastEtag,
                lastModified = activeSubscription.lastModified,
            )

            if (fetch.notModified) {
                val updatedSubscription = activeSubscription.copy(
                    lastSyncMillis = System.currentTimeMillis(),
                    lastError = null,
                )
                store.upsert(updatedSubscription)
                IcsSubscriptionSyncSummary(
                    subscriptionId = activeSubscription.id,
                    created = 0,
                    updated = 0,
                    deleted = 0,
                    skipped = 0,
                )
            } else {
                val feed = parser.parse(fetch.body ?: "")
                val summary = writeEvents(activeSubscription, feed.events)
                store.upsert(
                    activeSubscription.copy(
                        lastEtag = fetch.etag ?: activeSubscription.lastEtag,
                        lastModified = fetch.lastModified ?: activeSubscription.lastModified,
                        lastSyncMillis = System.currentTimeMillis(),
                        lastError = null,
                    ),
                )
                summary
            }
        }.getOrElse { error ->
            store.upsert(activeSubscription.copy(lastError = error.message ?: "Unable to sync calendar."))
            throw error
        }
    }

    suspend fun syncAll(): List<IcsSubscriptionSyncSummary> = withContext(dispatcher) {
        pruneDuplicateSubscriptions()
        val subscriptions = store.list()
        val summaries = mutableListOf<IcsSubscriptionSyncSummary>()
        val failures = mutableListOf<Throwable>()

        subscriptions.forEach { subscription ->
            runCatching {
                syncSubscription(subscription.id)
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

    suspend fun unsubscribe(subscriptionId: String) = withContext(dispatcher) {
        requireCalendarPermissions()
        val subscription = store.get(subscriptionId)
            ?: return@withContext

        deleteCalendar(subscription.calendarId)
        store.remove(subscription.id)
    }

    suspend fun renameSubscription(
        subscriptionId: String,
        displayName: String,
    ) = withContext(dispatcher) {
        requireCalendarPermissions()
        val cleanedDisplayName = displayName.trim()
        require(cleanedDisplayName.isNotBlank()) { "Add a calendar name." }
        val subscription = store.get(subscriptionId)
            ?: throw IllegalArgumentException("Calendar subscription was not found.")

        updateSubscriptionCalendarName(subscription.calendarId, cleanedDisplayName)
        store.upsert(subscription.copy(displayName = cleanedDisplayName))
    }

    suspend fun repairSubscriptions() = withContext(dispatcher) {
        requireCalendarPermissions()
        pruneDuplicateSubscriptions()
        val subscriptions = store.list()
        subscriptions
            .filter { calendarExists(it.calendarId) }
            .forEach { updateSubscriptionCalendarName(it.calendarId, it.displayName) }
        pruneOrphanedProviderEvents(subscriptions)
        subscriptions.forEach(::pruneDuplicateProviderEvents)
        subscriptions
            .filter { !calendarExists(it.calendarId) }
            .forEach { subscription ->
                runCatching { syncSubscription(subscription.id) }
            }
    }

    private fun pruneDuplicateSubscriptions() {
        store.rawList()
            .groupBy { it.url }
            .values
            .filter { it.size > 1 }
            .forEach { subscriptions ->
                val keep = subscriptions.preferredSubscription()
                subscriptions
                    .filter { it.id != keep.id }
                    .forEach { duplicate ->
                        deleteEventsForSubscription(duplicate)
                        if (duplicate.calendarId != keep.calendarId && calendarExists(duplicate.calendarId)) {
                            deleteCalendar(duplicate.calendarId)
                        }
                        store.remove(duplicate.id)
                    }
            }
    }

    private fun findExistingSubscription(url: String): CalendarSubscription? {
        val subscriptions = store.findByUrl(url)
        val existingSubscriptions = subscriptions.filter { calendarExists(it.calendarId) }
        subscriptions
            .filter { it !in existingSubscriptions }
            .forEach { store.remove(it.id) }

        return existingSubscriptions.takeIf { it.isNotEmpty() }?.preferredSubscription()
    }

    private fun List<CalendarSubscription>.preferredSubscription(): CalendarSubscription {
        return maxWithOrNull(
            compareBy<CalendarSubscription> { existingSyncedEventCount(it) }
                .thenBy { if (calendarExists(it.calendarId)) 1 else 0 }
                .thenBy { it.lastSyncMillis ?: Long.MIN_VALUE }
                .thenBy { it.id },
        ) ?: throw IllegalArgumentException("No calendar subscriptions to compare.")
    }

    private fun ensureSubscriptionCalendar(subscription: CalendarSubscription): CalendarSubscription {
        if (calendarExists(subscription.calendarId)) {
            updateSubscriptionCalendarName(subscription.calendarId, subscription.displayName)
            return subscription
        }

        val repairedSubscription = subscription.copy(
            calendarId = createSubscriptionCalendar(subscription.displayName),
            lastEtag = null,
            lastModified = null,
            lastSyncMillis = null,
        )
        store.upsert(repairedSubscription)
        return repairedSubscription
    }

    private fun writeEvents(
        subscription: CalendarSubscription,
        events: List<IcsCalendarEvent>,
    ): IcsSubscriptionSyncSummary {
        val uniqueEvents = events.distinctBy { it.remoteId }
        val existing = queryExistingEvents(subscription)
        val incomingIds = uniqueEvents.map { it.remoteId }.toSet()
        var created = 0
        var updated = 0
        var skipped = 0

        uniqueEvents.forEach { event ->
            val values = event.toContentValues(subscription)
            val existingId = existing[event.remoteId]

            if (existingId == null) {
                val uri = context.contentResolver.insert(
                    CalendarContract.Events.CONTENT_URI.asSubscriptionSyncAdapter(),
                    values,
                )
                if (uri == null) {
                    skipped += 1
                } else {
                    eventLocationStore.put(ContentUris.parseId(uri), event.storedLocation())
                    created += 1
                }
            } else {
                val rows = context.contentResolver.update(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingId)
                        .asSubscriptionSyncAdapter(),
                    values,
                    null,
                    null,
                )
                if (rows > 0) {
                    eventLocationStore.put(existingId, event.storedLocation())
                    updated += 1
                } else {
                    skipped += 1
                }
            }
        }

        var deleted = 0
        existing
            .filterKeys { it !in incomingIds }
            .values
            .forEach { eventId ->
                deleted += context.contentResolver.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                        .asSubscriptionSyncAdapter(),
                    null,
                    null,
                )
            }

        return IcsSubscriptionSyncSummary(
            subscriptionId = subscription.id,
            created = created,
            updated = updated,
            deleted = deleted,
            skipped = skipped,
        )
    }

    private fun queryExistingEvents(subscription: CalendarSubscription): Map<String, Long> {
        val existingEvents = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI.asSubscriptionSyncAdapter(),
            ExistingEventProjection,
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.SYNC_DATA1} = ?",
            arrayOf(subscription.calendarId.toString(), subscription.id),
            null,
        )?.use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val eventId = cursor.requireLong(CalendarContract.Events._ID)
                    val remoteId = cursor.optionalString(CalendarContract.Events.SYNC_DATA2)
                    if (remoteId != null) put(eventId, remoteId)
                }
            }
        }.orEmpty()

        return existingEvents
            .entries
            .groupBy({ it.value }, { it.key })
            .mapValues { (_, eventIds) ->
                val keepId = eventIds.maxOrNull()
                    ?: throw IllegalArgumentException("No existing events to compare.")
                eventIds
                    .filter { it != keepId }
                    .forEach(::deleteEvent)
                keepId
            }
    }

    private fun pruneDuplicateProviderEvents(subscription: CalendarSubscription) {
        queryExistingEvents(subscription)
    }

    private fun pruneOrphanedProviderEvents(activeSubscriptions: List<CalendarSubscription>) {
        val activeSubscriptionIds = activeSubscriptions.map { it.id }.toSet()
        subscriptionCalendarIds().forEach { calendarId ->
            val orphanedEventIds = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI.asSubscriptionSyncAdapter(),
                OrphanEventProjection,
                "${CalendarContract.Events.CALENDAR_ID} = ?",
                arrayOf(calendarId.toString()),
                null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val eventId = cursor.requireLong(CalendarContract.Events._ID)
                        val subscriptionId = cursor.optionalString(CalendarContract.Events.SYNC_DATA1)
                        if (subscriptionId !in activeSubscriptionIds) {
                            add(eventId)
                        }
                    }
                }
            }.orEmpty()
            orphanedEventIds.forEach(::deleteEvent)
        }
    }

    private fun subscriptionCalendarIds(): Set<Long> {
        return context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            CalendarExistsProjection,
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(SubscriptionAccountType),
            null,
        )?.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(cursor.requireLong(CalendarContract.Calendars._ID))
                }
            }
        }.orEmpty()
    }

    private fun deleteEventsForSubscription(subscription: CalendarSubscription) {
        context.contentResolver.delete(
            CalendarContract.Events.CONTENT_URI.asSubscriptionSyncAdapter(),
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.SYNC_DATA1} = ?",
            arrayOf(subscription.calendarId.toString(), subscription.id),
        )
    }

    private fun deleteEvent(eventId: Long) {
        context.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                .asSubscriptionSyncAdapter(),
            null,
            null,
        )
    }

    private fun IcsCalendarEvent.toContentValues(subscription: CalendarSubscription): ContentValues {
        val end = resolvedEnd()
        val recurrenceDuration = if (recurrenceRule != null || recurrenceDates != null) {
            resolvedDuration(end)
        } else {
            null
        }

        return ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, subscription.calendarId)
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
            put(CalendarContract.Events._SYNC_ID, remoteId)
            put(CalendarContract.Events.SYNC_DATA1, subscription.id)
            put(CalendarContract.Events.SYNC_DATA2, remoteId)

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

    private fun createSubscriptionCalendar(displayName: String): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, SubscriptionAccountName)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, SubscriptionAccountType)
            put(CalendarContract.Calendars.NAME, displayName)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, SubscriptionAccountName)
            put(CalendarContract.Calendars.CALENDAR_COLOR, SubscriptionCalendarColor)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_READ)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CAN_MODIFY_TIME_ZONE, 0)
            put(CalendarContract.Calendars.CAN_ORGANIZER_RESPOND, 0)
        }

        val uri = context.contentResolver.insert(
            CalendarContract.Calendars.CONTENT_URI.asSubscriptionSyncAdapter(),
            values,
        ) ?: throw IllegalStateException("Android Calendar Provider did not return a calendar URI.")

        return ContentUris.parseId(uri)
    }

    private fun calendarExists(calendarId: Long): Boolean {
        return context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            CalendarExistsProjection,
            "${CalendarContract.Calendars._ID} = ? AND " +
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND " +
                "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(calendarId.toString(), SubscriptionAccountName, SubscriptionAccountType),
            null,
        )?.use { it.moveToFirst() } ?: false
    }

    private fun existingSyncedEventCount(subscription: CalendarSubscription): Int {
        return context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI.asSubscriptionSyncAdapter(),
            EventCountProjection,
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.SYNC_DATA1} = ?",
            arrayOf(subscription.calendarId.toString(), subscription.id),
            null,
        )?.use { it.count } ?: 0
    }

    private fun deleteCalendar(calendarId: Long) {
        if (!calendarExists(calendarId)) return

        context.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)
                .asSubscriptionSyncAdapter(),
            null,
            null,
        )
    }

    private fun updateSubscriptionCalendarName(
        calendarId: Long,
        displayName: String,
    ) {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.NAME, displayName)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
        }
        context.contentResolver.update(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)
                .asSubscriptionSyncAdapter(),
            values,
            null,
            null,
        )
    }

    private fun fetchCalendar(
        url: String,
        etag: String?,
        lastModified: String?,
    ): CalendarFetchResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = NetworkTimeoutMillis
            readTimeout = NetworkTimeoutMillis
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Essentials Calendar/0.1")
            etag?.let { setRequestProperty("If-None-Match", it) }
            lastModified?.let { setRequestProperty("If-Modified-Since", it) }
        }

        return connection.use {
            when (val code = responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> CalendarFetchResult(
                    notModified = true,
                    body = null,
                    etag = headerField("ETag"),
                    lastModified = headerField("Last-Modified"),
                )
                in 200..299 -> CalendarFetchResult(
                    notModified = false,
                    body = inputStream.readTextWithLimit(MaxCalendarBytes),
                    etag = headerField("ETag"),
                    lastModified = headerField("Last-Modified"),
                )
                else -> {
                    val message = errorStream?.readTextWithLimit(ErrorBodyBytes)?.takeIf { it.isNotBlank() }
                    throw IllegalStateException("Calendar fetch failed with HTTP $code${message?.let { ": $it" }.orEmpty()}")
                }
            }
        }
    }

    private fun normalizeSubscriptionUrl(rawUrl: String): String {
        val uri = Uri.parse(rawUrl.trim())
        val scheme = uri.scheme?.lowercase()
        val normalized = when (scheme) {
            "http" -> uri
            "https" -> uri
            "webcal" -> uri.buildUpon().scheme("http").build()
            "webcals" -> uri.buildUpon().scheme("https").build()
            else -> throw IllegalArgumentException("Use an HTTP, HTTPS, or webcal calendar URL.")
        }

        if (normalized.host.isNullOrBlank()) {
            throw IllegalArgumentException("Use a calendar URL with a host.")
        }

        return normalized.toString()
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

    private fun Cursor.optionalString(columnName: String): String? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private companion object {
        val ExistingEventProjection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.SYNC_DATA2,
        )
        val OrphanEventProjection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.SYNC_DATA1,
        )
        val CalendarExistsProjection = arrayOf(CalendarContract.Calendars._ID)
        val EventCountProjection = arrayOf(CalendarContract.Events._ID)
        const val NetworkTimeoutMillis = 20_000
        const val MaxCalendarBytes = 5 * 1024 * 1024
        const val ErrorBodyBytes = 8 * 1024
        const val SecondsPerMinute = 60L
        const val SecondsPerHour = 60L * SecondsPerMinute
        const val SecondsPerDay = 24L * SecondsPerHour
        val SubscriptionCalendarColor: Int = Color.rgb(0x2E, 0x7D, 0x62)
    }
}

private data class CalendarFetchResult(
    val notModified: Boolean,
    val body: String?,
    val etag: String?,
    val lastModified: String?,
)

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

private fun Uri.asSubscriptionSyncAdapter(): Uri {
    return buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, SubscriptionAccountName)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, SubscriptionAccountType)
        .build()
}

private fun HttpURLConnection.headerField(name: String): String? {
    return getHeaderField(name)?.takeIf { it.isNotBlank() }
}

private fun HttpURLConnection.use(block: HttpURLConnection.() -> CalendarFetchResult): CalendarFetchResult {
    return try {
        block()
    } finally {
        disconnect()
    }
}

private fun InputStream.readTextWithLimit(maxBytes: Int): String {
    use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0

        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                throw IllegalStateException("Calendar feed is larger than ${maxBytes / 1024 / 1024} MB.")
            }
            output.write(buffer, 0, read)
        }

        return output.toString(Charsets.UTF_8.name())
    }
}
