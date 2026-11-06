package dev.bubbleclaw

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the chat thread as JSON in filesDir. Images are kept as file paths inside
 * cacheDir and never as base64 inside the JSON.
 */
object ConversationStore {

    private const val FILE_NAME = "conversation.json"
    private const val IMAGE_DIR = "conversation"

    private val lock = Any()

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun imageDirectory(context: Context): File =
        File(context.cacheDir, IMAGE_DIR).apply { mkdirs() }

    fun load(context: Context): List<ChatMessage> {
        val target = file(context)
        if (!target.exists()) return emptyList()
        return synchronized(lock) {
            val messages = runCatching {
                val root = JSONObject(target.readText())
                val array = root.optJSONArray("messages") ?: JSONArray()
                val result = ArrayList<ChatMessage>(array.length())
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    result.add(fromJson(item))
                }
                result
            }.getOrDefault(emptyList())
            cleanupOrphans(context, messages)
            messages
        }
    }

    fun save(context: Context, messages: List<ChatMessage>) {
        synchronized(lock) {
            runCatching {
                val array = JSONArray()
                for (message in messages) array.put(toJson(message))
                val root = JSONObject()
                    .put("version", 1)
                    .put("messages", array)
                val target = file(context)
                val temp = File(target.parentFile, "$FILE_NAME.tmp")
                temp.writeText(root.toString())
                if (!temp.renameTo(target)) {
                    target.delete()
                    if (!temp.renameTo(target)) temp.delete()
                }
                cleanupOrphans(context, messages)
            }
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            runCatching { file(context).delete() }
            runCatching {
                imageDirectory(context).listFiles()?.forEach { it.delete() }
            }
        }
    }

    /** Copies a capture into the conversation cache so capture pruning cannot break history. */
    fun attachImage(context: Context, sourcePath: String?): String? {
        if (sourcePath.isNullOrBlank()) return null
        val source = File(sourcePath)
        if (!source.exists()) return null
        return runCatching {
            val directory = imageDirectory(context)
            val target = File(directory, "ctx_${System.currentTimeMillis()}.jpg")
            source.copyTo(target, overwrite = true)
            target.absolutePath
        }.getOrNull()
    }

    private fun cleanupOrphans(context: Context, messages: List<ChatMessage>) {
        val referenced = messages.mapNotNull { it.imagePath }.toSet()
        runCatching {
            imageDirectory(context).listFiles()
                ?.filter { it.absolutePath !in referenced }
                ?.forEach { it.delete() }
        }
    }

    private fun toJson(message: ChatMessage): JSONObject {
        val json = JSONObject()
            .put("role", message.role)
            .put("text", message.text)
            .put("timestamp", message.timestamp)
            .put("error", message.isError)
            .put("toolActivity", message.isToolActivity)
        message.imagePath?.let { json.put("imagePath", it) }
        message.contextHeader?.let { json.put("contextHeader", it) }
        message.toolCallId?.let { json.put("toolCallId", it) }
        if (message.toolCalls.isNotEmpty()) {
            val calls = JSONArray()
            for (call in message.toolCalls) {
                calls.put(
                    JSONObject()
                        .put("id", call.id)
                        .put("name", call.name)
                        .put("arguments", call.arguments)
                )
            }
            json.put("toolCalls", calls)
        }
        return json
    }

    private fun fromJson(json: JSONObject): ChatMessage {
        val calls = ArrayList<ToolCall>()
        val rawCalls = json.optJSONArray("toolCalls")
        if (rawCalls != null) {
            for (i in 0 until rawCalls.length()) {
                val item = rawCalls.optJSONObject(i) ?: continue
                calls.add(
                    ToolCall(
                        id = item.optString("id", "call_$i"),
                        name = item.optString("name"),
                        arguments = item.optString("arguments", "{}")
                    )
                )
            }
        }
        return ChatMessage(
            role = json.optString("role", "assistant"),
            text = json.optString("text", ""),
            toolCalls = calls,
            toolCallId = json.optString("toolCallId").takeIf { it.isNotEmpty() },
            isError = json.optBoolean("error", false),
            isToolActivity = json.optBoolean("toolActivity", false),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            imagePath = json.optString("imagePath").takeIf { it.isNotEmpty() },
            contextHeader = json.optString("contextHeader").takeIf { it.isNotEmpty() }
        )
    }
}
