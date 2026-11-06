package dev.bubbleclaw

import android.app.Application

class BubbleClawApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
    }
}
