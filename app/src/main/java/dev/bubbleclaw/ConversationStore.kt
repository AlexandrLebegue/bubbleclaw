package dev.bubbleclaw

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists chat threads as one JSON file per conversation in filesDir/conversations.
 * Images are kept as file paths inside a per-conversation cache directory, never as
 * base64 inside the JSON.
 */
object ConversationStore {

    private const val DIR_NAME = "conversations"
    private const val LEGACY_FILE = "conversation.json"
    private const val DEFAULT_TITLE = "Nouvelle conversation"
    private val lock = Any()

    data class ConversationMeta(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val sessionId: String
    )

    fun defaultTitle(): String = DEFAULT_TITLE

    fun directory(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun file(context: Context, id: String): File = File(directory(context), "$id.json")

    private fun imagesRoot(context: Context): File =
        File(context.cacheDir, "conversation").apply { mkdirs() }

    fun imageDirectory(context: Context, id: String): File =
        File(imagesRoot(context), id).apply { mkdirs() }

    fun list(context: Context): List<ConversationMeta> {
        synchronized(lock) {
            migrateLegacy(context)
            return scan(context)
        }
    }

    fun load(context: Context, id: String): List<ChatMessage> {
        synchronized(lock) {
            val target = file(context, id)
            if (!target.exists()) return emptyList()
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
            cleanupOrphans(context, id, messages)
            return messages
        }
    }

    fun save(context: Context, meta: ConversationMeta, messages: List<ChatMessage>) {
        synchronized(lock) {
            runCatching {
                val array = JSONArray()
                for (message in messages) array.put(toJson(message))
                val root = JSONObject()
                    .put("version", 2)
                    .put("id", meta.id)
                    .put("title", meta.title)
                    .put("sessionId", meta.sessionId)
                    .put("updatedAt", meta.updatedAt)
                    .put("messages", array)
                val target = file(context, meta.id)
                val temp = File(target.parentFile, "${target.name}.tmp")
                temp.writeText(root.toString())
                if (!temp.renameTo(target)) {
                    target.delete()
                    if (!temp.renameTo(target)) temp.delete()
                }
                cleanupOrphans(context, meta.id, messages)
            }
        }
    }

    fun create(context: Context, baseSessionId: String): ConversationMeta {
        val stamp = System.currentTimeMillis()
        val id = "c$stamp"
        val meta = ConversationMeta(
            id = id,
            title = DEFAULT_TITLE,
            updatedAt = stamp,
            sessionId = sessionFor(baseSessionId, id)
        )
        save(context, meta, emptyList())
        return meta
    }

    /** Empties a conversation and gives it a fresh gateway session. */
    fun reset(context: Context, id: String, baseSessionId: String): ConversationMeta? {
        val current = synchronized(lock) { metaOf(file(context, id)) } ?: return null
        val meta = current.copy(
            title = DEFAULT_TITLE,
            updatedAt = System.currentTimeMillis(),
            sessionId = sessionFor(baseSessionId, id)
        )
        save(context, meta, emptyList())
        runCatching { imageDirectory(context, id).deleteRecursively() }
        return meta
    }

    fun delete(context: Context, id: String) {
        synchronized(lock) {
            runCatching { file(context, id).delete() }
            runCatching { imageDirectory(context, id).deleteRecursively() }
        }
    }

    fun clearActive(context: Context, baseSessionId: String) {
        val id = AppPrefs.activeConversationId
        if (id.isNotBlank()) reset(context, id, baseSessionId)
    }

    fun clearAll(context: Context) {
        synchronized(lock) {
            runCatching { directory(context).listFiles()?.forEach { it.delete() } }
            runCatching { imagesRoot(context).deleteRecursively() }
            runCatching { File(context.filesDir, LEGACY_FILE).delete() }
        }
    }

    /** Copies a capture into the conversation cache so capture pruning cannot break history. */
    fun attachImage(context: Context, conversationId: String, sourcePath: String?): String? {
        if (sourcePath.isNullOrBlank()) return null
        val source = File(sourcePath)
        if (!source.exists()) return null
        return runCatching {
            val directory = imageDirectory(context, conversationId)
            val target = File(directory, "ctx_${System.currentTimeMillis()}.jpg")
            source.copyTo(target, overwrite = true)
            target.absolutePath
        }.getOrNull()
    }

    fun sessionFor(baseSessionId: String, conversationId: String): String =
        "$baseSessionId:${conversationId.takeLast(8)}"

    private fun scan(context: Context): List<ConversationMeta> =
        directory(context).listFiles { file -> file.isFile && file.extension == "json" }
            ?.mapNotNull { metaOf(it) }
            ?.sortedByDescending { it.updatedAt }
            .orEmpty()

    private fun metaOf(file: File): ConversationMeta? = runCatching {
        val root = JSONObject(file.readText())
        ConversationMeta(
            id = root.optString("id").ifBlank { file.nameWithoutExtension },
            title = root.optString("title").ifBlank { DEFAULT_TITLE },
            updatedAt = root.optLong("updatedAt", file.lastModified()),
            sessionId = root.optString("sessionId").ifBlank { AppPrefs.sessionId }
        )
    }.getOrNull()

    private fun migrateLegacy(context: Context) {
        val legacy = File(context.filesDir, LEGACY_FILE)
        if (!legacy.exists()) return
        runCatching {
            val root = JSONObject(legacy.readText())
            val array = root.optJSONArray("messages") ?: JSONArray()
            val messages = ArrayList<ChatMessage>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                messages.add(fromJson(item))
            }
            val id = "legacy"
            val meta = ConversationMeta(
                id = id,
                title = titleFor(messages),
                updatedAt = legacy.lastModified(),
                sessionId = AppPrefs.sessionId
            )
            save(context, meta, messages)
            legacy.delete()
            if (AppPrefs.activeConversationId.isBlank()) {
                AppPrefs.activeConversationId = id
            }
        }
    }

    fun titleFor(messages: List<ChatMessage>): String {
        val first = messages.firstOrNull {
            it.role == "user" && !it.isToolActivity && !it.isError && it.text.isNotBlank()
        }?.text ?: return DEFAULT_TITLE
        return first.trim().replace(Regex("\\s+"), " ").take(38).ifBlank { DEFAULT_TITLE }
    }

    private fun cleanupOrphans(context: Context, id: String, messages: List<ChatMessage>) {
        val referenced = messages.mapNotNull { it.imagePath }.toSet()
        runCatching {
            imageDirectory(context, id).listFiles()
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
