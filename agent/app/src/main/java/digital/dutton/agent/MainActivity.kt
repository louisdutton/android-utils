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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
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
import androidx.compose.foundation.border
import androidx.lifecycle.ViewModel
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI

sealed class MessageContent {
    data class Text(val content: String) : MessageContent()
    data class Error(val message: String) : MessageContent()
    data class ToolCall(val name: String, val input: String?) : MessageContent()
    data class ToolResult(val name: String, val output: String?) : MessageContent()
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: MessageContent,
    val isUser: Boolean
)

class ChatViewModel : ViewModel() {
    private var client: GhostClient? = null
    private var streamJob: kotlinx.coroutines.Job? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _instances = MutableStateFlow<List<GhostInstance>>(emptyList())
    val instances: StateFlow<List<GhostInstance>> = _instances.asStateFlow()

    private val _currentInstance = MutableStateFlow<GhostInstance?>(null)
    val currentInstance: StateFlow<GhostInstance?> = _currentInstance.asStateFlow()

    private var currentAssistantMessageId: String? = null
    private var currentTextContent = StringBuilder()

    fun connect(serverUrl: String, autoCreate: Boolean = true) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val ghostClient = GhostClient(serverUrl)
                client = ghostClient

                // Load existing instances
                refreshInstances()

                if (autoCreate) {
                    ghostClient.createInstance()
                    refreshInstances()
                    _currentInstance.value = _instances.value.find { it.id == ghostClient.currentInstanceId() }
                    startStreaming(ghostClient)
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

    fun refreshInstances() {
        viewModelScope.launch {
            try {
                client?.let { c ->
                    _instances.value = c.listInstances()
                }
            } catch (e: Exception) {
                // Ignore refresh errors
            }
        }
    }

    fun selectInstance(instance: GhostInstance) {
        viewModelScope.launch {
            try {
                val c = client ?: return@launch
                streamJob?.cancel()
                c.connectToInstance(instance.id)
                _currentInstance.value = instance
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

    fun createInstance(workDir: String?) {
        viewModelScope.launch {
            try {
                val c = client ?: return@launch
                _isLoading.value = true
                streamJob?.cancel()
                c.createInstance(workDir)
                refreshInstances()
                _currentInstance.value = _instances.value.find { it.id == c.currentInstanceId() }
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

    fun deleteInstance(instance: GhostInstance) {
        viewModelScope.launch {
            try {
                val c = client ?: return@launch
                c.deleteInstance(instance.id)
                refreshInstances()
                if (_currentInstance.value?.id == instance.id) {
                    streamJob?.cancel()
                    _currentInstance.value = null
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
                                content = MessageContent.ToolCall(event.name, event.input),
                                isUser = false
                            )
                        }
                        is GhostEvent.ToolResult -> {
                            _messages.value = _messages.value + ChatMessage(
                                content = MessageContent.ToolResult(event.name, event.output),
                                isUser = false
                            )
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
                ChatScreen(
                    onSettingsClick = { requestAssistantRole() }
                )
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
    val instances by viewModel.instances.collectAsState()
    val currentInstance by viewModel.currentInstance.collectAsState()

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE) }
    val savedUrl = remember { prefs.getString("server_url", null) }

    var inputText by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(savedUrl ?: "http://") }
    var showServerDialog by remember { mutableStateOf(savedUrl == null) }
    var showNewInstanceDialog by remember { mutableStateOf(false) }
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

    if (showNewInstanceDialog) {
        AlertDialog(
            onDismissRequest = { showNewInstanceDialog = false },
            title = { Text("New Instance") },
            text = {
                OutlinedTextField(
                    value = newWorkDir,
                    onValueChange = { newWorkDir = it },
                    label = { Text("Work Directory (optional)") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createInstance(newWorkDir.ifBlank { null })
                    newWorkDir = ""
                    showNewInstanceDialog = false
                    scope.launch { drawerState.close() }
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewInstanceDialog = false }) {
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
                Text(
                    "Instances",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                HorizontalDivider(color = Color(0xFF333333))

                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(instances, key = { it.id }) { instance ->
                        val isSelected = instance.id == currentInstance?.id
                        NavigationDrawerItem(
                            label = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            File(instance.workDir).name,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            instance.workDir,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.deleteInstance(instance) }
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
                                viewModel.selectInstance(instance)
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
                    label = { Text("New Instance") },
                    selected = false,
                    onClick = { showNewInstanceDialog = true },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent
                    )
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    label = { Text("Update App") },
                    selected = false,
                    onClick = {
                        val intent = Intent().apply {
                            setClassName("com.termux", "com.termux.app.RunCommandService")
                            action = "com.termux.RUN_COMMAND"
                            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "scp louis@mini:~/projects/android-utils/agent/app/build/outputs/apk/debug/app-debug.apk ~/app-debug.apk && termux-open ~/app-debug.apk"))
                            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                        }
                        try {
                            context.startService(intent)
                            Toast.makeText(context, "Updating...", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        scope.launch { drawerState.close() }
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent
                    )
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    label = { Text("Developer Mode") },
                    selected = developerMode,
                    onClick = {
                        developerMode = !developerMode
                        prefs.edit().putBoolean("developer_mode", developerMode).apply()
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        unselectedContainerColor = Color.Transparent
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    ) {
        Scaffold(
            containerColor = Color.Black,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                        }
                    },
                    title = {
                        Text(currentInstance?.let { File(it.workDir).name } ?: "Agent")
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black,
                        titleContentColor = Color.White
                    ),
                    actions = {
                        if (!isConnected) {
                            TextButton(onClick = { showServerDialog = true }) {
                                Text("Connect", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = Color.White
                            )
                        }
                    }
                )
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
                onMicClick = {
                    if (isRecording) {
                        // Stop recording - the coroutine will finish and handle transcription
                        audioRecorder.stopRecording()
                    } else {
                        // Start recording
                        scope.launch {
                            isRecording = true
                            try {
                                audioRecorder.startRecording()
                                // Recording finished (stopRecording was called)
                                isRecording = false
                                val file = audioRecorder.stopRecording()
                                if (file != null && file.exists() && whisperClient != null) {
                                    isTranscribing = true
                                    try {
                                        val text = whisperClient.transcribe(file)
                                        if (text.isNotBlank()) {
                                            inputText = text
                                        }
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Transcription failed: ${e.message}",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } finally {
                                        isTranscribing = false
                                        file.delete()
                                    }
                                }
                            } catch (e: Exception) {
                                isRecording = false
                                android.widget.Toast.makeText(
                                    context,
                                    "Recording failed: ${e.message}",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
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
                // Assistant messages: full width markdown
                Markdown(
                    content = content.content,
                    colors = mdColors,
                    typography = mdTypography,
                    modifier = Modifier.fillMaxWidth()
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
        is MessageContent.ToolCall -> {
            if (developerMode) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1A1A2E),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF64B5F6), RoundedCornerShape(8.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Tool: ${content.name}",
                            color = Color(0xFF64B5F6),
                            style = MaterialTheme.typography.labelMedium
                        )
                        content.input?.let { input ->
                            Text(
                                text = input.take(300) + if (input.length > 300) "..." else "",
                                color = Color(0xFFAAAAAA),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
        is MessageContent.ToolResult -> {
            if (developerMode) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1A2E1A),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF81C784), RoundedCornerShape(8.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Tool: ${content.name}",
                            color = Color(0xFF81C784),
                            style = MaterialTheme.typography.labelMedium
                        )
                        content.output?.let { output ->
                            Text(
                                text = output.take(300) + if (output.length > 300) "..." else "",
                                color = Color(0xFFAAAAAA),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
            Spacer(modifier = Modifier.width(8.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = value.isNotBlank() && enabled && !isRecording && !isTranscribing
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send"
                )
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
