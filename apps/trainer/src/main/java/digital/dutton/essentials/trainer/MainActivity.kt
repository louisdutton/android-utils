package digital.dutton.essentials.trainer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TrainerApp() }
    }
}

data class TrainerUiState(
    val challenge: SightSingingChallenge = SightSingingChallengeFactory.next(),
    val referencePitch: NaturalPitch = ReferencePitch,
    val isListening: Boolean = false,
    val detectedPitchLabel: String? = null,
    val detectedFrequencyHz: Double? = null,
    val centsOff: Double? = null,
    val confidence: Double = 0.0,
    val signalLevel: Float = 0f,
    val result: SingingResult? = null,
    val error: String? = null,
    val stats: SingingStats = SingingStats(),
)

data class SingingResult(
    val correct: Boolean,
    val expected: String,
    val detected: String?,
    val centsOff: Double?,
)

data class SingingStats(
    val attempts: Int = 0,
    val correct: Int = 0,
    val streak: Int = 0,
) {
    val accuracy: Int
        get() = if (attempts == 0) 0 else correct * 100 / attempts

    fun record(correctAnswer: Boolean): SingingStats {
        return copy(
            attempts = attempts + 1,
            correct = correct + if (correctAnswer) 1 else 0,
            streak = if (correctAnswer) streak + 1 else 0,
        )
    }
}

class TrainerViewModel : ViewModel() {
    private val tonePlayer = TrainingTonePlayer()
    private val pitchRecorder = SingingPitchRecorder(
        onObservation = ::handlePitchObservation,
        onError = ::handlePitchError,
    )
    private val _uiState = MutableStateFlow(TrainerUiState())
    val uiState: StateFlow<TrainerUiState> = _uiState.asStateFlow()
    private var consecutiveCorrectFrames = 0

    fun playReference() {
        tonePlayer.playNote(
            midiNumber = _uiState.value.referencePitch.midiNumber,
            durationMillis = ReferenceToneMillis,
        )
    }

    fun startListening() {
        if (_uiState.value.isListening) return
        consecutiveCorrectFrames = 0
        _uiState.update {
            it.copy(
                isListening = true,
                detectedPitchLabel = null,
                detectedFrequencyHz = null,
                centsOff = null,
                confidence = 0.0,
                signalLevel = 0f,
                result = null,
                error = null,
            )
        }
        if (!pitchRecorder.start()) {
            _uiState.update {
                it.copy(
                    isListening = false,
                    error = "Unable to start microphone capture.",
                )
            }
        }
    }

    fun stopListening(recordAttempt: Boolean = true) {
        pitchRecorder.stop()
        consecutiveCorrectFrames = 0
        _uiState.update { state ->
            if (!state.isListening) return@update state
            if (!recordAttempt || state.result != null) {
                state.copy(isListening = false)
            } else {
                state.copy(
                    isListening = false,
                    result = SingingResult(
                        correct = false,
                        expected = state.challenge.pitch.label,
                        detected = state.detectedPitchLabel,
                        centsOff = state.centsOff,
                    ),
                    stats = state.stats.record(correctAnswer = false),
                )
            }
        }
    }

    fun retryChallenge() {
        stopListening(recordAttempt = false)
        consecutiveCorrectFrames = 0
        _uiState.update {
            it.copy(
                detectedPitchLabel = null,
                detectedFrequencyHz = null,
                centsOff = null,
                confidence = 0.0,
                signalLevel = 0f,
                result = null,
                error = null,
            )
        }
    }

    fun nextChallenge() {
        stopListening(recordAttempt = false)
        consecutiveCorrectFrames = 0
        _uiState.update {
            it.copy(
                challenge = SightSingingChallengeFactory.next(),
                detectedPitchLabel = null,
                detectedFrequencyHz = null,
                centsOff = null,
                confidence = 0.0,
                signalLevel = 0f,
                result = null,
                error = null,
            )
        }
    }

