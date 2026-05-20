package digital.dutton.essentials.calendar

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Today
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import digital.dutton.essentials.calendar.data.CalendarEvent
import digital.dutton.essentials.calendar.data.CalendarEventDraft
import digital.dutton.essentials.calendar.data.CalendarSource
import digital.dutton.essentials.calendar.data.EventAvailability
import digital.dutton.essentials.calendar.data.locationLink
import digital.dutton.essentials.calendar.provider.AndroidCalendarRepository
import digital.dutton.essentials.locations.EventLocationLink
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val CalendarPermissions = arrayOf(
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CalendarApp() }
    }
}

data class CalendarUiState(
    val hasCalendarPermission: Boolean = false,
    val isLoading: Boolean = false,
    val calendars: List<CalendarSource> = emptyList(),
    val events: List<CalendarEvent> = emptyList(),
    val error: String? = null,
)

class CalendarViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AndroidCalendarRepository(application.applicationContext)
    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    fun refreshPermissionState() {
        val hasPermission = getApplication<Application>()
            .applicationContext
            .hasCalendarReadPermission()

        _uiState.update { it.copy(hasCalendarPermission = hasPermission) }
        if (hasPermission) {
            loadUpcomingEvents()
        }
    }

    fun loadUpcomingEvents() {
        if (!getApplication<Application>().applicationContext.hasCalendarReadPermission()) {
            _uiState.update {
                it.copy(
                    hasCalendarPermission = false,
                    isLoading = false,
                    calendars = emptyList(),
                    events = emptyList(),
                    error = null,
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    hasCalendarPermission = true,
                    isLoading = true,
                    error = null,
                )
            }

            val zone = ZoneId.systemDefault()
            val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = LocalDate.now(zone).plusDays(30).atStartOfDay(zone).toInstant().toEpochMilli()

            runCatching {
                repository.listCalendars() to repository.listEvents(start, end)
            }.onSuccess { (calendars, events) ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        calendars = calendars,
                        events = events,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "Unable to read calendars.",
                    )
                }
            }
        }
    }

    fun createEvent(event: CalendarEventDraft) {
        mutateEvent { repository.createEvent(event) }
    }

    fun updateEvent(
        eventId: Long,
        event: CalendarEventDraft,
    ) {
        mutateEvent { repository.updateEvent(eventId, event) }
    }

    fun deleteEvent(eventId: Long) {
        mutateEvent { repository.deleteEvent(eventId) }
    }

    private fun mutateEvent(action: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                action()
            }.onSuccess {
                loadUpcomingEvents()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "Unable to update calendar.",
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarApp(
    viewModel: CalendarViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshPermissionState()
    }

    LaunchedEffect(Unit) {
        viewModel.refreshPermissionState()
    }

    CalendarTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            CalendarScreen(
                state = state,
                onRequestPermission = { permissionLauncher.launch(CalendarPermissions) },
                onRefresh = viewModel::loadUpcomingEvents,
                onCreateEvent = viewModel::createEvent,
                onUpdateEvent = viewModel::updateEvent,
                onDeleteEvent = viewModel::deleteEvent,
            )
        }
    }
}

@Composable
private fun CalendarTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

