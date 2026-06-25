package digital.dutton.essentials.learn

import android.app.Application
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LearnApp() }
    }
}

data class LearnUiState(
    val decks: List<DeckSummary> = emptyList(),
    val selectedDeckId: Long? = null,
    val currentCard: StudyCard? = null,
    val showAnswer: Boolean = false,
    val isImporting: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val selectedDeck: DeckSummary?
        get() = selectedDeckId?.let { id -> decks.firstOrNull { it.id == id } }
}

class LearnViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LearnStore(application)
    private val importer = AnkiPackageImporter(application)
    private val _uiState = MutableStateFlow(LearnUiState(decks = store.listDecks()))
    val uiState: StateFlow<LearnUiState> = _uiState.asStateFlow()

    fun importPackage(uri: Uri) {
        _uiState.update {
            it.copy(
                isImporting = true,
                message = null,
                error = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val (packageName, cards) = importer.readPackage(uri)
                store.importCards(packageName, cards)
            }.onSuccess { result ->
                val decks = store.listDecks()
                _uiState.update {
                    it.copy(
                        decks = decks,
                        selectedDeckId = it.selectedDeckId ?: decks.firstOrNull()?.id,
                        currentCard = it.currentCard ?: decks.firstOrNull()?.let { deck ->
                            store.nextCard(deck.id)
                        },
                        isImporting = false,
                        message = "${result.newCards}/${result.totalCards} cards imported from ${result.packageName}",
                        error = null,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        error = error.message ?: "Unable to import this Anki package.",
                    )
                }
            }
        }
    }

    fun selectDeck(deckId: Long) {
        _uiState.update {
            it.copy(
                selectedDeckId = deckId,
                currentCard = store.nextCard(deckId),
                showAnswer = false,
                message = null,
                error = null,
            )
        }
    }

    fun clearDeckSelection() {
        _uiState.update {
            it.copy(
                selectedDeckId = null,
                currentCard = null,
                showAnswer = false,
                message = null,
                error = null,
            )
        }
    }

    fun showAnswer() {
        _uiState.update { it.copy(showAnswer = true) }
    }

    fun review(rating: ReviewRating) {
        val card = _uiState.value.currentCard ?: return
        viewModelScope.launch(Dispatchers.IO) {
            store.recordReview(card, rating)
            val decks = store.listDecks()
            _uiState.update { state ->
                val deckId = state.selectedDeckId ?: card.deckId
                state.copy(
                    decks = decks,
                    currentCard = store.nextCard(deckId),
                    showAnswer = false,
                    message = null,
                    error = null,
                )
            }
        }
    }

    fun refresh() {
        val deckId = _uiState.value.selectedDeckId
        _uiState.update {
            it.copy(
                decks = store.listDecks(),
                currentCard = deckId?.let(store::nextCard),
                showAnswer = false,
            )
        }
    }
}

@Composable
private fun LearnApp(viewModel: LearnViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    val packagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::importPackage)
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            LearnScreen(
                state = state,
                onImport = { packagePicker.launch(AnkiMimeTypes) },
                onSelectDeck = viewModel::selectDeck,
                onClearDeck = viewModel::clearDeckSelection,
                onShowAnswer = viewModel::showAnswer,
                onReview = viewModel::review,
                onRefresh = viewModel::refresh,
            )
        }
    }
}

