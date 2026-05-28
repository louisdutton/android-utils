package digital.dutton.essentials.scores

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<ScoresViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScoresApp(viewModel = viewModel)
        }
        viewModel.openFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.openFromIntent(intent)
    }
}

data class ScoresUiState(
    val records: List<ScoreRecord> = emptyList(),
    val selectedRecord: ScoreRecord? = null,
    val renderedPages: List<RenderedScorePage> = emptyList(),
    val progress: ImportProgress? = null,
    val isImporting: Boolean = false,
    val isRendering: Boolean = false,
    val error: String? = null,
)

class ScoresViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ScoresStore(application)
    private val importer = ScoreImporter(application, store)
    private val renderer = VerovioScoreRenderer(application)
    private val _state = MutableStateFlow(ScoresUiState(records = store.revalidateCompletedRecords()))
    private var importJob: Job? = null
    private var lastIntentUri: Uri? = null

    val state: StateFlow<ScoresUiState> = _state

    fun openFromIntent(intent: Intent?) {
        val uri = intent?.scoreUri() ?: return
        if (lastIntentUri == uri) return
        lastIntentUri = uri
        importUri(uri, intent.type)
    }

    fun importUri(
        uri: Uri,
        mimeType: String? = null,
    ) {
        importJob?.cancel()
        val app = getApplication<Application>()
        val displayName = app.displayNameFor(uri)
        val source = ScoreSource(
            uri = uri,
            mimeType = mimeType ?: app.contentResolver.getType(uri),
            displayName = displayName,
        )
        importJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isImporting = true,
                    progress = ImportProgress(ImportStage.Queued, message = "Queued"),
                    error = null,
                    selectedRecord = null,
                    renderedPages = emptyList(),
                )
            }
            try {
                val result = importer.`import`(source) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
                refresh()
                selectRecord(result.record)
            } catch (_: CancellationException) {
                refresh()
            } finally {
                _state.update { it.copy(isImporting = false) }
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    fun selectRecord(record: ScoreRecord) {
        _state.update {
            it.copy(
                selectedRecord = record,
                renderedPages = emptyList(),
                isRendering = record.state == ScoreImportState.Complete,
                error = null,
            )
        }
        if (record.state == ScoreImportState.Complete) {
            render(record)
        }
    }

    fun closeRecord() {
        _state.update { it.copy(selectedRecord = null, renderedPages = emptyList(), isRendering = false) }
    }

    fun deleteRecord(record: ScoreRecord) {
        store.delete(record)
        _state.update {
            it.copy(
                records = store.list(),
                selectedRecord = null,
                renderedPages = emptyList(),
                isRendering = false,
            )
        }
    }

    private fun render(record: ScoreRecord) {
        viewModelScope.launch {
            val path = record.musicXmlPath
            if (path == null) {
                _state.update { it.copy(isRendering = false) }
                return@launch
            }

            runCatching {
                renderer.render(File(path).readBytes(), targetWidthPx = 1200)
            }.onSuccess { pages ->
                _state.update { it.copy(renderedPages = pages, isRendering = false) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isRendering = false,
                        error = error.message ?: "Unable to render score.",
                    )
                }
            }
        }
    }

    private fun refresh() {
        _state.update { it.copy(records = store.revalidateCompletedRecords()) }
    }
}

