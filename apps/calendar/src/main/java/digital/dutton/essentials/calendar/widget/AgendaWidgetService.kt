package digital.dutton.essentials.calendar.widget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import digital.dutton.essentials.calendar.R
import digital.dutton.essentials.calendar.data.CalendarTask
import digital.dutton.essentials.calendar.data.CalendarTaskStatus
import digital.dutton.essentials.calendar.provider.CalendarTaskStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class AgendaWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsService.RemoteViewsFactory {
        return AgendaWidgetFactory(applicationContext)
    }
}

private class AgendaWidgetFactory(
    private val context: Context,
) : RemoteViewsService.RemoteViewsFactory {
    private var rows: List<WidgetAgendaRow> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        rows = runCatching { loadAgendaRows(context) }
            .getOrElse { listOf(WidgetAgendaRow.status("Calendar unavailable")) }
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        return when (val row = rows.getOrNull(position) ?: WidgetAgendaRow.status("Calendar unavailable")) {
            is WidgetAgendaRow.Month -> RemoteViews(context.packageName, R.layout.widget_agenda_month_junction).apply {
                setTextViewText(R.id.widget_month_label, row.label)
            }

            is WidgetAgendaRow.Item -> RemoteViews(context.packageName, R.layout.widget_agenda_item).apply {
                setTextViewText(R.id.widget_date_day, row.date.dayOfMonth.toString())
                setTextViewText(R.id.widget_date_dow, row.date.format(DayFormatter))
                setViewVisibility(R.id.widget_date_rail, if (row.showDateRail) View.VISIBLE else View.INVISIBLE)
                setInt(
                    R.id.widget_date_rail,
                    "setBackgroundResource",
                    if (row.isToday) R.drawable.widget_date_rail_today else R.drawable.widget_date_rail,
                )
                setTextColor(
                    R.id.widget_date_day,
                    if (row.isToday) WidgetTodayTextColor else WidgetPrimaryTextColor,
                )
                setTextColor(
                    R.id.widget_date_dow,
                    if (row.isToday) WidgetTodayTextColor else WidgetSecondaryTextColor,
                )
                setInt(R.id.widget_card, "setBackgroundResource", row.cardBackground)
                setTextViewText(R.id.widget_item_title, row.title)
                setTextViewText(R.id.widget_item_meta, row.meta)
                setViewVisibility(R.id.widget_item_meta, if (row.meta.isBlank()) View.GONE else View.VISIBLE)
                setViewVisibility(R.id.widget_task_indicator, if (row.isTask) View.VISIBLE else View.GONE)
                setInt(R.id.widget_accent, "setBackgroundColor", row.accentColor ?: WidgetAccentColor)
                setViewVisibility(R.id.widget_accent, if (row.accentColor == null) View.INVISIBLE else View.VISIBLE)
                setOnClickFillInIntent(R.id.widget_item_root, Intent())
            }
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 2

    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.stableId ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}

private fun loadAgendaRows(context: Context): List<WidgetAgendaRow> {
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
        return listOf(WidgetAgendaRow.status("Calendar access needed"))
    }

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val nowMillis = System.currentTimeMillis()
    val startMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val endMillis = today.plusDays(WidgetAgendaDays)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
    val events = queryWidgetEvents(context, startMillis, endMillis)
        .filter { it.endMillis > nowMillis }
    val tasks = CalendarTaskStore(context).listAgendaTasks(startMillis, endMillis)

    val dates = (events.map { it.startDate(zone) } + tasks.mapNotNull { it.agendaDate(zone) } + today)
        .distinct()
        .sorted()
    var previousMonth: YearMonth? = null

    return buildList {
        dates.forEach { date ->
            val month = YearMonth.from(date)
            if (month != previousMonth) {
                add(
                    WidgetAgendaRow.Month(
                        stableId = -month.atDay(1).toEpochDay(),
                        label = month.format(MonthFormatter),
                    ),
                )
            }
            previousMonth = month

            val items = agendaItemsForDate(
                date = date,
                events = events.filter { it.startDate(zone) == date },
                tasks = tasks.filter { it.agendaDate(zone) == date },
                nowMillis = nowMillis,
                zone = zone,
            )

            if (items.isEmpty()) {
                add(
                    WidgetAgendaRow.Item(
                        stableId = date.toEpochDay(),
                        date = date,
                        showDateRail = true,
                        isToday = date == today,
                        title = "No events or tasks",
                        meta = "",
                        isTask = false,
                        cardBackground = R.drawable.widget_card_base_background,
                        accentColor = null,
                    ),
                )
            } else {
                items.forEachIndexed { index, item ->
                    add(
                        item.toWidgetRow(
                            date = date,
                            showDateRail = index == 0,
                            isToday = date == today,
                            zone = zone,
                        ),
                    )
                }
            }
        }
    }.take(MaxWidgetRows)
}