    private fun handlePitchObservation(observation: PitchObservation) {
        val frequencyHz = observation.frequencyHz
        if (frequencyHz == null) {
            consecutiveCorrectFrames = 0
            _uiState.update {
                if (!it.isListening) return@update it
                it.copy(
                    detectedPitchLabel = null,
                    detectedFrequencyHz = null,
                    centsOff = null,
                    confidence = observation.confidence,
                    signalLevel = observation.level,
                )
            }
            return
        }

        _uiState.update { state ->
            if (!state.isListening) return@update state
            val target = state.challenge.pitch
            val centsOff = centsBetween(
                frequencyHz = frequencyHz,
                targetFrequencyHz = target.frequencyHz,
            )
            val detectedPitch = nearestPitchLabel(frequencyHz)
            val inTune = abs(centsOff) <= CorrectToleranceCents

            if (state.result == null) {
                consecutiveCorrectFrames = if (inTune) {
                    consecutiveCorrectFrames + 1
                } else {
                    0
                }
            }

            val result = if (
                state.result == null &&
                consecutiveCorrectFrames >= StableCorrectFrames
            ) {
                SingingResult(
                    correct = true,
                    expected = target.label,
                    detected = detectedPitch,
                    centsOff = centsOff,
                )
            } else {
                state.result
            }
            val stats = if (state.result == null && result?.correct == true) {
                state.stats.record(correctAnswer = true)
            } else {
                state.stats
            }

            state.copy(
                detectedPitchLabel = detectedPitch,
                detectedFrequencyHz = frequencyHz,
                centsOff = centsOff,
                confidence = observation.confidence,
                signalLevel = observation.level,
                result = result,
                stats = stats,
            )
        }
    }

    private fun handlePitchError(message: String) {
        _uiState.update {
            it.copy(
                isListening = false,
                error = message,
            )
        }
    }

    override fun onCleared() {
        stopListening(recordAttempt = false)
        tonePlayer.stop()
        super.onCleared()
    }

    private companion object {
        const val ReferenceToneMillis = 1_200L
        const val CorrectToleranceCents = 50.0
        const val StableCorrectFrames = 5
    }
}

@Composable
private fun TrainerApp(viewModel: TrainerViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var microphoneGranted by remember { mutableStateOf(isMicrophoneGranted(context)) }
    var microphoneDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        microphoneGranted = granted
        microphoneDenied = !granted
        if (granted) viewModel.startListening()
    }
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
            TrainerScreen(
                state = state,
                microphoneGranted = microphoneGranted,
                microphoneDenied = microphoneDenied,
                onPlayReference = viewModel::playReference,
                onStartListening = {
                    if (isMicrophoneGranted(context)) {
                        microphoneGranted = true
                        microphoneDenied = false
                        viewModel.startListening()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStopListening = viewModel::stopListening,
                onRetry = viewModel::retryChallenge,
                onNext = viewModel::nextChallenge,
            )
        }
    }
}

@Composable
private fun TrainerScreen(
    state: TrainerUiState,
    microphoneGranted: Boolean,
    microphoneDenied: Boolean,
    onPlayReference: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onRetry: () -> Unit,
    onNext: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppHeader(stats = state.stats)
            StaffPanel(challenge = state.challenge)
            ReferencePanel(
                referencePitch = state.referencePitch,
                onPlayReference = onPlayReference,
            )
            PitchReadout(
                state = state,
                microphoneDenied = microphoneDenied,
            )
            SingingControls(
                state = state,
                microphoneGranted = microphoneGranted,
                onStartListening = onStartListening,
                onStopListening = onStopListening,
                onRetry = onRetry,
                onNext = onNext,
            )
        }
    }
}

@Composable
private fun AppHeader(stats: SingingStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = "Sight Singing",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${stats.correct}/${stats.attempts} correct",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatPill(label = "${stats.accuracy}%", value = "Accuracy")
        StatPill(label = stats.streak.toString(), value = "Streak")
    }
}

@Composable
private fun StatPill(
    label: String,
    value: String,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StaffPanel(challenge: SightSingingChallenge) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
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
                Text(
                    text = challenge.clef.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "C major",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StaffCanvas(challenge = challenge)
        }
    }
}

