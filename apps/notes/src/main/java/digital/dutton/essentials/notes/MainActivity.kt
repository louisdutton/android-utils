package digital.dutton.essentials.notes

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val searchQuery: String = "",
    val isRecording: Boolean = false,
    val recordingNoteId: String? = null,
    val playingNoteId: String? = null,
    val pausedNoteId: String? = null,
    val playbackPositionMillis: Long = 0L,
    val playbackDurationMillis: Long = 0L,
    val audioLevels: List<Float> = emptyList(),
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
    private var amplitudeJob: Job? = null
    private var playbackProgressJob: Job? = null

    init {
        refreshNotes(selectedNoteId = null)
    }

    fun createNote() {
        viewModelScope.launch(Dispatchers.IO) {
            val note = store.createTextNote()
            refreshNotes(note.id)
        }
    }

    fun selectNote(noteId: String) {
        val note = _uiState.value.notes.firstOrNull { it.id == noteId }
        _uiState.update {
            it.copy(
                selectedNoteId = noteId,
                playbackPositionMillis = 0L,
                playbackDurationMillis = note?.audioDurationMillis ?: 0L,
                error = null,
            )
        }
    }

    fun closeNote() {
        if (_uiState.value.isRecording) {
            stopRecording()
        }
        _uiState.update { it.copy(selectedNoteId = null, audioLevels = emptyList(), error = null) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun updateTitle(title: String) {
        updateSelectedNote { it.copy(title = title) }
    }

    fun updateBody(body: String) {
        if (_uiState.value.selectedNote?.kind != NoteKind.Text) return
        updateSelectedNote { it.copy(body = body) }
    }

    fun deleteSelectedNote() {
        val selected = _uiState.value.selectedNote ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.recordingNoteId == selected.id) stopRecording()
            if (_uiState.value.playingNoteId == selected.id) stopPlayback()
            store.delete(selected)
            refreshNotes(selectedNoteId = null)
        }
    }

    fun startRecording() {
        if (_uiState.value.isRecording) return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val note = _uiState.value.selectedNote
                    ?.takeIf { it.kind == NoteKind.Audio }
                    ?: store.createAudioNote()
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
                        audioLevels = emptyList(),
                        error = null,
                    )
                }
                startAmplitudeSampler(note.id)
            }.onFailure { error ->
                recorder?.release()
                recorder = null
                amplitudeJob?.cancel()
                amplitudeJob = null
                _uiState.update {
                    it.copy(error = error.message ?: "Unable to start recording.")
                }
            }
        }
    }

    fun stopRecording() {
        val activeRecorder = recorder ?: return
        val noteId = _uiState.value.recordingNoteId

        amplitudeJob?.cancel()
        amplitudeJob = null
        runCatching { activeRecorder.stop() }
        activeRecorder.release()
        recorder = null

        viewModelScope.launch(Dispatchers.IO) {
            val durationMillis = System.currentTimeMillis() - recordingStartedMillis
            val note = store.listNotes().firstOrNull { it.id == noteId }
            if (note != null) {
                store.save(
                    note.copy(
                        kind = NoteKind.Audio,
                        body = "",
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
                    playbackPositionMillis = 0L,
                    playbackDurationMillis = durationMillis,
                    error = null,
                )
            }
        }
    }

    fun playAudio(note: Note) {
        if (note.kind != NoteKind.Audio) return

        if (_uiState.value.playingNoteId == note.id) {
            val activePlayer = mediaPlayer ?: return
            if (activePlayer.isPlaying) {
                activePlayer.pause()
                stopPlaybackProgress()
                _uiState.update {
                    it.copy(
                        pausedNoteId = note.id,
                        playbackPositionMillis = activePlayer.currentPosition.toLong(),
                        playbackDurationMillis = activePlayer.duration.toLong().coerceAtLeast(0L),
                    )
                }
            } else {
                activePlayer.start()
                startPlaybackProgress(note.id)
                _uiState.update { it.copy(pausedNoteId = null) }
            }
            return
        }

        val audioFile = store.audioFile(note)
        if (audioFile == null) {
            _uiState.update { it.copy(error = "Audio file was not found.") }
            return
        }

        stopPlayback()
        val requestedPosition = if (_uiState.value.selectedNoteId == note.id) {
            _uiState.value.playbackPositionMillis
        } else {
            0L
        }
        mediaPlayer = MediaPlayer().apply {
            setDataSource(audioFile.absolutePath)
            setOnCompletionListener { finishPlayback(note.id) }
            prepare()
            val startPosition = requestedPosition.coerceIn(0L, duration.toLong().coerceAtLeast(0L))
            if (startPosition > 0L) {
                seekTo(startPosition.toInt())
            }
            start()
        }
        startPlaybackProgress(note.id)
        _uiState.update {
            it.copy(
                playingNoteId = note.id,
                pausedNoteId = null,
                playbackDurationMillis = mediaPlayer?.duration?.toLong()?.coerceAtLeast(0L)
                    ?: note.audioDurationMillis
                    ?: 0L,
                playbackPositionMillis = mediaPlayer?.currentPosition?.toLong() ?: requestedPosition,
                error = null,
            )
        }
    }

    fun seekAudio(note: Note, positionMillis: Long) {
        if (note.kind != NoteKind.Audio) return

        val durationMillis = _uiState.value.playbackDurationMillis
            .takeIf { it > 0L }
            ?: note.audioDurationMillis
            ?: mediaPlayer?.duration?.toLong()?.coerceAtLeast(0L)
            ?: 0L
        val position = positionMillis.coerceIn(0L, durationMillis)
        if (_uiState.value.playingNoteId == note.id) {
            mediaPlayer?.seekTo(position.toInt())
        }
        _uiState.update {
            it.copy(
                selectedNoteId = note.id,
                playbackPositionMillis = position,
                playbackDurationMillis = durationMillis,
                error = null,
            )
        }
    }

    fun stopPlayback() {
        stopPlaybackProgress()
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            player.release()
        }
        mediaPlayer = null
        _uiState.update { it.copy(playingNoteId = null, pausedNoteId = null) }
    }

    private fun startAmplitudeSampler(noteId: String) {
        amplitudeJob?.cancel()
        amplitudeJob = viewModelScope.launch {
            while (_uiState.value.recordingNoteId == noteId && recorder != null) {
                val level = runCatching {
                    (recorder?.maxAmplitude ?: 0) / MaxRecorderAmplitude
                }.getOrDefault(0f).coerceIn(0f, 1f)
                _uiState.update { state ->
                    state.copy(audioLevels = (state.audioLevels + level).takeLast(AudioLevelCount))
                }
                delay(AudioLevelSampleMillis)
            }
        }
    }

    private fun startPlaybackProgress(noteId: String) {
        playbackProgressJob?.cancel()
        playbackProgressJob = viewModelScope.launch {
            while (_uiState.value.playingNoteId == noteId && mediaPlayer != null) {
                val player = mediaPlayer ?: break
                _uiState.update {
                    it.copy(
                        playbackPositionMillis = player.currentPosition.toLong(),
                        playbackDurationMillis = player.duration.toLong().coerceAtLeast(0L),
                    )
                }
                delay(PlaybackProgressSampleMillis)
            }
        }
    }

    private fun stopPlaybackProgress() {
        playbackProgressJob?.cancel()
        playbackProgressJob = null
    }

    private fun finishPlayback(noteId: String) {
        stopPlaybackProgress()
        val durationMillis = _uiState.value.playbackDurationMillis
            .takeIf { it > 0L }
            ?: _uiState.value.notes.firstOrNull { it.id == noteId }?.audioDurationMillis
            ?: 0L
        mediaPlayer?.release()
        mediaPlayer = null
        _uiState.update {
            it.copy(
                playingNoteId = null,
                pausedNoteId = null,
                playbackPositionMillis = durationMillis,
                playbackDurationMillis = durationMillis,
            )
        }
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
        amplitudeJob?.cancel()
        amplitudeJob = null
        stopPlaybackProgress()
        stopPlayback()
        super.onCleared()
    }

    private companion object {
        const val AudioFileName = "audio.m4a"
        const val AudioSampleRate = 44_100
        const val AudioBitRate = 128_000
        const val SaveDebounceMillis = 250L
        const val AudioLevelCount = 48
        const val AudioLevelSampleMillis = 70L
        const val MaxRecorderAmplitude = 32767f
        const val PlaybackProgressSampleMillis = 200L
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

    val startRecordingWithPermission = {
        if (context.hasRecordAudioPermission()) {
            viewModel.startRecording()
        } else {
            recorderPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
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
                onRecord = startRecordingWithPermission,
                onStopRecording = viewModel::stopRecording,
                onPlayAudio = viewModel::playAudio,
                onSeekAudio = viewModel::seekAudio,
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
    onRecord: () -> Unit,
    onStopRecording: () -> Unit,
    onPlayAudio: (Note) -> Unit,
    onSeekAudio: (Note, Long) -> Unit,
) {
    val selectedNote = state.selectedNote

    if (selectedNote == null) {
        NotesHomeScreen(
            state = state,
            notes = state.visibleNotes,
            onNewNote = onNewNote,
            onSelectNote = onSelectNote,
            onSearchChange = onSearchChange,
            onRecord = onRecord,
        )
    } else {
        BackHandler(onBack = onCloseNote)
        NoteDetailScreen(
            note = selectedNote,
            isRecording = state.isRecording && state.recordingNoteId == selectedNote.id,
            isRecordingLocked = state.isRecording && state.recordingNoteId != selectedNote.id,
            isPlaying = state.playingNoteId == selectedNote.id && state.pausedNoteId != selectedNote.id,
            isPlaybackPaused = state.pausedNoteId == selectedNote.id,
            playbackPositionMillis = state.playbackPositionMillis,
            playbackDurationMillis = state.playbackDurationMillis,
            audioLevels = state.audioLevels,
            error = state.error,
            onClose = onCloseNote,
            onTitleChange = onTitleChange,
            onBodyChange = onBodyChange,
            onRecord = onRecord,
            onStopRecording = onStopRecording,
            onPlayAudio = { onPlayAudio(selectedNote) },
            onSeekAudio = { positionMillis -> onSeekAudio(selectedNote, positionMillis) },
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
    onRecord: () -> Unit,
) {
    var showCreateActions by remember { mutableStateOf(false) }

    BackHandler(enabled = showCreateActions) {
        showCreateActions = false
    }

    Scaffold(
        floatingActionButton = {
            CreateNoteActions(
                expanded = showCreateActions,
                onToggleExpanded = { showCreateActions = !showCreateActions },
                onCreateText = {
                    showCreateActions = false
                    onNewNote()
                },
                onCreateAudio = {
                    showCreateActions = false
                    onRecord()
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding(),
        ) {
            SearchField(
                query = state.searchQuery,
                onQueryChange = onSearchChange,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 12.dp),
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
                            isRecording = state.recordingNoteId == note.id && state.isRecording,
                            onClick = { onSelectNote(note.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateNoteActions(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCreateText: () -> Unit,
    onCreateAudio: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (expanded) {
            CreateChoiceButton(
                label = "Text",
                icon = Icons.Rounded.Edit,
                onClick = onCreateText,
            )
            CreateChoiceButton(
                label = "Audio",
                icon = Icons.Rounded.Mic,
                onClick = onCreateAudio,
            )
        }

        FloatingActionButton(
            onClick = onToggleExpanded,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = "Create",
            )
        }
    }
}

@Composable
private fun CreateChoiceButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
            )
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
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isBlank()) {
                            Text(
                                text = "Search",
                                style = textStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                }
            },
        )
    }
}

@Composable
private fun NoteCard(
    note: Note,
    isRecording: Boolean,
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
                    text = note.updatedLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (note.kind == NoteKind.Audio) {
                AudioWaveform(
                    levels = note.waveformLevels(
                        liveLevels = emptyList(),
                        isRecording = isRecording,
                    ),
                    active = isRecording,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                )
            } else {
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
}

@Composable
private fun NoteDetailScreen(
    note: Note,
    isRecording: Boolean,
    isRecordingLocked: Boolean,
    isPlaying: Boolean,
    isPlaybackPaused: Boolean,
    playbackPositionMillis: Long,
    playbackDurationMillis: Long,
    audioLevels: List<Float>,
    error: String?,
    onClose: () -> Unit,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onRecord: () -> Unit,
    onStopRecording: () -> Unit,
    onPlayAudio: () -> Unit,
    onSeekAudio: (Long) -> Unit,
    onDelete: () -> Unit,
) {
    var pendingDelete by remember(note.id) { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            NoteDetailTopBar(
                isRecording = isRecording,
                onClose = onClose,
                onDelete = { pendingDelete = true },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        if (note.kind == NoteKind.Audio) {
            AudioNoteStage(
                note = note,
                isRecording = isRecording,
                isRecordingLocked = isRecordingLocked,
                isPlaying = isPlaying,
                isPlaybackPaused = isPlaybackPaused,
                playbackPositionMillis = playbackPositionMillis,
                playbackDurationMillis = playbackDurationMillis,
                audioLevels = audioLevels,
                error = error,
                onTitleChange = onTitleChange,
                onRecord = onRecord,
                onStopRecording = onStopRecording,
                onPlayAudio = onPlayAudio,
                onSeekAudio = onSeekAudio,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scrollState)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = note.fullUpdatedLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

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

                error?.let { StatusPanel(message = it) }

                PlainTextField(
                    value = note.body,
                    onValueChange = onBodyChange,
                    placeholder = "Start writing",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 360.dp),
                    textStyle = TextStyle(
                        fontSize = 18.sp,
                        lineHeight = 28.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                    ),
                )
            }
        }
    }

    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("Delete note?") },
            text = { Text(note.displayTitle()) },
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
    isRecording: Boolean,
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircleIconButton(
                icon = Icons.Rounded.Delete,
                contentDescription = "Delete note",
                onClick = onDelete,
                enabled = !isRecording,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AudioNoteStage(
    note: Note,
    isRecording: Boolean,
    isRecordingLocked: Boolean,
    isPlaying: Boolean,
    isPlaybackPaused: Boolean,
    playbackPositionMillis: Long,
    playbackDurationMillis: Long,
    audioLevels: List<Float>,
    error: String?,
    onTitleChange: (String) -> Unit,
    onRecord: () -> Unit,
    onStopRecording: () -> Unit,
    onPlayAudio: () -> Unit,
    onSeekAudio: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationMillis = playbackDurationMillis
        .takeIf { it > 0L }
        ?: note.audioDurationMillis
        ?: 0L
    val positionMillis = playbackPositionMillis.coerceIn(0L, durationMillis)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            PlainTextField(
                value = note.title,
                onValueChange = onTitleChange,
                placeholder = "Audio note",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            )

            AudioTimeline(
                levels = note.waveformLevels(audioLevels, isRecording),
                active = isRecording || isPlaying,
                positionMillis = positionMillis,
                durationMillis = durationMillis,
                seekEnabled = !isRecording && note.audioFileName != null && durationMillis > 0L,
                onSeek = onSeekAudio,
            )

            AudioTransportButton(
                hasAudio = note.audioFileName != null,
                isRecording = isRecording,
                isRecordingLocked = isRecordingLocked,
                isPlaying = isPlaying,
                onRecord = onRecord,
                onStopRecording = onStopRecording,
                onPlayAudio = onPlayAudio,
            )

            Text(
                text = note.audioStatusLabel(
                    isRecording = isRecording,
                    isRecordingLocked = isRecordingLocked,
                    isPlaying = isPlaying,
                    isPlaybackPaused = isPlaybackPaused,
                    playbackPositionMillis = positionMillis,
                    playbackDurationMillis = durationMillis,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            error?.let { StatusPanel(message = it) }
        }
    }
}

@Composable
private fun AudioTransportButton(
    hasAudio: Boolean,
    isRecording: Boolean,
    isRecordingLocked: Boolean,
    isPlaying: Boolean,
    onRecord: () -> Unit,
    onStopRecording: () -> Unit,
    onPlayAudio: () -> Unit,
) {
    val icon = when {
        isRecording -> Icons.Rounded.Stop
        hasAudio && isPlaying -> Icons.Rounded.Pause
        hasAudio -> Icons.Rounded.PlayArrow
        else -> Icons.Rounded.Mic
    }
    val contentDescription = when {
        isRecording -> "Stop recording"
        hasAudio && isPlaying -> "Pause audio"
        hasAudio -> "Play audio"
        else -> "Record audio"
    }
    val onClick = when {
        isRecording -> onStopRecording
        hasAudio -> onPlayAudio
        else -> onRecord
    }
    val containerColor = if (isRecording) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (isRecording) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        modifier = Modifier
            .size(112.dp)
            .clickable(enabled = !isRecordingLocked, onClick = onClick),
        shape = CircleShape,
        color = if (isRecordingLocked) MaterialTheme.colorScheme.surfaceContainerLow else containerColor,
        contentColor = if (isRecordingLocked) {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
        } else {
            contentColor
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

@Composable
private fun AudioTimeline(
    levels: List<Float>,
    active: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    seekEnabled: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val timelineWidth = remember(durationMillis) { durationMillis.timelineWidth() }
    val scrollState = rememberScrollState()
    val timelineLevels = remember(levels, durationMillis) {
        levels.toTimelineLevels(durationMillis)
    }
    val ticks = remember(durationMillis) { durationMillis.timelineTicks() }
    val tickFractions = remember(ticks, durationMillis) {
        ticks.map { tick ->
            if (durationMillis > 0L) {
                tick.toFloat() / durationMillis.toFloat()
            } else {
                0f
            }.coerceIn(0f, 1f)
        }
    }
    val progress = if (durationMillis > 0L) {
        positionMillis.toFloat() / durationMillis.toFloat()
    } else {
        0f
    }.coerceIn(0f, 1f)
    val seekModifier = if (seekEnabled) {
        Modifier
            .pointerInput(durationMillis) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek((durationMillis * fraction).toLong())
                }
            }
    } else {
        Modifier
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
        ) {
            Column(
                modifier = Modifier.width(timelineWidth),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AudioWaveform(
                    levels = timelineLevels,
                    active = active,
                    progressFraction = progress.takeIf { durationMillis > 0L },
                    tickFractions = tickFractions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .then(seekModifier),
                )

                TimelineTickLabels(
                    ticks = ticks,
                    durationMillis = durationMillis,
                    timelineWidth = timelineWidth,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = 0L.durationLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = positionMillis.durationLabel(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = durationMillis.durationLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimelineTickLabels(
    ticks: List<Long>,
    durationMillis: Long,
    timelineWidth: Dp,
) {
    val labelWidth = 54.dp
    val availableWidth = maxOf(timelineWidth - labelWidth, 0.dp)

    Box(
        modifier = Modifier
            .width(timelineWidth)
            .height(24.dp),
    ) {
        ticks.forEach { tick ->
            val fraction = if (durationMillis > 0L) {
                tick.toFloat() / durationMillis.toFloat()
            } else {
                0f
            }.coerceIn(0f, 1f)
            val alignment = when (tick) {
                0L -> TextAlign.Start
                durationMillis -> TextAlign.End
                else -> TextAlign.Center
            }
            Text(
                text = tick.durationLabel(),
                modifier = Modifier
                    .width(labelWidth)
                    .offset(x = availableWidth * fraction)
                    .align(Alignment.TopStart),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = alignment,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AudioWaveform(
    levels: List<Float>,
    active: Boolean,
    progressFraction: Float? = null,
    tickFractions: List<Float> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val count = levels.size.coerceAtLeast(1)
        val spacing = 4.dp.toPx()
        val barWidth = ((size.width - spacing * (count - 1)) / count).coerceAtLeast(2.dp.toPx())
        levels.forEachIndexed { index, rawLevel ->
            val level = rawLevel.coerceIn(0.04f, 1f)
            val x = index * (barWidth + spacing)
            val height = size.height * (0.16f + level * 0.78f)
            val y = (size.height - height) / 2f
            val color = if (index % 3 == 0) secondary else primary
            val alpha = if (active) {
                0.34f + level * 0.58f
            } else {
                0.16f + level * 0.28f
            }
            val centerFraction = (index + 0.5f) / count
            val isBeforeProgress = progressFraction != null && centerFraction <= progressFraction
            drawRoundRect(
                color = if (isBeforeProgress) {
                    color.copy(alpha = 0.72f + level * 0.24f)
                } else {
                    color.copy(alpha = alpha)
                },
                topLeft = Offset(x, y),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }

        drawRoundRect(
            color = outline.copy(alpha = 0.20f),
            topLeft = Offset(0f, size.height / 2f - 1.dp.toPx()),
            size = Size(size.width, 2.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
        )

        tickFractions.forEach { fraction ->
            val tickWidth = 1.dp.toPx()
            val tickHeight = 18.dp.toPx()
            val x = size.width * fraction.coerceIn(0f, 1f)
            drawRoundRect(
                color = outline.copy(alpha = 0.50f),
                topLeft = Offset(x - tickWidth / 2f, size.height - tickHeight),
                size = Size(tickWidth, tickHeight),
                cornerRadius = CornerRadius(tickWidth / 2f, tickWidth / 2f),
            )
        }

        progressFraction?.let { progress ->
            val x = size.width * progress.coerceIn(0f, 1f)
            drawRoundRect(
                color = primary.copy(alpha = 0.86f),
                topLeft = Offset(x - 1.5.dp.toPx(), 0f),
                size = Size(3.dp.toPx(), size.height),
                cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()),
            )
        }
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
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (hasQuery) "No matches" else "Nothing here yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
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
    enabled: Boolean = true,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp,
) {
    Surface(
        modifier = modifier
            .size(48.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = if (enabled) containerColor else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
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
                (note.kind == NoteKind.Text && note.body.contains(query, ignoreCase = true)) ||
                (note.kind == NoteKind.Audio && "audio".contains(query, ignoreCase = true))
        }
    }

private fun Context.hasRecordAudioPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun Note.displayTitle(): String {
    return title.trim().takeIf { it.isNotBlank() } ?: when (kind) {
        NoteKind.Text -> "Untitled"
        NoteKind.Audio -> "Audio note"
    }
}

private fun Note.previewLine(): String {
    if (kind == NoteKind.Audio) {
        return audioDurationMillis?.durationLabel() ?: "No recording yet"
    }

    val bodyPreview = body.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
    return bodyPreview ?: "Empty note"
}

private fun Note.audioStatusLabel(
    isRecording: Boolean,
    isRecordingLocked: Boolean,
    isPlaying: Boolean,
    isPlaybackPaused: Boolean,
    playbackPositionMillis: Long,
    playbackDurationMillis: Long,
): String {
    return when {
        isRecording -> "Recording"
        isRecordingLocked -> "Another audio note is recording"
        isPlaying -> "${playbackPositionMillis.durationLabel()} / ${playbackDurationMillis.durationLabel()}"
        isPlaybackPaused -> "${playbackPositionMillis.durationLabel()} / ${playbackDurationMillis.durationLabel()}"
        playbackDurationMillis > 0L -> playbackDurationMillis.durationLabel()
        audioDurationMillis != null -> audioDurationMillis.durationLabel()
        else -> "Ready to record"
    }
}

private fun Note.waveformLevels(
    liveLevels: List<Float>,
    isRecording: Boolean,
): List<Float> {
    if (isRecording && liveLevels.isNotEmpty()) return liveLevels

    val seed = id.hashCode()
    return List(48) { index ->
        val raw = ((seed xor (index * 1_103_515_245)) ushr (index % 8)) and 0xFF
        0.10f + (raw / 255f) * 0.78f
    }
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

private fun Long.timelineWidth(): Dp {
    val seconds = TimeUnit.MILLISECONDS.toSeconds(this).coerceAtLeast(1L)
    val width = (seconds * 16L).coerceIn(360L, 12_000L).toInt()
    return width.dp
}

private fun Long.timelineTicks(): List<Long> {
    val durationMillis = coerceAtLeast(0L)
    if (durationMillis == 0L) return listOf(0L)

    val stepMillis = when {
        durationMillis <= 30_000L -> 5_000L
        durationMillis <= 2 * 60_000L -> 15_000L
        durationMillis <= 10 * 60_000L -> 60_000L
        durationMillis <= 30 * 60_000L -> 5 * 60_000L
        else -> 10 * 60_000L
    }
    val ticks = mutableListOf<Long>()
    var tick = 0L
    while (tick < durationMillis) {
        ticks += tick
        tick += stepMillis
    }
    if (ticks.lastOrNull() != durationMillis) {
        ticks += durationMillis
    }
    return ticks
}

private fun List<Float>.toTimelineLevels(durationMillis: Long): List<Float> {
    val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMillis).coerceAtLeast(1L)
    val targetCount = (seconds * 2L).coerceIn(48L, 1_500L).toInt()
    if (isEmpty()) return List(targetCount) { 0.08f }
    if (size >= targetCount) return this
    if (size == 1) return List(targetCount) { first().coerceIn(0f, 1f) }

    return List(targetCount) { index ->
        val sourcePosition = index * (lastIndex.toFloat() / (targetCount - 1).coerceAtLeast(1))
        val lowerIndex = sourcePosition.toInt().coerceIn(0, lastIndex)
        val upperIndex = (lowerIndex + 1).coerceAtMost(lastIndex)
        val blend = sourcePosition - lowerIndex
        val interpolated = this[lowerIndex] * (1f - blend) + this[upperIndex] * blend
        val variation = 0.92f + ((index * 37) % 17) / 100f
        (interpolated * variation).coerceIn(0.04f, 1f)
    }
}

private fun Long.durationLabel(): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(this)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(this) % 60
    return "%d:%02d".format(minutes, seconds)
}
