package dev.bubbleclaw

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Writes uncaught exceptions to filesDir so the owner can inspect them locally with adb.
 * Nothing is ever uploaded: crash traces never leave the device.
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
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
