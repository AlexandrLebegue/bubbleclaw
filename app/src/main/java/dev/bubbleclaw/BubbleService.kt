package dev.bubbleclaw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

class BubbleService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel("bubble", "Bubble", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
        val n: Notification = Notification.Builder(this, "bubble")
            .setContentTitle("BubbleClaw")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(1, n)
        return START_STICKY
    }
}