private fun queryWidgetEvents(
    context: Context,
    startMillis: Long,
    endMillis: Long,
): List<WidgetEvent> {
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        .appendPath(startMillis.toString())
        .appendPath(endMillis.toString())
        .build()
    return context.contentResolver.query(
        uri,
        WidgetEventProjection,
        null,
        null,
        "${CalendarContract.Instances.BEGIN} ASC",
    )?.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(cursor.toWidgetEvent())
            }
        }
    }.orEmpty()
}

private fun agendaItemsForDate(
    date: LocalDate,
    events: List<WidgetEvent>,
    tasks: List<CalendarTask>,
    nowMillis: Long,
    zone: ZoneId,
): List<WidgetAgendaItem> {
    val dayStartMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
    return (
        events.map { event ->
            WidgetAgendaItem.Event(
                event = event,
                sortMillis = if (event.allDay) dayStartMillis else event.startMillis,
                sortRank = if (event.allDay) 0 else 2,
            )
        } +
            tasks.map { task ->
                val taskSortMillis = task.dueMillis ?: task.startMillis ?: dayStartMillis
                WidgetAgendaItem.Task(
                    task = task,
                    sortMillis = if (taskSortMillis < nowMillis && task.agendaDate(zone) == LocalDate.now(zone)) {
                        dayStartMillis
                    } else {
                        taskSortMillis
                    },
                    sortRank = if (task.dueAllDay || task.dueMillis == null) 1 else 3,
                )
            }
        ).sortedWith(
        compareBy<WidgetAgendaItem> { it.sortMillis }
            .thenBy { it.sortRank }
            .thenBy {
                when (it) {
                    is WidgetAgendaItem.Event -> it.event.title.lowercase()
                    is WidgetAgendaItem.Task -> it.task.title.lowercase()
                }
            },
    )
}

private fun WidgetAgendaItem.toWidgetRow(
    date: LocalDate,
    showDateRail: Boolean,
    isToday: Boolean,
    zone: ZoneId,
): WidgetAgendaRow {
    return when (this) {
        is WidgetAgendaItem.Event -> WidgetAgendaRow.Item(
            stableId = event.id * 31 + sortMillis,
            date = date,
            showDateRail = showDateRail,
            isToday = isToday,
            title = event.title,
            meta = event.meta(zone),
            isTask = false,
            cardBackground = R.drawable.widget_card_background,
            accentColor = event.calendarColor ?: WidgetAccentColor,
        )

        is WidgetAgendaItem.Task -> WidgetAgendaRow.Item(
            stableId = task.id.hashCode().toLong(),
            date = date,
            showDateRail = showDateRail,
            isToday = isToday,
            title = task.title,
            meta = task.meta(zone),
            isTask = true,
            cardBackground = R.drawable.widget_card_base_background,
            accentColor = task.listColor ?: WidgetTaskAccentColor,
        )
    }
}

