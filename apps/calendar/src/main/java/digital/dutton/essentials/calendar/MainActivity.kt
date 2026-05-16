package digital.dutton.essentials.calendar

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Today
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import digital.dutton.essentials.calendar.data.CalendarEvent
import digital.dutton.essentials.calendar.data.CalendarSource
import digital.dutton.essentials.calendar.provider.AndroidCalendarRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter
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
) {
    var isMonthExpanded by rememberSaveable { mutableStateOf(true) }
    val currentMonth = YearMonth.now()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AgendaTopBar(
                state = state,
                month = currentMonth,
                isMonthExpanded = isMonthExpanded,
                onToggleMonth = { isMonthExpanded = !isMonthExpanded },
                onToday = onRefresh,
            )
        },
    ) { padding ->
        LazyColumn(
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

            if (isMonthExpanded) {
                item {
                    MonthOverview(
                        month = currentMonth,
                        events = state.events,
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

            val sections = state.events.agendaSections()
            if (sections.isEmpty() && !state.isLoading) {
                item {
                    EmptyAgenda()
                }
            }

            sections.forEach { section ->
                item(key = "date-${section.date}") {
                    AgendaDaySection(section = section)
                }
            }
        }
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
                    imageVector = if (isMonthExpanded) {
                        Icons.Rounded.KeyboardArrowUp
                    } else {
                        Icons.Rounded.KeyboardArrowDown
                    },
                    contentDescription = if (isMonthExpanded) {
                        "Collapse month"
                    } else {
                        "Expand month"
                    },
                    modifier = Modifier.size(20.dp),
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
                        selected = date == today,
                        hasEvents = date in eventDates,
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
    hasEvents: Boolean,
) {
    Box(
        modifier = modifier.height(34.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (date == null) return@Box

        val contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
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
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
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
private fun AgendaDaySection(section: AgendaSection) {
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
                AgendaEventBlock(event = event)
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
private fun AgendaEventBlock(event: CalendarEvent) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .heightIn(min = 58.dp)
                    .background(event.accentColor()),
            )

            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = event.timeRangeLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val meta = event.agendaMeta()
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
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

private val WeekdayLabels = listOf("S", "M", "T", "W", "T", "F", "S")

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

@Composable
private fun CalendarEvent.accentColor(): Color {
    return calendarColor?.let(::Color) ?: MaterialTheme.colorScheme.primary
}

private fun CalendarEvent.agendaMeta(): String {
    return listOfNotNull(
        calendarName?.takeIf { it.isNotBlank() },
        location?.takeIf { it.isNotBlank() },
    ).joinToString(" - ")
}
