package dev.bubbleclaw

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes any uncaught exception to filesDir and posts it to the configured gateway so the
 * operator can read the stack trace without adb. Never blocks the crash path for long.
 */
object CrashReporter {

    private const val MAX_TRACE = 6000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                val trace = writer.toString().take(MAX_TRACE)
                val stamp = System.currentTimeMillis()
                runCatching { File(app.filesDir, "crash-$stamp.txt").writeText(trace) }
                val sender = Thread { runCatching { post(app, trace) } }
                sender.isDaemon = true
                sender.start()
                sender.join(2500)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun post(app: Context, trace: String) {
        val base = AppPrefs.baseUrl.trimEnd('/').let { if (it.endsWith("/v1")) it else "$it/v1" }
        val token = AppPrefs.token
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE).format(Date())
        val header = "CRASH BubbleClaw ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · $stamp"
        val payload = JSONObject()
            .put("model", AppPrefs.model)
            .put("user", "android:crash")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "$header\n\n```\n$trace\n```\n\nRapport automatique de crash, aucune action attendue de ta part.")
                )
            )
            .put("stream", false)

        val connection = URL("$base/chat/completions").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 4000
        connection.readTimeout = 6000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        try {
            connection.outputStream.use { it.write(bytes) }
            connection.responseCode
            runCatching { connection.inputStream?.close() }
        } finally {
            connection.disconnect()
        }
    }
}
