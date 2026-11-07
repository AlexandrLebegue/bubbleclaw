package dev.bubbleclaw

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Owns the persisted conversation and the network turn loop so that rotation, closing
 * the panel and relaunching never lose messages. Network and disk work never run on main.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    interface Listener {
        fun onChanged()
    }

    private val appContext = application.applicationContext
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val lock = Any()
    private val messageList = ArrayList<ChatMessage>()
    private val executor = Executors.newSingleThreadExecutor()
    private val persistence = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val imageCache = HashMap<String, String>()

    private var activeId: String = ""
    private var activeSession: String = AppPrefs.sessionId
    private var conversationTitle: String = ConversationStore.defaultTitle()

    @Volatile
    private var activeClient: ApiClient? = null

    @Volatile
    private var contextInfo: ScreenContextInfo? = null

    @Volatile
    private var pendingContext: ScreenContextInfo? = null

    @Volatile
    private var pendingAttachmentPath: String? = null

    @Volatile
    private var generation: Int = 0

    @Volatile
    var streamingText: String = ""
        private set

    @Volatile
    var isStreaming: Boolean = false
        private set

    @Volatile
    var status: String = "Prêt"
        private set

    @Volatile
    private var lastQuestion: String? = null

    init {
        val metas = ConversationStore.list(appContext)
        val savedId = AppPrefs.activeConversationId
        val active = metas.firstOrNull { it.id == savedId }
            ?: metas.firstOrNull()
            ?: ConversationStore.create(appContext, AppPrefs.sessionId)
        activeId = active.id
        activeSession = active.sessionId
        conversationTitle = active.title
        AppPrefs.activeConversationId = activeId
        val loaded = ConversationStore.load(appContext, activeId)
        synchronized(lock) {
            messageList.addAll(loaded)
            lastQuestion = loaded.lastOrNull {
                it.role == "user" && !it.isToolActivity && !it.isError
            }?.text
        }
        if (conversationTitle == ConversationStore.defaultTitle()) {
            val derived = ConversationStore.titleFor(loaded)
            if (derived != conversationTitle) {
                conversationTitle = derived
                persist()
            }
        }
    }

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

    fun setPendingContext(info: ScreenContextInfo) {
        pendingContext = info
        notifyChanged()
    }

    fun setPendingAttachment(path: String?) {
        pendingAttachmentPath = path
        notifyChanged()
    }

    fun hasPendingAttachment(): Boolean = pendingAttachmentPath != null

    fun messages(): List<ChatMessage> = synchronized(lock) { messageList.toList() }

    fun activeConversationId(): String = activeId

    fun activeConversationTitle(): String = conversationTitle

    fun conversations(): List<ConversationStore.ConversationMeta> {
        val list = ConversationStore.list(appContext)
        val current = ConversationStore.ConversationMeta(
            activeId,
            conversationTitle,
            System.currentTimeMillis(),
            activeSession
        )
        return if (list.any { it.id == activeId }) {
            list.map { if (it.id == activeId) current else it }
        } else {
            listOf(current) + list
        }
    }

    fun newConversation() {
        val current = messages()
        if (current.isEmpty() && conversationTitle == ConversationStore.defaultTitle()) return
        generation++
        activeClient?.cancel()
        val meta = ConversationStore.create(appContext, AppPrefs.sessionId)
        activate(meta, emptyList())
        notifyChanged()
    }

    fun switchConversation(id: String) {
        if (id == activeId) return
        val meta = ConversationStore.list(appContext).firstOrNull { it.id == id } ?: return
        generation++
        activeClient?.cancel()
        val loaded = ConversationStore.load(appContext, id)
        val title = if (meta.title == ConversationStore.defaultTitle()) {
            ConversationStore.titleFor(loaded)
        } else {
            meta.title
        }
        activate(meta.copy(title = title), loaded)
        notifyChanged()
    }

    fun deleteConversation(id: String): Boolean {
        val exists = ConversationStore.list(appContext).any { it.id == id }
        if (!exists) return false
        Diag.log("conversation deleted")
        if (id != activeId) {
            ConversationStore.delete(appContext, id)
            return true
        }
        generation++
        activeClient?.cancel()
        ConversationStore.delete(appContext, id)
        val next = ConversationStore.list(appContext).firstOrNull()
        if (next == null) {
            val meta = ConversationStore.create(appContext, AppPrefs.sessionId)
            activate(meta, emptyList())
        } else {
            val loaded = ConversationStore.load(appContext, next.id)
            val title = if (next.title == ConversationStore.defaultTitle()) {
                ConversationStore.titleFor(loaded)
            } else {
                next.title
            }
            activate(next.copy(title = title), loaded)
        }
        notifyChanged()
        return true
    }

    private fun activate(meta: ConversationStore.ConversationMeta, loaded: List<ChatMessage>) {
        activeId = meta.id
        activeSession = meta.sessionId
        conversationTitle = meta.title
        AppPrefs.activeConversationId = activeId
        synchronized(lock) {
            messageList.clear()
            messageList.addAll(loaded)
            lastQuestion = loaded.lastOrNull {
                it.role == "user" && !it.isToolActivity && !it.isError
            }?.text
        }
        pendingContext = null
        pendingAttachmentPath = null
        streamingText = ""
        isStreaming = false
        status = "Prêt"
        synchronized(imageCache) { imageCache.clear() }
        BubbleService.setState(BubbleState.Idle)
    }

    fun send(question: String) {
        val clean = question.trim()
        if (clean.isEmpty() || isStreaming) return

        val attached = resolveAttachContext()
        val header = attached?.let { buildContextHeader(it) }
        val sourceImage = pendingAttachmentPath
            ?: if (AppPrefs.sendScreenshot) attached?.imagePath else null
        val imagePath = ConversationStore.attachImage(appContext, activeId, sourceImage)
        pendingContext = null
        pendingAttachmentPath = null

        lastQuestion = clean
        addMessage(
            ChatMessage.user(
                text = clean,
                imagePath = imagePath,
                contextHeader = header
            )
        )
        startTurn()
    }

    fun retry() {
        if (isStreaming) return
        synchronized(lock) { messageList.removeAll { it.isError } }
        persist()
        if (lastQuestion == null) return
        startTurn()
    }

    /** Stops the current turn, drops the partial answer and re-enables input immediately. */
    fun cancel() {
        if (!isStreaming) return
        generation++
        activeClient?.cancel()
        main.post {
            streamingText = ""
            isStreaming = false
            status = "Prêt"
            BubbleService.setState(BubbleState.Idle)
            notifyChanged()
        }
    }

    fun clearConversation() {
        Diag.log("conversation cleared")
        generation++
        activeClient?.cancel()
        synchronized(lock) {
            messageList.clear()
            lastQuestion = null
        }
        pendingContext = null
        pendingAttachmentPath = null
        streamingText = ""
        isStreaming = false
        status = "Prêt"
        conversationTitle = ConversationStore.defaultTitle()
        activeSession = ConversationStore.sessionFor(AppPrefs.sessionId, activeId)
        synchronized(imageCache) { imageCache.clear() }
        BubbleService.setState(BubbleState.Idle)
        persistence.execute { ConversationStore.save(appContext, activeMeta(), emptyList()) }
        notifyChanged()
    }

    private fun resolveAttachContext(): ScreenContextInfo? {
        pendingContext?.let { return it }
        val hasUserMessage = synchronized(lock) {
            messageList.any { it.role == "user" && !it.isToolActivity && !it.isError }
        }
        return if (!hasUserMessage || AppPrefs.sendScreenshot) contextInfo else null
    }

    private fun startTurn() {
        Diag.log("network turn start")
        val turn = generation
        isStreaming = true
        streamingText = ""
        status = "Connexion…"
        BubbleService.setState(BubbleState.Thinking)
        notifyChanged()
        executor.execute { runTurn(turn) }
    }

    private fun runTurn(turn: Int) {
        var iteration = 0
        try {
            while (true) {
                if (turn != generation) return
                val tools = if (AppPrefs.controlMode && ScreenReaderService.instance != null) {
                    ControlTools.schema()
                } else {
                    null
                }
                val client = ApiClient.fromPrefs(activeSession)
                activeClient = client
                val result = try {
                    client.send(buildWire(), tools) { piece ->
                        main.post {
                            if (turn == generation) {
                                streamingText += piece
                                notifyChanged()
                            }
                        }
                    }
                } finally {
                    if (activeClient === client) activeClient = null
                }
                if (turn != generation) return

                if (result.toolCalls.isNotEmpty() && tools != null) {
                    if (iteration >= 6) {
                        addMessage(
                            ChatMessage.assistant(
                                "Trop d'actions enchaînées, je m'arrête là. Reformule ou relance."
                            )
                        )
                        finish(false, turn)
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
                        if (turn == generation) {
                            streamingText = ""
                            notifyChanged()
                        }
                    }
                    iteration++
                    continue
                }

                val finalText = result.content.ifBlank { streamingText }
                addMessage(
                    ChatMessage.assistant(
                        finalText.ifBlank { "Réponse vide du gateway, réessaie." }
                    )
                )
                finish(true, turn)
                return
            }
        } catch (e: ApiException) {
            if (turn == generation) fail(e.message ?: ApiClient.UNREACHABLE, turn)
        } catch (e: Exception) {
            if (turn == generation) {
                fail(e.message?.let { "Erreur : $it" } ?: ApiClient.UNREACHABLE, turn)
            }
        }
    }

    private fun finish(success: Boolean, turn: Int) {
        main.post {
            if (turn != generation) return@post
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

    private fun fail(message: String, turn: Int) {
        if (turn != generation) return
        Diag.log("network turn failed")
        addMessage(ChatMessage(role = "assistant", text = message, isError = true))
        main.post {
            if (turn != generation) return@post
            streamingText = ""
            isStreaming = false
            status = "Injoignable"
            BubbleService.setState(BubbleState.Idle)
            notifyChanged()
        }
    }

    private fun addMessage(message: ChatMessage) {
        synchronized(lock) { messageList.add(message) }
        if (conversationTitle == ConversationStore.defaultTitle()) {
            val derived = ConversationStore.titleFor(messages())
            if (derived != conversationTitle) conversationTitle = derived
        }
        persist()
        main.post { notifyChanged() }
    }

    private fun activeMeta(): ConversationStore.ConversationMeta =
        ConversationStore.ConversationMeta(
            activeId,
            conversationTitle,
            System.currentTimeMillis(),
            activeSession
        )

    private fun persist() {
        val turn = generation
        val snapshot = messages()
        val meta = activeMeta()
        persistence.execute {
            if (turn == generation) ConversationStore.save(appContext, meta, snapshot)
        }
    }

    private fun notifyChanged() {
        for (listener in listeners) listener.onChanged()
    }

    private fun buildWire(): JSONArray {
        val useful = messages().filter { !it.isError }
        val window = if (useful.size > 40) useful.takeLast(40) else useful
        val start = window.indexOfFirst { it.role == "user" }
        val capped = if (start >= 0) window.drop(start) else window

        val array = JSONArray()
        for (message in capped) {
            val text = message.contextHeader?.let { "$it\n\n${message.text}" } ?: message.text
            val image = message.imageDataUrl
                ?: message.imagePath?.let { loadImageDataUrl(it) }
            array.put(
                message.copy(
                    text = text,
                    imageDataUrl = image,
                    imagePath = null,
                    contextHeader = null
                ).toWireJson()
            )
        }
        return array
    }

    private fun buildContextHeader(info: ScreenContextInfo): String {
        val builder = StringBuilder(info.promptHeader())
        if (info.width > 0 && info.height > 0) {
            builder.append("\nCapture : ").append(info.width).append(" × ").append(info.height)
                .append(" px")
        }
        if (!info.visibleText.isNullOrBlank()) {
            builder.append("\nTexte visible : ").append(info.visibleText)
        }
        return builder.toString()
    }

    private fun loadImageDataUrl(path: String): String? {
        synchronized(imageCache) {
            imageCache[path]?.let { return it }
        }
        val data = runCatching {
            val bytes = File(path).readBytes()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull()
        if (data != null) synchronized(imageCache) { imageCache[path] = data }
        return data
    }

    override fun onCleared() {
        generation++
        activeClient?.cancel()
        BubbleService.setState(BubbleState.Idle)
        executor.shutdownNow()
        persistence.shutdown()
        listeners.clear()
        super.onCleared()
    }
}
