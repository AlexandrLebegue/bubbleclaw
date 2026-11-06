package dev.bubbleclaw

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Live permission status + checklist rows shared by onboarding and settings. */
object Permissions {

    const val REQUEST_NOTIFICATIONS = 4102

    fun overlayEnabled(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = ComponentName(context, ScreenReaderService::class.java)
        return enabled.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == expected
        }
    }

    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun buildChecklist(activity: Activity, container: LinearLayout) {
        container.removeAllViews()

        addRow(
            activity, container,
            activity.getString(R.string.settings_overlay),
            overlayEnabled(activity),
            action = {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            }
        )

        addRow(
            activity, container,
            activity.getString(R.string.settings_accessibility),
            accessibilityEnabled(activity),
            action = {
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )

        addRow(
            activity, container,
            activity.getString(R.string.settings_notifications),
            notificationsEnabled(activity),
            action = {
                if (!notificationsEnabled(activity) && Build.VERSION.SDK_INT >= 33) {
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_NOTIFICATIONS
                    )
                } else {
                    openNotificationSettings(activity)
                }
            }
        )
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(intent) }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return notificationsEnabled(context)
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun addRow(
        activity: Activity,
        container: LinearLayout,
        title: String,
        granted: Boolean,
        action: () -> Unit
    ) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 10), 0, dp(activity, 10))
        }

        val labels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        labels.addView(
            TextView(activity).apply {
                text = title
                setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
        )
        labels.addView(
            TextView(activity).apply {
                text = activity.getString(
                    if (granted) R.string.settings_granted else R.string.settings_missing
                )
                setTextColor(
                    ContextCompat.getColor(
                        activity,
                        if (granted) R.color.success else R.color.danger
                    )
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
        )
        row.addView(labels)

        row.addView(
            TextView(activity).apply {
                text = if (granted) "Gérer" else activity.getString(R.string.settings_open)
                setTextColor(ContextCompat.getColor(activity, R.color.coral_light))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 4), dp(activity, 6))
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
        )

        container.addView(row)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