@Composable
private fun CalendarScreen(
    state: CalendarUiState,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onCreateEvent: (CalendarEventDraft) -> Unit,
    onUpdateEvent: (Long, CalendarEventDraft) -> Unit,
    onDeleteEvent: (Long) -> Unit,
) {
    val context = LocalContext.current
    var isMonthExpanded by rememberSaveable { mutableStateOf(true) }
    var selectedDateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var eventDialog by remember { mutableStateOf<EventDialogState?>(null) }
    val currentMonth = YearMonth.now()
    val defaultCalendar = state.calendars.defaultWritableCalendar()
    val selectedDate = remember(selectedDateText) { LocalDate.parse(selectedDateText) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val sections = state.events.agendaSections()

    fun scrollToAgendaDate(date: LocalDate) {
        if (sections.isEmpty()) return

        val sectionIndex = sections.indexOfFirst { section -> section.date >= date }
            .takeUnless { it == -1 }
            ?: sections.lastIndex
        val sectionItemIndex = 1 + (if (state.error == null) 0 else 1) + sectionIndex

        coroutineScope.launch {
            listState.animateScrollToItem(sectionItemIndex)
        }
    }

    fun selectDate(date: LocalDate) {
        selectedDateText = date.toString()
        scrollToAgendaDate(date)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AgendaTopBar(
                state = state,
                month = currentMonth,
                isMonthExpanded = isMonthExpanded,
                onToggleMonth = { isMonthExpanded = !isMonthExpanded },
                onToday = {
                    val today = LocalDate.now()
                    selectedDateText = today.toString()
                    onRefresh()
                    scrollToAgendaDate(today)
                },
            )
        },
        floatingActionButton = {
            if (state.hasCalendarPermission && defaultCalendar != null) {
                FloatingActionButton(
                    onClick = {
                        eventDialog = EventDialogState.Create(defaultCalendar.id)
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Create event",
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!state.hasCalendarPermission) {
                item {
                    PermissionPanel(onRequestPermission = onRequestPermission)
                }
                return@LazyColumn
            }

            item(key = "month-overview") {
                AnimatedVisibility(
                    visible = isMonthExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    MonthOverview(
                        month = currentMonth,
                        events = state.events,
                        selectedDate = selectedDate,
                        onDateSelected = ::selectDate,
                    )
                }
            }

            state.error?.let { message ->
                item {
                    CompactStatus(
                        title = "Calendar unavailable",
                        body = message,
                    )
                }
            }

            if (sections.isEmpty() && !state.isLoading) {
                item {
                    EmptyAgenda()
                }
            }

            sections.forEach { section ->
                item(key = "date-${section.date}") {
                    AgendaDaySection(
                        section = section,
                        onEventClick = { event ->
                            eventDialog = EventDialogState.Details(event)
                        },
                    )
                }
            }
        }
    }

    when (val dialog = eventDialog) {
        is EventDialogState.Create -> EventEditorDialog(
            title = "New event",
            calendars = state.calendars,
            initialState = newEventFormState(dialog.calendarId),
            onDismiss = { eventDialog = null },
            onSave = { draft ->
                onCreateEvent(draft)
                eventDialog = null
            },
        )

        is EventDialogState.Details -> EventDetailsDialog(
            event = dialog.event,
            onDismiss = { eventDialog = null },
            onEdit = { eventDialog = EventDialogState.Edit(dialog.event) },
            onDelete = { eventDialog = EventDialogState.Delete(dialog.event) },
            onOpenLocation = context::openEventLocation,
        )

        is EventDialogState.Edit -> EventEditorDialog(
            title = "Edit event",
            calendars = state.calendars,
            initialState = dialog.event.toFormState(),
            onDismiss = { eventDialog = EventDialogState.Details(dialog.event) },
            onSave = { draft ->
                onUpdateEvent(dialog.event.id, draft)
                eventDialog = null
            },
        )

        is EventDialogState.Delete -> ConfirmDeleteDialog(
            event = dialog.event,
            onDismiss = { eventDialog = EventDialogState.Details(dialog.event) },
            onDelete = {
                onDeleteEvent(dialog.event.id)
                eventDialog = null
            },
        )

        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgendaTopBar(
    state: CalendarUiState,
    month: YearMonth,
    isMonthExpanded: Boolean,
    onToggleMonth: () -> Unit,
    onToday: () -> Unit,
) {
    val toggleRotation by animateFloatAsState(
        targetValue = if (isMonthExpanded) 180f else 0f,
        label = "Month toggle rotation",
    )

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = {}) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Open navigation",
                )
            }
        },
        title = {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onToggleMonth)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = month.format(DateTimeFormatter.ofPattern("MMMM")),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (isMonthExpanded) {
                        "Collapse month"
                    } else {
                        "Expand month"
                    },
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(toggleRotation),
                )
            }
        },
        actions = {
            if (state.hasCalendarPermission) {
                IconButton(onClick = onToday) {
                    Icon(
                        imageVector = Icons.Rounded.Today,
                        contentDescription = "Go to today",
                    )
                }
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "More options",
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun PermissionPanel(onRequestPermission: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Calendar access",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Allow access to Android Calendar Provider so Calendar can show and manage your existing calendars.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRequestPermission) {
                Text("Allow access")
            }
        }
    }
}

