package digital.dutton.essentials.documents

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var inboundIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        inboundIntent = intent
        setContent {
            DocumentsApp(inboundIntent = inboundIntent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        inboundIntent = intent
    }
}

data class DocumentsUiState(
    val isOpening: Boolean = false,
    val document: OpenDocumentState? = null,
    val recentDocuments: List<RecentDocument> = emptyList(),
    val error: String? = null,
    val searchActive: Boolean = false,
    val searchQuery: String = "",
    val searchInProgress: Boolean = false,
    val searchHits: List<PdfSearchHit> = emptyList(),
    val activeSearchHit: Int = 0,
)

data class OpenDocumentState(
    val name: String,
    val pages: List<PdfPageSpec>,
    val formKind: PdfFormKind,
) {
    val pageCount: Int = pages.size
}

class DocumentsViewModel(application: Application) : AndroidViewModel(application) {
    private val recentDocuments = RecentDocumentsStore(application.applicationContext)
    private val _uiState = MutableStateFlow(
        DocumentsUiState(recentDocuments = recentDocuments.list()),
    )
    val uiState: StateFlow<DocumentsUiState> = _uiState.asStateFlow()

    private var activeSession: PdfDocumentSession? = null
    private var consumedIntentUri: Uri? = null
    private var searchJob: Job? = null

    fun consumeIntent(intent: Intent?) {
        val uri = intent?.documentUri() ?: return
        if (uri == consumedIntentUri) return
        consumedIntentUri = uri
        openDocument(
            uri = uri,
            persistReadPermission = intent.canPersistReadPermission(),
            recordRecent = intent.canPersistReadPermission(),
        )
    }

    fun openPickedDocument(uri: Uri) {
        openDocument(
            uri = uri,
            persistReadPermission = true,
            recordRecent = true,
        )
    }

    fun openRecentDocument(document: RecentDocument) {
        openDocument(
            uri = document.uri,
            persistReadPermission = false,
            recordRecent = true,
        )
    }

    fun removeRecentDocument(document: RecentDocument) {
        recentDocuments.remove(document.uri)
        _uiState.update { it.copy(recentDocuments = recentDocuments.list()) }
    }

    fun closeDocument() {
        searchJob?.cancel()
        activeSession?.close()
        activeSession = null
        _uiState.update {
            it.copy(
                document = null,
                searchActive = false,
                searchQuery = "",
                searchHits = emptyList(),
                searchInProgress = false,
                activeSearchHit = 0,
                error = null,
                recentDocuments = recentDocuments.list(),
            )
        }
    }

    fun setSearchActive(active: Boolean) {
        if (!active) {
            searchJob?.cancel()
            _uiState.update {
                it.copy(
                    searchActive = false,
                    searchQuery = "",
                    searchHits = emptyList(),
                    searchInProgress = false,
                    activeSearchHit = 0,
                )
            }
            return
        }

        _uiState.update { it.copy(searchActive = true, error = null) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                searchHits = if (query.isBlank()) emptyList() else it.searchHits,
                activeSearchHit = 0,
                searchInProgress = query.isNotBlank(),
            )
        }

        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(searchInProgress = false) }
            return
        }

        val session = activeSession ?: return
        val expectedQuery = query.trim()
        searchJob = viewModelScope.launch {
            delay(250)
            val hits = runCatching { session.search(expectedQuery) }
                .getOrDefault(emptyList())
            _uiState.update { state ->
                if (state.searchQuery.trim() == expectedQuery) {
                    state.copy(
                        searchHits = hits,
                        activeSearchHit = 0,
                        searchInProgress = false,
                    )
                } else {
                    state
                }
            }
        }
    }

    fun moveSearchHit(delta: Int) {
        _uiState.update { state ->
            if (state.searchHits.isEmpty()) return@update state
            val next = (state.activeSearchHit + delta + state.searchHits.size) % state.searchHits.size
            state.copy(activeSearchHit = next)
        }
    }

    suspend fun renderPage(
        pageIndex: Int,
        widthPx: Int,
    ): RenderedPdfPage? {
        return activeSession?.renderPage(pageIndex, widthPx)
    }

    private fun openDocument(
        uri: Uri,
        persistReadPermission: Boolean,
        recordRecent: Boolean,
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isOpening = true,
                    error = null,
                    searchActive = false,
                    searchQuery = "",
                    searchHits = emptyList(),
                    searchInProgress = false,
                    activeSearchHit = 0,
                )
            }

            runCatching {
                PdfDocumentSession.open(getApplication(), uri)
            }.onSuccess { session ->
                activeSession?.close()
                activeSession = session

                val canRecord = if (persistReadPermission) {
                    getApplication<Application>().persistReadPermission(uri)
                } else {
                    getApplication<Application>().hasPersistedReadPermission(uri)
                }
                if (recordRecent && canRecord) {
                    recentDocuments.record(uri, session.displayName)
                }

                _uiState.update {
                    it.copy(
                        isOpening = false,
                        document = OpenDocumentState(
                            name = session.displayName,
                            pages = session.pages,
                            formKind = session.formKind,
                        ),
                        recentDocuments = recentDocuments.list(),
                        error = null,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isOpening = false,
                        error = error.message ?: "Unable to open document.",
                    )
                }
            }
        }
    }

    override fun onCleared() {
        activeSession?.close()
        activeSession = null
        super.onCleared()
    }
}

