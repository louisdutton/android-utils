package digital.dutton.essentials.notes

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
        setContent { NotesApp() }
    }
}

data class NotesUiState(
    val notes: List<Note> = emptyList(),
    val selectedNoteId: String? = null,
    val searchQuery: String = "",
    val error: String? = null,
)

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val store = NotesStore(application)
    private val _uiState = MutableStateFlow(NotesUiState())
    val uiState: StateFlow<NotesUiState> = _uiState.asStateFlow()
    private var saveJob: Job? = null

    init {
        refreshNotes(selectedNoteId = null)
    }

    fun createNote() {
        viewModelScope.launch(Dispatchers.IO) {
            val note = store.createNote()
            refreshNotes(note.id)
        }
    }

    fun selectNote(noteId: String) {
        _uiState.update { it.copy(selectedNoteId = noteId, error = null) }
    }

    fun closeNote() {
        _uiState.update { it.copy(selectedNoteId = null, error = null) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun updateTitle(title: String) {
        updateSelectedNote { it.copy(title = title) }
    }

    fun updateBody(body: String) {
        updateSelectedNote { it.copy(body = body) }
    }

    fun deleteSelectedNote() {
        val selected = _uiState.value.selectedNote ?: return
        viewModelScope.launch(Dispatchers.IO) {
            store.delete(selected)
            refreshNotes(selectedNoteId = null)
        }
    }

    private fun updateSelectedNote(transform: (Note) -> Note) {
        val selected = _uiState.value.selectedNote ?: return
        val updated = transform(selected)
        _uiState.update { state ->
            state.copy(notes = state.notes.map { note -> if (note.id == updated.id) updated else note })
        }
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(SaveDebounceMillis)
            val saved = store.save(updated)
            _uiState.update { state ->
                state.copy(notes = state.notes.map { note -> if (note.id == saved.id) saved else note })
            }
        }
    }

    private fun refreshNotes(selectedNoteId: String? = _uiState.value.selectedNoteId) {
        val notes = store.listNotes()
        val selected = selectedNoteId?.takeIf { id -> notes.any { it.id == id } }
        _uiState.update {
            it.copy(
                notes = notes,
                selectedNoteId = selected,
                error = null,
            )
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val SaveDebounceMillis = 250L
    }
}

@Composable
private fun NotesApp(viewModel: NotesViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            NotesScreen(
                state = state,
                onNewNote = viewModel::createNote,
                onSelectNote = viewModel::selectNote,
                onCloseNote = viewModel::closeNote,
                onSearchChange = viewModel::updateSearchQuery,
                onTitleChange = viewModel::updateTitle,
                onBodyChange = viewModel::updateBody,
                onDeleteSelected = viewModel::deleteSelectedNote,
            )
        }
    }
}

@Composable
private fun NotesScreen(
    state: NotesUiState,
    onNewNote: () -> Unit,
    onSelectNote: (String) -> Unit,
    onCloseNote: () -> Unit,
    onSearchChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val selectedNote = state.selectedNote

    if (selectedNote == null) {
        NotesHomeScreen(
            state = state,
            notes = state.visibleNotes,
            onNewNote = onNewNote,
            onSelectNote = onSelectNote,
            onSearchChange = onSearchChange,
        )
    } else {
        BackHandler(onBack = onCloseNote)
        NoteDetailScreen(
            note = selectedNote,
            error = state.error,
            onClose = onCloseNote,
            onTitleChange = onTitleChange,
            onBodyChange = onBodyChange,
            onDelete = onDeleteSelected,
        )
    }
}

@Composable
private fun NotesHomeScreen(
    state: NotesUiState,
    notes: List<Note>,
    onNewNote: () -> Unit,
    onSelectNote: (String) -> Unit,
    onSearchChange: (String) -> Unit,
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewNote,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Create note",
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SearchField(
                query = state.searchQuery,
                onQueryChange = onSearchChange,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )

            state.error?.let { message ->
                StatusPanel(
                    message = message,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }

            if (notes.isEmpty()) {
                EmptyNotes(
                    hasQuery = state.searchQuery.isNotBlank(),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        items = notes,
                        key = { it.id },
                    ) { note ->
                        NoteCard(
                            note = note,
                            onClick = { onSelectNote(note.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Medium,
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isBlank()) {
                            Text(
                                text = "Search",
                                style = textStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

@Composable
private fun NoteCard(
    note: Note,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = note.displayTitle(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = note.inlineMetaLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = note.previewLine(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NoteDetailScreen(
    note: Note,
    error: String?,
    onClose: () -> Unit,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            NoteDetailTopBar(
                onClose = onClose,
                onDelete = { pendingDelete = true },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            PlainTextField(
                value = note.title,
                onValueChange = onTitleChange,
                placeholder = "Untitled",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            )

            Text(
                text = note.fullUpdatedLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PlainTextField(
                value = note.body,
                onValueChange = onBodyChange,
                placeholder = "Start writing",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 520.dp),
                textStyle = TextStyle(
                    fontSize = 19.sp,
                    lineHeight = 30.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            )

            error?.let { StatusPanel(message = it) }
        }
    }

    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("Delete note?") },
            text = { Text("This note will be removed from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun NoteDetailTopBar(
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Back",
            onClick = onClose,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )

        CircleIconButton(
            icon = Icons.Rounded.Delete,
            contentDescription = "Delete note",
            onClick = onDelete,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun PlainTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Box {
                if (value.isBlank()) {
                    Text(
                        text = placeholder,
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun EmptyNotes(
    hasQuery: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (hasQuery) "No matches" else "Nothing here yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StatusPanel(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            text = message,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp,
) {
    Surface(
        modifier = modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

private val NotesUiState.selectedNote: Note?
    get() = notes.firstOrNull { it.id == selectedNoteId }

private val NotesUiState.visibleNotes: List<Note>
    get() {
        val query = searchQuery.trim()
        if (query.isBlank()) return notes
        return notes.filter { note ->
            note.title.contains(query, ignoreCase = true) ||
                note.body.contains(query, ignoreCase = true)
        }
    }

private fun Note.displayTitle(): String {
    return title.trim().takeIf { it.isNotBlank() } ?: "Untitled"
}

private fun Note.previewLine(): String {
    val bodyPreview = body.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
    return bodyPreview ?: "Empty note"
}

private fun Note.inlineMetaLabel(): String {
    return "${body.wordCountLabel()} · ${updatedLabel()}"
}

private fun Note.updatedLabel(): String {
    return Instant.ofEpochMilli(updatedMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM"))
}

private fun Note.fullUpdatedLabel(): String {
    return Instant.ofEpochMilli(updatedMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
}

private val WordRegex = Regex("""\S+""")

private fun String.wordCountLabel(): String {
    val count = WordRegex.findAll(this).count()
    return if (count == 1) "1 word" else "$count words"
}
