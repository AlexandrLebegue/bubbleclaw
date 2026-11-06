package dev.bubbleclaw

import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.lifecycle.ViewModel
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Owns the conversation and the network turn loop so that rotation never loses messages.
 * All network work runs on a single background executor; UI state is published on main.
 */
class ChatViewModel : ViewModel() {

    interface Listener {
        fun onChanged()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val lock = Any()
    private val messageList = ArrayList<ChatMessage>()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var contextInfo: ScreenContextInfo? = null

    @Volatile
    var streamingText: String = ""
        private set

    @Volatile
    var isStreaming: Boolean = false
        private set

    @Volatile
    var status: String = "En ligne"
        private set

    @Volatile
    private var lastQuestion: String? = null

    private var cachedImageDataUrl: String? = null
    private var imageResolved = false

    fun attach(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun detach(listener: Listener) {
        listeners.remove(listener)
    }

    fun setContext(info: ScreenContextInfo?) {
        if (info != null) {
            contextInfo = info
            notifyChanged()
        }
    }

    fun context(): ScreenContextInfo? = contextInfo

    fun messages(): List<ChatMessage> = synchronized(lock) { messageList.toList() }

    fun send(question: String) {
        val clean = question.trim()
        if (clean.isEmpty() || isStreaming) return
        lastQuestion = clean
        addMessage(ChatMessage.user(clean))
        startTurn()
    }

    fun retry() {
        if (isStreaming) return
        synchronized(lock) { messageList.removeAll { it.isError } }
        if (lastQuestion == null) return
        startTurn()
    }

    private fun startTurn() {
        isStreaming = true
        streamingText = ""
        status = "En ligne"
        BubbleService.setState(BubbleState.Thinking)
        notifyChanged()
        executor.execute { runTurn() }
    }

    private fun runTurn() {
        var iteration = 0
        try {
            while (true) {
                val tools = if (AppPrefs.controlMode && ScreenReaderService.instance != null) {
                    ControlTools.schema()
                } else {
                    null
                }
                val result = ApiClient.fromPrefs().send(buildWire(), tools) { piece ->
                    main.post {
                        streamingText += piece
                        notifyChanged()
                    }
                }

                if (result.toolCalls.isNotEmpty() && tools != null) {
                    if (iteration >= 6) {
                        addMessage(
                            ChatMessage.assistant(
                                "Trop d'actions enchaînées, je m'arrête là. Reformule ou relance."
                            )
                        )
                        finish(false)
                        return
                    }
                    addMessage(ChatMessage.assistant(result.content, result.toolCalls))
                    for (call in result.toolCalls) {
                        val outcome = ControlTools.execute(call, contextInfo)
                        addMessage(
                            ChatMessage(
                                role = "tool",
                                text = outcome.text,
                                toolCallId = call.id,
                                isToolActivity = true
                            )
                        )
                        if (outcome.imageDataUrl != null) {
                            addMessage(
                                ChatMessage(
                                    role = "user",
                                    text = "[Capture actualisée après action]",
                                    imageDataUrl = outcome.imageDataUrl
                                )
                            )
                        }
                    }
                    main.post {
                        streamingText = ""
                        notifyChanged()
                    }
                    iteration++
                    continue
                }

                val finalText = result.content.ifBlank { streamingText }
                addMessage(ChatMessage.assistant(finalText.ifBlank { "…" }))
                finish(true)
                return
            }
        } catch (e: ApiException) {
            fail(e.message ?: ApiClient.UNREACHABLE)
        } catch (e: Exception) {
            fail(e.message?.let { "Erreur : $it" } ?: ApiClient.UNREACHABLE)
        }
    }

    private fun finish(success: Boolean) {
        main.post {
            streamingText = ""
            isStreaming = false
            if (success) {
                status = "En ligne"
                BubbleService.setState(BubbleState.AnswerReady)
            } else {
                BubbleService.setState(BubbleState.Idle)
            }
            notifyChanged()
        }
    }

    private fun fail(message: String) {
        addMessage(ChatMessage(role = "assistant", text = message, isError = true))
        main.post {
            streamingText = ""
            isStreaming = false
            status = "Injoignable"
            BubbleService.setState(BubbleState.Idle)
            notifyChanged()
        }
    }

    private fun addMessage(message: ChatMessage) {
        synchronized(lock) { messageList.add(message) }
        main.post { notifyChanged() }
    }

    private fun notifyChanged() {
        for (listener in listeners) listener.onChanged()
    }

    private fun buildWire(): JSONArray {
        val array = JSONArray()
        var firstUser = true
        for (message in messages()) {
            if (message.isError) continue
            if (message.role == "user") {
                if (firstUser) {
                    val header = buildContextHeader()
                    val text = if (header.isNullOrBlank()) message.text else "$header\n\n${message.text}"
                    array.put(ChatMessage.user(text, imageDataUrl()).toWireJson())
                    firstUser = false
                } else {
                    array.put(message.toWireJson())
                }
            } else {
                array.put(message.toWireJson())
            }
        }
        return array
    }

    private fun buildContextHeader(): String? {
        val info = contextInfo ?: return null
        val builder = StringBuilder(info.promptHeader())
        if (info.width > 0 && info.height > 0) {
            builder.append("\nCapture : ").append(info.width).append(" × ").append(info.height).append(" px")
        }
        if (!info.visibleText.isNullOrBlank()) {
            builder.append("\nTexte visible : ").append(info.visibleText)
        }
        return builder.toString()
    }

    private fun imageDataUrl(): String? {
        synchronized(this) {
            if (imageResolved) return cachedImageDataUrl
            imageResolved = true
            val path = contextInfo?.imagePath ?: return null
            cachedImageDataUrl = runCatching {
                val bytes = File(path).readBytes()
                "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }.getOrNull()
            return cachedImageDataUrl
        }
    }

    override fun onCleared() {
        executor.shutdownNow()
        listeners.clear()
        super.onCleared()
    }
}
