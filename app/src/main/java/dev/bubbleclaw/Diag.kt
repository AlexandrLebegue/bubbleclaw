package dev.bubbleclaw

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight internal journal used to diagnose field issues without adb.
 * It never records conversation content: only lifecycle, actions and generic errors.
 */
object Diag {

    private const val FILE_NAME = "diag.log"
    private const val MAX_BYTES = 200 * 1024
    private const val KEEP_BYTES = MAX_BYTES / 2

    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun log(message: String) {
        val context = appContext ?: return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE).format(Date())
        synchronized(lock) {
            runCatching {
                val file = File(context.filesDir, FILE_NAME)
                file.appendText("$stamp  $message\n")
                trim(file)
            }
        }
    }

    fun read(): String {
        val context = appContext ?: return ""
        return synchronized(lock) {
            runCatching { File(context.filesDir, FILE_NAME).readText() }.getOrDefault("")
        }
    }

    fun lastLines(count: Int): String {
        val lines = read().lineSequence().filter { it.isNotBlank() }.toList()
        return lines.takeLast(count.coerceAtLeast(1)).joinToString("\n")
    }

    private fun trim(file: File) {
        if (file.length() <= MAX_BYTES) return
        val text = file.readText()
        val tail = text.takeLast(KEEP_BYTES)
        val firstNewline = tail.indexOf('\n')
        val kept = if (firstNewline >= 0) tail.substring(firstNewline + 1) else tail
        file.writeText(kept)
    }
}
