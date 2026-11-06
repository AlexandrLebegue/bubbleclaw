package dev.bubbleclaw

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Snapshot of what the user was looking at when the bubble was tapped. */
data class ScreenContextInfo(
    val appName: String?,
    val page: String?,
    val windowTitle: String?,
    val time: String,
    val imagePath: String?,
    val thumbPath: String?,
    val width: Int,
    val height: Int,
    val visibleText: String?,
    val accessibilityMissing: Boolean = false,
    val appPackage: String? = null,
    val loading: Boolean = false
) {
    fun summary(): String {
        val app = appName?.takeIf { it.isNotBlank() } ?: "Application inconnue"
        val page = page?.takeIf { it.isNotBlank() }
            ?: windowTitle?.takeIf { it.isNotBlank() }
            ?: "Fenêtre active"
        return "Écran : $app — $page"
    }

    fun promptHeader(): String {
        val app = appName?.takeIf { it.isNotBlank() } ?: "Application inconnue"
        val page = page?.takeIf { it.isNotBlank() }
            ?: windowTitle?.takeIf { it.isNotBlank() }
            ?: "Fenêtre active"
        return "[Contexte écran] App : $app\nPage : $page\nHeure : $time"
    }
}

/** Bridges the bubble service and the chat activity without Binder payload limits. */
object ContextHolder {
    private val ref = AtomicReference<ScreenContextInfo?>(null)
    private val listeners = CopyOnWriteArrayList<(ScreenContextInfo?) -> Unit>()

    fun put(info: ScreenContextInfo) {
        ref.set(info)
        listeners.forEach { it(info) }
    }

    fun get(): ScreenContextInfo? = ref.get()

    fun addListener(listener: (ScreenContextInfo?) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (ScreenContextInfo?) -> Unit) {
        listeners.remove(listener)
    }

    fun clear() {
        ref.set(null)
    }
}
