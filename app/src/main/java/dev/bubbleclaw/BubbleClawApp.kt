package dev.bubbleclaw

import android.app.Application
import android.util.Log

class BubbleClawApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        Diag.init(this)
        CrashReporter.install(this)
        Diag.log("app start")
        lastCrashFile()?.let { Log.w("BubbleClaw", "previous crash: $it") }
    }

    private fun lastCrashFile(): String? = runCatching {
        filesDir.listFiles { f -> f.name.startsWith("crash-") }
            ?.maxByOrNull { it.lastModified() }
            ?.readText()
    }.getOrNull()
}