@Composable
private fun ScoresApp(viewModel: ScoresViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val colorScheme = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importUri(uri)
    }

    KeepScreenOn(state.isImporting)

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            ScoresScreen(
                state = state,
                onOpenFile = {
                    openDocumentLauncher.launch(
                        arrayOf(
                            ScoreMimeTypes.Pdf,
                            "image/*",
                            ScoreMimeTypes.MusicXml,
                            ScoreMimeTypes.CompressedMusicXml,
                            "application/xml",
                            "text/xml",
                        ),
                    )
                },
                onCancelImport = viewModel::cancelImport,
                onSelectRecord = viewModel::selectRecord,
                onCloseRecord = viewModel::closeRecord,
                onDeleteRecord = viewModel::deleteRecord,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScoresScreen(
    state: ScoresUiState,
    onOpenFile: () -> Unit,
    onCancelImport: () -> Unit,
    onSelectRecord: (ScoreRecord) -> Unit,
    onCloseRecord: () -> Unit,
    onDeleteRecord: (ScoreRecord) -> Unit,
) {
    val selectedRecord = state.selectedRecord
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selectedRecord?.title ?: "Scores",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (selectedRecord != null) {
                        IconButton(onClick = onCloseRecord) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (selectedRecord == null) {
                        IconButton(onClick = onOpenFile) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = "Open score")
                        }
                    } else {
                        IconButton(onClick = { onDeleteRecord(selectedRecord) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete score")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (selectedRecord == null) {
            ScoreLibraryScreen(
                modifier = Modifier.padding(padding),
                state = state,
                onOpenFile = onOpenFile,
                onCancelImport = onCancelImport,
                onSelectRecord = onSelectRecord,
            )
        } else {
            ScoreDetailScreen(
                modifier = Modifier.padding(padding),
                state = state,
                record = selectedRecord,
            )
        }
    }
}

@Composable
private fun ScoreLibraryScreen(
    modifier: Modifier,
    state: ScoresUiState,
    onOpenFile: () -> Unit,
    onCancelImport: () -> Unit,
    onSelectRecord: (ScoreRecord) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.isImporting) {
            item {
                ImportProgressPanel(
                    progress = state.progress,
                    onCancelImport = onCancelImport,
                )
            }
        }

        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenFile,
            ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Open file")
            }
        }

        if (state.records.isEmpty() && !state.isImporting) {
            item {
                EmptyScoresPanel()
            }
        }

        items(
            items = state.records,
            key = { it.id },
        ) { record ->
            ScoreRecordRow(
                record = record,
                onClick = { onSelectRecord(record) },
            )
        }
    }
}

@Composable
private fun ImportProgressPanel(
    progress: ImportProgress?,
    onCancelImport: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = progress?.message ?: "Importing",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    progress?.pageIndex?.let { pageIndex ->
                        Text(
                            text = "Page ${pageIndex + 1} of ${progress.pageCount ?: "?"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onCancelImport) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cancel import")
                }
            }
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun EmptyScoresPanel() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "No scores",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ScoreRecordRow(
    record: ScoreRecord,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        ListItem(
            leadingContent = {
                Icon(Icons.Rounded.Description, contentDescription = null)
            },
            headlineContent = {
                Text(
                    text = record.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    text = "${record.state.name} · ${record.sourceMime} · ${record.updatedAtMillis.asDateLabel()}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                if (record.warnings.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            modifier = Modifier.size(18.dp),
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = "Warnings",
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(record.warnings.size.toString())
                    }
                }
            },
        )
    }
}

@Composable
private fun ScoreDetailScreen(
    modifier: Modifier,
    state: ScoresUiState,
    record: ScoreRecord,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (record.warnings.isNotEmpty()) {
            item {
                WarningsPanel(warnings = record.warnings)
            }
        }

        state.error?.let { error ->
            item {
                WarningsPanel(
                    warnings = listOf(
                        ScoreWarning(
                            pageIndex = null,
                            code = "render_failed",
                            message = error,
                        ),
                    ),
                )
            }
        }

        if (record.state != ScoreImportState.Complete) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = record.state.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = record.sourceMime,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else if (state.isRendering) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else {
            items(
                items = state.renderedPages,
                key = { it.index },
            ) { page ->
                Image(
                    bitmap = page.bitmap.asImageBitmap(),
                    contentDescription = "Page ${page.index + 1}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

@Composable
private fun WarningsPanel(warnings: List<ScoreWarning>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Warning, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Warnings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            warnings.forEach { warning ->
                Text(
                    text = warning.message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(enabled, activity) {
        if (enabled) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private fun Long.asDateLabel(): String {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(this))
}

private fun Intent.scoreUri(): Uri? {
    return when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> streamUri()
        else -> null
    }
}

@Suppress("DEPRECATION")
private fun Intent.streamUri(): Uri? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM)
    }
}
