package digital.dutton.essentials.weather

import android.Manifest
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WeatherApp() }
    }
}

data class WeatherUiState(
    val selectedLocation: WeatherLocation? = null,
    val snapshot: WeatherSnapshot? = null,
    val searchQuery: String = "",
    val searchResults: List<WeatherLocation> = emptyList(),
    val isSearching: Boolean = false,
    val isRefreshing: Boolean = false,
    val shouldRequestCurrentLocationPermission: Boolean = false,
    val error: String? = null,
)

class WeatherViewModel(application: Application) : AndroidViewModel(application) {
    private val store = WeatherStore(application)
    private val client = OpenMeteoClient()
    private val currentLocationProvider = CurrentLocationProvider(application)
    private val savedLocation = store.loadLocation()
    private val _uiState = MutableStateFlow(WeatherUiState(selectedLocation = savedLocation))
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()
    private var searchJob: Job? = null
    private var refreshJob: Job? = null

    init {
        if (savedLocation != null) {
            refreshForecast(savedLocation)
        } else if (currentLocationProvider.hasLocationPermission()) {
            refreshCurrentLocation()
        } else {
            _uiState.update { it.copy(shouldRequestCurrentLocationPermission = true) }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                searchResults = emptyList(),
                isSearching = false,
                error = null,
            )
        }

        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) return

        searchJob = viewModelScope.launch {
            delay(SearchDebounceMillis)
            _uiState.update { it.copy(isSearching = true) }
            val result = runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) { client.searchLocations(trimmed) }
            }
            _uiState.update { state ->
                if (state.searchQuery.trim() != trimmed) return@update state
                result.fold(
                    onSuccess = { state.copy(searchResults = it, isSearching = false, error = null) },
                    onFailure = {
                        state.copy(
                            searchResults = emptyList(),
                            isSearching = false,
                            error = it.message ?: "Unable to search locations.",
                        )
                    },
                )
            }
        }
    }

    fun selectLocation(location: WeatherLocation) {
        searchJob?.cancel()
        store.saveLocation(location)
        _uiState.update {
            it.copy(
                selectedLocation = location,
                snapshot = null,
                searchQuery = "",
                searchResults = emptyList(),
                isSearching = false,
                error = null,
            )
        }
        refreshForecast(location)
    }

    fun refresh() {
        val location = _uiState.value.selectedLocation ?: return
        if (location.isCurrentLocation) {
            refreshCurrentLocation()
        } else {
            refreshForecast(location)
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(shouldRequestCurrentLocationPermission = false) }
        if (granted) {
            refreshCurrentLocation()
        }
    }

    private fun refreshForecast(location: WeatherLocation) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            val result = runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) { client.fetchForecast(location) }
            }
            _uiState.update { state ->
                if (state.selectedLocation?.id != location.id) return@update state
                result.fold(
                    onSuccess = { state.copy(snapshot = it, isRefreshing = false, error = null) },
                    onFailure = {
                        state.copy(
                            isRefreshing = false,
                            error = it.message ?: "Unable to load weather.",
                        )
                    },
                )
            }
        }
    }

    private fun refreshCurrentLocation() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            val result = runCatching {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    currentLocationProvider.currentWeatherLocation()
                } ?: error("Unable to get current location.")
            }
            result.fold(
                onSuccess = { location ->
                    _uiState.update {
                        it.copy(
                            selectedLocation = location,
                            snapshot = null,
                            isRefreshing = false,
                            error = null,
                        )
                    }
                    refreshForecast(location)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            error = error.message ?: "Unable to get current location.",
                        )
                    }
                },
            )
        }
    }

    private companion object {
        const val SearchDebounceMillis = 250L
    }
}

@Composable
private fun WeatherApp(viewModel: WeatherViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val units = WeatherUnits.from(configuration)
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        viewModel.onLocationPermissionResult(grants.values.any { it })
    }
    val colorScheme = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }

    LaunchedEffect(state.shouldRequestCurrentLocationPermission) {
        if (state.shouldRequestCurrentLocationPermission) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
            )
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            WeatherScreen(
                state = state,
                units = units,
                onSearchChange = viewModel::updateSearchQuery,
                onSelectLocation = viewModel::selectLocation,
                onRefresh = viewModel::refresh,
            )
        }
    }
}

@Composable
private fun WeatherScreen(
    state: WeatherUiState,
    units: WeatherUnits,
    onSearchChange: (String) -> Unit,
    onSelectLocation: (WeatherLocation) -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                WeatherHeader(
                    selectedLocation = state.selectedLocation,
                    isRefreshing = state.isRefreshing,
                    canRefresh = state.selectedLocation != null,
                    onRefresh = onRefresh,
                )
            }

            item {
                LocationSearch(
                    query = state.searchQuery,
                    isSearching = state.isSearching,
                    results = state.searchResults,
                    onQueryChange = onSearchChange,
                    onSelectLocation = onSelectLocation,
                )
            }

            state.error?.let { error ->
                item { ErrorPanel(error) }
            }

            val snapshot = state.snapshot
            when {
                snapshot != null -> {
                    item { CurrentWeatherPanel(snapshot, units) }
                    item { HourlyForecast(snapshot.hourly, units) }
                    item { DailyForecast(snapshot.daily, today = snapshot.current.time.toLocalDate(), units) }
                }
                state.isRefreshing -> {
                    item { LoadingPanel() }
                }
                state.selectedLocation == null -> {
                    item { EmptyPanel() }
                }
            }
        }
    }
}

