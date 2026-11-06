package dev.bubbleclaw

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Minimal OpenAI-compatible client built on HttpURLConnection and org.json.
 * Blocking calls: always invoke from a worker thread.
 */
class ApiClient(
    baseUrl: String,
    private val token: String,
    private val model: String,
    private val sessionId: String,
    private val streamEnabled: Boolean = true
) {
    private val root = baseUrl.trim().trimEnd('/')

    companion object {
        const val UNREACHABLE =
            "OpenClaw injoignable. Vérifie que le gateway écoute et que le téléphone est sur le même réseau (Tailscale ou Wi-Fi)."
        const val INVALID_TOKEN = "Jeton invalide."

        fun fromPrefs(): ApiClient = ApiClient(
            AppPrefs.baseUrl,
            AppPrefs.token,
            AppPrefs.model,
            AppPrefs.sessionId,
            AppPrefs.streamEnabled
        )
    }

    fun send(
        messages: JSONArray,
        tools: JSONArray?,
        onDelta: (String) -> Unit
    ): TurnResult {
        if (streamEnabled) {
            var emitted = false
            try {
                return streamCompletion(messages, tools) { piece ->
                    emitted = true
                    onDelta(piece)
                }
            } catch (e: ApiException) {
                if (!emitted) return completion(messages, tools)
                throw e
            }
        }
        return completion(messages, tools)
    }

    fun listModels(): List<String> {
        val connection = open("GET", "$root/models", accept = "application/json", timeoutMs = 12_000)
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw ApiException(httpError(code, readBody(connection.errorStream)))
            val body = readBody(connection.inputStream) ?: throw ApiException(UNREACHABLE)
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            val models = ArrayList<String>()
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotBlank()) models.add(id)
            }
            models
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(networkMessage(e))
        } finally {
            connection.disconnect()
        }
    }

    private fun streamCompletion(
        messages: JSONArray,
        tools: JSONArray?,
        onDelta: (String) -> Unit
    ): TurnResult {
        val connection = open("POST", "$root/chat/completions", accept = "text/event-stream")
        try {
            val payload = buildPayload(messages, tools, stream = true)
            write(connection, payload)
            val code = connection.responseCode
            if (code !in 200..299) throw ApiException(httpError(code, readBody(connection.errorStream)))

            val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
            val content = StringBuilder()
            val toolAccumulators = LinkedHashMap<Int, ToolAccumulator>()
            var finishReason: String? = null

            reader.useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || !line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") return@useLines
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    val delta = choice.optJSONObject("delta")
                    if (delta != null) {
                        val piece = delta.optString("content", "")
                        if (piece.isNotEmpty()) {
                            content.append(piece)
                            onDelta(piece)
                        }
                        val calls = delta.optJSONArray("tool_calls")
                        if (calls != null) collectToolCalls(calls, toolAccumulators)
                    }
                    val reason = choice.optString("finish_reason", "")
                    if (reason.isNotEmpty() && reason != "null") finishReason = reason
                }
            }

            val toolCalls = toolAccumulators.toToolCalls()
            return TurnResult(
                content = content.toString(),
                toolCalls = toolCalls,
                finishReason = finishReason ?: if (toolCalls.isNotEmpty()) "tool_calls" else null
            )
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(networkMessage(e))
        } finally {
            connection.disconnect()
        }
    }

    private fun completion(messages: JSONArray, tools: JSONArray?): TurnResult {
        val connection = open("POST", "$root/chat/completions", accept = "application/json")
        try {
            write(connection, buildPayload(messages, tools, stream = false))
            val code = connection.responseCode
            if (code !in 200..299) throw ApiException(httpError(code, readBody(connection.errorStream)))
            val body = readBody(connection.inputStream) ?: throw ApiException(UNREACHABLE)
            val choice = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)
                ?: throw ApiException("Réponse inattendue du gateway.")
            val message = choice.optJSONObject("message")
            val content = message?.optString("content", "").orEmpty()
            val calls = ArrayList<ToolCall>()
            val rawCalls = message?.optJSONArray("tool_calls")
            if (rawCalls != null) {
                for (i in 0 until rawCalls.length()) {
                    val item = rawCalls.optJSONObject(i) ?: continue
                    val fn = item.optJSONObject("function") ?: continue
                    calls.add(
                        ToolCall(
                            id = item.optString("id", "call_$i"),
                            name = fn.optString("name"),
                            arguments = fn.optString("arguments", "{}")
                        )
                    )
                }
            }
            val finish = choice.optString("finish_reason", "")
            return TurnResult(content, calls, finish.ifEmpty { if (calls.isNotEmpty()) "tool_calls" else null })
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(networkMessage(e))
        } finally {
            connection.disconnect()
        }
    }

    private fun buildPayload(messages: JSONArray, tools: JSONArray?, stream: Boolean): JSONObject {
        val payload = JSONObject()
            .put("model", model)
            .put("user", sessionId)
            .put("stream", stream)
            .put("messages", messages)
        if (tools != null && tools.length() > 0) payload.put("tools", tools)
        return payload
    }

    private fun collectToolCalls(calls: JSONArray, accumulators: MutableMap<Int, ToolAccumulator>) {
        for (i in 0 until calls.length()) {
            val item = calls.optJSONObject(i) ?: continue
            val index = item.optInt("index", i)
            val accumulator = accumulators.getOrPut(index) { ToolAccumulator() }
            item.optString("id", "").takeIf { it.isNotEmpty() }?.let { accumulator.id = it }
            val fn = item.optJSONObject("function") ?: continue
            fn.optString("name", "").takeIf { it.isNotEmpty() }?.let { accumulator.name = it }
            val args = fn.optString("arguments", "")
            if (args.isNotEmpty()) accumulator.arguments.append(args)
        }
    }

    private fun Map<Int, ToolAccumulator>.toToolCalls(): List<ToolCall> =
        entries.sortedBy { it.key }.mapNotNull { (index, acc) ->
            if (acc.name.isBlank()) null
            else ToolCall(
                id = acc.id.ifBlank { "call_$index" },
                name = acc.name,
                arguments = acc.arguments.toString().ifBlank { "{}" }
            )
        }

    private fun open(method: String, url: String, accept: String, timeoutMs: Int = 120_000): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 8_000
        connection.readTimeout = timeoutMs
        connection.doInput = true
        connection.doOutput = method == "POST"
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
        return connection
    }

    private fun write(connection: HttpURLConnection, payload: JSONObject) {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { out: OutputStream -> out.write(bytes) }
    }

    private fun readBody(stream: java.io.InputStream?): String? {
        if (stream == null) return null
        return runCatching { stream.bufferedReader().use { it.readText() } }.getOrNull()
    }

    private fun httpError(code: Int, body: String?): String {
        if (code == 401 || code == 403) return INVALID_TOKEN
        val snippet = body?.trim()?.take(240).orEmpty()
        return if (snippet.isEmpty()) "Erreur HTTP $code." else "Erreur HTTP $code : $snippet"
    }

    private fun networkMessage(e: Exception): String = when (e) {
        is SocketTimeoutException, is UnknownHostException, is ConnectException, is IOException -> UNREACHABLE
        else -> e.message?.let { "Erreur réseau : $it" } ?: UNREACHABLE
    }

    private class ToolAccumulator {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }
}
