package dev.bubbleclaw

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class ScreenCapture(
    val bitmap: Bitmap,
    val thumbnail: Bitmap,
    val jpeg: ByteArray
)

class ScreenReaderService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ScreenReaderService? = null
            private set

        private val BROWSERS = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.opera.gx",
            "com.duckduckgo.mobile.android",
            "com.vivaldi.browser",
            "org.chromium.chrome"
        )

        private const val MAX_WIDTH = 1080
        private const val THUMB_WIDTH = 320
        private const val JPEG_QUALITY = 70
        private val URL_RX = Regex("^[\\w.-]+\\.[a-z]{2,}(/.*)?$", RegexOption.IGNORE_CASE)
        private val PLACEHOLDERS = listOf(
            "Rechercher ou saisir une URL",
            "Rechercher ou saisir une adresse",
            "Search or type URL",
            "Search or enter address",
            "Search Google or type a URL"
        )
    }

    private var foregroundPackage: String? = null
    private var foregroundLabel: String? = null
    private var windowTitle: String? = null
    private val labelCache = HashMap<String, String>()
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Diag.log("accessibility connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Diag.log("accessibility unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        Diag.log("accessibility destroyed")
        captureExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg != foregroundPackage) {
            foregroundPackage = pkg
            foregroundLabel = resolveLabel(pkg)
        }
        val title = event.text
            ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            ?.firstOrNull()
        if (!title.isNullOrBlank()) windowTitle = title
    }

    val foregroundAppName: String?
        get() = foregroundLabel

    val foregroundAppPackage: String?
        get() = foregroundPackage

    val currentWindowTitle: String?
        get() = windowTitle

    fun captureScreenshot(callback: (Bitmap?) -> Unit) {
        capture { callback(it?.bitmap) }
    }

    fun capture(callback: (ScreenCapture?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }
        runCatching {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    var buffer: android.hardware.HardwareBuffer? = null
                    var full: Bitmap?
                    try {
                        buffer = screenshot.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        full = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (e: Exception) {
                        full = null
                    } finally {
                        try {
                            buffer?.close()
                        } catch (_: Exception) {
                        }
                    }
                    if (full == null) {
                        callback(null)
                        return
                    }
                    // Decoding/scale/JPEG are the expensive part: keep them off the main thread.
                    captureExecutor.execute {
                        val result = try {
                            val scaled = scaleToMaxWidth(full, MAX_WIDTH)
                            val thumbnail = scaleToMaxWidth(scaled, THUMB_WIDTH)
                            val jpeg = compressJpeg(scaled, JPEG_QUALITY)
                            ScreenCapture(scaled, thumbnail, jpeg)
                        } catch (e: Exception) {
                            null
                        }
                        callback(result)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Diag.log("screen capture failure code=$errorCode")
                    callback(null)
                }
            }
        )
        }.onFailure {
            Diag.log("screen capture error: ${it.javaClass.simpleName}")
            callback(null)
        }
    }

    fun currentPageUrl(): String? {
        val pkg = foregroundPackage ?: return null
        if (pkg !in BROWSERS) return null
        val root = rootInActiveWindow ?: return windowTitle?.takeIf { it.isNotBlank() }
        val screenHeight = resources.displayMetrics.heightPixels
        val limit = (screenHeight * 0.35f).toInt()
        val found = findOmnibox(root, limit, 0)
        if (found != null) {
            val cleaned = cleanUrl(found)
            if (cleaned.isNotBlank()) return cleaned
        }
        return windowTitle?.trim()?.takeIf { it.isNotBlank() && !looksLikePlaceholder(it) }
    }

    fun visibleTextDigest(maxChars: Int = 1500): String? {
        val root = rootInActiveWindow ?: return null
        val builder = StringBuilder()
        collectText(root, builder, 0)
        val collapsed = builder.toString().replace(Regex("\\s+"), " ").trim()
        if (collapsed.isEmpty()) return null
        return if (collapsed.length > maxChars) collapsed.substring(0, maxChars).trimEnd() + "…" else collapsed
    }

    fun performTap(x: Float, y: Float): String {
        if (instance == null) return NOT_CONNECTED
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatch(gesture, "tap(${x.toInt()}, ${y.toInt()})")
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 300): String {
        if (instance == null) return NOT_CONNECTED
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val duration = durationMs.coerceIn(80, 3000).toLong()
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatch(
            gesture,
            "swipe(${x1.toInt()}, ${y1.toInt()} → ${x2.toInt()}, ${y2.toInt()})"
        )
    }

    fun screenWidthPx(): Int = runCatching {
        (getSystemService(WindowManager::class.java)).maximumWindowMetrics.bounds.width()
    }.getOrDefault(resources.displayMetrics.widthPixels)

    fun screenHeightPx(): Int = runCatching {
        (getSystemService(WindowManager::class.java)).maximumWindowMetrics.bounds.height()
    }.getOrDefault(resources.displayMetrics.heightPixels)

    fun performScroll(direction: String, amount: Int): String {
        if (instance == null) return NOT_CONNECTED
        val width = screenWidthPx()
        val height = screenHeightPx()
        val cx = width / 2f
        val cy = height / 2f
        val offset = amount.coerceIn(60, height / 3).toFloat()
        return when (direction.lowercase()) {
            "up" -> performSwipe(cx, cy + offset, cx, cy - offset, 300)
            "down" -> performSwipe(cx, cy - offset, cx, cy + offset, 300)
            "left" -> performSwipe(cx + offset, cy, cx - offset, cy, 300)
            "right" -> performSwipe(cx - offset, cy, cx + offset, cy, 300)
            else -> "Direction inconnue : $direction"
        }
    }

    fun performGlobal(action: String): String {
        if (instance == null) return NOT_CONNECTED
        val code = when (action.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "recent" -> GLOBAL_ACTION_RECENTS
            "notifications", "notification" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return "Action inconnue : $action"
        }
        return if (performGlobalAction(code)) "Action ${action.lowercase()} effectuée." else "Action refusée par le système."
    }

    fun typeText(text: String): String {
        if (instance == null) return NOT_CONNECTED
        val root = rootInActiveWindow ?: return "Aucune fenêtre active."
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (focused != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return "Texte saisi dans le champ actif."
            }
            if (focused.isEditable) {
                focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                copyToClipboard(text)
                if (focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    return "Texte collé dans le champ actif."
                }
            }
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("BubbleClaw", text))
        return if (focused != null && focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            "Texte collé dans le champ actif."
        } else {
            "Texte copié, aucun champ actif n'a pu être rempli."
        }
    }

    fun performEnter(): String {
        if (instance == null) return NOT_CONNECTED
        val root = rootInActiveWindow ?: return "Aucune fenêtre active."
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return "Aucun champ actif."
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        ) {
            "Touche Entrée envoyée."
        } else {
            "Impossible d'envoyer Entrée."
        }
    }

    fun openApp(query: String): String {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(intent, 0)
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return "Nom d'application vide."
        val direct = pm.getLaunchIntentForPackage(query.trim())
        if (direct != null) {
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(direct)
            return "Application ${resolveLabel(query.trim())} ouverte."
        }
        val matches = activities.filter { info ->
            val label = info.loadLabel(pm).toString().lowercase()
            label.contains(needle) || info.activityInfo.packageName.lowercase().contains(needle)
        }.distinctBy { it.activityInfo.packageName }
        return when {
            matches.isEmpty() -> "Application introuvable : $query"
            matches.size > 3 -> "Plusieurs applications correspondent : $query"
            matches.size > 1 -> {
                val names = matches.joinToString(", ") { it.loadLabel(pm).toString() }
                "Plusieurs applications correspondent ($names). Précise le nom."
            }
            else -> {
                val launch = pm.getLaunchIntentForPackage(matches.first().activityInfo.packageName)
                if (launch == null) {
                    "Impossible d'ouvrir ${matches.first().loadLabel(pm)}."
                } else {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                    "Application ${matches.first().loadLabel(pm)} ouverte."
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("BubbleClaw", text))
    }

    private fun dispatch(gesture: GestureDescription, label: String): String {
        return try {
            val accepted = dispatchGesture(gesture, null, null)
            if (accepted) "$label effectué." else "Geste refusé par le système."
        } catch (e: Exception) {
            "Geste impossible : ${e.message ?: "erreur inconnue"}"
        }
    }

    private fun findOmnibox(node: AccessibilityNodeInfo, topLimit: Int, depth: Int): String? {
        if (depth > 40) return null
        val className = node.className?.toString().orEmpty()
        if (className.contains("EditText")) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.top <= topLimit && node.isVisibleToUser) {
                val raw = node.text?.toString()
                    ?: node.contentDescription?.toString()
                    ?: node.hintText?.toString()
                if (!raw.isNullOrBlank() && !looksLikePlaceholder(raw)) {
                    val cleaned = cleanUrl(raw)
                    if (looksLikeUrlOrQuery(cleaned)) return cleaned
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findOmnibox(child, topLimit, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
        if (depth > 40) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.append(it).append(' ') }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out, depth + 1)
        }
    }

    private fun looksLikePlaceholder(text: String): Boolean {
        val trimmed = text.trim()
        return PLACEHOLDERS.any { trimmed.contains(it, ignoreCase = true) }
    }

    private fun cleanUrl(raw: String): String {
        var value = raw.trim()
        for (placeholder in PLACEHOLDERS) {
            value = value.replace(placeholder, "", ignoreCase = true)
        }
        value = value.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("www.")
        value = value.trim().trimEnd('/')
        return value
    }

    private fun looksLikeUrlOrQuery(text: String): Boolean {
        if (text.isBlank()) return false
        return text.contains("://") || text.startsWith("www.") || URL_RX.matches(text.trim())
    }

    private fun resolveLabel(pkg: String): String {
        labelCache[pkg]?.let { return it }
        val label = try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            pkg
        }
        labelCache[pkg] = label
        return label
    }

    private fun scaleToMaxWidth(source: Bitmap, maxWidth: Int): Bitmap {
        if (source.width <= maxWidth) return source
        val ratio = maxWidth.toFloat() / source.width
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, maxWidth, height, true)
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}

private const val NOT_CONNECTED = "Service d'accessibilité non connecté. Active-le dans les réglages."