@Composable
private fun WeatherHeader(
    selectedLocation: WeatherLocation?,
    isRefreshing: Boolean,
    canRefresh: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Weather",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = selectedLocation?.let { listOf(it.title, it.subtitle).filter { part -> part.isNotBlank() }.joinToString(", ") }
                    ?: "Choose a location",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onRefresh,
                enabled = canRefresh && !isRefreshing,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh weather",
                )
            }
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun LocationSearch(
    query: String,
    isSearching: Boolean,
    results: List<WeatherLocation>,
    onQueryChange: (String) -> Unit,
    onSelectLocation: (WeatherLocation) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                )
            },
            trailingIcon = {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            },
        )

        if (results.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Column {
                    results.forEachIndexed { index, location ->
                        LocationResultRow(
                            location = location,
                            onClick = { onSelectLocation(location) },
                        )
                        if (index != results.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationResultRow(
    location: WeatherLocation,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = location.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = location.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CurrentWeatherPanel(
    snapshot: WeatherSnapshot,
    units: WeatherUnits,
) {
    val current = snapshot.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = snapshot.location.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = weatherDescription(current.weatherCode),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Updated ${current.time.format(HourFormatter)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                    )
                }
                Text(
                    text = current.temperatureC.formatTemp(units),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WeatherMetric(
                    label = "Feels",
                    value = current.apparentTemperatureC.formatTemp(units),
                    modifier = Modifier.weight(1f),
                )
                WeatherMetric(
                    label = "Humidity",
                    value = "${current.humidityPercent}%",
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WeatherMetric(
                    label = "Wind",
                    value = "${current.windDirectionDegrees.compassDirection()} ${current.windSpeedKmh.formatWind(units)}",
                    modifier = Modifier.weight(1f),
                )
                WeatherMetric(
                    label = "Rain",
                    value = current.precipitationMm.formatPrecipitation(units),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun WeatherMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun HourlyForecast(
    hourly: List<HourlyWeather>,
    units: WeatherUnits,
) {
    if (hourly.isEmpty()) return
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Next 24 hours",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(hourly) { hour ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .width(86.dp)
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = hour.time.format(HourFormatter),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = hour.temperatureC.formatTemp(units),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = hour.precipitationProbabilityPercent?.let { "$it%" } ?: "-",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyForecast(
    daily: List<DailyWeather>,
    today: LocalDate,
    units: WeatherUnits,
) {
    if (daily.isEmpty()) return
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "7 days",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(8.dp),
        ) {
            Column {
                daily.forEachIndexed { index, day ->
                    DailyForecastRow(day, today, units)
                    if (index != daily.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyForecastRow(
    day: DailyWeather,
    today: LocalDate,
    units: WeatherUnits,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = day.date.dayLabel(today),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = weatherDescription(day.weatherCode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = day.precipitationProbabilityPercent?.let { "$it%" } ?: "-",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "${day.highC.formatTemp(units)} / ${day.lowC.formatTemp(units)}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ErrorPanel(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LoadingPanel() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyPanel() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "No location selected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Search by city to load an Open-Meteo forecast.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val HourFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d")

private fun Double.formatTemp(units: WeatherUnits): String {
    val value = when (units.temperatureUnit) {
        TemperatureUnit.Celsius -> this
        TemperatureUnit.Fahrenheit -> this * 9.0 / 5.0 + 32.0
    }
    return "${value.rounded(units.locale)}\u00B0"
}

private fun Double.formatWind(units: WeatherUnits): String {
    return when (units.windUnit) {
        WindUnit.Kmh -> "${rounded(units.locale)} km/h"
        WindUnit.Mph -> "${(this * 0.621371).rounded(units.locale)} mph"
    }
}

private fun Double.formatPrecipitation(units: WeatherUnits): String {
    return when (units.precipitationUnit) {
        PrecipitationUnit.Millimeters -> "${oneDecimal(units.locale)} mm"
        PrecipitationUnit.Inches -> "${(this / 25.4).twoDecimals(units.locale)} in"
    }
}

private fun Double.rounded(locale: Locale): String = String.format(locale, "%.0f", this)

private fun Double.oneDecimal(locale: Locale): String = String.format(locale, "%.1f", this)

private fun Double.twoDecimals(locale: Locale): String = String.format(locale, "%.2f", this)

private fun Int.compassDirection(): String {
    val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return directions[((this + 22) / 45).floorMod(directions.size)]
}

private fun Int.floorMod(other: Int): Int = Math.floorMod(this, other)

private fun LocalDate.dayLabel(today: LocalDate): String {
    return if (this == today) "Today" else format(DayFormatter)
}
