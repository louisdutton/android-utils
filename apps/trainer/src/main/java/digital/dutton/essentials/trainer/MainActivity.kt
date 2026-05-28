package digital.dutton.essentials.trainer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
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
    val selectedMode: PracticeMode = PracticeMode.Theory,
    val theoryChallenge: TheoryChallenge = TrainingChallengeFactory.nextTheory(),
    val theoryResult: AnswerResult? = null,
    val staffNoteChallenge: StaffNoteChallenge = TrainingChallengeFactory.nextStaffNote(),
    val staffNoteResult: AnswerResult? = null,
    val dictationChallenge: DictationChallenge = TrainingChallengeFactory.nextDictation(),
    val dictationInput: List<NoteName> = emptyList(),
    val dictationResult: AnswerResult? = null,
    val stats: PracticeStats = PracticeStats(),
)

data class AnswerResult(
    val correct: Boolean,
    val selected: String,
    val expected: String,
)

data class PracticeStats(
    val attempts: Int = 0,
    val correct: Int = 0,
    val streak: Int = 0,
) {
    val accuracy: Int
        get() = if (attempts == 0) 0 else correct * 100 / attempts

    fun record(correctAnswer: Boolean): PracticeStats {
        return copy(
            attempts = attempts + 1,
            correct = correct + if (correctAnswer) 1 else 0,
            streak = if (correctAnswer) streak + 1 else 0,
        )
    }
}

class TrainerViewModel : ViewModel() {
    private val tonePlayer = TrainingTonePlayer()
    private val _uiState = MutableStateFlow(TrainerUiState())
    val uiState: StateFlow<TrainerUiState> = _uiState.asStateFlow()

    fun selectMode(mode: PracticeMode) {
        _uiState.update { it.copy(selectedMode = mode) }
    }

    fun answerTheory(answer: String) {
        val state = _uiState.value
        if (state.theoryResult != null) return
        val correct = answer == state.theoryChallenge.correctAnswer
        _uiState.update {
            it.copy(
                theoryResult = AnswerResult(
                    correct = correct,
                    selected = answer,
                    expected = state.theoryChallenge.correctAnswer,
                ),
                stats = it.stats.record(correct),
            )
        }
    }

    fun nextTheory() {
        _uiState.update {
            it.copy(
                theoryChallenge = TrainingChallengeFactory.nextTheory(),
                theoryResult = null,
            )
        }
    }

    fun answerStaffNote(answer: NoteName) {
        val state = _uiState.value
        if (state.staffNoteResult != null) return
        val correct = answer == state.staffNoteChallenge.correctAnswer
        _uiState.update {
            it.copy(
                staffNoteResult = AnswerResult(
                    correct = correct,
                    selected = answer.label,
                    expected = state.staffNoteChallenge.correctAnswer.label,
                ),
                stats = it.stats.record(correct),
            )
        }
    }

    fun nextStaffNote() {
        _uiState.update {
            it.copy(
                staffNoteChallenge = TrainingChallengeFactory.nextStaffNote(),
                staffNoteResult = null,
            )
        }
    }

    fun playDictation() {
        val challenge = _uiState.value.dictationChallenge
        tonePlayer.playSequence(
            midiNumbers = challenge.notes.map { it.midiNumber },
            tempoBpm = challenge.tempoBpm,
        )
    }

    fun addDictationNote(note: NoteName) {
        _uiState.update { state ->
            if (state.dictationResult != null ||
                state.dictationInput.size >= state.dictationChallenge.notes.size
            ) {
                state
            } else {
                state.copy(dictationInput = state.dictationInput + note)
            }
        }
    }

    fun removeDictationNote() {
        _uiState.update { state ->
            if (state.dictationResult != null || state.dictationInput.isEmpty()) {
                state
            } else {
                state.copy(dictationInput = state.dictationInput.dropLast(1))
            }
        }
    }

    fun clearDictationInput() {
        _uiState.update { state ->
            if (state.dictationResult != null) {
                state
            } else {
                state.copy(dictationInput = emptyList())
            }
        }
    }

    fun submitDictation() {
        val state = _uiState.value
        if (state.dictationResult != null ||
            state.dictationInput.size != state.dictationChallenge.notes.size
        ) {
            return
        }
        val correct = state.dictationInput == state.dictationChallenge.correctAnswer
        _uiState.update {
            it.copy(
                dictationResult = AnswerResult(
                    correct = correct,
                    selected = state.dictationInput.joinToString(" ") { note -> note.label },
                    expected = state.dictationChallenge.answerLabel,
                ),
                stats = it.stats.record(correct),
            )
        }
    }

