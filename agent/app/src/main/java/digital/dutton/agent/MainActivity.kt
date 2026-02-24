package digital.dutton.agent

import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.ViewModel
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.compose.components.markdownComponents
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI

enum class Screen { Chat, Settings }

sealed class MessageContent {
    data class Text(val content: String) : MessageContent()
    data class Error(val message: String) : MessageContent()
    data class ToolUse(
        val name: String,
        val input: String?,
        val output: String? = null,
        val isError: Boolean = false
    ) : MessageContent()
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: MessageContent,
    val isUser: Boolean
)

class ChatViewModel : ViewModel() {
    private var client: GhostClient? = null
    private var streamJob: kotlinx.coroutines.Job? = null
    private var prefs: android.content.SharedPreferences? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _sessions = MutableStateFlow<List<GhostSession>>(emptyList())
    val sessions: StateFlow<List<GhostSession>> = _sessions.asStateFlow()

    private val _currentSession = MutableStateFlow<GhostSession?>(null)
    val currentSession: StateFlow<GhostSession?> = _currentSession.asStateFlow()

    private val _sessionTitles = MutableStateFlow<Map<String, String>>(emptyMap())
    val sessionTitles: StateFlow<Map<String, String>> = _sessionTitles.asStateFlow()

    private var currentAssistantMessageId: String? = null
    private var currentTextContent = StringBuilder()