@Composable
private fun StaffCanvas(challenge: SightSingingChallenge) {
    val lineColor = MaterialTheme.colorScheme.onSurface
    val noteColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.surface

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp),
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize(),
        ) {
            val staffLeft = size.width * 0.10f
            val staffRight = size.width * 0.92f
            val staffWidth = staffRight - staffLeft
            val lineSpacing = size.height / 9f
            val topLineY = size.height / 2f - lineSpacing * 2f
            val bottomLineY = topLineY + lineSpacing * 4f
            val strokeWidth = 2.4.dp.toPx()

            repeat(5) { line ->
                val y = topLineY + lineSpacing * line
                drawLine(
                    color = lineColor,
                    start = Offset(staffLeft, y),
                    end = Offset(staffRight, y),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }

            val clefX = staffLeft + staffWidth * 0.10f
            drawTrebleClefHint(
                x = clefX,
                centerY = topLineY + lineSpacing * 2f,
                lineSpacing = lineSpacing,
                color = lineColor,
                strokeWidth = strokeWidth,
            )

            val noteX = staffLeft + staffWidth * 0.60f
            val noteY = bottomLineY - challenge.staffStepsFromBottomLine * lineSpacing / 2f
            drawLedgerLines(
                noteY = noteY,
                topLineY = topLineY,
                bottomLineY = bottomLineY,
                lineSpacing = lineSpacing,
                noteX = noteX,
                lineColor = lineColor,
                strokeWidth = strokeWidth,
            )

            val noteWidth = lineSpacing * 1.25f
            val noteHeight = lineSpacing * 0.84f
            rotate(
                degrees = -18f,
                pivot = Offset(noteX, noteY),
            ) {
                drawOval(
                    color = noteColor,
                    topLeft = Offset(noteX - noteWidth / 2f, noteY - noteHeight / 2f),
                    size = Size(noteWidth, noteHeight),
                )
                drawOval(
                    color = lineColor,
                    topLeft = Offset(noteX - noteWidth / 2f, noteY - noteHeight / 2f),
                    size = Size(noteWidth, noteHeight),
                    style = Stroke(width = strokeWidth),
                )
            }

            val middleLineY = topLineY + lineSpacing * 2f
            val stemUp = noteY >= middleLineY
            val stemX = if (stemUp) {
                noteX + noteWidth * 0.42f
            } else {
                noteX - noteWidth * 0.42f
            }
            val stemEndY = if (stemUp) {
                noteY - lineSpacing * 3.2f
            } else {
                noteY + lineSpacing * 3.2f
            }
            drawLine(
                color = lineColor,
                start = Offset(stemX, noteY),
                end = Offset(stemX, stemEndY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrebleClefHint(
    x: Float,
    centerY: Float,
    lineSpacing: Float,
    color: Color,
    strokeWidth: Float,
) {
    drawCircle(
        color = color,
        radius = lineSpacing * 0.62f,
        center = Offset(x, centerY + lineSpacing * 0.70f),
        style = Stroke(width = strokeWidth),
    )
    drawLine(
        color = color,
        start = Offset(x, centerY + lineSpacing * 1.45f),
        end = Offset(x, centerY - lineSpacing * 2.35f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(x, centerY - lineSpacing * 2.35f),
        end = Offset(x + lineSpacing * 0.65f, centerY - lineSpacing * 1.55f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLedgerLines(
    noteY: Float,
    topLineY: Float,
    bottomLineY: Float,
    lineSpacing: Float,
    noteX: Float,
    lineColor: Color,
    strokeWidth: Float,
) {
    val ledgerHalfWidth = lineSpacing * 0.9f
    var y = bottomLineY + lineSpacing
    while (y <= noteY + lineSpacing * 0.25f) {
        drawLine(
            color = lineColor,
            start = Offset(noteX - ledgerHalfWidth, y),
            end = Offset(noteX + ledgerHalfWidth, y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        y += lineSpacing
    }

    y = topLineY - lineSpacing
    while (y >= noteY - lineSpacing * 0.25f) {
        drawLine(
            color = lineColor,
            start = Offset(noteX - ledgerHalfWidth, y),
            end = Offset(noteX + ledgerHalfWidth, y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        y -= lineSpacing
    }
}

@Composable
private fun ReferencePanel(
    referencePitch: NaturalPitch,
    onPlayReference: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Reference",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = referencePitch.label,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Button(onClick = onPlayReference) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play reference",
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Play",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PitchReadout(
    state: TrainerUiState,
    microphoneDenied: Boolean,
) {
    val colorScheme = MaterialTheme.colorScheme
    val result = state.result
    val statusText = when {
        microphoneDenied -> "Microphone permission denied"
        state.error != null -> state.error
        result?.correct == true -> "Correct: ${result.expected}"
        result?.correct == false -> "Expected ${result.expected}"
        state.isListening && state.detectedPitchLabel == null -> "Listening"
        state.detectedPitchLabel != null -> buildPitchText(state.detectedPitchLabel, state.centsOff)
        else -> "Ready"
    }
    val statusColor = when {
        result?.correct == true -> colorScheme.primary
        result?.correct == false || microphoneDenied || state.error != null -> colorScheme.error
        else -> colorScheme.onSurface
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
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
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${(state.signalLevel * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PitchMeter(
                centsOff = state.centsOff,
                inTune = result?.correct == true ||
                    (state.centsOff?.let { abs(it) <= CorrectToleranceCents } == true),
            )
        }
    }
}

@Composable
private fun PitchMeter(
    centsOff: Double?,
    inTune: Boolean,
) {
    val colorScheme = MaterialTheme.colorScheme
    val trackColor = colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
    val markerColor = if (inTune) colorScheme.primary else colorScheme.error

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
    ) {
        val y = size.height / 2f
        val startX = 18.dp.toPx()
        val endX = size.width - 18.dp.toPx()
        val centerX = (startX + endX) / 2f

        drawLine(
            color = trackColor,
            start = Offset(startX, y),
            end = Offset(endX, y),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )
        listOf(-50f, 0f, 50f).forEach { cents ->
            val x = centerX + (endX - startX) * cents / 200f
            drawLine(
                color = trackColor,
                start = Offset(x, y - 9.dp.toPx()),
                end = Offset(x, y + 9.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        centsOff?.let { cents ->
            val clamped = cents.coerceIn(-100.0, 100.0).toFloat()
            val x = centerX + (endX - startX) * clamped / 200f
            drawCircle(
                color = markerColor,
                radius = 8.dp.toPx(),
                center = Offset(x, y),
            )
        }
    }
}

@Composable
private fun SingingControls(
    state: TrainerUiState,
    microphoneGranted: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onRetry: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = onRetry,
            enabled = !state.isListening && (state.result != null || state.detectedPitchLabel != null),
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Rounded.Sync,
                contentDescription = "Retry",
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Retry",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Button(
            onClick = if (state.isListening) onStopListening else onStartListening,
            enabled = state.isListening || state.result?.correct != true,
            modifier = Modifier.weight(1.25f),
        ) {
            Icon(
                imageVector = if (state.isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                contentDescription = if (state.isListening) "Stop listening" else "Start singing",
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = when {
                    state.isListening -> "Stop"
                    microphoneGranted -> "Sing"
                    else -> "Enable mic"
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        FilledTonalButton(
            onClick = onNext,
            enabled = !state.isListening || state.result?.correct == true,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = "Next note",
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Next",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

private fun buildPitchText(
    pitchLabel: String?,
    centsOff: Double?,
): String {
    if (pitchLabel == null || centsOff == null) return "Listening"
    val roundedCents = abs(centsOff).roundToInt()
    val direction = when {
        centsOff > 4.0 -> "sharp"
        centsOff < -4.0 -> "flat"
        else -> "centered"
    }
    return if (direction == "centered") {
        "$pitchLabel centered"
    } else {
        "$pitchLabel $roundedCents cents $direction"
    }
}

private fun isMicrophoneGranted(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
}

private const val CorrectToleranceCents = 50.0
