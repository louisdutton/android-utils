package digital.dutton.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class GhostEvent {
    data class Text(val content: String) : GhostEvent()
    data class ToolCall(val name: String, val input: String?) : GhostEvent()
    data class ToolResult(val name: String, val output: String?) : GhostEvent()
    data class TurnEnd(val reason: String?) : GhostEvent()
    data class Error(val message: String) : GhostEvent()
    data object TurnBegin : GhostEvent()
    data object ConnectionClosed : GhostEvent()
}

data class GhostSession(
    val id: String,
    val workDir: String,
    val status: String,
    val createdAt: Long
)

class GhostClient(baseUrl: String = "http://localhost:3000") {
    private val baseUrl = baseUrl.trimEnd('/')
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for SSE
        .build()

    private var sessionId: String? = null
    private var eventSource: EventSource? = null

    suspend fun listSessions(): List<GhostSession> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/instances")
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Failed to list sessions: ${response.code}")
        }

        val body = response.body?.string() ?: throw IOException("Empty response")
        val result = JSONObject(body)
        val sessions = result.getJSONArray("instances")
        (0 until sessions.length()).map { i ->
            val obj = sessions.getJSONObject(i)
            GhostSession(
                id = obj.getString("id"),
                workDir = obj.getString("work_dir"),
                status = obj.getString("status"),
                createdAt = obj.getLong("created_at")
            )
        }
    }

    suspend fun createSession(workDir: String? = null): String = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            workDir?.let { put("work_dir", it) }
        }

        val request = Request.Builder()
            .url("$baseUrl/instances")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Failed to create session: ${response.code}")
        }

        val body = response.body?.string() ?: throw IOException("Empty response")
        val result = JSONObject(body)
        val id = result.getString("id")
        sessionId = id
        id
    }

    fun connectToSession(id: String) {
        sessionId = id
    }

    fun currentSessionId(): String? = sessionId

    suspend fun sendMessage(content: String): Unit = withContext(Dispatchers.IO) {
        val id = sessionId ?: throw IllegalStateException("No session created")

        val json = JSONObject().apply {
            put("content", content)
        }

        val request = Request.Builder()
            .url("$baseUrl/instances/$id/message")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Failed to send message: ${response.code}")
        }
    }

    fun streamEvents(): Flow<GhostEvent> = callbackFlow {
        val id = sessionId ?: throw IllegalStateException("No session created")

        val request = Request.Builder()
            .url("$baseUrl/instances/$id/stream")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                // Connection established
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val json = JSONObject(data)
                    val eventType = json.optString("type").ifEmpty { json.optString("event") }
                    val event = when (eventType) {
                        "TextPart", "text" -> GhostEvent.Text(json.optString("text", json.optString("content", "")))
                        "TurnBegin", "turn_begin" -> GhostEvent.TurnBegin
                        "TurnEnd", "turn_end" -> GhostEvent.TurnEnd(null)
                        "ToolCall" -> {
                            // Format: {"type":"ToolCall","function":{"name":"shell","arguments":"{...}"},"id":"..."}
                            val func = json.optJSONObject("function")
                            GhostEvent.ToolCall(
                                name = func?.optString("name") ?: "unknown",
                                input = func?.optString("arguments")
                            )
                        }
                        "ToolResult" -> {
                            // Format: {"type":"ToolResult","toolName":"shell","content":"...","toolCallId":"..."}
                            GhostEvent.ToolResult(
                                name = json.optString("toolName", "unknown"),
                                output = json.optString("content", null)
                            )
                        }
                        "error" -> GhostEvent.Error(json.optString("message", "Unknown error"))
                        "StepBegin", "StepEnd", "StatusUpdate" -> null // Ignore step/status events
                        else -> {
                            android.util.Log.d("GhostClient", "Unknown event: $eventType")
                            null
                        }
                    }
                    event?.let { trySend(it) }
                } catch (e: Exception) {
                    trySend(GhostEvent.Error("Parse error: ${e.message}"))
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // Only report unexpected errors, not normal closures
                val isNormalClose = when (t) {
                    null -> true
                    is java.io.EOFException -> true
                    is java.io.InterruptedIOException -> true
                    is java.net.SocketException -> true
                    is java.io.IOException -> t.message?.contains("canceled", ignoreCase = true) == true
                    else -> false
                }
                if (!isNormalClose) {
                    trySend(GhostEvent.Error(t?.message ?: "Connection failed"))
                }
                close(t)
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(GhostEvent.ConnectionClosed)
                close()
            }
        }

        eventSource = EventSources.createFactory(client).newEventSource(request, listener)

        awaitClose {
            eventSource?.cancel()
            eventSource = null
        }
    }

    suspend fun deleteSession(id: String? = null): Unit = withContext(Dispatchers.IO) {
        val targetId = id ?: sessionId ?: return@withContext

        val request = Request.Builder()
            .url("$baseUrl/instances/$targetId")
            .delete()
            .build()

        client.newCall(request).execute()
        if (targetId == sessionId) {
            sessionId = null
        }
    }

    fun close() {
        eventSource?.cancel()
        eventSource = null
    }
}
