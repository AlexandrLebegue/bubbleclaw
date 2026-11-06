package dev.bubbleclaw

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var startButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<ScrollView>(R.id.main_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val slot = findViewById<FrameLayout>(R.id.mascot_slot)
        slot.addView(
            LobsterView(this),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val onboarding = findViewById<LinearLayout>(R.id.onboarding_card)
        onboarding.visibility = if (AppPrefs.onboardingDone) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.onboarding_done).setOnClickListener {
            AppPrefs.onboardingDone = true
            onboarding.visibility = View.GONE
        }

        startButton = findViewById(R.id.start_button)
        startButton.setOnClickListener { toggleBubble() }
        findViewById<MaterialButton>(R.id.settings_button).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        Permissions.buildChecklist(this, findViewById(R.id.permissions_container))
        updateStartButton()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Permissions.REQUEST_NOTIFICATIONS) {
            Permissions.buildChecklist(this, findViewById(R.id.permissions_container))
        }
    }

    private fun updateStartButton() {
        startButton.setText(
            if (BubbleService.isRunning()) R.string.settings_bubble_stop else R.string.settings_bubble_start
        )
    }

    private fun toggleBubble() {
        if (BubbleService.isRunning()) {
            BubbleService.stop(this)
            AppPrefs.bubbleEnabled = false
            Toast.makeText(this, "Bulle arrêtée", Toast.LENGTH_SHORT).show()
            startButton.postDelayed({ updateStartButton() }, 300)
            return
        }
        if (!Permissions.overlayEnabled(this)) {
            Toast.makeText(this, "Autorise l'affichage par-dessus les autres apps.", Toast.LENGTH_LONG).show()
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
            )
            return
        }
        BubbleService.start(this)
        AppPrefs.bubbleEnabled = true
        if (!Permissions.accessibilityEnabled(this)) {
            Toast.makeText(
                this,
                "Active le service d'accessibilité pour capturer l'écran.",
                Toast.LENGTH_LONG
            ).show()
        }
        if (Build.VERSION.SDK_INT >= 33 && !Permissions.hasNotificationPermission(this)) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                Permissions.REQUEST_NOTIFICATIONS
            )
        }
        startButton.postDelayed({ updateStartButton() }, 400)
    }
}
