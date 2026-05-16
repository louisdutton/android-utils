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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(padding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Header(
                    state = state,
                    onRefresh = onRefresh,
                )
            }

            if (!state.hasCalendarPermission) {
                item {
                    PermissionPanel(onRequestPermission = onRequestPermission)
                }
                return@LazyColumn
            }

            state.error?.let { message ->
                item {
                    StatusCard(
                        title = "Calendar unavailable",
                        body = message,
                    )
                }
            }

            item {
                StatusCard(
                    title = "Connected calendars",
                    body = "${state.calendars.size} available through Android Calendar Provider",
                )
            }

            if (state.events.isEmpty() && !state.isLoading) {
                item {
                    StatusCard(
                        title = "No upcoming events",
                        body = "Events for the next 30 days will appear here.",
                    )
                }
            }

            items(
                items = state.events,
                key = { event -> "${event.id}-${event.startMillis}" },
            ) { event ->
                EventCard(event = event)
            }
        }
    }
}

@Composable
private fun Header(
    state: CalendarUiState,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Calendar",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = if (state.isLoading) "Syncing local provider" else "Next 30 days",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (state.hasCalendarPermission) {
            TextButton(onClick = onRefresh) {
                Text("Refresh")
            }
        }
    }
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
private fun StatusCard(
    title: String,
    body: String,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EventCard(event: CalendarEvent) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = event.formattedWindow(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            event.calendarName?.let { calendar ->
                Text(
                    text = calendar,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            event.location?.takeIf { it.isNotBlank() }?.let { location ->
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun Context.hasCalendarReadPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.READ_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun CalendarEvent.formattedWindow(): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(startMillis).atZone(zone)
    val end = Instant.ofEpochMilli(endMillis).atZone(zone)
    return if (allDay) {
        start.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
    } else {
        "${start.format(DateTimeFormatter.ofPattern("EEE, d MMM HH:mm"))} - ${end.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }
}