    fun initPrefs(prefs: android.content.SharedPreferences) {
        this.prefs = prefs
        // Load existing titles
        val titles = mutableMapOf<String, String>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("session_title_") && value is String) {
                titles[key.removePrefix("session_title_")] = value
            }
        }
        _sessionTitles.value = titles
    }

    fun getSessionTitle(sessionId: String): String {
        return _sessionTitles.value[sessionId] ?: "New session"
    }

    private fun setSessionTitle(sessionId: String, firstMessage: String) {
        if (_sessionTitles.value.containsKey(sessionId)) return
        val title = firstMessage.take(40).let { if (firstMessage.length > 40) "$it..." else it }
        _sessionTitles.value = _sessionTitles.value + (sessionId to title)
        prefs?.edit()?.putString("session_title_$sessionId", title)?.apply()
    }

    fun connect(serverUrl: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val ghostClient = GhostClient(serverUrl)
                client = ghostClient

                // Load existing sessions (inline, not async)
                val sessions = ghostClient.listSessions()
                _sessions.value = sessions

                // Only auto-create if no sessions exist at all
                if (sessions.isEmpty()) {
                    ghostClient.createSession()
                    _sessions.value = ghostClient.listSessions()
                    _currentSession.value = _sessions.value.find { it.id == ghostClient.currentSessionId() }
                    startStreaming(ghostClient)
                } else {
                    // Resume most recent session
                    val mostRecent = sessions.maxByOrNull { it.createdAt }
                    if (mostRecent != null) {
                        ghostClient.connectToSession(mostRecent.id)
                        _currentSession.value = mostRecent
                        startStreaming(ghostClient)
                    }
                }

                _isConnected.value = true
                _isLoading.value = false
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(
                    content = MessageContent.Error("Connection failed: ${e.message}"),
                    isUser = false
                )
                _isConnected.value = false
                _isLoading.value = false
            }
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            try {
                client?.let { c ->
                    _sessions.value = c.listSessions()
                }
            } catch (e: Exception) {
                // Ignore refresh errors
            }
        }
    }

    fun selectSession(session: GhostSession) {
        viewModelScope.launch {
            try {
                val c = client ?: return@launch
                streamJob?.cancel()
                c.connectToSession(session.id)
                _currentSession.value = session
                _messages.value = emptyList()
                startStreaming(c)
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(
                    content = MessageContent.Error("Failed to switch: ${e.message}"),
                    isUser = false
                )
            }
        }
    }

    fun createSession(workDir: String?) {
        viewModelScope.launch {
            try {
                val c = client ?: return@launch
                _isLoading.value = true
                streamJob?.cancel()
                c.createSession(workDir)
                refreshSessions()
                _currentSession.value = _sessions.value.find { it.id == c.currentSessionId() }
                _messages.value = emptyList()
                startStreaming(c)
                _isLoading.value = false
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(
                    content = MessageContent.Error("Failed to create: ${e.message}"),
                    isUser = false
                )
                _isLoading.value = false
            }
        }
    }

    fun deleteSession(session: GhostSession) {
        viewModelScope.launch {
            try {
                val c = client ?: return@launch
                c.deleteSession(session.id)
                refreshSessions()
                if (_currentSession.value?.id == session.id) {
                    streamJob?.cancel()
                    _currentSession.value = null
                    _messages.value = emptyList()
                }
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(
                    content = MessageContent.Error("Failed to delete: ${e.message}"),
                    isUser = false
                )
            }
        }
    }

    private fun startStreaming(ghostClient: GhostClient) {
        streamJob = viewModelScope.launch {
            try {
                ghostClient.streamEvents().collect { event ->
                    when (event) {
                        is GhostEvent.TurnBegin -> {
                            currentTextContent.clear()
                            val msg = ChatMessage(content = MessageContent.Text(""), isUser = false)
                            currentAssistantMessageId = msg.id
                            _messages.value = _messages.value + msg
                        }
                        is GhostEvent.Text -> {
                            currentAssistantMessageId?.let { id ->
                                currentTextContent.append(event.content)
                                _messages.value = _messages.value.map { msg ->
                                    if (msg.id == id) msg.copy(content = MessageContent.Text(currentTextContent.toString()))
                                    else msg
                                }
                            }
                        }
                        is GhostEvent.ToolCall -> {
                            _messages.value = _messages.value + ChatMessage(
                                content = MessageContent.ToolUse(
                                    name = event.name,
                                    input = event.input,
                                    output = null,
                                    isError = false
                                ),
                                isUser = false
                            )
                        }
                        is GhostEvent.ToolResult -> {
                            // Find the last ToolUse message without output and merge result into it
                            val msgs = _messages.value.toMutableList()
                            val idx = msgs.indexOfLast { msg ->
                                val c = msg.content
                                c is MessageContent.ToolUse && c.output == null
                            }
                            if (idx >= 0) {
                                val existing = msgs[idx].content as MessageContent.ToolUse
                                msgs[idx] = msgs[idx].copy(
                                    content = existing.copy(
                                        output = event.output,
                                        isError = event.isError
                                    )
                                )
                                _messages.value = msgs
                            }
                        }
                        is GhostEvent.TurnEnd -> {
                            currentAssistantMessageId = null
                            currentTextContent.clear()
                            _isLoading.value = false
                        }
                        is GhostEvent.Error -> {
                            _messages.value = _messages.value + ChatMessage(
                                content = MessageContent.Error(event.message),
                                isUser = false
                            )
                            _isLoading.value = false
                        }
                        is GhostEvent.ConnectionClosed -> {
                            // Connection closed, mark as disconnected for auto-reconnect
                            _isConnected.value = false
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal cancellation, don't show error
            } catch (e: java.io.EOFException) {
                // Normal stream end
            } catch (e: java.io.InterruptedIOException) {
                // Normal interruption
            } catch (e: java.net.SocketException) {
                // Normal socket close
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(
                    content = MessageContent.Error("Stream error: ${e.message}"),
                    isUser = false
                )
            }
        }
    }

    fun sendMessage(content: String) {
        val c = client ?: return
        if (content.isBlank()) return

        viewModelScope.launch {
            // Set session title from first message
            val sessionId = _currentSession.value?.id
            val isFirstMessage = _messages.value.none { it.isUser }
            if (sessionId != null && isFirstMessage) {
                setSessionTitle(sessionId, content)
            }

            _messages.value = _messages.value + ChatMessage(content = MessageContent.Text(content), isUser = true)
            _isLoading.value = true
            try {
                c.sendMessage(content)
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(
                    content = MessageContent.Error("Failed to send: ${e.message}"),
                    isUser = false
                )
                _isLoading.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        client?.close()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AgentTheme {
                var currentScreen by remember { mutableStateOf(Screen.Chat) }

                when (currentScreen) {
                    Screen.Chat -> ChatScreen(
                        onSettingsClick = { currentScreen = Screen.Settings }
                    )
                    Screen.Settings -> SettingsScreen(
                        onBack = { currentScreen = Screen.Chat },
                        onRequestAssistantRole = { requestAssistantRole() }
                    )
                }
            }
        }
    }

    private fun requestAssistantRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        val available = roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
        val held = roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)

        Toast.makeText(this, "Role available: $available, held: $held", Toast.LENGTH_LONG).show()

        if (!available) {
            try {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
            return
        }

        if (!held) {
            try {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                Toast.makeText(this, "Launching role request...", Toast.LENGTH_SHORT).show()
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_ASSISTANT_ROLE)
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ASSISTANT_ROLE) {
            Toast.makeText(this, "Result: $resultCode", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQUEST_ASSISTANT_ROLE = 1
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onSettingsClick: () -> Unit,
    viewModel: ChatViewModel = viewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()
    val sessionTitles by viewModel.sessionTitles.collectAsState()

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE) }
    val savedUrl = remember { prefs.getString("server_url", null) }

    // Initialize prefs in viewModel
    LaunchedEffect(Unit) {
        viewModel.initPrefs(prefs)
    }

    var inputText by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(savedUrl ?: "") }
    var showServerDialog by remember { mutableStateOf(savedUrl == null) }
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var newWorkDir by remember { mutableStateOf("") }
    var developerMode by remember { mutableStateOf(prefs.getBoolean("developer_mode", false)) }

    // Dictation state
    var isRecording by remember { mutableStateOf(false) }
    var isTranscribing by remember { mutableStateOf(false) }
    val audioRecorder = remember { AudioRecorder(context.cacheDir) }
    val whisperClient = remember(savedUrl) {
        savedUrl?.let { url ->
            try {
                val uri = URI(url)
                val whisperUrl = "${uri.scheme}://${uri.host}:5932"
                WhisperClient(whisperUrl)
            } catch (e: Exception) {
                null
            }
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Auto-connect if we have a saved URL
    LaunchedEffect(savedUrl) {
        if (savedUrl != null && !isConnected) {
            viewModel.connect(savedUrl)
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    if (showServerDialog && !isConnected) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Connect to Ghost Server") },
            text = {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putString("server_url", serverUrl).apply()
                    viewModel.connect(serverUrl)
                    showServerDialog = false
                }) {
                    Text("Connect")
                }
            }
        )
    }

    if (showNewSessionDialog) {
        val workDirBase = prefs.getString("work_dir_base", "/home/louis/projects") ?: "/home/louis/projects"
        AlertDialog(
            onDismissRequest = { showNewSessionDialog = false },
            title = { Text("New Session") },
            text = {
                Column {
                    Text(
                        text = "Base: $workDirBase",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = newWorkDir,
                        onValueChange = { newWorkDir = it },
                        label = { Text("Project name (optional)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val fullPath = when {
                        newWorkDir.isBlank() -> null
                        newWorkDir.startsWith("/") -> newWorkDir
                        else -> "${workDirBase.trimEnd('/')}/${newWorkDir.trimStart('/')}"
                    }
                    viewModel.createSession(fullPath)
                    newWorkDir = ""
                    showNewSessionDialog = false
                    scope.launch { drawerState.close() }
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewSessionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF121212)
            ) {
                // Push content to bottom
                Spacer(modifier = Modifier.weight(1f))

                Text(
                    "Sessions",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Gray
                )

                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    reverseLayout = true
                ) {
                    items(sessions, key = { it.id }) { session ->
                        val isSelected = session.id == currentSession?.id
                        val title = sessionTitles[session.id] ?: "New session"
                        NavigationDrawerItem(
                            label = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            title,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            session.workDir,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.deleteSession(session) }
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = Color.Gray
                                        )
                                    }
                                }
                            },
                            selected = isSelected,
                            onClick = {
                                viewModel.selectSession(session)
                                scope.launch { drawerState.close() }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                unselectedContainerColor = Color.Transparent
                            )
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF333333))
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    label = { Text("New Session") },
                    selected = false,
                    onClick = { showNewSessionDialog = true },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent
                    )
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    label = { Text("Developer Mode") },
                    badge = {
                        Switch(
                            checked = developerMode,
                            onCheckedChange = {
                                developerMode = it
                                prefs.edit().putBoolean("developer_mode", it).apply()
                            },
                            modifier = Modifier.height(24.dp)
                        )
                    },
                    selected = false,
                    onClick = {
                        developerMode = !developerMode
                        prefs.edit().putBoolean("developer_mode", developerMode).apply()
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent
                    )
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onSettingsClick()
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent
                    )
                )
                Spacer(modifier = Modifier.navigationBarsPadding())
            }
        }
    ) {
        Scaffold(
            containerColor = Color.Black,
            topBar = {
                // Minimal status header
                Surface(
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Circle,
                            contentDescription = if (isConnected) "Connected" else "Disconnected",
                            tint = if (isConnected) Color(0xFF4CAF50) else Color(0xFFE57373),
                            modifier = Modifier.size(10.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            currentSession?.let { sessionTitles[it.id] ?: "New session" } ?: "Agent",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            },
            bottomBar = {
                ChatInputBar(
                    value = inputText,
                    onValueChange = { inputText = it },
                    onSend = {
                        if (inputText.isNotBlank() && isConnected) {
                            viewModel.sendMessage(inputText.trim())
                            inputText = ""
                        }
                    },
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onMicClick = {
                        if (isRecording) {
                            audioRecorder.stopRecording()
                        } else {
                            scope.launch {
                                isRecording = true
                                try {
                                    audioRecorder.startRecording()
                                    isRecording = false
                                    val file = audioRecorder.stopRecording()
                                    if (file != null && file.exists() && whisperClient != null) {
                                        isTranscribing = true
                                        try {
                                            val text = whisperClient.transcribe(file)
                                            if (text.isNotBlank() && isConnected) {
                                                viewModel.sendMessage(text.trim())
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Transcription failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                        } finally {
                                            isTranscribing = false
                                            file.delete()
                                        }
                                    }
                                } catch (e: Exception) {
                                    isRecording = false
                                    Toast.makeText(context, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    isRecording = isRecording,
                    isTranscribing = isTranscribing,
                    enabled = isConnected && !isLoading
                )
            }
        ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                ChatBubble(message, developerMode)
            }
            if (isLoading && (messages.isEmpty() || messages.last().isUser)) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage, developerMode: Boolean) {
    val mdColors = markdownColor(
        text = Color.White,
        codeText = Color(0xFFE0E0E0),
        codeBackground = Color(0xFF1E1E1E),
        dividerColor = Color(0xFF444444)
    )
    val mdTypography = markdownTypography(
        h1 = MaterialTheme.typography.headlineLarge.copy(color = Color.White),
        h2 = MaterialTheme.typography.headlineMedium.copy(color = Color.White),
        h3 = MaterialTheme.typography.headlineSmall.copy(color = Color.White),
        h4 = MaterialTheme.typography.titleLarge.copy(color = Color.White),
        h5 = MaterialTheme.typography.titleMedium.copy(color = Color.White),
        h6 = MaterialTheme.typography.titleSmall.copy(color = Color.White),
        text = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
        code = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFE0E0E0)),
        quote = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFFB0B0B0)),
        paragraph = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
        ordered = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
        bullet = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
        list = MaterialTheme.typography.bodyLarge.copy(color = Color.White)
    )

    when (val content = message.content) {
        is MessageContent.Text -> {
            if (message.isUser) {
                // User messages: bubble aligned right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.widthIn(max = 280.dp)
                    ) {
                        Text(
                            text = content.content,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } else {
                // Assistant messages: full width markdown with syntax highlighting
                Markdown(
                    content = content.content,
                    colors = mdColors,
                    typography = mdTypography,
                    modifier = Modifier.fillMaxWidth(),
                    components = markdownComponents(
                        codeFence = { model ->
                            // Extract language from code fence (```lang\ncode```)
                            val language = model.node.children.firstOrNull()
                                ?.let { child -> model.content.substring(child.startOffset, child.endOffset).trim() }
                                ?.takeIf { it.isNotEmpty() && !it.contains('\n') }
                            val codeContent = model.content.lines()
                                .drop(1).dropLast(1).joinToString("\n")
                            HighlightedCodeBlock(code = codeContent, language = language)
                        }
                    )
                )
            }
        }
        is MessageContent.Error -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF2E1A1A),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFCF6679), RoundedCornerShape(8.dp))
            ) {
                Text(
                    text = content.message,
                    color = Color(0xFFCF6679),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        is MessageContent.ToolUse -> {
            if (developerMode) {
                ToolUseBubble(content = content, developerMode = developerMode)
            }
        }
    }
}

/**
 * Extract a short human-readable summary from tool input JSON.
 * e.g. shell → "git diff", grep → "pattern in *.kt", read_file → "path/to/file"
 */
private fun toolInputSummary(name: String, input: String?): String {
    if (input == null) return ""
    return try {
        val json = org.json.JSONObject(input)
        when (name) {
            "shell" -> json.optString("command", "").let { cmd ->
                if (cmd.length > 60) cmd.take(60) + "…" else cmd
            }
            "grep" -> {
                val pattern = json.optString("pattern", "")
                val include = json.optString("include", "")
                listOfNotNull(
                    pattern.takeIf { it.isNotEmpty() },
                    include.takeIf { it.isNotEmpty() }
                ).joinToString(" in ")
            }
            "read_file" -> json.optString("path", "")
            "write_file" -> json.optString("path", "")
            "str_replace" -> json.optString("path", "")
            "glob" -> json.optString("pattern", "")
            "web_search" -> json.optString("query", "")
            "web_fetch" -> json.optString("url", "").let { url ->
                if (url.length > 60) url.take(60) + "…" else url
            }
            "spawn" -> json.optString("prompt", "").let { p ->
                if (p.length > 60) p.take(60) + "…" else p
            }
            "think" -> "…"
            "todo" -> json.optString("action", "")
            else -> input.take(60).let { if (input.length > 60) "$it…" else it }
        }
    } catch (e: Exception) {
        input.take(60).let { if (input.length > 60) "$it…" else it }
    }
}

@Composable
fun ToolUseBubble(content: MessageContent.ToolUse, developerMode: Boolean) {
    var expanded by remember { mutableStateOf(false) }

    val isComplete = content.output != null
    val isError = content.isError
    val isRunning = !isComplete

    // Colors based on state
    val bgColor = when {
        isRunning -> Color(0xFF1A1A2E)  // blue-ish while running
        isError -> Color(0xFF2E1A1A)     // red-ish on error
        else -> Color(0xFF1A2E1A)        // green-ish on success
    }
    val borderColor = when {
        isRunning -> Color(0xFF64B5F6)
        isError -> Color(0xFFCF6679)
        else -> Color(0xFF81C784)
    }
    val accentColor = borderColor

    val summary = remember(content.name, content.input) {
        toolInputSummary(content.name, content.input)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = isComplete) { expanded = !expanded }
            .animateContentSize()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Compact header line: ⚙ tool_name · summary
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = accentColor
                    )
                } else {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = content.name,
                    color = accentColor,
                    style = MaterialTheme.typography.labelMedium
                )
                if (summary.isNotEmpty()) {
                    Text(
                        text = "  ·  ",
                        color = Color(0xFF666666),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = summary,
                        color = Color(0xFFAAAAAA),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }

            // Expanded content
            if (expanded && isComplete) {
                Spacer(modifier = Modifier.height(8.dp))
                // Show input in developer mode
                if (developerMode && content.input != null) {
                    Text(
                        text = "Input:",
                        color = Color(0xFF888888),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF111111),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp, bottom = 6.dp)
                    ) {
                        Text(
                            text = content.input.take(1000) + if (content.input.length > 1000) "\n…" else "",
                            color = Color(0xFF999999),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(8.dp)
                        )
                    }
                }
                // Always show output when expanded
                content.output?.let { output ->
                    if (developerMode && content.input != null) {
                        Text(
                            text = "Output:",
                            color = Color(0xFF888888),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF111111),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                    ) {
                        Text(
                            text = output.take(3000) + if (output.length > 3000) "\n…" else "",
                            color = Color(0xFFCCCCCC),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onMenuClick: () -> Unit,
    onMicClick: () -> Unit,
    isRecording: Boolean,
    isTranscribing: Boolean,
    enabled: Boolean = true
) {
    Surface(
        color = Color.Black,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        when {
                            isRecording -> "Recording..."
                            isTranscribing -> "Transcribing..."
                            else -> "Message"
                        }
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                singleLine = true,
                enabled = enabled && !isRecording && !isTranscribing,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1A1A1A),
                    unfocusedContainerColor = Color(0xFF1A1A1A),
                    disabledContainerColor = Color(0xFF1A1A1A),
                    focusedBorderColor = if (isRecording) Color(0xFFE57373) else MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = if (isRecording) Color(0xFFE57373) else Color(0xFF333333),
                    disabledBorderColor = if (isRecording) Color(0xFFE57373) else Color(0xFF333333),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    disabledTextColor = Color.Gray,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledIconButton(
                onClick = onMicClick,
                enabled = enabled && !isTranscribing,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isRecording) Color(0xFFE57373) else MaterialTheme.colorScheme.primary
                )
            ) {
                if (isTranscribing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Icon(
                        if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isRecording) "Stop" else "Dictate"
                    )
                }
            }
        }
    }
}

