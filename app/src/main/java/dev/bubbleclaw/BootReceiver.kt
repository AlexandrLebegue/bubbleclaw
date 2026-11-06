package dev.bubbleclaw

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Restores the bubble after a reboot when the user had left it enabled. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppPrefs.bubbleEnabled) return
        if (!Settings.canDrawOverlays(context)) return
        runCatching { BubbleService.start(context) }
    }
}
