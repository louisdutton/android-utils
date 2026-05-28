package digital.dutton.essentials.recorder

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
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
        setContent { RecorderApp() }
    }
}

data class RecorderUiState(
    val recordings: List<Recording> = emptyList(),
    val isRecording: Boolean = false,
    val recordingMillis: Long = 0L,
    val liveLevels: List<Float> = emptyList(),
    val playingId: String? = null,
    val pausedId: String? = null,
    val playbackPositionMillis: Long = 0L,
    val playbackDurationMillis: Long = 0L,
    val error: String? = null,
)

class RecorderViewModel(application: Application) : AndroidViewModel(application) {
    private val store = RecorderStore(application)
    private val _uiState = MutableStateFlow(RecorderUiState(recordings = store.listRecordings()))
    val uiState: StateFlow<RecorderUiState> = _uiState.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var recorderOutputFile: java.io.File? = null
    private var recordingStartedMillis: Long = 0L
    private var recordTickerJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playbackTickerJob: Job? = null

    fun startRecording() {
        if (_uiState.value.isRecording) return
        stopPlayback()

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val outputFile = store.createOutputFile()
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
                recorderOutputFile = outputFile
                recordingStartedMillis = System.currentTimeMillis()
                _uiState.update {
                    it.copy(
                        isRecording = true,
                        recordingMillis = 0L,
                        liveLevels = emptyList(),
                        error = null,
                    )
                }
                startRecordTicker()
            }.onFailure { error ->
                recorder?.release()
                recorder = null
                recorderOutputFile?.delete()
                recorderOutputFile = null
                _uiState.update { it.copy(error = error.message ?: "Unable to start recording.") }
            }
        }
    }

    fun stopRecording() {
        val activeRecorder = recorder ?: return
        recordTickerJob?.cancel()
        recordTickerJob = null
        runCatching { activeRecorder.stop() }
        activeRecorder.release()
        recorder = null
        recorderOutputFile = null
        _uiState.update {
            it.copy(
                recordings = store.listRecordings(),
                isRecording = false,
                recordingMillis = 0L,
                liveLevels = emptyList(),
                error = null,
            )
        }
    }

    fun playRecording(recording: Recording) {
        if (_uiState.value.playingId == recording.id) {
            val activePlayer = mediaPlayer ?: return
            if (activePlayer.isPlaying) {
                activePlayer.pause()
                stopPlaybackTicker()
                _uiState.update {
                    it.copy(
                        pausedId = recording.id,
                        playbackPositionMillis = activePlayer.currentPosition.toLong(),
                    )
                }
            } else {
                activePlayer.start()
                startPlaybackTicker(recording.id)
                _uiState.update { it.copy(pausedId = null) }
            }
            return
        }

        stopPlayback()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(recording.file.absolutePath)
            setOnCompletionListener { finishPlayback(recording) }
            prepare()
            start()
        }
        startPlaybackTicker(recording.id)
        _uiState.update {
            it.copy(
                playingId = recording.id,
                pausedId = null,
                playbackPositionMillis = 0L,
                playbackDurationMillis = mediaPlayer?.duration?.toLong()?.coerceAtLeast(0L)
                    ?: recording.durationMillis,
                error = null,
            )
        }
    }

    fun deleteRecording(recording: Recording) {
        if (_uiState.value.playingId == recording.id) stopPlayback()
        viewModelScope.launch(Dispatchers.IO) {
            store.delete(recording)
            _uiState.update { it.copy(recordings = store.listRecordings()) }
        }
    }

    fun refreshRecordings() {
        _uiState.update { it.copy(recordings = store.listRecordings()) }
    }

    private fun startRecordTicker() {
        recordTickerJob?.cancel()
        recordTickerJob = viewModelScope.launch {
            while (_uiState.value.isRecording && recorder != null) {
                val elapsed = System.currentTimeMillis() - recordingStartedMillis
                val level = runCatching {
                    (recorder?.maxAmplitude ?: 0) / MaxRecorderAmplitude
                }.getOrDefault(0f).coerceIn(0f, 1f)
                _uiState.update { state ->
                    state.copy(
                        recordingMillis = elapsed,
                        liveLevels = (state.liveLevels + level).takeLast(WaveformLevelCount),
                    )
                }
                delay(WaveformSampleMillis)
            }
        }
    }

    private fun startPlaybackTicker(recordingId: String) {
        playbackTickerJob?.cancel()
        playbackTickerJob = viewModelScope.launch {
            while (_uiState.value.playingId == recordingId && mediaPlayer != null) {
                val player = mediaPlayer ?: break
                _uiState.update {
                    it.copy(
                        playbackPositionMillis = player.currentPosition.toLong(),
                        playbackDurationMillis = player.duration.toLong().coerceAtLeast(0L),
                    )
                }
                delay(PlaybackSampleMillis)
            }
        }
    }

    private fun stopPlaybackTicker() {
        playbackTickerJob?.cancel()
        playbackTickerJob = null
    }

    private fun finishPlayback(recording: Recording) {
        stopPlaybackTicker()
        mediaPlayer?.release()
        mediaPlayer = null
        _uiState.update {
            it.copy(
                playingId = null,
                pausedId = null,
                playbackPositionMillis = recording.durationMillis,
                playbackDurationMillis = recording.durationMillis,
            )
        }
    }

    private fun stopPlayback() {
        stopPlaybackTicker()
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            player.release()
        }
        mediaPlayer = null
        _uiState.update { it.copy(playingId = null, pausedId = null) }
    }

    override fun onCleared() {
        recorder?.release()
        recorder = null
        recordTickerJob?.cancel()
        stopPlayback()
        super.onCleared()
    }

    private companion object {
        const val AudioSampleRate = 44_100
        const val AudioBitRate = 128_000
        const val WaveformLevelCount = 56
        const val WaveformSampleMillis = 70L
        const val PlaybackSampleMillis = 200L
        const val MaxRecorderAmplitude = 32767f
    }
}

