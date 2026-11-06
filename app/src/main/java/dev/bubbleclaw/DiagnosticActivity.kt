package dev.bubbleclaw

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticActivity : AppCompatActivity() {

    private var crashReport = ""
    private var logReport = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostic)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<ScrollView>(R.id.diag_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        findViewById<TextView>(R.id.diag_back).setOnClickListener { finish() }

        findViewById<TextView>(R.id.diag_info).text = buildInfo()

        val latest = latestCrash()
        val crashView = findViewById<TextView>(R.id.diag_crash_text)
        val crashTime = findViewById<TextView>(R.id.diag_crash_time)
        if (latest == null) {
            crashReport = getString(R.string.diag_no_crash)
            crashTime.text = crashReport
            crashView.text = getString(R.string.diag_no_crash)
        } else {
            val stamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE)
                .format(Date(latest.lastModified()))
            crashReport = latest.readText()
            crashTime.text = "Horodatage : $stamp"
            crashView.text = crashReport
        }

        val logView = findViewById<TextView>(R.id.diag_log_text)
        logReport = Diag.lastLines(20)
        logView.text = logReport.ifBlank { getString(R.string.diag_no_log) }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.diag_copy_crash)
            .setOnClickListener { copy(crashReport) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.diag_copy_log)
            .setOnClickListener { copy(logReport) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.diag_share_crash)
            .setOnClickListener { share("BubbleClaw crash", crashReport) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.diag_share_log)
            .setOnClickListener { share("BubbleClaw journal", logReport) }
    }

    private fun buildInfo(): String {
        val version = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("inconnue")
        val accessibility = if (Permissions.accessibilityEnabled(this)) {
            getString(R.string.diag_granted)
        } else {
            getString(R.string.diag_missing)
        }
        val overlay = if (Permissions.overlayEnabled(this)) {
            getString(R.string.diag_granted)
        } else {
            getString(R.string.diag_missing)
        }
        val bubble = if (BubbleService.isRunning()) {
            getString(R.string.diag_running)
        } else {
            getString(R.string.diag_stopped)
        }
        return buildString {
            appendLine("Version : $version")
            appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Accessibilité : $accessibility")
            appendLine("Overlay : $overlay")
            appendLine("Bulle : $bubble")
            appendLine("URL gateway : ${AppPrefs.baseUrl}")
            append("Modèle : ${AppPrefs.model}")
        }
    }

    private fun latestCrash(): File? = runCatching {
        filesDir.listFiles { file -> file.name.startsWith("crash-") && file.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }
    }.getOrNull()

    private fun copy(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.diag_title), text))
        Toast.makeText(this, getString(R.string.diag_copied), Toast.LENGTH_SHORT).show()
    }

    private fun share(subject: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.diag_share_chooser))) }
            .onFailure { Toast.makeText(this, "Partage indisponible.", Toast.LENGTH_SHORT).show() }
    }
}
