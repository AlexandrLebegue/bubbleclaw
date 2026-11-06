package dev.bubbleclaw

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    private lateinit var bubbleButton: MaterialButton
    private lateinit var testResult: TextView
    private lateinit var testButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<ScrollView>(R.id.settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        findViewById<TextView>(R.id.back_button).setOnClickListener { finish() }

        val baseUrl = findViewById<EditText>(R.id.base_url)
        val token = findViewById<EditText>(R.id.token)
        val model = findViewById<EditText>(R.id.model)
        val session = findViewById<EditText>(R.id.session)

        baseUrl.setText(AppPrefs.baseUrl)
        token.setText(AppPrefs.token)
        model.setText(AppPrefs.model)
        session.setText(AppPrefs.sessionId)

        baseUrl.doAfterTextChanged { AppPrefs.baseUrl = it?.toString().orEmpty() }
        token.doAfterTextChanged { AppPrefs.token = it?.toString().orEmpty() }
        model.doAfterTextChanged { AppPrefs.model = it?.toString().orEmpty() }
        session.doAfterTextChanged { AppPrefs.sessionId = it?.toString().orEmpty() }

        val autoScreenshot = findViewById<SwitchMaterial>(R.id.switch_auto_screenshot)
        val visibleText = findViewById<SwitchMaterial>(R.id.switch_visible_text)
        val control = findViewById<SwitchMaterial>(R.id.switch_control)
        val stream = findViewById<SwitchMaterial>(R.id.switch_stream)
        val crashReports = findViewById<SwitchMaterial>(R.id.switch_crash_reports)

        autoScreenshot.isChecked = AppPrefs.autoScreenshot
        visibleText.isChecked = AppPrefs.includeVisibleText
        control.isChecked = AppPrefs.controlMode
        stream.isChecked = AppPrefs.streamEnabled
        crashReports.isChecked = AppPrefs.crashReportsEnabled

        autoScreenshot.setOnCheckedChangeListener { _, checked -> AppPrefs.autoScreenshot = checked }
        visibleText.setOnCheckedChangeListener { _, checked -> AppPrefs.includeVisibleText = checked }
        control.setOnCheckedChangeListener { _, checked ->
            AppPrefs.controlMode = checked
            if (checked && !Permissions.accessibilityEnabled(this)) {
                Toast.makeText(
                    this,
                    "Mode contrôle enregistré, mais le service d'accessibilité est coupé : active-le pour agir sur l'écran.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        stream.setOnCheckedChangeListener { _, checked -> AppPrefs.streamEnabled = checked }
        crashReports.setOnCheckedChangeListener { _, checked -> AppPrefs.crashReportsEnabled = checked }

        testResult = findViewById(R.id.test_result)
        testButton = findViewById(R.id.test_button)
        testButton.setOnClickListener { testConnection() }

        bubbleButton = findViewById(R.id.bubble_button)
        bubbleButton.setOnClickListener { toggleBubble() }

        findViewById<MaterialButton>(R.id.diagnostic_button).setOnClickListener {
            startActivity(android.content.Intent(this, DiagnosticActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.clear_chat_button).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(getString(R.string.chat_clear_confirm))
                .setNegativeButton(getString(R.string.chat_clear_no), null)
                .setPositiveButton(getString(R.string.chat_clear_yes)) { _, _ ->
                    ConversationStore.clear(this)
                    BubbleService.clearChatIfOpen()
                    Toast.makeText(this, getString(R.string.chat_cleared), Toast.LENGTH_SHORT)
                        .show()
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        Permissions.buildChecklist(this, findViewById(R.id.permissions_container))
        updateBubbleButton()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Permissions.REQUEST_NOTIFICATIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    this,
                    "Notifications refusées : Android peut masquer l'état de la bulle.",
                    Toast.LENGTH_LONG
                ).show()
            }
            Permissions.buildChecklist(this, findViewById(R.id.permissions_container))
        }
    }

    private fun testConnection() {
        ApiClient.validateBaseUrl(AppPrefs.baseUrl)?.let { error ->
            testResult.setTextColor(ContextCompat.getColor(this, R.color.danger))
            testResult.text = error
            return
        }
        testButton.isEnabled = false
        testResult.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        testResult.text = getString(R.string.settings_testing)
        Thread {
            var message: String
            var ok: Boolean
            try {
                val models = ApiClient.fromPrefs().listModels()
                ok = true
                message = if (models.isEmpty()) {
                    "Gateway joignable, aucun modèle listé."
                } else {
                    "Gateway joignable. Modèles : ${models.joinToString(", ")}"
                }
            } catch (e: ApiException) {
                ok = false
                message = e.message ?: ApiClient.UNREACHABLE
            } catch (e: Exception) {
                ok = false
                message = e.message ?: ApiClient.UNREACHABLE
            }
            Diag.log(if (ok) "gateway test ok" else "gateway test failed")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                testResult.text = message
                testResult.setTextColor(
                    ContextCompat.getColor(this, if (ok) R.color.success else R.color.danger)
                )
                testButton.isEnabled = true
            }
        }.start()
    }

    private fun updateBubbleButton() {
        bubbleButton.setText(
            if (BubbleService.isRunning()) R.string.settings_bubble_stop else R.string.settings_bubble_start
        )
    }

    private fun toggleBubble() {
        if (BubbleService.isRunning()) {
            BubbleService.stop(this)
            AppPrefs.bubbleEnabled = false
            Toast.makeText(this, "Bulle arrêtée", Toast.LENGTH_SHORT).show()
            bubbleButton.postDelayed({ updateBubbleButton() }, 300)
            return
        }
        if (!Permissions.overlayEnabled(this)) {
            Toast.makeText(this, "Autorise l'affichage par-dessus les autres apps.", Toast.LENGTH_LONG).show()
            startActivity(
                android.content.Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        AppPrefs.bubbleEnabled = true
        BubbleService.start(this)
        if (!Permissions.accessibilityEnabled(this)) {
            Toast.makeText(this, "Active aussi le service d'accessibilité.", Toast.LENGTH_LONG).show()
        }
        if (Build.VERSION.SDK_INT >= 33 && !Permissions.hasNotificationPermission(this)) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                Permissions.REQUEST_NOTIFICATIONS
            )
        }
        bubbleButton.postDelayed({ updateBubbleButton() }, 400)
    }
}