@Composable
private fun CompactStatus(
    title: String,
    body: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MonthOverview(
    month: YearMonth,
    events: List<CalendarEvent>,
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val eventDates = events.map { event -> event.startDate(zone) }.toSet()
    val cells = month.calendarCells()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            WeekdayLabels.forEach { label ->
                Text(
                    modifier = Modifier.weight(1f),
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        cells.chunked(7).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                week.forEach { date ->
                    MonthDayCell(
                        modifier = Modifier.weight(1f),
                        date = date,
                        selected = date == selectedDate,
                        isToday = date == today,
                        hasEvents = date in eventDates,
                        onClick = onDateSelected,
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthDayCell(
    modifier: Modifier = Modifier,
    date: LocalDate?,
    selected: Boolean,
    isToday: Boolean,
    hasEvents: Boolean,
    onClick: (LocalDate) -> Unit,
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .clickable(enabled = date != null) {
                date?.let(onClick)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (date == null) return@Box

        val contentColor = when {
            selected -> MaterialTheme.colorScheme.onPrimary
            isToday -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected || isToday) FontWeight.SemiBold else FontWeight.Medium,
                    color = contentColor,
                )
            }

            Box(
                modifier = Modifier
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(
                        if (hasEvents) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                    ),
            )
        }
    }
}

@Composable
private fun AgendaDaySection(
    section: AgendaSection,
    onEventClick: (CalendarEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DateRail(date = section.date)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            section.events.forEach { event ->
                AgendaEventBlock(
                    event = event,
                    onClick = { onEventClick(event) },
                )
            }
        }
    }
}

@Composable
private fun DateRail(date: LocalDate) {
    val isToday = date == LocalDate.now()
    Column(
        modifier = Modifier.width(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = if (isToday) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            text = date.format(DateTimeFormatter.ofPattern("EEE")),
            style = MaterialTheme.typography.labelMedium,
            color = if (isToday) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun AgendaEventBlock(
    event: CalendarEvent,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(event.accentColor()),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = event.agendaMeta(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EventDetailsDialog(
    event: CalendarEvent,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenLocation: (EventLocationAction) -> Unit,
) {
    val locationAction = event.locationAction()

    Dialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(start = 24.dp, top = 18.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(event.accentColor()),
                    )
                    Text(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                        text = event.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close event details",
                        )
                    }
                }

                Column(
                    modifier = Modifier.padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DetailLine(
                        label = "Time",
                        value = event.detailDateTimeLabel(),
                    )
                    locationAction?.let { action ->
                        ActionDetailLine(
                            label = "Location",
                            value = action.displayLabel,
                            onClick = { onOpenLocation(action) },
                        )
                    }
                    event.description?.takeIf { it.isNotBlank() }?.let { description ->
                        RichNotesLine(label = "Notes", value = description)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("Delete")
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = onEdit) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text("Edit")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ActionDetailLine(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RichNotesLine(
    label: String,
    value: String,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val bodySize = MaterialTheme.typography.bodyMedium.fontSize.value
    val richText = remember(value) { value.toCalendarNoteText() }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
        )
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                TextView(context).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    includeFontPadding = false
                    linksClickable = true
                    movementMethod = LinkMovementMethod.getInstance()
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySize)
                }
            },
            update = { textView ->
                textView.text = richText
                textView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                textView.setTextColor(textColor)
                textView.setLinkTextColor(linkColor)
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySize)
            },
        )
    }
}

@Composable
private fun EventEditorDialog(
    title: String,
    calendars: List<CalendarSource>,
    initialState: EventFormState,
    onDismiss: () -> Unit,
    onSave: (CalendarEventDraft) -> Unit,
) {
    var formState by remember(initialState) { mutableStateOf(initialState) }
    val calendarName = calendars.calendarName(formState.calendarId)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                formState.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text(
                    text = "Calendar: $calendarName",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = formState.title,
                    onValueChange = { formState = formState.copy(title = it, error = null) },
                    label = { Text("Title") },
                    singleLine = true,
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = formState.date,
                    onValueChange = { formState = formState.copy(date = it, error = null) },
                    label = { Text("Date") },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = formState.allDay,
                        onCheckedChange = {
                            formState = formState.copy(allDay = it, error = null)
                        },
                    )
                    Text(
                        text = "All day",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = formState.startTime,
                        onValueChange = { formState = formState.copy(startTime = it, error = null) },
                        label = { Text("Start") },
                        placeholder = { Text("09:00") },
                        singleLine = true,
                        enabled = !formState.allDay,
                    )
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = formState.endTime,
                        onValueChange = { formState = formState.copy(endTime = it, error = null) },
                        label = { Text("End") },
                        placeholder = { Text("10:00") },
                        singleLine = true,
                        enabled = !formState.allDay,
                    )
                }

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = formState.location,
                    onValueChange = { formState = formState.copy(location = it, error = null) },
                    label = { Text("Location") },
                    singleLine = true,
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = formState.description,
                    onValueChange = { formState = formState.copy(description = it, error = null) },
                    label = { Text("Notes") },
                    minLines = 2,
                    maxLines = 4,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    runCatching {
                        formState.toDraft()
                    }.onSuccess { draft ->
                        onSave(draft)
                    }.onFailure { error ->
                        formState = formState.copy(
                            error = error.message ?: "Check the event details.",
                        )
                    }
                },
            ) {
                Text("Save")
            }
        },
    )
}

