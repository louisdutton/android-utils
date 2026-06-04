package digital.dutton.essentials.calendar

import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import digital.dutton.essentials.calendar.data.CalendarEvent
import digital.dutton.essentials.calendar.data.CalendarEventDraft
import digital.dutton.essentials.calendar.data.CalendarSource
import digital.dutton.essentials.calendar.data.CalendarTask
import digital.dutton.essentials.calendar.data.CalendarTaskDraft
import digital.dutton.essentials.calendar.data.CalendarTaskStatus
import digital.dutton.essentials.calendar.data.EventAvailability
import digital.dutton.essentials.calendar.data.locationLink
import digital.dutton.essentials.calendar.provider.AndroidCalendarRepository
import digital.dutton.essentials.calendar.provider.CalendarTaskStore
import digital.dutton.essentials.calendar.sync.CalDavAccount
import digital.dutton.essentials.calendar.sync.CalDavAccountStore
import digital.dutton.essentials.calendar.sync.CalDavCalendar
import digital.dutton.essentials.calendar.sync.CalDavSyncWorker
import digital.dutton.essentials.calendar.sync.CalDavSyncer
import digital.dutton.essentials.calendar.sync.CalendarSubscription
import digital.dutton.essentials.calendar.sync.CalendarSubscriptionStore
import digital.dutton.essentials.calendar.sync.IcsSubscriptionSyncWorker
import digital.dutton.essentials.calendar.sync.IcsSubscriptionSyncer
import digital.dutton.essentials.calendar.widget.AgendaWidgetProvider
import digital.dutton.essentials.locations.EventLocationLink
import digital.dutton.essentials.locations.GeoPoint
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
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
        setContent { CalendarApp(initialSubscriptionUrl = intent.subscriptionUrl()) }
    }
}

data class CalendarUiState(
    val hasCalendarPermission: Boolean = false,
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val rangeStartDate: LocalDate = LocalDate.now(),
    val rangeEndDate: LocalDate = LocalDate.now().plusDays(DefaultAgendaFutureDays),
    val calendars: List<CalendarSource> = emptyList(),
    val events: List<CalendarEvent> = emptyList(),
    val tasks: List<CalendarTask> = emptyList(),
    val allTasks: List<CalendarTask> = emptyList(),
    val subscriptions: List<CalendarSubscription> = emptyList(),
    val calDavAccounts: List<CalDavAccount> = emptyList(),
    val calDavCalendars: List<CalDavCalendar> = emptyList(),
    val error: String? = null,
)

class CalendarViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AndroidCalendarRepository(application.applicationContext)
    private val taskStore = CalendarTaskStore(application.applicationContext)
    private val subscriptionStore = CalendarSubscriptionStore(application.applicationContext)
    private val subscriptionSyncer = IcsSubscriptionSyncer(application.applicationContext)
    private val calDavStore = CalDavAccountStore(application.applicationContext)
    private val calDavSyncer = CalDavSyncer(application.applicationContext)
    private val _uiState = MutableStateFlow(
        CalendarUiState(
            hasCalendarPermission = application.applicationContext.hasCalendarPermissions(),
        ),
    )
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    fun refreshPermissionState() {
        val hasPermission = getApplication<Application>()
            .applicationContext
            .hasCalendarPermissions()

        _uiState.update { it.copy(hasCalendarPermission = hasPermission) }
        if (hasPermission) {
            IcsSubscriptionSyncWorker.enqueuePeriodicForAll(getApplication<Application>().applicationContext)
            CalDavSyncWorker.enqueuePeriodicForAll(getApplication<Application>().applicationContext)
            loadUpcomingEvents()
            recoverMissingProviderCalendars()
        }
    }

    private fun recoverMissingProviderCalendars() {
        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            runCatching {
                if (subscriptionSyncer.hasMissingProviderCalendars()) {
                    IcsSubscriptionSyncWorker.enqueueOneTime(context)
                }
                if (calDavSyncer.hasMissingProviderCalendars()) {
                    CalDavSyncWorker.enqueueOneTime(context)
                }
            }
        }
    }

    fun loadUpcomingEvents() {
        val state = _uiState.value
        loadEventsForRange(state.rangeStartDate, state.rangeEndDate)
    }

    fun loadNewerEvents() {
        val state = _uiState.value
        loadEventsForRange(
            startDate = state.rangeStartDate,
            endDate = state.rangeEndDate.plusDays(AgendaPageDays),
        )
    }

    fun ensureDateVisible(date: LocalDate) {
        ensureDateRangeVisible(
            startDate = date.minusDays(AgendaSelectionPaddingDays),
            endDate = date.plusDays(AgendaSelectionPaddingDays),
        )
    }

    fun ensureDateRangeVisible(
        startDate: LocalDate,
        endDate: LocalDate,
    ) {
        val state = _uiState.value
        val requestedStartDate = minOf(startDate, endDate)
        val requestedEndDate = maxOf(startDate, endDate)

        if (
            requestedStartDate.isBefore(state.rangeStartDate) ||
            requestedEndDate.isAfter(state.rangeEndDate)
        ) {
            loadEventsForRange(
                startDate = minOf(state.rangeStartDate, requestedStartDate),
                endDate = maxOf(state.rangeEndDate, requestedEndDate),
            )
        }
    }

    private fun loadEventsForRange(
        startDate: LocalDate,
        endDate: LocalDate,
    ) {
        if (!getApplication<Application>().applicationContext.hasCalendarPermissions()) {
            _uiState.update {
                it.copy(
                    hasCalendarPermission = false,
                    isLoading = false,
                    calendars = emptyList(),
                    events = emptyList(),
                    tasks = emptyList(),
                    allTasks = emptyList(),
                    subscriptions = emptyList(),
                    calDavAccounts = emptyList(),
                    calDavCalendars = emptyList(),
                    error = null,
                )
            }
            AgendaWidgetProvider.updateAll(getApplication<Application>().applicationContext)
            return
        }

        val requestedStartDate = minOf(startDate, endDate)
        val requestedEndDate = maxOf(startDate, endDate)
        val normalizedStartDate = requestedStartDate
        val normalizedEndDate = maxOf(requestedEndDate, normalizedStartDate)

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    hasCalendarPermission = true,
                    isLoading = true,
                    error = null,
                    rangeStartDate = normalizedStartDate,
                    rangeEndDate = normalizedEndDate,
                )
            }

            val zone = ZoneId.systemDefault()
            val start = normalizedStartDate.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = normalizedEndDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

            runCatching {
                CalendarSnapshot(
                    calendars = repository.listCalendars(),
                    events = repository.listEvents(start, end),
                    tasks = taskStore.listAgendaTasks(start, end),
                    allTasks = taskStore.listTasks(),
                    subscriptions = subscriptionStore.list(),
                    calDavAccounts = calDavStore.listAccounts(),
                    calDavCalendars = calDavStore.listCalendars(),
                )
            }.onSuccess { snapshot ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        calendars = snapshot.calendars,
                        events = snapshot.events,
                        tasks = snapshot.tasks,
                        allTasks = snapshot.allTasks,
                        subscriptions = snapshot.subscriptions,
                        calDavAccounts = snapshot.calDavAccounts,
                        calDavCalendars = snapshot.calDavCalendars,
                    )
                }
                AgendaWidgetProvider.updateAll(getApplication<Application>().applicationContext)
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
        mutateEvent(reloadDate = event.startDate(ZoneId.systemDefault())) {
            repository.createEvent(event)
        }
    }

    fun createTask(task: CalendarTaskDraft) {
        mutateEvent(reloadDate = task.dueDate(ZoneId.systemDefault())) {
            calDavSyncer.createTask(task)
        }
    }

    fun setTaskCompleted(
        task: CalendarTask,
        completed: Boolean,
    ) {
        mutateEvent(reloadDate = task.agendaDate(ZoneId.systemDefault())) {
            calDavSyncer.setTaskCompleted(task, completed)
        }
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

    fun subscribeCalendar(
        url: String,
        displayName: String?,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                subscriptionSyncer.subscribe(url, displayName)
            }.onSuccess { summary ->
                IcsSubscriptionSyncWorker.enqueuePeriodic(
                    getApplication<Application>().applicationContext,
                    summary.subscriptionId,
                )
                loadUpcomingEvents()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "Unable to subscribe to calendar.",
                    )
                }
            }
        }
    }

    fun syncSubscribedCalendars() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, error = null) }
            runCatching {
                subscriptionSyncer.syncAll()
            }.onSuccess {
                IcsSubscriptionSyncWorker.enqueuePeriodicForAll(getApplication<Application>().applicationContext)
                _uiState.update { it.copy(isSyncing = false) }
                loadUpcomingEvents()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        error = error.message ?: "Unable to sync subscribed calendars.",
                    )
                }
            }
        }
    }

    fun connectCalDav(
        serverUrl: String,
        username: String,
        password: String,
        displayName: String?,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                calDavSyncer.connect(
                    baseUrl = serverUrl,
                    username = username,
                    password = password,
                    requestedName = displayName,
                )
            }.onSuccess { summary ->
                CalDavSyncWorker.enqueuePeriodic(
                    getApplication<Application>().applicationContext,
                    summary.accountId,
                )
                loadUpcomingEvents()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "Unable to connect CalDAV account.",
                    )
                }
            }
        }
    }

    fun syncCalDavAccounts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, error = null) }
            runCatching {
                calDavSyncer.syncAll()
            }.onSuccess {
                CalDavSyncWorker.enqueuePeriodicForAll(getApplication<Application>().applicationContext)
                _uiState.update { it.copy(isSyncing = false) }
                loadUpcomingEvents()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        error = error.message ?: "Unable to sync CalDAV accounts.",
                    )
                }
            }
        }
    }

    fun syncRemoteCalendars() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, error = null) }
            runCatching {
                val failures = mutableListOf<Throwable>()
                runCatching { subscriptionSyncer.syncAll() }.onFailure(failures::add)
                runCatching { calDavSyncer.syncAll() }.onFailure(failures::add)
                if (failures.isNotEmpty()) throw failures.first()
            }.onSuccess {
                IcsSubscriptionSyncWorker.enqueuePeriodicForAll(getApplication<Application>().applicationContext)
                CalDavSyncWorker.enqueuePeriodicForAll(getApplication<Application>().applicationContext)
                _uiState.update { it.copy(isSyncing = false) }
                loadUpcomingEvents()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        error = error.message ?: "Unable to sync calendars.",
                    )
                }
            }
        }
    }

    fun disconnectCalDav(accountId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                calDavSyncer.disconnect(accountId)
            }.onSuccess {
                CalDavSyncWorker.cancel(
                    getApplication<Application>().applicationContext,
                    accountId,
                )
                loadUpcomingEvents()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "Unable to remove CalDAV account.",
                    )
                }
            }
        }
    }

    fun renameCalDavCalendar(
        calendarId: String,
        displayName: String,
        color: Int,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                calDavSyncer.renameCalendar(calendarId, displayName, color)
            }.onSuccess {
                loadUpcomingEvents()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "Unable to rename CalDAV calendar.",
                    )
                }
            }
        }
    }

    fun unsubscribeCalendar(subscriptionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                subscriptionSyncer.unsubscribe(subscriptionId)
            }.onSuccess {
                IcsSubscriptionSyncWorker.cancel(
                    getApplication<Application>().applicationContext,
                    subscriptionId,
                )
                loadUpcomingEvents()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "Unable to remove calendar subscription.",
                    )
                }
            }
        }
    }

    fun renameSubscription(
        subscriptionId: String,
        displayName: String,
        color: Int,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                subscriptionSyncer.renameSubscription(subscriptionId, displayName, color)
            }.onSuccess {
                loadUpcomingEvents()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "Unable to rename calendar subscription.",
                    )
                }
            }
        }
    }

    private fun mutateEvent(
        reloadDate: LocalDate? = null,
        action: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                action()
            }.onSuccess {
                if (reloadDate == null) {
                    loadUpcomingEvents()
                } else {
                    val state = _uiState.value
                    loadEventsForRange(
                        startDate = minOf(
                            state.rangeStartDate,
                            reloadDate.minusDays(AgendaSelectionPaddingDays),
                        ),
                        endDate = maxOf(
                            state.rangeEndDate,
                            reloadDate.plusDays(AgendaSelectionPaddingDays),
                        ),
                    )
                }
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

private data class CalendarSnapshot(
    val calendars: List<CalendarSource>,
    val events: List<CalendarEvent>,
    val tasks: List<CalendarTask>,
    val allTasks: List<CalendarTask>,
    val subscriptions: List<CalendarSubscription>,
    val calDavAccounts: List<CalDavAccount>,
    val calDavCalendars: List<CalDavCalendar>,
)

private data class DrawerCalendarItem(
    val key: String,
    val displayName: String,
    val color: Int?,
    val isReadOnly: Boolean,
    val secondaryText: String?,
    val calendar: CalendarSource? = null,
    val subscription: CalendarSubscription? = null,
    val calDavCalendar: CalDavCalendar? = null,
    val calDavAccount: CalDavAccount? = null,
)

@Composable
private fun CalendarApp(
    viewModel: CalendarViewModel = viewModel(),
    initialSubscriptionUrl: String? = null,
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
                initialSubscriptionUrl = initialSubscriptionUrl,
                onRequestPermission = { permissionLauncher.launch(CalendarPermissions) },
                onLoadNewerEvents = viewModel::loadNewerEvents,
                onEnsureDateVisible = viewModel::ensureDateVisible,
                onEnsureDateRangeVisible = viewModel::ensureDateRangeVisible,
                onCreateEvent = viewModel::createEvent,
                onCreateTask = viewModel::createTask,
                onSetTaskCompleted = viewModel::setTaskCompleted,
                onUpdateEvent = viewModel::updateEvent,
                onDeleteEvent = viewModel::deleteEvent,
                onSubscribeCalendar = viewModel::subscribeCalendar,
                onUnsubscribeCalendar = viewModel::unsubscribeCalendar,
                onConnectCalDav = viewModel::connectCalDav,
                onSyncRemoteCalendars = viewModel::syncRemoteCalendars,
                onDisconnectCalDav = viewModel::disconnectCalDav,
                onRenameSubscription = viewModel::renameSubscription,
                onRenameCalDavCalendar = viewModel::renameCalDavCalendar,
            )
        }
    }
}

