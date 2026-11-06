package dev.bubbleclaw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.NotificationCompat
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

class BubbleService : Service() {

    companion object {
        const val ACTION_START = "dev.bubbleclaw.action.START_BUBBLE"
        const val ACTION_STOP = "dev.bubbleclaw.action.STOP_BUBBLE"
        const val EXTRA_IMAGE_PATH = "extra_image_path"
        const val EXTRA_THUMB_PATH = "extra_thumb_path"
        const val EXTRA_CONTEXT = "extra_context"
        const val EXTRA_BUBBLE_AT_BOTTOM = "extra_bubble_at_bottom"
        const val EXTRA_BUBBLE_X = "extra_bubble_x"
        const val EXTRA_BUBBLE_Y = "extra_bubble_y"

        private const val CHANNEL_ID = "bubble"
        private const val NOTIFICATION_ID = 1
        private const val MENU_OPEN = 1
        private const val MENU_CONTROL = 2
        private const val MENU_SETTINGS = 3
        private const val MENU_HIDE = 4
        private const val MENU_CLEAR = 5

        @Volatile
        private var instance: BubbleService? = null

        @Volatile
        private var chatActivity: WeakReference<ChatActivity>? = null

        fun start(context: Context) {
            val intent = Intent(context, BubbleService::class.java).setAction(ACTION_START)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BubbleService::class.java).setAction(ACTION_STOP))
        }

        fun setState(state: BubbleState) {
            val service = instance ?: return
            Diag.log("state=$state")
            service.bubbleView?.state = state
        }

        fun isRunning(): Boolean = instance != null

        fun onChatOpened(activity: ChatActivity) {
            chatActivity = WeakReference(activity)
            instance?.bubbleView?.contentDescription =
                instance?.getString(R.string.bubble_description_close)
        }

        fun onChatClosed(activity: ChatActivity) {
            if (chatActivity?.get() === activity) {
                chatActivity = null
                instance?.bubbleView?.contentDescription =
                    instance?.getString(R.string.bubble_description_open)
            }
        }

        fun isChatOpen(): Boolean {
            val activity = chatActivity?.get() ?: return false
            return !activity.isFinishing && !activity.isDestroyed
        }

        fun closeChat() {
            val activity = chatActivity?.get() ?: return
            if (activity.isFinishing || activity.isDestroyed) return
            activity.requestCloseFromBubble()
        }

        fun clearChatIfOpen() {
            val activity = chatActivity?.get() ?: return
            if (activity.isFinishing || activity.isDestroyed) return
            activity.clearConversationFromOutside()
        }

        fun sinkBubble() {
            instance?.animateBubbleSink()
        }

        fun restoreBubble() {
            instance?.animateBubbleRestore()
        }
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: LobsterBubbleView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var snapAnimator: android.animation.ValueAnimator? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var longPressFired = false
    private var capturing = false

    private val longPressRunnable = Runnable {
        if (!dragging) {
            longPressFired = true
            showMenu()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        chatActivity = null
        Diag.log("service created")
        runCatching { createChannel() }.onFailure { Diag.log("notification channel error: ${it.javaClass.simpleName}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Diag.log("service start action=${intent?.action ?: "none"}")
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            .onFailure {
                Diag.log("startForeground failed: ${it.javaClass.simpleName}")
                runCatching { stopSelf() }
                return START_NOT_STICKY
            }
        if (!Settings.canDrawOverlays(this)) {
            Diag.log("overlay permission missing")
            Toast.makeText(this, "Autorisation d'affichage par-dessus requise.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!AppPrefs.bubbleEnabled) {
            Diag.log("bubble disabled")
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubbleView == null) {
            runCatching { addBubble() }.onFailure {
                Diag.log("addBubble failed: ${it.javaClass.simpleName}")
                runCatching { stopSelf() }
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val layout = params ?: return
        val size = dp(64)
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        layout.x = if (AppPrefs.bubbleOnLeft) 0 else (width - size).coerceAtLeast(0)
        clampY(layout, height)
        updateLayout(layout)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        snapAnimator?.cancel()
        bubbleView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        bubbleView = null
        params = null
        chatActivity = null
        instance = null
        Diag.log("service destroyed")
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bubble_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.bubble_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.bubble_notification_title))
            .setContentText(getString(R.string.bubble_notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.bubble_notification_stop), stopIntent)
            .build()
    }

    private fun addBubble() {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = manager
        val size = dp(64)
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val view = LobsterBubbleView(this).apply {
            contentDescription = getString(R.string.bubble_description_open)
        }
        val layout = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (AppPrefs.bubbleOnLeft) 0 else (width - size).coerceAtLeast(0)
            y = AppPrefs.bubbleY.takeIf { it >= 0 } ?: (height / 3)
        }
        clampY(layout, height)

        view.setOnTouchListener { _, event -> handleTouch(event, layout, width, height); true }
        manager.addView(view, layout)
        bubbleView = view
        params = layout
    }

    private fun handleTouch(event: MotionEvent, layout: WindowManager.LayoutParams, width: Int, height: Int) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = layout.x
                startY = layout.y
                dragging = false
                longPressFired = false
                handler.postDelayed(longPressRunnable, 480)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > dp(8).toDouble()) {
                    dragging = true
                    handler.removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    layout.x = (startX + dx).toInt()
                    layout.y = (startY + dy).toInt()
                    clampY(layout, height)
                    updateLayout(layout)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (dragging) {
                    snapToEdge(layout, width, height)
                } else if (!longPressFired && event.actionMasked == MotionEvent.ACTION_UP) {
                    onBubbleTap()
                }
                dragging = false
            }
        }
    }

    private fun clampY(layout: WindowManager.LayoutParams, screenHeight: Int) {
        val minY = dp(24)
        val maxY = (screenHeight - dp(64) - dp(24)).coerceAtLeast(minY)
        layout.y = layout.y.coerceIn(minY, maxY)
    }

    private fun updateLayout(layout: WindowManager.LayoutParams) {
        val view = bubbleView ?: return
        runCatching { windowManager?.updateViewLayout(view, layout) }
    }

    private fun snapToEdge(layout: WindowManager.LayoutParams, width: Int, height: Int) {
        clampY(layout, height)
        val size = dp(64)
        val center = layout.x + size / 2
        val target = if (center < width / 2) 0 else width - size
        AppPrefs.bubbleY = layout.y
        AppPrefs.bubbleOnLeft = target == 0
        snapAnimator?.cancel()
        snapAnimator = android.animation.ValueAnimator.ofInt(layout.x, target).apply {
            duration = 180
            addUpdateListener {
                layout.x = it.animatedValue as Int
                updateLayout(layout)
            }
            start()
        }
    }

    private fun onBubbleTap() {
        if (capturing) return
        Diag.log("bubble tap")
        if (isChatOpen()) {
            bubbleView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            closeChat()
            return
        }
        capturing = true
        bubbleView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        animateBubbleSink()
        val reader = ScreenReaderService.instance
        val app = reader?.foregroundAppName
        val page = reader?.currentPageUrl()
        val title = reader?.currentWindowTitle
        val pkg = reader?.foregroundAppPackage
        val time = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date())

        if (reader == null || !AppPrefs.autoScreenshot) {
            Diag.log("capture skipped reader=${reader != null} auto=${AppPrefs.autoScreenshot}")
            val info = ScreenContextInfo(
                appName = app,
                page = page,
                windowTitle = title,
                time = time,
                imagePath = null,
                thumbPath = null,
                width = 0,
                height = 0,
                visibleText = if (AppPrefs.includeVisibleText) reader?.visibleTextDigest() else null,
                accessibilityMissing = reader == null,
                appPackage = pkg
            )
            capturing = false
            openChat(info)
            return
        }

        Diag.log("capture start")
        // Open the panel right away with a skeleton; the capture fills it in later.
        openChat(
            ScreenContextInfo(
                appName = app,
                page = page,
                windowTitle = title,
                time = time,
                imagePath = null,
                thumbPath = null,
                width = 0,
                height = 0,
                visibleText = null,
                accessibilityMissing = false,
                appPackage = pkg,
                loading = true
            )
        )

        reader.capture { capture ->
            if (capture == null) {
                Diag.log("capture failed")
                capturing = false
                handler.post {
                    ContextHolder.put(
                        ScreenContextInfo(
                            app, page, title, time, null, null, 0, 0, null, false, pkg
                        )
                    )
                }
                return@capture
            }
            Diag.log("capture ok ${capture.bitmap.width}x${capture.bitmap.height}")
            val directory = File(cacheDir, "captures").apply { mkdirs() }
            val stamp = System.currentTimeMillis()
            val imageFile = File(directory, "capture_$stamp.jpg")
            val thumbFile = File(directory, "capture_${stamp}_thumb.jpg")
            runCatching {
                imageFile.writeBytes(capture.jpeg)
                thumbFile.writeBytes(jpegOf(capture.thumbnail))
            }
            pruneCaptures(directory)
            val imagePath = imageFile.takeIf { it.exists() }?.absolutePath
            val thumbPath = thumbFile.takeIf { it.exists() }?.absolutePath
            val width = capture.bitmap.width
            val height = capture.bitmap.height
            handler.post {
                val info = ScreenContextInfo(
                    appName = app,
                    page = page,
                    windowTitle = title,
                    time = time,
                    imagePath = imagePath,
                    thumbPath = thumbPath,
                    width = width,
                    height = height,
                    visibleText = if (AppPrefs.includeVisibleText) reader.visibleTextDigest() else null,
                    accessibilityMissing = false,
                    appPackage = pkg
                )
                capturing = false
                ContextHolder.put(info)
            }
        }
    }

    private fun jpegOf(bitmap: android.graphics.Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
        return stream.toByteArray()
    }

    private fun pruneCaptures(directory: File) {
        runCatching {
            directory.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(20)
                ?.forEach { it.delete() }
        }
    }

    private fun openChat(info: ScreenContextInfo) {
        Diag.log("chat open requested")
        ContextHolder.put(info)
        bubbleView?.state = BubbleState.Idle
        val screenHeight = resources.displayMetrics.heightPixels
        val size = dp(64)
        val bubbleCenterX = (params?.x ?: 0) + size / 2
        val bubbleCenterY = (params?.y ?: 0) + size / 2
        val intent = Intent(this, ChatActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_IMAGE_PATH, info.imagePath)
            putExtra(EXTRA_THUMB_PATH, info.thumbPath)
            putExtra(EXTRA_CONTEXT, info.summary())
            putExtra(EXTRA_BUBBLE_AT_BOTTOM, bubbleCenterY > screenHeight / 2)
            putExtra(EXTRA_BUBBLE_X, bubbleCenterX)
            putExtra(EXTRA_BUBBLE_Y, bubbleCenterY)
        }
        runCatching { startActivity(intent) }
            .onFailure { Diag.log("chat start failed: ${it.javaClass.simpleName}") }
    }

    private fun animateBubbleSink() {
        val view = bubbleView ?: return
        view.animate().cancel()
        view.animate()
            .scaleX(0.86f)
            .scaleY(0.86f)
            .setDuration(90)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun animateBubbleRestore() {
        val view = bubbleView ?: return
        view.animate().cancel()
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(140)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun showMenu() {
        val view = bubbleView ?: return
        val themed = ContextThemeWrapper(this, R.style.Theme_BubbleClaw)
        val popup = PopupMenu(themed, view)
        popup.menu.add(0, MENU_OPEN, 0, getString(R.string.menu_open_chat))
        popup.menu.add(
            0,
            MENU_CONTROL,
            1,
            getString(if (AppPrefs.controlMode) R.string.menu_control_on else R.string.menu_control_off)
        )
        popup.menu.add(0, MENU_SETTINGS, 2, getString(R.string.menu_settings))
        popup.menu.add(0, MENU_CLEAR, 3, getString(R.string.menu_clear_chat))
        popup.menu.add(0, MENU_HIDE, 4, getString(R.string.menu_hide_bubble))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_OPEN -> onBubbleTap()
                MENU_CONTROL -> {
                    AppPrefs.controlMode = !AppPrefs.controlMode
                    val message = if (AppPrefs.controlMode) {
                        if (ScreenReaderService.instance == null) {
                            "Mode contrôle activé — active le service d'accessibilité pour agir."
                        } else {
                            "Mode contrôle activé"
                        }
                    } else {
                        "Mode contrôle désactivé"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
                MENU_SETTINGS -> startActivity(
                    Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                MENU_CLEAR -> confirmClearConversation()
                MENU_HIDE -> {
                    AppPrefs.bubbleEnabled = false
                    Toast.makeText(this, "Bulle masquée", Toast.LENGTH_SHORT).show()
                    stopSelf()
                }
            }
            true
        }
        runCatching { popup.show() }.onFailure {
            Toast.makeText(this, "Menu momentanément indisponible.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearConversation() {
        val themed = ContextThemeWrapper(this, R.style.Theme_BubbleClaw)
        AlertDialog.Builder(themed)
            .setMessage(getString(R.string.chat_clear_confirm))
            .setNegativeButton(getString(R.string.chat_clear_no), null)
            .setPositiveButton(getString(R.string.chat_clear_yes)) { _, _ ->
                ConversationStore.clear(this)
                clearChatIfOpen()
                Toast.makeText(this, getString(R.string.chat_cleared), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}