@Composable
private fun ConfirmDeleteDialog(
    event: CalendarEvent,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete event?") },
        text = {
            Text("This removes \"${event.title}\" from the Android Calendar Provider.")
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        confirmButton = {
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("Delete")
            }
        },
    )
}

@Composable
private fun EmptyAgenda() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "No upcoming events",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Your next 30 days are clear.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun Context.hasCalendarReadPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.READ_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED
}

private data class AgendaSection(
    val date: LocalDate,
    val events: List<CalendarEvent>,
)

private sealed interface EventDialogState {
    data class Create(val calendarId: Long) : EventDialogState

    data class Details(val event: CalendarEvent) : EventDialogState

    data class Edit(val event: CalendarEvent) : EventDialogState

    data class Delete(val event: CalendarEvent) : EventDialogState
}

private data class EventFormState(
    val calendarId: Long,
    val title: String,
    val date: String,
    val startTime: String,
    val endTime: String,
    val allDay: Boolean,
    val location: String,
    val description: String,
    val error: String? = null,
)

private val WeekdayLabels = listOf("S", "M", "T", "W", "T", "F", "S")

private val DateInputFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val TimeInputFormatter = DateTimeFormatter.ofPattern("H:mm")
private val TimeOutputFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val MapsPackageName = "digital.dutton.essentials.maps"
private const val LocationIntentExtraSource = "digital.dutton.essentials.locations.extra.SOURCE"
private const val LocationIntentExtraCalendarEventId = "digital.dutton.essentials.locations.extra.CALENDAR_EVENT_ID"
private const val LocationIntentExtraRawProviderLocation =
    "digital.dutton.essentials.locations.extra.RAW_PROVIDER_LOCATION"
private const val LocationIntentSourceCalendar = "calendar"
private val OnlineMeetingUrlPattern = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)

private sealed interface EventLocationAction {
    val displayLabel: String
}

private data class OnlineMeetingAction(
    override val displayLabel: String,
    val uri: Uri,
) : EventLocationAction

private data class MapLocationAction(
    val link: EventLocationLink,
) : EventLocationAction {
    override val displayLabel: String = link.location.displayAddress
}

private data class OnlineMeetingLink(
    val providerLabel: String,
    val uri: Uri,
)

private fun YearMonth.calendarCells(): List<LocalDate?> {
    val leadingEmptyCells = atDay(1).dayOfWeek.value % 7
    val dates = (1..lengthOfMonth()).map(::atDay)
    val trailingEmptyCells = (7 - ((leadingEmptyCells + dates.size) % 7)) % 7
    return List(leadingEmptyCells) { null } + dates + List(trailingEmptyCells) { null }
}

private fun List<CalendarEvent>.agendaSections(): List<AgendaSection> {
    val zone = ZoneId.systemDefault()
    return groupBy { event -> event.startDate(zone) }
        .toSortedMap()
        .map { (date, events) -> AgendaSection(date, events) }
}

private fun CalendarUiState.agendaSubtitle(): String {
    return when {
        !hasCalendarPermission -> "Calendar access needed"
        isLoading -> "Refreshing agenda"
        else -> "Next 30 days, ${calendars.size.calendarCountLabel()}"
    }
}