@Composable
private fun LearnScreen(
    state: LearnUiState,
    onImport: () -> Unit,
    onSelectDeck: (Long) -> Unit,
    onClearDeck: () -> Unit,
    onShowAnswer: () -> Unit,
    onReview: (ReviewRating) -> Unit,
    onRefresh: () -> Unit,
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
            AppHeader(
                importing = state.isImporting,
                onImport = onImport,
                onRefresh = onRefresh,
            )
            state.message?.let { MessageBanner(text = it, isError = false) }
            state.error?.let { MessageBanner(text = it, isError = true) }

            if (state.selectedDeckId == null) {
                DecksScreen(
                    decks = state.decks,
                    importing = state.isImporting,
                    onImport = onImport,
                    onSelectDeck = onSelectDeck,
                    modifier = Modifier.weight(1f),
                )
            } else {
                StudyScreen(
                    deck = state.selectedDeck,
                    card = state.currentCard,
                    showAnswer = state.showAnswer,
                    onBack = onClearDeck,
                    onShowAnswer = onShowAnswer,
                    onReview = onReview,
                    modifier = Modifier.weight(1f),
                )
                DeckStrip(
                    decks = state.decks,
                    selectedDeckId = state.selectedDeckId,
                    onSelectDeck = onSelectDeck,
                )
            }
        }
    }
}

@Composable
private fun AppHeader(
    importing: Boolean,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = "Learn",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (importing) "Importing Anki package" else "Anki package flashcards",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(
            onClick = onRefresh,
            enabled = !importing,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Rounded.Sync, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
        }
        Button(
            onClick = onImport,
            enabled = !importing,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = "Import Anki package", modifier = Modifier.size(18.dp))
            Text("Import", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun DecksScreen(
    decks: List<DeckSummary>,
    importing: Boolean,
    onImport: () -> Unit,
    onSelectDeck: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (decks.isEmpty()) {
        EmptyState(
            importing = importing,
            onImport = onImport,
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(decks, key = { it.id }) { deck ->
            DeckRow(
                deck = deck,
                selected = false,
                onClick = { onSelectDeck(deck.id) },
            )
        }
    }
}

@Composable
private fun EmptyState(
    importing: Boolean,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Import an Anki .apkg deck",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text(
                    text = "Cards are stored locally for offline study.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Button(
                    onClick = onImport,
                    enabled = !importing,
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Choose package", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun StudyScreen(
    deck: DeckSummary?,
    card: StudyCard?,
    showAnswer: Boolean,
    onBack: () -> Unit,
    onShowAnswer: () -> Unit,
    onReview: (ReviewRating) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Decks", modifier = Modifier.padding(start = 4.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deck?.name ?: card?.deckName ?: "Deck",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                deck?.let {
                    Text(
                        text = "${it.dueCount} due of ${it.cardCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (card == null) {
            MessageBanner(
                text = "No cards are available in this deck.",
                isError = false,
            )
            return
        }

        CardPanel(
            label = "Front",
            text = card.front,
        )
        if (showAnswer) {
            CardPanel(
                label = "Back",
                text = card.back,
            )
            ReviewButtons(onReview = onReview)
        } else {
            Button(
                onClick = onShowAnswer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Show answer", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun CardPanel(
    label: String,
    text: String,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 170.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.titleLarge,
                lineHeight = MaterialTheme.typography.titleLarge.lineHeight,
            )
        }
    }
}

@Composable
private fun ReviewButtons(onReview: (ReviewRating) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = { onReview(ReviewRating.Again) },
            modifier = Modifier.weight(1f),
        ) {
            Text("Again")
        }
        Button(
            onClick = { onReview(ReviewRating.Good) },
            modifier = Modifier.weight(1f),
        ) {
            Text("Good")
        }
        Button(
            onClick = { onReview(ReviewRating.Easy) },
            modifier = Modifier.weight(1f),
        ) {
            Text("Easy")
        }
    }
}

@Composable
private fun DeckStrip(
    decks: List<DeckSummary>,
    selectedDeckId: Long?,
    onSelectDeck: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(decks, key = { it.id }) { deck ->
            DeckRow(
                deck = deck,
                selected = deck.id == selectedDeckId,
                onClick = { onSelectDeck(deck.id) },
            )
        }
    }
}

@Composable
private fun DeckRow(
    deck: DeckSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
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
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deck.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${deck.cardCount} cards",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Text(
                    text = "${deck.dueCount} due",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun MessageBanner(
    text: String,
    isError: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

private val AnkiMimeTypes = arrayOf(
    "application/octet-stream",
    "application/zip",
    "application/x-zip-compressed",
    "*/*",
)
