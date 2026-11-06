package dev.bubbleclaw

import org.json.JSONArray
import org.json.JSONObject

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

data class TurnResult(
    val content: String,
    val toolCalls: List<ToolCall>,
    val finishReason: String?
)

/** A message as rendered in the panel and converted to the gateway wire format. */
data class ChatMessage(
    val role: String,
    val text: String,
    val imageDataUrl: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val isError: Boolean = false,
    val isToolActivity: Boolean = false
) {
    fun toWireJson(): JSONObject {
        val json = JSONObject()
        json.put("role", role)
        if (isToolActivity) return json
        when {
            role == "tool" -> {
                json.put("tool_call_id", toolCallId ?: "")
                json.put("content", text)
            }
            toolCalls.isNotEmpty() -> {
                json.put("content", JSONObject.NULL)
                val array = JSONArray()
                for (call in toolCalls) {
                    array.put(
                        JSONObject()
                            .put("id", call.id)
                            .put("type", "function")
                            .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))
                    )
                }
                json.put("tool_calls", array)
            }
            imageDataUrl != null -> {
                val parts = JSONArray()
                    .put(JSONObject().put("type", "text").put("text", text))
                    .put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", imageDataUrl))
                    )
                json.put("content", parts)
            }
            else -> json.put("content", text)
        }
        return json
    }

    companion object {
        fun system(text: String) = ChatMessage(role = "system", text = text)
        fun user(text: String, image: String? = null) = ChatMessage(role = "user", text = text, imageDataUrl = image)
        fun assistant(text: String, calls: List<ToolCall> = emptyList()) =
            ChatMessage(role = "assistant", text = text, toolCalls = calls)
        fun tool(id: String, text: String) = ChatMessage(role = "tool", text = text, toolCallId = id)
    }
}

class ApiException(message: String) : Exception(message)