// Syntax highlighting colors (Atom One Dark theme)
private val keywordColor = Color(0xFFC678DD)    // purple
private val stringColor = Color(0xFF98C379)     // green
private val commentColor = Color(0xFF5C6370)    // gray
private val numberColor = Color(0xFFD19A66)     // orange
private val functionColor = Color(0xFF61AFEF)   // blue
private val typeColor = Color(0xFFE5C07B)       // yellow

private fun highlightCode(code: String, language: String?): AnnotatedString {
    val keywords = when (language?.lowercase()) {
        "kotlin", "kt" -> setOf("fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "return", "import", "package", "private", "public", "internal", "protected", "override", "open", "abstract", "sealed", "data", "suspend", "inline", "companion", "const", "lateinit", "by", "lazy", "null", "true", "false", "is", "as", "in", "out", "try", "catch", "finally", "throw")
        "java" -> setOf("class", "interface", "public", "private", "protected", "static", "final", "void", "int", "long", "double", "float", "boolean", "char", "byte", "short", "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "return", "new", "this", "super", "extends", "implements", "import", "package", "try", "catch", "finally", "throw", "throws", "null", "true", "false")
        "python", "py" -> setOf("def", "class", "if", "elif", "else", "for", "while", "return", "import", "from", "as", "try", "except", "finally", "raise", "with", "lambda", "yield", "pass", "break", "continue", "and", "or", "not", "in", "is", "None", "True", "False", "self", "async", "await")
        "javascript", "js", "typescript", "ts" -> setOf("function", "const", "let", "var", "if", "else", "for", "while", "return", "import", "export", "from", "class", "extends", "new", "this", "super", "try", "catch", "finally", "throw", "async", "await", "null", "undefined", "true", "false", "typeof", "instanceof", "interface", "type", "enum")
        "rust", "rs" -> setOf("fn", "let", "mut", "const", "if", "else", "match", "for", "while", "loop", "return", "use", "mod", "pub", "struct", "enum", "impl", "trait", "self", "Self", "true", "false", "Some", "None", "Ok", "Err", "async", "await", "move", "unsafe", "where")
        "go", "golang" -> setOf("func", "var", "const", "if", "else", "for", "range", "return", "import", "package", "type", "struct", "interface", "map", "chan", "go", "defer", "select", "case", "switch", "break", "continue", "nil", "true", "false", "make", "new", "append", "len", "cap")
        "shell", "bash", "sh", "zsh" -> setOf("if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function", "return", "exit", "export", "local", "readonly", "declare", "echo", "cd", "ls", "cat", "grep", "sed", "awk", "true", "false")
        "c", "cpp", "c++" -> setOf("if", "else", "for", "while", "do", "switch", "case", "break", "continue", "return", "int", "long", "double", "float", "char", "void", "struct", "class", "public", "private", "protected", "static", "const", "virtual", "override", "nullptr", "true", "false", "new", "delete", "include", "define", "ifdef", "endif", "template", "typename", "namespace", "using", "auto")
        else -> setOf("if", "else", "for", "while", "return", "function", "class", "true", "false", "null", "import", "export")
    }

    val types = setOf("String", "Int", "Long", "Double", "Float", "Boolean", "List", "Map", "Set", "Array", "Unit", "Any", "Nothing", "Object", "void", "int", "long", "double", "float", "boolean", "char", "byte", "short", "str", "dict", "list", "tuple", "bool", "number", "string", "Vec", "Option", "Result", "Box")

    return buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            when {
                // Comments
                code.startsWith("//", i) -> {
                    val end = code.indexOf('\n', i).let { if (it == -1) code.length else it }
                    withStyle(SpanStyle(color = commentColor)) { append(code.substring(i, end)) }
                    i = end
                }
                code.startsWith("#", i) && (i == 0 || code[i-1] == '\n' || code[i-1] == ' ') -> {
                    val end = code.indexOf('\n', i).let { if (it == -1) code.length else it }
                    withStyle(SpanStyle(color = commentColor)) { append(code.substring(i, end)) }
                    i = end
                }
                // Strings
                code[i] == '"' -> {
                    val end = findStringEnd(code, i + 1, '"')
                    withStyle(SpanStyle(color = stringColor)) { append(code.substring(i, end)) }
                    i = end
                }
                code[i] == '\'' -> {
                    val end = findStringEnd(code, i + 1, '\'')
                    withStyle(SpanStyle(color = stringColor)) { append(code.substring(i, end)) }
                    i = end
                }
                // Numbers
                code[i].isDigit() -> {
                    val end = findWordEnd(code, i)
                    withStyle(SpanStyle(color = numberColor)) { append(code.substring(i, end)) }
                    i = end
                }
                // Words (keywords, types, identifiers)
                code[i].isLetter() || code[i] == '_' -> {
                    val end = findWordEnd(code, i)
                    val word = code.substring(i, end)
                    when {
                        word in keywords -> withStyle(SpanStyle(color = keywordColor)) { append(word) }
                        word in types || word[0].isUpperCase() -> withStyle(SpanStyle(color = typeColor)) { append(word) }
                        i + word.length < code.length && code[i + word.length] == '(' -> withStyle(SpanStyle(color = functionColor)) { append(word) }
                        else -> append(word)
                    }
                    i = end
                }
                else -> {
                    append(code[i])
                    i++
                }
            }
        }
    }
}

