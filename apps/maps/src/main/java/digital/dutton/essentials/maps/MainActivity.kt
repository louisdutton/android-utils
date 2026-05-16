package digital.dutton.essentials.maps

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import digital.dutton.essentials.locations.EventLocationLink
import digital.dutton.essentials.locations.GeoPoint
import digital.dutton.essentials.locations.MapLocation
import digital.dutton.essentials.maps.data.AndroidDeviceLocationRepository
import digital.dutton.essentials.maps.data.AndroidGeocoderLocationResolver
import digital.dutton.essentials.maps.data.MapDataSources
import digital.dutton.essentials.maps.data.MapStyleSource
import digital.dutton.essentials.maps.data.RoutePreview
import digital.dutton.essentials.maps.data.SavedPlacesRepository
import digital.dutton.essentials.maps.data.StraightLineRoutePlanner
import digital.dutton.essentials.maps.data.toMapLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap as NativeMapLibreMap

private val MapPermissions = arrayOf(
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_FINE_LOCATION,
)

class MainActivity : ComponentActivity() {
    private var incomingGeoUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingGeoUri = intent?.data
        enableEdgeToEdge()
        setContent {
            MapsApp(incomingGeoUri = incomingGeoUri)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingGeoUri = intent.data
    }
}

data class MapsUiState(
    val mapStyle: MapStyleSource = MapDataSources.developmentStyle,
    val searchQuery: String = "",
    val searchResults: List<MapLocation> = emptyList(),
    val savedPlaces: List<MapLocation> = emptyList(),
    val eventLinks: List<EventLocationLink> = emptyList(),
    val selectedPlace: MapLocation? = null,
    val currentLocation: GeoPoint? = null,
    val routePreview: RoutePreview? = null,
    val hasLocationPermission: Boolean = false,
    val isSearching: Boolean = false,
    val isLocating: Boolean = false,
    val error: String? = null,
)

class MapsViewModel(application: Application) : AndroidViewModel(application) {
    private val savedPlacesRepository = SavedPlacesRepository(application.applicationContext)
    private val deviceLocationRepository = AndroidDeviceLocationRepository(application.applicationContext)
    private val locationResolver = AndroidGeocoderLocationResolver(application.applicationContext)
    private val routePlanner = StraightLineRoutePlanner()

    private val _uiState = MutableStateFlow(
        MapsUiState(savedPlaces = savedPlacesRepository.load()),
    )
    val uiState: StateFlow<MapsUiState> = _uiState.asStateFlow()

    fun refreshLocationPermission() {
        _uiState.update {
            it.copy(hasLocationPermission = deviceLocationRepository.hasPermission())
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update {
            it.copy(searchQuery = query, error = null)
        }
    }

    fun submitSearch() {
        search(query = uiState.value.searchQuery, selectFirst = false)
    }

    fun openGeoUri(uri: Uri) {
        val location = uri.toMapLocation() ?: return
        _uiState.update {
            it.copy(
                searchQuery = location.address ?: location.name,
                selectedPlace = location,
                searchResults = location.point?.let { listOf(location) } ?: it.searchResults,
                routePreview = null,
                error = null,
            )
        }

        if (location.point == null) {
            search(query = location.name, selectFirst = true)
        }
    }

    fun selectPlace(location: MapLocation) {
        _uiState.update {
            it.copy(
                selectedPlace = location,
                routePreview = null,
                error = null,
            )
        }
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(
                selectedPlace = null,
                routePreview = null,
                error = null,
            )
        }
    }

    fun saveSelectedPlace() {
        val selectedPlace = uiState.value.selectedPlace ?: return
        val savedPlaces = savedPlacesRepository.save(selectedPlace)
        _uiState.update {
            it.copy(savedPlaces = savedPlaces)
        }
    }

    fun requestCurrentLocation() {
        refreshLocationPermission()
        if (!uiState.value.hasLocationPermission) {
            _uiState.update { it.copy(error = "Location permission required") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLocating = true, error = null) }
            val location = deviceLocationRepository.currentLocation()
            _uiState.update {
                it.copy(
                    currentLocation = location,
                    isLocating = false,
                    error = if (location == null) "Current location unavailable" else null,
                )
            }
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasLocationPermission = granted) }
        if (granted) requestCurrentLocation()
    }

    fun routeToSelectedPlace() {
        val state = uiState.value
        val origin = state.currentLocation
        val destination = state.selectedPlace

        if (destination == null) return
        if (origin == null) {
            _uiState.update { it.copy(error = "Current location unavailable") }
            requestCurrentLocation()
            return
        }

        val route = routePlanner.previewRoute(origin, destination)
        _uiState.update {
            it.copy(
                routePreview = route,
                error = if (route == null) "Route unavailable" else null,
            )
        }
    }

    private fun search(
        query: String,
        selectFirst: Boolean,
    ) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(isSearching = true, error = null)
            }

            val results = locationResolver.search(normalizedQuery)
            _uiState.update {
                it.copy(
                    searchResults = results,
                    selectedPlace = if (selectFirst) results.firstOrNull() ?: it.selectedPlace else it.selectedPlace,
                    isSearching = false,
                    routePreview = null,
                    error = if (results.isEmpty()) "No places found" else null,
                )
            }
        }
    }
}

