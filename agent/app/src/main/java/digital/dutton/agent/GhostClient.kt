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
    data class TurnEnd(val reason: String?) : GhostEvent()
    data class Error(val message: String) : GhostEvent()
    data object TurnBegin : GhostEvent()
}

class GhostClient(baseUrl: String = "http://localhost:3000") {
    private val baseUrl = baseUrl.trimEnd('/')
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for SSE
        .build()

    private var instanceId: String? = null
    private var eventSource: EventSource? = null

    suspend fun createInstance(workDir: String? = null): String = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            workDir?.let { put("work_dir", it) }
        }

        val request = Request.Builder()
            .url("$baseUrl/instances")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Failed to create instance: ${response.code}")
        }

        val body = response.body?.string() ?: throw IOException("Empty response")
        val result = JSONObject(body)
        val id = result.getString("id")
        instanceId = id
        id
    }

    suspend fun sendMessage(content: String): Unit = withContext(Dispatchers.IO) {
        val id = instanceId ?: throw IllegalStateException("No instance created")

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
        val id = instanceId ?: throw IllegalStateException("No instance created")

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
                    val event = when (json.optString("type")) {
                        "TextPart" -> GhostEvent.Text(json.optString("text", ""))
                        "TurnBegin" -> GhostEvent.TurnBegin
                        "TurnEnd" -> GhostEvent.TurnEnd(null)
                        "error" -> GhostEvent.Error(json.optString("message", "Unknown error"))
                        else -> null
                    }
                    event?.let { trySend(it) }
                } catch (e: Exception) {
                    trySend(GhostEvent.Error("Parse error: ${e.message}"))
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                trySend(GhostEvent.Error(t?.message ?: "Connection failed"))
                close(t)
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        eventSource = EventSources.createFactory(client).newEventSource(request, listener)

        awaitClose {
            eventSource?.cancel()
            eventSource = null
        }
    }

    suspend fun deleteInstance(): Unit = withContext(Dispatchers.IO) {
        val id = instanceId ?: return@withContext

        val request = Request.Builder()
            .url("$baseUrl/instances/$id")
            .delete()
            .build()

        client.newCall(request).execute()
        instanceId = null
    }

    fun close() {
        eventSource?.cancel()
        eventSource = null
    }
}
