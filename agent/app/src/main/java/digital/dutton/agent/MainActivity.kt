package digital.dutton.agent

import android.app.role.RoleManager
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String,
    val isUser: Boolean
)

class ChatViewModel : ViewModel() {
    private var client: GhostClient? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var currentAssistantMessageId: String? = null

    fun connect(serverUrl: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val ghostClient = GhostClient(serverUrl)
                client = ghostClient
                ghostClient.createInstance()
                _isConnected.value = true
                _isLoading.value = false

                // Start listening to events in separate coroutine
                launch {
                    try {
                        ghostClient.streamEvents().collect { event ->
                            when (event) {
                                is GhostEvent.TurnBegin -> {
                                    val msg = ChatMessage(content = "", isUser = false)
                                    currentAssistantMessageId = msg.id
                                    _messages.value = _messages.value + msg
                                }
                                is GhostEvent.Text -> {
                                    currentAssistantMessageId?.let { id ->
                                        _messages.value = _messages.value.map { msg ->
                                            if (msg.id == id) msg.copy(content = msg.content + event.content)
                                            else msg
                                        }
                                    }
                                }
                                is GhostEvent.TurnEnd -> {
                                    currentAssistantMessageId = null
                                    _isLoading.value = false
                                }
                                is GhostEvent.Error -> {
                                    _messages.value = _messages.value + ChatMessage(
                                        content = "Error: ${event.message}",
                                        isUser = false
                                    )
                                    _isLoading.value = false
                                }
                            }
                        }
                    } catch (e: Exception) {
                        _messages.value = _messages.value + ChatMessage(
                            content = "Stream error: ${e.message}",
                            isUser = false
                        )
                    }
                }
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(
                    content = "Connection failed: ${e.message}",
                    isUser = false
                )
                _isConnected.value = false
                _isLoading.value = false
            }
        }
    }

    fun sendMessage(content: String) {
        val c = client ?: return
        if (content.isBlank()) return

        viewModelScope.launch {
            _messages.value = _messages.value + ChatMessage(content = content, isUser = true)
            _isLoading.value = true
            try {
                c.sendMessage(content)
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(
                    content = "Failed to send: ${e.message}",
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

    var inputText by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("http://10.0.2.2:3000") } // Default for emulator
    var showServerDialog by remember { mutableStateOf(!isConnected) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

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
                    viewModel.connect(serverUrl)
                    showServerDialog = false
                }) {
                    Text("Connect")
                }
            }
        )
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Agent") },
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
                ChatBubble(message)
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

@Composable
fun ChatBubble(message: ChatMessage) {
    val bubbleColor = if (message.isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (message.isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.content,
                color = textColor,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
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
                placeholder = { Text("Message") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                singleLine = true,
                enabled = enabled,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1A1A1A),
                    unfocusedContainerColor = Color(0xFF1A1A1A),
                    disabledContainerColor = Color(0xFF1A1A1A),
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color(0xFF333333),
                    disabledBorderColor = Color(0xFF333333),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    disabledTextColor = Color.Gray,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = value.isNotBlank() && enabled
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