@Composable
private fun MapsApp(
    incomingGeoUri: Uri?,
    viewModel: MapsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        viewModel.onLocationPermissionResult(permissions.values.any { it })
    }

    LaunchedEffect(Unit) {
        viewModel.refreshLocationPermission()
    }

    LaunchedEffect(incomingGeoUri) {
        incomingGeoUri?.let(viewModel::openGeoUri)
    }

    MapsTheme {
        MapsScreen(
            state = state,
            onSearchQueryChange = viewModel::onSearchQueryChange,
            onSubmitSearch = viewModel::submitSearch,
            onSelectPlace = viewModel::selectPlace,
            onClearSelection = viewModel::clearSelection,
            onSaveSelectedPlace = viewModel::saveSelectedPlace,
            onRouteToSelectedPlace = viewModel::routeToSelectedPlace,
            onRequestCurrentLocation = {
                if (state.hasLocationPermission) {
                    viewModel.requestCurrentLocation()
                } else {
                    permissionLauncher.launch(MapPermissions)
                }
            },
        )
    }
}

@Composable
private fun MapsScreen(
    state: MapsUiState,
    onSearchQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onSelectPlace: (MapLocation) -> Unit,
    onClearSelection: () -> Unit,
    onSaveSelectedPlace: () -> Unit,
    onRouteToSelectedPlace: () -> Unit,
    onRequestCurrentLocation: () -> Unit,
) {
    val mapPlaces = remember(
        state.savedPlaces,
        state.searchResults,
        state.eventLinks,
        state.selectedPlace,
    ) {
        buildList {
            addAll(state.savedPlaces)
            addAll(state.searchResults)
            addAll(state.eventLinks.map { it.location })
            state.selectedPlace?.let(::add)
        }.distinctBy { it.id }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(onClick = onRequestCurrentLocation) {
                if (state.isLocating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.MyLocation,
                        contentDescription = "Current location",
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            MapLibreSurface(
                modifier = Modifier.fillMaxSize(),
                mapStyle = state.mapStyle,
                places = mapPlaces,
                selectedPlace = state.selectedPlace,
                currentLocation = state.currentLocation,
                routePreview = state.routePreview,
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MapsTopBar(
                    query = state.searchQuery,
                    isSearching = state.isSearching,
                    onQueryChange = onSearchQueryChange,
                    onSubmitSearch = onSubmitSearch,
                )
                Spacer(modifier = Modifier.weight(1f))
                PlacesTray(
                    state = state,
                    onSelectPlace = onSelectPlace,
                    onClearSelection = onClearSelection,
                    onSaveSelectedPlace = onSaveSelectedPlace,
                    onRouteToSelectedPlace = onRouteToSelectedPlace,
                )
            }
        }
    }
}

@Composable
private fun MapsTopBar(
    query: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { }) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Open navigation drawer",
                )
            }
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextField(
                modifier = Modifier.weight(1f),
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search places") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun PlacesTray(
    state: MapsUiState,
    onSelectPlace: (MapLocation) -> Unit,
    onClearSelection: () -> Unit,
    onSaveSelectedPlace: () -> Unit,
    onRouteToSelectedPlace: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 5.dp,
    ) {
        LazyColumn(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                PlacesTrayHeader(
                    selectedPlace = state.selectedPlace,
                    calendarCount = state.eventLinks.size,
                    onClearSelection = onClearSelection,
                )
            }

            state.error?.let { error ->
                item {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            state.selectedPlace?.let { place ->
                item {
                    SelectedPlace(
                        place = place,
                        routePreview = state.routePreview,
                        onSave = onSaveSelectedPlace,
                        onRoute = onRouteToSelectedPlace,
                    )
                }
            }

            if (state.searchResults.isNotEmpty()) {
                item {
                    SectionTitle("Search results")
                }
                items(
                    items = state.searchResults,
                    key = { "search-${it.id}" },
                ) { place ->
                    PlaceRow(
                        location = place,
                        sourceLabel = "Search",
                        leadingIcon = Icons.Rounded.Search,
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = { onSelectPlace(place) },
                    )
                }
            }

            if (state.savedPlaces.isNotEmpty()) {
                item {
                    SectionTitle("Saved")
                }
                items(
                    items = state.savedPlaces,
                    key = { "saved-${it.id}" },
                ) { place ->
                    PlaceRow(
                        location = place,
                        sourceLabel = "Saved",
                        leadingIcon = Icons.Rounded.Bookmark,
                        accent = MaterialTheme.colorScheme.secondary,
                        onClick = { onSelectPlace(place) },
                    )
                }
            }

            if (state.eventLinks.isNotEmpty()) {
                item {
                    SectionTitle("Calendar")
                }
                items(
                    items = state.eventLinks,
                    key = { "calendar-${it.eventId}-${it.location.id}" },
                ) { link ->
                    PlaceRow(
                        location = link.location,
                        sourceLabel = "Calendar",
                        leadingIcon = Icons.Rounded.CalendarToday,
                        accent = MaterialTheme.colorScheme.tertiary,
                        onClick = { onSelectPlace(link.location) },
                    )
                }
            }

            if (
                state.selectedPlace == null &&
                state.searchResults.isEmpty() &&
                state.savedPlaces.isEmpty() &&
                state.eventLinks.isEmpty()
            ) {
                item {
                    Text(
                        text = "No places saved",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlacesTrayHeader(
    selectedPlace: MapLocation?,
    calendarCount: Int,
    onClearSelection: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = selectedPlace?.name ?: "Places",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "$calendarCount from Calendar",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (selectedPlace != null) {
            IconButton(onClick = onClearSelection) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close place details",
                )
            }
        }
    }
}

@Composable
private fun SelectedPlace(
    place: MapLocation,
    routePreview: RoutePreview?,
    onSave: () -> Unit,
    onRoute: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = place.displayAddress,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        routePreview?.let { route ->
            Text(
                text = route.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            TextButton(onClick = onSave) {
                Icon(
                    imageVector = Icons.Rounded.Bookmark,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save")
            }
            Button(onClick = onRoute) {
                Icon(
                    imageVector = Icons.Rounded.Navigation,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Route")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun PlaceRow(
    location: MapLocation,
    sourceLabel: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = accent.copy(alpha = 0.16f),
            contentColor = accent,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = location.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = location.displayAddress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = sourceLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MapLibreSurface(
    mapStyle: MapStyleSource,
    places: List<MapLocation>,
    selectedPlace: MapLocation?,
    currentLocation: GeoPoint?,
    routePreview: RoutePreview?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
    var nativeMap by remember { mutableStateOf<NativeMapLibreMap?>(null) }
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                map.uiSettings.isCompassEnabled = true
                map.uiSettings.isAttributionEnabled = true
                map.setStyle(mapStyle.url) {
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(51.5074, -0.1278))
                        .zoom(10.0)
                        .build()
                    nativeMap = map
                }
            }
        }
    }

    DisposableEffect(lifecycle, mapView) {
        var isDestroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    mapView.onDestroy()
                    isDestroyed = true
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!isDestroyed) {
                mapView.onDestroy()
            }
        }
    }

    LaunchedEffect(
        nativeMap,
        places,
        selectedPlace,
        currentLocation,
        routePreview,
        routeColor,
    ) {
        nativeMap?.renderPlaces(
            places = places,
            currentLocation = currentLocation,
            routePreview = routePreview,
            routeColor = routeColor,
        )
    }

    LaunchedEffect(nativeMap, selectedPlace?.point, currentLocation) {
        val focus = selectedPlace?.point ?: currentLocation
        focus?.let { point ->
            nativeMap?.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(point.toLatLng())
                        .zoom(13.0)
                        .build(),
                ),
            )
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
    )
}

@Suppress("DEPRECATION")
private fun NativeMapLibreMap.renderPlaces(
    places: List<MapLocation>,
    currentLocation: GeoPoint?,
    routePreview: RoutePreview?,
    routeColor: Int,
) {
    clear()

    routePreview?.points?.takeIf { it.size >= 2 }?.let { points ->
        addPolyline(
            PolylineOptions()
                .addAll(points.map { it.toLatLng() })
                .color(routeColor)
                .width(8f),
        )
    }

    places.forEach { location ->
        val point = location.point ?: return@forEach
        addMarker(
            MarkerOptions()
                .position(point.toLatLng())
                .title(location.name)
                .snippet(location.displayAddress),
        )
    }

    currentLocation?.let { point ->
        addMarker(
            MarkerOptions()
                .position(point.toLatLng())
                .title("Current location")
                .snippet("${point.latitude}, ${point.longitude}"),
        )
    }
}

private fun GeoPoint.toLatLng(): LatLng {
    return LatLng(latitude, longitude)
}

@Composable
private fun MapsTheme(content: @Composable () -> Unit) {
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
