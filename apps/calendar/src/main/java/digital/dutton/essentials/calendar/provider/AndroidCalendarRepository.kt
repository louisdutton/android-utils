package digital.dutton.essentials.calendar.provider

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import digital.dutton.essentials.calendar.data.CalendarEvent
import digital.dutton.essentials.calendar.data.CalendarEventDraft
import digital.dutton.essentials.calendar.data.CalendarRepository
import digital.dutton.essentials.calendar.data.CalendarSource
import digital.dutton.essentials.calendar.data.EventAvailability
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidCalendarRepository(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CalendarRepository {
    override suspend fun listCalendars(): List<CalendarSource> = withContext(dispatcher) {
        requireCalendarReadPermission()

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            CalendarProjection,
            null,
            null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toCalendarSource())
                }
            }
        }.orEmpty()
    }

    override suspend fun listEvents(
        startMillis: Long,
        endMillis: Long,
    ): List<CalendarEvent> = withContext(dispatcher) {
        requireCalendarReadPermission()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMillis.toString())
            .appendPath(endMillis.toString())
            .build()

        context.contentResolver.query(
            uri,
            EventProjection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toCalendarEvent())
                }
            }
        }.orEmpty()
    }

    override suspend fun createEvent(event: CalendarEventDraft): Long = withContext(dispatcher) {
        requireCalendarWritePermission()

        val uri = context.contentResolver.insert(
            CalendarContract.Events.CONTENT_URI,
            event.toContentValues(),
        ) ?: throw IllegalStateException("Android Calendar Provider did not return a new event URI.")

        ContentUris.parseId(uri)
    }

    override suspend fun updateEvent(
        eventId: Long,
        event: CalendarEventDraft,
    ) {
        withContext(dispatcher) {
            requireCalendarWritePermission()

            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val updatedRows = context.contentResolver.update(uri, event.toContentValues(), null, null)
            if (updatedRows == 0) {
                throw IllegalStateException("Event was not updated.")
            }
        }
    }

    override suspend fun deleteEvent(eventId: Long) {
        withContext(dispatcher) {
            requireCalendarWritePermission()

            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val deletedRows = context.contentResolver.delete(uri, null, null)
            if (deletedRows == 0) {
                throw IllegalStateException("Event was not deleted.")
            }
        }
    }

    private fun requireCalendarReadPermission() {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Calendar read permission has not been granted.")
        }
    }

    private fun requireCalendarWritePermission() {
        if (context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Calendar write permission has not been granted.")
        }
    }

    private fun CalendarEventDraft.toContentValues(): ContentValues {
        return ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title.ifBlank { "Untitled" })
            put(CalendarContract.Events.EVENT_LOCATION, location?.takeIf { it.isNotBlank() })
            put(CalendarContract.Events.DESCRIPTION, description?.takeIf { it.isNotBlank() })
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, timeZone)
            put(CalendarContract.Events.EVENT_END_TIMEZONE, timeZone)
            put(CalendarContract.Events.AVAILABILITY, availability.toProviderValue())
        }
    }

    private fun EventAvailability.toProviderValue(): Int {
        return when (this) {
            EventAvailability.Busy -> CalendarContract.Events.AVAILABILITY_BUSY
            EventAvailability.Free -> CalendarContract.Events.AVAILABILITY_FREE
            EventAvailability.Tentative -> CalendarContract.Events.AVAILABILITY_TENTATIVE
            EventAvailability.Unknown -> CalendarContract.Events.AVAILABILITY_BUSY
        }
    }

    private fun Cursor.toCalendarSource(): CalendarSource {
        return CalendarSource(
            id = requireLong(CalendarContract.Calendars._ID),
            displayName = optionalString(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                ?: optionalString(CalendarContract.Calendars.NAME)
                ?: "Calendar",
            accountName = optionalString(CalendarContract.Calendars.ACCOUNT_NAME),
            accountType = optionalString(CalendarContract.Calendars.ACCOUNT_TYPE),
            color = optionalInt(CalendarContract.Calendars.CALENDAR_COLOR),
            isPrimary = optionalInt(CalendarContract.Calendars.IS_PRIMARY) == 1,
            isVisible = optionalInt(CalendarContract.Calendars.VISIBLE) != 0,
        )
    }

    private fun Cursor.toCalendarEvent(): CalendarEvent {
        return CalendarEvent(
            id = requireLong(CalendarContract.Instances.EVENT_ID),
            calendarId = requireLong(CalendarContract.Instances.CALENDAR_ID),
            calendarName = optionalString(CalendarContract.Instances.CALENDAR_DISPLAY_NAME),
            calendarColor = optionalInt(CalendarContract.Instances.CALENDAR_COLOR),
            title = optionalString(CalendarContract.Instances.TITLE) ?: "Untitled",
            location = optionalString(CalendarContract.Instances.EVENT_LOCATION),
            description = optionalString(CalendarContract.Instances.DESCRIPTION),
            startMillis = requireLong(CalendarContract.Instances.BEGIN),
            endMillis = requireLong(CalendarContract.Instances.END),
            allDay = optionalInt(CalendarContract.Instances.ALL_DAY) == 1,
            timeZone = optionalString(CalendarContract.Instances.EVENT_TIMEZONE),
            recurrenceRule = optionalString(CalendarContract.Instances.RRULE),
            availability = when (optionalInt(CalendarContract.Instances.AVAILABILITY)) {
                CalendarContract.Events.AVAILABILITY_BUSY -> EventAvailability.Busy
                CalendarContract.Events.AVAILABILITY_FREE -> EventAvailability.Free
                CalendarContract.Events.AVAILABILITY_TENTATIVE -> EventAvailability.Tentative
                else -> EventAvailability.Unknown
            },
        )
    }

    private fun Cursor.requireLong(columnName: String): Long {
        return getLong(getColumnIndexOrThrow(columnName))
    }

    private fun Cursor.optionalInt(columnName: String): Int? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getInt(index) else null
    }

    private fun Cursor.optionalString(columnName: String): String? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private companion object {
        val CalendarProjection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE,
        )

        val EventProjection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.CALENDAR_COLOR,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_TIMEZONE,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.AVAILABILITY,
        )
    }
}