@Composable
private fun CalendarTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context).copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF080808),
            surfaceContainer = Color(0xFF101010),
            surfaceContainerHigh = Color(0xFF171717),
            surfaceContainerHighest = Color(0xFF202020),
        )
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
    initialSubscriptionUrl: String?,
    onRequestPermission: () -> Unit,
    onLoadNewerEvents: () -> Unit,
    onEnsureDateVisible: (LocalDate) -> Unit,
    onEnsureDateRangeVisible: (LocalDate, LocalDate) -> Unit,
    onCreateEvent: (CalendarEventDraft) -> Unit,
    onCreateTask: (CalendarTaskDraft) -> Unit,
    onSetTaskCompleted: (CalendarTask, Boolean) -> Unit,
    onUpdateEvent: (Long, CalendarEventDraft) -> Unit,
    onDeleteEvent: (Long) -> Unit,
    onSubscribeCalendar: (String, String?) -> Unit,
    onUnsubscribeCalendar: (String) -> Unit,
    onConnectCalDav: (String, String, String, String?) -> Unit,
    onSyncRemoteCalendars: () -> Unit,
    onDisconnectCalDav: (String) -> Unit,
    onRenameSubscription: (String, String, Int) -> Unit,
    onRenameCalDavCalendar: (String, String, Int) -> Unit,
) {
    val context = LocalContext.current
    var viewModeText by rememberSaveable { mutableStateOf(CalendarViewMode.Agenda.name) }
    var taskFilterText by rememberSaveable { mutableStateOf(TaskFilter.Active.name) }
    var selectedDateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var visibleMonthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var pendingScrollDateText by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingScrollRequiresExactDate by rememberSaveable { mutableStateOf(false) }
    var eventDialog by remember { mutableStateOf<EventDialogState?>(null) }
    var addCalendarDialogInitialUrl by rememberSaveable(initialSubscriptionUrl) {
        mutableStateOf(initialSubscriptionUrl)
    }
    var selectedDrawerItemKey by remember { mutableStateOf<String?>(null) }
    val defaultCalendar = state.calendars.defaultWritableCalendar()
    val viewMode = remember(viewModeText) { CalendarViewMode.valueOf(viewModeText) }
    val taskFilter = remember(taskFilterText) { TaskFilter.valueOf(taskFilterText) }
    val selectedDate = remember(selectedDateText) { LocalDate.parse(selectedDateText) }
    val visibleMonth = remember(visibleMonthText) { YearMonth.parse(visibleMonthText) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val nowMillis = remember(state.events, state.tasks, state.rangeStartDate, state.rangeEndDate) {
        System.currentTimeMillis()
    }
    val sections = remember(state.events, state.tasks, nowMillis) {
        agendaSectionsWithToday(
            events = state.events.filter { event -> event.endMillis > nowMillis },
            tasks = state.tasks,
            nowMillis = nowMillis,
        )
    }
    var lastAgendaAutoLoadAnchorIndex by rememberSaveable { mutableStateOf(-1) }
    val agendaAutoLoadAnchorIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf null

            if (
                layoutInfo.totalItemsCount > 0 &&
                lastVisibleItemIndex >= layoutInfo.totalItemsCount - AgendaLoadAheadItems
            ) {
                lastVisibleItemIndex
            } else {
                null
            }
        }
    }

    fun scrollToAgendaDate(date: LocalDate) {
        if (sections.isEmpty()) return

        val sectionIndex = sections.indexOfFirst { section -> section.date >= date }
            .takeUnless { it == -1 }
            ?: sections.lastIndex
        val sectionItemIndex =
            (if (state.error == null) 0 else 1) +
            sectionIndex +
            sections.monthJunctionCountThrough(sectionIndex)

        coroutineScope.launch {
            listState.animateScrollToItem(sectionItemIndex)
        }
    }

    fun selectMonthDate(date: LocalDate) {
        selectedDateText = date.toString()
        visibleMonthText = YearMonth.from(date).toString()
        onEnsureDateVisible(date)
    }

    fun moveVisibleMonth(monthOffset: Long) {
        val nextMonth = visibleMonth.plusMonths(monthOffset)
        val targetDate = if (nextMonth == YearMonth.now()) {
            LocalDate.now()
        } else {
            nextMonth.atDay(1)
        }
        visibleMonthText = nextMonth.toString()
        selectedDateText = targetDate.toString()
        onEnsureDateRangeVisible(nextMonth.atDay(1), nextMonth.atEndOfMonth())
    }

    fun openAgenda() {
        viewModeText = CalendarViewMode.Agenda.name
        pendingScrollDateText = selectedDateText
        pendingScrollRequiresExactDate = false
    }

    fun openMonthView() {
        viewModeText = CalendarViewMode.Month.name
        onEnsureDateRangeVisible(visibleMonth.atDay(1), visibleMonth.atEndOfMonth())
    }

    fun openTasksView() {
        viewModeText = CalendarViewMode.Tasks.name
    }

    fun goToToday() {
        val today = LocalDate.now()
        selectedDateText = today.toString()
        visibleMonthText = YearMonth.from(today).toString()
        onEnsureDateVisible(today)
        if (viewMode == CalendarViewMode.Agenda) {
            pendingScrollDateText = today.toString()
            pendingScrollRequiresExactDate = false
            scrollToAgendaDate(today)
        }
    }

    fun runDrawerAction(action: () -> Unit) {
        coroutineScope.launch {
            drawerState.close()
            action()
        }
    }

    LaunchedEffect(sections, pendingScrollDateText, pendingScrollRequiresExactDate, state.error) {
        val targetDate = pendingScrollDateText?.let(LocalDate::parse) ?: return@LaunchedEffect
        if (state.error != null) {
            pendingScrollDateText = null
            pendingScrollRequiresExactDate = false
            return@LaunchedEffect
        }
        if (sections.isNotEmpty()) {
            val canConsumeScroll = !pendingScrollRequiresExactDate ||
                sections.any { section -> section.date == targetDate }
            if (!canConsumeScroll) return@LaunchedEffect

            scrollToAgendaDate(targetDate)
            pendingScrollDateText = null
            pendingScrollRequiresExactDate = false
        }
    }

    LaunchedEffect(
        viewMode,
        state.hasCalendarPermission,
        state.isLoading,
        sections.size,
        agendaAutoLoadAnchorIndex,
    ) {
        val anchorIndex = agendaAutoLoadAnchorIndex ?: return@LaunchedEffect
        if (
            viewMode == CalendarViewMode.Agenda &&
            state.hasCalendarPermission &&
            !state.isLoading &&
            sections.isNotEmpty() &&
            anchorIndex > lastAgendaAutoLoadAnchorIndex
        ) {
            lastAgendaAutoLoadAnchorIndex = anchorIndex
            onLoadNewerEvents()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            CalendarDrawer(
                state = state,
                onAddCalendar = {
                    runDrawerAction { addCalendarDialogInitialUrl = "" }
                },
                onSyncCalendars = {
                    runDrawerAction(onSyncRemoteCalendars)
                },
                onCalendarLongPress = { itemKey -> selectedDrawerItemKey = itemKey },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                CalendarTopBar(
                    state = state,
                    viewMode = viewMode,
                    month = visibleMonth,
                    onOpenNavigation = {
                        coroutineScope.launch { drawerState.open() }
                    },
                    onOpenAgenda = ::openAgenda,
                    onOpenMonth = ::openMonthView,
                    onOpenTasks = ::openTasksView,
                    onPreviousMonth = { moveVisibleMonth(-1) },
                    onNextMonth = { moveVisibleMonth(1) },
                    onToday = ::goToToday,
                )
            },
            floatingActionButton = {
                if (state.hasCalendarPermission && defaultCalendar != null) {
                    FloatingActionButton(
                        onClick = {
                            eventDialog = EventDialogState.CreateChoice(defaultCalendar.id, selectedDate)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Create event or task",
                        )
                    }
                }
            },
        ) { padding ->
            when (viewMode) {
                CalendarViewMode.Agenda -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!state.hasCalendarPermission) {
                            item {
                                PermissionPanel(onRequestPermission = onRequestPermission)
                            }
                            return@LazyColumn
                        }

                        state.error?.let { message ->
                            item {
                                CompactStatus(
                                    title = "Calendar unavailable",
                                    body = message,
                                )
                            }
                        }

                        var previousSectionMonth: YearMonth? = null
                        sections.forEach { section ->
                            val sectionMonth = YearMonth.from(section.date)
                            if (sectionMonth != previousSectionMonth) {
                                item(key = "month-$sectionMonth") {
                                    AgendaMonthJunction(month = sectionMonth)
                                }
                            }
                            previousSectionMonth = sectionMonth

                            item(key = "date-${section.date}") {
                                AgendaDaySection(
                                    section = section,
                                    onEventClick = { event ->
                                        eventDialog = EventDialogState.Details(event)
                                    },
                                    onTaskClick = { task ->
                                        eventDialog = EventDialogState.TaskDetails(task)
                                    },
                                    onSetTaskCompleted = onSetTaskCompleted,
                                )
                            }
                        }
                    }
                }

                CalendarViewMode.Month -> {
                    MonthCalendarView(
                        state = state,
                        month = visibleMonth,
                        events = state.events,
                        tasks = state.tasks,
                        selectedDate = selectedDate,
                        onRequestPermission = onRequestPermission,
                        onDateSelected = ::selectMonthDate,
                        onEventClick = { event ->
                            eventDialog = EventDialogState.Details(event)
                        },
                        onTaskClick = { task ->
                            eventDialog = EventDialogState.TaskDetails(task)
                        },
                        onSetTaskCompleted = onSetTaskCompleted,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                }

                CalendarViewMode.Tasks -> {
                    TasksView(
                        state = state,
                        filter = taskFilter,
                        onFilterChange = { taskFilterText = it.name },
                        onRequestPermission = onRequestPermission,
                        onTaskClick = { task ->
                            eventDialog = EventDialogState.TaskDetails(task)
                        },
                        onSetTaskCompleted = onSetTaskCompleted,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                }
            }
        }
    }

    when (val dialog = eventDialog) {
        is EventDialogState.CreateChoice -> CreateItemDialog(
            canCreateTasks = state.calDavAccounts.isNotEmpty(),
            onDismiss = { eventDialog = null },
            onCreateEvent = { eventDialog = EventDialogState.CreateEvent(dialog.calendarId, dialog.date) },
            onCreateTask = { eventDialog = EventDialogState.CreateTask(dialog.date) },
        )

        is EventDialogState.CreateEvent -> EventEditorDialog(
            title = "New event",
            calendars = state.calendars,
            initialState = newEventFormState(dialog.calendarId, dialog.date),
            canChooseCalendar = true,
            onDismiss = { eventDialog = null },
            onSave = { draft ->
                val eventDate = draft.startDate(ZoneId.systemDefault())
                selectedDateText = eventDate.toString()
                visibleMonthText = YearMonth.from(eventDate).toString()
                pendingScrollDateText = eventDate.toString()
                pendingScrollRequiresExactDate = draft.endMillis > System.currentTimeMillis()
                onEnsureDateVisible(eventDate)
                onCreateEvent(draft)
                eventDialog = null
            },
        )

        is EventDialogState.CreateTask -> TaskEditorDialog(
            taskLists = state.calDavCalendars.taskLists(),
            hasCalDavAccount = state.calDavAccounts.isNotEmpty(),
            initialState = newTaskFormState(dialog.date, state.calDavCalendars.taskLists().firstOrNull()?.id),
            onDismiss = { eventDialog = null },
            onSave = { draft ->
                val taskDate = draft.dueDate(ZoneId.systemDefault())
                selectedDateText = taskDate.toString()
                visibleMonthText = YearMonth.from(taskDate).toString()
                pendingScrollDateText = taskDate.toString()
                pendingScrollRequiresExactDate = true
                onEnsureDateVisible(taskDate)
                onCreateTask(draft)
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
            canChooseCalendar = false,
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

        is EventDialogState.TaskDetails -> TaskDetailsDialog(
            task = dialog.task,
            onDismiss = { eventDialog = null },
            onSetCompleted = { completed ->
                onSetTaskCompleted(dialog.task, completed)
                eventDialog = null
            },
        )

        null -> Unit
    }

    addCalendarDialogInitialUrl?.let { initialUrl ->
        AddCalendarDialog(
            initialUrl = initialUrl,
            onDismiss = { addCalendarDialogInitialUrl = null },
            onSubscribe = { url, displayName ->
                onSubscribeCalendar(url, displayName)
                addCalendarDialogInitialUrl = null
            },
            onConnect = { serverUrl, username, password, displayName ->
                onConnectCalDav(serverUrl, username, password, displayName)
                addCalendarDialogInitialUrl = null
            },
        )
    }

    selectedDrawerItemKey
        ?.let { itemKey -> state.drawerCalendarItems().firstOrNull { it.key == itemKey } }
        ?.let { item ->
            CalendarOptionsDialog(
                item = item,
                onDismiss = { selectedDrawerItemKey = null },
                onRenameSubscription = { subscriptionId, displayName, color ->
                    onRenameSubscription(subscriptionId, displayName, color)
                    selectedDrawerItemKey = null
                },
                onRenameCalDavCalendar = { calendarId, displayName, color ->
                    onRenameCalDavCalendar(calendarId, displayName, color)
                    selectedDrawerItemKey = null
                },
                onUnsubscribe = { subscriptionId ->
                    onUnsubscribeCalendar(subscriptionId)
                    selectedDrawerItemKey = null
                },
                onDisconnectCalDav = { accountId ->
                    onDisconnectCalDav(accountId)
                    selectedDrawerItemKey = null
                },
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarTopBar(
    state: CalendarUiState,
    viewMode: CalendarViewMode,
    month: YearMonth,
    onOpenNavigation: () -> Unit,
    onOpenAgenda: () -> Unit,
    onOpenMonth: () -> Unit,
    onOpenTasks: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
) {
    fun selectViewMode(mode: CalendarViewMode) {
        when (mode) {
            CalendarViewMode.Agenda -> onOpenAgenda()
            CalendarViewMode.Month -> onOpenMonth()
            CalendarViewMode.Tasks -> onOpenTasks()
        }
    }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onOpenNavigation) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Open navigation",
                )
            }
        },
        title = {
            CalendarViewTitleMenu(
                viewMode = viewMode,
                title = when (viewMode) {
                    CalendarViewMode.Agenda -> "Agenda"
                    CalendarViewMode.Month -> month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                    CalendarViewMode.Tasks -> "Tasks"
                },
                onModeSelected = ::selectViewMode,
            )
        },
        actions = {
            if (state.hasCalendarPermission) {
                if (viewMode == CalendarViewMode.Month) {
                    IconButton(onClick = onPreviousMonth) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                            contentDescription = "Previous month",
                        )
                    }
                    IconButton(onClick = onNextMonth) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = "Next month",
                        )
                    }
                }
                if (viewMode != CalendarViewMode.Tasks) {
                    IconButton(onClick = onToday) {
                        Icon(
                            imageVector = Icons.Rounded.Today,
                            contentDescription = "Go to today",
                        )
                    }
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
private fun CalendarViewTitleMenu(
    viewMode: CalendarViewMode,
    title: String,
    onModeSelected: (CalendarViewMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Rounded.ArrowDropDown,
                contentDescription = "Change view",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            CalendarViewMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    leadingIcon = {
                        Icon(
                            imageVector = mode.icon(),
                            contentDescription = null,
                        )
                    },
                    trailingIcon = if (mode == viewMode) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onModeSelected(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun CalendarViewMode.icon(): ImageVector {
    return when (this) {
        CalendarViewMode.Agenda -> Icons.Rounded.ViewAgenda
        CalendarViewMode.Month -> Icons.Rounded.CalendarMonth
        CalendarViewMode.Tasks -> Icons.Rounded.CheckCircle
    }
}

private val CalendarViewMode.label: String
    get() = when (this) {
        CalendarViewMode.Agenda -> "Agenda"
        CalendarViewMode.Month -> "Month"
        CalendarViewMode.Tasks -> "Tasks"
    }

@Composable
private fun CalendarDrawer(
    state: CalendarUiState,
    onAddCalendar: () -> Unit,
    onSyncCalendars: () -> Unit,
    onCalendarLongPress: (String) -> Unit,
) {
    val drawerItems = remember(state.calendars, state.subscriptions, state.calDavAccounts, state.calDavCalendars) {
        state.drawerCalendarItems()
    }

    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 12.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "Calendars",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.hasRemoteCalendars()) {
                    IconButton(
                        modifier = Modifier.size(40.dp),
                        onClick = onSyncCalendars,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = "Sync calendars",
                        )
                    }
                }
            }
            Text(
                modifier = Modifier.padding(horizontal = 16.dp),
                text = state.agendaSubtitle(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            if (state.hasCalendarPermission) {
                if (drawerItems.isEmpty()) {
                    Text(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        text = "No calendars connected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    drawerItems.forEach { item ->
                        CalendarDrawerRow(
                            item = item,
                            onLongPress = { onCalendarLongPress(item.key) },
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                NavigationDrawerItem(
                    label = { Text("Add calendar") },
                    selected = false,
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                        )
                    },
                    onClick = onAddCalendar,
                )
            }
        }
    }
}

@Composable
private fun TaskListDrawerRow(taskList: CalDavCalendar) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(taskList.color?.let(::Color) ?: MaterialTheme.colorScheme.tertiary),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = taskList.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = taskList.lastSyncLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalendarDrawerRow(
    item: DrawerCalendarItem,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress,
                onLongClickLabel = "Calendar options",
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(item.drawerColor()),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            item.secondaryText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item.isReadOnly) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = "Read-only calendar",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun AgendaMonthJunction(month: YearMonth) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun TasksView(
    state: CalendarUiState,
    filter: TaskFilter,
    onFilterChange: (TaskFilter) -> Unit,
    onRequestPermission: () -> Unit,
    onTaskClick: (CalendarTask) -> Unit,
    onSetTaskCompleted: (CalendarTask, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filteredTasks = remember(state.allTasks, filter) {
        state.allTasks.filter { task ->
            when (filter) {
                TaskFilter.Active -> task.isActive()
                TaskFilter.Completed -> task.status == CalendarTaskStatus.Completed
                TaskFilter.All -> true
            }
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.hasCalendarPermission) {
            item {
                PermissionPanel(onRequestPermission = onRequestPermission)
            }
            return@LazyColumn
        }

        state.error?.let { message ->
            item {
                CompactStatus(
                    title = "Tasks unavailable",
                    body = message,
                )
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TaskFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { onFilterChange(option) },
                        label = { Text(option.label) },
                    )
                }
            }
        }

        if (filteredTasks.isEmpty()) {
            item {
                CompactStatus(
                    title = "No ${filter.label.lowercase()} tasks",
                    body = if (filter == TaskFilter.Completed) {
                        "Completed tasks will appear here."
                    } else {
                        "Tasks created from CalDAV lists will appear here."
                    },
                )
            }
        } else {
            filteredTasks.forEach { task ->
                item(key = "task-${task.id}") {
                    AgendaTaskBlock(
                        task = task,
                        onClick = { onTaskClick(task) },
                        onSetCompleted = { completed ->
                            onSetTaskCompleted(task, completed)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthCalendarView(
    state: CalendarUiState,
    month: YearMonth,
    events: List<CalendarEvent>,
    tasks: List<CalendarTask>,
    selectedDate: LocalDate,
    onRequestPermission: () -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    onTaskClick: (CalendarTask) -> Unit,
    onSetTaskCompleted: (CalendarTask, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val selectedItems = remember(events, tasks, selectedDate) {
        agendaItemsForDate(
            date = selectedDate,
            events = events.filter { event -> selectedDate in event.agendaDates(zone) },
            tasks = tasks.filter { task -> task.agendaDate(zone) == selectedDate },
            nowMillis = System.currentTimeMillis(),
        )
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!state.hasCalendarPermission) {
            PermissionPanel(onRequestPermission = onRequestPermission)
            return@Column
        }

        state.error?.let { message ->
            CompactStatus(
                title = "Calendar unavailable",
                body = message,
            )
        }

        MonthOverview(
            month = month,
            events = events,
            tasks = tasks,
            selectedDate = selectedDate,
            onDateSelected = onDateSelected,
        )

        AgendaDaySection(
            section = AgendaSection(selectedDate, selectedItems),
            onEventClick = onEventClick,
            onTaskClick = onTaskClick,
            onSetTaskCompleted = onSetTaskCompleted,
        )
    }
}

@Composable
private fun MonthOverview(
    month: YearMonth,
    events: List<CalendarEvent>,
    tasks: List<CalendarTask>,
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val eventDates = (events.flatMap { event -> event.agendaDates(zone) } + tasks.mapNotNull { task -> task.agendaDate(zone) }).toSet()
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
    onTaskClick: (CalendarTask) -> Unit,
    onSetTaskCompleted: (CalendarTask, Boolean) -> Unit,
) {
    val today = LocalDate.now()
    val isToday = section.date == today

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DateRail(date = section.date, isToday = isToday)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (section.items.isEmpty()) {
                AgendaEmptyDayBlock()
            } else {
                section.items.forEach { item ->
                    when (item) {
                        is AgendaItem.Event -> AgendaEventBlock(
                            event = item.event,
                            onClick = { onEventClick(item.event) },
                        )

                        is AgendaItem.Task -> AgendaTaskBlock(
                            task = item.task,
                            onClick = { onTaskClick(item.task) },
                            onSetCompleted = { completed ->
                                onSetTaskCompleted(item.task, completed)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaEmptyDayBlock() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "No events or tasks",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun DateRail(
    date: LocalDate,
    isToday: Boolean,
) {
    val containerColor = if (isToday) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val primaryTextColor = if (isToday) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryTextColor = if (isToday) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .width(54.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Medium,
            color = primaryTextColor,
        )
        Text(
            text = date.format(DateTimeFormatter.ofPattern("EEE")),
            style = MaterialTheme.typography.labelMedium,
            color = secondaryTextColor,
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
            .height(58.dp)
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = event.agendaMeta(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AgendaTaskBlock(
    task: CalendarTask,
    onClick: () -> Unit,
    onSetCompleted: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(task.accentColor()),
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = task.agendaMeta(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TaskCompletionButton(
                    completed = task.isCompleted(),
                    onSetCompleted = onSetCompleted,
                )
            }
        }
    }
}

@Composable
private fun TaskCompletionButton(
    completed: Boolean,
    onSetCompleted: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        modifier = modifier.size(40.dp),
        onClick = { onSetCompleted(!completed) },
    ) {
        Icon(
            imageVector = if (completed) {
                Icons.Rounded.CheckCircle
            } else {
                Icons.Rounded.RadioButtonUnchecked
            },
            contentDescription = if (completed) {
                "Reopen task"
            } else {
                "Mark task done"
            },
            tint = if (completed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventDetailsDialog(
    event: CalendarEvent,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenLocation: (EventLocationAction) -> Unit,
) {
    val locationAction = event.locationAction()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, top = 4.dp, end = 16.dp, bottom = 24.dp),
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
                event.recurrenceRule?.repeatLabel()?.let { repeat ->
                    DetailLine(label = "Repeat", value = repeat)
                }
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

            if (event.isReadOnly) {
                Text(
                    text = "Read-only subscribed event",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDetailsDialog(
    task: CalendarTask,
    onDismiss: () -> Unit,
    onSetCompleted: (Boolean) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, top = 4.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .border(
                            width = 2.dp,
                            color = task.accentColor(),
                            shape = CircleShape,
                        ),
                )
                Text(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    text = task.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close task details",
                    )
                }
            }

            Column(
                modifier = Modifier.padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DetailLine(label = "Due", value = task.detailDueLabel())
                task.recurrenceRule?.repeatLabel()?.let { repeat ->
                    DetailLine(label = "Repeat", value = repeat)
                }
                task.listName?.takeIf { it.isNotBlank() }?.let { listName ->
                    DetailLine(label = "List", value = listName)
                }
                DetailLine(label = "Status", value = task.status.displayLabel())
                task.description?.takeIf { it.isNotBlank() }?.let { description ->
                    RichNotesLine(label = "Notes", value = description)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Button(
                enabled = !task.isReadOnly,
                onClick = { onSetCompleted(!task.isCompleted()) },
            ) {
                Icon(
                    imageVector = if (task.isCompleted()) {
                        Icons.Rounded.RadioButtonUnchecked
                    } else {
                        Icons.Rounded.CheckCircle
                    },
                    contentDescription = null,
                )
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = if (task.isCompleted()) "Reopen" else "Mark done",
                )
            }

            Text(
                text = "Synced CalDAV task",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateItemDialog(
    canCreateTasks: Boolean,
    onDismiss: () -> Unit,
    onCreateEvent: () -> Unit,
    onCreateTask: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Create",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CreateItemRow(
                    title = "Event",
                    body = "Scheduled time, place, and calendar availability.",
                    enabled = true,
                    onClick = onCreateEvent,
                )
                CreateItemRow(
                    title = "Task",
                    body = if (canCreateTasks) {
                        "Due date, optional time, and CalDAV sync."
                    } else {
                        "Connect a CalDAV account before creating tasks."
                    },
                    enabled = canCreateTasks,
                    onClick = onCreateTask,
                )
            }
        }
    }
}

@Composable
private fun CreateItemRow(
    title: String,
    body: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarEditorDialogFrame(
    title: String,
    saveLabel: String = "Save",
    saveEnabled: Boolean = true,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Close editor",
                                )
                            }
                        },
                        title = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        actions = {
                            TextButton(
                                enabled = saveEnabled,
                                onClick = onSave,
                            ) {
                                Text(saveLabel)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun EventEditorDialog(
    title: String,
    calendars: List<CalendarSource>,
    initialState: EventFormState,
    canChooseCalendar: Boolean,
    onDismiss: () -> Unit,
    onSave: (CalendarEventDraft) -> Unit,
) {
    var formState by remember(initialState) { mutableStateOf(initialState) }
    val writableCalendars = remember(calendars) { calendars.writableEventCalendars() }
    val calendarName = calendars.calendarName(formState.calendarId)
    val locationPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val selection = result.data?.toMapLocationSelection() ?: return@rememberLauncherForActivityResult
        formState = formState.copy(
            location = selection.name.ifBlank { formState.location },
            locationPoint = selection.point,
            locationMapName = selection.name,
            locationMapId = selection.mapId,
            error = null,
        )
    }

    CalendarEditorDialogFrame(
        title = title,
        onDismiss = onDismiss,
        onSave = {
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
        formState.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (canChooseCalendar && writableCalendars.isNotEmpty()) {
            EventCalendarPicker(
                calendars = writableCalendars,
                selectedCalendarId = formState.calendarId,
                onSelected = { calendarId ->
                    formState = formState.copy(calendarId = calendarId, error = null)
                },
            )
        } else {
            Text(
                text = "Calendar: $calendarName",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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

        RepeatPicker(
            recurrenceRule = formState.recurrenceRule,
            onSelected = { recurrenceRule ->
                formState = formState.copy(recurrenceRule = recurrenceRule, error = null)
            },
        )

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = formState.location,
            onValueChange = {
                formState = formState.copy(
                    location = it,
                    locationPoint = null,
                    locationMapName = null,
                    locationMapId = null,
                    error = null,
                )
            },
            label = { Text("Location") },
            singleLine = true,
            trailingIcon = {
                IconButton(
                    onClick = {
                        runCatching {
                            locationPickerLauncher.launch(formState.locationPickerIntent())
                        }.onFailure {
                            formState = formState.copy(error = "Unable to open Maps.")
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LocationOn,
                        contentDescription = "Pick location in Maps",
                    )
                }
            },
        )

        formState.locationPoint?.let { point ->
            LinkedMapLocationRow(
                name = formState.locationMapName?.takeIf { it.isNotBlank() } ?: formState.location,
                point = point,
                onClear = {
                    formState = formState.copy(
                        locationPoint = null,
                        locationMapName = null,
                        locationMapId = null,
                        error = null,
                    )
                },
            )
        }

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = formState.description,
            onValueChange = { formState = formState.copy(description = it, error = null) },
            label = { Text("Notes") },
            minLines = 3,
            maxLines = 6,
        )
    }
}

@Composable
private fun TaskEditorDialog(
    taskLists: List<CalDavCalendar>,
    hasCalDavAccount: Boolean,
    initialState: TaskFormState,
    onDismiss: () -> Unit,
    onSave: (CalendarTaskDraft) -> Unit,
) {
    var formState by remember(initialState) { mutableStateOf(initialState) }

    CalendarEditorDialogFrame(
        title = "New task",
        saveEnabled = hasCalDavAccount,
        onDismiss = onDismiss,
        onSave = {
            runCatching {
                formState.toDraft()
            }.onSuccess { draft ->
                onSave(draft)
            }.onFailure { error ->
                formState = formState.copy(
                    error = error.message ?: "Check the task details.",
                )
            }
        },
    ) {
        formState.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        when {
            taskLists.isNotEmpty() -> TaskListPicker(
                taskLists = taskLists,
                selectedCollectionId = formState.collectionId,
                onSelected = { collectionId ->
                    formState = formState.copy(collectionId = collectionId, error = null)
                },
            )

            hasCalDavAccount -> Text(
                text = "List: Tasks will be created on save",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> Text(
                text = "Connect a CalDAV account before creating tasks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

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
            label = { Text("Due date") },
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

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = formState.dueTime,
            onValueChange = { formState = formState.copy(dueTime = it, error = null) },
            label = { Text("Time") },
            placeholder = { Text("09:00") },
            singleLine = true,
            enabled = !formState.allDay,
        )

        RepeatPicker(
            recurrenceRule = formState.recurrenceRule,
            onSelected = { recurrenceRule ->
                formState = formState.copy(recurrenceRule = recurrenceRule, error = null)
            },
        )

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = formState.description,
            onValueChange = { formState = formState.copy(description = it, error = null) },
            label = { Text("Notes") },
            minLines = 3,
            maxLines = 6,
        )
    }
}

@Composable
private fun TaskListPicker(
    taskLists: List<CalDavCalendar>,
    selectedCollectionId: String?,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "List",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            taskLists.forEach { taskList ->
                TaskListPickerRow(
                    taskList = taskList,
                    selected = taskList.id == selectedCollectionId,
                    onClick = { onSelected(taskList.id) },
                )
            }
        }
    }
}

@Composable
private fun TaskListPickerRow(
    taskList: CalDavCalendar,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(taskList.color?.let(::Color) ?: MaterialTheme.colorScheme.tertiary),
            )
            Text(
                modifier = Modifier.weight(1f),
                text = taskList.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LinkedMapLocationRow(
    name: String,
    point: GeoPoint,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.LocationOn,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = name.ifBlank { "Maps location" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${point.latitude.formatCoordinate()}, ${point.longitude.formatCoordinate()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClear) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Clear linked Maps location",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun EventCalendarPicker(
    calendars: List<CalendarSource>,
    selectedCalendarId: Long,
    onSelected: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Calendar",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            calendars.forEach { calendar ->
                CalendarPickerRow(
                    calendar = calendar,
                    selected = calendar.id == selectedCalendarId,
                    onClick = { onSelected(calendar.id) },
                )
            }
        }
    }
}

@Composable
private fun CalendarPickerRow(
    calendar: CalendarSource,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(calendar.drawerColor()),
            )
            Text(
                modifier = Modifier.weight(1f),
                text = calendar.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RepeatPicker(
    recurrenceRule: String?,
    onSelected: (String?) -> Unit,
) {
    val selectedOption = RepeatOption.fromRule(recurrenceRule)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Repeat",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RepeatOptions.chunked(3).forEach { rowOptions ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowOptions.forEach { option ->
                        FilterChip(
                            selected = option == selectedOption,
                            onClick = { onSelected(option.rule) },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
        }
    }
}

private enum class AddCalendarType {
    Url,
    CalDav,
}

@Composable
private fun AddCalendarDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onSubscribe: (String, String?) -> Unit,
    onConnect: (String, String, String, String?) -> Unit,
) {
    var selectedType by rememberSaveable(initialUrl) { mutableStateOf(AddCalendarType.Url) }
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun submit() {
        when (selectedType) {
            AddCalendarType.Url -> {
                val cleanedUrl = url.trim()
                if (cleanedUrl.isBlank()) {
                    error = "Add a calendar URL."
                } else {
                    onSubscribe(cleanedUrl, displayName.trim().takeIf { it.isNotBlank() })
                }
            }

            AddCalendarType.CalDav -> when {
                serverUrl.isBlank() -> error = "Add a CalDAV server URL."
                username.isBlank() -> error = "Add a username."
                password.isBlank() -> error = "Add a password or app password."
                else -> onConnect(
                    serverUrl.trim(),
                    username.trim(),
                    password,
                    displayName.trim().takeIf { it.isNotBlank() },
                )
            }
        }
    }

    CalendarEditorDialogFrame(
        title = "Add calendar",
        saveLabel = if (selectedType == AddCalendarType.Url) "Subscribe" else "Connect",
        onDismiss = onDismiss,
        onSave = ::submit,
    ) {
        error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedType == AddCalendarType.Url,
                onClick = {
                    selectedType = AddCalendarType.Url
                    error = null
                },
                label = { Text("URL") },
            )
            FilterChip(
                selected = selectedType == AddCalendarType.CalDav,
                onClick = {
                    selectedType = AddCalendarType.CalDav
                    error = null
                },
                label = { Text("CalDAV") },
            )
        }
        when (selectedType) {
            AddCalendarType.Url -> {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    label = { Text("Calendar URL") },
                    placeholder = { Text("http://example.com/calendar.ics") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = displayName,
                    onValueChange = {
                        displayName = it
                        error = null
                    },
                    label = { Text("Name") },
                    placeholder = { Text("Optional") },
                    singleLine = true,
                )
            }

            AddCalendarType.CalDav -> {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = serverUrl,
                    onValueChange = {
                        serverUrl = it
                        error = null
                    },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://example.com/") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = username,
                    onValueChange = {
                        username = it
                        error = null
                    },
                    label = { Text("Username") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = displayName,
                    onValueChange = {
                        displayName = it
                        error = null
                    },
                    label = { Text("Name") },
                    placeholder = { Text("Optional") },
                    singleLine = true,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarOptionsDialog(
    item: DrawerCalendarItem,
    onDismiss: () -> Unit,
    onRenameSubscription: (String, String, Int) -> Unit,
    onRenameCalDavCalendar: (String, String, Int) -> Unit,
    onUnsubscribe: (String) -> Unit,
    onDisconnectCalDav: (String) -> Unit,
) {
    val subscription = item.subscription
    val calDavCalendar = item.calDavCalendar
    val calDavAccount = item.calDavAccount
    val initialColor = item.color ?: CalendarOptionColors.first()
    val colorOptions = remember(initialColor) {
        (listOf(initialColor) + CalendarOptionColors).distinct()
    }
    var displayName by remember(item.key) { mutableStateOf(item.displayName) }
    var selectedColor by remember(item.key) { mutableStateOf(initialColor) }
    val cleanedDisplayName = displayName.trim()
    val canEdit = subscription != null || calDavCalendar != null
    val canSave = canEdit &&
        cleanedDisplayName.isNotBlank() &&
        (cleanedDisplayName != item.displayName || selectedColor != initialColor)
    val secondaryText = item.secondaryText

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Calendar options",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close calendar options",
                    )
                }
            }

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                enabled = canEdit,
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )

            CalendarColorPicker(
                colors = colorOptions,
                selectedColor = selectedColor,
                enabled = canEdit,
                onSelected = { selectedColor = it },
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = when {
                        subscription != null -> "URL calendar"
                        calDavCalendar?.supportsEvents == true -> "CalDAV calendar"
                        calDavCalendar?.supportsTasks == true -> "CalDAV task list"
                        else -> "Android calendar"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                secondaryText?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                subscription?.let {
                    Text(
                        text = it.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                calDavAccount?.let { account ->
                    Text(
                        text = "${account.displayName} · ${account.baseUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!canEdit) {
                    Text(
                        text = "This calendar is managed outside this app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            when {
                subscription != null -> TextButton(
                    onClick = { onUnsubscribe(subscription.id) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Unsubscribe")
                }

                calDavCalendar != null -> TextButton(
                    onClick = { onDisconnectCalDav(calDavCalendar.accountId) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Disconnect")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        when {
                            subscription != null -> onRenameSubscription(
                                subscription.id,
                                cleanedDisplayName,
                                selectedColor,
                            )

                            calDavCalendar != null -> onRenameCalDavCalendar(
                                calDavCalendar.id,
                                cleanedDisplayName,
                                selectedColor,
                            )
                        }
                    },
                    enabled = canSave,
                ) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun CalendarColorPicker(
    colors: List<Int>,
    selectedColor: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Color",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.chunked(6).forEach { rowColors ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowColors.forEach { color ->
                        val isSelected = color == selectedColor
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(color))
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    shape = CircleShape,
                                )
                                .clickable(enabled = enabled) { onSelected(color) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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
private fun EmptyAgenda(state: CalendarUiState) {
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
                text = "No events or tasks in this range",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "More agenda items load automatically as you scroll.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun Context.hasCalendarPermissions(): Boolean {
    return CalendarPermissions.all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}

private fun Intent.subscriptionUrl(): String? {
    val uri = data ?: return null
    val scheme = uri.scheme?.lowercase()
    return uri.toString().takeIf { scheme == "webcal" || scheme == "webcals" }
}

private data class AgendaSection(
    val date: LocalDate,
    val items: List<AgendaItem>,
)

private sealed interface AgendaItem {
    val sortMillis: Long
    val sortRank: Int

    data class Event(
        val event: CalendarEvent,
        override val sortMillis: Long,
        override val sortRank: Int,
    ) : AgendaItem

    data class Task(
        val task: CalendarTask,
        override val sortMillis: Long,
        override val sortRank: Int,
    ) : AgendaItem
}

private enum class CalendarViewMode {
    Agenda,
    Month,
    Tasks,
}

private enum class TaskFilter(
    val label: String,
) {
    Active("Active"),
    Completed("Completed"),
    All("All"),
}

private enum class RepeatOption(
    val label: String,
    val rule: String?,
) {
    None("None", null),
    Daily("Daily", "FREQ=DAILY"),
    Weekly("Weekly", "FREQ=WEEKLY"),
    Monthly("Monthly", "FREQ=MONTHLY"),
    Yearly("Yearly", "FREQ=YEARLY");

    companion object {
        fun fromRule(rule: String?): RepeatOption {
            val normalizedRule = rule?.uppercase().orEmpty()
            return entries.firstOrNull { option ->
                option.rule != null && normalizedRule.startsWith(option.rule)
            } ?: None
        }
    }
}

private sealed interface EventDialogState {
    data class CreateChoice(
        val calendarId: Long,
        val date: LocalDate,
    ) : EventDialogState

    data class CreateEvent(
        val calendarId: Long,
        val date: LocalDate,
    ) : EventDialogState

    data class CreateTask(val date: LocalDate) : EventDialogState

    data class Details(val event: CalendarEvent) : EventDialogState

    data class Edit(val event: CalendarEvent) : EventDialogState

    data class Delete(val event: CalendarEvent) : EventDialogState

    data class TaskDetails(val task: CalendarTask) : EventDialogState
}

private data class EventFormState(
    val calendarId: Long,
    val title: String,
    val date: String,
    val startTime: String,
    val endTime: String,
    val allDay: Boolean,
    val recurrenceRule: String?,
    val location: String,
    val description: String,
    val locationPoint: GeoPoint? = null,
    val locationMapName: String? = null,
    val locationMapId: String? = null,
    val error: String? = null,
)

private data class TaskFormState(
    val collectionId: String?,
    val title: String,
    val date: String,
    val dueTime: String,
    val allDay: Boolean,
    val recurrenceRule: String?,
    val description: String,
    val error: String? = null,
)

private data class MapLocationSelection(
    val point: GeoPoint,
    val name: String,
    val mapId: String?,
)

private val WeekdayLabels = listOf("S", "M", "T", "W", "T", "F", "S")
private val RepeatOptions = listOf(
    RepeatOption.None,
    RepeatOption.Daily,
    RepeatOption.Weekly,
    RepeatOption.Monthly,
    RepeatOption.Yearly,
)

private val DateInputFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val TimeInputFormatter = DateTimeFormatter.ofPattern("H:mm")
private val TimeOutputFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val DefaultAgendaFutureDays = 30L
private const val AgendaPageDays = 90L
private const val AgendaLoadAheadItems = 4
private const val AgendaSelectionPaddingDays = 14L
private const val MapsPackageName = "digital.dutton.essentials.maps"
private const val MapsApiExtraPickPoint = "app.organicmaps.api.extra.PICK_POINT"
private const val MapsApiExtraPointName = "app.organicmaps.api.extra.POINT_NAME"
private const val MapsApiExtraPointLat = "app.organicmaps.api.extra.POINT_LAT"
private const val MapsApiExtraPointLon = "app.organicmaps.api.extra.POINT_LON"
private const val MapsApiExtraPointId = "app.organicmaps.api.extra.POINT_ID"
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

private fun agendaSectionsWithToday(
    events: List<CalendarEvent>,
    tasks: List<CalendarTask>,
    nowMillis: Long,
): List<AgendaSection> {
    val zone = ZoneId.systemDefault()
    val eventDates = events.flatMap { event -> event.agendaDates(zone) }
    val taskDates = tasks.mapNotNull { task -> task.agendaDate(zone) }
    val sections = (eventDates + taskDates)
        .distinct()
        .sorted()
        .map { date ->
            AgendaSection(
                date = date,
                items = agendaItemsForDate(
                    date = date,
                    events = events.filter { event -> date in event.agendaDates(zone) },
                    tasks = tasks.filter { task -> task.agendaDate(zone) == date },
                    nowMillis = nowMillis,
                ),
            )
        }
    val today = LocalDate.now()

    if (sections.any { it.date == today }) return sections

    return (sections + AgendaSection(today, emptyList()))
        .sortedBy { it.date }
}

private fun agendaItemsForDate(
    date: LocalDate,
    events: List<CalendarEvent>,
    tasks: List<CalendarTask>,
    nowMillis: Long,
): List<AgendaItem> {
    val zone = ZoneId.systemDefault()
    val dayStartMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
    return (
        events.map { event ->
            AgendaItem.Event(
                event = event,
                sortMillis = if (event.allDay) dayStartMillis else maxOf(event.startMillis, dayStartMillis),
                sortRank = if (event.allDay) 0 else 2,
            )
        } +
            tasks.map { task ->
                val taskSortMillis = task.dueMillis ?: task.startMillis ?: dayStartMillis
                AgendaItem.Task(
                    task = task,
                    sortMillis = if (taskSortMillis < nowMillis && task.agendaDate(zone) == LocalDate.now(zone)) {
                        dayStartMillis
                    } else {
                        taskSortMillis
                    },
                    sortRank = if (task.dueAllDay || task.dueMillis == null) 1 else 3,
                )
            }
        )
        .sortedWith(
            compareBy<AgendaItem> { it.sortMillis }
                .thenBy { it.sortRank }
                .thenBy {
                    when (it) {
                        is AgendaItem.Event -> it.event.title.lowercase()
                        is AgendaItem.Task -> it.task.title.lowercase()
                    }
                },
        )
}

private fun List<AgendaSection>.monthJunctionCountThrough(sectionIndex: Int): Int {
    return take(sectionIndex + 1)
        .filterIndexed { index, section ->
            index == 0 || YearMonth.from(section.date) != YearMonth.from(this[index - 1].date)
        }
        .size
}

private fun CalendarUiState.agendaSubtitle(): String {
    return when {
        !hasCalendarPermission -> "Calendar access needed"
        isSyncing -> "Syncing calendars"
        isLoading -> "Refreshing agenda"
        else -> "${drawerCalendarItems().size.calendarCountLabel()}, ${tasks.size.taskCountLabel()}"
    }
}

private fun CalendarUiState.hasRemoteCalendars(): Boolean {
    return subscriptions.isNotEmpty() || calDavAccounts.isNotEmpty()
}

private fun LocalDate.compactDateLabel(): String {
    return format(DateTimeFormatter.ofPattern("d MMM yyyy"))
}

private fun Int.calendarCountLabel(): String {
    return when (this) {
        0 -> "No calendars"
        1 -> "1 calendar"
        else -> "$this calendars"
    }
}

private fun Int.taskCountLabel(): String {
    return when (this) {
        0 -> "no tasks"
        1 -> "1 task"
        else -> "$this tasks"
    }
}

private fun String.repeatLabel(): String? {
    return RepeatOption.fromRule(this).takeUnless { it == RepeatOption.None }?.label
}

private fun CalendarSubscription.lastSyncLabel(): String {
    val syncMillis = lastSyncMillis ?: return "Not synced yet"
    val syncTime = Instant.ofEpochMilli(syncMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
    return "Last synced $syncTime"
}

private fun CalDavCalendar.lastSyncLabel(): String {
    val syncMillis = lastSyncMillis ?: return "Not synced yet"
    val syncTime = Instant.ofEpochMilli(syncMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
    return "Last synced $syncTime"
}

private fun CalDavAccount.lastSyncLabel(): String {
    val syncMillis = lastSyncMillis ?: return "Not synced yet"
    val syncTime = Instant.ofEpochMilli(syncMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
    return "Last synced $syncTime"
}

@Composable
private fun CalendarSource.drawerColor(): Color {
    return color?.let(::Color) ?: MaterialTheme.colorScheme.primary
}

@Composable
private fun DrawerCalendarItem.drawerColor(): Color {
    return color?.let(::Color) ?: MaterialTheme.colorScheme.primary
}

private fun CalendarUiState.drawerCalendarItems(): List<DrawerCalendarItem> {
    val calendarsById = calendars.associateBy { it.id }
    val accountsById = calDavAccounts.associateBy { it.id }
    val managedProviderIds = buildSet {
        subscriptions.forEach { add(it.calendarId) }
        calDavCalendars.mapNotNullTo(this) { it.localCalendarId }
    }

    val subscriptionItems = subscriptions.map { subscription ->
        val providerCalendar = calendarsById[subscription.calendarId]
        DrawerCalendarItem(
            key = "subscription:${subscription.id}",
            displayName = subscription.displayName,
            color = providerCalendar?.color ?: subscription.color ?: UrlCalendarFallbackColor,
            isReadOnly = true,
            secondaryText = subscription.drawerStatusLabel(providerCalendar),
            calendar = providerCalendar,
            subscription = subscription,
        )
    }

    val calDavItems = calDavCalendars.map { calDavCalendar ->
        val providerCalendar = calDavCalendar.localCalendarId?.let(calendarsById::get)
        val account = accountsById[calDavCalendar.accountId]
        DrawerCalendarItem(
            key = "caldav:${calDavCalendar.id}",
            displayName = calDavCalendar.displayName,
            color = providerCalendar?.color
                ?: calDavCalendar.color
                ?: if (calDavCalendar.supportsTasks && !calDavCalendar.supportsEvents) {
                    TaskListFallbackColor
                } else {
                    CalDavCalendarFallbackColor
                },
            isReadOnly = false,
            secondaryText = calDavCalendar.drawerStatusLabel(account, providerCalendar),
            calendar = providerCalendar,
            calDavCalendar = calDavCalendar,
            calDavAccount = account,
        )
    }

    val unmanagedProviderItems = calendars
        .filter { it.id !in managedProviderIds }
        .map { calendar ->
            DrawerCalendarItem(
                key = "provider:${calendar.id}",
                displayName = calendar.displayName,
                color = calendar.color,
                isReadOnly = calendar.isDrawerReadOnly(),
                secondaryText = null,
                calendar = calendar,
            )
        }

    return subscriptionItems + calDavItems + unmanagedProviderItems
}

private fun CalendarSource.isDrawerReadOnly(): Boolean {
    return isSubscribed || !isWritable
}

private fun CalendarSubscription.drawerStatusLabel(providerCalendar: CalendarSource?): String {
    return when {
        !lastError.isNullOrBlank() -> "Sync error: $lastError"
        providerCalendar == null -> "Restoring calendar"
        else -> lastSyncLabel()
    }
}

private fun CalDavCalendar.drawerStatusLabel(
    account: CalDavAccount?,
    providerCalendar: CalendarSource?,
): String {
    val accountError = account?.lastError
    return when {
        !lastError.isNullOrBlank() -> "Sync error: $lastError"
        !accountError.isNullOrBlank() -> "Sync error: $accountError"
        supportsEvents && localCalendarId != null && providerCalendar == null -> "Restoring calendar"
        else -> lastSyncLabel()
    }
}

private const val UrlCalendarFallbackColor: Int = 0xFF2E7D62.toInt()
private const val CalDavCalendarFallbackColor: Int = 0xFF1E88E5.toInt()
private const val TaskListFallbackColor: Int = 0xFF7E57C2.toInt()
private val CalendarOptionColors = listOf(
    0xFFE53935.toInt(),
    0xFFD81B60.toInt(),
    0xFF8E24AA.toInt(),
    0xFF5E35B1.toInt(),
    0xFF3949AB.toInt(),
    0xFF1E88E5.toInt(),
    0xFF00897B.toInt(),
    0xFF43A047.toInt(),
    0xFF7CB342.toInt(),
    0xFFFDD835.toInt(),
    0xFFFB8C00.toInt(),
    0xFF6D4C41.toInt(),
)

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
    return firstOrNull { it.isVisible && it.isWritable } ?: firstOrNull { it.isWritable }
}

private fun List<CalendarSource>.writableEventCalendars(): List<CalendarSource> {
    return filter { it.isVisible && it.isWritable }
        .ifEmpty { filter { it.isWritable } }
}

private fun List<CalendarSource>.calendarName(calendarId: Long): String {
    return firstOrNull { it.id == calendarId }?.displayName ?: "Default calendar"
}

private fun List<CalDavCalendar>.taskLists(): List<CalDavCalendar> {
    return filter { it.supportsTasks }
        .sortedBy { it.displayName.lowercase() }
}

private fun newEventFormState(
    calendarId: Long,
    date: LocalDate,
): EventFormState {
    val start = LocalTime.now()
        .plusHours(1)
        .truncatedTo(ChronoUnit.HOURS)
    val end = start.plusHours(1)

    return EventFormState(
        calendarId = calendarId,
        title = "",
        date = date.format(DateInputFormatter),
        startTime = start.format(TimeOutputFormatter),
        endTime = end.format(TimeOutputFormatter),
        allDay = false,
        recurrenceRule = null,
        location = "",
        description = "",
    )
}

private fun newTaskFormState(
    date: LocalDate,
    collectionId: String?,
): TaskFormState {
    val dueTime = LocalTime.now()
        .plusHours(1)
        .truncatedTo(ChronoUnit.HOURS)

    return TaskFormState(
        collectionId = collectionId,
        title = "",
        date = date.format(DateInputFormatter),
        dueTime = dueTime.format(TimeOutputFormatter),
        allDay = true,
        recurrenceRule = null,
        description = "",
    )
}

private fun CalendarEvent.toFormState(): EventFormState {
    val zone = ZoneId.systemDefault()
    val dateZone = if (allDay) ZoneOffset.UTC else zone
    val start = Instant.ofEpochMilli(startMillis).atZone(dateZone)
    val end = Instant.ofEpochMilli(endMillis).atZone(dateZone)

    return EventFormState(
        calendarId = calendarId,
        title = title,
        date = start.toLocalDate().format(DateInputFormatter),
        startTime = start.toLocalTime().format(TimeOutputFormatter),
        endTime = end.toLocalTime().format(TimeOutputFormatter),
        allDay = allDay,
        recurrenceRule = recurrenceRule,
        location = location.orEmpty(),
        locationPoint = locationPoint,
        locationMapName = locationMapName,
        locationMapId = locationMapId,
        description = description.orEmpty(),
    )
}

private fun TaskFormState.toDraft(): CalendarTaskDraft {
    val cleanedTitle = title.trim()
    if (cleanedTitle.isBlank()) {
        throw IllegalArgumentException("Add a title.")
    }

    val taskDate = runCatching {
        LocalDate.parse(date.trim(), DateInputFormatter)
    }.getOrElse {
        throw IllegalArgumentException("Use a due date in YYYY-MM-DD format.")
    }

    val zone = ZoneId.systemDefault()
    val dueMillis: Long
    val taskTimeZone: String

    if (allDay) {
        dueMillis = taskDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        taskTimeZone = ZoneOffset.UTC.id
    } else {
        val parsedTime = parseTime(dueTime, "task")
        dueMillis = taskDate.atTime(parsedTime).atZone(zone).toInstant().toEpochMilli()
        taskTimeZone = zone.id
    }

    return CalendarTaskDraft(
        collectionId = collectionId,
        title = cleanedTitle,
        description = description.trim().takeIf { it.isNotBlank() },
        dueMillis = dueMillis,
        dueAllDay = allDay,
        timeZone = taskTimeZone,
        recurrenceRule = recurrenceRule,
        priority = null,
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
    val eventTimeZone: String

    if (allDay) {
        startMillis = eventDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        endMillis = eventDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        eventTimeZone = ZoneOffset.UTC.id
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
        eventTimeZone = zone.id
    }

    return CalendarEventDraft(
        calendarId = calendarId,
        title = cleanedTitle,
        location = location.trim().takeIf { it.isNotBlank() },
        description = description.trim().takeIf { it.isNotBlank() },
        startMillis = startMillis,
        endMillis = endMillis,
        allDay = allDay,
        timeZone = eventTimeZone,
        recurrenceRule = recurrenceRule,
        availability = EventAvailability.Busy,
        locationPoint = locationPoint,
        locationMapName = locationMapName,
        locationMapId = locationMapId,
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
    val dateZone = if (allDay) ZoneOffset.UTC else zone
    return Instant.ofEpochMilli(startMillis).atZone(dateZone).toLocalDate()
}

private fun CalendarEvent.agendaDates(zone: ZoneId): List<LocalDate> {
    val dateZone = if (allDay) ZoneOffset.UTC else zone
    val startDate = Instant.ofEpochMilli(startMillis).atZone(dateZone).toLocalDate()
    val effectiveEndMillis = (endMillis - 1).coerceAtLeast(startMillis)
    val endDate = Instant.ofEpochMilli(effectiveEndMillis).atZone(dateZone).toLocalDate()
    return datesBetweenInclusive(startDate, endDate)
}

private fun datesBetweenInclusive(
    startDate: LocalDate,
    endDate: LocalDate,
): List<LocalDate> {
    if (endDate.isBefore(startDate)) return listOf(startDate)

    val dates = mutableListOf<LocalDate>()
    var date = startDate
    while (!date.isAfter(endDate)) {
        dates += date
        date = date.plusDays(1)
    }
    return dates
}

private fun CalendarEventDraft.startDate(zone: ZoneId): LocalDate {
    val dateZone = if (allDay) ZoneOffset.UTC else zone
    return Instant.ofEpochMilli(startMillis).atZone(dateZone).toLocalDate()
}

private fun CalendarTaskDraft.dueDate(zone: ZoneId): LocalDate {
    val dateZone = if (dueAllDay) ZoneOffset.UTC else zone
    return Instant.ofEpochMilli(dueMillis).atZone(dateZone).toLocalDate()
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

private fun CalendarTask.isActive(): Boolean {
    return status != CalendarTaskStatus.Completed && status != CalendarTaskStatus.Cancelled
}

private fun CalendarTask.isCompleted(): Boolean {
    return status == CalendarTaskStatus.Completed
}

@Composable
private fun CalendarTask.accentColor(): Color {
    return listColor?.let(::Color) ?: MaterialTheme.colorScheme.tertiary
}

private fun CalendarTask.agendaMeta(): String {
    return dueSummaryLabel().orEmpty()
}

private fun CalendarTask.detailDueLabel(): String {
    return dueDetailLabel() ?: startDetailLabel() ?: "No due date"
}

private fun CalendarTask.dueSummaryLabel(): String? {
    val due = dueMillis ?: return startMillis?.let { "Starts ${taskDateTimeLabel(it, startAllDay)}" }
    val zone = ZoneId.systemDefault()
    val dueDate = Instant.ofEpochMilli(due).atZone(if (dueAllDay) ZoneOffset.UTC else zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        dueDate.isBefore(today) -> "Overdue"
        dueDate == today && dueAllDay -> "Due today"
        dueDate == today -> "Due ${taskDateTimeLabel(due, allDay = false)}"
        else -> "Due ${taskDateTimeLabel(due, dueAllDay)}"
    }
}

private fun CalendarTask.dueDetailLabel(): String? {
    return dueMillis?.let { "Due ${taskDateTimeLabel(it, dueAllDay)}" }
}

private fun CalendarTask.startDetailLabel(): String? {
    return startMillis?.let { "Starts ${taskDateTimeLabel(it, startAllDay)}" }
}

private fun CalendarTaskStatus.displayLabel(): String {
    return when (this) {
        CalendarTaskStatus.NeedsAction -> "Needs action"
        CalendarTaskStatus.InProcess -> "In progress"
        CalendarTaskStatus.Completed -> "Completed"
        CalendarTaskStatus.Cancelled -> "Cancelled"
        CalendarTaskStatus.Unknown -> "Unknown"
    }
}

private fun taskDateTimeLabel(
    millis: Long,
    allDay: Boolean,
): String {
    val zone = if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()
    val value = Instant.ofEpochMilli(millis).atZone(zone)
    return if (allDay) {
        value.toLocalDate().format(DateTimeFormatter.ofPattern("d MMM"))
    } else {
        value.format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
    }
}

private fun EventFormState.locationPickerIntent(): Intent {
    val query = location.trim()
    val uri = if (query.isBlank()) {
        Uri.Builder()
            .scheme("cm")
            .authority("crosshair")
            .appendQueryParameter("appname", "Calendar")
            .build()
    } else {
        Uri.Builder()
            .scheme("cm")
            .authority("search")
            .appendQueryParameter("query", query)
            .build()
    }

    return Intent(Intent.ACTION_VIEW, uri)
        .setPackage(MapsPackageName)
        .putExtra(MapsApiExtraPickPoint, true)
}

private fun Intent.toMapLocationSelection(): MapLocationSelection? {
    val latitude = getDoubleExtra(MapsApiExtraPointLat, Double.NaN)
    val longitude = getDoubleExtra(MapsApiExtraPointLon, Double.NaN)
    if (latitude.isNaN() || longitude.isNaN()) return null

    val point = runCatching { GeoPoint(latitude, longitude) }.getOrNull() ?: return null
    val name = getStringExtra(MapsApiExtraPointName)
        ?.takeIf { it.isNotBlank() }
        ?: "${point.latitude.formatCoordinate()}, ${point.longitude.formatCoordinate()}"

    return MapLocationSelection(
        point = point,
        name = name,
        mapId = getStringExtra(MapsApiExtraPointId)?.takeIf { it.isNotBlank() },
    )
}

private fun Double.formatCoordinate(): String {
    return "%.6f".format(java.util.Locale.US, this)
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
    val dateZone = if (allDay) ZoneOffset.UTC else zone
    val start = Instant.ofEpochMilli(startMillis).atZone(dateZone)
    val end = Instant.ofEpochMilli(endMillis).atZone(dateZone)

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
    val point = link.location.point
    val exactLocationUri = point?.let {
        Uri.Builder()
            .scheme("cm")
            .authority("map")
            .appendQueryParameter("ll", "${it.latitude},${it.longitude}")
            .appendQueryParameter("n", link.location.name)
            .appendQueryParameter("id", link.location.id)
            .appendQueryParameter("z", "17")
            .build()
    }
    val exactGeoUri = point?.let {
        Uri.parse(
            "geo:${it.latitude},${it.longitude}?q=${it.latitude},${it.longitude}(${Uri.encode(link.location.name)})",
        )
    }
    val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(providerLocation)}")
    val mapsIntent = Intent(Intent.ACTION_VIEW, exactLocationUri ?: geoUri)
        .setPackage(MapsPackageName)
        .apply {
            if (point == null) {
                putExtra(LocationIntentExtraSource, LocationIntentSourceCalendar)
                putExtra(LocationIntentExtraCalendarEventId, link.eventId)
                putExtra(LocationIntentExtraRawProviderLocation, providerLocation)
            }
        }

    runCatching {
        startActivity(mapsIntent)
    }.onFailure {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, exactGeoUri ?: geoUri))
        }
    }
}