private fun Cursor.toWidgetEvent(): WidgetEvent {
    return WidgetEvent(
        id = requireLong(CalendarContract.Instances.EVENT_ID),
        title = optionalString(CalendarContract.Instances.TITLE) ?: "Untitled",
        location = optionalString(CalendarContract.Instances.EVENT_LOCATION),
        calendarName = optionalString(CalendarContract.Instances.CALENDAR_DISPLAY_NAME),
        calendarColor = optionalInt(CalendarContract.Instances.CALENDAR_COLOR),
        startMillis = requireLong(CalendarContract.Instances.BEGIN),
        endMillis = requireLong(CalendarContract.Instances.END),
        allDay = optionalInt(CalendarContract.Instances.ALL_DAY) == 1,
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

private fun WidgetEvent.startDate(zone: ZoneId): LocalDate {
    val dateZone = if (allDay) ZoneOffset.UTC else zone
    return Instant.ofEpochMilli(startMillis).atZone(dateZone).toLocalDate()
}

private fun CalendarTask.agendaDate(zone: ZoneId): LocalDate? {
    val millis = dueMillis ?: startMillis ?: return null
    val dateZone = if (dueMillis != null && dueAllDay || dueMillis == null && startAllDay) {
        ZoneOffset.UTC
    } else {
        zone
    }
    val date = Instant.ofEpochMilli(millis).atZone(dateZone).toLocalDate()
    val today = LocalDate.now(zone)
    return if (
        status != CalendarTaskStatus.Completed &&
        status != CalendarTaskStatus.Cancelled &&
        dueMillis != null &&
        date.isBefore(today)
    ) {
        today
    } else {
        date
    }
}

private fun WidgetEvent.meta(zone: ZoneId): String {
    val timeLabel = if (allDay) {
        "All day"
    } else {
        "${formatTime(startMillis, zone)} - ${formatTime(endMillis, zone)}"
    }
    return listOfNotNull(
        timeLabel,
        location?.takeIf { it.isNotBlank() },
    ).joinToString(" - ")
}

private fun CalendarTask.meta(zone: ZoneId): String {
    return dueLabel(zone).orEmpty()
}

private fun CalendarTask.dueLabel(zone: ZoneId): String? {
    val due = dueMillis ?: return startMillis?.let { "Starts ${formatDateTime(it, startAllDay, zone)}" }
    val dueDate = Instant.ofEpochMilli(due).atZone(if (dueAllDay) ZoneOffset.UTC else zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        dueDate.isBefore(today) -> "Overdue"
        dueDate == today && dueAllDay -> "Due today"
        dueDate == today -> "Due ${formatTime(due, zone)}"
        else -> "Due ${formatDateTime(due, dueAllDay, zone)}"
    }
}

private fun formatTime(
    millis: Long,
    zone: ZoneId,
): String {
    return Instant.ofEpochMilli(millis)
        .atZone(zone)
        .format(TimeFormatter)
}

private fun formatDateTime(
    millis: Long,
    allDay: Boolean,
    zone: ZoneId,
): String {
    val dateZone = if (allDay) ZoneOffset.UTC else zone
    val dateTime = Instant.ofEpochMilli(millis).atZone(dateZone)
    return if (allDay) {
        dateTime.toLocalDate().format(DateFormatter)
    } else {
        dateTime.format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
    }
}

private data class WidgetEvent(
    val id: Long,
    val title: String,
    val location: String?,
    val calendarName: String?,
    val calendarColor: Int?,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
)

private sealed interface WidgetAgendaRow {
    val stableId: Long

    data class Month(
        override val stableId: Long,
        val label: String,
    ) : WidgetAgendaRow

    data class Item(
        override val stableId: Long,
        val date: LocalDate,
        val showDateRail: Boolean,
        val isToday: Boolean,
        val title: String,
        val meta: String,
        val isTask: Boolean,
        val cardBackground: Int,
        val accentColor: Int?,
    ) : WidgetAgendaRow

    companion object {
        fun status(title: String): WidgetAgendaRow {
            val today = LocalDate.now()
            return Item(
                stableId = today.toEpochDay(),
                date = today,
                showDateRail = true,
                isToday = true,
                title = title,
                meta = "",
                isTask = false,
                cardBackground = R.drawable.widget_card_base_background,
                accentColor = null,
            )
        }
    }
}

private sealed interface WidgetAgendaItem {
    val sortMillis: Long
    val sortRank: Int

    data class Event(
        val event: WidgetEvent,
        override val sortMillis: Long,
        override val sortRank: Int,
    ) : WidgetAgendaItem

    data class Task(
        val task: CalendarTask,
        override val sortMillis: Long,
        override val sortRank: Int,
    ) : WidgetAgendaItem
}

private val WidgetEventProjection = arrayOf(
    CalendarContract.Instances.EVENT_ID,
    CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
    CalendarContract.Instances.CALENDAR_COLOR,
    CalendarContract.Instances.TITLE,
    CalendarContract.Instances.EVENT_LOCATION,
    CalendarContract.Instances.BEGIN,
    CalendarContract.Instances.END,
    CalendarContract.Instances.ALL_DAY,
)

private val MonthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
private val DayFormatter = DateTimeFormatter.ofPattern("EEE")
private val DateFormatter = DateTimeFormatter.ofPattern("d MMM")
private val TimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val WidgetAgendaDays = 30L
private const val MaxWidgetRows = 50
private const val WidgetPrimaryTextColor = 0xFFEDEDED.toInt()
private const val WidgetSecondaryTextColor = 0xFFB8B8B8.toInt()
private const val WidgetTodayTextColor = 0xFF202124.toInt()
private const val WidgetAccentColor = 0xFF2E8E70.toInt()
private const val WidgetTaskAccentColor = 0xFFEDEDED.toInt()
