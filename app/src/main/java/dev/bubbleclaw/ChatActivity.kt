package dev.bubbleclaw

import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : AppCompatActivity(), ChatViewModel.Listener {

    private lateinit var viewModel: ChatViewModel
    private lateinit var root: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var headerRow: LinearLayout
    private lateinit var inputRow: LinearLayout
    private lateinit var contextCard: LinearLayout
    private lateinit var scroll: MaxHeightScrollView
    private lateinit var container: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var input: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var contextApp: TextView
    private lateinit var contextPage: TextView
    private lateinit var contextTime: TextView
    private lateinit var contextThumb: ImageView
    private lateinit var appIcon: ImageView
    private lateinit var mascotSlot: FrameLayout
    private lateinit var mascot: LobsterView
    private lateinit var headerDots: BouncingDotsView

    private var renderedCount = -1
    private var wasStreaming = false
    private val happyReset = Runnable {
        if (!viewModel.isStreaming) mascot.mood = LobsterMood.Idle
    }
    private var streamingView: TextView? = null
    private var thinkingView: ThinkingIndicatorView? = null
    private var contextInfo: ScreenContextInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0f)
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        root = findViewById(R.id.chat_root)
        panel = findViewById(R.id.chat_panel)
        headerRow = findViewById(R.id.header_row)
        inputRow = findViewById(R.id.input_row)
        contextCard = findViewById(R.id.context_card)
        scroll = findViewById(R.id.scroll)
        container = findViewById(R.id.messages_container)
        statusText = findViewById(R.id.status_text)
        input = findViewById(R.id.input)
        sendButton = findViewById(R.id.send_button)
        contextApp = findViewById(R.id.context_app)
        contextPage = findViewById(R.id.context_page)
        contextTime = findViewById(R.id.context_time)
        contextThumb = findViewById(R.id.context_thumb)
        appIcon = findViewById(R.id.app_icon)
        mascotSlot = findViewById(R.id.mascot_slot)
        headerDots = findViewById(R.id.header_dots)

        mascot = LobsterView(this)
        mascotSlot.addView(
            mascot,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val bubbleAtBottom = intent.getBooleanExtra(BubbleService.EXTRA_BUBBLE_AT_BOTTOM, false)
        val panelParams = panel.layoutParams as FrameLayout.LayoutParams
        panelParams.gravity = if (bubbleAtBottom) {
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        } else {
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        panel.layoutParams = panelParams
        panel.setOnClickListener { }

        root.setOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, system.top, 0, maxOf(system.bottom, ime.bottom))
            view.post { updatePanelGeometry() }
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePanelGeometry()
        }

        viewModel = ViewModelProvider(this).get(ChatViewModel::class.java)
        contextInfo = resolveContext()
        populateContext(contextInfo)
        viewModel.setContext(contextInfo)

        findViewById<TextView>(R.id.close_button).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.retake_context).setOnClickListener { captureContext() }
        sendButton.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || (enter && event?.isShiftPressed != true)) {
                submit()
                true
            } else {
                false
            }
        }

        viewModel.attach(this)
        render()
    }

    override fun onDestroy() {
        mascot.removeCallbacks(happyReset)
        viewModel.detach(this)
        super.onDestroy()
    }

    override fun onChanged() {
        render()
    }

    private fun resolveContext(): ScreenContextInfo? {
        ContextHolder.get()?.let { return it }
        val summary = intent.getStringExtra(BubbleService.EXTRA_CONTEXT)
        val image = intent.getStringExtra(BubbleService.EXTRA_IMAGE_PATH)
        val thumb = intent.getStringExtra(BubbleService.EXTRA_THUMB_PATH)
        if (summary == null && image == null && thumb == null) return null
        val time = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date())
        return ScreenContextInfo(
            appName = null,
            page = null,
            windowTitle = summary,
            time = time,
            imagePath = image,
            thumbPath = thumb,
            width = 0,
            height = 0,
            visibleText = null,
            accessibilityMissing = false
        )
    }

    private fun populateContext(info: ScreenContextInfo?) {
        if (info == null) {
            contextApp.text = "Contexte indisponible"
            contextPage.text = "Ouvre la discussion depuis la bulle pour capturer l'écran."
            contextTime.text = ""
            contextThumb.visibility = View.GONE
            appIcon.setImageResource(R.drawable.ic_launcher)
            return
        }
        contextApp.text = info.appName?.takeIf { it.isNotBlank() } ?: "Application inconnue"
        contextPage.text = when {
            info.accessibilityMissing -> "Service d'accessibilité désactivé — active-le dans les réglages."
            else -> info.page?.takeIf { it.isNotBlank() }
                ?: info.windowTitle?.takeIf { it.isNotBlank() }
                ?: "Fenêtre active"
        }
        val dimensions = if (info.width > 0 && info.height > 0) " · ${info.width}×${info.height}" else ""
        contextTime.text = "Capture à ${info.time}$dimensions"

        val packageName = info.appPackage
        if (!packageName.isNullOrBlank()) {
            runCatching {
                appIcon.setImageDrawable(packageManager.getApplicationIcon(packageName))
            }.onFailure { appIcon.setImageResource(R.drawable.ic_launcher) }
        } else {
            appIcon.setImageResource(R.drawable.ic_launcher)
        }

        val thumbPath = info.thumbPath
        val thumb = thumbPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        if (thumb != null) {
            contextThumb.visibility = View.VISIBLE
            contextThumb.setImageBitmap(thumb)
            val full = info.imagePath ?: thumbPath
            contextThumb.setOnClickListener { showFullScreen(full) }
        } else {
            contextThumb.visibility = View.GONE
        }
    }

    private fun submit() {
        val text = input.text.toString()
        if (text.isBlank() || viewModel.isStreaming) return
        input.setText("")
        viewModel.send(text)
    }

    private fun captureContext() {
        val reader = ScreenReaderService.instance
        if (reader == null) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.chat_context_unavailable),
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        val button = findViewById<ImageButton>(R.id.retake_context)
        button.isEnabled = false
        reader.capture { capture ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                button.isEnabled = true
                if (capture == null) {
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.chat_context_unavailable),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                val directory = java.io.File(cacheDir, "captures").apply { mkdirs() }
                val stamp = System.currentTimeMillis()
                val imageFile = java.io.File(directory, "capture_$stamp.jpg")
                val thumbFile = java.io.File(directory, "capture_${stamp}_thumb.jpg")
                runCatching {
                    imageFile.writeBytes(capture.jpeg)
                    thumbFile.writeBytes(jpegOf(capture.thumbnail))
                }
                val info = ScreenContextInfo(
                    appName = reader.foregroundAppName,
                    page = reader.currentPageUrl(),
                    windowTitle = reader.currentWindowTitle,
                    time = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date()),
                    imagePath = imageFile.takeIf { it.exists() }?.absolutePath,
                    thumbPath = thumbFile.takeIf { it.exists() }?.absolutePath,
                    width = capture.bitmap.width,
                    height = capture.bitmap.height,
                    visibleText = if (AppPrefs.includeVisibleText) reader.visibleTextDigest() else null,
                    accessibilityMissing = false,
                    appPackage = reader.foregroundAppPackage
                )
                viewModel.setPendingContext(info)
                populateContext(info)
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.chat_context_attached),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun jpegOf(bitmap: android.graphics.Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
        return stream.toByteArray()
    }

    private fun render() {
        val messages = viewModel.messages()
        if (messages.size != renderedCount) {
            container.removeAllViews()
            if (messages.isEmpty()) addEmptyState()
            for (message in messages) addMessageView(message)
            renderedCount = messages.size
            streamingView = null
            thinkingView = null
        }

        if (viewModel.isStreaming) {
            val text = viewModel.streamingText
            if (text.isBlank()) {
                streamingView?.let { view ->
                    (view.parent as? ViewGroup)?.removeView(view)
                }
                streamingView = null
                if (thinkingView == null) thinkingView = addThinkingBubble()
            } else {
                thinkingView?.let { view ->
                    (view.parent as? ViewGroup)?.removeView(view)
                }
                thinkingView = null
                val view = streamingView
                if (view == null || view.parent == null) {
                    streamingView = addAssistantBubble(text)
                } else {
                    view.text = text
                }
            }
        } else {
            streamingView?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
            }
            streamingView = null
            thinkingView?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
            }
            thinkingView = null
        }
        headerDots.visibility =
            if (viewModel.isStreaming && viewModel.streamingText.isBlank()) View.VISIBLE
            else View.GONE

        statusText.text = viewModel.status
        statusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (viewModel.status == "Injoignable") R.color.danger else R.color.success
            )
        )
        sendButton.isEnabled = !viewModel.isStreaming
        sendButton.alpha = if (viewModel.isStreaming) 0.45f else 1f

        if (viewModel.isStreaming) {
            mascot.mood = LobsterMood.Thinking
            wasStreaming = true
        } else if (wasStreaming) {
            mascot.mood = LobsterMood.Happy
            wasStreaming = false
            mascot.removeCallbacks(happyReset)
            mascot.postDelayed(happyReset, 1600)
        } else if (mascot.mood == LobsterMood.Thinking) {
            mascot.mood = LobsterMood.Idle
        }

        scrollToBottom()
    }

    private fun addEmptyState() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(22), dp(12), dp(22))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val slot = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
        }
        slot.addView(
            LobsterView(this).apply { mood = LobsterMood.Idle },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        column.addView(slot)
        column.addView(
            TextView(this).apply {
                text = getString(R.string.chat_empty_welcome)
                setTextColor(ContextCompat.getColor(this@ChatActivity, R.color.text_secondary))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), 0)
            }
        )
        container.addView(column)
    }

    private fun addThinkingBubble(): ThinkingIndicatorView {
        val row = bubbleRow(Gravity.START)
        val indicator = ThinkingIndicatorView(this)
        row.addView(indicator)
        container.addView(row)
        return indicator
    }

    private fun addMessageView(message: ChatMessage) {
        if (message.role == "assistant" && isPlaceholder(message.text)) return
        when {
            message.isError -> addErrorView(message.text)
            message.toolCalls.isNotEmpty() -> {
                if (message.text.isNotBlank()) addAssistantBubble(message.text)
                for (call in message.toolCalls) {
                    addActivityLine("⚙︎ ${call.name}(${shorten(call.arguments, 90)})")
                }
            }
            message.role == "tool" -> addActivityLine("↳ ${shorten(message.text, 180)}")
            message.role == "user" -> addUserBubble(message.text)
            else -> addAssistantBubble(message.text)
        }
    }

    private fun addUserBubble(text: String): TextView {
        val row = bubbleRow(Gravity.END)
        val bubble = bubbleTextView(text, R.drawable.bg_bubble_user, R.color.text_primary)
        row.addView(bubble)
        container.addView(row)
        return bubble
    }

    private fun addAssistantBubble(text: String): TextView {
        val row = bubbleRow(Gravity.START)
        val bubble = bubbleTextView(text, R.drawable.bg_bubble_assistant, R.color.text_primary)
        row.addView(bubble)
        container.addView(row)
        return bubble
    }

    private fun addErrorView(reason: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bubble_error)
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                maxBubbleWidth(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        card.addView(
            TextView(this).apply {
                text = reason
                setTextColor(ContextCompat.getColor(this@ChatActivity, R.color.danger))
                textSize = 14f
            }
        )
        card.addView(
            TextView(this).apply {
                text = getString(R.string.chat_retry)
                setTextColor(ContextCompat.getColor(this@ChatActivity, R.color.coral_light))
                textSize = 14f
                setPadding(0, dp(8), 0, 0)
                setOnClickListener { viewModel.retry() }
            }
        )
        container.addView(card)
    }

    private fun addActivityLine(text: String) {
        container.addView(
            TextView(this).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(this@ChatActivity, R.color.text_secondary))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(4), dp(3), dp(4), dp(3))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            }
        )
    }

    private fun bubbleRow(gravity: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }

    private fun bubbleTextView(text: String, background: Int, color: Int): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@ChatActivity, color))
            textSize = 15f
            setLineSpacing(0f, 1.12f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = maxBubbleWidth()
            setBackgroundResource(background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    private fun maxBubbleWidth(): Int =
        (resources.displayMetrics.widthPixels * 0.82f).toInt()

    private fun scrollToBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showFullScreen(path: String?) {
        if (path.isNullOrBlank()) return
        val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(
            image,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        dialog.show()
    }

    private fun updatePanelGeometry() {
        val metrics = resources.displayMetrics
        val targetWidth = minOf((metrics.widthPixels * 0.92f).toInt(), dp(420))
        if (panel.layoutParams.width != targetWidth) {
            panel.layoutParams = panel.layoutParams.apply { width = targetWidth }
        }
        val available = root.height - root.paddingTop - root.paddingBottom
        if (available <= 0) return
        val maxPanel = (available * 0.62f).toInt()
        val contextHeight = if (contextCard.visibility == View.VISIBLE) contextCard.measuredHeight else 0
        val fixed = headerRow.measuredHeight + contextHeight + inputRow.measuredHeight
        val maxScroll = (maxPanel - fixed).coerceAtLeast(dp(180))
        scroll.maxHeight = maxScroll
    }

    private fun isPlaceholder(text: String): Boolean {
        val clean = text.trim()
        return clean == "…" || clean == "..." || clean.startsWith("Réflexion")
    }

    private fun shorten(value: String, max: Int): String =
        if (value.length <= max) value else value.substring(0, max).trimEnd() + "…"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