    fun nextDictation() {
        tonePlayer.stop()
        _uiState.update {
            it.copy(
                dictationChallenge = TrainingChallengeFactory.nextDictation(),
                dictationInput = emptyList(),
                dictationResult = null,
            )
        }
    }

    override fun onCleared() {
        tonePlayer.stop()
        super.onCleared()
    }
}

@Composable
private fun TrainerApp(viewModel: TrainerViewModel = viewModel()) {
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
            TrainerScreen(
                state = state,
                onModeSelected = viewModel::selectMode,
                onTheoryAnswer = viewModel::answerTheory,
                onNextTheory = viewModel::nextTheory,
                onStaffAnswer = viewModel::answerStaffNote,
                onNextStaff = viewModel::nextStaffNote,
                onPlayDictation = viewModel::playDictation,
                onDictationNote = viewModel::addDictationNote,
                onDeleteDictationNote = viewModel::removeDictationNote,
                onClearDictation = viewModel::clearDictationInput,
                onSubmitDictation = viewModel::submitDictation,
                onNextDictation = viewModel::nextDictation,
            )
        }
    }
}

@Composable
private fun TrainerScreen(
    state: TrainerUiState,
    onModeSelected: (PracticeMode) -> Unit,
    onTheoryAnswer: (String) -> Unit,
    onNextTheory: () -> Unit,
    onStaffAnswer: (NoteName) -> Unit,
    onNextStaff: () -> Unit,
    onPlayDictation: () -> Unit,
    onDictationNote: (NoteName) -> Unit,
    onDeleteDictationNote: () -> Unit,
    onClearDictation: () -> Unit,
    onSubmitDictation: () -> Unit,
    onNextDictation: () -> Unit,
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppHeader(stats = state.stats)
            ModeTabs(
                selectedMode = state.selectedMode,
                onModeSelected = onModeSelected,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when (state.selectedMode) {
                    PracticeMode.Theory -> TheoryScreen(
                        challenge = state.theoryChallenge,
                        result = state.theoryResult,
                        onAnswer = onTheoryAnswer,
                        onNext = onNextTheory,
                    )
                    PracticeMode.SightReading -> SightReadingScreen(
                        challenge = state.staffNoteChallenge,
                        result = state.staffNoteResult,
                        onAnswer = onStaffAnswer,
                        onNext = onNextStaff,
                    )
                    PracticeMode.Dictation -> DictationScreen(
                        challenge = state.dictationChallenge,
                        input = state.dictationInput,
                        result = state.dictationResult,
                        onPlay = onPlayDictation,
                        onNote = onDictationNote,
                        onDeleteNote = onDeleteDictationNote,
                        onClear = onClearDictation,
                        onSubmit = onSubmitDictation,
                        onNext = onNextDictation,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppHeader(stats: PracticeStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = "Music Trainer",
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
private fun ModeTabs(
    selectedMode: PracticeMode,
    onModeSelected: (PracticeMode) -> Unit,
) {
    PrimaryTabRow(
        selectedTabIndex = selectedMode.ordinal,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        PracticeMode.entries.forEach { mode ->
            Tab(
                selected = mode == selectedMode,
                onClick = { onModeSelected(mode) },
                text = {
                    Text(
                        text = mode.label,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun TheoryScreen(
    challenge: TheoryChallenge,
    result: AnswerResult?,
    onAnswer: (String) -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ChallengePanel(
            label = challenge.category,
            primary = challenge.question,
            secondary = challenge.detail,
        )
        ChoiceList(
            options = challenge.options,
            selected = result?.selected,
            correct = result?.expected,
            enabled = result == null,
            onSelected = onAnswer,
        )
        ResultBlock(result = result)
        if (result != null) {
            NextButton(
                text = "Next question",
                onClick = onNext,
            )
        }
    }
}

@Composable
private fun SightReadingScreen(
    challenge: StaffNoteChallenge,
    result: AnswerResult?,
    onAnswer: (NoteName) -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StaffPanel(challenge = challenge)
        ChoiceList(
            options = NoteName.entries.map { it.label },
            selected = result?.selected,
            correct = result?.expected,
            enabled = result == null,
            onSelected = { label ->
                NoteName.entries.firstOrNull { it.label == label }?.let(onAnswer)
            },
        )
        ResultBlock(result = result)
        if (result != null) {
            NextButton(
                text = "Next note",
                onClick = onNext,
            )
        }
    }
}

@Composable
private fun DictationScreen(
    challenge: DictationChallenge,
    input: List<NoteName>,
    result: AnswerResult?,
    onPlay: () -> Unit,
    onNote: (NoteName) -> Unit,
    onDeleteNote: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
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
                ChallengeSummary(
                    label = challenge.keyLabel,
                    primary = "${challenge.notes.size} notes",
                    secondary = "${challenge.tempoBpm} bpm",
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onPlay,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play dictation",
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Play",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        DictationSlots(
            targetLength = challenge.notes.size,
            input = input,
            result = result,
        )
        NoteNameGrid(
            enabled = result == null && input.size < challenge.notes.size,
            onSelected = onNote,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onDeleteNote,
                enabled = result == null && input.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove note",
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Undo",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            TextButton(
                onClick = onClear,
                enabled = result == null && input.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Clear notes",
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Clear",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Button(
                onClick = onSubmit,
                enabled = result == null && input.size == challenge.notes.size,
                modifier = Modifier.weight(1.2f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Check answer",
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Check",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        ResultBlock(result = result)
        if (result != null) {
            NextButton(
                text = "Next melody",
                onClick = onNext,
            )
        }
    }
}

@Composable
private fun ChallengePanel(
    label: String,
    primary: String,
    secondary: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        ChallengeSummary(
            label = label,
            primary = primary,
            secondary = secondary,
            modifier = Modifier.padding(18.dp),
        )
    }
}

@Composable
private fun ChallengeSummary(
    label: String,
    primary: String,
    secondary: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = primary,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = secondary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChoiceList(
    options: List<String>,
    selected: String?,
    correct: String?,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val isCorrect = option == correct
            val colorScheme = MaterialTheme.colorScheme
            val containerColor = when {
                correct != null && isCorrect -> colorScheme.primaryContainer
                correct != null && isSelected && !isCorrect -> colorScheme.errorContainer
                else -> colorScheme.surfaceContainerHigh
            }
            val contentColor = when {
                correct != null && isCorrect -> colorScheme.onPrimaryContainer
                correct != null && isSelected && !isCorrect -> colorScheme.onErrorContainer
                else -> colorScheme.onSurface
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .clickable(enabled = enabled) { onSelected(option) },
                shape = RoundedCornerShape(8.dp),
                color = containerColor,
                contentColor = contentColor,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (correct != null && isCorrect) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultBlock(result: AnswerResult?) {
    if (result == null) return

    val colorScheme = MaterialTheme.colorScheme
    val containerColor = if (result.correct) {
        colorScheme.primaryContainer
    } else {
        colorScheme.errorContainer
    }
    val contentColor = if (result.correct) {
        colorScheme.onPrimaryContainer
    } else {
        colorScheme.onErrorContainer
    }
    val message = if (result.correct) {
        "Correct"
    } else {
        "Answer: ${result.expected}"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun NextButton(
    text: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.Rounded.Sync,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = text,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun StaffPanel(challenge: StaffNoteChallenge) {
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
                    text = "Name the note",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StaffCanvas(challenge = challenge)
        }
    }
}

@Composable
private fun StaffCanvas(challenge: StaffNoteChallenge) {
    val lineColor = MaterialTheme.colorScheme.onSurface
    val noteColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.surface

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp),
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

            val noteX = staffLeft + staffWidth * 0.56f
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
private fun DictationSlots(
    targetLength: Int,
    input: List<NoteName>,
    result: AnswerResult?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(targetLength) { index ->
            val note = input.getOrNull(index)
            val colorScheme = MaterialTheme.colorScheme
            val containerColor = when {
                result?.correct == true -> colorScheme.primaryContainer
                result?.correct == false -> colorScheme.errorContainer
                note != null -> colorScheme.secondaryContainer
                else -> colorScheme.surfaceContainerHigh
            }
            val contentColor = when {
                result?.correct == true -> colorScheme.onPrimaryContainer
                result?.correct == false -> colorScheme.onErrorContainer
                note != null -> colorScheme.onSecondaryContainer
                else -> colorScheme.onSurface
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
                shape = RoundedCornerShape(8.dp),
                color = containerColor,
                contentColor = contentColor,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = note?.label ?: "",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteNameGrid(
    enabled: Boolean,
    onSelected: (NoteName) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NoteName.entries.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { note ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clickable(enabled = enabled) { onSelected(note) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (enabled) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = note.label,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                repeat(4 - row.size) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                    )
                }
            }
        }
    }
}