private fun findStringEnd(code: String, start: Int, quote: Char): Int {
    var i = start
    while (i < code.length) {
        if (code[i] == quote && (i == start || code[i-1] != '\\')) return i + 1
        if (code[i] == '\n') return i
        i++
    }
    return code.length
}

private fun findWordEnd(code: String, start: Int): Int {
    var i = start
    while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '_')) i++
    return i
}

@Composable
fun HighlightedCodeBlock(code: String, language: String?) {
    val annotatedCode = remember(code, language) { highlightCode(code, language) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1E1E1E),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            if (language != null) {
                Text(
                    text = language,
                    color = Color(0xFF808080),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            Text(
                text = annotatedCode,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFE0E0E0),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onRequestAssistantRole: () -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE) }

    var ghostUrl by remember { mutableStateOf(prefs.getString("server_url", "") ?: "") }
    var whisperUrl by remember { mutableStateOf(prefs.getString("whisper_url", "") ?: "") }
    var workDirBase by remember { mutableStateOf(prefs.getString("work_dir_base", "/home/louis/projects") ?: "/home/louis/projects") }
    var updateSource by remember { mutableStateOf(prefs.getString("update_source", "louis@mini:~/projects/android-utils/agent/app/build/outputs/apk/debug/app-debug.apk") ?: "") }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Text("Connection", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            item {
                OutlinedTextField(
                    value = ghostUrl,
                    onValueChange = { ghostUrl = it },
                    label = { Text("Ghost Server URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color(0xFF333333)
                    )
                )
            }
            item {
                OutlinedTextField(
                    value = whisperUrl,
                    onValueChange = { whisperUrl = it },
                    label = { Text("Whisper Server URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color(0xFF333333)
                    )
                )
            }
            item {
                OutlinedTextField(
                    value = workDirBase,
                    onValueChange = { workDirBase = it },
                    label = { Text("Work Directory Base Path") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color(0xFF333333)
                    )
                )
            }
            item {
                Button(
                    onClick = {
                        prefs.edit()
                            .putString("server_url", ghostUrl)
                            .putString("whisper_url", whisperUrl)
                            .putString("work_dir_base", workDirBase)
                            .apply()
                        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Settings")
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                Text("System", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            item {
                Button(
                    onClick = onRequestAssistantRole,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Request Assistant Role")
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                Text("Updates", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            item {
                OutlinedTextField(
                    value = updateSource,
                    onValueChange = { updateSource = it },
                    label = { Text("Update Source Path") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color(0xFF333333)
                    )
                )
            }
            item {
                Button(
                    onClick = {
                        prefs.edit().putString("update_source", updateSource).apply()
                        val intent = Intent().apply {
                            setClassName("com.termux", "com.termux.app.RunCommandService")
                            action = "com.termux.RUN_COMMAND"
                            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "scp $updateSource ~/app-debug.apk && termux-open ~/app-debug.apk"))
                            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                        }
                        try {
                            context.startService(intent)
                            Toast.makeText(context, "Updating...", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Update App")
                }
            }
        }
    }
}

@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    val dynamicColors = dynamicDarkColorScheme(LocalContext.current)
    val colorScheme = dynamicColors.copy(
        background = Color.Black,
        surface = Color.Black
    )
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