private fun Int.calendarCountLabel(): String {
    return when (this) {
        0 -> "No calendars"
        1 -> "1 calendar"
        else -> "$this calendars"
    }
}

private fun String.toCalendarNoteText(): CharSequence {
    val note = trim()
    return HtmlCompat.fromHtml(note, HtmlCompat.FROM_HTML_MODE_COMPACT)
        .trimCalendarNote()
}

private fun CharSequence.trimCalendarNote(): CharSequence {
    var start = 0
    var end = length
    while (start < end && this[start].isWhitespace()) start += 1
    while (end > start && this[end - 1].isWhitespace()) end -= 1
    return subSequence(start, end)
}

private fun List<CalendarSource>.defaultWritableCalendar(): CalendarSource? {
    return firstOrNull { it.isVisible } ?: firstOrNull()
}

private fun List<CalendarSource>.calendarName(calendarId: Long): String {
    return firstOrNull { it.id == calendarId }?.displayName ?: "Default calendar"
}

private fun newEventFormState(calendarId: Long): EventFormState {
    val start = LocalTime.now()
        .plusHours(1)
        .truncatedTo(ChronoUnit.HOURS)
    val end = start.plusHours(1)

    return EventFormState(
        calendarId = calendarId,
        title = "",
        date = LocalDate.now().format(DateInputFormatter),
        startTime = start.format(TimeOutputFormatter),
        endTime = end.format(TimeOutputFormatter),
        allDay = false,
        location = "",
        description = "",
    )
}

private fun CalendarEvent.toFormState(): EventFormState {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(startMillis).atZone(zone)
    val end = Instant.ofEpochMilli(endMillis).atZone(zone)

    return EventFormState(
        calendarId = calendarId,
        title = title,
        date = start.toLocalDate().format(DateInputFormatter),
        startTime = start.toLocalTime().format(TimeOutputFormatter),
        endTime = end.toLocalTime().format(TimeOutputFormatter),
        allDay = allDay,
        location = location.orEmpty(),
        description = description.orEmpty(),
    )
}

private fun EventFormState.toDraft(): CalendarEventDraft {
    if (calendarId <= 0L) {
        throw IllegalArgumentException("Choose a calendar before saving.")
    }

    val cleanedTitle = title.trim()
    if (cleanedTitle.isBlank()) {
        throw IllegalArgumentException("Add a title.")
    }

    val eventDate = runCatching {
        LocalDate.parse(date.trim(), DateInputFormatter)
    }.getOrElse {
        throw IllegalArgumentException("Use a date in YYYY-MM-DD format.")
    }

    val zone = ZoneId.systemDefault()
    val startMillis: Long
    val endMillis: Long

    if (allDay) {
        startMillis = eventDate.atStartOfDay(zone).toInstant().toEpochMilli()
        endMillis = eventDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    } else {
        val parsedStart = parseTime(startTime, "start")
        val parsedEnd = parseTime(endTime, "end")
        val start = eventDate.atTime(parsedStart).atZone(zone)
        val end = eventDate.atTime(parsedEnd).atZone(zone)

        if (!end.isAfter(start)) {
            throw IllegalArgumentException("End time must be after start time.")
        }

        startMillis = start.toInstant().toEpochMilli()
        endMillis = end.toInstant().toEpochMilli()
    }

    return CalendarEventDraft(
        calendarId = calendarId,
        title = cleanedTitle,
        location = location.trim().takeIf { it.isNotBlank() },
        description = description.trim().takeIf { it.isNotBlank() },
        startMillis = startMillis,
        endMillis = endMillis,
        allDay = allDay,
        timeZone = zone.id,
        availability = EventAvailability.Busy,
    )
}

private fun parseTime(
    value: String,
    fieldName: String,
): LocalTime {
    return runCatching {
        LocalTime.parse(value.trim(), TimeInputFormatter)
    }.getOrElse {
        throw IllegalArgumentException("Use $fieldName time in HH:mm format.")
    }
}

private fun LocalDate.agendaTitle(): String {
    return format(DateTimeFormatter.ofPattern("EEEE, d MMM"))
}

