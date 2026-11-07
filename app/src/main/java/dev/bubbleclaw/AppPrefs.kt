package dev.bubbleclaw

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for persisted configuration.
 * Defaults match the contract: a local OpenClaw gateway, no control mode.
 */
object AppPrefs {
    private const val FILE = "bubbleclaw"
    const val BUBBLE_SIZE_MIN_DP = 32
    const val BUBBLE_SIZE_MAX_DP = 72
    const val BUBBLE_SIZE_DEFAULT_DP = 48

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    var baseUrl: String
        get() = prefs.getString("baseUrl", "http://adresse-du-gateway:18789/v1")!!
        set(value) = prefs.edit().putString("baseUrl", value.trim()).apply()

    var token: String
        get() = prefs.getString("token", "")!!
        set(value) = prefs.edit().putString("token", value.trim()).apply()

    var model: String
        get() = prefs.getString("model", "openclaw/boss")!!
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    var sessionId: String
        get() = prefs.getString("sessionId", "android:bubbleclaw")!!
        set(value) = prefs.edit().putString("sessionId", value.trim()).apply()

    var autoScreenshot: Boolean
        get() = prefs.getBoolean("autoScreenshot", true)
        set(value) = prefs.edit().putBoolean("autoScreenshot", value).apply()

    var includeVisibleText: Boolean
        get() = prefs.getBoolean("includeVisibleText", false)
        set(value) = prefs.edit().putBoolean("includeVisibleText", value).apply()

    var controlMode: Boolean
        get() = prefs.getBoolean("controlMode", false)
        set(value) = prefs.edit().putBoolean("controlMode", value).apply()

    var bubbleEnabled: Boolean
        get() = prefs.getBoolean("bubbleEnabled", true)
        set(value) = prefs.edit().putBoolean("bubbleEnabled", value).apply()

    var streamEnabled: Boolean
        get() = prefs.getBoolean("streamEnabled", true)
        set(value) = prefs.edit().putBoolean("streamEnabled", value).apply()

    var crashReportsEnabled: Boolean
        get() = prefs.getBoolean("crashReportsEnabled", true)
        set(value) = prefs.edit().putBoolean("crashReportsEnabled", value).apply()

    var bubbleSkin: BubbleSkin
        get() = BubbleSkin.fromName(prefs.getString("bubbleSkin", BubbleSkin.Lobster.name))
        set(value) = prefs.edit().putString("bubbleSkin", value.name).apply()

    var bubbleSizeDp: Int
        get() = prefs.getInt("bubbleSizeDp", BUBBLE_SIZE_DEFAULT_DP)
            .coerceIn(BUBBLE_SIZE_MIN_DP, BUBBLE_SIZE_MAX_DP)
        set(value) = prefs.edit()
            .putInt("bubbleSizeDp", value.coerceIn(BUBBLE_SIZE_MIN_DP, BUBBLE_SIZE_MAX_DP))
            .apply()

    var bubbleY: Int
        get() = prefs.getInt("bubbleY", -1)
        set(value) = prefs.edit().putInt("bubbleY", value).apply()

    var bubbleOnLeft: Boolean
        get() = prefs.getBoolean("bubbleOnLeft", false)
        set(value) = prefs.edit().putBoolean("bubbleOnLeft", value).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboardingDone", false)
        set(value) = prefs.edit().putBoolean("onboardingDone", value).apply()

    var sendScreenshot: Boolean
        get() = prefs.getBoolean("sendScreenshot", false)
        set(value) = prefs.edit().putBoolean("sendScreenshot", value).apply()

    var activeConversationId: String
        get() = prefs.getString("activeConversationId", "")!!
        set(value) = prefs.edit().putString("activeConversationId", value).apply()
}
