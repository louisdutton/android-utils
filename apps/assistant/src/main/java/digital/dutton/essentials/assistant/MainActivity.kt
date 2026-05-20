package digital.dutton.essentials.assistant

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.SettingsVoice
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEBUG_COMMAND_EXTRA = "digital.dutton.essentials.assistant.DEBUG_COMMAND"
private const val DEBUG_PREVIEW_EXTRA = "digital.dutton.essentials.assistant.DEBUG_PREVIEW"

data class AssistantUiState(
    val input: String = "",
    val history: List<AssistantResult> = emptyList(),
    val isRunning: Boolean = false,
    val isListening: Boolean = false,
    val status: String = "Ready"
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = AssistantEngine(application.applicationContext)

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { engine.warmLanguageModel() }
        }
    }

    fun updateInput(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    fun submitInput() {
        submitText(_uiState.value.input)
    }

    fun submitText(text: String) {
        val request = text.trim()
        if (request.isBlank() || _uiState.value.isRunning) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    input = "",
                    isRunning = true,
                    status = "Running"
                )
            }
            val result = engine.execute(request)
            _uiState.update {
                it.copy(
                    history = (it.history + result).takeLast(30),
                    isRunning = false,
                    status = if (result.error == null) "Ready" else "Action failed"
                )
            }
        }
    }

    fun previewText(text: String) {
        val request = text.trim()
        if (request.isBlank() || _uiState.value.isRunning) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    input = "",
                    isRunning = true,
                    status = "Previewing"
                )
            }
            val result = engine.preview(request)
            _uiState.update {
                it.copy(
                    history = (it.history + result).takeLast(30),
                    isRunning = false,
                    status = "Ready"
                )
            }
        }
    }

    fun listenAndRun() {
        if (_uiState.value.isListening || _uiState.value.isRunning) return

        viewModelScope.launch {
            _uiState.update { it.copy(isListening = true, status = "Listening") }
            runCatching {
                LocalSpeechTranscriber.transcribe(getApplication<Application>().applicationContext)
            }.onSuccess { text ->
                _uiState.update { it.copy(isListening = false, input = text, status = "Running") }
                submitText(text)
            }.onFailure { error ->
                val result = AssistantResult(
                    input = "Speech",
                    title = "Voice input failed",
                    detail = error.message ?: "Could not transcribe speech",
                    confidence = 0f,
                    launched = false,
                    error = error.message
                )
                _uiState.update {
                    it.copy(
                        history = (it.history + result).takeLast(30),
                        isListening = false,
                        status = "Ready"
                    )
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private val assistantRoleHeld = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AssistantTheme {
                val viewModel: AssistantViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsState()
                val context = LocalContext.current
                var initialListenRequested by remember { mutableStateOf(false) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) viewModel.listenAndRun()
                }

                val startListening = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.listenAndRun()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                LaunchedEffect(Unit) {
                    assistantRoleHeld.value = isAssistantRoleHeld()
                    val isDebuggable =
                        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    val debugCommand = if (isDebuggable) {
                        intent.getStringExtra(DEBUG_COMMAND_EXTRA)?.trim()
                    } else {
                        null
                    }
                    if (!debugCommand.isNullOrBlank()) {
                        if (intent.getBooleanExtra(DEBUG_PREVIEW_EXTRA, false)) {
                            viewModel.previewText(debugCommand)
                        } else {
                            viewModel.submitText(debugCommand)
                        }
                        return@LaunchedEffect
                    }
                    if (!initialListenRequested) {
                        initialListenRequested = true
                        startListening()
                    }
                }

                AssistantScreen(
                    uiState = uiState,
                    assistantRoleHeld = assistantRoleHeld.value,
                    onListen = startListening,
                    onRequestAssistantRole = { requestAssistantRole() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        assistantRoleHeld.value = isAssistantRoleHeld()
    }

    private fun requestAssistantRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
            !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        ) {
            startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
            return
        }

        runCatching {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.recoverCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    private fun isAssistantRoleHeld(): Boolean {
        val roleManager = getSystemService(RoleManager::class.java)
        return roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
            roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }
}

@Composable
private fun AssistantTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantScreen(
    uiState: AssistantUiState,
    assistantRoleHeld: Boolean,
    onListen: () -> Unit,
    onRequestAssistantRole: () -> Unit
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            AssistantStatusRow(
                roleHeld = assistantRoleHeld,
                status = uiState.status,
                onRequestAssistantRole = onRequestAssistantRole
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                VoiceControl(
                    status = uiState.status,
                    enabled = !uiState.isRunning && !uiState.isListening,
                    isListening = uiState.isListening,
                    isRunning = uiState.isRunning,
                    onListen = onListen
                )
            }

            if (uiState.history.isNotEmpty() || uiState.isRunning || uiState.isListening) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.history, key = { "${it.input}-${it.title}-${it.detail}" }) { item ->
                        ResultRow(item)
                    }

                    if (uiState.isRunning || uiState.isListening) {
                        item {
                            RunningRow(if (uiState.isListening) "Listening" else "Running")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceControl(
    status: String,
    enabled: Boolean,
    isListening: Boolean,
    isRunning: Boolean,
    onListen: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier
                .size(132.dp)
                .clickable(enabled = enabled, onClick = onListen),
            shape = CircleShape,
            color = if (isListening) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            tonalElevation = 4.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(42.dp), strokeWidth = 4.dp)
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = "Speak",
                        modifier = Modifier.size(54.dp),
                        tint = if (isListening) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        Text(
            text = when {
                isListening -> "Listening"
                isRunning -> "Running"
                else -> status
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AssistantStatusRow(
    roleHeld: Boolean,
    status: String,
    onRequestAssistantRole: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AssistChip(
            onClick = onRequestAssistantRole,
            label = { Text(if (roleHeld) "Default assistant" else "Set default") },
            leadingIcon = {
                Icon(
                    imageVector = if (roleHeld) Icons.Rounded.CheckCircle else Icons.Rounded.SettingsVoice,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        )
        Text(
            text = status,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ResultRow(result: AssistantResult) {
    val failed = result.error != null
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (failed) Icons.Rounded.Error else Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(22.dp),
                tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.input,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = result.error ?: result.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RunningRow(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