@Composable
private fun RecorderApp(viewModel: RecorderViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startRecording()
    }

    MaterialTheme(colorScheme = colorScheme) {
        RecorderScreen(
            state = state,
            onRecord = {
                if (context.hasRecordAudioPermission()) {
                    viewModel.startRecording()
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onStopRecording = viewModel::stopRecording,
            onPlay = viewModel::playRecording,
            onDelete = viewModel::deleteRecording,
        )
    }
}

@Composable
private fun RecorderScreen(
    state: RecorderUiState,
    onRecord: () -> Unit,
    onStopRecording: () -> Unit,
    onPlay: (Recording) -> Unit,
    onDelete: (Recording) -> Unit,
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
            contentPadding = PaddingValues(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                RecordingStage(
                    isRecording = state.isRecording,
                    recordingMillis = state.recordingMillis,
                    levels = state.liveLevels,
                    error = state.error,
                    onRecord = onRecord,
                    onStopRecording = onStopRecording,
                )
            }

            if (state.recordings.isEmpty()) {
                item {
                    EmptyRecordings()
                }
            } else {
                items(
                    items = state.recordings,
                    key = { it.id },
                ) { recording ->
                    RecordingRow(
                        recording = recording,
                        isPlaying = state.playingId == recording.id && state.pausedId != recording.id,
                        isPaused = state.pausedId == recording.id,
                        playbackPositionMillis = if (state.playingId == recording.id || state.pausedId == recording.id) {
                            state.playbackPositionMillis
                        } else {
                            0L
                        },
                        onPlay = { onPlay(recording) },
                        onDelete = { onDelete(recording) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingStage(
    isRecording: Boolean,
    recordingMillis: Long,
    levels: List<Float>,
    error: String?,
    onRecord: () -> Unit,
    onStopRecording: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AudioWaveform(
                levels = if (levels.isEmpty()) List(56) { 0.08f } else levels,
                active = isRecording,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp),
            )

            Surface(
                modifier = Modifier
                    .size(112.dp)
                    .clickable(onClick = if (isRecording) onStopRecording else onRecord),
                shape = CircleShape,
                color = if (isRecording) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                contentColor = if (isRecording) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        contentDescription = if (isRecording) "Stop recording" else "Record",
                        modifier = Modifier.size(48.dp),
                    )
                }
            }

            Text(
                text = if (isRecording) recordingMillis.durationLabel() else "Ready",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            error?.let {
                StatusPanel(message = it)
            }
        }
    }
}

@Composable
private fun RecordingRow(
    recording: Recording,
    isPlaying: Boolean,
    isPaused: Boolean,
    playbackPositionMillis: Long,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf(false) }
    val progress = if ((isPlaying || isPaused) && recording.durationMillis > 0L) {
        playbackPositionMillis.toFloat() / recording.durationMillis.toFloat()
    } else {
        null
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = recording.displayName(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${recording.durationMillis.durationLabel()} · ${recording.createdLabel()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AudioWaveform(
                    levels = recording.waveformLevels(),
                    active = isPlaying,
                    progressFraction = progress,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                )

                CircleIconButton(
                    icon = when {
                        isPlaying -> Icons.Rounded.Pause
                        else -> Icons.Rounded.PlayArrow
                    },
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    onClick = onPlay,
                    modifier = Modifier.size(42.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    iconSize = 20.dp,
                )

                CircleIconButton(
                    icon = Icons.Rounded.Delete,
                    contentDescription = "Delete recording",
                    onClick = { pendingDelete = true },
                    modifier = Modifier.size(42.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.error,
                    iconSize = 20.dp,
                )
            }
        }
    }

    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("Delete recording?") },
            text = { Text("This recording will be removed from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
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
private fun AudioWaveform(
    levels: List<Float>,
    active: Boolean,
    modifier: Modifier = Modifier,
    progressFraction: Float? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier = modifier) {
        val count = levels.size.coerceAtLeast(1)
        val spacing = 4.dp.toPx()
        val barWidth = ((size.width - spacing * (count - 1)) / count).coerceAtLeast(2.dp.toPx())

        drawRoundRect(
            color = outline.copy(alpha = 0.20f),
            topLeft = Offset(0f, size.height / 2f - 1.dp.toPx()),
            size = Size(size.width, 2.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
        )

        levels.forEachIndexed { index, rawLevel ->
            val level = rawLevel.coerceIn(0.04f, 1f)
            val x = index * (barWidth + spacing)
            val height = size.height * (0.16f + level * 0.78f)
            val y = (size.height - height) / 2f
            val baseColor = if (index % 3 == 0) secondary else primary
            val alpha = if (active) {
                0.34f + level * 0.58f
            } else {
                0.16f + level * 0.28f
            }
            val centerFraction = (index + 0.5f) / count
            val beforeProgress = progressFraction != null && centerFraction <= progressFraction
            drawRoundRect(
                color = if (beforeProgress) {
                    baseColor.copy(alpha = 0.72f + level * 0.24f)
                } else {
                    baseColor.copy(alpha = alpha)
                },
                topLeft = Offset(x, y),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable
private fun EmptyRecordings() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 42.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No recordings yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StatusPanel(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
        modifier = modifier.clickable(onClick = onClick),
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

private fun Context.hasRecordAudioPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun Recording.displayName(): String {
    return file.nameWithoutExtension
        .removePrefix("recording-")
        .ifBlank { "Recording" }
}

private fun Recording.createdLabel(): String {
    return Instant.ofEpochMilli(createdMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM"))
}

private fun Recording.waveformLevels(): List<Float> {
    val seed = id.hashCode() xor sizeBytes.toInt() xor durationMillis.toInt()
    return List(48) { index ->
        val raw = ((seed xor (index * 1_103_515_245)) ushr (index % 8)) and 0xFF
        0.10f + (raw / 255f) * 0.78f
    }
}

private fun Long.durationLabel(): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(this)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(this) % 60
    return "%d:%02d".format(minutes, seconds)
}
