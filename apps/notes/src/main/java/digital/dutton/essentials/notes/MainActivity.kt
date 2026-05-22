package digital.dutton.essentials.notes

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
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
    val isRecording: Boolean = false,
    val recordingNoteId: String? = null,
    val playingNoteId: String? = null,
    val error: String? = null,
)

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val store = NotesStore(application.applicationContext)
    private val _uiState = MutableStateFlow(NotesUiState())
    val uiState: StateFlow<NotesUiState> = _uiState.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var recordingStartedMillis: Long = 0L
    private var mediaPlayer: MediaPlayer? = null
    private var saveJob: Job? = null

    init {
        refreshNotes()
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

    fun updateTitle(title: String) {
        updateSelectedNote { it.copy(title = title) }
    }

    fun updateBody(body: String) {
        updateSelectedNote { it.copy(body = body) }
    }

    fun deleteSelectedNote() {
        val selected = _uiState.value.selectedNote ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.recordingNoteId == selected.id) stopRecording()
            if (_uiState.value.playingNoteId == selected.id) stopPlayback()
            store.delete(selected)
            refreshNotes()
        }
    }

    fun startRecording() {
        if (_uiState.value.isRecording) return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val note = _uiState.value.selectedNote ?: store.createNote(title = "Audio note")
                val outputFile = store.createAudioFile(note)
                val nextRecorder = MediaRecorder(getApplication<Application>().applicationContext).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(AudioSampleRate)
                    setAudioEncodingBitRate(AudioBitRate)
                    setOutputFile(outputFile.absolutePath)
                    prepare()
                    start()
                }

                recorder = nextRecorder
                recordingStartedMillis = System.currentTimeMillis()
                _uiState.update {
                    it.copy(
                        notes = store.listNotes(),
                        selectedNoteId = note.id,
                        isRecording = true,
                        recordingNoteId = note.id,
                        error = null,
                    )
                }
            }.onFailure { error ->
                recorder?.release()
                recorder = null
                _uiState.update {
                    it.copy(error = error.message ?: "Unable to start recording.")
                }
            }
        }
    }

    fun stopRecording() {
        val activeRecorder = recorder ?: return
        val noteId = _uiState.value.recordingNoteId

        runCatching { activeRecorder.stop() }
        activeRecorder.release()
        recorder = null

        viewModelScope.launch(Dispatchers.IO) {
            val durationMillis = System.currentTimeMillis() - recordingStartedMillis
            val note = store.listNotes().firstOrNull { it.id == noteId }
            if (note != null) {
                store.save(
                    note.copy(
                        audioFileName = AudioFileName,
                        audioDurationMillis = durationMillis,
                    ),
                )
            }
            _uiState.update {
                it.copy(
                    notes = store.listNotes(),
                    isRecording = false,
                    recordingNoteId = null,
                    error = null,
                )
            }
        }
    }

    fun playAudio(note: Note) {
        if (_uiState.value.playingNoteId == note.id) {
            stopPlayback()
            return
        }

        val audioFile = store.audioFile(note)
        if (audioFile == null) {
            _uiState.update { it.copy(error = "Audio file was not found.") }
            return
        }

        stopPlayback()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(audioFile.absolutePath)
            setOnCompletionListener { stopPlayback() }
            prepare()
            start()
        }
        _uiState.update { it.copy(playingNoteId = note.id, error = null) }
    }

    fun stopPlayback() {
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            player.release()
        }
        mediaPlayer = null
        _uiState.update { it.copy(playingNoteId = null) }
    }

    private fun updateSelectedNote(transform: (Note) -> Note) {
        val selected = _uiState.value.selectedNote ?: return
        val updated = transform(selected)
        _uiState.update { state ->
            state.copy(
                notes = state.notes.map { note -> if (note.id == updated.id) updated else note },
                error = null,
            )
        }

        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(SaveDebounceMillis)
            val saved = store.save(updated)
            refreshNotes(saved.id)
        }
    }

    private fun refreshNotes(selectedNoteId: String? = _uiState.value.selectedNoteId) {
        val notes = store.listNotes()
        val selected = selectedNoteId?.takeIf { id -> notes.any { it.id == id } }
            ?: notes.firstOrNull()?.id
        _uiState.update {
            it.copy(
                notes = notes,
                selectedNoteId = selected,
                error = null,
            )
        }
    }

    override fun onCleared() {
        recorder?.release()
        recorder = null
        stopPlayback()
        super.onCleared()
    }

    private companion object {
        const val AudioFileName = "audio.m4a"
        const val AudioSampleRate = 44_100
        const val AudioBitRate = 128_000
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
    val recorderPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startRecording()
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            NotesScreen(
                state = state,
                onNewNote = viewModel::createNote,
                onSelectNote = viewModel::selectNote,
                onTitleChange = viewModel::updateTitle,
                onBodyChange = viewModel::updateBody,
                onDeleteSelected = viewModel::deleteSelectedNote,
                onRecord = {
                    if (context.hasRecordAudioPermission()) {
                        viewModel.startRecording()
                    } else {
                        recorderPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStopRecording = viewModel::stopRecording,
                onPlayAudio = viewModel::playAudio,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesScreen(
    state: NotesUiState,
    onNewNote: () -> Unit,
    onSelectNote: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onDeleteSelected: () -> Unit,
    onRecord: () -> Unit,
    onStopRecording: () -> Unit,
    onPlayAudio: (Note) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf(false) }
    val selectedNote = state.selectedNote

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Notes",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(onClick = onNewNote) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "New note",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewNote) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "New note",
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.error?.let { message ->
                item {
                    StatusPanel(message = message)
                }
            }

            item {
                if (selectedNote == null) {
                    EmptyNotes(
                        onNewNote = onNewNote,
                        onRecord = onRecord,
                    )
                } else {
                    NoteEditor(
                        note = selectedNote,
                        isRecording = state.isRecording && state.recordingNoteId == selectedNote.id,
                        isRecordingLocked = state.isRecording && state.recordingNoteId != selectedNote.id,
                        isPlaying = state.playingNoteId == selectedNote.id,
                        onTitleChange = onTitleChange,
                        onBodyChange = onBodyChange,
                        onRecord = onRecord,
                        onStopRecording = onStopRecording,
                        onPlayAudio = { onPlayAudio(selectedNote) },
                        onDelete = { pendingDelete = true },
                    )
                }
            }

            if (state.notes.isNotEmpty()) {
                item {
                    Text(
                        text = "All notes",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(
                    items = state.notes,
                    key = { it.id },
                ) { note ->
                    NoteListRow(
                        note = note,
                        selected = note.id == state.selectedNoteId,
                        isPlaying = note.id == state.playingNoteId,
                        onClick = { onSelectNote(note.id) },
                    )
                }
            }
        }
    }

    if (pendingDelete && selectedNote != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("Delete note?") },
            text = { Text(selectedNote.title) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = false
                        onDeleteSelected()
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
private fun NoteEditor(
    note: Note,
    isRecording: Boolean,
    isRecordingLocked: Boolean,
    isPlaying: Boolean,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onRecord: () -> Unit,
    onStopRecording: () -> Unit,
    onPlayAudio: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Edit note",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    enabled = !isRecording,
                    onClick = onDelete,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Delete note",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = note.title,
                onValueChange = onTitleChange,
                label = { Text("Title") },
                singleLine = true,
            )

            AudioControls(
                note = note,
                isRecording = isRecording,
                isRecordingLocked = isRecordingLocked,
                isPlaying = isPlaying,
                onRecord = onRecord,
                onStopRecording = onStopRecording,
                onPlayAudio = onPlayAudio,
            )

            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 260.dp),
                value = note.body,
                onValueChange = onBodyChange,
                label = { Text("Markdown") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }
}

@Composable
private fun AudioControls(
    note: Note,
    isRecording: Boolean,
    isRecordingLocked: Boolean,
    isPlaying: Boolean,
    onRecord: () -> Unit,
    onStopRecording: () -> Unit,
    onPlayAudio: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                enabled = !isRecordingLocked,
                onClick = if (isRecording) onStopRecording else onRecord,
                colors = if (isRecording) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(if (isRecording) "Stop" else "Record")
            }

            if (note.audioFileName != null) {
                Button(
                    onClick = onPlayAudio,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(if (isPlaying) "Stop" else "Play")
                }
            }
        }

        note.audioDurationMillis?.let { duration ->
            Text(
                text = "Audio ${duration.durationLabel()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoteListRow(
    note: Note,
    selected: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = CircleShape,
                    color = if (note.audioFileName != null || isPlaying) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    content = {},
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = note.previewLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = note.updatedLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyNotes(
    onNewNote: () -> Unit,
    onRecord: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "No notes yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNewNote) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("New note")
                }
                Button(
                    onClick = onRecord,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Record")
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            text = message,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private val NotesUiState.selectedNote: Note?
    get() = notes.firstOrNull { it.id == selectedNoteId }

private fun Context.hasRecordAudioPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun Note.previewLine(): String {
    val bodyPreview = body.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
    val audioPreview = audioDurationMillis?.let { "Audio ${it.durationLabel()}" }
    return bodyPreview ?: audioPreview ?: "Empty note"
}

private fun Note.updatedLabel(): String {
    return Instant.ofEpochMilli(updatedMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM"))
}

private fun Long.durationLabel(): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(this)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(this) % 60
    return "%d:%02d".format(minutes, seconds)
}