private fun CalendarEvent.startDate(zone: ZoneId): LocalDate {
    return Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
}

private fun CalendarEvent.timeRangeLabel(): String {
    if (allDay) return "All day"

    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(startMillis).atZone(zone)
    val end = Instant.ofEpochMilli(endMillis).atZone(zone)
    return "${start.format(DateTimeFormatter.ofPattern("HH:mm"))} - ${end.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}

private fun CalendarEvent.detailDateTimeLabel(): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(startMillis).atZone(zone)
    val end = Instant.ofEpochMilli(endMillis).atZone(zone)

    return if (allDay) {
        "${start.toLocalDate().format(DateInputFormatter)} - All day"
    } else {
        "${start.toLocalDate().format(DateInputFormatter)}, ${start.toLocalTime().format(TimeOutputFormatter)} - ${end.toLocalTime().format(TimeOutputFormatter)}"
    }
}

@Composable
private fun CalendarEvent.accentColor(): Color {
    return calendarColor?.let(::Color) ?: MaterialTheme.colorScheme.primary
}

private fun CalendarEvent.agendaMeta(): String {
    return listOfNotNull(
        timeRangeLabel(),
        locationAction()?.displayLabel,
    ).joinToString(" - ")
}

private fun CalendarEvent.locationAction(): EventLocationAction? {
    val providerLocation = location?.trim()?.takeIf { it.isNotBlank() }
    val onlineMeeting = onlineMeetingLink()
    val mapLocation = locationLink()?.let(::MapLocationAction)

    if (onlineMeeting != null && (mapLocation == null || providerLocation.isOnlineMeetingLocationLabel())) {
        val label = providerLocation?.takeUnless { it.isUrlOnly() } ?: onlineMeeting.providerLabel
        return OnlineMeetingAction(label, onlineMeeting.uri)
    }

    return mapLocation ?: onlineMeeting?.let {
        OnlineMeetingAction(it.providerLabel, it.uri)
    }
}

private fun CalendarEvent.onlineMeetingLink(): OnlineMeetingLink? {
    return listOfNotNull(location, description)
        .asSequence()
        .flatMap { text -> OnlineMeetingUrlPattern.findAll(text) }
        .mapNotNull { match -> match.value.toOnlineMeetingLink() }
        .firstOrNull()
}

private fun String.toOnlineMeetingLink(): OnlineMeetingLink? {
    val url = replace("&amp;", "&")
        .trimEnd('.', ',', ';', ':', ')', ']', '}')
    val uri = Uri.parse(url)
    val host = uri.host?.lowercase() ?: return null

    return when {
        host == "teams.microsoft.com" || host.endsWith(".teams.microsoft.com") || host == "teams.live.com" -> {
            OnlineMeetingLink("Microsoft Teams Meeting", uri)
        }
        host == "meet.google.com" -> {
            OnlineMeetingLink("Google Meet", uri)
        }
        else -> null
    }
}

private fun String?.isOnlineMeetingLocationLabel(): Boolean {
    val location = this?.lowercase() ?: return true
    return location.contains("teams") ||
        location.contains("google meet") ||
        location.contains("meet.google") ||
        location.contains("hangouts meet") ||
        location.contains("online meeting") ||
        location.isUrlOnly()
}

private fun String.isUrlOnly(): Boolean {
    return startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
}

private fun Context.openEventLocation(action: EventLocationAction) {
    when (action) {
        is OnlineMeetingAction -> openOnlineMeeting(action.uri)
        is MapLocationAction -> openEventLocationInMaps(action.link)
    }
}

private fun Context.openOnlineMeeting(uri: Uri) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}

private fun Context.openEventLocationInMaps(link: EventLocationLink) {
    val providerLocation = link.rawProviderLocation.takeIf { it.isNotBlank() } ?: return
    val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(providerLocation)}")
    val mapsIntent = Intent(Intent.ACTION_VIEW, geoUri)
        .setPackage(MapsPackageName)
        .putExtra(LocationIntentExtraSource, LocationIntentSourceCalendar)
        .putExtra(LocationIntentExtraCalendarEventId, link.eventId)
        .putExtra(LocationIntentExtraRawProviderLocation, providerLocation)

    runCatching {
        startActivity(mapsIntent)
    }.onFailure {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, geoUri))
        }
    }
}