@Composable
private fun DocumentsApp(
    inboundIntent: Intent?,
    viewModel: DocumentsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::openPickedDocument)
    }

    LaunchedEffect(inboundIntent) {
        viewModel.consumeIntent(inboundIntent)
    }

    BackHandler(enabled = state.searchActive) {
        viewModel.setSearchActive(false)
    }
    BackHandler(enabled = state.document != null && !state.searchActive) {
        viewModel.closeDocument()
    }

    DocumentsTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            val openPicker = { openDocumentLauncher.launch(arrayOf("application/pdf", "application/x-pdf")) }
            val document = state.document
            if (document == null) {
                LibraryScreen(
                    state = state,
                    onOpenPicker = openPicker,
                    onOpenRecent = viewModel::openRecentDocument,
                    onRemoveRecent = viewModel::removeRecentDocument,
                )
            } else {
                PdfViewerScreen(
                    state = state,
                    document = document,
                    onClose = viewModel::closeDocument,
                    onOpenPicker = openPicker,
                    onSearchActive = { viewModel.setSearchActive(true) },
                    onSearchClose = { viewModel.setSearchActive(false) },
                    onSearchQuery = viewModel::setSearchQuery,
                    onMoveSearchHit = viewModel::moveSearchHit,
                    renderPage = viewModel::renderPage,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    state: DocumentsUiState,
    onOpenPicker: () -> Unit,
    onOpenRecent: (RecentDocument) -> Unit,
    onRemoveRecent: (RecentDocument) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Documents") },
                actions = {
                    IconButton(onClick = onOpenPicker) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = "Open document")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onOpenPicker) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = "Open document")
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
            ) {
                if (state.isOpening) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                ErrorBanner(error = state.error)

                if (state.recentDocuments.isEmpty()) {
                    EmptyLibrary(onOpenPicker = onOpenPicker)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp, top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = state.recentDocuments,
                            key = { it.uri.toString() },
                        ) { document ->
                            RecentDocumentCard(
                                document = document,
                                onClick = { onOpenRecent(document) },
                                onRemove = { onRemoveRecent(document) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onOpenPicker: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Description,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Open a PDF",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Button(onClick = onOpenPicker) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Choose document")
            }
        }
    }
}

@Composable
private fun RecentDocumentCard(
    document: RecentDocument,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = document.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = document.openedAtLabel(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Document actions")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Remove from recents") },
                        onClick = {
                            menuExpanded = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfViewerScreen(
    state: DocumentsUiState,
    document: OpenDocumentState,
    onClose: () -> Unit,
    onOpenPicker: () -> Unit,
    onSearchActive: () -> Unit,
    onSearchClose: () -> Unit,
    onSearchQuery: (String) -> Unit,
    onMoveSearchHit: (Int) -> Unit,
    renderPage: suspend (Int, Int) -> RenderedPdfPage?,
) {
    val listState = rememberLazyListState()
    val activeHit = state.searchHits.getOrNull(state.activeSearchHit)

    LaunchedEffect(activeHit?.pageIndex, state.activeSearchHit) {
        activeHit?.let { listState.animateScrollToItem(it.pageIndex) }
    }

    Scaffold(
        topBar = {
            if (state.searchActive) {
                SearchTopBar(
                    query = state.searchQuery,
                    hitCount = state.searchHits.size,
                    activeHit = state.activeSearchHit,
                    searchInProgress = state.searchInProgress,
                    onQueryChange = onSearchQuery,
                    onClose = onSearchClose,
                    onMove = onMoveSearchHit,
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = document.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${listState.firstVisibleItemIndex + 1} of ${document.pageCount}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close document")
                        }
                    },
                    actions = {
                        IconButton(onClick = onSearchActive) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = onOpenPicker) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = "Open document")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    start = 10.dp,
                    end = 10.dp,
                    top = 10.dp,
                    bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items = document.pages,
                    key = { it.index },
                ) { page ->
                    PdfPageView(
                        page = page,
                        hits = state.searchHits.filter { it.pageIndex == page.index },
                        activeHit = activeHit?.takeIf { it.pageIndex == page.index },
                        renderPage = renderPage,
                    )
                }
            }

            if (state.isOpening) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            ErrorBanner(error = state.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    hitCount: Int,
    activeHit: Int,
    searchInProgress: Boolean,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onMove: (Int) -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close search")
            }
        },
        title = {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search") },
            )
        },
        actions = {
            val counter = when {
                searchInProgress -> "..."
                hitCount == 0 -> "0"
                else -> "${activeHit + 1}/$hitCount"
            }
            Text(
                text = counter,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = { onMove(-1) },
                enabled = hitCount > 0,
            ) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Previous result")
            }
            IconButton(
                onClick = { onMove(1) },
                enabled = hitCount > 0,
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Next result")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
private fun PdfPageView(
    page: PdfPageSpec,
    hits: List<PdfSearchHit>,
    activeHit: PdfSearchHit?,
    renderPage: suspend (Int, Int) -> RenderedPdfPage?,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val targetWidthPx = with(density) { maxWidth.toPx().roundToInt() }.coerceAtLeast(1)
        var renderedPage by remember(page.index, targetWidthPx) {
            mutableStateOf<RenderedPdfPage?>(null)
        }
        var renderFailed by remember(page.index, targetWidthPx) {
            mutableStateOf(false)
        }

        LaunchedEffect(page.index, targetWidthPx) {
            renderedPage = null
            renderFailed = false
            runCatching {
                renderPage(page.index, targetWidthPx)
            }.onSuccess {
                renderedPage = it
            }.onFailure {
                renderFailed = true
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shape = RoundedCornerShape(2.dp),
            shadowElevation = 1.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(page.sourceWidth.toFloat() / page.sourceHeight.toFloat())
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = renderedPage?.bitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Page ${page.index + 1}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                    PageSearchHighlights(
                        page = page,
                        hits = hits,
                        activeHit = activeHit,
                    )
                } else if (renderFailed) {
                    Text(
                        text = "Unable to render page",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun PageSearchHighlights(
    page: PdfPageSpec,
    hits: List<PdfSearchHit>,
    activeHit: PdfSearchHit?,
) {
    if (hits.isEmpty()) return

    val highlight = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.32f)
    val activeHighlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.36f)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val scaleX = size.width / page.sourceWidth.toFloat()
        val scaleY = size.height / page.sourceHeight.toFloat()
        hits.forEach { hit ->
            val color = if (hit == activeHit) activeHighlight else highlight
            hit.bounds.forEach { rect ->
                drawRect(
                    color = color,
                    topLeft = Offset(rect.left * scaleX, rect.top * scaleY),
                    size = Size(rect.width() * scaleX, rect.height() * scaleY),
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(error: String?) {
    if (error == null) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = error,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DocumentsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        darkColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

private fun Intent.documentUri(): Uri? {
    return when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> streamExtra()
        else -> null
    }
}

private fun Intent.canPersistReadPermission(): Boolean {
    return (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0 &&
        (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
}

private fun Intent.streamExtra(): Uri? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        (getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)
    }
}

private fun Context.persistReadPermission(uri: Uri): Boolean {
    return runCatching {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }.getOrDefault(false)
}

private fun Context.hasPersistedReadPermission(uri: Uri): Boolean {
    return contentResolver.persistedUriPermissions.any { permission ->
        permission.uri == uri && permission.isReadPermission
    }
}

private fun RecentDocument.openedAtLabel(): String {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(openedAtMillis))
}
